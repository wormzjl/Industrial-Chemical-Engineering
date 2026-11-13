package com.wormzjl.icheme.science.thermo.phase;

import static com.wormzjl.icheme.science.thermo.phase.BracketSearch.holdsSolid;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.PRESSURE_STEP_CAP;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.R;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.SPECIFICATION_TOLERANCE;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.WARM_UV_EVALUATIONS;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.crystalCeiling;

import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.thermo.IdealGasFunction;
import com.wormzjl.icheme.science.thermo.NumericalBox;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.phase.CrystalSet.Crystal;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumRequest.Specification;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.Classification;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.Diagnostics;
import com.wormzjl.icheme.science.thermo.phase.EquilibriumResult.FailureKind;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.Settings;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.Workspace;
import java.util.Objects;

/**
 * The PH and UV searches of {@link FluidTpEquilibrium} over its TP answers (P3 WP6b; S01): the search window
 * ({@link #searchWindow}), the ideal-gas and one-phase starts, the bracketed PH search in {@code T} ({@link #solvePh},
 * {@link #closedPh}), the UV Newton on {@code (ln T, ln P)} ({@link #newtonUv}) and the nested UV search in {@code T}
 * ({@link #nestedUv}) with its pressure search at each temperature ({@link #innerUv}, {@link #closedInner}), and the
 * search's answers and failures ({@link #outerAnswer}, {@link #searchFailure}, {@link #outsideWindow}). Each search
 * runs on the workspace's two {@link BracketSearch}es; a step a bracket straddles or closes on is answered from the
 * balances by {@link BalanceSteps}, whose side of the step comes back in the workspace's {@code coexistenceX} and
 * {@code coexistenceResidual}. It reads the engine's contract, evaluator, kernel, settings, translations, crystal set and
 * critical band, given once at construction, and calls back into the engine only for {@code evaluateTp} (each search
 * step's TP answer) and the static {@code crystalCeiling}. Immutable; one instance per engine.
 */
final class SpecificationSearch {
    /** Consecutive halvings back toward the last evaluated state when a trial state has no TP answer. */
    private static final int MAXIMUM_RETREATS = 8;
    /**
     * Largest step of one UV Newton step in {@code ln T}, and of an unbracketed temperature step as a fraction of
     * {@code T} ({@link FluidTpEquilibrium#PRESSURE_STEP_CAP} is the step in {@code ln P}).
     */
    private static final double TEMPERATURE_STEP_CAP = 0.25;
    /** The UV Newton phase's finite-difference step in {@code ln T} and {@code ln P}. */
    private static final double DIFFERENCE_STEP = 1.0e-7;
    private static final int NEWTON_UV_EVALUATIONS = 30;
    private static final int LINE_SEARCH_HALVINGS = 4;
    /** The nested UV search's loosest volume solve, in {@code ln V}, while the energy is still far off. */
    private static final double INNER_LOOSENESS = 1.0e-3;
    // Outcomes of the UV pressure search at one temperature.
    private static final int INNER_FOUND = 0;
    private static final int INNER_ABOVE = 1;
    private static final int INNER_BELOW = 2;
    private static final int INNER_FAILED = 3;
    /** A PH (relative in T) or UV (in ln P) bracket this narrow that straddles a binary's three-phase step is solved there (P5). */
    private static final double THREE_PHASE_BRACKET = 0.02;

    private final FluidTpEquilibrium engine;
    private final BalanceSteps balanceSteps;
    private final PhaseContract contract;
    private final CubicPhaseEvaluator evaluator;
    private final PengRobinsonKernel kernel;
    private final Settings settings;
    private final int count;
    /** The evaluator's constant volume translations (the engine's), per component. */
    private final double[] translations;
    private final CrystalSet crystalSet;
    private final CriticalBand criticalBand;

    SpecificationSearch(FluidTpEquilibrium engine, BalanceSteps balanceSteps, PhaseContract contract, CubicPhaseEvaluator evaluator,
                        PengRobinsonKernel kernel, Settings settings, int count, double[] translations, CrystalSet crystalSet,
                        CriticalBand criticalBand) {
        this.engine = engine;
        this.balanceSteps = balanceSteps;
        this.contract = contract;
        this.evaluator = evaluator;
        this.kernel = kernel;
        this.settings = settings;
        this.count = count;
        this.translations = translations;
        this.crystalSet = crystalSet;
        this.criticalBand = criticalBand;
    }

    // PH and UV (P3 WP6b).

    /**
     * The search window: the numerical box ({@link NumericalBox}, where the mathematics stops; no data range bounds it);
     * resets the search's counters and state. A search starts inside the data of every model the material uses, where
     * they overlap (the package envelope, the carried species' ideal-gas functions and the steam's range), since the
     * first temperature is a lower bound from the ideal gas, which a latent heat can put far below the answer (35 K for a
     * frost answer at 150 K); from inside the data the search moves out only as far as the specification asks.
     */
    void searchWindow(Workspace w) {
        w.minimumT = NumericalBox.MINIMUM_TEMPERATURE;
        w.maximumT = NumericalBox.MAXIMUM_TEMPERATURE;
        w.minimumP = NumericalBox.MINIMUM_PRESSURE;
        w.maximumP = NumericalBox.MAXIMUM_PRESSURE;
        w.outerEvaluations = 0;
        w.outerKernel = 0;
        w.outerDerivative = 0;
        w.jacobianValid = false;
        w.bestT = Double.NaN;
        w.bestResidual = Double.POSITIVE_INFINITY;
        w.innerPressure = Double.NaN;
        w.innerResult = null;
        w.innerCoexistence = false;
        w.innerFailure = null;
        w.innerFailureKind = null;
        w.innerLooseness = 0.0;
        w.innerSlope = 1.0;
        w.innerLoose = false;
        w.innerSingle = true;
        w.innerT1 = w.innerY1 = w.innerT2 = w.innerY2 = Double.NaN;
        // The composition and present list as the hydrocarbon core sets them (the band and the coexistence solves).
        w.request.presentCount = 0;
        for (int i = 0; i < count; i++) {
            w.request.z[i] = w.hydrocarbonTotal > 0.0 ? w.request.amounts[i] / w.hydrocarbonTotal : 0.0;
            if (w.request.amounts[i] > 0.0) w.request.present[w.request.presentCount++] = i;
        }
        double[] envelope = contract.domain().envelope();
        double low = envelope[0];
        double high = envelope[1];
        for (int a = 0; a < w.request.presentCount; a++) {
            IdealGasFunction gas = evaluator.idealGas(w.request.present[a]);
            low = Math.max(low, gas.minimumTemperature());
            high = Math.min(high, gas.maximumTemperature());
        }
        if (!(low <= high)) {
            low = envelope[0];
            high = envelope[1];
        }
        w.startMinimumT = Math.clamp(low, w.minimumT, w.maximumT);
        w.startMaximumT = Math.clamp(high, w.minimumT, w.maximumT);
    }

    /**
     * One TP equilibrium of a search, counted. A failure of a pure fluid inside its critical band (the kernel refusing a
     * root or a derivative at coalescence, where the plan promises no finite smooth derivatives) is typed
     * {@code CRITICAL_BAND}, so a search that ends on it says so.
     */
    private EquilibriumResult outerTp(double t, double p, PhaseCompetition competition, Workspace w) {
        EquilibriumResult result = engine.evaluateTp(t, p, competition, w);
        w.outerEvaluations++;
        w.outerKernel += w.kernelEvaluations;
        w.outerDerivative += w.derivativeEvaluations;
        if (result.status() == EquilibriumResult.Status.NOT_CONVERGED && !result.diagnostics().criticalBand()
                && w.hydrocarbonTotal > 0.0 && criticalBand.inBand(t, p, w.request)) {
            Diagnostics d = result.diagnostics();
            result = EquilibriumResult.notConverged(Specification.TP, contract.identity(), competition, result.failureKind(), true,
                    EquilibriumResult.CRITICAL_BAND + " (critical band: pure fluid near its critical point): " + result.detail(),
                    new Diagnostics(d.feedVerdict(), d.feedTangentPlaneDistance(), d.flashIterations(), d.extrapolations(),
                            d.rachfordRiceIterations(), d.lnKChange(), d.fugacityResidual(),
                            d.productTangentPlaneDistance(), d.kernelEvaluations(), d.derivativeEvaluations(),
                            d.newtonIterations(), d.tieLine(), true));
        }
        return result;
    }

