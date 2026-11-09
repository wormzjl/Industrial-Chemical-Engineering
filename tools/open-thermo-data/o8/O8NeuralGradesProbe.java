package com.wormzjl.icheme.science.column.v3;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.science.material.TiaJuanaTestCatalog;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * O8 probe (batch 2026-11-04-open-thermo-data): the bundled regrouped model's learned seed against the classical start on
 * the four generated grades, the medium grade over O3's feed-temperature grid, and blends from Tia Juana toward each grade.
 * The model is forced past its admission: its sidecar gets one composition edge from Tia Juana to each grade, and its global
 * bounds are lifted. The learned route runs as LNN_ONLY with a 60 s budget, so iteration caps decide, not the 100 ms
 * large-wet probe of LNN_FIRST (src/main untouched). Copy to src/test/java/com/wormzjl/icheme/science/column/v3/, run
 * ./gradlew columnGates --offline --tests "*O8NeuralGradesProbe*" (or one method), read tmp/open-thermo-o8/*.json.
 */
class O8NeuralGradesProbe {
    static final Path SESSION = Path.of("D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/cleanup-stale-branches-worktrees-9bc4aa");
    static final Path O3_PRESETS = SESSION.resolve("tmp/open-thermo-o3/presets");
    static final Path OUT = SESSION.resolve("tmp/open-thermo-o8");
    static final String[] GRADES = {"light_crude", "medium_crude", "heavy_crude", "oil_sand_dilbit"};
    static final V3InitializationOptions LEARNED = new V3InitializationOptions(V3InitializationOptions.Mode.LNN_ONLY,
            V3InitializationOptions.WetStart.AUTO, 16, 60_000, V3InitializationOptions.Correction.PROGRESS);

    static MaterialCatalog catalog;
    static JsonObject originalSidecar;
    static V3NeuralInitializer forced;

    static synchronized void setUp() throws Exception {
        if (catalog != null) return;
        var resources = TiaJuanaTestCatalog.resources();
        try (var files = Files.list(O3_PRESETS)) {
            for (var f : files.sorted().toList()) resources.put("data/icheme/materials/presets/" + f.getFileName(), Files.readString(f));
        }
        catalog = MaterialCatalog.parse(resources);
        byte[] payload, sidecar;
        try (var p = O8NeuralGradesProbe.class.getResourceAsStream("/data/icheme/neural/v3-column-transformer-regrouped.payload.json");
             var s = O8NeuralGradesProbe.class.getResourceAsStream("/data/icheme/neural/v3-column-transformer-regrouped.sidecar.json")) {
            payload = p.readAllBytes(); sidecar = s.readAllBytes();
        }
        originalSidecar = JsonParser.parseString(new String(sidecar, StandardCharsets.UTF_8)).getAsJsonObject();
        var modified = originalSidecar.deepCopy();
        double[] tj = fractions(input("tia_juana"));
        for (String g : GRADES) {
            var edge = new JsonArray(); edge.add(array(tj)); edge.add(array(fractions(input(g))));
            modified.getAsJsonArray("compositionEdges").add(edge);
        }
        int width = modified.getAsJsonArray("globalMin").size();
        var lo = new JsonArray(); var hi = new JsonArray();
        for (int i = 0; i < width; i++) { lo.add(-1e12); hi.add(1e12); }
        modified.add("globalMin", lo); modified.add("globalMax", hi);
        var loaded = V3NeuralRegistry.TransformerArtifact.read(new ByteArrayInputStream(payload),
                new ByteArrayInputStream(modified.toString().getBytes(StandardCharsets.UTF_8)));
        forced = new V3NeuralRegistry(List.of(new V3NeuralRegistry.Entry(1, loaded.initializer(), loaded.pipelineSha256())));
    }

    static V3ColumnInput input(String preset) { return catalog.presets().column(preset).input(catalog); }

    static double[] fractions(V3ColumnInput i) {
        double[] f = i.feedComponentMolarFlowsMolPerSecond().clone(); double t = Arrays.stream(f).sum();
        for (int k = 0; k < f.length; k++) f[k] /= t;
        return f;
    }

    static JsonArray array(double[] v) { var a = new JsonArray(); for (double x : v) a.add(x); return a; }

    static V3ColumnInput atTemperature(V3ColumnInput i, double t) {
        return new V3ColumnInput(i.schemaVersion(), i.packageId(), i.assayId(), i.componentBasis(), i.feedComponentMolarFlowsMolPerSecond(), t,
                i.stageCount(), i.feedStageNumber(), i.topPressurePascal(), i.stagePressureDropPascal(), i.specifications(),
                i.sideDraws(), i.steamFeeds(), i.pumparounds(), i.columnDiameterMetres());
    }

