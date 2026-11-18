// The respirator's worn model, option C (full face, twin cartridges; owner, 2026-11-18): one geometry, from which this
// script writes the game's model code, its texture, and a sheet rendered the way the game draws it.
//
//   node tools/respirator-sheets/model.mjs <output folder>            render the sheet of the built model only
//   node tools/respirator-sheets/model.mjs <output folder> --write    also write RespiratorGeometry.java and the texture
//
// Units are model pixels in the head part's space (Minecraft's ModelPart: y down, the face towards -z, the head box
// -4..4, -8..0, -4..4). Every cube has whole-pixel sizes so its box UV maps texel for texel. Run from the worktree root.
// No people: the render puts the mask on a grey head form (owner rule, 2026-11-18).
import fs from 'node:fs';
import path from 'node:path';
import { Canvas, text } from './png.mjs';

const TEX_W = 64, TEX_H = 64;

const M = {
  rubber: [52, 54, 60],
  grey: [92, 94, 100],
  lens: [150, 196, 220],
  strap: [92, 72, 54],
  cartridge: [178, 150, 92],
  band: [236, 236, 230],
  metal: [150, 152, 158],
};

// Parts: name, pivot, rotation (x, y, z radians, applied as ModelPart does: Z, then Y, then X), cubes.
// A cube: origin [x, y, z], size [w, h, d] (whole pixels), material, and how its faces are painted.
const TILT = 0.6; // the cartridges turn 34° outward from straight ahead
const DROOP = -0.15; // and tip 9° down
function cartridge(side) {
  return {
    name: side > 0 ? 'cartridge_left' : 'cartridge_right',
    pivot: [side * 2.4, -1.9, -4.7],
    rotation: [DROOP, -side * TILT, 0],
    // A round cartridge from two crossed boxes (a twelve-sided outline), with its connector to the mask.
    cubes: [
      { origin: [-1.5, -1, -2], size: [3, 2, 2], material: 'cartridge', paint: 'cartridge_hub' },
      { origin: [-1, -1.5, -1.9], size: [2, 3, 2], material: 'cartridge', paint: 'cartridge_cross' }, // 0.1 px behind: no shared face
      { origin: [-0.5, -0.5, 0], size: [1, 1, 1], material: 'metal' },
    ],
  };
}

export const PARTS = [
  {
    name: 'mask', pivot: [0, 0, 0], rotation: [0, 0, 0],
    cubes: [
      // The face seal: rubber, narrower than the head so its sides never meet the skin.
      { origin: [-3.5, -7.5, -4.6], size: [7, 7, 1], material: 'rubber' },
      // The visor, standing 0.4 px proud of the seal.
      { origin: [-3, -6.5, -5], size: [6, 3, 1], material: 'lens', paint: 'lens' },
      // The nose cup and the speech diaphragm.
      { origin: [-1.5, -3.5, -5.4], size: [3, 3, 1], material: 'grey' },
      { origin: [-1, -3, -6], size: [2, 2, 1], material: 'metal' },
      // The head harness: a band round the sides and back at the eyes, and one over the crown.
      { origin: [3.75, -4.5, -4], size: [1, 1, 8], material: 'strap' },
      { origin: [-4.75, -4.5, -4], size: [1, 1, 8], material: 'strap' },
      { origin: [-5, -4.5, 3.75], size: [10, 1, 1], material: 'strap' },
      { origin: [-0.5, -8.75, -3.75], size: [1, 1, 7], material: 'strap' },
    ],
  },
  cartridge(1),
  cartridge(-1),
];

// ---------------------------------------------------------------------------------------------------------------------
// Texture atlas: each cube's box UV block (2(d + w) wide, d + h high), packed on shelves.

function pack(parts) {
  let x = 0, y = 0, shelf = 0;
  for (const part of parts) for (const cube of part.cubes) {
    const [w, h, d] = cube.size;
    const bw = 2 * (d + w), bh = d + h;
    if (x + bw > TEX_W) { x = 0; y += shelf; shelf = 0; }
    if (y + bh > TEX_H) throw new Error('the texture is full');
    cube.uv = [x, y];
    x += bw;
    shelf = Math.max(shelf, bh);
  }
}

