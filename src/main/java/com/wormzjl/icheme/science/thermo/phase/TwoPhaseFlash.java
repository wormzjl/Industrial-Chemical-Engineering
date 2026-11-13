package com.wormzjl.icheme.science.thermo.phase;

import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.ARMIJO;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.FAILED;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.FRACTION_TO_BOUNDARY;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.FUGACITY_TOLERANCE;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.GIBBS_ROUNDOFF;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.MERGED;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.NEWTON_HALVINGS;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.SHIFTS_FROM_1E8;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.SLOW_EIGENVALUE;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.SLOW_SUBSTITUTION_STEPS;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.SPLIT;

import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.math.SmallDenseSolve;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.PhaseIdentification;
import com.wormzjl.icheme.science.thermo.SubstitutionAcceleration;
import com.wormzjl.icheme.science.thermo.TangentPlaneStability;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.Feed;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.Settings;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.Workspace;
import java.util.Arrays;

/**
 * The two-phase flash of {@link FluidTpEquilibrium} (S01): the split of an unstable feed on the two-slot path. Successive
 * substitution with Rachford-Rice in the minor phase's fraction and the dominant-eigenvalue extrapolation, the Newton
 * finish on the Gibbs energy, the products' states with merging and their stability re-check ({@code finishSplit}),
 * and the lower-Gibbs root of a composition, which the three-slot system shares ({@link ThreeSlotFlash}). The dry core
 * hands it the feed and the workspace and reads the outcome (SPLIT, MERGED or FAILED) and the products the workspace
 * holds. It reads the engine's kernel, evaluator, settings, stability test and crystal species, given once at
 * construction, and calls back into the engine only for the counted stability test ({@code stabilityOf}) and the
 * carried path's crystal-rich test ({@code crystalRich}). Immutable; one instance per engine.
 */
final class TwoPhaseFlash {
    /** Keeps {@code exp(ln K)} a finite, positive normal double. */
    private static final double LOG_K_BOUND = 700.0;
    private static final int MAXIMUM_RACHFORD_RICE_STEPS = 200;
    /** Halvings of the amount of the stationary phase when a Newton start is built from the stability test. */
    private static final int START_HALVINGS = 60;

    private final FluidTpEquilibrium engine;
    private final PengRobinsonKernel kernel;
    private final CubicPhaseEvaluator evaluator;
    private final Settings settings;
    private final TangentPlaneStability stability;
    private final int count;
    /** Per component, whether a crystal of the engine is of that species (the engine's list). */
    private final boolean[] crystalSpecies;

    TwoPhaseFlash(FluidTpEquilibrium engine, PengRobinsonKernel kernel, CubicPhaseEvaluator evaluator, Settings settings,
                  TangentPlaneStability stability, int count, boolean[] crystalSpecies) {
        this.engine = engine;
        this.kernel = kernel;
        this.evaluator = evaluator;
        this.settings = settings;
        this.stability = stability;
        this.count = count;
        this.crystalSpecies = crystalSpecies;
    }

    /**
     * The split of an unstable feed: a first attempt seeded from the stability test's stationary phase, and, when that
     * fails, one more from Wilson's K.
     */
    int twoPhase(double t, TangentPlaneStability.Result feed, Feed material, Workspace w) {
        int outcome = attempt(t, feed, false, material, w);
        if (outcome == FAILED) {
            String first = w.failure;
            double seed = w.seedDistance;
            outcome = attempt(t, feed, true, material, w);
            if (outcome == FAILED) w.failure = first + "; again from Wilson's K: " + w.failure;
            w.seedDistance = seed;
        }
        return outcome;
    }

