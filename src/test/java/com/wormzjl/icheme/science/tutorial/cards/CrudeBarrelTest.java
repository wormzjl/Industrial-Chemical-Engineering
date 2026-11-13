package com.wormzjl.icheme.science.tutorial.cards;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The crude barrel recording (batch 2026-11-12-tutorial-system, P2a): the asset equals what the material data give, its
 * shares add to one, its curves rise and end where the barrel's part below 900 K does, and medium crude carries the light
 * ends and residue its specification names. Prints the four grades' band shares.
 */
class CrudeBarrelTest {
    private static final Set<String> LIGHT_ENDS = Set.of("Methane", "Ethane", "Propane", "Isobutane", "N-butane", "Isopentane", "N-pentane");

    @Test
    void theRecordingEqualsTheMaterialData() throws Exception {
        var computed = BarrelData.compute(Path.of("src/main/resources/data/icheme/materials"));
        var recorded = BarrelData.load();
        assertEquals(BarrelData.GRADES, recorded.stream().map(BarrelData.Grade::id).toList());
        for (int g = 0; g < computed.size(); g++) {
            var a = computed.get(g);
            var b = recorded.get(g);
            assertEquals(a.id(), b.id());
            assertEquals(a.sulfur(), b.sulfur(), 1e-9);
            assertEquals(a.segments().size(), b.segments().size(), a.id());
            for (int i = 0; i < a.segments().size(); i++) {
                var x = a.segments().get(i);
                var y = b.segments().get(i);
                assertEquals(x.component(), y.component());
                assertEquals(x.share(), y.share(), 1e-9);
                assertEquals(x.colour(), y.colour());
                assertEquals(x.lower(), y.lower(), 1e-9);
                assertEquals(Double.isNaN(x.upper()), Double.isNaN(y.upper()));
                if (!Double.isNaN(x.upper())) assertEquals(x.upper(), y.upper(), 1e-9);
            }
            assertArrayEquals(a.bands(), b.bands(), 1e-9, a.id());
            assertArrayEquals(a.curve(), b.curve(), 1e-9, a.id());
        }
        var line = new StringBuilder("Band shares:");
        for (var grade : recorded) {
            line.append("\n  ").append(grade.id());
            int[] p = BarrelData.percents(grade.bands());
            for (int i = 0; i < p.length; i++) line.append(String.format(Locale.ROOT, "  %s %.4f (%d %%)", BarrelData.BANDS.get(i), grade.bands()[i], p[i]));
        }
        System.out.println(line);
    }

    @Test
    void sharesAddToOneAndTheCurvesRiseToTheirEnd() {
        for (var grade : BarrelData.load()) {
            double segments = 0, bands = 0, below = 0;
            double previous = Double.NEGATIVE_INFINITY;
            for (var s : grade.segments()) {
                segments += s.share();
                below += s.share() * s.below(BarrelData.CURVE_TO);
                assertTrue(s.lower() >= previous, grade.id() + ": boiling order at " + s.component());
                previous = s.lower();
            }
            for (double b : grade.bands()) bands += b;
            assertEquals(1, segments, 1e-12, grade.id());
            assertEquals(1, bands, 1e-12, grade.id());
            var curve = grade.curve();
            assertEquals(BarrelData.points(), curve.length);
            for (int i = 1; i < curve.length; i++) assertTrue(curve[i] >= curve[i - 1] - 1e-15, grade.id() + " rises at " + i);
            double end = curve[curve.length - 1];
            assertTrue(Math.abs(end - 1) < 1e-12 || Math.abs(end - below) < 1e-12, grade.id() + ": ends at " + end);
            int sum = 0;
            for (int p : BarrelData.percents(grade.bands())) sum += p;
            assertEquals(100, sum, grade.id() + ": whole percents add to 100");
        }
    }

    @Test
    void mediumCrudeHasItsLightEndsAndResidue() {
        var medium = BarrelData.load().getFirst();
        assertEquals("medium_crude", medium.id());
        double light = 0;
        for (var s : medium.segments()) if (LIGHT_ENDS.contains(s.component())) light += s.share();
        assertEquals(.025, light, .002);
        assertEquals(.18, medium.bands()[BarrelData.BANDS.indexOf("residue")], .04);
        assertEquals(1.5, medium.sulfur(), 1e-12);
    }

    @Test
    void wholePercentsGoByLargestRemainder() {
        assertArrayEquals(new int[]{34, 33, 33}, BarrelData.percents(new double[]{1, 1, 1}));
        assertArrayEquals(new int[]{1, 17, 11, 24, 28, 19}, BarrelData.percents(new double[]{.013, .1658, .1106, .2386, .2832, .1889}));
    }
}
