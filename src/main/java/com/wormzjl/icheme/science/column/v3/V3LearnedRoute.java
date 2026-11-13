package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.column.v3.V3SimultaneousColumnSolver.NewtonTrace;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.column.v3.thermo.V3ThermoException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

import static com.wormzjl.icheme.science.column.v3.V3SolveReport.boundedEvent;
import static com.wormzjl.icheme.science.column.v3.V3SolveReport.boundedSummary;
import static com.wormzjl.icheme.science.column.v3.V3SolveReport.failedAudit;
import static com.wormzjl.icheme.science.column.v3.V3SolveReport.mergedEvents;
import static com.wormzjl.icheme.science.column.v3.V3SolveReport.terminalFailure;

/**
 * The transformer initializer's route: the learned candidates under their own poll budget, each corrected by one
 * bounded Newton pass, and the one classical backup when none publishes.
 *
 * <p>Moved out of {@link V3ColumnCalculator} unchanged (batch 2026-11-13-codebase-review, D-C2). The calculator keeps
 * the entry points and the catalog pin, and calls {@link #calculateInCatalog} (package-private with the move). The
 * route calls back into the calculator for the classical backup, the attempt preparation, the feed flash, the
 * single-problem solve, the acceptance gates and the diagnostics.</p>
 */
final class V3LearnedRoute {
    private V3LearnedRoute() {}

    static V3ColumnOutcome calculateInCatalog(V3ColumnInput input, V3SolveControl control,
            double cutoff, double closureFraction, V3InitializationOptions options, V3NeuralInitializer model,
            java.util.function.Consumer<V3NeuralSeed> observer, double liquidSupplyScreenRatio,
            NewtonTrace trace) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(control, "control");
        Objects.requireNonNull(options, "options");
        control.checkpoint();
        V3TruncationSupport.requireCutoff(cutoff);
        V3LiquidSupplyScreen.requireRatio(liquidSupplyScreenRatio);
        double closure = V3ColumnCalculator.closureTolerance(closureFraction);
        if (options.mode() == V3InitializationOptions.Mode.CURRENT_ONLY)
            return observer == null ? V3ColumnCalculator.calculate(input, control, cutoff, closureFraction, liquidSupplyScreenRatio)
                    : V3ColumnCalculator.calculateWithAcceptedProfile(input, control, observer, liquidSupplyScreenRatio);
        Objects.requireNonNull(model, "model");
        try {
            V3ColumnProblemResolver.validateInput(input);
        } catch (IllegalArgumentException invalid) {
            return terminalFailure(V3SolverFailureCode.INVALID_INPUT, invalid.getMessage(),
                    "initialization/admission", List.of());
        }
        // Request-only, so it must be answered the same way on both routes and before any candidate is built.
        V3ColumnOutcome.Failure unsuppliedDraws = V3Admission.liquidSupplyScreen(input, liquidSupplyScreenRatio);
        if (unsuppliedDraws != null) return unsuppliedDraws;
        V3ColumnOutcome.Failure coolingFailure = V3Admission.staticCoolingAdmission(input);
        if (coolingFailure != null) return coolingFailure;
        // The learned route spends at most options.budgetPolls() control polls (owner decision D2, batch
        // 2026-11-05-neural-retrain): a counter, so the same request takes the same route on any machine.
        java.util.concurrent.atomic.AtomicLong neuralPolls = new java.util.concurrent.atomic.AtomicLong();
        NeuralProgress progress = new NeuralProgress(trace);
        V3SolveControl neuralControl = () -> {
            control.checkpoint(); // Whole-request cancellation always wins over the local neural budget.
            if (neuralPolls.incrementAndGet() > options.budgetPolls())
                throw new NeuralBudgetExceeded();
        };
        String reason = "model unavailable or outside coverage";
        V3ColumnOutcome.Success selected = null;
        V3NeuralSeed selectedProfile = null;
        V3ColumnOutcome.Failure lastFailure = null;
        int candidatesTried = 0;
        model=model.bind(com.wormzjl.icheme.science.material.MaterialRuntime.current(),input);
        String modelId = model.modelId();
        if (modelId == null || !modelId.matches("[A-Za-z0-9._:/-]{1,96}")) modelId = "invalid-id";
        try {
            neuralControl.checkpoint();
            List<V3NeuralSeed> predictions = model.candidates(input, neuralControl);
            neuralControl.checkpoint();
            for (V3NeuralSeed prediction : predictions) {
                candidatesTried++;
                V3NeuralSeed[] acceptedCandidate = {null};
                V3ColumnOutcome outcome;
                try {
                    outcome = correctNeuralSeed(input, prediction, options, neuralControl,
                            new V3SolvePolicy(cutoff, cutoff, closure,
                                    observer == null ? null : seed -> acceptedCandidate[0] = seed, progress));
                } catch (V3ThermoException | IllegalArgumentException | IllegalStateException rejected) {
                    reason = "neural seed rejected: " + rejected.getClass().getSimpleName();
                    continue;
                }
                neuralControl.checkpoint();
                if (outcome instanceof V3ColumnOutcome.Success success) {
                    boolean advisory = success.result().acceptanceAudit().checks().stream()
                            .anyMatch(check -> check.hasFamily(V3AuditFamily.WATER_DEW_POINT) && check.value() > check.limit());
                    if (selected == null || !advisory) {
                        selected = success;
                        selectedProfile = acceptedCandidate[0];
                    }
                    // Prefer a qualified equilibrium state if another neural phase candidate can supply it.
                    // The existing accepted-advisory policy is retained when no such candidate succeeds.
                    if (!advisory) break;
                    continue;
                }
                V3ColumnOutcome.Failure failure = (V3ColumnOutcome.Failure) outcome;
                lastFailure = failure;
                // Both codes are properties of the request, so no later candidate and no classical backup can
                // change them: PROPERTY_OUT_OF_RANGE is the resolved pressure profile, and since the two
                // state-dependent heat bounds became PathDependentHeatBound, INFEASIBLE_SPECIFICATION is only
                // ever raised by the request-only gates. A path-dependent verdict must not stop the search.
                if (failure.code() == V3SolverFailureCode.PROPERTY_OUT_OF_RANGE
                        || failure.code() == V3SolverFailureCode.INFEASIBLE_SPECIFICATION) break;
                reason = "neural correction " + failure.code();
            }
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (NeuralBudgetExceeded exhausted) {
            // The budget throws out of whatever the correction was doing, so what that attempt had already
            // measured is published here instead of being discarded with the stack. Nothing about the budget,
            // the attempt or the outcome changes; only the evidence attached to the failure does.
            reason = "neural budget exhausted; " + progress.summary();
        } catch (V3ThermoException | IllegalArgumentException | IllegalStateException rejected) {
            reason = "neural seed rejected: " + rejected.getClass().getSimpleName();
        }
        control.checkpoint();
        if (selected != null) {
            if (observer != null) observer.accept(Objects.requireNonNull(selectedProfile));
            return initializationEvent(selected, "initializer=LNN; model=" + modelId + "; wet=" + options.wetStart()
                    + "; mode=" + options.mode() + "; candidates=" + candidatesTried
                    + "; neuralPolls=" + neuralPolls.get());
        }
        if (lastFailure != null && (lastFailure.code() == V3SolverFailureCode.PROPERTY_OUT_OF_RANGE
                || lastFailure.code() == V3SolverFailureCode.INFEASIBLE_SPECIFICATION)) return lastFailure;
        String event = "initializer=" + (options.mode() == V3InitializationOptions.Mode.LNN_ONLY ? "LNN_FAILED" : "CURRENT_BACKUP")
                + "; model=" + modelId + "; neuralPolls=" + neuralPolls.get() + "/" + options.budgetPolls()
                + "; " + reason;
        if (options.mode() == V3InitializationOptions.Mode.LNN_ONLY)
            return initializationEvent(lastFailure != null ? lastFailure
                    : neuralFailure(reason, progress), event);
        // One clean backup from the original input; no failed seed state or neural control escapes into it.
        return initializationEvent(observer == null
                ? V3ColumnCalculator.calculate(input, control, cutoff, closureFraction, liquidSupplyScreenRatio)
                : V3ColumnCalculator.calculateWithAcceptedProfile(input, control, observer, liquidSupplyScreenRatio), event);
    }

