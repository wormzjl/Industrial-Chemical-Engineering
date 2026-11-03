package com.wormzjl.icheme.science.flowsheet;

import java.util.Objects;

/**
 * A connection point on a unit (batch 2026-11-02-process-simulator, plan section 3.11). Its id is a stable string, and a block's
 * row names its port by the row's own id, never its position, so removing PA 1 does not move PA 2's wires.
 *
 * @param required an inlet the unit cannot run without; a free pair's inlet is not, for a utility takes its heat
 * @param utility  a heat side's inlet that sets the flow of a sheet feed of water alone wired to it ({@code COOLER_MODEL_PLAN.md}
 *                 section 8): the unit, not the feed, says how much comes
 */
public record Port(String id, Direction direction, boolean required, boolean utility) {
    public enum Direction {
        IN,
        OUT
    }

    public Port {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(direction, "direction");
    }

    public Port(String id, Direction direction, boolean required) {
        this(id, direction, required, false);
    }

    public static Port in(String id) {
        return new Port(id, Direction.IN, true);
    }

    /** An inlet that may stay free. */
    public static Port optionalIn(String id) {
        return new Port(id, Direction.IN, false);
    }

    /** A heat side's inlet that may stay free, and that sets the flow of a utility feed wired to it. */
    public static Port utilityIn(String id) {
        return new Port(id, Direction.IN, false, true);
    }

    public static Port out(String id) {
        return new Port(id, Direction.OUT, false);
    }
}
