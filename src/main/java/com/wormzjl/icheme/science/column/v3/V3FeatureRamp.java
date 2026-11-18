package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static com.wormzjl.icheme.science.column.v3.V3AttemptLoop.feedFlash;
import static com.wormzjl.icheme.science.column.v3.V3AttemptLoop.prepareAttempt;
import static com.wormzjl.icheme.science.column.v3.V3AttemptLoop.runRung;
import static com.wormzjl.icheme.science.column.v3.V3ColumnCalculator.MAXIMUM_NEWTON_ITERATIONS;
import static com.wormzjl.icheme.science.column.v3.V3ColumnCalculator.publishesSuccess;
import static com.wormzjl.icheme.science.column.v3.V3ColumnCalculator.withPriorSupportNotes;
import static com.wormzjl.icheme.science.column.v3.V3ContinuationLadder.continuationSeed;
import static com.wormzjl.icheme.science.column.v3.V3ContinuationLadder.reboilerDutyWatts;
import static com.wormzjl.icheme.science.column.v3.V3ContinuationLadder.surrogateSteamDutyWatts;
import static com.wormzjl.icheme.science.column.v3.V3ContinuationLadder.withReboilerDuty;
import static com.wormzjl.icheme.science.column.v3.V3SolveReport.boundedEvent;
import static com.wormzjl.icheme.science.column.v3.V3SolveReport.mergedEvents;

/**
 * The feature ramp: from an accepted seed without draws or stage heat to the authored steam, stage heat and side
 * draws, in bounded rungs at the requested geometry (steam, then heat, then draws, or one coupled homotopy), with
 * the energy-shift prediction of a heat or steam rung, the heat bounds read off the continuation state, the
 * subdivision of a failed heat rung and the second, full-budget steam sweep.
 *
 * <p>Moved out of {@link V3ColumnCalculator} unchanged (batch 2026-11-13-codebase-review, D-C4). The calculator's
 * {@code calculateBranch} and {@link V3ContinuationLadder} call {@link #recoverWithDrawRamp}; each rung is one
 * {@code runRung}: a single-problem solve and its condenser phase correction. A heat bound stops the ramp with
 * {@link PathDependentHeatBound}.</p>
 */
final class V3FeatureRamp {
    private static final int HEAT_RAMP_STEPS = 4;
    /** Side-draw rungs once a stage heat is authored; a cooled zone needs finer withdrawal increments. */
    private static final int HEAT_BEARING_DRAW_RAMP_STEPS = 8;
    private static final int MAXIMUM_HEAT_SUBDIVISIONS = 4;
    private static final int MAXIMUM_HEAT_SUBDIVISIONS_PER_RUNG = 2;
    /** Steam-rung halvings the second, full-budget sweep of one feature ramp may spend. */
    private static final int MAXIMUM_STEAM_SUBDIVISIONS = 2;
    private static final int DRAW_RAMP_INTERMEDIATE_MAXIMUM_ITERATIONS = 40;
    private static final int DRAW_RAMP_REQUESTED_MAXIMUM_ITERATIONS = 32;
    /**
     * Newton budget of an intermediate steam rung.
     *
     * <p>An intermediate rung exists only to hand the next one a nearby state. When a steam rung cannot be
     * solved, the ramp keeps the last state, records its "stopped at" event and skips ahead to the requested
     * input, so everything that rung spends after it stops descending is spent for nothing. Measured on the
     * literature preset, the one failing steam rung ran 41 iterations while its maximum scaled residual crawled
     * from 0.058 to 0.042, with 786 line-search trials and four entries into the damped normal-equation cascade
     * at doubled bandwidth: 2.6 s of a 13.0 s solve for a state the ramp discards.</p>
     *
     * <p>So an intermediate steam rung stops as soon as its residual has not halved over twelve iterations (the
     * solver's one stall rule, {@link V3SimultaneousColumnSolver#STALL_WINDOW_ITERATIONS}) —
     * well outside the quadratic tail, and floored at 1e-3 so a rung merely closing slowly is never cut off —
     * and keeps only the first two dampings of the cascade with no gradient fallback and no certificate cascade
     * behind them.</p>
     *
     * <p>Only steam rungs. A heat rung's failure is not absorbed the same way: it is answered by halving the
     * heat increment ({@code midpointHeatStep}), a recovery whose seed is the failed state and whose whole
     * point is that the rung got as far as it did. Measured on the 40 MW return-tray case, whose heat rungs
     * descend slowly and unevenly — 0.048 to 0.140 and back to 0.077 over twelve iterations — stopping them
     * early turns an accepted solve into a NONCONVERGENCE, and on a three-draw cooled crude case it moves
     * the published state. A draw rung has no retry of its own but shares the heat rungs' slow descent, and
     * neither pays: on the wet preset the steam ramp is what the continuation spends its time in.</p>
     *
     * <p>The requested rung, every stage rung, every recovery, the condenser-phase correction and the
     * free-water continuation all keep {@link V3SimultaneousColumnSolver.RungBudget#DEFAULT}.</p>
     */
    private static final V3SimultaneousColumnSolver.RungBudget INTERMEDIATE_RUNG_BUDGET =
            new V3SimultaneousColumnSolver.RungBudget(2, false, 0, V3SimultaneousColumnSolver.STALL_WINDOW_ITERATIONS,
                    V3SimultaneousColumnSolver.STALL_FACTOR, 1.0e-3);

    private V3FeatureRamp() {}

