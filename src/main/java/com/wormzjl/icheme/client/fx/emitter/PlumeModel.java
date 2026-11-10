package com.wormzjl.icheme.client.fx.emitter;

import com.wormzjl.icheme.client.fx.FxPlume;
import com.wormzjl.icheme.client.fx.FxSink;

/**
 * The jet plumes of the effects (batch 2026-11-10-custom-shader-system, plan sections 2 and 4.5, W3 to W6): a tower's or a
 * stack's white plume, a flue's or a flare's smoke. Pure; names no Minecraft class.
 * <ul>
 * <li><b>Exit.</b> The exhaust leaves at its volume flow over the exit area: moist air (or flue gas, as air) at
 * ρ = P / (R_d T) (1 + w) / (1 + w / ε).</li>
 * <li><b>Path.</b> A round jet in a cross-wind, Pratte and Baines (1967): z / (r D) = 2.05 (x / (r D))^0.28, r = U_j / u∞
 * the exit velocity over the wind; it rises straight out of the exit and leans downwind, the more the weaker the jet.</li>
 * <li><b>Visible part.</b> Liquid water shows between the two exhaust shares where the mixing line crosses saturation: from
 * the larger (a saturated tower exhaust: at the exit; a hot stack: well downwind, where it has cooled) to the smaller
 * ({@link FxPlume#visibleEnd}), each at the jet's distance {@link FxPlume#JET_DILUTION} D / f, at most {@link #CAP} m. Its
 * edges are sharp, as a real plume's. Opacity from the most liquid water: 1 − exp(−excess / 1 g/kg), so the plan's faint
 * 0.28 g/kg shows at 0.24 and a dense 2.8 g/kg at 0.94.</li>
 * <li><b>Puffs.</b> The visible part is cut into segments the wind crosses in about one drift period; each holds
 * {@link #PHASES} travelling puffs ({@link FxSink#puff}) a third of a period apart, so the stream is steady. A puff's
 * half-size is the exit's plus {@link #SPREAD} of its distance along the path.</li>
 * </ul>
 * A plume's glided values ({@link #values}): its visible start and end along the path, r, wind x, wind z (unit), density, D,
 * and the offset of its path's origin from each exit.
 */
public final class PlumeModel {
    /** Gas constant of dry air, J/(kg K). */
    public static final double R_DRY = 287.05;
    /** Travelling puffs per segment. */
    public static final int PHASES = 3;
    /** A puff's half-size grows by this share of the distance along the path. */
    public static final double SPREAD = .15;
    /** The farthest a plume shows, m: about the vanilla render distance's reach for a tall plume. */
    public static final double CAP = 200;
    /** Liquid water that makes a plume about 63 % opaque, kg per kg of dry air. */
    public static final double DENSE_EXCESS = 1e-3;
    /** A puff's opacity is its plume's density times this: three overlapping puffs build it up. */
    static final double PUFF_ALPHA = .55;
    /** The values' layout. */
    static final int START = 0, LENGTH = 1, RATIO = 2, WIND_X = 3, WIND_Z = 4, DENSITY = 5, DIAMETER = 6, OFFSET = 7, SIZE = 10;
    /** Points of the centre line the puffs are placed on. */
    private static final int LINE = 64;
    private static final int BISECTION_STEPS = 50;

    private PlumeModel() {
    }

    /** A puff colour from the plume's start ({@code near}) to its end ({@code far}), r g b each 0 to 1. */
    public record Style(double[] near, double[] far, double pulse) {
        public static final Style WHITE = new Style(new double[]{.95, .95, .97}, new double[]{.93, .94, .96});

        /** A steady plume's colours. */
        public Style(double[] near, double[] far) {
            this(near, far, 0);
        }

        /**
         * These colours with a pulse of {@code depth} (0 to 1): the puffs of a segment's phases alternate in density, so a
         * few denser puffs a second run up the plume, as an engine's turbulent exhaust eddies do (batch
         * 2026-11-10-prime-movers: the firing itself, 80 to 200 Hz, is continuous to the eye).
         */
        public Style pulsing(double depth) {
            return new Style(near, far, depth);
        }

