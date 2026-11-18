package com.wormzjl.icheme.runtime.fluid;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.fluid.network.ConservativeTransport;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * The water intake and the waste outfall (batch 2026-11-18-water-intake-outfall) in the network. The intake is a generator
 * that is the water body: pure water at 25 C and 101 325 Pa, its strainer an entry loss K = 3 on its link; a pump on its line
 * draws from it into a drum, and what it gave is what the island holds (to 1e-9). Switched off (no water body) it gives
 * nothing. The outfall is a sink open to the air at 101 325 Pa with an exit loss K = 1: a line above the atmosphere drains
 * into it, what the line's source gave is what the outfall took and the line holds; nothing ever enters through it.
 */
class WaterIntakeOutfallTest {
    private static final PhysicalFluidTopology.Direction SOUTH = PhysicalFluidTopology.Direction.SOUTH;
    private static final int Y = 64;

    private static long waterIntake(EventPathWorld w, int x, int y, int z, boolean supplying) {
        long id = w.ledger.nextIdentity();
        var record = Placement.waterIntake(id, new PhysicalFluidTopology.Position(EventPathWorld.DIM, x, y, z), SOUTH, .05, w.catalog, supplying);
        w.registry.submit(List.of(new WorldTopologyLedger.Edit(id, record)), id + 1, null);
        return id;
    }

    private static long outfall(EventPathWorld w, int x, int y, int z) {
        long id = w.ledger.nextIdentity();
        var record = Placement.outfall(id, new PhysicalFluidTopology.Position(EventPathWorld.DIM, x, y, z), SOUTH, .05, w.catalog);
        w.registry.submit(List.of(new WorldTopologyLedger.Edit(id, record)), id + 1, null);
        return id;
    }

    /** What a run booked: per compound the exchanges at each watched boundary, and the island's inventory gain; the commits seen. */
    record Run(Map<Long, Map<String, Double>> exchanged, Map<String, Double> gained, int commits, double drumWater) {}

    /**
     * Runs the island of {@code anchor} for {@code intervals} commits or until it is steady, summing the {@code EXCHANGE} transfers at {@code watched}
     * from the first state seen, and the island's inventory change over the same commits.
     */
    private static Run run(EventPathWorld w, long anchor, long drum, int intervals, long... watched) {
        var exchanged = new HashMap<Long, Map<String, Double>>();
        for (long id : watched) exchanged.put(id, new HashMap<>());
        Optional<PassiveIntervalSolver.Result> seen = Optional.empty();
        Map<String, Double> start = null;
        int commits = 0;
        long limit = w.ledger.onlineTick() + 400L * intervals;
        while (commits < intervals) {
            w.tick();
            var island = w.registry.owner(anchor);
            if (w.ledger.onlineTick() >= limit) fail("the island commits: " + (island == null ? "no island" : w.describe(island)));
            if (island == null) continue;
            var s = w.coordinator.observe(island);
            // A steady island commits no more intervals: it replays its last one (no transfer of its own to count).
            if (start != null && s.status().startsWith("STEADY") && (commits > 0 || s.status().startsWith("STEADY: no flow"))) break;
            var ids = w.coordinator.model(s.id()).components();
            if (start == null) {
                start = held(s.graph(), ids);
                seen = s.lastResult();
                continue;
            }
            if (s.lastResult().isEmpty() || s.lastResult().equals(seen)) continue;
            seen = s.lastResult();
            commits++;
            for (var t : seen.get().boundaries()) {
                var into = exchanged.get(t.nodeId());
                if (into == null || t.kind() != ConservativeTransport.BoundaryTransfer.Kind.EXCHANGE) continue;
                for (int c = 0; c < ids.size(); c++) into.merge(ids.get(c), t.moles()[c], Double::sum);
            }
        }
        w.assertKeptUp("water intake and outfall");
        var s = w.coordinator.observe(w.registry.owner(anchor));
        var ids = w.coordinator.model(s.id()).components();
        var end = held(s.graph(), ids);
        var gained = new HashMap<String, Double>();
        for (var id : end.keySet()) gained.put(id, end.get(id) - start.getOrDefault(id, 0.));
        double water = 0;
        for (var n : s.graph().reservoirs()) if (n.id() == drum) water = n.inventory().moles()[ids.indexOf("Water")];
        return new Run(exchanged, gained, commits, water);
    }

