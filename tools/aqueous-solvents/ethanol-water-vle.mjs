// ethanol(1)-water(2) bubble points at 101.325 kPa.
// Models: PR78 phi-phi with classical mixing (k_ij scanned), NRTL gamma with Antoine, and the hybrid
// (NRTL gamma, ethanol's pure-liquid fugacity from PR78's own saturation, water from Antoine).
const R = 8.31446261815324, P = 101325;
// Perry's table (x1, y1, T in C), ethanol-water at 1 atm.
const DATA = [
  [0.0190, 0.1700, 95.5], [0.0721, 0.3891, 89.0], [0.0966, 0.4375, 86.7], [0.1238, 0.4704, 85.3],
  [0.1661, 0.5089, 84.1], [0.2337, 0.5445, 82.7], [0.2608, 0.5580, 82.3], [0.3273, 0.5826, 81.5],
  [0.3965, 0.6122, 80.7], [0.5079, 0.6564, 79.8], [0.5198, 0.6599, 79.7], [0.5732, 0.6841, 79.3],
  [0.6763, 0.7385, 78.74], [0.7472, 0.7815, 78.41], [0.8943, 0.8943, 78.15]];
const C = [{ tc: 514.71, pc: 6.268e6, w: 0.644 }, { tc: 647.096, pc: 22.064e6, w: 0.3443 }];

function kappa(w) {
  return w <= 0.491 ? 0.37464 + 1.54226 * w - 0.26992 * w * w
    : 0.379642 + 1.48503 * w - 0.164423 * w * w + 0.016666 * w * w * w;
}
function pure(T) {
  return C.map(c => {
    const al = (1 + kappa(c.w) * (1 - Math.sqrt(T / c.tc))) ** 2;
    return { a: 0.45724 * R * R * c.tc * c.tc / c.pc * al, b: 0.07780 * R * c.tc / c.pc };
  });
}
function roots(A, B) { // Z^3 - (1-B)Z^2 + (A-3B^2-2B)Z - (AB-B^2-B^3) = 0
  const c2 = -(1 - B), c1 = A - 3 * B * B - 2 * B, c0 = -(A * B - B * B - B * B * B);
  const f = z => ((z + c2) * z + c1) * z + c0, out = [];
  let prev = B + 1e-12, fp = f(prev);
  for (let i = 1; i <= 20000; i++) { // scan (B, 1.5] for sign changes, then bisect
    const z = B + (1.5 - B) * i / 20000, fz = f(z);
    if (fp === 0 || fp * fz < 0) {
      let lo = prev, hi = z;
      for (let k = 0; k < 80; k++) { const m = 0.5 * (lo + hi); if (f(lo) * f(m) <= 0) hi = m; else lo = m; }
      out.push(0.5 * (lo + hi));
    }
    prev = z; fp = fz;
  }
  return out;
}
// ln phi of both components for composition z, liquid (smallest root) or vapour (largest root).
function lnphi(T, p, z, k12, liquid) {
  const pc = pure(T), a12 = Math.sqrt(pc[0].a * pc[1].a) * (1 - k12);
  const a = z[0] * z[0] * pc[0].a + 2 * z[0] * z[1] * a12 + z[1] * z[1] * pc[1].a;
  const b = z[0] * pc[0].b + z[1] * pc[1].b, A = a * p / (R * T) ** 2, B = b * p / (R * T);
  const zs = roots(A, B); if (!zs.length) return null;
  const Z = liquid ? zs[0] : zs[zs.length - 1], s2 = Math.SQRT2;
  const sum = [z[0] * pc[0].a + z[1] * a12, z[0] * a12 + z[1] * pc[1].a];
  return [0, 1].map(i => pc[i].b / b * (Z - 1) - Math.log(Z - B)
    - A / (2 * s2 * B) * (2 * sum[i] / a - pc[i].b / b) * Math.log((Z + (1 + s2) * B) / (Z + (1 - s2) * B)));
}
function bubblePR(x1, k12) {
  const x = [x1, 1 - x1]; let y = [x1, 1 - x1];
  const resid = T => {
    for (let it = 0; it < 200; it++) {
      const l = lnphi(T, P, x, k12, true), v = lnphi(T, P, y, k12, false); if (!l || !v) return NaN;
      const ky = [0, 1].map(i => x[i] * Math.exp(l[i] - v[i])), s = ky[0] + ky[1], ny = [ky[0] / s, ky[1] / s];
      const d = Math.abs(ny[0] - y[0]); y = ny; if (d < 1e-12) return s - 1;
    }
    const l = lnphi(T, P, x, k12, true), v = lnphi(T, P, y, k12, false);
    return x[0] * Math.exp(l[0] - v[0]) + x[1] * Math.exp(l[1] - v[1]) - 1;
  };
  let lo = 320, hi = 390;
  for (let k = 0; k < 60; k++) { const m = 0.5 * (lo + hi); y = [x1, 1 - x1]; if (resid(m) > 0) hi = m; else lo = m; }
  const T = 0.5 * (lo + hi); resid(T); return { T, y: y[0] };
}
function psatPR(i, T) { // pure-component PR78 saturation pressure
  const c = C[i], al = (1 + kappa(c.w) * (1 - Math.sqrt(T / c.tc))) ** 2;
  const a = 0.45724 * R * R * c.tc * c.tc / c.pc * al, b = 0.07780 * R * c.tc / c.pc;
  let p = c.pc * Math.exp(5.373 * (1 + c.w) * (1 - c.tc / T));
  for (let it = 0; it < 200; it++) {
    const A = a * p / (R * T) ** 2, B = b * p / (R * T), zs = roots(A, B);
    if (zs.length < 2) { p *= 0.9; continue; }
    const ln = Z => Z - 1 - Math.log(Z - B) - A / (2 * Math.SQRT2 * B) * Math.log((Z + (1 + Math.SQRT2) * B) / (Z + (1 - Math.SQRT2) * B));
    const r = Math.exp(ln(zs[0]) - ln(zs[zs.length - 1])); p *= r; if (Math.abs(r - 1) < 1e-12) break;
  }
  return p;
}
const mmHg = 133.322368;
const antoine = [
  T => { const t = T - 273.15; return mmHg * 10 ** (t < 78.3 ? 8.20417 - 1642.89 / (230.3 + t) : 7.68117 - 1332.04 / (199.2 + t)); },
  T => mmHg * 10 ** (8.07131 - 1730.63 / (233.426 + T - 273.15))];
