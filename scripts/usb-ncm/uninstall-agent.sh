#!/bin/bash
# Remove the MF800 USB-internet launchd agent.
set -euo pipefail
LABEL="com.mf800.usb-ncm"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
launchctl unload "$PLIST" 2>/dev/null || true
rm -f "$PLIST"
echo "removed: $PLIST"
