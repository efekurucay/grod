# On-device data

grod keeps a few local logs so you (or an agent) can see what distracts you and tune the rules. Nothing is sent anywhere: the logs live in the Android app's private storage, and `grod phone …` reads them over adb.

## Files (Android, `files/log/` in app storage)

One JSON object per line. The newest day is plain text and older days are gzipped (`.jsonl.gz`). Files older than `notifications.keepDays` (default 30) are deleted.

`notifications-YYYY-MM-DD.jsonl`, one line per event:

```json
{"t":1762394400123,"e":"posted","pkg":"com.whatsapp","app":"WhatsApp","cat":"msg","title":"Ayşe","text":"geliyor musun?","held":true}
{"t":1762394460000,"e":"removed","pkg":"com.whatsapp","title":"Ayşe","reason":"app"}
```

- `e`: `posted` or `removed`. `reason` on removals: `app` (the app withdrew it, for example a deleted message), `user`, `delivered` (grod released it), `other`.
- `held`: true when grod snoozed it to the next delivery.

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
