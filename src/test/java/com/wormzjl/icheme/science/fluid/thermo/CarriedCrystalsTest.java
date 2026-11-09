package com.wormzjl.icheme.science.fluid.thermo;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult;
import com.wormzjl.icheme.science.thermo.phase.PhaseCompetition;
import com.wormzjl.icheme.science.thermo.phase.PhaseKind;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Mode (a) of batch 2026-11-02-dry-ice-cost: a step's TP, PH and UV decisions carry their crystals in the fluid phases
 * (owner decision D23, {@link PhaseCompetition#FLUID_CARRYING_CRYSTALS}). The engine states three shapes, and only a vessel's
 * refresh books a crystal. Basis: nitrogen, methane, ethane, carbon dioxide, water.
 */
class CarriedCrystalsTest {
    private static final PhaseCompetition CARRIED = PhaseCompetition.FLUID_CARRYING_CRYSTALS;
    private final FluidThermodynamics pilot = FluidTestSupport.model(com.wormzjl.icheme.science.material.PilotCryogenicTestCatalog.withPilot(), "icheme:pilot_cryogenic");

    private EquilibriumResult carried(double t, double p, double... overall) {
        var answer = pilot.phaseEngine().tp(t, p, overall, CARRIED);
        assertTrue(answer.converged(), answer.toString());
        for (var phase : answer.phases()) assertNotEquals(PhaseKind.SOLID, phase.kind(), "no crystal is booked");
        return answer;
    }
    private static double share(EquilibriumResult answer, PhaseKind kind) {
        for (var phase : answer.phases()) if (phase.kind() == kind) return phase.amount(3) / phase.total();
        throw new AssertionError("No " + kind + " in " + answer);
    }

    /** Shape one: a dense fluid is one liquid and carries its CO2, where the fluid phases alone state a second liquid. */
    @Test void aDenseFluidStaysOneLiquid() {
        double[] lng = {.01, .94, .01, .04, 0};
        assertEquals(EquilibriumResult.Classification.LIQUID_LIQUID, pilot.phaseEngine().tp(114, 1e6, lng, false).classification());
        assertEquals(1, carried(114, 1e6, lng).phases().size());
        assertEquals(1, carried(112, 1e6, 0, .6, 0, .4, 0).phases().size(), "40 % of CO2: the lean trial is no phase either");
        for (double t : new double[]{91, 100, 110, 120}) assertEquals(1, carried(t, 1e6, 0, .99, 0, .01, 0).phases().size(), "G3F7 at " + t);
    }

    /** Shape two: beside a vapour the condensed CO2 is the stand-in, a liquid of crystal species only, never in the vapour. */
    @Test void aVapourKeepsItsCondensedCarbonDioxideAsTheStandIn() {
        double[] letDown = {0, .6, 0, .4, 0};
        var answer = carried(150, 5e5, letDown);
        assertEquals(1, share(answer, PhaseKind.LIQUID), 1e-12);
        assertTrue(pilot.condensedShareOnly(pilot.flashTP(150, 5e5, letDown, () -> {})));
    }

    /** Shape three: vapour beside a methane-rich liquid, which holds the CO2 far past the crystal's solubility. */
    @Test void aMethaneRichLiquidHoldsTheCarbonDioxideSupersaturated() {
        double[] lng = {.01, .95, .01, .03, 0};
        var answer = carried(112, 101325, lng);
        var competing = pilot.phaseEngine().tp(112, 101325, lng, true);
        assertTrue(competing.classification().holdsCrystal());
        assertTrue(share(answer, PhaseKind.LIQUID) > 100 * share(competing, PhaseKind.LIQUID), answer.toString());
        assertFalse(pilot.condensedShareOnly(pilot.flashTP(112, 101325, lng, () -> {})));
    }

    /** The share condition: a phase set with no crystal-rich phase is no crystal's, and stays the liquid-liquid hold. */
    @Test void twoLiquidsWithNoCrystalRichPhaseStayTheHold() {
        double[] feed = {.5, 0, .45, .05, 0};
        assertEquals(EquilibriumResult.Classification.LIQUID_LIQUID, carried(95, 2e6, feed).classification());
        var hold = assertThrows(FluidThermodynamics.UnsupportedPhases.class, () -> pilot.flashTP(95, 2e6, feed, () -> {}));
        assertEquals(FluidThermodynamics.UnsupportedPhases.Kind.LIQUID_LIQUID, hold.kind());
    }

    /** The dense-trial and feed conditions: a vapour trial and a vapour feed are never filtered, so a let-down still splits. */
    @Test void aVapourTrialAndAVapourFeedAreNeverFiltered() {
        double[] gas = {.02, .97, .0086, .0014, 0};
        var fluid = pilot.phaseEngine().tp(134, 541000, gas, false);
        var answer = carried(134, 541000, gas);
        assertEquals(EquilibriumResult.Classification.VAPOR_LIQUID, answer.classification());
        assertEquals(share(fluid, PhaseKind.VAPOR), share(answer, PhaseKind.VAPOR), 1e-12, "a sound fluid answer stands");
        assertEquals(EquilibriumResult.Classification.VAPOR_LIQUID, carried(150, 5e5, 0, .6, 0, .4, 0).classification());
    }

    /** The supersaturation condition: above the triple point the CO2-rich liquid is stable and nothing is excluded. */
    @Test void aboveTheTriplePointNothingIsExcluded() {
        double[] feed = {0, .5, 0, .5, 0};
        var fluid = pilot.phaseEngine().tp(225, 3e6, feed, false);
        var answer = carried(225, 3e6, feed);
        assertEquals(fluid.classification(), answer.classification());
        assertEquals(EquilibriumResult.Classification.VAPOR_LIQUID, answer.classification());
        assertEquals(share(fluid, PhaseKind.LIQUID), share(answer, PhaseKind.LIQUID), 0);
    }

    /**
     * Below the triple point the equation of state continues vapour, liquid and frost as three fluid phases, and neither
     * liquid's split with the vapour is stable: the fluid phases alone are refused across the window (3 % of CO2 at 1 atm, 113
     * to 129 K; 2.8 % at 541 kPa near 140 K). Carried, the window answers one liquid beside the vapour, its amounts conserved.
     */
    @Test void theThreeFluidWindowAnswersOneLiquidBesideTheVapour() {
        double[] lng = {.01, .95, .01, .03, 0};
        for (double t : new double[]{113.2, 116, 120, 125, 129}) {
            assertFalse(pilot.phaseEngine().tp(t, 101325, lng, false).converged(), "the fluid phases alone at " + t);
            var answer = carried(t, 101325, lng);
            assertEquals(EquilibriumResult.Classification.VAPOR_LIQUID, answer.classification(), "at " + t);
            double co2 = 0;
            for (var phase : answer.phases()) co2 += phase.amount(3);
            assertEquals(lng[3], co2, 1e-12, "at " + t);
        }
        double[] gas = {.02, .95, .002, .028, 0};
        assertEquals(EquilibriumResult.Classification.VAPOR_LIQUID, carried(139.5, 541000, gas).classification());
        // The lean liquid here is ethane-rich, with a little less trace nitrogen than the mixed liquid: still that liquid with CO2 out.
        for (double t : new double[]{120, 125, 130, 135}) {
            assertFalse(pilot.phaseEngine().tp(t, 1e5, new double[]{.1, .6, .1, .1, 0}, false).converged(), "the fluid phases alone at " + t);
            assertEquals(EquilibriumResult.Classification.VAPOR_LIQUID, carried(t, 1e5, .1, .6, .1, .1, 0).classification(), "at " + t);
        }
    }

    /** A PH run through the window finds an answer at every enthalpy: a junction's let-down has no refused stretch. */
    @Test void aLetDownEnthalpyRunIsAnsweredThroughTheWindow() {
        double[] lng = {.01, .95, .01, .03, 0};
        double low = carried(105, 101325, lng).enthalpy(), high = carried(135, 101325, lng).enthalpy();
        for (int i = 0; i <= 30; i++) {
            var answer = pilot.phaseEngine().ph(101325, low + (high - low) * i / 30, lng, CARRIED);
            assertTrue(answer.converged(), "at " + i + ": " + answer);
        }
    }

    /** Only a refresh books a crystal, and carrying changed none of its answers (values read on the code before this batch's change). */
    @Test void aRefreshStillBooksItsCrystalUnchanged() {
        double[] n = {1, 95, 1, 3, 0};
        var taut = pilot.crystalsAtVolume(n, -1.5e6, .04, 114, 8e5, true, () -> {});
        assertEquals(110.077204892204, taut.temperature(), 1e-9);
        assertEquals(100578.126807334, taut.pressure(), 1e-6);
        assertEquals(2.97985720124467, taut.crystalMoles()[0], 1e-9);
        var slack = pilot.crystalsAtPressure(n, -1.2e6, 101325, true, () -> {});
        assertEquals(111.505052990387, slack.temperature(), 1e-9);
        assertEquals(2.98474115800194, slack.crystalMoles()[0], 1e-9);
    }

    @Test void theCarriedRequestNamesNoCrystalAndWantsACrystalPackage() {
        assertThrows(IllegalArgumentException.class, () -> new PhaseCompetition(new TreeSet<>(), true, true));
        assertFalse(CARRIED.fluidOnly());
        assertFalse(CARRIED.crystalsCompete());
        assertEquals("fluid+carried-crystals", CARRIED.toString());
        assertEquals(CARRIED, pilot.stepCompetition(120, new double[]{.01, .95, .01, .03, 0}));
        assertEquals(PhaseCompetition.FLUID_ONLY, pilot.stepCompetition(120, new double[]{.01, .95, .04, 0, 0}), "no crystal species");
    }
}
