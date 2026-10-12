import 'dart:async';
import 'dart:io';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

import '../config/api_config.dart';

/// Wildcard-шаблон из Remote Config: `*.api.example.com`
/// (схема `https://` и порт необязательны).
class ApiHostTemplate {
  const ApiHostTemplate._(this.scheme, this.suffix, this.port);

  final String scheme;
  final String suffix;
  final int? port;

  // `*.` и минимум две DNS-метки суффикса: wildcard-сертификат покрывает
  // ровно одну метку слева, а `*.com` выпустить нельзя.
  static final _pattern = RegExp(
    r'^(?:(https?)://)?\*\.((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+'
    r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(?::(\d{1,5}))?/*$',
  );

  static ApiHostTemplate? tryParse(String raw) {
    final match = _pattern.firstMatch(raw.trim().toLowerCase());
    if (match == null) return null;
    final portText = match.group(3);
    final port = portText == null ? null : int.parse(portText);
    if (port != null && (port < 1 || port > 65535)) return null;
    return ApiHostTemplate._(match.group(1) ?? 'https', match.group(2)!, port);
  }

  /// Базовый URL для метки: `https://<label>.<suffix>[:port]`.
  String build(String label) =>
      '$scheme://$label.$suffix${port == null ? '' : ':$port'}';

  /// Каноничная запись шаблона: `https://*.api.example.com`.
  @override
  String toString() => build('*');
}

/// Основной API-домен приложения ([ApiConfig.baseUrl]).
///
/// Обычный URL из Remote Config работает как раньше. Для wildcard-шаблона
/// (`*.api.example.com`) на устройстве генерируется случайная метка
/// (`k3x9a0qf7d2m.api.example.com`) и хранится между запусками. Любой сбой —
/// сетевой или ответ с ошибкой — сразу меняет метку на свежую: старая могла
/// быть заблокирована, а смена не стоит ничего, потому что все метки ведут
/// на один сервер.
class ApiHostService {
  ApiHostService({Random? random}) : _random = random ?? Random.secure();

  static final ApiHostService instance = ApiHostService();

  // Последнее применённое значение `api_base_url` (если оно не равно дефолту).
  static const _sourceKey = 'api_host_source';
  static const _labelKey = 'api_host_label';

  static const _alphabet = 'abcdefghijklmnopqrstuvwxyz0123456789';
  static const _labelLength = 12;
  static final _labelPattern = RegExp(r'^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$');

  final Random _random;

  ApiHostTemplate? _template;
  String? _label;

  /// Поднимает с диска последний `api_base_url` и метку. Remote Config может
  /// ответить нескоро или не ответить вовсе (Firebase тоже блокируют), а
  /// первые запросы уходят сразу после старта.
  Future<void> restore() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final label = prefs.getString(_labelKey);
      if (label != null && _labelPattern.hasMatch(label)) _label = label;
      final saved = prefs.getString(_sourceKey);
      if (saved != null && apply(saved, remember: false)) return;
    } catch (e) {
      _log('restore failed: $e');
    }
    apply(ApiConfig.defaultBaseUrl, remember: false);
  }

  /// Применяет значение `api_base_url`: обычный URL или wildcard-шаблон.
  /// Некорректное значение игнорируется (возвращает false).
  bool apply(String raw, {bool remember = true}) {
    final parsed = _parse(raw);
    if (parsed == null) return false;

    final template = parsed.template;
    _template = template;
    if (template == null) {
      ApiConfig.baseUrl = parsed.canonical;
    } else {
      var label = _label;
      if (label == null) {
        label = _label = _newLabel();
        unawaited(_store(_labelKey, label));
      }
      ApiConfig.baseUrl = template.build(label);
    }

    if (remember) {
      final isDefault =
          parsed.canonical == _parse(ApiConfig.defaultBaseUrl)?.canonical;
      unawaited(_store(_sourceKey, isDefault ? null : parsed.canonical));
    }
    _log('base url → ${ApiConfig.baseUrl}');
    return true;
  }

  /// Выполняет [send] с текущим базовым URL.
  ///
  /// Сбой — сетевой (DNS, TCP, TLS, обрыв, таймаут) или ответ с ошибкой
  /// (HTTP >= 400) — меняет метку на свежую и один раз повторяет запрос на
  /// новом хосте. Для запросов, которые нельзя слать вслепую, передавайте
  /// `idempotent: false`: после таймаута сервер мог успеть их обработать.
  Future<http.Response> withFailover(
    Future<http.Response> Function(String baseUrl) send, {
    bool idempotent = true,
  }) async {
    final baseUrl = ApiConfig.baseUrl;
    http.Response response;
    try {
      response = await send(baseUrl);
    } catch (error) {
      if (!isConnectionFailure(error)) rethrow;
      if (!_switchLabel(baseUrl)) rethrow;
      if (!idempotent && error is TimeoutException) rethrow;
      return send(ApiConfig.baseUrl);
    }

    if (response.statusCode >= 400 && _switchLabel(baseUrl) && idempotent) {
      return send(ApiConfig.baseUrl);
    }
    return response;
  }

  /// Ошибки уровня соединения: DNS, TCP, TLS, обрыв, таймаут.
  @visibleForTesting
  static bool isConnectionFailure(Object error) =>
      error is SocketException ||
      error is TlsException ||
      error is HttpException ||
      error is TimeoutException ||
      error is http.ClientException;

  /// Меняет метку на свежую. false — если шаблон не задан (обычный URL):
  /// менять нечего.
  bool _switchLabel(String failedBase) {
    final template = _template;
    if (template == null) return false;
    // Пока падал запрос, метку мог сменить другой запрос.
    if (ApiConfig.baseUrl != failedBase) return true;
    final label = _label = _newLabel();
    ApiConfig.baseUrl = template.build(label);
    unawaited(_store(_labelKey, label));
    _log('switched → ${ApiConfig.baseUrl}');
    return true;
  }

  String _newLabel() => String.fromCharCodes([
        for (var i = 0; i < _labelLength; i++)
          _alphabet.codeUnitAt(_random.nextInt(_alphabet.length)),
      ]);

  static ({ApiHostTemplate? template, String canonical})? _parse(String raw) {
    final value = raw.trim();
    final template = ApiHostTemplate.tryParse(value);
    if (template != null) {
      return (template: template, canonical: template.toString());
    }
    final url = value.replaceFirst(RegExp(r'/+$'), '');
    final uri = Uri.tryParse(url);
    final valid = uri != null &&
        (uri.scheme == 'http' || uri.scheme == 'https') &&
        uri.host.isNotEmpty &&
        !url.contains(RegExp(r'[\s*]'));
    return valid ? (template: null, canonical: url) : null;
  }

  Future<void> _store(String key, String? value) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      if (value == null) {
        await prefs.remove(key);
      } else {
        await prefs.setString(key, value);
      }
    } catch (e) {
      _log('cannot persist $key: $e');
    }
  }

  void _log(String message) {
    if (kDebugMode) debugPrint('ApiHostService: $message');
  }
}
