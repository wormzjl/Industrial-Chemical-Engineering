package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A storage tile (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} sections 3.4, 3.5 and 3.10): a floating roof while the
 * liquid's bubble pressure at the tile's temperature allows one, a sphere above; then the fewest vessels, then the least volume. A
 * new tank's shell follows its diameter, as the constructor's does. What a size holds is the shape's own {@link EquipmentShape#volume}.
 */
public final class TankSizing {
    /** The most vessels on one row. */
    private static final int MOST = 12;
    private static final List<Size> ROOF = sizes(UnitType.FLOATING_ROOF), SPHERE = sizes(UnitType.SPHERE);

    private TankSizing() {
    }

    private static List<Size> sizes(UnitType type) {
        var design = UnitDesign.of(type.kind());
        var sizes = new ArrayList<Size>();
        for (int count = 1; count <= MOST; count++) for (double diameter : UnitSize.grid(type.kind())) sizes.add(new Size(type, count, diameter, design.defaultLength(diameter)));
        return List.copyOf(sizes);
    }

    /**
     * A size of storage.
     *
     * @param count    in parallel
     * @param diameter m
     * @param shell    a tank's height, m; a sphere has 1
     */
    public record Size(UnitType type, int count, double diameter, int shell) implements UnitSize {
        public Size {
            if (type != UnitType.FLOATING_ROOF && type != UnitType.SPHERE) throw new IllegalArgumentException("Not a storage vessel: " + type);
            if (count < 1 || !(diameter > 0) || !Double.isFinite(diameter) || shell < 1) throw new IllegalArgumentException("Invalid storage size");
        }

        @Override public EquipmentShape shape() {
            return UnitSize.unit(type.kind(), diameter, shell, ProcessUnit.Bank.ONE);
        }
    }

    /**
     * A tile at design flow.
     *
     * @param flow   its liquid, m³/h
     * @param bubble that liquid's bubble pressure at the tile's temperature, bar
     * @param hold   the tank's entry, h
     */
    public record Load(double flow, double bubble, double hold) {
        /** A tile that holds no liquid: no size, no lamp. */
        public static final Load NONE = new Load(0, 0, 0);

        /** The tile whose liquid leaves by these streams, each possibly empty. @param hold its entry (h), null for the row's */
        public static Load of(FlowStream oil, FlowStream water, @Nullable Double hold, DesignBasis basis, FlowThermo thermo) {
            if (oil.empty() && water.empty()) return NONE;
            double[] amounts = oil.amounts();
            for (int i = 0; i < amounts.length; i++) amounts[i] += water.amountsView()[i];
            double volume = 0;
            for (var stream : List.of(oil, water)) if (!stream.empty()) volume += stream.state().volume();
            double temperature = oil.empty() ? water.temperature() : oil.temperature();
            return new Load(volume * basis.designFlow() * 3600, thermo.bubblePressure(temperature, amounts) / 1e5, hold == null ? Limit.TANK_HOLD.value() : hold);
        }

        boolean flows() {
            return flow > 0;
        }
    }

    /** The type for {@code load}. */
    public static UnitType type(Load load) {
        return load.bubble() <= Limit.ROOF_BUBBLE.value() ? UnitType.FLOATING_ROOF : UnitType.SPHERE;
    }

    /** Every limit of {@code load} read at {@code size}, either type. */
    public static Rating rate(Load load, Size size) {
        if (!load.flows()) return Rating.NONE;
        var shape = UnitSize.built(size);
        var readings = UnitSize.readings(shape);
        boolean roof = size.type() == UnitType.FLOATING_ROOF;
        readings.add((roof ? Limit.TANK_DIAMETER : Limit.SPHERE_DIAMETER).read(size.diameter()));
        if (roof) {
            readings.add(Limit.TANK_SHELL.read(size.shell()));
            readings.add(Limit.ROOF_BUBBLE_WORLD.read(load.bubble()));
            readings.add(Limit.ROOF_BUBBLE.read(load.bubble()));
        } else readings.add(Limit.SPHERE_DESIGN.read(Limit.SPHERE_DESIGN_FACTOR.value() * load.bubble()));
        if (shape != null) {
            double each = shape.volume(), held = size.count() * each;
            readings.add(Limit.TANK_VOLUME.read(each));
            readings.add(Limit.TANK_HOLD.against(held / load.flow(), load.hold()));
            readings.add(Limit.TANK_WORKING.read(100 * load.hold() * load.flow() / held));
        }
        return new Rating(readings);
    }

    /** {@code type}'s fewest vessels of the least volume: the player's type, on Auto's size, or the proposed type where it is null. No liquid, no size. */
    public static Selection.Choice<Size> auto(Load load, @Nullable UnitType type) {
        if (!load.flows()) return Selection.Choice.none();
        if (type == null) type = type(load);
        return Selection.smallest(type == UnitType.FLOATING_ROOF ? ROOF : SPHERE, size -> rate(load, size));
    }
}
