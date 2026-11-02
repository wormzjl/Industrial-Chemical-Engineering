package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.wormzjl.icheme.science.column.v3.thermo.V3CrudeFeed;
import com.wormzjl.icheme.science.column.v3.thermo.V3FugacityResult;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.column.v3.thermo.V3ThermoWorkspace;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The C++ column library loads by default where the build packed it, and its PR pass agrees with the Java kernel. */
@org.junit.jupiter.api.extension.ExtendWith(com.wormzjl.icheme.science.material.Cdu17FixtureExtension.class)
class NativeColumnSolverTest {
    @Test
    void loadsThePackedLibraryByDefaultAndFallsBackOtherwise() {
        if (Boolean.getBoolean("icheme.native.packaged") || !System.getProperty("icheme.native.path", "").isBlank()) {
            assertTrue(NativeColumnSolver.available(), "no C++ column library for " + NativeColumnSolver.platform());
        }
        if (!NativeColumnSolver.available()) {
            assertEquals("java-fallback", NativeColumnSolver.version());
            assertFalse(NativeColumnSolver.thermoBatchEnabled());
            return;
        }
        assertEquals(Boolean.parseBoolean(System.getProperty("icheme.native.thermo.batch", "true").trim()),
                NativeColumnSolver.thermoBatchEnabled());
        assertEquals("icheme-column-solver-cpp17", NativeColumnSolver.version());
        assertEquals(Math.sqrt(2.0), NativeColumnSolver.smokeSolve(), 1.0e-10);
        double[] variables = {1.0};
        NativeColumnSolver.SolveResult result = NativeColumnSolver.solve(
                variables, (point, residuals) -> residuals[0] = point[0] * point[0] - 2.0,
                16, 1.0e-12, 1.0e-12);
        assertEquals(0, result.status());
        assertEquals(Math.sqrt(2.0), variables[0], 1.0e-10);
        double[] derivativeBatch = NativeColumnSolver.pengRobinsonBatchTemperatureDerivatives(
                new double[] {190.56}, new double[] {4_599_000.0}, new double[] {0.0115},
                new double[] {0.0}, new double[] {300.0}, new double[] {101_325.0},
                new double[] {1.0}, new double[] {1.0});
        assertTrue(derivativeBatch != null && derivativeBatch.length == 11);
        assertEquals(0.0, derivativeBatch[0]);
        assertTrue(Double.isFinite(derivativeBatch[4]));
        assertTrue(Double.isFinite(derivativeBatch[8]));
    }

    /**
     * The C++ pass is a second Peng-Robinson implementation feeding the inside-out trial, so it must give the Java
     * kernel's values: fugacity coefficients, enthalpy and compressibility of a crude column's liquid and vapour.
     */
    @Test
    void nativePengRobinsonPassMatchesTheJavaKernel() {
        assumeTrue(NativeColumnSolver.available());
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("icheme:cdu17_tjl_acs2018");
        int components = thermo.componentBasis().componentCount();
        double[] criticalTemperatures = new double[components];
        double[] criticalPressures = new double[components];
        double[] acentricFactors = new double[components];
        double[] interactions = new double[components * components];
        double[][] kij = thermo.binaryInteractions();
        for (int component = 0; component < components; component++) {
            criticalTemperatures[component] = thermo.componentCriticalTemperatureKelvin(component);
            criticalPressures[component] = thermo.componentCriticalPressurePascal(component);
            acentricFactors[component] = thermo.componentAcentricFactor(component);
            System.arraycopy(kij[component], 0, interactions, component * components, components);
        }
        // Three trays from the top to the bottom of a crude column: the liquid leans to the heavy end, the vapour
        // to the light end.
        double[] temperatures = {380.0, 480.0, 600.0};
        double[] pressures = {150_000.0, 180_000.0, 220_000.0};
        int nodes = temperatures.length;
        double[] liquid = new double[nodes * components];
        double[] vapor = new double[nodes * components];
        for (int node = 0; node < nodes; node++) {
            for (int component = 0; component < components; component++) {
                liquid[node * components + component] = 1.0 + component;
                vapor[node * components + component] = components - component;
            }
        }
        double[] batch = NativeColumnSolver.pengRobinsonBatch(criticalTemperatures, criticalPressures,
                acentricFactors, interactions, temperatures, pressures, liquid, vapor);
        assertTrue(batch != null && batch.length == 1 + nodes * 2 * (components + 2));
        assertEquals(0.0, batch[0]);

        V3ThermoWorkspace workspace = thermo.newWorkspace();
        double worstLogPhi = 0.0;
        double worstEnthalpy = 0.0;
        double worstCompressibility = 0.0;
        for (int node = 0; node < nodes; node++) {
            for (Root root : Root.values()) {
                double[] composition = new double[components];
                double total = 0.0;
                for (int component = 0; component < components; component++) {
                    composition[component] = (root == Root.LIQUID ? liquid : vapor)[node * components + component];
                    total += composition[component];
                }
                for (int component = 0; component < components; component++) composition[component] /= total;
                V3FugacityResult java = thermo.fugacity(temperatures[node], pressures[node], composition, root, workspace);
                int offset = 1 + (2 * node + (root == Root.LIQUID ? 0 : 1)) * (components + 2);
                for (int component = 0; component < components; component++) {
                    worstLogPhi = Math.max(worstLogPhi,
                            Math.abs(batch[offset + component] - java.logFugacityCoefficient(component)));
                }
                double enthalpy = batch[offset + components]
                        + thermo.idealGasMolarEnthalpy(temperatures[node], composition);
                worstEnthalpy = Math.max(worstEnthalpy, Math.abs(enthalpy - java.molarEnthalpyJoulesPerMol()));
                worstCompressibility = Math.max(worstCompressibility,
                        Math.abs(batch[offset + components + 1] - java.compressibilityFactor()));
            }
        }
        System.out.printf("C++ - Java PR: ln(phi) %.3e, H %.3e J/mol, Z %.3e%n",
                worstLogPhi, worstEnthalpy, worstCompressibility);
        assertTrue(worstLogPhi <= 1.0e-9, "ln(phi) differs by " + worstLogPhi);
        assertTrue(worstEnthalpy <= 1.0e-6, "H differs by " + worstEnthalpy + " J/mol");
        assertTrue(worstCompressibility <= 1.0e-9, "Z differs by " + worstCompressibility);
    }

