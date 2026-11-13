## What's new

- **The scroll pause can't be skipped (Android).** Going Home or to Recents and back brings the pause back with the time it had left.
- **Urgent (Android).** The pause screen offers "Urgent" for when you really need the app now. It opens at once, then asks every 30 seconds of scrolling whether it is still urgent, so you see how long "just this" took.
- **Night hours.** From 00:00 to 06:00 (configurable) every answer to the opening check waits, Message too.
- **A quieter home screen.** Only apps you opened in the last 7 days are shown, most used first; the rest are a search away. Search by voice with the microphone.
- **Dock.** A fixed bottom row for the apps you need every day, drawn with their own logos in one color.
- **Uninstall suggestions.** Apps you installed and haven't opened for 30 days are listed in the grod app's Apps tab, one tap to uninstall or keep.

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
