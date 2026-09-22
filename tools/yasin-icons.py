#!/usr/bin/env python3
"""Draws the sixteen Nexus icons, as PNG for the deck and as SVG for the film.

### Why this exists

Both renderers shipped with Microsoft's Fluent Emoji. They are good artwork and they are
emoji: full colour, softly shaded, and read by anyone over the age of twelve as decoration
rather than as information. On a slide put in front of somebody deciding whether to fund
the work, that is the wrong register, and it was the first thing said about the deck.

So these are drawn here instead: one stroke weight, one colour, geometric, on a 24 unit
grid, which is the vocabulary every professional interface icon set uses.

### Why it rasterises rather than calling a library

There is no image library on the build machine, and adding one to a hackathon repository to
draw sixteen 256px glyphs is a poor trade. Everything below is stdlib.

The rasteriser is a signed distance field: for each pixel, the distance to the nearest
primitive is measured, and anything within half a stroke width is ink. That is a lot less
code than a scanline filler and it gives round caps, round joins and clean antialiasing for
free, because all three fall out of "distance to a line segment" rather than needing to be
special-cased.

Run: python3 tools/yasin-icons.py
"""

import math
import struct
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PNG_DIR = ROOT / "src/main/resources/yasin-deck/icons"

GRID = 24.0          # the design grid every path below is drawn on
SIZE = 256           # the PNG the deck embeds
SUPER = 3            # supersampling factor; 3 is indistinguishable from 4 at this size
STROKE = 1.9         # in grid units, so it scales with the icon
INK = (0x0D, 0x15, 0x26)   # DeckTheme.INK. A slide's icons are type, not decoration.


# --------------------------------------------------------------------- primitives

def seg(x1, y1, x2, y2):
    return ("seg", x1, y1, x2, y2)


def poly(points, close=False):
    out = []
    pts = list(points)
    if close:
        pts = pts + [pts[0]]
    for i in range(len(pts) - 1):
        out.append(seg(pts[i][0], pts[i][1], pts[i + 1][0], pts[i + 1][1]))
    return out


def ring(cx, cy, r):
    return [("ring", cx, cy, r)]


def arc(cx, cy, r, a0, a1):
    """A stroked arc, approximated by segments. Angles in degrees, 0 = east, clockwise."""
    steps = max(3, int(abs(a1 - a0) / 12))
    pts = []
    for i in range(steps + 1):
        a = math.radians(a0 + (a1 - a0) * i / steps)
        pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
    return poly(pts)


def rect(x, y, w, h, r=0.0):
    """A stroked rectangle, with optional corner radius."""
    if r <= 0:
        return poly([(x, y), (x + w, y), (x + w, y + h), (x, y + h)], close=True)
    out = []
    out += poly([(x + r, y), (x + w - r, y)])
    out += poly([(x + w, y + r), (x + w, y + h - r)])
    out += poly([(x + w - r, y + h), (x + r, y + h)])
    out += poly([(x, y + h - r), (x, y + r)])
    out += arc(x + w - r, y + r, r, -90, 0)
    out += arc(x + w - r, y + h - r, r, 0, 90)
    out += arc(x + r, y + h - r, r, 90, 180)
    out += arc(x + r, y + r, r, 180, 270)
    return out


def dot(cx, cy, r):
    """A filled disc. Drawn as a zero length segment with its own width."""
    return [("dot", cx, cy, r)]


# ------------------------------------------------------------------ the icon set

