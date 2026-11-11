package com.wormzjl.icheme.science.thermo.phase;

import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.thermo.HuronVidalExcess;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;

/**
 * Water's own share of enthalpy, entropy and volume in a phase of the cubic (batch 2026-11-11-aqueous-solvents, plan
 * M2, W3a decision D2): the balance book's correction, never part of the Gibbs energy or {@code ln phi}. Ported from
 * {@code tools/aqueous-solvents/water-share.mjs} with analytic derivatives.
 *
 * <p>Per mole of a phase with water fraction {@code x_w}, untranslated molar volume {@code v} and co-volume {@code b}:</p>
 * <ul>
 *   <li>{@code share = x_w W L(T, P) + x_w^2 (1 - W) G(T, P)}, the weight {@code W} a C2 quintic of {@code v/b}: 1 below
 *   {@link #RATIO_DENSE}, 0 above {@link #RATIO_GAS};</li>
 *   <li>the liquid gap {@code L}: IF97 Region 1's residual properties of pure liquid water minus the cubic's pure liquid
 *   water, at {@code T} and {@code max(P, p_sat(T))} (Wagner and Pruss): {@code h_R1 - h_ig - h^R}, {@code s_R1 - s_ig
 *   - s^R}, {@code v_R1 - v}, the ideal gas IF97 Region 2's. Up to {@link #FADE_START} exact; from there to the critical
 *   temperature the gap at {@link #FADE_START} continued with its slope there and faded to zero (value and slope
 *   continuous); zero from the critical temperature on. Below the triple point Region 1 is held there and continued
 *   with the held slopes ({@code c_p} for {@code h} and {@code s}, {@code dv/dT} for {@code v}), as the repository's
 *   Region 1 is;</li>
 *   <li>the gas gap {@code G}: {@code h = P (D_IF97 - D)}, {@code v = B_IF97 - B}, {@code s = -P (B'_IF97 - B')} with
 *   the second virial coefficients {@code B_IF97} ({@link SteamVirial}) and the cubic's {@code B = b - a/(R T)},
 *   {@code D = B - T B'}.</li>
 * </ul>
 *
 * <p>Immutable; the per-thread scratch ({@link Workspace}) keeps the liquid gap of the last {@code (T, P)} and the gas
 * gap of the last {@code T}. Every transcendental through {@link DeterministicMath}; nothing allocates per call.</p>
 */
final class WaterShare {
    /** The declared revision of this rule: bumped when a constant or a form here changes. */
    static final String REVISION = "water-share-m2-r1";
    private static final double R = PengRobinsonKernel.GAS_CONSTANT;
    private static final double RS = 461.526;
    private static final double MOLAR = R / RS;
    private static final double TC = 647.096, PC = 22.064e6;
    /** Region 1's upper boundary, where the liquid gap starts to fade. */
    static final double FADE_START = 623.15;
    private static final double TRIPLE = 273.16;
    /** Region 1's upper pressure, Pa: the gap continues linearly in P past it. */
    private static final double REGION_1_MAXIMUM_PRESSURE = 100.0e6;
    static final double RATIO_DENSE = 2.6, RATIO_GAS = 3.6;
    private static final double SQRT_TWO = Math.sqrt(2.0);

