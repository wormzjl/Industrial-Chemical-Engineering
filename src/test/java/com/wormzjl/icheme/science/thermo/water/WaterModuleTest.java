package com.wormzjl.icheme.science.thermo.water;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.WaterRecords;
import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The water module (C1a of batch 2026-11-13-codebase-review) against the formulas it replaced, written out here as they
 * were (not kept copies of the old classes): {@code V3WaterProperties}' Wagner-Pruss line, slope, bisection, Shomate
 * steam, Watson latent heat and datum, {@code WaterRegion1}'s sums and held continuation, the water share's Region 1
 * superset, Region 2 ideal part and its own Wagner association, and the dilute steam viscosity. Bit for bit, except the
 * water share's Wagner association: its stated shift is bounded and printed. The grid covers the triple point, the
 * critical point, below the triple point (the continuations) and past Region 1's 623.15 K and the steam's 900 K.
 */
class WaterModuleTest {
    private static final MaterialCatalog.Water RECORD = MaterialCatalog.bundled().requirePackage(
            com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog.networkPackage(MaterialCatalog.bundled())).water();
    private static final WaterConstants W = WaterRecords.constants(RECORD);

    private static List<Double> temperatures() {
        List<Double> t = new ArrayList<>();
        for (double x : new double[] {150.0, 200.0, 250.0, 273.15, 273.16, 273.17, 298.15, 300.0, 350.0, 373.15, 400.0, 450.0, 500.0,
                550.0, 600.0, 623.14, 623.15, 623.16, 640.0, 647.0, 647.09, 647.096}) t.add(x);
        for (int k = 0; k < 200; k++) t.add(1.0 + 646.0 * k / 199.0);
        return t;
    }

