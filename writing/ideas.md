# Writing ideas from grod

Raw material for articles, a video and a website post. Every grod fact below comes from this repo (README, docs/, research/, source, git log). Every study is listed with its link in research/distraction-science.md.

## Angles

### 0. I blocked Reels and kept scrolling anyway

grod first removed the Reels and Shorts feeds by reading the screen. The user's own data showed Instagram time rising (54 → 86 → 103 minutes a day) while grod bounced them out of Reels 45 times in one morning: the home feed had become a reel feed of its own. grod switched to counting scrolling instead of recognizing screens, with a pause that doubles each time. The piece is about measuring behavior instead of chasing an app's layout.

- Research: Meinhardt 2026 (growing friction stays effective; simple pop-ups get ignored); Grüning 2023 (a delay plus a way out changes the habit).
- grod facts: docs/rules.md "Scroll pause" (5 s cap per scroll event, 60 s free, 10 s doubling to 300 s, reset after 15 min); the Android service runs with window content access off.

### 1. Blocking Instagram doesn't last. Removing Reels might.

Blocking the whole app also blocks friends' stories and DMs, so the blocker is off by Friday. grod removes only the endless feed and leaves the rest of the app. The piece walks through what that means app by app, and why "one item is fine, the next one is not" is the line it draws.

- Research: Purohit 2023 (no feed cut Facebook time by 64%, a reduced feed by 39%); Lyngs 2020 (feed removal helped but caused fear of missing out; let people define what distracts them); Zhang 2022 (redesign raised the sense of control, dashboards did not); Chiossi 2023 (only the short-video group got worse at remembering planned tasks).
- grod facts: the README table (Instagram keeps Stories, posts, DMs, search; YouTube keeps Home, subscriptions, normal videos). A reel a friend sends in a DM plays once; swiping to the next one triggers Back. On the web, `/reel/<id>/` (one shared reel) stays open and `/reels/` (the feed) is blocked. A Short opened on the web becomes a normal video.

### 2. Instagram calls Stories "reel": reading apps through the accessibility tree

grod's Android app sees screens only through view ids and labels. The ids are full of traps, and this piece is a field guide to them: what an accessibility service can see, how to cover one tab without touching the rest, and what goes wrong.

- Research: Lyngs 2019 (most tools block or track; few remove the trigger itself).
- grod facts: Stories are `reel_viewer_root` inside Instagram, so a rule matching `reel_*` would kill Stories; Reels are `clips_*`. Instagram keeps hidden tabs in the tree, so only nodes visible to the user with non-empty bounds count. `uiautomator dump` suspends other accessibility services while it runs, so grod's covers vanish during a dump; check them with `screencap` instead. YouTube ignores accessibility clicks on the miniplayer's close button, so grod retries with taps (a Premium account would otherwise keep playing in the background). The cover is a `TYPE_ACCESSIBILITY_OVERLAY` window, so no "draw over other apps" permission is needed.

### 3. Why a VPN can't block Reels

The obvious approach is to block at the network. It fails for a plain reason: Reels, posts, stories and DMs all come from the same `cdninstagram.com` / `fbcdn.net` hosts, and DNS cannot see paths. The piece goes layer by layer (DNS, `/etc/hosts`, a network filter, a browser extension, the accessibility tree) and shows where the information grod needs actually exists.

- Research: Castelo 2025 (blocking all mobile internet for two weeks improved attention, but only 25.5% of participants kept the block fully on). Blocking everything works for the few who stick with it.
- grod facts: `/etc/hosts` sees only hostnames and cannot see `/shorts`. A macOS Network Extension content filter sees only IP and SNI over HTTPS, and a free Apple developer account cannot use one. An unsigned Safari extension has to be re-allowed every time Safari quits, so grod's macOS app guards Safari by reading the front tab URL every 1.5 s instead.

### 4. Notifications on the hour, not off

Turning notifications off makes people anxious about missing something. grod holds them silently and delivers the originals together on a schedule, while calls and alarms ring and calendar reminders pass at once. The piece covers what the studies say about batching and how Android makes it possible without root.

- Research: Fitz 2019 (N=237; three batches a day improved attention, productivity and mood; hourly helped little; no notifications raised anxiety). Pielot & Rello 2017 (a day without alerts: more productive, but anxious and less connected). Stothart 2015 (an unanswered notification still costs attention, though a preregistered study by Johannes et al. 2019 did not find a performance cost). Weber 2018 (people who snooze notifications mostly push them to later that day or the next morning).
- grod facts: held notifications are snoozed with `NotificationListenerService.snoozeNotification` until the next delivery time, and Android re-posts the originals, so reply buttons and taps still work. Do Not Disturb stays on with a policy that lets calls, repeat callers, alarms, media, events and reminders through. With `deliverEvery: 60` deliveries fall on the hour; `deliverAt` takes fixed times such as 09:00, 13:00, 18:00. The shipped rules use hourly, while the best evidence favors three a day. The README says to start hourly and move toward fixed times. That tension is worth stating openly.

