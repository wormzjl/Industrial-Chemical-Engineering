**Not clean: 0 failures, 0 holds, 4 unannounced moved results** (3 printed physics values and 1 printed grade label; no asserted reference among them).

# The final lane of the supercritical batch

## Summary

- Branch `claude/supercritical-fluid-support-d0ffa4`. The lane ran once at `a599419dd`: `bash tools/merge-lane/lane.sh head`, no `--all`, exit 0. Its output is in `tmp/merge-lane/head/` (git-ignored).
- Engine digest, before the lane: `EngineDigestTest` ran once alone in record mode. The recording has the same 429 requests in the same order. One line differs, line 313, and only in its grade token. It was copied over the reference in `a599419dd`, with both lines in the commit message. Old: `tjl20_methane_nitrogen tp 350.0 K 1.2E7 Pa CH4 fluid | TP CONVERGED SUPERCRITICAL_FLUID grade=EXTRAPOLATED | ...`. New: the same line with `grade=ESTIMATED_DECLARED_ERROR`, every other byte equal.
- Selection: 303 files changed since main `ab6298d03`; 12 test classes had no load set. `test` 411 of 504 classes selected, but the filter list was too long, so the task ran whole (504). `holdGates` ALL (10 of 10), `columnGates` 82 of 89, `equipmentGates` 25 of 31. Chain-100 was due (`V3PengRobinsonThermo` changed). The GameTests were due (`DenseFluidGameTests` changed). The campaign was due (fluid network inputs changed).
- Tests: 3,938 tests in 624 classes, 0 failed, 0 errors, 0 skipped. Per task: `test` 504 classes and 3,084 tests, `holdGates` 14 and 136, `columnGates` 81 and 502, `equipmentGates` 25 and 216. The items S8 and the merge review left for this lane passed: `EngineDigestTest` (on the new reference), `LanguageFilesTest`, `UnitTextTest`, `SizingPageTest`, `EquipmentLayoutGoldenTest`. The lane saved 620 load sets (0 not passed).
- Chain-100: exact (maximum deviation 0 in moles, temperature, phase fraction and flow).
- GameTests: 114 of 114 passed. `icheme_fluid_test` 110 of 110 (the baseline also has 110). `icheme_dense_test` 4 of 4 (two nozzles, letdown, plunger, loop).
- Junction lines: 33 of 33 identical, counters included (`compare.mjs`). `MIXED_GAS_COST` 313 Newton solves in base and head.
- Campaign: 1000 seeds, factor 4 on both sides, 8 workers, refusals 0 everywhere. The baseline is the 0.33.0 lane at `953635400`, an ancestor of `main` (`pipe-fittings-7f3f4b/tmp/merge-lane/head/`, read only).
  - cold random: 992 → 993. No new hold. Seed 6 (F) fixed.
  - bundled random: 995 → 993. Two new F holds (wall deadline), 118 and 678, were replayed once together on one thread with no other test JVM running. Both are clean (`replay-bundled-118-678-report.txt`), so 995 after the replay.
  - bundled defaults: 1000 → 1000.
  - No class moved on any run.
- Newton counters (not like for like where a hold was fixed or added):
  - cold: solves 326,331 → 335,137, iterations 2,023,231 → 2,096,830, implicit solves 198,805 → 201,757;
  - bundled: solves 134,419 → 133,924, iterations 673,097 → 675,696, implicit solves 94,381 → 93,995;
  - defaults: identical (27,002, 65,433, 22,527).
- Announced moves seen:
  - the digest's line 313 (re-recorded above);
  - S1: the digest summary's `band` 29 → 27, `PhaseTraitsTest`'s two new rule counts, and `G3F5CriticalRegionPathTest`'s D14 corner, now outside the band (in band 81 → 47 per path);
  - S3: `V3IdentityTest` limit 16,384 → 21,000, and the `FluidFactsCompatTest` corpus islands restated on load (energy booked);
  - S0: `HelmholtzReferenceParityTest`'s wider grid (83 → 233 nitrogen states, all within tolerance);
  - S8: checkpoint state format 51 (record byte sizes), 30 page films, and the sample sheet's size.
  - S3's three grade pins moved to 36 MPa and the Tia Juana feed at 901 K pass in their tests. Neither has a printed line in the baseline (`ColumnFeedOnePhaseOracleTest` is new).