    /** The moles every vessel and junction of {@code graph} holds, by compound id. */
    private static Map<String, Double> held(PassiveNetwork graph, List<String> ids) {
        var out = new HashMap<String, Double>();
        for (var n : graph.reservoirs()) {
            if (n.kind() != PassiveNetwork.NodeKind.RESERVOIR && n.kind() != PassiveNetwork.NodeKind.JUNCTION) continue;
            var moles = n.inventory().moles();
            for (int c = 0; c < ids.size(); c++) out.merge(ids.get(c), moles[c], Double::sum);
        }
        return out;
    }

    /** Every compound: the island's gain equals the sum of the watched boundaries' signed exchanges (into the island positive), to 1e-9 of the turnover. */
    private static void assertCloses(Run run, String label) {
        double scale = 0;
        for (var m : run.exchanged().values()) for (double v : m.values()) scale += Math.abs(v);
        assertTrue(scale > 0, label + ": something crossed the boundaries: " + run);
        var ids = new TreeSet<>(run.gained().keySet());
        for (var m : run.exchanged().values()) ids.addAll(m.keySet());
        for (var id : ids) {
            double crossed = 0;
            for (var m : run.exchanged().values()) crossed += m.getOrDefault(id, 0.);
            assertEquals(run.gained().getOrDefault(id, 0.), crossed, 1e-9 * scale, label + ": " + id + " crossed is what the island gained: " + run);
        }
    }

    @Test void theIntakeIsTheWaterBodyAtItsSurface() {
        var catalog = EventPathWorld.CATALOG;
        var r = Placement.waterIntake(9, new PhysicalFluidTopology.Position(EventPathWorld.DIM, 0, Y, 0), SOUTH, .1, catalog, true);
        var s = r.spec();
        assertTrue(s.ambient(), "the world outside");
        assertEquals(298.15, s.temperature());
        assertEquals(101325, s.pressure());
        var water = FluidDeviceSpec.water(catalog).composition();
        assertArrayEquals(water, s.composition(), 0, "pure water");
        assertEquals(Kind.GENERATOR, r.device().kind());
        assertEquals(3, r.device().geometry().minorLoss(), "the strainer's entry loss, K = 3 (as the gas intake's)");
        assertEquals(.1, r.device().geometry().diameter(), "the joined pipe's bore");
        assertEquals(1, r.device().geometry().length());
        assertTrue(r.device().generatorEnabled());
        assertFalse(Placement.waterIntake(9, r.device().position(), SOUTH, .1, catalog, false).device().generatorEnabled(), "no water body: off");
        assertTrue(FluidWorldAuthority.waterIntake(r));
        assertFalse(FluidWorldAuthority.gasIntake(r));
        assertFalse(FluidWorldAuthority.outfall(r));
        var gas = Placement.intake(10, r.device().position(), SOUTH, .1, com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.forNetwork(catalog, FluidPresetCatalog.NETWORK_PACKAGE));
        assertTrue(FluidWorldAuthority.gasIntake(gas), "the gas intake's damp air is not a water body's charge");
        assertFalse(FluidWorldAuthority.waterIntake(gas));
        var o = Placement.outfall(11, r.device().position(), SOUTH, .1, catalog);
        assertTrue(FluidWorldAuthority.outfall(o));
        assertEquals(Kind.VOID, o.device().kind());
        assertEquals(101325, o.spec().pressure());
        assertEquals(1, o.device().geometry().minorLoss(), "a free discharge, K = 1");
        assertFalse(o.device().chimney(), "a one-block sink, no stack");
        assertFalse(o.device().atmospheric(), "it stands at 101 325 Pa, as the brief's void");
    }

