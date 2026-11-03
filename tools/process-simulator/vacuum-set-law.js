// The vacuum set's steam law (SIZING_PLAN.md section 3.12, proposal W10b) against El-Dessouky's single stage, and four cases.
// Pressures in kPa, flows in kg/h. Run from the worktree root: node tools/process-simulator/vacuum-set-law.js
const PCF = pm => { const p = Math.min(pm, 1500); return 3e-7 * p * p - 9e-4 * p + 1.6101; };
const TCF = t => 2e-8 * t * t - 6e-4 * t + 1.0047;
// El-Dessouky as SIZING_PLAN 3.12: steam per kg of load, water basis, then sqrt(18.015/M).
const elDessouky = (ps, pd, pm, tC, m) => 0.296 * Math.pow(pd, 1.19) / Math.pow(ps, 1.04) * Math.pow(pm / ps, 0.015) * PCF(pm) / TCF(tC) * Math.sqrt(18.015 / m);
// Water's vapour pressure (Antoine, 1-100 C), kPa.
const psat = tC => Math.pow(10, 8.07131 - 1730.63 / (233.426 + tC)) * 0.133322;
const K = 1.6; // kg steam per kg air-equivalent per e-fold, El-Dessouky's air figure at a stage of 4 (30 -> 120 kPa)
const airEq = (kg, m) => kg * Math.sqrt(29 / m);
// The set: gas lifted from the vent to the discharge; water lifted only to twice its vapour pressure at the vent's temperature.
function set(gas, water, ps, tC, pd = 120, pm = 1000) {
  const pc = 2 * psat(tC);
  const g = gas.reduce((s, [kg, m]) => s + airEq(kg, m), 0), w = airEq(water, 18.015);
  const gasTerm = g * Math.log(pd / ps), waterTerm = w * Math.max(0, Math.log(pc / ps));
  return { pc, g, w, gasTerm, waterTerm, steam: K * PCF(pm) / PCF(1000) * (gasTerm + waterTerm), oneTerm: K * PCF(pm) / PCF(1000) * (g + w) * Math.log(pd / ps) };
}
const f = (x, d = 2) => x.toFixed(d);
console.log('k check: El-Dessouky air at 30 -> 120 kPa', f(elDessouky(30, 120, 1000, 50, 29), 3), 'per e-fold', f(elDessouky(30, 120, 1000, 50, 29) / Math.log(4), 3));
console.log('\nPs  CR    single stage air (today)   set air (proposal)');
for (const ps of [80, 50, 30, 20, 12, 5, 2]) console.log(String(ps).padEnd(4), f(120 / ps, 1).padEnd(5), f(elDessouky(ps, 120, 1000, 50, 29)).padEnd(26), f(K * Math.log(120 / ps)));
// A three-stage chain on El-Dessouky with an intercondenser after each stage (gas only), 5 -> 120 kPa.
let chain = 0, p = 5; const r = Math.pow(120 / 5, 1 / 3);
for (let i = 0; i < 3; i++) { chain += elDessouky(p, p * r, 1000, 50, 29); p *= r; }
console.log('\nthree-stage chain, air, 5 -> 120 kPa:', f(chain), 'against the set law', f(K * Math.log(24)));
console.log('booster check, water 2 -> 14.8 kPa single stage:', f(elDessouky(2, 2 * psat(40), 1000, 40, 18.015)), 'kg/kg; set law', f(K * Math.sqrt(29 / 18.015) * Math.log(2 * psat(40) / 2)));
console.log('\npsat(40 C) =', f(psat(40), 3), 'kPa, Pc =', f(2 * psat(40), 2), 'kPa');
console.log('motive factor: 3 bar', f(PCF(300) / PCF(1000)), ', 5 bar', f(PCF(500) / PCF(1000)), ', 7 bar', f(PCF(700) / PCF(1000)), ', 15 bar and up', f(PCF(1500) / PCF(1000)));
// Cases, vent at 40 C.
const satWater = (gasKmol, ps, tC) => gasKmol * psat(tC) / (ps - psat(tC)) * 18.015; // kg/h of water vapour saturating the gas
const cases = [
  ['1 chemical column, 30 kPa, 50 kg/h N2', [[50, 28]], satWater(50 / 28, 30, 40), 30],
  ['2 column at 80 kPa, 100 kg/h of M 29 gas', [[100, 29]], satWater(100 / 29, 80, 40), 80],
  ['3 VDU case A, 10.6 kPa, 500 kg/h gas M 30', [[500, 30]], satWater(500 / 30, 10.6, 40), 10.6],
  ['4 deep cut, 2 kPa, 10 t/h steam passes, 500 kg/h gas', [[500, 30]], 10000, 2],
];
console.log('\ncase                                                  water kg/h  G     W      gas term  water term  steam kg/h  one-term');
for (const [name, gas, water, ps] of cases) {
  const s = set(gas, water, ps, 40);
  console.log(name.padEnd(54), f(water, 0).padEnd(11), f(s.g, 0).padEnd(5), f(s.w, 0).padEnd(6), f(s.gasTerm, 0).padEnd(9), f(s.waterTerm, 0).padEnd(11), f(s.steam, 0).padEnd(11), f(s.oneTerm, 0));
}
