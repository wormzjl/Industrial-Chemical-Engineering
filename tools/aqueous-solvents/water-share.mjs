// Prototype of plan M2: water as a compound of the cubic, with water's own share of enthalpy and volume from IF97.
//   equilibrium: the cubic alone (a Mathias-Copeman alpha matched to the Wagner and Pruss saturation line);
//   a liquid-like phase: + n_w W (Region 1 - the cubic's pure liquid), at max(P, saturation), faded out from 350 C to the critical temperature;
//   a gas-like phase:    + (n_w^2 / n) (1 - W) P (IF97's second virial coefficient - the cubic's);
//   W: a smooth function of the phase's own v/b, 1 for a dense liquid and 0 on the gas side of the critical volume.
// Truth: IF97 Region 1 (the repository's table), Region 2 (below, checked against the release's test values).
// Usage: node water-share.mjs <WaterRegion1.java> [alpha coefficients, default 3]
import { readFileSync } from 'node:fs';
const R = 8.31446261815324, RS = 461.526, M = R / RS, S2 = Math.SQRT2, TC = 647.096, PC = 22.064e6, T1 = 623.15, TRIPLE = 273.16;
const source = readFileSync(process.argv[2], 'utf8'), TERMS = Number(process.argv[3] ?? 3);
const table = name => source.match(new RegExp(`\\b${name}\\s*=\\s*\\{([^}]*)\\}`))[1].split(',').map(v => Number(v.trim()));
const I1 = table('I'), J1 = table('J'), N1 = table('N');
if (I1.length !== 34 || J1.length !== 34 || N1.length !== 34 || N1.some(Number.isNaN)) throw new Error('Region 1 table not read');

// ---- IF97, per kg ----
function region1raw(T, p) {
  const tau = 1386 / T, x = p / 16.53e6 - 7.1, y = tau - 1.222; let gp = 0, gpp = 0, gt = 0, gtt = 0, gpt = 0;
  for (let k = 0; k < 34; k++) {
    const i = I1[k], j = J1[k], n = N1[k];
    if (i !== 0) gp += n * i * x ** (i - 1) * y ** j;
    if (i > 1) gpp += n * i * (i - 1) * x ** (i - 2) * y ** j;
    if (j !== 0) gt += n * j * x ** i * y ** (j - 1);
    if (j !== 0 && j !== 1) gtt += n * j * (j - 1) * x ** i * y ** (j - 2);
    if (i !== 0 && j !== 0) gpt += n * i * j * x ** (i - 1) * y ** (j - 1);
  }
  return { v: RS * T * gp / 16.53e6, h: RS * T * tau * gt, cp: -RS * tau * tau * gtt, vT: RS / 16.53e6 * (gp - tau * gpt), vp: RS * T * gpp / 16.53e6 ** 2 };
}
// The repository's continuation: held at the triple point and at 623.15 K, volume and enthalpy continued with the held slopes.
function region1(T, p) { const held = Math.max(TRIPLE, Math.min(T, T1)), s = region1raw(held, p); if (held !== T) { s.v += s.vT * (T - held); s.h += s.cp * (T - held); } return s; }
const J0 = [0, 1, -5, -4, -3, -2, -1, 2, 3], N0 = [-0.96927686500217e1, 0.10086655968018e2, -0.56087911283020e-2, 0.71452738081455e-1, -0.40710498223928, 0.14240819171444e1, -0.43839511319450e1, -0.28408632460772, 0.21268463753307e-1];
const R2 = [[1, 0, -0.17731742473213e-2], [1, 1, -0.17834862292358e-1], [1, 2, -0.45996013696365e-1], [1, 3, -0.57581259083432e-1], [1, 6, -0.50325278727930e-1],
  [2, 1, -0.33032641670203e-4], [2, 2, -0.18948987516315e-3], [2, 4, -0.39392777243355e-2], [2, 7, -0.43797295650573e-1], [2, 36, -0.26674547914087e-4],
  [3, 0, 0.20481737692309e-7], [3, 1, 0.43870667284435e-6], [3, 3, -0.32277677238570e-4], [3, 6, -0.15033924542148e-2], [3, 35, -0.40668253562649e-1],
  [4, 1, -0.78847309559367e-9], [4, 2, 0.12790717852285e-7], [4, 3, 0.48225372718507e-6], [5, 7, 0.22922076337661e-5], [6, 3, -0.16714766451061e-10],
  [6, 16, -0.21171472321355e-2], [6, 35, -0.23895741934104e2], [7, 0, -0.59059564324270e-17], [7, 11, -0.12621808899101e-5], [7, 25, -0.38946842435739e-1],
  [8, 8, 0.11256211360459e-10], [8, 36, -0.82311340897998e1], [9, 13, 0.19809712802088e-7], [10, 4, 0.10406965210174e-18], [10, 10, -0.10234747095929e-12],
  [10, 14, -0.10018179379511e-8], [16, 29, -0.80882908646985e-10], [16, 50, 0.10693031879409], [18, 57, -0.33662250574171], [20, 20, 0.89185845355421e-24],
  [20, 35, 0.30629316876232e-12], [20, 48, -0.42002467698208e-5], [21, 21, -0.59056029685639e-25], [22, 53, 0.37826947613457e-5], [23, 39, -0.12768608934681e-14],
  [24, 26, 0.73087610595061e-28], [24, 40, 0.55414715350778e-16], [24, 58, -0.94369707241210e-6]];
