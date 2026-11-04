package com.wormzjl.icheme.science.column.v3.thermo;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class V3Tjl20MethanePropertyPackageTest {
    private static final String TJL19 = "icheme:tjl19_dwsim", TJL19_ASSAY = "icheme:tia_juana_light";
    private static final String TJL20 = "icheme:tjl20_methane", TJL20_ASSAY = "icheme:tia_juana_light_methane";

    @Test void extendsTheFrozenDatasetWithoutChangingItsPropertiesOrInteractions() {
        var base = V3CatalogPropertyPackage.require(TJL19);
        var extended = V3CatalogPropertyPackage.require(TJL20);
        assertEquals(extended.componentBasis(), V3CatalogPropertyPackage.require(extended.packageId()).componentBasis());
        assertEquals(base.componentBasis().componentCount()+1, extended.componentBasis().componentCount());
        assertNotEquals(base.packageId(), extended.packageId());
        double[][] original = base.binaryInteractions();
        double[][] interactions = extended.binaryInteractions();
        for (int component = 0; component < base.componentBasis().componentCount(); component++) {
            assertEquals(base.component(component), extended.component(component + 1));
            assertEquals(0, interactions[0][component + 1]);
            assertEquals(0, interactions[component + 1][0]);
            for (int other = 0; other < base.componentBasis().componentCount(); other++) {
                assertEquals(original[component][other], interactions[component + 1][other + 1]);
            }
        }
        interactions[1][2] = 123;
        assertEquals(original[0][1], extended.binaryInteractions()[1][2]);
        double[] feed = extended.crudeFeed(TJL20_ASSAY).moleFractions();
        double[] oldFeed = base.crudeFeed(TJL19_ASSAY).moleFractions();
        assertEquals(0.005, feed[0], 1e-15);
        for (int component = 0; component < base.componentBasis().componentCount(); component++) assertEquals(oldFeed[component] * 0.995, feed[component + 1], 1e-15);
    }

    @Test void methaneThermalFitMatchesTheSetzmannWagnerIdealGasAndDifferentiatesConsistently() {
        var methane = V3CatalogPropertyPackage.require(TJL20).component(0);
        assertEquals(190.564, methane.criticalTemperatureKelvin());
        assertEquals(4_599_200, methane.criticalPressurePascal());
        assertEquals(0.01142, methane.acentricFactor());
        assertEquals(0, methane.idealGasEnthalpy(298.15));
        // The reference is the closed form the record was fitted to, not the fitted polynomial: the ideal-gas part of
        // Setzmann and Wagner (1991) as CoolProp 8.0.0 states it (Methane.json alpha0, MIT); until batch
        // 2026-11-04-open-thermo-data it was the NIST WebBook Shomate expression. Fit worst 0.013 J/(mol K), 0.21 J/mol.
        for (int point = 0; point <= 1000; point++) {
            double temperature = 298.15 + (900 - 298.15) * point / 1000;
            assertEquals(setzmannWagnerHeatCapacity(temperature), methane.idealGasHeatCapacity(temperature), 0.05, "Cp at " + temperature);
            assertEquals(setzmannWagnerEnthalpy(temperature) - setzmannWagnerEnthalpy(298.15), methane.idealGasEnthalpy(temperature), 0.5,
                    "H relative to 298.15 K at " + temperature);
            double derivative = (methane.idealGasEnthalpy(temperature + 0.001)
                    - methane.idealGasEnthalpy(temperature - 0.001)) / 0.002;
            assertEquals(methane.idealGasHeatCapacity(temperature), derivative, 1e-6);
        }
    }

    @Test void zeroMethaneRetainsTheOriginalFlashAndMethaneEntersTheVaporPhase() {
        var base = V3PengRobinsonThermo.fromRegisteredPackage(TJL19);
        var extended = V3PengRobinsonThermo.fromRegisteredPackage(TJL20);
        double[] oldFeed = base.crudeFeed(TJL19_ASSAY).moleFractions();
        double[] zeroMethane = new double[extended.componentBasis().componentCount()];
        System.arraycopy(oldFeed, 0, zeroMethane, 1, oldFeed.length);
        var expected = base.flashTP(638.15, 250_000, oldFeed, base.newWorkspace());
        var actual = extended.flashTP(638.15, 250_000, zeroMethane, extended.newWorkspace());
        assertEquals(expected.phase(), actual.phase());
        assertEquals(expected.vaporFraction(), actual.vaporFraction(), 1e-10);
        assertEquals(expected.molarEnthalpyJoulesPerMol(), actual.molarEnthalpyJoulesPerMol(), 1e-6);
        double[] feed = extended.crudeFeed(TJL20_ASSAY).moleFractions();
        var flash = extended.flashTP(638.15, 250_000, feed, extended.newWorkspace());
        assertEquals(V3FeedPhase.TWO_PHASE, flash.phase());
        double[] x = flash.liquidComposition();
        double[] y = flash.vaporComposition();
        assertTrue(y[0] > x[0]);
        for (int component = 0; component < feed.length; component++) {
            assertEquals(feed[component], (1 - flash.vaporFraction()) * x[component] + flash.vaporFraction() * y[component], 1e-10);
        }
    }

    /** CoolProp Methane.json EOS[0].alpha0: R = 8.31451, log_tau a = 3.0016, Planck-Einstein n_k at theta_k = v_k (K). */
    private static final double R = 8.31451, LOG_TAU = 3.0016;
    private static final double[] N = {0.008449, 4.6942, 3.4865, 1.6572, 1.4115}, THETA = {648, 1957, 3895, 5705, 15080};
    private static double setzmannWagnerHeatCapacity(double temperature) {
        double c = 1 + LOG_TAU;
        for (int k = 0; k < N.length; k++) { double x = THETA[k] / temperature, e = Math.exp(-x); c += N[k] * x * x * e / ((1 - e) * (1 - e)); }
        return R * c;
    }
    private static double setzmannWagnerEnthalpy(double temperature) {
        double c = 1 + LOG_TAU;
        for (int k = 0; k < N.length; k++) { double x = THETA[k] / temperature; c += N[k] * x / Math.expm1(x); }
        return R * temperature * c;
    }
}
