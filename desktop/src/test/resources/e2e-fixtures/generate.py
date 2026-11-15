"""Generates the fixture manga the offline end-to-end journeys read (see FixtureServer.kt).

Each page is one solid colour with a black frame and its number in white, so a test can tell from
a screenshot that the page drew (most of it is that colour) and which page it is. Every chapter
uses one image format; the same colours go into manifest.json, which the fixture source serves.

Needs Pillow 11.2 or newer (for AVIF). Run it from this folder:

    python -I generate.py
"""
import json
import os

from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
WIDTH, HEIGHT = 600, 900

# format -> (Pillow format name, file extension, save options, base colour per page)
FORMATS = {
    "jpeg": ("JPEG", "jpg", {"quality": 90}, [(200, 40, 40), (160, 30, 90), (220, 110, 30)]),
    "png": ("PNG", "png", {}, [(40, 170, 60), (20, 120, 120), (120, 180, 40)]),
    "webp": ("WEBP", "webp", {"quality": 90}, [(40, 70, 200), (90, 40, 180), (30, 140, 210)]),
    "avif": ("AVIF", "avif", {"quality": 90}, [(230, 150, 20), (200, 200, 40), (240, 100, 120)]),
}


def page(colour, label):
    image = Image.new("RGB", (WIDTH, HEIGHT), colour)
    draw = ImageDraw.Draw(image)
    draw.rectangle([0, 0, WIDTH - 1, HEIGHT - 1], outline=(0, 0, 0), width=12)
    try:
        font = ImageFont.truetype("arial.ttf", 120)
    except OSError:
        font = ImageFont.load_default()
    draw.text((WIDTH // 2, HEIGHT // 2), label, fill=(255, 255, 255), font=font, anchor="mm")
    return image


def main():
    images = os.path.join(HERE, "images")
    os.makedirs(images, exist_ok=True)
    manifest = {"pages": {}, "covers": []}
    for fmt, (pil, ext, options, colours) in FORMATS.items():
        pages = []
        for index, colour in enumerate(colours, start=1):
            name = f"{fmt}-{index}.{ext}"
            page(colour, f"{fmt.upper()} {index}").save(os.path.join(images, name), pil, **options)
            pages.append({"file": name, "rgb": list(colour)})
        manifest["pages"][fmt] = pages
    for index, colour in enumerate([(70, 70, 90), (90, 60, 60), (60, 90, 60), (80, 80, 40)], start=1):
        name = f"cover-{index}.png"
        page(colour, f"#{index}").resize((300, 450)).save(os.path.join(images, name), "PNG")
        manifest["covers"].append(name)
    with open(os.path.join(HERE, "manifest.json"), "w", newline="\n") as out:
        json.dump(manifest, out, indent=2)
        out.write("\n")


if __name__ == "__main__":
    main()
