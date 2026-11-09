// W1: migrate the frozen TJL reference set (src/test/resources/column/v3/tjl-reference-resources.json) the way
// migrate-data.mjs migrated the bundled records: a method record, packages as compound lists, compound defaults, free assays.
import { readFileSync, writeFileSync } from 'node:fs';
const file = process.argv[2], text = readFileSync(file, 'utf8'), eol = text.includes('\r\n') ? '\r\n' : '\n';
const rows = JSON.parse(text);
const P = 'data/icheme/materials/';
const packages = rows.filter((r) => r.path.startsWith(P + 'packages/'));
if (packages.length !== 2) throw new Error('expected two packages');
for (const { record: p } of packages) {
  if (p.model !== 'pr78' || p.missing_interactions !== 'zero' || p.water_model !== 'icheme:water' || p.pressure_min_pascal !== 50000
      || p.pressure_max_pascal !== 2000000 || p.temperature_min_kelvin !== 298.15 || p.temperature_max_kelvin !== 900
      || Object.keys(p.aliases).length || p.liquid_mixture || p.fluid_domain || !['icheme:tjl19', 'icheme:tjl20'].includes(p.interactions))
    throw new Error('package differs: ' + p.id);
}
// Each compound's default dataset; the two packages agree where they share a compound.
const defaults = {};
for (const { record: p } of packages) p.components.forEach((c, i) => {
  if (defaults[c] && defaults[c] !== p.properties[i]) throw new Error('two datasets for ' + c);
  defaults[c] = p.properties[i];
});
const out = [];
let methodAdded = false;
const bump = (r) => r.replace(/-r(\d+)$/, (_, n) => '-r' + (Number(n) + 1));
for (const row of rows) {
  const r = row.record;
  if (row.path.startsWith(P + 'components/')) {
    if (!defaults[r.id]) throw new Error('no dataset for ' + r.id);
    const o = {}; for (const [k, v] of Object.entries(r)) { o[k] = v; if (k === 'fallback') o.properties = defaults[r.id]; }
    out.push({ path: row.path, record: o });
  } else if (row.path.startsWith(P + 'packages/')) {
    if (!methodAdded) {
      // The interactions/tjl19 library is gone; its pairs equal icheme:tjl20's (checked by migrate-data.mjs).
      out.push({ path: P + 'methods/test_tjl_pr78.json', record: { schema_version: 1, id: 'test:tjl_pr78', revision: 'test-tjl-pr78-r1',
        model: 'pr78', ideal_gas: 'fit', interactions: ['icheme:tjl20'], missing_interactions: 'zero', water_model: 'icheme:water',
        pressure_min_pascal: 50000, pressure_max_pascal: 2000000 } });
      methodAdded = true;
    }
    if (r.components.some((c, i) => r.properties[i] !== defaults[c])) throw new Error('dataset override in ' + r.id);
    out.push({ path: row.path, record: { schema_version: r.schema_version, id: r.id, revision: bump(r.revision), method: 'test:tjl_pr78',
      components: r.components, advisory_evidence: r.advisory_evidence } });
  } else if (row.path.startsWith(P + 'assays/')) {
    if (r.components.length !== r.amounts.length) throw new Error('assay length ' + r.id);
    const o = {};
    for (const [k, v] of Object.entries(r)) {
      if (k === 'package' || k === 'components') continue;
      if (k === 'amounts') o.amounts_by_component = Object.fromEntries(r.components.map((c, i) => [c, v[i]]));
      else o[k] = v;
    }
    out.push({ path: row.path, record: o });
  } else if (row.path.startsWith(P + 'properties/')) out.push(row);
  else throw new Error('unexpected record ' + row.path);
}
writeFileSync(file, (JSON.stringify(out, null, 1) + '\n').replace(/\n/g, eol));
console.log(rows.length, '->', out.length);
