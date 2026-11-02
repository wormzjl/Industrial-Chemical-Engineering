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
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;

import java.util.ArrayList;
import java.util.List;

/**
 * A heat exchanger or a kettle reboiler (batch 2026-11-02-process-simulator, plan section 3.2): target Outlet, Duty or Area. An
 * outlet or a duty names a {@code side}, and that side solves alone, so the graph splits and a loop through the other side is
 * no loop of this unit's. The duty reaches the other side as a link. A free pair's inlet leaves the heat to a utility. Area needs
 * both inlets, so it is one calculation. A rating reads both inlets afterwards, and says the surface the duty needs and whether
 * the heat crosses. A kettle's shell boils its feed and has a gas and a liquid outlet. The drops are typed: a sized unit's
 * come from its flow (sizing plan section 3.11).
 *
 * <p>Sign: {@code duty} and the link are the heat into the named side.
 */
public final class ExchangerUnit implements UnitModel {
    private static final String TUBE_IN = "tube_in", TUBE_OUT = "tube_out", TUBE = "tube", SHELL = "shell";
    /** Beyond each end of a curve, so the ends of the span are never its last knots (K). */
    private static final double SPAN = 1;
    private final boolean kettle;

    public ExchangerUnit(boolean kettle) {
        this.kettle = kettle;
    }

    private String shellIn() {
        return kettle ? "feed" : "shell_in";
    }

    private List<String> shellOutlets() {
        return kettle ? List.of("gas", "liquid") : List.of("shell_out");
    }

    @Override public List<Port> ports(Params params) {
        var ports = new ArrayList<Port>();
        ports.add(Port.optionalIn(TUBE_IN));
        ports.add(Port.out(TUBE_OUT));
        ports.add(kettle ? Port.in(shellIn()) : Port.optionalIn(shellIn()));
        for (var port : shellOutlets()) ports.add(Port.out(port));
        return ports;
    }

    @Override public List<Calculation> calculations(WiredUnit u) {
        String tube = u.wire(TUBE_IN), shell = u.wire(shellIn());
        if (tube == null && u.wire(TUBE_OUT) != null) throw new FlowsheetFailure("unit.free_inlet", TUBE_IN);
        if (shell == null) for (var port : shellOutlets()) if (u.wire(port) != null) throw new FlowsheetFailure("unit.free_inlet", shellIn());
        if (tube == null && shell == null) throw new FlowsheetFailure("unit.free_inlet", shellIn());
        String target = u.params().text("target", "outlet");
        if (target.equals("area")) {
            if (tube == null || shell == null) throw new FlowsheetFailure("exchanger.area_pair");
            return List.of(area(u, tube, shell));
        }
        if (!target.equals("outlet") && !target.equals("duty")) throw new FlowsheetFailure("unit.setting", "target");
        boolean onTube = u.params().text("side", SHELL).equals(TUBE);
        if ((onTube ? tube : shell) == null) throw new FlowsheetFailure("exchanger.set_side");
        String link = u.part("heat");
        var calculations = new ArrayList<Calculation>();
        boolean both = tube != null && shell != null;
        calculations.add(new Calculation(u.part(onTube ? TUBE : SHELL), u.id(), List.of(onTube ? tube : shell), outlets(u, onTube), List.of(), both ? List.of(link) : List.of(), Calculation.Kind.STEP, (board, report) -> {
            var in = board.stream(onTube ? tube : shell);
            var out = set(u, target, onTube, in);
            write(u, board, report, onTube, out);
            double heat = in.empty() ? 0 : out.enthalpy() - in.enthalpy();
            if (both) board.link(link, heat);
            else report.duty(heat);
            report.reading("duty", Math.abs(heat));
        }));
        if (both) {
            calculations.add(new Calculation(u.part(onTube ? SHELL : TUBE), u.id(), List.of(onTube ? shell : tube), outlets(u, !onTube), List.of(link), List.of(), Calculation.Kind.STEP, (board, report) -> {
                var in = board.stream(onTube ? shell : tube);
                write(u, board, report, !onTube, in.empty() ? in : u.thermo().heated(in, -board.link(link), pressure(u, !onTube, in)));
            }));
            calculations.add(rating(u, tube, shell, onTube, link));
        }
        return calculations;
    }

