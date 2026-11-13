package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.column.v3.V3SimultaneousColumnSolver.NewtonTrace;

import com.wormzjl.icheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.icheme.science.column.v3.thermo.V3FeedPhase;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.column.v3.thermo.V3ThermoException;
import com.wormzjl.icheme.science.thermo.TraceTruncationPolicy;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;

import static com.wormzjl.icheme.science.column.v3.V3SolveReport.boundedEvent;
import static com.wormzjl.icheme.science.column.v3.V3SolveReport.boundedSolvePath;
import static com.wormzjl.icheme.science.column.v3.V3SolveReport.failedAudit;
import static com.wormzjl.icheme.science.column.v3.V3SolveReport.failureCode;
import static com.wormzjl.icheme.science.column.v3.V3SolveReport.flashTraceEvent;
import static com.wormzjl.icheme.science.column.v3.V3SolveReport.mergedEvents;
import static com.wormzjl.icheme.science.column.v3.V3SolveReport.sideDrawDiagnostic;
import static com.wormzjl.icheme.science.column.v3.V3SolveReport.stageTraceEvent;
import static com.wormzjl.icheme.science.column.v3.V3SolveReport.terminalFailure;
import static com.wormzjl.icheme.science.column.v3.V3SolveReport.waterDiagnostic;
import static com.wormzjl.icheme.science.column.v3.V3ContinuationLadder.hasCondenserPhaseMismatch;
import static com.wormzjl.icheme.science.column.v3.V3ContinuationLadder.initializeForSolve;
import static com.wormzjl.icheme.science.column.v3.V3ContinuationLadder.originalProblem;
import static com.wormzjl.icheme.science.column.v3.V3ContinuationLadder.recoverWithBubblePointProjection;
import static com.wormzjl.icheme.science.column.v3.V3ContinuationLadder.requiresPressureContinuation;
import static com.wormzjl.icheme.science.column.v3.V3ContinuationLadder.solvePressureContinuation;
import static com.wormzjl.icheme.science.column.v3.V3ContinuationLadder.solveStageContinuation;
import static com.wormzjl.icheme.science.column.v3.V3ContinuationLadder.withStageGeometry;
import static com.wormzjl.icheme.science.column.v3.V3ContinuationLadder.withoutPumparounds;
import static com.wormzjl.icheme.science.column.v3.V3ContinuationLadder.withoutSideDraws;
import static com.wormzjl.icheme.science.column.v3.V3ContinuationLadder.withoutSteamWithSurrogateDuty;
import static com.wormzjl.icheme.science.column.v3.V3FeatureRamp.recoverWithDrawRamp;

/**
 * Stateless dry-V3 calculation façade for a fully immutable input snapshot.
 *
 * <p>This class has no Minecraft, cache, warm-state, executor, or packet dependency. A caller must arrange any
 * cancellation/deadline policy outside this direct numerical boundary. Every candidate is freshly audited before a
 * {@link V3ColumnOutcome.Success} can be returned.</p>
 */
public final class V3ColumnCalculator {
    /** Alias of {@link V3ColumnLabels#FORMULATION_REVISION}, kept for its readers (tests). */
    public static final String FORMULATION_REVISION = V3ColumnLabels.FORMULATION_REVISION;
    /** Alias of {@link V3ColumnLabels#ASSUMPTIONS_REVISION}, kept for its readers (tests). */
    public static final String ASSUMPTIONS_REVISION = V3ColumnLabels.ASSUMPTIONS_REVISION;
    /** Alias of {@link V3ColumnLabels#WET_ASSUMPTIONS_REVISION}, kept for its readers (tests). */
    public static final String WET_ASSUMPTIONS_REVISION = V3ColumnLabels.WET_ASSUMPTIONS_REVISION;
    /** Alias of {@link V3ColumnLabels#HEAT_ASSUMPTIONS_REVISION}, kept for its readers (tests). */
    public static final String HEAT_ASSUMPTIONS_REVISION = V3ColumnLabels.HEAT_ASSUMPTIONS_REVISION;
    public static final int MAXIMUM_NEWTON_ITERATIONS = 128;
    public static final double SCALED_RESIDUAL_TOLERANCE = 1.0e-8;
    /** Fast interactive requests use the admitted gameplay closure; strict/offline requests retain 1e-8. */
    static final double AGGRESSIVE_CLOSURE_TOLERANCE = 1.0e-3;
    private static final int CONDENSER_PHASE_CORRECTOR_MAXIMUM_ITERATIONS = 24;
    /** Alias of {@link V3HydraulicPublish#HYDRAULIC_CORRECTION_BUDGET_POLLS}, kept for its readers (tests). */
    static final long HYDRAULIC_CORRECTION_BUDGET_POLLS = V3HydraulicPublish.HYDRAULIC_CORRECTION_BUDGET_POLLS;
    /** Alias of {@link PathDependentHeatBound#PATH_DEPENDENT_BOUND_SUFFIX}, kept for its readers (tests). */
    static final String PATH_DEPENDENT_BOUND_SUFFIX = PathDependentHeatBound.PATH_DEPENDENT_BOUND_SUFFIX;
    /** Bounded reinsertion/removal passes over the always-on relative flow floor within one attempt. */
    private static final int MAXIMUM_FLOOR_SUPPORT_REFRESHES = 3;
    /** Only one of those passes may follow a stalled attempt; a second stall in a row is not new information. */
    private static final int MAXIMUM_STALLED_FLOOR_SUPPORT_REFRESHES = 1;
    /** Free-water tray-set refreshes within one attempt; only a converged attempt spends one. */
    private static final int MAXIMUM_WET_TRAY_REFRESHES = 3;
    /**
     * Newton budget of the one refresh that follows a stalled attempt and only removes points.
     *
     * <p>Measured on the 40 MW return-tray case: all four such refreshes stalled again with a worse final
     * residual than the attempt they repeated (0.070 to 0.220, 0.006 to 0.108, 0.007 to 0.127, 0.008 to
     * 0.148) and each spent a full 40-iteration budget, 2.7 s of an 11.1 s solve. A drop-only refresh cannot
     * repair a stalled attempt: the stalled state is what decided the removal, so the repeat starts from the
     * same place with fewer degrees of freedom. What it does contribute is state motion the continuation
     * ramp then re-derives its next rung's support from, and that is worth a bounded polish but not a second
     * full budget. Cutting it off entirely was measured and costs two cases (the 40 MW return-tray case and
     * {@code V3PumparoundSteamCalculatorTest.tjl19SolvesSumpSteamWithThreePumparounds}); one eighth of the
     * Newton budget keeps both and still removes about a quarter of the 40 MW case's cost. A refresh that
     * reinserts a point is different — it carries information the stalled state did not have — and keeps the
     * full budget.</p>
     */
    private static final int STALLED_DROP_REFRESH_ITERATIONS = MAXIMUM_NEWTON_ITERATIONS / 8;
    /**
     * Calibrated ratio of the request-only liquid-supply screen every existing entry point uses.
     *
     * <p>See {@link V3LiquidSupplyScreen}. Zero disables the calibrated tier and leaves only the physically
     * necessary {@code rho >= 1} rejection; a caller that wants the raw solver on a draw-wall specification
     * passes zero as the screen ratio.</p>
     */
    public static final double DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO = V3LiquidSupplyScreen.DEFAULT_CALIBRATED_RATIO;

    enum ContinuationJacobianPolicy {
        NONE,
        STAGE_LOCAL_BLOCKS,
        PRESSURE_LOCAL_PREDICTOR
    }

    private V3ColumnCalculator() {}

    /** Offline export hook: called only after the requested state passes every publication gate. */
    public static V3ColumnOutcome calculateWithAcceptedProfile(V3ColumnInput input, V3SolveControl control,
            java.util.function.Consumer<V3NeuralSeed> observer) {
        return calculateWithAcceptedProfile(input, control, observer, DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO);
    }

    static V3ColumnOutcome calculateWithAcceptedProfile(V3ColumnInput input, V3SolveControl control,
            java.util.function.Consumer<V3NeuralSeed> observer, double liquidSupplyScreenRatio) {
        return calculate(input, control,
                new V3SolvePolicy(0.0, 0.0, V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE,
                        Objects.requireNonNull(observer, "observer")), liquidSupplyScreenRatio);
    }

    /**
     * Explicit initializer strategy at an authored liquid-supply screen ratio. The learned path never calls a
     * classical initializer before deciding whether backup is allowed.
     *
     * <p>Screening is a property of the request, so it happens once at this entry, before the neural
     * candidates and before any flash: the learned and classical routes publish the identical typed failure
     * for a screened specification. See {@link V3LiquidSupplyScreen}; {@code 0} disables the calibrated tier.</p>
     *
     * @throws IllegalArgumentException if the screen ratio is nonfinite or outside [0, 1]
     */
    public static V3ColumnOutcome calculate(V3ColumnInput input, V3SolveControl control,
            double cutoff, double closureFraction, V3InitializationOptions options, V3NeuralInitializer model,
            double liquidSupplyScreenRatio) {
        return calculate(input, control, cutoff, closureFraction, options, model, null, liquidSupplyScreenRatio,
                NewtonTrace.NONE);
    }

