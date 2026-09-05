#!/usr/bin/env bash
# =============================================================================
# AWG 3.1 server installer — тестовый VPS GreyWebVPN (51.75.64.24)
#
# Ставит на чистую Ubuntu 22.04/24.04 (ядро >= 6.7):
#   1. Kernel-модуль AmneziaWG 3.x из PPA Amnezia + amneziawg-tools
#   2. Параметры обфускации 3.1: Jc/Jmin/Jmax, S1-S4, H1-H4 (диапазоны),
#      I1 (CPS-цепочка в теговом формате 3.1), HeaderProtectionKey,
#      ContentPaddingAddition
#   3. Первый peer + клиентский конфиг (текст/base64/QR)
#   4. ip_forward + NAT + UFW
#
# Запуск:      sudo bash awg31-install.sh
# Свой порт:   sudo AWG_PORT=51820 bash awg31-install.sh
# После:       bash /root/awg31/show-client.sh
#
# ВАЖНО: это ТЕСТОВЫЙ скрипт для нового сервера. Рабочий сервер с 300+
# онлайном им не трогается.
# =============================================================================
set -euo pipefail

# ---------- Настраиваемые параметры ----------
AWG_IFACE="awg0"
AWG_PORT="${AWG_PORT:-39744}"           # тот же порт, что у рабочего сервера
AWG_MTU="${AWG_MTU:-1280}"              # рекомендация Amnezia для 3.1
TUNNEL_SUBNET="${TUNNEL_SUBNET:-10.8.1.0/24}"
CLIENT_DNS="${CLIENT_DNS:-1.1.1.1, 8.8.8.8}"
AWG_DIR="/root/awg31"
SERVER_CONF="/etc/amnezia/${AWG_IFACE}.conf"   # стандартный путь awg-quick

# ---------- Проверки ----------
[[ $EUID -eq 0 ]] || { echo "Запусти от root: sudo bash $0"; exit 1; }

. /etc/os-release
echo ">> OS: ${ID} ${VERSION_ID} ($(uname -r))"

if [[ "${ID}" != "ubuntu" ]]; then
    echo "!! Скрипт рассчитан на Ubuntu 22.04/24.04. Для Debian поправь suite PPA вручную."
    exit 1
fi

KERNEL_MAJOR=$(uname -r | cut -d. -f1)
KERNEL_MINOR=$(uname -r | cut -d. -f2)
if (( KERNEL_MAJOR < 6 || (KERNEL_MAJOR == 6 && KERNEL_MINOR < 7) )); then
    echo "!! Ядро $(uname -r) < 6.7 — PPA поставит старую линию модуля."
    echo "   Обнови ядро (HWE) или используй bivlked-скрипт."
    exit 1
fi

# ---------- 1. Система и PPA Amnezia ----------
echo ">> Обновление системы..."
export DEBIAN_FRONTEND=noninteractive
apt-get update -y
apt-get install -y curl wget ca-certificates software-properties-common qrencode

echo ">> Добавление PPA Amnezia (ppa:amnezia/ppa)..."
add-apt-repository -y ppa:amnezia/ppa
apt-get update -y

echo ">> Установка AmneziaWG (kernel-модуль 3.x через DKMS + tools)..."
apt-get install -y amneziawg-dkms amneziawg-tools

echo ">> Загрузка модуля..."
depmod -a || true
modprobe amneziawg || echo "!! modprobe не удался — модуль подхватится после reboot"
if [[ -f /sys/module/amneziawg/version ]]; then
    echo ">> Версия модуля в памяти: $(cat /sys/module/amneziawg/version)"
fi
echo ">> Версия модуля из dkms: $(modinfo -F version amneziawg 2>/dev/null || echo '?')"

# ---------- 2. Генерация ключей и 3.1-параметров ----------
echo ">> Генерация ключей и параметров обфускации..."

SERVER_PRIVKEY=$(awg genkey)
SERVER_PUBKEY=$(echo "$SERVER_PRIVKEY" | awg pubkey)
CLIENT_PRIVKEY=$(awg genkey)
CLIENT_PUBKEY=$(echo "$CLIENT_PRIVKEY" | awg pubkey)
CLIENT_PSK=$(awg genkey)    # PSK == genkey (32 случайных байта, base64)

