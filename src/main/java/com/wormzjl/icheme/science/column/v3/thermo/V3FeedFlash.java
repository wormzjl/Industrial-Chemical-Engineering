package com.wormzjl.icheme.science.column.v3.thermo;

import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.PhaseTraits;
import com.wormzjl.icheme.science.thermo.TangentPlaneStability;
import com.wormzjl.icheme.science.thermo.phase.FluidTpEquilibrium;
import java.util.Locale;

/**
 * Bounded rigorous dry-hydrocarbon TP flash with normal all-liquid, two-phase, and all-vapor outcomes.
 *
 * <p>A feed is one phase exactly where it is stable against a second phase (batch 2026-11-16-supercritical-followups,
 * P3, form F3). The flash brackets two phases with Wilson's K and iterates by successive substitution on the PR78
 * K-values. Where it does not reach an answer it asks the equilibrium engine's tangent-plane stability test
 * ({@link TangentPlaneStability} on the column's own kernel):</p>
 * <ul>
 *   <li>at the Wilson endpoint (no two-phase bracket): a stable feed is the endpoint's phase, as before; an unstable one
 *       goes into the iteration from the test's stationary phase;</li>
 *   <li>where the Rachford-Rice root is lost or the iterations run out: a stable feed is one phase ({@link #onePhase}),
 *       its label by the network's slot rule; an unstable one restarts once from the stationary phase;</li>
 *   <li>an unstable feed that still has no split takes the equilibrium engine's own ({@link V3FeedEngine}), its
 *       enthalpies on the column's roots. A feed the engine does not split either stays a typed refusal.</li>
 * </ul>
 * <p>The column takes an unstable feed's split only where its lighter phase is the column's vapour (rule D,
 * {@link #lighterPhaseIsVapour}): a vapour by the slot rule, or a fluid above its own critical temperature. Two subcritical
 * liquids are outside the two-phase stage model: at the Wilson endpoint the endpoint's answer stands (one liquid, a known
 * limit: a cold crude whose heaviest cut separates as a second liquid), elsewhere a typed refusal ({@link #TWO_LIQUIDS}).</p>
 * <p>On the review's grids (P3_COLUMN_FEED_REVIEW.md: 10,602 states of six mixtures) no feed the engine splits into a
 * vapour and a liquid is answered as one phase and no answer of the frozen flash moves outside the 181 real splits it
 * called one phase at the Wilson endpoint. Where the column's iteration converges, its split and the engine's agree to
 * 1e-10 in {@code ln K}.</p>
 */
final class V3FeedFlash {
    private static final int MAXIMUM_ITERATIONS = 64;
    private static final int RACHFORD_RICE_ITERATIONS = 100;
    private static final double LOG_K_TOLERANCE = 1.0e-10;
    private static final double PHASE_ENDPOINT_TOLERANCE = 1.0e-12;
    /** A stationary phase within this {@code sum (ln W_i - ln z_i)^2} of the feed seeds nothing: the engine's own bound. */
    private static final double TRIVIAL_SEED = FluidTpEquilibrium.Settings.DEFAULT.trivialDistance();
    /** The start of every detail of a one-phase answer of a stable feed. */
    static final String ONE_PHASE = "one phase, no two-phase split";
    /** The detail of the equilibrium engine's split. */
    static final String ENGINE_SPLIT = "two-phase split of the equilibrium engine";
    /** The end of a refusal of an unstable feed whose split is two subcritical liquids. */
    static final String TWO_LIQUIDS = "the feed splits into two liquids, which the two-phase column does not carry";

    private V3FeedFlash() {}

