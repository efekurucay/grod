# Android: blocking feeds with an AccessibilityService

Sources read 2026-11-06. View ids change with app updates: when a rule stops firing, re-check these repos first and inspect the live tree with `adb shell uiautomator dump`.

## Prior art (ideas only for GPL repos)

| Project | Technique | License |
|---|---|---|
| [Scrolless](https://github.com/duartebarbosadev/Scrolless) | One view id per app, Back; exempts Reels opened from DMs | GPL-3.0 |
| [Curbox](https://github.com/curbox-app/curbox-android) | Node queries, counts reels | GPL-3.0 |
| [dh-focus-android](https://github.com/digitalhabits/dh-focus-android) | Covers single elements (Reels tab) with `TYPE_ACCESSIBILITY_OVERLAY`; rules in a text file | GPL-3.0 |
| [DetoxDroid](https://github.com/flxapps/DetoxDroid) | Scroll-break on known pagers | GPL-3.0 |
| [Shorts-Blocker](https://github.com/atick-faisal/Shorts-Blocker) | Capped BFS + Back, 1.5 s cooldown | Apache-2.0 |
| [Doomscrolling-Interruptor](https://github.com/MazterGD/Doomscrolling-Interruptor) | Rules as data, never reads `getText()` | — |
| [instagram-reelguard](https://github.com/pyerino/instagram-reelguard) | IG ids harvested per version | MIT |

grod's Android code is written from scratch; nothing is copied from the GPL projects.

## Ids in use

Instagram `com.instagram.android`:
- Reels viewer: `clips_viewer_view_pager` (most projects), alternatives `clips_video_container`, `root_clips_layout`, `clips_viewer_pager`.
- Bottom tabs: bar `tab_bar`, Reels `clips_tab`, Home `feed_tab` (verified on IG 453).
- Reel opened from a DM uses the same viewer. DM markers: `reply_bar_container`, `reel_viewer_message_composer`, `reply_bar_edittext`.
- **Trap:** Stories are called "reel" internally (`reel_viewer_root`, tray desc "reels tray container"). Never match `reel_*` on Instagram.
- IG keeps hidden tabs in the tree: only nodes with `isVisibleToUser` and non-empty bounds count.

YouTube `com.google.android.youtube` (and ReVanced `app.revanced.android.youtube`):
- Shorts player: `reel_player_page_container`, `reel_recycler`, `reel_watch_player`, `reel_progress_bar`.
- Shorts tab: Button in `pivot_bar` with content description "Shorts".
- Normal player `watch_player` is never touched.

TikTok `com.zhiliaoapp.musically`, `com.ss.android.ugc.trill`: the whole app is the feed.

Snapchat: Spotlight `spotlight_container`. `opera_viewer` is Stories, leave it.

Facebook `com.facebook.katana`: no stable ids. Selected node whose desc starts with "Reels," near the top; fragile.

## Verified on a device

Huawei P30 Pro, EMUI 12 (Android 10), 2026-11-06:
- Instagram 453.0.0.57.79: `clips_tab` (desc "Reels") covered; a `/reels/` deep link opened the viewer and grod pressed Back. Stories tray and feed untouched. Feed posts that are reels show a "Watch more reels" button that opens the viewer, which then gets the same Back.
- YouTube 21.43.523: bottom bar has 4 tabs; the Shorts tab is a node with desc "Shorts" inside `pivot_bar` and is covered. Tapping a Shorts thumbnail on Home opened the player and grod pressed Back within a second. The Home Shorts shelf stays visible: its header has desc "Shorts" (covered), its thumbnails have desc ending in "- play Short".
- `uiautomator dump` suspends other accessibility services while it runs, so covers vanish until the next app event. Check covers with `adb exec-out screencap`, not with a dump.

## Mechanics

- Cover a tab: `getBoundsInScreen` → `TYPE_ACCESSIBILITY_OVERLAY` view at those bounds, `FLAG_NOT_FOCUSABLE` only, so it swallows taps. No `SYSTEM_ALERT_WINDOW` needed. Move it when bounds change; remove it when the app leaves the front.
- Escape: `performGlobalAction(GLOBAL_ACTION_BACK)` with a ~1.5 s cooldown. If still in the feed, `GLOBAL_ACTION_HOME`.
- Light: `packageNames` limited to target apps in the service XML; events `typeWindowStateChanged|typeWindowContentChanged|typeViewScrolled`; `notificationTimeout` ~200 ms; `flagReportViewIds`; `findAccessibilityNodeInfosByViewId` (search runs in the target process). Never read `getText()` except for matching rules that ask for text.

## Sideload restrictions

- Android 13+: sideloaded apps need App info → ⋮ → **Allow restricted settings** before accessibility can be enabled. adb: `adb shell cmd appops set dev.grod ACCESS_RESTRICTED_SETTINGS allow`.
- Enable via adb: `adb shell settings put secure enabled_accessibility_services dev.grod/dev.grod.GrodService` and `adb shell settings put secure accessibility_enabled 1`.
- Play Store policy does not let a blocker claim `isAccessibilityTool`; irrelevant for sideloading.

## Why not VPN/DNS

Feed, stories, reels and DMs all come from the same `cdninstagram.com` / `fbcdn.net` hosts, and DNS cannot see paths. Blocking by network would break the whole app.
