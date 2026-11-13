# D-C2 The column calculator's stateful groups, first three: floor lift, publication and hydraulics, learned route (report)

Brief: [WAVE_D_BRIEFS.md](../WAVE_D_BRIEFS.md), section "D-C2". Review: [COLUMN_REVIEW.md](../COLUMN_REVIEW.md) section 2, row S1. Previous slice: [D-C1_REPORT.md](D-C1_REPORT.md).
Subagent (Opus high), 2026-11-13, on `claude/codebase-review-refactor-8a85ef` at `7b36c23ff`. Written without a compile or a test run.

## Summary

1. **Four steps, a move only.** Commits: shared types `c18dbb148`, floor lift `f1e93703f`, publication and hydraulics `fca429c86`, learned route `9b0792241`, then this document and the code guide. Every moved body is the old text. The only edits in a body are a call's qualifier (`V3ColumnCalculator.`, `V3FloorLift.`, `V3HydraulicPublish.`, `V3LearnedRoute.`) and the rename `SolvePolicy` to `V3SolvePolicy` that the brief asks for. Every published string, event text, solve path and budget keeps its characters; no call is added, dropped or reordered.
2. **New files** in `science/column/v3`, all package-private: `V3SolvePass` (38 lines), `V3SolvePolicy` (60), `PreparedAttempt` (19), `HydraulicPublication` (11), `NeuralProgress` (95), `HydraulicBudgetExceeded` (9), `NeuralBudgetExceeded` (10), `V3FloorLift` (247), `V3HydraulicPublish` (247), `V3LearnedRoute` (230).
3. **Calculator**: 3,218 lines before, 2,376 after (895 removed, 53 added: 34 lines of the rename, the alias and its comment, 16 qualifier or modifier lines, 1 Javadoc link). About 840 lines moved; the new files add headers and class comments.
4. **Move check.** `tmp/d-c2/verify.mjs` matches every removed line of each step against the step's added lines, after normalising modifiers, qualifiers and the rename: 0 unmatched lines in steps 2, 3 and 4. In step 1 three one-line Javadoc comments became the first line of a multi-line class comment (comments only). `tmp/d-c2/calls.mjs` checks the 12 changed files for an unqualified call or constant that is neither declared in the file nor statically imported, and for a simple name of a nested type the calculator kept: 0 found. `tmp/d-c2/braces.mjs`: braces, parentheses and brackets balance in every file.
5. **What stays, and why** (table below): the attempt loop's `prepareAttempt`, `seedForTopology` and `refreshFloorSupport`; the acceptance gates `publishesSuccess` and `publishesRequestedGeometry`; `diagnostics`; the entry points and the catalog pin; the types `PhaseCorrection`, `InitializationFailure`, `PathDependentHeatBound`, `CondenserAttempts`, `RampStep`, `EnergyShiftLog`, `HeatSubdivisions` and `ContinuationJacobianPolicy`. No block inside `calculateInCatalog` or `calculateBranch` had to be split: each group moved as whole methods.
6. **Visibility.** Each private member a moved group reads became package-private (list below). A package-private record cannot keep a private canonical constructor (JLS 8.10.4), so the three records' compact constructors lost `private`.
7. **One alias**: `V3ColumnCalculator.HYDRAULIC_CORRECTION_BUDGET_POLLS` reads the moved constant; `V3TrayHydraulicsColumnTest` reads it. The two tests that called `V3ColumnCalculator.liftFloorSupport` (`V3ContinuationSeedTest`, `V3PhaseTruncationTest`) now call `V3FloorLift.liftFloorSupport`, as D-C1 did for moved methods.
8. **Gate groups.** `columnGates` takes every main class under `science/column/v3/**` (its tests directory is that package), and `nativeSmoke` reads the column group's copy: the new classes are reached by the same patterns; no list in `build.gradle` needs a line. `equipmentGates` lists V3 classes one by one and none of them loads the calculator; no change. The Rust test `formulation_is_the_java_token` reads `V3ColumnLabels.java`, which this batch does not touch.
9. **A finding outside my lane** (D-C1's move, not fixed here): `build.gradle` line 382 still declares `V3ColumnCalculator.java` as the `rustTest` step's `formulationSource` input, but the Rust test has read `V3ColumnLabels.java` since D-C1. A label change therefore leaves `rustTest` up to date. The input should name `V3ColumnLabels.java`.
10. **Tools.** No harness under `tools/**/*.java` names a moved member. `tools/neural` (the `neuralMvp` source set) calls only `calculate`, `calculateWithAcceptedProfile` (public) and the 3-argument `formulationRevision` alias, all unchanged. `tools/tutorial-column/SmallColumn.java` sorts control polls by stack frames: a frame of a class ending in `V3ColumnCalculator` (the entries stay there, so every solve still has one), the method name `correctNeuralSeed` (kept) or a class name containing `Neural`. Its learned and classical counts are the same after the move. `tools/lnn-gap` reads `NEURAL_RAMP_HANDOFF_BUDGET_MILLIS`, which was gone before this batch.
11. **Code guide.** `column-v3.md`: the calculator row, four new class-map rows (`V3LearnedRoute`, `V3HydraulicPublish`, the shared types, `V3FloorLift`), main-flow items 5, 7 and 8, and the where-to-look row for tray hydraulics. `map.mjs --readme` rewrote the package count (69 to 79 files); `--check` passes. No CHANGELOG line. The batch README and `documentation/INDEX.md` are left to the main thread, as for D-C1.

## Moved, per step

| Step | Member or type | Old place | New place | Visibility before → after |
|---|---|---|---|---|
| 1 | `V3SolvePass` (record) | nested, calculator end | `V3SolvePass` | private → pp; compact constructor private → pp |
| 1 | `SolvePolicy` (record) | nested, after the floor lift | `V3SolvePolicy` (the brief's name) | private → pp; compact and the three other constructors, `OFF`, `OFF_FAST`, `withoutCutoff`, `trace` private → pp |
| 1 | `PreparedAttempt` (record) | nested, after `SolvePolicy` | `PreparedAttempt` | private → pp; compact constructor private → pp |
| 1 | `HydraulicPublication` (record) | nested, after `publishAccepted` | `HydraulicPublication` | private → pp |
| 1 | `NeuralProgress` (class) | nested, learned route | `NeuralProgress` | pp → pp; constructor, `iterations`, `lastMaximumScaledResidual`, `summary` private → pp; `noteIteration` and the fields stay private |
| 1 | `HydraulicBudgetExceeded`, `NeuralBudgetExceeded` | nested | own files | private → pp (one class each; thrown and caught as before) |
| 2 | `liftFloorSupport` | after `refreshFloorSupport` | `V3FloorLift` | pp → pp |
| 2 | `deliveredInflow`, `capOversizedPoint`, `reinsertRemovedPoint`, `hasLiquidPhase`, `liftAbsentPhase`, `OVERSIZED_SEED_FACTOR` | same block; the constant from the calculator head | `V3FloorLift` | private → private |
| 2 | `componentFloor` | same block | `V3FloorLift` | private → pp (`seedForTopology` reads it) |
| 3 | `publishAccepted` | after `publishesRequestedGeometry` | `V3HydraulicPublish` | private → pp (the ladder and the learned route call it) |
| 3 | `trayHydraulics`, `hydraulicCorrection`, `wetTrayMask`, `summarised`, `advisory` | after `publishAccepted` | `V3HydraulicPublish` | private → private |
| 3 | `HYDRAULIC_MISMATCH_TOLERANCE`, `HYDRAULIC_CORRECTION_MAXIMUM_ITERATIONS`, `HYDRAULIC_CORRECTION_PATH_SUFFIX` | calculator head | `V3HydraulicPublish` | private → private |
| 3 | `HYDRAULIC_CORRECTION_BUDGET_POLLS` | calculator head | `V3HydraulicPublish` (alias kept) | pp → pp |
| 4 | `calculateInCatalog` (the learned, 9-argument overload) | after the private `calculate` | `V3LearnedRoute` | private → pp |
| 4 | `correctNeuralSeed`, `neuralRungBudget`, `neuralExtension`, `initializationEvent`, `neuralFailure` | learned route | `V3LearnedRoute` | private → private |

Kept nested in the calculator, against the brief's step-1 list: `PhaseCorrection`. Only the calculator's remaining code uses it (`correctCondenserPhase` and the ladder: the "solve, correct the condenser phase, recover" copies of the next slice), so the brief's rule "a type that only the calculator's remaining code uses stays nested" applies. `InitializationFailure` and `PathDependentHeatBound` stay nested too: no moved group throws or catches them.

## Considered and left (what each reads or who calls it)

| Member | Why it stays |
|---|---|
| `prepareAttempt` (both) | Called by the attempt loop (3 calls), the ramp (`withEnergyShiftPrediction`), the ladder (the coarse recovery) and the learned route. It lies in the review's attempt-loop range too. The 6-argument one is pp now (the learned route calls it). |
| `seedForTopology`, `refreshFloorSupport` | Only `prepareAttempt` and the attempt loop call them; they are in the review's attempt-loop range (old 2724 to 2822), which overlaps the floor-lift range (old 2775 to 3039). `seedForTopology` calls `V3FloorLift.componentFloor`. |
| `componentFloor` | Moved, not left: two calls in `liftFloorSupport` and two in `seedForTopology` (a tie), and it is the floor itself, declared in the floor-lift block. |
| `publishesRequestedGeometry` | One caller in each of the learned route, the publication and the ladder (`calculateBranch`): no majority, and it is an acceptance gate like `publishesSuccess`. pp now. |
| `publishesSuccess` | The ladder's acceptance gate (about 30 uses). pp now. |
| `diagnostics` (both) | Called by the ladder (`calculateBranch`), the learned route and the publication. pp now. |
| `solveSingleProblem` (10 and 11 arguments) | The attempt loop. The publication calls the 10-argument one, the learned route the 11-argument one: both pp now; the four shorter overloads stay private. |
| `feedFlash` | Called by the attempt loop, the projection, the ramp and the learned route. pp now. |
| `calculateWithAcceptedProfile` (4 arguments) | An entry plumbing overload; the learned route's backup calls it. pp now. |
| The private `calculate` overloads (4, 8 and 9 arguments) | The entries and the catalog pin (`MaterialRuntime.pinned`, `V3SolveScratch.within`). The 9-argument one calls `V3LearnedRoute.calculateInCatalog`. |
| `ContinuationJacobianPolicy` | The attempt loop's switch. pp now; the publication and the learned route name it as `V3ColumnCalculator.ContinuationJacobianPolicy.STAGE_LOCAL_BLOCKS`. |
| `AGGRESSIVE_CLOSURE_TOLERANCE` | Beside the other tolerances; `V3SolvePolicy.OFF_FAST` reads it. pp now. |

## Visibility changes, all forced by the move

- Calculator members, private → pp: `AGGRESSIVE_CLOSURE_TOLERANCE`, `ContinuationJacobianPolicy`, `calculateWithAcceptedProfile` (4 arguments), `publishesRequestedGeometry`, `solveSingleProblem` (10 and 11 arguments), `feedFlash`, `prepareAttempt` (6 arguments), `publishesSuccess`, `diagnostics` (5 and 6 arguments).
- Moved members, private → pp: `V3FloorLift.componentFloor`, `V3HydraulicPublish.publishAccepted`, `V3LearnedRoute.calculateInCatalog`; in the types, the list of step 1 above.
- `SolveTelemetry`: its field and constructor parameter name `NeuralProgress` instead of `V3ColumnCalculator.NeuralProgress` (a qualifier the moved type forces; the file is D-C1's).

## Tests for the main thread

Nothing is expected to move: any changed digest, solve path, event, published string or factorization count is a defect of this change. The calculator is loaded by every column solve, so the affected set of `columnGates` is the whole group; the list below is the brief's, plus the classes that name a moved member or exercise a moved group.

| Task | Classes | What it proves here |
|---|---|---|
| `compileJava`, `compileTestJava` (and `compileNeuralMvpJava` where `tools/development.gradle` is applied) | everything | The uncertain points below; `tools/neural` still compiles against the public entries |
| `columnGates --tests` | Brief: `V3ColumnCalculatorTest`, `V3BoundedSolvePathTest`, `V3StageContinuationTest`, `V3ColdStartTest`, `V3ConvergenceClosureTest`, `V3StageTraceCalculatorTest`, `V3LiteraturePresetTest`, `V3LearnedRecoveryTest`, `V3ColumnCodesTest`, `V3BandFactorizationCountTest`, `V3PumparoundSteamCalculatorTest`, `V3CondenserPhaseTransitionTest`, `V3PumparoundCalculatorTest`, `V3RegroupedTransformerTest`, `V3TrayHydraulicsColumnTest`, `V3FlashTruncationColumnTest`, `V3ColumnDisplayResultTest`, `V3BundledModelTest`, `V3LiquidSupplyScreenTest`, `V3PumparoundContractTest`, `V3PathDependentInfeasibilityTest`, `HollandExample32BenchmarkTest` | Digests, paths, events and counts of the ladder, the learned route (`V3LearnedRecoveryTest`, `V3RegroupedTransformerTest`, `V3BundledModelTest`), the publication and its hydraulic correction (`V3TrayHydraulicsColumnTest`: the alias and the poll budget), the factorization event (`V3BandFactorizationCountTest`) |
| `columnGates --tests` (added) | `V3ContinuationSeedTest`, `V3PhaseTruncationTest` (call `V3FloorLift.liftFloorSupport`), `V3TraceFloorSupportTest`, `V3NeuralInitializationTest` | The floor lift on its own (reinsertion, absent phase, oversized cap) and through the calculator; the learned entry's modes and budget |
| `test --tests` | `com.wormzjl.icheme.network.ColumnWireTest`, `com.wormzjl.icheme.network.V3InspectionCodecTest`, `com.wormzjl.icheme.client.gui.flowsheet.FlowsheetExamplesTest`, `com.wormzjl.icheme.science.plant.column.ColumnSolveTest`, `com.wormzjl.icheme.LayeringTest` | Published results on the wire and in the sheet's examples; the running column's solve; the new files' imports keep the package rules |
| `holdGates --tests` | `com.wormzjl.icheme.science.flowsheet.FlowsheetColumnTest` | The sheet's column solves end to end |
| `nativeSmoke` (where the library is built) | `NativeColumnSolverTest` | The native band solve under the moved calculator, digests unchanged |
| `compileFluidGameTestJava` | the fluid GameTests | They read only `V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO` (unchanged); a compile shows it |

`equipmentGates` does not load the calculator (see summary item 8); no run. The Rust test `formulation_is_the_java_token` reads a file this batch does not change; no run.

## Points where compiling is not certain

I could not compile. Each point below is legal Java as far as I can read the language specification, and each was checked by reading the code and with the scripts above.

1. `V3SolvePolicy.java` line 41, `V3SolvePass.java` line 25, `PreparedAttempt.java` line 13: compact canonical constructors with no modifier in package-private records. A private one would not compile (JLS 8.10.4: the canonical constructor must not be private when the record has package access).
2. `V3SolvePolicy.java` line 5: the stray one-line `/** ... */` before the class Javadoc came with the type unchanged. It is a dangling doc comment; `javac -Xlint` on JDK 23 or later warns about it, which fails nothing unless warnings are errors (I found no `-Werror` in `build.gradle`).
3. `V3SolvePolicy.java` line 39: `OFF_FAST` reads `V3ColumnCalculator.AGGRESSIVE_CLOSURE_TOLERANCE`, a compile-time constant, so it is inlined: no class-initialization cycle between the record and the calculator. The calculator's alias `HYDRAULIC_CORRECTION_BUDGET_POLLS` (`V3ColumnCalculator.java` line 66) is a constant read of `V3HydraulicPublish`'s in the same way.
4. Overload resolution from another class, where some overloads of the same name stay private: `V3HydraulicPublish.java` line 187 (`solveSingleProblem`, 10 arguments) and lines 69 (`diagnostics`, 6); `V3LearnedRoute.java` lines 40, 41, 137, 138 (`calculate`, 5 arguments, public; `calculateWithAcceptedProfile`, 4), 157 (`prepareAttempt`, 6), 163 (`feedFlash`), 173 (`solveSingleProblem`, 11), 185 (`diagnostics`, 5). Inaccessible methods are not potentially applicable (JLS 15.12.2.1), and each call has one accessible overload of its arity.
5. `V3HydraulicPublish.java` line 188 and `V3LearnedRoute.java` line 174 name the package-private nested enum `V3ColumnCalculator.ContinuationJacobianPolicy` of the public calculator.
6. `V3LearnedRoute.java` line 9 to 13 and `V3HydraulicPublish.java` lines 10 to 11: static imports of package-private members of the package-private `V3SolveReport`, as in D-C1; neither file declares a member of an imported name. `failedAudit` has two overloads; the import brings both.
7. `SolveTelemetry.java` lines 18 and 29 now name the top-level `NeuralProgress`; `V3SolvePolicy.progress()` returns that type, and `solveSingleProblem` passes it to the telemetry as before.
8. The two tests (`V3ContinuationSeedTest.java` lines 110, 156, 179; `V3PhaseTruncationTest.java` lines 252, 302) call the package-private `V3FloorLift` from the same package, as they called the package-private `liftFloorSupport` before.
