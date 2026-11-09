// Entry point 3: the four generated crude grades (E10, E11, E14) as assay records, mass basis.
// Writes <out>/assays/<id>.json and <out>/grades.json (compositions and metadata the other entry points read).
// Run: node tools/crude-grades/grades.mjs
import path from 'node:path';
import {loadComponents, OUT} from './lib/materials.mjs';
import {cutBins, compose, blend, standardDensity, tbpYields, cutYields, solveA, solveAB, meanMolarMass, SPLIT_CRUDE, SPLIT_CONDENSATE} from './lib/grades.mjs';
import {writeJson, isMain} from './lib/out.mjs';

export const REVISION = 'generated-r1';
export const T0_CRUDE = 309.22; // K, the N-pentane NBP: the C6+ distribution starts here
export const T0_BITUMEN = 473.15; // K, raw bitumen's own initial boiling point (200 C)
export const DILBIT_BITUMEN_FRACTION = 0.76; // E11
/** The specifications. `mode` api+residue solves A and B; residue fixes B and solves A (shape first, E10). */
export const SPECS = [
  {id: 'light_crude', label: 'Light crude', mode: 'api+residue', api: 40.0, lightEnds: 0.040, residue: 0.04, sulfur: 0.2, T0: T0_CRUDE, split: SPLIT_CRUDE,
    color: '#6B4A1E', transparency: 0.12, references: 'ECCC Alberta Sweet Mixed Blend #5 (512.5, API 35.7), Bakken (1969.1, API 40.3 measured)'},
  {id: 'medium_crude', label: 'Medium crude', mode: 'api+residue', api: 31.0, lightEnds: 0.025, residue: 0.18, sulfur: 1.5, T0: T0_CRUDE, split: SPLIT_CRUDE,
    color: '#45301E', transparency: 0.05, references: 'ECCC Alaska North Slope [2015] (2713.1, API 31.3)'},
  {id: 'heavy_crude', label: 'Heavy crude', mode: 'residue', B: 1.5, apiTarget: 21.5, lightEnds: 0.010, residue: 0.45, sulfur: 3.0, T0: T0_CRUDE, split: SPLIT_CRUDE,
    color: '#251A12', transparency: 0.02, references: 'ECCC Hebron [2018] (4029.1, API 19.9), Sockeye Sour (690, API 19.3), Western Canadian Select (2709.1, API 21.1)'},
];
const BITUMEN = {id: 'bitumen', mode: 'residue', B: 1.5, lightEnds: 0, residue: 0.50, sulfur: 4.9, T0: T0_BITUMEN, split: SPLIT_CRUDE};
const CONDENSATE = {id: 'condensate', mode: 'api+residue', api: 70.0, lightEnds: 0.25, residue: 0.0005, sulfur: 0.05, T0: T0_CRUDE, split: SPLIT_CONDENSATE};
const DILBIT = {id: 'oil_sand_dilbit', label: 'Oil-sand dilbit', apiReal: 21.5, color: '#16120E', transparency: 0.0,
  references: 'ECCC Cold Lake Blend Winter [2015] (2712, API 22.0), Western Canadian Select (2709.1, API 21.1)'};

const rho15 = rho60 => rho60 * Math.exp(613.9723 / rho60 ** 2 * 0.5556); // API MPMS 11.1 crude expansion, 60 F -> 15 C
const binsJson = b => b.map(v => Number.isFinite(v) ? +v.toFixed(3) : null);

function distribution(comps, spec) {
  const bins = cutBins(comps, spec.T0);
  const ab = spec.mode === 'residue' ? {B: spec.B, A: solveA(spec, spec.residue), bins} : solveAB(comps, spec);
  const p = {...spec, ...ab}, w = compose(comps, p);
  return {spec, A: p.A, B: p.B, T0: spec.T0, bins, w, density: standardDensity(comps, w), yields: tbpYields(p)};
}
function methodBlock(d) {
  return {distribution: 'riazi_1989_tbp_mass', formula: 'x(T) = 1 - exp(-(B/A) ((T - T0)/T0)^B), mass fraction of the C6+ part boiling below T',
    A: d.A, B: d.B, t0_kelvin: d.T0, cut_bins_kelvin: binsJson(d.bins), cut_bin_rule: 'each crude_pc cut takes the mass between the midpoints of its NBP and its neighbours\' NBPs; crude_pc12 open above (null)',
    light_end_split_by_mass: d.spec.split};
}
const evidence = (extra, methane = true) => [
  'GENERATED_GRADE: no assay values; Riazi 1989 TBP distribution on the shared crude_pc01..12 cuts, shape first (E10): TBP, C1-C5 and 565 C+ meet the specification',
  'API_FROM_CUTS: density and API are what the shared cuts give; the cuts carry the Watson K (11.6-12.0) of the Tia Juana Light characterisation',
  'LIGHT_END_SPLIT: the C1-C5 split by mass is an assumption (CRUDE_GRADES_REVIEW 3.1)' + (methane ? '; its methane makes the grade apply only to packages holding Methane' : ''),
  'SULFUR_METADATA: sulfur is a specification for display only; no sulfur species are carried',
  'TRANSPORT: cut liquid viscosity Twu 1985 + ASTM D341 (twu-1985-d341-r1); liquid-mixture pair coefficient fitted to ECCC whole crudes (OGL-Canada)',
  ...extra];