    // IF97 Region 1, in (pi - 7.1): odd-I coefficients with reversed signs (as science.fluid.thermo.WaterRegion1).
    private static final int[] I = {0,0,0,0,0,0,0,0,1,1,1,1,1,1,2,2,2,2,2,3,3,3,4,4,4,5,8,8,21,23,29,30,31,32};
    private static final int[] J = {-2,-1,0,1,2,3,4,5,-9,-7,-1,0,1,3,-3,0,1,3,17,-4,0,6,-5,-2,10,-8,-11,-6,-29,-31,-38,-39,-40,-41};
    private static final double[] N = {
            .14632971213167,-.84548187169114,-3.756360367204,3.3855169168385,-.95791963387872,
            .15772038513228,-.016616417199501,.00081214629983568,-.00028319080123804,
            .00060706301565874,.018990068218419,.032529748770505,.021841717175414,.00005283835796993,
            -.00047184321073267,-.00030001780793026,.000047661393906987,-4.4141845330846e-6,
            -7.2694996297594e-16,.000031679644845054,2.8270797985312e-6,8.5205128120103e-10,
            -.0000022425281908,-6.5171222895601e-7,-1.4341729937924e-13,4.0516996860117e-7,
            -1.2734301741641e-9,-1.7424871230634e-10,6.8762131295531e-19,-1.4478307828521e-20,
            -2.6335781662795e-23,-1.1947622640071e-23,-1.8228094581404e-24,-9.3537087292458e-26};
    private static final double P_STAR = 16.53e6, T_STAR = 1386.0;
    private static final int HIGHEST_X = 32, LOWEST_Y = -43, HIGHEST_Y = 17;
    // IF97 Region 2's ideal-gas part.
    private static final int[] J0 = {0, 1, -5, -4, -3, -2, -1, 2, 3};
    private static final double[] N0 = {-0.96927686500217e1, 0.10086655968018e2, -0.56087911283020e-2, 0.71452738081455e-1,
            -0.40710498223928, 0.14240819171444e1, -0.43839511319450e1, -0.28408632460772, 0.21268463753307e-1};
    // Wagner and Pruss (IAPWS 1992) saturation line.
    private static final double[] WAGNER = {-7.85951783, 1.84408259, -11.7866497, 22.6807411, -15.9618719, 1.80122502};

    /** Indices into the output of {@link #phase}. */
    static final int H = 0, S = 1, V = 2, H_T = 3, H_P = 4, S_T = 5, S_P = 6, V_T = 7, V_P = 8, SIZE = 9;

    private final PengRobinsonKernel water;
    private final double coVolume;

    /** @param mathiasCopeman the cubic water's alpha coefficients, or null for PR78's kappa */
    WaterShare(double criticalTemperature, double criticalPressure, double acentricFactor, double[] mathiasCopeman) {
        water = PengRobinsonKernel.huronVidal(new double[] {criticalTemperature}, new double[] {criticalPressure},
                new double[] {acentricFactor}, HuronVidalExcess.builder(1).build(), new double[][] {mathiasCopeman},
                Double.MIN_VALUE, Double.MAX_VALUE, Double.MIN_VALUE, Double.MAX_VALUE);
        coVolume = water.coVolume(0);
    }

    Workspace newWorkspace() { return new Workspace(this); }

    /** Per-thread scratch: the pure water's kernel storage, Region 1's power tables and the last gaps. */
    static final class Workspace {
        private final WaterShare owner;
        private final PengRobinsonKernel.Workspace mixture;
        private final PengRobinsonKernel.Evaluation evaluation;
        private final double[] pure = {1.0}, rootDt = new double[1], rootDt2 = new double[1];
        private final double[] xp = new double[HIGHEST_X + 1], yp = new double[HIGHEST_Y - LOWEST_Y + 1];
        private double powersTemperature = Double.NaN, kernelTemperature = Double.NaN;
        // Region 1 (molar): v, h, s, cp, vT, vP, vTT, vTP, dh/dP, ds/dP; the cubic's pure liquid: v, hR, sR, cpR, vT, vP,
        // vTT, vTP.
        private final double[] region = new double[10], cubic = new double[8];
        /** The gap at one (T, P): h, s, v, their T and P derivatives, and the mixed ones (h, s, v). */
        private final double[] gap = new double[12];
        /** The liquid gap L of the last (T, P): h, s, v, hT, hP, sT, sP, vT, vP. */
        private final double[] liquid = new double[9];
        private double liquidT = Double.NaN, liquidP = Double.NaN;
        /** The gas gap of the last T: B, B', B''. */
        private final double[] gas = new double[3];
        private double gasT = Double.NaN;

        private Workspace(WaterShare owner) {
            this.owner = owner;
            mixture = owner.water.newWorkspace();
            evaluation = owner.water.newEvaluation();
        }
    }

