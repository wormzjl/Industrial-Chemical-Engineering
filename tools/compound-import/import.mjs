// Compound import from the DWSIM-embedded ChemSep v8.3 data into the IChemE material record formats.
//
// Usage: node import.mjs <dwsim-data dir> <selection.json> <out dir> [--data <REFINERY_COMPOUNDS_DATA.md>]
//                        [--materials <src/main/resources/data/icheme/materials>]
//
// Writes, under <out dir>: components/<file>.json, properties/chemsep_<file>.json, interactions/<pair library>.json,
// transport/<volume reference>.json, freezing/<record>.json (freezing sub-records for existing datasets),
// thermochemistry/<record>.json and flammability/<record>.json (for existing datasets; insert-subrecord.mjs writes them in),
// group_counts/<record>.json and henry_in_water/<record>.json (the same, for groups_only and henry_only entries),
// properties/<cubic_water.file>.json (water as a compound of the cubic, from the selection's cubic_water entry),
// lang-en_us.json (suggested English lines) and report.json; prints the fit errors, the pair sources and a
// cross-check table against the batch data document. Deterministic: fixed ordering, JSON number formatting.
import { mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { readChemSep, evaluate } from './chemsep.mjs';
import { readKijPr, readDechema, chooseDechema, pairKey } from './pairs.mjs';
import { groupCountsRecord, readTables } from './groups.mjs';

const HERE = dirname(fileURLToPath(import.meta.url));
const EXCEPTIONS = JSON.parse(readFileSync(join(HERE, 'pair-exceptions.json'), 'utf8'));
const STANDARD_T = 288.7055555556; // 60 F, the bundled light ends' standard state
const STANDARD_P = 101325;
const REVISION = /^[A-Za-z0-9_.-]{1,64}$/;
const R = 8.314462618;

// ---------------------------------------------------------------------------------------------------- arguments
const argv = process.argv.slice(2);
const option = (name) => { const i = argv.indexOf(name); if (i < 0) return null; const v = argv[i + 1]; argv.splice(i, 2); return v; };
const dataDoc = option('--data') ?? join(HERE, '..', '..', 'documentation', '2026-11-04-refinery-compounds', 'REFINERY_COMPOUNDS_DATA.md');
const materialsDir = option('--materials');
if (argv.length !== 3) {
  console.error('usage: node import.mjs <dwsim-data dir> <selection.json> <out dir> [--data <md>] [--materials <dir>]');
  process.exit(2);
}
const [dwsimDir, selectionPath, outDir] = argv;
const selection = JSON.parse(readFileSync(selectionPath, 'utf8'));
const form = selection.form;

// ---------------------------------------------------------------------------------------------------- data
const compounds = readChemSep(dwsimDir);
const byCas = new Map(), byIndex = new Map();
for (const c of compounds) {
  if (!byCas.has(c.cas)) byCas.set(c.cas, c); // chemsep1 before chemsep2
  if (!byIndex.has(c.index)) byIndex.set(c.index, c);
}
const chemsep = (cas) => { const c = byCas.get(cas); if (!c) throw new Error('ChemSep has no compound with CAS ' + cas); return c; };
const credit = (c) => `ChemSep v8.3 pure component data (Copyright (c) Harry Kooijman and Ross Taylor; Artistic License 2.0), `
  + `as embedded in DWSIM 10.2.5 (DWSIM.Thermodynamics.dll resource ${c.file}), compound "${c.name}" (ChemSep index ${c.index}, CAS ${c.cas})`;
const eqText = (e) => `eq. ${e.eqno} (A=${e.A}, B=${e.B}, C=${e.C}, D=${e.D}, E=${e.E}; data ${e.tmin}..${e.tmax} K)`;

// ---------------------------------------------------------------------------------------------------- numerics
/** Least squares by Householder QR on x scaled to [~-1, 1]: returns the polynomial coefficients in the unscaled x. */
function polyfit(xs, ys, degree, scale) {
  const n = xs.length, m = degree + 1;
  const A = xs.map((x) => Array.from({ length: m }, (_, k) => (x / scale) ** k)), b = ys.slice();
  for (let j = 0; j < m; j++) {
    let norm = 0; for (let i = j; i < n; i++) norm += A[i][j] ** 2; norm = Math.sqrt(norm);
    const s = A[j][j] > 0 ? -norm : norm, v = new Array(n).fill(0);
    v[j] = A[j][j] - s; for (let i = j + 1; i < n; i++) v[i] = A[i][j];
    let vv = 0; for (let i = j; i < n; i++) vv += v[i] ** 2;
    if (vv === 0) continue;
    for (let k = j; k < m; k++) { let d = 0; for (let i = j; i < n; i++) d += v[i] * A[i][k]; d = 2 * d / vv; for (let i = j; i < n; i++) A[i][k] -= d * v[i]; }
    let d = 0; for (let i = j; i < n; i++) d += v[i] * b[i]; d = 2 * d / vv; for (let i = j; i < n; i++) b[i] -= d * v[i];
  }
  const c = new Array(m).fill(0);
  for (let j = m - 1; j >= 0; j--) { let s = b[j]; for (let k = j + 1; k < m; k++) s -= A[j][k] * c[k]; c[j] = s / A[j][j]; }
  return c.map((a, k) => a / scale ** k);
}
const shifted = (c, t) => { const d = t - 298.15; return c[0] + d * (c[1] + d * (c[2] + d * (c[3] + d * (c[4] + d * c[5])))); };

/**
 * A log table by recursive bisection: an interval is kept when log-linear interpolation between its ends is within
 * `tolerance` (relative) of f at its quarter and mid points, else halved, to at most 2^maxDepth intervals.
 * This is the rule that reproduces the bundled DWSIM-sampled light-end tables node for node.
 */
function logTable(f, a, b, tolerance, maxDepth = 8) {
  const nodes = [a];
  const refine = (x, y, depth) => {
    const fx = f(x), fy = f(y);
    let error = 0;
    for (const q of [0.25, 0.5, 0.75]) {
      const t = x + (y - x) * q;
      error = Math.max(error, Math.abs(Math.exp(Math.log(fx) + q * (Math.log(fy) - Math.log(fx))) / f(t) - 1));
    }
    if (depth < maxDepth && error > tolerance) { const m = x + (y - x) / 2; refine(x, m, depth + 1); refine(m, y, depth + 1); }
    else nodes.push(y);
  };
  refine(a, b, 0);
  return nodes;
}

/** PR78 at (T, P): the real compressibility roots and the fugacity coefficients of the outer ones. */
function pr78(c, t, p) {
  const w = c.omega, kappa = w <= 0.491 ? 0.37464 + 1.54226 * w - 0.26992 * w * w
    : 0.379642 + 1.48503 * w - 0.164423 * w * w + 0.016666 * w ** 3;
  const alpha = (1 + kappa * (1 - Math.sqrt(t / c.tc))) ** 2;
  const a = 0.45723553 * (R * c.tc) ** 2 / c.pc * alpha, b = 0.07779607 * R * c.tc / c.pc;
  const A = a * p / (R * t) ** 2, B = b * p / (R * t);
  // Z^3 + c2 Z^2 + c1 Z + c0 = 0
  const c2 = -(1 - B), c1 = A - 3 * B * B - 2 * B, c0 = -(A * B - B * B - B ** 3);
  const q = (3 * c1 - c2 * c2) / 9, r = (9 * c2 * c1 - 27 * c0 - 2 * c2 ** 3) / 54, disc = q ** 3 + r * r;
  let roots;
  if (disc > 0) { const s = Math.cbrt(r + Math.sqrt(disc)), u = Math.cbrt(r - Math.sqrt(disc)); roots = [s + u - c2 / 3]; }
  else { const th = Math.acos(r / Math.sqrt(-(q ** 3))); roots = [0, 1, 2].map((k) => 2 * Math.sqrt(-q) * Math.cos((th + 2 * Math.PI * k) / 3) - c2 / 3); }
  roots = roots.filter((z) => z > B).sort((x, y) => x - y);
  const lnPhi = (z) => z - 1 - Math.log(z - B) - A / (2 * Math.SQRT2 * B) * Math.log((z + (1 + Math.SQRT2) * B) / (z + (1 - Math.SQRT2) * B));
  return { roots, lnPhiL: lnPhi(roots[0]), lnPhiV: lnPhi(roots[roots.length - 1]), b };
}
/** PR78's own saturation pressure at T (successive substitution from p0), or null where it has no two roots. */
function pr78Saturation(c, t, p0) {
  let p = p0;
  for (let i = 0; i < 200; i++) {
    const s = pr78(c, t, p);
    if (s.roots.length < 2) return null;
    const next = p * Math.exp(s.lnPhiL - s.lnPhiV);
    if (Math.abs(next / p - 1) < 1e-12) return next;
    p = next;
  }
  return p;
}

// ---------------------------------------------------------------------------------------------------- records
const snake = (id) => id.replace(/([a-z0-9])([A-Z])/g, '$1_$2').replace(/[-\s,]+/g, '_').toLowerCase();
const freezingPoint = (c) => c.tt ?? c.tm;

function fitCp(c) {
  const cp = c.cp;
  const low = form.cp_fit.low === 'data' ? Math.max(cp.tmin, freezingPoint(c)) : form.cp_fit.low;
  const high = form.cp_fit.high, step = form.cp_fit.grid_step_kelvin;
  // Grid: every multiple of the step in [low, high], with the low end itself first when include_low_end is set. The
  // bundled light-end fits sampled 300, 325, ..., 900 K (include_low_end false reproduces them to 1e-13).
  const ts = form.cp_fit.include_low_end ? [low] : [];
  for (let t = Math.ceil(low / step) * step; t <= high + 1e-9; t += step) if (t > low || !form.cp_fit.include_low_end) ts.push(t);
  if (ts[ts.length - 1] < high) ts.push(high);
  const value = (t) => evaluate(cp, t) / 1000; // J/kmol/K -> J/mol/K
  const coefficients = polyfit(ts.map((t) => t - 298.15), ts.map(value), 5, high - 298.15);
  let worst = 0, worstAt = low, worstHigh = 0;
  for (let i = 0; i <= 4000; i++) {
    const t = low + (high - low) * i / 4000, e = shifted(coefficients, t) / value(t) - 1;
    if (Math.abs(e) > Math.abs(worst)) { worst = e; worstAt = t; }
    if (t >= 298.15 && Math.abs(e) > Math.abs(worstHigh)) worstHigh = e;
  }
  const outside = cp.tmin > low || cp.tmax < high;
  return { low, high, points: ts.length, coefficients, worst, worstAt, worstHigh, outside };
}

function viscosity(c, phase, domainLow) {
  const e = phase === 'liquid' ? c.muL : c.muV;
  if (!e) return null;
  let low, high;
  if (phase === 'liquid') {
    const f = form.liquid_viscosity;
    low = f.low === 'half_tc' ? 0.5 * c.tc : Math.max(e.tmin, freezingPoint(c));
    high = f.high_reduced_temperature * c.tc;
  } else {
    const f = form.vapor_viscosity;
    low = f.low === 'fluid_domain' ? Math.max(domainLow, e.tmin) : f.low;
    high = Math.min(f.high, e.tmax);
  }
  // Past the data end a liquid table either evaluates the equation (DWSIM's own behaviour; the regression form) or,
  // with above_data "andrade", continues ln(mu) linear in 1/T at the equation's slope there: eq. 101's D T^E term can
  // collapse the liquid towards the vapour value (toluene at 0.95 Tc: 0.013 mPa s, vapour 0.0127), while the Andrade
  // continuation, checked by cutting six full-range compounds at 0.75 Tc, stays within 0.97..1.36 of their equation at 0.95 Tc.
  const andrade = phase === 'liquid' && form.liquid_viscosity.above_data === 'andrade' && e.eqno === 101 && high > e.tmax;
  const end = e.tmax, slope = e.B - e.C * end - e.D * e.E * end ** (e.E + 1); // d ln(mu) / d(1/T) of eq. 101
  const fn = (t) => (andrade && t > end ? Math.exp(Math.log(evaluate(e, end)) + slope * (1 / t - 1 / end)) : evaluate(e, t));
  let nodes = logTable(fn, low, high, form.tolerance);
  const extra = phase === 'vapor' ? (form.vapor_viscosity.extra_low_nodes ?? []).filter((t) => t < low) : [];
  nodes = [...extra, ...nodes];
  const values = nodes.map(fn);
  const below = nodes[0] < e.tmin, above = nodes[nodes.length - 1] > e.tmax;
  const extrapolated = [below ? `${nodes[0]}..${e.tmin} K` : null, above ? `${e.tmax}..${nodes[nodes.length - 1]} K` : null].filter(Boolean);
  const spanText = phase === 'liquid'
    ? (form.liquid_viscosity.low === 'half_tc' ? '0.5 Tc' : 'max(data start, triple point)') + ` to ${form.liquid_viscosity.high_reduced_temperature} Tc`
    : 'the fluid-domain start (or the data start, if later) to ' + form.vapor_viscosity.high + ' K (or the data end, if earlier)';
  const beyond = !extrapolated.length ? ''
    : andrade ? `Past the data end, ${extrapolated.join(' and ')}: estimated, ln(mu) linear in 1/T at the equation's slope at ${end} K (Andrade form). `
    : `The equation is extrapolated over ${extrapolated.join(' and ')}, outside its data range: estimated. `;
  const source = `${credit(c)}: ${phase === 'liquid' ? 'liquid' : 'vapour'} viscosity ${eqText(e)}, sampled over ${spanText} `
    + `into a log table by recursive bisection, log interpolation within ${form.tolerance * 100} % at quarter and mid points. `
    + beyond + 'No pressure correction; the parser continues the table past its ends.';
  return {
    entry: {
      type: 'log_table', temperature_min_kelvin: nodes[0], temperature_max_kelvin: nodes[nodes.length - 1],
      pressure_min_pascal: 100000, pressure_max_pascal: 100000, reference_temperature_kelvin: 300,
      coefficients: values, temperatures_kelvin: nodes, revision: selection.viscosity_revision,
      source, estimated: extrapolated.length > 0,
    },
    extrapolated, equation: e,
  };
}

/** A freezing sub-record that takes a crystal record's anchor (its values and estimated flag), for a compound with a crystal. */
function crystalFreezing(component, crystalFile, note) {
  if (!materialsDir) throw new Error('--materials is required for ' + component);
  const crystal = JSON.parse(readFileSync(join(materialsDir, 'crystals', crystalFile), 'utf8')), a = crystal.anchor;
  return { normal_melting_kelvin: a.temperature_kelvin, triple_point_kelvin: a.temperature_kelvin, triple_point_pascal: a.pressure_pascal,
    fusion_enthalpy_j_per_mol: a.fusion_enthalpy_j_per_mol, estimated: a.estimated ?? false,
    source: `The anchor of ${crystal.id} (revision ${crystal.revision}), its values and estimated flag. ${note ?? ''} Data only: never a validity floor or a range end.`,
    revision: crystalFreezingRevision };
}

/** Notes the gap between a crystal's anchor and a freezing record's triple point. */
function crystalGap(component, crystalFile, record) {
  if (!crystalFile || !materialsDir) return;
  const crystal = JSON.parse(readFileSync(join(materialsDir, 'crystals', crystalFile), 'utf8'));
  const gap = Math.abs(crystal.anchor.temperature_kelvin - (record.triple_point_kelvin ?? record.normal_melting_kelvin));
  report.notes.push(`${component}: crystal ${crystal.id} anchor ${crystal.anchor.temperature_kelvin} K, freezing ${record.triple_point_kelvin} K, gap ${gap} K${gap > 0.005 ? ' (EXCEEDS 0.005 K)' : ''}`);
}

function freezingRecord(c, override, note) {
  if (override) return { ...override, revision: override.revision ?? selection.freezing_revision };
  const record = { normal_melting_kelvin: c.tm ?? c.tt };
  if (c.tt !== null) record.triple_point_kelvin = c.tt;
  if (c.pt !== null) record.triple_point_pascal = c.pt;
  if (c.hfus !== null) record.fusion_enthalpy_j_per_mol = c.hfus / 1000;
  record.estimated = false;
  record.source = `${credit(c)}: melting point, triple point temperature and pressure, heat of fusion at the melting point. `
    + (note ? note + ' ' : '') + 'Data only: never a validity floor or a range end.';
  record.revision = selection.freezing_revision;
  return record;
}

// ---------------------------------------------------------------------------------------------------- thermochemistry (O9, E23 form S2)
const ATOMIC_WEIGHT = { C: 12.011, H: 1.008, N: 14.007, O: 15.999, S: 32.06 };
/** Element counts of a molecular formula of C, H, N, O and S ("C2H6S"), in that order, zero counts left out. */
function elements(formula) {
  const n = { C: 0, H: 0, N: 0, O: 0, S: 0 };
  const rest = formula.replace(/([A-Z][a-z]?)(\d*)/g, (_, e, k) => {
    if (!(e in n)) throw new Error(`formula ${formula}: element ${e} is not C, H, N, O or S`);
    n[e] += k === '' ? 1 : Number(k); return '';
  });
  if (rest !== '') throw new Error('formula ' + formula + ': unreadable');
  return Object.fromEntries(Object.entries(n).filter(([, v]) => v > 0));
}
/** A finite number in (0, 1) from a volume percent, without binary noise in its decimal form (1.6 % -> 0.016). */
const fraction = (percent) => Number((percent / 100).toPrecision(12));
const tooLong = (component, record) => {
  for (const [k, v] of Object.entries(record)) if (typeof v === 'string' && v.length > 2048) throw new Error(`${component}: ${k} longer than 2048 characters`);
  return record;
};
/**
 * A thermochemistry sub-record (form S2): ChemSep's ideal-gas heat of formation at 298.15 K (J/kmol to J/mol) and the
 * element formula, whose molar mass must match ChemSep's within 0.02 g/mol. An element in its reference state
 * ("reference_element") is 0 by definition, and ChemSep must agree. Heats of reaction are left to Hess's law.
 */
function thermochemistryRecord(c, e) {
  const n = elements(e.formula);
  const mw = Object.entries(n).reduce((s, [k, v]) => s + v * ATOMIC_WEIGHT[k], 0);
  if (Math.abs(mw - c.mw) > 0.02) throw new Error(`${e.component}: formula ${e.formula} gives ${mw} g/mol, ChemSep ${c.mw}`);
  if (c.hf === null) throw new Error(e.component + ': ChemSep has no heat of formation');
  const hf = c.hf / 1000;
  if (e.reference_element && hf !== 0) throw new Error(e.component + ': an element in its reference state, but ChemSep gives ' + hf);
  return tooLong(e.component, {
    formation_enthalpy_j_per_mol: hf,
    elements: n,
    estimated: false,
    source: (e.reference_element ? `0 by definition: ${e.formula} is an element in its reference state (ChemSep agrees). ` : '')
      + `Ideal-gas heat of formation at 298.15 K and formula ${e.formula} (molar mass ${+mw.toFixed(4)} g/mol) of ${credit(c)}.`
      + (e.note ? ' ' + e.note : '') + ' Heats of reaction follow by Hess\'s law. Data only.',
    revision: selection.thermochemistry_revision,
  });
}
/** A flammability sub-record (form S2): the entry's limits as volume fractions with the selection's source text. */
function flammabilityRecord(e) {
  const l = e.limits, template = selection.limit_sources?.[l.source];
  if (!template) throw new Error(e.component + ': unknown limit source ' + l.source);
  if (!(l.lfl_volume_percent > 0 && l.lfl_volume_percent < 100)) throw new Error(e.component + ': LFL out of range');
  if (l.ufl_volume_percent !== undefined && !(l.ufl_volume_percent > l.lfl_volume_percent && l.ufl_volume_percent < 100))
    throw new Error(e.component + ': UFL out of range');
  const record = { lfl_volume_fraction: fraction(l.lfl_volume_percent) };
  if (l.ufl_volume_percent !== undefined) record.ufl_volume_fraction = fraction(l.ufl_volume_percent);
  record.estimated = false;
  record.source = template.replaceAll('{entry}', l.entry).replaceAll('{ref}', l.ref) + (l.note ? ' ' + l.note : '');
  record.revision = selection.flammability_revision;
  return tooLong(e.component, record);
}

// ---------------------------------------------------------------------------------------------------- groups and Henry (aqueous solvents, M3)
// Every compound gets its group counts with no further step (plan section 8): ChemSep's group numbers are named by the
// bundled group_interactions records (--materials, else this repository's src/main/resources/data/icheme/materials).
const groupTables = readTables(materialsDir ?? join(HERE, '..', '..', 'src', 'main', 'resources', 'data', 'icheme', 'materials'));
const groupRevisions = selection.group_counts_revisions ?? {};
for (const r of Object.values(groupRevisions)) if (!/^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/.test(r)) throw new Error('invalid group_counts revision ' + r);
/**
 * A compound's group_counts sub-record (groups.mjs), or null; the refused schemes go to the report. An entry's
 * `authored_counts` ({<scheme>: {counts, source, revision}}: counts written from the group definitions, estimated false)
 * give the schemes they name in place of ChemSep's, and come first in the record, so a scheme added to a record that
 * already has another is an insertion only.
 */
function groupCounts(c, component, authored = {}) {
  const g = c ? groupCountsRecord(c, groupTables, credit, groupRevisions) : { record: null, refused: [] };
  const own = Object.fromEntries(Object.entries(authored).map(([scheme, a]) => [scheme, authoredCounts(component, scheme, a)]));
  for (const reason of g.refused) if (!(reason.split(':')[0] in own)) report.group_counts_refused.push(component + ': ' + reason);
  const record = { ...own };
  for (const [scheme, value] of Object.entries(g.record ?? {})) if (!(scheme in own)) record[scheme] = value;
  return Object.keys(record).length ? record : null;
}
/** One authored scheme: every name a group of the bundled table of that scheme, every count a positive integer. */
function authoredCounts(component, scheme, a) {
  const table = scheme === 'modified-unifac-dortmund' ? groupTables.modfac : scheme === 'eppr78-2022' ? groupTables.eppr78 : null;
  if (!table) throw new Error(component + ': authored_counts: unknown scheme ' + scheme);
  const names = new Set(table.names.values()), counts = {};
  for (const [name, count] of Object.entries(a.counts ?? {})) {
    if (!names.has(name)) throw new Error(component + ': authored_counts.' + scheme + ': ' + name + ' is not a group of the table');
    if (!(Number.isInteger(count) && count >= 1)) throw new Error(component + ': authored_counts.' + scheme + '.' + name + ': not a positive integer');
    counts[name] = count;
  }
  if (!Object.keys(counts).length) throw new Error(component + ': authored_counts.' + scheme + ': no counts');
  if (!a.source || !/^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/.test(a.revision ?? '')) throw new Error(component + ': authored_counts.' + scheme + ': source and revision required');
  return tooLong(component, { counts, estimated: false, source: a.source + ' (table ' + table.revision + ')', revision: a.revision });
}
/**
 * A polar compound's homomorph sub-record: the bundled compound whose E-PPR78 pairs stand for its own with a compound
 * that has no modified UNIFAC groups (its hydrocarbon homomorph, each polar group read as CH3).
 */
function homomorphRecord(component, h) {
  if (!h.component || !h.source || !/^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/.test(h.revision ?? '')) throw new Error(component + ': homomorph: component, source and revision required');
  return tooLong(component, { component: h.component, source: h.source, revision: h.revision });
}
/**
 * A gas's henry_in_water sub-record: the IAPWS G7-04 coefficients A, B, C the entry gives, with the selection's source
 * text ({entry} is the gas's formula) and revision.
 */
function henryRecord(e) {
  if (!Array.isArray(e.coefficients) || e.coefficients.length !== 3 || !e.coefficients.every(Number.isFinite))
    throw new Error(e.component + ': henry coefficients must be three finite numbers');
  if (!selection.henry_source || !/^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/.test(selection.henry_revision ?? '')) throw new Error('henry_source and henry_revision required');
  return tooLong(e.component, { type: 'iapws_g7_04', coefficients: e.coefficients,
    source: selection.henry_source.replaceAll('{entry}', e.gas ?? e.component), revision: selection.henry_revision });
}

/** The saturated liquid at Tr = 0.7: eq. 105 density and eq. 101 vapour pressure, with the PR78 checks. */
function liquidReference(c) {
  const t = 0.7 * c.tc, p = evaluate(c.psat, t), rho = evaluate(c.rho, t); // Pa, kmol/m3
  const v = 1 / (rho * 1000);
  const pr = pr78(c, t, p), sat = pr78Saturation(c, t, p);
  const prLiquid = pr.roots.length > 1 ? pr.roots[0] * R * t / p : null;
  const omegaImplied = -Math.log10(p / c.pc) - 1;
  const outside = [t < c.rho.tmin || t > c.rho.tmax ? 'eq. 105' : null, t < c.psat.tmin || t > c.psat.tmax ? 'eq. 101' : null].filter(Boolean);
  return { t, p, v, rho, liquidRoot: pr.roots.length > 1, prLiquid, prSaturation: sat, omegaImplied, outside };
}

function standardDensity(c, ref) {
  if (c.tc > STANDARD_T) {
    const rho = evaluate(c.rho, STANDARD_T) * c.mw;
    const note = STANDARD_T < c.rho.tmin ? ' (below the eq. 105 data start: extrapolated)' : '';
    return { density: rho, t: STANDARD_T, p: STANDARD_P, text: `standard liquid density: eq. 105 saturated liquid at 288.7056 K (60 F), as the bundled light ends${note}` };
  }
  // Tc below 60 F: no hypothetical 60 F liquid; the record's own reference is its saturated liquid at Tr = 0.7.
  return { density: ref.rho * c.mw, t: ref.t, p: ref.p,
    text: 'standard liquid density: the saturated liquid at Tr = 0.7 (eq. 105, at the eq. 101 vapour pressure), because Tc lies below 288.7 K and no 60 F liquid exists' };
}

// ---------------------------------------------------------------------------------------------------- build
const write = (rel, value) => {
  const path = join(outDir, rel); mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, JSON.stringify(value, null, 2) + '\n');
  written.push(rel);
};
const written = [];
// A freezing sub-record's revision names its source: ChemSep's, the crude-fraction estimate's or the crystal anchor's.
const estimateFreezingRevision = selection.estimate_freezing_revision ?? selection.freezing_revision;
const crystalFreezingRevision = selection.crystal_freezing_revision ?? selection.freezing_revision;
for (const r of [selection.properties_revision, selection.viscosity_revision, selection.freezing_revision, estimateFreezingRevision, crystalFreezingRevision])
  if (!REVISION.test(r)) throw new Error('invalid revision token ' + r);
