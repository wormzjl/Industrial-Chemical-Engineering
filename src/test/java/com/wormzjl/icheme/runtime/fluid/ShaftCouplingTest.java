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
