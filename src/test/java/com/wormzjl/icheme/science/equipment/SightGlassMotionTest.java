package com.wormzjl.icheme.science.equipment;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A sight glass's bubbles carried by the published speed (batch 2026-11-18-equipment-reflections, part 3): the crossing
 * time, the rise on a vertical run, a constant size from edge to edge, the stratified horizontal run, the stretch, the
 * phase kept through a speed step, and the fallback rings inside the glass.
 */
class SightGlassMotionTest {
    private static final SightGlassGeometry.Motion ALONG_X = new SightGlassGeometry.Motion(1, 0), UP = new SightGlassGeometry.Motion(0, 1);

    /** The crossing time, s, of the brief's windows and speeds: horizontal, vertical up and vertical down (negative: rising against the flow). */
    @Test
    void crossingTimesOfTheBriefsWindowsAndSpeeds() {
        double[] speeds = {.02, .2, 1, 3};
        // {diameter, rise, crossing at each speed, s}; a negative crossing moves against the flow.
        double[][] table = {
                {.1, 0, 5, .5, .4, .4},
                {.3, 0, 6, 1.5, .4, .4},
                {.1, 1, .4, .4, .4, .4},
                {.3, 1, .3 / .27, .3 / .45, .4, .4},
                {.1, -1, -.1 / .23, -2, .4, .4},
                {.3, -1, -.3 / .23, -6, .4, .4},
        };
        for (var row : table)
            for (int i = 0; i < speeds.length; i++) {
                double v = SightGlassGeometry.velocity(speeds[i], row[0], (int) row[1]);
                double t = Math.copySign(SightGlassGeometry.crossing(v, row[0]), v);
                assertEquals(row[2 + i], t, 1e-9, "D " + row[0] + " m, rise " + row[1] + ", " + speeds[i] + " m/s");
            }
    }

    @Test
    void neverSlowerThanTheFloorNorAgainstTheFlowFasterThanTheRise() {
        for (double d : new double[]{.02, .1, .3, 1})
            for (int rise = -1; rise <= 1; rise++)
                for (double s = 0; s <= 5; s += .01) {
                    double v = SightGlassGeometry.velocity(s, d, rise), t = SightGlassGeometry.crossing(v, d);
                    assertTrue(t <= SightGlassGeometry.SLOWEST + 1e-12 && t >= SightGlassGeometry.FASTEST - 1e-12, "crossing " + t);
                    if (v < 0) assertTrue(rise < 0 && -v <= SightGlassGeometry.RISE + 1e-12, "only rise carries a bubble against the flow");
                }
        assertTrue(SightGlassGeometry.velocity(0, .1, 1) > 0, "still liquid in a vertical run: bubbles rise");
        assertTrue(SightGlassGeometry.velocity(0, .1, -1) < 0, "still liquid flowing down: bubbles still rise");
        assertEquals(-.3 / 6, SightGlassGeometry.velocity(.25, .3, -1), 1e-12, "flow and rise cancel: they rise at the floor");
        assertEquals(.1 / 6, SightGlassGeometry.velocity(0, .1, 0), 1e-12, "a horizontal run at rest drifts at the floor");
    }

    @Test
    void eachBubbleKeepsItsSizeAndCrossesFromEdgeToEdge() {
        double d = .1;
        var first = SightGlassGeometry.bubbles(0, 9, 255, ALONG_X, d, .05, false);
        assertEquals(8, first.size());
        for (int i = 0; i < first.size(); i++) {
            double least = Double.MAX_VALUE, most = -Double.MAX_VALUE, radius = first.get(i).radius();
            for (double s = 0; s < 1; s += .0005) {
                var b = SightGlassGeometry.bubbles(s, 9, 255, ALONG_X, d, .05, false).get(i);
                assertEquals(radius, b.radius(), 0, "constant radius");
                least = Math.min(least, b.x());
                most = Math.max(most, b.x());
            }
            assertTrue(least <= -1 - radius && most >= 1 + radius, "enters fully outside one edge, leaves past the other");
        }
    }

