package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.runtime.ProcessSolveServices;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.machine.Npsh;
import com.wormzjl.icheme.science.machine.Wear;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * W10b (build plan revision 2, section 3.3, owner decision D34): an in-line pump draws water at 298.15 K from a supply and
 * delivers it to a void at 1 atm. Fed at about twice its NPSH3 it cavitates at the erosion peak, books its wear at its
 * commits and, certified, on the cadence, fails at 0, wakes its island and stands closed ({@code FAILED} plan); a repair
 * makes it new and it runs again. Fed at the atmosphere it is clean (σ near 6) and keeps its health.
 */
class PumpHealthTest {
    private final FluidThermodynamics model = FluidTestSupport.networkModel();
    private static final PipeResistance.Geometry FAT = new PipeResistance.Geometry(1, .1, PipeResistance.DEFAULT_ROUGHNESS_METRES, 0);
    /** The pump's set flow, m³/s: its NPSH3 is 1.68 m at the 2-pole speed (Npsh.forDuty). */
    private static final double FLOW = .01;
    /** Lives short enough for a test: 60 s at the erosion peak, 30 s dry. */
    private static final Wear.Settings FAST = new Wear.Settings(true, 1 / 60.0, .5);

    private FluidThermodynamics.State water(double pressure) {
        var n = new double[model.componentCount()];
        n[n.length - 1] = 1;
        return model.flashTP(298.15, pressure, n, () -> {
        });
    }

    private final class Rig {
        final long[] epoch = {0};
        final Map<Long, IslandCoordinator.Attempt> attempts = new LinkedHashMap<>();
        final Map<Long, ProcessSolveServices.FluidIslandCommand> commands = new HashMap<>();
        final IslandCoordinator coordinator;
        final PumpHealth health;
        /** The in-line pump's electric store (M-04), or null where the line runs without stores. */
        final ElectricStores electric;
        long requests;

        Rig(double supply) {
            this(supply, 1, FAST);
        }

        Rig(double supply, int factor, Wear.Settings wear) {
            this(supply, factor, wear, false);
        }