const TOKEN = /^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$/;
if (selection.thermochemistry_only?.length || selection.compounds.some((s) => s.thermochemistry))
  for (const r of [selection.thermochemistry_revision, selection.flammability_revision])
    if (!TOKEN.test(r ?? '')) throw new Error('invalid thermochemistry or flammability revision ' + r);

const report = { compounds: [], pairs: [], freezing_only: [], thermochemistry_only: [], groups_only: [], group_counts_refused: [], henry_only: [], notes: [] };
const lang = {};
const references = [];
// A selection entry may replace ChemSep constants ("constants": {tc, pc, omega, omit: [field], source, freezing_note})
// where the ChemSep record is inconsistent; every Tc-dependent value (0.95 Tc, Tr = 0.7) then follows the new Tc.
const withConstants = (c, k) => {
  if (!k) return c;
  const out = { ...c };
  for (const f of ['tc', 'pc', 'omega']) if (k[f] !== undefined) out[f] = k[f];
  for (const f of k.omit ?? []) out[f] = null;
  return out;
};
for (const s of selection.compounds) {
  const c = withConstants(chemsep(s.cas), s.constants);
  const file = s.file ?? snake(s.id);
  const propertiesId = (selection.properties_prefix ?? 'icheme:chemsep_') + file;
  const cp = fitCp(c);
  const fd = form.fluid_domain;
  const domainLow = fd.temperature_min === 'cp_fit' ? cp.low : fd.temperature_min;
  const ref = liquidReference(c);
  const std = standardDensity(c, ref);
  const muL = viscosity(c, 'liquid', domainLow), muV = viscosity(c, 'vapor', domainLow);
  const estimates = [];
  if (cp.outside) estimates.push('Cp fit range leaves the eq. data range');
  if (muL?.extrapolated.length) estimates.push('liquid viscosity extrapolated ' + muL.extrapolated.join(', '));
  if (muV?.extrapolated.length) estimates.push('vapour viscosity extrapolated ' + muV.extrapolated.join(', '));
  if (!muL) estimates.push('no ChemSep liquid viscosity');
  if (!muV) estimates.push('no ChemSep vapour viscosity');
  if (ref.outside.length) estimates.push('Tr 0.7 outside ' + ref.outside.join(', '));

  write(`components/${file}.json`, {
    schema_version: 1, id: s.id, kind: 'chemical', translation_key: 'material.icheme.' + file, fallback: s.id,
    properties: propertiesId,
    appearance: { color: s.appearance?.color ?? '#F3F4EA', transparency: s.appearance?.transparency ?? 0.85, estimated: true },
    identifiers: { cas: c.cas, chemsep_id: c.index, smiles: c.smiles.trim(), formula: c.formula.trim() },
  });
  lang['material.icheme.' + file] = s.english ?? s.id;

  const property = {
    schema_version: 1, id: propertiesId, component: s.id, revision: selection.properties_revision,
    source: `${credit(c)}. Molecular weight, normal boiling point, Tc, Pc and acentric factor as given; `
      + `${std.text}; ideal-gas Cp ${eqText(c.cp)} fitted by least squares as shifted_polynomial_5 over ${cp.low}..${cp.high} K `
      + `(${cp.points} points), largest deviation ${(cp.worst * 100).toFixed(3)} % at ${cp.worstAt.toFixed(1)} K, `
      + `${(Math.abs(cp.worstHigh) * 100).toFixed(3)} % over 298.15..${cp.high} K; viscosity tables and freezing data in their entries. `
      + (s.constants ? s.constants.source + ' ' : '') + 'Imported by tools/compound-import.',
    molecular_weight_kg_per_mol: c.mw / 1000,
    normal_boiling_point_kelvin: c.tb,
    standard_liquid_density_kg_per_m3: std.density,
    standard_temperature_kelvin: std.t,
    standard_pressure_pascal: std.p,
    temperature_min_kelvin: form.column_range_kelvin[0],
    temperature_max_kelvin: form.column_range_kelvin[1],
    fluid_domain: {
      temperature_min_kelvin: domainLow, temperature_max_kelvin: fd.temperature_max,
      pressure_min_pascal: fd.pressure_min_pascal, pressure_max_pascal: fd.pressure_max_pascal,
      evidence: (fd.evidence ?? 'Fluid network only (not the column range): the ideal-gas Cp fit range, from max(ChemSep Cp data start, '
        + 'triple point) to {max} K; below it Cp is held and integrated, with a notice (a data-range end, never a validity floor). '
        + '{pmin} Pa..{pmax} Pa as the bundled light ends: PR78 from ChemSep critical constants, not qualified above that.')
        .replace('{max}', fd.temperature_max).replace('{pmin}', fd.pressure_min_pascal).replace('{pmax}', fd.pressure_max_pascal),
    },
    estimated_heavy_residue: false,
    ideal_gas_cp: { type: 'shifted_polynomial_5', reference_kelvin: 298.15, coefficients: cp.coefficients },
    models: { pr78: { critical_temperature_kelvin: c.tc, critical_pressure_pascal: c.pc, acentric_factor: c.omega } },
  };
  const visc = {};
  if (muL) visc.liquid = muL.entry;
  if (muV) visc.vapor = muV.entry;
  if (Object.keys(visc).length) property.viscosity = visc;
  // A compound with a crystal record takes the crystal's anchor as its freezing data (as the existing CO2 dataset).
  property.freezing = s.from_crystal ? crystalFreezing(s.id, s.from_crystal, s.freezing_note)
    : freezingRecord(c, s.freezing_override, s.constants?.freezing_note);
  crystalGap(s.id, s.from_crystal, property.freezing);
  // An entry may carry "thermochemistry": {formula, reference_element, limits} (the thermochemistry_only form).
  if (s.thermochemistry) {
    const e = { component: s.id, ...s.thermochemistry };
    property.thermochemistry = thermochemistryRecord(c, e);
    if (e.limits) property.flammability = flammabilityRecord(e);
  }
  // Group counts always (plan section 8); a gas's Henry constant in water when the entry gives its coefficients.
  const groupRecord = groupCounts(c, s.id, s.authored_counts);
  if (groupRecord) property.group_counts = groupRecord;
  if (s.henry_in_water) property.henry_in_water = henryRecord({ component: s.id, ...s.henry_in_water });
  if (s.homomorph) property.homomorph = homomorphRecord(s.id, s.homomorph);
  write(`properties/${propertiesId.replace(/^[a-z0-9_.-]+:/, '')}.json`, property);

  references.push({
    component: s.id, temperatureKelvin: ref.t, pressurePascal: ref.p, molarVolumeCubicMetres: ref.v,
    source: `${credit(c)}: saturated liquid at Tr = 0.7 (${ref.t} K), molar volume from liquid density ${eqText(c.rho)}, `
      + `pressure from vapour pressure ${eqText(c.psat)}`,
  });
  report.compounds.push({
    id: s.id, cas: c.cas, chemsep: c.name, index: c.index, tc: c.tc, pc: c.pc, omega: c.omega, tb: c.tb, tm: c.tm, tt: c.tt, pt: c.pt,
    hfus_j_per_mol: c.hfus === null ? null : c.hfus / 1000,
    cp_fit: { low: cp.low, high: cp.high, worst: cp.worst, worst_at: cp.worstAt, worst_298_900: cp.worstHigh, eq: c.cp.eqno, data: [c.cp.tmin, c.cp.tmax] },
    standard: { t: std.t, p: std.p, density: std.density },
    reference: { t: ref.t, p: ref.p, v: ref.v, liquid_root: ref.liquidRoot, pr_liquid_volume: ref.prLiquid,
      volume_shift: ref.prLiquid === null ? null : ref.v - ref.prLiquid, pr_saturation: ref.prSaturation, omega_implied: ref.omegaImplied },
    triple_pressure_vs_eq101: c.tt !== null && c.pt ? c.pt / evaluate(c.psat, c.tt) : null,
    liquid_viscosity: muL ? { low: muL.entry.temperature_min_kelvin, high: muL.entry.temperature_max_kelvin, nodes: muL.entry.temperatures_kelvin.length,
      data: [muL.equation.tmin, muL.equation.tmax], end_vs_vapor: muV ? muL.entry.coefficients.at(-1) / evaluate(c.muV, muL.entry.temperature_max_kelvin) : null } : null,
    vapor_viscosity: muV ? { low: muV.entry.temperature_min_kelvin, high: muV.entry.temperature_max_kelvin, nodes: muV.entry.temperatures_kelvin.length,
      data: [muV.equation.tmin, muV.equation.tmax] } : null,
    estimates,
  });
}

