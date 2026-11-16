**Not clean: 2 failures, 0 holds (4 new wall-deadline holds, all clean on their one replay), 8 unannounced moved results** (5 printed results, 1 of them a column sheet that now fails, and 3 allocation counters).

# The final lane of the supercritical follow-ups

## Summary

- Branch `claude/supercritical-fluid-support-d0ffa4`, prepared commit `9b1dd2bd6` (merge preparation done). The lane ran once there: `bash tools/merge-lane/lane.sh head`, no `--all`, exit 0. Output in `tmp/merge-lane/head/` (git-ignored); the console, the comparison and the printed-line diffs in `tmp/followups-lane/`.
- Baseline: the 0.34.0 lane at `a599419dd` (`review-rust-a1-math-5ced47/tmp/merge-lane/head/`, read only), compared with `tools/merge-lane/compare.mjs`.
- Selection (since main `adb413b4d`: 95 files changed, 3 test classes with no load set): `test` 378 of 506 classes, but the filter list was too long, so the task ran whole (506); `holdGates` ALL (10 of 10); `columnGates` 81 of 90; `equipmentGates` 22 of 31; `fluidSolverRegression` ALL. Chain-100 due (`V3ThermoModel` reads the new `feed-flash-stability-states.tsv`). GameTests due (`DenseFluidGameTests` changed). Campaign due (fluid network inputs changed). `build.gradle` changed: no class selected for it (rule of 2026-11-10).
- Tests: 3,964 tests, 2 failed, 0 errors, 0 skipped. Per task: `test` 506 result files, 3,106 tests, 2 failed; `holdGates` 14 and 136; `columnGates` 80 and 516; `equipmentGates` 22 and 206. The lane saved 616 load sets (2 not passed) and 1 for chain-100.
- The two failures (both in `test`):
  1. `DeterministicMathSourceTest.fluidSourcesCallNoNonCorrectlyRoundedMathFunction`: "use DeterministicMath (bitwise identical on every JVM) instead of: `PlungerPump.java:97: Math.log1p(`, `PlungerPump.java:102: Math.expm1(`, `PlungerPump.java:117: Math.expm1(`". P1's new law.
  2. `ColumnWaterTest.aLiquidThatWouldSettleIsANote` (line 97): expected `ColumnSolve.Solved`, got `Failed[reason=INITIALIZATION_FAILURE: V3 initialization failed for 4 trays: V3 feed flash lost its two-phase Rachford-Rice root; the feed is unstable and the equilibrium engine gives no split, transientFailure=false]`. P3's flash refuses a wet unstable feed (the engine path takes no feed with water).
- `EngineDigestTest` passed on the reference as it is (1 test, 0 failures). `LanguageFilesTest` and `UnitTextTest` were selected (they read `en_us.json`) and passed.
- Chain-100: exact (maximum deviation 0 in moles, temperature, phase fraction and flow).
- GameTests: 114 of 114 passed. `icheme_fluid_test` 110 of 110 (baseline 110), `icheme_dense_test` 4 of 4 (two nozzles, letdown, plunger, loop).
- Junction lines: 33 of 33 identical, floats and counters included. `MIXED_GAS_COST` 313 Newton solves in base and head.
- Campaign: 1000 seeds, factor 4 both sides, 8 workers, 0 refusals everywhere.
  - cold random: clean 993 → 992. New hold seed 6 (F, wall deadline). No fixed hold. No class moved.
  - bundled random: clean 993 → 991. New holds 47, 59, 707 (F, wall deadline); fixed 118 (F). No class moved.
  - bundled defaults: 1000 → 1000, counters identical.
  - The four new F holds were replayed once, alone (no other test JVM on the machine), one thread: cold seed 6 in one run, bundled 47, 59, 707 batched in one run (one run per harness). All four clean (`replay-cold-6-report.txt`, `replay-bundled-47-59-707-report.txt`). After the replay: cold 993 (as the baseline), bundled random 994 (baseline 993).
  - The lane shared the machine with one other batch's Gradle test run during `test`, chain-100, the GameTests and the cold campaign (the lane's `WARNING: 1` lines).
