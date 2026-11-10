package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.unit.VacuumPumpUnit;
import com.wormzjl.icheme.science.machine.VacuumPump;

import java.util.ArrayList;
import java.util.List;

/**
 * A vacuum pump (batch 2026-11-10-vacuum-pump, VACUUM_PUMP_PLAN.md sections 4 and 9): the tile's type, then the smallest model
 * whose capacity at the suction ({@link VacuumPump#suction}, the world's curve) holds the load at design flow, those that hold it
 * with {@link Limit#AUTO_SPARE} first (the owner's 20 % spare). The capacity reads as a displacement against the load.
 */
public final class VacuumPumpSizing {
    private VacuumPumpSizing() {
    }

    /** @param type its type; @param model its model's number, 1 to 4 (a two-stage ring 1 to 3) */
    public record Size(VacuumPump.Type type, int model) implements UnitSize {
        public Size {
            java.util.Objects.requireNonNull(type);
            if (model < 1 || model > VacuumPumpUnit.models(type)) throw new IllegalArgumentException("No vacuum pump model " + model + " of " + type);
        }

        /** What it takes at {@code pressure} Pa, m³/h. */
        double capacity(double pressure) {
            return UnitDesign.vacuumPump(shape()).suction(pressure, 1) * 3600;
        }

        @Override public EquipmentShape shape() {
            var shape = VacuumPumpUnit.shape(type, model);
            return UnitSize.unit(shape.kind(), shape.diameter(), (int) shape.tangentLength(), ProcessUnit.Bank.ONE);
        }
    }

    /**
     * A vacuum pump at design flow.
     *
     * @param flow    the load's volume at its suction, m³/h
     * @param suction the load's pressure, Pa
     * @param type    the tile's type
     */
    public record Load(double flow, double suction, VacuumPump.Type type) {
        public static Load of(FlowStream load, Params params, DesignBasis basis) {
            var type = VacuumPumpUnit.type(params);
            if (load.empty() || load.state() == null) return new Load(0, 1e5, type);
            return new Load(load.state().volume() * basis.designFlow() * 3600, load.pressure(), type);
        }

        boolean flows() {
            return flow > 0;
        }
    }

    /** The models of {@code type}, smallest first. */
    static List<Size> sizes(VacuumPump.Type type) {
        var out = new ArrayList<Size>();
        for (int model = 1; model <= VacuumPumpUnit.models(type); model++) out.add(new Size(type, model));
        return out;
    }

    /** Every limit of {@code load} read at {@code size}. */
    public static Rating rate(Load load, Size size) {
        if (!load.flows()) return Rating.NONE;
        var readings = UnitSize.readings(UnitSize.built(size));
        readings.add(Limit.COMPRESSOR_DISPLACEMENT.against(size.capacity(load.suction()), load.flow()));
        return new Rating(readings);
    }

    /** The smallest model of the tile's type that meets every limit, those that hold the load with its spare first. No load, no size. */
    public static Selection.Choice<Size> auto(Load load) {
        if (!load.flows()) return Selection.Choice.none();
        double held = Selection.spared(load.flow());
        return Selection.smallest(Selection.spareFirst(sizes(load.type()), size -> size.capacity(load.suction()), held), size -> rate(load, size));
    }
}
