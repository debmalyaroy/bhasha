"""Builds the launcher icon (adaptive: background, foreground, monochrome) and an SVG preview.

Glyph outlines come from Noto Sans Devanagari Bold (SIL Open Font License).
usage: make_logo.py <font.ttf> <res dir> <preview.svg>
"""
import sys

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

font = TTFont(sys.argv[1])
res, preview = sys.argv[2], sys.argv[3]
cmap, glyphs, upm = font.getBestCmap(), font.getGlyphSet(), font["head"].unitsPerEm
hmtx = font["hmtx"]


def text_path(text, cx, cy, height, ref_top, ref_bottom):
    """Outline of `text` centred on (cx, cy); `height` is the size of the ref_bottom..ref_top band (font units)."""
    names = [cmap[ord(c)] for c in text]
    width = sum(hmtx[n][0] for n in names)
    scale = height / (ref_top - ref_bottom)
    x = cx - width * scale / 2
    y = cy + (ref_top + ref_bottom) / 2 * scale
    out = []
    for n in names:
        pen = SVGPathPen(glyphs, ntos=lambda v: ("%.2f" % v).rstrip("0").rstrip("."))
        glyphs[n].draw(TransformPen(pen, (scale, 0, 0, -scale, x, y)))
        out.append(pen.getCommands())
        x += hmtx[n][0] * scale
    return "".join(out)


def bubble(x0, y0, x1, y1, r, tail):
    """Rounded rectangle with a small tail; tail = (base x1, base x2, tip x, tip y) on the bottom edge."""
    from shapely.geometry import Polygon, box
    b1, b2, tx, ty = tail
    return box(x0 + r, y0 + r, x1 - r, y1 - r).buffer(r, 24).union(Polygon([(b1, y1 - 1), (b2, y1 - 1), (tx, ty)]))


def outline(shape):
    polys = getattr(shape, "geoms", [shape])
    return "".join("M" + "L".join("%.2f,%.2f" % c for c in p.exterior.coords[:-1]) + "Z" for p in polys)


# Devanagari: the band between the baseline and the headline; Latin: cap height.
os2 = font["OS/2"]
cap = getattr(os2, "sCapHeight", 0) or 714
head = 0.62 * upm   # approximate height of the Devanagari headline above the baseline

INK = "#2A2468"
main_shape = bubble(21, 25, 71, 65, 11, (30, 42, 27, 77))
second_shape = bubble(53, 49, 88, 82, 9, (70, 80, 84, 91))
main, second = outline(main_shape), outline(second_shape)
# One-colour version: a gap keeps the two bubbles apart.
main_cut = outline(main_shape.difference(second_shape.buffer(3)))
bha = text_path("भा", 43.5, 42.5, 18, head, 0)
# The font has no Latin letters, so the A is drawn by hand: outline plus its counter.
latin = "M62.2,74L68.4,57h4.2L78.8,74h-4.1l-1.2,-3.7h-6.2l-1.2,3.7ZM68.5,67.1h4l-2,-6.2Z"

HEADER = '<?xml version="1.0" encoding="utf-8"?>\n'
VEC = ('<vector xmlns:android="http://schemas.android.com/apk/res/android"%s\n'
       '    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n%s</vector>\n')


def path(d, color, extra=""):
    return f'    <path android:fillColor="{color}"{extra}\n        android:pathData="{d}" />\n'


background = VEC % ('\n    xmlns:aapt="http://schemas.android.com/aapt"', """    <path android:pathData="M0,0h108v108h-108z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="linear" android:startX="0" android:startY="0" android:endX="108" android:endY="108">
                <item android:offset="0" android:color="#FFFF9F43" />
                <item android:offset="0.55" android:color="#FFF2643D" />
                <item android:offset="1" android:color="#FFD63A6E" />
            </gradient>
        </aapt:attr>
    </path>
""")
# Shrunk around the centre so nothing reaches outside the launcher's circular mask.
GROUP = ('    <group android:pivotX="54" android:pivotY="54" android:scaleX="0.8" android:scaleY="0.8"'
         ' android:translateY="-3.5">' + chr(10) + "%s    </group>" + chr(10))
SVG_T = 'transform="translate(54 50.5) scale(0.8) translate(-54 -54)"'
foreground = VEC % ("", GROUP % (path(main, "#FFFFFFFF") + path(bha, INK) + path(second, INK) + path(latin, "#FFFFFFFF", ' android:fillType="evenOdd"')))
# Themed icons use one colour: the second bubble becomes an outline so the two stay distinct.
mono = VEC % ("", GROUP % (path(main_cut + bha, "#FF000000", ' android:fillType="evenOdd"')
                           + path(second + latin, "#FF000000", ' android:fillType="evenOdd"')))
adaptive = """<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""
import os

os.makedirs(res + "/mipmap-anydpi-v26", exist_ok=True)
for name, body in (("drawable/ic_launcher_background.xml", background), ("drawable/ic_launcher_foreground.xml", foreground),
                   ("drawable/ic_launcher_monochrome.xml", mono), ("mipmap-anydpi-v26/ic_launcher.xml", adaptive)):
    open(f"{res}/{name}", "w", encoding="utf-8", newline="\n").write(HEADER + body)

svg = f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 240 120" width="960" height="480">
<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#FF9F43"/>
<stop offset="0.55" stop-color="#F2643D"/><stop offset="1" stop-color="#D63A6E"/></linearGradient>
<clipPath id="c"><circle cx="54" cy="54" r="36"/></clipPath><clipPath id="s"><rect x="18" y="18" width="72" height="72" rx="20"/></clipPath></defs>
<rect width="240" height="120" fill="#888"/>
<g transform="translate(6,6)" clip-path="url(#c)"><rect width="108" height="108" fill="url(#g)"/>
<g {SVG_T}><path d="{main}" fill="#fff"/><path d="{bha}" fill="{INK}"/><path d="{second}" fill="{INK}"/><path d="{latin}" fill="#fff" fill-rule="evenodd"/></g></g>
<g transform="translate(126,6)" clip-path="url(#s)"><rect width="108" height="108" fill="#1b1b1f"/>
<g {SVG_T}><path d="{main_cut + bha}" fill="#cfd8ff" fill-rule="evenodd"/><path d="{second + latin}" fill="#cfd8ff" fill-rule="evenodd"/></g></g>
</svg>"""
open(preview, "w", encoding="utf-8").write(svg)
print("ok")