- Newton counters (not like for like where a hold was added or fixed):
  - cold: solves 335,137 → 334,562, iterations 2,096,830 → 2,093,136, implicit solves 201,757 → 201,385;
  - bundled: solves 133,924 → 131,480, iterations 675,696 → 646,370, implicit solves 93,995 → 92,878;
  - defaults: identical (27,002, 65,433, 22,527).
- Announced moves, seen:
  - P1: the S8 probe's `PLUNGER_RUN`/`PLUNGER_GAS` lines (table 3), the new `PLUNGER_EXPONENT` and `PLUNGER_REFERENCE` lines (as in P1 sections 1 and 2), `FlowsheetUnitsTest PLUNGER_SHEET` (new), and the dense GameTest's plunger case (flow 9.98559 → 9.39154 m³/h, expected 9.98080 → 9.38621, shaft 37,879.8 → 27,490.9 W, passed).
  - P2: `DeviceMetersTest` passes (no printed line); the dense loop's heat 5.709328742601837E8 → 5.704052162969123E8 J (−0.09 %), circuit 147.22827 → 147.22836 s, closures still at 1e-16, passed.
  - P3: `V3FeedFlashStabilityTest` is new (no baseline lines; the 181, 102 and 721 pass in it); `ColumnFeedOnePhaseOracleTest` gains one line (CO2/CH4 90/10 at 250 K, 2 MPa, VAPOR, 6 it); its other 13 lines differ only in the order the composition map prints, every value equal. The two 900 K crude feeds pass in `V3PengRobinsonThermoTest` (no printed line).
  - P4: `MoverSoundsTest` passes; the GameTests load the new registration and pass.
- Unannounced moves (table 4), for the main thread or the owner:
  1. `FlowsheetAqueousColumnTest` W5 sheet ethanol, water, hexane: `STEADY` with two warnings (`column.two_liquids`, `column.two_liquids_distillate`) → `FAILED`, log `column.failed`: the same feed-flash refusal as failure 2. The test still passes.
  2. `FlowsheetAqueousColumnTest` W5 sheet wash: distillate 0.8075 ethanol at 10.495 → 10.496 mol/s.
  3. `FlowsheetAqueousColumnTest` W6 example: spirit 0.80747 ethanol at 10.4945 → 10.4963 mol/s.
  4. `ColumnWaterTest` W5 running wash column: ethanol to distillate 0.8474 → 0.8475.
  5. `CrudeUnitSampleTest$SimulatorRunTest` SAMPLE_BODY: packed 23,186 → 23,187 bytes (sheet and results unchanged).
  6. to 8. Allocation counters: `UpLookingLuParityTest` P-17 585 → 581 bytes a factorization; `PassiveStepSolverTest` S1 two tanks 147,548 → 147,532 bytes a step, heat side 141,248 → 141,305 bytes a step (workspaces and LU factorizations a step equal).
- Printed lines were compared per class on the classes both lanes ran, after removing times, allocation `bytes=` fields and object hashes; whatever else differs is listed above.

## 1. Selection and steps

| Task or step | Selected | Ran | Why |
|---|---|---|---|
| `test` | 378 of 506 | whole (506): filter list too long | lang, `FluidThermodynamics`, `V3ThermoModel`'s new resource, `UnitType`, client GUI sources |
| `holdGates` | ALL (10) | 10 (14 result files) | |
| `columnGates` | 81 of 90 | 81 (80 result files) | |
| `equipmentGates` | 22 of 31 | 22 | |
| chain-100 | due | ran | loads `V3ThermoModel`, which reads `feed-flash-stability-states.tsv` |
| GameTests | due | ran | `DenseFluidGameTests.java` changed |
| campaign | due | ran | campaign inputs changed since main |

## 2. Test results

| Task | Result files | Tests | Failed | Errors | Skipped |
|---|---|---|---|---|---|
| `test` | 506 | 3,106 | 2 | 0 | 0 |
| `holdGates` | 14 | 136 | 0 | 0 | 0 |
| `columnGates` | 80 | 516 | 0 | 0 | 0 |
| `equipmentGates` | 22 | 206 | 0 | 0 | 0 |
| total | 622 | 3,964 | 2 | 0 | 0 |
| chain-100 | 1 | 1 | 0 (exact) | 0 | 0 |
| GameTests | 114 | 114 | 0 | | |

