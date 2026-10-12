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
  /// ДОМЕНОВ В БИЛДЕ НЕТ: URL приходит только из Firebase Remote Config
  /// (ключи encrypted_*, формат gwenc1 — см. RemoteConfigService). До первого
  /// успешного fetch домены пусты; после — последнее значение сохраняется
  /// в SharedPreferences и переживает отсутствие сети. НЕ удаляй пустоту:
  /// зашитый URL = цель для реверс-инжиниринга.
  static const String defaultBaseUrlEncrypted = '';
  static const String panelBaseUrlEncrypted = '';
  static const String subBaseUrlEncrypted = '';

  /// Пустой дефолт для совместимости с [ApiHostService] (fallback = no-op).
  static String get defaultBaseUrl => '';

  /// Текущий основной API-домен (регистрация, тарифы, конфиги, оплата).
  /// Remote Config key: `encrypted_api_url` — закодированный gwenc1 URL или
  /// wildcard-шаблон. Пишет сюда только [ApiHostService].
  static String baseUrl = '';

  /// Домен панели управления (метрики нод, статистика трафика).
  /// Remote Config key: `encrypted_panel_url` (gwenc1).
  static String panelBaseUrl = '';

  /// Домен раздачи подписок (subscription). Сейчас его проксирует бэкенд,
  /// но ключ заведён на будущее, чтобы менять и его без релиза.
  /// Remote Config key: `encrypted_sub_url` (gwenc1).
  static String subBaseUrl = '';

  // Локальная отладка (подставить в baseUrl вручную при необходимости):
  //   Эмулятор Android:      'http://10.0.2.2:8000'
  //   Реальное устройство:   'http://192.168.1.42:8000'
}