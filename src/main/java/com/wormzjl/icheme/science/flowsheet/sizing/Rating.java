package com.wormzjl.icheme.science.flowsheet.sizing;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * What one size does against its limits (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} sections 3.1 and 3.5). A rate
 * function gives one for any size, and {@link Selection} calls that same function, so one rule sizes and rates.
 *
 * @param readings the limits, in the order that breaks a tie for what is closest
 */
public record Rating(List<Reading> readings) {
    /** Nothing to rate. */
    public static final Rating NONE = new Rating(List.of());

    public Rating {
        readings = List.copyOf(readings);
    }

    public Lamp lamp() {
        var lamp = readings.isEmpty() ? Lamp.NONE : Lamp.GREEN;
        for (var reading : readings) if (reading.lamp().compareTo(lamp) > 0) lamp = reading.lamp();
        return lamp;
    }

    /** What limits this size: the worst miss, or if nothing is missed the row nearest its limit. Null with nothing to rate. */
    public @Nullable Reading closest() {
        Reading worst = null, nearest = null;
        for (var reading : readings) {
            if (!reading.met() && (worst == null || reading.lamp().compareTo(worst.lamp()) > 0 || reading.lamp() == worst.lamp() && reading.load() > worst.load())) worst = reading;
            if (reading.limit().rank() == Limit.Rank.CLOSEST && (nearest == null || reading.load() > nearest.load())) nearest = reading;
        }
        return worst != null ? worst : nearest;
    }

    /** The reading of {@code limit}, null if this size has none. */
    public @Nullable Reading reading(Limit limit) {
        for (var reading : readings) if (reading.limit() == limit) return reading;
        return null;
    }

    /** Whether Auto can stop here: every limit met, but a {@linkplain Limit.Rank#PAID paid} row may stand missed. */
    boolean settled() {
        for (var reading : readings) if (reading.limit().rank() != Limit.Rank.PAID && !reading.met()) return false;
        return true;
    }

    /** How far past the Musts it stands, 0 if it meets them. */
    double mustMiss() {
        return miss(Limit.Kind.MUST);
    }

    /** How far past the Shoulds it stands, 0 if it meets them. */
    double shouldMiss() {
        return miss(Limit.Kind.SHOULD);
    }

    private double miss(Limit.Kind kind) {
        double miss = 0;
        for (var reading : readings) if (reading.limit().kind() == kind && !reading.met()) miss = Math.max(miss, reading.load());
        return miss;
    }
}
