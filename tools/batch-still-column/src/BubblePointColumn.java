package com.wormzjl.icheme.science.column.batch;

import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.column.v3.thermo.V3ThermoWorkspace;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;

/**
 * The candidate simpler column for the batch still (batch 2026-11-09-batch-distiller, plan section 7, owner B3): the
 * bubble-point method (Wang and Henke) on the PR K values V3 uses. Stages: 0 the total condenser, 1..N the trays, N + 1
 * the reboiler. Per iteration one tridiagonal component balance per component on fixed K values (Thomas algorithm), then
 * each stage's temperature by its bubble point. With {@code energyBalance} the stage flows come from the energy balances
 * of the top envelopes on the stage enthalpies (Wang and Henke's own flow update) and D from the reboiler's; without it,
 * constant molar overflow: the boil-up V is the duty over the reboiler's latent heat, V on every stage, D = V / (R + 1),
 * L = R D above the feed, L + F below it. The energy-balance form starts on constant molar overflow and switches to its
 * own flows once no stage moves {@value #SWITCH_KELVIN} K in an iteration: on a wide-boiling crude its first profiles carry
 * the feed's sensible heat past the duty (no positive distillate). A reboiler held at the charge ({@code feedStage} 0) is
 * the batch still's pot.
 */
final class BubblePointColumn {
    private final V3PengRobinsonThermo thermo;
    private final V3ThermoWorkspace workspace;
    private final int n;
    private final double[] tc, pc, omega;
    private final boolean energyBalance;
    static final double SWITCH_KELVIN = 1;
    /** PR fugacity evaluations made, for the comparison's counters. */
    long evaluations;

    BubblePointColumn(V3PengRobinsonThermo thermo) {
        this(thermo, false);
    }

    BubblePointColumn(V3PengRobinsonThermo thermo, boolean energyBalance) {
        this.thermo = thermo;
        this.energyBalance = energyBalance;
        this.workspace = thermo.newWorkspace();
        this.n = thermo.componentBasis().componentCount();
        tc = new double[n];
        pc = new double[n];
        omega = new double[n];
        for (int i = 0; i < n; i++) {
            tc[i] = thermo.componentCriticalTemperatureKelvin(i);
            pc[i] = thermo.componentCriticalPressurePascal(i);
            omega[i] = thermo.componentAcentricFactor(i);
        }
    }

    /**
     * @param stages    trays N between the condenser and the reboiler
     * @param feedStage the stage the saturated-liquid feed enters, 1..N a tray, N + 1 the reboiler; 0 for none, the
     *                  reboiler held at {@code z}
     * @param pressure  every stage's pressure, Pa
     * @param reflux    reflux ratio L / D
     * @param duty      reboiler duty, W
     * @param feed      feed rate, mol/s (0 with {@code feedStage} 0)
     * @param z         the feed's, or the held reboiler's, composition
     */
    record Spec(int stages, int feedStage, double pressure, double reflux, double duty, double feed, double[] z) {
    }

    record Result(boolean converged, int iterations, double[][] x, double[] temperatures, double boilUp, double distillate, String failure) {
        double[] distillateComposition() {
            return x[0];
        }

        double reboilerTemperature() {
            return temperatures[temperatures.length - 1];
        }
    }

    private record Bubble(double t, double[] y, double[] k) {
    }

    private record Eval(double f, double[] y, double[] k) {
    }