    /**
     * The share per mole of a phase into {@code out} ({@link #SIZE} entries, indexed by {@link #H} and the rest): the phase
     * at {@code (t, p)} with water fraction {@code xw}, untranslated molar volume {@code v}, co-volume {@code b} and the
     * fixed-composition {@code dv/dT}, {@code dv/dP}. All zeros for {@code xw = 0}.
     */
    void phase(double t, double p, double xw, double v, double b, double dvdt, double dvdp, Workspace w, double[] out) {
        if (w.owner != this) throw new IllegalArgumentException("Workspace belongs to another water share");
        java.util.Arrays.fill(out, 0.0);
        if (!(xw > 0.0)) return;
        double u = (v / b - RATIO_DENSE) / (RATIO_GAS - RATIO_DENSE);
        double weight = 1.0 - smooth(u);
        double weightSlope = -smoothSlope(u) / ((RATIO_GAS - RATIO_DENSE) * b);
        double weightT = weightSlope * dvdt, weightP = weightSlope * dvdp;
        if (weight > 0.0 || weightSlope != 0.0) {
            double[] l = liquidGap(t, p, w);
            out[H] += xw * weight * l[0];
            out[S] += xw * weight * l[1];
            out[V] += xw * weight * l[2];
            out[H_T] += xw * (weightT * l[0] + weight * l[3]);
            out[H_P] += xw * (weightP * l[0] + weight * l[4]);
            out[S_T] += xw * (weightT * l[1] + weight * l[5]);
            out[S_P] += xw * (weightP * l[1] + weight * l[6]);
            out[V_T] += xw * (weightT * l[2] + weight * l[7]);
            out[V_P] += xw * (weightP * l[2] + weight * l[8]);
        }
        if (weight < 1.0 || weightSlope != 0.0) {
            double[] g = gasGap(t, w);
            double gapB = g[0], gapB1 = g[1], gapB2 = g[2];
            double d = gapB - t * gapB1, d1 = -t * gapB2;
            double gasWeight = 1.0 - weight, x2 = xw * xw;
            out[H] += x2 * gasWeight * p * d;
            out[S] += -x2 * gasWeight * p * gapB1;
            out[V] += x2 * gasWeight * gapB;
            out[H_T] += x2 * (-weightT * p * d + gasWeight * p * d1);
            out[H_P] += x2 * (-weightP * p * d + gasWeight * d);
            out[S_T] += x2 * (weightT * p * gapB1 - gasWeight * p * gapB2);
            out[S_P] += x2 * (weightP * p * gapB1 - gasWeight * gapB1);
            out[V_T] += x2 * (-weightT * gapB + gasWeight * gapB1);
            out[V_P] += x2 * (-weightP * gapB);
        }
    }

    /**
     * The liquid gap {@code L} of pure water at {@code (t, p)}, molar: {@code h, s, v} and their T and P derivatives (the
     * workspace's buffer, kept for the next call at the same state).
     */
    double[] liquidGap(double t, double p, Workspace w) {
        double[] l = w.liquid;
        if (t == w.liquidT && p == w.liquidP) return l;
        java.util.Arrays.fill(l, 0.0);
        if (t < TC) {
            boolean fading = t > FADE_START;
            double at = fading ? FADE_START : t;
            double saturation = saturationPressure(at);
            boolean above = p >= saturation;
            double[] g = gapAt(at, above ? p : saturation, w);
            double slope = above ? 0.0 : saturationSlope(at, saturation);
            // Along the saturation line below it: the state moves with p_sat(T), and not with P.
            double hT = g[3] + g[4] * slope, sT = g[5] + g[6] * slope, vT = g[7] + g[8] * slope;
            double hP = above ? g[4] : 0.0, sP = above ? g[6] : 0.0, vP = above ? g[8] : 0.0;
            if (!fading) {
                l[0] = g[0]; l[1] = g[1]; l[2] = g[2];
                l[3] = hT; l[4] = hP; l[5] = sT; l[6] = sP; l[7] = vT; l[8] = vP;
            } else {
                double span = TC - FADE_START, d = t - FADE_START, q = d / span;
                double fade = 1.0 - smooth(q), fadeSlope = -smoothSlope(q) / span;
                double hTP = above ? g[9] : 0.0, sTP = above ? g[10] : 0.0, vTP = above ? g[11] : 0.0;
                double h = g[0] + hT * d, s = g[1] + sT * d, v = g[2] + vT * d;
                l[0] = h * fade; l[1] = s * fade; l[2] = v * fade;
                l[3] = hT * fade + h * fadeSlope;
                l[5] = sT * fade + s * fadeSlope;
                l[7] = vT * fade + v * fadeSlope;
                l[4] = (hP + hTP * d) * fade;
                l[6] = (sP + sTP * d) * fade;
                l[8] = (vP + vTP * d) * fade;
            }
        }
        w.liquidT = t;
        w.liquidP = p;
        return l;
    }

