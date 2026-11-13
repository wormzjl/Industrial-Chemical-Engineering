# Wave B, lane C1 (column): report

Branch `claude/review-wave-a1-column-b4eca7`, merged up to `0f0993162` (fast-forward, clean). Five items, P2 left out. All named tests pass, the column digests and traces among them; every item is bit for bit. Commits after the items: 0e17ff0ec (P8 comment and tests), dca88fa19 (P7 count test reads the total by its label), fd8f1a5da (wave A: MaterialCatalogTest, for the cherry-pick).

## Items

| Id | Commit | What changed |
|---|---|---|
| P7 | 379a16df1, dca88fa19 | Band factorizations counted by call site in the calculation-scoped pool of `V3SolveScratch` (one per outermost calculation, not a static): Newton step, local block step, final certificate's direct and damped corrections, damped Gauss-Newton fallback. An accepted solve's last solver event: `band factorizations: newton n, local block n, verify direct n, verify damped n, damped fallback n, total n` (after the termination, where the event list has room). The tray-hydraulics event names each correction's factorizations beside its polls. |
| P3 | 2ea7f1ef4 | `V3DegreeOfFreedomLedger` on int tables: an index table keyed in `UnknownId` order (family, node, component); each equation's references gathered as order keys, sorted and made unique (the sorted set the records held), kept as unknown indexes; Kuhn's matching on them in the same equation order and reference order, so the same augmenting paths and matching. `Equation` records with references are built only when `equations()` is read; `V3StageBlockLayout` reads `equationIds()`. Gone: the per-probe `UnknownId` and `HashMap` node, the per-equation `HashSet` and sorted list, the id-to-index `HashMap`, and the dead `EQUATION_REFERENCES_REMOVED_PHASE` check. |
| P8 | 596b35de6, then the comment and tests | The elimination's swap and update stop at the dgbtf2 extent `ju = max(ju, min(pivotRow + ku, n - 1))`, not `pivot + kl + ku`. Sign of zero checked: the pivot row is +0.0 past `ju`; a target row's skipped `x - m*(+0.0)` differs from x only for x = -0.0, and a -0.0 can arise only from row scaling (a negative entry under 2^-1074 of its row's largest). A band holding one after scaling takes the full width (parent decision: option A); the method comment states the argument in four lines. Tests: pivoted bands and a -0.0 band give the bits of a dense full-width reference in the same operation order. Growth maximum and guard unchanged; back substitution unchanged (a -0.0 right-hand side would change sign there). The workspace counts entries updated against the full width, and full-width solves. |
| P9 | de172ee2a | The update loop alone (`band[r+c] -= m * band[p+c]`, never fused in Java), then the growth maximum over the same columns in its own pass: the same number, NaN and infinity included. Back substitution has no maximum and stays. |
| P6 | cdd29c845 | The untruncated feed flash (attempt cutoff 0) kept in the calculation's pool, keyed by the column identity, feed temperature, feed-tray pressure and feed flows (16 entries at most). It takes no control, so no checkpoint is skipped; its result is immutable. The truncated flash polls the control and stays computed each time. |
| P2 | none | Left out of this round (parent decision: option A): a failed attempt's audit polls the control 4 + nodes times (fewer when its residual throws), and the learned route's budget and the hydraulic budget count those polls, so skipping it can move a request across a budget boundary; faking the polls is not exact, as their number depends on whether the failed state's residual evaluates. P2 becomes identical once D6 gives the budgets a physical unit. |

## Counters (before from B1 and the code; after from the lane)

| Item | Before | After |
|---|---|---|
| P7 factorizations per solve (newton / local block / verify direct / verify damped / damped fallback = total; hydraulic correction's share) | not counted | medium cold (lnn): 0 / 17 / 4 / 0 / 0 = 21, correction 5. Medium warm (warm/aggressive): 0 / 6 / 2 / 0 / 0 = 8, correction 4. Literature cold (lnn): 2 / 21 / 3 / 0 / 0 = 26, correction 5. Literature warm (classical chain, stage continuation 15-30-40, draw ramp): 19 / 153 / 19 / 13 / 0 = 204, correction 5 |
| P3 ledger | B1: `augment` 11 to 16 % of column CPU; `UnknownId` with `HashMap$Node` 7 to 10 % of column bytes (cold medium: `addIfActive` 335/336, 6.0 GB of 62.5 GB) | records: one `Unknown` per unknown, no reference record unless `equations()` is read; bytes per solve: the main thread's bench |
| P8 elimination entries updated (pooled workspace) | full width: medium cold 68.6 M, medium warm 25.9 M, literature cold 63.2 M, literature warm 481 M | 34.5 M, 13.3 M, 32.1 M, 265 M: 50 to 55 % of the full width. Full-width solves: 0 on all four |
| P9 | fused loop | no counter shows it; a time from the bench only |
| P6 untruncated feed flashes | each one computed | computed / reused: medium cold 2 / 0, medium warm 2 / 0, literature cold 2 / 1, literature warm 2 / 8 (the saving is on the classical chain) |

## Tests run (one compile, each class once after it last changed)

- `columnGates`, all pass: V3BandFactorizationCountTest 1 (first run failed on its own parser, fixed, rerun alone), V3TrayHydraulicsColumnTest 9 (poll counts as in A1: 1,676 to 21,282, budget 200,000), V3DegreeOfFreedomLedgerTest 8, V3StructuralMatchingOracleTest 1, V3TruncationStructureTest 12, V3BandedPivotedSolverTest 11, V3PivotGrowthTest 5, V3ContinuationSeedTest 5, V3ConvergenceClosureTest 16 and V3StageTraceCalculatorTest 5 (digests), V3ColumnCalculatorTest 13, V3PathDependentInfeasibilityTest 3.
- `test`: MaterialCatalogTest 14, pass.
- Bytes per solve (ThreadMXBean, as `ColumnBench` reads them) and times: the main thread's bench after the round.

## Not done

- P2: stopped (above). P1, L1 to L4, P4, P5, F10 to F12, D3: not in this round.
- P9 has no counter; its effect shows only in the bench's times.
