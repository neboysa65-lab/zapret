#!/usr/bin/env bash
# GW node installer: SSH (direct-tcpip only) + RFC-6455 WebSocket bridge.
# Usage:
#   sudo bash install.sh --domain vpn.example.com [--ws-ports 80,443] [--ssh-port 22] [-y]
set -euo pipefail

GW_USER="gw"
BRIDGE_DIR="/opt/gw-ws-bridge"
ETC_DIR="/etc/gw-node"
SERVICE_NAME="gw-ws-bridge"
WS_PORTS="80,443"
SSH_PORT="22"
DOMAIN=""
ASSUME_YES="0"
TLS_MODE="none"          # none | selfsigned | cert
TLS_PORTS="443"
TLS_CERT=""
TLS_KEY=""
FIREWALL="0"

log()  { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m !!\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m XX\033[0m %s\n' "$*" >&2; exit 1; }
trap 'printf "\033[1;31m XX\033[0m install failed at line $LINENO\n" >&2' ERR

while [[ $# -gt 0 ]]; do
  case "$1" in
    --domain)    DOMAIN="${2:-}"; shift 2 ;;
    --ws-ports)  WS_PORTS="${2:-}"; shift 2 ;;
    --ssh-port)  SSH_PORT="${2:-}"; shift 2 ;;
    --tls-ports) TLS_PORTS="${2:-}"; shift 2 ;;
    --firewall)  FIREWALL="1"; shift ;;
    --tls)       TLS_MODE="${2:-none}"; shift 2 ;;
    --tls-cert)  TLS_CERT="${2:-}"; shift 2 ;;
    --tls-key)   TLS_KEY="${2:-}"; shift 2 ;;
    -y)          ASSUME_YES="1"; shift ;;
    *)           die "unknown option: $1" ;;
  esac
done

[[ $EUID -eq 0 ]] || die "run as root: sudo bash $0 ..."
command -v python3 >/dev/null || die "python3 is required"
command -v systemctl >/dev/null || die "systemd is required"

if ! command -v sshd >/dev/null && ! command -v /usr/sbin/sshd >/dev/null; then
  log "Installing openssh-server"
  if command -v apt-get >/dev/null; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq && apt-get install -y -qq openssh-server openssl curl
  elif command -v dnf >/dev/null; then
    dnf install -y -q openssh-server openssl curl
  elif command -v yum >/dev/null; then
    yum install -y -q openssh-server openssl curl
  else
    die "no supported package manager found"
  fi
fi

mkdir -p "$ETC_DIR"
chmod 700 "$ETC_DIR"

if id "$GW_USER" &>/dev/null; then
  warn "user '$GW_USER' already exists, reusing"
else
  NOLOGIN=$(command -v nologin || echo "/bin/false")
  useradd --shell "$NOLOGIN" --create-home "$GW_USER"
  log "user '$GW_USER' created (shell: $NOLOGIN)"
fi

GW_PASS=$(python3 -c 'import secrets,string; print("".join(secrets.choice(string.ascii_letters+string.digits) for _ in range(24)))')
printf '%s:%s' "$GW_USER" "$GW_PASS" | chpasswd

MATCH_MARKER="# --- gw-node ---"
if ! grep -q "$MATCH_MARKER" /etc/ssh/sshd_config 2>/dev/null; then
  cp /etc/ssh/sshd_config /etc/ssh/sshd_config.bak.gw
  {
    echo ""
    echo "$MATCH_MARKER"
    echo "Match User $GW_USER"
    echo "    PermitTTY no"
    echo "    X11Forwarding no"
    echo "    AllowAgentForwarding no"
    echo "    AllowTcpForwarding yes"
    echo "    PermitTunnel no"
    echo "    PasswordAuthentication yes"
    echo "    ForceCommand /bin/false"
  } >>/etc/ssh/sshd_config
  log "sshd_config updated (Match block for $GW_USER)"
fi

if pgrep -x sshd >/dev/null || ss -tlnp 2>/dev/null | grep -q ":${SSH_PORT} "; then
  systemctl restart sshd 2>/dev/null || systemctl restart ssh 2>/dev/null || service ssh restart || true
else
  systemctl enable --now sshd 2>/dev/null || systemctl enable --now ssh || true
fi

log "Kernel tuning"
cat >/etc/sysctl.d/70-gw-node.conf <<EOF
fs.file-max = 1048576
fs.nr_open = 1048576
net.core.somaxconn = 65535
net.core.netdev_max_backlog = 16384
net.ipv4.tcp_congestion_control = bbr
net.core.default_qdisc = fq
net.ipv4.tcp_keepalive_time = 60
net.ipv4.tcp_keepalive_intvl = 20
net.ipv4.tcp_keepalive_probes = 5
net.netfilter.nf_conntrack_max = 1048576
EOF
sysctl --system >/dev/null 2>&1 || true

if grep -qE '^\*\s+soft\s+nofile' /etc/security/limits.conf 2>/dev/null; then
  :
else
  cat >>/etc/security/limits.conf <<EOF
* soft nofile 1048576
* hard nofile 1048576
root soft nofile 1048576
root hard nofile 1048576
EOF
fi