function ideal(T) { const tau = 540 / T; let gt = 0, gtt = 0; for (let k = 0; k < 9; k++) { gt += N0[k] * J0[k] * tau ** (J0[k] - 1); gtt += N0[k] * J0[k] * (J0[k] - 1) * tau ** (J0[k] - 2); } return { h: RS * T * tau * gt, cp: -RS * tau * tau * gtt }; }
function region2(T, p) {
  const tau = 540 / T, pi = p / 1e6, s = tau - 0.5; let gp = 0, gt = 0;
  for (const [i, j, n] of R2) { gp += n * i * pi ** (i - 1) * s ** j; if (j !== 0) gt += n * pi ** i * j * s ** (j - 1); }
  return { v: RS * T / p * (1 + pi * gp), h: ideal(T).h + RS * T * tau * gt };
}
// The repository's steam: Region 2's five terms linear in pressure. Molar B and dB/dT.
function virial(T) { const tau = 540 / T, s = tau - 0.5; let f = 0, f1 = 0; for (const [i, j, n] of R2) if (i === 1) { f += n * s ** j; if (j > 0) f1 += n * j * s ** (j - 1); } return { B: RS * T / 1e6 * f * M, B1: RS / 1e6 * (f - tau * f1) * M }; }
const wagner = T => { const th = 1 - T / TC; return PC * Math.exp(TC / T * (-7.85951783 * th + 1.84408259 * th ** 1.5 - 11.7866497 * th ** 3 + 22.6807411 * th ** 3.5 - 15.9618719 * th ** 4 + 1.80122502 * th ** 7.5)); };