    private int attempt(double t, TangentPlaneStability.Result feed, boolean wilson, Feed material, Workspace w) {
        // The stability test's stationary point seeds one side of the split: the lighter one the vapour.
        double[] trial = feed.trialComposition();
        System.arraycopy(trial, 0, w.trial, 0, count);
        kernel.evaluate(t, w.pressure, trial, feed.trialRoot(), w.mixture, w.first);
        kernel.evaluate(t, w.pressure, material.amounts, feed.feedRoot(), w.mixture, w.second);
        w.kernelEvaluations += 2;
        w.trialIsVapour = w.first.compressibility() > w.second.compressibility();
        Arrays.fill(w.logK, 0.0);
        double seedDistance = 0.0;
        for (int a = 0; a < material.presentCount; a++) {
            int i = material.present[a];
            double ratio = DeterministicMath.log(trial[i]) - DeterministicMath.log(material.z[i]);
            w.logK[i] = w.trialIsVapour ? ratio : -ratio;
            seedDistance += w.logK[i] * w.logK[i];
        }
        w.seedDistance = seedDistance;
        if (wilson || !(seedDistance >= settings.trivialDistance())) {
            // Instability proven by the feed's own curvature with no distinct stationary point: Wilson's K instead.
            kernel.wilsonK(t, w.pressure, w.k);
            for (int a = 0; a < material.presentCount; a++) w.logK[material.present[a]] = DeterministicMath.log(w.k[material.present[a]]);
        }

        // Successive substitution; Newton takes over when it is slow, collapses or loses the split.
        boolean newton = false;
        boolean fromSubstitution = true;
        boolean hasPrevious = false;
        int plainSteps = 0;
        w.lambda = 0.0;
        int steps = 0;
        while (true) {
            if (!split(material, w)) {
                newton = true;
                fromSubstitution = false;
                break;
            }
            phaseLogPhi(t, w.x, false, w.liquidLogPhi, w);
            phaseLogPhi(t, w.y, true, w.vaporLogPhi, w);
            double change = 0.0;
            for (int a = 0; a < material.presentCount; a++) {
                int i = material.present[a];
                w.newLogK[i] = w.liquidLogPhi[i] - w.vaporLogPhi[i];
                w.delta[i] = w.newLogK[i] - w.logK[i];
                change = Math.max(change, Math.abs(w.delta[i]));
            }
            w.iterations++;
            steps++;
            w.lastChange = change;
            if (change < settings.lnKTolerance()) {
                for (int a = 0; a < material.presentCount; a++) w.logK[material.present[a]] = w.newLogK[material.present[a]];
                break;
            }
            if (!Double.isFinite(change)) {
                newton = true;
                fromSubstitution = false;
                break;
            }
            if (steps >= settings.maximumIterations()) {
                w.failure = "no convergence in " + steps + " substitution iterations (max |d ln K| " + change + ")";
                return FAILED;
            }
            boolean extrapolated = false;
            plainSteps++;
            if (hasPrevious && plainSteps >= settings.accelerationCycle()) {
                double numerator = 0.0;
                double denominator = 0.0;
                for (int a = 0; a < material.presentCount; a++) {
                    int i = material.present[a];
                    numerator += w.delta[i] * w.delta[i];
                    denominator += w.previousDelta[i] * w.delta[i];
                }
                double lambda = numerator / denominator;
                w.lambda = lambda;
                if (SubstitutionAcceleration.admissible(lambda)) {
                    double factor = SubstitutionAcceleration.factor(lambda);
                    double scale = SubstitutionAcceleration.scale(change, factor);
                    for (int a = 0; a < material.presentCount; a++) {
                        int i = material.present[a];
                        w.logK[i] = w.newLogK[i] + w.delta[i] * factor * scale;
                    }
                    extrapolated = true;
                    w.extrapolations++;
                }
                plainSteps = 0;
            }
            if (extrapolated) {
                hasPrevious = false;
            } else {
                for (int a = 0; a < material.presentCount; a++) w.logK[material.present[a]] = w.newLogK[material.present[a]];
                double[] swap = w.previousDelta;
                w.previousDelta = w.delta;
                w.delta = swap;
                hasPrevious = true;
            }
            double distance = 0.0;
            for (int a = 0; a < material.presentCount; a++) distance += w.logK[material.present[a]] * w.logK[material.present[a]];
            if (distance < settings.trivialDistance()) {
                // Collapsing onto the feed: start the finish from the stability test's own phase instead.
                newton = true;
                fromSubstitution = false;
                break;
            }
            if (steps >= settings.substitutionLimit()
                    || steps >= SLOW_SUBSTITUTION_STEPS && w.lambda > SLOW_EIGENVALUE) {
                newton = true;
                break;
            }
        }

        if (newton) {
            if (!newtonFinish(t, feed, fromSubstitution, material, w)) return FAILED;
        } else {
            if (!twoPhaseSplit(material, w)) {
                // A converged ratio set with no admissible split: the finish decides from the stability test's phase.
                if (!newtonFinish(t, feed, false, material, w)) return FAILED;
            } else {
                double tie = 0.0;
                Arrays.fill(w.vaporAmounts, 0.0);
                Arrays.fill(w.liquidAmounts, 0.0);
                for (int a = 0; a < material.presentCount; a++) {
                    int i = material.present[a];
                    tie += material.z[i] * w.logK[i] * w.logK[i];
                    // The smaller share by its own formula, the larger by difference: conserved to rounding, and a
                    // component almost entirely in one phase keeps its trace in the other to full relative precision.
                    double denominator = splitDenominator(w, i);
                    double vapourShare = w.vapourFraction * w.k[i] / denominator;
                    double liquidShare = w.liquidFraction / denominator;
                    if (vapourShare <= liquidShare) {
                        w.vaporAmounts[i] = Math.min(material.amounts[i], material.amounts[i] * vapourShare);
                        w.liquidAmounts[i] = material.amounts[i] - w.vaporAmounts[i];
                    } else {
                        w.liquidAmounts[i] = Math.min(material.amounts[i], material.amounts[i] * liquidShare);
                        w.vaporAmounts[i] = material.amounts[i] - w.liquidAmounts[i];
                    }
                }
                w.tieLine = tie;
            }
        }
        return finishSplit(t, material, w);
    }

