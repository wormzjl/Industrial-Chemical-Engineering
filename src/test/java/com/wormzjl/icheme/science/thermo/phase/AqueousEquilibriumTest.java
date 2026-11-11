package com.wormzjl.icheme.science.thermo.phase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.material.PilotAqueousTestCatalog;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.Classification;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The equilibrium engine with water in the equation of state (batch 2026-11-11-aqueous-solvents, W3a D3 to D5 and the
 * engine gates of D7) on the pilot list {@code icheme:pilot_aqueous}: three fluid phases at T and P and their steps in PH
 * and UV, ethanol and water as one liquid, gases dissolved, ice as water's crystal beside another crystal, round trips,
 * conservation, the label flip of a one-root phase, and the kernel evaluations recorded as W3b's baseline.
 */
class AqueousEquilibriumTest {
    private static final double R = PengRobinsonKernel.GAS_CONSTANT;
    private static final double ATM = 101325.0;
    private static final FluidTpEquilibrium ENGINE = FluidTpEquilibrium.forDerivedPackage(PilotAqueousTestCatalog.catalog(),
            PilotAqueousTestCatalog.PACKAGE_ID);
    private static final List<String> BASIS = ENGINE.contract().components();
    private static final PhaseCompetition FLUID = PhaseCompetition.FLUID_ONLY;
    private static final PhaseCompetition ICE = ENGINE.contract().crystalCompetition();
    /** Perry's table at 101.325 kPa: x ethanol, y ethanol, T in C (W2b's points). */
    private static final double[][] PERRY = {{0.0190, 0.1700, 95.5}, {0.0721, 0.3891, 89.0}, {0.0966, 0.4375, 86.7}, {0.1238, 0.4704, 85.3},
            {0.1661, 0.5089, 84.1}, {0.2337, 0.5445, 82.7}, {0.2608, 0.5580, 82.3}, {0.3273, 0.5826, 81.5}, {0.3965, 0.6122, 80.7},
            {0.5079, 0.6564, 79.8}, {0.5198, 0.6599, 79.7}, {0.5732, 0.6841, 79.3}, {0.6763, 0.7385, 78.74}, {0.7472, 0.7815, 78.41},
            {0.8943, 0.8943, 78.15}};

    private static double[] feed(Object... pairs) {
        double[] amounts = new double[BASIS.size()];
        for (int k = 0; k < pairs.length; k += 2) amounts[BASIS.indexOf((String) pairs[k])] = ((Number) pairs[k + 1]).doubleValue();
        return amounts;
    }

    private static EquilibriumResult tp(double t, double p, double[] z, PhaseCompetition competition) {
        var r = ENGINE.tp(EquilibriumRequest.tp(t, p, BASIS, z, competition));
        assertTrue(r.converged(), () -> "TP " + t + " K, " + p + " Pa: " + r.detail());
        conserved(r, z);
        return r;
    }

    private static boolean holdsVapour(EquilibriumResult r) {
        for (PhaseAmounts phase : r.phases()) if (phase.kind() == PhaseKind.VAPOR) return true;
        return false;
    }

    private static boolean holdsSolid(EquilibriumResult r) {
        for (PhaseAmounts phase : r.phases()) if (phase.kind() == PhaseKind.SOLID) return true;
        return false;
    }

    /** The lowest temperature with a vapour at {@code p} between {@code low} (none) and {@code high} (a vapour), to 1e-9 K. */
    private static double bubble(double[] z, double p, double low, double high) {
        assertTrue(!holdsVapour(tp(low, p, z, FLUID)) && holdsVapour(tp(high, p, z, FLUID)), "bracket of the bubble point");
        while (high - low > 1.0e-9) {
            double mid = 0.5 * (low + high);
            if (holdsVapour(tp(mid, p, z, FLUID))) high = mid; else low = mid;
        }
        return high;
    }

    /** Every compound of every answer sums to its feed, to rounding. */
    private static void conserved(EquilibriumResult r, double[] z) {
        double total = 0.0;
        for (double amount : z) total += amount;
        for (int i = 0; i < z.length; i++) {
            double sum = 0.0;
            for (PhaseAmounts phase : r.phases()) sum += phase.amount(i);
            assertEquals(z[i], sum, 1.0e-12 * total, "compound " + BASIS.get(i) + " in " + r.classification());
        }
    }

