"""Scratch: renders the plunger pump's candidate forms (geometry/*.json from PlungerForms) as option sheets.

Run from the worktree root:
  <authoring python> -I tmp/plunger-forms/render.py
The z-buffer is the one of tools/equipment-drawings/rendering.py with a paint-like palette; nothing tracked is read or written.
"""
import json, math
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
BODY = {'PUMP_CASTING', 'PUMP_VOLUTE', 'PUMP_FINS', 'PUMP_JOINT', 'PUMP_DISC', 'PUMP_COVER', 'MACHINE', 'SHELL', 'HEAD'}
COLOURS = {'PUMP_BASE': (86, 94, 102), 'PUMP_CHANNEL': (74, 82, 90), 'STEELWORK': (96, 104, 112), 'CONCRETE': (188, 186, 178), 'SHAFT': (222, 226, 230),
           'COUPLING_GUARD': (232, 190, 44), 'GUARD': (232, 190, 44), 'FLANGE': (150, 158, 166), 'PIPE': (128, 138, 148), 'BOLT': (58, 62, 68),
           'OPENING': (40, 44, 48), 'GLASS': (170, 205, 215), 'FIGURE': (205, 150, 120), 'SHAFT_INPUT': (60, 150, 90), 'SHAFT_OUTPUT': (60, 150, 90)}
JOINT = {'PUMP_JOINT': .86, 'PUMP_DISC': .93, 'HEAD': 1.04}


def colour(material, n):
    n = np.abs(n)
    shade = .62 + .26 * n[1] + .12 * n[2] + .02 * n[0]
    if material in BODY:
        c = np.array([58, 118, 160]) * JOINT.get(material, 1)
    else:
        c = np.array(COLOURS.get(material, (198, 208, 214)))
    return np.clip(c * shade, 0, 255).astype(np.uint8)


def basis(direction):
    n = np.array(direction, float)
    n /= np.linalg.norm(n)
    r = np.cross([0, 1, 0], n)
    r /= np.linalg.norm(r)
    return np.array([r, np.cross(n, r), n])


def load(name, b):
    """The model and a 1.8 m figure standing clear of it at the view's right."""
    polys = [(np.array(p['v'], float).reshape(-1, 3), p['m']) for p in json.loads((HERE / 'geometry' / (name + '.json')).read_text())['polygons']]
    v = np.concatenate([q for q, _ in polys])
    r = b[0]
    d = np.array([-r[2], 0, r[0]])
    p = r * ((v @ r).max() + .55) + d * (v @ d).mean()
    for lo, hi in [((p[0] - .22, 0, p[2] - .22), (p[0] + .22, 1.45, p[2] + .22)), ((p[0] - .12, 1.45, p[2] - .12), (p[0] + .12, 1.8, p[2] + .12))]:
        polys += box(lo, hi, 'FIGURE')
    return polys


def box(lo, hi, material):
    (x0, y0, z0), (x1, y1, z1) = lo, hi
    c = [(x0, y0, z0), (x1, y0, z0), (x1, y1, z0), (x0, y1, z0), (x0, y0, z1), (x1, y0, z1), (x1, y1, z1), (x0, y1, z1)]
    return [(np.array([c[i] for i in f], float), material) for f in [(0, 1, 2, 3), (4, 5, 6, 7), (0, 1, 5, 4), (3, 2, 6, 7), (0, 3, 7, 4), (1, 2, 6, 5)]]


def extent(polys, b):
    q = np.concatenate([v for v, _ in polys]) @ b.T
    return q[:, :2].min(0), q[:, :2].max(0)