// ---- the cubic's pure water, molar ----
const AC = 0.45724 * R * R * TC * TC / PC, BW = 0.07780 * R * TC / PC;
let C = [0.91942, -0.33534, 0.32782];
function attraction(T) { // above the critical temperature the first coefficient alone: value and slope continuous there
  const root = Math.sqrt(T * TC), u = 1 - Math.sqrt(T / TC), u1 = -1 / (2 * root), u2 = 1 / (4 * T * root), n = T > TC ? 1 : C.length; let s = 1, s1 = 0, s2 = 0;
  for (let k = 0; k < n; k++) { s += C[k] * u ** (k + 1); s1 += C[k] * (k + 1) * u ** k; if (k > 0) s2 += C[k] * (k + 1) * k * u ** (k - 1); }
  const sp = s1 * u1, spp = s2 * u1 * u1 + s1 * u2; return { a: AC * s * s, a1: 2 * AC * s * sp, a2: 2 * AC * (sp * sp + s * spp) };
}
function cubic(c2, c1, c0) {
  const p = c1 - c2 * c2 / 3, q = 2 * c2 * c2 * c2 / 27 - c2 * c1 / 3 + c0, s = -c2 / 3, d = q * q / 4 + p * p * p / 27; let r;
  if (d > 0 || p === 0) { const e = Math.sqrt(Math.max(d, 0)); r = [Math.cbrt(-q / 2 + e) + Math.cbrt(-q / 2 - e) + s]; }
  else { const m = 2 * Math.sqrt(-p / 3), th = Math.acos(Math.max(-1, Math.min(1, 3 * q / (p * m)))) / 3; r = [0, 1, 2].map(k => m * Math.cos(th - 2 * Math.PI * k / 3) + s); }
  return r.map(z => { for (let i = 0; i < 4; i++) { const f = ((z + c2) * z + c1) * z + c0, g = (3 * z + 2 * c2) * z + c1; if (g !== 0) z -= f / g; } return z; }).sort((u, v) => u - v);
}
function phaseOf(T, p, a, a1, liquid) {
  const A = a * p / (R * T) ** 2, B = BW * p / (R * T), z = cubic(B - 1, A - 3 * B * B - 2 * B, -(A * B - B * B - B * B * B)).filter(v => v > B);
  const Z = liquid ? z[0] : z[z.length - 1], L = Math.log((Z + (1 + S2) * B) / (Z + (1 - S2) * B));
  return { Z, v: Z * R * T / p, lnphi: Z - 1 - Math.log(Z - B) - A / (2 * S2 * B) * L, hres: R * T * (Z - 1) + (T * a1 - a) / (2 * S2 * BW) * L, two: z.length > 1 && z[z.length - 1] - z[0] > 1e-12 };
}
const phase = (T, p, liquid) => { const s = attraction(T); return phaseOf(T, p, s.a, s.a1, liquid); };
function saturationOf(T, a) {
  let p = wagner(T);
  for (let it = 0; it < 2000; it++) {
    const l = phaseOf(T, p, a, 0, true), v = phaseOf(T, p, a, 0, false);
    if (!l.two) { p *= l.Z < 0.3 ? 0.97 : 1.03; continue; }
    const ratio = Math.exp(l.lnphi - v.lnphi); p *= ratio; if (Math.abs(ratio - 1) < 1e-13) break;
  }
  return p;
}
const saturation = T => saturationOf(T, attraction(T).a);

// ---- the alpha matched to the saturation line: the exact a(T), then a weighted least-squares Mathias-Copeman form ----
function matched(T) { let a = attraction(T).a; const p = wagner(T), f = v => phaseOf(T, p, v, 0, true).lnphi - phaseOf(T, p, v, 0, false).lnphi; for (let it = 0; it < 60; it++) { const e = a * 1e-7, step = f(a) / ((f(a + e) - f(a - e)) / (2 * e)); a -= step; if (Math.abs(step) < 1e-13 * a) break; } return a; }
function solve(m, y) { const n = y.length; for (let i = 0; i < n; i++) { let best = i; for (let r = i + 1; r < n; r++) if (Math.abs(m[r][i]) > Math.abs(m[best][i])) best = r; [m[i], m[best]] = [m[best], m[i]]; [y[i], y[best]] = [y[best], y[i]]; for (let r = i + 1; r < n; r++) { const f = m[r][i] / m[i][i]; for (let c = i; c < n; c++) m[r][c] -= f * m[i][c]; y[r] -= f * y[i]; } } const x = Array(n).fill(0); for (let i = n - 1; i >= 0; i--) { let s = y[i]; for (let c = i + 1; c < n; c++) s -= m[i][c] * x[c]; x[i] = s / m[i][i]; } return x; }
const FIT = []; for (let T = 273.15; T <= 643.151; T += 5) FIT.push(T);
function fitAlpha(terms) {
  const rows = FIT.map(T => { const a = matched(T), s = Math.sqrt(a / AC), u = 1 - Math.sqrt(T / TC), w = Math.abs(Math.log(saturationOf(T, AC * (s + 1e-5) ** 2) / saturationOf(T, AC * (s - 1e-5) ** 2)) / 2e-5); return { u, y: s - 1, w }; });
  const m = Array.from({ length: terms }, () => Array(terms).fill(0)), y = Array(terms).fill(0);
  for (const r of rows) for (let i = 0; i < terms; i++) { y[i] += r.w * r.w * r.u ** (i + 1) * r.y; for (let j = 0; j < terms; j++) m[i][j] += r.w * r.w * r.u ** (i + 1) * r.u ** (j + 1); }
  return solve(m, y);
}
const worst = () => { let w = 0, at = 0; for (const T of FIT) { const e = Math.abs(saturation(T) / wagner(T) - 1); if (e > w) { w = e; at = T; } } return `${(100 * w).toFixed(3)} % at ${(at - 273.15).toFixed(0)} C`; };
const pct = (v, ref, d = 2) => `${(100 * (v / ref - 1)).toFixed(d)} %`, C2K = t => t + 273.15;

