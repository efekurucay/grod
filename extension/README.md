# grod browser extension

One extension for Chrome, Chromium-based browsers (Arc, Brave, Edge) and Firefox. It applies `rules/grod.rules.json` to the browser (see `docs/rules.md`, "Scroll pause" and "Web links"): scrolling on the sites of enabled rules is paused every so often, longer each time; feed-only URLs are redirected (Shorts → normal player) or blocked with a calm page that offers "Go back".

## Load

- **Chrome / Arc / Brave**: `chrome://extensions` → turn on Developer mode → Load unpacked → pick this `extension/` folder.
- **Firefox (121+)**: run `npm run firefox`, then `about:debugging#/runtime/this-firefox` → Load Temporary Add-on → pick `extension/dist/firefox/manifest.json`. Firefox needs its own manifest because it runs the background as a script page, while Chrome only accepts a service worker. Temporary add-ons are removed when Firefox quits. If pages are not affected, open the add-on's Permissions tab in `about:addons` and allow access to all websites.

## Rules updates

- With the grod macOS app running, the extension reads `http://127.0.0.1:47770/rules.json` at startup and every minute. Changes apply to open tabs without a reload.
- Without it, run `grod sync` (writes `extension/rules.json`), then reload the extension.

## Scroll pause

The content script listens for `scroll` on the document (capturing, so element scrollers count as well as the window) on hosts of enabled rules, while the tab is visible, and tells the background at most every 250 ms. The background keeps one session for the whole browser in `storage.session`, so it is shared by all tabs, survives service worker restarts and ends when the browser quits. Timings come from the `scroll` object of the rules file.

When a pause is due, the tab that scrolled shows the pause screen (`pause.html`) in an iframe inside a closed shadow root, in the top layer: the site's CSS cannot restyle it and clicks cannot reach the site. It shows the rule's title and `why`, the time scrolled this session and a countdown. Videos on the page are paused while it shows. "Leave" closes the tab; "Continue" appears when the countdown ends. Any other tab of a rule's sites that scrolls before the countdown ends shows the same pause, so a reload or a new tab is no way around it.

The popup shows the active revision, where it came from (`localhost` or `bundled`), the last fetch, which rules apply to the current tab, and how many pauses each rule has caused.

## Test

```sh
npm test       # rules logic against the real rules file
npm run check  # manifest.json and rules.json parse
npm i && CHROME="<Chrome for Testing binary>" npm run e2e   # real browser: YouTube, Instagram, TikTok, scroll pause
```

`npm run e2e` needs Chrome for Testing (`npx @puppeteer/browsers install chrome@stable`), because branded Chrome ignores `--load-extension`. It never reaches the grod macOS app (plain http goes to a dead proxy), and the scroll pause runs on a test page served by the test itself with a short `freeSeconds`. Run it after changing Web rules, the content script or the pause screen.
