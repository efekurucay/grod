# Architecture

grod lets you scroll a feed for a minute, then makes you wait, a little longer each time. It does not look at what is on the screen, only at whether you scroll and where, so it works the same in every app and site and does not break when an app changes its layout. research/distraction-science.md explains why growing friction beats a hard block or a usage dashboard.

One rules file drives every platform. An AI agent (Claude Code, Codex) edits it through the grod MCP server or CLI, and each platform picks the change up.

```
                      rules/grod.rules.json  <- single source of truth
                                 |
                  tools/: grod CLI + MCP server
                  validate -> atomic save -> sync
           _____________________|______________________________
          |                      |                             |
   macos/ menubar app     extension/ (Chrome, Firefox)    android/ app
   - polls the rules      - polls 127.0.0.1:47770         - AccessibilityService:
     file (2 s)             every minute                    scroll events from
   - serves it on         - scroll pause on rule hosts      target apps only
     127.0.0.1:47770      - redirect, block               - scroll pause screen
   - Safari guard         - bundled copy until the        - rules: bundled, adb push,
   - quits whole-feed       first fetch                     or HTTPS URL
     apps
```

## Why each piece exists

- **Rules file + schema**: one place to change, validated before every save, so a bad agent edit is rejected instead of breaking devices. Behavior is specified in docs/rules.md and mirrored by each platform's tests.
- **MCP server**: lets an agent list, read, write and sync rules with schema errors fed back to it. The `grod-block` skill holds the workflow for "this distracts me lately".
- **Extension**: counts scrolling on the sites of the rules and shows the pause screen in the page. It also redirects or blocks a few URLs that are nothing but a feed. It owns Chromium browsers and Firefox.
- **macOS app**: a local hub, not a second browser enforcer. It hands fresh rules to the extension without a reload, guards Safari (no unsigned Safari extension survives a restart without a paid developer account), and quits native apps that are all feed.
- **Android app**: the phone is where the scrolling happens. An AccessibilityService receives only scroll and window-change events from the listed packages, with window content access off, so it cannot read the screen. VPN or DNS blocking cannot tell scrolling from reading a message: both come from the same hosts.

## Update paths

| Platform | How a rules change arrives | Delay |
|---|---|---|
| macOS app | polls the file | ~2 s |
| Extension | polls the macOS app; otherwise `grod sync` + reload | ≤ 1 min |
| Android, cable or Wi-Fi adb | `grod sync` pushes over adb; the app checks the file's mtime | ~5 s |
| Android, away | optional HTTPS URL (e.g. raw GitHub file), fetched on use every 6 h or on Refresh | ≤ 6 h |

Every copy carries `revision` (hash of the rules file), and every platform shows the revision it runs. A copy that fails validation is ignored; the last valid one stays active.

## Limits

- Scrolling counts everywhere in a listed app, including a long DM thread. The 5 s cap per scroll event keeps slow reading cheap.
- Sideloaded Android apps need "Allow restricted settings" before accessibility can be turned on; `android/install.sh` does it over adb.
