---
name: grod-block
description: Add or fix a grod rule when the user names something distracting ("X'in explore'u dikkatimi dağıtıyor", "block LinkedIn feed", "Reels engeli çalışmıyor", "shorts geri geldi"). Finds selectors or Android view ids, writes the rule, syncs every platform, and verifies.
---

# Block a distraction with grod

Goal: remove the distracting *part* and keep the rest of the app usable. Blocking a whole host or app is the last resort, only when the whole thing is the feed (TikTok).

## 1. Pin down the target

Ask yourself, not the user, unless it is truly ambiguous:
- Which surface: web (which host and path), Android (which package), macOS native app?
- What exactly is the feed, and what must keep working (posts, DMs, search, single shared items)?

Read the current rules with the grod MCP tools (`list_rules`, `get_rule`) or `node tools/bin/grod.js list`. Read `rules_guide` (or docs/rules.md) once per session.

## 2. Find stable hooks

Web:
- Prefer URL patterns (`block` / `redirect`) for whole feed pages, and `hide` selectors for entry points (nav links, shelves).
- Selectors: landmarks, `href`, `data-*`, `aria-*`, custom element names. Never generated class names.
- Check maintained lists first: research/web-and-macos.md links them. Use the browser tools if available to inspect the live DOM.

Android (phone on adb):
```
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml /tmp/ui.xml
```
Open the feed on the phone first, then dump. Use `resource-id` (without `pkg:id/`) for `feed`, the tab button for `cover`, and something present only in the allowed case for `exempt`. research/android-accessibility.md lists known ids and traps (Instagram `reel_*` = Stories).

## 3. Write the rule

`upsert_rule` (MCP) or `node tools/bin/grod.js add -`. The rule is validated before it is saved; fix and retry on errors. Fill `why` with what stays usable.

## 4. Push and verify

- `sync` (MCP) or `node tools/bin/grod.js sync`. Report the revision.
- The macOS app and the extension update within a minute. The phone updates if it is on adb, or at its next URL fetch.
- Verify where you can: run the test suites (`cd tools && npm test`, `cd extension && npm test`), and on a connected phone check the Android app shows the new revision.
- Tell the user what is now blocked, what stays usable, and anything you could not verify (for example, a view id you could not see on a real device).
