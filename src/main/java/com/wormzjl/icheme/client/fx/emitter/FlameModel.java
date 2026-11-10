package com.wormzjl.icheme.client.fx.emitter;

import com.wormzjl.icheme.client.fx.FxSink;

/**
 * A flare stack's flame (batch 2026-11-10-custom-shader-system, plan sections 2 and 4.5, W3). Pure; names no Minecraft class.
 * <ul>
 * <li><b>Length.</b> API 521, as {@code Flare.flameLength}: L = exp(0.4562 ln Q − 5.3603) m, Q the heat released in W (about
 * 30 m at a 0.3 m tip's 212 MW rating).</li>
 * <li><b>Bend.</b> API 521's flame distortion by a cross-wind: the flame's end stands ΣΔx downwind and ΣΔy up from the tip,
 * both as shares of L, by the ratio of the wind to the exit velocity, u∞ / U_j ({@link #distortion}). The centre line is a
 * quadratic curve that leaves the tip upward and reaches that end.</li>
 * <li><b>Width.</b> A jet flame is about six times as long as it is wide: half-width {@link #HALF_WIDTH} L, reached at
 * 0.7 L and held nearly to the end, the tip's radius at the root (so the pilots show beside it). The ribbons stay wide
 * to the end because the shader closes the flame: its tongues, its wandering end and the pockets that tear off it
 * ({@code fx_flame.fsh}; owner 2026-11-10, "still looks like plumes, it lacks the variability of actual flames").</li>
 * <li><b>Look.</b> 2 or 3 layered ribbons of {@link #SEGMENTS} segments, hotter toward the core, more with the load;
 * three pilot flames at the rim always, drawn last. At most 27 quads (plan: 48). Each ribbon keeps one seed along its
 * length and turns about the centre line's tangent at each joint, so its noise and its width run on across the joints
 * (visual review 2026-11-10, items 6 and 9). The seeds start from the flare's own, so no two flares burn alike.</li>
 * </ul>
 * Its glided values ({@link #values}): L, the end's x, y, z from the tip, the half-width, heat, soot, opacity, the tip's radius.
 */
public final class FlameModel {
    /** Segments per ribbon. */
    public static final int SEGMENTS = 8;
    /** The widest half-width over the length. */
    public static final double HALF_WIDTH = .09;
    /** Pilot flames at the rim, each one segment of {@link #PILOT_LENGTH} m. */
    public static final int PILOTS = 3;
    public static final double PILOT_LENGTH = 1;
    /**
     * API 521's flame distortion (the figure "approximate flame distortion due to lateral wind on jet velocity from flare
     * stack"), read approximately: u∞/U_j, then ΣΔx/L and ΣΔy/L. Interpolated in log(u∞/U_j) above 0.01, linearly below.
     */
    static final double[] RATIO = {.01, .02, .05, .1, .2, .5, 1}, DOWNWIND = {.2, .3, .5, .63, .76, .88, .93}, UP = {.98, .95, .86, .77, .64, .47, .36};
    static final int LENGTH = 0, END = 1, HALF = 4, HEAT = 5, SOOT = 6, OPACITY = 7, TIP = 8, SIZE = 9;

    private FlameModel() {
    }

    /** The flame's end from the tip, ΣΔx/L downwind and ΣΔy/L up, for the wind over the exit velocity {@code ratio}. */
    public static double[] distortion(double ratio) {
        double r = Double.isNaN(ratio) ? 0 : Math.max(0, ratio);
        if (r <= RATIO[0]) return new double[]{DOWNWIND[0] * r / RATIO[0], 1 + (UP[0] - 1) * r / RATIO[0]};
        if (r >= RATIO[RATIO.length - 1]) return new double[]{DOWNWIND[RATIO.length - 1], UP[RATIO.length - 1]};
        int i = 1;
        while (RATIO[i] < r) i++;
        double k = Math.log(r / RATIO[i - 1]) / Math.log(RATIO[i] / RATIO[i - 1]);
        return new double[]{DOWNWIND[i - 1] + (DOWNWIND[i] - DOWNWIND[i - 1]) * k, UP[i - 1] + (UP[i] - UP[i - 1]) * k};
    }

    /**
     * The glided values of a flame of {@code length} m in a wind of {@code windX}, {@code windZ} m/s from a jet at
     * {@code exitVelocity} m/s, burning {@code share} of its rated heat with soot index {@code soot}, from a tip of radius
     * {@code tipRadius} m. No length, no flame: the values collapse onto the tip.
     */
    public static double[] values(double length, double windX, double windZ, double exitVelocity, double share, double soot, double tipRadius) {
        double l = Math.max(0, length), wind = Math.hypot(windX, windZ);
        double[] d = distortion(wind / Math.max(exitVelocity, 1));
        double ux = wind > 0 ? windX / wind : 0, uz = wind > 0 ? windZ / wind : 0, s = Math.max(0, Math.min(1, share));
        return new double[]{l, d[0] * l * ux, d[1] * l, d[0] * l * uz, Math.max(HALF_WIDTH * l, tipRadius), .5 + .5 * Math.sqrt(s),
                Math.max(0, Math.min(1, soot)), l > 0 ? .85 : 0, tipRadius};
    }

    /** Ribbons for a flame burning {@code share} of its rating: 2 or 3, none without a flame. */
    public static int ribbons(double length, double share) {
        return length > 0 ? 2 + (int) Math.round(Math.max(0, Math.min(1, share))) : 0;
    }

