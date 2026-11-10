package com.wormzjl.icheme.science.flowsheet.unit;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.flowsheet.Calculation;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFailure;
import com.wormzjl.icheme.science.flowsheet.Note;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.Port;
import com.wormzjl.icheme.science.flowsheet.UnitModel;
import com.wormzjl.icheme.science.flowsheet.WiredUnit;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.InletPhase;
import com.wormzjl.icheme.science.machine.Motor;
import com.wormzjl.icheme.science.machine.VacuumPump;

import java.util.List;
import java.util.Locale;

/**
 * A vacuum pump (batch 2026-11-10-vacuum-pump, VACUUM_PUMP_PLAN.md section 9, owner V8 A): the world's curve. The suction is
 * the load; the discharge is set ({@code pressure}, default {@link #DISCHARGE_PRESSURE}). Its type ({@code pump}: a liquid ring
 * of one or two stages, or a dry screw) and its model ({@code model}: 0 for the smallest whose capacity at the suction holds the
 * load with the 20 % spare, or the model's number) give the world's law ({@link VacuumPump}): its capacity there, its shaft
 * power at its design speed, the heat its gas takes (a dry screw's, owner V9) and the motor it proposes
 * ({@link Motor#ratingFor} of its curve's largest shaft power). At or below blank-off it fails; over its capacity it warns.
 */
public final class VacuumPumpUnit implements UnitModel {
    /** The discharge a new tile sets, Pa: a vent header a little above the atmosphere. */
    public static final double DISCHARGE_PRESSURE = 1.05e5;
    /** The proposed motor's poles, as the movers' ({@link MoverUnit#POLES}). */
    private static final int POLES = MoverUnit.POLES;
    /** The spare the automatic model holds over the load. */
    public static final double SPARE = Motor.SPARE;

    @Override public List<Port> ports(Params params) {
        return List.of(Port.in("in"), Port.out("out"));
    }

    /** The type a tile's {@code pump} names; a one-stage liquid ring where it names none this version knows. */
    public static VacuumPump.Type type(Params params) {
        var text = params.text("pump", VacuumPump.Type.LIQUID_RING_ONE_STAGE.key());
        for (var type : VacuumPump.Type.values()) if (type.key().equals(text)) return type;
        return VacuumPump.Type.LIQUID_RING_ONE_STAGE;
    }

    /** The shape of model {@code model} (1 to 4) of {@code type}. */
    public static EquipmentShape shape(VacuumPump.Type type, int model) {
        var kind = type.liquidRing() ? EquipmentShape.Kind.LIQUID_RING_PUMP : EquipmentShape.Kind.DRY_SCREW_PUMP;
        return EquipmentShape.unit(kind, model, type == VacuumPump.Type.LIQUID_RING_TWO_STAGE ? 2 : 1, EquipmentShape.Heading.NORTH, 0);
    }

    /** The models of {@code type}, by number: a two-stage ring has no 5,000 m³/h model. */
    public static int models(VacuumPump.Type type) {
        return type == VacuumPump.Type.LIQUID_RING_TWO_STAGE ? 3 : 4;
    }

    /** The smallest model of {@code type} whose capacity at {@code pressure} Pa holds {@code load} m³/s with the spare; 0 where none does. */
    public static int smallest(VacuumPump.Type type, double pressure, double load) {
        for (int model = 1; model <= models(type); model++)
            if (UnitDesign.vacuumPump(shape(type, model)).suction(pressure, 1) >= SPARE * load) return model;
        return 0;
    }