    static V3FlashResult resolve(
            V3PengRobinsonThermo model, double temperatureKelvin, double pressurePascal, V3ThermoWorkspace workspace) {
        int componentCount = workspace.componentCount();
        model.wilsonK(temperatureKelvin, pressurePascal, workspace.wilsonK, workspace);
        for (int component = 0; component < componentCount; component++) workspace.logK[component] = DeterministicMath.log(workspace.wilsonK[component]);

        double atLiquidEndpoint = rachfordRiceResidual(workspace.normalizedOverall, workspace.logK, 0.0);
        double atVaporEndpoint = rachfordRiceResidual(workspace.normalizedOverall, workspace.logK, 1.0);
        if (!Double.isFinite(atLiquidEndpoint) || !Double.isFinite(atVaporEndpoint)) {
            throw new V3ThermoException(V3ThermoException.Code.FLASH_NONCONVERGENCE, null,
                    "V3 feed flash could not establish a finite Rachford-Rice phase bracket");
        }
        boolean liquidEndpoint = atLiquidEndpoint <= PHASE_ENDPOINT_TOLERANCE;
        boolean endpoint = liquidEndpoint || atVaporEndpoint >= -PHASE_ENDPOINT_TOLERANCE;
        double[][] endpointScratch = null;
        if (endpoint) {
            // Wilson's K put the feed outside its two-phase bracket: one phase unless the stability test proves a second.
            TangentPlaneStability.Result stability = model.feedStability(temperatureKelvin, pressurePascal, workspace);
            if (stability.verdict() != TangentPlaneStability.Verdict.UNSTABLE) {
                return endpointAnswer(model, temperatureKelvin, pressurePascal, workspace, liquidEndpoint);
            }
            // Kept so that a split of two liquids leaves the workspace as the endpoint answer always did.
            endpointScratch = new double[][] {workspace.logK.clone(), workspace.nextLogK.clone(),
                    workspace.liquidComposition.clone(), workspace.vaporComposition.clone()};
            seedFromStationaryPhase(model, temperatureKelvin, pressurePascal, stability, workspace);
        }

        // These values are fixed during each root solve. Allocate scratch once per two-phase flash;
        // the other workspace arrays remain available to the reference/truncation protocol.
        double[] k = new double[componentCount];
        double[] kMinusOne = new double[componentCount];
        Failure failure = new Failure();
        V3FlashResult split = iterate(model, temperatureKelvin, pressurePascal, workspace, k, kMinusOne, failure);
        if (!endpoint) {
            // The iteration from Wilson's K: its answer stands, as it always did.
            if (split != null) return split;
            TangentPlaneStability.Result stability = model.feedStability(temperatureKelvin, pressurePascal, workspace);
            if (stability.verdict() == TangentPlaneStability.Verdict.STABLE) {
                return onePhase(model, temperatureKelvin, pressurePascal, workspace, failure.iteration);
            }
            if (stability.verdict() == TangentPlaneStability.Verdict.UNRESOLVED) {
                throw new V3ThermoException(V3ThermoException.Code.FLASH_NONCONVERGENCE, null,
                        failure.message + "; its stability test is unresolved");
            }
            // Unstable: once more from the stability test's stationary phase.
            split = seedFromStationaryPhase(model, temperatureKelvin, pressurePascal, stability, workspace)
                    ? iterate(model, temperatureKelvin, pressurePascal, workspace, k, kMinusOne, failure) : null;
        }
        // An unstable feed: only a split counts, the column's own or, where substitution does not reach one, the
        // equilibrium engine's.
        if (split != null && split.phase() != V3FeedPhase.TWO_PHASE) split = null;
        if (split == null) {
            double vaporFraction = model.engineSplit(temperatureKelvin, pressurePascal, workspace);
            if (vaporFraction > 0.0 && vaporFraction < 1.0) {
                split = engineSplit(model, temperatureKelvin, pressurePascal, workspace, vaporFraction, failure.iteration);
            }
        }
        if (split != null && lighterPhaseIsVapour(model, temperatureKelvin, pressurePascal, split, workspace)) return split;
        if (split != null && endpoint) {
            // Two liquids: outside the two-phase stage model. The endpoint's answer stands, one liquid as before.
            System.arraycopy(endpointScratch[0], 0, workspace.logK, 0, componentCount);
            System.arraycopy(endpointScratch[1], 0, workspace.nextLogK, 0, componentCount);
            System.arraycopy(endpointScratch[2], 0, workspace.liquidComposition, 0, componentCount);
            System.arraycopy(endpointScratch[3], 0, workspace.vaporComposition, 0, componentCount);
            return endpointAnswer(model, temperatureKelvin, pressurePascal, workspace, liquidEndpoint);
        }
        throw new V3ThermoException(V3ThermoException.Code.FLASH_NONCONVERGENCE, null, failure.message
                + (split != null ? "; " + TWO_LIQUIDS : "; the feed is unstable and the equilibrium engine gives no split"));
    }

