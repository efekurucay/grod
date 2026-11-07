# grod mark

A lowercase single-story g: a round bowl and one quiet stroke. Lowercase on purpose, so it never reads as a capital G with a bar.

- `grod-mark.svg`: the mark alone, drawn in `currentColor`. Use it where the platform tints icons (macOS menu bar template image, Android monochrome icon).
- `grod-icon.svg`: the mark on its tile, for app and extension icons.
- `grod-wordmark.svg`: the name written in the mark's strokes, drawn in `currentColor`. Use it where the name is shown as a logo rather than set in text.

Proportions on a 24-unit grid: bowl radius 5 centered at (12, 9), stem at x = 17 from y = 4 down to a 5-unit curve ending at x = 9, stroke 2.4 with round caps. Tile `#2B2F36`, mark `#EDEBE6`.

The wordmark keeps the mark's rules: the g is the mark itself (shifted to x = 7), and r, o, d are built only from radius-5 bowls and curves and straight stems, stroke 2.4, round caps. x-height 10 units (y 4 to 14), d's ascender rises to y = -2.5, g's descender ends at y = 20.5. viewBox `0 -4 54 26`. Cropping the viewBox to width 14 leaves the g alone, so one file serves both the name and the mark at text size.

Platform copies are generated from these files; change the SVG here first.

## Name

Always lowercase: **grod**. Products: grod for Android, grod for Chrome, grod for macOS, grod CLI.

Tagline: **Get rid of distracting. Keep what matters.**

## Wallpapers

`python3 brand/wallpapers/build.py` renders the phone and desktop wallpapers, dark and light, into `site/wallpapers/` with headless Chrome. The landing page links them.
