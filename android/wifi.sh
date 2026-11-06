#!/usr/bin/env bash
# Lets grod push rules over Wi-Fi so the cable can go. Run once with the phone on USB;
# Android turns it off again on reboot, so run it again after a restart.
set -euo pipefail
ip="$(adb -d shell ip -f inet addr show wlan0 | sed -n 's/.*inet \([0-9.]*\).*/\1/p' | head -1)"
[ -n "$ip" ] || { echo "Phone is not on Wi-Fi." >&2; exit 1; }
adb -d tcpip 5555 >/dev/null
sleep 2
adb connect "$ip:5555"
# The grod CLI and the macOS app reconnect to this address when the phone drops off adb.
mkdir -p "$HOME/.config/grod"
echo "$ip:5555" > "$HOME/.config/grod/phone-wifi"
echo "Unplug the phone: grod sync now reaches it at $ip while both are on the same network."
