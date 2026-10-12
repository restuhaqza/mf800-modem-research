#!/usr/bin/env bash
# Serve slide deck "Ngoprek Modem" + buka browser.
# Pakai: ./scripts/serve-slides.sh [port]     (default 8901)
#
# Port 8901 sengaja: review-slides.mjs & audit-layout.mjs menarget
# http://127.0.0.1:8901/ngoprek-modem.html — jadi selama server ini
# hidup, tool review tinggal `node slides/review-slides.mjs`.
set -euo pipefail

PORT="${1:-8901}"
DIR="$(cd "$(dirname "$0")/../slides" && pwd)"
URL="http://127.0.0.1:${PORT}/ngoprek-modem.html"

if [[ ! -f "$DIR/ngoprek-modem.html" ]]; then
  echo "✗ slide tidak ketemu: $DIR/ngoprek-modem.html" >&2
  exit 1
fi

# Port bentrok? matikan server lama dulu.
if lsof -ti ":$PORT" >/dev/null 2>&1; then
  echo "• port ${PORT} terpakai — mematikan server lama…"
  lsof -ti ":$PORT" | xargs kill 2>/dev/null || true
  sleep 0.5
fi

echo "• slides : ${DIR}"
echo "• URL    : ${URL}"
echo "• stop   : Ctrl+C"

# Buka browser setelah server siap (macOS).
( sleep 0.7; open "$URL" ) &

cd "$DIR"
exec python3 -m http.server "$PORT"