    private static double fraction(PhaseAmounts phase, String id) {
        return phase.amount(BASIS.indexOf(id)) / phase.total();
    }

    @Test void waterAndHexaneHaveThreePhasesAtOneTemperature() {
        double[] z = feed("Water", 0.5, "N-hexane", 0.5);
        double t3 = bubble(z, ATM, 320.0, 345.0);
        // The step of H(T) there: an enthalpy between its ends is the three phases at that temperature.
        double below = tp(t3 - 1.0e-3, ATM, z, FLUID).enthalpy(), above = tp(t3 + 1.0e-3, ATM, z, FLUID).enthalpy();
        var ph = ENGINE.ph(EquilibriumRequest.ph(ATM, 0.5 * (below + above), BASIS, z, FLUID));
        assertTrue(ph.converged(), ph.detail());
        conserved(ph, z);
        assertEquals(Classification.VAPOR_LIQUID_LIQUID, ph.classification());
        double vapour = fraction(ph.phases().get(0), "N-hexane");
        System.out.printf(Locale.ROOT, "water and n-hexane at 101.325 kPa: three phases at %.3f C, vapour %.4f hexane%n", t3 - 273.15, vapour);
        assertEquals(61.4, t3 - 273.15, 0.5);
        assertEquals(0.79, vapour, 0.02);
        assertEquals(t3, ph.phases().get(0).temperature(), 1.0e-6);
    }

    @Test void ethanolWaterAndHexaneBubbleIntoThreePhases() {
        double[] z = feed("Ethanol", 0.2, "Water", 0.4, "N-hexane", 0.4);
        double t = bubble(z, ATM, 320.0, 345.0);
        var r = tp(t + 1.0e-6, ATM, z, FLUID);
        System.out.printf(Locale.ROOT, "ethanol 0.2, water 0.4, n-hexane 0.4 at 101.325 kPa: bubble point %.3f C (%s), %d kernel evaluations%n",
                t - 273.15, r.classification(), r.diagnostics().kernelEvaluations());
        assertEquals(57.0, t - 273.15, 1.0);
        assertEquals(Classification.VAPOR_LIQUID_LIQUID, r.classification());
    }

    @Test void ethanolAndWaterAreOneLiquidWithW2bBubblePoints() {
        for (int k = 1; k < 20; k++) {
            double x = 0.05 * k;
            var r = tp(298.15, ATM, feed("Ethanol", x, "Water", 1.0 - x), FLUID);
            assertEquals(Classification.SINGLE_LIQUID, r.classification(), "x ethanol " + x);
        }
        double temperature = 0.0, vapour = 0.0;
        for (double[] point : PERRY) {
            double[] z = feed("Ethanol", point[0], "Water", 1.0 - point[0]);
            double t = bubble(z, ATM, 340.0, 380.0);
            var r = tp(t, ATM, z, FLUID);
            temperature += Math.abs(t - 273.15 - point[2]);
            vapour += Math.abs(fraction(r.phases().get(0), "Ethanol") - point[1]);
        }
        temperature /= PERRY.length;
        vapour /= PERRY.length;
        System.out.printf(Locale.ROOT, "ethanol and water at 1 atm through the engine: AAD T %.3f K, AAD y %.4f%n", temperature, vapour);
        assertTrue(temperature <= 0.25, "AAD T " + temperature);
        assertTrue(vapour <= 0.008, "AAD y " + vapour);
    }

    @Test void carbonDioxideDissolvesInWaterAndEthanolByW2bBounds() {
        var catalog = PilotAqueousTestCatalog.catalog();
        var water = catalog.requirePackage(PilotAqueousTestCatalog.PACKAGE_ID).water();
        double henry = catalog.henryInWater(PilotAqueousTestCatalog.PACKAGE_ID, "CarbonDioxide").orElseThrow().pascal(water, 298.15);
        var wet = tp(298.15, ATM, feed("Water", 0.9, "CarbonDioxide", 0.1), FLUID);
        var gas = wet.phases().get(0);
        var liquid = wet.phases().get(1);
        double inWater = fraction(liquid, "CarbonDioxide") / (fraction(gas, "CarbonDioxide") * ATM / henry);
        var ethanol = tp(298.15, ATM, feed("Ethanol", 0.9, "CarbonDioxide", 0.1), FLUID);
        double inEthanol = fraction(ethanol.phases().get(1), "CarbonDioxide") / (5.5e-3 * fraction(ethanol.phases().get(0), "CarbonDioxide"));
        System.out.printf(Locale.ROOT, "CO2 at 25 C, 101.325 kPa: in water %.4f of its Henry constant, in ethanol %.3f of the homomorph reference%n",
                inWater, inEthanol);
        assertEquals(1.0, inWater, 0.05);
        assertTrue(inEthanol >= 1 / 1.3 && inEthanol <= 1.3, "in ethanol " + inEthanol);
    }

