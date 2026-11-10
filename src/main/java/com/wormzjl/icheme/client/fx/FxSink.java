package com.wormzjl.icheme.client.fx;

/**
 * Where an emitter writes its quads (CUSTOM_SHADER_SYSTEM_PLAN.md section 4.6). An emitter describes each quad once, with
 * the values it had when its glide started ("old") and the values the glide eases to ("new"); the sink then either packs
 * both into a cached vertex buffer for the vertex shader to ease, face the camera and flicker (SHADER path), or eases and
 * faces it on the CPU this frame (COMPATIBLE path). Positions are emitter-local metres (from {@link FxEmitter#x()} etc.),
 * sizes are half-widths in metres. A sink takes at most the quads it was allotted and drops the rest, so an emitter writes
 * its most important quads first.
 * <p><b>Parameter bytes</b> ({@code params}, packed 0xAARRGGBB, each 0 to 255), by {@link FxKind}:
 * <ul>
 * <li>FLAME: R is replaced by each end's position along the flame (0 root, 1 tip); G is the heat (0 to 1, how hot the core
 * burns, 1100 to 1900 K); B is the soot (0 clean, 1 sooty: darkens the upper flame); A is the opacity.</li>
 * <li>SMOKE, PLUME: RGB is the albedo, A the density.</li>
 * <li>HAZE: A is the shimmer's strength (0 none, 1 about 4 px of displacement); RGB unused.</li>
 * </ul>
 * {@code seed} (0 to 4095) varies the noise and the flipbook frame per quad.
 */
public interface FxSink {
    /** Seeds are 12 bits. */
    int SEED_MASK = 0xFFF;

    /** One point of a quad: its old and new emitter-local position, m, and its old and new half-size, m. */
    record Point(double oldX, double oldY, double oldZ, double newX, double newY, double newZ, double oldHalf, double newHalf) {
        /** A point that does not move. */
        public static Point still(double x, double y, double z, double half) {
            return new Point(x, y, z, x, y, z, half, half);
        }

        /** The point eased by {@code e} (0 old, 1 new): x, y, z and half-size. */
        public double x(double e) {
            return oldX + (newX - oldX) * e;
        }

        public double y(double e) {
            return oldY + (newY - oldY) * e;
        }

        public double z(double e) {
            return oldZ + (newZ - oldZ) * e;
        }

        public double half(double e) {
            return oldHalf + (newHalf - oldHalf) * e;
        }
    }

    /** A camera-facing square puff centred on {@code centre}. */
    void sprite(Point centre, int params, int seed);

    /**
     * A camera-facing puff that travels (batch 2026-11-10-custom-shader-system, plan section 4.5): over each of its emitter's
     * {@link FxEmitter#driftPeriod()} it moves from {@code start} by {@code (dx, dy, dz)} m (at most {@link #DRIFT_SCALE}),
     * grows by {@link #GROWTH} of its half-size and fades in and out ({@link #envelope}); its phase is offset by
     * {@code seed} ({@link #phase}). Puffs of one segment with staggered seeds make a steady stream: smoke, a plume, drops.
     */
    void puff(Point start, double dx, double dy, double dz, int params, int seed);

    /** The longest drift of one period, m: the SHADER path packs the drift into the vertex normal's bytes. */
    double DRIFT_SCALE = 16;
    /** A travelling puff's growth over one period, as a share of its half-size. */
    double GROWTH = .5;

    /** A travelling puff's phase at {@code seconds}: fract(seconds / period + seed / 4096), as {@code fx_plume.vsh}. */
    static double phase(double seconds, double period, int seed) {
        double p = seconds / Math.max(period, 1e-3) + (seed & SEED_MASK) / 4096.0;
        return p - Math.floor(p);
    }

    /** A puff's drift as the SHADER path packs it into the vertex normal: the drift over {@link #DRIFT_SCALE}, its length at most 1. */
    static float[] drift(double dx, double dy, double dz) {
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz), scale = Math.max(length, DRIFT_SCALE);
        return new float[]{(float) (dx / scale), (float) (dy / scale), (float) (dz / scale)};
    }

    /** A travelling puff's opacity over its period: sin(π phase); two puffs half a period apart sum to 1 to 1.41. */
    static double envelope(double phase) {
        return Math.sin(Math.PI * Math.max(0, Math.min(1, phase)));
    }

    /** A seed for puff {@code k} of {@code of} in one segment, their phases evenly staggered, plus {@code noise} (any) in the free bits. */
    static int staggered(int k, int of, int noise) {
        int base = (int) Math.round(4096.0 * Math.floorMod(k, Math.max(of, 1)) / Math.max(of, 1)) & SEED_MASK;
        return base + (Math.floorMod(noise, 64) - 32) & SEED_MASK;
    }

    /**
     * One segment of a ribbon from end {@code a} to end {@code b}, turned about its own axis to face the camera; {@code va}
     * and {@code vb} are the ends' positions along the whole flame, 0 to 1.
     */
    void ribbon(Point a, double va, Point b, double vb, int params, int seed);

    /** Quads this sink still takes. */
    int remaining();

    /** Packs four bytes, each clamped to 0..255, as 0xAARRGGBB. */
    static int params(double r, double g, double b, double a) {
        return unit(a) << 24 | unit(r) << 16 | unit(g) << 8 | unit(b);
    }

    private static int unit(double v) {
        return (int) Math.round(Math.max(0, Math.min(1, Double.isNaN(v) ? 0 : v)) * 255);
    }
}
