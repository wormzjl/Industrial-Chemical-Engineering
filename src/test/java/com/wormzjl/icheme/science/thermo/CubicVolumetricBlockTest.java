package com.wormzjl.icheme.science.thermo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * S08 (codebase review, wave C): the kernel's one copy of the cubic's volumetric block gives, bit for bit, each of the
 * four forms it replaced, written out here as they were: the translated model's ({@code (v-b)*(v-b)}, {@code -R*T},
 * {@code 2*R*T}), phase identification's ({@code free * free}), the water share's ({@code -rt}, {@code 2.0 * rt} with
 * {@code rt = R t}) and the engine's pressure start.
 */
class CubicVolumetricBlockTest {
    private static final double R = PengRobinsonKernel.GAS_CONSTANT;

    @Test void everyFormerFormIsTheKernelsBlockBitForBit() {
        var random = new Random(20261113L);
        int compared = 0;
        for (int sample = 0; sample < 20_000; sample++) {
            double t = 50.0 + 1500.0 * random.nextDouble();
            double b = 1.0e-5 * (0.5 + 30.0 * random.nextDouble());
            double v = b * (1.0 + Math.exp(12.0 * random.nextDouble() - 6.0));
            double a = 0.01 + 40.0 * random.nextDouble();
            double dadt = -1.0e-2 * random.nextDouble();
            // The translated model's fill.
            double denominator = v*v + 2*b*v - b*b;
            double dpdv = -R*t / ((v-b)*(v-b)) + 2*a*(v+b) / (denominator*denominator);
            double dpdt = R/(v-b) - dadt/denominator;
            double dpdvv = 2*R*t/((v-b)*(v-b)*(v-b)) + 2*a/(denominator*denominator)
                    - 8*a*(v+b)*(v+b)/(denominator*denominator*denominator);
            double dpdtv = -R/((v-b)*(v-b)) + 2*dadt*(v+b)/(denominator*denominator);
            same(denominator, PengRobinsonKernel.cubicDenominator(v, b));
            same(dpdv, PengRobinsonKernel.pressureVolumeDerivative(t, v, a, b));
            same(dpdt, PengRobinsonKernel.pressureTemperatureDerivative(v, b, dadt));
            same(dpdvv, PengRobinsonKernel.pressureSecondVolumeDerivative(t, v, a, b));
            same(dpdtv, PengRobinsonKernel.pressureTemperatureVolumeDerivative(v, b, dadt));
            // Phase identification.
            double free = v - b;
            double den = v * v + 2.0 * b * v - b * b;
            same(-R * t / (free * free) + 2.0 * a * (v + b) / (den * den), PengRobinsonKernel.pressureVolumeDerivative(t, v, a, b));
            same(R / free - dadt / den, PengRobinsonKernel.pressureTemperatureDerivative(v, b, dadt));
            same(2.0 * R * t / (free * free * free) + 2.0 * a / (den * den) - 8.0 * a * (v + b) * (v + b) / (den * den * den),
                    PengRobinsonKernel.pressureSecondVolumeDerivative(t, v, a, b));
            same(-R / (free * free) + 2.0 * dadt * (v + b) / (den * den), PengRobinsonKernel.pressureTemperatureVolumeDerivative(v, b, dadt));
            // The water share.
            double rt = R * t;
            same(-rt / ((v - b) * (v - b)) + 2.0 * a * (v + b) / (den * den), PengRobinsonKernel.pressureVolumeDerivative(t, v, a, b));
            same(2.0 * rt / ((v - b) * (v - b) * (v - b)) + 2.0 * a / (den * den) - 8.0 * a * (v + b) * (v + b) / (den * den * den),
                    PengRobinsonKernel.pressureSecondVolumeDerivative(t, v, a, b));
            // The engine's pressure start.
            same(R * t / free - a / den, PengRobinsonKernel.cubicPressure(t, v, a, b));
            compared += 12;
        }
        assertEquals(240_000, compared);
    }

    private static void same(double expected, double actual) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual), expected + " vs " + actual);
    }
}
