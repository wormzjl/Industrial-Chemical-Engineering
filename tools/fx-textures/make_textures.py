"""Bakes the COMPATIBLE path's effect textures (batch 2026-11-10-custom-shader-system, plan sections 4.1 and 4.5).

The same noise and shapes as the SHADER path's fragment shaders (fx_flame.fsh, fx_plume.fsh), baked into textures that
vanilla entity render types sample under a shader pack (FxRenderTypes, FxImmediateSink):

- flame_flipbook.png: 8 frames of 64 x 128 stacked top to bottom (64 x 1024), the flame's tip at each frame's top, root
  at its bottom. Near-white albedo: the vertex colour (FxColour.flame) gives the blackbody hue. The frames loop.
- flame_flipbook_s.png: LabPBR 1.3 specular beside it: R smoothness 0, G F0 10 (dielectric), B 0, A emission
  (0 to 254 with the flame's body; 255 would mean none), so packs that read emission make the flame bloom.
- smoke.png, plume.png: four soft puffs in a 2 x 2 grid (128 x 128), white albedo lit from above; the vertex colour tints.

Python standard library only (zlib, struct). Deterministic: the same files on every run.
Usage: python -I tools/fx-textures/make_textures.py <output folder>
"""

import math
import os
import struct
import sys
import zlib


def png(path, width, height, pixels):
    """Writes RGBA 8-bit pixels (a bytes-like of width * height * 4) as a PNG."""
    raw = bytearray()
    stride = width * 4
    for y in range(height):
        raw.append(0)
        raw.extend(pixels[y * stride:(y + 1) * stride])

    def chunk(kind, data):
        body = kind + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    with open(path, "wb") as out:
        out.write(b"\x89PNG\r\n\x1a\n")
        out.write(chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0)))
        out.write(chunk(b"IDAT", zlib.compress(bytes(raw), 9)))
        out.write(chunk(b"IEND", b""))


def hash2(ix, iy, seed):
    """A value in [0, 1) for a lattice point: integer mixing, the same on every platform."""
    h = (ix * 374761393 + iy * 668265263 + seed * 2147483647) & 0xFFFFFFFF
    h = ((h ^ (h >> 13)) * 1274126177) & 0xFFFFFFFF
    h ^= h >> 16
    return h / 4294967296.0


def noise(x, y, seed):
    ix, iy = math.floor(x), math.floor(y)
    fx, fy = x - ix, y - iy
    ux, uy = fx * fx * (3 - 2 * fx), fy * fy * (3 - 2 * fy)
    a, b = hash2(ix, iy, seed), hash2(ix + 1, iy, seed)
    c, d = hash2(ix, iy + 1, seed), hash2(ix + 1, iy + 1, seed)
    return (a + (b - a) * ux) + ((c + (d - c) * ux) - (a + (b - a) * ux)) * uy


def fbm(x, y, seed, octaves=4):
    total, amplitude = 0.0, 0.5
    for _ in range(octaves):
        total += amplitude * noise(x, y, seed)
        x, y = x * 2.03 + 1.7, y * 2.03 + 9.2
        amplitude *= 0.5
    return total


def smooth(lo, hi, x):
    k = min(1.0, max(0.0, (x - lo) / (hi - lo)))
    return k * k * (3 - 2 * k)


def clamp(v):
    return min(1.0, max(0.0, v))


def byte(v):
    return int(round(clamp(v) * 255))


FRAMES, FRAME_W, FRAME_H = 8, 64, 128


def flame(folder):
    width, height = FRAME_W, FRAME_H * FRAMES
    albedo, specular = bytearray(width * height * 4), bytearray(width * height * 4)
    for f in range(FRAMES):
        # A loop: the noise's offset goes round a circle once over the eight frames.
        angle = 2 * math.pi * f / FRAMES
        ox, oy = 0.9 * math.cos(angle), 0.9 * math.sin(angle)
        for py in range(FRAME_H):
            along = 1 - (py + 0.5) / FRAME_H
            for px in range(FRAME_W):
                across = (px + 0.5) / FRAME_W * 2 - 1
                x, y = across * 1.6 + ox, along * 3.0 + oy
                warp_x, warp_y = fbm(x, y, 11), fbm(x + 5.2, y + 1.3, 12)
                n = fbm(x + 1.7 * warp_x, y + 1.7 * warp_y, 13)
                edge = abs(across) + 0.55 * (n - 0.47) + 0.25 * along
                body = 1 - smooth(0.55, 1.0, edge)
                alpha = clamp(body * (1 - 0.7 * smooth(0.75, 1.0, along)) * (0.75 + 0.5 * n))
                core = clamp((1 - along) * (1 - abs(across)) * (0.6 + 0.9 * n))
                light = 0.82 + 0.18 * core
                i = ((f * FRAME_H + py) * width + px) * 4
                albedo[i:i + 4] = bytes((byte(light), byte(light), byte(light * 0.97), byte(alpha)))
                specular[i:i + 4] = bytes((0, 10, 0, min(254, byte(body * 1.2))))
    png(os.path.join(folder, "flame_flipbook.png"), width, height, albedo)
    png(os.path.join(folder, "flame_flipbook_s.png"), width, height, specular)


def puffs(folder, name, seed, softness, shade):
    size, half = 128, 64
    pixels = bytearray(size * size * 4)
    for variant in range(4):
        cx, cy = (variant & 1) * half, (variant >> 1) * half
        for py in range(half):
            for px in range(half):
                qx, qy = (px + 0.5) / half * 2 - 1, (py + 0.5) / half * 2 - 1
                r = math.hypot(qx, qy)
                x, y = qx * 1.6 + variant * 31.0, qy * 1.6 + variant * 17.0
                n = fbm(x + 1.3 * fbm(x + 3.1, y, seed), y + 1.3 * fbm(x - 1.7, y, seed + 1), seed + 2)
                shape = 1 - smooth(softness, 1.0, r + 0.45 * (n - 0.47))
                alpha = clamp(shape * (0.7 + 0.6 * n))
                # Lit from above (image y grows downward): the top of the puff is brighter.
                light = clamp(1 - shade * (0.5 + 0.5 * qy) - 0.1 * (1 - n))
                i = ((cy + py) * size + cx + px) * 4
                pixels[i:i + 4] = bytes((byte(light), byte(light), byte(light), byte(alpha)))
    png(os.path.join(folder, name + ".png"), size, size, pixels)


def main():
    if len(sys.argv) != 2:
        print("usage: make_textures.py <output folder>")
        return 2
    folder = sys.argv[1]
    os.makedirs(folder, exist_ok=True)
    flame(folder)
    puffs(folder, "smoke", 21, 0.3, 0.35)
    puffs(folder, "plume", 31, 0.4, 0.2)
    for name in ("flame_flipbook.png", "flame_flipbook_s.png", "smoke.png", "plume.png"):
        print(name, os.path.getsize(os.path.join(folder, name)), "bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