# Jc/Jmin/Jmax — мусорные пакеты перед handshake
AWG_Jc=$(shuf -i 4:12 -n 1)
AWG_Jmin=$(shuf -i 40:60 -n 1)
AWG_Jmax=$(shuf -i 71:1000 -n 1)

# S1-S4 — дополнения перед типами пакетов (init/response/cookie/transport)
AWG_S1=$(shuf -i 15:150 -n 1)
AWG_S2=$(shuf -i 15:150 -n 1)
AWG_S3=$(shuf -i 15:150 -n 1)
AWG_S4=$(shuf -i 15:150 -n 1)

# H1-H4 — случайные непересекающиеся диапазоны magic-заголовков
gen_header_range() {
    local base; base=$(shuf -i 5:50 -n 1)
    echo "${base}-$((base + $(shuf -i 10:30 -n 1)))"
}
AWG_H1=$(gen_header_range)
AWG_H2=$(gen_header_range)
AWG_H3=$(gen_header_range)
AWG_H4=$(gen_header_range)

# HeaderProtectionKey — 32 байта hex (шифрование заголовков, только 3.0+)
AWG_HPK=$(openssl rand -hex 32)

# I1 — CPS-цепочка, теговый формат 3.1 (device/obf.go движка):
#   <t>       — timestamp uint32 unix (анти-replay)
#   <r N>     — N случайных байт
#   <rc N>    — N случайных печатных символов
#   <rd N>    — N случайных цифр
#   <b 0x..>  — фиксированные байты (мимикрия под заголовок)
# Тег <c> НЕ используем: он есть только в kernel-модуле, Go-клиент
# (наш Android) такой конфиг отвергнет целиком.
AWG_I1="<t><r 64><rc 16>"

# ContentPaddingAddition — рандомизация паддинга data-пакетов
AWG_CPA=$(shuf -i 2:10 -n 1)

# ---------- 3. Конфиги ----------
echo ">> Запись ${SERVER_CONF}..."
mkdir -p /etc/amnezia
umask 077
SERVER_ADDR="${TUNNEL_SUBNET%.*}.1/24"
CLIENT_ADDR="${TUNNEL_SUBNET%.*}.2/24"
SERVER_IP=$(curl -s -4 --max-time 10 ifconfig.me || echo "51.75.64.24")

# Общая обфускация: одинаковые значения в [Interface] сервера И клиента.
OBF_PARAMS="Jc = ${AWG_Jc}
Jmin = ${AWG_Jmin}
Jmax = ${AWG_Jmax}
S1 = ${AWG_S1}
S2 = ${AWG_S2}
S3 = ${AWG_S3}
S4 = ${AWG_S4}
H1 = ${AWG_H1}
H2 = ${AWG_H2}
H3 = ${AWG_H3}
H4 = ${AWG_H4}
I1 = ${AWG_I1}
HeaderProtectionKey = ${AWG_HPK}
ContentPaddingAddition = ${AWG_CPA}"

cat > "$SERVER_CONF" <<EOF
[Interface]
PrivateKey = ${SERVER_PRIVKEY}
Address = ${SERVER_ADDR}
ListenPort = ${AWG_PORT}
MTU = ${AWG_MTU}
${OBF_PARAMS}

