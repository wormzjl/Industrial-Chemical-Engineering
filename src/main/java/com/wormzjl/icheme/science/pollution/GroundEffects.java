package com.wormzjl.icheme.science.pollution;

/**
 * What polluted ground does to the world (batch 2026-11-18-ground-pollution, plan G4), as pure rules on a column's four
 * published bytes ({@link GroundSnapshot#packed(long)}). The block changes are {@link GroundSpoil}'s (G4b).
 */
public final class GroundEffects {
    /** Where the worst ground index of a column is at least this, crops, saplings and bonemeal do not grow. */
    public static final double GROWTH_LIMIT = Exposure.MEDIUM;

    private GroundEffects() {
    }

    /** Whether a column's ground stops growth: its worst channel index reaches {@link #GROWTH_LIMIT}. */
    public static boolean blocksGrowth(int packed) {
        int worst = 0;
        for (var channel : Channel.values()) worst = Math.max(worst, MapColumns.value(packed, channel));
        return MapColumns.dequantise(worst) >= GROWTH_LIMIT;
    }

    /**
     * The exposure of a living thing in the water of a column (by channel ordinal): the toxic and corrosive ground indices
     * at {@link Exposure#level}; what the water carries of the combustible and particulate channels does not act on a
     * swimmer (NONE). Null when nothing acts, so a pass skips the column.
     */
    public static Exposure.Level[] water(int packed) {
        var out = new Exposure.Level[Channel.values().length];
        java.util.Arrays.fill(out, Exposure.Level.NONE);
        out[Channel.TOXIC.ordinal()] = Exposure.level(MapColumns.dequantise(MapColumns.value(packed, Channel.TOXIC)));
        out[Channel.CORROSIVE.ordinal()] = Exposure.level(MapColumns.dequantise(MapColumns.value(packed, Channel.CORROSIVE)));
        boolean any = out[Channel.TOXIC.ordinal()] != Exposure.Level.NONE || out[Channel.CORROSIVE.ordinal()] != Exposure.Level.NONE;
        return any ? out : null;
    }
}
