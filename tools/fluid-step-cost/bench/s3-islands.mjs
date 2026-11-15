// node tools/fluid-step-cost/bench/s3-islands.mjs <reference tag> <tag> [from to]: the same islands in two runs. The fixture's
// first 100 ladders are mixed100's, so the reference's islands, by rank of id, are the other run's first 100. Compares the
// step's wall and CPU time island by island over the simulated start ticks both runs hold (optionally from..to), and
// gives the other run's remaining islands apart. Warm-up samples are read too when asked with a third flag "warm".
import fs from 'node:fs';
const [ta, tb, from = '0', to = '1e9', warm = ''] = process.argv.slice(2);
const load = t => {
  const r = JSON.parse(fs.readFileSync(`tmp/pattern-census/bench-${t}/timed/report.json`, 'utf8'));
  const all = warm === 'warm' ? [...(r.warmupSamples || []), ...r.samples] : r.samples;
  const ids = [...new Set(all.map(x => x.island))].sort((a, b) => a - b);
  // rank among the islands that stepped (the closed ladders rest): ids are handed out in ladder order
  const every = ids;
  const rank = new Map(ids.map((id, i) => [id, i]));
  const by = new Map();
  for (const x of all) { const k = rank.get(x.island) + ':' + x.timing.startTick; by.set(k, { w: x.timing.workerNanos / 1e6, c: Math.max(0, x.timing.workerCpuNanos) / 1e6, rank: rank.get(x.island), tick: x.timing.startTick }); }
  return { r, by, ladders: r.ladders, every };
};
const a = load(ta), b = load(tb);
console.log(`${ta}: islands that stepped ${a.every.length}; ${tb}: ${b.every.length}`);
let n = 0, wa = 0, wb = 0, ca = 0, cb = 0; const ratios = [];
for (const [k, ea] of a.by) { const eb = b.by.get(k); if (!eb || ea.tick < +from || ea.tick > +to) continue; n++; wa += ea.w; wb += eb.w; ca += ea.c; cb += eb.c; ratios.push(eb.w / ea.w); }
ratios.sort((x, y) => x - y);
console.log(`same island and interval in both, ticks ${from}..${to}: ${n} steps | wall ms ${ta} ${(wa / n).toFixed(2)} ${tb} ${(wb / n).toFixed(2)} (x${(wb / wa).toFixed(3)}) | cpu ${(ca / n).toFixed(2)} ${(cb / n).toFixed(2)} (x${(cb / ca).toFixed(3)}) | ratio median ${ratios[ratios.length >> 1]?.toFixed(3)} p10 ${ratios[Math.floor(ratios.length * .1)]?.toFixed(3)} p90 ${ratios[Math.floor(ratios.length * .9)]?.toFixed(3)}`);
const lim = a.every.length; let m = 0, w = 0, m2 = 0, w2 = 0;
for (const e of b.by.values()) { if (e.tick < +from || e.tick > +to) continue; if (e.rank < lim) { m++; w += e.w; } else { m2++; w2 += e.w; } }
console.log(`${tb}, ticks ${from}..${to}: its first ${lim} islands ${m} steps ${(w / m).toFixed(2)} ms; the others ${m2} steps ${(w2 / m2).toFixed(2)} ms`);
