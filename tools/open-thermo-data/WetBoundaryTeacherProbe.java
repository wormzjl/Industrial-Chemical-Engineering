package com.wormzjl.icheme.science.column.v3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Throwaway probe of batch 2026-11-04-open-thermo-data (O7, step 3, O2 decision 7): re-makes the test fixture
 * {@code science/column/v3/neural/tjl20-wet-boundary.json} the way it was made on 2026-09-10, by the offline teacher
 * {@code tools/neural/V3WetBoundaryTeacher.java} ported to the current API: (1) a classical solve of the methane preset at a
 * 338.15 K (else 332.15 K) condenser; (2) a parametric continuation that holds 1 mol/s of free water on tray 1 and bisects the
 * condenser outlet temperature until tray 1's vapour is exactly saturated with water (27 refinements); (3) the release: the
 * selected profile as a fixed seed through LNN_ONLY with PREDICTED_WET (16 iterations, as the test; the teacher's 128 is
 * refused by today's options, a correction extension may not lower the base cap), accepted only as a WET_EQUILIBRIUM certificate. No
 * neural model takes part. It runs where the test reads the fixture: under {@code TjlReferenceExtension} (the frozen 20-compound
 * TJL20 reference package with the bundled water; O2's WetBoundaryCaptureProbe ran on the bundled 19-compound package instead). Prints each step's tray-1 saturation ratio and, per tray of the released profile, the water dew
 * point margin; writes {@code tmp/open-thermo-o7/tjl20-wet-boundary.json} and {@code tmp/open-thermo-o7/wet-boundary.json}.
 * Never committed under {@code src/test}: copy it to {@code src/test/java/com/wormzjl/icheme/science/column/v3/}, run
 * {@code ./gradlew columnGates --offline --tests com.wormzjl.icheme.science.column.v3.WetBoundaryTeacherProbe}, move it back.
 */
@org.junit.jupiter.api.extension.ExtendWith(com.wormzjl.icheme.science.material.TjlReferenceExtension.class)
class WetBoundaryTeacherProbe {
    private static final String FIXTURE = "/science/column/v3/neural/tjl20-wet-boundary.json";
    private final JsonArray trace = new JsonArray();

    private record Parametric(V3NeuralSeed profile, double ratio) {}

    private static double condenser(V3ColumnInput input) {
        return input.specifications().stream().filter(s -> s instanceof V3ColumnSpecification.CondenserOutletTemperature)
                .mapToDouble(s -> ((V3ColumnSpecification.CondenserOutletTemperature) s).kelvin()).findFirst().orElseThrow();
    }

    private static V3ColumnInput withTemperature(V3ColumnInput base, double temperature) {
        return new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(),
                base.feedComponentMolarFlowsMolPerSecond(), base.feedTemperatureKelvin(), base.stageCount(),
                base.feedStageNumber(), base.topPressurePascal(), base.stagePressureDropPascal(),
                base.specifications().stream().map(s -> s instanceof V3ColumnSpecification.CondenserOutletTemperature
                        ? (V3ColumnSpecification) new V3ColumnSpecification.CondenserOutletTemperature(temperature) : s).toList(),
                base.sideDraws(), base.steamFeeds(), base.pumparounds(), base.columnDiameterMetres());
    }

    private static Parametric parametric(V3ColumnInput input, V3NeuralSeed snapshot, V3PengRobinsonThermo thermo, double hf) {
        double[] temperatures = snapshot.temperatures(); temperatures[0] = condenser(input);
        double[] water = snapshot.freeWater(); water[1] = 1;
        boolean[] mask = snapshot.wetTrays(); mask[1] = true;
        var seed = new V3NeuralSeed(input, snapshot.propertyRevision(), snapshot.branch(), snapshot.liquid(), snapshot.vapor(), temperatures, water, mask);
        var full = V3ColumnProblemResolver.resolve(input, seed.branch());
        var set = V3WetTraySet.parametric(full.topology(), mask, water);
        var state = seed.stateFor(full);
        var problem = V3ColumnProblemResolver.withTruncation(full, V3TruncationSupport.derive(full, 0, state), set);
        var attempt = V3SimultaneousColumnSolver.solve(problem, new V3MeshResidualEvaluator(problem, thermo, hf), new V3DryMeshCoordinateMap(problem),
                set.seed(problem, state), thermo::newWorkspace, V3ConvergenceEvidence.unavailable(), 64, 1e-8,
                V3FiniteDifferenceJacobian.DifferenceScale.FINE, V3SolveControl.UNBOUNDED);
        if (!(attempt instanceof V3SimultaneousColumnSolver.Attempt.Converged solved)) return null;
        double ratio = V3WetTraySet.saturation(problem, solved.state(), 1, problem.waterVaporFlow(solved.state(), 1)).ratio();
        return new Parametric(V3NeuralSeed.capture(problem, solved.state(), seed.propertyRevision()), ratio);
    }

    private void note(String step, double tc, Object value) {
        var o = new JsonObject(); o.addProperty("step", step); o.addProperty("condenserKelvin", tc); o.addProperty("value", String.valueOf(value));
        trace.add(o); System.out.println("wet boundary " + step + " tc " + tc + ": " + value);
    }

    /** Per tray of a profile: temperature, free water, water vapour mole fraction, pressure and the water dew point margin. */
    private static JsonArray dewPoints(V3NeuralSeed seed) {
        var full = V3ColumnProblemResolver.resolve(seed.input(), seed.branch());
        var rows = new JsonArray();
        for (int n = 1; n <= seed.input().stageCount() && n < 12; n++) {
            double t = seed.temperatures()[n], p = full.nodePressurePascal(n);
            double hydrocarbon = java.util.Arrays.stream(seed.vapor()[n]).sum();
            var state = seed.stateFor(full);
            double water = full.waterVaporFlow(state, n);
            double y = water / (water + hydrocarbon);
            double psat = com.wormzjl.icheme.science.column.v3.thermo.V3WaterProperties.saturationPressurePascal(t);
            double tdew = com.wormzjl.icheme.science.column.v3.thermo.V3WaterProperties.saturationTemperatureKelvin(y * p);
            var o = new JsonObject();
            o.addProperty("tray", n); o.addProperty("temperatureKelvin", t); o.addProperty("pressurePascal", p);
            o.addProperty("freeWaterMolPerSecond", seed.freeWater()[n]); o.addProperty("waterVapourFraction", y);
            o.addProperty("partialOverSaturation", y * p / psat); o.addProperty("waterDewPointKelvin", tdew); o.addProperty("marginKelvin", t - tdew);
            rows.add(o);
        }
        return rows;
    }

    @Test
    void reMakeTheWetBoundaryFixture() throws Exception {
        var out = new JsonObject();
        var base = V3TestInputs.methaneCduInput();
        V3NeuralSeed[] captured = {null};
        var coldInput = withTemperature(base, 338.15);
        var cold = V3ColumnCalculator.calculateWithAcceptedProfile(coldInput, V3SolveControl.UNBOUNDED, s -> captured[0] = s);
        if (!cold.isSuccess()) {
            coldInput = withTemperature(base, 332.15);
            cold = V3ColumnCalculator.calculateWithAcceptedProfile(coldInput, V3SolveControl.UNBOUNDED, s -> captured[0] = s);
        }
        note("classical", condenser(coldInput), cold.isSuccess() ? "success" : cold.toString());
        if (!cold.isSuccess()) { write(out, null); return; }
        out.add("classicalDewPoints", dewPoints(captured[0]));
        var thermo = V3PengRobinsonThermo.fromRegisteredPackage(base.packageId());
        var original = V3ColumnProblemResolver.resolve(base, captured[0].branch());
        double hf = thermo.flashTP(base.feedTemperatureKelvin(), original.nodePressurePascal(base.feedStageNumber()),
                base.feedComponentMolarFlowsMolPerSecond(), thermo.newWorkspace()).molarEnthalpyJoulesPerMol();
        double low = Double.NaN, high = Double.NaN, tc = condenser(coldInput), bestError = Double.POSITIVE_INFINITY;
        V3NeuralSeed snapshot = captured[0], best = null;
        int refinements = 0;
        String failure = null;
        for (int iteration = 0; iteration < 60; iteration++) {
            var input = withTemperature(base, tc);
            var solved = parametric(input, snapshot, thermo, hf);
            if (solved == null) {
                captured[0] = null;
                V3ColumnCalculator.calculateWithAcceptedProfile(input, V3SolveControl.UNBOUNDED, s -> captured[0] = s);
                if (captured[0] != null) solved = parametric(input, captured[0], thermo, hf);
                if (solved == null) { failure = "parametric continuation failed at " + tc; break; }
            }
            note("parametric", tc, "tray-1 saturation ratio " + solved.ratio());
            snapshot = solved.profile();
            if (Math.abs(solved.ratio() - 1) < bestError) { bestError = Math.abs(solved.ratio() - 1); best = snapshot; }
            if (solved.ratio() > 1) low = tc; else high = tc;
            if (Double.isFinite(low) && Double.isFinite(high)) {
                if (++refinements >= 27) break;
                tc = (low + high) * .5;
            } else tc += solved.ratio() > 1 ? 2 : -2;
            if (tc < 313.15 || tc > 348.15) { failure = "wet boundary outside the declared condenser range at " + tc; break; }
        }
        if (failure == null && (!Double.isFinite(low) || !Double.isFinite(high))) failure = "wet boundary was not bracketed";
        if (failure != null) { note("failed", tc, failure); write(out, null); return; }
        var selected = best;
        out.add("selectedDewPoints", dewPoints(selected));
        V3NeuralInitializer fixed = new V3NeuralInitializer() {
            public String modelId() { return "offline-wet-boundary-teacher"; }
            public Optional<V3NeuralSeed> predict(V3ColumnInput input, V3SolveControl c) { c.checkpoint(); return Optional.of(selected); }
        };
        captured[0] = null;
        var result = V3ColumnCalculator.calculateWithAcceptedProfile(selected.input(), V3SolveControl.UNBOUNDED,
                new V3InitializationOptions(V3InitializationOptions.Mode.LNN_ONLY, V3InitializationOptions.WetStart.PREDICTED_WET, 16, 10_000,
                        V3InitializationOptions.Correction.PROGRESS), fixed, s -> captured[0] = s);
        var grade = captured[0] == null ? null : V3WaterPhaseQualification.assess(captured[0]).grade();
        note("release", condenser(selected.input()), result.isSuccess() + ", grade " + grade + (result.isSuccess() ? "" : ", " + result));
        if (captured[0] != null) out.add("releasedDewPoints", dewPoints(captured[0]));
        if (!result.isSuccess() || grade != V3WaterPhaseQualification.Grade.WET_EQUILIBRIUM) { write(out, null); return; }
        // The fixture, as O2's WetBoundaryCaptureProbe writes it: the condenser temperature, the released profile, its audit.
        String text;
        try (var in = getClass().getResourceAsStream(FIXTURE)) { text = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        var json = JsonParser.parseString(text).getAsJsonObject();
        double old = json.getAsJsonObject("input").getAsJsonArray("specifications").get(0).getAsJsonObject().get("kelvin").getAsDouble();
        double now = condenser(selected.input());
        var seed = captured[0];
        var gson = new Gson();
        json.getAsJsonObject("input").getAsJsonArray("specifications").get(0).getAsJsonObject().addProperty("kelvin", now);
        var s = json.getAsJsonObject("seed");
        s.getAsJsonObject("input").getAsJsonArray("specifications").get(0).getAsJsonObject().addProperty("kelvin", now);
        s.addProperty("propertyRevision", seed.propertyRevision());
        s.addProperty("branch", seed.branch().name());
        s.add("liquid", gson.toJsonTree(seed.liquid()));
        s.add("vapor", gson.toJsonTree(seed.vapor()));
        s.add("temperatures", gson.toJsonTree(seed.temperatures()));
        s.add("freeWater", gson.toJsonTree(seed.freeWater()));
        s.add("wetTrays", gson.toJsonTree(seed.wetTrays()));
        var accepted = ((V3ColumnOutcome.Success) result).result();
        json.add("acceptanceAudit", gson.toJsonTree(accepted.acceptanceAudit()));
        json.add("convergenceEvidence", gson.toJsonTree(accepted.convergenceEvidence()));
        json.addProperty("provenance", json.get("provenance").getAsString() + " Re-made 2026-11-04 on the open thermo data (batch"
                + " 2026-11-04-open-thermo-data O7) by the same teacher (tools/open-thermo-data/WetBoundaryTeacherProbe.java, a port of"
                + " tools/neural/V3WetBoundaryTeacher.java): condenser outlet " + old + " -> " + now + " K, tray-1 free water "
                + seed.freeWater()[1] + " mol/s.");
        write(out, json);
    }

    private void write(JsonObject out, JsonObject fixture) throws Exception {
        out.add("trace", trace);
        var dir = Path.of("tmp/open-thermo-o7");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("wet-boundary.json"), new GsonBuilder().setPrettyPrinting().create().toJson(out));
        if (fixture != null) Files.writeString(dir.resolve("tjl20-wet-boundary.json"), new GsonBuilder().create().toJson(fixture));
    }
}
