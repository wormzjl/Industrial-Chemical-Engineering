package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.column.v3.thermo.V3WaterProperties;
import com.wormzjl.icheme.science.material.MaterialAxis;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialPresets;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

/**
 * C1 measurement of batch 2026-10-28-column-operation (plan section 14). Not a gate: it measures what the column unit's
 * split table can stand on. Homogeneity at alpha 0.5 and 1.2; the table's error against feed drift; warm solve success
 * and time; rule B's solves (steam short, condenser warmer). Writes tmp/column-op-c1/*.tsv and prints one line per case.
 */
class ColumnOperationC1BenchTest {
    private static final Path OUT = Path.of("tmp", "column-op-c1");
    private static final double SCREEN = V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO;
    private final List<String> rows = new ArrayList<>();

    /** Cheap look at the candidate designs: wet trays, cold time, kettle duties. */
    @Test void probe() throws IOException {
        var base = networkDefault();
        var cases = new LinkedHashMap<String, V3ColumnInput>();
        // Owner hint 2026-10-29: a lower condenser temperature with a high reflux gives free-water trays.
        for (double steam : new double[] {1, 2})
            for (double[] tr : new double[][] {{315, 6}, {310, 8}, {305, 8}, {300, 10}}) {
                var s = withSteam(base, steam);
                cases.put("steam x" + steam + " cond " + tr[0] + " reflux " + tr[1], rebuild(s, b -> b, s.feedTemperatureKelvin(),
                        specs(s, tr[0], tr[1], null), s.steamFeeds(), s.columnDiameterMetres(), 0));
            }
        for (var c : cases.entrySet()) {
            try { report("probe", c.getKey(), c.getValue(), cold(c.getValue())); }
            catch (RuntimeException e) { System.out.println("C1 probe " + c.getKey() + " threw " + e); }
        }
        write("probe");
    }

    /** The C1 sweep: every case is a warm solve from its design's accepted base, as the column unit would run it. */
    @Test void sweep() throws IOException {
        var designs = new LinkedHashMap<String, V3ColumnInput>();
        designs.put("dry", networkDefault());
        // Owner hint: a cold condenser with a high reflux. Tray 2 then stands 15.6 K below its water dew point; V3 publishes it
        // as a dry tray with the dew-point warning (owner decision), and no modelled wet tray forms on the network package.
        var wet = networkDefault();
        designs.put("wet", rebuild(wet, b -> b, wet.feedTemperatureKelvin(), specs(wet, 310.0, 8.0, null), wet.steamFeeds(),
                wet.columnDiameterMetres(), 0));
        designs.put("kettle", kettle(networkDefault(), 15e6));
        cold(designs.get("dry")); // JIT warm-up, untimed
        for (var design : designs.entrySet()) {
            String name = design.getKey();
            V3ColumnInput input = design.getValue();
            Solve base = cold(input);
            report("base", name, input, base);
            if (base.success() == null) continue;
            var seed = base.success().result().warmSeed().orElse(null);
            boolean steam = !input.steamFeeds().isEmpty();
            // Homogeneity with the column's hydraulics (diameter 8 m) and with a prescribed 0.7 kPa drop (diameter 0).
            for (double alpha : new double[] {0.5, 1.2}) compare("alpha", name, "hyd " + alpha, base, scaled(input, alpha), seed, alpha);
            V3ColumnInput prescribed = prescribed(input);
            Solve prescribedBase = cold(prescribed);
            if (prescribedBase.success() != null) {
                var pSeed = prescribedBase.success().result().warmSeed().orElse(null);
                for (double alpha : new double[] {0.5, 1.2})
                    compare("alpha", name, "drop " + alpha, prescribedBase, scaled(prescribed, alpha), pSeed, alpha);
            }
            for (double dt : new double[] {-10, -5, -2, 2, 5, 10})
                compare("feedT", name, String.format("%+.0f K", dt), base, feedTemperature(input, dt), seed, 1);
            MaterialCatalog catalog = MaterialRuntime.current();
            for (String other : new String[] {"wti_light_export", "cold_lake_blend"})
                for (double s : new double[] {0.01, 0.05, 0.10})
                    compare("feedZ", name, other + " " + s, base, toward(input, crude(catalog, other, input), s), seed, 1);
            if (steam) for (double cut : new double[] {0.10, 0.25, 0.50})
                compare("ruleB", name, "steam -" + Math.round(cut * 100) + "%", base, withSteam(input, 1 - cut), seed, 1);
            for (double dt : new double[] {5, 10})
                compare("ruleB", name, "condenser +" + dt + " K", base, condenser(input, dt), seed, 1);
        }
        write("sweep");
    }

