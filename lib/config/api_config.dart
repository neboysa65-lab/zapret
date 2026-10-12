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
  /// Запасной основной API-домен: пока Remote Config не ответил и с прошлых
  /// запусков ничего не сохранилось. Может быть и wildcard-шаблоном
  /// (`https://*.api.example.com`), см. [ApiHostService].
  static const String defaultBaseUrl = 'https://vleapi.vpnhub.xyz';

  /// Текущий основной API-домен (регистрация, тарифы, конфиги, оплата).
  /// Remote Config key: `api_base_url` — обычный URL или wildcard-шаблон
  /// `*.api.example.com`. Пишет сюда только [ApiHostService].
  static String baseUrl = defaultBaseUrl;

  /// Домен панели управления (метрики нод, статистика трафика).
  /// Remote Config key: `panel_base_url`
  static String panelBaseUrl = 'https://panel.gw-vpn.click';

  /// Домен раздачи подписок (subscription). Сейчас его проксирует бэкенд,
  /// но ключ заведён на будущее, чтобы менять и его без релиза.
  /// Remote Config key: `sub_base_url`
  static String subBaseUrl = 'https://sub.gw-vpn.click';

  // Локальная отладка (подставить в baseUrl вручную при необходимости):
  //   Эмулятор Android:      'http://10.0.2.2:8000'
  //   Реальное устройство:   'http://192.168.1.42:8000'
}