package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Verifies the optional local shared-library boundary without launching a native executable. */
class NativeColumnSolverTest {
    @Test
    void reportsNativeVersionWhenConfiguredAndFallsBackOtherwise() {
        String configuredPath = System.getProperty("icheme.native.path", "").trim();
        if (configuredPath.isEmpty()) {
            assertEquals("java-fallback", NativeColumnSolver.version());
        } else {
            assertTrue(NativeColumnSolver.available());
            assertEquals("icheme-column-solver-cpp17", NativeColumnSolver.version());
            assertEquals(Math.sqrt(2.0), NativeColumnSolver.smokeSolve(), 1.0e-10);
            double[] variables = {1.0};
            NativeColumnSolver.SolveResult result = NativeColumnSolver.solve(
                    variables, (point, residuals) -> residuals[0] = point[0] * point[0] - 2.0,
                    16, 1.0e-12, 1.0e-12);
            assertEquals(0, result.status());
            assertEquals(Math.sqrt(2.0), variables[0], 1.0e-10);
            double[] batch = NativeColumnSolver.pengRobinsonBatch(
                    new double[] {190.56}, new double[] {4_599_000.0}, new double[] {0.0115},
                    new double[] {0.0}, new double[] {300.0}, new double[] {101_325.0},
                    new double[] {1.0}, new double[] {1.0});
            assertTrue(batch != null && batch.length == 7);
            assertEquals(0.0, batch[0]);
            assertTrue(Double.isFinite(batch[1]));
            assertTrue(Double.isFinite(batch[5]));
            double[] derivativeBatch = NativeColumnSolver.pengRobinsonBatchTemperatureDerivatives(
                    new double[] {190.56}, new double[] {4_599_000.0}, new double[] {0.0115},
                    new double[] {0.0}, new double[] {300.0}, new double[] {101_325.0},
                    new double[] {1.0}, new double[] {1.0});
            assertTrue(derivativeBatch != null && derivativeBatch.length == 11);
            assertEquals(0.0, derivativeBatch[0]);
            assertTrue(Double.isFinite(derivativeBatch[4]));
            assertTrue(Double.isFinite(derivativeBatch[8]));
        }
    }
}
