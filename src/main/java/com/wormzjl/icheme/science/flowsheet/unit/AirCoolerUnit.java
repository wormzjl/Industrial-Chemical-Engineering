package com.wormzjl.icheme.science.flowsheet.unit;

import com.wormzjl.icheme.science.flowsheet.Calculation;
import com.wormzjl.icheme.science.flowsheet.Note;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.Port;
import com.wormzjl.icheme.science.flowsheet.UnitModel;
import com.wormzjl.icheme.science.flowsheet.WiredUnit;

import java.util.List;

/**
 * An air cooler (batch 2026-11-02-process-simulator, plan section 3.2): target Outlet or Duty, the heat removed. The air is a
 * utility, off the sheet, and it cannot heat: set above the inlet, the stream passes and says so. Its drop is typed until it has
 * a size; then its bank's law gives it at the run's flow, at the stream's mean temperature (sizing plan section 3.11, D19).
 */
public final class AirCoolerUnit implements UnitModel {
    @Override public List<Port> ports(Params params) {
        return List.of(Port.in("in"), Port.out("out"));
    }

    @Override public List<Calculation> calculations(WiredUnit u) {
        return List.of(u.through("in", "out", (board, report) -> {
            var in = board.stream(u.wire("in"));
            boolean duty = u.params().text("target", "outlet").equals("duty");
            var out = u.leaving("drop", in, u.params().number("drop", 0), pressure -> duty ? u.thermo().heated(in, -u.params().number("duty"), pressure)
                    : u.thermo().atTemperature(u.params().number("temperature"), pressure, in.amounts(), in.solids()), report);
            double removed = in.enthalpy() - out.enthalpy();
            if (removed < 0) {
                report.note(Note.warning(u.id(), "cooler.heats"));
                out = u.thermo().heated(in, 0, out.pressure());
                removed = 0;
            }
            u.write(board, report, "out", out);
            report.duty(-removed);
            report.reading("duty", removed);
        }));
    }
}