    private static boolean usable(EquilibriumResult result) {
        return result.converged() && result.classification() != Classification.PURE_COEXISTENCE_UNDERDETERMINED;
    }

    /**
     * {@code T} of {@code sum_i n_i h_ig,i(T) = target} (or of the internal energy {@code h_ig - R T}), water as ideal
     * steam, inside the data where the search starts ({@link #searchWindow}): the search's first temperature. Newton on the
     * ideal-gas functions only.
     */
    private double idealTemperature(double target, boolean internal, Workspace w) {
        double t = Math.clamp(298.15, w.startMinimumT, w.startMaximumT);
        for (int k = 0; k < 60; k++) {
            double f = -target;
            double slope = 0.0;
            for (int a = 0; a < w.request.presentCount; a++) {
                int i = w.request.present[a];
                IdealGasFunction gas = evaluator.idealGas(i);
                f += w.request.amounts[i] * gas.enthalpy(t);
                slope += w.request.amounts[i] * gas.heatCapacity(t);
            }
            if (internal) {
                f -= w.total * R * t;
                slope -= w.total * R;
            }
            if (!(slope > 0.0) || !Double.isFinite(f)) break;
            double next = Math.clamp(t - f / slope, w.startMinimumT, w.startMaximumT);
            boolean done = Math.abs(next - t) <= 1.0e-9 * t;
            t = next;
            if (done) break;
        }
        return t;
    }

    /** {@code sum_i n_i c_p,ig,i(T)}, water as steam; minus {@code N R} for the isochoric one: a positive slope scale. */
    private double idealHeatCapacity(double t, boolean isochoric, Workspace w) {
        double sum = 0.0;
        for (int a = 0; a < w.request.presentCount; a++) {
            int i = w.request.present[a];
            sum += w.request.amounts[i] * evaluator.idealGas(i).heatCapacity(t);
        }
        return isochoric ? sum - w.total * R : sum;
    }

    /** The derivatives of a dry answer with one phase (its own root), or null (several phases, water, a refusal). */
    private PhaseDerivatives dryDerivatives(EquilibriumResult result, double t, double p, Workspace w) {
        if (result.phases().size() != 1) return null;
        PhaseAmounts only = result.phases().get(0);
        if (only.kind() == PhaseKind.SOLID) {
            // A crystal alone (P4 stage 2a): its own c_p, expansion and compressibility.
            Crystal c = crystalSet.crystalById(only.crystal());
            if (c == null) return null;
            w.outerDerivative++;
            return c.anchored().derivatives(t, p, w.solidSlope);
        }
        w.outerKernel++;
        w.outerDerivative++;
        try {
            return evaluator.derivatives(t, p, w.request.amounts, result.phases().get(0).state().root(), w.phases, w.slope);
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException refused) {
            // At root coalescence (a pure critical point) the kernel promises no derivative: the search takes a secant.
            return null;
        }
    }

    /** {@code (dH/dT)_P} of one dry phase, {@code NaN} otherwise. */
    private double dryHeatCapacity(EquilibriumResult result, double t, double p, Workspace w) {
        PhaseDerivatives d = dryDerivatives(result, t, p, w);
        return d == null ? Double.NaN : w.total * d.balanceHeatCapacity();
    }

    /** {@code (dU/dT)_V} of one dry phase, {@code NaN} otherwise. */
    private double dryIsochoricHeatCapacity(EquilibriumResult result, double t, Workspace w) {
        double p = result.phases().get(0).pressure();
        PhaseDerivatives d = dryDerivatives(result, t, p, w);
        if (d == null) return Double.NaN;
        double dvdt = d.balanceVolumeTemperatureDerivative();
        double dvdp = d.balanceVolumePressureDerivative();
        double dudt = d.balanceHeatCapacity() - p * dvdt;
        double dudp = d.balanceEnthalpyPressureDerivative() - d.state().balanceVolume() - p * dvdp;
        return w.total * (dudt - dudp * dvdt / dvdp);
    }

    /** {@code -(d ln V/d ln P)_T} of one dry phase, {@code NaN} otherwise. */
    private double dryCompressibility(EquilibriumResult result, double t, double p, Workspace w) {
        PhaseDerivatives d = dryDerivatives(result, t, p, w);
        return d == null ? Double.NaN : -p * d.balanceVolumePressureDerivative() / d.state().balanceVolume();
    }

    private double energyResidual(EquilibriumResult result, double target, double t, Workspace w) {
        return (result.internalEnergy() - target) / (w.total * R * t);
    }

    // PH.