- Unannounced moves. Each test passes, and no asserted value moved. The main thread or the owner decides:
  1. `WaterIntoCryogenicMethaneTest` WP4 gate (c), interval 6 only:
     - water 1.3208e+03 → 1.3163e+03 mol (−0.34 %);
     - ice 18.452169 → 18.525634 kg (+0.073 kg, about 4.1 mol, which equals the water's loss within print rounding);
     - 8 → 7 substeps, 11 → 10 rejected.
     T, P and methane are unchanged. Intervals 1 to 5 are identical.
  2. `HoldClassWorldsTest` MELT_GATE seed 778: flow 7.973097600898987E-4 → 7.973097617461125E-4 (2.1e-9 relative).
  3. `PumpBottomPortDrainTest` F1_SLICE: last digits only.
     - condensed 8.517753050284969E-5 → 8.517753050288751E-5 (4e-13 relative);
     - slice 5 tankP 100293.98055282679 → ...676 Pa.
  4. `G4F2CarbonDioxideNitrogenFrostPointHoldoutTest`: five dense-carrier rows (CO2 + N2 at 140 K, 7.1 to 9.1 MPa) change grade `RESEARCH_ONLY` → `ESTIMATED_DECLARED_ERROR`, with every number equal. This is the same kind of grade change as S1's and S3's. No stage review names this test.
- Not causes: the lane shared the machine with one other batch's Gradle test run (the `WARNING: 1` / `2` lines), within the two-lane rule. No dev client ran.
- Side effect: the replay ran in the `test` task and replaced `build/test-results/test/`. The lane's own copy in `tmp/merge-lane/head/results/` is complete.
- Nothing was pushed. `main` is untouched. No version bump.

## Printed lines that differ from the baseline, beyond times, allocations, paths and log noise

| Test | Base | Head | Reason |
|---|---|---|---|
| `WaterIntoCryogenicMethaneTest` interval 6 | water 1.3208e3 mol, ice 18.452169 kg, 8 substeps / 11 rejected | 1.3163e3 mol, 18.525634 kg, 7 / 10 | unannounced (1) |
| `HoldClassWorldsTest` MELT_GATE 778 | flow 7.973097600898987E-4 | 7.973097617461125E-4 | unannounced (2) |
| `PumpBottomPortDrainTest` F1_SLICE | condensed 8.517753050284969E-5 | 8.517753050288751E-5 | unannounced (3), last digits |
| `G4F2CarbonDioxideNitrogenFrostPointHoldoutTest` (5 rows) | `RESEARCH_ONLY` | `ESTIMATED_DECLARED_ERROR` | unannounced (4), label only |
| `EngineDigestTest` summary | band=29 | band=27 | S1 (2 digest lines) |
| `PhaseTraitsTest` | 9 rule counts | + one component subcritical 81, supercritical 14 | S1 phase traits |
| `G3F5CriticalRegionPathTest` | in band 81, D14 corner in band | in band 47, outside the band | S1 band boxes 3 and 4 removed |
| `HelmholtzReferenceParityTest` | 83 N2 states | 233 states, within tolerance | S0 parity grid |
| `V3IdentityTest` | of 16384 | of 21000 | S3 |
| `FluidFactsCompatTest` | state format 50 | 51; 12 vessels restated, energy_delta −2.897 MJ | S3 revisions, S8 format 51 |
| `FluidCheckpointFormatTest`, `FluidCheckpointStoreTest` | core 257,693 / 1,482,319 bytes | 276,261 / 1,500,887 | S8 format 51 |
| `CrudeUnitSampleTest$SimulatorRunTest` | sheet 13,690, packed 23,066 bytes | 14,547, 23,186 | the sheet's new fields (S4, S8) |
| `PageFilmsTest` | 29 films | 30 | S8 plunger page |
| `UpLookingLuParityTest`, `PassiveStepSolverTest` | 579; 146,894 / 140,739 bytes | 585; 147,548 / 141,248 | allocation per step, no counter moved |

New classes with printed lines: `PlungerPumpMoverTest`, `MixtureViscosityTest`, `ColumnFeedOnePhaseOracleTest`, `G3F11SupercriticalRegionTest`. Normalized diff: `tmp/supercritical-lane/printed-diff.txt` (script `printed-diff.js`).

## Campaign

| run | clean base → head | after replay | new holds | fixed | class moved |
|---|---|---|---|---|---|
| cold random | 992 → 993 | 993 | none | 6 F | 0 |
| bundled random | 995 → 993 | 995 | 118 F, 678 F (clean on replay) | none | 0 |
| bundled defaults | 1000 → 1000 | 1000 | none | none | 0 |

Head F holds in bundled random by the classifier: 118, 579, 678, 941. Seeds 579 and 941 are also in the baseline.
