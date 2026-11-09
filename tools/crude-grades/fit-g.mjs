// Entry point 2: the one-term pair coefficient G of the crude liquid-mixture viscosity (E12, E14), fitted by least squares
// in ln mu to ECCC whole-crude viscosities (Open Government Licence - Canada), each crude's boiling curve put on the
// shared cuts, with the Twu cut tables of cut-tables.mjs. Writes <out>/g-fit.json and <out>/g-fit.md.
// Run: node tools/crude-grades/fit-g.mjs [path to ECCC_Petroleum_Products_Database-2021-01-22.EN.csv]
import path from 'node:path';
import {loadComponents, OUT, ECCC_CSV} from './lib/materials.mjs';
import {readSamples, bulk, boiling} from './lib/eccc.mjs';
import {cutBins, standardDensity, moleFractions} from './lib/grades.mjs';
import {mixtureViscosity, pairFraction} from './lib/mixture.mjs';
import {cutTables} from './cut-tables.mjs';
import {writeJson, writeText, isMain} from './lib/out.mjs';

export const G_REVIEW = 0.878; // CRUDE_GRADES_REVIEW 4.2, six points
// R2: refined products and non-crude samples, by ECCC name.
export const REFINED = /bunker|diesel|fuel oil|\bhfo\b|\bifo\b|lube|pure drill|mv arrow|biodiesel|orimulsion/i;
// R5: the cut basis must represent the crude's chemistry: its curve on the cuts gives its measured API within this margin.
export const API_MARGIN = 3.0;
// R3b: a curve lighter than its crude by more than this is not a whole-crude curve (truncated SIMDIS); cut chemistry alone
// accounts for at most about 7 API on the ECCC set (dilbits, CRUDE_GRADES_REVIEW 3.2).
export const TRUNCATED_MARGIN = 10.0;
const PROPANE_NBP_C = -42.1;

/** Cumulative mass fraction F(T C) of a boiling curve: linear between points; below the first point, linear down to 0 at
 * the propane NBP; above the last point, a Riazi tail fitted to the curve's C6+ part, joined continuously. */
export function curveFunction(points) {
  const p = [...points].sort((a, b) => a[1] - b[1] || a[0] - b[0]).filter((q, i, a) => i === 0 || q[0] >= a[i - 1][0]);
  if (p[0][0] > 0) p.unshift([0, Math.min(PROPANE_NBP_C, p[0][1] - 1)]);
  const lin = T => { if (T <= p[0][1]) return 0; for (let i = 1; i < p.length; i++) if (T <= p[i][1]) return p[i - 1][0] + (p[i][0] - p[i - 1][0]) * (T - p[i - 1][1]) / (p[i][1] - p[i - 1][1]); return p.at(-1)[0]; };
  const [xl, Tl] = p.at(-1);
  if (xl >= 1) return {F: T => Math.min(1, lin(T)), tail: null};
  const T0 = 309.22, f0 = lin(36.07);
  const fit = p.filter(([x, T]) => T > 40 && x > f0 && x < 1).map(([x, T]) => [Math.log(Math.log(1 / (1 - (x - f0) / (1 - f0)))), Math.log((T + 273.15 - T0) / T0)]);
  const n = fit.length, sx = fit.reduce((s, q) => s + q[0], 0), sy = fit.reduce((s, q) => s + q[1], 0);
  const sxx = fit.reduce((s, q) => s + q[0] * q[0], 0), sxy = fit.reduce((s, q) => s + q[0] * q[1], 0);
  const slope = (n * sxy - sx * sy) / (n * sxx - sx * sx), B = 1 / slope, A = B * Math.exp((sy - slope * sx) / n * B);
  const X = T => T <= T0 ? 0 : 1 - Math.exp(-(B / A) * Math.pow((T - T0) / T0, B));
  const Xl = X(Tl + 273.15);
  if (!(B > 0) || !Number.isFinite(A) || !(Xl < 1)) throw new Error('tail fit failed');
  return {F: T => T <= Tl ? lin(T) : xl + (1 - xl) * (X(T + 273.15) - Xl) / (1 - Xl), tail: {A, B, T0}};
}

/** The crude's mass fractions on the 19 components: each component takes the curve's mass between the midpoints of its
 * NBP and its neighbours' NBPs (light ends included; crude_pc12 open above). */
