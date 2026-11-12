package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.HydrocarbonModel;
import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.ThermoComponent;
import java.util.List;

/**
 * The batch still's column (batch 2026-11-09-batch-distiller, BATCH_DISTILLER_PLAN.md section 7, owner B3): the
 * bubble-point method (Wang and Henke) with its energy-balance flow update, in pot mode, on the network's own phase
 * evaluator ({@link HydrocarbonModel}, any package the network takes, {@code icheme:refinery} included). Minecraft-free.
 *
 * <p>Stages: 0 the total condenser, 1..N the trays, N + 1 the pot. The column is quasi-steady (its holdup is small next to
 * the pot's): at each moment a steady rectifier with the pot's liquid held at its composition, the pot's vapour in at the
 * bottom, the liquid back to the pot, the reflux ratio R = L0 / D from the valve. Per iteration one tridiagonal balance per
 * component on fixed K values (Thomas), then each stage's temperature at its bubble point. The flows start on constant
 * molar overflow (L = R D, V = (R + 1) D) until no stage moves {@value #SWITCH_KELVIN} K in an iteration, then come from the
 * energy balances of the top envelopes on the stage enthalpies (Wang and Henke's own update): with D = 1,
 * V(j+1) = ((R + 1)(H1 - h0) + h0 - hj) / (H(j+1) - hj) and Lj = V(j+1) - 1. The start is that measured form, not a mode:
 * every converged answer stands on the energy balance.</p>
 *
 * <p>In pot mode the compositions and temperatures do not depend on the boil-up: every flow is proportional to D. So the
 * solve and its rates are per mole of distillate; the pot's energy balance ({@link BatchStill}) sets how many moles a step
 * distils. The energy that leaves the pot and the column per mole of distillate is the top envelope's, e = (R + 1) H1 - R h0:
 * H1 the head vapour's enthalpy (its composition the distillate's, at the top tray's temperature), h0 the condensate's at its
 * bubble point. With no column (N = 0) or no reflux the still is one equilibrium stage: the distillate is the pot's bubble
 * vapour, e its enthalpy. No heat per mole is taken as e less the pot liquid's enthalpy: that is a difference of two
 * mixtures, the light vapour and the heavy pot liquid, and on a wide-boiling charge it falls through zero while the pot
 * still distils (batch 2026-11-12-still-heat-per-distillate).</p>
 *
 * <p>A closed valve (R infinite, total reflux) solves at {@link #CLOSED_REFLUX} for its profile and sends no distillate, so
 * its rates are per mole of top vapour; that vapour's heat is H1 - h0, the head vapour against its own condensate.
 * Numerical guards only: the bubble search's box and a phase with no equation-of-state root, both a refused trial. One
 * instance belongs to one thread at a time; it keeps no state between solves (a warm start passes its {@link Profile}).</p>
 */
public final class StillColumn {
    /** The flows switch from constant molar overflow to the energy balance once no stage moves this much in an iteration, K. */
    public static final double SWITCH_KELVIN = 1;
    /** Converged once no stage moves this much in an energy-balance iteration, K. */
    public static final double TOLERANCE = 1e-4;
    public static final int MAX_ITERATIONS = 300;
    /** The most trays. */
    public static final int MAX_STAGES = 8;
    /** The reflux ratio a closed valve's profile is solved at. */
    public static final double CLOSED_REFLUX = 1e4;
    /** The bubble search's numerical box, K. */
    static final double LOWEST = 60, HIGHEST = 1400;

    private final HydrocarbonModel model;
    private final double pressure;
    private final int n;
    private final double[] tc, pc, omega, molarMass;

    /** The column on {@code model} at {@code pressure} Pa on every stage. */
    public StillColumn(HydrocarbonModel model, double pressure) {
        if (!(pressure > 0) || !Double.isFinite(pressure)) throw new IllegalArgumentException("Invalid still pressure");
        this.model = model;
        this.pressure = pressure;
        n = model.componentCount();
        tc = new double[n];
        pc = new double[n];
        omega = new double[n];
        molarMass = new double[n];
        var components = components(model);
        for (int i = 0; i < n; i++) {
            var c = components.get(i);
            tc[i] = c.criticalTemperatureKelvin();
            pc[i] = c.criticalPressurePascal();
            omega[i] = c.acentricFactor();
            molarMass[i] = model.molecularWeight(i);
        }
    }

