// Fits the corrosion sub-record's per-cut sulfur (wt%) and acid number (mg KOH/g) of crude_pc01..12 so that the four
// assays reproduce their whole-crude values (batch 2026-11-16-fouling-corrosion, WP1, plan section 6.4). The light ends
// carry neither. One set of twelve values serves the four assays: non-decreasing with the cut, the lightest cut and the
// residue held near the plan's ends. The fit minimises the largest relative residual (a convex problem), smoothed as a
// log-sum-exp and solved by projected gradient descent. Deterministic.
// Run from the repository root: node tools/fouling-corrosion/fit-cuts.mjs
import { readFileSync } from 'node:fs';

const dir = 'src/main/resources/data/icheme/materials/assays/';
// Whole-crude targets: sulfur from each assay's specification; acid number from the plan.
const assays = { light_crude: [0.2, 0.1], medium_crude: [1.5, 0.3], heavy_crude: [3, 1.5], oil_sand_dilbit: [3.74, 2.5] };
const crudes = [];
for (const [name, target] of Object.entries(assays)) {
  const a = JSON.parse(readFileSync(dir + name + '.json', 'utf8'));
  const total = Object.values(a.amounts_by_component).reduce((s, x) => s + x, 0);
  const w = []; for (let i = 1; i <= 12; i++) w.push((a.amounts_by_component['crude_pc' + String(i).padStart(2, '0')] ?? 0) / total);
  crudes.push({ name, w, sulfur: target[0], acid: target[1] });
}
const whole = (w, v) => w.reduce((s, x, i) => s + x * v[i], 0);

// Values from steps: v_0 = first, v_i = v_(i-1) + s_i, s_i >= 0; the last value held in [lastMin, lastMax].
function values(first, steps) { const v = [first]; for (const s of steps) v.push(v[v.length - 1] + s); return v; }
function residuals(key, v) { return crudes.map(k => whole(k.w, v) / k[key] - 1); }
function project(first, steps, lastMin, lastMax) {
  const s = steps.map(x => Math.max(0, x));
  const sum = s.reduce((a, b) => a + b, 0), lo = lastMin - first, hi = lastMax - first;
  if (sum > hi) return s.map(x => x * hi / sum);
  if (sum < lo) { const add = (lo - sum) / s.length; return s.map(x => x + add); }
  return s;
}
function fit(key, first, lastMin, lastMax) {
  let steps = project(first, Array(11).fill((lastMin - first) / 11), lastMin, lastMax);
  for (const beta of [5, 20, 80, 320, 1280]) for (let n = 0; n < 40000; n++) {
    const v = values(first, steps), r = residuals(key, v);
    // Smoothed max of |r|: softmax weights over +r and -r.
    const terms = r.flatMap((x, k) => [[beta * x, k, 1], [-beta * x, k, -1]]);
    const m = Math.max(...terms.map(t => t[0]));
    const e = terms.map(t => Math.exp(t[0] - m)), z = e.reduce((a, b) => a + b, 0);
    const gv = Array(12).fill(0);
    terms.forEach((t, j) => { const k = crudes[t[1]]; for (let i = 0; i < 12; i++) gv[i] += e[j] / z * t[2] * k.w[i] / k[key]; });
    // d v_i / d s_j = 1 for i >= j.
    const gs = steps.map((_, j) => { let g = 0; for (let i = j + 1; i < 12; i++) g += gv[i]; return g; });
    steps = project(first, steps.map((s, j) => s - 0.5 / beta * gs[j]), lastMin, lastMax);
  }
  // Two significant figures, order kept.
  const v = values(first, steps).map(x => x < 1e-3 ? 0 : Number(x.toPrecision(2)));
  v[0] = first;
  for (let i = 1; i < 12; i++) v[i] = Math.max(v[i], v[i - 1]);
  return { v, res: residuals(key, v) };
}
for (const [key, first, lastMin, lastMax] of [['sulfur', 0.02, 4.5, 6], ['acid', 0, 3.5, 4.5]]) {
  const f = fit(key, first, lastMin, lastMax);
  console.log(key, JSON.stringify(f.v));
  crudes.forEach((k, i) => console.log('  ' + k.name.padEnd(16), 'target', k[key], 'fit', whole(k.w, f.v).toFixed(3), 'residual', (100 * f.res[i]).toFixed(1) + ' %'));
}
