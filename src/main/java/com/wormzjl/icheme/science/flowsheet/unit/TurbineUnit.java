package com.wormzjl.icheme.science.flowsheet.unit;

import com.wormzjl.icheme.science.flowsheet.Calculation;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFailure;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.Port;
import com.wormzjl.icheme.science.flowsheet.UnitModel;
import com.wormzjl.icheme.science.flowsheet.WiredUnit;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.machine.SteamTurbine;

import java.util.List;

/**
 * A steam turbine (batch 2026-11-03-rotating-machinery, W8 form A, owner decision D42): steam in, exhaust out at the door's set
 * pressure, shaft power out of the sheet. The world's closure ({@link FlowControl.Turbine#shaftPower}): {@code ṁ η_is Δh_is}, the
 * isentropic drop from the inlet's real {@code P/ρ} on its gas's heat capacity ratio, and the exhaust at {@code h_in − W/ṁ}, whose
 * flash gives its temperature and wetness. The efficiency is the {@link SteamTurbine#typicalEfficiency} of a turbine rated at the
 * tile's own shaft power ({@link #designed}). An exhaust at or above the inlet does no work in the world, where the line passes
 * as an open one; a sheet cannot run steam uphill, so the tile refuses it.
 */
public final class TurbineUnit implements UnitModel {
    /** Steam needed per kWh is {@code 3.6e6 / (η Δh_is)} kg. */
    private static final double JOULES_PER_KWH = 3.6e6;
    /** The design iteration stops when the power moves less than this share. */
    private static final double SETTLED = 1e-12;
    private static final int MOST_PASSES = 60;
    /** A new tile's exhaust, Pa: the low-pressure steam header (3.5 bar(a)). */
    public static final double EXHAUST_PRESSURE = 3.5e5;

    @Override public List<Port> ports(Params params) {
        return List.of(Port.in("in"), Port.out("out"));
    }

    @Override public List<Calculation> calculations(WiredUnit u) {
        return List.of(u.through("in", "out", (board, report) -> {
            var in = board.stream(u.wire("in"));
            var thermo = u.thermo();
            double exhaust = u.params().number("exhaust");
            if (!(exhaust > 0) || !(exhaust < in.pressure())) throw new FlowsheetFailure("turbine.drop");
            double density = in.mass() / in.state().volume(), k = thermo.model().heatCapacityRatio(in.state());
            // The world's closure at η 1 gives the isentropic power, ṁ Δh_is, its k ≤ 1 guard included.
            double isentropic = new FlowControl.Turbine(new SteamTurbine(1, 1)).shaftPower(in.mass(), density, in.pressure(), exhaust, k);
            var design = designed(isentropic);
            double power = design.efficiency() * isentropic;
            u.write(board, report, "out", thermo.heated(in, -power, exhaust));
            report.duty(-power);
            report.reading("power", power);
            report.reading("efficiency", design.efficiency());
            report.reading("drop", in.mass() > 0 ? isentropic / in.mass() : 0);
            if (power > 0) report.reading("steam_rate", in.mass() * JOULES_PER_KWH / power);
        }));
    }

    /**
     * The turbine a design proposes for isentropic power {@code isentropic} (W, {@code ṁ Δh_is}): rated at the shaft power it
     * gives, {@code P = η(P) ṁ Δh_is} with {@code η = typicalEfficiency(P)}. A fixed-point pass from the single stage's power: the
     * efficiency runs in log power, so each pass moves the power by at most {@code 0.31/ln 10 ≈ 0.135} of the last move, and the
     * passes settle to the last bit in about fifteen.
     */
    public static SteamTurbine designed(double isentropic) {
        if (!(isentropic > 0)) return SteamTurbine.rated(1);
        double power = SteamTurbine.typicalEfficiency(isentropic) * isentropic;
        for (int pass = 0; pass < MOST_PASSES; pass++) {
            double next = SteamTurbine.typicalEfficiency(power) * isentropic;
            boolean settled = Math.abs(next - power) <= SETTLED * power;
            power = next;
            if (settled) break;
        }
        return SteamTurbine.rated(power);
    }
}
