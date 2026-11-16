package com.wormzjl.icheme.science.column.v3;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Captures {@code src/test/resources/column/invalid-binary-roots.json} on the medium crude's package (batch
 * 2026-11-16-cdu17-fixture-removal): the {@code crude_pc03}/{@code crude_pc10} binary of {@code V3ExactWarmStartSweepTest}
 * (50 mol/s each, two trays, 250 kPa, 300 K condenser, reflux 2) on the two-phase condenser branch at its five numerical
 * matrix points. Each row is the test's own cold attempt ({@code coldAttempt}, by reflection): a converged numerical root
 * whose fresh audit rejects it on the phase checks only. A point that does not give such a root stops the capture.
 *
 * <p>Run by {@code capture.sh} against the built main and test classes. Args: {@code <output file>}.</p>
 */
public final class InvalidBinaryRootCapture {
    static final double[][] POINTS = {{550, 750, 50_000}, {551, 750, 50_000}, {550, 0, 50_000}, {550, 750, 20_000}, {550, 750, 100_000}};

    public static void main(String[] args) throws Exception {
        Method cold = V3ExactWarmStartSweepTest.class.getDeclaredMethod("coldAttempt", V3ColumnInput.class, V3CondenserPhaseBranch.class);
        cold.setAccessible(true);
        Method binary = V3ExactWarmStartSweepTest.class.getDeclaredMethod("binaryInput", double.class, double.class, double.class,
                double.class, double.class, double.class, double.class);
        binary.setAccessible(true);
        var gson = new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create();
        JsonArray rows = new JsonArray();
        for (double[] point : POINTS) {
            V3ColumnInput input = (V3ColumnInput) binary.invoke(null, 100.0, point[0], 250_000.0, point[1], 300.0, 2.0, point[2]);
            V3CondenserPhaseBranch branch = V3CondenserPhaseBranch.TWO_PHASE;
            Object attempt = cold.invoke(null, input, branch);
            var solved = (V3SimultaneousColumnSolver.Attempt) attempt.getClass().getMethod("attempt").invoke(attempt);
            var audit = (V3AcceptanceAudit) attempt.getClass().getMethod("audit").invoke(attempt);
            if (!(solved instanceof V3SimultaneousColumnSolver.Attempt.Converged converged)
                    || !converged.evidence().convergenceEvidence().satisfiesGates() || audit.accepted()
                    || !audit.checks().stream().filter(c -> !c.passed()).allMatch(c -> c.family().equals("CONDENSER_PHASE")
                    || c.family().equals("INTERIOR_PHASE_IDENTITY")))
                throw new IllegalStateException("no rejected numerical root at " + java.util.Arrays.toString(point) + ": " + solved + " " + audit);
            V3DryMeshState state = converged.state();
            JsonObject row = new JsonObject();
            row.add("evidence", gson.toJsonTree(converged.evidence().convergenceEvidence()));
            JsonObject data = new JsonObject();
            double[][] liquid = new double[state.nodeCount()][state.componentCount()], vapor = new double[state.nodeCount()][state.componentCount()];
            double[] temperatures = new double[state.nodeCount()];
            for (int n = 0; n < state.nodeCount(); n++) {
                temperatures[n] = state.temperatureKelvin(n);
                for (int c = 0; c < state.componentCount(); c++) {
                    liquid[n][c] = state.liquidFlow(n, c);
                    vapor[n][c] = state.vaporFlow(n, c);
                }
            }
            data.add("liquidComponentFlows", gson.toJsonTree(liquid));
            data.add("vaporComponentFlows", gson.toJsonTree(vapor));
            data.add("temperaturesKelvin", gson.toJsonTree(temperatures));
            data.add("freeWaterFlowsMolPerSecond", gson.toJsonTree(new double[state.nodeCount()]));
            row.add("state", data);
            row.add("input", input(input, gson));
            row.addProperty("branch", branch.name());
            rows.add(row);
            System.out.println("captured " + java.util.Arrays.toString(point) + " residual " + converged.evidence().maximumScaledResidual()
                    + " rejected by " + audit.checks().stream().filter(c -> !c.passed()).map(V3AcceptanceAudit.Check::family).toList());
        }
        Files.writeString(Path.of(args[0]), gson.toJson(rows) + "\n");
    }

    private static JsonObject input(V3ColumnInput input, com.google.gson.Gson gson) {
        JsonObject o = new JsonObject();
        o.addProperty("schemaVersion", input.schemaVersion());
        o.addProperty("packageId", input.packageId());
        o.addProperty("assayId", input.assayId());
        o.add("componentBasis", gson.toJsonTree(input.componentBasis().componentIds()));
        o.add("feedComponentMolarFlowsMolPerSecond", gson.toJsonTree(input.feedComponentMolarFlowsMolPerSecond()));
        o.addProperty("feedTemperatureKelvin", input.feedTemperatureKelvin());
        o.addProperty("stageCount", input.stageCount());
        o.addProperty("feedStageNumber", input.feedStageNumber());
        o.addProperty("topPressurePascal", input.topPressurePascal());
        o.addProperty("stagePressureDropPascal", input.stagePressureDropPascal());
        JsonArray specifications = new JsonArray();
        for (V3ColumnSpecification s : input.specifications()) specifications.add(gson.toJsonTree(s));
        o.add("specifications", specifications);
        o.add("sideDraws", new JsonArray());
        o.add("steamFeeds", new JsonArray());
        o.add("pumparounds", new JsonArray());
        o.addProperty("columnDiameterMetres", input.columnDiameterMetres());
        return o;
    }

    private InvalidBinaryRootCapture() {}
}
