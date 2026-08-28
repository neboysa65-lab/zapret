# Динамическое обновление доменов (Firebase Remote Config)

Домены API больше **не зашиты в код намертво**. При запуске приложение
скачивает актуальные адреса из Firebase Remote Config. Если домен
заблокируют — вы просто меняете значение в консоли Firebase, и все
установленные приложения подхватывают новый адрес. **Релиз в Google Play не нужен.**

---

## Какие домены управляются

| Ключ в Remote Config | Что это | Значение по умолчанию |
|----------------------|---------|-----------------------|
| `api_base_url`   | Основной API: регистрация, тарифы, конфиги, оплата | `https://api-vlessvpn-app.vpnhub.xyz` |
| `panel_base_url` | Панель управления: метрики нод, статистика трафика | `https://panel.gw-vpn.click` |
| `sub_base_url`   | Домен раздачи подписок (задел на будущее)          | `https://sub.gw-vpn.click` |

Значения по умолчанию лежат в `lib/config/api_config.dart` — они срабатывают
только как запасной вариант, если Firebase недоступен (нет сети при первом запуске).

---

## Как это работает (шаг за шагом)

1. **Старт приложения** (`lib/main.dart`). Сразу после `Firebase.initializeApp()`
   вызывается `RemoteConfigService.instance.initialize()` — **до** отрисовки UI.

2. **Значения по умолчанию.** `RemoteConfigService` регистрирует дефолты
   (те же зашитые домены). Приложение гарантированно знает адреса, даже если
   интернета нет.

3. **Скачивание из Firebase.** Вызывается `fetchAndActivate()`:
   приложение забирает актуальные значения ключей из облака и активирует их.
   Таймаут — 10 секунд; если Firebase не ответил, остаются дефолты.

4. **Применение.** Полученные адреса переносятся в `ApiConfig`
   (`ApiConfig.baseUrl`, `ApiConfig.panelBaseUrl`, `ApiConfig.subBaseUrl`).
   Есть валидация: значение принимается, только если начинается с `http://`
   или `https://`; завершающий `/` обрезается. Опечатка в консоли не сломает приложение.

5. **Использование.** Весь остальной код читает домены только из `ApiConfig`:
   - `lib/services/api_service.dart`, `lib/services/user_api_service.dart`,
     `lib/screens/subscription_screen.dart`, `lib/screens/profile_screen.dart`,
     `lib/screens/pay_web_screen.dart` → `ApiConfig.baseUrl`
   - `lib/services/metrics_service.dart` → `ApiConfig.panelBaseUrl`

6. **Периодическое обновление.** `minimumFetchInterval` = 1 час. Не чаще раза в час
   приложение реально ходит в сеть за новыми значениями (кэш между запусками).
   Есть метод `RemoteConfigService.instance.refresh()` для принудительного
   обновления (например, повесить на кнопку «Обновить серверы»).

### Схема

```
main() ──> Firebase.initializeApp()
       └─> RemoteConfigService.initialize()
              ├─ setDefaults(зашитые домены)      // fallback
              ├─ fetchAndActivate()               // тянем из Firebase
              └─ ApiConfig.baseUrl/... = свежие значения
                        │
                        ▼
        Весь код читает ApiConfig.* (уже актуальные домены)
```

---

## Как менять домены (без обновления в Google Play)

1. Откройте [Firebase Console](https://console.firebase.google.com/) →
   выберите проект (`vleesvpn` для Android — см. `firebase.json`).
2. В левом меню: **Run → Remote Config** (Запуск → Удалённая конфигурация).
3. Первый раз — нажмите **Add parameter / Добавить параметр** и создайте три ключа
   ровно с такими именами:
   - `api_base_url`
   - `panel_base_url`
   - `sub_base_url`

   Тип — **String**. В значение впишите полный адрес со схемой, например
   `https://api-new-domain.example`.
4. Чтобы **сменить заблокированный домен** — откройте нужный ключ, впишите новый
   адрес, нажмите **Publish changes / Опубликовать**.
5. Готово. Приложения подхватят новый адрес при следующем `fetch` (в течение часа,
   либо сразу при следующем холодном старте с интернетом).

> **Важно:** пишите адрес с `http://` или `https://` и без завершающего `/`
> (слэш всё равно обрежется). Пустое или некорректное значение будет
> проигнорировано — приложение останется на предыдущем/дефолтном адресе.

### Пример: заблокировали `panel.gw-vpn.click`

1. Подняли зеркало панели на `panel2.gw-vpn.click`.
2. Firebase → Remote Config → ключ `panel_base_url` → `https://panel2.gw-vpn.click`.
3. Publish. Метрики нод снова работают, обновление приложения не требуется.

---

## Файлы, которые за это отвечают

- `lib/config/api_config.dart` — домены (значения по умолчанию + куда пишет Remote Config).
- `lib/services/remote_config_service.dart` — загрузка и применение значений.
- `lib/main.dart` — инициализация при старте.
- `lib/services/metrics_service.dart` — пример потребителя (`ApiConfig.panelBaseUrl`).
- `pubspec.yaml` — зависимость `firebase_remote_config`.

## Локальная отладка

Чтобы значения подтягивались мгновенно (без кэша в 1 час), временно поставьте
в `RemoteConfigService.initialize()` `minimumFetchInterval: Duration.zero`.
Не оставляйте так в релизе — это создаёт лишнюю нагрузку и риск троттлинга.
