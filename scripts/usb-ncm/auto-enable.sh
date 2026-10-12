#!/bin/bash
#
# Lightweight watchdog invoked by the launchd agent (see install-agent.sh).
# If the modem LAN is already reachable, do nothing: this keeps ADB stable
# (running adb commands every 30s makes macOS drop/restart the adb handle).
# Only when the modem is not reachable do we run the full enabler.
#
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"

if ping -c1 -t1 192.168.100.1 >/dev/null 2>&1; then
    exit 0
fi
exec "$HERE/enable-usb-ncm.sh"
