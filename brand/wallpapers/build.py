# Renders the grod wallpapers (mobile/desktop x dark/light) into site/wallpapers with headless Chrome.
# Run from anywhere: python3 brand/wallpapers/build.py   (CHROME=<path> to use another Chrome)
import os, subprocess

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '../../site/wallpapers')
CHROME = os.environ.get('CHROME', '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome')
MARK = open(os.path.join(HERE, '../grod-mark.svg')).read()


def blend(fg, bg, a):
    """Opaque mix, so the mark's overlapping strokes do not show a brighter seam."""
    f = [int(fg[i:i + 2], 16) for i in (1, 3, 5)]
    b = [int(bg[i:i + 2], 16) for i in (1, 3, 5)]
    return '#' + ''.join(f'{round(a * x + (1 - a) * y):02x}' for x, y in zip(f, b))


# grod UI background and foreground (android/app/src/main/res/values*/grod_colors.xml).
THEMES = {'dark': ('#1b1917', '#e4e1db'), 'light': ('#f7f5f1', '#2b2825')}
# CSS size, device scale, where the mark sits, mark and word size.
LAYOUTS = {
    'mobile': dict(w=645, h=1398, s=2, top='68%', mark=60, font=19),
    'desktop': dict(w=1440, h=900, s=2, top='50%', mark=44, font=15),
}


def build(theme, layout):
    bg, fg = THEMES[theme]
    L = LAYOUTS[layout]
    mark = MARK.replace('currentColor', blend(fg, bg, 0.95))
    html = f'''<!doctype html><html><head><meta charset="utf-8">
<link href="https://fonts.googleapis.com/css2?family=Space+Grotesk:wght@300&display=swap" rel="stylesheet">
<style>
html,body{{margin:0;width:{L['w']}px;height:{L['h']}px;overflow:hidden;background:{bg}}}
.center{{position:absolute;left:50%;top:{L['top']};transform:translate(-50%,-50%);text-align:center}}
.center svg{{width:{L['mark']}px;height:{L['mark']}px;display:block;margin:0 auto 18px}}
.word{{font:300 {L['font']}px 'Space Grotesk';letter-spacing:.42em;padding-left:.42em;color:{blend(fg, bg, 0.85)}}}
</style></head><body><div class="center">{mark}<div class="word">grod</div></div></body></html>'''
    name = os.path.join(OUT, f'grod-{layout}-{theme}')
    open(name + '.html', 'w').write(html)
    out = f"{name}-{L['w'] * L['s']}x{L['h'] * L['s']}.png"
    subprocess.run([CHROME, '--headless=new', '--disable-gpu', '--hide-scrollbars', f"--force-device-scale-factor={L['s']}",
                    f"--window-size={L['w']},{L['h']}", '--virtual-time-budget=4000', f'--screenshot={out}', f'file://{name}.html'],
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
    os.remove(name + '.html')
    print(os.path.relpath(out))


if __name__ == '__main__':
    os.makedirs(OUT, exist_ok=True)
    for theme in THEMES:
        for layout in LAYOUTS:
            build(theme, layout)
