package com.wormzjl.icheme.science.equipment;

import java.util.Collection;

/**
 * A built structure's default name (batch 2026-11-18-default-equipment-names; owner 2026-11-18: "Give equipment a
 * default name", format "Plant tag: P-101", built equipment only): a letter code by kind, after the ISA / PFD
 * convention, and the lowest free number from 101 among the names its dimension's structures hold. The tag is an
 * ordinary {@link DeviceName}: the player renames or clears it.
 */
public final class PlantTag {
    /** The first number a prefix takes. */
    public static final int FIRST = 101;

    private PlantTag() {
    }

    /** The letter code of {@code kind}; every kind has one, so a new kind does not compile until it is given one. */
    public static String prefix(EquipmentShape.Kind kind) {
        return switch (kind) {
            case COLUMN, BATCH_STILL -> "C";
            case DRUM, ADSORBER, CYCLONE, GAS_CONVERTER -> "V";
            case TANK, SPHERE, FLUID_CONVERTER -> "TK";
            case EXCHANGER, KETTLE, AIR_COOLER -> "E";
            case PUMP, PLUNGER_PUMP -> "P";
            case COMPRESSOR, CENTRIFUGAL_COMPRESSOR, LIQUID_RING_PUMP, DRY_SCREW_PUMP, EJECTOR -> "K";
            case VERTICAL_HEATER, CABIN_HEATER, FLUIDIZED_BED_BOILER, GRATE_BOILER -> "H";
            case STIRRED_REACTOR, TRICKLE_BED_REACTOR, EBULLATED_BED_REACTOR, FCC_CONVERTER, DELAYED_COKER,
                 FLUIDIZED_BED_GASIFIER, FIXED_BED_GASIFIER -> "R";
            case INDUCED_DRAFT_COOLING_TOWER, NATURAL_DRAFT_COOLING_TOWER -> "CT";
            case FLARE_STACK, STEEL_STACK, BRICK_CHIMNEY -> "FL";
            case MOTOR, GENERATOR, GEARBOX, STEAM_TURBINE, COMBUSTION_ENGINE, GAS_TURBINE -> "M";
        };
    }

    /** The lowest n from {@link #FIRST} such that no name in {@code taken} is {@code prefix + "-" + n} (names as stored). */
    public static int next(String prefix, Collection<String> taken) {
        var lead = prefix + "-";
        var used = new java.util.BitSet();
        for (var name : taken) {
            if (name == null || !name.startsWith(lead)) continue;
            var digits = name.substring(lead.length());
            if (digits.isEmpty() || digits.length() > 10 || digits.charAt(0) == '0') continue;
            boolean plain = true;
            for (int i = 0; i < digits.length() && plain; i++) plain = digits.charAt(i) >= '0' && digits.charAt(i) <= '9';
            if (!plain) continue;
            long n = Long.parseLong(digits);
            // The answer is at most FIRST + taken.size(): numbers above it cannot close the gap and are not stored.
            if (n >= FIRST && n - FIRST <= taken.size()) used.set((int) (n - FIRST));
        }
        return FIRST + used.nextClearBit(0);
    }

    /** The default name of a new structure of {@code kind}, beside the structures named {@code taken}. */
    public static String tag(EquipmentShape.Kind kind, Collection<String> taken) {
        var prefix = prefix(kind);
        return prefix + "-" + next(prefix, taken);
    }
}
