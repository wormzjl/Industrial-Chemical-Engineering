# E0-3 The column's codes as enums (S2): report

Brief: [WAVE_E_BRIEFS.md](../WAVE_E_BRIEFS.md), section "E0-3". Review row: [COLUMN_REVIEW.md](../COLUMN_REVIEW.md) S2.
Subagent (Opus high), 2026-11-13. Structure only: no solve path, digest, published text, wire byte or saved string changes.
Written without a compile or a test run; the main thread runs both (see "Tests for the main thread").

## Summary

1. **Survey** (step 1, tables below): 27 compare sites in the lane: 8 on attempt codes, 3 on audit families, 6 on ramp
   kinds, 10 on open texts (event prefixes, a detail prefix, exception messages). All three typed groups are done whole.
2. **Enums** (step 2), each constant carrying its old text as `text()`, and `toString()` returning that text too, so an
   accidental concatenation still prints the old characters:
   - `V3AttemptCode` (package-private, 10 constants): `STATE_DOMAIN`, `MAX_ITERATIONS`, `STALLED`,
     `STALLED_SUPPORT_FLOOR`, `JACOBIAN_BAND_STRUCTURE`, `LINEAR_SINGULAR`, `LINEAR_ILL_CONDITIONED`,
     `LINEAR_BACKWARD_ERROR_EXCEEDED`, `LINE_SEARCH_EXHAUSTED`, `INTERNAL_INVARIANT` (texts equal the names).
     `linear(FailureCode)` replaces `"LINEAR_" + failure.code()` by an exhaustive switch over the band solver's three codes.
     `Attempt.Failure` now holds `attemptCode()`; its `code()` stays, as the text. `failureCode(V3AttemptCode)` is an
     exhaustive switch with no default (a new constant fails the compile) and maps every producer as the prefixes did.
   - `V3AuditFamily` (public, 17 constants: the 16 auditor families and `UNAVAILABLE`; texts equal the names).
     `Check` keeps `String family`: `V3InspectionCodec` decodes it from text (wire and NBT), the client shows it, and
     fixtures build checks with families of their own (`"material"`, `"TEST"`, `"HOLLAND_BENCHMARK"`). Producers in
     `src/main` call the new overloads `Check.pass/fail(V3AuditFamily, ...)`; the calculator's three compares read
     `check.hasFamily(V3AuditFamily)`, which is `family.equals(text())`, the old test character for character.
   - `V3RampKind` (package-private): `STEAM` `steam-ramp`, `WET` `wet-ramp`, `HEAT` `heat-ramp`, `DRAW` `draw-ramp`,
     `COUPLED` `coupled-ramp`. `RampStep` holds `kind()`; `pathLabel()` stays, as the text, and is the only thing the
     solve path and the energy-shift note concatenate. The first-rung test compares kinds, starting from `null` where it
     started from `""` (no label was empty, so the first rung is still always "first"). The three `*_RAMP_LABEL`
     constants are gone.
3. **Open sets stay strings** (step 3, no code change): the event prefixes (`stage-trace support:`, `condenser phase
   transition`, `flash-trace `, `stage-trace cutoff=`) and the event de-duplication `contains`; the `warning: ` detail
   prefix; the off-band exception messages. Each is text with data after a fixed head. The compares do read the head
   alone, so a split is allowed by the brief, but the events are a public `List<String>` that tests match by `contains`
   and `startsWith` and that `V3TruncationFallback` re-publishes; a typed event record is a change of
   `V3SolverDiagnostics`, not of a compare. The off-band messages are an identity test on an `IllegalStateException`'s
   message against a named constant; a typed exception is a change of `V3NormalEquations` and the solver's catch
   blocks. Left for the Rust result block's design.
4. **Dead comparison removed** (step 4): the `CONVERGENCE_EVIDENCE` prefix in `failureCode`; no producer in `src/main`,
   `src/test` or `tools/` emits it (its own commit, before the enums).
5. **Lines changed** (`src/main`): +242 -101 over 8 files, of which +122 the three new enum files; `src/test`: +72 -3
   (the new `V3ColumnCodesTest`, three lines of `V3StageContinuationTest`). Code guide `column-v3.md`: three lines.
6. **What moves**: nothing a test reads. Every branch takes the same side (the text-to-constant map is one to one, and
   each compare tests the same set of producers). Every published, hashed, sent or saved string keeps its characters:
   `Check.family` is still the same text, `code()` and `pathLabel()` give the old texts, `solvePath` and the events are
   built from them. One text changes, in no output: the `toString` of the package-private `Attempt.Failure` record
   reads `attemptCode=` where it read `code=` (assertion messages only; no test or digest reads it).
7. **Batch README**: no history line written here; the main thread owns `README.md` and `documentation/INDEX.md`.

## Step 1: survey

