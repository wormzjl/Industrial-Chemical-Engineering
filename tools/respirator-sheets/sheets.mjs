// The respirator's look options (batch 2026-11-18-respirator): three worn masks on a plain head form, software rendered,
// with their item icons; and the placeholder textures the mod ships until the owner picks.
//
//   node tools/respirator-sheets/sheets.mjs <output folder>              option sheet and icon previews
//   node tools/respirator-sheets/sheets.mjs <output folder> --textures   also writes the placeholder textures into src/main/resources
//
// No people: the mask sits on a grey head form (owner rule, 2026-11-18).
import fs from 'node:fs';
import path from 'node:path';
import { Canvas, text } from './png.mjs';

// ---------------------------------------------------------------------------------------------------------------------
// A small z-buffered renderer of boxes and cylinders, orthographic, flat Lambert shading, outlines between parts.

function rotate(p, yaw, pitch) {
  const cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
  const x = p[0] * cy + p[2] * sy;
  const z = -p[0] * sy + p[2] * cy;
  const y = p[1] * cp - z * sp;
  const z2 = p[1] * sp + z * cp;
  return [x, y, z2];
}

const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const norm = (a) => { const l = Math.hypot(...a) || 1; return [a[0] / l, a[1] / l, a[2] / l]; };
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];

class Scene {
  constructor() { this.quads = []; this.part = 0; }
  quad(a, b, c, d, colour, part) { this.quads.push({ v: [a, b, c, d], colour, part }); }
  box(x0, y0, z0, x1, y1, z1, colour) {
    const p = ++this.part;
    const P = (x, y, z) => [x, y, z];
    this.quad(P(x0, y0, z1), P(x1, y0, z1), P(x1, y1, z1), P(x0, y1, z1), colour, p); // +z
    this.quad(P(x1, y0, z0), P(x0, y0, z0), P(x0, y1, z0), P(x1, y1, z0), colour, p); // -z
    this.quad(P(x1, y0, z1), P(x1, y0, z0), P(x1, y1, z0), P(x1, y1, z1), colour, p); // +x
    this.quad(P(x0, y0, z0), P(x0, y0, z1), P(x0, y1, z1), P(x0, y1, z0), colour, p); // -x
    this.quad(P(x0, y1, z1), P(x1, y1, z1), P(x1, y1, z0), P(x0, y1, z0), colour, p); // +y
    this.quad(P(x0, y0, z0), P(x1, y0, z0), P(x1, y0, z1), P(x0, y0, z1), colour, p); // -y
  }
  /** A cylinder from {@code base} along {@code axis} (unit), with {@code n} sides. */
  cylinder(base, axis, radius, length, colour, n = 20, capColour = colour) {
    const p = ++this.part;
    axis = norm(axis);
    const helper = Math.abs(axis[1]) < 0.9 ? [0, 1, 0] : [1, 0, 0];
    const u = norm(cross(axis, helper));
    const v = cross(axis, u);
    const top = base.map((b, i) => b + axis[i] * length);
    const ring = (c, k) => {
      const t = (2 * Math.PI * k) / n;
      return c.map((ci, i) => ci + radius * (Math.cos(t) * u[i] + Math.sin(t) * v[i]));
    };
    for (let k = 0; k < n; k++) {
      const a = ring(base, k), b = ring(base, k + 1), c = ring(top, k + 1), d = ring(top, k);
      this.quad(a, b, c, d, colour, p);
      this.quad(top, d, c, top, capColour, p);
      this.quad(base, b, a, base, colour, p);
    }
  }
}

function render(scene, w, h, yaw, pitch, scale, centre, background) {
  const img = new Canvas(w, h, background);
  const depth = new Float64Array(w * h).fill(-Infinity);
  const ids = new Int32Array(w * h).fill(0);
  const light = norm([-0.5, 0.8, 0.6]);
  for (const q of scene.quads) {
    const r = q.v.map((p) => rotate(sub(p, centre), yaw, pitch));
    let n = cross(sub(r[1], r[0]), sub(r[2], r[0]));
    if (Math.hypot(...n) < 1e-12) n = cross(sub(r[2], r[0]), sub(r[3], r[0]));
    n = norm(n);
    if (n[2] <= 0) continue;
    // Light in view space: the light turns with the camera so every view reads alike.
    const shade = 0.42 + 0.58 * Math.max(0, dot(n, light));
    const colour = q.colour.slice(0, 3).map((c) => Math.min(255, Math.round(c * shade)));
    const s = r.map((p) => [w / 2 + p[0] * scale, h / 2 - p[1] * scale, p[2]]);
    for (const tri of [[s[0], s[1], s[2]], [s[0], s[2], s[3]]]) raster(tri, colour, q.part, img, depth, ids, w, h);
  }
  // Outlines where the part changes, and around the silhouette.
  const out = new Canvas(w, h, background);
  out.px.set(img.px);
  for (let y = 1; y < h - 1; y++) for (let x = 1; x < w - 1; x++) {
    const id = ids[y * w + x];
    const edge = [ids[y * w + x - 1], ids[y * w + x + 1], ids[(y - 1) * w + x], ids[(y + 1) * w + x]].some((o) => o !== id);
    if (edge && id !== 0) out.set(x, y, [25, 25, 30]);
  }
  return out;
}