    /** Offline export after native seed correction; intermediate or parametric states are never exported. */
    public static V3ColumnOutcome calculateWithAcceptedProfile(V3ColumnInput input, V3SolveControl control,
            V3InitializationOptions options, V3NeuralInitializer model, java.util.function.Consumer<V3NeuralSeed> observer) {
        return calculate(input, control, 0, 0, options, model, Objects.requireNonNull(observer, "observer"),
                DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO, NewtonTrace.NONE);
    }

    /**
     * Offline diagnostic seam: the unchanged learned path with a caller-owned Newton observation trace.
     *
     * <p>The trace only observes. It cannot change a step, a support, a budget or an outcome, and every
     * production entry point above passes {@link NewtonTrace#NONE}, so the traced and untraced paths
     * run the same arithmetic in the same order. It exists because the correction trajectory of a seed
     * that runs out of iterations or budget is not recoverable from the published evidence. A non-null
     * {@code observer} also exports the accepted profile, so a traced request can be audited like any other.</p>
     */
    static V3ColumnOutcome calculateWithNeuralTrace(V3ColumnInput input, V3SolveControl control,
            V3InitializationOptions options, V3NeuralInitializer model, NewtonTrace trace,
            java.util.function.Consumer<V3NeuralSeed> observer) {
        // The seam observes the production learned entry, so it screens on the same terms as that entry.
        return calculate(input, control, 0, 0, options, model, observer, DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO,
                Objects.requireNonNull(trace, "trace"));
    }

    private static V3ColumnOutcome calculate(V3ColumnInput input, V3SolveControl control,
            double cutoff, double closureFraction, V3InitializationOptions options, V3NeuralInitializer model,
            java.util.function.Consumer<V3NeuralSeed> observer, double liquidSupplyScreenRatio,
            NewtonTrace trace) {
        return com.wormzjl.icheme.science.material.MaterialRuntime.pinned(input.packageId(),
                () -> V3SolveScratch.within(() -> V3LearnedRoute.calculateInCatalog(input, control, cutoff, closureFraction, options, model, observer, liquidSupplyScreenRatio, trace)));
    }

    /** An audited pass may publish only when it solved the authored request itself, every wet row included. */
    static boolean publishesRequestedGeometry(V3SolvePass pass, V3ColumnInput input) {
        return pass.prepared().problem().input().equals(input)
                && !pass.prepared().problem().wetTraySet().isParametric();
    }

