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
