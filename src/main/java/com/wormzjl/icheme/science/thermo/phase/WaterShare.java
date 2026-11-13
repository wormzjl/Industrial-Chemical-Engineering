package com.wormzjl.icheme.science.thermo.phase;

import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.thermo.HuronVidalExcess;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.water.Iapws97Region1;
import com.wormzjl.icheme.science.thermo.water.Iapws97Region2Ideal;
import com.wormzjl.icheme.science.thermo.water.SteamVirial;
import com.wormzjl.icheme.science.thermo.water.WagnerPruss;
import com.wormzjl.icheme.science.thermo.water.WaterConstants;

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
 *   water, at {@code T} and {@code P} (Wagner and Pruss): {@code h_R1 - h_ig - h^R}, {@code s_R1 - s_ig - s^R},
 *   {@code v_R1 - v}, the ideal gas IF97 Region 2's; below {@code p_sat(T)} continued linearly in {@code P} from
 *   {@code p_sat}, value and slope continuous (held at {@code p_sat}, dh/dP was 9 % off at 350 K and 10 kPa). Up to {@link #FADE_START} exact; from there to the critical
 *   temperature the gap at {@link #FADE_START} continued with its slope there and faded to zero (value and slope
 *   continuous); zero from the critical temperature on. Below the triple point Region 1 is held there and continued
 *   with the held slopes ({@code c_p} for {@code h} and {@code s}, {@code dv/dT} for {@code v}), as the repository's
 *   Region 1 is;</li>
 *   <li>the gas gap {@code G}: {@code h = P (D_IF97 - D)}, {@code v = B_IF97 - B}, {@code s = -P (B'_IF97 - B')} with
 *   the second virial coefficients {@code B_IF97} ({@link SteamVirial}) and the cubic's {@code B = b - a/(R T)},
 *   {@code D = B - T B'}.</li>
 * </ul>
 *
 * <p>IF97 Region 1, Region 2's ideal part and the Wagner-Pruss line are the water module's ({@code science.thermo.water},
 * X39 of batch 2026-11-13-codebase-review), on the record's numbers ({@link WaterConstants}); the share keeps the gaps,
 * the weight and the fades.</p>
 *
 * <p>Immutable; the per-thread scratch ({@link Workspace}) keeps the liquid gap of the last {@code (T, P)} and the gas
 * gap of the last {@code T}. Every transcendental through {@link DeterministicMath}; nothing allocates per call.</p>
 */
public final class WaterShare {
    /** The declared revision of this rule: bumped when a constant or a form here changes. */
    public static final String REVISION = "water-share-m2-r1";
    private static final double R = PengRobinsonKernel.GAS_CONSTANT;
    private static final double RS = Iapws97Region1.GAS_CONSTANT;
    private static final double MOLAR = R / RS;
    /** Region 1's upper boundary, where the liquid gap starts to fade. */
    static final double FADE_START = Iapws97Region1.MAXIMUM_TEMPERATURE;
    /** Region 1's upper pressure, Pa: the gap continues linearly in P past it. */
    private static final double REGION_1_MAXIMUM_PRESSURE = Iapws97Region1.MAXIMUM_PRESSURE;
    static final double RATIO_DENSE = 2.6, RATIO_GAS = 3.6;
    private static final double SQRT_TWO = Math.sqrt(2.0);

    /** Indices into the output of {@link #phase}. */
    public static final int H = 0, S = 1, V = 2, H_T = 3, H_P = 4, S_T = 5, S_P = 6, V_T = 7, V_P = 8, SIZE = 9;

    private final PengRobinsonKernel water;
    private final double coVolume;
    /** The water record's numbers: the critical point the liquid gap ends at, the triple point Region 1 holds at, the saturation line. */
    private final WaterConstants record;
    private final double criticalTemperature, triplePoint;

    /**
     * @param record the water record's numbers (Region 1, the saturation line, the critical and triple points)
     * @param cubicCriticalTemperature the cubic water's critical temperature (its derivation's), K
     * @param cubicCriticalPressure the cubic water's critical pressure, Pa
     * @param acentricFactor the cubic water's acentric factor
     * @param mathiasCopeman the cubic water's alpha coefficients, or null for PR78's kappa
     */
    public WaterShare(WaterConstants record, double cubicCriticalTemperature, double cubicCriticalPressure, double acentricFactor,
                      double[] mathiasCopeman) {
        this.record = record;
        this.criticalTemperature = record.criticalTemperature();
        this.triplePoint = record.triplePoint();
        water = PengRobinsonKernel.huronVidal(new double[] {cubicCriticalTemperature}, new double[] {cubicCriticalPressure},
                new double[] {acentricFactor}, HuronVidalExcess.builder(1).build(), new double[][] {mathiasCopeman},
                Double.MIN_VALUE, Double.MAX_VALUE, Double.MIN_VALUE, Double.MAX_VALUE);
        coVolume = water.coVolume(0);
    }

    public Workspace newWorkspace() { return new Workspace(this); }

    /** Per-thread scratch: the pure water's kernel storage, Region 1's state and power tables and the last gaps. */
    public static final class Workspace {
        private final WaterShare owner;
        private final PengRobinsonKernel.Workspace mixture;
        private final double[] rootDt = new double[1], rootDt2 = new double[1];
        private final Iapws97Region1.State region1 = new Iapws97Region1.State();
        private final double[] ideal = new double[Iapws97Region2Ideal.SIZE];
        private double kernelTemperature = Double.NaN;
        // Region 1 (molar): v, h, s, cp, vT, vP, vTT, vTP, dh/dP, ds/dP, vPP; the cubic's pure liquid: v, hR, sR, cpR, vT,
        // vP, vTT, vTP, vPP.
        private final double[] region = new double[11], cubic = new double[9];
        /** The gap at one (T, P): h, s, v, their T and P derivatives, the mixed ones (h, s, v) and the second P ones (h, s, v). */
        private final double[] gap = new double[15];
        /** The liquid gap L of the last (T, P): h, s, v, hT, hP, sT, sP, vT, vP. */
        private final double[] liquid = new double[9];
        private double liquidT = Double.NaN, liquidP = Double.NaN;
        /** The gas gap of the last T: B, B', B''. */
        private final double[] gas = new double[3];
        private double gasT = Double.NaN;

        private Workspace(WaterShare owner) {
            this.owner = owner;
            mixture = owner.water.newWorkspace();
        }
    }

    /**
     * The share per mole of a phase into {@code out} ({@link #SIZE} entries, indexed by {@link #H} and the rest): the phase
     * at {@code (t, p)} with water fraction {@code xw}, untranslated molar volume {@code v}, co-volume {@code b} and the
     * fixed-composition {@code dv/dT}, {@code dv/dP}. All zeros for {@code xw = 0}.
     */
    public void phase(double t, double p, double xw, double v, double b, double dvdt, double dvdp, Workspace w, double[] out) {
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
        if (t < criticalTemperature) {
            boolean fading = t > FADE_START;
            double at = fading ? FADE_START : t;
            double saturation = WagnerPruss.pressure(record, at);
            boolean above = p >= saturation;
            double[] g = gapAt(at, above ? p : saturation, w);
            // Below p_sat: continued linearly in P from the gap at p_sat, g(T, ps) + g_P (p - ps), whose T derivative at
            // fixed p is g_T + (g_TP + g_PP ps') (p - ps) (the move of ps along the line cancels in value).
            double below = above ? 0.0 : p - saturation, slope = above ? 0.0 : saturation * WagnerPruss.logSlope(record, at);
            double hTP = g[9] + g[12] * slope, sTP = g[10] + g[13] * slope, vTP = g[11] + g[14] * slope;
            double h0 = g[0] + g[4] * below, s0 = g[1] + g[6] * below, v0 = g[2] + g[8] * below;
            double hT = g[3] + hTP * below, sT = g[5] + sTP * below, vT = g[7] + vTP * below;
            double hP = g[4], sP = g[6], vP = g[8];
            if (!fading) {
                l[0] = h0; l[1] = s0; l[2] = v0;
                l[3] = hT; l[4] = hP; l[5] = sT; l[6] = sP; l[7] = vT; l[8] = vP;
            } else {
                double span = criticalTemperature - FADE_START, d = t - FADE_START, q = d / span;
                double fade = 1.0 - smooth(q), fadeSlope = -smoothSlope(q) / span;
                double h = h0 + hT * d, s = s0 + sT * d, v = v0 + vT * d;
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
        double[] ideal = w.ideal;
        Iapws97Region2Ideal.molar(t, p, ideal);
        double idealH = ideal[Iapws97Region2Ideal.H], idealCp = ideal[Iapws97Region2Ideal.CP], idealS = ideal[Iapws97Region2Ideal.S];
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
        g[12] = (r[5] - t * r[7]) - (c[5] - t * c[7]);
        g[13] = -(r[7] - c[7]);
        g[14] = r[10] - c[8];
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
    private void region1(double temperature, double p, Workspace w) {
        double t = Math.max(temperature, triplePoint);
        Iapws97Region1.State state = w.region1;
        Iapws97Region1.evaluate(t, p, state);
        double v = state.volume(), h = state.enthalpy(), s = state.entropy(), cp = state.heatCapacity();
        double vT = state.volumeTemperatureDerivative(), vP = state.volumePressureDerivative();
        double vTT = state.volumeSecondTemperatureDerivative(), vTP = state.volumeTemperaturePressureDerivative();
        double vPP = state.volumeSecondPressureDerivative();
        if (!(v > 0.0) || !(cp > 0.0) || !(vP < 0.0) || !Double.isFinite(h) || !Double.isFinite(s))
            throw new IllegalArgumentException("Invalid Region 1 state of the water share at " + t + " K, " + p + " Pa");
        double[] r = w.region;
        if (temperature < triplePoint) {
            double step = temperature - triplePoint, logRatio = DeterministicMath.log(temperature / triplePoint);
            double cpPressure = -triplePoint * vTT;
            r[0] = v + vT * step; r[1] = h + cp * step; r[2] = s + cp * logRatio; r[3] = cp;
            r[4] = vT; r[5] = vP + vTP * step; r[6] = 0.0; r[7] = vTP;
            r[8] = v - triplePoint * vT + cpPressure * step;
            r[9] = -vT + cpPressure * logRatio;
            r[10] = vPP;
        } else {
            r[0] = v; r[1] = h; r[2] = s; r[3] = cp; r[4] = vT; r[5] = vP; r[6] = vTT; r[7] = vTP;
            r[8] = v - t * vT;
            r[9] = -vT;
            r[10] = vPP;
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
        // One compound: the cubic's liquid root and ln phi in closed form on the prepared temperature, the same doubles
        // as the kernel's one-component evaluation (x = 1, a_mix = s s, b_mix = b, no excess cells), with no mixture work.
        if (!Double.isFinite(p) || !(p > 0.0)) throw new IllegalArgumentException("Invalid pressure of the water share");
        double root = w.mixture.attractionRootsView()[0], r1 = w.rootDt[0], r2 = w.rootDt2[0];
        double a = root * root, a1 = 2.0 * root * r1, a2 = 2.0 * (r1 * r1 + root * r2), b = coVolume;
        double reducedA = a * p / (R * R * t * t), reducedB = b * p / (R * t);
        double z = PengRobinsonKernel.selectCompressibility(reducedA, reducedB, Root.LIQUID);
        double logPhi = (z - 1.0) - DeterministicMath.log(z - reducedB) - reducedA / (2.0 * SQRT_TWO * Math.max(reducedB, 1.0e-300))
                * DeterministicMath.log((z + (1.0 + SQRT_TWO) * reducedB) / (z + (1.0 - SQRT_TWO) * reducedB));
        double rt = R * t, v = z * rt / p;
        double den = PengRobinsonKernel.cubicDenominator(v, b);
        // The kernel's volumetric block: -rt is (-R) t and 2 rt is (2 R) t exactly, so these are the same doubles.
        double dpdv = PengRobinsonKernel.pressureVolumeDerivative(t, v, a, b);
        double dpdt = PengRobinsonKernel.pressureTemperatureDerivative(v, b, a1);
        if (!(dpdv < 0.0)) throw new IllegalArgumentException("Mechanically unstable pure-water root of the water share");
        double dpdvv = PengRobinsonKernel.pressureSecondVolumeDerivative(t, v, a, b);
        double dpdtv = PengRobinsonKernel.pressureTemperatureVolumeDerivative(v, b, a1);
        double vT = -dpdt / dpdv, vP = 1.0 / dpdv;
        double vTT = -(-a2 / den + 2.0 * dpdtv * vT + dpdvv * vT * vT) / dpdv;
        double vTP = -(dpdtv + dpdvv * vT) / (dpdv * dpdv);
        double vPP = -dpdvv / (dpdv * dpdv * dpdv);
        double log = DeterministicMath.log((v + (1.0 + SQRT_TWO) * b) / (v + (1.0 - SQRT_TWO) * b));
        double dlogdv = 1.0 / (v + (1.0 + SQRT_TWO) * b) - 1.0 / (v + (1.0 - SQRT_TWO) * b);
        double hR = rt * (z - 1.0) + (t * a1 - a) / (2.0 * SQRT_TWO * b) * log;
        double cpR = p * vT - R + t * a2 / (2.0 * SQRT_TWO * b) * log + (t * a1 - a) / (2.0 * SQRT_TWO * b) * dlogdv * vT;
        double sR = (hR - rt * logPhi) / t;
        double[] c = w.cubic;
        c[0] = v; c[1] = hR; c[2] = sR; c[3] = cpR; c[4] = vT; c[5] = vP; c[6] = vTT; c[7] = vTP; c[8] = vPP;
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
}