    EquilibriumResult solvePh(double p, double target, PhaseCompetition competition, Workspace w) {
        BracketSearch s = w.search;
        s.reset(w.minimumT, w.maximumT);
        double tolerance = settings.outerTolerance();
        double t = idealTemperature(target, false, w);
        double previousT = Double.NaN;
        double previousResidual = Double.NaN;
        int previousPhases = -1;
        double lastT = Double.NaN;
        EquilibriumResult last = null;
        int retreats = 0;
        int warmer = 0;
        while (true) {
            if (w.outerEvaluations >= settings.maximumOuterIterations()) {
                return searchFailure(Specification.PH, competition, FailureKind.SEARCH, "no convergence of the PH temperature search in "
                        + w.outerEvaluations + " TP equilibria (bracket " + s.lower + " .. " + s.upper + " K)", last, w);
            }
            EquilibriumResult result = outerTp(t, p, competition, w);
            if (!result.converged()) {
                if (Double.isNaN(lastT) && w.activeCount > 0 && t < crystalCeiling(w)) {
                    // P4 stage 2a: the ideal-gas start ignores a crystal's enthalpy and can land where a crystal meets a
                    // liquid (a typed hold); the search starts again from the competition's ceiling, where no crystal forms.
                    t = crystalCeiling(w);
                    continue;
                }
                if (Double.isNaN(lastT) && t < w.maximumT && ++warmer <= MAXIMUM_RETREATS) {
                    // The start is a lower bound of the answer's temperature (the ideal gas's, which a latent heat puts far
                    // below it): a start the equilibrium does not answer (a phase set it does not carry) lies below the
                    // answer, so the search starts again warmer.
                    t = Math.min(1.25 * t, w.maximumT);
                    continue;
                }
                if (Double.isNaN(lastT) || ++retreats > MAXIMUM_RETREATS) {
                    return outerAnswer(Specification.PH, result, target, Double.NaN, false,
                            "PH search: the TP equilibrium at " + t + " K", w);
                }
                t = 0.5 * (t + lastT);
                continue;
            }
            retreats = 0;
            double residual;
            boolean coexistence = false;
            if (result.classification() == Classification.PURE_COEXISTENCE_UNDERDETERMINED) {
                EquilibriumResult answer = holdsSolid(result) ? balanceSteps.pureCrystalStep(Specification.PH, t, p, target, false, competition, w)
                        : balanceSteps.pureCoexistence(Specification.PH, t, p, target, false, competition, w);
                if (answer != null) return outerAnswer(Specification.PH, answer, target, Double.NaN, true, null, w);
                if (Double.isNaN(w.coexistenceX)) {
                    return searchFailure(Specification.PH, competition, FailureKind.SEARCH, "no equal-fugacity temperature found near " + t
                            + " K at " + p + " Pa", result, w);
                }
                t = w.coexistenceX;
                residual = w.coexistenceResidual;
                result = null;
                coexistence = true;
            } else {
                residual = result.enthalpy() - target;
                last = result;
            }
            lastT = t;
            s.record(t, residual, result, coexistence);
            double slope = result == null ? Double.NaN : dryHeatCapacity(result, t, p, w);
            int phases = result == null ? -1 : result.phases().size();
            if (!(slope > 0.0) || !Double.isFinite(slope)) {
                // A secant only between answers with the same phases (across a bubble or dew point H(T) has a kink);
                // otherwise the bracket's own secant, before a bracket the ideal-gas heat capacity.
                slope = Double.isNaN(previousT) || previousT == t || phases != previousPhases ? Double.NaN
                        : (residual - previousResidual) / (t - previousT);
                if (!(slope > 0.0) || !Double.isFinite(slope)) slope = s.bracketSlope();
            }
            if (!(slope > 0.0) || !Double.isFinite(slope)) slope = idealHeatCapacity(t, false, w);
            previousPhases = phases;
            double step = -residual / slope;
            if (result != null && Math.abs(residual) <= SPECIFICATION_TOLERANCE * w.total * R * t
                    && Math.abs(step) <= tolerance * t) {
                return outerAnswer(Specification.PH, result, target, Double.NaN, false, null, w);
            }
            if (s.bracketed() && (s.width() <= tolerance * t || w.request.presentCount == 1 && s.straddlesPureCoexistence()
                    || balanceSteps.pureCrystal(w) != null && s.straddlesCrystalStep()
                    || balanceSteps.binaryCrystal(w) != null && threePhaseDue(s, s.width() / t)
                    || balanceSteps.binaryFluid(w) >= 0 && fluidThreeDue(s, s.width() / t))) {
                EquilibriumResult closed = closedPh(p, target, competition, w);
                if (closed != null) return closed;
                // The coexistence lies beyond the jump (its side is recorded), or was not met yet: the search goes on.
                if (!Double.isNaN(w.coexistenceX)) {
                    t = w.coexistenceX;
                    s.record(t, w.coexistenceResidual, null, true);
                }
                previousT = Double.NaN;
                t = s.next(t, Double.NaN, Double.POSITIVE_INFINITY);
                continue;
            }
            if (!s.bracketed()) {
                if (t <= s.minimum && residual > 0.0) {
                    return outsideWindow(Specification.PH, true, true,
                            "the specified enthalpy " + target + " J", competition, last, w);
                }
                if (t >= s.maximum && residual < 0.0) {
                    return outsideWindow(Specification.PH, true, false,
                            "the specified enthalpy " + target + " J", competition, last, w);
                }
            }
            previousT = t;
            previousResidual = residual;
            // With crystals (P4 stage 2a) an unbracketed step is capped: a vapour's c_p says nothing of the heat of deposition.
            t = s.next(t, step, w.activeCount > 0 ? TEMPERATURE_STEP_CAP * t : Double.POSITIVE_INFINITY);
        }
    }

    /**
     * The PH bracket closed (or straddles a pure component's liquid and vapour): the root, or a step of {@code H(T)}.
     * Returns the answer, or null when the search goes on: a pure coexistence lies beyond the jump (its side in
     * {@code coexistenceX} and {@code coexistenceResidual}), or the bracket is not closed yet and no equal-fugacity
     * temperature was met ({@code coexistenceX} NaN).
     */
    private EquilibriumResult closedPh(double p, double target, PhaseCompetition competition, Workspace w) {
        BracketSearch s = w.search;
        double t = 0.5 * (s.lower + s.upper);
        double jump = s.upperResidual - s.lowerResidual;
        boolean closed = s.width() <= settings.outerTolerance() * t;
        w.coexistenceX = Double.NaN;
        if (closed && jump <= SPECIFICATION_TOLERANCE * w.total * R * t && s.best() != null) {
            return outerAnswer(Specification.PH, s.best(), target, Double.NaN, s.bestCoexistence(), null, w);
        }
        boolean crystalStep = balanceSteps.pureCrystal(w) != null && s.endHoldsSolid();
        if (crystalStep || w.request.presentCount == 1) {
            EquilibriumResult answer = crystalStep ? balanceSteps.pureCrystalStep(Specification.PH, t, p, target, closed, competition, w)
                    : balanceSteps.pureCoexistence(Specification.PH, t, p, target, closed, competition, w);
            if (answer != null) return outerAnswer(Specification.PH, answer, target, Double.NaN, true, null, w);
            if (!Double.isNaN(w.coexistenceX) || !closed) return null;
        } else if (balanceSteps.binaryCrystal(w) != null && BalanceSteps.straddlesThreePhase(s)) {
            // P5: a binary's three-phase step (vapour, liquid and crystal at one temperature for the fixed pressure).
            EquilibriumResult answer = balanceSteps.binaryThreePhase(Specification.PH, s, t, p, target, closed, competition, w);
            if (answer != null) return outerAnswer(Specification.PH, answer, target, Double.NaN, true, null, w);
            if (!Double.isNaN(w.coexistenceX) || !closed) return null;
        } else if (balanceSteps.binaryFluid(w) >= 0 && BalanceSteps.straddlesFluidThree(s)) {
            // W3a D4: a binary's vapour-liquid-liquid step (at the fixed pressure, one temperature).
            EquilibriumResult answer = balanceSteps.binaryFluidThreePhase(Specification.PH, s, t, p, target, closed, competition, w);
            if (answer != null) return outerAnswer(Specification.PH, answer, target, Double.NaN, true, null, w);
            if (!Double.isNaN(w.coexistenceX) || !closed) return null;
        }
        // Not closed yet: the search goes on (the step is not where the enthalpy lies).
        if (!closed) return null;
        EquilibriumResult lever = balanceSteps.leverStep(Specification.PH, s, target, competition, w);
        if (lever != null) return outerAnswer(Specification.PH, lever, target, Double.NaN, true, null, w);
        return searchFailure(Specification.PH, competition, crystalStepKind(crystalStep), crystalStepType(crystalStep) + "the specified enthalpy " + target
                + " J lies in a step of H(T) at " + p + " Pa between " + s.lower + " and " + s.upper + " K (from "
                + (target + s.lowerResidual) + " to " + (target + s.upperResidual) + " J): no state of the model has it",
                s.lowerResult, w);
    }

    // UV.

    EquilibriumResult solveUv(double u, double v, double hintT, double hintP, PhaseCompetition competition, Workspace w) {
        // P5, D21: inside the triple point's triangle of the feed's one major species the answer is that triple point (or,
        // near-pure with too little fluid for its traces, the typed hold), whatever a search would meet on its way there.
        Crystal major = balanceSteps.pureCrystal(w);
        if (major != null && BalanceSteps.nearTriangle(u, v, w.hydrocarbonTotal, major)) {
            EquilibriumResult triple = balanceSteps.pureTriplePoint(u, v, competition, w);
            if (triple != null) return triple;
        }
        if (hintT >= w.minimumT && hintT <= w.maximumT && hintP >= w.minimumP && hintP <= w.maximumP) {
            // P6 warm start: the Newton phase from the previous answer, on a short budget; the cold search otherwise.
            EquilibriumResult warm = newtonUv(hintT, hintP, null, u, v, competition, w, WARM_UV_EVALUATIONS);
            if (warm != null) return warm;
            w.outerEvaluations = 0;
            w.jacobianValid = false;
            w.bestT = Double.NaN;
            w.bestResidual = Double.POSITIVE_INFINITY;
        }
        // Both are lower bounds of a two-phase answer's temperature (the residual energy is negative, and a split lies
        // below the single phase's energy at the same temperature and volume); for one phase the first is the answer.
        double single = singlePhaseTemperature(u, v, w);
        double ideal = idealTemperature(u, true, w);
        double t = Math.clamp(Double.isNaN(single) ? ideal : Math.max(single, ideal), w.startMinimumT, w.startMaximumT);
        double p = cubicPressure(t, v, w);
        EquilibriumResult first = null;
        if (Double.isNaN(p)) {
            // No stable single phase at the start (two phases): an inexact volume solve there first, so the
            // Newton phase starts on the specified volume (a liquid's volume barely moves with pressure).
            w.innerLooseness = INNER_LOOSENESS;
            int outcome = innerUv(t, v, competition, w);
            if (outcome == INNER_FOUND && w.innerCoexistence) {
                w.bestT = t;
                return nestedUv(u, v, competition, w);
            }
            if (outcome == INNER_FOUND) first = w.innerResult;
            p = outcome == INNER_FOUND ? w.innerPressure : w.total * R * t / v;
        }
        p = Math.clamp(p, w.minimumP, w.maximumP);
        EquilibriumResult answer = newtonUv(t, p, first, u, v, competition, w, settings.maximumOuterIterations());
        return answer != null ? answer : nestedUv(u, v, competition, w);
    }