// ---- the model: water's own share ----
const RATIO_DENSE = Number(process.env.RATIO_DENSE ?? 2.6), RATIO_GAS = Number(process.env.RATIO_GAS ?? 3.6);
const smooth = t => t <= 0 ? 0 : t >= 1 ? 1 : t * t * t * (10 + t * (6 * t - 15));
const liquidWeight = ratio => 1 - smooth((ratio - RATIO_DENSE) / (RATIO_GAS - RATIO_DENSE));
function liquidGap(T, p) { const pe = Math.max(p, wagner(T)), r1 = region1(T, pe), cub = phase(T, pe, true); return { h: r1.h * M - (ideal(T).h * M + cub.hres), v: r1.v * M - cub.v }; }
function liquidShare(T, p) { // molar; zero from the critical temperature on
  if (T >= TC) return { h: 0, v: 0 }; if (T <= T1) return liquidGap(T, p);
  const c0 = liquidGap(T1, p), cm = liquidGap(T1 - 0.05, p), fade = 1 - smooth((T - T1) / (TC - T1)), d = T - T1;
  return { h: (c0.h + (c0.h - cm.h) / 0.05 * d) * fade, v: (c0.v + (c0.v - cm.v) / 0.05 * d) * fade };
}
function virialGap(T) { const s = attraction(T), w = virial(T); return { B: w.B - (BW - s.a / (R * T)), D: w.B - T * w.B1 - (BW - 2 * s.a / (R * T) + s.a1 / R) }; }
// Pure water at one root, per kg. mode: 'cubic' (alone), 'liquid' (the liquid share only), 'both' (and the gas's second virial coefficient).
function water(T, p, liquid, mode = 'both') {
  const ph = phase(T, p, liquid), id = ideal(T), w = mode === 'cubic' ? 0 : liquidWeight(ph.v / BW), ls = w > 0 ? liquidShare(T, p) : { h: 0, v: 0 }, g = mode === 'both' ? virialGap(T) : { B: 0, D: 0 };
  return { h: (id.h * M + ph.hres + w * ls.h + (mode === 'cubic' ? 0 : 1 - w) * p * g.D) / M, v: (ph.v + w * ls.v + (mode === 'cubic' ? 0 : 1 - w) * g.B) / M, lnphi: ph.lnphi, ratio: ph.v / BW, two: ph.two };
}
function stable(T, p, mode) { const l = water(T, p, true, mode); if (!l.two) return { ...l, liquid: null }; const v = water(T, p, false, mode); return l.lnphi < v.lnphi ? { ...l, liquid: true } : { ...v, liquid: false }; }

// ---- 1. the tables against the release's test values ----
{ const a = region1(300, 3e6), b = region2(300, 3500), c = region2(700, 30e6);
  console.log(`IF97 check: Region 1 at 300 K, 3 MPa v ${a.v.toPrecision(9)} (0.00100215168), h ${(a.h / 1000).toPrecision(9)} (115.331273); Region 2 at 300 K, 3.5 kPa v ${b.v.toPrecision(9)} (39.4913866), h ${(b.h / 1000).toPrecision(9)} (2549.91145); at 700 K, 30 MPa v ${c.v.toPrecision(9)} (0.00542946619), h ${(c.h / 1000).toPrecision(9)} (2631.49474)`); }

