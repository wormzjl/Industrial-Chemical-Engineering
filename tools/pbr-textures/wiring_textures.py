"""Instrument wiring textures with LabPBR 1.3 maps (batch 2026-11-10-custom-shader-system, plan section 4.4).

Writes branch.png and tray_1.png .. tray_6.png, each with _n and _s beside it, into the folder given as the only
argument. Python standard library only (PNG through zlib and struct). Every map comes from one height field per
texture, in millimetres:

- u runs across the ribbon (image x), v along the run (image y, downwards); one image height is one 0.5 m period.
- branch.png: left half (u 0 .. 0.5) is the 20 mm branch ribbon: one 12 mm light-blue armoured IS cable on a narrow
  galvanised strap, held by a 316L saddle once per period. Right half (u 0.5 .. 1) is the same sheath unrolled round
  the cable (37.7 mm of circumference), for the drip-loop tube at each transmitter head.
- tray_N.png: the 25 mm baseline: a perforated galvanised channel with a lip at each edge, N cables side by side on
  its floor, one black nylon tie across once per period. Perforations are transparent (cutout), so the skin shows
  through them.

_n: R, G = tangent-space normal X (+u) and Y (+v, DirectX "Y-" as LabPBR asks), B = AO, A = height (1 .. 255).
_s: R = smoothness, G = F0 (dielectrics, linear) or 230 (iron, every steel part), B = porosity (0), A = emission (0).
The albedo carries no painted relief.
"""
import math
import os
import struct
import sys
import zlib

W, H = 64, 128
PERIOD = 500.0  # mm per image height

# Albedo (sRGB) and _s of each part: (rgb, smoothness, f0 or metal id)
SHEATH = ((59, 131, 189), .35, 10)  # light blue (RAL 5012 class), intrinsically safe circuits; PVC F0 about 0.04
GALVANISED = ((168, 173, 171), .50, 230)
STAINLESS = ((196, 199, 201), .65, 230)  # 316L
NYLON = ((34, 34, 36), .30, 10)


def png(path, pixels):
    raw = b"".join(b"\0" + bytes(v for px in row for v in px) for row in pixels)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", W, H, 8, 6, 0, 0, 0))
                + chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))


def noise(x, y, seed):
    """Deterministic value in [-1, 1] per texel: the galvanised spangle."""
    n = (x * 374761393 + y * 668265263 + seed * 2246822519) & 0xFFFFFFFF
    n = ((n ^ (n >> 13)) * 1274126177) & 0xFFFFFFFF
    return ((n ^ (n >> 16)) & 0xFFFF) / 32767.5 - 1


def cable(x, centre, radius, floor):
    """Height of a round cable of `radius` resting on `floor` at offset x, or None off the cable."""
    d = x - centre
    return None if abs(d) >= radius else floor + radius + math.sqrt(radius * radius - d * d)


def band(v, half):
    """Whether v (mm along the period) lies within `half` of the period's start, wrapping."""
    d = v % PERIOD
    return min(d, PERIOD - d) <= half