### 5. A message deleted for everyone is still on your phone

A side effect of batching: grod logs every notification it sees, held or not, and its removal. A WhatsApp message the sender deleted for everyone remains in the history for 30 days. This is a short post about what a notification listener can see and why that data stays on the device.

- grod facts: one JSON line per event, newest day plain text, older days gzipped, deleted after `keepDays` (30). `grod phone search <text> --withdrawn` finds withdrawn messages. Honest caveat from the review skill: "withdrawn" also includes messages read on another device. Only adb on the user's own computer can read the logs; no account, no server, no analytics.

### 6. The settings screen is an AI agent

grod has no settings UI for rules. One file, `rules/grod.rules.json`, drives the phone, the browser and the Mac. You tell Claude Code "X's Explore page has been pulling me in" and it finds the hooks, writes the rule, validates it and pushes it out. The piece is about what changes when the user's agent, not a menu, is the interface, and what keeps it from breaking devices.

- Research: Lyngs 2020 (people should define their own distractions); Kovacs 2018 (unexplained changes nearly doubled uninstalls; a short explanation roughly halved that); Kovacs 2021 (people drift toward easier settings while planning to be stricter later; an agent makes loosening rules easy too).
- grod facts: every save is validated against a JSON Schema, so a bad edit is rejected instead of reaching a device. The MCP server has 13 tools. Each copy carries a `revision` (first 12 hex characters of the SHA-256 of the rules file), so "is my phone up to date" is a string comparison. Updates arrive with no rebuild: the macOS app in about 2 s, the extension within a minute, a phone on adb in seconds, a phone away from the computer within 6 h through an optional HTTPS URL. The `grod-review` skill reads the phone's own logs and proposes at most three changes, each with the number behind it.

### 7. A Turkish "Ç" broke my channel blocker

A short developer story. Channel matching broke on a YouTube handle that starts with "Ç". The same handle reaches grod in three forms: percent-encoded in links (`@%C3%87inMal%C4%B1`), precomposed (NFC) as YouTube links use it, and decomposed (NFD) in macOS process arguments, where "Ç" becomes "C" plus a combining cedilla. grod percent-decodes and normalizes to NFC before comparing, and tests cover each form.

- grod facts: `tools/src/channels.ts` (`normalizeHandle`), tests in `tools/test/channels.test.ts` and `extension/test/rules-core.test.mjs`.
- No research needed. Good as a short video or a website post for developers who handle non-English text.

## Numbers worth quoting

- Android app: about 0.9 MB, written in Kotlin, zero runtime dependencies (the debug build is 912.7 KB).
- One rules file, seven apps shipped (Instagram, YouTube, TikTok, Facebook, Snapchat, X, LinkedIn).
- 14 MCP tools in the grod server.
- Rule update delays: macOS ~2 s, extension ≤ 1 min, phone on adb ~5 s, phone away ≤ 6 h.
- Scroll pause defaults: 60 s of scrolling free, then 10, 20, 40, 80, 160, 300 s; 15 min away starts over.
- Notification history: 30 days by default.
- Fitz 2019: N=237; three deliveries a day helped, hourly helped little.
- Purohit 2023: removing the Facebook feed cut time on Facebook by 64%; a reduced feed by 39%.
- Kovacs 2018: rotating interventions nearly doubled uninstalls; a short explanation roughly halved that.
- Castelo 2025: two weeks without mobile internet improved attention for many, but only 25.5% of 467 participants fully kept it on.
- Holte 2020: grayscale cut about 38 minutes of screen time a day, but not video time.
- Chiossi 2023: in a study with 60 people, only the TikTok group got worse at remembering a planned action.
- Tested on one phone: Huawei P30 Pro, Android 10 (EMUI 12), Instagram 453, YouTube 21.43.

## Demo script (60–90 s)

1. **0–8 s.** Phone on screen, Instagram open. Voice: "I didn't want to delete Instagram. I wanted to stop scrolling it."
2. **8–25 s.** Scroll the home feed. After a minute the pause screen: "Instagram · 1 min of scrolling · 10 s". Tap Leave, or wait and continue.
3. **25–35 s.** Scroll again: the next pause is 20 s. Open a DM and a single post: no pause, that is not scrolling.
4. **35–45 s.** Laptop, same thing on YouTube in the browser: the pause covers the page and the video stops.
5. **45–55 s.** Claude Code in the repo: "Reddit has been pulling me in, add it." The agent writes one rule with the package and the domain; the phone and the browser pick it up with no reload.
6. **55–70 s.** Notifications: the phone stays silent for an hour, a call still rings, then at the top of the hour all held notifications arrive together and a reply button works.
7. **70–85 s.** Ask Claude: "What's been distracting me this week?" It reads the phone's own logs over adb, shows how often you left versus continued after a pause, and proposes up to three changes with numbers. Accept one.
8. **85–90 s.** Close on the repo: "One rules file, a small app that cannot read your screen, nothing leaves the device."