    /**
     * The mixture's {@code a}, {@code b} and {@code da/dT} at {@code t} into {@code w.first} (one kernel call at the
     * ideal-gas pressure of the specified volume; the three do not depend on pressure). False when the kernel refuses.
     */
    private boolean mixtureParameters(double t, double v, Workspace w) {
        try {
            kernel.evaluate(t, w.hydrocarbonTotal * R * t / v, w.request.amounts, Root.VAPOR, w.mixture, w.first);
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException refused) {
            return false;
        }
        w.outerKernel++;
        return true;
    }

    /** The untranslated molar volume of the dry feed at total volume {@code v}: the constant translation removed. */
    private double untranslatedVolume(double v, Workspace w) {
        double shift = 0.0;
        for (int a = 0; a < w.request.presentCount; a++) shift += w.request.z[w.request.present[a]] * translations[w.request.present[a]];
        return v / w.hydrocarbonTotal - shift;
    }

    /**
     * The UV start of dry material (P3 WP6b): the temperature at which the feed as one phase of the cubic, at the
     * specified molar volume, has the specified internal energy. Kernel calls only, no equilibrium: on the untranslated
     * volume {@code v_u} (the constant translation cancels from {@code u = h - P v}) the PR departure is closed-form,
     * {@code u^R = (a - T da/dT) / (2 sqrt2 b) ln[(v_u + (1 - sqrt2) b) / (v_u + (1 + sqrt2) b)]}, and
     * {@code u(T) = sum z_i h_ig,i(T) - R T + u^R} increases with {@code T} at fixed volume; a safeguarded secant on
     * the window. NaN when the volume is not above the covolume or the solve fails. For a single-phase answer this is
     * the answer's temperature; inside a two-phase region it is a start of the right size.
     */
    private double singlePhaseTemperature(double u, double v, Workspace w) {
        if (!(w.hydrocarbonTotal > 0.0)) return Double.NaN;
        double target = u / w.hydrocarbonTotal;
        double volume = untranslatedVolume(v, w);
        double low = w.minimumT;
        double high = w.maximumT;
        double t = Math.clamp(idealTemperature(u, true, w), low, high);
        double previousT = Double.NaN;
        double previousValue = Double.NaN;
        final double root2 = Math.sqrt(2.0);
        for (int k = 0; k < 100; k++) {
            if (!mixtureParameters(t, v, w)) return Double.NaN;
            double a = w.first.aMix();
            double b = w.first.bMix();
            if (!(volume > b)) return Double.NaN;
            double departure = (a - t * w.first.daMixDt()) / (2.0 * root2 * b)
                    * DeterministicMath.log((volume + (1.0 - root2) * b) / (volume + (1.0 + root2) * b));
            double value = departure - R * t - target;
            for (int s = 0; s < w.request.presentCount; s++) {
                int i = w.request.present[s];
                value += w.request.z[i] * evaluator.idealGas(i).enthalpy(t);
            }
            if (!Double.isFinite(value)) return Double.NaN;
            if (value < 0.0) low = t; else high = t;
            double next;
            if (!Double.isNaN(previousT) && value != previousValue) {
                next = t - value * (t - previousT) / (value - previousValue);
            } else {
                next = t - value / (idealHeatCapacity(t, true, w) / w.total);
            }
            if (!(next > low && next < high)) {
                // The root lies beyond an end of the window: that end is the start.
                if (value > 0.0 && t <= w.minimumT || value < 0.0 && t >= w.maximumT) return t;
                next = 0.5 * (low + high);
            }
            if (Math.abs(next - t) <= 1.0e-12 * t) return next;
            previousT = t;
            previousValue = value;
            t = next;
        }
        return Double.NaN;
    }

    /**
     * The cubic's pressure {@code R T/(v_u - b) - a/(v_u^2 + 2 b v_u - b^2)} of the dry feed as one phase at temperature
     * {@code t} and total volume {@code v}; NaN unless positive and mechanically stable ({@code dP/dv < 0}): the start
     * of a pressure search, exact for a stable single phase.
     */
    private double cubicPressure(double t, double v, Workspace w) {
        if (!(w.hydrocarbonTotal > 0.0) || !mixtureParameters(t, v, w)) return Double.NaN;
        double volume = untranslatedVolume(v, w);
        double a = w.first.aMix();
        double b = w.first.bMix();
        if (!(volume > b)) return Double.NaN;
        double pressure = PengRobinsonKernel.cubicPressure(t, volume, a, b);
        double slope = PengRobinsonKernel.pressureVolumeDerivative(t, volume, a, b);
        return pressure > 0.0 && slope < 0.0 && Double.isFinite(pressure) ? pressure : Double.NaN;
    }