    /** The Wilson endpoint's one phase: the feed on the endpoint's root. */
    private static V3FlashResult endpointAnswer(V3PengRobinsonThermo model, double temperatureKelvin, double pressurePascal,
                                                V3ThermoWorkspace workspace, boolean liquidEndpoint) {
        Root root = liquidEndpoint ? Root.LIQUID : Root.VAPOR;
        model.evaluateInto(temperatureKelvin, pressurePascal, workspace.normalizedOverall, root, workspace);
        double enthalpy = model.phaseMolarEnthalpy(temperatureKelvin, workspace.normalizedOverall, root, workspace);
        return liquidEndpoint
                ? V3FlashResult.liquid(0, workspace.normalizedOverall, enthalpy, "all-liquid Wilson endpoint classification")
                : V3FlashResult.vapor(0, workspace.normalizedOverall, enthalpy, "all-vapor Wilson endpoint classification");
    }

    /**
     * Whether a split's lighter phase is the column's vapour, the phase that goes up from the feed tray: a vapour by the
     * network's slot rule (reduced density below 1), or a fluid on a supercritical isotherm of its own composition, which
     * has no liquid identity ({@link PhaseTraits#supercriticalIsotherm()}). Otherwise both phases are subcritical liquids:
     * two liquids, which the two-phase stage model does not carry.
     */
    private static boolean lighterPhaseIsVapour(V3PengRobinsonThermo model, double temperatureKelvin, double pressurePascal,
                                                V3FlashResult split, V3ThermoWorkspace workspace) {
        model.evaluateInto(temperatureKelvin, pressurePascal, split.vaporComposition(), Root.VAPOR, workspace);
        PhaseTraits traits = model.phaseTraits(Root.VAPOR, temperatureKelvin, pressurePascal, workspace);
        return traits.reducedDensity() < 1.0 || traits.supercriticalIsotherm();
    }

    /** Where successive substitution left off without an answer. */
    private static final class Failure {
        int iteration;
        String message;
    }

