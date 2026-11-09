package com.wormzjl.icheme.science.flowsheet.unit;

import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.flowsheet.Board;
import com.wormzjl.icheme.science.flowsheet.Calculation;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.Note;
import com.wormzjl.icheme.science.flowsheet.Report;
import com.wormzjl.icheme.science.flowsheet.WiredUnit;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.ToDoubleFunction;

import static com.wormzjl.icheme.science.flowsheet.unit.ColumnUnit.*;

/**
 * The sheet sides of the column's heat pairs ({@link ColumnUnit}): the condenser's, the kettle's and each pumparound cooler's
 * utility side, a regulated side's feed and its rules, and each pair's check once the sheet stands.
 */
final class ColumnSides {
    /**
     * A regulated side's flow is found again while its outlet's pressure moves by more than this (Pa), at most this often; its
     * condensate stands this far under its saturation (K), a liquid the flash reads as one.
     */
    private static final double SAME_PRESSURE = 100, SUBCOOLED = 1e-3;
    private static final int SETTLING = 5;

    private ColumnSides() {
    }

    /**
     * The utility side of a heat pair: {@code heat} W into it, from the door, or from {@code link} once the column has said. The
     * column's duty carries the column's sign, so this side takes its negative. It loses the door's {@code drop} (Pa), or once its
     * member has a size, the member's tubes' law at the run's flow (D19).
     */
    static Calculation side(WiredUnit u, String name, String in, String out, String passage, double drop, @Nullable String link, @Nullable Double heat) {
        return new Calculation(u.part(name), u.id(), List.of(u.wire(in)), List.of(u.out(out)), link == null ? List.of() : List.of(link), List.of(), Calculation.Kind.STEP, (board, report) -> {
            var stream = board.stream(u.wire(in));
            if (stream.empty()) {
                u.write(board, report, out, stream);
                return;
            }
            double pressure = WiredUnit.after(stream, u.drop(passage, stream, drop, report));
            double watts = link == null ? heat : board.link(link);
            u.write(board, report, out, u.thermo().heated(stream, watts, pressure));
            report.reading("duty", watts);
        });
    }

    /**
     * What a regulated side asks of its feed ({@code COOLER_MODEL_PLAN.md} section 8): the flow, as a share of the typed one, that
     * carries {@code watts} into it with the side's outlet at {@code pressure}; NaN keeps the typed flow.
     */
    interface Rule {
        double share(Board board, Report report, FlowStream typed, double watts, double pressure);
    }

    /**
     * The feed of a heat side that sets its flow ({@code COOLER_MODEL_PLAN.md} section 8): the sheet's feed of water alone on
     * {@code in}, which this calculation writes in place of a source, at its typed temperature, pressure and makeup and at the flow
     * {@code rule} asks for {@code watts}; the side then takes it as any inlet. The typed flow is the first guess, and stays where the
     * rule cannot set one. A sized passage's drop moves with the flow, so the flow is found again at each drop until the side's outlet
     * pressure settles.
     */
    static Calculation utility(WiredUnit u, String name, String in, String passage, double drop, List<String> reads, List<String> links, ToDoubleFunction<Board> watts, Rule rule) {
        var feed = u.utilities().feeds().get(in);
        String wire = u.wire(in);
        return new Calculation(u.part(name + " utility"), u.id(), reads, List.of(wire), links, List.of(), Calculation.Kind.STEP, (board, report) -> {
            var thermo = u.thermo();
            var typed = com.wormzjl.icheme.science.flowsheet.FlowsheetCompiler.typed(feed, thermo);
            double heat = watts.applyAsDouble(board);
            var scratch = new Report();
            double pressure = WiredUnit.after(typed, u.drop(passage, typed, drop, scratch));
            double share = rule.share(board, scratch, typed, heat, pressure);
            for (int tries = 0; tries < SETTLING && share > 0; tries++) {
                double next = WiredUnit.after(typed, u.drop(passage, scaled(thermo, typed, share), drop, scratch));
                if (Math.abs(next - pressure) <= SAME_PRESSURE) break;
                pressure = next;
                share = rule.share(board, scratch, typed, heat, pressure);
            }
            share = rule.share(board, report, typed, heat, pressure);
            var stream = Double.isNaN(share) ? typed : scaled(thermo, typed, share);
            board.stream(wire, stream);
            report.enters(stream.mass(), stream.enthalpy());
            if (!Double.isNaN(share)) report.note(Note.info(wire, "flow.set_by", u.id()));
        });
    }

