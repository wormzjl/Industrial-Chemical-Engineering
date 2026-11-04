package com.wormzjl.icheme.science.flowsheet.unit;

import com.wormzjl.icheme.science.flowsheet.Calculation;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.Passage;
import com.wormzjl.icheme.science.flowsheet.Port;
import com.wormzjl.icheme.science.flowsheet.UnitModel;
import com.wormzjl.icheme.science.flowsheet.WiredUnit;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;

import java.util.List;

/** An in-line filter (batch 2026-11-02-process-simulator, plan section 3.2): the solids leave the sheet as cake, the fluid goes on. */
public final class FilterUnit implements UnitModel {
    @Override public List<Port> ports(Params params) {
        return List.of(Port.in("in"), Port.out("out"));
    }

    @Override public List<Passage> passages(Params params) {
        return List.of(Passage.THROUGH);
    }

    @Override public List<Calculation> calculations(WiredUnit u) {
        return List.of(u.through("in", "out", (board, report) -> {
            var in = board.stream(u.wire("in"));
            var moments = in.solids().moments();
            double cake = moments.enthalpy(in.temperature(), in.pressure());
            report.leaves(moments.mass(), cake);
            report.reading("solids", moments.mass());
            u.write(board, report, "out", in.solids().empty() ? in : u.thermo().atEnthalpy(in.pressure(), in.enthalpy() - cake, in.amounts(), in.temperature(), SolidInventory.EMPTY));
        }));
    }
}
