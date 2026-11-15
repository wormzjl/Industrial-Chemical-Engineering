// node tools/fluid-step-cost/bench/s3-jfr.mjs <tag> ...: from each timed run's JFR, inside the measured minute: the JVM's and the
// machine's CPU load (user, system), the solver workers' user and system load, the GC pauses and the young generation.
// Uses the JDK's own `jfr print --json`.
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
const jfr = 'C:/Program Files/Java/jdk-21.0.11/bin/jfr.exe';
const mean = a => a.length ? a.reduce((x, y) => x + y, 0) / a.length : NaN;
for (const t of process.argv.slice(2)) {
  const dir = `tmp/pattern-census/bench-${t}/timed`;
  const r = JSON.parse(fs.readFileSync(`${dir}/report.json`, 'utf8'));
  const from = r.measuredStartEpochMillis, to = from + r.measuredSeconds * 1000;
  const read = events => JSON.parse(execFileSync(jfr, ['print', '--json', '--events', events, `${dir}/stress.jfr`], { maxBuffer: 1 << 30 }).toString()).recording.events
    .filter(e => { const at = Date.parse(e.values.startTime); return at >= from && at <= to; });
  const load = read('jdk.CPULoad').map(e => e.values);
  const threads = read('jdk.ThreadCPULoad').map(e => e.values);
  const names = {};
  for (const v of threads) { const n = (v.eventThread?.javaName || v.eventThread?.osName || '?').replace(/[-#\s]*\d+$/, ''); (names[n] ||= []).push(v); }
  const gcs = read('jdk.GarbageCollection').map(e => e.values);
  const dur = d => { const m = /PT(?:(\d+)M)?([\d.]+)S/.exec(d); return m ? (+(m[1] || 0) * 60 + +m[2]) * 1000 : NaN; };
  console.log(`== ${t}: JVM user ${mean(load.map(v => v.jvmUser)).toFixed(4)} system ${mean(load.map(v => v.jvmSystem)).toFixed(4)} of the machine's 16 threads; machine total ${mean(load.map(v => v.machineTotal)).toFixed(4)} (${load.length} samples)`);
  const cores = Object.entries(names).map(([n, vs]) => [n, vs.reduce((s, v) => s + v.user, 0) / load.length * 16, vs.reduce((s, v) => s + v.system, 0) / load.length * 16, new Set(vs.map(v => v.eventThread?.javaThreadId)).size])
    .sort((a, b) => b[1] + b[2] - a[1] - a[2]).slice(0, 7);
  for (const [n, u, s, k] of cores) console.log(`   ${n.padEnd(34)} threads ${String(k).padStart(2)} user ${u.toFixed(3)} system ${s.toFixed(3)} cores (system share ${(s / (u + s)).toFixed(3)})`);
  const byName = {};
  for (const g of gcs) { const e = (byName[g.name] ||= { n: 0, pause: 0, longest: 0 }); e.n++; e.pause += dur(g.sumOfPauses); e.longest = Math.max(e.longest, dur(g.longestPause)); }
  console.log(`   GC: ${Object.entries(byName).map(([n, e]) => `${n} ${e.n} collections, pauses ${e.pause.toFixed(0)} ms, longest ${e.longest.toFixed(1)} ms`).join('; ')}`);
}