    /**
     * Cooling water's rule: the flow that takes the heat from its inlet to {@link HeatSides#coolingWaterOutlet}, 5 K under the
     * process cold end {@code coldEnd} (K) at most. Water that comes in there or above cannot hold it, and keeps its typed flow with a
     * warning; a feed that holds vapour is no cooling water, and keeps its own.
     */
    static Rule cooling(WiredUnit u, String port, ToDoubleFunction<Board> coldEnd) {
        return (board, report, typed, watts, pressure) -> {
            var thermo = u.thermo();
            if (thermo.vapourShare(typed) > TRACE || watts < 0) return Double.NaN;
            if (watts == 0) return 0;
            double outlet = HeatSides.coolingWaterOutlet(typed.temperature(), coldEnd.applyAsDouble(board));
            if (!(outlet > typed.temperature())) {
                report.note(Note.warning(u.id(), "column.water_hot", port));
                return Double.NaN;
            }
            return watts / (thermo.atTemperature(outlet, pressure, typed.amountsView(), typed.solids()).enthalpy() - typed.enthalpy());
        };
    }

    /**
     * Steam's rule: the flow whose condensing gives the kettle's duty, the condensate leaving saturated at the side's outlet pressure.
     * Steam no hotter than the bottoms cannot heat them, and keeps its typed flow, which the reboiler's check reads as {@code
     * column.reboiler_cold}; steam hotter than them that condenses no hotter can give them its superheat alone, and keeps its typed
     * flow with {@code column.steam_low}. A feed of liquid water is no steam, and keeps its own.
     */
    static Rule steam(WiredUnit u, String bottomsTemperature) {
        return (board, report, typed, watts, pressure) -> {
            var thermo = u.thermo();
            if (!(thermo.vapourShare(typed) > TRACE) || watts > 0) return Double.NaN;
            if (watts == 0) return 0;
            double saturation = EjectorUnit.saturation(thermo, pressure);
            double bottoms = board.link(bottomsTemperature);
            if (typed.temperature() <= bottoms) return Double.NaN;
            if (saturation <= bottoms) {
                report.note(Note.warning(u.id(), "column.steam_low"));
                return Double.NaN;
            }
            double given = typed.enthalpy() - thermo.atTemperature(saturation - SUBCOOLED, pressure, typed.amountsView(), typed.solids()).enthalpy();
            return given > 0 ? -watts / given : Double.NaN;
        };
    }

    /** Stripping steam's rule (U1b): its row's rate, which the inlet valve holds; a feed of liquid water is no steam, and keeps its own. */
    static Rule rate(WiredUnit u, ColumnDoor.Steam row) {
        return (board, report, typed, watts, pressure) -> u.thermo().vapourShare(typed) > TRACE ? row.flow() / typed.total() : Double.NaN;
    }

    /** {@code typed} at {@code share} of its flow, in the same state; none at a share of 0. */
    static FlowStream scaled(com.wormzjl.icheme.science.flowsheet.FlowThermo thermo, FlowStream typed, double share) {
        if (share == 0) return FlowStream.empty(typed.amountsView().length);
        double[] amounts = typed.amounts();
        for (int i = 0; i < amounts.length; i++) amounts[i] *= share;
        return thermo.atTemperature(typed.temperature(), typed.pressure(), amounts, typed.solids());
    }

    static Calculation check(WiredUnit u, String name, String read, @Nullable String other, Calculation.Law law) {
        return new Calculation(u.part(name), u.id(), other == null ? List.of(read) : List.of(read, other), List.of(), List.of(), List.of(), Calculation.Kind.CHECK, law);
    }
}
