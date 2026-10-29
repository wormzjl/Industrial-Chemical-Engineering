package com.wormzjl.icheme.science.column.v3;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Observation only, compiled with temporary instrumented source copies by trace.ps1. */
final class VacuumTrace {
    private static final Gson JSON = new Gson();
    private static int sequence;
    private VacuumTrace() {}

    static void state(String event, V3ColumnProblem problem, V3DryMeshState state, String detail) {
        var row = base(event, problem);
        row.put("detail", detail);
        row.put("temperature", temperatures(state));
        double[][] liquid = new double[state.nodeCount()][state.componentCount()];
        double[][] vapor = new double[state.nodeCount()][state.componentCount()];
        for (int n = 0; n < state.nodeCount(); n++) for (int c = 0; c < state.componentCount(); c++) {
            liquid[n][c] = state.liquidFlow(n,c); vapor[n][c] = state.vaporFlow(n,c);
        }
        row.put("liquid", liquid);
        row.put("vapor", vapor);
        emit(row);
    }

    static void iteration(V3ColumnProblem problem, V3DryMeshState state, V3MeshResidual residual, int iteration) {
        if (iteration != 0 && iteration % 5 != 0) return;
        var row = base("iteration", problem);
        row.put("iteration", iteration);
        row.put("maximumScaledResidual", residual.maximumAbsoluteScaledResidual());
        var worst = residual.rows().stream().max(java.util.Comparator.comparingDouble(r -> Math.abs(r.scaledValue()))).orElseThrow();
        row.put("worst", worst);
        row.put("temperature", temperatures(state));
        var traffic = new ArrayList<double[]>();
        for (int n = 0; n < state.nodeCount(); n++) {
            double liquid = 0, vapor = 0;
            for (int c = 0; c < state.componentCount(); c++) { liquid += state.liquidFlow(n,c); vapor += state.vaporFlow(n,c); }
            traffic.add(new double[] {liquid, vapor});
        }
        row.put("traffic", traffic);
        emit(row);
    }

    static void search(double merit, double candidateMerit, double step, String error) {
        if (step != 1.0 && step > Math.scalb(1.0, -19)) return;
        var row = new LinkedHashMap<String,Object>();
        row.put("event", "search");
        row.put("baseMerit", Double.toString(merit));
        row.put("trialMerit", Double.toString(candidateMerit));
        row.put("step", step);
        row.put("error", error);
        emit(row);
    }

    private static Map<String,Object> base(String event, V3ColumnProblem problem) {
        var row = new LinkedHashMap<String,Object>();
        row.put("event", event);
        row.put("stages", problem.input().stageCount());
        row.put("feedStage", problem.input().feedStageNumber());
        row.put("pressure", problem.input().topPressurePascal());
        row.put("feedT", problem.input().feedTemperatureKelvin());
        row.put("specs", problem.input().specifications());
        row.put("activeComponents", java.util.stream.IntStream.range(0, problem.activeComponentBasis().componentCount())
                .mapToObj(i -> problem.input().componentBasis().componentId(problem.activeComponentBasis().publicIndex(i))).toList());
        return row;
    }

    private static double[] temperatures(V3DryMeshState state) {
        return java.util.stream.IntStream.range(0,state.nodeCount()).mapToDouble(state::temperatureKelvin).toArray();
    }

    private static void emit(Map<String,Object> row) {
        row.put("sequence", sequence++);
        try {
            Files.writeString(Path.of(System.getProperty("vacuum.traceFile")), JSON.toJson(row) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
    }
}