function assay(comps, id, label, w, provenance, color, transparency) {
  const amounts = {}; for (const c of comps) if ((w[c.name] || 0) > 0) amounts[c.name] = w[c.name];
  return {schema_version: 1, id: 'icheme:' + id, basis: 'mass', provenance, appearance: {color, transparency, estimated: true}, revision: REVISION, amounts_by_component: amounts};
}
function wholeCrude(comps, w, sulfur) {
  const d = standardDensity(comps, w);
  return {density_15c_kg_per_m3: +rho15(d.rho60).toFixed(2), api_gravity: +d.api.toFixed(2), sulfur_mass_percent: sulfur, mean_molar_mass_g_per_mol: +meanMolarMass(comps, w).toFixed(2),
    generated: true, note: 'density at 15 C by ideal volume additivity of the cuts\' standard densities (API MPMS 11.1 crude expansion from 60 F); sulfur is the specification, metadata only'};
}
const rounded = y => Object.fromEntries(Object.entries(y).map(([k, v]) => [k, +v.toFixed(5)]));

export function buildGrades(comps = loadComponents()) {
  const grades = [];
  for (const spec of SPECS) {
    const d = distribution(comps, spec), cy = cutYields(comps, d.w);
    const target = spec.mode === 'api+residue' ? {api: spec.api} : {api_expected_class: spec.apiTarget, b_fixed: spec.B};
    const provenance = {source_reference: 'generated by tools/crude-grades/grades.mjs (batch 2026-11-04-open-thermo-data; E10, E14)', generated: true,
      advisory_evidence: evidence(spec.id === 'heavy_crude' ? ['HEAVY_TAIL: crude_pc12\'s open bin above 757 C holds ' + (d.w.crude_pc12 * 100).toFixed(1) + ' % of the mass'] : []),
      specification: {...target, light_ends_c1_c5_mass_fraction: spec.lightEnds, residue_565c_plus_mass_fraction: spec.residue, sulfur_mass_percent: spec.sulfur, open_references: spec.references},
      method: methodBlock(d), source_whole_crude: wholeCrude(comps, d.w, spec.sulfur),
      yields_tbp_mass_fraction: rounded(d.yields), yields_by_cut_nbp_mass_fraction: rounded(cy)};
    grades.push({id: spec.id, label: spec.label, w: d.w, sulfur: spec.sulfur, yields: d.yields, cutYields: cy, density: d.density, references: spec.references,
      record: assay(comps, spec.id, spec.label, d.w, provenance, spec.color, spec.transparency)});
  }
  const bit = distribution(comps, BITUMEN), con = distribution(comps, CONDENSATE), f = DILBIT_BITUMEN_FRACTION;
  const w = blend(comps, [[bit.w, f], [con.w, 1 - f]]), sulfur = +(f * BITUMEN.sulfur + (1 - f) * CONDENSATE.sulfur).toFixed(2);
  const y = Object.fromEntries(Object.keys(bit.yields).map(k => [k, f * bit.yields[k] + (1 - f) * con.yields[k]])), cy = cutYields(comps, w);
  const part = (d, s) => ({specification: {...(s.mode === 'residue' ? {b_fixed: s.B} : {api: s.api}), light_ends_c1_c5_mass_fraction: s.lightEnds, residue_565c_plus_mass_fraction: s.residue, sulfur_mass_percent: s.sulfur},
    method: methodBlock(d), api_on_cuts: +d.density.api.toFixed(2)});
  const provenance = {source_reference: 'generated by tools/crude-grades/grades.mjs (batch 2026-11-04-open-thermo-data; E10, E11, E14)', generated: true,
    advisory_evidence: evidence([
      `DILBIT_BLEND: ${f * 100} % generated raw bitumen (T0 473 K, B 1.5, 50 % 565 C+) + ${(1 - f) * 100} % generated condensate (API 70, 25 % C1-C5), by mass (E11)`,
      'DILBIT_LIMIT: on these cuts the dilbit reads API ' + standardDensity(comps, w).api.toFixed(1) + ' against about 21.5 for real dilbit, and its liquid viscosity is about 20 times too low (E11)'], false),
    specification: {api_real_dilbit: DILBIT.apiReal, bitumen_mass_fraction: f, sulfur_mass_percent: sulfur, open_references: DILBIT.references},
    blend: {bitumen: part(bit, BITUMEN), condensate: part(con, CONDENSATE)}, source_whole_crude: wholeCrude(comps, w, sulfur),
    yields_tbp_mass_fraction: rounded(y), yields_by_cut_nbp_mass_fraction: rounded(cy)};
  grades.push({id: DILBIT.id, label: DILBIT.label, w, sulfur, yields: y, cutYields: cy, density: standardDensity(comps, w), references: DILBIT.references,
    record: assay(comps, DILBIT.id, DILBIT.label, w, provenance, DILBIT.color, DILBIT.transparency)});
  return grades;
}

if (isMain(import.meta.url)) {
  const comps = loadComponents(), grades = buildGrades(comps);
  for (const g of grades) {
    writeJson(path.join(OUT, 'assays', g.id + '.json'), g.record);
    const sum = Object.values(g.record.amounts_by_component).reduce((a, b) => a + b, 0);
    console.log(g.id.padEnd(16), 'API', g.density.api.toFixed(2), 'rho15', rho15(g.density.rho60).toFixed(1), 'sum', sum.toFixed(12),
      Object.entries(g.yields).map(([k, v]) => k + ' ' + (v * 100).toFixed(1)).join(' | '));
  }
  writeJson(path.join(OUT, 'grades.json'), grades.map(({record, ...g}) => g));
}
