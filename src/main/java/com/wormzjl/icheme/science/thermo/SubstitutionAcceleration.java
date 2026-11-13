package com.wormzjl.icheme.science.thermo;

/**
 * The dominant-eigenvalue extrapolation of successive substitution (Michelsen and Mollerup, ch. 9): with the ratio
 * {@code lambda = |d_k|^2 / (d_(k-1) . d_k)} of two consecutive steps, the remaining geometric series adds
 * {@code lambda/(1 - lambda)} times the last step. The stability test and the equilibrium engine's two substitutions
 * (the flash and the three-slot balance) share these pieces, each in its old operation order (S16 of batch
 * 2026-11-13-codebase-review).
 *
 * <p>Two parts stay with their callers on purpose, because merging them would change results at the edges: the
 * stability test accepts the ratio when {@code denominator > 0 && numerator < denominator} (a quotient that rounds to
 * one is accepted there, refused by {@link #admissible}) and caps the added multiple itself
 * ({@code extra = MAXIMUM / largest}, not {@code factor * scale}).</p>
 */
public final class SubstitutionAcceleration {
    /** How far one extrapolation may move any log variable, in both users. */
    public static final double MAXIMUM_EXTRAPOLATION = 20.0;

    private SubstitutionAcceleration() {}

    /** The added multiple of the last step, {@code lambda / (1 - lambda)}. */
    public static double factor(double lambda) {
        return lambda / (1.0 - lambda);
    }

    /** The engine's acceptance of a ratio: strictly inside {@code (0, 1)} (false for NaN). */
    public static boolean admissible(double lambda) {
        return lambda > 0.0 && lambda < 1.0;
    }

    /**
     * The engine's cap: the scale that keeps {@code change * factor} (the largest move, {@code change} the largest
     * component of the last step) at most {@link #MAXIMUM_EXTRAPOLATION}; one where it already is.
     */
    public static double scale(double change, double factor) {
        double largest = change * factor;
        return largest > MAXIMUM_EXTRAPOLATION ? MAXIMUM_EXTRAPOLATION / largest : 1.0;
    }
}
