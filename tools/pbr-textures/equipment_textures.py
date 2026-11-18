"""Equipment relief sprites with LabPBR 1.3 maps (batch 2026-11-15-equipment-visual-design, RELIEF_SPRITES_PLAN.md 4.1).

Writes, into the folder given as the only argument, for each relief sprite <name>:

- <name>.png: the baked albedo the chunk mesh uses without a shader pack: the relief lit from the upper left and its
  crevices darkened (the game's block shader reads no normals);
- <name>_flat.png: the same albedo without relief, which the shell model swaps in while a shader pack is in use
  (decision X5 A of 2026-11-10-custom-shader-system);
- <name>_flat_n.png, <name>_flat_s.png: its LabPBR 1.3 normal and specular maps, as wiring_textures.py writes them.

The baked sprite has no _n, so a pack never lights its relief twice. Python standard library only (PNG through zlib and
struct); the output is the same bytes on every run. Every map comes from one height field per sprite, in millimetres.
Image x is u, image y is v downwards; on a box's side faces and up a vertical shell, image up is world up.

The sprites:

- equipment_bolt_face (32 x 32): one hex nut, 1.6 d across its flats, with its stud end, drawn for M20 (d = 20 mm);
  every dimension scales with d, so one sprite serves every nut. The hexagon's corners lie on u (Mesh.disc stretches the
  sprite across the nut's corner-to-corner width). Replaces the nut prisms below Mesh.SOLID_NUT on an equipment's own
  joints (Mesh.bolts, Mesh.nut; pipe flanges keep prisms); its mean grey is BOLT_FACE_MEAN.
- motor_louvre (64 x 64, 1 m): a large motor's or generator's louvred cooler opening, 16 blades a metre, neutral grey for
  the body paint. Replaces the flat black OPENING boxes (ProcessUnit.largeMotor).
- equipment_cladding, painted_cladding (64 x 64, 2 m): today's aluminium jacketing (VesselTextures.cladding,
  paintedCladding), its laps as they are, twice the texels, with a stainless band strap every metre (owner,
  2026-11-15: "Keep straps"). The baked sprites keep today's pixels outside the straps and today's mean colour.

Plain sprites (batch 2026-11-18-equipment-reflections): every other painted body and bare steel sprite the reflection
pass draws gets <name>_flat.png (a copy: it has no relief), a flat <name>_flat_n.png and a uniform <name>_flat_s.png with
the smoothness and F0 of its class in ReflectionClass (PLAIN below; ReflectionClassTest checks them), so a shader pack
mirrors it too. They are read from the mod's own textures (src/main/resources/assets/icheme/textures/block).

_n: R, G = tangent-space normal X (+u) and Y (+v, DirectX "Y-" as LabPBR asks), B = AO, A = height (1 .. 255).
_s: R = smoothness, G = F0 (dielectrics, linear) or a LabPBR metal id (230 iron, 232 aluminium), B = porosity (0),
A = emission (0).
"""
import math
import os
import struct
import sys
import zlib

M64 = (1 << 64) - 1
# The baked relief's light, image coordinates (x right, y down, z out of the face): from the upper left and in front.
LIGHT = (lambda x, y, z: (x / math.sqrt(x * x + y * y + z * z), y / math.sqrt(x * x + y * y + z * z), z / math.sqrt(x * x + y * y + z * z)))(-.35, -.5, 1.0)


def png(path, pixels, alpha):
    """An 8-bit RGB (alpha False) or RGBA image of rows of tuples."""
    w, h = len(pixels[0]), len(pixels)
    raw = b"".join(b"\0" + bytes(v for px in row for v in (px if alpha else px[:3])) for row in pixels)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 6 if alpha else 2, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


# ---- Today's sprites, ported exactly from tools/custom-equipment-rendering-poc/textures/VesselTextures.java (the copies in
# assets matched these bit for bit; EquipmentTexturesTest checks the means).

def jhash(x, y, seed):
    """VesselTextures.hash: Java long arithmetic."""
    h = (x * 73856093) ^ (y * 19349663) ^ (seed * 83492791)
    h &= M64
    h ^= h >> 13
    h = (h * 0x5bd1e995) & M64
    h ^= h >> 15
    return (h & 0xffff) / 65535.0


def jround(v):
    return math.floor(v + .5)


def clamp(v):
    return int(max(0, min(255, jround(v))))


def plate(x, y, size, seed):
    grain = (jhash(x, y, seed) - .5) * 12
    mottle = 3 * math.sin(2 * math.pi * (2 * x + y) / size + seed) + 2 * math.sin(2 * math.pi * (x - 3 * y) / size + 2 * seed)
    return grain + mottle


