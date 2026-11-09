// W1: migrate the CDU17 literature fixture (src/test/resources/materials/cdu17-reference) to methods + compound lists.
import { readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';
const root = process.argv[2], dir = join(root, 'data/icheme/materials');
const read = (p) => JSON.parse(readFileSync(join(dir, p), 'utf8'));
const crlf = (t) => t.replace(/\r?\n/g, '\r\n');
const write = (p, o) => writeFileSync(join(dir, p), crlf(JSON.stringify(o, null, 2) + '\n'));
const old = read('packages/cdu17.json');
if (old.revision !== 'cdu17-tjl-kl1976-r2' || old.liquid_mixture || old.fluid_domain) throw new Error('unexpected cdu17 package');
mkdirSync(join(dir, 'methods'), { recursive: true });
write('methods/cdu17.json', { schema_version: 1, id: 'icheme:cdu17_pr78', revision: 'cdu17-pr78-r1', model: old.model, ideal_gas: 'fit',
  interactions: [old.interactions], missing_interactions: old.missing_interactions, water_model: old.water_model,
  pressure_min_pascal: old.pressure_min_pascal, pressure_max_pascal: old.pressure_max_pascal });
const shared = new Set(['Methane', 'Ethane', 'Propane']);
const files = { Methane: 'methane', Ethane: 'ethane', Propane: 'propane' };
const aliasesOf = {};
for (const [alias, id] of Object.entries(old.aliases)) (aliasesOf[id] ??= []).push(alias);
const components = old.components.map((c, i) => shared.has(c) ? { id: c, properties: old.properties[i] } : c);
old.components.forEach((c, i) => {
  const path = 'components/' + (files[c] ?? c) + '.json', o = read(path);
  if (o.id !== c) throw new Error('component file mismatch ' + path);
  const out = {};
  for (const [k, v] of Object.entries(o)) {
    out[k] = v;
    if (k === 'fallback') {
      if (!shared.has(c)) out.properties = old.properties[i];
      if (aliasesOf[c]) out.aliases = aliasesOf[c];
    }
  }
  write(path, out);
});
write('packages/cdu17.json', { schema_version: 1, id: old.id, revision: 'cdu17-tjl-kl1976-r3', method: 'icheme:cdu17_pr78',
  components, advisory_evidence: old.advisory_evidence });
const a = read('assays/cdu17.json');
if (a.package !== old.id || a.components.length !== a.amounts.length) throw new Error('unexpected cdu17 assay');
const out = {};
for (const [k, v] of Object.entries(a)) {
  if (k === 'package' || k === 'components') continue;
  if (k === 'id') { out.id = 'icheme:tia_juana_light_kl1976'; continue; }
  if (k === 'amounts') { out.amounts_by_component = Object.fromEntries(a.components.map((c, i) => [c, v[i]])); continue; }
  out[k] = v;
}
write('assays/cdu17.json', out);
const indexPath = join(root, 'index.json'), index = JSON.parse(readFileSync(indexPath, 'utf8'));
const at = index.indexOf('data/icheme/materials/packages/cdu17.json');
if (!index.includes('data/icheme/materials/methods/cdu17.json')) index.splice(at, 0, 'data/icheme/materials/methods/cdu17.json');
writeFileSync(indexPath, crlf(JSON.stringify(index, null, 2) + '\n'));
