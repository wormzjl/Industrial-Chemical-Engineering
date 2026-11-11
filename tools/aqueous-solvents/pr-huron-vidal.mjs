// PR78 with water on the cubic (a Mathias-Copeman alpha fitted to its saturation line) and the Huron-Vidal mixing
// rule with the b-weighted NRTL for the ethanol(1)-water(2) pair. One model for every phase: the form a column and a
// still on ln phi can run. Checks: pure water (saturation, heat of vaporization, liquid heat capacity), then the
// 15 points of Perry's table at 101.325 kPa with the pair's two energies fitted, then with energies linear in T
// fitted also to bubble points of the temperature-dependent NRTL (ideal vapour) at 20 and 300 kPa.
const R = 8.31446261815324, S2 = Math.SQRT2, LAMBDA = Math.log((2 + S2) / (2 - S2)) / (2 * S2);
const DATA = [
  [0.0190, 0.1700, 95.5], [0.0721, 0.3891, 89.0], [0.0966, 0.4375, 86.7], [0.1238, 0.4704, 85.3],
  [0.1661, 0.5089, 84.1], [0.2337, 0.5445, 82.7], [0.2608, 0.5580, 82.3], [0.3273, 0.5826, 81.5],
  [0.3965, 0.6122, 80.7], [0.5079, 0.6564, 79.8], [0.5198, 0.6599, 79.7], [0.5732, 0.6841, 79.3],
  [0.6763, 0.7385, 78.74], [0.7472, 0.7815, 78.41], [0.8943, 0.8943, 78.15]];
