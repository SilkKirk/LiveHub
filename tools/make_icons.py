"""Generate the launcher icon set from the supplied illustration.

Run: python tools/make_icons.py <source-image>

The source is a portrait 600x849 illustration, but Android launcher icons are square and
get masked by the launcher (circle / squircle / rounded square). So the art is cropped to a
square centred on the face, and the result is emitted twice:

  * an adaptive icon (API 26+) split into a full-bleed foreground layer and a solid colour
    background, which the launcher then crops with whatever mask it likes, and
  * legacy density-bucket PNGs for API < 26, one square and one already circular-masked.

Keeping this as a script rather than checking in a pile of resized PNGs means the crop can
be re-tuned without re-deriving every density by hand.
"""

from __future__ import annotations

import sys
from pathlib import Path

from PIL import Image, ImageDraw

RES = Path("app/src/main/res")

# Legacy launcher icons, in dp. 48dp is the classic size; every bucket is a multiple.
LEGACY_DP = 48

# Adaptive icon layers are 108dp; the launcher guarantees only the centre 72dp survives
# masking, so the artwork is scaled to 108dp and allowed to bleed.
ADAPTIVE_DP = 108

DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}


def square_crop(image: Image.Image) -> Image.Image:
    """Crops to a square centred on the character's face.

    The source is portrait and the interesting part is the head and shoulders, which sit in
    the upper half. A plain centre crop would put the chin on the icon's midline and cut the
    top of the head off once a circular mask is applied.
    """
    width, height = image.size
    side = min(width, height)
    left = (width - side) // 2
    # Bias upward: keep the face slightly above centre so a round mask trims background
    # rather than hair.
    top = max(0, int(height * 0.02))
    top = min(top, height - side)
    return image.crop((left, top, left + side, top + side))


def background_colour(image: Image.Image) -> tuple[int, int, int]:
    """Average of the four corners, used behind the art for the adaptive background."""
    w, h = image.size
    corners = [
        image.getpixel((2, 2)),
        image.getpixel((w - 3, 2)),
        image.getpixel((2, h - 3)),
        image.getpixel((w - 3, h - 3)),
    ]
    channels = list(zip(*corners))
    return tuple(sum(c) // len(c) for c in channels)  # type: ignore[return-value]


def circular(image: Image.Image) -> Image.Image:
    """Applies a circular alpha mask, for the pre-26 round icon."""
    size = image.size[0]
    mask = Image.new("L", (size * 4, size * 4), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
    mask = mask.resize((size, size), Image.LANCZOS)
    out = image.convert("RGBA")
    out.putalpha(mask)
    return out


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit("usage: make_icons.py <source-image>")
    source = Image.open(sys.argv[1]).convert("RGB")
    art = square_crop(source)
    bg = background_colour(art)
    print(f"source {source.size}, crop {art.size}, background #{bg[0]:02X}{bg[1]:02X}{bg[2]:02X}")

    for bucket, scale in DENSITIES.items():
        folder = RES / f"mipmap-{bucket}"
        folder.mkdir(parents=True, exist_ok=True)

        adaptive_px = int(round(ADAPTIVE_DP * scale))
        legacy_px = int(round(LEGACY_DP * scale))

        # Adaptive foreground: full-bleed art on the 108dp canvas, transparent elsewhere so
        # the launcher's mask decides the shape.
        art.resize((adaptive_px, adaptive_px), Image.LANCZOS).convert("RGBA").save(
            folder / "ic_launcher_foreground.png"
        )

        legacy = art.resize((legacy_px, legacy_px), Image.LANCZOS)
        legacy.save(folder / "ic_launcher.png")
        circular(legacy).save(folder / "ic_launcher_round.png")

        print(f"  {bucket}: adaptive {adaptive_px}px, legacy {legacy_px}px")

    colors = RES / "values" / "colors.xml"
    colors.write_text(
        '<?xml version="1.0" encoding="utf-8"?>\n'
        "<resources>\n"
        f'    <color name="ic_launcher_background">#{bg[0]:02X}{bg[1]:02X}{bg[2]:02X}</color>\n'
        "</resources>\n",
        encoding="utf-8",
    )
    print(f"  wrote {colors}")


if __name__ == "__main__":
    main()
