package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.icheme.science.column.v3.thermo.V3FlashTruncationEvidence;
import com.wormzjl.icheme.science.column.v3.thermo.V3WaterProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a column solve publishes about itself: the bounded event lines, the operating detail of a failure, the
 * bounded solve path, the typed failure built before or outside an attempt, and the attempt code's failure code.
 *
 * <p>Moved out of {@link V3ColumnCalculator} unchanged (batch 2026-11-13-codebase-review, D-C1). Each function
 * reads only its arguments: no solve policy, pass or attempt state of the calculator. The texts are published
 * (events, solve paths, failure summaries) and tests match them; a change of one character is a result change.
 * The per-attempt direction counters are {@link SolveTelemetry}.</p>
 */
final class V3SolveReport {
    private V3SolveReport() {}

    static String boundedEvent(String event) {
        return event.length() <= 256 ? event : event.substring(0, 256);
    }

    static String sideDrawDiagnostic(
            V3ColumnProblem problem, V3DryMeshState state, int requestedStageCount) {
        V3SideDrawSpec worst = null;
        double worstLiquid = 0.0;
        double largestFraction = -1.0;
        for (V3SideDrawSpec draw : problem.input().sideDraws()) {
            double liquid = V3SideDrawSpec.liquidTotal(state, draw.trayNumber());
            double fraction = liquid > 0.0 && Double.isFinite(liquid)
                    ? draw.molarFlowMolPerSecond() / liquid : Double.MAX_VALUE;
            if (fraction > largestFraction) {
                worst = draw;
                worstLiquid = liquid;
                largestFraction = fraction;
            }
        }
        if (worst == null) return "";
        String geometry = problem.topology().trayCount() == requestedStageCount
                ? "authored tray " + (worst.trayNumber() + 1)
                : "tray " + (worst.trayNumber() + 1) + " at the " + (problem.topology().trayCount() + 2)
                        + "-tray continuation grid (requested " + (requestedStageCount + 2) + " trays)";
        return String.format(Locale.ROOT,
                "; side draw on %s requests %.6g kmol/h; final internal liquid %.6g kmol/h (withdrawal %.5g)",
                geometry, worst.molarFlowMolPerSecond() * 3.6, worstLiquid * 3.6, largestFraction);
    }

    static String waterDiagnostic(V3ColumnProblem problem, V3DryMeshState state) {
        if (!problem.hasSteamFeeds()) return "";
        double maximumRatio = 0.0;
        for (int node = 1; node <= problem.topology().reboilerNode(); node++) {
            double water = problem.waterVaporFlow(state, node);
            double temperature = state.temperatureKelvin(node);
            if (water == 0.0 || temperature >= 640.0 || temperature < V3WaterProperties.triplePoint()) continue;
            double hydrocarbon = 0.0;
            for (int component = 0; component < state.componentCount(); component++) hydrocarbon += state.vaporFlow(node, component);
            maximumRatio = Math.max(maximumRatio, problem.nodePressurePascal(node) * water / (hydrocarbon + water)
                    / V3WaterProperties.saturationPressurePascal(temperature));
        }
        // A wet tray sits exactly on the saturation line, so this ratio reads one there by construction.
        return String.format(Locale.ROOT, "; water dew-point saturation ratio %.5g (wet trays: %d)",
                maximumRatio, problem.wetTraySet().wetTrayCount());
    }

    static String stageTraceEvent(V3TruncationSupport support, V3DryMeshState state, V3ColumnProblem problem) {
        String defect;
        try {
            defect = String.format(Locale.ROOT, "%.6g", support.massDefectMolPerSecond(state)
                    / problem.activeComponentBasis().totalFeedFlowMolPerSecond());
        } catch (IllegalArgumentException unavailable) {
            defect = "unavailable";
        }
        String event = "stage-trace cutoff=" + support.cutoffMoleFraction() + "; truncated="
                + support.truncatedPointCount() + "/" + support.totalPointCount() + "; one-phase="
                + support.onePhasePointCount() + "; closure-pruned="
                + support.closurePrunedCount() + "; defect/feed=" + defect
                + (support.note().isEmpty() ? "" : "; " + support.note());
        return event.length() <= 256 ? event : event.substring(0, 256);
    }

    static String flashTraceEvent(V3FlashResult flash) {
        V3FlashTruncationEvidence evidence = flash.truncationEvidence();
        String event = String.format(Locale.ROOT,
                "flash-trace cutoff=%.6g; status=%s; omitted=%dL/%dV; it=%d/%d",
                evidence.cutoffMoleFraction(), evidence.status(), evidence.omittedLiquidComponents(),
                evidence.omittedVaporComponents(), evidence.referenceIterations(), evidence.reducedIterations());
        event += evidence.errorsEvaluated() ? String.format(Locale.ROOT,
                "; alloc=%.3g; beta=%.3g; x/y=%.3g; mass=%.3g; dH=%.3g J/mol",
                evidence.allocationError(), evidence.betaError(), evidence.maxPhaseCompositionError(),
                evidence.maxMaterialClosureError(), evidence.enthalpyErrorJoulesPerMol()) : "; errors=not-evaluated";
        event += "; feed-H=reference";
        return event.length() <= 256 ? event : event.substring(0, 256);
    }

