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
 * <li><b>Width.</b> A jet flame is about six times as long as it is wide: half-width {@link #HALF_WIDTH} L at its widest,
 * a little past the middle, never narrower at the root than the tip.</li>
 * <li><b>Look.</b> 3 to 5 layered ribbons of {@link #SEGMENTS} segments, hotter toward the core, more with the load;
 * three pilot flames at the rim always. At most 43 quads (plan: 48).</li>
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
    public static final double PILOT_LENGTH = .6;
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

    /** Ribbons for a flame burning {@code share} of its rating: 3 to 5, none without a flame. */
    public static int ribbons(double length, double share) {
        return length > 0 ? 3 + (int) Math.round(2 * Math.max(0, Math.min(1, share))) : 0;
    }

    /** The point at {@code v} (0 tip, 1 end) of the centre line of values {@code f}: x, y, z from the tip. */
    public static double[] centre(double[] f, double v) {
        // A quadratic curve: leaves the tip toward its control point, which stands over the tip, and ends at the flame's end.
        double cx = .2 * f[END], cy = .7 * f[END + 1], cz = .2 * f[END + 2];
        double a = 2 * v * (1 - v), b = v * v;
        return new double[]{a * cx + b * f[END], a * cy + b * f[END + 1], a * cz + b * f[END + 2]};
    }

    /** The half-width at {@code v} of values {@code f}, m: widest a little past the middle, the tip's radius at the root. */
    public static double half(double[] f, double v) {
        double profile = (.3 + 2.8 * v) * Math.pow(Math.max(0, 1 - v), .6) / 1.15;
        return Math.max(profile * f[HALF], f[TIP] * (1 - v));
    }

    /**
     * Writes the flame eased from values {@code from} to {@code to}, {@code ribbons} ribbons (from the new state), the outer
     * ribbon first, then the pilots, then the inner ribbons. Positions are from the tip.
     */
    public static void write(FxSink sink, double[] from, double[] to, int ribbons) {
        boolean flame = ribbons > 0 && (from[LENGTH] > .05 || to[LENGTH] > .05);
        if (flame) ribbon(sink, from, to, 0, ribbons);
        pilots(sink, to[TIP]);
        if (flame) for (int j = 1; j < ribbons; j++) ribbon(sink, from, to, j, ribbons);
    }

    private static void ribbon(FxSink sink, double[] from, double[] to, int j, int of) {
        // Inner ribbons are narrower and hotter, and stand a little to either side across the wind.
        double scale = 1 - .18 * j, heat = to[HEAT] * (.6 + .4 * j / Math.max(1, of - 1));
        double side = (j % 2 == 0 ? 1 : -1) * .25 * Math.min(j, 2);
        int params = FxSink.params(0, heat, to[SOOT], to[OPACITY]);
        FxSink.Point last = null;
        for (int i = 0; i <= SEGMENTS; i++) {
            double v = (double) i / SEGMENTS;
            var p = new FxSink.Point(at(from, v, side, 0), at(from, v, side, 1), at(from, v, side, 2), at(to, v, side, 0), at(to, v, side, 1), at(to, v, side, 2),
                    half(from, v) * scale, half(to, v) * scale);
            if (last != null) sink.ribbon(last, (double) (i - 1) / SEGMENTS, p, v, params, j * 397 + i * 53 & FxSink.SEED_MASK);
            last = p;
        }
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
    private static void pilots(FxSink sink, double tipRadius) {
        int params = FxSink.params(0, .8, 0, .9);
        double r = Math.max(.1, tipRadius);
        for (int k = 0; k < PILOTS; k++) {
            double t = 2 * Math.PI * k / PILOTS + Math.PI / 2, x = r * Math.cos(t), z = r * Math.sin(t);
            sink.ribbon(FxSink.Point.still(x, 0, z, .1), 0, FxSink.Point.still(x, PILOT_LENGTH, z, .04), 1, params, 2048 + k * 211);
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
