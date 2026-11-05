# Web and macOS blocking notes

Sources checked 2026-11-06. Use these when a site changes its markup and a rule stops working.

## Selector sources worth re-reading

| Site | Source | Notes |
|---|---|---|
| YouTube | [i5heu/ublock-hide-yt-shorts list.txt](https://raw.githubusercontent.com/i5heu/ublock-hide-yt-shorts/master/list.txt) | Maintained Shorts list. Uses uBlock-only `:has-text` / `:matches-path`; grod translates to plain CSS. |
| YouTube, X | [jordwest/news-feed-eradicator sitelist](https://github.com/jordwest/news-feed-eradicator/tree/main/src/sitelist) | Declarative `site → region{selectors}` model, same shape as grod rules. |
| Instagram | [shbernal/instagram-feed-blocker selectors.ts / routes.ts](https://github.com/shbernal/instagram-feed-blocker/tree/main/src/content) | Class names are regenerated each build: use landmarks, `href`, `data-pagelet`, never classes. |
| Facebook | [mxmxmarexmxm/AntiReels](https://github.com/mxmxmarexmxm/AntiReels) | Stale (2023). uBlock dropped Facebook filters in Aug 2026: expect to maintain these by hand. |
| X | [nikspyratos gist](https://gist.github.com/nikspyratos/a340af08e1a72acf76ce981f0900554c) | "For you" tab = first `[role="tablist"] [role="tab"]`. |

## Plain-CSS selectors in use

YouTube desktop:
- `ytd-rich-section-renderer:has(a[href^="/shorts/"])`, `ytd-rich-shelf-renderer[is-shorts]`, `ytd-reel-shelf-renderer`
- `ytd-guide-entry-renderer:has(a[title="Shorts"])`, `ytd-mini-guide-entry-renderer:has(a[title="Shorts"])`
- `ytd-video-renderer:has(a[href^="/shorts/"])`, `grid-shelf-view-model:has(a[href^="/shorts/"])`
- `ytd-notification-renderer:has(> a[href^="/shorts/"])`

YouTube mobile web:
- `ytm-pivot-bar-item-renderer:has(.pivot-shorts)`, `ytm-reel-shelf-renderer`, `grid-shelf-view-model:has(ytm-shorts-lockup-view-model)`, `ytm-video-with-context-renderer:has(.big-shorts-singleton)`

Instagram web:
- Reels tab route: `^/reels(/[^/]+)?/?$`. `/reel/<id>/` (singular) is a single shared reel and stays open, the same as a post.
- Nav link: `a[href="/reels/"]`.
- Instagram `replaceState`s a stale `/reels/<id>/` URL when leaving Reels, so the route alone is not reliable: the player selector `main[role="main"]:has([data-virtualized] video)` is the backup.

X: "For you" tab `[role="tablist"] [role="tab"]:first-child` on `/home`.

TikTok: whole site is short-form, block the host.

## Extension mechanics

- Static stylesheet injected at `document_start`. CSS `:has()` re-matches new DOM on its own, so there is no per-mutation JS cost.
- SPA navigation: the content script hooks `navigation` `currententrychange`, `popstate` and YouTube's `yt-navigate-finish`. `declarativeNetRequest` only sees real network loads, not `pushState`.
- One manifest for both browsers: `background.scripts` (Firefox) plus `background.service_worker` (Chrome), and `browser_specific_settings.gecko`.
- Hidden `<video>` keeps playing. Pause videos inside hidden regions in JS.

## macOS options, lightest first (no paid Apple account)

1. Quit native apps by bundle id: `NSWorkspace.didActivateApplicationNotification` + `NSRunningApplication.terminate()`. No entitlements.
2. Read and set the front tab URL with AppleScript: Safari `URL of front document`, Chromium browsers `URL of active tab of front window`. Triggers the Automation consent prompt once per browser. grod uses this only for Safari, because the extension owns Chromium/Firefox and two enforcers would fight.
3. `/etc/hosts`: hostname only, needs root. Cannot see `/shorts`.
4. Network Extension content filter: sees only IP/SNI over HTTPS, and a free account cannot use it. Not viable.
5. Safari Web Extension via `xcrun safari-web-extension-converter`: unsigned extensions must be re-allowed every time Safari quits without a paid account.

## MCP SDK

`@modelcontextprotocol/server` 2.x (Oct 2026) with `zod` 4. `McpServer.registerTool(name, {description, inputSchema: z.object(...)}, handler)`; stdio transport from `@modelcontextprotocol/server/stdio`.
