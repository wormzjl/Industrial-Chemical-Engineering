package com.wormzjl.icheme.science.thermo.phase;

/**
 * IAPWS-IF97 Region 2 in full, the test reference of {@link SteamVirial}: the residual part's 43 terms (IF97 table 11),
 * the saturation-pressure equation of Region 4 (table 34) and the B23 boundary (equation 5), which bound Region 2 below
 * 623.15 K and from 623.15 to 863.15 K. {@link SteamVirialTest} checks it against IF97's own computer-program verification
 * values (tables 15 and 35) before it judges the virial.
 */
final class If97Region2 {
    /** IF97's specific gas constant, J/(kg K). */
    static final double SPECIFIC_GAS_CONSTANT = 461.526;
    static final double CEILING = 1073.15;
    static final double FLOOR = 273.15;
    private static final int[] I = {1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 3, 3, 3, 3, 3, 4, 4, 4, 5, 6, 6, 6, 7, 7, 7, 8, 8, 9, 10, 10,
            10, 16, 16, 18, 20, 20, 20, 21, 22, 23, 24, 24, 24};
    private static final int[] J = {0, 1, 2, 3, 6, 1, 2, 4, 7, 36, 0, 1, 3, 6, 35, 1, 2, 3, 7, 3, 16, 35, 0, 11, 25, 8, 36, 13,
            4, 10, 14, 29, 50, 57, 20, 35, 48, 21, 53, 39, 26, 40, 58};
    private static final double[] N = {
            -0.17731742473213e-2, -0.17834862292358e-1, -0.45996013696365e-1, -0.57581259083432e-1, -0.50325278727930e-1,
            -0.33032641670203e-4, -0.18948987516315e-3, -0.39392777243355e-2, -0.43797295650573e-1, -0.26674547914087e-4,
            0.20481737692309e-7, 0.43870667284435e-6, -0.32277677238570e-4, -0.15033924542148e-2, -0.40668253562649e-1,
            -0.78847309559367e-9, 0.12790717852285e-7, 0.48225372718507e-6, 0.22922076337661e-5, -0.16714766451061e-10,
            -0.21171472321355e-2, -0.23895741934104e2, -0.59059564324270e-17, -0.12621808899101e-5, -0.38946842435739e-1,
            0.11256211360459e-10, -0.82311340897998e1, 0.19809712802088e-7, 0.10406965210174e-18, -0.10234747095929e-12,
            -0.10018179379511e-8, -0.80882908646985e-10, 0.10693031879409, -0.33662250574171, 0.89185845355421e-24,
            0.30629316876232e-12, -0.42002467698208e-5, -0.59056029685639e-25, 0.37826947613457e-5, -0.12768608934681e-14,
            0.73087610595061e-28, 0.55414715350778e-16, -0.94369707241210e-6};
    private static final double[] SATURATION = {0.11670521452767e4, -0.72421316703206e6, -0.17073846940092e2,
            0.12020824702470e5, -0.32325550322333e7, 0.14915108613530e2, -0.48232657361591e4, 0.40511340542057e6,
            -0.23855557567849, 0.65017534844798e3};

    private If97Region2() {}

    /** {@code p v / (R T) = 1 + pi gamma^r_pi}. */
    static double compressibility(double t, double p) {
        double pi = p / 1.0e6, s = 540.0 / t - 0.5, sum = 0.0;
        for (int k = 0; k < N.length; k++) sum += N[k] * I[k] * Math.pow(pi, I[k] - 1) * Math.pow(s, J[k]);
        return 1.0 + pi * sum;
    }

    /** The residual enthalpy over {@code R T}, {@code tau gamma^r_tau}. */
    static double residualEnthalpy(double t, double p) {
        double pi = p / 1.0e6, tau = 540.0 / t, s = tau - 0.5, sum = 0.0;
        for (int k = 0; k < N.length; k++) if (J[k] > 0) sum += N[k] * Math.pow(pi, I[k]) * J[k] * Math.pow(s, J[k] - 1);
        return tau * sum;
    }

    /** The specific volume, m3/kg. */
    static double specificVolume(double t, double p) {
        return SPECIFIC_GAS_CONSTANT * t / p * compressibility(t, p);
    }

    /** The saturation pressure of Region 4, Pa (273.15 K to 647.096 K). */
    static double saturationPressure(double t) {
        double[] n = SATURATION;
        double theta = t + n[8] / (t - n[9]);
        double a = theta * theta + n[0] * theta + n[1], b = n[2] * theta * theta + n[3] * theta + n[4],
                c = n[5] * theta * theta + n[6] * theta + n[7];
        return Math.pow(2.0 * c / (-b + Math.sqrt(b * b - 4.0 * a * c)), 4) * 1.0e6;
    }

    /** The B23 boundary between Regions 2 and 3, Pa. */
    static double b23Pressure(double t) {
        return (0.34805185628969e3 - 0.11671859879975e1 * t + 0.10192970039326e-2 * t * t) * 1.0e6;
    }

    /** Region 2's highest pressure at {@code t}: saturation to 623.15 K, B23 to 863.15 K, then 100 MPa. */
    static double maximumPressure(double t) {
        return t <= 623.15 ? saturationPressure(t) : t <= 863.15 ? b23Pressure(t) : 100.0e6;
    }
}