    private static V3ColumnOutcome correctNeuralSeed(V3ColumnInput input, V3NeuralSeed seed,
            V3InitializationOptions options, V3SolveControl control, V3SolvePolicy policy) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
        if (!thermo.datasetRevision().equals(seed.propertyRevision()))
            throw new IllegalArgumentException("Seed property revision mismatch");
        V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input, seed.branch());
        if (V3OperatingDomainValidator.assess(problem, thermo) instanceof V3OperatingDomainValidator.Assessment.Rejected)
            return terminalFailure(V3SolverFailureCode.PROPERTY_OUT_OF_RANGE, "Requested pressure outside property coverage",
                    "initialization/admission", thermo.advisoryEvidence());
        V3DryMeshState state = seed.stateFor(problem);
        V3WetTraySet wet = seed.wetSetFor(problem, options.wetStart());
        boolean fixedWaterRefined = false;
        if (wet.hasWetTrays()) {
            // Near an aqueous phase boundary, releasing the weakly determined water coordinate before
            // correcting the energy profile gives enormous Newton steps. Hold only the predicted water
            // during this warm prepass, then release it for the unchanged final wet-system certificate.
            PreparedAttempt prepared = V3ColumnCalculator.prepareAttempt(problem, thermo, state, policy, wet, false);
            boolean[] mask = new boolean[problem.topology().nodeCount()];
            for (int tray : wet.wetTrays()) mask[tray] = true;
            V3WetTraySet parametric = V3WetTraySet.parametric(problem.topology(), mask,
                    V3ColumnInitializer.freeWaterFlows(prepared.seed()));
            V3ColumnProblem warm = V3ColumnProblemResolver.withTruncation(problem, prepared.support(), parametric);
            var flash = V3ColumnCalculator.feedFlash(problem, thermo, control, policy);
            var evaluator = new V3MeshResidualEvaluator(warm, thermo, flash.molarEnthalpyJoulesPerMol());
            var attempt = V3SimultaneousColumnSolver.solveWithContinuationLocalBlocks(warm, evaluator,
                    new V3DryMeshCoordinateMap(warm), parametric.seed(warm, prepared.seed()), thermo::newWorkspace,
                    options.maximumIterations(), policy.closureTolerance(), control, policy.trace());
            if (attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged converged) {
                state = converged.state();
                fixedWaterRefined = true;
            }
        }
        V3SolvePass pass = V3ColumnCalculator.solveSingleProblem(problem, thermo, state, control, "lnn/requested-state",
                V3ColumnCalculator.ContinuationJacobianPolicy.STAGE_LOCAL_BLOCKS, options.maximumIterations(), policy,
                neuralRungBudget(options), wet, neuralExtension(options));
        List<String> events = fixedWaterRefined ? mergedEvents(List.of(
                "wet seed refinement: fixed-water warm pass; final certificate releases every wet coordinate"), pass.solverEvents())
                : pass.solverEvents();
        if (V3ColumnCalculator.publishesSuccess(pass.attempt(), pass.audit()) && pass.reachedRequestedProblem()
                && V3ColumnCalculator.publishesRequestedGeometry(pass, input)) {
            return V3HydraulicPublish.publishAccepted(input, pass, thermo, policy, control, pass.solvePath(), events);
        }
        return new V3ColumnOutcome.Failure(V3SolverFailureCode.INITIALIZATION_FAILURE,
                "Learned seed did not reach an audited requested solution",
                V3ColumnCalculator.diagnostics(pass.attempt(), pass.audit(), pass.solvePath(), events, policy));
    }

    /**
     * The rung budget of the learned correction: the default cascade, plus the progress rule's early stop.
     *
     * <p>The stall stop is the one the steam rungs already carry; nothing else changes: every damping step,
     * the gradient fallback and the verification cascade stay, because a correction that reaches tolerance
     * still has to publish a certificate.</p>
     */
    private static V3SimultaneousColumnSolver.RungBudget neuralRungBudget(V3InitializationOptions options) {
        V3InitializationOptions.Correction correction = options.correction();
        V3SimultaneousColumnSolver.RungBudget budget = V3SimultaneousColumnSolver.RungBudget.DEFAULT;
        return new V3SimultaneousColumnSolver.RungBudget(budget.maximumDampingSteps(), budget.gradientFallback(),
                budget.verificationDampingSteps(), correction.stallWindow(), correction.stallFactor(),
                correction.stallResidualFloor());
    }

    /** The progress extension of the learned correction. */
    private static V3SimultaneousColumnSolver.Extension neuralExtension(V3InitializationOptions options) {
        V3InitializationOptions.Correction correction = options.correction();
        return new V3SimultaneousColumnSolver.Extension(correction.extensionBlock(),
                correction.extensionMaximumIterations(), correction.contractionWindow(),
                correction.contractionFactor());
    }

    private static V3ColumnOutcome initializationEvent(V3ColumnOutcome outcome, String event) {
        V3SolverDiagnostics old = outcome.diagnostics();
        var diagnostics = new V3SolverDiagnostics(old.initializerIterations(), old.newtonIterations(),
                old.residualEvaluations(), old.linearSolves(), old.maximumScaledResidual(), old.finalStepNorm(),
                old.solvePath(), mergedEvents(List.of(event.substring(0, Math.min(256, event.length()))), old.events()),
                old.acceptanceAudit(), old.convergenceEvidence(), old.closureTolerance());
        if (outcome instanceof V3ColumnOutcome.Success success) return new V3ColumnOutcome.Success(success.result(), diagnostics);
        V3ColumnOutcome.Failure failure = (V3ColumnOutcome.Failure) outcome;
        return new V3ColumnOutcome.Failure(failure.code(), failure.summary(), diagnostics);
    }

    /** A learned pass that ended without a failure of its own still publishes what its attempts measured. */
    private static V3ColumnOutcome.Failure neuralFailure(String reason, NeuralProgress progress) {
        String summary = boundedSummary(reason);
        return new V3ColumnOutcome.Failure(V3SolverFailureCode.INITIALIZATION_FAILURE, summary,
                new V3SolverDiagnostics(0, progress.iterations(), 0, 0, progress.lastMaximumScaledResidual(), 0.0,
                        "initialization/lnn-only", List.of(boundedEvent(summary)), failedAudit(V3AuditFamily.UNAVAILABLE, summary),
                        V3ConvergenceEvidence.unavailable()));
    }
}
