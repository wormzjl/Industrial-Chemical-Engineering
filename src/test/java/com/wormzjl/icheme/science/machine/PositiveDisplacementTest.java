package com.wormzjl.icheme.science.machine;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The law of plan section 3.3 (W2, form F2e): 10 l/s up to a 5 bar relief rise, a slip line and a relief line joined by a
 * rounded corner. */
class PositiveDisplacementTest {
    private static final double QD = .01, RELIEF = 5e5;
    private static final PositiveDisplacement MACHINE = new PositiveDisplacement(QD, RELIEF);

    @Test void deliversItsDisplacementLessItsSlipUntilTheReliefLifts() {
        assertEquals(.9999888890, MACHINE.flow(0, 1) / QD, 1e-10, "the displacement at no rise, less the corner's tail");
        assertEquals(1.0039947358, MACHINE.flow(-RELIEF, 1) / QD, 1e-10, "0.4 % more a relief rise below no rise: the slip runs forward");
        assertEquals(.9979750133, MACHINE.flow(.5 * RELIEF, 1) / QD, 1e-10, "99.80 % at half the relief rise");
        assertEquals(.9880804789, MACHINE.flow(.9 * RELIEF, 1) / QD, 1e-10, "98.8 % at 90 %, where the lines cross");
        assertEquals(.4998989110, MACHINE.flow(.95 * RELIEF, 1) / QD, 1e-10, "50 % at 95 %, on the relief line");
        assertEquals(.0999888008, MACHINE.flow(.99 * RELIEF, 1) / QD, 1e-10, "10 % at 99 %");
        assertEquals(0, MACHINE.flow(RELIEF, 1) / QD, 1e-15, "nothing at the relief rise");
        assertEquals(-.0999908434, MACHINE.flow(1.01 * RELIEF, 1) / QD, 1e-10, "a reverse flow past it, on the relief line");
        for (double v = 0; v < 1.5; v += .01)
            assertTrue(MACHINE.flow(v * RELIEF, 1) < QD, "below the displacement at every rise, at " + v);
        assertEquals(.5 * MACHINE.flow(.9 * RELIEF, 1), MACHINE.flow(.9 * RELIEF, .5), 1e-17, "flow with speed");
    }

    @Test void theRiseIsTheFlowsInverse() {
        for (double u = -1; u <= 1.004; u += .01) {
            double flow = u * .8 * QD;
            assertEquals(flow, MACHINE.flow(MACHINE.rise(flow, .8), .8), 1e-12 * QD, "at " + u + " of the displacement at 80 % speed");
        }
        assertEquals(1, MACHINE.rise(0, 1) / RELIEF, 1e-15, "the relief rise at no flow: the rise limit is the law's rise there");
        assertEquals(1.1000050291, MACHINE.rise(-QD, 1) / RELIEF, 1e-10, "above it for a reverse flow");
        assertEquals(-.0027692262, MACHINE.rise(QD, 1) / RELIEF, 1e-10, "the displacement a little below no rise");
        assertTrue(Double.isFinite(MACHINE.rise(10 * QD, 1)), "finite at every flow");
    }

    @Test void theRiseSlopeIsTheRisesDerivative() {
        // The balance's Newton on a set-flow mover reads d(rise)/dQ: −(Δp_relief/(Q_d s)) (a + b)/(SLIP b + m a), a and b
        // the flow's distances below the two lines.
        for (double u = -.5; u < 1.004; u += .05) {
            double flow = u * QD, h = 1e-7 * QD, difference = (MACHINE.rise(flow + h, 1) - MACHINE.rise(flow - h, 1)) / (2 * h);
            assertEquals(difference, MACHINE.riseSlope(flow, 1), 1e-5 * Math.abs(difference), "at " + u);
        }
        double slip = RELIEF / QD / PositiveDisplacement.SLIP, relief = RELIEF / QD / PositiveDisplacement.RELIEF_SLOPE;
        assertEquals(-slip, MACHINE.riseSlope(.999 * QD, 1), .01 * slip, "the slip line's slope");
        assertEquals(-relief, MACHINE.riseSlope(.5 * QD, 1), .01 * relief, "the relief line's");
    }

    @Test void theRowHasAFlowDerivativeEverywhereAndABoundedRiseDerivative() {
        double h = 1e-6;
        for (double v = -.5; v < 1.5; v += .05) {
            double rise = v * RELIEF, flow = MACHINE.flow(rise, 1);
            assertEquals(0, MACHINE.residual(flow, rise, 1), 1e-12 * RELIEF, "on the law at " + v);
            assertEquals(RELIEF / QD, (MACHINE.residual(flow + h * QD, rise, 1) - MACHINE.residual(flow, rise, 1)) / (h * QD),
                    1e-6 * RELIEF / QD, "flow derivative at " + v);
            double slope = (MACHINE.residual(flow, rise + h * RELIEF, 1) - MACHINE.residual(flow, rise - h * RELIEF, 1)) / (2 * h * RELIEF);
            assertTrue(slope > .999 * PositiveDisplacement.SLIP && slope < 1.001 * PositiveDisplacement.RELIEF_SLOPE, "rise derivative " + slope + " at " + v);
        }
    }

    @Test void aStoppedMachineIsClosed() {
        assertThrows(IllegalArgumentException.class, () -> MACHINE.flow(0, 0));
        assertThrows(IllegalArgumentException.class, () -> MACHINE.rise(0, 0));
        assertThrows(IllegalArgumentException.class, () -> MACHINE.residual(0, 0, 0));
    }
}
