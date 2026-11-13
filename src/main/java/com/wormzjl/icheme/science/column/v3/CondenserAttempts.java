package com.wormzjl.icheme.science.column.v3;

import java.util.EnumSet;
import java.util.Objects;

/**
 * Solve-confined attempt history; prevents duplicate cold branch restarts after a phase correction.
 *
 * <p>Moved out of {@link V3ColumnCalculator} unchanged (batch 2026-11-13-codebase-review, D-C4): a nested class
 * before. The calculator opens one per request and passes it to the ladder, the ramp (which opens its own for
 * its rungs) and the condenser phase correction of {@link V3AttemptLoop}.</p>
 */
final class CondenserAttempts {
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
