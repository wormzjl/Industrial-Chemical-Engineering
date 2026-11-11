package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.solver.PhaseLayout;
import com.wormzjl.icheme.science.fluid.solver.SparseNewton;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;

/**
 * The pilot world's rows B and D (the branch's P6b regression, re-derived for main's vessel wall in milestone M3 of the
 * unified thermo port): a 1 m3 vessel of nitrogen at 298.15 K and 101.325 kPa between a liquid methane generator, a water
 * generator and a void at 101.325 kPa, on the pilot network package (since W3b on the refinery network, whose cubic holds
 * water; the pilot's holds none, and the pilot's D40 A refresh of methane and ice left with it).
 *
 * <p>On the branch the cold methane drew the rigid vessel below the water generator's pressure, water entered, and the
 * mixture fell below 273.16 K, where the water model has no state (D12); its water arrived only in the second TR-BDF2
 * stage, whose companion was a negative amount (fixed there by clipping it). Main has neither: backward Euler has one
 * implicit stage, and a vessel's wall goes slack at the ambient instead of below it (D54), so a vessel the methane cools
 * stands at or above 101.325 kPa and a water generator at the ambient has no drive into it: the rows commit and no water
 * enters. Wherever water is driven into a vessel that falls below its melting line (the water generator at 1 MPa) the
 * water freezes (WP4 of the range-extrapolation plan, its gate (c)): the pipe and the vessel's steps carry it as its
 * stand-in, supercooled liquid water, and the vessel's refresh freezes it into its ice population, with the latent heat
 * in the vessel's energy, so more of the methane boils; nothing holds (the interim ice hold before WP4).</p>
 */
class WaterIntoCryogenicMethaneTest {
    private static final long VESSEL = 11;
    private static final int INTERVALS = 6;
    private static final String ICE = "icheme:crystal_ice_ih";
    /** W3b: the water rows run on the refinery network, whose cubic holds water (the pilot's holds none). */
    private final FluidThermodynamics model = FluidTestSupport.networkModel();
    private final int water = model.components().indexOf("Water"), methane = model.components().indexOf("Methane");

    /** {@code (N2, CH4, C2H6, CO2, water)} on the network's basis. */
    private double[] basis(double... pilotBasis) {
        String[] ids = {"Nitrogen", "Methane", "Ethane", "CarbonDioxide", "Water"};
        double[] n = new double[model.componentCount()];
        for (int k = 0; k < ids.length; k++) if (pilotBasis[k] != 0) n[model.components().indexOf(ids[k])] = pilotBasis[k];
        return n;
    }

    /** One cubic metre of {@code feed} (basis N2, CH4, C2H6, CO2, water) at (T, P), the engine's TP answer. */
    private FluidThermodynamics.State metre(double t, double p, double[] feed) {
        var probe = model.flashTP(t, p, feed, () -> {});
        double scale = 1.0 / probe.volume();
        double[] amounts = feed.clone();
        for (int i = 0; i < amounts.length; i++) amounts[i] *= scale;
        return model.flashTP(t, p, amounts, () -> {});
    }

