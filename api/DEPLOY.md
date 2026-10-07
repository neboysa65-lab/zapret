# Rabochiy VPN — деплой второго API на тот же VPS

Этот гайд показывает, как поднять Rabochiy API + бот рядом с уже работающим
VPN-стеком на одном сервере. Они полностью изолированы:

| Параметр   | Существующий API | Rabochiy API |
|------------|------------------|-----------|
| Порт       | 3002             | **3003**  |
| MongoDB    | `127.0.0.1:27017` (общая) | `127.0.0.1:27017` (общая) |
| База в MongoDB | существующая | **`RabochiyVPN`** |
| Bot token  | свой             | **отдельный** |
| Bundle ID  | свой             | **`app.rabochiy.vpn`** |
| Домен      | существующий     | **`https://app.rabochiy.app`** |

Прила Rabochiy VPN после смены bundle ID и домена будет работать без ошибок —
проверка подписи на сервере уже принимает `app.rabochiy.vpn`
(см. `src/endpoints/v1/auth_middleware.py`).

---

## 1. Скопировать API на VPS

С локальной машины:

```bash
scp -r D:/top/mobil/vpn/rabochiy_vpn_python_api root@<VPS_IP>:/opt/rabochiy-api
```

Либо через Git:

```bash
ssh root@<VPS_IP>
mkdir -p /opt && cd /opt
git clone <ваш-репо> rabochiy-api
```

## 2. Запустить через Docker (рекомендуется)

`Dockerfile` и `docker-compose.yml` уже лежат рядом с `src/`.
`network_mode: host` даёт контейнеру доступ к локальной MongoDB на 27017
без проброса портов.

```bash
cd /opt/rabochiy-api
docker compose up -d --build
docker compose logs -f rabochiy-api          # проверить логи
docker compose ps                         # должно показать "healthy"
```

`restart: unless-stopped` означает, что после ребута VPS контейнер сам
поднимется через 5–10 секунд (как у вашей текущей системы).

## 3. (Опционально) Запустить бота

Если бот нужен — рядом с API положите такую же связку Dockerfile +
compose. **Главное, чтобы у нового бота был свой `BOT_TOKEN`** (получить
у `@BotFather`). Иначе телеграм просто не даст двум процессам слушать
один токен.

Минимальный `bot/.env`:

```
BOT_TOKEN=<НОВЫЙ_ТОКЕН_ОТ_BOTFATHER>
ADMIN_IDS=<ваш_telegram_id>
API_BASE_URL=http://127.0.0.1:3003
```

## 4. Настроить Nginx + домен

Чтобы `https://app.rabochiy.app` указывал на порт 3003, нужен reverse proxy.
Добавьте в `/etc/nginx/sites-available/rabochiy-api`:

```nginx
server {
    listen 80;
    server_name app.rabochiy.app;

    location / {
        proxy_pass http://127.0.0.1:3003;
        proxy_set_header Host              $host;
        proxy_set_header X-Real-IP         $remote_addr;
        proxy_set_header X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 60s;
    }
}
```

Активируйте сайт и получите TLS-сертификат:

```bash
sudo ln -s /etc/nginx/sites-available/rabochiy-api /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx

# Let's Encrypt (если ещё не настроен certbot — поставить: apt install certbot python3-certbot-nginx)
sudo certbot --nginx -d app.rabochiy.app
```

В DNS-зоне домена `rabochiy.app` создайте A-запись `app → <IP_VPS>`.

## 5. Проверка

Из браузера или curl-а с любой машины:

```bash
curl https://app.rabochiy.app/vpn/api/v3/client/tariff/get
# должен вернуть JSON со списком тарифов (даже если пустой)
```

Если ответ пришёл — приложение Rabochiy VPN сразу заработает: оно ходит
именно на `https://app.rabochiy.app` (см. `defaultBaseUrl` в
`lib/services/api_service.dart`).

## 6. Дальнейшее обслуживание

```bash
# обновить код
cd /opt/rabochiy-api
git pull                  # или scp заново
docker compose up -d --build

# посмотреть логи
docker compose logs -f --tail=200 rabochiy-api

# остановить
docker compose down
```

---

## Часто задаваемые вопросы

**Q: будет ли мешать существующая система?**
Нет. Они слушают разные порты (3002 vs **3003**), пишут в **разные базы
данных** Mongo (существующая vs **`RabochiyVPN`**), разные домены и разные
`bundleId` в подписи. Полная изоляция.

**Q: нужно ли поднимать вторую MongoDB?**
Нет. Один `mongod` на 27017 спокойно держит сотни баз. Rabochiy пишет
**только** в `RabochiyVPN`, чужую базу даже не открывает. Если хочется ещё
большую изоляцию — можно поднять второй `mongod` на 27018 и поправить
`MONGODB_URI` во всех `src/db/**/*.py`, но это не требуется.

**Q: что с bundle ID?**
Bundle ID `app.rabochiy.vpn` уже зашит во все нужные места:
- `lib/services/api_service.dart` → `_signatureBundleId`
- `android/app/build.gradle.kts` → `applicationId`
- `android/app/google-services.json` → `package_name`
- `lib/firebase_options.dart` → `iosBundleId`
- `src/endpoints/v1/auth_middleware.py` → `ALLOWED_BUNDLE_IDS`

Никакого 403 от подписи не будет.

**Q: что если VPS перезагрузится?**
`restart: unless-stopped` поднимет контейнер автоматически через
5–10 с после старта Docker daemon. Та же схема, что у текущего стека.
