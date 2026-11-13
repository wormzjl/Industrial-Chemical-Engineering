# E0-4 The inside-out trial leaves the column (L1): report

Brief: [WAVE_E_BRIEFS.md](../WAVE_E_BRIEFS.md), sections "L1 measured" and "E0-4". Review row: [COLUMN_REVIEW.md](../COLUMN_REVIEW.md) L1.
Subagent (Opus high), 2026-11-13, on `claude/codebase-review-refactor-8a85ef` at `32cfe2d89`. Removal commit `358ddd9f9`.
Written without a compile or a test run; the main thread runs both (see "Tests for the main thread").

## Summary

1. **What changed.** `solveStageContinuation` passes `STAGE_LOCAL_BLOCKS` on every grid rung. Before, the first grid of a request with no draw, steam or stage heat and with 4 or more stages took `INSIDE_OUT`. That rung now takes the stage-local block steps, with the frozen fine Jacobian reuse, from the same seed. This is the measured form of "L1 measured". The support and wet-set refresh attempts of that rung change in the same way.
2. **What went** (each grep covered `src/main`, `src/test`, `src/fluidGameTest`, `src/mcpCompat` and the Java under `tools/`):
   - `ContinuationJacobianPolicy.INSIDE_OUT` and its `case`. Grep `INSIDE_OUT`: only `V3ColumnCalculator`.
   - `SolveTelemetry.insideOut`, its constructor parameter and its event line. Grep `insideOut\|Boston-Sullivan`: only the calculator, plus three `tools/native-column-default` probes that went with the folder.
   - `V3SimultaneousColumnSolver.solveWithInsideOut`, its two outer passes, `INSIDE_OUT_TRIGGER_RESIDUAL` and the class-head comment. Grep: the calculator's case, the deleted test and `tools/native-column-default/probes/InsideOutProbeTest.java`.
   - In `V3MeshResidualEvaluator`: `frozenThermo`, the private snapshot constructor, `captureThermoSnapshot`, `withFrozenThermo`, `usesFrozenThermo`, the nested `ThermoSnapshot` (with `Phase`, the temperature secant and the 25 K trust-region refusal) and the two frozen branches in `nodeProperties`. Grep `frozenThermo\|ThermoSnapshot\|captureThermoSnapshot\|withFrozenThermo\|usesFrozenThermo`: only the evaluator itself and `solveWithInsideOut`. No predictor, continuation, learned-start export or fixture used them.
   - `tools/native-column-default/` (8 files) and its `tools/INDEX.md` row.
3. **What stayed, and why:**
   - `providesLocalDerivatives` and `localDerivatives` now read only `thermo instanceof V3ThermoDerivatives`. The frozen guard is gone; the instanceof test stays because the manufactured test thermo models are not differentiable.
   - `MAXIMUM_FROZEN_FINE_JACOBIAN_STEPS` and `usesFrozenFineJacobian` stay. Despite the name, they are the reuse of one fine FD Jacobian between local-block steps (`STAGE_LOCAL_BLOCKS`, `PRESSURE_LOCAL_PREDICTOR`), not the frozen-property mode.
   - `ContinuationJacobianPolicy` keeps three values (`NONE`, `STAGE_LOCAL_BLOCKS`, `PRESSURE_LOCAL_PREDICTOR`), so every `solveSingleProblem` signature stays.
   - The 11-argument `V3SimultaneousColumnSolver.solve(..., control, trace)` stays: the `NONE` case and the tests use it.
   - `V3TestInputs.cdu17BoilingBinary` now has no caller (only the deleted test used it). It is a general fixture, a live-boilup binary, so I kept it. Delete it if you prefer.
   - Two unused imports in the evaluator (`java.util.ArrayList`, `java.util.List`) were already unused before this change. I left them.
   - `src/main/rust` and `NativeColumnSolver` are not touched.
