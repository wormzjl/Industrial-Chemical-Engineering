package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.column.v3.thermo.V3FlashResult;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Probe (not a gate): where does the inside-out predictor get used, and do C++ and Java passes then agree? */
@org.junit.jupiter.api.extension.ExtendWith(com.wormzjl.icheme.science.material.Cdu17FixtureExtension.class)
class InsideOutProbeTest {
    @Test
    void probe() {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage("icheme:cdu17_tjl_acs2018");
        for (int stages : new int[] {4, 8, 15}) {
            for (double feedTemperature : new double[] {450.0, 550.0}) {
                double[] feed = new double[thermo.componentBasis().componentCount()];
                feed[6] = 50.0;
                feed[13] = 50.0;
                V3ColumnInput input = V3TestInputs.dry(V3ColumnInput.SCHEMA_VERSION, thermo.packageId(), "test:probe",
                        thermo.componentBasis(), feed, feedTemperature, stages, stages / 2, 250_000.0, 750.0, List.of(
                                new V3ColumnSpecification.CondenserOutletTemperature(300.0),
                                new V3ColumnSpecification.OrganicRefluxRatio(2.0),
                                new V3ColumnSpecification.ReboilerDuty(0.0)));
                V3ColumnProblem problem = V3ColumnProblemResolver.resolve(input, V3CondenserPhaseBranch.TWO_PHASE);
                V3FlashResult flash = thermo.flashTP(problem.input().feedTemperatureKelvin(),
                        problem.nodePressurePascal(problem.topology().feedTrayNumber()),
                        problem.input().feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace());
                V3MeshResidualEvaluator evaluator = new V3MeshResidualEvaluator(problem, thermo, flash.molarEnthalpyJoulesPerMol());
                V3DryMeshState seed = V3ColumnInitializer.initialize(problem, thermo, thermo.newWorkspace()).state();
                double seedResidual = evaluator.evaluate(seed, thermo.newWorkspace()).maximumAbsoluteScaledResidual();
                StringBuilder line = new StringBuilder(String.format("stages %2d feed %.0f K seed residual %.2e:", stages, feedTemperature, seedResidual));
                V3DryMeshState[] states = new V3DryMeshState[2];
                String[] modes = {"true", "false"};
                for (int m = 0; m < 2; m++) {
                    System.setProperty("icheme.native.thermo.batch", modes[m]);
                    boolean[] predicted = {false};
                    int[] iterations = {0};
                    V3SimultaneousColumnSolver.NewtonTrace trace = new V3SimultaneousColumnSolver.NewtonTrace() {
                        @Override public void sampledIteration(int iteration, V3MeshResidual residual, double merit) {}
                        @Override public void sampledState(int iteration, V3DryMeshState state, V3MeshResidual residual, double merit) {
                            iterations[0]++;
                            if (iteration != 0) return;
                            for (int node = 0; node < seed.nodeCount(); node++)
                                if (state.temperatureKelvin(node) != seed.temperatureKelvin(node)) predicted[0] = true;
                        }
                    };
                    long t0 = System.nanoTime();
                    V3SimultaneousColumnSolver.Attempt attempt = V3SimultaneousColumnSolver.solveWithInsideOut(problem, evaluator,
                            new V3DryMeshCoordinateMap(problem), seed, thermo::newWorkspace, 128, 1.0e-8,
                            V3SolveControl.UNBOUNDED, trace);
                    double ms = (System.nanoTime() - t0) / 1.0e6;
                    states[m] = attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged c ? c.state() : null;
                    line.append(String.format(" [%s: %s, predicted=%s, sampled=%d, %.0f ms]", m == 0 ? "C++" : "Java",
                            attempt.getClass().getSimpleName(), predicted[0], iterations[0], ms));
                }
                System.clearProperty("icheme.native.thermo.batch");
                if (states[0] != null && states[1] != null) {
                    int differ = 0, values = 0; double worst = 0;
                    for (int node = 0; node < states[0].nodeCount(); node++) {
                        double[][] pairs = new double[1 + 2 * states[0].componentCount()][];
                        pairs[0] = new double[] {states[0].temperatureKelvin(node), states[1].temperatureKelvin(node)};
                        for (int c = 0; c < states[0].componentCount(); c++) {
                            pairs[1 + 2 * c] = new double[] {states[0].liquidFlow(node, c), states[1].liquidFlow(node, c)};
                            pairs[2 + 2 * c] = new double[] {states[0].vaporFlow(node, c), states[1].vaporFlow(node, c)};
                        }
                        for (double[] p : pairs) {
                            values++;
                            if (Double.compare(p[0], p[1]) != 0) differ++;
                            worst = Math.max(worst, Math.abs(p[0] - p[1]) / Math.max(Math.max(Math.abs(p[0]), Math.abs(p[1])), Double.MIN_NORMAL));
                        }
                    }
                    line.append(String.format(" states: %d of %d differ, worst %.2e", differ, values, worst));
                }
                System.out.println(line);
            }
        }
    }
}
