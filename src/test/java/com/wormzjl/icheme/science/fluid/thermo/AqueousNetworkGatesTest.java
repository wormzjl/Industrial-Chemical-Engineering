package com.wormzjl.icheme.science.fluid.thermo;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.PilotAqueousTestCatalog;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * W3b steps 3 to 5 (batch 2026-11-11-aqueous-solvents): the network's three fluid phases from the cubic on the pilot
 * aqueous package, water a component. Two liquids and a vapour come from the physics and are never a hold; a single
 * liquid is labelled by its water share without a phase event; a dry island keeps its layout.
 */
class AqueousNetworkGatesTest {
    private static final double ATM = 101325;
    private final MaterialCatalog catalog = PilotAqueousTestCatalog.catalog();
    private final FluidThermodynamics model = FluidThermodynamics.forNetwork(catalog, PilotAqueousTestCatalog.PACKAGE_ID);

    private double[] feed(Object... pairs) {
        double[] n = new double[model.componentCount()];
        for (int k = 0; k < pairs.length; k += 2) n[model.components().indexOf((String) pairs[k])] = ((Number) pairs[k + 1]).doubleValue();
        return n;
    }
    private int index(String id) { return model.components().indexOf(id); }
    private static double fraction(double[] amounts, int i) {
        double total = 0;
        for (double v : amounts) total += v;
        return amounts[i] / total;
    }
    private static void conserved(double[] feed, FluidThermodynamics.State state, String at) {
        double[] totals = state.totals();
        for (int i = 0; i < feed.length; i++) assertEquals(feed[i], totals[i], 1e-12 * Math.max(1, feed[i]), at + " component " + i);
    }
    private int liquids(FluidThermodynamics.State s) { return (s.liquidSlotVolume() > 0 ? 1 : 0) + (s.aqueousSlotVolume() > 0 ? 1 : 0); }

    /** Water, n-hexane and nitrogen in a tank at 25 C: three phases with the mutual solubilities, amounts and energy kept. */
    @Test void waterHexaneAndNitrogenAt25CHaveTheMutualSolubilities() {
        double[] n = feed("Water", 50, "N-hexane", 50, "Nitrogen", 1);
        var state = model.flashTP(298.15, ATM, n, () -> {});
        conserved(n, state, "TP");
        assertTrue(state.vaporVolume() > 0 && liquids(state) == 2, "a vapour and two liquids");
        double hexaneInWater = fraction(state.labelAmountsView(FluidThermodynamics.AQUEOUS), index("N-hexane"));
        double waterInHexane = fraction(state.labelAmountsView(FluidThermodynamics.OIL), index("Water"));
        System.out.printf(Locale.ROOT, "pilot aqueous, 25 C: n-hexane in water %.3e, water in n-hexane %.3e%n", hexaneInWater, waterInHexane);
        assertEquals(1, hexaneInWater / 2.1e-6, 0.5, "n-hexane in water, about 2.1e-6");
        assertEquals(1, waterInHexane / 9.5e-4, 0.5, "water in n-hexane, about 9.5e-4");
        // The energy: the same slots evaluated again, and the state back from its enthalpy.
        var again = model.state(298.15, ATM, state.vaporAmountsView().clone(), state.liquidAmountsView().clone(), state.aqueousAmountsView().clone());
        assertEquals(state.enthalpy(), again.enthalpy(), 1e-12 * Math.abs(state.enthalpy()));
        var back = model.equilibriumPH(310, ATM, state.enthalpy(), n, () -> {});
        conserved(n, back, "PH");
        assertEquals(298.15, back.temperature(), 1e-6, "PH back to its temperature");
        assertEquals(state.enthalpy(), back.enthalpy(), 1e-9 * Math.abs(state.enthalpy()));
    }

    /** An ethanol-water liquid heated through its bubble point at 1 atm stays one liquid beside its vapour. */
    @Test void anEthanolWaterLiquidHeatedThroughItsBubblePointStaysOneLiquid() {
        double[] n = feed("Ethanol", 30, "Water", 70);
        boolean below = false, above = false;
        for (double t = 340; t <= 372; t += 0.5) {
            double temperature = t;
            var state = assertDoesNotThrow(() -> model.flashTP(temperature, ATM, n, () -> {}), t + " K");
            conserved(n, state, t + " K");
            assertTrue(liquids(state) <= 1, "one liquid at " + t + " K");
            if (state.vaporVolume() == 0) below = true; else if (liquids(state) == 1) above = true;
        }
        assertTrue(below && above, "the scan crosses the bubble point");
    }

    /** Ethanol added step by step to n-hexane over water: every state is answered (no hold) and conserved. */
    @Test void ethanolAddedUnderHexaneMovesWithoutAHold() {
        for (int k = 0; k <= 12; k++) {
            int step = k;
            double[] n = feed("Water", 40, "N-hexane", 40, "Ethanol", 5.0 * step, "Nitrogen", 0.5);
            var state = assertDoesNotThrow(() -> model.flashTP(298.15, ATM, n, () -> {}), "ethanol " + 5.0 * step);
            conserved(n, state, "ethanol " + n[index("Ethanol")]);
            double inWater = state.labelAmountsView(FluidThermodynamics.AQUEOUS)[index("Ethanol")];
            if (step > 0) assertTrue(inWater > 0, "ethanol goes to the water as well");
        }
    }

