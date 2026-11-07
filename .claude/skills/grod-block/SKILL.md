---
name: grod-block
description: Add, change or loosen a grod rule when the user names something distracting or says the pause is wrong ("X dikkatimi dağıtıyor", "LinkedIn'e de koy", "Reddit'i ekle", "bekleme çok kısa", "pause çok sık geliyor", "grod çalışmıyor"). Covers the scroll pause settings and per-app rules on the phone, browser and Mac.
---

# Add a distraction to grod

grod does not look at the screen. A rule only names where scrolling counts: an app's Android packages and its web hosts. After `scroll.freeSeconds` of scrolling there, a pause screen makes the user wait, longer each time (docs/rules.md "Scroll pause"). Read `rules_guide` once per session.

## 1. Add or change an app

- Find the Android package: `adb shell pm list packages | grep -i <name>` (phone on adb, see the `android-phone` skill), or the Play Store URL `id=`.
- Web hosts: the bare domain (`reddit.com` also covers `www.` and `old.`).
- Write it with `upsert_rule` (MCP) or `grod add -`:
  ```json
  {"id": "reddit", "title": "Reddit", "why": "Scrolling Reddit pauses you every minute, a little longer each time. Search and single threads stay.", "enabled": true,
   "web": {"hosts": ["reddit.com"]}, "android": {"packages": ["com.reddit.frontpage"]}}
  ```
- `web.redirect` / `web.block` only for a URL that is nothing but a feed (YouTube `/shorts/…` → `/watch?v=…`, Instagram `/reels`). `macos.quitApps` only for a native Mac app that is all feed.
- "Too strict for this app" → `set_rule_enabled false` rather than delete, so it can come back.

## 2. Change how strict it is

`set_scroll_pause` (MCP) or `grod scroll free|first|growth|max|reset <n>`: free seconds before each pause, first pause, growth factor, longest pause, minutes without scrolling that start over. Change one value at a time and tell the user what it means in practice (e.g. "pauses now come every 2 minutes: 10 s, 20 s, 40 s…").

## 3. Push and verify

- Saving pushes everywhere by itself: the macOS app at once, the extension within a minute, a phone on adb within seconds. Report the revision.
- On a connected phone, open the app and scroll for longer than `freeSeconds`; the pause screen should appear. In the grod repo, run the test suites (CLAUDE.md "Checks") after code changes.
- Tell the user what now pauses, what stays free, and anything you could not verify.
