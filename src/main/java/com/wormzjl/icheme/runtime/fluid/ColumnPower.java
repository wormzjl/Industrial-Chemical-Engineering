package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.machine.InlineMachines;
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

    @Override
    public boolean pump(long mover, double share, long tick) {
        var r = topology.active().get(mover);
        if (r == null || !(r.device().control() instanceof FlowControl.Mover m)) return true;
        double shaft = InlineMachines.ratedShaftPower(m) * Math.max(0, share);
        // A casing books on its train, whose motor draws from its own store; an in-line pump draws through its integral motor.
        if (shafts.unitOf(mover)) return shafts.held(mover, shaft);
        return electric.member(mover, InlineMachines.electricPower(shaft), tick);
    }

    /**
     * The first of {@code members}' pumps that has no driver at all, by name ({@code reflux pump}, {@code pumparound N pump}),
     * or null: a casing whose train has no driver (W5, step 4). An in-line pump carries its own motor.
     */
    public String undriven(ColumnUnit.Members members) {
        for (long p : members.reflux()) if (undriven(p)) return "reflux pump";
        for (int j = 0; j < members.pumparounds().size(); j++)
            for (long p : members.pumparound(j)) if (undriven(p)) return "pumparound " + (j + 1) + " pump";
        return null;
    }

    private boolean undriven(long mover) {
        return shafts.unitOf(mover) && shafts.driverOf(mover) == null;
    }
}
