#!/usr/bin/env bash
# Builds grod, installs it on the connected phone, enables its accessibility service and pushes the rules.
# Safe to run again: every step checks or overwrites rather than appends twice.
# With several devices attached, pick one with ANDROID_SERIAL=<serial>.
set -euo pipefail
cd "$(dirname "$0")"

PKG=dev.grod
SERVICE="$PKG/$PKG.GrodService"
SERVICE_SHORT="$PKG/.GrodService"
APK=app/build/outputs/apk/debug/app-debug.apk
PUSH_PATH=/sdcard/Android/data/$PKG/files/rules.json

say() { printf '==> %s\n' "$*"; }

if [ -z "${JAVA_HOME:-}" ] && [ -d /opt/homebrew/opt/openjdk@17 ]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@17
fi
command -v adb >/dev/null || { echo "adb not found in PATH" >&2; exit 1; }
if [ "$(adb get-state 2>/dev/null || true)" != "device" ]; then
  echo "No phone ready: connect one with USB debugging on (adb devices)." >&2
  exit 1
fi

say "Building debug APK"
./gradlew -q assembleDebug

say "Installing $APK"
adb install -r "$APK" >/dev/null
echo "    installed"

say "Allowing restricted settings (needed on Android 13+ for sideloaded apps)"
if adb shell cmd appops set "$PKG" ACCESS_RESTRICTED_SETTINGS allow >/dev/null 2>&1; then
  echo "    allowed"
else
  echo "    not supported on this Android version, skipped"
fi

say "Enabling accessibility service $SERVICE"
current="$(adb shell settings get secure enabled_accessibility_services | tr -d '\r')"
others="$(printf '%s' "$current" | tr ':' '\n' | grep -v -x -e "$SERVICE" -e "$SERVICE_SHORT" -e null | paste -sd: - || true)"
# Some phones (Huawei, Android 10) do not rebind an updated service, so it is switched off and on again.
adb shell settings put secure enabled_accessibility_services "${others:-null}"
# The settings observer coalesces quick writes; without a pause it sees no change and keeps the old binding.
sleep 1
updated="${others:+$others:}$SERVICE"
adb shell settings put secure enabled_accessibility_services "$updated"
echo "    enabled (services: $updated)"
# YouTube stops publishing tile descriptions while accessibility is briefly off and only republishes
# them after a restart; without them its masks and channel blocking see nothing.
for app in com.google.android.youtube app.revanced.android.youtube; do
  adb shell am force-stop "$app"
done
echo "    restarted YouTube so it reads the accessibility state again"
adb shell settings put secure accessibility_enabled 1

say "Pushing rules"
if command -v node >/dev/null && [ -f ../tools/bin/grod.js ]; then
  node ../tools/bin/grod.js sync
else
  adb shell mkdir -p "$(dirname "$PUSH_PATH")"
  adb push app/src/main/assets/rules.json "$PUSH_PATH" >/dev/null
  echo "    pushed bundled copy to $PUSH_PATH (grod CLI not available)"
fi

say "Done. Open grod on the phone to see the active revision."
