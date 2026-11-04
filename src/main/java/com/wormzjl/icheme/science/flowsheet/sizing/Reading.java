package com.wormzjl.icheme.science.flowsheet.sizing;

/**
 * One limit read on one size (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} section 3.1).
 *
 * @param value what the size gives, in the row's unit
 * @param low   the limit's floor, negative infinity if it has none
 * @param high  the limit's ceiling, positive infinity if it has none
 */
public record Reading(Limit limit, double value, double low, double high) {
    /** How close to a limit still counts as on it. */
    private static final double ON_IT = 1e-9;

    static Reading of(Limit limit, double value, double number) {
        if (limit.kind() == Limit.Kind.RULE) throw new IllegalArgumentException("A rule lights no lamp: " + limit);
        var bound = limit.bound();
        return switch (bound.sense()) {
            case MOST -> new Reading(limit, value, Double.NEGATIVE_INFINITY, number);
            case LEAST, ABOVE -> new Reading(limit, value, number, Double.POSITIVE_INFINITY);
            case WITHIN -> new Reading(limit, value, bound.low(), number);
        };
    }

    /** Whether the size meets this limit. A value that is no number does not. */
    public boolean met() {
        boolean floor = limit.bound().sense() == Bound.Sense.ABOVE ? value > low : value >= low * (1 - ON_IT);
        return floor && value <= high * (1 + ON_IT);
    }

    public Lamp lamp() {
        return met() ? Lamp.GREEN : limit.kind() == Limit.Kind.MUST ? Lamp.RED : Lamp.YELLOW;
    }

    /** How near the limit: 1 is on it, more is past it. */
    public double load() {
        double ceiling = high == Double.POSITIVE_INFINITY ? 0 : value / high;
        double floor = low == Double.NEGATIVE_INFINITY ? 0 : value <= 0 ? Double.POSITIVE_INFINITY : low / value;
        double load = Math.max(ceiling, floor);
        return Double.isNaN(load) ? Double.POSITIVE_INFINITY : load;
    }
}
