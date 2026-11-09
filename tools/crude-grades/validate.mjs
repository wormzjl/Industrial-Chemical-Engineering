// Entry point 6: the validation tables per grade (API, density, yields, whole-grade viscosity at 15 and 40 C against ECCC
// reference crudes) and the cut tables' ratio to the current Dalia tables. Reads <out>/grades.json and <out>/g-fit.json.
// Writes <out>/validation.md and <out>/validation.json. Run last: node tools/crude-grades/validate.mjs
import fs from 'node:fs';
import path from 'node:path';
import {loadComponents, OUT, ECCC_CSV, readJson, MATERIALS, evalTable} from './lib/materials.mjs';
import {moleFractions} from './lib/grades.mjs';
import {mixtureViscosity} from './lib/mixture.mjs';
import {d341} from './lib/correlations.mjs';
import {readSamples, bulk} from './lib/eccc.mjs';
import {cutTables} from './cut-tables.mjs';
import {writeJson, writeText, isMain} from './lib/out.mjs';

export const REFERENCES = {light_crude: ['512.5', '511.1', '1969.1'], medium_crude: ['2713.1', '507.3', '2152.2'],
  heavy_crude: ['4029.1', '690', '2709.1'], oil_sand_dilbit: ['2712', '2709.1']};

/** The current Dalia correction (symmetric_pair_groups_v1 with the bundled coefficients and descriptors), for reference. */
function currentDelta(comps, x, T) {
  const m = readJson(path.join(MATERIALS, 'methods/crude_pr78.json')).liquid_mixture, c = m.coefficients;
  let s = {x: 0, x2: 0, xh: 0, xh2: 0, xe: 0, xhe: 0, x2e: 0, xh2e: 0};
  for (const comp of comps) {
    const d = comp.record.liquid_mixture_descriptor ?? {participation: 0, residue_retention: 0, activation_slope: 0};
    const v = (x[comp.name] || 0) * d.participation, h = v * d.residue_retention, e = d.activation_slope;
    s.x += v; s.x2 += v * v; s.xh += h; s.xh2 += h * h; s.xe += v * e; s.xhe += h * e; s.x2e += v * v * e; s.xh2e += h * h * e;
  }
  const fa = s.x * s.x - s.x2, fh = s.xh * s.xh - s.xh2, ea = s.x * s.xe - s.x2e, eh = s.xh * s.xhe - s.xh2e;
  const q = 1000 * (1 / Math.min(T, m.slope_cap_kelvin) - 1 / m.reference_kelvin);
  return c[0] * (fa - fh) + c[1] * fh + c[2] * (ea - eh) * q + c[3] * eh * q;
}

if (isMain(import.meta.url)) {
  const comps = loadComponents();
  const grades = JSON.parse(fs.readFileSync(path.join(OUT, 'grades.json'), 'utf8'));
  const fit = JSON.parse(fs.readFileSync(path.join(OUT, 'g-fit.json'), 'utf8')), G = fit.representable.G, Gs = fit.full.G; // E15: the representable-subset G is the chosen one
  const tables = Object.fromEntries(Object.entries(cutTables(comps)).map(([k, b]) => [k, {t: b.temperatures_kelvin, c: b.coefficients}]));
  const eccc = new Map(readSamples(ECCC_CSV).filter(s => s.weathering === 'W0').map(s => [s.code, s]));
  const ref = code => {
    const s = eccc.get(code), b = bulk(s), out = {name: s.name, code, api: b.api, mu15: b.mu15, mu0: b.mu0};
    out.mu40 = b.mu0 && b.mu15 ? d341(b.mu0 / b.density15, 273.15, b.mu15 / b.density15, 288.15)(313.15) * b.density15 * (1 - 0.00085 * 25) : null;
    return out;
  };
  const rows = [], lines = ['# Validation of the generated grades', '', `G = ${G.toFixed(4)} (the crudes the cuts represent, E15); G = ${Gs.toFixed(4)} on the full ECCC set, for comparison. ECCC values: Open Government Licence - Canada; 40 C values are ASTM D341 extrapolations from the 0 and 15 C measurements (interpretation).`, '',
    '| Grade | API (rho15 kg/m3) | C1-C4 / naphtha / kero / diesel / VGO / 565+ (TBP mass %) | Same, cuts by NBP | mu 15 C / 40 C, Twu + G | Twu + G full set | Current Dalia + delta | ECCC references, mu 15 C / 40 C |', '|---|---|---|---|---|---|---|---|'];
  for (const g of grades) {
    const x = moleFractions(comps, g.w);
    const mu = (T, GG) => mixtureViscosity(comps, x, T, GG, tables) * 1e3;
    const cur = T => mixtureViscosity(comps, x, T, 0) * Math.exp(currentDelta(comps, x, T)) * 1e3;
    const r = {grade: g.id, api: g.density.api, rho15: g.density.rho60 * Math.exp(613.9723 / g.density.rho60 ** 2 * 0.5556),
      yields: g.yields, cutYields: g.cutYields, mu15: mu(288.15, G), mu40: mu(313.15, G), mu15_subsetG: mu(288.15, Gs), mu40_subsetG: mu(313.15, Gs),
      mu15_current: cur(288.15), mu40_current: cur(313.15), references: REFERENCES[g.id].map(ref)};
    rows.push(r);
    const y = o => ['gas', 'naphtha', 'kerosene', 'diesel', 'vgo', 'residue'].map(k => (o[k] * 100).toFixed(1)).join(' / ');
    const p = v => v === null || v === undefined ? 'n/a' : v >= 100 ? v.toFixed(0) : v.toPrecision(3);
    lines.push(`| ${g.id} | ${r.api.toFixed(1)} (${r.rho15.toFixed(1)}) | ${y(g.yields)} | ${y(g.cutYields)} | ${p(r.mu15)} / ${p(r.mu40)} | ${p(r.mu15_subsetG)} / ${p(r.mu40_subsetG)} | ${p(r.mu15_current)} / ${p(r.mu40_current)} | `
      + r.references.map(e => `${e.name} (${e.code}, API ${e.api}): ${p(e.mu15)} / ${p(e.mu40)}`).join('; ') + ' |');
  }
  lines.push('', '## Cut tables against the current Dalia tables', '', '| Cut | Twu mPa s 313 / 373 K | ratio to Dalia 313 / 373 K |', '|---|---|---|');
  const cutRows = [];
  for (const c of comps.filter(c => !c.light)) {
    const a = [313.15, 373.15].map(T => evalTable(tables[c.name], T)), d = [313.15, 373.15].map(T => evalTable(c.liq, T));
    cutRows.push({cut: c.name, twu_mpas: a.map(v => v * 1e3), ratio: a.map((v, i) => v / d[i])});
    lines.push(`| ${c.name} | ${a.map(v => (v * 1e3).toPrecision(4)).join(' / ')} | ${a.map((v, i) => (v / d[i]).toPrecision(3)).join(' / ')} |`);
  }
  writeJson(path.join(OUT, 'validation.json'), {G, G_subset: Gs, grades: rows, cuts: cutRows});
  writeText(path.join(OUT, 'validation.md'), lines.join('\n') + '\n');
  console.log(lines.join('\n'));
}
