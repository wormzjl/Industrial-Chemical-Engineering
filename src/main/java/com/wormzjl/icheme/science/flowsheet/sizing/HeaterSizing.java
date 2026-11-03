package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A fired heater (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} sections 3.4, 3.5 and 3.10): a vertical while the
 * largest vertical holds the design duty with its spare, a cabin above, a count of cabins above the largest cabin; then the least
 * duty that holds. The lamps read the bare duty.
 */
public final class HeaterSizing {
    private static final List<Size> VERTICAL = sizes(UnitType.VERTICAL_HEATER), CABIN = sizes(UnitType.CABIN_HEATER);

    private HeaterSizing() {
    }

    private static List<Size> sizes(UnitType type) {
        var design = UnitDesign.of(type.kind());
        var sizes = new ArrayList<Size>();
        for (double size : UnitSize.grid(type.kind()))
            for (int length = design.minLength(size); length <= design.maxLength(size); length++) sizes.add(new Size(type, 1, size, length));
        sizes.sort(Comparator.comparingDouble(Size::held));
        return List.copyOf(sizes);
    }

    /**
     * A size of heater.
     *
     * @param count    in parallel
     * @param diameter a vertical's casing, a cabin's width, m
     * @param length   a vertical's radiant height, a cabin's length, m
     */
    public record Size(UnitType type, int count, double diameter, int length) implements UnitSize {
        public Size {
            if (type != UnitType.VERTICAL_HEATER && type != UnitType.CABIN_HEATER) throw new IllegalArgumentException("Not a heater: " + type);
            if (count < 1 || !(diameter > 0) || !Double.isFinite(diameter) || length < 1) throw new IllegalArgumentException("Invalid heater size");
        }

        /** What all of them hold, MW. */
        public double held() {
            return count * ProcessUnit.heaterDuty(type.kind(), diameter, length);
        }

        @Override public EquipmentShape shape() {
            return UnitSize.unit(type.kind(), diameter, length, ProcessUnit.Bank.ONE);
        }
    }

    /**
     * A heater at design flow.
     *
     * @param duty  what it must fire, MW
     * @param spare the pressure spare on its path (bar), null until the Pressure view gives it: then its row does not read
     */
    public record Load(double duty, @Nullable Double spare) {
    }

    /** The duty Auto sizes for: the design duty with {@link Limit#AUTO_SPARE}, MW. */
    private static double spared(Load load) {
        return Selection.spared(load.duty());
    }

    /** The type for {@code load}: a vertical while the largest holds the duty with its spare (S4c). */
    public static UnitType type(Load load) {
        return spared(load) <= Limit.HEATER_VERTICAL.value() ? UnitType.VERTICAL_HEATER : UnitType.CABIN_HEATER;
    }

    /** Every limit of {@code load} read at {@code size}, any type and any count. No duty, nothing to read. */
    public static Rating rate(Load load, Size size) {
        if (!(load.duty() > 0)) return Rating.NONE;
        var readings = UnitSize.readings(UnitSize.built(size));
        readings.add(Limit.HEATER_DUTY.against(size.held(), load.duty()));
        readings.add(size.type() == UnitType.VERTICAL_HEATER ? Limit.HEATER_VERTICAL.read(load.duty()) : Limit.HEATER_CABIN.read(load.duty()));
        var spare = load.spare();
        if (spare != null) readings.add(Limit.HEATER_SPARE.read(spare));
        return new Rating(readings);
    }

    /**
     * {@code type}'s least duty that holds: the player's type, on Auto's size, or the proposed type where it is null. Cabins are
     * counted to hold the duty with {@link Limit#AUTO_SPARE} (S4c), and the least size that holds it comes first; where none of the
     * type does, the least that holds the bare duty. No duty, no size.
     */
    public static Selection.Choice<Size> auto(Load load, @Nullable UnitType type) {
        if (!(load.duty() > 0)) return Selection.Choice.none();
        if (type == null) type = type(load);
        int count = type == UnitType.CABIN_HEATER ? Math.max(1, (int) Math.ceil(spared(load) / Limit.TYPE_CABIN_LARGEST.value())) : 1;
        var sizes = (type == UnitType.CABIN_HEATER ? CABIN : VERTICAL).stream().map(size -> new Size(size.type(), count, size.diameter(), size.length())).toList();
        return Selection.smallest(Selection.spareFirst(sizes, Size::held, spared(load)), size -> rate(load, size));
    }
}