// Liquid volume reference points (plan 3.5): one record for the imported compounds.
if (selection.volume_reference) {
  write(`transport/${selection.volume_reference.file}.json`, {
    schema_version: 1, id: selection.volume_reference.id, type: 'liquid_volume_reference_points',
    revision: selection.volume_reference.revision,
    source: 'ChemSep v8.3 (Kooijman and Taylor, Artistic License 2.0) as embedded in DWSIM 10.2.5: each point is the saturated '
      + 'liquid at Tr = 0.7 from the compound\'s liquid density (eq. 105) and vapour pressure (eq. 101); individual sources per point.',
    points: references,
  });
}

// ---------------------------------------------------------------------------------------------------- pairs (D3)
// Pairs involve an imported compound only, so a selection without compounds reads no pair file (the groups extract
// tmp/dwsim-groups holds none), and neither does one with `no_pair_library` (compounds whose pairs are derived).
const importing = selection.compounds.length > 0 && !selection.no_pair_library;
const kij = importing ? readKijPr(dwsimDir) : { values: new Map(), conflicts: [] };
const dechema = importing ? readDechema(dwsimDir, byIndex) : { entries: [], unmapped: 0, ipd: [] };
if (kij.conflicts.length) report.notes.push('kij_pr.dat triangle conflicts: ' + kij.conflicts.join('; '));
if (importing) report.notes.push(`pr_ip.dat: ${dechema.entries.length} rows, ${dechema.unmapped} name a ChemSep index outside chemsep1/2.xml; `
  + `pr.ipd (2009 corrections, cross-check only): ${dechema.ipd.length} rows`);
