#!/usr/bin/env python3
"""Generates Android brand assets from the official DIM8 artwork.

Source of truth: raw-assets/dim8-vfd-logo.png. It is read only and excluded from
Git. The official mark's pixels are cropped and scaled but never redrawn.

Outputs:
  drawable-nodpi/dim8_logo_mark.png        Header logo ("8." without the wordmark,
                                           which is unreadable at header size)
  mipmap-{xxhdpi,xxxhdpi}/ic_launcher_foreground.png
                                           Adaptive icon: the official mark plus a
                                           slim thermometer stem rising from its dot

Only xxhdpi and xxxhdpi are generated because the Pixel XL is the sole target.

Requires Pillow. Run from the project root:
  python3 tools/generate_brand_assets.py
"""
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFilter

SOURCE = Path("raw-assets/dim8-vfd-logo.png")
RES = Path("app/src/main/res")

BACKGROUND = (0x01, 0x01, 0x00)
# Approved temperature palette, drawn from the bulb (cold) to the stem tip (hot).
HEAT_STOPS = [
    (0x43, 0x8E, 0xD4),
    (0x36, 0xB5, 0xA8),
    (0xF2, 0xBE, 0x29),
    (0xE7, 0x85, 0x33),
    (0xD8, 0x4A, 0x43),
]

# Measured from the 1254 px source: the "8." without the wordmark.
MARK_BOX = (395, 225, 940, 880)
# A square around the mark, used for the icon.
ICON_BOX = (335, 222, 995, 882)
# Measured dot center and stem extent, in source pixels.
DOT_CENTER = (896, 830)
# A full-height stem read as the digit 1 ("81") at small icon sizes.
STEM_TOP_Y = 560
STEM_HALF_WIDTH = 11
# Adaptive icons guarantee only the central 66 of 108 dp after masking.
ICON_CONTENT_DP = 62
ICON_CANVAS_DP = 108
ICON_SIZES = {"mipmap-xxhdpi": 324, "mipmap-xxxhdpi": 432}
LOGO_HEIGHT_PX = 176


def heat(fraction):
    """Interpolates the heat palette; 0 is the bulb and 1 is the stem tip."""
    scaled = max(0.0, min(1.0, fraction)) * (len(HEAT_STOPS) - 1)
    index = min(int(scaled), len(HEAT_STOPS) - 2)
    local = scaled - index
    start, end = HEAT_STOPS[index], HEAT_STOPS[index + 1]
    return tuple(round(a + (b - a) * local) for a, b in zip(start, end))


def on_background(image):
    """Places black-backed art on the brand background without changing its light."""
    return ImageChops.lighter(Image.new("RGB", image.size, BACKGROUND), image)


def stem_layer(size, origin):
    """Draws the thermometer stem on black, ending inside the official dot."""
    layer = Image.new("RGB", size, (0, 0, 0))
    draw = ImageDraw.Draw(layer)
    x = DOT_CENTER[0] - origin[0]
    top = STEM_TOP_Y - origin[1]
    bottom = DOT_CENTER[1] - origin[1]
    for y in range(top, bottom + 1):
        color = heat((bottom - y) / (bottom - top))
        draw.line([(x - STEM_HALF_WIDTH, y), (x + STEM_HALF_WIDTH, y)], fill=color)
    draw.pieslice(
        [x - STEM_HALF_WIDTH, top - STEM_HALF_WIDTH, x + STEM_HALF_WIDTH, top + STEM_HALF_WIDTH],
        180,
        360,
        fill=heat(1.0),
    )
    return layer


def build_icon(source, size):
    crop = source.crop(ICON_BOX)
    stem = stem_layer(crop.size, ICON_BOX[:2])
    glow = stem.filter(ImageFilter.GaussianBlur(9)).point(lambda value: int(value * 0.38))
    # The official dot is composited last, so the stem never overdraws the mark.
    art = ImageChops.lighter(ImageChops.lighter(glow, stem), Image.new("RGB", crop.size))
    x, y = DOT_CENTER[0] - ICON_BOX[0], DOT_CENTER[1] - ICON_BOX[1]
    mask = Image.new("L", crop.size, 0)
    ImageDraw.Draw(mask).ellipse([x - 30, y - 30, x + 30, y + 30], fill=255)
    art.paste((0, 0, 0), mask=mask)
    art = ImageChops.lighter(art, crop)

    content = round(size * ICON_CONTENT_DP / ICON_CANVAS_DP)
    scaled = on_background(art.resize((content, content), Image.LANCZOS))
    canvas = Image.new("RGB", (size, size), BACKGROUND)
    offset = (size - content) // 2
    canvas.paste(scaled, (offset, offset))
    return canvas


def save(image, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    image.save(path, optimize=True)
    print(f"{path} {image.size[0]}x{image.size[1]} {path.stat().st_size} bytes")


def main():
    source = Image.open(SOURCE).convert("RGB")
    mark = source.crop(MARK_BOX)
    width = round(mark.width * LOGO_HEIGHT_PX / mark.height)
    logo = on_background(mark.resize((width, LOGO_HEIGHT_PX), Image.LANCZOS))
    save(logo, RES / "drawable-nodpi" / "dim8_logo_mark.png")
    for folder, size in ICON_SIZES.items():
        save(build_icon(source, size), RES / folder / "ic_launcher_foreground.png")


if __name__ == "__main__":
    main()