// ---- 2. the alpha ----
for (const terms of [3, 5]) { C = fitAlpha(terms); console.log(`Mathias-Copeman, ${terms} coefficients ${C.map(v => v.toFixed(5)).join(', ')}: saturation against Wagner-Pruss over 0..370 C, worst ${worst()}; at 78 C ${pct(saturation(351.15), wagner(351.15), 3)}, 100 C ${pct(saturation(373.15), wagner(373.15), 3)}, -30 C ${pct(saturation(243.15), wagner(243.15), 2)}`); }
C = fitAlpha(TERMS); console.log(`used below: ${TERMS} coefficients`);

// ---- 3. steam ----
console.log('\nsteam, error against IF97 Region 2: volume %, enthalpy kJ/kg  [cubic alone | cubic with IF97\'s second virial coefficient | today\'s virial steam]');
const steamRow = (label, T, p) => { const t = region2(T, p), id = ideal(T), w = virial(T), c = water(T, p, false, 'cubic'), b = water(T, p, false, 'both'), vv = (R * T / p + w.B) / M, vh = id.h + p * (w.B - T * w.B1) / M;
  const cell = (v, h) => `${(100 * (v / t.v - 1)).toFixed(2).padStart(6)} %, ${((h - t.h) / 1000).toFixed(1).padStart(6)}`; console.log(`  ${label.padEnd(22)} ${cell(c.v, c.h)} | ${cell(b.v, b.h)} | ${cell(vv, vh)}`); };
for (const t of [25, 100, 150, 200, 250, 300, 350]) steamRow(`saturated ${t} C`, C2K(t), wagner(C2K(t)));
for (const [t, mpa] of [[200, 0.1], [300, 1], [400, 4], [450, 6], [500, 10], [550, 15], [600, 20]]) steamRow(`${t} C, ${mpa} MPa`, C2K(t), mpa * 1e6);

// ---- 4. pure water on its saturation line ----
console.log('\npure water at saturation, error against IF97: pressure | heat of vaporization: cubic alone, liquid share only, both shares, today (Wagner-Pruss, Region 1, virial) | liquid density: cubic alone, with the share');
for (const t of [0.01, 25, 50, 100, 150, 200, 250, 300, 350]) {
  const T = C2K(t), pw = wagner(T), ps = saturation(T), truth = region2(T, pw).h - region1(T, pw).h, hfg = mode => water(T, ps, false, mode).h - water(T, ps, true, mode).h, w = virial(T), today = ideal(T).h + pw * (w.B - T * w.B1) / M - region1(T, pw).h;
  console.log(`  ${String(t).padStart(5)} C: ${pct(ps, pw, 3).padStart(9)} | ${pct(hfg('cubic'), truth).padStart(8)}, ${pct(hfg('liquid'), truth).padStart(8)}, ${pct(hfg('both'), truth).padStart(8)}, ${pct(today, truth).padStart(8)} | ${pct(1 / water(T, ps, true, 'cubic').v, 1 / region1(T, pw).v, 1).padStart(8)}, ${pct(1 / water(T, ps, true, 'both').v, 1 / region1(T, pw).v, 3)}`);
}

