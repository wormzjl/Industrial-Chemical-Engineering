package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.column.v3.thermo.V3ThermoException;
import com.wormzjl.icheme.science.column.v3.thermo.V3WaterProperties;
import java.util.ArrayList;
import java.util.List;

import static com.wormzjl.icheme.science.column.v3.V3AttemptLoop.correctCondenserPhase;
import static com.wormzjl.icheme.science.column.v3.V3AttemptLoop.feedFlash;
import static com.wormzjl.icheme.science.column.v3.V3AttemptLoop.solveSingleProblem;
import static com.wormzjl.icheme.science.column.v3.V3ColumnCalculator.MAXIMUM_NEWTON_ITERATIONS;
import static com.wormzjl.icheme.science.column.v3.V3ColumnCalculator.publishesSuccess;
import static com.wormzjl.icheme.science.column.v3.V3ColumnCalculator.withPriorSupportNotes;
import static com.wormzjl.icheme.science.column.v3.V3FeatureRamp.recoverWithDrawRamp;
import static com.wormzjl.icheme.science.column.v3.V3SolveReport.boundedSummary;
import static com.wormzjl.icheme.science.column.v3.V3SolveReport.mergedEvents;

/**
 * The continuation ladder: the stage grids from a small column up to the request, the pressure legs down from
 * 150 kPa(a), the seed hand-off between grids (interpolation and the material/VLE projection), the recoveries of a
 * failed grid or leg, and the auxiliary inputs the ladder solves on the way (fewer stages, no draws, no stage heat,
 * a dry boilup surrogate for steam, another top pressure).
 *
 * <p>Moved out of {@link V3ColumnCalculator} unchanged (batch 2026-11-13-codebase-review, D-C4). The calculator's
 * {@code calculateBranch} calls the two continuations, the recoveries and the input transforms. Each grid and each
 * leg is one single-problem solve with its condenser phase correction, and a ladder whose request carries draws,
 * steam or stage heat hands its last accepted state to {@link V3FeatureRamp#recoverWithDrawRamp}.</p>
 */
final class V3ContinuationLadder {
    private static final double PRESSURE_CONTINUATION_TRIGGER_PASCAL = 100_000.0;
    private static final double PRESSURE_CONTINUATION_ANCHOR_PASCAL = 150_000.0;
    private static final double PRESSURE_CONTINUATION_STEP_PASCAL = 10_000.0;
    private static final double PRESSURE_CONTINUATION_FINE_STEP_PASCAL = 5_000.0;
    private static final double PRESSURE_CONTINUATION_FINE_STEP_FROM_PASCAL = 110_000.0;
    private static final int PRESSURE_CONTINUATION_CORRECTOR_MAXIMUM_ITERATIONS = 12;
    private static final int PRESSURE_CONTINUATION_RECOVERY_MAXIMUM_ITERATIONS = 24;

    private V3ContinuationLadder() {}

    static boolean requiresPressureContinuation(V3OperatingDomainValidator.Assessment admission) {
        return admission instanceof V3OperatingDomainValidator.Assessment.Eligible eligible
                && eligible.minimumNodePressurePascal() <= PRESSURE_CONTINUATION_TRIGGER_PASCAL;
    }