    /**
     * Fits a solve path into the diagnostics contract without losing where the solve started or ended.
     *
     * <p>A low-pressure request that also ramps draws, steam and stage heat writes a path longer than the
     * diagnostics record accepts. The record's rejection is an {@link IllegalArgumentException}, which the branch
     * boundary reports as {@code INVALID_INPUT}: a completed solve was discarded and its real outcome replaced by
     * a claim about the request. The pressure ladder is the long part and carries the least, so it is reduced to
     * its two ends first; anything still too long keeps its head and its tail.</p>
     */
    static String boundedSolvePath(String solvePath) {
        int limit = V3SolverDiagnostics.MAX_SOLVE_PATH_LENGTH;
        if (solvePath == null || solvePath.isBlank() || solvePath.length() <= limit) return solvePath;
        String ladderPrefix = "cold/pressure-continuation/";
        int ladderStart = solvePath.indexOf(ladderPrefix);
        if (ladderStart >= 0) {
            ladderStart += ladderPrefix.length();
            int ladderEnd = solvePath.indexOf('/', ladderStart);
            if (ladderEnd < 0) ladderEnd = solvePath.length();
            String ladder = solvePath.substring(ladderStart, ladderEnd);
            int first = ladder.indexOf('-'), last = ladder.lastIndexOf('-');
            if (first > 0 && last > first) {
                solvePath = solvePath.substring(0, ladderStart) + ladder.substring(0, first) + ".."
                        + ladder.substring(last + 1) + solvePath.substring(ladderEnd);
            }
        }
        if (solvePath.length() <= limit) return solvePath;
        String elision = "/.../";
        int headLength = 80;
        return solvePath.substring(0, headLength) + elision
                + solvePath.substring(solvePath.length() - (limit - headLength - elision.length()));
    }

    static V3ColumnOutcome.Failure terminalFailure(
            V3SolverFailureCode code, String detail, String solvePath, List<String> advisoryEvidence) {
        String summary = boundedSummary(detail);
        V3AcceptanceAudit audit = failedAudit(V3AuditFamily.UNAVAILABLE, summary, advisoryEvidence);
        // The summary and the audit detail are bounded at 512, a diagnostic event at 256. Passing the summary
        // straight through threw IllegalArgumentException out of the public calculate() for any detail between
        // the two bounds — reachable as soon as a typed detail grew past 256 characters.
        V3SolverDiagnostics diagnostics = new V3SolverDiagnostics(0, 0, 0, 0, 0.0, 0.0, boundedSolvePath(solvePath),
                List.of(boundedEvent(summary)), audit, V3ConvergenceEvidence.unavailable());
        return new V3ColumnOutcome.Failure(code, summary, diagnostics);
    }

    static V3AcceptanceAudit failedAudit(V3AuditFamily family, String detail) {
        return failedAudit(family, detail, List.of());
    }

    static V3AcceptanceAudit failedAudit(V3AuditFamily family, String detail, List<String> advisoryEvidence) {
        return new V3AcceptanceAudit(List.of(V3AcceptanceAudit.Check.fail(family, 1.0, 0.0, boundedSummary(detail))),
                advisoryEvidence);
    }

    /** Maps a solver attempt code onto the published failure taxonomy; a stopped stall is a nonconvergence. */
    static V3SolverFailureCode failureCode(V3AttemptCode code) {
        return switch (code) {
            case LINEAR_SINGULAR, LINEAR_ILL_CONDITIONED, LINEAR_BACKWARD_ERROR_EXCEEDED, JACOBIAN_BAND_STRUCTURE ->
                    V3SolverFailureCode.LINEAR_SOLVE_FAILURE;
            case MAX_ITERATIONS, LINE_SEARCH_EXHAUSTED, STATE_DOMAIN, STALLED, STALLED_SUPPORT_FLOOR ->
                    V3SolverFailureCode.NONCONVERGENCE;
            case INTERNAL_INVARIANT -> V3SolverFailureCode.INTERNAL_ERROR;
        };
    }

    static String boundedSummary(String value) {
        if (value == null || value.isBlank()) return "V3 calculation failed without a detail string";
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    static List<String> mergedEvents(List<String> continuationEvents, List<String> solverEvents) {
        List<String> result = new ArrayList<>(continuationEvents);
        for (String event : solverEvents) {
            if (result.size() >= V3SolverDiagnostics.MAX_EVENTS) break;
            result.add(event);
        }
        return List.copyOf(result);
    }
}
