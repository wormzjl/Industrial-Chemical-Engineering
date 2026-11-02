package com.wormzjl.icheme.science.flowsheet.unit;

import com.wormzjl.icheme.science.flowsheet.Board;
import com.wormzjl.icheme.science.flowsheet.Calculation;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFailure;
import com.wormzjl.icheme.science.flowsheet.Note;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.Port;
import com.wormzjl.icheme.science.flowsheet.Report;
import com.wormzjl.icheme.science.flowsheet.UnitModel;
import com.wormzjl.icheme.science.flowsheet.WiredUnit;

import java.util.List;

/**
 * A fired heater (batch 2026-11-02-process-simulator, plan sections 3.2 and 3.11): target Outlet or Duty, with a duty limit, for
 * the fuel stays off the sheet. Its coils in series are one pair. Set apart, the convection coil is a second pair with its own
 * target, so steam can superheat there, and what it takes counts against the limit first. A coil at the limit falls short of its
 * outlet, as a real one does at full fire, and one set below its inlet passes the stream, as with the fire out; each says so.
 * The drops are typed until it has a size.
 */
public final class FiredHeaterUnit implements UnitModel {
    private static final String CONVECTION_IN = "convection_in", CONVECTION_OUT = "convection_out", RADIANT_IN = "radiant_in", RADIANT_OUT = "radiant_out";

    private static boolean apart(Params params) {
        return params.text("coils", "series").equals("apart");
    }

    @Override public List<Port> ports(Params params) {
        if (apart(params)) return List.of(Port.optionalIn(CONVECTION_IN), Port.out(CONVECTION_OUT), Port.in(RADIANT_IN), Port.out(RADIANT_OUT));
        return List.of(Port.in("in"), Port.out("out"));
    }

    @Override public List<Calculation> calculations(WiredUnit u) {
        var p = u.params();
        double limit = p.number("limit", Double.POSITIVE_INFINITY);
        if (!apart(p)) {
            return List.of(u.through("in", "out", (board, report) -> {
                var in = board.stream(u.wire("in"));
                var out = fire(u, report, in, p.text("target", "outlet"), "temperature", "duty", limit, p.number("radiant_drop", 0) + p.number("convection_drop", 0));
                u.write(board, report, "out", out);
                report.reading("duty", out.enthalpy() - in.enthalpy());
                report.reading("vapour", u.thermo().vapourShare(out));
            }));
        }
        if (u.wire(CONVECTION_IN) == null) {
            if (u.wire(CONVECTION_OUT) != null) throw new FlowsheetFailure("unit.free_inlet", CONVECTION_IN);
            return List.of(u.through(RADIANT_IN, RADIANT_OUT, (board, report) -> radiant(u, board, report, 0, limit)));
        }
        String taken = u.part("taken");
        var convection = new Calculation(u.part("convection"), u.id(), List.of(u.wire(CONVECTION_IN)), List.of(u.out(CONVECTION_OUT)), List.of(), List.of(taken), Calculation.Kind.STEP, (board, report) -> {
            var in = board.stream(u.wire(CONVECTION_IN));
            var out = in.empty() ? in : fire(u, report, in, p.text("convection_target", "outlet"), "convection_temperature", "convection_duty", limit, p.number("convection_drop", 0));
            u.write(board, report, CONVECTION_OUT, out);
            board.link(taken, in.empty() ? 0 : out.enthalpy() - in.enthalpy());
            report.reading("duty", in.empty() ? 0 : out.enthalpy() - in.enthalpy());
        });
        var radiant = new Calculation(u.part("radiant"), u.id(), List.of(u.wire(RADIANT_IN)), List.of(u.out(RADIANT_OUT)), List.of(taken), List.of(), Calculation.Kind.STEP, (board, report) -> {
            var in = board.stream(u.wire(RADIANT_IN));
            if (in.empty()) board.stream(u.out(RADIANT_OUT), in);
            else radiant(u, board, report, board.link(taken), limit);
        });
        return List.of(convection, radiant);
    }

    private static void radiant(WiredUnit u, Board board, Report report, double taken, double limit) {
        var in = board.stream(u.wire(RADIANT_IN));
        var out = fire(u, report, in, u.params().text("target", "outlet"), "temperature", "duty", limit - taken, u.params().number("radiant_drop", 0));
        u.write(board, report, RADIANT_OUT, out);
        report.reading("duty", out.enthalpy() - in.enthalpy());
        report.reading("vapour", u.thermo().vapourShare(out));
    }

    private static FlowStream fire(WiredUnit u, Report report, FlowStream in, String target, String temperature, String duty, double limit, double drop) {
        double pressure = in.pressure() - drop;
        if (!(pressure > 0)) throw new FlowsheetFailure("unit.pressure");
        var out = target.equals("duty") ? u.thermo().heated(in, u.params().number(duty), pressure) : u.thermo().atTemperature(u.params().number(temperature), pressure, in.amounts(), in.solids());
        double heat = out.enthalpy() - in.enthalpy();
        if (heat < 0 || heat > limit) {
            report.note(heat < 0 ? Note.warning(u.id(), "heater.cools") : Note.warning(u.id(), "heater.limit", String.valueOf(heat), String.valueOf(limit)));
            heat = heat < 0 ? 0 : limit;
            out = u.thermo().heated(in, heat, pressure);
        }
        report.duty(heat);
        return out;
    }
}
