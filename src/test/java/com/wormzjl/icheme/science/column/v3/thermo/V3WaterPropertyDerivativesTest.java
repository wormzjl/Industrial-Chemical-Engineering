package com.wormzjl.icheme.science.column.v3.thermo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Holds each water function's derivative against a difference of the value it belongs to.
 *
 * <p>A step of a milli-kelvin is small enough that the truncation of a central difference — which grows as the
 * square of the step — stays far below the tolerance, and large enough that the cancellation between two
 * nearly equal values stays near the {@code 1e-13} level of relative rounding.</p>
 *
 * <p>Observed maxima before E30 (batch 2026-11-04-open-thermo-data), relative to the value of the derivative itself:
 * {@code d ln(P_sat)/dT} 1.2e-10 and vapour heat capacity 3.8e-10. Since E30 the steam is the network's virial gas and
 * the liquid the network's Region 1 water; their derivatives are held the same way.</p>
 */
class V3WaterPropertyDerivativesTest {
    private static final double STEP_KELVIN = 1.0e-3;
    private static final double TOLERANCE = 1.0e-7;

    @Test
    void saturationPressureLogarithmicSlopeMatchesACentralDifference() {
        for (double temperature = 280.0; temperature <= 640.0; temperature += 5.0) {
            double difference = (Math.log(V3WaterProperties.saturationPressurePascal(temperature + STEP_KELVIN))
                    - Math.log(V3WaterProperties.saturationPressurePascal(temperature - STEP_KELVIN)))
                    / (2.0 * STEP_KELVIN);
            assertEquals(difference, V3WaterProperties.dLogSaturationPressureDT(temperature),
                    TOLERANCE * Math.abs(difference), "T=" + temperature);
        }
    }

    @Test
    void waterVapourHeatCapacityMatchesACentralDifferenceOfItsEnthalpy() {
        for (double temperature = 280.0; temperature <= 890.0; temperature += 5.0) {
            double difference = (V3WaterProperties.vaporMolarEnthalpy(temperature + STEP_KELVIN)
                    - V3WaterProperties.vaporMolarEnthalpy(temperature - STEP_KELVIN)) / (2.0 * STEP_KELVIN);
            assertEquals(difference, V3WaterProperties.dVaporMolarEnthalpyDT(temperature),
                    TOLERANCE * Math.abs(difference), "T=" + temperature);
        }
    }

    /** The virial steam's heat capacity at constant partial pressure, at a vacuum tray's, a column's and 10 bar. */
    @Test
    void steamHeatCapacityMatchesACentralDifferenceOfItsEnthalpy() {
        for (double pressure : new double[] {5.0e3, 2.5e5, 1.0e6}) {
            for (double temperature = 280.0; temperature <= 890.0; temperature += 5.0) {
                double difference = (V3WaterProperties.steamMolarEnthalpy(temperature + STEP_KELVIN, pressure)
                        - V3WaterProperties.steamMolarEnthalpy(temperature - STEP_KELVIN, pressure)) / (2.0 * STEP_KELVIN);
                assertEquals(difference, V3WaterProperties.dSteamMolarEnthalpyDT(temperature, pressure),
                        TOLERANCE * Math.abs(difference), "T=" + temperature + ", p=" + pressure);
            }
        }
    }