    /**
     * The C++ pass only predicts; the Java Newton and audit publish. A four-tray dry crude column, whose cold solve
     * takes the inside-out trial, publishes the same products with either pass, to rounding.
     */
    @Test
    void separatingColumnPublishesTheSameProductsWithEitherOuterPass() {
        assumeTrue(NativeColumnSolver.available());
        V3CrudeFeed crude = V3PengRobinsonThermo.fromRegisteredPackage("icheme:cdu17_tjl_acs2018")
                .crudeFeed("icheme:tia_juana_light");
        double[] flows = crude.moleFractions();
        for (int component = 0; component < flows.length; component++) flows[component] *= 2_610.7 * 1_000.0 / 3_600.0;
        V3ColumnInput input = V3TestInputs.dry(V3ColumnInput.SCHEMA_VERSION, crude.packageId(), crude.assayId(),
                crude.componentBasis(), flows, 638.15, 4, 3, 250_000.0, 750.0, List.of(
                        new V3ColumnSpecification.CondenserOutletTemperature(332.15),
                        new V3ColumnSpecification.OrganicRefluxRatio(4.17),
                        new V3ColumnSpecification.ReboilerDuty(8_000_000.0)));
        String previous = System.getProperty("icheme.native.thermo.batch");
        V3ColumnOutcome.Success cpp;
        V3ColumnOutcome.Success java;
        try {
            System.setProperty("icheme.native.thermo.batch", "true");
            cpp = assertInstanceOf(V3ColumnOutcome.Success.class, V3TestInputs.calculate(input));
            System.setProperty("icheme.native.thermo.batch", "false");
            java = assertInstanceOf(V3ColumnOutcome.Success.class, V3TestInputs.calculate(input));
        } finally {
            if (previous == null) System.clearProperty("icheme.native.thermo.batch");
            else System.setProperty("icheme.native.thermo.batch", previous);
        }
        assertTrue(String.valueOf(cpp.diagnostics()).contains("Boston-Sullivan"), "the inside-out trial did not run");
        List<V3ColumnStreamProperties> cppStreams = cpp.result().streams();
        List<V3ColumnStreamProperties> javaStreams = java.result().streams();
        assertEquals(javaStreams.size(), cppStreams.size());
        double worst = 0.0;
        for (int stream = 0; stream < cppStreams.size(); stream++) {
            V3ColumnStreamProperties a = cppStreams.get(stream);
            V3ColumnStreamProperties b = javaStreams.get(stream);
            assertEquals(b.streamId(), a.streamId());
            worst = Math.max(worst, relative(a.molarFlowMolPerSecond(), b.molarFlowMolPerSecond()));
            worst = Math.max(worst, relative(a.temperatureKelvin(), b.temperatureKelvin()));
            for (int component = 0; component < a.moleFractions().size(); component++) {
                double x = a.moleFractions().get(component).moleFraction();
                double y = b.moleFractions().get(component).moleFraction();
                if (Math.max(x, y) > 1.0e-6) worst = Math.max(worst, relative(x, y));
            }
        }
        System.out.printf("C++ - Java outer pass, published products: %.3e relative%n", worst);
        assertTrue(worst <= 1.0e-9, "published products differ by " + worst + " relative");
    }

    private static double relative(double a, double b) {
        return Math.abs(a - b) / Math.max(Math.max(Math.abs(a), Math.abs(b)), Double.MIN_NORMAL);
    }
}