const fresh = selection.compounds.map((s) => ({ id: s.id, cas: s.cas, fresh: true }));
const partners = (selection.existing ?? []).map((e) => ({ id: e.id, cas: e.cas, fresh: false }));
const universe = [...partners, ...fresh];
const pairs = [], counts = { kij_pr: 0, dechema: 0, exception: 0, zero_rule: 0, none: 0 }, nonePairs = [];
for (let i = 0; i < universe.length; i++) for (let j = i + 1; j < universe.length; j++) {
  const a = universe[i], b = universe[j];
  if (!a.fresh && !b.fresh) continue; // existing pairs unchanged
  const key = pairKey(a.cas, b.cas);
  // Owner exceptions to D3 (pair-exceptions.json): a zero compound gets no pair; an override replaces the D3 value.
  const zero = EXCEPTIONS.zero_compounds.find((z) => z.cas === a.cas || z.cas === b.cas);
  if (zero) { counts.zero_rule++; continue; }
  const override = EXCEPTIONS.overrides.find((o) => pairKey(o.first_cas, o.second_cas) === key);
  if (override) { pairs.push({ first: a.id, second: b.id, kij: override.kij, source: override.source }); counts.exception++; continue; }
  const d = kij.values.get(key);
  if (d) {
    pairs.push({ first: a.id, second: b.id, kij: d.kij, source: `DWSIM 10.2.5 kij_pr.dat (${d.cell})` }); counts.kij_pr++; continue;
  }
  const candidates = dechema.entries.filter((e) => e.a && e.b && pairKey(e.a, e.b) === key);
  const chosen = chooseDechema(candidates);
  const ipd = dechema.ipd.filter((r) => pairKey(r.a, r.b) === key).map((r) => r.kij);
  if (chosen ? !ipd.includes(chosen.kij) : ipd.length)
    report.notes.push(`${a.id}/${b.id}: pr_ip.dat ${chosen ? chosen.kij : 'none'}, pr.ipd (2009) [${ipd.join(', ')}]`);
  if (chosen) {
    const range = chosen.range ? `${chosen.range[0]}..${chosen.range[1]} K` : 'no stated temperature';
    pairs.push({ first: a.id, second: b.id, kij: chosen.kij,
      source: `DECHEMA PR via ChemSep pr_ip.dat row ${chosen.row}: "${chosen.comment}" (${range}; nearest 300..600 K of ${candidates.length})` });
    counts.dechema++;
    report.pairs.push({ pair: a.id + '/' + b.id, kij: chosen.kij, source: 'dechema', range: chosen.range, candidates: candidates.length,
      alternatives: candidates.map((e) => [e.kij, e.range ? e.range.join('-') : '-']) });
    continue;
  }
  counts.none++; nonePairs.push(a.id + '/' + b.id);
}
if (selection.pair_library) {
  write(`interactions/${selection.pair_library.file}.json`, {
    schema_version: 1, id: selection.pair_library.id, model: 'pr78', revision: selection.pair_library.revision,
    source: 'PR78 k_ij for the pairs that involve an imported compound (owner decision D3): DWSIM 10.2.5 kij_pr.dat; else the DECHEMA '
      + 'Peng-Robinson value of ChemSep pr_ip.dat (Kooijman and Taylor, Artistic License 2.0) whose fitted temperature range is nearest '
      + '300..600 K; else no pair (the method\'s zero). Owner exceptions (tools/compound-import/pair-exceptions.json): no Hydrogen '
      + 'pair; Methane/HydrogenSulfide from ChemSep pr.ipd 2009. Pairs between existing compounds are left to their own libraries.',
    pairs,
  });
}
report.pair_counts = counts;
report.pairs_without_data = nonePairs;

