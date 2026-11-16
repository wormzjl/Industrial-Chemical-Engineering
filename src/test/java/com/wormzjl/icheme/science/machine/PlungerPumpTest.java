package com.wormzjl.icheme.science.machine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The plunger pump's law (batch 2026-11-15-supercritical-fluids, stage S8, PLUNGER_PUMP_PLAN.md section 3). */
class PlungerPumpTest {
    private static final PlungerPump PP30 = PlungerPump.of(PlungerPump.model(3));
    private static final double Q = 30 / 3600.0, RELIEF = PlungerPump.RELIEF_RISE;

    /** The flow the law passes at {@code rise}, by the row it is solved on. */
    private static double delivered(PlungerPump law, double rise, double speed) {
        return law.row(speed).flow(rise, 1);
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
        assertEquals((1 - .5 * PositiveDisplacement.SLIP) * Q, delivered(PP30, .5 * RELIEF, 1), 5e-5 * Q);
        assertEquals((1 - .4 * PositiveDisplacement.SLIP) * Q, delivered(PP30, 10e6, 1), 5e-5 * Q);
    }

    @Test void itsReliefValvePassesTheWholeDeliveryBack() {
        assertEquals(RELIEF, PP30.rise(0, 1, 470, 8e6, 1.4), 1e-9 * RELIEF);
        assertEquals(0, delivered(PP30, RELIEF, 1), 1e-9 * Q);
        assertTrue(delivered(PP30, .95 * RELIEF, 1) < .6 * Q, "the relief line over the last tenth");
    }

    @Test void itsDeliveryDoesNotReadTheDensity() {
        double flow = .99 * Q, rise = PP30.rise(flow, 1, 1000, 1e5, 1.4);
        for (double density : new double[]{280, 470, 1000}) {
            assertEquals(rise, PP30.rise(flow, 1, density, 8e6, 1.3), 0, "rise at " + density);
            assertEquals(PP30.riseSlope(flow, 1, 1000, 1e5, 1.4), PP30.riseSlope(flow, 1, density, 8e6, 1.3), 0, "slope at " + density);
            assertEquals(0, PP30.residual(flow, rise, 1, density, 8e6, 1.3), 1e-6 * RELIEF, "residual at " + density);
        }
    }

    @Test void theRowIsZeroOnTheLawInTheFlowForm() {
        for (double rise : new double[]{0, 5e6, 20e6, 24e6}) {
            double flow = delivered(PP30, rise, .8);
            assertEquals(0, PP30.residual(flow, rise, .8, 470, 8e6, 1.3), 1e-6 * RELIEF, "at " + rise);
            assertEquals(RELIEF / (.8 * Q), PP30.residual(flow + 1e-6, rise, .8, 470, 8e6, 1.3) / 1e-6, 1e-3 * RELIEF / Q, "flow derivative");
        }
    }

    @Test void theShaftTakesTheWholeDisplacementAgainstTheRise() {
        assertEquals(Q * RELIEF / .88, PP30.shaftPower(0, 1, 470, 8e6, 1.3), 1e-9 * Q * RELIEF);
        assertTrue(PP30.shaftPower(0, 1, 470, 8e6, 1.3) > 0, "finite and above zero at no flow");
        double flow = delivered(PP30, 10e6, 1);
        assertEquals(Q * 10e6 / .88, PP30.shaftPower(flow, 1, 470, 8e6, 1.3), 1e-6 * Q * 10e6);
        assertEquals(0, PP30.shaftPower(flow, 0, 470, 8e6, 1.3));
        assertTrue(Double.isFinite(PP30.rise(0, 0, 470, 8e6, 1.3)), "a standing train has a finite row");
    }

    @Test void theFluidTakesFlowTimesRise() {
        double massFlow = 470 * .99 * Q;
        assertEquals(.99 * Q * 12e6, PP30.fluidPower(massFlow, 12e6, 470, 8e6, 1.3), 1e-9 * Q * 12e6);
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
