package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.PassiveIntervalSolver;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PortPlan;
import com.wormzjl.icheme.science.fluid.network.Separation;

import java.util.Map;
import java.util.Objects;

/**
 * The vessels' separation as a slice input (batch 2026-11-17-three-phase-separator, plan section 4): at dispatch each
 * closed vessel gets what its outlets carry beside their own phase over the slice ({@link Separation#plan}), from its
 * committed state and what its connections moved over the island's last accepted slice. A read of the graph and the last
 * result, no solve. It keeps nothing: the last result is the island's own, so a restored island plans as it did.
 */
final class Separators implements IslandCoordinator.Ports {
    private final IslandCoordinator coordinator;

    Separators(IslandCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator);
    }

    @Override public boolean ready(long island, PassiveNetwork graph, long start) {
        return true;
    }

    @Override public Map<Long, PortPlan> plans(long island, PassiveNetwork graph, IslandClock.Slice slice) {
        return Map.of();
    }

    @Override public void booked(long island, PassiveNetwork graph, long start, long end, PassiveIntervalSolver.Result result) {
    }

    @Override public PassiveNetwork dispatch(long island, PassiveNetwork graph, IslandClock.Slice slice) {
        return Separation.plan(coordinator.model(island), graph, coordinator.lastResult(island).orElse(null));
    }
}
