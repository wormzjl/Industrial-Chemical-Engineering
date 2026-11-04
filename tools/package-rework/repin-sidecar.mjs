// W1 bridge: re-pin the regrouped model's sidecar propertyRevision to the reworked tjl20_methane identity, then the registry's
// pipelineSha256 (sha256(hex(sha256(payload)) + ":" + hex(sha256(sidecar)))). Bytes are -text: only these values change.
import { readFileSync, writeFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
const dir = 'src/main/resources/data/icheme/neural/';
const h = (b) => createHash('sha256').update(b).digest('hex');
const identity = JSON.parse(readFileSync('tmp/package-rework/after.json', 'utf8'));
const sidecarPath = dir + 'v3-column-transformer-regrouped.sidecar.json';
let sidecar = readFileSync(sidecarPath, 'latin1');
const OLD = '"propertyRevision": "icheme:tjl20_methane@crude-regrouped-r1;properties=';
const NEW = '"propertyRevision": "icheme:tjl20_methane@crude-regrouped-r2;method=icheme:crude_pr78@crude-pr78-r1;properties=';
if (sidecar.split(OLD).length !== 2) throw new Error('sidecar line not found once');
sidecar = sidecar.replace(OLD, NEW);
const line = sidecar.split('\n').find((l) => l.includes('"propertyRevision"'));
const pinned = JSON.parse('{' + line.trim().replace(/,$/, '') + '}').propertyRevision;
// The probe's after.json records each package's scientificRevision; the new pin must equal tjl20_methane's.
const find = (o) => { if (o && typeof o === 'object') { if (o.scientificRevision && String(o.scientificRevision).startsWith('icheme:tjl20_methane@')) return o.scientificRevision;
  for (const v of Object.values(o)) { const r = find(v); if (r) return r; } } return null; };
const expected = find(identity);
if (pinned !== expected) throw new Error('pin differs from the probe identity:\n' + pinned + '\n' + expected);
writeFileSync(sidecarPath, Buffer.from(sidecar, 'latin1'));
const payload = readFileSync(dir + 'v3-column-transformer-regrouped.payload.json');
const pipeline = h(Buffer.from(h(payload) + ':' + h(readFileSync(sidecarPath)), 'ascii'));
const registryPath = dir + 'registry.json';
const registry = readFileSync(registryPath, 'latin1');
const old = 'd4d790127a373fd1444e10cc419458978a30c118415b48212410915f114e3696';
if (registry.split(old).length !== 2) throw new Error('registry pin not found once');
writeFileSync(registryPath, Buffer.from(registry.replace(old, pipeline), 'latin1'));
console.log('pipelineSha256', pipeline);
