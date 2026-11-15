// node tools/fluid-step-cost/bench/bench-sixths.mjs <tag> ...: each timed run's mean island step over the six sixths of its measured
// window (by dispatch tick: the workload is the same at the same tick in every run), with the whole-window mean and the
// median of the six. A sixth far above the same sixth of the other runs is a disturbed stretch, not the code.
import fs from 'node:fs';
const n = 6;
for (const t of process.argv.slice(2)) {
  const r = JSON.parse(fs.readFileSync(`tmp/pattern-census/bench-${t}/timed/report.json`, 'utf8'));
  const s = r.samples.map(x => ({ tick: x.timing.dispatchedAtTick, ms: x.timing.workerNanos / 1e6 }));
  const t0 = Math.min(...s.map(x => x.tick)), t1 = Math.max(...s.map(x => x.tick));
  const bins = Array.from({ length: n }, () => []);
  for (const x of s) bins[Math.min(n - 1, Math.floor((x.tick - t0) / (t1 - t0 + 1) * n))].push(x.ms);
  const means = bins.map(b => b.reduce((a, c) => a + c, 0) / b.length);
  const sorted = [...means].sort((a, b) => a - b);
  console.log(`${t.padEnd(5)} sixths ${means.map(m => m.toFixed(1).padStart(5)).join(' ')} | mean ${(s.reduce((a, c) => a + c.ms, 0) / s.length).toFixed(2)} | median of sixths ${((sorted[2] + sorted[3]) / 2).toFixed(2)} | steps ${s.length}`);
}
