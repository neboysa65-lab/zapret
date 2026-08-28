# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Команды разработки

```bash
# Зависимости
flutter pub get

# Запуск приложения
flutter run                         # Подключенное устройство/эмулятор
flutter run -d android
flutter run -d ios

# Генерация кода (JSON сериализация)
flutter pub run build_runner build
flutter pub run build_runner watch  # Режим слежения

# Локализация
flutter gen-l10n

# Анализ кода
flutter analyze

# Сборка
flutter build apk --release
flutter build appbundle --release
flutter build ios --release
```

## Архитектура

### State Management

Приложение использует **Provider** pattern. Два основных провайдера:

- `AppProvider` (`lib/providers/app_provider.dart`) — ядро приложения: управление VPN, выбор сервера, метрики, статистика трафика, split tunneling
- `AuthProvider` (`lib/providers/auth_provider.dart`) — авторизация пользователя

### VPN Stack

Протокол **VLESS** через пакет `flutter_v2ray_plus`. Поток данных:

```
ApiService → List<VlessConfig> → AppProvider → VpnService → flutter_v2ray_plus
                                       ↑
                              MetricsService (загруженность серверов)
```

- `VpnService` (`lib/services/vpn_service.dart`) — обёртка над flutter_v2ray_plus, управляет подключением и предоставляет стримы статуса/скорости
- `VlessConfig` (`lib/models/vless_config_model.dart`) — модель конфига сервера, включает парсинг VLESS URL и нормализацию имён

### Нормализация имён серверов

Критическая логика: имена из VLESS URL сопоставляются с метриками API через нормализацию — удаление символов VLESS/APP, пробелов, ведущих нулей. Документация в `Documentation/ANALYSIS.md`.

### Умный выбор сервера

Автоматический выбор по критериям: 1) низкая загруженность (`ServerLoadTier`), 2) низкий пинг, 3) стабильность.

### Навигация

Named routes в `lib/main.dart`: `/` → SplashScreen, `/home`, `/welcome`, `/register`, `/login`, `/main`, `/subscription`.

## Ключевые конфиги

- `lib/config/api_config.dart` — URL API эндпоинтов
- `lib/config/ad_config.dart` — конфиг Yandex MobileAds
- `lib/config/app_theme.dart` — дизайн-система (тёмная тема, акцент `#A3FF00`)
- `lib/l10n/` — локализация EN/RU (ARB файлы)
- `firebase_options.dart` — Firebase конфигурация

## Интеграции

- **Firebase** — Crashlytics + Analytics (инициализируется в `main.dart`)
- **Yandex MobileAds** — баннеры (`AdBannerWidget`) и interstitial (`AdInterstitialWidget`)
- **Flutter Secure Storage** — хранение токенов авторизации

## Документация проекта

В папке `Documentation/`:
- `CURRENT_STATE.md` — текущий функционал и дизайн-система
- `ANALYSIS.md` — алгоритм нормализации имён и расчёт загруженности серверов
- `api.md` — API endpoints и форматы данных
- `ANDROID_SETUP.md` — настройка Android сборки и подпись APK
