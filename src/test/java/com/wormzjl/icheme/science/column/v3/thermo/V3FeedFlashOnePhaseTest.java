package com.wormzjl.icheme.science.column.v3.thermo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The feed flash answers a feed with no two-phase solution as one phase (batch 2026-11-15-supercritical-fluids, D1,
 * COLUMN_FEED_REVIEW.md): its label by reduced density on both sides of 1, its enthalpy on its own root, every feed the
 * frozen flash answered unchanged to the bit, and a refusal that is no collapse kept.
 */
class V3FeedFlashOnePhaseTest {
    private static final String PACKAGE = "icheme:tjl20_methane_nitrogen";
    private static final Map<String, Double> METHANE_ETHANE = Map.of("Methane", 0.5, "Ethane", 0.5);
    private static final Map<String, Double> NATURAL_GAS = Map.of("Methane", 0.90, "Ethane", 0.06, "Propane", 0.03, "Nitrogen", 0.01);
    private static final Map<String, Double> METHANE_CUT = Map.of("Methane", 0.9, "crude_pc03", 0.1);

    /** Whether a flash result is an answer of the one-phase rule ({@code V3FeedFlash.onePhase}). */
    static boolean isOnePhaseRule(V3FlashResult result) {
        return result.phase() != V3FeedPhase.TWO_PHASE && result.detail().startsWith(V3FeedFlash.ONE_PHASE);
    }

