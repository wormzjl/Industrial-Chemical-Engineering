// Water as a PR78 compound with a Mathias-Copeman alpha (fitted to the Wagner and Pruss saturation line, 0 to 350 C)
// and one constant volume translation (anchored at 25 C), against IAPWS-95 saturation data and one steam state.
const R = 8.31446261815324, S2 = Math.SQRT2, TC = 647.096, PC = 22.064e6, M = 0.01801528;
let c = [0.8726, 0, 0];
const alpha = T => { const u = 1 - Math.sqrt(T / TC), s = 1 + c[0] * u + c[1] * u * u + c[2] * u * u * u; return s * s; };
const a = T => 0.45724 * R * R * TC * TC / PC * alpha(T), b = 0.07780 * R * TC / PC;
function cubic(c2, c1, c0) {
  const p = c1 - c2 * c2 / 3, q = 2 * c2 * c2 * c2 / 27 - c2 * c1 / 3 + c0, s = -c2 / 3, d = q * q / 4 + p * p * p / 27; let r;
  if (d > 0 || p === 0) { const e = Math.sqrt(Math.max(d, 0)); r = [Math.cbrt(-q / 2 + e) + Math.cbrt(-q / 2 - e) + s]; }
  else { const m = 2 * Math.sqrt(-p / 3), th = Math.acos(Math.max(-1, Math.min(1, 3 * q / (p * m)))) / 3; r = [0, 1, 2].map(k => m * Math.cos(th - 2 * Math.PI * k / 3) + s); }
  return r.map(z => { for (let i = 0; i < 4; i++) { const f = ((z + c2) * z + c1) * z + c0, g = (3 * z + 2 * c2) * z + c1; if (g !== 0) z -= f / g; } return z; }).sort((u, v) => u - v);
}
const roots = (T, p) => { const A = a(T) * p / (R * T) ** 2, B = b * p / (R * T); return { A, B, z: cubic(-(1 - B), A - 3 * B * B - 2 * B, -(A * B - B * B - B * B * B)).filter(v => v > B) }; };
function saturation(T) {
  let p = PC * Math.exp(5.373 * 1.5 * (1 - TC / T));
  for (let it = 0; it < 400; it++) {
    const { A, B, z } = roots(T, p); if (z.length < 2) { p *= 0.7; continue; }
    const ln = Z => Z - 1 - Math.log(Z - B) - A / (2 * S2 * B) * Math.log((Z + (1 + S2) * B) / (Z + (1 - S2) * B)), r = Math.exp(ln(z[0]) - ln(z[z.length - 1]));
    p *= r; if (Math.abs(r - 1) < 1e-13) break;
  }
  return p;
}
function residualEnthalpy(T, p, liquid) {
  const { B, z } = roots(T, p), Z = liquid ? z[0] : z[z.length - 1], dadT = (a(T + 0.01) - a(T - 0.01)) / 0.02;
  return R * T * (Z - 1) + (T * dadT - a(T)) / (2 * S2 * b) * Math.log((Z + (1 + S2) * B) / (Z + (1 - S2) * B));
}
const volume = (T, p, liquid) => { const { z } = roots(T, p); return (liquid ? z[0] : z[z.length - 1]) * R * T / p; };
function nelderMead(fn, start, step, iterations) {
  const n = start.length; let pts = [start, ...start.map((_, i) => start.map((v, j) => v + (i === j ? step[j] : 0)))].map(p => ({ p, f: fn(p) }));
  for (let it = 0; it < iterations; it++) {
    pts.sort((u, v) => u.f - v.f); const cen = start.map((_, j) => pts.slice(0, n).reduce((t, q) => t + q.p[j], 0) / n), worst = pts[n];
    const tryAt = t => { const p = cen.map((v, j) => v + t * (worst.p[j] - v)); return { p, f: fn(p) }; }, refl = tryAt(-1);
    if (refl.f < pts[0].f) { const ex = tryAt(-2); pts[n] = ex.f < refl.f ? ex : refl; } else if (refl.f < pts[n - 1].f) pts[n] = refl;
    else { const con = tryAt(0.5); if (con.f < worst.f) pts[n] = con; else pts = pts.map((q, k) => k === 0 ? q : (() => { const p = q.p.map((v, j) => 0.5 * (v + pts[0].p[j])); return { p, f: fn(p) }; })()); }
  }
  pts.sort((u, v) => u.f - v.f); return pts[0];
}
const wagner = T => { const th = 1 - T / TC; return PC * Math.exp(TC / T * (-7.85951783 * th + 1.84408259 * th ** 1.5 - 11.7866497 * th ** 3 + 22.6807411 * th ** 3.5 - 15.9618719 * th ** 4 + 1.80122502 * th ** 7.5)); };
const grid = []; for (let T = 273.15; T <= 623.151; T += 10) grid.push(T);
c = nelderMead(v => { c = v; return grid.reduce((s, T) => s + (saturation(T) / wagner(T) - 1) ** 2, 0); }, [0.9, -0.3, 0.2], [0.05, 0.1, 0.1], 600).p;
console.log('Mathias-Copeman c =', c.map(v => v.toFixed(5)).join(', '), '; saturation error over 0..350 C, worst', (100 * Math.max(...grid.map(T => Math.abs(saturation(T) / wagner(T) - 1)))).toFixed(3), '%');
const shift = volume(298.15, saturation(298.15), true) - M / 997.05, cpIdeal = T => 33.60 + (T - 300) * (T < 400 ? 0.0066 : T < 500 ? 0.0097 : 0.0109) + (T < 400 ? 0 : T < 500 ? 0.66 - 0.97 : 1.63 - 2.18 + 0.0);
// T C, liquid kg/m3, vapour kg/m3, heat of vaporization kJ/kg, liquid cp kJ/kg K (IAPWS-95 saturation)
const REF = [[25, 997.05, 0.02307, 2441.7, 4.1813], [100, 958.35, 0.5982, 2256.4, 4.2157], [200, 864.66, 7.861, 1939.7, 4.4958], [300, 712.14, 46.17, 1404.6, 5.7504]];
console.log('T C: liquid density, vapour density, heat of vaporization, liquid heat capacity (error against IAPWS-95)');
const pct = (v, ref) => `${(100 * (v / ref - 1)).toFixed(1)} %`;
for (const [t, rl, rv, hfg, cp] of REF) {
  const T = t + 273.15, p = saturation(T), dl = M / (volume(T, p, true) - shift), dv = M / (volume(T, p, false) - shift), h = (residualEnthalpy(T, p, false) - residualEnthalpy(T, p, true)) / M / 1000;
  const cpl = (cpIdeal(T) + (residualEnthalpy(T + 0.5, p * 1.02, true) - residualEnthalpy(T - 0.5, p * 1.02, true))) / M / 1000;
  console.log(`  ${String(t).padStart(3)}: ${dl.toFixed(1)} (${pct(dl, rl)}), ${dv.toPrecision(4)} (${pct(dv, rv)}), ${h.toFixed(1)} (${pct(h, hfg)}), ${cpl.toFixed(3)} (${pct(cpl, cp)})`);
}
// Steam at 500 C and 10 MPa against liquid at 25 C: IF97 3375.1 - 104.8 kJ/kg; ideal-gas rise 298.15 -> 773.15 K 16.98 kJ/mol (NIST).
const rise = (16980 + residualEnthalpy(773.15, 10e6, false) - residualEnthalpy(298.15, saturation(298.15), true)) / M / 1000;
console.log(`steam at 500 C, 10 MPa above liquid at 25 C: ${rise.toFixed(1)} kJ/kg (IF97 3270.3, ${pct(rise, 3270.3)}); its density ${(M / (volume(773.15, 10e6, false) - shift)).toFixed(2)} kg/m3 (IF97 30.48)`);