    /** One immutable authored-feature continuation point. */
    private record RampStep(
            double steamFraction, double heatFraction, double drawFraction, double labelFraction,
            V3RampKind kind, String description) {
        private RampStep {
            if (!Double.isFinite(steamFraction) || steamFraction < 0.0 || steamFraction > 1.0
                    || !Double.isFinite(heatFraction) || heatFraction < 0.0 || heatFraction > 1.0
                    || !Double.isFinite(drawFraction) || drawFraction < 0.0 || drawFraction > 1.0
                    || !Double.isFinite(labelFraction) || labelFraction < 0.0 || labelFraction > 1.0) {
                throw new IllegalArgumentException("V3 continuation ramp fractions must be finite and within zero to one");
            }
            kind = Objects.requireNonNull(kind, "kind");
            description = Objects.requireNonNull(description, "description");
        }

        /** Heat-free continuation point; the label fraction preserves the historical path strings. */
        private static RampStep legacy(double steamFraction, double drawFraction, V3RampKind kind, String description) {
            return new RampStep(steamFraction, 0.0, drawFraction,
                    Math.max(steamFraction, drawFraction), kind, description);
        }

        /** The rung's label in the solve path and the energy-shift note. */
        String pathLabel() {
            return kind.text();
        }

        boolean requested(V3ColumnInput input) {
            return (input.steamFeeds().isEmpty() || steamFraction == 1.0)
                    && (input.pumparounds().isEmpty() || heatFraction == 1.0)
                    && (input.sideDraws().isEmpty() || drawFraction == 1.0);
        }

        boolean heatRung() {
            return kind == V3RampKind.HEAT;
        }

        boolean steamRung() {
            return kind == V3RampKind.STEAM || kind == V3RampKind.WET;
        }

        double progress() {
            return labelFraction;
        }
    }

