package com.wormzjl.icheme.science.thermo.phase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import org.junit.jupiter.api.Test;

/**
 * The free water's second-virial steam ({@link SteamVirial}, {@code separate-free-water-v3}) against the full IAPWS-IF97
 * Region 2 ({@link If97Region2}): B and the residual enthalpy are Region 2's low-pressure limits, and the qualified
 * partial pressure ({@link FluidThermodynamics#waterVaporPressureLimit}) bounds the virial's volume within 2 % of Region 2.
 */
class SteamVirialTest {
    private static final double R = PengRobinsonKernel.GAS_CONSTANT;

    /** The reference first: IF97's computer-program verification values, tables 15 (Region 2) and 35 (Region 4). */
    @Test
    void theReferenceReproducesIf97sVerificationValues() {
        assertEquals(0.394913866e2, If97Region2.specificVolume(300, 3.5e3), 1e-8 * 0.394913866e2);
        assertEquals(0.923015898e2, If97Region2.specificVolume(700, 3.5e3), 1e-8 * 0.923015898e2);
        assertEquals(0.542946619e-2, If97Region2.specificVolume(700, 30e6), 1e-8 * 0.542946619e-2);
        // h(700 K, 30 MPa) - h(700 K, 3.5 kPa): the ideal part cancels, the residual's tau derivative remains, kJ/kg.
        double dh = If97Region2.SPECIFIC_GAS_CONSTANT * 700 * (If97Region2.residualEnthalpy(700, 30e6) - If97Region2.residualEnthalpy(700, 3.5e3)) / 1e3;
        assertEquals(0.263149474e4 - 0.333568375e4, dh, 1e-5);
        assertEquals(0.353658941e4, If97Region2.saturationPressure(300), 1e-8 * 0.353658941e4);
        assertEquals(0.263889776e7, If97Region2.saturationPressure(500), 1e-8 * 0.263889776e7);
        assertEquals(0.123443146e8, If97Region2.saturationPressure(600), 1e-8 * 0.123443146e8);
        assertEquals(If97Region2.saturationPressure(623.15), If97Region2.b23Pressure(623.15), 1e-4 * 16.5e6, "B23 meets saturation at 623.15 K");
    }

    /**
     * {@code B p_ref/(R T)} and {@code h_res p_ref/(R T p)} ({@code p_ref} = 1 MPa) equal Region 2's {@code (Z - 1)/pi} and {@code tau gamma^r_tau/pi} as
     * {@code pi -> 0} (Richardson's limit from 1 and 2 Pa, which removes the {@code I = 2} terms), over Region 2's range.
     */
    @Test
    void theSecondVirialAndItsResidualEnthalpyAreRegion2sLowPressureLimits() {
        for (double t = If97Region2.FLOOR; t <= If97Region2.CEILING; t += 2.5) {
            double pi = 1e-6;
            double z = 2 * (If97Region2.compressibility(t, 1) - 1) / pi - (If97Region2.compressibility(t, 2) - 1) / (2 * pi);
            double h = 2 * If97Region2.residualEnthalpy(t, 1) / pi - If97Region2.residualEnthalpy(t, 2) / (2 * pi);
            double b = SteamVirial.secondVirial(t) * 1e6 / (R * t);
            assertEquals(z, b, 1e-6 * Math.abs(z), t + " K: B");
            assertEquals(h, SteamVirial.residualEnthalpy(t, 1e6) / (R * t), 1e-6 * Math.abs(h), t + " K: h_res");
            // The derivative is B's own: a central difference of 1 mK (above the floor, where the second derivative jumps).
            if (t == If97Region2.FLOOR) continue;
            double slope = (SteamVirial.secondVirial(t + 1e-3) - SteamVirial.secondVirial(t - 1e-3)) / 2e-3;
            assertEquals(slope, SteamVirial.temperatureDerivative(t), 1e-6 * Math.abs(slope), t + " K: dB/dT");
        }
        // Below Region 2's floor B continues linearly, value and slope continuous.
        double floor = SteamVirial.MINIMUM_TEMPERATURE;
        assertEquals(SteamVirial.secondVirial(floor) - 10 * SteamVirial.temperatureDerivative(floor), SteamVirial.secondVirial(floor - 10), 1e-15);
        assertEquals(SteamVirial.temperatureDerivative(floor + 1e-9), SteamVirial.temperatureDerivative(floor - 1e-9), 1e-6 * SteamVirial.temperatureDerivative(floor));
    }

