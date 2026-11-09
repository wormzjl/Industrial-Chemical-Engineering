// O8: rank correlation of the learned seed's initial scaled residual with each distance form, over the feeds that vary
// in composition at 638.15 K (Tia Juana, four grades, twelve blends). Run after distances.mjs: node relate.mjs.
import fs from 'node:fs';
const d = JSON.parse(fs.readFileSync('distances.json', 'utf8'));
const rows = d.rows.filter(r => !r.case.includes('@6') && r.case !== 'tia_juana-bundled');
const rank = a => { const s = a.map((v, i) => [v, i]).sort((x, y) => x[0] - y[0]), r = Array(a.length); s.forEach(([, i], k) => r[i] = k); return r; };
const spearman = (a, b) => { const ra = rank(a), rb = rank(b), n = a.length, m = (n - 1) / 2; let num = 0, da = 0, db = 0;
  for (let i = 0; i < n; i++) { num += (ra[i] - m) * (rb[i] - m); da += (ra[i] - m) ** 2; db += (rb[i] - m) ** 2; } return num / Math.sqrt(da * db); };
const r0 = rows.map(r => r.learned.r0), r1 = rows.map(r => r.learned.r1);
const forms = { edgeLinf: r => r.edgeLinf, pcInPlane: r => r.pcInPlane, pcOutOfPlane: r => r.pcOutOfPlane, pcTotal: r => Math.hypot(r.pcInPlane, r.pcOutOfPlane),
  cutLinf: r => r.cutLinf, hullCutLinf: r => r.hullCutLinf, compositionBoxOut: r => r.globalOut.filter(g => { const i = +g.slice(1, g.indexOf('=')); return i >= 17 && i <= 35; }).length };
console.log('n =', rows.length);
for (const [k, f] of Object.entries(forms)) console.log(k.padEnd(18), 'rho(r0)', spearman(rows.map(f), r0).toFixed(2), ' rho(final)', spearman(rows.map(f), r1).toFixed(2));
for (const r of rows) console.log(r.case.padEnd(22), r.globalOut.join(' '));
