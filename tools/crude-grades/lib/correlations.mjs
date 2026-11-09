// Open correlations: API gravity, Twu 1985 kinematic viscosity, the ASTM D341 two-point form, and the liquid density
// used to turn a kinematic into a dynamic viscosity.

export const WATER_60F = 999.016; // kg/m3, water at 60 F
export const API = sg => 141.5 / sg - 131.5;
export const SG = rho60 => rho60 / WATER_60F;
export const T100F = 310.9278; // K
export const T210F = 372.0389; // K
const rankine = K => K * 1.8;

/** Twu, Ind. Eng. Chem. Process Des. Dev. 24 (1985) 1287: kinematic viscosity (cSt) at 100 F and 210 F from the normal
 * boiling point (K) and the specific gravity 60/60 F, through the n-alkane reference fluid of the same boiling point. */
export function twu1985(Tb, sg) {
  const tb = rankine(Tb);
  const Tc0 = tb / (0.533272 + 0.191017e-3 * tb + 0.779681e-7 * tb ** 2 - 0.284376e-10 * tb ** 3 + 0.959468e28 / tb ** 13);
  const a = 1 - tb / Tc0;
  const SG0 = 0.843593 - 0.128624 * a - 3.36159 * a ** 3 - 13749.5 * a ** 12;
  const v2ref = Math.exp(4.73227 - 27.0975 * a + 49.4491 * a ** 2 - 50.4706 * a ** 4) - 1.5;
  const v1ref = Math.exp(0.801621 + 1.37179 * Math.log(v2ref));
  const d = sg - SG0, x = Math.abs(1.99873 - 56.7394 / Math.sqrt(tb));
  const f1 = 1.33932 * x * d - 21.1141 * d * d / Math.sqrt(tb), f2 = x * d - 21.1141 * d * d / Math.sqrt(tb);
  const v100 = Math.exp(Math.log(v1ref + 450 / tb) * ((1 + 2 * f1) / (1 - 2 * f1)) ** 2) - 450 / tb;
  const v210 = Math.exp(Math.log(v2ref + 450 / tb) * ((1 + 2 * f2) / (1 - 2 * f2)) ** 2) - 450 / tb;
  return {v100, v210};
}

// ASTM D341 (Walther; the 2009 form with the low-viscosity terms of Z): log10 log10 Z linear in log10 T.
const Z = v => v + 0.7 + Math.exp(-1.47 - 1.84 * v - 0.51 * v * v);
const fromZ = z => { const y = z - 0.7; if (!(y < 20)) return y; return y - Math.exp(-0.7487 - 3.295 * y + 0.6119 * y * y - 0.3193 * y ** 3); };
/** The D341 line through (T1, v1) and (T2, v2), cSt and K; returns T -> cSt. */
export function d341(v1, T1, v2, T2) {
  const y1 = Math.log10(Math.log10(Z(v1))), y2 = Math.log10(Math.log10(Z(v2)));
  const B = (y1 - y2) / (Math.log10(T2) - Math.log10(T1)), A = y1 + B * Math.log10(T1);
  return T => fromZ(10 ** (10 ** (A - B * Math.log10(T))));
}

/** Saturated-liquid density (kg/m3) by the Rackett equation in the Spencer-Danner form with the Yamada-Gunn compressibility
 * Z_RA = 0.29056 - 0.08775 omega, anchored at the component's standard density: rho(T) = rho_std Z_RA^(phi(T_std) - phi(T)),
 * phi = (1 - min(T/Tc, 1))^(2/7). Above Tc it holds the critical value. */
export function rackettDensity(comp, T) {
  const zra = 0.29056 - 0.08775 * comp.omega;
  const phi = t => Math.pow(1 - Math.min(t / comp.Tc, 1), 2 / 7);
  return comp.rho60 * Math.pow(zra, phi(comp.Tstd) - phi(T));
}

/** A cut's dynamic liquid viscosity (Pa s) by Twu 1985 + D341, times the Rackett density; capped at 1e12 Pa s. */
export function twuDynamic(comp, T) {
  const {v100, v210} = twu1985(comp.Tb, SG(comp.rho60));
  const nu = d341(v100, T100F, v210, T210F)(T); // cSt
  return Math.min(nu * 1e-6 * rackettDensity(comp, T), 1e12);
}
