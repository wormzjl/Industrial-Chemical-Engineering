package com.wormzjl.icheme.science.column.batch;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnOutcome;
import com.wormzjl.icheme.science.column.v3.V3ColumnSpecification;
import com.wormzjl.icheme.science.column.v3.V3ColumnStreamProperties;
import com.wormzjl.icheme.science.column.v3.V3NeuralSeed;
import com.wormzjl.icheme.science.column.v3.thermo.V3PengRobinsonThermo;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnUnit;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Batch 2026-11-09-batch-distiller, plan section 7 (owner B3): the bubble-point column ({@link BubblePointColumn}) against
 * V3 on the same problem, V3's topology (total condenser, N trays, reboiler) with the pot liquid fed into the reboiler at
 * 20 times the boil-up: the reboiler stays at the pot's composition, the still's pot. The measurement runs only with
 * {@code ICHEME_BATCH_COLUMN=1}; its report goes to {@code build/batch-column/}.
 */
class BatchColumnComparisonTest {
    /** V3 refuses {@code icheme:refinery}: it admits CO2 crystals, and the V3 column has no crystal equations. */
    private static final String PACKAGE = "icheme:tjl20_methane_nitrogen";
    private static final double P = 101_325, BOIL_UP = 1, FEED = 20, TOLERANCE = 1e-4;
    private static final int MAX_ITERATIONS = 300;
    private static final double[] DISTILLED = {0, .25, .5, .7};
    private static final int[] STAGES = {2, 4, 8};
    private static final double[] REFLUX = {.5, 2, 8};