    private static List<ThermoComponent> components(HydrocarbonModel model) {
        if (model.spine().isPresent()) return model.spine().get().components();
        return model.propertyPackage().properties().stream().map(p -> new ThermoComponent(p.component(), p.pr().criticalTemperature(),
                p.pr().criticalPressure(), p.pr().acentricFactor(), p.molecularWeight())).toList();
    }

    /** The critical temperature of component {@code i} of the column's basis, K. */
    public double criticalTemperature(int i) {
        return tc[i];
    }

    public HydrocarbonModel model() {
        return model;
    }

    public double pressure() {
        return pressure;
    }

    public int componentCount() {
        return n;
    }

    /** A warm start: each stage's temperature and liquid, condenser first, pot last. */
    public record Profile(double[] temperatures, double[][] liquids) {
        public int stages() {
            return temperatures.length - 2;
        }

        public double potTemperature() {
            return temperatures[temperatures.length - 1];
        }
    }

    /**
     * The column's answer. Its rates are per mole of distillate (D = 1), or with a closed valve, which distils nothing, per
     * mole of top vapour (V1 = 1); times the moles a second of that basis they are mol/s. Enthalpies in J/mol, temperatures
     * in K, compositions in mole fractions.
     *
     * @param distillate            the distillate's composition (the head vapour's and the condensate's)
     * @param headTemperature       the vapour at the head, the top tray's temperature (the pot's bubble point with no column)
     * @param condensateTemperature the condensate's bubble point (the total condenser's outlet)
     * @param potTemperature        the pot's bubble point
     * @param distillateRate        D: 1, or 0 with a closed valve
     * @param vapourRate            the vapour from the pot, of {@code vapour}, at {@code vapourEnthalpy}
     * @param liquidRate            the liquid back to the pot, of {@code liquid}, at {@code liquidEnthalpy}
     * @param topVapourRate         the vapour into the condenser, (R + 1) D, of the distillate's composition (1 with a closed valve)
     * @param headEnthalpy          H1, the head vapour's
     * @param condensateEnthalpy    h0, the condensate's at its bubble point
     * @param potEnthalpy           the pot liquid's at its bubble point
     * @param energyPerDistillate   the energy that leaves the pot and the column per mole of distillate, (R + 1) H1 - R h0
     *                              (the vapour's H for one stage; infinite with a closed valve)
     * @param refluxRatio           the reflux ratio solved (0 for one stage, {@link #CLOSED_REFLUX} for a closed valve)
     */
    public record Split(boolean converged, int iterations, String failure, int stages, double refluxRatio, boolean closed,
                        double[] distillate, double headTemperature, double condensateTemperature, double potTemperature,
                        double distillateRate, double vapourRate, double[] vapour, double vapourEnthalpy,
                        double liquidRate, double[] liquid, double liquidEnthalpy,
                        double topVapourRate, double headEnthalpy, double condensateEnthalpy, double potEnthalpy,
                        double energyPerDistillate, Profile profile) {
        /** The condenser's duty per mole of the rates' basis, J/mol: all of the top vapour condensed to its bubble point. */
        public double condenserDuty() {
            return topVapourRate * (headEnthalpy - condensateEnthalpy);
        }

        /** Per component, per mole of the rates' basis: the vapour up from the pot, the liquid back, the distillate; up = back + distillate exactly. */
        public double[] vapourFlows() {
            double[] up = new double[vapour.length];
            for (int i = 0; i < up.length; i++) up[i] = Math.max(vapourRate * vapour[i], distillateRate * distillate[i]);
            return up;
        }

        public double[] liquidFlows() {
            double[] up = vapourFlows(), back = new double[up.length];
            for (int i = 0; i < up.length; i++) back[i] = up[i] - distillateRate * distillate[i];
            return back;
        }

        public double[] distillateFlows() {
            double[] out = new double[distillate.length];
            for (int i = 0; i < out.length; i++) out[i] = distillateRate * distillate[i];
            return out;
        }
    }

