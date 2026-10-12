#!/bin/bash
#
# MF800: give this Mac internet over USB.
#
# Why: the modem's stock USB network is RNDIS, and macOS ships no RNDIS driver.
# macOS *does* support CDC-NCM natively, and the modem firmware exposes an
# `ncm,adb` USB composition. This script switches the gadget to NCM at runtime
# and bridges the resulting `ncm0` interface into the modem's `br0` LAN, so the
# existing dnsmasq + NAT serve the Mac exactly like a Wi-Fi client.
#
# The switch is done over ADB (USB), so ADB must be available. Nothing is
# persisted on the modem: a reboot/re-plug returns it to the stock RNDIS mode,
# which keeps ADB and the OEM behaviour stable. Re-run this script (or install
# the bundled launchd agent) to re-enable USB internet.
#
# Usage:  ./enable-usb-ncm.sh
set -euo pipefail

ADB="${ADB:-$HOME/Library/Android/Sdk/platform-tools/adb}"
command -v "$ADB" >/dev/null 2>&1 || ADB=adb
say() { printf '%s\n' "$*"; }

# No device? nothing to do (also makes this safe to run from a launchd poll).
# A composition switch can make the Mac's adb server lose track of the device
# even though USB is healthy, so try a server restart before giving up.
state=$("$ADB" get-state 2>/dev/null || true)
if [ "$state" != "device" ]; then
    "$ADB" kill-server >/dev/null 2>&1 || true
    "$ADB" start-server >/dev/null 2>&1 || true
    sleep 2
    state=$("$ADB" get-state 2>/dev/null || true)
fi
[ "$state" = "device" ] || { say "no modem on USB (adb state: ${state:-none})"; exit 0; }
"$ADB" wait-for-device
say "modem: $("$ADB" shell getprop ro.product.model 2>/dev/null | tr -d '\r')  ($("$ADB" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r'))"

# Idempotent: already in NCM mode and the Mac already has a lease -> nothing to do.
STATE_USB="$("$ADB" shell getprop sys.usb.state 2>/dev/null | tr -d '\r')"
if [ "$STATE_USB" = "ncm,adb" ] && ifconfig -a 2>/dev/null | grep -q "inet 192\.168\.100\."; then
    say "already enabled (ncm,adb + 192.168.100.x lease); nothing to do."
    exit 0
fi

# If the gadget is ALREADY ncm,adb (e.g. ncm0 fell out of the bridge after an
# adbd restart), do NOT switch composition again: switching while adbd is alive
# trips the functionfs bug and drops ADB. Just bring ncm0 back below.
if [ "$STATE_USB" = "ncm,adb" ]; then
    say "USB already in ncm,adb; re-bridging ncm0 (no composition switch)."
else
    say "switching USB gadget -> ncm,adb (macOS-native CDC-NCM) ..."
    "$ADB" shell 'setprop sys.usb.config ncm,adb' >/dev/null

    # ADB re-enumerates during the switch; wait for it to come back.
    sleep 4
    for _ in $(seq 1 30); do
        if "$ADB" get-state >/dev/null 2>&1; then break; fi
        sleep 1
    done
    "$ADB" wait-for-device
fi

# Wait for the NCM netdev, then bridge it into the modem LAN.
for _ in $(seq 1 30); do
    if "$ADB" shell '[ -e /sys/class/net/ncm0 ]' >/dev/null 2>&1; then break; fi
    sleep 1
done
"$ADB" shell 'ip link set ncm0 up; brctl addif br0 ncm0 2>/dev/null; true' >/dev/null
say "modem bridge:"; "$ADB" shell 'brctl show' | tr -d '\r' | sed 's/^/  /'

# Wait for the modem's DHCP to lease the Mac an address on the NCM interface.
say "waiting for 192.168.100.x lease on the Mac interface ..."
IF=""
for _ in $(seq 1 30); do
    IF=$(ifconfig -a 2>/dev/null | awk '/^[a-z]/{i=$1} /inet 192\.168\.100\./{print i; exit}' | tr -d ':')
    [ -n "$IF" ] && break
    sleep 1
done

if [ -n "$IF" ]; then
    say "OK: $IF -> $(ifconfig "$IF" | awk '/inet 192\.168\.100\./{print $2}')  (default route via modem)"
    # macOS often drops adb's handle to the device across the composition
    # switch even though USB stays up; restart the server so adb keeps working.
    "$ADB" kill-server >/dev/null 2>&1 || true
    "$ADB" start-server >/dev/null 2>&1 || true
else
    say "WARN: no 192.168.100.x lease yet. Check: ifconfig -a | grep 192.168.100"
    say "      The modem side may need a moment; re-run this script if needed."
    exit 1
fi
