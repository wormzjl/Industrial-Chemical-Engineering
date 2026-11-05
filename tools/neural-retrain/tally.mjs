// Yield table of a NeuralRetrainProbe labels journal (batch 2026-11-05-neural-retrain, N1 pilot; guide sections 5 and 6.3).
// Run: node tools/neural-retrain/tally.mjs tmp/neural-retrain/pilot  -> prints the table and writes yield.json there.
import fs from 'node:fs';
import path from 'node:path';

const dir = process.argv[2] ?? 'tmp/neural-retrain/pilot';
const rows = fs.readFileSync(path.join(dir, 'cases.jsonl'), 'utf8').trim().split('\n').map((l) => JSON.parse(l));
const run = JSON.parse(fs.readFileSync(path.join(dir, 'run.json'), 'utf8'));
const GRADES = ['light', 'medium', 'heavy', 'dilbit'];

// Failure classes of guide 6.3, plus the liquid-depleted side draw (6.1) and the probe's own cap.
function klass(r) {
  if (r.status === 'EXCLUDED_BY_DESIGN') return r.exclusion === 'DEGENERATE' ? 'A' : r.exclusion.startsWith('REQUEST_ONLY') ? 'C' : 'SIGMA';
  if (r.strict) return 'label';
  if (r.status === 'ACCEPTED') return 'notStrict:' + r.strictFailure;
  const text = `${r.failure ?? ''} ${(r.events ?? []).join(' ')}`;
  if (/condensation-capped|not below the base condenser duty/.test(text)) return 'B';
  if (r.status === 'INFEASIBLE_SPECIFICATION' && (r.newtonIterations ?? 0) === 0) return 'C';
  if (/SIDE_DRAW_SPLIT|withdrawal/.test(text)) return 'depletedDraw';
  if (r.status === 'POLL_CAP') return 'D:pollCap';
  return 'D';
}
const factor = {
  all: () => 'all',
  composition: (r) => r.design.composition.kind,
  dominantGrade: (r) => GRADES[r.design.composition.weights.indexOf(Math.max(...r.design.composition.weights))],
  stages: (r) => r.design.operating.stages,
  feedT: (r) => `${Math.floor(r.design.operating.feedTemperatureKelvin / 15) * 15} K`,
  topP: (r) => `${Math.floor(r.design.operating.topPressurePascal / 50e3) * 50} kPa`,
  turndown: (r) => (r.design.operating.feedRateFactor < 0.75 ? '<0.75' : '>=0.75'),
  drawsDropped: (r) => (r.design.operating.dropDraw >= 0 ? 'one dropped' : 'three'),
  pumparoundsDropped: (r) => (r.design.operating.dropPumparound >= 0 ? 'one dropped' : 'three'),
  zeroC1C2: (r) => (r.design.composition.zero.length ? 'zeroed' : 'kept'),
};
const out = { run, factors: {}, classes: {} };
for (const r of rows) { const k = klass(r); out.classes[k] = (out.classes[k] ?? 0) + 1; }
for (const [name, f] of Object.entries(factor)) {
  const t = {};
  for (const r of rows) {
    const key = f(r); const e = (t[key] ??= { requests: 0, screened: 0, solved: 0, strict: 0 });
    e.requests++; if (r.status === 'EXCLUDED_BY_DESIGN') e.screened++; else e.solved++; if (r.strict) e.strict++;
  }
  for (const e of Object.values(t)) e.strictPerSolved = e.solved ? +(e.strict / e.solved).toFixed(3) : null;
  out.factors[name] = t;
}
const solved = rows.filter((r) => r.controlPolls !== undefined);
const strictRows = rows.filter((r) => r.strict);
const sum = (l, f) => l.reduce((a, r) => a + f(r), 0);
const polls = sum(solved, (r) => r.controlPolls), wall = sum(solved, (r) => r.wallMsInformative);
const sorted = (l) => l.slice().sort((a, b) => a - b), q = (l, p) => l[Math.min(l.length - 1, Math.floor(p * l.length))];
const sp = sorted(strictRows.map((r) => r.controlPolls));
out.cost = {
  solvedRows: solved.length, strictLabels: strictRows.length,
  pollsTotal: polls, pollsPerSolvedRow: Math.round(polls / solved.length), pollsPerStrictLabel: Math.round(polls / strictRows.length),
  strictLabelPolls: { median: q(sp, 0.5), p90: q(sp, 0.9), max: sp.at(-1) },
  wallSecondsPerSolvedRowInformative: +(wall / solved.length / 1e3).toFixed(2),
  wallSecondsPerStrictLabelInformative: +(wall / strictRows.length / 1e3).toFixed(2),
  elapsedSecondsPerStrictLabelAt4Workers: +(run.elapsedSecondsInformative / strictRows.length).toFixed(2),
};
fs.writeFileSync(path.join(dir, 'yield.json'), JSON.stringify(out, null, 1));
console.log(JSON.stringify(out.classes));
for (const [name, t] of Object.entries(out.factors))
  console.log(name.padEnd(18), Object.entries(t).sort().map(([k, e]) => `${k}: ${e.strict}/${e.solved} (${e.screened} screened)`).join(' | '));
console.log(JSON.stringify(out.cost));