    /** A liquid at its bubble point: the temperature, the equilibrium vapour and the K values. */
    public record Bubble(double temperature, double[] vapour, double[] k) {
    }

    private record Eval(double f, double[] y, double[] k) {
    }

    /**
     * The column for pot liquid {@code pot} (any amounts) under {@code stages} trays (0: none) at reflux ratio
     * {@code reflux} (0: no reflux, the trays dry; {@link Double#POSITIVE_INFINITY}: a closed valve), warm from {@code warm}
     * (null for a cold start, or a profile of another stage count).
     */
    public Split solve(double[] pot, int stages, double reflux, Profile warm) {
        if (stages < 0 || stages > MAX_STAGES) throw new IllegalArgumentException("Still column stages must be 0 to " + MAX_STAGES);
        if (!(reflux >= 0)) throw new IllegalArgumentException("Invalid still reflux");
        double[] z = fractions(pot);
        if (stages == 0 || reflux == 0) return single(z, warm);
        boolean closed = reflux > CLOSED_REFLUX;
        double ratio = Math.min(reflux, CLOSED_REFLUX);
        int m = stages + 2, r = m - 1;
        boolean seeded = warm != null && warm.temperatures().length == m && warm.liquids().length == m;
        var start = bubble(z, seeded ? warm.potTemperature() : Double.NaN);
        if (start == null) return failed(stages, ratio, closed, "no bubble point of the pot liquid");
        double[] t = new double[m];
        double[][] x = new double[m][], y = new double[m][], k = new double[m][];
        for (int j = 0; j < m; j++) {
            Bubble b = null;
            if (seeded && j < r && warm.liquids()[j].length == n && sum(warm.liquids()[j]) > 0) {
                x[j] = fractions(warm.liquids()[j]);
                var e = eval(x[j], warm.temperatures()[j], start.vapour());
                if (e != null) b = new Bubble(warm.temperatures()[j], e.y(), e.k());
            }
            if (b == null) {
                x[j] = z.clone();
                b = start;
            }
            t[j] = b.temperature();
            y[j] = b.vapour();
            k[j] = b.k();
        }
        x[r] = z;
        t[r] = start.temperature();
        y[r] = start.vapour();
        k[r] = start.k();
        double[] a = new double[m], b = new double[m], c = new double[m], rhs = new double[m], solution = new double[m];
        // vapour[j]: leaving stage j (1..r); liquid[j]: leaving stage j (0..N), liquid[0] the reflux; per mole of distillate.
        double[] vapour = new double[m], liquid = new double[m];
        boolean energy = false;
        for (int iteration = 1; iteration <= MAX_ITERATIONS; iteration++) {
            if (energy) {
                if (!energyFlows(ratio, t, x, y, vapour, liquid)) return failed(stages, ratio, closed, "no enthalpy on a stage");
                for (int j = 0; j < m; j++)
                    if ((j > 0 && !(vapour[j] > 0)) || (j < r && !(liquid[j] > 0)))
                        return failed(stages, ratio, closed, "no positive flow on stage " + j);
            } else {
                for (int j = 0; j < m; j++) {
                    vapour[j] = ratio + 1;
                    liquid[j] = ratio;
                }
            }
            double[][] raw = new double[r][n];
            for (int i = 0; i < n; i++) {
                a[0] = 0;
                b[0] = -(liquid[0] + 1);
                c[0] = vapour[1] * k[1][i];
                rhs[0] = 0;
                for (int j = 1; j < r; j++) {
                    a[j] = liquid[j - 1];
                    b[j] = -(liquid[j] + vapour[j] * k[j][i]);
                    c[j] = vapour[j + 1] * k[j + 1][i];
                    rhs[j] = 0;
                }
                a[r] = 0;
                b[r] = 1;
                c[r] = 0;
                rhs[r] = z[i];
                thomas(a, b, c, rhs, solution);
                for (int j = 0; j < r; j++) raw[j][i] = Math.max(0, solution[j]);
            }
            double change = 0;
            for (int j = 0; j < r; j++) {
                double sum = sum(raw[j]);
                if (!(sum > 0) || !Double.isFinite(sum)) return failed(stages, ratio, closed, "no liquid on stage " + j);
                x[j] = fractions(raw[j]);
                var bubble = bubble(x[j], t[j], y[j]);
                if (bubble == null) return failed(stages, ratio, closed, "no bubble point on stage " + j);
                change = Math.max(change, Math.abs(bubble.temperature() - t[j]));
                t[j] = bubble.temperature();
                y[j] = bubble.vapour();
                k[j] = bubble.k();
            }
            if (energy && change < TOLERANCE) return answer(stages, ratio, closed, iteration, t, x, y);
            if (change < SWITCH_KELVIN) energy = true;
        }
        return failed(stages, ratio, closed, "no convergence in " + MAX_ITERATIONS + " iterations");
    }

