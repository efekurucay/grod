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

To get rule updates without a cable, put the rules file at an HTTPS URL, for example the raw GitHub URL of `rules/grod.rules.json` in a public repo or a gist. Enter that URL in the app. The app fetches it while you use the target apps, at most every 6 hours, or when you tap Refresh.

## Changing what is blocked

In this repo, tell Claude Code what distracts you, for example "X's Explore page has been distracting me, block it". The `grod-block` skill and the `grod` MCP server (`.mcp.json`) do the rest:
1. Find stable hooks: selectors or Android view ids.
2. Write and validate the rule.
3. Sync it.

By hand:

```sh
grod list                 # what is blocked where
grod disable tiktok       # or enable / remove / add rule.json
grod sync                 # update extension + Android copies, push to a phone on adb
```

| Platform | Picks up a change |
|---|---|
| macOS app | in about 2 s |
| Extension | within a minute, through the macOS app. Without it: `grod sync` + reload the extension |
| Android | in about 5 s if `grod sync` pushed it over adb; otherwise at the next URL fetch |

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