    private PassiveNetwork island(double methaneTemperature, double methanePressure, double waterPressure) {
        var pipe = new PipeResistance.Geometry(1.0, 0.05, 4.5e-5, 0.0);
        var nitrogen = metre(298.15, PhaseLayout.AMBIENT_PRESSURE, basis(1, 0, 0, 0, 0));
        return new PassiveNetwork(List.of(
                new PassiveNetwork.Reservoir(7, 0, metre(methaneTemperature, methanePressure, basis(0, 1, 0, 0, 0)), PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(VESSEL, 0, nitrogen),
                new PassiveNetwork.Reservoir(15, 0, metre(298.15, waterPressure, basis(0, 0, 0, 0, 1)), PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(34, 0, nitrogen, PassiveNetwork.NodeKind.VOID)),
                List.of(new PassiveNetwork.Pipe(1, 0, 1, pipe), new PassiveNetwork.Pipe(2, 1, 3, pipe), new PassiveNetwork.Pipe(3, 1, 2, pipe)));
    }

    /** Four 5 s intervals commit; the vessel stands at or above the ambient and holds no water. While its wall is slack
     * at the ambient, the water generator's pressure, the drive into it is zero, which the step resolves to its tolerance:
     * since a vessel starts each pass from its own layout (owner decision D40 A), the 120 K row's first interval books
     * 7.4e-12 mol (1.3e-10 g) of water there, gone again by the third. No water enters to 1e-9 mol. */
    private void commitsWithNoWaterEntering(double methaneTemperature, double methanePressure) {
        var solver = new PassiveIntervalSolver(model);
        var graph = island(methaneTemperature, methanePressure, PhaseLayout.AMBIENT_PRESSURE);
        double step = 0.05;
        for (int k = 0; k < 4; k++) {
            var result = solver.solve(graph, 5, PassiveIntervalSolver.Settings.defaults(), () -> {}, step);
            step = solver.nextStepEstimate();
            graph = result.graph();
            var vessel = graph.reservoirs().get(1);
            System.out.printf(Locale.ROOT, "methane at %.0f K, %.0f Pa, interval %d: vessel %.3f K %.1f Pa, liquid %.4e m3, water %.3e mol (%d substeps)%n", methaneTemperature,
                    methanePressure, k + 1, vessel.state().temperature(), vessel.state().pressure(), TestPhases.liquidVolume(vessel.state()), vessel.inventory().moles()[water],
                    result.acceptedSubsteps());
            assertTrue(vessel.state().pressure() >= PhaseLayout.AMBIENT_PRESSURE, "the wall goes slack at the ambient, never below it");
            assertEquals(0, vessel.inventory().moles()[water], 1e-9, "no water enters");
        }
    }

    @Test
    void waterBesideLiquidMethaneAt120KDoesNotEnterTheVessel() {
        commitsWithNoWaterEntering(120, 1.2e6);
    }

    @Test
    void waterBesideLiquidMethaneAt115KDoesNotEnterTheVessel() {
        commitsWithNoWaterEntering(115, 2.0e5);
    }

    /** The vessel's ice, kg: its population of the free water's ice (WP4). */
    private static double ice(PassiveNetwork.Reservoir vessel) {
        double kg = 0;
        for (var population : vessel.inventory().solids().crystals()) if (population.material().id().equals(ICE)) kg += population.massKg();
        return kg;
    }

    /**
     * WP4 gate (c): water driven in at 1 MPa while the methane cools the vessel below its melting line freezes at the
     * vessel's refresh, and nothing holds. Physics: water is immiscible with the methane and nitrogen, and below its
     * melting line its vapour pressure is the ice's (5.6e-12 Pa at 111.6 K), so all of it freezes; from 25 °C to ice at the
     * vessel's temperature it gives up about 12 kJ/mol (1.9 liquid, 6.0 fusion, 4.4 ice), which boils about 1.5 mol of
     * methane (8.2 kJ/mol) per mol of water: the vessel's methane leaves as gas to the void. In the network the step
     * carries the water as its stand-in, so the liquid's share of that heat leaves in the step and the fusion's at the
     * refresh: the refresh after the first interval freezes every mole of the stand-in, the ice's steam aside, and boils
     * methane.
     */
    @Test
    void waterDrivenIntoTheCooledVesselFreezesAtTheRefresh() {
        var solver = new PassiveIntervalSolver(model);
        var graph = island(120, 1.2e6, 1e6);
        double step = 0.05;
        double frozen = 0;
        boolean refreshed = false;
        for (int k = 0; k < INTERVALS; k++) {
            var result = solver.solve(graph, 5, PassiveIntervalSolver.Settings.defaults(), () -> {}, step);
            step = solver.nextStepEstimate();
            graph = result.graph();
            var vessel = graph.reservoirs().get(1);
            var state = vessel.state();
            System.out.printf(Locale.ROOT, "WP4 gate (c) interval %d: vessel %.3f K %.1f Pa, water %.4e mol (stand-in %.4e mol, steam %.4e mol), ice %.6f kg, "
                            + "methane liquid %.4e mol vapour %.4e mol (%d substeps, %d rejected, %s)%n", k + 1, state.temperature(), state.pressure(),
                    vessel.inventory().moles()[water], TestPhases.waterLiquid(state), TestPhases.waterVapor(state), ice(vessel), TestPhases.liquid(state)[methane], TestPhases.vapor(state)[methane],
                    result.acceptedSubsteps(), result.rejectedSubsteps(), result.rejectionReasons());
            assertTrue(result.rejectionReasons().keySet().stream().noneMatch(r -> r.startsWith(com.wormzjl.icheme.science.fluid.thermo.ThermoHold.REASON_PREFIX)),
                    "no thermo hold: " + result.rejectionReasons());
            frozen = Math.max(frozen, ice(vessel));
            if (refreshed || !(TestPhases.waterLiquid(state) > 0) || ice(vessel) > 0) continue;
            var after = InventoryEquilibrium.refresh(graph, model, () -> {}).graph().reservoirs().get(1);
            double inVessel = vessel.inventory().moles()[water], iceMoles = ice(after) / model.waterMolecularWeight;
            System.out.printf(Locale.ROOT, "WP4 gate (c) refresh after interval %d: %.4e mol of stand-in to %.4e mol of ice at %.3f K %.1f Pa, steam %.4e mol, "
                            + "methane vapour %.4e to %.4e mol, liquid %.4e to %.4e mol%n", k + 1, inVessel, iceMoles, after.state().temperature(), after.state().pressure(),
                    TestPhases.waterVapor(after.state()), TestPhases.vapor(state)[methane], TestPhases.vapor(after.state())[methane], TestPhases.liquid(state)[methane], TestPhases.liquid(after.state())[methane]);
            assertEquals(0, TestPhases.waterLiquid(after.state()), "no stand-in is left");
            assertEquals(inVessel, iceMoles + TestPhases.waterVapor(after.state()), 1e-9 * inVessel, "every mole of the water is ice, its steam aside");
            assertTrue(TestPhases.vapor(after.state())[methane] > TestPhases.vapor(state)[methane], "the fusion heat boils methane");
            refreshed = true;
        }
        assertTrue(refreshed, "a refresh froze the stand-in");
        var vessel = graph.reservoirs().get(1);
        assertTrue(frozen > 0, "the water froze at a refresh");
        assertTrue(vessel.state().temperature() < 273.0, "the vessel is below the melting line: " + vessel.state().temperature());
    }
}
