package com.wormzjl.icheme.science.machine;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The corner law of plan section 3.3: 10 l/s up to a 5 bar relief rise. */
class PositiveDisplacementTest {
    private static final double QD = .01, RELIEF = 5e5;
    private static final PositiveDisplacement MACHINE = new PositiveDisplacement(QD, RELIEF);

    @Test void deliversItsDisplacementUntilTheReliefLifts() {
        assertEquals(.98727, MACHINE.flow(.9 * RELIEF, 1) / QD, 1e-5, "98.7 % at 90 % of the relief rise");
        assertEquals(0, MACHINE.flow(RELIEF, 1), 0);
        assertEquals(0, MACHINE.flow(2 * RELIEF, 1), 0);
        assertEquals(QD, MACHINE.flow(0, 1), 0);
        assertEquals(QD, MACHINE.flow(-1e5, 1), 0, "a negative rise passes the displacement");
        assertEquals(.5 * MACHINE.flow(.9 * RELIEF, 1), MACHINE.flow(.9 * RELIEF, .5), 1e-17, "flow with speed");
    }

    @Test void residualIsZeroOnTheCornerAndADistanceInside() {
        for (double v = -.5; v < 1; v += .01) {
            double rise = v * RELIEF;
            assertEquals(0, MACHINE.residual(MACHINE.flow(rise, .8), rise, .8), 1e-14, "on the corner at " + v);
        }
        assertEquals(Math.sqrt(Math.sqrt(Math.sqrt(Math.sqrt(2)))) / 2 - 1, MACHINE.residual(.5 * QD, .5 * RELIEF, 1), 1e-15);
        assertEquals(-.478, MACHINE.residual(.5 * QD, .5 * RELIEF, 1), 1e-3);
        assertEquals(2 * (MACHINE.residual(.3 * QD, .2 * RELIEF, 1) + 1), MACHINE.residual(.6 * QD, .4 * RELIEF, 1) + 1, 1e-14, "homogeneous");
        assertEquals(-1, MACHINE.residual(-QD, -RELIEF, 1), 0, "both shares count from zero");
    }

    @Test void slopesMatchDifferences() {
        double[][] points = {{.5, .5}, {.99, .3}, {.2, .98}, {1.2, .1}, {.7, -.2}, {-.3, .9}};
        for (double[] p : points) {
            double flow = p[0] * QD, rise = p[1] * RELIEF, hq = 1e-7 * QD, hp = 1e-7 * RELIEF;
            double dq = (MACHINE.residual(flow + hq, rise, 1) - MACHINE.residual(flow - hq, rise, 1)) / (2 * hq);
            double dp = (MACHINE.residual(flow, rise + hp, 1) - MACHINE.residual(flow, rise - hp, 1)) / (2 * hp);
            assertEquals(dq, MACHINE.residualFlowSlope(flow, rise, 1), 1e-6 / QD, "flow slope at " + p[0] + ", " + p[1]);
            assertEquals(dp, MACHINE.residualRiseSlope(flow, rise, 1), 1e-6 / RELIEF, "rise slope at " + p[0] + ", " + p[1]);
            assertTrue(MACHINE.residualFlowSlope(flow, rise, 1) * QD <= 1 + 1e-12 && MACHINE.residualRiseSlope(flow, rise, 1) * RELIEF <= 1 + 1e-12);
        }
    }

    @Test void riseTurnsTheFlowRound() {
        // Only where both are well conditioned: below a third of the relief rise, 1 − v^16 rounds to one.
        for (double v = .3; v < .91; v += .05)
            assertEquals(v * RELIEF, MACHINE.rise(MACHINE.flow(v * RELIEF, 1), 1), 1e-6 * RELIEF, "at " + v);
        assertEquals(RELIEF, MACHINE.rise(0, 1), 0);
        assertEquals(RELIEF, MACHINE.rise(-QD, 1), 0, "a reverse flow stands at the relief rise");
        assertEquals(0, MACHINE.rise(QD, 1), 0);
        assertEquals(Double.NEGATIVE_INFINITY, MACHINE.rise(1.01 * QD, 1));
    }

    @Test void theRiseSlopeIsTheRisesDerivative() {
        // W2: the balance's Newton on a corner mover reads d(rise)/dQ = −(Δp_relief/(Q_d s)) (u/(1 − u^16)^(1/16))^15.
        for (double u = .1; u < .95; u += .1) {
            double flow = u * QD, h = 1e-6 * QD, difference = (MACHINE.rise(flow + h, 1) - MACHINE.rise(flow - h, 1)) / (2 * h);
            assertEquals(difference, MACHINE.riseSlope(flow, 1), 1e-6 * Math.abs(difference) + 1e-9 * RELIEF / QD, "at " + u);
        }
        assertEquals(0, MACHINE.riseSlope(0, 1), 0);
        assertEquals(0, MACHINE.riseSlope(-QD, 1), 0, "flat at the relief rise below no flow");
        assertEquals(Double.NEGATIVE_INFINITY, MACHINE.riseSlope(QD, 1));
    }

    @Test void aStoppedMachineIsClosed() {
        assertThrows(IllegalArgumentException.class, () -> MACHINE.flow(0, 0));
        assertThrows(IllegalArgumentException.class, () -> MACHINE.residual(0, 0, 0));
    }
}
