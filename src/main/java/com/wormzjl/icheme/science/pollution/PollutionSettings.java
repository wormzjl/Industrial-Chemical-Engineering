package com.wormzjl.icheme.science.pollution;

/**
 * The constants of the pollution step (plan section 4).
 *
 * @param faceShare share of the index difference a face passes per {@code nominalSeconds} (0.12: an eddy diffusivity
 *     near 1.5 m²/s over 16 m); the per-step share is capped at 1/7 for a stable, positive explicit step
 * @param nominalSeconds the pass length {@code faceShare} and {@code settlingShare} refer to (20 process seconds)
 * @param halfLifeOpenSeconds half-life in a cube at or above the surface
 * @param halfLifeEnclosedSeconds half-life in an enclosed cube (indoors, underground)
 * @param washout factor by which rain shortens the half-life of corrosive and particulate in open cubes
 * @param settlingShare share of the particulate moved to the cube below per {@code nominalSeconds} (capped at 0.1 per step)
 * @param cutoff the smallest index the field keeps
 * @param maximumCubes the most cubes the field holds
 * @param closedAirShare below this air share a cube is closed: it exchanges nothing, and its index uses this share
 */
public record PollutionSettings(double faceShare, double nominalSeconds, double halfLifeOpenSeconds,
        double halfLifeEnclosedSeconds, double washout, double settlingShare, double cutoff, int maximumCubes,
        double closedAirShare) {
    public static final PollutionSettings DEFAULT = new PollutionSettings(0.12, 20, 300, 3600, 4, 0.05, 1e-3, 8192,
            0.02);

    public PollutionSettings {
        positive("faceShare", faceShare);
        positive("nominalSeconds", nominalSeconds);
        positive("halfLifeOpenSeconds", halfLifeOpenSeconds);
        positive("halfLifeEnclosedSeconds", halfLifeEnclosedSeconds);
        positive("washout", washout);
        positive("settlingShare", settlingShare);
        positive("cutoff", cutoff);
        positive("closedAirShare", closedAirShare);
        if (!(settlingShare < 0.1)) {
            throw new IllegalArgumentException("settlingShare must be below 0.1: " + settlingShare);
        }
        if (maximumCubes < 1) {
            throw new IllegalArgumentException("maximumCubes must be at least 1: " + maximumCubes);
        }
        if (closedAirShare > 1.0) {
            throw new IllegalArgumentException("closedAirShare must not exceed 1: " + closedAirShare);
        }
    }

    private static void positive(String name, double value) {
        if (!(value > 0.0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite and positive: " + value);
        }
    }
}