    /** States of both products, merging, the product stability re-check and the fugacity residual. */
    private int finishSplit(double t, Feed material, Workspace w) {
        // Each product's WP7c label from the root lowerGibbsRoot chose (no further kernel call): both liquid-like and
        // denser than the cubic's critical volume is a liquid-liquid split (P3 WP7d; the volume guard P3 WP11).
        Root liquidRoot = lowerGibbsRoot(t, w.liquidAmounts, Root.LIQUID, w.liquidLogPhi, w);
        w.liquidProductDenseLiquidLike = PhaseIdentification.denseLiquidLike(w.chosen, t, w.pressure);
        PhaseState liquid = evaluator.evaluate(t, w.pressure, w.liquidAmounts, liquidRoot, w.phases, w.liquidState);
        Root vapourRoot = lowerGibbsRoot(t, w.vaporAmounts, Root.VAPOR, w.vaporLogPhi, w);
        w.vapourProductDenseLiquidLike = PhaseIdentification.denseLiquidLike(w.chosen, t, w.pressure);
        PhaseState vapour = evaluator.evaluate(t, w.pressure, w.vaporAmounts, vapourRoot, w.phases, w.vaporState);
        w.ownSplit = true;
        w.kernelEvaluations += 2;
        double gap = Math.abs(vapour.molarVolume() - liquid.molarVolume()) / (0.5 * (vapour.molarVolume() + liquid.molarVolume()));
        if (w.tieLine < settings.mergeTieLine() && gap < settings.mergeVolumeGap()) return MERGED;
        // Stability of both products: a third phase or another split is not an answer.
        w.carriedProduct = true;
        TangentPlaneStability.Result liquidCheck = engine.stabilityOf(stability, t, w.liquidAmounts, material, w);
        TangentPlaneStability.Result vapourCheck = engine.stabilityOf(stability, t, w.vaporAmounts, material, w);
        w.productDistance = Math.min(liquidCheck.minimumTangentPlaneDistance(), vapourCheck.minimumTangentPlaneDistance());
        if (liquidCheck.verdict() != TangentPlaneStability.Verdict.STABLE
                || vapourCheck.verdict() != TangentPlaneStability.Verdict.STABLE) {
            // A product proved unstable is the typed indication of a third phase (P3 WP11): the split's common tangent
            // plane is crossed, so it is not the global minimum; a consumer holds on it with that reason. A product the
            // re-check left unresolved is the same failure short of the proof, typed as such.
            boolean unstable = liquidCheck.verdict() == TangentPlaneStability.Verdict.UNSTABLE
                    || vapourCheck.verdict() == TangentPlaneStability.Verdict.UNSTABLE;
            if (w.carrying && vapourCheck.verdict() == TangentPlaneStability.Verdict.UNSTABLE
                    && liquidCheck.verdict() == TangentPlaneStability.Verdict.STABLE
                    && liquid.molarVolume() < vapour.molarVolume() && engine.crystalRich(w.liquidAmounts, liquid, t, w)) {
                w.standInHeld = true;
                for (int i = 0; i < count; i++) w.standIn[i] = crystalSpecies[i] ? w.liquidAmounts[i] : 0.0;
            }
            w.failure = (unstable ? EquilibriumResult.UNSTABLE_PRODUCT : EquilibriumResult.UNRESOLVED_PRODUCT) + ": a product phase is not stable (liquid "
                    + liquidCheck.verdict() + ", vapour " + vapourCheck.verdict()
                    + "): a third phase or another split; three-phase equilibrium is not implemented";
            return FAILED;
        }
        double residual = 0.0;
        for (int a = 0; a < material.presentCount; a++) {
            int i = material.present[a];
            double xi = liquid.moleFraction(i);
            double yi = vapour.moleFraction(i);
            if (!(xi > 0.0) || !(yi > 0.0)) continue;
            double difference = DeterministicMath.log(xi) + liquid.logFugacityCoefficient(i) - DeterministicMath.log(yi) - vapour.logFugacityCoefficient(i);
            residual = Math.max(residual, Math.abs(difference));
        }
        w.residual = residual;
        return SPLIT;
    }

