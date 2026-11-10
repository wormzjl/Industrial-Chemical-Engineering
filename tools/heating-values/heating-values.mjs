// Prebuilds each material record's lower heating value (batch 2026-11-09-furnace-fuel-power, owner decision F2) from its
// thermochemistry sub-record by Hess's law, and writes one wrapper per record for insert-subrecord.mjs (key "combustion").
//
// Usage: node heating-values.mjs <materials dir> <out dir>
//
// The fuel CcHhNnOoSs burns to c CO2 + h/2 H2O (gas) + s SO2 + n/2 N2. LHV = Hf(fuel) - c Hf(CO2) - h/2 Hf(H2O) - s Hf(SO2),
// J/mol, on the ideal-gas heats of formation at 298.15 K of the bundled records themselves (carbon dioxide, the water
// model, sulfur dioxide): the same basis the crude fractions' estimated formation enthalpies were back-computed on. A
// record that gives no heat (products, inerts, oxygen) gets 0; a negative result (never for the shipped records) also 0.
import { readFileSync, writeFileSync, readdirSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

const [materials, out] = process.argv.slice(2);
if (!materials || !out) {
  console.error('usage: node heating-values.mjs <materials dir> <out dir>');
  process.exit(2);
}
const REVISION = 'hess-lhv-r1';
const read = (path) => JSON.parse(readFileSync(join(materials, path), 'utf8'));
const records = [];
for (const name of readdirSync(join(materials, 'properties')).filter((n) => n.endsWith('.json')).sort())
  records.push({ path: 'properties/' + name, record: read('properties/' + name) });
for (const name of readdirSync(join(materials, 'water')).filter((n) => n.endsWith('.json')).sort())
  records.push({ path: 'water/' + name, record: read('water/' + name) });
const product = (component) => {
  const found = records.filter((r) => (r.record.component ?? 'Water') === component && r.record.thermochemistry);
  if (found.length !== 1) throw new Error('expected one record of ' + component + ' with thermochemistry, found ' + found.length);
  return found[0];
};
const co2 = product('CarbonDioxide'), h2o = records.find((r) => r.path.startsWith('water/')), so2 = product('SulfurDioxide');
const hf = (r) => r.record.thermochemistry.formation_enthalpy_j_per_mol;
mkdirSync(out, { recursive: true });
let written = 0;
for (const r of records) {
  const t = r.record.thermochemistry;
  if (!t) continue;
  const e = t.elements, c = e.C ?? 0, h = e.H ?? 0, s = e.S ?? 0;
  const lhv = Math.max(0, t.formation_enthalpy_j_per_mol - c * hf(co2) - h / 2 * hf(h2o) - s * hf(so2));
  const source = 'Hess\'s law on this record\'s thermochemistry (' + t.revision + '): the formula burnt to CO2, H2O (gas), SO2 and N2 '
    + 'on the ideal-gas heats of formation at 298.15 K of ' + co2.record.id + ' (' + co2.record.thermochemistry.revision + '), '
    + h2o.record.id + ' (' + h2o.record.thermochemistry.revision + ') and ' + so2.record.id + ' (' + so2.record.thermochemistry.revision + '). '
    + (lhv === 0 ? 'Gives no heat. ' : '') + 'Computed by tools/heating-values/heating-values.mjs. Read by a fired heater\'s burners.';
  const wrapper = { id: r.record.id, path: r.path, combustion: { lower_heating_value_j_per_mol: lhv, estimated: t.estimated, source, revision: REVISION } };
  if (r.record.component !== undefined) wrapper.component = r.record.component;
  const file = r.path.replace('/', '__');
  writeFileSync(join(out, file), JSON.stringify(wrapper, null, 2) + '\n');
  const mw = r.record.molecular_weight_kg_per_mol ?? r.record.molar_mass;
  console.log((r.record.component ?? 'Water').padEnd(18), (lhv / 1000).toFixed(1).padStart(9), 'kJ/mol', mw ? (lhv / mw / 1e6).toFixed(2).padStart(7) + ' MJ/kg' : '');
  written++;
}
console.log('wrote ' + written + ' combustion wrappers');
