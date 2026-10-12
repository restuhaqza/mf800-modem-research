#!/bin/zsh
# Install the `speedtest` CLI (Ookla speedtest.net) so it is available from the
# modem's web terminal.
#
# Engine: showwin/speedtest-go (pure Go). The device is Android 4.4.4 / armv7
# (bionic, no glibc), so we ship a *static* linux/arm binary built with CGO off.
# It runs with no libc and no Android linker. Two things are handled here:
#
#   1. A GOOS=linux Go binary does NOT read Android's CA store, so TLS to
#      speedtest.net fails with "certificate signed by unknown authority".
#      A tiny wrapper `speedtest` exports SSL_CERT_DIR=/system/etc/security/cacerts
#      before exec'ing the real `speedtest-go` binary.
#   2. The web terminal (PTY and /api/sh) runs as the helper's app uid (u0_a30)
#      with <helper filesDir>/bin on PATH, so the files are installed there.
#      They survive reboot (the helper only rewrites that dir when its own
#      `.installed` marker is missing — see HttpApiService.installBusybox).
#
# Requires Go 1.26+ (speedtest-go's go.mod) and network for the first build.
#
# Pemakaian:
#   ./scripts/install-speedtest.sh
#   SPEEDTEST_BIN=/path/to/speedtest-go ./scripts/install-speedtest.sh   # no build
#   ANDROID_SERIAL=0123456789ABCDEF ./scripts/install-speedtest.sh        # multi-device
set -euo pipefail

ADB="${ADB:-$HOME/Library/Android/Sdk/platform-tools/adb}"
VERSION="${SPEEDTEST_VERSION:-v1.8.3}"
PKG=/data/data/com.mifi.helper/files
BIN="$PKG/bin"

[ -x "$ADB" ] || { echo "adb tidak ditemukan: $ADB" >&2; exit 1; }
"$ADB" get-state >/dev/null 2>&1 || {
  echo "Device tidak terhubung (set ANDROID_SERIAL bila lebih dari satu)" >&2; exit 1; }

# 1) obtain the static arm binary (build unless SPEEDTEST_BIN is given)
SRC="${SPEEDTEST_BIN:-}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
if [ -z "$SRC" ]; then
  command -v go >/dev/null 2>&1 || { echo "go tidak ditemukan; set SPEEDTEST_BIN" >&2; exit 1; }
  echo "building github.com/showwin/speedtest-go@$VERSION (linux/arm) ..."
  # GOBIN dibiarkan kosong: `go install` menolak build silang bila GOBIN diset.
  # Hasil silang mendarat di <gopath>/bin/linux_arm/. Pakai GOPATH default agar
  # module cache terpakai ulang (dan tidak ada cache read-only untuk dibersihkan).
  env -u GOBIN GOFLAGS=-trimpath \
    CGO_ENABLED=0 GOOS=linux GOARCH=arm GOARM=7 \
    go install -ldflags "-s -w" "github.com/showwin/speedtest-go@$VERSION"
  GB="$(go env GOPATH)/bin"
  SRC="$GB/linux_arm/speedtest-go"
  [ -f "$SRC" ] || SRC="$GB/speedtest-go"
  [ -f "$SRC" ] || { echo "build gagal: binary tidak ditemukan" >&2; exit 1; }
fi
file "$SRC" | grep -q 'ARM' || { echo "bukan binary ARM: $SRC" >&2; exit 1; }

# 2) wrapper that points Go at Android's system CA store before exec
WRAP="$TMP/speedtest"
cat > "$WRAP" <<EOF
#!/system/bin/sh
SSL_CERT_DIR=/system/etc/security/cacerts
export SSL_CERT_DIR
exec $BIN/speedtest-go "\$@"
EOF
chmod 755 "$WRAP"

# 3) install into the helper's bin dir, owned by the app uid (web terminal user)
OWNER="$("$ADB" shell "/system/bin/busybox stat -c '%u:%g' '$PKG'" | tr -d '\r')"
"$ADB" push "$SRC"  /data/local/tmp/speedtest-go >/dev/null
"$ADB" push "$WRAP" /data/local/tmp/speedtest    >/dev/null
"$ADB" shell "D='$BIN'; cp /data/local/tmp/speedtest-go \"\$D/speedtest-go\"; \
  cp /data/local/tmp/speedtest \"\$D/speedtest\"; \
  chmod 755 \"\$D/speedtest-go\" \"\$D/speedtest\"; \
  chown $OWNER \"\$D/speedtest-go\" \"\$D/speedtest\"; \
  rm -f /data/local/tmp/speedtest-go /data/local/tmp/speedtest; \
  /system/bin/busybox ls -la \"\$D/speedtest\" \"\$D/speedtest-go\""

echo
echo "Installed as '$BIN/speedtest' (wrapper) + 'speedtest-go' (engine)."
echo "Try it from the web terminal:  speedtest --version  &&  speedtest"
