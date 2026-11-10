package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A flare stack in the engine (batch 2026-11-09-flare-stack, FLARE_STACK_PLAN.md W2-W3): a gas generator feeds two pipes into
 * the flare's tip, a void behind its gas inlet that {@code FluidWorldAuthority.placeFlare} places. Methane burns (FLARING), and
 * so does methane carried with nitrogen while its flame stays above 1700 K; methane drowned in nitrogen vents unburnt (FLAME
 * OUT); a supply that pushes more than the tip passes is held to its limit (OVER CAPACITY).
 */
class FlareRuntimeTest {
    private final MaterialCatalog catalog = MaterialCatalog.bundled();
    private final FluidThermodynamics model = FluidTestSupport.networkModel();
    private static final PipeResistance.Geometry LINE = new PipeResistance.Geometry(1, .2, .000045, 0);
    private static final long GAS = 1, FLARE = 9;

    private static PhysicalFluidTopology.Position at(int x) {
        return new PhysicalFluidTopology.Position("minecraft:overworld", x, 0, 0);
    }

    private static PhysicalFluidTopology.Device plain(long id, int x, Kind kind) {
        return new PhysicalFluidTopology.Device(id, at(x), kind, EAST, LINE, new FlowControl.Passive());
    }

    /** The flare at (3, 0, 0), fed from the west, with tip {@code tip}, as {@code FluidWorldAuthority.placeFlare} places it. */
    private static PhysicalFluidTopology.Device flare(double tip) {
        return new PhysicalFluidTopology.Device(FLARE, at(3), Kind.VOID, NORTH, new PipeResistance.Geometry(1, tip, PipeResistance.DEFAULT_ROUGHNESS_METRES, 0), FlowControl.ControlValve.flare(),
                List.of(new PhysicalFluidTopology.Port(at(3), WEST, PassiveNetwork.PhasePort.BULK, PhysicalFluidTopology.ConnectionRole.VESSEL)), true, PhysicalFluidTopology.Cutoff.NONE, false,
                PhysicalFluidTopology.ALL_FACES, 0, 0, null);
    }

    private List<PhysicalFluidTopology.Device> devices(double tip) {
        return List.of(plain(GAS, 0, Kind.GENERATOR), plain(110, 1, Kind.PIPE), plain(111, 2, Kind.PIPE), flare(tip));
    }

    /** A gas of {@code moles} by component name at {@code temperature} K and {@code pressure} Pa, one cubic metre of it. */
    private FluidThermodynamics.State gas(Map<String, Double> moles, double temperature, double pressure) {
        double[] n = new double[model.componentCount()];
        for (var e : moles.entrySet()) {
            int c = model.components().indexOf(e.getKey());
            assertTrue(c >= 0, e.getKey());
            n[c] = e.getValue();
        }
        double volume = model.flashTP(temperature, pressure, n, () -> {
        }).volume();
        for (int c = 0; c < n.length; c++) n[c] /= volume;
        return model.flashTP(temperature, pressure, n, () -> {
        });
    }

    /** The flare's runtime: its ledger, its flares and the island, run in 20 s slices. */
    private final class Rig {
        final double tip;
        final WorldTopologyLedger ledger;
        final Flares flares;
        PassiveNetwork graph;
        final PassiveIntervalSolver solver = new PassiveIntervalSolver(model);

        Rig(double tip, FluidThermodynamics.State supply) {
            this.tip = tip;
            var n2 = FluidDeviceSpec.nitrogen(catalog).composition();
            var active = new TreeMap<Long, WorldTopologyLedger.Registration>();
            active.put(FLARE, new WorldTopologyLedger.Registration(flare(tip), new FluidDeviceSpec(1, Flare.AMBIENT, 101325, n2), 0));
            var empty = WorldTopologyLedger.Snapshot.empty(catalog);
            ledger = new WorldTopologyLedger(new WorldTopologyLedger.Snapshot(0, 1000, active, List.of(), empty.constructed(), empty.destroyed(), empty.basis()));
            flares = new Flares(ledger, FluidWorldAuthority.heatingValues(catalog, model), model.molecularWeights(), FluidWorldAuthority.formulas(catalog, model));
            var compiled = PhysicalFluidTopology.compile(devices(tip), Map.of(GAS, new PassiveNetwork.Reservoir(GAS, 0, supply, PassiveNetwork.NodeKind.GENERATOR),
                    FLARE, new PassiveNetwork.Reservoir(FLARE, 0, gas(Map.of("Nitrogen", 1.), Flare.AMBIENT, 101325), PassiveNetwork.NodeKind.VOID)));
            assertEquals(1, compiled.islands().size());
            graph = compiled.islands().getFirst().graph();
            boolean valved = false;
            for (var pipe : graph.pipes())
                if (pipe.control() instanceof FlowControl.ControlValve v && v.source() == FlowControl.ValveSource.FLARE) {
                    valved = true;
                    assertEquals(FLARE, graph.reservoirs().get(pipe.second()).id(), "the tip's valve is stated toward the flare");
                }
            assertTrue(valved, "the run into the flare carries its tip limit");
        }

