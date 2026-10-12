#!/bin/zsh
# Uji cepat hotspot.apk: install, jalankan, lalu panggil API-nya lewat adb forward.
#   Pakai: ./scripts/test-hotspot.sh [serial]
set -euo pipefail

ADB="${ADB:-$HOME/Library/Android/Sdk/platform-tools/adb}"
SER="${1:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APK="$ROOT/apps/hotspot/out/hotspot.apk"

A=("$ADB")
[ -n "$SER" ] && A+=(-s "$SER")
C=("curl" "-s" "--max-time" "8")

[ -f "$APK" ] || { echo "APK belum dibangun: $APK" >&2; exit 1; }

echo "== install =="
"${A[@]}" install -r "$APK"

echo "== start activity =="
"${A[@]}" shell am start -n com.mifi.hotspot/.HotspotActivity >/dev/null

echo "== adb forward tcp:18090 -> tcp:8090 =="
"${A[@]}" forward tcp:18090 tcp:8090
sleep 2

echo "== /api/health =="
"${C[@]}" http://localhost:18090/api/health || true; echo
echo "== /api/status =="
"${C[@]}" http://localhost:18090/api/status || true; echo
echo "== /api/capabilities =="
"${C[@]}" -H 'X-Auth: admin' http://localhost:18090/api/capabilities || true; echo
echo "== /api/log =="
"${C[@]}" -H 'X-Auth: admin' http://localhost:18090/api/log || true; echo

echo
echo "buka di browser: http://localhost:18090/  (PIN default: admin)"
