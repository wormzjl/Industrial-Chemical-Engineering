package com.wormzjl.icheme.science.machine;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Motor sizes, speeds and IE3 efficiencies (plan sections 1.3 and 7). */
class MotorTest {
    /**
     * Generators to 400 MW (batch 2026-11-10-prime-movers, owner P9 A): the rating holds the 20 % spare (300 MW asks 360,
     * rated 400); the efficiency runs from the IE3 table at 1 MW to 98.8 % at 300 MW in log power; 12 poles read as 8.
     */
    @Test void largeGeneratorsRateAndRunToFourHundredMegawatts() {
        assertEquals(4e8, Motor.generatorRatingFor(3e8), 1);
        assertEquals(Double.NaN, Motor.generatorRatingFor(4e8));
        assertEquals(.988, Motor.generatorEfficiency(3e8, 2), 1e-12);
        assertEquals(.988, Motor.generatorEfficiency(4e8, 2), 1e-12, "flat above 300 MW");
        assertEquals(Motor.efficiency(1e6, 2), Motor.generatorEfficiency(1e6, 2), 1e-12, "the IE3 table to 1 MW");
        assertEquals(Motor.efficiency(5e5, 8), Motor.generatorEfficiency(5e5, 12), 1e-12, "12 poles read as 8");
        double last = 0;
        for (double w : new double[]{1e6, 5e6, 2e7, 1e8, 3e8}) {
            double eta = Motor.generatorEfficiency(w, 2);
            assertTrue(eta >= last, "rising with size at " + w);
            last = eta;
        }
    }

    /** The standard ratings, W, smallest first, walked through {@link Motor#standardRating} (the index accessors left
     * {@code Motor} at the batch's tooling cleanup: the game reads only the next rating above a power). */
    private static final double[] RATINGS = ratings();

    private static double[] ratings() {
        var out = new java.util.ArrayList<Double>();
        for (double r = Motor.standardRating(0); !Double.isNaN(r); r = Motor.standardRating(r * (1 + 1e-9))) out.add(r);
        return out.stream().mapToDouble(Double::doubleValue).toArray();
    }

    @Test void ratingsRunFromThreeQuarterKilowattToTwentyMegawatts() {
        assertEquals(60, RATINGS.length);
        assertEquals(750, RATINGS[0], 0);
        assertEquals(20e6, RATINGS[RATINGS.length - 1], 0);
        for (int i = 1; i < RATINGS.length; i++) assertTrue(RATINGS[i] > RATINGS[i - 1]);
    }

    @Test void aProposedMotorCarriesTheSpare() {
        assertEquals(30e3, Motor.ratingFor(24.2e3), 0, "the 24 kW pump: 29.0 kW with the spare, so 30 kW");
        assertEquals(30e3, Motor.ratingFor(25e3), 0, "exactly a standard rating");
        assertEquals(1e6, Motor.ratingFor(830e3), 0, "the compressor's 830 kW shaft");
        assertEquals(37e3, Motor.standardRating(30.1e3), 0);
        assertTrue(Double.isNaN(Motor.ratingFor(17e6)), "no standard motor above 20 MW");
    }

    @Test void ie3EfficiencyFromTheTable() {
        assertEquals(.933, Motor.efficiency(30e3, 2), 1e-12);
        assertEquals(.936, Motor.efficiency(30e3, 4), 1e-12);
        assertEquals(.904, Motor.efficiency(7.5e3, 4), 1e-12);
        assertEquals(.750, Motor.efficiency(750, 8), 1e-12);
        assertEquals(.958, Motor.efficiency(1e6, 2), 1e-12);
        assertEquals(.960, Motor.efficiency(10e6, 4), 1e-12, "held above the table");
        double between = Motor.efficiency(26e3, 4);
        assertTrue(between > .930 && between < .936, "between 22 and 30 kW");
        for (int poles = 2; poles <= 8; poles += 2) {
            double last = 0;
            for (int i = 0; i < RATINGS.length; i++) {
                double eta = Motor.efficiency(RATINGS[i], poles);
                assertTrue(eta >= last, poles + " poles at " + RATINGS[i]);
                last = eta;
            }
        }
    }

    @Test void electricPowerOfThePlanExamples() {
        assertEquals(25.94e3, Motor.electricPower(24.2e3, 30e3, 2, false), 10, "the 24 kW pump takes 26 kW");
        assertEquals(866.4e3, Motor.electricPower(830e3, 1e6, 2, false), 100, "the compressor's motor takes 865 kW");
        assertEquals(Motor.electricPower(830e3, 1e6, 2, false) / .97, Motor.electricPower(830e3, 1e6, 2, true), 1e-6);
        assertEquals(0, Motor.electricPower(-5, 1e6, 2, false), 0);
    }

    @Test void speedsOfEachPoleCount() {
        assertEquals(2950, Motor.ratedSpeed(2), 0);
        assertEquals(1475, Motor.ratedSpeed(4), 0);
        assertEquals(985, Motor.ratedSpeed(6), 0);
        assertEquals(740, Motor.ratedSpeed(8), 0);
        assertThrows(IllegalArgumentException.class, () -> Motor.ratedSpeed(3));
        assertThrows(IllegalArgumentException.class, () -> Motor.efficiency(1e3, 10));
    }
}
