# Final lane review: time factor, drum operation holds, gas blanketing (claude/gas-vent-hold)

2026-11-11. One run of `LANE_OUT=tmp/final-lane bash tools/merge-lane/lane.sh head`, exit 0, at its defaults (`LANE_WORKERS` 8, `LANE_FORKS` 4: it started when no other run was active, before the owner allowed two). The lane ran on `5e5fabf7b`. The commits `fd0785ac5` and `a99adbe41` (testing rules; `AGENTS.md`, `tools/merge-lane/README.md`, this README) landed above it during the test step and change no code. Its two failures were fixed after it in `6a40f319c` (owner: "Approve both"). Sub-thread on Opus high.

## Step 1: the cleanup commit `f938f923e`

- `compileFluidGameTestJava` with the level-loop init script: passes.
- `run.sh 4`, blanketed: `level_loop start ... cycles=2 blanket=true direct=false`; result `failure=null`, `levelAtStep` and `levelAtEnd` 50.00 %, 121.3 to 148.6 kPa, level 24.9 to 82.1 %, residual 3.6e-11 of 1.6e7 mol. The peak equals the 148.6 kPa that GAS_BLANKET_REVIEW.md records for the other ramp phase, not its 171.8 kPa (vent lag, open item 2). All 9 required tests pass.

## Selection

`test` 296 of 383 classes selected; the filter list was too long, so `test` ran whole (383). `holdGates` 12/12, `columnGates` 64/82, `equipmentGates` 26/28: 485 classes in all. chain-100 (`FlowControl.java` changed), the fluid GameTests (`FluidCheckpointGameTests.java` changed) and the campaign (its inputs changed) were due. The campaign runs at time factor 1, the harnesses' `-PholdFuzzTimeScale` default. (Since [valve follow-ups](../2026-11-11-valve-followups/VALVE_FOLLOWUPS_REVIEW.md) item 5 the default is 4; the next lane compares against the factor-4 campaign.)

## Results against the 0.26.0 lane (`1d872d2f9`, `tmp/merge-lane/head`)

| Step | Head | Baseline |
|---|---|---|
| Tests (four tasks) | 3179, **2 failures**, 0 errors, 0 skipped | 2771, 0 failures |
| chain-100 | exact (deviation 0 in moles, T, phase fraction, flow) | exact |
| Fluid GameTests | 93 of 93 (+5: `level-loop`, `level-loop-blanket`, three `psv-flare*`) | 88 of 88 |
| Campaign cold random | 991 clean, 0 refused; Newton solves 278,449, iterations 1,608,622 | 991; 278,512; 1,609,077 |
| Campaign bundled random | 990 clean, 0 refused; 166,169; 865,520 | 990; 167,407; 878,057 |
| Campaign bundled defaults | 1000 clean; 31,897; 64,037 | 1000; 31,897; 64,037 |
| Junction lines | 33 of 33 identical, floats included | 33 |
| `MIXED_GAS_COST` | newtonSolves 313, 175.1 MB | 313, 174.9 MB |

- Holds (`compare.mjs`): no new holds, none fixed, no class moved in any campaign. No new budget-class (C, F) hold, so nothing was replayed.
- Watch list, all passed: `FlareRuntimeTest` 5/5 (the D6 expectations), `FluidFactsCompatTest` 20/20 (schema 22 and 23 images), `AccessoryStoreCompatTest` 8/8, `LanguageFilesTest` 2/2, `UnitTextTest` 1/1 (translation commit `e943bf588`), `EquipmentLayoutGoldenTest` 1/1, `PassiveStepSolverTest` 6/6.

## What moved and why

- The campaign at factor 1 runs the old engine (TIME_SCALE_BUILD_REVIEW.md, section 1). The only change is fewer Newton work units in bundled random (-0.7 % solves, -1.4 % iterations) and in cold (-0.02 %). Clean counts and holds are equal. This fits the drum batch's factor-1 rules (`BalanceNet.rest`, the starved regulating valve), whose quick checks gave the same holds. The factor-4 campaign (TIME_SCALE_BUILD_REVIEW.md, section 3) is that batch's measure and was not repeated here.
- Junction lines and `MIXED_GAS_COST` counters: unchanged, as W1, W2 and the drum batch recorded. Under the rule of `a99adbe41`, this batch is judged by counters and no times are reported.

## Failures, fixed after the lane in `6a40f319c` (owner: "Approve both")

1. `ShapeRasterizerTest.aGasBlanketingValvesSupplyPipeStandsClearOfTheDrumAndItsNozzlePipes`, line 463: "the outlet points to the side", face y 0 expected, 1 found. The PSV commit `09e106041` put its two outlet assertions (to the side; the face asked for, on `ReliefValveGeometry`) in the blanketing valve's test, whose inlet faces up by design (`9777378a4`). Fix (a): both moved into `aSafetyValvesOutletPipeStandsClear...`; the blanketing valve's own assertions are unchanged.
2. `DeterministicMathSourceTest.fluidSourcesCallNoNonCorrectlyRoundedMathFunction`: `Math.hypot` at `EquipmentInstruments.java:218` and `:221` (PSV commit `439a22868`, F2's next free roof cell). Fix (a): squared distances (`reach < 0 ||` keeps a negative reach's old answer). The F1 and F2 reservations of 8768 drum shapes (both orientations, 0.5 to 12 m, tangent 1 to 16 m, four headings, supports 0 to 4; `tmp/final-lane/f2check/`) are identical before and after: no cell choice sits on the edge.
- The PSV branch's own runs missed both (orchestrator's account): its filter ran two `ShapeRasterizerTest` cases, and `DeterministicMathSourceTest` was not among its reached classes.
- Proof, one filtered run (`--max-workers=4`) of the classes that load `EquipmentInstruments` or the edited test (`affected.mjs select` after the edit) plus `DeterministicMathSourceTest`: `test` 7 classes, `holdGates` 6, `equipmentGates` 17, 265 tests, 0 failures. Left out: the 14 hold-repro and fuzz harness classes the selection lists only for having no load set. The fix touches no solver code; chain-100, the GameTests and the campaign do not load it.

## Overlap and what the lane did not cover

- Overlap: during the test step, another session's compile at 22:27 and its MiniRunner tests 22:30 to 22:33; during the campaign, a Gradle worker daemon of unknown owner 22:35 to 22:39, possibly the lane's own. Counters judge both. The campaign counters equal the baseline's, so the overlap moved nothing there.
- `columnGates` 18 classes and `equipmentGates` 2 classes did not run, because nothing they load changed since their recorded pass. Those passes stand. Not run: `base` (the 0.26.0 lane is the baseline), the campaign at factor 4, an in-game check.
- Output: `tmp/final-lane/` in this worktree (`head/`, `console.txt`, `compare.txt`, step-1 logs). Archive: `tools/merge-lane/logs/gas-blanketing-final-lane-logs-2026-11-11.tar.gz` in the main checkout (local, git-ignored). The lane saved the load sets of its passing classes to the load map. The fix run recorded none, so its 30 classes run in the next lane.
