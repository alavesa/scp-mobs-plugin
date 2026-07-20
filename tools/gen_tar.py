#!/usr/bin/env python3
"""Tar / dark-substance block texture for SCP-106's pocket dimension.

Retextures the vanilla `sculk` block into an oozing black tar so the flat
pocket-dimension floor reads as corroding pitch. Pure stdlib, no PIL. Writes:

  resource-pack/assets/minecraft/textures/block/sculk.png        (16x16)
  resource-pack/assets/minecraft/textures/block/sculk.png.mcmeta (static; kills
      the vanilla sculk animation so our single frame renders correctly)

Note: this retextures EVERY sculk block, so overworld deep-dark sculk also
becomes tar. On a facility server that block is essentially unused above ground,
which is why sculk is the pocket-dimension floor in the first place.

Run from the repo root:  python3 tools/gen_tar.py
"""
import math, os, struct, zlib

W = H = 16

# A near-black tar with a faint violet corrosion, plus glossy highlights.
BASE      = (12, 10, 16, 255)
DEEP      = (6, 5, 9, 255)
OOZE      = (24, 16, 30, 255)     # faint violet sheen in the pits
HIGHLIGHT = (60, 54, 70, 255)     # wet glint
SPECK     = (40, 30, 48, 255)     # bubbling speckle


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(4))


class Canvas:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.px = [[BASE] * w for _ in range(h)]

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.px[y][x] = c

    def png(self, path):
        rows = b"".join(b"\x00" + b"".join(bytes(p) for p in line) for line in self.px)

        def chunk(tag, data):
            return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))

        data = (b"\x89PNG\r\n\x1a\n"
                + chunk(b"IHDR", struct.pack(">IIBBBBB", self.w, self.h, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb") as f:
            f.write(data)
        print(f"{path} ({self.w}x{self.h})")


def tar():
    c = Canvas(W, H)
    # deterministic pseudo-noise: layered sines -> slow, oily undulation
    for y in range(H):
        for x in range(W):
            n = (math.sin(x * 0.9 + y * 0.5) + math.sin(x * 0.3 - y * 1.1)
                 + math.sin((x + y) * 0.7)) / 3.0          # -1..1
            t = (n + 1) / 2                                  # 0..1
            if t < 0.35:
                col = mix(DEEP, BASE, t / 0.35)
            elif t < 0.75:
                col = mix(BASE, OOZE, (t - 0.35) / 0.40)
            else:
                col = mix(OOZE, HIGHLIGHT, (t - 0.75) / 0.25)
            c.set(x, y, col)
    # a few bright wet glints and dark bubbles at fixed spots
    for (gx, gy) in [(3, 4), (11, 2), (8, 9), (13, 12), (5, 13)]:
        c.set(gx, gy, HIGHLIGHT)
        c.set(gx + 1, gy, mix(HIGHLIGHT, BASE, 0.5))
    for (bx, by) in [(2, 10), (9, 3), (14, 7), (6, 6), (12, 14)]:
        c.set(bx, by, DEEP)
        c.set(bx, by + 1, mix(DEEP, SPECK, 0.5))
    return c


base = os.path.join(os.path.dirname(__file__), "..", "resource-pack",
                    "assets", "minecraft", "textures", "block")
tar().png(os.path.join(base, "sculk.png"))
# Empty metadata overrides the vanilla animated sculk.png.mcmeta -> static texture.
with open(os.path.join(base, "sculk.png.mcmeta"), "w") as f:
    f.write("{}\n")
print(os.path.join(base, "sculk.png.mcmeta"))
