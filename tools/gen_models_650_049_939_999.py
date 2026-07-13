#!/usr/bin/env python3
"""Placeholder looks for SCP-650, SCP-049, SCP-939 and SCP-999.

Emits 16x16 textures into assets/scp/textures/block/ (that folder is stitched
into the block atlas automatically - textures/entity/ would need an atlas
registration) and blocky element models into assets/scp/models/entity/.
The shapes are deliberately simple primitives: ops repaint the PNGs, the
model jsons keep pointing at them by name.

Run from the repo root:  python3 tools/gen_models_650_049_939_999.py
"""
import json, os, random, struct, zlib

ROOT = os.path.join(os.path.dirname(__file__), "..", "resource-pack", "assets", "scp")
TEX = os.path.join(ROOT, "textures", "block")
MODELS = os.path.join(ROOT, "models", "entity")


def png(path, px):
    h, w = len(px), len(px[0])
    rows = b"".join(b"\x00" + b"".join(bytes(p) for p in line) for line in px)
    def chunk(tag, data):
        return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data))
    data = (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b""))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)
    print(path)


def sheet(base, jitter=6, seed=1):
    rng = random.Random(seed)  # fixed seed - same texture every run
    return [[tuple(max(0, min(255, c + rng.randint(-jitter, jitter))) for c in base) + (255,)
             for _ in range(16)] for _ in range(16)]


def model(path, textures, elements):
    faces_all = ("north", "south", "east", "west", "up", "down")
    body = {
        "textures": {"particle": textures[next(iter(textures))]} | textures,
        "elements": [
            {"from": list(frm), "to": list(to),
             "faces": {f: {"texture": overrides.get(f, tex)} for f in faces_all}}
            for frm, to, tex, overrides in elements
        ],
    }
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        json.dump(body, f, indent=2)
        f.write("\n")
    print(path)


# ---------------------------------------------------------------- textures

# scp650 - matte black, so dark the shape is hard to read
body650 = sheet((16, 16, 18), jitter=4, seed=650)
png(os.path.join(TEX, "scp650_body.png"), body650)
face650 = sheet((16, 16, 18), jitter=4, seed=651)
for x, y in ((5, 5), (10, 5), (5, 6), (10, 6)):     # pits where eyes should be
    face650[y][x] = (4, 4, 5, 255)
for x in range(6, 10):                              # a smear of a mouth
    face650[10][x] = (8, 8, 9, 255)
png(os.path.join(TEX, "scp650_face.png"), face650)

# scp049 - near-black robe with vertical fold shadows
robe049 = sheet((26, 22, 28), jitter=5, seed=490)
for x in (3, 8, 12):
    for y in range(16):
        robe049[y][x] = (16, 13, 18, 255)
png(os.path.join(TEX, "scp049_robe.png"), robe049)
# scp049 - the beak mask: worn bone white, two dark glass eyes
mask049 = sheet((226, 221, 206), jitter=5, seed=491)
for x, y in ((4, 6), (11, 6)):
    mask049[y][x] = (28, 26, 24, 255)
    mask049[y][x + 1] = (28, 26, 24, 255)
for y in range(9, 14):                              # stitched seam down the beak
    mask049[y][8] = (180, 172, 156, 255)
png(os.path.join(TEX, "scp049_mask.png"), mask049)

# scp939 - skinless red flesh, sinew running lengthwise
skin939 = sheet((142, 30, 26), jitter=12, seed=939)
for y in (3, 7, 11):
    for x in range(16):
        skin939[y][x] = (96, 16, 14, 255)
png(os.path.join(TEX, "scp939_skin.png"), skin939)
# scp939 - the maw: dark red gum, one row of white teeth, no eyes at all
maw939 = sheet((70, 10, 10), jitter=6, seed=940)
for x in range(1, 15, 2):
    maw939[9][x] = (232, 228, 214, 255)
    maw939[10][x] = (218, 212, 196, 255)
png(os.path.join(TEX, "scp939_maw.png"), maw939)

