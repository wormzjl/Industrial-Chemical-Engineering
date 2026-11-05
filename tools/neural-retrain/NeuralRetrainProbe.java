package com.wormzjl.icheme.science.column.v3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.io.ByteArrayInputStream;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Batch 2026-11-05-neural-retrain, N0 and N1 (NEURAL_RETRAIN_PLAN.md, NEURAL_RETRAIN_DATA.md). Offline only, on the bundled
 * catalog plus the four grade presets of tools/neural-retrain/presets/ (no Tia Juana overlay).
 * <ul>
 * <li>{@code labels}: realises each design row of NR_DESIGN on the grade presets, applies the generation screens (request-only
 * admission at 0.30, the energy-limited draw screen sigma, the degenerate family), solves it CURRENT_ONLY under a control-poll
 * cap and journals every row, failures included, with the accepted profile and its strict evidence.</li>
 * <li>{@code features}: for NR_CASES rows (or the four presets), the bundled model's inputs and raw outputs (global and node
 * features, native anchor, branch logits, raw values): the parity reference for the Python twin (tools/neural-retrain/model.py).</li>
 * </ul>
 * Copy to src/test/java/com/wormzjl/icheme/science/column/v3/, then e.g.
 * {@code NR_DESIGN=tmp/neural-retrain/design/pilot.jsonl NR_OUT=tmp/neural-retrain/pilot NR_WORKERS=4 ./gradlew columnGates --offline --tests "*NeuralRetrainProbe.labels" --max-workers=4},
 * and move it back.
 */
class NeuralRetrainProbe {
    static final Gson JSON = new GsonBuilder().serializeSpecialFloatingPointValues().create();
    static final String[] GRADES = {"light_crude", "medium_crude", "heavy_crude", "oil_sand_dilbit"};
    static final Path PRESETS = Path.of("tools/neural-retrain/presets");
    /** Control-poll cap of one classical solve: 4 M polls, about 24 s at 165 polls/ms; O8's slowest classical run was 1.76 M. */
    static final long POLL_CAP = 4_000_000L;
    static final double SCREEN_RATIO = 0.30, SIGMA_LIMIT = 0.66, LAMBDA = 60_000.0;

    static MaterialCatalog catalog;
    static V3ColumnInput[] base = new V3ColumnInput[GRADES.length];

    static synchronized void setUp() throws Exception {
        if (catalog != null) return;
        var resources = new LinkedHashMap<>(MaterialCatalog.bundled().resources());
        for (String g : GRADES) resources.put("data/icheme/materials/presets/column_" + g + ".json", Files.readString(PRESETS.resolve("column_" + g + ".json")));
        catalog = MaterialCatalog.parse(resources);
        for (int i = 0; i < GRADES.length; i++) base[i] = catalog.presets().column(GRADES[i]).input(catalog);
    }

    static String env(String name, String fallback) { String v = System.getenv(name); return v == null || v.isBlank() ? fallback : v; }

    // ---------------------------------------------------------------- realisation of a design row

    static int scaled(int tray, int stages) { return Math.max(1, Math.min(stages, (int) Math.round(tray * stages / 40.0))); }

