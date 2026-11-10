package com.wormzjl.icheme.client.fx.emitter;

import com.wormzjl.icheme.science.fluid.network.Flare;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The flare flame's length, bend and geometry (CUSTOM_SHADER_SYSTEM_PLAN.md sections 2 and 4.5, W3). */
class FlameModelTest {
    private static final double TIP = .3;

    @Test
    void ratedFlameIsAboutThirtyMetres() {
        double q = Flare.rated(TIP);
        assertEquals(212e6, q, 15e6);
        double length = Flare.flameLength(q);
        assertEquals(29.5, length, 1.5);
        assertEquals(length, FlameModel.values(length, 5, 0, 120, 1, 0, TIP / 2)[FlameModel.LENGTH], 1e-12);
    }

    @Test
    void windBendsTheFlameByTheVelocityRatio() {
        double[] still = FlameModel.distortion(0);
        assertEquals(0, still[0], 1e-12);
        assertEquals(1, still[1], 1e-12);
        double lastDown = 0, lastUp = 1;
        for (double r : new double[]{.005, .01, .03, .1, .3, 1, 3}) {
            double[] d = FlameModel.distortion(r);
            assertTrue(d[0] >= lastDown && d[1] <= lastUp, "monotone at " + r);
            assertTrue(Math.hypot(d[0], d[1]) <= 1.01, "the end stays within one flame length at " + r);
            lastDown = d[0];
            lastUp = d[1];
        }
        assertEquals(.5, FlameModel.distortion(.05)[0], 1e-9);
        // A flare near its rating (exit 120 m/s, wind 6 m/s) stands; one at a tenth of its flow lies over.
        assertTrue(FlameModel.distortion(6 / 120.0)[1] > .8);
        assertTrue(FlameModel.distortion(6 / 12.0)[1] < .5);
    }

    @Test
    void flameEndsAtItsBentEndAndStaysWithinBudget() {
        double length = 30;
        var v = FlameModel.values(length, 5, 0, 100, 1, 0, TIP / 2);
        int ribbons = FlameModel.ribbons(length, 1);
        assertEquals(3, ribbons);
        var sink = new RecordingSink();
        FlameModel.write(sink, v, v, ribbons, 0);
        assertEquals(ribbons * FlameModel.SEGMENTS + FlameModel.PILOTS, sink.quads());
        assertTrue(sink.quads() <= 48, "plan: at most 48 quads per flare");
        // The outer ribbon is written first and ends at the bent end: 0.5 L downwind and 0.86 L up at u/Uj = 0.05.
        var last = sink.ribbons.get(FlameModel.SEGMENTS - 1).b();
        assertEquals(15, last.newX(), 1e-6);
        assertEquals(25.8, last.newY(), 1e-6);
        assertEquals(0, last.newZ(), 1e-6);
        double widest = 0;
        for (var r : sink.ribbons) widest = Math.max(widest, r.a().newHalf());
        assertEquals(FlameModel.HALF_WIDTH * length, widest, .2 * FlameModel.HALF_WIDTH * length);
    }

    @Test
    void flameGrowsFromTheTipAndPilotsAlwaysBurn() {
        var rest = FlameModel.values(0, 0, 0, 1, 0, 0, TIP / 2);
        var v = FlameModel.values(20, 0, 3, 60, .5, .4, TIP / 2);
        var sink = new RecordingSink();
        FlameModel.write(sink, rest, v, FlameModel.ribbons(20, .5), 0);
        var first = sink.ribbons.get(FlameModel.SEGMENTS - 1).b();
        assertEquals(0, first.oldY(), 1e-9);
        assertTrue(first.newY() > 10 && first.newZ() > 0, "wind +z bends the flame toward +z");
        var none = new RecordingSink();
        FlameModel.write(none, rest, rest, 0, 0);
        assertEquals(FlameModel.PILOTS, none.quads());
        // A cut budget keeps the outer ribbon whole.
        var cut = new RecordingSink(FlameModel.SEGMENTS);
        FlameModel.write(cut, v, v, 4, 0);
        assertEquals(FlameModel.SEGMENTS, cut.ribbons.size());
        assertEquals(1, cut.ribbons.get(FlameModel.SEGMENTS - 1).vb(), 1e-12);
    }

    /**
     * The allocation-free write (FX_BENCH_REVIEW.md, COMPATIBLE path: no centre array per coordinate, tangents reused) gives
     * the same calls, bit for bit, as the form before it, kept below as {@link #reference}.
     */
    @Test
    void writeMatchesTheFormBeforeTheAllocationPass() {
        var rest = FlameModel.values(0, 0, 0, 1, 0, 0, TIP / 2);
        double[][][] cases = {
                {FlameModel.values(30, 5, 0, 100, 1, 0, TIP / 2), FlameModel.values(30, 5, 0, 100, 1, 0, TIP / 2)},
                {rest, FlameModel.values(20, 0, 3, 60, .5, .4, TIP / 2)},
                {FlameModel.values(12, 0, 0, 80, .3, .1, TIP / 2), FlameModel.values(25, -4, 2.5, 90, .9, .7, TIP / 2)},
                {rest, rest}};
        for (var c : cases)
            for (int ribbons = 0; ribbons <= 4; ribbons++)
                for (int seed : new int[]{0, 1234, 4095}) {
                    var actual = new CallSink();
                    FlameModel.write(actual, c[0], c[1], ribbons, seed);
                    var expected = new CallSink();
                    reference(expected, c[0], c[1], ribbons, seed);
                    assertEquals(expected.calls, actual.calls, "ribbons " + ribbons + " seed " + seed);
                }
    }

