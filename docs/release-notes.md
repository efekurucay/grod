## What's new

- **Scroll pause.** Scroll for a minute in Instagram, YouTube, TikTok, X, LinkedIn, Facebook or Snapchat, on the phone or in the browser, and a pause screen makes you wait 10 seconds or leave. Each pause is twice as long as the last, up to 5 minutes; 15 minutes without scrolling starts over. Tune it from the Mac app's Settings or by telling your agent.
- **grod cannot read your screen.** The Android service only learns that you scrolled and in which app; window content access is off.
- **Simpler rules.** A rule is an app's Android packages and its websites. Reels/Shorts screen detection, CSS hiding and grod's YouTube channel blocking are gone; use YouTube's own *Don't recommend channel* instead.
- **Screen time** counts one app at a time and stops when the screen turns off.

## Install

**Android** (Android 8+)
1. Download `grod-<version>.apk` below and open it on the phone. Allow installing from your browser or file manager when asked.
2. Open grod and follow the checklist at the top: turn on the grod accessibility service, then notification access, Do Not Disturb access and usage access.
   - On Android 13 and newer, the accessibility switch is greyed out for sideloaded apps at first. Go to Settings → Apps → grod → ⋮ → **Allow restricted settings**, then try again.
3. Optional: in the app, set a rules URL (for example the raw link to your own `grod.rules.json`) so rule changes reach the phone without a cable.

**Browser** (Chrome, Comet, Arc, Brave, Edge)
1. Download `grod-extension-<version>.zip` and unzip it.
2. Open `chrome://extensions`, turn on Developer mode, click **Load unpacked** and pick the unzipped folder.

**macOS menu bar app, CLI and the AI-agent workflow:** clone the repository and follow the README.

Signed with the grod release key. Updates install over the previous version.
