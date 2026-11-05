---
name: grod-review
description: Review what has been distracting the user, from the phone's own data, and propose grod changes. Use for "son haftama bak", "beni en çok ne dağıtıyor", "weekly review", "bildirimler nasıl gidiyor", "kim mesaj silmiş", or any question about screen time, notifications or how often grod blocked something.
---

# Review distractions with data

The phone keeps its own logs (docs/data.md): every notification, every time a rule acted, and screen time per app. Nothing leaves the phone except over adb to this machine.

1. Make sure the phone is reachable: `adb devices`. If it is not listed, ask the user to plug it in or run `android/wifi.sh`.
2. Pull the numbers: the `phone_stats` MCP tool (or `grod phone stats 7`). Use 7 days by default, or 30 for trends.
3. Read them for patterns, not for a dashboard:
   - Which apps send the most notifications, and at what hours.
   - Which apps take the most screen time.
   - Which rules fire most. A rule that fires many times a day means the entry point is still visible: hide it better (`cover`/`mask`/`hide`).
   - Notifications that were held but are clearly urgent for the user, which belong in `allow`.
4. Propose at most three changes, each with the number behind it. For example: "Instagram took 9 h this week and its Explore fired grod 40 times: mask Explore entirely?" Apply a change only after the user agrees, with the grod tools (`upsert_rule`, `allow_notifications`, `set_notification_batching`). Every edit pushes itself to all devices.
5. To look something up in the history, such as a deleted WhatsApp message, use `search_notifications`. `withdrawnOnly` covers deleted messages but also messages read on another device, so say that when you report.

Keep the message text you quote to what the user asked for. It is their private data.
