package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.column.v3.linalg.V3BandedPivotedSolver;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The column's typed codes publish the exact texts the strings they replaced carried (batch E0-3, S2). */
class V3ColumnCodesTest {
    @Test
    void attemptCodesKeepTheirTextsAndTheirPublishedCategories() {
        Map<String, V3SolverFailureCode> expected = new LinkedHashMap<>();
        expected.put("STATE_DOMAIN", V3SolverFailureCode.NONCONVERGENCE);
        expected.put("MAX_ITERATIONS", V3SolverFailureCode.NONCONVERGENCE);
        expected.put("STALLED", V3SolverFailureCode.NONCONVERGENCE);
        expected.put("STALLED_SUPPORT_FLOOR", V3SolverFailureCode.NONCONVERGENCE);
        expected.put("JACOBIAN_BAND_STRUCTURE", V3SolverFailureCode.LINEAR_SOLVE_FAILURE);
        expected.put("LINEAR_SINGULAR", V3SolverFailureCode.LINEAR_SOLVE_FAILURE);
        expected.put("LINEAR_ILL_CONDITIONED", V3SolverFailureCode.LINEAR_SOLVE_FAILURE);
        expected.put("LINEAR_BACKWARD_ERROR_EXCEEDED", V3SolverFailureCode.LINEAR_SOLVE_FAILURE);
        expected.put("LINE_SEARCH_EXHAUSTED", V3SolverFailureCode.NONCONVERGENCE);
        expected.put("INTERNAL_INVARIANT", V3SolverFailureCode.INTERNAL_ERROR);
        assertEquals(List.copyOf(expected.keySet()), Arrays.stream(V3AttemptCode.values()).map(V3AttemptCode::text).toList());
        for (V3AttemptCode code : V3AttemptCode.values()) {
            assertEquals(code.text(), code.toString());
            assertEquals(expected.get(code.text()), V3SolveReport.failureCode(code), code.text());
        }
        // The band solver's code behind "LINEAR_": the text the solver built by concatenation.
        for (V3BandedPivotedSolver.FailureCode band : V3BandedPivotedSolver.FailureCode.values()) {
            assertEquals("LINEAR_" + band, V3AttemptCode.linear(band).text());
        }
    }

    @Test
    void auditFamiliesKeepTheirTexts() {
        assertEquals(List.of("FINITE_TOPOLOGY", "LOCAL_COMPONENT_BALANCE", "EQUILIBRIUM", "ENERGY_BALANCE",
                        "GLOBAL_ENERGY_BALANCE", "CONDENSER_PHASE", "CONDENSER_ENERGY_BALANCE", "SIDE_DRAW_SPLIT",
                        "TRUNCATION_MASS_DEFECT", "PHASE_TRUNCATION_DEFECT", "WATER_PROFILE", "WATER_BALANCE",
                        "WATER_DEW_POINT", "FREE_WATER_SPLIT", "INTERIOR_PHASE_IDENTITY", "CARRY_OVER", "UNAVAILABLE"),
                Arrays.stream(V3AuditFamily.values()).map(V3AuditFamily::text).toList());
        for (V3AuditFamily family : V3AuditFamily.values()) {
            assertEquals(family.text(), family.toString());
            V3AcceptanceAudit.Check pass = V3AcceptanceAudit.Check.pass(family, 0.0, 1.0, "fixture");
            V3AcceptanceAudit.Check fail = V3AcceptanceAudit.Check.fail(family, 1.0, 0.0, "fixture");
            assertEquals(family.text(), pass.family());
            assertEquals(family.text(), fail.family());
            assertTrue(pass.passed());
            assertFalse(fail.passed());
            assertTrue(pass.hasFamily(family));
        }
        // A family read back from the wire or built by a fixture is text: it matches a constant only by its text.
        assertTrue(V3AcceptanceAudit.Check.pass("CONDENSER_PHASE", 0.0, 0.0, "decoded").hasFamily(V3AuditFamily.CONDENSER_PHASE));
        assertFalse(V3AcceptanceAudit.Check.pass("condenser_phase", 0.0, 0.0, "decoded").hasFamily(V3AuditFamily.CONDENSER_PHASE));
        assertFalse(V3AcceptanceAudit.Check.pass("TEST", 0.0, 0.0, "fixture").hasFamily(V3AuditFamily.UNAVAILABLE));
    }

    @Test
    void rampKindsKeepTheirSolvePathLabels() {
        assertEquals(List.of("steam-ramp", "wet-ramp", "heat-ramp", "draw-ramp", "coupled-ramp"),
                Arrays.stream(V3RampKind.values()).map(V3RampKind::text).toList());
        for (V3RampKind kind : V3RampKind.values()) assertEquals(kind.text(), kind.toString());
    }
}