function raster(t, colour, part, img, depth, ids, w, h) {
  const [a, b, c] = t;
  const minX = Math.max(0, Math.floor(Math.min(a[0], b[0], c[0])));
  const maxX = Math.min(w - 1, Math.ceil(Math.max(a[0], b[0], c[0])));
  const minY = Math.max(0, Math.floor(Math.min(a[1], b[1], c[1])));
  const maxY = Math.min(h - 1, Math.ceil(Math.max(a[1], b[1], c[1])));
  const area = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
  if (Math.abs(area) < 1e-9) return;
  for (let y = minY; y <= maxY; y++) for (let x = minX; x <= maxX; x++) {
    const px = x + 0.5, py = y + 0.5;
    const w0 = ((b[0] - px) * (c[1] - py) - (b[1] - py) * (c[0] - px)) / area;
    const w1 = ((c[0] - px) * (a[1] - py) - (c[1] - py) * (a[0] - px)) / area;
    const w2 = 1 - w0 - w1;
    if (w0 < 0 || w1 < 0 || w2 < 0) continue;
    const z = w0 * a[2] + w1 * b[2] + w2 * c[2];
    const i = y * w + x;
    if (z <= depth[i]) continue;
    depth[i] = z;
    ids[i] = part;
    img.set(x, y, colour);
  }
}

// ---------------------------------------------------------------------------------------------------------------------
// The head form and the three masks. Units: the Minecraft head's pixels, 8 x 8 x 8, face towards +z.

const C = {
  form: [188, 186, 180],
  rubber: [52, 54, 60],
  grey: [92, 94, 100],
  lens: [150, 196, 220],
  strap: [70, 60, 50],
  cartridge: [178, 150, 92],
  band: [236, 236, 230],
  metal: [150, 152, 158],
};

function headForm(s) {
  s.box(-4, 0, -4, 4, 8, 4, C.form);
  // A neck stub so the form stands, no shoulders.
  s.box(-2.2, -2.5, -2.2, 2.2, 0, 2.2, C.form);
}

function straps(s, y, t = 0.45) {
  s.box(4, y, -4.15, 4.15, y + t, 4, C.strap);
  s.box(-4.15, y, -4.15, -4, y + t, 4, C.strap);
  s.box(-4.15, y, -4.15, 4.15, y + t, -4, C.strap);
}

const OPTIONS = [
  {
    key: 'A', name: 'HALF MASK, TWIN CARTRIDGES',
    build(s) {
      headForm(s);
      s.box(-2.6, 0.5, 4, 2.6, 3.4, 4.9, C.rubber);
      s.box(-1.3, 3.4, 4, 1.3, 4.3, 4.6, C.rubber);
      s.cylinder([0, 1.7, 4.9], [0, 0, 1], 0.75, 0.6, C.grey);
      for (const side of [-1, 1]) {
        const axis = norm([side * 0.75, 0, 0.66]);
        s.cylinder([side * 2.3, 1.8, 4.4], axis, 1.35, 0.85, C.cartridge, 22, C.band);
      }
      straps(s, 2.0);
      straps(s, 5.4);
    },
  },
  {
    key: 'B', name: 'FULL FACE, ONE FRONT CANISTER',
    build(s) {
      headForm(s);
      s.box(-3.7, 0.3, 4, 3.7, 7.1, 4.45, C.rubber);
      s.box(-3.0, 3.5, 4.45, 3.0, 6.5, 4.75, C.lens);
      s.box(-1.9, 0.6, 4.45, 1.9, 3.3, 5.3, C.grey);
      s.cylinder([0, 1.6, 5.3], [0, 0, 1], 1.45, 2.4, C.cartridge, 24, C.cartridge);
      s.cylinder([0, 1.6, 7.0], [0, 0, 1], 1.5, 0.35, C.band, 24, C.band);
      s.cylinder([0, 1.6, 7.7], [0, 0, 1], 0.5, 0.15, C.metal, 16, C.metal);
      straps(s, 3.9);
      s.box(-0.4, 8, -3.2, 0.4, 8.2, 3.6, C.strap);
    },
  },
  {
    key: 'C', name: 'FULL FACE, TWIN CARTRIDGES',
    build(s) {
      headForm(s);
      s.box(-3.7, 0.3, 4, 3.7, 7.1, 4.45, C.rubber);
      s.box(-3.0, 3.5, 4.45, 3.0, 6.5, 4.75, C.lens);
      s.box(-1.5, 0.6, 4.45, 1.5, 3.1, 5.0, C.grey);
      s.cylinder([0, 1.6, 5.0], [0, 0, 1], 0.7, 0.4, C.metal, 16, C.metal);
      for (const side of [-1, 1]) {
        const axis = norm([side * 0.55, -0.15, 0.82]);
        s.cylinder([side * 2.4, 1.5, 4.6], axis, 1.25, 1.0, C.cartridge, 22, C.band);
      }
      straps(s, 3.9);
      s.box(-0.4, 8, -3.2, 0.4, 8.2, 3.6, C.strap);
    },
  },
];

