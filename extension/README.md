# grod browser extension

One extension for Chrome, Chromium-based browsers (Arc, Brave, Edge) and Firefox. It applies the Web part of `rules/grod.rules.json` (see `docs/rules.md`): hides feed elements, redirects (Shorts → normal player), and blocks feed routes with a calm page that offers "Go back".

## Load

- **Chrome / Arc / Brave**: `chrome://extensions` → turn on Developer mode → Load unpacked → pick this `extension/` folder.
- **Firefox (121+)**: run `npm run firefox`, then `about:debugging#/runtime/this-firefox` → Load Temporary Add-on → pick `extension/dist/firefox/manifest.json`. Firefox needs its own manifest because it runs the background as a script page, while Chrome only accepts a service worker. Temporary add-ons are removed when Firefox quits. If pages are not affected, open the add-on's Permissions tab in `about:addons` and allow access to all websites.

## Rules updates

- With the grod macOS app running, the extension reads `http://127.0.0.1:47770/rules.json` at startup and every minute. Changes apply to open tabs without a reload.
- Without it, run `grod sync` (writes `extension/rules.json`), then reload the extension.

## Blocking a YouTube channel

Open a video's ⋮ menu → "grod: Block channel", or right-click a video or channel link → "grod: Block channel". The channel's tiles disappear at once and its channel and watch pages show the blocked page. The extension sends it to the macOS app, which adds it to the rules file so the phone gets it on the next sync; until then it stays in the extension as pending and is retried every minute. Removing a channel: `grod channel remove <@handle>`.

The popup shows the active revision, where it came from (`localhost` or `bundled`), the last fetch, each rule's fire count, which rules apply to the current tab, and the blocked channels.

## Test

```sh
npm test       # rules logic against the real rules file
npm run check  # manifest.json and rules.json parse
npm i && CHROME="<Chrome for Testing binary>" npm run e2e   # real browser: YouTube, Instagram, TikTok
```

`npm run e2e` needs Chrome for Testing (`npx @puppeteer/browsers install chrome@stable`), because branded Chrome ignores `--load-extension`. Run it after changing Web rules or the content script.