def icons():
    """Every icon, as primitives on a 24 unit grid. Names match DeckIcons.pick()."""
    out = {}

    # A paper plane, not a cartoon rocket with flames.
    out["rocket"] = (
        poly([(21, 3), (3, 10.5), (10.5, 13.5), (21, 3)])
        + poly([(21, 3), (13.5, 21), (10.5, 13.5)])
        + poly([(10.5, 13.5), (21, 3)])
    )

    out["search"] = ring(10.5, 10.5, 6.5) + poly([(15.2, 15.2), (21, 21)])

    # Bars on a baseline. They stop short of it: drawn all the way down, the round caps
    # merge with the axis and the three bars read as one solid block.
    out["chart"] = (
        poly([(3.5, 20.5), (20.5, 20.5)])
        + poly([(7, 18.2), (7, 12.5)])
        + poly([(12, 18.2), (12, 6.5)])
        + poly([(17, 18.2), (17, 9.5)])
    )

    # A spanner. The head is an open C, not a ring: closed, it reads as a lollipop.
    out["toolbox"] = (
        arc(15.8, 8.2, 4.6, 55, 320)
        + poly([(12.6, 11.5), (4.8, 19.3)])
        + poly([(4.8, 19.3), (3.2, 17.7)])
        + poly([(3.2, 17.7), (11.0, 9.9)])
    )

    out["laptop"] = rect(4, 5, 16, 10.5, 1.4) + poly([(2.5, 19.5), (21.5, 19.5)])

    # Layers, which is what a design system actually is.
    out["palette"] = (
        poly([(12, 3), (21, 8), (12, 13), (3, 8)], close=True)
        + poly([(3, 12), (12, 17), (21, 12)])
        + poly([(3, 16), (12, 21), (21, 16)])
    )

    out["check"] = ring(12, 12, 9) + poly([(8, 12.2), (11, 15.2), (16, 9.2)])

    out["plug"] = (
        poly([(9, 3), (9, 8)]) + poly([(15, 3), (15, 8)])
        + rect(6, 8, 12, 5, 1.2)
        + arc(12, 13, 6, 0, 180)
        + poly([(12, 19), (12, 21.5)])
    )

    out["bolt"] = poly([(13.5, 2.5), (5, 13.5), (11.5, 13.5), (10.5, 21.5), (19, 10.5), (12.5, 10.5)], close=True)

    out["package"] = (
        poly([(12, 2.5), (20.5, 7), (20.5, 17), (12, 21.5), (3.5, 17), (3.5, 7)], close=True)
        + poly([(3.5, 7), (12, 11.5), (20.5, 7)])
        + poly([(12, 11.5), (12, 21.5)])
    )

    # A shackle is a U with straight legs, not a semicircle. Drawn as a bare half circle
    # the whole glyph reads as a handbag, which is what the first pass produced.
    out["lock"] = (
        rect(5.2, 10.8, 13.6, 9.8, 1.8)
        + poly([(8.2, 10.8), (8.2, 8.0)])
        + poly([(15.8, 10.8), (15.8, 8.0)])
        + arc(12, 8.0, 3.8, 180, 360)
    )

    # A single four point star, not a scatter of sparkles.
    out["sparkles"] = (
        poly([(12, 2.5), (14.2, 9.8), (21.5, 12), (14.2, 14.2), (12, 21.5), (9.8, 14.2), (2.5, 12), (9.8, 9.8)], close=True)
    )

    out["files"] = (
        poly([(7.5, 3.5), (14.5, 3.5), (19, 8), (19, 20.5), (7.5, 20.5)], close=True)
        + poly([(14.5, 3.5), (14.5, 8), (19, 8)])
        + poly([(10.5, 12.5), (16, 12.5)])
        + poly([(10.5, 16), (16, 16)])
    )

    out["target"] = ring(12, 12, 9) + ring(12, 12, 4.7) + dot(12, 12, 1.5)

    # A lightbulb. The neck is as wide as the glass is at the point it leaves it, so the
    # two read as one object; drawn narrower, the whole thing looks like a key.
    out["bulb"] = (
        arc(12, 9.6, 5.6, 145, 395)
        + poly([(8.8, 14.2), (8.8, 17.2)]) + poly([(15.2, 14.2), (15.2, 17.2)])
        + poly([(8.8, 17.2), (15.2, 17.2)])
        + poly([(10.1, 20.3), (13.9, 20.3)])
    )

    out["compass"] = ring(12, 12, 9) + poly([(15.5, 8.5), (13.5, 13.5), (8.5, 15.5), (10.5, 10.5)], close=True)

    return out


