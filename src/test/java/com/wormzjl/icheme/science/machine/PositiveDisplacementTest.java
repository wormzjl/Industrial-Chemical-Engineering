package com.wormzjl.icheme.science.machine;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The corner law of plan section 3.3 as a rise (W2, form F2b): 10 l/s up to a 5 bar relief rise. */
class PositiveDisplacementTest {
    private static final double QD = .01, RELIEF = 5e5;
    private static final PositiveDisplacement MACHINE = new PositiveDisplacement(QD, RELIEF);

    @Test void deliversItsDisplacementUntilTheReliefLifts() {
        assertEquals(.98727, MACHINE.flow(.9 * RELIEF, 1) / QD, 1e-5, "98.7 % at 90 % of the relief rise");
        assertEquals(0, MACHINE.flow(RELIEF, 1), 0);
        assertEquals(0, MACHINE.flow(2 * RELIEF, 1), 0);
        assertEquals(1.0151, MACHINE.flow(0, 1) / QD, 1e-4, "1.5 % above the displacement at no rise, along the line past the knee");
        assertTrue(MACHINE.flow(-1e5, 1) > MACHINE.flow(0, 1), "a suction above the discharge blows through");
        assertEquals(.5 * MACHINE.flow(.9 * RELIEF, 1), MACHINE.flow(.9 * RELIEF, .5), 1e-17, "flow with speed");
    }

    @Test void theRiseIsTheFlowsInverseAndContinuousAtTheKnee() {
        for (double v = -.5; v < 1; v += .01)
            assertEquals(v * RELIEF, MACHINE.rise(MACHINE.flow(v * RELIEF, .8), .8), 1e-6 * RELIEF, "at " + v);
        assertEquals(RELIEF, MACHINE.rise(0, 1), 0);
        assertEquals(RELIEF, MACHINE.rise(-QD, 1), 0, "a reverse flow stands at the relief rise");
        assertEquals(.7719, MACHINE.rise(PositiveDisplacement.KNEE * QD, 1) / RELIEF, 1e-4, "the corner's rise at the knee");
        double knee = PositiveDisplacement.KNEE * QD, h = 1e-9 * QD;
        assertEquals(MACHINE.rise(knee - h, 1), MACHINE.rise(knee + h, 1), 1e-5 * RELIEF, "continuous at the knee");
        assertEquals(MACHINE.riseSlope(knee - h, 1), MACHINE.riseSlope(knee + h, 1), 1e-5 * Math.abs(MACHINE.riseSlope(knee, 1)), "with its slope");
        assertEquals(-47.88, MACHINE.riseSlope(knee + h, 1) * QD / RELIEF, .01, "the line's slope, relief rises per displacement");
        assertTrue(Double.isFinite(MACHINE.rise(10 * QD, 1)) && MACHINE.rise(10 * QD, 1) < 0, "finite and falling at every flow");
    }

    @Test void theRiseSlopeIsTheRisesDerivative() {
        // The balance's Newton on a corner mover reads d(rise)/dQ: the corner's −(Δp_relief/(Q_d s)) (u/(1 − u^16)^(1/16))^15
        // up to the knee, the line's past it.
        for (double u = .1; u < 1.5; u += .1) {
            if (Math.abs(u - PositiveDisplacement.KNEE) < 1e-3) continue;
            double flow = u * QD, h = 1e-6 * QD, difference = (MACHINE.rise(flow + h, 1) - MACHINE.rise(flow - h, 1)) / (2 * h);
            assertEquals(difference, MACHINE.riseSlope(flow, 1), 1e-6 * Math.abs(difference) + 1e-9 * RELIEF / QD, "at " + u);
        }
        assertEquals(0, MACHINE.riseSlope(0, 1), 0);
        assertEquals(0, MACHINE.riseSlope(-QD, 1), 0, "flat at the relief rise below no flow");
    }

    @Test void aStoppedMachineIsClosed() {
        assertThrows(IllegalArgumentException.class, () -> MACHINE.flow(0, 0));
        assertThrows(IllegalArgumentException.class, () -> MACHINE.rise(0, 0));
    }
}
