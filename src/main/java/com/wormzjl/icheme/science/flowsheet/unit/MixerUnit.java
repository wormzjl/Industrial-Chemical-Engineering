package com.wormzjl.icheme.science.flowsheet.unit;

import com.wormzjl.icheme.science.flowsheet.Calculation;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.Note;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.Port;
import com.wormzjl.icheme.science.flowsheet.UnitModel;
import com.wormzjl.icheme.science.flowsheet.WiredUnit;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;

import java.util.ArrayList;
import java.util.List;

/**
 * A mixer (batch 2026-11-02-process-simulator, plan section 3.2): adds amounts and enthalpy and takes the lowest inlet pressure,
 * with a warning if they differ, because no pressure network pushes the higher inlet back.
 */
public final class MixerUnit implements UnitModel {
    private static final double SAME_PRESSURE = 1e-3;

    @Override public List<Port> ports(Params params) {
        var ports = new ArrayList<Port>();
        for (int i = 1; i <= params.integer("inlets", 2); i++) ports.add(Port.optionalIn("in/" + i));
        ports.add(Port.out("out"));
        return ports;
    }

    @Override public List<Calculation> calculations(WiredUnit u) {
        var reads = new ArrayList<String>();
        for (var port : ports(u.params())) if (port.direction() == Port.Direction.IN && u.wire(port.id()) != null) reads.add(u.wire(port.id()));
        return List.of(u.calculation(reads, List.of(u.out("out")), (board, report) -> {
            int components = u.thermo().axis().size();
            double[] amounts = new double[components];
            double enthalpy = 0, weighted = 0, total = 0, lowest = Double.POSITIVE_INFINITY, highest = 0;
            var solids = SolidInventory.EMPTY;
            for (var read : reads) {
                var in = board.stream(read);
                if (in.empty()) continue;
                for (int i = 0; i < components; i++) amounts[i] += in.amountsView()[i];
                enthalpy += in.enthalpy();
                solids = solids.plus(in.solids());
                weighted += in.temperature() * in.total();
                total += in.total();
                lowest = Math.min(lowest, in.pressure());
                highest = Math.max(highest, in.pressure());
            }
            if (total == 0) {
                u.write(board, report, "out", FlowStream.empty(components));
                return;
            }
            if (highest - lowest > SAME_PRESSURE * highest) report.note(Note.warning(u.id(), "mixer.pressures", String.valueOf(lowest), String.valueOf(highest)));
            u.write(board, report, "out", u.thermo().atEnthalpy(lowest, enthalpy, amounts, weighted / total, solids));
        }));
    }
}