    @Test void iceFormsOutOfTenPercentEthanolBelowMinusFour() {
        double xe = (10.0 / 46.069) / (10.0 / 46.069 + 90.0 / 18.015);
        double[] z = feed("Ethanol", xe, "Water", 1.0 - xe);
        double low = 255.0, high = 273.15;
        assertTrue(holdsSolid(tp(low, ATM, z, ICE)) && !holdsSolid(tp(high, ATM, z, ICE)));
        while (high - low > 1.0e-6) {
            double mid = 0.5 * (low + high);
            if (holdsSolid(tp(mid, ATM, z, ICE))) low = mid; else high = mid;
        }
        // Pure water melts on the record's melting line through the anchor: 273.1525 K at 101.325 kPa (IAPWS R14-08).
        double[] pure = feed("Water", 1.0);
        double melt = 260.0, liquid = 274.0;
        while (liquid - melt > 1.0e-6) {
            double mid = 0.5 * (melt + liquid);
            if (holdsSolid(tp(mid, ATM, pure, ICE))) melt = mid; else liquid = mid;
        }
        System.out.printf(Locale.ROOT, "ice onset of 10 %% ethanol by mass at 101.325 kPa: %.3f C; pure water melts at %.4f K%n", high - 273.15, liquid);
        assertTrue(high - 273.15 >= -5.5 && high - 273.15 <= -3.5, "ice onset " + (high - 273.15));
        assertEquals(273.1525, liquid, 2.0e-3);
        var ice = ENGINE.anchoredCrystal("icheme:crystal_ice_ih");
        var ev = ENGINE.evaluator();
        double[] water = new double[ev.componentCount()];
        water[ev.waterComponent()] = 1.0;
        PhaseState l = ev.evaluate(273.16, 611.657, water, PengRobinsonKernel.Root.LIQUID, ev.newWorkspace(), ev.newState());
        PhaseState s = ice.evaluate(273.16, 611.657, new PhaseState(1));
        assertEquals(6007.0, l.balanceEnthalpy() - s.balanceEnthalpy(), 1.0e-6, "fusion on the balance book at the anchor");
        assertEquals(1.965e-5, s.balanceVolume(), 1.0e-12, "ice's own volume on the balance book");
        // The volume of fusion agrees between the books at the anchor, so the melting line and the balances agree.
        assertEquals(l.balanceVolume() - s.balanceVolume(), l.molarVolume() - s.molarVolume(), 1.0e-12);
    }

    @Test void iceAndDryIceFormTogether() {
        double[] z = feed("Nitrogen", 0.8, "CarbonDioxide", 0.15, "Water", 0.05);
        var r = tp(170.0, ATM, z, ICE);
        int solids = 0;
        for (PhaseAmounts phase : r.phases()) if (phase.kind() == PhaseKind.SOLID) solids++;
        assertEquals(2, solids, r.detail());
        assertEquals(Classification.VAPOR_SOLID, r.classification());
        double rt = R * 170.0;
        PhaseState gas = r.phases().get(0).state();
        for (String id : List.of("icheme:crystal_ice_ih", "icheme:crystal_carbon_dioxide_i")) {
            var c = ENGINE.anchoredCrystal(id);
            int i = ENGINE.evaluator().components().indexOf(c.component());
            assertEquals(0.0, (gas.chemicalPotential(i) - c.chemicalPotential(170.0, ATM)) / rt, 1.0e-8, id);
        }
        var alone = tp(180.0, ATM, feed("CarbonDioxide", 0.5, "Water", 0.5), ICE);
        assertEquals(Classification.SOLID_PRESENT, alone.classification(), alone.detail());
        assertEquals(2, alone.phases().size());
    }

    private static int count(EquilibriumResult r, PhaseKind kind) {
        int n = 0;
        for (PhaseAmounts phase : r.phases()) if (phase.kind() == kind) n++;
        return n;
    }

