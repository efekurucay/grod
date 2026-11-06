<p align="center"><img src="brand/grod-icon.svg" width="96" alt="grod"></p>

<h1 align="center">grod</h1>

<p align="center"><b>Get rid of distracting. Keep what matters.</b><br>Removes Reels, Shorts and other endless feeds. Keeps the rest of the app.</p>

---

Blocking Instagram outright also blocks your friends' stories and your DMs, so the blocker gets turned off by Friday. grod takes out only the part that eats the evening: the infinite short-video feed. The rest of the app keeps working.

| App | Gone | Still there |
|---|---|---|
| Instagram | Reels feed, reels in Explore, Reels tab (browser) | Stories, posts, DMs, search, profile grids; one at a time: a reel sent to you, your own reels, reels from accounts you follow |
| YouTube | Shorts player, Shorts tab and shelves (browser) | Home, search, subscriptions, normal videos (a Short opens as a normal video on the web) |
| TikTok | Everything (the whole app is the feed) | — |
| Facebook (web) | Reels | Posts, groups, Messenger |
| Snapchat | Spotlight | Chats, Stories |
| Any YouTube channel you choose | Its videos everywhere: home, search, recommendations | Every other channel |

One item is fine. **Swiping on to the next one is what grod stops.**

It comes as **grod for Android**, **grod for Chrome** (any Chromium browser: Chrome, Comet, Arc, Brave, Edge), **grod for macOS** and the **grod CLI**. It is driven by one rules file, and you change that file by telling an AI agent what distracts you.

## Why it works this way

