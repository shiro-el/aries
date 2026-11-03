"""Generate splash, notification and in-app marks from assets/aries/png.

Ported from prototype/ui-revamp scripts/proto-ui/gen_icons.py. Requires Pillow.
Usage: python3 scripts/brand/gen_marks.py assets/aries/png android/app/src/main/res
Launcher layers are generated separately by gen_adaptive.py from the unclipped SVG."""
import os
import sys
from PIL import Image

png, res = sys.argv[1], sys.argv[2]
DARK = (43, 39, 36)

def near(c, ref, tol=40):
    return all(abs(a - b) <= tol for a, b in zip(c[:3], ref))

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

# Notification small icon: white silhouette of the mark.
mark = Image.open(os.path.join(png, "aries-mark-512.png")).convert("RGBA")
mark = mark.crop(mark.getbbox())
for dpi, size in [("mdpi", 24), ("hdpi", 36), ("xhdpi", 48), ("xxhdpi", 72), ("xxxhdpi", 96)]:
    m = mark.copy()
    m.thumbnail((int(size * 0.9), int(size * 0.9)), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    sil = Image.new("RGBA", m.size, (255, 255, 255, 255))
    sil.putalpha(m.getchannel("A"))
    canvas.paste(sil, ((size - m.size[0]) // 2, (size - m.size[1]) // 2), sil)
    save(canvas, "drawable-" + dpi, "ic_stat_aries.png")

# Mark (small, for in-app headers) as-is and light-ink copy.
ms = Image.open(os.path.join(png, "aries-mark-small-128.png")).convert("RGBA")
ms = ms.crop(ms.getbbox())
save(ms, "drawable-nodpi", "mark_ink.png")
light_mark = ms.copy()
light_pixels = light_mark.load()
for y in range(light_mark.size[1]):
    for x in range(light_mark.size[0]):
        c = light_pixels[x, y]
        if c[3] and near(c, DARK, 30):
            light_pixels[x, y] = (243, 237, 230, c[3])
save(light_mark, "drawable-nodpi", "mark_light.png")
print("ok")