// NRTL, ethanol(1)-water(2), tau = a + b/T, alpha 0.3 (Aspen VLE-IG set).
function gamma(x1, T) {
  const x2 = 1 - x1, t12 = -0.8009 + 246.18 / T, t21 = 3.4578 - 586.0809 / T, al = 0.3;
  const G12 = Math.exp(-al * t12), G21 = Math.exp(-al * t21);
  return [Math.exp(x2 * x2 * (t21 * (G21 / (x1 + x2 * G21)) ** 2 + t12 * G12 / (x2 + x1 * G12) ** 2)),
    Math.exp(x1 * x1 * (t12 * (G12 / (x2 + x1 * G12)) ** 2 + t21 * G21 / (x1 + x2 * G21) ** 2))];
}
function bubbleGamma(x1, sat) {
  let lo = 320, hi = 390, p1, p2;
  for (let k = 0; k < 60; k++) {
    const T = 0.5 * (lo + hi), g = gamma(x1, T); p1 = x1 * g[0] * sat[0](T); p2 = (1 - x1) * g[1] * sat[1](T);
    if (p1 + p2 > P) hi = T; else lo = T;
  }
  return { T: 0.5 * (lo + hi), y: p1 / (p1 + p2) };
}
function score(model) {
  let dT = 0, dy = 0, mT = 0, my = 0;
  for (const [x, y, t] of DATA) {
    const r = model(x), eT = r.T - 273.15 - t, ey = r.y - y;
    dT += Math.abs(eT); dy += Math.abs(ey); mT = Math.max(mT, Math.abs(eT)); my = Math.max(my, Math.abs(ey));
  }
  return { aadT: dT / DATA.length, maxT: mT, aadY: dy / DATA.length, maxY: my };
}
function azeotrope(model) {
  let best = null;
  for (let x = 0.5; x <= 0.999; x += 0.001) { const r = model(x); if (best === null || Math.abs(r.y - x) < Math.abs(best.y - best.x)) best = { x, ...r }; }
  const end = model(0.999); return (end.y < 0.999 && Math.abs(best.y - best.x) < 5e-4) ? best : null;
}
const fmt = (n, d = 3) => n.toFixed(d);
function report(name, model) {
  const s = score(model), z = azeotrope(model), wash = model(0.033);
  console.log(`${name.padEnd(34)} AAD T ${fmt(s.aadT, 2)} K (max ${fmt(s.maxT, 2)})  AAD y ${fmt(s.aadY)} (max ${fmt(s.maxY)})  azeotrope ${z ? fmt(z.x) + ' at ' + fmt(z.T - 273.15, 2) + ' C' : 'none'}  wash x=0.033: ${fmt(wash.T - 273.15, 1)} C, y ${fmt(wash.y)}`);
}
console.log('pure ends, PR78 saturation: ethanol at 351.44 K', fmt(psatPR(0, 351.44) / 1000, 2), 'kPa; water at 373.15 K', fmt(psatPR(1, 373.15) / 1000, 2),
  'kPa; ethanol at 298.15 K', fmt(psatPR(0, 298.15) / 1000, 3), '(7.87 measured); water at 298.15 K', fmt(psatPR(1, 298.15) / 1000, 3), '(3.17 measured)');
console.log('measured: azeotrope 0.894 at 78.15 C; wash x=0.033 about 93 C, y about 0.25');
let best = null;
for (let k = -0.30; k <= 0.2001; k += 0.01) {
  const s = score(x => bubblePR(x, k)); if (!best || s.aadT + 100 * s.aadY < best.s.aadT + 100 * best.s.aadY) best = { k, s };
}
report('PR78, k12 = 0', x => bubblePR(x, 0));
report(`PR78, best k12 = ${fmt(best.k, 2)}`, x => bubblePR(x, best.k));
report('NRTL + Antoine (ideal vapour)', x => bubbleGamma(x, antoine));
report('hybrid: NRTL, ethanol f0 from PR78', x => bubbleGamma(x, [T => psatPR(0, T), antoine[1]]));
// False liquid split of the best PR78: ethanol's liquid fugacity must rise with x1 at fixed T, P.
let split = false, prevF = -Infinity;
for (let x = 0.005; x < 0.999; x += 0.005) {
  const l = lnphi(350, 5e5, [x, 1 - x], best.k, true), f = Math.log(x) + l[0];
  if (f < prevF) split = true; prevF = f;
}
console.log('best PR78 liquid at 350 K: false liquid-liquid split', split ? 'YES' : 'no');
console.log('point by point (x, y measured, y PR78 best, y hybrid; T measured, T PR78 best, T hybrid):');
for (const [x, y, t] of DATA) {
  const p = bubblePR(x, best.k), h = bubbleGamma(x, [T => psatPR(0, T), antoine[1]]);
  console.log(' ', fmt(x, 4), fmt(y, 4), fmt(p.y, 4), fmt(h.y, 4), ' ', fmt(t, 2), fmt(p.T - 273.15, 2), fmt(h.T - 273.15, 2));
}
