import 'dart:async';

import 'package:firebase_remote_config/firebase_remote_config.dart';
import 'package:flutter/foundation.dart';

import '../config/api_config.dart';
import 'api_host_service.dart';

/// Сервис динамического обновления доменов через Firebase Remote Config.
///
/// Как это работает:
///   1. Задаём значения по умолчанию (совпадают с [ApiConfig]) — на случай,
///      если Firebase недоступен при первом запуске (нет сети / VPN выключен).
///   2. Скачиваем актуальные значения из Firebase и активируем их.
///   3. Переносим значения в [ApiConfig], откуда их читает весь остальной код.
///
/// Ключи в консоли Firebase Remote Config:
///   - `api_base_url`   → [ApiConfig.baseUrl] через [ApiHostService]: обычный
///     URL или wildcard-шаблон `*.api.example.com` (рандомный поддомен
///     на устройство с авто-ротацией при блокировке)
///   - `panel_base_url` → [ApiConfig.panelBaseUrl]
///   - `sub_base_url`   → [ApiConfig.subBaseUrl]
class RemoteConfigService {
  RemoteConfigService._();
  static final RemoteConfigService instance = RemoteConfigService._();

  // Названия ключей — держим в одном месте, чтобы не опечататься.
  static const String kApiBaseUrl = 'api_base_url';
  static const String kPanelBaseUrl = 'panel_base_url';
  static const String kSubBaseUrl = 'sub_base_url';

  /// Жёсткий таймаут на весь fetchAndActivate. Если Firebase зависает
  /// (провайдер рвёт соединение), не держим главный поток дольше этого
  /// времени — иначе получаем ANR.
  static const Duration _hardTimeout = Duration(seconds: 5);

  FirebaseRemoteConfig? _rc;

  /// Инициализация при старте приложения. Никогда не бросает исключение
  /// наружу — падение Remote Config не должно ронять запуск приложения.
  Future<void> initialize() async {
    try {
      final rc = FirebaseRemoteConfig.instance;
      _rc = rc;

      // Значения по умолчанию = зашитые домены (fallback). Для API берём
      // именно дефолт, а не текущий baseUrl: он может быть выбранным
      // поддоменом шаблона.
      await rc.setDefaults(<String, dynamic>{
        kApiBaseUrl: ApiConfig.defaultBaseUrl,
        kPanelBaseUrl: ApiConfig.panelBaseUrl,
        kSubBaseUrl: ApiConfig.subBaseUrl,
      });

      await rc.setConfigSettings(RemoteConfigSettings(
        // Сколько ждём ответ от Firebase, прежде чем взять дефолты.
        fetchTimeout: const Duration(seconds: 10),
        // Как часто разрешаем реально ходить в сеть за новыми значениями.
        // 1 час — компромисс: быстро реагируем на смену домена, но не спамим.
        // Для отладки можно поставить Duration.zero.
        minimumFetchInterval: const Duration(hours: 1),
      ));

      // Скачиваем + активируем свежие значения с жёстким таймаутом.
      // Firebase.fetchAndActivate() иногда зависает, когда провайдер
      // рвёт соединение (Connection closed by peer) — без этого
      // обёртки приложение может зависнуть и получить ANR.
      await rc.fetchAndActivate().timeout(_hardTimeout, onTimeout: () {
        if (kDebugMode) {
          debugPrint('RemoteConfigService: fetchAndActivate timed out '
              'after $_hardTimeout');
        }
        return false;
      });

      // Переносим в ApiConfig, чтобы весь код читал уже актуальные домены.
      _applyToApiConfig(rc);
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

  void _applyToApiConfig(FirebaseRemoteConfig rc) {
    final api = rc.getString(kApiBaseUrl);
    final panel = rc.getString(kPanelBaseUrl);
    final sub = rc.getString(kSubBaseUrl);

    // Валидирует сам: принимает и обычный URL, и wildcard-шаблон.
    ApiHostService.instance.apply(api);
    if (_looksValid(panel)) ApiConfig.panelBaseUrl = _clean(panel);
    if (_looksValid(sub)) ApiConfig.subBaseUrl = _clean(sub);

    if (kDebugMode) {
      debugPrint('RemoteConfig applied → api=${ApiConfig.baseUrl}, '
          'panel=${ApiConfig.panelBaseUrl}, sub=${ApiConfig.subBaseUrl}');
    }
  }

  /// Отсекаем мусор: пустые строки и значения без http-схемы,
  /// чтобы случайная опечатка в консоли не сломала приложение.
  bool _looksValid(String value) {
    final v = value.trim();
    return v.startsWith('http://') || v.startsWith('https://');
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
