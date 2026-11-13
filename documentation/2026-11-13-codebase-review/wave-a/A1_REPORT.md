# Wave A1 (column): report

Branch `claude/review-wave-a1-column-b4eca7`, from `ba35da565`. Seven commits, five findings. All named tests pass. Parent decisions: C4 option A (V3's curve and pressure), F1 option A (four more files in the lane).

## Findings

| Id | Commits | Files | Tests run (task), result |
|---|---|---|---|
| C4 | 571187fac, b1f1993ac | `operation/ColumnSolve.java` | ColumnSolveTest 11/11 (`test`): two new cases. ColumnWaterTest 4, ColumnRunTest 50, ColumnProbeTest 2 (`test`), FlowsheetColumnTest 10 (`holdGates`): pass |
| F1 | bf63df17c | `v3/thermo/V3CatalogPropertyPackage`, `V3PengRobinsonThermo`; `v3/V3NeuralRegistry`, `V3AnchorTransformerInitializer`, `V3ColumnResult`; `flowsheet/Report` | V3IdentityTest 2 (new), V3PengRobinsonThermoTest 5, V3NeuralRegistryTest 12, V3NeuralInitializationTest 8, V3LearnedRecoveryTest 7, V3WaterPhaseQualificationTest 3 (`columnGates`); SheetCompoundsTest 7, SimulatorRunnerTest 28 (`test`): pass |
| F3 | 1934b839d | `v3/thermo/V3WaterProperties`, `v3/V3AcceptanceAuditor` (advisory) | V3WaterPropertyDerivativesTest 10 (two new cases), V3FreeWaterTrayTest 11, V3SteamFeedContractTest 9 (`columnGates`): pass |
| D4 | 09189202b | `v3/V3AcceptanceAuditor` | V3AcceptanceAuditorLimitsTest 1 (new), V3ConvergenceClosureTest 16, V3SideDrawAuditTest 4, V3ColumnCalculatorTest 13, V3BoundedSolvePathTest 5, V3CondenserPhaseAuditTest 8, V3AqueousColumnTest 4 (`columnGates`): pass |
| D2 | 36cd72e89, dd67c8d25 | `v3/V3ColumnCalculator` | V3TrayHydraulicsColumnTest 9 (`columnGates`; two new cases): pass at the final budget |

One compile (`compileJava compileTestJava`). One `columnGates` run of the 17 column classes. One `test` run of the six classes. One `holdGates` run. Then three single-class reruns: V3TrayHydraulicsColumnTest twice (the measurement after adding the templates, then after setting the budget, which is production code); ColumnSolveTest once (its new case looked for the steam row in the wrong place, a test fault: V3 orders steam rows by stage). No times recorded.

## C4: feed water and steam

- The defect is wider than the review said: the comparison was reversed (`psat(t-5) < p`). A hot wet feed's water followed the distillate. A cold one's was handed to V3 as steam, which refuses it, so the whole solve failed. `steamAtFeedTray` now applies V3's own admission, so the two cannot disagree at the border (4 K above saturation is no steam feed, 6 K is one, and V3 agrees both times): Wagner-Pruss `Tsat`, the resolver's injection pressure `top + max(0, min(feed, stages) - 1) * drop`, T inside 273.16 to 900 K. When nothing saturates (above Pc, below the triple-point pressure), there is no steam feed.
- How common: `onAxis` is false unless a polar compound with a homomorph holds more than 0.1 % of the feed (`ColumnWater.SHARE`). So every wet crude feed takes this branch. Every such feed whose water is at least 5 K superheated at the feed tray (any crude column feed) now enters its water as a steam feed at the feed tray, not into the distillate.
- No existing test expectation moved: the preset feeds in ColumnSolveTest hold no water, and the other column runs above passed unchanged. Not built: a full solve of a 300 K / 200 kPa wet feed, because no preset column solves on a 300 K feed. The note branch is covered by the decision cases and the border check against V3's validation.
- Left as it is: the hydraulic correction re-resolves on its marched profile. Steam that is marginal there falls back with the existing warning (as before).

## F1: column identity

- `datasetRevision()` before: `<scientificRevision>`. After: `<scientificRevision>;derived-pairs=derived-pairs-r7;records=<tokens>` (a lens keeps the bare string). This applies to every non-lens package, so every saved column seed and every digest rebuilds once (state). Two shipped packages are column packages. The longest identity is 3,482 of the 16,384 characters allowed (`icheme:tjl20_methane_nitrogen`).
- Moved test expectations: V3PengRobinsonThermoTest (`datasetRevision` == scientificRevision becomes == `columnIdentity`, starting with it); V3NeuralRegistryTest (the NETWORK and MAIN `propertyRevision`, and the `isCurrent` helper); V3WaterPhaseQualificationTest (its seed stamp). Added: the `projected==original` seed stamp (registry test) and `Report.Warm.current` (true on the catalog it was captured on, false after a group-count revision bump on the same scientificRevision; SheetCompoundsTest). No test pins a digest made from a column identity (the two pinned hex digests use fixed test revisions).
- No route or event changed: the learned-route tests (`initializer=LNN` in V3NeuralInitializationTest, V3LearnedRecoveryTest) pass, so none fell to CURRENT_BACKUP. A second derivation on a catalog runs no fit (`fitsRun() == 0`, asserted for every shipped column package).

## F3: callers that caught the throws as a range test

- Kept as explicit tests (capability limits): the resolver's steam feed range (169-171) and condenser below the triple point (198); `V3WetTraySet.saturation` (251-252: a wet tray below the triple point or at Tc and above); `V3ColumnCalculator.waterDiagnostic` (2697) and the auditor's dew-point loop (below 640 K).
- Continued now: the auditor's dew ratio (404-410) and its dew-point text (464) evaluate where they used to report the largest violation. The evaluator's steam rows and the wet-row residual (`V3MeshResidualEvaluator` 357-394, 550, 566) were rejected trial steps and now evaluate; inside the data range the results are bit for bit the same. The derivative `dLogSaturationPressureDT` continues with the value.
- Advisory: an accepted state with steam past the data gets one "Warning: the steam on tray N at T K uses water properties continued past their data" in the audit's advisories. No solve test reaches it: the shipped columns stay inside 273-900 K.

## D4 and D2

- D4: no solve that passed before fails `LOCAL_COMPONENT_BALANCE` or `ENERGY_BALANCE` at the closure limit (all column classes above pass), so there is no decision request. No recovery branch reads the failed families of an unconverged attempt (1797 needs a converged attempt; 1813 checks the gates first). Only the diagnostics text that names the first failed family can change there.
- D2 polls, one correction each: medium_crude preset 4,305; tower templates small_crude 1,901, medium_crude 4,305, light_crude 8,363, heavy_crude 11,446, oil_sand_dilbit 6,407; Tia Juana literature preset 21,282 (the largest); 12-tray literature column 1,676. Median of the eight: 5,356 (the test prints the upper middle value, 6,407). Budget `HYDRAULIC_CORRECTION_BUDGET_POLLS` = 8 x 21,282, rounded up = 200,000. Old: a 10 s wall clock.
- A control that sleeps 1 ms at every checkpoint publishes the same pressure profile and solve path as an unbounded one, bit for bit.

## Not done

- The resolver's steam feed range (273.16 to 900 K) could now be relaxed, since the steam continues past it. That needs a file outside the lane and an owner decision.
