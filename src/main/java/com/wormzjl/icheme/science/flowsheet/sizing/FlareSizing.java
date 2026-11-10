package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.fluid.network.Flare;

import java.util.ArrayList;
import java.util.List;

/**
 * A flare stack (batch 2026-11-09-flare-stack, W4): the tip that passes the run's gas at design flow, on the constructor's grid. The
 * most a tip passes is {@link Flare#limit}: its rated heat or its stable exit velocity. Auto first tries the tips that pass the load
 * with {@link Limit#AUTO_SPARE} (the owner's "always have enough excess", 2026-11-03). Its flame temperature is the gas's, not the
 * tip's: a flame that does not hold reads red at every size.
 */
public final class FlareSizing {
    private static final List<Size> SIZES = sizes();

    private FlareSizing() {
    }

    private static List<Size> sizes() {
        var sizes = new ArrayList<Size>();
        for (double tip : UnitSize.grid(EquipmentShape.Kind.FLARE_STACK)) sizes.add(new Size(tip));
        return List.copyOf(sizes);
    }

    /** @param tip the tip's diameter, m: the world's flare is sized by it, and its stack height follows */
    public record Size(double tip) implements UnitSize {
        public Size {
            if (!(tip > 0) || !Double.isFinite(tip)) throw new IllegalArgumentException("Invalid flare tip");
        }

        @Override public EquipmentShape shape() {
            return UnitSize.unit(EquipmentShape.Kind.FLARE_STACK, tip, 1, ProcessUnit.Bank.ONE);
        }
    }

    /**
     * A flare at design flow.
     *
     * @param mass        the gas's mass flow, kg/h
     * @param perKilogram its heat, J/kg
     * @param nhv         its net heating value, J/scm
     * @param density     its density at the tip, kg/m³
     * @param flame       its flame temperature, K
     */
    public record Load(double mass, double perKilogram, double nhv, double density, double flame) {
        /** A flare with no load: no size, no lamp. */
        public static final Load NONE = new Load(0, 0, 0, 1, Flare.AMBIENT);

        /**
         * The flare on the run's gas, of {@code mass} kg/s whose reading gave {@code perKilogram} J/kg, {@code nhv} J/scm and a
         * flame of {@code flame} K, at {@code density} kg/m³ at its tip.
         */
        public static Load of(double mass, double perKilogram, double nhv, double density, double flame, DesignBasis basis) {
            return mass > 0 && density > 0 ? new Load(mass * basis.designFlow() * 3600, perKilogram, nhv, density, flame) : NONE;
        }

        boolean flows() {
            return mass > 0;
        }

        /** The most tip {@code size} passes of this gas, kg/h. */
        double passes(Size size) {
            return Flare.limit(size.tip(), perKilogram, nhv, density) * 3600;
        }
    }

    /** Every limit of {@code load} read at {@code size}. */
    public static Rating rate(Load load, Size size) {
        if (!load.flows()) return Rating.NONE;
        var readings = UnitSize.readings(UnitSize.built(size));
        double passes = load.passes(size);
        readings.add(Limit.FLARE_FLAME.read(load.flame()));
        readings.add(Limit.FLARE_CAPACITY.against(load.mass(), passes));
        readings.add(Limit.FLARE_SPARE.read(load.mass() > 0 ? 100 * (passes / load.mass() - 1) : Double.POSITIVE_INFINITY));
        return new Rating(readings);
    }

    /** The smallest tip that meets every limit, the ones that pass the load with its spare first. No load, no size. */
    public static Selection.Choice<Size> auto(Load load) {
        if (!load.flows()) return Selection.Choice.none();
        double held = Selection.spared(load.mass());
        return Selection.smallest(Selection.spareFirst(SIZES, load::passes, held), size -> rate(load, size));
    }
}
