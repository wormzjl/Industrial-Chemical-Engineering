package com.wormzjl.icheme.science.thermo.water;

import com.wormzjl.icheme.science.math.DeterministicMath;

/**
 * The IAPWS 2008 dilute-gas viscosity term of water (Pa.s); not a liquid or dense-steam correlation. Moved from
 * {@code WaterRegion1}, operation for operation.
 */
public final class DiluteSteamViscosity {
    /** The term's data floor, K. */
    public static final double LOW = 273.16;
    private static final double AT_LOW = formula(LOW / 647.096);
    private static final double EXPONENT_AT_LOW = exponent(LOW / 647.096);

    private DiluteSteamViscosity() {}

    /**
     * The viscosity, Pa.s. Its data range is 273.16 to 900 K. Above it the formula continues itself; below it the
     * viscosity goes as a power of the temperature with the formula's own exponent at 273.16 K
     * ({@code d ln mu / d ln T}, 0.876), value and slope continuous, because the formula's denominator has a root near
     * 134.5 K.
     */
    public static double viscosity(double temperatureKelvin) {
        if (!(temperatureKelvin > 0) || !Double.isFinite(temperatureKelvin))
            throw new IllegalArgumentException("Vapor viscosity temperature must be positive and finite: " + temperatureKelvin);
        if (temperatureKelvin < LOW)
            return AT_LOW * DeterministicMath.pow(temperatureKelvin / LOW, EXPONENT_AT_LOW);
        return formula(temperatureKelvin / 647.096);
    }

    private static double formula(double reduced) {
        return 1e-4*Math.sqrt(reduced)/(1.67752+2.20462/reduced+.6366564/(reduced*reduced)-.241605/(reduced*reduced*reduced));
    }

    /** {@code d ln mu / d ln T} of {@link #formula}: {@code 1/2 + (a1/x + 2 a2/x^2 + 3 a3/x^3) / D(x)}. */
    private static double exponent(double reduced) {
        double denominator=1.67752+2.20462/reduced+.6366564/(reduced*reduced)-.241605/(reduced*reduced*reduced);
        return .5+(2.20462/reduced+2*.6366564/(reduced*reduced)-3*.241605/(reduced*reduced*reduced))/denominator;
    }
}
