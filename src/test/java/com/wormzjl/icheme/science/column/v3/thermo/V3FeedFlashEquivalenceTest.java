package com.wormzjl.icheme.science.column.v3.thermo;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import java.lang.reflect.Method;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.wormzjl.icheme.science.math.DeterministicMath;

@org.junit.jupiter.api.extension.ExtendWith(com.wormzjl.icheme.science.material.Cdu17FixtureExtension.class)
class V3FeedFlashEquivalenceTest {
    @Test
    void rootMatchesFrozenHundredStepAlgorithmIncludingEndpointsAndInvalidInputs() throws Exception {
        Method optimized = V3FeedFlash.class.getDeclaredMethod("rachfordRiceRoot",
                double[].class, double[].class, double[].class, double[].class);
        Method reference = V3FeedFlashReference.class.getDeclaredMethod("rachfordRiceRoot", double[].class, double[].class);
        optimized.setAccessible(true);
        reference.setAccessible(true);
        Random random = new Random(20260909);
        for (int sample = 0; sample < 1000; sample++) {
            double[] z = new double[19];
            double[] logK = new double[19];
            double total = 0;
            for (int i = 0; i < z.length; i++) {
                z[i] = random.nextBoolean() ? 0 : random.nextDouble();
                total += z[i];
                logK[i] = -700 + 1400 * random.nextDouble();
            }
            if (total == 0) z[0] = total = 1;
            for (int i = 0; i < z.length; i++) z[i] /= total;
            assertRoot(reference, optimized, z, logK);
        }
        for (double special : new double[]{-1000, -60, -1e-25, 0, 1e-25, 60, 1000,
                Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY}) {
            assertRoot(reference, optimized, new double[]{0.5, 0.5}, new double[]{special, -special});
            assertRoot(reference, optimized, new double[]{1, 0}, new double[]{special, Double.NaN});
        }
    }

    private static void assertRoot(Method reference, Method optimized, double[] z, double[] logK) throws Exception {
        double expected = (double) reference.invoke(null, z, logK);
        double actual = (double) optimized.invoke(null, z, logK, new double[z.length], new double[z.length]);
        assertEquals(Double.doubleToLongBits(expected), Double.doubleToLongBits(actual));
    }

    @Test
    void completeFlashAndFailureOutcomesMatchFrozenMain() {
        int successes = 0;
        int failures = 0;
        for (String packageId : new String[]{"icheme:cdu17_tjl_acs2018", "icheme:tjl19_dwsim"}) {
            V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(packageId);
            double[] overall = thermo.crudeFeed(com.wormzjl.icheme.science.material.Cdu17TestCatalog.tiaJuanaAssay(packageId)).moleFractions();
            for (double temperature : new double[]{298.15, 323.15, 500, 638.15, 900}) {
                for (double pressure : new double[]{50_000, 110_000, 267_250}) {
                    V3ThermoWorkspace oldWorkspace = thermo.newWorkspace();
                    V3ThermoWorkspace newWorkspace = thermo.newWorkspace();
                    System.arraycopy(overall, 0, oldWorkspace.normalizedOverall, 0, overall.length);
                    System.arraycopy(overall, 0, newWorkspace.normalizedOverall, 0, overall.length);
                    V3FlashResult expected;
                    try {
                        expected = V3FeedFlashReference.resolve(thermo, temperature, pressure, oldWorkspace);
                    } catch (V3ThermoException failure) {
                        V3ThermoException actual = assertThrows(V3ThermoException.class,
                                () -> V3FeedFlash.resolve(thermo, temperature, pressure, newWorkspace));
                        assertEquals(failure.getMessage(), actual.getMessage());
                        assertEquals(failure.code(), actual.code());
                        assertEquals(failure.phase(), actual.phase());
                        failures++;
                        continue;
                    }
                    V3FlashResult actual = V3FeedFlash.resolve(thermo, temperature, pressure, newWorkspace);
                    assertEquals(expected.phase(), actual.phase());
                    assertEquals(expected.iterations(), actual.iterations());
                    assertEquals(expected.detail(), actual.detail());
                    assertEquals(expected.vaporFraction(), actual.vaporFraction());
                    assertEquals(expected.molarEnthalpyJoulesPerMol(), actual.molarEnthalpyJoulesPerMol());
                    assertArrayEquals(expected.liquidComposition(), actual.liquidComposition());
                    assertArrayEquals(expected.vaporComposition(), actual.vaporComposition());
                    assertArrayEquals(oldWorkspace.nextLogK, newWorkspace.nextLogK);
                    successes++;
                }
            }
        }
        assertTrue(successes > 0);
        assertEquals(30, successes + failures);
    }

