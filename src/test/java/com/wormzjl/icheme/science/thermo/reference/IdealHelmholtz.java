package com.wormzjl.icheme.science.thermo.reference;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Arrays;

/**
 * Ideal-gas part alpha0(tau, delta) of a CoolProp pure-fluid equation of state, read from the {@code alpha0} array
 * of a {@code dev/fluids/*.json} EOS block. The term families and their CoolProp names:
 * <ul>
 *   <li>{@code IdealGasHelmholtzLead}: ln(delta) + a1 + a2 tau;</li>
 *   <li>{@code IdealGasHelmholtzEnthalpyEntropyOffset}: a1 + a2 tau (the reference-state offset baked into the file);</li>
 *   <li>{@code IdealGasHelmholtzLogTau}: a ln(tau);</li>
 *   <li>{@code IdealGasHelmholtzPower}: sum n_i tau^t_i;</li>
 *   <li>{@code IdealGasHelmholtzPlanckEinstein}: sum n_i ln(1 - exp(-t_i tau));</li>
 *   <li>{@code IdealGasHelmholtzPlanckEinsteinFunctionT}: sum n_i ln(1 - exp(-v_i tau / Tcrit)), with the term's
 *       own {@code Tcrit}, as CoolProp's parser converts it;</li>
 *   <li>{@code IdealGasHelmholtzCP0AlyLee} (n-heptane; batch 2026-11-15-supercritical-fluids, S0): the Aly-Lee ideal-gas
 *       heat capacity {@code cp0/R = c0 + c1 [(c2/T)/sinh(c2/T)]^2 + c3 [(c4/T)/cosh(c4/T)]^2}, converted as CoolProp's
 *       parser does: the constant {@code c0} as CoolProp's {@code IdealHelmholtzCP0PolyT} term of exponent 0,
 *       {@code c0 (1 - tau/tau0 + ln(tau/tau0))} with {@code tau0 = Tc/T0} (the term's own {@code Tc} and {@code T0}),
 *       the sinh term as the generalized Planck-Einstein term {@code c1 ln(1 - exp(-2 c2 tau/Tc))} and the cosh term as
 *       {@code -c3 ln(1 + exp(-2 c4 tau/Tc))}, with no integration constants: the file's reference-state offset was
 *       computed on that form. Proved by the n-Heptane parity fixture (h and s at 1e-10).</li>
 * </ul>
 * Both Planck-Einstein forms are held as CoolProp's generalized form n ln(c + d exp(theta tau)) with c = 1, d = -1.
 * Any other term type is refused at load.
 */
final class IdealHelmholtz {
    private double leadA1;
    private double leadA2;
    private boolean hasLead;
    private double logTauA;
    private double[] powerN = new double[0];
    private double[] powerT = new double[0];
    private double[] planckN = new double[0];
    private double[] planckTheta = new double[0];
    private double[] planckC = new double[0];
    private double[] planckD = new double[0];
    /** CoolProp's {@code IdealHelmholtzCP0PolyT} terms of exponent 0 (Aly-Lee's constant): coefficient and tau0. */
    private double[] constantCpC = new double[0];
    private double[] constantCpTau0 = new double[0];