    /**
     * Newton on {@code x = (ln T, ln P)} for {@code F = ((U - U_spec)/(N R T), ln(V/V_spec))}: the analytic Jacobian of one
     * dry phase, otherwise finite differences once and Broyden's update after each step (refreshed by differences when
     * a step fails), the step capped in {@code ln T} and {@code ln P} and kept in the window, halvings until
     * {@code |F|^2} decreases. Null when it does not converge within its budget: the nested search takes over from its
     * best temperature.
     */
    private EquilibriumResult newtonUv(double t, double p, EquilibriumResult start, double u, double v,
                                       PhaseCompetition competition, Workspace w, int budget) {
        double tolerance = settings.outerTolerance();
        EquilibriumResult current = start != null ? start : outerTp(t, p, competition, w);
        if (!usable(current)) return null;
        double f0 = energyResidual(current, u, t, w);
        double f1 = DeterministicMath.log(current.volume() / v);
        noteBest(t, f0, f1, w);
        w.jacobianValid = false;
        boolean refresh = false;
        double[] j = w.jacobian;
        double lastRatio = 0.0;
        // Beyond NEWTON_UV_EVALUATIONS TP equilibria only while the last accepted step cut |F|^2 at least fourfold.
        while (w.outerEvaluations < budget && (w.outerEvaluations < NEWTON_UV_EVALUATIONS || lastRatio <= 0.25)) {
            boolean fresh = true;
            if (!analyticJacobian(current, t, p, f0, w)) {
                if (!w.jacobianValid || refresh) {
                    if (!differenceJacobian(t, p, u, v, f0, f1, competition, w)) return null;
                } else {
                    fresh = false;
                }
            }
            refresh = false;
            double determinant = j[0] * j[3] - j[1] * j[2];
            double d0 = -(j[3] * f0 - j[1] * f1) / determinant;
            double d1 = -(j[0] * f1 - j[2] * f0) / determinant;
            if (!Double.isFinite(d0) || !Double.isFinite(d1)) return null;
            double size = Math.max(Math.abs(d0), Math.abs(d1));
            boolean met = Math.abs(f0) <= SPECIFICATION_TOLERANCE && Math.abs(f1) <= SPECIFICATION_TOLERANCE;
            if (met && size <= tolerance) return outerAnswer(Specification.UV, current, u, v, false, null, w);
            double x0 = DeterministicMath.log(t);
            double x1 = DeterministicMath.log(p);
            double alpha = Math.min(1.0, Math.min(TEMPERATURE_STEP_CAP / Math.abs(d0), PRESSURE_STEP_CAP / Math.abs(d1)));
            alpha = Math.min(alpha, windowFraction(x0, d0, DeterministicMath.log(w.minimumT), DeterministicMath.log(w.maximumT)));
            alpha = Math.min(alpha, windowFraction(x1, d1, DeterministicMath.log(w.minimumP), DeterministicMath.log(w.maximumP)));
            if (!(alpha > 0.0)) return null;
            double norm = f0 * f0 + f1 * f1;
            boolean accepted = false;
            double tt = t;
            double pt = p;
            double g0 = Double.NaN;
            double g1 = Double.NaN;
            EquilibriumResult trial = null;
            for (int halving = 0; halving <= LINE_SEARCH_HALVINGS && w.outerEvaluations < budget; halving++, alpha *= 0.5) {
                tt = DeterministicMath.exp(x0 + alpha * d0);
                pt = DeterministicMath.exp(x1 + alpha * d1);
                trial = outerTp(tt, pt, competition, w);
                if (!usable(trial)) continue;
                g0 = energyResidual(trial, u, tt, w);
                g1 = DeterministicMath.log(trial.volume() / v);
                noteBest(tt, g0, g1, w);
                if (g0 * g0 + g1 * g1 <= (1.0 - 1.0e-4 * alpha) * norm) {
                    accepted = true;
                    break;
                }
            }
            if (!accepted) {
                // At the noise floor of the TP answers a converged point cannot be improved.
                if (met && size <= 10.0 * tolerance) return outerAnswer(Specification.UV, current, u, v, false, null, w);
                if (!fresh) {
                    refresh = true;
                    continue;
                }
                return null;
            }
            // Broyden's update for the next point when it is not one dry phase.
            double s0 = DeterministicMath.log(tt) - x0;
            double s1 = DeterministicMath.log(pt) - x1;
            double squared = s0 * s0 + s1 * s1;
            double r0 = g0 - f0 - (j[0] * s0 + j[1] * s1);
            double r1 = g1 - f1 - (j[2] * s0 + j[3] * s1);
            j[0] += r0 * s0 / squared;
            j[1] += r0 * s1 / squared;
            j[2] += r1 * s0 / squared;
            j[3] += r1 * s1 / squared;
            w.jacobianValid = true;
            lastRatio = (g0 * g0 + g1 * g1) / norm;
            current = trial;
            t = tt;
            p = pt;
            f0 = g0;
            f1 = g1;
        }
        return null;
    }

    private static void noteBest(double t, double f0, double f1, Workspace w) {
        double norm = f0 * f0 + f1 * f1;
        if (norm < w.bestResidual) {
            w.bestResidual = norm;
            w.bestT = t;
        }
    }

    /** The largest fraction of the step {@code d} from {@code x} that stays in {@code [low, high]}. */
    private static double windowFraction(double x, double d, double low, double high) {
        if (d > 0.0) return (high - x) / d;
        if (d < 0.0) return (low - x) / d;
        return Double.POSITIVE_INFINITY;
    }

    /** The UV Jacobian of one dry phase from its derivatives; false otherwise. */
    private boolean analyticJacobian(EquilibriumResult result, double t, double p, double f0, Workspace w) {
        PhaseDerivatives d = dryDerivatives(result, t, p, w);
        if (d == null) return false;
        double volume = d.state().balanceVolume();
        double dvdt = d.balanceVolumeTemperatureDerivative();
        double dvdp = d.balanceVolumePressureDerivative();
        double rt = R * t;
        w.jacobian[0] = t * (d.balanceHeatCapacity() - p * dvdt) / rt - f0;
        w.jacobian[1] = p * (d.balanceEnthalpyPressureDerivative() - volume - p * dvdp) / rt;
        w.jacobian[2] = t * dvdt / volume;
        w.jacobian[3] = p * dvdp / volume;
        w.jacobianValid = true;
        return true;
    }

    /** The UV Jacobian by forward differences of TP answers in {@code ln T} and {@code ln P}; false when one fails. */
    private boolean differenceJacobian(double t, double p, double u, double v, double f0, double f1,
                                       PhaseCompetition competition, Workspace w) {
        double ht = t * DeterministicMath.exp(DIFFERENCE_STEP) <= w.maximumT ? DIFFERENCE_STEP : -DIFFERENCE_STEP;
        double tt = t * DeterministicMath.exp(ht);
        EquilibriumResult a = outerTp(tt, p, competition, w);
        if (!usable(a)) return false;
        double hp = p * DeterministicMath.exp(DIFFERENCE_STEP) <= w.maximumP ? DIFFERENCE_STEP : -DIFFERENCE_STEP;
        double pp = p * DeterministicMath.exp(hp);
        EquilibriumResult b = outerTp(t, pp, competition, w);
        if (!usable(b)) return false;
        w.jacobian[0] = (energyResidual(a, u, tt, w) - f0) / ht;
        w.jacobian[2] = (DeterministicMath.log(a.volume() / v) - f1) / ht;
        w.jacobian[1] = (energyResidual(b, u, t, w) - f0) / hp;
        w.jacobian[3] = (DeterministicMath.log(b.volume() / v) - f1) / hp;
        w.jacobianValid = true;
        return true;
    }

