package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A steam ejector set (batch 2026-11-02-process-simulator, main plan W10, W10b, {@code SIZING_PLAN.md} section 3.12): the suction
 * bore that passes the load within the Vacuum vapour row of section 3.3, on the constructor's grid. A set has no stage limits; a
 * wired Motive port reads its steam against the law's need. Auto first tries the bores that pass the load with
 * {@link Limit#AUTO_SPARE} (the owner's "always have enough excess", 2026-11-03).
 */
public final class EjectorSizing {
    private static final List<Size> SIZES = sizes();

    private EjectorSizing() {
    }

    private static List<Size> sizes() {
        var sizes = new ArrayList<Size>();
        for (double suction : UnitSize.grid(EquipmentShape.Kind.EJECTOR)) sizes.add(new Size(suction));
        return List.copyOf(sizes);
    }

    /** @param suction the suction's bore, m: the world's ejector is sized by it */
    public record Size(double suction) implements UnitSize {
        public Size {
            if (!(suction > 0) || !Double.isFinite(suction)) throw new IllegalArgumentException("Invalid ejector suction");
        }

        /** What its suction passes at {@code speed} m/s, m³/h. */
        double passes(double speed) {
            return Math.PI / 4 * suction * suction * speed * 3600;
        }

        @Override public EquipmentShape shape() {
            return UnitSize.unit(EquipmentShape.Kind.EJECTOR, suction, 1, ProcessUnit.Bank.ONE);
        }
    }

    /**
     * An ejector at design flow.
     *
     * @param flow        the load's volume at its suction, m³/h
     * @param suction     the load's pressure, bara
     * @param need        the motive steam the law needs, kg/h
     * @param motive      what a wired Motive port brings, kg/h; null for a free port, a utility that gives the need
     */
    public record Load(double flow, double suction, double need, @Nullable Double motive) {
        /** An ejector with no load: no size, no lamp. */
        public static final Load NONE = new Load(0, 1, 0, null);

        /**
         * The ejector on {@code load}, whose law needs {@code need} kg/s of steam, with what {@code wired} brings (kg/s), null where
         * its port is free.
         */
        public static Load of(FlowStream load, double need, @Nullable Double wired, DesignBasis basis) {
            if (load.empty() || load.state() == null) return NONE;
            double flow = basis.designFlow() * 3600;
            return new Load(load.state().volume() * flow, load.pressure() / 1e5, need * flow, wired == null ? null : wired * flow);
        }

        boolean flows() {
            return flow > 0;
        }
    }

    /** Every limit of {@code load} read at {@code size}. */
    public static Rating rate(Load load, Size size) {
        if (!load.flows()) return Rating.NONE;
        var readings = UnitSize.readings(UnitSize.built(size));
        readings.add(Limit.VACUUM_SPEED.read(load.flow() / size.passes(1), load.suction()));
        var motive = load.motive();
        if (motive != null) {
            readings.add(Limit.EJECTOR_MOTIVE.against(motive, load.need()));
            readings.add(Limit.EJECTOR_MOTIVE_SPARE.read(load.need() > 0 ? 100 * (motive / load.need() - 1) : Double.POSITIVE_INFINITY));
        }
        return new Rating(readings);
    }

    /** The smallest suction that meets every limit, the ones that pass the load with its spare first. No load, no size. */
    public static Selection.Choice<Size> auto(Load load) {
        if (!load.flows()) return Selection.Choice.none();
        double held = Selection.spared(load.flow());
        return Selection.smallest(Selection.spareFirst(SIZES, size -> size.passes(Limit.VACUUM_SPEED.value(load.suction())), held), size -> rate(load, size));
    }
}
