package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.column.v3.V3SimultaneousColumnSolver.NewtonTrace;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Bounded per-solve direction counters, surfaced through the existing diagnostics event contract.
 *
 * <p>One per Newton attempt of {@code V3AttemptLoop.solveSingleProblem}; it holds only its own counters and
 * forwards to the learned pass's recorder. Moved out of {@link V3ColumnCalculator} unchanged (batch
 * 2026-11-13-codebase-review, D-C1).</p>
 */
final class SolveTelemetry implements NewtonTrace {
    private final V3ColumnProblem problem;
    /** Where a learned pass keeps its in-flight evidence; null on every classical solve. */
    private final NeuralProgress progress;
    private int acceptedLocalBlockDirections;
    private int rejectedLocalBlockDirections;
    private int freshFiniteDifferenceJacobians;
    private int reusedFiniteDifferenceJacobians;
    private double initialMaximumScaledResidual = Double.NaN;
    private double finalMaximumScaledResidual = Double.NaN;
    private V3DegreeOfFreedomLedger.EquationId finalDominantEquation;
    private double finalDominantPhysicalResidual;
    private V3DryMeshState finalState;

    SolveTelemetry(V3ColumnProblem problem, NeuralProgress progress) {
        this.problem = Objects.requireNonNull(problem, "problem");
        this.progress = progress;
    }

    @Override
    public void sampledIteration(int iteration, V3MeshResidual residual, double scaledMerit) {
        // The state-bearing callback below owns production telemetry. This method preserves lambda compatibility.
    }

    @Override
    public void sampledState(int iteration, V3DryMeshState state, V3MeshResidual residual, double scaledMerit) {
        if (progress != null) progress.sampledState(iteration, state, residual, scaledMerit);
        double maximum = residual.maximumAbsoluteScaledResidual();
        if (Double.isNaN(initialMaximumScaledResidual)) initialMaximumScaledResidual = maximum;
        finalMaximumScaledResidual = maximum;
        finalState = state;
        int dominant = 0;
        for (int row = 1; row < residual.size(); row++) {
            if (Math.abs(residual.scaledValue(row)) > Math.abs(residual.scaledValue(dominant))) dominant = row;
        }
        finalDominantEquation = residual.equation(dominant);
        finalDominantPhysicalResidual = residual.physicalValue(dominant);
    }

    @Override
    public void localBlockDirection(int iteration, boolean accepted) {
        if (progress != null) progress.localBlockDirection(iteration, accepted);
        if (accepted) acceptedLocalBlockDirections++;
        else rejectedLocalBlockDirections++;
    }

    @Override
    public void finiteDifferenceJacobian(int iteration, boolean reused) {
        if (progress != null) progress.finiteDifferenceJacobian(iteration, reused);
        if (reused) reusedFiniteDifferenceJacobians++;
        else freshFiniteDifferenceJacobians++;
    }

    List<String> events() {
        List<String> events = new ArrayList<>();
        if (Double.isFinite(initialMaximumScaledResidual)) {
            events.add("scaled residual: initial=" + initialMaximumScaledResidual
                    + ", final=" + finalMaximumScaledResidual);
        }
        if (finalDominantEquation != null) {
            events.add("dominant residual: " + finalDominantEquation + ", physical="
                    + finalDominantPhysicalResidual);
            if (finalState != null && finalDominantEquation.family()
                    == V3DegreeOfFreedomLedger.EquationFamily.VAPOR_LIQUID_EQUILIBRIUM) {
                int activeComponent = finalDominantEquation.component();
                int publicComponent = problem.activeComponentBasis().publicIndex(activeComponent);
                int node = finalDominantEquation.node();
                events.add("dominant VLE state: component=" + problem.input().componentBasis().componentId(publicComponent)
                        + ", node=" + node + ", temperature=" + finalState.temperatureKelvin(node)
                        + ", liquid-flow=" + finalState.liquidFlow(node, activeComponent)
                        + ", vapor-flow=" + finalState.vaporFlow(node, activeComponent));
            }
        }
        if (acceptedLocalBlockDirections + rejectedLocalBlockDirections > 0) {
            events.add("local-block directions: accepted=" + acceptedLocalBlockDirections
                    + ", rejected=" + rejectedLocalBlockDirections);
        }
        if (freshFiniteDifferenceJacobians + reusedFiniteDifferenceJacobians > 0) {
            events.add("fine finite-difference Jacobians: fresh=" + freshFiniteDifferenceJacobians
                    + ", reused=" + reusedFiniteDifferenceJacobians);
        }
        return List.copyOf(events);
    }
}
