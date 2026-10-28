package com.wormzjl.icheme.science.thermo.phase;

import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;

/**
 * The free water's steam as a second-virial gas ({@code separate-free-water-v3}, 2026-10-28; option B of
 * {@code documentation/2026-10-27-heat-exchanger-design/OWNER_TEST_REVIEW.md}, section 5): the steam's ideal-gas
 * functions ({@link FreeWaterModel#vapor()}) plus the residual of IAPWS-IF97 Region 2 truncated to its five terms linear
 * in pressure ({@code I = 1}).
 *
 * <p>The model. {@code v = R T / p + B(T)}, with {@code B(T) = (R T / p*) sum n_i (tau - 0.5)^J_i},
 * {@code p* = 1 MPa} and {@code tau = 540 K / T}: the low-pressure limit of Region 2's volume. Its residual properties at
 * the steam's partial pressure {@code p} are {@code h_res = p (B - T dB/dT)}, {@code s_res = -p dB/dT},
 * {@code g_res = p B} and {@code u_res = -p T dB/dT}; {@code n_w} moles of steam in a gas volume {@code V} (Dalton: the
 * hydrocarbon vapour shares it) are at {@code p_w = n_w R T / (V - n_w B)}, the exact inverse of
 * {@code V = n_w (R T / p_w + B)}. Its volume is within 2 % of the full Region 2's up to saturation below 480.9 K
 * (1.82 MPa; 1.0 % at 10 bar, where ideal steam is 7.6 % off) and to 4.9 MPa at 584 K: the qualified partial pressure,
 * {@code FluidThermodynamics.waterVaporPressureLimit}.</p>
 *
 * <p>Range. The polynomial is Region 2's from its 273.15 K floor up; past its 1073.15 K ceiling it continues as it is
 * ({@code |B p / (R T)|} below 0.4 % at 20 bar there and falling with temperature; the steam's record ends at 900 K).
 * Below 273.15 K it continues linearly, value and slope continuous (owner rule of 2026-10-16), where the polynomial's
 * {@code tau^6} term would grow without bound; the steam there is at the ice's sublimation pressure, at most 611 Pa, and
 * {@code |B p / (R T)|} at most 5.1e-4. The steam's notice of the water model's range reports both ends.</p>
 *
 * <p>Pure functions of their arguments, safe to share; nothing allocates.</p>
 */
public final class SteamVirial {
    private static final double R = PengRobinsonKernel.GAS_CONSTANT;
    /** IF97's reducing temperature of Region 2, K. */
    private static final double TAU_TEMPERATURE = 540.0;
    /** IF97's reducing pressure of Region 2, Pa. */
    private static final double REDUCING_PRESSURE = 1.0e6;
    /** Region 2's floor, K: below it {@link #secondVirial} continues linearly. */
    public static final double MINIMUM_TEMPERATURE = 273.15;
    /** IF97 Region 2, table 11: the residual terms with {@code I = 1}. */
    private static final int[] J = {0, 1, 2, 3, 6};
    private static final double[] N = {-0.17731742473213e-2, -0.17834862292358e-1, -0.45996013696365e-1,
            -0.57581259083432e-1, -0.50325278727930e-1};
    private static final double FLOOR_B = polynomial(MINIMUM_TEMPERATURE);
    private static final double FLOOR_SLOPE = polynomialSlope(MINIMUM_TEMPERATURE);

    private SteamVirial() {}

    /** The second virial coefficient {@code B(T)}, m3/mol (negative below about 1550 K). */
    public static double secondVirial(double t) {
        require(t);
        return t < MINIMUM_TEMPERATURE ? FLOOR_B + FLOOR_SLOPE * (t - MINIMUM_TEMPERATURE) : polynomial(t);
    }

    /** {@code dB/dT}, m3/(mol K). */
    public static double temperatureDerivative(double t) {
        require(t);
        return t < MINIMUM_TEMPERATURE ? FLOOR_SLOPE : polynomialSlope(t);
    }

    /** The molar volume of steam at {@code (t, p)}, {@code R T / p + B}, m3/mol; it is not positive past {@code p = R T / |B|}. */
    public static double molarVolume(double t, double p) {
        return R * t / p + secondVirial(t);
    }

    /**
     * The partial pressure of {@code steam} moles in the gas volume {@code volume}, Pa: {@code n R T / (V - n B)}.
     * Infinite where {@code V <= n B} (a positive B above about 1550 K and a gas volume the steam's co-volume fills: no
     * state of the virial gas).
     */
    public static double partialPressure(double steam, double t, double volume) {
        double free = volume - steam * secondVirial(t);
        return free > 0.0 ? steam * R * t / free : Double.POSITIVE_INFINITY;
    }

    /** The moles of steam at partial pressure {@code p} in the gas volume {@code volume}: {@code p V / (R T + p B)}. */
    public static double moles(double p, double t, double volume) {
        return p * volume / (R * t + p * secondVirial(t));
    }

    /** The residual molar enthalpy at the steam's partial pressure {@code p}, {@code p (B - T dB/dT)}, J/mol. */
    public static double residualEnthalpy(double t, double p) {
        return p * (secondVirial(t) - t * temperatureDerivative(t));
    }

    /** The residual molar entropy at the steam's partial pressure {@code p}, {@code -p dB/dT}, J/(mol K). */
    public static double residualEntropy(double t, double p) {
        return -p * temperatureDerivative(t);
    }

    /** The residual molar Gibbs energy at {@code p}, {@code p B}, J/mol: {@code ln phi = p B / (R T)}. */
    public static double residualGibbs(double t, double p) {
        return p * secondVirial(t);
    }

    private static double polynomial(double t) {
        double s = TAU_TEMPERATURE / t - 0.5, sum = 0.0;
        for (int i = 0; i < N.length; i++) sum += N[i] * power(s, J[i]);
        return R * t / REDUCING_PRESSURE * sum;
    }

    /** {@code dB/dT = (R / p*) (f - tau f')}, {@code f} the sum of {@link #polynomial} and {@code f'} its tau derivative. */
    private static double polynomialSlope(double t) {
        double tau = TAU_TEMPERATURE / t, s = tau - 0.5, f = 0.0, slope = 0.0;
        for (int i = 0; i < N.length; i++) {
            f += N[i] * power(s, J[i]);
            if (J[i] > 0) slope += N[i] * J[i] * power(s, J[i] - 1);
        }
        return R / REDUCING_PRESSURE * (f - tau * slope);
    }

    private static double power(double x, int n) {
        double result = 1.0;
        for (int k = 0; k < n; k++) result *= x;
        return result;
    }

    private static void require(double t) {
        if (!(t > 0.0) || !Double.isFinite(t)) throw new IllegalArgumentException("Steam temperature must be positive and finite: " + t);
    }
}
