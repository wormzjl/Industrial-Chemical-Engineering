package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.column.v3.V3SimultaneousColumnSolver.NewtonTrace;

/** Immutable per-chain policy separates scientific request provenance from an untruncated retry. */
/**
 * Per-chain solve policy: the authored stage-trace cutoff and the convergence closure.
 *
 * <p>Both travel together into every {@code solveSingleProblem}, ramp rung, continuation stage, recovery,
 * condenser-phase correction and warm-start solve, so that no solve on a chain can be run at a different
 * closure from the one the chain publishes. The cutoff can be disabled for one rung
 * ({@link #withoutCutoff()}); the closure never can.</p>
 *
 * <p>Moved out of {@link V3ColumnCalculator} as its nested {@code SolvePolicy}, unchanged but for the name (batch
 * 2026-11-13-codebase-review, D-C2). A package-private record cannot keep a private canonical constructor, so the
 * constructors, the two policies and the two methods became package-private with the move.</p>
 */
record V3SolvePolicy(double requestedCutoff, double attemptCutoff, double closureTolerance,
        java.util.function.Consumer<V3NeuralSeed> observer, NeuralProgress progress, boolean fastPublication) {
    V3SolvePolicy(double requestedCutoff, double attemptCutoff, double closureTolerance) {
        this(requestedCutoff, attemptCutoff, closureTolerance, null, null, false);
    }

    /** Only the learned path carries an in-flight recorder; every classical solve leaves it null. */
    V3SolvePolicy(double requestedCutoff, double attemptCutoff, double closureTolerance,
            java.util.function.Consumer<V3NeuralSeed> observer) {
        this(requestedCutoff, attemptCutoff, closureTolerance, observer, null, false);
    }
    V3SolvePolicy(double requestedCutoff, double attemptCutoff, double closureTolerance,
            java.util.function.Consumer<V3NeuralSeed> observer, NeuralProgress progress) {
        this(requestedCutoff, attemptCutoff, closureTolerance, observer, progress, false);
    }
    static final V3SolvePolicy OFF =
            new V3SolvePolicy(0.0, 0.0, V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE, null, null, false);
    static final V3SolvePolicy OFF_FAST =
            // Interactive cold requests trade the last digits of the MESH closure for a bounded response. The
            // strict DRIFT_SPEC path keeps the 1e-8 certificate; this route uses the public 1e-3 gameplay
            // closure while retaining the independent conservation and final-step audits.
            new V3SolvePolicy(0.0, 0.0, V3ColumnCalculator.AGGRESSIVE_CLOSURE_TOLERANCE, null, null, true);

    V3SolvePolicy {
        V3TruncationSupport.requireCutoff(requestedCutoff);
        V3TruncationSupport.requireCutoff(attemptCutoff);
        V3ConvergenceEvidence.requireClosure(closureTolerance);
        if (attemptCutoff != 0.0 && attemptCutoff != requestedCutoff) {
            throw new IllegalArgumentException("V3 attempt cutoff must match the request or be disabled for fallback");
        }
    }

    /** Same closure, no stage-trace cutoff; the ramp's intermediate rungs run untruncated by design. */
    V3SolvePolicy withoutCutoff() {
        return requestedCutoff == 0.0 && attemptCutoff == 0.0
                ? this : new V3SolvePolicy(0.0, 0.0, closureTolerance, observer, progress, fastPublication);
    }

    /** The trace a solve on this policy reports through; unobserved classical solves report nowhere. */
    NewtonTrace trace() {
        return progress == null ? NewtonTrace.NONE : progress;
    }
}
