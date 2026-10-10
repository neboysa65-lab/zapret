# Второй транспорт API: MTP (Mimic Protocol)

Зачем: РКН банит HTTPS-домен API каждые 30–60 минут. Второй транспорт —
собственный протокол MTP из https://github.com/Locon213/Mimic-Protocol
поверх **UDP** (не TLS): надёжная доставка (ARQ), FEC, шифрование
ChaCha20-Poly1305 с общим секретом, маскировка пакетов под QUIC и
легитимный трафик. HTTPS-транспорт остаётся без изменений и работает
фолбеком.

## Схема

```
Flutter (dart:io HttpOverrides) → 127.0.0.1:7102 (HTTP-прокси движка)
  → MTP/UDP 443 → прод 57.129.126.169 (mimic-server, systemd mimic-api)
  → nginx (vhost *.appvle.store) → backtg-mirror-api
```

- Клиент: Go-обвязка `mimic_mobile/` собирается gomobile в AAR
  (workflow `build-mimic-aar.yml`, результат коммитится в
  `android/mimic_engine/`). Kotlin-мост — `MimicBridge`, канал
  `app.vless.vpn/mimic`.
- Маршрутизация: `MimicHttpOverrides` + `MimicTransportService`.
  Приоритет — туннель; недоступен/кулдаун → прямой HTTPS. Белый список
  доменов — `ApiConfig.mimicHostSuffixes`; прочее через туннель не ходит
  (на движке default_policy=block, чтобы локальные порты не были
  открытым прокси для других приложений).
- Проверка туннеля: SOCKS5-проба до API-хоста через локальный порт
  движка раз в 30 с, мгновенная перепроверка после сбоя запроса.
- Remote Config: `mimic_enabled`, `mimic_server` (`ip:port`),
  `mimic_secret`, `mimic_hosts` (запятая). Меняются без релиза.

## Прод (57.129.126.169)

Unit `mimic-api.service` слушает **UDP 443** (`ss -ulnp | grep 443`),
конфиг `/opt/mimic/server.yaml`, бинарь `/opt/mimic/mimic-server`.
Старое не отключено и не перезапущено: nginx, docker-контейнеры и прочие
сервисы работают как раньше. Секрет равен секрету клиента
(`ApiConfig.mimicSecret` / Remote Config `mimic_secret`).

Ротация секрета:
1. Сервер: `uuid:` в `/opt/mimic/server.yaml`, затем
   `sudo systemctl restart mimic-api.service` (только этот unit).
2. Клиенты: новое значение в Firebase Remote Config `mimic_secret` —
   движок перезапустится сам в течение цикла Remote Config (10 мин).

## Проверено

- Тесты самого протокола (`go test ./...`) — pass (pkg/mtp, compression,
  network).
- E2E с прод-сервером: mimic-клиент → MTP/UDP 443 →
  `GET https://api.appvle.store/` через туннель → ответ API за ~0.9 s.
- Осталось проверить на устройстве: сборка AAR в APK и подключение на
  мобильном интернете (UDP у части операторов режется — тогда сработает
  фолбек на HTTPS, это ожидаемое поведение).