    /** The highest temperature at {@code p} where ice forms, between {@code low} (ice) and {@code high} (none), to 1e-6 K. */
    private static double iceOnset(double[] z, double p, double low, double high) {
        assertTrue(holdsSolid(tp(low, p, z, ICE)) && !holdsSolid(tp(high, p, z, ICE)), "bracket of the ice onset");
        while (high - low > 1.0e-6) {
            double mid = 0.5 * (low + high);
            if (holdsSolid(tp(mid, p, z, ICE))) low = mid; else high = mid;
        }
        return high;
    }

    /** PH and UV of a TP answer return its state, to 1e-6 of N R T (W3b step 1). */
    private static void roundTrips(EquilibriumResult r, double t, double p, double[] z, PhaseCompetition competition) {
        double nrt = R * t;
        var ph = ENGINE.ph(EquilibriumRequest.ph(p, r.enthalpy(), BASIS, z, competition));
        assertTrue(ph.converged(), () -> "PH at " + t + " K: " + ph.detail());
        conserved(ph, z);
        assertEquals(r.enthalpy(), ph.enthalpy(), 1.0e-6 * nrt);
        assertEquals(t, ph.phases().get(0).temperature(), 1.0e-6 * t);
        var uv = ENGINE.uv(EquilibriumRequest.uv(r.internalEnergy(), r.volume(), BASIS, z, competition));
        assertTrue(uv.converged(), () -> "UV at " + t + " K: " + uv.detail());
        conserved(uv, z);
        assertEquals(r.internalEnergy(), uv.internalEnergy(), 1.0e-6 * nrt);
        assertEquals(t, uv.phases().get(0).temperature(), 1.0e-6 * t);
        assertEquals(p, uv.phases().get(0).pressure(), 1.0e-6 * p);
    }

    /**
     * W3b step 1: ice beside two liquids and beside three fluid phases is an answer. Water under n-hexane with nitrogen at
     * -5 C and 1 atm freezes to ice, a hexane-rich liquid and gas; with ethanol at 10 % by mass of the water the ice
     * appears only below about -4 C, beside two liquids and the gas. Both round-trip through PH and UV.
     */
    @Test void iceFormsBesideTwoLiquidsAndThreeFluidPhases() {
        double t = 268.15;
        double[] dry = feed("Water", 0.3, "N-hexane", 0.4, "Nitrogen", 0.3);
        var frozen = tp(t, ATM, dry, ICE);
        System.out.printf(Locale.ROOT, "water, n-hexane, nitrogen at -5 C, 1 atm: %s, %d kernel evaluations%n", frozen.classification(),
                frozen.diagnostics().kernelEvaluations());
        assertEquals(1, count(frozen, PhaseKind.SOLID), frozen.detail());
        assertEquals(1, count(frozen, PhaseKind.VAPOR), frozen.detail());
        assertEquals(1, count(frozen, PhaseKind.LIQUID), frozen.detail());
        PhaseAmounts oil = null;
        for (PhaseAmounts phase : frozen.phases()) if (phase.kind() == PhaseKind.LIQUID) oil = phase;
        assertTrue(fraction(oil, "N-hexane") > 0.9, "the liquid is hexane-rich");
        roundTrips(frozen, t, ATM, dry, ICE);

        double water = 0.3, ethanol = 0.1 * water * 18.015 / 46.069 / 0.9;
        double[] wet = feed("Ethanol", ethanol, "Water", water, "N-hexane", 0.4, "Nitrogen", 0.3);
        double onset = iceOnset(wet, ATM, 263.15, 272.15);
        // Some ethanol goes to the oil and the gas, so the water-rich liquid holds less than 10 % and freezes a little higher.
        var atOnset = tp(onset + 1.0e-3, ATM, wet, ICE);
        PhaseAmounts aqueous = null;
        for (PhaseAmounts phase : atOnset.phases()) {
            if (phase.kind() == PhaseKind.LIQUID && (aqueous == null || fraction(phase, "Water") > fraction(aqueous, "Water"))) aqueous = phase;
        }
        double massEthanol = aqueous.amount(BASIS.indexOf("Ethanol")) * 46.069;
        double massShare = massEthanol / (massEthanol + aqueous.amount(BASIS.indexOf("Water")) * 18.015);
        System.out.printf(Locale.ROOT, "with ethanol at 10 %% by mass of the water: ice onset %.3f C, the water-rich liquid %.2f %% ethanol by mass%n",
                onset - 273.15, 100.0 * massShare);
        assertTrue(onset - 273.15 >= -5.5 && onset - 273.15 <= -3.0, "ice onset " + (onset - 273.15));
        double below = onset - 2.0;
        var four = tp(below, ATM, wet, ICE);
        System.out.printf(Locale.ROOT, "  %.2f C: %s, %d kernel evaluations%n", below - 273.15, four.classification(),
                four.diagnostics().kernelEvaluations());
        assertEquals(Classification.VAPOR_LIQUID_LIQUID_SOLID, four.classification(), four.detail());
        assertEquals(2, count(four, PhaseKind.LIQUID));
        double rt = R * below;
        var ice = ENGINE.anchoredCrystal("icheme:crystal_ice_ih");
        int w = ENGINE.evaluator().waterComponent();
        for (PhaseAmounts phase : four.phases()) {
            if (phase.kind() == PhaseKind.SOLID) continue;
            assertEquals(0.0, (phase.state().chemicalPotential(w) - ice.chemicalPotential(below, ATM)) / rt, 1.0e-8, "water at the ice's potential");
        }
        roundTrips(four, below, ATM, wet, ICE);
        // Without the gas, ice beside two liquids.
        double[] liquids = feed("Ethanol", ethanol, "Water", water, "N-hexane", 0.4);
        var two = tp(below, 5.0 * ATM, liquids, ICE);
        assertEquals(Classification.LIQUID_LIQUID_SOLID, two.classification(), two.detail());
        roundTrips(two, below, 5.0 * ATM, liquids, ICE);
    }

