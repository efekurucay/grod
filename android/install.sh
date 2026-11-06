#!/usr/bin/env bash
# Builds grod, installs it on the connected phone, enables its accessibility service and pushes the rules.
# Safe to run again: every step checks or overwrites rather than appends twice.
# With several devices attached, pick one with ANDROID_SERIAL=<serial>.
set -euo pipefail
cd "$(dirname "$0")"

PKG=dev.grod
SERVICE="$PKG/$PKG.GrodService"
SERVICE_SHORT="$PKG/.GrodService"
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

# A phone runs one signature: once a signed release is on it, updates must be signed the same way.
# Signing settings (GROD_KEYSTORE_FILE, …) come from the environment or ~/.config/grod/release.env.
if [ -z "${GROD_KEYSTORE_FILE:-}" ] && [ -f "$HOME/.config/grod/release.env" ]; then
  set -a; . "$HOME/.config/grod/release.env"; set +a
fi
if [ -n "${GROD_KEYSTORE_FILE:-}" ]; then
  say "Building signed release APK"
  ./gradlew -q assembleRelease
  APK="$(ls -t app/build/outputs/apk/release/grod-*.apk | grep -v unsigned | head -1)"
else
  say "Building debug APK"
  ./gradlew -q assembleDebug
  APK="$(ls -t app/build/outputs/apk/debug/grod-*-debug.apk | head -1)"
fi

say "Installing $APK"
if ! adb install -r "$APK" >/dev/null 2>&1; then
  echo "    The phone has grod signed with a different key. Uninstall it first (adb uninstall $PKG), then run this again." >&2
  exit 1
fi
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
# Apps that were running while accessibility was briefly off (YouTube, Instagram, …) keep a stale
# accessibility state until they restart: no tile descriptions, no tab covers. Restart every target app.
targets="$(node -e 'const r=require("../rules/grod.rules.json");console.log([...new Set(r.rules.flatMap(x=>x.android?.packages??[]))].join(" "))' 2>/dev/null || echo "com.google.android.youtube com.instagram.android")"
for app in $targets; do
  adb shell am force-stop "$app"
done
echo "    restarted target apps so they read the accessibility state again"
adb shell settings put secure accessibility_enabled 1

say "Granting notification access, Do Not Disturb access and usage access"
grant() {
  if out="$(adb shell "$@" 2>&1)" && ! printf '%s' "$out" | grep -qi -e error -e exception -e unknown; then
    echo "    ok: $*"
  else
    echo "    failed: $* (${out//$'\n'/ })"
  fi
}
grant cmd notification allow_listener "$PKG/$PKG.NotificationService"
grant cmd notification allow_dnd "$PKG"
grant appops set "$PKG" GET_USAGE_STATS allow

say "Pushing rules"
if command -v node >/dev/null && [ -f ../tools/bin/grod.js ]; then
  node ../tools/bin/grod.js sync
else
  adb shell mkdir -p "$(dirname "$PUSH_PATH")"
  adb push app/src/main/assets/rules.json "$PUSH_PATH" >/dev/null
  echo "    pushed bundled copy to $PUSH_PATH (grod CLI not available)"
fi

say "Done. Open grod on the phone to see the active revision."
