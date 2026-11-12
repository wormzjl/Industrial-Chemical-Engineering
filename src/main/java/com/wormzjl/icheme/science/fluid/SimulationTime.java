package com.wormzjl.icheme.science.fluid;

/**
 * Process time against online time (batch 2026-11-11-minecraft-time-delta, W1): one online tick advances
 * {@code factor / 20} s of process time. The factor is the server's {@code fluid.timeScale}, captured at server start
 * and carried on the network model ({@link com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics#time}).
 *
 * <p>Two kinds of time meet here. Play time is the engine's schedule, in ticks: slices, cadence, fences, buckets, the
 * wall budget, "since" and "next check at". Process time is what the physics integrates: a solved interval's seconds,
 * a flow over a slice, a duty's joules, a pump's wear, a fuel's burn. Only process durations pass through
 * {@link #seconds}; a schedule quantity stays in ticks. {@link #ONLINE} (factor 1) is the engine's own default (tests,
 * a model built without the setting), and at it every conversion is bit-identical to {@code ticks / 20.0}.
 */
public record SimulationTime(int factor) {
    /** Online ticks per play second at the nominal 20 TPS. */
    public static final int TICKS_PER_SECOND = 20;
    /** The factor range the setting accepts: 1 (process time is play time) to 72 (a Minecraft day is 24 h). */
    public static final int MINIMUM_FACTOR = 1, MAXIMUM_FACTOR = 72;
    /**
     * The server setting's default ({@code fluid.timeScale}), and so the factor a player's world runs at: the hold-fuzz
     * harnesses default to it too (batch 2026-11-11-valve-followups, item 5).
     */
    public static final int DEFAULT_FACTOR = 4;
    /** The engine's own default: process time is play time. */
    public static final SimulationTime ONLINE = new SimulationTime(1);

    public SimulationTime {
        if (factor < MINIMUM_FACTOR || factor > MAXIMUM_FACTOR)
            throw new IllegalArgumentException("Time factor " + factor + " outside " + MINIMUM_FACTOR + " to " + MAXIMUM_FACTOR);
    }

    public static SimulationTime of(int factor) {
        return factor == 1 ? ONLINE : new SimulationTime(factor);
    }

    /** The process seconds {@code ticks} online ticks advance: {@code ticks x factor / 20}. */
    public double seconds(double ticks) {
        return ticks * factor / TICKS_PER_SECOND;
    }

    /** The online ticks, unrounded, in which {@code seconds} of process time pass: {@code seconds x 20 / factor}. */
    public double ticks(double seconds) {
        return seconds * TICKS_PER_SECOND / factor;
    }

    /** Play seconds of {@code ticks} online ticks, whatever the factor: for "since", "next check at", ages. */
    public static double playSeconds(double ticks) {
        return ticks / TICKS_PER_SECOND;
    }

    /** Process seconds per play second. */
    public double perPlaySecond() {
        return factor;
    }

    /** Process seconds in one online tick. */
    public double tickSeconds() {
        return seconds(1);
    }
}