    // ---- designs ----

    private static V3ColumnInput networkDefault() {
        var catalog = MaterialRuntime.current();
        return catalog.presets().column("tia_juana_nitrogen").input(catalog);
    }

    private static V3ColumnInput kettle(V3ColumnInput input, double watts) {
        return rebuild(input, b -> b, input.feedTemperatureKelvin(), specs(input, null, null, watts), List.of(),
                input.columnDiameterMetres(), input.stagePressureDropPascal());
    }

    private static V3ColumnInput withoutSteam(V3ColumnInput input) {
        return rebuild(input, b -> b, input.feedTemperatureKelvin(), input.specifications(), List.of(), input.columnDiameterMetres(),
                input.stagePressureDropPascal());
    }

    private static V3ColumnInput withSteam(V3ColumnInput input, double factor) {
        var steam = input.steamFeeds().stream().map(s -> new V3SteamFeedSpec(s.stageNumber(), s.molarFlowMolPerSecond() * factor,
                s.temperatureKelvin())).toList();
        return rebuild(input, b -> b, input.feedTemperatureKelvin(), input.specifications(), steam, input.columnDiameterMetres(),
                input.stagePressureDropPascal());
    }

    private static V3ColumnInput prescribed(V3ColumnInput input) {
        return rebuild(input, b -> b, input.feedTemperatureKelvin(), input.specifications(), input.steamFeeds(), 0.0, 700.0);
    }

    private static V3ColumnInput condenser(V3ColumnInput input, double dt) {
        double t = specValue(input, V3ColumnSpecification.CondenserOutletTemperature.class);
        return rebuild(input, b -> b, input.feedTemperatureKelvin(), specs(input, t + dt, null, null), input.steamFeeds(),
                input.columnDiameterMetres(), input.stagePressureDropPascal());
    }

    private static V3ColumnInput feedTemperature(V3ColumnInput input, double dt) {
        return rebuild(input, b -> b, input.feedTemperatureKelvin() + dt, input.specifications(), input.steamFeeds(),
                input.columnDiameterMetres(), input.stagePressureDropPascal());
    }

    /** Every extensive input times alpha: feed, steam, draws, pumparound and reboiler duties. */
    private static V3ColumnInput scaled(V3ColumnInput input, double alpha) {
        var steam = input.steamFeeds().stream().map(s -> new V3SteamFeedSpec(s.stageNumber(), s.molarFlowMolPerSecond() * alpha,
                s.temperatureKelvin())).toList();
        var draws = input.sideDraws().stream().map(d -> new V3SideDrawSpec(d.trayNumber(), d.molarFlowMolPerSecond() * alpha)).toList();
        var pas = input.pumparounds().stream().map(p -> new V3PumparoundSpec(p.returnTray(), p.drawTray(), p.dutyWatts() * alpha,
                p.split())).toList();
        double duty = specValue(input, V3ColumnSpecification.ReboilerDuty.class);
        double[] feed = input.feedComponentMolarFlowsMolPerSecond();
        for (int i = 0; i < feed.length; i++) feed[i] *= alpha;
        return new V3ColumnInput(input.schemaVersion(), input.packageId(), input.assayId(), input.componentBasis(), feed,
                input.feedTemperatureKelvin(), input.stageCount(), input.feedStageNumber(), input.topPressurePascal(),
                input.stagePressureDropPascal(), specs(input, null, null, duty * alpha), draws, steam, pas, input.columnDiameterMetres());
    }

    /** The feed moved by a fraction s toward another network crude, at the same total molar rate. */
    private static V3ColumnInput toward(V3ColumnInput input, double[] other, double s) {
        double[] feed = input.feedComponentMolarFlowsMolPerSecond();
        double total = Arrays.stream(feed).sum();
        double[] moved = new double[feed.length];
        for (int i = 0; i < feed.length; i++) moved[i] = total * ((1 - s) * feed[i] / total + s * other[i]);
        return new V3ColumnInput(input.schemaVersion(), input.packageId(), input.assayId(), input.componentBasis(), moved,
                input.feedTemperatureKelvin(), input.stageCount(), input.feedStageNumber(), input.topPressurePascal(),
                input.stagePressureDropPascal(), input.specifications(), input.sideDraws(), input.steamFeeds(), input.pumparounds(),
                input.columnDiameterMetres());
    }

