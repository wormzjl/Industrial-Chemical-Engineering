package com.wormzjl.icheme.runtime.fluid;

import java.util.Objects;

/**
 * A running column's members' power over the world's electric stores and shaft trains (W5 of batch
 * 2026-11-10-column-failure-modes, {@code MEMBER_POWER_NOTE.md}): a held member books at the column's cycle what its island's
 * commit would have booked, and the store or train says whether it has power for the next cycle. On the server thread.
 */
public final class ColumnPower implements ColumnOperation.Power {
    private final WorldTopologyLedger topology;
    private final ElectricStores electric;
    private final ShaftTrains shafts;

    public ColumnPower(WorldTopologyLedger topology, ElectricStores electric, ShaftTrains shafts) {
        this.topology = Objects.requireNonNull(topology);
        this.electric = Objects.requireNonNull(electric);
        this.shafts = Objects.requireNonNull(shafts);
    }

    @Override
    public boolean needed() {
        return electric.settings().needPower();
    }

    @Override
    public double surface(long side) {
        var r = topology.active().get(side);
        return r == null || r.device().heat() == null ? 0 : r.device().heat().area();
    }

    @Override
    public boolean fans(long side, double fans, long tick) {
        var r = topology.active().get(side);
        if (r == null || r.device().heat() == null) return true;
        return electric.member(side, ElectricStores.fanDraw(r.device().heat(), fans), tick);
    }
}