    /**
     * Region 1 minus the cubic at one state, into the workspace's gap: {@code h, s, v}, then {@code dh/dT, dh/dP, ds/dT,
     * ds/dP, dv/dT, dv/dP}, then {@code d2h/dTdP, d2s/dTdP, d2v/dTdP}.
     */
    private double[] gapAt(double t, double pressure, Workspace w) {
        // Past Region 1's 100 MPa its sums fail (no positive c_p at 623 K and 1 GPa): the gap there continues linearly in
        // P from 100 MPa, with the mixed derivative carrying its T slopes, value and slope continuous.
        double p = Math.min(pressure, REGION_1_MAXIMUM_PRESSURE);
        region1(t, p, w);
        pureCubic(t, p, w);
        double[] r = w.region, c = w.cubic, g = w.gap;
        double tau = 540.0 / t, gt = 0.0, gtt = 0.0, g0 = DeterministicMath.log(p / 1.0e6);
        for (int k = 0; k < J0.length; k++) {
            int j = J0[k];
            g0 += N0[k] * power(tau, j);
            if (j != 0) gt += N0[k] * j * power(tau, j - 1);
            if (j != 0 && j != 1) gtt += N0[k] * j * (j - 1) * power(tau, j - 2);
        }
        double idealH = R * t * tau * gt, idealCp = -R * tau * tau * gtt, idealS = R * (tau * gt - g0);
        g[0] = r[1] - idealH - c[1];
        g[1] = r[2] - idealS - c[2];
        g[2] = r[0] - c[0];
        g[3] = r[3] - idealCp - c[3];
        g[4] = r[8] - (c[0] - t * c[4]);
        g[5] = (r[3] - idealCp - c[3]) / t;
        g[6] = r[9] + c[4];
        g[7] = r[4] - c[4];
        g[8] = r[5] - c[5];
        g[9] = -t * (r[6] - c[6]);
        g[10] = -(r[6] - c[6]);
        g[11] = r[7] - c[7];
        if (pressure > p) {
            double step = pressure - p;
            g[0] += g[4] * step;
            g[1] += g[6] * step;
            g[2] += g[8] * step;
            g[3] += g[9] * step;
            g[5] += g[10] * step;
            g[7] += g[11] * step;
        }
        return g;
    }

