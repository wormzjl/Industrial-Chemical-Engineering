package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * The audit's residual families are bounded by the closure the Newton stop used (D4 of batch 2026-11-13-codebase-review):
 * a local component balance out by 1e-4 of its scale fails at the strict closure, where the limit of 1.0 passed it.
 */
class V3AcceptanceAuditorLimitsTest {
    @Test
    void aScaledComponentBalanceOf1e4FailsAtTheStrictClosure() {
        var fixture = V3TruncationNumericsTest.fixture(V3CondenserPhaseBranch.TWO_PHASE, 0.01);
        var auditor = new V3AcceptanceAuditor(fixture.problem(), fixture.thermo(), 0.0);
        V3AcceptanceAudit.Check exact = check(auditor.audit(fixture.exact(), fixture.thermo().newWorkspace(), V3SolveControl.UNBOUNDED),
                "LOCAL_COMPONENT_BALANCE");
        assertTrue(exact.passed(), exact::toString);
        assertEquals(V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE, exact.limit(), 0.0, "the strict closure");

        // One tray liquid flow moved: its component rows are linear in the flow, so a probe sizes the move to 1e-4.
        double probe = 1.0e-3, target = 1.0e-4;
        double scaled = maximumScaledBalance(fixture, moved(fixture, probe));
        V3DryMeshState state = moved(fixture, probe * target / scaled);
        assertEquals(target, maximumScaledBalance(fixture, state), 0.05 * target, "the scaled balance of the moved state");
        V3AcceptanceAudit.Check balance = check(auditor.audit(state, fixture.thermo().newWorkspace(), V3SolveControl.UNBOUNDED),
                "LOCAL_COMPONENT_BALANCE");
        assertFalse(balance.passed(), balance::toString);
        assertEquals(V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE, balance.limit(), 0.0);
        assertEquals(V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE, check(auditor.audit(state, fixture.thermo().newWorkspace(),
                V3SolveControl.UNBOUNDED), "ENERGY_BALANCE").limit(), 0.0, "the energy rows share the closure");
    }

    private static V3DryMeshState moved(V3TruncationNumericsTest.Fixture fixture, double delta) {
        double[][] liquid = V3TruncationNumericsTest.copyFlows(fixture.exact(), true);
        liquid[3][0] += delta;
        return new V3DryMeshState(fixture.problem().topology(), 3, liquid,
                V3TruncationNumericsTest.copyFlows(fixture.exact(), false), V3TruncationNumericsTest.temperatures());
    }

    private static double maximumScaledBalance(V3TruncationNumericsTest.Fixture fixture, V3DryMeshState state) {
        V3MeshResidual residual = new V3MeshResidualEvaluator(fixture.problem(), fixture.thermo(), 0.0)
                .evaluate(state, fixture.thermo().newWorkspace());
        double maximum = 0.0;
        for (int row = 0; row < residual.size(); row++)
            if (residual.equation(row).family() == V3DegreeOfFreedomLedger.EquationFamily.COMPONENT_MATERIAL_BALANCE)
                maximum = Math.max(maximum, Math.abs(residual.scaledValue(row)));
        return maximum;
    }

    private static V3AcceptanceAudit.Check check(V3AcceptanceAudit audit, String family) {
        return audit.checks().stream().filter(c -> c.family().equals(family)).findFirst().orElseThrow();
    }
}
