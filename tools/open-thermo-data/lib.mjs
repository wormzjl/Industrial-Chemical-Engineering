// Shared evaluators of batch 2026-11-04-open-thermo-data (O2): CoolProp ideal-gas Helmholtz terms (MIT), NASA CEA
// 9-coefficient polynomials (Apache-2.0), least-squares fits in the record forms, and the IAPWS R12-08 viscosity.
// Node 22, no dependencies. The sources are the main checkout's git-ignored copies (MANIFEST.md of
// research/2026-09-24-coolprop-low-temperature/sources/ gives their revisions and hashes).
import fs from 'node:fs';
import path from 'node:path';

export const HERE = path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1'));
export const WORKTREE = path.resolve(HERE, '../..');
export const MAIN = 'D:/Minecraft/Modding/1.21/CreateChemE';
export const SOURCES = `${MAIN}/research/2026-09-24-coolprop-low-temperature/sources`;
export const MATERIALS = `${WORKTREE}/src/main/resources/data/icheme/materials`;
export const R_CEA = 8.31451; // nasa/cea 3f4441d2 source/param.f90.in (8314.5100 J/(kmol K))

export const readJson = f => JSON.parse(fs.readFileSync(f, 'utf8'));

/** CoolProp 8.0.0 fluid file (ae81610e, MIT). */
export const coolpropFile = name => readJson(`${SOURCES}/coolprop/fluids/${name}.json`);

/** The ideal-gas part of a CoolProp fluid file's first EOS: cp(T), h(T), s(T) up to constants, in J/mol units. */
export function coolpropIdealGas(name) {
  const eos = coolpropFile(name).EOS[0];
  const Tr = eos.STATES.reducing.T, R = eos.gas_constant;
  let a = 0; const power = [], planck = [];
  for (const t of eos.alpha0) {
    if (t.type === 'IdealGasHelmholtzLogTau') a += t.a;
    else if (t.type === 'IdealGasHelmholtzPower') t.n.forEach((n, i) => power.push([n, t.t[i]]));
    else if (t.type === 'IdealGasHelmholtzPlanckEinstein') t.n.forEach((n, i) => planck.push([n, t.t[i]]));
    else if (t.type === 'IdealGasHelmholtzPlanckEinsteinFunctionT') t.n.forEach((n, i) => planck.push([n, t.v[i] / t.Tcrit]));
    else if (t.type === 'IdealGasHelmholtzLead' || t.type === 'IdealGasHelmholtzEnthalpyEntropyOffset') continue; // constants
    else throw new Error(`${name}: unsupported alpha0 term ${t.type}`);
  }
  const cp = T => {
    const tau = Tr / T; let c = 1 + a;
    for (const [n, t] of power) c -= n * t * (t - 1) * tau ** t;
    for (const [n, th] of planck) { const x = th * tau, e = Math.exp(-x); c += n * x * x * e / (1 - e) ** 2; }
    return R * c;
  };
  const h = T => {
    const tau = Tr / T; let c = 1 + a;
    for (const [n, t] of power) c += n * t * tau ** t;
    for (const [n, th] of planck) { const x = th * tau; c += n * x / Math.expm1(x); }
    return R * T * c;
  };
  return { name, R, Tr, Ttp: eos.Ttriple, molarMass: eos.molar_mass, reference: eos.BibTeX_EOS, cp, h };
}

