import 'secret_codec.dart';

/// Централизованное хранилище доменов приложения.
///
/// ВАЖНО: значения ниже — это ТОЛЬКО значения по умолчанию (fallback).
/// При старте приложения [RemoteConfigService] скачивает актуальные домены
/// из Firebase Remote Config и перезаписывает эти поля. Благодаря этому,
/// если домен заблокируют, достаточно поменять значение в панели Firebase —
/// новая версия в Google Play НЕ нужна.
///
/// Поля намеренно НЕ `const` и НЕ `final`, чтобы их можно было
/// перезаписать во время выполнения (см. RemoteConfigService.applyToApiConfig).
class ApiConfig {
  /// Защитные дефолты (формат gwenc1, см. [SecretCodec]): если Remote Config
  /// недоступен (нет сети при первом запуске, Firebase заблокирован),
  /// приложение работает на них. Уделить их = оставить чистую установку
  /// без API. Remote Config перекрывает дефолт всегда, когда отвечает.
  static const String defaultBaseUrlEncrypted =
      'gwenc1:0WWwb9nTsHqFtnPSoL1kxs1wtWC/1Gm0l7tijLw=';
  static const String panelBaseUrlEncrypted =
      'gwenc1:asmOrGvEnV6Kd89rx9p8r2Tc1Gm0l7tijLw=';

  /// Подписки проксируются бэкендом, ключ зашит пустым (не используется).
  static const String subBaseUrlEncrypted = '';

  /// Расшифрованный дефолт основного API-домена (fallback Remote Config'а).
  static String get defaultBaseUrl => SecretCodec.decode(defaultBaseUrlEncrypted);

  /// Текущий основной API-домен (регистрация, тарифы, конфиги, оплата).
  /// Remote Config key: `encrypted_api_url` — закодированный gwenc1 URL или
  /// wildcard-шаблон. Пишет сюда только [ApiHostService].
  static String baseUrl = SecretCodec.decode(defaultBaseUrlEncrypted);

  /// Домен панели управления (метрики нод, статистика трафика).
  /// Remote Config key: `encrypted_panel_url` (gwenc1).
  static String panelBaseUrl = SecretCodec.decode(panelBaseUrlEncrypted);

  /// Домен раздачи подписок (subscription). Сейчас его проксирует бэкенд,
  /// но ключ заведён на будущее, чтобы менять и его без релиза.
  /// Remote Config key: `encrypted_sub_url` (gwenc1).
  static String subBaseUrl = SecretCodec.decode(subBaseUrlEncrypted);

  // Локальная отладка (подставить в baseUrl вручную при необходимости):
  //   Эмулятор Android:      'http://10.0.2.2:8000'
  //   Реальное устройство:   'http://192.168.1.42:8000'
}