    /**
     * A naphtha pot under four trays at reflux 2 sends a pentane-rich distillate (the still's own mode), in both forms; at a
     * boil-up of 1 mol/s the distillate is near V / (R + 1), exactly so on constant molar overflow.
     */
    @Test
    void aNaphthaPotRectifies() {
        var catalog = MaterialCatalog.bundled();
        MaterialRuntime.with(catalog, PACKAGE, () -> {
            var thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE);
            double[] z = charge(thermo, NAPHTHA);
            var ids = thermo.componentBasis().componentIds();
            double pot = z[ids.indexOf("Isopentane")] + z[ids.indexOf("N-pentane")];
            for (boolean energy : new boolean[]{false, true}) {
                var column = new BubblePointColumn(thermo, energy);
                double duty = BOIL_UP * column.latentHeat(z, P);
                var r = column.solve(new BubblePointColumn.Spec(4, 0, P, 2, duty, 0, z), null, MAX_ITERATIONS, TOLERANCE);
                assertTrue(r.converged(), r.failure());
                double top = r.distillateComposition()[ids.indexOf("Isopentane")] + r.distillateComposition()[ids.indexOf("N-pentane")];
                assertTrue(top > pot + .3, "pentane-rich: " + top + " against " + pot);
                assertTrue(Math.abs(r.distillate() - BOIL_UP / 3) < (energy ? .05 : .02) * BOIL_UP, "D near V / (R + 1): " + r.distillate());
            }
            return null;
        });
    }

    private static final Map<String, Double> NAPHTHA = Map.of("Isopentane", .20, "N-pentane", .30, "crude_pc01", .25, "crude_pc02", .15,
            "crude_pc03", .10);

    private record Charge(String name, double[] z) {
    }

    private record Row(String charge, int stages, double reflux, double distilled, boolean bpCold, int bpIterations, double bpColdMs,
                       Boolean bpWarm, int bpWarmIterations, double bpWarmMs, boolean v3Cold, double v3ColdMs, Boolean v3Warm,
                       double v3WarmMs, boolean v3WarmRoute, double dx, double dT, String note, double dxWarm, double dxV3, String worst) {
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ICHEME_BATCH_COLUMN", matches = "1")
    void bubblePointAgainstV3() throws IOException {
        var catalog = MaterialCatalog.bundled();
        var report = MaterialRuntime.with(catalog, PACKAGE, () -> run(catalog));
        Path out = Path.of("build", "batch-column");
        Files.createDirectories(out);
        Files.writeString(out.resolve("report.txt"), report);
        System.out.println(report);
    }

    private String run(MaterialCatalog catalog) {
        var thermo = V3PengRobinsonThermo.fromRegisteredPackage(PACKAGE);
        // Form 1 constant molar overflow, form 2 the energy-balance flow update; V3 is the reference for both.
        var forms = List.of(new BubblePointColumn(thermo), new BubblePointColumn(thermo, true));
        String[] names = {"cmo", "energy"};
        var column = forms.getFirst();
        var charges = charges(thermo);
        // Warm-up for all, discarded: the JIT, the neural registry and the PR caches.
        for (int pass = 0; pass < 2; pass++) for (var form : forms) for (var c : charges) snapshot(catalog, thermo, form, column, c, 4, 2, 0, c.z(), null, null);
        var rows = new ArrayList<Row>();
        var csv = new StringBuilder("charge,stages,reflux,distilled,bp_cold,bp_iterations,bp_cold_ms,bp_warm,bp_warm_iterations,bp_warm_ms,v3_cold,v3_cold_ms,v3_warm,v3_warm_ms,v3_warm_route,dx,dT,note,dx_warm,dx_v3,worst\n");
        for (int form = 0; form < forms.size(); form++)
        for (var charge : charges) {
            var path = rayleigh(column, charge.z());
            var c = new Charge(names[form] + ":" + charge.name(), charge.z());
            var model = forms.get(form);
            for (int n : STAGES)
                for (double reflux : REFLUX) {
                    double[] warm = null;
                    V3NeuralSeed seed = null;
                    for (int s = 0; s < DISTILLED.length; s++) {
                        var row = snapshot(catalog, thermo, model, column, c, n, reflux, DISTILLED[s], path.get(s), warm, seed);
                        rows.add(row.row);
                        if (row.warm != null) warm = row.warm;
                        if (row.seed != null) seed = row.seed;
                        var r = row.row;
                        csv.append(String.format(Locale.ROOT, "%s,%d,%.1f,%.2f,%b,%d,%.2f,%s,%d,%.2f,%b,%.2f,%s,%.2f,%b,%.5f,%.3f,%s,%.5f,%.5f,%s%n", r.charge, r.stages,
                                r.reflux, r.distilled, r.bpCold, r.bpIterations, r.bpColdMs, r.bpWarm, r.bpWarmIterations, r.bpWarmMs, r.v3Cold, r.v3ColdMs,
                                r.v3Warm, r.v3WarmMs, r.v3WarmRoute, r.dx, r.dT, r.note.replace(',', ';'), r.dxWarm, r.dxV3, r.worst));
                    }
                }
        }
        try {
            Path out = Path.of("build", "batch-column");
            Files.createDirectories(out);
            Files.writeString(out.resolve("snapshots.csv"), csv);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        var text = new StringBuilder();
        text.append("Snapshots (pot on its Rayleigh path; V3 topology, feed 20 x boil-up into the reboiler)\n");
        text.append(String.format(Locale.ROOT, "%-16s %5s %5s | %-22s | %-22s | %-22s | %-22s | %s%n", "charge", "n", "R", "BP cold ok/ms/it", "BP warm ok/ms/it",
                "V3 cold ok/ms", "V3 warm ok/ms/route", "max dx / max dT"));
        var groups = new LinkedHashMap<String, List<Row>>();
        for (var r : rows) groups.computeIfAbsent(r.charge + "|" + r.stages + "|" + r.reflux, k -> new ArrayList<>()).add(r);
        for (var g : groups.values()) {
            var f = g.getFirst();
            text.append(String.format(Locale.ROOT, "%-16s %5d %5.1f | %-22s | %-22s | %-22s | %-22s | %.4f / %.2f K%n", f.charge, f.stages, f.reflux,
                    summary(g, Row::bpCold, Row::bpColdMs, r -> (double) r.bpIterations),
                    summary(g.subList(1, g.size()), r -> Boolean.TRUE.equals(r.bpWarm), Row::bpWarmMs, r -> (double) r.bpWarmIterations),
                    summary(g, Row::v3Cold, Row::v3ColdMs, null),
                    summary(g.subList(1, g.size()), r -> Boolean.TRUE.equals(r.v3Warm), Row::v3WarmMs, r -> r.v3WarmRoute ? 1 : 0),
                    g.stream().mapToDouble(Row::dx).filter(Double::isFinite).max().orElse(Double.NaN),
                    g.stream().mapToDouble(r -> Math.abs(r.dT)).filter(Double::isFinite).max().orElse(Double.NaN)));
        }
        for (var name : names) text.append("\n").append(name).append(' ').append(totals(rows.stream().filter(r -> r.charge.startsWith(name + ":")).toList()).substring(1));
        text.append(sensible(thermo, column, charges));
        text.append("\nWhole batches (n = 4, R = 2, 1 % of the charge a step to 70 %; each model on its own path)\n");
        for (var c : charges) {
            text.append(batch(catalog, thermo, forms.getFirst(), c, "BP cmo", true));
            text.append(batch(catalog, thermo, forms.getLast(), c, "BP energy", false));
        }
        var failures = rows.stream().filter(r -> !r.note.isEmpty()).map(r -> String.format(Locale.ROOT, "  %s n=%d R=%.1f %.0f%%: %s", r.charge, r.stages, r.reflux,
                100 * r.distilled, r.note)).toList();
        if (!failures.isEmpty()) text.append("\nNotes\n").append(String.join("\n", failures)).append('\n');
        return text.toString();
    }

    private interface Pass {
        boolean test(Row r);
    }

    private interface Number {
        double of(Row r);
    }

    private static String summary(List<Row> rows, Pass ok, Number ms, Number extra) {
        int good = 0;
        double[] times = rows.stream().filter(ok::test).mapToDouble(ms::of).sorted().toArray();
        good = times.length;
        double median = times.length == 0 ? Double.NaN : times[times.length / 2];
        double add = extra == null ? Double.NaN : rows.stream().filter(ok::test).mapToDouble(extra::of).average().orElse(Double.NaN);
        return extra == null ? String.format(Locale.ROOT, "%d/%d %.1f", good, rows.size(), median)
                : String.format(Locale.ROOT, "%d/%d %.1f %.1f", good, rows.size(), median, add);
    }

    private static String totals(List<Row> rows) {
        long bpCold = rows.stream().filter(Row::bpCold).count(), v3Cold = rows.stream().filter(Row::v3Cold).count();
        long bpWarm = rows.stream().filter(r -> Boolean.TRUE.equals(r.bpWarm)).count(), v3Warm = rows.stream().filter(r -> Boolean.TRUE.equals(r.v3Warm)).count();
        long warmTried = rows.stream().filter(r -> r.bpWarm != null).count();
        long bpWhereV3 = rows.stream().filter(r -> r.v3Cold && !r.bpCold).count();
        double[] bpc = rows.stream().filter(Row::bpCold).mapToDouble(Row::bpColdMs).sorted().toArray();
        double[] v3c = rows.stream().filter(Row::v3Cold).mapToDouble(Row::v3ColdMs).sorted().toArray();
        double[] bpw = rows.stream().filter(r -> Boolean.TRUE.equals(r.bpWarm)).mapToDouble(Row::bpWarmMs).sorted().toArray();
        double[] v3w = rows.stream().filter(r -> Boolean.TRUE.equals(r.v3Warm)).mapToDouble(Row::v3WarmMs).sorted().toArray();
        double[] dx = rows.stream().mapToDouble(Row::dx).filter(Double::isFinite).sorted().toArray();
        double[] dT = rows.stream().mapToDouble(r -> Math.abs(r.dT)).filter(Double::isFinite).sorted().toArray();
        double[] dxw = rows.stream().mapToDouble(Row::dxWarm).filter(Double::isFinite).sorted().toArray();
        double[] dxv = rows.stream().mapToDouble(Row::dxV3).filter(Double::isFinite).sorted().toArray();
        return String.format(Locale.ROOT, "%nTotals: BP cold %d/%d, V3 cold %d/%d, BP warm %d/%d, V3 warm %d/%d; BP failed where V3 converged: %d%n"
                        + "median ms: BP cold %.1f, V3 cold %.1f, BP warm %.1f, V3 warm %.1f; p95 ms: BP cold %.1f, V3 cold %.1f%n"
                        + "distillate dx: median %.4f, p95 %.4f, max %.4f; reboiler dT: median %.2f, p95 %.2f, max %.2f K%n"
                        + "distillate dx against V3 warm: median %.4f, p95 %.4f, max %.4f; V3 cold against V3 warm: median %.4f, p95 %.4f, max %.4f%n",
                bpCold, rows.size(), v3Cold, rows.size(), bpWarm, warmTried, v3Warm, warmTried, bpWhereV3, q(bpc, .5), q(v3c, .5), q(bpw, .5), q(v3w, .5),
                q(bpc, .95), q(v3c, .95), q(dx, .5), q(dx, .95), q(dx, 1), q(dT, .5), q(dT, .95), q(dT, 1), q(dxw, .5), q(dxw, .95), q(dxw, 1), q(dxv, .5), q(dxv, .95), q(dxv, 1));
    }

    private static double q(double[] sorted, double quantile) {
        if (sorted.length == 0) return Double.NaN;
        return sorted[Math.min(sorted.length - 1, (int) Math.floor(quantile * (sorted.length - 1)))];
    }

    private record Snapshot(Row row, double[] warm, V3NeuralSeed seed) {
    }

    private Snapshot snapshot(MaterialCatalog catalog, V3PengRobinsonThermo thermo, BubblePointColumn column, BubblePointColumn setpoints, Charge c,
                              int n, double reflux, double distilled, double[] pot, double[] warm, V3NeuralSeed seed) {
        var note = new StringBuilder();
        double lambda = column.latentHeat(pot, P), feedT = column.bubbleTemperature(pot, P, Double.NaN);
        double duty = BOIL_UP * lambda;
        var spec = new BubblePointColumn.Spec(n, n + 1, P, reflux, duty, FEED, pot);
        long t0 = System.nanoTime();
        var cold = column.solve(spec, null, MAX_ITERATIONS, TOLERANCE);
        double bpColdMs = (System.nanoTime() - t0) / 1e6;
        if (!cold.converged()) note.append("BP cold: ").append(cold.failure()).append(' ');
        BubblePointColumn.Result hot = null;
        double bpWarmMs = Double.NaN;
        if (warm != null) {
            t0 = System.nanoTime();
            hot = column.solve(spec, warm, MAX_ITERATIONS, TOLERANCE);
            bpWarmMs = (System.nanoTime() - t0) / 1e6;
            if (!hot.converged()) note.append("BP warm: ").append(hot.failure()).append(' ');
        }
        var reference = setpoints == column ? cold : setpoints.solve(spec, null, MAX_ITERATIONS, TOLERANCE);
        double condenserT = reference.converged() ? reference.temperatures()[0] - 1 : feedT - 30;
        var input = v3Input(thermo, n, reflux, duty, pot, feedT, condenserT);
        t0 = System.nanoTime();
        var v3 = ColumnUnit.solve(input, null, catalog, () -> {
        });
        double v3ColdMs = (System.nanoTime() - t0) / 1e6;
        boolean v3Ok = v3 instanceof V3ColumnOutcome.Success;
        if (v3 instanceof V3ColumnOutcome.Failure f) note.append("V3 cold: ").append(f.code()).append(' ');
        Boolean v3Warm = null;
        double v3WarmMs = Double.NaN;
        boolean route = false;
        double[] warmXd = null;
        if (seed != null) {
            t0 = System.nanoTime();
            var again = ColumnUnit.solve(input, seed, catalog, () -> {
            });
            v3WarmMs = (System.nanoTime() - t0) / 1e6;
            v3Warm = again instanceof V3ColumnOutcome.Success;
            if (again instanceof V3ColumnOutcome.Success s) {
                route = s.diagnostics().solvePath().contains("warm");
                warmXd = distillate(thermo, s);
            } else if (again instanceof V3ColumnOutcome.Failure f) note.append("V3 warm: ").append(f.code()).append(' ');
        }
        double dx = Double.NaN, dT = Double.NaN, dxWarm = Double.NaN, dxV3 = Double.NaN;
        String worst = "";
        double[] coldXd = v3 instanceof V3ColumnOutcome.Success s ? distillate(thermo, s) : null;
        if (cold.converged() && coldXd != null) {
            int at = 0;
            for (int i = 0; i < coldXd.length; i++) if (Math.abs(coldXd[i] - cold.distillateComposition()[i]) > Math.abs(coldXd[at] - cold.distillateComposition()[at])) at = i;
            dx = Math.abs(coldXd[at] - cold.distillateComposition()[at]);
            worst = String.format(Locale.ROOT, "%s V3 %.4f BP %.4f", thermo.componentBasis().componentId(at), coldXd[at], cold.distillateComposition()[at]);
            var bottoms = stream((V3ColumnOutcome.Success) v3, "bottoms_liquid");
            if (bottoms != null) dT = cold.reboilerTemperature() - bottoms.temperatureKelvin();
        }
        if (cold.converged() && warmXd != null) dxWarm = maxDiff(warmXd, cold.distillateComposition());
        if (coldXd != null && warmXd != null) dxV3 = maxDiff(coldXd, warmXd);
        var row = new Row(c.name(), n, reflux, distilled, cold.converged(), cold.iterations(), bpColdMs, hot == null ? null : hot.converged(),
                hot == null ? 0 : hot.iterations(), bpWarmMs, v3Ok, v3ColdMs, v3Warm, v3WarmMs, route, dx, dT, note.toString().trim(), dxWarm, dxV3, worst);
        V3NeuralSeed next = v3 instanceof V3ColumnOutcome.Success s ? s.result().warmSeed().orElse(null) : null;
        return new Snapshot(row, cold.converged() ? cold.temperatures() : null, next);
    }

    /** Both models along their own batch: 1 % of the charge distilled a step to 70 %, each warm from its last step. */
    private String batch(MaterialCatalog catalog, V3PengRobinsonThermo thermo, BubblePointColumn column, Charge c, String label, boolean withV3) {
        int n = 4, components = c.z().length;
        double reflux = 2;
        var text = new StringBuilder();
        for (String model : withV3 ? new String[]{"BP", "POT", "V3"} : new String[]{"BP", "POT"}) {
            double[] pot = c.z().clone();
            for (int i = 0; i < components; i++) pot[i] *= 100;
            double[] cut = new double[components];
            double[] warm = null;
            V3NeuralSeed seed = null;
            int failures = 0, steps = 0;
            long spent = 0;
            var cuts = new StringBuilder();
            for (int step = 1; step <= 70; step++) {
                double[] z = BubblePointColumn.normalized(pot);
                double lambda = column.latentHeat(z, P), feedT = column.bubbleTemperature(z, P, Double.NaN);
                double[] xd = null;
                if (!model.equals("V3")) {
                    long t0 = System.nanoTime();
                    var spec = model.equals("POT") ? new BubblePointColumn.Spec(n, 0, P, reflux, BOIL_UP * lambda, 0, z)
                            : new BubblePointColumn.Spec(n, n + 1, P, reflux, BOIL_UP * lambda, FEED, z);
                    var r = column.solve(spec, warm, MAX_ITERATIONS, TOLERANCE);
                    spent += System.nanoTime() - t0;
                    if (r.converged()) {
                        warm = r.temperatures();
                        xd = r.distillateComposition();
                    }
                } else {
                    double condenserT = Double.NaN;
                    var bp = column.solve(new BubblePointColumn.Spec(n, n + 1, P, reflux, BOIL_UP * lambda, FEED, z), warm, MAX_ITERATIONS, TOLERANCE);
                    if (bp.converged()) {
                        warm = bp.temperatures();
                        condenserT = bp.temperatures()[0] - 1;
                    }
                    if (!Double.isFinite(condenserT)) condenserT = feedT - 30;
                    var v3Problem = v3Input(thermo, n, reflux, BOIL_UP * lambda, z, feedT, condenserT);
                    long t0 = System.nanoTime();
                    var outcome = ColumnUnit.solve(v3Problem, seed, catalog, () -> {
                    });
                    spent += System.nanoTime() - t0;
                    if (outcome instanceof V3ColumnOutcome.Success s) {
                        seed = s.result().warmSeed().orElse(seed);
                        var d = stream(s, "distillate_liquid");
                        if (d != null) {
                            xd = new double[components];
                            var ids = thermo.componentBasis().componentIds();
                            for (var f : d.moleFractions()) {
                                int i = ids.indexOf(f.componentId());
                                if (i >= 0) xd[i] = f.moleFraction();
                            }
                        }
                    }
                }
                steps++;
                if (xd == null) {
                    failures++;
                    xd = z;
                }
                for (int i = 0; i < components; i++) {
                    double take = Math.min(pot[i], xd[i]);
                    pot[i] -= take;
                    cut[i] += take;
                }
                if (step == 25 || step == 50 || step == 70) {
                    double[] avg = BubblePointColumn.normalized(cut);
                    cuts.append(String.format(Locale.ROOT, " %d%%: %s", step, top(thermo, avg, 3)));
                }
            }
            double seconds = spent / 1e9;
            text.append(String.format(Locale.ROOT, "%-10s %-13s: %d steps, %d failed, %.2f s;%s%n", c.name(), model.equals("V3") ? model : model.equals("POT") ? label + " pot" : label, steps, failures, seconds, cuts));
        }
        return text.toString();
    }

    /**
     * The pot's own heating along its simple-distillation path, from the energy balance of one step of 1 mol distilled
     * from a 100 mol charge at the fraction a distilled: the heat Q = N1 h(x1, T1) - N0 h(x0, T0) + H_V(y, T), its warming
     * part N cp (T1 - T0) at the pot's composition, and the rest, the boil-up heat. Ratio = warming over boil-up.
     */
    private static String sensible(V3PengRobinsonThermo thermo, BubblePointColumn column, List<Charge> charges) {
        var text = new StringBuilder("\nPot heating against boil-up (simple distillation path, a 1 mol step of a 100 mol charge; ratio = warming / boil-up heat)\n");
        var workspace = thermo.newWorkspace();
        var liquid = com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root.LIQUID;
        var vapour = com.wormzjl.icheme.science.thermo.PengRobinsonKernel.Root.VAPOR;
        for (var c : charges) {
            var path = rayleighFine(column, c.z());
            text.append(String.format(Locale.ROOT, "%-10s", c.name()));
            for (double a : new double[]{0, .25, .5, .7}) {
                int i = (int) Math.round(a * 200);
                double[] x0 = path.get(i), xm = path.get(i + 1), x1 = path.get(i + 2);
                double t0 = column.bubbleTemperature(x0, P, Double.NaN), tm = column.bubbleTemperature(xm, P, t0), t1 = column.bubbleTemperature(x1, P, tm);
                double n0 = 100 * (1 - a), n1 = n0 - 1;
                double[] y = column.bubbleVapour(xm, P);
                double q = n1 * thermo.fugacity(t1, P, x1, liquid, workspace).molarEnthalpyJoulesPerMol()
                        - n0 * thermo.fugacity(t0, P, x0, liquid, workspace).molarEnthalpyJoulesPerMol()
                        + thermo.fugacity(tm, P, y, vapour, workspace).molarEnthalpyJoulesPerMol();
                double cp = thermo.fugacity(tm + .5, P, xm, liquid, workspace).molarEnthalpyJoulesPerMol()
                        - thermo.fugacity(tm - .5, P, xm, liquid, workspace).molarEnthalpyJoulesPerMol();
                double warming = (n0 - .5) * cp * (t1 - t0), boil = q - warming;
                text.append(String.format(Locale.ROOT, " | %2.0f%%: T %.0f K, dT %.2f K/mol, warming %.1f kJ, boil-up %.1f kJ/mol, ratio %.2f", 100 * a, t0,
                        t1 - t0, warming / 1000, boil / 1000, warming / boil));
            }
            text.append('\n');
        }
        return text.toString();
    }

    /** The pot's composition every 0.5 % distilled, 0..75 %, by simple distillation from {@code z}. */
    private static List<double[]> rayleighFine(BubblePointColumn column, double[] z) {
        double[] pot = z.clone();
        for (int i = 0; i < pot.length; i++) pot[i] *= 100;
        var out = new ArrayList<double[]>();
        out.add(BubblePointColumn.normalized(pot));
        for (int step = 0; step < 150; step++) {
            double[] y = column.bubbleVapour(BubblePointColumn.normalized(pot), P);
            for (int i = 0; i < pot.length; i++) pot[i] = Math.max(0, pot[i] - .5 * y[i]);
            out.add(BubblePointColumn.normalized(pot));
        }
        return out;
    }

    private static double maxDiff(double[] a, double[] b) {
        double out = 0;
        for (int i = 0; i < a.length; i++) out = Math.max(out, Math.abs(a[i] - b[i]));
        return out;
    }

    private static double[] distillate(V3PengRobinsonThermo thermo, V3ColumnOutcome.Success s) {
        var d = stream(s, "distillate_liquid");
        if (d == null) return null;
        var ids = thermo.componentBasis().componentIds();
        double[] x = new double[ids.size()];
        for (var fraction : d.moleFractions()) {
            int i = ids.indexOf(fraction.componentId());
            if (i >= 0) x[i] = fraction.moleFraction();
        }
        return x;
    }

    private static String top(V3PengRobinsonThermo thermo, double[] x, int count) {
        Integer[] order = new Integer[x.length];
        for (int i = 0; i < x.length; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(x[b], x[a]));
        var parts = new ArrayList<String>();
        for (int k = 0; k < count && k < order.length; k++)
            parts.add(String.format(Locale.ROOT, "%s %.3f", thermo.componentBasis().componentId(order[k]), x[order[k]]));
        return String.join(", ", parts);
    }

    private static V3ColumnStreamProperties stream(V3ColumnOutcome.Success s, String id) {
        return s.result().streams().stream().filter(x -> x.streamId().equals(id)).findFirst().orElse(null);
    }

    private static V3ColumnInput v3Input(V3PengRobinsonThermo thermo, int n, double reflux, double duty, double[] pot, double feedT, double condenserT) {
        double[] flows = new double[pot.length];
        for (int i = 0; i < pot.length; i++) flows[i] = pot[i] * FEED;
        return new V3ColumnInput(V3ColumnInput.SCHEMA_VERSION, PACKAGE, PACKAGE, thermo.componentBasis(), flows, feedT, n, n + 1, P, 0,
                List.of(new V3ColumnSpecification.CondenserOutletTemperature(condenserT), new V3ColumnSpecification.OrganicRefluxRatio(reflux),
                        new V3ColumnSpecification.ReboilerDuty(duty)), List.of(), List.of(), List.of(), V3ColumnInput.PRESCRIBED_DROP_DIAMETER);
    }

    /** The pot's composition at each of {@link #DISTILLED}, by simple (one-stage) distillation from {@code z}. */
    private static List<double[]> rayleigh(BubblePointColumn column, double[] z) {
        double[] pot = z.clone();
        for (int i = 0; i < pot.length; i++) pot[i] *= 100;
        var out = new ArrayList<double[]>();
        double distilled = 0;
        int next = 0;
        while (next < DISTILLED.length) {
            if (distilled >= 100 * DISTILLED[next] - 1e-9) {
                out.add(BubblePointColumn.normalized(pot));
                next++;
                continue;
            }
            double[] y = column.bubbleVapour(BubblePointColumn.normalized(pot), P);
            if (y == null) throw new IllegalStateException("no bubble point on the Rayleigh path at " + distilled);
            for (int i = 0; i < pot.length; i++) pot[i] = Math.max(0, pot[i] - .5 * y[i]);
            distilled += .5;
        }
        return out;
    }

    private static List<Charge> charges(V3PengRobinsonThermo thermo) {
        var crude = thermo.crudeFeed("icheme:light_crude");
        var stabilised = new LinkedHashMap<String, Double>();
        for (int i = 0; i < crude.componentBasis().componentCount(); i++) {
            String id = crude.componentBasis().componentId(i);
            if (List.of("Methane", "Ethane", "Propane", "Isobutane", "N-butane", "Nitrogen").contains(id)) continue;
            stabilised.merge(id, crude.moleFractions()[i], Double::sum);
        }
        return List.of(new Charge("crude", charge(thermo, stabilised)),
                new Charge("naphtha", charge(thermo, NAPHTHA)),
                new Charge("pentanes", charge(thermo, Map.of("Isopentane", .5, "N-pentane", .5))));
    }

    private static double[] charge(V3PengRobinsonThermo thermo, Map<String, Double> fractions) {
        var ids = thermo.componentBasis().componentIds();
        double[] z = new double[ids.size()];
        for (var e : fractions.entrySet()) {
            int i = ids.indexOf(e.getKey());
            if (i < 0) throw new IllegalArgumentException("not in " + PACKAGE + ": " + e.getKey());
            z[i] += e.getValue();
        }
        return BubblePointColumn.normalized(z);
    }
}
