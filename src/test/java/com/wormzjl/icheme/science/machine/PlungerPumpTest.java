package com.wormzjl.icheme.science.machine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The plunger pump's law (batch 2026-11-15-supercritical-fluids, stage S8, PLUNGER_PUMP_PLAN.md section 3; its clearance
 * loss and isentropic work, batch 2026-11-16-supercritical-followups, P1, FOLLOWUPS_PLAN.md section 3). The S8 cases read a
 * liquid suction, water at 1 MPa ({@code n} 2,200), where the clearance costs at most 1.5e-4 of the displacement. */
class PlungerPumpTest {
    private static final PlungerPump PP30 = PlungerPump.of(PlungerPump.model(3));
    private static final double Q = 30 / 3600.0, RELIEF = PlungerPump.RELIEF_RISE;
    /** A liquid suction: water at 300 K and 1 MPa. */
    private static final double P = 1e6, WATER = 2200;

    /** The flow the law passes at {@code rise} on the liquid suction. */
    private static double delivered(PlungerPump law, double rise, double speed) {
        return law.flow(rise, speed, P, WATER);
    }

    @Test void theCatalogueIsFourModelsAtThreeHundredRpm() {
        assertEquals("PP-3", PlungerPump.model(0).name());
        assertEquals("PP-100", PlungerPump.model(9).name());
        assertEquals(Q, PP30.displacement(), 1e-15);
        assertEquals(300, PP30.ratedSpeed());
        assertEquals(25e6, PP30.reliefRise());
        assertEquals(789.1e3, PlungerPump.of(PlungerPump.model(4)).maximumShaftPower(), .1e3);
    }

    @Test void itDeliversItsDisplacementAtNoRise() {
        assertEquals(Q, delivered(PP30, 0, 1), 1e-4 * Q);
        assertEquals(.5 * Q, delivered(PP30, 0, .5), 1e-4 * Q);
    }

    @Test void itSlipsFourTenthsOfAPercentPerReliefRise() {
        assertEquals((1 - .5 * PositiveDisplacement.SLIP) * Q, delivered(PP30, .5 * RELIEF, 1), 2e-4 * Q);
        assertEquals((1 - .4 * PositiveDisplacement.SLIP) * Q, delivered(PP30, 10e6, 1), 2e-4 * Q);
    }

    @Test void itsReliefValvePassesTheWholeDeliveryBack() {
        assertEquals(RELIEF, PP30.rise(0, 1, 1000, P, WATER), 1e-4 * RELIEF, "a liquid's no-flow rise moves under 1e-4");
        assertEquals(RELIEF, PP30.row(1).rise(0, 1), 1e-9 * RELIEF, "the law with no clearance stops at the relief rise");
        assertEquals(0, delivered(PP30, PP30.rise(0, 1, 1000, P, WATER), 1), 1e-9 * Q);
        assertTrue(delivered(PP30, .95 * RELIEF, 1) < .6 * Q, "the relief line over the last tenth");
    }

    /** On a gas the clearance stops the delivery where {@code c (r − 1)} reaches the share: nitrogen from 0.1 MPa near
     * {@code p ((1 + share/c)^n − 1)}, 2.77 MPa (FOLLOWUPS_PLAN.md section 3, point 7). */
    @Test void onAGasTheClearanceStopsTheDeliveryBelowTheReliefRise() {
        double stop = PP30.rise(0, 1, 1.2, 1e5, 1.4);
        double share = 1 - PositiveDisplacement.SLIP * stop / RELIEF;
        assertEquals(1e5 * (Math.pow(1 + share / PlungerPump.CLEARANCE, 1.4) - 1), stop, 1e-4 * stop, "the slip line, less the corner's rounding");
        assertEquals(2.77e6, stop, .01e6);
        assertEquals(0, PP30.flow(stop, 1, 1e5, 1.4), 1e-12 * Q);
    }

