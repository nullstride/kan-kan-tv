"""Regenerate the Apple-TV-inspired 看看电视 identity.
Usage: python tools/generate_tv_banner.py [Chinese font path]
Requires Pillow, fontTools and aggdraw. System fonts are used locally only.
"""
from pathlib import Path
import sys
from PIL import Image, ImageDraw, ImageFont
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.basePen import BasePen
from fontTools.pens.boundsPen import BoundsPen
from fontTools.svgLib.path import parse_path
import aggdraw

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
BRAND = ROOT / "docs/brand"
FONT = sys.argv[1] if len(sys.argv) > 1 else "C:/Windows/Fonts/msyh.ttc"
BG = "#141519"
ANDROID = "http://schemas.android.com/apk/res/android"
SILVER = [(0, "#FFFFFF"), (0.40, "#F5F6FB"), (0.58, "#DFE4F4"),
          (0.72, "#F0DFEA"), (0.87, "#D4ECE9"), (1, "#EEF5FC")]
# The play mark replaces a brand symbol, retaining our own identity.
PLAY = "M18,40 Q17,39.4 17,41 L17,65 Q17,66.6 18.5,65.7 L36.5,54.7 Q38,53.8 36.5,52.9 Z"


def wordmark():
    font = TTFont("C:/Windows/Fonts/seguisb.ttf")
    glyphs = font.getGlyphSet()
    mapping = font.getBestCmap()
    scale = 49 / font["head"].unitsPerEm
    pen = SVGPathPen(glyphs)
    x = 41
    for char in "tv":
        glyph = glyphs[mapping[ord(char)]]
        glyph.draw(TransformPen(pen, (scale, 0, 0, -scale, x, 71)))
        x += glyph.width * scale - 0.6
    font.close()
    return pen.getCommands()


def center_mark(path):
    bounds = BoundsPen(None)
    parse_path(path, bounds)
    left, top, right, bottom = bounds.bounds
    # Center the visible contours, then compensate for the play triangle's
    # left-heavy ink distribution. Shared by Android, SVG and raster outputs.
    optical_x, optical_y = 0.85, -0.05
    dx = 54 - (left + right) / 2 + optical_x
    dy = 54 - (top + bottom) / 2 + optical_y
    pen = SVGPathPen(None)
    parse_path(path, TransformPen(pen, (1, 0, 0, 1, dx, dy)))
    return pen.getCommands()


TV = wordmark()
MARK = center_mark(PLAY + " " + TV)


def write(path, text):
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text + "\n", encoding="utf-8")


def gradient_xml(colors, start=(20, 28), end=(88, 82)):
    items = "".join(f'<item android:offset="{offset}" android:color="{color}"/>' for offset, color in colors)
    return (f'<aapt:attr name="android:fillColor"><gradient android:type="linear" '
            f'android:startX="{start[0]}" android:startY="{start[1]}" '
            f'android:endX="{end[0]}" android:endY="{end[1]}">{items}</gradient></aapt:attr>')


def vector(scale, legacy=False, mono=False):
    offset = 54 * (1-scale)
    tile = ('<path android:fillColor="#17181C" android:pathData="M24,0 H84 Q108,0 108,24 V84 Q108,108 84,108 H24 Q0,108 0,84 V24 Q0,0 24,0 Z"/>' if legacy else "")
    fill = 'android:fillColor="#F8F9FC"' if legacy or mono else ''
    gradient = '' if legacy or mono else gradient_xml(SILVER)
    return (f'<?xml version="1.0" encoding="utf-8"?>\n'
            f'<vector xmlns:android="{ANDROID}" xmlns:aapt="http://schemas.android.com/aapt" '
            'android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">\n'
            f'{tile}<group android:scaleX="{scale}" android:scaleY="{scale}" '
            f'android:translateX="{offset:.4f}" android:translateY="{offset:.4f}">\n'
            f'<path {fill} android:pathData="{MARK}">{gradient}</path>\n</group>\n</vector>')


