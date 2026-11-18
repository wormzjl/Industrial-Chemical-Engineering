package com.wormzjl.icheme.science.pollution;

/**
 * What polluted ground does to the world (batch 2026-11-18-ground-pollution, plan G4), as pure rules on a column's
 * published byte ({@link GroundSnapshot#value(long)}). The block changes are {@link GroundSpoil}'s (G4b).
 */
public final class GroundEffects {
    /** Where a column's ground index is at least this, crops, saplings and bonemeal do not grow. */
    public static final double GROWTH_LIMIT = Exposure.MEDIUM;

    private GroundEffects() {
    }

    /** A column's ground index from its byte. */
    public static double index(int value) {
        return MapColumns.dequantise(value);
    }

    /** Whether a column's ground stops growth: its index reaches {@link #GROWTH_LIMIT}. */
    public static boolean blocksGrowth(int value) {
        return index(value) >= GROWTH_LIMIT;
    }

    /**
     * The exposure of a living thing in the water of a column (by channel ordinal): the ground index read on the toxic
     * channel's level table ({@link Exposure#level}), the other channels NONE. Null when nothing acts, so a pass skips the
     * column.
     */
    public static Exposure.Level[] water(int value) {
        var level = Exposure.level(index(value));
        if (level == Exposure.Level.NONE) return null;
        var out = new Exposure.Level[Channel.values().length];
        java.util.Arrays.fill(out, Exposure.Level.NONE);
        out[Channel.TOXIC.ordinal()] = level;
        return out;
    }
}