    /** The converged column's answer: its flows from the energy balance on the final profile, per mole of its basis. */
    private Split answer(int stages, double ratio, boolean closed, int iterations, double[] t, double[][] x, double[][] y) {
        int m = t.length, r = m - 1;
        double[] vapour = new double[m], liquid = new double[m];
        if (!energyFlows(ratio, t, x, y, vapour, liquid)) return failed(stages, ratio, closed, "no enthalpy on a stage");
        double h0, headVapour, hPot, hBack, hUp;
        try {
            h0 = liquidEnthalpy(t[0], x[0]);
            headVapour = vapourEnthalpy(t[1], y[1]);
            hPot = liquidEnthalpy(t[r], x[r]);
            hBack = liquidEnthalpy(t[r - 1], x[r - 1]);
            hUp = vapourEnthalpy(t[r], y[r]);
        } catch (RuntimeException absent) {
            return failed(stages, ratio, closed, "no enthalpy on a stage");
        }
        // The flows are per mole of distillate (D = 1); a closed valve's per mole of top vapour (V1 = 1).
        double d = closed ? 1 / vapour[1] : 1;
        var profile = new Profile(t.clone(), copy(x));
        // The head vapour leaves tray 1 at its dew point; a total condenser gives its composition, x0, to the distillate.
        return new Split(true, iterations, null, stages, ratio, closed, x[0].clone(), t[1], t[0], t[r], closed ? 0 : d,
                vapour[r] * d, y[r].clone(), hUp, closed ? vapour[r] * d : liquid[r - 1] * d, closed ? y[r].clone() : x[r - 1].clone(),
                hBack, vapour[1] * d, headVapour, h0, hPot, closed ? Double.POSITIVE_INFINITY : (ratio + 1) * headVapour - ratio * h0, profile);
    }

    /** One equilibrium stage: the pot's bubble vapour is the distillate, one mole of vapour a mole, no liquid back. */
    private Split single(double[] z, Profile warm) {
        var pot = bubble(z, warm != null ? warm.potTemperature() : Double.NaN);
        if (pot == null) return failed(0, 0, false, "no bubble point of the pot liquid");
        double[] y = pot.vapour();
        var condensate = bubble(y, pot.temperature() - 1);
        if (condensate == null) return failed(0, 0, false, "no bubble point of the condensate");
        double hPot, hVapour, h0;
        try {
            hPot = liquidEnthalpy(pot.temperature(), z);
            hVapour = vapourEnthalpy(pot.temperature(), y);
            h0 = liquidEnthalpy(condensate.temperature(), y);
        } catch (RuntimeException absent) {
            return failed(0, 0, false, "no enthalpy of the pot or its vapour");
        }
        var profile = new Profile(new double[]{condensate.temperature(), pot.temperature()}, new double[][]{y.clone(), z.clone()});
        return new Split(true, 0, null, 0, 0, false, y.clone(), pot.temperature(), condensate.temperature(), pot.temperature(), 1, 1, y.clone(),
                hVapour, 0, z.clone(), hPot, 1, hVapour, h0, hPot, hVapour, profile);
    }