    /**
     * Over Region 2 (275 K to its 1073.15 K ceiling, up to saturation, B23 or 100 MPa), the virial's volume is within 2 %
     * of Region 2's wherever the steam is at or below the qualified partial pressure; each band's limit is where the
     * virial first leaves 2 % at the band's lower temperature, rounded down to two digits.
     */
    @Test
    void theQualifiedPartialPressureBoundsTheVirialWithinTwoPercentOfRegion2() {
        int accepted = 0, rejected = 0;
        for (double t = 275; t <= If97Region2.CEILING; t += 2.5) {
            double top = If97Region2.maximumPressure(t), limit = FluidThermodynamics.waterVaporPressureLimit(t);
            for (int k = 1; k <= 400; k++) {
                double p = top * k / 400;
                if (p <= limit) {
                    assertTrue(Math.abs(error(t, p)) <= 0.02, t + " K, " + p + " Pa: " + error(t, p));
                    accepted++;
                } else rejected++;
            }
        }
        assertTrue(accepted > 50000 && rejected > 20000, accepted + " accepted, " + rejected + " rejected");
        // The first band: the virial meets 2 % on the saturation line at 480.9 K and 1.82 MPa.
        double low = 450, high = 520;
        for (int k = 0; k < 60; k++) {
            double m = 0.5 * (low + high);
            if (Math.abs(error(m, If97Region2.saturationPressure(m))) <= 0.02) low = m; else high = m;
        }
        double saturated = If97Region2.saturationPressure(low);
        assertTrue(saturated >= FluidThermodynamics.waterVaporPressureLimit(low) && saturated < 1.1 * FluidThermodynamics.waterVaporPressureLimit(low),
                "2 % at saturation: " + low + " K, " + saturated + " Pa");
        // Every later band: its limit lies within 10 % below the first 2 % crossing at its lower temperature.
        for (double t : new double[] {500, 525, 550, 575, 600, 625, 650, 675, 700, 725, 750, 775, 800, 825, 850}) {
            double crossing = firstCrossing(t), limit = FluidThermodynamics.waterVaporPressureLimit(t);
            assertTrue(limit <= crossing && crossing < 1.1 * limit, t + " K: limit " + limit + " Pa, 2 % at " + crossing + " Pa");
        }
        // From 875 K the least crossing up to Region 2's ceiling (65.5 MPa at 1000 K).
        double least = Double.POSITIVE_INFINITY;
        for (double t = 875; t <= If97Region2.CEILING; t += 5) least = Math.min(least, firstCrossing(t));
        double last = FluidThermodynamics.waterVaporPressureLimit(875);
        assertTrue(last <= least && least < 1.1 * last, "from 875 K: limit " + last + " Pa, least 2 % crossing " + least + " Pa");
    }

    /**
     * The network's state holds the virial steam: alone at the state pressure, {@code V = n (R T/P + B)} and {@code p_w = P},
     * its enthalpy the ideal gas's plus {@code p (B - T dB/dT)}; beside nitrogen, saturated over free water, the gas holds
     * {@code p_sat V/(R T + p_sat B)} of steam and the partial pressures close.
     */
    @Test
    void theNetworkStateHoldsTheVirialSteam() {
        var model = FluidTestSupport.networkModel();
        int n = model.componentCount() - 1;
        double t = 453.03, p = 1e6;
        var steam = model.state(t, p, new double[n], new double[n], 0, 2, p);
        assertEquals(2 * SteamVirial.molarVolume(t, p), steam.vaporVolume(), 1e-14);
        assertEquals(p, steam.waterPartialPressure());
        double h = 2 * (model.vaporWaterEnthalpy(t) + SteamVirial.residualEnthalpy(t, p));
        assertEquals(h, steam.enthalpy(), 1e-12 * Math.abs(h));
        assertEquals(h - p * steam.volume(), steam.internalEnergy(), 1e-12 * Math.abs(h));
        // Z = 1 + B p/(R T) = 0.939 at 10 bar on saturation (Region 2: 0.930), B = -229 cm3/mol.
        assertEquals(-0.0607, SteamVirial.secondVirial(t) * p / (R * t), 5e-4);

        double[] overall = new double[n + 1];
        overall[model.components().indexOf("Nitrogen")] = 1;
        overall[n] = 10;
        double hot = 450, total = 2e6;
        var wet = model.flashTP(hot, total, overall, () -> { });
        double saturation = model.saturationPressure(hot);
        assertTrue(wet.waterLiquid() > 0 && wet.waterVapor() > 0, wet.toString());
        assertEquals(saturation, wet.waterPartialPressure(), 1e-6 * saturation, "the gas saturated over the free water");
        assertEquals(total, wet.hydrocarbonPartialPressure() + wet.waterPartialPressure(), 1e-6 * total, "Dalton's closure");
        assertEquals(SteamVirial.moles(saturation, hot, wet.vaporVolume()), wet.waterVapor(), 1e-6 * wet.waterVapor());
    }

    /** {@code v_virial / v_IF97 - 1}: the compressibilities' ratio, R and the molar mass cancelling. */
    private static double error(double t, double p) {
        return SteamVirial.molarVolume(t, p) * p / (R * t) / If97Region2.compressibility(t, p) - 1;
    }

    /** The first pressure at {@code t} where the virial leaves 2 % of Region 2 (a scan, then bisection), infinite if none. */
    private static double firstCrossing(double t) {
        double top = If97Region2.maximumPressure(t), previous = 0;
        for (int k = 1; k <= 4000; k++) {
            double p = top * k / 4000;
            if (Math.abs(error(t, p)) > 0.02) {
                double low = previous, high = p;
                for (int j = 0; j < 60; j++) {
                    double m = 0.5 * (low + high);
                    if (Math.abs(error(t, m)) <= 0.02) low = m; else high = m;
                }
                return low;
            }
            previous = p;
        }
        return Double.POSITIVE_INFINITY;
    }
}
