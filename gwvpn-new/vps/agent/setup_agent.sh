#!/bin/bash
# ==============================================================================
# GWVPN AWG Agent — установка/обновление на VPS с уже установленным AmneziaWG.
#
# Что делает (идемпотентно, можно перезапускать):
#   1. Расширяет туннельную подсеть /24 -> /16 (лимит пиров: 253 -> ~65 тыс.)
#   2. Генерирует токен агента и самоподписанный TLS-сертификат
#   3. Ставит агент (gwvpn_awg_agent.py) как systemd-сервис gwvpn-awg-agent
#   4. Открывает порт агента в UFW (можно ограничить только IP API: --api-ip=X)
#   5. Ставит ежедневную cron-чистку неактивных пиров (>30 дней без handshake)
#   6. Регистрирует агент в API (ip, порт, токен) — после этого API начинает
#      выдавать персональные конфиги пользователям этого сервера
#
# Использование (на VPS, рядом должен лежать gwvpn_awg_agent.py):
#   sudo bash setup_agent.sh [-y] [--api-ip=IP_API_СЕРВЕРА] [--port=39744] [--rotate-token]
# ==============================================================================
set -euo pipefail

# --- Параметры ---
AGENT_PORT=39744
API_BASE="https://api-gwvpn-app.vpnhub.xyz"
API_IP=""            # если задан — порт агента откроется только для этого IP
AUTO_YES=0
ROTATE_TOKEN=0
CLEANUP_DAYS=30

AWG_DIR="/root/awg"
SERVER_CONF_FILE="/etc/amnezia/amneziawg/awg0.conf"
CONFIG_FILE="$AWG_DIR/awgsetup_cfg.init"
AGENT_ETC="/etc/gwvpn-agent"
AGENT_ENV="$AGENT_ETC/agent.env"
AGENT_BIN="/usr/local/bin/gwvpn_awg_agent.py"
AGENT_SERVICE="/etc/systemd/system/gwvpn-awg-agent.service"
CLEANUP_CRON="/etc/cron.d/gwvpn-awg-cleanup"

for arg in "$@"; do
    case "$arg" in
        -y|--yes)          AUTO_YES=1 ;;
        --api-ip=*)        API_IP="${arg#*=}" ;;
        --port=*)          AGENT_PORT="${arg#*=}" ;;
        --rotate-token)    ROTATE_TOKEN=1 ;;
        --cleanup-days=*)  CLEANUP_DAYS="${arg#*=}" ;;
        --api-base=*)      API_BASE="${arg#*=}" ;;
        *) echo "Неизвестный аргумент: $arg"; exit 1 ;;
    esac
done

log()  { echo "[INFO] $1"; }
warn() { echo "[WARN] $1" >&2; }
die()  { echo "[ERROR] $1" >&2; exit 1; }

[[ "$(id -u)" -eq 0 ]] || die "Запустите от root: sudo bash $0"
[[ -f "$SERVER_CONF_FILE" ]] || die "AmneziaWG не установлен ($SERVER_CONF_FILE не найден). Сначала install_awg.sh."
command -v awg >/dev/null || die "Команда awg не найдена."

# AWG 3.1 (установка через vps/awg31-install.sh, без bivlked-скриптов):
# cfg-файла может не быть — генерируем его из серверного конфига,
# включая 3.1-параметры (I1, HeaderProtectionKey, ContentPaddingAddition).
if [[ ! -f "$CONFIG_FILE" ]]; then
    log "$CONFIG_FILE не найден — генерирую из $SERVER_CONF_FILE (режим AWG 3.1)."
    mkdir -p "$AWG_DIR"
    grab() { grep -m1 -P "^\s*$1\s*=\s*" "$SERVER_CONF_FILE" | sed "s/^\s*$1\s*=\s*//;s/\"//g" | head -1; }
    {
        echo "AWG_Jc=\"$(grab Jc)\""
        echo "AWG_Jmin=\"$(grab Jmin)\""
        echo "AWG_Jmax=\"$(grab Jmax)\""
        echo "AWG_S1=\"$(grab S1)\""
        echo "AWG_S2=\"$(grab S2)\""
        echo "AWG_S3=\"$(grab S3)\""
        echo "AWG_S4=\"$(grab S4)\""
        echo "AWG_H1=\"$(grab H1)\""
        echo "AWG_H2=\"$(grab H2)\""
        echo "AWG_H3=\"$(grab H3)\""
        echo "AWG_H4=\"$(grab H4)\""
        echo "AWG_I1=\"$(grab I1)\""
        echo "AWG_HPK=\"$(grab HeaderProtectionKey)\""
        echo "AWG_CPA=\"$(grab ContentPaddingAddition)\""
        echo "AWG_PORT=\"$(grab ListenPort)\""
    } > "$CONFIG_FILE"
    chmod 600 "$CONFIG_FILE"
    log "Создан $CONFIG_FILE с параметрами из серверного конфига."