    /**
     * The flows per mole of distillate from the energy balances on the stage states (Wang and Henke): around the condenser
     * and stages 1..j, V(j+1) (H(j+1) - hj) = (R + 1)(H1 - h0) + h0 - hj, and Lj = V(j+1) - 1; L0 = R. False where a stage
     * has no enthalpy.
     */
    private boolean energyFlows(double ratio, double[] t, double[][] x, double[][] y, double[] vapour, double[] liquid) {
        int m = t.length, r = m - 1;
        double[] h = new double[m], hv = new double[m];
        try {
            for (int j = 0; j < m; j++) {
                h[j] = liquidEnthalpy(t[j], x[j]);
                if (j > 0) hv[j] = vapourEnthalpy(t[j], y[j]);
            }
        } catch (RuntimeException absent) {
            return false;
        }
        double condenser = (ratio + 1) * (hv[1] - h[0]);
        vapour[1] = ratio + 1;
        liquid[0] = ratio;
        for (int j = 1; j < r; j++) {
            vapour[j + 1] = (h[0] - h[j] + condenser) / (hv[j + 1] - h[j]);
            liquid[j] = vapour[j + 1] - 1;
        }
        for (int j = 0; j < m; j++) if (!Double.isFinite(vapour[j]) || !Double.isFinite(liquid[j])) return false;
        return true;
    }

    /** The bubble point of liquid {@code x} (any amounts) from {@code guess} K when finite, else from Wilson's estimate; null if none. */
    public Bubble bubble(double[] x, double guess) {
        return bubble(fractions(x), guess, null);
    }

    private Bubble bubble(double[] x, double guess, double[] yGuess) {
        double t0 = Double.isFinite(guess) ? guess : wilsonBubble(x);
        if (!Double.isFinite(t0)) return null;
        var e0 = eval(x, t0, yGuess != null ? yGuess : wilsonVapour(x, t0));
        if (e0 == null) return null;
        if (Math.abs(e0.f()) < 1e-11) return new Bubble(t0, e0.y(), e0.k());
        double t1 = t0 + (e0.f() > 0 ? -1 : 1);
        var e1 = eval(x, t1, e0.y());
        if (e1 == null) return null;
        for (int step = 0; step < 60; step++) {
            double slope = (e1.f() - e0.f()) / (t1 - t0);
            double t2 = Math.abs(slope) > 1e-14 ? t1 - e1.f() / slope : t1 + (e1.f() > 0 ? -5 : 5);
            if (!Double.isFinite(t2)) t2 = t1 + (e1.f() > 0 ? -5 : 5);
            t2 = Math.clamp(t2, t1 - 40, t1 + 40);
            t2 = Math.clamp(t2, LOWEST, HIGHEST);
            var e2 = eval(x, t2, e1.y());
            for (int half = 0; e2 == null && half < 8; half++) {
                t2 = (t1 + t2) / 2;
                e2 = eval(x, t2, e1.y());
            }
            if (e2 == null) return null;
            t0 = t1;
            e0 = e1;
            t1 = t2;
            e1 = e2;
            if (Math.abs(e1.f()) < 1e-10 || Math.abs(t1 - t0) < 1e-7) return new Bubble(t1, e1.y(), e1.k());
        }
        return null;
    }

