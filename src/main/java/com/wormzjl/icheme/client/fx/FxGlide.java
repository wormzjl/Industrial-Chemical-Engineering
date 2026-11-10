package com.wormzjl.icheme.client.fx;

import java.util.Arrays;

/**
 * Eases a set of values from where they stood to a snapshot's new values over {@link #SECONDS}, starting when the
 * snapshot arrives (CUSTOM_SHADER_SYSTEM_PLAN.md section 4.1). The engine publishes about every 5 s; a 2 s glide hides the
 * step without lagging a whole period behind. The ease is the smoothstep 3k² − 2k³ of the elapsed share k, the same curve the
 * effect vertex shaders use ({@code fx_flame.vsh}, {@code fx_plume.vsh}), so the SHADER and COMPATIBLE paths agree.
 * Times are in seconds of online game time ({@link #seconds}), so a paused game freezes the glide. Not thread-safe;
 * names no Minecraft class.
 */
public final class FxGlide {
    /** The glide's length, s. */
    public static final double SECONDS = 2.0;

    private double[] from;
    private double[] to;
    private double start = Double.NEGATIVE_INFINITY;

    /** A glide resting at {@code initial}. */
    public FxGlide(double... initial) {
        from = initial.clone();
        to = initial.clone();
    }

    /** Game time in seconds: (game time in ticks + partial tick) / 20, at the nominal 20 TPS. */
    public static double seconds(long gameTime, double partialTick) {
        return (gameTime + partialTick) / 20.0;
    }

    /** The ease at elapsed share {@code k}: 0 at or before 0, 1 at or after 1, smoothstep between. */
    public static double ease(double k) {
        if (!(k > 0)) return 0;
        if (k >= 1) return 1;
        return k * k * (3 - 2 * k);
    }

    /** The ease of a glide that started at {@code start}, at {@code now} (both s). */
    public static double ease(double start, double now) {
        return ease((now - start) / SECONDS);
    }

    /**
     * A snapshot arrived at {@code now}: the glide restarts from where it stands now towards {@code next}. A different
     * number of values snaps to {@code next} at once.
     */
    public void arrive(double[] next, double now) {
        if (next.length != to.length) {
            from = next.clone();
        } else {
            from = at(now);
        }
        to = next.clone();
        start = now;
    }

    /** The values at {@code now}. */
    public double[] at(double now) {
        double e = ease(start, now);
        double[] out = new double[to.length];
        for (int i = 0; i < out.length; i++) out[i] = from[i] + (to[i] - from[i]) * e;
        return out;
    }

    /** Value {@code i} at {@code now}. */
    public double at(int i, double now) {
        return from[i] + (to[i] - from[i]) * ease(start, now);
    }

    /** Whether the glide has reached its target at {@code now}. */
    public boolean settled(double now) {
        return ease(start, now) >= 1;
    }

    /** The values the glide started from (a vertex's "old" values). */
    public double[] from() {
        return from.clone();
    }

    /** The values it eases to (a vertex's "new" values). */
    public double[] to() {
        return to.clone();
    }

    /**
     * As {@link #from()} without the copy, for a caller that only reads it before the next {@link #arrive}: the per-frame
     * writes. Never modify it.
     */
    public double[] fromShared() {
        return from;
    }

    /** As {@link #to()} without the copy, on the terms of {@link #fromShared()}. */
    public double[] toShared() {
        return to;
    }

    /** When the glide started, s; negative infinity before the first snapshot. */
    public double start() {
        return start;
    }

    @Override
    public String toString() {
        return "FxGlide" + Arrays.toString(from) + "->" + Arrays.toString(to) + "@" + start;
    }
}