    @Test void itsDeliveryDoesNotReadTheDensity() {
        double flow = .99 * Q, rise = PP30.rise(flow, 1, 1000, 8e6, 1.3);
        for (double density : new double[]{280, 470, 1000}) {
            assertEquals(rise, PP30.rise(flow, 1, density, 8e6, 1.3), 0, "rise at " + density);
            assertEquals(PP30.riseSlope(flow, 1, 1000, 8e6, 1.3), PP30.riseSlope(flow, 1, density, 8e6, 1.3), 0, "slope at " + density);
            assertEquals(0, PP30.residual(flow, rise, 1, density, 8e6, 1.3), 1e-6 * RELIEF, "residual at " + density);
        }
    }

    @Test void theRowIsZeroOnTheLawInTheFlowForm() {
        for (double rise : new double[]{0, 5e6, 20e6, 24e6}) {
            double flow = PP30.flow(rise, .8, 8e6, 1.3);
            assertEquals(0, PP30.residual(flow, rise, .8, 470, 8e6, 1.3), 1e-6 * RELIEF, "at " + rise);
            assertEquals(RELIEF / (.8 * Q), PP30.residual(flow + 1e-6, rise, .8, 470, 8e6, 1.3) / 1e-6, 1e-3 * RELIEF / Q, "flow derivative");
        }
    }

    /** {@code rise(Q(ΔP)) = ΔP} on 3 suction pressures × 3 exponents × 20 rises, below no rise and past the relief rise. */
    @Test void theRiseIsTheInverseOfTheFlow() {
        for (double p : new double[]{1e5, 8e6, 15e6})
            for (double n : new double[]{1.3, 3, 2200})
                for (int i = 0; i < 20; i++) {
                    double rise = -.3 * p + i / 19.0 * (1.02 * RELIEF + .3 * p), flow = PP30.flow(rise, 1, p, n);
                    assertTrue(Double.isFinite(flow), "finite at " + rise);
                    assertEquals(rise, PP30.rise(flow, 1, 470, p, n), 1e-10 * Math.max(Math.abs(rise), p), "p " + p + ", n " + n + ", rise " + rise);
                }
    }

    @Test void theSlopeIsTheRisesDerivative() {
        for (double p : new double[]{1e5, 8e6})
            for (double n : new double[]{1.3, 2200})
                for (double share : new double[]{.2, .6, .9, .99}) {
                    double flow = share * PP30.flow(0, 1, p, n), h = 1e-6 * Q;
                    double difference = (PP30.rise(flow + h, 1, 470, p, n) - PP30.rise(flow - h, 1, 470, p, n)) / (2 * h);
                    double slope = PP30.riseSlope(flow, 1, 470, p, n);
                    assertTrue(slope < 0, "falling at " + share);
                    assertEquals(difference, slope, 1e-5 * Math.abs(slope), "p " + p + ", n " + n + ", share " + share);
                }
    }

    /** {@code η_v = 1 − c (r − 1)}, {@code r = ((p + ΔP)/p)^(1/n)}: none at no rise, finite below no rise and below no
     * pressure. */
    @Test void theClearanceLossIsTheIsentropesDensityRatio() {
        assertEquals(1, PlungerPump.volumetricEfficiency(0, 1e5, 1.4), 0);
        assertEquals(1 - .1 * (Math.pow(4, 1 / 1.4) - 1), PlungerPump.volumetricEfficiency(3e5, 1e5, 1.4), 1e-15);
        assertTrue(PlungerPump.volumetricEfficiency(-.5e5, 1e5, 1.4) > 1, "a discharge below the suction gains");
        assertTrue(Double.isFinite(PlungerPump.volumetricEfficiency(-1e6, 1e5, 1.4)), "finite past no discharge pressure");
        assertEquals(PlungerPump.volumetricEfficiency(3e5, 1e5, PlungerPump.LEAST_EXPONENT), PlungerPump.volumetricEfficiency(3e5, 1e5, .9), 0, "the floor");
        assertEquals(PlungerPump.volumetricEfficiency(3e5, 1e5, PlungerPump.NO_EXPONENT), PlungerPump.volumetricEfficiency(3e5, 1e5, Double.NaN), 0);
    }

