#!/usr/bin/env bash
#
# install.sh — install Alpine Linux as a chroot package manager on the MF800 modem.
#
# It unpacks an Alpine armv7 minirootfs under /data/local/alpine (persistent)
# and installs two entry points:
#   - `alpine` (root shell wrapper, /data/local/mifi/alpine)
#   - `alpine` (app-side client in the mifi-helper bin dir) so the web terminal
#     can jump straight into Alpine without typing `root` first.
#
# chroot needs root, which the modem exposes via the root PTY channel
# (see ../root-shell/). Run that first if you have not.
#
# Usage:
#   ./install.sh            # (re)install rootfs + entry points
#   ./install.sh --keep     # keep existing rootfs (don't wipe installed packages)
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ADB="${ADB:-$HOME/Library/Android/Sdk/platform-tools/adb}"
VER="${ALPINE_VER:-3.20.10}"
ARCH="${ALPINE_ARCH:-armv7}"
MAJ="${VER%.*}"

R=/data/local/alpine
MIFI=/data/local/mifi
HELPER_FILES=/data/data/com.mifi.helper/files
HELPER_BIN="$HELPER_FILES/bin"
CACHE="$HERE/.cache"
F="alpine-minirootfs-${VER}-${ARCH}.tar.gz"
BASE="https://dl-cdn.alpinelinux.org/alpine/v${MAJ}/releases/${ARCH}"

KEEP=0
[ "${1:-}" = "--keep" ] && KEEP=1

adb_() { "$ADB" ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} "$@"; }
sh_()  { adb_ shell "$@"; }

echo "== 1/5 fetch Alpine minirootfs $VER ($ARCH) =="
if [ "$KEEP" = "1" ]; then
    echo "   (--keep) tidak mengunduh/mengekstrak ulang"
else
    mkdir -p "$CACHE"
    [ -f "$CACHE/$F" ]        || curl -fsSL -o "$CACHE/$F"        "$BASE/$F"
    [ -f "$CACHE/$F.sha256" ] || curl -fsSL -o "$CACHE/$F.sha256" "$BASE/$F.sha256"
    ( cd "$CACHE" && shasum -a 256 -c "$F.sha256" ) || { echo "sha256 mismatch"; exit 1; }
fi

echo "== 2/5 wait for device (root) =="
adb_ wait-for-device
[ "$(sh_ busybox id -u | tr -d '\r')" = "0" ] || { echo "adb shell is not root"; exit 1; }

echo "== 3/5 rootfs =="
sh_ "busybox mkdir -p $MIFI"
if [ "$KEEP" = "1" ]; then
    sh_ "test -x $R/bin/busybox && echo '   rootfs OK (kept)' || echo '   rootfs missing'"
else
    # Unmount any leftover bind mounts first, otherwise rm walks into /proc,/sys,/dev.
    sh_ "for m in $R/proc $R/sys $R/dev/pts $R/dev; do busybox umount -l \$m 2>/dev/null; done; busybox rm -rf $R 2>/dev/null; busybox mkdir -p $R"
    adb_ push "$CACHE/$F" "/data/local/$F" >/dev/null
    sh_ "busybox tar xzf /data/local/$F -C $R 2>/dev/null; test -x $R/bin/busybox && echo '   extracted' || echo '   EXTRACT FAILED'"
fi

echo "== 4/5 entry points =="
adb_ push "$HERE/profile" "$R/etc/profile" >/dev/null
adb_ push "$HERE/alpine"  "$MIFI/alpine"   >/dev/null
sh_ "chmod 755 $MIFI/alpine; chown root:root $MIFI/alpine; \
     busybox printf 'nameserver 127.0.0.1\nnameserver 8.8.8.8\n' > $R/etc/resolv.conf; \
     if [ -d /opt/bin ]; then busybox ln -sf $MIFI/alpine /opt/bin/alpine; fi; \
     echo '   root wrapper ready'"

if [ -d "$HELPER_BIN" ] || sh_ "test -d $HELPER_BIN"; then
    OWNER="$(sh_ "busybox stat -c '%u:%g' $HELPER_FILES" | tr -d '\r')"
    adb_ push "$HERE/alpine-client" "$HELPER_BIN/alpine" >/dev/null
    sh_ "chmod 755 $HELPER_BIN/alpine; chown $OWNER $HELPER_BIN/alpine; echo '   terminal client ready'"
else
    echo "   (mifi-helper bin dir not found; terminal client skipped)"
fi

echo "== 5/5 verify =="
sh_ "ALPINE_ROOT=$R busybox timeout 30 $MIFI/alpine apk --version"

echo
echo "Done."
echo "  web terminal:  alpine            (langsung masuk Alpine)"
echo "   atau:         root  ->  alpine"
echo "  di Alpine:     apk update && apk add <pkg>"