    /**
     * Successive substitution from the workspace's {@code ln K}: the converged split or endpoint, or null with
     * {@code failure} naming where it left off (the Rachford-Rice root lost, or the iterations run out).
     */
    private static V3FlashResult iterate(V3PengRobinsonThermo model, double temperatureKelvin, double pressurePascal,
                                         V3ThermoWorkspace workspace, double[] k, double[] kMinusOne, Failure failure) {
        int componentCount = workspace.componentCount();
        for (int iteration = 1; iteration <= MAXIMUM_ITERATIONS; iteration++) {
            double vaporFraction = rachfordRiceRoot(workspace.normalizedOverall, workspace.logK, k, kMinusOne);
            if (!Double.isFinite(vaporFraction) || vaporFraction <= 0.0 || vaporFraction >= 1.0) {
                failure.iteration = iteration;
                failure.message = "V3 feed flash lost its two-phase Rachford-Rice root";
                return null;
            }
            populatePhaseCompositions(workspace.normalizedOverall, workspace.logK, vaporFraction,
                    workspace.liquidComposition, workspace.vaporComposition);
            model.evaluateInto(temperatureKelvin, pressurePascal, workspace.liquidComposition, Root.LIQUID, workspace);
            model.evaluateInto(temperatureKelvin, pressurePascal, workspace.vaporComposition, Root.VAPOR, workspace);
            double maximumLogKChange = 0.0;
            for (int component = 0; component < componentCount; component++) {
                double target = model.logFugacityCoefficient(Root.LIQUID, component, workspace)
                        - model.logFugacityCoefficient(Root.VAPOR, component, workspace);
                maximumLogKChange = Math.max(maximumLogKChange, Math.abs(target - workspace.logK[component]));
                workspace.nextLogK[component] = 0.5 * (workspace.logK[component] + target);
            }
            if (maximumLogKChange <= LOG_K_TOLERANCE) {
                // PR updates can move an initial Wilson two-phase bracket onto the liquid endpoint.
                // Bisection otherwise leaves a tiny positive vapor fraction and mislabels the single phase.
                if (rachfordRiceResidual(workspace.normalizedOverall, workspace.logK, 0.0) <= PHASE_ENDPOINT_TOLERANCE) {
                    model.evaluateInto(temperatureKelvin, pressurePascal, workspace.normalizedOverall, Root.LIQUID, workspace);
                    return V3FlashResult.liquid(iteration, workspace.normalizedOverall,
                            model.phaseMolarEnthalpy(temperatureKelvin, workspace.normalizedOverall, Root.LIQUID, workspace),
                            "all-liquid converged PR endpoint classification");
                }
                double liquidEnthalpy = model.phaseMolarEnthalpy(temperatureKelvin, workspace.liquidComposition,
                        Root.LIQUID, workspace);
                double vaporEnthalpy = model.phaseMolarEnthalpy(temperatureKelvin, workspace.vaporComposition,
                        Root.VAPOR, workspace);
                return V3FlashResult.twoPhase(iteration, vaporFraction, workspace.liquidComposition,
                        workspace.vaporComposition, (1.0 - vaporFraction) * liquidEnthalpy + vaporFraction * vaporEnthalpy,
                        "rigorous two-phase Rachford-Rice flash");
            }
            System.arraycopy(workspace.nextLogK, 0, workspace.logK, 0, componentCount);
        }
        failure.iteration = MAXIMUM_ITERATIONS;
        failure.message = "V3 feed flash did not converge within " + MAXIMUM_ITERATIONS + " iterations";
        return null;
    }

    /**
     * Seeds {@code ln K} from the stability test's stationary phase {@code W}, the lighter of it and the feed the vapour:
     * {@code ln K_i = +-(ln W_i - ln z_i)}, as the equilibrium engine seeds its split ({@code TwoPhaseFlash}). A stationary
     * point within {@link #TRIVIAL_SEED} of the feed (instability proven by the feed's own curvature) seeds nothing:
     * Wilson's K stay.
     *
     * @return whether the seed was taken
     */
    private static boolean seedFromStationaryPhase(V3PengRobinsonThermo model, double temperatureKelvin, double pressurePascal,
                                                   TangentPlaneStability.Result stability, V3ThermoWorkspace workspace) {
        double[] trial = stability.trialComposition();
        double[] overall = workspace.normalizedOverall;
        model.evaluateInto(temperatureKelvin, pressurePascal, trial, stability.trialRoot(), workspace);
        double trialCompressibility = model.compressibilityFactor(stability.trialRoot(), workspace);
        model.evaluateInto(temperatureKelvin, pressurePascal, overall, stability.feedRoot(), workspace);
        boolean trialIsVapour = trialCompressibility > model.compressibilityFactor(stability.feedRoot(), workspace);
        double distance = 0.0;
        for (int component = 0; component < overall.length; component++) {
            if (overall[component] == 0.0) continue;
            double ratio = DeterministicMath.log(trial[component]) - DeterministicMath.log(overall[component]);
            distance += ratio * ratio;
        }
        if (!(distance >= TRIVIAL_SEED)) return false;
        for (int component = 0; component < overall.length; component++) {
            if (overall[component] == 0.0) continue;
            double ratio = DeterministicMath.log(trial[component]) - DeterministicMath.log(overall[component]);
            workspace.logK[component] = trialIsVapour ? ratio : -ratio;
        }
        return true;
    }

