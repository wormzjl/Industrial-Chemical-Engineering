// Compares a head merge lane with a baseline lane: campaign holds by class, counters, refusals, the printed lines, the
// 33 junction lines and MIXED_GAS_COST. Usage (worktree root):
//   node tools/merge-lane/compare.mjs <base lane folder> <head lane folder> [<campaign base folder>]
// The campaign compares against the third folder when given (the folder `affected.mjs select` names as the campaign's
// last reports, or a campaign at the head's time factor), else against the base lane's. The head runs the watch seeds
// (campaign-seeds.txt): a baseline of more seeds is compared on the head's seeds only, and the counters, which a
// report gives for all its seeds together, only when both ran the same seeds. A harness records its factor on the
// SUMMARY line (factor=, since the valve follow-ups batch, item 5); a pair of differing factors is a warning.
// The printed lines that differ go to <head lane folder>/printed-diff.txt, class by class.
import fs from 'fs';
import path from 'path';

const [baseDir, headDir, campaignDir] = process.argv.slice(2);
const read = (dir, name) => {
  const f = path.join(dir, name);
  return fs.existsSync(f) ? fs.readFileSync(f, 'utf8').replace(/\r/g, '') : null;
};

function campaign(dir, label) {
  const report = read(dir, `campaign-${label}-report.txt`);
  const classes = read(dir, `campaign-${label}-report.txt.classes.txt`);
  if (report == null) return null;
  const summary = (report.match(/^SUMMARY .*$/m) || [''])[0];
  const counters = {};
  const counterLine = (report.match(/^COUNTERS .*$/m) || [''])[0];
  for (const m of counterLine.matchAll(/(\w+)=(\d+)/g)) counters[m[1]] = Number(m[2]);
  const seeds = new Map(); // seed -> {held, refused}
  for (const m of report.matchAll(/^SEED (\d+) .*? holds=(\d+) .*? refused=(\d+)/gm)) seeds.set(Number(m[1]), { held: Number(m[2]) > 0, refused: Number(m[3]) });
  const seedClass = new Map();
  for (const line of (classes || '').split('\n').filter(Boolean)) {
    const name = line.slice(0, 60).trim();
    const list = (line.match(/list=(.*)$/) || ['', ''])[1].trim().split(/\s+/).filter(Boolean).map(Number);
    for (const s of list) seedClass.set(s, name);
  }
  return { summary, counters, seeds, seedClass };
}

for (const label of ['cold-random', 'bundled-random', 'bundled-defaults']) {
  const b = campaign(campaignDir || baseDir, label), h = campaign(headDir, label);
  console.log(`== campaign ${label}${campaignDir ? ' against ' + campaignDir : ''}`);
  if (!h) { console.log('   not run on head (no watch seed, or the campaign was not due): the baseline stands'); continue; }
  if (!b) { console.log('   missing report base'); continue; }
  const factor = s => (s.match(/factor=(\d+)/) || [])[1] || 'not recorded';
  console.log(`   time factor base ${factor(b.summary)} head ${factor(h.summary)}`);
  if (factor(b.summary) !== factor(h.summary)) console.log('   WARNING: the time factors differ or one is not recorded; counts compare only at one factor');
  // On the head's seeds: a baseline of 1000 seeds says nothing more about a head of the watch seeds.
  const common = [...h.seeds.keys()].filter(s => b.seeds.has(s));
  const same = common.length === h.seeds.size && common.length === b.seeds.size;
  if (!same) console.log(`   seeds base ${b.seeds.size} head ${h.seeds.size}: compared on the ${common.length} seeds both ran`);
  const clean = side => common.filter(s => !side.seeds.get(s).held).length;
  const refused = side => common.reduce((n, s) => n + side.seeds.get(s).refused, 0);
  console.log(`   clean base ${clean(b)} head ${clean(h)} of ${common.length}; refused base ${refused(b)} head ${refused(h)}`);
  if (same) for (const k of ['newtonSolves', 'newtonIterations', 'implicitSolves']) console.log(`   ${k} base ${b.counters[k]} head ${h.counters[k]}`);
  else console.log('   counters: not compared (the reports total different seeds); the next lane compares against this one');
  const held = side => common.filter(s => side.seedClass.has(s)).map(s => [s, side.seedClass.get(s)]);
  const newHolds = held(h).filter(([s]) => !b.seedClass.has(s));
  const fixed = held(b).filter(([s]) => !h.seedClass.has(s));
  const moved = held(h).filter(([s, c]) => b.seedClass.has(s) && b.seedClass.get(s) !== c);
  console.log(`   new holds ${newHolds.length}: ${newHolds.map(([s, c]) => s + ' ' + c.split(' ')[0]).join(', ')}`);
  console.log(`   fixed holds ${fixed.length}: ${fixed.map(([s, c]) => s + ' ' + c.split(' ')[0]).join(', ')}`);
  console.log(`   class moved ${moved.length}: ${moved.map(([s, c]) => s + ' ' + b.seedClass.get(s).split(' ')[0] + '->' + c.split(' ')[0]).join(', ')}`);
  const budget = newHolds.filter(([, c]) => /^(C|F) /.test(c));
  console.log(`   new budget-class (C, F) holds: ${budget.length ? budget.map(([s, c]) => s + ' ' + c.split(' ')[0]).join(', ') : 'none'}`);
  if (newHolds.length) console.log('   a new hold joins its line of tools/merge-lane/campaign-seeds.txt if it is not on it');
}