    @Test void aPumpDrawsWaterFromTheIntakeIntoADrum() {
        var w = new EventPathWorld();
        w.runTo(20);
        long intake = waterIntake(w, 0, Y, 0, true);
        w.place(0, Y, 1, Kind.PIPE, SOUTH, .05);
        w.place(0, Y, 2, Kind.PUMP, SOUTH, new FlowControl.Pump(.001, 3e5), null, .05);
        w.place(0, Y, 3, Kind.PIPE, SOUTH, .05);
        long drum = w.place(0, Y, 4, Kind.RESERVOIR, SOUTH, new FluidDeviceSpec(2, 298.15, 101325, EventPathWorld.nitrogen().composition()), .05);
        var run = run(w, drum, drum, 40, intake);
        System.out.println("WATER_INTAKE pump " + run);
        assertTrue(run.drumWater() > 0, "the drum takes water: " + run);
        assertTrue(run.exchanged().get(intake).getOrDefault("Water", 0.) > 0, "the intake gave water");
        for (var e : run.exchanged().get(intake).entrySet())
            if (!e.getKey().equals("Water")) assertEquals(0, e.getValue(), 0, "the intake gives water only: " + e);
        assertCloses(run, "intake");
    }

    @Test void anIntakeWithNoWaterBodyGivesNothing() {
        var w = new EventPathWorld();
        w.runTo(20);
        long intake = waterIntake(w, 0, Y, 0, false);
        w.place(0, Y, 1, Kind.PIPE, SOUTH, .05);
        w.place(0, Y, 2, Kind.PUMP, SOUTH, new FlowControl.Pump(.001, 3e5), null, .05);
        w.place(0, Y, 3, Kind.PIPE, SOUTH, .05);
        long drum = w.place(0, Y, 4, Kind.RESERVOIR, SOUTH, new FluidDeviceSpec(2, 298.15, 101325, EventPathWorld.nitrogen().composition()), .05);
        var run = run(w, drum, drum, 20, intake);
        double gave = run.exchanged().get(intake).values().stream().mapToDouble(Math::abs).sum();
        assertEquals(0, gave, 0, "a closed boundary: " + run);
        assertEquals(0, run.drumWater(), 0, "the drum holds no water");
    }

    @Test void aLineDrainsIntoTheOutfallAndTheAccountCloses() {
        var w = new EventPathWorld();
        w.runTo(20);
        double[] water = FluidDeviceSpec.water(w.catalog).composition();
        long source = w.place(0, Y, 0, Kind.GENERATOR, SOUTH, new FluidDeviceSpec(1, 298.15, 2e5, water), .05);
        w.place(0, Y, 1, Kind.PIPE, SOUTH, .05);
        w.place(0, Y, 2, Kind.PIPE, SOUTH, .05);
        long out = outfall(w, 0, Y, 3);
        var run = run(w, out, -1, 30, source, out);
        System.out.println("WASTE_OUTFALL drain " + run);
        double gave = run.exchanged().get(source).getOrDefault("Water", 0.), took = run.exchanged().get(out).getOrDefault("Water", 0.);
        assertTrue(gave > 0, "the source gave water: " + run);
        assertTrue(took < 0, "the outfall took water out of the plant: " + run);
        for (var v : run.exchanged().get(out).values()) assertTrue(v <= 0, "nothing enters through an outfall: " + run);
        assertCloses(run, "outfall");
    }

    @Test void nothingEntersThroughAnOutfall() {
        // A drum joined to an outfall and nothing else: whatever the drum holds, no transfer at the outfall is ever into the plant.
        var w = new EventPathWorld();
        w.runTo(20);
        long out = outfall(w, 0, Y, 0);
        w.place(0, Y, 1, Kind.PIPE, SOUTH, .05);
        long drum = w.place(0, Y, 2, Kind.RESERVOIR, SOUTH, new FluidDeviceSpec(2, 298.15, 101325, EventPathWorld.nitrogen().composition()), .05);
        Optional<PassiveIntervalSolver.Result> seen = Optional.empty();
        double in = 0;
        int results = 0;
        for (int t = 0; t < 2000; t++) {
            w.tick();
            var island = w.registry.owner(drum);
            if (island == null) continue;
            var s = w.coordinator.observe(island);
            if (s.lastResult().isEmpty() || s.lastResult().equals(seen)) continue;
            seen = s.lastResult();
            results++;
            for (var b : seen.get().boundaries()) if (b.nodeId() == out) for (double v : b.moles()) in += Math.max(0, v);
        }
        w.assertKeptUp("outfall supplies nothing");
        assertEquals(0, in, 0, "an outfall never supplies (" + results + " results)");
    }
}
