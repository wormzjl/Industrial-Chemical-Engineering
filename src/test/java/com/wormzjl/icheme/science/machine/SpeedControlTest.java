package com.wormzjl.icheme.science.machine;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.math.DeterministicMath;
import org.junit.jupiter.api.Test;

/** The drivers' speed laws of plan section 4.1. */
class SpeedControlTest {
    private static final CentrifugalPump PUMP = new CentrifugalPump(100 / 3600.0, 80, .72);

    /** A positive-displacement machine's speed for {@code setFlow}: the set flow over the displacement (m³/s each), exact and
     * without feedback, within the drive's range (plan section 4.1; no driver sets it yet, so it moved here from
     * {@code SpeedControl} at the batch's tooling cleanup). */
    static double displacementSpeed(double setFlow, double displacement, double lowest, double highest) {
        return Math.min(highest, Math.max(lowest, setFlow / displacement));
    }

    @Test void displacementSpeedIsExact() {
        assertEquals(.7, displacementSpeed(.007, .01, .1, 1), 1e-15);
        assertEquals(.1, displacementSpeed(0, .01, .1, 1), 0);
        assertEquals(1, displacementSpeed(.02, .01, .1, 1), 0);
    }

    /** The pump of plan section 1.1 on its line with 30 m static head, regulated from full speed to 70 % flow. */
    @Test void regulatesAPumpOnALineWithStaticHeadInAFewSlices() {
        double speed = 1, flow = flowOnLine(speed), previousSpeed = Double.NaN, previousFlow = Double.NaN;
        int slices = 0;
        while (Math.abs(flow - .7) > 1e-3) {
            double next = SpeedControl.regulate(.7, speed, flow, previousSpeed, previousFlow, 1, Motor.LOWEST_SPEED, 1);
            previousSpeed = speed;
            previousFlow = flow;
            speed = next;
            flow = flowOnLine(speed);
            assertTrue(++slices <= 4, "settled after " + slices + " slices");
        }
        assertEquals(.808, speed, 2e-3);
    }

    @Test void aSecantTheWrongWayFallsBackOnTheAffinityGuess() {
        assertEquals(.9 * .7 / .8, SpeedControl.regulate(.7, .9, .8, 1, .75, 1, .1, 1), 1e-15);
        assertEquals(.9 * Math.sqrt(.7 / .8), SpeedControl.regulate(.7, .9, .8, Double.NaN, Double.NaN, 2, .1, 1), 1e-15);
        assertEquals(.7, SpeedControl.regulate(2, .5, 0, Double.NaN, Double.NaN, 1, .1, 1), 1e-15, "nothing measured: one step up");
        assertEquals(1, SpeedControl.regulate(.7, .9, .1, Double.NaN, Double.NaN, 1, .1, 1), 0, "a step at most 0.2, within the range");
    }

    /** A load whose power goes as {@code s^m}: each slice multiplies the error of {@code ln s} by {@code 1 − m/6}. */
    @Test void powerLimitedStepConvergesForLoadsBelowTheTwelfthPower() {
        for (double m : new double[]{1, 3, 6, 9, 11}) {
            double target = .6, available = DeterministicMath.pow(target, m), speed = 1;
            for (int slice = 0; slice < 80; slice++) {
                double error = DeterministicMath.log(speed / target);
                speed = SpeedControl.powerLimited(speed, available, DeterministicMath.pow(speed, m), 1);
                if (Math.abs(error) > 1e-6) assertEquals(1 - m / 6, DeterministicMath.log(speed / target) / error, 1e-9, "m " + m + " slice " + slice);
            }
            assertEquals(target, speed, 1e-6, "m " + m);
        }
        assertEquals(.8, SpeedControl.powerLimited(.5, 1e9, 1, .8), 0, "with power enough, back to the set speed");
        assertEquals(.8, SpeedControl.powerLimited(.5, 1, 0, .8), 0, "no demand");
    }

    private static double flowOnLine(double speed) {
        double low = 0, high = 3;
        for (int i = 0; i < 200; i++) {
            double q = (low + high) / 2;
            if (PUMP.head(q * PUMP.ratedFlow(), speed) / PUMP.ratedHead() > .375 + .625 * q * q) low = q;
            else high = q;
        }
        return low;
    }
}
