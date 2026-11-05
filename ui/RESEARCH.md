# grod UI: research and proposal

A small UI library for grod whose components and colors never pull attention. Researched 2026-11-06. This is a proposal; nothing is built yet.

## Sources

**shadcn/ui and registries**
- Registry docs: https://ui.shadcn.com/docs/registry/getting-started, https://ui.shadcn.com/docs/registry/registry-json, https://ui.shadcn.com/docs/registry/registry-item-json
- Theming (CSS variables, OKLCH, base colors): https://ui.shadcn.com/docs/theming
- Changelog: https://ui.shadcn.com/docs/changelog, including GitHub registries (Jun 2026) https://ui.shadcn.com/docs/changelog/2026-06-github-registries, private GitHub registries (Aug 2026) https://ui.shadcn.com/docs/changelog/2026-08-private-github-registries, `shadcn apply` (Apr 2026) https://ui.shadcn.com/docs/changelog/2026-04-shadcn-apply
- How other libraries ship: Magic UI https://magicui.design/docs/installation, Kibo UI https://www.kibo-ui.com/docs/setup, Aceternity https://ui.aceternity.com/docs/cli, coss ui (which absorbed Origin UI) https://coss.com/ui/r/ui.json
- Tailwind without a build step: https://tailwindcss.com/docs/installation/play-cdn
- Chrome extensions ban remote code: https://developer.chrome.com/docs/extensions/develop/migrate/improve-security

**Calm design and attention**
- Weiser & Brown (1995), "Designing Calm Technology", Xerox PARC: https://calmtech.com/papers/designing-calm-technology
- Amber Case, Principles of Calm Technology: https://calmtech.com/
- Holte & Ferraro (2020), "True colors: Grayscale setting reduces screen time in college students", *The Social Science Journal* 60(2): https://doi.org/10.1080/03623319.2020.1737461
- Dekker & Baumgartner (2023), "Is life brighter when your phone is not?", *Mobile Media & Communication*: https://doi.org/10.1177/20501579231212062
- Bartoli & Benedetto (2022), "Driven by notifications", *PLOS ONE* 17(6): https://doi.org/10.1371/journal.pone.0270888
- Kushlev, Proulx & Dunn (2016), "Silence Your Phones", CHI '16: https://www.interruptions.net/literature/Kushlev-CHI16.pdf
- Gray, Kou, Battles, Hoggatt & Toombs (2018), "The Dark (Patterns) Side of UX Design", CHI '18: https://doi.org/10.1145/3173574.3174108 (PDF: https://par.nsf.gov/servlets/purl/10057203)
- Andersen & Maier (2017), "The attentional capture of colour in visual interface design", ICED17: https://strathprints.strath.ac.uk/78532
- High-performance HMI / ISA-101 "going gray" (secondary source): https://control.com/technical-articles/going-gray/
- WCAG 2.2 SC 2.3.3 Animation from Interactions: https://www.w3.org/WAI/WCAG22/Understanding/animation-from-interactions.html; SC 1.4.3 https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html; SC 1.4.11 https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html
- Material Components dark theme: https://github.com/material-components/material-components-android/blob/master/docs/theming/Dark.md

**Design tokens**
- DTCG Format, Color and Resolver modules 2025.10: https://www.w3.org/community/reports/design-tokens/CG-FINAL-format-20251028/, https://www.w3.org/community/reports/design-tokens/CG-FINAL-color-20251028/, https://www.w3.org/community/reports/design-tokens/CG-FINAL-resolver-20251028/
- Style Dictionary: https://styledictionary.com/info/dtcg/, https://styledictionary.com/reference/hooks/formats/predefined/, https://styledictionary.com/versions/v5/migration/
- Terrazzo: https://terrazzo.app/docs/, https://github.com/terrazzoapp/terrazzo/blob/main/packages/cli/CHANGELOG.md
- Versions on npm today: `shadcn` 4.23.0, `tailwindcss` 4.3.4, `style-dictionary` 5.8.0, `@terrazzo/cli` 2.10.0.

## Findings

### 1. Building on shadcn/ui