    /**
     * The nested UV search: {@code U(T)} at the specified volume, increasing, bracketed in temperature; each temperature
     * solves the volume by {@link #innerUv}. A pressure outside the window at a temperature moves the bracket (at fixed
     * volume the pressure rises with temperature); a closed bracket there is out of domain in pressure.
     */
    private EquilibriumResult nestedUv(double u, double v, PhaseCompetition competition, Workspace w) {
        BracketSearch s = w.search;
        s.reset(w.minimumT, w.maximumT);
        double tolerance = settings.outerTolerance();
        double t = Double.isNaN(w.bestT) ? idealTemperature(u, true, w) : w.bestT;
        double previousT = Double.NaN;
        double previousResidual = Double.NaN;
        double lastT = Double.NaN;
        EquilibriumResult last = null;
        int retreats = 0;
        boolean strict = false;
        // Whether each bracket end was recorded from an inexact volume solve, and whether a closed jump was re-solved.
        boolean lowerLoose = false, upperLoose = false, resolved = false;
        int warmer = 0;
        for (int steps = 0; ; steps++) {
            if (steps >= settings.maximumOuterIterations()) {
                return searchFailure(Specification.UV, competition, FailureKind.SEARCH, "no convergence of the UV temperature search in "
                        + steps + " steps (bracket " + s.lower + " .. " + s.upper + " K)", last, w);
            }
            // An inexact volume solve while the energy is far off: a hundredth of the last relative energy residual. Not
            // under a crystal competition (P5): a crystal beside a liquid makes the answer nearly incompressible, so an
            // inexact volume moves its pressure by megapascals and its energy by more than the residual that set the
            // tolerance (dU/d ln V at fixed T is T alpha/kappa, ten times N R T), which put ends of the bracket on the wrong
            // side and closed it on a false step.
            w.innerLooseness = strict || w.activeCount > 0 ? 0.0 : Double.isNaN(previousResidual) ? INNER_LOOSENESS
                    : Math.min(INNER_LOOSENESS, 0.01 * Math.abs(previousResidual) / (w.total * R * t));
            int outcome = innerUv(t, v, competition, w);
            if (outcome == INNER_FAILED) {
                if (Double.isNaN(lastT) && w.activeCount > 0 && t < crystalCeiling(w)) {
                    // P4 stage 2a: as in PH, a start in a held region starts again from the competition's ceiling.
                    t = crystalCeiling(w);
                    continue;
                }
                if (Double.isNaN(lastT) && t < w.maximumT && ++warmer <= MAXIMUM_RETREATS) {
                    // As in PH: a start the equilibrium does not answer lies below the answer; the search starts warmer.
                    t = Math.min(1.25 * t, w.maximumT);
                    continue;
                }
                if (Double.isNaN(lastT) || ++retreats > MAXIMUM_RETREATS) {
                    return w.innerResult != null
                            ? outerAnswer(Specification.UV, w.innerResult, u, v, false, w.innerFailure, w)
                            : searchFailure(Specification.UV, competition, w.innerFailureKind, w.innerFailure, last, w);
                }
                t = 0.5 * (t + lastT);
                continue;
            }
            retreats = 0;
            if (outcome != INNER_FOUND) {
                boolean above = outcome == INNER_ABOVE;
                s.recordSide(t, above);
                lastT = t;
                previousT = Double.NaN;
                boolean closed = s.bracketed() && s.width() <= tolerance * t;
                if (closed || !s.bracketed() && (above ? t <= s.minimum : t >= s.maximum)) {
                    return outsideWindow(Specification.UV, false, !above,
                            "the specified volume " + v + " m3", competition, last, w);
                }
                t = s.bracketed() ? s.next(t, Double.NaN, 0.0) : Math.clamp(above ? 0.8 * t : 1.25 * t, s.minimum, s.maximum);
                continue;
            }
            EquilibriumResult result = w.innerResult;
            boolean coexistence = w.innerCoexistence;
            last = result;
            lastT = t;
            double residual = result.internalEnergy() - u;
            double scale = SPECIFICATION_TOLERANCE * w.total * R * t;
            if (w.innerLoose && Math.abs(residual) <= 100.0 * scale) {
                // Close enough to need the volume exactly: solve it again at this temperature (nothing recorded).
                strict = true;
                continue;
            }
            s.record(t, residual, result, coexistence);
            if (residual < 0.0) lowerLoose = w.innerLoose; else upperLoose = w.innerLoose;
            double slope = coexistence ? Double.NaN : dryIsochoricHeatCapacity(result, t, w);
            if (!(slope > 0.0) || !Double.isFinite(slope)) {
                slope = Double.isNaN(previousT) || previousT == t ? Double.NaN : (residual - previousResidual) / (t - previousT);
            }
            if (!(slope > 0.0) || !Double.isFinite(slope)) slope = idealHeatCapacity(t, true, w);
            double step = -residual / slope;
            if (Math.abs(residual) <= scale && Math.abs(step) <= tolerance * t) {
                return outerAnswer(Specification.UV, result, u, v, coexistence, null, w);
            }
            if (s.bracketed() && s.width() <= tolerance * t) {
                // U(T) at fixed volume is continuous (through a coexistence too): a jump here is no state of the model.
                if (s.upperResidual - s.lowerResidual <= scale && s.best() != null) {
                    return outerAnswer(Specification.UV, s.best(), u, v, s.bestCoexistence(), null, w);
                }
                // An end known only by its pressure leaving the window (P3 WP7d): the bracket closed against the box, on
                // the temperature where the isochore meets the box's pressure bound, with the energy still short of the
                // specification at the answered end. The answer lies beyond that bound: not converged.
                boolean aboveWindow = Double.isNaN(s.upperResidual) && s.upperResult == null;
                boolean belowWindow = Double.isNaN(s.lowerResidual) && s.lowerResult == null;
                if (aboveWindow || belowWindow) {
                    if (Math.abs(residual) <= scale) return outerAnswer(Specification.UV, result, u, v, coexistence, null, w);
                    return outsideWindow(Specification.UV, false, belowWindow,
                            "the specified volume " + v + " m3 at the specified internal energy " + u + " J", competition, last, w);
                }
                if ((lowerLoose || upperLoose) && !resolved) {
                    // An end from an inexact volume solve may lie on the wrong side: within a split dU/d ln V at fixed T is
                    // about the vapour's share of the latent heat, far above the N R T the looseness assumes (W3a: ethanol
                    // and water at 360 K, 7 Pa and 5 J off). The search starts again from its best end with exact solves.
                    resolved = true;
                    strict = true;
                    lowerLoose = upperLoose = false;
                    t = Math.abs(s.lowerResidual) <= Math.abs(s.upperResidual) ? s.lower : s.upper;
                    s.reset(s.minimum, s.maximum);
                    previousT = Double.NaN;
                    previousResidual = Double.NaN;
                    continue;
                }
                EquilibriumResult lever = balanceSteps.leverStep(Specification.UV, s, u, competition, w);
                if (lever != null) return outerAnswer(Specification.UV, lever, u, v, true, null, w);
                // A target inside a one-major feed's triple-point triangle was answered before the search (solveUv).
                boolean crystalStep = balanceSteps.pureCrystal(w) != null && s.endHoldsSolid();
                return searchFailure(Specification.UV, competition, crystalStepKind(crystalStep), crystalStepType(crystalStep) + "the specified internal energy "
                        + u + " J lies in a step of U(T) at the specified volume between " + s.lower + " and " + s.upper + " K",
                        last, w);
            }
            if (!s.bracketed()) {
                if (t <= s.minimum && residual > 0.0) {
                    return outsideWindow(Specification.UV, true, true,
                            "the specified internal energy " + u + " J", competition, last, w);
                }
                if (t >= s.maximum && residual < 0.0) {
                    return outsideWindow(Specification.UV, true, false,
                            "the specified internal energy " + u + " J", competition, last, w);
                }
            }
            previousT = t;
            previousResidual = residual;
            // Before a bracket the step is capped: the ideal-gas slope of a two-phase start is far too small.
            t = s.next(t, step, TEMPERATURE_STEP_CAP * t);
        }
    }