Every string a branch compares (`equals`, `startsWith`, `contains`, `matches`, a `switch` on a string) in the lane:
`V3ColumnCalculator`, `V3SimultaneousColumnSolver`, `V3TruncationFallback`, `V3AcceptanceAudit`, `V3AcceptanceAuditor`,
`V3SolverDiagnostics`. Line numbers are those of `1dbb7f3cb`. No `switch` on a string exists in the lane. 27 compare
sites in all (8 attempt codes, 3 families, 6 ramp kinds, 10 open texts); compares of records (`input.equals`, `packageId().equals`, `budget.equals`, revision tokens) and
`modelId.matches` (an input validation) are not codes and are left out.

### Attempt outcome codes (`V3SimultaneousColumnSolver.Attempt.Failure.code`)

| Text | Produced | Compared | Leaves the solver |
|---|---|---|---|
| `STATE_DOMAIN` | solver 389 | calculator 3486 (`startsWith`, to NONCONVERGENCE) | progress listener `finishedAttempt(String)` (calculator 1699, `tools/neural-budget`); tests |
| `MAX_ITERATIONS` | solver 424 | calculator 3485 (prefix) | same; `V3StageContinuationTest` |
| `STALLED` | solver 446 | calculator 3487 (prefix) | same; `V3SimultaneousColumnSolverTest` (`assertEquals("STALLED", ...)`) |
| `STALLED_SUPPORT_FLOOR` | solver 455, 537, 573 | calculator 1716 (`equals`: the release), 3487 (prefix `STALLED`) | same |
| `JACOBIAN_BAND_STRUCTURE` | solver 522 | calculator 3482 (prefix `JACOBIAN_`, to LINEAR_SOLVE_FAILURE) | same |
| `LINEAR_` + band `FailureCode` (`SINGULAR`, `ILL_CONDITIONED`, `BACKWARD_ERROR_EXCEEDED`) | solver 551 | calculator 3482 (prefix `LINEAR_`) | same; `V3SimultaneousColumnSolverTest` (`startsWith("LINEAR_")`) |
| `LINE_SEARCH_EXHAUSTED` | solver 589 | calculator 3485 (prefix `LINE_SEARCH`) | same; `V3SimultaneousColumnSolverTest` (`startsWith("LINE_SEARCH")`) |
| `INTERNAL_INVARIANT` | solver 610 | none (falls to INTERNAL_ERROR) | same |
| `CONVERGENCE_EVIDENCE*` | **no producer** | calculator 3486 (prefix) | dead |

The attempt code never reaches `V3ColumnOutcome`, the wire, a digest or a save: `failureCode` maps it to
`V3SolverFailureCode` (an enum already) and the failure summary is built from `Evidence.termination`, not from the code.
The record's `toString` (assertion messages only) is the one place the component name shows.

### Audit check families (`V3AcceptanceAudit.Check.family`)

| Text | Produced | Compared | Leaves the solver |
|---|---|---|---|
| `LOCAL_COMPONENT_BALANCE`, `EQUILIBRIUM`, `ENERGY_BALANCE` | auditor 86 to 91 (`maximumFamily`) | none in the lane | result audit |
| `GLOBAL_ENERGY_BALANCE`, `CONDENSER_ENERGY_BALANCE`, `INTERIOR_PHASE_IDENTITY`, `WATER_PROFILE`, `WATER_BALANCE`, `CARRY_OVER`, `FREE_WATER_SPLIT`, `SIDE_DRAW_SPLIT`, `FINITE_TOPOLOGY`, `TRUNCATION_MASS_DEFECT`, `PHASE_TRUNCATION_DEFECT` | auditor 191 to 727 | none in the lane | result audit |
| `WATER_DEW_POINT` | auditor 458 to 476 | calculator 311 (`equals`: the advisory neural candidate) | result audit; `tools/neural/V3WetQualificationProbe` |
| `CONDENSER_PHASE` | auditor 742 to 824 | calculator 1817 (`equals`), 1833 (`!equals`) | result audit |
| `UNAVAILABLE` | calculator 754, 3391, 3462 (`failedAudit`) | none | failure audit |

Every family leaves the solver: `V3InspectionCodec` writes `family()` as UTF text (96 chars) on the wire and in NBT and
decodes it back into a `Check` from that text; `ColumnGlass` shows it; `V3TruncationFallback` 23 writes it into a
published event (`audit=<family>`); tests and tool fixtures build checks with their own families (`"material"`,
`"TEST"`, `"HOLLAND_BENCHMARK"`) and compare the text (`family().equals`) in about 40 places.

### Ramp kinds (`V3ColumnCalculator.RampStep.pathLabel`)

