// Gases in organic liquids at 25 C and 101.325 kPa of the gas: PR78 with the classical rule, k_ij = 0 against the
// group-contribution k_ij(T) of E-PPR78 (the repository's table). Usage: node gas-in-organics.mjs <eppr78_2022.json>
import { readFileSync } from 'node:fs';
const table = JSON.parse(readFileSync(process.argv[2], 'utf8')), R = 8.31446261815324, S2 = Math.SQRT2, T = 298.15, P = 101325;
const AB = {};
for (const p of table.pairs) { AB[`${p.first}-${p.second}`] = [p.a_mpa * 1e6, p.b_mpa * 1e6]; AB[`${p.second}-${p.first}`] = [p.a_mpa * 1e6, p.b_mpa * 1e6]; }
const kappa = w => w <= 0.491 ? 0.37464 + 1.54226 * w - 0.26992 * w * w : 0.379642 + 1.48503 * w - 0.164423 * w * w + 0.016666 * w * w * w;
const COMP = {
  hexane: { tc: 507.6, pc: 3.025e6, w: 0.3013, groups: { CH3: 2, CH2: 4 } },
  ethanol: { tc: 514.71, pc: 6.268e6, w: 0.644, groups: { CH3: 1, CH2: 1 }, others: 1 }, // the OH has no group in the scheme
  nitrogen: { tc: 126.19, pc: 3.3958e6, w: 0.0372, groups: { N2: 1 } },
  methane: { tc: 190.564, pc: 4.5992e6, w: 0.01142, groups: { CH4: 1 } },
  co2: { tc: 304.13, pc: 7.3773e6, w: 0.22394, groups: { CO2: 1 } },
};
const a = n => { const c = COMP[n], s = 1 + kappa(c.w) * (1 - Math.sqrt(T / c.tc)); return 0.45724 * R * R * c.tc * c.tc / c.pc * s * s; }, b = n => 0.07780 * R * COMP[n].tc / COMP[n].pc;
function eppr78(i, j) {
  const frac = n => { const g = COMP[n].groups, total = Object.values(g).reduce((s, v) => s + v, 0) + (COMP[n].others ?? 0); return Object.fromEntries(Object.entries(g).map(([k, v]) => [k, v / total])); };
  const fi = frac(i), fj = frac(j), ids = [...new Set([...Object.keys(fi), ...Object.keys(fj)])]; let sum = 0;
  for (const k of ids) for (const l of ids) { if (k === l) continue; const ab = AB[`${k}-${l}`]; if (!ab || ab[0] === 0) continue; sum += ((fi[k] ?? 0) - (fj[k] ?? 0)) * ((fi[l] ?? 0) - (fj[l] ?? 0)) * ab[0] * (298.15 / T) ** (ab[1] / ab[0] - 1); }
  const di = Math.sqrt(a(i)) / b(i), dj = Math.sqrt(a(j)) / b(j); return (-0.5 * sum - (di - dj) ** 2) / (2 * di * dj);
}
function cubic(c2, c1, c0) {
  const p = c1 - c2 * c2 / 3, q = 2 * c2 * c2 * c2 / 27 - c2 * c1 / 3 + c0, s = -c2 / 3, d = q * q / 4 + p * p * p / 27; let r;
  if (d > 0 || p === 0) { const e = Math.sqrt(Math.max(d, 0)); r = [Math.cbrt(-q / 2 + e) + Math.cbrt(-q / 2 - e) + s]; }
  else { const m = 2 * Math.sqrt(-p / 3), th = Math.acos(Math.max(-1, Math.min(1, 3 * q / (p * m)))) / 3; r = [0, 1, 2].map(k => m * Math.cos(th - 2 * Math.PI * k / 3) + s); }
  return r.map(z => { for (let i = 0; i < 4; i++) { const f = ((z + c2) * z + c1) * z + c0, g = (3 * z + 2 * c2) * z + c1; if (g !== 0) z -= f / g; } return z; }).sort((u, v) => u - v);
}
// Mole fraction of the gas dissolved in the pure liquid solvent: 1 / phi of the gas at infinite dilution (ideal vapour).
function dissolved(gas, solvent, k) {
  const as = a(solvent), bs = b(solvent), A = as * P / (R * T) ** 2, B = bs * P / (R * T), Z = cubic(-(1 - B), A - 3 * B * B - 2 * B, -(A * B - B * B - B * B * B)).filter(z => z > B)[0];
  const cross = Math.sqrt(a(gas) * as) * (1 - k);
  return Math.exp(-(b(gas) / bs * (Z - 1) - Math.log(Z - B) - A / (2 * S2 * B) * (2 * cross / as - b(gas) / bs) * Math.log((Z + (1 + S2) * B) / (Z + (1 - S2) * B))));
}
const MEASURED = { 'nitrogen/hexane': 1.4e-3, 'methane/hexane': 5.0e-3, 'co2/hexane': 1.2e-2, 'nitrogen/ethanol': 3.6e-4, 'methane/ethanol': 1.3e-3, 'co2/ethanol': 6.4e-3 };
console.log('gas / solvent: k_ij 0 -> x (ratio to measured); E-PPR78 k_ij -> x (ratio); measured');
for (const [pair, measured] of Object.entries(MEASURED)) {
  const [gas, solvent] = pair.split('/'), x0 = dissolved(gas, solvent, 0), k = eppr78(gas, solvent), xk = dissolved(gas, solvent, k);
  console.log(`  ${pair.padEnd(17)} ${x0.toExponential(2)} (${(x0 / measured).toFixed(2)});  k ${k.toFixed(3)} -> ${xk.toExponential(2)} (${(xk / measured).toFixed(2)});  ${measured.toExponential(1)}${solvent === 'ethanol' ? '   (alkyl groups only: the scheme has no OH)' : ''}`);
}
// What k_ij the measured value needs.
for (const [pair, measured] of Object.entries(MEASURED)) {
  const [gas, solvent] = pair.split('/'); let lo = -1, hi = 1; for (let i = 0; i < 80; i++) { const m = 0.5 * (lo + hi); if (dissolved(gas, solvent, m) > measured) lo = m; else hi = m; }
  console.log(`  ${pair.padEnd(17)} measured needs k_ij ${(0.5 * (lo + hi)).toFixed(3)}`);
}
// A polar organic liquid has no group for its OH in the scheme. Candidate: the k_ij of the gas with the liquid's
// hydrocarbon homomorph (OH read as CH3: ethanol as propane), used with the liquid itself.
COMP.propane = { tc: 369.83, pc: 4.248e6, w: 0.1523, groups: { CH3: 2, CH2: 1 } };
console.log('gas in ethanol with the k_ij of its homomorph (propane):');
for (const gas of ['nitrogen', 'methane', 'co2']) { const k = eppr78(gas, 'propane'), x = dissolved(gas, 'ethanol', k), m = MEASURED[`${gas}/ethanol`]; console.log(`  ${gas.padEnd(9)} k ${k.toFixed(3)} -> ${x.toExponential(2)} (${(x / m).toFixed(2)})`); }
// The authored library (dwsim_refinery_pr78) gives these three pairs with n-hexane; pairs it does not give are zero today.
console.log('gas in n-hexane with the authored library k_ij:');
for (const [gas, k] of [['nitrogen', 0.1496], ['methane', 0.04], ['co2', 0.11]]) { const x = dissolved(gas, 'hexane', k), m = MEASURED[`${gas}/hexane`]; console.log(`  ${gas.padEnd(9)} k ${k.toFixed(3)} -> ${x.toExponential(2)} (${(x / m).toFixed(2)})`); }
