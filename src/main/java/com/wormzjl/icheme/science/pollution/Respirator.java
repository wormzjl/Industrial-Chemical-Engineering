package com.wormzjl.icheme.science.pollution;

/**
 * The respirator's law (batch 2026-11-18-respirator). Pure: no Minecraft type.
 *
 * <p>A full-face air-purifying respirator lowers what its wearer breathes by an assigned protection factor; its filter is
 * used up by the contaminant it takes out, so it lasts long in light air and briefly in heavy air, and a spent filter passes
 * everything. The respirator acts only on what is breathed: the cube's real index still corrodes armour and still ignites.</p>
 *
 * <p>The filter's remaining capacity is in index-seconds of play. One pass takes {@link #load} from it; the pass is protected
 * by the capacity before its own load, so the pass that empties the filter is still protected and the next is not.</p>
 */
public final class Respirator {
    /** The assigned protection factor of a full-face air-purifying respirator: the breathed index is the cube's over this. */
    public static final double PROTECTION_FACTOR = 50;
    /** A new filter's capacity, index-seconds of play: 10 min at one channel at index 1. */
    public static final double FILTER_CAPACITY = 600;
    /** A remaining capacity at or below this after a pass is 0: a sum of many small loads must not leave a crumb. */
    static final double EMPTY = 1e-9;

    private Respirator() {
    }

    /** Whether a filter with {@code remaining} capacity protects: any capacity above 0. NaN does not. */
    public static boolean protects(double remaining) {
        return remaining > 0;
    }

    /** The index the wearer breathes: the cube's over {@link #PROTECTION_FACTOR} while the filter protects, else unchanged. */
    public static double breathed(double index, double remaining) {
        return protects(remaining) ? index / PROTECTION_FACTOR : index;
    }

    /**
     * The filter load of one pass: the sum of the channels' real indices, each counted only at or above
     * {@link Exposure#LOW}, times the pass's play seconds. Below LOW on every channel nothing is used.
     */
    public static double load(double[] indices, double seconds) {
        if (indices == null || !(seconds > 0)) {
            return 0;
        }
        double sum = 0;
        for (double index : indices) {
            if (index >= Exposure.LOW) {
                sum += index;
            }
        }
        return sum * seconds;
    }

    /** The capacity left after a pass's load, never below 0; a spent filter stays spent. */
    public static double after(double remaining, double load) {
        if (!protects(remaining)) {
            return 0;
        }
        if (!(load > 0)) {
            return remaining;
        }
        double left = remaining - load;
        return left > EMPTY ? left : 0;
    }

    /** The filter's remaining share, 0 to 1. */
    public static double share(double remaining) {
        if (!(remaining > 0)) {
            return 0;
        }
        return Math.min(1, remaining / FILTER_CAPACITY);
    }

    /**
     * The levels one pass uses for effects and damage: {@code levels} itself (the cube's, unchanged) unless a respirator is
     * {@code worn} with a filter that protects, else the breathed levels of {@code indices}.
     */
    public static Exposure.Level[] protect(boolean worn, double remaining, double[] indices, Exposure.Level[] levels) {
        if (!worn || !protects(remaining)) {
            return levels;
        }
        return breathedLevels(indices, remaining);
    }

    /**
     * The levels the wearer's body takes from the cube's {@code indices} (by channel ordinal), with a filter of
     * {@code remaining} capacity before this pass's load. Without protection it is each index's own level.
     */
    public static Exposure.Level[] breathedLevels(double[] indices, double remaining) {
        var out = new Exposure.Level[indices.length];
        for (int i = 0; i < indices.length; i++) {
            out[i] = Exposure.level(breathed(indices[i], remaining));
        }
        return out;
    }
}