/** The face regions of a cube's box UV block, as ModelPart.Cube lays them out. */
function faces(cube) {
  const [u, v] = cube.uv;
  const [w, h, d] = cube.size;
  return {
    down: [u + d + w, v, w, d], up: [u + d, v, w, d],
    west: [u, v + d, d, h], north: [u + d, v + d, w, h], east: [u + d + w, v + d, d, h], south: [u + d + w + d, v + d, w, h],
  };
}

const shade = (c, f) => c.map((v) => Math.max(0, Math.min(255, Math.round(v * f))));

function paint(parts) {
  const tex = new Canvas(TEX_W, TEX_H);
  for (const part of parts) for (const cube of part.cubes) {
    const base = M[cube.material];
    for (const [name, [x0, y0, fw, fh]] of Object.entries(faces(cube))) {
      for (let y = 0; y < fh; y++) for (let x = 0; x < fw; x++) {
        const edge = x === 0 || y === 0 || x === fw - 1 || y === fh - 1;
        let c = edge && fw > 2 && fh > 2 ? shade(base, 0.82) : base;
        if (cube.paint === 'lens' && name === 'north') {
          c = y === 0 && (x === 1 || x === 2) ? [222, 242, 252] : y === fh - 1 ? shade(M.lens, 0.85) : M.lens;
        }
        if (cube.paint && cube.paint.startsWith('cartridge')) {
          // The outer pixel of the depth is the white label band; the face away from the mask is tan with a steel hub.
          if (name === 'north') c = cube.paint === 'cartridge_hub' && x === 1 ? M.metal : M.cartridge;
          else if (name === 'west' || name === 'east') c = x === (name === 'west' ? 0 : fw - 1) ? M.band : M.cartridge;
          else if (name === 'up' || name === 'down') c = y === (name === 'up' ? 0 : fh - 1) ? M.band : M.cartridge;
        }
        tex.set(x0 + x, y0 + y, c);
      }
    }
  }
  return tex;
}

// ---------------------------------------------------------------------------------------------------------------------
// The game's code: RespiratorGeometry.java, the parts as ModelPart definitions.

const num = (v) => (Number.isInteger(v) ? v.toFixed(1) : String(v)) + 'F';

function java(parts) {
  const lines = [];
  lines.push('package com.wormzjl.icheme.client;', '');
  lines.push('import net.minecraft.client.model.geom.PartPose;', 'import net.minecraft.client.model.geom.builders.CubeListBuilder;',
    'import net.minecraft.client.model.geom.builders.PartDefinition;', '');
  lines.push('/**', ' * The respirator\'s worn model, option C (full face, twin cartridges; batch 2026-11-18-respirator). Generated by',
    ' * {@code tools/respirator-sheets/model.mjs --write} with its texture {@code textures/models/armor/respirator_layer_1.png};',
    ' * change the geometry there and write both again, never by hand.', ' */');
  lines.push('public final class RespiratorGeometry {');
  lines.push(`    public static final int TEXTURE_WIDTH = ${TEX_W};`, `    public static final int TEXTURE_HEIGHT = ${TEX_H};`);
  lines.push(`    /** The parts' names, children of the head. */`);
  lines.push(`    public static final java.util.List<String> PARTS = java.util.List.of(${parts.map((p) => JSON.stringify(p.name)).join(', ')});`, '');
  lines.push('    private RespiratorGeometry() {', '    }', '');
  lines.push('    /** Adds the mask\'s parts to the head part. */');
  lines.push('    public static void build(PartDefinition head) {');
  for (const part of parts) {
    const cubes = part.cubes.map((c) => `.texOffs(${c.uv[0]}, ${c.uv[1]}).addBox(${c.origin.map(num).join(', ')}, ${c.size.map(num).join(', ')})`).join('\n                ');
    const [px, py, pz] = part.pivot, [rx, ry, rz] = part.rotation;
    const pose = rx || ry || rz ? `PartPose.offsetAndRotation(${[px, py, pz, rx, ry, rz].map(num).join(', ')})` : `PartPose.offset(${[px, py, pz].map(num).join(', ')})`;
    lines.push(`        head.addOrReplaceChild(${JSON.stringify(part.name)}, CubeListBuilder.create()`, `                ${cubes},`, `                ${pose});`);
  }
  lines.push('    }', '}', '');
  return lines.join('\r\n');
}

