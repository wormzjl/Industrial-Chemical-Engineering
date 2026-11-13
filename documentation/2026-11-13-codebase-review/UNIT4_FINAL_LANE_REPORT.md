# Final lane of unit 4 of the review branch: report

`LANE_OUT=tmp/merge-lane-unit4 bash tools/merge-lane/lane.sh head`, run once by the main thread on 2026-11-14 at the code of `3516c6b85` (the later commit `bcac53764` is documents), tree clean, no other Gradle run and no subagent writing. Baseline: unit 3's lane (`tmp/merge-lane-unit3/head`, the code of `36b5805d3`; [UNIT3_FINAL_LANE_REPORT.md](UNIT3_FINAL_LANE_REPORT.md)). Output: `tmp/merge-lane-unit4/head/`, comparison `tmp/merge-lane-unit4/compare.txt`, replay `tmp/merge-lane-unit4/replay/`, console `tmp/review-verify/lane-console-unit4.txt`. Counters only; no times.

**Result: green.** Three wall-deadline holds of the bundled campaign were replayed alone once and are clean.

## What unit 4 holds (after the marker `claude/codebase-review-unit3`)

Nine packages, all structure but one wire change. D-R3 (eight of the authority's ten displays on one `Display`), D-R4 (the authority in five files), D-Q3 (the designs' `Sizes` record), D-S4 and D-S5 (the step solver in eight files; `Equations` left in it), D-E4 and D-E5 (the equilibrium engine in thirteen files; the engine class is its façade), D-K1 (the client's shared frame layout and one units accessor), D-F1 (the completion router in `runtime`, the wire's count guards on one helper, one protocol version: the wire change).

## Steps

- **test.** Selection: 323/457 `test`, 10/10 `holdGates`, 44/87 `columnGates`, 19/29 `equipmentGates`, `fluidSolverRegression` 1/1. 3,345 tests, 0 skipped, 0 errors, 0 failures.
- **regression (chain-100).** Exact: max deviation 0 in moles, temperature, phase fraction and flow.
- **gametest.** 97 of 97. This is the first run of the split authority, the generic display's publication passes, the one protocol version between a client and a server, and `ClientConfig` on a server with its new method.
- **campaign** at factor 4, `compare.mjs` against unit 3's lane:

| run | clean unit 3 lane → unit 4 | refused | newtonSolves | holds |
|---|---|---|---|---|
| cold random | 992 → 992 | 0 / 0 | 328617 → 328617 | none new; every counter identical |
| bundled random | 995 → 992 | 0 / 0 | 133560 → 131506 | new: 12 F, 47 F, 59 F (replayed alone: clean) |
| bundled defaults | 1000 → 1000 | 0 / 0 | 26997 → 26997 | none; every counter identical |

- **Junction lines:** 33/33 identical with unit 3's lane, floats included; 33/33 counters identical. `MIXED_GAS_COST` newtonSolves 313 → 313, allocated 324.3 → 324.2 MB.

## The three budget-class holds

Seeds 12, 47 and 59 of the bundled random campaign held on `HELD: wall deadline`, at ticks 3023, 1507 and 1573. They are class F and not in unit 3's lane, so they were replayed alone once, in one run on one thread:

| seed | in the lane | alone | unit 3's lane |
|---|---|---|---|
| 12 | held at tick 3023 | clean, end 14096, holds 0 | clean, end 14096 |
| 47 | held at tick 1507 | clean, end 13499, holds 0 | clean, end 13499 |
| 59 | held at tick 1573 | clean, end 13446, holds 0 | clean, end 13446 |

They do not count. Seeds 47 and 59 held at these same ticks in unit 2's lane, were clean in unit 3's and hold again here with the machine quiet both times: they are early seeds whose first slices meet the wall budget while the campaign's eight threads start (seed 59 solves in a quarter of a second alone). The bundled random campaign stands at 995. Its counters differ from unit 3's lane only by these three seeds, which stopped at their hold: of the 3,000 outcome lines (end tick, holds, classes, every island's status, crystal mass) every other one is equal, and the cold and defaults campaigns' counters are identical to the last digit. So no solve of the campaign moved.

## Printed lines against unit 3's lane

Read with times, hashes and byte counts stripped: `MixedGasJunctionTransientTest`'s 24 lines differ in `bytes` only. No other printed line differs. (`V3IdentityTest`'s line is absent because the lane did not select that class this time; it stands from unit 3's lane.)

## What stands

Every gate above stands for the code of `3516c6b85`. The two campaign harnesses compiled and ran. Not covered by any gate: the watch, replay and fan-out paths of the eight displays (no GameTest sends a watch request); the machine page's two title keys; the items listed for units 1 to 3.