    static V3ColumnInput realise(JsonObject row) {
        var c = row.getAsJsonObject("composition");
        double[] w = JSON.fromJson(c.get("weights"), double[].class);
        if (row.getAsJsonObject("operating").has("preset")) { for (int i = 0; i < w.length; i++) if (w[i] == 1) return base[i]; }
        double[] m = JSON.fromJson(c.get("multipliers"), double[].class);
        int[] zero = JSON.fromJson(c.get("zero"), int[].class);
        var o = row.getAsJsonObject("operating");
        var b0 = base[0];
        int comps = b0.feedComponentMolarFlowsMolPerSecond().length, stages = o.get("stages").getAsInt();
        double rate = o.get("feedRateFactor").getAsDouble();
        // Standard-volume blend of the grades (every preset carries the same standard volume), then the perturbation.
        double[] feed = new double[comps]; double blended = 0;
        for (int g = 0; g < GRADES.length; g++) for (int k = 0; k < comps; k++) feed[k] += w[g] * base[g].feedComponentMolarFlowsMolPerSecond()[k];
        for (double f : feed) blended += f;
        for (int k = 0; k < comps; k++) feed[k] *= m[k];
        for (int k : zero) feed[k] = 0;
        double total = Arrays.stream(feed).sum();
        for (int k = 0; k < comps; k++) feed[k] *= blended / total * rate;
        // Per-feed setpoints of the blend, times their factors and the turndown.
        int drop = o.get("dropDraw").getAsInt(), dropPa = o.get("dropPumparound").getAsInt();
        double[] df = JSON.fromJson(o.get("drawFactors"), double[].class), pf = JSON.fromJson(o.get("pumparoundFactors"), double[].class);
        var draws = new ArrayList<V3SideDrawSpec>();
        for (int k = 0; k < b0.sideDraws().size(); k++) {
            if (k == drop) continue;
            double flow = 0; for (int g = 0; g < GRADES.length; g++) flow += w[g] * base[g].sideDraws().get(k).molarFlowMolPerSecond();
            draws.add(new V3SideDrawSpec(scaled(b0.sideDraws().get(k).trayNumber(), stages), flow * df[k] * rate));
        }
        var steam = new ArrayList<V3SteamFeedSpec>();
        for (int k = 0; k < b0.steamFeeds().size(); k++) {
            double flow = 0; for (int g = 0; g < GRADES.length; g++) flow += w[g] * base[g].steamFeeds().get(k).molarFlowMolPerSecond();
            steam.add(new V3SteamFeedSpec(stages + 1, flow * o.get("steamFactor").getAsDouble() * rate, o.get("steamTemperatureKelvin").getAsDouble()));
        }
        var pas = new ArrayList<V3PumparoundSpec>();
        for (int k = 0; k < b0.pumparounds().size(); k++) {
            if (k == dropPa) continue;
            var p = b0.pumparounds().get(k);
            double duty = 0; for (int g = 0; g < GRADES.length; g++) duty += w[g] * base[g].pumparounds().get(k).dutyWatts();
            int drawTray = scaled(p.drawTray(), stages), returnTray = Math.max(1, drawTray - (p.drawTray() - p.returnTray()));
            pas.add(new V3PumparoundSpec(returnTray, drawTray, duty * pf[k] * rate, p.split()));
        }
        var specs = List.<V3ColumnSpecification>of(
                new V3ColumnSpecification.CondenserOutletTemperature(o.get("condenserTemperatureKelvin").getAsDouble()),
                new V3ColumnSpecification.OrganicRefluxRatio(o.get("refluxRatio").getAsDouble()),
                new V3ColumnSpecification.ReboilerDuty(0));
        return new V3ColumnInput(b0.schemaVersion(), b0.packageId(), b0.assayId(), b0.componentBasis(), feed, o.get("feedTemperatureKelvin").getAsDouble(),
                stages, stages - 3, o.get("topPressurePascal").getAsDouble(), 0, specs, draws, steam, pas, o.get("diameterMetres").getAsDouble());
    }

    // ---------------------------------------------------------------- generation screens (guide 4.4, 6.1)

    /**
     * Energy-limited draw screen (guide 4.4, revision 2.1): V_gen = max(0, Q/lambda + vf F), L(T) = R/(R+1) V_gen + cooling above
     * and on T / lambda, plus (1 - vf) F at and below the feed tray; sigma = worst cumulative draw on trays 1..T over L(T).
     */
    static double sigma(V3ColumnInput in) {
        if (in.sideDraws().isEmpty()) return 0;
        var thermo = V3PengRobinsonThermo.fromRegisteredPackage(in.packageId());
        double[] feed = in.feedComponentMolarFlowsMolPerSecond(); double f = Arrays.stream(feed).sum();
        double pFeed = in.topPressurePascal() + in.feedStageNumber() * in.stagePressureDropPascal();
        double vf = thermo.flashTP(in.feedTemperatureKelvin(), pFeed, feed, thermo.newWorkspace()).vaporFraction();
        double q = 0, r = 0;
        for (var s : in.specifications()) {
            if (s instanceof V3ColumnSpecification.ReboilerDuty d) q = d.watts();
            if (s instanceof V3ColumnSpecification.OrganicRefluxRatio x) r = x.ratio();
        }
        double vGen = Math.max(0, q / LAMBDA + vf * f), worst = 0, drawn = 0;
        var draws = new ArrayList<>(in.sideDraws()); draws.sort((a, b) -> Integer.compare(a.trayNumber(), b.trayNumber()));
        for (var d : draws) {
            drawn += d.molarFlowMolPerSecond();
            double cooling = 0;
            for (var pa : in.pumparounds()) if (Math.min(pa.returnTray(), pa.drawTray()) <= d.trayNumber()) cooling += -pa.dutyWatts();
            double l = r / (r + 1) * vGen + cooling / LAMBDA + (d.trayNumber() >= in.feedStageNumber() ? (1 - vf) * f : 0);
            worst = Math.max(worst, l > 0 ? drawn / l : Double.POSITIVE_INFINITY);
        }
        return worst;
    }