    /** w standard-volume share of grade g (both presets carry the same standard volume), specifications linear in w. */
    static V3ColumnInput blend(V3ColumnInput a, V3ColumnInput b, double w) {
        double[] fa = a.feedComponentMolarFlowsMolPerSecond(), fb = b.feedComponentMolarFlowsMolPerSecond(), f = new double[fa.length];
        for (int k = 0; k < f.length; k++) f[k] = (1 - w) * fa[k] + w * fb[k];
        var draws = new ArrayList<V3SideDrawSpec>();
        for (int k = 0; k < a.sideDraws().size(); k++) {
            var da = a.sideDraws().get(k); var db = b.sideDraws().get(k);
            if (da.trayNumber() != db.trayNumber()) throw new IllegalStateException("draw trays differ");
            draws.add(new V3SideDrawSpec(da.trayNumber(), (1 - w) * da.molarFlowMolPerSecond() + w * db.molarFlowMolPerSecond()));
        }
        var steam = new ArrayList<V3SteamFeedSpec>();
        for (int k = 0; k < a.steamFeeds().size(); k++) {
            var sa = a.steamFeeds().get(k); var sb = b.steamFeeds().get(k);
            steam.add(new V3SteamFeedSpec(sa.stageNumber(), (1 - w) * sa.molarFlowMolPerSecond() + w * sb.molarFlowMolPerSecond(), sa.temperatureKelvin()));
        }
        var pas = new ArrayList<V3PumparoundSpec>();
        for (int k = 0; k < a.pumparounds().size(); k++) {
            var pa = a.pumparounds().get(k); var pb = b.pumparounds().get(k);
            pas.add(new V3PumparoundSpec(pa.returnTray(), pa.drawTray(), (1 - w) * pa.dutyWatts() + w * pb.dutyWatts(), pa.split()));
        }
        if (!a.specifications().equals(b.specifications()) || a.feedTemperatureKelvin() != b.feedTemperatureKelvin()
                || a.stageCount() != b.stageCount() || a.feedStageNumber() != b.feedStageNumber() || a.topPressurePascal() != b.topPressurePascal())
            throw new IllegalStateException("operating basis differs");
        return new V3ColumnInput(a.schemaVersion(), a.packageId(), a.assayId(), a.componentBasis(), f, a.feedTemperatureKelvin(),
                a.stageCount(), a.feedStageNumber(), a.topPressurePascal(), a.stagePressureDropPascal(), a.specifications(),
                draws, steam, pas, a.columnDiameterMetres());
    }

    /** The bundled model's own admission of the request: its binding, and which global bounds the request leaves. */
    static JsonObject gates(V3ColumnInput input) {
        var o = new JsonObject();
        o.addProperty("bundledBinds", MaterialRuntime.with(catalog, input.packageId(), () -> V3NeuralRegistry.bundled().bind(catalog, input).modelId()));
        double[] g = V3GeneralNeuralFeatures.global(input);
        var min = originalSidecar.getAsJsonArray("globalMin"); var max = originalSidecar.getAsJsonArray("globalMax");
        var out = new JsonArray();
        for (int i = 0; i < g.length; i++) {
            double a = min.get(i).getAsDouble(), b = max.get(i).getAsDouble(), slack = 1e-9 * Math.max(1, Math.max(Math.abs(a), Math.abs(b)));
            if (g[i] < a - slack || g[i] > b + slack) out.add(String.format("x%d=%.4g [%.4g,%.4g]", i, g[i], a, b));
        }
        o.add("globalOut", out);
        o.add("fractions", array(fractions(input)));
        return o;
    }

    static JsonObject run(String label, V3ColumnInput input, boolean learned) { return run(label, input, learned, forced); }

    static JsonObject run(String label, V3ColumnInput input, boolean learned, V3NeuralInitializer model) {
        var polls = new java.util.concurrent.atomic.AtomicLong();
        long start = System.nanoTime();
        var outcome = MaterialRuntime.with(catalog, input.packageId(), () -> V3ColumnCalculator.calculate(input, polls::incrementAndGet, 0, 0,
                learned ? LEARNED : V3InitializationOptions.CURRENT, learned ? model : V3NeuralInitializer.UNAVAILABLE,
                V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO));
        var o = new JsonObject();
        o.addProperty("case", label);
        o.addProperty("route", learned ? "learned" : "classical");
        o.addProperty("success", outcome instanceof V3ColumnOutcome.Success);
        o.addProperty("controlPolls", polls.get());
        o.addProperty("wallMsInformative", (System.nanoTime() - start) / 1e6);
        var d = outcome.diagnostics();
        o.addProperty("initializerIterations", d.initializerIterations());
        o.addProperty("newtonIterations", d.newtonIterations());
        o.addProperty("residualEvaluations", d.residualEvaluations());
        o.addProperty("maximumScaledResidual", d.maximumScaledResidual());
        o.addProperty("solvePath", d.solvePath());
        if (outcome instanceof V3ColumnOutcome.Failure f) { o.addProperty("code", f.code().name()); o.addProperty("summary", f.summary()); }
        var events = new JsonArray();
        d.events().stream().limit(4).map(e -> e.length() > 400 ? e.substring(0, 400) : e).forEach(events::add);
        o.add("events", events);
        System.out.println("o8 " + label + " " + o.get("route").getAsString() + ": success=" + o.get("success") + " polls=" + polls.get()
                + " newton=" + d.newtonIterations() + " path=" + d.solvePath() + (d.events().isEmpty() ? "" : " ev0=" + events.get(0).getAsString()));
        return o;
    }

