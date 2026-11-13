package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.column.v3.V3SimultaneousColumnSolver.NewtonTrace;
import java.util.Objects;

/**
 * In-flight evidence of one learned correction pass, and the seam a diagnostic trace observes it through.
 *
 * <p>The local neural budget and a candidate rejection both leave the correction loop without a failure
 * of their own, so the published outcome used to carry a zero-iteration, zero-residual placeholder even
 * when the correction had already run most of a Newton solve. The archived campaigns therefore record
 * those cases as "budget exhausted with zero recorded iterations", which is a reporting artefact rather
 * than a seed rejected before its first step. This records what the interrupted attempt had already
 * measured so the published failure can say how far it got.</p>
 *
 * <p>It is written only from the request's own thread, only on the learned path, and it is read only
 * after the pass has ended. No step, support, budget, tolerance or acceptance decision consults it.</p>
 *
 * <p>Moved out of {@link V3ColumnCalculator} unchanged (batch 2026-11-13-codebase-review, D-C2); the constructor,
 * {@code iterations}, {@code lastMaximumScaledResidual} and {@code summary} became package-private with the move.</p>
 */
final class NeuralProgress implements NewtonTrace {
    private final NewtonTrace observer;
    private int attempts;
    private int iterations;
    private int completedIterations;
    private double maximumScaledResidual = Double.NaN;
    private int supportRefreshes;
    private int wetTrayRefreshes;

    NeuralProgress(NewtonTrace observer) {
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @Override
    public void sampledIteration(int iteration, V3MeshResidual residual, double scaledMerit) {
        observer.sampledIteration(iteration, residual, scaledMerit);
        noteIteration(iteration, residual);
    }

    @Override
    public void sampledState(int iteration, V3DryMeshState state, V3MeshResidual residual, double scaledMerit) {
        observer.sampledState(iteration, state, residual, scaledMerit);
        noteIteration(iteration, residual);
    }

    @Override
    public void localBlockDirection(int iteration, boolean accepted) {
        observer.localBlockDirection(iteration, accepted);
    }

    @Override
    public void finiteDifferenceJacobian(int iteration, boolean reused) {
        observer.finiteDifferenceJacobian(iteration, reused);
    }

    @Override
    public void beganAttempt(int attempt, int supportRefreshes, int wetTrayRefreshes, int iterationBudget,
            int retainedPoints, int totalPoints, int wetTrayCount) {
        observer.beganAttempt(attempt, supportRefreshes, wetTrayRefreshes, iterationBudget,
                retainedPoints, totalPoints, wetTrayCount);
        // Iterations sampled before the first attempt belong to the fixed-water wet prepass.
        completedIterations += iterations;
        iterations = 0;
        attempts = attempt;
        this.supportRefreshes = supportRefreshes;
        this.wetTrayRefreshes = wetTrayRefreshes;
    }

    @Override
    public void finishedAttempt(int attempt, String code, int iterations, double maximumScaledResidual) {
        observer.finishedAttempt(attempt, code, iterations, maximumScaledResidual);
    }

    private void noteIteration(int iteration, V3MeshResidual residual) {
        iterations = iteration;
        maximumScaledResidual = residual.maximumAbsoluteScaledResidual();
    }

    /** Iterations the interrupted attempt had completed; the published {@code newtonIterations}. */
    int iterations() {
        return iterations;
    }

    /** Last maximum scaled residual any attempt of this pass observed, or zero if none ever did. */
    double lastMaximumScaledResidual() {
        return Double.isFinite(maximumScaledResidual) ? maximumScaledResidual : 0.0;
    }

    String summary() {
        return "attempts=" + attempts + ", iterations=" + iterations + "/" + (completedIterations + iterations)
                + ", residual=" + (Double.isNaN(maximumScaledResidual) ? "unmeasured" : Double.toString(maximumScaledResidual))
                + ", refreshes=" + supportRefreshes + "/" + wetTrayRefreshes;
    }
}