    /**
     * The pressure at temperature {@code t} whose TP answer has volume {@code v}: {@code ln(v/V(P))} increases with
     * {@code ln P}, bracketed like PH. Started from the cubic's own pressure of the dry feed as one phase when the last
     * answer was one phase, otherwise from the pressures found at earlier temperatures extrapolated linearly in
     * {@code (1/T, ln P)} (one earlier answer: Trouton's slope {@code -10 T}); the first slope without a compressibility
     * is the last secant measured between two answers on one piece of {@code V(P)} ({@link #samePhases}). Accepted within
     * {@code innerLooseness} in {@code ln V} (the nested search's inexact solves far from the answer) or at the
     * specification. A step of {@code V(P)} is the volume jump between the bracket's ends (owner decision
     * D40 A): a pure coexistence, a crystal's step, a binary's three-phase pressure,
     * each answered from the volume balance, or a typed failure.
     */
    private int innerUv(double t, double v, PhaseCompetition competition, Workspace w) {
        BracketSearch s = w.inner;
        double tolerance = settings.outerTolerance();
        s.reset(DeterministicMath.log(w.minimumP), DeterministicMath.log(w.maximumP));
        double cubic = !w.innerSingle ? Double.NaN : cubicPressure(t, v, w);
        double y;
        if (!Double.isNaN(cubic)) {
            y = DeterministicMath.log(cubic);
        } else if (Double.isNaN(w.innerY1)) {
            y = DeterministicMath.log(w.total * R * t / v);
        } else {
            double slope = !Double.isNaN(w.innerY2) && w.innerT2 != w.innerT1
                    ? (w.innerY1 - w.innerY2) / (1.0 / w.innerT1 - 1.0 / w.innerT2) : -10.0 * w.innerT1;
            if (!(slope < 0.0) || !Double.isFinite(slope)) slope = -10.0 * w.innerT1;
            y = w.innerY1 + Math.clamp(slope * (1.0 / t - 1.0 / w.innerT1), -2.0 * PRESSURE_STEP_CAP, 2.0 * PRESSURE_STEP_CAP);
        }
        y = Math.clamp(y, s.minimum, s.maximum);
        w.innerLoose = false;
        double previousY = Double.NaN;
        double previousResidual = Double.NaN;
        EquilibriumResult previous = null;
        double lastY = Double.NaN;
        int retreats = 0;
        w.innerResult = null;
        w.innerCoexistence = false;
        w.innerFailure = null;
        w.innerFailureKind = null;
        for (int steps = 0; ; steps++) {
            if (steps >= settings.maximumOuterIterations()) {
                w.innerFailure = "no convergence of the UV pressure search at " + t + " K in " + steps + " TP equilibria";
                w.innerFailureKind = FailureKind.SEARCH;
                return INNER_FAILED;
            }
            double p = DeterministicMath.exp(y);
            EquilibriumResult result = outerTp(t, p, competition, w);
            if (!result.converged()) {
                if (Double.isNaN(lastY) || ++retreats > MAXIMUM_RETREATS) {
                    w.innerResult = result;
                    w.innerFailure = "UV search: the TP equilibrium at " + t + " K, " + p + " Pa";
                    w.innerFailureKind = result.failureKind();
                    return INNER_FAILED;
                }
                y = 0.5 * (y + lastY);
                continue;
            }
            retreats = 0;
            double residual;
            boolean coexistence = false;
            if (result.classification() == Classification.PURE_COEXISTENCE_UNDERDETERMINED) {
                EquilibriumResult answer = holdsSolid(result) ? balanceSteps.pureCrystalStep(Specification.UV, t, p, v, false, competition, w)
                        : balanceSteps.pureCoexistence(Specification.UV, t, p, v, false, competition, w);
                if (answer != null) return foundOrHeld(answer, w);
                if (Double.isNaN(w.coexistenceX)) {
                    w.innerFailure = "no equal-fugacity pressure found near " + p + " Pa at " + t + " K";
                    w.innerFailureKind = FailureKind.SEARCH;
                    return INNER_FAILED;
                }
                y = w.coexistenceX;
                residual = w.coexistenceResidual;
                result = null;
                coexistence = true;
            } else {
                residual = DeterministicMath.log(v / result.volume());
            }
            lastY = y;
            s.record(y, residual, result, coexistence);
            double slope = result == null ? Double.NaN : dryCompressibility(result, t, p, w);
            if (!(slope > 0.0) || !Double.isFinite(slope)) {
                // A secant only between answers on one piece of V(P), the same phases (owner decision D40 A): across a bubble
                // or dew point V(P) has a kink, across a step a jump whose secant is no slope; stored, such a secant started
                // the next temperature's search with steps below the spacing of ln P (100 TP equilibria at one point).
                slope = Double.isNaN(previousY) || previousY == y || !samePhases(result, previous) ? Double.NaN
                        : (residual - previousResidual) / (y - previousY);
                if (slope > 0.0 && Double.isFinite(slope)) w.innerSlope = slope; else slope = w.innerSlope;
            }
            previous = result;
            double step = -residual / slope;
            boolean exact = Math.abs(residual) <= SPECIFICATION_TOLERANCE && Math.abs(step) <= tolerance;
            if (result != null && (exact || Math.abs(residual) <= w.innerLooseness)) {
                w.innerLoose = !exact;
                return found(result, false, w);
            }
            if (s.bracketed() && (s.width() <= tolerance || w.request.presentCount == 1 && s.straddlesPureCoexistence()
                    || balanceSteps.pureCrystal(w) != null && s.straddlesCrystalStep()
                    || balanceSteps.binaryCrystal(w) != null && threePhaseDue(s, s.width())
                    || balanceSteps.binaryFluid(w) >= 0 && fluidThreeDue(s, s.width()))) {
                int closed = closedInner(t, v, competition, w);
                if (closed != INNER_BELOW) return closed;
                // The coexistence lies beyond the jump (its side is recorded), or was not met yet: the search goes on.
                if (!Double.isNaN(w.coexistenceX)) {
                    y = w.coexistenceX;
                    s.record(y, w.coexistenceResidual, null, true);
                }
                previousY = Double.NaN;
                y = s.next(y, Double.NaN, PRESSURE_STEP_CAP);
                continue;
            }
            if (!s.bracketed()) {
                if (y <= s.minimum && residual > 0.0) return INNER_BELOW;
                if (y >= s.maximum && residual < 0.0) return INNER_ABOVE;
            }
            previousY = y;
            previousResidual = residual;
            y = s.next(y, step, PRESSURE_STEP_CAP);
        }
    }

    private static int found(EquilibriumResult result, boolean coexistence, Workspace w) {
        w.innerResult = result;
        w.innerCoexistence = coexistence;
        w.innerPressure = result.phases().get(0).pressure();
        w.innerSingle = !coexistence && result.phases().size() == 1;
        double t = result.phases().get(0).temperature();
        if (t != w.innerT1) {
            w.innerT2 = w.innerT1;
            w.innerY2 = w.innerY1;
        }
        w.innerT1 = t;
        w.innerY1 = DeterministicMath.log(w.innerPressure);
        return INNER_FOUND;
    }

    /**
     * The inner bracket closed (or straddles a pure component's vapour and liquid). Returns {@code INNER_FOUND},
     * {@code INNER_FAILED}, or {@code INNER_BELOW} when the search goes on: the coexistence lies beyond the jump (its
     * side in {@code coexistenceX} and {@code coexistenceResidual}), or the bracket is not closed yet and no
     * equal-fugacity pressure was met ({@code coexistenceX} NaN).
     */
    private int closedInner(double t, double v, PhaseCompetition competition, Workspace w) {
        BracketSearch s = w.inner;
        boolean closed = s.width() <= settings.outerTolerance();
        w.coexistenceX = Double.NaN;
        if (closed && s.upperResidual - s.lowerResidual <= SPECIFICATION_TOLERANCE && s.best() != null) {
            if (!s.bestCoexistence()) w.innerPressure = s.best().phases().get(0).pressure();
            return found(s.best(), s.bestCoexistence(), w);
        }
        double p = DeterministicMath.exp(0.5 * (s.lower + s.upper));
        boolean crystalStep = balanceSteps.pureCrystal(w) != null && s.endHoldsSolid();
        // The one component's coexistence: any jump of a pure feed.
        if (crystalStep || w.request.presentCount == 1) {
            EquilibriumResult answer = crystalStep ? balanceSteps.pureCrystalStep(Specification.UV, t, p, v, closed, competition, w)
                    : balanceSteps.pureCoexistence(Specification.UV, t, p, v, closed, competition, w);
            if (answer != null) return foundOrHeld(answer, w);
            if (!Double.isNaN(w.coexistenceX) || !closed) return INNER_BELOW;
        } else if (balanceSteps.binaryCrystal(w) != null && BalanceSteps.straddlesThreePhase(s)) {
            // P5: a binary's three-phase step (vapour, liquid and crystal at one pressure for the fixed temperature).
            EquilibriumResult answer = balanceSteps.binaryThreePhase(Specification.UV, s, t, p, v, closed, competition, w);
            if (answer != null) return foundOrHeld(answer, w);
            if (!Double.isNaN(w.coexistenceX) || !closed) return INNER_BELOW;
        } else if (balanceSteps.binaryFluid(w) >= 0 && BalanceSteps.straddlesFluidThree(s)) {
            // W3a D4: a binary's vapour-liquid-liquid step (at the fixed temperature, one pressure).
            EquilibriumResult answer = balanceSteps.binaryFluidThreePhase(Specification.UV, s, t, p, v, closed, competition, w);
            if (answer != null) return foundOrHeld(answer, w);
            if (!Double.isNaN(w.coexistenceX) || !closed) return INNER_BELOW;
        }
        // Not closed yet: the search goes on (the step is not where the volume lies).
        if (!closed) return INNER_BELOW;
        w.innerFailure = crystalStepType(crystalStep) + "the specified volume " + v + " m3 lies in a step of V(P) at " + t
                + " K between " + DeterministicMath.exp(s.lower) + " and " + DeterministicMath.exp(s.upper)
                + " Pa: no state of the model has it";
        w.innerFailureKind = crystalStepKind(crystalStep);
        return INNER_FAILED;
    }