- Removing a feed beats blocking the app or showing a screen-time dashboard. Taking the Facebook newsfeed out cut time on Facebook by 39–64% ([Purohit et al., CHI 2023](https://doi.org/10.1145/3544548.3581187); [Lyngs et al., CHI 2020](https://arxiv.org/abs/2001.04180)). Changing the interface raised people's sense of control, while usage dashboards did not ([Zhang et al., CHI 2022](https://doi.org/10.1145/3491102.3517722)).
- The harm comes from fast switching between short clips. In one study, only short-form video hurt people's memory for planned tasks ([Chiossi et al., CHI 2023](https://doi.org/10.1145/3544548.3580778)).
- A blocker that tells you what it did keeps being used. When interventions change without explanation, uninstalls nearly double ([Kovacs et al., CSCW 2018](https://doi.org/10.1145/3274364)). grod always says which rule acted and what stays usable.

[research/distraction-science.md](research/distraction-science.md) has the full reading list.

## How it works

```
                 rules/grod.rules.json   one file, every platform
                           │
        grod CLI + MCP server: validate · save · push
      ┌────────────────────┼──────────────────────────┐
  macOS menu bar app    browser extension         Android app
  serves the rules      hides shelves and tabs,   accessibility service:
  on localhost          redirects Shorts,         leaves the Reels/Shorts feed
                        blocks feed pages         with Back
```

- **Android:** an accessibility service watches only the apps listed in the rules. It recognizes screens by view ids and labels, and leaves a Reels or Shorts feed with Back the moment it opens; the tabs stay visible, so nothing on screen is darkened. Videos of blocked YouTube channels get an overlay that lets the page scroll.
- **Browser:** a Manifest V3 extension with CSS hiding, redirects and blocked pages. It handles YouTube's and Instagram's in-page navigation.
- **macOS:** a menu bar app with a Settings window. From it you can switch rules on and off, set notification batching, manage blocked channels and set the weekly review. A status row shows whether the phone is connected and up to date. The app also serves the current rules to the extension, so the extension updates without a reload.
- **Agent:** the `grod` CLI and an MCP server. The rules are validated against a JSON Schema before every save.

Details: [docs/architecture.md](docs/architecture.md) and [docs/rules.md](docs/rules.md).

## Install

### Just the phone and the browser

Download the latest [release](https://github.com/efekurucay/grod/releases):

- **Android:** open `grod-<version>.apk` on the phone. Then follow the checklist at the top of the app: accessibility, notification access, Do Not Disturb access and usage access. On Android 13+, first go to Settings → Apps → grod → ⋮ → *Allow restricted settings*.
- **Browser:** unzip `grod-extension-<version>.zip`, open `chrome://extensions`, turn on Developer mode, click *Load unpacked* and pick the folder.

This gives you the default rules. To change them yourself, with an AI agent and the desktop app, set up the full version below.

### Everything (macOS + AI agent)

You need Node 22.18+, and adb for Android. To build the apps yourself you also need Xcode command line tools (macOS) and JDK 17 + the Android SDK (Android).

```sh
git clone https://github.com/efekurucay/grod && cd grod
cd tools && npm install && npm link && cd ..   # the grod command
cd macos && ./install.sh && cd ..              # menu bar app, starts at login
```

**Browser:** open `chrome://extensions`, turn on Developer mode, click Load unpacked and pick the `extension/` folder. This works the same way in Comet, Arc, Brave and Edge.

**Android:** turn on USB debugging, plug the phone in, then run:

```sh
cd android && ./install.sh
```

The script builds and installs the app and switches its accessibility service on, including the "restricted settings" step Android 13+ requires for sideloaded apps. It then pushes the rules. To lose the cable afterwards, run `./wifi.sh` once with the phone plugged in. The grod CLI and the macOS app reconnect to that address by themselves; after the phone restarts, run it again.

## Tell it what distracts you

Open this repo in [Claude Code](https://claude.com/claude-code). The `grod` MCP server is already configured in `.mcp.json`. Then just say what distracts you:

> "X's Explore page has been pulling me in lately, block it."
> "Reels are back on my phone."
> "Block this channel: https://youtu.be/…"

The `grod-block` skill finds stable hooks: CSS selectors on the web, view ids on Android (taken from a screen dump while the phone is on adb). It writes and validates the rule and pushes it out. **Nothing needs a rebuild or a reload.** The macOS app picks up a change in about 2 seconds, the extension within a minute, and a phone on adb (USB or Wi-Fi) within seconds.

By hand:

```sh
grod list                       # what is blocked where
grod disable tiktok             # enable / disable / remove / add rule.json
grod channel add @somechannel   # or a video or channel link
grod channel list
grod sync                       # push again, e.g. after plugging the phone in
```

### Blocking a YouTube channel

- **Browser:** a video's ⋮ menu → *grod: Block channel*, or right-click a video or channel link.
- **Phone:** YouTube → Share → *grod: block channel*.
- **Agent or terminal:** `grod channel add <link | @handle>`.

The channel's videos disappear from home, search and recommendations. If one is opened anyway, grod stops it and leaves the page.

## Notifications, delivered on the hour (Android)

Turn on batching and the phone stops pinging. App notifications are held: no sound, nothing in the shade. On the hour, the original notifications all arrive together, so reply buttons and taps work as usual. Calls and alarms always ring, and calendar reminders come through at once. In a field experiment with 237 people, getting notifications three times a day improved attention, productivity and mood. Turning notifications off entirely made people anxious, and hourly batches helped little ([Fitz et al., 2019](https://doi.org/10.1016/j.chb.2019.07.016)). Start hourly if that feels safer, then move toward fixed times.

```sh
grod notifications on
grod notifications every 60                # or 120, 180, …
grod notifications at 09:00,13:00,18:00    # fixed times instead
grod notifications allow com.whatsapp      # or category:reminder, or title:<contact name>
```

Or tell Claude: *"WhatsApp messages from my mom should come through right away."*

The phone keeps 30 days of notification history, so a message deleted for everyone can still be found: `grod phone search <text> --withdrawn`.

## Weekly review

Once a week (Sunday 20:00 by default), the phone asks to be connected. Plug it in, or keep it on Wi-Fi adb. The Mac saves the week's numbers to `~/.config/grod/reviews/` and shows *Weekly review ready*. Click it, and Claude Code opens with the `grod-review` skill. It compares the week with the previous ones, suggests at most three changes and writes notes next to the numbers. Reviews stay out of the repository because they hold personal data.

## Your own data, for your agent

Every notification, every time a rule acts, and screen time per app are logged on the phone (format: [docs/data.md](docs/data.md)). Ask Claude *"what's been distracting me this week?"* The `grod-review` skill reads the data over adb (`phone_stats`) and suggests at most three changes, each backed by a number. You decide which to apply.

## Privacy

- Nothing leaves your devices. There are no accounts, no analytics and no server.
- The Android service acts only inside the apps named in the rules. While one of its overlays is on screen, it also listens to other apps, but only to notice that you left so it can remove the overlay. It matches view ids and the labels a rule names (for YouTube channels, the tile descriptions). It does not store or send anything it sees.
- Network use:
  - The extension talks only to `127.0.0.1`.
  - The Android app goes online only for an optional rules URL you set yourself, and for resolving a channel you share to it (YouTube oEmbed).
  - If you turn on launcher weather (`launcher.weather`), the launcher fetches weather from Open-Meteo with a rounded location, at most hourly; deny the location prompt to turn it off.
- The phone logs notifications, rule actions and screen time in the app's private storage. Logs are deleted after `keepDays` (30 by default), and only adb on your own computer can read them.

## Limits

- Apps rename views and sites change their markup, so rules need upkeep. When something slips through, tell the agent. [research/](research/) records where each selector and id came from.
- The Android YouTube rules match English UI labels.
- There is no iOS version and no Play Store build. The app is installed with adb.
- Tested on one Android 10 phone (EMUI 12) with Instagram 453 and YouTube 21.43, and in Chrome 157.

## Repository

| Path | What |
|---|---|
| `rules/` | The rules file and its JSON Schema |
| `tools/` | `grod` CLI and MCP server (TypeScript, runs on Node directly) |
| `extension/` | Browser extension (plain JS) with unit and real-browser tests |
| `android/` | Kotlin app, no runtime dependencies (≈0.9 MB) |
| `macos/` | Swift menu bar app |
| `ui/` | grod UI: calm tokens and components |
| `research/` | Evidence and selector sources |
| `docs/` | Architecture and rule behavior |

Checks:

```sh
cd tools && npm test && npx tsc --noEmit
cd extension && npm test        # npm run e2e runs a real Chrome, see extension/README.md
cd macos && swift test
cd android && ./gradlew testDebugUnitTest assembleDebug
```