    /**
     * Bounded authored-parameter ramp from a dry surrogate seed to liquid draws and free-water steam; {@code coupled}
     * runs the one 16-rung coupled homotopy instead of the sequential schedule.
     */
    static V3SolvePass recoverWithDrawRamp(
            V3ColumnProblem requested, V3PengRobinsonThermo thermo, V3SolvePass seedBase,
            V3SolveControl control, V3SolvePolicy policy, CondenserAttempts condenserAttempts, boolean coupled) {
        V3ColumnInput input = requested.input();
        seedBase = Objects.requireNonNull(seedBase, "seedBase");
        if (!publishesSuccess(seedBase.attempt(), seedBase.audit())
                || seedBase.prepared().problem().hasSideDraws()
                || (seedBase.prepared().problem().hasSteamFeeds()
                        && !seedBase.prepared().problem().input().steamFeeds().equals(input.steamFeeds()))
                || seedBase.prepared().problem().hasPumparounds()) {
            throw new IllegalArgumentException("V3 feature ramp requires an accepted no-draw, no-pumparound seed with matching steam");
        }
        String rampPath = seedBase.solvePath();
        V3SolvePass previous = seedBase;
        CondenserAttempts rampAttempts = new CondenserAttempts();
        List<String> rampEvents = new ArrayList<>();
        boolean intermediateFailed = false;
        // The first fixed-geometry handoff uses the material projection. Later parameter rungs retain the
        // accepted state directly; reprojecting every rung was measured to cause residual stagnation.
        double totalSteamMolPerSecond = input.steamFeeds().stream()
                .mapToDouble(V3SteamFeedSpec::molarFlowMolPerSecond).sum();
        // Water is a known profile, so a rung adds its whole increment to the VLE dilution term and the
        // condenser split at once: aim for 4 mol/s per rung. The count is capped so a large stripping rate
        // cannot make the ramp unbounded, and at the cap the physical increment grows again. Twelve rungs
        // put 27.8 mol/s on the first rung of a 1200 kmol/h sump-steam column and diverged there whenever
        // the authored reboiler duty was small enough that the boilup surrogate dominated it; twenty-four
        // rungs halve that increment and converge, at about three seconds of extra continuation. This is the
        // resolution of the schedule rather than its rung count: see steamRampFractions, whose first rung is
        // this increment and whose later rungs double the water already placed.
        int steamRampSteps = Math.max(4, Math.min(24, (int) Math.ceil(totalSteamMolPerSecond / 4.0)));
        List<RampStep> rampSteps = new ArrayList<>(rampSteps(input, steamRampSteps));
        if (policy.fastPublication()) rampSteps = new ArrayList<>(fastRampSteps(input, rampSteps));
        boolean steamAnchored = !input.steamFeeds().isEmpty()
                && seedBase.prepared().problem().input().steamFeeds().equals(input.steamFeeds());
        if (coupled) {
            // A different bounded homotopy from the same freshly accepted dry state. This
            // avoids an intermediate steam-only or heat-only state becoming a path barrier.
            rampSteps.clear();
            for(int step=1;step<=16;step++) {
                double fraction=step/16.0;
                rampSteps.add(new RampStep(input.steamFeeds().isEmpty()?0:steamAnchored?1.0:fraction,
                        input.pumparounds().isEmpty()?0:fraction,input.sideDraws().isEmpty()?0:fraction,
                        fraction,V3RampKind.COUPLED,"coupled feature ramp"));
            }
        }
        HeatSubdivisions subdivisions = new HeatSubdivisions();
        EnergyShiftLog energyShift = new EnergyShiftLog();
        // A dry surrogate starts without heat or steam. A steam-anchored seed already carries the exact
        // authored water source and only needs the side cuts and cooling ramped in.
        double seedHeatFraction = 0.0;
        double seedSteamFraction = steamAnchored ? 1.0 : 0.0;
        boolean seedAccepted = true;
        double[] rungFeedEnthalpy = {Double.NaN, Double.NaN};
        double acceptedHeatFraction = 0.0;
        boolean condenserBoundChecked = false;
        // The bounded second sweep: the steam rung whose reduced budget stopped the schedule, the last state
        // the schedule accepted before it, the fractions that state belongs to, and whether the sweep has
        // already been spent. See the block that arms it at the requested rung.
        RampStep stoppedSteamStep = null;
        V3SolvePass stoppedSteamAccepted = null;
        double resumeHeatFraction = 0.0;
        double resumeSteamFraction = 0.0;
        double resumeAcceptedHeatFraction = 0.0;
        boolean fullBudgetSteamRungs = false;
        int steamSubdivisions = 0;
        for (int index = 0; index < rampSteps.size(); index++) {
            RampStep rampStep = rampSteps.get(index);
            if (intermediateFailed && !rampStep.requested(input)) continue;
            control.checkpoint();
            // Q_cond0 is the condenser duty of the same column without stage heat, which on a wet column
            // includes the water-vapor slip and the decanted free water. The dry surrogate seed carries a
            // replacement boilup and no condenser water at all, so the bound is taken from the last
            // accepted heat-free rung: for a steam-free input that is still the seed itself.
            if (!coupled && !condenserBoundChecked && rampStep.heatFraction() > 0.0
                    && publishesSuccess(previous.attempt(), previous.audit())) {
                condenserBoundChecked = true;
                requireCoolingBelowBaseCondenserDuty(input, thermo, previous);
            }
            if (rampStep.heatRung()) {
                int cappedTray = condensationCappedTray(previous, thermo, input, rampStep.heatFraction());
                if (cappedTray > 0) {
                    RampStep midpoint = midpointHeatStep(rampStep, acceptedHeatFraction);
                    if (midpoint != null && subdivisions.allows(rampStep.heatFraction())) {
                        subdivisions.record(rampStep.heatFraction());
                        rampEvents.add(boundedEvent("stage-heat ramp subdivided at " + rampStep.heatFraction()
                                + ": tray " + (cappedTray + 1) + " condensation cap"));
                        rampSteps.add(index, midpoint);
                        index--;
                        continue;
                    }
                    throw new PathDependentHeatBound(V3HeatFeasibility.condensationCapDetail(cappedTray,
                            condensationCapacityWatts(previous, thermo, cappedTray),
                            rampStep.heatFraction() * trayDutyWatts(input, cappedTray)),
                            "condensation-capped at tray " + (cappedTray + 1) + " on the continuation path",
                            V3ColumnOutcome.CONDENSATION_CAP_PATH + input.pumparounds().size());
                }
            }
            List<V3SideDrawSpec> draws = rampStep.drawFraction() == 0.0 ? List.of() : input.sideDraws().stream()
                    .map(draw -> new V3SideDrawSpec(draw.trayNumber(),
                            rampStep.drawFraction() * draw.molarFlowMolPerSecond())).toList();
            List<V3SteamFeedSpec> steam = rampStep.steamFraction() == 0.0 ? List.of() : input.steamFeeds().stream()
                    .map(feed -> new V3SteamFeedSpec(feed.stageNumber(),
                            rampStep.steamFraction() * feed.molarFlowMolPerSecond(),
                            feed.temperatureKelvin())).toList();
            List<V3PumparoundSpec> heat = V3PumparoundSpec.scaled(input, rampStep.heatFraction());
            double reboilerDuty = reboilerDutyWatts(input)
                    + (1.0 - rampStep.steamFraction()) * surrogateSteamDutyWatts(input);
            V3ColumnInput rampInput = new V3ColumnInput(input.schemaVersion(), input.packageId(), input.assayId(),
                    input.componentBasis(), input.feedComponentMolarFlowsMolPerSecond(), input.feedTemperatureKelvin(),
                    input.stageCount(), input.feedStageNumber(), input.topPressurePascal(), input.stagePressureDropPascal(),
                    withReboilerDuty(input, reboilerDuty), draws, steam, heat, input.columnDiameterMetres(),
                    // The water the drum returns ramps with the steam it is part of.
                    steam.isEmpty() || !input.returnsRefluxFreeWater() ? 0.0
                            : rampStep.steamFraction() == 1.0 ? input.refluxFreeWaterMolPerSecond()
                            : rampStep.steamFraction() * input.refluxFreeWaterMolPerSecond());
            V3CondenserPhaseBranch branch = previous.prepared().problem().topology().condenserPhaseBranch();
            V3ColumnProblem problem = V3ColumnProblemResolver.resolve(rampInput, branch);
            rampAttempts.recordAttempt(branch);
            V3DryMeshState seed = previous == seedBase
                    ? continuationSeed(problem, previous.prepared().problem().topology(),
                            previous.attempt().state(), thermo, control)
                    : previous.attempt().state();
            V3SolvePolicy rampPolicy = rampStep.requested(input) ? policy : policy.withoutCutoff();
            // A rung that changes the stage heat or the steam moves the whole temperature profile while the
            // flows barely move, and leaves every energy row short by the same amount: a long flat valley in
            // the merit that the line search crawls along. Predict that common shift before Newton starts.
            // A pure draw rung changes flows rather than heat and is left alone, and so is every continuation
            // grid, whose seed comes from a different geometry rather than from a different duty.
            //
            // The seed must also be an accepted state. The prediction linearises the energy rows around a
            // profile that satisfies its own rung with those flows; a state that failed its rung does not,
            // and its residual is not the duty increment the linearisation is solving for. Measured: the
            // 40-tray steam-plus-cooler column stalls its steam ramp at 5/12 and then jumps to the requested
            // input, and predicting a 40 K shift from that stalled state cost the case its convergence
            // (SUCCESS at 6.8e-14 became NONCONVERGENCE at 6.3e-7); skipping the prediction there keeps it.
            if (seedAccepted
                    && (rampStep.heatFraction() != seedHeatFraction || rampStep.steamFraction() != seedSteamFraction)) {
                seed = withEnergyShiftPrediction(problem, thermo, seed, control, rampPolicy, rampStep,
                        energyShift, rungFeedEnthalpy);
            }
            boolean requestedRung = rampStep.requested(input);
            int rungIterations = requestedRung
                    ? DRAW_RAMP_REQUESTED_MAXIMUM_ITERATIONS : DRAW_RAMP_INTERMEDIATE_MAXIMUM_ITERATIONS;
            V3SimultaneousColumnSolver.RungBudget budget =
                    rampRungBudget(rampStep.steamRung(), requestedRung, fullBudgetSteamRungs);
            V3SolvePass pass = runRung(problem, thermo, seed, control,
                    rampPath + (fullBudgetSteamRungs ? "/full-budget/" : "/") + rampStep.pathLabel()
                            + "-" + rampStep.progress(),
                    new RungOptions(ContinuationJacobianPolicy.STAGE_LOCAL_BLOCKS, rungIterations, budget,
                            V3SimultaneousColumnSolver.Extension.NONE), rampPolicy, rampAttempts);
            if (!publishesSuccess(pass.attempt(), pass.audit())) {
                if (requestedRung) {
                    // A reduced intermediate budget is a bet that a steam rung either closes cheaply or
                    // cannot be closed at all. When the bet loses, the ramp abandons its schedule and hands
                    // this rung whatever state the stopped rung left — a different seed, and therefore a
                    // different basin. Measured on the wet literature CDU, three perturbations (top 200 kPa,
                    // steam x0.5, steam x1.5) are lost exactly this way, one of them skipping ahead from a
                    // *lower* residual than the full budget ever reaches on that rung, so the residual level
                    // is not what decides; the seed is. Spending the declined fallbacks eagerly recovers all
                    // three, but it also costs the flagship preset 1.7 s -> 3.0 s for the same digest, because
                    // its own schedule stalls at 0.625 and skips ahead to an answer anyway.
                    //
                    // So the second sweep is bought only once the first one has failed where it matters: at
                    // the rung that publishes. Resume from the last state the schedule accepted, replay from
                    // the rung that stopped with the full damped cascade, the gradient direction and no stall
                    // stop, and let the schedule continue if that rung now closes. A ramp whose requested rung
                    // succeeds never reaches here and is bit-identical; a ramp that would have failed pays one
                    // extra sweep, once.
                    int resumeIndex = stoppedSteamStep == null ? -1 : rampSteps.indexOf(stoppedSteamStep);
                    if (resumeIndex >= 0 && !fullBudgetSteamRungs) {
                        fullBudgetSteamRungs = true;
                        rampEvents.add(boundedEvent(rampStep.description()
                                + " failed at the requested input: " + rampEvidence(pass)
                                + "; resuming the steam schedule at " + stoppedSteamStep.progress()
                                + " on the full rung budget"));
                        previous = stoppedSteamAccepted;
                        seedHeatFraction = resumeHeatFraction;
                        seedSteamFraction = resumeSteamFraction;
                        acceptedHeatFraction = resumeAcceptedHeatFraction;
                        seedAccepted = true;
                        intermediateFailed = false;
                        index = resumeIndex - 1;
                        continue;
                    }
                    // Preserve the ordinary ramp and its successful basins exactly. Only a request
                    // that exhausted both schedules gets a bounded fresh correction of its terminal
                    // state; no acceptance limit, physical specification or previous label is changed.
                    V3SolvePass retry = runRung(problem, thermo, pass.recoverySeed(), control,
                            rampPath + "/request-retry", RungOptions.of(ContinuationJacobianPolicy.STAGE_LOCAL_BLOCKS,
                                    MAXIMUM_NEWTON_ITERATIONS - DRAW_RAMP_REQUESTED_MAXIMUM_ITERATIONS),
                            rampPolicy, rampAttempts);
                    rampEvents.add(boundedEvent("requested-state correction retry: " + rampEvidence(retry)));
                    if (publishesSuccess(retry.attempt(), retry.audit()))
                        return withPriorSupportNotes(seedBase, withRampEvents(retry, energyShift.merged(rampEvents)));
                    if (!coupled) {
                        V3SolvePass alternative=recoverWithDrawRamp(requested,thermo,seedBase,control,
                                policy,condenserAttempts,true);
                        if(publishesSuccess(alternative.attempt(),alternative.audit()))
                            return withPriorSupportNotes(seedBase,withRampEvents(alternative,
                                    List.of("sequential feature path failed; accepted bounded coupled continuation")));
                    }
                    condenserAttempts.recordRequestedDrawRampFailure();
                    rampEvents.add(boundedEvent(
                            rampStep.description() + " reached the requested input and failed: " + rampEvidence(pass)));
                    V3SolvePass annotated = withRampEvents(pass, energyShift.merged(rampEvents));
                    return withPriorSupportNotes(seedBase, annotated);
                }
                String event = rampStep.description() + " stopped at " + rampStep.progress() + ": " + rampEvidence(pass)
                        + "; failed checks=" + pass.audit().checks().stream().filter(check -> !check.passed())
                        .map(V3AcceptanceAudit.Check::family).toList();
                rampEvents.add(boundedEvent(event));
                // A failed steam rung is not subdivided the way a heat rung is. Measured on the literature
                // preset: halving the failed 0.625 increment to 0.458 stalls again for another 1.2 s, and the
                // requested rung then costs 3.6 s from that state instead of 2.5 s from the 0.625 one — the
                // same accepted solution and digest, but two extra attempts, 26 extra Newton iterations and a
                // published iteration count of 4 rather than 3. The water a stalled rung could not place is
                // not placed any better by asking for half of it.
                RampStep midpoint = rampStep.heatRung() ? midpointHeatStep(rampStep, acceptedHeatFraction) : null;
                if (midpoint != null && subdivisions.allows(rampStep.heatFraction())) {
                    // The last accepted state is still the better seed; retry the smaller heat increment.
                    subdivisions.record(rampStep.heatFraction());
                    rampSteps.add(index, midpoint);
                    index--;
                    continue;
                }
                // Inside the second sweep the calculus above is different, because the alternative is no
                // longer a cheap skip-ahead to an answer: the requested rung has already been tried from the
                // skipped-ahead state and failed. A stopped rung that the full budget cannot close either is
                // then worth halving, at the cost of a rung the first sweep never pays for. Bounded to
                // {@link #MAXIMUM_STEAM_SUBDIVISIONS} halvings so a schedule that stalls at every fraction
                // cannot walk the interval.
                if (fullBudgetSteamRungs && rampStep.steamRung()
                        && steamSubdivisions < MAXIMUM_STEAM_SUBDIVISIONS) {
                    RampStep steamMidpoint = midpointSteamStep(rampStep, seedSteamFraction);
                    if (steamMidpoint != null) {
                        steamSubdivisions++;
                        rampSteps.add(index, steamMidpoint);
                        index--;
                        continue;
                    }
                }
                // Remember where a reduced budget gave up, and on what state, so the requested rung can buy a
                // second sweep from here if it needs one. Only the first such rung is remembered: it is the
                // one whose seed the whole rest of the schedule depends on.
                if (stoppedSteamStep == null && seedAccepted
                        && !budget.equals(V3SimultaneousColumnSolver.RungBudget.DEFAULT)) {
                    stoppedSteamStep = rampStep;
                    stoppedSteamAccepted = previous;
                    resumeHeatFraction = seedHeatFraction;
                    resumeSteamFraction = seedSteamFraction;
                    resumeAcceptedHeatFraction = acceptedHeatFraction;
                }
                // A failed intermediate fraction is still a finite fixed-geometry seed. The authored
                // full-rate problem must be attempted before returning a terminal diagnostic.
                previous = pass;
                seedHeatFraction = rampStep.heatFraction();
                seedSteamFraction = rampStep.steamFraction();
                seedAccepted = false;
                intermediateFailed = true;
                continue;
            }
            previous = pass;
            seedHeatFraction = rampStep.heatFraction();
            seedSteamFraction = rampStep.steamFraction();
            seedAccepted = true;
            if (rampStep.heatRung()) acceptedHeatFraction = rampStep.heatFraction();
        }
        condenserAttempts.recordAttempt(previous.prepared().problem().topology().condenserPhaseBranch());
        condenserAttempts.finishPhaseCorrection(true);
        return withPriorSupportNotes(seedBase, withRampEvents(previous, energyShift.merged(rampEvents)));
    }

