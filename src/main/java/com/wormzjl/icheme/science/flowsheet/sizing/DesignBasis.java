package com.wormzjl.icheme.science.flowsheet.sizing;

import org.jetbrains.annotations.Nullable;

/**
 * The sizing stage's three entries for the whole sheet (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} section 3.2).
 *
 * @param designFlow what every limit applies at, over the steady flow: 1.10 is 110 %
 * @param lineLength a line's planned length with its fittings (m), unless it holds its own
 * @param paFall     a pumparound's fall across its cooler (K): it gives the circulation the column model does not have
 */
public record DesignBasis(double designFlow, double lineLength, double paFall) {
    public static final DesignBasis DEFAULT = new DesignBasis(1.10, 50, 60);

    public DesignBasis {
        if (!(designFlow > 0) || !(lineLength > 0) || !(paFall > 0) || !Double.isFinite(designFlow + lineLength + paFall)) throw new IllegalArgumentException("Invalid design basis");
    }

    /** The length of a line that holds {@code own} (m), or the sheet's with none. */
    public double length(@Nullable Double own) {
        return own != null ? own : lineLength;
    }
}