    @Test
    void labelFollowsReducedDensityOnBothSidesOfOne() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE);
        double[] z = feed(thermo, METHANE_ETHANE);
        // 300 K is above the 50/50 mixture's pseudo-critical temperature; the root is lighter than the cubic's critical
        // volume at 10 MPa (reduced density 0.961) and denser at 12 MPa (1.197).
        assertOnePhase(thermo, z, 300.0, 10.0e6, V3FeedPhase.VAPOR, 4,
                "one phase, no two-phase split above the mixture's pseudo-critical temperature: vapour by reduced density 0.961");
        assertOnePhase(thermo, z, 300.0, 12.0e6, V3FeedPhase.LIQUID, 10,
                "one phase, no two-phase split above the mixture's pseudo-critical temperature: liquid by reduced density 1.197");
        // A compressed one-root liquid below the mixture's own supercritical isotherm, its root lost at the vapour end.
        assertOnePhase(thermo, z, 250.0, 8.0e6, V3FeedPhase.LIQUID, 62,
                "one phase, no two-phase split below the mixture's pseudo-critical temperature: liquid by reduced density 1.866");
        // A slow collapse that runs out of iterations with |ln K| about 1e-11.
        assertOnePhase(thermo, z, 275.0, 12.0e6, V3FeedPhase.LIQUID, 64,
                "one phase, no two-phase split above the mixture's pseudo-critical temperature: liquid by reduced density 1.64");
        // A natural gas above its cricondentherm, refused at the second iteration with |ln K| still 2.6.
        assertOnePhase(thermo, feed(thermo, NATURAL_GAS), 250.0, 6.0e6, V3FeedPhase.VAPOR, 2,
                "one phase, no two-phase split above the mixture's pseudo-critical temperature: vapour by reduced density 0.453");
    }

    @Test
    void aRealSplitThatDoesNotConvergeStaysRefused() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE);
        // The equilibrium engine splits this feed (vapour fraction 0.125); successive substitution stalls near the
        // critical point with |ln K| at 0.48. It is no collapse, and stays the typed refusal it was.
        V3ThermoException refusal = assertThrows(V3ThermoException.class,
                () -> thermo.flashTP(250.0, 6.0e6, feed(thermo, METHANE_ETHANE), thermo.newWorkspace()));
        assertEquals(V3ThermoException.Code.FLASH_NONCONVERGENCE, refusal.code());
        assertEquals("V3 feed flash did not converge within 64 iterations", refusal.getMessage());
        // Methane with a heavy cut at 300 K and 20 MPa: a real split the engine finds; stalled at |ln K| 4.2.
        assertThrows(V3ThermoException.class,
                () -> thermo.flashTP(300.0, 20.0e6, feed(thermo, METHANE_CUT), thermo.newWorkspace()));
    }

    /**
     * The review's grid on the production package: every feed the frozen flash ({@code V3FeedFlashReference}) answers
     * is answered to the bit, including every real two-phase feed; a frozen refusal is either the same refusal or a
     * one-phase answer of the rule. The counts are the review's.
     */
    @Test
    void everyFeedAnsweredBeforeIsUnchangedToTheBit() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE);
        int[] answeredBefore = new int[3], twoPhase = new int[3], answeredNow = new int[3], refused = new int[3];
        Map<String, Double>[] mixtures = new Map[] {METHANE_ETHANE, NATURAL_GAS, METHANE_CUT};
        for (int m = 0; m < mixtures.length; m++) {
            double[] z = feed(thermo, mixtures[m]);
            for (double t = 250.0; t <= 400.0; t += 25.0) {
                for (double pressureMegapascal : new double[] {2, 4, 6, 8, 10, 12, 15, 20, 25, 30}) {
                    double p = pressureMegapascal * 1.0e6;
                    V3ThermoWorkspace oldWorkspace = thermo.newWorkspace();
                    V3ThermoWorkspace newWorkspace = thermo.newWorkspace();
                    System.arraycopy(z, 0, oldWorkspace.normalizedOverall, 0, z.length);
                    System.arraycopy(z, 0, newWorkspace.normalizedOverall, 0, z.length);
                    String at = m + " at " + t + " K, " + pressureMegapascal + " MPa";
                    V3FlashResult expected;
                    try {
                        expected = V3FeedFlashEquivalenceTest.V3FeedFlashReference.resolve(thermo, t, p, oldWorkspace);
                    } catch (V3ThermoException failure) {
                        try {
                            V3FlashResult answered = V3FeedFlash.resolve(thermo, t, p, newWorkspace);
                            assertTrue(isOnePhaseRule(answered), at + ": " + answered.detail());
                            answeredNow[m]++;
                        } catch (V3ThermoException actual) {
                            assertEquals(failure.getMessage(), actual.getMessage(), at);
                            assertEquals(failure.code(), actual.code(), at);
                            refused[m]++;
                        }
                        continue;
                    }
                    V3FlashResult actual = V3FeedFlash.resolve(thermo, t, p, newWorkspace);
                    assertEquals(expected.phase(), actual.phase(), at);
                    assertEquals(expected.iterations(), actual.iterations(), at);
                    assertEquals(expected.detail(), actual.detail(), at);
                    assertEquals(Double.doubleToLongBits(expected.vaporFraction()), Double.doubleToLongBits(actual.vaporFraction()), at);
                    assertEquals(Double.doubleToLongBits(expected.molarEnthalpyJoulesPerMol()),
                            Double.doubleToLongBits(actual.molarEnthalpyJoulesPerMol()), at);
                    assertArrayEquals(expected.liquidComposition(), actual.liquidComposition(), at);
                    assertArrayEquals(expected.vaporComposition(), actual.vaporComposition(), at);
                    assertArrayEquals(oldWorkspace.nextLogK, newWorkspace.nextLogK, at);
                    assertFalse(isOnePhaseRule(actual), at);
                    answeredBefore[m]++;
                    if (actual.phase() == V3FeedPhase.TWO_PHASE) twoPhase[m]++;
                }
            }
        }
        // Methane/ethane: 56 answered (2 of them two-phase), 13 refusals now answered, the stalled real split kept.
        // Natural gas: 57 answered, 13 now answered. Methane with the cut: 54 answered (49 two-phase), 16 real splits kept.
        assertArrayEquals(new int[] {56, 57, 54}, answeredBefore);
        assertArrayEquals(new int[] {1, 0, 49}, twoPhase);
        assertArrayEquals(new int[] {13, 13, 0}, answeredNow);
        assertArrayEquals(new int[] {1, 0, 16}, refused);
    }

    private static void assertOnePhase(V3PengRobinsonThermo thermo, double[] z, double t, double p, V3FeedPhase phase,
                                       int iterations, String detail) {
        V3FlashResult flash = thermo.flashTP(t, p, z, thermo.newWorkspace());
        assertEquals(phase, flash.phase(), flash::detail);
        assertEquals(iterations, flash.iterations());
        assertTrue(flash.detail().startsWith(detail), flash::detail);
        assertEquals(phase == V3FeedPhase.VAPOR ? 1.0 : 0.0, flash.vaporFraction());
        assertArrayEquals(z, phase == V3FeedPhase.VAPOR ? flash.vaporComposition() : flash.liquidComposition(), 1.0e-15);
        // The enthalpy is the feed's own on the labelled root.
        Root root = phase == V3FeedPhase.VAPOR ? Root.VAPOR : Root.LIQUID;
        assertEquals(thermo.molarEnthalpy(t, p, z, root, thermo.newWorkspace()), flash.molarEnthalpyJoulesPerMol());
    }

    private static double[] feed(V3PengRobinsonThermo thermo, Map<String, Double> parts) {
        double[] z = new double[thermo.componentBasis().componentCount()];
        for (int i = 0; i < z.length; i++) z[i] = parts.getOrDefault(thermo.componentBasis().componentId(i), 0.0);
        return z;
    }
}
