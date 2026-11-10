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
}