    /** A network fluid preset's crude, projected on the column's axis (water dropped), as mole fractions. */
    private static double[] crude(MaterialCatalog catalog, String assay, V3ColumnInput input) {
        var source = catalog.requirePackage("icheme:" + assay + "_tjl20");
        var axis = new MaterialAxis(input.componentBasis().componentIds());
        double[] z = axis.project(new MaterialAxis(source.components()), MaterialPresets.moleFractions(source, "icheme:" + assay));
        double sum = Arrays.stream(z).sum();
        for (int i = 0; i < z.length; i++) z[i] /= sum;
        return z;
    }

    private static V3ColumnInput rebuild(V3ColumnInput input, UnaryOperator<double[]> feed, double feedT,
            List<V3ColumnSpecification> specs, List<V3SteamFeedSpec> steam, double diameter, double drop) {
        return new V3ColumnInput(input.schemaVersion(), input.packageId(), input.assayId(), input.componentBasis(),
                feed.apply(input.feedComponentMolarFlowsMolPerSecond()), feedT, input.stageCount(), input.feedStageNumber(),
                input.topPressurePascal(), drop, specs, input.sideDraws(), steam, input.pumparounds(), diameter);
    }

    private static List<V3ColumnSpecification> specs(V3ColumnInput input, Double condenser, Double reflux, Double duty) {
        return List.of(
                new V3ColumnSpecification.CondenserOutletTemperature(condenser != null ? condenser
                        : specValue(input, V3ColumnSpecification.CondenserOutletTemperature.class)),
                new V3ColumnSpecification.OrganicRefluxRatio(reflux != null ? reflux
                        : specValue(input, V3ColumnSpecification.OrganicRefluxRatio.class)),
                new V3ColumnSpecification.ReboilerDuty(duty != null ? duty : specValue(input, V3ColumnSpecification.ReboilerDuty.class)));
    }

    private static double specValue(V3ColumnInput input, Class<? extends V3ColumnSpecification> type) {
        for (var spec : input.specifications()) {
            if (!type.isInstance(spec)) continue;
            if (spec instanceof V3ColumnSpecification.CondenserOutletTemperature t) return t.kelvin();
            if (spec instanceof V3ColumnSpecification.OrganicRefluxRatio r) return r.ratio();
            if (spec instanceof V3ColumnSpecification.ReboilerDuty q) return q.watts();
        }
        return 0.0;
    }

    // ---- solves ----

    record Solve(V3ColumnOutcome outcome, double seconds) {
        V3ColumnOutcome.Success success() { return outcome instanceof V3ColumnOutcome.Success s ? s : null; }
        String path() {
            var d = outcome instanceof V3ColumnOutcome.Success s ? s.diagnostics()
                    : ((V3ColumnOutcome.Failure) outcome).diagnostics();
            return d.solvePath() + " n=" + d.newtonIterations();
        }
        String status() { return outcome instanceof V3ColumnOutcome.Failure f ? f.code().name() : "OK"; }
    }

    private static Solve cold(V3ColumnInput input) {
        long start = System.nanoTime();
        var outcome = V3ColumnCalculator.calculate(input, deadline(start), 0, 0, V3TestInputs.LEARNED, V3NeuralRegistry.bundled(), SCREEN);
        return new Solve(outcome, (System.nanoTime() - start) * 1e-9);
    }

    private static Solve warm(V3ColumnInput input, V3NeuralSeed seed) {
        long start = System.nanoTime();
        var outcome = V3ColumnCalculator.calculateWithStrategy(input, deadline(start), V3SolveStrategy.AGGRESSIVE, seed, SCREEN);
        return new Solve(outcome, (System.nanoTime() - start) * 1e-9);
    }

    private static V3SolveControl deadline(long start) {
        return () -> {
            if (System.nanoTime() - start > 45_000_000_000L) throw new java.util.concurrent.CancellationException("45 s deadline");
        };
    }

    // ---- the table and its errors ----

    /** Component flows per stream on the public axis plus water, the stream's T and P. */
    record Product(String id, String phase, Map<String, Double> flows, double t, double p) {}

    private static List<Product> products(V3ColumnOutcome.Success success) {
        var out = new ArrayList<Product>();
        for (var s : success.result().streams()) {
            var flows = new LinkedHashMap<String, Double>();
            for (var f : s.moleFractions()) flows.put(f.componentId(), s.molarFlowMolPerSecond() * f.moleFraction());
            out.add(new Product(s.streamId(), s.phase(), flows, s.temperatureKelvin(), s.pressurePascal()));
        }
        return out;
    }