    /**
     * The {@link EquilibriumResult#CRYSTAL_STEP} prefix of a closed search's failure when the step it closed on is the
     * crystal step of the feed's one major species ({@code crystalStep}: a pure or near-pure feed, an end holding the
     * crystal; D21 option C), otherwise nothing.
     */
    private static String crystalStepType(boolean crystalStep) {
        return crystalStep ? EquilibriumResult.CRYSTAL_STEP + ": " : "";
    }

    /** The kind beside {@link #crystalStepType}: {@link FailureKind#CRYSTAL_STEP} on the crystal step, else a search's failure. */
    private static FailureKind crystalStepKind(boolean crystalStep) {
        return crystalStep ? FailureKind.CRYSTAL_STEP : FailureKind.SEARCH;
    }

    // Results of a search.

    /**
     * A search's answer under its specification: the TP answer (or a coexistence from a balance) with the search's
     * counters and residual; a note is appended to the detail (never prepended: a {@code CRITICAL_BAND} prefix stays).
     */
    static EquilibriumResult outerAnswer(Specification specification, EquilibriumResult answer, double first, double second,
                                         boolean coexistence, String note, Workspace w) {
        double residual = Double.NaN;
        if (answer.converged() && !answer.phases().isEmpty()) {
            double t = answer.phases().get(0).temperature();
            double scale = answer.totalAmount() * R * t;
            residual = specification == Specification.PH ? Math.abs(answer.enthalpy() - first) / scale
                    : Math.max(Math.abs(answer.internalEnergy() - first) / scale, Math.abs(DeterministicMath.log(answer.volume() / second)));
        }
        Diagnostics d = answer.diagnostics();
        Diagnostics outer = new Diagnostics(d.feedVerdict(), d.feedTangentPlaneDistance(), d.flashIterations(),
                d.extrapolations(), d.rachfordRiceIterations(), d.lnKChange(), d.fugacityResidual(),
                d.productTangentPlaneDistance(), w.outerKernel, w.outerDerivative, d.newtonIterations(), d.tieLine(),
                d.criticalBand(), w.outerEvaluations, residual, coexistence && answer.converged());
        String detail = note == null ? answer.detail() : answer.detail() + "; " + note;
        EquilibriumResult.Coverage coverage = answer.converged() || note == null ? answer.coverage()
                : new EquilibriumResult.Coverage(answer.coverage().grade(), detail);
        return new EquilibriumResult(specification, answer.status(), answer.reason(), detail,
                answer.identity(), answer.competition(), answer.classification(), answer.phases(), answer.coexisting(),
                coverage, outer, answer.deposition(), answer.failureKind(), answer.failedInCriticalBand());
    }

    /** A search that ended without an answer; inside the critical band (by its last TP answer) typed {@code CRITICAL_BAND}. */
    private EquilibriumResult searchFailure(Specification specification, PhaseCompetition competition, FailureKind kind,
                                            String why, EquilibriumResult last, Workspace w) {
        Diagnostics d = last == null ? Diagnostics.NONE : last.diagnostics();
        boolean band = d.criticalBand();
        String detail = band ? EquilibriumResult.CRITICAL_BAND + " (the search's last TP equilibrium lies in the critical band): "
                + why : why;
        Diagnostics outer = new Diagnostics(d.feedVerdict(), d.feedTangentPlaneDistance(), d.flashIterations(),
                d.extrapolations(), d.rachfordRiceIterations(), d.lnKChange(), d.fugacityResidual(),
                d.productTangentPlaneDistance(), w.outerKernel, w.outerDerivative, d.newtonIterations(), d.tieLine(), band,
                w.outerEvaluations, Double.NaN, false);
        return EquilibriumResult.notConverged(specification, contract.identity(), competition, kind, band, detail, outer);
    }

    /**
     * The specification lies beyond an end of the search window, the numerical box: not converged, a numerical failure
     * (owner rule of 2026-10-16: no data range bounds a search).
     */
    private EquilibriumResult outsideWindow(Specification specification, boolean temperature, boolean below, String what,
                                            PhaseCompetition competition, EquilibriumResult last, Workspace w) {
        double minimum = temperature ? w.minimumT : w.minimumP;
        double maximum = temperature ? w.maximumT : w.maximumP;
        return searchFailure(specification, competition, FailureKind.SEARCH, what + " lies " + (below ? "below" : "above") + " the "
                + (temperature ? "temperature" : "pressure") + " window " + minimum + " .. " + maximum + (temperature ? " K" : " Pa")
                + " of the " + specification + " search (the numerical box)", last, w);
    }

    /**
     * Whether two answers lie on one piece of {@code V(P)} (or {@code H(T)}): both answered, with the same phases by kind
     * and crystal, in order (owner decision D40 A). A step of the function changes the phases at its ends (a pure
     * component's coexistence, a crystal's step, a three-phase pressure), and so
     * does a kink (a bubble or dew point).
     */
    private static boolean samePhases(EquilibriumResult a, EquilibriumResult b) {
        if (a == null || b == null || a.phases().size() != b.phases().size()) return false;
        for (int k = 0; k < a.phases().size(); k++) {
            PhaseAmounts x = a.phases().get(k);
            PhaseAmounts y = b.phases().get(k);
            if (x.kind() != y.kind() || !Objects.equals(x.crystal(), y.crystal())) return false;
        }
        return true;
    }

    /** An inner UV answer from a balance: a converged one is found, a typed hold ends the pressure search with it. */
    private static int foundOrHeld(EquilibriumResult answer, Workspace w) {
        if (answer.converged()) return found(answer, true, w);
        w.innerResult = answer;
        w.innerFailure = answer.detail();
        w.innerFailureKind = answer.failureKind();
        return INNER_FAILED;
    }

    /**
     * Whether a binary's three-phase point is due from bracket {@code s} (P5): it straddles the step, and this is the
     * search's first attempt, or one of three more once the bracket is narrower than {@link #THREE_PHASE_BRACKET}
     * ({@code width}: relative in T for PH, in ln P for UV); a closed bracket is always tried by the closing branch.
     */
    private static boolean threePhaseDue(BracketSearch s, double width) {
        if (!BalanceSteps.straddlesThreePhase(s)) return false;
        if (s.threePhaseAttempts == 0 || width <= THREE_PHASE_BRACKET && s.threePhaseAttempts < 4) {
            s.threePhaseAttempts++;
            return true;
        }
        return false;
    }

    /** {@link #threePhaseDue} for the vapour-liquid-liquid step of a {@link BalanceSteps#binaryFluid} feed. */
    private static boolean fluidThreeDue(BracketSearch s, double width) {
        if (!BalanceSteps.straddlesFluidThree(s)) return false;
        if (s.threePhaseAttempts == 0 || width <= THREE_PHASE_BRACKET && s.threePhaseAttempts < 4) {
            s.threePhaseAttempts++;
            return true;
        }
        return false;
    }
}
