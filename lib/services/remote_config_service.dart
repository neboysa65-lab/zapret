import 'dart:async';

import 'package:firebase_remote_config/firebase_remote_config.dart';
import 'package:flutter/foundation.dart';

import '../config/api_config.dart';
import '../config/secret_codec.dart';
import 'api_host_service.dart';

/// Сервис динамического обновления доменов через Firebase Remote Config.
///
/// Как это работает:
///   1. Задаём значения по умолчанию (совпадают с [ApiConfig]) — на случай,
///      если Firebase недоступен при первом запуске (нет сети / VPN выключен).
///   2. Скачиваем актуальные значения из Firebase и активируем их.
///   3. Переносим значения в [ApiConfig], откуда их читает весь остальной код.
///
/// Ключи в консоли Firebase Remote Config (значения в формате gwenc1,
/// см. [SecretCodec]; открытые api_base_url/panel_base_url/sub_base_url
/// из старых версий консоли намеренно НЕ читаются — в JSON Remote Config
/// не должно быть открытых URL для новых версий):
///   - `encrypted_api_url`   → [ApiConfig.baseUrl] через [ApiHostService]:
///     закодированный URL или wildcard-шаблон `*.api.example.com`
///     (рандомный поддомен на устройство с авто-ротацией при блокировке)
///   - `encrypted_panel_url` → [ApiConfig.panelBaseUrl]
///   - `encrypted_sub_url`   → [ApiConfig.subBaseUrl]
class RemoteConfigService {
  RemoteConfigService._();
  static final RemoteConfigService instance = RemoteConfigService._();

  // Названия ключей — держим в одном месте, чтобы не опечататься.
  static const String kEncryptedApiUrl = 'encrypted_api_url';
  static const String kEncryptedPanelUrl = 'encrypted_panel_url';
  static const String kEncryptedSubUrl = 'encrypted_sub_url';

  /// Таймаут на один fetchAndActivate (защита от зависания, когда
  /// провайдер рвёт соединение — без него получаем ANR).
  static const Duration _hardTimeout = Duration(seconds: 20);

  /// Штатный период фонового re-fetch в живом процессе: смена домена
  /// доезжает без перезапуска приложения.
  static const Duration _steadyRefreshInterval = Duration(minutes: 10);

  /// Попыток первой серии (интервал удваивается: 3с, 6с, 12с, 24с, 48с).
  static const int _maxBootRetries = 5;

  FirebaseRemoteConfig? _rc;
  Timer? _steadyTimer;

  /// Завершается при первом успешно применённом конфиге (baseUrl не пуст).
  final Completer<void> _firstApplied = Completer<void>();

  /// Инициализация при старте приложения. Никогда не бросает исключение
  /// наружу — падение Remote Config не должно ронять запуск приложения.
  Future<void> initialize() async {
    try {
      final rc = FirebaseRemoteConfig.instance;
      _rc = rc;

      // Зашитых доменов в билде НЕТ — дефолты пустые. Значение API
      // применяем только когда оно реально пришло из Remote Config
      // (source == valueRemote), иначе затирали бы сохранённый домен.
      await rc.setDefaults(<String, dynamic>{
        kEncryptedApiUrl: ApiConfig.defaultBaseUrlEncrypted,
        kEncryptedPanelUrl: ApiConfig.panelBaseUrlEncrypted,
        kEncryptedSubUrl: ApiConfig.subBaseUrlEncrypted,
      });

      await rc.setConfigSettings(RemoteConfigSettings(
        fetchTimeout: const Duration(seconds: 30),
        // Лимитов на частоту fetch больше нет: конфиг критичен (без него
        // нет baseUrl), Firebase троттлит только по своей квоте проекта.
        minimumFetchInterval: Duration.zero,
      ));

      // Первая серия fetch'ей с повторами — в фоне; после успеха конфиг
      // продолжит обновляться штатным steady-циклом.
      unawaited(_fetchLoop());
    } catch (e, s) {
      // Нет сети / Firebase недоступен — работаем на дефолтах ApiConfig.
      if (kDebugMode) {
        debugPrint('RemoteConfigService.initialize failed: $e\n$s');
      }
    }
  }

  /// Принудительно обновить значения во время работы приложения
  /// (например, по кнопке «Обновить серверы»). Возвращает true, если
  /// применились новые значения.
  Future<bool> refresh() async {
    final rc = _rc;
    if (rc == null) return false;
    try {
      final activated = await rc.fetchAndActivate().timeout(
        _hardTimeout,
        onTimeout: () => false,
      );
      _applyToApiConfig(rc);
      return activated;
    } catch (e) {
      if (kDebugMode) debugPrint('RemoteConfigService.refresh failed: $e');
      return false;
    }
  }