def svg(mask="rounded", adaptive=False):
    # The system's visible adaptive window is 72 of its 108-unit viewport.
    scale = 0.68 * 108/72 if adaptive else 0.88
    offset = 54 * (1-scale)
    stops = ''.join(f'<stop offset="{o}" stop-color="{c}"/>' for o, c in SILVER)
    radius = 24 if mask == "rounded" else 0
    shape = ('<circle cx="54" cy="54" r="54"/>' if mask == "circle" else
             f'<rect width="108" height="108" rx="{radius}"/>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="1080" height="1080" viewBox="0 0 108 108">'
            '<title>看看电视</title><defs>'
            '<linearGradient id="bg" x2="0" y2="1"><stop stop-color="#303135"/>'
            '<stop offset="0.56" stop-color="#16171B"/><stop offset="1" stop-color="#07080B"/></linearGradient>'
            f'<linearGradient id="silver" x1="0" y1="0" x2="1" y2="1">{stops}</linearGradient>'
            f'<clipPath id="mask">{shape}</clipPath></defs>'
            f'<g clip-path="url(#mask)"><rect width="108" height="108" fill="url(#bg)"/>'
            f'<g transform="translate({offset} {offset}) scale({scale})">'
            f'<path d="{MARK}" fill="url(#silver)"/></g>'
            '</g></svg>')


def main():
    ImageFont.truetype(FONT, 32)
    BRAND.mkdir(parents=True, exist_ok=True)
    write("app/src/main/res/drawable/ic_launcher.xml", vector(0.88, legacy=True))
    write("app/src/main/res/mipmap-anydpi/ic_launcher.xml", vector(0.88, legacy=True))
    write("app/src/main/res/drawable/ic_launcher_foreground.xml", vector(0.68))
    write("app/src/main/res/drawable/ic_launcher_monochrome.xml", vector(0.68, mono=True))
    write("app/src/main/res/drawable/ic_launcher_background.xml",
          f'<?xml version="1.0" encoding="utf-8"?>\n<shape xmlns:android="{ANDROID}" android:shape="rectangle">'
          '<gradient android:angle="270" android:startColor="#303135" android:centerColor="#16171B" android:endColor="#07080B"/></shape>')
    for api in [26, 33]:
        mono = '<monochrome android:drawable="@drawable/ic_launcher_monochrome"/>' if api == 33 else ''
        write(f"app/src/main/res/mipmap-anydpi-v{api}/ic_launcher.xml",
              f'<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="{ANDROID}">'
              '<background android:drawable="@drawable/ic_launcher_background"/>'
              f'<foreground android:drawable="@drawable/ic_launcher_foreground"/>{mono}</adaptive-icon>')
    write("app/src/main/res/values/branding.xml",
          f'<?xml version="1.0" encoding="utf-8"?>\n<resources><color name="launcher_background">{BG}</color></resources>')
    write("docs/brand/logo.svg", svg())
    for mask in ["rounded", "circle", "square"]:
        write(f"docs/brand/launcher-{mask}.svg", svg(mask, adaptive=True))
    render_outputs()
    print("Generated all Android icons, TV banner and brand previews.")


class RasterPen(BasePen):
    def __init__(self, path):
        super().__init__(None)
        self.path = path

    def _moveTo(self, point):
        self.path.moveto(*point)

    def _lineTo(self, point):
        self.path.lineto(*point)

    def _curveToOne(self, a, b, c):
        self.path.curveto(*a, *b, *c)

    def _closePath(self):
        self.path.close()


def ramp(size, stops, diagonal=False):
    from PIL import ImageColor
    # A small gradient is scaled up, keeping generation quick and deterministic.
    colors = [(o, ImageColor.getrgb(c)) for o, c in stops]
    pixels = []
    for y in range(256):
        for x in range(256):
            t = (x+y)/510 if diagonal else y/255
            for (lo, ca), (hi, cb) in zip(colors, colors[1:]):
                if lo <= t <= hi:
                    a = (t-lo)/(hi-lo)
                    pixels.append(tuple(round(u+(v-u)*a) for u, v in zip(ca, cb)))
                    break
    im = Image.new("RGB", (256, 256))
    im.putdata(pixels)
    return im.resize(size, Image.Resampling.BICUBIC)


