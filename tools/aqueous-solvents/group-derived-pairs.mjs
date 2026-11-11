// No authored pair parameters: each polar pair's two Huron-Vidal energies are derived from the compounds' group counts
// with modified UNIFAC (Dortmund), by matching the two activity coefficients at infinite dilution at 298.15 K and
// 373.15 K (energies linear in T between them). The model that runs is PR78 with the Huron-Vidal rule (see
// pr-huron-vidal.mjs). Usage: node group-derived-pairs.mjs <dir with DWSIM's modfac.txt and modfac_ip.txt>
import { readFileSync } from 'node:fs';
const dir = process.argv[2]; if (!dir) throw new Error('usage: node group-derived-pairs.mjs <dwsim data dir>');
const R = 8.31446261815324, S2 = Math.SQRT2, LAMBDA = Math.log((2 + S2) / (2 - S2)) / (2 * S2), ALPHA = 0.3, ATM = 101325;
const fmt = (v, d = 3) => v.toFixed(d), sci = v => v.toExponential(1);

// Group tables.
const SUB = {}, IP = {};
for (const line of readFileSync(`${dir}/modfac.txt`, 'utf8').split(/\r?\n/).slice(1)) {
  const f = line.split(';'); if (f.length > 5 && !(f[2] in SUB)) SUB[f[2]] = { main: +f[0], R: +f[4], Q: +f[5] };
}
for (const line of readFileSync(`${dir}/modfac_ip.txt`, 'utf8').split(/\r?\n/)) {
  if (!line.trim() || line.startsWith("'")) continue;
  const f = line.trim().split(/\s+/).map(Number); IP[`${f[0]}-${f[1]}`] = f.slice(2, 5); IP[`${f[1]}-${f[0]}`] = f.slice(5, 8);
}
function kappa(w) { return w <= 0.491 ? 0.37464 + 1.54226 * w - 0.26992 * w * w : 0.379642 + 1.48503 * w - 0.164423 * w * w + 0.016666 * w * w * w; }
const COMP = {
  ethanol: { tc: 514.71, pc: 6.268e6, c: [kappa(0.644), 0, 0], groups: { CH3: 1, CH2: 1, OH: 1 } },
  water: { tc: 647.096, pc: 22.064e6, c: [0.91167, -0.27706, 0.22232], groups: { H2O: 1 } },
  hexane: { tc: 507.6, pc: 3.025e6, c: [kappa(0.3013), 0, 0], groups: { CH3: 2, CH2: 4 } },
};

// Modified UNIFAC (Dortmund): ln gamma of each compound of `names` at composition x.
function dortmund(names, x, T, residualOnly = false) {
  const subs = [...new Set(names.flatMap(n => Object.keys(COMP[n].groups)))];
  const nu = names.map(n => subs.map(s => COMP[n].groups[s] ?? 0)), Q = subs.map(s => SUB[s].Q), main = subs.map(s => SUB[s].main);
  const psi = (m, k) => { if (main[m] === main[k]) return 1; const p = IP[`${main[m]}-${main[k]}`]; if (!p) throw new Error(`no group pair ${main[m]}-${main[k]}`); return Math.exp(-(p[0] + p[1] * T + p[2] * T * T) / T); };
  const lnGamma = comp => { // comp: mole numbers of each compound
    const X = subs.map((_, k) => comp.reduce((s, c, i) => s + c * nu[i][k], 0)), tot = X.reduce((s, v, k) => s + Q[k] * v, 0), theta = X.map((v, k) => Q[k] * v / tot);
    const den = subs.map((_, m) => theta.reduce((s, t, n) => s + t * psi(n, m), 0));
    return subs.map((_, k) => Q[k] * (1 - Math.log(den[k]) - theta.reduce((s, t, m) => s + t * psi(k, m) / den[m], 0)));
  };
  const mix = lnGamma(x), r = nu.map(row => row.reduce((s, v, k) => s + v * SUB[subs[k]].R, 0)), q = nu.map(row => row.reduce((s, v, k) => s + v * Q[k], 0));
  const sr = x.reduce((s, v, i) => s + v * r[i], 0), sq = x.reduce((s, v, i) => s + v * q[i], 0), s34 = x.reduce((s, v, i) => s + v * r[i] ** 0.75, 0);
  return names.map((_, i) => {
    const pure = lnGamma(names.map((__, j) => j === i ? 1 : 0)), V = r[i] / sr, F = q[i] / sq, Vp = r[i] ** 0.75 / s34;
    return (residualOnly ? 0 : 1 - Vp + Math.log(Vp) - 5 * q[i] * (1 - V / F + Math.log(V / F))) + nu[i].reduce((s, v, k) => s + v * (mix[k] - pure[k]), 0);
  });
}