fi

# server_public.key нужен агенту для клиентских конфигов
if [[ ! -f "$AWG_DIR/server_public.key" ]]; then
    mkdir -p "$AWG_DIR"
    awg show awg0 public-key > "$AWG_DIR/server_public.key"
    log "Создан $AWG_DIR/server_public.key."
fi
command -v python3 >/dev/null || die "python3 не найден."
command -v openssl >/dev/null || die "openssl не найден."

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
[[ -f "$SCRIPT_DIR/gwvpn_awg_agent.py" ]] || die "Рядом со скриптом нет gwvpn_awg_agent.py — скопируйте оба файла."

# ------------------------------------------------------------------------------
# Шаг 1. Расширение туннельной подсети /24 -> /16
# ------------------------------------------------------------------------------
current_mask=$(grep -oP '^Address\s*=\s*[0-9.]+/\K\d+' "$SERVER_CONF_FILE" | head -1)
if [[ -z "$current_mask" ]]; then
    die "Не удалось прочитать Address из $SERVER_CONF_FILE"
fi
if [[ "$current_mask" -gt 16 ]]; then
    log "Расширяю туннельную подсеть: /$current_mask -> /16 ..."
    cp -a "$SERVER_CONF_FILE" "${SERVER_CONF_FILE}.bak-$(date +%s)"
    old_addr=$(grep -m1 -oP '^Address\s*=\s*\K[0-9.]+' "$SERVER_CONF_FILE")
    sed -i -E "s|^(Address\s*=\s*[0-9.]+)/${current_mask}|\1/16|" "$SERVER_CONF_FILE"
    if grep -q '^AWG_TUNNEL_SUBNET=' "$CONFIG_FILE"; then
        sed -i -E "s|^(AWG_TUNNEL_SUBNET=\"?[0-9.]+)/${current_mask}|\1/16|" "$CONFIG_FILE"
    fi
    # PostUp/PostDown: старая подсеть x.x.<n>.0/${current_mask} -> x.x.0.0/16
    net16=$(awk -F. '{print $1"."$2".0.0/16"}' <<< "$old_addr")
    sed -i -E "s|-s ${old_addr%.*}.0/${current_mask}|-s ${net16}|g" "$SERVER_CONF_FILE"
    systemctl restart awg-quick@awg0 || die "Не удалось перезапустить awg-quick@awg0"
    log "Подсеть расширена, сервис перезапущен (заодно сброшен timestamp-лок)."
else
    log "Подсеть уже /$current_mask — расширение не требуется."
fi

# ------------------------------------------------------------------------------
# Шаг 2. Токен и сертификат
# ------------------------------------------------------------------------------
mkdir -p "$AGENT_ETC"
chmod 700 "$AGENT_ETC"

AGENT_TOKEN=""
if [[ -f "$AGENT_ENV" && "$ROTATE_TOKEN" -eq 0 ]]; then
    AGENT_TOKEN=$(grep -oP '^AGENT_TOKEN=\K.*' "$AGENT_ENV" || true)
fi
if [[ -z "$AGENT_TOKEN" ]]; then
    AGENT_TOKEN=$(openssl rand -hex 32)
    log "Сгенерирован новый токен агента."
else
    log "Использую существующий токен агента."
fi

