#!/bin/bash
set -euo pipefail
MACOS_DIR="$(cd -- "$(dirname -- "$0")" && pwd)"
REPO_DIR="$(cd -- "$MACOS_DIR/.." && pwd)"
cd "$MACOS_DIR"
swift build -c release
BIN_DIR="$(swift build -c release --show-bin-path)"
APP="$MACOS_DIR/build/Grod.app"
mkdir -p "$APP/Contents/MacOS"
cp "$BIN_DIR/GrodBar" "$APP/Contents/MacOS/GrodBar"
PLIST="$APP/Contents/Info.plist"
cat > "$PLIST" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleIdentifier</key><string>dev.grod.bar</string>
<key>CFBundleName</key><string>Grod</string>
<key>CFBundleExecutable</key><string>GrodBar</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>1.0</string>
<key>CFBundleVersion</key><string>1</string>
<key>LSMinimumSystemVersion</key><string>26.0</string>
<key>LSUIElement</key><true/>
<key>NSAppleEventsUsageDescription</key><string>grod reads Safari’s front page URL and redirects distracting feeds using your rules.</string>
</dict></plist>
PLIST
/usr/libexec/PlistBuddy -c "Add :GrodRulesPath string $REPO_DIR/rules/grod.rules.json" "$PLIST"
# The app hands rule edits to the grod CLI; launchd gives it no PATH, so the node used at build time is recorded.
NODE_PATH="$(command -v node || true)"
if [ -n "$NODE_PATH" ]; then
  /usr/libexec/PlistBuddy -c "Add :GrodNodePath string $NODE_PATH" "$PLIST"
  /usr/libexec/PlistBuddy -c "Add :GrodCLIPath string $REPO_DIR/tools/bin/grod.js" "$PLIST"
else
  echo "node not found: blocking channels from the browser and changing settings will be unavailable" >&2
fi
# The weekly review watches for the phone on adb.
ADB_PATH="$(command -v adb || true)"
if [ -n "$ADB_PATH" ]; then
  /usr/libexec/PlistBuddy -c "Add :GrodAdbPath string $ADB_PATH" "$PLIST"
else
  echo "adb not found: the weekly review will not notice the phone" >&2
fi
codesign --force -s - "$APP"
printf 'Built %s\n' "$APP"