    static String screen(V3ColumnInput in, double sigma) {
        var admission = V3ColumnCalculator.requestOnlyAdmission(in, SCREEN_RATIO);
        if (admission.typed()) return "REQUEST_ONLY:" + admission.gate();
        boolean noReboil = in.specifications().stream().anyMatch(s -> s instanceof V3ColumnSpecification.ReboilerDuty d && d.watts() == 0);
        if (in.steamFeeds().isEmpty() && noReboil && in.feedStageNumber() < in.stageCount()) return "DEGENERATE";
        if (sigma >= SIGMA_LIMIT) return "SIGMA";
        return null;
    }

    // ---------------------------------------------------------------- serialisation

    static JsonObject inputJson(V3ColumnInput in) {
        var o = new JsonObject();
        o.addProperty("packageId", in.packageId()); o.addProperty("assayId", in.assayId());
        o.add("feedComponentMolarFlowsMolPerSecond", JSON.toJsonTree(in.feedComponentMolarFlowsMolPerSecond()));
        o.addProperty("feedTemperatureKelvin", in.feedTemperatureKelvin()); o.addProperty("stageCount", in.stageCount());
        o.addProperty("feedStageNumber", in.feedStageNumber()); o.addProperty("topPressurePascal", in.topPressurePascal());
        o.addProperty("stagePressureDropPascal", in.stagePressureDropPascal()); o.addProperty("columnDiameterMetres", in.columnDiameterMetres());
        var specs = new JsonArray();
        for (var s : in.specifications()) {
            var x = new JsonObject(); x.addProperty("kind", s.controlledQuantity().name());
            if (s instanceof V3ColumnSpecification.CondenserOutletTemperature t) x.addProperty("kelvin", t.kelvin());
            if (s instanceof V3ColumnSpecification.OrganicRefluxRatio r) x.addProperty("ratio", r.ratio());
            if (s instanceof V3ColumnSpecification.ReboilerDuty d) x.addProperty("watts", d.watts());
            specs.add(x);
        }
        o.add("specifications", specs);
        o.add("sideDraws", JSON.toJsonTree(in.sideDraws())); o.add("steamFeeds", JSON.toJsonTree(in.steamFeeds()));
        o.add("pumparounds", JSON.toJsonTree(in.pumparounds()));
        return o;
    }

    static JsonObject seedJson(V3NeuralSeed s) {
        var o = new JsonObject();
        o.addProperty("branch", s.branch().name()); o.addProperty("propertyRevision", s.propertyRevision());
        o.add("liquid", JSON.toJsonTree(s.liquid())); o.add("vapor", JSON.toJsonTree(s.vapor()));
        o.add("temperatures", JSON.toJsonTree(s.temperatures())); o.add("freeWater", JSON.toJsonTree(s.freeWater()));
        o.add("wetTrays", JSON.toJsonTree(s.wetTrays()));
        return o;
    }

    /** The strict-label rule of tools/neural/prepare_transformer_data.strict, on the native objects. */
    static String strict(V3ColumnOutcome outcome, V3NeuralSeed seed, V3ColumnInput in, V3WaterPhaseQualification.Assessment water) {
        if (!(outcome instanceof V3ColumnOutcome.Success)) return "NOT_SUCCESS";
        if (seed == null) return "NO_PROFILE";
        if (!water.qualified()) return "WATER_" + water.grade();
        if (!Arrays.equals(seed.input().feedComponentMolarFlowsMolPerSecond(), in.feedComponentMolarFlowsMolPerSecond())) return "SEED_INPUT_MISMATCH";
        var d = outcome.diagnostics(); var audit = d.acceptanceAudit(); var cert = d.convergenceEvidence();
        if (audit == null || audit.checks().isEmpty() || !audit.checks().stream().allMatch(V3AcceptanceAudit.Check::passed)) return "AUDIT";
        if (cert == null || !cert.hasFinalNewtonStep() || d.closureTolerance() != 1e-8 || cert.finalLinearBackwardError() > 1e-12
                || cert.maximumLogFlowChange() > 1e-8 || cert.maximumTemperatureStepRatio() > 1) return "EVIDENCE";
        return null;
    }

    // ---------------------------------------------------------------- labels