## 3. Announced moves, P1's probe (`PlungerPumpMoverTest`, CO2 at 310 K)

| δ | flow m³/h, base → head | share, base → head | work J, base → head |
|---|---|---|---|
| 0.30 | 29.95138 → 26.30563 | 0.998379 → 0.876854 | |
| 0.45 | 29.95137 → 27.37714 | 0.998379 → 0.912571 | (ρ 186.6 → 186.7) |
| 0.60 | 29.95135 → 28.04983 | 0.998378 → 0.934994 | 83,277.0 → 59,514.0 |
| 0.80 | 29.95134 → 28.67119 | 0.998378 → 0.955706 | 83,302.9 → 65,521.1 |
| 1.00 | 29.95132 → 29.09113 | 0.998377 → 0.969704 | 83,328.6 → 70,362.0 |
| 1.60 | 29.95128 → 29.67308 | 0.998376 → 0.989103 | 83,405.2 → 78,692.4 |

These equal P1_PLUNGER_REVIEW.md section 3.

## 4. Unannounced moved results

| # | Test, line | Base | Head |
|---|---|---|---|
| 1 | `FlowsheetAqueousColumnTest` W5 sheet ethanol, water, hexane | STEADY; notes `column.two_liquids` [8], `column.two_liquids_distillate` | FAILED; log ERROR `column.failed`: "V3 initialization failed for 4 trays: V3 feed flash lost its two-phase Rachford-Rice root; the feed is unstable and the equilibrium engine gives no split" |
| 2 | `FlowsheetAqueousColumnTest` W5 sheet wash, distillate | 10.495 mol/s | 10.496 mol/s |
| 3 | `FlowsheetAqueousColumnTest` W6 example, spirit | 10.4945 mol/s | 10.4963 mol/s |
| 4 | `ColumnWaterTest` W5 running wash, ethanol to distillate | 0.8474 | 0.8475 |
| 5 | `CrudeUnitSampleTest$SimulatorRunTest` SAMPLE_BODY packed | 23,186 | 23,187 |
| 6 | `UpLookingLuParityTest` P-17, bytes a factorization | 585 | 581 |
| 7 | `PassiveStepSolverTest` S1 two tanks, bytes a step | 147,548 | 147,532 |
| 8 | `PassiveStepSolverTest` S1 heat side, bytes a step | 141,248 | 141,305 |

## 5. Campaign

| Run | Clean base → head | After replay | New holds | Fixed | Class moved | Refused |
|---|---|---|---|---|---|---|
| cold random | 993 → 992 | 993 | 6 F (clean on replay) | none | 0 | 0 |
| bundled random | 993 → 991 | 994 | 47 F, 59 F, 707 F (clean on replay) | 118 F | 0 | 0 |
| bundled defaults | 1000 → 1000 | | none | none | 0 | 0 |

| Run | Newton solves | Newton iterations | Implicit solves |
|---|---|---|---|
| cold random | 335,137 → 334,562 | 2,096,830 → 2,093,136 | 201,757 → 201,385 |
| bundled random | 133,924 → 131,480 | 675,696 → 646,370 | 93,995 → 92,878 |
| bundled defaults | 27,002 → 27,002 | 65,433 → 65,433 | 22,527 → 22,527 |

| Replay | Seeds | Clean | Newton solves | Newton iterations |
|---|---|---|---|---|
| cold, 1 thread, alone | 6 | 1 of 1 | 687 | 4,364 |
| bundled, 1 thread, alone | 47, 59, 707 | 3 of 3 | 3,465 | 34,510 |

## 6. GameTests

| Namespace | Base | Head |
|---|---|---|
| `icheme_fluid_test` | 110 of 110 | 110 of 110 |
| `icheme_dense_test` | 4 of 4 | 4 of 4 |
| dense plunger: flow / expected / shaft | 9.98559 m³/h / 9.98080 / 37,879.8 W | 9.39154 m³/h / 9.38621 / 27,490.9 W |
| dense loop: heat / worst component closure | 5.709328742601837E8 J / 3.68e-16 | 5.704052162969123E8 J / 5.47e-16 |
