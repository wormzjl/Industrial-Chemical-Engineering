package com.wormzjl.icheme.science.thermo.water;

import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;

/**
 * IF97 Region 2's ideal-gas part (IAPWS R7-97(2012), table 10), molar on the molar gas constant: the water share's
 * reference ideal gas (moved from {@code WaterShare.gapAt}, operation for operation).
 */
public final class Iapws97Region2Ideal {
    private static final double R = PengRobinsonKernel.GAS_CONSTANT;
    private static final int[] J0 = {0, 1, -5, -4, -3, -2, -1, 2, 3};
    private static final double[] N0 = {-0.96927686500217e1, 0.10086655968018e2, -0.56087911283020e-2, 0.71452738081455e-1,
            -0.40710498223928, 0.14240819171444e1, -0.43839511319450e1, -0.28408632460772, 0.21268463753307e-1};
    /** Indices into the output of {@link #molar}. */
    public static final int H = 0, CP = 1, S = 2, SIZE = 3;

    private Iapws97Region2Ideal() {}

    /** {@code h}, {@code c_p} and {@code s} of the ideal gas at {@code (t, p)}, molar, into {@code out}. */
    public static void molar(double t, double p, double[] out) {
        double tau = 540.0 / t, gt = 0.0, gtt = 0.0, g0 = DeterministicMath.log(p / 1.0e6);
        for (int k = 0; k < J0.length; k++) {
            int j = J0[k];
            g0 += N0[k] * power(tau, j);
            if (j != 0) gt += N0[k] * j * power(tau, j - 1);
            if (j != 0 && j != 1) gtt += N0[k] * j * (j - 1) * power(tau, j - 2);
        }
        out[H] = R * t * tau * gt;
        out[CP] = -R * tau * tau * gtt;
        out[S] = R * (tau * gt - g0);
    }

    private static double power(double x, int n) {
        double base = n < 0 ? 1.0 / x : x, out = 1.0;
        for (int k = Math.abs(n); k > 0; k--) out *= base;
        return out;
    }
}