    /**
     * Applies the enthalpy-consistent temperature shift of one heat or steam rung to that rung's seed.
     *
     * <p>The prediction is measured on the pair the attempt will actually solve — the truncation support the
     * rung's own policy derives, and the seed projected onto it — because a state whose truncated points hold
     * exact zeros cannot be evaluated against an identity-support problem. Only the temperature vector is
     * carried back to the caller's seed: the flows are frozen through the whole prediction, so the shift is
     * the same for the projected state and for the untruncated one the attempt will prepare for itself.</p>
     */
    private static V3DryMeshState withEnergyShiftPrediction(
            V3ColumnProblem problem, V3PengRobinsonThermo thermo, V3DryMeshState seed, V3SolveControl control,
            V3SolvePolicy policy, RampStep rampStep, EnergyShiftLog log, double[] feedEnthalpyCache) {
        int slot = policy.attemptCutoff() > 0.0 ? 1 : 0;
        if (Double.isNaN(feedEnthalpyCache[slot])) {
            feedEnthalpyCache[slot] = feedFlash(problem, thermo, control, policy).referenceMolarEnthalpyJoulesPerMol();
        }
        PreparedAttempt prepared = prepareAttempt(problem, thermo, seed, policy);
        V3EnergyShiftPredictor.Prediction prediction = V3EnergyShiftPredictor.predict(prepared.problem(), thermo,
                feedEnthalpyCache[slot], prepared.seed(), thermo.newWorkspace(), control);
        log.record(rampStep, prediction);
        return prediction.applyTo(seed, problem.topology());
    }

