package com.wormzjl.icheme.science.machine;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** NPSH3 of plan section 3.6 (owner decision D20 Q11 A) against TWO_PHASE_MACHINES_RESEARCH_REVIEW.md section 3 and
 * tmp/rotating-machinery/two-phase/laws.js. */
class NpshTest {
    @Test void theRatedValueFollowsASuctionSpecificSpeedOf200() {
        assertEquals(3.3, Npsh.rated(100 / 3600.0, 2950), .05, "plan 1.1's pump, 100 m³/h at 2950 rpm");
        assertEquals(1.3, Npsh.rated(100 / 3600.0, 1475), .05, "at 1475 rpm");
        assertEquals(9.7, Npsh.rated(500 / 3600.0, 2950), .05, "500 m³/h at 2950 rpm");
        assertEquals(1.7, Npsh.rated(.01, Motor.ratedSpeed(2)), .05, "the in-line mover's default, 0.01 m³/s at the 2-pole speed (D19)");
        double n = 2950, q = .02, nr = Npsh.rated(q, n);
        assertEquals(Npsh.SUCTION_SPECIFIC_SPEED, n * Math.sqrt(q) / Math.pow(nr, .75), 1e-9, "S = n √Q / NPSH3^0.75");
        assertThrows(IllegalArgumentException.class, () -> Npsh.rated(0, 2950));
        assertThrows(IllegalArgumentException.class, () -> Npsh.rated(.01, 0));
    }

    /** Owner decision D21: a pump chosen for its duty turns at the speed that keeps its NPSH3 at 3.3 m, at most the motor's. */
    @Test void aPumpChosenForItsDutyNeedsAtMost3Point3Metres() {
        double twoPole = Motor.ratedSpeed(2);
        assertEquals(twoPole, Npsh.dutySpeed(.01, twoPole), 0, "the default in-line mover turns at the 2-pole speed");
        assertEquals(Npsh.rated(.01, twoPole), Npsh.forDuty(.01, twoPole).ratedNpsh3(), 0, "1.7 m, as at 2950 rpm");
        for (double q : new double[]{.03, .1, .3, 1.5, 2}) {
            assertEquals(Npsh.DUTY_NPSH3, Npsh.forDuty(q, twoPole).ratedNpsh3(), 1e-9, "3.3 m at " + q + " m³/s");
            assertTrue(Npsh.dutySpeed(q, twoPole) < twoPole, "slower than 2-pole at " + q + " m³/s");
        }
        assertEquals(400, Npsh.dutySpeed(1.5, twoPole), 1, "about 400 rpm at 1.5 m³/s");
        assertEquals(1, Npsh.forDuty(.5, twoPole).speed(), 0, "it runs at its rated speed");
        assertThrows(IllegalArgumentException.class, () -> Npsh.dutySpeed(0, twoPole));
    }

    @Test void itGrowsWithFlowAndWithSpeedSquared() {
        var design = new Npsh(.01, 1, 2);
        assertEquals(.4 * 2, design.required(0), 1e-12, "0.40 at shutoff");
        assertEquals(.55 * 2, design.required(.005), 1e-12, "0.55 at half flow");
        assertEquals(2, design.required(.01), 1e-12, "the rated value at the rated flow");
        assertEquals(1.264 * 2, design.required(.012), 1e-12, "1.26 at 120 %");
        assertEquals(1.75 * 2, design.required(.015), 1e-12, "1.75 at 150 %");
        assertEquals(design.required(.007), design.required(-.007), 0, "even in the flow");
        for (double s : new double[]{.1, .5, .8}) {
            var slow = new Npsh(.01, s, 2);
            assertEquals(2 * s * s, slow.required(s * .01), 1e-12, "the affinity laws: NPSH3 at s Q_r is s² NPSH3_r at speed " + s);
            assertEquals(.4 * 2 * s * s, slow.required(0), 1e-12, "shutoff at speed " + s);
        }
        assertEquals(0, new Npsh(.01, 0, 2).required(0), 0, "a stopped pump needs none at no flow");
    }

    @Test void theBreakdownPressureIsKappaRhoGNpsh3() {
        var design = Npsh.rated(.01, 2950, 1);
        double npsh = design.ratedNpsh3();
        assertEquals(.85 * 997 * 9.80665 * npsh, design.breakdownPressure(.01, 997), 1e-9, "κ ρ g NPSH3 at the rated flow");
        assertEquals(14.0e3, design.breakdownPressure(.01, 997), 100, "about 14 kPa for the in-line default on water");
        assertEquals(.4 * design.breakdownPressure(.01, 997), design.breakdownPressure(0, 997), 1e-9, "0.4 of it at shutoff");
        assertThrows(IllegalArgumentException.class, () -> new Npsh(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Npsh(.01, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new Npsh(.01, 1, Double.NaN));
    }
}