# scp999 - happy orange gel with lighter blobs
body999 = sheet((240, 148, 44), jitter=10, seed=999)
rng = random.Random(998)
for _ in range(14):
    x, y = rng.randint(1, 14), rng.randint(1, 14)
    body999[y][x] = (250, 176, 84, 255)
png(os.path.join(TEX, "scp999_body.png"), body999)
face999 = [row[:] for row in body999]
for x, y in ((4, 5), (11, 5)):                      # big friendly eyes
    face999[y][x] = (40, 24, 12, 255)
    face999[y + 1][x] = (40, 24, 12, 255)
for x in range(5, 11):                              # the grin
    face999[10][x] = (150, 70, 20, 255)
face999[9][4] = face999[9][11] = (150, 70, 20, 255)
png(os.path.join(TEX, "scp999_face.png"), face999)

# ------------------------------------------------------------------ models

# SCP-650: humanoid statue, head a touch too small, arms far too long
model(os.path.join(MODELS, "scp650.json"),
      {"body": "scp:block/scp650_body", "face": "scp:block/scp650_face"},
      [
          ((5, 0, 7), (7, 6, 9), "#body", {}),        # left leg
          ((9, 0, 7), (11, 6, 9), "#body", {}),       # right leg
          ((4.5, 6, 6.5), (11.5, 12, 9.5), "#body", {}),  # torso
          ((2.8, 0.5, 7), (4.4, 11.5, 9), "#body", {}),   # arm, almost to the floor
          ((11.6, 0.5, 7), (13.2, 11.5, 9), "#body", {}),
          ((5.8, 12, 6.8), (10.2, 15.5, 9.2), "#body", {"north": "#face"}),
      ])

# SCP-049: robed figure, white beak mask pointing north
model(os.path.join(MODELS, "scp049.json"),
      {"robe": "scp:block/scp049_robe", "mask": "scp:block/scp049_mask"},
      [
          ((4, 0, 6), (12, 9, 10), "#robe", {}),          # skirt of the robe
          ((4.5, 9, 6.3), (11.5, 13, 9.7), "#robe", {}),  # chest
          ((3, 3.5, 7), (4.5, 12, 9), "#robe", {}),       # arms
          ((11.5, 3.5, 7), (13, 12, 9), "#robe", {}),
          ((5.5, 13, 6.8), (10.5, 16, 9.8), "#robe", {"north": "#mask"}),  # hooded head
          ((7, 13.4, 4.6), (9, 14.8, 6.8), "#mask", {}),  # the beak
      ])

# SCP-939: low, long, red quadruped; the maw is the whole face
model(os.path.join(MODELS, "scp939.json"),
      {"skin": "scp:block/scp939_skin", "maw": "scp:block/scp939_maw"},
      [
          ((5, 2, 3), (11, 7, 15), "#skin", {}),          # long body
          ((4.5, 2.5, -1.5), (11.5, 7.5, 3), "#skin", {"north": "#maw"}),  # head
          ((5, 0, 3.5), (6.6, 2, 5), "#skin", {}),        # legs
          ((9.4, 0, 3.5), (11, 2, 5), "#skin", {}),
          ((5, 0, 12.5), (6.6, 2, 14), "#skin", {}),
          ((9.4, 0, 12.5), (11, 2, 14), "#skin", {}),
          ((7, 3.5, 15), (9, 5.5, 18), "#skin", {}),      # tail
      ])

# SCP-999: rounded orange blob with stubby arms
model(os.path.join(MODELS, "scp999.json"),
      {"body": "scp:block/scp999_body", "face": "scp:block/scp999_face"},
      [
          ((3, 0, 3), (13, 7, 13), "#body", {"north": "#face"}),  # the blob
          ((4.5, 7, 4.5), (11.5, 10.5, 11.5), "#body", {}),       # rounded top
          ((1.4, 2.5, 6), (3, 5.5, 10), "#body", {}),             # stubby arms
          ((13, 2.5, 6), (14.6, 5.5, 10), "#body", {}),
      ])