    /** The isentropic work per suction volume: the gas law at {@code n} 1.4, {@code ΔP} itself as {@code n} grows. */
    @Test void theWorkIsTheIsentropicStep() {
        assertEquals(1e5 * 3.5 * (Math.pow(4, .4 / 1.4) - 1), PlungerPump.isentropicWork(3e5, 1e5, 1.4), 1e-9 * 3e5);
        assertEquals(12e6, PlungerPump.isentropicWork(12e6, 1e6, Double.POSITIVE_INFINITY), 1e-12 * 12e6);
        assertEquals(12e6, PlungerPump.isentropicWork(12e6, 1e6, 1e15), 1e-12 * 12e6);
        assertEquals(12e6, PlungerPump.isentropicWork(12e6, 1e6, WATER), 2e-3 * 12e6, "water: within its compression");
        assertTrue(PlungerPump.isentropicWork(12e6, 1e6, WATER) < 12e6);
        assertEquals(10, PlungerPump.isentropicWork(10, 1e7, 1.3), 1e-6 * 10, "a small rise takes its own work");
    }

    @Test void theShaftTakesTheDrawnVolumeAgainstTheWork() {
        assertEquals(Q * RELIEF / .88, PP30.shaftPower(0, 1, 1000, P, WATER), 2e-3 * Q * RELIEF);
        assertTrue(PP30.shaftPower(0, 1, 470, 8e6, 1.3) > 0, "finite and above zero at no flow");
        double flow = delivered(PP30, 10e6, 1);
        assertEquals(Q * 10e6 / .88, PP30.shaftPower(flow, 1, 1000, P, WATER), 2e-3 * Q * 10e6);
        double stop = PP30.rise(0, 1, 1.2, 1e5, 1.4);
        assertEquals(Q * PlungerPump.volumetricEfficiency(stop, 1e5, 1.4) * PlungerPump.isentropicWork(stop, 1e5, 1.4) / .88, PP30.shaftPower(0, 1, 1.2, 1e5, 1.4), 1e-9 * Q * stop);
        assertEquals(0, PP30.shaftPower(flow, 0, 470, 8e6, 1.3));
        assertTrue(Double.isFinite(PP30.rise(0, 0, 470, 8e6, 1.3)), "a standing train has a finite row");
        assertEquals(Q * RELIEF / .88, PP30.maximumShaftPower(), 1e-9 * Q * RELIEF, "the motor's duty is the liquid bound");
    }

    @Test void theFluidTakesFlowTimesTheIsentropicWork() {
        double massFlow = 470 * .99 * Q;
        assertEquals(.99 * Q * PlungerPump.isentropicWork(12e6, 8e6, 1.3), PP30.fluidPower(massFlow, 12e6, 470, 8e6, 1.3), 1e-9 * Q * 12e6);
        assertEquals(.99 * Q * 12e6, PP30.fluidPower(massFlow, 12e6, 470, P, Double.POSITIVE_INFINITY), 1e-9 * Q * 12e6);
        assertEquals(0, PP30.fluidPower(-massFlow, 12e6, 470, 8e6, 1.3));
        assertEquals(0, PP30.fluidPower(massFlow, -1, 470, 8e6, 1.3));
        assertEquals(0, PP30.fluidPower(massFlow, 12e6, 0, 8e6, 1.3));
    }

    @Test void itIsALiquidMoverWithNoHead() {
        assertTrue(PP30.liquid());
        assertEquals(0, PP30.ratedHead());
        assertEquals(Q, PP30.ratedFlow());
        assertEquals(.88, PP30.efficiency());
        assertThrows(IllegalArgumentException.class, () -> new PlungerPump(0, RELIEF, 300));
        assertThrows(IllegalArgumentException.class, () -> new PlungerPump(Q, Double.NaN, 300));
        assertThrows(IllegalArgumentException.class, () -> new PlungerPump(Q, RELIEF, 0));
    }
}