// PR78 with the Huron-Vidal rule; energy(j, i, T) in J/mol.
const pureA = (n, T) => { const c = COMP[n], u = 1 - Math.sqrt(T / c.tc), s = 1 + c.c[0] * u + c.c[1] * u * u + c.c[2] * u * u * u; return 0.45724 * R * R * c.tc * c.tc / c.pc * s * s; };
const pureB = n => 0.07780 * R * COMP[n].tc / COMP[n].pc;
function cubic(c2, c1, c0) {
  const p = c1 - c2 * c2 / 3, q = 2 * c2 * c2 * c2 / 27 - c2 * c1 / 3 + c0, s = -c2 / 3, d = q * q / 4 + p * p * p / 27; let r;
  if (d > 0 || p === 0) { const e = Math.sqrt(Math.max(d, 0)); r = [Math.cbrt(-q / 2 + e) + Math.cbrt(-q / 2 - e) + s]; }
  else { const m = 2 * Math.sqrt(-p / 3), th = Math.acos(Math.max(-1, Math.min(1, 3 * q / (p * m)))) / 3; r = [0, 1, 2].map(k => m * Math.cos(th - 2 * Math.PI * k / 3) + s); }
  return r.map(z => { for (let i = 0; i < 4; i++) { const f = ((z + c2) * z + c1) * z + c0, g = (3 * z + 2 * c2) * z + c1; if (g !== 0) z -= f / g; } return z; }).sort((a, b) => a - b);
}
function lnphi(names, T, p, x, energy, liquid, alphaOf = () => ALPHA) {
  const n = names.length, a = names.map(m => pureA(m, T)), b = names.map(pureB);
  const tau = (j, i) => i === j ? 0 : energy(names[j], names[i], T) / (R * T), G = (j, i) => Math.exp(-alphaOf(names[j], names[i]) * tau(j, i)), Sn = [], Dn = [];
  for (let i = 0; i < n; i++) { let s = 0, d = 0; for (let j = 0; j < n; j++) { s += x[j] * b[j] * G(j, i) * tau(j, i); d += x[j] * b[j] * G(j, i); } Sn.push(s); Dn.push(d); }
  const lnG = names.map((_, k) => { let v = Sn[k] / Dn[k]; for (let i = 0; i < n; i++) v += x[i] * b[k] * G(k, i) * (tau(k, i) - Sn[i] / Dn[i]) / Dn[i]; return v; });
  let ge = 0, bm = 0, lin = 0; for (let i = 0; i < n; i++) { ge += x[i] * Sn[i] / Dn[i]; bm += x[i] * b[i]; lin += x[i] * a[i] / (b[i] * R * T); }
  const B = bm * p / (R * T), A = (lin - ge / LAMBDA) * B, zs = cubic(-(1 - B), A - 3 * B * B - 2 * B, -(A * B - B * B - B * B * B)).filter(z => z > B);
  if (!zs.length) return null; const Z = liquid ? zs[0] : zs[zs.length - 1], L = Math.log((Z + (1 + S2) * B) / (Z + (1 - S2) * B));
  return names.map((_, i) => b[i] / bm * (Z - 1) - Math.log(Z - B) - (a[i] / (b[i] * R * T) - lnG[i] / LAMBDA) / (2 * S2) * L);
}

