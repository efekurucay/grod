---
name: grod-review
description: Review what has been distracting the user, from the phone's own data, and propose grod changes. Use for "son haftama bak", "beni en çok ne dağıtıyor", "weekly review", "bildirimler nasıl gidiyor", "kim mesaj silmiş", or any question about screen time, notifications or how often grod paused the user.
---

# Review distractions with data

The phone keeps its own logs (docs/data.md): every notification, every scroll pause and what the user did after it, and screen time per app. Nothing leaves the phone except over adb to this machine.

Works from any folder: it needs only the grod MCP tools (or the `grod` command).

1. Make sure the phone is reachable: `adb devices`. If it is not listed, ask the user to plug it in, or to run `wifi.sh` from the grod repo's android folder.
2. Get the numbers: call `weekly_reviews` with `take: true`. That saves this week's snapshot and lists past weeks. Read last week's JSON and notes too, and compare. For a different range, use `phone_stats` with `days`.
3. Read them for patterns, not for a dashboard:
   - Which apps send the most notifications, and at what hours.
   - Which apps take the most screen time.
   - Which rules pause the user most, and how often they leave versus continue after a pause (`events`: `paused`, `left`, `continued`). Many pauses with "continued" means the pause is too short to break the loop: suggest a lower `freeSeconds` or a higher `growth`. Mostly "left" means it works, unless many `returned` follow: that is the user going Home and straight back, which brings the same pause back with the time left.
   - Urgent on Android (`urgent`, then `still-urgent` every 30 s of scrolling): each `still-urgent` is 30 s scrolled on an "urgent" pass. Many of them, or urgent most nights, means Urgent became the way in; say it with the numbers.
   - The opening check (`openings` in `phone_stats`): how often each purpose was given and how long those sessions lasted. A purpose whose sessions run far past a couple of minutes is the user's "just a message" turning into browsing; say it with the numbers. To change how soon it asks again, the first quick window or the night hours (when every answer waits), use `set_opening_check`.
   - Distracting apps with a lot of screen time but few pauses: time spent watching, not scrolling. That is outside what grod does; say so rather than inventing a rule.
   - Notifications that were held but are clearly urgent for the user, which belong in `allow`.
4. Propose at most three changes, each with the number behind it. For example: "Instagram took 9 h this week and paused you 60 times, and you continued 50 of them: pause after 30 s instead of 60?" Apply a change only after the user agrees, with the grod tools (`set_scroll_pause`, `upsert_rule`, `allow_notifications`, `set_notification_batching`). Every edit pushes itself to all devices.
5. Write the review to `<week>.md` next to the snapshot (`~/.config/grod/reviews/`). Include: what changed since last week (numbers), what you proposed, what the user accepted. Next week starts from it. It stays out of the repository because it holds personal data.
6. To look something up in the history, such as a deleted WhatsApp message, use `search_notifications`. `withdrawnOnly` covers deleted messages but also messages read on another device, so say that when you report.

Keep the message text you quote to what the user asked for. It is their private data.
