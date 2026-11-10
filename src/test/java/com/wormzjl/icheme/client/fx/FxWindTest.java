package com.wormzjl.icheme.client.fx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FxWindTest {
    @Test
    void sameTimeAndDimensionGiveTheSameWind() {
        for (double t : new double[]{0, 13.7, 5000, 1e6}) {
            assertEquals(FxWind.vector("minecraft:overworld", t), FxWind.vector("minecraft:overworld", t));
        }
    }

    @Test
    void dimensionsDiffer() {
        assertNotEquals(FxWind.vector("minecraft:overworld", 100), FxWind.vector("minecraft:the_nether", 100));
    }

    @Test
    void speedStaysInRangeAndTheDirectionTurnsSlowly() {
        double previous = Double.NaN;
        for (int i = 0; i <= 20_000; i++) {
            double t = i * 5.0;
            var wind = FxWind.vector("minecraft:overworld", t);
            assertTrue(wind.speed() >= FxWind.MIN_SPEED - 1e-9 && wind.speed() <= FxWind.MAX_SPEED + 1e-9, "speed " + wind.speed());
            double angle = Math.atan2(wind.z(), wind.x());
            if (!Double.isNaN(previous)) {
                double turn = Math.abs(Math.IEEEremainder(angle - previous, 2 * Math.PI));
                assertTrue(Math.toDegrees(turn) < 5 * .5, "turned " + Math.toDegrees(turn) + "° in 5 s");
            }
            previous = angle;
        }
    }

    @Test
    void gameTimeAndPartialTickMapToSeconds() {
        assertEquals(FxWind.vector("minecraft:overworld", 50.5), FxWind.vector("minecraft:overworld", FxGlide.seconds(1000, 10)));
    }
}
