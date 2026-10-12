#!/bin/zsh
# Build mifi-agent: binary static linux/arm (untuk modem) + native (untuk uji lokal).
#
# Modem = Android 4.4.4 / armv7 / bionic (tanpa glibc), jadi kita bangun binary
# static dengan CGO dimatikan — sama seperti trik speedtest-go.
#
# Pemakaian:
#   ./scripts/build-mini-agent.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/apps/mini-agent"
OUT="$APP/out"

command -v go >/dev/null 2>&1 || { echo "go tidak ditemukan di PATH" >&2; exit 1; }
[ -f "$APP/main.go" ] || { echo "sumber tidak ketemu: $APP/main.go" >&2; exit 1; }

mkdir -p "$OUT"
cd "$APP"

echo "• build linux/arm static (GOARM=7, CGO off) …"
env -u GOBIN GOFLAGS=-trimpath \
  CGO_ENABLED=0 GOOS=linux GOARCH=arm GOARM=7 \
  go build -ldflags "-s -w" -o "$OUT/mifi-agent-linux-arm" .

echo "• build native (uji lokal) …"
go build -o "$OUT/mifi-agent-native" .

echo
file "$OUT/mifi-agent-linux-arm" "$OUT/mifi-agent-native"
ls -lh "$OUT/mifi-agent-linux-arm" | awk '{print "  ukuran:", $5}'
echo
echo "Selesai. Pasang ke modem:  ./scripts/install-mini-agent.sh"
