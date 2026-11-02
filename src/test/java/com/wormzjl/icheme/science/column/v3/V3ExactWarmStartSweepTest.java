package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exact-input hot-start companion to {@link V3ColdStartTest}.
 *
 * <p>The negative points are frozen numerical roots captured at 494d3e45; the positive point starts fresh.
 * The hot attempt receives only the state and convergence certificate, while problem resolution, thermodynamic
 * workspaces, residual recomputation, and acceptance audit are all fresh. Nearby-input reuse is deliberately out of
 * scope for this exact-compatibility test. The original two-phase numerical matrix is retained as negative
 * coverage: neither a cold nor hot certificate may override its independent phase rejection. A separate
 * physically valid liquid-only case verifies accepted-state reuse.</p>
 */
@org.junit.jupiter.api.extension.ExtendWith(com.wormzjl.icheme.science.material.Cdu17FixtureExtension.class)
class V3ExactWarmStartSweepTest {
    // A finite duty made these historical numerical roots reproducible. The new independent phase audit
    // exposes their collapsed interiors; retain the roots without requiring the current cold path to find them.
    @Test
    void numericalTwoPhaseMatrixRemainsRejectedAfterFreshExactInputHotStarts() {
        for (V3ColumnInput input : List.of(
                binaryInput(100.0, 550.0, 250_000.0, 750.0, 300.0, 2.0, 50_000.0),
                binaryInput(100.0, 551.0, 250_000.0, 750.0, 300.0, 2.0, 50_000.0),
                binaryInput(100.0, 550.0, 250_000.0, 0.0, 300.0, 2.0, 50_000.0),
                binaryInput(100.0, 550.0, 250_000.0, 750.0, 300.0, 2.0, 20_000.0),
                binaryInput(100.0, 550.0, 250_000.0, 750.0, 300.0, 2.0, 100_000.0))) {
            verifyExactHotStart(input, V3CondenserPhaseBranch.TWO_PHASE, false);
        }
    }

    @Test
    void physicallyAcceptedLiquidOnlyStatePassesFreshExactInputHotStart() {
        // The old 550 K / 50 kW fixture contains collapsed interior phases. This live-boilup case
        // independently passes the stronger physical audit as well as Newton's certificate.
        verifyExactHotStart(binaryInput(100.0, 600.0, 250_000.0, 750.0, 300.0, 2.0, 500_000.0),
                V3CondenserPhaseBranch.LIQUID_ONLY, true);
    }

    private static void verifyExactHotStart(V3ColumnInput input, V3CondenserPhaseBranch branch, boolean accepted) {
            String label = caseLabel(input);
            ColdAttempt cold = accepted ? coldAttempt(input, branch) : capturedAttempt(input, branch);
            V3SimultaneousColumnSolver.Attempt.Converged coldConverged = assertInstanceOf(
                    V3SimultaneousColumnSolver.Attempt.Converged.class, cold.attempt(), () -> label + ": " + cold.attempt());
            assertEquals(accepted, cold.audit().accepted(), () -> label + ": cold audit=" + cold.audit());
            assertTrue(cold.audit().checks().stream().filter(check -> !check.family().equals("CONDENSER_PHASE")
                            && !check.family().equals("INTERIOR_PHASE_IDENTITY"))
                    .allMatch(V3AcceptanceAudit.Check::passed));
            assertTrue(coldConverged.evidence().convergenceEvidence().satisfiesGates(),
                    () -> label + ": cold evidence=" + coldConverged.evidence());

            V3PengRobinsonThermo freshThermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
            V3ColumnProblem freshProblem = V3ColumnProblemResolver.resolve(input, branch);
            V3FlashResult freshFlash = freshThermo.flashTP(input.feedTemperatureKelvin(),
                    freshProblem.nodePressurePascal(freshProblem.topology().feedTrayNumber()),
                    input.feedComponentMolarFlowsMolPerSecond(), freshThermo.newWorkspace());
            V3MeshResidualEvaluator freshEvaluator = new V3MeshResidualEvaluator(
                    freshProblem, freshThermo, freshFlash.molarEnthalpyJoulesPerMol());
            V3SimultaneousColumnSolver.Attempt.Converged hotConverged = assertInstanceOf(
                    V3SimultaneousColumnSolver.Attempt.Converged.class,
                    V3SimultaneousColumnSolver.solve(
                            freshProblem,
                            freshEvaluator,
                            new V3DryMeshCoordinateMap(freshProblem),
                            coldConverged.state(),
                            freshThermo::newWorkspace,
                            coldConverged.evidence().convergenceEvidence(),
                            128,
                            1.0e-8), label + ": exact-input hot solve");
            V3AcceptanceAudit hotAudit = new V3AcceptanceAuditor(
                    freshProblem, freshThermo, freshFlash.molarEnthalpyJoulesPerMol())
                    .audit(hotConverged.state(), freshThermo.newWorkspace(), V3SolveControl.UNBOUNDED);
            assertEquals(0, hotConverged.evidence().iterations(), label);
            assertTrue(hotConverged.evidence().convergenceEvidence().satisfiesGates(),
                    () -> label + ": hot evidence=" + hotConverged.evidence());
            assertEquals(accepted, hotAudit.accepted(), () -> label + ": hot audit=" + hotAudit);
            assertTrue(hotAudit.checks().stream().filter(check -> !check.family().equals("CONDENSER_PHASE")
                            && !check.family().equals("INTERIOR_PHASE_IDENTITY"))
                    .allMatch(V3AcceptanceAudit.Check::passed));
    }