    static IdealHelmholtz parse(JsonArray alpha0) {
        IdealHelmholtz ideal = new IdealHelmholtz();
        for (JsonElement element : alpha0) {
            JsonObject term = element.getAsJsonObject();
            String type = term.get("type").getAsString();
            switch (type) {
                case "IdealGasHelmholtzLead" -> {
                    if (ideal.hasLead) {
                        throw new IllegalArgumentException("more than one IdealGasHelmholtzLead term");
                    }
                    ideal.hasLead = true;
                    ideal.leadA1 += term.get("a1").getAsDouble();
                    ideal.leadA2 += term.get("a2").getAsDouble();
                }
                case "IdealGasHelmholtzEnthalpyEntropyOffset" -> {
                    // Same functional form as the lead term without ln(delta); folded into its constants.
                    ideal.leadA1 += term.get("a1").getAsDouble();
                    ideal.leadA2 += term.get("a2").getAsDouble();
                }
                case "IdealGasHelmholtzLogTau" -> ideal.logTauA += term.get("a").getAsDouble();
                case "IdealGasHelmholtzPower" -> {
                    ideal.powerN = concat(ideal.powerN, JsonFluidFiles.doubles(term, "n"));
                    ideal.powerT = concat(ideal.powerT, JsonFluidFiles.doubles(term, "t"));
                }
                case "IdealGasHelmholtzPlanckEinstein" -> {
                    double[] n = JsonFluidFiles.doubles(term, "n");
                    double[] t = JsonFluidFiles.doubles(term, "t");
                    double[] theta = new double[t.length];
                    for (int i = 0; i < t.length; i++) {
                        theta[i] = -t[i];
                    }
                    ideal.addPlanckEinstein(n, theta);
                }
                case "IdealGasHelmholtzPlanckEinsteinFunctionT" -> {
                    double[] n = JsonFluidFiles.doubles(term, "n");
                    double[] v = JsonFluidFiles.doubles(term, "v");
                    double tcrit = term.get("Tcrit").getAsDouble();
                    double[] theta = new double[v.length];
                    for (int i = 0; i < v.length; i++) {
                        theta[i] = -v[i] / tcrit;
                    }
                    ideal.addPlanckEinstein(n, theta);
                }
                case "IdealGasHelmholtzCP0AlyLee" -> {
                    double[] c = JsonFluidFiles.doubles(term, "c");
                    if (c.length != 5) {
                        throw new IllegalArgumentException("IdealGasHelmholtzCP0AlyLee needs five coefficients");
                    }
                    double tc = term.get("Tc").getAsDouble();
                    double t0 = term.get("T0").getAsDouble();
                    // CoolProp's thresholds for a present term (FluidLibrary's AlyLee conversion).
                    if (Math.abs(c[0]) > 1.0e-14) {
                        ideal.constantCpC = concat(ideal.constantCpC, new double[] {c[0]});
                        ideal.constantCpTau0 = concat(ideal.constantCpTau0, new double[] {tc / t0});
                    }
                    if (Math.abs(c[1]) > 1.0e-14) {
                        ideal.addGeneralized(c[1], -2.0 * c[2] / tc, 1.0, -1.0);
                    }
                    if (Math.abs(c[3]) > 1.0e-14) {
                        ideal.addGeneralized(-c[3], -2.0 * c[4] / tc, 1.0, 1.0);
                    }
                }
                default -> throw new IllegalArgumentException("unsupported alpha0 term type " + type);
            }
        }
        if (!ideal.hasLead) {
            throw new IllegalArgumentException("alpha0 has no IdealGasHelmholtzLead term");
        }
        if (ideal.powerN.length != ideal.powerT.length) {
            throw new IllegalArgumentException("IdealGasHelmholtzPower n and t differ in length");
        }
        return ideal;
    }

    private void addPlanckEinstein(double[] n, double[] theta) {
        if (n.length != theta.length) {
            throw new IllegalArgumentException("Planck-Einstein n and t/v differ in length");
        }
        double[] c = new double[n.length];
        double[] d = new double[n.length];
        Arrays.fill(c, 1.0);
        Arrays.fill(d, -1.0);
        planckN = concat(planckN, n);
        planckTheta = concat(planckTheta, theta);
        planckC = concat(planckC, c);
        planckD = concat(planckD, d);
    }

    private void addGeneralized(double n, double theta, double c, double d) {
        planckN = concat(planckN, new double[] {n});
        planckTheta = concat(planckTheta, new double[] {theta});
        planckC = concat(planckC, new double[] {c});
        planckD = concat(planckD, new double[] {d});
    }

    void evaluate(double tau, double delta, AlphaDerivatives out) {
        double alpha = Math.log(delta) + leadA1 + leadA2 * tau + logTauA * Math.log(tau);
        double dTau = leadA2 + logTauA / tau;
        double dTau2 = -logTauA / (tau * tau);
        for (int i = 0; i < constantCpC.length; i++) {
            double c = constantCpC[i];
            double tau0 = constantCpTau0[i];
            alpha += c - c * tau / tau0 + c * Math.log(tau / tau0);
            dTau += c / tau - c / tau0;
            dTau2 += -c / (tau * tau);
        }
        for (int i = 0; i < powerN.length; i++) {
            double n = powerN[i];
            double t = powerT[i];
            double value = n * Math.pow(tau, t);
            alpha += value;
            dTau += value * t / tau;
            dTau2 += value * t * (t - 1.0) / (tau * tau);
        }
        for (int i = 0; i < planckN.length; i++) {
            double n = planckN[i];
            double theta = planckTheta[i];
            double c = planckC[i];
            double d = planckD[i];
            double e = Math.exp(theta * tau);
            double g = c + d * e;
            alpha += n * Math.log(g);
            dTau += n * d * theta * e / g;
            dTau2 += n * c * d * theta * theta * e / (g * g);
        }
        out.alpha = alpha;
        out.dDelta = 1.0 / delta;
        out.dDelta2 = -1.0 / (delta * delta);
        out.dTau = dTau;
        out.dTau2 = dTau2;
        out.dDeltaDTau = 0.0;
    }

    private static double[] concat(double[] a, double[] b) {
        double[] joined = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, joined, a.length, b.length);
        return joined;
    }
}