SERVER_PUBLIC_IP=$(curl -4 -sf --max-time 10 https://checkip.amazonaws.com/ | tr -d '[:space:]' || true)
if [[ -z "$SERVER_PUBLIC_IP" ]]; then
    SERVER_PUBLIC_IP=$(curl -4 -sf --max-time 10 https://api.ipify.org | tr -d '[:space:]' || true)
fi
[[ -n "$SERVER_PUBLIC_IP" ]] || die "Не удалось определить внешний IP сервера."

if [[ "$AUTO_YES" -eq 0 ]]; then
    read -rp "Внешний IP этого сервера: $SERVER_PUBLIC_IP — верно? [Y/n]: " yn < /dev/tty || yn="y"
    case "$yn" in
        [Nn]*) read -rp "Введите IP: " SERVER_PUBLIC_IP < /dev/tty ;;
    esac
fi

if [[ ! -f "$AGENT_ETC/agent.crt" || ! -f "$AGENT_ETC/agent.key" ]]; then
    log "Генерирую самоподписанный TLS-сертификат..."
    openssl req -x509 -newkey rsa:2048 -nodes -days 3650 \
        -keyout "$AGENT_ETC/agent.key" -out "$AGENT_ETC/agent.crt" \
        -subj "/CN=${SERVER_PUBLIC_IP}" >/dev/null 2>&1
    chmod 600 "$AGENT_ETC/agent.key" "$AGENT_ETC/agent.crt"
fi

cat > "$AGENT_ENV" << EOF
AGENT_TOKEN=${AGENT_TOKEN}
AGENT_PORT=${AGENT_PORT}
AGENT_CERT=${AGENT_ETC}/agent.crt
AGENT_KEY=${AGENT_ETC}/agent.key
AWG_DIR=${AWG_DIR}
KEYS_DIR=${AWG_DIR}/keys
CONFIG_FILE=${CONFIG_FILE}
SERVER_CONF_FILE=${SERVER_CONF_FILE}
API_BASE=${API_BASE}
EOF
chmod 600 "$AGENT_ENV"

# ------------------------------------------------------------------------------
# Шаг 3. Установка агента и systemd-сервиса
# ------------------------------------------------------------------------------
install -m 755 "$SCRIPT_DIR/gwvpn_awg_agent.py" "$AGENT_BIN"

cat > "$AGENT_SERVICE" << EOF
[Unit]
Description=GWVPN AWG Agent (per-user peer provisioning)
After=network-online.target awg-quick@awg0.service
Wants=network-online.target

[Service]
Type=simple
EnvironmentFile=${AGENT_ENV}
ExecStart=/usr/bin/python3 ${AGENT_BIN}
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable gwvpn-awg-agent >/dev/null 2>&1
systemctl restart gwvpn-awg-agent
sleep 1
systemctl is-active --quiet gwvpn-awg-agent || die "Агент не запустился: journalctl -u gwvpn-awg-agent -n 50"
log "Агент запущен (порт $AGENT_PORT)."

# ------------------------------------------------------------------------------
# Шаг 4. UFW
# ------------------------------------------------------------------------------
if command -v ufw >/dev/null && ufw status | grep -q "Status: active"; then
    if [[ -n "$API_IP" ]]; then
        ufw allow from "$API_IP" to any port "$AGENT_PORT" proto tcp comment "GWVPN Agent (API only)" >/dev/null
        log "UFW: порт $AGENT_PORT/tcp открыт только для $API_IP."
    else
        ufw allow "${AGENT_PORT}/tcp" comment "GWVPN Agent" >/dev/null
        log "UFW: порт $AGENT_PORT/tcp открыт (рекомендую ограничить: --api-ip=IP_API)."
    fi
else
    warn "UFW неактивен — пропускаю настройку фаервола."
fi

# ------------------------------------------------------------------------------
# Шаг 5. Cron-чистка неактивных пиров
# ------------------------------------------------------------------------------
cat > "$CLEANUP_CRON" << EOF
# GWVPN: удаление пиров без handshake > ${CLEANUP_DAYS} дней (с инвалидацией кэша API)
30 4 * * * root /bin/bash -c 'set -a; source ${AGENT_ENV}; set +a; /usr/bin/python3 ${AGENT_BIN} --cleanup --days ${CLEANUP_DAYS}' >> /var/log/gwvpn-awg-cleanup.log 2>&1
EOF
chmod 644 "$CLEANUP_CRON"
log "Cron-чистка установлена: $CLEANUP_CRON"

# ------------------------------------------------------------------------------
# Шаг 6. Проверка и регистрация в API
# ------------------------------------------------------------------------------
health=$(curl -sk --max-time 10 -H "X-Auth-Token: ${AGENT_TOKEN}" "https://127.0.0.1:${AGENT_PORT}/v1/health" || true)
echo "$health" | grep -q '"success": 1' || echo "$health" | grep -q '"success":1' || die "Health-check агента не прошёл: $health"
log "Health-check OK: $health"

log "Регистрирую агент в API..."
register_resp=$(curl -s --max-time 15 -H "Content-Type: application/json" -X POST \
    -d "{\"ip_address\":\"${SERVER_PUBLIC_IP}\",\"port\":${AGENT_PORT},\"token\":\"${AGENT_TOKEN}\",\"scheme\":\"https\"}" \
    "${API_BASE}/vpn/api/v1/bot/agents_awg/register" || true)
if echo "$register_resp" | grep -q '"success": *1'; then
    log "Агент зарегистрирован в API."
else
    warn "Не удалось зарегистрировать агент в API (ответ: $register_resp)."
    warn "Если API ещё не обновлён до версии с поддержкой агентов — обнови API и перезапусти этот скрипт."
fi

echo
log "=== ГОТОВО ==="
log "Сервер:        ${SERVER_PUBLIC_IP}"
log "Порт агента:   ${AGENT_PORT}/tcp (HTTPS, самоподписанный сертификат)"
log "Токен:         ${AGENT_ENV}"
log "Логи агента:   journalctl -u gwvpn-awg-agent -f"
log "Чистка пиров:  ежедневно в 04:30, лог /var/log/gwvpn-awg-cleanup.log"
