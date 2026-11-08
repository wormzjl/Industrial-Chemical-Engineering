// O9 (batch 2026-11-04-open-thermo-data, decision E23): compares the two storage forms of the combustion data, offline.
// Reads data files only; runs nothing from them.
//
// Usage: node compare-combustion-forms.mjs <S1 wrapper dir>... --s2 <S2 wrapper dir>... --patches <s1.patch> <s2.patch>
//   S1 wrappers: combustion/<record>.json as tools/compound-import/import.mjs (selection-combustion-existing.json) and
//   tools/crude-grades/combustion.mjs write them. S2 wrappers: thermochemistry/ and flammability/<record>.json as
//   import.mjs (selection-thermochemistry-existing.json) and tools/crude-grades/thermochemistry.mjs write them; give the
//   folders that hold those two subfolders.
//
// Prints, per compound and cut, S1's stored LHV against the LHV Hess's law gives from S2 (fuel to CO2, H2O gas, SO2, N2 on
// S2's own CO2, H2O and SO2 records), the largest difference, what only S2 gives (O2 demand, CO2, H2O and SO2 formed per
// mole of fuel), and the cost of each form: fields per record and lines added in src/main and src/test by each patch.
import { readFileSync, readdirSync, existsSync } from 'node:fs';
import { join } from 'node:path';

const argv = process.argv.slice(2);
const at = (flag) => argv.indexOf(flag);
const s1Dirs = argv.slice(0, at('--s2')), s2Dirs = argv.slice(at('--s2') + 1, at('--patches')), patches = argv.slice(at('--patches') + 1);
if (at('--s2') < 1 || at('--patches') < 0 || patches.length !== 2) {
  console.error('usage: node compare-combustion-forms.mjs <S1 dir>... --s2 <S2 dir>... --patches <s1.patch> <s2.patch>');
  process.exit(2);
}
const read = (dir, sub) => {
  const out = new Map(), path = join(dir, sub);
  if (!existsSync(path)) return out;
  for (const f of readdirSync(path).filter((n) => n.endsWith('.json'))) {
    const w = JSON.parse(readFileSync(join(path, f), 'utf8'));
    out.set(w.component ?? (w.id === 'icheme:water' ? 'Water' : w.id), w);
  }
  return out;
};
const merge = (dirs, sub) => new Map(dirs.flatMap((d) => [...read(d, sub)]));
const s1 = merge(s1Dirs, '.'), thermo = merge(s2Dirs, 'thermochemistry'), flam = merge(s2Dirs, 'flammability');
const hf = (id) => { const t = thermo.get(id); if (!t) throw new Error('S2 has no thermochemistry for ' + id); return t.thermochemistry.formation_enthalpy_j_per_mol; };
const HF = { CO2: hf('CarbonDioxide'), H2O: hf('Water'), SO2: hf('SulfurDioxide') };
const n = (t, e) => t.elements[e] ?? 0;

console.log('Component         S1 LHV J/mol      S2 Hess J/mol     diff J/mol   rel        S1 water  S2 water  O2 dem   CO2     SO2   LFL S1/S2   UFL S1/S2');
let worst = 0, worstRel = 0, worstAt = '';
const rows = [...s1.entries()].sort((a, b) => a[0].localeCompare(b[0]));
for (const [id, w] of rows) {
  const c = w.combustion, t = thermo.get(id)?.thermochemistry, f = flam.get(id)?.flammability;
  if (!t) { console.log(id.padEnd(17) + ' S2 has no thermochemistry'); continue; }
  const lhv = t.formation_enthalpy_j_per_mol - n(t, 'C') * HF.CO2 - n(t, 'H') / 2 * HF.H2O - n(t, 'S') * HF.SO2;
  const diff = lhv - c.lhv_j_per_mol, rel = diff / c.lhv_j_per_mol;
  if (Math.abs(diff) > Math.abs(worst)) { worst = diff; worstAt = id; }
  worstRel = Math.max(worstRel, Math.abs(rel));
  const o2 = n(t, 'C') + n(t, 'H') / 4 + n(t, 'S') - n(t, 'O') / 2;
  const same = (a, b) => (a ?? '-') + '/' + (b ?? '-');
  console.log([id.padEnd(17), c.lhv_j_per_mol.toFixed(3).padStart(16), lhv.toFixed(3).padStart(16), diff.toExponential(2).padStart(10),
    rel.toExponential(1).padStart(9), c.water_formed_mol_per_mol.toFixed(3).padStart(8), (n(t, 'H') / 2).toFixed(3).padStart(8),
    o2.toFixed(3).padStart(7), n(t, 'C').toFixed(2).padStart(7), n(t, 'S').toFixed(0).padStart(5),
    same(c.lfl_volume_fraction, f?.lfl_volume_fraction).padStart(12), same(c.ufl_volume_fraction, f?.ufl_volume_fraction).padStart(12)].join(' '));
}
console.log(`\nLargest LHV difference: ${worst.toExponential(3)} J/mol (${worstAt}); largest relative ${worstRel.toExponential(2)}; over ${rows.length} records.`);
const onlyS2 = [...thermo.keys()].filter((id) => !s1.has(id)).sort();
console.log('S2 records with no S1 counterpart (products, inerts, water): ' + onlyS2.join(', '));

// ---- cost
const fields = (o) => Object.keys(o).length + (o.elements ? Object.keys(o.elements).length - 1 : 0);
const mean = (xs) => xs.reduce((a, b) => a + b, 0) / xs.length;
const s1Fields = rows.map(([, w]) => fields(w.combustion));
const s2Fields = [...thermo.values()].map((w) => fields(w.thermochemistry)), s2Limits = [...flam.values()].map((w) => fields(w.flammability));
console.log(`\nFields per record (an element count is a field):`);
console.log(`  S1 combustion: ${Math.min(...s1Fields)}..${Math.max(...s1Fields)} (mean ${mean(s1Fields).toFixed(1)}) on ${s1Fields.length} records`);
console.log(`  S2 thermochemistry: ${Math.min(...s2Fields)}..${Math.max(...s2Fields)} (mean ${mean(s2Fields).toFixed(1)}) on ${s2Fields.length} records; `
  + `flammability: ${Math.min(...s2Limits)}..${Math.max(...s2Limits)} (mean ${mean(s2Limits).toFixed(1)}) on ${s2Limits.length} records`);
for (const [name, file] of [['S1', patches[0]], ['S2', patches[1]]]) {
  const added = {}; let current = null;
  for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\+\+\+ b\/(.*)$/);
    if (m) { current = m[1].startsWith('src/main/java') ? 'src/main/java' : m[1].startsWith('src/main/resources') ? 'src/main/resources'
      : m[1].startsWith('src/test') ? 'src/test' : m[1].startsWith('tools') ? 'tools' : 'other'; continue; }
    if (current && line.startsWith('+') && !line.startsWith('+++')) added[current] = (added[current] ?? 0) + 1;
  }
  console.log(`  ${name} patch, lines added: ` + Object.entries(added).map(([k, v]) => `${k} ${v}`).join(', '));
}