    /** Every ribbon call with copies of its tangents (a writer may reuse the arrays), compared by value. */
    private static final class CallSink implements com.wormzjl.icheme.client.fx.FxSink {
        record Call(Point a, double va, java.util.List<Double> ta, Point b, double vb, java.util.List<Double> tb, int params, int seed) {
        }

        final java.util.List<Call> calls = new java.util.ArrayList<>();

        private static java.util.List<Double> copy(double[] t) {
            return t == null ? null : java.util.List.of(t[0], t[1], t[2]);
        }

        @Override
        public void sprite(Point centre, int params, int seed) {
            throw new AssertionError("a flame writes no sprites");
        }

        @Override
        public void puff(Point start, double dx, double dy, double dz, int params, int seed) {
            throw new AssertionError("a flame writes no puffs");
        }

        @Override
        public void ribbon(Point a, double va, Point b, double vb, int params, int seed) {
            calls.add(new Call(a, va, null, b, vb, null, params, seed));
        }

        @Override
        public void ribbon(Point a, double va, double[] ta, Point b, double vb, double[] tb, int params, int seed) {
            calls.add(new Call(a, va, copy(ta), b, vb, copy(tb), params, seed));
        }

        @Override
        public int remaining() {
            return Integer.MAX_VALUE;
        }
    }

    /** {@code FlameModel.write} as it stood before the allocation pass (commit 50e00a0e2). */
    private static void reference(com.wormzjl.icheme.client.fx.FxSink sink, double[] from, double[] to, int ribbons, int seed) {
        boolean flame = ribbons > 0 && (from[FlameModel.LENGTH] > .05 || to[FlameModel.LENGTH] > .05);
        if (flame) for (int j = 0; j < ribbons; j++) {
            double scale = 1 - .18 * j, heat = to[FlameModel.HEAT] * (.6 + .4 * j / Math.max(1, ribbons - 1));
            double side = (j % 2 == 0 ? 1 : -1) * .25 * Math.min(j, 2);
            int params = com.wormzjl.icheme.client.fx.FxSink.params(0, heat, to[FlameModel.SOOT], to[FlameModel.OPACITY]), s = seed + j * 397 & com.wormzjl.icheme.client.fx.FxSink.SEED_MASK;
            var points = new com.wormzjl.icheme.client.fx.FxSink.Point[FlameModel.SEGMENTS + 1];
            for (int i = 0; i <= FlameModel.SEGMENTS; i++) {
                double v = (double) i / FlameModel.SEGMENTS;
                points[i] = new com.wormzjl.icheme.client.fx.FxSink.Point(at(from, v, side, 0), at(from, v, side, 1), at(from, v, side, 2),
                        at(to, v, side, 0), at(to, v, side, 1), at(to, v, side, 2), FlameModel.half(from, v) * scale, FlameModel.half(to, v) * scale);
            }
            double[] previous = tangent(points, 0);
            for (int i = 1; i <= FlameModel.SEGMENTS; i++) {
                double[] next = tangent(points, i);
                sink.ribbon(points[i - 1], (double) (i - 1) / FlameModel.SEGMENTS, previous, points[i], (double) i / FlameModel.SEGMENTS, next, params, s);
                previous = next;
            }
        }
        int params = com.wormzjl.icheme.client.fx.FxSink.params(0, .8, 0, .9);
        double r = Math.max(.1, to[FlameModel.TIP]);
        for (int k = 0; k < FlameModel.PILOTS; k++) {
            double t = 2 * Math.PI * k / FlameModel.PILOTS + Math.PI / 2, x = r * Math.cos(t), z = r * Math.sin(t);
            sink.ribbon(com.wormzjl.icheme.client.fx.FxSink.Point.still(x, 0, z, .1), 0, com.wormzjl.icheme.client.fx.FxSink.Point.still(x, FlameModel.PILOT_LENGTH, z, .1), 1,
                    params, seed + 2048 + k * 211 & com.wormzjl.icheme.client.fx.FxSink.SEED_MASK);
        }
    }

    private static double[] tangent(com.wormzjl.icheme.client.fx.FxSink.Point[] points, int i) {
        var a = points[Math.max(0, i - 1)];
        var b = points[Math.min(points.length - 1, i + 1)];
        double x = b.newX() - a.newX(), y = b.newY() - a.newY(), z = b.newZ() - a.newZ(), length = Math.sqrt(x * x + y * y + z * z);
        return length < 1e-9 ? new double[]{0, 1, 0} : new double[]{x / length, y / length, z / length};
    }

    private static double at(double[] f, double v, double side, int axis) {
        double[] c = FlameModel.centre(f, v);
        double ex = f[FlameModel.END], ez = f[FlameModel.END + 2], h = Math.hypot(ex, ez);
        double ax = h > 1e-9 ? -ez / h : 1, az = h > 1e-9 ? ex / h : 0;
        double shift = side * f[FlameModel.HALF] * Math.sin(Math.PI * v);
        return axis == 0 ? c[0] + ax * shift : axis == 1 ? c[1] : c[2] + az * shift;
    }
}
