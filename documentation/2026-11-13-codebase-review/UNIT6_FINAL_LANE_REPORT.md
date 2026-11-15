# Final lane of unit 6 of the review branch: report

`LANE_OUT=tmp/merge-lane-unit6 bash tools/merge-lane/lane.sh head`, run once by the main thread on 2026-11-15 at `9d6d0d82e`, tree clean. Baseline: unit 5's lane (`tmp/merge-lane-unit5/head`, the code of `54df44fb7`; [UNIT5_FINAL_LANE_REPORT.md](UNIT5_FINAL_LANE_REPORT.md)). Output: `tmp/merge-lane-unit6/head/`, comparison `tmp/merge-lane-unit6/compare.txt`, replays `tmp/merge-lane-unit6/replay/`, console `tmp/review-verify/lane-console-unit6.txt`. Counters only; no times.

**Result: green.** The lane's test step ended with exit 1 for a reason that is not a test: see "The column gate with nothing to run".

## What unit 6 holds (after the marker `claude/codebase-review-unit5`)

- **R-ST4** (a defect fix, results move): the column's and the exchanger coupling's readings that followed the machine. A running column's wait materialises a certified port island to the cycle's tick; its wakes are dated; a refused column solve is asked again for the same cycle; a coupled side's read materialises the other side to the slice start; the coupling's wakes are dated by the partner's span and acted on in tick order.
- **H-C5** (C5 of the simplifications): one column solve and one energy closure for the sheet and the world; each side keeps its cold start as a named choice.
- **J2** (documents): the code guide's chapters on one template.
- Two comments by the main thread.

## Steps

- **test.** Selection: 115/463 `test`, 9/10 `holdGates`, `equipmentGates` and `fluidSolverRegression` none. 1,084 tests, 0 skipped, 0 errors, 0 failures.
- **regression (chain-100).** Not run: the test loads nothing that changed. It stands from unit 5's lane (exact).
- **gametest.** 98 of 98. This is the first run in a world of the column's dated wakes and held solves and of the coupling's dated wakes.
- **campaign** at factor 4, `compare.mjs` against unit 5's lane:

| run | clean unit 5 lane → unit 6 | refused | holds |
|---|---|---|---|
| cold random | 992 → 992 | 0 / 0 | every outcome line equal |
| bundled random | 992 → 991 | 0 / 0 | wall deadline: 47, 59, 118 as before, 12 again; every other outcome line equal |
| bundled defaults | 1000 → 1000 | 0 / 0 | none; every counter identical |

- **Junction lines:** not run (nothing the junction tests load changed); unit 5's stand.

## The wall-deadline holds

Replayed alone once, on one thread: bundled 12 (end 14096), 47 (end 13499), 59 (end 13446) and 118 (end 14380) are clean, with the end ticks of every earlier lane. The bundled random campaign stands at 995. Cold seed 431 held on the wall deadline at tick 3085 in the lane, as in unit 5's; alone at this code it reaches tick 3485 on its crystal hold with unit 4's counters (4,302 Newton solves): the same edge of the wall budget, on a machine that is slower than on the day of unit 4's lane ([UNIT5_FINAL_LANE_REPORT.md](UNIT5_FINAL_LANE_REPORT.md)).

## The column gate with nothing to run

The lane selected one class for `columnGates`, `NativeColumnSolverTest`, because its source reads `src/main/rust/LAYOUT.md`, a file the lane cannot track through a load set; `columnGates` excludes that class (it runs in `nativeSmoke`, on the built library), so Gradle reported "No tests found" and the step's exit code is 1. In the earlier lanes the class was one of many selected and was dropped silently. Nothing under `src/main/rust` and nothing the library's tests load changed in unit 6. The selection tool should leave out a class its task excludes: listed for the tools.

## Printed lines against unit 5's lane

Fewer classes were selected, so most lines stand from unit 5's lane. Those that moved, each expected:

| line | unit 5's lane | unit 6 | from |
|---|---|---|---|
| sample sheet, warm start: checkpoints | 213,803 | 31,672 | H-C5's seed screen, by reading the code (not traced): a wet seed is dropped and the block starts from the learned start, where it tried the warm route with it and fell through V3's strict cold chain |
| sample sheet, cold: checkpoints | 907,450 | 321,343 | the same |
| sample sheet, `T-101` condenser: surface needed | 512.6 m² | 513.7 m² | H-C5: the condenser's duty is the closure on the sheet's streams (0.2 %) |
| its hot end, water drops, air cooler need, condenser drop | 56.52; 0.08519, 0.08448; 1268; 0.2869 bar | 56.55; 0.08520, 0.08450; 1269; 0.2870 bar | the same |
| `ColumnEjectorTest`, free vent: condenser | 51.23 MW | 51.25 MW | the same (0.04 %) |
| `ColumnRunTest` `C1B_BOOKING` at 330 K: differences | −35,154.8 J | −35,166.6 J | R-ST4: the certified port is replayed to the cycle's tick |
| `C1B_BOOKING` at 305 K: differences; water; owed | 1,048.1; 30,759.6; 5,288.5 J | 1,048.5; 30,760.2; 5,288.2 J | the same |
| `ColumnRunTest` `U2_SHORT`: valve | 0.014249 kg/s | 0.014173 kg/s | the same (0.5 %) |

New lines: the two column gates and their counts (`ColumnRunTest`), the two coupling gates (`ExchangerCouplingTest`). The sample's sizes, lamps and line sizes did not move.

## What stands

Every gate above stands for the code of `9d6d0d82e`; chain-100 and the junction lines stand from unit 5's lane. Not covered by any gate: a column and an exchanger pair in a played world on a slow machine; the items listed for units 1 to 5.
