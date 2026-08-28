# VPN Backend — Полная документация API для Flutter

Этот документ описывает **все** API-эндпоинты, бизнес-логику, модели данных и правила взаимодействия.
Предназначен для разработки Flutter-приложения — как для человека, так и для AI-агента (Claude Code).

---

## Содержание

1. [Конфигурация — Base URL](#1-конфигурация--base-url)
2. [Аутентификация — X-Seed-Hash](#2-аутентификация--x-seed-hash)
3. [Бизнес-модель и премиум](#3-бизнес-модель-и-премиум)
4. [Эндпоинты — Users](#4-эндпоинты--users)
5. [Эндпоинты — Tariffs](#5-эндпоинты--tariffs)
6. [Эндпоинты — Payments](#6-эндпоинты--payments)
7. [Страница оплаты WebView](#7-страница-оплаты-webview)
8. [Служебные эндпоинты](#8-служебные-эндпоинты)
9. [Коды ошибок](#9-коды-ошибок)
10. [Модели данных — полные схемы](#10-модели-данных--полные-схемы)
11. [Полный flow пользователя](#11-полный-flow-пользователя)
12. [Dart-примеры](#12-dart-примеры)

---

## 1. Конфигурация — Base URL

В приложении должна быть **одна точка конфигурации** URL:

| Окружение | URL |
|-----------|-----|
| Локальная сеть (Mac → телефон по WiFi) | `http://<IP-компьютера>:8000` |
| Android эмулятор | `http://10.0.2.2:8000` |
| iOS симулятор | `http://localhost:8000` |
| Продакшн | `https://<домен>` |

```dart
// lib/config/api_config.dart
class ApiConfig {
  static const String baseUrl = 'http://192.168.1.42:8000'; // менять только здесь
}
```

---

## 2. Аутентификация — X-Seed-Hash

### Принцип работы

Бэкенд не хранит пароли и не использует JWT. Аутентификация строится на BIP39-мнемонике:

1. При первой регистрации бэкенд генерирует **12-словную seed-фразу** (BIP39, английский словарь, strength=128)
2. В БД хранится только `sha256(seed_phrase)` — сам seed_phrase нигде не сохраняется
3. Приложение сохраняет seed_phrase в защищённом хранилище (`flutter_secure_storage`)
4. Каждый авторизованный запрос передаёт заголовок: `X-Seed-Hash: sha256(seed_phrase)`
5. Бэкенд находит пользователя по hash и инжектит его в контекст запроса

### Вычисление seed_hash

```
seed_hash = sha256(seed_phrase)  // lowercase hex-string, 64 символа
```

Пример:
```
seed_phrase: "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
seed_hash:   "7957ee4e7b01c2e5dbf7b3c2bde9a1d1e3e3a0e6e4c7b8a9f2d5e6c3b4a1f0d8"
```

### Побочный эффект авторизации

Каждый запрос с валидным `X-Seed-Hash` **обновляет `last_login_at`** пользователя.
Реализован кулдаун: обновление происходит не чаще одного раза в **5 минут**.

### Заголовок для защищённых эндпоинтов

```
X-Seed-Hash: <64-символьный hex SHA-256>
```

Если заголовок отсутствует или hash не найден — `401 Unauthorized`.

---

## 3. Бизнес-модель и премиум

**Freemium:** все пользователи получают бесплатный VPN.

**Премиум** (`is_premium: true`) открывает:
- Отсутствие рекламы
- Split Tunneling
- Приоритетный сервер

### Как работает премиум-подписка

- `is_premium` — булев флаг, активен пока `premium_until > now`
- При покупке нового тарифа срок **суммируется** с текущим, а не перезаписывается:
  ```
  base = premium_until  (если ещё не истёк) или now
  new_premium_until = base + tariff.duration_days
  ```
- Флаг `is_premium` выставляется в `true` автоматически при активации
- Деактивация (по истечении срока) — **не реализована автоматически** на данном этапе, проверяется на клиенте по `premium_until`

---

## 4. Эндпоинты — Users

### 4.1 Регистрация

```
POST /api/v1/users/register
```

**Аутентификация:** не требуется
**Тело запроса:** нет

**Что происходит внутри:**
1. Генерируется 12-словная BIP39 seed-фраза (английский словарь)
2. Вычисляется `sha256(seed_phrase)` — сохраняется в БД
3. Создаётся запись пользователя с `is_premium=false`
4. Seed-фраза возвращается **один раз** — бэкенд её не хранит

**Ответ `200 OK`:**
```json
{
  "user_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "seed_phrase": "witch collapse practice feed shame open despair creek road again ice least",
  "is_premium": false
}
```

| Поле | Тип | Описание |
|------|-----|----------|
| `user_id` | `string (UUID v4)` | Уникальный идентификатор пользователя |
| `seed_phrase` | `string` | 12 слов через пробел — **показать один раз, сохранить в secure storage** |
| `is_premium` | `bool` | Всегда `false` при регистрации |

> **КРИТИЧЕСКИ ВАЖНО:** seed_phrase нигде на сервере не сохраняется. Если пользователь потеряет фразу — восстановление невозможно. Приложение обязано сохранить её в `flutter_secure_storage` до закрытия экрана регистрации.

---

### 4.2 Восстановление аккаунта по seed-фразе

Отдельного эндпоинта для "входа" нет. Восстановление — это:

1. Пользователь вводит свою 12-словную seed-фразу
2. Приложение вычисляет `sha256(seed_phrase)`
3. Сохраняет в `flutter_secure_storage`
4. Делает запрос `GET /api/v1/users/me` с этим hash
5. Если пользователь найден — вход выполнен, `user_id` получен из ответа

```dart
// Проверить что фраза валидна и получить профиль
final response = await http.get(
  Uri.parse('$baseUrl/api/v1/users/me'),
  headers: {'X-Seed-Hash': sha256OfSeedPhrase},
);
// 200 — успешный вход
// 401 — фраза неверна или пользователь не существует
```

---

### 4.3 Профиль текущего пользователя

```
GET /api/v1/users/me
```

**Аутентификация:** `X-Seed-Hash` обязателен

**Ответ `200 OK`:**
```json
{
  "user_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "is_premium": true,
  "premium_until": "2025-06-01T12:00:00Z"
}
```

| Поле | Тип | Описание |
|------|-----|----------|
| `user_id` | `string (UUID v4)` | Идентификатор пользователя |
| `is_premium` | `bool` | Активна ли премиум-подписка |
| `premium_until` | `string (ISO 8601) \| null` | Дата и время окончания подписки в UTC. `null` если подписки не было |

**Используй для:**
- Определения экрана при запуске приложения
- Проверки актуальности статуса премиум
- Получения `user_id` после восстановления по seed-фразе

---

### 4.4 QR-код пользователя

```
GET /api/v1/users/me/qr
```

**Аутентификация:** `X-Seed-Hash` обязателен
**Ответ:** бинарный PNG-файл (`Content-Type: image/png`)

**Содержимое QR-кода:**
```
vpn:user:<user_id>
```
Пример: `vpn:user:3fa85f64-5717-4562-b3fc-2c963f66afa6`

**Назначение:** пользователь показывает QR администратору поддержки для идентификации (например, через Telegram-бота, который может считать QR).

**Использование в Flutter:**
```dart
Image.network(
  '$baseUrl/api/v1/users/me/qr',
  headers: {'X-Seed-Hash': seedHash},
  width: 200,
  height: 200,
)
```

Или загрузить как bytes и отобразить через `Image.memory`:
```dart
final response = await http.get(
  Uri.parse('$baseUrl/api/v1/users/me/qr'),
  headers: {'X-Seed-Hash': seedHash},
);
// response.bodyBytes — PNG-данные
Image.memory(response.bodyBytes)
```

---

### 4.5 Удаление аккаунта

```
DELETE /api/v1/users/me
```

**Аутентификация:** `X-Seed-Hash` обязателен

Удаляет пользователя и все его платежи **безвозвратно**.

**Ответ `204 No Content`** — тело пустое.

После удаления любой запрос с этим `X-Seed-Hash` вернёт `401 Unauthorized`.

---

## 5. Эндпоинты — Tariffs

### 5.1 Список активных тарифов

```
GET /api/v1/tariffs
```

**Аутентификация:** не требуется
**Тело запроса:** нет

**Ответ `200 OK`:**
```json
[
  {
    "id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
    "name": "Premium 1 месяц",
    "price": 29900,
    "currency": "RUB",
    "duration_days": 30,
    "is_active": true
  },
  {
    "id": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
    "name": "Premium 3 месяца",
    "price": 79900,
    "currency": "RUB",
    "duration_days": 90,
    "is_active": true
  }
]
```

| Поле | Тип | Описание |
|------|-----|----------|
| `id` | `string (UUID v4)` | Идентификатор тарифа |
| `name` | `string` | Отображаемое название тарифа |
| `price` | `int` | Цена в **минимальных единицах валюты** (копейки для RUB) |
| `currency` | `string` | Код валюты (`"RUB"`, `"USD"` и т.д.) |
| `duration_days` | `int` | Длительность подписки в днях |
| `is_active` | `bool` | В этом ответе всегда `true` (неактивные не возвращаются) |

> **Важно про `price`:** хранится в копейках. Для отображения делить на 100.
> `price: 29900` → отображать как `"299 ₽"`

**Возвращает пустой массив** `[]` если тарифов нет.

---

## 6. Эндпоинты — Payments

### 6.1 Создание платежа (тест-режим)

```
POST /api/v1/payments/initiate
```

**Аутентификация:** не требуется (вызывается со страницы `/pay`)
**Тело запроса (`application/json`):**
```json
{
  "user_id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "tariff_id": "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
}
```

| Поле | Тип | Описание |
|------|-----|----------|
| `user_id` | `string (UUID v4)` | Идентификатор пользователя (из `uid` query-param страницы) |
| `tariff_id` | `string (UUID v4)` | Идентификатор выбранного тарифа |

**Ответ `201 Created`:**
```json
{
  "payment_id": "c3d4e5f6-a7b8-9012-cdef-123456789012",
  "status": "paid"
}
```

**Что происходит внутри (тест-режим):**
1. Проверяет существование пользователя и активного тарифа
2. Создаёт запись `Payment` со статусом `pending`
3. Немедленно активирует премиум (суммируя срок)
4. Устанавливает `status = "paid"`

> **Тест-заглушка.** При подключении реального провайдера этот эндпоинт будет создавать платёж у провайдера и возвращать URL для редиректа. Логика активации переедет в webhook.

---

### 6.2 Webhook оплаты (вызывается платёжным провайдером)

```
POST /api/v1/payments/webhook
```

**Аутентификация:** не требуется (вызывается сервером провайдера)
**Назначение:** провайдер уведомляет бэкенд о результате платежа

**Тело запроса (`application/json`):**
```json
{
  "external_id": "yookassa-payment-uuid-here",
  "provider": "yookassa",
  "status": "paid",
  "amount": 29900,
  "currency": "RUB"
}
```

| Поле | Тип | Обяз. | Описание |
|------|-----|-------|----------|
| `external_id` | `string` | да | ID платежа на стороне провайдера |
| `provider` | `string` | да | Название провайдера (`"yookassa"`, `"cryptomus"` и т.д.) |
| `status` | `string` | да | Статус платежа: `"pending"`, `"paid"`, `"failed"` |
| `amount` | `int \| null` | нет | Сумма в минимальных единицах |
| `currency` | `string \| null` | нет | Код валюты |

**Логика обработки:**
- Бэкенд ищет платёж по `external_id + provider`
- Обновляет `payment.status`
- Если `status == "paid"` → активирует премиум пользователю (суммируя срок)
- Если платёж не найден → `404`

**Ответ `200 OK`:**
```json
{ "ok": true }
```

> **Из Flutter не вызывается.** Настраивается как URL-обработчик в личном кабинете платёжного провайдера.

---

### 6.3 Проверка статуса платежа

```
GET /api/v1/payments/check?payment_id=<uuid>
```

**Аутентификация:** `X-Seed-Hash` обязателен

**Query-параметры:**

| Параметр | Тип | Обязательный | Описание |
|----------|-----|--------------|----------|
| `payment_id` | `string (UUID v4)` | да | ID платежа, полученный при создании |

**Ответ `200 OK`:**
```json
{
  "payment_id": "c3d4e5f6-a7b8-9012-cdef-123456789012",
  "status": "paid",
  "is_premium": true
}
```

| Поле | Тип | Описание |
|------|-----|----------|
| `payment_id` | `string (UUID v4)` | Идентификатор платежа |
| `status` | `string` | Текущий статус: `"pending"` / `"paid"` / `"failed"` |
| `is_premium` | `bool` | Актуальный статус премиум пользователя после обработки |

**Стратегия поллинга в приложении:**
```dart
// После открытия WebView оплаты — опрашивать статус каждые 3 секунды
// Остановиться при status == "paid" или "failed", или по таймауту
Timer.periodic(Duration(seconds: 3), (timer) async {
  final result = await checkPayment(paymentId);
  if (result.status == 'paid' || result.status == 'failed') {
    timer.cancel();
    // обновить UI
  }
});
```

> **Важно:** платёж принадлежит текущему пользователю — бэкенд проверяет `payment.user_id == current_user.id`. Нельзя проверить чужой платёж.

---

## 7. Страница оплаты WebView

```
GET /pay?uid=<user_id>
```

**Аутентификация:** не требуется
**Ответ:** HTML-страница (рендерится в WebView)

**Query-параметры:**

| Параметр | Тип | Обязательный | Описание |
|----------|-----|--------------|----------|
| `uid` | `string (UUID v4)` | нет | UUID пользователя для привязки платежа |

**Что отображается:** карточки тарифов с названием, сроком и ценой. Кнопка "Купить" у каждого тарифа.

**Отображение цены на странице:** `price / 100` (т.е. копейки → рубли).

**Использование в Flutter:**
```dart
// Открыть WebView с uid пользователя
final payUrl = '$baseUrl/pay?uid=$userId';

// Вариант 1 — через url_launcher
await launchUrl(Uri.parse(payUrl));

// Вариант 2 — встроенный WebView (webview_flutter)
WebViewController()
  ..loadRequest(Uri.parse(payUrl));
```

**Полный flow оплаты:**
1. Загрузить тарифы → `GET /api/v1/tariffs`
2. Пользователь выбирает тариф
3. Открыть WebView: `GET /pay?uid=<user_id>`
4. Пользователь платит через форму провайдера
5. Провайдер вызывает webhook → бэкенд активирует премиум
6. Приложение опрашивает `GET /api/v1/payments/check?payment_id=...`
7. При `status == "paid"` закрыть WebView, показать экран успеха

---

## 8. Служебные эндпоинты

### Health Check

```
GET /health
```

**Аутентификация:** не требуется

**Ответ `200 OK`:**
```json
{ "status": "ok" }
```

**Использование:** проверка доступности сервера перед запуском приложения.

### Swagger UI (интерактивная документация)

```
GET /docs
```

Открыть в браузере для ручного тестирования всех эндпоинтов.

---

## 9. Коды ошибок

| HTTP-код | Когда возникает | Поле `detail` |
|----------|-----------------|---------------|
| `401 Unauthorized` | Отсутствует или неверный `X-Seed-Hash` | `"Invalid credentials"` |
| `404 Not Found` | Платёж не найден или не принадлежит пользователю | `"Payment not found"` |
| `422 Unprocessable Entity` | Невалидные данные запроса (Pydantic validation) | Массив с описанием ошибок |
| `500 Internal Server Error` | Ошибка сервера | — |

**Формат ошибки:**
```json
{
  "detail": "Invalid credentials"
}
```

**Для 422:**
```json
{
  "detail": [
    {
      "type": "missing",
      "loc": ["query", "payment_id"],
      "msg": "Field required"
    }
  ]
}
```

---

## 10. Модели данных — полные схемы

### User

| Поле | Тип БД | Описание |
|------|--------|----------|
| `id` | `UUID` PK | Идентификатор пользователя |
| `seed_hash` | `Text` UNIQUE NOT NULL | SHA-256 от seed_phrase |
| `is_premium` | `Boolean` NOT NULL, default `false` | Флаг активной премиум-подписки |
| `premium_until` | `DateTime(tz)` NULL | Дата окончания подписки (UTC) |
| `created_at` | `DateTime(tz)` NOT NULL, default `now()` | Дата регистрации |
| `serial_number` | `BigInteger` UNIQUE NOT NULL | Порядковый номер пользователя (автоинкремент) |
| `last_login_at` | `DateTime(tz)` NULL | Последняя активность (обновляется с кулдауном 5 мин) |

### Tariff

| Поле | Тип БД | Описание |
|------|--------|----------|
| `id` | `UUID` PK | Идентификатор тарифа |
| `name` | `Text` NOT NULL | Название тарифа |
| `price` | `Integer` NOT NULL | Цена в копейках/центах (минимальные единицы) |
| `currency` | `Text` NOT NULL, default `'RUB'` | Код валюты |
| `duration_days` | `Integer` NOT NULL | Длительность подписки в днях |
| `is_active` | `Boolean` NOT NULL, default `true` | Активен ли тариф |

### Payment

| Поле | Тип БД | Описание |
|------|--------|----------|
| `id` | `UUID` PK | Идентификатор платежа |
| `user_id` | `UUID` FK → `users.id` | Пользователь |
| `tariff_id` | `UUID` FK → `tariffs.id` | Тариф |
| `amount` | `Integer` NOT NULL | Сумма в минимальных единицах |
| `currency` | `Text` NOT NULL | Код валюты |
| `provider` | `Text` NOT NULL | Провайдер оплаты |
| `status` | `Text` NOT NULL, default `'pending'` | Статус: `pending` / `paid` / `failed` |
| `external_id` | `Text` NULL | ID платежа у провайдера |
| `created_at` | `DateTime(tz)` NOT NULL, default `now()` | Дата создания |

### Settings (только бот, не через API)

| Поле | Тип БД | Описание |
|------|--------|----------|
| `key` | `Text` PK | Ключ настройки |
| `value` | `Text` NOT NULL | Значение настройки |

> Таблица `settings` используется Telegram-ботом (runtime-конфигурация). Через REST API не доступна.

---

## 11. Полный flow пользователя

```
┌─────────────────────────────────────────────────────────────┐
│  ПЕРВЫЙ ЗАПУСК                                               │
│                                                              │
│  POST /api/v1/users/register                                 │
│  ← { user_id, seed_phrase, is_premium: false }              │
│                                                              │
│  Сохранить seed_phrase в flutter_secure_storage             │
│  Показать seed_phrase пользователю (один раз!)              │
└─────────────────────────────────────────────────────────────┘
              │
              ▼
┌─────────────────────────────────────────────────────────────┐
│  КАЖДЫЙ ЗАПУСК                                               │
│                                                              │
│  Читать seed_phrase из flutter_secure_storage               │
│  Вычислить seed_hash = sha256(seed_phrase)                  │
│                                                              │
│  GET /api/v1/users/me  (X-Seed-Hash: seed_hash)            │
│  ← { user_id, is_premium, premium_until }                   │
│                                                              │
│  is_premium == true  → главный экран (премиум)              │
│  is_premium == false → главный экран (бесплатный)           │
└─────────────────────────────────────────────────────────────┘
              │
              ▼
┌─────────────────────────────────────────────────────────────┐
│  ВОССТАНОВЛЕНИЕ АККАУНТА (seed-фраза утеряна/новый телефон) │
│                                                              │
│  Пользователь вводит 12 слов вручную                        │
│  Вычислить seed_hash = sha256(введённая фраза)              │
│                                                              │
│  GET /api/v1/users/me  (X-Seed-Hash: seed_hash)            │
│  200 → сохранить фразу, войти                               │
│  401 → фраза неверна                                        │
└─────────────────────────────────────────────────────────────┘
              │
              ▼
┌─────────────────────────────────────────────────────────────┐
│  ПОКУПКА ПРЕМИУМ                                             │
│                                                              │
│  1. GET /api/v1/tariffs                                      │
│     ← [ { id, name, price, currency, duration_days } ]      │
│                                                              │
│  2. Пользователь выбирает тариф                             │
│                                                              │
│  3. Открыть WebView: GET /pay?uid=<user_id>                 │
│     Пользователь оплачивает через форму провайдера          │
│                                                              │
│  4. Параллельно: поллинг каждые 3 сек:                      │
│     GET /api/v1/payments/check?payment_id=<id>              │
│     status == "paid"   → закрыть WebView, показать успех    │
│     status == "failed" → показать ошибку                    │
│     (таймаут 10 мин)                                        │
│                                                              │
│  5. GET /api/v1/users/me — обновить статус                  │
└─────────────────────────────────────────────────────────────┘
              │
              ▼
┌─────────────────────────────────────────────────────────────┐
│  ПРОФИЛЬ / ПОДДЕРЖКА                                         │
│                                                              │
│  GET /api/v1/users/me/qr                                    │
│  ← PNG-изображение QR-кода                                  │
│  QR содержит: "vpn:user:<user_id>"                          │
│                                                              │
│  Пользователь показывает QR администратору в Telegram       │
└─────────────────────────────────────────────────────────────┘
```

---

## 12. Dart-примеры

### Хранение и вычисление seed_hash

```dart
// pubspec.yaml: crypto: ^3.0.3, flutter_secure_storage: ^9.0.0
import 'dart:convert';
import 'package:crypto/crypto.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

const _storage = FlutterSecureStorage();
const _seedKey = 'seed_phrase';

String computeSeedHash(String seedPhrase) {
  final bytes = utf8.encode(seedPhrase);
  return sha256.convert(bytes).toString(); // 64-символьный hex
}

Future<void> saveSeedPhrase(String seedPhrase) async {
  await _storage.write(key: _seedKey, value: seedPhrase);
}

Future<String?> loadSeedPhrase() async {
  return await _storage.read(key: _seedKey);
}
```

### API-клиент

```dart
import 'dart:convert';
import 'package:http/http.dart' as http;

class ApiClient {
  final String baseUrl;
  final String? seedHash;

  ApiClient({required this.baseUrl, this.seedHash});

  Map<String, String> get _authHeaders => seedHash != null
      ? {'X-Seed-Hash': seedHash!, 'Content-Type': 'application/json'}
      : {'Content-Type': 'application/json'};

  // POST /api/v1/users/register
  Future<Map<String, dynamic>> register() async {
    final resp = await http.post(Uri.parse('$baseUrl/api/v1/users/register'));
    if (resp.statusCode == 200) return jsonDecode(resp.body);
    throw ApiException(resp.statusCode, resp.body);
  }

  // GET /api/v1/users/me
  Future<Map<String, dynamic>> getMe() async {
    final resp = await http.get(
      Uri.parse('$baseUrl/api/v1/users/me'),
      headers: _authHeaders,
    );
    if (resp.statusCode == 200) return jsonDecode(resp.body);
    if (resp.statusCode == 401) throw UnauthorizedException();
    throw ApiException(resp.statusCode, resp.body);
  }

  // GET /api/v1/users/me/qr → bytes
  Future<List<int>> getQrCode() async {
    final resp = await http.get(
      Uri.parse('$baseUrl/api/v1/users/me/qr'),
      headers: _authHeaders,
    );
    if (resp.statusCode == 200) return resp.bodyBytes;
    throw ApiException(resp.statusCode, resp.body);
  }

  // GET /api/v1/tariffs
  Future<List<dynamic>> getTariffs() async {
    final resp = await http.get(Uri.parse('$baseUrl/api/v1/tariffs'));
    if (resp.statusCode == 200) return jsonDecode(resp.body);
    throw ApiException(resp.statusCode, resp.body);
  }

  // GET /api/v1/payments/check
  Future<Map<String, dynamic>> checkPayment(String paymentId) async {
    final resp = await http.get(
      Uri.parse('$baseUrl/api/v1/payments/check?payment_id=$paymentId'),
      headers: _authHeaders,
    );
    if (resp.statusCode == 200) return jsonDecode(resp.body);
    if (resp.statusCode == 404) throw NotFoundException();
    throw ApiException(resp.statusCode, resp.body);
  }

  // GET /health
  Future<bool> healthCheck() async {
    try {
      final resp = await http
          .get(Uri.parse('$baseUrl/health'))
          .timeout(const Duration(seconds: 5));
      return resp.statusCode == 200;
    } catch (_) {
      return false;
    }
  }
}

class ApiException implements Exception {
  final int statusCode;
  final String body;
  ApiException(this.statusCode, this.body);
}

class UnauthorizedException implements Exception {}
class NotFoundException implements Exception {}
```

### Форматирование цены

```dart
// price хранится в копейках (минимальных единицах)
String formatPrice(int priceInKopecks, String currency) {
  final rubles = priceInKopecks / 100;
  final formatted = rubles.toStringAsFixed(rubles.truncateToDouble() == rubles ? 0 : 2);
  final symbol = currency == 'RUB' ? '₽' : currency;
  return '$formatted $symbol';
}

// formatPrice(29900, 'RUB') → "299 ₽"
// formatPrice(9900, 'RUB')  → "99 ₽"
```

### Поллинг статуса платежа

```dart
import 'dart:async';

Future<String> pollPaymentStatus(
  ApiClient api,
  String paymentId, {
  Duration interval = const Duration(seconds: 3),
  Duration timeout = const Duration(minutes: 10),
}) async {
  final deadline = DateTime.now().add(timeout);

  while (DateTime.now().isBefore(deadline)) {
    final result = await api.checkPayment(paymentId);
    final status = result['status'] as String;

    if (status == 'paid' || status == 'failed') {
      return status;
    }

    await Future.delayed(interval);
  }

  return 'timeout';
}
```

---

## Сводная таблица всех эндпоинтов

| Метод | Путь | Auth | Описание |
|-------|------|------|----------|
| `POST` | `/api/v1/users/register` | нет | Регистрация, получить seed_phrase |
| `GET` | `/api/v1/users/me` | X-Seed-Hash | Профиль пользователя |
| `GET` | `/api/v1/users/me/qr` | X-Seed-Hash | QR-код для поддержки (PNG) |
| `GET` | `/api/v1/tariffs` | нет | Список активных тарифов |
| `POST` | `/api/v1/payments/initiate` | нет (uid в body) | Создать платёж и активировать премиум (тест) |
| `POST` | `/api/v1/payments/webhook` | нет | Webhook от провайдера (не из приложения) |
| `GET` | `/api/v1/payments/check` | X-Seed-Hash | Проверка статуса платежа |
| `DELETE` | `/api/v1/users/me` | X-Seed-Hash | Удаление аккаунта |
| `GET` | `/pay` | нет (uid в query) | HTML-страница оплаты для WebView |
| `GET` | `/health` | нет | Проверка доступности сервера |
| `GET` | `/docs` | нет | Swagger UI (только для разработки) |
