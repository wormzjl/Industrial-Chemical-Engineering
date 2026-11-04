// W2: the regrouped model's sidecar to schema 3. packageId, propertyRevision and the two zero flags give way to the
// trained physics, read from the W1 bridge identity (the records the model was trained on); then the registry's
// pipelineSha256 (sha256(hex(sha256(payload)) + ":" + hex(sha256(sidecar)))). The files are -text, CRLF: only these lines change.
import { readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
const dir = 'src/main/resources/data/icheme/neural/';
const h = (b) => createHash('sha256').update(b).digest('hex');
const sidecarPath = dir + 'v3-column-transformer-regrouped.sidecar.json';
const text = readFileSync(sidecarPath, 'latin1');
if (!text.includes('\r\n')) throw new Error('expected CRLF');
const doc = JSON.parse(text);
const payload = readFileSync(dir + 'v3-column-transformer-regrouped.payload.json');
const components = JSON.parse(payload.toString('utf8')).components;
const tokens = Object.fromEntries(doc.propertyRevision.split(';').map((t) => { const i = t.indexOf('='); return i < 0 ? ['package', t] : [t.slice(0, i), t.slice(i + 1)]; }));
if (tokens.package !== 'icheme:tjl20_methane@crude-regrouped-r2' || tokens.method !== 'icheme:crude_pr78@crude-pr78-r1') throw new Error('unexpected identity ' + doc.propertyRevision);
const datasets = tokens.properties.split(',');
if (datasets.length !== components.length || components.length !== 19) throw new Error('dataset count');
const physics = { model: 'pr78', compounds: components.map((id, i) => ({ id, properties: datasets[i] })),
  interactions: tokens.interactions.split(',').filter((s) => s), missingInteractions: 'zero', water: tokens.water };
const lines = text.split('\r\n');
const expect = (i, s) => { if (lines[i] !== s) throw new Error('line ' + (i + 1) + ': ' + lines[i]); };
expect(1, '  "schemaVersion": 2,');
if (!lines[4].startsWith('  "packageId": ') || !lines[5].startsWith('  "propertyRevision": ')) throw new Error('identity lines');
const n = lines.length;
expect(n - 5, '  "correctionRule": "PROGRESS",');
expect(n - 4, '  "allowExtraZeroComponents": false,');
expect(n - 3, '  "allowMissingZeroComponents": false');
expect(n - 2, '}');
const block = JSON.stringify({ physics }, null, 2).split('\n').slice(1, -1);
block[block.length - 1] += ',';
const out = [lines[0], '  "schemaVersion": 3,', lines[2], lines[3], ...block, ...lines.slice(6, n - 5), '  "correctionRule": "PROGRESS"', '}', ''];
const sidecar = Buffer.from(out.join('\r\n'), 'latin1');
const parsed = JSON.parse(sidecar.toString('latin1'));
if (parsed.packageId || parsed.propertyRevision || 'allowExtraZeroComponents' in parsed || parsed.schemaVersion !== 3) throw new Error('rewrite');
writeFileSync(sidecarPath, sidecar);
const pipeline = h(Buffer.from(h(payload) + ':' + h(sidecar), 'ascii'));
const registryPath = dir + 'registry.json';
const registry = readFileSync(registryPath, 'latin1');
const old = 'abf6d8bb503ef8ad6ed52dad6dfaf6ea7eb7ea8ccb8d9c8d150b771a80a7e7d0';
if (registry.split(old).length !== 2) throw new Error('registry pin not found once');
writeFileSync(registryPath, Buffer.from(registry.replace(old, pipeline), 'latin1'));
console.log('sidecar', h(sidecar), 'pipelineSha256', pipeline);
