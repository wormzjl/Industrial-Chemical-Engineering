**Not clean: 10 failures in 5 classes.** 1 is expected (`LanguageFilesTest`, until the translation commit). 8 are sizing references of `CrudeUnitSampleTest` that WP5's design deposit moves, not re-recorded. 1 is a real rule break (`DeterministicMathSourceTest`: `Math.expm1` in `ColumnWear`). Chain-100 exact, GameTests 119 of 119, junction lines and `MIXED_GAS_COST` identical, campaign clean after one replay.

# The final lane of the fouling and corrosion batch

## Summary

- Branch `claude/fouling-corrosion-system-ba0275` at `96a6fa733` (main 0.34.0 `adb413b4d` merged in), run in worktree `fouling-corrosion-wp6-materials-tab-439886` on branch `claude/fouling-corrosion-final-lane-f2943c`. `bash tools/merge-lane/lane.sh head`, once, no `--all`, lane exit 0. Output in `tmp/merge-lane/head/` (git-ignored); it is the next baseline.
- Baseline: the 0.34.0 lane at `a599419dd` (`review-rust-a1-math-5ced47/tmp/merge-lane/head/`, read only).
- No other test or client JVM ran (no `WARNING` line). No dev client.
- Selection: 194 files changed since main; 13 test classes had no load set. `test` 429 of 517 selected, filter list too long, so the task ran whole (517). `holdGates` ALL (10 of 10), `columnGates` 84 of 89, `equipmentGates` 27 of 31. Chain-100 due (`MaterialCatalog` changed). GameTests due (`IntegrityGameTests` changed). Campaign due. Note: `build.gradle` changed, no class selected for it.
- Tests: 4,109 tests in 641 classes, 10 failed, 0 errors, 0 skipped. Per task: `test` 517 classes and 3,177 tests, `holdGates` 14 and 137, `columnGates` 83 and 517, `equipmentGates` 27 and 278. The lane saved 635 load sets (2 not passed).
- Failed classes:
  1. `LanguageFilesTest.everyTranslationHasTheEnglishKeysAndArguments`: missing keys in es_es and the others (`chat.icheme.repair_kit.not_idle`, `gui.icheme.assembly.operation.clean`, ...). Expected until the translation commit. `UnitTextTest` passed.
  2. `CrudeUnitSampleTest` (a slow gate, in `holdGates`), 8 failures in 3 nested classes. Finding 1.
  3. `DeterministicMathSourceTest.fluidSourcesCallNoNonCorrectlyRoundedMathFunction`: `src/main/java/com/wormzjl/icheme/science/integrity/ColumnWear.java:51: Math.expm1(`. Finding 2.
- Chain-100: exact. Maximum deviation 0 in moles, temperature, phase fraction and flow.
- GameTests: "All 119 required tests passed" (the union's count).
- Junction lines: 33 of 33 identical, floats and counters included (`compare.mjs`). `MIXED_GAS_COST` 313 Newton solves in base and head.
- Campaign: 1000 seeds, factor 4 on both sides, 8 workers, refusals 0 everywhere.
  - cold random: 993 → 992. New hold: seed 6 F (wall deadline). Replayed once alone on one thread: clean (`replay-cold-6-report.txt`). 993 after the replay.
  - bundled random: 993 → 989. New holds: 12, 47, 59, 707, all F (wall deadline). Replayed once together on one thread, alone: all clean (`replay-bundled-12-47-59-707-report.txt`). 993 after the replay. The baseline's 118 and 678 (F, clean on the baseline's replay) are F again in head; they are not new and were not replayed. Like for like, the baseline read 995 after its replay.
  - bundled defaults: 1000 → 1000.
  - No hold fixed, no class moved on any run. No new hold outside the budget class.
- Newton counters, head against base (not like for like where a hold was added):
  - cold: solves 335,137 → 334,547, iterations 2,096,830 → 2,092,859, implicit solves 201,757 → 201,368;
  - bundled: solves 133,924 → 131,120, iterations 675,696 → 644,191, implicit solves 93,995 → 92,584 (four seeds stopped early at their F hold);
  - defaults: identical (27,002, 65,433, 22,527).
- Replay side effect: the replays ran in the `test` task and replaced `build/test-results/test/`. The lane's copy in `tmp/merge-lane/head/results/` is complete.
- Nothing was pushed or merged. No reference or test was edited.

## Findings