- **A registry is a `registry.json` plus source files.** Items have a `type` (`registry:ui`, `registry:theme`, `registry:style`, `registry:base`, `registry:file`, and others), `files`, `dependencies`, `registryDependencies`, and optional `cssVars` (`theme` / `light` / `dark`) and `css`. `shadcn build` writes `public/r/<name>.json`; users run `shadcn add <url>` or set up a namespace (`@grod/button`) in `components.json`.
- **Since June 2026, a GitHub repo is a registry.** `shadcn add owner/repo/item` reads the root `registry.json` directly: no build, no hosting. Private repos work since August 2026 through `gh` auth.
- **Theming is CSS variables in background/foreground pairs** (`--primary` / `--primary-foreground`), written in OKLCH, mapped into Tailwind v4 with `@theme inline`. Base colors are Neutral, Stone, Zinc, Mauve, Olive, Mist and Taupe.
- **The popular libraries are all registries, not npm packages.** Magic UI (`@magicui/globe`), Kibo UI (`@kibo-ui`, CSS-variables mode required), Aceternity (`.../registry/[component].json`), coss ui (`@coss/...`). tweakcn ships themes only, as registry JSON URLs (seen only in third-party docs).
- **Registry vs npm.** A registry copies source into your project: you own it, it is easy to change, and there is no version drift, but fixes do not propagate on their own. An npm package does propagate updates, but it needs a build, versioning and publishing, which is too much for one person.
- **grod's limits.** shadcn components are React with Tailwind, and both need a build. The extension has no bundler, and Chrome extensions may not load remote scripts, so the Tailwind Play CDN is out (it is also dev-only). Native apps can share only tokens. The part of shadcn grod can use everywhere is its *approach*: owned source, semantic CSS-variable pairs, OKLCH.

### 2. Calm vs attention-capturing design

- **Weiser & Brown (1995).** Calm technology "engages both the center and the periphery of our attention"; it "encalm[s] as [it] empower[s] our periphery". **Case's principles** add: require "the smallest possible amount of attention", "communicate, but [don't] need to speak", and use "the minimum needed".
- **Color drives use.** Grayscale for 8–10 days cut screen time by 37.9 min/day (N=161; Holte & Ferraro 2020). A one-week grayscale trial cut it by 20 min/day and raised perceived control, but unlocks did not change (N=84; Dekker & Baumgartner 2023). Primary colors capture attention more than secondary ones (Andersen & Maier 2017, N=11, small).
- **Badges work as calls to action.** In a test with 15 icons and one badge (N>1,000), the badged app drew a large share of first clicks; the authors cite salience and urgency (Bartoli & Benedetto 2022). With alerts on for a week, people reported more inattention and hyperactivity (N=221; Kushlev 2016).
- **Dark patterns.** Gray et al. (2018) coded 118 artifacts into five strategies: nagging, obstruction, sneaking, interface interference and forced action. "False hierarchy" (one option given visual precedence) and "toying with emotion" through color fall under interface interference. A calm UI must not do these either; for example, "turn rule off" must not be styled as the scary choice.
- **Industrial precedent.** High-performance HMI (ISA-101) keeps screens gray and reserves color and motion for abnormal states, so color carries meaning only when something needs action. This comes from a secondary source.
- **Contrast and motion.** WCAG AA asks for 4.5:1 for text and 3:1 for UI boundaries and focus; it sets no upper limit, so pure #000/#fff (21:1) is not required. Material's dark baseline uses dark gray, not black, citing eye strain. Motion triggered by interaction must be possible to switch off (SC 2.3.3, AAA; `prefers-reduced-motion` is a sufficient technique).

### 3. Shared tokens

- **DTCG 2025.10 is stable** (Community Group report, not a W3C Standard; Oct 28, 2025). Files end in `.tokens.json`. Colors are objects such as `{colorSpace: "oklch", components: [L, C, h], hex?}`. A separate **Resolver** module handles light and dark.
- **Style Dictionary 5.8** (Node 22+) has `css/variables`, `android/colors`, `compose/object`, `ios-swift/class.swift` and a SwiftUI `Color` transform. **Verified locally:** an OKLCH DTCG token built to the expected hex (`#54715a`) in CSS, Android XML, Compose and Swift. Its Swift output uses `UIColor`/SwiftUI; grod's macOS app is AppKit, so it needs a small custom `NSColor` transform. There is no built-in resolver support, so build light and dark as two passes.
- **Terrazzo 2.x** fully supports 2025.10, including resolvers, with CSS, Swift and Tailwind plugins, but none for Android.

## Recommendation

### Structure (no npm package; source you own, shadcn-style)

```
ui/
  tokens/grod.tokens.json      DTCG 2025.10, OKLCH + hex fallback
  tokens/light.tokens.json, dark.tokens.json
  build.mjs                    Style Dictionary 5 → dist/
  dist/grod.css                :root + prefers-color-scheme dark vars
  dist/android/values{,-night}/grod_colors.xml
  dist/macos/GrodColors.swift  NSColor via custom transform
  components/*.css + *.html    framework-free, class-based (.g-button)
  registry.json                shadcn schema: registry:theme (cssVars) + items, for the landing page later
```
Copy `grod.css` and the component CSS into `extension/` with a sync step, the same way rules are synced. Once the repo is on GitHub, the landing page can run `shadcn add <owner>/grod/grod-theme`.