// The group model inside the rule, nothing fitted, b linear. Residual form: a/(bRT) = sum x_i a_i/(b_i RT) - gE_residual/(q RT).
// Full form (Michelsen's MHV1, q 0.53 for PR): a/(bRT) = sum x_i a_i/(b_i RT) - [gE/RT + sum x_i ln(b/b_i)]/q.
function lnphiGroups(q, full = false) {
  return (names, T, p, x, liquid) => {
    const a = names.map(m => pureA(m, T)), b = names.map(pureB), lnG = dortmund(names, x, T, !full);
    let ge = 0, bm = 0, lin = 0; for (let i = 0; i < names.length; i++) { bm += x[i] * b[i]; lin += x[i] * a[i] / (b[i] * R * T); }
    if (full) for (let i = 0; i < names.length; i++) lnG[i] += Math.log(bm / b[i]) + b[i] / bm - 1;
    for (let i = 0; i < names.length; i++) ge += x[i] * (full ? lnG[i] - b[i] / bm + 1 : lnG[i]);
    const B = bm * p / (R * T), A = (lin - ge / q) * B, zs = cubic(-(1 - B), A - 3 * B * B - 2 * B, -(A * B - B * B - B * B * B)).filter(z => z > B);
    if (!zs.length) return null; const Z = liquid ? zs[0] : zs[zs.length - 1], L = Math.log((Z + (1 + S2) * B) / (Z + (1 - S2) * B));
    return names.map((_, i) => b[i] / bm * (Z - 1) - Math.log(Z - B) - (a[i] / (b[i] * R * T) - lnG[i] / q) / (2 * S2) * L);
  };
}

// Derive a pair's energies at T: the model's two ln gamma at infinite dilution equal the group model's.
function derive(first, second, T) {
  const names = [first, second], P = 2e6, target = [dortmund(names, [0, 1], T)[0], dortmund(names, [1, 0], T)[1]];
  const model = u => { const e = (j, i) => j === first ? u[1] : u[0]; // u[0]: energy(second, first), u[1]: energy(first, second)
    return [lnphi(names, T, P, [0, 1], e, true)[0] - lnphi([first], T, P, [1], () => 0, true)[0], lnphi(names, T, P, [1, 0], e, true)[1] - lnphi([second], T, P, [1], () => 0, true)[0]]; };
  let u = [R * T * target[0], R * T * target[1]];
  for (let it = 0; it < 60; it++) {
    const f = model(u).map((v, k) => v - target[k]); if (Math.max(Math.abs(f[0]), Math.abs(f[1])) < 1e-11) break;
    const h = 1, c0 = model([u[0] + h, u[1]]).map((v, k) => (v - target[k] - f[k]) / h), c1 = model([u[0], u[1] + h]).map((v, k) => (v - target[k] - f[k]) / h);
    const det = c0[0] * c1[1] - c1[0] * c0[1]; let d = [-(f[0] * c1[1] - c1[0] * f[1]) / det, -(c0[0] * f[1] - f[0] * c0[1]) / det];
    const big = Math.max(Math.abs(d[0]), Math.abs(d[1])); if (big > 3000) d = d.map(v => v * 3000 / big);
    u = [u[0] + d[0], u[1] + d[1]];
  }
  const miss = model(u).map((v, k) => Math.abs(v - target[k])); if (Math.max(...miss) > 1e-8) throw new Error(`pair ${first}/${second} at ${T} K did not match`);
  return { u, target };
}
const T1 = 298.15, T2 = 373.15, PAIRS = {};
for (const [a, b] of [['ethanol', 'water'], ['ethanol', 'hexane'], ['water', 'hexane']]) {
  const lo = derive(a, b, T1), hi = derive(a, b, T2); PAIRS[`${b}>${a}`] = [lo.u[0], hi.u[0]]; PAIRS[`${a}>${b}`] = [lo.u[1], hi.u[1]];
  console.log(`${a}/${b}: group model gamma at infinite dilution ${fmt(Math.exp(lo.target[0]), 2)}, ${fmt(Math.exp(lo.target[1]), 2)} at 25 C; ${fmt(Math.exp(hi.target[0]), 2)}, ${fmt(Math.exp(hi.target[1]), 2)} at 100 C; derived energies ${fmt(lo.u[0], 0)}, ${fmt(lo.u[1], 0)} J/mol at 25 C; ${fmt(hi.u[0], 0)}, ${fmt(hi.u[1], 0)} at 100 C`);
}
const energy = (j, i, T) => { const e = PAIRS[`${j}>${i}`]; return e[0] + (e[1] - e[0]) * (T - T1) / (T2 - T1); };