    /** ln Σ K x at {@code t}, the vapour taken to its fixed point from {@code yStart}; null where a phase has no root. */
    private Eval eval(double[] x, double t, double[] yStart) {
        try {
            var terms = model.prepare(t);
            double[] liquid = model.phase(t, pressure, x, PengRobinsonKernel.Root.LIQUID, terms).logFugacityView();
            double[] y = yStart.clone(), k = new double[n];
            double sum = 1;
            for (int pass = 0; pass < 8; pass++) {
                double[] vapour = model.phase(t, pressure, y, PengRobinsonKernel.Root.VAPOR, terms).logFugacityView();
                sum = 0;
                for (int i = 0; i < n; i++) {
                    k[i] = DeterministicMath.exp(liquid[i] - vapour[i]);
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
            return new Eval(DeterministicMath.log(sum), y, k);
        } catch (RuntimeException absent) {
            return null;
        }
    }

    /** Liquid {@code x}'s molar enthalpy at {@code t} K on the network's datum, J/mol. */
    public double liquidEnthalpy(double t, double[] x) {
        return model.phase(t, pressure, fractions(x), PengRobinsonKernel.Root.LIQUID).molarEnthalpy();
    }

    /** Vapour {@code y}'s molar enthalpy at {@code t} K, J/mol. */
    public double vapourEnthalpy(double t, double[] y) {
        return model.phase(t, pressure, fractions(y), PengRobinsonKernel.Root.VAPOR).molarEnthalpy();
    }

    /** Liquid {@code x}'s molar volume at {@code t} K, m³/mol. */
    public double liquidVolume(double t, double[] x) {
        return model.phase(t, pressure, fractions(x), PengRobinsonKernel.Root.LIQUID).molarVolume();
    }

    /** Vapour {@code y}'s molar volume at {@code t} K, m³/mol. */
    public double vapourVolume(double t, double[] y) {
        return model.phase(t, pressure, fractions(y), PengRobinsonKernel.Root.VAPOR).molarVolume();
    }

    /** The molar mass of a mixture of mole fractions {@code x}, kg/mol. */
    public double molarMass(double[] x) {
        double[] f = fractions(x);
        double sum = 0;
        for (int i = 0; i < n; i++) sum += f[i] * molarMass[i];
        return sum;
    }

    /** Component {@code i}'s molar mass, kg/mol. */
    public double molarMass(int i) {
        return molarMass[i];
    }

    private double wilson(int i, double t) {
        return pc[i] / pressure * DeterministicMath.exp(5.373 * (1 + omega[i]) * (1 - tc[i] / t));
    }

    private double wilsonBubble(double[] x) {
        double low = LOWEST, high = HIGHEST;
        for (int step = 0; step < 80; step++) {
            double mid = (low + high) / 2, sum = 0;
            for (int i = 0; i < n; i++) if (x[i] > 0) sum += wilson(i, mid) * x[i];
            if (sum > 1) high = mid;
            else low = mid;
        }
        return (low + high) / 2;
    }

    private double[] wilsonVapour(double[] x, double t) {
        double[] y = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) sum += y[i] = x[i] > 0 ? wilson(i, t) * x[i] : 0;
        for (int i = 0; i < n; i++) y[i] /= sum;
        return y;
    }

    /** Amounts as mole fractions; refused for no amount or the wrong basis. */
    double[] fractions(double[] amounts) {
        if (amounts.length != n) throw new IllegalArgumentException("Still basis mismatch");
        double sum = 0;
        for (double e : amounts) {
            if (!(e >= 0) || !Double.isFinite(e)) throw new IllegalArgumentException("Invalid still amount");
            sum += e;
        }
        if (!(sum > 0)) throw new IllegalArgumentException("No liquid in the still");
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = amounts[i] / sum;
        return out;
    }

    private static double sum(double[] v) {
        double s = 0;
        for (double e : v) s += e;
        return s;
    }

    private static double[][] copy(double[][] x) {
        double[][] out = new double[x.length][];
        for (int j = 0; j < x.length; j++) out[j] = x[j].clone();
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

    private Split failed(int stages, double ratio, boolean closed, String why) {
        return new Split(false, 0, why, stages, ratio, closed, new double[n], Double.NaN, Double.NaN, Double.NaN, 0, 0, new double[n], Double.NaN,
                0, new double[n], Double.NaN, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, null);
    }
}