### Palette (OKLCH, warm low-chroma neutrals; chroma ≤ 0.07; no red)

Contrast was computed with the WCAG 2.x formula on the rounded sRGB hex values.

| Token | Light | Dark |
|---|---|---|
| background | `oklch(0.970 0.005 85)` #f7f5f1 | `oklch(0.215 0.004 75)` #1b1917 |
| surface | `oklch(0.990 0.003 85)` #fdfcf9 | `oklch(0.250 0.005 75)` #23211f |
| muted | `oklch(0.935 0.006 85)` #ebe9e5 | `oklch(0.280 0.005 75)` #2a2926 |
| foreground | `oklch(0.280 0.008 75)` #2b2825 | `oklch(0.910 0.008 85)` #e4e1db |
| muted-foreground | `oklch(0.500 0.010 75)` #67635d | `oklch(0.700 0.010 80)` #a29e98 |
| border (decorative) | `oklch(0.895 0.007 85)` #dedcd7 | `oklch(0.330 0.006 75)` #373532 |
| input (boundary) | `oklch(0.640 0.010 80)` #8f8c85 | `oklch(0.520 0.008 80)` #6b6864 |
| primary / -fg | `oklch(0.320 0.008 75)` on bg | `oklch(0.880 0.008 85)` on bg |
| accent (sage, "on") | `oklch(0.520 0.050 150)` #54715a | `oklch(0.740 0.060 150)` #90b697 |
| caution (clay, not red) | `oklch(0.540 0.070 60)` #8d6544 | `oklch(0.760 0.070 65)` #d1a882 |
| ring | `oklch(0.580 0.040 150)` #6a816e | `oklch(0.640 0.045 150)` #79947e |

| Pair | Light | Dark | Needs |
|---|---|---|---|
| foreground / background | 13.46 | 13.43 | 4.5 (deliberately below 21) |
| foreground / muted | 12.09 | 11.15 | 4.5 |
| muted-fg / background | 5.48 | 6.58 | 4.5 |
| muted-fg / muted | 4.92 | 5.46 | 4.5 |
| primary-fg / primary | 11.71 | 12.21 | 4.5 |
| accent-fg / accent | 5.19 | 7.78 | 4.5 |
| accent / background | 4.96 | 7.78 | 4.5 |
| caution / background | 4.73 | 8.04 | 4.5 |
| input / background | 3.08 | 3.16 | 3 |
| ring / background | 3.87 | 5.30 | 3 |
| border / background | 1.26 | 1.43 | decorative only |

These values formalize the colors already in `extension/popup.html` and `blocked.html` (#f6f5f2 / #22211f).

### Type, space, radius
- Font: `system-ui` (no webfont to load). Sizes: 12, 13 (popup body), 14, 16 (page body), 20, 24. Weights: 400 and 600 only. Line height 1.5 (1.25 for headings).
- Spacing on a 4px grid: 4, 8, 12, 16, 24, 32, 48.
- Radius: 4 / 6 / 10. No pills, no shadows; use a 1px border or a step in surface color.

### Rules
1. Color means state, never decoration. At most one accent element per screen.
2. No badges, counts, red dots, pulsing, toasts or autoplay. Counts appear only where the user asked for them, in `muted-foreground`.
3. Motion: 0, 120 or 180 ms, ease-out, opacity and color only, no looping. Under `prefers-reduced-motion: reduce`, every duration is 0.
4. Never rely on color alone: pair it with text or an icon.
5. Choices get equal visual weight; no false hierarchy.

### First 8 components
1. **Button** (primary, quiet, ghost): the blocked page's "Go back", the Android Refresh button.
2. **Switch**: turning a rule on or off.
3. **List row**: title, secondary text and a trailing control or count (popup rules, blocked channels).
4. **Key–value list**: the popup's Revision / Source / Last fetch block.
5. **Field**: label, input and hint (rules URL on Android).
6. **Section**: muted uppercase heading plus a surface.
7. **Notice**: an inline, static message in place of toasts and alerts.
8. **Interstitial**: the blocked-page layout (label, title, why, one action).

## Open questions

1. Is the warm neutral plus sage accent the right mood, or do you want cooler (Mist/Zinc) neutrals?
2. Should the popup's green "on" dot become a Switch, or stay as read-only status?
3. Will the repo be on GitHub, public or private? That decides whether `shadcn add owner/grod/...` works.
4. Should the landing page use React/Tailwind (full shadcn) or stay plain HTML like the extension?
5. Do Android and macOS really need tokens now, or only once their UI grows? The Android app uses plain Views with zero dependencies.
6. Should grod offer an optional grayscale mode for its own surfaces?
