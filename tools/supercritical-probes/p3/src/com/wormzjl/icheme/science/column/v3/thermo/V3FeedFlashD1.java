package com.wormzjl.icheme.science.column.v3.thermo;

import com.wormzjl.icheme.science.math.DeterministicMath;
import com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root;
import com.wormzjl.icheme.science.thermo.PhaseTraits;
import java.util.Locale;

/** The 0.34.0 flash (main adb413b4d, D1 collapse rule), copied for the P3 wet probe; do not update.
 *
 * Bounded rigorous dry-hydrocarbon TP flash with normal all-liquid, two-phase, and all-vapor outcomes.
 *
 * <p>A feed with no two-phase solution at its state is one fluid (batch 2026-11-15-supercritical-fluids, D1). Where
 * successive substitution collapses the K-values toward the trivial solution, the feed is answered as one phase
 * ({@link #onePhase}). A collapse is the largest {@code |ln K|} of the feed's components falling at every iteration. The
 * flash answers it where the Rachford-Rice root leaves (0, 1) during the collapse, or where the iterations run out with
 * {@code |ln K|} at most {@link #TRIVIAL_LOG_K}. This reads the flash's own iterates; it is not a stability test. On the
 * review's grid (COLUMN_FEED_REVIEW.md: 10,602 states of six mixtures) no feed that the equilibrium engine splits meets
 * it: every refused real split runs out of iterations with {@code |ln K|} at 0.1 or above.</p>
 */
final class V3FeedFlashD1 {
    private static final int MAXIMUM_ITERATIONS = 64;
    private static final int RACHFORD_RICE_ITERATIONS = 100;
    private static final double LOG_K_TOLERANCE = 1.0e-10;
    private static final double PHASE_ENDPOINT_TOLERANCE = 1.0e-12;
    /**
     * The largest {@code |ln K|} of a collapse that ran out of iterations and is still answered as one phase: the square
     * root of {@link #LOG_K_TOLERANCE}. It is four decades below the smallest {@code |ln K|} of a stalled real split on the
     * review's grid (0.1). Phases whose K-values are within it differ by at most 1e-5 in {@code ln(y/x)}: one fluid at the
     * column's resolution.
     */
    private static final double TRIVIAL_LOG_K = 1.0e-5;
    /** The start of every detail of a one-phase answer by the collapse rule. */
    static final String ONE_PHASE = "one phase, no two-phase split";

    private V3FeedFlashD1() {}

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
        if (atLiquidEndpoint <= PHASE_ENDPOINT_TOLERANCE) {
            model.evaluateInto(temperatureKelvin, pressurePascal, workspace.normalizedOverall, Root.LIQUID, workspace);
            return V3FlashResult.liquid(0, workspace.normalizedOverall,
                    model.phaseMolarEnthalpy(temperatureKelvin, workspace.normalizedOverall, Root.LIQUID, workspace),
                    "all-liquid Wilson endpoint classification");
        }
        if (atVaporEndpoint >= -PHASE_ENDPOINT_TOLERANCE) {
            model.evaluateInto(temperatureKelvin, pressurePascal, workspace.normalizedOverall, Root.VAPOR, workspace);
            return V3FlashResult.vapor(0, workspace.normalizedOverall,
                    model.phaseMolarEnthalpy(temperatureKelvin, workspace.normalizedOverall, Root.VAPOR, workspace),
                    "all-vapor Wilson endpoint classification");
        }

        // These values are fixed during each root solve. Allocate scratch once per two-phase flash;
        // the other workspace arrays remain available to the reference/truncation protocol.
        double[] k = new double[componentCount];
        double[] kMinusOne = new double[componentCount];
        // The collapse is only read while the iteration runs: a flash answered before is unchanged to the bit.
        double previousSpread = Double.POSITIVE_INFINITY;
        boolean collapsing = true;
        for (int iteration = 1; iteration <= MAXIMUM_ITERATIONS; iteration++) {
            double spread = logKSpread(workspace.normalizedOverall, workspace.logK);
            if (!(spread < previousSpread)) collapsing = false;
            previousSpread = spread;
            double vaporFraction = rachfordRiceRoot(workspace.normalizedOverall, workspace.logK, k, kMinusOne);
            if (!Double.isFinite(vaporFraction) || vaporFraction <= 0.0 || vaporFraction >= 1.0) {
                if (Double.isFinite(vaporFraction) && collapsing) {
                    return onePhase(model, temperatureKelvin, pressurePascal, workspace, iteration);
                }
                throw new V3ThermoException(V3ThermoException.Code.FLASH_NONCONVERGENCE, null,
                        "V3 feed flash lost its two-phase Rachford-Rice root");
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
        if (collapsing && logKSpread(workspace.normalizedOverall, workspace.logK) <= TRIVIAL_LOG_K) {
            return onePhase(model, temperatureKelvin, pressurePascal, workspace, MAXIMUM_ITERATIONS);
        }
        throw new V3ThermoException(V3ThermoException.Code.FLASH_NONCONVERGENCE, null,
                "V3 feed flash did not converge within " + MAXIMUM_ITERATIONS + " iterations");
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

    /** The largest {@code |ln K|} over the feed's components: zero at the trivial solution. */
    private static double logKSpread(double[] composition, double[] logK) {
        double spread = 0.0;
        for (int component = 0; component < composition.length; component++) {
            if (composition[component] != 0.0) spread = Math.max(spread, Math.abs(logK[component]));
        }
        return spread;
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