  /// Один fetchAndActivate + применение. Возвращает true, если после
  /// попытки baseUrl известен (конфиг пришёл или был в кэше).
  Future<bool> _fetchOnce() async {
    final rc = _rc;
    if (rc == null) return false;
    try {
      await rc.fetchAndActivate().timeout(
        _hardTimeout,
        onTimeout: () => false,
      );
      _applyToApiConfig(rc);
      return ApiConfig.baseUrl.isNotEmpty;
    } catch (e) {
      if (kDebugMode) debugPrint('RemoteConfigService: fetch failed: $e');
      return false;
    }
  }

  /// Стартовая серия попыток + фоновый re-fetch на всю жизнь процесса.
  /// Раньше конфиг читался только при старте: юзер с приложением в памяти
  /// никогда не получал новый домен из консоли.
  Future<void> _fetchLoop() async {
    var delay = const Duration(seconds: 3);
    for (var attempt = 0; attempt < _maxBootRetries; attempt++) {
      if (ApiConfig.baseUrl.isNotEmpty) break;
      final ok = await _fetchOnce();
      if (ok) break;
      if (attempt < _maxBootRetries - 1) {
        await Future.delayed(delay);
        delay *= 2;
      }
    }
    _steadyTimer ??= Timer.periodic(
      _steadyRefreshInterval,
      (_) => unawaited(refresh()),
    );
  }

  /// Ждёт первый успешно применённый конфиг (не дольше [timeout]).
  /// Для действий, которым нужен baseUrl прямо сейчас (регистрация, логин):
  /// без ожидания запрос на чистой установке уходил на пустой URL —
  /// отсюда красная «Ошибка подключения» при живом интернете.
  Future<bool> ensureReady({Duration timeout = const Duration(seconds: 20)}) async {
    if (ApiConfig.baseUrl.isNotEmpty) return true;
    try {
      await _firstApplied.future.timeout(timeout);
      return true;
    } on TimeoutException {
      return false;
    }
  }

  void _applyToApiConfig(FirebaseRemoteConfig rc) {
    // Только значение, реально пришедшее из Remote Config (source ==
    // valueRemote). Если Firebase не ответил, rc отдаёт дефолт из
    // setDefaults — применить его значило бы затереть сохранённый домен.
    // Значения приходят закодированными (gwenc1) — расшифровываем здесь,
    // непосредственно перед применением.
    final apiValue = rc.getValue(kEncryptedApiUrl);
    if (apiValue.source == ValueSource.valueRemote) {
      final decoded = _resolveDecoded(apiValue.asString());
      if (decoded != null) {
        // Валидирует сам: принимает и обычный URL, и wildcard-шаблон.
        ApiHostService.instance.apply(decoded);
      }
    }

    final panel = _resolveDecoded(rc.getString(kEncryptedPanelUrl));
    final sub = _resolveDecoded(rc.getString(kEncryptedSubUrl));
    if (panel != null && _looksValid(panel)) {
      ApiConfig.panelBaseUrl = _clean(panel);
    }
    if (sub != null && _looksValid(sub)) {
      ApiConfig.subBaseUrl = _clean(sub);
    }

    if (ApiConfig.baseUrl.isNotEmpty && !_firstApplied.isCompleted) {
      _firstApplied.complete();
    }

    if (kDebugMode) {
      debugPrint('RemoteConfig applied → api=${ApiConfig.baseUrl}, '
          'panel=${ApiConfig.panelBaseUrl}, sub=${ApiConfig.subBaseUrl}');
    }
  }

  /// Отсекаем мусор: пустые строки и значения без http-схемы,
  /// чтобы случайная опечатка в консоли не сломала приложение.
  bool _looksValid(String? value) {
    final v = value?.trim() ?? '';
    return v.startsWith('http://') || v.startsWith('https://');
  }

  /// Расшифровка значения из Remote Config. Битое закодированное значение
  /// (опечатка в консоли) возвращает null — источник пропускается,
  /// приложение работает на сохранённом/дефолтном домене. Plaintext
  /// (значение без префикса) проходит как есть.
  String? _resolveDecoded(String raw) {
    final trimmed = raw.trim();
    if (trimmed.isEmpty) return null;
    final decoded = SecretCodec.decode(trimmed);
    if (SecretCodec.isEncoded(trimmed) && !_looksValid(decoded)) {
      return null;
    }
    return decoded;
  }

  /// Убираем завершающий слэш, чтобы не было двойных `//` при склейке путей.
  String _clean(String value) {
    var v = value.trim();
    while (v.endsWith('/')) {
      v = v.substring(0, v.length - 1);
    }
    return v;
  }
}