def metal(x, y, size, seed, streak, flake):
    grain = (jhash(x, y, seed) - .5) * 3
    fine = broad = 0.0
    for k in range(-1, 2): fine += jhash((x + k) % size, 0, seed + 40) - .5
    for k in range(-3, 4): broad += jhash((x + k) % size, 0, seed + 41) - .5
    s = fine / 3 * 12 + broad / 7 * 34 + 1.5 * math.sin(2 * math.pi * y / size + seed) * (jhash(x, 1, seed + 42) - .5) * 2
    sparkle = 7 if jhash(x, y, seed + 50) > .975 else 0
    return grain + streak * s + flake * sparkle


STRAP = 2.5  # mm, the drawn height of a band strap over the jacketing
# The relief nut's mean grey, baked and flat (owner 2026-11-15, R-W4: "Darken the relief nut?" "yes"): the prism's BOLT
# sprite reads about 68; 78 keeps the lit edge visible. Low detail uses the same sprite and darkens with it. Before R-W4
# it kept the old low-detail sprite's mean, 122.6.
BOLT_FACE_MEAN = 78
NEUTRAL = 236  # VesselTextures.NEUTRAL, Paint.SPRITE_VALUE
CLADDING = (196, 199, 201)


def cladding_today(painted):
    """VesselTextures.paintedCladding (painted) or cladding: 32 x 32 for 2 m, (grain shade, lap shade) per texel."""
    out = []
    for y in range(32):
        row = []
        for x in range(32):
            if painted:
                grain, lap = metal(x, y, 32, 27, 1, .6), 0
                if y in (0, 11, 21): lap = 7
                elif y in (1, 12, 22): lap = -8
                elif x % 16 == 0: lap = 5
                elif x % 16 == 1: lap = -6
            else:
                grain, lap = plate(x, y, 32, 13) * .5 - 6 * jhash(x, 0, 14) * (y % 11) / 11.0, 0
                if y in (0, 11, 21): lap = 14
                elif y in (1, 12, 22): lap = -16
                elif x % 16 == 0: lap = 10
                elif x % 16 == 1: lap = -12
            row.append((grain, lap))
        out.append(row)
    return out


def colour(base, shade):
    return tuple(clamp(c + shade) for c in base)


def mean(pixels):
    n = len(pixels) * len(pixels[0])
    return tuple(sum(p[c] for row in pixels for p in row) / n for c in range(3))


# ---- Relief: normals, light and AO from a height field

def relief(height, du, dv, wrap):
    """(normal x, normal y, light share, ao) per texel: the light relative to a flat face (1 there), AO from how far the
    neighbourhood (3 mm across, one texel along) stands above the texel. `wrap`: the sprite tiles (else edges clamp)."""
    h, w = len(height), len(height[0])

    def at(x, y):
        if wrap: return height[y % h][x % w]
        return height[max(0, min(h - 1, y))][max(0, min(w - 1, x))]

    out = []
    reach = max(1, round(3.0 / du))
    for y in range(h):
        row = []
        for x in range(w):
            gx = (at(x + 1, y) - at(x - 1, y)) / (2 * du)
            gy = (at(x, y + 1) - at(x, y - 1)) / (2 * dv)
            nx, ny, nz = -gx, -gy, 1.0
            k = math.sqrt(nx * nx + ny * ny + nz * nz)
            nx, ny, nz = nx / k, ny / k, nz / k
            light = max(0.0, nx * LIGHT[0] + ny * LIGHT[1] + nz * LIGHT[2]) / LIGHT[2]
            around = max(at(xx, yy) for yy in (y - 1, y, y + 1) for xx in range(x - reach, x + reach + 1))
            ao = 1 - .45 * min(1.0, max(0.0, around - height[y][x]) / 5.0)
            row.append((nx, ny, light, ao))
        out.append(row)
    return out


def normal_map(height, shading):
    top = max(max(r) for r in height)
    low = min(min(r) for r in height)
    span = max(1e-9, top - low)
    return [[(jround((s[0] * .5 + .5) * 255), jround((s[1] * .5 + .5) * 255), jround(s[3] * 255),
              max(1, min(255, jround(1 + 254 * (height[y][x] - low) / span)))) for x, s in enumerate(row)]
            for y, row in enumerate(shading)]


def specular_map(parts):
    return [[(jround(smooth * 255), f0, 0, 0) for smooth, f0 in row] for row in parts]


def lit(albedo, shading):
    return [[tuple(c * s[2] * s[3] for c in albedo[y][x]) for x, s in enumerate(row)] for y, row in enumerate(shading)]


def to_bytes(pixels):
    return [[tuple(clamp(c) for c in p) + (255,) for p in row] for row in pixels]


def matched(pixels, target):
    """`pixels` (floats) shifted per channel so their mean is `target`'s: the paint tint and colours stay as they are."""
    m = [sum(p[c] for row in pixels for p in row) / (len(pixels) * len(pixels[0])) for c in range(3)]
    return [[tuple(p[c] + target[c] - m[c] for c in range(3)) for p in row] for row in pixels]


