// O8 (batch 2026-11-04-open-thermo-data): distance of each probed feed from the regrouped model's training ring, in
// three forms, against the learned seed's counters. Run from tmp/open-thermo-o8: node distances.mjs. Reads grades.json,
// grid.json, blends.json (O8NeuralGradesProbe); writes distances.json and prints a table.
import fs from 'node:fs';
const read = f => fs.existsSync(f) ? JSON.parse(fs.readFileSync(f, 'utf8')) : null;
const grades = read('grades.json'), grid = read('grid.json'), blends = read('blends.json');
const ring = grades.ringEdges; // 6 edges, each [a, b], mole fractions on the model's 19 compounds
const feeds = { ...grades.feeds, ...(grid?.feeds ?? {}), ...(blends?.feeds ?? {}) };
const runs = [...grades.runs, ...(grid?.runs ?? []), ...(blends?.runs ?? [])];
if (grid?.bundledCheck) runs.push(grid.bundledCheck);

const sub = (a, b) => a.map((v, i) => v - b[i]);
const dot = (a, b) => a.reduce((s, v, i) => s + v * b[i], 0);
const linf = a => Math.max(...a.map(Math.abs));
// Code form: the gate's clamped projection on each edge (least squares in t), then the largest component deviation.
function edgeDistance(x, edges, norm) {
  let best = Infinity, at = null;
  edges.forEach(([a, b], e) => {
    const d = sub(b, a), den = dot(d, d);
    const t = den === 0 ? 0 : Math.min(1, Math.max(0, dot(sub(x, a), d) / den));
    const r = norm(sub(x, a.map((v, i) => v + t * d[i])));
    if (r < best) { best = r; at = `${e}@${t.toFixed(2)}`; }
  });
  return { d: best, at };
}
const l2 = a => Math.sqrt(dot(a, a));

// Principal-component form: PCA of the trained compositions (each ring edge sampled at t = 0, 0.1, ..., 1).
const samples = [];
for (const [a, b] of ring) for (let k = 0; k <= 10; k++) samples.push(a.map((v, i) => v + k / 10 * (b[i] - v)));
const n = samples[0].length, mean = Array(n).fill(0);
for (const s of samples) s.forEach((v, i) => mean[i] += v / samples.length);
const C = Array.from({ length: n }, () => Array(n).fill(0));
for (const s of samples) { const c = sub(s, mean); for (let i = 0; i < n; i++) for (let j = 0; j < n; j++) C[i][j] += c[i] * c[j] / samples.length; }
function jacobi(A) { // symmetric eigen-decomposition
  const a = A.map(r => r.slice()), V = a.map((r, i) => r.map((_, j) => +(i === j)));
  for (let sweep = 0; sweep < 100; sweep++) {
    let off = 0; for (let i = 0; i < n; i++) for (let j = i + 1; j < n; j++) off += a[i][j] ** 2;
    if (off < 1e-30) break;
    for (let p = 0; p < n; p++) for (let q = p + 1; q < n; q++) {
      if (Math.abs(a[p][q]) < 1e-300) continue;
      const th = (a[q][q] - a[p][p]) / (2 * a[p][q]), t = Math.sign(th || 1) / (Math.abs(th) + Math.sqrt(th * th + 1));
      const c = 1 / Math.sqrt(t * t + 1), s = t * c;
      for (let k = 0; k < n; k++) { const x = a[k][p], y = a[k][q]; a[k][p] = c * x - s * y; a[k][q] = s * x + c * y; }
      for (let k = 0; k < n; k++) { const x = a[p][k], y = a[q][k]; a[p][k] = c * x - s * y; a[q][k] = s * x + c * y; }
      for (let k = 0; k < n; k++) { const x = V[k][p], y = V[k][q]; V[k][p] = c * x - s * y; V[k][q] = s * x + c * y; }
    }
  }
  return a.map((r, i) => ({ value: r[i], vector: V.map(row => row[i]) })).sort((x, y) => y.value - x.value);
}
const eig = jacobi(C), total = eig.reduce((s, e) => s + e.value, 0);
const K = +(process.env.K ?? 3), pcs = eig.slice(0, K).map(e => e.vector);
const explained = eig.slice(0, 4).map(e => +(e.value / total).toFixed(4));
const score = x => pcs.map(v => dot(sub(x, mean), v));
const sigma = eig.slice(0, K).map(e => Math.sqrt(e.value));
const ringScores = ring.map(([a, b]) => [score(a), score(b)]);
const ringRadius = Math.sqrt(total); // RMS distance of the trained compositions from their mean
function pcDistance(x) {
  const s = score(x), recon = mean.map((m, i) => m + pcs.reduce((acc, v, k) => acc + s[k] * v[i], 0));
  const inPlane = edgeDistance(s, ringScores, l2).d, outOfPlane = l2(sub(x, recon));
  return { inPlane: inPlane / ringRadius, outOfPlane: outOfPlane / ringRadius, scores: s.map((v, k) => +(v / sigma[k]).toFixed(2)) };
}

