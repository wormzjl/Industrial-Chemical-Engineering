// O7 of batch 2026-11-04-open-thermo-data (owner decision E8): the regrouped model's sidecar to schema 4. The trained
// physics travels with the model as values: the schema-3 block named the trained records by id@revision; this script reads
// those records as they were at training (git 03e7b6138, the batch's parent, before O2), checks that each declares the
// named revision, and writes only what the binding compares (TrainedPhysics): each compound's PR78 constants and its
// shifted_polynomial_5 ideal-gas Cp, the non-zero constant pairs, the water's saturation, latent-heat and vapour Shomate
// values, and the trained temperature range. Then the registry's pipelineSha256 (sha256(hex(sha256(payload)) + ":" +
// hex(sha256(sidecar)))), as tools/package-rework/sidecar-schema3.mjs did. The files are -text and CRLF: only the physics
// block and the schema line change. Run from the worktree root: node tools/open-thermo-data/sidecar-schema4.mjs [--write]
import { readFileSync, writeFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';

const write = process.argv.includes('--write');
const TRAINED = '03e7b6138';
const dir = 'src/main/resources/data/icheme/neural/';
const materials = 'src/main/resources/data/icheme/materials/';
const h = (b) => createHash('sha256').update(b).digest('hex');
const sidecarPath = dir + 'v3-column-transformer-regrouped.sidecar.json';
const text = readFileSync(sidecarPath, 'latin1');
if (!text.includes('\r\n')) throw new Error('expected CRLF');
const doc = JSON.parse(text);
if (doc.schemaVersion !== 3) throw new Error('expected schema 3, got ' + doc.schemaVersion);
const payload = readFileSync(dir + 'v3-column-transformer-regrouped.payload.json');
const components = JSON.parse(payload.toString('utf8')).components;
const old = doc.physics;
if (old.model !== 'pr78' || old.missingInteractions !== 'zero' || JSON.stringify(old.compounds.map(c => c.id)) !== JSON.stringify(components))
  throw new Error('unexpected schema-3 physics');
const at = (path) => JSON.parse(execFileSync('git', ['show', `${TRAINED}:${materials}${path}`], { encoding: 'utf8' }));
const named = (token, kind) => {
  const i = token.indexOf('@'); const id = token.slice(0, i), revision = token.slice(i + 1);
  const record = at(`${kind}/${id.replace(/^icheme:/, '')}.json`);
  if (record.id !== id || record.revision !== revision) throw new Error(`${token}: the record at ${TRAINED} is ${record.id}@${record.revision}`);
  return record;
};

// The compounds: PR78 constants and the ideal-gas fit (reference 298.15 K, the loader's rule).
const records = old.compounds.map(c => { const r = named(c.properties, 'properties'); if (r.component !== c.id) throw new Error(c.id); return r; });
const compounds = records.map((r, i) => {
  const pr = r.models.pr78, cp = r.ideal_gas_cp;
  if (!pr || !cp || cp.type !== 'shifted_polynomial_5' || cp.reference_kelvin !== 298.15 || cp.coefficients.length !== 6) throw new Error(r.id);
  return { id: components[i], criticalTemperatureKelvin: pr.critical_temperature_kelvin, criticalPressurePascal: pr.critical_pressure_pascal,
    acentricFactor: pr.acentric_factor, idealGasCp: cp.coefficients };
});
// The pairs: the libraries in order, the first that names a pair gives it (the loader's rule); missing pairs are zero.
const seen = new Set(), pairs = [];
for (const token of old.interactions) {
  const library = named(token, 'interactions');
  if (library.model !== 'pr78' || library.rule) throw new Error(token);
  for (const p of library.pairs) {
    if (!components.includes(p.first) || !components.includes(p.second)) continue;
    const key = [p.first, p.second].sort().join('\u0000');
    if (seen.has(key)) continue; seen.add(key);
    if (p.kij !== 0) pairs.push({ first: p.first, second: p.second, kij: p.kij });
  }
}
const w = named(old.water, 'water');
const water = { criticalTemperatureKelvin: w.critical_temperature, criticalPressurePascal: w.critical_pressure, saturationCoefficients: w.saturation_coefficients,
  boilingTemperatureKelvin: w.reference_boiling_temperature, vaporizationEnthalpyJoulesPerMol: w.reference_vaporization_enthalpy,
  watsonExponent: w.watson_exponent, shomateCoefficients: w.shomate_coefficients };

// The trained temperature range. The training journals are gone (research/INDEX.md, convergence review), so the evidence
// is the sidecar's own global bounds: the temperature-valued features are the feed (index 1), the condenser outlet
// specification (6) and the two steam feeds (17 + n + 20 + 9 + 4 p + 3). The lower end is the datasets' column floor
// (298.15 K, below the trained condenser minimum); the upper end is the largest trained temperature bound, the feed's.
const n = components.length, steam = [17 + n + 29 + 3, 17 + n + 29 + 7];
const floor = Math.max(...records.map(r => r.temperature_min_kelvin));
const bounds = { feed: [doc.globalMin[1], doc.globalMax[1]], condenser: [doc.globalMin[6], doc.globalMax[6]],
  steam: steam.map(i => [doc.globalMin[i], doc.globalMax[i]]) };
const top = Math.max(doc.globalMax[1], doc.globalMax[6], ...steam.map(i => doc.globalMax[i]));
if (!(doc.globalMin[6] >= floor) || top !== doc.globalMax[1]) throw new Error('unexpected bounds ' + JSON.stringify(bounds));
const physics = { model: 'pr78',
  source: `The values of the records the model was trained on (${old.compounds.map(c => c.properties).join(', ')}; ${old.interactions.join(', ')}; ${old.water}), read from git ${TRAINED} by tools/open-thermo-data/sidecar-schema4.mjs (batch 2026-11-04-open-thermo-data, O7). Trained range: ${floor} K (the datasets' column floor) to the trained feed temperature maximum (sidecar globalMax[1]). Pairs not listed are zero.`,
  temperatureMinimumKelvin: floor, temperatureMaximumKelvin: top, compounds, pairs, water };

const lines = text.split('\r\n');
const expect = (i, s) => { if (lines[i] !== s) throw new Error('line ' + (i + 1) + ': ' + lines[i]); };
expect(1, '  "schemaVersion": 3,');
const start = lines.indexOf('  "physics": {'); let end = start;
while (end < lines.length && lines[end] !== '  },') end++;
if (start < 0 || end >= lines.length) throw new Error('physics block');
const block = JSON.stringify({ physics }, null, 2).split('\n').slice(1, -1);
block[block.length - 1] += ',';
const out = [lines[0], '  "schemaVersion": 4,', ...lines.slice(2, start), ...block, ...lines.slice(end + 1)];
const sidecar = Buffer.from(out.join('\r\n'), 'latin1');
const parsed = JSON.parse(sidecar.toString('latin1'));
const keys = (o) => JSON.stringify(Object.keys(o));
if (parsed.schemaVersion !== 4 || keys(parsed) !== keys(doc) || JSON.stringify({ ...parsed, schemaVersion: 3, physics: old }) !== JSON.stringify(doc))
  throw new Error('rewrite changed more than the physics block');
const pipeline = h(Buffer.from(h(payload) + ':' + h(sidecar), 'ascii'));
const registryPath = dir + 'registry.json';
const registry = readFileSync(registryPath, 'latin1');
const current = h(Buffer.from(h(payload) + ':' + h(readFileSync(sidecarPath)), 'ascii'));
if (registry.split(current).length !== 2) throw new Error('registry pin not found once');
console.log(JSON.stringify({ bounds, floor, top, compounds: compounds.length, pairs: pairs.length, sidecarBytes: sidecar.length, sidecarSha256: h(sidecar), pipelineSha256: pipeline }, null, 2));
if (write) {
  writeFileSync(sidecarPath, sidecar);
  writeFileSync(registryPath, Buffer.from(registry.replace(current, pipeline), 'latin1'));
  console.log('written');
}