function kappa(w) {
  return w <= 0.491 ? 0.37464 + 1.54226 * w - 0.26992 * w * w
    : 0.379642 + 1.48503 * w - 0.164423 * w * w + 0.016666 * w * w * w;
}
// c: the Mathias-Copeman coefficients; [kappa, 0, 0] is PR78's own alpha.
const C = [{ tc: 514.71, pc: 6.268e6, c: [kappa(0.644), 0, 0] }, { tc: 647.096, pc: 22.064e6, c: [kappa(0.3443), 0, 0] }];
function pureA(i, T) {
  const c = C[i], u = 1 - Math.sqrt(T / c.tc), s = 1 + c.c[0] * u + c.c[1] * u * u + c.c[2] * u * u * u;
  return 0.45724 * R * R * c.tc * c.tc / c.pc * s * s;
}
const pureB = i => 0.07780 * R * C[i].tc / C[i].pc;
function cubic(c2, c1, c0) {
  const p = c1 - c2 * c2 / 3, q = 2 * c2 * c2 * c2 / 27 - c2 * c1 / 3 + c0, s = -c2 / 3, d = q * q / 4 + p * p * p / 27;
  let r;
  if (d > 0 || p === 0) { const e = Math.sqrt(Math.max(d, 0)); r = [Math.cbrt(-q / 2 + e) + Math.cbrt(-q / 2 - e) + s]; }
  else { const m = 2 * Math.sqrt(-p / 3), th = Math.acos(Math.max(-1, Math.min(1, 3 * q / (p * m)))) / 3; r = [0, 1, 2].map(k => m * Math.cos(th - 2 * Math.PI * k / 3) + s); }
  return r.map(z => { for (let i = 0; i < 4; i++) { const f = ((z + c2) * z + c1) * z + c0, g = (3 * z + 2 * c2) * z + c1; if (g !== 0) z -= f / g; } return z; }).sort((a, b) => a - b);
}
const roots = (A, B) => cubic(-(1 - B), A - 3 * B * B - 2 * B, -(A * B - B * B - B * B * B)).filter(z => z > B);
const logTerm = (Z, B) => Math.log((Z + (1 + S2) * B) / (Z + (1 - S2) * B));
// Huron-Vidal: a/b = sum x_i a_i/b_i - gE/Lambda; gE the NRTL with b-weighted local compositions. E[j][i] in J/mol.
function mixture(T, x, par) {
  const n = x.length, a = x.map((_, i) => pureA(i, T)), b = x.map((_, i) => pureB(i));
  const energy = v => Array.isArray(v) ? v[0] + v[1] * (T - 351.44) : v;
  const tau = (j, i) => i === j ? 0 : energy(par.E[j][i]) / (R * T), G = (j, i) => Math.exp(-par.alpha * tau(j, i));
  const Sn = [], Dn = [];
  for (let i = 0; i < n; i++) { let s = 0, d = 0; for (let j = 0; j < n; j++) { s += x[j] * b[j] * G(j, i) * tau(j, i); d += x[j] * b[j] * G(j, i); } Sn.push(s); Dn.push(d); }
  const lnGamma = x.map((_, k) => { let v = Sn[k] / Dn[k]; for (let i = 0; i < n; i++) v += x[i] * b[k] * G(k, i) * (tau(k, i) - Sn[i] / Dn[i]) / Dn[i]; return v; });
  let ge = 0, bm = 0, lin = 0;
  for (let i = 0; i < n; i++) { ge += x[i] * Sn[i] / Dn[i]; bm += x[i] * b[i]; lin += x[i] * a[i] / (b[i] * R * T); }
  return { a, b, bm, alphaMix: lin - ge / LAMBDA, alphaBar: x.map((_, i) => a[i] / (b[i] * R * T) - lnGamma[i] / LAMBDA) };
}
function lnphi(T, p, x, par, liquid) {
  const m = mixture(T, x, par), B = m.bm * p / (R * T), A = m.alphaMix * B, zs = roots(A, B); if (!zs.length) return null;
  const Z = liquid ? zs[0] : zs[zs.length - 1];
  return x.map((_, i) => m.b[i] / m.bm * (Z - 1) - Math.log(Z - B) - m.alphaBar[i] / (2 * S2) * logTerm(Z, B));
}
function bubble(x1, par, P) {
  const x = [x1, 1 - x1]; let y = [x1, 1 - x1];
  const resid = T => {
    let s = 1;
    for (let it = 0; it < 300; it++) {
      const l = lnphi(T, P, x, par, true), v = lnphi(T, P, y, par, false); if (!l || !v) return NaN;
      const ky = [0, 1].map(i => x[i] * Math.exp(l[i] - v[i])); s = ky[0] + ky[1];
      const ny = [ky[0] / s, ky[1] / s], d = Math.abs(ny[0] - y[0]); y = ny; if (d < 1e-12) break;
    }
    return s - 1;
  };
  let lo = 250, hi = 520;
  for (let k = 0; k < 60; k++) { const m = 0.5 * (lo + hi); y = [x1, 1 - x1]; if (resid(m) > 0) hi = m; else lo = m; }
  const T = 0.5 * (lo + hi); resid(T); return { T, y: y[0] };
}
// Pure component i: saturation pressure, and the residual enthalpy of a root.
function saturation(i, T) {
  const a = pureA(i, T), b = pureB(i); let p = C[i].pc * Math.exp(5.373 * (1 + 0.5) * (1 - C[i].tc / T));
  for (let it = 0; it < 300; it++) {
    const A = a * p / (R * T) ** 2, B = b * p / (R * T), zs = roots(A, B);
    if (zs.length < 2) { p *= 0.7; continue; }
    const ln = Z => Z - 1 - Math.log(Z - B) - A / (2 * S2 * B) * logTerm(Z, B), r = Math.exp(ln(zs[0]) - ln(zs[zs.length - 1]));
    p *= r; if (Math.abs(r - 1) < 1e-13) break;
  }
  return p;
}
function residualEnthalpy(i, T, p, liquid) {
  const a = pureA(i, T), b = pureB(i), dadT = (pureA(i, T + 0.01) - pureA(i, T - 0.01)) / 0.02;
  const B = b * p / (R * T), zs = roots(a * p / (R * T) ** 2, B), Z = liquid ? zs[0] : zs[zs.length - 1];
  return R * T * (Z - 1) + (T * dadT - a) / (2 * S2 * b) * logTerm(Z, B);
}
function nelderMead(f, start, step, iterations) {
  const n = start.length; let pts = [start, ...start.map((_, i) => start.map((v, j) => v + (i === j ? step[j] : 0)))].map(p => ({ p, f: f(p) }));
  for (let it = 0; it < iterations; it++) {
    pts.sort((u, v) => u.f - v.f);
    const cen = start.map((_, j) => pts.slice(0, n).reduce((s, q) => s + q.p[j], 0) / n), worst = pts[n];
    const at = t => cen.map((c, j) => c + t * (worst.p[j] - c)), tryAt = t => { const p = at(t); return { p, f: f(p) }; };
    const refl = tryAt(-1);
    if (refl.f < pts[0].f) { const exp = tryAt(-2); pts[n] = exp.f < refl.f ? exp : refl; }
    else if (refl.f < pts[n - 1].f) pts[n] = refl;
    else { const con = tryAt(0.5); if (con.f < worst.f) pts[n] = con; else pts = pts.map((q, k) => k === 0 ? q : (() => { const p = q.p.map((v, j) => 0.5 * (v + pts[0].p[j])); return { p, f: f(p) }; })()); }
  }
  pts.sort((u, v) => u.f - v.f); return pts[0];
}
const fmt = (v, d = 3) => v.toFixed(d);

