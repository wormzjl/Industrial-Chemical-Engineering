# D-C1 The column calculator's pure groups: labels, reporting, admission (report)

Brief: [WAVE_D_BRIEFS.md](../WAVE_D_BRIEFS.md), section "D-C1". Review: [COLUMN_REVIEW.md](../COLUMN_REVIEW.md) section 2, row S1.
Subagent (Opus high), 2026-11-13, on `claude/codebase-review-refactor-8a85ef` at `8e4644144`. Written without a compile or a test run.

## Summary

1. **Three groups moved, a move only.** Commits: labels `0b73eebc8`, reporting `bd9a6b06f`, admission `fbeb38a47`, then this document. Every moved body is the old text; the only edits in a body are a call's qualifier (`V3ColumnCalculator.specValue`). Every published string keeps its characters.
2. **New files** in `science/column/v3`: `V3ColumnLabels` (public, 127 lines), `V3SolveReport` (pp, 175), `SolveTelemetry` (pp, 98, the nested class made top-level under its own name), `V3Admission` (pp, 131).
3. **Calculator**: 3,652 lines before, 3,218 after (467 removed, 33 added: aliases, static imports, qualifiers). About 434 lines moved; the new files add headers and class comments.
4. **Purity.** Each moved member reads only its arguments: the input, the cutoff and the closure (labels); a problem, a state, a support, a flash, a code or strings (reporting); the request, plus one feed flash of it in `staticCoolingAdmission` (admission). None reads `SolvePolicy`, `V3SolvePass`, `PreparedAttempt`, `CondenserAttempts` or a ramp's state. `SolveTelemetry` holds its own counters, one per Newton attempt; it reads no calculator state and forwards to the learned pass's recorder as before.
5. **Calls inside the calculator.** Calls to the reporting functions go through 11 single static imports, so none of the 47 call sites changed. Label and admission calls are qualified (`V3ColumnLabels.`, `V3Admission.`). No calculator member shares a name with an imported one, so no import is shadowed.
6. **Visibility changes, all forced by the move.** These are private before and package-private after:
   - in `V3SolveReport`: `boundedEvent`, `mergedEvents`, `stageTraceEvent`, `flashTraceEvent`, `sideDrawDiagnostic`, `waterDiagnostic`, `terminalFailure`, both `failedAudit`, `boundedSummary`;
   - in `V3Admission`: `liquidSupplyScreen`, `staticCoolingAdmission` (the learned route calls them);
   - `SolveTelemetry`: the class and its constructor;
   - non-moving members of the calculator: `V3ColumnCalculator.specValue` (the labels and the screen read it) and `V3ColumnCalculator.NeuralProgress` (the telemetry's field type).
7. **Names that change for their readers.** `RequestAdmission` is now `V3Admission.RequestAdmission`. A Java type cannot have an alias. Its only reader outside the package's main code, `tools/benchmark-population/java/V3RequestAdmissionProbe.java`, already fails to compile today: it declares `package com.wormzjl.createcheme...`, from before the rename. I left that file untouched.
8. **Text that reads the source.** The Rust test `layout.rs formulation_is_the_java_token` parses the Java source for `String FORMULATION_REVISION = "`. It now reads `V3ColumnLabels.java`. Its comments, `LAYOUT.md`, the `FORMATS.md` row and the `FormatsTableTest` comment name `V3ColumnLabels`.
9. **Code guide.** In `column-v3.md`: three class-map rows, the calculator row, the labels lines, the classical route and two where-to-look rows. `map.mjs --readme` updated the README counts. No CHANGELOG line. The batch README and INDEX are left to the main thread.

## Members moved

| Group | Member | Old place | New class | Visibility before → after |
|---|---|---|---|---|
| labels | `FORMULATION_REVISION`, `ASSUMPTIONS_REVISION`, `WET_ASSUMPTIONS_REVISION`, `HEAT_ASSUMPTIONS_REVISION` | calculator head | `V3ColumnLabels` | public → public |
| labels | `DERIVED_PAIRS_SUFFIX` | calculator head | `V3ColumnLabels` | pp → pp |
| labels | `LEGACY_FORMULATION_REVISION`, `HEAT_FORMULATION_SUFFIX`, `legacyFormulationRevision`, `formulationRevisionWithoutClosure` | head; label functions | `V3ColumnLabels` | private → private |
| labels | `formulationRevision` (2 and 3 args), `initializationFormulationRevision`, `seedFormulationRevision`, `closureSuffix`, `assumptionsRevision` | label functions | `V3ColumnLabels` | pp → pp |
| reporting | `boundedSolvePath`, `failureCode` | reporting tail | `V3SolveReport` | pp → pp |
| reporting | `boundedEvent`, `mergedEvents`, `stageTraceEvent`, `flashTraceEvent`, `sideDrawDiagnostic`, `waterDiagnostic`, `terminalFailure`, `failedAudit` (2), `boundedSummary` | ramp tail, floor-lift tail, reporting tail | `V3SolveReport` | private → pp |
| reporting | `SolveTelemetry` (class; constructor) | nested, calculator end | top-level `SolveTelemetry` | private → pp (both) |
| admission | `RequestAdmission` (record, gate names, `ADMITTED`), `requestOnlyAdmission` | after `calculateInCatalog` | `V3Admission` | pp → pp |
| admission | `liquidSupplyScreen`, `staticCoolingAdmission` | same | `V3Admission` | private → pp |

## Members considered and left (what each reads)

| Member | Why it stays |
|---|---|
| `diagnostics` (both) | Reads `SolvePolicy.closureTolerance()`, the chain's policy record (private). |
| `audit` | Reads `SolvePolicy` and runs the auditor (an acceptance step, not a report). |
| `pressureEvent`, `rampEvidence` | Read a `V3SolvePass` (private record). |
| `withRampEvents`, `withPhaseEvents`, `withPriorSupportNotes` | Build a `V3SolvePass`. |
| `initializationEvent` | Pure, but part of the learned route (old lines 612 to 743). |
| `neuralFailure` | Reads `NeuralProgress.iterations()` and `lastMaximumScaledResidual()` (private). |
| `publishesSuccess` | Pure, but it is the ladder's acceptance gate (about 30 uses), not a report. |
| `bounded(String)` (feature ramp) | Twin of `boundedEvent`, with the same body, used by the wet energy shift. Not merged: the brief merges nothing. |
| `EnergyShiftLog`, `advisory`, `continuationStagePath`, `continuationPressurePath`, `HYDRAULIC_CORRECTION_PATH_SUFFIX` | Belong to the ramp's state, the hydraulic publication record and the ladder's path text. |
| `PATH_DEPENDENT_BOUND_SUFFIX`, `PathDependentHeatBound`, `InitializationFailure` | The ladder's exceptions and the text they carry. Tests read the suffix as `V3ColumnCalculator.NAME`. |
| `validateInput` call and its `INVALID_INPUT` refusal | Inside the learned entry's body (`calculateInCatalog`, learned overload). Moving them would restructure the method. |
| `closureTolerance` | Public entry point (request validation). |
| `preferredCondenserBranch`, the `V3OperatingDomainValidator.assess` calls | Run on a resolved problem with a branch, inside the branch or the learned route. They are not request-only. |
| `specValue` | General input reader, also used by `warmCompatible` and `reboilerDuty`. Widened to pp in place. |

## Aliases kept in the calculator

| Alias | Readers |
|---|---|
| `FORMULATION_REVISION`, `ASSUMPTIONS_REVISION`, `WET_ASSUMPTIONS_REVISION`, `HEAT_ASSUMPTIONS_REVISION` (`static final`, compile-time constants equal to the moved ones) | Tests: `NativeColumnSolverTest`, `V3StageTraceCalculatorTest`, `V3FlashTruncationColumnTest`, `V3CondenserPhaseTransitionTest`, `V3PumparoundCalculatorTest`, `V3HollandExample32`, `V3TrayHydraulicsColumnTest`. Archived tools. |
| `formulationRevision(input, cutoff, closure)` (delegates) | `tools/neural` (compiled by the optional `neuralMvp` source set; `retired/` too). Archived `tools/*-followup`, `hybrid-learning`. The calculator itself calls `V3ColumnLabels`. |
| `requestOnlyAdmission(input, ratio)` (delegates, returns `V3Admission.RequestAdmission`) | `tools/neural-retrain/NeuralRetrainProbe.java` (`var`, still compiles). Provenance texts in `tools/benchmark-population` (README, `classify.py`, `exclusions.json`). |

Tests that called a moved method now call the new class: `V3BoundedSolvePathTest`, `V3ColumnCodesTest`, `V3StageContinuationTest`, `V3ConvergenceClosureTest`, `V3PumparoundCalculatorTest`, `V3CondenserPhaseTransitionTest`, `V3RegroupedTransformerTest`, `V3TrayHydraulicsColumnTest` and the fixture `V3TestInputs`.

## Tests for the main thread

Nothing is expected to move: no reference is re-recorded, and any moved digest, path, event or count is a defect of this change. Every column test loads the calculator, so the affected set of `columnGates` is the whole group. The list below is the brief's list, plus every class that names a moved member or an alias, plus the admission tests.

| Task | Classes |
|---|---|
| `columnGates --tests` | Brief: `V3ColumnCalculatorTest`, `V3BoundedSolvePathTest`, `V3StageContinuationTest`, `V3ColdStartTest`, `V3ConvergenceClosureTest`, `V3StageTraceCalculatorTest`, `V3LiteraturePresetTest`, `V3LearnedRecoveryTest`, `V3ColumnCodesTest`, `V3BandFactorizationCountTest`, `V3PumparoundSteamCalculatorTest`. Added: `V3CondenserPhaseTransitionTest`, `V3PumparoundCalculatorTest`, `V3RegroupedTransformerTest`, `V3TrayHydraulicsColumnTest`, `V3FlashTruncationColumnTest`, `V3ColumnDisplayResultTest`, `V3BundledModelTest`, `V3LiquidSupplyScreenTest`, `V3PumparoundContractTest`, `V3PathDependentInfeasibilityTest`, `HollandExample32BenchmarkTest` |
| `test --tests` | `com.wormzjl.icheme.network.ColumnWireTest`, `com.wormzjl.icheme.network.V3InspectionCodecTest`, `com.wormzjl.icheme.client.gui.flowsheet.FlowsheetExamplesTest`, `com.wormzjl.icheme.science.plant.column.ColumnSolveTest`, `com.wormzjl.icheme.FormatsTableTest` (reads the changed row), `com.wormzjl.icheme.LayeringTest` (new files) |
| `holdGates --tests` | `com.wormzjl.icheme.science.flowsheet.FlowsheetColumnTest` |
| `nativeSmoke` (where the library is built), and `cargo test -p icheme-jni formulation_is_the_java_token` from `src/main/rust` (counts as one lane) | `NativeColumnSolverTest`; the Rust token test now reads `V3ColumnLabels.java` |

`equipmentGates` goes out of date: `V3TestInputs` is in its helper list. The changed line is in `stateless`, a package-private method of the column test package that no equipment test can call. No equipment test loads the change.

## Points where compiling is not certain

1. Static imports of package-private members of a package-private class in the same package (`V3SolveReport`, into the calculator and into `V3Admission`). This is legal Java. No member of the calculator or of its nested classes has an imported name.
2. `SolveTelemetry`'s field and constructor parameter are typed `V3ColumnCalculator.NeuralProgress`, now a package-private static nested class. The telemetry calls only that class's public `NewtonTrace` overrides.
3. `V3ColumnCalculator.requestOnlyAdmission` returns `V3Admission.RequestAdmission`, a package-private nested record of a package-private class. Its callers are in the same package.
4. The four constant aliases initialise from another class's compile-time constants. They stay constants (no case label uses them).
5. A grep check (`tmp/d-c1/calls.mjs`) found no unqualified call in the five files that is not declared or imported. Every removed line appears in a new file, except the intended qualifier and modifier edits (`tmp/d-c1/verify.mjs`). The Javadoc `{@link}`s to moved members were re-pointed; the build has no doclint.