    // The second-order finish.

    /**
     * Newton on the vapour mole numbers per mole of feed, minimising the Gibbs energy. Leaves the amounts, the tie line
     * and the ratios in the workspace; false (with {@code failure}) when it cannot start or does not converge.
     */
    private boolean newtonFinish(double t, TangentPlaneStability.Result feed, boolean fromSubstitution, Feed material, Workspace w) {
        // The mole numbers go to the kernel over the whole basis: a component absent from this feed must be zero, not the
        // amount an earlier request on this workspace left there (P3 WP7d: a reused workspace failed or changed a split).
        Arrays.fill(w.vaporMoles, 0.0);
        Arrays.fill(w.liquidMoles, 0.0);
        Arrays.fill(w.trialVapor, 0.0);
        Arrays.fill(w.trialLiquid, 0.0);
        // d_i = ln f_i of the feed on its stability root, over the pressure.
        kernel.evaluate(t, w.pressure, material.z, feed.feedRoot(), w.mixture, w.first);
        w.kernelEvaluations++;
        double[] feedLogPhi = w.first.logFugacityCoefficientsView();
        for (int a = 0; a < material.presentCount; a++) {
            int i = material.present[a];
            w.reference[i] = DeterministicMath.log(material.z[i]) + feedLogPhi[i];
        }
        double gibbs = Double.NaN;
        if (fromSubstitution) {
            if (twoPhaseSplit(material, w)) {
                for (int a = 0; a < material.presentCount; a++) {
                    int i = material.present[a];
                    double denominator = splitDenominator(w, i);
                    w.vaporMoles[i] = material.z[i] * (w.vapourFraction * w.k[i] / denominator);
                    w.liquidMoles[i] = material.z[i] * (w.liquidFraction / denominator);
                }
                gibbs = gibbs(t, w.vaporMoles, w.liquidMoles, material, w);
                if (!(gibbs < -GIBBS_ROUNDOFF * w.gibbsScale)) gibbs = Double.NaN;
            }
        }
        if (Double.isNaN(gibbs)) gibbs = startFromStationaryPhase(t, feed, material, w);
        if (Double.isNaN(gibbs)) {
            w.failure = "no two-phase start below the feed's Gibbs energy for the Newton finish (stationary tm "
                    + feed.minimumTangentPlaneDistance() + ")";
            return false;
        }
        int m = material.presentCount;
        for (int iteration = 0; ; iteration++) {
            // Both phases with their composition derivatives at the current point.
            double vapourTotal = 0.0;
            double liquidTotal = 0.0;
            for (int a = 0; a < m; a++) {
                int i = material.present[a];
                vapourTotal += w.vaporMoles[i];
                liquidTotal += w.liquidMoles[i];
            }
            PengRobinsonKernel.Evaluation vapour;
            PengRobinsonKernel.Evaluation liquid;
            try {
                vapour = differentiatePhase(t, w.vaporMoles, true, w.vaporDerivatives, w);
                liquid = differentiatePhase(t, w.liquidMoles, false, w.liquidDerivatives, w);
            } catch (IllegalStateException coalescing) {
                w.failure = "the Newton finish met a phase at root coalescence: " + coalescing.getMessage();
                return false;
            }
            double largest = 0.0;
            double tie = 0.0;
            for (int a = 0; a < m; a++) {
                int i = material.present[a];
                double logY = DeterministicMath.log(w.vaporMoles[i] / vapourTotal);
                double logX = DeterministicMath.log(w.liquidMoles[i] / liquidTotal);
                w.gradient[i] = logY + vapour.logFugacityCoefficient(i) - logX - liquid.logFugacityCoefficient(i);
                largest = Math.max(largest, Math.abs(w.gradient[i]));
                tie += material.z[i] * (logY - logX) * (logY - logX);
            }
            w.lastChange = largest;
            w.tieLine = tie;
            if (largest < FUGACITY_TOLERANCE) break;
            if (!Double.isFinite(largest) || iteration >= settings.maximumNewtonIterations()) {
                w.failure = "no convergence in " + w.newtonIterations + " Newton steps (max |ln f_V - ln f_L| " + largest + ")";
                return false;
            }
            // Scaled Hessian: s_i H_ij s_j with s_i = sqrt(v_i l_i / z_i), so the ideal part is the identity.
            for (int a = 0; a < m; a++) {
                int i = material.present[a];
                w.scale[i] = Math.sqrt(w.vaporMoles[i] * w.liquidMoles[i] / (w.vaporMoles[i] + w.liquidMoles[i]));
            }
            for (int a = 0; a < m; a++) {
                int i = material.present[a];
                double[] vapourRow = w.vaporDerivatives.dLogPhiDnRowView(i);
                double[] liquidRow = w.liquidDerivatives.dLogPhiDnRowView(i);
                for (int b = 0; b <= a; b++) {
                    int j = material.present[b];
                    double coupling = (0.5 * (vapourRow[j] + w.vaporDerivatives.dLogPhiDnRowView(j)[i]) - 1.0) / vapourTotal
                            + (0.5 * (liquidRow[j] + w.liquidDerivatives.dLogPhiDnRowView(j)[i]) - 1.0) / liquidTotal;
                    double entry = coupling * w.scale[i] * w.scale[j];
                    if (a == b) entry += 1.0;
                    w.hessian[a * m + b] = entry;
                }
            }
            if (!shiftedSolve(material, w, m)) {
                w.failure = "the Newton finish found no positive definite shift of the Gibbs Hessian";
                return false;
            }
            // Step in mole numbers, cut so that no amount shrinks below a tenth of itself.
            double slope = 0.0;
            double length = 1.0;
            for (int a = 0; a < m; a++) {
                int i = material.present[a];
                double dv = w.scale[i] * w.step[a];
                w.direction[i] = dv;
                slope += w.gradient[i] * dv;
                if (dv < 0.0) length = Math.min(length, FRACTION_TO_BOUNDARY * w.vaporMoles[i] / -dv);
                else if (dv > 0.0) length = Math.min(length, FRACTION_TO_BOUNDARY * w.liquidMoles[i] / dv);
            }
            boolean accepted = false;
            double allowance = GIBBS_ROUNDOFF * w.gibbsScale;
            for (int halving = 0; halving <= NEWTON_HALVINGS; halving++, length *= 0.5) {
                for (int a = 0; a < m; a++) {
                    int i = material.present[a];
                    w.trialVapor[i] = w.vaporMoles[i] + length * w.direction[i];
                    w.trialLiquid[i] = w.liquidMoles[i] - length * w.direction[i];
                }
                double next;
                try {
                    next = gibbs(t, w.trialVapor, w.trialLiquid, material, w);
                } catch (IllegalArgumentException refused) {
                    continue;
                }
                if (next <= gibbs + ARMIJO * length * slope + allowance) {
                    gibbs = next;
                    accepted = true;
                    break;
                }
            }
            if (!accepted) {
                w.failure = "the Newton finish found no Gibbs-energy decrease (max |ln f_V - ln f_L| " + largest + ")";
                return false;
            }
            for (int a = 0; a < m; a++) {
                int i = material.present[a];
                w.vaporMoles[i] = w.trialVapor[i];
                w.liquidMoles[i] = w.trialLiquid[i];
            }
            w.newtonIterations++;
        }
        // The amounts: each component's smaller share by its own mole number, the larger by difference.
        Arrays.fill(w.vaporAmounts, 0.0);
        Arrays.fill(w.liquidAmounts, 0.0);
        for (int a = 0; a < m; a++) {
            int i = material.present[a];
            double v = w.vaporMoles[i];
            double l = w.liquidMoles[i];
            if (v <= l) {
                w.vaporAmounts[i] = Math.min(material.amounts[i], material.amounts[i] * (v / (v + l)));
                w.liquidAmounts[i] = material.amounts[i] - w.vaporAmounts[i];
            } else {
                w.liquidAmounts[i] = Math.min(material.amounts[i], material.amounts[i] * (l / (v + l)));
                w.vaporAmounts[i] = material.amounts[i] - w.liquidAmounts[i];
            }
        }
        return true;
    }

