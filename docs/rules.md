# Rules: format and behavior

`rules/grod.rules.json` is the only place distractions are defined. `rules/grod.rules.schema.json` is the formal schema; this page defines what each platform does with a rule. Every platform must behave the same way, so change this page when behavior changes.

## Revision

`revision` = first 12 hex chars of SHA-256 over the raw bytes of `rules/grod.rules.json`. The CLI and the macOS app compute it from the file. Copies handed to other platforms (`extension/rules.json`, Android assets, the localhost endpoint, the adb-pushed file) carry it as a top-level `"revision"` field. Each platform shows the revision it runs, so "is my phone up to date" is a string comparison.

A copy is accepted only if it parses, has `schemaVersion: 1` and a `rules` array. A bad copy is ignored and the last valid one stays active.

## Scroll pause

The main rule on every platform: scrolling in the apps and sites of enabled rules is allowed for a while, then a pause screen makes you wait, longer each time. grod does not look at what is on the screen; it only notices that you scroll and where. Settings live in the top-level `scroll` object; missing fields take the defaults in brackets.

1. **Targets:** on Android the `android.packages` of enabled rules, on the web the `web.hosts` of enabled rules (a host also matches its subdomains).
2. **Scroll time:** each scroll event in a target adds the time since the previous scroll event in a target, at most 5 s. The first scroll event of a session adds nothing. So steady scrolling counts in full, and a swipe after watching a video for a while adds 5 s. Playing a video, typing or reading without scrolling adds nothing.
3. **Pause:** each time the scroll time reaches `freeSeconds` (60), a pause screen covers the app or page for `firstPauseSeconds` (10) × `growth` (2) ^ n seconds, where n counts the pauses already shown in this session, capped at `maxPauseSeconds` (300). So the defaults give 10, 20, 40, 80, 160, 300, 300… The scroll time then starts again from 0.
4. **Session:** the scroll time and n are shared by all targets on one device (switching from Instagram to TikTok does not start over). When no target has seen a scroll event for `resetMinutes` (15), both go back to 0. Each device (phone, each browser) keeps its own session.
5. **Pause screen:** a full screen in grod UI with the rule title, its `why`, how many minutes were scrolled in this session and a countdown. "Leave" is always available: Android goes Home, the web closes the tab. "Continue" appears when the countdown ends and returns to the app or page. Scroll events while the pause screen is up are ignored. Videos on the page are paused while it shows. In the browser, while a pause runs, any target tab that scrolls shows the same pause with the time left, so a new tab or a reload does not skip it.
6. **Logging:** each pause is logged with the rule whose target was scrolled last (Android: `events-*.jsonl`, `action` `"paused"`, then `"left"` or `"continued"`; extension: a per-rule counter shown in the popup).

## Opening check (Android)

Before a distracting app opens, grod asks what it is for, and learns from how that went. Settings live in the top-level `opening` object (defaults in brackets). Targets are the `android.packages` of enabled rules plus `launcher.distracting`, plus apps marked distracting with a long-press in the launcher (minus those unmarked there).

1. **When:** a target app comes to the front, by any route (home screen, notification, recents, a link), after it was not in front for at least `awayMinutes` (5). Coming back sooner continues the same session without a question.
2. **The question:** a full screen in grod UI with the app's name and three one-tap answers: **Message**, **Look something up**, **Browse**, plus **Leave** (goes Home). The answer last given for this app is preselected. No typing.
3. **Message and Look something up** open the app at once with a time window: `quickMinutes` (2) until that purpose has 5 sessions in the last 14 days, then the 75th percentile of those sessions' lengths, clamped to 1–10 minutes. When the window is over and the app is still in front, the next scroll event shows the scroll pause screen at once, saying what the app was opened for and how long ago ("Opened for a message 3 min ago"). This counts as a scroll pause (docs "Scroll pause").
4. **Browse** shows a wait before the app opens: `scroll.firstPauseSeconds` × `scroll.growth` ^ n seconds, capped at `scroll.maxPauseSeconds`, where n counts the Browse answers given today (local midnight resets it). During the wait the screen asks "What's next after this?" (no answer needed). "Continue" appears when the wait ends; "Leave" is always there.
5. **Learning which answers hold:** a session lasts from the moment the app opens after the answer (after the wait, if there was one) until the app leaves the front or the screen turns off; a wait never counts against a window. A session of a quick purpose that outlasts its window has overrun. When a quick purpose has at least 5 sessions in the last 14 days and more than half overran, choosing it gets the Browse wait instead of opening at once, and the question screen says so ("Message: 6 of your last 10 ran over"). It earns the quick open back as soon as the share drops to half or less.
6. **Scroll pause** keeps running underneath in every case.
7. **Logging** (`events-*.jsonl`): `opening` with `purpose` (`message`, `lookup`, `browse`) when an answer is given, `left` when Leave is tapped, and `session` with `purpose` and `ms` when a session ends.

## Web links (extension, macOS Safari guard)

For a page URL, let `host` = hostname and `target` = pathname + search.