    Result solve(Spec s, double[] warm, int maxIterations, double tolerance) {
        int m = s.stages() + 2, r = m - 1;
        double[] z = normalized(s.z());
        double[] t = new double[m];
        double[][] x = new double[m][], y = new double[m][], k = new double[m][];
        var start = bubble(z, s.pressure(), Double.NaN, null);
        if (start == null) return failed(m, "no bubble point of the charge");
        for (int j = 0; j < m; j++) {
            boolean seeded = warm != null && warm.length == m;
            x[j] = z.clone();
            var b = seeded ? bubbleAt(z, warm[j], s.pressure(), start.y) : start;
            if (b == null) b = start;
            t[j] = seeded ? warm[j] : start.t;
            y[j] = b.y;
            k[j] = b.k;
        }
        double v = 0, d = 0;
        double[] a = new double[m], b = new double[m], c = new double[m], rhs = new double[m], solution = new double[m];
        // vapour[j]: vapour leaving stage j (1..N + 1); liquid[j]: liquid leaving stage j (0..N), liquid[0] the reflux.
        double[] vapour = new double[m], liquid = new double[m];
        double feedEnthalpy = energyBalance && s.feedStage() > 0 ? liquidEnthalpy(start.t, z, s.pressure()) : 0;
        boolean energyNow = false;
        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            if (energyNow) {
                d = energyFlows(s, t, x, y, feedEnthalpy, vapour, liquid);
                if (!(d > 0)) return new Result(false, iteration, x, t, v, d, "no positive distillate from the energy balance");
                for (int j = 0; j < m; j++)
                    if ((j > 0 && !(vapour[j] > 0)) || (j < r && !(liquid[j] > 0)))
                        return new Result(false, iteration, x, t, v, d, "no positive flow on stage " + j);
                v = vapour[r];
            } else {
                double lambda = latent(t[r], x[r], y[r], s.pressure());
                if (!(lambda > 0)) return new Result(false, iteration, x, t, v, d, "no latent heat at the reboiler");
                v = s.duty() / lambda;
                d = v / (s.reflux() + 1);
                double l = s.reflux() * d;
                for (int j = 0; j < m; j++) {
                    vapour[j] = v;
                    liquid[j] = s.feedStage() > 0 && j >= s.feedStage() ? l + s.feed() : l;
                }
            }
            double bottoms = s.feed() - d;
            if (s.feedStage() > 0 && !(bottoms > 0)) return new Result(false, iteration, x, t, v, d, "the distillate exceeds the feed");
            double[][] raw = new double[m][n];
            for (int i = 0; i < n; i++) {
                b[0] = -(liquid[0] + d);
                c[0] = vapour[1] * k[1][i];
                rhs[0] = 0;
                a[0] = 0;
                for (int j = 1; j <= s.stages(); j++) {
                    a[j] = liquid[j - 1];
                    b[j] = -(liquid[j] + vapour[j] * k[j][i]);
                    c[j] = vapour[j + 1] * k[j + 1][i];
                    rhs[j] = j == s.feedStage() ? -s.feed() * z[i] : 0;
                }
                if (s.feedStage() > 0) {
                    a[r] = liquid[s.stages()];
                    b[r] = -(bottoms + vapour[r] * k[r][i]);
                    rhs[r] = s.feedStage() == r ? -s.feed() * z[i] : 0;
                } else {
                    a[r] = 0;
                    b[r] = 1;
                    rhs[r] = z[i];
                }
                c[r] = 0;
                thomas(a, b, c, rhs, solution);
                for (int j = 0; j < m; j++) raw[j][i] = Math.max(0, solution[j]);
            }
            double change = 0;
            for (int j = 0; j < m; j++) {
                x[j] = normalized(raw[j]);
                var bubble = bubble(x[j], s.pressure(), t[j], y[j]);
                if (bubble == null) return new Result(false, iteration, x, t, v, d, "no bubble point on stage " + j);
                change = Math.max(change, Math.abs(bubble.t - t[j]));
                t[j] = bubble.t;
                y[j] = bubble.y;
                k[j] = bubble.k;
            }
            if (change < tolerance && energyNow == energyBalance) return new Result(true, iteration, x, t, v, d, null);
            if (energyBalance && change < SWITCH_KELVIN) energyNow = true;
        }
        return new Result(false, maxIterations, x, t, v, d, "no convergence in " + maxIterations);
    }

    /** A liquid's bubble point at {@code pressure}, from {@code guess} K when finite, else from the Wilson estimate; null if none. */
    double bubbleTemperature(double[] x, double pressure, double guess) {
        var b = bubble(normalized(x), pressure, guess, null);
        return b == null ? Double.NaN : b.t;
    }

    /** The equilibrium vapour of a liquid at its bubble point, or null. */
    double[] bubbleVapour(double[] x, double pressure) {
        var b = bubble(normalized(x), pressure, Double.NaN, null);
        return b == null ? null : b.y;
    }

    /** The latent heat of a liquid against its equilibrium vapour at its bubble point, J/mol. */
    double latentHeat(double[] x, double pressure) {
        var z = normalized(x);
        var b = bubble(z, pressure, Double.NaN, null);
        return b == null ? Double.NaN : latent(b.t, z, b.y, pressure);
    }

    /**
     * The flows from the energy balances on the current stage states; returns D. Around the condenser and stages 1..j,
     * with Q_C = (R + 1) D (H_1 - h_0) and F_j the feed entered at or above j:
     * V_(j+1) (H_(j+1) - h_j) = D (h_0 - h_j) + Q_C + F_j (h_j - h_F), L_j = V_(j+1) + F_j - D, both linear in D. The
     * reboiler balance, Q_R = V_r (H_r - h_r) + L_N (h_r - h_N) + F_r (h_r - h_F) with L_N = V_r + F_N - D, fixes D;
     * F_N is the feed on a tray, F_r the feed into the reboiler.
     */
    private double energyFlows(Spec s, double[] t, double[][] x, double[][] y, double feedEnthalpy, double[] vapour, double[] liquid) {
        int m = t.length, r = m - 1, stages = s.stages();
        double p = s.pressure();
        double[] h = new double[m], hv = new double[m];
        for (int j = 0; j < m; j++) {
            h[j] = liquidEnthalpy(t[j], x[j], p);
            if (j > 0) hv[j] = vapourEnthalpy(t[j], y[j], p);
            if (!Double.isFinite(h[j]) || (j > 0 && !Double.isFinite(hv[j]))) return Double.NaN;
        }
        double condenser = (s.reflux() + 1) * (hv[1] - h[0]);
        // V_(j+1) = alpha[j] D + beta[j] for j = 0..N.
        double[] alpha = new double[m], beta = new double[m];
        alpha[0] = s.reflux() + 1;
        for (int j = 1; j <= stages; j++) {
            double fed = s.feedStage() > 0 && j >= s.feedStage() ? s.feed() : 0;
            double rise = hv[j + 1] - h[j];
            alpha[j] = (h[0] - h[j] + condenser) / rise;
            beta[j] = fed * (h[j] - feedEnthalpy) / rise;
        }
        double onTray = s.feedStage() > 0 && s.feedStage() <= stages ? s.feed() : 0, intoReboiler = s.feedStage() == r ? s.feed() : 0;
        double rise = hv[r] - h[stages], drop = h[r] - h[stages];
        double d = (s.duty() - beta[stages] * rise - onTray * drop - intoReboiler * (h[r] - feedEnthalpy)) / (alpha[stages] * rise - drop);
        for (int j = 0; j <= stages; j++) {
            double above = s.feedStage() > 0 && j >= s.feedStage() ? s.feed() : 0;
            vapour[j + 1] = alpha[j] * d + beta[j];
            liquid[j] = j == 0 ? s.reflux() * d : vapour[j + 1] + above - d;
        }
        return d;
    }

    private double liquidEnthalpy(double t, double[] x, double p) {
        try {
            evaluations++;
            return thermo.fugacity(t, p, x, Root.LIQUID, workspace).molarEnthalpyJoulesPerMol();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private double vapourEnthalpy(double t, double[] y, double p) {
        try {
            evaluations++;
            return thermo.fugacity(t, p, y, Root.VAPOR, workspace).molarEnthalpyJoulesPerMol();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private double latent(double t, double[] x, double[] y, double p) {
        try {
            evaluations += 2;
            return thermo.fugacity(t, p, y, Root.VAPOR, workspace).molarEnthalpyJoulesPerMol()
                    - thermo.fugacity(t, p, x, Root.LIQUID, workspace).molarEnthalpyJoulesPerMol();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    private Bubble bubbleAt(double[] x, double t, double p, double[] y) {
        var e = eval(x, t, p, y);
        return e == null ? null : new Bubble(t, e.y, e.k);
    }

    private Bubble bubble(double[] x, double p, double guess, double[] yGuess) {
        double t0 = Double.isFinite(guess) ? guess : wilsonBubble(x, p);
        if (!Double.isFinite(t0)) return null;
        var e0 = eval(x, t0, p, yGuess != null ? yGuess : wilsonVapour(x, t0, p));
        if (e0 == null) return null;
        if (Math.abs(e0.f) < 1e-11) return new Bubble(t0, e0.y, e0.k);
        double t1 = t0 + (e0.f > 0 ? -1 : 1);
        var e1 = eval(x, t1, p, e0.y);
        if (e1 == null) return null;
        for (int step = 0; step < 60; step++) {
            double slope = (e1.f - e0.f) / (t1 - t0);
            double t2 = Math.abs(slope) > 1e-14 ? t1 - e1.f / slope : t1 + (e1.f > 0 ? -5 : 5);
            if (!Double.isFinite(t2)) t2 = t1 + (e1.f > 0 ? -5 : 5);
            t2 = Math.clamp(t2, t1 - 40, t1 + 40);
            t2 = Math.clamp(t2, 80, 1400);
            var e2 = eval(x, t2, p, e1.y);
            for (int half = 0; e2 == null && half < 8; half++) {
                t2 = (t1 + t2) / 2;
                e2 = eval(x, t2, p, e1.y);
            }
            if (e2 == null) return null;
            t0 = t1;
            e0 = e1;
            t1 = t2;
            e1 = e2;
            if (Math.abs(e1.f) < 1e-10 || Math.abs(t1 - t0) < 1e-7) return new Bubble(t1, e1.y, e1.k);
        }
        return null;
    }

    /** ln Σ K x at {@code t}, the vapour taken to its fixed point from {@code yStart}; null where PR has no answer. */
    private Eval eval(double[] x, double t, double p, double[] yStart) {
        try {
            evaluations++;
            double[] liquid = thermo.fugacity(t, p, x, Root.LIQUID, workspace).logFugacityCoefficients().clone();
            double[] y = yStart.clone(), k = new double[n];
            double sum = 1;
            for (int pass = 0; pass < 8; pass++) {
                evaluations++;
                double[] vapour = thermo.fugacity(t, p, y, Root.VAPOR, workspace).logFugacityCoefficients();
                sum = 0;
                for (int i = 0; i < n; i++) {
                    k[i] = Math.exp(liquid[i] - vapour[i]);
                    sum += k[i] * x[i];
                }
                if (!(sum > 0) || !Double.isFinite(sum)) return null;
                double moved = 0;
                double[] next = new double[n];
                for (int i = 0; i < n; i++) {
                    next[i] = k[i] * x[i] / sum;
                    moved = Math.max(moved, Math.abs(next[i] - y[i]));
                }
                y = next;
                if (moved < 1e-10) break;
            }
            return new Eval(Math.log(sum), y, k);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private double wilson(int i, double t, double p) {
        return pc[i] / p * Math.exp(5.373 * (1 + omega[i]) * (1 - tc[i] / t));
    }

    private double wilsonBubble(double[] x, double p) {
        double low = 60, high = 1400;
        for (int step = 0; step < 80; step++) {
            double mid = (low + high) / 2, sum = 0;
            for (int i = 0; i < n; i++) sum += wilson(i, mid, p) * x[i];
            if (sum > 1) high = mid;
            else low = mid;
        }
        return (low + high) / 2;
    }

    private double[] wilsonVapour(double[] x, double t, double p) {
        double[] y = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) sum += y[i] = wilson(i, t, p) * x[i];
        for (int i = 0; i < n; i++) y[i] /= sum;
        return y;
    }

    static double[] normalized(double[] v) {
        double sum = 0;
        for (double e : v) sum += e;
        double[] out = new double[v.length];
        for (int i = 0; i < v.length; i++) out[i] = v[i] / sum;
        return out;
    }

    /** Solves the tridiagonal system a x(j-1) + b x(j) + c x(j+1) = d into {@code out}. */
    private static void thomas(double[] a, double[] b, double[] c, double[] d, double[] out) {
        int m = b.length;
        double[] cp = new double[m], dp = new double[m];
        cp[0] = c[0] / b[0];
        dp[0] = d[0] / b[0];
        for (int j = 1; j < m; j++) {
            double den = b[j] - a[j] * cp[j - 1];
            cp[j] = j < m - 1 ? c[j] / den : 0;
            dp[j] = (d[j] - a[j] * dp[j - 1]) / den;
        }
        out[m - 1] = dp[m - 1];
        for (int j = m - 2; j >= 0; j--) out[j] = dp[j] - cp[j] * out[j + 1];
    }

    private Result failed(int m, String why) {
        return new Result(false, 0, new double[m][n], new double[m], 0, 0, why);
    }
}