    /**
     * Region 1 of IF97, molar, held at the triple point below it (class comment): v, h, s, cp, vT, vP, vTT, vTP and the
     * P derivatives of h and s (of the continued ones below the triple point).
     */
    private static void region1(double temperature, double p, Workspace w) {
        double t = Math.max(temperature, TRIPLE);
        double tau = T_STAR / t;
        if (t != w.powersTemperature) {
            double y = tau - 1.222;
            double[] yp = w.yp;
            yp[-LOWEST_Y] = 1.0;
            for (int k = -LOWEST_Y + 1; k < yp.length; k++) yp[k] = yp[k - 1] * y;
            double inverse = 1.0 / y;
            for (int k = -LOWEST_Y - 1; k >= 0; k--) yp[k] = yp[k + 1] * inverse;
            w.powersTemperature = t;
        }
        double x = p / P_STAR - 7.1;
        double[] xp = w.xp, yp = w.yp;
        xp[0] = 1.0;
        for (int k = 1; k < xp.length; k++) xp[k] = xp[k - 1] * x;
        double g = 0, gp = 0, gpp = 0, gt = 0, gtt = 0, gpt = 0, gptt = 0, gppt = 0;
        for (int k = 0; k < N.length; k++) {
            int i = I[k], jj = J[k], j = jj - LOWEST_Y;
            double n = N[k];
            g += n * xp[i] * yp[j];
            if (i != 0) gp += n * i * xp[i - 1] * yp[j];
            if (i > 1) gpp += n * i * (i - 1) * xp[i - 2] * yp[j];
            if (jj != 0) gt += n * jj * xp[i] * yp[j - 1];
            if (jj != 0 && jj != 1) gtt += n * jj * (jj - 1) * xp[i] * yp[j - 2];
            if (i != 0 && jj != 0) gpt += n * i * jj * xp[i - 1] * yp[j - 1];
            if (i != 0 && jj != 0 && jj != 1) gptt += n * i * jj * (jj - 1) * xp[i - 1] * yp[j - 2];
            if (i > 1 && jj != 0) gppt += n * i * (i - 1) * jj * xp[i - 2] * yp[j - 1];
        }
        double v = RS * t * gp / P_STAR, h = RS * t * tau * gt, s = RS * (tau * gt - g), cp = -RS * tau * tau * gtt;
        double vT = RS / P_STAR * (gp - tau * gpt), vP = RS * t * gpp / (P_STAR * P_STAR);
        double vTT = RS * tau * tau * gptt / (P_STAR * t), vTP = RS / (P_STAR * P_STAR) * (gpp - tau * gppt);
        if (!(v > 0.0) || !(cp > 0.0) || !(vP < 0.0) || !Double.isFinite(h) || !Double.isFinite(s))
            throw new IllegalArgumentException("Invalid Region 1 state of the water share at " + t + " K, " + p + " Pa");
        double[] r = w.region;
        if (temperature < TRIPLE) {
            double step = temperature - TRIPLE, logRatio = DeterministicMath.log(temperature / TRIPLE);
            double cpPressure = -TRIPLE * vTT;
            r[0] = v + vT * step; r[1] = h + cp * step; r[2] = s + cp * logRatio; r[3] = cp;
            r[4] = vT; r[5] = vP + vTP * step; r[6] = 0.0; r[7] = vTP;
            r[8] = v - TRIPLE * vT + cpPressure * step;
            r[9] = -vT + cpPressure * logRatio;
        } else {
            r[0] = v; r[1] = h; r[2] = s; r[3] = cp; r[4] = vT; r[5] = vP; r[6] = vTT; r[7] = vTP;
            r[8] = v - t * vT;
            r[9] = -vT;
        }
        for (int k = 0; k < r.length; k++) r[k] *= MOLAR;
    }

    /** The cubic's pure liquid water at {@code (t, p)}: v, h^R, s^R, c_p^R, vT, vP, vTT, vTP. */
    private void pureCubic(double t, double p, Workspace w) {
        if (t != w.kernelTemperature) {
            water.prepareTemperature(t, w.mixture);
            water.prepareRootDerivatives(t, w.mixture, w.rootDt, w.rootDt2);
            w.kernelTemperature = t;
        }
        water.evaluate(t, p, w.pure, Root.LIQUID, w.mixture, w.evaluation);
        double root = w.mixture.attractionRootsView()[0], r1 = w.rootDt[0], r2 = w.rootDt2[0];
        double a = root * root, a1 = 2.0 * root * r1, a2 = 2.0 * (r1 * r1 + root * r2), b = coVolume;
        double rt = R * t, z = w.evaluation.compressibility(), v = z * rt / p;
        double den = v * v + 2.0 * b * v - b * b;
        double dpdv = -rt / ((v - b) * (v - b)) + 2.0 * a * (v + b) / (den * den);
        double dpdt = R / (v - b) - a1 / den;
        if (!(dpdv < 0.0)) throw new IllegalArgumentException("Mechanically unstable pure-water root of the water share");
        double dpdvv = 2.0 * rt / ((v - b) * (v - b) * (v - b)) + 2.0 * a / (den * den) - 8.0 * a * (v + b) * (v + b) / (den * den * den);
        double dpdtv = -R / ((v - b) * (v - b)) + 2.0 * a1 * (v + b) / (den * den);
        double vT = -dpdt / dpdv, vP = 1.0 / dpdv;
        double vTT = -(-a2 / den + 2.0 * dpdtv * vT + dpdvv * vT * vT) / dpdv;
        double vTP = -(dpdtv + dpdvv * vT) / (dpdv * dpdv);
        double log = DeterministicMath.log((v + (1.0 + SQRT_TWO) * b) / (v + (1.0 - SQRT_TWO) * b));
        double dlogdv = 1.0 / (v + (1.0 + SQRT_TWO) * b) - 1.0 / (v + (1.0 - SQRT_TWO) * b);
        double hR = rt * (z - 1.0) + (t * a1 - a) / (2.0 * SQRT_TWO * b) * log;
        double cpR = p * vT - R + t * a2 / (2.0 * SQRT_TWO * b) * log + (t * a1 - a) / (2.0 * SQRT_TWO * b) * dlogdv * vT;
        double sR = (hR - rt * w.evaluation.logFugacityCoefficient(0)) / t;
        double[] c = w.cubic;
        c[0] = v; c[1] = hR; c[2] = sR; c[3] = cpR; c[4] = vT; c[5] = vP; c[6] = vTT; c[7] = vTP;
    }