    /**
     * The equilibrium engine's split, already in the workspace's liquid and vapour compositions, with the enthalpies on the
     * column's own roots (the liquid on the liquid root, the vapour on the vapour root, as the column's own split).
     */
    private static V3FlashResult engineSplit(V3PengRobinsonThermo model, double temperatureKelvin, double pressurePascal,
                                             V3ThermoWorkspace workspace, double vaporFraction, int iterations) {
        double[] liquid = workspace.liquidComposition;
        double[] vapor = workspace.vaporComposition;
        model.evaluateInto(temperatureKelvin, pressurePascal, liquid, Root.LIQUID, workspace);
        model.evaluateInto(temperatureKelvin, pressurePascal, vapor, Root.VAPOR, workspace);
        for (int component = 0; component < liquid.length; component++) {
            workspace.logK[component] = liquid[component] > 0.0 && vapor[component] > 0.0
                    ? DeterministicMath.log(vapor[component]) - DeterministicMath.log(liquid[component]) : 0.0;
            workspace.nextLogK[component] = workspace.logK[component];
        }
        double enthalpy = (1.0 - vaporFraction) * model.phaseMolarEnthalpy(temperatureKelvin, liquid, Root.LIQUID, workspace)
                + vaporFraction * model.phaseMolarEnthalpy(temperatureKelvin, vapor, Root.VAPOR, workspace);
        return V3FlashResult.twoPhase(iterations, vaporFraction, liquid, vapor, enthalpy, ENGINE_SPLIT);
    }

    /**
     * The feed as one phase: the overall composition on its own root. With one physical root its label is the
     * network's slot rule (owner decision O2 A): liquid where the reduced density {@code v_c / v} is at least 1
     * ({@link PhaseTraits#reducedDensity()}), vapour below. With both roots, the root of lower Gibbs energy,
     * {@code sum z_i ln phi_i} at the same temperature, pressure and composition.
     */
    private static V3FlashResult onePhase(
            V3PengRobinsonThermo model, double temperatureKelvin, double pressurePascal, V3ThermoWorkspace workspace,
            int iterations) {
        double[] overall = workspace.normalizedOverall;
        model.evaluateInto(temperatureKelvin, pressurePascal, overall, Root.LIQUID, workspace);
        model.evaluateInto(temperatureKelvin, pressurePascal, overall, Root.VAPOR, workspace);
        PhaseTraits traits = model.phaseTraits(Root.LIQUID, temperatureKelvin, pressurePascal, workspace);
        Root root;
        String detail;
        if (traits.physicalRootCount() > 1) {
            double liquidGibbs = 0.0;
            double vaporGibbs = 0.0;
            for (int component = 0; component < overall.length; component++) {
                if (overall[component] == 0.0) continue;
                liquidGibbs += overall[component] * model.logFugacityCoefficient(Root.LIQUID, component, workspace);
                vaporGibbs += overall[component] * model.logFugacityCoefficient(Root.VAPOR, component, workspace);
            }
            root = liquidGibbs < vaporGibbs ? Root.LIQUID : Root.VAPOR;
            detail = ONE_PHASE + ": " + (root == Root.LIQUID ? "liquid" : "vapour") + " root of lower Gibbs energy";
        } else {
            // The isotherm is supercritical for the mixture's own a and b: above its pseudo-critical temperature, which
            // is not the mixture's critical point (a crude splits above it at 0.5 MPa and 900 K).
            double reducedDensity = traits.reducedDensity();
            root = reducedDensity >= 1.0 ? Root.LIQUID : Root.VAPOR;
            detail = ONE_PHASE + (traits.supercriticalIsotherm() ? " above" : " below") + " the mixture's pseudo-critical temperature: "
                    + (root == Root.LIQUID ? "liquid" : "vapour") + String.format(Locale.ROOT, " by reduced density %.3f", reducedDensity);
        }
        double enthalpy = model.phaseMolarEnthalpy(temperatureKelvin, overall, root, workspace);
        return root == Root.LIQUID ? V3FlashResult.liquid(iterations, overall, enthalpy, detail)
                : V3FlashResult.vapor(iterations, overall, enthalpy, detail);
    }

