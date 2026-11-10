package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.fluid.network.Chimney;

import java.util.ArrayList;
import java.util.List;

/**
 * A chimney (batch 2026-11-10-chimney, owner C2 A and C7): the exit diameter and the height on the constructor's grid. Auto takes
 * the smallest diameter whose mouth passes {@link Limit#AUTO_SPARE} over the design volume flow at the form's design velocity (the
 * owner's "always have enough excess"), its exit velocity at design flow in the window {@link Chimney#LOW_VELOCITY} to
 * {@link Chimney#HIGH_VELOCITY} and under the 100 m/s cap; the height good engineering practice gives the nearby structure
 * ({@link Chimney#gepHeight}), within the form's range at that diameter.
 */
public final class ChimneySizing {
    private ChimneySizing() {
    }

    /** @param kind the form's kind, {@code STEEL_STACK} or {@code BRICK_CHIMNEY}; {@code diameter} the exit's, m; {@code height} m */
    public record Size(EquipmentShape.Kind kind, double diameter, int height) implements UnitSize {
        public Size {
            if (kind == null || !kind.chimney() || !(diameter > 0) || !Double.isFinite(diameter) || height <= 0) throw new IllegalArgumentException("Invalid chimney size");
        }

        public Chimney.Form form() {
            return kind == EquipmentShape.Kind.BRICK_CHIMNEY ? Chimney.Form.BRICK : Chimney.Form.STEEL;
        }

        @Override public EquipmentShape shape() {
            return UnitSize.unit(kind, diameter, height, ProcessUnit.Bank.ONE);
        }
    }

    /** The kind of {@code form}. */
    public static EquipmentShape.Kind kind(Chimney.Form form) {
        return form == Chimney.Form.BRICK ? EquipmentShape.Kind.BRICK_CHIMNEY : EquipmentShape.Kind.STEEL_STACK;
    }

    /**
     * A chimney at design flow.
     *
     * @param form       the door's form
     * @param volumeFlow the gas at the exit at design flow, m³/s
     * @param nearby     the nearby structure's height, m
     */
    public record Load(Chimney.Form form, double volumeFlow, double nearby) {
        /** The chimney of the run's {@code volumeFlow} m³/s at the exit, beside a structure {@code nearby} m high. */
        public static Load of(Chimney.Form form, double volumeFlow, double nearby, DesignBasis basis) {
            return new Load(form, volumeFlow > 0 ? volumeFlow * basis.designFlow() : 0, Math.max(0, nearby));
        }

        boolean flows() {
            return volumeFlow > 0;
        }

        /** What a mouth of {@code size} passes at the form's design velocity, m³/s. */
        double passes(Size size) {
            return form.designVelocity * Math.PI * size.diameter() * size.diameter() / 4;
        }
    }

    /** Every limit of {@code load} read at {@code size}. */
    public static Rating rate(Load load, Size size) {
        if (!load.flows()) return Rating.NONE;
        var readings = UnitSize.readings(UnitSize.built(size));
        double velocity = Chimney.exitVelocity(load.volumeFlow(), size.diameter());
        readings.add(Limit.CHIMNEY_CAP.read(velocity));
        readings.add(Limit.CHIMNEY_SLOW.read(velocity));
        readings.add(Limit.CHIMNEY_FAST.read(velocity));
        readings.add(Limit.CHIMNEY_HEIGHT.against(size.height(), Chimney.gepHeight(load.nearby())));
        return new Rating(readings);
    }

    /** The sizes of {@code load}'s form, smallest diameter first, each at the height its nearby structure asks within its range. */
    static List<Size> sizes(Load load) {
        var kind = kind(load.form());
        var out = new ArrayList<Size>();
        for (double d : UnitSize.grid(kind)) {
            int height = Math.max(load.form().minHeight, Math.min(load.form().maxHeight(d), Chimney.gepHeight(load.nearby())));
            out.add(new Size(kind, d, height));
        }
        return out;
    }

    /** The smallest chimney that meets every limit, those that pass the load with its spare first. No load, no size. */
    public static Selection.Choice<Size> auto(Load load) {
        if (!load.flows()) return Selection.Choice.none();
        return Selection.smallest(Selection.spareFirst(sizes(load), load::passes, Selection.spared(load.volumeFlow())), size -> rate(load, size));
    }
}
