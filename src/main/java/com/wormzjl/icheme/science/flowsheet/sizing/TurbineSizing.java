package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.MachineDuty;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.machine.Motor;
import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A steam turbine (batch 2026-11-03-rotating-machinery, W8, owner decision D43): the constructor's design point, rated power,
 * inlet steam class and exhaust, proposed from the tile's shaft power at design flow. Auto takes the smallest rated power of
 * {@link MachineDuty#POWERS} that holds the need with the {@link Motor#SPARE} (the owner's "always have enough excess"), on the
 * lowest steam class whose header holds the inlet, at the exhaust of {@link MachineDuty#EXHAUSTS} nearest the tile's. What the
 * constructor refuses (a power outside the turbine's range, an exhaust above half its class's inlet) reads red on
 * {@link Limit#UNIT_SHAPE}; the tile itself refuses an exhaust at or above its inlet, so no steady run holds one.
 */
public final class TurbineSizing {
    private TurbineSizing() {
    }

    /**
     * A design point.
     *
     * @param power   rated shaft power, kW
     * @param steam   the inlet steam class
     * @param exhaust exhaust pressure, kPa absolute
     */
    public record Size(double power, MachineDuty.Steam steam, double exhaust) implements UnitSize {
        public Size {
            Objects.requireNonNull(steam, "steam");
            if (!(power > 0) || !Double.isFinite(power) || !(exhaust > 0) || !Double.isFinite(exhaust)) throw new IllegalArgumentException("Invalid turbine size");
        }

        @Override public EquipmentShape shape() {
            var design = UnitDesign.of(EquipmentShape.Kind.STEAM_TURBINE);
            double least = design.minSize();
            // The drawn sizes follow the design point (withDuty), which refuses one outside the constructor's ranges.
            return UnitSize.unit(EquipmentShape.Kind.STEAM_TURBINE, least, design.minLength(least), ProcessUnit.Bank.ONE).withDuty(MachineDuty.turbine(power, steam, exhaust));
        }
    }

    /**
     * A turbine at design flow.
     *
     * @param power   the shaft power it gives, kW
     * @param inlet   its steam's pressure, kPa absolute
     * @param exhaust the tile's exhaust, kPa absolute
     */
    public record Load(double power, double inlet, double exhaust) {
        /** A turbine that gives no power: no size, no lamp. */
        public static final Load NONE = new Load(0, 0, 0);

        /** The turbine whose run gave {@code power} (W) from {@code in} to {@code exhaust} (Pa), at the basis's design flow. */
        public static Load of(FlowStream in, double power, double exhaust, DesignBasis basis) {
            if (in.empty() || !(power > 0)) return NONE;
            return new Load(power * basis.designFlow() / 1e3, in.pressure() / 1e3, exhaust / 1e3);
        }

        boolean flows() {
            return power > 0;
        }
    }

    /** Every limit of {@code load} read at {@code size}. */
    public static Rating rate(Load load, Size size) {
        if (!load.flows()) return Rating.NONE;
        var readings = UnitSize.readings(UnitSize.built(size));
        // A ratio, not a share over the need: under the need it still ranks, so where nothing holds the spare Auto offers the largest.
        readings.add(Limit.TURBINE_SPARE.read(size.power() / load.power()));
        readings.add(Limit.TURBINE_INLET.against(load.inlet(), size.steam().pressure));
        return new Rating(readings);
    }

    /** The exhaust of {@link MachineDuty#EXHAUSTS} nearest {@code exhaust} (kPa) by ratio, the lower on a tie. */
    static double exhaust(double exhaust) {
        double best = MachineDuty.EXHAUSTS.getFirst(), far = Double.POSITIVE_INFINITY;
        for (double e : MachineDuty.EXHAUSTS) {
            double d = Math.abs(DeterministicMath.log(e / exhaust));
            if (d < far) {
                far = d;
                best = e;
            }
        }
        return best;
    }

    /** The list's end a turbine was sized from before the generators passed 50 MW (batch 2026-11-10-prime-movers), kW. */
    static final double TURBINE_LIST = 50000;

    /** The smallest rated power that holds the need with its spare, on the lowest class that holds the inlet. No power, no size. */
    public static Selection.Choice<Size> auto(Load load) {
        if (!load.flows()) return Selection.Choice.none();
        double exhaust = exhaust(load.exhaust());
        var sizes = new ArrayList<Size>();
        for (double power : MachineDuty.POWERS) if (power <= TURBINE_LIST) for (var steam : MachineDuty.Steam.values()) sizes.add(new Size(power, steam, exhaust));
        return Selection.smallest(Selection.spareFirst(List.copyOf(sizes), Size::power, Motor.SPARE * load.power()), size -> rate(load, size));
    }
}