// ---------------------------------------------------------------------------------------------------- existing datasets
const riazi = (m) => 397 - Math.exp(6.5096 - 0.14187 * m ** 0.47);
for (const e of selection.freezing_only ?? []) {
  let record;
  if (e.estimate === 'riazi_al_sahhaf_1995') {
    if (!materialsDir) throw new Error('--materials is required for the crude-fraction estimate');
    const p = JSON.parse(readFileSync(join(materialsDir, 'properties', e.properties_file), 'utf8'));
    const m = p.molecular_weight_kg_per_mol * 1000;
    record = { normal_melting_kelvin: riazi(m), estimated: true,
      source: `Estimated upper bound: the melting point of the n-paraffin of the fraction's molar mass ${m} g/mol (${p.id}, revision ${p.revision}), `
        + 'Riazi and Al-Sahhaf, Ind. Eng. Chem. Res. 34 (1995) 4145, Tm = 397 - exp(6.5096 - 0.14187 M^0.47), fitted for C5..C40 (M about 70..565)'
        + (m > 565 ? '; this M lies beyond C40, so the value is extrapolated' : '') + '. A fraction freezes over a range (cloud and pour points lie lower). '
        + 'Data only: never a validity floor or a range end.',
      revision: estimateFreezingRevision };
  } else if (e.from_crystal) {
    record = crystalFreezing(e.component, e.from_crystal, e.note);
  } else {
    record = freezingRecord(chemsep(e.cas));
  }
  crystalGap(e.component, e.from_crystal ?? e.crystal, record);
  write(`freezing/${e.properties_file}`, { properties: e.properties, component: e.component, freezing: record });
  report.freezing_only.push({ component: e.component, properties: e.properties, ...record, source: undefined });
}
// Wrappers name their record by id and path (relative to the materials folder): a dataset, or the water model.
for (const e of selection.thermochemistry_only ?? []) {
  const path = e.path ?? 'properties/' + e.properties_file, name = path.split('/').pop();
  const target = { id: e.properties, path, ...(e.path ? {} : { component: e.component }) };
  const record = thermochemistryRecord(chemsep(e.cas), e);
  write(`thermochemistry/${name}`, { ...target, thermochemistry: record });
  const flammability = e.limits ? flammabilityRecord(e) : null;
  if (flammability) write(`flammability/${name}`, { ...target, flammability });
  report.thermochemistry_only.push({ component: e.component, formation_enthalpy_j_per_mol: record.formation_enthalpy_j_per_mol,
    elements: record.elements, lfl: flammability?.lfl_volume_fraction ?? null, ufl: flammability?.ufl_volume_fraction ?? null });
}
// Group counts and Henry constants for existing datasets (aqueous solvents, M3), as wrappers for insert-subrecord.mjs.
for (const e of selection.groups_only ?? []) {
  const target = { id: e.properties, path: 'properties/' + e.properties_file, component: e.component };
  const counts = groupCounts(e.cas ? chemsep(e.cas) : null, e.component, e.authored_counts);
  if (counts) write(`group_counts/${e.properties_file}`, { ...target, group_counts: counts });
  report.groups_only.push({ component: e.component, schemes: counts ? Object.fromEntries(Object.entries(counts).map(([k, v]) => [k, v.counts])) : {} });
}
for (const e of selection.henry_only ?? []) {
  const henry = henryRecord(e);
  write(`henry_in_water/${e.properties_file}`, { id: e.properties, path: 'properties/' + e.properties_file, component: e.component, henry_in_water: henry });
  report.henry_only.push({ component: e.component, coefficients: henry.coefficients });
}

