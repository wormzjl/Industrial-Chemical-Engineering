package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.ScheduledTransfer;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

public class CircuitLedgerDigest {
    private static final Runnable CHECK = () -> {};

    public static void main(String[] args) throws Exception {
        var model = FluidTestSupport.networkModel();
        double[] methane = new double[model.componentCount()];
        methane[0] = .5;
        double injectionEnergy = model.flashTP(350, 150_000, methane, CHECK).enthalpy();
        long id = 0;
        for (boolean gas : new boolean[]{false, true}) {
            for (double bore : new double[]{.02, .05}) for (int ports = gas ? 4 : 0; ports <= (gas ? 6 : 0); ports++) {
                for (boolean variation : new boolean[]{false, true}) {
                    var graph = gas ? MixedGasJunctionTransientTest.island(model, bore, ports, variation)
                            : LiquidJunctionTransientTest.island(model, variation, bore);
                    var solver = new PassiveIntervalSolver(model);
                    PassiveIntervalSolver.Result previous = null;
                    int tank = 0;
                    if (gas) while (graph.reservoirs().get(tank).id() != 1) tank++;
                    for (int i = 0; i < 4; i++) {
                        double dt = gas && i >= 2 ? 3 : 5;
                        if (gas) graph = new PassiveNetwork(graph.reservoirs(), graph.pipes(), i == 2
                                ? List.of(new ScheduledTransfer.Injection(900, tank, methane, injectionEnergy)) : List.of());
                        solver.replayStart(graph, previous);
                        previous = solver.solve(graph, dt, PassiveIntervalSolver.Settings.defaults(), CHECK);
                        if (previous.advancedSeconds() != dt) throw new IllegalStateException("Partial interval");
                        graph = previous.graph();
                        var clock = new IslandClock.Snapshot(100, 100, 0, 20);
                        // The transfer is already in the result ledger. The committed world cannot save request inputs.
                        var saved = graph.scheduledTransfers().isEmpty() ? graph : new PassiveNetwork(graph.reservoirs(), graph.pipes());
                        var snapshot = new IslandCoordinator.Snapshot(++id, 1, saved, clock, Optional.of(previous), "FULL");
                        var entry = new FluidCheckpointCodec.IslandEntry("offline:fixed", "fluid", snapshot);
                        var image = FluidCheckpointCodec.encode(new FluidCheckpointCodec.Checkpoint(List.of(entry)), ignored -> model);
                        var sha = MessageDigest.getInstance("SHA-256");
                        for (var pack : new TreeMap<>(image.packs()).values()) sha.update(pack);
                        System.out.printf("%s %.2f %d %s %d %d/%d %s%n", gas ? "gas" : "liquid", bore, ports, variation, i,
                                previous.acceptedSubsteps(), previous.rejectedSubsteps(), HexFormat.of().formatHex(sha.digest()));
                    }
                }
            }
        }
    }
}
