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
  /// Дефолтные домены хранятся ЗАКОДИРОВАННЫМИ (формат gwenc1, см.
  /// [SecretCodec]) — в билде нет открытых https:// строк. Дефолт ниже —
  /// актуальный домен media-ru. Смена домена без релиза — ключ
  /// encrypted_api_url в Firebase Remote Config (см. RemoteConfigService).
  static const String defaultBaseUrlEncrypted =
      'gwenc1:fKtm2IhqepasY5l2zXjGzXC1YL/UabSXu2KMvA==';
  static const String panelBaseUrlEncrypted =
      'gwenc1:asmOrGvEnV6Kd89rx9p8r2Tc1Gm0l7tijLw=';
  static const String subBaseUrlEncrypted =
      'gwenc1:kJ1hgqq8kqTQoZDTsbtw2dRptJe7Yoy8';

  /// Расшифрованный дефолт основного API-домена. Может быть и
  /// wildcard-шаблоном (`https://*.api.example.com`), см. [ApiHostService].
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