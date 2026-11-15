package com.wormzjl.icheme.science.column.v3;

/**
 * How one rung's Newton solve runs: the Jacobian route, the iteration limit, the rung budget and the progress
 * extension. {@link V3AttemptLoop#solveSingleProblem} takes them together.
 *
 * <p>A plain carrier: it checks nothing, so the solve checks its arguments in the order it always did (the
 * iteration limit, the route, the budget, the extension). Every rung but a ramp's steam rung and the learned
 * correction takes {@link #of}: the default budget and no extension. Batch 2026-11-13-codebase-review, D-C3: it
 * replaces the overloads that filled these values one by one.</p>
 */
record RungOptions(
        ContinuationJacobianPolicy jacobianPolicy, int maximumIterations,
        V3SimultaneousColumnSolver.RungBudget budget, V3SimultaneousColumnSolver.Extension extension) {
    /** The default rung budget and no progress extension. */
    static RungOptions of(ContinuationJacobianPolicy jacobianPolicy, int maximumIterations) {
        return new RungOptions(jacobianPolicy, maximumIterations, V3SimultaneousColumnSolver.RungBudget.DEFAULT,
                V3SimultaneousColumnSolver.Extension.NONE);
    }
}
