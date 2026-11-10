package com.wormzjl.icheme.client.fx;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FxAmbientTest {
    private static final double NOON = 1, MIDNIGHT = -1;

    @Test
    void biomeTemperatureMapsToCelsius() {
        assertEquals(25, FxAmbient.celsius(.8), 1e-9);
        assertEquals(0, FxAmbient.celsius(.15), 1e-9);
        assertEquals(40, FxAmbient.celsius(2), 1e-9);
        assertEquals(-20, FxAmbient.celsius(-.7), 1e-9);
    }

    @Test
    void plainsAtNoonAreTheModsAmbient() {
        var a = FxAmbient.of(.8, .4, 64, 0, NOON);
        assertEquals(25, a.celsius(), 1e-9);
        assertEquals(.54, a.humidity(), 1e-9);
    }

    @Test
    void heightRainAndNightCool() {
        assertEquals(.8 - .05, FxAmbient.heightAdjusted(.8, 120), 1e-12);
        assertEquals(.8, FxAmbient.heightAdjusted(.8, 80), 1e-12);
        var rain = FxAmbient.of(.8, .4, 64, 1, NOON);
        assertEquals(22, rain.celsius(), 1e-9);
        assertEquals(.54 + .8 * .46, rain.humidity(), 1e-9);
        assertEquals(20, FxAmbient.of(.8, .4, 64, 0, MIDNIGHT).celsius(), 1e-9);
        assertEquals(17, FxAmbient.of(.8, .4, 64, 1, MIDNIGHT).celsius(), 1e-9);
    }

    @Test
    void boundsHold() {
        var snowy = FxAmbient.of(-.7, 0, 250, 1, MIDNIGHT);
        assertEquals(-20, snowy.celsius(), 1e-9);
        var desert = FxAmbient.of(2, 0, 64, 0, NOON);
        assertEquals(40, desert.celsius(), 1e-9);
        assertEquals(.3, desert.humidity(), 1e-9);
        assertTrue(FxAmbient.humidity(5, 5) <= 1);
    }

    @Test
    void nightShareRampsAroundTheHorizon() {
        assertEquals(0, FxAmbient.night(.2), 1e-12);
        assertEquals(.5, FxAmbient.night(0), 1e-12);
        assertEquals(1, FxAmbient.night(-.2), 1e-12);
    }
}