    /**
     * A split below the feed's Gibbs energy from the stability test's stationary composition {@code w}: that phase in the
     * amount {@code eps w}, the rest {@code z - eps w}, with {@code eps} halved from half the largest admissible value
     * until {@code Delta G < 0} (to first order {@code Delta G = eps D(w) < 0}). NaN when the test found no negative
     * stationary point or no amount works.
     */
    private double startFromStationaryPhase(double t, TangentPlaneStability.Result feed, Feed material, Workspace w) {
        if (!(feed.minimumTangentPlaneDistance() < 0.0)) return Double.NaN;
        double largest = Double.POSITIVE_INFINITY;
        for (int a = 0; a < material.presentCount; a++) {
            int i = material.present[a];
            if (w.trial[i] > 0.0) largest = Math.min(largest, material.z[i] / w.trial[i]);
        }
        if (!(largest > 0.0) || !Double.isFinite(largest)) return Double.NaN;
        double amount = 0.5 * Math.min(largest, 1.0);
        for (int halving = 0; halving < START_HALVINGS; halving++, amount *= 0.5) {
            for (int a = 0; a < material.presentCount; a++) {
                int i = material.present[a];
                double part = amount * w.trial[i];
                double rest = material.z[i] - part;
                if (w.trialIsVapour) {
                    w.vaporMoles[i] = part;
                    w.liquidMoles[i] = rest;
                } else {
                    w.liquidMoles[i] = part;
                    w.vaporMoles[i] = rest;
                }
            }
            double gibbs;
            try {
                gibbs = gibbs(t, w.vaporMoles, w.liquidMoles, material, w);
            } catch (IllegalArgumentException refused) {
                continue;
            }
            if (gibbs < -GIBBS_ROUNDOFF * w.gibbsScale) return gibbs;
        }
        return Double.NaN;
    }

