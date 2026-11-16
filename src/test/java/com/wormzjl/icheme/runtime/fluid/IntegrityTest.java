package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.runtime.ProcessSolveJobs;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.fluid.SimulationTime;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.science.integrity.CorrosionLaw;
import com.wormzjl.icheme.science.integrity.FoulingLaw;
import com.wormzjl.icheme.science.integrity.Mechanism;
import com.wormzjl.icheme.science.integrity.Metallurgy;
import com.wormzjl.icheme.science.machine.Wear;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The walls of constructed equipment (batch 2026-11-16-fouling-corrosion, WP2): rates booked over process time at the time
 * factor, the bound of {@link Integrity#EVALUATIONS} records per cadence taken round-robin, a wall through failing once by its
 * kind (a vessel stands breached, a heat side closes and lets its holdup out once, a machine fails), blast damage refusing the
 * repair, the idle-and-empty rule of the structure repair, the cleaning, the corrosive air adding, and the switches. One
 * island (1) holds every device; its last result is synthesized, so nothing solves.
 */
class IntegrityTest {
    private static final FluidThermodynamics MODEL = FluidTestSupport.networkModel();
    private static final PipeResistance.Geometry LINE = new PipeResistance.Geometry(1, .05, PipeResistance.DEFAULT_ROUGHNESS_METRES, 0);
    /** The devices: a 3 m³ vessel, an exchanger's tube side, and a pump casing. */
    private static final long VESSEL = 1, SIDE = 2, PUMP = 3;
    /** The graph's other nodes: the heat side's supply and drain, and the pump's discharge. */
    private static final long SUPPLY = 100, DRAIN = 101, DELIVERY = 102;

    private static PhysicalFluidTopology.Position at(int x) {
        return new PhysicalFluidTopology.Position(EventPathWorld.DIM, x, 64, 0);
    }

    private static PhysicalFluidTopology.Port port(int x, PhysicalFluidTopology.ConnectionRole role) {
        return new PhysicalFluidTopology.Port(at(x), PhysicalFluidTopology.Direction.UP, PassiveNetwork.PhasePort.BULK, role);
    }

    private static FluidThermodynamics.State water(double pressure) {
        var n = new double[MODEL.componentCount()];
        n[n.length - 1] = 1;
        return MODEL.flashTP(298.15, pressure, n, () -> {
        });
    }

    private static FluidThermodynamics.State nitrogen(double pressure) {
        var n = FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG).composition().clone();
        return MODEL.flashTP(298.15, pressure, n, () -> {
        });
    }

    /** A constructed closed vessel at x {@code 10 × id}: registered with its nozzle port. */
    private static WorldTopologyLedger.Registration vessel(long id, boolean breached) {
        int x = (int) (10 * id);
        var device = PhysicalFluidTopology.Device.builder(id, at(x), TopologyCompiler.Kind.RESERVOIR, PhysicalFluidTopology.Direction.NORTH, LINE, new FlowControl.Passive())
                .ports(List.of(port(x, PhysicalFluidTopology.ConnectionRole.VESSEL))).build();
        var spec = FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG);
        return new WorldTopologyLedger.Registration(device, new FluidDeviceSpec(3, 298.15, 101325, spec.composition()).withBreached(breached), 0);
    }

    private static WorldTopologyLedger.Registration side() {
        var device = PhysicalFluidTopology.Device.builder(SIDE, at(-20), TopologyCompiler.Kind.HEAT_SIDE, PhysicalFluidTopology.Direction.NORTH, HeatSides.tubePassage(1, 6), new FlowControl.Passive())
                .ports(List.of(port(-20, PhysicalFluidTopology.ConnectionRole.INLET), port(-18, PhysicalFluidTopology.ConnectionRole.OUTLET)))
                .heat(new HeatUnit(HeatSides.Unit.EXCHANGER, HeatSides.Side.TUBE, 450, 99)).build();
        return new WorldTopologyLedger.Registration(device, FluidDeviceSpec.water(EventPathWorld.CATALOG), 0);
    }

    private static WorldTopologyLedger.Registration pump() {
        var device = PhysicalFluidTopology.Device.builder(PUMP, at(-40), TopologyCompiler.Kind.PUMP, PhysicalFluidTopology.Direction.NORTH, LINE, new FlowControl.Pump(.01, 5e5))
                .ports(List.of(port(-40, PhysicalFluidTopology.ConnectionRole.SUCTION), port(-38, PhysicalFluidTopology.ConnectionRole.DISCHARGE))).build();
        return new WorldTopologyLedger.Registration(device, FluidDeviceSpec.water(EventPathWorld.CATALOG), 0);
    }

    /** A one-block vessel: no ports, so not constructed equipment. */
    private static WorldTopologyLedger.Registration tank(long id) {
        var device = PhysicalFluidTopology.Device.builder(id, at((int) (10 * id)), TopologyCompiler.Kind.RESERVOIR, PhysicalFluidTopology.Direction.NORTH, LINE, new FlowControl.Passive()).build();
        return new WorldTopologyLedger.Registration(device, FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG), 0);
    }

    /** What the walls did to the world. */
    private static final class Host implements Integrity.Host {
        final List<Long> closed = new ArrayList<>(), opened = new ArrayList<>(), released = new ArrayList<>();
        final List<String> weeps = new ArrayList<>();
        /** The restates queued (WP5), and what the ledger held for each side when its restate was queued. */
        final List<Long> restated = new ArrayList<>();
        final List<Double> ledgerAtRestate = new ArrayList<>();
        WorldTopologyLedger ledger;
        double air;

        @Override
        public boolean restate(long id) {
            restated.add(id);
            var facts = ledger == null ? null : ledger.integrity().get(id);
            ledgerAtRestate.add(facts == null ? 0 : facts.fouling());
            return true;
        }

        public boolean close(long id) {
            closed.add(id);
            return true;
        }

        public boolean open(long id) {
            opened.add(id);
            return true;
        }

        public void weeping(long id, boolean on) {
            weeps.add(id + (on ? " weeps" : " stops"));
        }

        public void release(long id) {
            released.add(id);
        }

        public double corrosiveAir(PhysicalFluidTopology.Position at) {
            return air;
        }

        public com.wormzjl.icheme.science.integrity.CorrosionStandard standard(List<String> compounds) {
            return FluidWorldAuthority.corrosionStandards(EventPathWorld.CATALOG, MODEL).apply(compounds);
        }
    }

    /** The world: the ledger with {@code registrations}, island 1 holding {@code graph}, and the walls. */
    private static final class Rig {
        final long[] epoch = {0};
        final IslandCoordinator coordinator;
        final WorldTopologyLedger ledger;
        final MachineHealth health;
        final Integrity integrity;
        final Host host = new Host();
        Integrity.Settings settings = Integrity.Settings.DEFAULT;

        Rig(int factor, Collection<WorldTopologyLedger.Registration> registrations, PassiveNetwork graph, double[] flows) {
            var time = SimulationTime.of(factor);
            coordinator = new IslandCoordinator(new IslandCoordinator.Dispatcher() {
                public int availableWorkers() {
                    return 0;
                }

                public long nextRequestId() {
                    return 1;
                }

                public boolean submit(IslandCoordinator.Attempt attempt, ProcessSolveJobs.FluidIslandCommand command) {
                    throw new AssertionError("nothing solves here");
                }

                public void cancel(long request) {
                    throw new AssertionError("nothing solves here");
                }
            }, changed -> {
            }, () -> 0L, new IslandCoordinator.Settings(30_000_000_000L, 20_000_000_000L, 64, false, 100, CertificatePolicy.defaults()).withTime(time), () -> epoch[0]);
            var empty = WorldTopologyLedger.Snapshot.empty(EventPathWorld.CATALOG);
            var active = new LinkedHashMap<Long, WorldTopologyLedger.Registration>();
            for (var r : registrations) active.put(r.device().id(), r);
            ledger = new WorldTopologyLedger(new WorldTopologyLedger.Snapshot(0, 1000, active, List.of(), empty.constructed(), empty.destroyed(), empty.basis()));
            owner = id -> active.containsKey(id) ? 1L : null;
            health = new MachineHealth(ledger, owner::apply, coordinator, id -> id == PUMP && active.containsKey(PUMP), id -> null, () -> new Wear.Settings(true, 1, 1));
            coordinator.register(new IslandCoordinator.Snapshot(1, 0, graph, new IslandClock.Snapshot(0, 0, 0, 100), Optional.of(result(graph, flows)), "READY"), MODEL.withTime(time));
            host.ledger = ledger;
            integrity = new Integrity(ledger, owner::apply, coordinator, health, () -> settings, host);
        }

        final LongFunctionOwner owner;

        /** The walls as a load builds them again from the ledger's facts, with their own host. */
        Integrity reload(Host other) {
            other.ledger = ledger;
            return new Integrity(ledger, owner::apply, coordinator, health, () -> settings, other);
        }

        /** Advances the online clock by {@code ticks} and runs the walls' cadence. */
        void cadence(long ticks) {
            epoch[0] += ticks;
            integrity.cadence();
        }

        /** Island 1 commits a span ending now with its registered result. */
        void booked() {
            integrity.booked(1, epoch[0] - 1, epoch[0], coordinator.observe(1).lastResult().orElseThrow());
        }

        Integrity.Outcome repair(long... devices) {
            return apply(false, devices);
        }

        Integrity.Outcome clean(long... devices) {
            return apply(true, devices);
        }

        private Integrity.Outcome apply(boolean clean, long... devices) {
            var list = Arrays.stream(devices).boxed().toList();
            Integrity.Outcome[] out = {null};
            if (clean) integrity.queueClean(list, o -> out[0] = o);
            else integrity.queueRepair(list, o -> out[0] = o);
            assertTrue(integrity.hasEvents());
            integrity.apply();
            assertFalse(integrity.hasEvents());
            return out[0];
        }
    }

    private interface LongFunctionOwner {
        Long apply(long id);
    }

    private static PassiveIntervalSolver.Result result(PassiveNetwork graph, double[] flows) {
        int pipes = graph.pipes().size();
        return new PassiveIntervalSolver.Result(graph, 5, flows, 1, 0, 0, List.of(), Map.of(), Collections.nCopies(pipes, FlowControl.Mode.PASSIVE), new double[pipes], flows.clone(), List.of());
    }

    /** The plant: the vessel holding {@code contents}, the heat side between a water supply and drain, the pump into a delivery. */
    private static Rig plant(int factor, FluidThermodynamics.State contents, double sideFlow, double vesselFlow, boolean breached) {
        var nodes = List.of(new PassiveNetwork.Reservoir(VESSEL, 0, contents), new PassiveNetwork.Reservoir(SUPPLY, 0, water(2e5), PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(DRAIN, 0, water(101325), PassiveNetwork.NodeKind.VOID), new PassiveNetwork.Reservoir(PUMP, 0, water(101325)),
                new PassiveNetwork.Reservoir(DELIVERY, 0, water(101325), PassiveNetwork.NodeKind.VOID));
        var pipes = List.of(new PassiveNetwork.Pipe(PhysicalFluidTopology.sideIdentity(SIDE), 1, 2, HeatSides.tubePassage(1, 6)),
                new PassiveNetwork.Pipe(20, 0, 2, LINE), new PassiveNetwork.Pipe(30, 3, 4, LINE, new FlowControl.Pump(.01, 5e5, false)));
        return new Rig(factor, List.of(vessel(VESSEL, breached), side(), pump()), new PassiveNetwork(nodes, pipes), new double[]{sideFlow, vesselFlow, 0});
    }

    private static Rig plant() {
        return plant(1, nitrogen(101325), 0, 0, false);
    }

    @Test
    void ratesBookOverProcessTimeAtTheTimeFactor() {
        for (int factor : new int[]{1, 4}) {
            var rig = plant(factor, nitrogen(101325), 0, 0, false);
            rig.integrity.rate(VESSEL, 2, Mechanism.AERATED_WATER);
            rig.cadence(100);
            // 100 online ticks are 5 s at factor 1, 20 s at factor 4; 2 mm per process year of 200 h, the default (D12; a year of
            // 20 h before: a tenth of the old loss).
            double seconds = 100 / 20.0 * factor;
            assertEquals(seconds, rig.coordinator.time().seconds(100), 1e-12);
            assertEquals(2 * seconds / (200 * 3600), rig.integrity.wall(VESSEL).loss(), 1e-15, "factor " + factor);
            // A process year of 10 h books twenty times as fast.
            rig.settings = new Integrity.Settings(true, 10, true, true, true, 10);
            rig.cadence(100);
            assertEquals(2 * seconds / (200 * 3600) + 2 * seconds / (10 * 3600), rig.integrity.wall(VESSEL).loss(), 1e-15);
            var wall = rig.integrity.wall(VESSEL);
            assertEquals(Mechanism.AERATED_WATER, wall.mechanism());
            assertEquals(Metallurgy.CARBON_STEEL.allowance(), wall.allowance());
            // The loss is a fact for the ledger, the rate is not.
            assertEquals(wall.loss(), rig.ledger.integrity().get(VESSEL).wallLoss(), 1e-18);
        }
    }

    @Test
    void whichDevicesHaveWalls() {
        var rig = new Rig(1, List.of(vessel(VESSEL, false), side(), pump(), tank(7)), new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(VESSEL, 0, nitrogen(101325))), List.of()), new double[0]);
        assertEquals(Integrity.Kind.VESSEL, rig.integrity.kindOf(VESSEL));
        assertEquals(Integrity.Kind.HEAT_SIDE, rig.integrity.kindOf(SIDE));
        assertEquals(Integrity.Kind.MACHINE, rig.integrity.kindOf(PUMP));
        assertNull(rig.integrity.kindOf(7), "a one-block tank is not constructed equipment");
        assertFalse(rig.integrity.set(7, 1, 0, false));
        rig.integrity.wreck(7);
        assertFalse(rig.integrity.wrecked(7), "a blast wrecks only constructed equipment");
    }

    @Test
    void evaluationsAreBoundedAndTakenRoundRobin() {
        int count = 300;
        var registrations = new ArrayList<WorldTopologyLedger.Registration>();
        var nodes = new ArrayList<PassiveNetwork.Reservoir>();
        var air = nitrogen(101325);
        for (long id = 1; id <= count; id++) {
            registrations.add(vessel(id, false));
            nodes.add(new PassiveNetwork.Reservoir(id, 0, air));
        }
        var rig = new Rig(1, registrations, new PassiveNetwork(nodes, List.of()), new double[0]);
        rig.host.air = 2;
        rig.epoch[0] = 1;
        rig.booked();
        rig.cadence(MachineHealth.CADENCE);
        assertEquals(Integrity.EVALUATIONS, rig.integrity.evaluations());
        double first = CorrosionLaw.outside(Metallurgy.CARBON_STEEL, 2);
        assertTrue(first > 0);
        for (long id = 1; id <= count; id++)
            assertEquals(id <= Integrity.EVALUATIONS ? first : 0, rig.integrity.wall(id).rate(), 1e-12, "vessel " + id + " after the first cadence");
        // No new span: only the 44 not yet evaluated take their turn.
        rig.cadence(MachineHealth.CADENCE);
        assertEquals(count, rig.integrity.evaluations());
        for (long id = 1; id <= count; id++) assertEquals(first, rig.integrity.wall(id).rate(), 1e-12);
        rig.cadence(MachineHealth.CADENCE);
        assertEquals(count, rig.integrity.evaluations(), "nothing new to read");
        // A new span and new air: the next 256 from the cursor, wrapping to the first.
        rig.host.air = 4;
        rig.booked();
        rig.cadence(MachineHealth.CADENCE);
        assertEquals(count + Integrity.EVALUATIONS, rig.integrity.evaluations());
        double second = CorrosionLaw.outside(Metallurgy.CARBON_STEEL, 4);
        for (long id = 1; id <= count; id++)
            assertEquals(id <= Integrity.EVALUATIONS ? second : first, rig.integrity.wall(id).rate(), 1e-12, "vessel " + id + " after the new span");
        rig.cadence(MachineHealth.CADENCE);
        for (long id = 1; id <= count; id++) assertEquals(second, rig.integrity.wall(id).rate(), 1e-12);
    }

    @Test
    void corrosiveAirAddsToTheInsideRate() {
        var rig = plant();
        rig.booked();
        rig.cadence(MachineHealth.CADENCE);
        double clean = rig.integrity.wall(VESSEL).rate();
        rig.host.air = 3;
        rig.booked();
        rig.cadence(MachineHealth.CADENCE);
        var wall = rig.integrity.wall(VESSEL);
        assertEquals(clean + CorrosionLaw.outside(Metallurgy.CARBON_STEEL, 3), wall.rate(), 1e-12);
        assertEquals(Mechanism.CORROSIVE_AIR, wall.mechanism(), "dry nitrogen inside: the air governs");
        // corrosion.external false: the air adds nothing.
        rig.settings = new Integrity.Settings(true, 20, false, true, true, 10);
        rig.booked();
        rig.cadence(MachineHealth.CADENCE);
        assertEquals(clean, rig.integrity.wall(VESSEL).rate(), 1e-12);
    }

    @Test
    void aFlowingHeatSideFoulsTowardItsAsymptote() {
        var rig = plant(1, nitrogen(101325), 1, 0, false);
        rig.booked();
        rig.cadence(MachineHealth.CADENCE);
        assertEquals(0, rig.integrity.fouling(SIDE), "the first cadence only reads the flow");
        rig.cadence(20 * 3600);
        double rf = rig.integrity.fouling(SIDE);
        // An hour of flowing water at 25 °C: 1 - e^(-1/100) of the asymptote at the default 100 h (D12): 1.74e-6 m²K/W towards
        // the halved end value 0.000175 (D14; 3.48e-6 towards the anchor 0.00035 before), where the old 10 h gave 1 - e^(-1/10), 3.33e-5.
        double end = FoulingLaw.PLACEHOLDER_SHARE * FoulingLaw.WATER;
        assertEquals(FoulingLaw.step(0, end, 3600, 100), rf, 1e-12);
        assertTrue(rf > 0 && rf < end);
        assertEquals(rf, rig.ledger.integrity().get(SIDE).fouling(), 1e-18);
        // A side no flow passes keeps its deposit.
        var still = plant(1, nitrogen(101325), 0, 0, false);
        still.booked();
        still.cadence(MachineHealth.CADENCE);
        still.cadence(20 * 3600);
        assertEquals(0, still.integrity.fouling(SIDE));
    }

    @Test
    void aWallThroughFailsOnceByItsKind() {
        var rig = plant();
        double through = Metallurgy.CARBON_STEEL.allowance();
        for (long id : new long[]{VESSEL, SIDE, PUMP}) assertTrue(rig.integrity.set(id, through, 0, false));
        assertEquals("LEAKING", rig.integrity.status(VESSEL));
        assertEquals("LEAKING: tube leak", rig.integrity.status(SIDE));
        assertEquals("", rig.integrity.status(PUMP), "a machine through says FAILED by its health");
        rig.cadence(MachineHealth.CADENCE);
        assertEquals(List.of(VESSEL, SIDE), rig.host.closed, "the vessel stands breached, the heat side closes");
        assertEquals(List.of(SIDE), rig.host.released, "the heat side's holdup is let out once");
        assertTrue(rig.health.hasEvents());
        rig.health.apply();
        assertTrue(rig.health.failed(PUMP), "the machine fails");
        for (int k = 0; k < 3; k++) rig.cadence(MachineHealth.CADENCE);
        assertEquals(List.of(VESSEL, SIDE), rig.host.closed, "once");
        assertEquals(List.of(SIDE), rig.host.released, "once");
        assertFalse(rig.health.hasEvents());
        // Below through and through again: a new failure.
        rig.integrity.set(VESSEL, 0, 0, false);
        rig.cadence(MachineHealth.CADENCE);
        rig.integrity.set(VESSEL, through, 0, false);
        rig.cadence(MachineHealth.CADENCE);
        assertEquals(List.of(VESSEL, SIDE, VESSEL), rig.host.closed);
        // The words follow the allowance used.
        rig.integrity.set(VESSEL, .5 * through, 0, false);
        assertEquals("THIN WALL", rig.integrity.status(VESSEL));
        rig.integrity.set(VESSEL, .9 * through, 0, false);
        assertEquals("WEEPING", rig.integrity.status(VESSEL));
        rig.integrity.set(VESSEL, .4 * through, 0, false);
        assertEquals("", rig.integrity.status(VESSEL));
    }

    @Test
    void aWeepingWallIsToldAtEachCadenceAndOnceWhenItStops() {
        var rig = plant(1, nitrogen(101325), 0, 0, false);
        double through = Metallurgy.CARBON_STEEL.allowance();
        rig.integrity.set(VESSEL, .5 * through, 0, false);
        rig.cadence(MachineHealth.CADENCE);
        assertEquals(List.of(), rig.host.weeps, "a thin wall does not weep");
        rig.integrity.set(VESSEL, .95 * through, 0, false);
        rig.cadence(MachineHealth.CADENCE);
        rig.cadence(MachineHealth.CADENCE);
        assertEquals(List.of(VESSEL + " weeps", VESSEL + " weeps"), rig.host.weeps, "told at every cadence: the host sends only a change");
        assertTrue(rig.integrity.weeping(VESSEL));
        assertEquals(Integrity.Outcome.REPAIRED, rig.repair(VESSEL));
        assertEquals(List.of(VESSEL + " weeps", VESSEL + " weeps", VESSEL + " stops"), rig.host.weeps, "a repaired wall stops at once");
        assertFalse(rig.integrity.weeping(VESSEL));
        rig.cadence(MachineHealth.CADENCE);
        assertEquals(3, rig.host.weeps.size(), "and is not told again");
    }

    @Test
    void wreckedRefusesTheRepair() {
        var rig = plant(1, nitrogen(101325), 0, 0, true);
        rig.integrity.wreck(VESSEL);
        assertTrue(rig.integrity.wrecked(VESSEL));
        assertTrue(rig.ledger.integrity().get(VESSEL).wrecked(), "a fact at once");
        assertEquals("WRECKED: deconstruct and rebuild", rig.integrity.status(VESSEL));
        assertEquals(Integrity.Outcome.WRECKED, rig.repair(VESSEL, SIDE));
        assertEquals(Integrity.Outcome.WRECKED, rig.clean(VESSEL));
        assertEquals(List.of(), rig.host.opened);
        // Clearing the facts (the command) makes it repairable again.
        rig.integrity.set(VESSEL, 0, 0, false);
        assertEquals(Integrity.Outcome.REPAIRED, rig.repair(VESSEL));
        assertEquals(List.of(VESSEL), rig.host.opened);
    }

    @Test
    void theRepairNeedsEveryDeviceIdleAndEveryVesselEmpty() {
        double loss = 1;
        // Nothing worn, nothing breached: nothing to repair.
        assertEquals(Integrity.Outcome.NOTHING_TO_REPAIR, plant().repair(VESSEL, SIDE, PUMP));
        // Flow through the vessel's nozzle.
        var flowing = plant(1, nitrogen(101325), 0, .5, false);
        flowing.integrity.set(VESSEL, loss, 0, false);
        assertEquals(Integrity.Outcome.NOT_IDLE, flowing.repair(VESSEL));
        // Flow through the heat side, the vessel worn.
        var side = plant(1, nitrogen(101325), .5, 0, false);
        side.integrity.set(VESSEL, loss, 0, false);
        assertEquals(Integrity.Outcome.NOT_IDLE, side.repair(VESSEL, SIDE));
        assertEquals(Integrity.Outcome.REPAIRED, side.repair(VESSEL), "the vessel alone stands idle");
        // Idle, but under pressure.
        var pressed = plant(1, nitrogen(3e5), 0, 0, false);
        pressed.integrity.set(VESSEL, loss, 0, false);
        assertEquals(Integrity.Outcome.NOT_EMPTY, pressed.repair(VESSEL));
        // Idle at the ambient, but holding liquid.
        var wet = plant(1, water(101325), 0, 0, false);
        wet.integrity.set(VESSEL, loss, 0, false);
        assertEquals(Integrity.Outcome.NOT_EMPTY, wet.repair(VESSEL));
        // Idle and empty: every wall new, the breach closed, the machine new.
        var ready = plant(1, nitrogen(101325), 0, 0, true);
        ready.integrity.set(VESSEL, loss, 0, false);
        ready.integrity.set(SIDE, loss, 0, false);
        assertEquals(Integrity.Outcome.REPAIRED, ready.repair(VESSEL, SIDE, PUMP));
        assertEquals(0, ready.integrity.wall(VESSEL).loss());
        assertEquals(0, ready.integrity.wall(SIDE).loss());
        assertEquals(List.of(VESSEL), ready.host.opened, "only the breached vessel opens again");
        assertNull(ready.ledger.integrity().get(VESSEL), "a new wall is no fact");
        // The test host leaves the vessel's spec breached (the authority's edit opens it): the rest has nothing to repair.
        assertEquals(Integrity.Outcome.NOTHING_TO_REPAIR, ready.repair(SIDE, PUMP));
    }

    @Test
    void theCleaningClearsTheDepositUnderTheSameRule() {
        var rig = plant();
        assertEquals(Integrity.Outcome.NOTHING_TO_REPAIR, rig.clean(SIDE));
        rig.integrity.set(SIDE, 0, .0005, false);
        assertEquals(10, rig.integrity.foulingLevel(SIDE), "10 steps of 5e-5 (5 of 1e-4 before D14)");
        assertEquals(Integrity.Outcome.CLEANED, rig.clean(SIDE));
        assertEquals(0, rig.integrity.fouling(SIDE));
        assertNull(rig.ledger.integrity().get(SIDE));
        var flowing = plant(1, nitrogen(101325), .5, 0, false);
        flowing.integrity.set(SIDE, 0, .0005, false);
        assertEquals(Integrity.Outcome.NOT_IDLE, flowing.clean(SIDE));
        assertEquals(.0005, flowing.integrity.fouling(SIDE));
    }

    /**
     * WP5 (plan section 12): the graph sees a deposit only in whole steps of 0.00005 m²K/W (D14; 0.0001 before). Flowing water at
     * 25 °C grows towards 0.000175 (D14; 0.00035 before) with a 10 h time constant: under the first step for three process hours
     * (4.54e-5), over it in the fourth (5.77e-5; 1.15e-4 against the old step before D14), so one
     * restate is queued then, after the facts reached the ledger; none in the hour after, still at that step. The rig runs the old
     * 10 h time constant explicitly: at the default 100 h (D12) the first step comes only after 34 process hours.
     */
    @Test
    void aDepositStepQueuesOneRestateAfterItsFactsReachTheLedger() {
        var rig = plant(1, nitrogen(101325), 1, 0, false);
        rig.settings = new Integrity.Settings(true, 20, true, true, true, 10);
        rig.booked();
        rig.cadence(MachineHealth.CADENCE);
        for (int hour = 1; hour <= 3; hour++) {
            rig.cadence(20 * 3600);
            assertEquals(0, rig.integrity.foulingLevel(SIDE), "hour " + hour);
        }
        assertEquals(List.of(), rig.host.restated, "under a step the graph stays as it is: no event");
        rig.cadence(20 * 3600);
        assertEquals(1, rig.integrity.foulingLevel(SIDE));
        assertEquals(List.of(SIDE), rig.host.restated);
        assertEquals(rig.integrity.fouling(SIDE), rig.host.ledgerAtRestate.getFirst(), 1e-18, "the facts are in the ledger before the restate");
        rig.cadence(20 * 3600);
        assertEquals(1, rig.integrity.foulingLevel(SIDE));
        assertEquals(1, rig.integrity.restates(), "the same step queues nothing more");
        // A set deposit (the command, the tests) and a cleaning each move the step once.
        rig.integrity.set(SIDE, 0, .0005, false);
        assertEquals(List.of(SIDE, SIDE), rig.host.restated);
        rig.integrity.set(SIDE, 0, .00052, false);
        assertEquals(2, rig.integrity.restates(), "a deposit inside the same step (level 10) changes nothing the solver reads");
        var idle = plant();
        idle.integrity.set(SIDE, 0, .0005, false);
        assertEquals(Integrity.Outcome.CLEANED, idle.clean(SIDE));
        assertEquals(List.of(SIDE, SIDE), idle.host.restated, "set to 10 (5 before D14), cleaned to 0");
        assertEquals(0, idle.host.ledgerAtRestate.getLast());
    }

    @Test
    void aRestoredWorldQueuesNoRestateAndFoulingOffRestatesToClean() {
        var rig = plant();
        rig.integrity.set(SIDE, 0, .0005, false);
        // A load builds the walls from the ledger's facts; the restored graph was compiled from the same facts.
        var host = new Host();
        var restored = rig.reload(host);
        assertEquals(10, restored.foulingLevel(SIDE), "5 before D14");
        rig.epoch[0] += MachineHealth.CADENCE;
        restored.cadence();
        assertEquals(List.of(), host.restated);
        // fouling.enabled false: the deposit stays a fact, the graph reads level 0, once.
        rig.settings = new Integrity.Settings(true, 20, true, true, false, 10);
        rig.epoch[0] += MachineHealth.CADENCE;
        restored.cadence();
        assertEquals(List.of(SIDE), host.restated);
        assertEquals(.0005, restored.fouling(SIDE));
        assertFalse(restored.fouled(SIDE));
        rig.epoch[0] += MachineHealth.CADENCE;
        restored.cadence();
        assertEquals(1, restored.restates());
        // On again, and at FOULED's threshold the status word stands.
        rig.settings = Integrity.Settings.DEFAULT;
        restored.set(SIDE, 0, FoulingLaw.FOULED, false);
        assertTrue(restored.fouled(SIDE));
        assertEquals(List.of(SIDE, SIDE), host.restated);
    }

    @Test
    void switchesOffBookNothingAndFailNothing() {
        var rig = plant(1, nitrogen(101325), 1, 0, false);
        rig.settings = new Integrity.Settings(false, 20, true, false, false, 10);
        rig.host.air = 5;
        rig.booked();
        rig.cadence(MachineHealth.CADENCE);
        rig.integrity.rate(VESSEL, 5, Mechanism.AERATED_WATER);
        rig.integrity.rate(SIDE, 5, Mechanism.EROSION);
        rig.cadence(20 * 3600);
        assertEquals(0, rig.integrity.wall(VESSEL).loss());
        assertEquals(0, rig.integrity.wall(VESSEL).rate());
        assertNull(rig.integrity.wall(VESSEL).mechanism());
        assertEquals(0, rig.integrity.wall(SIDE).loss());
        assertEquals(0, rig.integrity.fouling(SIDE), "fouling.enabled false");
        // A wall through fails nothing while both switches are off.
        rig.integrity.set(VESSEL, Metallurgy.CARBON_STEEL.allowance(), 0, false);
        rig.cadence(MachineHealth.CADENCE);
        assertEquals(List.of(), rig.host.closed);
        // Erosion on alone: only the erosion part books.
        rig.integrity.set(VESSEL, 0, 0, false);
        rig.settings = new Integrity.Settings(false, 20, true, true, false, 10);
        rig.cadence(20 * 3600);
        assertEquals(0, rig.integrity.wall(VESSEL).loss());
        assertEquals(5 * 3600 / (20 * 3600.0), rig.integrity.wall(SIDE).loss(), 1e-12);
        assertEquals(Mechanism.EROSION, rig.integrity.wall(SIDE).mechanism());
    }
}