    static JsonObject label(JsonObject row) {
        var out = new JsonObject();
        out.add("id", row.get("id")); out.add("split", row.get("split")); out.add("design", row);
        final V3ColumnInput in;
        try { in = realise(row); }
        catch (IllegalArgumentException invalid) { out.addProperty("status", "INVALID_INPUT"); out.addProperty("failure", invalid.getMessage()); return out; }
        out.add("input", inputJson(in));
        return MaterialRuntime.with(catalog, in.packageId(), () -> {
            double sigma;
            try { sigma = sigma(in); } catch (RuntimeException failed) { sigma = Double.NaN; out.addProperty("sigmaFailure", String.valueOf(failed.getMessage())); }
            out.addProperty("sigma", sigma);
            String excluded = screen(in, Double.isNaN(sigma) ? 0 : sigma);
            if (excluded != null) { out.addProperty("status", "EXCLUDED_BY_DESIGN"); out.addProperty("exclusion", excluded); return out; }
            var polls = new AtomicLong(); V3NeuralSeed[] profile = {null};
            long start = System.nanoTime();
            V3SolveControl control = () -> {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("interrupted");
                if (polls.incrementAndGet() > POLL_CAP) throw new CancellationException("poll cap");
            };
            V3ColumnOutcome outcome;
            try { outcome = V3ColumnCalculator.calculateWithAcceptedProfile(in, control, p -> profile[0] = p); }
            catch (CancellationException capped) { outcome = null; }
            catch (RuntimeException failed) { outcome = null; out.addProperty("exception", failed.getClass().getSimpleName() + ": " + failed.getMessage()); }
            out.addProperty("controlPolls", polls.get());
            out.addProperty("wallMsInformative", (System.nanoTime() - start) / 1e6);
            if (outcome == null) { out.addProperty("status", polls.get() > POLL_CAP ? "POLL_CAP" : "EXCEPTION"); out.addProperty("success", false); return out; }
            var d = outcome.diagnostics();
            out.addProperty("success", outcome.isSuccess());
            out.addProperty("newtonIterations", d.newtonIterations()); out.addProperty("initializerIterations", d.initializerIterations());
            out.addProperty("solvePath", d.solvePath());
            if (outcome instanceof V3ColumnOutcome.Failure f) {
                out.addProperty("status", f.code().name()); out.addProperty("failure", f.summary());
                var ev = new JsonArray(); d.events().stream().limit(4).map(e -> e.length() > 300 ? e.substring(0, 300) : e).forEach(ev::add); out.add("events", ev);
                return out;
            }
            out.addProperty("status", "ACCEPTED");
            out.addProperty("formulationRevision", ((V3ColumnOutcome.Success) outcome).result().formulationRevision());
            var water = profile[0] == null ? null : V3WaterPhaseQualification.assess(profile[0]);
            if (water != null) { out.addProperty("waterGrade", water.grade().name()); out.addProperty("wetTrayCount", water.wetTrayCount()); }
            String reason = water == null ? "NO_PROFILE" : strict(outcome, profile[0], in, water);
            out.addProperty("strict", reason == null); if (reason != null) out.addProperty("strictFailure", reason);
            if (profile[0] != null) {
                out.add("seed", seedJson(profile[0]));
                out.add("targets", JSON.toJsonTree(V3FactorizedNeuralFeatures.targets(profile[0])));
            }
            return out;
        });
    }

    static List<JsonObject> read(Path path) throws Exception {
        var rows = new ArrayList<JsonObject>();
        for (String s : Files.readAllLines(path)) if (!s.isBlank()) rows.add(JsonParser.parseString(s).getAsJsonObject());
        return rows;
    }