// Bubble point of a binary: by the model (phi-phi), or by the group model with given vapour pressures (ideal vapour).
const derived = (names, T, p, x, liquid) => lnphi(names, T, p, x, energy, liquid);
function bubble(names, x1, P, sat, phi = derived) {
  const x = [x1, 1 - x1]; let y = [...x], lo = 230, hi = 520;
  const resid = T => {
    if (sat) { const g = dortmund(names, x, T), p = [0, 1].map(i => x[i] * Math.exp(g[i]) * sat[i](T)); y = [p[0] / (p[0] + p[1]), p[1] / (p[0] + p[1])]; return (p[0] + p[1]) / P - 1; }
    let s = 1;
    for (let it = 0; it < 300; it++) {
      const l = phi(names, T, P, x, true), v = phi(names, T, P, y, false); if (!l || !v) return NaN;
      const k = [0, 1].map(i => x[i] * Math.exp(l[i] - v[i])); s = k[0] + k[1]; const ny = [k[0] / s, k[1] / s], d = Math.abs(ny[0] - y[0]); y = ny; if (d < 1e-12) break;
    }
    return s - 1;
  };
  for (let k = 0; k < 60; k++) { const m = 0.5 * (lo + hi); y = [...x]; if (resid(m) > 0) hi = m; else lo = m; }
  const T = 0.5 * (lo + hi); resid(T); return { T, y: y[0] };
}
function azeotrope(names, P, sat, from = 0.05, to = 0.999, phi = derived) {
  let best = null; for (let x = from; x <= to; x += 0.002) { const r = bubble(names, x, P, sat, phi); if (!best || Math.abs(r.y - x) < Math.abs(best.y - best.x)) best = { x, ...r }; }
  return Math.abs(best.y - best.x) < 1.5e-3 && best.x < to - 0.003 ? `${fmt(best.x)} at ${fmt(best.T - 273.15, 1)} C` : 'none';
}
const mmHg = 133.322368, antoine = [
  T => { const t = T - 273.15; return mmHg * 10 ** (t < 78.3 ? 8.20417 - 1642.89 / (230.3 + t) : 7.68117 - 1332.04 / (199.2 + t)); },
  T => mmHg * 10 ** (8.07131 - 1730.63 / (233.426 + T - 273.15))];
const DATA = [[0.0190, 0.1700, 95.5], [0.0721, 0.3891, 89.0], [0.0966, 0.4375, 86.7], [0.1238, 0.4704, 85.3], [0.1661, 0.5089, 84.1], [0.2337, 0.5445, 82.7], [0.2608, 0.5580, 82.3], [0.3273, 0.5826, 81.5],
  [0.3965, 0.6122, 80.7], [0.5079, 0.6564, 79.8], [0.5198, 0.6599, 79.7], [0.5732, 0.6841, 79.3], [0.6763, 0.7385, 78.74], [0.7472, 0.7815, 78.41], [0.8943, 0.8943, 78.15]];
