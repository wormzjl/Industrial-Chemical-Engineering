package com.wormzjl.icheme.science.flowsheet.unit;

import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.flowsheet.Calculation;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Note;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.Passage;
import com.wormzjl.icheme.science.flowsheet.Port;
import com.wormzjl.icheme.science.flowsheet.UnitModel;
import com.wormzjl.icheme.science.flowsheet.WiredUnit;

import java.util.List;

/**
 * A cooler (batch 2026-11-02-process-simulator, plan section 3.2; {@code COOLER_MODEL_PLAN.md} section 3, C2): target Outlet or Duty,
 * the heat removed. Its type, Air or Water, is the sizing stage's: both coolants are utilities off the sheet that come in at 25 °C,
 * so the run is the same for both and a type changes no port. No coolant takes the stream below its own inlet, so a target below
 * 25 °C stops there and says so, as a heater stops at its limit; set above the inlet, the stream passes and says so. Its drop is
 * typed until it has a size; then its size's law gives it at the run's flow (sizing plan section 3.11, D19): an air cooler's bank at
 * the stream's mean temperature, a water cooler's shell at its inlet.
 */
public final class CoolerUnit implements UnitModel {
    /** The door's drop, and the passage a sized cooler's law answers for. */
    public static final String DROP = "drop";
    /** Where every cooling utility comes in, K: air and tower water alike. */
    public static final double COOLANT = HeatSides.TOWER_WATER_TEMPERATURE;

    @Override public List<Port> ports(Params params) {
        return List.of(Port.in("in"), Port.out("out"));
    }

    @Override public List<Passage> passages(Params params) {
        return List.of(Passage.THROUGH);
    }

    @Override public List<Calculation> calculations(WiredUnit u) {
        return List.of(u.through("in", "out", (board, report) -> {
            var in = board.stream(u.wire("in"));
            boolean duty = u.params().text("target", "outlet").equals("duty");
            var thermo = u.thermo();
            java.util.function.DoubleFunction<Cooled> asked = pressure -> duty ? removing(thermo, in, u.params().number("duty"), pressure)
                    : coolant(thermo, in, thermo.atTemperature(u.params().number("temperature"), pressure, in.amounts(), in.solids()));
            var out = u.leaving(DROP, in, u.params().number(DROP, 0), pressure -> asked.apply(pressure).stream(), report);
            if (asked.apply(out.pressure()).capped()) report.note(Note.warning(u.id(), "cooler.coolant"));
            double removed = in.enthalpy() - out.enthalpy();
            if (removed < 0) {
                report.note(Note.warning(u.id(), "cooler.heats"));
                out = thermo.heated(in, 0, out.pressure());
                removed = 0;
            }
            u.write(board, report, "out", out);
            report.duty(-removed);
            report.reading("duty", removed);
        }));
    }

    /** A stream as a coolant leaves it, and whether the coolant stopped it short of what was asked. */
    public record Cooled(FlowStream stream, boolean capped) {
    }

    /**
     * {@code out} as a coolant that comes in at {@link #COOLANT} can give it from {@code in}: where it would cool the stream below
     * the coolant, at the coolant, or at the inlet's own state where that is the coolant's or below. A stream that is heated, or not
     * cooled that far, stays {@code out} itself.
     */
    public static Cooled coolant(FlowThermo thermo, FlowStream in, FlowStream out) {
        if (in.empty() || !(out.enthalpy() < in.enthalpy()) || !(out.temperature() < COOLANT)) return new Cooled(out, false);
        return new Cooled(floor(thermo, in, out.pressure()), true);
    }

    /**
     * {@code in} with {@code removed} W taken out at {@code pressure}, as far as a coolant at {@link #COOLANT} can take it: a duty
     * past the coolant stops there, read on enthalpy first, so no state below it is asked of the model. A negative duty heats, as
     * asked.
     */
    public static Cooled removing(FlowThermo thermo, FlowStream in, double removed, double pressure) {
        if (in.empty() || !(removed > 0)) return new Cooled(thermo.heated(in, -removed, pressure), false);
        var floor = floor(thermo, in, pressure);
        return in.enthalpy() - removed < floor.enthalpy() ? new Cooled(floor, true) : new Cooled(thermo.heated(in, -removed, pressure), false);
    }

    /** The coldest a coolant leaves {@code in} at {@code pressure}: at the coolant, or at the inlet's own temperature where that is the coolant's or below. */
    private static FlowStream floor(FlowThermo thermo, FlowStream in, double pressure) {
        return in.temperature() > COOLANT ? thermo.atTemperature(COOLANT, pressure, in.amounts(), in.solids()) : thermo.heated(in, 0, pressure);
    }
}