    @Override public List<Calculation> calculations(WiredUnit u) {
        return List.of(u.through("in", "out", (board, report) -> {
            var in = board.stream(u.wire("in"));
            var thermo = u.thermo();
            var type = type(u.params());
            double suction = in.pressure(), outlet = u.params().number("pressure", DISCHARGE_PRESSURE), rise = outlet - suction;
            if (!(rise > 0)) throw new FlowsheetFailure("mover.rise");
            if (!(suction > type.blankOff())) throw new FlowsheetFailure("vacuum_pump.blank_off");
            double density = in.mass() / in.state().volume(), load = in.state().volume();
            var probe = new FlowControl.Compressor(1, 2);
            if (probe.refuses(InletPhase.bulk(thermo.model(), in.state()), false)) throw new FlowsheetFailure("compressor.phase");
            int asked = (int) Math.round(u.params().number("model", 0));
            int model = asked >= 1 && asked <= models(type) ? asked : smallest(type, suction, load);
            if (model == 0) {
                model = models(type);
                report.note(Note.warning(u.id(), "vacuum_pump.capacity"));
            }
            var shape = shape(type, model);
            var law = UnitDesign.vacuumPump(shape);
            double capacity = law.suction(suction, 1);
            if (asked >= 1 && capacity < load) report.note(Note.warning(u.id(), "vacuum_pump.capacity"));
            if (outlet > VacuumPump.MAXIMUM_DISCHARGE) report.note(Note.warning(u.id(), "vacuum_pump.discharge"));
            double k = thermo.model().heatCapacityRatio(in.state());
            double shaft = law.shaftPower(load, 1, density, suction, k), heat = law.fluidPower(in.mass(), rise, density, suction, k);
            u.write(board, report, "out", thermo.heated(in, heat, outlet));
            report.duty(shaft);
            report.reading("power", shaft);
            report.reading("capacity", capacity);
            report.reading("load", load);
            report.reading("model", model);
            report.reading("rise", rise);
            double rating = Motor.ratingFor(law.maximumShaftPower());
            if (!Double.isNaN(rating)) {
                report.reading("motor", rating);
                report.reading("motor_efficiency", Motor.efficiency(rating, POLES));
                report.reading("electric", Motor.electricPower(shaft, rating, POLES, false));
            }
        }));
    }

    /** A vacuum pump picked for a load: its type and model's number, 0 where none holds it. */
    public record Pick(VacuumPump.Type type, int model) {
        /** The model's law: the largest of its type's where none holds the load. */
        public VacuumPump law() {
            return UnitDesign.vacuumPump(shape(type, model == 0 ? models(type) : model));
        }
    }

    /**
     * The pump a column's {@code vacuum} puts on its receiver's vapour outlet for {@code load} m³/s at {@code pressure} Pa: a dry
     * screw's smallest model; a liquid ring's smallest single-stage model, or the two-stage one's where no single stage holds it
     * (a single stage loses its capacity towards its seal water's vapour pressure). Where none holds it, model 0 of the largest
     * type: the screw, or the single-stage ring above its blank-off (its 5,000 m³/h model), else the two-stage one.
     */
    public static Pick pick(com.wormzjl.icheme.science.equipment.ColumnComponents.Vacuum vacuum, double pressure, double load) {
        if (vacuum == com.wormzjl.icheme.science.equipment.ColumnComponents.Vacuum.DRY_SCREW)
            return new Pick(VacuumPump.Type.DRY_SCREW, smallest(VacuumPump.Type.DRY_SCREW, pressure, load));
        int one = pressure > VacuumPump.Type.LIQUID_RING_ONE_STAGE.blankOff() ? smallest(VacuumPump.Type.LIQUID_RING_ONE_STAGE, pressure, load) : 0;
        if (one > 0) return new Pick(VacuumPump.Type.LIQUID_RING_ONE_STAGE, one);
        int two = pressure > VacuumPump.Type.LIQUID_RING_TWO_STAGE.blankOff() ? smallest(VacuumPump.Type.LIQUID_RING_TWO_STAGE, pressure, load) : 0;
        if (two > 0 || pressure <= VacuumPump.Type.LIQUID_RING_ONE_STAGE.blankOff()) return new Pick(VacuumPump.Type.LIQUID_RING_TWO_STAGE, two);
        return new Pick(VacuumPump.Type.LIQUID_RING_ONE_STAGE, 0);
    }

    /** The model's name a reading {@code model} of {@code params}' type gives ({@code LR1-500}). */
    public static String name(Params params, int model) {
        return model < 1 ? "" : UnitDesign.vacuumPumpModel(shape(type(params), Math.min(model, models(type(params))))).toUpperCase(Locale.ROOT);
    }
}
