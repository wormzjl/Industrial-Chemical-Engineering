// Riazi (1989) TBP distribution on the shared cuts, the light-end split, blends, density and yields.
import {API, SG} from './correlations.mjs';

/** Cut bins (K): the C6+ distribution starts at T0; each cut takes the mass between the midpoints of its NBP and its
 * neighbours' NBPs; crude_pc12's bin is open above. */
export function cutBins(comps, T0) {
  const cuts = comps.filter(c => !c.light), b = [T0];
  for (let i = 0; i < cuts.length - 1; i++) b.push((cuts[i].Tb + cuts[i + 1].Tb) / 2);
  b.push(Infinity);
  return b;
}
/** Riazi 1989, mass basis: cumulative fraction of the C6+ part boiling below T. */
export const riaziX = (T, A, B, T0) => T <= T0 ? 0 : T === Infinity ? 1 : 1 - Math.exp(-(B / A) * Math.pow((T - T0) / T0, B));
/** Light-end split of the C1-C5 total, by mass (CRUDE_GRADES_REVIEW 3.1; an assumption). */
export const SPLIT_CRUDE = {Methane: 0.01, Ethane: 0.04, Propane: 0.15, Isobutane: 0.10, 'N-butane': 0.22, Isopentane: 0.21, 'N-pentane': 0.27};
export const SPLIT_CONDENSATE = {Methane: 0, Ethane: 0.01, Propane: 0.06, Isobutane: 0.07, 'N-butane': 0.16, Isopentane: 0.30, 'N-pentane': 0.40};

/** Mass fractions by component name for one distribution. */
export function compose(comps, {lightEnds, split, A, B, T0, bins}) {
  const w = {}, cuts = comps.filter(c => !c.light);
  for (const c of comps) if (c.light) w[c.name] = lightEnds * split[c.name];
  cuts.forEach((c, i) => { w[c.name] = (1 - lightEnds) * (riaziX(bins[i + 1], A, B, T0) - riaziX(bins[i], A, B, T0)); });
  return w;
}
export function blend(comps, parts) { // parts: [[w, massFraction], ...]
  const w = {}; for (const c of comps) w[c.name] = parts.reduce((s, [p, f]) => s + f * (p[c.name] || 0), 0);
  return w;
}
/** Ideal-volume density at the standard condition and its API gravity. */
export function standardDensity(comps, w) {
  let v = 0; for (const c of comps) v += (w[c.name] || 0) / c.rho60;
  const rho = 1 / v; return {rho60: rho, api: API(SG(rho))};
}
export const meanMolarMass = (comps, w) => 1 / comps.reduce((s, c) => s + (w[c.name] || 0) / c.M, 0); // g/mol
export function moleFractions(comps, w) {
  const n = comps.map(c => (w[c.name] || 0) / c.M), t = n.reduce((a, b) => a + b);
  return Object.fromEntries(comps.map((c, i) => [c.name, n[i] / t]));
}
/** Product yields (mass) of the continuous distribution: C1-C4, naphtha C5-175 C, kerosene 175-235, diesel 235-343,
 * VGO 343-565, residue 565 C+. */
export const PRODUCTS = [['naphtha', 448.15], ['kerosene', 508.15], ['diesel', 616.15], ['vgo', 838.15], ['residue', Infinity]];
export function tbpYields({lightEnds, split, A, B, T0}) {
  const c5 = split.Isopentane + split['N-pentane'], y = {gas: lightEnds * (1 - c5)};
  let prev = T0; for (const [n, T] of PRODUCTS) { y[n] = (1 - lightEnds) * (riaziX(T, A, B, T0) - riaziX(prev, A, B, T0)) + (n === 'naphtha' ? lightEnds * c5 : 0); prev = T; }
  return y;
}
/** Yields with each cut assigned whole to the product range holding its NBP. */
export function cutYields(comps, w) {
  const y = {gas: 0}; for (const [n] of PRODUCTS) y[n] = 0;
  for (const c of comps) { const m = w[c.name] || 0; if (c.light) { y[c.Tb < 300 ? 'gas' : 'naphtha'] += m; continue; } y[PRODUCTS.find(([, T]) => c.Tb < T)[0]] += m; }
  return y;
}
/** A by bisection so that the 565 C+ share of the whole grade is `residue` (B, T0 fixed). */
export function solveA({lightEnds, B, T0}, residue) {
  let lo = -12, hi = 8;
  for (let k = 0; k < 200; k++) { const m = (lo + hi) / 2; if ((1 - lightEnds) * (1 - riaziX(838.15, Math.exp(m), B, T0)) < residue) lo = m; else hi = m; }
  return Math.exp((lo + hi) / 2);
}
/** B (and A) by bisection so that the grade meets both its API and its 565 C+ share. */
export function solveAB(comps, spec) {
  const bins = cutBins(comps, spec.T0);
  const apiAt = B => { const A = solveA({...spec, B}, spec.residue); return standardDensity(comps, compose(comps, {...spec, A, B, bins})).api; };
  let lo = 0.3, hi = 6, flo = apiAt(lo) - spec.api;
  if (flo * (apiAt(hi) - spec.api) > 0) throw new Error(spec.id + ': API and residue cannot both be met');
  for (let k = 0; k < 200; k++) { const m = (lo + hi) / 2, f = apiAt(m) - spec.api; if (f * flo > 0) { lo = m; flo = f; } else hi = m; }
  const B = (lo + hi) / 2;
  return {B, A: solveA({...spec, B}, spec.residue), bins};
}
