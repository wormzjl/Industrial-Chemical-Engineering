package com.wormzjl.icheme.science.fluid.network;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A gas turbine's heat-recovery steam coil, designed to its pinch (batch 2026-11-10-prime-movers, PRIME_MOVERS_PLAN.md section 10). */
class ExhaustCoilTest {
    /** Water at about 40 bar: liquid at 4.2 kJ/kgK from 105 °C, boiling at 250 °C (1.71 MJ/kg), steam at 2.6 kJ/kgK to 900 K. */
    private static final HeatCurve WATER = HeatCurve.of(List.of(new double[]{0, 378.15, 2000}, new double[]{6.09e5, 523.15, 2000}, new double[]{2.319e6, 523.15, 5000},
            new double[]{2.319e6 + 2600 * (900 - 523.15), 900, 100}));
    private static final double GAS = 800, FLUE = 1e5;

    /**
     * A strong stream: the pinch at the bubble point decides. The gas above 533 K, 26.7 MW, raises the steam; 20 kg/s takes
     * 0.609 MJ/kg to the bubble and 5 kJ/kg per kelvin of gas over the pinch, 38.9 MW, and the stack leaves at 411 K.
     */
    @Test void thePinchAtTheBubbleDecides() {
        var coil = new ExhaustCoil(GAS, FLUE);
        double heat = coil.heat(WATER, 0, 20);
        assertEquals(20 * (6.09e5 + FLUE / 20 * (GAS - 523.15 - ExhaustCoil.PINCH)), heat, 1);
        assertEquals(38.865e6, heat, 1e3);
        assertEquals(411.35, ExhaustCoil.stack(GAS, FLUE, heat), .01);
    }

    /** A weak stream leaves at its approach, 25 K under the gas; the pinch does not bind. */
    @Test void aWeakStreamLeavesAtTheApproach() {
        var coil = new ExhaustCoil(GAS, FLUE);
        double heat = coil.heat(WATER, 0, 5);
        assertEquals(GAS - ExhaustCoil.APPROACH, WATER.temperatureAt(heat / 5), 1e-6);
        assertTrue(heat < FLUE * (GAS - 378.15), "less than the gas holds over the water's inlet");
    }

    @Test void itMovesNothingWithoutGasOrFlow() {
        assertEquals(0, ExhaustCoil.COLD.heat(WATER, 0, 10), "the turbine stopped");
        assertEquals(0, new ExhaustCoil(GAS, FLUE).heat(WATER, 0, 0), "no flow");
        assertEquals(new ExhaustCoil(GAS, FLUE).heat(WATER, 0, 10), new ExhaustCoil(GAS, FLUE).heat(WATER, 0, -10), "either direction");
        assertEquals(0, new ExhaustCoil(500, FLUE).heat(WATER, WATER.enthalpyAt(495), 10), "water within the pinch of the gas takes none");
        assertEquals(ExhaustCoil.COLDEST, ExhaustCoil.stack(GAS, 0, 0), "no exhaust: the air");
        assertEquals(GAS, ExhaustCoil.stack(GAS, FLUE, 0), "no coil heat: the turbine's exhaust");
        assertEquals(new ExhaustCoil(GAS, FLUE).shape(), ExhaustCoil.COLD.shape(), "the gas is a slice input, not a shape");
        assertThrows(IllegalArgumentException.class, () -> new ExhaustCoil(-1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ExhaustCoil(1, Double.NaN));
    }

    /** The coil's heat rises with the flow and never takes more than the gas holds over the water's inlet less the pinch. */
    @Test void itIsBoundedByTheGas() {
        var coil = new ExhaustCoil(GAS, FLUE);
        double last = 0;
        for (double flow : new double[]{.1, 1, 5, 11, 12, 20, 100, 1e4}) {
            double heat = coil.heat(WATER, 0, flow);
            assertTrue(heat >= last - 1e-6, flow + " kg/s");
            assertTrue(heat <= FLUE * (GAS - 378.15 - ExhaustCoil.PINCH) + 1e-6, flow + " kg/s");
            last = heat;
        }
    }
}
