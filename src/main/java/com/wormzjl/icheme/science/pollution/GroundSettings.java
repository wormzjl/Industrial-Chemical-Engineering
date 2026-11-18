package com.wormzjl.icheme.science.pollution;

/**
 * The constants of the ground step (batch 2026-11-18-ground-pollution, plan G1).
 *
 * @param reference the amount of one column's channel at index 1, in air-mole equivalents ({@link GroundField#GROUND_REFERENCE})
 * @param nominalSeconds the pass length {@code spreadShare} refers to (20 process seconds)
 * @param spreadShare share of the amount difference a face between two loaded columns passes per {@code nominalSeconds};
 *     0 turns spreading off
 * @param wetSpread factor on the spread while it rains
 * @param waterSpread factor on the spread across a face where either column's surface is mostly water
 * @param halfLifeSeconds half-life of a loaded column's amounts and compounds, process seconds
 * @param washout factor by which rain shortens the half-life
 * @param cutoff the smallest index a column's channel keeps
 * @param maximumColumns the most columns the field holds
 */
public record GroundSettings(double reference, double nominalSeconds, double spreadShare, double wetSpread,
        double waterSpread, double halfLifeSeconds, double washout, double cutoff, int maximumColumns) {
    /** The largest share a face passes in one step: four faces never take more than 0.8 of a column. */
    public static final double MAXIMUM_FACE_SHARE = 0.2;

    public static final GroundSettings DEFAULT = new GroundSettings(GroundField.GROUND_REFERENCE, 20, 5e-4, 4, 4,
            14400, 4, 1e-4, 16384);

    public GroundSettings {
        positive("reference", reference);
        positive("nominalSeconds", nominalSeconds);
        if (!(spreadShare >= 0.0) || !Double.isFinite(spreadShare)) {
            throw new IllegalArgumentException("spreadShare must be finite and not negative: " + spreadShare);
        }
        positive("wetSpread", wetSpread);
        positive("waterSpread", waterSpread);
        positive("halfLifeSeconds", halfLifeSeconds);
        positive("washout", washout);
        positive("cutoff", cutoff);
        if (maximumColumns < 1) {
            throw new IllegalArgumentException("maximumColumns must be at least 1: " + maximumColumns);
        }
    }

    /** This with spreading off (a test of the decay alone). */
    public GroundSettings withoutSpread() {
        return new GroundSettings(reference, nominalSeconds, 0, wetSpread, waterSpread, halfLifeSeconds, washout,
                cutoff, maximumColumns);
    }

    private static void positive(String name, double value) {
        if (!(value > 0.0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite and positive: " + value);
        }
    }
}