[Peer]
# test-client-$(date +%Y%m%d)
PublicKey = ${CLIENT_PUBKEY}
PresharedKey = ${CLIENT_PSK}
AllowedIPs = ${CLIENT_ADDR%/*}/32
EOF
chmod 600 "$SERVER_CONF"

CLIENT_CONF="[Interface]
PrivateKey = ${CLIENT_PRIVKEY}
Address = ${CLIENT_ADDR}
DNS = ${CLIENT_DNS}
MTU = ${AWG_MTU}
${OBF_PARAMS}

[Peer]
PublicKey = ${SERVER_PUBKEY}
PresharedKey = ${CLIENT_PSK}
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = ${SERVER_IP}:${AWG_PORT}
PersistentKeepalive = 25
"

# ---------- 4. Сеть: forward + NAT + UFW ----------
echo ">> Настройка сети..."
cat > /etc/sysctl.d/99-awg31.conf <<EOF
net.ipv4.ip_forward = 1
net.ipv4.conf.all.forwarding = 1
net.core.rmem_max = 16777216
net.core.wmem_max = 16777216
EOF
sysctl -p /etc/sysctl.d/99-awg31.conf >/dev/null

if command -v ufw >/dev/null; then
    ufw allow OpenSSH >/dev/null 2>&1 || true
    ufw allow "${AWG_PORT}/udp" >/dev/null 2>&1 || true
    if ufw status | grep -q "Status: active"; then
        echo ">> UFW уже активен — правило ${AWG_PORT}/udp добавлено."
    else
        echo ">> UFW неактивен — не включаю (правила добавлены на будущее)."
    fi
fi

nic=$(ip -4 route show default | awk '{print $5; exit}')
# NAT пропишем в сам конфиг через PostUp/PostDown — переживёт reboot вместе с awg-quick
sed -i "/^MTU = /a PostUp = iptables -I FORWARD 1 -i %i -j ACCEPT; iptables -I FORWARD 1 -o %i -j ACCEPT; iptables -t nat -A POSTROUTING -s ${TUNNEL_SUBNET} -o ${nic} -j MASQUERADE
PostDown = iptables -D FORWARD -i %i -j ACCEPT; iptables -D FORWARD -o %i -j ACCEPT; iptables -t nat -D POSTROUTING -s ${TUNNEL_SUBNET} -o ${nic} -j MASQUERADE" "$SERVER_CONF"

# ---------- 5. Файлы клиента в /root/awg31 ----------
mkdir -p "$AWG_DIR"
echo "$CLIENT_CONF" > "$AWG_DIR/client.conf"
chmod 600 "$AWG_DIR/client.conf"
base64 -w0 "$AWG_DIR/client.conf" > "$AWG_DIR/client.conf.b64"
cp "$SERVER_CONF" "$AWG_DIR/server.conf.backup"

cat > "$AWG_DIR/show-client.sh" <<'EOS'
#!/usr/bin/env bash
echo "=== Клиентский конфиг (текст) ==="
cat /root/awg31/client.conf
echo
echo "=== Base64 (для API приложения) ==="
cat /root/awg31/client.conf.b64
echo
echo
echo "=== QR-код ==="
qrencode -t ansiutf8 < /root/awg31/client.conf
EOS
chmod +x "$AWG_DIR/show-client.sh"

# ---------- 6. Запуск ----------
echo ">> Запуск туннеля..."
systemctl enable "awg-quick@${AWG_IFACE}" >/dev/null 2>&1 || true
if awg-quick up "$AWG_IFACE"; then
    UP_OK=1
else
    UP_OK=0
    echo "!! awg-quick up не удался. Если ругань на 3.1-параметры — пришли лог:"
    echo "   journalctl -u awg-quick@${AWG_IFACE} --no-pager | tail -20"
fi

echo
echo "=============================================="
echo " AWG 3.1 (ядро 3.x) установка завершена."
echo
echo " Параметры 3.1:"
echo "   Jc=$AWG_Jc Jmin=$AWG_Jmin Jmax=$AWG_Jmax"
echo "   S1=$AWG_S1 S2=$AWG_S2 S3=$AWG_S3 S4=$AWG_S4"
echo "   H1=$AWG_H1 H2=$AWG_H2 H3=$AWG_H3 H4=$AWG_H4"
echo "   I1=$AWG_I1"
echo "   HPK=${AWG_HPK:0:12}... CPA=$AWG_CPA MTU=$AWG_MTU"
echo "   Порт UDP $AWG_PORT, подсеть $TUNNEL_SUBNET"
echo
if [[ $UP_OK -eq 1 ]]; then
    echo " Туннель ПОДНЯТ:  awg show"
else
    echo " Туннель НЕ поднят — смотри лог выше; после исправления:"
    echo "   sudo awg-quick up ${AWG_IFACE}"
fi
echo
echo " Дальше:"
echo "   bash /root/awg31/show-client.sh   — конфиг/base64/QR клиента"
echo "   sudo reboot                       — и проверить, что модуль и туннель поднялись сами"
echo "=============================================="
