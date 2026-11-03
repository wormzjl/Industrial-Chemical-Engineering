package com.wormzjl.icheme.science.flowsheet.sizing;

import java.util.List;

/**
 * What a row of the {@link Limit} table holds (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md}): a ceiling, a floor or
 * a band, as one number, as {@linkplain Step steps} along an axis, or {@linkplain #given given} by the rating.
 *
 * @param sense which side of the number is good
 * @param low   a band's floor, NaN for the other senses
 * @param axis  what picks a step
 * @param steps the row's numbers, the last one open at the top; none if the rating gives it
 */
public record Bound(Sense sense, double low, Axis axis, List<Step> steps) {
    public enum Sense {
        /** At most. */
        MOST,
        /** At least. */
        LEAST,
        /** Strictly above: a limit that a value on it misses. */
        ABOVE,
        /** From {@link Bound#low} to the row's number. */
        WITHIN
    }

    /** What picks a step of a row. */
    public enum Axis {
        NONE,
        /** A line's bore, m. */
        BORE,
        /** The pressure over ambient, barg. */
        GAUGE,
        /** The absolute pressure, bara. */
        ABSOLUTE,
        /** An ejector's suction, mbar. */
        SUCTION
    }

    /**
     * One step: {@code value} up to {@code edge}.
     *
     * @param closed whether the edge itself belongs to this step
     */
    public record Step(double edge, boolean closed, double value) {
        /** Under {@code edge}: the edge belongs to the next step. */
        public static Step under(double edge, double value) {
            return new Step(edge, false, value);
        }

        /** Up to {@code edge} and on it. */
        public static Step to(double edge, double value) {
            return new Step(edge, true, value);
        }

        /** Everything past the last edge. */
        public static Step rest(double value) {
            return new Step(Double.POSITIVE_INFINITY, true, value);
        }

        boolean holds(double key) {
            return closed ? key <= edge : key < edge;
        }
    }

    public Bound {
        steps = List.copyOf(steps);
    }

    public static Bound most(double value) {
        return new Bound(Sense.MOST, Double.NaN, Axis.NONE, List.of(Step.rest(value)));
    }

    public static Bound least(double value) {
        return new Bound(Sense.LEAST, Double.NaN, Axis.NONE, List.of(Step.rest(value)));
    }

    public static Bound above(double value) {
        return new Bound(Sense.ABOVE, Double.NaN, Axis.NONE, List.of(Step.rest(value)));
    }

    public static Bound within(double low, double high) {
        return new Bound(Sense.WITHIN, low, Axis.NONE, List.of(Step.rest(high)));
    }

    public static Bound most(Axis axis, Step... steps) {
        return new Bound(Sense.MOST, Double.NaN, axis, List.of(steps));
    }

    public static Bound least(Axis axis, Step... steps) {
        return new Bound(Sense.LEAST, Double.NaN, axis, List.of(steps));
    }

    /** A limit the rating gives: a nozzle's bore, the surface a duty needs. */
    public static Bound given(Sense sense) {
        return new Bound(sense, Double.NaN, Axis.NONE, List.of());
    }

    /** The row's number at {@code key} on its axis. */
    double at(double key) {
        if (steps.isEmpty()) throw new IllegalStateException("The rating gives this limit");
        for (var step : steps) if (step.holds(key)) return step.value();
        throw new IllegalArgumentException("Outside the row's steps: " + key);
    }
}
