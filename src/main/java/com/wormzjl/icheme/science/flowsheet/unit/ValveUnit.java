package com.wormzjl.icheme.science.flowsheet.unit;

import com.wormzjl.icheme.science.flowsheet.Calculation;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFailure;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.Port;
import com.wormzjl.icheme.science.flowsheet.UnitModel;
import com.wormzjl.icheme.science.flowsheet.WiredUnit;

import java.util.List;

/** A control valve (batch 2026-11-02-process-simulator, plan section 3.2): isenthalpic to a set outlet or a set drop. */
public final class ValveUnit implements UnitModel {
    @Override public List<Port> ports(Params params) {
        return List.of(Port.in("in"), Port.out("out"));
    }

    @Override public List<Calculation> calculations(WiredUnit u) {
        return List.of(u.through("in", "out", (board, report) -> {
            var in = board.stream(u.wire("in"));
            double outlet = u.params().text("target", "outlet").equals("drop") ? in.pressure() - u.params().number("drop") : u.params().number("pressure");
            if (!(outlet > 0) || outlet > in.pressure()) throw new FlowsheetFailure("valve.outlet");
            var out = u.thermo().heated(in, 0, outlet);
            u.write(board, report, "out", out);
            report.reading("drop", in.pressure() - outlet);
            report.reading("cooled", in.temperature() - out.temperature());
            report.reading("vapour", u.thermo().vapourShare(out));
        }));
    }
}
