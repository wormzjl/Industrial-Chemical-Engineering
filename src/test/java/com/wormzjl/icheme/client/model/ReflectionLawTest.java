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
    void theSheenIsTheHorizonsBrightness() {
        // 0.2126 x 0.93 + 0.7152 x 0.95 + 0.0722 x 0.97 = 0.94719
        assertEquals(.94719, ReflectionLaw.surround(ReflectionLaw.HORIZON), E);
        assertEquals(0, ReflectionLaw.surround(new double[3]), E, "a black fog (night, a cave): no sheen");
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
        double added = ReflectionLaw.added(n, n, ReflectionClass.PAINT, ReflectionLaw.HORIZON, noon, 1, 1, 1);
        // the horizon's brightness 0.94719 x F0 0.05 = 0.04736, no highlight (R.L = 0)
        assertEquals(.04736, added, E);
        assertEquals(0, ReflectionLaw.added(n, n, ReflectionClass.PAINT, ReflectionLaw.HORIZON, noon, 0, 1, 1), 0, "no sky light, no sheen");
    }

    @Test
    void theBaseKeepsWhatTheSheenTakes() {
        double[] n = {0, 0, 1};
        // head-on: F = F0; steel and jacketing keep 0.80 of the chunk mesh's colour, grating 0.90, paint 0.95
        assertEquals(.80, ReflectionLaw.kept(n, n, ReflectionClass.STEEL, 1, 1, 1), E);
        assertEquals(.80, ReflectionLaw.kept(n, n, ReflectionClass.CLADDING, 1, 1, 1), E);
        assertEquals(.90, ReflectionLaw.kept(n, n, ReflectionClass.GALVANISED, 1, 1, 1), E);
        assertEquals(.95, ReflectionLaw.kept(n, n, ReflectionClass.PAINT, 1, 1, 1), E);
        // no sky light or the range's edge: the base is kept whole
        assertEquals(1, ReflectionLaw.kept(n, n, ReflectionClass.CLADDING, 0, 1, 1), E);
        assertEquals(1, ReflectionLaw.kept(n, n, ReflectionClass.CLADDING, 1, 1, 0), E);
    }

    @Test
    void theSheenIsTheSameFromAboveAndBelow() {
        // a clad wall seen 40 degrees from above and from below, at noon: the same grey either way, under full white
        double a = Math.toRadians(40), base = 196 / 255.0 * .8;
        double[] wall = {0, 0, 1}, noon = ReflectionLaw.celestial(0, 0);
        double[] above = {0, Math.sin(a), Math.cos(a)}, below = {0, -Math.sin(a), Math.cos(a)};
        double up = base * ReflectionLaw.kept(wall, above, ReflectionClass.CLADDING, 1, 1, 1) + ReflectionLaw.added(wall, above, ReflectionClass.CLADDING, ReflectionLaw.HORIZON, noon, 1, 1, 1);
        double down = base * ReflectionLaw.kept(wall, below, ReflectionClass.CLADDING, 1, 1, 1) + ReflectionLaw.added(wall, below, ReflectionClass.CLADDING, ReflectionLaw.HORIZON, noon, 1, 1, 1);
        // 0.61490 x 0.79944 + 0.94719 x 0.20056 = 0.68155 (the highlight from below: R.L = sin 40, ^30 x 0.5 = 9e-7)
        assertEquals(.68155, down, 1e-4);
        assertEquals(down, up, 1e-4, "no sky above and ground below any more");
    }

    @Test
    void strengthScalesTheWholePass() {
        double[] n = {0, 0, 1}, noon = ReflectionLaw.celestial(0, 0);
        double one = ReflectionLaw.added(n, n, ReflectionClass.STEEL, ReflectionLaw.HORIZON, noon, 1, 1, 1);
        // 0.94719 x 0.20 = 0.18944
        assertEquals(.18944, one, E);
        assertEquals(2 * one, ReflectionLaw.added(n, n, ReflectionClass.STEEL, ReflectionLaw.HORIZON, noon, 1, 2, 1), E, "reflection_strength scales the whole pass");
    }
}