// ---- 5. the two shares' sizes and the cubic liquid's v/b ----
console.log('\nthe liquid share (Region 1 - cubic liquid) at saturation and 10 MPa above: enthalpy kJ/kg, volume % of the liquid | cubic liquid v/b | second virial gap: B cm3/mol (IF97, cubic), B - T dB/dT gap cm3/mol');
for (const t of [0.01, 25, 100, 200, 300, 350, 360, 370, 373.5]) {
  const T = C2K(t), pw = wagner(T), s = liquidShare(T, pw), s10 = liquidShare(T, pw + 10e6), g = virialGap(T), w = virial(T);
  console.log(`  ${String(t).padStart(5)} C: ${(s.h / M / 1000).toFixed(1).padStart(6)}, ${(100 * s.v / (region1(T, pw).v * M)).toFixed(1).padStart(6)} %; +10 MPa ${(s10.h / M / 1000).toFixed(1).padStart(6)}, ${(100 * s10.v / (region1(T, pw + 10e6).v * M)).toFixed(1).padStart(6)} % | ${(phase(T, pw, true).v / BW).toFixed(2)} | ${(w.B * 1e6).toFixed(0)}, ${((w.B - g.B) * 1e6).toFixed(0)}, ${(g.D * 1e6).toFixed(0)}`);
}
for (const t of [400, 500, 600, 800]) { const T = C2K(t), g = virialGap(T), w = virial(T); console.log(`  ${String(t).padStart(5)} C: second virial ${(w.B * 1e6).toFixed(1)}, ${((w.B - g.B) * 1e6).toFixed(1)}, gap of B - T dB/dT ${(g.D * 1e6).toFixed(1)}`); }

// ---- 6. continuity and stability of pure water with both shares ----
function scan(label, points, at, slopeSign) { // at(k) -> { x, y, liquid }; reports the slope's range and isolated spikes outside the phase change
  let last = null, lastSlope = null, min = Infinity, max = -Infinity, minAt = 0, changes = 0, spikes = 0; const slopes = [];
  for (let k = 0; k < points; k++) {
    const s = at(k);
    if (last) {
      const change = last.liquid !== null && s.liquid !== null && last.liquid !== s.liquid;
      if (change) { changes++; slopes.push(null); } else { const slope = (s.y - last.y) / (s.x - last.x); slopes.push(slope); if (slope < min) { min = slope; minAt = s.x; } if (slope > max) max = slope; }
    }
    last = s;
  }
  for (let k = 1; k + 1 < slopes.length; k++) { const a = slopes[k - 1], b = slopes[k], c = slopes[k + 1]; if (a === null || b === null || c === null) continue; if (Math.abs(b) > 3 * Math.max(Math.abs(a), Math.abs(c)) || Math.sign(b) !== Math.sign(a) && Math.sign(b) !== Math.sign(c)) spikes++; }
  return { label, min, max, minAt, changes, spikes, bad: slopeSign > 0 ? min <= 0 : max >= 0 };
}
console.log('\nisobars 0 to 600 C in 0.25 K steps, stable root: c_p kJ/(kg K) min .. max (cubic alone min), phase changes, isolated spikes');
for (const mpa of [0.101325, 1, 5, 10, 16, 18, 20, 21.5, 22, 22.5, 23, 25, 30, 40]) {
  const run = mode => scan(`${mpa} MPa`, 2401, k => { const T = 273.15 + 0.25 * k, s = stable(T, mpa * 1e6, mode); return { x: T, y: s.h / 1000, liquid: s.liquid }; }, 1), m = run('both'), c = run('cubic');
  console.log(`  ${String(mpa).padStart(8)} MPa: ${m.min.toFixed(3)} at ${(m.minAt - 273.15).toFixed(1)} C .. ${m.max.toFixed(1)} (${c.min.toFixed(3)}), ${m.changes}, ${m.spikes} (cubic alone ${c.spikes})${m.bad ? '   NOT POSITIVE' : ''}`);
}
console.log('isotherms 0.05 to 40 MPa in 0.01 MPa steps, stable root: dv/dp max (must be negative), phase changes, isolated spikes; and d h/dp range kJ/(kg MPa)');
for (const t of [25, 200, 300, 340, 349, 351, 355, 360, 365, 370, 373, 374.5, 380, 400, 450, 500]) {
  const T = C2K(t), state = k => stable(T, 0.05e6 + 0.01e6 * k, 'both'), v = scan('', 3996, k => { const s = state(k); return { x: 0.05 + 0.01 * k, y: s.v, liquid: s.liquid }; }, -1), h = scan('', 3996, k => { const s = state(k); return { x: 0.05 + 0.01 * k, y: s.h / 1000, liquid: s.liquid }; }, 0);
  console.log(`  ${String(t).padStart(6)} C: ${v.max.toExponential(2)}, ${v.changes}, ${v.spikes + h.spikes}; ${h.min.toFixed(2)} .. ${h.max.toFixed(2)}${v.bad ? '   NOT NEGATIVE' : ''}`);
}