    private static Map<String, Double> inputs(V3ColumnInput input) {
        var in = new LinkedHashMap<String, Double>();
        double[] feed = input.feedComponentMolarFlowsMolPerSecond();
        var ids = input.componentBasis().componentIds();
        for (int i = 0; i < feed.length; i++) in.put(ids.get(i), feed[i]);
        in.put("Water", input.steamFeeds().stream().mapToDouble(V3SteamFeedSpec::molarFlowMolPerSecond).sum());
        return in;
    }

    /** The base's recoveries applied to the new input: the split table's products. */
    private static List<Product> table(V3ColumnOutcome.Success base, V3ColumnInput baseInput, V3ColumnInput input) {
        var in0 = inputs(baseInput);
        var in1 = inputs(input);
        var out = new ArrayList<Product>();
        for (var p : products(base)) {
            var flows = new LinkedHashMap<String, Double>();
            p.flows().forEach((id, n) -> flows.put(id, in0.getOrDefault(id, 0.0) > 0 ? n / in0.get(id) * in1.getOrDefault(id, 0.0) : 0.0));
            out.add(new Product(p.id(), p.phase(), flows, p.t(), p.p()));
        }
        return out;
    }

    /** Hydrocarbons by a TP flash at the stream's T and P; water as vapour in a vapour stream, else liquid. */
    private static double enthalpyWatts(V3ColumnInput input, List<Product> products) {
        var thermo = V3PengRobinsonThermo.fromRegisteredPackage(input.packageId());
        var ids = input.componentBasis().componentIds();
        double total = 0;
        for (var p : products) {
            double[] hc = new double[ids.size()];
            double sum = 0;
            for (int i = 0; i < hc.length; i++) { hc[i] = p.flows().getOrDefault(ids.get(i), 0.0); sum += hc[i]; }
            if (sum > 1e-12) {
                double[] z = hc.clone();
                for (int i = 0; i < z.length; i++) z[i] /= sum;
                double h;
                try {
                    h = thermo.flashTP(p.t(), p.p(), z, thermo.newWorkspace()).molarEnthalpyJoulesPerMol();
                } catch (com.wormzjl.icheme.science.column.v3.thermo.V3ThermoException nearSaturation) {
                    // A tabled product sits near its own bubble or dew point: take the stream's own phase root.
                    h = thermo.molarEnthalpy(p.t(), p.p(), z, p.phase().toLowerCase().contains("vap")
                            ? com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root.VAPOR
                            : com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root.LIQUID, thermo.newWorkspace());
                }
                total += sum * h;
            }
            double water = p.flows().getOrDefault("Water", 0.0);
            if (water > 0) total += water * (p.phase().toLowerCase().contains("vap")
                    ? V3WaterProperties.vaporMolarEnthalpy(p.t()) : V3WaterProperties.liquidMolarEnthalpy(p.t()));
        }
        return total;
    }

    /** The condenser duty that closes the energy balance on these products (column sign: negative = removed). */
    private static double closedCondenser(V3ColumnOutcome.Success solvedForInput, V3ColumnInput input, List<Product> products) {
        var ledger = solvedForInput.result().dutyLedger().orElseThrow();
        return enthalpyWatts(input, products) - ledger.feedEnthalpyWatts() - ledger.steamEnthalpyWatts() - ledger.reboilerWatts()
                - ledger.stageHeatTotalWatts();
    }