    static void write(String name, JsonObject o) throws Exception {
        Files.createDirectories(OUT);
        Files.writeString(OUT.resolve(name), new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create().toJson(o));
    }

    /** (a) the four grade templates and Tia Juana, learned only (classical counters stored by O3 and O7). */
    @Test void grades() throws Exception {
        setUp();
        var out = new JsonObject(); var runs = new JsonArray(); var feeds = new JsonObject();
        for (String p : new String[] {"tia_juana", GRADES[0], GRADES[1], GRADES[2], GRADES[3]}) {
            feeds.add(p, gates(input(p)));
            runs.add(run(p, input(p), true));
            write("grades.json", out);
        }
        var ring = originalSidecar.getAsJsonArray("compositionEdges");
        out.add("ringEdges", ring); out.add("feeds", feeds); out.add("runs", runs);
        write("grades.json", out);
    }

    /** (b) the medium grade over O3's feed-temperature grid and Tia Juana on the same grid, learned (classical counters stored by O3); one Tia Juana run on the bundled, unforced model checks that forcing changes no prediction. */
    @Test void grid() throws Exception {
        setUp();
        double[] temps = {622, 626, 630, 631.28, 633, 634, 636, 638.15, 640, 644};
        var out = new JsonObject(); var runs = new JsonArray(); var feeds = new JsonObject();
        out.add("runs", runs); out.add("feeds", feeds);
        for (double t : temps) {
            var med = atTemperature(input("medium_crude"), t);
            feeds.add("medium@" + t, gates(med));
            runs.add(run("medium@" + t, med, true));
            write("grid.json", out);
        }
        var check = run("tia_juana-bundled", input("tia_juana"), true, V3NeuralRegistry.bundled());
        out.add("bundledCheck", check);
        for (double t : temps) {
            if (t == 638.15) continue;
            var tj = atTemperature(input("tia_juana"), t);
            runs.add(run("tia_juana@" + t, tj, true));
            write("grid.json", out);
        }
    }

    /** (c) blends from Tia Juana toward each grade, both routes. */
    @Test void blends() throws Exception {
        setUp();
        var out = new JsonObject(); var runs = new JsonArray(); var feeds = new JsonObject();
        out.add("runs", runs); out.add("feeds", feeds);
        var tj = input("tia_juana");
        for (String g : GRADES) for (double w : new double[] {0.25, 0.5, 0.75}) {
            var b = blend(tj, input(g), w);
            String label = g + "@" + w;
            feeds.add(label, gates(b));
            runs.add(run(label, b, true));
            runs.add(run(label, b, false));
            write("blends.json", out);
        }
    }
    /** The learned seed itself (no correction): its condenser branch, temperatures, free water and vapour leaving the top, against O3's stored classical profile. */
    @Test void seeds() throws Exception {
        setUp();
        var out = new JsonObject();
        for (String p : new String[] {"tia_juana", GRADES[0], GRADES[1], GRADES[2], GRADES[3]}) {
            var in = input(p);
            var seeds = MaterialRuntime.with(catalog, in.packageId(), () -> forced.bind(catalog, in).candidates(in, () -> {}));
            var o = new JsonObject();
            for (var seed : seeds) {
                o.addProperty("branch", seed.branch().name());
                o.add("T", array(seed.temperatures()));
                o.add("freeWater", array(seed.freeWater()));
                double[][] v = seed.vapor(), l = seed.liquid();
                double[] vt = new double[v.length], lt = new double[l.length];
                for (int n = 0; n < v.length; n++) { for (double x : v[n]) vt[n] += x; for (double x : l[n]) lt[n] += x; }
                o.add("V", array(vt)); o.add("L", array(lt));
                o.add("V0", array(v[0])); o.add("L0", array(l[0]));
            }
            o.addProperty("candidates", seeds.size());
            System.out.println("o8 seed " + p + ": " + seeds.size() + " " + (seeds.isEmpty() ? "" : seeds.get(0).branch()));
            out.add(p, o);
        }
        write("seeds.json", out);
    }
}