    /**
     * Calculates one dry V3 problem without sharing mutable numerical state across callers.
     *
     * <p>{@code control} is caller-owned cooperative cancellation: a cancellation exception from it intentionally
     * escapes unchanged so an outer service can publish its own typed deadline or cancellation completion.</p>
     *
     * <p>{@code stageTraceCutoffMoleFraction} freezes per-attempt stage support with an audited molar defect. It
     * is a mole fraction in [0, 0.01], not a feed filter. Zero uses the exact untruncated path. A failed truncated
     * chain retries once untruncated, except for admission failures and caller-owned cancellation.</p>
     *
     * <p>{@code convergenceClosureFraction} is the relative closure every residual row must reach: a component
     * balance closes to that fraction of its own local throughput, an equilibrium row to that difference in log
     * composition, a tray energy row to that fraction of {@code F_total * 1e5 W}. Zero selects the frozen
     * default {@link V3ConvergenceEvidence#MAXIMUM_LOG_FLOW_CHANGE} and reproduces the default path bit for
     * bit, including its digest and formulation label. A positive value loosens the Newton stop, the final-step
     * gate, the equilibrium and condenser-split audit limits and the two energy-closure audit limits together,
     * and appends a closure suffix to the formulation label so an accepted result at one closure can never be
     * mistaken for one at another.</p>
     *
     * <p>{@code liquidSupplyScreenRatio} is the calibrated tier of {@link V3LiquidSupplyScreen}: a request
     * whose authored side draws withdraw at least that fraction of the liquid reflux, feed and authored
     * pumparound condensation can deliver to their trays is typed {@code INFEASIBLE_SPECIFICATION} before any
     * flash, so it fails in microseconds instead of seconds. {@code 0} disables the calibrated tier, leaving
     * only the physically necessary {@code rho >= 1} rejection, and is how a research probe reaches the raw
     * solver on the draw-wall specifications this project intends to learn to solve.</p>
     *
     * <p>The screen reads nothing but the request, evaluates no thermodynamics and changes no digest,
     * closure, support rule, ramp schedule or acceptance limit.</p>
     *
     * @throws IllegalArgumentException if the cutoff or closure is out of range, or if the screen ratio is
     *         nonfinite or outside [0, 1]
     */
    public static V3ColumnOutcome calculate(
            V3ColumnInput input, V3SolveControl control, double stageTraceCutoffMoleFraction,
            double convergenceClosureFraction, double liquidSupplyScreenRatio) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(control, "control");
        V3TruncationSupport.requireCutoff(stageTraceCutoffMoleFraction);
        V3LiquidSupplyScreen.requireRatio(liquidSupplyScreenRatio);
        double closure = closureTolerance(convergenceClosureFraction);
        if (stageTraceCutoffMoleFraction == 0.0) {
            return closure == V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE
                    ? calculate(input, control, V3SolvePolicy.OFF, liquidSupplyScreenRatio)
                    : calculate(input, control, new V3SolvePolicy(0.0, 0.0, closure), liquidSupplyScreenRatio);
        }
        return V3TruncationFallback.calculate(stageTraceCutoffMoleFraction, attemptCutoff -> calculate(
                input, control, new V3SolvePolicy(stageTraceCutoffMoleFraction, attemptCutoff, closure),
                liquidSupplyScreenRatio));
    }

    /** Re-solves a request using one of the repeat-request warm-start policies. */
    public static V3ColumnOutcome calculateWithStrategy(
            V3ColumnInput input, V3SolveControl control, V3SolveStrategy strategy,
            V3NeuralSeed warmStart, double liquidSupplyScreenRatio) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(strategy, "strategy");
        V3LiquidSupplyScreen.requireRatio(liquidSupplyScreenRatio);
        // Warm corrections already have a nearby certified state; keep their strict hydraulic polish. The coarse
        // ramp and no-second-pressure-solve policy are reserved for the expensive first interactive solve.
        V3SolvePolicy policy = strategy == V3SolveStrategy.AGGRESSIVE && warmStart == null
                ? V3SolvePolicy.OFF_FAST : V3SolvePolicy.OFF;
        return com.wormzjl.icheme.science.material.MaterialRuntime.pinned(input.packageId(),
                () -> V3SolveScratch.within(() -> calculateInCatalog(input, control, policy, liquidSupplyScreenRatio,
                        strategy, warmStart)));
    }

    /**
     * Converts an authored closure request into the tolerance every gate uses.
     *
     * @throws IllegalArgumentException if the request is nonfinite or outside
     *         [0, {@link V3ConvergenceEvidence#MAXIMUM_CLOSURE_TOLERANCE}]
     */
    public static double closureTolerance(double convergenceClosureFraction) {
        if (!Double.isFinite(convergenceClosureFraction) || convergenceClosureFraction < 0.0
                || convergenceClosureFraction > V3ConvergenceEvidence.MAXIMUM_CLOSURE_TOLERANCE) {
            throw new IllegalArgumentException("V3 convergence closure fraction must be finite and in [0, 1e-3]");
        }
        return Math.max(V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE, convergenceClosureFraction);
    }

    private static V3ColumnOutcome calculate(
            V3ColumnInput input, V3SolveControl control, V3SolvePolicy policy, double liquidSupplyScreenRatio) {
        return com.wormzjl.icheme.science.material.MaterialRuntime.pinned(input.packageId(),
                () -> V3SolveScratch.within(() -> calculateInCatalog(input, control, policy, liquidSupplyScreenRatio,
                        V3SolveStrategy.DRIFT_SPEC, null)));
    }

    private static V3ColumnOutcome calculateInCatalog(
            V3ColumnInput input, V3SolveControl control, V3SolvePolicy policy, double liquidSupplyScreenRatio,
            V3SolveStrategy strategy, V3NeuralSeed warmStart) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(control, "control");
        V3Admission.RequestAdmission admission = V3Admission.requestOnlyAdmission(input, liquidSupplyScreenRatio);
        if (admission.typed()) return admission.failure();
        CondenserAttempts condenserAttempts = new CondenserAttempts();
        V3CondenserPhaseBranch preferred = warmStart != null
                && (input.equals(warmStart.input()) || warmCompatible(input, warmStart.input(), strategy))
                ? warmStart.branch() : preferredCondenserBranch(input, control);
        V3ColumnOutcome outcome = calculateBranch(input, control, preferred, policy, condenserAttempts, strategy, warmStart);
        if (outcome instanceof V3ColumnOutcome.Success
                || outcome instanceof V3ColumnOutcome.Failure failure
                && (failure.code() == V3SolverFailureCode.INVALID_INPUT
                || failure.code() == V3SolverFailureCode.PROPERTY_OUT_OF_RANGE
                || failure.code() == V3SolverFailureCode.INFEASIBLE_SPECIFICATION)) return outcome;
        V3CondenserPhaseBranch alternate = preferred == V3CondenserPhaseBranch.LIQUID_ONLY
                ? V3CondenserPhaseBranch.TWO_PHASE : V3CondenserPhaseBranch.LIQUID_ONLY;
        // A known phase mismatch has its bounded same-rung warm correction. Restarting either branch
        // from the smallest grid discards that useful profile and can revisit an inappropriate phase.
        if (!condenserAttempts.allowsColdRecovery() || condenserAttempts.hasAttempted(alternate)) return outcome;
        V3ColumnOutcome alternative = calculateBranch(input, control, alternate, policy, condenserAttempts, strategy, warmStart);
        return alternative instanceof V3ColumnOutcome.Success ? alternative : outcome;
    }

    /** Alias of {@link V3Admission#requestOnlyAdmission} for the offline population screens in {@code tools/}. */
    static V3Admission.RequestAdmission requestOnlyAdmission(V3ColumnInput input, double liquidSupplyScreenRatio) {
        return V3Admission.requestOnlyAdmission(input, liquidSupplyScreenRatio);
    }

    /** A seed flash only orders the branch attempts; the solved liquid outlet is independently audited. */
    private static V3CondenserPhaseBranch preferredCondenserBranch(V3ColumnInput input, V3SolveControl control) {
        control.checkpoint();
        try {
            V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
            V3ColumnInput noDrawInput = withoutPumparounds(withoutSteamWithSurrogateDuty(withoutSideDraws(input)));
            V3ColumnInput probeInput = noDrawInput.stageCount() <= 4 ? noDrawInput : withStageGeometry(noDrawInput, 4);
            V3ColumnProblem probe = V3ColumnProblemResolver.resolve(probeInput, V3CondenserPhaseBranch.TWO_PHASE);
            if (V3OperatingDomainValidator.assess(probe, thermo) instanceof V3OperatingDomainValidator.Assessment.Rejected) {
                return V3CondenserPhaseBranch.TWO_PHASE;
            }
            V3DryMeshState seed = V3ColumnInitializer.initialize(probe, thermo, thermo.newWorkspace(),
                    V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE).state();
            double[] overhead = new double[input.componentBasis().componentCount()];
            for (int component = 0; component < seed.componentCount(); component++) {
                overhead[probe.activeComponentBasis().publicIndex(component)] = seed.vaporFlow(1, component);
            }
            control.checkpoint();
            V3FlashResult flash = thermo.flashTP(seed.temperatureKelvin(0), probe.nodePressurePascal(0),
                    overhead, thermo.newWorkspace());
            return flash.phase() == V3FeedPhase.LIQUID
                    ? V3CondenserPhaseBranch.LIQUID_ONLY : V3CondenserPhaseBranch.TWO_PHASE;
        } catch (V3ThermoException | IllegalArgumentException unavailableSeedFlash) {
            return V3CondenserPhaseBranch.TWO_PHASE;
        }
    }

    private static V3ColumnOutcome calculateBranch(V3ColumnInput input, V3SolveControl control,
            V3CondenserPhaseBranch condenserBranch, V3SolvePolicy policy, CondenserAttempts condenserAttempts,
            V3SolveStrategy strategy, V3NeuralSeed warmStart) {
        List<String> advisoryEvidence = List.of();
        boolean admitted = false;
        try {
            condenserAttempts.recordAttempt(condenserBranch);
            control.checkpoint();
            V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
            advisoryEvidence = thermo.advisoryEvidence();
            V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input, condenserBranch);
            V3OperatingDomainValidator.Assessment admission = V3OperatingDomainValidator.assess(problem, thermo);
            if (admission instanceof V3OperatingDomainValidator.Assessment.Rejected rejected) {
                return terminalFailure(V3SolverFailureCode.PROPERTY_OUT_OF_RANGE, rejected.detail(), "admission", advisoryEvidence);
            }
            admitted = true;
            V3SolvePass pass;
            V3SolvePass requestedColdFailure = null;
            pass = warmStartPass(input, problem, thermo, control, policy, strategy, warmStart);
            // Preserve the authored phase layout before trying an auxiliary column. A pot has no
            // smaller tray grid; a zero-reflux tower can lose its dry upper section in that auxiliary.
            if (pass == null && (problem.topology().dryTopTrayCount() > 0 || input.stageCount() < 2)) {
                pass = runRung(problem, thermo,
                        initializeForSolve(problem, thermo, V3ColumnInitializer.Mode.MATERIAL_CLOSED), control,
                        "cold/phase-available/fine-fd",
                        RungOptions.of(ContinuationJacobianPolicy.NONE, MAXIMUM_NEWTON_ITERATIONS), policy,
                        condenserAttempts);
                if (!publishesSuccess(pass.attempt(), pass.audit())
                        && (!input.sideDraws().isEmpty() || !input.pumparounds().isEmpty())) {
                    V3ColumnInput baseInput = withoutPumparounds(withoutSideDraws(input));
                    V3ColumnProblem baseProblem = V3ColumnProblemResolver.resolve(baseInput, condenserBranch);
                    V3SolvePass base = runRung(baseProblem, thermo,
                            initializeForSolve(baseProblem, thermo, V3ColumnInitializer.Mode.MATERIAL_CLOSED), control,
                            "cold/phase-available-base/fine-fd",
                            RungOptions.of(ContinuationJacobianPolicy.NONE, MAXIMUM_NEWTON_ITERATIONS), policy,
                            condenserAttempts);
                    if (publishesSuccess(base.attempt(), base.audit())) {
                        V3ColumnProblem target = V3ColumnProblemResolver.resolve(input, base.prepared().problem().topology().condenserPhaseBranch());
                        pass = recoverWithDrawRamp(target, thermo, base, control, policy, condenserAttempts, true);
                    }
                }
                if (!publishesSuccess(pass.attempt(), pass.audit())) {
                    requestedColdFailure = pass;
                    pass = null;
                }
            }
            if (pass == null) {
                pass = requiresPressureContinuation(admission)
                        ? solvePressureContinuation(input, thermo, control, condenserBranch, policy, condenserAttempts,
                                strategy)
                        : solveStageContinuation(input, thermo, control, condenserBranch, policy, condenserAttempts,
                                strategy);
            }
            if (requestedColdFailure != null && !publishesSuccess(pass.attempt(), pass.audit())) pass = requestedColdFailure;
            if (!publishesSuccess(pass.attempt(), pass.audit())
                    && !hasCondenserPhaseMismatch(pass)
                    && condenserAttempts.allowsColdRecovery()
                    && pass.attemptedRequestedProblem()
                    && pass.terminalStageCount() >= input.stageCount()
                    && pass.allowsFreshMaterialClosedFallback()) {
                problem = originalProblem(pass);
                // The sequential material/VLE preconditioner is deliberately optional. It must not turn an
                // otherwise solvable MESH problem into a failure; this is a fresh V3 material-closed seed,
                // no approximation or retained warm state is used.
                V3DryMeshState fallbackSeed = initializeForSolve(
                        problem, thermo, V3ColumnInitializer.Mode.MATERIAL_CLOSED);
                pass = withPriorSupportNotes(pass, solveSingleProblem(problem, thermo, fallbackSeed,
                        control, "cold/material-closed-fallback/fine-fd",
                        RungOptions.of(ContinuationJacobianPolicy.NONE, MAXIMUM_NEWTON_ITERATIONS), policy));
                PhaseCorrection fallbackPhase = correctCondenserPhase(pass, thermo, control, policy, condenserAttempts);
                pass = fallbackPhase.pass();
                if (!publishesSuccess(pass.attempt(), pass.audit())
                        && !fallbackPhase.attempted() && !hasCondenserPhaseMismatch(pass)) {
                    V3SolvePass recovered = recoverWithBubblePointProjection(
                            problem, thermo, pass.attempt().state(), control,
                            "cold/material-closed-fallback/material-vle-recovery/fine-fd",
                            ContinuationJacobianPolicy.STAGE_LOCAL_BLOCKS, MAXIMUM_NEWTON_ITERATIONS, policy);
                    recovered = correctCondenserPhase(recovered, thermo, control, policy, condenserAttempts).pass();
                    if (publishesSuccess(recovered.attempt(), recovered.audit())
                            || recovered.attempt().evidence().maximumScaledResidual()
                            < pass.attempt().evidence().maximumScaledResidual()) {
                        pass = withPriorSupportNotes(pass, recovered);
                    }
                }
                // The material-closed fallback may rescue the dry surrogate after an early stage rung
                // fails. It is still only a seed for an authored request that carries draws, steam or
                // stage heat, and must never publish a no-draw, dry surrogate in place of that ramp.
                if ((!input.steamFeeds().isEmpty() || !input.pumparounds().isEmpty()
                        || !input.sideDraws().isEmpty())
                        && publishesSuccess(pass.attempt(), pass.audit())
                        && !pass.prepared().problem().hasSideDraws()
                        && !pass.prepared().problem().hasSteamFeeds()
                        && !pass.prepared().problem().hasPumparounds()) {
                    V3ColumnProblem requested = V3ColumnProblemResolver.resolve(input,
                            pass.prepared().problem().topology().condenserPhaseBranch());
                    pass = recoverWithDrawRamp(requested, thermo, pass, control, policy, condenserAttempts, false);
                }
            }
            V3SimultaneousColumnSolver.Attempt attempt = pass.attempt();
            V3AcceptanceAudit audit = pass.audit();
            String solvePath = pass.solvePath();
            PreparedAttempt selected = pass.prepared();
            if (pass.reachedRequestedProblem() && !publishesSuccess(attempt, audit)
                    && !hasCondenserPhaseMismatch(pass) && condenserAttempts.allowsColdRecovery()) {
                try {
                    control.checkpoint();
                    // A failed material-closed fallback may still describe the feature-free surrogate.
                    // Its state can seed a recovery, but only the authored input can be recovered/published.
                    V3ColumnProblem coarseRequested = V3ColumnProblemResolver.resolve(input,
                            pass.prepared().problem().topology().condenserPhaseBranch());
                    PreparedAttempt coarsePrepared = prepareAttempt(coarseRequested, thermo, pass.recoverySeed(), policy);
                    V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                            coarsePrepared.problem(), thermo, pass.feedMolarEnthalpyJoulesPerMol());
                    V3SimultaneousColumnSolver.Attempt coarseAttempt = V3SimultaneousColumnSolver.solve(
                            coarsePrepared.problem(), evaluator, new V3DryMeshCoordinateMap(coarsePrepared.problem()),
                            coarsePrepared.seed(), thermo::newWorkspace,
                            V3ConvergenceEvidence.unavailable(policy.closureTolerance()), MAXIMUM_NEWTON_ITERATIONS,
                            policy.closureTolerance(),
                            V3FiniteDifferenceJacobian.DifferenceScale.COARSE, control);
                    control.checkpoint();
                    V3AcceptanceAudit coarseAudit = audit(coarsePrepared.problem(), thermo,
                            pass.feedMolarEnthalpyJoulesPerMol(), coarseAttempt.state(), control, policy);
                    V3SolvePass coarsePass = withPriorSupportNotes(pass, new V3SolvePass(coarseAttempt, coarseAudit,
                            pass.feedMolarEnthalpyJoulesPerMol(), "cold/coarse-fd-recovery", pass.recoverySeed(),
                            pass.terminalStageCount(), pass.reachedRequestedProblem(), pass.attemptedRequestedProblem(),
                            pass.allowsFreshMaterialClosedFallback(), List.of("coarse finite-difference recovery"), coarsePrepared));
                    PhaseCorrection coarsePhase = correctCondenserPhase(coarsePass, thermo, control, policy, condenserAttempts);
                    coarsePass = coarsePhase.pass();
                    if (coarsePhase.attempted() || publishesSuccess(coarsePass.attempt(), coarsePass.audit())
                            || coarsePass.attempt().evidence().maximumScaledResidual() < attempt.evidence().maximumScaledResidual()) {
                        pass = coarsePass;
                        attempt = coarsePass.attempt();
                        audit = coarsePass.audit();
                        solvePath = coarsePass.solvePath();
                        selected = coarsePass.prepared();
                    }
                } catch (CancellationException cancelled) {
                    throw cancelled;
                } catch (IllegalStateException unavailableRecovery) {
                    // The primary fine attempt remains a complete typed result when this optional stencil violates
                    // the band-structure guard because of finite-difference noise.
                }
            }
            if (selected.problem().topology().condenserPhaseBranch() == V3CondenserPhaseBranch.LIQUID_ONLY) {
                solvePath += "/liquid-only-condenser";
            }
            if (input.sideDraws().size() > 0) solvePath += "/draws-" + input.sideDraws().size();
            if (!input.steamFeeds().isEmpty()) solvePath += "/steam-" + input.steamFeeds().size();
            if (!input.pumparounds().isEmpty()) solvePath += "/heat-" + input.pumparounds().size();
            List<String> solverEvents = !selected.support().isIdentity()
                    ? mergedEvents(List.of(stageTraceEvent(selected.support(), attempt.state(), selected.problem())), pass.solverEvents())
                    : pass.solverEvents();
            V3SolverDiagnostics diagnostics = diagnostics(attempt, audit, solvePath, solverEvents, policy, null);
            if (pass.reachedRequestedProblem() && publishesRequestedGeometry(pass, input)
                    && attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged converged && audit.accepted()
                    && converged.evidence().convergenceEvidence().satisfiesGates(policy.closureTolerance())) {
                return V3HydraulicPublish.publishAccepted(input, pass, thermo, policy, control, solvePath, solverEvents);
            }
            if (attempt instanceof V3SimultaneousColumnSolver.Attempt.Failure failure) {
                String detail = !pass.attemptedRequestedProblem() && pass.terminalStageCount() >= input.stageCount()
                        ? "Pressure continuation stalled before the requested operating point at "
                        + pass.solvePath() + " after " + failure.evidence().iterations()
                        + " Newton iterations; maximum scaled residual "
                        + failure.evidence().maximumScaledResidual() + ": " + failure.evidence().termination()
                        : pass.terminalStageCount() < input.stageCount()
                        ? "Stage continuation stalled at " + pass.terminalStageCount() + " stages after "
                        + failure.evidence().iterations() + " Newton iterations; maximum scaled residual "
                        + failure.evidence().maximumScaledResidual() + ": " + failure.evidence().termination()
                        : failure.evidence().termination();
                String operatingDetail = sideDrawDiagnostic(selected.problem(), attempt.state(), input.stageCount())
                        + waterDiagnostic(selected.problem(), attempt.state());
                if (!operatingDetail.isEmpty() && detail.length() + operatingDetail.length() > 512) {
                    detail = detail.substring(0, Math.max(0, 512 - operatingDetail.length()));
                }
                return new V3ColumnOutcome.Failure(failureCode(failure.attemptCode()), detail + operatingDetail, diagnostics);
            }
            return new V3ColumnOutcome.Failure(V3SolverFailureCode.ACCEPTANCE_AUDIT_FAILURE,
                    "The fresh V3 acceptance audit rejected the converged candidate"
                            + sideDrawDiagnostic(selected.problem(), attempt.state(), input.stageCount())
                            + waterDiagnostic(selected.problem(), attempt.state()), diagnostics);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (InitializationFailure initialization) {
            return terminalFailure(V3SolverFailureCode.INITIALIZATION_FAILURE,
                    initialization.getMessage(), "initialization", advisoryEvidence);
        } catch (PathDependentHeatBound pathBound) {
            // Not INFEASIBLE_SPECIFICATION: the bound was measured on a continuation state, so the same
            // request can still be solvable from another seed. The diagnostic survives as the hint. The
            // recorded flag keeps the caller's cold-recovery decision exactly as it was under the old code.
            condenserAttempts.recordPathDependentHeatBound();
            return terminalFailure(V3SolverFailureCode.NONCONVERGENCE, pathBound.getMessage(),
                    pathBound.solvePath(), advisoryEvidence);
        } catch (V3ThermoException thermoFailure) {
            return terminalFailure(admitted && policy.attemptCutoff() > 0.0 ? V3SolverFailureCode.NONCONVERGENCE
                    : V3SolverFailureCode.PROPERTY_OUT_OF_RANGE, thermoFailure.getMessage(), "property", advisoryEvidence);
        } catch (IllegalArgumentException invalid) {
            return terminalFailure(admitted && policy.attemptCutoff() > 0.0 ? V3SolverFailureCode.NONCONVERGENCE
                    : V3SolverFailureCode.INVALID_INPUT, invalid.getMessage(), "input", advisoryEvidence);
        } catch (IllegalStateException internal) {
            return terminalFailure(V3SolverFailureCode.INTERNAL_ERROR, internal.getMessage(), "internal", advisoryEvidence);
        }
    }

    /** Attempts a direct correction from the previous accepted state; a rejected candidate never replaces cold solve. */
    private static V3SolvePass warmStartPass(V3ColumnInput input, V3ColumnProblem problem,
            V3PengRobinsonThermo thermo, V3SolveControl control, V3SolvePolicy policy,
            V3SolveStrategy strategy, V3NeuralSeed warmStart) {
        if (warmStart == null || warmStart.branch() != problem.topology().condenserPhaseBranch()
                || !(input.equals(warmStart.input()) || warmCompatible(input, warmStart.input(), strategy))) {
            return null;
        }
        V3DryMeshState seed;
        try {
            seed = retargetWarmSeed(input, problem, warmStart);
            int iterations = strategy == V3SolveStrategy.AGGRESSIVE ? 8 : 16;
            V3SolvePass pass = solveSingleProblem(problem, thermo, seed, control,
                    strategy == V3SolveStrategy.AGGRESSIVE ? "warm/aggressive" : "warm/drift-spec",
                    RungOptions.of(ContinuationJacobianPolicy.STAGE_LOCAL_BLOCKS, iterations), policy);
            if (publishesSuccess(pass.attempt(), pass.audit())) return pass;
        } catch (V3ThermoException | IllegalArgumentException | IllegalStateException ignored) {
            // Warm starts are an optional cache. Any shape, property or trust-region mismatch uses cold recovery.
        }
        return null;
    }

    private static V3DryMeshState retargetWarmSeed(V3ColumnInput input, V3ColumnProblem problem,
            V3NeuralSeed source) {
        double[] temperature = source.temperatures();
        temperature[0] = input.specifications().stream()
                .filter(V3ColumnSpecification.CondenserOutletTemperature.class::isInstance)
                .map(V3ColumnSpecification.CondenserOutletTemperature.class::cast).findFirst().orElseThrow().kelvin();
        double[] water = source.freeWater();
        for (double value : water) if (value != 0.0) throw new IllegalArgumentException("wet warm seed requires wet continuation");
        V3NeuralSeed retargeted = new V3NeuralSeed(input, source.propertyRevision(), source.branch(),
                source.liquid(), source.vapor(), temperature, water, new boolean[input.stageCount() + 2]);
        return retargeted.stateFor(problem);
    }

    private static boolean warmCompatible(V3ColumnInput target, V3ColumnInput source, V3SolveStrategy strategy) {
        if (!target.packageId().equals(source.packageId()) || !target.componentBasis().equals(source.componentBasis())
                || target.stageCount() != source.stageCount() || target.feedStageNumber() != source.feedStageNumber()
                || !sameFeatureShape(target, source)) {
            return false;
        }
        double limit = strategy == V3SolveStrategy.AGGRESSIVE ? 0.50 : 0.15;
        if (relativeDrift(target.topPressurePascal(), source.topPressurePascal()) > limit
                || relativeDrift(target.feedTemperatureKelvin(), source.feedTemperatureKelvin()) > limit
                || relativeDrift(specValue(target, V3ColumnSpecification.CondenserOutletTemperature.class),
                        specValue(source, V3ColumnSpecification.CondenserOutletTemperature.class)) > limit) {
            return false;
        }
        double[] a = target.feedComponentMolarFlowsMolPerSecond(), b = source.feedComponentMolarFlowsMolPerSecond();
        for (int i = 0; i < a.length; i++) if (relativeDrift(a[i], b[i]) > limit) {
            return false;
        }
        return relativeDrift(reboilerDuty(target), reboilerDuty(source)) <= limit;
    }

    private static boolean sameFeatureShape(V3ColumnInput a, V3ColumnInput b) {
        if (a.sideDraws().size() != b.sideDraws().size() || a.steamFeeds().size() != b.steamFeeds().size()
                || a.pumparounds().size() != b.pumparounds().size()) return false;
        for (int i = 0; i < a.sideDraws().size(); i++)
            if (a.sideDraws().get(i).trayNumber() != b.sideDraws().get(i).trayNumber()) return false;
        for (int i = 0; i < a.steamFeeds().size(); i++)
            if (a.steamFeeds().get(i).stageNumber() != b.steamFeeds().get(i).stageNumber()) return false;
        for (int i = 0; i < a.pumparounds().size(); i++)
            if (a.pumparounds().get(i).drawTray() != b.pumparounds().get(i).drawTray()
                    || a.pumparounds().get(i).returnTray() != b.pumparounds().get(i).returnTray()) return false;
        return true;
    }

    static double specValue(V3ColumnInput input,
            Class<? extends V3ColumnSpecification> type) {
        return input.specifications().stream().filter(type::isInstance).findFirst()
                .map(spec -> spec instanceof V3ColumnSpecification.CondenserOutletTemperature t ? t.kelvin()
                        : spec instanceof V3ColumnSpecification.ReboilerDuty q ? q.watts()
                        : spec instanceof V3ColumnSpecification.OrganicRefluxRatio r ? r.ratio() : 0.0)
                .orElse(0.0);
    }

    private static double reboilerDuty(V3ColumnInput input) {
        return specValue(input, V3ColumnSpecification.ReboilerDuty.class);
    }

    private static double relativeDrift(double a, double b) {
        return Math.abs(a - b) / Math.max(1.0, Math.max(Math.abs(a), Math.abs(b)));
    }

    /** One rung's solve that starts dry: the wet set is the solved problem's own dry one. */
    static V3SolvePass solveSingleProblem(V3ColumnProblem problem, V3PengRobinsonThermo thermo,
            V3DryMeshState seed, V3SolveControl control, String solvePath, RungOptions rung, V3SolvePolicy policy) {
        return solveSingleProblem(problem, thermo, seed, control, solvePath, rung, policy,
                V3WetTraySet.dry(problem.topology()));
    }

    static V3SolvePass solveSingleProblem(V3ColumnProblem problem, V3PengRobinsonThermo thermo,
            V3DryMeshState seed, V3SolveControl control, String solvePath, RungOptions rung, V3SolvePolicy policy,
            V3WetTraySet initialWetTrays) {
        int maximumIterations = rung.maximumIterations();
        V3SimultaneousColumnSolver.RungBudget budget = rung.budget();
        V3SimultaneousColumnSolver.Extension extension = rung.extension();
        if (maximumIterations < 1 || maximumIterations > MAXIMUM_NEWTON_ITERATIONS) {
            throw new IllegalArgumentException("V3 simultaneous solve iteration limit is invalid");
        }
        ContinuationJacobianPolicy jacobianPolicy = Objects.requireNonNull(rung.jacobianPolicy(), "jacobianPolicy");
        // Only the continuation path has rungs whose failure a caller absorbs; a reduced budget anywhere else
        // would silently weaken a solve that has nothing to fall back on.
        if ((!Objects.requireNonNull(budget, "budget").equals(V3SimultaneousColumnSolver.RungBudget.DEFAULT)
                || Objects.requireNonNull(extension, "extension").enabled())
                && jacobianPolicy != ContinuationJacobianPolicy.STAGE_LOCAL_BLOCKS) {
            throw new IllegalArgumentException("V3 reduced rung budgets belong to the stage-local continuation path");
        }
        control.checkpoint();
        V3FlashResult feedFlash = feedFlash(problem, thermo, control, policy);
        // A phase-allocation approximation must never change the authored feed's physical energy.
        double feedMolarEnthalpy = feedFlash.referenceMolarEnthalpyJoulesPerMol();
        V3DryMeshState recoverySeed = seed;
        V3ColumnProblem untruncated = problem;
        PreparedAttempt prepared = prepareAttempt(untruncated, thermo, seed, policy, initialWetTrays, false);
        if (!prepared.wetTrays().sameSet(initialWetTrays))
            throw new IllegalArgumentException("Requested initial wet set cannot form a valid ledger");
        SolveTelemetry telemetry;
        V3SimultaneousColumnSolver.Attempt attempt;
        V3AcceptanceAudit audit;
        int nextIterations = maximumIterations;
        int refreshes = 0;
        int stalledRefreshes = 0;
        int wetTrayRefreshes = 0;
        // The interactive closure already tolerates the bounded floor defect; one refresh catches a stale support
        // without paying for the repeated polish passes used by strict/offline certificates.
        int floorRefreshLimit = policy.fastPublication() ? 1 : MAXIMUM_FLOOR_SUPPORT_REFRESHES;
        int attempts = 0;
        List<String> wetTrayEvents = new ArrayList<>();
        // The floor support is frozen for the length of one Newton solve, so it is re-derived from the
        // solved state afterwards: a point that fell below the floor is dropped, and a removed point whose
        // retained neighbours now deliver at least its floor is reinserted at the floor and the solve is
        // repeated from that state. Without the reinsertion pass the omitted mass would not be bounded;
        // with it, the audited sink-edge defect can never exceed one floor per edge.
        while (true) {
            V3ColumnProblem attemptProblem = prepared.problem();
            V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                    attemptProblem, thermo, feedMolarEnthalpy);
            telemetry = new SolveTelemetry(attemptProblem, policy.progress());
            V3DryMeshCoordinateMap coordinates = new V3DryMeshCoordinateMap(attemptProblem);
            V3DryMeshState attemptSeed = prepared.seed();
            if (policy.progress() != null) {
                policy.progress().beganAttempt(++attempts, refreshes, wetTrayRefreshes, nextIterations,
                        prepared.support().totalPointCount() - prepared.support().truncatedPointCount(),
                        prepared.support().totalPointCount(), prepared.wetTrays().wetTrayCount());
            }
            attempt = switch (jacobianPolicy) {
                case NONE -> V3SimultaneousColumnSolver.solve(
                        attemptProblem, evaluator, coordinates, attemptSeed, thermo::newWorkspace,
                        V3ConvergenceEvidence.unavailable(policy.closureTolerance()), nextIterations,
                        policy.closureTolerance(),
                        V3FiniteDifferenceJacobian.DifferenceScale.FINE, control, telemetry);
                case STAGE_LOCAL_BLOCKS -> V3SimultaneousColumnSolver.solveWithContinuationLocalBlocks(
                        attemptProblem, evaluator, coordinates, attemptSeed, thermo::newWorkspace,
                        nextIterations, policy.closureTolerance(), control, telemetry, budget, extension);
                case PRESSURE_LOCAL_PREDICTOR -> V3SimultaneousColumnSolver.solveWithOneLocalBlockPredictor(
                        attemptProblem, evaluator, coordinates, attemptSeed, thermo::newWorkspace,
                        nextIterations, policy.closureTolerance(), control, telemetry);
            };
            if (policy.progress() != null) {
                policy.progress().finishedAttempt(attempts,
                        attempt instanceof V3SimultaneousColumnSolver.Attempt.Failure failed ? failed.code() : "CONVERGED",
                        attempt.evidence().iterations(), attempt.evidence().maximumScaledResidual());
            }
            control.checkpoint();
            audit = audit(attemptProblem, thermo, feedMolarEnthalpy, attempt.state(), control, policy);
            // A stalled attempt is refreshed too, and measurably must be: a stall is often exactly the state
            // that has just dried a point out or started feeding a removed one, and one repeat from the
            // corrected support is what converges the rung. Only one, though: a second stall in a row is a
            // rung the continuation is going to subdivide or abandon anyway, and re-solving it three times
            // triples the cost of every case that is on its way to a typed failure.
            //
            // A release is not a stall. The solve held a point at its floor and stopped earning steps because of
            // it, or a free point sank below its floor and left no Newton step (STALLED_SUPPORT_FLOOR): the
            // refresh that drops that point is the next step of the same solve, so it is neither counted against
            // the stalled limit nor cut to the polish budget.
            boolean converged = attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged;
            boolean released = attempt instanceof V3SimultaneousColumnSolver.Attempt.Failure heldFailure
                    && heldFailure.attemptCode() == V3AttemptCode.STALLED_SUPPORT_FLOOR;
            boolean floorBudget = refreshes < floorRefreshLimit
                    && (converged || released || stalledRefreshes < MAXIMUM_STALLED_FLOOR_SUPPORT_REFRESHES);
            // The wet set is re-derived only from a state that solved its own equations, and carries its own
            // small budget so it never has to compete with the support refreshes for one. How far a tray sits
            // below the water dew point, and how much water it must therefore shed, are readings of a
            // solution; taken off a stalled iterate they are noise, and a free-water flow seeded from noise
            // is megawatts of latent heat in the wrong place.
            boolean wetBudget = converged && wetTrayRefreshes < MAXIMUM_WET_TRAY_REFRESHES;
            if (!floorBudget && !wetBudget) break;
            PreparedAttempt refreshed = refreshFloorSupport(
                    untruncated, thermo, prepared, attempt.state(), policy, wetBudget);
            if (refreshed == null) break;
            // See STALLED_DROP_REFRESH_ITERATIONS: a refresh that only removes points from a stalled attempt
            // is a bounded polish, not a second full Newton solve.
            // A refresh that only removes — points from the support, trays from the wet set — carries no
            // information the stalled state did not already have. One that adds a wet tray does: the water
            // that tray sheds is a degree of freedom the stalled solve never had, so it keeps the full budget.
            nextIterations = !converged && !released
                    && refreshed.support().presentPhaseCount() <= prepared.support().presentPhaseCount()
                    && refreshed.wetTrays().wetTrayCount() <= prepared.wetTrays().wetTrayCount()
                    ? Math.min(maximumIterations, STALLED_DROP_REFRESH_ITERATIONS)
                    : maximumIterations;
            if (!refreshed.support().sameRetention(prepared.support())) {
                refreshes++;
                if (!converged && !released) stalledRefreshes++;
            }
            if (!refreshed.wetTrays().sameSet(prepared.wetTrays())) {
                wetTrayRefreshes++;
                if (!refreshed.wetTrays().hasWetTrays()) {
                    // A refresh that only takes trays away is an energy step like a heat rung, and the
                    // predictor is what re-levels the profile for it.
                    refreshed = withWetEnergyShift(refreshed, thermo, feedMolarEnthalpy, control, wetTrayEvents);
                } else {
                    // A refresh that admits one places its free water by continuation instead: the
                    // simultaneous system cannot see past the energy plateau the free-water unknown creates,
                    // while a parametric solve re-solves every energy row exactly at each step.
                    PreparedAttempt continued = withFreeWaterContinuation(
                            untruncated, thermo, feedMolarEnthalpy, refreshed, control, policy, wetTrayEvents);
                    if (continued != null) {
                        refreshed = continued;
                    } else if (refreshed.support().sameRetention(prepared.support())) {
                        // The derived wet set has no realisable free-water flow and nothing else moved. The
                        // converged candidate this refresh came from is the answer the column has, and its
                        // WATER_DEW_POINT check reports the supersaturated stage by name.
                        break;
                    } else {
                        // The support still has to be refreshed, but a set the continuation has refused is
                        // not derived again on this solve: it would cost the same solves for the same answer.
                        wetTrayRefreshes = MAXIMUM_WET_TRAY_REFRESHES;
                        refreshed = prepareAttempt(
                                untruncated, thermo, attempt.state(), policy, prepared.wetTrays(), false);
                    }
                }
            }
            prepared = refreshed;
        }
        problem = prepared.problem();
        List<String> events = telemetry.events();
        if (refreshes > 0) {
            events = mergedEvents(List.of("floor support refreshed " + refreshes + " time(s); retained="
                    + (prepared.support().totalPointCount() - prepared.support().truncatedPointCount())
                    + "/" + prepared.support().totalPointCount()), events);
        }
        // The wet-tray events are published even when the set was refused: why a supersaturated stage did
        // not take a free-water phase is exactly what an operator reading a failed dew-point check needs.
        if (prepared.wetTrays().hasWetTrays()) {
            events = mergedEvents(mergedEvents(List.of(prepared.wetTrays().event(attempt.state())), wetTrayEvents), events);
        } else if (!wetTrayEvents.isEmpty()) {
            events = mergedEvents(wetTrayEvents, events);
        }
        if (policy.attemptCutoff() > 0.0) events = mergedEvents(List.of(flashTraceEvent(feedFlash)), events);
        if (!prepared.support().note().isEmpty()) {
            events = mergedEvents(List.of("stage-trace support: " + prepared.support().note()), events);
        }
        return new V3SolvePass(attempt, audit, feedMolarEnthalpy, solvePath,
                recoverySeed, problem.input().stageCount(), true, true, true, events, prepared);
    }

    /** The authored feed's flash at the feed tray, truncated exactly as this attempt's policy asks. */
    static V3FlashResult feedFlash(
            V3ColumnProblem problem, V3PengRobinsonThermo thermo, V3SolveControl control, V3SolvePolicy policy) {
        return policy.attemptCutoff() > 0.0
                ? thermo.flashTP(problem.input().feedTemperatureKelvin(),
                        problem.nodePressurePascal(problem.topology().feedTrayNumber()),
                        problem.input().feedComponentMolarFlowsMolPerSecond(), TraceTruncationPolicy.of(policy.attemptCutoff()),
                        thermo.newWorkspace(), control::checkpoint)
                : untruncatedFeedFlash(problem, thermo);
    }

    /** The untruncated feed flash, once per calculation and feed-tray pressure (P6): it polls nothing, so caching skips no poll. */
    private static V3FlashResult untruncatedFeedFlash(V3ColumnProblem problem, V3PengRobinsonThermo thermo) {
        double temperature = problem.input().feedTemperatureKelvin();
        double pressure = problem.nodePressurePascal(problem.topology().feedTrayNumber());
        double[] feed = problem.input().feedComponentMolarFlowsMolPerSecond();
        return V3SolveScratch.feedFlash(thermo.datasetRevision(), temperature, pressure, feed,
                () -> thermo.flashTP(temperature, pressure, feed, thermo.newWorkspace()));
    }

    /** One warm condenser flash correction per rung; its candidate still needs the full fresh audit. */
    static PhaseCorrection correctCondenserPhase(
            V3SolvePass pass, V3PengRobinsonThermo thermo, V3SolveControl control,
            V3SolvePolicy policy, CondenserAttempts condenserAttempts) {
        if (!hasCondenserPhaseMismatch(pass)) return new PhaseCorrection(pass, false);
        condenserAttempts.beginPhaseCorrection();
        if (!pass.attempt().evidence().convergenceEvidence().satisfiesGates()
                || pass.audit().checks().stream().anyMatch(check -> !check.hasFamily(V3AuditFamily.CONDENSER_PHASE) && !check.passed())) {
            return new PhaseCorrection(withPhaseEvents(pass,
                    "condenser phase transition unavailable: other convergence or audit gates also failed"), true);
        }
        V3CondenserPhaseTransition.Prepared transition;
        try {
            transition = V3CondenserPhaseTransition.prepare(pass.prepared().problem(), pass.attempt().state(), thermo, control);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (V3ThermoException | IllegalArgumentException unavailable) {
            String detail = "condenser phase transition unavailable: " + unavailable.getMessage();
            return new PhaseCorrection(withPhaseEvents(pass, detail.length() <= 256 ? detail : detail.substring(0, 256)), true);
        }
        V3CondenserPhaseBranch sourceBranch = pass.prepared().problem().topology().condenserPhaseBranch();
        V3CondenserPhaseBranch targetBranch = transition.problem().topology().condenserPhaseBranch();
        condenserAttempts.recordAttempt(targetBranch);
        String event = "condenser phase transition: " + sourceBranch + " -> " + targetBranch
                + "; overhead flash vapor fraction=" + transition.vaporFraction();
        V3SolvePass corrected = solveSingleProblem(transition.problem(), thermo, transition.seed(), control,
                pass.solvePath() + "/condenser-phase-correction",
                RungOptions.of(ContinuationJacobianPolicy.PRESSURE_LOCAL_PREDICTOR,
                        CONDENSER_PHASE_CORRECTOR_MAXIMUM_ITERATIONS), policy);
        condenserAttempts.finishPhaseCorrection(publishesSuccess(corrected.attempt(), corrected.audit()));
        return new PhaseCorrection(withPhaseEvents(withPriorSupportNotes(pass, corrected), event), true);
    }

    /**
     * One rung that starts dry, then its condenser phase correction, on the same policy and attempt history.
     *
     * <p>The rungs that take nothing between the two and keep only the corrected pass: the direct and base
     * solves of a dry-top column or a pot, a feature-ramp rung and the ramp's request retry. The stage grid, the
     * pressure leg, the material-closed fallback and the coarse recovery do more between them or keep the
     * correction's verdict, so they run their own steps.</p>
     */
    static V3SolvePass runRung(V3ColumnProblem problem, V3PengRobinsonThermo thermo, V3DryMeshState seed,
            V3SolveControl control, String solvePath, RungOptions rung, V3SolvePolicy policy,
            CondenserAttempts condenserAttempts) {
        V3SolvePass solved = solveSingleProblem(problem, thermo, seed, control, solvePath, rung, policy);
        return correctCondenserPhase(solved, thermo, control, policy, condenserAttempts).pass();
    }

    private static V3SolvePass withPhaseEvents(V3SolvePass pass, String event) {
        return new V3SolvePass(pass.attempt(), pass.audit(), pass.feedMolarEnthalpyJoulesPerMol(), pass.solvePath(),
                pass.recoverySeed(), pass.terminalStageCount(), pass.reachedRequestedProblem(),
                pass.attemptedRequestedProblem(), false, mergedEvents(List.of(event), pass.solverEvents()), pass.prepared());
    }

    record PhaseCorrection(V3SolvePass pass, boolean attempted) {}

    /** Solve-confined attempt history; prevents duplicate cold branch restarts after a phase correction. */
    static final class CondenserAttempts {
        private final EnumSet<V3CondenserPhaseBranch> branches = EnumSet.noneOf(V3CondenserPhaseBranch.class);
        private boolean unresolvedPhaseMismatch;
        private boolean requestedDrawRampFailed;
        private boolean pathDependentHeatBound;

        void recordAttempt(V3CondenserPhaseBranch branch) { branches.add(Objects.requireNonNull(branch, "branch")); }
        boolean hasAttempted(V3CondenserPhaseBranch branch) { return branches.contains(branch); }
        void beginPhaseCorrection() { unresolvedPhaseMismatch = true; }
        void finishPhaseCorrection(boolean auditedSuccess) {
            if (auditedSuccess) unresolvedPhaseMismatch = false;
        }
        void recordRequestedDrawRampFailure() { requestedDrawRampFailed = true; }
        // A heat bound stopped the ramp after the whole heat-free chain had already been accepted. Retyping it
        // from INFEASIBLE_SPECIFICATION to NONCONVERGENCE must not silently buy a second cold chain on the other
        // condenser branch: the bound is about the authored duty against that accepted state, not about the
        // condenser phase, so the alternate branch would re-derive the same stop at twice the cost.
        void recordPathDependentHeatBound() { pathDependentHeatBound = true; }
        boolean allowsColdRecovery() {
            return !unresolvedPhaseMismatch && !requestedDrawRampFailed && !pathDependentHeatBound;
        }
    }

    /**
     * Re-levels the temperature profile after a free-water tray leaves the frozen set.
     *
     * <p>Losing a wet tray is an energy step of exactly the kind the ramp's rungs make: the free water it was
     * shedding carried its latent heat out of the tray below and into the tray itself, so the seed's energy
     * rows move by that duty while its temperatures still belong to the wet solution. That is the common mode
     * {@link V3EnergyShiftPredictor} solves for, and the gate it requires is satisfied here by construction: a
     * wet refresh only follows a converged attempt.</p>
     *
     * <p>A refresh that <em>admits</em> a tray is handled by {@link V3FreeWaterContinuation} instead, which
     * re-solves every energy row exactly at each of its steps rather than linearising them once.</p>
     */
    private static PreparedAttempt withWetEnergyShift(
            PreparedAttempt prepared, V3PengRobinsonThermo thermo, double feedMolarEnthalpyJoulesPerMol,
            V3SolveControl control, List<String> events) {
        V3EnergyShiftPredictor.Prediction prediction = V3EnergyShiftPredictor.predict(prepared.problem(), thermo,
                feedMolarEnthalpyJoulesPerMol, prepared.seed(), thermo.newWorkspace(), control);
        if (events.size() < V3SolverDiagnostics.MAX_EVENTS) {
            events.add(bounded(prediction.applied()
                    ? String.format(Locale.ROOT,
                            "free-water energy shift: largest %.3f K, scaled energy %.6g -> %.6g",
                            prediction.largestShiftKelvin(), prediction.scaledEnergyBefore(),
                            prediction.scaledEnergyAfter())
                    : "free-water energy shift declined: " + prediction.note()));
        }
        if (!prediction.applied()) return prepared;
        return new PreparedAttempt(prepared.problem(), prepared.support(), prepared.wetTrays(),
                prediction.applyTo(prepared.seed(), prepared.problem().topology()));
    }

    /**
     * Places the free water by continuation before the simultaneous wet system is asked to certify it.
     *
     * <p>Returns null when there is nothing to continue or the continuation declined, in which case the
     * caller keeps the existing path. On success the attempt is rebuilt around the set the continuation
     * ended on — which may be deeper than the refresh derived, because a parametric tray costs no ledger
     * change and the continuation may therefore admit the trays the falling water wets. The seed it returns
     * satisfies every row of the wet system already, so the certifying Newton starts inside its own
     * tolerance. See {@link V3FreeWaterContinuation}.</p>
     */
    private static PreparedAttempt withFreeWaterContinuation(
            V3ColumnProblem untruncated, V3PengRobinsonThermo thermo, double feedMolarEnthalpyJoulesPerMol,
            PreparedAttempt prepared, V3SolveControl control, V3SolvePolicy policy, List<String> events) {
        if (!prepared.wetTrays().hasWetTrays()) return null;
        V3FreeWaterContinuation.Result result = V3FreeWaterContinuation.run(untruncated, prepared.support(),
                prepared.wetTrays(), prepared.seed(), thermo, feedMolarEnthalpyJoulesPerMol,
                policy.closureTolerance(), control);
        if (events.size() < V3SolverDiagnostics.MAX_EVENTS) events.add(bounded(result.event()));
        if (!result.converged()) return null;
        V3ColumnProblem problem;
        try {
            problem = V3ColumnProblemResolver.withTruncation(untruncated, prepared.support(), result.wetTrays());
        } catch (IllegalArgumentException invalidLedger) {
            return null;
        }
        return new PreparedAttempt(problem, prepared.support(), result.wetTrays(),
                result.wetTrays().seed(problem, result.state()));
    }

    private static String bounded(String event) {
        return event.length() <= 256 ? event : event.substring(0, 256);
    }

    /** Alias of {@link V3ColumnLabels#formulationRevision(V3ColumnInput, double, double)} for the learned-start tools in {@code tools/}. */
    static String formulationRevision(V3ColumnInput input, double requestedCutoff, double closureTolerance) {
        return V3ColumnLabels.formulationRevision(input, requestedCutoff, closureTolerance);
    }

    static V3SolvePass withPriorSupportNotes(V3SolvePass previous, V3SolvePass next) {
        // Preserve availability and phase-transition evidence without retaining earlier numerical states.
        List<String> notes = null;
        for (String event : previous.solverEvents()) {
            if (!event.startsWith("stage-trace support:") && !event.startsWith("condenser phase transition")
                    && !event.startsWith("flash-trace ")) continue;
            if (notes == null) notes = new ArrayList<>();
            if (!notes.contains(event) && !next.solverEvents().contains(event)) notes.add(event);
        }
        if (notes == null || notes.isEmpty()) return next;
        return new V3SolvePass(next.attempt(), next.audit(), next.feedMolarEnthalpyJoulesPerMol(), next.solvePath(),
                next.recoverySeed(), next.terminalStageCount(), next.reachedRequestedProblem(),
                next.attemptedRequestedProblem(), next.allowsFreshMaterialClosedFallback(),
                mergedEvents(notes, next.solverEvents()), next.prepared());
    }

    /**
     * The deciding seed is final here: interpolation/preconditioning happens outside this frozen attempt.
     *
     * <p>The seed is lifted first. A point removed by an earlier attempt or an earlier continuation grid
     * holds exact zeros, so it could never re-enter from the state alone; lifting restores every point whose
     * retained neighbours are delivering material to it before the support is derived, which keeps the
     * decision self-consistent at every rung instead of only at a refresh.</p>
     */
    static PreparedAttempt prepareAttempt(
            V3ColumnProblem original, V3PengRobinsonThermo thermo, V3DryMeshState seed, V3SolvePolicy policy) {
        return prepareAttempt(original, thermo, seed, policy, V3WetTraySet.dry(original.topology()), false);
    }

    /**
     * Freezes both attempt-local decisions — the flow-floor support and the free-water tray set — together.
     *
     * <p>{@code previousWetTrays} is the set the candidate state belongs to, and it is what gives the wet
     * decision its hysteresis: a tray that is already wet stays wet until its solved free water falls below
     * a floor, while a dry tray needs its saturation ratio to clear one by a margin.</p>
     *
     * <p>With {@code deriveWetTrays} false the set is taken as given, and the first attempt of every solve
     * therefore runs <strong>dry</strong>. The free-water decision is not a structural one like the flow
     * floor: it asks how far a tray sits below the water dew point and how much water it must therefore
     * shed, and both readings come out of a state that has not satisfied its own equations as nonsense.
     * Measured on the literature column at its published duties, deriving the set from a stalled iterate put
     * 773 kmol/h of free water on tray one against a true value near 210, poisoned the top three energy rows
     * by 10, 6 and 4 MW, and left Newton accepting every step while the residual moved from 0.8454 to 0.8448
     * in 32 iterations. Only a converged attempt spends a wet refresh, which is the rule the energy-shift
     * predictor already follows for the same reason.</p>
     */
    static PreparedAttempt prepareAttempt(
            V3ColumnProblem original, V3PengRobinsonThermo thermo, V3DryMeshState seed, V3SolvePolicy policy,
            V3WetTraySet currentWetTrays, boolean deriveWetTrays) {
        seed = seedForTopology(original, seed);
        V3DryMeshState lifted = V3FloorLift.liftFloorSupport(original, thermo, seed);
        V3TruncationSupport support = V3TruncationSupport.derive(original, policy.attemptCutoff(), lifted);
        V3WetTraySet wetTrays = deriveWetTrays
                ? V3WetTraySet.derive(original, lifted, currentWetTrays) : currentWetTrays;
        V3ColumnProblem problem;
        try {
            problem = V3ColumnProblemResolver.withTruncation(original, support, wetTrays);
        } catch (IllegalArgumentException invalidLedger) {
            support = support.fallbackToIdentity(original,
                    "Stage-trace support fell back to identity: reduced ledger validation failed");
            wetTrays = V3WetTraySet.dry(original.topology());
            problem = original;
        }
        return new PreparedAttempt(problem, support, wetTrays,
                wetTrays.seed(problem, support.projectSeed(original, lifted)));
    }

    /** Phase availability can change when a cooling rung wets a previously dry upper section. */
    private static V3DryMeshState seedForTopology(V3ColumnProblem problem, V3DryMeshState seed) {
        V3ColumnTopology topology = problem.topology();
        double[][] liquid = V3ColumnInitializer.flows(seed, true);
        double[][] vapor = V3ColumnInitializer.flows(seed, false);
        boolean changed = false;
        for (int node = 0; node < seed.nodeCount(); node++) {
            double liquidTotal = 0.0;
            for (double flow : liquid[node]) liquidTotal += flow;
            double vaporTotal = 0.0, downstreamDraws = 0.0;
            for (double flow : vapor[node]) vaporTotal += flow;
            for (V3SideDrawSpec draw : problem.input().sideDraws()) if (draw.trayNumber() >= node) downstreamDraws += draw.molarFlowMolPerSecond();
            double appearingLiquid = downstreamDraws + 0.01 * vaporTotal;
            for (int c = 0; c < seed.componentCount(); c++) {
                if (!topology.hasLiquidPhase(node) && liquid[node][c] != 0.0) {
                    liquid[node][c] = 0.0;
                    changed = true;
                } else if (topology.hasLiquidPhase(node) && liquidTotal == 0.0
                        && problem.condenserComponentPhases().hasLiquid(topology, node, c)) {
                    liquid[node][c] = Math.max(V3FloorLift.componentFloor(problem, c),
                            vaporTotal > 0.0 ? appearingLiquid * vapor[node][c] / vaporTotal : V3FloorLift.componentFloor(problem, c));
                    changed = true;
                }
                if (!topology.hasVaporPhase(node) && vapor[node][c] != 0.0) {
                    vapor[node][c] = 0.0;
                    changed = true;
                }
            }
        }
        return changed ? new V3DryMeshState(topology, seed.componentCount(), liquid, vapor,
                V3ColumnInitializer.temperatures(seed), V3ColumnInitializer.freeWaterFlows(seed)) : seed;
    }

    /**
     * Re-derives the floor support and the wet-tray set from a solved state.
     *
     * <p>Returns null when neither moved. The wet set is refreshed on exactly the same schedule as the
     * support and for the same reason: both are frozen for the length of one Newton solve, so the state that
     * solve produced is the first honest evidence about whether the decision was right.</p>
     */
    private static PreparedAttempt refreshFloorSupport(
            V3ColumnProblem untruncated, V3PengRobinsonThermo thermo, PreparedAttempt prepared,
            V3DryMeshState state, V3SolvePolicy policy, boolean deriveWetTrays) {
        PreparedAttempt refreshed = prepareAttempt(
                untruncated, thermo, state, policy, prepared.wetTrays(), deriveWetTrays);
        return refreshed.support().sameRetention(prepared.support())
                && refreshed.wetTrays().sameSet(prepared.wetTrays()) ? null : refreshed;
    }

    private static V3AcceptanceAudit audit(
            V3ColumnProblem problem,
            V3PengRobinsonThermo thermo,
            double feedMolarEnthalpy,
            V3DryMeshState state,
            V3SolveControl control,
            V3SolvePolicy policy) {
        try {
            return new V3AcceptanceAuditor(problem, thermo, feedMolarEnthalpy, policy.closureTolerance())
                    .audit(state, thermo.newWorkspace(), control);
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (RuntimeException unavailable) {
            return failedAudit(V3AuditFamily.UNAVAILABLE, unavailable.getMessage());
        }
    }

    static boolean publishesSuccess(V3SimultaneousColumnSolver.Attempt attempt, V3AcceptanceAudit audit) {
        return attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged converged
                && converged.evidence().convergenceEvidence().satisfiesGates() && audit.accepted();
    }

    /** A pass's bounded evidence; {@code trailer} (the calculation's band factorizations, P7, or null) follows the termination where there is room. */
    static V3SolverDiagnostics diagnostics(
            V3SimultaneousColumnSolver.Attempt attempt,
            V3AcceptanceAudit audit,
            String solvePath,
            List<String> solverEvents,
            V3SolvePolicy policy,
            String trailer) {
        V3SimultaneousColumnSolver.Evidence evidence = attempt.evidence();
        double finalStepNorm = Math.max(evidence.convergenceEvidence().maximumLogFlowChange(),
                evidence.convergenceEvidence().maximumTemperatureChangeKelvin());
        List<String> events = new ArrayList<>(solverEvents);
        if (events.size() < V3SolverDiagnostics.MAX_EVENTS) events.add(evidence.termination());
        if (trailer != null && events.size() < V3SolverDiagnostics.MAX_EVENTS) events.add(boundedEvent(trailer));
        return new V3SolverDiagnostics(0, evidence.iterations(), 0, 0, evidence.maximumScaledResidual(), finalStepNorm,
                boundedSolvePath(solvePath), events, audit, evidence.convergenceEvidence(), policy.closureTolerance());
    }
}