// 1. Water on the cubic.
const wagner = T => { const th = 1 - T / 647.096; return 22.064e6 * Math.exp(647.096 / T * (-7.85951783 * th + 1.84408259 * th ** 1.5 - 11.7866497 * th ** 3 + 22.6807411 * th ** 3.5 - 15.9618719 * th ** 4 + 1.80122502 * th ** 7.5)); };
const grid = []; for (let T = 273.15; T <= 473.151; T += 10) grid.push(T);
const waterError = () => grid.map(T => saturation(1, T) / wagner(T) - 1);
const worst = e => Math.max(...e.map(Math.abs));
console.log('water, PR78 alpha: saturation error over 0..200 C, worst', fmt(100 * worst(waterError()), 2), '%; at 100 C', fmt(saturation(1, 373.15) / 1000, 2), 'kPa');
const fit = nelderMead(c => { C[1].c = c; return waterError().reduce((s, e) => s + e * e, 0); }, [kappa(0.3443), 0, 0], [0.05, 0.1, 0.1], 400);
C[1].c = fit.p;
console.log('water, Mathias-Copeman c =', fit.p.map(v => fmt(v, 5)).join(', '), ': worst', fmt(100 * worst(waterError()), 3), '%; at 100 C', fmt(saturation(1, 373.15) / 1000, 3), 'kPa');
for (const [t, ref] of [[25, 43.99], [100, 40.65], [150, 38.08], [200, 34.94]]) {
  const T = t + 273.15, p = saturation(1, T), h = (residualEnthalpy(1, T, p, false) - residualEnthalpy(1, T, p, true)) / 1000;
  console.log(`  heat of vaporization at ${t} C: ${fmt(h, 2)} kJ/mol (IAPWS ${ref}, ${fmt(100 * (h / ref - 1), 1)} %)`);
}
for (const t of [25, 60, 100]) {
  const T = t + 273.15, cp = 33.6 + 0.007 * (T - 300) + (residualEnthalpy(1, T + 0.5, 101325, true) - residualEnthalpy(1, T - 0.5, 101325, true));
  console.log(`  liquid heat capacity at ${t} C: ${fmt(cp, 1)} J/mol K (measured 75.3 to 75.9)`);
}
console.log('ethanol, PR78 alpha: saturation at 351.44 K', fmt(saturation(0, 351.44) / 1000, 2), 'kPa; at 298.15 K', fmt(saturation(0, 298.15) / 1000, 3), 'kPa (7.87 measured)');

