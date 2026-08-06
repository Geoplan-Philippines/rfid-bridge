#!/usr/bin/env bash
#
# Installs the RFID Bridge as a systemd background service on Ubuntu/Debian.
#
#   sudo ./linux/install.sh
#
# What it does:
#   * ensures a Java 17+ runtime is present (installs openjdk-17-jre-headless)
#   * builds dist/rfid-bridge.jar if it isn't there yet (needs the JDK)
#   * creates the 'rfidbridge' system user
#   * installs the jar + config under /opt/rfid-bridge
#   * installs, enables and starts the rfid-bridge systemd service
#
# Re-running is safe: it updates the jar/unit and never overwrites an existing
# /opt/rfid-bridge/bridge.properties (your edited config is preserved).
#
set -euo pipefail

APP_DIR=/opt/rfid-bridge
SVC_USER=rfidbridge
UNIT=/etc/systemd/system/rfid-bridge.service

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/.." && pwd)"

if [[ $EUID -ne 0 ]]; then
    echo "Please run as root:  sudo $0" >&2
    exit 1
fi

# --- 1. Java runtime --------------------------------------------------------
if ! command -v java >/dev/null 2>&1; then
    echo "Java not found; installing openjdk-17-jre-headless..."
    apt-get update -y
    apt-get install -y openjdk-17-jre-headless
fi
echo "Java: $(java -version 2>&1 | head -n1)"

# --- 2. Build the jar if missing --------------------------------------------
jar="$root/dist/rfid-bridge.jar"
if [[ ! -f "$jar" ]]; then
    echo "dist/rfid-bridge.jar not found; building it..."
    if ! command -v javac >/dev/null 2>&1; then
        echo "Installing JDK to build (openjdk-17-jdk-headless)..."
        apt-get update -y
        apt-get install -y openjdk-17-jdk-headless
    fi
    bash "$here/build-linux.sh"
fi

# --- 3. Service user --------------------------------------------------------
if ! id "$SVC_USER" >/dev/null 2>&1; then
    echo "Creating system user '$SVC_USER'..."
    useradd --system --home-dir "$APP_DIR" --shell /usr/sbin/nologin "$SVC_USER"
fi

# --- 4. Install files -------------------------------------------------------
echo "Installing to $APP_DIR..."
install -d -o "$SVC_USER" -g "$SVC_USER" "$APP_DIR"
install -o "$SVC_USER" -g "$SVC_USER" -m 644 "$jar" "$APP_DIR/rfid-bridge.jar"

# Seed config only if it's not already there (preserve operator edits).
if [[ ! -f "$APP_DIR/bridge.properties" ]]; then
    if [[ -f "$root/bridge.properties" ]]; then
        seed="$root/bridge.properties"
    else
        seed="$root/src/main/resources/geoplanph/default-bridge.properties"
    fi
    echo "Seeding config from $seed"
    install -o "$SVC_USER" -g "$SVC_USER" -m 600 "$seed" "$APP_DIR/bridge.properties"
else
    echo "Keeping existing $APP_DIR/bridge.properties"
fi

# --- 5. systemd unit --------------------------------------------------------
echo "Installing systemd unit -> $UNIT"
install -m 644 "$here/rfid-bridge.service" "$UNIT"
systemctl daemon-reload
systemctl enable rfid-bridge.service
# restart, not 'enable --now': --now is a no-op on an already-running service,
# which would silently leave the OLD jar running after a re-install/upgrade.
systemctl restart rfid-bridge.service

echo ""
echo "Done. The bridge is running and will start on boot."
echo ""
systemctl --no-pager --full status rfid-bridge.service || true
echo ""
echo "Config : $APP_DIR/bridge.properties   (edit, then: sudo systemctl restart rfid-bridge)"
echo "Logs   : sudo journalctl -u rfid-bridge -f"
echo "         $APP_DIR/RfidBridge/logs/bridge.log"
echo "Dashboard (loopback only) — tunnel from your PC then open http://localhost:20080/ :"
echo "         ssh -L 20080:localhost:20080 $(logname 2>/dev/null || echo user)@<server-ip>"
