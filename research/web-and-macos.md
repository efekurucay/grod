# Web and macOS blocking notes

Sources checked 2026-11-06.

## Extension mechanics

- SPA navigation: the content script hooks `navigation` `currententrychange`, `popstate` and YouTube's `yt-navigate-finish`. `declarativeNetRequest` only sees real network loads, not `pushState`.
- Separate manifests: Chrome warns on `background.scripts`, Firefox has no extension service worker. `manifest.json` is Chrome's; `npm run firefox` writes `dist/firefox/` with `background.scripts` and `browser_specific_settings.gecko`.

## macOS options, lightest first (no paid Apple account)

1. Quit native apps by bundle id: `NSWorkspace.didActivateApplicationNotification` + `NSRunningApplication.terminate()`. No entitlements.
2. Read and set the front tab URL with AppleScript: Safari `URL of front document`, Chromium browsers `URL of active tab of front window`. Triggers the Automation consent prompt once per browser. grod uses this only for Safari, because the extension owns Chromium/Firefox and two enforcers would fight.
3. `/etc/hosts`: hostname only, needs root. Cannot see `/shorts`.
4. Network Extension content filter: sees only IP/SNI over HTTPS, and a free account cannot use it. Not viable.
5. Safari Web Extension via `xcrun safari-web-extension-converter`: unsigned extensions must be re-allowed every time Safari quits without a paid account.

## MCP SDK

`@modelcontextprotocol/server` 2.x (Oct 2026) with `zod` 4. `McpServer.registerTool(name, {description, inputSchema: z.object(...)}, handler)`; stdio transport from `@modelcontextprotocol/server/stdio`.
