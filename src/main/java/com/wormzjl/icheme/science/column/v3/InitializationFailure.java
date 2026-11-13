package com.wormzjl.icheme.science.column.v3;

/**
 * Distinguishes numerical seed construction from malformed authored input.
 *
 * <p>Thrown by {@link V3ContinuationLadder#initializeForSolve}; {@code V3ColumnCalculator.calculateBranch} catches
 * it and publishes {@code INITIALIZATION_FAILURE}. Moved out of {@link V3ColumnCalculator} unchanged (batch
 * 2026-11-13-codebase-review, D-C4): a nested class before, its constructor package-private now.</p>
 */
final class InitializationFailure extends RuntimeException {
    InitializationFailure(String message, Throwable cause) {
        super(message, cause);
    }
}
