# Final lane of unit 2 of the review branch: report

`LANE_OUT=tmp/merge-lane-unit2 bash tools/merge-lane/lane.sh head`, run once by the main thread on 2026-11-13 at the code of `088e9887a` (the later commits to `f2543d8fa` are documents), tree clean, no other Gradle run. Three subagents were reading and writing files on the machine during the campaign step (no builds). Baseline: the 0.30.0 final lane (`tutorial-system-final-lane-logs-2026-11-13.tar.gz`, `head/`). Output: `tmp/merge-lane-unit2/head/`, comparison `tmp/merge-lane-unit2/compare.txt`, replays `tmp/merge-lane-unit2/replay/`, console `tmp/review-verify/lane-console-unit2.txt`. Counters only; no times.

**Result: green.** Three wall-deadline holds of the campaign were replayed alone once and are clean.

## What unit 2 holds (after the marker `claude/codebase-review-unit1`)

The kept LU storages and EJML out of the jar (P-17b, P-17c); G1 part 2 (wet gas, wet exhaust on the turbine's own exhaust, the gearbox limit, the trips removed); C4a (`science/column/operation` to `science/plant/column`); E0-3 (the column's codes as enums); C1b (the engine free of the network's package, D09); J2 (the map script, `LayeringTest`, `FormatsTableTest`); E0-4 (the inside-out trial removed); C3 (machines below the sheet and the geometry); C2 (material free of the solvers); the translation commit; small items.

## Steps

- **test.** Selection: 383/454 `test` (the task ran whole: the filter list was too long), 10/10 `holdGates`, 78/87 `columnGates`, 22/29 `equipmentGates`, `fluidSolverRegression` 1/1. 3,581 tests, 0 skipped, 0 errors, 0 failures. 563 load sets saved, none failing.
- **regression (chain-100).** Exact: max deviation 0 in moles, temperature, phase fraction and flow.
- **gametest.** 97 of 97 (`CreateCouplingGameTests` with its 30 kW gearbox among them).
- **campaign** at factor 4, `compare.mjs` against the baseline:

| run | clean base → head | refused | newtonSolves | newtonIterations | implicitSolves | holds |
|---|---|---|---|---|---|---|
| cold random | 992 → 991 | 0 / 0 | 327588 → 327015 | 1988631 → 1984579 | 197157 → 196831 | new: 6 F |
| bundled random | 994 → 992 | 0 / 0 | 134674 → 131190 | 685337 → 635031 | 94735 → 92909 | new: 47 F, 59 F; 431 moved F → E9 (as in unit 1) |
| bundled defaults | 1000 → 1000 | 0 / 0 | 26997 → 26997 | 65438 → 65447 | 22539 → 22539 | none |

- **Junction lines:** 33/33 identical with the baseline, floats included; 33/33 counters identical. `MIXED_GAS_COST` newtonSolves 313 → 313, allocated 885.1 → 324.1 MB.

## The three new budget-class holds

Seeds 6 (cold), 47 and 59 (bundled random) held on `HELD: wall deadline`, each early in its run (ticks 2028, 1507 and 1573). They are class F, not in the baseline and not in unit 1's lane, so each was replayed alone once (one thread, batched: one run for the cold seed, one for the two bundled seeds):

| seed | in the lane | alone | baseline lane |
|---|---|---|---|
| cold 6 | held at tick 2028 | clean, end 14021, holds 0 | clean, end 14021 |
| bundled 47 | held at tick 1507 | clean, end 13499, holds 0 | clean, end 13499 |
| bundled 59 | held at tick 1573 | clean, end 13446, holds 0 | clean, end 13446 |

Seed 59 is a cheap seed (well under a second of solving in the baseline), so its hold is a stall of the machine, not a cost. The three do not count (AGENTS.md: a wall-clock budget hold is replayed alone before it is counted). With them the campaigns stand at the baseline: cold 992, bundled random 994 (431 in E9), defaults 1000.

## Printed lines that moved against unit 1's lane

Read with times, time stamps, object hashes and byte counts stripped. No gate reference moved: every class passed, chain-100 is exact and the junction lines are identical.

- **E0-4, as stated:** dry cold column solves moved inside the closure. `V3StageContinuationTest`, plain 40-tray literature column: a tray temperature 464.06867188971063 → 464.0686718897107 K, a liquid flow in its 14th digit; factorizations 137 → 116 (Newton 31 → 0, block 78 → 88). `V3StageTraceCalculatorTest` 323.15 K: factorizations 98 → 75, residual 1.8e-14 → 2.8e-14. `V3PumparoundCalculatorTest` base case: residual 2.5e-14 → 3.2e-14. `CrudeUnitSampleTest` packed body 22962 → 22963 bytes.
- **E0-3:** `Attempt.Failure` prints `attemptCode=` where it printed `code=` (one assertion message line of `V3SimultaneousColumnSolverTest`).
- **New lines:** `KeptLuStorageTest` (0 storages, 0 reach searches on the kept handle), `MachineHealthTest` (exhaust moisture 0.0620), `PassiveStepSolverTest` byte counts.
- Log noise only: "Unable to delete file ... debug.log" lines appear in other classes than before.

## What stands

Every gate above stands for the code of `088e9887a`. Not covered by any gate: the five screens' frame batch in the game (unit 1), the Health meter on a compressor's and a turbine's page, a turbine's wet exhaust in a built plant (no review world has a steam turbine).
