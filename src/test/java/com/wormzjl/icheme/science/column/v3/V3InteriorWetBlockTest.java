package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.column.v3.thermo.V3ThermoWorkspace;
import org.junit.jupiter.api.Test;

/**
 * Step 1 of E31 (batch 2026-11-04-open-thermo-data): does a wet block whose top tray is interior have a lever?
 *
 * <p>The retrain reported that lower wet trays converge because each takes its lever from the free water of the tray
 * above. That holds for a tray <em>below</em> a wet tray. The top tray of any block whose water falls has none: the
 * telescoped balance gives it {@code W_n = (steam fed at or below n)} exactly, whatever it sheds, so its saturation
 * row can move only through its temperature and hydrocarbon vapour (the thermal loop measured near-null, 1.85e-8 per
 * kmol/h on the literature column, 2026-10-28 slowGates record of {@code V3StageContinuationTest}), and the
 * continuation declines it as uncontrollable. Carry-over gives tray one its lever; an interior block's top tray keeps
 * the circulating form. Fixture: the three-tray column of {@link V3FreeWaterTrayTest}, trays 2 and 3 wet, tray 1 dry.</p>
 */
class V3InteriorWetBlockTest {
    @Test
    void theTopTrayOfAnInteriorWetBlockHasNoLeverAndTheTrayBelowItDoes() {
        V3ColumnProblem problem = V3FreeWaterTrayTest.wetProblem(2, 3);
        double authoredTwo = problem.waterVaporFlowMolPerSecond(2);
        double authoredThree = problem.waterVaporFlowMolPerSecond(3);

        for (double flow : new double[] {1.0e-9, 0.5, 1.5, 4.0, 40.0}) {
            double[] water = new double[problem.topology().nodeCount()];
            water[2] = flow;
            water[3] = 0.75;
            V3DryMeshState state = V3ColumnInitializer.withFreeWater(
                    V3FreeWaterTrayTest.state(problem.topology()), problem.topology(), water);
            assertEquals(authoredTwo, problem.waterVaporFlow(state, 2), 0.0,
                    "the top tray of the block carries the whole authored steam whatever it sheds");
            assertEquals(authoredThree + flow, problem.waterVaporFlow(state, 3), 1.0e-12,
                    "the tray below is exactly what that free water controls");
        }

        // Structurally: tray 2's saturation row references neither its own free water nor any active one above it,
        // while tray 3's references the free water of tray 2.
        V3DegreeOfFreedomLedger.UnknownId ownTwo = new V3DegreeOfFreedomLedger.UnknownId(
                V3DegreeOfFreedomLedger.UnknownFamily.FREE_WATER_FLOW, 2, -1);
        V3DegreeOfFreedomLedger.Equation saturationTwo = saturationRow(problem, 2);
        V3DegreeOfFreedomLedger.Equation saturationThree = saturationRow(problem, 3);
        assertTrue(saturationTwo.referencedUnknowns().stream()
                .noneMatch(unknown -> unknown.family() == V3DegreeOfFreedomLedger.UnknownFamily.FREE_WATER_FLOW),
                saturationTwo.referencedUnknowns()::toString);
        assertTrue(saturationThree.referencedUnknowns().contains(ownTwo));
    }

    /** The residual says the same in numbers: tray 2's saturation row is bit-identical across its own free water. */
    @Test
    void theTopTraysSaturationRowDoesNotMoveWithItsOwnFreeWater() {
        V3ColumnProblem problem = V3FreeWaterTrayTest.wetProblem(2, 3);
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                problem, new V3FreeWaterTrayTest.AffineEnthalpyThermo(), 0.0);

        V3DryMeshState low = withWater(problem, 0.5, 0.75);
        V3DryMeshState high = withWater(problem, 4.0, 0.75);

        assertEquals(Double.doubleToRawLongBits(saturation(evaluator, low, 2)),
                Double.doubleToRawLongBits(saturation(evaluator, high, 2)),
                "no free-water flow of its own moves the top tray's saturation row");
        assertNotEquals(saturation(evaluator, low, 3), saturation(evaluator, high, 3),
                "the tray below reads it directly");
        assertFalse(problem.carriesTopTrayWaterOver() && problem.isWetTray(1),
                "tray one is dry here, so no carry-over is in play");
    }

    private static V3DryMeshState withWater(V3ColumnProblem problem, double trayTwo, double trayThree) {
        double[] water = new double[problem.topology().nodeCount()];
        water[2] = trayTwo;
        water[3] = trayThree;
        return V3ColumnInitializer.withFreeWater(V3FreeWaterTrayTest.state(problem.topology()), problem.topology(), water);
    }

    private static V3DegreeOfFreedomLedger.Equation saturationRow(V3ColumnProblem problem, int node) {
        return problem.degreeOfFreedomLedger().equations().stream()
                .filter(equation -> equation.id().family() == V3DegreeOfFreedomLedger.EquationFamily.WATER_SATURATION
                        && equation.id().node() == node).findFirst().orElseThrow();
    }

    private static double saturation(V3MeshResidualEvaluator evaluator, V3DryMeshState state, int node) {
        return evaluator.evaluate(state, new V3ThermoWorkspace(2)).rows().stream()
                .filter(row -> row.equation().family() == V3DegreeOfFreedomLedger.EquationFamily.WATER_SATURATION
                        && row.equation().node() == node).findFirst().orElseThrow().physicalValue();
    }
}
