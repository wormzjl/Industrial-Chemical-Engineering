package com.wormzjl.icheme.science.column.v3;

import java.util.List;
import java.util.Objects;

/**
 * One solve of the column calculator's ladder: its attempt and fresh audit, the path and events it publishes, the
 * seed a recovery may start from, how far the chain got, and the attempt it prepared.
 *
 * <p>Moved out of {@link V3ColumnCalculator} unchanged (batch 2026-11-13-codebase-review, D-C2). A package-private
 * record cannot keep a private canonical constructor, so the constructor became package-private with the move.</p>
 */
record V3SolvePass(
        V3SimultaneousColumnSolver.Attempt attempt,
        V3AcceptanceAudit audit,
        double feedMolarEnthalpyJoulesPerMol,
        String solvePath,
        V3DryMeshState recoverySeed,
        int terminalStageCount,
        boolean reachedRequestedProblem,
        boolean attemptedRequestedProblem,
        boolean allowsFreshMaterialClosedFallback,
        List<String> solverEvents,
        PreparedAttempt prepared) {
    V3SolvePass {
        attempt = Objects.requireNonNull(attempt, "attempt");
        audit = Objects.requireNonNull(audit, "audit");
        recoverySeed = Objects.requireNonNull(recoverySeed, "recoverySeed");
        prepared = Objects.requireNonNull(prepared, "prepared");
        solverEvents = List.copyOf(Objects.requireNonNull(solverEvents, "solverEvents"));
        if (!Double.isFinite(feedMolarEnthalpyJoulesPerMol) || solvePath == null || solvePath.isBlank()
                || terminalStageCount < V3ColumnInput.MIN_STAGE_COUNT
                || solverEvents.size() > V3SolverDiagnostics.MAX_EVENTS
                || solverEvents.stream().anyMatch(event -> event == null || event.length() > 256)) {
            throw new IllegalArgumentException("V3 solve pass evidence is invalid");
        }
    }
}
