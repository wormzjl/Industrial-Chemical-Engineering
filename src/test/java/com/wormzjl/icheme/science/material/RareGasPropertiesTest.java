package com.wormzjl.icheme.science.material;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The rare gases on the network package (batch 2026-11-15-fluid-interfaces, W1, plan section 4.5): argon, neon, helium,
 * krypton and xenon from their ChemSep records, appended to {@code icheme:refinery}. Each check runs the network's own
 * flash on the dry view of one gas.
 */
final class RareGasPropertiesTest {
    private static final MaterialCatalog CATALOG = MaterialCatalog.bundled();
    private static final String NETWORK = CATALOG.presets().network().packageId();
    private static final FluidThermodynamics REFINERY = FluidThermodynamics.forNetwork(CATALOG, NETWORK);
    private static final double ATM = 101_325;
    private static final List<String> GASES = List.of("Argon", "Neon", "Helium", "Krypton", "Xenon");
    /**
     * Second virial coefficients at 298.15 K, m3/mol (Dymond, Marsh, Wilhoit and Wong, Landolt-Bornstein IV/21A, 2002,
     * rounded): the real gas at 1 atm is Z = 1 + B p / (R T). Xenon's Z is 0.9947, so the plan's "within 0.2 % of the ideal
     * gas" holds for the real gas, not the ideal one.
     */
    private static final double[] VIRIAL = {-15.8e-6, 11.3e-6, 11.8e-6, -50.5e-6, -130.5e-6};

    private static FluidThermodynamics.State pure(String gas, double t, double p) {
        var dry = REFINERY.on(List.of(gas), false);
        double[] n = new double[dry.componentCount()];
        n[dry.components().indexOf(gas)] = 1;
        return dry.flashTP(t, p, n, () -> {
        });
    }

    @Test void theRareGasesFollowEthanolOnTheNetworkPackage() {
        var pack = CATALOG.requirePackage(NETWORK);
        assertEquals("refinery-tier1-r5", pack.revision());
        int ethanol = pack.components().indexOf("Ethanol");
        assertEquals(GASES, pack.components().subList(ethanol + 1, ethanol + 6), "appended after ethanol, in this order");
        assertEquals(ethanol + 6, pack.components().size(), "the package's last compounds");
    }

    /** Argon at 87.30 K, krypton at 119.7 K and xenon at 165.0 K boil at 1 atm: gas 3 % below it, liquid 3 % above. */
    @Test void argonKryptonAndXenonBoilAtTheirNormalBoilingPoints() {
        String[] gas = {"Argon", "Krypton", "Xenon"};
        double[] boiling = {87.30, 119.7, 165.0};
        for (int g = 0; g < gas.length; g++) {
            var below = pure(gas[g], boiling[g], .97 * ATM);
            var above = pure(gas[g], boiling[g], 1.03 * ATM);
            assertTrue(FluidThermodynamics.holdsPhase(below, FluidThermodynamics.GAS) && !FluidThermodynamics.holdsLiquid(below), gas[g] + ": gas at 0.97 atm");
            assertTrue(FluidThermodynamics.holdsLiquid(above) && !FluidThermodynamics.holdsPhase(above, FluidThermodynamics.GAS), gas[g] + ": liquid at 1.03 atm");
            double low = .97 * ATM, high = 1.03 * ATM;
            for (int i = 0; i < 40; i++) {
                double mid = .5 * (low + high);
                if (FluidThermodynamics.holdsLiquid(pure(gas[g], boiling[g], mid))) high = mid; else low = mid;
            }
            System.out.printf(Locale.ROOT, "W1 %s: saturation at %.2f K between %.1f and %.1f Pa (%.2f %% of 1 atm)%n", gas[g], boiling[g], low, high,
                    100 * (.5 * (low + high) / ATM - 1));
        }
    }

    /** Each gas at 298.15 K and 101 325 Pa: one gas phase, its density within 0.2 % of the real gas's (second virial). */
    @Test void eachGasWeighsAsTheRealGasAtAmbient() {
        double t = 298.15;
        for (int g = 0; g < GASES.size(); g++) {
            var dry = REFINERY.on(List.of(GASES.get(g)), false);
            var state = pure(GASES.get(g), t, ATM);
            assertTrue(FluidThermodynamics.holdsPhase(state, FluidThermodynamics.GAS), GASES.get(g));
            assertFalse(FluidThermodynamics.holdsLiquid(state), GASES.get(g));
            double m = dry.molecularWeight(dry.components().indexOf(GASES.get(g)));
            double ideal = ATM * m / (FluidThermodynamics.R * t);
            double real = ideal / (1 + VIRIAL[g] * ATM / (FluidThermodynamics.R * t));
            double density = dry.vaporDensity(state);
            System.out.printf(Locale.ROOT, "W1 %s: %.6f kg/m3 at 298.15 K and 1 atm, ideal %.6f (%+.3f %%), real %.6f (%+.3f %%)%n", GASES.get(g), density,
                    ideal, 100 * (density / ideal - 1), real, 100 * (density / real - 1));
            assertEquals(real, density, .002 * real, GASES.get(g));
        }
    }

    /** Helium and neon, quantum gases on PR78 with a negative acentric factor: one finite gas phase from 100 to 600 K, 1 to 20 bar. */
    @Test void heliumAndNeonAreFiniteGasesFrom100To600Kelvin() {
        for (String gas : List.of("Helium", "Neon")) {
            var dry = REFINERY.on(List.of(gas), false);
            double m = dry.molecularWeight(dry.components().indexOf(gas));
            for (double t : new double[] {100, 200, 300, 450, 600}) for (double p : new double[] {1e5, 5e5, 2e6}) {
                var state = pure(gas, t, p);
                assertTrue(FluidThermodynamics.holdsPhase(state, FluidThermodynamics.GAS) && !FluidThermodynamics.holdsLiquid(state), gas + " at " + t + " K, " + p + " Pa");
                double density = dry.vaporDensity(state), ideal = p * m / (FluidThermodynamics.R * t);
                assertTrue(Double.isFinite(density) && Math.abs(density / ideal - 1) < .05, gas + " at " + t + " K, " + p + " Pa: " + density + " against " + ideal);
            }
        }
    }
}