        /** A puff's density factor at phase {@code j} of {@link #PHASES}: 1 + pulse on the first, less on the others, mean 1. */
        double phase(int j) {
            return j == 0 ? 1 + pulse : 1 - pulse / (PHASES - 1);
        }

        int params(double along, double alpha) {
            double k = Math.max(0, Math.min(1, along));
            return FxSink.params(near[0] + (far[0] - near[0]) * k, near[1] + (far[1] - near[1]) * k, near[2] + (far[2] - near[2]) * k, alpha);
        }
    }

    /** The exit velocity of {@code wetFlow} kg/s of gas at {@code kelvin} with {@code ratio} kg water per kg dry gas through {@code area} m², m/s. */
    public static double exitVelocity(double wetFlow, double kelvin, double ratio, double area) {
        if (!(wetFlow > 0 && kelvin > 0 && area > 0)) return 0;
        double w = Math.max(0, ratio), density = FxPlume.PRESSURE / (R_DRY * kelvin) * (1 + w) / (1 + w / FxPlume.EPSILON);
        return wetFlow / density / area;
    }

    /** The plume's opacity, 0 to 1, from the most liquid water on its mixing line ({@link FxPlume#excess}), kg/kg. */
    public static double density(double excess) {
        return excess > 0 ? 1 - Math.exp(-excess / DENSE_EXCESS) : 0;
    }

    /**
     * The visible white plume of an exit {@code diameter} m across: where it starts and ends along its path, m, and its
     * density, 0 to 1; all 0 when the mixing line never reaches saturation or the plume would start past {@link #CAP}.
     *
     * @param exhaustCelsius exhaust temperature, °C
     * @param exhaustRatio   exhaust humidity ratio, kg/kg dry gas
     */
    public static double[] visible(double diameter, double exhaustCelsius, double exhaustRatio, double ambientCelsius, double ambientRh) {
        double end = FxPlume.visibleEnd(exhaustCelsius, exhaustRatio, ambientCelsius, ambientRh);
        if (Double.isNaN(end) || !(diameter > 0)) return new double[3];
        double from = distance(diameter, start(exhaustCelsius, exhaustRatio, ambientCelsius, ambientRh)), to = FxPlume.visibleLength(diameter, end, CAP);
        if (!(from < to)) return new double[3];
        return new double[]{from, to, density(FxPlume.excess(exhaustCelsius, exhaustRatio, ambientCelsius, ambientRh))};
    }

    /** The largest exhaust share that holds liquid water: where the visible plume starts (1: at the exit). NaN for none. */
    static double start(double exhaustCelsius, double exhaustRatio, double ambientCelsius, double ambientRh) {
        double wa = FxPlume.ambientRatio(ambientCelsius, ambientRh);
        double top = FxPlume.peak(exhaustCelsius, exhaustRatio, ambientCelsius, ambientRh);
        if (!(FxPlume.excessAt(top, exhaustCelsius, exhaustRatio, ambientCelsius, wa) > 0)) return Double.NaN;
        if (FxPlume.excessAt(1, exhaustCelsius, exhaustRatio, ambientCelsius, wa) >= 0) return 1;
        double lo = top, hi = 1;
        for (int i = 0; i < BISECTION_STEPS; i++) {
            double mid = (lo + hi) / 2;
            if (FxPlume.excessAt(mid, exhaustCelsius, exhaustRatio, ambientCelsius, wa) > 0) lo = mid;
            else hi = mid;
        }
        return lo;
    }

    /** The distance along the jet where its exhaust share falls to {@code share}: 0 at 1 (the exit), JET_DILUTION D / f below. */
    static double distance(double diameter, double share) {
        if (Double.isNaN(share)) return Double.POSITIVE_INFINITY;
        return share >= 1 ? 0 : FxPlume.JET_DILUTION * diameter / Math.max(share, 1e-9);
    }

    /** The glided values of a plume that shows from the exit (smoke): see the class comment. */
    public static double[] values(double length, double ratio, double windX, double windZ, double density, double diameter, double ox, double oy, double oz) {
        return values(0, length, ratio, windX, windZ, density, diameter, ox, oy, oz);
    }