// ---------------------------------------------------------------------------------------------------------------------
// Render as the game draws it: ModelPart's pose (translate to the pivot, rotate Z, Y, X), box faces with their box UV,
// then the entity renderer's flip (x and y negated), orthographic, textured, flat shading.

function rotZYX(p, [rx, ry, rz]) {
  let [x, y, z] = p;
  // v' = Rz · Ry · Rx · v (JOML rotationZYX).
  [y, z] = [y * Math.cos(rx) - z * Math.sin(rx), y * Math.sin(rx) + z * Math.cos(rx)];
  [x, z] = [x * Math.cos(ry) + z * Math.sin(ry), -x * Math.sin(ry) + z * Math.cos(ry)];
  [x, y] = [x * Math.cos(rz) - y * Math.sin(rz), x * Math.sin(rz) + y * Math.cos(rz)];
  return [x, y, z];
}

/** The quads of a cube in head space, each with its four texel UVs, as ModelPart.Cube builds them. */
function cubeQuads(cube, part, colour = null) {
  const [x0, y0, z0] = cube.origin, [w, h, d] = cube.size;
  const x1 = x0 + w, y1 = y0 + h, z1 = z0 + d;
  const V = [[x0, y0, z0], [x1, y0, z0], [x1, y1, z0], [x0, y1, z0], [x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1]]
    .map((p) => rotZYX(p, part.rotation)).map((p) => [p[0] + part.pivot[0], p[1] + part.pivot[1], p[2] + part.pivot[2]]);
  const quads = [];
  const add = (idx, ua, va, ub, vb) => quads.push({ v: idx.map((i) => V[i]), uv: [[ub, va], [ua, va], [ua, vb], [ub, vb]], colour });
  if (cube.uv) {
    const [u, v] = cube.uv;
    const u0 = u, u1 = u + d, u2 = u + d + w, u3 = u + d + w + w, u4 = u + d + w + d, u5 = u + d + w + d + w;
    const v0 = v, v1 = v + d, v2 = v + d + h;
    add([5, 4, 0, 1], u1, v0, u2, v1); // down
    add([2, 3, 7, 6], u2, v1, u3, v0); // up
    add([0, 4, 7, 3], u0, v1, u1, v2); // west
    add([1, 0, 3, 2], u1, v1, u2, v2); // north
    add([5, 1, 2, 6], u2, v1, u4, v2); // east
    add([4, 5, 6, 7], u4, v1, u5, v2); // south
  } else {
    for (const idx of [[5, 4, 0, 1], [2, 3, 7, 6], [0, 4, 7, 3], [1, 0, 3, 2], [5, 1, 2, 6], [4, 5, 6, 7]]) quads.push({ v: idx.map((i) => V[i]), colour });
  }
  return quads;
}

function render(quads, tex, w, h, yaw, pitch, scale, centre, background) {
  const img = new Canvas(w, h, background);
  const depth = new Float64Array(w * h).fill(-Infinity);
  const light = [-0.35, 0.55, 0.75].map((c, _, a) => c / Math.hypot(...a));
  for (const q of quads) {
    // The entity renderer's flip, then the camera.
    const r = q.v.map(([x, y, z]) => {
      let p = [-x - centre[0], -y - centre[1], z - centre[2]];
      const cy = Math.cos(yaw), sy = Math.sin(yaw), cp = Math.cos(pitch), sp = Math.sin(pitch);
      const X = p[0] * cy + p[2] * sy, Z = -p[0] * sy + p[2] * cy;
      return [X, p[1] * cp - Z * sp, p[1] * sp + Z * cp];
    });
    const e1 = r[1].map((c, i) => c - r[0][i]), e2 = r[2].map((c, i) => c - r[0][i]);
    let n = [e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]];
    const l = Math.hypot(...n);
    if (l < 1e-12) continue;
    n = n.map((c) => c / l);
    // The flip (x and y negated) is a half turn, so the winding holds: a face towards the viewer has n.z > 0.
    if (n[2] <= 0) continue;
    const f = 0.45 + 0.55 * Math.max(0, n[0] * light[0] + n[1] * light[1] + n[2] * light[2]);
    const s = r.map((p) => [w / 2 + p[0] * scale, h / 2 - p[1] * scale, p[2]]);
    for (const [a, b, c] of [[0, 1, 2], [0, 2, 3]]) tri([s[a], s[b], s[c]], q.uv ? [q.uv[a], q.uv[b], q.uv[c]] : null, q.colour, f, tex, img, depth, w, h);
  }
  return img;
}

