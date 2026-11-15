// node tools/fluid-step-cost/bench/bench-compare.mjs <tag> <tag> ...: step time, CPU, collections, heap and counter differences of bench pairs.
import fs from 'node:fs';
const tags = process.argv.slice(2);
const read = (t, f) => JSON.parse(fs.readFileSync(`tmp/pattern-census/bench-${t}/${f}/report.json`, 'utf8'));
const sum = a => a.reduce((x, y) => x + y, 0);
const q = (a, p) => { const b = [...a].sort((x, y) => x - y); return b[Math.floor(p * (b.length - 1))]; };
let first = null;
for (const t of tags) {
  const r = read(t, 'timed'), d = read(t, 'diag');
  const w = r.samples.map(x => x.timing.workerNanos / 1e6), c = r.samples.map(x => x.timing.workerCpuNanos / 1e6);
  const m = r.memorySamples.filter(s => s.measured);
  const heap = m.map(s => s.heapAfterLastGc / 1e9);
  console.log(`${t}: steps ${w.length} mean ${(sum(w) / w.length).toFixed(2)} p50 ${q(w, .5).toFixed(2)} p95 ${q(w, .95).toFixed(2)} max ${Math.max(...w).toFixed(1)} ms | cpu mean ${(sum(c) / c.length).toFixed(2)} ms | occupancy ${(sum(w) / 1000 / r.measuredSeconds / r.workers).toFixed(3)}`
    + ` | gc ${m[m.length - 1].gcCount - m[0].gcCount} in ${m[m.length - 1].gcMillis - m[0].gcMillis} ms | heap after gc ${Math.min(...heap).toFixed(2)}-${Math.max(...heap).toFixed(2)} GB | held ${r.heldIntervals} | from ${r.measuredStartEpochMillis}`);
  const k = d.solverDiagnostics?.counters || {};
  if (!first) { first = k; continue; }
  const moved = Object.keys({ ...first, ...k }).filter(n => !/Nanos$/.test(n) && first[n] !== k[n]);
  console.log(`   counters that differ from ${tags[0]} (times left out): ${moved.length ? moved.map(n => `${n} ${first[n]} -> ${k[n]}`).join('; ') : 'none'}`);
  console.log(`   luFactor ms ${(first.luFactorNanos / 1e6).toFixed(0)} -> ${(k.luFactorNanos / 1e6).toFixed(0)}, luSolve ms ${(first.luSolveNanos / 1e6).toFixed(0)} -> ${(k.luSolveNanos / 1e6).toFixed(0)}`);
}