    /** The point at {@code v} (0 tip, 1 end) of the centre line of values {@code f}: x, y, z from the tip. */
    public static double[] centre(double[] f, double v) {
        // A quadratic curve: leaves the tip toward its control point, high over the tip, and ends at the flame's end still
        // rising (about 40° at u∞/U_j 0.05 to 0.1), not streaming flat downwind.
        double cx = .3 * f[END], cy = .85 * f[END + 1], cz = .3 * f[END + 2];
        double a = 2 * v * (1 - v), b = v * v;
        return new double[]{a * cx + b * f[END], a * cy + b * f[END + 1], a * cz + b * f[END + 2]};
    }

    /** The half-width at {@code v} of values {@code f}, m: full from v = 0.7, 0.85 of it at the end, the tip's radius at the root. */
    public static double half(double[] f, double v) {
        double profile = v < .7 ? Math.sin(Math.PI / 2 * v / .7) : 1 - .5 * (v - .7);
        return Math.max(profile * f[HALF], f[TIP] * (1 - v));
    }

    /**
     * Writes the flame eased from values {@code from} to {@code to}, {@code ribbons} ribbons (from the new state), the outer
     * ribbon first, then the inner ribbons, then the pilots on top, their seeds from the flare's {@code seed}. Positions are
     * from the tip.
     */
    public static void write(FxSink sink, double[] from, double[] to, int ribbons, int seed) {
        boolean flame = ribbons > 0 && (from[LENGTH] > .05 || to[LENGTH] > .05);
        if (flame) for (int j = 0; j < ribbons; j++) ribbon(sink, from, to, j, ribbons, seed);
        pilots(sink, to[TIP], seed);
    }

    private static void ribbon(FxSink sink, double[] from, double[] to, int j, int of, int flare) {
        // Inner ribbons are narrower and hotter, and stand a little to either side across the wind.
        double scale = 1 - .18 * j, heat = to[HEAT] * (.6 + .4 * j / Math.max(1, of - 1));
        double side = (j % 2 == 0 ? 1 : -1) * .25 * Math.min(j, 2);
        int params = FxSink.params(0, heat, to[SOOT], to[OPACITY]), seed = flare + j * 397 & FxSink.SEED_MASK;
        var points = new FxSink.Point[SEGMENTS + 1];
        for (int i = 0; i <= SEGMENTS; i++) {
            double v = (double) i / SEGMENTS;
            points[i] = new FxSink.Point(at(from, v, side, 0), at(from, v, side, 1), at(from, v, side, 2), at(to, v, side, 0), at(to, v, side, 1), at(to, v, side, 2),
                    half(from, v) * scale, half(to, v) * scale);
        }
        double[] previous = tangent(points, 0);
        for (int i = 1; i <= SEGMENTS; i++) {
            double[] next = tangent(points, i);
            sink.ribbon(points[i - 1], (double) (i - 1) / SEGMENTS, previous, points[i], (double) i / SEGMENTS, next, params, seed);
            previous = next;
        }
    }

    /** The unit tangent of the new centre line at point {@code i}: the chord across its neighbours (one-sided at the ends). */
    static double[] tangent(FxSink.Point[] points, int i) {
        var a = points[Math.max(0, i - 1)];
        var b = points[Math.min(points.length - 1, i + 1)];
        double x = b.newX() - a.newX(), y = b.newY() - a.newY(), z = b.newZ() - a.newZ(), length = Math.sqrt(x * x + y * y + z * z);
        return length < 1e-9 ? new double[]{0, 1, 0} : new double[]{x / length, y / length, z / length};
    }

    /** Coordinate {@code axis} of the centre line at {@code v}, moved {@code side} half-widths across the wind (sin π v). */
    private static double at(double[] f, double v, double side, int axis) {
        double[] c = centre(f, v);
        double ex = f[END], ez = f[END + 2], h = Math.hypot(ex, ez);
        // Across the wind: the end's horizontal way turned a quarter; x when the flame stands straight.
        double ax = h > 1e-9 ? -ez / h : 1, az = h > 1e-9 ? ex / h : 0;
        double shift = side * f[HALF] * Math.sin(Math.PI * v);
        return axis == 0 ? c[0] + ax * shift : axis == 1 ? c[1] : c[2] + az * shift;
    }

    /** The pilots: three small flames round the rim, a third of a turn apart. */
    private static void pilots(FxSink sink, double tipRadius, int flare) {
        int params = FxSink.params(0, .8, 0, .9);
        double r = Math.max(.1, tipRadius);
        for (int k = 0; k < PILOTS; k++) {
            double t = 2 * Math.PI * k / PILOTS + Math.PI / 2, x = r * Math.cos(t), z = r * Math.sin(t);
            sink.ribbon(FxSink.Point.still(x, 0, z, .1), 0, FxSink.Point.still(x, PILOT_LENGTH, z, .1), 1, params, flare + 2048 + k * 211 & FxSink.SEED_MASK);
        }
    }

    /** The end of the flame of values {@code v}: x, y, z from the tip. */
    public static double[] end(double[] v) {
        return new double[]{v[END], v[END + 1], v[END + 2]};
    }

    /** A radius about the tip that holds the flame of values {@code v}. */
    public static double radius(double[] v) {
        return Math.max(1, v[LENGTH] + v[HALF]);
    }
}