    private static double rachfordRiceRoot(double[] composition, double[] logK, double[] k, double[] kMinusOne) {
        for (int component = 0; component < composition.length; component++) {
            if (composition[component] == 0.0) continue;
            k[component] = DeterministicMath.exp(logK[component]);
            kMinusOne[component] = DeterministicMath.expm1(logK[component]);
        }
        double lower = 0.0;
        double upper = 1.0;
        for (int iteration = 0; iteration < RACHFORD_RICE_ITERATIONS; iteration++) {
            double midpoint = 0.5 * (lower + upper);
            double residual = cachedRachfordRiceResidual(composition, k, kMinusOne, midpoint);
            if (!Double.isFinite(residual)) return Double.NaN;
            // Only stop when the next update cannot change the bracket in binary64. Check the
            // residual first so an invalid endpoint cannot be accepted by the stagnation shortcut.
            if (midpoint == lower || midpoint == upper) return midpoint;
            if (residual > 0.0) lower = midpoint;
            else upper = midpoint;
        }
        return 0.5 * (lower + upper);
    }

    private static double cachedRachfordRiceResidual(
            double[] composition, double[] k, double[] kMinusOne, double vaporFraction) {
        double residual = 0.0;
        for (int component = 0; component < composition.length; component++) {
            if (composition[component] == 0.0) continue;
            double denominator = (1.0 - vaporFraction) + vaporFraction * k[component];
            if (!(denominator > 0.0) || !Double.isFinite(denominator)) return Double.NaN;
            residual += composition[component] * kMinusOne[component] / denominator;
        }
        return residual;
    }

    private static double rachfordRiceResidual(double[] composition, double[] logK, double vaporFraction) {
        double residual = 0.0;
        for (int component = 0; component < composition.length; component++) {
            if (composition[component] == 0.0) continue;
            double k = DeterministicMath.exp(logK[component]);
            double kMinusOne = DeterministicMath.expm1(logK[component]);
            // Keep a tiny positive K at beta=1 instead of cancelling it in 1 + (K - 1).
            double denominator = (1.0 - vaporFraction) + vaporFraction * k;
            if (!(denominator > 0.0) || !Double.isFinite(denominator)) return Double.NaN;
            residual += composition[component] * kMinusOne / denominator;
        }
        return residual;
    }

    private static void populatePhaseCompositions(
            double[] overall, double[] logK, double vaporFraction, double[] liquid, double[] vapor) {
        double liquidTotal = 0.0;
        double vaporTotal = 0.0;
        for (int component = 0; component < overall.length; component++) {
            if (overall[component] == 0.0) {
                liquid[component] = 0.0;
                vapor[component] = 0.0;
                continue;
            }
            double k = DeterministicMath.exp(logK[component]);
            liquid[component] = overall[component] / ((1.0 - vaporFraction) + vaporFraction * k);
            vapor[component] = k * liquid[component];
            liquidTotal += liquid[component];
            vaporTotal += vapor[component];
        }
        if (!Double.isFinite(liquidTotal) || !Double.isFinite(vaporTotal) || liquidTotal <= 0.0 || vaporTotal <= 0.0) {
            throw new V3ThermoException(V3ThermoException.Code.FLASH_NONCONVERGENCE, null,
                    "V3 feed flash generated a nonphysical phase composition");
        }
        for (int component = 0; component < overall.length; component++) {
            liquid[component] /= liquidTotal;
            vapor[component] /= vaporTotal;
        }
    }
}
