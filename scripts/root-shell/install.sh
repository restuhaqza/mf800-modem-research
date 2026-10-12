#!/usr/bin/env bash
#
# install.sh — set up a real root shell in the MF800 mifi web terminal.
#
# What it does:
#   1. builds `rootd` (root PTY server) for armv7 with the NDK
#   2. installs it + a boot updater under /data/local/mifi (persistent)
#   3. appends a one-shot hook to /system/etc/init.qcom.post_boot.sh so init
#      recreates the persistent /opt + /bin symlinks and starts rootd at boot
#      (init runs that script as root at sys.boot_completed=1)
#   4. installs the `root` client into the mifi-helper bin dir (on the terminal
#      PATH) so you can type `root` in the web terminal
#
# Re-runnable (idempotent). Uninstall with ./uninstall.sh
#
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

ADB="${ADB:-$HOME/Library/Android/Sdk/platform-tools/adb}"
SDK="${SDK:-$HOME/Library/Android/Sdk}"
NDK_DIR="$(ls -d "$SDK"/ndk/* 2>/dev/null | head -1)"
[ -n "$NDK_DIR" ] || { echo "NDK not found under $SDK/ndk"; exit 1; }
PREBUILT="$(ls -d "$NDK_DIR"/toolchains/llvm/prebuilt/* | head -1)"
CC="$PREBUILT/bin/armv7a-linux-androideabi21-clang"
[ -x "$CC" ] || { echo "clang not found: $CC"; exit 1; }

MIFI_DIR=/data/local/mifi
BOOT=/system/etc/init.qcom.post_boot.sh
MARK="mifi-root-shell"
HELPER_FILES=/data/data/com.mifi.helper/files
HELPER_BIN="$HELPER_FILES/bin"

adb_() { "$ADB" ${ANDROID_SERIAL:+-s "$ANDROID_SERIAL"} "$@"; }
sh_()  { adb_ shell "$@"; }

echo "== 1/5 build rootd =="
"$CC" -O2 -Wall -o "$HERE/rootd" "$HERE/rootd.c"
file "$HERE/rootd"

echo "== 2/5 wait for device (root) =="
adb_ wait-for-device
ROOTUID="$(sh_ busybox id -u | tr -d '\r')"
[ "$ROOTUID" = "0" ] || { echo "adb shell is not root (expected ro.secure=0)"; exit 1; }

echo "== 3/5 install daemon + boot updater =="
sh_ "mkdir -p $MIFI_DIR/home"
adb_ push "$HERE/rootd"         "$MIFI_DIR/rootd"         >/dev/null
adb_ push "$HERE/rootd-up.sh"   "$MIFI_DIR/rootd-up.sh"   >/dev/null
adb_ push "$HERE/bashrc"        "$MIFI_DIR/home/.bashrc"  >/dev/null
sh_ "chown root:root $MIFI_DIR/rootd $MIFI_DIR/rootd-up.sh $MIFI_DIR/home/.bashrc; \
     chmod 755 $MIFI_DIR/rootd $MIFI_DIR/rootd-up.sh; chmod 644 $MIFI_DIR/home/.bashrc"

echo "== 4/5 install 'root' client into helper bin dir =="
OWNER="$(sh_ "busybox stat -c '%u:%g' $HELPER_FILES" | tr -d '\r')"
sh_ "mkdir -p $HELPER_BIN"
adb_ push "$HERE/root" "$HELPER_BIN/root" >/dev/null
sh_ "chown $OWNER $HELPER_BIN/root; chmod 755 $HELPER_BIN/root"
echo "   owner -> $OWNER"

echo "== 5/5 patch boot hook (idempotent) =="
if [ -n "$(sh_ "busybox grep '$MARK' $BOOT" | tr -d '\r')" ]; then
    echo "   hook already present"
else
    sh_ "mount -o rw,remount /system"
    adb_ push "$HERE/init-block.sh" "$MIFI_DIR/init-block.sh" >/dev/null
    sh_ "busybox cat $MIFI_DIR/init-block.sh >> $BOOT"
    sh_ "mount -o ro,remount /system 2>/dev/null || true"
    echo "   hook appended"
fi

echo "== start now =="
sh_ "/system/bin/sh $MIFI_DIR/rootd-up.sh"
sleep 1

echo "== verify (root channel on 127.0.0.1:9022) =="
sh_ "{ busybox printf 'SIZE 100 30\nid\nopkg --version\nexit\n'; busybox sleep 2; } | busybox nc 127.0.0.1 9022"

echo
echo "Done. In the web terminal type:  root"
echo "Entware:  root  ->  opkg install <pkg>   (opkg update first if needed)"
