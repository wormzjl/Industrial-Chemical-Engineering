// node tools/fluid-step-cost/bench/forms-counters.mjs <tag> ...: the linear-algebra counters of each bench's diagnostics run.
import fs from 'node:fs';
for (const t of process.argv.slice(2)) {
  const r = JSON.parse(fs.readFileSync(`tmp/pattern-census/bench-${t}/diag/report.json`, 'utf8'));
  const k = r.solverDiagnostics.counters;
  console.log(`${t.padEnd(5)} L+U a factorization ${(k.luFactorEntries / k.luFactorizations).toFixed(0).padStart(7)} | reach searched ${(k.luReachSearches / k.luColumnsFactored).toFixed(3)} | pattern not kept ${(k.luPatternSearches / k.luColumnsFactored).toFixed(3)}`
    + ` | refinements ${k.luRefinements} of ${k.luChecks} checks | newton solves ${k.newtonSolves} iterations ${k.newtonIterations} stale ${k.newtonIterationsStale} stalled ${k.newtonRefreshesStalled}`
    + ` | factorizations ${k.luFactorizations} | held ${r.heldIntervals} | status ${r.status} | factor ms each (diag) ${(k.luFactorNanos / 1e6 / k.luFactorizations).toFixed(2)}`);
}
