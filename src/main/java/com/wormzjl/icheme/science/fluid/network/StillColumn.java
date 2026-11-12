package com.wormzjl.icheme.science.fluid.network;

import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
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
 * <p>Water (batch 2026-11-11-aqueous-solvents, W4_STILL_PLAN.md section 3). Where the model carries water
 * ({@link HydrocarbonModel#wet}) the column's basis is the model's conserved basis, water last, and water is one more
 * component of every stage (D1); a list without water keeps its compounds. The method states each stage's liquid as one
 * liquid. Where water is in it, two liquids are stated with the engine's own TP split ({@link FluidThermodynamics#flashTP},
 * D2): the pot once a solve and the condensate once an iteration. From the liquid's one-liquid bubble point, each trial
 * temperature is split by the engine and the oil layer's ln Σ K x read there, a bracketed secant to its zero (at most
 * {@value #LAYER_ROUNDS} splits); the condensate's next iteration splits once, at the temperature its last search gave.
 * The pot's vapour is its oil layer's; K is that vapour over the pot's overall liquid, all the stage above reads. A
 * condensate of two layers returns its oil layer first (D3, owner choice 1 a): per mole of distillate it is R + 1 moles,
 * oil O and aqueous A; the reflux takes the share ρo = min(1, R / O) of the oil and ρa = max(0, (R - O) / A) of the
 * aqueous layer, so component i returns the share s_i = ρo ω_i + ρa (1 - ω_i) (ω_i the oil layer's share of i). The
 * condenser's row reads the reflux x0 = s_i V1 y1 / R, the distillate is (R + 1) y1 (1 - s_i), and the energy balance
 * reads the reflux's enthalpy hR where it read h0; the condenser takes the top vapour to the whole condensate's enthalpy
 * hC. The shares are the previous iteration's and continuous where a layer appears or the oil runs out. With one liquid
 * s_i = R / (R + 1) and hR = hC = h0: the arithmetic is the one-liquid column's, row for row. The trays hold one liquid; at
 * convergence each tray with water in it is split once by the engine, and a tray whose liquid would settle is reported
 * ({@link Split#settlingTrays}, D4), never a failure. A liquid without water is never sent to the engine; a refused split
 * reads as one liquid.</p>
 *
 * <p>A closed valve (R infinite, total reflux) solves at {@link #CLOSED_REFLUX} for its profile and sends no distillate, so
 * its rates are per mole of top vapour; that vapour's heat is H1 - hC, the head vapour against its own condensate.
 * Numerical guards only: the bubble search's box and a phase with no equation-of-state root, both a refused trial. One
 * instance belongs to one thread at a time; it keeps no state between solves (a warm start passes its {@link Profile}).</p>
 */
public final class StillColumn {
    /** The most engine splits that find one liquid's two layers at their bubble point (the pot's, a cold condensate's). */
    public static final int LAYER_ROUNDS = 8;
    /** How far under its predicted bubble point a warm condensate is split, K: below it, so the one split finds its layers. */
    static final double LAYER_MARGIN = .02;
    private static final Runnable NO_CHECKPOINT = () -> {
    };
    /** The flows switch from constant molar overflow to the energy balance once no stage moves this much in an iteration, K. */
    public static final double SWITCH_KELVIN = 1;
    /** Converged once no stage moves this much in an energy-balance iteration, K. */
    public static final double TOLERANCE = 1e-4;
    public static final int MAX_ITERATIONS = 300;
    /** Iterations with no new least change, since the flows last changed form, that end the iterating (W4 plan section 12). */
    public static final int STALL_ITERATIONS = 20;
    /** The notice of a wet column that fell back to the pot's single stage (W4 plan section 12). */
    public static final String POT_STILL = "two liquids on the trays: the column does not rectify";
    /** The most trays. */
    public static final int MAX_STAGES = 8;
    /** The reflux ratio a closed valve's profile is solved at. */
    public static final double CLOSED_REFLUX = 1e4;
    /** The bubble search's numerical box, K. */
    static final double LOWEST = 60, HIGHEST = 1400;

    private final FluidThermodynamics thermo;
    private final HydrocarbonModel model;
    private final double pressure;
    /** The basis: the compounds, then water where the model carries it ({@link #water}). */
    private final int n, water;
    private final double[] tc, pc, omega, molarMass;
    /** The engine splits of the solve under way (one thread at a time). */
    private int flashes;

    /** The column on {@code thermo}'s model at {@code pressure} Pa on every stage; its engine states the liquids that hold water. */
    public StillColumn(FluidThermodynamics thermo, double pressure) {
        if (!(pressure > 0) || !Double.isFinite(pressure)) throw new IllegalArgumentException("Invalid still pressure");
        this.thermo = thermo;
        model = thermo.hydrocarbon;
        this.pressure = pressure;
        int compounds = model.componentCount();
        water = model.wet() ? compounds : -1;
        n = model.wet() ? compounds + 1 : compounds;
        tc = new double[n];
        pc = new double[n];
        omega = new double[n];
        molarMass = new double[n];
        var components = components(model);
        for (int i = 0; i < compounds; i++) {
            var c = components.get(i);
            tc[i] = c.criticalTemperatureKelvin();
            pc[i] = c.criticalPressurePascal();
            omega[i] = c.acentricFactor();
        }
        if (water >= 0) {
            // Water's constants for the Wilson start: the model's own derivation, where its cubic carries water last.
            var derivation = model.evaluator().derivation();
            tc[water] = derivation.criticalTemperatures()[water];
            pc[water] = derivation.criticalPressures()[water];
            omega[water] = derivation.acentricFactors()[water];
        }
        for (int i = 0; i < n; i++) molarMass[i] = model.molecularWeight(i);
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

    /** A vector on the column's basis on the model's conserved basis: itself where the model carries water, else water at zero. */
    private double[] conserved(double[] x) {
        return water >= 0 ? x : java.util.Arrays.copyOf(x, n + 1);
    }

    public HydrocarbonModel model() {
        return model;
    }

    public FluidThermodynamics thermo() {
        return thermo;
    }

    /** Water's index on the column's basis (the last), -1 on a list without water. */
    public int water() {
        return water;
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
     * @param topVapourRate         the vapour into the condenser, (R + 1) D, of the head vapour's composition (1 with a closed valve)
     * @param headEnthalpy          H1, the head vapour's
     * @param condensateEnthalpy    hC, the whole condensate's at its bubble point (both layers; h0 with one liquid)
     * @param potEnthalpy           the pot liquid's at its bubble point (both layers where it holds two)
     * @param energyPerDistillate   the energy that leaves the pot and the column per mole of distillate, (R + 1) H1 - R hR
     *                              (the vapour's H for one stage; infinite with a closed valve)
     * @param refluxRatio           the reflux ratio solved (0 for one stage, {@link #CLOSED_REFLUX} for a closed valve)
     * @param refluxEnthalpy        hR, the reflux's (h0 with one liquid; the condensate's with no reflux)
     * @param distillateEnthalpy    hD, the distillate's, ((R + 1) hC - R hR) from the layers (h0 with one liquid)
     * @param twoLiquidPot          the pot's liquid settles into two liquids at its bubble point
     * @param twoLiquidCondensate   the condensate settles into two layers
     * @param settlingTrays         the trays (1..N) whose liquid would settle into two liquids (D4): a notice, not a failure
     * @param flashes               the engine splits the solve made (0 where no liquid holds water)
     * @param notice                {@link #POT_STILL} where a wet column fell back to the pot's single stage, else null
     */
    public record Split(boolean converged, int iterations, String failure, int stages, double refluxRatio, boolean closed,
                        double[] distillate, double headTemperature, double condensateTemperature, double potTemperature,
                        double distillateRate, double vapourRate, double[] vapour, double vapourEnthalpy,
                        double liquidRate, double[] liquid, double liquidEnthalpy,
                        double topVapourRate, double headEnthalpy, double condensateEnthalpy, double potEnthalpy,
                        double energyPerDistillate, Profile profile, double refluxEnthalpy, double distillateEnthalpy,
                        boolean twoLiquidPot, boolean twoLiquidCondensate, int[] settlingTrays, int flashes, String notice) {
        /** The condenser's duty per mole of the rates' basis, J/mol: all of the top vapour condensed to its bubble point, both layers. */
        public double condenserDuty() {
            return topVapourRate * (headEnthalpy - condensateEnthalpy);
        }

        @Override
        public int[] settlingTrays() {
            return settlingTrays.clone();
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
     * A liquid at its bubble point as the engine states it (D2): the temperature, the vapour, K over the whole liquid, its
     * molar enthalpy and, with two liquids, the oil layer's share of the moles, each layer's composition and enthalpy, the
     * next trial temperature of its search and the slope of the oil layer's ln Σ K x there (a warm start's).
     */
    private record Boiling(Bubble bubble, double enthalpy, boolean two, double oil, double[] oilLayer, double[] aqueousLayer,
                           double oilEnthalpy, double aqueousEnthalpy, double next, double slope) {
        double temperature() {
            return bubble.temperature();
        }
    }

    /** Two liquid layers of one liquid: the oil layer's share of its moles, each layer's composition, and whether a vapour stands beside them. */
    private record Layers(double oil, double[] oilLayer, double[] aqueousLayer, boolean vapour) {
    }

    /**
     * The condensate of two layers returned oil first (D3), per mole of distillate: each component's share s_i back to the
     * column, the reflux's, the whole condensate's and the distillate's enthalpies, the distillate's composition and the
     * condensate's bubble point. A condensate of one liquid has none (null).
     */
    private record Decanter(double temperature, double[] shares, double reflux, double whole, double distillate, double[] composition,
                            Boiling condensate) {
    }

    /**
     * The column for pot liquid {@code pot} (any amounts) under {@code stages} trays (0: none) at reflux ratio
     * {@code reflux} (0: no reflux, the trays dry; {@link Double#POSITIVE_INFINITY}: a closed valve), warm from {@code warm}
     * (null for a cold start, or a profile of another stage count).
     */
    public Split solve(double[] pot, int stages, double reflux, Profile warm) {
        if (stages < 0 || stages > MAX_STAGES) throw new IllegalArgumentException("Still column stages must be 0 to " + MAX_STAGES);
        if (!(reflux >= 0)) throw new IllegalArgumentException("Invalid still reflux");
        flashes = 0;
        double[] z = fractions(pot);
        if (stages == 0 || reflux == 0) return single(z, warm);
        boolean closed = reflux > CLOSED_REFLUX;
        double ratio = Math.min(reflux, CLOSED_REFLUX);
        int m = stages + 2, r = m - 1;
        boolean seeded = warm != null && warm.temperatures().length == m && warm.liquids().length == m;
        var potBoiling = boiling(fractions(z), seeded ? warm.potTemperature() : Double.NaN, null);
        if (potBoiling == null) return failed(stages, ratio, closed, "no bubble point of the pot liquid");
        var start = potBoiling.bubble();
        // A pot that settles starts its stages on its oil layer (W4 plan section 12): at the answer each tray is the oil layer
        // at saturation, where one liquid's K is smooth through the edge of the gap; the pot's whole liquid read as one is not.
        double[] oil = potBoiling.two() ? fractions(potBoiling.oilLayer()) : null;
        var oilStart = oil != null ? bubble(oil, start.temperature(), start.vapour()) : null;
        double[] t = new double[m];
        double[][] x = new double[m][], y = new double[m][], k = new double[m][];
        for (int j = 0; j < m; j++) {
            Bubble b = null;
            if (seeded && j < r && warm.liquids()[j].length == n && sum(warm.liquids()[j]) > 0) {
                x[j] = fractions(warm.liquids()[j]);
                var e = eval(x[j], warm.temperatures()[j], start.vapour());
                if (e != null) b = new Bubble(warm.temperatures()[j], e.y(), e.k());
            }
            if (b == null && oilStart != null) {
                x[j] = oil.clone();
                b = oilStart;
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
        // The stall rule: the least change since the flows last changed form; STALL_ITERATIONS with no new least end it.
        double least = Double.POSITIVE_INFINITY;
        int sinceLeast = 0, iteration = 0;
        // The condensate's layers on the top vapour (D2, D3): the shares of this iteration are the last one's.
        var decanter = decanter(y[1], t[0], ratio, null);
        while (++iteration <= MAX_ITERATIONS) {
            if (energy) {
                if (!energyFlows(ratio, t, x, y, vapour, liquid, decanter))
                    return failedOrPot(failed(stages, ratio, closed, "no enthalpy on a stage"), z, potBoiling, decanter, t, x, stages, iteration);
                for (int j = 0; j < m; j++)
                    if ((j > 0 && !(vapour[j] > 0)) || (j < r && !(liquid[j] > 0)))
                        return failedOrPot(failed(stages, ratio, closed, "no positive flow on stage " + j), z, potBoiling, decanter, t, x, stages, iteration);
            } else {
                for (int j = 0; j < m; j++) {
                    vapour[j] = ratio + 1;
                    liquid[j] = ratio;
                }
            }
            double[][] raw = new double[r][n];
            for (int i = 0; i < n; i++) {
                a[0] = 0;
                // The condenser: the reflux R x0 is the share s_i of the top vapour V1 y1 (R / (R + 1) with one liquid).
                double share = decanter == null ? Double.NaN : decanter.shares()[i];
                if (decanter == null) b[0] = -(liquid[0] + 1);
                else b[0] = share > 0 ? -(liquid[0] / share) : 1;
                c[0] = decanter == null || share > 0 ? vapour[1] * k[1][i] : 0;
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
                if (!(sum > 0) || !Double.isFinite(sum))
                    return failedOrPot(failed(stages, ratio, closed, "no liquid on stage " + j), z, potBoiling, decanter, t, x, stages, iteration);
                x[j] = fractions(raw[j]);
                var bubble = bubble(x[j], t[j], y[j]);
                if (bubble == null) return failedOrPot(failed(stages, ratio, closed, "no bubble point on stage " + j), z, potBoiling, decanter, t, x, stages, iteration);
                change = Math.max(change, Math.abs(bubble.temperature() - t[j]));
                t[j] = bubble.temperature();
                y[j] = bubble.vapour();
                k[j] = bubble.k();
            }
            decanter = decanter(y[1], t[0], ratio, decanter);
            if (energy && change < TOLERANCE) {
                // A wet tray colder than the tray above it is the miscibility gap's sign: where a liquid of the still settles,
                // the column does not rectify (W4 plan section 12).
                if (coldWetTray(t, x, stages) && settles(potBoiling, decanter, t, x, stages)) return single(z, potBoiling, POT_STILL, iteration);
                var answer = answer(stages, ratio, closed, iteration, t, x, y, potBoiling, decanter);
                return answer.converged() ? answer : failedOrPot(answer, z, potBoiling, decanter, t, x, stages, iteration);
            }
            if (change < least) {
                least = change;
                sinceLeast = 0;
            } else if (++sinceLeast >= STALL_ITERATIONS) break;
            if (!energy && change < SWITCH_KELVIN) {
                energy = true;
                least = Double.POSITIVE_INFINITY;
                sinceLeast = 0;
            }
        }
        int done = Math.min(iteration, MAX_ITERATIONS);
        return failedOrPot(failed(stages, ratio, closed, "no convergence in " + done + " iterations"), z, potBoiling, decanter, t, x, stages, done);
    }

    /**
     * A failure of the iteration (W4 plan section 12): where a liquid of the still settles (the pot, the condensate or a wet
     * tray), a tray holds free water the one-liquid trays cannot carry, and the answer is the pot's single stage with
     * {@link #POT_STILL}; a dry solve fails as {@code failure} (no flash: {@link #settles} splits wet trays only).
     */
    private Split failedOrPot(Split failure, double[] z, Boiling pot, Decanter decanter, double[] t, double[][] x, int stages, int iterations) {
        return settles(pot, decanter, t, x, stages) ? single(z, pot, POT_STILL, iterations) : failure;
    }

    /** A tray j (2..N) whose liquid holds water and stands more than {@link #SWITCH_KELVIN} under tray j - 1. */
    private boolean coldWetTray(double[] t, double[][] x, int stages) {
        if (water < 0) return false;
        for (int j = 2; j <= stages; j++) if (x[j][water] > 0 && t[j] < t[j - 1] - SWITCH_KELVIN) return true;
        return false;
    }

    /** Whether a liquid of the still settles: the pot, the condensate, or a wet tray the engine splits (one split a tray). */
    private boolean settles(Boiling pot, Decanter decanter, double[] t, double[][] x, int stages) {
        if (pot.two() || decanter != null) return true;
        if (water < 0) return false;
        for (int j = 1; j <= stages; j++) if (x[j][water] > 0 && layers(x[j], t[j]) != null) return true;
        return false;
    }

    /** The converged column's answer: its flows from the energy balance on the final profile, per mole of its basis. */
    private Split answer(int stages, double ratio, boolean closed, int iterations, double[] t, double[][] x, double[][] y,
                         Boiling pot, Decanter decanter) {
        int m = t.length, r = m - 1;
        double[] vapour = new double[m], liquid = new double[m];
        if (!energyFlows(ratio, t, x, y, vapour, liquid, decanter)) return failed(stages, ratio, closed, "no enthalpy on a stage");
        double h0, headVapour, hPot, hBack, hUp;
        try {
            h0 = decanter == null ? liquidEnthalpy(t[0], x[0]) : Double.NaN;
            headVapour = vapourEnthalpy(t[1], y[1]);
            hPot = pot.two() ? pot.enthalpy() : liquidEnthalpy(t[r], x[r]);
            hBack = liquidEnthalpy(t[r - 1], x[r - 1]);
            hUp = vapourEnthalpy(t[r], y[r]);
        } catch (RuntimeException absent) {
            return failed(stages, ratio, closed, "no enthalpy on a stage");
        }
        // One liquid: hR = hC = hD = h0. Two layers: the decanter's (D3).
        double hR = decanter == null ? h0 : decanter.reflux(), hC = decanter == null ? h0 : decanter.whole();
        double hD = decanter == null ? h0 : decanter.distillate();
        // The flows are per mole of distillate (D = 1); a closed valve's per mole of top vapour (V1 = 1).
        double d = closed ? 1 / vapour[1] : 1;
        var profile = new Profile(t.clone(), copy(x));
        // The head vapour leaves tray 1 at its dew point; a total condenser gives its composition, x0, to the distillate; of
        // two layers the distillate is what the reflux leaves, (R + 1) y1 (1 - s).
        double[] distillate = decanter == null ? x[0].clone() : decanter.composition().clone();
        double condensing = decanter == null ? t[0] : decanter.temperature();
        int[] settling = settling(t, x, stages);
        return new Split(true, iterations, null, stages, ratio, closed, distillate, t[1], condensing, t[r], closed ? 0 : d,
                vapour[r] * d, y[r].clone(), hUp, closed ? vapour[r] * d : liquid[r - 1] * d, closed ? y[r].clone() : x[r - 1].clone(),
                hBack, vapour[1] * d, headVapour, hC, hPot, closed ? Double.POSITIVE_INFINITY : (ratio + 1) * headVapour - ratio * hR, profile, hR, hD,
                pot.two(), decanter != null, settling, flashes, null);
    }

    /** One equilibrium stage: the pot's bubble vapour is the distillate. */
    private Split single(double[] z, Profile warm) {
        var potBoiling = boiling(fractions(z), warm != null ? warm.potTemperature() : Double.NaN, null);
        if (potBoiling == null) return failed(0, 0, false, "no bubble point of the pot liquid");
        return single(z, potBoiling, null, 0);
    }

    /** One equilibrium stage on the pot's {@code potBoiling}, with {@code notice} on its answer, after {@code iterations} of a column. */
    private Split single(double[] z, Boiling potBoiling, String notice, int iterations) {
        var pot = potBoiling.bubble();
        double[] y = pot.vapour();
        var condensing = boiling(fractions(y), pot.temperature() - 1, null);
        if (condensing == null) return failed(0, 0, false, "no bubble point of the condensate");
        var condensate = condensing.bubble();
        double hPot, hVapour, h0;
        try {
            hPot = potBoiling.two() ? potBoiling.enthalpy() : liquidEnthalpy(pot.temperature(), z);
            hVapour = vapourEnthalpy(pot.temperature(), y);
            h0 = condensing.two() ? condensing.enthalpy() : liquidEnthalpy(condensate.temperature(), y);
        } catch (RuntimeException absent) {
            return failed(0, 0, false, "no enthalpy of the pot or its vapour");
        }
        var profile = new Profile(new double[]{condensate.temperature(), pot.temperature()}, new double[][]{y.clone(), z.clone()});
        // No reflux: the whole condensate, both layers, is the distillate.
        return new Split(true, iterations, null, 0, 0, false, y.clone(), pot.temperature(), condensate.temperature(), pot.temperature(), 1, 1, y.clone(),
                hVapour, 0, z.clone(), hPot, 1, hVapour, h0, hPot, hVapour, profile, h0, h0, potBoiling.two(), condensing.two(),
                NO_TRAYS, flashes, notice);
    }

    private static final int[] NO_TRAYS = new int[0];

    /**
     * Liquid {@code x} (mole fractions) at its bubble point as the engine states it (D2): its one-liquid bubble point from
     * {@code guess}; where water is in it, the engine's split there and, with two liquids, the layers' bubble point
     * ({@link #layered}). The vapour is the oil layer's and K that vapour over {@code x}. Null where no bubble point is
     * found; one liquid where a split is refused.
     */
    private Boiling boiling(double[] x, double guess, double[] yGuess) {
        return boiling(x, guess, yGuess, null);
    }

    /**
     * {@link #boiling(double[], double, double[])}; with {@code warm} (the last iteration's two layers of a liquid that moves
     * little, the condensate's) one split at its next start first (just under the bubble point it predicted), and the full
     * search only where that split is not below the bubble point.
     */
    private Boiling boiling(double[] x, double guess, double[] yGuess, Boiling warm) {
        boolean wetLiquid = water >= 0 && x[water] > 0;
        if (wetLiquid && warm != null && warm.two()) {
            var once = layered(x, warm.next(), warm.bubble().vapour(), warm.slope(), 1);
            if (once != null) return once;
        }
        var one = bubble(x, guess, yGuess);
        if (one == null) return null;
        var single = new Boiling(one, Double.NaN, false, 1, x, null, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        if (!wetLiquid) return single;
        var best = layered(x, one.temperature(), one.vapour(), Double.NaN, LAYER_ROUNDS);
        return best != null ? best : single;
    }

    /**
     * Liquid {@code x}'s two layers at their bubble point, from {@code t} K: at each trial temperature the engine's split
     * and, where it holds two liquids and no vapour (below the bubble point), the oil layer's f = ln Σ K x on its layers
     * there, negative and rising to zero at the bubble point. A split with vapour or one liquid lies above it (in a mixture
     * of three or more components two liquids and a vapour coexist over a range of temperatures, where f is zero
     * throughout). A secant on the trials below, held inside the bracket (bisection where it leaves it), at most
     * {@code most} splits. The first slope is {@code slope} where finite, else f's at the first layers' fixed composition
     * (too small: the layers take up more of each other as the temperature rises). The answer is the highest trial below,
     * and the next start {@link #LAYER_MARGIN} under the bubble point it predicts. Null where no trial is below.
     */
    private Boiling layered(double[] x, double t, double[] yStart, double slope, int most) {
        double below = Double.NEGATIVE_INFINITY, above = Double.POSITIVE_INFINITY, lastT = Double.NaN, lastF = Double.NaN;
        double[] y = yStart;
        Layers bestLayers = null;
        Eval best = null;
        double bestT = Double.NaN, bestSlope = Double.NaN;
        for (int round = 0; round < most; round++) {
            var layers = layers(x, t);
            var e = layers == null || layers.vapour() ? null : eval(layers.oilLayer(), t, y);
            double step;
            if (e == null || !(e.f() < 0)) {
                // Two liquids, no vapour, and f not below zero: at the bubble point (the engine's split and the oil layer's
                // ln Σ K x agree to their tolerances). Else above it.
                if (e != null) {
                    best = e;
                    bestLayers = layers;
                    bestT = t;
                    bestSlope = slope;
                    break;
                }
                // One liquid before any trial below: the liquid does not settle.
                if (layers == null && !Double.isFinite(below)) break;
                above = Math.min(above, t);
                step = Double.isFinite(below) ? (below + above) / 2 : t - 5;
            } else {
                double f = e.f();
                double s = Double.isFinite(lastT) ? (f - lastF) / (t - lastT) : slope;
                if (!(s > 0)) {
                    var shifted = eval(layers.oilLayer(), t + 1, e.y());
                    s = shifted == null ? Double.NaN : shifted.f() - f;
                }
                if (!(t < bestT)) {
                    best = e;
                    bestLayers = layers;
                    bestT = t;
                    bestSlope = s;
                }
                below = Math.max(below, t);
                lastT = t;
                lastF = f;
                y = e.y();
                step = s > 0 ? t - f / s : Double.NaN;
                if (!(step > below && step < above)) step = Double.isFinite(above) ? (below + above) / 2 : t + 5;
                if (f > -1e-10) break;
            }
            if (Math.abs(step - t) < TOLERANCE || above - below < TOLERANCE) break;
            t = step;
        }
        if (best == null) return null;
        double hOil, hAqueous;
        try {
            hOil = liquidEnthalpy(bestT, bestLayers.oilLayer());
            hAqueous = liquidEnthalpy(bestT, bestLayers.aqueousLayer());
        } catch (RuntimeException absent) {
            return null;
        }
        double[] k = new double[n];
        for (int i = 0; i < n; i++) k[i] = x[i] > 0 ? best.y()[i] / x[i] : best.k()[i];
        double beta = bestLayers.oil();
        double predicted = bestSlope > 0 ? bestT - best.f() / bestSlope : bestT;
        if (Double.isFinite(above)) predicted = Math.min(predicted, above);
        return new Boiling(new Bubble(bestT, best.y(), k), beta * hOil + (1 - beta) * hAqueous, true, beta, bestLayers.oilLayer(),
                bestLayers.aqueousLayer(), hOil, hAqueous, predicted - LAYER_MARGIN, bestSlope);
    }

    /**
     * The engine's TP split of liquid {@code x} (mole fractions, water in it) at {@code t} K: its two liquid layers, or null
     * for one liquid or a refused split. The layers' compositions are the slots'; the oil layer's share of the moles is the
     * least-squares lever rule of {@code x} on them (a trace of vapour at a bubble point does not move it).
     */
    private Layers layers(double[] x, double t) {
        flashes++;
        FluidThermodynamics.State state;
        try {
            state = thermo.flashTP(t, pressure, conserved(x), NO_CHECKPOINT);
        } catch (RuntimeException refused) {
            return null;
        }
        if (!FluidThermodynamics.holdsPhase(state, FluidThermodynamics.OIL) || !FluidThermodynamics.holdsPhase(state, FluidThermodynamics.AQUEOUS))
            return null;
        double[] oil = normalized(state.labelAmountsView(FluidThermodynamics.OIL)), aqueous = normalized(state.labelAmountsView(FluidThermodynamics.AQUEOUS));
        if (oil == null || aqueous == null) return null;
        double along = 0, span = 0;
        for (int i = 0; i < n; i++) {
            double d = oil[i] - aqueous[i];
            along += (x[i] - aqueous[i]) * d;
            span += d * d;
        }
        if (!(span > 0)) return null;
        return new Layers(Math.clamp(along / span, 0, 1), oil, aqueous, FluidThermodynamics.holdsPhase(state, FluidThermodynamics.GAS));
    }

    /** Whether liquid {@code amounts} (any amounts on the column's basis) would settle into two liquids at {@code t} K: the engine's split; false without water. */
    public boolean twoLiquids(double[] amounts, double t) {
        double[] x = fractions(amounts);
        return water >= 0 && x[water] > 0 && layers(x, t) != null;
    }

    /**
     * The condensate of top vapour {@code y1} (D2, D3): null for one liquid (no water in it, a refused split, one layer);
     * else its layers at its bubble point (from {@code guess} K) and the reflux R drawn from the oil layer first. With the
     * last iteration's two layers ({@code last}) one split at their temperature states them (D7: one a iteration).
     */
    private Decanter decanter(double[] y1, double guess, double ratio, Decanter last) {
        if (water < 0 || !(y1[water] > 0)) return null;
        var condensate = boiling(fractions(y1), guess, null, last != null ? last.condensate() : null);
        if (condensate == null || !condensate.two()) return null;
        double beta = condensate.oil(), total = ratio + 1, oil = beta * total, aqueous = total - oil;
        double fromOil = oil > 0 ? Math.min(1, ratio / oil) : 1, fromAqueous = aqueous > 0 ? Math.max(0, (ratio - oil) / aqueous) : 0;
        double[] xo = condensate.oilLayer(), xa = condensate.aqueousLayer(), shares = new double[n], out = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double o = beta * xo[i], a = (1 - beta) * xa[i], omega = o + a > 0 ? o / (o + a) : 1;
            shares[i] = fromOil * omega + fromAqueous * (1 - omega);
            sum += out[i] = (1 - shares[i]) * y1[i];
        }
        if (!(sum > 0)) return null;
        for (int i = 0; i < n; i++) out[i] /= sum;
        double hOil = condensate.oilEnthalpy(), hAqueous = condensate.aqueousEnthalpy();
        double reflux = (fromOil * oil * hOil + fromAqueous * aqueous * hAqueous) / ratio;
        double distillate = (1 - fromOil) * oil * hOil + (1 - fromAqueous) * aqueous * hAqueous;
        return new Decanter(condensate.temperature(), shares, reflux, beta * hOil + (1 - beta) * hAqueous, distillate, out, condensate);
    }

    /** The trays (1..N) whose liquid, with water in it, the engine splits into two liquids at the tray's temperature (D4). */
    private int[] settling(double[] t, double[][] x, int stages) {
        if (water < 0) return NO_TRAYS;
        int[] found = new int[stages];
        int count = 0;
        for (int j = 1; j <= stages; j++) if (x[j][water] > 0 && layers(x[j], t[j]) != null) found[count++] = j;
        return count == 0 ? NO_TRAYS : java.util.Arrays.copyOf(found, count);
    }

    private static double[] normalized(double[] amounts) {
        double sum = sum(amounts);
        if (!(sum > 0) || !Double.isFinite(sum)) return null;
        double[] out = new double[amounts.length];
        for (int i = 0; i < out.length; i++) out[i] = Math.max(0, amounts[i]) / sum;
        return out;
    }

    /**
     * The flows per mole of distillate from the energy balances on the stage states (Wang and Henke): around the condenser
     * and stages 1..j, V(j+1) (H(j+1) - hj) = (R + 1) H1 - R hR - hj, and Lj = V(j+1) - 1; L0 = R. hR is the reflux's: the
     * stage-0 liquid's with one liquid, the decanter's with two layers. False where a stage has no enthalpy.
     */
    private boolean energyFlows(double ratio, double[] t, double[][] x, double[][] y, double[] vapour, double[] liquid, Decanter decanter) {
        int m = t.length, r = m - 1;
        double[] h = new double[m], hv = new double[m];
        try {
            for (int j = 0; j < m; j++) {
                h[j] = j == 0 && decanter != null ? decanter.reflux() : liquidEnthalpy(t[j], x[j]);
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
            double[] liquid = model.phase(t, pressure, conserved(x), PengRobinsonKernel.Root.LIQUID, terms, false).logFugacityView();
            double[] y = yStart.clone(), k = new double[n];
            double sum = 1;
            for (int pass = 0; pass < 8; pass++) {
                double[] vapour = model.phase(t, pressure, conserved(y), PengRobinsonKernel.Root.VAPOR, terms, false).logFugacityView();
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
        return model.phase(t, pressure, conserved(fractions(x)), PengRobinsonKernel.Root.LIQUID).molarEnthalpy();
    }

    /** Vapour {@code y}'s molar enthalpy at {@code t} K, J/mol. */
    public double vapourEnthalpy(double t, double[] y) {
        return model.phase(t, pressure, conserved(fractions(y)), PengRobinsonKernel.Root.VAPOR).molarEnthalpy();
    }

    /** Liquid {@code x}'s molar volume at {@code t} K, m³/mol. */
    public double liquidVolume(double t, double[] x) {
        return model.phase(t, pressure, conserved(fractions(x)), PengRobinsonKernel.Root.LIQUID).molarVolume();
    }

    /** Vapour {@code y}'s molar volume at {@code t} K, m³/mol. */
    public double vapourVolume(double t, double[] y) {
        return model.phase(t, pressure, conserved(fractions(y)), PengRobinsonKernel.Root.VAPOR).molarVolume();
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
                0, new double[n], Double.NaN, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, null, Double.NaN, Double.NaN, false, false,
                NO_TRAYS, flashes, null);
    }
}