    private static void same(double expected, double actual, String what) {
        assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(actual), what + ": " + expected + " vs " + actual);
    }

    // ---- the old formulas ---------------------------------------------------------------------------------------------

    private static double oldColumnPolynomial(double theta) {
        double root = Math.sqrt(theta), cube = theta * theta * theta;
        List<Double> a = RECORD.saturation();
        return a.get(0) * theta + a.get(1) * (theta * root)
                + a.get(2) * theta * theta * theta + a.get(3) * (cube * root)
                + a.get(4) * theta * theta * theta * theta + a.get(5) * (cube * cube * theta * root);
    }

    private static double oldColumnPressure(double t) {
        if (t == RECORD.criticalTemperature()) return RECORD.criticalPressure();
        double theta = 1.0 - t / RECORD.criticalTemperature();
        return RECORD.criticalPressure() * DeterministicMath.exp(RECORD.criticalTemperature() / t * oldColumnPolynomial(theta));
    }

    private static double oldColumnLogSlope(double t) {
        double theta = 1.0 - t / RECORD.criticalTemperature();
        double root = Math.sqrt(theta), cube = theta * theta * theta;
        List<Double> a = RECORD.saturation();
        double slope = a.get(0) + 1.5 * a.get(1) * Math.sqrt(theta)
                + 3.0 * a.get(2) * theta * theta + 3.5 * a.get(3) * (theta * theta * root)
                + 4.0 * a.get(4) * theta * theta * theta + 7.5 * a.get(5) * (cube * cube * root);
        return -RECORD.criticalTemperature() * oldColumnPolynomial(theta) / (t * t) - slope / t;
    }

    private static final double[] SHARE_WAGNER = {-7.85951783, 1.84408259, -11.7866497, 22.6807411, -15.9618719, 1.80122502};

    private static double oldShareWagner(double theta, double root) {
        double t3 = theta * theta * theta;
        return SHARE_WAGNER[0] * theta + SHARE_WAGNER[1] * theta * root + SHARE_WAGNER[2] * t3 + SHARE_WAGNER[3] * t3 * root
                + SHARE_WAGNER[4] * t3 * theta + SHARE_WAGNER[5] * t3 * t3 * theta * root;
    }

    private static double oldSharePressure(double t) {
        double theta = 1.0 - t / 647.096, root = Math.sqrt(theta);
        return 22.064e6 * DeterministicMath.exp(647.096 / t * oldShareWagner(theta, root));
    }

    private static double oldShareSlope(double t, double saturation) {
        double theta = 1.0 - t / 647.096, root = Math.sqrt(theta);
        double f = oldShareWagner(theta, root);
        double t3 = theta * theta * theta;
        double slope = SHARE_WAGNER[0] + 1.5 * SHARE_WAGNER[1] * root + 3.0 * SHARE_WAGNER[2] * theta * theta
                + 3.5 * SHARE_WAGNER[3] * theta * theta * root + 4.0 * SHARE_WAGNER[4] * t3 + 7.5 * SHARE_WAGNER[5] * t3 * t3 * root;
        return saturation * (-647.096 / (t * t) * f - slope / t);
    }

    private static double oldShomateEnthalpy(double temperature) {
        double t = temperature / 1_000.0;
        List<Double> s = RECORD.shomate();
        double kiloJoulesPerMol = s.get(0) * t + s.get(1) * t * t / 2.0 + s.get(2) * t * t * t / 3.0
                + s.get(3) * t * t * t * t / 4.0 - s.get(4) / t + s.get(5) + s.get(6);
        return 1_000.0 * kiloJoulesPerMol;
    }

    private static double oldShomateHeatCapacity(double temperature) {
        double t = temperature / 1_000.0;
        List<Double> s = RECORD.shomate();
        return s.get(0) + s.get(1) * t + s.get(2) * t * t + s.get(3) * t * t * t + s.get(4) / (t * t);
    }

    private static double oldSteamEnthalpy(double t) {
        double at = Math.max(RECORD.triplePoint(), Math.min(RECORD.maximumTemperature(), t));
        double h = oldShomateEnthalpy(at);
        return at == t ? h : h + oldShomateHeatCapacity(at) * (t - at);
    }

    private static double oldWatson(double t) {
        if (t >= RECORD.criticalTemperature()) return 0.0;
        double reduced = (1.0 - t / RECORD.criticalTemperature()) / (1.0 - RECORD.boilingTemperature() / RECORD.criticalTemperature());
        return RECORD.vaporizationEnthalpy() * DeterministicMath.pow(Math.max(0.0, reduced), RECORD.watsonExponent());
    }

    // IF97 Region 1, as WaterRegion1 and WaterShare both had it.
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
    private static final double RS = 461.526, P_STAR = 16.53e6, T_STAR = 1386;

    /** The old sums: g, gp, gpp, gppp, gt, gtt, gpt, gptt, gppt (fresh power tables each call). */
    private static double[] oldSums(double t, double p) {
        double tau = T_STAR / t, y = tau - 1.222, x = p / P_STAR - 7.1;
        double[] yp = new double[61], xp = new double[33];
        yp[43] = 1;
        for (int k = 44; k < yp.length; k++) yp[k] = yp[k - 1] * y;
        double inverse = 1 / y;
        for (int k = 42; k >= 0; k--) yp[k] = yp[k + 1] * inverse;
        xp[0] = 1;
        for (int k = 1; k < xp.length; k++) xp[k] = xp[k - 1] * x;
        double g = 0, gp = 0, gpp = 0, gppp = 0, gt = 0, gtt = 0, gpt = 0, gptt = 0, gppt = 0;
        for (int k = 0; k < N.length; k++) {
            int i = I[k], jj = J[k], j = jj + 43;
            double n = N[k];
            g += n * xp[i] * yp[j];
            if (i != 0) gp += n * i * xp[i - 1] * yp[j];
            if (i > 1) gpp += n * i * (i - 1) * xp[i - 2] * yp[j];
            if (i > 2) gppp += n * i * (i - 1) * (i - 2) * xp[i - 3] * yp[j];
            if (jj != 0) gt += n * jj * xp[i] * yp[j - 1];
            if (jj != 0 && jj != 1) gtt += n * jj * (jj - 1) * xp[i] * yp[j - 2];
            if (i != 0 && jj != 0) gpt += n * i * jj * xp[i - 1] * yp[j - 1];
            if (i != 0 && jj != 0 && jj != 1) gptt += n * i * jj * (jj - 1) * xp[i - 1] * yp[j - 2];
            if (i > 1 && jj != 0) gppt += n * i * (i - 1) * jj * xp[i - 2] * yp[j - 1];
        }
        return new double[] {g, gp, gpp, gppp, gt, gtt, gpt, gptt, gppt, tau};
    }

    // ---- the tests ----------------------------------------------------------------------------------------------------

    @Test void theSaturationLineIsTheColumnsBitForBitAndTheSharesWithinItsStatedShift() {
        int compared = 0;
        // The share's association moves ln p_sat by a few ulps of (T_c/T) sum a_i theta^e_i, so p_sat relatively by
        // about T_c/T times 1e-15: last digits, largest in the supercooled continuation.
        double worstPressure = 0.0, worstSlope = 0.0, worstAboveTriple = 0.0;
        for (double t : temperatures()) {
            same(oldColumnPressure(t), WagnerPruss.pressure(W, t), "p_sat at " + t);
            if (t < RECORD.criticalTemperature()) same(oldColumnLogSlope(t), WagnerPruss.logSlope(W, t), "d ln p_sat/dT at " + t);
            if (t <= Iapws97Region1.MAXIMUM_TEMPERATURE) {
                double old = oldSharePressure(t), now = WagnerPruss.pressure(W, t), scale = Math.max(1.0, 647.096 / t);
                double shift = old == 0.0 ? 0.0 : Math.abs(now - old) / old;
                worstPressure = Math.max(worstPressure, shift / scale);
                if (t >= 273.16) worstAboveTriple = Math.max(worstAboveTriple, shift);
                double oldSlope = oldShareSlope(t, old), nowSlope = now * WagnerPruss.logSlope(W, t);
                if (oldSlope != 0.0) worstSlope = Math.max(worstSlope, Math.abs(nowSlope - oldSlope) / Math.abs(oldSlope) / scale);
            }
            compared++;
        }
        assertTrue(worstPressure <= 1.0e-14, "the share's p_sat moves by at most 1e-14 max(1, T_c/T) relative: " + worstPressure);
        assertTrue(worstSlope <= 1.0e-13, "the share's dp_sat/dT moves by at most 1e-13 max(1, T_c/T) relative: " + worstSlope);
        System.out.println(String.format(Locale.ROOT, "C1a Wagner-Pruss: %d temperatures bit for bit; the share's stated shift: p_sat %.2e"
                        + " relative at and above the triple point, %.2e and dp_sat/dT %.2e relative per max(1, T_c/T) on the whole grid",
                compared, worstAboveTriple, worstPressure, worstSlope));
        for (double p : new double[] {700.0, 1.0e4, 1.01325e5, 1.0e6, 1.0e7, 2.2e7}) {
            double low = RECORD.triplePoint(), high = RECORD.criticalTemperature();
            for (int k = 0; k < 80; k++) {
                double middle = 0.5 * (low + high);
                if (oldColumnPressure(middle) < p) low = middle; else high = middle;
            }
            same(0.5 * (low + high), WagnerPruss.temperature(W, p), "T_sat at " + p);
        }
    }

    @Test void theSteamTheLatentHeatAndTheDatumAreTheColumnsBitForBit() {
        for (double t : temperatures()) {
            same(oldSteamEnthalpy(t), SteamIdealGas.enthalpy(W, t), "h_ig at " + t);
            double at = Math.max(RECORD.triplePoint(), Math.min(RECORD.maximumTemperature(), t));
            same(oldShomateHeatCapacity(at), SteamIdealGas.heatCapacity(W, t), "cp_ig at " + t);
            same(oldWatson(t), WaterDatum.watsonLatentHeat(W, t), "Watson at " + t);
        }
        for (double t : new double[] {950.0, 1200.0}) same(oldSteamEnthalpy(t), SteamIdealGas.enthalpy(W, t), "h_ig past the data at " + t);
        // The datum: steam plus the virial residual at p_sat, less the record's latent heat and Region 1 there.
        double saturation = oldColumnPressure(298.15);
        double[] sums = oldSums(298.15, saturation);
        double h = RS * 298.15 * sums[9] * sums[4];
        double offset = oldSteamEnthalpy(298.15) + SteamVirial.residualEnthalpy(298.15, saturation) - RECORD.datumVaporizationEnthalpy()
                - h * RECORD.molarMass();
        same(offset, WaterDatum.liquidEnthalpyOffset(W), "liquid enthalpy offset");
    }

    @Test void regionOneIsBothFormerEvaluatorsBitForBit() {
        var state = new Iapws97Region1.State();
        var liquid = new Iapws97Region1.Liquid();
        int compared = 0;
        for (double t : temperatures()) {
            for (double p : new double[] {611.657, 1.0e4, 1.01325e5, 1.0e6, 1.0e7, 5.0e7, 1.0e8, 2.0e8}) {
                // The water share's superset at the temperature held at the triple point.
                double held = Math.max(t, RECORD.triplePoint());
                double[] s = oldSums(held, p);
                double tau = s[9];
                Iapws97Region1.evaluate(held, p, state);
                same(RS * held * s[1] / P_STAR, state.volume(), "v");
                same(RS * held * tau * s[4], state.enthalpy(), "h");
                same(RS * (tau * s[4] - s[0]), state.entropy(), "s");
                same(-RS * tau * tau * s[5], state.heatCapacity(), "cp");
                same(RS / P_STAR * (s[1] - tau * s[6]), state.volumeTemperatureDerivative(), "vT");
                same(RS * held * s[2] / (P_STAR * P_STAR), state.volumePressureDerivative(), "vP");
                same(RS * tau * tau * s[7] / (P_STAR * held), state.volumeSecondTemperatureDerivative(), "vTT");
                same(RS / (P_STAR * P_STAR) * (s[2] - tau * s[8]), state.volumeTemperaturePressureDerivative(), "vTP");
                same(RS * held * s[3] / (P_STAR * P_STAR * P_STAR), state.volumeSecondPressureDerivative(), "vPP");
                // WaterRegion1's liquid: held inside [triple, 623.15 K], continued with the held slopes.
                double hold = t < RECORD.triplePoint() ? RECORD.triplePoint() : Math.min(t, 623.15);
                double[] r = oldSums(hold, p);
                double rt = r[9];
                double v = RS * hold * r[1] / P_STAR, hh = RS * hold * rt * r[4], cp = -RS * rt * rt * r[5];
                double vt = RS / P_STAR * (r[1] - rt * r[6]), vp = RS * hold * r[2] / (P_STAR * P_STAR);
                boolean valid = v > 0 && cp > 0 && vp < 0 && Double.isFinite(hh);
                double cv = v, ch = hh;
                if (hold != t) {
                    cv += vt * (t - hold);
                    ch += cp * (t - hold);
                }
                if (!valid || !(cv > 0)) continue;
                Iapws97Region1.liquid(RECORD.triplePoint(), t, p, liquid);
                same(cv, liquid.specificVolume(), "liquid v at " + t + " K " + p + " Pa");
                same(ch, liquid.specificEnthalpy(), "liquid h");
                same(ch - p * cv, liquid.specificInternalEnergy(), "liquid u");
                same(cp, liquid.specificHeatCapacity(), "liquid cp");
                same(vt, liquid.volumeTemperatureDerivative(), "liquid vT");
                same(vp, liquid.volumePressureDerivative(), "liquid vP");
                same(v - hold * vt, liquid.enthalpyPressureDerivative(), "liquid dh/dp");
                same(RS * rt * rt * r[7] / (P_STAR * hold), liquid.volumeSecondTemperatureDerivative(), "liquid vTT");
                compared++;
            }
        }
        assertTrue(compared > 1000, "compared " + compared);
    }

    @Test void regionTwosIdealPartAndTheViscosityAreTheFormerOnesBitForBit() {
        int[] j0 = {0, 1, -5, -4, -3, -2, -1, 2, 3};
        double[] n0 = {-0.96927686500217e1, 0.10086655968018e2, -0.56087911283020e-2, 0.71452738081455e-1,
                -0.40710498223928, 0.14240819171444e1, -0.43839511319450e1, -0.28408632460772, 0.21268463753307e-1};
        double r = PengRobinsonKernel.GAS_CONSTANT;
        double[] out = new double[Iapws97Region2Ideal.SIZE];
        for (double t : temperatures()) {
            for (double p : new double[] {611.657, 1.0e5, 1.0e7}) {
                double tau = 540.0 / t, gt = 0.0, gtt = 0.0, g0 = DeterministicMath.log(p / 1.0e6);
                for (int k = 0; k < j0.length; k++) {
                    int j = j0[k];
                    g0 += n0[k] * power(tau, j);
                    if (j != 0) gt += n0[k] * j * power(tau, j - 1);
                    if (j != 0 && j != 1) gtt += n0[k] * j * (j - 1) * power(tau, j - 2);
                }
                Iapws97Region2Ideal.molar(t, p, out);
                same(r * t * tau * gt, out[Iapws97Region2Ideal.H], "ideal h");
                same(-r * tau * tau * gtt, out[Iapws97Region2Ideal.CP], "ideal cp");
                same(r * (tau * gt - g0), out[Iapws97Region2Ideal.S], "ideal s");
            }
            double reduced = t / 647.096;
            double formula = 1e-4*Math.sqrt(reduced)/(1.67752+2.20462/reduced+.6366564/(reduced*reduced)-.241605/(reduced*reduced*reduced));
            if (t >= 273.16) same(formula, DiluteSteamViscosity.viscosity(t), "viscosity at " + t);
        }
        double low = 273.16 / 647.096;
        double atLow = 1e-4*Math.sqrt(low)/(1.67752+2.20462/low+.6366564/(low*low)-.241605/(low*low*low));
        double denominator = 1.67752+2.20462/low+.6366564/(low*low)-.241605/(low*low*low);
        double exponent = .5+(2.20462/low+2*.6366564/(low*low)-3*.241605/(low*low*low))/denominator;
        same(atLow * DeterministicMath.pow(200.0 / 273.16, exponent), DiluteSteamViscosity.viscosity(200.0), "viscosity continued at 200 K");
    }

    private static double power(double x, int n) {
        double base = n < 0 ? 1.0 / x : x, out = 1.0;
        for (int k = Math.abs(n); k > 0; k--) out *= base;
        return out;
    }
}