    /** The glided values of a plume that shows from {@code start} to {@code length} m along its path. */
    public static double[] values(double start, double length, double ratio, double windX, double windZ, double density, double diameter,
                                  double ox, double oy, double oz) {
        double speed = Math.hypot(windX, windZ), end = Math.max(0, length);
        return new double[]{Math.max(0, Math.min(start, end)), end, Math.max(0, ratio), speed > 0 ? windX / speed : 1, speed > 0 ? windZ / speed : 0,
                Math.max(0, Math.min(1, density)), Math.max(0, diameter), ox, oy, oz};
    }

    /**
     * Segments for {@code length} m of plume the wind {@code wind} m/s carries over each drift {@code period} s: at least 3
     * (a short trail still widens along its length; visual review 2026-11-10, item 5), at most {@code most}.
     */
    public static int segments(double length, double wind, double period, int most) {
        if (!(length > 0) || most < 1) return 0;
        return (int) Math.max(Math.min(3, most), Math.min(most, Math.round(length / Math.max(.5, wind * period))));
    }

    /**
     * The centre line from the exit: {@code steps} + 1 points at equal steps of arc length up to {@code length}, each its
     * distance downwind and its height, m (Pratte and Baines, in the parameter u = (x / rD)^0.28: x = rD u^(1/0.28),
     * z = 2.05 rD u). A jet with no momentum (r D near 0) bends over at once.
     */
    public static double[][] centreLine(double diameter, double ratio, double length, int steps) {
        int n = Math.max(1, steps);
        double[][] out = new double[n + 1][2];
        if (!(length > 0)) return out;
        double rd = Math.max(.05, ratio * diameter), exponent = 1 / .28;
        double step = length / n / 16, u = 0, x = 0, z = 0, s = 0;
        int next = 1;
        while (next <= n) {
            double dxdu = rd * exponent * Math.pow(u, exponent - 1), dzdu = 2.05 * rd;
            double un = u + step / Math.hypot(dxdu, dzdu), xn = rd * Math.pow(un, exponent), zn = 2.05 * rd * un;
            double ds = Math.hypot(xn - x, zn - z);
            // Points at equal arc length, interpolated within the step.
            while (next <= n && s + ds >= length * next / n) {
                double k = ds > 0 ? (length * next / n - s) / ds : 0;
                out[next][0] = x + (xn - x) * k;
                out[next][1] = z + (zn - z) * k;
                next++;
            }
            u = un;
            x = xn;
            z = zn;
            s += ds;
        }
        return out;
    }

    /** The point {@code s} m along a centre line of {@code length} m ({@link #centreLine}), interpolated. */
    static double[] along(double[][] line, double length, double s) {
        return along(line, length, s, new double[2]);
    }

    /** As {@link #along(double[][], double, double)}, into {@code out} (downwind, height); returns {@code out}. */
    static double[] along(double[][] line, double length, double s, double[] out) {
        if (!(length > 0)) {
            out[0] = 0;
            out[1] = 0;
            return out;
        }
        double k = Math.max(0, Math.min(1, s / length)) * (line.length - 1);
        int i = Math.min(line.length - 2, (int) k);
        double f = k - i;
        out[0] = line[i][0] + (line[i + 1][0] - line[i][0]) * f;
        out[1] = line[i][1] + (line[i + 1][1] - line[i][1]) * f;
        return out;
    }

    /**
     * One plume's centre lines ({@link #centreLine} at {@link #LINE} steps), kept between frames: a line depends only on the
     * glide end's diameter, r and length, which change only when the emitter is aimed again, so the COMPATIBLE path, which
     * writes every frame, computes each line once per aim (bench 2026-11-10, FX_BENCH_REVIEW.md: {@code centreLine} was its
     * top frame). Two slots, so the aim's new start (the old target) is found again. Render thread only, one per emitter.
     */
    public static final class Lines {
        private final double[][][] lines = new double[2][][];
        private final double[] diameter = new double[2], ratio = new double[2], length = new double[2];
        private int next;
        /** The three points one segment reads, reused. */
        final double[] pa = new double[2], pb = new double[2], qb = new double[2];
        /** Lines computed, for the tests. */
        int computed;

