package com.wormzjl.icheme.science.column.v3;

/**
 * A rung's pass after its condenser phase correction, and whether the pass had a condenser phase mismatch to
 * correct ({@code attempted} is true also when the correction was unavailable).
 *
 * <p>Moved out of {@link V3ColumnCalculator} unchanged (batch 2026-11-13-codebase-review, D-C4): a nested record
 * before. {@link V3AttemptLoop#correctCondenserPhase} returns it; the stage grid and the pressure leg of
 * {@link V3ContinuationLadder} and the calculator's material-closed fallback and coarse recovery read the
 * verdict.</p>
 */
record PhaseCorrection(V3SolvePass pass, boolean attempted) {}
