# grod for macOS

Requires Swift 6.3, Xcode 26.6 and macOS 26. No external dependencies.

```sh
swift build
swift test
./build.sh
open build/grod.app
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

Settings… (⌘,) changes rules, phone notification batching, blocked channels, the weekly
review time and the Safari guard. Every change runs the grod CLI (`GrodNodePath`,
`GrodCLIPath`), which validates, saves and pushes to the phone; the window shows the
phone's adb status (`GrodAdbPath`) while open and syncs a phone that is behind.
Colors in `Sources/GrodBar/GrodColors.swift` come from `ui/tokens.json` via `node ui/build.mjs`.
