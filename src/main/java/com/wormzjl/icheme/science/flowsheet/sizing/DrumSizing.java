package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.fluid.transport.MixtureViscosity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A drum (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} sections 3.4, 3.5 and 3.10): lying with a boot for a second
 * liquid; lying if a standing drum's hold would need more than the type's L/D; standing otherwise; then the least volume. What a size
 * holds is the shape's own {@link EquipmentShape#volume}, heads and all, and its boot is what the boot adds to it.
 */
public final class DrumSizing {
    /** The constructor's diameters. */
    private static final double DIAMETER_STEP = .5;
    private static final List<Size> STANDING = sizes(UnitType.STANDING_DRUM, false), LYING = sizes(UnitType.LYING_DRUM, false), BOOTED = sizes(UnitType.LYING_DRUM, true);

    private DrumSizing() {
    }

    /** Every size the constructor builds, by volume. */
    private static List<Size> sizes(UnitType type, boolean boot) {
        var sizes = new ArrayList<Size>();
        for (double diameter = EquipmentShape.MIN_DIAMETER; diameter <= EquipmentShape.MAX_DIAMETER; diameter += DIAMETER_STEP)
            for (int length = 1; length <= EquipmentShape.MAX_TANGENT_LENGTH; length++) {
                var size = new Size(type, diameter, length, boot);
                if (UnitSize.built(size) != null) sizes.add(size);
            }
        sizes.sort(Comparator.comparingDouble(size -> size.shape().volume()));
        return List.copyOf(sizes);
    }

    /**
     * A size of drum.
     *
     * @param diameter m
     * @param length   tangent to tangent, m
     * @param boot     whether a lying drum has one
     */
    public record Size(UnitType type, double diameter, int length, boolean boot) implements UnitSize {
        public Size {
            if (type != UnitType.STANDING_DRUM && type != UnitType.LYING_DRUM) throw new IllegalArgumentException("Not a drum: " + type);
            if (!(diameter > 0) || !Double.isFinite(diameter) || length < 1) throw new IllegalArgumentException("Invalid drum size");
        }

        private boolean lying() {
            return type == UnitType.LYING_DRUM;
        }

        private EquipmentShape.Orientation orientation() {
            return lying() ? EquipmentShape.Orientation.HORIZONTAL : EquipmentShape.Orientation.VERTICAL;
        }

        private EquipmentShape withBoot(boolean with) {
            int support = with ? EquipmentShape.minimumBootSupport(diameter, length) : EquipmentShape.defaultSupport(diameter, orientation());
            return EquipmentShape.drum(diameter, length, orientation(), EquipmentShape.Heading.NORTH, support, List.of(), with);
        }

        @Override public EquipmentShape shape() {
            return withBoot(boot);
        }
    }

    /**
     * A drum at design flow.
     *
     * @param gas    m³/s
     * @param oil    m³/s
     * @param water  m³/s
     * @param light  the gas's density, kg/m³
     * @param heavy  the liquid's it settles from, kg/m³
     * @param hold   the drum's entry, min
     */
    public record Load(double gas, double oil, double water, double light, double heavy, double hold) {
        /** A drum with no flow: no size, no lamp. */
        public static final Load NONE = new Load(0, 0, 0, 0, 0, 0);

        /** The drum whose outlets carry these streams, each possibly empty. @param hold its entry (min), null for the row's */
        public static Load of(FlowStream gas, FlowStream oil, FlowStream water, @Nullable Double hold, DesignBasis basis, FlowThermo thermo, MixtureViscosity.Workspace scratch) {
            if (gas.empty() && oil.empty() && water.empty()) return NONE;
            double flow = basis.designFlow();
            var top = gas.empty() ? null : LineSizing.Carried.of(gas, flow, thermo, scratch);
            var bottom = oil.empty() ? null : LineSizing.Carried.of(oil, flow, thermo, scratch);
            var boot = water.empty() ? null : LineSizing.Carried.of(water, flow, thermo, scratch);
            var liquid = bottom != null ? bottom : boot;
            return new Load(top == null ? 0 : top.volume(), bottom == null ? 0 : bottom.volume(), boot == null ? 0 : boot.volume(), top == null ? 0 : top.density(), liquid == null ? 0 : liquid.density(),
                    hold == null ? Limit.DRUM_HOLD.value() : hold);
        }

