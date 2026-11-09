// Re-derives `sense` and `label` of a NeuralRetrainProbe journal from its saved profiles, for a journal written before a
// sense-window change (pilot r2: the overflash upper bound was dropped). Only OVERFLASH is recomputed; the other criteria stand.
// Run: node tools/neural-retrain/relabel.mjs <dir>  -> <dir>/cases-relabelled.jsonl
import fs from 'node:fs';
import path from 'node:path';
const dir = process.argv[2];
const rows = fs.readFileSync(path.join(dir, 'cases.jsonl'), 'utf8').trim().split('\n').map((l) => JSON.parse(l));
let changed = 0;
for (const r of rows) {
  if (!r.strict || !r.seed || !Array.isArray(r.sense)) continue;
  const L = r.seed.liquid, f = r.input.feedComponentMolarFlowsMolPerSecond.reduce((a, b) => a + b, 0);
  const overflash = L[r.input.feedStageNumber - 1].reduce((a, b) => a + b, 0) / f;
  const sense = r.sense.filter((s) => s !== 'OVERFLASH');
  if (overflash < 0.01) sense.push('OVERFLASH');
  r.overflash = overflash;
  if (sense.length !== r.sense.length) changed++;
  r.sense = sense; r.label = sense.length === 0;
}
fs.writeFileSync(path.join(dir, 'cases-relabelled.jsonl'), rows.map((r) => JSON.stringify(r)).join('\n') + '\n');
console.log(JSON.stringify({ rows: rows.length, changed, labels: rows.filter((r) => r.label).length }));