def mark_mask(size, scale, center=None, unit=None):
    mask = Image.new("L", size, 0)
    path = aggdraw.Path()
    unit = unit or size[0]/108*scale
    cx, cy = center or (size[0]/2, size[1]/2)
    pen = TransformPen(RasterPen(path), (unit, 0, 0, unit, cx-54*unit, cy-54*unit))
    parse_path(MARK, pen)
    ctx = aggdraw.Draw(mask)
    ctx.path(path, aggdraw.Brush("white"))
    ctx.flush()
    return mask


def icon(size, mask="rounded", adaptive=False):
    ss = 3
    n = size*ss
    canvas = ramp((n, n), [(0, "#303135"), (0.56, "#16171B"), (1, "#07080B")])
    alpha = mark_mask((n, n), 0.68*108/72 if adaptive else 0.88)
    silver = ramp((n, n), SILVER, diagonal=True)
    canvas.paste(silver, (0, 0), alpha)
    clip = Image.new("L", (n, n), 0)
    d = ImageDraw.Draw(clip)
    if mask == "circle":
        d.ellipse((0, 0, n-1, n-1), fill=255)
    else:
        d.rounded_rectangle((0, 0, n-1, n-1), radius=n*24/108 if mask == "rounded" else 0, fill=255)
    canvas.putalpha(clip)
    return canvas.resize((size, size), Image.Resampling.LANCZOS)


def render_outputs():
    icon(1024).save(BRAND / "logo.png")
    icon(320).save(BRAND / "logo-preview.png")
    # The banner uses the same wordmark and silver material as the launcher icon.
    ss = 3
    banner = ramp((640*ss, 360*ss), [(0, "#26272C"), (1, "#08090C")])
    title_font = ImageFont.truetype(FONT, 32*ss)
    d = ImageDraw.Draw(banner)
    title_box = d.textbbox((320*ss, 258*ss), "看看电视", font=title_font, anchor="mm")
    bounds = BoundsPen(None)
    parse_path(MARK, bounds)
    block_top = min(145*ss + (bounds.bounds[1]-54)*2.9*ss, title_box[1])
    block_bottom = max(145*ss + (bounds.bounds[3]-54)*2.9*ss, title_box[3])
    block_shift = 180*ss - (block_top+block_bottom)/2
    alpha = mark_mask(banner.size, 1, center=(320*ss, 145*ss+block_shift), unit=2.9*ss)
    banner.paste(ramp(banner.size, SILVER, diagonal=True), (0, 0), alpha)
    d = ImageDraw.Draw(banner)
    d.text((320*ss, 258*ss+block_shift), "看看电视", font=title_font,
           fill="#E7E8ED", anchor="mm")
    banner = banner.resize((640, 360), Image.Resampling.LANCZOS)
    banner.save(RES / "drawable-nodpi/ic_tv_banner.png")
    sheet = Image.new("RGB", (1000, 640), "#F5F5F7")
    d = ImageDraw.Draw(sheet)
    title = ImageFont.truetype(FONT, 28)
    small = ImageFont.truetype(FONT, 17)
    d.text((42, 28), "看看电视", font=title, fill="#1D1D1F")
    d.text((780, 38), "图标与电视横幅", font=small, fill="#77777E")
    for x, shape, label in [(42, "rounded", "圆角图标"), (224, "circle", "圆形裁切"), (406, "square", "方形裁切")]:
        im = icon(144, shape, adaptive=True)
        sheet.paste(im, (x, 108), im)
        d.text((x, 268), label, font=small, fill="#6E6E73")
    d.text((654, 108), "桌面尺寸", font=small, fill="#6E6E73")
    for x, size in [(654, 48), (738, 32)]:
        im = icon(size, "circle", adaptive=True)
        sheet.paste(im, (x, 150), im)
    sheet.paste(banner.resize((480, 270), Image.Resampling.LANCZOS), (42, 330))
    d.text((570, 366), "石墨黑 · 银白字标", font=title, fill="#1D1D1F")
    d.text((570, 415), "柔和光泽，轻微虹彩", font=small, fill="#6E6E73")
    d.text((570, 452), "与深色玻璃 UI 保持一致", font=small, fill="#6E6E73")
    sheet.save(BRAND / "preview.png")


if __name__ == "__main__":
    main()
