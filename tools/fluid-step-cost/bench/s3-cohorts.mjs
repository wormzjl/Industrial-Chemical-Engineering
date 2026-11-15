// node tools/fluid-step-cost/bench/s3-cohorts.mjs <tag> ...: each run's steps by simulated start tick (a cohort: every awake
// island's interval from that tick), so that runs that lag differently are compared on the same simulated work:
// steps in the window, mean wall and CPU ms a step. Then every pair's ratio over the cohorts both hold at least 90 % of.
import fs from 'node:fs';
const runs = {};
for (const t of process.argv.slice(2)) {
  const r = JSON.parse(fs.readFileSync(`tmp/pattern-census/bench-${t}/timed/report.json`, 'utf8'));
  const by = new Map();
  for (const x of r.samples) {
    const k = x.timing.startTick, e = by.get(k) || { n: 0, w: 0, c: 0, sub: 0 };
    e.n++; e.w += x.timing.workerNanos / 1e6; e.c += Math.max(0, x.timing.workerCpuNanos) / 1e6; e.sub += x.substeps; by.set(k, e);
  }
  const awake = r.stressSamples[0].debtSeconds.count;
  runs[t] = { by, awake, forced: null };
  const find = (o, path) => { if (o && typeof o === 'object' && !Array.isArray(o)) for (const [k, v] of Object.entries(o)) { if (/forced/i.test(k)) console.log(`${t} ${path}${k}: ${JSON.stringify(v).slice(0, 200)}`); else if (k !== 'manifest') find(v, path + k + '.'); } };
  find(r, '');
}
const tags = Object.keys(runs);
const ticks = [...new Set(tags.flatMap(t => [...runs[t].by.keys()]))].sort((a, b) => a - b);
console.log('tick   ' + tags.map(t => t.padEnd(26)).join(''));
for (const k of ticks) console.log(String(k).padEnd(7) + tags.map(t => { const e = runs[t].by.get(k); return (e ? `${String(e.n).padStart(4)} ${(e.w / e.n).toFixed(1).padStart(5)} ${(e.c / e.n).toFixed(1).padStart(5)} sub ${(e.sub / e.n).toFixed(2)}` : '').padEnd(26); }).join(''));
for (let i = 0; i < tags.length; i++) for (let j = i + 1; j < tags.length; j++) {
  const a = runs[tags[i]], b = runs[tags[j]];
  const common = ticks.filter(k => (a.by.get(k)?.n ?? 0) >= .9 * a.awake && (b.by.get(k)?.n ?? 0) >= .9 * b.awake);
  if (!common.length) { console.log(`${tags[i]} vs ${tags[j]}: no common cohort`); continue; }
  const sum = (r, f) => common.reduce((s, k) => s + r.by.get(k)[f], 0);
  const wa = sum(a, 'w') / sum(a, 'n'), wb = sum(b, 'w') / sum(b, 'n'), ca = sum(a, 'c') / sum(a, 'n'), cb = sum(b, 'c') / sum(b, 'n');
  console.log(`${tags[i]} vs ${tags[j]}: cohorts ${common[0]}..${common.at(-1)} (${common.length}) wall ${wa.toFixed(2)} vs ${wb.toFixed(2)} (x${(wb / wa).toFixed(3)}), cpu ${ca.toFixed(2)} vs ${cb.toFixed(2)} (x${(cb / ca).toFixed(3)})`);
}
