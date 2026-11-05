# grod for macOS

Requires Swift 6.3, Xcode 26.6 and macOS 26. No external dependencies.

```sh
swift build
swift test
./build.sh
open build/Grod.app
```

The build embeds the absolute repository rules path in the app's Info.plist.
Set `GROD_RULES` when launching the executable to override it. Keep the repository
in place after installing; “Open rules file” opens that same file. Changes are
checked every two seconds, including atomic replacements. Invalid rules keep the
last valid snapshot; before the first valid load, `/rules.json` returns 503.

The Safari guard defaults to on. When Safari is frontmost, allow the macOS
Automation request so grod can read and change its front document URL. Permission
errors appear in the menu. Safari checks run every 1.5 seconds; CSS hiding is the
extension's responsibility. Other browsers are not controlled by the app.

`./install.sh` builds, copies the app to `~/Applications`, and replaces and loads
the `dev.grod.bar` LaunchAgent. It runs at login without restart-on-quit behavior.
