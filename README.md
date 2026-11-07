<p align="center"><img src="brand/grod-icon.svg" width="96" alt="grod"></p>

<h1 align="center">grod</h1>

<p align="center"><b>Get rid of distracting. Keep what matters.</b><br>Scroll a feed for a minute, then wait. A little longer each time.</p>

---

Blocking Instagram outright also blocks your friends' stories and your DMs, so the blocker gets turned off by Friday. Blocking only the Reels tab does not work either: the home feed itself plays one video after another. grod does not care which part of the app you are in. It counts how long you scroll.

- Scroll for a minute in Instagram, YouTube, TikTok, X, LinkedIn, Facebook or Snapchat, on your phone or in the browser, and a pause screen appears: wait 10 seconds, or leave.
- Keep scrolling and the next pause is 20 seconds, then 40, 80, up to 5 minutes.
- Put the phone down for 15 minutes and it starts short again.
- Messages, posts you open, a video you watch, search: none of that is scrolling, so none of it is slowed down.

It comes as **grod for Android**, **grod for Chrome** (any Chromium browser: Chrome, Comet, Arc, Brave, Edge), **grod for macOS** and the **grod CLI**. It is driven by one rules file, and you change that file by telling an AI agent what distracts you.

## Why it works this way

- People learn to ignore a pop-up that asks "are you sure?". Friction that grows the longer you scroll kept working longest in a 7-day study of short-video use ([Meinhardt et al., IMWUT 2026](https://arxiv.org/abs/2607.15818)).
- A short delay with the option to back out is what changes the habit. With the one sec app, people backed out of about a third of openings and attempts fell by 57% over six weeks ([Grüning et al., PNAS 2023](https://doi.org/10.1073/pnas.2213114120)).
- The harm comes from fast switching between short clips. In one study, only short-form video hurt people's memory for planned tasks ([Chiossi et al., CHI 2023](https://doi.org/10.1145/3544548.3580778)). Scrolling is that switching.
- A blocker that explains itself keeps being used. When interventions change without explanation, uninstalls nearly double ([Kovacs et al., CSCW 2018](https://doi.org/10.1145/3274364)). The pause screen always says which app and how long you scrolled.

[research/distraction-science.md](research/distraction-science.md) has the full reading list.

## How it works

```
                 rules/grod.rules.json   one file, every platform
                           │
        grod CLI + MCP server: validate · save · push
      ┌────────────────────┼──────────────────────────┐
  macOS menu bar app    browser extension         Android app
  serves the rules      counts scrolling,         accessibility service:
  on localhost          shows the pause screen    counts scrolling,
                                                  shows the pause screen
```

- **Android:** an accessibility service receives only scroll events from the apps in the rules. It cannot read the screen: window content access is off. When the time is up it shows a full-screen pause with a countdown and "Leave".
- **Browser:** a Manifest V3 extension. It counts scrolling on the sites in the rules, shows the same pause inside the page and pauses videos. It also opens a Short as a normal video and keeps a few pure-feed pages (Instagram `/reels`, TikTok) closed.
- **macOS:** a menu bar app with a Settings window. From it you can switch rules on and off, tune the scroll pause, set notification batching and the weekly review. A status row shows whether the phone is connected and up to date. The app also serves the current rules to the extension, so the extension updates without a reload.
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

> "Reddit has been pulling me in lately, add it."
> "The pause comes too late, make it 30 seconds."
> "LinkedIn is fine, take it out."

A rule only names an app's Android package and its websites, so adding one takes the agent a few seconds. It validates the rule and pushes it out. **Nothing needs a rebuild or a reload.** The macOS app picks up a change in about 2 seconds, the extension within a minute, and a phone on adb (USB or Wi-Fi) within seconds.

By hand:

```sh
grod list                       # which apps and sites count
grod disable linkedin           # enable / disable / remove / add rule.json
grod scroll                     # pause settings
grod scroll free 30             # pause after 30 s of scrolling
grod scroll growth 3            # 10 s, 30 s, 90 s, …
grod sync                       # push again, e.g. after plugging the phone in
```

To keep one YouTube channel out of your feed, use YouTube's own *Don't recommend channel* in the video's ⋮ menu. It follows your account to every device.

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

Every notification, every scroll pause and what you did after it, and screen time per app are logged on the phone (format: [docs/data.md](docs/data.md)). Ask Claude *"what's been distracting me this week?"* The `grod-review` skill reads the data over adb (`phone_stats`) and suggests at most three changes, each backed by a number. You decide which to apply.

## Privacy

- Nothing leaves your devices. There are no accounts, no analytics and no server.
- The Android service receives only scroll and window-change events from the apps named in the rules. It cannot read the screen: window content access is off. It does not store or send anything except the pause log below.
- Network use:
  - The extension talks only to `127.0.0.1`.
  - The Android app goes online only for an optional rules URL you set yourself.
  - If you turn on launcher weather (`launcher.weather`), the launcher fetches weather from Open-Meteo with a rounded location, at most hourly; deny the location prompt to turn it off.
- The phone logs notifications, scroll pauses and screen time in the app's private storage. Logs are deleted after `keepDays` (30 by default), and only adb on your own computer can read them.

## Limits

- Scrolling counts everywhere in a listed app, including a long DM thread. Each scroll event counts at most 5 seconds, so slow reading costs little.
- Watching without scrolling is not slowed down: one long video, or Reels left looping on one clip.
- There is no iOS version and no Play Store build. The app is installed with adb.
- Tested on one Android 10 phone (EMUI 12) and in Chrome 157.

## Repository

| Path | What |
|---|---|
| `rules/` | The rules file and its JSON Schema |
| `tools/` | `grod` CLI and MCP server (TypeScript, runs on Node directly) |
| `extension/` | Browser extension (plain JS) with unit and real-browser tests |
| `android/` | Kotlin app, no runtime dependencies (≈0.9 MB) |
| `macos/` | Swift menu bar app |
| `ui/` | grod UI: calm tokens and components |
| `research/` | Evidence behind the design |
| `docs/` | Architecture and rule behavior |

Checks:

```sh
cd tools && npm test && npx tsc --noEmit
cd extension && npm test        # npm run e2e runs a real Chrome, see extension/README.md
cd macos && swift test
cd android && ./gradlew testDebugUnitTest assembleDebug
```
