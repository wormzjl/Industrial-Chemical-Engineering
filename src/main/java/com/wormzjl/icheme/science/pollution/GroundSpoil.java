package com.wormzjl.icheme.science.pollution;

import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * Polluted ground spoils the surface (batch 2026-11-18-ground-pollution, G4b, owner 2026-11-18: "Do random block
 * transformations like vanilla"), as pure rules on a column's published bytes. Each world pass ({@code EFFECT_TICKS},
 * 1 s) a column whose ground index is at least {@link #SPOIL_LIMIT} gets {@link #attempts} random surface picks; a
 * pick removes the vegetation it meets and turns the first soil into polluted soil. Polluted soil turns back to dirt on
 * its random tick where the index is below {@link #RECOVERY_LIMIT}. The spoiling takes nothing from the ground.
 */
public final class GroundSpoil {
    /** Where the index is at least this, the surface spoils (the growth limit). */
    public static final double SPOIL_LIMIT = Exposure.MEDIUM;
    /** Below this index, polluted soil recovers. */
    public static final double RECOVERY_LIMIT = Exposure.LOW;
    /** The surface positions of a chunk column. */
    public static final int POSITIONS = 256;
    /** The most picks a column gets in one pass. */
    public static final int MAXIMUM_ATTEMPTS = 8;
    /** The pass, seconds of play. */
    public static final double PASS_SECONDS = 1.0;
    /** At index 1, half of a column's positions are picked after this long (seconds of play). */
    public static final double HALF_SECONDS_AT_ONE = 600.0;
    /**
     * Expected picks per pass and unit index: half of {@link #POSITIONS} picked at least once after
     * {@link #HALF_SECONDS_AT_ONE} at index 1, ln 2 / (−ln(1 − 1/256)) / 600 = 0.2952.
     */
    public static final double ATTEMPTS_PER_INDEX = 0.6931471805599453 / -DeterministicMath.log1p(-1.0 / POSITIONS)
            / (HALF_SECONDS_AT_ONE / PASS_SECONDS);

    private GroundSpoil() {
    }

    /** A column's ground index from its byte. */
    public static double index(int value) {
        return MapColumns.dequantise(value);
    }

    /** The expected picks of one pass at index {@code index}: 0 below the limit, at most {@link #MAXIMUM_ATTEMPTS}. */
    public static double expectedAttempts(double index) {
        if (!(index >= SPOIL_LIMIT)) return 0.0;
        return Math.min(MAXIMUM_ATTEMPTS, index * ATTEMPTS_PER_INDEX);
    }

    /**
     * The picks of one pass: {@link #expectedAttempts} rounded up or down at random so its mean is exact
     * ({@code uniform} in [0, 1) from the level's random source). A plain round would give no pick at all below index 1.7.
     */
    public static int attempts(double index, double uniform) {
        double expected = expectedAttempts(index);
        int whole = (int) Math.floor(expected);
        return whole + (uniform < expected - whole ? 1 : 0);
    }

    /** The share of a column's positions picked at least once after {@code seconds} at index {@code index}. */
    public static double spoiledShare(double index, double seconds) {
        double picks = expectedAttempts(index) * seconds / PASS_SECONDS;
        return 1.0 - DeterministicMath.exp(picks * DeterministicMath.log1p(-1.0 / POSITIONS));
    }

    /** Seconds until half of a column's positions are picked at index {@code index} (infinite below the limit). */
    public static double halfSeconds(double index) {
        double rate = expectedAttempts(index);
        return rate == 0.0 ? Double.POSITIVE_INFINITY : 0.6931471805599453 / -DeterministicMath.log1p(-1.0 / POSITIONS) / rate * PASS_SECONDS;
    }

    /** Whether polluted soil in a column of these bytes turns back to dirt on its random tick. */
    public static boolean recovers(int value) {
        return index(value) < RECOVERY_LIMIT;
    }
}
