package com.wormzjl.icheme.science.flowsheet.sizing;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import org.jetbrains.annotations.Nullable;

/**
 * Every kind of equipment declares its sizing (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} work package S5): the size class
 * that sizes it and gives Send to block its shape, or why it has none. The switch has no default, so a new kind fails the compile here, as it
 * does in {@code FlowsheetCatalog}. The ejector joined with main plan W10. {@code SizingCoverageTest} holds a kind the simulator runs to a size class, and each class to the kinds
 * its shapes build.
 */
public final class SizingCoverage {
    /**
     * Where a kind stands.
     *
     * @param size   the size class that sizes it, null where none does
     * @param reason why none does, null where one does
     */
    public record Entry(@Nullable Class<? extends UnitSize> size, @Nullable String reason) {
        public Entry {
            if ((size == null) == (reason == null)) throw new IllegalArgumentException("A size class or a reason, one of the two");
        }
    }

    /** A size class that no kind builds: the valve, which the pipe tool lays at its inlet line's bore. */
    public static final Class<? extends UnitSize> NO_KIND = ValveSizing.Size.class;

    private static final String NO_MODEL = "No process model: the simulator draws its tile unlit, and a run reads no number a size would rate";

    private SizingCoverage() {
    }

    private static Entry sized(Class<? extends UnitSize> size) {
        return new Entry(size, null);
    }

    private static Entry none(String reason) {
        return new Entry(null, reason);
    }

    public static Entry of(EquipmentShape.Kind kind) {
        return switch (kind) {
            case DRUM -> sized(DrumSizing.Size.class);
            case COLUMN -> sized(ColumnSizing.Size.class);
            case TANK, SPHERE -> sized(TankSizing.Size.class);
            case EXCHANGER, KETTLE -> sized(ExchangerSizing.Size.class);
            case AIR_COOLER -> sized(AirCoolerSizing.Size.class);
            case PUMP -> sized(PumpSizing.Size.class);
            case COMPRESSOR, CENTRIFUGAL_COMPRESSOR -> sized(CompressorSizing.Size.class);
            case VERTICAL_HEATER, CABIN_HEATER -> sized(HeaterSizing.Size.class);
            case EJECTOR -> sized(EjectorSizing.Size.class);
            case STIRRED_REACTOR, ADSORBER, TRICKLE_BED_REACTOR, EBULLATED_BED_REACTOR, STEAM_TURBINE, MOTOR, GENERATOR, GEARBOX, CYCLONE, FCC_CONVERTER, DELAYED_COKER,
                 FLUIDIZED_BED_BOILER, FLUIDIZED_BED_GASIFIER, GRATE_BOILER, FIXED_BED_GASIFIER, INDUCED_DRAFT_COOLING_TOWER, NATURAL_DRAFT_COOLING_TOWER -> none(NO_MODEL);
        };
    }
}
