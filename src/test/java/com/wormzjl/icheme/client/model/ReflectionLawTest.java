package com.wormzjl.icheme.client.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The reflection law against numbers worked by hand (REFLECTION_PASS_REVIEW.md section 2). */
class ReflectionLawTest {
    private static final double E = 1e-4;

    @Test
    void fresnelRisesFromF0HeadOnToOneAtGrazing() {
        assertEquals(.05, ReflectionLaw.fresnel(.05, 1), E);
        assertEquals(1, ReflectionLaw.fresnel(.05, 0), E);
        // 0.55 + 0.45 * 0.5^5 = 0.5640625
        assertEquals(.5640625, ReflectionLaw.fresnel(.55, .5), 1e-9);
        // 0.05 + 0.95 * 0.3^5 = 0.0523085: paint at 45 degrees is still mostly its own colour
        assertEquals(.0523085, ReflectionLaw.fresnel(.05, Math.cos(Math.toRadians(45.57))), E);
    }

    @Test
    void theSkyRunsFromTheHorizonToTheZenithAndTheGroundBelow() {
        double[] z = ReflectionLaw.ZENITH, h = ReflectionLaw.HORIZON;
        assertArrayEquals(z, ReflectionLaw.sky(1, .3, z, h, 1), E, "straight up: the zenith");
        // at the horizon the join is half: (0.93 + 0.42) / 2 = 0.675 in red
        assertEquals(.675, ReflectionLaw.sky(0, .3, z, h, 1)[0], E);
        // half way up: 0.5^0.45 = 0.73204 of the way from 0.93 to 0.45 = 0.57862
        assertEquals(.57862, ReflectionLaw.sky(.5, .3, z, h, 1)[0], E);
        // straight down at half daylight: the ground 0.25 x 0.5
        assertEquals(.125, ReflectionLaw.sky(-1, .3, z, h, .5)[0], E);
        // a sharper horizon: at ry = 0.1 the cladding's (blur 0.18) join is 0.87380, the paint's (0.30) 0.74074
        assertEquals(.87380, ReflectionLaw.smoothstep(-ReflectionClass.CLADDING.blur, ReflectionClass.CLADDING.blur, .1), E);
        assertEquals(.74074, ReflectionLaw.smoothstep(-ReflectionClass.PAINT.blur, ReflectionClass.PAINT.blur, .1), E);
    }

    @Test
    void theHighlightFollowsTheMirrorDirection() {
        double[] up = {0, 1, 0};
        assertEquals(.9, ReflectionLaw.highlight(up, up, 120, .9), E);
        double a = Math.toRadians(10);
        // cos(10)^120 = 0.15928, x 0.9 = 0.14335 for paint; cos(10)^60 x 1.6 = 0.63857 for steel: the wider lobe
        assertEquals(.14335, ReflectionLaw.highlight(new double[]{Math.sin(a), Math.cos(a), 0}, up, 120, .9), E);
        assertEquals(.63857, ReflectionLaw.highlight(new double[]{Math.sin(a), Math.cos(a), 0}, up, 60, 1.6), E);
        assertEquals(0, ReflectionLaw.highlight(new double[]{0, -1, 0}, up, 60, 1.6));
        assertArrayEquals(new double[]{0, 1, 0}, ReflectionLaw.reflect(new double[]{0, 1, 0}, up), E);
        double s = Math.sqrt(.5);
        assertArrayEquals(new double[]{-s, s, 0}, ReflectionLaw.reflect(new double[]{s, s, 0}, up), E, "a view from 45 degrees mirrors to the other side");
    }

    @Test
    void theSunAndTheMoon() {
        assertArrayEquals(new double[]{0, 1, 0, 1}, ReflectionLaw.celestial(0, 0), E, "noon: overhead, full");
        assertArrayEquals(new double[]{0, 1, 0, ReflectionLaw.MOON}, ReflectionLaw.celestial(Math.PI, 0), E, "midnight: the moon overhead, weak");
        assertEquals(0, ReflectionLaw.celestial(0, 1)[3], E, "rain takes the highlight away");
        // sunset: the sun on the horizon in the west (-x), smoothstep(-0.05, 0.15, 0) = 0.15625
        double[] dusk = ReflectionLaw.celestial(Math.PI / 2, 0);
        assertEquals(-1, dusk[0], E);
        assertEquals(.15625, dusk[3], E);
    }

    @Test
    void theRangeFadesOverItsLastQuarter() {
        assertEquals(1, ReflectionLaw.fade(0, 96), E);
        assertEquals(1, ReflectionLaw.fade(72, 96), E);
        assertEquals(.5, ReflectionLaw.fade(84, 96), E);
        assertEquals(0, ReflectionLaw.fade(96, 96), E);
    }

    @Test
    void paintSeenHeadOnGainsAtMostFivePercent() {
        double[] n = {0, 0, 1}, noon = ReflectionLaw.celestial(0, 0);
        double[] added = ReflectionLaw.added(n, n, ReflectionClass.PAINT, 0xffffff, ReflectionLaw.ZENITH, ReflectionLaw.HORIZON, 1, noon, 1, 1, 1);
        // R = (0, 0, 1): the horizon; 0.675 x F0 0.05 = 0.03375 in red, no highlight (R.L = 0)
        assertEquals(.03375, added[0], E);
        for (double c : added) assertTrue(c <= .05, "under 5 % head-on");
        double[] indoors = ReflectionLaw.added(n, n, ReflectionClass.PAINT, 0xffffff, ReflectionLaw.ZENITH, ReflectionLaw.HORIZON, 1, noon, 0, 1, 1);
        assertArrayEquals(new double[3], indoors, 0, "no sky light, no reflection");
    }

    @Test
    void steelTakesItsOwnColourAndPaintStaysWhite() {
        double[] n = {0, 0, 1}, v = {0, 0, 1}, noon = ReflectionLaw.celestial(0, 0);
        double[] steel = ReflectionLaw.added(n, v, ReflectionClass.STEEL, 0x808080, ReflectionLaw.ZENITH, ReflectionLaw.HORIZON, 1, noon, 1, 1, 1);
        // 0.675 x 0.55 x 128/255 = 0.18635
        assertEquals(.18635, steel[0], E);
        double[] doubled = ReflectionLaw.added(n, v, ReflectionClass.STEEL, 0x808080, ReflectionLaw.ZENITH, ReflectionLaw.HORIZON, 1, noon, 1, 2, 1);
        assertEquals(2 * steel[0], doubled[0], E, "reflection_strength scales the whole pass");
    }
}
