package com.wormzjl.icheme.science.machine;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The pump law of plan section 3.1 against the worked example of section 1.1: 100 m³/h at 80 m, 800 kg/m³, 72 %. */
class CentrifugalPumpTest {
    private static final CentrifugalPump PUMP = new CentrifugalPump(100 / 3600.0, 80, .72);

    @Test void curvePassesTheRatedPointAndRisesToShutoff() {
        double qr = PUMP.ratedFlow();
        assertEquals(80, PUMP.head(qr, 1), 1e-12);
        assertEquals(96.8, PUMP.head(0, 1), 1e-12, "shutoff at 121 %");
        assertEquals(62.6, PUMP.head(1.5 * qr, 1), 1e-12, "63 m at 150 % flow");
        assertTrue(PUMP.head(2.64 * qr, 1) > 0 && PUMP.head(2.65 * qr, 1) < 0, "runout at 2.65 Q_r");
    }

    @Test void slopeFallsByTheBoundAtEveryFlowAndSpeed() {
        double qr = PUMP.ratedFlow(), bound = -.01 * PUMP.ratedHead() / qr;
        for (double s = 0; s <= 1.2; s += .1) {
            for (double q = -1; q <= 3; q += .05) {
                double slope = PUMP.headSlope(q * qr, s), step = 1e-6 * qr;
                assertTrue(slope <= bound * (1 - 1e-12), "slope " + slope + " at s " + s + ", q " + q);
                // The curvature jumps at zero flow (q|q|): a difference across it is off by 0.15 of the step.
                double difference = (PUMP.head(q * qr + step, s) - PUMP.head(q * qr - step, s)) / (2 * step);
                assertEquals(slope, difference, 1e-6 * (Math.abs(slope) + PUMP.ratedHead() / qr), "slope against a difference at s " + s + ", q " + q);
            }
        }
    }

    /** The line of section 1.1: 30 m static head and friction through the rated point, {@code h = 0.375 + 0.625 q²}. */
    @Test void meetsALineWithStaticHeadAsTheAffinityLawsSay() {
        assertEquals(1, flowOnLine(1), 1e-9, "the rated point at full speed");
        assertEquals(.849, flowOnLine(.9), 5e-4, "85 % flow at 90 % speed");
        double low = .1, high = 1;
        for (int i = 0; i < 100; i++) {
            double mid = (low + high) / 2;
            if (flowOnLine(mid) < .7) low = mid;
            else high = mid;
        }
        assertEquals(.808, low, 5e-4, "81 % speed for 70 % flow");
    }

    @Test void powerIsFiniteAtShutoffAndBestAtTheRatedPoint() {
        double qr = PUMP.ratedFlow(), rated = PUMP.ratedShaftPower(800);
        assertEquals(24.2e3, rated, 50, "24.2 kW shaft power");
        assertEquals(rated, PUMP.shaftPower(qr, 1, 800), 1e-9 * rated);
        assertEquals(.35 * rated, PUMP.shaftPower(0, 1, 800), 1e-9 * rated, "8.5 kW at shutoff");
        assertEquals(.35 * rated, PUMP.shaftPower(-qr, 1, 800), 1e-9 * rated, "a reverse flow counts as none");
        assertEquals(.72, PUMP.efficiencyAt(qr, 1), 1e-12);
        assertEquals(.846, PUMP.efficiencyAt(.5 * qr, 1) / .72, 5e-4, "85 % of the best at half flow");
        assertEquals(.886, PUMP.efficiencyAt(1.5 * qr, 1) / .72, 5e-4, "89 % of the best at 150 %");
        double best = 0, at = 0;
        for (double q = .5; q <= 1.5; q += .001) {
            double eta = PUMP.efficiencyAt(q * qr, 1);
            if (eta > best) {
                best = eta;
                at = q;
            }
        }
        assertEquals(1, at, .02, "the best efficiency at the rated flow");
        assertEquals(0, PUMP.efficiencyAt(0, 1));
        assertEquals(.3 * .9 * qr, PUMP.minimumFlow(.9), 1e-15);
    }

    @Test void bestEfficiencyFollowsCorripio() {
        // The fluids library's example of Corripio_pump_efficiency: 461 gpm.
        assertEquals(.705888867095162, CentrifugalPump.bestEfficiency(461 / 15850.323), 1e-8);
        assertEquals(.7015, CentrifugalPump.bestEfficiency(100 / 3600.0), 5e-4, "100 m³/h");
        assertEquals(CentrifugalPump.bestEfficiency(50 / CentrifugalPump.GPM_PER_CUBIC_METRE_PER_SECOND), CentrifugalPump.bestEfficiency(1e-5), 0);
        assertEquals(.440, CentrifugalPump.bestEfficiency(1e-5), 5e-4, "held at 50 gpm");
        assertEquals(.860, CentrifugalPump.bestEfficiency(10), 5e-4, "held at 5000 gpm");
        assertEquals(CentrifugalPump.bestEfficiency(1), CentrifugalPump.rated(1, 50).efficiency(), 0);
    }

    @Test void refusesAnInvalidRating() {
        assertThrows(IllegalArgumentException.class, () -> new CentrifugalPump(0, 80, .7));
        assertThrows(IllegalArgumentException.class, () -> new CentrifugalPump(.01, 80, 1.2));
    }

    /** The flow share where the pump at {@code speed} meets the line {@code h = 0.375 + 0.625 q²}. */
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