function table(name, sat, phi = derived) {
  let dT = 0, dy = 0, mT = 0, my = 0;
  for (const [x, y, t] of DATA) { const r = bubble(['ethanol', 'water'], x, ATM, sat, phi), eT = Math.abs(r.T - 273.15 - t), ey = Math.abs(r.y - y); dT += eT; dy += ey; mT = Math.max(mT, eT); my = Math.max(my, ey); }
  const wash = bubble(['ethanol', 'water'], 0.033, ATM, sat, phi);
  console.log(`${name}: AAD T ${fmt(dT / 15, 2)} K (max ${fmt(mT, 2)}), AAD y ${fmt(dy / 15)} (max ${fmt(my)}), azeotrope ${azeotrope(['ethanol', 'water'], ATM, sat, 0.5, 0.999, phi)}, wash ${fmt(wash.T - 273.15, 1)} C y ${fmt(wash.y)}`);
}
console.log('\nethanol and water at 101.325 kPa (measured: azeotrope 0.894 at 78.15 C; wash about 93 C, y about 0.25)');
table('  group model itself, ideal vapour', antoine);
table('  PR78 + Huron-Vidal, derived pairs', null);
console.log(`  azeotrope of the derived model at 20 kPa ${azeotrope(['ethanol', 'water'], 20000, null, 0.5)} (NRTL 0.928), at 300 kPa ${azeotrope(['ethanol', 'water'], 300000, null, 0.5)} (NRTL 0.868)`);
console.log(`\nethanol and n-hexane at 101.325 kPa, derived model: azeotrope ${azeotrope(['ethanol', 'hexane'], ATM, null)} (measured 0.332 at 58.7 C)`);

// Two liquids: K_i = activity (or fugacity) coefficient ratio, Rachford-Rice on the organic share.
function twoLiquids(names, z, T, lnCoefficient, aqueous, organic) {
  let xa = aqueous, xo = organic, beta = 0.5;
  for (let it = 0; it < 4000; it++) {
    const la = lnCoefficient(xa), lo = lnCoefficient(xo), K = names.map((_, i) => Math.exp(la[i] - lo[i]));
    let a = 1e-12, b = 1 - 1e-12; const f = v => z.reduce((s, zi, i) => s + zi * (K[i] - 1) / (1 + v * (K[i] - 1)), 0);
    if (f(a) < 0 || f(b) > 0) return null;
    for (let k = 0; k < 80; k++) { const m = 0.5 * (a + b); if (f(m) > 0) a = m; else b = m; } beta = 0.5 * (a + b);
    const na = z.map((zi, i) => zi / (1 + beta * (K[i] - 1))), no = na.map((v, i) => K[i] * v), sa = na.reduce((s, v) => s + v, 0), so = no.reduce((s, v) => s + v, 0);
    const nxa = na.map(v => v / sa), nxo = no.map(v => v / so), d = Math.max(...nxa.map((v, i) => Math.abs(v - xa[i])), ...nxo.map((v, i) => Math.abs(v - xo[i])));
    xa = nxa; xo = nxo; if (d < 1e-13) break;
  }
  return { xa, xo, beta };
}
console.log('\nwater and n-hexane at 25 C (measured: hexane in water 2.5e-6, water in hexane 5.3e-4)');
{ const n = ['water', 'hexane'], T = 298.15;
  const g = twoLiquids(n, [0.5, 0.5], T, x => dortmund(n, x, T), [1 - 1e-6, 1e-6], [1e-3, 1 - 1e-3]), e = twoLiquids(n, [0.5, 0.5], T, x => lnphi(n, T, ATM, x, energy, true), [1 - 1e-6, 1e-6], [1e-3, 1 - 1e-3]);
  console.log(`  group model: hexane in water ${sci(g.xa[1])}, water in hexane ${sci(g.xo[0])}; derived model: ${sci(e.xa[1])}, ${sci(e.xo[0])}`); }
