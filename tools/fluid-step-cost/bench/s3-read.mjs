// node tools/fluid-step-cost/bench/s3-read.mjs <tag> ...: S3's readout of each timed run: the load on the workers (steps done
// against steps due, occupancy, debt), the step's wall and CPU time, and the heap. A step is one island interval.
import fs from 'node:fs';
const mean = a => a.length ? a.reduce((x, y) => x + y, 0) / a.length : NaN;
const pct = (a, p) => { const s = [...a].sort((x, y) => x - y); return s.length ? s[Math.min(s.length - 1, Math.floor(p * s.length))] : NaN; };
const runs = {};
for (const t of process.argv.slice(2)) {
  const r = JSON.parse(fs.readFileSync(`tmp/pattern-census/bench-${t}/timed/report.json`, 'utf8'));
  const s = r.samples, st = r.stressSamples;
  const wall = s.map(x => x.timing.workerNanos / 1e6), cpu = s.filter(x => x.timing.workerCpuNanos >= 0).map(x => x.timing.workerCpuNanos / 1e6);
  const seconds = r.measuredSeconds;
  const awake = st.length ? st[0].debtSeconds.count : NaN;
  const due = awake * seconds / r.cadenceSeconds;
  const first = st[0], last = st[st.length - 1];
  const mem = r.memorySamples || [];
  const measured = mem.filter(m => m.measured);
  const gcMs = measured.length ? measured[measured.length - 1].gcMillis - measured[0].gcMillis : NaN;
  const gcN = measured.length ? measured[measured.length - 1].gcCount - measured[0].gcCount : NaN;
  runs[t] = { r, s };
  console.log(`== ${t}: ${r.profile}, workers ${r.workers} (configured ${r.manifest.configuredWorkers}), islands ${r.islands}, awake ${awake}, status ${r.status}, held ${r.heldIntervals}, window ${seconds.toFixed(1)} s`);
  console.log(`   steps done ${s.length} of ${due.toFixed(0)} due (${(s.length / due).toFixed(3)}); a second ${(s.length / seconds).toFixed(1)}; realtime ratio ${r.aggregateRealtimeRatio?.toFixed?.(3)}`);
  console.log(`   step wall ms mean ${mean(wall).toFixed(2)} median ${pct(wall, .5).toFixed(2)} p95 ${pct(wall, .95).toFixed(2)} | cpu ms mean ${mean(cpu).toFixed(2)} | wall sum ${(wall.reduce((a, b) => a + b, 0) / 1000).toFixed(1)} s, cpu sum ${(cpu.reduce((a, b) => a + b, 0) / 1000).toFixed(1)} s`);
  console.log(`   occupancy (cpu / workers / window) ${r.meanWorkerCpuOccupancy.toFixed(3)}; wall-busy share ${(wall.reduce((a, b) => a + b, 0) / 1000 / seconds / r.workers).toFixed(3)}; active workers mean ${r.meanReportedActiveWorkers.toFixed(2)} max ${Math.max(...st.map(x => x.activeWorkers))} limit ${Math.max(...st.map(x => x.workerLimit))}`);
  console.log(`   debt s median first ${first.debtSeconds.median} last ${last.debtSeconds.median}, max last ${last.debtSeconds.max}; eligible islands first ${first.eligibleIslands} last ${last.eligibleIslands}; final debt ${JSON.stringify(r.finalDebtSeconds)}`);
  const eng = r.engineServerMillisecondsPerTick, tick = r.wholeTickMilliseconds, lat = r.readyToPublicationMilliseconds;
  console.log(`   engine ms a tick ${JSON.stringify(eng)}`);
  console.log(`   whole tick ms ${JSON.stringify(tick)}`);
  console.log(`   ready to publication ms ${JSON.stringify(lat)}`);
  console.log(`   heap: used last ${(mem.at(-1)?.heapUsed / 2 ** 30).toFixed(2)} GiB, after last GC ${(mem.at(-1)?.heapAfterLastGc / 2 ** 30).toFixed(2)} GiB, max ${(mem.at(-1)?.heapMax / 2 ** 30).toFixed(1)} GiB; GC in window ${gcN} collections ${gcMs} ms`);
  for (const k of Object.keys(r)) if (/forcedGc|afterForced/i.test(k)) console.log(`   ${k}: ${JSON.stringify(r[k])}`);
  // by simulated start tick: the same island interval costs the same in every run, whatever the lag
  const byTick = new Map();
  for (const x of s) { const k = x.timing.startTick; const e = byTick.get(k) || [0, 0, 0]; e[0]++; e[1] += x.timing.workerNanos / 1e6; e[2] += Math.max(0, x.timing.workerCpuNanos) / 1e6; byTick.set(k, e); }
  runs[t].byTick = byTick;
  const ticks = [...byTick.keys()].sort((a, b) => a - b);
  console.log(`   simulated start ticks ${ticks[0]} .. ${ticks.at(-1)} (${ticks.length} cohorts)`);
}
const tags = Object.keys(runs);
if (tags.length > 1) {
  // cohorts every run has in full (the count of the fullest cohort of that run)
  const full = t => Math.max(...[...runs[t].byTick.values()].map(e => e[0]));
  const common = [...runs[tags[0]].byTick.keys()].filter(k => tags.every(t => runs[t].byTick.get(k)?.[0] === full(t))).sort((a, b) => a - b);
  console.log(`== cohorts complete in every run: ${common.length} (${common[0]} .. ${common.at(-1)})`);
  for (const t of tags) {
    let n = 0, w = 0, c = 0; for (const k of common) { const e = runs[t].byTick.get(k); n += e[0]; w += e[1]; c += e[2]; }
    console.log(`   ${t.padEnd(8)} steps ${n} wall ms a step ${(w / n).toFixed(2)} cpu ms a step ${(c / n).toFixed(2)}`);
  }
}