    /** Bounded record of one ramp's energy-shift predictions, published as a single diagnostic event. */
    private static final class EnergyShiftLog {
        private int applied;
        private int declined;
        private double largestShiftKelvin;
        private String first = "";
        private String firstDeclined = "";

        void record(RampStep step, V3EnergyShiftPredictor.Prediction prediction) {
            if (!prediction.applied()) {
                declined++;
                if (firstDeclined.isEmpty()) firstDeclined = prediction.note();
                return;
            }
            applied++;
            largestShiftKelvin = Math.max(largestShiftKelvin, prediction.largestShiftKelvin());
            if (first.isEmpty()) {
                first = step.pathLabel() + "-" + step.progress() + " scaled energy "
                        + prediction.scaledEnergyBefore() + " -> " + prediction.scaledEnergyAfter();
            }
        }

        /**
         * The predictor's own line first, then the ramp's, bounded by the published event contract.
         *
         * <p>The bound is applied on both branches. A ramp that stops rungs, subdivides them and then resumes
         * its steam schedule on the full budget writes more lines than one that walks its schedule cleanly,
         * and {@link V3SolverDiagnostics} rejects a list longer than {@link V3SolverDiagnostics#MAX_EVENTS}
         * outright rather than truncating it for the caller.</p>
         */
        List<String> merged(List<String> rampEvents) {
            if (applied == 0 && declined == 0) {
                return rampEvents.size() <= V3SolverDiagnostics.MAX_EVENTS ? rampEvents
                        : List.copyOf(rampEvents.subList(0, V3SolverDiagnostics.MAX_EVENTS));
            }
            List<String> events = new ArrayList<>();
            events.add(boundedEvent("energy-shift predictor: applied=" + applied + ", declined=" + declined
                    + ", largest shift=" + largestShiftKelvin + " K"
                    + (first.isEmpty() ? "" : ", first " + first)
                    + (firstDeclined.isEmpty() ? "" : ", declined because " + firstDeclined)));
            for (String event : rampEvents) {
                if (events.size() >= V3SolverDiagnostics.MAX_EVENTS) break;
                events.add(event);
            }
            return events;
        }
    }

