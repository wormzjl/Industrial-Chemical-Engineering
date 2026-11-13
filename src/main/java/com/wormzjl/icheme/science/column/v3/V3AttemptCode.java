package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.column.v3.linalg.V3BandedPivotedSolver;

/**
 * Why one Newton attempt stopped without converging ({@link V3SimultaneousColumnSolver.Attempt.Failure}). The
 * calculator maps it onto the published {@link V3SolverFailureCode} ({@code V3SolveReport.failureCode}); the
 * text is what progress observers and test prints read, the same characters the attempt carried as a string.
 */
enum V3AttemptCode {
    STATE_DOMAIN("STATE_DOMAIN"),
    MAX_ITERATIONS("MAX_ITERATIONS"),
    STALLED("STALLED"),
    /** A point held at its support floor, or sunk below it, stopped the solve: the refresh is its next step. */
    STALLED_SUPPORT_FLOOR("STALLED_SUPPORT_FLOOR"),
    JACOBIAN_BAND_STRUCTURE("JACOBIAN_BAND_STRUCTURE"),
    LINEAR_SINGULAR("LINEAR_SINGULAR"),
    LINEAR_ILL_CONDITIONED("LINEAR_ILL_CONDITIONED"),
    LINEAR_BACKWARD_ERROR_EXCEEDED("LINEAR_BACKWARD_ERROR_EXCEEDED"),
    LINE_SEARCH_EXHAUSTED("LINE_SEARCH_EXHAUSTED"),
    INTERNAL_INVARIANT("INTERNAL_INVARIANT");

    private final String text;

    V3AttemptCode(String text) {
        this.text = text;
    }

    /** The published text of the code. */
    String text() {
        return text;
    }

    @Override
    public String toString() {
        return text;
    }

    /** The code of a banded solve that failed with no descent fallback: {@code LINEAR_} and the band's code. */
    static V3AttemptCode linear(V3BandedPivotedSolver.FailureCode code) {
        return switch (code) {
            case SINGULAR -> LINEAR_SINGULAR;
            case ILL_CONDITIONED -> LINEAR_ILL_CONDITIONED;
            case BACKWARD_ERROR_EXCEEDED -> LINEAR_BACKWARD_ERROR_EXCEEDED;
        };
    }
}