// Water as a compound of the cubic (aqueous solvents, plan M2): the entry's PR78 constants, alpha and group counts, the
// water model's molar mass and its vapour's Shomate Cp fitted as shifted_polynomial_5 on the import's grid.
if (selection.cubic_water) {
  const w = selection.cubic_water;
  if (!materialsDir) throw new Error('cubic_water: --materials is required (the water model)');
  const model = JSON.parse(readFileSync(join(materialsDir, w.water_model), 'utf8'));
  const [A, B, C, D, E] = model.shomate_coefficients;
  const shomate = (x) => { const s = x / 1000; return A + B * s + C * s * s + D * s * s * s + E / (s * s); };
  const low = model.triple_point, high = form.cp_fit.high, step = form.cp_fit.grid_step_kelvin, ts = [low];
  for (let x = Math.ceil(low / step) * step; x <= high + 1e-9; x += step) if (x > low) ts.push(x);
  const coefficients = polyfit(ts.map((x) => x - 298.15), ts.map(shomate), 5, high - 298.15);
  let worst = 0, worstAt = low;
  for (let i = 0; i <= 4000; i++) {
    const x = low + (high - low) * i / 4000, e = shifted(coefficients, x) / shomate(x) - 1;
    if (Math.abs(e) > Math.abs(worst)) { worst = e; worstAt = x; }
  }
  if (!REVISION.test(w.revision ?? '')) throw new Error('cubic_water: invalid revision ' + w.revision);
  const fd = form.fluid_domain;
  const property = {
    schema_version: 1, id: w.id, component: 'Water', revision: w.revision,
    source: w.source + ` Molar mass ${model.molar_mass} kg/mol and the vapour Shomate Cp of the water model ${model.id} (${model.revision}), `
      + `fitted by least squares as shifted_polynomial_5 over ${low}..${high} K (${ts.length} points), largest deviation ${(worst * 100).toFixed(3)} % at ${worstAt.toFixed(1)} K. `
      + 'Imported by tools/compound-import (cubic_water).',
    molecular_weight_kg_per_mol: model.molar_mass,
    normal_boiling_point_kelvin: w.normal_boiling_point_kelvin,
    standard_liquid_density_kg_per_m3: w.standard_liquid_density_kg_per_m3,
    standard_temperature_kelvin: STANDARD_T,
    standard_pressure_pascal: STANDARD_P,
    temperature_min_kelvin: form.column_range_kelvin[0],
    temperature_max_kelvin: form.column_range_kelvin[1],
    fluid_domain: { temperature_min_kelvin: low, temperature_max_kelvin: fd.temperature_max, pressure_min_pascal: fd.pressure_min_pascal,
      pressure_max_pascal: fd.pressure_max_pascal, evidence: 'Fluid network only: the water model Cp fit range, from its triple point.' },
    estimated_heavy_residue: false,
    ideal_gas_cp: { type: 'shifted_polynomial_5', reference_kelvin: 298.15, coefficients },
    models: { pr78: { critical_temperature_kelvin: w.constants.tc, critical_pressure_pascal: w.constants.pc, acentric_factor: w.constants.omega,
      alpha: { type: 'mathias_copeman', coefficients: w.alpha } } },
  };
  // With a crystal record, the crystal's anchor is the freezing data (as an imported compound's from_crystal).
  if (w.from_crystal) {
    property.freezing = crystalFreezing('Water', w.from_crystal, w.freezing_note);
    crystalGap('Water', w.from_crystal, property.freezing);
  }
  const groups = groupCounts(null, 'Water', w.authored_counts);
  if (groups) property.group_counts = groups;
  write(`properties/${w.file}.json`, property);
  report.notes.push(`cubic_water: ${w.id}, Cp fit worst ${(worst * 100).toFixed(3)} % at ${worstAt.toFixed(1)} K`);
}