    // Merged from V3FeedFlashTest.
    @Test
    void convergedLiquidEndpointDoesNotPublishAnInfinitesimalVaporPhase() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("icheme:cdu17_tjl_acs2018");
        // A 50 Celsius crude-condenser outlet whose Wilson guess starts inside the two-phase bracket.
        double[] composition = {0.0, 0.002221751374857895, 0.019949047354192243, 0.054160836655037715,
                0.2442251337042464, 0.11704483090680007, 0.20198741266388825, 0.09732234881493797,
                0.17093587548826125, 0.0881394158911579, 0.003960475367106011, 5.245952693290889e-5,
                4.11295543900127e-7, 9.5703604528816e-10, 1.4138582777064319e-15, 2.3450168525575994e-27};

        V3FlashResult flash = thermo.flashTP(323.15, 110_000.0, composition, thermo.newWorkspace());

        assertTrue(flash.iterations() > 0);
        assertEquals(V3FeedPhase.LIQUID, flash.phase(), flash::detail);
        assertEquals(0.0, flash.vaporFraction());
        assertEquals(0, flash.vaporComposition().length);
        assertArrayEquals(composition, flash.liquidComposition(), 1.0e-12);
    }

    /** Frozen db46e88 flash for numerical equivalence tests; do not update with the implementation. */
    static final class V3FeedFlashReference {
        private static final int MAXIMUM_ITERATIONS = 64;
        private static final int RACHFORD_RICE_ITERATIONS = 100;
        private static final double LOG_K_TOLERANCE = 1.0e-10;
        private static final double PHASE_ENDPOINT_TOLERANCE = 1.0e-12;

        private V3FeedFlashReference() {}

        static V3FlashResult resolve(
                V3PengRobinsonThermo model, double temperatureKelvin, double pressurePascal, V3ThermoWorkspace workspace) {
            int componentCount = workspace.componentCount();
            model.wilsonK(temperatureKelvin, pressurePascal, workspace.wilsonK, workspace);
            for (int component = 0; component < componentCount; component++) workspace.logK[component] = DeterministicMath.log(workspace.wilsonK[component]);

            double atLiquidEndpoint = rachfordRiceResidual(workspace.normalizedOverall, workspace.logK, 0.0);
            double atVaporEndpoint = rachfordRiceResidual(workspace.normalizedOverall, workspace.logK, 1.0);
            if (!Double.isFinite(atLiquidEndpoint) || !Double.isFinite(atVaporEndpoint)) {
                throw new V3ThermoException(V3ThermoException.Code.FLASH_NONCONVERGENCE, null,
                        "V3 feed flash could not establish a finite Rachford-Rice phase bracket");
            }
            if (atLiquidEndpoint <= PHASE_ENDPOINT_TOLERANCE) {
                model.evaluateInto(temperatureKelvin, pressurePascal, workspace.normalizedOverall, Root.LIQUID, workspace);
                return V3FlashResult.liquid(0, workspace.normalizedOverall,
                        model.phaseMolarEnthalpy(temperatureKelvin, workspace.normalizedOverall, Root.LIQUID, workspace),
                        "all-liquid Wilson endpoint classification");
            }
            if (atVaporEndpoint >= -PHASE_ENDPOINT_TOLERANCE) {
                model.evaluateInto(temperatureKelvin, pressurePascal, workspace.normalizedOverall, Root.VAPOR, workspace);
                return V3FlashResult.vapor(0, workspace.normalizedOverall,
                        model.phaseMolarEnthalpy(temperatureKelvin, workspace.normalizedOverall, Root.VAPOR, workspace),
                        "all-vapor Wilson endpoint classification");
            }

            for (int iteration = 1; iteration <= MAXIMUM_ITERATIONS; iteration++) {
                double vaporFraction = rachfordRiceRoot(workspace.normalizedOverall, workspace.logK);
                if (!Double.isFinite(vaporFraction) || vaporFraction <= 0.0 || vaporFraction >= 1.0) {
                    throw new V3ThermoException(V3ThermoException.Code.FLASH_NONCONVERGENCE, null,
                            "V3 feed flash lost its two-phase Rachford-Rice root");
                }
                populatePhaseCompositions(workspace.normalizedOverall, workspace.logK, vaporFraction,
                        workspace.liquidComposition, workspace.vaporComposition);
                model.evaluateInto(temperatureKelvin, pressurePascal, workspace.liquidComposition, Root.LIQUID, workspace);
                model.evaluateInto(temperatureKelvin, pressurePascal, workspace.vaporComposition, Root.VAPOR, workspace);
                double maximumLogKChange = 0.0;
                for (int component = 0; component < componentCount; component++) {
                    double target = model.logFugacityCoefficient(Root.LIQUID, component, workspace)
                            - model.logFugacityCoefficient(Root.VAPOR, component, workspace);
                    maximumLogKChange = Math.max(maximumLogKChange, Math.abs(target - workspace.logK[component]));
                    workspace.nextLogK[component] = 0.5 * (workspace.logK[component] + target);
                }
                if (maximumLogKChange <= LOG_K_TOLERANCE) {
                    // PR updates can move an initial Wilson two-phase bracket onto the liquid endpoint.
                    // Bisection otherwise leaves a tiny positive vapor fraction and mislabels the single phase.
                    if (rachfordRiceResidual(workspace.normalizedOverall, workspace.logK, 0.0) <= PHASE_ENDPOINT_TOLERANCE) {
                        model.evaluateInto(temperatureKelvin, pressurePascal, workspace.normalizedOverall, Root.LIQUID, workspace);
                        return V3FlashResult.liquid(iteration, workspace.normalizedOverall,
                                model.phaseMolarEnthalpy(temperatureKelvin, workspace.normalizedOverall, Root.LIQUID, workspace),
                                "all-liquid converged PR endpoint classification");
                    }
                    double liquidEnthalpy = model.phaseMolarEnthalpy(temperatureKelvin, workspace.liquidComposition,
                            Root.LIQUID, workspace);
                    double vaporEnthalpy = model.phaseMolarEnthalpy(temperatureKelvin, workspace.vaporComposition,
                            Root.VAPOR, workspace);
                    return V3FlashResult.twoPhase(iteration, vaporFraction, workspace.liquidComposition,
                            workspace.vaporComposition, (1.0 - vaporFraction) * liquidEnthalpy + vaporFraction * vaporEnthalpy,
                            "rigorous two-phase Rachford-Rice flash");
                }
                System.arraycopy(workspace.nextLogK, 0, workspace.logK, 0, componentCount);
            }
            throw new V3ThermoException(V3ThermoException.Code.FLASH_NONCONVERGENCE, null,
                    "V3 feed flash did not converge within " + MAXIMUM_ITERATIONS + " iterations");
        }

        private static double rachfordRiceRoot(double[] composition, double[] logK) {
            double lower = 0.0;
            double upper = 1.0;
            for (int iteration = 0; iteration < RACHFORD_RICE_ITERATIONS; iteration++) {
                double midpoint = 0.5 * (lower + upper);
                double residual = rachfordRiceResidual(composition, logK, midpoint);
                if (!Double.isFinite(residual)) return Double.NaN;
                if (residual > 0.0) lower = midpoint;
                else upper = midpoint;
            }
            return 0.5 * (lower + upper);
        }

        private static double rachfordRiceResidual(double[] composition, double[] logK, double vaporFraction) {
            double residual = 0.0;
            for (int component = 0; component < composition.length; component++) {
                if (composition[component] == 0.0) continue;
                double k = DeterministicMath.exp(logK[component]);
                double kMinusOne = DeterministicMath.expm1(logK[component]);
                // Keep a tiny positive K at beta=1 instead of cancelling it in 1 + (K - 1).
                double denominator = (1.0 - vaporFraction) + vaporFraction * k;
                if (!(denominator > 0.0) || !Double.isFinite(denominator)) return Double.NaN;
                residual += composition[component] * kMinusOne / denominator;
            }
            return residual;
        }

        private static void populatePhaseCompositions(
                double[] overall, double[] logK, double vaporFraction, double[] liquid, double[] vapor) {
            double liquidTotal = 0.0;
            double vaporTotal = 0.0;
            for (int component = 0; component < overall.length; component++) {
                if (overall[component] == 0.0) {
                    liquid[component] = 0.0;
                    vapor[component] = 0.0;
                    continue;
                }
                double k = DeterministicMath.exp(logK[component]);
                liquid[component] = overall[component] / ((1.0 - vaporFraction) + vaporFraction * k);
                vapor[component] = k * liquid[component];
                liquidTotal += liquid[component];
                vaporTotal += vapor[component];
            }
            if (!Double.isFinite(liquidTotal) || !Double.isFinite(vaporTotal) || liquidTotal <= 0.0 || vaporTotal <= 0.0) {
                throw new V3ThermoException(V3ThermoException.Code.FLASH_NONCONVERGENCE, null,
                        "V3 feed flash generated a nonphysical phase composition");
            }
            for (int component = 0; component < overall.length; component++) {
                liquid[component] /= liquidTotal;
                vapor[component] /= vaporTotal;
            }
        }
    }
}
