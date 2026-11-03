package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.column.operation.SplitTable;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import org.jetbrains.annotations.Nullable;

/**
 * Coolers: air and tower water (batch 2026-11-02-process-simulator, {@code COOLER_MODEL_PLAN.md} sections 3 and 6, C2). Both
 * coolants come in at 25 °C. An air cooler is practical to 40 °C, its 15 K approach ({@link Limit#AIR_COOLER_OUTLET}); a water
 * cooler to 35 °C, its 10 K ({@link Limit#EXCHANGER_APPROACH}); below 25 °C a stream needs refrigeration. Sizing proposes the type
 * by the outlet, a key keeps the player's, and the type's own rows rate it, as a Fixed size is rated.
 */
public final class CoolerSizing {
    /** What a free water side's tubes carry the water at, Pa: the sample's cooling water supply. Liquid water's state hardly reads it. */
    static final double TOWER_WATER_PRESSURE = 4e5;

    private CoolerSizing() {
    }

    /**
     * Where a free water side's water returns, K, for a process inlet at {@code inlet} K: 25 °C plus min(10, max(T_in − 35, (T_in −
     * 25)/4)) °C. The full 10 K rise keeps 10 K at the hot end down to a 40 °C inlet; a cooler inlet takes less, so the water never
     * reaches it, and the least difference along the exchanger stays at the cold end, where {@link Limit#EXCHANGER_APPROACH} reads it.
     */
    public static double waterReturn(double inlet) {
        double in = inlet - HeatSides.TOWER_WATER_TEMPERATURE;
        return HeatSides.TOWER_WATER_TEMPERATURE + Math.max(0, Math.min(HeatSides.WATER_RANGE, Math.max(in - HeatSides.WATER_RANGE, in / 4)));
    }

    /**
     * Where a column's free water side returns, K (section 8): as a regulated side's water, {@link HeatSides#coolingWaterOutlet} from
     * 25 °C below the process cold end {@code coldEnd}; where that cold end leaves the tower water no rise, section 3's return on the
     * process inlet {@code inlet}, which the side's approach row then reads.
     */
    public static double columnReturn(double coldEnd, double inlet) {
        double back = HeatSides.coolingWaterOutlet(HeatSides.TOWER_WATER_TEMPERATURE, coldEnd);
        return back > HeatSides.TOWER_WATER_TEMPERATURE ? back : waterReturn(inlet);
    }

    /** The tower water that takes {@code in} to {@code out} at the run's flow, back at section 3's return ({@link #waterReturn}). */
    static @Nullable Water water(FlowStream in, FlowStream out, FlowThermo thermo) {
        return water(in, out, waterReturn(in.temperature()), thermo);
    }

    /**
     * The tower water that takes {@code in} to {@code out} at the run's flow: its supply at 25 °C and its return at {@code back} K, the
     * same moles. Null where the stream is not cooled, the water does not rise, or the basis holds no water.
     */
    static @Nullable Water water(FlowStream in, FlowStream out, double back, FlowThermo thermo) {
        double removed = in.enthalpy() - out.enthalpy();
        int water = thermo.axis().indexOf(SplitTable.WATER);
        if (in.empty() || !(removed > 0) || water < 0 || !(back > HeatSides.TOWER_WATER_TEMPERATURE)) return null;
        double[] one = new double[thermo.axis().size()];
        one[water] = 1;
        double rise = thermo.atTemperature(back, TOWER_WATER_PRESSURE, one, SolidInventory.EMPTY).enthalpy() - thermo.atTemperature(HeatSides.TOWER_WATER_TEMPERATURE, TOWER_WATER_PRESSURE, one, SolidInventory.EMPTY).enthalpy();
        double[] moles = new double[one.length];
        moles[water] = removed / rise;
        return new Water(thermo.atTemperature(HeatSides.TOWER_WATER_TEMPERATURE, TOWER_WATER_PRESSURE, moles, SolidInventory.EMPTY), thermo.atTemperature(back, TOWER_WATER_PRESSURE, moles, SolidInventory.EMPTY));
    }

    /** A free side's tower water: what comes from the tower and what goes back to it. */
    record Water(FlowStream supply, FlowStream back) {
    }

    /** The Cooler's proposal for an outlet at {@code outlet} K: Air at the air's split, 40 °C, and above it; Water below. */
    public static UnitType type(double outlet) {
        return outlet >= HeatSides.AIR_SPLIT_TEMPERATURE ? UnitType.AIR_COOLER : UnitType.WATER_COOLER;
    }

    /**
     * A column condenser's proposal (section 6) from its top vapour's and its receiver's temperatures, K: air while the receiver is
     * at the split or above it, water where the top vapour is, and air then water between.
     */
    public static ColumnComponents.Condenser condenser(double top, double receiver) {
        if (receiver >= HeatSides.AIR_SPLIT_TEMPERATURE) return ColumnComponents.Condenser.AIR_COOLER;
        return top <= HeatSides.AIR_SPLIT_TEMPERATURE ? ColumnComponents.Condenser.EXCHANGER : ColumnComponents.Condenser.AIR_THEN_WATER;
    }

    /** Which member of an air-then-water condenser takes no duty, null where both take some. */
    public enum Idle { AIR, TRIM }

    /**
     * The idle member of an air-then-water condenser between a top vapour at {@code top} and a receiver at {@code receiver}, K: the
     * trim where the receiver stands at the split or above it, nothing to cool below 40 °C; the air where the top vapour stands at
     * it or below it; null where the split lies between them ({@link HeatSides#airSplit}).
     */
    public static @Nullable Idle twinIdle(double top, double receiver) {
        double split = HeatSides.airSplit(top, receiver);
        return split <= receiver ? Idle.TRIM : split >= top ? Idle.AIR : null;
    }

    /**
     * On a water cooler from {@code in} to {@code out}, what an air cooler ahead of it would take down to the split, W at the run's
     * flow: 0 where the inlet stands at the split or below it, the whole where the outlet stands above it.
     */
    static double airAhead(FlowStream in, FlowStream out, FlowThermo thermo) {
        if (in.empty() || !(in.temperature() > HeatSides.AIR_SPLIT_TEMPERATURE)) return 0;
        var split = thermo.atTemperature(Math.max(HeatSides.AIR_SPLIT_TEMPERATURE, out.temperature()), in.pressure(), in.amountsView(), in.solids());
        return Math.max(0, in.enthalpy() - split.enthalpy());
    }
}
