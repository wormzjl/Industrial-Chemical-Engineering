package com.wormzjl.icheme.science.thermo.phase;

import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.ARMIJO;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.FAILED;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.FRACTION_TO_BOUNDARY;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.FUGACITY_TOLERANCE;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.GIBBS_ROUNDOFF;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.MERGED;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.NEWTON_HALVINGS;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.SHIFTS_FROM_1E13;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.SHIFTS_FROM_1E8;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.SINGLE;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.SLOW_EIGENVALUE;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.SLOW_SUBSTITUTION_STEPS;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.SPLIT;
import static com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium.THREE;

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
 * The three-slot system of {@link FluidTpEquilibrium} (W3a D3, S01): three fluid phases as one system, for an unstable
 * feed of a derived list holding a polar compound with water in the equation of state. The dry core hands it the feed
 * and the workspace ({@link FluidTpEquilibrium#threeSlotFeed} decides); it answers the core's outcome (SINGLE, SPLIT,
 * MERGED, THREE or FAILED) and leaves the products in the workspace as the two-slot path does. It reads the engine's
 * kernel, evaluator, settings and stability test, given once at construction, and calls back into the engine only for
 * the counted stability test ({@code stabilityOf}); the lower-Gibbs root of a composition is the two-phase flash's
 * ({@link TwoPhaseFlash#lowerGibbsRoot}, {@link TwoPhaseFlash#differentiatePhase}). Immutable; one instance per engine.
 */
final class ThreeSlotFlash {
    // The three-slot system (W3a D3).
    private static final int SLOT_COUNT = 3;
    /**
     * A watched slot absent this many steps in a row with {@code sum x <} {@link #WATCH_SUM} is dropped: below one its
     * phase is absent (the tangent plane lies under it), and the audit still reseeds a phase it missed (W3b step 1c: at
     * 0.9 an absent hexane-rich slot at 0.95 was evaluated on both roots at every step, 366 kernel evaluations instead of
     * 282 for ethanol, water and n-hexane at 58 C).
     */
    private static final int WATCH_STEPS = 3;
    private static final double WATCH_SUM = 1.0;
    /** Halvings of a refused trial composition toward the slot's last accepted one. */
    private static final int REFUSED_HALVINGS = 8;
    /** Two present slots on one root within this {@code sum z (ln x - ln x')^2} are one phase. */
    private static final double SLOT_DUPLICATE = 1.0e-8;
    /** Two slots of one composition whose compressibility factors agree within this share are on one root. */
    private static final double SLOT_SAME_ROOT = 1.0e-2;
    /** Newton steps of the slots' Rachford-Rice, and its tolerance on the gradient {@code 1 - sum_i x_ik}. */
    private static final int RACHFORD_RICE_SLOTS_ITERATIONS = 100;
    private static final double SLOT_FRACTION_TOLERANCE = 1.0e-13;

    private final FluidTpEquilibrium engine;
    private final TwoPhaseFlash twoPhase;
    private final PengRobinsonKernel kernel;
    private final CubicPhaseEvaluator evaluator;
    private final Settings settings;
    private final TangentPlaneStability stability;
    private final int count;
    /** Per component, polar by its group counts (the engine's list). */
    private final boolean[] polar;
    /** The cubic's water component, -1 without one. */
    private final int cubicWater;

    ThreeSlotFlash(FluidTpEquilibrium engine, TwoPhaseFlash twoPhase, PengRobinsonKernel kernel, CubicPhaseEvaluator evaluator,
                   Settings settings, TangentPlaneStability stability, int count, boolean[] polar, int cubicWater) {
        this.engine = engine;
        this.twoPhase = twoPhase;
        this.kernel = kernel;
        this.evaluator = evaluator;
        this.settings = settings;
        this.stability = stability;
        this.count = count;
        this.polar = polar;
        this.cubicWater = cubicWater;
    }

    /**
     * The three-slot system (W3a D3): slots vapour, liquid and second liquid; for fixed {@code ln phi} of each slot the phase
     * fractions minimise Michelsen's convex {@code Q(beta) = sum_k beta_k - sum_i z_i ln(sum_k beta_k / phi_ik)} over
     * {@code beta >= 0} ({@link #slotFractions}), whose optimality conditions are the presence rows: {@code beta_k >= 0},
     * {@code 1 - sum_i x_ik >= 0}, their product zero, {@code x_ik = z_i / (phi_ik E_i)}. Successive substitution on the
     * stacked {@code ln phi} with the dominant-eigenvalue extrapolation, then the Newton finish on the Gibbs energy over the
     * mole numbers of the phases present ({@link #slotNewton}). Seeds: Wilson's vapour, Wilson's liquid of the non-polar
     * compounds (of the whole feed without one), and the water-rich (else the largest polar compound's) liquid. An absent slot is watched (one evaluation per step) until it has been
     * absent with {@code sum x <} {@link #WATCH_SUM} for {@link #WATCH_STEPS} steps; two present slots that coincide are one.
     * The products are audited by the stability test; a failed audit reseeds an absent slot from its stationary trial,
     * once; a second failure is {@link EquilibriumResult#UNSTABLE_PRODUCT}. Returns SINGLE, SPLIT, MERGED, THREE or FAILED.
     */
    int threeSlot(double t, TangentPlaneStability.Result feed, Feed material, Workspace w) {
        Slots s = w.slots;
        int n = count;
        kernel.wilsonK(t, w.pressure, w.k);
        int seedPolar = cubicWater >= 0 && material.amounts[cubicWater] > 0.0 ? cubicWater : -1;
        if (seedPolar < 0) {
            for (int a = 0; a < material.presentCount; a++) {
                int i = material.present[a];
                if (polar[i] && (seedPolar < 0 || material.z[i] > material.z[seedPolar])) seedPolar = i;
            }
        }
        boolean nonPolar = false;
        for (int a = 0; a < material.presentCount; a++) nonPolar |= !polar[material.present[a]];
        for (int k = 0; k < SLOT_COUNT; k++) {
            Arrays.fill(s.composition[k], 0.0);
            double sum = 0.0;
            for (int a = 0; a < material.presentCount; a++) {
                int i = material.present[a];
                // The first liquid is Wilson's liquid of the non-polar compounds, the polar ones at a trace, by the rule the
                // stability test's liquid trial follows for water (TangentPlaneStability.SEED_TRACE; W3b step 1c: Wilson's
                // liquid of the whole feed was water-rich like the second seed, so the oil-rich liquid was never seeded and the
                // ternary at its bubble point converged to a vapour and the aqueous liquid first, then failed its audit, 783
                // kernel evaluations instead of 515). Without a non-polar compound, Wilson's liquid of the whole feed.
                double liquid = material.z[i] / w.k[i];
                if (k == 1 && nonPolar && polar[i]) liquid *= TangentPlaneStability.SEED_TRACE;
                double value = k == 0 ? material.z[i] * w.k[i] : k == 1 ? liquid : i == seedPolar ? 1.0 : TangentPlaneStability.SEED_TRACE * material.z[i];
                s.composition[k][i] = value;
                sum += value;
            }
            for (int a = 0; a < material.presentCount; a++) s.composition[k][material.present[a]] /= sum;
            System.arraycopy(s.composition[k], 0, s.accepted[k], 0, n);
            s.watched[k] = true;
            s.absentSteps[k] = 0;
            s.beta[k] = 1.0 / SLOT_COUNT;
            evaluateSlot(t, k, s.composition[k], w);
        }
        s.reseeded = false;
        w.tieLine = Double.NaN;
        w.seedDistance = Double.NaN;
        // d_i of the feed on its stability root: the Newton finish's reference.
        kernel.evaluate(t, w.pressure, material.z, feed.feedRoot(), w.mixture, w.first);
        w.kernelEvaluations++;
        for (int a = 0; a < material.presentCount; a++) {
            int i = material.present[a];
            w.reference[i] = DeterministicMath.log(material.z[i]) + w.first.logFugacityCoefficient(i);
        }
        while (true) {
            if (!slotSubstitution(t, material, w)) return FAILED;
            int present = slotsPresent(s);
            // The audit: every present product against the stability test.
            int reseed = -1;
            double[] trial = null;
            Root trialRoot = null;
            TangentPlaneStability.Verdict worst = TangentPlaneStability.Verdict.STABLE;
            w.productDistance = Double.POSITIVE_INFINITY;
            for (int k = 0; k < SLOT_COUNT; k++) {
                if (!s.present[k]) continue;
                TangentPlaneStability.Result check = engine.stabilityOf(stability, t, s.moles[k], material, w);
                w.productDistance = Math.min(w.productDistance, check.minimumTangentPlaneDistance());
                if (check.verdict() != TangentPlaneStability.Verdict.STABLE) {
                    if (worst != TangentPlaneStability.Verdict.UNSTABLE) worst = check.verdict();
                    if (trial == null && check.verdict() == TangentPlaneStability.Verdict.UNSTABLE) {
                        trial = check.trialComposition();
                        trialRoot = check.trialRoot();
                    }
                }
            }
            if (worst == TangentPlaneStability.Verdict.STABLE) return slotOutcome(t, present, material, w);
            if (trial != null && !s.reseeded) {
                for (int k = trialRoot == Root.VAPOR ? 0 : 1; k < SLOT_COUNT && reseed < 0; k++) if (!s.present[k]) reseed = k;
                for (int k = 0; k < SLOT_COUNT && reseed < 0; k++) if (!s.present[k]) reseed = k;
            }
            if (reseed < 0) {
                w.failure = (worst == TangentPlaneStability.Verdict.UNSTABLE ? EquilibriumResult.UNSTABLE_PRODUCT
                        : EquilibriumResult.UNRESOLVED_PRODUCT) + ": a product of the three-slot system is not stable ("
                        + present + " phases present" + (s.reseeded ? ", after one reseed" : "") + ")";
                return FAILED;
            }
            s.reseeded = true;
            System.arraycopy(trial, 0, s.composition[reseed], 0, n);
            System.arraycopy(trial, 0, s.accepted[reseed], 0, n);
            s.watched[reseed] = true;
            s.absentSteps[reseed] = 0;
            s.beta[reseed] = 0.0;
            evaluateSlot(t, reseed, s.composition[reseed], w);
        }
    }

    /** ln phi of slot {@code k} at {@code composition} on its lower-Gibbs root; a refused trial is halved toward the last accepted one. */
    private void evaluateSlot(double t, int k, double[] composition, Workspace w) {
        Slots s = w.slots;
        Root preferred = k == 0 ? Root.VAPOR : Root.LIQUID;
        for (int halving = 0; ; halving++) {
            try {
                s.root[k] = twoPhase.lowerGibbsRoot(t, composition, preferred, s.logPhi[k], w);
                s.compressibility[k] = w.chosen.compressibility();
                System.arraycopy(composition, 0, s.accepted[k], 0, count);
                return;
            } catch (IllegalArgumentException | IllegalStateException refused) {
                // D3: the Huron-Vidal attraction can turn negative for a trial composition: a rejected trial, never a hold.
                if (halving >= REFUSED_HALVINGS) throw refused;
                for (int i = 0; i < count; i++) composition[i] = 0.5 * (composition[i] + s.accepted[k][i]);
            }
        }
    }

    /**
     * Substitution on the stacked {@code ln phi} of the watched slots until no entry moves by {@link Settings#lnKTolerance()},
     * then the mole numbers ({@code s.moles}) and presence; the Newton finish where substitution is slow. False with
     * {@code failure} when neither converges.
     */
    private boolean slotSubstitution(double t, Feed material, Workspace w) {
        Slots s = w.slots;
        boolean hasPrevious = false, newtonTried = false;
        int plainSteps = 0, steps = 0;
        w.lambda = 0.0;
        while (true) {
            slotFractions(material, w);
            // Presence of the step: watched slots with a positive fraction; a duplicate of a lower slot is dropped.
            for (int k = 0; k < SLOT_COUNT; k++) s.present[k] = s.watched[k] && s.beta[k] > 0.0;
            if (dropDuplicates(material, w)) {
                hasPrevious = false;
                continue;
            }
            for (int k = 0; k < SLOT_COUNT; k++) {
                if (!s.watched[k] || s.present[k]) {
                    s.absentSteps[k] = 0;
                    continue;
                }
                s.absentSteps[k] = s.sums[k] < WATCH_SUM ? s.absentSteps[k] + 1 : 0;
                if (s.absentSteps[k] >= WATCH_STEPS) {
                    s.watched[k] = false;
                    hasPrevious = false;
                }
            }
            double change = 0.0;
            for (int k = 0; k < SLOT_COUNT; k++) {
                if (!s.watched[k]) continue;
                for (int a = 0; a < material.presentCount; a++) {
                    int i = material.present[a];
                    w.trial[i] = s.unnormalised[k][i] / s.sums[k];
                }
                for (int i = 0; i < count; i++) if (material.amounts[i] == 0.0) w.trial[i] = 0.0;
                System.arraycopy(w.trial, 0, s.composition[k], 0, count);
                double[] previous = s.newLogPhi[k];
                System.arraycopy(s.logPhi[k], 0, previous, 0, count);
                evaluateSlot(t, k, s.composition[k], w);
                for (int a = 0; a < material.presentCount; a++) {
                    int i = material.present[a];
                    double d = s.logPhi[k][i] - previous[i];
                    s.delta[k * count + i] = d;
                    change = Math.max(change, Math.abs(d));
                }
            }
            w.iterations++;
            steps++;
            w.lastChange = change;
            if (change < settings.lnKTolerance() && s.fractionsConverged) break;
            if (!Double.isFinite(change)) {
                w.failure = "the three-slot substitution met a non-finite ln phi";
                return false;
            }
            if (steps >= settings.maximumIterations()) {
                w.failure = "no convergence of the three-slot system in " + steps + " substitution iterations (max |d ln phi| " + change + ")";
                return false;
            }
            plainSteps++;
            boolean extrapolated = false;
            if (hasPrevious && plainSteps >= settings.accelerationCycle()) {
                double numerator = 0.0, denominator = 0.0;
                for (int k = 0; k < SLOT_COUNT; k++) {
                    if (!s.watched[k]) continue;
                    for (int a = 0; a < material.presentCount; a++) {
                        int v = k * count + material.present[a];
                        numerator += s.delta[v] * s.delta[v];
                        denominator += s.previousDelta[v] * s.delta[v];
                    }
                }
                double lambda = numerator / denominator;
                w.lambda = lambda;
                if (SubstitutionAcceleration.admissible(lambda)) {
                    double factor = SubstitutionAcceleration.factor(lambda);
                    double scale = SubstitutionAcceleration.scale(change, factor);
                    for (int k = 0; k < SLOT_COUNT; k++) {
                        if (!s.watched[k]) continue;
                        for (int a = 0; a < material.presentCount; a++) {
                            int i = material.present[a];
                            s.logPhi[k][i] += s.delta[k * count + i] * factor * scale;
                        }
                    }
                    extrapolated = true;
                    w.extrapolations++;
                }
                plainSteps = 0;
            }
            if (extrapolated) {
                hasPrevious = false;
            } else {
                double[] swap = s.previousDelta;
                s.previousDelta = s.delta;
                s.delta = swap;
                hasPrevious = true;
            }
            if (!newtonTried && slotsPresent(s) >= 2 && (steps >= settings.substitutionLimit()
                    || steps >= SLOW_SUBSTITUTION_STEPS && w.lambda > SLOW_EIGENVALUE)) {
                newtonTried = true;
                slotMoles(material, w);
                if (slotNewton(t, material, w)) return true;
                hasPrevious = false;
            }
        }
        slotFractions(material, w);
        for (int k = 0; k < SLOT_COUNT; k++) s.present[k] = s.watched[k] && s.beta[k] > 0.0;
        dropDuplicates(material, w);
        slotMoles(material, w);
        return true;
    }

    private static int slotsPresent(Slots s) {
        int present = 0;
        for (int k = 0; k < SLOT_COUNT; k++) if (s.present[k]) present++;
        return present;
    }

    /**
     * Drops one of two watched slots whose compositions agree within {@link #SLOT_DUPLICATE}
     * ({@code sum_i z_i (ln x_ik - ln x_il)^2}) on one root: one phase taken twice, which leaves Q's Hessian singular. The
     * present one (the lower where both are) stays and takes both fractions. One root is judged by the compressibility
     * factors the evaluations took, within {@link #SLOT_SAME_ROOT} of the larger, never by the requested label: where the
     * cubic has one root a vapour slot and a liquid slot evaluate the same phase (W3b step 1c: CO2 over water at 25 C with
     * an oil-rich seed kept the gas twice, a tie line of zero).
     */
    private boolean dropDuplicates(Feed material, Workspace w) {
        Slots s = w.slots;
        for (int k = 0; k < SLOT_COUNT; k++) {
            if (!s.watched[k]) continue;
            for (int l = k + 1; l < SLOT_COUNT; l++) {
                if (!s.watched[l]) continue;
                double zk = s.compressibility[k], zl = s.compressibility[l];
                if (!(Math.abs(zk - zl) <= SLOT_SAME_ROOT * Math.max(Math.abs(zk), Math.abs(zl)))) continue;
                double distance = 0.0;
                for (int a = 0; a < material.presentCount; a++) {
                    int i = material.present[a];
                    double d = DeterministicMath.log(s.composition[k][i]) - DeterministicMath.log(s.composition[l][i]);
                    distance += material.z[i] * d * d;
                }
                if (distance < SLOT_DUPLICATE) {
                    int kept = s.present[l] && !s.present[k] ? l : k, dropped = kept == k ? l : k;
                    s.watched[dropped] = false;
                    s.present[dropped] = false;
                    s.beta[kept] += s.beta[dropped];
                    s.beta[dropped] = 0.0;
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Michelsen's multiphase Rachford-Rice for the slots' current {@code ln phi}: the minimum of the convex {@code Q(beta)}
     * over the watched slots, {@code beta >= 0}, by Newton on the free set (a fraction above zero, or its gradient
     * negative), a fraction-to-zero cap and an Armijo search on Q. Fills {@code beta}, the unnormalised
     * {@code x_ik = z_i c_ik / E_i} ({@code c_ik = exp(m_i - ln phi_ik)}, {@code m_i} the smallest ln phi of component i) and
     * their sums.
     */
    private void slotFractions(Feed material, Workspace w) {
        Slots s = w.slots;
        for (int a = 0; a < material.presentCount; a++) {
            int i = material.present[a];
            double least = Double.POSITIVE_INFINITY;
            for (int k = 0; k < SLOT_COUNT; k++) if (s.watched[k]) least = Math.min(least, s.logPhi[k][i]);
            for (int k = 0; k < SLOT_COUNT; k++) s.c[k * count + i] = s.watched[k] ? DeterministicMath.exp(least - s.logPhi[k][i]) : 0.0;
        }
        double total = 0.0;
        for (int k = 0; k < SLOT_COUNT; k++) {
            if (!s.watched[k]) s.beta[k] = 0.0;
            total += s.beta[k];
        }
        if (!(total > 0.0)) for (int k = 0; k < SLOT_COUNT; k++) if (s.watched[k]) s.beta[k] = 1.0;
        double q = slotObjective(s.beta, material, w);
        s.fractionsConverged = false;
        for (int iteration = 0; iteration < RACHFORD_RICE_SLOTS_ITERATIONS; iteration++) {
            w.rachfordRiceSteps++;
            // E_i, the gradient 1 - sum_i z_i c_ik / E_i and the Hessian sum_i z_i c_ik c_il / E_i^2.
            Arrays.fill(s.rrGradient, 1.0);
            Arrays.fill(s.rrHessian, 0.0);
            for (int a = 0; a < material.presentCount; a++) {
                int i = material.present[a];
                double e = 0.0;
                for (int k = 0; k < SLOT_COUNT; k++) e += s.beta[k] * s.c[k * count + i];
                for (int k = 0; k < SLOT_COUNT; k++) {
                    if (!s.watched[k]) continue;
                    double ck = material.z[i] * s.c[k * count + i] / e;
                    s.rrGradient[k] -= ck;
                    for (int l = 0; l <= k; l++) if (s.watched[l]) s.rrHessian[k * SLOT_COUNT + l] += ck * s.c[l * count + i] / e;
                }
            }
            int free = 0;
            boolean done = true;
            for (int k = 0; k < SLOT_COUNT; k++) {
                if (!s.watched[k]) continue;
                boolean isFree = s.beta[k] > 0.0 || s.rrGradient[k] < 0.0;
                if (isFree) {
                    s.freeSlots[free++] = k;
                    if (Math.abs(s.rrGradient[k]) > SLOT_FRACTION_TOLERANCE) done = false;
                }
            }
            if (done) {
                s.fractionsConverged = true;
                break;
            }
            // The Newton step on the free set. A slot at zero whose own row of the step points below zero is absent for
            // this step (section 12 of the W3b plan): it leaves the free set and the step is solved again on the rest. A
            // zero-length step blocked by such a slot would leave every fraction where it is (the wet crude at 393 K, 5 bar
            // held 1000 substitution iterations so).
            boolean factored = false;
            while (true) {
                for (int a = 0; a < free; a++) {
                    for (int b = 0; b <= a; b++) {
                        int k = s.freeSlots[a], l = s.freeSlots[b];
                        s.rrMatrix[a * free + b] = k >= l ? s.rrHessian[k * SLOT_COUNT + l] : s.rrHessian[l * SLOT_COUNT + k];
                    }
                    s.rrStep[a] = -s.rrGradient[s.freeSlots[a]];
                }
                factored = false;
                for (int attempt = 0; attempt < 14 && !factored; attempt++) {
                    double mu = SHIFTS_FROM_1E13[attempt];
                    factored = SmallDenseSolve.factorShifted(s.rrMatrix, free, mu, s.rrFactor);
                }
                if (!factored) break;
                SmallDenseSolve.solveCholesky(s.rrFactor, free, s.rrStep);
                int kept = 0;
                for (int a = 0; a < free; a++) if (s.beta[s.freeSlots[a]] > 0.0 || s.rrStep[a] >= 0.0) s.freeSlots[kept++] = s.freeSlots[a];
                if (kept == free || kept == 0) break;
                free = kept;
            }
            if (!factored) break;
            double length = 1.0, slope = 0.0;
            int blocking = -1;
            for (int a = 0; a < free; a++) {
                int k = s.freeSlots[a];
                slope += s.rrGradient[k] * s.rrStep[a];
                if (s.rrStep[a] < 0.0 && s.beta[k] + length * s.rrStep[a] < 0.0) {
                    length = s.beta[k] / -s.rrStep[a];
                    blocking = k;
                }
            }
            boolean accepted = false;
            for (int halving = 0; halving <= NEWTON_HALVINGS; halving++, length *= 0.5, blocking = -1) {
                Arrays.fill(s.trialBeta, 0.0);
                for (int k = 0; k < SLOT_COUNT; k++) s.trialBeta[k] = s.beta[k];
                for (int a = 0; a < free; a++) {
                    int k = s.freeSlots[a];
                    s.trialBeta[k] = k == blocking ? 0.0 : Math.max(0.0, s.beta[k] + length * s.rrStep[a]);
                }
                double next = slotObjective(s.trialBeta, material, w);
                if (next <= q + ARMIJO * length * slope + 1.0e-15 * Math.abs(q)) {
                    q = next;
                    System.arraycopy(s.trialBeta, 0, s.beta, 0, SLOT_COUNT);
                    accepted = true;
                    break;
                }
            }
            if (!accepted) break;
        }
        for (int k = 0; k < SLOT_COUNT; k++) {
            s.sums[k] = 0.0;
            Arrays.fill(s.unnormalised[k], 0.0);
        }
        for (int a = 0; a < material.presentCount; a++) {
            int i = material.present[a];
            double e = 0.0;
            for (int k = 0; k < SLOT_COUNT; k++) e += s.beta[k] * s.c[k * count + i];
            for (int k = 0; k < SLOT_COUNT; k++) {
                if (!s.watched[k]) continue;
                double x = material.z[i] * s.c[k * count + i] / e;
                s.unnormalised[k][i] = x;
                s.sums[k] += x;
            }
        }
    }

    /** {@code Q(beta) = sum_k beta_k - sum_i z_i ln E_i}; +infinity where an E_i is not positive. */
    private double slotObjective(double[] beta, Feed material, Workspace w) {
        Slots s = w.slots;
        double q = 0.0;
        for (int k = 0; k < SLOT_COUNT; k++) q += beta[k];
        for (int a = 0; a < material.presentCount; a++) {
            int i = material.present[a];
            double e = 0.0;
            for (int k = 0; k < SLOT_COUNT; k++) e += beta[k] * s.c[k * count + i];
            if (!(e > 0.0)) return Double.POSITIVE_INFINITY;
            q -= material.z[i] * DeterministicMath.log(e);
        }
        return q;
    }

    /** The present slots' mole numbers per mole of feed, {@code beta_k x_ik}, each component summing to its {@code z_i}. */
    private void slotMoles(Feed material, Workspace w) {
        Slots s = w.slots;
        for (int k = 0; k < SLOT_COUNT; k++) Arrays.fill(s.moles[k], 0.0);
        for (int a = 0; a < material.presentCount; a++) {
            int i = material.present[a];
            double e = 0.0;
            for (int k = 0; k < SLOT_COUNT; k++) if (s.present[k]) e += s.beta[k] * s.c[k * count + i];
            for (int k = 0; k < SLOT_COUNT; k++) if (s.present[k]) s.moles[k][i] = material.z[i] * s.beta[k] * s.c[k * count + i] / e;
        }
    }

    /**
     * The Newton finish over the present slots (two or three): the Gibbs energy over the mole numbers of every present
     * slot but the largest (the reference, by difference), gradient {@code mu_ik - mu_iR}, Hessian
     * {@code delta_kl M^k + M^R} with {@code M^k_ij = delta_ij/n_ik + (Phi^k_ij - 1)/N_k}, Jacobi-scaled and shifted until
     * Cholesky succeeds, the step cut so no amount shrinks below a tenth of itself, an Armijo search on {@code Delta G/RT}.
     * Converged at {@code max |g| <} {@link FluidTpEquilibrium#FUGACITY_TOLERANCE}; the slots' compositions and {@code ln phi} follow.
     */
    private boolean slotNewton(double t, Feed material, Workspace w) {
        Slots s = w.slots;
        int q = 0, reference = -1;
        double largest = -1.0;
        for (int k = 0; k < SLOT_COUNT; k++) {
            if (!s.present[k]) continue;
            double total = 0.0;
            for (int a = 0; a < material.presentCount; a++) total += s.moles[k][material.present[a]];
            if (total > largest) {
                largest = total;
                reference = k;
            }
        }
        for (int k = 0; k < SLOT_COUNT; k++) if (s.present[k] && k != reference) s.others[q++] = k;
        int m = material.presentCount, dimension = q * m;
        double gibbs = slotGibbs(t, s.moles, material, w);
        if (!Double.isFinite(gibbs)) return false;
        for (int iteration = 0; ; iteration++) {
            for (int k = 0; k < SLOT_COUNT; k++) {
                if (!s.present[k]) continue;
                try {
                    twoPhase.differentiatePhase(t, s.moles[k], k == 0, s.derivatives[k], w);
                } catch (IllegalStateException | IllegalArgumentException coalescing) {
                    return false;
                }
                s.totals[k] = 0.0;
                for (int a = 0; a < m; a++) s.totals[k] += s.moles[k][material.present[a]];
            }
            double worst = 0.0;
            for (int c = 0; c < q; c++) {
                int k = s.others[c];
                for (int a = 0; a < m; a++) {
                    int i = material.present[a];
                    double g = DeterministicMath.log(s.moles[k][i] / s.totals[k]) + s.derivatives[k].evaluation().logFugacityCoefficient(i)
                            - DeterministicMath.log(s.moles[reference][i] / s.totals[reference])
                            - s.derivatives[reference].evaluation().logFugacityCoefficient(i);
                    s.newtonGradient[c * m + a] = g;
                    worst = Math.max(worst, Math.abs(g));
                }
            }
            w.lastChange = worst;
            if (worst < FUGACITY_TOLERANCE) break;
            if (!Double.isFinite(worst) || iteration >= settings.maximumNewtonIterations()) return false;
            for (int c = 0; c < q; c++) {
                int k = s.others[c];
                for (int a = 0; a < m; a++) {
                    for (int d = 0; d <= c; d++) {
                        int l = s.others[d];
                        for (int b = 0; b < m && (d < c || b <= a); b++) {
                            double entry = slotCurvature(s, reference, a, b, material);
                            if (k == l) entry += slotCurvature(s, k, a, b, material);
                            s.newtonHessian[(c * m + a) * dimension + d * m + b] = entry;
                        }
                    }
                }
            }
            for (int v = 0; v < dimension; v++) s.newtonScale[v] = 1.0 / Math.sqrt(s.newtonHessian[v * dimension + v]);
            for (int v = 0; v < dimension; v++) {
                for (int u = 0; u <= v; u++) s.newtonHessian[v * dimension + u] *= s.newtonScale[v] * s.newtonScale[u];
                s.newtonStep[v] = -s.newtonScale[v] * s.newtonGradient[v];
            }
            boolean factored = false;
            for (int attempt = 0; attempt < 14 && !factored; attempt++) {
                double mu = SHIFTS_FROM_1E8[attempt];
                factored = SmallDenseSolve.factorShifted(s.newtonHessian, dimension, mu, s.newtonFactor);
            }
            if (!factored) return false;
            SmallDenseSolve.solveCholesky(s.newtonFactor, dimension, s.newtonStep);
            double slope = 0.0, length = 1.0;
            for (int v = 0; v < dimension; v++) {
                s.newtonStep[v] *= s.newtonScale[v];
                slope += s.newtonGradient[v] * s.newtonStep[v];
            }
            for (int a = 0; a < m; a++) {
                int i = material.present[a];
                double moved = 0.0;
                for (int c = 0; c < q; c++) {
                    double d = s.newtonStep[c * m + a];
                    moved += d;
                    if (d < 0.0) length = Math.min(length, FRACTION_TO_BOUNDARY * s.moles[s.others[c]][i] / -d);
                }
                if (moved > 0.0) length = Math.min(length, FRACTION_TO_BOUNDARY * s.moles[reference][i] / moved);
            }
            boolean accepted = false;
            for (int halving = 0; halving <= NEWTON_HALVINGS; halving++, length *= 0.5) {
                for (int k = 0; k < SLOT_COUNT; k++) System.arraycopy(s.moles[k], 0, s.trialMoles[k], 0, count);
                for (int a = 0; a < m; a++) {
                    int i = material.present[a];
                    for (int c = 0; c < q; c++) {
                        double d = length * s.newtonStep[c * m + a];
                        s.trialMoles[s.others[c]][i] += d;
                        s.trialMoles[reference][i] -= d;
                    }
                }
                double next;
                try {
                    next = slotGibbs(t, s.trialMoles, material, w);
                } catch (IllegalArgumentException | IllegalStateException refused) {
                    continue;
                }
                if (next <= gibbs + ARMIJO * length * slope + GIBBS_ROUNDOFF * w.gibbsScale) {
                    gibbs = next;
                    accepted = true;
                    break;
                }
            }
            if (!accepted) return false;
            for (int k = 0; k < SLOT_COUNT; k++) System.arraycopy(s.trialMoles[k], 0, s.moles[k], 0, count);
            w.newtonIterations++;
        }
        // The slots follow the converged mole numbers.
        for (int k = 0; k < SLOT_COUNT; k++) {
            if (!s.present[k]) continue;
            for (int i = 0; i < count; i++) s.composition[k][i] = s.moles[k][i] / s.totals[k];
            s.beta[k] = s.totals[k];
            evaluateSlot(t, k, s.composition[k], w);
        }
        return true;
    }

    /** {@code M^k_ab = delta_ab / n_ak + (Phi^k_ab - 1) / N_k} of slot k, symmetrised, over present components a and b. */
    private static double slotCurvature(Slots s, int k, int a, int b, Feed material) {
        int i = material.present[a], j = material.present[b];
        double phi = 0.5 * (s.derivatives[k].dLogPhiDnRowView(i)[j] + s.derivatives[k].dLogPhiDnRowView(j)[i]);
        return (a == b ? 1.0 / s.moles[k][i] : 0.0) + (phi - 1.0) / s.totals[k];
    }

    /** {@code Delta G/RT} of the present slots' mole numbers against the feed's tangent plane; the magnitude in gibbsScale. */
    private double slotGibbs(double t, double[][] moles, Feed material, Workspace w) {
        Slots s = w.slots;
        double sum = 0.0, magnitude = 0.0;
        for (int k = 0; k < SLOT_COUNT; k++) {
            if (!s.present[k]) continue;
            double total = 0.0;
            for (int a = 0; a < material.presentCount; a++) total += moles[k][material.present[a]];
            Arrays.fill(w.x, 0.0);
            for (int a = 0; a < material.presentCount; a++) w.x[material.present[a]] = moles[k][material.present[a]] / total;
            twoPhase.lowerGibbsRoot(t, w.x, k == 0 ? Root.VAPOR : Root.LIQUID, w.liquidLogPhi, w);
            for (int a = 0; a < material.presentCount; a++) {
                int i = material.present[a];
                double term = DeterministicMath.log(w.x[i]) + w.liquidLogPhi[i];
                sum += moles[k][i] * (term - w.reference[i]);
                magnitude += moles[k][i] * (Math.abs(term) + Math.abs(w.reference[i]));
            }
        }
        w.gibbsScale = magnitude;
        if (!Double.isFinite(sum)) throw new IllegalArgumentException("Non-finite Gibbs energy of a trial three-slot split");
        return sum;
    }

    /**
     * The converged slots as the core's outcome: one present slot is the feed (SINGLE on its root); two fill the two-slot
     * workspace (the denser the liquid) for {@link FluidTpEquilibrium#twoPhaseResult}, merged where the split is below the model's
     * resolution; three are THREE for {@link FluidTpEquilibrium#threePhaseResult}. Each component's amounts: every share but the largest by
     * its own mole number, the largest by difference, so each component conserves to rounding.
     */
    private int slotOutcome(double t, int present, Feed material, Workspace w) {
        Slots s = w.slots;
        for (int k = 0; k < SLOT_COUNT; k++) Arrays.fill(s.amounts[k], 0.0);
        for (int a = 0; a < material.presentCount; a++) {
            int i = material.present[a];
            double sum = 0.0;
            int major = -1;
            for (int k = 0; k < SLOT_COUNT; k++) {
                if (!s.present[k]) continue;
                sum += s.moles[k][i];
                if (major < 0 || s.moles[k][i] > s.moles[major][i]) major = k;
            }
            double rest = material.amounts[i];
            for (int k = 0; k < SLOT_COUNT; k++) {
                if (!s.present[k] || k == major) continue;
                s.amounts[k][i] = Math.min(rest, material.amounts[i] * (s.moles[k][i] / sum));
                rest -= s.amounts[k][i];
            }
            s.amounts[major][i] = rest;
        }
        if (present == 1) {
            for (int k = 0; k < SLOT_COUNT; k++) if (s.present[k]) w.singleRoot = s.root[k];
            return SINGLE;
        }
        // Tie lines between the present slots; the shortest decides the band.
        double shortest = Double.POSITIVE_INFINITY;
        for (int k = 0; k < SLOT_COUNT; k++) {
            for (int l = k + 1; l < SLOT_COUNT; l++) {
                if (!s.present[k] || !s.present[l]) continue;
                double tie = 0.0;
                for (int a = 0; a < material.presentCount; a++) {
                    int i = material.present[a];
                    double d = DeterministicMath.log(s.composition[k][i]) - DeterministicMath.log(s.composition[l][i]);
                    tie += material.z[i] * d * d;
                }
                shortest = Math.min(shortest, tie);
            }
        }
        w.tieLine = shortest;
        for (int k = 0; k < SLOT_COUNT; k++) {
            if (!s.present[k]) continue;
            kernel.evaluate(t, w.pressure, s.amounts[k], s.root[k], w.mixture, w.first);
            w.kernelEvaluations++;
            s.dense[k] = PhaseIdentification.denseLiquidLike(w.first, t, w.pressure);
            evaluator.evaluate(t, w.pressure, s.amounts[k], s.root[k], w.phases, s.states[k]);
            w.kernelEvaluations++;
        }
        if (present == 3) return THREE;
        int first = -1, second = -1;
        for (int k = 0; k < SLOT_COUNT; k++) {
            if (!s.present[k]) continue;
            if (first < 0) first = k; else second = k;
        }
        boolean firstDenser = s.states[first].molarVolume() < s.states[second].molarVolume();
        int liquid = firstDenser ? first : second, vapour = firstDenser ? second : first;
        System.arraycopy(s.amounts[liquid], 0, w.liquidAmounts, 0, count);
        System.arraycopy(s.amounts[vapour], 0, w.vaporAmounts, 0, count);
        w.liquidProductDenseLiquidLike = s.dense[liquid];
        w.vapourProductDenseLiquidLike = s.dense[vapour];
        evaluator.evaluate(t, w.pressure, w.liquidAmounts, s.root[liquid], w.phases, w.liquidState);
        evaluator.evaluate(t, w.pressure, w.vaporAmounts, s.root[vapour], w.phases, w.vaporState);
        w.ownSplit = true;
        w.kernelEvaluations += 2;
        double gap = Math.abs(w.vaporState.molarVolume() - w.liquidState.molarVolume())
                / (0.5 * (w.vaporState.molarVolume() + w.liquidState.molarVolume()));
        if (w.tieLine < settings.mergeTieLine() && gap < settings.mergeVolumeGap()) return MERGED;
        double residual = 0.0;
        for (int a = 0; a < material.presentCount; a++) {
            int i = material.present[a];
            double difference = DeterministicMath.log(w.liquidState.moleFraction(i)) + w.liquidState.logFugacityCoefficient(i)
                    - DeterministicMath.log(w.vaporState.moleFraction(i)) - w.vaporState.logFugacityCoefficient(i);
            if (Double.isFinite(difference)) residual = Math.max(residual, Math.abs(difference));
        }
        w.residual = residual;
        return SPLIT;
    }

    /** The three-slot system's scratch (W3a D3), in the workspace of an engine with water in the equation of state. */
    static final class Slots {
        final double[][] logPhi, newLogPhi, composition, accepted, unnormalised, moles, trialMoles, amounts;
        final double[] beta = new double[SLOT_COUNT], sums = new double[SLOT_COUNT], totals = new double[SLOT_COUNT];
        final double[] trialBeta = new double[SLOT_COUNT], rrGradient = new double[SLOT_COUNT];
        final double[] rrHessian = new double[SLOT_COUNT * SLOT_COUNT], rrMatrix = new double[SLOT_COUNT * SLOT_COUNT];
        final double[] rrFactor = new double[SLOT_COUNT * SLOT_COUNT], rrStep = new double[SLOT_COUNT];
        final int[] freeSlots = new int[SLOT_COUNT], others = new int[SLOT_COUNT], absentSteps = new int[SLOT_COUNT];
        final boolean[] watched = new boolean[SLOT_COUNT], present = new boolean[SLOT_COUNT], dense = new boolean[SLOT_COUNT];
        final Root[] root = new Root[SLOT_COUNT];
        /** The compressibility factor of each slot's last evaluation, on the root it took. */
        final double[] compressibility = new double[SLOT_COUNT];
        final double[] c;
        double[] delta, previousDelta;
        final double[] newtonGradient, newtonHessian, newtonFactor, newtonStep, newtonScale;
        final PengRobinsonKernel.Derivatives[] derivatives = new PengRobinsonKernel.Derivatives[SLOT_COUNT];
        final PhaseState[] states = new PhaseState[SLOT_COUNT];
        boolean reseeded, fractionsConverged;
        final double[] threeU = new double[4], threeR = new double[4], threeTrial = new double[4], threeShifted = new double[4];
        final double[] threeJacobian = new double[16], threeStep = new double[4];

        Slots(ThreeSlotFlash owner) {
            int n = owner.count;
            logPhi = new double[SLOT_COUNT][n];
            newLogPhi = new double[SLOT_COUNT][n];
            composition = new double[SLOT_COUNT][n];
            accepted = new double[SLOT_COUNT][n];
            unnormalised = new double[SLOT_COUNT][n];
            moles = new double[SLOT_COUNT][n];
            trialMoles = new double[SLOT_COUNT][n];
            amounts = new double[SLOT_COUNT][n];
            c = new double[SLOT_COUNT * n];
            delta = new double[SLOT_COUNT * n];
            previousDelta = new double[SLOT_COUNT * n];
            int dimension = (SLOT_COUNT - 1) * n;
            newtonGradient = new double[dimension];
            newtonHessian = new double[dimension * dimension];
            newtonFactor = new double[dimension * dimension];
            newtonStep = new double[dimension];
            newtonScale = new double[dimension];
            for (int k = 0; k < SLOT_COUNT; k++) {
                derivatives[k] = owner.kernel.newDerivativesWithoutHeatCapacity();
                states[k] = owner.evaluator.newState();
            }
        }
    }
}