    /**
     * W3b step 1: the ethanol, water and n-hexane ternary below its ice onset with a gas: ice beside three fluid phases
     * answers and conserves every compound. A ternary has four phases at one pressure per temperature (phase rule), so
     * the gas at a fixed T and P is held by a little nitrogen.
     */
    @Test void iceBesideThreeFluidPhasesConserves() {
        double[] z = feed("Ethanol", 0.05, "Water", 0.45, "N-hexane", 0.45, "Nitrogen", 0.05);
        for (double t : new double[] {263.15, 258.15, 253.15}) {
            for (double p : new double[] {0.5 * ATM, ATM}) {
                var r = tp(t, p, z, ICE);
                System.out.printf(Locale.ROOT, "ethanol/water/n-hexane/N2 at %.2f C, %.0f Pa: %s%n", t - 273.15, p, r.classification());
                assertEquals(Classification.VAPOR_LIQUID_LIQUID_SOLID, r.classification(), r.detail());
            }
        }
    }

    /** TP to PH and UV and back over one, two and three fluid phases and with crystals, to 1e-6 of N R T. */
    @Test void phAndUvRoundTrips() {
        Object[][] cases = {
                {FLUID, ATM, 250.0, 400.0, new Object[] {"Methane", 0.5, "N-hexane", 0.5}},
                {FLUID, ATM, 300.0, 400.0, new Object[] {"Ethanol", 0.3, "Water", 0.7}},
                {FLUID, ATM, 300.0, 400.0, new Object[] {"Water", 0.5, "N-hexane", 0.5}},
                {FLUID, ATM, 300.0, 400.0, new Object[] {"Ethanol", 0.2, "Water", 0.4, "N-hexane", 0.4}},
                {FLUID, 5.0e5, 300.0, 500.0, new Object[] {"Water", 0.5, "crude_pc03", 0.4, "Methane", 0.1}},
                {FLUID, ATM, 300.0, 450.0, new Object[] {"Water", 0.5, "crude_pc03", 0.5}},
                {ICE, ATM, 255.0, 275.0, new Object[] {"Ethanol", 0.0416, "Water", 0.9584}},
                {ICE, ATM, 160.0, 200.0, new Object[] {"Nitrogen", 0.8, "CarbonDioxide", 0.15, "Water", 0.05}}};
        int answers = 0;
        for (Object[] c : cases) {
            PhaseCompetition competition = (PhaseCompetition) c[0];
            double p = (Double) c[1];
            double[] z = feed((Object[]) c[4]);
            for (int k = 0; k < 6; k++) {
                double t = (Double) c[2] + ((Double) c[3] - (Double) c[2]) * k / 5;
                var r = tp(t, p, z, competition);
                double nrt = R * t;
                var ph = ENGINE.ph(EquilibriumRequest.ph(p, r.enthalpy(), BASIS, z, competition));
                assertTrue(ph.converged(), () -> "PH at " + t + " K: " + ph.detail());
                conserved(ph, z);
                assertEquals(r.enthalpy(), ph.enthalpy(), 1.0e-6 * nrt);
                assertEquals(t, ph.phases().get(0).temperature(), 1.0e-6 * t);
                var uv = ENGINE.uv(EquilibriumRequest.uv(r.internalEnergy(), r.volume(), BASIS, z, competition));
                assertTrue(uv.converged(), () -> "UV at " + t + " K: " + uv.detail());
                conserved(uv, z);
                assertEquals(r.internalEnergy(), uv.internalEnergy(), 1.0e-6 * nrt);
                assertEquals(t, uv.phases().get(0).temperature(), 1.0e-6 * t);
                assertEquals(p, uv.phases().get(0).pressure(), 1.0e-6 * p);
                answers++;
            }
        }
        // Through each step: pure water boiling, water and n-hexane's three phases, pure water melting beside ice.
        Object[][] steps = {{FLUID, 373.0, 373.3, new Object[] {"Water", 1.0}}, {FLUID, 334.0, 334.6, new Object[] {"Water", 0.5, "N-hexane", 0.5}},
                {ICE, 273.0, 273.3, new Object[] {"Water", 1.0}}};
        for (Object[] c : steps) {
            PhaseCompetition competition = (PhaseCompetition) c[0];
            double[] z = feed((Object[]) c[3]);
            double low = tp((Double) c[1], ATM, z, competition).enthalpy(), high = tp((Double) c[2], ATM, z, competition).enthalpy();
            for (double f : new double[] {0.1, 0.5, 0.9}) {
                double h = low + f * (high - low);
                var ph = ENGINE.ph(EquilibriumRequest.ph(ATM, h, BASIS, z, competition));
                assertTrue(ph.converged(), () -> "PH in the step at " + c[1] + " K: " + ph.detail());
                conserved(ph, z);
                double t = ph.phases().get(0).temperature();
                assertEquals(h, ph.enthalpy(), 1.0e-6 * R * t);
                var uv = ENGINE.uv(EquilibriumRequest.uv(ph.internalEnergy(), ph.volume(), BASIS, z, competition));
                assertTrue(uv.converged(), () -> "UV in the step at " + c[1] + " K: " + uv.detail());
                conserved(uv, z);
                assertEquals(t, uv.phases().get(0).temperature(), 1.0e-6 * t);
                assertEquals(ph.internalEnergy(), uv.internalEnergy(), 1.0e-6 * R * t);
                answers++;
            }
        }
        System.out.println("aqueous PH and UV round trips: " + answers + " answers within 1e-6 N R T");
    }

