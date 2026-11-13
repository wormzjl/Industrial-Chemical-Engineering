# Wave B, lane C1 (column): report

Branch `claude/review-wave-a1-column-b4eca7`, merged up to `0f0993162` (fast-forward, clean). Five commits; P2 stopped. **Tests pending**: no Gradle has run in this round (the final lane of wave A holds the machine). Every item is meant bit for bit; the lane's run is the proof, and the counts below are filled in from it.

## Items

| Id | Commit | What changed |
|---|---|---|
| P7 | 379a16df1 | Band factorizations counted by call site in the calculation-scoped pool of `V3SolveScratch` (one per outermost calculation, not a static): Newton step, local block step, final certificate's direct and damped corrections, damped Gauss-Newton fallback. An accepted solve's last solver event: `band factorizations: newton n, local block n, verify direct n, verify damped n, damped fallback n, total n` (after the termination, where the event list has room). The tray-hydraulics event names each correction's factorizations beside its polls. |
| P3 | 2ea7f1ef4 | `V3DegreeOfFreedomLedger` on int tables: an index table keyed in `UnknownId` order (family, node, component); each equation's references gathered as order keys, sorted and made unique (the sorted set the records held), kept as unknown indexes; Kuhn's matching on them in the same equation order and reference order, so the same augmenting paths and matching. `Equation` records with references are built only when `equations()` is read; `V3StageBlockLayout` reads `equationIds()`. Gone: the per-probe `UnknownId` and `HashMap` node, the per-equation `HashSet` and sorted list, the id-to-index `HashMap`, and the dead `EQUATION_REFERENCES_REMOVED_PHASE` check. |
| P8 | 596b35de6, then the comment and tests | The elimination's swap and update stop at the dgbtf2 extent `ju = max(ju, min(pivotRow + ku, n - 1))`, not `pivot + kl + ku`. Sign of zero checked: the pivot row is +0.0 past `ju`; a target row's skipped `x - m*(+0.0)` differs from x only for x = -0.0, and a -0.0 can arise only from row scaling (a negative entry under 2^-1074 of its row's largest). A band holding one after scaling takes the full width (parent decision: option A); the method comment states the argument in four lines. Tests: pivoted bands and a -0.0 band give the bits of a dense full-width reference in the same operation order. Growth maximum and guard unchanged; back substitution unchanged (a -0.0 right-hand side would change sign there). The workspace counts entries updated against the full width, and full-width solves. |
| P9 | de172ee2a | The update loop alone (`band[r+c] -= m * band[p+c]`, never fused in Java), then the growth maximum over the same columns in its own pass: the same number, NaN and infinity included. Back substitution has no maximum and stays. |
| P6 | cdd29c845 | The untruncated feed flash (attempt cutoff 0) kept in the calculation's pool, keyed by the column identity, feed temperature, feed-tray pressure and feed flows (16 entries at most). It takes no control, so no checkpoint is skipped; its result is immutable. The truncated flash polls the control and stays computed each time. |
| P2 | none | Left out of this round (parent decision: option A): a failed attempt's audit polls the control 4 + nodes times (fewer when its residual throws), and the learned route's budget and the hydraulic budget count those polls, so skipping it can move a request across a budget boundary; faking the polls is not exact, as their number depends on whether the failed state's residual evaluates. P2 becomes identical once D6 gives the budgets a physical unit. |

## Counters (before from B1 and the code; after from the lane)

| Item | Before | After |
|---|---|---|
| P7 factorizations per solve, by site | not counted | pending: medium cold, medium warm, literature cold, literature warm (`V3BandFactorizationCountTest` prints them) |
| P3 ledger | B1: `augment` 11 to 16 % of column CPU; `UnknownId` with `HashMap$Node` 7 to 10 % of column bytes (cold medium: `addIfActive` 335/336, 6.0 GB of 62.5 GB) | records: one `Unknown` per unknown, no reference record unless `equations()` is read; bytes per solve pending (bench) |
| P8 elimination | updates to `pivot + kl + ku` (about 166 a row at 42 unknowns a stage) | pending: `elimination updates X of Y at full width, full-width solves Z` in the event (full-width solves expected 0 on the bench inputs) |
| P9 | fused loop | no counter shows it; a time from the bench only |
| P6 feed flashes | one per `solveSingleProblem`, projection and prepass | pending: `feed flashes N computed, M reused` in the event |

## Tests to run after "lane free" (each once)

- `columnGates`: V3BandFactorizationCountTest (new), V3TrayHydraulicsColumnTest (parser), V3DegreeOfFreedomLedgerTest (new ordering case), V3StructuralMatchingOracleTest (int signature), V3TruncationStructureTest, V3BandedPivotedSolverTest (two new cases), V3PivotGrowthTest, V3ContinuationSeedTest, V3ConvergenceClosureTest and V3StageTraceCalculatorTest (digests), V3ColumnCalculatorTest, V3PathDependentInfeasibilityTest.
- Bytes per solve (ThreadMXBean, as `ColumnBench` reads them): the main thread's bench after the round.

## Not done

- P2: stopped (above). P1, L1 to L4, P4, P5, F10 to F12, D3: not in this round.
- Counters after: pending until the lane runs.