    /** Separates water/phase continuation from draw withdrawal when both authored features are present. */
    private static List<RampStep> rampSteps(V3ColumnInput input, int steamRampSteps) {
        if (!input.pumparounds().isEmpty()) return heatBearingRampSteps(input, steamRampSteps);
        boolean hasSteam = !input.steamFeeds().isEmpty();
        boolean hasDraws = !input.sideDraws().isEmpty();
        if (!hasSteam) {
            List<RampStep> draws = new ArrayList<>(4);
            for (int step = 1; step <= 4; step++) {
                draws.add(RampStep.legacy(0.0, step / 4.0, V3RampKind.DRAW, "side-draw ramp"));
            }
            return List.copyOf(draws);
        }
        List<Double> steamFractions = steamRampFractions(steamRampSteps);
        List<RampStep> steps = new ArrayList<>(steamFractions.size() + 4);
        V3RampKind kind = hasDraws ? V3RampKind.STEAM : V3RampKind.WET;
        String description = hasDraws ? "steam ramp" : "wet ramp";
        for (double fraction : steamFractions) {
            steps.add(RampStep.legacy(fraction, 0.0, kind, description));
        }
        if (hasDraws) {
            for (int step = 1; step <= 4; step++) {
                steps.add(RampStep.legacy(1.0, step / 4.0, V3RampKind.DRAW, "side-draw ramp"));
            }
        }
        return List.copyOf(steps);
    }

    /** Coarse homotopy used only by the interactive aggressive route. Every feature still has an authored final
     * rung; retaining the first rung preserves phase admission while dropping repeated midpoint corrections. */
    private static List<RampStep> fastRampSteps(V3ColumnInput input, List<RampStep> source) {
        List<RampStep> result = new ArrayList<>(source.size());
        V3RampKind previousKind = null;
        for (RampStep step : source) {
            boolean first = step.kind() != previousKind;
            boolean requested = step.requested(input);
            // One midpoint is useful when a large authored flow or duty crosses a phase boundary.
            double fraction = step.kind() == V3RampKind.DRAW ? step.drawFraction()
                    : step.kind() == V3RampKind.HEAT ? step.heatFraction() : step.steamFraction();
            boolean midpoint = Math.abs(fraction - 0.5) < 0.26 && fraction > 0.0 && fraction < 1.0;
            if (first || midpoint || requested) result.add(step);
            previousKind = step.kind();
        }
        return List.copyOf(result);
    }

