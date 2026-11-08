package com.wormzjl.icheme.runtime.control;

/**
 * Volume balance inversion, m³ and seconds. The hydraulic solve still enforces attainable flow and
 * all conservation.
 */
public final class InventoryControl {
    private InventoryControl() {}

    public static double target(
            double volume,
            double desired,
            double actual,
            double previous,
            double elapsed,
            double horizon,
            boolean outlet) {
        if (!Double.isFinite(volume + desired + actual + previous + elapsed + horizon)
                || volume < 0
                || desired < 0
                || actual < 0
                || elapsed < 0
                || !(horizon > 0))
            throw new IllegalArgumentException("Invalid inventory control sample");
        double direction = outlet ? 1 : -1;
        double balance =
                elapsed > 0
                        ? Math.max(0, actual + direction * (volume - previous) / elapsed)
                        : actual;
        return Math.max(0, balance + direction * (volume - desired) / horizon);
    }
}
