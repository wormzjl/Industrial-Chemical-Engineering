package com.wormzjl.icheme.science.thermo.phase;

import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.CONVERGED;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.R;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.SPECIFICATION_TOLERANCE;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.diagnostics;
import static com.wormzjl.icheme.science.thermo.phase.SpecificationSearch.outerAnswer;

import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.math.SmallDenseSolve;
import com.wormzjl.icheme.science.thermo.NumericalBox;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.PhaseIdentification;
import com.wormzjl.icheme.science.thermo.phase.CrystalSet.Crystal;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumRequest.Specification;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.Classification;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.FailureKind;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.Workspace;
import com.wormzjl.icheme.science.thermo.phase.ThreeSlotFlash.Slots;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * The steps a TP answer of {@link FluidTpEquilibrium} leaves undetermined, answered from the balances (P3 WP6b, P4 stage
 * 2a, P5, W3a D4, D21; S01): a pure fluid at its equal-fugacity point ({@link #pureCoexistence}), a pure or near-pure
 * species beside its competing crystal at its sublimation or melting step ({@link #pureCrystalStep}) and at its family's
 * triple point ({@link #pureTriplePoint}), a binary's vapour-liquid-crystal step ({@link #binaryThreePhase}) and
 * vapour-liquid-liquid step ({@link #binaryFluidThreePhase}), and a closed bracket's step by the lever rule
 * ({@link #leverStep}). The PH and UV searches call them where a bracket straddles or closes on a step; each returns the
 * answer, or null with the step's end on the target's side in the workspace's {@code coexistenceX} and
 * {@code coexistenceResidual}. It reads the engine's contract, evaluator, kernel, critical band, crystal set, basis
 * maps and converged coverage, given once at construction, and calls back into the engine only for {@code converged},
 * {@code single} and the static {@code diagnostics}, and into the search for its static {@code outerAnswer}; a crystal
 * answer's coverage is the crystal equilibrium's. The onsets take the crystal step's temperature and pressure from it.
 * Immutable; one instance per engine.
 */
final class BalanceSteps {
    private static final int COEXISTENCE_ITERATIONS = 100;
    /** Newton steps of a binary's three-phase point (P5), and its tolerance on {@code |mu - mu_s|/(R T)}. */
    private static final int THREE_PHASE_ITERATIONS = 50;
    private static final double THREE_PHASE_TOLERANCE = 1.0e-11;
    /** A species below this fraction of the feed is a trace of a pseudo-binary's three-phase step (P5). */
    private static final double TRACE_FRACTION = 1.0e-8;
    /**
     * D21: a dry feed whose species other than its largest total at most this fraction of it is near-pure. When that
     * major species has a competing crystal, PH and UV answer its crystal steps (sublimation, melting, triple point) as
     * a pure feed's, the other species carried as traces in the fluid phase the step leaves ({@link #STEP_FLUID_TRACES});
     * the pure species' pressure or temperature is then about 1.3 times the trace fraction off (0.1 mK at 101 kPa at
     * this bound). TP cannot resolve the feed's own vapour-solid region, about the trace fraction wide in pressure, up
     * to about 1e-6 and not reliably just above (a PH target at 2e-6 closed on a 0.57 J step of H(T) over 6e-10 K).
     * Takes precedence over the pseudo-binary rule of {@link #TRACE_FRACTION}.
     */
    private static final double NEAR_PURE_TRACES = 1.0e-5;
    /**
     * D21: the largest share of a near-pure step's fluid its traces may take. The step at the pure species' point leaves
     * out the traces' dilution of that fluid, which moves the real step by about this share in ln P; a step needing a
     * smaller fluid (deep deposition) is the general solve's, whose vapour-solid region is then at least this share
     * wide in drive. At 10 times {@link #NEAR_PURE_TRACES} every near-pure feed keeps at least 90 % of its step on the
     * rule, and the handover moves the answer by at most 1e-4 in ln P; unbounded, the rule and the general solve
     * alternate along {@code H(T)} where the traces make most of the fluid.
     */
    private static final double STEP_FLUID_TRACES = 1.0e-4;
    /**
     * The margin of the triple-point screen, a fraction of the feed: the feed's own phases (a near-pure feed's traces in
     * one fluid) move the triangle's balances by about the trace fraction, far inside it.
     */
    private static final double TRIANGLE_SCREEN = 1.0e-3;
    /** Evaluations of the fluid at its own composition while a near-pure step's split converges (D21). */
    private static final int NEAR_PURE_ITERATIONS = 8;
    /** Two ends' phases of one kind are the same phase across a closed bracket's step where their compositions differ by less (mole fraction). */
    private static final double LEVER_MATCH = 1.0e-4;

    private final FluidTpEquilibrium engine;
    private final CrystalEquilibrium crystalEquilibrium;
    private final PhaseContract contract;
    private final CubicPhaseEvaluator evaluator;
    private final PengRobinsonKernel kernel;
    private final CriticalBand criticalBand;
    private final CrystalSet crystalSet;
    private final int count;
    private final int basis;
    private final int[] basisOfComponent;
    /** W3a D1: water is a component of the cubic (the engine's): a binary's vapour-liquid-liquid step needs it. */
    private final boolean inEquationOfState;
    /** The coverage of a converged fluid-only answer (the engine's). */
    private final EquilibriumResult.Coverage convergedCoverage;

    BalanceSteps(FluidTpEquilibrium engine, CrystalEquilibrium crystalEquilibrium, PhaseContract contract,
                 CubicPhaseEvaluator evaluator, PengRobinsonKernel kernel, CriticalBand criticalBand, CrystalSet crystalSet,
                 int count, int basis, int[] basisOfComponent, boolean inEquationOfState,
                 EquilibriumResult.Coverage convergedCoverage) {
        this.engine = engine;
        this.crystalEquilibrium = crystalEquilibrium;
        this.contract = contract;
        this.evaluator = evaluator;
        this.kernel = kernel;
        this.criticalBand = criticalBand;
        this.crystalSet = crystalSet;
        this.count = count;
        this.basis = basis;
        this.basisOfComponent = basisOfComponent;
        this.inEquationOfState = inEquationOfState;
        this.convergedCoverage = convergedCoverage;
    }

    // Coexistences TP leaves undetermined.

    /**
     * A pure hydrocarbon at its equal-fugacity point: TP leaves its split undetermined, PH (at the fixed pressure, the
     * energy balance) and UV (at the fixed temperature, the volume balance) determine it:
     * {@code target = N [(1 - beta) x_L + beta x_V]} with {@code x} the saturated states' molar enthalpy or volume. The
     * equal-fugacity temperature (PH) or pressure (UV) is solved on the kernel from {@code t} or {@code p}. Returns the
     * answer when {@code beta} lies in {@code [0, 1]} (within the specification tolerance, or always when
     * {@code clamp}); otherwise null with the jump's end on the target's side in {@code coexistenceX} (T for PH,
     * {@code ln P} for UV) and {@code coexistenceResidual}; {@code coexistenceX} is NaN when no equal-fugacity point
     * was found.
     */
    EquilibriumResult pureCoexistence(Specification specification, double t, double p, double target, boolean clamp,
                                      PhaseCompetition competition, Workspace w) {
        w.coexistenceX = Double.NaN;
        boolean energy = specification == Specification.PH;
        if (energy) t = coexistenceTemperature(t, p, w); else p = coexistencePressure(t, p, w);
        if (Double.isNaN(t) || Double.isNaN(p)) return null;
        PhaseState liquid = evaluator.evaluate(t, p, w.request.amounts, Root.LIQUID, w.phases, w.liquidScratch);
        PhaseState vapour = evaluator.evaluate(t, p, w.request.amounts, Root.VAPOR, w.phases, w.vaporScratch);
        w.outerKernel += 2;
        double n = w.hydrocarbonTotal;
        double liquidValue = energy ? liquid.balanceEnthalpy() : liquid.balanceVolume();
        double vapourValue = energy ? vapour.balanceEnthalpy() : vapour.balanceVolume();
        double beta = (target / n - liquidValue) / (vapourValue - liquidValue);
        // The specification tolerance as a phase fraction.
        double gap = vapourValue - liquidValue;
        double belowAllowance = SPECIFICATION_TOLERANCE * (energy ? R * t : liquidValue) / gap;
        double aboveAllowance = SPECIFICATION_TOLERANCE * (energy ? R * t : vapourValue) / gap;
        if (!clamp && (beta < -belowAllowance || beta > 1.0 + aboveAllowance) || !Double.isFinite(beta)) {
            boolean liquidEnd = beta < 0.0;
            double value = n * (liquidEnd ? liquidValue : vapourValue);
            w.coexistenceX = energy ? t : DeterministicMath.log(p);
            w.coexistenceResidual = energy ? value - target : DeterministicMath.log(target / value);
            return null;
        }
        beta = Math.clamp(beta, 0.0, 1.0);
        boolean band = criticalBand.inBand(t, p, w.request);
        String why = band ? criticalBand.bandReason(t, p, w.request, w, Double.NaN) : null;
        String note = "pure coexistence at the equal-fugacity point (" + t + " K, " + p + " Pa): vapour fraction " + beta
                + " from the " + (energy ? "energy" : "volume") + " balance";
        List<PhaseAmounts> phases;
        Classification classification;
        if (beta == 0.0 || beta == 1.0) {
            boolean vapourOnly = beta == 1.0;
            classification = vapourOnly ? Classification.SINGLE_VAPOR : Classification.SINGLE_LIQUID;
            PhaseState only = vapourOnly ? vapour : liquid;
            phases = List.of(new PhaseAmounts(vapourOnly ? PhaseKind.VAPOR : PhaseKind.LIQUID, null, t, p, w.request.basisAmounts,
                    only.copy(), only.traits()));
        } else {
            int slot = basisOfComponent[w.request.present[0]];
            double amount = w.request.amounts[w.request.present[0]];
            double[] vapourAmounts = new double[basis];
            double[] liquidAmounts = new double[basis];
            // The smaller share by its own formula, the larger by difference: conserved to rounding.
            if (beta <= 0.5) {
                vapourAmounts[slot] = beta * amount;
                liquidAmounts[slot] = amount - vapourAmounts[slot];
            } else {
                liquidAmounts[slot] = (1.0 - beta) * amount;
                vapourAmounts[slot] = amount - liquidAmounts[slot];
            }
            classification = Classification.VAPOR_LIQUID;
            phases = List.of(new PhaseAmounts(PhaseKind.VAPOR, null, t, p, vapourAmounts, vapour.copy(), vapour.traits()),
                    new PhaseAmounts(PhaseKind.LIQUID, null, t, p, liquidAmounts, liquid.copy(), liquid.traits()));
        }
        return engine.converged(competition, classification, phases, List.of(), diagnostics(w, null, band), why, note);
    }

    /**
     * The pure component's equal-fugacity temperature at {@code p}, from {@code t}: Newton on
     * {@code g = ln phi_L - ln phi_V}, {@code dg/dT = (h^R_V - h^R_L)/(R T^2)} (the translation cancels), bisection once
     * both signs are seen; NaN when no liquid-vapour root pair is met or it does not converge.
     */
    private double coexistenceTemperature(double t, double p, Workspace w) {
        int only = w.request.present[0];
        double below = Double.NaN;
        double above = Double.NaN;
        for (int k = 0; k < COEXISTENCE_ITERATIONS; k++) {
            kernel.evaluate(t, p, w.request.amounts, Root.LIQUID, w.mixture, w.first);
            kernel.evaluate(t, p, w.request.amounts, Root.VAPOR, w.mixture, w.second);
            w.outerKernel += 2;
            double next;
            boolean liquidRoot = w.first.physicalRootCount() > 1 || PhaseIdentification.liquidLike(w.first, t, p);
            boolean vapourRoot = w.second.physicalRootCount() > 1 || !PhaseIdentification.liquidLike(w.second, t, p);
            if (!liquidRoot || !vapourRoot) {
                // One root: the liquid-like one lies below the equal-fugacity temperature.
                if (liquidRoot) below = t; else above = t;
                if (Double.isNaN(below) || Double.isNaN(above)) return Double.NaN;
                next = 0.5 * (below + above);
            } else {
                double g = w.first.logFugacityCoefficient(only) - w.second.logFugacityCoefficient(only);
                double slope = (w.second.residualEnthalpyJoulesPerMol() - w.first.residualEnthalpyJoulesPerMol()) / (R * t * t);
                if (g == 0.0) return t;
                if (g < 0.0) below = t; else above = t;
                next = t - g / slope;
                boolean bracketed = !Double.isNaN(below) && !Double.isNaN(above);
                if (!(slope > 0.0) || !Double.isFinite(next) || bracketed && !(next > below && next < above)) {
                    if (!bracketed) return Double.NaN;
                    next = 0.5 * (below + above);
                }
            }
            if (Math.abs(next - t) <= 1.0e-14 * t) return next;
            t = next;
        }
        return Double.NaN;
    }

    /**
     * The pure component's equal-fugacity pressure at {@code t}, from {@code p}: Newton in {@code ln P} on
     * {@code g = ln phi_L - ln phi_V}, {@code dg/d ln P = Z_L - Z_V}, bisection once both signs are seen; NaN when no
     * root pair is met or it does not converge.
     */
    private double coexistencePressure(double t, double p, Workspace w) {
        int only = w.request.present[0];
        double low = Double.NaN;
        double high = Double.NaN;
        double y = DeterministicMath.log(p);
        for (int k = 0; k < COEXISTENCE_ITERATIONS; k++) {
            double pressure = DeterministicMath.exp(y);
            kernel.evaluate(t, pressure, w.request.amounts, Root.LIQUID, w.mixture, w.first);
            kernel.evaluate(t, pressure, w.request.amounts, Root.VAPOR, w.mixture, w.second);
            w.outerKernel += 2;
            double next;
            boolean liquidRoot = w.first.physicalRootCount() > 1 || PhaseIdentification.liquidLike(w.first, t, pressure);
            boolean vapourRoot = w.second.physicalRootCount() > 1 || !PhaseIdentification.liquidLike(w.second, t, pressure);
            if (!liquidRoot || !vapourRoot) {
                // One root: the liquid-like one lies above the equal-fugacity pressure, the vapour-like one below.
                if (liquidRoot) high = y; else low = y;
                if (Double.isNaN(low) || Double.isNaN(high)) return Double.NaN;
                next = 0.5 * (low + high);
            } else {
                double g = w.first.logFugacityCoefficient(only) - w.second.logFugacityCoefficient(only);
                if (g == 0.0) return pressure;
                if (g > 0.0) low = y; else high = y;
                double slope = w.first.compressibility() - w.second.compressibility();
                next = y - g / slope;
                boolean bracketed = !Double.isNaN(low) && !Double.isNaN(high);
                if (!(slope < 0.0) || !Double.isFinite(next) || bracketed && !(next > low && next < high)) {
                    if (!bracketed) return Double.NaN;
                    next = 0.5 * (low + high);
                }
            }
            if (Math.abs(next - y) <= 1.0e-14) return DeterministicMath.exp(next);
            y = next;
        }
        return Double.NaN;
    }

    /** Whether a converged answer holds a crystal, among its phases or its two coexisting states. */
    private static boolean holdsKind(EquilibriumResult result, PhaseKind kind) {
        if (result == null || !result.converged()) return false;
        for (PhaseAmounts phase : result.phases()) if (phase.kind() == kind) return true;
        return false;
    }

    /**
     * The competing crystal of the feed's one major species, or null: the only species present (a pure feed), or the
     * largest of a dry near-pure feed whose other species total at most {@link #NEAR_PURE_TRACES} of it (D21).
     */
    Crystal pureCrystal(Workspace w) {
        if (w.activeCount == 0) return null;
        int major = majorSpecies(w);
        if (major < 0) return null;
        for (int a = 0; a < w.activeCount; a++) if (w.active[a].component() == major) return w.active[a];
        return null;
    }

    /** The feed's one major species (see {@link #pureCrystal}), or -1. */
    private static int majorSpecies(Workspace w) {
        if (w.request.presentCount == 1) return w.request.present[0];
        if (w.request.presentCount == 0) return -1;
        int major = w.request.present[0];
        for (int a = 1; a < w.request.presentCount; a++) if (w.request.amounts[w.request.present[a]] > w.request.amounts[major]) major = w.request.present[a];
        return traces(major, w) <= NEAR_PURE_TRACES * w.hydrocarbonTotal ? major : -1;
    }

    /** The feed's amount of every species but {@code major}, mol: the traces of a near-pure feed (D21), 0 for a pure one. */
    private static double traces(int major, Workspace w) {
        double others = 0.0;
        for (int a = 0; a < w.request.presentCount; a++) if (w.request.present[a] != major) others += w.request.amounts[w.request.present[a]];
        return others;
    }

    /**
     * The competing crystal of a dry binary that carries its species (and no other crystal's), or null (P5). A
     * pseudo-binary counts: two species above {@link #TRACE_FRACTION} of the feed, the others traces (a network vessel
     * carries the nitrogen trace of an injection), whose three-phase region at fixed T and P is as thin as their share. A
     * near-pure feed whose major species has the crystal is not one (D21: its pure species' steps take precedence).
     */
    Crystal binaryCrystal(Workspace w) {
        if (w.activeCount == 0 || w.request.presentCount < 2 || pureCrystal(w) != null) return null;
        int majors = 0;
        for (int a = 0; a < w.request.presentCount; a++) if (w.request.amounts[w.request.present[a]] > TRACE_FRACTION * w.hydrocarbonTotal) majors++;
        if (majors != 2) return null;
        Crystal found = null;
        for (int a = 0; a < w.activeCount; a++) {
            Crystal c = w.active[a];
            if (!(w.request.amounts[c.component()] > 0.0)) continue;
            if (found != null) return null;
            found = c;
        }
        if (found == null || !(w.request.amounts[found.component()] > TRACE_FRACTION * w.hydrocarbonTotal)) return null;
        return found;
    }

    /** The other major species of a pseudo-binary with crystal species {@code i} (see {@link #binaryCrystal}). */
    private int binaryPartner(int i, Workspace w) {
        for (int a = 0; a < w.request.presentCount; a++) {
            int k = w.request.present[a];
            if (k != i && w.request.amounts[k] > TRACE_FRACTION * w.hydrocarbonTotal) return k;
        }
        throw new IllegalStateException("no partner");
    }

    /**
     * The pure species of a competing crystal at its equal-chemical-potential point with its own fluid (PH at the fixed
     * pressure, UV at the fixed temperature): below the family's triple point the sublimation step with the vapour, above
     * it the melting step with the liquid. The energy or volume balance determines the split,
     * {@code target = N [(1 - beta) x_s + beta x_F]}; as {@link #pureCoexistence}, the answer when {@code beta} lies in
     * {@code [0, 1]}, otherwise null with the jump's end in {@code coexistenceX} and {@code coexistenceResidual}. A
     * near-pure feed (D21) takes the same step at the same point, its traces in the fluid ({@code beta} the fluid's share
     * of all the moles, the fluid evaluated at its own composition, a few re-evaluations converging the split), while the
     * traces are at most {@link #STEP_FLUID_TRACES} of that fluid; a target beyond that end is the general solve's, and a
     * closed search there is the typed {@link EquilibriumResult#CRYSTAL_STEP} hold.
     */
    EquilibriumResult pureCrystalStep(Specification specification, double t, double p, double target, boolean clamp,
                                      PhaseCompetition competition, Workspace w) {
        w.coexistenceX = Double.NaN;
        Crystal c = pureCrystal(w);
        if (c == null) return null;
        boolean energy = specification == Specification.PH;
        // The step's fluid by the triple point (a crystal denser than its liquid melts above T_t at every pressure), and a
        // liquid where the bracket's fluid end is one (ice, lighter than water, also melts below T_t above P_t, W3a D5).
        BracketSearch s = energy ? w.search : w.inner;
        boolean liquidEnd = holdsKind(s.lowerResult, PhaseKind.LIQUID) || holdsKind(s.upperResult, PhaseKind.LIQUID);
        boolean vapourEnd = holdsKind(s.lowerResult, PhaseKind.VAPOR) || holdsKind(s.upperResult, PhaseKind.VAPOR);
        boolean melting = (energy ? p > c.triplePressure() : t > c.tripleTemperature()) || liquidEnd && !vapourEnd;
        Root root = melting ? Root.LIQUID : Root.VAPOR;
        if (energy) t = crystalStepTemperature(p, t, c, root, w); else p = crystalStepPressure(t, p, c, root, w);
        if (Double.isNaN(t) || Double.isNaN(p)) return null;
        int i = c.component();
        String species = evaluator.components().get(i);
        PhaseState fluid = evaluator.evaluate(t, p, w.request.amounts, root, w.phases, w.vaporScratch);
        PhaseState solid = c.anchored().evaluate(t, p, w.solidState);
        w.outerKernel++;
        double n = w.hydrocarbonTotal;
        double traces = traces(i, w);
        double solidValue = energy ? solid.balanceEnthalpy() : solid.balanceVolume();
        double fluidValue = energy ? fluid.balanceEnthalpy() : fluid.balanceVolume();
        double gap = fluidValue - solidValue;
        double beta = (target / n - solidValue) / gap;
        // D21: the smallest fluid share that keeps the traces within STEP_FLUID_TRACES of it (0 for a pure feed).
        double lowest = traces / (STEP_FLUID_TRACES * n);
        if (traces > 0.0) {
            for (int k = 0; k < NEAR_PURE_ITERATIONS && beta >= lowest && beta <= 1.0; k++) {
                fluid = stepFluid(t, p, i, beta * n - traces, root, w);
                fluidValue = energy ? fluid.balanceEnthalpy() : fluid.balanceVolume();
                gap = fluidValue - solidValue;
                double next = (target / n - solidValue) / gap;
                boolean converged = Math.abs(next - beta) <= 4.0 * Math.ulp(beta);
                beta = next;
                if (converged) break;
            }
        }
        double belowAllowance = Math.abs(SPECIFICATION_TOLERANCE * (energy ? R * t : solidValue) / gap);
        double aboveAllowance = Math.abs(SPECIFICATION_TOLERANCE * (energy ? R * t : fluidValue) / gap);
        if (!clamp && (beta < lowest - belowAllowance || beta > 1.0 + aboveAllowance) || !Double.isFinite(beta)) {
            boolean solidEnd = beta < lowest;
            double value = !solidEnd ? n * fluidValue
                    : traces > 0.0 ? n * ((1.0 - lowest) * solidValue + lowest * fluidValue) : n * solidValue;
            w.coexistenceX = energy ? t : DeterministicMath.log(p);
            w.coexistenceResidual = energy ? value - target : DeterministicMath.log(target / value);
            return null;
        }
        if (traces > 0.0 && beta < lowest - belowAllowance) {
            // A closed search on the crystal step with the target beyond the traces' end of it (D21 option C).
            return crystalStepHold("the specified " + (energy ? "enthalpy " + target + " J" : "volume " + target + " m3")
                    + " needs a fluid of more than " + STEP_FLUID_TRACES + " traces beside the crystal at the pure " + species
                    + " step (" + t + " K, " + p + " Pa; fluid share " + beta + ", the traces' end " + lowest + ")", competition, w);
        }
        beta = Math.clamp(beta, lowest, 1.0);
        String note = "pure " + (melting ? "melting" : "sublimation") + " of " + species + " at the equal-chemical-potential point ("
                + t + " K, " + p + " Pa): " + (melting ? "liquid" : "vapour") + " fraction " + beta + " from the "
                + (energy ? "energy" : "volume") + " balance"
                + (traces > 0.0 ? "; near-pure (D21): " + traces + " mol of other species in the " + (melting ? "liquid" : "vapour") : "");
        int slot = c.slot();
        double amount = w.request.amounts[i];
        PhaseKind fluidKind = melting ? PhaseKind.LIQUID : PhaseKind.VAPOR;
        List<PhaseAmounts> phases;
        Classification classification;
        EquilibriumResult.Deposition deposition = null;
        if (beta == 1.0) {
            if (traces > 0.0) fluid = stepFluid(t, p, i, amount, root, w);
            classification = !melting ? Classification.SINGLE_VAPOR
                    : fluid.physicalRootCount() > 1 ? Classification.SINGLE_LIQUID : Classification.SINGLE_FLUID;
            phases = List.of(new PhaseAmounts(fluidKind, null, t, p, w.request.basisAmounts, fluid.copy(), fluid.traits()));
        } else if (beta == 0.0) {
            classification = Classification.SOLID_PRESENT;
            phases = List.of(new PhaseAmounts(PhaseKind.SOLID, c.id(), t, p, w.request.basisAmounts, solid.copy(), null));
            deposition = new EquilibriumResult.Deposition(c.id(), species, Double.NaN, Double.NaN, amount, true, 0, 0);
        } else {
            double[] fluidAmounts = new double[basis];
            double[] solidAmounts = new double[basis];
            // The traces stay in the fluid (D21; none for a pure feed, whose n is the species' amount).
            for (int a = 0; a < w.request.presentCount; a++) {
                int j = w.request.present[a];
                if (j != i) fluidAmounts[basisOfComponent[j]] = w.request.amounts[j];
            }
            // The smaller share by its own formula, the larger by difference: conserved to rounding.
            if (beta <= 0.5) {
                fluidAmounts[slot] = beta * n - traces;
                solidAmounts[slot] = amount - fluidAmounts[slot];
            } else {
                solidAmounts[slot] = (1.0 - beta) * n;
                fluidAmounts[slot] = amount - solidAmounts[slot];
            }
            classification = melting ? Classification.LIQUID_SOLID : Classification.VAPOR_SOLID;
            phases = List.of(new PhaseAmounts(fluidKind, null, t, p, fluidAmounts, fluid.copy(), fluid.traits()),
                    new PhaseAmounts(PhaseKind.SOLID, c.id(), t, p, solidAmounts, solid.copy(), null));
            deposition = new EquilibriumResult.Deposition(c.id(), species, Double.NaN, Double.NaN, solidAmounts[slot], false, 0, 0);
        }
        return new EquilibriumResult(Specification.TP, EquilibriumResult.Status.CONVERGED, null, CONVERGED + "; " + note,
                contract.identity(), competition, classification, phases, List.of(), crystalEquilibrium.solidCoverage(convergedCoverage, w),
                diagnostics(w, null, false), deposition);
    }

    /**
     * The fluid a near-pure crystal step leaves (D21): the feed's traces with {@code amount} of species {@code i},
     * evaluated at (t, p) on {@code root} into the workspace's vapour scratch state (the one {@link #pureCrystalStep}'s
     * fluid is in).
     */
    private PhaseState stepFluid(double t, double p, int i, double amount, Root root, Workspace w) {
        System.arraycopy(w.request.amounts, 0, w.stepFluid, 0, count);
        w.stepFluid[i] = amount;
        w.outerKernel++;
        return evaluator.evaluate(t, p, w.stepFluid, root, w.phases, w.vaporScratch);
    }

    /** The typed hold of a crystal step the near-pure rule does not answer (D21 option C, {@link EquilibriumResult#CRYSTAL_STEP}). */
    private EquilibriumResult crystalStepHold(String why, PhaseCompetition competition, Workspace w) {
        return EquilibriumResult.notConverged(Specification.TP, contract.identity(), competition, FailureKind.CRYSTAL_STEP, false,
                EquilibriumResult.CRYSTAL_STEP + ": " + why, diagnostics(w, null, false));
    }

    /**
     * The temperature at {@code p} where the crystal's pure species and its fluid on {@code root} (the vapour for the
     * sublimation line, the liquid for the melting line, P5) have equal chemical potentials on this family: Newton in
     * {@code x = 1/T} on {@code g = (mu_F - mu_s)/(R T)} with {@code dg/dx = (h_F - h_s)/R}, bisection once both signs are
     * seen, within the numerical box's floor and the crystal's ceiling; converged at {@code |dx| <= 1e-14 x}. {@code NaN}
     * when it does not converge. The kernel calls count into {@code outerKernel}.
     */
    double crystalStepTemperature(double p, double start, Crystal c, Root root, Workspace w) {
        int i = c.component();
        Arrays.fill(w.pure, 0.0);
        w.pure[i] = 1.0;
        // The numerical box's floor to the crystal's ceiling, where it ceases to exist: the record and the ideal gas continue
        // past their data ranges (owner rule of 2026-10-16).
        double low = NumericalBox.MINIMUM_TEMPERATURE;
        double high = c.anchored().record().maximumTemperature();
        double t = Math.clamp(Double.isFinite(start) ? start : cold(p, c), low, high);
        double warm = Double.NaN;
        double cold = Double.NaN;
        for (int k = 0; k < COEXISTENCE_ITERATIONS; k++) {
            PhaseState fluid = evaluator.evaluate(t, p, w.pure, root, w.phases, w.pureState);
            PhaseState solid = c.anchored().evaluate(t, p, w.solidState);
            w.outerKernel++;
            double x = 1.0 / t;
            double g = (fluid.chemicalPotential(i) - solid.molarGibbsEnergy()) / (R * t);
            if (g == 0.0) return t;
            if (g > 0.0) cold = x; else warm = x;
            double next = x - g * R / (fluid.molarEnthalpy() - solid.molarEnthalpy());
            boolean bracketed = !Double.isNaN(cold) && !Double.isNaN(warm);
            if (!Double.isFinite(next) || bracketed && !(next > warm && next < cold)) {
                if (!bracketed) return Double.NaN;
                next = 0.5 * (warm + cold);
            }
            next = Math.clamp(next, 1.0 / high, 1.0 / low);
            if (Math.abs(next - x) <= 1.0e-14 * x) return 1.0 / next;
            if (next == x) return Double.NaN;
            t = 1.0 / next;
        }
        return Double.NaN;
    }

    /**
     * The pressure at {@code t} where the crystal's pure species and its fluid on {@code root} have equal chemical
     * potentials on this family (the sublimation pressure on the vapour root, the melting pressure on the liquid root,
     * P5): Newton in {@code ln P} on {@code g = (mu_F - mu_s)/(R T)} with {@code dg/d ln P = P (v_F - v_s)/(R T)}, either
     * sign of {@code v_F - v_s}, bisection once both signs are seen; converged at {@code |d ln P| <= 1e-14}; {@code NaN} when it does not converge.
     */
    double crystalStepPressure(double t, double start, Crystal c, Root root, Workspace w) {
        int i = c.component();
        Arrays.fill(w.pure, 0.0);
        w.pure[i] = 1.0;
        var anchor = c.anchored().anchor();
        double y;
        if (Double.isFinite(start) && start > 0.0) {
            y = DeterministicMath.log(start);
        } else {
            // Clausius-Clapeyron from the triple point with the ideal-gas sublimation enthalpy there.
            double sublimation = evaluator.idealGas(i).enthalpy(anchor.temperature())
                    - (anchor.liquidEnthalpy() - c.anchored().record().anchor().fusionEnthalpy());
            y = DeterministicMath.log(anchor.pressure()) - sublimation / R * (1.0 / t - 1.0 / anchor.temperature());
        }
        double maximum = DeterministicMath.log(NumericalBox.MAXIMUM_PRESSURE);
        double low = Double.NaN;
        double high = Double.NaN;
        // The orientation of g in ln P: the sign of the volume of fusion or sublimation at the start (W3a D5: negative for
        // ice beside water, whose liquid is the denser phase), so the bracket reads sign * g as increasing.
        double sign = 0.0;
        for (int k = 0; k < COEXISTENCE_ITERATIONS; k++) {
            double p = DeterministicMath.exp(y);
            PhaseState fluid = evaluator.evaluate(t, p, w.pure, root, w.phases, w.pureState);
            PhaseState solid = c.anchored().evaluate(t, p, w.solidState);
            w.outerKernel++;
            if (sign == 0.0) sign = fluid.molarVolume() >= solid.molarVolume() ? 1.0 : -1.0;
            double g = sign * (fluid.chemicalPotential(i) - solid.molarGibbsEnergy()) / (R * t);
            if (g == 0.0) return p;
            if (g > 0.0) high = y; else low = y;
            double slope = sign * p * (fluid.molarVolume() - solid.molarVolume()) / (R * t);
            double next = y - g / slope;
            boolean bracketed = !Double.isNaN(low) && !Double.isNaN(high);
            if (!(slope > 0.0) || !Double.isFinite(next) || bracketed && !(next > low && next < high)) {
                if (!bracketed) return Double.NaN;
                next = 0.5 * (low + high);
            }
            next = Math.min(next, maximum);
            if (Math.abs(next - y) <= 1.0e-14) return DeterministicMath.exp(next);
            if (next == y) return Double.NaN;
            y = next;
        }
        return Double.NaN;
    }

    /** A start of the sublimation-temperature solve at {@code p}: Clausius-Clapeyron from the crystal's triple point. */
    private double cold(double p, Crystal c) {
        var anchor = c.anchored().anchor();
        double sublimation = evaluator.idealGas(c.component()).enthalpy(anchor.temperature())
                - (anchor.liquidEnthalpy() - c.anchored().record().anchor().fusionEnthalpy());
        return 1.0 / (1.0 / anchor.temperature() - R * DeterministicMath.log(p / anchor.pressure()) / sublimation);
    }

    /**
     * The pure species of a competing crystal at its family's triple point in UV: the vapour, the liquid and the crystal
     * at the family's triple point ({@link Crystal#tripleTemperature()}, {@link Crystal#triplePressure()}), with the
     * amounts from the three balances (moles, energy, volume). A target inside the triple point's triangle (every amount
     * nonnegative within the specification tolerance) has this answer, {@link Classification#VAPOR_LIQUID_SOLID}, before
     * any search (at that volume {@code U(T)} steps at the triple temperature); otherwise null. A near-pure feed (D21)
     * carries its traces in the larger fluid phase of the balances, that fluid evaluated at its own composition and the
     * balances solved again until the amounts settle; with more than {@link #STEP_FLUID_TRACES} of traces in it the
     * answer is the typed {@link EquilibriumResult#CRYSTAL_STEP} hold.
     */
    EquilibriumResult pureTriplePoint(double u, double v, PhaseCompetition competition, Workspace w) {
        Crystal c = pureCrystal(w);
        if (c == null) return null;
        double t = c.tripleTemperature();
        double p = c.triplePressure();
        int i = c.component();
        PhaseState vapour = evaluator.evaluate(t, p, w.request.amounts, Root.VAPOR, w.phases, w.threeVapour);
        PhaseState liquid = evaluator.evaluate(t, p, w.request.amounts, Root.LIQUID, w.phases, w.threeLiquid);
        PhaseState solid = c.anchored().evaluate(t, p, w.solidState);
        w.outerKernel += 2;
        double n = w.hydrocarbonTotal;
        double traces = traces(i, w);
        // n_V + n_L + n_S = N; n_V u_V + n_L u_L + n_S u_s = U; n_V v_V + n_L v_L + n_S v_s = V.
        double[] b = {n, u, v};
        double[] amounts = insideTriangle(tripleBalances(vapour, liquid, solid), b, t);
        if (amounts == null) return null;
        boolean inVapour = amounts[0] >= amounts[1];
        String species = evaluator.components().get(i);
        if (traces > 0.0) {
            // D21: the traces in the larger fluid at its own composition, the other fluid the pure species.
            if (!((inVapour ? amounts[0] : amounts[1]) * STEP_FLUID_TRACES >= traces)) return tripleHold(u, v, amounts, traces, species, competition, w);
            Arrays.fill(w.pure, 0.0);
            w.pure[i] = 1.0;
            for (int k = 0; k < NEAR_PURE_ITERATIONS; k++) {
                System.arraycopy(w.request.amounts, 0, w.stepFluid, 0, count);
                w.stepFluid[i] = (inVapour ? amounts[0] : amounts[1]) - traces;
                vapour = evaluator.evaluate(t, p, inVapour ? w.stepFluid : w.pure, Root.VAPOR, w.phases, w.threeVapour);
                liquid = evaluator.evaluate(t, p, inVapour ? w.pure : w.stepFluid, Root.LIQUID, w.phases, w.threeLiquid);
                w.outerKernel += 2;
                double[] next = insideTriangle(tripleBalances(vapour, liquid, solid), b, t);
                if (next == null) return null;
                boolean settled = Math.abs(next[0] - amounts[0]) <= 4.0 * Math.ulp(n) && Math.abs(next[2] - amounts[2]) <= 4.0 * Math.ulp(n);
                amounts = next;
                if (settled) break;
            }
            if (!((inVapour ? amounts[0] : amounts[1]) * STEP_FLUID_TRACES >= traces)) return tripleHold(u, v, amounts, traces, species, competition, w);
        }
        double nv = amounts[0];
        double nl = amounts[1];
        double ns = amounts[2];
        int slot = c.slot();
        List<PhaseAmounts> phases = new ArrayList<>(3);
        if (nv > 0.0) phases.add(new PhaseAmounts(PhaseKind.VAPOR, null, t, p, tripleAmounts(slot, nv, traces > 0.0 && inVapour, i, w), vapour.copy(),
                vapour.traits()));
        if (nl > 0.0) phases.add(new PhaseAmounts(PhaseKind.LIQUID, null, t, p, tripleAmounts(slot, nl, traces > 0.0 && !inVapour, i, w), liquid.copy(),
                liquid.traits()));
        if (ns > 0.0) phases.add(new PhaseAmounts(PhaseKind.SOLID, c.id(), t, p, engine.single(slot, ns), solid.copy(), null));
        Classification classification = crystalClassification(nv > 0.0, nl > 0.0, ns > 0.0);
        if (classification == null) return null;
        String note = "pure " + species + " at its family's triple point (" + t + " K, " + p + " Pa): vapour " + nv + ", liquid " + nl
                + ", " + c.id() + " " + ns + " mol from the mole, energy and volume balances"
                + (traces > 0.0 ? "; near-pure (D21): " + traces + " mol of other species in the " + (inVapour ? "vapour" : "liquid") : "");
        var deposition = ns > 0.0 ? new EquilibriumResult.Deposition(c.id(), species, Double.NaN, Double.NaN, ns, false, 0, 0) : null;
        EquilibriumResult answer = new EquilibriumResult(Specification.TP, EquilibriumResult.Status.CONVERGED, null, CONVERGED + "; " + note,
                contract.identity(), competition, classification, phases, List.of(), crystalEquilibrium.solidCoverage(convergedCoverage, w),
                diagnostics(w, null, false), deposition);
        return outerAnswer(Specification.UV, answer, u, v, true, null, w);
    }

    /**
     * The triple point's amounts (vapour, liquid, crystal) at {@code b = (N, U, V)} over the balances {@code a}
     * ({@link #tripleBalances}) at the triple temperature {@code t}: {@link #solve3}'s vapour and crystal with the liquid
     * from the mole balance, when every one is nonnegative within the specification tolerance. An amount below zero is set
     * to 0, the others keeping the moles, where the answer then still meets the specification: the energy to
     * {@link FluidTpEquilibrium#SPECIFICATION_TOLERANCE} of {@code N R T} and the volume in {@code ln V}. A tolerance in moles would not bound
     * the volume, the vapour's molar volume being the condensed phases' times 1e2 for CO2 and 2e5 for water at 611.657 Pa.
     * Otherwise null.
     */
    private static double[] insideTriangle(double[] a, double[] b, double t) {
        double[] solved = SmallDenseSolve.solve3(a, b);
        if (solved == null) return null;
        double n = b[0];
        double nv = Math.max(solved[0], 0.0);
        double ns = Math.max(solved[2], 0.0);
        double nl = n - nv - ns;
        if (solved[0] >= 0.0 && solved[2] >= 0.0 && nl >= 0.0) return new double[] {nv, nl, ns};
        if (nl < 0.0) {
            double scale = n / (nv + ns);
            nv *= scale;
            ns *= scale;
            nl = 0.0;
        }
        double energy = nv * a[3] + nl * a[4] + ns * a[5];
        double volume = nv * a[6] + nl * a[7] + ns * a[8];
        if (!(Math.abs(energy - b[1]) <= SPECIFICATION_TOLERANCE * n * R * t)
                || !(Math.abs(DeterministicMath.log(volume / b[2])) <= SPECIFICATION_TOLERANCE)) return null;
        return new double[] {nv, nl, ns};
    }

    /** The UV hold of a near-pure triple point whose fluids are too small for its traces (D21 option C), typed. */
    private EquilibriumResult tripleHold(double u, double v, double[] amounts, double traces, String species, PhaseCompetition competition,
                                         Workspace w) {
        return outerAnswer(Specification.UV, crystalStepHold("the specified energy and volume put near-pure " + species + " at its family's triple point with vapour "
                + amounts[0] + ", liquid " + amounts[1] + " and crystal " + amounts[2] + " mol, too little fluid for the " + traces
                + " mol of other species within " + STEP_FLUID_TRACES + " of it", competition, w), u, v, false, null, w);
    }

    /**
     * Whether {@code (N, U, V)} may lie inside crystal {@code c}'s triple-point triangle: the balances over its pure
     * corners ({@link Crystal#triangle()}) leave no amount below {@link #TRIANGLE_SCREEN} of {@code N} (a screen that
     * spares a search the triple point's evaluations; {@link #pureTriplePoint} decides on the feed's own states).
     */
    static boolean nearTriangle(double u, double v, double n, Crystal c) {
        double[] m = c.triangle();
        if (m == null) return false;
        double[] amounts = SmallDenseSolve.solve3(tripleBalances(m), new double[] {n, u, v});
        if (amounts == null) return false;
        for (double amount : amounts) if (!(amount >= -TRIANGLE_SCREEN * n)) return false;
        return true;
    }

    /** The triple point's mole, energy and volume balances over (vapour, liquid, crystal), row-major for {@link #solve3}. */
    private static double[] tripleBalances(PhaseState vapour, PhaseState liquid, PhaseState solid) {
        return new double[] {1.0, 1.0, 1.0, vapour.balanceInternalEnergy(), liquid.balanceInternalEnergy(), solid.balanceInternalEnergy(),
                vapour.balanceVolume(), liquid.balanceVolume(), solid.balanceVolume()};
    }

    /** The same balances over a triangle's corners, {@code (u_V, v_V, u_L, v_L, u_s, v_s)}. */
    private static double[] tripleBalances(double[] corners) {
        return new double[] {1.0, 1.0, 1.0, corners[0], corners[2], corners[4], corners[1], corners[3], corners[5]};
    }

    /**
     * A triple-point fluid phase's amounts: {@code amount} of the species alone, or (D21, {@code carrier}) the feed's
     * traces with the rest of {@code amount} of species {@code i}.
     */
    private double[] tripleAmounts(int slot, double amount, boolean carrier, int i, Workspace w) {
        if (!carrier) return engine.single(slot, amount);
        double[] amounts = new double[basis];
        double traces = 0.0;
        for (int a = 0; a < w.request.presentCount; a++) {
            int j = w.request.present[a];
            if (j == i) continue;
            amounts[basisOfComponent[j]] = w.request.amounts[j];
            traces += w.request.amounts[j];
        }
        amounts[slot] = amount - traces;
        return amounts;
    }

    /** The classification of a crystal answer by its phases (vapour, liquid, crystal), or null for none of the known sets. */
    private static Classification crystalClassification(boolean vapour, boolean liquid, boolean solid) {
        if (vapour && liquid && solid) return Classification.VAPOR_LIQUID_SOLID;
        if (vapour && solid) return Classification.VAPOR_SOLID;
        if (liquid && solid) return Classification.LIQUID_SOLID;
        if (vapour && liquid) return Classification.VAPOR_LIQUID;
        if (solid) return Classification.SOLID_PRESENT;
        if (vapour) return Classification.SINGLE_VAPOR;
        if (liquid) return Classification.SINGLE_LIQUID;
        return null;
    }

    /**
     * Whether a closed or narrow PH or UV bracket of a binary with a competing crystal straddles its three-phase
     * (vapour-liquid-crystal) step (P5): both ends converged, together holding a vapour, a liquid and the crystal, at
     * least one end the crystal. At fixed T and P a binary has the three phases only on its three-phase line, so
     * {@code H(T)} at fixed pressure and {@code V(P)} at fixed temperature step there, like a pure fluid's coexistence.
     */
    static boolean straddlesThreePhase(BracketSearch s) {
        if (s.lowerResult == null || s.upperResult == null) return false;
        boolean vapour = false, liquid = false, solid = false;
        for (EquilibriumResult end : new EquilibriumResult[] {s.lowerResult, s.upperResult}) {
            if (!end.converged()) return false;
            for (PhaseAmounts phase : end.phases()) {
                switch (phase.kind()) {
                    case SOLID -> solid = true;
                    case LIQUID -> liquid = true;
                    default -> vapour = true;
                }
            }
        }
        return vapour && liquid && solid;
    }

    /**
     * The three-phase step of a dry binary with a competing crystal: at the fixed pressure (PH) the temperature, at the
     * fixed temperature (UV) the pressure where the vapour, a liquid and the crystal coexist, and the three amounts from
     * the two species' balances and the energy or volume balance. The three-phase point is solved from the bracket's two
     * ends: Newton on {@code (X, ln x_i, ln y_i)} ({@code X = 1/T} for PH, {@code ln P} for UV) for
     * {@code (mu_i^L - mu_s, mu_i^V - mu_s, mu_j^L - mu_j^V)/(R T) = 0}, a finite-difference Jacobian, steps capped, to
     * {@code 1e-11}; it must lie inside the bracket with two distinct fluid phases. One free amount along
     * {@code d = (1 - x_i, -(1 - y_i), x_i - y_i)} keeps both species' balances and the third balance fixes it. As
     * {@link #pureCoexistence}: the answer when every amount is nonnegative within the tolerance (or clamped), otherwise
     * null with the step's end in {@code coexistenceX} and {@code coexistenceResidual}, NaN when no three-phase point
     * lies in the bracket. The liquid's other species and the crystal are taken by difference, so both species conserve.
     */
    EquilibriumResult binaryThreePhase(Specification specification, BracketSearch s, double t, double p, double target,
                                       boolean clamp, PhaseCompetition competition, Workspace w) {
        w.coexistenceX = Double.NaN;
        Crystal c = binaryCrystal(w);
        if (c == null || !straddlesThreePhase(s)) return null;
        boolean energy = specification == Specification.PH;
        int i = c.component();
        int j = binaryPartner(i, w);
        double x = Double.NaN;
        double y = Double.NaN;
        for (EquilibriumResult end : new EquilibriumResult[] {s.lowerResult, s.upperResult}) {
            for (PhaseAmounts phase : end.phases()) {
                if (phase.kind() == PhaseKind.LIQUID && Double.isNaN(x)) x = phase.state().moleFraction(i);
                else if (phase.kind() != PhaseKind.SOLID && phase.kind() != PhaseKind.LIQUID && Double.isNaN(y)) y = phase.state().moleFraction(i);
            }
        }
        if (!(x > 0.0 && x < 1.0 && y > 0.0 && y < 1.0)) return null;
        double lower = energy ? Math.min(s.lower, s.upper) : DeterministicMath.exp(Math.min(s.lower, s.upper));
        double upper = energy ? Math.max(s.lower, s.upper) : DeterministicMath.exp(Math.max(s.lower, s.upper));
        double[] u = {energy ? 1.0 / t : DeterministicMath.log(p), DeterministicMath.log(x), DeterministicMath.log(y)};
        double[] r = new double[3];
        double[] trial = new double[3];
        double[] shifted = new double[3];
        double[] jacobian = new double[9];
        boolean converged = false;
        for (int k = 0; k < THREE_PHASE_ITERATIONS; k++) {
            if (!threePhaseResidual(energy, energy ? p : t, u, c, i, j, r, w)) return null;
            double norm = Math.max(Math.abs(r[0]), Math.max(Math.abs(r[1]), Math.abs(r[2])));
            if (norm <= THREE_PHASE_TOLERANCE) {
                converged = true;
                break;
            }
            for (int col = 0; col < 3; col++) {
                double h = col == 0 ? 1.0e-7 * Math.abs(u[0]) + (energy ? 0.0 : 1.0e-7) : 1.0e-7;
                System.arraycopy(u, 0, shifted, 0, 3);
                shifted[col] += h;
                if (!threePhaseResidual(energy, energy ? p : t, shifted, c, i, j, trial, w)) return null;
                for (int row = 0; row < 3; row++) jacobian[3 * row + col] = (trial[row] - r[row]) / h;
            }
            double[] step = SmallDenseSolve.solve3(jacobian, new double[] {-r[0], -r[1], -r[2]});
            if (step == null) return null;
            double scale = 1.0;
            scale = Math.min(scale, (energy ? 0.02 * Math.abs(u[0]) : 0.2) / Math.max(Math.abs(step[0]), 1.0e-300));
            scale = Math.min(scale, 1.0 / Math.max(Math.abs(step[1]), 1.0e-300));
            scale = Math.min(scale, 1.0 / Math.max(Math.abs(step[2]), 1.0e-300));
            for (int a = 0; a < 3; a++) u[a] += scale * step[a];
            u[1] = Math.min(u[1], -1.0e-12);
            u[2] = Math.min(u[2], -1.0e-12);
        }
        if (!converged) return null;
        double t3 = energy ? 1.0 / u[0] : t;
        double p3 = energy ? p : DeterministicMath.exp(u[0]);
        double x3 = DeterministicMath.exp(u[1]);
        double y3 = DeterministicMath.exp(u[2]);
        double variable = energy ? t3 : p3;
        double slack = 1.0e-9 * variable;
        if (!(variable >= lower - slack && variable <= upper + slack)) return null;
        PhaseState vapour = evaluateBinary(t3, p3, y3, i, j, Root.VAPOR, w.threeVapour, w);
        PhaseState liquid = evaluateBinary(t3, p3, x3, i, j, Root.LIQUID, w.threeLiquid, w);
        PhaseState solid = c.anchored().evaluate(t3, p3, w.solidState);
        w.outerKernel += 2;
        // Two distinct fluid phases: not the trivial solution of one fluid at its onset.
        if (!(Math.abs(liquid.molarVolume() - vapour.molarVolume()) > 1.0e-3 * liquid.molarVolume()) || !(Math.abs(x3 - y3) > 1.0e-9)) return null;
        double ni = w.request.amounts[i];
        double nj = w.request.amounts[j];
        double vapourValue = energy ? vapour.balanceEnthalpy() : vapour.balanceVolume();
        double liquidValue = energy ? liquid.balanceEnthalpy() : liquid.balanceVolume();
        double solidValue = energy ? solid.balanceEnthalpy() : solid.balanceVolume();
        // Rows: species i, species j, the third balance.
        double[] a = {y3, x3, 1.0, 1.0 - y3, 1.0 - x3, 0.0, vapourValue, liquidValue, solidValue};
        double[] amounts = SmallDenseSolve.solve3(a, new double[] {ni, nj, target});
        if (amounts == null) return null;
        double n = ni + nj;
        double allowance = SPECIFICATION_TOLERANCE * n;
        boolean inside = amounts[0] >= -allowance && amounts[1] >= -allowance && amounts[2] >= -allowance;
        if (!inside && !clamp) {
            // The end of the step on the target's side: move along d (both species' balances kept) until a phase vanishes.
            double[] d = {1.0 - x3, -(1.0 - y3), x3 - y3};
            double third = d[0] * vapourValue + d[1] * liquidValue + d[2] * solidValue;
            double lambdaLow = Double.NEGATIVE_INFINITY, lambdaHigh = Double.POSITIVE_INFINITY;
            for (int a2 = 0; a2 < 3; a2++) {
                if (d[a2] > 0.0) lambdaLow = Math.max(lambdaLow, -amounts[a2] / d[a2]);
                else if (d[a2] < 0.0) lambdaHigh = Math.min(lambdaHigh, -amounts[a2] / d[a2]);
            }
            if (!(lambdaLow <= lambdaHigh)) return null;
            // amounts is at lambda = 0 (the target); the end nearer it.
            double lambda = 0.0 < lambdaLow ? lambdaLow : lambdaHigh;
            double value = target + lambda * third;
            w.coexistenceX = energy ? t3 : DeterministicMath.log(p3);
            w.coexistenceResidual = energy ? value - target : DeterministicMath.log(target / value);
            return null;
        }
        if (!inside) {
            double[] d = {1.0 - x3, -(1.0 - y3), x3 - y3};
            double lambdaLow = Double.NEGATIVE_INFINITY, lambdaHigh = Double.POSITIVE_INFINITY;
            for (int a2 = 0; a2 < 3; a2++) {
                if (d[a2] > 0.0) lambdaLow = Math.max(lambdaLow, -amounts[a2] / d[a2]);
                else if (d[a2] < 0.0) lambdaHigh = Math.min(lambdaHigh, -amounts[a2] / d[a2]);
            }
            if (!(lambdaLow <= lambdaHigh)) return null;
            double lambda = Math.clamp(0.0, lambdaLow, lambdaHigh);
            for (int a2 = 0; a2 < 3; a2++) amounts[a2] += lambda * d[a2];
        }
        double nv = Math.max(amounts[0], 0.0);
        double nl = Math.max(amounts[1], 0.0);
        double ns = Math.max(amounts[2], 0.0);
        // Per species: the vapour's and the liquid's own amounts, the rest by difference (both species conserved to rounding).
        double vi, vj, li, lj;
        if (nv == 0.0) {
            vi = 0.0;
            vj = 0.0;
            lj = nj;
            li = ns == 0.0 ? ni : Math.min(nl * x3, ni);
        } else if (nl == 0.0) {
            li = 0.0;
            lj = 0.0;
            vj = nj;
            vi = ns == 0.0 ? ni : Math.min(nv * y3, ni);
        } else {
            vi = Math.min(nv * y3, ni);
            vj = Math.min(nv * (1.0 - y3), nj);
            lj = nj - vj;
            li = ns == 0.0 ? ni - vi : Math.min(nl * x3, ni - vi);
        }
        double si = ns == 0.0 ? 0.0 : ni - vi - li;
        if (si < 0.0) {
            li += si;
            si = 0.0;
        }
        int slotI = basisOfComponent[i];
        int slotJ = basisOfComponent[j];
        double[] vapourAmounts = pair(slotI, vi, slotJ, vj);
        double[] liquidAmounts = pair(slotI, li, slotJ, lj);
        // Traces of a pseudo-binary: each split between the vapour and the liquid by its infinite-dilution K on the three-phase
        // states (equal fugacities to first order in the trace), the vapour's share directly and the liquid's by difference.
        for (int b = 0; b < w.request.presentCount; b++) {
            int k = w.request.present[b];
            if (k == i || k == j) continue;
            double amount = w.request.amounts[k];
            double vapourShare;
            if (vi + vj > 0.0 && li + lj > 0.0) {
                double kValue = DeterministicMath.exp(liquid.logFugacityCoefficient(k) - vapour.logFugacityCoefficient(k));
                double nv3 = vi + vj;
                double nl3 = li + lj;
                vapourShare = nv3 * kValue / (nv3 * kValue + nl3);
            } else {
                vapourShare = vi + vj > 0.0 ? 1.0 : 0.0;
            }
            vapourAmounts[basisOfComponent[k]] = amount * vapourShare;
            liquidAmounts[basisOfComponent[k]] = amount - vapourAmounts[basisOfComponent[k]];
        }
        List<PhaseAmounts> phases = new ArrayList<>(3);
        if (vi + vj > 0.0) phases.add(new PhaseAmounts(PhaseKind.VAPOR, null, t3, p3, vapourAmounts, vapour.copy(), vapour.traits()));
        if (li + lj > 0.0) phases.add(new PhaseAmounts(PhaseKind.LIQUID, null, t3, p3, liquidAmounts, liquid.copy(), liquid.traits()));
        if (si > 0.0) phases.add(new PhaseAmounts(PhaseKind.SOLID, c.id(), t3, p3, engine.single(c.slot(), si), solid.copy(), null));
        Classification classification = crystalClassification(vi + vj > 0.0, li + lj > 0.0, si > 0.0);
        if (classification == null) return null;
        String species = evaluator.components().get(i);
        String note = "three-phase point of the " + species + " binary with " + c.id() + " at (" + t3 + " K, " + p3 + " Pa): x = " + x3
                + ", y = " + y3 + "; vapour " + nv + ", liquid " + nl + ", crystal " + si + " mol from the species and "
                + (energy ? "energy" : "volume") + " balances";
        var deposition = si > 0.0 ? new EquilibriumResult.Deposition(c.id(), species, Double.NaN, Double.NaN, si, false, 0, 0) : null;
        return new EquilibriumResult(Specification.TP, EquilibriumResult.Status.CONVERGED, null, CONVERGED + "; " + note,
                contract.identity(), competition, classification, phases, List.of(), crystalEquilibrium.solidCoverage(convergedCoverage, w),
                diagnostics(w, null, false), deposition);
    }

    /**
     * A closed bracket's step answered by the lever rule (section 12 of the W3b plan, the binary's three-phase step
     * generalised): where the two ends of a closed PH or UV bracket differ by one fluid phase leaving and one entering, as
     * water's boiling under a heavy cut does (a step 3e-9 K wide), the answer is the ends' mixture {@code (1 - lambda) low +
     * lambda high} whose enthalpy (PH) or internal energy (UV, both ends at the specified volume) is the specification: the
     * phases the ends share are merged, the leaving phase comes from the lower end and the entering one from the upper.
     * Every component is conserved, as at both ends. A crystal is one of the phases like a fluid, matched by its id: ice
     * melting under oil at 273.15 K is the step between gas, oil and ice and gas, oil and water. Null where the ends do not
     * differ so or the specification lies outside them.
     */
    EquilibriumResult leverStep(Specification specification, BracketSearch s, double target, PhaseCompetition competition, Workspace w) {
        EquilibriumResult low = s.lowerResult, high = s.upperResult;
        if (low == null || high == null || !low.converged() || !high.converged()) return null;
        boolean energy = specification == Specification.PH;
        double lowValue = energy ? low.enthalpy() : low.internalEnergy(), highValue = energy ? high.enthalpy() : high.internalEnergy();
        double lambda = (target - lowValue) / (highValue - lowValue);
        if (!(lambda >= 0.0 && lambda <= 1.0)) return null;
        List<PhaseAmounts> lows = low.phases(), highs = high.phases();
        int[] partner = new int[highs.size()];
        boolean[] taken = new boolean[lows.size()];
        int entering = -1, leaving = -1;
        for (int h = 0; h < highs.size(); h++) {
            partner[h] = -1;
            double nearest = LEVER_MATCH;
            for (int l = 0; l < lows.size(); l++) {
                if (taken[l] || lows.get(l).kind() != highs.get(h).kind() || !Objects.equals(lows.get(l).crystal(), highs.get(h).crystal())) continue;
                double distance = compositionDistance(lows.get(l).amounts(), highs.get(h).amounts());
                if (distance < nearest) {
                    nearest = distance;
                    partner[h] = l;
                }
            }
            if (partner[h] >= 0) taken[partner[h]] = true;
            else if (entering < 0) entering = h;
            else return null;
        }
        for (int l = 0; l < lows.size(); l++) {
            if (taken[l]) continue;
            if (leaving >= 0) return null;
            leaving = l;
        }
        if (entering < 0 || leaving < 0) return null;
        List<PhaseAmounts> phases = new ArrayList<>(highs.size() + 1);
        for (int h = 0; h < highs.size(); h++) {
            PhaseAmounts up = highs.get(h);
            if (h == entering) {
                if (lambda > 0.0) phases.add(scaled(up, lambda, null));
                continue;
            }
            PhaseAmounts down = lows.get(partner[h]);
            double[] merged = down.amounts(), upper = up.amounts();
            for (int i = 0; i < merged.length; i++) merged[i] = (1.0 - lambda) * merged[i] + lambda * upper[i];
            phases.add(scaled(lambda < 0.5 ? down : up, 1.0, merged));
        }
        if (lambda < 1.0) phases.add(scaled(lows.get(leaving), 1.0 - lambda, null));
        // The vapour first, then the liquids lighter first.
        phases.sort((a, b) -> a.kind() == b.kind() ? Double.compare(b.state().molarVolume(), a.state().molarVolume())
                : Integer.compare(order(a.kind()), order(b.kind())));
        int liquids = 0, solids = 0;
        boolean vapour = false;
        PhaseAmounts crystal = null;
        for (PhaseAmounts phase : phases) {
            if (phase.kind() == PhaseKind.LIQUID) liquids++;
            else if (phase.kind() == PhaseKind.SOLID) { solids++; crystal = phase; }
            else vapour = true;
        }
        if (solids > 1 || phases.size() - solids > 3) return null;
        Classification classification = solids == 0
                ? vapour ? liquids == 2 ? Classification.VAPOR_LIQUID_LIQUID : liquids == 1 ? Classification.VAPOR_LIQUID : null
                        : liquids == 2 ? Classification.LIQUID_LIQUID : null
                : liquids == 2 ? vapour ? Classification.VAPOR_LIQUID_LIQUID_SOLID : Classification.LIQUID_LIQUID_SOLID
                : crystalClassification(vapour, liquids == 1, true);
        if (classification == null) return null;
        EquilibriumResult.Deposition deposition = null;
        if (crystal != null) {
            Crystal c = crystalSet.crystalById(crystal.crystal());
            double held = 0.0;
            for (double x : crystal.amountsView()) held += x;
            if (c != null && held > 0.0) deposition = new EquilibriumResult.Deposition(c.id(), evaluator.components().get(c.component()), Double.NaN, Double.NaN, held, false, 0, 0);
        }
        String note = "on the step between " + s.lower + " and " + s.upper + (energy ? " K" : " K at the specified volume")
                + " by the lever rule (" + lambda + " of the upper end)";
        return new EquilibriumResult(Specification.TP, EquilibriumResult.Status.CONVERGED, null, CONVERGED + "; " + note,
                contract.identity(), competition, classification, phases, List.of(), crystalEquilibrium.solidCoverage(convergedCoverage, w),
                diagnostics(w, null, false), deposition);
    }

    /** The order of the lever step's phases by kind: the vapour, the liquids, then a crystal. */
    private static int order(PhaseKind kind) { return kind == PhaseKind.SOLID ? 2 : kind == PhaseKind.LIQUID ? 1 : 0; }

    /** The largest mole-fraction difference of two phases' amounts. */
    private static double compositionDistance(double[] a, double[] b) {
        double ta = 0.0, tb = 0.0, distance = 0.0;
        for (double x : a) ta += x;
        for (double x : b) tb += x;
        if (!(ta > 0.0) || !(tb > 0.0)) return Double.POSITIVE_INFINITY;
        for (int i = 0; i < a.length; i++) distance = Math.max(distance, Math.abs(a[i] / ta - b[i] / tb));
        return distance;
    }

    /** {@code phase} with its amounts times {@code factor}, or {@code amounts} where given; its state and conditions kept. */
    private static PhaseAmounts scaled(PhaseAmounts phase, double factor, double[] amounts) {
        double[] n = amounts != null ? amounts : phase.amounts();
        if (amounts == null) for (int i = 0; i < n.length; i++) n[i] *= factor;
        return new PhaseAmounts(phase.kind(), phase.crystal(), phase.temperature(), phase.pressure(), n, phase.state(), phase.traits());
    }

    private double[] pair(int first, double a, int second, double b) {
        double[] amounts = new double[basis];
        amounts[first] = a;
        amounts[second] = b;
        return amounts;
    }

    /** The binary's phase of mole fraction {@code fraction} of species {@code i} (the rest {@code j}) on {@code root}. */
    private PhaseState evaluateBinary(double t, double p, double fraction, int i, int j, Root root, PhaseState out, Workspace w) {
        Arrays.fill(w.pure, 0.0);
        w.pure[i] = fraction;
        w.pure[j] = 1.0 - fraction;
        return evaluator.evaluate(t, p, w.pure, root, w.phases, out);
    }

    /**
     * The first major species of a (pseudo-)binary of an engine with water in the equation of state that has no
     * competing crystal (W3a D4), or -1: its vapour and two liquids coexist only on a line, so at fixed pressure
     * {@code H(T)} and at fixed temperature {@code V(P)} step there, as at {@link #binaryThreePhase}'s point.
     */
    int binaryFluid(Workspace w) {
        if (!inEquationOfState || w.request.presentCount < 2 || binaryCrystal(w) != null) return -1;
        int first = -1, majors = 0;
        for (int a = 0; a < w.request.presentCount; a++) {
            int k = w.request.present[a];
            if (w.request.amounts[k] > TRACE_FRACTION * w.hydrocarbonTotal) {
                if (first < 0) first = k;
                majors++;
            }
        }
        return majors == 2 ? first : -1;
    }

    /**
     * Whether a bracket of a {@link #binaryFluid} feed straddles its vapour-liquid-liquid step: both ends converged, one
     * holding two liquids, one a vapour, none a crystal.
     */
    static boolean straddlesFluidThree(BracketSearch s) {
        if (s.lowerResult == null || s.upperResult == null) return false;
        boolean vapour = false, twoLiquids = false;
        for (EquilibriumResult end : new EquilibriumResult[] {s.lowerResult, s.upperResult}) {
            if (!end.converged()) return false;
            int liquids = 0;
            for (PhaseAmounts phase : end.phases()) {
                switch (phase.kind()) {
                    case SOLID -> {
                        return false;
                    }
                    case LIQUID -> liquids++;
                    default -> vapour = true;
                }
            }
            if (liquids >= 2) twoLiquids = true;
        }
        return vapour && twoLiquids;
    }

    /**
     * The vapour-liquid-liquid step of a {@link #binaryFluid} feed (W3a D4), {@link #binaryThreePhase}'s role with a
     * second liquid for the crystal: at the fixed pressure (PH) the temperature, at the fixed temperature (UV) the
     * pressure where a vapour and two liquids coexist, then the three amounts from the two species' balances and the
     * energy or volume balance (balance book). Newton on {@code (X, q_1, q_2, q_V)} ({@code X = 1/T} for PH, {@code ln P}
     * for UV; {@code q = ln(x_i/x_j)} per phase, which resolves a trace of either species) for the four equal chemical
     * potentials of each liquid with the vapour, a finite-difference Jacobian, steps capped, to {@code 1e-11}; it must lie
     * inside the bracket with three distinct phases. The answer, the step's end in {@code coexistenceX} and
     * {@code coexistenceResidual}, or null, as {@link #binaryThreePhase}. Each species' rounding goes to the phase holding
     * most of it; a trace splits over the phases by {@code n_p / phi_p}, equal fugacities to first order.
     */
    EquilibriumResult binaryFluidThreePhase(Specification specification, BracketSearch s, double t, double p, double target,
                                            boolean clamp, PhaseCompetition competition, Workspace w) {
        w.coexistenceX = Double.NaN;
        int i = binaryFluid(w);
        if (i < 0 || !straddlesFluidThree(s)) return null;
        int j = -1;
        for (int a = 0; a < w.request.presentCount; a++) {
            int k = w.request.present[a];
            if (k != i && w.request.amounts[k] > TRACE_FRACTION * w.hydrocarbonTotal) j = k;
        }
        boolean energy = specification == Specification.PH;
        int slotI = basisOfComponent[i], slotJ = basisOfComponent[j];
        Slots z = w.slots;
        double[] u = z.threeU;
        u[1] = u[2] = u[3] = Double.NaN;
        for (EquilibriumResult end : new EquilibriumResult[] {s.lowerResult, s.upperResult}) {
            int liquids = 0;
            for (PhaseAmounts phase : end.phases()) if (phase.kind() == PhaseKind.LIQUID) liquids++;
            for (PhaseAmounts phase : end.phases()) {
                double q = logRatio(phase.amount(slotI), phase.amount(slotJ));
                if (phase.kind() != PhaseKind.LIQUID) {
                    if (Double.isNaN(u[3])) u[3] = q;
                } else if (liquids >= 2) {
                    if (Double.isNaN(u[1]) || q > u[1]) {
                        if (!Double.isNaN(u[1])) u[2] = u[1];
                        u[1] = q;
                    } else if (Double.isNaN(u[2])) {
                        u[2] = q;
                    }
                }
            }
        }
        if (Double.isNaN(u[1]) || Double.isNaN(u[2]) || Double.isNaN(u[3])) return null;
        double lower = energy ? Math.min(s.lower, s.upper) : DeterministicMath.exp(Math.min(s.lower, s.upper));
        double upper = energy ? Math.max(s.lower, s.upper) : DeterministicMath.exp(Math.max(s.lower, s.upper));
        u[0] = energy ? 1.0 / t : DeterministicMath.log(p);
        double[] r = z.threeR, trial = z.threeTrial, shifted = z.threeShifted, jacobian = z.threeJacobian, step = z.threeStep;
        double fixed = energy ? p : t;
        boolean converged = false;
        for (int k = 0; k < THREE_PHASE_ITERATIONS; k++) {
            if (!fluidThreeResidual(energy, fixed, u, i, j, r, w)) return null;
            double norm = 0.0;
            for (int a = 0; a < 4; a++) norm = Math.max(norm, Math.abs(r[a]));
            if (norm <= THREE_PHASE_TOLERANCE) {
                converged = true;
                break;
            }
            for (int col = 0; col < 4; col++) {
                double h = col == 0 ? 1.0e-7 * Math.abs(u[0]) + (energy ? 0.0 : 1.0e-7) : 1.0e-7 * Math.max(1.0, Math.abs(u[col]));
                System.arraycopy(u, 0, shifted, 0, 4);
                shifted[col] += h;
                if (!fluidThreeResidual(energy, fixed, shifted, i, j, trial, w)) return null;
                for (int row = 0; row < 4; row++) jacobian[4 * row + col] = (trial[row] - r[row]) / h;
            }
            for (int a = 0; a < 4; a++) step[a] = -r[a];
            if (!SmallDenseSolve.solvePivoted(jacobian, step, 4)) return null;
            double scale = Math.min(1.0, (energy ? 0.02 * Math.abs(u[0]) : 0.2) / Math.max(Math.abs(step[0]), 1.0e-300));
            for (int a = 1; a < 4; a++) scale = Math.min(scale, 2.0 / Math.max(Math.abs(step[a]), 1.0e-300));
            for (int a = 0; a < 4; a++) u[a] += scale * step[a];
        }
        if (!converged) return null;
        double t3 = energy ? 1.0 / u[0] : t;
        double p3 = energy ? p : DeterministicMath.exp(u[0]);
        double variable = energy ? t3 : p3;
        double slack = 1.0e-9 * variable;
        if (!(variable >= lower - slack && variable <= upper + slack)) return null;
        PhaseState vapour = evaluateRatio(t3, p3, u[3], i, j, Root.VAPOR, w.threeVapour, w);
        PhaseState first = evaluateRatio(t3, p3, u[1], i, j, Root.LIQUID, w.threeLiquid, w);
        PhaseState second = evaluateRatio(t3, p3, u[2], i, j, Root.LIQUID, z.states[2], w);
        w.outerKernel += 3;
        // Three distinct phases: not one fluid taken twice at its onset.
        if (!(Math.abs(u[1] - u[2]) > 1.0e-6 && Math.abs(u[1] - u[3]) > 1.0e-6 && Math.abs(u[2] - u[3]) > 1.0e-6)
                || !(vapour.molarVolume() > 1.5 * Math.max(first.molarVolume(), second.molarVolume()))) {
            return null;
        }
        PhaseState[] states = {vapour, first, second};
        double ni = w.request.amounts[i], nj = w.request.amounts[j];
        double[] a = new double[9];
        for (int c = 0; c < 3; c++) {
            a[c] = states[c].moleFraction(i);
            a[3 + c] = states[c].moleFraction(j);
            a[6 + c] = energy ? states[c].balanceEnthalpy() : states[c].balanceVolume();
        }
        double[] amounts = SmallDenseSolve.solve3(a, new double[] {ni, nj, target});
        if (amounts == null) return null;
        // The direction that keeps both species' balances: the cross product of their rows.
        double[] d = {a[1] * a[5] - a[2] * a[4], a[2] * a[3] - a[0] * a[5], a[0] * a[4] - a[1] * a[3]};
        double allowance = SPECIFICATION_TOLERANCE * (ni + nj);
        boolean inside = amounts[0] >= -allowance && amounts[1] >= -allowance && amounts[2] >= -allowance;
        if (!inside) {
            double lambdaLow = Double.NEGATIVE_INFINITY, lambdaHigh = Double.POSITIVE_INFINITY;
            for (int c = 0; c < 3; c++) {
                if (d[c] > 0.0) lambdaLow = Math.max(lambdaLow, -amounts[c] / d[c]);
                else if (d[c] < 0.0) lambdaHigh = Math.min(lambdaHigh, -amounts[c] / d[c]);
            }
            if (!(lambdaLow <= lambdaHigh)) return null;
            if (!clamp) {
                double third = d[0] * a[6] + d[1] * a[7] + d[2] * a[8];
                double lambda = 0.0 < lambdaLow ? lambdaLow : lambdaHigh;
                double value = target + lambda * third;
                w.coexistenceX = energy ? t3 : DeterministicMath.log(p3);
                w.coexistenceResidual = energy ? value - target : DeterministicMath.log(target / value);
                return null;
            }
            double lambda = Math.clamp(0.0, lambdaLow, lambdaHigh);
            for (int c = 0; c < 3; c++) amounts[c] += lambda * d[c];
        }
        double[][] split = new double[3][];
        for (int c = 0; c < 3; c++) {
            amounts[c] = Math.max(amounts[c], 0.0);
            split[c] = pair(slotI, amounts[c] * a[c], slotJ, amounts[c] * a[3 + c]);
        }
        conserve(split, slotI, ni);
        conserve(split, slotJ, nj);
        for (int b = 0; b < w.request.presentCount; b++) {
            int k = w.request.present[b];
            if (k == i || k == j) continue;
            double weights = 0.0;
            for (int c = 0; c < 3; c++) weights += amounts[c] * DeterministicMath.exp(-states[c].logFugacityCoefficient(k));
            for (int c = 0; c < 3; c++) {
                split[c][basisOfComponent[k]] = weights > 0.0
                        ? w.request.amounts[k] * amounts[c] * DeterministicMath.exp(-states[c].logFugacityCoefficient(k)) / weights : 0.0;
            }
            conserve(split, basisOfComponent[k], w.request.amounts[k]);
        }
        List<PhaseAmounts> phases = new ArrayList<>(3);
        boolean hasVapour = amounts[0] > 0.0;
        int liquids = 0;
        if (hasVapour) phases.add(new PhaseAmounts(PhaseKind.VAPOR, null, t3, p3, split[0], vapour.copy(), vapour.traits()));
        int lighter = first.molarVolume() >= second.molarVolume() ? 1 : 2;
        for (int c : new int[] {lighter, 3 - lighter}) {
            if (amounts[c] > 0.0) {
                phases.add(new PhaseAmounts(PhaseKind.LIQUID, null, t3, p3, split[c], states[c].copy(), states[c].traits()));
                liquids++;
            }
        }
        Classification classification = hasVapour ? liquids == 2 ? Classification.VAPOR_LIQUID_LIQUID
                : liquids == 1 ? Classification.VAPOR_LIQUID : Classification.SINGLE_VAPOR
                : liquids == 2 ? Classification.LIQUID_LIQUID : liquids == 1 ? Classification.SINGLE_LIQUID : null;
        if (classification == null) return null;
        String note = "vapour-liquid-liquid point of the " + evaluator.components().get(i) + " and " + evaluator.components().get(j)
                + " binary at (" + t3 + " K, " + p3 + " Pa); vapour " + amounts[0] + ", liquids " + amounts[1] + " and " + amounts[2]
                + " mol from the species and " + (energy ? "energy" : "volume") + " balances";
        return new EquilibriumResult(Specification.TP, EquilibriumResult.Status.CONVERGED, null, CONVERGED + "; " + note,
                contract.identity(), competition, classification, phases, List.of(), crystalEquilibrium.solidCoverage(convergedCoverage, w),
                diagnostics(w, null, false), null);
    }

    /** {@code ln(a/b)}, held to +-46 (a fraction of 1e-20) where one of them is zero. */
    private static double logRatio(double a, double b) {
        if (!(a > 0.0)) return -46.0;
        if (!(b > 0.0)) return 46.0;
        return Math.clamp(DeterministicMath.log(a / b), -46.0, 46.0);
    }

    /** Puts the rounding of {@code slot}'s balance over three phase amounts into the phase that holds most of it. */
    private static void conserve(double[][] split, int slot, double total) {
        int most = 0;
        for (int c = 1; c < 3; c++) if (split[c][slot] > split[most][slot]) most = c;
        double rest = total;
        for (int c = 0; c < 3; c++) if (c != most) rest -= split[c][slot];
        split[most][slot] = Math.max(rest, 0.0);
    }

    /** The binary's phase with {@code q = ln(x_i/x_j)} on {@code root}, both fractions resolved near either pure end. */
    private PhaseState evaluateRatio(double t, double p, double q, int i, int j, Root root, PhaseState out, Workspace w) {
        Arrays.fill(w.pure, 0.0);
        w.pure[i] = 1.0 / (1.0 + DeterministicMath.exp(-q));
        w.pure[j] = 1.0 / (1.0 + DeterministicMath.exp(q));
        return evaluator.evaluate(t, p, w.pure, root, w.phases, out);
    }

    /**
     * The residuals of the binary vapour-liquid-liquid point at {@code u = (X, q_1, q_2, q_V)}: each species' chemical
     * potential in each liquid less the vapour's, over {@code R T}; false when a state is refused.
     */
    private boolean fluidThreeResidual(boolean energy, double fixed, double[] u, int i, int j, double[] r, Workspace w) {
        double t = energy ? 1.0 / u[0] : fixed;
        double p = energy ? fixed : DeterministicMath.exp(u[0]);
        if (!Double.isFinite(t) || !Double.isFinite(p) || !(t > 0.0) || !(p > 0.0)) return false;
        try {
            PhaseState vapour = evaluateRatio(t, p, u[3], i, j, Root.VAPOR, w.threeVapour, w);
            PhaseState first = evaluateRatio(t, p, u[1], i, j, Root.LIQUID, w.threeLiquid, w);
            PhaseState second = evaluateRatio(t, p, u[2], i, j, Root.LIQUID, w.slots.states[2], w);
            w.outerKernel += 3;
            double rt = R * t;
            r[0] = (first.chemicalPotential(i) - vapour.chemicalPotential(i)) / rt;
            r[1] = (first.chemicalPotential(j) - vapour.chemicalPotential(j)) / rt;
            r[2] = (second.chemicalPotential(i) - vapour.chemicalPotential(i)) / rt;
            r[3] = (second.chemicalPotential(j) - vapour.chemicalPotential(j)) / rt;
            for (int a = 0; a < 4; a++) if (!Double.isFinite(r[a])) return false;
            return true;
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException refused) {
            return false;
        }
    }

    /**
     * The residuals of the binary three-phase point at {@code u = (X, ln x_i, ln y_i)} ({@code X = 1/T} with the pressure
     * fixed, or {@code ln P} with the temperature fixed): {@code (mu_i^L - mu_s)/(R T)}, {@code (mu_i^V - mu_s)/(R T)},
     * {@code (mu_j^L - mu_j^V)/(R T)}; false when a state is refused.
     */
    private boolean threePhaseResidual(boolean energy, double fixed, double[] u, Crystal c, int i, int j, double[] r, Workspace w) {
        double t = energy ? 1.0 / u[0] : fixed;
        double p = energy ? fixed : DeterministicMath.exp(u[0]);
        double x = DeterministicMath.exp(u[1]);
        double y = DeterministicMath.exp(u[2]);
        if (!(x < 1.0 && y < 1.0) || !Double.isFinite(t) || !Double.isFinite(p) || !(t > 0.0) || !(p > 0.0)) return false;
        try {
            PhaseState liquid = evaluateBinary(t, p, x, i, j, Root.LIQUID, w.threeLiquid, w);
            PhaseState vapour = evaluateBinary(t, p, y, i, j, Root.VAPOR, w.threeVapour, w);
            double mu = c.anchored().chemicalPotential(t, p);
            w.outerKernel += 2;
            double rt = R * t;
            r[0] = (liquid.chemicalPotential(i) - mu) / rt;
            r[1] = (vapour.chemicalPotential(i) - mu) / rt;
            r[2] = (liquid.chemicalPotential(j) - vapour.chemicalPotential(j)) / rt;
            return Double.isFinite(r[0]) && Double.isFinite(r[1]) && Double.isFinite(r[2]);
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException refused) {
            return false;
        }
    }
}