/** A species of the CEA extract (thermo.inp 3f4441d2): intervals with a1..a7, b1, b2. */
export function ceaSpecies(formula) {
  const text = fs.readFileSync(`${SOURCES}/nasa-cea/cea-species-extract.txt`, 'utf8').split(/\r?\n/);
  const start = text.findIndex(l => l.startsWith(`# ${formula}:`));
  if (start < 0) throw new Error(`CEA ${formula} not found`);
  const header = text[start + 2], count = parseInt(header.slice(0, 2), 10);
  const hf = parseFloat(header.slice(65, 80));
  const num = s => parseFloat(s.replace(/D/g, 'e'));
  const intervals = [];
  for (let i = 0; i < count; i++) {
    const base = start + 3 + 3 * i, range = text[base], c1 = text[base + 1], c2 = text[base + 2];
    const a = [0, 16, 32, 48, 64].map(o => num(c1.slice(o, o + 16)));
    a.push(num(c2.slice(0, 16)), num(c2.slice(16, 32)), num(c2.slice(48, 64)), num(c2.slice(64, 80)));
    if (a.some(v => !Number.isFinite(v))) throw new Error(`CEA ${formula}: unparsed coefficient`);
    intervals.push({ tlo: parseFloat(range.slice(0, 11)), thi: parseFloat(range.slice(11, 21)), a });
  }
  const pick = T => intervals.find(iv => T >= iv.tlo && T <= iv.thi) ?? (T < intervals[0].tlo ? intervals[0] : intervals.at(-1));
  const cp = T => { const c = pick(T).a; return R_CEA * (c[0] / T ** 2 + c[1] / T + c[2] + c[3] * T + c[4] * T ** 2 + c[5] * T ** 3 + c[6] * T ** 4); };
  const h = T => { const c = pick(T).a; return R_CEA * T * (-c[0] / T ** 2 + c[1] * Math.log(T) / T + c[2] + c[3] * T / 2 + c[4] * T ** 2 / 3 + c[5] * T ** 3 / 4 + c[6] * T ** 4 / 5 + c[7] / T); };
  const s = T => { const c = pick(T).a; return R_CEA * (-c[0] / (2 * T ** 2) - c[1] / T + c[2] * Math.log(T) + c[3] * T + c[4] * T ** 2 / 2 + c[5] * T ** 3 / 3 + c[6] * T ** 4 / 4 + c[8]); };
  return { formula, title: text[start].slice(2), hf, intervals, cp, h, s };
}

/** Solves the normal equations of a linear least-squares fit (Gauss-Jordan, partial pivoting). */
export function leastSquares(rows, basis) {
  const n = basis.length, A = Array.from({ length: n }, () => new Array(n + 1).fill(0));
  for (const [x, y] of rows) {
    const f = basis.map(b => b(x));
    for (let i = 0; i < n; i++) { for (let j = 0; j < n; j++) A[i][j] += f[i] * f[j]; A[i][n] += y * f[i]; }
  }
  for (let i = 0; i < n; i++) {
    let p = i; for (let r = i + 1; r < n; r++) if (Math.abs(A[r][i]) > Math.abs(A[p][i])) p = r;
    [A[i], A[p]] = [A[p], A[i]];
    for (let r = 0; r < n; r++) if (r !== i) { const f = A[r][i] / A[i][i]; for (let c = i; c <= n; c++) A[r][c] -= f * A[i][c]; }
  }
  return A.map((row, i) => row[n] / row[i]);
}

/**
 * Degree-5 fit of cp(T) in powers of (T - 298.15 K) over [lo, hi] on `count` uniform points (the record form). With
 * pinLow the fit passes through cp(lo) exactly (equality-constrained least squares), so a heat capacity held below lo
 * is the source's own value there.
 */
export function fitShiftedPolynomial5(cp, lo, hi, count = 1201, { pinLow = false } = {}) {
  const scale = 100, rows = [], x0 = (lo - 298.15) / scale, y0 = cp(lo);
  for (let i = 0; i < count; i++) { const T = lo + (hi - lo) * i / (count - 1); rows.push([(T - 298.15) / scale, cp(T)]); }
  let scaled;
  if (pinLow) {
    const tail = leastSquares(rows.map(([x, y]) => [x, y - y0]), [1, 2, 3, 4, 5].map(k => x => x ** k - x0 ** k));
    scaled = [y0 - tail.reduce((s, c, i) => s + c * x0 ** (i + 1), 0), ...tail];
  } else scaled = leastSquares(rows, [0, 1, 2, 3, 4, 5].map(k => x => x ** k));
  return scaled.map((c, k) => c / scale ** k);
}
export const shiftedPolynomial = c => T => { const d = T - 298.15; let v = 0; for (let k = c.length - 1; k >= 0; k--) v = v * d + c[k]; return v; };