    /** CO2 over water at 25 C and 1 atm dissolves at its Henry constant. */
    @Test void carbonDioxideOverWaterReachesItsHenryValue() {
        var water = catalog.requirePackage(PilotAqueousTestCatalog.PACKAGE_ID).water();
        double henry = catalog.henryInWater(PilotAqueousTestCatalog.PACKAGE_ID, "CarbonDioxide").orElseThrow().pascal(water, 298.15);
        double[] n = feed("Water", 90, "CarbonDioxide", 10);
        var state = model.flashTP(298.15, ATM, n, () -> {});
        conserved(n, state, "TP");
        int co2 = index("CarbonDioxide");
        double dissolved = fraction(state.labelAmountsView(FluidThermodynamics.AQUEOUS), co2);
        double partial = fraction(state.vaporAmountsView(), co2) * ATM;
        System.out.printf(Locale.ROOT, "pilot aqueous, CO2 at 25 C: x %.4e against p/H %.4e%n", dissolved, partial / henry);
        assertEquals(1, dissolved / (partial / henry), 0.05);
    }

    /** Ice under oil over water: a slack vessel's refresh at the enthalpy of the 263 K stand-in freezes ice; at 285 K none. */
    @Test void iceUnderOilOverWaterFreezesAndMeltsAtARefresh() {
        double[] n = feed("Water", 40, "N-hexane", 40, "Nitrogen", 0.5);
        var ice = TestPhases.ice(model);
        assertNotNull(ice, "the package's ice");
        int iceIndex = model.crystalSolids().all().indexOf(ice);
        var cold = model.flashTP(263.15, ATM, n, () -> {});
        assertTrue(cold.labelPhase(FluidThermodynamics.AQUEOUS) != null, "the stand-in: supercooled water under the oil");
        var frozen = model.crystalsAtPressure(n, cold.enthalpy(), ATM, true, () -> {});
        assertTrue(frozen.crystalMoles()[iceIndex] > 0, "the refresh freezes ice");
        assertTrue(frozen.temperature() > 263.15 && frozen.temperature() <= 273.16, "the freezing heat warms it: " + frozen.temperature());
        var warm = model.flashTP(285, ATM, n, () -> {});
        var melted = model.crystalsAtPressure(n, warm.enthalpy(), ATM, true, () -> {});
        assertFalse(melted.holdsCrystal(), "above the melting line the refresh melts it");
        assertEquals(285, melted.temperature(), 1e-6);
    }

    /**
     * A single ethanol-water liquid crossing half water in a node keeps its slot: its label flips (the aqueous label from
     * half water up), no phase appears or vanishes, the layout keeps its unknowns and the state is continuous.
     */
    @Test void aSingleLiquidCrossingHalfWaterRelabelsWithoutAPhaseEvent() {
        int n = model.componentCount();
        double[] none = new double[n];
        double[] oilyLiquid = feed("Ethanol", 50.01, "Water", 49.99), wateryLiquid = feed("Ethanol", 49.99, "Water", 50.01);
        var oily = model.state(298.15, ATM, none.clone(), oilyLiquid, none.clone());
        var watery = model.state(298.15, ATM, none.clone(), wateryLiquid, none.clone());
        assertEquals(FluidThermodynamics.phaseBits(oily), FluidThermodynamics.phaseBits(watery), "the same slot");
        assertEquals(FluidThermodynamics.State.LIQUID, oily.slot(FluidThermodynamics.OIL));
        assertEquals(-1, oily.slot(FluidThermodynamics.AQUEOUS));
        assertEquals(FluidThermodynamics.State.LIQUID, watery.slot(FluidThermodynamics.AQUEOUS), "relabelled, in its slot");
        assertEquals(-1, watery.slot(FluidThermodynamics.OIL));
        assertEquals(new PhaseLayout(model, oily).size(), new PhaseLayout(model, watery).size(), "the same unknowns");
        assertEquals(oily.volume(), watery.volume(), 1e-3 * oily.volume(), "continuous volume");
        assertEquals(oily.enthalpy(), watery.enthalpy(), 1e-3 * Math.abs(oily.enthalpy()), "continuous enthalpy");
    }

    /** A dry island keeps the unknown count of the dry model: the refinery network's layout of a dry crude equals the waterless package's. */
    @Test void aDryLayoutKeepsItsUnknownCount() {
        var wet = FluidTestSupport.networkModel();
        var dry = FluidThermodynamics.forNetwork(MaterialCatalog.bundled(), "icheme:tjl20_methane_nitrogen");
        var assay = com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.thermo("icheme:tjl20_methane");
        var x = assay.crudeFeed(com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.ASSAY_ID).moleFractions();
        double[] onWet = new double[wet.componentCount()], onDry = new double[dry.componentCount()];
        for (int c = 0; c < x.length; c++) {
            String id = assay.componentBasis().componentId(c);
            onWet[wet.components().indexOf(id)] += x[c];
            onDry[dry.components().indexOf(id)] += x[c];
        }
        onWet[wet.components().indexOf("Nitrogen")] += .1;
        onDry[dry.components().indexOf("Nitrogen")] += .1;
        var a = wet.flashTP(350, 150000, onWet, () -> {});
        var b = dry.flashTP(350, 150000, onDry, () -> {});
        var la = new PhaseLayout(wet, a);
        var lb = new PhaseLayout(dry, b);
        System.out.printf(Locale.ROOT, "dry crude + N2 at 350 K, 150 kPa: %d unknowns on the refinery network, %d on the waterless package%n", la.size(), lb.size());
        assertEquals(lb.size(), la.size());
    }
}
