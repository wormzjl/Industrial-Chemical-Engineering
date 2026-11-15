# Final lane of unit 5 of the review branch: report

`LANE_OUT=tmp/merge-lane-unit5 bash tools/merge-lane/lane.sh head`, run once by the main thread on 2026-11-15 at the code of `54df44fb7` (the later commits to `a6894fe88`, which the lane's last steps name, are documents). Baseline: unit 4's lane (`tmp/merge-lane-unit4/head`, the code of `3516c6b85`; [UNIT4_FINAL_LANE_REPORT.md](UNIT4_FINAL_LANE_REPORT.md)). Output: `tmp/merge-lane-unit5/head/`, comparison `tmp/merge-lane-unit5/compare.txt`, replays `tmp/merge-lane-unit5/replay/`, console `tmp/review-verify/lane-console-unit5.txt`, the timing check `tmp/ab/`.

**Result: green.** Four wall-deadline holds were replayed alone and are clean; the machine ran about a fifth slower than on the day of unit 4's lane, on unit 4's code as on unit 5's.

## What unit 5 holds (after the marker `claude/codebase-review-unit4`)

Thirteen packages and five changes by the main thread.

- **Structure, results bit for bit:** D-R5 (the saved facts in sections), D-S6 (`StepEquations`), D-W1 (`EquipmentStructure` in files), I-1 (`PhaseTraits` on every answer), C5a (`StepPhaseRules`; the thermo port below the solver), D-E6 (`FailureKind`, `ThermoRefusal`), D-K2, D-K3, D-K4 (the client), F0-S1's P-07 (pooled buffers in the residual).
- **Declared revisions in place of hashes:** H-R11 (the certificate's `REPLAY_REVISION`), H-E20 (a design's layout revision; old stamps read as an alias).
- **A defect fix that moves results:** R-ST3 (the batch still beside certified islands; a refused column job).
- **By the main thread:** a tank's Accessory tab (a client crash); column products priced by one flash each, by mass (C2); a heat curve's phase boundary to 1e-3 K (L-F11); the sheet's compressor discharge on the world's polytropic exponent (P3-02); one ejector load sum on the suction's vapour (P3-03).

## Steps

- **test.** Selection: 352/463 `test` (the filters were too long, so the task ran whole), 10/10 `holdGates`, 68/87 `columnGates`, 22/29 `equipmentGates`, `fluidSolverRegression` 1/1. 3,563 tests, 0 skipped, 0 errors, 0 failures.
- **regression (chain-100).** Exact: max deviation 0 in moles, temperature, phase fraction and flow.
- **gametest.** 98 of 98 (one more than unit 4's lane: `EquipmentCompatGameTests`' case for the four ways a cell reads its layout). This is the first run in a world of the equipment structure in files, the layout revision, the facts in sections, the still's wakes with certificates on, and the column's products priced by mass.
- **campaign** at factor 4, `compare.mjs` against unit 4's lane:

| run | clean unit 4 lane → unit 5 | refused | holds |
|---|---|---|---|
| cold random | 992 → 992 | 0 / 0 | seed 431 moved class (below); every other outcome line equal |
| bundled random | 992 → 992 | 0 / 0 | wall deadline: 47 and 59 as in unit 4's lane, 118 new, 12 gone; every other outcome line equal |
| bundled defaults | 1000 → 1000 | 0 / 0 | none; every counter identical |

- **Junction lines:** 33/33 identical with unit 4's lane, floats included; 33/33 counters identical. `MIXED_GAS_COST` newtonSolves 313 → 313, allocated 324.2 → 318.7 MB (P-07).

## The wall-deadline holds

They are class F, the one input of a campaign that follows the machine. Each was replayed alone once, on one thread.

| seed | in the lane | alone | unit 4's lane |
|---|---|---|---|
| bundled 47 | held at tick 1507 | clean, end 13499 | held (clean alone, end 13499) |
| bundled 59 | held at tick 1573 | clean, end 13446 | held (clean alone, end 13446) |
| bundled 118 | held at tick 7507 | clean, end 14380 | clean, end 14380 |
| cold 431 | held at tick 3085, wall deadline | held again at 3085 in the first replay; at the same code later, tick 3485 on its crystal hold | held at tick 3485, "Crystal equilibrium did not converge" (class E) |

The bundled random campaign stands at 995, as in unit 4's report. Cold seed 431 holds in every run; what moved is where. Its slice from tick 2985 sits at the edge of the wall budget (the seed is the one the aqueous-solvents batch listed for its wall budget). In the lane and in the replay right after it, the slice ran out of its budget; both ran while five subagents were starting. Because a hold class moved, the main thread ran the seed alone at four commits of unit 5 and on unit 4's code (the project rule against replaying a held seed was set aside for this, as the owner allowed for this task): at `3345deca4`, `81c9e7eae`, `00bd7b1c4` and at the lane's own code `35667abdb` it reaches tick 3485 with unit 4's counters to the last digit (4,302 Newton solves, 39,069 iterations, 1,374 step attempts). So the code solves this seed as unit 4's does, and the wall deadline at 3085 was the machine.

## The machine was slower, the code is not

Every step of this lane took about a fifth more wall time than unit 4's at identical counters. To tell the machine from the code, the bundled campaigns were run on unit 4's code (a scratch worktree at the marker) and on unit 5's, alternating, one run at a time:

| campaign (1000 seeds, 8 threads) | unit 4's lane | unit 4's code today | unit 5's code today |
|---|---|---|---|
| defaults, seed time summed | 53.1 s | 65.7 s, 77.0 s | 67.9 s, 66.8 s |
| random, seed time summed | 243.3 s | 303.8 s, 289.5 s | 301.9 s, 308.2 s |

Unit 4's code is as slow today as unit 5's. Unit 6's subagents were writing during these runs, so the numbers are not a benchmark; they answer one question, and the answer is the machine.

## Printed lines against unit 4's lane

Read with times, hashes and byte counts stripped. New lines: the two still gates (`StillRuntimeTest`), `ColumnSolveTest`'s price departure (144.4 J/mol), `EngineFailureKindTest`'s kinds (404 converged; no `INTERNAL`), `PhaseTraitsTest`'s rules, `V3IdentityTest`'s line (the lane selected the class this time). Moved, each expected:

- `CrystalIslandRuntimeTest`: the certificate prints `replay-1` where it printed a digest's first characters (H-R11).
- `ColumnEjectorTest`, the free vent: steam 3.0080 → 3.0035 kg/s, heat 7.641 → 7.629 MW, condenser 51.24 → 51.23 MW (P3-03: the vent's small liquid share no longer counts as gas; 0.15 % of the steam).
- `PassiveStepSolverTest`'s bytes a step: 487,155 → 487,270 (two tanks), 392,561 → 390,966 (a heat side).
- `MixedGasJunctionTransientTest`: 24 lines differ in `bytes` only.
- `FluidCheckpointStoreTest`: the stack traces of its expected failures (line numbers).

## What stands

Every gate above stands for the code of `54df44fb7`. Not covered by any gate: the client changes (the device screen's pages and door, the screens' input routing and name entry, the client watch, the polygon bake, a tank's Accessory tab); a refitted safety valve; a batch still in a played world; the items listed for units 1 to 4.