    /**
     * The steam's enthalpy rate in a tray's gas: its derivatives in the steam, the hydrocarbon vapour and the temperature,
     * which the local block Jacobian carries, against relative central differences.
     */
    @Test
    void steamEnthalpyRateDerivativesMatchCentralDifferences() {
        double pressure = 2.5e5;
        for (double temperature : new double[] {330.0, 450.0, 600.0}) {
            for (double[] flows : new double[][] {{300.0, 3000.0}, {300.0, 30.0}, {1.0e-3, 500.0}}) {
                double water = flows[0], hydrocarbon = flows[1];
                double dw = 1.0e-4 * water, dv = 1.0e-4 * (water + hydrocarbon);
                double rate = V3WaterProperties.steamEnthalpyRate(temperature, pressure, water, hydrocarbon);
                double byWater = (V3WaterProperties.steamEnthalpyRate(temperature, pressure, water + dw, hydrocarbon)
                        - V3WaterProperties.steamEnthalpyRate(temperature, pressure, water - dw, hydrocarbon)) / (2.0 * dw);
                assertEquals(byWater, V3WaterProperties.steamEnthalpyRateByWater(temperature, pressure, water, hydrocarbon),
                        TOLERANCE * Math.abs(byWater), "dW at T=" + temperature);
                double byHydrocarbon = (V3WaterProperties.steamEnthalpyRate(temperature, pressure, water, hydrocarbon + dv)
                        - V3WaterProperties.steamEnthalpyRate(temperature, pressure, water, hydrocarbon - dv)) / (2.0 * dv);
                // Only the virial residual depends on V, a part in 1e3 of the rate: the difference is held to the
                // rate's own rounding where that is the larger.
                assertEquals(byHydrocarbon,
                        V3WaterProperties.steamEnthalpyRateByHydrocarbon(temperature, pressure, water, hydrocarbon),
                        Math.max(1.0e-6 * Math.abs(byHydrocarbon), 1.0e-14 * Math.abs(rate) / dv), "dV at T=" + temperature);
                double byTemperature = (V3WaterProperties.steamEnthalpyRate(temperature + STEP_KELVIN, pressure, water, hydrocarbon)
                        - V3WaterProperties.steamEnthalpyRate(temperature - STEP_KELVIN, pressure, water, hydrocarbon))
                        / (2.0 * STEP_KELVIN);
                assertEquals(byTemperature,
                        V3WaterProperties.steamEnthalpyRateByTemperature(temperature, pressure, water, hydrocarbon),
                        TOLERANCE * Math.abs(byTemperature), "dT at T=" + temperature);
            }
        }
        assertEquals(0.0, V3WaterProperties.steamEnthalpyRate(450.0, pressure, 0.0, 10.0), 0.0, "no steam, no rate");
    }

    /**
     * Region 1's heat capacity up to the boiling point at each pressure (free water exists only below it), at 20 MPa also
     * past the region's 623.15 K boundary, where the enthalpy continues with the held c_p.
     */
    @Test
    void liquidWaterHeatCapacityMatchesACentralDifferenceOfItsEnthalpy() {
        for (double pressure : new double[] {1.0e5, 2.5e5, 3.0e6, 2.0e7}) {
            double boiling = V3WaterProperties.saturationTemperatureKelvin(pressure);
            for (double temperature = 280.0; temperature <= boiling; temperature += 5.0) {
                double difference = (V3WaterProperties.liquidMolarEnthalpy(temperature + STEP_KELVIN, pressure)
                        - V3WaterProperties.liquidMolarEnthalpy(temperature - STEP_KELVIN, pressure)) / (2.0 * STEP_KELVIN);
                assertEquals(difference, V3WaterProperties.dLiquidMolarEnthalpyDT(temperature, pressure),
                        TOLERANCE * Math.abs(difference), "T=" + temperature + ", p=" + pressure);
            }
        }
    }

    /**
     * The water datum: saturated liquid water at 298.15 K sits the record's declared latent heat (IF97's, 2441.706 kJ/kg)
     * below the saturated steam (W3b of batch 2026-11-11-aqueous-solvents; the Watson latent heat it used before stood
     * 564.5 J/mol higher).
     */
    @Test
    void theLiquidSitsOneVaporizationEnthalpyBelowTheSteamAtTheDatum() {
        double saturation = V3WaterProperties.saturationPressurePascal(298.15);
        double gap = V3WaterProperties.steamMolarEnthalpy(298.15, saturation) - V3WaterProperties.liquidMolarEnthalpy(298.15, saturation);
        assertEquals(43988.01, gap, 1.0e-9 * gap);
    }

    /** Above the critical point the vaporisation enthalpy is the constant zero. */
    @Test
    void vaporisationEnthalpyIsZeroFromTheCriticalPointUpward() {
        for (double temperature : new double[] {
                V3WaterProperties.criticalTemperature(), 700.0, V3WaterProperties.maximumTemperature()}) {
            assertEquals(0.0, V3WaterProperties.vaporizationEnthalpy(temperature), 0.0);
        }
    }

