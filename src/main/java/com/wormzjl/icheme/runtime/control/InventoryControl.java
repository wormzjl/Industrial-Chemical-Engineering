package com.wormzjl.icheme.runtime.control;

/**
 * Volume balance inversion, m³ and seconds. The hydraulic solve still enforces attainable flow and
 * all conservation.
 *
 * <p>The law is a balance over the interval between two samples, and a correction over the time its output stays in
 * force (code base review, LEVEL_LOOP_REVIEW.md options A1 and A2). The inflow it does not actuate is the volume change
 * over that interval plus the actuated stream's mean flow over the same interval. The mean comes from the volume the
 * island booked through the valve, not from the flow at the sample's end. An output acts only from the island's next
 * slice. The interval then ends with part of it under the earlier output, and an end-of-interval flow biases the
 * estimate by that part times the change of output. With that bias the loop is stable only below 0.38 of a cadence of
 * dead time. With the mean it is stable below a full cadence: its poles are z² - f z + f, |z| = √f. The correction
 * spreads the inventory error over the horizon {@link #horizon}: at least a cadence, and the time the output is held. A
 * one-cadence correction held for n cadences has its pole at 1 - n, which is unstable for n > 2.
 */
public final class InventoryControl {
    private InventoryControl() {}

    /**
     * The actuated volume flow that brings {@code volume} to {@code desired} over {@code horizon}. {@code previous} is the
     * volume at the earlier sample, {@code elapsed} (> 0) seconds before; NaN when there is no earlier sample.
     * {@code meanFlow} is the actuated stream's mean volume flow over those {@code elapsed} seconds. A reading that has
     * not advanced since the earlier sample has no balance (a zero interval): the caller holds the output in force, the
     * best estimate there is, and does not call this.
     */
    public static double target(
            double volume,
            double desired,
            double meanFlow,
            double previous,
            double elapsed,
            double horizon,
            boolean outlet) {
        boolean sampled = !Double.isNaN(previous);
        if (!Double.isFinite(volume + desired + meanFlow + elapsed + horizon)
                || sampled && !Double.isFinite(previous)
                || sampled && !(elapsed > 0)
                || volume < 0
                || desired < 0
                || meanFlow < 0
                || elapsed < 0
                || !(horizon > 0))
            throw new IllegalArgumentException("Invalid inventory control sample");
        double direction = outlet ? 1 : -1;
        // The balance flow holds the inventory steady: the actuated stream's mean over the interval corrected by the change
        // over the same interval. A first decision (no earlier sample) has none: the actuated flow of that moment is not the
        // balance (a draining drum's outlet read 0.737 m³/s on a 0.199 m³/s feed, FEED_STEP_HOLD_REVIEW.md), so it acts on
        // the level error alone.
        double balance = !sampled ? 0 : Math.max(0, meanFlow + direction * (volume - previous) / elapsed);
        return Math.max(0, balance + direction * (volume - desired) / horizon);
    }

    /**
     * The horizon, s: the cadence {@code cadence}, or longer where the output stays in force longer. {@code elapsed} is the
     * process time between the last two samples (the decisions' spacing in process time, which a catch-up stretches) and
     * {@code slice} the island's slice (an output acts on whole slices). A value that is not a positive number is left out.
     */
    public static double horizon(double cadence, double elapsed, double slice) {
        if (!(cadence > 0) || !Double.isFinite(cadence)) throw new IllegalArgumentException("Invalid control cadence");
        double h = cadence;
        if (elapsed > h && Double.isFinite(elapsed)) h = elapsed;
        if (slice > h && Double.isFinite(slice)) h = slice;
        return h;
    }
}