def branch_texel(x, y):
    """(height mm, part or None for a hole) of branch.png at texel (x, y)."""
    v = (y + .5) * PERIOD / H
    if x >= W // 2:
        return 7.0, SHEATH  # the unrolled sheath: the tube's own geometry gives the relief
    u = (x + .5) * 20.0 / (W // 2)  # mm across the 20 mm ribbon
    if u < .6 or u > 19.4:
        return 0.0, None
    sag = .5 * math.sin(math.pi * v / PERIOD) ** 2  # the cable bows off its strap between saddles
    top = cable(u, 10.0, 6.0, 1.2 + sag)
    h, part = (top, SHEATH) if top is not None else (1.2, GALVANISED)
    if band(v, 9.0):  # the saddle, arched over the cable, its feet on the strap
        arch = (cable(u, 10.0, 7.2, 1.2) or 0.0) + .4
        return max(arch, 2.2), STAINLESS
    return h, part


def tray_texel(n):
    width, lip, floor = 25.0, 2.5, .6
    inner = width - 2 * lip
    d = min(11.0, (inner - .6) / n)  # cable diameter on the 25 mm drawing width (X10 A)
    centres = [lip + inner / 2 + (i - (n - 1) / 2) * d for i in range(n)]

    def texel(x, y):
        u = (x + .5) * width / W
        v = (y + .5) * PERIOD / H
        if u < lip or u > width - lip:
            edge = min(u, width - u)
            h, part = 5.0 + .8 * math.sin(math.pi * edge / lip), GALVANISED  # the channel's rolled lips
        else:
            h, part = floor, GALVANISED
            for c in centres:
                top = cable(u, c, d / 2, floor)
                if top is not None and top > h: h, part = top, SHEATH
            if part is GALVANISED:  # 3 x 10 mm slots, staggered, in the floor only
                row = int(v // 20)
                su = (u - lip + (1.5 if row % 2 else 0)) % 4.0
                if su < 1.6 and 4 < v % 20 < 15: return 0.0, None
        if band(v, 4.0):  # the nylon tie strap round the bundle and over the lips
            return h + .8, NYLON
        return h, part

    return texel


def maps(texel, du, dv, seed):
    height = [[0.0] * W for _ in range(H)]
    part = [[None] * W for _ in range(H)]
    for y in range(H):
        for x in range(W):
            height[y][x], part[y][x] = texel(x, y)
    top = max(max(r) for r in height)
    albedo, normal, spec = [], [], []
    for y in range(H):
        a, nrm, s = [], [], []
        for x in range(W):
            p = part[y][x]
            if p is None:
                a.append((0, 0, 0, 0))
                nrm.append((128, 128, 255, 1))
                s.append((0, 0, 0, 0))
                continue
            rgb, smooth, f0 = p
            jitter = noise(x, y, seed) * (6 if p is GALVANISED else 2)
            a.append(tuple(max(0, min(255, round(c + jitter))) for c in rgb) + (255,))

            # branch.png's two halves are separate surfaces: never take a slope across u = 0.5.
            lo, hi = (0, W - 1) if seed else ((0, W // 2 - 1) if x < W // 2 else (W // 2, W - 1))

            def at(xx, yy):
                return height[yy % H][max(lo, min(hi, xx))]

            gx = (at(x + 1, y) - at(x - 1, y)) / (2 * du)
            gy = (at(x, y + 1) - at(x, y - 1)) / (2 * dv)
            nx, ny, nz = -gx, -gy, 1.0
            k = math.sqrt(nx * nx + ny * ny + nz * nz)
            nx, ny = nx / k, ny / k
            # AO: how far the neighbourhood (3 mm across, one texel along) stands above this texel.
            reach = max(1, round(3.0 / du))
            around = max(height[yy % H][xx] for yy in (y - 1, y, y + 1)
                         for xx in range(max(0, x - reach), min(W, x + reach + 1)))
            ao = 1 - .45 * min(1.0, max(0.0, around - height[y][x]) / 5.0)
            nrm.append((round((nx * .5 + .5) * 255), round((ny * .5 + .5) * 255), round(ao * 255),
                        max(1, min(255, round(1 + 254 * height[y][x] / top)))))
            s.append((round(smooth * 255), f0, 0, 0))
        albedo.append(a)
        normal.append(nrm)
        spec.append(s)
    return albedo, normal, spec


def write(out, name, texel, du, dv, seed):
    albedo, normal, spec = maps(texel, du, dv, seed)
    png(os.path.join(out, name + ".png"), albedo)
    png(os.path.join(out, name + "_n.png"), normal)
    png(os.path.join(out, name + "_s.png"), spec)


def main():
    if len(sys.argv) != 2: sys.exit("usage: wiring_textures.py <output folder>")
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    dv = PERIOD / H
    write(out, "branch", branch_texel, 20.0 / (W // 2), dv, 0)
    for n in range(1, 7):
        write(out, "tray_%d" % n, tray_texel(n), 25.0 / W, dv, n)
    print("wrote branch and tray_1 .. tray_6 (+ _n, _s), %d x %d, to %s" % (W, H, out))


if __name__ == "__main__":
    main()