    /** H(T) at fixed P through the label flip of a one-root phase with water: continuous, the share follows v/b. */
    @Test void enthalpyIsContinuousThroughALabelFlip() {
        double[] z = feed("Water", 0.9, "Methane", 0.1);
        double p = 40.0e6, low = 668.0, high = 672.0;
        PhaseKind lowKind = tp(low, p, z, FLUID).phases().get(0).kind();
        assertTrue(tp(low, p, z, FLUID).phases().size() == 1 && tp(high, p, z, FLUID).phases().get(0).kind() != lowKind, "a flip in the bracket");
        while (high - low > 1.0e-10 * high) {
            double mid = 0.5 * (low + high);
            if (tp(mid, p, z, FLUID).phases().get(0).kind() == lowKind) low = mid; else high = mid;
        }
        var a = tp(low, p, z, FLUID);
        var b = tp(high, p, z, FLUID);
        double slope = (tp(high + 1.0e-3, p, z, FLUID).enthalpy() - tp(low - 1.0e-3, p, z, FLUID).enthalpy()) / (high - low + 2.0e-3);
        double jump = Math.abs(b.enthalpy() - a.enthalpy() - slope * (high - low));
        System.out.printf(Locale.ROOT, "water 0.9, methane 0.1 at 40 MPa: label flip at %.6f K, jump of H %.2e N R T%n", high, jump / (R * high));
        assertTrue(jump <= 1.0e-9 * R * high, "jump " + jump);
    }

