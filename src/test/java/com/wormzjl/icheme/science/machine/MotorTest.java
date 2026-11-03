package com.wormzjl.icheme.science.machine;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Motor sizes, speeds and IE3 efficiencies (plan sections 1.3 and 7). */
class MotorTest {
    @Test void ratingsRunFromThreeQuarterKilowattToTwentyMegawatts() {
        assertEquals(60, Motor.ratingCount());
        assertEquals(750, Motor.rating(0), 0);
        assertEquals(20e6, Motor.rating(Motor.ratingCount() - 1), 0);
        for (int i = 1; i < Motor.ratingCount(); i++) assertTrue(Motor.rating(i) > Motor.rating(i - 1));
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
            for (int i = 0; i < Motor.ratingCount(); i++) {
                double eta = Motor.efficiency(Motor.rating(i), poles);
                assertTrue(eta >= last, poles + " poles at " + Motor.rating(i));
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
