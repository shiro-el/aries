"""Adaptive launcher icon layers from assets/aries/aries-icon.svg, without the baked rounded tile.

The ram artwork ends in two straight cuts (right edge x=1049, y 367..1028; bottom edge y=1028,
x 579..1049 with the chest edge sloping down at 0.19 from x 572 at y 990, in tile units). They are extended to the canvas edge so the launcher mask, never
the artwork, shapes the icon. The body gradient is repainted through one mask in user space,
with a gradientTransform equal to the original objectBoundingBox one, so the extension has no seam.
The 1024 tile is fitted into the 66dp safe zone (offset 21dp) of the 108dp canvas.

Usage: /usr/bin/python3 scripts/brand/gen_adaptive.py
Writes SVG source layers and mdpi through xxxhdpi Android PNGs. Rendering requires
PyGObject and the librsvg GdkPixbuf loader (Debian/Ubuntu: python3-gi
gir1.2-gdkpixbuf-2.0 librsvg2-common).
Use --svg-only to generate just the layers with the Python standard library.
Derived from the reviewed ui-console icon/gen_adaptive.py. Background is #2B2724;
no rounded tile, clipping path or legacy launcher bitmap is generated.
"""
import re
import pathlib

import argparse

parser = argparse.ArgumentParser(description=__doc__)
repo = pathlib.Path(__file__).resolve().parents[2]
parser.add_argument("source", nargs="?", type=pathlib.Path, default=repo / "assets/aries/aries-icon.svg")
parser.add_argument("--svg-dir", type=pathlib.Path, default=pathlib.Path(__file__).parent / "layers")
parser.add_argument("--res-dir", type=pathlib.Path, default=repo / "android/app/src/main/res")
parser.add_argument("--svg-only", action="store_true")
args = parser.parse_args()
src = args.source.read_text()
here = args.svg_dir
here.mkdir(parents=True, exist_ok=True)

ram = re.search(r'<g transform="translate\(56 110\) scale\(.95\)">.*?</g></g>', src).group(0)
ram_white = ram.replace('fill="url(#gg)"', 'fill="#FFFFFF"')
holes = re.search(r'<mask id="s".*?</mask>', src).group(0)
holes = holes.replace('x="0" y="0" width="1200" height="1200"', 'x="-600" y="-600" width="2400" height="2400"')
holes = holes.replace('<rect width="1200" height="1200" fill="white"/>', '<rect x="-600" y="-600" width="2400" height="2400" fill="white"/>')

BX0, BY0, BX1, BY1 = 198, 249, 1049, 1028      # the ram's box in tile units (measured)
W, H = BX1 - BX0, BY1 - BY0
FAR = 1600                                        # past the 108dp canvas edge in tile units
filler = (f'<g mask="url(#s)"><rect x="{BX1 - 3}" y="367" width="{FAR}" height="{FAR}" fill="#FFFFFF"/>'
          f'<polygon points="578,{BY1 - 6} {BX1 + FAR},{BY1 - 6} {BX1 + FAR},{BY1 + FAR} {578 + int(FAR * 0.19)},{BY1 + FAR}" fill="#FFFFFF"/></g>')
body = f'<mask id="body" maskUnits="userSpaceOnUse" x="-600" y="-600" width="2400" height="2400">{ram_white}{filler}</mask>'
grad = (f'<linearGradient id="g2" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="0.35" y2="1" '
        f'gradientTransform="matrix({W} 0 0 {-H} {BX0} {BY1})">'
        '<stop offset="0" stop-color="#F8D98A"/><stop offset="1" stop-color="#D9952E"/></linearGradient>')

S, OFF = 66 / 1024, 21


def layer(fill):
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108">'
            f'<defs>{holes}{body}{grad}</defs>'
            f'<g transform="translate({OFF} {OFF}) scale({S})">'
            f'<rect x="-600" y="-600" width="2400" height="2400" fill="{fill}" mask="url(#body)"/></g></svg>')


(here / 'fg.svg').write_text(layer('url(#g2)'))
(here / 'mono.svg').write_text(layer('#FFFFFF'))

if not args.svg_only:
    import gi
    gi.require_version("GdkPixbuf", "2.0")
    from gi.repository import GdkPixbuf

    for density, size in [("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)]:
        destination = args.res_dir / ("drawable-" + density)
        destination.mkdir(parents=True, exist_ok=True)
        for source, name in [("fg.svg", "ic_launcher_foreground"), ("mono.svg", "ic_launcher_monochrome")]:
            image = GdkPixbuf.Pixbuf.new_from_file_at_size(str(here / source), size, size)
            image.savev(str(destination / (name + ".png")), "png", [], [])
