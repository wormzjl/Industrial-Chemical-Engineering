// Independent check of the energies the compat corpus loads book after O2: n * [h_new(T) - h_old(T)] per compound,
// ideal-gas parts only (the only data that moved at these states). The old records are read from git at the batch's
// parent commit 03e7b6138. Vessel states and amounts are those FluidFactsCompatTest prints (printRestated).
// Run from this folder: node compat-energy.mjs
import { execFileSync } from 'node:child_process';
import * as L from './lib.mjs';
const PARENT = '03e7b6138', PATHS = { nitrogen: 'properties/nitrogen.json', methane: 'properties/tjl20_methane.json', water: 'water/water.json' };
const old = n => JSON.parse(execFileSync('git', ['-C', L.WORKTREE, 'show', `${PARENT}:src/main/resources/data/icheme/materials/${PATHS[n]}`], { encoding: 'utf8' }));
const cur = f => L.readJson(`${L.MATERIALS}/${f}`);
const integ = (c, lo) => T => { // sensible enthalpy of a shifted_polynomial_5 from 298.15 K, Cp held below lo
  const P = x => c.reduce((s, a, k) => s + a * x ** (k + 1) / (k + 1), 0), cp = L.shiftedPolynomial(c);
  if (T >= lo) return P(T - 298.15);
  return P(lo - 298.15) + cp(lo) * (T - lo);
};
const n2o = integ(old('nitrogen').ideal_gas_cp.coefficients, 273.16), n2n = integ(cur('properties/nitrogen.json').ideal_gas_cp.coefficients, 273.16);
const ch4o = integ(old('methane').ideal_gas_cp.coefficients, 0), ch4n = integ(cur('properties/tjl20_methane.json').ideal_gas_cp.coefficients, 0);
const wo = L.shomateEnthalpy(old('water').shomate_coefficients), wn = L.shomateEnthalpy(cur('water/water.json').shomate_coefficients);
const dN2 = T => n2n(T) - n2o(T), dCH4 = T => ch4n(T) - ch4o(T), dW = T => wn(T) - wo(T);
const cases = {
  'schema-1': [[382.9605631748737, { N2: 94.15796899475772 }], [461.73624410202746, { N2: 78.05805961268007 }]],
  'island-sets': [[332.16916378719134, { CH4: 54.41369498033667, N2: 54.41369498033666 }], [390.9137644918197, { CH4: 46.17352233110432, N2: 46.173522331104316 }],
    [323.8495189992295, { N2: 56.11960240662218 }], [365.8922797990988, { N2: 49.53487022855613 }]],
  'compound-basis': [[366.6812436029122, { CH4: 65.7455967188584, N2: 230.1095885160042 }]],
  'steam-v2': [[661.2243894113532, { N2: 36.37263758043564, W: 145.49055032174257 }]],
};
const d = { N2: dN2, CH4: dCH4, W: dW };
for (const [name, vessels] of Object.entries(cases)) {
  let sum = 0;
  for (const [T, n] of vessels) for (const [k, m] of Object.entries(n)) sum += m * d[k](T);
  console.log(name.padEnd(15), 'expected from the data change', sum.toPrecision(12), 'J');
}
console.log('per mol at 661.22 K: water', dW(661.2243894113532).toFixed(4), 'N2', dN2(661.2243894113532).toFixed(4));
