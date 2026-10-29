package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class V3TruncationSupportTest {
    /**
     * The relative flow floor is always on, so a zero cutoff now inspects the seed. It still reuses the
     * problem's identity support, and rebuilds nothing, whenever no point is below the floor.
     */
    @Test
    void zeroCutoffStillAppliesTheFlowFloorAndReusesIdentityWhenNothingIsBelowIt() {
        V3ColumnProblem problem = problem(V3CondenserPhaseBranch.TWO_PHASE, 1.0, 2);
        V3DryMeshState populated = state(problem, uniformFlows(problem), uniformFlows(problem));
        V3TruncationSupport support = V3TruncationSupport.derive(problem, 0.0, populated);

        assertSame(problem.truncationSupport(), support);
        assertSame(support, V3TruncationSupport.derive(problem, -0.0, populated));
        assertSame(problem, V3ColumnProblemResolver.withTruncation(problem, support, V3WetTraySet.dry(problem.topology())));
        assertSame(problem.degreeOfFreedomLedger(),
                V3ColumnProblemResolver.withTruncation(problem, support, V3WetTraySet.dry(problem.topology())).degreeOfFreedomLedger());
        assertSame(problem, V3ColumnProblemResolver.withTruncation(problem,
                V3TruncationSupport.identity(problem.topology(), 2), V3WetTraySet.dry(problem.topology())));
        assertEquals(12, support.totalPointCount());
        assertEquals(0, support.truncatedPointCount());
        assertEquals(0, support.closurePrunedCount());
        assertEquals("", support.note());
        assertTrue(support.isIdentity());
        for (int node = 0; node < 6; node++) {
            for (int component = 0; component < 2; component++) assertTrue(support.retains(node, component));
        }
    }

    @Test
    void exactlyCutoffInEitherPhaseRetainsTheWholePointAndFeedTrayIsExempt() {
        V3ColumnProblem problem = problem(V3CondenserPhaseBranch.TWO_PHASE, 1.0, 2);
        double[][] liquid = uniformFlows(problem);
        double[][] vapor = uniformFlows(problem);
        liquid[0] = new double[] {1.0, 99.0};
        vapor[0] = new double[] {0.5, 99.5};
        liquid[2] = new double[] {0.0, 100.0};
        vapor[2] = new double[] {0.0, 100.0};
        liquid[4] = new double[] {0.5, 99.5};
        vapor[4] = new double[] {1.0, 99.0};
        V3TruncationSupport support = V3TruncationSupport.derive(problem, 0.01, state(problem, liquid, vapor));

        assertTrue(support.retains(0, 0));
        assertTrue(support.retains(2, 0));
        assertTrue(support.retains(4, 0));
        assertTrue(support.isIdentity());
        assertEquals(0.01, support.cutoffMoleFraction());
    }

    @Test
    void strictlyBelowCutoffInBothPhasesRemovesThePointOnlyAtThoseStages() {
        V3ColumnProblem problem = problem(V3CondenserPhaseBranch.TWO_PHASE, 1.0, 2);
        V3TruncationSupport support = topTailSupport(problem);

        assertFalse(support.isIdentity());
        assertFalse(support.retains(0, 0));
        assertFalse(support.retains(1, 0));
        assertTrue(support.retains(2, 0));
        assertTrue(support.retains(3, 0));
        for (int node = 0; node < 6; node++) assertTrue(support.retains(node, 1));
        assertEquals(2, support.truncatedPointCount());
        assertEquals(0, support.closurePrunedCount());
    }

    @ParameterizedTest
    @EnumSource(V3CondenserPhaseBranch.class)
    void condenserUsesOnlyItsStructurallyPresentPhases(V3CondenserPhaseBranch branch) {
        V3ColumnProblem problem = problem(branch, 0.0, 2);
        V3TruncationSupport support = topTailSupport(problem);

        assertFalse(support.retains(0, 0));
        assertTrue(support.retains(0, 1));
        assertEquals(2, support.truncatedPointCount());
    }

    @Test
    void zeroTotalPhaseIsUntestableAndNoTestablePhaseConservativelyRetains() {
        V3ColumnProblem problem = problem(V3CondenserPhaseBranch.TWO_PHASE, 1.0, 2);
        double[][] liquid = uniformFlows(problem);
        double[][] vapor = uniformFlows(problem);
        liquid[0] = new double[] {0.0, 0.0};
        vapor[0] = new double[] {0.1, 99.9};
        liquid[4] = new double[] {0.0, 0.0};
        vapor[4] = new double[] {0.0, 0.0};
        V3TruncationSupport support = V3TruncationSupport.derive(problem, 0.01, state(problem, liquid, vapor));

        assertFalse(support.retains(0, 0));
        assertTrue(support.retains(0, 1));
        assertTrue(support.retains(4, 0));
        assertTrue(support.retains(4, 1));
    }

    @Test
    void disconnectedUpperGroupIsPrunedEvenWhenPositiveRefluxCreatesACycle() {
        V3ColumnProblem noReflux = problem(V3CondenserPhaseBranch.TWO_PHASE, 0.0, 3);
        double[][] flows = uniformFlows(noReflux);
        flows[2] = new double[] {0.1, 99.9};
        V3TruncationSupport support = V3TruncationSupport.derive(noReflux, 0.01, state(noReflux, flows, flows));

        assertFalse(support.retains(0, 0));
        assertFalse(support.retains(1, 0));
        assertFalse(support.retains(2, 0));
        assertTrue(support.retains(3, 0));
        assertEquals(2, support.closurePrunedCount());
        assertEquals(3, support.truncatedPointCount());

        V3ColumnProblem reflux = problem(V3CondenserPhaseBranch.TWO_PHASE, 1.0, 3);
        V3TruncationSupport withReflux = V3TruncationSupport.derive(reflux, 0.01, state(reflux, flows, flows));
        // Condenser and tray one exchange material, but neither has a path from the feed at tray three.
        assertFalse(withReflux.retains(0, 0));
        assertFalse(withReflux.retains(1, 0));
        assertFalse(withReflux.retains(2, 0));
        assertTrue(withReflux.retains(3, 0));
        assertEquals(2, withReflux.closurePrunedCount());
        assertEquals(3, withReflux.truncatedPointCount());
        V3DryMeshState projected = withReflux.projectSeed(reflux, state(reflux, flows, flows));
        for (int node = 0; node <= 2; node++) {
            assertEquals(0.0, projected.liquidFlow(node, 0));
            assertEquals(0.0, projected.vaporFlow(node, 0));
        }
        assertThrows(IllegalArgumentException.class,
                () -> V3ColumnProblemResolver.withTruncation(noReflux, withReflux, V3WetTraySet.dry(noReflux.topology())));
    }

    @ParameterizedTest
    @EnumSource(V3CondenserPhaseBranch.class)
    void disconnectedLowerGroupCannotUseItsOwnLiquidVaporCycleAsAFeed(V3CondenserPhaseBranch branch) {
        V3ColumnProblem problem = problem(branch, 0.0, 2);
        double[][] flows = uniformFlows(problem);
        flows[3] = new double[] {0.1, 99.9};
        V3DryMeshState seed = state(problem, flows, flows);
        V3TruncationSupport support = V3TruncationSupport.derive(problem, 0.01, seed);

        for (int node = 0; node <= 2; node++) assertTrue(support.retains(node, 0));
        for (int node = 3; node <= 5; node++) assertFalse(support.retains(node, 0));
        for (int node = 0; node <= 5; node++) assertTrue(support.retains(node, 1));
        assertEquals(2, support.closurePrunedCount());
        assertEquals(3, support.truncatedPointCount());
        V3DryMeshState projected = support.projectSeed(problem, seed);
        for (int node = 3; node <= 5; node++) {
            assertEquals(0.0, projected.liquidFlow(node, 0));
            assertEquals(0.0, projected.vaporFlow(node, 0));
        }
        assertTrue(V3ColumnProblemResolver.withTruncation(problem, support, V3WetTraySet.dry(problem.topology())).degreeOfFreedomLedger().isValid());
    }

    /**
     * A side draw keeps the liquid it receives, and only that.
     *
     * <p>The forced product-path band from the feed tray to the outermost draw tray is gone. What is left is
     * a per-draw-tray guarantee over the liquid the tray above actually delivers: the draw on tray three,
     * directly below the feed tray, keeps the component in its liquid, while the draw on tray one has no
     * retained liquid arriving from the condenser and is decided by the flow rule like any other tray.</p>
     */
    @Test
    void aSideDrawTrayKeepsTheLiquidItReceivesAndNotAWholeBandBackToTheFeed() {
        V3ColumnProblem problem = problem(V3CondenserPhaseBranch.TWO_PHASE, 1.0, 2,
                List.of(new V3SideDrawSpec(1, 1.0), new V3SideDrawSpec(3, 1.0)));
        double[][] flows = uniformFlows(problem);
        for (int node = 0; node < flows.length; node++) flows[node] = new double[] {0.1, 99.9};
        V3TruncationSupport support = V3TruncationSupport.derive(problem, 0.01, state(problem, flows, flows));

        assertTrue(support.retains(2, 0), "the feed tray is still retained whole");
        assertTrue(support.retainsLiquid(3, 0), "the draw tray keeps the liquid the feed tray delivers");
        assertFalse(support.retainsVapor(3, 0), "and only the liquid: the vapor is below the floor");
        assertFalse(support.retains(1, 0), "the upper draw receives no retained liquid and is not forced");
        for (int node : new int[] {0, 4, 5}) assertFalse(support.retains(node, 0));
        for (int node = 0; node <= 5; node++) assertTrue(support.retains(node, 1));
        assertEquals(0, support.closurePrunedCount());
        assertEquals(4, support.truncatedPointCount());
        assertEquals(1, support.onePhasePointCount());
        assertTrue(V3ColumnProblemResolver.withTruncation(problem, support, V3WetTraySet.dry(problem.topology())).degreeOfFreedomLedger().isValid());
    }

    @Test
    void condenserAndReboilerLoseSupportWhenTheirOnlyInflowNeighborIsRemoved() {
        V3ColumnProblem problem = problem(V3CondenserPhaseBranch.TWO_PHASE, 1.0, 2);
        double[][] flows = uniformFlows(problem);
        flows[1] = new double[] {0.1, 99.9};
        flows[4] = new double[] {0.1, 99.9};
        V3TruncationSupport support = V3TruncationSupport.derive(problem, 0.01, state(problem, flows, flows));

        assertFalse(support.retains(0, 0));
        assertFalse(support.retains(5, 0));
        assertTrue(support.retains(2, 0));
        assertEquals(2, support.closurePrunedCount());
        assertEquals(4, support.truncatedPointCount());
    }

    @ParameterizedTest
    @EnumSource(V3CondenserPhaseBranch.class)
    void emptyStructuralPhaseFallsBackToIdentityWithBoundedProvenance(V3CondenserPhaseBranch branch) {
        V3ColumnProblem problem = problem(branch, 0.0, 2);
        double[][] flows = uniformFlows(problem);
        flows[0] = new double[] {99.9, 0.1};
        flows[1] = new double[] {0.1, 99.9};
        V3TruncationSupport support = V3TruncationSupport.derive(problem, 0.01, state(problem, flows, flows));

        assertTrue(support.isIdentity());
        assertEquals(0, support.truncatedPointCount());
        assertEquals(1, support.closurePrunedCount());
        assertEquals(0.01, support.cutoffMoleFraction());
        assertTrue(support.note().contains("identity"));
        assertTrue(support.note().length() <= 256);
        assertSame(problem, V3ColumnProblemResolver.withTruncation(problem, support, V3WetTraySet.dry(problem.topology())));
    }

    @Test
    void validatesCutoffShapeAndPointAccessWithoutAcceptingNonfinitePhaseTotals() {
        V3ColumnProblem problem = problem(V3CondenserPhaseBranch.TWO_PHASE, 1.0, 2);
        V3DryMeshState state = state(problem, uniformFlows(problem), uniformFlows(problem));
        for (double invalid : new double[] {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                -Double.MIN_VALUE, Math.nextUp(0.01)}) {
            assertThrows(IllegalArgumentException.class, () -> V3TruncationSupport.derive(problem, invalid, state));
        }
        assertThrows(IllegalArgumentException.class, () -> V3TruncationSupport.identity(problem.topology(), 0));
        assertThrows(IllegalArgumentException.class, () -> V3TruncationSupport.identity(problem.topology(), 65));
        V3DryMeshState wrongAxis = new V3DryMeshState(problem.topology(), 1, new double[6][1],
                new double[6][1], temperatures(problem));
        assertThrows(IllegalArgumentException.class, () -> V3TruncationSupport.derive(problem, 0.01, wrongAxis));
        V3TruncationSupport support = problem.truncationSupport();
        assertThrows(IndexOutOfBoundsException.class, () -> support.retains(-1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> support.retains(6, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> support.retains(0, -1));
        assertThrows(IndexOutOfBoundsException.class, () -> support.retains(0, 2));
        double[][] overflowing = uniformFlows(problem);
        overflowing[0] = new double[] {Double.MAX_VALUE, Double.MAX_VALUE};
        V3DryMeshState overflow = state(problem, overflowing, uniformFlows(problem));
        assertThrows(IllegalArgumentException.class, () -> V3TruncationSupport.derive(problem, 0.01, overflow));
    }

    static V3ColumnProblem problem(V3CondenserPhaseBranch branch, double reflux, int feedTray) {
        return problem(branch, reflux, feedTray, List.of());
    }

    private static V3ColumnProblem problem(V3CondenserPhaseBranch branch, double reflux, int feedTray,
                                           List<V3SideDrawSpec> sideDraws) {
        V3ColumnInput input = new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, "test:manufactured", "test:binary",
                new V3ComponentBasis(List.of("component-a", "component-b")), new double[] {1.0e-6, 100.0}, 400.0,
                4, feedTray, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(400.0),
                        new V3ColumnSpecification.OrganicRefluxRatio(reflux),
                        new V3ColumnSpecification.ReboilerDuty(0.0)), sideDraws);
        return allPhaseProblem(input, branch);
    }

    /** Manufactured wet-stage graph for algebra tests, independent of the production liquid-supply compiler. */
    static V3ColumnProblem allPhaseProblem(V3ColumnInput input, V3CondenserPhaseBranch branch) {
        var resolved = V3ColumnProblemResolver.resolve(input,branch);
        var topology = switch (branch) {
            case TWO_PHASE -> V3ColumnTopology.twoPhase(input.stageCount(),input.feedStageNumber());
            case LIQUID_ONLY -> V3ColumnTopology.liquidOnly(input.stageCount(),input.feedStageNumber());
            case VAPOR_ONLY -> V3ColumnTopology.vaporOnly(input.stageCount(),input.feedStageNumber());
        };
        var support = V3TruncationSupport.identity(topology,resolved.activeComponentBasis().componentCount());
        var ledger = V3DegreeOfFreedomLedger.create(topology,resolved.activeComponentBasis().componentCount(),input.specifications(),
                resolved.condenserComponentPhases(),support,input.sideDraws());
        return new V3ColumnProblem(input,topology,resolved.activeComponentBasis(),resolved.condenserComponentPhases(),
                resolved.nodePressuresPascal(),ledger,support,ledger.wetTraySet());
    }

    static V3TruncationSupport topTailSupport(V3ColumnProblem problem) {
        double[][] flows = uniformFlows(problem);
        flows[0] = new double[] {0.1, 99.9};
        flows[1] = new double[] {0.1, 99.9};
        return V3TruncationSupport.derive(problem, 0.01, state(problem, flows, flows));
    }

    static double[][] uniformFlows(V3ColumnProblem problem) {
        double[][] flows = new double[problem.topology().nodeCount()][2];
        for (double[] row : flows) Arrays.fill(row, 50.0);
        return flows;
    }

    static V3DryMeshState state(V3ColumnProblem problem, double[][] liquid, double[][] vapor) {
        // Do not mutate caller arrays when the condenser has only one phase.
        liquid = liquid.clone();
        vapor = vapor.clone();
        if (!problem.topology().hasLiquidPhase(0)) liquid[0] = new double[2];
        if (!problem.topology().hasVaporPhase(0)) vapor[0] = new double[2];
        return new V3DryMeshState(problem.topology(), 2, liquid, vapor, temperatures(problem));
    }

    private static double[] temperatures(V3ColumnProblem problem) {
        double[] temperatures = new double[problem.topology().nodeCount()];
        Arrays.fill(temperatures, 400.0);
        return temperatures;
    }

    // Merged from V3TruncationAuditTest.
    @Test
    void auditConsumesFrozenSupportAndAcceptsTheRecomputedSmallDefect() {
        V3TruncationNumericsTest.Fixture fixture = V3TruncationNumericsTest.fixture(V3CondenserPhaseBranch.TWO_PHASE, 0.01);
        V3TruncationSupport support = fixture.problem().truncationSupport();
        V3AcceptanceAudit audit = new V3AcceptanceAuditor(fixture.problem(), fixture.thermo(), 0.0)
                .audit(fixture.exact(), fixture.thermo().newWorkspace(), V3SolveControl.UNBOUNDED);
        assertTrue(audit.accepted(), audit::toString);
        V3AcceptanceAudit.Check defect = defectCheck(audit);
        assertEquals(0.01 / 90.01, defect.value(), 1.0e-16);
        // The floor support adds its own per-sink-edge construction bound to the cutoff budget: 4.4e-13 here.
        assertEquals(0.08, defect.limit(), 1.0e-9);
        assertSame(support, fixture.problem().truncationSupport());
        assertEquals(5, support.truncatedPointCount());
    }

    @Test
    void auditRejectsAnOversizedDefectEvenWhenAllRetainedMeshRowsClose() {
        V3TruncationNumericsTest.Fixture fixture = V3TruncationNumericsTest.fixture(V3CondenserPhaseBranch.TWO_PHASE, 10.0);
        V3AcceptanceAudit audit = new V3AcceptanceAuditor(fixture.problem(), fixture.thermo(), 0.0)
                .audit(fixture.exact(), fixture.thermo().newWorkspace(), V3SolveControl.UNBOUNDED);
        assertFalse(audit.accepted());
        assertEquals(0.1, defectCheck(audit).value(), 1.0e-15);
        assertFalse(defectCheck(audit).passed());
        assertTrue(audit.checks().stream().filter(check -> !check.family().equals("TRUNCATION_MASS_DEFECT"))
                .allMatch(V3AcceptanceAudit.Check::passed));
    }

    @Test
    void digestIncludesTheRequestedCutoffButNotPathDependentSupportAndPreservesZeroEncoding() {
        V3TruncationNumericsTest.Fixture fixture = V3TruncationNumericsTest.fixture(V3CondenserPhaseBranch.TWO_PHASE, 0.01);
        V3InputDigest original = V3InputDigest.of(fixture.original(), "mesh-r2", "data", "assumptions");
        assertEquals(original, V3InputDigest.of(fixture.original(), "mesh-r2", "data", "assumptions", 0.0));
        assertEquals(original, V3InputDigest.of(fixture.original(), "mesh-r2", "data", "assumptions", -0.0));
        V3InputDigest enabled = V3InputDigest.of(fixture.problem(), "mesh-r3", "data", "assumptions", 1.0e-6);
        assertEquals(enabled, V3InputDigest.of(fixture.original(), "mesh-r3", "data", "assumptions", 1.0e-6));
        assertNotEquals(original, enabled);
        assertNotEquals(enabled, V3InputDigest.of(fixture.problem(), "mesh-r3", "data", "assumptions", 1.0e-5));
        assertThrows(IllegalArgumentException.class,
                () -> V3InputDigest.of(fixture.problem(), "mesh-r3", "data", "assumptions", Double.NaN));
    }

    private static V3AcceptanceAudit.Check defectCheck(V3AcceptanceAudit audit) {
        return audit.checks().stream().filter(check -> check.family().equals("TRUNCATION_MASS_DEFECT"))
                .findFirst().orElseThrow();
    }
}