    /** Frozen roots at 494d3e45: keep rejection coverage independent of a changed cold trajectory. */
    static ColdAttempt capturedAttempt(V3ColumnInput input, V3CondenserPhaseBranch branch) {
        try (var stream = V3ExactWarmStartSweepTest.class.getResourceAsStream("/column/invalid-binary-roots.json")) {
            var rows = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(stream,
                    java.nio.charset.StandardCharsets.UTF_8)).getAsJsonArray();
            for (var element : rows) {
                var row = element.getAsJsonObject();
                var source = row.getAsJsonObject("input");
                double duty = source.getAsJsonArray("specifications").get(2).getAsJsonObject().get("watts").getAsDouble();
                double requestedDuty = input.specifications().stream().filter(V3ColumnSpecification.ReboilerDuty.class::isInstance)
                        .map(V3ColumnSpecification.ReboilerDuty.class::cast).findFirst().orElseThrow().watts();
                if (!row.get("branch").getAsString().equals(branch.name())
                        || source.get("feedTemperatureKelvin").getAsDouble() != input.feedTemperatureKelvin()
                        || source.get("stagePressureDropPascal").getAsDouble() != input.stagePressureDropPascal()
                        || duty != requestedDuty) continue;
                var problem = V3ColumnProblemResolver.resolve(input, branch);
                var data = row.getAsJsonObject("state");
                var gson = new com.google.gson.Gson();
                var state = new V3DryMeshState(problem.topology(), problem.activeComponentBasis().componentCount(),
                        gson.fromJson(data.get("liquidComponentFlows"), double[][].class),
                        gson.fromJson(data.get("vaporComponentFlows"), double[][].class),
                        gson.fromJson(data.get("temperaturesKelvin"), double[].class));
                var evidence = gson.fromJson(row.get("evidence"), V3ConvergenceEvidence.class);
                var thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
                double h = thermo.flashTP(input.feedTemperatureKelvin(), problem.nodePressurePascal(problem.topology().feedTrayNumber()),
                        input.feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace()).molarEnthalpyJoulesPerMol();
                var evaluator = new V3MeshResidualEvaluator(problem, thermo, h);
                var residual = evaluator.evaluate(state, thermo.newWorkspace());
                assertTrue(residual.maximumAbsoluteScaledResidual() <= 1e-8);
                var attempt = V3SimultaneousColumnSolver.solve(problem, evaluator, new V3DryMeshCoordinateMap(problem),
                        state, thermo::newWorkspace, evidence, 128, 1e-8);
                return new ColdAttempt(attempt, new V3AcceptanceAuditor(problem, thermo, h)
                        .audit(state, thermo.newWorkspace(), V3SolveControl.UNBOUNDED));
            }
            throw new AssertionError("Missing captured binary root");
        } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }

    private static ColdAttempt coldAttempt(V3ColumnInput input, V3CondenserPhaseBranch branch) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input, branch);
        V3FlashResult flash = thermo.flashTP(input.feedTemperatureKelvin(),
                problem.nodePressurePascal(problem.topology().feedTrayNumber()), input.feedComponentMolarFlowsMolPerSecond(),
                thermo.newWorkspace());
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, thermo, flash.molarEnthalpyJoulesPerMol());
        V3ColumnInitializer.Seed seed = V3ColumnInitializer.initialize(problem, thermo, thermo.newWorkspace(),
                branch == V3CondenserPhaseBranch.TWO_PHASE ? V3ColumnInitializer.Mode.MATERIAL_CLOSED
                        : V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE);
        // Preserve the original two-phase numerical matrix; the accepted liquid case uses production's cold policy.
        V3SimultaneousColumnSolver.Attempt attempt = branch == V3CondenserPhaseBranch.TWO_PHASE
                ? V3SimultaneousColumnSolver.solve(problem, evaluator, new V3DryMeshCoordinateMap(problem), seed.state(),
                        thermo::newWorkspace, V3ConvergenceEvidence.unavailable(), 128, 1.0e-8, V3FiniteDifferenceJacobian.DifferenceScale.FINE, V3SolveControl.UNBOUNDED)
                : V3SimultaneousColumnSolver.solveWithContinuationLocalBlocks(
                problem, evaluator, new V3DryMeshCoordinateMap(problem), seed.state(), thermo::newWorkspace,
                128, 1.0e-8, V3SolveControl.UNBOUNDED, V3SimultaneousColumnSolver.NewtonTrace.NONE);
        V3AcceptanceAuditor auditor = new V3AcceptanceAuditor(problem, thermo, flash.molarEnthalpyJoulesPerMol());
        V3AcceptanceAudit audit = auditor.audit(attempt.state(), thermo.newWorkspace(), V3SolveControl.UNBOUNDED);
        if (!(attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged)
                || (branch != V3CondenserPhaseBranch.TWO_PHASE && !audit.accepted())) {
            V3DryMeshState prepared = V3SimultaneousColumnSolverTest.projectedOrNull(problem, thermo, attempt.state());
            if (prepared != null) {
                V3SimultaneousColumnSolver.Attempt recovered = V3SimultaneousColumnSolver.solveWithContinuationLocalBlocks(
                        problem, evaluator, new V3DryMeshCoordinateMap(problem), prepared,
                        thermo::newWorkspace, 128, 1.0e-8, V3SolveControl.UNBOUNDED, V3SimultaneousColumnSolver.NewtonTrace.NONE);
                V3AcceptanceAudit recoveredAudit = auditor.audit(recovered.state(), thermo.newWorkspace(), V3SolveControl.UNBOUNDED);
                if (recovered instanceof V3SimultaneousColumnSolver.Attempt.Converged
                        && (branch == V3CondenserPhaseBranch.TWO_PHASE || recoveredAudit.accepted())) {
                    attempt = recovered;
                    audit = recoveredAudit;
                }
            }
        }
        if (!(attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged converged
                && converged.evidence().convergenceEvidence().satisfiesGates()
                && (branch == V3CondenserPhaseBranch.TWO_PHASE || audit.accepted()))) {
            V3SimultaneousColumnSolver.Attempt coarseAttempt = V3SimultaneousColumnSolver.solve(
                    problem, evaluator, new V3DryMeshCoordinateMap(problem), seed.state(), thermo::newWorkspace,
                    V3ConvergenceEvidence.unavailable(), 128, 1.0e-8,
                    V3FiniteDifferenceJacobian.DifferenceScale.COARSE, V3SolveControl.UNBOUNDED);
            V3AcceptanceAudit coarseAudit = auditor.audit(coarseAttempt.state(), thermo.newWorkspace(), V3SolveControl.UNBOUNDED);
            if (coarseAttempt instanceof V3SimultaneousColumnSolver.Attempt.Converged coarseConverged
                    && coarseConverged.evidence().convergenceEvidence().satisfiesGates()
                    && (branch == V3CondenserPhaseBranch.TWO_PHASE || coarseAudit.accepted())) {
                attempt = coarseAttempt;
                audit = coarseAudit;
            }
        }
        if (!(attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged certified
                && certified.evidence().convergenceEvidence().satisfiesGates()
                && (branch == V3CondenserPhaseBranch.TWO_PHASE || audit.accepted()))) {
            // Production's cold policy ends in a fresh material-closed seed when the sequential material/VLE
            // preconditioner leaves a stage dry; on this two-tray point that is the strategy that reaches the
            // accepted liquid-only state, in the calculator as well as here.
            V3ColumnInitializer.Seed materialClosed = V3ColumnInitializer.initialize(
                    problem, thermo, thermo.newWorkspace(), V3ColumnInitializer.Mode.MATERIAL_CLOSED);
            V3SimultaneousColumnSolver.Attempt closedAttempt = V3SimultaneousColumnSolver.solve(
                    problem, evaluator, new V3DryMeshCoordinateMap(problem), materialClosed.state(),
                    thermo::newWorkspace, V3ConvergenceEvidence.unavailable(), 128, 1.0e-8, V3FiniteDifferenceJacobian.DifferenceScale.FINE, V3SolveControl.UNBOUNDED);
            V3AcceptanceAudit closedAudit = auditor.audit(closedAttempt.state(), thermo.newWorkspace(), V3SolveControl.UNBOUNDED);
            if (closedAttempt instanceof V3SimultaneousColumnSolver.Attempt.Converged closedConverged
                    && closedConverged.evidence().convergenceEvidence().satisfiesGates()
                    && (branch == V3CondenserPhaseBranch.TWO_PHASE || closedAudit.accepted())) {
                attempt = closedAttempt;
                audit = closedAudit;
            }
        }
        return new ColdAttempt(attempt, audit);
    }

    private static String caseLabel(V3ColumnInput input) {
        StringBuilder label = new StringBuilder("feedTemperatureK=").append(input.feedTemperatureKelvin())
                .append(", topPressurePa=").append(input.topPressurePascal())
                .append(", stagePressureDropPa=").append(input.stagePressureDropPascal());
        for (V3ColumnSpecification specification : input.specifications()) {
            switch (specification) {
                case V3ColumnSpecification.CondenserOutletTemperature temperature ->
                        label.append(", condenserTemperatureK=").append(temperature.kelvin());
                case V3ColumnSpecification.OrganicRefluxRatio reflux -> label.append(", refluxRatio=").append(reflux.ratio());
                case V3ColumnSpecification.ReboilerDuty duty -> label.append(", reboilerDutyW=").append(duty.watts());
            }
        }
        return label.toString();
    }

    private static V3ColumnInput binaryInput(
            double feedFlow,
            double feedTemperature,
            double topPressure,
            double pressureDrop,
            double condenserTemperature,
            double reflux,
            double reboilerDuty) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("icheme:cdu17_tjl_acs2018");
        double[] feedFlows = new double[thermo.componentBasis().componentCount()];
        feedFlows[6] = feedFlow / 2.0;
        feedFlows[13] = feedFlow / 2.0;
        return V3TestInputs.dry(V3ColumnInput.SCHEMA_VERSION, thermo.packageId(), "test:registered-pr-binary",
                thermo.componentBasis(), feedFlows, feedTemperature, 2, 1, topPressure, pressureDrop, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(condenserTemperature),
                        new V3ColumnSpecification.OrganicRefluxRatio(reflux),
                        new V3ColumnSpecification.ReboilerDuty(reboilerDuty)));
    }

    record ColdAttempt(V3SimultaneousColumnSolver.Attempt attempt, V3AcceptanceAudit audit) {}
}
