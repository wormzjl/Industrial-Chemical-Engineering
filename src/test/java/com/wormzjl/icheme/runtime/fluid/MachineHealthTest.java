package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.runtime.ProcessSolveJobs;
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
class MachineHealthTest {
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
        final Map<Long, ProcessSolveJobs.FluidIslandCommand> commands = new HashMap<>();
        final IslandCoordinator coordinator;
        final MachineHealth health;
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
            this(supply, factor, wear, metered, new FlowControl.Pump(FLOW, 5e5), 101325);
        }

        /** The line with mover {@code control} delivering to a void at {@code discharge} Pa (N1: a centrifugal casing pump). */
        Rig(double supply, int factor, Wear.Settings wear, boolean metered, FlowControl.Mover control, double discharge) {
            this(supply, factor, wear, metered, control, discharge, null, null);
        }

        /**
         * The line with machine {@code control} from a generator holding {@code from} into a void holding {@code to}, at time
         * factor 1 (N2: a centrifugal compressor on wet nitrogen; N4: a steam turbine into a wet receiver).
         */
        Rig(FluidThermodynamics.State from, FluidThermodynamics.State to, FlowControl control, Wear.Settings wear) {
            this(Double.NaN, 1, wear, false, control, Double.NaN, from, to);
        }

        /** The line: water at {@code supply} and {@code discharge} Pa where {@code from} and {@code to} are null. */
        private Rig(double supply, int factor, Wear.Settings wear, boolean metered, FlowControl control, double discharge, FluidThermodynamics.State from,
                    FluidThermodynamics.State to) {
            var time = com.wormzjl.icheme.science.fluid.SimulationTime.of(factor);
            coordinator = new IslandCoordinator(new IslandCoordinator.Dispatcher() {
                public int availableWorkers() {
                    return 64 - attempts.size();
                }

                public long nextRequestId() {
                    return ++requests;
                }

                public boolean submit(IslandCoordinator.Attempt attempt, ProcessSolveJobs.FluidIslandCommand command) {
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
            health = new MachineHealth(ledger, device -> device == 1 ? 1L : null, coordinator, device -> device == 1, device -> device == 1 ? new FlowControl.Pump(FLOW, 5e5) : null, () -> wear);
            trains.health(health);
            if (metered) {
                ledger.stores(Map.of(1L, com.wormzjl.icheme.science.machine.ElectricStore.Facts.EMPTY));
                electric = new ElectricStores(ledger, device -> device == 1 ? 1L : null, coordinator, () -> ElectricStores.Settings.DEFAULT);
                trains.electric(electric);
            } else electric = null;
            trains.host(ShaftTrains.Host.NONE);
            coordinator.shafts(trains);
            var line = new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1, 0, from != null ? from : water(supply), PassiveNetwork.NodeKind.GENERATOR),
                    new PassiveNetwork.Reservoir(2, 0, to != null ? to : water(discharge), PassiveNetwork.NodeKind.VOID)),
                    List.of(new PassiveNetwork.Pipe(10, 0, 1, FAT, control)));
            coordinator.register(new IslandCoordinator.Snapshot(1, 0, line, new IslandClock.Snapshot(0, 0, 0, 100), Optional.empty(), "READY"), model.withTime(time));
        }

        void tick() {
            epoch[0]++;
            coordinator.tick();
            while (!attempts.isEmpty()) {
                for (long request : List.copyOf(attempts.keySet())) {
                    var attempt = attempts.remove(request);
                    var command = commands.remove(request);
                    coordinator.completed(attempt, Optional.of((ProcessSolveJobs.FluidIslandSolveResult) command.solve(FluidTestSupport.NEVER)));
                }
                coordinator.pump();
            }
            if (epoch[0] % MachineHealth.CADENCE == 0) {
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
        var device = PhysicalFluidTopology.Device.builder(1, new PhysicalFluidTopology.Position("minecraft:overworld", 0, 0, 0), com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PIPE,
                PhysicalFluidTopology.Direction.EAST, FAT, new FlowControl.Pump(FLOW, 5e5)).build();
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
            for (int i = 0; i < 2 * MachineHealth.CADENCE; i++) rig.tick();
            double rate = rig.health.rate(1), before = rig.health.health(1);
            assertEquals(Wear.rate(rig.ratio(), false, slow), rate, 1e-12, "it books its commit's rate");
            for (int i = 0; i < 2 * MachineHealth.CADENCE; i++) rig.tick();
            double lost = before - rig.health.health(1), expected = rate * factor * 2 * MachineHealth.CADENCE / 20.0;
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
        // M-12: its standing fault is the one that set its rate.
        assertEquals(Wear.Fault.CAVITATION, rig.health.fault(1));
        assertEquals(Wear.intensity(rig.ratio()), rig.health.intensity(1), 1e-12);
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
        var outcome = new ArrayList<MachineHealth.Outcome>();
        int[] taken = {0};
        rig.health.queue(new MachineHealth.Repair(1, () -> ++taken[0] > 0, outcome::add));
        rig.tick();
        assertEquals(List.of(MachineHealth.Outcome.REPAIRED), outcome);
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
        var screw = new WorldTopologyLedger.Registration(PhysicalFluidTopology.Device.builder(20, at, kind, PhysicalFluidTopology.Direction.EAST, FAT,
                new FlowControl.Machine(new com.wormzjl.icheme.science.machine.VacuumPump(com.wormzjl.icheme.science.machine.VacuumPump.Type.DRY_SCREW, 300 / 3600., 2950), 1, true)).build(), spec, 0);
        var compressor = new WorldTopologyLedger.Registration(PhysicalFluidTopology.Device.builder(30, at, kind, PhysicalFluidTopology.Direction.EAST, FAT,
                new FlowControl.Compressor(.1, 3)).build(), spec, 0);
        var registrations = Map.of(20L, screw, 30L, compressor);
        assertTrue(MachineHealth.pump(screw), "a dry screw wears");
        assertFalse(MachineHealth.pump(compressor), "a compressor does not");
        var ledger = new WorldTopologyLedger(WorldTopologyLedger.Snapshot.empty(EventPathWorld.CATALOG));
        ledger.health(Map.of(20L, 0.0));
        var health = new MachineHealth(ledger, device -> null, rig.coordinator, device -> MachineHealth.pump(registrations.get(device)),
                device -> registrations.containsKey(device) ? registrations.get(device).device().control() : null, () -> FAST);
        assertTrue(health.failed(20), "saved at 0, it stands failed");
        assertNull(health.health(30), "a compressor has no health record");
        var outcome = new ArrayList<MachineHealth.Outcome>();
        int[] taken = {0};
        health.queue(new MachineHealth.Repair(20, () -> ++taken[0] > 0, outcome::add));
        health.queue(new MachineHealth.Repair(30, () -> ++taken[0] > 0, outcome::add));
        health.apply();
        assertEquals(List.of(MachineHealth.Outcome.REPAIRED, MachineHealth.Outcome.NOT_A_PUMP), outcome);
        assertEquals(1, taken[0], "one kit, for the screw");
        assertFalse(health.failed(20));
        assertEquals(1, health.health(20), "new again");
    }

    /**
     * WP6b of batch 2026-11-15-pollution-system: a blast's breach of a machine casing is a queued event; at the drain a new dry
     * screw falls to health 0 and stands failed, a compressor (no health record) is untouched, and one kit makes the screw new.
     */
    @Test
    void aBreachedCasingFailsUntilAKitRepairsIt() {
        var rig = new Rig(101325);
        var at = new PhysicalFluidTopology.Position("minecraft:overworld", 5, 0, 0);
        var kind = com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PIPE;
        var spec = FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG);
        var screw = new WorldTopologyLedger.Registration(PhysicalFluidTopology.Device.builder(20, at, kind, PhysicalFluidTopology.Direction.EAST, FAT,
                new FlowControl.Machine(new com.wormzjl.icheme.science.machine.VacuumPump(com.wormzjl.icheme.science.machine.VacuumPump.Type.DRY_SCREW, 300 / 3600., 2950), 1, true)).build(), spec, 0);
        var compressor = new WorldTopologyLedger.Registration(PhysicalFluidTopology.Device.builder(30, at, kind, PhysicalFluidTopology.Direction.EAST, FAT,
                new FlowControl.Compressor(.1, 3)).build(), spec, 0);
        var registrations = Map.of(20L, screw, 30L, compressor);
        var ledger = new WorldTopologyLedger(WorldTopologyLedger.Snapshot.empty(EventPathWorld.CATALOG));
        var health = new MachineHealth(ledger, device -> null, rig.coordinator, device -> MachineHealth.pump(registrations.get(device)),
                device -> registrations.containsKey(device) ? registrations.get(device).device().control() : null, () -> FAST);
        assertEquals(1, health.health(20), "a new screw");
        health.breach(20);
        health.breach(30);
        assertTrue(health.hasEvents(), "the breach is queued");
        assertFalse(health.failed(20), "nothing changes before the drain");
        health.apply();
        assertTrue(health.failed(20), "breached: failed");
        assertEquals(0, health.health(20), "at health 0");
        assertEquals(Map.of(20L, 0.0), health.facts(), "the health is the fact");
        assertNull(health.health(30), "a compressor has no health record, and gets none");
        assertEquals("FAILED", health.status(20));
        var outcome = new ArrayList<MachineHealth.Outcome>();
        health.queue(new MachineHealth.Repair(20, () -> true, outcome::add));
        health.apply();
        assertEquals(List.of(MachineHealth.Outcome.REPAIRED), outcome);
        assertFalse(health.failed(20));
        assertEquals(1, health.health(20), "new again");
    }

    /** N1: the casing pump of the low-flow cases: 0.01 m³/s at 50 m, 70 %, at its 2,950 rpm (speed share 1). */
    private static final com.wormzjl.icheme.science.machine.CentrifugalPump CENTRIFUGAL = new com.wormzjl.icheme.science.machine.CentrifugalPump(.01, 50, .7);

    /** The void pressure that holds the centrifugal pump at {@code share} of its rated flow from a supply at {@code supply} Pa. */
    private double dischargeAt(double supply, double share) {
        var s = water(supply);
        return supply + CENTRIFUGAL.rise(share * CENTRIFUGAL.ratedFlow(), 1, s.mass() / s.volume(), supply, 1.4);
    }

    /** The centrifugal pump's flow over its rated flow, from the last committed slice (its suction is the supply). */
    private static double share(Rig rig, FluidThermodynamics.State supply) {
        return Math.max(0, rig.flow()) / (supply.mass() / supply.volume()) / CENTRIFUGAL.ratedFlow();
    }

    /**
     * N1: a casing pump against a closed discharge (a void at its shut-off head) recirculates and erodes at the low-flow
     * rate, 1/(8 h) of health per process second at the default lives, says LOW FLOW, and keeps wearing on the cadence
     * while its island rests certified.
     */
    @Test
    void aDeadHeadedCasingPumpErodesAtTheLowFlowRateAlsoAtRest() {
        var rig = new Rig(101325, 1, Wear.Settings.DEFAULT, false, new FlowControl.Machine(CENTRIFUGAL, 1, true), dischargeAt(101325, 0));
        for (int i = 0; i < 4000 && rig.coordinator.observe(1).certificate().isEmpty(); i++) rig.tick();
        assertTrue(rig.coordinator.observe(1).certificate().isPresent(), "the dead-headed line rests certified: " + rig.coordinator.certificationRefusal(1));
        double q = share(rig, water(101325));
        assertTrue(q < .02, "next to no flow: " + q);
        assertEquals(Wear.rate(Wear.Fault.LOW_FLOW.intensity(q), 0, Wear.Settings.DEFAULT), rig.health.rate(1), 1e-9 * rig.health.rate(1), "its low-flow rate");
        assertEquals(1 / (8 * 3600.), rig.health.rate(1), .15 / (8 * 3600.), "about 1/(8 h) a process second");
        assertEquals(Wear.Fault.LOW_FLOW, rig.health.fault(1));
        assertEquals("LOW FLOW", rig.health.status(1));
        // At rest the cadence books its last rate: two cadences of process time at that rate.
        double before = rig.health.health(1), rate = rig.health.rate(1);
        for (int i = 0; i < 2 * MachineHealth.CADENCE; i++) rig.tick();
        assertEquals(rate * 2 * MachineHealth.CADENCE / 20.0, before - rig.health.health(1), 1e-3 * rate * 2 * MachineHealth.CADENCE / 20.0, "it wears at rest");
    }

    /** N1: a pump at 15 % of its rated flow that also cavitates wears at the larger erosion term, not their sum. */
    @Test
    void aPumpAtFifteenPercentThatCavitatesWearsAtTheLargerTerm() {
        var water = water(101325);
        double rho = water.mass() / water.volume(), q = .15 * CENTRIFUGAL.ratedFlow();
        double supply = model.saturationPressure(298.15) + 2.06 * rho * 9.80665 * CENTRIFUGAL.npsh(1).required(q);
        var rig = new Rig(supply, 1, Wear.Settings.DEFAULT, false, new FlowControl.Machine(CENTRIFUGAL, 1, true), dischargeAt(supply, .15));
        for (int i = 0; i < 400; i++) rig.tick();
        double share = share(rig, water(supply)), sigma = rig.ratio();
        assertTrue(share > .05 && share < .3, "below its minimum flow: " + share);
        double cavitation = Wear.intensity(sigma), lowFlow = Wear.lowFlow(share);
        assertTrue(cavitation > 0 && lowFlow > 0, "both erode: " + cavitation + ", " + lowFlow);
        assertEquals(Wear.rate(Math.max(cavitation, lowFlow), 0, Wear.Settings.DEFAULT), rig.health.rate(1), 1e-9 * rig.health.rate(1), "the larger term");
        assertEquals(cavitation > lowFlow ? Wear.Fault.CAVITATION : Wear.Fault.LOW_FLOW, rig.health.fault(1));
        assertEquals("LOW FLOW", rig.health.status(1), "LOW FLOW is said before CAVITATION");
    }

    /** N1: a stopped casing pump wears nothing; a set-flow pump has no curve and no low-flow fault. */
    @Test
    void aStoppedPumpAndASetFlowPumpLoseNoneToLowFlow() {
        var rig = new Rig(101325, 1, Wear.Settings.DEFAULT, false, new FlowControl.Machine(CENTRIFUGAL, 0, true), dischargeAt(101325, 0));
        for (int i = 0; i < 600; i++) rig.tick();
        assertEquals(1, rig.health.health(1), "a stopped pump wears nothing");
        assertEquals(0, rig.health.rate(1));
        assertEquals("", rig.health.status(1));
        assertFalse(MachineHealth.faults(new FlowControl.Pump(FLOW, 5e5)).contains(Wear.Fault.LOW_FLOW), "a set-flow pump has no curve");
        assertTrue(MachineHealth.faults(new FlowControl.Machine(CENTRIFUGAL, 1, true)).contains(Wear.Fault.LOW_FLOW));
    }

    /** N2: the compressor of the wet gas cases, MachineMoverTest's: 0.02 m³/s at 24.4 kJ/kg, from 150 into 180 kPa. */
    private static final com.wormzjl.icheme.science.machine.CentrifugalCompressor COMPRESSOR = new com.wormzjl.icheme.science.machine.CentrifugalCompressor(.02, 24400,
            com.wormzjl.icheme.science.machine.CentrifugalCompressor.DEFAULT_EFFICIENCY);

    /**
     * Nitrogen at 298.15 K and {@code pressure} with {@code waterVolume} m³ of liquid water in each cubic metre
     * (TwoPhaseMoverTest's wet suction): 7.7e-5 m³ at 150 kPa is about 3 % liquid by mass (water's vapour pressure takes
     * 2 % of the gas's moles), 0.2 L about 10 %.
     */
    private FluidThermodynamics.State wetNitrogen(double waterVolume, double pressure) {
        double t = 298.15;
        var mw = model.molecularWeights();
        var n = new double[model.componentCount()];
        int water = n.length - 1;
        n[water] = waterVolume * 997 / mw[water];
        n[com.wormzjl.icheme.science.material.MaterialTestBasis.NITROGEN] = pressure * (1 - waterVolume) / (FluidThermodynamics.R * t);
        return model.flashTP(t, pressure, n, () -> {
        });
    }

    private FluidThermodynamics.State nitrogen(double pressure) {
        return model.initialNitrogenCharge(1, 298.15, pressure, () -> {
        });
    }

    /** The compressor's suction condensed mass share as its last committed slice read it (N2), and checks it is the wet efficiency's. */
    private double condensed(Rig rig) {
        var result = rig.coordinator.observe(1).lastResult().orElseThrow();
        double w = result.observations().getFirst().condensedShare();
        assertEquals(com.wormzjl.icheme.science.machine.CentrifugalCompressor.wetEfficiency(1, w), PassiveStepSolver.wetShare(model, result.graph(), 0, result.advancedSeconds()), 1e-12,
                "the row carries the share the solve's wet efficiency reads");
        return w;
    }

    /**
     * N2: a centrifugal compressor on a suction about 3 % liquid by mass erodes at half the erosion intensity, 1/(16 h) of
     * health per process second at the default lives, and says WET GAS.
     */
    @Test
    void aCentrifugalCompressorOnAThreePercentWetSuctionErodesAndSaysWetGas() {
        var rig = new Rig(wetNitrogen(7.7e-5, 150_000), nitrogen(180_000), new FlowControl.Machine(COMPRESSOR, 1, true), Wear.Settings.DEFAULT);
        for (int i = 0; i < 400; i++) rig.tick();
        assertTrue(rig.flow() > 0, "it compresses: " + rig.flow() + " kg/s");
        double w = condensed(rig);
        assertEquals(.03, w, .01, "about 3 % liquid by mass: " + w);
        double rate = rig.health.rate(1);
        assertEquals(Wear.rate(Wear.Fault.WET_GAS.intensity(w), 0, Wear.Settings.DEFAULT), rate, 1e-12 * rate, "its wet gas rate");
        assertEquals(1 / (16 * 3600.), rate, .4 / (16 * 3600.), "about 1/(16 h) a process second");
        assertEquals(Wear.Fault.WET_GAS, rig.health.fault(1));
        assertEquals(Wear.wetGas(w), rig.health.intensity(1), 1e-12);
        assertEquals("WET GAS", rig.health.status(1));
        assertTrue(rig.health.health(1) < 1, "it has worn");
    }

    /** N2: on dry nitrogen the same compressor reads no liquid and keeps its health. */
    @Test
    void aCentrifugalCompressorOnDryGasLosesNone() {
        var rig = new Rig(nitrogen(150_000), nitrogen(180_000), new FlowControl.Machine(COMPRESSOR, 1, true), Wear.Settings.DEFAULT);
        for (int i = 0; i < 400; i++) rig.tick();
        assertTrue(rig.flow() > 0, "it compresses: " + rig.flow() + " kg/s");
        assertEquals(0, condensed(rig), 1e-12, "a dry suction");
        assertEquals(1, rig.health.health(1));
        assertEquals(0, rig.health.rate(1));
        assertNull(rig.health.fault(1));
        assertEquals("", rig.health.status(1));
        assertTrue(rig.health.facts().isEmpty(), "a new compressor carries no entry");
    }

    /**
     * N2: worn to 0 on a wet suction (lives short enough for a test), the compressor fails at the next cadence and is
     * stopped by its {@code FAILED} plan (a machine at no speed, its law kept); a kit makes it new and lifts the plan.
     */
    @Test
    void aFailedCompressorIsStoppedAndAKitRestoresIt() {
        var rig = new Rig(wetNitrogen(7.7e-5, 150_000), nitrogen(180_000), new FlowControl.Machine(COMPRESSOR, 1, true), FAST);
        for (int i = 0; i < 8000 && !rig.health.failed(1); i++) rig.tick();
        assertTrue(rig.health.failed(1), "it failed: " + rig.health.health(1));
        assertEquals("FAILED", rig.health.status(1));
        assertEquals(Map.of(1L, 0.0), rig.health.facts(), "health is a fact");
        for (int i = 0; i < 300; i++) rig.tick();
        assertTrue(rig.coordinator.observe(1).graph().pipes().getFirst().control() instanceof FlowControl.Machine m && m.speed() == 0 && m.law().equals(COMPRESSOR),
                "it stands stopped, its law kept: the closure is a slice input");
        var plans = new HashMap<Long, ShaftPlan>();
        rig.health.plans(rig.coordinator.observe(1).graph(), plans);
        assertEquals(new ShaftPlan(ShaftPlan.Kind.FAILED, 0), plans.get(1L));
        var outcome = new ArrayList<MachineHealth.Outcome>();
        rig.health.queue(new MachineHealth.Repair(1, () -> true, outcome::add));
        rig.tick();
        assertEquals(List.of(MachineHealth.Outcome.REPAIRED), outcome);
        assertFalse(rig.health.failed(1));
        assertEquals(1, rig.health.health(1), "new again");
        plans.clear();
        rig.health.plans(rig.coordinator.observe(1).graph(), plans);
        assertTrue(plans.isEmpty(), "repaired, no close: its train's or its store's speed plan restores it: " + plans);
    }

    /** N4: one mole of steam at {@code temperature} K and {@code pressure} Pa. */
    private FluidThermodynamics.State steam(double temperature, double pressure) {
        var n = new double[model.componentCount()];
        n[n.length - 1] = 1;
        return model.flashTP(temperature, pressure, n, () -> {
        });
    }

    /** N4: a 1 MW turbine on steam just above saturation at 40 bar (525 K), exhausting into dry nitrogen at 0.1 bar. */
    private Rig turbineRig(Wear.Settings wear) {
        return new Rig(steam(525, 4e6), nitrogen(1e4), new FlowControl.Turbine(com.wormzjl.icheme.science.machine.SteamTurbine.rated(1e6)), wear);
    }

    /** The turbine's own exhaust moisture as its last committed slice read it (N4): its observation row's condensed share. */
    private double exhaustMoisture(Rig rig) {
        return rig.coordinator.observe(1).lastResult().orElseThrow().observations().getFirst().condensedShare();
    }

    /**
     * N4: a steam turbine expanding nearly saturated steam from 40 bar to 0.1 bar exhausts wet (its own stream at the exhaust
     * pressure and at its inlet enthalpy less its shaft work), erodes its last blades by the wet exhaust rule and says WET
     * EXHAUST, although its receiver holds dry nitrogen. Its health is keyed by its fluid device, as a pump's is. Its rule
     * reads the onset at its exhaust pressure (W5, batch 2026-11-15-equipment-visual-design): 5.6 % at 10 kPa, a condensing
     * last stage's design moisture, so this inlet that lost its superheat wears at about a tenth of the full intensity.
     */
    @Test
    void aSteamTurbineOnSaturatedSteamExhaustsWetErodesAndSaysWetExhaust() {
        var rig = turbineRig(Wear.Settings.DEFAULT);
        for (int i = 0; i < 400; i++) rig.tick();
        assertTrue(rig.flow() > 0, "steam passes: " + rig.flow() + " kg/s");
        double w = exhaustMoisture(rig), p = rig.coordinator.observe(1).lastResult().orElseThrow().observations().getFirst().secondPressure();
        System.out.printf(java.util.Locale.ROOT, "N4 exhaust moisture, 40 bar 525 K to 0.1 bar: %.4f at %.0f Pa, onset %.4f%n", w, p, Wear.wetOnset(p));
        assertEquals(.062, w, .005, "about 6 % liquid by mass on the solve's work law: " + w);
        assertEquals(1e4, p, 500, "the row's second end is the exhaust");
        double intensity = Wear.wetExhaust(w, p);
        assertTrue(intensity > 0 && intensity < .3, "above the 10 kPa onset, well under the full intensity: " + intensity);
        assertEquals(intensity, rig.health.intensity(1), 1e-12, "the wet exhaust intensity at its exhaust's onset");
        double rate = rig.health.rate(1);
        assertEquals(Wear.rate(intensity, 0, Wear.Settings.DEFAULT), rate, 1e-12 * rate, "its wet exhaust rate");
        assertEquals(Wear.Fault.WET_EXHAUST, rig.health.fault(1));
        assertEquals("WET EXHAUST", rig.health.status(1));
        assertEquals(0, rig.health.crackle(1), "a turbine has no mover noise");
    }

    /**
     * N4: steam at 40 bar and 400 °C (SteamTurbineConnectionTest's inlet) expanded to 4 bar stays superheated, so the turbine
     * keeps its health, although its receiver holds about 16 % liquid by mass (a hotwell): what the receiver holds does not
     * enter the read.
     */
    @Test
    void aSteamTurbineWithADryExhaustIntoAWetReceiverLosesNone() {
        var rig = new Rig(steam(673.15, 4e6), wetNitrogen(8.9e-4, 4e5), new FlowControl.Turbine(com.wormzjl.icheme.science.machine.SteamTurbine.rated(1e6)), Wear.Settings.DEFAULT);
        for (int i = 0; i < 400; i++) rig.tick();
        assertTrue(rig.flow() > 0, "steam passes: " + rig.flow() + " kg/s");
        var result = rig.coordinator.observe(1).lastResult().orElseThrow();
        double held = InletPhase.bulk(rig.coordinator.model(1), result.graph().reservoirs().get(1).state()).condensedMassShare();
        assertEquals(.16, held, .01, "the receiver holds about 16 % liquid by mass: " + held);
        assertEquals(0, exhaustMoisture(rig), 1e-12, "a superheated exhaust");
        assertEquals(1, rig.health.health(1));
        assertEquals(0, rig.health.rate(1));
        assertEquals("", rig.health.status(1));
    }

    /**
     * N4: worn to 0, the turbine fails at the next cadence and its {@code FAILED} plan shuts its admission (mass target 0,
     * as a stop shuts it), so no steam passes; a kit makes it new and lifts the plan (its train's governor plan opens it).
     */
    @Test
    void aFailedTurbineIsShutAndAKitRestoresIt() {
        // A tenth of FAST's erosion life: at 10 kPa's onset (W5) this exhaust wears at about a tenth of the full intensity.
        var rig = turbineRig(new Wear.Settings(true, 1 / 600.0, .5));
        for (int i = 0; i < 8000 && !rig.health.failed(1); i++) rig.tick();
        assertTrue(rig.health.failed(1), "it failed: " + rig.health.health(1));
        assertEquals("FAILED", rig.health.status(1));
        for (int i = 0; i < 300; i++) rig.tick();
        assertTrue(rig.coordinator.observe(1).graph().pipes().getFirst().control() instanceof FlowControl.Turbine t && t.massTarget() == 0,
                "its admission is shut: " + rig.coordinator.observe(1).graph().pipes().getFirst().control());
        assertEquals(0, rig.flow(), 1e-6, "a failed turbine passes no steam");
        var outcome = new ArrayList<MachineHealth.Outcome>();
        rig.health.queue(new MachineHealth.Repair(1, () -> true, outcome::add));
        rig.tick();
        assertEquals(List.of(MachineHealth.Outcome.REPAIRED), outcome);
        assertEquals(1, rig.health.health(1), "new again");
        var plans = new HashMap<Long, ShaftPlan>();
        rig.health.plans(rig.coordinator.observe(1).graph(), plans);
        assertTrue(plans.isEmpty(), "repaired, no close: " + plans);
    }

    /**
     * N2, N4: the cadence keeps the entries of every machine that wears ({@link MachineHealth#pump}, the predicate the world
     * passes): a centrifugal compressor's and a steam turbine's saved health stand; a reciprocating frame's, which does not
     * wear, is dropped.
     */
    @Test
    void theCadenceKeepsACompressorsAndATurbinesHealth() {
        var rig = new Rig(101325);
        var at = new PhysicalFluidTopology.Position("minecraft:overworld", 5, 0, 0);
        var kind = com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PIPE;
        var spec = FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG);
        var compressor = new WorldTopologyLedger.Registration(PhysicalFluidTopology.Device.builder(40, at, kind, PhysicalFluidTopology.Direction.EAST, FAT,
                new FlowControl.Machine(COMPRESSOR, 1, true)).build(), spec, 0);
        var turbine = new WorldTopologyLedger.Registration(PhysicalFluidTopology.Device.builder(50, at, com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.TURBINE,
                PhysicalFluidTopology.Direction.EAST, FAT, new FlowControl.Turbine(com.wormzjl.icheme.science.machine.SteamTurbine.rated(55e3)))
                .ports(List.of(new PhysicalFluidTopology.Port(at, PhysicalFluidTopology.Direction.WEST, PassiveNetwork.PhasePort.BULK, PhysicalFluidTopology.ConnectionRole.SUCTION),
                        new PhysicalFluidTopology.Port(at, PhysicalFluidTopology.Direction.EAST, PassiveNetwork.PhasePort.BULK, PhysicalFluidTopology.ConnectionRole.DISCHARGE))).build(), spec, 0);
        var frame = new WorldTopologyLedger.Registration(PhysicalFluidTopology.Device.builder(60, at, kind, PhysicalFluidTopology.Direction.EAST, FAT,
                new FlowControl.Compressor(.1, 3)).build(), spec, 0);
        var registrations = Map.of(40L, compressor, 50L, turbine, 60L, frame);
        assertTrue(MachineHealth.pump(compressor), "a centrifugal compressor wears");
        assertTrue(MachineHealth.pump(turbine), "a steam turbine wears");
        assertFalse(MachineHealth.pump(frame), "a frame does not");
        var ledger = new WorldTopologyLedger(WorldTopologyLedger.Snapshot.empty(EventPathWorld.CATALOG));
        ledger.health(Map.of(40L, .5, 50L, .25, 60L, .75));
        var health = new MachineHealth(ledger, device -> null, rig.coordinator, device -> MachineHealth.pump(registrations.get(device)),
                device -> registrations.containsKey(device) ? registrations.get(device).device().control() : null, () -> Wear.Settings.DEFAULT);
        health.cadence();
        assertEquals(.5, health.health(40));
        assertEquals(.25, health.health(50));
        assertNull(health.health(60), "a frame's entry is dropped");
        assertEquals(Map.of(40L, .5, 50L, .25), health.facts());
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
        var outcome = new ArrayList<MachineHealth.Outcome>();
        int[] taken = {0};
        rig.health.queue(new MachineHealth.Repair(1, () -> ++taken[0] > 0, outcome::add));
        rig.health.queue(new MachineHealth.Repair(2, () -> ++taken[0] > 0, outcome::add));
        rig.tick();
        assertEquals(List.of(MachineHealth.Outcome.FULL, MachineHealth.Outcome.NOT_A_PUMP), outcome);
        assertEquals(0, taken[0], "no kit taken");
    }
}
