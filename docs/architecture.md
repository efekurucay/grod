# Architecture

grod removes the infinite short-video feed from apps and sites while keeping the rest of each app usable. Instagram stories, posts and DMs stay. Reels go. YouTube videos stay. Shorts go. research/distraction-science.md explains why removing the feature beats blocking the app.

One rules file drives every platform. An AI agent (Claude Code, Codex) edits it through the grod MCP server or CLI, and each platform picks the change up.

```
                      rules/grod.rules.json  <- single source of truth
                                 |
                  tools/: grod CLI + MCP server
                  validate -> atomic save -> sync
           _____________________|______________________________
          |                      |                             |
   macos/ menubar app     extension/ (Chrome, Firefox)    android/ app
   - polls the rules      - polls 127.0.0.1:47770         - AccessibilityService
     file (2 s)             every minute                    in target apps only
   - serves it on         - CSS hide, redirect, block     - covers Reels/Shorts tab
     127.0.0.1:47770        on load and SPA navigation      with an overlay
   - Safari guard         - bundled copy until the        - leaves the feed (Back)
   - quits whole-feed       first fetch                   - rules: bundled, adb push,
     apps                                                    or HTTPS URL
```

## Why each piece exists

- **Rules file + schema**: one place to change, validated before every save, so a bad agent edit is rejected instead of breaking devices. Behavior is specified in docs/rules.md and mirrored by each platform's tests.
- **MCP server**: lets an agent list, read, write and sync rules with schema errors fed back to it. The `grod-block` skill holds the workflow for "this distracts me lately".
- **Extension**: the only thing that can hide part of a web page. It owns Chromium browsers and Firefox.
- **macOS app**: a local hub, not a second browser enforcer. It hands fresh rules to the extension without a reload, guards Safari (no unsigned Safari extension survives a restart without a paid developer account), and quits native apps that are all feed.
- **Android app**: the phone is where the scrolling happens. An AccessibilityService sees which screen an app shows by view id, without reading content, and acts only inside the listed packages. VPN or DNS blocking cannot tell Reels from posts: they come from the same hosts.

## Update paths

| Platform | How a rules change arrives | Delay |
|---|---|---|
| macOS app | polls the file | ~2 s |
| Extension | polls the macOS app; otherwise `grod sync` + reload | ≤ 1 min |
| Android, cable | `grod sync` pushes over adb; the app checks the file's mtime | ~5 s |
| Android, away | optional HTTPS URL (e.g. raw GitHub file), fetched on use every 6 h or on Refresh | ≤ 6 h |

Every copy carries `revision` (hash of the rules file), and every platform shows the revision it runs. A copy that fails validation is ignored; the last valid one stays active.

## Limits

- Android view ids change with app updates. When a rule stops firing, dump the screen with `uiautomator` and update the ids (see the `grod-block` skill).
- Sideloaded Android apps need "Allow restricted settings" before accessibility can be turned on; `android/install.sh` does it over adb.
- Web selectors drift too. research/web-and-macos.md lists the maintained lists to re-check.
