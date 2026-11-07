# grod — get rid of distracting

Slows down endless scrolling: after a minute of scrolling in a listed app or site, a pause screen makes you wait, longer each time. Android, browsers and macOS; one rules file drives every platform.

## Map

- `rules/grod.rules.json`: the only place distractions are defined. Schema: `rules/grod.rules.schema.json`. Behavior every platform must follow: `docs/rules.md`.
- `tools/`: `grod` CLI and MCP server (Node 22, TypeScript run directly, no build step).
- `extension/`: MV3 extension for Chrome and Firefox. Plain JS.
- `android/`: Kotlin app with an AccessibilityService that only receives scroll events, zero runtime dependencies.
- `macos/`: Swift menubar app: serves rules to the extension on 127.0.0.1:47770, Safari guard, quits whole-feed apps.
- `research/`: evidence behind the design. `docs/architecture.md`: how the parts connect.

## Changing what is blocked

Use the `grod-block` skill. In short: a rule names an app's Android packages and web hosts; write it through the grod MCP tools or `node tools/bin/grod.js`, then `sync`. Never edit the platform copies (`extension/rules.json`, `android/app/src/main/assets/rules.json`): `sync` overwrites them.

## Changing behavior

A change to how rules are applied must land in `docs/rules.md` and in every platform that implements it (extension `lib/rules-core.js`, Android, macOS `GrodCore`), with their tests.

## Checks

```
cd tools && npm test && npx tsc --noEmit
cd extension && npm test
cd macos && swift test
cd android && ./gradlew testDebugUnitTest assembleDebug
```