// ---------------------------------------------------------------------------------------------------------------------
// Item icons, 16 x 16 pixel art.

function ellipse(c, cx, cy, rx, ry, colour) {
  for (let y = 0; y < c.h; y++) for (let x = 0; x < c.w; x++) {
    const dx = (x + 0.5 - cx) / rx, dy = (y + 0.5 - cy) / ry;
    if (dx * dx + dy * dy <= 1) c.set(x, y, colour);
  }
}

/** Darkens the pixels on the edge of the drawn shape: the vanilla icon outline. */
function outline(c) {
  const copy = new Canvas(c.w, c.h);
  copy.px.set(c.px);
  for (let y = 0; y < c.h; y++) for (let x = 0; x < c.w; x++) {
    const p = copy.get(x, y);
    if (p[3] === 0) continue;
    const edge = [[1, 0], [-1, 0], [0, 1], [0, -1]].some(([dx, dy]) => {
      const nx = x + dx, ny = y + dy;
      return nx < 0 || ny < 0 || nx >= c.w || ny >= c.h || copy.get(nx, ny)[3] === 0;
    });
    if (edge) c.set(x, y, p.slice(0, 3).map((v) => Math.round(v * 0.45)));
  }
  return c;
}

const ICONS = {
  A: {
    respirator() {
      const c = new Canvas(16, 16);
      c.rect(2, 4, 12, 1, C.strap);
      ellipse(c, 8, 9, 4.5, 4, C.rubber);
      ellipse(c, 8, 10, 1.4, 1.4, C.grey);
      ellipse(c, 3.5, 11, 2.6, 2.6, C.cartridge);
      ellipse(c, 12.5, 11, 2.6, 2.6, C.cartridge);
      c.set(3, 11, C.band); c.set(12, 11, C.band);
      return outline(c);
    },
    filter() {
      const c = new Canvas(16, 16);
      ellipse(c, 8, 8, 6, 6, C.cartridge);
      ellipse(c, 8, 8, 4.2, 4.2, C.band);
      ellipse(c, 8, 8, 2.2, 2.2, C.cartridge);
      ellipse(c, 8, 8, 0.9, 0.9, C.metal);
      return outline(c);
    },
  },
  B: {
    respirator() {
      const c = new Canvas(16, 16);
      ellipse(c, 8, 7.5, 6, 7, C.rubber);
      c.rect(4, 3, 8, 4, C.lens);
      c.rect(5, 3, 2, 1, [220, 240, 250]);
      c.rect(6, 8, 4, 2, C.grey);
      c.rect(5, 10, 6, 5, C.cartridge);
      c.rect(5, 13, 6, 1, C.band);
      return outline(c);
    },
    filter() {
      const c = new Canvas(16, 16);
      c.rect(4, 3, 8, 11, C.cartridge);
      c.rect(4, 10, 8, 2, C.band);
      c.rect(6, 1, 4, 2, C.metal);
      c.rect(5, 3, 1, 11, [205, 178, 120]);
      return outline(c);
    },
  },
  C: {
    respirator() {
      const c = new Canvas(16, 16);
      ellipse(c, 8, 7, 5.5, 6.5, C.rubber);
      c.rect(4, 3, 8, 4, C.lens);
      c.rect(5, 3, 2, 1, [220, 240, 250]);
      ellipse(c, 8, 10, 1.2, 1.2, C.metal);
      ellipse(c, 3.2, 12, 2.6, 2.6, C.cartridge);
      ellipse(c, 12.8, 12, 2.6, 2.6, C.cartridge);
      c.set(3, 12, C.band); c.set(12, 12, C.band);
      return outline(c);
    },
    filter() {
      const c = new Canvas(16, 16);
      ellipse(c, 8, 9, 6, 5.5, C.cartridge);
      ellipse(c, 8, 9, 4, 3.6, C.band);
      ellipse(c, 8, 9, 2, 1.8, C.cartridge);
      c.rect(6, 2, 4, 3, C.metal);
      return outline(c);
    },
  },
};