console.log('\nethanol 0.2, water 0.4, n-hexane 0.4 at 25 C: two liquids (mole fractions ethanol, water, hexane)');
{ const n = ['ethanol', 'water', 'hexane'], T = 298.15, z = [0.2, 0.4, 0.4], aq = [0.3, 0.7 - 1e-6, 1e-6], org = [0.05, 1e-3, 0.949];
  const show = (name, r) => console.log(r ? `  ${name}: aqueous ${r.xa.map(v => fmt(v, 4)).join(' ')}; organic ${r.xo.map(v => fmt(v, 4)).join(' ')}; ethanol's share in the aqueous liquid ${fmt(r.xa[0] * (1 - r.beta) / z[0], 3)}` : `  ${name}: one liquid`);
  show('group model', twoLiquids(n, z, T, x => dortmund(n, x, T), aq, org)); show('derived model', twoLiquids(n, z, T, x => lnphi(n, T, ATM, x, energy, true), aq, org)); }
let split = false, prev = -Infinity; for (let x = 0.005; x < 0.999; x += 0.005) { const f = Math.log(x) + lnphi(['ethanol', 'water'], 298.15, ATM, [x, 1 - x], energy, true)[0]; if (f < prev) split = true; prev = f; }
console.log(`\nfalse liquid split, derived model at 25 C: ethanol and water ${split ? 'YES' : 'no'}`);
split = false; prev = -Infinity; for (let x = 0.005; x < 0.999; x += 0.005) { const f = Math.log(x) + lnphi(['ethanol', 'hexane'], 298.15, ATM, [x, 1 - x], energy, true)[0]; if (f < prev) split = true; prev = f; }
console.log(`liquid split, derived model at 25 C: ethanol and n-hexane ${split ? 'YES (measured: miscible)' : 'no (measured: miscible)'}`);

// The group model inside the rule (nothing fitted, no pair): the same checks.
const misc = (names, phi) => { let bad = false, last = -Infinity; for (let x = 0.005; x < 0.999; x += 0.005) { const v = Math.log(x) + phi(names, 298.15, ATM, [x, 1 - x], true)[0]; if (v < last) bad = true; last = v; } return bad ? 'two liquids' : 'one liquid'; };
for (const [q, full] of [[0.53087, false], [0.53, true], [LAMBDA, true]]) {
  const phi = lnphiGroups(q, full); console.log(`
group model inside the rule, ${full ? 'full' : 'residual'} form, q = ${fmt(q, 5)}`);
  table('  ethanol and water at 101.325 kPa', null, phi);
  console.log(`  its azeotrope at 20 kPa ${azeotrope(['ethanol', 'water'], 20000, null, 0.5, 0.999, phi)} (NRTL 0.928), at 300 kPa ${azeotrope(['ethanol', 'water'], 300000, null, 0.5, 0.999, phi)} (NRTL 0.868)`);
  console.log(`  ethanol and n-hexane: azeotrope ${azeotrope(['ethanol', 'hexane'], ATM, null, 0.05, 0.999, phi)} (measured 0.332 at 58.7 C); at 25 C ${misc(['ethanol', 'hexane'], phi)} (measured: one)`);
  const n2 = ['water', 'hexane'], b2 = twoLiquids(n2, [0.5, 0.5], 298.15, x => phi(n2, 298.15, ATM, x, true), [1 - 1e-6, 1e-6], [1e-3, 1 - 1e-3]);
  console.log(`  water and n-hexane at 25 C: hexane in water ${sci(b2.xa[1])}, water in hexane ${sci(b2.xo[0])} (group model 1.5e-4, 7.8e-3; measured 2.5e-6, 5.3e-4)`);
  const n3 = ['ethanol', 'water', 'hexane'], r = twoLiquids(n3, [0.2, 0.4, 0.4], 298.15, x => phi(n3, 298.15, ATM, x, true), [0.3, 0.7 - 1e-6, 1e-6], [0.05, 1e-3, 0.949]);
  console.log(r ? `  ternary at 25 C: aqueous ${r.xa.map(v => fmt(v, 4)).join(' ')}; organic ${r.xo.map(v => fmt(v, 4)).join(' ')}; ethanol's share in the aqueous liquid ${fmt(r.xa[0] * (1 - r.beta) / 0.2, 3)}` : '  ternary at 25 C: one liquid');
}

