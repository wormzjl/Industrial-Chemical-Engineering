package com.wormzjl.icheme.science.column.v3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Throwaway probe of batch 2026-11-04-open-thermo-data (O2): re-captures the test fixture
 * {@code science/column/v3/neural/tjl20-wet-boundary.json} on the current data the way it was made (2026-09-10): the
 * classical methane preset whose condenser outlet temperature puts exactly 1 mol/s of free water on tray 1, the released
 * wet-state certificate the solver exports. Bisects the condenser temperature on the accepted profile's tray-1 free water
 * and writes {@code tmp/open-thermo-nist/tjl20-wet-boundary.json} (the fixture with the new temperature, seed and
 * property revision). Never committed under {@code src/test}: copy it to
 * {@code src/test/java/com/wormzjl/icheme/science/column/v3/}, run
 * {@code ./gradlew columnGates --offline --tests com.wormzjl.icheme.science.column.v3.WetBoundaryCaptureProbe}, move it back.
 */
class WetBoundaryCaptureProbe {
    private static final String FIXTURE = "/science/column/v3/neural/tjl20-wet-boundary.json";

    private static V3ColumnInput at(double tc) {
        var base = V3TestInputs.methaneCduInput();
        return new V3ColumnInput(base.schemaVersion(), base.packageId(), base.assayId(), base.componentBasis(),
                base.feedComponentMolarFlowsMolPerSecond(), base.feedTemperatureKelvin(), base.stageCount(),
                base.feedStageNumber(), base.topPressurePascal(), base.stagePressureDropPascal(),
                base.specifications().stream().map(s -> s instanceof V3ColumnSpecification.CondenserOutletTemperature
                        ? (V3ColumnSpecification) new V3ColumnSpecification.CondenserOutletTemperature(tc) : s).toList(),
                base.sideDraws(), base.steamFeeds(), base.pumparounds(), V3ColumnInput.PRESCRIBED_DROP_DIAMETER);
    }

    private record Capture(double tc, V3NeuralSeed seed, V3ColumnOutcome outcome) {
        double water() { return seed == null ? Double.NaN : seed.freeWater()[1]; }
    }

    private static Capture solve(double tc) {
        var captured = new AtomicReference<V3NeuralSeed>();
        var outcome = V3ColumnCalculator.calculateWithAcceptedProfile(at(tc), () -> {}, captured::set);
        var c = new Capture(tc, outcome instanceof V3ColumnOutcome.Success ? captured.get() : null, outcome);
        System.out.println("wet boundary tc " + tc + ": tray-1 free water " + c.water() + " mol/s, " + (c.seed == null ? outcome : "wet trays "
                + java.util.stream.IntStream.range(0, c.seed.wetTrays().length).filter(i -> c.seed.wetTrays()[i]).boxed().toList()));
        return c;
    }

    @Test
    void recaptureTheWetBoundaryFixture() throws Exception {
        String text;
        try (var in = getClass().getResourceAsStream(FIXTURE)) { text = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        var json = JsonParser.parseString(text).getAsJsonObject();
        double old = json.getAsJsonObject("input").getAsJsonArray("specifications").get(0).getAsJsonObject().get("kelvin").getAsDouble();
        Capture low = null, high = null;
        for (double d : new double[] {0, -0.05, 0.05, -0.2, 0.2, -0.5, 0.5, -1, 1}) {
            var c = solve(old + d);
            if (Double.isNaN(c.water())) continue;
            if (c.water() <= 1 && (low == null || Math.abs(c.tc - old) < Math.abs(low.tc - old))) low = c;
            if (c.water() > 1 && (high == null || Math.abs(c.tc - old) < Math.abs(high.tc - old))) high = c;
            if (low != null && high != null) break;
        }
        if (low == null || high == null) throw new IllegalStateException("no bracket around 1 mol/s");
        Capture best = Math.abs(low.water() - 1) < Math.abs(high.water() - 1) ? low : high;
        for (int k = 0; k < 60 && Math.abs(best.water() - 1) > 1e-10; k++) {
            // Regula falsi with the Illinois safeguard, on the accepted profile's tray-1 free water.
            double tc = low.tc + (1 - low.water()) * (high.tc - low.tc) / (high.water() - low.water());
            if (!(tc > Math.min(low.tc, high.tc) && tc < Math.max(low.tc, high.tc))) tc = 0.5 * (low.tc + high.tc);
            var c = solve(tc);
            if (Double.isNaN(c.water())) { c = solve(0.5 * (low.tc + high.tc)); if (Double.isNaN(c.water())) throw new IllegalStateException("no solution"); }
            if (c.water() <= 1) low = c; else high = c;
            if (Math.abs(c.water() - 1) < Math.abs(best.water() - 1)) best = c;
        }
        var seed = best.seed;
        var gson = new Gson();
        json.getAsJsonObject("input").getAsJsonArray("specifications").get(0).getAsJsonObject().addProperty("kelvin", best.tc);
        var s = json.getAsJsonObject("seed");
        s.getAsJsonObject("input").getAsJsonArray("specifications").get(0).getAsJsonObject().addProperty("kelvin", best.tc);
        s.addProperty("propertyRevision", seed.propertyRevision());
        s.addProperty("branch", seed.branch().name());
        s.add("liquid", gson.toJsonTree(seed.liquid()));
        s.add("vapor", gson.toJsonTree(seed.vapor()));
        s.add("temperatures", gson.toJsonTree(seed.temperatures()));
        s.add("freeWater", gson.toJsonTree(seed.freeWater()));
        s.add("wetTrays", gson.toJsonTree(seed.wetTrays()));
        var result = ((V3ColumnOutcome.Success) best.outcome).result();
        json.add("acceptanceAudit", gson.toJsonTree(result.acceptanceAudit()));
        json.add("convergenceEvidence", gson.toJsonTree(result.convergenceEvidence()));
        json.addProperty("provenance", json.get("provenance").getAsString() + " Re-captured 2026-11-04 on the open ideal-gas data (batch"
                + " 2026-11-04-open-thermo-data O2) by tools/open-thermo-data/WetBoundaryCaptureProbe.java: condenser outlet " + old + " -> "
                + best.tc + " K, tray-1 free water " + best.water() + " mol/s.");
        var out = Path.of("tmp/open-thermo-nist/tjl20-wet-boundary.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, new GsonBuilder().create().toJson(json));
        System.out.println("wet boundary captured: tc " + old + " -> " + best.tc + ", free water " + best.water());
    }
}