    /**
     * Steam rung fractions: a doubling schedule at the resolution {@code steamRampSteps} sets.
     *
     * <p>The rungs are {@code (2^k - 1) / N} for {@code k = 1, 2, ...} while that is below one, then one exactly:
     * for {@code N = 24} the schedule is 1/24, 3/24, 7/24, 15/24, 1. The first rung is still the smallest
     * increment the equal schedule ever took — that is what {@code N} was measured for, and the first admission
     * of water to a dry column is the increment that decides whether the ramp starts at all — and every later
     * rung doubles the water already on the trays, which is the perturbation the previous solution can absorb.</p>
     *
     * <p>The equal schedule spent nine rungs walking to 37.5 % of the authored steam on the literature preset
     * before failing at 41.7 %, and each of those rungs converged in five Newton iterations: they were not
     * carrying the continuation, they were paying for it. The doubling schedule reaches the same water in four
     * rungs. Where a rung does fail, the ramp's existing rule keeps the last state and skips ahead to the
     * requested input, so a coarser schedule cannot lose a solution the equal one found — it can only hand the
     * final rung a different seed, which is measured.</p>
     *
     * @throws IllegalArgumentException if the resolution is not positive
     */
    static List<Double> steamRampFractions(int steamRampSteps) {
        if (steamRampSteps < 1) throw new IllegalArgumentException("V3 steam ramp resolution must be positive");
        List<Double> fractions = new ArrayList<>();
        for (long span = 1; span < steamRampSteps; span = 2 * span + 1) {
            fractions.add(span / (double) steamRampSteps);
        }
        fractions.add(1.0);
        return List.copyOf(fractions);
    }

    /**
     * Rung order for an authored stage heat: steam, then heat, then draws.
     *
     * <p>The surrogate boilup must be removed before anything else changes, and cooling above a draw tray
     * increases the liquid arriving there, which is what the draws need.</p>
     */
    private static List<RampStep> heatBearingRampSteps(V3ColumnInput input, int steamRampSteps) {
        boolean hasSteam = !input.steamFeeds().isEmpty();
        boolean hasDraws = !input.sideDraws().isEmpty();
        List<RampStep> steps = new ArrayList<>(steamRampSteps + 2 * HEAT_RAMP_STEPS);
        if (hasSteam) {
            for (double fraction : steamRampFractions(steamRampSteps)) {
                steps.add(new RampStep(fraction, 0.0, 0.0, fraction, V3RampKind.STEAM, "steam ramp"));
            }
        }
        for (int step = 1; step <= HEAT_RAMP_STEPS; step++) {
            double fraction = step / (double) HEAT_RAMP_STEPS;
            steps.add(new RampStep(hasSteam ? 1.0 : 0.0, fraction, 0.0, fraction, V3RampKind.HEAT, "stage-heat ramp"));
        }
        if (hasDraws) {
            // Cooling has already changed the liquid traffic arriving at every draw tray, so a quarter of the
            // authored withdrawal is a larger perturbation here than on a heat-free column. A 30-stage crude
            // column with sump steam, three pumparounds and the three preset draws stalls at 1.5e-2 on the
            // last of four rungs and converges on eight.
            for (int step = 1; step <= HEAT_BEARING_DRAW_RAMP_STEPS; step++) {
                double fraction = step / (double) HEAT_BEARING_DRAW_RAMP_STEPS;
                steps.add(new RampStep(hasSteam ? 1.0 : 0.0, 1.0, fraction, fraction, V3RampKind.DRAW, "side-draw ramp"));
            }
        }
        return List.copyOf(steps);
    }

    /**
     * The budget one ramp rung runs on: only an intermediate steam rung gets the reduced one.
     *
     * <p>The rung that reaches the authored input publishes the result, so it keeps every fallback and the full
     * final-Newton certificate cascade unchanged. See {@link #INTERMEDIATE_RUNG_BUDGET} for why heat and draw
     * rungs keep it too.</p>
     *
     * <p>Once the ramp has resumed on the full budget there is no reduced rung left anywhere in the schedule:
     * the whole point of the resumed sweep is that the fallbacks the first sweep declined are what it is
     * buying, so the flag wins over every other consideration.</p>
     */
    static V3SimultaneousColumnSolver.RungBudget rampRungBudget(
            boolean steamRung, boolean requested, boolean fullBudgetSweep) {
        if (fullBudgetSweep) return V3SimultaneousColumnSolver.RungBudget.DEFAULT;
        return steamRung && !requested ? INTERMEDIATE_RUNG_BUDGET : V3SimultaneousColumnSolver.RungBudget.DEFAULT;
    }

    /**
     * Halves the remaining steam increment; null when the increment can no longer be split.
     *
     * <p>Only the second, full-budget sweep uses this. On the first sweep the measured trade is the other way
     * round: halving a stopped 0.625 rung to 0.458 stalls again for another 1.2 s and then costs the requested
     * rung 3.6 s instead of 2.5 s for the same accepted answer, because the skip-ahead was going to succeed
     * anyway. Once the requested rung has failed from the skipped-ahead state, that comparison no longer
     * applies and the smaller increment is the only unexplored seed left.</p>
     */
    private static RampStep midpointSteamStep(RampStep failed, double acceptedSteamFraction) {
        double midpoint = steamRampMidpointFraction(acceptedSteamFraction, failed.steamFraction());
        if (Double.isNaN(midpoint)) return null;
        return new RampStep(midpoint, failed.heatFraction(), failed.drawFraction(), midpoint,
                failed.kind(), failed.description());
    }

    /**
     * The steam fraction halfway between an accepted rung and the rung that stopped, or NaN.
     *
     * <p>NaN is returned whenever the halving would not actually produce a new rung strictly inside the
     * interval — a zero-width interval, or one so narrow that the midpoint rounds onto an endpoint in double
     * arithmetic — which is what keeps the subdivision from re-queueing a fraction it has already solved.</p>
     */
    static double steamRampMidpointFraction(double acceptedSteamFraction, double failedSteamFraction) {
        double midpoint = 0.5 * (acceptedSteamFraction + failedSteamFraction);
        if (!(midpoint > acceptedSteamFraction) || !(midpoint < failedSteamFraction)) return Double.NaN;
        return midpoint;
    }

