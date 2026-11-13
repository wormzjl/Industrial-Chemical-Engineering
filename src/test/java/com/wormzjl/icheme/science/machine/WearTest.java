package com.wormzjl.icheme.science.machine;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** W10b (build plan revision 2, section 1): a pump's wear from cavitation and from running dry. */
class WearTest {
    private static double hours(double sigma) {
        return Wear.life(sigma, false, Wear.Settings.DEFAULT) / 3600;
    }

    @Test
    void cavitationLivesMatchThePlan() {
        assertEquals(1, Wear.intensity(Wear.PEAK), 1e-12, "the erosion peak is one");
        // Process time (batch 2026-11-11-minecraft-time-delta, owner T3): four times the old online lives, the same play time at factor 4.
        assertEquals(8.0, hours(2.06), .2, "8.0 h at the peak");
        assertEquals(20.4, hours(1), .2, "20.4 h at NPSH3");
        assertEquals(75.6, hours(3.5), .2, "75.6 h at sigma 3.5");
        // The peak is the shortest life.
        for (double s = .1; s < 4; s += .01) assertTrue(hours(s) >= 8.0 - 1e-9, "sigma " + s);
    }

    @Test
    void nothingErodesFromInceptionUpOrWithoutARead() {
        for (double s : new double[]{4, 4.5, 10, Double.POSITIVE_INFINITY, Double.NaN, 0, -1}) {
            assertEquals(0, Wear.intensity(s), "sigma " + s);
            assertEquals(0, Wear.rate(s, false), "sigma " + s);
        }
        assertTrue(Wear.intensity(3.99) > 0);
    }

    /**
     * M-12: every fault has a class; one rule: the largest intensity of a class counts, the classes add. A pump's two
     * terms (cavitation, dry) are today's two terms in today's order, bit for bit.
     */
    @Test
    void oneRateRuleOverTheFaultTable() {
        assertEquals(Wear.FaultClass.EROSION, Wear.Fault.CAVITATION.type);
        assertEquals(Wear.FaultClass.SEIZURE, Wear.Fault.DRY.type);
        assertEquals(Wear.FaultClass.EROSION, Wear.Fault.LOW_FLOW.type);
        assertEquals(Wear.FaultClass.EROSION, Wear.Fault.WET_GAS.type);
        assertEquals(Wear.FaultClass.SEIZURE, Wear.Fault.LIQUID.type);
        assertEquals(Wear.FaultClass.EROSION, Wear.Fault.WET_EXHAUST.type);
        var s = Wear.Settings.DEFAULT;
        for (double sigma : new double[]{Double.NaN, 0, .5, 1, 2.06, 3.5, 3.99, 4, 9})
            for (boolean dry : new boolean[]{false, true}) {
                double before = Wear.intensity(sigma) / (s.cavitationHours() * 3600) + (dry ? 1 / (s.dryMinutes() * 60) : 0);
                assertEquals(Double.doubleToRawLongBits(before), Double.doubleToRawLongBits(Wear.rate(sigma, dry, s)), "sigma " + sigma + " dry " + dry);
                assertEquals(Wear.intensity(sigma), Wear.Fault.CAVITATION.intensity(sigma));
            }
        assertEquals(1, Wear.Fault.DRY.intensity(1));
        assertEquals(0, Wear.Fault.DRY.intensity(0));
        // A dry screw: no erosion, its liquid's seizure term as running dry.
        assertEquals(1 / (s.dryMinutes() * 60), Wear.rate(0, Wear.Fault.LIQUID.intensity(.5), s));
        assertEquals(0, Wear.rate(0, Wear.Fault.LIQUID.intensity(VacuumPump.LIQUID_TRACE), s));
        assertEquals(0, Wear.rate(1, 1, new Wear.Settings(false, 8, 40)), "switched off");
    }

    /**
     * N5 (M-06): a dry screw's wear follows its noise, a ramp from the trace (1 %) to full at 10 % liquid by volume: 40 min
     * of process time from 10 % up, 80 min at 5 %, 400 min just above 1 %, none at and below it.
     */
    @Test
    void aDryScrewsLiquidWearFollowsItsNoise() {
        var s = Wear.Settings.DEFAULT;
        java.util.function.DoubleUnaryOperator minutes = liquid -> {
            double r = Wear.rate(0, Wear.Fault.LIQUID.intensity(liquid), s);
            return r > 0 ? 1 / r / 60 : Double.POSITIVE_INFINITY;
        };
        assertEquals(Double.POSITIVE_INFINITY, minutes.applyAsDouble(VacuumPump.LIQUID_TRACE), "none at the trace");
        assertEquals(400, minutes.applyAsDouble(.01 + 1e-12), 1e-6, "400 min just above 1 %");
        assertEquals(80, minutes.applyAsDouble(.05), 1e-9, "80 min at 5 %");
        assertEquals(40, minutes.applyAsDouble(.1), 1e-9, "40 min at 10 %");
        assertEquals(40, minutes.applyAsDouble(.6), 1e-9, "40 min above 10 %");
        for (double liquid = 0; liquid <= 1; liquid += .001)
            assertEquals((int) Math.round(255 * Wear.Fault.LIQUID.intensity(liquid)), VacuumPump.liquidNoise(liquid), "the noise byte is 255 times the wear's intensity at " + liquid);
    }

    @Test
    void runningDryLastsFortyProcessMinutesAndTheSwitchStopsAllWear() {
        assertEquals(2400, Wear.life(Double.NaN, true, Wear.Settings.DEFAULT), 1e-9);
        var off = new Wear.Settings(false, 2, 10);
        assertEquals(0, Wear.rate(2.06, true, off));
        // The config scales each life.
        var fast = new Wear.Settings(true, 1, 5);
        assertEquals(3600, Wear.life(2.06, false, fast), 3600 * .01);
        assertEquals(300, Wear.life(Double.NaN, true, fast), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> new Wear.Settings(true, 0, 10));
    }
}
