// Compares the quick check's runs with a baseline campaign on the same seeds (batch 2026-11-03-rotating-machinery).
// Usage: node tools/rotating-machinery/compare.js <baseline dir> <last seed> <run dir>...
//   The baseline dir holds the merge lane's campaign-<dist>-report.txt, a run dir quick-check.sh's
//   hold-fuzz-<dist>-report.txt, each with classify.sh's .classes.txt beside it. Seeds above <last seed> are ignored.
// Prints, per distribution: clean seeds, the held seeds by class, seeds held only in the run or only in the baseline,
// refused registrations; then each run's solver counters (campaign-wide, so only runs of one seed range compare).
const fs = require('fs'), path = require('path');
const [baseDir, lastArg, ...runDirs] = process.argv.slice(2);
if (!baseDir || !lastArg || runDirs.length === 0) { console.error('usage: compare.js <baseline dir> <last seed> <run dir>...'); process.exit(2); }
const last = Number(lastArg);
const DISTS = ['cold-random', 'bundled-random', 'bundled-defaults'];
const COUNTERS = ['implicitSolves', 'activeSetPasses', 'newtonSolves', 'newtonIterations', 'jacobianBuilds', 'stepAttempts', 'stepAttemptsAccepted', 'newtonBacktracks', 'restIntervals'];

function read(file) {
  if (!fs.existsSync(file)) return null;
  const text = fs.readFileSync(file, 'utf8');
  const seeds = new Map();
  for (const m of text.matchAll(/^SEED (\d+) .*?holds=(\d+) .*?refused=(\d+)/gm)) {
    const seed = Number(m[1]);
    if (seed <= last) seeds.set(seed, { held: Number(m[2]) > 0, refused: Number(m[3]) });
  }
  const classes = new Map();
  const classFile = file + '.classes.txt';
  if (fs.existsSync(classFile)) for (const line of fs.readFileSync(classFile, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^(\S+)\s.*list=(.*)$/);
    if (!m) continue;
    for (const s of m[2].trim().split(/\s+/).map(Number)) if (s <= last) classes.set(s, m[1]);
  }
  const counters = {};
  const c = text.match(/^COUNTERS (.*)$/m);
  if (c) for (const kv of c[1].split(' ')) { const [k, v] = kv.split('='); counters[k] = v; }
  const summary = (text.match(/^SUMMARY (.*)$/m) || [, ''])[1];
  return { seeds, classes, counters, summary };
}
const held = r => [...r.seeds].filter(([, s]) => s.held).map(([seed]) => seed).sort((a, b) => a - b);
const byClass = r => held(r).map(s => `${r.classes.get(s) || '?'}:${s}`).join(' ') || '-';

const runs = runDirs.map(d => ({ name: path.basename(d), dir: d }));
for (const dist of DISTS) {
  const base = read(path.join(baseDir, `campaign-${dist}-report.txt`));
  console.log(`== ${dist} (seeds 1-${last})`);
  const baseHeld = base ? new Set(held(base)) : new Set();
  if (base) console.log(`  base  clean=${base.seeds.size - baseHeld.size}/${base.seeds.size}  held ${byClass(base)}`);
  for (const run of runs) {
    const r = read(path.join(run.dir, `hold-fuzz-${dist}-report.txt`));
    if (!r) { console.log(`  ${run.name}  (no report)`); continue; }
    const h = held(r), refused = [...r.seeds.values()].reduce((a, s) => a + s.refused, 0);
    const added = h.filter(s => !baseHeld.has(s)), gone = [...baseHeld].filter(s => !h.includes(s));
    const ms = (r.summary.match(/seedMsSum=(\d+)/) || [, '?'])[1];
    console.log(`  ${run.name}  clean=${r.seeds.size - h.length}/${r.seeds.size}  held ${byClass(r)}  new [${added.join(' ')}] gone [${gone.join(' ')}] refused=${refused} seedMs=${ms}`);
  }
}
console.log('== counters (campaign-wide, per distribution)');
for (const dist of DISTS) for (const run of runs) {
  const r = read(path.join(run.dir, `hold-fuzz-${dist}-report.txt`));
  if (r) console.log(`  ${dist} ${run.name}  ` + COUNTERS.map(k => `${k}=${r.counters[k] ?? '?'}`).join(' '));
}