log "Installing bridge to $BRIDGE_DIR"
mkdir -p "$BRIDGE_DIR"
if [[ -f "$(dirname "$0")/gw-ws-bridge.py" ]]; then
  install -m 0755 "$(dirname "$0")/gw-ws-bridge.py" "$BRIDGE_DIR/gw-ws-bridge.py"
elif [[ ! -f "$BRIDGE_DIR/gw-ws-bridge.py" ]]; then
  die "gw-ws-bridge.py not found next to install.sh — keep both files together"
fi

TLS_ENV=""
TLS_SPEC=""
case "$TLS_MODE" in
  selfsigned|cert)
    if [[ "$TLS_MODE" == "selfsigned" || -z "$TLS_CERT" ]]; then
      CN="${DOMAIN:-$(hostname -f)}"
      openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes \
        -keyout "$ETC_DIR/tls.key" -out "$ETC_DIR/tls.crt" \
        -subj "/CN=${CN}" >/dev/null 2>&1
      TLS_CERT="$ETC_DIR/tls.crt"
      TLS_KEY="$ETC_DIR/tls.key"
      warn "self-signed certificate generated for CN=$CN"
    fi
    [[ -f "$TLS_CERT" && -f "$TLS_KEY" ]] || die "tls cert/key missing"
    for p in ${TLS_PORTS//,/ }; do
      TLS_SPEC+="${p}:${TLS_CERT}:${TLS_KEY},"
    done
    TLS_SPEC=${TLS_SPEC%,}
    TLS_ENV="Environment=GW_WS_TLS=${TLS_SPEC}
"
    ;;
esac

STATS_TOKEN=$(python3 -c 'import secrets; print(secrets.token_hex(16))')

cat >"/etc/systemd/system/${SERVICE_NAME}.service" <<EOF
[Unit]
Description=GW WebSocket-to-SSH bridge
After=network.target sshd.service

[Service]
ExecStart=/usr/bin/python3 ${BRIDGE_DIR}/gw-ws-bridge.py
Environment=GW_WS_PORTS=${WS_PORTS}
Environment=GW_SSH_HOST=127.0.0.1
Environment=GW_SSH_PORT=${SSH_PORT}
Environment=GW_STATS_TOKEN=${STATS_TOKEN}
Environment=PYTHONUNBUFFERED=1
${TLS_ENV}LimitNOFILE=1048576
Restart=always
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable --now "$SERVICE_NAME"
sleep 1
systemctl --no-pager --lines=5 status "$SERVICE_NAME" || true

if [[ "$FIREWALL" == "1" ]] && command -v nft >/dev/null; then
  mkdir -p /etc/nftables.d
  PORTS_NFT=""
  for p in ${WS_PORTS//,/ } $SSH_PORT; do
    PORTS_NFT+="        tcp dport $p accept"$'\n'
  done
  cat >/etc/nftables.d/30-gw-node.nft <<EOF
table inet gw_node {
  chain input {
    type filter hook input priority 0; policy accept;
$PORTS_NFT    }
}
EOF
  nft -f /etc/nftables.d/30-gw-node.nft || true
  log "nftables rules applied"
fi

PROXY_HINT="${DOMAIN:-<your-domain>}"
cat >"$ETC_DIR/credentials.txt" <<EOF
gw_user=$GW_USER
gw_password=$GW_PASS
ssh_host=$DOMAIN
ssh_port=$SSH_PORT
proxy_host=$PROXY_HINT
proxy_port=$(echo "$WS_PORTS" | cut -d, -f1)
proxy_scheme=http
sni=
stats_token=$STATS_TOKEN
payload=GET / HTTP/1.1[crlf]Host: [host][crlf]Connection: Upgrade[crlf]User-Agent: [ua][crlf]Upgrade: websocket[crlf]Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==[crlf]Sec-WebSocket-Version: 13[crlf][crlf]
EOF
chmod 600 "$ETC_DIR/credentials.txt"

cat >/usr/local/bin/gw-status <<'EOF'
#!/usr/bin/env bash
echo "--- GW node status ---"
systemctl is-active gw-ws-bridge && echo "bridge: running" || echo "bridge: DOWN"
echo "credentials:"
cat /etc/gw-node/credentials.txt
ss -tlnp | grep -E ':(22|80|443)\b' || true
EOF
chmod +x /usr/local/bin/gw-status

IP=$(curl -s4 ifconfig.me || hostname -I | awk '{print $1}')
cat <<SUMMARY

==============================================
  GW node installed
----------------------------------------------
  SSH host      : ${DOMAIN:-$IP}
  SSH port      : $SSH_PORT
  SSH user      : $GW_USER
  SSH password  : $GW_PASS
  WS ports      : $WS_PORTS
  Stats token   : $STATS_TOKEN
  Bridge        : systemctl status $SERVICE_NAME
  Credentials   : $ETC_DIR/credentials.txt
  Status helper : gw-status
----------------------------------------------
  Cloudflare checklist:
   * A record -> $IP, Proxied ON
   * SSL/TLS: Full (origin cert) or Flexible for plain HTTP test
   * Network -> WebSockets enabled
   * disable Bot Fight Mode while testing
==============================================
SUMMARY
