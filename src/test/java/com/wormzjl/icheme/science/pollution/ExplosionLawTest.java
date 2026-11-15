package com.wormzjl.icheme.science.pollution;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The blast numbers of plan section 7. */
class ExplosionLawTest {
    @Test
    void anOpenCubeAtTheLflIsOneTntBlock() {
        double energy = ExplosionLaw.energy(1.0, 1.0);
        assertEquals(7.4e9, energy, 0.01 * 7.4e9, "7.4 GJ");
        double tnt = ExplosionLaw.tntKilograms(energy);
        assertEquals(48.0, tnt, 0.02 * 48.0, "48 kg of TNT");
        assertEquals(4.0, ExplosionLaw.power(tnt, 8.0), 0.1, "power 4");
        assertTrue(ExplosionLaw.flammable(1.0));
        assertFalse(ExplosionLaw.flammable(0.999));
    }

    @Test
    void aRichCubeBurnsAsTheStoichiometricIndex() {
        assertEquals(2.0, ExplosionLaw.burntIndex(5.0));
        assertEquals(ExplosionLaw.energy(2.0, 1.0), ExplosionLaw.energy(5.0, 1.0));
        double power = ExplosionLaw.power(ExplosionLaw.tntKilograms(ExplosionLaw.energy(5.0, 1.0)), 8.0);
        assertEquals(5.0, power, 0.1, "index 2 gives power 5");
    }

    @Test
    void theCapHolds() {
        assertEquals(8.0, ExplosionLaw.power(1e9, 8.0));
        assertEquals(3.0, ExplosionLaw.power(48.0, 3.0));
        assertEquals(0.0, ExplosionLaw.power(0.0, 8.0));
    }

    @Test
    void aTwoHundredCubicMetreRoomAtTheLflGivesPowerNearOneAndAHalf() {
        double power = ExplosionLaw.power(ExplosionLaw.tntKilograms(ExplosionLaw.energy(1.0, 200.0 / 4096.0)), 8.0);
        assertTrue(power > 1.3 && power < 1.7, "power " + power);
    }

    /** WP6b: the strength at a cell falls linearly to nothing at twice the power; a TNT block breaches equipment within 4 m. */
    @Test
    void theStrengthAtACellFallsToNothingAtTwiceThePower() {
        assertEquals(4.0, ExplosionLaw.strengthAt(4.0, 0.0), 1e-12, "power 4 at the centre");
        assertEquals(2.0, ExplosionLaw.strengthAt(4.0, 4.0), 1e-12, "power 4 at 4 m");
        assertEquals(0.0, ExplosionLaw.strengthAt(4.0, 8.0), 1e-12, "power 4 at 8 m");
        assertEquals(0.0, ExplosionLaw.strengthAt(4.0, 20.0), 1e-12, "nothing beyond");
        assertEquals(0.0, ExplosionLaw.strengthAt(0.0, 0.0), 1e-12, "no blast");
        assertEquals(0.0, ExplosionLaw.strengthAt(4.0, Double.NaN), 1e-12, "an unknown distance reaches nothing");
        assertTrue(ExplosionLaw.strengthAt(4.0, 4.0) >= ExplosionLaw.BREACH_POWER);
        assertTrue(ExplosionLaw.strengthAt(4.0, 4.01) < ExplosionLaw.BREACH_POWER);
        assertEquals(4.0, ExplosionLaw.reach(4.0, ExplosionLaw.BREACH_POWER), 1e-12, "a TNT block breaches within 4 m");
        assertEquals(12.0, ExplosionLaw.reach(8.0, ExplosionLaw.BREACH_POWER), 1e-12, "the cap's blast within 12 m");
        assertEquals(0.0, ExplosionLaw.reach(1.5, ExplosionLaw.BREACH_POWER), 1e-12, "a weak blast breaches nothing");
    }
}