| Text | Produced | Compared | Leaves the solver |
|---|---|---|---|
| `heat-ramp` (`HEAT_RAMP_LABEL`) | calculator 2431 | 1895 (`heatRung`), 2378 | `solvePath` (2080, concatenated), energy-shift note (2310) |
| `steam-ramp` (`STEAM_RAMP_LABEL`) | 2355, 2426 | 1899 (`steamRung`) | same |
| `wet-ramp` (`WET_RAMP_LABEL`) | 2355 | 1899 (`steamRung`) | same |
| `draw-ramp` | 2349, 2362, 2440 | 2377 | same |
| `coupled-ramp` | 1980 | none | same |
| previous label (`""` at start) | 2372 | 2374 (`!equals(previousPath)`: the first rung of a kind) | no |

`solvePath` is matched with `contains` by tests and is bounded to 128 characters; only `pathLabel()` text enters it.

### Event prefixes and other open texts

| Text | Produced | Compared | Leaves the solver |
|---|---|---|---|
| `stage-trace support: <note>` | calculator 1789 | calculator 2732 (`startsWith`) | diagnostics events |
| `condenser phase transition...` (three forms) | calculator 1835, 1843, 1849 | calculator 2732 (`startsWith`) | diagnostics events |
| `flash-trace cutoff=...` | calculator 3080 | calculator 2733 (`startsWith("flash-trace ")`) | diagnostics events |
| `stage-trace cutoff=...` | calculator 3069 | `V3TruncationFallback` 19 (`startsWith`) | diagnostics events |
| any event text | (the list above) | calculator 2735 (`contains`: de-duplication) | diagnostics events |
| check detail `warning: <text>` | auditor 597 | auditor 556 (`startsWith`) | result audit detail |
| exception message `NEWTON_OFF_BAND_MESSAGE`, `DAMPED_NORMAL_OFF_BAND_MESSAGE` | solver 757; `V3NormalEquations` 161 | solver 521, 872, 885 (`equals`) | no (rethrown otherwise) |

## Tests for the main thread

The calculator, the solver and the auditor changed, and every column test loads them, so the affected set of
`columnGates` is the whole group; the run below is the brief's list plus the readers of a changed accessor. What
each run proves: the code compiles, every reference stays exact (no reference is re-recorded; any moved digest,
printed line or count is a defect of this change, not a move), and the typed codes give the old texts.

| Gradle task | Classes | Why |
|---|---|---|
| `columnGates --tests` | `V3ColumnCodesTest` (new) | the enum texts, `failureCode` on every code, `linear` on every band code, the `Check` overloads and `hasFamily` |
| `columnGates --tests` | `V3BoundedSolvePathTest`, `V3StageContinuationTest`, `V3ColdStartTest`, `V3ColumnCalculatorTest`, `V3ConvergenceClosureTest`, `V3StageTraceCalculatorTest`, `V3LiteraturePresetTest`, `V3PumparoundSteamCalculatorTest`, `V3VacuumStartupTest`, `V3PathDependentInfeasibilityTest`, `V3SimultaneousColumnSolverTest`, `V3BandFactorizationCountTest`, `V3LearnedRecoveryTest` | the brief's list: solve paths, digests, events, failure codes, `Attempt.Failure.code()` |
| `columnGates --tests` | `V3TruncationFallbackTest`, `V3CondenserPhaseAuditTest`, `V3AcceptanceAuditorLimitsTest`, `V3CarryOverEntrainmentTest`, `V3SideDrawAuditTest`, `V3PhaseTruncationTest`, `V3FreeWaterTrayTest`, `V3GradePresetCarryOverTest`, `V3TraceFloorSupportTest`, `V3PumparoundCalculatorTest`, `V3SideDrawCalculatorTest` | read audit families the auditor now sets through the enum, or the fallback's `audit=` event |
| `columnGates --tests` (or `v3HollandBenchmark`) | `HollandExample32BenchmarkTest` | reads `Attempt.Failure.code()` |
| `test --tests` | `com.wormzjl.icheme.network.ColumnWireTest`, `com.wormzjl.icheme.network.V3InspectionCodecTest`, `com.wormzjl.icheme.runtime.fluid.ColumnProbeTest`, `com.wormzjl.icheme.client.gui.flowsheet.FlowsheetExamplesTest` | the wire and NBT form of the audit (family as text), checks built from strings, the examples' column solves |
| `holdGates --tests` | `FlowsheetColumnTest`, `ColumnEjectorTest`, `CrudeUnitSampleTest`, `SmallCrudeUnitTest`, `SmallUnitRecordingTest` | the indirect loaders that solve columns (sample sizing) |

`equipmentGates` is not affected: its inputs hold none of the changed classes. `tools/` sources that compile against
the changed classes and still compile unchanged: `tools/neural/V3WetQualificationProbe.java` and
`tools/v3-cold-core-benchmark/.../V3ColdCoreBenchmarkWorker.java` (`family()` stays a `String`),
`tools/neural-budget/java/V3BudgetTraceProbe.java` (the progress listener still takes the code as a `String`).