1. A rule applies when it is `enabled`, has `web`, and `host === h || host.endsWith("." + h)` for some `h` in `web.hosts`.
2. **redirect** runs first. For each `{from, to}` in order: if `new RegExp(from)` matches `target`, the new target is `target.replace(new RegExp(from), to)`, keeping only the matched part replaced (`$1`…`$9` refer to groups). If the new target differs, navigate to `origin + newTarget` with history replace, and stop.
3. **block** runs next. If any `new RegExp(pattern)` matches `target`, show the grod blocked page (rule title and `why`), which offers "Go back".
4. Checks run at page start and on every same-document navigation (pushState, replaceState, popstate, site-specific events such as YouTube's `yt-navigate-finish`).

## Android service

The accessibility service only receives scroll and window-change events from the target packages. It does not read window content (`canRetrieveWindowContent` is off), so it cannot see text, view ids or anything else on screen.

## Notifications (Android)

The top-level `notifications` object batches app notifications. Settings: `enabled`, `deliverEvery` (minutes) or `deliverAt` (fixed local times such as `["09:00", "13:00", "18:00"]`, which replaces `deliverEvery`), `allow` and `keepDays`.

While it is enabled:
- **Sound and vibration:** grod keeps Do Not Disturb on with a policy that lets through calls from anyone, repeat callers, alarms, media, and the `event` and `reminder` categories. Calls ring normally. Other notifications make no sound.
- **Holding:** each held notification is snoozed (`NotificationListenerService.snoozeNotification`) until the next delivery time. With `deliverEvery`, delivery times are aligned to the clock: with 60 they fall on the hour, with 120 on even hours. With `deliverAt`, held notifications wait for the next listed time. Android posts the original notifications again at that time, so reply buttons and taps work as usual. A notification that an app updates while held is snoozed again to the same delivery time.
- **Not held:**
  - anything matching an `allow` entry (every field in the entry must match: `package`, `category`, `titleContains`, case-insensitive);
  - category `call` or `alarm`;
  - ongoing and foreground-service notifications;
  - grod's own notifications.
- **History:** every notification grod sees is logged on the device (docs/data.md), held or not, and so is its removal. A message deleted for everyone still shows in the history. Logs older than `keepDays` are deleted.
- **Turning it off:** this restores the user's own Do Not Disturb state and stops holding new notifications. Ones already held still arrive at the time they were given, at most one interval later, because Android 10 cannot change the time of a notification that is already snoozed.

## Weekly review

The top-level `review` object (`weekday`, 1 = Monday to 7 = Sunday, and `time`) sets a weekly check-in.

- **Android:** at that time, the phone posts a grod notification: "Connect me to your computer for the weekly review". grod's own notifications are never held. Tapping it opens the app, which explains the step.
- **macOS app:** it notices when the phone appears on adb (USB or Wi-Fi). If the last review is more than 6 days old, it runs `grod review snapshot`, which saves the week's numbers (`phone_stats`) to `~/.config/grod/reviews/<YYYY>-W<ww>.json`. It then shows a notification. Clicking it opens Terminal with Claude Code on the `grod-review` skill, which reads the snapshot, writes `<YYYY>-W<ww>.md` next to it with what changed since last week and at most three proposals, and applies nothing without the user's yes.
- Reviews stay outside the repository, because they hold personal data.

## Launcher (Android)

grod includes an optional home screen. The user picks it as the default launcher in Android settings.

- **Layout:** from the top, the clock, the date (with the weather to its right when `launcher.weather` is on), an always-visible search field, then the apps as a word cloud: names only, in centered rows, alphabetical so words keep their place. No icons, no badges, no notification counts. Typing filters the cloud; Enter opens the first match.
- **Sizes:** each name has one of five sizes from how much the app was used in the last 14 days (foreground time plus launches from grod), on a log scale, recomputed at most once a day. At most three apps get the largest size. Bigger names use the foreground color, smaller ones the muted color; there are no per-app colors.
- **Distracting apps:** these are the packages named in any enabled `android` rule plus `launcher.distracting`. They always get the smallest, muted size whatever their use, and their use does not count toward the scale. Opening one goes through the opening check ("Opening check" above), which applies to every route into the app, not only the launcher.
- **Weather (`launcher.weather`, off by default):** the current temperature and a one-word sky ("14° · Cloudy") from Open-Meteo, for the phone's last known coarse location rounded to 0.1°, at most once an hour and only while the launcher is on screen. The location permission is asked once; denying it, or being offline, just hides the text.
- **Hidden apps:** `launcher.hidden` apps are left out of the list but still found by search.
- **Unused apps:** apps used less than a minute in the last 14 days leave the cloud and are found by search; two launches from grod in that time also keep an app in. Distracting apps follow the same rule. Without usage access every app is shown.
- **Long-press** an app for app info, hide/unhide, or mark/unmark as distracting. These choices stay on the phone, on top of the rules. To make one permanent everywhere, tell the agent or use the Mac app.

## macOS

- Settings window (menu → Settings…, ⌘,): rules on/off, scroll pause, notification batching, weekly review, Safari guard. Every change runs through the grod CLI, so the file keeps one writer and the change is pushed to the phone. A status row shows whether the phone is connected (USB or Wi-Fi) and whether it runs the current revision. While the window is open, a phone that is behind gets synced.
- `macos.quitApps`: bundle ids of enabled rules are quit when they launch or come to the front.
- Safari guard: while Safari is in front, the app reads the front tab URL every 1.5 s and applies the Web rules above. The blocked page is served at `http://127.0.0.1:47770/blocked?rule=<id>`. Chromium browsers and Firefox are left to the extension, so two enforcers never fight.
- `GET http://127.0.0.1:47770/rules.json` serves the current valid rules with `revision`. The extension polls it.

## Writing a good rule

- One rule per app or site: its Android packages and its web hosts. That is all the scroll pause needs.
- Add `web.redirect` or `web.block` only for a URL that is nothing but a feed (`/reels`, `/shorts/…`).
- Fill `why` with what stays usable. It is shown on the pause screen.
