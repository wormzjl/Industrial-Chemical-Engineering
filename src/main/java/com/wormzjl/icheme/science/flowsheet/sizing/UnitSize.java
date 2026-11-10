package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import org.jetbrains.annotations.Nullable;

/**
 * A unit's size, Auto's or the player's (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} sections 3.1 and 3.7). A size is
 * what the constructor can build: {@link #shape} is what Send to block stores, and a size it refuses reads red in {@link #built}, so
 * a rate function never throws for a Fixed size. Every number a rating reads comes from the shape's own laws.
 */
public sealed interface UnitSize permits PumpSizing.Size, ExchangerSizing.Size, AirCoolerSizing.Size, HeaterSizing.Size, CompressorSizing.Size, DrumSizing.Size, TankSizing.Size, ValveSizing.Size, ColumnSizing.Size, EjectorSizing.Size, TurbineSizing.Size, FlareSizing.Size, StillSizing.Size, ChimneySizing.Size, VacuumPumpSizing.Size, PrimeMoverSizing.Size {
    /** The shape one of these builds, null for a unit the constructor has no kind for. Throws {@link IllegalArgumentException} for a size it refuses. */
    @Nullable EquipmentShape shape();

    /** How many stand in parallel on the row (D16). */
    default int count() {
        return 1;
    }

    /** {@code kind} at these sizes, on its least support. */
    static EquipmentShape unit(EquipmentShape.Kind kind, double size, int length, ProcessUnit.Bank bank) {
        return EquipmentShape.designed(kind, size, length, EquipmentShape.Heading.NORTH, ProcessUnit.defaultSupport(kind, size, length), bank);
    }

    /** Every main size of {@code kind} the constructor offers, smallest first, each on its grid. */
    static double[] grid(EquipmentShape.Kind kind) {
        var design = UnitDesign.of(kind);
        int steps = (int) Math.round((design.maxSize() - design.minSize()) / design.sizeStep());
        double[] sizes = new double[steps + 1];
        for (int step = 0; step <= steps; step++) sizes[step] = ProcessUnit.snap(kind, design.minSize() + step * design.sizeStep());
        return sizes;
    }

    /** {@code size}'s shape, null if the constructor refuses it. */
    static @Nullable EquipmentShape built(UnitSize size) {
        try {
            return size.shape();
        } catch (IllegalArgumentException refused) {
            return null;
        }
    }

    /** Whether the constructor builds what {@link #built} gave. */
    static Reading reading(@Nullable EquipmentShape shape) {
        return Limit.UNIT_SHAPE.read(shape == null ? 0 : 1);
    }

    /** A rating's readings, opened with {@link #reading} of {@code shape}. */
    static java.util.ArrayList<Reading> readings(@Nullable EquipmentShape shape) {
        var readings = new java.util.ArrayList<Reading>();
        readings.add(reading(shape));
        return readings;
    }
}
