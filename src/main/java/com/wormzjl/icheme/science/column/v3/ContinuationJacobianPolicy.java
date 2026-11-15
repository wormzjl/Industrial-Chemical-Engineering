package com.wormzjl.icheme.science.column.v3;

/**
 * The Jacobian route of one rung's Newton solve. The switch of {@link V3AttemptLoop#solveSingleProblem} maps it to
 * a solver entry: {@code NONE} to {@code V3SimultaneousColumnSolver.solve} with fine finite differences,
 * {@code STAGE_LOCAL_BLOCKS} to {@code solveWithContinuationLocalBlocks}, {@code PRESSURE_LOCAL_PREDICTOR} to
 * {@code solveWithOneLocalBlockPredictor}. {@link RungOptions} carries it.
 *
 * <p>Moved out of {@link V3ColumnCalculator} unchanged (batch 2026-11-13-codebase-review, D-C4): a nested enum
 * before.</p>
 */
enum ContinuationJacobianPolicy {
    NONE,
    STAGE_LOCAL_BLOCKS,
    PRESSURE_LOCAL_PREDICTOR
}
