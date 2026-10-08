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
/// (`k3x9a0qf7d2m.api.example.com`) и хранится между запусками. Если запрос
/// упал на уровне соединения, а свежая метка отвечает, домен меняется на неё
/// и запрос повторяется.
class ApiHostService {
  ApiHostService({
    http.Client? client,
    Random? random,
    DateTime Function()? now,
    Duration probeTimeout = const Duration(seconds: 5),
  })  : _client = client ?? http.Client(),
        _random = random ?? Random.secure(),
        _now = now ?? DateTime.now,
        _probeTimeout = probeTimeout;

  static final ApiHostService instance = ApiHostService();

  // Последнее применённое значение `api_base_url` (если оно не равно дефолту).
  static const _sourceKey = 'api_host_source';
  static const _labelKey = 'api_host_label';

  // Эндпоинт без авторизации: любой HTTP-ответ доказывает, что DNS, TLS
  // и маршрут до сервера работают.
  static const _probePath = '/api/v1/tariffs';

  static const _alphabet = 'abcdefghijklmnopqrstuvwxyz0123456789';
  static const _labelLength = 12;
  static final _labelPattern = RegExp(r'^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$');

  // Свежих меток на одну проверку.
  static const _candidates = 2;
  // Не больше стольких смен метки за окно: защита от «дрожания» на плохой сети.
  static const _maxRotations = 3;
  static const _rotationWindow = Duration(hours: 1);
  // Пауза после проверки, которая ничего не сменила, чтобы не засыпать сеть
  // пробами при каждом упавшем запросе.
  static const _cooldown = Duration(seconds: 20);

  final http.Client _client;
  final Random _random;
  final DateTime Function() _now;
  final Duration _probeTimeout;

  ApiHostTemplate? _template;
  String? _label;
  Future<bool>? _rotation;
  DateTime _retryAfter = DateTime.fromMillisecondsSinceEpoch(0);
  final List<DateTime> _rotations = [];

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

  /// Выполняет [send] с текущим базовым URL. При сбое соединения проверяет
  /// хост и, если метку удалось сменить, повторяет запрос один раз на новом.
  ///
  /// HTTP-ответы (в том числе 4xx/5xx) сюда не попадают: [send] должен
  /// возвращать ответ, а статус разбирать снаружи. Для запросов, которые
  /// нельзя повторять вслепую, передавайте `idempotent: false`: после таймаута
  /// сервер мог успеть их обработать.
  Future<T> withFailover<T>(
    Future<T> Function(String baseUrl) send, {
    bool idempotent = true,
  }) async {
    final baseUrl = ApiConfig.baseUrl;
    try {
      return await send(baseUrl);
    } catch (error) {
      if (!isConnectionFailure(error) || !await _recover(baseUrl)) rethrow;
      if (!idempotent && error is TimeoutException) rethrow;
      return send(ApiConfig.baseUrl);
    }
  }

  /// Ошибки уровня соединения: DNS, TCP, TLS, обрыв, таймаут.
  @visibleForTesting
  static bool isConnectionFailure(Object error) =>
      error is SocketException ||
      error is TlsException ||
      error is HttpException ||
      error is TimeoutException ||
      error is http.ClientException;

  /// true, если домен уже сменили и упавший запрос стоит повторить.
  Future<bool> _recover(String failedBase) {
    final template = _template;
    if (template == null) return Future.value(false);
    if (failedBase != ApiConfig.baseUrl) return Future.value(true);
    return _rotation ??=
        _rotate(template, failedBase).whenComplete(() => _rotation = null);
  }

  Future<bool> _rotate(ApiHostTemplate template, String failedBase) async {
    try {
      final now = _now();
      if (now.isBefore(_retryAfter)) return false;
      _rotations.removeWhere((at) => now.difference(at) >= _rotationWindow);
      if (_rotations.length >= _maxRotations) return false;

      // Сменить метку имеет смысл, только если текущая молчит, а свежая нет;
      // если молчат обе, то нет сети или забит IP/вся зона, метка не поможет.
      final labels = [for (var i = 0; i < _candidates; i++) _newLabel()];
      final current = _reachable(failedBase);
      final fresh = [
        for (final label in labels) _reachable(template.build(label)),
      ];

      String? chosen;
      if (!await current) {
        for (var i = 0; i < labels.length && chosen == null; i++) {
          if (await fresh[i]) chosen = labels[i];
        }
      }

      // Пока шли пробы, Remote Config мог выдать другой адрес.
      if (ApiConfig.baseUrl != failedBase) return true;
      if (chosen == null) {
        _retryAfter = now.add(_cooldown);
        return false;
      }

      _label = chosen;
      _rotations.add(now);
      ApiConfig.baseUrl = template.build(chosen);
      unawaited(_store(_labelKey, chosen));
      _log('rotated → ${ApiConfig.baseUrl}');
      return true;
    } catch (e) {
      _log('rotation failed: $e');
      return false;
    }
  }

  Future<bool> _reachable(String baseUrl) async {
    try {
      await _client
          .get(Uri.parse('$baseUrl$_probePath'))
          .timeout(_probeTimeout);
      return true;
    } catch (_) {
      return false;
    }
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
