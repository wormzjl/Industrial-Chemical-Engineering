// node tools/fluid-step-cost/bench/bench-read.mjs <tag> [keys]: the timed and the counters report of one bench pair, in short.
import fs from 'node:fs';
const tag = process.argv[2];
const dir = `tmp/pattern-census/bench-${tag}/`;
const read = f => JSON.parse(fs.readFileSync(dir + f, 'utf8'));
const timed = read('timed/report.json'), diag = read('diag/report.json');
if (process.argv[3] === 'keys') {
  const walk = (o, p, d) => { for (const [k, v] of Object.entries(o)) { if (k === 'manifest' || k === 'jvmArguments') continue; const q = p ? p + '.' + k : k; if (v && typeof v === 'object' && !Array.isArray(v) && d < 2) walk(v, q, d + 1); else console.log(q, '=', Array.isArray(v) ? '[' + v.length + ']' : JSON.stringify(v).slice(0, 70)); } };
  walk(timed, '', 0);
  console.log('--- diag solverDiagnostics keys:', Object.keys(diag.solverDiagnostics || {}).join(', '));
  process.exit(0);
}
const pick = (o, keys) => keys.map(k => `${k}=${k.split('.').reduce((x, y) => x == null ? x : x[y], o)}`).join('  ');
console.log(tag, 'timed:', pick(timed, (process.argv[3] || '').split(',').filter(Boolean)));
const c = diag.solverDiagnostics?.counters || {}, per = diag.solverDiagnostics?.perAcceptedInterval || {};
const names = ['newtonSolves', 'newtonIterations', 'newtonIterationsFresh', 'newtonIterationsStale', 'residualEvaluations', 'residualEvaluationsInJacobian', 'jacobianBuilds', 'jacobianBlockBuilds', 'jacobianDeclaredEntries', 'jacobianNonzeros', 'luFactorizations', 'luFactorNanos', 'luFactorEntries', 'luSolves', 'luSolveNanos', 'luOrderings', 'luOrderingNanos', 'luColumnsFactored', 'luReachSearches', 'luPatternSearches', 'luStorages', 'transportFactorizations', 'transportFactorNanos', 'transportFactorEntries', 'transportSolveNanos'];
for (const n of names) if (n in c) console.log('  ', n.padEnd(32), String(c[n]).padStart(14), per[n] !== undefined ? '  per interval ' + Number(per[n]).toFixed(1) : '');
const f = c.luFactorizations, j = c.jacobianBuilds;
console.log('  derived: kept share', (c.jacobianNonzeros / c.jacobianDeclaredEntries).toFixed(3), '| declared a build', (c.jacobianDeclaredEntries / j).toFixed(0), '| kept a build', (c.jacobianNonzeros / j).toFixed(0),
  '| L+U a factorization', (c.luFactorEntries / f).toFixed(0), '| factor ms', (c.luFactorNanos / 1e6).toFixed(0), 'solve ms', (c.luSolveNanos / 1e6).toFixed(0), 'ordering ms', (c.luOrderingNanos / 1e6).toFixed(0),
  '| reach searched', (c.luReachSearches / c.luColumnsFactored).toFixed(3), '| fresh iterations', (c.newtonIterationsFresh / c.newtonIterations).toFixed(3));
