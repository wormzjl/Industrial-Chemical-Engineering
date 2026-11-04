# P1 and P2: where a run starts (built 2026-11-04)

PERFORMANCE_REVIEW.md, proposals 2 and 3. The owner chose "Warm start P1 + P2" (2026-11-04). Built as design A' below, which replaces this file's paper design (a seed map in the runner, keyed by the door). Results move within the loop's tolerance (Effect).

## Expected behaviour

- A column whose feed and settings moved a little since the last steady Run is near its old profile. Newton from that profile needs a few steps; the learned seed needs the whole homotopy.
- Before: each Run built a new column solve, so its warm seed and its feed-tray and steam-row pressures died with the Run. Only the tears carried over (`SimulatorRunner.steady`). A block's restored run started nothing, so the first Run after opening took 3 passes from cold.
- Measured at a958cecd2 (bench `size-stages`): Size all's re-run solved T-101 from the learned seed, 4.7 s and 773k checkpoints of its 4.81 s, though the trials had just solved it.

## As built (A')

- The seed rides on the column's report in the steady run: `Report.Warm(seed, feedTray, stages)`, which `ColumnBlockSolve` leaves after each solve. `FlowsheetCompiler.compile` hands each unit its report from `last` where `last` stood steady (`WiredUnit.last`); the column's first pass starts from it. No new engine argument, no map, no key.
- Validity: `Warm.current` (the package still at the seed's `scientificRevision`), then V3's own `warmCompatible` (same package, basis, stage count, feed stage and features; top pressure, feed and condenser temperatures, feed flows and reboiler duty within 50 %). Where it does not fit, or the warm attempt fails, the learned start runs, as before.
- P1: `SimulatorRunner.restore` sets `steady` from a block's stored run that stood steady. Stored results carry no `Warm` (`SimulatorResults` never writes it), so the first Run after opening starts its loops warm and its columns from the learned start.
- Stop: `stop()` replaces `steady` by `steady.cold()`, the same run without its columns' seeds. The next Run starts its loops where the steady run left them, its columns learned: a warm start that took long is not tried again.
- Size all: the column's first diameter trial starts from the run's `Warm` (`ColumnSizing.Trials`), each later one from the last trial that solved. The re-run starts from the shown run, so T-101 starts warm there too.
- A FAILED, NOT_STEADY or stopped Run leaves `steady` as it was. Closing the screen drops the runner. Solve alone starts its column learned, as before.

## Deviations from the paper design

- An edit to the column no longer drops its seed by a key on the door's `Params`. V3's compatibility check and its fallback judge the seed: a small edit (reflux, a duty) starts warm, a new stage count or feed stage starts learned.
- Size all's re-run starts from the run's own end state at the door's diameter, not from the trial at the chosen diameter. There is no seed per diameter; the profile hardly moves with the diameter, which only the hydraulics read.

## Effect (measured)

- Bench `bits` on the crude unit, against 6c1bf7bda: the cold Run is bit-identical (2,979 values; the new lines are the seed itself). The warm Run takes 33,391 checkpoints for 238,333 (−86 %); `SimulatorRunTest` counts 33,391 for a cold run's 853,590–857,142.
- The warm Run against the old one: component flows within 1.8e-4 relative, temperatures 1.1e-3 K, pressures 2.8e-7; T-101's energy gap 464 W on a 39.2 MW condenser. Size all: 6.2e-5, 5.1e-4 K, the sized sheet byte-identical, the same loop passes (1 and 2).
- That is inside the warm solve's closure (V3's gameplay closure, 0.1 % of each row's scale); Newton's last step lands orders of magnitude inside it. `ColumnEjectorTest` compared two runs of one column to 1e-6 and met 1.2e-6; its wired run now starts from no run, as the free one does, so it compares the ejector law, not the start.
- Two Runs of one sheet can print different last digits, depending on what ran before. The alternative (warm only inside Size all) was not taken.

## Gates

- `SimulatorRunTest.aRunFromASteadyOneStartsTheColumnWhereItEnded` (slow): the first solve starts LAST, the run stands steady, under half a cold run's checkpoints, streams within the band; Stop's cold copy starts LEARNED.
- `SimulatorRunTest.theSamplesResultsFitTheBodyAndComeBack`: the run carries a `Warm`, its stored copy none.
- `SimulatorRunnerTest.stopLeavesTheNextRunsColumnsToTheLearnedStart`, `SimulatorBlockTest.aStoredSteadyRunStartsTheNextRunsLoops`.
- `bits` (`tools/simulator-bench/`): the cold Run bit-identical to the commit before.
