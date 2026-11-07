# On-device data

grod keeps a few local logs so you (or an agent) can see what distracts you and tune the rules. Nothing is sent anywhere: the logs live in the Android app's private storage, and `grod phone …` reads them over adb.

## Files (Android, `files/log/` in app storage)

One JSON object per line. The newest day is plain text and older days are gzipped (`.jsonl.gz`). Files older than `notifications.keepDays` (default 30) are deleted.

`notifications-YYYY-MM-DD.jsonl`, one line per event: one `posted` line per message or entry, with the full text where the app provides it, and one `removed` line when a notification goes:

```json
{"t":1762394400123,"e":"posted","pkg":"com.whatsapp","app":"WhatsApp","cat":"msg","title":"Ayşe","text":"geliyor musun?","held":true}
{"t":1762394460000,"e":"removed","pkg":"com.whatsapp","title":"Ayşe","reason":"app"}
```

- `e`: `posted` or `removed`. `reason` on removals: `app` (the app withdrew it: a message deleted for everyone, but also a chat read on another device), `user`, `delivered` (grod released it), `other`.
- `held`: true when grod snoozed it to the next delivery.
- What a `posted` line holds:
  - Chat notifications (MessagingStyle, such as WhatsApp): one line per message, earlier messages included. `title` is the conversation, `text` the message, starting with `Sender: ` in group chats and for anyone other than the person a one-to-one chat is named after (`You: ` for your own replies). `t` is the message's own time when the app gives one.
  - Inbox notifications (InboxStyle, such as a mail app's "5 new messages"): one line per listed entry, `text` the whole line.
  - Anything else: `title` and the full text (`android.bigText`, else `android.text`).
  - An update logs only what it adds, so a chat that posts all its messages again logs each message once. A plain notification is logged again with the same text only after it was removed. A group summary logs only entries its group's notifications did not already log, and nothing when it lists no entries of its own. Ongoing notifications (players, downloads) are logged once.
- `removed` lines carry the same `title` as the notification's `posted` lines (the conversation for chats).

The app's Notifications tab shows the `posted` lines as a history, newest first, with search and an app filter. A row is tagged "held" when grod held it, and "removed by app" when a `removed` line with reason `app` has the same package and title; chat apps also do that when the chat is read on another device, so it does not mean the sender deleted the message. Deleting there rewrites these files atomically, on the log thread. Swiping a row away deletes its `posted` line after a few seconds of undo. Bulk deletes ask first, with the count: all from the filtered app, all from one conversation or sender (an app's lines with that `title`, long-press a row), one day (long-press its header), everything logged before the start of the day 7 days ago, or everything; they take the `posted` and `removed` lines in their scope. Any `removed` line also goes once no `posted` line with its package and title is left. Deleted entries are gone for good, including for `grod phone …` and the weekly review.

`events-YYYY-MM-DD.jsonl`, one line each time grod acts. A scroll pause logs `paused` when it appears and `left` or `continued` when it ends; the launcher logs `opened` or `backed-out` for a distracting app (`rule: "launcher"`).

```json
{"t":1762394400123,"rule":"instagram","pkg":"com.instagram.android","action":"paused"}
{"t":1762394410456,"rule":"instagram","pkg":"com.instagram.android","action":"left"}
```

## Export

Release builds are not debuggable, so the private files are read through an export into the app's external files dir, which `adb pull` can reach:

```sh
adb shell am broadcast -a dev.grod.EXPORT -n dev.grod/.ExportReceiver
adb pull /sdcard/Android/data/dev.grod/files/export/ .
adb shell am broadcast -a dev.grod.EXPORT_CLEAR -n dev.grod/.ExportReceiver
```

`dev.grod.EXPORT` writes into `/sdcard/Android/data/dev.grod/files/export/`:

- `usage.json`: foreground time per app per local day for the last `keepDays` days, read from `UsageStatsManager` (usage access: `android/install.sh` or the app's setup list). One app counts as in front at a time: it stops counting when another app comes to the front or the screen turns off. Without usage access it has an `"error"` field and no days.
- `log/`: a copy of every file in `files/log/` (the logs above). The private originals stay.

```json
{"generated":1762394400123,"days":{"2026-11-06":{"com.instagram.android":5400000,"com.google.android.youtube":1800000}}}
```

`dev.grod.EXPORT_CLEAR` deletes the export dir, so the copies do not linger in shared storage. Both actions need the `DUMP` permission, which `adb shell` has and apps do not. Debug builds are also debuggable, so `adb shell run-as dev.grod cat files/…` still works there.
