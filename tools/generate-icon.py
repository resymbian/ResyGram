#!/usr/bin/env python3
"""Draw res/icon.png, the MIDlet icon the phone's application menu shows.

The JAD (and the JAR manifest) name it with MIDlet-Icon, and build.ps1 copies
everything under res/ into the JAR root, so the attribute and the file have to
agree on "/icon.png".

    python3 tools/generate-icon.py

48x48 is the size S60 3rd draws in the menu; the larger sizes are for the
launcher and for anything that scales the PNG up. Kept to a flat shape and a
vertical gradient so it stays legible at 48 px on a 2011 handset.
"""
import os

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, 'res', 'icon.png')

# Telegram-ish blues: bright at the top, deeper at the bottom.
TOP = (58, 194, 240)
BOTTOM = (23, 128, 188)
WING = (255, 255, 255)
FOLD = (214, 233, 246)


def rounded_gradient(size):
    """Rounded square with a vertical gradient, drawn at 4x and downsampled."""
    ss = 4
    n = size * ss
    grad = Image.new('RGB', (1, n))
    for y in range(n):
        t = y / (n - 1)
        grad.putpixel((0, y), tuple(int(TOP[i] + (BOTTOM[i] - TOP[i]) * t) for i in range(3)))
    img = Image.new('RGBA', (n, n), (0, 0, 0, 0))
    img.paste(grad.resize((n, n)), (0, 0))
    mask = Image.new('L', (n, n), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, n - 1, n - 1], radius=int(n * 0.22), fill=255)
    img.putalpha(mask)
    return img, ss


def plane(size):
    """White paper plane, the same silhouette as the Telegram logo."""
    img, ss = rounded_gradient(size)
    d = ImageDraw.Draw(img)

    def p(x, y):
        return (x * size * ss, y * size * ss)

    # Big wing: the wide triangle from the nose to the left tail.
    wing = [p(0.87, 0.14), p(0.09, 0.46), p(0.40, 0.58)]
    d.polygon(wing, fill=WING)

    # Folded fin: the narrow one that hangs down from the crease.
    fin = [p(0.40, 0.58), p(0.47, 0.89), p(0.62, 0.52)]
    d.polygon(fin, fill=FOLD)

    return img.resize((size, size), Image.LANCZOS)


def main():
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    # 48 is what the menu uses; drawing it from the same geometry keeps the
    # shape identical whatever size someone asks for later.
    plane(48).save(OUT)
    for size in (64, 96):
        plane(size).save(os.path.join(ROOT, 'docs', 'assets', 'icon-%d.png' % size))
    print('wrote %s (%dx%d)' % (os.path.relpath(OUT, ROOT), 48, 48))


if __name__ == '__main__':
    main()
