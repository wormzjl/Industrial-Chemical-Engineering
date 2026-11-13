# D-C4 The column calculator's ladder, ramp and attempt loop in their own files: the rest of S1 (report)

Brief: [WAVE_D_BRIEFS.md](../WAVE_D_BRIEFS.md), section "D-C4". Review: [COLUMN_REVIEW.md](../COLUMN_REVIEW.md) row S1, section 2. Previous slices: [D-C1_REPORT.md](D-C1_REPORT.md), [D-C2_REPORT.md](D-C2_REPORT.md), [D-C3_REPORT.md](D-C3_REPORT.md).
Subagent (Opus high), 2026-11-13, on `claude/codebase-review-refactor-8a85ef` at `a91f6f9fa`. Written without a compile or a test run.

## Summary

1. **Three steps, a move only.** Commits: ladder `7f793c2c2`, ramp `34409eb11`, attempt loop `7c893c051`, then this document and the code guide. Every moved line is the old line: the only edits in moved code are declaration modifiers (28 lines, listed below) and the qualifier of a call or type in the files that called the calculator by name (`V3LearnedRoute`, `V3HydraulicPublish`, `RungOptions`, seven tests). No call is added, dropped or reordered; no event text, path string, poll site, budget or exception depth changes.
2. **How the parts call each other.** The calculator and the three new files call each other through single static imports (`import static ...V3AttemptLoop.solveSingleProblem;`), so no moved body and no body left in the calculator changed. Between steps only import lines changed. The files that already named `V3ColumnCalculator.x` (`V3LearnedRoute`, `V3HydraulicPublish`) now name `V3AttemptLoop.x`.
3. **New files** in `science/column/v3`, all package-private: `V3ContinuationLadder` (555 lines), `V3FeatureRamp` (711), `V3AttemptLoop` (485), `InitializationFailure` (14), `PathDependentHeatBound` (41), `ContinuationJacobianPolicy` (16), `PhaseCorrection` (12), `CondenserAttempts` (34).
4. **Calculator**: 2,328 lines before; 1,812 after step 1 (536 removed, 20 added); 1,102 after step 2 (715, 5); 626 after step 3 (495, 19). It keeps the entries, the catalog pin, the branch order, `calculateBranch` with its warm start, the acceptance gates, `withPriorSupportNotes`, `diagnostics` and the aliases (table below). It is 26 lines over the brief's 450 to 600: the warm start (`warmStartPass` to `relativeDrift`, 84 lines) stays, because only the façade calls it.
5. **Rule for a member more than one part calls**: the brief's (the group with the most call sites; a tie goes to the group whose review range holds it). A member only the façade and one group call goes with that group. The one member in no range that all four parts call, `withPriorSupportNotes` (façade 3 sites, ladder 4, ramp 4, attempt loop 1), stays in the façade. Members placed by this rule are marked in the tables.
6. **Types.** `InitializationFailure` (thrown by the ladder, caught by `calculateBranch`) and `PathDependentHeatBound` (thrown by the ramp, caught by `calculateBranch`) are top-level, one class each. `ContinuationJacobianPolicy`, `PhaseCorrection` and `CondenserAttempts`, named by more than one part, are top-level. `RampStep`, `EnergyShiftLog` and `HeatSubdivisions` stay private and nested, in `V3FeatureRamp`, the only part that names them.
7. **Aliases.** One new one: `V3ColumnCalculator.PATH_DEPENDENT_BOUND_SUFFIX` reads `PathDependentHeatBound.PATH_DEPENDENT_BOUND_SUFFIX` (three tests read it). The aliases of the revisions, `HYDRAULIC_CORRECTION_BUDGET_POLLS`, `requestOnlyAdmission`, `formulationRevision` and `closureTolerance` are unchanged.
8. **Tests repointed, not forwarded.** Seven tests that called a moved package-private member now name its new class (the qualifier only), as D-C1 and D-C2 did: `V3StageContinuationTest` (`continuationStageCounts(int)` on `V3ContinuationLadder`; `steamRampFractions`, `rampRungBudget`, `steamRampMidpointFraction` on `V3FeatureRamp`), `V3ContinuationSeedTest` (`sourcePosition`, `withStageGeometry`), `V3PumparoundCalculatorTest` and `V3SideDrawContractTest` (`withStageGeometry`), `V3ColumnInitializerTest` and `V3VacuumStartupTest` (`projectedSeedOrPrevious`), `V3CondenserPhaseTransitionTest` (`CondenserAttempts`). The calculator keeps no forwarder of a moved method.
9. **Checks by reading and by script** (scripts under `tmp/d-c4/`, gitignored). After each commit I re-read every new file in full and every changed import, and grepped every moved member over `src/main`, `src/test`, `src/fluidGameTest`, `src/mcpCompat` and `tools/**/*.java`.
   - `verify.mjs a91f6f9fa` (all three steps): of 1,778 removed lines, all but 8 reappear among the 1,944 added after normalising modifiers and the four qualifiers; the 8 are comment lines I rewrote (two one-line Javadocs that became class comments, two re-pointed `{@link}`s, four class-comment lines of `RungOptions`, `V3HydraulicPublish`, `V3LearnedRoute`). The added lines not from a removed line are package lines, imports, class comments, the four class headers and private constructors, and the alias.
   - `modifiers.mjs a91f6f9fa`: 28 declaration lines differ only in modifiers, 46 code lines only in a qualifier, and nothing else.
   - Braces, parentheses and brackets balance in all 20 changed files; every changed file is CRLF only.
   - `calls.mjs`: in each of the 13 changed main files, every unqualified call, upper-case constant and simple type name is declared in the file, imported, a top-level type of the package or `java.lang` (the only hits are the enum's constants in its own declaration and in the switch).
   - `unused.mjs`: no unused import in any changed file. No file declares a method with a name it static-imports, so no import is shadowed.
   - `refs.mjs`: every `V3ColumnCalculator.x`, `V3ContinuationLadder.x`, `V3FeatureRamp.x`, `V3AttemptLoop.x` in code names a declared member, except three tool probes that were already broken at `a91f6f9fa` (see "Outside my lane").
10. **Not checked:** compilation, the tests, the gates, the poll and factorization counts, the digests. Nothing is expected to move. A changed digest, path, event, published string, poll or factorization count is a defect of this change.
11. **Stack-frame readers.** `tools/tutorial-column/SmallColumn.java` (not compiled by the lane; not edited) counts a poll only when a frame's class ends in `V3ColumnCalculator`, and calls it learned on the method `correctNeuralSeed` or a class containing `Neural`. Every solve still enters through the façade (`calculate`, `calculateWithStrategy` and their lambdas, `calculateInCatalog`, `calculateBranch`), so each poll still has a calculator frame below it. No new class name ends in `V3ColumnCalculator`, `ColumnSizing` or `BlockSizing` or contains `Neural`, so its buckets do not change. `CrudeUnitSampleTest` (lines 242, 874, 1228) tests `contains("V3ColumnCalculator")` on the stack: the same façade frames answer.
12. **Gate groups.** `columnGates` takes every main class under `science/column/v3/**` and `nativeSmoke` reads the same copy, so the eight new classes are reached. `build.gradle` needs no line. No Rust or C++ source names a moved type (grep), so no `cargo` run. `V3ColumnLabels.java` did not change.
13. **Code guide.** `column-v3.md`: the calculator row; three new class-map rows (`V3ContinuationLadder`, `V3FeatureRamp`, `V3AttemptLoop`); the shared-types row (the five new types); main-flow items 6 and 7; the continuations heading; the projection trap; three where-to-look rows. `map.mjs --readme` rewrote the package count (80 to 88 files); `--check` passes. No CHANGELOG line. I left the batch README and `documentation/INDEX.md` to the main thread, as for D-C1 to D-C3.

## Moved, per step

| Step | Member or type | Callers (sites) | Visibility before → after |
|---|---|---|---|
| 1 | `PRESSURE_CONTINUATION_*` (7 constants) | ladder | private → private |
| 1 | `requiresPressureContinuation` | façade 1 (review range, reads the trigger) | private → pp |
| 1 | `solveStageContinuation`, `solvePressureContinuation` | façade, ladder | private → pp |
| 1 | `continuationSeed` | ladder 1, ramp 1 (tie: range) | private → pp |
| 1 | `projectedSeedOrPrevious` | ladder 2; two tests | pp → pp |
| 1 | `isLogCoordinateFeasible`, `recoverContinuationStage`, `continuationStagePath`, `withTopPressure`, `continuationPressureSteps`, `continuationPressurePath`, `interpolate`, `specification`, `pressureEvent` | ladder | private → private |
| 1 | `featureRampRequired` | ladder only (it sat in the ramp's range) | private → private |
| 1 | `recoverWithBubblePointProjection` | façade 1, ladder 2 | private → pp |
| 1 | `hasCondenserPhaseMismatch` | façade 3, ladder 3, attempt loop 1 (rule: ladder 3 > loop 1) | private → pp |
| 1 | `originalProblem` | façade 1, ladder 1 | private → pp |
| 1 | `continuationStageCounts` (1 and 2 arguments), `withStageGeometry`, `sourcePosition` | ladder; tests | pp → pp |
| 1 | `withoutSideDraws`, `withoutPumparounds`, `withoutSteamWithSurrogateDuty`, `initializeForSolve` | façade and ladder (range 3135 to 3351) | private → pp |
| 1 | `surrogateSteamDutyWatts`, `withReboilerDuty`, `reboilerDutyWatts` | ladder 1, ramp 1 each (tie: range) | private → pp |
| 1 | `InitializationFailure` | thrown by the ladder, caught by the façade | private nested → pp top-level; constructor private → pp |
| 2 | `HEAT_RAMP_STEPS`, `HEAT_BEARING_DRAW_RAMP_STEPS`, `MAXIMUM_HEAT_SUBDIVISIONS(_PER_RUNG)`, `MAXIMUM_STEAM_SUBDIVISIONS`, `DRAW_RAMP_*_MAXIMUM_ITERATIONS`, `INTERMEDIATE_RUNG_BUDGET` | ramp | private → private |
| 2 | `RampStep`, `EnergyShiftLog`, `HeatSubdivisions` | ramp | private nested → private nested in `V3FeatureRamp` |
| 2 | `recoverWithDrawRamp` | façade 2, ladder 2, ramp 1 | pp (since step 1) |
| 2 | `withEnergyShiftPrediction` | ramp only (it sat in the attempt loop's range, old 2187 to 2267; it takes `RampStep` and `EnergyShiftLog`) | private → private |
| 2 | `rampSteps`, `fastRampSteps`, `heatBearingRampSteps`, `midpointSteamStep`, `midpointHeatStep`, `requireCoolingBelowBaseCondenserDuty`, `condensationCappedTray`, `condensationCapacityWatts`, `trayDutyWatts`, `withRampEvents`, `rampEvidence` | ramp | private → private |
| 2 | `steamRampFractions`, `rampRungBudget`, `steamRampMidpointFraction` | ramp; `V3StageContinuationTest` | pp → pp |
| 2 | `PathDependentHeatBound` with `PATH_DEPENDENT_BOUND_SUFFIX` | thrown by the ramp, caught by the façade | private nested → pp top-level; constructor and `solvePath()` private → pp; the suffix pp, aliased |
| 3 | `CONDENSER_PHASE_CORRECTOR_MAXIMUM_ITERATIONS`, `MAXIMUM_FLOOR_SUPPORT_REFRESHES`, `MAXIMUM_STALLED_FLOOR_SUPPORT_REFRESHES`, `MAXIMUM_WET_TRAY_REFRESHES`, `STALLED_DROP_REFRESH_ITERATIONS` | attempt loop | private → private |
| 3 | `solveSingleProblem` (7 arguments) | façade 2, ladder 4, attempt loop 2 | private → pp (in step 1) |
| 3 | `solveSingleProblem` (8), `prepareAttempt` (6), `feedFlash` | attempt loop, ladder, ramp, learned route, publication | pp → pp |
| 3 | `prepareAttempt` (4) | façade 1, ramp 1 | private → pp (in step 2) |
| 3 | `correctCondenserPhase` | façade 3, ladder 3, attempt loop 1 | private → pp (in step 1) |
| 3 | `runRung` | façade 2, ramp 2 | private → pp (in step 2) |
| 3 | `untruncatedFeedFlash`, `withPhaseEvents`, `withWetEnergyShift`, `withFreeWaterContinuation`, `bounded`, `seedForTopology`, `refreshFloorSupport` | attempt loop | private → private |
| 3 | `audit` | façade 1 (coarse recovery), attempt loop 1 (in no range; the loop runs the attempts) | private → pp |
| 3 | `ContinuationJacobianPolicy` | all parts, `RungOptions`, publication, learned route | pp nested → pp top-level enum |
| 3 | `PhaseCorrection` | façade, ladder, attempt loop | private nested (pp since step 1) → pp top-level record |
| 3 | `CondenserAttempts` | façade, ladder, ramp, attempt loop, `V3CondenserPhaseTransitionTest` | pp `static final` nested → pp `final` top-level (a top-level class cannot be `static`) |

In steps 1 and 2 the new files imported the still-nested types (`import ...V3ColumnCalculator.PhaseCorrection;`), so their bodies did not change when the types moved in step 3: only those import lines went.

## What stays in the façade, and why

| Member | Why |
|---|---|
| Entries: `calculate` (public 7 and 5 arguments; private 9 and 4), `calculateWithAcceptedProfile` (public 3 and 5, pp 4), `calculateWithNeuralTrace`, `calculateWithStrategy`, `closureTolerance` | Public and package entries, the catalog pin (`MaterialRuntime.pinned`, `V3SolveScratch.within`) |
| `calculateInCatalog` (6 arguments), `preferredCondenserBranch` | The branch order and its probe |
| `calculateBranch` | The façade the review drew; it keeps the direct and base solves, the material-closed fallback and the coarse recovery, which differ from every other rung (D-C3, sites A to D) |
| `warmStartPass`, `retargetWarmSeed`, `warmCompatible`, `sameFeatureShape`, `reboilerDuty`, `relativeDrift` | Only the façade calls them (the warm start of `calculateWithStrategy`); in no group's range |
| `specValue` | `V3Admission` and `V3ColumnLabels` read it, and `warmCompatible` |
| `publishesSuccess`, `publishesRequestedGeometry`, `diagnostics` | The acceptance gates and the diagnostics, which every part and the learned route and publication call (D-C2) |
| `withPriorSupportNotes` | All four parts call it; in no range; ladder and ramp tie at 4 sites |
| Constants and aliases: `MAXIMUM_NEWTON_ITERATIONS`, `SCALED_RESIDUAL_TOLERANCE`, `AGGRESSIVE_CLOSURE_TOLERANCE`, `DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO`, the four revisions, `HYDRAULIC_CORRECTION_BUDGET_POLLS`, `PATH_DEPENDENT_BOUND_SUFFIX`, `requestOnlyAdmission`, `formulationRevision` | Public or read as `V3ColumnCalculator.NAME` by the solver, `V3InitializationOptions`, `V3SolvePolicy`, tests and tools |

## Lines

| File | Before | After |
|---|---|---|
| `V3ColumnCalculator.java` | 2,328 | 626 |
| `V3ContinuationLadder.java` | – | 555 |
| `V3FeatureRamp.java` | – | 711 |
| `V3AttemptLoop.java` | – | 485 |
| `InitializationFailure.java`, `PathDependentHeatBound.java`, `ContinuationJacobianPolicy.java`, `PhaseCorrection.java`, `CondenserAttempts.java` | – | 14, 41, 16, 12, 34 |
| `RungOptions.java`, `V3LearnedRoute.java`, `V3HydraulicPublish.java`, `V3FloorLift.java` | 20, 230, 246, 247 | unchanged counts (qualifiers and comments) |

## Outside my lane (not changed; for the main thread)

- Comments that still name a moved member on the calculator: `SolveTelemetry.java` line 11 (`V3ColumnCalculator.solveSingleProblem`), `V3Admission.java` line 59 and `V3HeatFeasibility.java` line 21 (`V3ColumnCalculator.PathDependentHeatBound`), `V3SimultaneousColumnSolver.java` line 68 (`V3ColumnCalculator.INTERMEDIATE_RUNG_BUDGET`), `V3ContinuationSeedTest.java` lines 16 and 46 (`{@link V3ColumnCalculator#sourcePosition}`). Comments only: nothing compiles them (no doclint). The test's comment is not a forced qualifier, so I left it.
- Tool probes already broken at `a91f6f9fa`, not by this change: `tools/benchmark-population/java/V3RequestAdmissionProbe.java` (`V3ColumnCalculator.RequestAdmission`, D-C1), `tools/lnn-gap/java/V3GapEvaluationProbe.java` (`NEURAL_RAMP_HANDOFF_BUDGET_MILLIS`), `tools/open-thermo-data/o10/O10StallProbe.java` (`o10Warm`). None is in a compiled source set.

## Tests for the main thread

Nothing is expected to move. Every column solve loads the four files, so the whole of `columnGates` is the affected set. The list is the D-C3 list, plus the two tests whose qualifiers changed and that it did not hold. Run the second lane with at most 4 workers (`--max-workers=4`).

| Task | Classes | What it proves here |
|---|---|---|
| `compileJava`, `compileTestJava`, `compileFluidGameTestJava` (and `compileNeuralMvpJava` where `tools/development.gradle` is applied) | everything | The uncertain points below; `tools/neural` calls only the public entries and the `formulationRevision` alias, unchanged |
| `columnGates --tests` | D-C2's 22: `V3ColumnCalculatorTest`, `V3BoundedSolvePathTest`, `V3StageContinuationTest`, `V3ColdStartTest`, `V3ConvergenceClosureTest`, `V3StageTraceCalculatorTest`, `V3LiteraturePresetTest`, `V3LearnedRecoveryTest`, `V3ColumnCodesTest`, `V3BandFactorizationCountTest`, `V3PumparoundSteamCalculatorTest`, `V3CondenserPhaseTransitionTest`, `V3PumparoundCalculatorTest`, `V3RegroupedTransformerTest`, `V3TrayHydraulicsColumnTest`, `V3FlashTruncationColumnTest`, `V3ColumnDisplayResultTest`, `V3BundledModelTest`, `V3LiquidSupplyScreenTest`, `V3PumparoundContractTest`, `V3PathDependentInfeasibilityTest`, `HollandExample32BenchmarkTest` | Paths, events, digests, poll and factorization counts of every rung kind through the three new files (`V3BoundedSolvePathTest`, `V3BandFactorizationCountTest`, `V3ColumnCodesTest`); the ramp and its heat bounds through the top-level `PathDependentHeatBound` and the suffix alias (`V3PathDependentInfeasibilityTest`, `V3PumparoundCalculatorTest`, `V3PumparoundSteamCalculatorTest`); the condenser correction and the top-level `CondenserAttempts` (`V3CondenserPhaseTransitionTest`); the learned and hydraulic calls of `V3AttemptLoop` (`V3LearnedRecoveryTest`, `V3RegroupedTransformerTest`, `V3BundledModelTest`, `V3TrayHydraulicsColumnTest`); the schedules on their new classes (`V3StageContinuationTest`) |
| `columnGates --tests` (D-C2 and D-C3 additions) | `V3ContinuationSeedTest`, `V3PhaseTruncationTest`, `V3TraceFloorSupportTest`, `V3NeuralInitializationTest`, `V3PotColumnTest`, `V3VacuumStartupTest`, `V3SideDrawCalculatorTest`, `V3AggressiveStrategyTest`, `V3AqueousColumnTest` | The seed hand-off (`sourcePosition`, `withStageGeometry` on the ladder), the floor lift and refresh in the loop, the traced learned entry, a pot's direct solve, the pressure legs and the projection on the ladder (`V3VacuumStartupTest`), draw ramps, the warm start, wet columns |
| `columnGates --tests` (added here) | `V3ColumnInitializerTest`, `V3SideDrawContractTest` | `projectedSeedOrPrevious` and `withStageGeometry` on `V3ContinuationLadder` (qualifier edits) |
| `test --tests` | `com.wormzjl.icheme.network.ColumnWireTest`, `com.wormzjl.icheme.network.V3InspectionCodecTest`, `com.wormzjl.icheme.client.gui.flowsheet.FlowsheetExamplesTest`, `com.wormzjl.icheme.science.plant.column.ColumnSolveTest`, `com.wormzjl.icheme.LayeringTest` | Published results on the wire and in the sheet's examples; the running column's solve; the new files' imports keep the package rules |
| `holdGates --tests` | `com.wormzjl.icheme.science.flowsheet.FlowsheetColumnTest` | The sheet's column solves end to end |
| `nativeSmoke` (where the library is built) | `NativeColumnSolverTest` | The native band solve under the split calculator, digests unchanged |

40 classes. `equipmentGates` loads no file this change touched (its V3 list names input, preset, outcome, resolver, specification, basis, options and spec classes only); no run. No `cargo` run: no Rust source and not `V3ColumnLabels.java` changed. `CrudeUnitSampleTest` (`holdGates`) reads stack frames by class name, but the frames it reads are the façade's, which every solve still has; it does not need a run for this change.

## Points where compiling or binding is not certain

I could not compile. As far as I can read the language specification, each point is legal Java. I checked each one by reading and with the scripts above.

1. Static imports between the four classes, in both directions (`V3ColumnCalculator.java` lines 14 to 38, `V3ContinuationLadder.java` lines 9 to 17, `V3FeatureRamp.java` lines 8 to 19, `V3AttemptLoop.java` lines 13 to 19). They import package-private static members of classes in the same package (JLS 7.5.3; D-C1 did the same with `V3SolveReport`). A static-imported method is hidden in a class that declares a method of the same name (JLS 6.4.1). No file declares a name it imports (script), and the names were unique in the one class they came from.
2. A local variable and a static-imported method with the same name: the local `audit` at `V3ColumnCalculator.java` line 394 and the call `audit(coarsePrepared.problem(), ...)` at line 415, which now binds to the static import of `V3AttemptLoop.audit` (declared at `V3AttemptLoop.java` line 469). Method names and variable names are separate namespaces (JLS 6.5.7.1). The same pattern inside `V3AttemptLoop` (`feedFlash` at line 84, `audit` at line 140) binds to methods of the same class, as before the move.
3. `V3AttemptLoop.java` line 55: `STALLED_DROP_REFRESH_ITERATIONS = MAXIMUM_NEWTON_ITERATIONS / 8` now reads a static-imported constant variable. A simple name of a constant variable is still a constant expression (JLS 15.29). No switch label or annotation needs it to be one.
4. `PathDependentHeatBound.java` line 29: the constructor reads `PATH_DEPENDENT_BOUND_SUFFIX`, which line 38 declares below it. This is not a forward reference in an initializer (JLS 8.3.3), so it is legal. `V3ColumnCalculator.java` line 69: the alias is a compile-time constant of the other class's constant, as the earlier aliases are.
5. Top-level package-private types with package-private members: `PhaseCorrection` (a record whose component type is the package-private `V3SolvePass`; the implicit canonical constructor has package access), `CondenserAttempts` (its default constructor has package access, which `new CondenserAttempts()` uses at `V3ColumnCalculator.java` line 244, `V3FeatureRamp.java` line 138 and the test's lines 24 and 40), `InitializationFailure` and `PathDependentHeatBound` (`RuntimeException` subclasses with no `serialVersionUID`, as the nested ones had; `-Xlint:serial` warns at most).
6. `V3FeatureRamp.java` line 76: `RampStep` stays a private nested record with a private compact constructor. That is legal because the record itself is private (JLS 8.10.4), as before. `HeatSubdivisions` (line 623) reads the outer class's private constants, which a nested class may do.
7. `V3AttemptLoop.java` lines 121 to 133: the switch on the top-level enum `ContinuationJacobianPolicy` uses its constants' simple names as case labels, as it did for the nested enum.
8. `catch (InitializationFailure ...)` and `catch (PathDependentHeatBound ...)` at `V3ColumnCalculator.java` lines 477 and 480 now resolve to the top-level classes by their simple names (same package). Each is one class, thrown at `V3ContinuationLadder.java` line 433 and `V3FeatureRamp.java` lines 214 and 666. The catch order and the catch depth are unchanged.
9. Commits 1 and 2 on their own: they import nested types of the calculator (`import ...V3ColumnCalculator.PhaseCorrection;`, `.CondenserAttempts`, `.ContinuationJacobianPolicy`). Commit 3 removes those imports with the move. These imports are legal (single-type imports of member types: JLS 7.5.1). Only the branch tip needs to compile.
10. Class initialization: `INTERMEDIATE_RUNG_BUDGET` is now built when `V3FeatureRamp` initializes (at the first ramp or `rampRungBudget` call), not when the calculator does. The `RungBudget` constructor only validates, so the value and `equals` are the same.
