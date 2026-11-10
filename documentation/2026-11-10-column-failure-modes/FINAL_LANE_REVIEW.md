# Final lane review: column failure modes

2026-11-10. Branch `claude/distillation-failure-mode-control-db2da5`, head `1d872d2f9` (main 0.25.0 merged in), clean tree. One run of `bash tools/merge-lane/lane.sh head`, 23:02 to 23:15, alone on the machine (one idle Gradle daemon). Exit 0. Sub-thread on Opus medium; no code changed.

## Selection as it ran

- `test` 231 of 374 classes selected. The filter list was too long for the command line, so the lane ran `test` whole (374 classes). `holdGates` 12 of 12, `columnGates` 11 of 82, `equipmentGates` 26 of 28: 423 classes in all.
- chain-100 (`PassiveStepSolver.java` changed), the fluid GameTests (`AssemblyGameTests.java` changed), the campaign (its inputs changed since main).
- `build.gradle` changed: no class was selected for it (owner rule of 2026-11-10).
- 423 load sets and 1 regression load set saved to the load map at `1d872d2f98`.

## Results

| Step | Head | Baseline (0.25.0 lane, `7a7a1373c`) |
|---|---|---|
| Tests (four tasks) | 2771, 0 failures, 0 errors, 0 skipped | 11 failures in 3088 (fixed on main afterwards, `c6dc5426c`) |
| chain-100 | exact: deviation 0 in moles, T, phase fraction, flow | exact |
| Fluid GameTests | 88 of 88 passed (this batch adds one) | 87 of 87 |
| Campaign cold random | 991 / 1000 clean, 0 refused | 991 |
| Campaign bundled random | 990 / 1000 clean, 0 refused | 989 |
| Campaign bundled defaults | 1000 / 1000 clean, 0 refused | 1000 |

Watch list, all passed: `FluidFactsCompatTest` 19/19 (schema 21 image), `LanguageFilesTest` 2/2, `UnitTextTest` 1/1, `PassiveStepSolverTest` 6/6, `EquipmentLayoutGoldenTest` 1/1, the `column-operation` GameTest batch, which is among the 88.

## Comparison with the baseline (`compare.mjs`)

- Cold random: no new holds, none fixed, no class moved. Newton solves 298,941 to 278,512 (-7 %). Holds: B 629; E1 85, 988; E5 752, 803; E6 101, 206, 446; F 118 (all in the baseline).
- Bundled random: seed **575 F fixed** (by main's fix `7fb9c03cb`, which came after the baseline lane). Seed 514 moved from class X to E9 and still holds (the known remnant-underflow seed). No new holds. Newton solves 326,438 to 167,407 (575 no longer runs to the wall). Holds: E10 430, 579; E5 200, 696, 896; E6 241, 431; E8 698; E9 514; F 941 (in the baseline).
- Bundled defaults: no change. Newton solves 35,531 to 31,897.
- New budget-class (C, F) holds: none in any campaign. Nothing was replayed.
- Junction lines: 33 against 33. 16 are identical with their floats. The others differ only in round-off-sized floats (worstMoles about 1e-16 to 1e-15; one junctionShare in the 13th digit). Integer counters are identical on 24 of 33. Lines 23 to 26 and 29 to 32 take 1 to 3 more Newton solves.
- `MIXED_GAS_COST`: newtonSolves 297 to 313 (+5 %), 322 to 311 ms, 160.1 to 174.9 MB allocated. The baseline lane predates main's lane fixes (`c6dc5426c`, `7fb9c03cb`, which touch `PassiveStepSolver`). This lane cannot say which side moved the counter: this batch's heating branch, or main's closed-device rule. The tests pass.

## Failures

None.

## Not run and why

- `columnGates` 71 classes and `equipmentGates` 2 classes: nothing they load changed since their recorded pass. Those passes stand.
- `base`: not run. The 0.25.0 lane archive is the baseline.
- No budget-hold replay: there was no new C or F hold.

## Where the output is

- Lane folder: `tmp/merge-lane/head/` in this worktree, with `console.txt` and `compare.txt` beside it. The baseline is extracted in `tmp/merge-lane/base-0.25.0/`.
- Archive, for the next baseline: `tools/merge-lane/logs/column-failure-modes-final-lane-logs-2026-11-10.tar.gz` in the main checkout. It is local and git-ignored.