    /**
     * {@code Delta G/RT} per mole of feed of the split {@code (v, l)} against the feed:
     * {@code sum_i v_i (ln y_i + ln phi_i^V + s_i - d_i) + l_i (ln x_i + ln phi_i^L - d_i)}; leaves the magnitude of its
     * summands in {@code gibbsScale} for the roundoff allowance.
     */
    private double gibbs(double t, double[] v, double[] l, Feed material, Workspace w) {
        double vapourTotal = 0.0;
        double liquidTotal = 0.0;
        for (int a = 0; a < material.presentCount; a++) {
            int i = material.present[a];
            vapourTotal += v[i];
            liquidTotal += l[i];
        }
        Arrays.fill(w.x, 0.0);
        Arrays.fill(w.y, 0.0);
        for (int a = 0; a < material.presentCount; a++) {
            int i = material.present[a];
            w.y[i] = v[i] / vapourTotal;
            w.x[i] = l[i] / liquidTotal;
        }
        phaseLogPhi(t, w.y, true, w.vaporLogPhi, w);
        phaseLogPhi(t, w.x, false, w.liquidLogPhi, w);
        double sum = 0.0;
        double magnitude = 0.0;
        for (int a = 0; a < material.presentCount; a++) {
            int i = material.present[a];
            double vapourTerm = DeterministicMath.log(w.y[i]) + w.vaporLogPhi[i];
            double liquidTerm = DeterministicMath.log(w.x[i]) + w.liquidLogPhi[i];
            sum += v[i] * (vapourTerm - w.reference[i]) + l[i] * (liquidTerm - w.reference[i]);
            magnitude += v[i] * (Math.abs(vapourTerm) + Math.abs(w.reference[i]))
                    + l[i] * (Math.abs(liquidTerm) + Math.abs(w.reference[i]));
        }
        w.gibbsScale = magnitude;
        if (!Double.isFinite(sum)) throw new IllegalArgumentException("Non-finite Gibbs energy of a trial split");
        return sum;
    }

