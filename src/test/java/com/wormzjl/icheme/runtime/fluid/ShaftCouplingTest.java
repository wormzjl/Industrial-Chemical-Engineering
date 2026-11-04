package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.runtime.ProcessSolveServices;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.machine.CentrifugalPump;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The shaft trains' coupling to the islands (batch 2026-11-03-rotating-machinery W3, build plan section 2): a machine's
 * speed is a slice input read at dispatch, so a change reaches the island's next slice, one slice after the step that
 * made it; a certified island stays certified while its speed holds, and a change of speed ends its certificate
 * ({@link IslandCoordinator#shaftsMoved}, a coupling wake). The governor valve walk finds the turbine's valve.
 */
class ShaftCouplingTest {
    /**
     * A driver's state with no slice before it (NaN: none yet) saves and reads back (W5 found this: Writer.f64 refuses
     * non-finite numbers, so a world with a started driver failed to save).
     */
    @Test void aDriverStateWithNoSliceBeforeItSavesAndReadsBack() {
        var cell = new PhysicalFluidTopology.Position(EventPathWorld.DIM, 0, 66, 0);
        var states = List.of(new ShaftUnit.State(2950, Double.NaN, Double.NaN), new ShaftUnit.State(2950, 2940, Double.NaN),
                new ShaftUnit.State(1500, 1490, .8));
        var units = new HashMap<Long, ShaftUnit>();
        for (int i = 0; i < states.size(); i++) units.put(i + 1L, ShaftUnit.shaft(i + 1L, cell, 0).withState(states.get(i)));
        byte[] data = FluidFacts.topologyState(List.of(), Map.of(), units);
        var read = FluidFacts.topologyState(data, 0, data.length).shafts();
        for (int i = 0; i < states.size(); i++) assertEquals(states.get(i), read.get(i + 1L), "state " + i);
    }
    private final FluidThermodynamics model = FluidTestSupport.networkModel();
    private static final PipeResistance.Geometry FAT = new PipeResistance.Geometry(1, .1, PipeResistance.DEFAULT_ROUGHNESS_METRES, 0);
    private static final CentrifugalPump PUMP = new CentrifugalPump(.01, 50, .7);

    private FluidThermodynamics.State water(double pressure) {
        var n = new double[model.componentCount()];
        n[n.length - 1] = 1;
        return model.flashTP(298.15, pressure, n, () -> {
        });
    }

    /** A test train: the share it sends the pump at node 1, and the speeds it saw each machine solve at. */
    private static final class Train implements IslandCoordinator.Shafts {
        double share;
        final List<Double> booked = new ArrayList<>();
        int decisions;

        public Map<Long, ShaftPlan> plans(long island, PassiveNetwork graph) {
            return Map.of(1L, new ShaftPlan(ShaftPlan.Kind.SPEED, share));
        }

        public void booked(long island, PassiveNetwork graph, long start, long end, PassiveIntervalSolver.Result result) {
            if (result.graph().pipes().getFirst().control() instanceof FlowControl.Machine m) booked.add(m.speed());
        }

        public void decide() {
            decisions++;
        }
    }

    private final class Rig {
        final long[] epoch = {0};
        final Map<Long, IslandCoordinator.Attempt> attempts = new LinkedHashMap<>();
        final Map<Long, ProcessSolveServices.FluidIslandCommand> commands = new HashMap<>();
        final IslandCoordinator coordinator;
        long requests;

        Rig() {
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
            }, () -> 0L, new IslandCoordinator.Settings(30_000_000_000L, 20_000_000_000L, 64, false, 100, CertificatePolicy.defaults()), () -> epoch[0]);
        }

        void drain() {
            while (!attempts.isEmpty()) {
                for (long request : List.copyOf(attempts.keySet())) {
                    var attempt = attempts.remove(request);
                    var command = commands.remove(request);
                    coordinator.completed(attempt, Optional.of((ProcessSolveServices.FluidIslandSolveResult) command.solve(FluidTestSupport.NEVER)));
                }
                coordinator.pump();
            }
        }

        void run(double seconds) {
            long ticks = Math.round(seconds * 20);
            for (long i = 0; i < ticks; i++) {
                epoch[0]++;
                coordinator.tick();
                drain();
            }
        }
    }

    /** The pump between a supply and a void that asks its 0.8 speed line's rise at 80 % of its rated flow. */
    private PassiveNetwork line() {
        var supply = water(101325);
        double rho = supply.mass() / supply.volume(), rise = PUMP.rise(.008, .8, rho, supply.pressure(), 1.4);
        return new PassiveNetwork(List.of(new PassiveNetwork.Reservoir(1, 0, supply, PassiveNetwork.NodeKind.GENERATOR),
                new PassiveNetwork.Reservoir(2, 0, water(101325 + rise), PassiveNetwork.NodeKind.VOID)),
                List.of(new PassiveNetwork.Pipe(10, 0, 1, FAT, new FlowControl.Machine(PUMP, 0, true))));
    }

    @Test
    void aSpeedReachesTheNextSliceAndAChangeWakesACertifiedIsland() {
        var rig = new Rig();
        var train = new Train();
        train.share = .8;
        rig.coordinator.shafts(train);
        rig.coordinator.register(new IslandCoordinator.Snapshot(1, 0, line(), new IslandClock.Snapshot(0, 0, 0, 100), Optional.empty(), "READY"), model);
        for (int i = 0; i < 240 && rig.coordinator.observe(1).certificate().isEmpty(); i++) rig.run(5);
        assertTrue(rig.coordinator.observe(1).certificate().isPresent(), "a steady pump line certifies: " + rig.coordinator.certificationRefusal(1));
        assertTrue(train.booked.stream().allMatch(s -> s == .8), "every slice solved at the share sent at its dispatch");
        assertTrue(train.decisions > 0, "the trains decide at the coordinator's decision points");
        double flow = rig.coordinator.observe(1).lastResult().orElseThrow().endpointFlows()[0];
        // While the share holds, the certificate stands.
        int before = train.booked.size();
        rig.run(60);
        assertTrue(rig.coordinator.observe(1).certificate().isPresent(), "the share held: still certified");
        // A step to share 1: one wake, then the next slice solves at 1 (one slice after the step).
        boolean enabled = FluidRuntimeDiagnostics.ENABLED;
        FluidRuntimeDiagnostics.ENABLED = true;
        long wakes = FluidRuntimeDiagnostics.couplingWakes.sum();
        train.share = 1;
        int seen = train.booked.size();
        rig.coordinator.shaftsMoved(Set.of(1L));
        rig.run(10);
        long woken = FluidRuntimeDiagnostics.couplingWakes.sum() - wakes;
        FluidRuntimeDiagnostics.ENABLED = enabled;
        assertEquals(1, woken, "the speed change woke the certified island once");
        assertTrue(train.booked.size() > seen, "it solved again");
        assertEquals(1.0, train.booked.getLast(), 0, "at the new share");
        double faster = rig.coordinator.observe(1).lastResult().orElseThrow().endpointFlows()[0];
        assertTrue(faster > 1.1 * flow, "full speed against the same static rise moves more: " + flow + " -> " + faster);
        assertTrue(before > 0);
    }

    private static PhysicalFluidTopology.Position cell(int x) {
        return new PhysicalFluidTopology.Position(EventPathWorld.DIM, x, 64, 0);
    }

    /**
     * W5 (build plan section 7.4): an 11 kW motor drives the pump at node 1 through a shaft. Built from W5 on, its store
     * starts empty, so it stands (NO POWER) until its port is filled to one slice of its rated draw; it then runs, books
     * its draw at its train's steps and on the cadence, runs short, stops (NO POWER, never saved as its trip) and restarts
     * by itself on a refill.
     */
    @Test
    void aMotorRunsShortTripsNoPowerAndRestartsOnARefill() {
        var rig = new Rig();
        var ledger = new WorldTopologyLedger(WorldTopologyLedger.Snapshot.empty(EventPathWorld.CATALOG));
        long first = ledger.reserve(3);
        var fixed = com.wormzjl.icheme.science.equipment.MachineDuty.Drive.FIXED;
        var trip = com.wormzjl.icheme.science.machine.ShaftTrain.Trip.NONE;
        ledger.shaft(new ShaftUnit(first, com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.MOTOR, cell(0), 0, List.of(new ShaftUnit.End(cell(0), 1, 0, 0, true)), 0,
                11e3, 2950, 2, fixed, ShaftUnit.Settings.DEFAULT, trip, null));
        ledger.shaft(ShaftUnit.shaft(first + 1, cell(1), 0));
        ledger.shaft(new ShaftUnit(first + 2, com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.CASING, cell(2), 0, List.of(new ShaftUnit.End(cell(2), -1, 0, 0, false)), 1,
                0, 2950, 0, fixed, ShaftUnit.Settings.DEFAULT, trip, null));
        var trains = new ShaftTrains(ledger, device -> device == 1 ? 1L : null, rig.coordinator);
        var stores = new ElectricStores(ledger, device -> device == 1 ? 1L : null, rig.coordinator, () -> ElectricStores.Settings.DEFAULT);
        trains.electric(stores);
        rig.coordinator.shafts(trains);
        trains.host(ShaftTrains.Host.NONE);
        rig.coordinator.register(new IslandCoordinator.Snapshot(1, 0, line(), new IslandClock.Snapshot(0, 0, 0, 100), Optional.empty(), "READY"), model);
        long motor = first;
        double rated = com.wormzjl.icheme.science.machine.ElectricStore.motorDraw(11e3, 2, false);
        int[] feed = {0};
        Runnable tick = () -> {
            rig.epoch[0]++;
            rig.coordinator.tick();
            rig.drain();
            if (feed[0] > 0) stores.receive(motor, feed[0], false);
            if (rig.epoch[0] % ElectricStores.CADENCE == 0) {
                stores.cadence();
                trains.cadence();
            }
        };
        // Empty: the motor stands.
        for (int i = 0; i < 300; i++) tick.run();
        assertEquals("TRIPPED: NO POWER", trains.view(motor).status(), "a new store is empty");
        assertEquals(com.wormzjl.icheme.science.machine.ShaftTrain.Trip.NO_POWER, trains.view(motor).trip());
        assertEquals(trip, ledger.shaft(motor).trip(), "NO POWER is never the unit's saved trip");
        assertEquals(0, trains.share(1), "the pump stands");
        // A port filled at its full rate: the store passes one slice of rated draw and the motor restarts on the cadence.
        feed[0] = Integer.MAX_VALUE;
        assertEquals(stores.receive(motor, Integer.MAX_VALUE, true), (int) Math.floor(2 * rated * .05 / 2.5 + 1e-9), "the port takes twice the rated draw per tick");
        for (int i = 0; i < 1000 && !trains.view(motor).status().equals("RUNNING"); i++) tick.run();
        assertEquals("RUNNING", trains.view(motor).status());
        assertTrue(stores.view(motor).joules() >= com.wormzjl.icheme.science.machine.ElectricStore.restart(rated) - 2 * rated * .05 * 100, "it restarted near one slice of rated draw");
        for (int i = 0; i < 600; i++) tick.run();
        assertEquals(1, trains.share(1), 1e-9, "the pump turns at its design speed");
        double draw = trains.view(motor).electricPower();
        assertTrue(draw > 1e3 && draw < rated, "it draws the pump's shaft power over its efficiency: " + draw);
        double full = com.wormzjl.icheme.science.machine.ElectricStore.capacity(rated);
        assertTrue(stores.view(motor).joules() >= full - draw * com.wormzjl.icheme.science.machine.ElectricStore.SLICE_SECONDS, "the port kept it full within a slice's booking: " + stores.view(motor).joules() + " of " + full);
        // The feed stops: the store drains at the draw, then the motor stops.
        feed[0] = 0;
        double before = stores.view(motor).joules();
        long start = rig.epoch[0];
        for (int i = 0; i < 20_000 && !trains.view(motor).status().equals("TRIPPED: NO POWER"); i++) tick.run();
        assertEquals("TRIPPED: NO POWER", trains.view(motor).status());
        assertEquals(0, stores.view(motor).joules(), "it booked what was left");
        double seconds = (rig.epoch[0] - start) / 20.0;
        assertEquals(before / draw, seconds, Math.max(10, .25 * before / draw), "it ran as long as its store held at its draw");
        for (int i = 0; i < 300; i++) tick.run();
        assertEquals(0, trains.share(1), "the pump stands without power");
        assertEquals(trip, ledger.shaft(motor).trip());
        // A refill restarts it by itself (D6), no reset.
        feed[0] = Integer.MAX_VALUE;
        for (int i = 0; i < 1000 && !trains.view(motor).status().equals("RUNNING"); i++) tick.run();
        assertEquals("RUNNING", trains.view(motor).status());
        for (int i = 0; i < 300; i++) tick.run();
        assertEquals(1, trains.share(1), 1e-9);
    }

    /** The governor walk: from a turbine's inlet upstream through unbranched pipes to the nearest control valve. */
    @Test
    void theGovernorWalkFindsTheTurbinesValve() {
        var steam = water(4e6);
        var v = new FlowControl.ControlValve(FlowControl.ValveSource.NONE, 200000, .1, .01, null, 1);
        var turbine = new FlowControl.Turbine(com.wormzjl.icheme.science.machine.SteamTurbine.rated(55e3));
        // header 1 -> pipe node 2 -> valve 3 -> pipe node 4 -> turbine 5 -> exhaust 6
        var nodes = new ArrayList<PassiveNetwork.Reservoir>();
        for (long id = 1; id <= 6; id++) nodes.add(new PassiveNetwork.Reservoir(id, 0, steam, id == 1 ? PassiveNetwork.NodeKind.GENERATOR : id == 6 ? PassiveNetwork.NodeKind.VOID : PassiveNetwork.NodeKind.RESERVOIR));
        var pipes = List.of(new PassiveNetwork.Pipe(101, 0, 1, FAT, new FlowControl.Passive()), new PassiveNetwork.Pipe(102, 2, 1, FAT, v),
                new PassiveNetwork.Pipe(103, 2, 3, FAT, new FlowControl.Passive()), new PassiveNetwork.Pipe(104, 4, 3, FAT, new FlowControl.Passive()),
                new PassiveNetwork.Pipe(105, 4, 5, FAT, turbine));
        var graph = new PassiveNetwork(nodes, pipes);
        assertEquals(3, ShaftTrains.upstreamValve(graph, 5), "the valve's node");
        // A branch between them stops the walk: no governor valve.
        var branched = new ArrayList<>(pipes);
        branched.add(new PassiveNetwork.Pipe(106, 3, 5, FAT, new FlowControl.Passive()));
        assertEquals(0, ShaftTrains.upstreamValve(new PassiveNetwork(nodes, branched), 5));
    }
}