        /**
         * The line under time factor {@code factor} and wear lives {@code wear} (batch 2026-11-11-minecraft-time-delta, W4);
         * {@code metered}: the pump is a registered in-line pump with an empty metered store (M-04).
         */
        Rig(double supply, int factor, Wear.Settings wear, boolean metered) {
            var time = com.wormzjl.icheme.science.fluid.SimulationTime.of(factor);
            coordinator = new IslandCoordinator(new IslandCoordinator.Dispatcher() {
                public int availableWorkers() {
                    return 64 - attempts.size();
                }

                public long nextRequestId() {
                    return ++requests;
                }

                public boolean submit(IslandCoordinator.Attempt attempt, ProcessSolveServices.FluidIslandCommand command) {
                    attempts.put(attempt.slice().requestId(), attempt);
                    commands.put(attempt.slice().requestId(), command);
                    return true;
                }

                public void cancel(long request) {
                    throw new AssertionError("no deadlines here");
                }
            }, changed -> {
            }, () -> 0L, new IslandCoordinator.Settings(30_000_000_000L, 20_000_000_000L, 64, false, 100, CertificatePolicy.defaults()).withTime(time), () -> epoch[0]);
            var empty = WorldTopologyLedger.Snapshot.empty(EventPathWorld.CATALOG);
            var ledger = new WorldTopologyLedger(metered ? new WorldTopologyLedger.Snapshot(0, 1000, Map.of(1L, inline()), List.of(), empty.constructed(), empty.destroyed(), empty.basis()) : empty);
            var trains = new ShaftTrains(ledger, device -> device == 1 ? 1L : null, coordinator);
            health = new PumpHealth(ledger, device -> device == 1 ? 1L : null, coordinator, device -> device == 1, device -> device == 1 ? new FlowControl.Pump(FLOW, 5e5) : null, () -> wear);
            trains.health(health);
            if (metered) {
                ledger.stores(Map.of(1L, com.wormzjl.icheme.science.machine.ElectricStore.Facts.EMPTY));
                electric = new ElectricStores(ledger, device -> device == 1 ? 1L : null, coordinator, () -> ElectricStores.Settings.DEFAULT);
                trains.electric(electric);
            } else electric = null;
            trains.host(ShaftTrains.Host.NONE);
            coordinator.shafts(trains);
            var line = new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1, 0, water(supply), PassiveNetwork.NodeKind.GENERATOR),
                    new PassiveNetwork.Reservoir(2, 0, water(101325), PassiveNetwork.NodeKind.VOID)),
                    List.of(new PassiveNetwork.Pipe(10, 0, 1, FAT, new FlowControl.Pump(FLOW, 5e5))));
            coordinator.register(new IslandCoordinator.Snapshot(1, 0, line, new IslandClock.Snapshot(0, 0, 0, 100), Optional.empty(), "READY"), model.withTime(time));
        }

        void tick() {
            epoch[0]++;
            coordinator.tick();
            while (!attempts.isEmpty()) {
                for (long request : List.copyOf(attempts.keySet())) {
                    var attempt = attempts.remove(request);
                    var command = commands.remove(request);
                    coordinator.completed(attempt, Optional.of((ProcessSolveServices.FluidIslandSolveResult) command.solve(FluidTestSupport.NEVER)));
                }
                coordinator.pump();
            }
            if (epoch[0] % PumpHealth.CADENCE == 0) {
                health.cadence();
                if (electric != null) electric.cadence();
            }
            if (health.hasEvents()) health.apply();
        }

        /** Whether the island's last solved graph carries the pump switched on. */
        boolean open() {
            return coordinator.observe(1).graph().pipes().getFirst().control() instanceof FlowControl.Pump p && p.enabled();
        }

        double flow() {
            return coordinator.observe(1).lastResult().map(r -> r.endpointFlows()[0]).orElse(Double.NaN);
        }

        double ratio() {
            return coordinator.observe(1).lastResult().orElseThrow().observations().getFirst().npshRatio();
        }
    }

    /** The in-line pump's registration (M-04): a pipe block at the supply carrying the pump, switched on. */
    private static WorldTopologyLedger.Registration inline() {
        var device = new PhysicalFluidTopology.Device(1, new PhysicalFluidTopology.Position("minecraft:overworld", 0, 0, 0), com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PIPE,
                PhysicalFluidTopology.Direction.EAST, FAT, new FlowControl.Pump(FLOW, 5e5));
        return new WorldTopologyLedger.Registration(device, FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG), 0);
    }

    /** The supply pressure that puts the pump's NPSHA at {@code sigma} times its NPSH3 at its set flow. */
    private double supplyAt(double sigma) {
        var s = water(101325);
        double rho = s.mass() / s.volume();
        return model.saturationPressure(298.15) + sigma * rho * 9.80665 * Npsh.forDuty(FLOW, 2950).required(FLOW);
    }

    /**
     * Wear is booked over process time (batch 2026-11-11-minecraft-time-delta, W4, owner T3): at factors 4 and 72 the pump at
     * the erosion peak loses its rate times the process seconds of the ticks that passed, factor times the play time.
     */
    @Test
    void wearIsBookedOverProcessTimeAtFactorsFourAndSeventyTwo() {
        var slow = new Wear.Settings(true, 1, 10);
        for (int factor : new int[]{4, 72}) {
            var rig = new Rig(supplyAt(2.06), factor, slow);
            for (int i = 0; i < 2 * PumpHealth.CADENCE; i++) rig.tick();
            double rate = rig.health.rate(1), before = rig.health.health(1);
            assertEquals(Wear.rate(rig.ratio(), false, slow), rate, 1e-12, "it books its commit's rate");
            for (int i = 0; i < 2 * PumpHealth.CADENCE; i++) rig.tick();
            double lost = before - rig.health.health(1), expected = rate * factor * 2 * PumpHealth.CADENCE / 20.0;
            assertEquals(expected, lost, 1e-3 * expected, "factor " + factor + ": two cadences of play are " + factor * 10 + " s of process wear");
        }
        assertEquals(8 * 3600, Wear.life(Wear.PEAK, false, Wear.Settings.DEFAULT), 1e-6, "the default life at the peak: 8 h of process, 2 h of play at 4");
        assertEquals(40 * 60, Wear.life(Double.NaN, true, Wear.Settings.DEFAULT), 1e-9, "dry: 40 min of process, 10 min of play at 4");
    }

    @Test
    void aCavitatingPumpWearsToFailedStandsClosedAndARepairRestoresIt() {
        var rig = new Rig(supplyAt(2.06));
        for (int i = 0; i < 400; i++) rig.tick();
        assertTrue(rig.flow() > 9, "it delivers its set flow: " + rig.flow() + " kg/s");
        assertEquals(2.06, rig.ratio(), .1, "it runs at the erosion peak");
        // W10c: its mover state's crackle byte is its committed I_c(σ), near full; a set-flow pump has no churn read.
        assertEquals(Math.round(255 * Wear.intensity(rig.ratio())), rig.health.crackle(1), "the crackle follows I_c");
        assertTrue(rig.health.crackle(1) > 250, "loud at the peak: " + rig.health.crackle(1));
        assertEquals(0, rig.health.churn(1));
        double rate = rig.health.rate(1);
        assertEquals(Wear.rate(rig.ratio(), false, FAST), rate, 1e-12, "it books its commit's rate");
        double worn = rig.health.health(1);
        assertTrue(worn < 1 && worn > 0, "it has worn: " + worn);
        assertFalse(rig.health.failed(1));
        // W10d: its page reads its committed suction (NPSH3 at its D21 duty speed, NPSHA = σ NPSH3, no gas in water; a
        // set-flow pump has no gas ratio) and says CAVITATION; its health has its meter, off the status line.
        var suction = rig.health.suction(1);
        double npsh3 = Npsh.forDuty(FLOW, 2950).required(FLOW);
        assertEquals(npsh3, suction.required(), .02 * npsh3, "NPSH required is NPSH3 at the set flow");
        assertEquals(rig.ratio(), suction.sigma(), 1e-12);
        assertEquals(suction.sigma() * suction.required(), suction.available(), 1e-12);
        assertEquals(0, suction.gas(), 1e-9, "water below its boiling point carries no vapour");
        assertTrue(Double.isNaN(suction.ratio()), "a set-flow pump has no gas ratio");
        assertEquals("CAVITATION", rig.health.status(1));
        // It wears to 0 at about its 60 s life, fails at the next cadence and stands closed.
        for (int i = 0; i < 2400 && !rig.health.failed(1); i++) rig.tick();
        assertTrue(rig.health.failed(1), "it failed: " + rig.health.health(1));
        assertEquals(0, rig.health.health(1));
        assertEquals("FAILED", rig.health.status(1), "FAILED takes precedence over CAVITATION");
        for (int i = 0; i < 300; i++) rig.tick();
        assertEquals(0, rig.flow(), 1e-9, "a failed pump passes nothing");
        assertEquals(0, rig.health.crackle(1), "a stopped pump is silent");
        assertTrue(rig.coordinator.observe(1).graph().pipes().getFirst().control() instanceof FlowControl.Pump p && !p.enabled() && p.requestedVolumeFlow() == FLOW && p.maximumAddedPressure() == 5e5,
                "it is switched off with its settings kept: the closure is a slice input");
        assertEquals(Map.of(1L, 0.0), rig.health.facts(), "health is a fact");
        // A repair: accepted, the kit is taken, the pump is new and runs again.
        var outcome = new ArrayList<PumpHealth.Outcome>();
        int[] taken = {0};
        rig.health.queue(new PumpHealth.Repair(1, () -> ++taken[0] > 0, outcome::add));
        rig.tick();
        assertEquals(List.of(PumpHealth.Outcome.REPAIRED), outcome);
        assertEquals(1, taken[0], "one kit");
        assertFalse(rig.health.failed(1));
        assertEquals(1, rig.health.health(1), "new again");
        assertTrue(rig.health.facts().isEmpty());
        for (int i = 0; i < 300; i++) rig.tick();
        assertTrue(rig.health.health(1) < 1 && rig.health.health(1) > .7, "cavitating, it wears again: " + rig.health.health(1));
        assertTrue(rig.flow() > 9, "it runs again: " + rig.flow());
        assertTrue(rig.coordinator.observe(1).graph().pipes().getFirst().control() instanceof FlowControl.Pump p && p.enabled(), "switched back on");
    }

    /**
     * M-04: an unpowered in-line set-flow pump (an empty metered store) stays closed at every dispatch: the store's
     * {@code FAILED 0} is not overwritten by the health's "switch back on" {@code FAILED 1}. With power restored it opens at
     * the next dispatch.
     */
    @Test
    void anUnpoweredInlinePumpStaysClosedAndOpensWhenPowered() {
        var rig = new Rig(101325, 1, FAST, true);
        assertFalse(rig.electric.powered(1), "an empty metered store stands unpowered");
        long seen = rig.requests;
        int closed = 0;
        for (int i = 0; i < 2000 && closed < 3; i++) {
            rig.tick();
            if (rig.requests == seen) continue;
            seen = rig.requests;
            closed++;
            assertFalse(rig.open(), "dispatch " + closed + ": the unpowered pump stands closed");
            assertEquals(0, rig.flow(), 1e-9, "dispatch " + closed + ": it passes nothing");
            // A closed line rests after two slices; a wake (as a store's change gives) asks for the next dispatch.
            rig.coordinator.shaftsMoved(Set.of(1L));
        }
        assertEquals(3, closed, "three dispatches seen");
        // Power restored: FE every tick; the store restarts at a cadence and wakes the island.
        int opened = -1;
        for (int i = 0; i < 4000 && opened < 0; i++) {
            rig.electric.receive(1, 1_000_000, false);
            boolean was = rig.electric.powered(1);
            seen = rig.requests;
            rig.tick();
            if (was && rig.requests != seen) opened = rig.open() ? 1 : 0;
        }
        assertEquals(1, opened, "the first dispatch after the store restarted opens the pump");
        assertTrue(rig.flow() > 9, "it delivers: " + rig.flow());
    }

    /**
     * M-02: the kit takes every mover casing and the health decides. A dry screw saved failed (health 0) is repaired; a
     * compressor, which does not wear, answers {@code NOT_A_PUMP} and takes no kit.
     */
    @Test
    void aFailedDryScrewIsRepairedAndACompressorIsNotAPump() {
        var rig = new Rig(101325);
        var at = new PhysicalFluidTopology.Position("minecraft:overworld", 5, 0, 0);
        var kind = com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PIPE;
        var spec = FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG);
        var screw = new WorldTopologyLedger.Registration(new PhysicalFluidTopology.Device(20, at, kind, PhysicalFluidTopology.Direction.EAST, FAT,
                new FlowControl.Machine(new com.wormzjl.icheme.science.machine.VacuumPump(com.wormzjl.icheme.science.machine.VacuumPump.Type.DRY_SCREW, 300 / 3600., 2950), 1, true)), spec, 0);
        var compressor = new WorldTopologyLedger.Registration(new PhysicalFluidTopology.Device(30, at, kind, PhysicalFluidTopology.Direction.EAST, FAT,
                new FlowControl.Compressor(.1, 3)), spec, 0);
        var registrations = Map.of(20L, screw, 30L, compressor);
        assertTrue(PumpHealth.pump(screw), "a dry screw wears");
        assertFalse(PumpHealth.pump(compressor), "a compressor does not");
        var ledger = new WorldTopologyLedger(WorldTopologyLedger.Snapshot.empty(EventPathWorld.CATALOG));
        ledger.health(Map.of(20L, 0.0));
        var health = new PumpHealth(ledger, device -> null, rig.coordinator, device -> PumpHealth.pump(registrations.get(device)),
                device -> registrations.containsKey(device) ? registrations.get(device).device().control() : null, () -> FAST);
        assertTrue(health.failed(20), "saved at 0, it stands failed");
        assertNull(health.health(30), "a compressor has no health record");
        var outcome = new ArrayList<PumpHealth.Outcome>();
        int[] taken = {0};
        health.queue(new PumpHealth.Repair(20, () -> ++taken[0] > 0, outcome::add));
        health.queue(new PumpHealth.Repair(30, () -> ++taken[0] > 0, outcome::add));
        health.apply();
        assertEquals(List.of(PumpHealth.Outcome.REPAIRED, PumpHealth.Outcome.NOT_A_PUMP), outcome);
        assertEquals(1, taken[0], "one kit, for the screw");
        assertFalse(health.failed(20));
        assertEquals(1, health.health(20), "new again");
    }

    @Test
    void aCleanPumpKeepsItsHealthAndAFullPumpRefusesAKit() {
        var rig = new Rig(101325);
        for (int i = 0; i < 2000; i++) rig.tick();
        assertTrue(rig.flow() > 9, "it delivers: " + rig.flow());
        assertTrue(rig.ratio() > 4, "its NPSHA is past inception: " + rig.ratio());
        assertEquals(1, rig.health.health(1));
        assertEquals(0, rig.health.rate(1));
        assertEquals(0, rig.health.crackle(1), "a clean pump does not crackle (W10c)");
        assertTrue(rig.health.facts().isEmpty(), "a new pump carries no entry");
        assertEquals("", rig.health.status(1), "a quiet pump says nothing");
        assertTrue(rig.health.suction(1).available() > 4 * rig.health.suction(1).required(), "its NPSHA above the inception curve");
        var outcome = new ArrayList<PumpHealth.Outcome>();
        int[] taken = {0};
        rig.health.queue(new PumpHealth.Repair(1, () -> ++taken[0] > 0, outcome::add));
        rig.health.queue(new PumpHealth.Repair(2, () -> ++taken[0] > 0, outcome::add));
        rig.tick();
        assertEquals(List.of(PumpHealth.Outcome.FULL, PumpHealth.Outcome.NOT_A_PUMP), outcome);
        assertEquals(0, taken[0], "no kit taken");
    }
}
