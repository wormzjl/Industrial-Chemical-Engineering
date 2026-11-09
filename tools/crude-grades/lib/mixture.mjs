// The game's liquid-mixture viscosity with the one-term open pair correction (E12):
// mu = exp(sum_i x_i ln mu_i + G (x_cut^2 - sum_cuts x_i^2)), x mole fractions over every liquid component,
// x_cut the summed mole fraction of crude_pc01..12. G = 0 gives the base rule.
import {gameLiquid} from './materials.mjs';

export function pairFraction(comps, x) {
  let xc = 0, x2 = 0;
  for (const c of comps) if (!c.light) { const v = x[c.name] || 0; xc += v; x2 += v * v; }
  return xc * xc - x2;
}
/** Pa s. {@code tables} maps a cut name to the liquid log_table to use ({t, c}); other components use their record. */
export function mixtureViscosity(comps, x, T, G, tables = {}) {
  let s = 0;
  for (const c of comps) { const v = x[c.name] || 0; if (v > 0) s += v * Math.log(gameLiquid(c, T, tables[c.name] ?? c.liq)); }
  return Math.exp(s + G * pairFraction(comps, x));
}
