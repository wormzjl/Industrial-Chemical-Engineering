package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.icheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.icheme.science.column.v3.thermo.V3FugacityResult;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.column.v3.thermo.V3ThermoModel;
import com.wormzjl.icheme.science.column.v3.thermo.V3ThermoWorkspace;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.extension.ExtendWith(com.wormzjl.icheme.science.material.Cdu17FixtureExtension.class)
class V3SimultaneousColumnSolverTest {
    /** The default fallbacks without the damped certificate cascade: a rung whose state is a seed. */
    private static final V3SimultaneousColumnSolver.RungBudget NO_VERIFICATION_CASCADE =
            new V3SimultaneousColumnSolver.RungBudget(V3SimultaneousColumnSolver.RungBudget.DEFAULT.maximumDampingSteps(),
                    true, 0, 0, 0.0, 0.0);

    @Test
    void dampedBandedNewtonRecoversTheManufacturedFullMeshStateOnlyThroughBothAcceptanceGates() {
        V3ColumnProblem problem = problem();
        NewtonManufacturedThermo thermo = new NewtonManufacturedThermo();
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, thermo, 0.0);
        V3DryMeshCoordinateMap coordinates = new V3DryMeshCoordinateMap(problem);
        V3DryMeshState exact = exactState(problem.topology());
        double[] perturbedCoordinates = coordinates.encode(exact);
        for (int index = 0; index < perturbedCoordinates.length; index++) {
            perturbedCoordinates[index] += index < 24 ? ((index % 5) - 2) * 0.02 : (index % 2 == 0 ? 0.5 : -0.5);
        }
        V3DryMeshState perturbed = coordinates.decode(perturbedCoordinates);
        AtomicInteger traceSamples = new AtomicInteger();

        V3SimultaneousColumnSolver.Attempt attempt = V3SimultaneousColumnSolver.solve(
                problem, evaluator, coordinates, perturbed, thermo::newWorkspace, V3ConvergenceEvidence.unavailable(),
                16, 1.0e-9, V3FiniteDifferenceJacobian.DifferenceScale.FINE, V3SolveControl.UNBOUNDED,
                (iteration, residual, merit) -> traceSamples.incrementAndGet());

