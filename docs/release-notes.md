## What's new

- **grod launcher (Android):** a calm home screen. Clock, date and search on top; the apps you used in the last 14 days as a word cloud sized by use. Distracting apps stay small and open after a short pause you can back out of.
- **Nothing on screen is darkened.** Tabs and thumbnails stay as the app draws them; grod leaves a Reels or Shorts feed the moment it opens.
- **One reel at a time** for a reel someone sends you, your own reels and reels from accounts you follow. Opening one no longer bounces right back; swiping to the next one does.
- **Screen time** counts one app at a time and stops when the screen turns off, so weekly reviews no longer show hours of an app that was closed.
- **Wireless adb:** the CLI and the macOS app reconnect to the phone's saved Wi-Fi address on their own.

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