4. **Tests.**
   - Deleted: `V3SimultaneousColumnSolverTest.insideOutFrozenPropertyPredictorStillRequiresStrictPrCertification`, which tested the trial itself.
   - Restated: the comment of `NativeColumnSolverTest.aColumnPublishesTheSameBitsWithTheBandSolveInTheLibrary`. Its 4-tray dry column is now "a dry cold solve on the classical chain"; its assertions are unchanged.
   - No test or resource reads the removed event line: a grep of `Boston\|inside-out\|frozen K/H` over `src/test`, `src/test/resources`, `src/fluidGameTest` and `examples` finds nothing.
5. **Expected to move: nothing pinned.** I found no test that pins a value from a dry cold solve with 4 or more stages other than the cases measured in "L1 measured". I re-recorded nothing; any value that moves is for the main thread to re-record from the run.
6. **Docs and changelog.**
   - Code guide `column-v3.md`, Continuations: every grid rung takes stage-local block steps, and there is no frozen-property or inside-out pass.
   - `CHANGELOG.md` `[Unreleased]` Changed: one line with the measured gain.
   - The batch README history line is left to the main thread.

## Expectations that may move (left at their old value)

| Test | Field | Why |
|---|---|---|
| `V3ConvergenceClosureTest` case A (dry CDU17, 30 stages) | kind `SUCCESS`, iterations 0, solve path `cold/stage-continuation/4-8-15-30/fine-fd/liquid-only-condenser`, digest | Its first rung lost the trial. It is the measured 30-stage case, so I expect no move |
| `NativeColumnSolverTest` column (4 trays, dry) | none pinned (off against band in one run) | Both sides take the same new path |
| `V3ColdStartTest` real-crude matrix (4 to 30 stages) | report only; no failure may be `INTERNAL_ERROR` | Outcomes are printed, not pinned |
| `FlowsheetExamplesTest`, `FlowsheetColumnTest`, `SmallCrudeUnitTest` | any digest or stream of a dry column with 4 or more stages and no draw, steam or stage heat | Can move inside the closure if such a sheet solves cold on the classical chain |
| 2-stage pilots (`V3ColdStartTest` setup matrix, 551 K root) | none | They had fewer than 4 stages, so they never took the trial |

## Tests for the main thread

- `columnGates`: `V3SimultaneousColumnSolverTest`, `V3ColdStartTest`, `V3StageContinuationTest`, `V3ColumnCalculatorTest`, `V3ConvergenceClosureTest`, `V3StageTraceCalculatorTest`, `V3BoundedSolvePathTest`, `V3LiteraturePresetTest`, `V3VacuumStartupTest`, `V3PathDependentInfeasibilityTest`, `V3BandFactorizationCountTest`, `V3LearnedRecoveryTest`, `V3ColumnCodesTest`, `HollandExample32BenchmarkTest`.
- `test`: `NativeColumnSolverTest`, `FlowsheetExamplesTest`.
- `holdGates`: `FlowsheetColumnTest`, `SmallCrudeUnitTest`.
- No other test referenced a removed symbol. The two test classes that did, `V3SimultaneousColumnSolverTest` and `NativeColumnSolverTest`, are already in the lists.

## References left in place

- `tools/codebase-review-bench/JfrSummary.java:42`: `InsideOut` in a JFR frame-grouping regex. It is a pattern, not a symbol, and it is harmless; it can go in the bench's next edit.
- Historical documents still link to the removed `tools/native-column-default/`: `documentation/2026-10-28-native-column-default/README.md` and `NATIVE_DEFAULT_REVIEW.md`, `2026-10-28-column-operation/MERGE_REVIEW.md`, `2026-11-02-dry-ice-cost/MERGE_REVIEW.md`, `2026-11-02-test-suite-optimization/parts/column-time.md`, and this batch's `INVENTORY.md`, `MERGE_PREP_REVIEW.md`, `PHASE2_PLAN.md`, `README.md` and `rust/RUST_2B_REPORT.md`. They are history, so I did not edit them.
