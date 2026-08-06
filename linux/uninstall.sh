#!/usr/bin/env bash
#
# Removes the RFID Bridge systemd service. Keeps /opt/rfid-bridge (config +
# logs) unless you pass --purge.
#
#   sudo ./linux/uninstall.sh          # stop + remove service, keep data
#   sudo ./linux/uninstall.sh --purge  # also delete /opt/rfid-bridge and the user
#
set -euo pipefail

APP_DIR=/opt/rfid-bridge
SVC_USER=rfidbridge
UNIT=/etc/systemd/system/rfid-bridge.service

if [[ $EUID -ne 0 ]]; then
    echo "Please run as root:  sudo $0" >&2
    exit 1
fi

systemctl disable --now rfid-bridge.service 2>/dev/null || true
rm -f "$UNIT"
systemctl daemon-reload

if [[ "${1:-}" == "--purge" ]]; then
    echo "Purging $APP_DIR and user '$SVC_USER'..."
    rm -rf "$APP_DIR"
    userdel "$SVC_USER" 2>/dev/null || true
    echo "Purged."
else
    echo "Service removed. Data kept in $APP_DIR (use --purge to delete it)."
fi