// Three numbers per pair, derived: the two energies and alpha, by least squares on ln gamma of both compounds over
// the whole composition range at 298.15 K (the model's ln gamma_i = ln phi_i - ln phi_i of the pure liquid, at 2 MPa);
// then the energies alone at 373.15 K with that alpha.
function nelderMead(fn, start, step, iterations) {
  const n = start.length; let pts = [start, ...start.map((_, i) => start.map((v, j) => v + (i === j ? step[j] : 0)))].map(p => ({ p, f: fn(p) }));
  for (let it = 0; it < iterations; it++) {
    pts.sort((u, v) => u.f - v.f); const cen = start.map((_, j) => pts.slice(0, n).reduce((t, q) => t + q.p[j], 0) / n), worst = pts[n];
    const tryAt = t => { const p = cen.map((c, j) => c + t * (worst.p[j] - c)); return { p, f: fn(p) }; }, refl = tryAt(-1);
    if (refl.f < pts[0].f) { const ex = tryAt(-2); pts[n] = ex.f < refl.f ? ex : refl; } else if (refl.f < pts[n - 1].f) pts[n] = refl;
    else { const con = tryAt(0.5); if (con.f < worst.f) pts[n] = con; else pts = pts.map((q, k) => k === 0 ? q : (() => { const p = q.p.map((v, j) => 0.5 * (v + pts[0].p[j])); return { p, f: fn(p) }; })()); }
  }
  pts.sort((u, v) => u.f - v.f); return pts[0];
}
const GRID = [0, 0.02, 0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 0.95, 0.98, 1];
function deriveShape(first, second, T, fixedAlpha) {
  const names = [first, second], P = 2e6, pure = [lnphi([first], T, P, [1], () => 0, true)[0], lnphi([second], T, P, [1], () => 0, true)[0]];
  const target = GRID.map(x => dortmund(names, [x, 1 - x], T));
  // One liquid in the group model at T (its ln activity of the first compound rises with x): the derived pair must be one liquid too.
  const FINE = []; for (let x = 0.01; x < 0.995; x += 0.01) FINE.push(x);
  const falls = act => { let drop = 0, last = -Infinity; for (const x of FINE) { const v = act(x); if (v < last) drop += last - v; last = Math.max(last, v); } return drop; };
  const oneLiquid = falls(x => Math.log(x) + dortmund(names, [x, 1 - x], T)[0]) === 0;
  const miss = v => { const al = fixedAlpha ?? v[2]; if (!(al > 0.05 && al < 0.7)) return 1e9; let sum = 0;
    for (let k = 0; k < GRID.length; k++) { const x = GRID[k], l = lnphi(names, T, P, [x, 1 - x], (j) => j === first ? v[1] : v[0], true, () => al); if (!l) return 1e9;
      sum += (l[0] - pure[0] - target[k][0]) ** 2 + (l[1] - pure[1] - target[k][1]) ** 2; }
    if (oneLiquid) { const drop = falls(x => Math.log(x) + lnphi(names, T, P, [x, 1 - x], (j) => j === first ? v[1] : v[0], true, () => al)[0]); if (drop > 0) sum += 1 + 1000 * drop; }
    return sum; };
  const start = [R * T * target[0][0], R * T * target[GRID.length - 1][1]]; let best = null;
  for (const a0 of fixedAlpha ? [fixedAlpha] : [0.2, 0.3, 0.45]) { const r = nelderMead(miss, fixedAlpha ? start : [...start, a0], fixedAlpha ? [800, 800] : [800, 800, 0.05], 300); if (!best || r.f < best.f) best = r; }
  return { u: best.p, alpha: fixedAlpha ?? best.p[2], rms: Math.sqrt(best.f / (2 * GRID.length)), oneLiquid };
}
const SHAPED = {}, SHAPE_ALPHA = {};
console.log('\npairs with alpha derived too:');
for (const [a, b] of [['ethanol', 'water'], ['ethanol', 'hexane'], ['water', 'hexane']]) {
  const lo = deriveShape(a, b, T1), hi = deriveShape(a, b, T2, lo.alpha); SHAPED[`${b}>${a}`] = [lo.u[0], hi.u[0]]; SHAPED[`${a}>${b}`] = [lo.u[1], hi.u[1]]; SHAPE_ALPHA[[a, b].sort().join('/')] = lo.alpha;
  console.log(`  ${a}/${b}: alpha ${fmt(lo.alpha)}, energies ${fmt(lo.u[0], 0)}, ${fmt(lo.u[1], 0)} J/mol at 25 C (rms ln gamma ${fmt(lo.rms)}, group model: ${lo.oneLiquid ? 'one liquid' : 'two liquids'}); ${fmt(hi.u[0], 0)}, ${fmt(hi.u[1], 0)} at 100 C (rms ${fmt(hi.rms)})`);
}
{ const e = (j, i, T) => { const v = SHAPED[`${j}>${i}`]; return v[0] + (v[1] - v[0]) * (T - T1) / (T2 - T1); }, al = (j, i) => SHAPE_ALPHA[[j, i].sort().join('/')] ?? ALPHA;
  const phi = (names, T, p, x, liquid) => lnphi(names, T, p, x, e, liquid, al);
  table('  ethanol and water at 101.325 kPa', null, phi);
  console.log(`  its azeotrope at 20 kPa ${azeotrope(['ethanol', 'water'], 20000, null, 0.5, 0.999, phi)} (NRTL 0.928), at 300 kPa ${azeotrope(['ethanol', 'water'], 300000, null, 0.5, 0.999, phi)} (NRTL 0.868)`);
  console.log(`  ethanol and n-hexane: azeotrope ${azeotrope(['ethanol', 'hexane'], ATM, null, 0.05, 0.999, phi)} (measured 0.332 at 58.7 C); at 25 C ${misc(['ethanol', 'hexane'], phi)} (measured: one)`);
  const n2 = ['water', 'hexane'], b2 = twoLiquids(n2, [0.5, 0.5], 298.15, x => phi(n2, 298.15, ATM, x, true), [1 - 1e-6, 1e-6], [1e-3, 1 - 1e-3]);
  console.log(`  water and n-hexane at 25 C: hexane in water ${sci(b2.xa[1])}, water in hexane ${sci(b2.xo[0])} (group model 1.5e-4, 7.8e-3; measured 2.5e-6, 5.3e-4)`);
  const n3 = ['ethanol', 'water', 'hexane'], r = twoLiquids(n3, [0.2, 0.4, 0.4], 298.15, x => phi(n3, 298.15, ATM, x, true), [0.3, 0.7 - 1e-6, 1e-6], [0.05, 1e-3, 0.949]);
  console.log(r ? `  ternary at 25 C: aqueous ${r.xa.map(v => fmt(v, 4)).join(' ')}; organic ${r.xo.map(v => fmt(v, 4)).join(' ')}; ethanol's share in the aqueous liquid ${fmt(r.xa[0] * (1 - r.beta) / 0.2, 3)} (group model: aqueous 0.3206 0.6734 0.0059; organic 0.0280 0.0103 0.9617; 0.942)` : '  ternary at 25 C: one liquid'); }