    /**
     * Solves {@code (H + mu I) s = -s g} in place into {@code step} for the scaled Hessian in {@code hessian}, with
     * {@code mu} zero or the first of {@code 1e-8, 1e-7, ..., 1e4} that makes it positive definite.
     */
    private static boolean shiftedSolve(Feed material, Workspace w, int m) {
        double[] h = w.hessian;
        for (int attempt = 0; attempt < 14; attempt++) {
            double mu = SHIFTS_FROM_1E8[attempt];
            if (SmallDenseSolve.factorShifted(h, m, mu, w.factor)) {
                for (int a = 0; a < m; a++) {
                    int i = material.present[a];
                    w.step[a] = -w.scale[i] * w.gradient[i];
                }
                SmallDenseSolve.solveCholesky(w.factor, m, w.step);
                return true;
            }
        }
        return false;
    }

    // Phase evaluations at the workspace's pressure.

    /** {@code ln phi_i} of a composition on its lower-Gibbs root (the side's root at a tie) into {@code out}. */
    private Root phaseLogPhi(double t, double[] composition, boolean vapourSide, double[] out, Workspace w) {
        return lowerGibbsRoot(t, composition, vapourSide ? Root.VAPOR : Root.LIQUID, out, w);
    }

    /** Derivatives of one side's phase on its lower-Gibbs root. */
    PengRobinsonKernel.Evaluation differentiatePhase(double t, double[] amounts, boolean vapourSide,
                                                     PengRobinsonKernel.Derivatives out, Workspace w) {
        Root preferred = vapourSide ? Root.VAPOR : Root.LIQUID;
        kernel.evaluateDerivatives(t, w.pressure, amounts, preferred, w.mixture, out);
        w.kernelEvaluations++;
        w.derivativeEvaluations++;
        if (out.evaluation().physicalRootCount() > 1) {
            Root other = vapourSide ? Root.LIQUID : Root.VAPOR;
            kernel.evaluate(t, w.pressure, amounts, other, w.mixture, w.second);
            w.kernelEvaluations++;
            if (residualGibbs(w.second, amounts) < residualGibbs(out.evaluation(), amounts)) {
                kernel.evaluateDerivatives(t, w.pressure, amounts, other, w.mixture, out);
                w.kernelEvaluations++;
                w.derivativeEvaluations++;
            }
        }
        return out.evaluation();
    }

