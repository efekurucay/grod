#!/bin/bash
set -euo pipefail
MACOS_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
"$MACOS_DIR/build.sh"
mkdir -p "$HOME/Applications" "$HOME/Library/LaunchAgents"
APP="$HOME/Applications/Grod.app"
AGENT="$HOME/Library/LaunchAgents/dev.grod.bar.plist"
DOMAIN="gui/$(id -u)"
launchctl bootout "$DOMAIN/dev.grod.bar" 2>/dev/null || true
if [ -e "$APP" ]; then rm -rf "$APP"; fi
ditto "$MACOS_DIR/build/Grod.app" "$APP"
/usr/libexec/PlistBuddy -c 'Clear dict' "$AGENT"
/usr/libexec/PlistBuddy -c 'Add :Label string dev.grod.bar' "$AGENT"
/usr/libexec/PlistBuddy -c 'Add :ProgramArguments array' "$AGENT"
/usr/libexec/PlistBuddy -c "Add :ProgramArguments:0 string $APP/Contents/MacOS/GrodBar" "$AGENT"
/usr/libexec/PlistBuddy -c 'Add :RunAtLoad bool true' "$AGENT"
/usr/libexec/PlistBuddy -c 'Add :KeepAlive bool false' "$AGENT"
launchctl bootstrap "$DOMAIN" "$AGENT"
printf 'Installed %s\n' "$APP"
