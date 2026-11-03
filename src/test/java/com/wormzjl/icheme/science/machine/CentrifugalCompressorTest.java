package com.wormzjl.icheme.science.machine;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.math.DeterministicMath;
import org.junit.jupiter.api.Test;

/** The compressor law of plan section 3.2 against the worked example of section 1.2: natural gas (M = 18, k = 1.27) from
 * 1 bar and 300 K to 3 bar, 3.5 kg/s, η_p = 0.78. */
class CentrifugalCompressorTest {
    private static final double R = 8.31446261815324, M = .018, K = 1.27, PRESSURE = 1e5, TEMPERATURE = 300;
    private static final double DENSITY = PRESSURE * M / (R * TEMPERATURE);
    private static final CentrifugalCompressor MACHINE = new CentrifugalCompressor(4.85, 177.5e3, .78);

    @Test void speedLinePassesTheRatedPointAndRunsOnSmoothlyThroughSurge() {
        double qr = MACHINE.ratedFlow(), hr = MACHINE.ratedHead();
        assertEquals(hr, MACHINE.head(qr, 1), 1e-9 * hr);
        assertEquals(1.1450625 * hr, MACHINE.head(0, 1), 1e-9 * hr, "shutoff on the surge extension");
        for (double s = .1; s <= 1.2; s += .1) {
            double surge = MACHINE.surgeFlow(s), step = 1e-9 * qr;
            assertEquals(MACHINE.head(surge - step, s), MACHINE.head(surge + step, s), 1e-6 * hr, "head through surge at " + s);
            assertEquals(MACHINE.headSlope(surge - step, s), MACHINE.headSlope(surge + step, s), 1e-6 * hr / qr, "slope through surge at " + s);
            for (double q = -.5; q <= 2; q += .05) {
                double slope = MACHINE.headSlope(q * qr, s), h = 1e-6 * qr;
                assertTrue(slope <= -.0895 * s * hr / qr * (1 - 1e-9), "falls at s " + s + ", q " + q);
                // The curvature jumps at the surge line: a difference across it is off by 0.89 of the step.
                double difference = (MACHINE.head(q * qr + h, s) - MACHINE.head(q * qr - h, s)) / (2 * h);
                assertEquals(slope, difference, 1e-6 * (Math.abs(slope) + hr / qr), "slope against a difference at s " + s + ", q " + q);
            }
        }
        assertEquals(-.889 * hr, MACHINE.head(qr, 0), 1e-9 * hr, "stopped: a quadratic loss");
    }

    @Test void ratioAndPowerOfThePlanExample() {
        double sigma = CentrifugalCompressor.sigma(K, .78), head = CentrifugalCompressor.polytropicHead(3, PRESSURE, DENSITY, sigma);
        assertEquals(177.5e3, head, 500, "177.5 kJ/kg");
        assertEquals(3, CentrifugalCompressor.ratio(head, PRESSURE, DENSITY, sigma), 1e-12);
        assertEquals(2e5, CentrifugalCompressor.rise(head, PRESSURE, DENSITY, sigma), 1e-6);
        assertEquals(796e3, MACHINE.gasPower(3.5, head), 1e3, "796 kW");
        // The gas power heats an ideal gas exactly to the polytropic discharge temperature T_s r^(1/σ): 405 K.
        double cp = K / (K - 1) * R / M, discharge = TEMPERATURE * DeterministicMath.pow(3, 1 / sigma);
        assertEquals(discharge - TEMPERATURE, MACHINE.gasPower(1, head) / cp, 1e-9 * discharge);
        assertEquals(405, discharge, .5);
        // Nitrogen at ratio 3 from 298 K and η_p 0.8 (plan section 4.4): +143 K, against +93 K booked isothermally today.
        assertEquals(143, 298 * (DeterministicMath.pow(3, 1 / CentrifugalCompressor.sigma(1.4, .8)) - 1), .5);
    }

    @Test void riseRunsOnThroughZeroHead() {
        double sigma = CentrifugalCompressor.sigma(K, .78);
        assertEquals(0, CentrifugalCompressor.rise(0, PRESSURE, DENSITY, sigma), 0);
        assertEquals(DENSITY, CentrifugalCompressor.riseSlope(0, PRESSURE, DENSITY, sigma), 0);
        assertEquals(DENSITY, CentrifugalCompressor.riseSlope(1e-9, PRESSURE, DENSITY, sigma), 1e-12);
        assertEquals(-DENSITY * 5e4, CentrifugalCompressor.rise(-5e4, PRESSURE, DENSITY, sigma), 1e-9);
        for (double head : new double[]{-1e5, -1e3, 1e3, 5e4, 2e5, 4e5}) {
            double step = 1e-3, difference = (CentrifugalCompressor.rise(head + step, PRESSURE, DENSITY, sigma)
                    - CentrifugalCompressor.rise(head - step, PRESSURE, DENSITY, sigma)) / (2 * step);
            assertEquals(CentrifugalCompressor.riseSlope(head, PRESSURE, DENSITY, sigma), difference, 1e-6 * difference, "at " + head);
            double ratio = CentrifugalCompressor.ratio(head, PRESSURE, DENSITY, sigma);
            assertEquals(head, CentrifugalCompressor.polytropicHead(ratio, PRESSURE, DENSITY, sigma), 1e-9 * Math.abs(head), "round trip at " + head);
        }
    }

    @Test void recycleCarriesTheMachineFlowAtSurge() {
        double qr = MACHINE.ratedFlow(), head = MACHINE.head(.5 * qr, 1);
        assertEquals(.75 * qr, MACHINE.machineFlow(.5 * qr, 1), 1e-15);
        assertEquals(qr, MACHINE.machineFlow(qr, 1), 0);
        double recycled = MACHINE.gasPower(DENSITY * .75 * qr, head);
        assertEquals(recycled, MACHINE.shaftPower(.5 * qr, 1, DENSITY, head), 1e-12 * recycled);
        assertEquals(0, MACHINE.gasPower(1, -1e3), "past choke the gas takes no work");
        assertEquals(0, MACHINE.gasPower(-1, 1e3));
    }

    @Test void refusesAnInvalidRating() {
        assertThrows(IllegalArgumentException.class, () -> new CentrifugalCompressor(1, 0, .78));
        assertThrows(IllegalArgumentException.class, () -> CentrifugalCompressor.sigma(1, .78));
    }
}