// Principal-cut form: the model's 19 compounds lumped into the column's five products by normal boiling point.
const CUTS = { lpg: [0, 1, 2, 3, 4], naphtha: [5, 6, 7, 8, 9], kerosene: [10, 11], gasOil: [12, 13], residue: [14, 15, 16, 17, 18] };
const lump = x => Object.values(CUTS).map(ix => ix.reduce((s, i) => s + x[i], 0));
const ringCuts = ring.map(([a, b]) => [lump(a), lump(b)]);
// Distance to the convex hull of the six crudes in cut space (Frank-Wolfe on the simplex weights, L2).
const vertices = ring.map(([a]) => lump(a));
function hullDistance(y) {
  let w = Array(vertices.length).fill(1 / vertices.length);
  const point = () => y.map((_, i) => vertices.reduce((s, v, k) => s + w[k] * v[i], 0));
  for (let it = 0; it < 5000; it++) {
    const p = point(), g = vertices.map(v => dot(sub(p, y), v)), k = g.indexOf(Math.min(...g));
    const d = sub(vertices[k], p), den = dot(d, d); if (den === 0) break;
    const step = Math.min(1, Math.max(0, -dot(sub(p, y), d) / den)); if (step < 1e-12) break;
    w = w.map((v, j) => (1 - step) * v + (j === k ? step : 0));
  }
  return linf(sub(y, point()));
}

const learned = {};
for (const r of runs) {
  if (r.route !== 'learned') continue;
  const ev = r.events.find(e => e.startsWith('scaled residual')) ?? '';
  const m = ev.match(/initial=([0-9.eE+-]+), final=([0-9.eE+-]+)/);
  learned[r.case] = { ok: r.success, polls: r.controlPolls, newton: r.newtonIterations, r0: m ? +m[1] : null, r1: m ? +m[2] : null };
}
const baseOf = c => c.startsWith('medium@') ? 'medium_crude' : c.startsWith('tia_juana') ? 'tia_juana' : c;
const out = { explainedVarianceTop4: explained, pcsKept: K, ringRadiusMoleFraction: ringRadius, cuts: CUTS, rows: [] };
console.log('PCA explained', explained, 'ring RMS radius', ringRadius.toFixed(4));
console.log('case                    edgeLinf  pcIn  pcOut  cutLinf  hullCut  globalOut  learned(ok,polls,newton,r0,r1)');
for (const c of Object.keys(learned)) {
  const f = feeds[c] ?? feeds[baseOf(c)]; if (!f) continue;
  const x = f.fractions, e = edgeDistance(x, ring, linf), p = pcDistance(x), q = edgeDistance(lump(x), ringCuts, linf), h = hullDistance(lump(x));
  const row = { case: c, edgeLinf: e.d, edgeAt: e.at, pcInPlane: p.inPlane, pcOutOfPlane: p.outOfPlane, pcScoresSigma: p.scores,
    cutLinf: q.d, cutAt: q.at, hullCutLinf: h, cutFractions: lump(x), globalOut: f.globalOut, learned: learned[c] };
  out.rows.push(row);
  const L = learned[c];
  console.log(c.padEnd(24), e.d.toFixed(4).padStart(8), p.inPlane.toFixed(2).padStart(5), p.outOfPlane.toFixed(2).padStart(6),
    q.d.toFixed(4).padStart(8), h.toFixed(4).padStart(8), String(f.globalOut.length).padStart(5), '  ', L.ok, L.polls, L.newton, L.r0?.toFixed(2), L.r1?.toFixed(3));
}
console.log('ring vertices in cut space:', vertices.map(v => v.map(x => x.toFixed(3)).join('/')).join('  '));
fs.writeFileSync('distances.json', JSON.stringify(out, null, 2));