    /**
     * Rachford-Rice for the current {@code ln K}, solved in the minor phase's fraction: the vapour fraction {@code beta}
     * of {@code sum z_i (K_i - 1)/(1 + beta (K_i - 1)) = 0} when the root lies at or below one half, otherwise the liquid
     * fraction {@code delta = 1 - beta} of the same equation ({@code 1 + beta (K_i - 1) = K_i - delta (K_i - 1)}), so the
     * minor fraction carries full relative precision (a liquid of 1e-14 of the feed is {@code delta = 1e-14}, a split
     * while {@code delta > 0}). The first evaluation, at one half, tells which phase is minor; safeguarded Newton with
     * bisection between that fraction's pole and one half, stopped when a step moves the fraction by at most 1e-15 of
     * itself. Fills {@code k}, the normalised {@code x} and {@code y}, and both fractions; false when the K-values
     * straddle no split.
     */
    private boolean split(Feed material, Workspace w) {
        double smallest = Double.POSITIVE_INFINITY;
        double largest = Double.NEGATIVE_INFINITY;
        for (int a = 0; a < material.presentCount; a++) {
            int i = material.present[a];
            w.k[i] = DeterministicMath.exp(Math.clamp(w.logK[i], -LOG_K_BOUND, LOG_K_BOUND));
            smallest = Math.min(smallest, w.k[i]);
            largest = Math.max(largest, w.k[i]);
        }
        if (!(smallest < 1.0 && largest > 1.0)) return false;
        boolean liquidMinor = false;
        double lower = 1.0 / (1.0 - largest);
        double upper = 1.0 / (1.0 - smallest);
        double fraction = 0.5;
        for (int step = 0; step < MAXIMUM_RACHFORD_RICE_STEPS; step++) {
            double f = 0.0;
            double slope = 0.0;
            for (int a = 0; a < material.presentCount; a++) {
                int i = material.present[a];
                double d = w.k[i] - 1.0;
                double denominator = liquidMinor ? w.k[i] - fraction * d : 1.0 + fraction * d;
                f += material.z[i] * d / denominator;
                slope -= material.z[i] * d * d / (denominator * denominator);
            }
            w.rachfordRiceSteps++;
            if (f == 0.0) break;
            if (liquidMinor) {
                // The sum increases in delta; its negative decreases like the vapour form's, with the slope above.
                f = -f;
            } else if (step == 0 && f > 0.0) {
                // The root lies above one half: the liquid is the minor phase. At one half the delta form's negated sum
                // is -f with this slope; its window runs from the pole of the smallest K.
                liquidMinor = true;
                f = -f;
                lower = smallest / (smallest - 1.0);
                upper = largest / (largest - 1.0);
            }
            // f decreases in the fraction: a positive value puts the root above it.
            if (f > 0.0) lower = fraction; else upper = fraction;
            double next = fraction - f / slope;
            if (!(next > lower && next < upper)) next = 0.5 * (lower + upper);
            if (next == fraction || Math.abs(next - fraction) <= 1.0e-15 * Math.abs(fraction)) {
                fraction = next;
                break;
            }
            fraction = next;
        }
        w.liquidMinor = liquidMinor;
        w.vapourFraction = liquidMinor ? 1.0 - fraction : fraction;
        w.liquidFraction = liquidMinor ? fraction : 1.0 - fraction;
        double sumX = 0.0;
        double sumY = 0.0;
        Arrays.fill(w.x, 0.0);
        Arrays.fill(w.y, 0.0);
        for (int a = 0; a < material.presentCount; a++) {
            int i = material.present[a];
            double xi = material.z[i] / splitDenominator(w, i);
            w.x[i] = xi;
            w.y[i] = w.k[i] * xi;
            sumX += w.x[i];
            sumY += w.y[i];
        }
        for (int a = 0; a < material.presentCount; a++) {
            int i = material.present[a];
            w.x[i] /= sumX;
            w.y[i] /= sumY;
        }
        return true;
    }

    /** {@link #split} with both fractions positive: the K-values divide the feed into two populated phases. */
    private boolean twoPhaseSplit(Feed material, Workspace w) {
        return split(material, w) && w.vapourFraction > 0.0 && w.liquidFraction > 0.0;
    }

    /**
     * Component {@code i}'s {@code 1 + beta (K_i - 1)} of the last {@link #split}, written in the minor phase's fraction
     * ({@code K_i - delta (K_i - 1)} when the liquid is minor), so that it keeps that fraction's precision: component
     * {@code i}'s liquid share is {@code delta} over it and its vapour share {@code beta K_i} over it.
     */
    private static double splitDenominator(Workspace w, int i) {
        double d = w.k[i] - 1.0;
        return w.liquidMinor ? w.k[i] - w.liquidFraction * d : 1.0 + w.vapourFraction * d;
    }

    /** Evaluates {@code composition} on its lower-Gibbs root (the preferred one at a tie), copying its {@code ln phi}. */
    Root lowerGibbsRoot(double t, double[] composition, Root preferred, double[] logPhi, Workspace w) {
        double p = w.pressure;
        kernel.evaluate(t, p, composition, preferred, w.mixture, w.first);
        w.kernelEvaluations++;
        PengRobinsonKernel.Evaluation chosen = w.first;
        Root root = preferred;
        if (w.first.physicalRootCount() > 1) {
            Root other = preferred == Root.LIQUID ? Root.VAPOR : Root.LIQUID;
            kernel.evaluate(t, p, composition, other, w.mixture, w.second);
            w.kernelEvaluations++;
            if (residualGibbs(w.second, composition) < residualGibbs(w.first, composition)) {
                chosen = w.second;
                root = other;
            }
        }
        System.arraycopy(chosen.logFugacityCoefficientsView(), 0, logPhi, 0, count);
        w.chosen = chosen;
        return root;
    }

    /** {@code sum n_i ln phi_i}: at one composition the root with the smaller value has the lower Gibbs energy. */
    private double residualGibbs(PengRobinsonKernel.Evaluation evaluation, double[] composition) {
        double[] logPhi = evaluation.logFugacityCoefficientsView();
        double sum = 0.0;
        for (int i = 0; i < count; i++) if (composition[i] > 0.0) sum += composition[i] * logPhi[i];
        return sum;
    }
}
