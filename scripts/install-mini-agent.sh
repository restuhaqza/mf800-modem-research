#!/bin/zsh
# Install `mifi-agent` ke terminal web modem (dir bin milik helper).
#
# Sama seperti speedtest:
#  1. Binary Go GOOS=linux TIDAK membaca CA store Android, jadi TLS ke api.deepseek.com
#     gagal ("certificate signed by unknown authority"). Wrapper `mifi-agent` mengekspor
#     SSL_CERT_DIR=/system/etc/security/cacerts sebelum exec engine `mifi-agent.bin`.
#  2. Terminal web berjalan sebagai uid app (u0_a30) dengan <helper filesDir>/bin di PATH,
#     jadi file dipasang di sana (bertahan setelah reboot).
#
# Pemakaian:
#   ./scripts/build-mini-agent.sh && ./scripts/install-mini-agent.sh
#   MIFI_AI_KEY=sk-... ./scripts/install-mini-agent.sh        # sekalian tulis config
#   MINI_AGENT_BIN=/path/to/bin ./scripts/install-mini-agent.sh
set -euo pipefail

ADB="${ADB:-$HOME/Library/Android/Sdk/platform-tools/adb}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="${MINI_AGENT_BIN:-$ROOT/apps/mini-agent/out/mifi-agent-linux-arm}"
PKG=/data/data/com.mifi.helper/files
BIN="$PKG/bin"
HOME_D="$PKG/home"

[ -x "$ADB" ] || { echo "adb tidak ditemukan: $ADB" >&2; exit 1; }
"$ADB" get-state >/dev/null 2>&1 || {
  echo "Device tidak terhubung (set ANDROID_SERIAL bila lebih dari satu)" >&2; exit 1; }
[ -f "$SRC" ] || { echo "binary belum dibuild: $SRC (jalankan ./scripts/build-mini-agent.sh)" >&2; exit 1; }
file "$SRC" | grep -q 'ARM' || { echo "bukan binary ARM: $SRC" >&2; exit 1; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# wrapper yang mengarahkan Go ke CA store Android
WRAP="$TMP/mifi-agent"
cat > "$WRAP" <<EOF
#!/system/bin/sh
HOME=$HOME_D
export HOME
SSL_CERT_DIR=/system/etc/security/cacerts
export SSL_CERT_DIR
exec $BIN/mifi-agent.bin "\$@"
EOF
chmod 755 "$WRAP"

OWNER="$("$ADB" shell "/system/bin/busybox stat -c '%u:%g' '$PKG'" | tr -d '\r')"

"$ADB" push "$SRC"  /data/local/tmp/mifi-agent.bin >/dev/null
"$ADB" push "$WRAP" /data/local/tmp/mifi-agent     >/dev/null
"$ADB" shell "D='$BIN'; cp /data/local/tmp/mifi-agent.bin \"\$D/mifi-agent.bin\"; \
  cp /data/local/tmp/mifi-agent \"\$D/mifi-agent\"; \
  chmod 755 \"\$D/mifi-agent.bin\" \"\$D/mifi-agent\"; \
  chown $OWNER \"\$D/mifi-agent.bin\" \"\$D/mifi-agent\"; \
  rm -f /data/local/tmp/mifi-agent.bin /data/local/tmp/mifi-agent; \
  /system/bin/busybox ls -la \"\$D/mifi-agent\" \"\$D/mifi-agent.bin\""

# Config opsional (kalau MIFI_AI_KEY diset saat menjalankan script ini)
if [ -n "${MIFI_AI_KEY:-}" ]; then
  CFG="$TMP/.mifi-agent.json"
  cat > "$CFG" <<EOF
{
  "provider": "${MIFI_AI_PROVIDER:-deepseek}",
  "model": "${MIFI_AI_MODEL:-deepseek-flash}",
  "api_key": "$MIFI_AI_KEY"
}
EOF
  "$ADB" shell "mkdir -p '$HOME_D'" </dev/null
  "$ADB" push "$CFG" /data/local/tmp/mifi-agent.json >/dev/null
  "$ADB" shell "cp /data/local/tmp/mifi-agent.json '$HOME_D/.mifi-agent.json'; \
    chmod 600 '$HOME_D/.mifi-agent.json'; chown $OWNER '$HOME_D/.mifi-agent.json'; \
    rm -f /data/local/tmp/mifi-agent.json" </dev/null
  echo "• config ditulis ke $HOME_D/.mifi-agent.json (provider ${MIFI_AI_PROVIDER:-deepseek})"
else
  echo "• (tanpa API key — isi nanti: mifi-agent --config  /  export MIFI_AI_KEY=…)"
fi

echo
echo "Terpasang sebagai '$BIN/mifi-agent' (wrapper) + 'mifi-agent.bin' (engine)."
echo "Coba dari terminal web modem:"
echo "    mifi-agent --selftest"
echo "    mifi-agent --config"
echo "    mifi-agent \"cek sinyal dan jelaskan apa yang kamu lihat\""