    /**
     * Runs a bounded Wang-Henke-style stage continuation entirely within this request.
     *
     * <p>Every lower-stage solve must pass the same simultaneous-MESH and fresh-audit gates before its profile may
     * seed the next grid. The intermediate states are local variables only: neither they nor their thermodynamic
     * workspaces are retained after this calculation returns.</p>
     */
    static V3SolvePass solveStageContinuation(
            V3ColumnInput input, V3PengRobinsonThermo thermo, V3SolveControl control,
            V3CondenserPhaseBranch condenserBranch, V3SolvePolicy policy, CondenserAttempts condenserAttempts,
            V3SolveStrategy strategy) {
        List<Integer> stageCounts = continuationStageCounts(input.stageCount(), strategy);
        String stagePath = continuationStagePath(stageCounts);
        V3DryMeshState previousState = null;
        V3SolvePass lastPass = null;
        boolean featureRampRequired = featureRampRequired(input);
        V3ColumnInput continuationInput = withoutPumparounds(
                input.steamFeeds().isEmpty() ? input : withoutSteamWithSurrogateDuty(input));
        V3ColumnTopology previousTopology = null;
        for (int stageCount : stageCounts) {
            control.checkpoint();
            V3ColumnInput stageInput = !featureRampRequired && stageCount == input.stageCount()
                    ? continuationInput : withStageGeometry(continuationInput, stageCount);
            V3CondenserPhaseBranch currentBranch = lastPass == null ? condenserBranch
                    : lastPass.prepared().problem().topology().condenserPhaseBranch();
            V3ColumnProblem stageProblem = V3ColumnProblemResolver.resolve(stageInput, currentBranch);
            V3DryMeshState seed = previousState == null
                    ? initializeForSolve(stageProblem, thermo, V3ColumnInitializer.Mode.SEQUENTIAL_MATERIAL_VLE)
                    : continuationSeed(stageProblem, previousTopology, previousState, thermo, control);
            V3SolvePass preceding = lastPass;
            lastPass = solveSingleProblem(stageProblem, thermo,
                    seed, control, "cold/stage-continuation/" + stagePath + "/fine-fd",
                    RungOptions.of(ContinuationJacobianPolicy.STAGE_LOCAL_BLOCKS, MAXIMUM_NEWTON_ITERATIONS), policy);
            if (preceding != null) lastPass = withPriorSupportNotes(preceding, lastPass);
            PhaseCorrection phaseCorrection = correctCondenserPhase(lastPass, thermo, control, policy, condenserAttempts);
            lastPass = phaseCorrection.pass();
            if (!publishesSuccess(lastPass.attempt(), lastPass.audit())) {
                if (!phaseCorrection.attempted() && !hasCondenserPhaseMismatch(lastPass)) {
                    lastPass = withPriorSupportNotes(lastPass,
                            recoverContinuationStage(originalProblem(lastPass), thermo, lastPass, control, stagePath, stageCount, policy));
                    phaseCorrection = correctCondenserPhase(lastPass, thermo, control, policy, condenserAttempts);
                    lastPass = phaseCorrection.pass();
                }
                if (!publishesSuccess(lastPass.attempt(), lastPass.audit())) {
                    return new V3SolvePass(lastPass.attempt(), lastPass.audit(), lastPass.feedMolarEnthalpyJoulesPerMol(),
                            "cold/stage-continuation/" + stagePath + "/failed-stage-" + stageCount,
                            lastPass.recoverySeed(), stageCount, false, stageCount == input.stageCount(),
                            stageCount == input.stageCount() && !phaseCorrection.attempted()
                                    && !hasCondenserPhaseMismatch(lastPass), lastPass.solverEvents(), lastPass.prepared());
                }
            }
            previousState = lastPass.attempt().state();
            previousTopology = lastPass.prepared().problem().topology();
        }
        if (lastPass == null) throw new IllegalStateException("V3 Stage continuation has no stage grid");
        if (featureRampRequired) {
            V3ColumnProblem requested = V3ColumnProblemResolver.resolve(input,
                    lastPass.prepared().problem().topology().condenserPhaseBranch());
            lastPass = recoverWithDrawRamp(requested, thermo, lastPass, control, policy, condenserAttempts, false);
            if (!publishesSuccess(lastPass.attempt(), lastPass.audit())) {
                return new V3SolvePass(lastPass.attempt(), lastPass.audit(), lastPass.feedMolarEnthalpyJoulesPerMol(),
                        "cold/stage-continuation/" + stagePath + "/failed-stage-" + input.stageCount(),
                        lastPass.recoverySeed(), input.stageCount(), false, true, false,
                        lastPass.solverEvents(), lastPass.prepared());
            }
        }
        return new V3SolvePass(lastPass.attempt(), lastPass.audit(), lastPass.feedMolarEnthalpyJoulesPerMol(),
                lastPass.solvePath(), lastPass.recoverySeed(), lastPass.terminalStageCount(), true, true, false,
                lastPass.solverEvents(), lastPass.prepared());
    }

