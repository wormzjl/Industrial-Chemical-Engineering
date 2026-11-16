package com.wormzjl.icheme.science.pollution;

import java.util.Locale;

/**
 * What the handheld pollution meter shows for a cube (batch 2026-11-15-pollution-system, WP8; plan section 11). Pure: no
 * Minecraft type. Each of the four channels reads on its own; nothing is added together (owner, 2026-11-15: particulate is
 * a stand-alone kind).
 */
public final class Reading {
    private Reading() {
    }

    /** The word beside a channel's value, by its {@link Exposure} level. */
    public enum Word {
        SAFE,
        WARNING,
        DANGER,
        LETHAL,
        FLAMMABLE
    }

    /**
     * The word of a channel at an index: NONE is SAFE, LOW is WARNING, MEDIUM is DANGER, HIGH and EXTREME are LETHAL; a
     * combustible gas at HIGH and above (the LFL) is FLAMMABLE. NaN or negative is SAFE.
     */
    public static Word word(Channel channel, double index) {
        var level = Exposure.level(index);
        return switch (level) {
            case NONE -> Word.SAFE;
            case LOW -> Word.WARNING;
            case MEDIUM -> Word.DANGER;
            case HIGH, EXTREME -> channel == Channel.COMBUSTIBLE ? Word.FLAMMABLE : Word.LETHAL;
        };
    }

    /** The highest level of the four channels; the warning tick's rate follows it. */
    public static Exposure.Level worst(double toxic, double combustible, double corrosive, double particulate) {
        var out = Exposure.level(toxic);
        for (double index : new double[] {combustible, corrosive, particulate}) {
            var level = Exposure.level(index);
            if (level.compareTo(out) > 0) {
                out = level;
            }
        }
        return out;
    }

    /** An index for display: two decimals below 10, one from 10; "0.00" for NaN or a negative value. */
    public static String number(double index) {
        if (!(index >= 0)) {
            return "0.00";
        }
        if (Double.isInfinite(index)) {
            return "999.9";
        }
        // 9.995 and above would round to "10.00": they show as "10.0".
        return index < 9.995 ? String.format(Locale.ROOT, "%.2f", index) : String.format(Locale.ROOT, "%.1f", index);
    }
}