        boolean flows() {
            return gas + oil + water > 0;
        }

        private boolean twoLiquids() {
            return oil > 0 && water > 0;
        }
    }

    /** The type for {@code load}. */
    public static UnitType type(Load load) {
        return load.twoLiquids() || standingSlenderness(load) > Limit.TYPE_DRUM_STANDING.value() ? UnitType.LYING_DRUM : UnitType.STANDING_DRUM;
    }

    /** The L/D a standing drum's hold needs at the least diameter its gas allows; a hold no standing drum meets needs more than any. */
    private static double standingSlenderness(Load load) {
        if (!(load.oil() + load.water() > 0)) return 0;
        Size least = null;
        for (var size : STANDING) {
            var rating = rate(load, size);
            if (rating.reading(Limit.DRUM_HOLD).met() && (load.gas() == 0 || rating.reading(Limit.DRUM_GAS_STANDING).met()) && (least == null || size.diameter() < least.diameter())) least = size;
        }
        return least == null ? Double.POSITIVE_INFINITY : least.length() / least.diameter();
    }

    /** Every limit of {@code load} read at {@code size}, either type. */
    public static Rating rate(Load load, Size size) {
        if (!load.flows()) return Rating.NONE;
        var readings = new ArrayList<Reading>();
        var shape = UnitSize.built(size);
        readings.add(UnitSize.reading(shape));
        readings.add(Limit.DRUM_DIAMETER.read(size.diameter()));
        readings.add(Limit.DRUM_LENGTH.read(size.length()));
        boolean boot = size.boot() && size.lying();
        if (boot) readings.add(Limit.DRUM_BOOT.read(EquipmentShape.bootFor(size.diameter(), size.length()) == null ? 0 : 1));
        double slender = size.length() / size.diameter();
        readings.add(size.lying() ? Limit.DRUM_SLENDERNESS_LYING.read(slender) : Limit.DRUM_SLENDERNESS_STANDING.read(slender));
        if (load.gas() > 0 && load.heavy() > 0) {
            double section = Math.PI * size.diameter() * size.diameter() / (size.lying() ? 8 : 4), allowed = Math.sqrt((load.heavy() - load.light()) / load.light());
            var row = size.lying() ? Limit.DRUM_GAS_LYING : Limit.DRUM_GAS_STANDING;
            readings.add(row.against(load.gas() / section, row.value() * allowed));
        }
        if (shape != null) {
            double held = boot ? size.withBoot(false).volume() : shape.volume(), settled = boot ? load.oil() : load.oil() + load.water();
            if (settled > 0) readings.add(Limit.DRUM_HOLD.against(held / 2 / settled / 60, load.hold()));
            if (boot && load.water() > 0) readings.add(Limit.DRUM_BOOT_HOLD.read((shape.volume() - held) / load.water() / 60));
        }
        return new Rating(readings);
    }

    /** The proposed type's least volume. No flow, no size. */
    public static Selection.Choice<Size> auto(Load load) {
        return load.flows() ? auto(load, type(load)) : new Selection.Choice<>(null, Rating.NONE);
    }

    /** {@code type}'s least volume: the player's type, on Auto's size. */
    public static Selection.Choice<Size> auto(Load load, UnitType type) {
        if (!load.flows()) return new Selection.Choice<>(null, Rating.NONE);
        var sizes = type == UnitType.STANDING_DRUM ? STANDING : load.twoLiquids() ? BOOTED : LYING;
        return Selection.smallest(sizes, size -> rate(load, size));
    }
}
