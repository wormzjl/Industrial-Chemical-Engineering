package com.wormzjl.icheme.science.machine;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.machine.ShaftTrain.Drive;
import com.wormzjl.icheme.science.machine.ShaftTrain.Driver;
import com.wormzjl.icheme.science.machine.ShaftTrain.Measure;
import com.wormzjl.icheme.science.machine.ShaftTrain.Mode;
import com.wormzjl.icheme.science.machine.ShaftTrain.Trip;
import org.junit.jupiter.api.Test;

/**
 * A fuelled prime mover's governor (batch 2026-11-10-prime-movers, owner P11): it holds its set speed on the power its fuel
 * gives, never runs above its set speed or its rated speed, never trips, and short of fuel slows and says LOW FUEL.
 */
class ShaftTrainFuelTest {
    private static final double RATED = 30e6, RPM = 3000;

    private static Driver set(double setPoint) {
        return new Driver(Drive.FUEL, RATED, RPM, 0, true, Mode.SPEED, setPoint, 0, Trip.NONE, true);
    }

    private static Measure fuel(double demand, double available) {
        return new Measure(demand, available, false, Double.NaN, Double.NaN, Double.NaN, Double.NaN, true);
    }

    @Test void itHoldsItsSetSpeedOnEnoughFuel() {
        var step = ShaftTrain.step(set(1), RPM, fuel(20e6, 30e6));
        assertEquals(RPM, step.speed(), 1e-9);
        assertEquals(Trip.NONE, step.trip());
        assertEquals(.9 * RPM, ShaftTrain.step(set(.9), .9 * RPM, fuel(10e6, 30e6)).speed(), 1e-9, "a lower set speed");
    }

    /** No overspeed: a set point over its rating reads as its rating; a lost load leaves the speed at its set, with no trip. */
    @Test void itNeverRunsAboveItsSetOrRatedSpeed() {
        assertEquals(RPM, ShaftTrain.step(set(1.2), RPM, fuel(10e6, 30e6)).speed(), 1e-9, "set over 100 % holds the rating");
        for (double speed : new double[]{.5 * RPM, RPM, 1.1 * RPM}) {
            var lost = ShaftTrain.step(set(1), speed, fuel(0, 30e6));
            assertTrue(lost.speed() <= RPM + 1e-9, "a lost load at " + speed + " rpm: " + lost.speed());
            assertEquals(Trip.NONE, lost.trip());
            assertFalse(lost.status().contains("TRIP"), lost.status());
        }
    }

    @Test void shortOfFuelItSlowsAndSaysLowFuel() {
        var step = ShaftTrain.step(set(1), RPM, fuel(30e6, 15e6));
        assertTrue(step.speed() < RPM, "slows: " + step.speed());
        assertEquals("LOW FUEL", step.status());
        assertEquals(Trip.NONE, step.trip());
    }

    @Test void stoppedItStands() {
        var stopped = new Driver(Drive.FUEL, RATED, RPM, 0, false, Mode.SPEED, 1, 0, Trip.NONE, true);
        assertEquals(0, ShaftTrain.step(stopped, RPM, fuel(10e6, 30e6)).speed(), 0);
    }
}
