# grod — get rid of distracting

grod removes the endless short-video feeds and leaves the rest of each app alone. Instagram stories, posts and DMs stay; Reels go. YouTube videos and search stay; Shorts go. A Short or a reel someone sends you still plays once. Scrolling on to the next one is what grod stops.

It runs on Android, in Chrome and Firefox, and on macOS, from one rules file. To change what is blocked, tell Claude Code what is distracting you. It edits the rules through the grod MCP server, and every platform picks up the change.

| Surface | What it does |
|---|---|
| **Android** (`android/`) | Covers the Reels and Shorts tabs so they cannot be tapped. If you reach the feed anyway (a link, Explore), it presses Back. TikTok goes to the home screen. |
| **Browser** (`extension/`) | Hides Shorts shelves and Reels links, turns `/shorts/ID` into a normal video page, and blocks `/reels/`, Facebook `/reel/` and TikTok. |
| **macOS** (`macos/`) | Menubar app. It hands fresh rules to the extension, applies the same web rules in Safari, and quits native apps that are all feed. |
| **Agent** (`tools/`) | `grod` CLI plus an MCP server: list, edit, validate and sync rules. |

Why this design: [research/distraction-science.md](research/distraction-science.md). Removing the feature works better, and gets uninstalled less, than blocking the whole app or showing a screen-time dashboard.

## Setup

Requirements: Node 22.18+, Xcode command line tools (macOS app), JDK 17 and the Android SDK (Android app), adb.

```sh
cd tools && npm install && npm link    # puts `grod` on PATH
cd ../macos && ./install.sh            # menubar app, starts at login
```

**Browser.** Open `chrome://extensions`, turn on Developer mode, choose Load unpacked, and pick `extension/`. For Firefox, see [extension/README.md](extension/README.md).

**Android.** Turn on USB debugging, connect the phone, then:

```sh
cd android && ./install.sh
```

The script installs the app, allows "restricted settings" (sideloaded apps need this before accessibility can be turned on), turns on the grod accessibility service and pushes the rules. Open grod on the phone to check that the service is on and to see the rules revision.

To drop the cable, run `android/wifi.sh` once with the phone plugged in: `grod sync` then reaches it over Wi-Fi until the phone restarts.

To get rule updates away from home, put the rules file at an HTTPS URL, for example the raw GitHub URL of `rules/grod.rules.json` in a public repo or a gist. Enter that URL in the app. The app fetches it while you use the target apps, at most every 6 hours, or when you tap Refresh.

## Changing what is blocked

In this repo, tell Claude Code what distracts you, for example "X's Explore page has been distracting me, block it". The `grod-block` skill and the `grod` MCP server (`.mcp.json`) do the rest:
1. Find stable hooks: selectors or Android view ids.
2. Write and validate the rule.
3. Sync it.

By hand:

```sh
grod list                 # what is blocked where
grod disable tiktok       # or enable / remove / add rule.json
grod sync                 # push again, e.g. after plugging the phone in
```

### Blocking a YouTube channel

A blocked channel's videos disappear from YouTube home, search and recommendations, and its watch and channel pages are blocked.

- **Browser**: open a video's ⋮ menu and choose "grod: Block channel". You can also right-click a video or channel link and choose "grod: Block channel".
- **Phone**: in YouTube, tap Share on a video or channel and pick grod.
- **Claude or the terminal**: `grod channel add <video link | channel link | @handle>`. Use `grod channel list` to see blocked channels and `grod channel remove <@handle>` to unblock one.

A channel blocked in the browser goes into the rules through the macOS app. A channel blocked on the phone works on the phone right away and joins the rules at the next `grod sync` with the phone connected.

Nothing needs a rebuild or a reload. Every change made through the CLI or MCP is pushed at once: the macOS app sees it in about 2 seconds, the extension within a minute, and a phone on adb (USB or Wi-Fi) within seconds.

| Platform | Picks up a change |
|---|---|
| macOS app | in about 2 s |
| Extension | within a minute, through the macOS app. Without it: `grod sync` + reload the extension |
| Android | in about 5 s when the phone is on adb (USB or Wi-Fi); otherwise at the next `grod sync` or URL fetch |

Each platform shows the rules revision it runs. Compare it with `grod revision`.

How a rule works on each platform: [docs/rules.md](docs/rules.md). How the parts connect: [docs/architecture.md](docs/architecture.md).

## Checks

```sh
cd tools && npm test && npx tsc --noEmit
cd extension && npm test            # npm run e2e for a real-browser run, see extension/README.md
cd macos && swift test
cd android && ./gradlew testDebugUnitTest assembleDebug
```

## When a rule stops working

Apps rename their views and sites change their markup. When something slips through, tell Claude Code ("Reels are back on my phone"). For Android, keep the phone on adb with the feed open, so it can dump the screen and update the view ids. [research/](research/) lists where the current selectors and ids came from.