        Flares.View run(int slices) {
            long tick = 0;
            for (int i = 0; i < slices; i++) {
                var plans = new HashMap<Long, ShaftPlan>();
                flares.plans(graph, plans);
                var result = solver.solve(ShaftPlan.apply(graph, plans), 20, PassiveIntervalSolver.Settings.defaults(), () -> {
                });
                flares.booked(tick, tick + 400, result);
                graph = result.graph();
                tick += 400;
            }
            return flares.view(FLARE);
        }
    }

    private static void print(String what, Flares.View v) {
        System.out.printf(Locale.ROOT, "%s: %s, flow %.3f kg/s of %.3f, heat %.1f MW of %.1f, NHV %.2f MJ/m3, flame %.0f K%n",
                what, v.status(), v.massFlow(), v.limit(), v.heat() / 1e6, v.rated() / 1e6, v.heatingValue() / 1e6, v.flameTemperature());
    }

    @Test
    void methaneFlares() {
        var v = new Rig(.3, gas(Map.of("Methane", 1.), 300, 105_000)).run(4);
        print("methane", v);
        assertEquals(Flares.Status.FLARING, v.status());
        assertTrue(v.massFlow() > .01 && v.massFlow() < v.limit(), "it flows, within the tip's limit");
        assertEquals(v.massFlow() * 5.0e7, v.heat(), .02 * v.heat(), "it releases the methane's lower heating value");
        assertEquals(33.4e6, v.heatingValue(), .2e6);
        assertEquals(List.of(), v.alarms());
    }

    /** The flame's presentation (batch 2026-11-10-custom-shader-system, W3): methane gives no soot; its exit velocity is its volume flow over the tip. */
    @Test
    void theFlamesSootAndExitVelocityFollowTheBookedSpan() {
        var rig = new Rig(.3, gas(Map.of("Methane", 1.), 300, 105_000));
        assertEquals(Flares.Flame.NONE, rig.flares.flame(FLARE), "no span booked yet: no flame");
        var v = rig.run(4);
        var flame = rig.flares.flame(FLARE);
        assertEquals(0, flame.soot(), "methane burns without smoke");
        assertTrue(flame.exitVelocity() > 0 && flame.exitVelocity() <= Flare.HIGH_VELOCITY, "it leaves the tip: " + flame.exitVelocity());
        // At about 0.65 kg/m3 at the tip, the velocity is the mass flow over that density and the tip's area.
        assertEquals(v.massFlow() / (.65 * Flare.tipArea(.3)), flame.exitVelocity(), .1 * flame.exitVelocity());
        assertNull(rig.flares.flame(12345), "no flare there");
    }

    /** Nitrogen flares with methane while the flame holds (owner: as long as the combustion temperature is maintained). */
    @Test
    void anInertFlaresWithAFuelWhileTheFlameHolds() {
        var lean = new Rig(.3, gas(Map.of("Methane", 1., "Nitrogen", 4.), 300, 101_800)).run(4);
        print("1 CH4 : 4 N2", lean);
        // A lean gas leaves the tip at 18.3 m/s at most: this supply meets that limit, and the flame holds.
        assertNotEquals(Flares.Status.FLAME_OUT, lean.status());
        assertTrue(lean.flameTemperature() > Flare.FLAME_LIMIT, "the flame holds: " + lean.flameTemperature());
        assertTrue(lean.heat() > 0 && !lean.alarms().contains(EquipmentAlarm.FLAME_OUT), "it burns");
        var drowned = new Rig(.3, gas(Map.of("Methane", 1., "Nitrogen", 8.), 300, 101_800)).run(4);
        print("1 CH4 : 8 N2", drowned);
        assertEquals(Flares.Status.FLAME_OUT, drowned.status());
        assertTrue(drowned.massFlow() > .01, "the gas still leaves");
        assertEquals(0, drowned.heat(), "unburnt: it releases nothing");
        assertEquals(List.of(EquipmentAlarm.FLAME_OUT), drowned.alarms());
    }

    /** A stiff supply into a small tip is held to the tip's limit (owner X4). */
    @Test
    void aFlowOverTheTipsLimitIsHeldBack() {
        var v = new Rig(Flare.MIN_TIP, gas(Map.of("Methane", 1.), 300, 1_000_000)).run(6);
        print("small tip", v);
        assertEquals(Flares.Status.OVER_CAPACITY, v.status());
        assertEquals(v.limit(), v.massFlow(), .02 * v.limit(), "the tip passes its limit, not more");
        assertTrue(v.heat() <= v.rated() * 1.02, "nor more than its rated heat");
        assertEquals(List.of(EquipmentAlarm.OVER_CAPACITY), v.alarms());
    }
}
