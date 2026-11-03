"""Generate the splash bitmap and the small vector marks.

Ported from prototype/ui-revamp scripts/proto-ui/gen_icons.py. Requires Pillow.
Usage: python3 scripts/brand/gen_marks.py assets/aries/png android/app/src/main/res
Launcher layers are generated separately by gen_adaptive.py from the unclipped SVG."""
import os
import sys
from PIL import Image
from pathlib import Path
from gen_small_marks import generate

png, res = sys.argv[1], sys.argv[2]
DARK = (43, 39, 36)

def save(im, sub, name):
    d = os.path.join(res, sub)
    os.makedirs(d, exist_ok=True)
    im.save(os.path.join(d, name), optimize=True)

# Splash artwork from the prototype: the gold ram with the background removed.
icon = Image.open(os.path.join(png, "aries-icon-1024.png")).convert("RGBA")
px = icon.load()
for y in range(icon.size[1]):
    for x in range(icon.size[0]):
        c = px[x, y]
        if c[3] == 0:
            continue
        # Distance from the dark tile: fade anti-aliased edges.
        d = max(abs(c[0] - DARK[0]), abs(c[1] - DARK[1]), abs(c[2] - DARK[2]))
        if d < 18:
            px[x, y] = (0, 0, 0, 0)
        elif d < 70:
            a = int(255 * (d - 18) / 52)
            px[x, y] = (c[0], c[1], c[2], min(a, c[3]))
ram = icon.crop(icon.getchannel("A").point(lambda a: 255 if a > 120 else 0).getbbox())

def adaptive(content, scale):
    """content centred on a 432px (108dp@xxxhdpi) canvas, fitted to scale*432."""
    side = 432
    c = content.copy()
    c.thumbnail((int(side * scale), int(side * scale)), Image.LANCZOS)
    canvas = Image.new("RGBA", (side, side), (0, 0, 0, 0))
    canvas.paste(c, ((side - c.size[0]) // 2, (side - c.size[1]) // 2), c)
    return canvas

# Splash: the prototype's 432px nodpi canvas, with the ram fitted to its inner 50%.
splash = adaptive(ram, 0.5)
save(splash, "drawable-nodpi", "splash_mark.png")

# Small surfaces must stay vectors rather than downscaled PNGs.
generate(Path(__file__).resolve().parents[2] / "assets/aries/aries-mark-small.svg",
         Path(res), Path(__file__).resolve().parents[2] / "android/wear/src/main/res")
print("ok")
