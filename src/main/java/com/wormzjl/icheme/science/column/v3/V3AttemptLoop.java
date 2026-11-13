package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.column.v3.thermo.V3ThermoException;
import com.wormzjl.icheme.science.thermo.TraceTruncationPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;

import static com.wormzjl.icheme.science.column.v3.V3ColumnCalculator.MAXIMUM_NEWTON_ITERATIONS;
import static com.wormzjl.icheme.science.column.v3.V3ColumnCalculator.publishesSuccess;
import static com.wormzjl.icheme.science.column.v3.V3ColumnCalculator.withPriorSupportNotes;
import static com.wormzjl.icheme.science.column.v3.V3ContinuationLadder.hasCondenserPhaseMismatch;
import static com.wormzjl.icheme.science.column.v3.V3SolveReport.failedAudit;
import static com.wormzjl.icheme.science.column.v3.V3SolveReport.flashTraceEvent;
import static com.wormzjl.icheme.science.column.v3.V3SolveReport.mergedEvents;

/**
 * The attempt loop: one rung's single-problem solve (the feed flash; the prepared attempt with its floor support
 * and wet-tray set; the Newton attempt on the rung's Jacobian route; the fresh audit; the bounded refreshes of the
 * support and the wet set, with the free-water energy shift and continuation), the condenser phase correction that
 * follows a rung, and {@link #runRung}, the two in a row.
 *
 * <p>Moved out of {@link V3ColumnCalculator} unchanged (batch 2026-11-13-codebase-review, D-C4). The calculator,
 * {@link V3ContinuationLadder}, {@link V3FeatureRamp}, {@link V3HydraulicPublish} and {@link V3LearnedRoute} call
 * it. It reads back the calculator's {@code publishesSuccess}, {@code withPriorSupportNotes} and
 * {@code MAXIMUM_NEWTON_ITERATIONS}, and the ladder's {@code hasCondenserPhaseMismatch}.</p>
 */
final class V3AttemptLoop {
    private static final int CONDENSER_PHASE_CORRECTOR_MAXIMUM_ITERATIONS = 24;
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

    private V3AttemptLoop() {}

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

    static V3AcceptanceAudit audit(
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
}
