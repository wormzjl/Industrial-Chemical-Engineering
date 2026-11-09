// W1 data migration (batch 2026-11-04-refinery-compounds): packages become a method plus a compound list.
// Usage: node migrate-data.mjs <materials dir> <materials-index.json>
import { readFileSync, writeFileSync, unlinkSync, mkdirSync } from 'node:fs';
import { join } from 'node:path';

const dir = process.argv[2], indexFile = process.argv[3];
const read = (p) => JSON.parse(readFileSync(join(dir, p), 'utf8'));
// Git checks these files out with CRLF (autocrlf); write them the same way.
const crlf = (t) => t.replace(/\r?\n/g, '\r\n');
const write = (p, o) => writeFileSync(join(dir, p), crlf(JSON.stringify(o, null, 2) + '\n'));
const old = {
  tjl19: read('packages/tjl19.json'), tjl20: read('packages/tjl20.json'),
  network: read('packages/tjl20_nitrogen.json'), pilot: read('packages/pilot_cryogenic.json'),
};
const bases = { crude_18: read('bases/crude_18.json'), crude_19: read('bases/crude_19.json'), network: read('bases/network.json') };

// Sanity: the three crude packages share water, pressure range, mixture correction and pair content.
for (const p of [old.tjl19, old.tjl20, old.network]) {
  if (p.water_model !== 'icheme:water' || p.pressure_min_pascal !== 1000 || p.pressure_max_pascal !== 2000000 || p.missing_interactions !== 'zero' || p.model !== 'pr78')
    throw new Error('crude package differs: ' + p.id);
  if (JSON.stringify(p.liquid_mixture) !== JSON.stringify(old.tjl20.liquid_mixture)) throw new Error('mixture differs: ' + p.id);
}
const pairs = (f) => JSON.stringify(read(f).pairs);
if (pairs('interactions/tjl19.json') !== pairs('interactions/tjl20.json')) throw new Error('tjl19 and tjl20 pairs differ');

mkdirSync(join(dir, 'methods'), { recursive: true });
write('methods/crude_pr78.json', {
  schema_version: 1, id: 'icheme:crude_pr78', revision: 'crude-pr78-r1', model: 'pr78', ideal_gas: 'fit',
  interactions: ['icheme:tjl20'], missing_interactions: 'zero', water_model: 'icheme:water',
  pressure_min_pascal: 1000, pressure_max_pascal: 2000000, liquid_mixture: old.tjl20.liquid_mixture,
});
write('methods/pilot_eppr78.json', {
  schema_version: 1, id: 'icheme:pilot_eppr78', revision: 'pilot-eppr78-r1', model: 'pr78', ideal_gas: 'spine',
  interactions: ['icheme:pilot_cryogenic'], missing_interactions: 'error', water_model: old.pilot.water_model,
  pressure_min_pascal: old.pilot.pressure_min_pascal, pressure_max_pascal: old.pilot.pressure_max_pascal,
});

// Component defaults: the crude family's datasets (crude_19 + the network's nitrogen), and CO2's only dataset.
const defaults = {};
for (const b of [bases.crude_19, bases.network]) b.components.forEach((c, i) => { defaults[c] = b.properties[i]; });
defaults['CarbonDioxide'] = 'icheme:pilot_carbon_dioxide';
const pkg = (o, rest) => ({ schema_version: 1, id: o.id, revision: rest.revision, method: rest.method, ...(rest.extends ? { extends: rest.extends } : {}),
  components: rest.components, ...(o.crystals ? { crystals: o.crystals } : {}), advisory_evidence: o.advisory_evidence });
write('packages/tjl19.json', pkg(old.tjl19, { revision: 'crude-regrouped-r2', method: 'icheme:crude_pr78', components: bases.crude_18.components }));
write('packages/tjl20.json', pkg(old.tjl20, { revision: 'crude-regrouped-r2', method: 'icheme:crude_pr78', components: bases.crude_19.components }));
write('packages/tjl20_nitrogen.json', pkg(old.network, { revision: 'crude-regrouped-r2', method: 'icheme:crude_pr78', extends: 'icheme:tjl20_methane', components: bases.network.components }));
// The pilot's datasets are its own family: each compound names its dataset; the spines are each compound's only one.
const pilotComponents = old.pilot.components.map((c, i) => old.pilot.properties[i] === defaults[c] ? c : { id: c, properties: old.pilot.properties[i] });
write('packages/pilot_cryogenic.json', pkg(old.pilot, { revision: 'pilot-cryogenic-p3-r2', method: 'icheme:pilot_eppr78', components: pilotComponents }));

const componentFiles = { Methane: 'methane', Ethane: 'ethane', Propane: 'propane', Isobutane: 'isobutane', 'N-butane': 'n_butane',
  Isopentane: 'isopentane', 'N-pentane': 'n_pentane', Nitrogen: 'nitrogen', CarbonDioxide: 'carbon_dioxide' };
for (let i = 1; i <= 12; i++) componentFiles['crude_pc' + String(i).padStart(2, '0')] = 'crude_pc' + String(i).padStart(2, '0');
for (const [component, file] of Object.entries(componentFiles)) {
  const path = 'components/' + file + '.json', o = read(path);
  if (o.id !== component) throw new Error('component file mismatch ' + path);
  if (!defaults[component]) throw new Error('no default for ' + component);
  const out = {};
  for (const [k, v] of Object.entries(o)) { out[k] = v; if (k === 'fallback') out.properties = defaults[component]; }
  if (!('properties' in out)) out.properties = defaults[component];
  write(path, out);
}

// Assays: compositions by compound, no package; the network copy of the Tia Juana blend is the same record.
for (const f of ['bonga', 'cold_lake_blend', 'dalia', 'tjl19', 'tjl20', 'upper_zakum', 'wti_light_export']) {
  const o = read('assays/' + f + '.json'); delete o.package; write('assays/' + f + '.json', o);
}
const n = read('assays/tjl20_nitrogen.json'), t = read('assays/tjl20.json');
if (n.id !== t.id || JSON.stringify(n.amounts_by_component) !== JSON.stringify(t.amounts_by_component) || n.basis !== t.basis
    || JSON.stringify(n.appearance) !== JSON.stringify(t.appearance)) throw new Error('network assay differs from tjl20');
for (const f of ['fluid_2', 'fluid_3', 'fluid_4']) { const o = read('presets/' + f + '.json'); delete o.package; write('presets/' + f + '.json', o); }

const removed = ['assays/tjl20_nitrogen.json', 'bases/crude_18.json', 'bases/crude_19.json', 'bases/network.json', 'interactions/tjl19.json'];
for (const f of removed) unlinkSync(join(dir, f));
const index = JSON.parse(readFileSync(indexFile, 'utf8'));
const prefix = 'data/icheme/materials/';
const next = index.filter((p) => !removed.map((r) => prefix + r).includes(p));
// Keep the index's own order; the methods folder sits between interactions and networks.
const at = next.indexOf(prefix + 'networks/default.json');
if (at < 0) throw new Error('index has no networks/default.json');
next.splice(at, 0, ...['methods/crude_pr78.json', 'methods/pilot_eppr78.json'].map((m) => prefix + m).filter((m) => !next.includes(m)));
writeFileSync(indexFile, JSON.stringify(next, null, 2) + '\n');
console.log('index', index.length, '->', next.length);