function tri(t, uv, colour, f, tex, img, depth, w, h) {
  const [a, b, c] = t;
  const area = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
  if (Math.abs(area) < 1e-9) return;
  const minX = Math.max(0, Math.floor(Math.min(a[0], b[0], c[0]))), maxX = Math.min(w - 1, Math.ceil(Math.max(a[0], b[0], c[0])));
  const minY = Math.max(0, Math.floor(Math.min(a[1], b[1], c[1]))), maxY = Math.min(h - 1, Math.ceil(Math.max(a[1], b[1], c[1])));
  for (let y = minY; y <= maxY; y++) for (let x = minX; x <= maxX; x++) {
    const px = x + 0.5, py = y + 0.5;
    const w0 = ((b[0] - px) * (c[1] - py) - (b[1] - py) * (c[0] - px)) / area;
    const w1 = ((c[0] - px) * (a[1] - py) - (c[1] - py) * (a[0] - px)) / area;
    const w2 = 1 - w0 - w1;
    if (w0 < 0 || w1 < 0 || w2 < 0) continue;
    const z = w0 * a[2] + w1 * b[2] + w2 * c[2];
    const i = y * w + x;
    if (z <= depth[i]) continue;
    let col = colour;
    if (uv) {
      const tu = Math.min(TEX_W - 1, Math.max(0, Math.floor(w0 * uv[0][0] + w1 * uv[1][0] + w2 * uv[2][0] - 1e-6)));
      const tv = Math.min(TEX_H - 1, Math.max(0, Math.floor(w0 * uv[0][1] + w1 * uv[1][1] + w2 * uv[2][1] - 1e-6)));
      const texel = tex.get(tu, tv);
      if (texel[3] < 128) continue; // cutout, as armorCutoutNoCull
      col = texel;
    }
    depth[i] = z;
    img.set(x, y, shade(col.slice(0, 3), f));
  }
}

// ---------------------------------------------------------------------------------------------------------------------

const out = process.argv[2];
if (!out) {
  console.error('usage: node model.mjs <output folder> [--write]');
  process.exit(2);
}
fs.mkdirSync(out, { recursive: true });
pack(PARTS);
const tex = paint(PARTS);

const FORM = [188, 186, 180];
const form = { pivot: [0, 0, 0], rotation: [0, 0, 0] };
const quads = [
  ...cubeQuads({ origin: [-4, -8, -4], size: [8, 8, 8] }, form, FORM),
  ...cubeQuads({ origin: [-2.2, 0, -2.2], size: [4.4, 2.5, 4.4] }, form, FORM),
  ...PARTS.flatMap((part) => part.cubes.flatMap((cube) => cubeQuads(cube, part))),
];