| # | Test | Base → head | Bound | Explained by | Needs |
|---|---|---|---|---|---|
| 1 | `CrudeUnitSampleTest` sizing references (8 asserts) | see below | none (sizing picks the least standard size that holds the need) | WP5, plan section on sizing: exchanger, cooler and air-cooler rows size on the service's design `Rf` and on narrowed passages, so a dirty crude service sizes larger | the main thread re-records them with the owner's agreement; the WP5 report's "all sizing references pass" did not include this slow gate |
| 2 | `DeterministicMathSourceTest` | pass → fail | rule: integrity code uses `DeterministicMath` (plan, WP1 package rule) | WP8 `ColumnWear.foul` calls `Math.expm1`. A real defect of the batch | `DeterministicMath.expm1` exists; a one-line change in `ColumnWear.foul`, then that test and `ColumnWearTest` |
| 3 | `LanguageFilesTest` | pass → fail | — | new en_us keys | the translation commit |

Finding 1, the asserted values (expected, from the reference, → head):

- `ColumnSizingSampleTest.theCondenserIsSetByItsShellDrop`: 509 → 765.9 (the condenser's surface need, printed 513.7 → 765.9 m²; the unit stays 2.0 shell, 6 tubes, YELLOW).
- `ColumnSizingSampleTest.eachPumparoundTakesItsCoolerAndPump`: PA 1 cooler 4 → 5 tubes.
- `SizeAllSampleTest.theResiduesPathIsTheProbes`: E-101 0.07 → 0.0641.
- `SizeAllSampleTest.p101sPathIsTheProbesStair`: T-101/pa 1 cooler drop 0.39 → 0.452.
- `UnitSizingSampleTest.e101KeptOneStepDownIsRatedByTheSameRule`: 1.31 → 1.755.
- `UnitSizingSampleTest.e101`: shell 1.25, 6 tubes → shell 1.5, 10 tubes.
- `UnitSizingSampleTest.e102`: 6.0 × 12, bank 1 × 3 → 5.5 × 9, bank 2 × 3 (larger face: 99 against 72 m² a bank row).
- `UnitSizingSampleTest.theProbesBankStillHoldsItsDutyWhenThePlayerKeepsIt`: GREEN → RED (the kept clean-sized bank no longer holds the duty dirty).

## Printed lines that differ from the baseline, beyond times, allocations, paths and log noise

| Test | Base | Head | Reason |
|---|---|---|---|
| `CrudeUnitSampleTest$ColumnSizingSampleTest` | pa 1 / 2 / 3 cooler needs 198.2 / 237.8 / 150.2 m², sizes 1.0×4 / 1.0×4 / 1.0×3; pa 1 / 2 pump head 13.88 / 15.79 m | 289.9 / 347.8 / 269.8 m², 1.0×5 / 1.25×4 / 1.0×5; 14.26 / 14.21 m | finding 1 (WP5) |
| `CrudeUnitSampleTest$SizeAllSampleTest` | pa 1 / 2 / 3 drop 0.386 / 0.413 / 0.300; E-101 tube drop 0.199; P-101 → V-101 lost 3.06; T-101 → RESIDUE lost 0.55 | 0.452 / 0.186 / 0.413; 0.154; 2.97; 0.41 | finding 1 (WP5), resized units |
| `CrudeUnitSampleTest$SimulatorRunTest` | WARM_START checkpoints 31,672, cold 321,343 | 31,956, 322,195; flow and temperature deviation equal | the resized sheet (WP5); test passes |
| `FluidFactsCompatTest` | state format 51; RESTATED vessels 15 | 52; vessels 16 (energy_delta −2,897,239.45 J equal); more lines | announced: checkpoint state format 52, the batch's new corpus image |
| `FluidCheckpointStoreTest` | pack 11,291,926 bytes | 11,291,927 (core 1,500,887 equal) | announced: format 52 |
| `ColumnRunTest` | — | status lines STOPPED, REPAIRED, CLEANED | announced: WP8 repair and Clean |
| `UpLookingLuParityTest`, `PassiveStepSolverTest` | 585; 147,548 bytes | 598; 147,628 | allocation per step, no counter moved |
| `ColumnFeedOnePhaseOracleTest` | `{Nitrogen, Ethane, Propane, Methane}` | `{Ethane, Nitrogen, Methane, Propane}`, every number equal | noise: `Map.of` iteration order is salted per JVM |

Other differing lines are times, object hash codes and the log-file delete errors. Normalized diff: `tmp/printed-diff/printed-diff.txt` (`printed-diff.js` of the supercritical lane).

## Campaign

| run | clean base → head | after replay | new holds | fixed | class moved |
|---|---|---|---|---|---|
| cold random | 993 → 992 | 993 | 6 F (clean on replay) | none | 0 |
| bundled random | 993 → 989 | 993 | 12, 47, 59, 707 F (clean on replay) | none | 0 |
| bundled defaults | 1000 → 1000 | 1000 | none | none | 0 |

Head holds by the classifier. Cold: E5 65, 101, 629, 752, 803, 919; F 6, 431. Bundled random: E6 241; E9 145, 431; F 12, 47, 59, 118, 579, 678, 707, 941.