/** Shomate Cp form in t = T/1000: A + B t + C t^2 + D t^3 + E / t^2, J/(mol K). */
export const shomateCp = c => T => { const t = T / 1000; return c[0] + c[1] * t + c[2] * t * t + c[3] * t ** 3 + c[4] / (t * t); };
/** The water record's enthalpy (V3WaterProperties.vaporMolarEnthalpy), J/mol: 1000 (A t + B t^2/2 + C t^3/3 + D t^4/4 - E/t + F + c6). */
export const shomateEnthalpy = c => T => { const t = T / 1000; return 1000 * (c[0] * t + c[1] * t * t / 2 + c[2] * t ** 3 / 3 + c[3] * t ** 4 / 4 - c[4] / t + c[5] + c[6]); };
export const shomateEntropyShape = c => T => { const x = T / 1000; return c[0] * Math.log(x) + c[1] * x + c[2] * x * x / 2 + c[3] * x ** 3 / 3 - c[4] / (2 * x * x); };
export function fitShomate(cp, lo, hi, count = 1201) {
  const rows = [];
  for (let i = 0; i < count; i++) { const T = lo + (hi - lo) * i / (count - 1); rows.push([T / 1000, cp(T)]); }
  return leastSquares(rows, [t => 1, t => t, t => t * t, t => t ** 3, t => 1 / (t * t)]);
}

/** Relative deviation statistics of f against g on a uniform grid. */
export function compare(f, g, lo, hi, step = 1) {
  let max = 0, at = lo, sum = 0, n = 0;
  for (let T = lo; T <= hi + 1e-9; T += step) { const d = f(T) / g(T) - 1; if (Math.abs(d) > Math.abs(max)) { max = d; at = T; } sum += d * d; n++; }
  return { max, at, rms: Math.sqrt(sum / n) };
}
export const pct = (x, d = 3) => `${x >= 0 ? '+' : ''}${(100 * x).toFixed(d)} %`;

/**
 * IAPWS R12-08 (2008) viscosity of water without the critical enhancement (mu2 = 1), Pa s, at T (K) and mass density
 * rho (kg/m3). Checked against the release's table 4 (non-critical points) by check-iapws-viscosity.
 */
export function iapws2008Viscosity(T, rho) {
  const Tb = T / 647.096, rb = rho / 322.0;
  const H0 = [1.67752, 2.20462, 0.6366564, -0.241605];
  const mu0 = 100 * Math.sqrt(Tb) / H0.reduce((s, h, i) => s + h / Tb ** i, 0);
  const H = [
    [5.20094e-1, 2.22531e-1, -2.81378e-1, 1.61913e-1, -3.25372e-2, 0, 0],
    [8.50895e-2, 9.99115e-1, -9.06851e-1, 2.57399e-1, 0, 0, 0],
    [-1.08374, 1.88797, -7.72479e-1, 0, 0, 0, 0],
    [-2.89555e-1, 1.26613, -4.89837e-1, 0, 6.98452e-2, 0, -4.35673e-3],
    [0, 0, -2.57040e-1, 0, 0, 8.72102e-3, 0],
    [0, 1.20573e-1, 0, 0, 0, 0, -5.93264e-4],
  ];
  let sum = 0;
  for (let i = 0; i < 6; i++) for (let j = 0; j < 7; j++) sum += H[i][j] * (1 / Tb - 1) ** i * (rb - 1) ** j;
  return 1e-6 * mu0 * Math.exp(rb * sum);
}

