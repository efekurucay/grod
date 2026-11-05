# Rules: format and behavior

`rules/grod.rules.json` is the only place distractions are defined. `rules/grod.rules.schema.json` is the formal schema; this page defines what each platform does with a rule. Every platform must behave the same way, so change this page when behavior changes.

## Revision

`revision` = first 12 hex chars of SHA-256 over the raw bytes of `rules/grod.rules.json`. The CLI and the macOS app compute it from the file. Copies handed to other platforms (`extension/rules.json`, Android assets, the localhost endpoint, the adb-pushed file) carry it as a top-level `"revision"` field. Each platform shows the revision it runs, so "is my phone up to date" is a string comparison.

A copy is accepted only if it parses, has `schemaVersion: 1` and a `rules` array. A bad copy is ignored and the last valid one stays active.

## Web (extension, macOS Safari guard)

For a page URL, let `host` = hostname and `target` = pathname + search.

1. A rule applies when it is `enabled`, has `web`, and `host === h || host.endsWith("." + h)` for some `h` in `web.hosts`.
2. **hide**: each selector becomes its own CSS rule `selector { display: none !important; }`, so one bad selector cannot disable the rest. `<video>` elements inside hidden elements are paused.
3. **redirect** runs first. For each `{from, to}` in order: if `new RegExp(from)` matches `target`, the new target is `target.replace(new RegExp(from), to)`, keeping only the matched part replaced (`$1`…`$9` refer to groups). If the new target differs, navigate to `origin + newTarget` with history replace, and stop.
4. **block** runs next. If any `new RegExp(pattern)` matches `target`, show the grod blocked page (rule title and `why`), which offers "Go back".
5. Checks run at page start and on every same-document navigation (pushState, replaceState, popstate, site-specific events such as YouTube's `yt-navigate-finish`).

## Android

Rules with an `android` section apply only inside their `packages`.

A **matcher** matches a node when every field present matches:
- `viewId`: node's `viewIdResourceName` equals `<package>:id/<viewId>`.
- `text` / `desc`: node text / content description equals the value, case-insensitive.
- `descContains`: content description contains the value, case-insensitive.
- `selected`: node `isSelected` equals the value.

Only nodes that are visible to the user with non-empty screen bounds count.

On each relevant accessibility event in a target package:
1. **cover**: every node matched by a `cover` matcher gets an opaque overlay at its screen bounds that swallows taps. Overlays move with the node and disappear when it does or the app leaves the front.
2. **mask**: every node matched by a `mask` matcher gets the same opaque overlay, but touches pass through it. Use it for large areas (feed thumbnails), so the page still scrolls; a tap that opens the feed is then caught by `feed`. Overlays follow scrolling.
3. **feed**: the user is in the feed if any `feed` matcher matches a node. A missing or empty `feed` means the whole app is the feed.
4. **exempt**: if any `exempt` matcher matches, the feed action is held, until a scroll event comes from inside the feed. One reel sent in a DM plays; swiping to the next one does not.
5. **action**: `back` presses Back; if the feed is still on screen 1.5 s later, it goes Home. `home` goes Home. After an action the rule does nothing for 1.5 s, so one feed entry triggers one action.

Each fired action increments a per-rule counter kept on the device.

## YouTube channels

A rule's `youtubeChannels` list hides channels everywhere on YouTube, on the web and in the Android app. Each entry has `name` (display name exactly as shown), `handle` (`@handle`) and optionally `id` (`UC…`). Channel lists from every enabled rule are merged.

Web (hosts `youtube.com` and subdomains):
- Every video, Short or channel tile that contains a link to `/@handle` or `/channel/<id>` is hidden. Handles compare case-insensitively after percent-decoding, so `/@%C3%87in…` matches `@Çin…`.
- Routes `/@handle` and `/channel/<id>` (and anything below them) show the blocked page.
- A watch page whose owner link points to a blocked channel shows the blocked page. The owner appears after the page renders, so it is checked for a few seconds after each navigation.

Android (`com.google.android.youtube`, `app.revanced.android.youtube`), YouTube in English:
- Nodes whose description contains `Go to channel <name> -` (video tiles) or whose text equals `<name>` (channel cards) get a mask.
- A watch page (`watch_player` on screen) with a node described `Subscribe to <name>.` or `Unsubscribe from <name>.` gets the `back` action.

Adding a channel:
- Desktop: the "Block channel" item grod adds to a video's ⋮ menu, or right-click a video or channel link → "grod: Block channel". The extension applies it at once and sends it to the macOS app (`POST /youtube-channels`), which writes it with `grod channel add`.
- Android: YouTube → Share → grod. The app resolves the channel (YouTube oEmbed for a video link, the channel page for a channel link), applies it at once and lists it in `channels-added.json` in its external files dir; `grod sync` merges that file into the rules.
- Agent or terminal: `grod channel add <video or channel URL | @handle>`.

## macOS

- `macos.quitApps`: bundle ids of enabled rules are quit when they launch or come to the front.
- Safari guard: while Safari is in front, the app reads the front tab URL every 1.5 s and applies the Web rules above. The blocked page is served at `http://127.0.0.1:47770/blocked?rule=<id>`. Chromium browsers and Firefox are left to the extension, so two enforcers never fight.
- `GET http://127.0.0.1:47770/rules.json` serves the current valid rules with `revision`. The extension polls it.
- `POST http://127.0.0.1:47770/youtube-channels` with JSON `{"name", "handle", "id"?}` and header `X-Grod: 1` runs `grod channel add`. The custom header forces a CORS preflight that the app never answers, so web pages cannot call it; the extension can, through its host permission.

## Writing a good rule

- Remove the feed, keep the app. Prefer `hide`/`cover` plus `feed` detection over blocking a host or quitting an app.
- Web selectors: landmarks, `href`, `data-*`, `aria-*`, custom element names. Never generated class names.
- Android: find ids with `adb shell uiautomator dump /sdcard/ui.xml && adb pull /sdcard/ui.xml`. On Instagram never match `reel_*` ids: those are Stories.
- Fill `why` with what stays usable. It is shown when the rule fires.
