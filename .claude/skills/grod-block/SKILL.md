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

Read the current rules with the grod MCP tools (`list_rules`, `get_rule`) or `grod list`. Read `rules_guide` once per session; it explains how each platform applies a rule.

## 2. Find stable hooks

Web:
- Prefer URL patterns (`block` / `redirect`) for whole feed pages, and `hide` selectors for entry points (nav links, shelves).
- Selectors: landmarks, `href`, `data-*`, `aria-*`, custom element names. Never generated class names.
- Check maintained lists first: research/web-and-macos.md in the grod repo links them. Use the browser tools if available to inspect the live DOM.

Android (phone on adb):
```
adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml /tmp/ui.xml
```
Open the feed on the phone first, then dump. The dump pauses grod's own service while it runs, so judge overlays from `adb exec-out screencap -p`, not from a dump. Use `resource-id` (without `pkg:id/`) for `feed`, the tab button for `cover`, and something present only in the allowed case for `exempt`. research/android-accessibility.md in the grod repo lists known ids and traps (Instagram `reel_*` = Stories).

## 3. Write the rule

`upsert_rule` (MCP) or `grod add -`. The rule is validated before it is saved; fix and retry on errors. Fill `why` with what stays usable.

## 4. Push and verify

- Saving pushes the rule everywhere by itself: the macOS app and the extension pick it up within a minute, and a phone on adb within seconds. Report the revision.
- Verify where you can: on a connected phone, check that the Android app shows the new revision. When you are working in the grod repo, run its test suites as well.
- Tell the user what is now blocked, what stays usable, and anything you could not verify (for example, a view id you could not see on a real device).