def render(polys, b, size, scale, ss=2):
    """An image of `size` metres at `scale` px/m: the model centred across, standing on the bottom margin."""
    lo, hi = extent(polys, b)
    iw, ih = int(size[0] * scale * ss), int(size[1] * scale * ss)
    s = scale * ss
    off = np.array([(size[0] - (hi[0] - lo[0])) / 2 - lo[0], .15 - lo[1]])
    image = np.full((ih, iw, 3), 255, np.uint8)
    depth = np.full((ih, iw), -np.inf)
    for v, m in polys:
        q = v @ b.T
        q[:, :2] = (q[:, :2] + off) * s
        q[:, 1] = ih - q[:, 1]
        n = np.cross(v[1] - v[0], v[2] - v[0])
        length = np.linalg.norm(n)
        if length < 1e-10:
            continue
        c = colour(m, n / length)
        for j in range(1, len(v) - 1):
            t = q[[0, j, j + 1]]
            x0, x1 = max(0, math.floor(t[:, 0].min())), min(iw - 1, math.ceil(t[:, 0].max()))
            y0, y1 = max(0, math.floor(t[:, 1].min())), min(ih - 1, math.ceil(t[:, 1].max()))
            if x1 < x0 or y1 < y0:
                continue
            a, bb, cc = t
            den = (bb[1] - cc[1]) * (a[0] - cc[0]) + (cc[0] - bb[0]) * (a[1] - cc[1])
            if abs(den) < 1e-8:
                continue
            yy, xx = np.ogrid[y0:y1 + 1, x0:x1 + 1]
            xx = xx + .5
            yy = yy + .5
            uu = ((bb[1] - cc[1]) * (xx - cc[0]) + (cc[0] - bb[0]) * (yy - cc[1])) / den
            ww = ((cc[1] - a[1]) * (xx - cc[0]) + (a[0] - cc[0]) * (yy - cc[1])) / den
            kk = 1 - uu - ww
            z = uu * a[2] + ww * bb[2] + kk * cc[2]
            region = depth[y0:y1 + 1, x0:x1 + 1]
            mask = (uu >= -1e-7) & (ww >= -1e-7) & (kk >= -1e-7) & (z >= region - 1e-7)
            region[mask] = z[mask]
            image[y0:y1 + 1, x0:x1 + 1][mask] = c
    return Image.fromarray(image).resize((iw // ss, ih // ss), Image.LANCZOS)


def font(size, bold=False):
    try:
        return ImageFont.truetype('C:/Windows/Fonts/' + ('arialbd.ttf' if bold else 'arial.ttf'), size)
    except OSError:
        return ImageFont.load_default()


def sheet(title, note, rows, columns, cells, filename, left=150):
    """cells[r][c]: an image. Row and column labels round the grid."""
    cw, chs = max(i.width for row in cells for i in row), [max(i.height for i in row) for row in cells]
    top = 110
    img = Image.new('RGB', (left + cw * len(columns) + 20, top + sum(chs) + 50), 'white')
    d = ImageDraw.Draw(img)
    d.text((20, 16), title, fill=(35, 59, 73), font=font(30, True))
    d.text((20, 58), note, fill=(99, 119, 131), font=font(17))
    for c, label in enumerate(columns):
        d.text((left + c * cw + 14, top - 26), label, fill=(35, 59, 73), font=font(20, True))
    y = top
    for r, label in enumerate(rows):
        d.text((16, y + 14), label, fill=(35, 59, 73), font=font(18, True))
        for c, cell in enumerate(cells[r]):
            img.paste(cell, (left + c * cw + (cw - cell.width) // 2, y + chs[r] - cell.height))
        d.line([(left, y + chs[r]), (left + cw * len(columns), y + chs[r])], fill=(205, 212, 218), width=1)
        y += chs[r]
    img.save(HERE / filename)
    print(filename, img.size)


FORMS = [('A', 'A  Monoblock'), ('B', 'B  Valve pots and bottles'), ('C', 'C  Vertical')]
VIEWS = [('liquid end side', (1, .62, -1)), ('drive end side', (-1, .62, 1)), ('side elevation', (1, 0, 0)), ('end elevation', (0, 0, -1))]


def group(names, direction, scale):
    b = basis(direction)
    models = [load(n, b) for n in names]
    spans = [extent(m, b) for m in models]
    size = (max(hi[0] - lo[0] for lo, hi in spans) + .3, max(hi[1] - lo[1] for lo, hi in spans) + .3)
    return [render(m, b, size, scale) for m in models]


if __name__ == '__main__':
    cells = [group([f + '_30' for f, _ in FORMS], d, 150) for _, d in VIEWS]
    sheet('Plunger pump: three forms at 30 m³/h', 'Scratch geometry from the kit\'s own parts; the figure is 1.8 m; one scale in each row. Body paint shown blue, bare steel bright, guard yellow.',
          [v for v, _ in VIEWS], [label for _, label in FORMS], cells, 'forms-30.png')
    sizes = ['3', '10', '30', '100']
    cells = [group(['A_' + s for s in sizes], d, 120) for _, d in VIEWS]
    sheet('Plunger pump, form A: the four models', 'Scratch geometry with its collision solids and final nozzle heights; one scale in each row; the figure is 1.8 m.',
          [v for v, _ in VIEWS], [s + ' m³/h' for s in sizes], cells, 'form-a.png', left=190)
    cells = [group([f + '_' + s for s in sizes], VIEWS[0][1], 105) for f, _ in FORMS]
    sheet('Plunger pump: the four models of each form', 'The same view and one scale in each row; the figure is 1.8 m. 3, 10, 30 and 100 m³/h at 300 rpm, relief rise 25 MPa.',
          [label for _, label in FORMS], [s + ' m³/h' for s in sizes], cells, 'forms-sizes.png', left=270)
