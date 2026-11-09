// Compares a head merge lane with a baseline lane: campaign holds by class, counters, refusals, the 33 junction lines and
// MIXED_GAS_COST. Usage (worktree root): node tools/merge-lane/compare.mjs <base lane folder> <head lane folder>
import fs from 'fs';
import path from 'path';

const [baseDir, headDir] = process.argv.slice(2);
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
  let refused = 0;
  for (const m of report.matchAll(/^SEED \d+ .*? refused=(\d+)/gm)) refused += Number(m[1]);
  const byClass = new Map();
  const seedClass = new Map();
  for (const line of (classes || '').split('\n').filter(Boolean)) {
    const name = line.slice(0, 60).trim();
    const list = (line.match(/list=(.*)$/) || ['', ''])[1].trim().split(/\s+/).filter(Boolean).map(Number);
    byClass.set(name, list);
    for (const s of list) seedClass.set(s, name);
  }
  return { summary, counters, refused, byClass, seedClass };
}

for (const label of ['cold-random', 'bundled-random', 'bundled-defaults']) {
  const b = campaign(baseDir, label), h = campaign(headDir, label);
  console.log(`== campaign ${label}`);
  if (!b || !h) { console.log('   missing report', !b ? 'base' : 'head'); continue; }
  const clean = s => (s.match(/clean=(\d+)/) || [])[1];
  console.log(`   clean base ${clean(b.summary)} head ${clean(h.summary)}; refused base ${b.refused} head ${h.refused}`);
  for (const k of ['newtonSolves', 'newtonIterations', 'implicitSolves']) {
    console.log(`   ${k} base ${b.counters[k]} head ${h.counters[k]}`);
  }
  const newHolds = [...h.seedClass].filter(([s]) => !b.seedClass.has(s));
  const fixed = [...b.seedClass].filter(([s]) => !h.seedClass.has(s));
  const moved = [...h.seedClass].filter(([s, c]) => b.seedClass.has(s) && b.seedClass.get(s) !== c);
  console.log(`   new holds ${newHolds.length}: ${newHolds.map(([s, c]) => s + ' ' + c.split(' ')[0]).join(', ')}`);
  console.log(`   fixed holds ${fixed.length}: ${fixed.map(([s, c]) => s + ' ' + c.split(' ')[0]).join(', ')}`);
  console.log(`   class moved ${moved.length}: ${moved.map(([s, c]) => s + ' ' + b.seedClass.get(s).split(' ')[0] + '->' + c.split(' ')[0]).join(', ')}`);
  const budget = newHolds.filter(([, c]) => /^(C|F) /.test(c));
  console.log(`   new budget-class (C, F) holds: ${budget.length ? budget.map(([s, c]) => s + ' ' + c.split(' ')[0]).join(', ') : 'none'}`);
}

// Wall time and allocation are machine noise: compare the lines without them.
const strip = line => line.replace(/\bms=\d+(\.\d+)?,?\s*/g, '').replace(/\ballocatedMB=[\d.]+\s*/g, '')
    .replace(/,?\s*\bbytes=\d+/g, '').trim();
const bj = (read(baseDir, 'junction.txt') || '').split('\n').filter(Boolean).map(strip);
const hj = (read(headDir, 'junction.txt') || '').split('\n').filter(Boolean).map(strip);
let same = 0;
const differ = [];
for (let i = 0; i < Math.max(bj.length, hj.length); i++) {
  if (bj[i] === hj[i]) { same++; continue; }
  const bt = (bj[i] || '').split(' '), ht = (hj[i] || '').split(' ');
  const j = bt.findIndex((t, k) => t !== ht[k]);
  differ.push(`   line ${i + 1}: ${bt.slice(0, 4).join(' ')} ... base "${bt[j]}" head "${ht[j]}"`);
}
console.log(`== junction lines: base ${bj.length}, head ${hj.length}, identical ${same} (floats included)`);
for (const d of differ.slice(0, 12)) console.log(d);
// The references are the integer counters (Newton solves, cases); the floats move with round-off.
const ints = l => [...l.matchAll(/\b([A-Za-z]+)=(-?\d+)(?=[,\s\])]|$)/g)].filter(m => m[1] !== 'interval').map(m => m[1] + '=' + m[2]).join(' ');
let sameCounters = 0;
for (let i = 0; i < Math.max(bj.length, hj.length); i++) {
  if (ints(bj[i] || '') === ints(hj[i] || '')) { sameCounters++; continue; }
  console.log(`   counters line ${i + 1}: base ${ints(bj[i] || '')} head ${ints(hj[i] || '')}`);
}
console.log(`== junction counters identical on ${sameCounters} of ${Math.max(bj.length, hj.length)}`);
for (const [dir, side] of [[baseDir, 'base'], [headDir, 'head']]) {
  const p = read(dir, 'printed.txt') || '';
  console.log(`== ${side} ${(p.match(/^MixedGasJunctionTransientTest MIXED_GAS_COST .*$/m) || ['MIXED_GAS_COST missing'])[0]}`);
}