write('lang-en_us.json', lang);

// ---------------------------------------------------------------------------------------------------- cross-check
function readDataDoc(path) {
  if (!existsSync(path)) return null;
  const rows = new Map(); let header = null;
  for (const line of readFileSync(path, 'utf8').split(/\r?\n/)) {
    if (!line.startsWith('|')) { header = null; continue; }
    const cells = line.split('|').slice(1, -1).map((s) => s.trim());
    if (cells[0] === 'Compound' || cells[0] === 'Component id' || cells[0] === 'Fraction') { header = cells; continue; }
    if (!header || /^-+$/.test(cells[0])) continue;
    const row = Object.fromEntries(header.map((h, i) => [h, cells[i]]));
    const key = row.CAS ?? row['Component id'] ?? row.Fraction;
    rows.set(key, row);
  }
  return rows;
}
const number = (s) => { if (!s) return null; const m = s.replace(/−/g, '-').match(/-?\d+(\.\d+)?/); return m ? Number(m[0]) : null; };
const doc = readDataDoc(dataDoc);
const lines = [];
if (doc) {
  const pct = (a, b) => (a === null || b === null || a === undefined || b === undefined) ? '      -' : ((a / b - 1) * 100).toFixed(2).padStart(7) + '%';
  lines.push('Cross-check against ' + dataDoc.replace(/\\/g, '/').split('/').slice(-2).join('/') + ' (ChemSep / document - 1)');
  lines.push('id                 Tc K     dTc      Pc MPa   dPc      omega     domega   Tb K     dTb      Tf K     dTf');
  for (const s of selection.compounds) {
    const c = withConstants(byCas.get(s.cas), s.constants), row = doc.get(s.cas) ?? doc.get(s.id);
    if (!row) { lines.push(s.id.padEnd(18) + ' not in the document'); continue; }
    const tf = number(row['Tf (kind)'] ?? row['Tf K']);
    lines.push([s.id.padEnd(18), String(c.tc).padEnd(8), pct(c.tc, number(row.Tc)), String(c.pc / 1e6).padEnd(8), pct(c.pc / 1e6, number(row.Pc)),
      String(c.omega).padEnd(9), pct(c.omega, number(row.omega)), String(c.tb).padEnd(8), pct(c.tb, number(row.Tb)),
      String(freezingPoint(c)).padEnd(8), pct(freezingPoint(c), tf)].join(' '));
  }
}