        assertTrue(attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged, attempt::toString);
        assertTrue(traceSamples.get() > 0);
        V3SimultaneousColumnSolver.Attempt.Converged converged = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class, attempt);
        assertTrue(converged.evidence().iterations() > 0);
        assertTrue(converged.evidence().maximumScaledResidual() <= 1.0e-9);
        assertTrue(converged.evidence().convergenceEvidence().satisfiesGates());
        V3MeshResidual finalResidual = evaluator.evaluate(converged.state(), thermo.newWorkspace());
        assertTrue(finalResidual.maximumAbsoluteScaledResidual() <= 1.0e-9);
        V3AcceptanceAudit audit = new V3AcceptanceAuditor(problem, thermo, 0.0).audit(converged.state(), thermo.newWorkspace(), V3SolveControl.UNBOUNDED);
        assertTrue(audit.accepted());
        V3ConvergenceEvidence convergenceEvidence = converged.evidence().convergenceEvidence();
        V3ColumnResult result = V3TestInputs.stateless(
                problem, new V3InputDigest("0".repeat(64)), audit, convergenceEvidence);
        V3SolverDiagnostics diagnostics = new V3SolverDiagnostics(
                0, converged.evidence().iterations(), 1, 1, converged.evidence().maximumScaledResidual(), 0.0,
                "manufactured-newton", List.of(), audit, convergenceEvidence);
        V3ColumnOutcome.Success success = new V3ColumnOutcome.Success(result, diagnostics);
        assertEquals(convergenceEvidence, success.result().convergenceEvidence());
    }

    @Test
    void registeredTiaJuanaPrNewtonAttemptIsBoundedAndRecordsItsActualConvergenceState() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("icheme:cdu17_tjl_acs2018");
        V3CrudeFeed crude = thermo.crudeFeed("icheme:tia_juana_light");
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(realCrudeInput(crude), V3CondenserPhaseBranch.TWO_PHASE);
        V3FlashResult feedFlash = thermo.flashTP(problem.input().feedTemperatureKelvin(),
                problem.nodePressurePascal(problem.topology().feedTrayNumber()), crude.moleFractions(), thermo.newWorkspace());
        V3ColumnInitializer.Seed seed = V3ColumnInitializer.initialize(problem, thermo, thermo.newWorkspace());
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                problem, thermo, feedFlash.molarEnthalpyJoulesPerMol());
        V3SimultaneousColumnSolver.Attempt attempt = V3SimultaneousColumnSolver.solve(
                problem, evaluator, new V3DryMeshCoordinateMap(problem), seed.state(), thermo::newWorkspace, V3ConvergenceEvidence.unavailable(), 128, 1.0e-8, V3FiniteDifferenceJacobian.DifferenceScale.FINE, V3SolveControl.UNBOUNDED);

        V3MeshResidual terminalResidual = evaluator.evaluate(attempt.state(), thermo.newWorkspace());
        System.out.println("V3 real crude Newton outcome: " + attempt + "; residual families="
                + maximumResidualsByFamily(terminalResidual));
        assertTrue(Double.isFinite(attempt.evidence().maximumScaledResidual()));
        assertTrue(Double.isFinite(attempt.evidence().scaledMerit()));
        if (attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged converged) {
            assertTrue(converged.evidence().convergenceEvidence().satisfiesGates());
            assertTrue(new V3AcceptanceAuditor(problem, thermo, feedFlash.molarEnthalpyJoulesPerMol())
                    .audit(converged.state(), thermo.newWorkspace(), V3SolveControl.UNBOUNDED).accepted());
        } else {
            V3SimultaneousColumnSolver.Attempt.Failure failure = assertInstanceOf(
                    V3SimultaneousColumnSolver.Attempt.Failure.class, attempt);
            assertFalse(failure.code().isBlank());
        }
    }

    @Test
    void registeredPrBinaryNumericalTwoPhaseRootCannotPassTheIndependentPhaseGate() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("icheme:cdu17_tjl_acs2018");
        var base = V3TestInputs.cdu17Binary("test:registered-pr-binary");
        var input = V3TestInputs.dry(1, base.packageId(), base.assayId(), base.componentBasis(),
                base.feedComponentMolarFlowsMolPerSecond(), 550, 2, 1, 250000, 750, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(300),
                        new V3ColumnSpecification.OrganicRefluxRatio(2),
                        new V3ColumnSpecification.ReboilerDuty(50000)));
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input,V3CondenserPhaseBranch.TWO_PHASE);
        V3FlashResult feedFlash = thermo.flashTP(problem.input().feedTemperatureKelvin(),
                problem.nodePressurePascal(problem.topology().feedTrayNumber()),
                problem.input().feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace());
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                problem, thermo, feedFlash.molarEnthalpyJoulesPerMol());
        // Re-evaluate a captured numerically certified root; a changed cold trajectory must not erase
        // this negative coverage of the publication and exact-input warm-start gates.
        var attempt = V3ExactWarmStartSweepTest.capturedAttempt(input, V3CondenserPhaseBranch.TWO_PHASE).attempt();
        V3SimultaneousColumnSolver.Attempt.Converged converged = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class, attempt, attempt::toString);
        V3ConvergenceEvidence convergenceEvidence = converged.evidence().convergenceEvidence();
        V3AcceptanceAudit audit = new V3AcceptanceAuditor(problem, thermo, feedFlash.molarEnthalpyJoulesPerMol())
                .audit(converged.state(), thermo.newWorkspace(), V3SolveControl.UNBOUNDED);

        assertTrue(convergenceEvidence.satisfiesGates());
        // This old numerical fixture can close a two-phase MESH system at a liquid-only outlet.
        // A verified Newton correction must not bypass the independent phase check.
        assertTrue(audit.checks().stream().filter(check -> !check.family().equals("CONDENSER_PHASE")
                        && !check.family().equals("INTERIOR_PHASE_IDENTITY"))
                .allMatch(V3AcceptanceAudit.Check::passed), audit::toString);
        assertFalse(audit.accepted());
        assertTrue(audit.checks().stream().anyMatch(check -> check.family().equals("CONDENSER_PHASE")
                && !check.passed() && check.detail().contains("LIQUID")));
        assertThrows(IllegalArgumentException.class, () -> V3TestInputs.stateless(
                problem, new V3InputDigest("1".repeat(64)), audit, convergenceEvidence));

        V3SimultaneousColumnSolver.Attempt.Converged warmConverged = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class, V3SimultaneousColumnSolver.solve(
                        problem, evaluator, new V3DryMeshCoordinateMap(problem), converged.state(), thermo::newWorkspace,
                        convergenceEvidence, 128, 1.0e-8));
        assertEquals(0, warmConverged.evidence().iterations());
        assertTrue(warmConverged.evidence().convergenceEvidence().satisfiesGates());
        assertFalse(new V3AcceptanceAuditor(problem, thermo, feedFlash.molarEnthalpyJoulesPerMol())
                .audit(warmConverged.state(), thermo.newWorkspace(), V3SolveControl.UNBOUNDED).accepted());
    }

    @Test
    void insideOutFrozenPropertyPredictorStillRequiresStrictPrCertification() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("icheme:cdu17_tjl_acs2018");
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(
                V3TestInputs.cdu17BoilingBinary("test:inside-out-gate"), V3CondenserPhaseBranch.LIQUID_ONLY);
        V3FlashResult feedFlash = thermo.flashTP(problem.input().feedTemperatureKelvin(),
                problem.nodePressurePascal(problem.topology().feedTrayNumber()),
                problem.input().feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace());
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                problem, thermo, feedFlash.molarEnthalpyJoulesPerMol());
        V3DryMeshCoordinateMap coordinates = new V3DryMeshCoordinateMap(problem);
        var accepted = assertInstanceOf(V3ColumnOutcome.Success.class, V3TestInputs.calculate(problem.input()));
        var physical = accepted.result().warmSeed().orElseThrow().stateFor(problem);
        double[] moved = coordinates.encode(physical);
        for (int i = 0; i < moved.length; i++) moved[i] +=
                coordinates.unknowns().get(i).id().family() == V3DegreeOfFreedomLedger.UnknownFamily.TEMPERATURE ? 1.0 : 0.01;
        V3DryMeshState seed = coordinates.decode(moved);

        V3SimultaneousColumnSolver.Attempt attempt = V3SimultaneousColumnSolver.solveWithInsideOut(
                problem, evaluator, coordinates, seed, thermo::newWorkspace, 128, 1.0e-8,
                V3SolveControl.UNBOUNDED, V3SimultaneousColumnSolver.NewtonTrace.NONE);

        V3SimultaneousColumnSolver.Attempt.Converged converged = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class, attempt, attempt::toString);
        assertTrue(converged.evidence().convergenceEvidence().satisfiesGates());
        assertTrue(evaluator.evaluate(converged.state(), thermo.newWorkspace()).maximumAbsoluteScaledResidual() <= 1.0e-8);
        assertTrue(converged.evidence().iterations() > 0);
        assertTrue(new V3AcceptanceAuditor(problem, thermo, feedFlash.molarEnthalpyJoulesPerMol())
                .audit(converged.state(), thermo.newWorkspace(), V3SolveControl.UNBOUNDED).accepted());
    }

    /**
     * The stall stop belongs to the budget that asks for it and to no other solve.
     *
     * <p>A factor of zero is the sharpest form of the rule the ramp uses: no residual above the floor is ever
     * a large enough fall, so the stop must fire on the first iteration the window allows and nowhere else.
     * The default budget on the same perturbed state converges, so nothing on the published path can be cut
     * off by a detector it never enabled; and a floor above the residual suppresses the stop and converges to
     * the same state, so the floor really is what keeps a slowly closing rung alive.</p>
     */
    @Test
    void onlyABudgetThatAsksForItStopsAStalledAttemptAndOnlyAboveItsResidualFloor() {
        V3ColumnProblem problem = problem();
        NewtonManufacturedThermo thermo = new NewtonManufacturedThermo();
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, thermo, 0.0);
        V3DryMeshCoordinateMap coordinates = new V3DryMeshCoordinateMap(problem);
        V3DryMeshState perturbed = perturbedState(problem, coordinates);

        V3SimultaneousColumnSolver.Attempt.Failure stalled = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Failure.class,
                solveWithBudget(problem, evaluator, coordinates, perturbed, thermo,
                        new V3SimultaneousColumnSolver.RungBudget(8, true, 8, 2, 0.0, 0.0)));
        assertEquals("STALLED", stalled.code());
        assertEquals(2, stalled.evidence().iterations(), "the stop fires on the first iteration the window allows");
        assertTrue(stalled.evidence().termination().contains("iteration 0"), stalled.evidence()::termination);
        assertTrue(stalled.evidence().termination().contains(
                Double.toString(stalled.evidence().maximumScaledResidual())), stalled.evidence()::termination);

        V3SimultaneousColumnSolver.Attempt.Converged byDefault = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class,
                solveWithBudget(problem, evaluator, coordinates, perturbed, thermo,
                        V3SimultaneousColumnSolver.RungBudget.DEFAULT));
        V3SimultaneousColumnSolver.Attempt.Converged belowFloor = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class,
                solveWithBudget(problem, evaluator, coordinates, perturbed, thermo,
                        new V3SimultaneousColumnSolver.RungBudget(8, true, 8, 2, 0.0, 1.0e30)));
        assertEquals(byDefault.evidence().iterations(), belowFloor.evidence().iterations());
        assertEquals(byDefault.evidence().maximumScaledResidual(), belowFloor.evidence().maximumScaledResidual(), 0.0);
    }

    /**
     * The cascade cap is honoured, and the two dampings the ramp keeps are the ones that carry this fixture.
     *
     * <p>The manufactured Jacobian goes singular on the way in, so the damped normal equations are what solve
     * it at all: with the cascade removed the attempt stops at iteration 0 having evaluated the residual 60
     * times instead of 308. The ramp's own cap of two dampings reaches exactly the same state as the full eight
     * with exactly the same work, which is the point — the fifth to eighth damping are a tail almost nothing
     * uses, and an intermediate rung that would need them is a rung the ramp skips past anyway.</p>
     *
     * <p>The verification cap is the opposite finding, and is why every published rung keeps it: with the
     * certificate cascade removed this attempt reaches its tolerance and then cannot certify, so it iterates
     * on and costs more, not less. That measurement is why the reduced budget is spent only where the state is
     * a seed — and why the stage continuation, whose rungs each certify, kept the full cascade.</p>
     *
     * <p>The proxy for work is the count of thermo fugacity calls each solve makes ({@link #counting}): every residual
     * evaluation makes the same number. It was the workspace count until the solver reused one workspace per attempt
     * ({@code 0967a985}), after which every attempt counted one.</p>
     */
    @Test
    void theDampedCascadeCapIsHonouredAndTheRampsTwoDampingsAreEnough() {
        V3ColumnProblem problem = problem();
        NewtonManufacturedThermo thermo = new NewtonManufacturedThermo();
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, thermo, 0.0);
        V3DryMeshCoordinateMap coordinates = new V3DryMeshCoordinateMap(problem);
        V3DryMeshState perturbed = perturbedState(problem, coordinates);

        AtomicInteger fullWorkspaces = new AtomicInteger();
        V3SimultaneousColumnSolver.Attempt.Converged full = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class,
                V3SimultaneousColumnSolver.solveWithContinuationLocalBlocks(problem, evaluator, coordinates, perturbed,
                        counting(thermo, fullWorkspaces), 32, 1.0e-9, V3SolveControl.UNBOUNDED, V3SimultaneousColumnSolver.NewtonTrace.NONE,
                        V3SimultaneousColumnSolver.RungBudget.DEFAULT));

        AtomicInteger rampWorkspaces = new AtomicInteger();
        V3SimultaneousColumnSolver.Attempt.Converged ramp = assertInstanceOf(
                V3SimultaneousColumnSolver.Attempt.Converged.class,
                V3SimultaneousColumnSolver.solveWithContinuationLocalBlocks(problem, evaluator, coordinates, perturbed,
                        counting(thermo, rampWorkspaces), 32, 1.0e-9, V3SolveControl.UNBOUNDED, V3SimultaneousColumnSolver.NewtonTrace.NONE,
                        new V3SimultaneousColumnSolver.RungBudget(2, false, 8, 0, 0.0, 0.0)));
        assertEquals(full.evidence().iterations(), ramp.evidence().iterations());
        assertEquals(full.evidence().maximumScaledResidual(), ramp.evidence().maximumScaledResidual(), 0.0);
        assertEquals(fullWorkspaces.get(), rampWorkspaces.get(),
                "two dampings and no gradient fallback did not change one residual evaluation here");

        AtomicInteger cappedWorkspaces = new AtomicInteger();
        V3SimultaneousColumnSolver.Attempt capped =
                V3SimultaneousColumnSolver.solveWithContinuationLocalBlocks(problem, evaluator, coordinates, perturbed,
                        counting(thermo, cappedWorkspaces), 32, 1.0e-9, V3SolveControl.UNBOUNDED, V3SimultaneousColumnSolver.NewtonTrace.NONE,
                        new V3SimultaneousColumnSolver.RungBudget(0, false, 8, 0, 0.0, 0.0));
        if (capped instanceof V3SimultaneousColumnSolver.Attempt.Failure failure) {
            assertTrue(cappedWorkspaces.get() < fullWorkspaces.get(),
                    () -> "removing the cascade must remove work: " + cappedWorkspaces.get()
                            + " residual evaluations against " + fullWorkspaces.get());
            assertTrue(failure.code().startsWith("LINEAR_") || failure.code().startsWith("LINE_SEARCH"),
                    failure::code);
        } else {
            assertEquals(full.evidence().maximumScaledResidual(),
                    capped.evidence().maximumScaledResidual(), 0.0,
                    "a solve that never needed the cascade may not be changed by capping it");
        }

        AtomicInteger uncertifiedWorkspaces = new AtomicInteger();
        V3SimultaneousColumnSolver.Attempt uncertified =
                V3SimultaneousColumnSolver.solveWithContinuationLocalBlocks(problem, evaluator, coordinates, perturbed,
                        counting(thermo, uncertifiedWorkspaces), 32, 1.0e-9, V3SolveControl.UNBOUNDED,
                        V3SimultaneousColumnSolver.NewtonTrace.NONE, NO_VERIFICATION_CASCADE);
        if (uncertified instanceof V3SimultaneousColumnSolver.Attempt.Converged certified) {
            assertTrue(certified.evidence().convergenceEvidence().satisfiesGates());
        } else {
            assertTrue(uncertifiedWorkspaces.get() > fullWorkspaces.get(),
                    () -> "giving up the certificate cascade cost the certificate and saved nothing: "
                            + uncertifiedWorkspaces.get() + " residual evaluations against " + fullWorkspaces.get());
        }
    }

    /** A budget is a bound on optional work, so every field has to be inside the solver's own limits. */
    @Test
    void theRungBudgetRejectsLimitsTheSolverCannotHonour() {
        assertThrows(IllegalArgumentException.class,
                () -> new V3SimultaneousColumnSolver.RungBudget(9, true, 8, 0, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new V3SimultaneousColumnSolver.RungBudget(-1, true, 8, 0, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new V3SimultaneousColumnSolver.RungBudget(8, true, 9, 0, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new V3SimultaneousColumnSolver.RungBudget(8, true, 8, -1, 0.0, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new V3SimultaneousColumnSolver.RungBudget(8, true, 8, 4, 1.5, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new V3SimultaneousColumnSolver.RungBudget(8, true, 8, 4, Double.NaN, 0.0));
        assertThrows(IllegalArgumentException.class,
                () -> new V3SimultaneousColumnSolver.RungBudget(8, true, 8, 4, 0.5, -1.0));

        V3SimultaneousColumnSolver.RungBudget seed =
                NO_VERIFICATION_CASCADE;
        assertEquals(0, seed.verificationDampingSteps());
        assertEquals(V3SimultaneousColumnSolver.RungBudget.DEFAULT.maximumDampingSteps(), seed.maximumDampingSteps());
        assertTrue(seed.gradientFallback());
        assertEquals(0, seed.stallWindow());
    }

    private static V3SimultaneousColumnSolver.Attempt solveWithBudget(
            V3ColumnProblem problem, V3MeshResidualEvaluator evaluator, V3DryMeshCoordinateMap coordinates,
            V3DryMeshState seed, NewtonManufacturedThermo thermo, V3SimultaneousColumnSolver.RungBudget budget) {
        return V3SimultaneousColumnSolver.solveWithContinuationLocalBlocks(problem, evaluator, coordinates, seed,
                thermo::newWorkspace, 32, 1.0e-9, V3SolveControl.UNBOUNDED, V3SimultaneousColumnSolver.NewtonTrace.NONE, budget);
    }

    /** The workspace factory of one solve, whose thermo calls {@code thermo} counts in {@code evaluations}. */
    private static V3FiniteDifferenceJacobian.V3ThermoWorkspaceFactory counting(
            NewtonManufacturedThermo thermo, AtomicInteger evaluations) {
        thermo.evaluations = evaluations;
        return thermo::newWorkspace;
    }

    private static V3DryMeshState perturbedState(V3ColumnProblem problem, V3DryMeshCoordinateMap coordinates) {
        double[] perturbedCoordinates = coordinates.encode(exactState(problem.topology()));
        for (int index = 0; index < perturbedCoordinates.length; index++) {
            perturbedCoordinates[index] += index < 24 ? ((index % 5) - 2) * 0.02 : (index % 2 == 0 ? 0.5 : -0.5);
        }
        return coordinates.decode(perturbedCoordinates);
    }

    private static V3ColumnProblem problem() {
        V3ColumnInput input = V3TestInputs.dry(V3ColumnInput.SCHEMA_VERSION, "test:newton", "test:binary",
                new V3ComponentBasis(List.of("component-a", "component-b")), new double[] {30.0, 60.0}, 400.0,
                4, 2, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(400.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(1.0),
                        new V3ColumnSpecification.ReboilerDuty(0.0)));
        return V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE);
    }

    private static V3ColumnInput realCrudeInput(V3CrudeFeed crude) {
        double[] feedFlows = crude.moleFractions();
        for (int component = 0; component < feedFlows.length; component++) feedFlows[component] *= 100.0;
        return V3TestInputs.dry(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(),
                crude.componentBasis(), feedFlows, 638.15, 4, 2, 266_500.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(4.17),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));
    }


    private static String maximumResidualsByFamily(V3MeshResidual residual) {
        StringBuilder summary = new StringBuilder();
        for (V3DegreeOfFreedomLedger.EquationFamily family : V3DegreeOfFreedomLedger.EquationFamily.values()) {
            double maximum = 0.0;
            for (V3MeshResidual.Row row : residual.rows()) {
                if (row.equation().family() == family) maximum = Math.max(maximum, Math.abs(row.scaledValue()));
            }
            if (!summary.isEmpty()) summary.append(", ");
            summary.append(family).append('=').append(maximum);
        }
        return summary.toString();
    }

    private static V3DryMeshState exactState(V3ColumnTopology topology) {
        return new V3DryMeshState(topology, 2, new double[][] {
                {10.0, 10.0}, {5.0, 5.0}, {35.0, 65.0}, {35.0, 65.0}, {35.0, 65.0}, {17.0, 53.0}
        }, new double[][] {
                {8.0, 2.0}, {18.0, 12.0}, {18.0, 12.0}, {18.0, 12.0}, {18.0, 12.0}, {18.0, 12.0}
        }, new double[] {400.0, 410.0, 420.0, 430.0, 440.0, 450.0});
    }

    private static final class NewtonManufacturedThermo implements V3ThermoModel {
        private static final double[][] K = {
                {1.6, 0.4}, {1.2, 0.8}, {12.0 / 7.0, 8.0 / 13.0},
                {12.0 / 7.0, 8.0 / 13.0}, {12.0 / 7.0, 8.0 / 13.0}, {42.0 / 17.0, 28.0 / 53.0}
        };
        private final V3ComponentBasis basis = new V3ComponentBasis(List.of("component-a", "component-b"));
        /** Fugacity calls, the work of the solve under way ({@link #counting}); solves run one at a time. */
        private AtomicInteger evaluations = new AtomicInteger();

        @Override public V3ComponentBasis componentBasis() { return basis; }
        @Override public V3ThermoWorkspace newWorkspace() { return new V3ThermoWorkspace(2); }

        @Override
        public V3FugacityResult fugacity(
                double temperatureKelvin, double pressurePascal, double[] composition, Root phase,
                V3ThermoWorkspace workspace) {
            evaluations.incrementAndGet();
            int node = nodeForTemperature(temperatureKelvin);
            double[] logPhi = new double[2];
            if (phase == Root.LIQUID) {
                double referenceTemperature = 400.0 + 10.0 * node;
                for (int component = 0; component < 2; component++) logPhi[component] = Math.log(K[node][component])
                        + (component + 1) * 1.0e-3 * (temperatureKelvin - referenceTemperature);
            }
            return new V3FugacityResult(phase, logPhi, 1.0, 0.0, 1, 0.0);
        }

        @Override
        public double molarEnthalpy(
                double temperatureKelvin, double pressurePascal, double[] composition, Root phase,
                V3ThermoWorkspace workspace) {
            return temperatureKelvin - (400.0 + 10.0 * nodeForTemperature(temperatureKelvin));
        }

        @Override
        public V3FlashResult flashTP(
                double temperatureKelvin, double pressurePascal, double[] overallComposition, V3ThermoWorkspace workspace) {
            return V3ManufacturedFlash.flash(overallComposition, K[nodeForTemperature(temperatureKelvin)]);
        }

        private static int nodeForTemperature(double temperatureKelvin) {
            int node = (int) Math.round((temperatureKelvin - 400.0) / 10.0);
            if (node < 0 || node >= K.length) throw new IllegalArgumentException("Manufactured Newton temperature is outside its grid");
            return node;
        }
    }

    /** The bounded material/bubble-point projection, or null when it fails on a property or state error. */
    static V3DryMeshState projectedOrNull(V3ColumnProblem problem, V3PengRobinsonThermo thermo, V3DryMeshState state) {
        try {
            return V3ColumnInitializer.projectMaterialBalancesAtFixedTemperature(problem, thermo, thermo.newWorkspace(), state);
        } catch (com.wormzjl.icheme.science.column.v3.thermo.V3ThermoException | IllegalArgumentException failure) {
            return null;
        }
    }

    // Merged from V3VerificationAcceptanceTest.
    private static final V3ConvergenceEvidence SMALL_STEP = new V3ConvergenceEvidence(true, 1e-14, 1e-12, 1e-12, 1e-6, V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE);

    @Test
    void allowanceRequiresBothStatesWithinFixedCeilingAndADirectCorrection() {
        assertTrue(accept(1e-27, 2e-27, Math.nextDown(1e-12), 1e-12, 1e-8, true, SMALL_STEP));
        assertFalse(accept(1e-27, 2e-27, Math.nextUp(1e-12), 1e-12, 1e-8, true, SMALL_STEP));
        assertFalse(accept(1e-27, 2e-27, 1e-12, Math.nextUp(1e-12), 1e-8, true, SMALL_STEP));
        assertFalse(accept(1e-27, 2e-27, 1e-14, 1e-14, 1e-8, false, SMALL_STEP));
        assertFalse(accept(1e-27, 2e-27, 2e-13, 1e-14, 1e-13, true, SMALL_STEP));
        assertFalse(accept(1e-27, 2e-27, 1e-14, 2e-13, 1e-13, true, SMALL_STEP));
        assertFalse(accept(1e-27, 2e-27, 1e-10, 1e-10, 1e-3, true, SMALL_STEP));
        assertTrue(accept(2e-12, 1e-12, 1e-6, 1e-6, 1e-3, false, SMALL_STEP));
    }

    @Test
    void nearRootMeritCannotOverrideAnyActualCorrectionGateOrNonfiniteValues() {
        for (V3ConvergenceEvidence invalid : new V3ConvergenceEvidence[]{
                V3ConvergenceEvidence.unavailable(),
                new V3ConvergenceEvidence(true, Math.nextUp(1e-12), 0, 0, 0, V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE),
                new V3ConvergenceEvidence(true, 0, Math.nextUp(1e-8), 0, 0, V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE),
                new V3ConvergenceEvidence(true, 0, 0, 1e-5, Math.nextUp(1.0), V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE)}) {
            assertFalse(accept(1e-27, 2e-27, 1e-14, 1e-14, 1e-8, true, invalid));
        }
        for (double nonfinite : new double[]{Double.NaN, Double.POSITIVE_INFINITY}) {
            assertFalse(accept(nonfinite, 2e-27, 1e-14, 1e-14, 1e-8, true, SMALL_STEP));
            assertFalse(accept(1e-27, nonfinite, 1e-14, 1e-14, 1e-8, true, SMALL_STEP));
            assertFalse(accept(1e-27, 2e-27, nonfinite, 1e-14, 1e-8, true, SMALL_STEP));
            assertFalse(accept(1e-27, 2e-27, 1e-14, nonfinite, 1e-8, true, SMALL_STEP));
        }
    }

    private static boolean accept(double baseMerit, double candidateMerit, double baseMaximum, double candidateMaximum,
            double tolerance, boolean direct, V3ConvergenceEvidence evidence) {
        return V3SimultaneousColumnSolver.verificationCandidateAcceptable(
                baseMerit, candidateMerit, baseMaximum, candidateMaximum, tolerance, direct, evidence);
    }
}