// The item icons, 16 x 16 with the vanilla dark outline.
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
// Drawn as pixel art from the built model's front view, in the texture's colours (owner, 2026-11-18, on a 16 px render of
// the model: "it's even worse"): seal, visor with its highlight, nose cup and diaphragm, the harness band at the sides,
// the twin cartridges low at the sides with their white band outward. The filter: one cartridge, face and white band.
const PIX = {
  s: M.strap, r: M.rubber, l: M.lens, h: [222, 242, 252], d: shade(M.lens, 0.8), g: M.grey, m: M.metal,
  t: M.cartridge, w: M.band, T: shade(M.cartridge, 0.75),
};
function pixelArt(rows) {
  const c = new Canvas(16, 16);
  rows.forEach((row, y) => [...row].forEach((ch, x) => { if (PIX[ch]) c.set(x, y, PIX[ch]); }));
  return outline(c);
}
// The model's front view (the sheet's FRONT): a thin seal round a large visor, the grey nose cup with its diaphragm, the
// harness band out at the sides, and the two square cartridges with their steel hub bar and the white band towards the cup.
const respiratorIcon = pixelArt([
  '................',
  '.......ss.......',
  '...rrrrrrrrrr...',
  '...rlhhlllllr...',
  '...rllllllllr...',
  '...rllllllllr...',
  '...rddddddddr...',
  'ssrrrrrrrrrrrrss',
  '.tmtwrggggrwtmt.',
  'TtmtwrgmmgrwtmtT',
  'TtmtwrgmmgrwtmtT',
  'TtmtwrggggrwtmtT',
  '.tttwrrrrrrwttt.',
  '................',
  '................',
  '................',
]);
// One cartridge as the model draws it, seen a little from the side: the square face with its hub bar, the white band
// and the tan depth behind it, the connector at the back.
const filterIcon = pixelArt([
  '................',
  '................',
  '....ttttttt.....',
  '...ttttmtttt....',
  '...ttttmttttw...',
  '...ttttmttttwT..',
  '...ttttmttttwT..',
  '...ttttmttttwT..',
  '...ttttmttttwT..',
  '...ttttmttttwT..',
  '....tttttttwwT..',
  '.....wwwwwwwTT..',
  '......TTTTTTTm..',
  '............mm..',
  '................',
  '................',
]);

const VIEW_W = 400, VIEW_H = 330, SCALE = 22, BG = [236, 238, 240, 255];
const centre = [0, 3.6, -0.6];
const views = [
  ['FRONT', Math.PI, 0.0001],
  ['ISO, FRONT LEFT', Math.PI + 0.65, 0.42],
  ['SIDE', Math.PI / 2, 0.08],
  ['BACK', 0.0001, 0.2],
];
const sheet = new Canvas(VIEW_W * 4, 60 + VIEW_H + 40 + 6 * TEX_H + 20, [250, 250, 250, 255]);
text(sheet, 16, 16, 'RESPIRATOR, OPTION C AS BUILT (THE GAME MODEL AND TEXTURE)', [30, 30, 40], 3);
views.forEach(([label, yaw, pitch], i) => {
  const img = render(quads, tex, VIEW_W - 10, VIEW_H - 40, yaw, pitch, SCALE, centre, BG);
  sheet.blit(img, i * VIEW_W + 5, 60);
  text(sheet, i * VIEW_W + 10, 60 + VIEW_H - 34, label, [90, 90, 100], 2);
  img.save(path.join(out, `built_${label.split(',')[0].toLowerCase()}.png`));
});
const ty = 60 + VIEW_H + 10;
text(sheet, 16, ty, 'TEXTURE 64 X 64 (X6)', [90, 90, 100], 2);
sheet.rect(16, ty + 22, TEX_W * 6, TEX_H * 6, [210, 210, 210, 255]);
sheet.blit(tex, 16, ty + 22, 6);
for (const [i, [label, icon]] of [['ICON', respiratorIcon], ['FILTER ICON', filterIcon]].entries()) {
  text(sheet, 460 + i * 200, ty, label, [90, 90, 100], 2);
  sheet.rect(460 + i * 200, ty + 22, 16 * 8 + 8, 16 * 8 + 8, [198, 198, 198, 255]);
  sheet.blit(icon, 464 + i * 200, ty + 26, 8);
  sheet.rect(460 + i * 200, ty + 170, 24, 24, [198, 198, 198, 255]);
  sheet.blit(icon, 464 + i * 200, ty + 174, 1);
}
sheet.save(path.join(out, 'respirator_built.png'));
console.log('wrote', path.join(out, 'respirator_built.png'));

if (process.argv.includes('--write')) {
  tex.save('src/main/resources/assets/icheme/textures/models/armor/respirator_layer_1.png');
  fs.writeFileSync('src/main/java/com/wormzjl/icheme/client/RespiratorGeometry.java', java(PARTS));
  respiratorIcon.save('src/main/resources/assets/icheme/textures/item/respirator.png');
  filterIcon.save('src/main/resources/assets/icheme/textures/item/respirator_filter.png');
  console.log('wrote RespiratorGeometry.java, respirator_layer_1.png and the two item icons');
}