/** Writes text with the line endings of the file it replaces (CRLF in the working tree for most records). */
export function writeLike(file, text) {
  const old = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : '';
  const crlf = old.includes('\r\n');
  const lf = text.replace(/\r\n/g, '\n');
  fs.writeFileSync(file, crlf ? lf.replace(/\n/g, '\r\n') : lf);
}

/** Replaces exactly one occurrence of `from` in `text`; throws otherwise. */
export function replaceOnce(text, from, to, label = from) {
  const n = text.split(from).length - 1;
  if (n !== 1) throw new Error(`${label}: found ${n} times`);
  return text.replace(from, () => to);
}

/** Ideal-gas Cp of a bundled reference-spine record's helmholtz_ideal_terms segments (as ReferenceSpine evaluates them). */
export function spineCp(file) {
  const rec = readJson(`${MATERIALS}/spine/${file}.json`);
  const segs = rec.ideal_gas.segments.map(g => {
    if (g.type !== 'helmholtz_ideal_terms') throw new Error(`${file}: segment ${g.type}`);
    const R = g.gas_constant_j_per_mol_kelvin, Tr = g.reducing_temperature_kelvin;
    let a = 0; const power = [], planck = [], cosh = [];
    for (const t of g.terms) {
      if (t.type === 'log_tau') a += t.a;
      else if (t.type === 'power') t.n.forEach((n, i) => power.push([n, t.t[i]]));
      else if (t.type === 'planck_einstein') t.n.forEach((n, i) => planck.push([n, t.t[i]]));
      else if (t.type === 'planck_einstein_function_t') t.n.forEach((n, i) => planck.push([n, t.v[i] / t.critical_temperature_kelvin]));
      else if (t.type === 'planck_einstein_cosh') t.n.forEach((n, i) => cosh.push([n, t.t[i]]));
      else throw new Error(`term ${t.type}`);
    }
    const cp = T => {
      const tau = Tr / T; let c = 1 + a;
      for (const [n, t] of power) c -= n * t * (t - 1) * tau ** t;
      for (const [n, th] of planck) { const x = th * tau, e = Math.exp(-x); c += n * x * x * e / (1 - e) ** 2; }
      for (const [n, th] of cosh) { const x = th * tau, e = Math.exp(-2 * x); c += n * x * x * 4 * e / (1 + e) ** 2; }
      return R * c;
    };
    return { lo: g.temperature_min_kelvin, hi: g.temperature_max_kelvin, revision: g.revision, cp };
  });
  const cp = T => (segs.find(s => T >= s.lo && T <= s.hi) ?? (T < segs[0].lo ? segs[0] : segs.at(-1))).cp(T);
  return { revision: rec.revision, segments: segs, cp };
}

/**
 * Nitrogen viscosity of Lemmon and Jacobsen (2004) as CoolProp's Nitrogen.json transport block states it (dilute
 * collision integral plus the modified Batschinski-Hildebrand residual), Pa s, at T (K) and molar density (mol/m3).
 */
export function nitrogenViscosity(T, rhomolar) {
  const v = coolpropFile('Nitrogen').TRANSPORT.viscosity, d = v.dilute, r = v.higher_order;
  const lnTs = Math.log(T / v.epsilon_over_k);
  const omega = Math.exp(d.a.reduce((s, a, i) => s + a * lnTs ** d.t[i], 0));
  const sigmaNm = v.sigma_eta * 1e9;
  const dilute = d.C * Math.sqrt(d.molar_mass * 1000 * T) / (sigmaNm * sigmaNm * omega);
  const tau = r.T_reduce / T, delta = rhomolar / r.rhomolar_reduce;
  let residual = 0;
  for (let i = 0; i < r.a.length; i++) residual += r.a[i] * delta ** r.d1[i] * tau ** r.t1[i] * Math.exp(r.gamma[i] * delta ** r.l[i]);
  if (r.f.some(x => x !== 0)) throw new Error('Nitrogen viscosity: unexpected free-volume terms');
  return dilute + residual;
}