// ---------------------------------------------------------------------------------------------------------------------
// The worn texture: the outer armour layer, 64 x 32, head box at (0, 0): front face (8..16, 8..16). Placeholder of
// option B, flat on the head until the owner's pick is built as a model.

function wornTexture() {
  const c = new Canvas(64, 32);
  const face = (x, y, colour) => c.set(8 + x, 8 + y, colour);
  for (let y = 1; y < 8; y++) for (let x = 0; x < 8; x++) face(x, y, C.rubber);
  for (let x = 1; x < 7; x++) for (let y = 1; y < 4; y++) face(x, y, C.lens);
  face(1, 1, [220, 240, 250]);
  face(2, 1, [220, 240, 250]);
  for (let x = 2; x < 6; x++) for (let y = 4; y < 6; y++) face(x, y, C.grey);
  for (let x = 3; x < 5; x++) for (let y = 5; y < 8; y++) face(x, y, C.cartridge);
  face(3, 6, C.band); face(4, 6, C.band);
  // The harness strap round the sides and the back, at the eyes' height.
  for (let x = 0; x < 8; x++) {
    c.set(0 + x, 10, C.strap); // right side
    c.set(16 + x, 10, C.strap); // left side
    c.set(24 + x, 10, C.strap); // back
  }
  return c;
}

// ---------------------------------------------------------------------------------------------------------------------

const out = process.argv[2];
if (!out) {
  console.error('usage: node sheets.mjs <output folder> [--textures]');
  process.exit(2);
}
fs.mkdirSync(out, { recursive: true });

const PANEL_W = 400, VIEW_H = 330, ICON_H = 190, HEAD = 22;
const BG = [236, 238, 240, 255];
const sheet = new Canvas(PANEL_W * 3, 60 + VIEW_H * 2 + ICON_H, [250, 250, 250, 255]);
text(sheet, 16, 16, 'RESPIRATOR LOOK OPTIONS - PICK ONE (A, B OR C)', [30, 30, 40], 3);
OPTIONS.forEach((option, i) => {
  const x0 = i * PANEL_W;
  const s = new Scene();
  option.build(s);
  const centre = [0, 3.4, 0.5];
  const iso = render(s, PANEL_W - 10, VIEW_H - 40, -0.65, 0.38, HEAD, centre, BG);
  const side = render(s, PANEL_W - 10, VIEW_H - 40, -Math.PI / 2, 0.08, HEAD, centre, BG);
  text(sheet, x0 + 10, 60, option.key + '  ' + option.name, [30, 30, 40], 2);
  sheet.blit(iso, x0 + 5, 84);
  text(sheet, x0 + 10, 84 + VIEW_H - 36, 'ISO, FRONT LEFT', [90, 90, 100], 2);
  sheet.blit(side, x0 + 5, 84 + VIEW_H);
  text(sheet, x0 + 10, 84 + 2 * VIEW_H - 36, 'SIDE', [90, 90, 100], 2);
  const resp = ICONS[option.key].respirator();
  const filt = ICONS[option.key].filter();
  const iy = 70 + 2 * VIEW_H;
  sheet.rect(x0 + 20, iy + 20, 16 * 8 + 8, 16 * 8 + 8, [198, 198, 198, 255]);
  sheet.blit(resp, x0 + 24, iy + 24, 8);
  sheet.rect(x0 + 200, iy + 20, 16 * 8 + 8, 16 * 8 + 8, [198, 198, 198, 255]);
  sheet.blit(filt, x0 + 204, iy + 24, 8);
  text(sheet, x0 + 24, iy + 166, 'RESPIRATOR', [60, 60, 70], 2);
  text(sheet, x0 + 204, iy + 166, 'FILTER', [60, 60, 70], 2);
  resp.save(path.join(out, `icon_respirator_${option.key}.png`));
  filt.save(path.join(out, `icon_filter_${option.key}.png`));
  iso.save(path.join(out, `worn_${option.key}_iso.png`));
});
sheet.save(path.join(out, 'respirator_options.png'));
console.log('wrote', path.join(out, 'respirator_options.png'));

if (process.argv.includes('--textures')) {
  const assets = 'src/main/resources/assets/icheme/textures';
  fs.mkdirSync(path.join(assets, 'models/armor'), { recursive: true });
  ICONS.B.respirator().save(path.join(assets, 'item/respirator.png'));
  ICONS.B.filter().save(path.join(assets, 'item/respirator_filter.png'));
  wornTexture().save(path.join(assets, 'models/armor/respirator_layer_1.png'));
  const preview = new Canvas(64 * 6, 32 * 6, [255, 255, 255, 255]);
  preview.blit(wornTexture(), 0, 0, 6);
  preview.save(path.join(out, 'placeholder_worn_texture_x6.png'));
  console.log('wrote the placeholder textures (option B)');
}
