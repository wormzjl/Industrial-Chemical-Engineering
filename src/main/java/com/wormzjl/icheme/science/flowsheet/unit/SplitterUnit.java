package com.wormzjl.icheme.science.flowsheet.unit;

import com.wormzjl.icheme.science.flowsheet.Calculation;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFailure;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.Port;
import com.wormzjl.icheme.science.flowsheet.UnitModel;
import com.wormzjl.icheme.science.flowsheet.WiredUnit;

import java.util.ArrayList;
import java.util.List;

/**
 * A splitter (batch 2026-11-02-process-simulator, plan section 3.2): every outlet has the inlet's state. Each row of
 * {@code outlets} has its own {@code id}, which names its port, and a {@code share} (0 to 1) or, in Flows, a {@code flow}
 * (mol/s); the one row with neither takes the rest.
 */
public final class SplitterUnit implements UnitModel {
    /** Shares that add up to one by hand can overrun it by this much. */
    private static final double ROUNDING = 1e-12;

    private static List<Params> rows(Params params) {
        var rows = params.rows("outlets");
        if (!rows.isEmpty()) return rows;
        var defaults = new ArrayList<Params>();
        for (int i = 1; i <= 2; i++) {
            var row = Params.none();
            row.json().addProperty("id", String.valueOf(i));
            defaults.add(row);
        }
        return defaults;
    }

    private static String port(Params row) {
        return "out/" + row.text("id", "");
    }

    @Override public List<Port> ports(Params params) {
        var ports = new ArrayList<Port>();
        ports.add(Port.in("in"));
        for (var row : rows(params)) ports.add(Port.out(port(row)));
        return ports;
    }

    @Override public List<Calculation> calculations(WiredUnit u) {
        var rows = rows(u.params());
        var writes = rows.stream().map(row -> u.out(port(row))).toList();
        return List.of(u.calculation(List.of(u.wire("in")), writes, (board, report) -> {
            var in = board.stream(u.wire("in"));
            if (in.empty()) {
                for (var row : rows) u.write(board, report, port(row), FlowStream.empty(u.thermo().axis().size()));
                return;
            }
            boolean flows = u.params().text("mode", "shares").equals("flows");
            double[] shares = new double[rows.size()];
            double sum = 0;
            int rest = -1;
            for (int i = 0; i < shares.length; i++) {
                var row = rows.get(i);
                String key = flows ? "flow" : "share";
                if (row.json().has(key)) {
                    shares[i] = flows ? row.number(key) / in.total() : row.number(key);
                    if (!(shares[i] >= 0)) throw new FlowsheetFailure("splitter.value", row.text("id", ""));
                    sum += shares[i];
                } else if (rest >= 0) throw new FlowsheetFailure("splitter.rest");
                else rest = i;
            }
            if (rest < 0) throw new FlowsheetFailure("splitter.rest");
            if (sum > 1 + ROUNDING) throw new FlowsheetFailure("splitter.over");
            shares[rest] = Math.max(0, 1 - sum);
            for (int i = 0; i < shares.length; i++) {
                double share = shares[i];
                double[] amounts = in.amounts();
                for (int k = 0; k < amounts.length; k++) amounts[k] *= share;
                u.write(board, report, port(rows.get(i)), share == 0 ? FlowStream.empty(amounts.length) : u.thermo().atEnthalpy(in.pressure(), in.enthalpy() * share, amounts, in.temperature(), in.solids().scale(share)));
            }
            report.reading("sum", sum);
            report.reading("rest", shares[rest]);
        }));
    }
}
