package com.wormzjl.icheme.science.flowsheet.sizing;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A pressure path (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} section 3.6): from a pressure the sheet holds to the
 * next one, through every line and unit side between, each at its sized drop at design flow. All in bar.
 *
 * <p>A path starts where a pressure is held: a feed, a pump's or a compressor's outlet, a vessel's or a column's outlet, a valve's
 * set outlet. It ends where the next is: a valve set to an outlet, which takes what is left; a column's tray; a drum set to a
 * pressure; a tank. Such an end is <em>set</em>, and its spare reads {@link Limit#PATH_SPARE}: a negative spare is red. A path that
 * ends at a product, a pump's suction or a drum at its inlet's pressure meets nothing that holds a pressure; its end is what the
 * doors' drops deliver, and its spare, what they budget past the sized drops, lights no lamp.
 *
 * @param start the unit, or the feed's stream, it starts from
 * @param from  the pressure there, bar
 * @param head  the liquid's static head over grade there, bar: a column's tray or sump, its receiver's level
 * @param end   the unit, or the product's stream, it ends at
 * @param to    the pressure there, bar: the one the sheet sets, or the doors' delivery
 * @param set   whether {@code to} is a pressure the sheet sets
 * @param taker the valve at its end, which takes the spare; null for none
 */
public record PressurePath(String start, double from, double head, String end, double to, boolean set, @Nullable String taker, List<Step> steps) {
    public PressurePath {
        steps = List.copyOf(steps);
    }

    /**
     * One step of the stair.
     *
     * @param item    the line's stream, or the unit or member whose side it is
     * @param passage {@code line}, or the side's passage: the door's drop it replaces
     * @param lost    at design flow, by its size: the door's estimate where it has none
     * @param typed   the door's estimate; a line has none, as the run's lines lose nothing
     */
    public record Step(String item, String passage, double lost, double typed) {
        public static final String LINE = "line";
    }

    /** What the path has between its ends, bar. */
    public double available() {
        return from + head - to;
    }

    /** What its steps lose at design flow, bar. */
    public double lost() {
        double lost = 0;
        for (var step : steps) lost += step.lost();
        return lost;
    }

    /** What is left: a valve at its end takes it. */
    public double spare() {
        return available() - lost();
    }

    /** Its lamp: a set end's spare; none for an end the sheet does not set. */
    public Rating rating() {
        return set ? new Rating(List.of(Limit.PATH_SPARE.read(spare()))) : Rating.NONE;
    }

    /** Whether {@code item} is one of its steps. */
    public boolean passes(String item) {
        for (var step : steps) if (step.item().equals(item)) return true;
        return false;
    }
}
