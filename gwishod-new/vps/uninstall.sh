#!/usr/bin/env bash
# GW node uninstaller. Usage: sudo bash uninstall.sh [--delete-user]
set -euo pipefail

GW_USER="gw"
BRIDGE_DIR="/opt/gw-ws-bridge"
ETC_DIR="/etc/gw-node"
SERVICE_NAME="gw-ws-bridge"
DELETE_USER="0"

[[ $EUID -eq 0 ]] || { echo "run as root" >&2; exit 1; }
[[ "${1:-}" == "--delete-user" ]] && DELETE_USER="1"

systemctl disable --now "$SERVICE_NAME" 2>/dev/null || true
rm -f "/etc/systemd/system/${SERVICE_NAME}.service"
systemctl daemon-reload

rm -rf "$BRIDGE_DIR" "$ETC_DIR"
rm -f /etc/sysctl.d/70-gw-node.conf /usr/local/bin/gw-status /etc/nftables.d/30-gw-node.nft
rm -f /etc/ssh/sshd_config.bak.gw

if [[ "$DELETE_USER" == "1" ]] && id "$GW_USER" &>/dev/null; then
  userdel -r "$GW_USER" || true
fi

sed -i '/# --- gw-node ---/,/^    ForceCommand \/bin\/false$/d' /etc/ssh/sshd_config 2>/dev/null || true
systemctl restart sshd 2>/dev/null || systemctl restart ssh 2>/dev/null || true

sysctl --system >/dev/null 2>&1 || true
echo "GW node removed."