    /** {@code B_IF97 - B}, its first and second temperature derivatives, at {@code t} (kept for the next call). */
    private double[] gasGap(double t, Workspace w) {
        double[] g = w.gas;
        if (t == w.gasT) return g;
        if (t != w.kernelTemperature) {
            water.prepareTemperature(t, w.mixture);
            water.prepareRootDerivatives(t, w.mixture, w.rootDt, w.rootDt2);
            w.kernelTemperature = t;
        }
        double root = w.mixture.attractionRootsView()[0], r1 = w.rootDt[0], r2 = w.rootDt2[0];
        double a = root * root, a1 = 2.0 * root * r1, a2 = 2.0 * (r1 * r1 + root * r2), rt = R * t;
        double cubic = coVolume - a / rt;
        double cubic1 = a / (rt * t) - a1 / rt;
        double cubic2 = -2.0 * a / (rt * t * t) + 2.0 * a1 / (rt * t) - a2 / rt;
        double curvature = t < SteamVirial.MINIMUM_TEMPERATURE ? 0.0 : -SteamVirial.residualHeatCapacity(t, 1.0) / t;
        g[0] = SteamVirial.secondVirial(t) - cubic;
        g[1] = SteamVirial.temperatureDerivative(t) - cubic1;
        g[2] = curvature - cubic2;
        w.gasT = t;
        return g;
    }

    /** Wagner and Pruss's saturation pressure, Pa, below the critical temperature. */
    static double saturationPressure(double t) {
        double theta = 1.0 - t / TC, root = Math.sqrt(theta);
        return PC * DeterministicMath.exp(TC / t * wagner(theta, root));
    }

    /** {@code d p_sat/dT} at {@code t}, given {@code p_sat(t)}. */
    private static double saturationSlope(double t, double saturation) {
        double theta = 1.0 - t / TC, root = Math.sqrt(theta);
        double f = wagner(theta, root);
        double t3 = theta * theta * theta;
        double slope = WAGNER[0] + 1.5 * WAGNER[1] * root + 3.0 * WAGNER[2] * theta * theta + 3.5 * WAGNER[3] * theta * theta * root
                + 4.0 * WAGNER[4] * t3 + 7.5 * WAGNER[5] * t3 * t3 * root;
        return saturation * (-TC / (t * t) * f - slope / t);
    }

    private static double wagner(double theta, double root) {
        double t3 = theta * theta * theta;
        return WAGNER[0] * theta + WAGNER[1] * theta * root + WAGNER[2] * t3 + WAGNER[3] * t3 * root
                + WAGNER[4] * t3 * theta + WAGNER[5] * t3 * t3 * theta * root;
    }

    private static double smooth(double u) {
        if (u <= 0.0) return 0.0;
        if (u >= 1.0) return 1.0;
        return u * u * u * (10.0 + u * (6.0 * u - 15.0));
    }

    private static double smoothSlope(double u) {
        if (u <= 0.0 || u >= 1.0) return 0.0;
        double v = u * (1.0 - u);
        return 30.0 * v * v;
    }

    private static double power(double x, int n) {
        double base = n < 0 ? 1.0 / x : x, out = 1.0;
        for (int k = Math.abs(n); k > 0; k--) out *= base;
        return out;
    }
}
