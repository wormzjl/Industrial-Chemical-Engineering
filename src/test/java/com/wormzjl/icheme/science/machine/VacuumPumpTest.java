package com.wormzjl.icheme.science.machine;

import org.junit.jupiter.api.Test;

import static com.wormzjl.icheme.science.machine.VacuumPump.Type.*;
import static org.junit.jupiter.api.Assertions.*;

/** The vacuum pump curves (batch 2026-11-10-vacuum-pump, VACUUM_PUMP_PLAN.md section 2 and the model table of section 4). */
class VacuumPumpTest {
    private static VacuumPump pump(VacuumPump.Type type, double m3h) {
        return new VacuumPump(type, m3h / 3600, 1450);
    }

    @Test void theLiquidRingsShareFollowsItsSealWatersVapourPressure() {
        var one = pump(LIQUID_RING_ONE_STAGE, 500);
        var two = pump(LIQUID_RING_TWO_STAGE, 500);
        double[][] table = {{101325, .94, .97}, {1e4, .47, .68}, {5e3, .13, .37}};
        for (var row : table) {
            assertEquals(row[1], one.share(row[0]), .01, "one stage at " + row[0]);
            assertEquals(row[2], two.share(row[0]), .01, "two stages at " + row[0]);
        }
        assertEquals(0, one.share(VacuumPump.SEAL_VAPOUR_PRESSURE));
        assertEquals(0, two.share(3000));
    }

    @Test void theDryScrewIsFlatToItsUltimate() {
        var screw = pump(DRY_SCREW, 650);
        assertEquals(.999, screw.share(1e3), 1e-12);
        assertEquals(.9, screw.share(10), 1e-12);
        assertEquals(0, screw.share(1));
    }

    @Test void capacityScalesWithSpeed() {
        var two = pump(LIQUID_RING_TWO_STAGE, 500);
        assertEquals(.5 * two.suction(2e4, 1), two.suction(2e4, .5), 1e-15);
    }

    @Test void theRowDeliversTheCapacityUnderTheReliefRiseAndNothingAtBlankOff() {
        var two = pump(LIQUID_RING_TWO_STAGE, 500);
        double p = 1e4, q = two.suction(p, 1);
        double rise = 101325 - p;
        // On the slip line well under the relief rise.
        var row = two.row(1, p);
        assertEquals(q, row.flow(rise, 1), .01 * q);
        assertEquals(0, two.residual(row.flow(rise, 1), rise, 1, .1, p, 1.4), 1e-6 * VacuumPump.reliefRise(p));
        assertTrue(two.row(1, 3000).flow(rise, 1) < 1e-11, "nothing moves at blank-off");
    }

    @Test void theOneStageRingsPowerPeaksNearFortyKilopascals() {
        var one = pump(LIQUID_RING_ONE_STAGE, 500);
        double best = 0, at = 0;
        for (int i = 10; i <= 1000; i++) {
            double p = 101325 * i / 1000, power = one.shaftPower(one.suction(p, 1), 1, 1, p, 1.4);
            if (power > best) { best = power; at = p; }
        }
        assertEquals(43e3, at, 3e3);
        assertEquals(14.3e3, one.maximumShaftPower(), .1e3);
        assertEquals(18.5e3, Motor.ratingFor(one.maximumShaftPower()));
    }

    @Test void theModelsTakeTheirTableMotors() {
        double[][] models = {{0, 150, 5.5}, {0, 500, 18.5}, {0, 1500, 55}, {0, 5000, 200}, {1, 150, 7.5}, {1, 500, 22}, {1, 1500, 75},
                {2, 100, 3}, {2, 300, 7.5}, {2, 650, 15}, {2, 2500, 75}};
        for (var m : models) {
            var law = pump(VacuumPump.Type.values()[(int) m[0]], m[1]);
            assertEquals(m[2] * 1e3, Motor.ratingFor(law.maximumShaftPower()), law.type() + " " + m[1]);
        }
    }

    @Test void theScrewsPowerFallsToItsVendorShareAtDeepVacuum() {
        var screw = pump(DRY_SCREW, 650);
        double rated = screw.shaftPower(0, 1, 1, 101325, 1.4);
        assertEquals(19 * 650, rated, 1e-9);
        assertEquals(.61, screw.shaftPower(0, 1, 1, 1, 1.4) / rated, .01);
        assertEquals(.6 * rated / 4, screw.shaftPower(0, .5, 1, 1e-9, 1.4), .01 * rated);
    }

    @Test void theScrewRunsHotAndTheRingDoesNot() {
        var screw = pump(DRY_SCREW, 650);
        double t = 300, r = 287.05 * t, p = 5e4, rho = p / r, k = 1.4, m = 1;
        // T_d = T_s [1 + 0.6 (1 − p_s/p_d)]: 50 kPa to 1 atm heats air from 300 K by 90 K.
        double heat = screw.fluidPower(m, 101325 - p, rho, p, k);
        assertEquals(1005 * 300 * .6 * (1 - p / 101325), heat, .01 * heat);
        assertEquals(0, pump(LIQUID_RING_ONE_STAGE, 500).fluidPower(m, 101325 - p, rho, p, k));
    }

    @Test void aStoppedPumpTakesNoPower() {
        assertEquals(0, pump(DRY_SCREW, 100).shaftPower(1, 0, 1, 1e4, 1.4));
    }
}
