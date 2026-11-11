"""Draws the built-in "night balcony" spatial pack: a pixel-art night view of Taipei 101 from a
Japanese-style balcony, as six depth layers.

Everything is drawn on a 180 x 400 grid and scaled up 6x without smoothing, so one art pixel is
a 6 px block on a 1080 x 2400 canvas. No third-party packages are needed.

    python tools/make_night_balcony.py            # writes the layers into the app's assets
    python tools/make_night_balcony.py preview.png  # also writes all layers flattened, to look at
"""
import math
import os
import random
import struct
import sys
import zlib

W, H, SCALE = 180, 400, 6
OUT = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'assets', 'samples', 'night-balcony')


def rgb(code, alpha=255):
    code = code.lstrip('#')
    return (int(code[0:2], 16), int(code[2:4], 16), int(code[4:6], 16), alpha)


CLEAR = (0, 0, 0, 0)


class Canvas:
    def __init__(self):
        self.rows = [[CLEAR] * W for _ in range(H)]

    def set(self, x, y, color):
        if 0 <= x < W and 0 <= y < H:
            self.rows[y][x] = color

    def get(self, x, y):
        if 0 <= x < W and 0 <= y < H:
            return self.rows[y][x]
        return CLEAR

    def rect(self, x0, y0, x1, y1, color):
        """Fills x0 <= x < x1, y0 <= y < y1."""
        for y in range(max(0, y0), min(H, y1)):
            row = self.rows[y]
            for x in range(max(0, x0), min(W, x1)):
                row[x] = color

    def halo(self, color, radius):
        """A dithered glow: checkerboard pixels of `color` within `radius` of anything painted."""
        solid = [[self.rows[y][x][3] > 0 for x in range(W)] for y in range(H)]
        for y in range(H):
            for x in range(W):
                if solid[y][x] or (x + y) % 2:
                    continue
                near = False
                for dy in range(-radius, radius + 1):
                    for dx in range(-radius, radius + 1):
                        if dx * dx + dy * dy > radius * radius:
                            continue
                        yy, xx = y + dy, x + dx
                        if 0 <= yy < H and 0 <= xx < W and solid[yy][xx]:
                            near = True
                            break
                    if near:
                        break
                if near:
                    self.rows[y][x] = color

    def png(self, path, scale=SCALE):
        raw = bytearray()
        for row in self.rows:
            line = bytearray()
            for pixel in row:
                line += bytes(pixel) * scale
            for _ in range(scale):
                raw.append(0)
                raw += line

        def chunk(tag, data):
            body = tag + data
            return struct.pack('>I', len(data)) + body + struct.pack('>I', zlib.crc32(body) & 0xFFFFFFFF)

        header = struct.pack('>IIBBBBB', len(self.rows[0]) * scale, len(self.rows) * scale, 8, 6, 0, 0, 0)
        with open(path, 'wb') as f:
            f.write(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', header) + chunk(b'IDAT', zlib.compress(bytes(raw), 9)) + chunk(b'IEND', b''))


BAYER = [[0, 8, 2, 10], [12, 4, 14, 6], [3, 11, 1, 9], [15, 7, 13, 5]]


# ------------------------------------------------------------------ sky

def draw_sky():
    c = Canvas()
    rng = random.Random(101)
    # Bands from the zenith down to the last orange of dusk on the horizon, blended with ordered
    # dithering. The horizon sits at about y = 300; everything below is hidden by the city.
    bands = ['#060a22', '#0a0f30', '#10143e', '#1a1a4e', '#2a1f5c', '#40266a', '#5e2f72', '#863c72', '#b24e6c', '#d8686a', '#ee8a55', '#f6a85a']
    stops = [0, 63, 115, 158, 192, 220, 243, 259, 270, 279, 287, 296]
    colors = [rgb(b) for b in bands]
    for y in range(H):
        i = 0
        while i < len(stops) - 2 and y >= stops[i + 1]:
            i += 1
        t = (y - stops[i]) / max(1, stops[i + 1] - stops[i])
        t = max(0.0, min(1.0, t))
        for x in range(W):
            pick = i + 1 if t * 16 > BAYER[y % 4][x % 4] + 0.5 else i
            c.rows[y][x] = colors[min(pick, len(colors) - 1)]

    # Stars: mostly single pixels, a few bright crosses. Drawn before the clouds so they hide some.
    for _ in range(130):
        x, y = rng.randrange(W), rng.randrange(0, 215)
        tone = rng.choice(['#ffffff', '#cfd8ff', '#fff2c8', '#9aa6e0', '#7c86c4'])
        c.set(x, y, rgb(tone))
    for x, y in [(70, 30), (158, 112), (16, 118), (104, 62), (48, 150)]:
        for dx, dy in [(0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)]:
            c.set(x + dx, y + dy, rgb('#ffffff' if (dx, dy) == (0, 0) else '#aab6f0'))

    # Long wind-streaked clouds, lit from below by the dusk: magenta high up, orange near the horizon.
    def streak(cx, cy, length, rows, tint, strength):
        tr, tg, tb, _ = rgb(tint)
        for j in range(rows):
            # Each row is offset and shorter than the one above: a ragged, slanted wisp.
            shrink = j * length // (rows * 3) + rng.randrange(0, 6)
            x0 = cx - length // 2 + shrink + j * 2
            x1 = cx + length // 2 - shrink + j * 2
            k = strength * (1.0 - 0.5 * j / rows)
            for x in range(x0, x1):
                edge = min(x - x0, x1 - 1 - x)
                if edge < 5 and (x + cy + j) % 2:
                    continue
                if (x * 5 + (cy + j) * 3) % 7 == 0:
                    continue
                r, g, b, _ = c.get(x, cy + j)
                c.set(x, cy + j, (round(r + (tr - r) * k), round(g + (tg - g) * k), round(b + (tb - b) * k), 255))

    streak(120, 120, 90, 3, '#6a3a8a', 0.45)
    streak(40, 147, 80, 4, '#8a3a7a', 0.45)
    streak(130, 172, 110, 5, '#b0406a', 0.5)
    streak(50, 197, 100, 4, '#c8485e', 0.5)
    streak(140, 218, 90, 5, '#d85a58', 0.55)
    streak(70, 236, 120, 4, '#e86a50', 0.55)
    streak(150, 252, 70, 3, '#f0804c', 0.6)
    streak(30, 264, 60, 3, '#f28a4e', 0.6)

    # Full moon with a dithered ring of glow and a few craters.
    mx, my, r = 106, 78, 11
    for y in range(my - r - 5, my + r + 6):
        for x in range(mx - r - 5, mx + r + 6):
            d = math.hypot(x - mx, y - my)
            if d <= r:
                shade = '#f6efcf' if (x - mx) + (y - my) < 5 else '#ddd3a8'
                c.set(x, y, rgb(shade))
            elif d <= r + 4 and (x + y) % 2 == 0:
                c.set(x, y, rgb('#3b3f86'))
    for cx, cy, cr in [(102, 74, 2), (110, 82, 2), (105, 84, 1), (112, 73, 1)]:
        for y in range(cy - cr, cy + cr + 1):
            for x in range(cx - cr, cx + cr + 1):
                if math.hypot(x - cx, y - cy) <= cr:
                    c.set(x, y, rgb('#c9bf94'))
    return c


# ------------------------------------------------------------------ far: mountains and distant lights

HORIZON = 300


def draw_far():
    c = Canvas()
    rng = random.Random(7)
    # Low mountains on the horizon, dusky purple against the orange.
    for x in range(W):
        top = int(HORIZON - 5 - 5 * math.sin(x / 31.0 + 2.2) - 2 * math.sin(x / 11.0))
        c.rect(x, top, x + 1, H, rgb('#33214e'))
    # The basin floor: a carpet of far-off lights, too small to be buildings.
    c.rect(0, HORIZON, W, H, rgb('#15123a'))
    for y in range(HORIZON, HORIZON + 40):
        for x in range(W):
            if rng.random() < 0.2:
                c.set(x, y, rgb(rng.choice(['#ffb060', '#ffd98a', '#e8905a', '#c9a56e', '#8a76a8', '#6f7fc0'])))
    return c


# ------------------------------------------------------------------ Taipei 101

TOWER_X = 74


def draw_tower():
    c = Canvas()
    rng = random.Random(101101)
    cx = TOWER_X
    dark, mid, light = rgb('#12303c'), rgb('#1b4654'), rgb('#27606e')
    lip, glow = rgb('#58d8c8'), rgb('#b9fff2')
    gold = rgb('#ffd27a')

    def row(y, half, lit=True):
        """One horizontal slice, shaded darker on the left so the tower reads as a solid."""
        for x in range(cx - half, cx + half):
            rel = (x - (cx - half)) / max(1, 2 * half - 1)
            c.set(x, y, dark if rel < 0.34 else mid if rel < 0.7 else light)
        if lit and y % 2 == 0:
            for x in range(cx - half + 1, cx + half - 1, 2):
                if rng.random() < 0.62:
                    c.set(x, y, rgb(rng.choice(['#8feede', '#5fbfb6', '#d8fff6'])))

    # Stocky rather than needle-thin, and only a little taller than its neighbours' double.
    # Tapering base first.
    for y in range(286, 320):
        row(y, 10 + (y - 286) * 3 // 34)
    # Eight flared sections, each wider at its top, with a bright lip and gold corner ornaments.
    for i in range(8):
        top = 198 + 11 * i
        for y in range(top, top + 11):
            row(y, 9 + (top + 10 - y) * 2 // 10)
        for x in range(cx - 12, cx + 12):
            c.set(x, top, lip)
            c.set(x, top + 1, rgb('#2f8f8c'))
        for x in (cx - 13, cx + 12):
            c.set(x, top, gold)
            c.set(x, top - 1, gold)
        c.set(cx - 1, top + 6, gold)
        c.set(cx, top + 6, gold)
    # Stepped crown, spire and antenna.
    for y in range(190, 198):
        row(y, 7)
    for y in range(183, 190):
        row(y, 5)
    for y in range(178, 183):
        row(y, 3, lit=False)
    for x in range(cx - 7, cx + 7):
        c.set(x, 190, lip)
    for x in range(cx - 5, cx + 5):
        c.set(x, 183, lip)
    c.rect(cx - 1, 165, cx + 1, 178, rgb('#3f8f96'))
    c.rect(cx - 1, 170, cx + 1, 172, glow)
    for y in range(158, 165):
        c.set(cx - 1, y, rgb('#6fb8c0'))
    # Aircraft warning light, with a warm tip like the lit spire at dusk.
    c.rect(cx - 2, 155, cx + 1, 158, rgb('#ff6a3a'))
    c.set(cx - 1, 154, rgb('#ffd0a0'))

    c.halo(rgb('#2f7f90', 120), 3)
    return c


# ------------------------------------------------------------------ city skyline

def draw_city():
    c = Canvas()
    rng = random.Random(2024)
    walls = [rgb('#0e1230'), rgb('#131a3e'), rgb('#0b0f28'), rgb('#171436')]
    warm = ['#ffd98a', '#ffc56a', '#ffb060', '#ff9a50', '#fff0b8']
    mixed = warm + ['#9fe8ff', '#ff9ac0', '#ffffff']

    def block(x0, x1, top, wall, lights, lit, step=2):
        c.rect(x0, top, x1, H, wall)
        c.rect(x0, top, x1, top + 1, rgb('#2a3470'))
        for wy in range(top + 2, HORIZON + 40, step):
            for wx in range(x0 + 1, x1 - 1, step):
                if rng.random() < lit:
                    c.set(wx, wy, rgb(rng.choice(lights)))

    # A low, dense carpet of buildings from edge to edge: the tower has the sky to itself.
    x = -3
    while x < W:
        width = rng.randrange(4, 10)
        top = rng.randrange(HORIZON - 12, HORIZON + 8)
        block(x, x + width, top, rng.choice(walls), mixed, rng.uniform(0.3, 0.6))
        x += width + rng.randrange(0, 2)

    # The handful that stand out. Beside the tower: a dark glass slab with a gold-edged top,
    # and a shorter one flood-lit in gold.
    block(92, 104, 262, rgb('#0a1026'), ['#3a4a80', '#566aa8', '#ffd98a'], 0.2)
    for xx in range(92, 104):
        c.set(xx, 262, rgb('#ffd27a'))
    for yy in range(262, 270):
        c.set(92, yy, rgb('#ffd27a'))
        c.set(103, yy, rgb('#ffd27a'))
    c.rect(103, 270, 104, 330, rgb('#28356e'))
    block(102, 114, 278, rgb('#3a2410'), ['#ffc56a', '#ffb060', '#ffe9a8'], 0.75)
    c.rect(102, 276, 114, 279, rgb('#ffcf70'))
    c.rect(105, 274, 111, 276, rgb('#ffb050'))

    # Left of the tower the city stays low; a few mid-rises break the line.
    block(8, 16, 287, walls[1], warm, 0.5)
    block(30, 37, 283, walls[0], mixed, 0.45)
    block(46, 54, 289, walls[3], warm, 0.6)
    c.rect(32, 280, 35, 283, rgb('#ff4a4a'))

    # The right-hand cluster: a white tower with a pink pointed cap, a red-lit block,
    # a tall pale slab and a dark one at the edge.
    block(132, 142, 285, rgb('#3a3f66'), ['#ffffff', '#dfe6ff', '#ffd98a'], 0.6)
    for i, half in enumerate([0, 1, 1, 2, 2, 3, 3, 4]):
        c.rect(137 - half, 277 + i, 137 + half + 1, 278 + i, rgb('#ff8ac8') if i < 5 else rgb('#ffd0e8'))
    c.rect(137, 273, 138, 277, rgb('#ffb0d8'))
    block(146, 156, 287, rgb('#4a1420'), ['#ff5a6a', '#ff8a7a', '#ffb060'], 0.7)
    c.rect(146, 286, 156, 288, rgb('#ff4a5a'))
    block(160, 169, 268, rgb('#2c3158'), ['#ffffff', '#fff0b8', '#dfe6ff'], 0.55)
    c.rect(160, 267, 169, 269, rgb('#8affb0'))
    block(174, 182, 255, rgb('#0a1026'), ['#3a4a80', '#ffd98a'], 0.25)
    c.rect(176, 251, 177, 255, rgb('#232a5c'))
    c.set(176, 250, rgb('#ff4a4a'))
    return c


# ------------------------------------------------------------------ the wooded hill below

def draw_hill():
    c = Canvas()
    rng = random.Random(55)
    base, leaf = rgb('#06080f'), rgb('#0c1418')
    tops = []
    for x in range(W):
        # Higher at both edges, dipping in the middle so the city shows above it.
        top = int(334 - 9 * ((x - 100) / 100.0) ** 2 + 2.5 * math.sin(x / 13.0 + 1.3) + 1.5 * math.sin(x / 4.0))
        tops.append(top)
        c.rect(x, top, x + 1, H, base)
    # Tree texture, and a ragged top edge of crowns.
    for x in range(W):
        if rng.random() < 0.35:
            c.set(x, tops[x] - 1, base)
        for y in range(tops[x], H):
            if rng.random() < 0.12:
                c.set(x, y, leaf)
    # A road winding down through the trees, picked out by orange street lamps.
    for i in range(22):
        t = i / 21.0
        x = round(24 + 70 * t + 10 * math.sin(t * 6.0))
        y = round(342 + 44 * t)
        c.set(x, y, rgb('#ff9a40'))
        if i % 3 == 0:
            c.set(x + 1, y, rgb('#ffd98a'))
    # Scattered lights: houses, a sports ground, lamps under the trees.
    for _ in range(70):
        x = rng.randrange(W)
        y = rng.randrange(tops[x] + 5, 394)
        tone = rng.choice(['#8aff9a', '#8aff9a', '#ffd98a', '#ffd98a', '#ffb060', '#dfe6ff'])
        c.set(x, y, rgb(tone))
        if rng.random() < 0.3:
            c.set(x + 1, y, rgb(tone))
    for x0, y0 in [(126, 358), (150, 368), (12, 362)]:
        for dx in range(0, 12, 2):
            c.set(x0 + dx, y0, rgb('#fff0b8'))
            c.set(x0 + dx, y0 + 3, rgb('#ffd98a'))
    return c


# ------------------------------------------------------------------ balcony and cat

# Sitting with its back to us, head turned to look over its shoulder.
# '#' fur, 'o' eye, 'n' nose, '.' nothing.
CAT = [
    '...................#.......#......',
    '..................###.....###.....',
    '..................####...####.....',
    '..................###########.....',
    '.................#############....',
    '.................#############....',
    '.................###oo###oo###....',
    '.................###oo###oo###....',
    '.................######n######....',
    '..................###########.....',
    '...................#########......',
    '..................##########......',
    '................############......',
    '..............##############......',
    '.............###############......',
    '............################......',
    '...........#################......',
    '..........##################......',
    '..........##################......',
    '.........###################......',
    '.........###################......',
    '........####################......',
    '........####################......',
    '........####################......',
    '........####################......',
    '........####################......',
    '........####################......',
    '........####################......',
    '........####################......',
    '........#####################.....',
    '........#####################.....',
    '.......######################.....',
]


RAIL = 322   # top of the railing's top rail
RAIL_BASE = 388  # top of its bottom rail; the floor starts 4 px below


# Where the cat sprite's top-left corner goes, and the two parts of it that move.
CAT_X, CAT_Y = 2, 302 - len(CAT)
TAIL_BOX = (0, CAT_Y + 32, 20, 14)      # x, y, width, height on the art grid
EARS_BOX = (CAT_X + 16, CAT_Y, 16, 3)
TAIL_FRAMES = 7
# The right ear folded out and down, for the twitch. Columns 24..31 of the first three rows.
EAR_FLICK = ['........', '....##..', '..####..']


def draw_balcony(tail_swing=0.0, ear_flick=False):
    """tail_swing: -1..1, how far the hanging tail has swung. ear_flick: the right ear folded down."""
    c = Canvas()
    wood, wood_dark, wood_light = rgb('#1c1412'), rgb('#110b0a'), rgb('#2c201c')
    metal, metal_light = rgb('#0a0b16'), rgb('#323a6c')

    # The eave overhead, seen from underneath: rafters fanning out from the wall behind us,
    # a fascia board along their ends, and the round end tiles of the roof edge below it.
    under = 38
    c.rect(0, 0, W, under, rgb('#0a0910'))
    c.rect(0, 13, W, 17, rgb('#15111c'))      # a purlin crossing the rafters
    c.rect(0, 17, W, 18, rgb('#06050a'))
    for xb in range(2, W, 16):
        xt = 90 + (xb - 90) * 1.5              # further apart at the top: they converge away from us
        for y in range(under):
            x = round(xt + (xb - xt) * y / (under - 1))
            c.rect(x, y, x + 4, y + 1, rgb('#1d1927'))
            c.set(x, y, rgb('#322b40'))        # the edge that catches the dusk
            c.set(x + 4, y, rgb('#050408'))    # and its shadow on the boards
    c.rect(0, under, W, under + 4, rgb('#231b2b'))
    c.rect(0, under, W, under + 1, rgb('#40324a'))
    c.rect(0, under + 4, W, under + 6, rgb('#101226'))
    for cx in range(5, W + 5, 10):
        cy = under + 7
        for y in range(cy - 4, cy + 5):
            for x in range(cx - 4, cx + 5):
                d = math.hypot(x - cx, y - cy)
                if d <= 4.3:
                    rim = d > 3.2 and y >= cy
                    c.set(x, y, rgb('#4c5480') if rim else rgb('#191d34'))
        c.set(cx, cy, rgb('#2c3256'))
    eave = under + 11  # the lowest row the roof edge reaches

    # Wind chime, hanging from the eave.
    fx, fy = 148, eave
    c.rect(fx, fy - 2, fx + 1, fy + 7, metal)
    for i, half in enumerate([2, 3, 4, 4, 4, 4]):
        for x in range(fx - half, fx + half + 1):
            c.set(x, fy + 7 + i, rgb('#9fe8ff', 215))
    c.set(fx - 2, fy + 9, rgb('#ffffff'))
    c.set(fx - 2, fy + 10, rgb('#ffffff'))
    c.rect(fx - 4, fy + 13, fx + 5, fy + 14, rgb('#5fb8d8'))
    c.rect(fx, fy + 14, fx + 1, fy + 20, metal)
    c.rect(fx - 2, fy + 20, fx + 3, fy + 36, rgb('#f2f0e0'))
    c.rect(fx - 1, fy + 24, fx + 2, fy + 26, rgb('#e0483a'))
    c.rect(fx, fy + 28, fx + 1, fy + 33, rgb('#e0483a'))

    # Looking slightly up, only a strip of floor shows below the railing.
    rail, base = RAIL, RAIL_BASE
    c.rect(0, base + 4, W, H, wood)
    c.rect(0, base + 4, W, base + 5, wood_dark)
    for x in range(0, W, 5):
        if (x * 7) % 11 < 4:
            c.set(x, base + 7, wood_light)

    # Railing: top rail, balusters, bottom rail, two posts.
    for x in range(4, W - 3, 9):
        c.rect(x, rail + 5, x + 2, base, metal)
        c.rect(x, rail + 5, x + 1, base, rgb('#141628'))
    c.rect(0, base, W, base + 4, metal)
    c.rect(0, base, W, base + 1, rgb('#1c2040'))
    for x in (9, 167):
        c.rect(x, rail - 4, x + 4, base + 4, metal)
        c.rect(x, rail - 4, x + 4, rail - 3, metal_light)
    c.rect(0, rail, W, rail + 5, metal)
    c.rect(0, rail, W, rail + 1, metal_light)

    # Potted plant standing on the floor, in front of the railing.
    px, py = 142, base - 29
    for cx, cy, r in [(px, py, 7), (px - 6, py + 6, 5), (px + 6, py + 5, 5), (px + 2, py - 8, 5), (px - 3, py - 5, 4)]:
        for y in range(cy - r, cy + r + 1):
            for x in range(cx - r, cx + r + 1):
                if math.hypot(x - cx, y - cy) <= r:
                    leaf = '#123626' if (x * 3 + y * 5) % 7 > 2 else '#1f5a3c'
                    if (x + y) % 9 == 0:
                        leaf = '#3c8a58'
                    c.set(x, y, rgb(leaf))
    c.rect(px - 1, py + 8, px + 1, py + 18, rgb('#2a1a12'))
    c.rect(px - 8, py + 16, px + 9, py + 19, rgb('#8a4632'))
    for i, y in enumerate(range(py + 19, py + 35)):
        half = 7 - i // 5
        c.rect(px - half, y, px + half + 1, y + 1, rgb('#6a3324'))
        c.set(px + half, y, rgb('#93503a'))

    # An air conditioner's outdoor unit in the left corner, standing on the floor in front of the
    # railing. It is the cat's perch: tall enough to lift the cat clear of a launcher's dock.
    ax0, ax1, atop = 0, 40, 302
    c.rect(ax0, atop, ax1, base + 2, rgb('#262a40'))
    c.rect(ax0, atop, ax1, atop + 2, rgb('#454c72'))          # top face catching the sky
    c.rect(ax1 - 2, atop + 2, ax1, base + 2, rgb('#1a1d30'))   # shaded side
    fx, fy, fr = 17, atop + 26, 13
    for y in range(fy - fr, fy + fr + 1):
        for x in range(fx - fr, fx + fr + 1):
            d = math.hypot(x - fx, y - fy)
            if d <= fr:
                ring = fr - 1 < d or abs(d - 8) < 0.6 or abs(d - 4) < 0.6
                spoke = x == fx or y == fy
                c.set(x, y, rgb('#3a4064') if ring or spoke else rgb('#10121e'))
    for y in range(atop + 8, atop + 44, 4):
        c.rect(33, y, 37, y + 1, rgb('#10121e'))
    c.rect(4, base + 2, 8, base + 4, rgb('#0a0b16'))
    c.rect(32, base + 2, 36, base + 4, rgb('#0a0b16'))

    # The cat sits on the unit, in silhouette against the dusk, its tail hanging down the front.
    assert atop - len(CAT) == CAT_Y
    ox, oy = CAT_X, CAT_Y
    fur, rim = rgb('#05050a'), rgb('#3a4488')
    marks = {'o': rgb('#f4e04a'), 'n': rgb('#d8869a')}
    for j, line in enumerate(CAT):
        if ear_flick and j < len(EAR_FLICK):
            line = line[:24] + EAR_FLICK[j] + line[32:]
        solid = [i for i, ch in enumerate(line) if ch != '.']
        for i, ch in enumerate(line):
            if ch == '.':
                continue
            # A thin edge of city light down both sides lifts the black shape off the dark hill.
            color = marks.get(ch) or (rim if i in (solid[0], solid[-1]) else fur)
            c.set(ox + i, oy + j, color)
    # The tail hangs from where the cat sits and curls at the tip; it swings like a pendulum,
    # so each point moves in proportion to how far down the tail it is.
    tail = [(7, 32), (6, 33), (6, 34), (5, 35), (5, 36), (5, 37), (5, 38), (5, 39), (5, 40), (6, 41), (6, 42), (7, 43), (8, 44), (9, 44), (10, 43)]
    for n, (tx, ty) in enumerate(tail):
        tx += round(tail_swing * 4.0 * n / (len(tail) - 1))
        c.rect(ox + tx, oy + ty, ox + tx + 2, oy + ty + 1, fur)
        c.set(ox + tx - 1, oy + ty, rim)
    return c


def write_strip(path, canvases, box):
    """Saves the same box cut out of several canvases, side by side: a sprite strip."""
    x0, y0, w, h = box
    strip = Canvas()
    strip.rows = [[canvas.rows[y0 + y][x0 + x] for canvas in canvases for x in range(w)] for y in range(h)]
    strip.png(path)


# ------------------------------------------------------------------ output

LAYERS = [
    ('sky', draw_sky, 1.0),
    ('far', draw_far, 0.85),
    ('tower', draw_tower, 0.7),
    ('city', draw_city, 0.5),
    ('hill', draw_hill, 0.3),
    ('balcony', draw_balcony, 0.05),
]


def main():
    os.makedirs(OUT, exist_ok=True)
    drawn = []
    for name, draw, _ in LAYERS:
        canvas = draw()
        canvas.png(os.path.join(OUT, name + '.png'))
        drawn.append(canvas)
        print('wrote', name + '.png')

    # The moving parts of the cat, as strips the app plays over the balcony layer.
    swings = [-1.0 + 2.0 * i / (TAIL_FRAMES - 1) for i in range(TAIL_FRAMES)]
    write_strip(os.path.join(OUT, 'cat-tail.png'), [draw_balcony(tail_swing=k) for k in swings], TAIL_BOX)
    still, flick = draw_balcony(), draw_balcony(ear_flick=True)
    write_strip(os.path.join(OUT, 'cat-ears.png'), [still, flick, still, flick, still], EARS_BOX)
    print('wrote cat-tail.png, cat-ears.png')
    print('sprite positions (px): tail', TAIL_BOX[0] * SCALE, TAIL_BOX[1] * SCALE, ' ears', EARS_BOX[0] * SCALE, EARS_BOX[1] * SCALE)

    if len(sys.argv) > 1:
        flat = Canvas()
        for canvas in drawn:
            for y in range(H):
                for x in range(W):
                    r, g, b, a = canvas.rows[y][x]
                    if a == 0:
                        continue
                    br, bg, bb, _ = flat.rows[y][x]
                    k = a / 255.0
                    flat.rows[y][x] = (round(r * k + br * (1 - k)), round(g * k + bg * (1 - k)), round(b * k + bb * (1 - k)), 255)
        flat.png(sys.argv[1], scale=3)
        print('wrote preview', sys.argv[1])


if __name__ == '__main__':
    main()