// 2. The pair: fit E[2][1] and E[1][2] (J/mol) at alpha 0.3, then alpha too.
const P = 101325, par = (v, alpha = 0.3) => ({ alpha, E: [[0, v[0]], [v[1], 0]] });
function errors(pr) {
  let dT = 0, dy = 0, mT = 0, my = 0, sq = 0;
  for (const [x, y, t] of DATA) {
    const r = bubble(x, pr, P), eT = r.T - 273.15 - t, ey = r.y - y;
    if (!Number.isFinite(eT) || !Number.isFinite(ey)) return { sq: 1e9 };
    dT += Math.abs(eT); dy += Math.abs(ey); mT = Math.max(mT, Math.abs(eT)); my = Math.max(my, Math.abs(ey)); sq += (eT / 0.3) ** 2 + (ey / 0.01) ** 2;
  }
  return { aadT: dT / DATA.length, maxT: mT, aadY: dy / DATA.length, maxY: my, sq };
}
function azeotrope(pr, pressure) {
  let best = null;
  for (let x = 0.5; x <= 0.9991; x += 0.002) { const r = bubble(x, pr, pressure); if (!best || Math.abs(r.y - x) < Math.abs(best.y - best.x)) best = { x, ...r }; }
  const end = bubble(0.999, pr, pressure); return end.y < 0.999 && Math.abs(best.y - best.x) < 1.5e-3 ? best : null;
}
let best = null;
for (const s of [[-300, 5200], [5200, -300], [2000, 2000], [0, 0], [4000, 4000]]) {
  const r = nelderMead(v => errors(par(v)).sq, s, [500, 500], 120); if (!best || r.f < best.f) best = r;
}
function report(name, pr) {
  const e = errors(pr), z = azeotrope(pr, P), wash = bubble(0.033, pr, P), bottom = bubble(0.001, pr, P);
  console.log(`${name}: AAD T ${fmt(e.aadT, 2)} K (max ${fmt(e.maxT, 2)}), AAD y ${fmt(e.aadY)} (max ${fmt(e.maxY)}), azeotrope ${z ? fmt(z.x) + ' at ' + fmt(z.T - 273.15, 2) + ' C' : 'none'}, wash x=0.033: ${fmt(wash.T - 273.15, 1)} C y ${fmt(wash.y)}, x=0.001: ${fmt(bottom.T - 273.15, 2)} C`);
}
console.log('measured: azeotrope 0.894 at 78.15 C; wash about 93 C, y about 0.25');
report(`PR78 + Huron-Vidal, alpha 0.3, E21 ${fmt(best.p[0], 0)} E12 ${fmt(best.p[1], 0)} J/mol`, par(best.p));
const free = nelderMead(v => v[2] > 0.05 && v[2] < 0.6 ? errors(par(v, v[2])).sq : 1e9, [...best.p, 0.3], [300, 300, 0.05], 200);
report(`PR78 + Huron-Vidal, alpha ${fmt(free.p[2], 3)}, E21 ${fmt(free.p[0], 0)} E12 ${fmt(free.p[1], 0)} J/mol`, par(free.p, free.p[2]));
const chosen = par(free.p, free.p[2]);
let split = false, prev = -Infinity;
for (let x = 0.005; x < 0.999; x += 0.005) { const f = Math.log(x) + lnphi(350, 5e5, [x, 1 - x], chosen, true)[0]; if (f < prev) split = true; prev = f; }
console.log('false liquid-liquid split at 350 K:', split ? 'YES' : 'no');
for (const pressure of [20000, 50000, 300000, 1000000]) {
  const z = azeotrope(chosen, pressure);
  console.log(`  at ${pressure / 1000} kPa, same energies: azeotrope ${z ? fmt(z.x) + ' at ' + fmt(z.T - 273.15, 1) + ' C' : 'none below 0.999'}`);
}
console.log('point by point (x, y measured, y model; T measured, T model):');
for (const [x, y, t] of DATA) { const r = bubble(x, chosen, P); console.log(' ', fmt(x, 4), fmt(y, 4), fmt(r.y, 4), ' ', fmt(t, 2), fmt(r.T - 273.15, 2)); }

