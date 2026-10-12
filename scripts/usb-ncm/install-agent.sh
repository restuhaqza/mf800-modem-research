#!/bin/bash
#
# Install a per-user launchd agent that keeps MF800 USB-internet enabled on
# this Mac: every 30s (and at login) it runs enable-usb-ncm.sh, which is a
# no-op unless the modem is attached and not yet in NCM mode.
#
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
LABEL="com.mf800.usb-ncm"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"

mkdir -p "$HOME/Library/LaunchAgents"
cat > "$PLIST" <<PLIST_EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key><string>$LABEL</string>
    <key>ProgramArguments</key>
    <array>
        <string>$HERE/auto-enable.sh</string>
    </array>
    <key>RunAtLoad</key><true/>
    <key>StartInterval</key><integer>30</integer>
    <key>StandardOutPath</key><string>/tmp/mf800-usb-ncm.log</string>
    <key>StandardErrorPath</key><string>/tmp/mf800-usb-ncm.log</string>
</dict>
</plist>
PLIST_EOF

launchctl unload "$PLIST" 2>/dev/null || true
launchctl load "$PLIST"
echo "installed + loaded: $PLIST"
echo "logs: /tmp/mf800-usb-ncm.log"
echo "remove with: $(dirname "$0")/uninstall-agent.sh"