    @Test
    void aHorizontalRunCarriesItsGasHighAtLowSpeedAndAVerticalOneSpreadsIt() {
        double d = .1, slow = SightGlassGeometry.velocity(.02, d, 0), fast = SightGlassGeometry.velocity(3, d, 0);
        double slowLeast = 1, fastLeast = 1, spread = 0;
        for (double s = 0; s < .5; s += .01) {
            for (var b : SightGlassGeometry.bubbles(s, 9, 255, ALONG_X, d, slow, true)) slowLeast = Math.min(slowLeast, b.y());
            for (var b : SightGlassGeometry.bubbles(s, 9, 255, ALONG_X, d, fast, true)) fastLeast = Math.min(fastLeast, b.y());
            for (var b : SightGlassGeometry.bubbles(s, 9, 255, UP, d, slow, false)) spread += b.x();
        }
        assertTrue(slowLeast > .4, "slow stratified flow: every bubble in the upper part, lowest " + slowLeast);
        assertTrue(fastLeast < 0, "fast flow disperses the gas below the axis, lowest " + fastLeast);
        assertEquals(0, spread / (50 * 8), .3, "a vertical run spreads them about the axis");
    }

    @Test
    void stretchRunsFromOneSlowToTwoAndAHalfFast() {
        for (double d : new double[]{.1, .3}) {
            assertEquals(1, SightGlassGeometry.bubbles(0, 9, 255, UP, d, SightGlassGeometry.velocity(0, d, 0), false).getFirst().stretch(), 1e-9, "at the 6 s floor");
            assertEquals(SightGlassGeometry.MOST_STRETCH, SightGlassGeometry.bubbles(0, 9, 255, UP, d, SightGlassGeometry.velocity(3, d, 0), false).getFirst().stretch(), 1e-9);
        }
    }

    @Test
    void aSpeedStepKeepsThePhaseAndEasesTheLook() {
        double d = .1, slow = SightGlassGeometry.velocity(.2, d, 0), fast = SightGlassGeometry.velocity(2, d, 0), at = 1234.37;
        var travel = SightGlassGeometry.Travel.start(1000, slow);
        var stepped = travel.retarget(at, fast);
        assertEquals(travel.at(at), stepped.at(at), 1e-12, "the distance travelled does not jump");
        assertEquals(slow, stepped.eased(at), 1e-12, "the look starts where it was");
        assertEquals(fast, stepped.eased(at + 20), 1e-12, "and reaches the new pace in one second");
        double e = 1e-4;
        List<SightGlassGeometry.Bubble> before = SightGlassGeometry.bubbles(travel.at(at - e), 9, 255, ALONG_X, d, travel.eased(at - e), true),
                after = SightGlassGeometry.bubbles(stepped.at(at + e), 9, 255, ALONG_X, d, stepped.eased(at + e), true);
        for (int i = 0; i < before.size(); i++)
            assertEquals(0, Math.hypot(after.get(i).x() - before.get(i).x(), after.get(i).y() - before.get(i).y()), .01, "bubble " + i + " does not jump");
        assertSame(stepped, stepped.retarget(at + 5, fast), "the same speed keeps the travel");
        assertEquals(stepped.at(at + 7), stepped.at(at + 7), 0, "a paused game (the same ticks) draws the same bubbles");
    }

    @Test
    void fallbackRingsStayInsideTheGlass() {
        for (double d : new double[]{.02, .1, .3})
            for (double speed : new double[]{0, .05, .5, 2})
                for (boolean stratified : new boolean[]{false, true})
                    for (long seed : new long[]{0, 9, Long.MAX_VALUE})
                        for (double s = 0; s < .6; s += .007)
                            for (var b : SightGlassGeometry.bubbles(s, seed, 255, ALONG_X, d, SightGlassGeometry.velocity(speed, d, 0), stratified)) {
                                double scale = SightGlassGeometry.fallbackScale(b), extent = b.radius() * Math.max(1, b.stretch()) * scale;
                                assertTrue(scale >= 0 && scale <= 1);
                                assertTrue(scale == 0 || Math.hypot(b.x(), b.y()) + extent <= SightGlassGeometry.APERTURE + 1e-12, "ring outside the window (scale 0: not drawn)");
                                if (Math.hypot(b.x(), b.y()) + b.radius() * b.stretch() <= SightGlassGeometry.APERTURE)
                                    assertEquals(1, scale, 0, "full size away from the rim");
                            }
    }
}
