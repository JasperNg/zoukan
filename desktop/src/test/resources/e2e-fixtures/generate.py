"""Generates the fixture manga the offline end-to-end journeys read (see FixtureServer.kt).

Each page is one solid colour with a black frame and its number in white, so a test can tell from
a screenshot that the page drew (most of it is that colour) and which page it is. Every chapter
uses one image format; the same colours go into manifest.json, which the fixture source serves.

The "scrambled" pages are what a descrambling source gets from its site: the page squeezed into the
right half of the image and a garbage pattern in the left half. FixtureSource's interceptor
stretches the right half back over the whole image, so only a descrambled page is mostly its
colour. One of them is AVIF, which the JVM can decode (through FFmpeg) but not encode.

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


# Scrambled pages: (Pillow format name, file extension, save options, page colour)
SCRAMBLED = [
    ("JPEG", "jpg", {"quality": 90}, (60, 180, 170)),
    ("PNG", "png", {}, (170, 60, 160)),
    ("AVIF", "avif", {"quality": 90}, (100, 100, 220)),
]


def scrambled(colour, label):
    """The page squeezed into the right half, grey stripes (no page colour) in the left half."""
    image = Image.new("RGB", (WIDTH, HEIGHT), (128, 128, 128))
    draw = ImageDraw.Draw(image)
    for y in range(0, HEIGHT, 40):
        draw.rectangle([0, y, WIDTH // 2 - 1, y + 19], fill=(30, 30, 30))
    image.paste(page(colour, label).resize((WIDTH // 2, HEIGHT)), (WIDTH // 2, 0))
    return image


def scrambled_pages(images):
    pages = []
    for index, (pil, ext, options, colour) in enumerate(SCRAMBLED, start=1):
        name = f"scrambled-{index}.{ext}"
        scrambled(colour, str(index)).save(os.path.join(images, name), pil, **options)
        pages.append({"file": name, "rgb": list(colour)})
    return pages


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
    manifest["pages"]["scrambled"] = scrambled_pages(images)
    for index, colour in enumerate([(70, 70, 90), (90, 60, 60), (60, 90, 60), (80, 80, 40)], start=1):
        name = f"cover-{index}.png"
        page(colour, f"#{index}").resize((300, 450)).save(os.path.join(images, name), "PNG")
        manifest["covers"].append(name)
    with open(os.path.join(HERE, "manifest.json"), "w", newline="\n") as out:
        json.dump(manifest, out, indent=2)
        out.write("\n")


if __name__ == "__main__":
    main()
