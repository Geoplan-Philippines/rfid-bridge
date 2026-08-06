#!/usr/bin/env bash
#
# Compiles the RFID Bridge into a single portable runnable jar:
#     dist/rfid-bridge.jar   (main class: geoplanph.Daemon)
#
# Needs a JDK 17+ on PATH (javac + jar). The resulting jar is platform
# independent — build it here or on the Ubuntu box, it runs anywhere with a
# JRE 17+. Run it with:  java -jar dist/rfid-bridge.jar
#
set -euo pipefail

# Resolve the project root (one level up from this linux/ dir).
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/.." && pwd)"
cd "$root"

if ! command -v javac >/dev/null 2>&1; then
    echo "ERROR: javac not found. Install a JDK 17+ (e.g. sudo apt install openjdk-17-jdk-headless)." >&2
    exit 1
fi

echo "Using JDK: $(javac -version 2>&1)"

# --- clean ------------------------------------------------------------------
rm -rf build/classes dist
mkdir -p build/classes dist

# --- compile ----------------------------------------------------------------
echo "Compiling..."
javac --release 17 -d build/classes src/main/java/geoplanph/*.java

# --- bundle resources (web UI + default config template) --------------------
cp -r src/main/resources/* build/classes/

# --- jar (Daemon = headless background entry point) -------------------------
echo "Building jar..."
jar --create --file dist/rfid-bridge.jar \
    --main-class geoplanph.Daemon \
    -C build/classes .

echo ""
echo "Done -> dist/rfid-bridge.jar"
echo "Run manually with:  java -jar dist/rfid-bridge.jar"
