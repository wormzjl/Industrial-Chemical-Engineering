package com.wormzjl.icheme.client.fx.emitter;

import com.wormzjl.icheme.client.fx.FxPlume;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plume exit, path, visible length and density against CUSTOM_SHADER_SYSTEM_PLAN.md section 2's table (W4, W5): a tower at
 * the calibration point (exhaust saturated at 28.5 °C) and a gas-fired stack (180 °C, 12 % water by mass of dry gas).
 */
class PlumeModelTest {
    private static final double TOWER = 28.51, TOWER_W = FxPlume.saturationRatio(TOWER), FAN = 4;
    private static final double STACK = 180, STACK_W = .1217, FLUE = 1.5;

    @Test
    void exitVelocityIsTheVolumeFlowOverTheArea() {
        // Dry air at 20 °C: 1.204 kg/m³, so 1.204 kg/s through 1 m² leaves at 1 m/s.
        assertEquals(1, PlumeModel.exitVelocity(1.2041, 293.15, 0, 1), .01);
        // Moist air is lighter: the same mass flow leaves faster.
        assertTrue(PlumeModel.exitVelocity(1.2041, 293.15, .02, 1) > 1.01);
        assertEquals(0, PlumeModel.exitVelocity(0, 300, 0, 1));
        // A fan stack of 4 m passing 150 kg/s of dry air at 28.5 °C, saturated: about 10 m/s (plan: 8 to 10).
        double v = PlumeModel.exitVelocity(150 * (1 + TOWER_W), TOWER + 273.15, TOWER_W, Math.PI * FAN * FAN / 4);
        assertEquals(10.5, v, 1.5);
    }

    @Test
    void towerShowsNoPlumeAtTheModsAmbient() {
        assertTrue(PlumeModel.visible(FAN, TOWER, TOWER_W, 25, .5)[2] < .01);
    }

    @Test
    void towerPlumeGrowsDenserAndLongerInColdHumidAir() {
        double[] faint = PlumeModel.visible(FAN, TOWER, TOWER_W, 15, .7), dense = PlumeModel.visible(FAN, TOWER, TOWER_W, 5, .8),
                cold = PlumeModel.visible(FAN, TOWER, TOWER_W, -5, .8);
        // A saturated exhaust shows at the exit.
        assertEquals(0, faint[0], 1e-9);
        assertEquals(0, dense[0], 1e-9);
        // Table: 0.28 g/kg faint, 2.8 dense, 5.0 dense.
        assertEquals(1 - Math.exp(-.28), faint[2], .03);
        assertTrue(dense[2] > .9 && cold[2] > dense[2]);
        // Faint: it clears at an exhaust share of 0.59, 5 D / 0.59 = 34 m; dense and cold reach the cap.
        assertEquals(34, faint[1], 2);
        assertTrue(dense[1] > faint[1] && cold[1] >= dense[1], "longer as the air gets colder");
        assertEquals(PlumeModel.CAP, cold[1], 1e-9);
        // Hot water (exhaust 32.4 °C) at 15 °C, 70 %: 1.3 g/kg, denser than the calibration point.
        double hot = FxPlume.saturationRatio(32.4);
        assertTrue(PlumeModel.visible(FAN, 32.4, hot, 15, .7)[2] > faint[2]);
    }

    @Test
    void gasStackPlumeOnlyInTheColdAndDetached() {
        assertEquals(0, PlumeModel.visible(FLUE, STACK, STACK_W, 25, .5)[1]);
        assertEquals(0, PlumeModel.visible(FLUE, STACK, STACK_W, 15, .7)[1]);
        double[] faint = PlumeModel.visible(FLUE, STACK, STACK_W, 5, .8), cold = PlumeModel.visible(FLUE, STACK, STACK_W, -5, .8);
        // Table: 0.4 g/kg faint at 5 °C, 4.4 at −5 °C.
        assertEquals(1 - Math.exp(-.4), faint[2], .06);
        assertTrue(cold[2] > .95);
        // The hot flue is dry at the exit: the plume starts where the jet has cooled, sooner in colder air
        // (shares 0.078 and 0.17: 5 D / f = 97 m and 43 m for a 1.5 m stack).
        assertEquals(97, faint[0], 3);
        assertEquals(43, cold[0], 2);
        assertTrue(faint[1] > faint[0] && cold[1] > cold[0]);
        // A 350 °C flue: none at 5 °C; at −5 °C (0.1 g/kg) it would start past the cap.
        assertEquals(0, PlumeModel.visible(FLUE, 350, STACK_W, 5, .8)[1]);
        assertEquals(0, PlumeModel.visible(FLUE, 350, STACK_W, -5, .8)[1]);
    }

    @Test
    void pathRisesThenLeansDownwind() {
        double length = 40;
        var strong = PlumeModel.centreLine(FAN, 2, length, 20);
        assertEquals(0, strong[0][0], 1e-12);
        assertEquals(0, strong[0][1], 1e-12);
        // Straight up out of the exit.
        assertTrue(strong[1][1] > 5 * strong[1][0]);
        // Equal arc steps summing to the length.
        double arc = 0;
        for (int i = 1; i < strong.length; i++) arc += Math.hypot(strong[i][0] - strong[i - 1][0], strong[i][1] - strong[i - 1][1]);
        assertEquals(length, arc, .02 * length);
        // A weaker jet bends over sooner: lower and farther downwind at the same arc length.
        var weak = PlumeModel.centreLine(FAN, .3, length, 20);
        assertTrue(weak[20][1] < strong[20][1] && weak[20][0] > strong[20][0]);
        // Pratte and Baines: z / rD = 2.05 (x / rD)^0.28 along the line.
        double rd = 2 * FAN, x = strong[20][0], z = strong[20][1];
        assertEquals(2.05 * Math.pow(x / rd, .28), z / rd, .02);
    }

    @Test
    void puffsFollowTheBudgetAndTheirSegments() {
        var v = PlumeModel.values(40, 2, 3, 4, .8, FAN, 0, 0, 0);
        assertEquals(.6, v[PlumeModel.WIND_X], 1e-12);
        assertEquals(.8, v[PlumeModel.WIND_Z], 1e-12);
        int segments = PlumeModel.segments(40, 5, 2, 32 / (PlumeModel.PHASES * 2));
        assertEquals(4, segments);
        var sink = new RecordingSink();
        double[][] exits = {{-3, 0, 0}, {3, 0, 0}};
        PlumeModel.write(sink, exits, v, v, segments, PlumeModel.Style.WHITE, 7);
        assertEquals(segments * PlumeModel.PHASES * exits.length, sink.puffs.size());
        // The first puffs start at the exits and drift up along the path; a segment's drift reaches the next one's start.
        var first = sink.puffs.get(0);
        assertEquals(-3, first.start().newX(), 1e-9);
        assertEquals(0, first.start().newY(), 1e-9);
        assertTrue(first.dy() > 0);
        var next = sink.puffs.get(PlumeModel.PHASES * exits.length);
        assertEquals(first.start().newY() + first.dy(), next.start().newY(), 1e-9);
        // No density, no puffs.
        var none = new RecordingSink();
        PlumeModel.write(none, exits, v, PlumeModel.values(40, 2, 3, 4, 0, FAN, 0, 0, 0), segments, PlumeModel.Style.WHITE, 7);
        assertEquals(0, none.quads());
        assertEquals(0, PlumeModel.segments(0, 5, 2, 8));
    }
}
