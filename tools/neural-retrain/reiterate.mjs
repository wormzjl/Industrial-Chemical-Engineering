// N3b round design (batch 2026-11-05-neural-retrain, NEURAL_RETRAIN_DATA.md section 8): the inputs round <n> solves LNN_FIRST.
//  - every TRAIN input that classical did not solve in the given label journals: not ACCEPTED, not EXCLUDED_BY_DESIGN, not
//    INVALID_INPUT (stalls, class D, depleted draws, poll caps; accepted rows that failed only the strict or sense rules are
//    solved, so a second start would land on the same state);
//  - fresh requests in the thinnest cells: a candidate pool from design.mjs on another seed, drawn without replacement with a
//    weight inversely proportional to its cells' label counts in the journals (CDU: composition, injection, dominant grade,
//    stages, feed T, top P, turndown; VDU: stages, feed T, top P). Half the fresh rows are CDU, half VDU (VDU is the thinner unit).
// Run: node tools/neural-retrain/reiterate.mjs <round> <out-dir> <fresh-count> <seed> <journal>...
//   -> <out-dir>/design.jsonl (failed + fresh rows, ids prefixed r<round>- for the fresh) and design.json (the counts).
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

const [round, out, freshText, seedText, ...journals] = process.argv.slice(2);
if (!journals.length) throw new Error('usage: reiterate.mjs <round> <out-dir> <fresh-count> <seed> <journal>...');
const fresh = Number(freshText), seed = Number(seedText);
const read = (f) => fs.readFileSync(f, 'utf8').split('\n').filter((l) => l.trim()).map((l) => JSON.parse(l));
const rows = journals.flatMap(read);
const label = (r) => r.label ?? r.strict ?? false;
const unsolved = (r) => !label(r) && r.split === 'train' && !['ACCEPTED', 'EXCLUDED_BY_DESIGN', 'INVALID_INPUT'].includes(r.status);
// A round re-solves an earlier journal's input under the same id: an id labelled in any journal is solved, and an input that
// failed in several journals enters the next round once (its latest row).
const solvedIds = new Set(rows.filter(label).map((r) => r.id));
const latest = new Map(); for (const r of rows) latest.set(r.id, r);
const failed = [...latest.values()].filter((r) => !solvedIds.has(r.id) && unsolved(r)).map((r) => r.design);

const GRADES = ['light', 'medium', 'heavy', 'dilbit'];
const vdu = (d) => d.unit === 'vdu';
const cells = { // tally.mjs's factors
  composition: (d) => d.composition.kind,
  injected: (d) => (d.composition.injection ? 'injected' : 'axis only'),
  dominantGrade: (d) => (!d.composition.weights ? '-' : GRADES[d.composition.weights.indexOf(Math.max(...d.composition.weights))]),
  stages: (d) => d.operating.stages,
  feedT: (d) => `${Math.floor(d.operating.feedTemperatureKelvin / 15) * 15} K`,
  topP: (d) => (vdu(d) ? `${Math.floor(d.operating.topPressurePascal / 2e3) * 2} kPa` : `${Math.floor(d.operating.topPressurePascal / 50e3) * 50} kPa`),
  turndown: (d) => (d.operating.feedRateFactor === undefined ? '-' : d.operating.feedRateFactor < 0.75 ? '<0.75' : '>=0.75'),
};
const factorsOf = (d) => (vdu(d) ? ['stages', 'feedT', 'topP'] : Object.keys(cells));
const counts = {}; // unit -> factor -> level -> labels
for (const r of rows.filter(label)) {
  const u = vdu(r.design) ? 'vdu' : 'cdu';
  for (const f of factorsOf(r.design)) { const t = ((counts[u] ??= {})[f] ??= {}); const k = cells[f](r.design); t[k] = (t[k] ?? 0) + 1; }
}
const weight = (d) => {
  const u = vdu(d) ? 'vdu' : 'cdu';
  return factorsOf(d).reduce((w, f) => {
    const t = counts[u]?.[f] ?? {}; const levels = Object.values(t); const mean = levels.length ? levels.reduce((a, b) => a + b, 0) / levels.length : 1;
    return w * mean / Math.max(1, t[cells[f](d)] ?? 0);
  }, 1);
};

// The candidate pool: design.mjs on this round's seed (6,000 CDU and 1,500 VDU rows), never the N2 seed.
const pool = path.join(out, 'pool');
fs.mkdirSync(out, { recursive: true });
execFileSync(process.execPath, [path.join(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), 'design.mjs'), pool, String(seed)], { stdio: 'ignore' });
let state = seed >>> 0;
const rand = () => { state = (state + 0x6D2B79F5) >>> 0; let t = Math.imul(state ^ (state >>> 15), 1 | state); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
function draw(candidates, count) { // weighted sampling without replacement (Efraimidis-Spirakis keys)
  return candidates.map((d) => ({ d, key: Math.log(rand() || 1e-300) / weight(d) })).sort((a, b) => b.key - a.key).slice(0, count).map((e) => e.d);
}
const cdu = draw(read(path.join(pool, 'train.jsonl')), Math.ceil(fresh / 2)).map((d, i) => ({ ...d, id: `r${round}-train-${String(i).padStart(5, '0')}` }));
const vduRows = draw(read(path.join(pool, 'vdu-train.jsonl')), Math.floor(fresh / 2)).map((d, i) => ({ ...d, id: `r${round}-vdu-train-${String(i).padStart(5, '0')}` }));
fs.rmSync(pool, { recursive: true, force: true });

const design = [...failed, ...cdu, ...vduRows];
fs.writeFileSync(path.join(out, 'design.jsonl'), design.map((d) => JSON.stringify(d)).join('\n') + '\n');
const tally = (list, f) => list.reduce((m, d) => ((m[f(d)] = (m[f(d)] ?? 0) + 1), m), {});
const summary = { round: Number(round), seed, journals, failed: { cdu: failed.filter((d) => !vdu(d)).length, vdu: failed.filter(vdu).length },
  fresh: { cdu: cdu.length, vdu: vduRows.length, cduStages: tally(cdu, cells.stages), cduTopP: tally(cdu, cells.topP), cduFeedT: tally(cdu, cells.feedT),
    cduComposition: tally(cdu, cells.composition), vduStages: tally(vduRows, cells.stages), vduTopP: tally(vduRows, cells.topP) },
  labelCounts: counts };
fs.writeFileSync(path.join(out, 'design.json'), JSON.stringify(summary, null, 1));
console.log(JSON.stringify({ rows: design.length, failed: summary.failed, fresh: { cdu: cdu.length, vdu: vduRows.length } }));