    /** Halves the remaining heat increment; null when the increment can no longer be split. */
    private static RampStep midpointHeatStep(RampStep failed, double acceptedHeatFraction) {
        double midpoint = 0.5 * (acceptedHeatFraction + failed.heatFraction());
        if (!(midpoint > acceptedHeatFraction) || !(midpoint < failed.heatFraction())) return null;
        return new RampStep(failed.steamFraction(), midpoint, failed.drawFraction(), midpoint,
                failed.kind(), failed.description());
    }

    /** Bounded midpoint budget: at most two subdivisions of one rung and four in a whole ramp. */
    private static final class HeatSubdivisions {
        private final java.util.Map<Double, Integer> perRung = new java.util.HashMap<>();
        private int total;

        boolean allows(double heatFraction) {
            return total < MAXIMUM_HEAT_SUBDIVISIONS
                    && perRung.getOrDefault(heatFraction, 0) < MAXIMUM_HEAT_SUBDIVISIONS_PER_RUNG;
        }

        void record(double heatFraction) {
            perRung.merge(heatFraction, 1, Integer::sum);
            total++;
        }
    }

    /**
     * Stops an authored cooling that is not below the base condenser duty of the same column without heat.
     *
     * <p>{@code Q_cond0} is recomputed from the last accepted heat-free state at the requested geometry, so
     * this is a state-based bound rather than a correlation. On a wet column that state already carries the
     * authored steam, so the duty includes the water-vapor slip and the free water leaving the drum.</p>
     *
     * <p>Because the bound is read off a continuation state it is path-dependent, and raises
     * {@link PathDependentHeatBound} — a typed {@code NONCONVERGENCE} with the bound as its hint — rather than
     * claiming the specification itself is infeasible.</p>
     *
     * <p>Like the static gate this compares the net authored heat: {@code Q_cond0} belongs to a column without
     * stage heat, so any authored heating is credited to it before the gross cooling is measured against it.</p>
     */
    private static void requireCoolingBelowBaseCondenserDuty(
            V3ColumnInput input, V3PengRobinsonThermo thermo, V3SolvePass heatFreeBase) {
        double cooling = -V3PumparoundSpec.totalCoolingWatts(input);
        if (!(cooling > 0.0)) return;
        double heating = V3HeatFeasibility.heatingCreditWatts(input);
        V3ColumnProblem base = heatFreeBase.prepared().problem();
        if (base.hasPumparounds()) {
            throw new IllegalStateException("V3 base condenser duty requires a heat-free continuation state");
        }
        V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(
                base, thermo, heatFreeBase.feedMolarEnthalpyJoulesPerMol());
        double baseCondenserDuty = V3ColumnDutyLedger.condenserDutyWatts(
                base, heatFreeBase.attempt().state(), evaluator, thermo.newWorkspace());
        if (!Double.isFinite(baseCondenserDuty) || cooling < Math.abs(baseCondenserDuty) + heating) return;
        throw new PathDependentHeatBound(
                V3HeatFeasibility.condenserBoundDetail(cooling, baseCondenserDuty, heating),
                "cooling above the base condenser duty measured on the continuation path",
                V3ColumnOutcome.CONDENSER_BOUND_PATH + input.pumparounds().size());
    }

    /** First tray whose arriving vapor cannot release the duty of the next heat rung; zero when none. */
    private static int condensationCappedTray(
            V3SolvePass accepted, V3PengRobinsonThermo thermo, V3ColumnInput input, double heatFraction) {
        V3ColumnProblem problem = accepted.prepared().problem();
        var workspace = thermo.newWorkspace();
        for (int tray = 1; tray <= Math.min(problem.topology().trayCount(), input.stageCount()); tray++) {
            double duty = heatFraction * trayDutyWatts(input, tray);
            if (duty >= 0.0) continue;
            double capacity = V3HeatFeasibility.condensationCapacityWatts(
                    problem, accepted.attempt().state(), thermo, workspace, tray);
            if (-duty > capacity) return tray;
        }
        return 0;
    }

    private static double condensationCapacityWatts(V3SolvePass accepted, V3PengRobinsonThermo thermo, int tray) {
        return V3HeatFeasibility.condensationCapacityWatts(accepted.prepared().problem(), accepted.attempt().state(),
                thermo, thermo.newWorkspace(), tray);
    }

    private static double trayDutyWatts(V3ColumnInput input, int trayNumber) {
        double duty = 0.0;
        for (V3PumparoundSpec pumparound : input.pumparounds()) duty += pumparound.trayDutyWatts(trayNumber);
        return duty;
    }

    private static V3SolvePass withRampEvents(V3SolvePass pass, List<String> events) {
        if (events.isEmpty()) return pass;
        return new V3SolvePass(pass.attempt(), pass.audit(), pass.feedMolarEnthalpyJoulesPerMol(), pass.solvePath(),
                pass.recoverySeed(), pass.terminalStageCount(), pass.reachedRequestedProblem(),
                pass.attemptedRequestedProblem(), pass.allowsFreshMaterialClosedFallback(),
                mergedEvents(events, pass.solverEvents()), pass.prepared());
    }

    private static String rampEvidence(V3SolvePass pass) {
        V3SimultaneousColumnSolver.Evidence evidence = pass.attempt().evidence();
        return evidence.termination() + ", iterations=" + evidence.iterations()
                + ", residual=" + evidence.maximumScaledResidual();
    }
}
