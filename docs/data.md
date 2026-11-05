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

`events-YYYY-MM-DD.jsonl`, one line each time a rule acts:

```json
{"t":1762394400123,"rule":"instagram-reels","pkg":"com.instagram.android","action":"back"}
```

## Export

`adb shell am broadcast -a dev.grod.EXPORT -n dev.grod/.ExportReceiver` writes `files/export/usage.json`: foreground time per app per day for the last `keepDays` days, read from `UsageStatsManager`. Usage access is granted by `android/install.sh`.

```json
{"generated":1762394400123,"days":{"2026-11-06":{"com.instagram.android":5400000,"com.google.android.youtube":1800000}}}
```

The app is debuggable, so `adb shell run-as dev.grod cat files/…` reads these files. That is how `grod phone stats` and the MCP tools get them.
