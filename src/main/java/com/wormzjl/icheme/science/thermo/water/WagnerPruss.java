package com.wormzjl.icheme.science.thermo.water;

import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * The IAPWS Wagner-Pruss auxiliary equation of water's saturation line, the one copy of it (F2 of batch
 * 2026-11-13-codebase-review): {@code ln(p_s/p_c) = (T_c/T) sum a_i theta^e_i}, {@code theta = 1 - T/T_c}, exponents
 * 1, 1.5, 3, 3.5, 4, 7.5. Below the triple point it is the supercooled liquid's saturation pressure (1.10 times the ice
 * sublimation pressure at 263 K, the measured ratio): the network and the column both continue there (owner rule of
 * 2026-10-16). Above the critical temperature there is no saturation.
 *
 * <p>The association is the column's ({@code a2 (theta root)}, half-integer powers as products with the correctly
 * rounded square root, not {@code pow}); the network's water share and the trained-physics check had another and moved
 * to this one by about 1e-16 relative (C1a's stated shift).</p>
 */
public final class WagnerPruss {
    private WagnerPruss() {}

    /** {@code sum a_i theta^e_i} at {@code theta}. */
    public static double polynomial(double a1, double a2, double a3, double a4, double a5, double a6, double theta) {
        // Half-integer powers as products with the correctly rounded sqrt, not pow (batch 2026-09-27-jdk-determinism).
        double root = Math.sqrt(theta), cube = theta * theta * theta;
        return a1 * theta + a2 * (theta * root)
                + a3 * theta * theta * theta + a4 * (cube * root)
                + a5 * theta * theta * theta * theta + a6 * (cube * cube * theta * root);
    }

    private static double polynomial(WaterConstants w, double theta) {
        return polynomial(w.saturation(0), w.saturation(1), w.saturation(2), w.saturation(3), w.saturation(4), w.saturation(5), theta);
    }

    /**
     * The saturation pressure, Pa, at any positive temperature up to the critical point; the critical pressure at it.
     * Refuses a temperature that is not positive or is above critical.
     */
    public static double pressure(WaterConstants w, double temperatureKelvin) {
        if (!(temperatureKelvin > 0) || !(temperatureKelvin <= w.criticalTemperature()))
            throw new IllegalArgumentException("Water saturation temperature must be positive and at most critical: " + temperatureKelvin);
        if (temperatureKelvin == w.criticalTemperature()) return w.criticalPressure();
        return w.criticalPressure() * DeterministicMath.exp(w.criticalTemperature() / temperatureKelvin
                * polynomial(w, 1.0 - temperatureKelvin / w.criticalTemperature()));
    }

    /**
     * {@code d ln(p_s)/dT}, 1/K, the same equation differentiated; finite at the critical point, where {@code theta}
     * vanishes and only the linear coefficient survives. No range check: the caller has made one.
     */
    public static double logSlope(WaterConstants w, double temperatureKelvin) {
        double theta = 1.0 - temperatureKelvin / w.criticalTemperature();
        double root = Math.sqrt(theta), cube = theta * theta * theta;
        double polynomial = polynomial(w, theta);
        double slope = w.saturation(0) + 1.5 * w.saturation(1) * Math.sqrt(theta)
                + 3.0 * w.saturation(2) * theta * theta + 3.5 * w.saturation(3) * (theta * theta * root)
                + 4.0 * w.saturation(4) * theta * theta * theta + 7.5 * w.saturation(5) * (cube * cube * root);
        return -w.criticalTemperature() * polynomial / (temperatureKelvin * temperatureKelvin) - slope / temperatureKelvin;
    }

    /**
     * The temperature, K, whose saturation pressure is {@code pressurePascal}: bounded bisection (80 halvings) between the
     * triple point and the critical temperature. Refuses a pressure outside {@code [p_s(T_t), p_c]}.
     */
    public static double temperature(WaterConstants w, double pressurePascal) {
        if (!Double.isFinite(pressurePascal) || pressurePascal <= 0.0 || pressurePascal > w.criticalPressure()) {
            throw new IllegalArgumentException("Water saturation pressure is outside the correlation envelope");
        }
        double minimumPressure = pressure(w, w.triplePoint());
        if (pressurePascal < minimumPressure) {
            throw new IllegalArgumentException("Water saturation pressure is below the triple-point envelope");
        }
        double low = w.triplePoint();
        double high = w.criticalTemperature();
        for (int iteration = 0; iteration < 80; iteration++) {
            double middle = 0.5 * (low + high);
            if (pressure(w, middle) < pressurePascal) low = middle;
            else high = middle;
        }
        return 0.5 * (low + high);
    }
}
