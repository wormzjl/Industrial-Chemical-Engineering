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
import java.util.TreeMap;
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
    /** The package that holds the 19-compound axis, nitrogen and the 24 refinery compounds (extends chain): injected rows. */
    static final String WORLD_PACKAGE = "icheme:refinery";
    /** design.mjs OFF_AXIS: the injectable off-axis compounds (the world list less CO2 and oxygen). */
    static final List<String> OFF_AXIS = List.of("Nitrogen", "Hydrogen", "HydrogenSulfide", "CarbonMonoxide", "Ammonia", "SulfurDioxide",
            "CarbonylSulfide", "Ethylene", "Propylene", "Butene-1", "Isobutene", "Cis-2-butene", "Trans-2-butene", "Butadiene", "N-hexane",
            "Cyclohexane", "Benzene", "Toluene", "Ethylbenzene", "O-xylene", "M-xylene", "P-xylene", "MethylMercaptan", "EthylMercaptan");
    /**
     * Sense windows (NEURAL_RETRAIN_DATA.md section 7): physical properties of the operating point, never solver difficulty.
     * Heater outlet below cracking (CDU about 370 C, VDU about 420 C); stripping steam by mass of hydrocarbon feed; trays below the
     * cracking limit; a wet wash zone above the feed (overflash at least 1 % of the feed moles); no vanishing product.
     */
    static final double CDU_HEATER_K = 643.15, VDU_HEATER_K = 693.15, CDU_STEAM_MASS = 0.10, VDU_STEAM_MASS = 0.03,
            CDU_TRAY_K = 653.15, VDU_TRAY_K = 703.15, OVERFLASH_MIN = 0.01, PRODUCT_MIN = 0.005;
    /**
     * The accepted VDU of batch 2026-10-28-vacuum-column per mole of its feed (about 210 mol/s of residue, 94.4 kg/s): cuts
     * 21.2 and 53.0 mol/s, steam 52.4 mol/s at 533.15 K, pumparound cooling 10 and 15 MW; condenser 333.15 K, reflux 0, no reboiler.
     */
    static final double VDU_STEAM = 0.25, VDU_PA_1 = 47.6e3, VDU_PA_2 = 71.4e3;
    /**
     * Each VDU cut is sized on the liquid its own pumparound condenses (duty over a gas-oil latent heat of about 80 kJ/mol): liquid
     * condensed higher up re-boils on the hotter trays below, so the accepted case draws 0.17 (cut 1) and 0.28 (cut 2) of its own
     * pumparound's liquid. The design draws 0.3 x U(0.6, 1.2) of it: 0.18 to 0.36 (pilot r2 lost 30 of 74 rows to depleted draws).
     */
    static final double VDU_LATENT = 80e3, VDU_CUT_SHARE = 0.3;

    static MaterialCatalog catalog;
    static V3ColumnInput[] base = new V3ColumnInput[GRADES.length];
    static Map<String, JsonObject> cduLabels = Map.of();
    /**
     * N3b (NEURAL_RETRAIN_DATA.md section 8): when NR_PAYLOAD and NR_SIDECAR name a candidate, `labels` solves LNN_FIRST through a
     * registry of that one model (so injected rows are lumped, U1) at the production start options (ColumnUnit.START: the default
     * poll budget, classical backup). The strict rule and the sense checks are unchanged: a network output is never a label.
     */
    static V3NeuralInitializer learned = null;
    static final V3InitializationOptions LEARNED_START = new V3InitializationOptions(V3InitializationOptions.Mode.LNN_FIRST,
            V3InitializationOptions.WetStart.AUTO, 16, Integer.parseInt(env("NR_BUDGET", String.valueOf(V3InitializationOptions.DEFAULT_BUDGET_POLLS))),
            V3InitializationOptions.Correction.PROGRESS); // NR_BUDGET: N4's budget measurement (the default is provisional)

    static V3NeuralInitializer candidate() throws Exception {
        byte[] payload = Files.readAllBytes(Path.of(env("NR_PAYLOAD", ""))), sidecar = Files.readAllBytes(Path.of(env("NR_SIDECAR", "")));
        var loaded = V3NeuralRegistry.TransformerArtifact.read(new ByteArrayInputStream(payload), new ByteArrayInputStream(sidecar));
        return new V3NeuralRegistry(List.of(new V3NeuralRegistry.Entry(1, loaded.initializer(), loaded.pipelineSha256())));
    }

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
        var op = row.getAsJsonObject("operating");
        if (op.has("preset")) for (int i = 0; i < w.length; i++) if (w[i] == 1) { // a grade preset, optionally at another feed temperature (N4 grid)
            var b = base[i];
            if (!op.has("feedTemperatureKelvin")) return b;
            return new V3ColumnInput(b.schemaVersion(), b.packageId(), b.assayId(), b.componentBasis(), b.feedComponentMolarFlowsMolPerSecond(),
                    op.get("feedTemperatureKelvin").getAsDouble(), b.stageCount(), b.feedStageNumber(), b.topPressurePascal(), b.stagePressureDropPascal(),
                    b.specifications(), b.sideDraws(), b.steamFeeds(), b.pumparounds(), b.columnDiameterMetres());
        }
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
        var cdu = new V3ColumnInput(b0.schemaVersion(), b0.packageId(), b0.assayId(), b0.componentBasis(), feed, o.get("feedTemperatureKelvin").getAsDouble(),
                stages, stages - 3, o.get("topPressurePascal").getAsDouble(), 0, specs, draws, steam, pas, o.get("diameterMetres").getAsDouble());
        return c.has("injection") ? injected(cdu, c.getAsJsonObject("injection")) : cdu;
    }

    /**
     * The same column on the world package with off-axis compounds added (NEURAL_RETRAIN_DATA.md section 5): {@code fraction}
     * of the feed moles, split equally over {@code compounds}; the axis part is scaled down so the total feed is unchanged.
     */
    static V3ColumnInput injected(V3ColumnInput in, JsonObject injection) {
        var pack = catalog.packages().get(WORLD_PACKAGE);
        if (pack == null) throw new IllegalStateException("No package " + WORLD_PACKAGE);
        var axis = in.componentBasis().componentIds(); var injectedIds = new java.util.HashSet<String>();
        for (var compound : injection.getAsJsonArray("compounds")) injectedIds.add(compound.getAsString());
        // The sheet's view (SheetCompounds: catalog.restrict): the axis and the injected compounds, in the package's order; it holds
        // only their crystals, so a column without CO2 is not refused for the package's CO2-I (O11).
        var ids = pack.components().stream().filter(id -> axis.contains(id) || injectedIds.contains(id)).toList();
        double[] axisFeed = in.feedComponentMolarFlowsMolPerSecond(), feed = new double[ids.size()];
        double total = Arrays.stream(axisFeed).sum(), fraction = injection.get("fraction").getAsDouble();
        for (int k = 0; k < axis.size(); k++) {
            int at = ids.indexOf(axis.get(k));
            if (at < 0) throw new IllegalStateException(WORLD_PACKAGE + " lacks " + axis.get(k));
            feed[at] = axisFeed[k] * (1 - fraction);
        }
        var compounds = injection.getAsJsonArray("compounds");
        for (var compound : compounds) {
            int at = ids.indexOf(compound.getAsString());
            if (at < 0) throw new IllegalArgumentException(WORLD_PACKAGE + " lacks " + compound.getAsString());
            feed[at] += total * fraction / compounds.size();
        }
        return new V3ColumnInput(in.schemaVersion(), pack.id(), in.assayId(), new V3ComponentBasis(ids), feed, in.feedTemperatureKelvin(),
                in.stageCount(), in.feedStageNumber(), in.topPressurePascal(), in.stagePressureDropPascal(), in.specifications(),
                in.sideDraws(), in.steamFeeds(), in.pumparounds(), in.columnDiameterMetres());
    }

    /** A VDU on the bottoms of a strict CDU label (NEURAL_RETRAIN_DATA.md section 6, case A envelope); grades are never blended directly. */
    static V3ColumnInput realiseVdu(JsonObject row) {
        var o = row.getAsJsonObject("operating");
        var strict = cduLabels.values().stream().filter(r -> r.has("label") ? r.get("label").getAsBoolean() : r.has("strict") && r.get("strict").getAsBoolean()).sorted((a, b) -> a.get("id").getAsString().compareTo(b.get("id").getAsString())).toList();
        if (strict.isEmpty()) throw new IllegalStateException("NR_CDU_LABELS holds no strict CDU label");
        var source = strict.get(Math.min(strict.size() - 1, (int) Math.floor(o.get("sourceRank").getAsDouble() * strict.size())));
        var sourceInput = realise(source.getAsJsonObject("design"));
        double[][] liquid = JSON.fromJson(source.getAsJsonObject("seed").get("liquid"), double[][].class);
        double[] feed = liquid[liquid.length - 1].clone(); // the sump liquid: the CDU's bottoms product
        double f = Arrays.stream(feed).sum();
        int n = o.get("stages").getAsInt();
        double pa = o.get("pumparoundFactor").getAsDouble(), duty1 = VDU_PA_1 * f * pa, duty2 = VDU_PA_2 * f * pa;
        // Owner, 2026-11-05: "Top trays should be dry for VDU". At zero reflux the dry prefix ends at the first cooling return, so
        // the top pumparound returns on tray 2 or lower and at least tray 1 stays dry (the accepted case: return 2, cut 3 of 10).
        // Cut 2 stays at least two trays below cut 1 and at least one above the feed (accepted: 6, feed 8).
        int t1 = Math.max(3, (int) Math.round(0.3 * n)), t2 = Math.min(n - 3, Math.max(t1 + 2, (int) Math.round(0.6 * n)));
        var draws = List.of(new V3SideDrawSpec(t1, VDU_CUT_SHARE * duty1 / VDU_LATENT * o.get("cut1Factor").getAsDouble()),
                new V3SideDrawSpec(t2, VDU_CUT_SHARE * duty2 / VDU_LATENT * o.get("cut2Factor").getAsDouble()));
        var steam = List.of(new V3SteamFeedSpec(n + 1, VDU_STEAM * f * o.get("steamFactor").getAsDouble(), 533.15));
        int d1 = draws.get(0).trayNumber(), d2 = draws.get(1).trayNumber();
        var pas = List.of(new V3PumparoundSpec(Math.max(1, d1 - 1), d1, -duty1, V3PumparoundSpec.Split.UNIFORM),
                new V3PumparoundSpec(Math.max(1, d2 - 1), d2, -duty2, V3PumparoundSpec.Split.UNIFORM));
        var specs = List.<V3ColumnSpecification>of(new V3ColumnSpecification.CondenserOutletTemperature(333.15),
                new V3ColumnSpecification.OrganicRefluxRatio(0), new V3ColumnSpecification.ReboilerDuty(0));
        return new V3ColumnInput(sourceInput.schemaVersion(), sourceInput.packageId(), sourceInput.assayId(), sourceInput.componentBasis(), feed,
                o.get("feedTemperatureKelvin").getAsDouble(), n, n - 2, o.get("topPressurePascal").getAsDouble(), 0, specs, draws, steam, pas,
                o.get("diameterMetres").getAsDouble());
    }

    static final java.util.concurrent.ConcurrentHashMap<List<String>, MaterialCatalog> VIEWS = new java.util.concurrent.ConcurrentHashMap<>();

    /** The catalog a request runs in: the world package restricted to the request's compounds, as the simulator does. */
    static MaterialCatalog catalogFor(V3ColumnInput in) {
        if (!WORLD_PACKAGE.equals(in.packageId())) return catalog;
        return VIEWS.computeIfAbsent(in.componentBasis().componentIds(), ids -> catalog.restrict(WORLD_PACKAGE, ids));
    }

    static boolean vdu(JsonObject row) { return "vdu".equals(row.has("unit") ? row.get("unit").getAsString() : "cdu"); }

    static V3ColumnInput build(JsonObject row) { return vdu(row) ? realiseVdu(row) : realise(row); }

    static double[] molarMasses(V3ColumnInput in) {
        var pack = catalogFor(in).packages().get(in.packageId()); var ids = in.componentBasis().componentIds();
        double[] m = new double[ids.size()];
        for (int k = 0; k < m.length; k++) {
            String id = ids.get(k);
            m[k] = pack.properties().stream().filter(p -> p.component().equals(id)).findFirst().orElseThrow(() -> new IllegalStateException("No property " + id)).molecularWeight();
        }
        return m;
    }

    static double[] boilingPoints(V3ColumnInput in) {
        var pack = catalogFor(in).packages().get(in.packageId()); var ids = in.componentBasis().componentIds();
        double[] b = new double[ids.size()];
        for (int k = 0; k < b.length; k++) {
            String id = ids.get(k);
            b[k] = pack.properties().stream().filter(p -> p.component().equals(id)).findFirst().orElseThrow().normalBoilingPoint();
        }
        return b;
    }

    /** Input sense checks at generation, no solve; the first failed criterion, or null. */
    static String senseInput(V3ColumnInput in, boolean vdu) {
        if (in.feedTemperatureKelvin() > (vdu ? VDU_HEATER_K : CDU_HEATER_K)) return "HEATER_ABOVE_CRACKING";
        double[] feed = in.feedComponentMolarFlowsMolPerSecond(), mw = molarMasses(in);
        double mass = 0; for (int k = 0; k < feed.length; k++) mass += feed[k] * mw[k];
        double steam = in.steamFeeds().stream().mapToDouble(V3SteamFeedSpec::molarFlowMolPerSecond).sum() * 0.018015;
        if (steam > (vdu ? VDU_STEAM_MASS : CDU_STEAM_MASS) * mass) return "STEAM_TO_FEED";
        if (vdu ? in.topPressurePascal() < 1.3e3 || in.topPressurePascal() > 8e3 : in.topPressurePascal() < 150e3 || in.topPressurePascal() > 350e3) return "TOP_PRESSURE";
        if (vdu ? in.stageCount() < 8 || in.stageCount() > 15 : in.stageCount() < 20) return "STAGE_COUNT";
        return null;
    }

    /** Sense checks on the converged state; the failed criteria (empty when it makes sense). */
    static List<String> senseState(V3ColumnInput in, V3NeuralSeed seed, List<V3ColumnStreamProperties> streams, boolean vdu) {
        var failed = new ArrayList<String>();
        double[] t = seed.temperatures(); int last = t.length - 1;
        for (int n = 1; n < last; n++) if (t[n] > (vdu ? VDU_TRAY_K : CDU_TRAY_K)) { failed.add("TRAY_ABOVE_CRACKING"); break; }
        double f = Arrays.stream(in.feedComponentMolarFlowsMolPerSecond()).sum();
        double[][] liquid = seed.liquid();
        if (!vdu) for (int n = 1; n <= in.stageCount(); n++) if (Arrays.stream(liquid[n]).sum() <= 0) { failed.add("NO_INTERNAL_REFLUX"); break; }
        if (vdu) { // the trays above the top cooling return are dry (owner: "Top trays should be dry for VDU")
            int top = in.pumparounds().stream().mapToInt(V3PumparoundSpec::returnTray).min().orElse(in.feedStageNumber());
            if (top <= 1) failed.add("VDU_TOP_WET");
            for (int n = 1; n < top; n++) if (Arrays.stream(liquid[n]).sum() > 0) { failed.add("VDU_TOP_WET"); break; }
        }
        double overflash = Arrays.stream(liquid[in.feedStageNumber() - 1]).sum() / f;
        // Lower bound only: the presets run 0.44 to 1.12 mol of liquid above the feed per mol of feed (pilot r2, 2026-11-05), far above
        // the 2 to 5 vol % of refinery practice, so an upper bound would refuse the game's own operating points. A dry wash zone fails.
        if (overflash < OVERFLASH_MIN) failed.add("OVERFLASH");
        for (var d : in.sideDraws()) if (d.molarFlowMolPerSecond() < PRODUCT_MIN * f) { failed.add("VANISHING_PRODUCT"); break; }
        if (!failed.contains("VANISHING_PRODUCT")) for (var s : streams)
            if (!"FREE_WATER".equalsIgnoreCase(s.phase()) && !s.streamId().contains("water") && s.molarFlowMolPerSecond() < PRODUCT_MIN * f) { failed.add("VANISHING_PRODUCT"); break; }
        // Products in boiling-range order: the mole-weighted normal boiling point of each liquid product rises from top to bottom.
        double[] nbp = boilingPoints(in); double previous = 0; boolean ordered = true;
        int[] trays = new int[in.sideDraws().size() + 1]; for (int k = 0; k < in.sideDraws().size(); k++) trays[k] = in.sideDraws().get(k).trayNumber();
        trays[trays.length - 1] = last; Arrays.sort(trays);
        for (int node : trays) {
            double tot = Arrays.stream(liquid[node]).sum(); if (tot <= 0) continue;
            double mean = 0; for (int k = 0; k < nbp.length; k++) mean += liquid[node][k] / tot * nbp[k];
            if (mean < previous) ordered = false; previous = mean;
        }
        if (!ordered) failed.add("PRODUCT_ORDER");
        return failed;
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
        o.add("componentIds", JSON.toJsonTree(in.componentBasis().componentIds()));
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
        try { in = build(row); }
        catch (IllegalArgumentException invalid) { out.addProperty("status", "INVALID_INPUT"); out.addProperty("failure", invalid.getMessage()); return out; }
        out.add("input", inputJson(in));
        return MaterialRuntime.with(catalogFor(in), in.packageId(), () -> {
            double sigma;
            try { sigma = sigma(in); } catch (RuntimeException failed) { sigma = Double.NaN; out.addProperty("sigmaFailure", String.valueOf(failed.getMessage())); }
            out.addProperty("sigma", sigma);
            String excluded = screen(in, Double.isNaN(sigma) ? 0 : sigma);
            if (excluded == null) { String sense = senseInput(in, vdu(row)); if (sense != null) excluded = "SENSE:" + sense; }
            if (excluded != null) { out.addProperty("status", "EXCLUDED_BY_DESIGN"); out.addProperty("exclusion", excluded); return out; }
            var polls = new AtomicLong(); V3NeuralSeed[] profile = {null};
            long start = System.nanoTime();
            V3SolveControl control = () -> {
                if (Thread.currentThread().isInterrupted()) throw new CancellationException("interrupted");
                if (polls.incrementAndGet() > POLL_CAP) throw new CancellationException("poll cap");
            };
            V3ColumnOutcome outcome;
            try {
                outcome = learned == null ? V3ColumnCalculator.calculateWithAcceptedProfile(in, control, p -> profile[0] = p)
                        : V3ColumnCalculator.calculateWithAcceptedProfile(in, control, LEARNED_START, learned, p -> profile[0] = p);
            }
            catch (CancellationException capped) { outcome = null; }
            catch (RuntimeException failed) { outcome = null; out.addProperty("exception", failed.getClass().getSimpleName() + ": " + failed.getMessage()); }
            out.addProperty("controlPolls", polls.get());
            out.addProperty("wallMsInformative", (System.nanoTime() - start) / 1e6);
            if (outcome == null) { out.addProperty("status", polls.get() > POLL_CAP ? "POLL_CAP" : "EXCEPTION"); out.addProperty("success", false); return out; }
            var d = outcome.diagnostics();
            out.addProperty("success", outcome.isSuccess());
            out.addProperty("newtonIterations", d.newtonIterations()); out.addProperty("initializerIterations", d.initializerIterations());
            out.addProperty("solvePath", d.solvePath());
            if (learned != null) d.events().stream().filter(e -> e.startsWith("initializer=")).findFirst()
                    .ifPresent(e -> out.addProperty("route", e.length() > 300 ? e.substring(0, 300) : e));
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
            var sense = profile[0] == null ? List.of("NO_PROFILE") : senseState(in, profile[0], ((V3ColumnOutcome.Success) outcome).result().streams(), vdu(row));
            out.add("sense", JSON.toJsonTree(sense));
            out.addProperty("label", reason == null && sense.isEmpty());
            if (vdu(row) && profile[0] != null) { // open check V0: the native anchor on a dry zero-reflux top
                var anchor = V3AnchorTransformerInitializer.NativeAnchor.build(in, profile[0].branch(), () -> {});
                out.addProperty("anchorAvailable", anchor.available()); out.addProperty("anchorReason", anchor.reason());
                out.addProperty("branch", profile[0].branch().name());
            }
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
        String sources = env("NR_CDU_LABELS", ""); // VDU rows take their feeds from this CDU labels journal
        if (!sources.isEmpty()) { var map = new LinkedHashMap<String, JsonObject>(); for (var r : read(Path.of(sources))) map.put(r.get("id").getAsString(), r); cduLabels = map; }
        Path out = Path.of(env("NR_OUT", "tmp/neural-retrain/pilot")); Files.createDirectories(out);
        learned = env("NR_PAYLOAD", "").isEmpty() ? null : candidate();
        int workers = Integer.parseInt(env("NR_WORKERS", "4"));
        if (workers < 1 || workers > Runtime.getRuntime().availableProcessors()) throw new IllegalArgumentException("NR_WORKERS 1..cores");
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
        run.addProperty("pollCap", POLL_CAP); run.addProperty("learned", learned == null ? "none (CURRENT_ONLY)" : env("NR_PAYLOAD", "")); run.addProperty("elapsedSecondsInformative", (System.nanoTime() - start) / 1e9);
        run.addProperty("note", "wall time is informative only: the run shares the machine with another Gradle lane; judge cost by control polls");
        Files.writeString(out.resolve("run.json"), new GsonBuilder().setPrettyPrinting().create().toJson(run));
    }

    // ---------------------------------------------------------------- training features (N2 -> N3), U1 lumping

    /** The model's axis: the 19 compounds of the column package the grades are on. */
    static List<String> axis() { return base[0].componentBasis().componentIds(); }

    /**
     * U1 (NEURAL_RETRAIN_DATA.md section 5): each off-axis compound of {@code ids} goes to the axis compound with the nearest normal
     * boiling point (the gases lighter than methane go to methane). Returns, for each of {@code ids}, its axis index.
     */
    static int[] lumpMap(V3ColumnInput in) {
        var ids = in.componentBasis().componentIds(); var ax = axis();
        double[] nbp = boilingPoints(in), axisNbp = boilingPoints(base[0]);
        int[] map = new int[ids.size()];
        for (int k = 0; k < map.length; k++) {
            int at = ax.indexOf(ids.get(k));
            if (at < 0) { at = 0; for (int a = 1; a < ax.size(); a++) if (Math.abs(axisNbp[a] - nbp[k]) < Math.abs(axisNbp[at] - nbp[k])) at = a; }
            map[k] = at;
        }
        return map;
    }

    static double[] lump(double[] flows, int[] map, int width) { double[] out = new double[width]; for (int k = 0; k < flows.length; k++) out[map[k]] += flows[k]; return out; }

    /** The request on the axis package: off-axis flows lumped (U1); identical to {@code in} for a request on the axis. */
    static V3ColumnInput lumped(V3ColumnInput in) {
        if (in.packageId().equals(base[0].packageId())) return in;
        var b = base[0];
        return new V3ColumnInput(in.schemaVersion(), b.packageId(), b.assayId(), b.componentBasis(), lump(in.feedComponentMolarFlowsMolPerSecond(), lumpMap(in), axis().size()),
                in.feedTemperatureKelvin(), in.stageCount(), in.feedStageNumber(), in.topPressurePascal(), in.stagePressureDropPascal(), in.specifications(),
                in.sideDraws(), in.steamFeeds(), in.pumparounds(), in.columnDiameterMetres());
    }

    /** U2's channels: the off-axis share of the feed with normal boiling point below 150 K, 150 to 320 K, above 320 K. */
    static double[] offAxisBands(V3ColumnInput in) {
        double[] bands = new double[3], f = in.feedComponentMolarFlowsMolPerSecond(), nbp = boilingPoints(in);
        double total = Arrays.stream(f).sum(); var ids = in.componentBasis().componentIds(); var ax = axis();
        for (int k = 0; k < f.length; k++) if (!ax.contains(ids.get(k))) bands[nbp[k] < 150 ? 0 : nbp[k] <= 320 ? 1 : 2] += f[k] / total;
        return bands;
    }

    static V3CondenserPhaseBranch[] BRANCHES = {V3CondenserPhaseBranch.LIQUID_ONLY, V3CondenserPhaseBranch.TWO_PHASE, V3CondenserPhaseBranch.VAPOR_ONLY};

    /** One label's model inputs and targets on the axis: global, nodes, the native anchor on the label's branch, targets, fractions. */
    static JsonObject trainingRow(JsonObject row) {
        final V3ColumnInput in;
        try { in = build(row.getAsJsonObject("design")); }
        catch (IllegalArgumentException basisChanged) { return null; } // a VDU row on an injected source written before O11's views
        var s = row.getAsJsonObject("seed");
        var branch = V3CondenserPhaseBranch.valueOf(s.get("branch").getAsString());
        int[] map = lumpMap(in); int width = axis().size();
        double[][] liquid = JSON.fromJson(s.get("liquid"), double[][].class), vapor = JSON.fromJson(s.get("vapor"), double[][].class);
        if (liquid[0].length != map.length) return null; // a journal written on another basis (an injected row before O11's views): relabel it
        for (int n = 0; n < liquid.length; n++) { liquid[n] = lump(liquid[n], map, width); vapor[n] = lump(vapor[n], map, width); }
        var axisInput = lumped(in);
        return MaterialRuntime.with(catalog, axisInput.packageId(), () -> {
            var seed = new V3NeuralSeed(axisInput, s.get("propertyRevision").getAsString(), branch, liquid, vapor,
                    JSON.fromJson(s.get("temperatures"), double[].class), JSON.fromJson(s.get("freeWater"), double[].class), JSON.fromJson(s.get("wetTrays"), boolean[].class));
            var x = new JsonObject();
            x.add("id", row.get("id")); x.add("split", row.get("split")); x.addProperty("unit", vdu(row.getAsJsonObject("design")) ? "vdu" : "cdu");
            x.addProperty("injected", !in.packageId().equals(axisInput.packageId()));
            x.add("offAxisBands", JSON.toJsonTree(offAxisBands(in)));
            x.add("feedFractions", JSON.toJsonTree(Arrays.stream(axisInput.feedComponentMolarFlowsMolPerSecond()).map(v -> v / Arrays.stream(axisInput.feedComponentMolarFlowsMolPerSecond()).sum()).toArray()));
            x.add("global", JSON.toJsonTree(V3GeneralNeuralFeatures.global(axisInput)));
            x.add("nodes", JSON.toJsonTree(V3GeneralNeuralFeatures.nodes(axisInput, V3CondenserPhaseBranch.TWO_PHASE)));
            var anchor = V3AnchorTransformerInitializer.NativeAnchor.build(axisInput, branch, () -> {});
            x.add("anchor", JSON.toJsonTree(anchor.values())); x.addProperty("anchorAvailable", anchor.available());
            x.addProperty("branch", Arrays.asList(BRANCHES).indexOf(branch));
            x.addProperty("reflux", axisInput.specifications().stream().anyMatch(sp -> sp instanceof V3ColumnSpecification.OrganicRefluxRatio r && r.ratio() > 0));
            x.add("targets", JSON.toJsonTree(V3FactorizedNeuralFeatures.targets(seed)));
            double[][][] fractions = new double[liquid.length][2][width];
            for (int n = 0; n < liquid.length; n++) for (int p = 0; p < 2; p++) {
                double[] q = p == 0 ? liquid[n] : vapor[n]; double t = Arrays.stream(q).sum();
                if (t > 0) for (int c = 0; c < width; c++) fractions[n][p][c] = q[c] / t;
            }
            x.add("fractions", JSON.toJsonTree(fractions));
            x.add("formulationRevision", row.get("formulationRevision"));
            return x;
        });
    }

    @Test void export() throws Exception {
        setUp();
        String sources = env("NR_CDU_LABELS", "");
        if (!sources.isEmpty()) { var map = new LinkedHashMap<String, JsonObject>(); for (var r : read(Path.of(sources))) map.put(r.get("id").getAsString(), r); cduLabels = map; }
        Path out = Path.of(env("NR_OUT", "tmp/neural-retrain/features")); Files.createDirectories(out);
        int rows = 0, anchors = 0, skipped = 0;
        try (BufferedWriter w = Files.newBufferedWriter(out.resolve("features.jsonl"), StandardCharsets.UTF_8)) {
            for (String journal : env("NR_LABELS", "").split(java.io.File.pathSeparator)) {
                if (journal.isBlank()) continue;
                for (var row : read(Path.of(journal))) {
                    if (!(row.has("label") ? row.get("label").getAsBoolean() : row.has("strict") && row.get("strict").getAsBoolean())) continue;
                    var x = trainingRow(row);
                    if (x == null) { skipped++; continue; }
                    w.write(JSON.toJson(x)); w.newLine(); rows++; if (x.get("anchorAvailable").getAsBoolean()) anchors++;
                }
            }
        }
        // The sidecar's lump map (schema 5): each injectable off-axis compound and its U1 lump, as trainingRow lumps it.
        var all = new JsonObject(); all.add("compounds", JSON.toJsonTree(OFF_AXIS)); all.addProperty("fraction", 0.01);
        var every = injected(base[1], all); int[] map = lumpMap(every); var lumps = new TreeMap<String, String>();
        var ids = every.componentBasis().componentIds();
        for (int k = 0; k < ids.size(); k++) if (!axis().contains(ids.get(k))) lumps.put(ids.get(k), axis().get(map[k]));
        Files.writeString(out.resolve("lumpMap.json"), new GsonBuilder().setPrettyPrinting().create().toJson(lumps));
        System.out.println("nr export rows=" + rows + " anchors=" + anchors + " skipped=" + skipped + " lumps=" + lumps.size());
        physics();
    }

    /**
     * The sidecar's trained physics (schema 4/5, TrainedPhysics): the values of the axis package the labels were solved on, in
     * this catalog, written to NR_OUT/physics.json. export.py sets the trained temperature ceiling from the TRAIN feeds.
     */
    @Test void physics() throws Exception {
        setUp();
        Path out = Path.of(env("NR_OUT", "tmp/neural-retrain/features")); Files.createDirectories(out);
        String packageId = base[0].packageId();
        var physics = V3RegroupedTransformerTest.physics(catalog, packageId);
        physics.addProperty("source", "The values of package " + packageId + " in the catalog the N2 labels were solved with "
                + "(batch 2026-11-05-neural-retrain, tools/neural-retrain NeuralRetrainProbe.physics). Trained range: 298.15 K to the "
                + "trained feed temperature maximum (sidecar globalMax[1]). Pairs not listed are zero.");
        Files.writeString(out.resolve("physics.json"), new GsonBuilder().setPrettyPrinting().create().toJson(physics));
        System.out.println("nr physics " + packageId + " compounds=" + physics.getAsJsonArray("compounds").size()
                + " pairs=" + physics.getAsJsonArray("pairs").size());
    }

    // ---------------------------------------------------------------- bundled-model parity reference (N0)

    @Test void features() throws Exception {
        setUp();
        byte[] payload, sidecar;
        try (var p = getClass().getResourceAsStream("/data/icheme/neural/v3-column-transformer-open.payload.json");
             var s = getClass().getResourceAsStream("/data/icheme/neural/v3-column-transformer-open.sidecar.json")) { payload = p.readAllBytes(); sidecar = s.readAllBytes(); }
        if (!env("NR_PAYLOAD", "").isEmpty()) { payload = Files.readAllBytes(Path.of(env("NR_PAYLOAD", ""))); sidecar = Files.readAllBytes(Path.of(env("NR_SIDECAR", ""))); } // an exported candidate
        var model = V3NeuralRegistry.TransformerArtifact.read(new ByteArrayInputStream(payload), new ByteArrayInputStream(sidecar)).initializer();
        var inputs = new ArrayList<Map.Entry<String, V3ColumnInput>>();
        for (int g = 0; g < GRADES.length; g++) inputs.add(Map.entry("preset-" + GRADES[g], base[g]));
        String cases = env("NR_CASES", "");
        int limit = Integer.parseInt(env("NR_LIMIT", "50"));
        if (!cases.isEmpty()) for (var row : read(Path.of(cases))) {
            if (inputs.size() >= limit + GRADES.length) break;
            if (row.has("strict") && row.get("strict").getAsBoolean() && !vdu(row.getAsJsonObject("design")) && !row.getAsJsonObject("design").getAsJsonObject("composition").has("injection")) inputs.add(Map.entry(row.get("id").getAsString(), build(row.getAsJsonObject("design"))));
        }
        Path out = Path.of(env("NR_OUT", "tmp/neural-retrain/parity")); Files.createDirectories(out);
        try (BufferedWriter w = Files.newBufferedWriter(out.resolve("features.jsonl"), StandardCharsets.UTF_8)) {
            for (var e : inputs) {
                var in = e.getValue();
                var o = MaterialRuntime.with(catalogFor(in), in.packageId(), () -> {
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
                    x.addProperty("supported", model.supported(in)); // the composition family, topology and global bounds
                    return x;
                });
                w.write(JSON.toJson(o)); w.newLine();
                System.out.println("nr features " + e.getKey() + " branch=" + o.get("branch") + " anchor=" + o.get("anchorAvailable") + " supported=" + o.get("supported"));
            }
        }
    }

    /**
     * N6: the bundled model's float64 fixtures (V3BundledModelTest). For the NR_FIXTURE_IDS rows (presets or NR_CASES ids), the
     * input, the raw outputs and branch logits of the independent PyTorch float64 inference (NR_PYTHON, written by fixtures.py
     * from this probe's `features`), and the seed the Java decoder makes of those raw outputs on their best legal branch.
     */
    @Test void fixtures() throws Exception {
        setUp();
        byte[] payload = Files.readAllBytes(Path.of(env("NR_PAYLOAD", ""))), sidecar = Files.readAllBytes(Path.of(env("NR_SIDECAR", "")));
        var model = V3NeuralRegistry.TransformerArtifact.read(new ByteArrayInputStream(payload), new ByteArrayInputStream(sidecar)).initializer();
        var side = JsonParser.parseString(new String(sidecar, StandardCharsets.UTF_8)).getAsJsonObject();
        boolean[] seen = JSON.fromJson(side.get("branchesSeen"), boolean[].class);
        var python = JsonParser.parseString(Files.readString(Path.of(env("NR_PYTHON", "")))).getAsJsonObject();
        var byId = new LinkedHashMap<String, V3ColumnInput>();
        for (int g = 0; g < GRADES.length; g++) byId.put("preset-" + GRADES[g], base[g]);
        if (!env("NR_CASES", "").isEmpty()) for (var row : read(Path.of(env("NR_CASES", "")))) byId.put(row.get("id").getAsString(), build(row.getAsJsonObject("design")));
        var branches = new V3CondenserPhaseBranch[] {V3CondenserPhaseBranch.LIQUID_ONLY, V3CondenserPhaseBranch.TWO_PHASE, V3CondenserPhaseBranch.VAPOR_ONLY};
        var list = new JsonArray();
        for (String id : env("NR_FIXTURE_IDS", "").split(",")) {
            var in = byId.get(id); if (in == null) throw new IllegalArgumentException("No input " + id);
            var p = python.getAsJsonObject(id);
            double[][] raw = JSON.fromJson(p.get("raw"), double[][].class); double[] logits = JSON.fromJson(p.get("branchLogits"), double[].class);
            boolean reflux = in.specifications().stream().anyMatch(s -> s instanceof V3ColumnSpecification.OrganicRefluxRatio r && r.ratio() > 0);
            int best = -1;
            for (int i = 0; i < 3; i++) if (seen[i] && !(i == 2 && reflux) && (best < 0 || logits[i] > logits[best])) best = i;
            int b = best;
            var seed = MaterialRuntime.with(catalogFor(in), in.packageId(), () -> {
                if (!model.supported(in)) throw new IllegalStateException(id + " is outside the model's coverage");
                return V3FactorizedNeuralFeatures.decode(in, model.propertyRevision(in.packageId()), branches[b], raw, 0.02,
                        V3FactorizedNeuralFeatures.DecodeOptions.zeroPhaseFloor(10));
            });
            var input = inputJson(in); input.addProperty("schemaVersion", in.schemaVersion());
            var expected = new JsonObject(); expected.addProperty("branch", seed.branch().name());
            expected.add("temperatures", JSON.toJsonTree(seed.temperatures())); expected.add("liquid", JSON.toJsonTree(seed.liquid()));
            expected.add("vapor", JSON.toJsonTree(seed.vapor())); expected.add("freeWater", JSON.toJsonTree(seed.freeWater()));
            expected.add("wetTrays", JSON.toJsonTree(seed.wetTrays()));
            var x = new JsonObject(); x.addProperty("id", id); x.add("input", input); x.add("raw", p.get("raw")); x.add("branchLogits", p.get("branchLogits"));
            x.add("expected", expected); list.add(x);
            System.out.println("nr fixture " + id + " branch=" + seed.branch() + " stages=" + in.stageCount());
        }
        var o = new JsonObject(); o.addProperty("schemaVersion", 2); o.addProperty("modelId", model.modelId());
        o.addProperty("payloadSha256", V3NeuralRegistry.TransformerArtifact.sha256(payload)); o.addProperty("sidecarSha256", V3NeuralRegistry.TransformerArtifact.sha256(sidecar));
        o.addProperty("source", "Raw outputs and branch logits: independent PyTorch float64 inference (tools/neural-retrain/fixtures.py) on the probe's features; expected seed: the Java phase-floor decoder (ZERO_PHASE_FLOOR_10) on those raw outputs; N4 holdout and validation inputs");
        o.add("fixtures", list);
        Path out = Path.of(env("NR_OUT", "tmp/neural-retrain/fixtures.json")); Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, new GsonBuilder().setPrettyPrinting().create().toJson(o) + "\n");
    }
}