export function curveOnCuts(comps, F) {
  const tb = comps.map(c => c.Tb - 273.15), b = [-Infinity];
  for (let i = 0; i < comps.length - 1; i++) b.push((tb[i] + tb[i + 1]) / 2);
  b.push(Infinity);
  const Fb = b.map(t => t === -Infinity ? 0 : t === Infinity ? 1 : F(t));
  return Object.fromEntries(comps.map((c, i) => [c.name, Fb[i + 1] - Fb[i]]));
}

export function ecccSet(comps, tables, file = ECCC_CSV) {
  const records = [], excluded = [];
  for (const s of readSamples(file)) {
    const id = `${s.name} (${s.code})`;
    if (s.weathering !== 'W0') continue; // R1: fresh samples only
    if (REFINED.test(s.name)) { excluded.push([id, 'R2 refined product or non-crude']); continue; }
    const b = bulk(s), curve = boiling(s);
    if (!curve) { excluded.push([id, 'R3 no boiling curve']); continue; }
    const methodRow = curve.kind === 'percent' ? s.keys.findIndex(k => k.startsWith('100%|')) + 1 : s.keys.findIndex(k => k.startsWith('Method|% w/w'));
    const methodName = (s.rows[methodRow]?.[s.column] ?? '').replace(/s+/g, ' ').trim() || curve.kind + ' block';
    if (/2887/.test(methodName)) { excluded.push([id, 'R3 ASTM D2887 curve (ends at 538 C; percentages of the eluted part, not of the whole crude)']); continue; }
    const pp = /^-?\d+(\.\d+)?$/.test(b.pourPoint) ? Number(b.pourPoint) : -Infinity;
    const points = [[273.15, b.mu0], [288.15, b.mu15]].filter(([T, m]) => m !== null && T - 273.15 > pp);
    if (!points.length) { excluded.push([id, b.mu0 === null && b.mu15 === null ? 'R4 no Newtonian viscosity at 0 or 15 C' : 'R4 viscosity points at or below the pour point']); continue; }
    let w;
    try { w = curveOnCuts(comps, curveFunction(curve.points).F); } catch (e) { excluded.push([id, 'R3 ' + e.message]); continue; }
    const apiCuts = standardDensity(comps, w).api, apiMeasured = b.api ?? (b.density15 ? 141.5 / (b.density15 * 1000 / 999.016) - 131.5 : null);
    if (apiMeasured !== null && apiCuts - apiMeasured > TRUNCATED_MARGIN) { excluded.push([id, `R3b truncated curve: API on the cuts ${apiCuts.toFixed(1)} against ${apiMeasured.toFixed(1)} measured`]); continue; }
    const x = moleFractions(comps, w), pair = pairFraction(comps, x);
    for (const [T, m] of points) {
      const base = mixtureViscosity(comps, x, T, 0, tables) * 1e3;
      records.push({id, name: s.name, code: s.code, method: methodName, T, measured: m, base, need: Math.log(m / base), pair, apiCuts, apiMeasured,
        representable: apiMeasured !== null && Math.abs(apiCuts - apiMeasured) <= API_MARGIN});
    }
  }
  return {records, excluded};
}

export function fitG(points) {
  const G = points.reduce((s, p) => s + p.need * p.pair, 0) / points.reduce((s, p) => s + p.pair * p.pair, 0);
  return {G, stats: residuals(points, G)};
}
export function residuals(points, G) {
  const r = points.map(p => p.need - G * p.pair), n = r.length;
  const mean = r.reduce((a, b) => a + b, 0) / n, rms = Math.sqrt(r.reduce((a, b) => a + b * b, 0) / n);
  const abs = r.map(Math.abs).sort((a, b) => a - b);
  return {points: n, crudes: new Set(points.map(p => p.id)).size, mean_ln: mean, rms_ln: rms, median_abs_ln: abs[Math.floor(n / 2)], max_abs_ln: abs[n - 1],
    rms_factor: Math.exp(rms), within_factor_2: r.filter(v => Math.abs(v) <= Math.log(2)).length};
}

