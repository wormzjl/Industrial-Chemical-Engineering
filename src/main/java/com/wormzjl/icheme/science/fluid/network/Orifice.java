package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * A restriction orifice plate (batch 2026-11-15-fluid-interfaces, decisions D1 and D2): a fixed pressure loss on its host
 * pipe, set by its bore ratio beta = d/D. It measures nothing. Its permanent loss on the pipe's own velocity head is
 * K(beta) = (1 - beta^4)(1 - beta^1.9) / (C^2 beta^4), C = 0.61: the differential pressure of ISO 5167-2 times its
 * permanent loss fraction 1 - beta^1.9. K does not depend on the bore, so a resize keeps it. It enters the pipe law as the
 * host's {@link PipeResistance.Geometry#minorLoss} plus K: no solver change, the same K both ways, local density, no
 * expansion factor; the 100 m/s cap stays the gas limit.
 *
 * <table>
 *   <caption>K at the ratios of the plan (section 4.1)</caption>
 *   <tr><th>beta</th><td>0.20</td><td>0.30</td><td>0.50</td><td>0.70</td><td>0.80</td></tr>
 *   <tr><th>K</th><td>1 598</td><td>296</td><td>29.5</td><td>4.19</td><td>1.34</td></tr>
 * </table>
 */
public final class Orifice {
    /** The discharge coefficient of a sharp-edged plate. */
    public static final double DISCHARGE = .61;
    /** The ratios a plate takes: {@link #MINIMUM} to {@link #MAXIMUM} in steps of {@link #STEP} (ISO 5167's range). */
    public static final double MINIMUM = .20, MAXIMUM = .80, STEP = .05, DEFAULT = .50;

    private Orifice() {}

    /** The permanent loss coefficient K on the pipe's velocity head at ratio {@code beta} (one of {@link #valid}). */
    public static double loss(double beta) {
        if (!valid(beta)) throw new IllegalArgumentException("Invalid orifice ratio " + beta);
        double b4 = beta * beta * beta * beta;
        return (1 - b4) * (1 - DeterministicMath.pow(beta, 1.9)) / (DISCHARGE * DISCHARGE * b4);
    }

    /** Whether {@code beta} is a ratio a plate takes: inside the range, on the 0.05 grid. */
    public static boolean valid(double beta) {
        return Double.isFinite(beta) && beta >= MINIMUM - 1e-9 && beta <= MAXIMUM + 1e-9 && Math.abs(beta / STEP - Math.rint(beta / STEP)) < 1e-6;
    }

    /** {@code beta} on the grid, exactly: the twentieths written as the decimal a player reads. */
    public static double snap(double beta) {
        return Math.rint(beta / STEP) / 20;
    }

    /** The next ratio up from {@code beta}, the smallest after the largest: what a sneak-use on the item steps to. */
    public static double next(double beta) {
        double up = snap(beta + STEP);
        return up > MAXIMUM + 1e-9 ? MINIMUM : up;
    }
}