// The printed lines of the test classes both lanes ran, without times, paths and map order: which classes moved.
const printed = dir => (read(dir, 'printed.txt') || '').split('\n').filter(Boolean)
  .map(l => l.replace(/\[\d\d:\d\d:\d\d\]/g, '[T]').replace(/[A-Z]:[\\/][^\s"']*?[\\/]\.claude[\\/]worktrees[\\/][\w.-]+/g, 'WT')
    .replace(/\b\d+(\.\d+)?\s*(ms|s)\b/g, '#ms').replace(/(ms|wallMs|seedMsSum|time|nanos|allocatedMB|MB|bytes)=[\d.]+/gi, '$1=#')
    .replace(/\{([^{}]*)\}/g, (m, x) => '{' + x.split(', ').sort().join(', ') + '}').trimEnd());
{
  const group = ls => { const g = new Map(); for (const l of ls) { const c = l.split(' ')[0]; if (!g.has(c)) g.set(c, []); g.get(c).push(l); } return g; };
  const bp = group(printed(baseDir)), hp = group(printed(headDir));
  const both = [...hp.keys()].filter(c => bp.has(c)).sort(), diff = [];
  let movedClasses = 0;
  for (const c of both) {
    const left = new Map(); for (const l of bp.get(c)) left.set(l, (left.get(l) || 0) + 1);
    const onlyHead = []; for (const l of hp.get(c)) { const n = left.get(l) || 0; if (n) left.set(l, n - 1); else onlyHead.push(l); }
    const onlyBase = [...left].flatMap(([l, n]) => Array(n).fill(l));
    if (!onlyBase.length && !onlyHead.length) continue;
    movedClasses++;
    diff.push(`### ${c}`, ...onlyBase.map(l => '- ' + l), ...onlyHead.map(l => '+ ' + l));
  }
  const fresh = [...hp.keys()].filter(c => !bp.has(c));
  console.log(`== printed lines: ${both.length} classes in both lanes, ${movedClasses} with lines that differ${fresh.length ? `; only on head: ${fresh.join(' ')}` : ''}`);
  if (diff.length) { fs.writeFileSync(path.join(headDir, 'printed-diff.txt'), diff.join('\n') + '\n'); console.log(`   ${diff.filter(l => l.startsWith('###')).map(l => l.slice(4)).join(' ')}`); console.log(`   lines in ${path.join(headDir, 'printed-diff.txt')}`); }
}

// Wall time and allocation are machine noise: compare the lines without them.
const strip = line => line.replace(/\bms=\d+(\.\d+)?,?\s*/g, '').replace(/\ballocatedMB=[\d.]+\s*/g, '')
    .replace(/,?\s*\bbytes=\d+/g, '').trim();
// The lane leaves junction.txt out when it did not select the junction tests: the baseline's lines stand.
if (read(headDir, 'junction.txt') == null) {
  console.log('== junction lines and MIXED_GAS_COST: not run on head (nothing the junction tests load changed); the baseline stands');
  process.exit(0);
}
const bj = (read(baseDir, 'junction.txt') || '').split('\n').filter(Boolean).map(strip);
const hj = (read(headDir, 'junction.txt') || '').split('\n').filter(Boolean).map(strip);
// A line is paired by its case: the test, the tag, the interval and the case's key (a lane that ran only some of the
// junction tests has fewer lines, in another order).
const caseOf = l => l.split(' ').slice(0, l.includes(' MIXED_GAS_COST ') ? 3 : 4).join(' ');
const byCase = lines => new Map(lines.map(l => [caseOf(l), l]));
const bc = byCase(bj), hc = byCase(hj), both = [...hc.keys()].filter(k => bc.has(k));
const only = (a, b) => [...a.keys()].filter(k => !b.has(k));
let same = 0;
const differ = [];
for (const k of both) {
  if (bc.get(k) === hc.get(k)) { same++; continue; }
  const bt = bc.get(k).split(' '), ht = hc.get(k).split(' ');
  const j = bt.findIndex((t, i) => t !== ht[i]);
  differ.push(`   ${k} ... base "${bt[j]}" head "${ht[j]}"`);
}
console.log(`== junction lines: base ${bj.length}, head ${hj.length}, in both ${both.length}, identical ${same} (floats included)`);
for (const d of differ.slice(0, 12)) console.log(d);
for (const [side, keys] of [['base (the baseline stands)', only(bc, hc)], ['head', only(hc, bc)]]) if (keys.length) console.log(`   only on ${side}: ${keys.length} lines, the first ${keys[0]}`);
// The references are the integer counters (Newton solves, cases); the floats move with round-off.
const ints = l => [...l.matchAll(/\b([A-Za-z]+)=(-?\d+)(?=[,\s\])]|$)/g)].filter(m => m[1] !== 'interval' && m[1] !== 'ms').map(m => m[1] + '=' + m[2]).join(' ');
let sameCounters = 0;
for (const k of both) {
  if (ints(bc.get(k)) === ints(hc.get(k))) { sameCounters++; continue; }
  console.log(`   counters ${k}: base ${ints(bc.get(k))} head ${ints(hc.get(k))}`);
}
console.log(`== junction counters identical on ${sameCounters} of ${both.length}`);
for (const [dir, side] of [[baseDir, 'base'], [headDir, 'head']]) {
  const p = read(dir, 'printed.txt') || '';
  console.log(`== ${side} ${strip((p.match(/^MixedGasJunctionTransientTest MIXED_GAS_COST .*$/m) || ['MIXED_GAS_COST missing'])[0])}`);
}