    /**
     * Continues a qualified 150 kPa(a) cold solution downward in bounded request-local pressure steps.
     *
     * <p>Each leg solves the complete requested-stage MESH system and independently audits it before its state may
     * seed the next leg. No accepted state survives this calculation call.</p>
     */
    static V3SolvePass solvePressureContinuation(
            V3ColumnInput input, V3PengRobinsonThermo thermo, V3SolveControl control,
            V3CondenserPhaseBranch condenserBranch, V3SolvePolicy policy, CondenserAttempts condenserAttempts,
            V3SolveStrategy strategy) {
        if (input.topPressurePascal() > PRESSURE_CONTINUATION_TRIGGER_PASCAL) {
            throw new IllegalArgumentException("V3 pressure continuation was requested outside its low-pressure lane");
        }
        // A high-pressure auxiliary column can have a different phase topology from the vacuum request.
        // Give the authored pressure its own cold solve before asking that auxiliary problem for a seed.
        V3SolvePass direct = solveStageContinuation(input, thermo, control, condenserBranch, policy,
                condenserAttempts, strategy);
        if (publishesSuccess(direct.attempt(), direct.audit())) return direct;
        boolean steamRampRequired = !input.steamFeeds().isEmpty() || !input.pumparounds().isEmpty();
        V3ColumnInput continuationInput = steamRampRequired
                ? withoutPumparounds(withoutSideDraws(withoutSteamWithSurrogateDuty(input))) : input;
        boolean finePressureSteps = !input.sideDraws().isEmpty() || steamRampRequired;
        V3ColumnInput anchorInput = withTopPressure(continuationInput, PRESSURE_CONTINUATION_ANCHOR_PASCAL);
        V3SolvePass pass = solveStageContinuation(anchorInput, thermo, control, condenserBranch, policy,
                condenserAttempts, strategy);
        List<String> pressureEvents = new ArrayList<>();
        String pressurePath = continuationPressurePath(input.topPressurePascal(), finePressureSteps);
        if (!publishesSuccess(pass.attempt(), pass.audit())) {
            return new V3SolvePass(pass.attempt(), pass.audit(), pass.feedMolarEnthalpyJoulesPerMol(),
                    "cold/pressure-continuation/" + pressurePath + "/anchor-failed", pass.recoverySeed(),
                    pass.terminalStageCount(), false, false, false,
                    mergedEvents(pressureEvents, pass.solverEvents()), pass.prepared());
        }
        for (double pressure : continuationPressureSteps(input.topPressurePascal(), finePressureSteps)) {
            control.checkpoint();
            V3ColumnInput stepInput = withTopPressure(continuationInput, pressure);
            V3ColumnProblem stepProblem = V3ColumnProblemResolver.resolve(stepInput,
                    pass.prepared().problem().topology().condenserPhaseBranch());
            V3DryMeshState seed = pass.attempt().state();
            String stepPath = "cold/pressure-continuation/" + pressurePath + "/top-"
                    + Math.round(pressure / 1_000.0) + "kpa/fine-fd";
            pass = withPriorSupportNotes(pass, solveSingleProblem(stepProblem, thermo, seed, control, stepPath,
                    RungOptions.of(ContinuationJacobianPolicy.PRESSURE_LOCAL_PREDICTOR,
                            PRESSURE_CONTINUATION_CORRECTOR_MAXIMUM_ITERATIONS), policy));
            pressureEvents.add(pressureEvent(pressure, "predictor", pass));
            PhaseCorrection phaseCorrection = correctCondenserPhase(pass, thermo, control, policy, condenserAttempts);
            pass = phaseCorrection.pass();
            if (!publishesSuccess(pass.attempt(), pass.audit())) {
                if (!phaseCorrection.attempted() && !hasCondenserPhaseMismatch(pass)) {
                    pass = withPriorSupportNotes(pass, recoverWithBubblePointProjection(stepProblem, thermo, seed, control,
                            "cold/pressure-continuation/" + pressurePath + "/top-"
                                    + Math.round(pressure / 1_000.0) + "kpa/material-vle-recovery/fine-fd",
                            ContinuationJacobianPolicy.PRESSURE_LOCAL_PREDICTOR,
                            PRESSURE_CONTINUATION_RECOVERY_MAXIMUM_ITERATIONS, policy));
                    pressureEvents.add(pressureEvent(pressure, "Wang-Henke material/VLE recovery", pass));
                    pass = correctCondenserPhase(pass, thermo, control, policy, condenserAttempts).pass();
                }
                if (!publishesSuccess(pass.attempt(), pass.audit())) {
                    boolean attemptedRequestedProblem = Double.compare(pressure, input.topPressurePascal()) == 0;
                    return new V3SolvePass(pass.attempt(), pass.audit(), pass.feedMolarEnthalpyJoulesPerMol(),
                            "cold/pressure-continuation/" + pressurePath + "/failed-top-"
                                    + Math.round(pressure / 1_000.0) + "kpa",
                            pass.recoverySeed(), input.stageCount(), false, attemptedRequestedProblem, false,
                            mergedEvents(pressureEvents, pass.solverEvents()), pass.prepared());
                }
            }
        }
        if (steamRampRequired) {
            V3ColumnProblem requested = V3ColumnProblemResolver.resolve(input,
                    pass.prepared().problem().topology().condenserPhaseBranch());
            pass = recoverWithDrawRamp(requested, thermo, pass, control, policy, condenserAttempts, false);
            if (!publishesSuccess(pass.attempt(), pass.audit())) {
                return new V3SolvePass(pass.attempt(), pass.audit(), pass.feedMolarEnthalpyJoulesPerMol(),
                        "cold/pressure-continuation/" + pressurePath + "/wet-ramp-failed", pass.recoverySeed(),
                        input.stageCount(), false, true, false, mergedEvents(pressureEvents, pass.solverEvents()), pass.prepared());
            }
        }
        return new V3SolvePass(pass.attempt(), pass.audit(), pass.feedMolarEnthalpyJoulesPerMol(),
                pass.solvePath(), pass.recoverySeed(), input.stageCount(), true, true, false,
                mergedEvents(pressureEvents, pass.solverEvents()), pass.prepared());
    }