# ---- The sprites

def bolt_face():
    """A hex nut, corners on u, M20 proportions: across flats 1.6 d = 32 mm, nut top 20 mm with a 30 degree chamfer from
    0.8 of its flats, a countersink round the stud, the stud end 0.4 d proud with a 45 degree chamfer."""
    n, d = 32, 20.0
    across = 1.6 * d
    corner = across / math.sqrt(3)  # the hexagon's corner radius: the sprite's half width (Mesh.disc)
    px = 2 * corner / n
    apothem = across / 2
    height, parts = [], []
    for y in range(n):
        hrow, prow = [], []
        for x in range(n):
            u, v = (x + .5 - n / 2) * px, (y + .5 - n / 2) * px
            rho = math.hypot(u, v)
            # The hexagon's own radius at this angle (corners on u): the largest of the three flats' projections.
            flat = max(abs(u * math.cos(t) + v * math.sin(t)) for t in (math.pi / 6, math.pi / 2, 5 * math.pi / 6))
            if flat > apothem: flat = apothem  # outside the polygon: never drawn, kept continuous for the gradient
            h = d - max(0.0, min(rho, corner) - .8 * apothem) * math.tan(math.radians(30)) - max(0.0, flat - apothem)
            stud = d / 2
            if rho < stud: h = d + .4 * d - max(0.0, rho - (stud - 1.5))
            elif rho < stud + 1.5: h = d - (stud + 1.5 - rho)
            hrow.append(h)
            prow.append((.45, 230))
        height.append(hrow)
        parts.append(prow)
    shading = relief(height, px, px, False)
    grain = [[(jhash(x, y, 61) - .5) * 6 for x in range(n)] for y in range(n)]
    albedo = [[(100 + grain[y][x],) * 3 for x in range(n)] for y in range(n)]
    baked = matched(lit(albedo, shading), (BOLT_FACE_MEAN,) * 3)
    # Flat: the steel's colour without relief, at the baked sprite's mean brightness.
    flat = matched(albedo, (BOLT_FACE_MEAN,) * 3)
    return baked, flat, height, shading, parts


def motor_louvre():
    """A metre of louvres, 16 blades of 62.5 mm: each blade's face slopes out and down to its lip (14 degrees), then the
    dark slot under the lip; neutral grey for the body paint, as the motor's other sprites. The relief keeps the flat
    albedo's mean, so both variants show the paint alike."""
    n = 64
    px = 1000.0 / n
    height, parts, albedo = [], [], []
    for y in range(n):
        t = y % 4
        h = (-8.0, 0.0, 4.0, 8.0)[t]
        slot = t == 0
        hrow, prow, arow = [], [], []
        for x in range(n):
            hrow.append(h + (jhash(x, y, 71) - .5) * .2)
            prow.append((.1, 10) if slot else (.4, 10))
            g = 64 if slot else 230 * (1 + .02 * math.sin(2 * math.pi * (x + 2 * y) / n))
            arow.append((g, g, g))
        height.append(hrow)
        parts.append(prow)
        albedo.append(arow)
    shading = relief(height, px, px, True)
    return matched(lit(albedo, shading), mean(albedo)), albedo, height, shading, parts


