# Wave E briefs (the column: stage 0 in Java, then Rust)

Batch `2026-11-13-codebase-review`, phase 2. Written by the main thread on 2026-11-13. Background: [RUST_COLUMN_PORT_PLAN.md](RUST_COLUMN_PORT_PLAN.md) section 5 (stage 0), [COLUMN_REVIEW.md](COLUMN_REVIEW.md) rows P1, L1, L2, D6, S2, S4, D3, [PHASE2_PLAN.md](PHASE2_PLAN.md) "Decided" rows 7, 8 and 16, and the counts of [wave-b/B-C1_REPORT.md](wave-b/B-C1_REPORT.md). The common rules of [WAVE_B_BRIEFS.md](WAVE_B_BRIEFS.md) apply (no Gradle until "lane free", one commit per item, the report's form, the cadence). Stage 0 items may move a result where their section states the shift (AGENTS.md, "Results that move").

Order of stage 0: E0-1 (this brief: the analytic Jacobian certifies and solves), then E0-2 (the budgets in a physical unit, D6), E0-3 (codes as enums, S2), E0-4 (the inside-out trial measured, L1; the code outside the port, S4), E0-5 (one closure policy and the start route, D3 and decision 16), then the parity fixtures. Briefs E0-2 to E0-5 follow E0-1's report.

## E0-1 The analytic Jacobian certifies and solves: P1 and L2 (thread wave-a1, Opus high)

Lane: `science/column/v3/V3SimultaneousColumnSolver.java`, `V3FiniteDifferenceJacobian.java`, `V3NormalEquations.java`, `V3BlockJacobianAssembler.java`, `V3BlockJacobian.java`, `V3ConvergenceEvidence.java`, in `V3ColumnCalculator.java` only the coarse-scale recovery rung and the Jacobian policies (about 165 to 171 and 1095 to 1135 before B-C1; find them again), `V3SolveScratch.java` (counters), their tests. Not yours: `linalg/**` (the Rust thread ports it as it stands), `thermo/**`, `ColumnSolve`, the network.

### What the solver does today (read at `V3SimultaneousColumnSolver.solve`, 318 to 616)

1. Each iteration evaluates the residual. It is converged when the maximum scaled residual is within the tolerance **and** the last accepted step left evidence that passes the gates (`V3ConvergenceEvidence.satisfiesGates`: log-flow change within the closure, temperature change within 1e-6 K + 1e-9 T, linear backward error within 1e-12).
2. The step it tries first comes from the block Jacobian (`V3BlockJacobianAssembler.assembleLocal`, analytic: closed-form material rows, analytic equilibrium and energy rows, closed-form free-water couplings). An accepted block step records **no** evidence (`V3ConvergenceEvidence.unavailable`, line 486).
3. Only a step from the finite-difference Jacobian records evidence (604 to 606). So a rung that converged on block steps reaches the residual gate with no evidence and calls `verifyFinalNewtonCorrection` (635 to 679): a fresh finite-difference Jacobian (3 colours times (2C + 2) slots times 2 sides of whole-column residuals), one factorization, one more residual evaluation at the corrected state, and up to 8 damped solves behind it; it publishes the **corrected** state.
4. B-C1's counts, factorizations per solve by site (Newton / local block / verify direct / verify damped / damped fallback): medium cold 0 / 17 / 4 / 0 / 0, medium warm 0 / 6 / 2 / 0 / 0, literature cold 2 / 21 / 3 / 0 / 0, literature warm on the classical chain 19 / 153 / 19 / 13 / 0. The block Jacobian already carries the solve; the finite-difference form is paid once per rung only to certify.

### The logic it rests on

Newton's correction at a state x, δ = -J(x)⁻¹ F(x), is the first-order distance from x to the root. With the exact Jacobian that estimate is better than with a central difference (truncation about 1e-9 relative at steps 1e-6 in log flow and 1e-4 K). So "the residual is within the tolerance at x and the full analytic correction at x is within the step gates" is a certificate at least as strong as today's, at the cost of one derivative evaluation per node and phase.

### Step A: an accepted full block step leaves evidence (P1, first half)

- At line 486: when the accepted trial took the full step (`localTrial.step() == 1.0`), record `convergenceEvidence(coordinates, baseCoordinates, localTrial.coordinates(), localSuccess.backwardError(), scaledTolerance)`; a shortened step leaves `unavailable`, as now. (The finite-difference branch records evidence for a shortened step too; do not copy that: a shortened step understates the correction.)
- Expected: a rung whose last block step was full converges at the next iteration's residual gate with no verification. Counter: "verify direct" falls towards 0 on the four bench solves; total factorizations medium cold 21 to about 17.
- Shift: the published state of such a rung is the state before today's verification correction. Bound: one final step, that is the closure in log flow and 1e-6 K + 1e-9 T.

### Step B: the certificate at the state itself (L2)

- Replace `verifyFinalNewtonCorrection`. When the residual is within the tolerance and the evidence does not pass: assemble the block Jacobian at the state, solve for the correction once, and build the evidence from the correction itself (base to base plus correction, the solve's backward error). If it passes the gates, return `Converged` with **this state** (reason text "final Newton correction within the step gates"); the correction is not applied and no candidate is evaluated. If it does not pass, the iteration continues, and that same solve is this iteration's block step (do not assemble twice).
- Deleted with it: `verifiedCandidate`, `verificationCandidateAcceptable` and its near-root ceiling (the ceiling existed because a correction at rounding level cannot pass an Armijo test; the new certificate applies no step, so the question is gone), the damped verification cascade, `RungBudget.verificationDampingSteps` and the budgets that set it, the `VERIFY_DAMPED` counter site.
- Shift: today's verified state is x + δ, the new one x. Bound: δ, within the step gates.
- Pitfall: a block Jacobian that cannot be assembled at the state (a derivative refuses and the per-node probe refuses too) gives no certificate: the iteration continues, as today when verification returns null.

### Step C: the finite-difference Jacobian leaves production (P1, second half)

- The fallback chain behind a block step that fails its line search keeps its order, on the block Jacobian: the damped normal-equation trial (`dampedGaussNewtonTrial`), then the gradient (`normalizedNegativeGradient`). `V3NormalEquations` takes the block Jacobian; its dense normal-product path (137 to 152) goes.
- Goes: `V3FiniteDifferenceJacobian` from `src/main` (it moves to the test tree beside `V3JacobianOracle`; the workspace-factory interface the solver uses moves to a main class first), the one-step Jacobian reuse (`cachedFineJacobian`, `maximumFrozenFineJacobianSteps` and the four retry blocks), the block-step attempt and rejection counters (`maximumLocalBlockAttempts`, `localBlocksDisabled`, `MAXIMUM_CONSECUTIVE_LOCAL_BLOCK_REJECTIONS`), the off-band guard with `JACOBIAN_BAND_STRUCTURE`, the coarse difference scale and its recovery rung in the calculator, `LOCAL_BLOCK_JACOBIAN_MINIMUM_COORDINATES` (read why it exists before you delete it and say so), and the three Jacobian policies become one. The per-node one-sided probe in the assembler stays (a node whose derivative refuses), at the fine scale.
- Failure modes to expect, each a line in the report: a case that converged only through the coarse rung or through finite-difference noise now refuses or takes more iterations; a small column that never used the block Jacobian (below the minimum) takes another path; `solveWithInsideOut` and the predictor entry points that passed a policy.
- A preset, a literature case or a tutorial column that converged before and refuses now is a decision request with the case and its trace. Do not tune a tolerance to recover it.

### Results that move, and what to state

- Every accepted state stays within two final steps of the same root: twice the closure in log flow, 2 (1e-6 K + 1e-9 T). Conservation (the audit's material and energy rows) is unchanged in kind: the audit still decides publication.
- Digests and exact references move (`V3ConvergenceClosureTest`, `V3StageTraceCalculatorTest`, the printed lines of the lane). Re-record each once, after step C, and list old and new with the largest shift per quantity (temperature, flows, duties) against the bound.
- Check what `FORMULATION_REVISION` and the result label stand for (calculator 30 to 52) and whether an accepted-state shift of this size needs their bump; say which and why. The column's saved profiles and seeds are state and are recomputed.
- The learned start's models were trained on accepted profiles of the old path; the shift is far below their accuracy. State that, with the number.

### Counters and tests

- From `V3BandFactorizationCountTest` (P7): the five sites per solve on the four bench inputs, before (B-C1's table) and after each step. Add residual evaluations and derivative evaluations per solve to the same event if they are not counted.
- After "lane free", through `columnGates` with `--tests`: `V3BlockJacobianAssemblerTest` (the oracle agreement), `V3SimultaneousColumnSolverTest`, `V3ColdStartTest`, `V3LiteraturePresetTest`, `V3PumparoundSteamCalculatorTest`, `V3VacuumStartupTest`, `V3StageContinuationTest`, `V3ConvergenceClosureTest`, `V3StageTraceCalculatorTest`, `V3ColumnCalculatorTest`, `V3PathDependentInfeasibilityTest`, `V3BandFactorizationCountTest`, `V3TrayHydraulicsColumnTest`; and the indirect loaders: the sample-sizing classes of `holdGates` and `FlowsheetExamplesTest`.
- Report: `wave-e/E0-1_REPORT.md`: the three steps, the counters, the moved references, the lines removed from `src/main` (the row expects about 350 plus 110) and what the Rust port no longer has to carry.

## Amendments (main thread, 2026-11-13)

1. **E0-1 was written and run, and is not merged: its premise does not hold.** A subagent (Opus high) wrote steps A, B and C on the branch `worktree-agent-a1d551d6db929a025` (A `cdbd79052`, B `801dcb20e`, C `4fb39188f`; `src/main` +321 -962); the main thread ran the 23 column classes of the brief in that worktree.
   - **Step A alone: all 166 tests pass, and nothing changes.** The factorization counts are B-C1's to the digit (medium cold 0 / 17 / 4, medium warm 0 / 6 / 2, literature cold 2 / 21 / 3, literature warm 19 / 153 / 19 / 13). The step that brings the residual inside the tolerance is larger than the step gates, so its evidence never passes; the "verification" is the one further correction the two-gate criterion needs, not an avoidable cost. What L2 could save is the finite-difference Jacobian of that correction, which is in the 5 to 7 % residual and Jacobian share, not in the band solver's 52 to 63 %.
   - **Steps B and C: 14 of 166 tests fail, and they are not reference moves.** With the block Jacobian alone the cold-start sweep stalls (`NONCONVERGENCE`, residual about 1.2e-5 after 128 iterations: nominal, flow 75, feed 548 K and others) or meets a singular matrix at a state that is already at the root (`LINEAR_SINGULAR`, residual 4e-16, iteration 6 or 7); `V3ColumnCalculatorTest`'s pilot, three stage-continuation seeds and two stage-trace cases fail to converge; the manufactured mesh state ends `LINEAR_SINGULAR` where the damped cascade certified it. One case converges an iteration earlier (D: 7 to 6).
   - **Reading.** The finite-difference Jacobian and the damped normal-equation cascade are what carry the solve where the block Jacobian's step stalls and where the Jacobian is singular at the root (a point at its support floor). COLUMN_REVIEW rows P1 and L2 called the block Jacobian exact and the cascade removable; the run refutes both as stated. Why the block step stalls at 1e-5 is not diagnosed (an omitted coupling, or the line search): that needs the block Jacobian compared with the oracle at a stalled state.
   - **Decision.** P1 and L2 are closed as not pursued: no measurable gain on the bench inputs and a real loss of robustness. The finite-difference Jacobian and the cascade stay in production. PHASE2_PLAN decision 7 is revised accordingly. The branch stays for reference and is not merged. A Rust port, if it goes on, carries both Jacobians.
2. **What stage 0 still holds:** D6 (the budgets in a physical unit), S2 (codes as enums), S4 (code outside any port), L1 (the inside-out trial measured), D3 with decision 16 (one closure policy and the start route). None is started.

## E0-3 The column's codes as enums: S2 (subagent, Opus high)

Written by the main thread on 2026-11-13. Review: COLUMN_REVIEW row S2 (its line numbers predate waves A and B: find each place again). Structure only: every solve path, digest, published text, wire byte and saved string stays as it is.

Lane: `science/column/v3/V3ColumnCalculator.java`, `V3SimultaneousColumnSolver.java`, `V3TruncationFallback.java`, the audit and diagnostics records these read (`V3AcceptanceAudit` and its check record, `V3SolverDiagnostics`, the attempt and rung records), new enum files in the same package, their tests. Not yours: `science/column/v3/linalg/**`, `thermo/**`, `ColumnSolve`, `ColumnUnit`, the network, the client, the wire codec (`network/**`) beyond what a compile needs.

What is wrong today: control flow on strings. Examples the review names: `code().equals("STALLED_SUPPORT_FLOOR")`, `family().equals("CONDENSER_PHASE")`, ramp labels, event prefixes, failure-code prefixes (calculator about 305, 1703, 1797, 1813, 1874 to 1880, 2357, 2712 to 2713, 3449 to 3459; solver about 389 to 608; `V3TruncationFallback` 21).

1. **Survey first, and write it into the report**: every string that a branch compares (`equals`, `startsWith`, `contains`, `switch` on a string) in the lane, grouped by what it names: an attempt's outcome code, an audit check's family, a rung or ramp kind, an event prefix. For each: where it is produced, where it is compared, and whether the text leaves the solver (a result record a client shows, a wire field, a digest, a test expectation, a saved profile).
2. **Enums**: `AttemptCode`, `AuditFamily`, `RungKind` (use other names if the survey shows better ones), each constant carrying the exact text it replaces (`text()`), so every record that publishes a string still publishes the same characters. Producers set the enum; comparisons read the enum; the string form is derived only where text is shown, hashed or sent. Where a record is public API with a `String` accessor that tests or the wire read, keep the accessor (derived from the enum) and add the typed one.
3. **Open sets stay strings.** A code that is built from data (a stage number inside a label, a free-form detail) is not an enum: split it into the enum and the detail only if the compare reads the fixed part alone; otherwise leave it and say so.
4. A comparison on a string that no producer in `src/main` sets is dead: delete it and name it in the report.

Bit-for-bit rule: no branch changes which side it takes on any input. A reordered `if` chain, a changed default or a merged case is out of scope. If two producers emit texts that differ only by case or spacing and a compare catches both, keep both texts on the enum (two constants or an alias) and report it; do not normalize.

Pitfalls: digests of result records (a `toString` or a canonical text that includes a code: an enum's `toString` is its name, not the old text, so records must not start printing the enum); `switch` exhaustiveness (keep a default that throws where the old code fell through to a failure); the solve-path string (`solvePath`) is concatenated text that tests match with `contains`: do not touch its characters; `V3StageContinuationTest` prints failed-check lists.

Tests for the main thread (the subagent runs none; `columnGates` unless the report finds otherwise): `V3BoundedSolvePathTest`, `V3StageContinuationTest`, `V3ColdStartTest`, `V3ColumnCalculatorTest`, `V3ConvergenceClosureTest`, `V3StageTraceCalculatorTest`, `V3LiteraturePresetTest`, `V3PumparoundSteamCalculatorTest`, `V3VacuumStartupTest`, `V3PathDependentInfeasibilityTest`, `V3SimultaneousColumnSolverTest`, `V3BandFactorizationCountTest`, `V3LearnedRecoveryTest`, `network/ColumnWireTest`, `FlowsheetExamplesTest`, and every other test that reads a changed accessor. Report: `wave-e/E0-3_REPORT.md`: the survey table, the enums with their texts, what stayed a string and why, the dead comparisons removed, lines changed.

## L1 measured: the inside-out trial is a net loss (main thread, 2026-11-13)

COLUMN_REVIEW row L1: "measure the first rung with and without it; delete it if it saves less than 5 % of a cold solve". Measured at `1dbb7f3cb`, the machine alone, a scratch probe (`tmp/l1-inside-out/L1Probe.java`: `V3ColumnCalculator.calculate` cold, the classical chain, 15 s warm-up and 20 s measured a case), once as built and once with the first rung on `STAGE_LOCAL_BLOCKS` in place of `INSIDE_OUT` (a scratch edit, reverted):

| Dry cold case (Tia Juana light, CDU-17) | With the trial | Without | |
|---|---|---|---|
| 4 stages, 2.5 bar | 0.105 s | 0.015 s | 6.8 times |
| 8 stages | 0.123 s | 0.031 s | 3.9 times |
| 15 stages | 0.169 s | 0.082 s | 2.1 times |
| 30 stages | 0.254 s | 0.170 s | -33 % |
| 30 stages, 1.5 bar top | 0.247 s | 0.168 s | -32 % |
| 30 stages, 1.1 bar top | 1.293 s | 0.907 s | -30 % |

- The trial costs about 0.09 s on every dry cold solve and saves nothing after it: the same path, the same Newton iterations, and the published streams equal in every printed digit (flows to 1e-9 mol/s, temperatures to 1e-6 K) in all six cases.
- It acts only on the first grid of a request with no draw, steam or stage heat, on the classical chain; the bench inputs of PROFILE_REVIEW (both with features, through the learned start) never run it, which is why no profile showed it.
- **Decision: delete it** (about 330 Java lines: `solveWithInsideOut`, the frozen-property evaluator paths, the `INSIDE_OUT` policy and its telemetry line). It goes in after E0-3 merges, because both change the calculator and the solver. Expected to move: nothing published in these six cases; the gate is the column classes' digests and the cold-start sweep.

## E0-4 The inside-out trial leaves the column: L1 (subagent, Opus high)

Written by the main thread on 2026-11-13, after E0-3 merged (`a8050413b`). Decision and measurement: "L1 measured" above. COLUMN_REVIEW row L1 names the places (line numbers predate three waves: find them by text).

Lane: `science/column/v3/**` (not `linalg/**`), their tests, `tools/native-column-default/` (removed whole), `tools/INDEX.md` (its row), `documentation/reference/code-guide/column-v3.md`.

What goes, in one commit:
1. In `V3ColumnCalculator`: the choice of `ContinuationJacobianPolicy.INSIDE_OUT` for the first grid of a request without draw, steam or stage heat (the rung then takes the policy every other rung takes, `STAGE_LOCAL_BLOCKS`); the `INSIDE_OUT` constant and its `case` in the attempt switch; the `insideOut` flag of `SolveTelemetry` and its event line ("Boston-Sullivan inside-out: ...").
2. In `V3SimultaneousColumnSolver`: `solveWithInsideOut`, its outer-pass loop, its constants and the comment at the class head that reserves the trial for cold seeds.
3. In `V3MeshResidualEvaluator` (and wherever else they live): the frozen-property mode the trial alone used (`usesFrozenThermo` and the frozen K and enthalpy snapshot paths). Before you delete a frozen-property path, grep its every caller: delete only what the trial alone reached; a path that the predictor, a continuation or a test of another feature also uses stays, and the report names it.
4. Tests that test the trial itself are deleted (name them in the report); a test of another behaviour that happened to run through the trial keeps its assertions. Any expectation that reads the event line or a solve path containing the trial's text is restated.

Expected results: the first rung now takes block steps from the same seed. On the six dry cold cases of the measurement nothing published moved; elsewhere a digest of a dry cold solve may move inside the solver's closure. A moved expectation is listed in the report with old and new values; do not re-record by guess: leave the old value and name the test, the main thread re-records from the run.

Pitfalls: `ContinuationJacobianPolicy` is passed through several `solveSingleProblem` overloads: keep their signatures unless the parameter becomes single-valued everywhere (then say so, do not remove it in this commit); the native library's self-test and `NativeColumnSolver` do not use the trial (the C++ outer pass is already gone): do not touch `src/main/rust`; the learned start's training export does not use the trial.

Tests for the main thread (`columnGates`): `V3SimultaneousColumnSolverTest`, `V3ColdStartTest`, `V3StageContinuationTest`, `V3ColumnCalculatorTest`, `V3ConvergenceClosureTest`, `V3StageTraceCalculatorTest`, `V3BoundedSolvePathTest`, `V3LiteraturePresetTest`, `V3VacuumStartupTest`, `V3PathDependentInfeasibilityTest`, `V3BandFactorizationCountTest`, `V3LearnedRecoveryTest`, `V3ColumnCodesTest`, `HollandExample32BenchmarkTest`; `test`: `NativeColumnSolverTest`, `FlowsheetExamplesTest`; `holdGates`: `FlowsheetColumnTest`, `SmallCrudeUnitTest`. Report: `wave-e/E0-4_REPORT.md`: lines removed per file, what stayed and why, tests deleted and restated.
