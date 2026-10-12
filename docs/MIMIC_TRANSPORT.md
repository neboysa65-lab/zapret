# Второй транспорт API: MTP (Mimic Protocol)

Зачем: РКН банит HTTPS-домен API каждые 30–60 минут. Приложение ходит к
API по цепочке приоритетов:

1. **MTP (Mimic Protocol) поверх UDP 443** — собственный протокол
   https://github.com/Locon213/Mimic-Protocol: ARQ, FEC, ChaCha20-Poly1305,
   маскировка под QUIC.
2. **Mimic TCP** — если UDP у оператора зарезан: TLS к тому же IP с пином
   SPKI (без домена и SNI) + yamux + HMAC-аутентификация тем же секретом.
   Свой бинарь `mimic-server-tcp` (исходники в `mimic_mobile/cmd/`),
   wire-формат стримов тот же, что у апстрим-сервера.
3. **Прямой HTTPS** — прежний путь без изменений, финальный фолбек.

## Схема

```
Flutter (dart:io HttpOverrides) → 127.0.0.1:7102 (HTTP-прокси движка)
  → MTP/UDP 443 → прод 57.129.126.169 (mimic-server, systemd mimic-api)
  → nginx (vhost *.appvle.store) → backtg-mirror-api

Путь 2 (когда UDP зарезан):

```
Flutter → 127.0.0.1:7104 (HTTP-прокси движка)
  → TLS:25565 (пин SPKI) → прод 57.129.126.169 (mimic-api-tcp.service)
  → nginx (vhost *.appvle.store) → backtg-mirror-api
```
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
  UDP мёртв 2 минуты (кулюдаун) → пробуется TCP; недоступны оба →
  прямой HTTPS, и так по кругу.
- Remote Config: `mimic_enabled`, `mimic_server` (`ip:port` UDP),
  `mimic_tcp_server` (`ip:port` TCP), `mimic_tcp_pin` (SPKI sha256),
  `mimic_secret`, `mimic_hosts` (запятая). Меняются без релиза.

## Прод (57.129.126.169)

Unit `mimic-api.service` слушает **UDP 443** (`ss -ulnp | grep 443`),
конфиг `/opt/mimic/server.yaml`, бинарь `/opt/mimic/mimic-server`.
TCP-фолбек — unit `mimic-api-tcp.service`, TCP 25565,
`/opt/mimic/mimic-server-tcp`, сертификат `/opt/mimic/tcp-*.pem`
(самоподписанный, пин в Remote Config `mimic_tcp_pin`).
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
- E2E MTP/UDP: mimic-клиент → UDP 443 → `GET https://api.appvle.store/`
  → ответ API за ~0.9 s.
- E2E Mimic TCP: тот же запрос через TLS:25565 с пином → ответ API;
  неверный пин и неверный секрет корректно отклоняются (проверено
  loopback-ом и с прод-сервером).
- Осталось проверить на устройстве: сборка AAR в APK и подключение на
  мобильном интернете (UDP у части операторов режется — тогда сработает
  фолбек на HTTPS, это ожидаемое поведение).