    @Test void labels() throws Exception {
        setUp();
        var rows = read(Path.of(env("NR_DESIGN", "tmp/neural-retrain/design/pilot.jsonl")));
        Path out = Path.of(env("NR_OUT", "tmp/neural-retrain/pilot")); Files.createDirectories(out);
        int workers = Integer.parseInt(env("NR_WORKERS", "4"));
        if (workers < 1 || workers > 10) throw new IllegalArgumentException("NR_WORKERS 1..10");
        Path journal = out.resolve("cases.jsonl");
        if (Files.exists(journal)) throw new IllegalStateException("Journal exists: " + journal);
        var pool = Executors.newFixedThreadPool(workers); var done = new ExecutorCompletionService<JsonObject>(pool);
        long start = System.nanoTime(); int accepted = 0, strict = 0;
        try (BufferedWriter w = Files.newBufferedWriter(journal, StandardOpenOption.CREATE_NEW)) {
            for (var row : rows) done.submit(() -> label(row));
            for (int i = 0; i < rows.size(); i++) {
                var r = done.take().get();
                w.write(JSON.toJson(r)); w.newLine(); w.flush();
                if ("ACCEPTED".equals(r.has("status") ? r.get("status").getAsString() : "")) accepted++;
                if (r.has("strict") && r.get("strict").getAsBoolean()) strict++;
                if (i % 10 == 0 || i == rows.size() - 1) System.out.printf("nr labels %d/%d accepted=%d strict=%d %.0fs%n", i + 1, rows.size(), accepted, strict, (System.nanoTime() - start) / 1e9);
            }
        } finally { pool.shutdownNow(); }
        var run = new JsonObject();
        run.addProperty("rows", rows.size()); run.addProperty("accepted", accepted); run.addProperty("strict", strict); run.addProperty("workers", workers);
        run.addProperty("pollCap", POLL_CAP); run.addProperty("elapsedSecondsInformative", (System.nanoTime() - start) / 1e9);
        run.addProperty("note", "wall time is informative only: the run shares the machine with another Gradle lane; judge cost by control polls");
        Files.writeString(out.resolve("run.json"), new GsonBuilder().setPrettyPrinting().create().toJson(run));
    }

    // ---------------------------------------------------------------- bundled-model parity reference (N0)

    @Test void features() throws Exception {
        setUp();
        byte[] payload, sidecar;
        try (var p = getClass().getResourceAsStream("/data/icheme/neural/v3-column-transformer-regrouped.payload.json");
             var s = getClass().getResourceAsStream("/data/icheme/neural/v3-column-transformer-regrouped.sidecar.json")) { payload = p.readAllBytes(); sidecar = s.readAllBytes(); }
        var model = V3NeuralRegistry.TransformerArtifact.read(new ByteArrayInputStream(payload), new ByteArrayInputStream(sidecar)).initializer();
        var inputs = new ArrayList<Map.Entry<String, V3ColumnInput>>();
        for (int g = 0; g < GRADES.length; g++) inputs.add(Map.entry("preset-" + GRADES[g], base[g]));
        String cases = env("NR_CASES", "");
        int limit = Integer.parseInt(env("NR_LIMIT", "50"));
        if (!cases.isEmpty()) for (var row : read(Path.of(cases))) {
            if (inputs.size() >= limit + GRADES.length) break;
            if (row.has("strict") && row.get("strict").getAsBoolean()) inputs.add(Map.entry(row.get("id").getAsString(), realise(row.getAsJsonObject("design"))));
        }
        Path out = Path.of(env("NR_OUT", "tmp/neural-retrain/parity")); Files.createDirectories(out);
        try (BufferedWriter w = Files.newBufferedWriter(out.resolve("features.jsonl"), StandardCharsets.UTF_8)) {
            for (var e : inputs) {
                var in = e.getValue();
                var o = MaterialRuntime.with(catalog, in.packageId(), () -> {
                    var x = new JsonObject(); x.addProperty("id", e.getKey());
                    double[] global = V3GeneralNeuralFeatures.global(in);
                    x.add("global", JSON.toJsonTree(global));
                    x.add("nodes", JSON.toJsonTree(V3GeneralNeuralFeatures.nodes(in, V3CondenserPhaseBranch.TWO_PHASE)));
                    var raw = model.raw(in, () -> {});
                    int best = raw.branchLogits()[0] >= raw.branchLogits()[1] ? 0 : 1; // the bundled model saw LIQUID_ONLY and TWO_PHASE only (branchesSeen)
                    var branch = new V3CondenserPhaseBranch[] {V3CondenserPhaseBranch.LIQUID_ONLY, V3CondenserPhaseBranch.TWO_PHASE, V3CondenserPhaseBranch.VAPOR_ONLY}[best];
                    var anchor = V3AnchorTransformerInitializer.NativeAnchor.build(in, branch, () -> {});
                    x.addProperty("branch", best); x.add("anchor", JSON.toJsonTree(anchor.values())); x.addProperty("anchorAvailable", anchor.available());
                    x.add("javaBranchLogits", JSON.toJsonTree(raw.branchLogits())); x.add("javaRaw", JSON.toJsonTree(raw.values()));
                    return x;
                });
                w.write(JSON.toJson(o)); w.newLine();
                System.out.println("nr features " + e.getKey() + " branch=" + o.get("branch") + " anchor=" + o.get("anchorAvailable"));
            }
        }
    }
}
