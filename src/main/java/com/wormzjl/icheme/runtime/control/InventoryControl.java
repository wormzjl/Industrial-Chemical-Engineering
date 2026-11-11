package com.wormzjl.icheme.runtime.control;

/**
 * Volume balance inversion, m³ and seconds. The hydraulic solve still enforces attainable flow and
 * all conservation.
 */
public final class InventoryControl {
    private InventoryControl() {}

    /**
     * The actuated volume flow that brings {@code volume} to {@code desired} in one {@code horizon}. {@code previous} is the
     * volume at the earlier sample, {@code elapsed} seconds before; NaN when there is no earlier sample. An {@code elapsed} of
     * 0 with an earlier sample is a reading that has not advanced since it.
     */
    public static double target(
            double volume,
            double desired,
            double actual,
            double previous,
            double elapsed,
            double horizon,
            boolean outlet) {
        boolean sampled = !Double.isNaN(previous);
        if (!Double.isFinite(volume + desired + actual + elapsed + horizon)
                || sampled && !Double.isFinite(previous)
                || volume < 0
                || desired < 0
                || actual < 0
                || elapsed < 0
                || !(horizon > 0))
            throw new IllegalArgumentException("Invalid inventory control sample");
        double direction = outlet ? 1 : -1;
        // The balance flow is the actuated stream that holds the inventory steady: the actuated flow corrected by the change
        // between two samples, or the actuated flow itself on a reading that has not advanced (it is what the last decision
        // set). A first decision (no earlier sample) has none: the actuated flow of that moment is not the balance (a draining
        // drum's outlet read 0.737 m³/s on a 0.199 m³/s feed, FEED_STEP_HOLD_REVIEW.md), so it acts on the level error alone.
        double balance =
                !sampled
                        ? 0
                        : elapsed > 0
                                ? Math.max(0, actual + direction * (volume - previous) / elapsed)
                                : actual;
        return Math.max(0, balance + direction * (volume - desired) / horizon);
    }
}