    /** Applies the material/VLE hand-off projection only in the qualified low-pressure operating region. */
    static V3DryMeshState continuationSeed(
            V3ColumnProblem targetProblem,
            V3ColumnTopology sourceTopology,
            V3DryMeshState previousState,
            V3PengRobinsonThermo thermo,
            V3SolveControl control) {
        V3DryMeshState interpolated = interpolate(previousState, sourceTopology, targetProblem);
        return projectedSeedOrPrevious(targetProblem, thermo, interpolated, control);
    }

    /**
     * Bounded component-material/bubble-point projection of a seed. The projection is a candidate for a later
     * rigorous MESH correction; a property-domain or invalid-state failure, or a state outside the log
     * coordinates, keeps the previous state.
     */
    static V3DryMeshState projectedSeedOrPrevious(
            V3ColumnProblem problem, V3PengRobinsonThermo thermo, V3DryMeshState previousState, V3SolveControl control) {
        var workspace = thermo.newWorkspace();
        control.checkpoint();
        V3DryMeshState projected;
        try {
            projected = V3ColumnInitializer.projectMaterialBalancesAtFixedTemperature(
                    problem, thermo, workspace, previousState);
            control.checkpoint();
        } catch (V3ThermoException | IllegalArgumentException failure) {
            return previousState;
        }
        if (!isLogCoordinateFeasible(problem, projected)) return previousState;
        try {
            double h = feedFlash(problem, thermo, control, V3SolvePolicy.OFF).referenceMolarEnthalpyJoulesPerMol();
            if (!new V3AcceptanceAuditor(problem, thermo, h).interiorPhaseIdentity(previousState, workspace, control).passed()) return previousState;
            if (problem.topology().dryTopTrayCount() == 0) return projected;
            V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, thermo, h);
            V3MeshResidual before = evaluator.evaluate(previousState, workspace);
            V3MeshResidual after = evaluator.evaluate(projected, workspace);
            double beforeMerit = 0.0, afterMerit = 0.0;
            double beforeEnergy = 0.0, afterEnergy = 0.0;
            for (int row = 0; row < before.size(); row++) {
                double scale = before.scale(row);
                double a = before.physicalValue(row) / scale;
                double b = after.physicalValue(row) / scale;
                beforeMerit += a * a;
                afterMerit += b * b;
                if (before.equation(row).family() == V3DegreeOfFreedomLedger.EquationFamily.ENERGY_BALANCE) {
                    beforeEnergy = Math.max(beforeEnergy, Math.abs(a));
                    afterEnergy = Math.max(afterEnergy, Math.abs(b));
                }
            }
            return afterMerit < beforeMerit && afterEnergy <= beforeEnergy ? projected : previousState;
        } catch (V3ThermoException | IllegalArgumentException invalidEvaluation) {
            return previousState;
        }
    }

    private static boolean isLogCoordinateFeasible(V3ColumnProblem problem, V3DryMeshState state) {
        for (int node = 0; node < state.nodeCount(); node++) {
            if (!Double.isFinite(state.temperatureKelvin(node)) || state.temperatureKelvin(node) <= 0.0) return false;
            for (int component = 0; component < state.componentCount(); component++) {
                boolean vaporPhase = problem.hasVaporUnknown(node, component);
                if (vaporPhase && (!Double.isFinite(state.vaporFlow(node, component)) || state.vaporFlow(node, component) <= 0.0)) {
                    return false;
                }
                if (!vaporPhase && state.vaporFlow(node, component) != 0.0) return false;
                boolean liquidPhase = problem.hasLiquidUnknown(node, component);
                if (liquidPhase && (!Double.isFinite(state.liquidFlow(node, component))
                        || state.liquidFlow(node, component) <= 0.0)) {
                    return false;
                }
                if (!liquidPhase && state.liquidFlow(node, component) != 0.0) return false;
            }
        }
        return true;
    }

    /**
     * Re-establishes component material closure after a failed auxiliary continuation solve, then retries MESH.
     *
     * <p>This mirrors the sequential material/VLE phase of a Wang-Henke solve. It is deliberately
     * bounded and local to the current request; the recovered state cannot be published until the unchanged
     * simultaneous solver and independent acceptance audit both pass.</p>
     */
    private static V3SolvePass recoverContinuationStage(
            V3ColumnProblem problem,
            V3PengRobinsonThermo thermo,
            V3SolvePass failedPass,
            V3SolveControl control,
            String stagePath,
            int stageCount,
            V3SolvePolicy policy) {
        control.checkpoint();
        return recoverWithBubblePointProjection(problem, thermo, failedPass.attempt().state(), control,
                "cold/stage-continuation/" + stagePath + "/material-vle-recovery-stage-" + stageCount + "/fine-fd",
                ContinuationJacobianPolicy.STAGE_LOCAL_BLOCKS,
                MAXIMUM_NEWTON_ITERATIONS, policy);
    }

    static V3SolvePass recoverWithBubblePointProjection(
            V3ColumnProblem problem,
            V3PengRobinsonThermo thermo,
            V3DryMeshState projectionSource,
            V3SolveControl control,
            String solvePath,
            ContinuationJacobianPolicy jacobianPolicy,
            int maximumIterations,
            V3SolvePolicy policy) {
        control.checkpoint();
        V3DryMeshState projected = projectedSeedOrPrevious(problem, thermo, projectionSource, control);
        return solveSingleProblem(problem, thermo, projected, control, solvePath,
                RungOptions.of(jacobianPolicy, maximumIterations), policy);
    }

    static boolean hasCondenserPhaseMismatch(V3SolvePass pass) {
        return pass.attempt() instanceof V3SimultaneousColumnSolver.Attempt.Converged
                && pass.audit().checks().stream().anyMatch(check -> check.hasFamily(V3AuditFamily.CONDENSER_PHASE) && !check.passed());
    }

    /** Resolve the current branch without carrying an earlier attempt's frozen truncation support. */
    static V3ColumnProblem originalProblem(V3SolvePass pass) {
        V3ColumnProblem selected = pass.prepared().problem();
        return V3ColumnProblemResolver.resolve(selected.input(), selected.topology().condenserPhaseBranch());
    }

    private static boolean featureRampRequired(V3ColumnInput input) {
        return !input.sideDraws().isEmpty() || !input.steamFeeds().isEmpty() || !input.pumparounds().isEmpty();
    }

    /**
     * Grid schedule of the stage continuation: the seed grids 4, 8 and 15 where they are below the request,
     * then a doubling of 15 for as long as the doubled grid is strictly below the request, and the request
     * itself last.
     *
     * <p>A request of 30 or fewer stages therefore produces exactly the historical {@code {4, 8, 15}} prefix
     * plus the request, because the first doubled grid is already 30: no digest, solve path or timing of an
     * existing case moves. Above 30 the schedule gains 30, then 60: a request of 40 becomes 4-8-15-30-40 and
     * a request of 64 becomes 4-8-15-30-60-64.</p>
     *
     * <p>Measured on the plain TJL19 40-tray literature column (a Tia Juana test fixture since 2026-11-05; feed tray 37, 250 kPa, reflux 4.17, condenser
     * 332.15 K, 8 MW reboiler): the 15 to 40 jump hands Newton a seed whose energy rows are all short by the
     * same 285 kW and whose first direction the line search rejects at iteration 0, so the request fails after
     * 287 s. With the intermediate 30-stage grid the same column converges in 5.4 s.</p>
     */
    static List<Integer> continuationStageCounts(int requestedStageCount) {
        return continuationStageCounts(requestedStageCount, V3SolveStrategy.DRIFT_SPEC);
    }

    static List<Integer> continuationStageCounts(int requestedStageCount, V3SolveStrategy strategy) {
        List<Integer> result = new java.util.ArrayList<>();
        if (strategy == V3SolveStrategy.AGGRESSIVE && requestedStageCount >= 32) {
            result.add(Math.min(15, requestedStageCount - 1));
            if (requestedStageCount >= 38) result.add(30);
            result.add(requestedStageCount);
            return List.copyOf(result);
        }
        for (int stageCount : new int[] {4, 8, 15}) {
            if (stageCount < requestedStageCount) result.add(stageCount);
        }
        for (int stageCount = 30; stageCount < requestedStageCount; stageCount *= 2) result.add(stageCount);
        result.add(requestedStageCount);
        return List.copyOf(result);
    }

    private static String continuationStagePath(List<Integer> stageCounts) {
        StringBuilder path = new StringBuilder();
        for (int index = 0; index < stageCounts.size(); index++) {
            if (index > 0) path.append('-');
            path.append(stageCounts.get(index));
        }
        return path.toString();
    }

    static V3ColumnInput withStageGeometry(V3ColumnInput input, int stageCount) {
        int feedStage = input.feedStageNumber() == input.stageCount()+1 ? stageCount+1 : Math.clamp((int) Math.round(
                stageCount * input.feedStageNumber() / (double) input.stageCount()), 1, Math.max(1,stageCount));
        return new V3ColumnInput(input.schemaVersion(), input.packageId(), input.assayId(), input.componentBasis(),
                input.feedComponentMolarFlowsMolPerSecond(), input.feedTemperatureKelvin(), stageCount, feedStage,
                input.topPressurePascal(), input.stagePressureDropPascal(), input.specifications(), List.of(),
                List.of(), List.of(), input.columnDiameterMetres());
    }

    static V3ColumnInput withoutSideDraws(V3ColumnInput input) {
        if (input.sideDraws().isEmpty()) return input;
        return new V3ColumnInput(input.schemaVersion(), input.packageId(), input.assayId(), input.componentBasis(),
                input.feedComponentMolarFlowsMolPerSecond(), input.feedTemperatureKelvin(), input.stageCount(),
                input.feedStageNumber(), input.topPressurePascal(), input.stagePressureDropPascal(),
                input.specifications(), List.of(), input.steamFeeds(), input.pumparounds(),
                input.columnDiameterMetres());
    }

    /** Stage heat is never present in a cold seed; it is introduced only by continuation from an accepted state. */
    static V3ColumnInput withoutPumparounds(V3ColumnInput input) {
        if (input.pumparounds().isEmpty()) return input;
        return new V3ColumnInput(input.schemaVersion(), input.packageId(), input.assayId(), input.componentBasis(),
                input.feedComponentMolarFlowsMolPerSecond(), input.feedTemperatureKelvin(), input.stageCount(),
                input.feedStageNumber(), input.topPressurePascal(), input.stagePressureDropPascal(),
                input.specifications(), input.sideDraws(), input.steamFeeds(), List.of(),
                input.columnDiameterMetres());
    }

    /** Supplies a dry boilup surrogate only for continuation seeds; publication always returns to authored duty. */
    static V3ColumnInput withoutSteamWithSurrogateDuty(V3ColumnInput input) {
        if (input.steamFeeds().isEmpty()) return input;
        return new V3ColumnInput(input.schemaVersion(), input.packageId(), input.assayId(), input.componentBasis(),
                input.feedComponentMolarFlowsMolPerSecond(), input.feedTemperatureKelvin(), input.stageCount(),
                input.feedStageNumber(), input.topPressurePascal(), input.stagePressureDropPascal(),
                withReboilerDuty(input, reboilerDutyWatts(input)
                        + surrogateSteamDutyWatts(input)), input.sideDraws(), List.of(), input.pumparounds(),
                input.columnDiameterMetres());
    }

    static double surrogateSteamDutyWatts(V3ColumnInput input) {
        return input.steamFeeds().stream().mapToDouble(V3SteamFeedSpec::molarFlowMolPerSecond).sum()
                * V3WaterProperties.vaporizationEnthalpy(450.0);
    }

    static List<V3ColumnSpecification> withReboilerDuty(V3ColumnInput input, double watts) {
        return input.specifications().stream().map(specification -> specification instanceof V3ColumnSpecification.ReboilerDuty
                ? new V3ColumnSpecification.ReboilerDuty(watts) : specification).toList();
    }

    static double reboilerDutyWatts(V3ColumnInput input) {
        return input.specifications().stream().filter(V3ColumnSpecification.ReboilerDuty.class::isInstance)
                .map(V3ColumnSpecification.ReboilerDuty.class::cast).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("V3 input is missing a reboiler-duty specification")).watts();
    }

    static V3DryMeshState initializeForSolve(
            V3ColumnProblem problem, V3PengRobinsonThermo thermo, V3ColumnInitializer.Mode mode) {
        try {
            return V3ColumnInitializer.initialize(problem, thermo, thermo.newWorkspace(), mode).state();
        } catch (V3ThermoException | IllegalArgumentException failure) {
            String draws = problem.input().sideDraws().isEmpty() ? ""
                    : "; authored side-draw trays=" + problem.input().sideDraws().stream()
                            .map(V3SideDrawSpec::trayNumber).toList();
            throw new InitializationFailure("V3 initialization failed for " + problem.topology().trayCount()
                    + " trays" + draws + ": " + boundedSummary(failure.getMessage()), failure);
        }
    }

    private static V3ColumnInput withTopPressure(V3ColumnInput input, double topPressurePascal) {
        if (!Double.isFinite(topPressurePascal) || topPressurePascal <= 0.0) {
            throw new IllegalArgumentException("V3 pressure-continuation top pressure is invalid");
        }
        return new V3ColumnInput(input.schemaVersion(), input.packageId(), input.assayId(), input.componentBasis(),
                input.feedComponentMolarFlowsMolPerSecond(), input.feedTemperatureKelvin(), input.stageCount(),
                input.feedStageNumber(), topPressurePascal, input.stagePressureDropPascal(), input.specifications(),
                input.sideDraws(), input.steamFeeds(), input.pumparounds(), input.columnDiameterMetres());
    }

    private static List<Double> continuationPressureSteps(
            double requestedTopPressurePascal, boolean fineStepsFromAnchor) {
        List<Double> pressures = new java.util.ArrayList<>();
        double pressure = PRESSURE_CONTINUATION_ANCHOR_PASCAL;
        while (pressure > requestedTopPressurePascal) {
            double step = !fineStepsFromAnchor && pressure > PRESSURE_CONTINUATION_FINE_STEP_FROM_PASCAL
                    ? PRESSURE_CONTINUATION_STEP_PASCAL : PRESSURE_CONTINUATION_FINE_STEP_PASCAL;
            pressure = Math.max(requestedTopPressurePascal, pressure - step);
            pressures.add(pressure);
        }
        return List.copyOf(pressures);
    }

    private static String continuationPressurePath(double requestedTopPressurePascal, boolean fineStepsFromAnchor) {
        StringBuilder path = new StringBuilder();
        path.append(Math.round(PRESSURE_CONTINUATION_ANCHOR_PASCAL / 1_000.0));
        for (double pressure : continuationPressureSteps(requestedTopPressurePascal, fineStepsFromAnchor)) {
            path.append('-').append(Math.round(pressure / 1_000.0));
        }
        return path.toString();
    }

    /**
     * Reads one converged grid's profile onto the next grid's nodes, node position by node position.
     *
     * <p>{@code sourceTopology} is the geometry the state belongs to, which a {@link V3DryMeshState} does not
     * carry: it validates against its topology and then keeps only the node count. It is needed because the
     * map is not purely geometric — see {@link #sourcePosition}, which holds a rectifying node off the source
     * feed tray.</p>
     */
    private static V3DryMeshState interpolate(
            V3DryMeshState source, V3ColumnTopology sourceTopology, V3ColumnProblem target) {
        if (source.nodeCount() != sourceTopology.nodeCount()) {
            throw new IllegalArgumentException("V3 continuation seed does not match its source geometry");
        }
        int nodes = target.topology().nodeCount();
        int components = source.componentCount();
        double[][] liquid = new double[nodes][components];
        double[][] vapor = new double[nodes][components];
        double[] temperatures = new double[nodes];
        for (int node = 0; node < nodes; node++) {
            double position = sourcePosition(node, sourceTopology, target.topology());
            int lower = (int) Math.floor(position);
            int upper = Math.min(source.nodeCount() - 1, lower + 1);
            double fraction = position - lower;
            temperatures[node] = source.temperatureKelvin(lower)
                    + fraction * (source.temperatureKelvin(upper) - source.temperatureKelvin(lower));
            for (int component = 0; component < components; component++) {
                liquid[node][component] = target.topology().hasLiquidPhase(node) ? source.liquidFlow(lower, component)
                        + fraction * (source.liquidFlow(upper, component) - source.liquidFlow(lower, component)) : 0.0;
                vapor[node][component] = source.vaporFlow(lower, component)
                        + fraction * (source.vaporFlow(upper, component) - source.vaporFlow(lower, component));
            }
        }
        temperatures[target.topology().condenserNode()] = specification(
                target.input(), V3ColumnSpecification.CondenserOutletTemperature.class).kelvin();
        return new V3DryMeshState(target.topology(), components, liquid, vapor, temperatures);
    }

    /**
     * Where a target node reads the source profile: the whole-column linear map, with one clamp.
     *
     * <p>The map itself is unchanged — {@code node × sourceReboiler / targetReboiler}, which fixes the
     * condenser and the reboiler — and the clamp only keeps a <em>rectifying</em> target node from reading
     * the source feed tray. {@code interpolate} blends {@code floor(position)} with the node below it, so a
     * position of at most {@code sourceFeed − 1} cannot touch the source feed tray at all: at exactly that
     * bound the fraction is zero and the second node is unused.</p>
     *
     * <p>What the clamp is for is that the feed tray is a step in the profile, not a point on a curve. Above
     * the feed the heavy pseudo-components fall by orders of magnitude per tray, while the feed tray itself
     * carries them in bulk, so a rectifying tray that reads even a fifth of the source feed tray is seeded
     * orders of magnitude above anything its own neighbours deliver. Newton undoes such a flow at one e-fold
     * per iteration — for {@code r = I − a·e^z} with {@code a·e^z ≫ I} the step is exactly
     * {@code −1 + I/(a·e^z)} — on a scaled residual pinned at one throughout. Measured on the plain 40-tray
     * column, with {@link V3FloorLift#capOversizedPoint} already in place, the clamp takes the stage rungs from
     * 11/21/30/29/21 to 11/21/28/29/14 Newton iterations and the e-fold plateau from 17 iterations to 6.</p>
     *
     * <p>Only the rectifying side, and that asymmetry is measured rather than assumed. Clamping the stripping
     * side symmetrically — a target node below the feed reading at least {@code sourceFeed + 1} — moves
     * exactly one tray on the cold 30-tray pilot's last rung, the tray just below the feed, and costs two of
     * the three {@code coldCondenserProducesOnlyLiquidProducts} cases their convergence entirely (18 s and
     * 25 s of NONCONVERGENCE at residuals of 2.2 and 5.1, against 0.3 s successes). Below the feed the same
     * components are bulk on both sides of the step, so there is nothing there for a clamp to protect and the
     * only thing it changes is the map. Anchoring the target feed tray onto the source feed tray as well was
     * measured separately, keeps every case, and is worth one Newton iteration out of a hundred: not enough
     * to justify a third rule.</p>
     *
     * <p>When the two grids have the same geometry — every ramp rung — the map is exactly the identity: the
     * position is the node, the clamp cannot bind, and a zero fraction returns its endpoint unrounded.</p>
     */
    static double sourcePosition(int node, V3ColumnTopology source, V3ColumnTopology target) {
        double position = node * (double) source.reboilerNode() / target.reboilerNode();
        if (node >= target.feedTrayNumber()) return position;
        return Math.min(position, source.feedTrayNumber() - 1.0);
    }

    private static <S extends V3ColumnSpecification> S specification(V3ColumnInput input, Class<S> type) {
        return input.specifications().stream().filter(type::isInstance).map(type::cast).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("V3 input is missing " + type.getSimpleName()));
    }

    private static String pressureEvent(double pressurePascal, String phase, V3SolvePass pass) {
        V3SimultaneousColumnSolver.Evidence evidence = pass.attempt().evidence();
        return "pressure " + Math.round(pressurePascal / 1_000.0) + " kPa " + phase
                + ": " + evidence.termination() + ", iterations=" + evidence.iterations()
                + ", residual=" + evidence.maximumScaledResidual();
    }
}