// ---------------------------------------------------------------------------------------------------- output
write('report.json', report);
console.log('Wrote ' + written.length + ' files to ' + resolve(outDir));
console.log('\nCompound           Cp fit K        worst %  @K     298-900 %  liquid mu K (nodes)     vapour mu K (nodes)   Vref Tr0.7: P Pa, PR root, omega(eq101)  estimates');
for (const r of report.compounds) {
  console.log([r.id.padEnd(18), `${r.cp_fit.low}..${r.cp_fit.high}`.padEnd(15), (r.cp_fit.worst * 100).toFixed(3).padStart(7), r.cp_fit.worst_at.toFixed(0).padStart(5),
    (Math.abs(r.cp_fit.worst_298_900) * 100).toFixed(3).padStart(9),
    (r.liquid_viscosity ? `${+r.liquid_viscosity.low.toFixed(2)}..${+r.liquid_viscosity.high.toFixed(2)} (${r.liquid_viscosity.nodes})` : '-').padEnd(23),
    (r.vapor_viscosity ? `${+r.vapor_viscosity.low.toFixed(2)}..${+r.vapor_viscosity.high.toFixed(2)} (${r.vapor_viscosity.nodes})` : '-').padEnd(21),
    `${r.reference.p.toPrecision(5)} ${r.reference.liquid_root ? 'yes' : 'NO'} ${r.reference.omega_implied.toFixed(4)}/${r.omega}`.padEnd(40),
    r.estimates.join('; ') || '-'].join(' '));
}
console.log(`\nPairs: kij_pr.dat ${counts.kij_pr}, DECHEMA ${counts.dechema}, owner exception ${counts.exception}, `
  + `zero by rule ${counts.zero_rule}, none ${counts.none} (of ${Object.values(counts).reduce((x, y) => x + y, 0)})`);
for (const p of report.pairs) console.log(`  DECHEMA ${p.pair} = ${p.kij} (${p.range ? p.range.join('..') + ' K' : 'no T'}; ${p.candidates} candidates: ${p.alternatives.map((a) => a.join('@')).join(', ')})`);
for (const n of report.notes) console.log('Note: ' + n);
for (const g of report.groups_only) console.log(`Groups ${g.component.padEnd(16)} ${Object.entries(g.schemes).map(([k, v]) => k + ' ' + JSON.stringify(v)).join('; ') || 'none'}`);
for (const r of report.group_counts_refused) console.log('Group counts refused: ' + r);
if (lines.length) console.log('\n' + lines.join('\n'));