        /** {@link #centreLine}({@code d}, {@code r}, {@code l}, {@link #LINE}), from a slot when it holds the same three values. */
        double[][] line(double d, double r, double l) {
            for (int k = 0; k < 2; k++)
                if (lines[k] != null && same(diameter[k], d) && same(ratio[k], r) && same(length[k], l)) return lines[k];
            int k = next;
            next = 1 - next;
            lines[k] = centreLine(d, r, l, LINE);
            diameter[k] = d;
            ratio[k] = r;
            length[k] = l;
            computed++;
            return lines[k];
        }

        private static boolean same(double a, double b) {
            return Double.doubleToLongBits(a) == Double.doubleToLongBits(b);
        }
    }

    /**
     * Writes a plume from each exit of {@code exits} (emitter-local x, y, z of each exit's centre): {@code segments} segments
     * of {@link #PHASES} puffs over its visible part, eased from the values {@code from} to {@code to}. Segment by segment over
     * all exits, so a cut budget shortens every plume alike. {@code seed} varies the noise per emitter.
     */
    public static void write(FxSink sink, double[][] exits, double[] from, double[] to, int segments, Style style, int seed) {
        write(sink, exits, from, to, segments, style, seed, new Lines());
    }

    /** As {@link #write(FxSink, double[][], double[], double[], int, Style, int)}, with the emitter's kept centre lines. */
    public static void write(FxSink sink, double[][] exits, double[] from, double[] to, int segments, Style style, int seed, Lines lines) {
        if (segments < 1 || !(to[LENGTH] > 0 || from[LENGTH] > 0) || !(to[DENSITY] > 0)) return;
        double[][] a = lines.line(from[DIAMETER], from[RATIO], from[LENGTH]), b = lines.line(to[DIAMETER], to[RATIO], to[LENGTH]);
        double sa = (from[LENGTH] - from[START]) / segments, sb = (to[LENGTH] - to[START]) / segments;
        for (int k = 0; k < segments; k++) {
            double alpha = to[DENSITY] * PUFF_ALPHA * (1 - .5 * k / segments);
            int params = style.params((k + .5) / segments, alpha);
            double ka = from[START] + sa * k, kb = to[START] + sb * k;
            double[] pa = along(a, from[LENGTH], ka, lines.pa), pb = along(b, to[LENGTH], kb, lines.pb), qb = along(b, to[LENGTH], kb + sb, lines.qb);
            double dx = (qb[0] - pb[0]) * to[WIND_X], dy = qb[1] - pb[1], dz = (qb[0] - pb[0]) * to[WIND_Z];
            for (int e = 0; e < exits.length; e++) {
                var exit = exits[e];
                var start = new FxSink.Point(exit[0] + from[OFFSET] + pa[0] * from[WIND_X], exit[1] + from[OFFSET + 1] + pa[1], exit[2] + from[OFFSET + 2] + pa[0] * from[WIND_Z],
                        exit[0] + to[OFFSET] + pb[0] * to[WIND_X], exit[1] + to[OFFSET + 1] + pb[1], exit[2] + to[OFFSET + 2] + pb[0] * to[WIND_Z],
                        from[DIAMETER] / 2 + SPREAD * ka, to[DIAMETER] / 2 + SPREAD * kb);
                for (int j = 0; j < PHASES; j++) {
                    if (sink.remaining() <= 0) return;
                    int p = style.pulse() > 0 ? style.params((k + .5) / segments, alpha * style.phase(j)) : params;
                    sink.puff(start, dx, dy, dz, p, FxSink.staggered(j, PHASES, seed * 31 + e * 7 + k));
                }
            }
        }
    }

    /** A radius about the emitter origin that holds a plume of values {@code v} from exits within {@code reach} m of it. */
    public static double radius(double[] v, double reach) {
        return reach + Math.abs(v[OFFSET]) + Math.abs(v[OFFSET + 1]) + Math.abs(v[OFFSET + 2]) + v[LENGTH] * (1 + SPREAD) + v[DIAMETER];
    }
}