    /**
     * Past the steam's data (273.16 to 900 K) its enthalpy holds the end heat capacity, as the network's does, so value and
     * slope are continuous at either end (F3 of batch 2026-11-13-codebase-review).
     */
    @Test
    void steamEnthalpyContinuesPastItsDataAtTheEndHeatCapacity() {
        double top = V3WaterProperties.maximumTemperature(), triple = V3WaterProperties.triplePoint();
        assertEquals(900.0, top, 0.0, "the record's top temperature");
        double expected = V3WaterProperties.vaporMolarEnthalpy(top) + V3WaterProperties.dVaporMolarEnthalpyDT(top) * 50.0;
        assertEquals(expected, V3WaterProperties.vaporMolarEnthalpy(950.0), 1.0e-12 * Math.abs(expected), "h(950 K)");
        assertEquals(V3WaterProperties.dVaporMolarEnthalpyDT(top), V3WaterProperties.dVaporMolarEnthalpyDT(950.0), 0.0, "held c_p");
        double below = V3WaterProperties.vaporMolarEnthalpy(triple) + V3WaterProperties.dVaporMolarEnthalpyDT(triple) * (260.0 - triple);
        assertEquals(below, V3WaterProperties.vaporMolarEnthalpy(260.0), 1.0e-12 * Math.abs(below), "h(260 K)");
        for (double end : new double[] {triple, top}) {
            double left = (V3WaterProperties.vaporMolarEnthalpy(end) - V3WaterProperties.vaporMolarEnthalpy(end - STEP_KELVIN)) / STEP_KELVIN;
            double right = (V3WaterProperties.vaporMolarEnthalpy(end + STEP_KELVIN) - V3WaterProperties.vaporMolarEnthalpy(end)) / STEP_KELVIN;
            assertEquals(left, right, 1.0e-6 * Math.abs(left), "slope continuous at " + end + " K");
            assertEquals(V3WaterProperties.dVaporMolarEnthalpyDT(end), right, 1.0e-6 * Math.abs(right), "c_p at " + end + " K");
        }
        assertEquals(V3WaterProperties.vaporMolarEnthalpy(950.0) + com.wormzjl.icheme.science.thermo.phase.SteamVirial.residualEnthalpy(950.0, 1.0e5),
                V3WaterProperties.steamMolarEnthalpy(950.0, 1.0e5), 0.0, "the virial steam at 950 K evaluates");
    }

    /** Below the triple point the column's saturation pressure is Wagner-Pruss continued, as the network's (F3). */
    @Test
    void saturationPressureBelowTheTriplePointIsWagnerPruss() {
        var water = com.wormzjl.icheme.science.material.MaterialRuntime.water();
        assertEquals(V3WaterProperties.wagnerPruss(water, 263.0), V3WaterProperties.saturationPressurePascal(263.0), 0.0);
        double difference = (Math.log(V3WaterProperties.saturationPressurePascal(263.0 + STEP_KELVIN))
                - Math.log(V3WaterProperties.saturationPressurePascal(263.0 - STEP_KELVIN))) / (2.0 * STEP_KELVIN);
        assertEquals(difference, V3WaterProperties.dLogSaturationPressureDT(263.0), TOLERANCE * Math.abs(difference));
    }

    /**
     * Every derivative refuses exactly the envelope its value refuses: the steam only a temperature that is not one, the
     * saturation above the critical point (nothing saturates there) and at no positive temperature.
     */
    @Test
    void derivativesShareTheDomainChecksOfTheirValues() {
        for (double outside : new double[] {Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> V3WaterProperties.vaporMolarEnthalpy(outside));
            assertThrows(IllegalArgumentException.class, () -> V3WaterProperties.dVaporMolarEnthalpyDT(outside));
            assertThrows(IllegalArgumentException.class, () -> V3WaterProperties.steamMolarEnthalpy(outside, 1.0e5));
            assertThrows(IllegalArgumentException.class, () -> V3WaterProperties.dSteamMolarEnthalpyDT(outside, 1.0e5));
        }
        for (double outside : new double[] {273.0, 901.0, Double.NaN})
            assertThrows(IllegalArgumentException.class, () -> V3WaterProperties.vaporizationEnthalpy(outside));
        for (double outside : new double[] {0.0, 648.0, Double.NaN}) {
            assertThrows(IllegalArgumentException.class, () -> V3WaterProperties.saturationPressurePascal(outside));
            assertThrows(IllegalArgumentException.class, () -> V3WaterProperties.dLogSaturationPressureDT(outside));
        }
        // The liquid continues past Region 1's range (owner rule of 2026-10-16) and refuses only a state that is not one.
        assertThrows(IllegalArgumentException.class, () -> V3WaterProperties.liquidMolarEnthalpy(Double.NaN, 1.0e5));
        assertThrows(IllegalArgumentException.class, () -> V3WaterProperties.dLiquidMolarEnthalpyDT(300.0, 0.0));
    }
}