    private void compare(String kind, String design, String label, Solve base, V3ColumnInput input, V3NeuralSeed seed, double alpha) {
        Solve solve = warm(input, seed);
        var baseInput = base.success().result().problem().input();
        String line;
        if (solve.success() == null) {
            line = String.join("\t", kind, design, label, solve.status(), fmt(solve.seconds()), solve.path());
        } else {
            var solved = products(solve.success());
            var tabled = table(base.success(), baseInput, input);
            double flowErr = 0, compErr = 0, tErr = 0;
            for (var s : solved) {
                var t = tabled.stream().filter(p -> p.id().equals(s.id())).findFirst().orElse(null);
                if (t == null) { flowErr = Double.NaN; continue; }
                double fs = s.flows().values().stream().mapToDouble(Double::doubleValue).sum();
                double ft = t.flows().values().stream().mapToDouble(Double::doubleValue).sum();
                flowErr = Math.max(flowErr, Math.abs(ft - fs) / fs);
                double l1 = 0;
                for (var id : s.flows().keySet()) l1 += Math.abs(t.flows().getOrDefault(id, 0.0) / ft - s.flows().get(id) / fs);
                compErr = Math.max(compErr, l1);
                tErr = Math.max(tErr, Math.abs(s.t() - t.t()));
            }
            double qSolved = solve.success().result().dutyLedger().orElseThrow().condenserWatts();
            double qTable = closedCondenser(solve.success(), input, tabled);
            double qCheck = closedCondenser(solve.success(), input, solved);
            double qRef = base.success().result().dutyLedger().orElseThrow().condenserWatts() * alpha;
            var nodes = solve.success().result().inspection().orElseThrow().nodes();
            var baseNodes = base.success().result().inspection().orElseThrow().nodes();
            double sumpDp = nodes.getLast().pressurePascal() - baseNodes.getLast().pressurePascal();
            double sumpDt = nodes.getLast().temperatureKelvin() - baseNodes.getLast().temperatureKelvin();
            line = String.join("\t", kind, design, label, solve.status(), fmt(solve.seconds()), solve.path(),
                    "flowErr=" + sci(flowErr), "compL1=" + sci(compErr), "dTprod=" + fmt(tErr),
                    "Qc=" + fmt(qSolved / 1e6), "QcTable=" + fmt(qTable / 1e6), "QcCheck=" + fmt(qCheck / 1e6),
                    "QcErr=" + pct((qTable - qSolved) / qSolved), "Qc/aQc0=" + fmt(qSolved / qRef),
                    "sumpDp=" + fmt(sumpDp / 1e3) + "kPa", "sumpDt=" + fmt(sumpDt) + "K", "wet=" + wet(solve.success()),
                    "dZ=" + sci(l1(inputs(input), inputs(baseInput))));
        }
        rows.add(line);
        System.out.println("C1 " + line);
    }

    private static double l1(Map<String, Double> a, Map<String, Double> b) {
        double sa = 0, sb = 0, l1 = 0;
        for (var id : a.keySet()) if (!id.equals("Water")) { sa += a.get(id); sb += b.getOrDefault(id, 0.0); }
        for (var id : a.keySet()) if (!id.equals("Water")) l1 += Math.abs(a.get(id) / sa - b.getOrDefault(id, 0.0) / sb);
        return l1;
    }

    private void report(String kind, String design, V3ColumnInput input, Solve solve) {
        String extra = "";
        if (solve.success() != null) {
            var ledger = solve.success().result().dutyLedger().orElseThrow();
            var hyd = solve.success().result().trayHydraulics();
            extra = String.join("\t", "Qc=" + fmt(ledger.condenserWatts() / 1e6), "Qr=" + fmt(ledger.reboilerWatts() / 1e6),
                    "wet=" + wet(solve.success()), "water=" + solve.success().diagnostics().acceptanceAudit().advisoryEvidence().stream()
                            .filter(a -> a.toLowerCase().contains("water")).map(a -> a.length() > 140 ? a.substring(0, 140) : a).toList()
                            + solve.success().diagnostics().events().stream().filter(e -> e.startsWith("wet trays")).toList(),
                    "QcCheck=" + fmt(closedCondenser(solve.success(), input, products(solve.success())) / 1e6),
                    "flood=" + hyd.map(h -> fmt(h.maximumFloodFraction())).orElse("-"),
                    "drop=" + hyd.map(h -> fmt(h.totalPressureDropPascal() / 1e3)).orElse("-") + "kPa",
                    "streams=" + solve.success().result().streams().stream().map(s -> s.streamId() + ":" + fmt(s.molarFlowMolPerSecond())
                            + "@" + fmt(s.temperatureKelvin())).toList());
        }
        String line = String.join("\t", kind, design, "-", solve.status(), fmt(solve.seconds()), solve.path(), extra);
        rows.add(line);
        System.out.println("C1 " + line);
    }

    private static int wet(V3ColumnOutcome.Success success) {
        var seed = success.result().warmSeed().orElse(null);
        if (seed == null) return -1;
        int wet = 0;
        for (double w : seed.freeWater()) if (w != 0) wet++;
        return wet;
    }

    private void write(String name) throws IOException {
        Files.createDirectories(OUT);
        Files.write(OUT.resolve(name + "-" + (NativeColumnSolver.thermoBatchEnabled() ? "native" : "java") + ".tsv"), rows);
    }

    private static String fmt(double v) { return String.format("%.3f", v); }
    private static String sci(double v) { return String.format("%.2e", v); }
    private static String pct(double v) { return String.format("%.2f%%", v * 100); }
}