// 3. Energies linear in T, E = E0 + E1 (T - 351.44 K): Perry's table and NRTL bubble points at 20 and 300 kPa.
const mmHg = 133.322368, antoine = [
  T => { const t = T - 273.15; return mmHg * 10 ** (t < 78.3 ? 8.20417 - 1642.89 / (230.3 + t) : 7.68117 - 1332.04 / (199.2 + t)); },
  T => mmHg * 10 ** (8.07131 - 1730.63 / (233.426 + T - 273.15))];
function nrtl(x1, pressure) {
  let lo = 250, hi = 480, p1, p2;
  for (let k = 0; k < 60; k++) {
    const T = 0.5 * (lo + hi), x2 = 1 - x1, t12 = -0.8009 + 246.18 / T, t21 = 3.4578 - 586.0809 / T, G12 = Math.exp(-0.3 * t12), G21 = Math.exp(-0.3 * t21);
    p1 = x1 * Math.exp(x2 * x2 * (t21 * (G21 / (x1 + x2 * G21)) ** 2 + t12 * G12 / (x2 + x1 * G12) ** 2)) * antoine[0](T);
    p2 = x2 * Math.exp(x1 * x1 * (t12 * (G12 / (x2 + x1 * G12)) ** 2 + t21 * G21 / (x1 + x2 * G21) ** 2)) * antoine[1](T);
    if (p1 + p2 > pressure) hi = T; else lo = T;
  }
  return { T: 0.5 * (lo + hi), y: p1 / (p1 + p2) };
}
const pseudo = [];
for (const pressure of [20000, 300000]) for (const x of [0.03, 0.1, 0.3, 0.6, 0.85, 0.95]) pseudo.push({ pressure, x, ...nrtl(x, pressure) });
const linear = v => ({ alpha: 0.3, E: [[0, [v[0], v[2]]], [[v[1], v[3]], 0]] });
const wide = v => { const pr = linear(v); let sq = errors(pr).sq; for (const q of pseudo) { const r = bubble(q.x, pr, q.pressure); sq += ((r.T - q.T) / 0.3) ** 2 + ((r.y - q.y) / 0.01) ** 2; } return Number.isFinite(sq) ? sq : 1e9; };
const lin = nelderMead(wide, [...best.p, 0, 0], [300, 300, 5, 5], 400), chosenT = linear(lin.p);
report(`PR78 + Huron-Vidal, energies linear in T (E21 ${fmt(lin.p[0], 0)} ${fmt(lin.p[2], 2)}/K, E12 ${fmt(lin.p[1], 0)} ${fmt(lin.p[3], 2)}/K)`, chosenT);
for (const pressure of [20000, 50000, 300000]) {
  const z = azeotrope(chosenT, pressure), n = (() => { let b = null; for (let x = 0.5; x <= 0.9991; x += 0.002) { const r = nrtl(x, pressure); if (!b || Math.abs(r.y - x) < Math.abs(b.y - b.x)) b = { x, ...r }; } return b; })();
  console.log(`  at ${pressure / 1000} kPa: azeotrope ${z ? fmt(z.x) + ' at ' + fmt(z.T - 273.15, 1) + ' C' : 'none below 0.999'} (NRTL ${fmt(n.x)} at ${fmt(n.T - 273.15, 1)} C)`);
}
let worstT = 0, worstY = 0; for (const q of pseudo) { const r = bubble(q.x, chosenT, q.pressure); worstT = Math.max(worstT, Math.abs(r.T - q.T)); worstY = Math.max(worstY, Math.abs(r.y - q.y)); }
console.log('  against the NRTL points at 20 and 300 kPa: worst', fmt(worstT, 2), 'K, y', fmt(worstY));