if (isMain(import.meta.url)) {
  const comps = loadComponents();
  const tables = Object.fromEntries(Object.entries(cutTables(comps)).map(([k, b]) => [k, {t: b.temperatures_kelvin, c: b.coefficients}]));
  const {records, excluded} = ecccSet(comps, tables, process.argv[2] ?? ECCC_CSV);
  const full = fitG(records), rep = records.filter(p => p.representable), repFit = fitG(rep);
  const result = {
    source: 'Environment and Climate Change Canada, Environment Canada Crude Oil and Petroleum Product Database, 2021-01-22 (open.canada.ca dataset 53c38f91-35c8-49a6-a437-b311703db8c5). Contains information licensed under the Open Government Licence - Canada.',
    rules: {
      R1: 'fresh samples only (weathering W0)', R2: 'crude oils only; refined products and non-crudes excluded by name: ' + REFINED.source,
      R3: 'a whole-crude boiling curve (percent-recovered SIMDIS block or cumulative-weight block); ASTM D2887 curves excluded',
      R3b: `a curve whose API on the cuts exceeds the measured API by more than ${TRUNCATED_MARGIN} is truncated, not whole-crude: excluded`,
      R4: 'Newtonian dynamic viscosity at 0 and/or 15 C, each point above the sample\'s pour point',
      R5: `representable on the shared cuts: |API(curve on cuts) - API(measured)| <= ${API_MARGIN} (subset fit only)`,
      review_flag: 'the ECCC CSV carries no review flag; NOAA ADIOS "Not Reviewed" is NOAA curation status of the mirror and applies to every EC record read, so it is not used',
    },
    model: 'ln mu = sum_i x_i ln mu_i + G (x_cut^2 - sum_cuts x_i^2); Twu 1985 + D341 cut tables (twu-1985-d341-r1), game table semantics below 293.15 K',
    full: {G: full.G, stats: full.stats, stats_at_review_G: residuals(records, G_REVIEW), stats_at_zero: residuals(records, 0)},
    representable: {G: repFit.G, stats: repFit.stats, stats_at_review_G: residuals(rep, G_REVIEW), stats_at_full_G: residuals(rep, full.G)},
    nonrepresentable_at_full_G: residuals(records.filter(p => !p.representable), full.G),
    records, excluded,
  };
  writeJson(path.join(OUT, 'g-fit.json'), result);
  const f = v => v.toFixed(3), st = s => `n ${s.points} (${s.crudes} crudes), mean ${f(s.mean_ln)}, rms ${f(s.rms_ln)} (x${s.rms_factor.toFixed(2)}), median |r| ${f(s.median_abs_ln)}, max |r| ${f(s.max_abs_ln)}, within x2: ${s.within_factor_2}`;
  const lines = ['# G refit on the ECCC crude set', '', result.source, '', ...Object.entries(result.rules).map(([k, v]) => `- ${k}: ${v}`), '',
    `- Full set (R1-R4, R3b): G = ${f(full.G)}; ${st(full.stats)}`, `  - at G = ${G_REVIEW}: ${st(result.full.stats_at_review_G)}`, `  - at G = 0: ${st(result.full.stats_at_zero)}`,
    `- Representable subset (R5): G = ${f(repFit.G)}; ${st(repFit.stats)}`, `  - at G = ${G_REVIEW}: ${st(result.representable.stats_at_review_G)}`, `  - at full G: ${st(result.representable.stats_at_full_G)}`,
    `- Non-representable records at full G: ${st(result.nonrepresentable_at_full_G)}`, '',
    '| Record | Curve | T C | measured mPa s | base | at full G | API cuts / measured | R5 |', '|---|---|---|---|---|---|---|---|',
    ...records.map(p => `| ${p.id} | ${p.method} | ${p.T - 273.15} | ${p.measured} | ${p.base.toPrecision(3)} | ${(p.base * Math.exp(full.G * p.pair)).toPrecision(3)} | ${p.apiCuts.toFixed(1)} / ${p.apiMeasured === null ? 'n/a' : p.apiMeasured.toFixed(1)} | ${p.representable ? 'yes' : 'no'} |`),
    '', '## Excluded', '', ...excluded.map(([id, why]) => `- ${id}: ${why}`)];
  writeText(path.join(OUT, 'g-fit.md'), lines.join('\n') + '\n');
  console.log(lines.slice(0, 18).join('\n'));
}