    /** D6: the identity names the derivation, the family with the share, and the water dataset with the share's revision. */
    @Test void identityNamesTheDerivationAndTheWaterShare() {
        var identity = ENGINE.contract().identity();
        var derivation = ENGINE.evaluator().derivation();
        assertTrue(identity.packageRevision().endsWith(";" + derivation.identity()), identity.packageRevision());
        assertEquals(CubicPhaseEvaluator.DERIVED_FAMILY, identity.evaluatorFamily());
        assertEquals(ThermoIdentity.WATER_IN_EOS_FORMULATION + ":icheme:pilot_water@cubic-water-r1:" + WaterShare.REVISION,
                identity.waterModelRevision());
        assertEquals(WaterParticipation.IN_EQUATION_OF_STATE, ENGINE.contract().water());
        assertEquals(EquilibriumResult.CoverageGrade.ESTIMATED_DECLARED_ERROR, ENGINE.contract().fluidCoverage().grade());
        assertTrue(ICE != null && ICE.crystals().contains("icheme:crystal_ice_ih"), "ice competes on the derived contract");
    }

    /**
     * The kernel evaluations per TP answer on the pilot list, printed, against W3a's table (W3b step 1: no higher; the
     * ternary at its bubble point 783 there, 515 since the oil-rich seed and the absent-slot rule of step 1c).
     */
    @Test void kernelEvaluationsRecorded() {
        Object[][] cases = {{"dry methane/n-hexane 25 C", 298.15, new Object[] {"Methane", 0.5, "N-hexane", 0.5}, 87},
                {"water/n-hexane 25 C", 298.15, new Object[] {"Water", 0.5, "N-hexane", 0.5}, 117},
                {"ethanol/water 80 C", 353.15, new Object[] {"Ethanol", 0.5, "Water", 0.5}, 52},
                {"ethanol/water/n-hexane 58 C", 331.15, new Object[] {"Ethanol", 0.2, "Water", 0.4, "N-hexane", 0.4}, 282},
                {"ethanol/water/n-hexane at its bubble point", 329.94, new Object[] {"Ethanol", 0.2, "Water", 0.4, "N-hexane", 0.4}, 515},
                {"water/cut 117 C", 390.0, new Object[] {"Water", 0.5, "crude_pc03", 0.5}, 120}};
        StringBuilder line = new StringBuilder("pilot_aqueous kernel evaluations per TP answer:");
        for (Object[] c : cases) {
            var r = tp((Double) c[1], ATM, feed((Object[]) c[2]), FLUID);
            int evaluations = r.diagnostics().kernelEvaluations();
            line.append(" ").append(c[0]).append(" ").append(evaluations).append(" (bound ").append(c[3]).append(");");
            assertTrue(evaluations > 0 && evaluations <= (Integer) c[3], c[0] + ": " + evaluations);
        }
        System.out.println(line);
    }

    /**
     * W3b step 1c: UV round trips through a narrow-boiling hydrocarbon binary's two-phase range (n-hexane and benzene at
     * 1 atm, under a kelvin wide). Its ends from inexact volume solves can straddle the answer; the search restarts with
     * exact solves (W3a D4), and every state comes back.
     */
    @Test void narrowBoilingBinaryRoundTripsThroughUv() {
        double[] z = feed("N-hexane", 0.5, "Benzene", 0.5);
        double low = Double.NaN, high = Double.NaN;
        for (double t = 343.0; t <= 345.0; t += 0.01) {
            if (tp(t, ATM, z, FLUID).phases().size() == 2) {
                if (Double.isNaN(low)) low = t;
                high = t;
            }
        }
        assertTrue(high > low, "a two-phase range");
        for (int k = 0; k <= 20; k++) {
            double t = low + (high - low) * k / 20;
            var r = tp(t, ATM, z, FLUID);
            var uv = ENGINE.uv(EquilibriumRequest.uv(r.internalEnergy(), r.volume(), BASIS, z, FLUID));
            assertTrue(uv.converged(), () -> "UV at " + t + " K: " + uv.detail());
            conserved(uv, z);
            assertEquals(t, uv.phases().get(0).temperature(), 1.0e-6 * t);
            assertEquals(r.internalEnergy(), uv.internalEnergy(), 1.0e-6 * R * t);
        }
    }
}