// ---- 7. a mixture through its critical region: CO2 with water as one fluid phase (classical rule, k_ij 0), where the phase's label flips ----
const CO2 = { tc: 304.13, pc: 7.3773e6, w: 0.22394, cp: 37.1 };
function mixture(T, p, xw, mode) { // per mole of mixture
  const k = 0.37464 + 1.54226 * CO2.w - 0.26992 * CO2.w * CO2.w, ac = 0.45724 * R * R * CO2.tc * CO2.tc / CO2.pc, sc = 1 + k * (1 - Math.sqrt(T / CO2.tc)), a2 = ac * sc * sc, d2 = -ac * sc * k / Math.sqrt(T * CO2.tc), b2 = 0.07780 * R * CO2.tc / CO2.pc;
  const w = attraction(T), xc = 1 - xw, cross = Math.sqrt(w.a * a2), a = xw * xw * w.a + 2 * xw * xc * cross + xc * xc * a2, da = xw * xw * w.a1 + xw * xc * (w.a1 * a2 + w.a * d2) / cross + xc * xc * d2, b = xw * BW + xc * b2;
  const A = a * p / (R * T) ** 2, B = b * p / (R * T), z = cubic(B - 1, A - 3 * B * B - 2 * B, -(A * B - B * B - B * B * B)).filter(v => v > B), L = Z => Math.log((Z + (1 + S2) * B) / (Z + (1 - S2) * B)), g = Z => Z - 1 - Math.log(Z - B) - A / (2 * S2 * B) * L(Z);
  const two = z.length > 1 && z[z.length - 1] - z[0] > 1e-12, liquid = two ? g(z[0]) < g(z[z.length - 1]) : null, Z = liquid === false ? z[z.length - 1] : z[0], v = Z * R * T / p, ratio = v / b;
  const weight = mode === 'cubic' ? 0 : liquidWeight(ratio), ls = weight > 0 ? liquidShare(T, p) : { h: 0, v: 0 }, gap = mode === 'cubic' ? { B: 0, D: 0 } : virialGap(T), gas = mode === 'cubic' ? 0 : 1 - weight;
  return { h: xw * ideal(T).h * M + xc * CO2.cp * (T - 298.15) + R * T * (Z - 1) + (T * da - a) / (2 * S2 * b) * L(Z) + xw * weight * ls.h + xw * xw * gas * p * gap.D, v: v + xw * weight * ls.v + xw * xw * gas * gap.B, ratio, liquid };
}
console.log('CO2 with water as one fluid phase, 0 to 150 C in 0.1 K steps: v/b range | c_p J/(mol K) min .. max (cubic alone min) | dv/dT min (must be positive) | phase changes, isolated spikes (cubic alone)');
for (const [xw, mpa] of [[0.02, 8], [0.02, 12], [0.3, 12], [0.3, 20], [0.7, 25]]) {
  let lo = Infinity, hi = -Infinity; const run = (mode, key) => scan('', 1501, k => { const T = 273.15 + 0.1 * k, s = mixture(T, mpa * 1e6, xw, mode); lo = Math.min(lo, s.ratio); hi = Math.max(hi, s.ratio); return { x: T, y: s[key], liquid: s.liquid }; }, 1);
  const m = run('both', 'h'), c = run('cubic', 'h'), v = run('both', 'v');
  console.log(`  water ${xw}, ${mpa} MPa: ${lo.toFixed(2)} .. ${hi.toFixed(2)} | ${m.min.toFixed(1)} .. ${m.max.toFixed(1)} (${c.min.toFixed(1)}) | ${v.min.toExponential(2)} | ${m.changes}, ${m.spikes} (${c.spikes})${m.bad || v.bad ? '   WRONG SIGN' : ''}`);
}