# -------------------------------------------------------------------- rasteriser

def distance(prims, px, py):
    """Distance from a point to the nearest primitive, and whether it is a filled dot."""
    best = 1e9
    fill = 1e9
    for p in prims:
        if p[0] == "seg":
            _, x1, y1, x2, y2 = p
            dx, dy = x2 - x1, y2 - y1
            length = dx * dx + dy * dy
            if length == 0:
                d = math.hypot(px - x1, py - y1)
            else:
                t = max(0.0, min(1.0, ((px - x1) * dx + (py - y1) * dy) / length))
                d = math.hypot(px - (x1 + t * dx), py - (y1 + t * dy))
            best = min(best, d)
        elif p[0] == "ring":
            _, cx, cy, r = p
            best = min(best, abs(math.hypot(px - cx, py - cy) - r))
        elif p[0] == "dot":
            _, cx, cy, r = p
            fill = min(fill, math.hypot(px - cx, py - cy) - r)
    return best, fill


def render(prims, size=SIZE):
    """One icon into an RGBA byte buffer, antialiased by supersampling."""
    scale = size / GRID
    half = STROKE / 2.0
    n = size * SUPER
    cover = [0] * (size * size)

    for sy in range(n):
        gy = (sy + 0.5) / SUPER / scale
        row = (sy // SUPER) * size
        for sx in range(n):
            gx = (sx + 0.5) / SUPER / scale
            d, f = distance(prims, gx, gy)
            if d <= half or f <= 0:
                cover[row + (sx // SUPER)] += 1

    top = SUPER * SUPER
    out = bytearray()
    for y in range(size):
        out.append(0)  # PNG filter: none
        for x in range(size):
            a = int(255 * cover[y * size + x] / top)
            out += bytes((INK[0], INK[1], INK[2], a))
    return bytes(out)


def png(raw, size=SIZE):
    """A minimal RGBA PNG. Three chunks is all a PNG needs to be valid."""
    def chunk(tag, data):
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    header = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", header)
            + chunk(b"IDAT", zlib.compress(raw, 9))
            + chunk(b"IEND", b""))


def svg(prims):
    """The same paths for the film, which draws them as SVG rather than as bitmaps."""
    parts = []
    for p in prims:
        if p[0] == "seg":
            parts.append('<path d="M%.2f %.2f L%.2f %.2f"/>' % p[1:])
        elif p[0] == "ring":
            _, cx, cy, r = p
            parts.append('<circle cx="%.2f" cy="%.2f" r="%.2f"/>' % (cx, cy, r))
        elif p[0] == "dot":
            _, cx, cy, r = p
            parts.append('<circle cx="%.2f" cy="%.2f" r="%.2f" fill="currentColor" stroke="none"/>' % (cx, cy, r))
    return ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" '
            'stroke="currentColor" stroke-width="%.2f" stroke-linecap="round" '
            'stroke-linejoin="round">%s</svg>' % (STROKE, "".join(parts)))


def main():
    import sys
    PNG_DIR.mkdir(parents=True, exist_ok=True)
    made = icons()
    only = set(sys.argv[1:])
    for name, prims in sorted(made.items()):
        if only and name not in only:
            continue
        raw = render(prims)
        (PNG_DIR / (name + ".png")).write_bytes(png(raw))
        print("  %-10s %s" % (name, (PNG_DIR / (name + ".png")).stat().st_size))
    (ROOT / "tools/yasin-icons.svg.txt").write_text(
        "\n".join("%s\t%s" % (name, svg(prims)) for name, prims in sorted(made.items()))
    )
    print("%d icons" % len(made))


if __name__ == "__main__":
    main()
