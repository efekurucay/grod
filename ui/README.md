# grod UI

A small set of tokens and plain HTML/CSS components for grod surfaces that should never pull attention. No framework, no build beyond one dependency-free script. Background and sources: `RESEARCH.md`.

## Rules

1. Color means state, never decoration. At most one accent (sage, "on") element per screen; caution is clay, never red.
2. No badges, counters, red dots, pulsing, toasts, autoplay or shadows. Counts appear only where the user asked for them, in muted text.
3. Motion: 0, 120 or 180 ms, ease-out, color and opacity only, no loops. Every duration is 0 under `prefers-reduced-motion: reduce`.
4. Never rely on color alone: pair it with text, position or an icon.
5. Choices get equal visual weight; no false hierarchy.
6. System font, weights 400 and 600, 4px spacing grid, radius 4/6/10 (no pills).

## Files

- `tokens.json`: DTCG 2025.10 tokens. OKLCH colors (light and dark), type, space, radius, motion.
- `components.css`: the components, hand-written against the `--g-*` variables.
- `build.mjs`: writes `grod.css` (variables + components) here, to `extension/ui/` and `site/`, plus Android colors (`grod_colors.xml`, light and night), macOS colors (`GrodColors.swift`) and the showcase at `site/ui/` (grod.efekurucay.com/ui).
- `index.html`: every component in light and dark, for review and the future landing page.

```sh
node ui/build.mjs
```

Edit `tokens.json` or `components.css`, never `grod.css` or the extension copy. Light is the default, dark follows `prefers-color-scheme`, and `data-g-theme="light|dark"` forces a mode on any subtree.

## Components

| Class | Use |
|---|---|
| `g-app`, `g-app--compact` | Base on `body`: background, text, 16px (13px compact, for the popup) |
| `g-brand` | Mark (inline SVG, `currentColor`) + wordmark |
| `g-button`, `--quiet`, `--ghost` | Button |
| `g-switch` | `<input type="checkbox" role="switch">` |
| `g-list`, `g-row`, `__main`, `__title`, `__meta`, `__end`, `g-row--off` | List row |
| `g-kv` | Key–value list on a `<dl>` |
| `g-field`, `__label`, `__hint`, `g-input` | Field |
| `g-section`, `__title`, `__body`, `__body--padded` | Section |
| `g-notice`, `__title`, `--caution` | Notice |
| `g-interstitial`, `__body`, `__label`, `__title`, `__why` | Blocked-page layout |
