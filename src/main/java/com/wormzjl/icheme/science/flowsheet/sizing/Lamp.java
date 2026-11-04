package com.wormzjl.icheme.science.flowsheet.sizing;

/** A row's state (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} section 3.1), in rising order. */
public enum Lamp {
    /** Nothing to rate: a line with no flow has no size and is not red. */
    NONE,
    /** Meets every limit. */
    GREEN,
    /** Misses a Should: outside good practice, and it still runs. */
    YELLOW,
    /** Misses a Must. */
    RED
}