    private List<String> outlets(WiredUnit u, boolean tube) {
        return tube ? List.of(u.out(TUBE_OUT)) : shellOutlets().stream().map(u::out).toList();
    }

    private static double pressure(WiredUnit u, boolean tube, FlowStream in) {
        double pressure = in.pressure() - u.params().number(tube ? "tube_drop" : "shell_drop", 0);
        if (!(pressure > 0)) throw new FlowsheetFailure("unit.pressure");
        return pressure;
    }

    private static FlowStream set(WiredUnit u, String target, boolean tube, FlowStream in) {
        if (in.empty()) return in;
        double pressure = pressure(u, tube, in);
        return target.equals("outlet") ? u.thermo().atTemperature(u.params().number("temperature"), pressure, in.amounts(), in.solids()) : u.thermo().heated(in, u.params().number("duty"), pressure);
    }

    private void write(WiredUnit u, Board board, Report report, boolean tube, FlowStream out) {
        if (tube) u.write(board, report, TUBE_OUT, out);
        else if (!kettle) u.write(board, report, "shell_out", out);
        else if (out.empty()) {
            u.write(board, report, "gas", out);
            u.write(board, report, "liquid", out);
        } else {
            u.write(board, report, "gas", u.thermo().phase(out, FluidThermodynamics.GAS));
            u.write(board, report, "liquid", u.thermo().liquid(out));
        }
    }

    private Calculation area(WiredUnit u, String tube, String shell) {
        var writes = new ArrayList<>(outlets(u, true));
        writes.addAll(outlets(u, false));
        return u.calculation(List.of(tube, shell), writes, (board, report) -> {
            var t = board.stream(tube);
            var s = board.stream(shell);
            double into = t.empty() || s.empty() || t.temperature() == s.temperature() ? 0 : passed(u, t, s, u.params().number("area"), report);
            write(u, board, report, true, t.empty() ? t : u.thermo().heated(t, into, pressure(u, true, t)));
            write(u, board, report, false, s.empty() ? s : u.thermo().heated(s, -into, pressure(u, false, s)));
        });
    }

    /** The heat into the tube side that {@code area} m² passes between two inlets. */
    private static double passed(WiredUnit u, FlowStream tube, FlowStream shell, double area, Report report) {
        var hot = tube.temperature() > shell.temperature() ? tube : shell;
        var cold = hot == tube ? shell : tube;
        var hotSide = side(u, hot, cold, hot);
        var coldSide = side(u, hot, cold, cold);
        double duty = ExchangerRating.duty(hotSide, coldSide, area, hot.temperature(), cold.temperature());
        report.reading("duty", duty);
        report.reading("approach", ExchangerRating.rate(hotSide, coldSide, duty).approach());
        return hot == tube ? -duty : duty;
    }

    /** One side of the rating, as its fluid: a heat curve holds no solids. */
    private static ExchangerRating.Side side(WiredUnit u, FlowStream hot, FlowStream cold, FlowStream of) {
        var solids = of.solids().moments();
        double fluid = of.mass() - solids.mass();
        return new ExchangerRating.Side(u.thermo().curve(of, of.pressure(), cold.temperature() - SPAN, hot.temperature() + SPAN), fluid, (of.enthalpy() - solids.enthalpy(of.temperature(), of.pressure())) / fluid);
    }

    /** What the duty asks of the surface. It runs after both sides, reads the inlets and changes nothing. */
    private static Calculation rating(WiredUnit u, String tube, String shell, boolean onTube, String link) {
        return new Calculation(u.part("rating"), u.id(), List.of(tube, shell), List.of(), List.of(link), List.of(), Calculation.Kind.CHECK, (board, report) -> {
            var t = board.stream(tube);
            var s = board.stream(shell);
            double into = onTube ? board.link(link) : -board.link(link);
            if (t.empty() || s.empty() || into == 0) return;
            var hot = into > 0 ? s : t;
            var cold = hot == s ? t : s;
            if (!(hot.temperature() > cold.temperature())) {
                report.note(Note.warning(u.id(), "exchanger.heat_cross"));
                return;
            }
            var result = ExchangerRating.rate(side(u, hot, cold, hot), side(u, hot, cold, cold), Math.abs(into));
            report.reading("approach", result.approach());
            if (Double.isFinite(result.area())) report.reading("area_needed", result.area());
            else report.note(Note.warning(u.id(), "exchanger.heat_cross"));
        });
    }
}