def cladding(painted):
    """Today's 2 m of jacketing at 64 x 64 (each of today's texels two by two), laps as they are, a band strap every
    metre (rows 0 and 32, about 31 mm: a 19 mm band and its edge) drawn 2.5 mm proud (a band and its seal, raised so it reads at a block's scale); the laps are 0.8 mm steps."""
    today = cladding_today(painted)
    n = 64
    du = dv = 2000.0 / n
    base = NEUTRAL if painted else None
    strap_rows = (0, 32)
    height, parts, flat, today_pixels = [], [], [], []
    for y in range(n):
        hrow, prow, frow, trow = [], [], [], []
        for x in range(n):
            grain, lap = today[y // 2][x // 2]
            pixel = (colour((base,) * 3, grain + lap) if painted else colour(CLADDING, grain + lap))
            trow.append(pixel)
            lapped = (y // 2) in (0, 11, 21) or (x // 2) % 16 == 0
            strap = y in strap_rows
            hrow.append(STRAP if strap else (.8 if lapped else 0.0))
            frow.append(tuple(c + (10 if strap else 0) for c in (colour((base,) * 3, grain) if painted else colour(CLADDING, grain))))
            prow.append((.4, 10) if painted else ((.6, 230) if strap else (.45, 232)))
        height.append(hrow)
        parts.append(prow)
        flat.append(frow)
        today_pixels.append(trow)
    # The baked sprite: today's pixels, the straps' own relief and colour over them (the laps are drawn already).
    straps = [[(STRAP if y in strap_rows else 0.0) for x in range(n)] for y in range(n)]
    strap_shading = relief(straps, du, dv, True)
    baked = [[tuple((c + (10 if y in strap_rows else 0)) * s[2] * s[3] for c in today_pixels[y][x]) for x, s in enumerate(row)]
             for y, row in enumerate(strap_shading)]
    target = mean(today_pixels)
    baked = matched(baked, target)
    flat = matched(flat, target)
    shading = relief(height, du, dv, True)
    return baked, flat, height, shading, parts


SPRITES = {
    "equipment_bolt_face": bolt_face,
    "motor_louvre": motor_louvre,
    "equipment_cladding": lambda: cladding(False),
    "painted_cladding": lambda: cladding(True),
}


# ---- Plain sprites: no relief, LabPBR maps from their reflection class (ReflectionClass: smoothness = 1 - sqrt(blur),
# F0 = 0.05 linear for paint, the iron id 230 for steel).

PAINT = (115, 13)   # ReflectionClass.PAINT: blur 0.30, F0 0.05
STEEL = (104, 230)  # ReflectionClass.STEEL: blur 0.35, a metal (iron)
PLAIN = {
    "painted_shell": PAINT, "painted_head": PAINT, "painted_support": PAINT, "painted_machine": PAINT, "equipment_motor": PAINT,
    "ac_header": PAINT, "pump_casting": PAINT, "pump_volute": PAINT, "pump_fins": PAINT, "pump_joint": PAINT,
    "motor_frame": PAINT, "motor_plain": PAINT, "painted_heater": PAINT, "equipment_handrail": PAINT,
    "equipment_pipe": STEEL, "equipment_flange": STEEL, "equipment_bolt": STEEL, "shaft_steel": STEEL,
    "still_stainless": STEEL, "still_carbon_steel": STEEL, "equipment_grating": STEEL,
}
SOURCE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "src", "main", "resources", "assets", "icheme", "textures", "block")


def read_png(path):
    """An 8-bit RGB or RGBA, non-interlaced PNG as rows of RGB tuples."""
    with open(path, "rb") as f:
        data = f.read()
    i, idat = 8, b""
    while i < len(data):
        length, kind = struct.unpack(">I", data[i:i + 4])[0], data[i + 4:i + 8]
        body = data[i + 8:i + 8 + length]
        i += 12 + length
        if kind == b"IHDR":
            w, h, depth, colour, _, _, interlace = struct.unpack(">IIBBBBB", body)
            if depth != 8 or colour not in (2, 6) or interlace: sys.exit("%s: only 8-bit RGB or RGBA, not interlaced" % path)
        elif kind == b"IDAT":
            idat += body
    raw, bpp = zlib.decompress(idat), (3 if colour == 2 else 4)
    stride, rows, prev, p = w * bpp, [], bytearray(w * bpp), 0
    for _ in range(h):
        kind, line = raw[p], bytearray(raw[p + 1:p + 1 + stride])
        p += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0
            b, c = prev[x], (prev[x - bpp] if x >= bpp else 0)
            if kind == 1: line[x] = (line[x] + a) & 255
            elif kind == 2: line[x] = (line[x] + b) & 255
            elif kind == 3: line[x] = (line[x] + (a + b) // 2) & 255
            elif kind == 4:
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append([tuple(line[x * bpp:x * bpp + 3]) for x in range(w)])
        prev = line
    return rows


def main():
    if len(sys.argv) != 2: sys.exit("usage: equipment_textures.py <output folder>")
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    for name, build in SPRITES.items():
        baked, flat, height, shading, parts = build()
        png(os.path.join(out, name + ".png"), to_bytes(baked), False)
        png(os.path.join(out, name + "_flat.png"), to_bytes(flat), False)
        png(os.path.join(out, name + "_flat_n.png"), normal_map(height, shading), True)
        png(os.path.join(out, name + "_flat_s.png"), specular_map(parts), True)
    print("wrote %s (+ _flat, _flat_n, _flat_s) to %s" % (", ".join(SPRITES), out))
    for name, (smooth, f0) in PLAIN.items():
        pixels = read_png(os.path.join(SOURCE, name + ".png"))
        png(os.path.join(out, name + "_flat.png"), [[p + (255,) for p in row] for row in pixels], False)
        png(os.path.join(out, name + "_flat_n.png"), [[(128, 128, 255, 255) for _ in row] for row in pixels], True)
        png(os.path.join(out, name + "_flat_s.png"), [[(smooth, f0, 0, 0) for _ in row] for row in pixels], True)
    print("wrote the plain %s (_flat, _flat_n, _flat_s) to %s" % (", ".join(PLAIN), out))


if __name__ == "__main__":
    main()
