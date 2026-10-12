#!/system/bin/sh
#
# rootd-up.sh — run by init as root at boot (see init-block.sh).
# Creates the persistent /opt + /bin symlinks Entware needs (rootfs is
# read-only), then starts the root PTY daemon.
#
# Managed by scripts/root-shell/install.sh — edit there and re-run.
#
BB=/system/bin/busybox
MIFI=/data/local/mifi

# Entware requires the absolute path /opt (its loader is /opt/lib/ld-linux.so.3);
# package postinst/preinst scripts use #!/bin/sh. Neither exists on Android and
# rootfs is read-only, so recreate them every boot.
if [ ! -d /opt ] || [ ! -d /bin ]; then
    mount -o rw,remount / 2>/dev/null
    [ -d /opt ] || $BB ln -sf /data/local/entware /opt
    [ -d /bin ] || $BB ln -sf /system/bin /bin
fi

mkdir -p "$MIFI/home"

# Start the root PTY daemon once (loopback only). rootd daemonizes itself so it
# survives this init oneshot script exiting. NB: match "rootd -d" so the guard
# doesn't match this script's own path (/data/local/mifi/rootd-up.sh) in ps.
if ! $BB ps | $BB grep -q '[r]ootd -d'; then
    "$MIFI/rootd" -d 9022
fi

# Start Entware services (cron, etc.) when any are installed.
if [ -x /opt/etc/init.d/rc.unslung ]; then
    /opt/etc/init.d/rc.unslung start >/dev/null 2>&1 &
fi

# Make the web terminal backend start at boot. Its BOOT_COMPLETED receiver is
# unreliable on this firmware, so kick it explicitly (best-effort).
if ! $BB ps | $BB grep -q 'com[.]mifi[.]helper'; then
    am startservice -n com.mifi.helper/.HttpApiService >/dev/null 2>&1
fi

exit 0
