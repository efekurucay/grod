## What's new

- **Packs instead of Urgent (Android).** The pause screen offers a pack: a few minutes without scroll pauses, for a chat that cannot wait. There are only a few (5 × 5 minutes to start) until they are refilled at the weekly review, so each one is a choice. When a pack ends, the next pause comes with the next scroll.
- **Scroll pauses keep coming (Android).** A pause screen started right as the previous one closed could fail to show, and then no pause came until grod restarted. A screen that does not come up within 5 seconds no longer holds scrolling.

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
