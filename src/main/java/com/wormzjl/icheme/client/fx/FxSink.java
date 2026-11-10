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
