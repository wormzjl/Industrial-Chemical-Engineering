package com.wormzjl.icheme.science.column.v3.thermo;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The wet grid and the dry two-liquid scan of the lane fixes (batch 2026-11-16-supercritical-followups,
 * LANE_FIXES_REVIEW.md): the frozen flash (V3FeedFlashReference), the 0.34.0 flash (V3FeedFlashD1, the collapse rule) and
 * the flash of the lane (V3FeedFlashP3, P3 form D at cb9970087) and the production flash on each state. Wet: ethanol/water, ethanol/water/n-hexane on {@code icheme:refinery}, the Tia
 * Juana crude with water; temperatures around their bubble points, 50 to 300 kPa. Dry: the two-liquid states of P3
 * (CO2/pc03 at 200 to 240 K, methane/pc03 at 110 to 130 K). Counts only.
 *
 * <p>Args: {@code wet <output file>}.</p>
 */
public final class P3WetProbe {
    static final double[] PRESSURES_KPA = {50, 75, 100, 150, 200, 300};

    record Mixture(String name, MaterialCatalog catalog, String packageId, Map<String, Double> parts, double[] temperatures,
                   double[] pressuresPa, boolean wet) {}

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[1]);
        MaterialCatalog bundled = MaterialCatalog.bundled();
        String refinery = "icheme:refinery";
        List<Mixture> mixtures = new ArrayList<>();
        double[] wetP = new double[PRESSURES_KPA.length];
        for (int i = 0; i < wetP.length; i++) wetP[i] = PRESSURES_KPA[i] * 1e3;
        mixtures.add(new Mixture("EtOH/W 10/90", bundled, refinery, Map.of("Ethanol", 0.1, "Water", 0.9), range(330, 400, 5), wetP, true));
        mixtures.add(new Mixture("EtOH/W 40/60", bundled, refinery, Map.of("Ethanol", 0.4, "Water", 0.6), range(330, 400, 5), wetP, true));
        mixtures.add(new Mixture("EtOH/W 80/20", bundled, refinery, Map.of("Ethanol", 0.8, "Water", 0.2), range(330, 400, 5), wetP, true));
        mixtures.add(new Mixture("EtOH/W/C6 40/50/10", bundled, refinery, Map.of("Ethanol", 0.4, "Water", 0.5, "N-hexane", 0.1),
                range(310, 400, 5), wetP, true));
        // The Tia Juana crude of the column preset with a tenth of water.
        MaterialCatalog tiaJuana = com.wormzjl.icheme.science.material.TiaJuanaTestCatalog.catalog();
        var base = com.wormzjl.icheme.science.column.v3.V3ColumnPresets.input(tiaJuana.presets().column("tia_juana"), tiaJuana);
        Map<String, Double> crude = new LinkedHashMap<>();
        double total = 0;
        for (double f : base.feedComponentMolarFlowsMolPerSecond()) total += f;
        for (int i = 0; i < base.feedComponentMolarFlowsMolPerSecond().length; i++) {
            double f = base.feedComponentMolarFlowsMolPerSecond()[i];
            if (f > 0) crude.put(base.componentBasis().componentId(i), 0.9 * f / total);
        }
        crude.merge("Water", 0.1, Double::sum);
        mixtures.add(new Mixture("crude/W 90/10 (" + base.packageId() + ")", tiaJuana, base.packageId(), crude, range(300, 600, 10), wetP, true));
        // Dry: P3's two-liquid states and their neighbourhood.
        MaterialCatalog co2 = withCarbonDioxide();
        double[] co2P = range(0.4e6, 1.0e6, 0.1e6), c1P = range(0.15e6, 0.35e6, 0.025e6);
        mixtures.add(new Mixture("CO2/pc03 90/10", co2, P3FormsProbe.CO2, Map.of("CarbonDioxide", 0.9, "crude_pc03", 0.1), range(200, 240, 5), co2P, false));
        mixtures.add(new Mixture("CO2/pc03 70/30", co2, P3FormsProbe.CO2, Map.of("CarbonDioxide", 0.7, "crude_pc03", 0.3), range(200, 240, 5), co2P, false));
        mixtures.add(new Mixture("C1/pc03 90/10", co2, P3FormsProbe.NETWORK, Map.of("Methane", 0.9, "crude_pc03", 0.1), range(110, 130, 2.5), c1P, false));

        StringBuilder states = new StringBuilder("# mixture\tT\tP_kPa\tfrozen\tD1\tlane\tnew\tdH_new_minus_frozen\tdetail_new\n");
        StringBuilder summary = new StringBuilder();
        Map<String, Integer> all = new TreeMap<>();
        for (Mixture m : mixtures) {
            Map<String, Integer> counts = new TreeMap<>();
            StringBuilder range = new StringBuilder();
            // As the column: wet feeds on the island view with water (ColumnSolve.run), dry ones on their package.
            MaterialCatalog catalog = m.wet() ? com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics.forNetwork(m.catalog(), m.packageId())
                    .on(m.parts().keySet().stream().filter(id -> !id.equals("Water")).toList(), true).catalog() : m.catalog();
            MaterialRuntime.with(catalog, m.packageId(), () -> {
                run(m, counts, states, range);
                return null;
            });
            summary.append("## ").append(m.name()).append(m.wet() ? " (wet)" : " (dry)").append('\n');
            counts.forEach((k, v) -> summary.append(k).append('\t').append(v).append('\n'));
            summary.append(range).append('\n');
            counts.forEach((k, v) -> all.merge((m.wet() ? "wet " : "dry ") + k, v, Integer::sum));
        }
        summary.append("## all\n");
        all.forEach((k, v) -> summary.append(k).append('\t').append(v).append('\n'));
        Files.createDirectories(file.getParent());
        Files.writeString(file, summary + "\n# states\n" + states);
        System.out.println(summary);
        System.out.println("PROBE wrote " + file);
    }

    static double[] range(double from, double to, double step) {
        int n = (int) Math.round((to - from) / step) + 1;
        double[] values = new double[n];
        for (int i = 0; i < n; i++) values[i] = from + i * step;
        return values;
    }

    static MaterialCatalog withCarbonDioxide() {
        var resources = new LinkedHashMap<>(MaterialCatalog.bundled().resources());
        resources.put("data/icheme/materials/packages/test_methane_co2.json",
                "{\"schema_version\":1,\"id\":\"" + P3FormsProbe.CO2 + "\",\"revision\":\"probe-r1\",\"method\":\"icheme:crude_pr78\","
                        + "\"extends\":\"" + P3FormsProbe.NETWORK + "\",\"components\":[{\"id\":\"CarbonDioxide\",\"properties\":\"icheme:chemsep_carbon_dioxide\"}]}");
        return MaterialCatalog.parse(resources);
    }

    interface Flash {
        V3FlashResult resolve(V3PengRobinsonThermo model, double t, double p, V3ThermoWorkspace w);
    }

    static P3FormsProbe.Outcome outcome(Flash flash, V3PengRobinsonThermo thermo, double t, double p, double[] z) {
        P3FormsProbe.Outcome o = new P3FormsProbe.Outcome();
        V3ThermoWorkspace w = thermo.newWorkspace();
        System.arraycopy(z, 0, w.normalizedOverall, 0, z.length);
        try { o.result = flash.resolve(thermo, t, p, w); }
        catch (V3ThermoException refusal) { o.refusal = refusal.getMessage(); }
        return o;
    }

    private static void run(Mixture m, Map<String, Integer> counts, StringBuilder states, StringBuilder rangeOut) {
        V3PengRobinsonThermo thermo = V3PengRobinsonThermo.fromRegisteredPackage(m.packageId());
        var ids = thermo.componentBasis().componentIds();
        double[] z = new double[ids.size()];
        double sum = 0;
        for (var e : m.parts().entrySet()) {
            int i = ids.indexOf(e.getKey());
            if (i < 0) throw new IllegalStateException(e.getKey() + " not in " + m.packageId());
            z[i] = e.getValue();
            sum += e.getValue();
        }
        for (int i = 0; i < z.length; i++) z[i] /= sum;
        double[] split = {Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY};
        for (double t : m.temperatures()) for (double p : m.pressuresPa()) {
            var frozen = outcome(V3FeedFlashEquivalenceTest.V3FeedFlashReference::resolve, thermo, t, p, z);
            var d1 = outcome(V3FeedFlashD1::resolve, thermo, t, p, z);
            var lane = outcome(V3FeedFlashP3::resolve, thermo, t, p, z);
            var now = outcome(V3FeedFlash::resolve, thermo, t, p, z);
            counts.merge("lane_" + lane.cls() + "|new_" + now.cls(), 1, Integer::sum);
            if (lane.result != null && (now.result == null || !P3FormsProbe.same(lane.result, now.result))) {
                counts.merge("moved_vs_lane " + lane.cls() + "->" + now.cls(), 1, Integer::sum);
            }
            counts.merge("states", 1, Integer::sum);
            counts.merge("frozen_" + frozen.cls() + "|new_" + now.cls(), 1, Integer::sum);
            counts.merge("D1_" + d1.cls() + "|new_" + now.cls(), 1, Integer::sum);
            if (frozen.result != null && now.result == null) counts.merge("GUARANTEE answered_by_frozen_refused_now", 1, Integer::sum);
            if (frozen.cls().equals("two") && now.cls().equals("one")) counts.merge("GUARANTEE frozen_split_one_phase_now", 1, Integer::sum);
            if (d1.result != null && now.result == null) counts.merge("answered_by_D1_refused_now", 1, Integer::sum);
            if (frozen.result != null && now.result != null && !P3FormsProbe.same(frozen.result, now.result)) {
                counts.merge("moved_vs_frozen " + frozen.cls() + "->" + now.cls(), 1, Integer::sum);
            }
            if (d1.result != null && now.result != null && !P3FormsProbe.same(d1.result, now.result)) {
                counts.merge("moved_vs_D1 " + d1.cls() + "->" + now.cls(), 1, Integer::sum);
            }
            if (now.result != null && now.result.detail().endsWith(V3FeedFlash.SECOND_LIQUID)) counts.merge("new_second_liquid_one_phase", 1, Integer::sum);
            if (now.result != null && now.result.detail().equals(V3FeedFlash.ENGINE_SPLIT)) counts.merge("new_engine_split", 1, Integer::sum);
            double dH = frozen.result != null && now.result != null
                    ? now.result.molarEnthalpyJoulesPerMol() - frozen.result.molarEnthalpyJoulesPerMol() : Double.NaN;
            if (frozen.cls().equals("one") && now.cls().equals("two")) {
                split[0] = Math.min(split[0], now.result.vaporFraction());
                split[1] = Math.max(split[1], now.result.vaporFraction());
                split[2] = Math.min(split[2], dH);
                split[3] = Math.max(split[3], dH);
            }
            boolean interesting = now.result == null || !lane.cls().equals(now.cls()) || !frozen.cls().equals(now.cls()) || !d1.cls().equals(now.cls())
                    || now.result != null && now.result.detail().endsWith(V3FeedFlash.SECOND_LIQUID)
                    || frozen.result != null && now.result != null && !P3FormsProbe.same(frozen.result, now.result);
            if (interesting) {
                states.append(String.format(Locale.ROOT, "%s\t%.1f\t%.1f\t%s\t%s\t%s\t%s\t%.3f\t%s%n", m.name(), t, p / 1e3, label(frozen), label(d1), label(lane),
                        label(now), dH, now.result != null ? now.result.detail() : now.refusal));
            }
        }
        if (split[0] <= split[1]) {
            rangeOut.append(String.format(Locale.ROOT, "one phase by the frozen flash, split now: vapour fraction %.6f to %.6f, H(new) - H(frozen) %.1f to %.1f J/mol%n",
                    split[0], split[1], split[2], split[3]));
        }
    }

    static String label(P3FormsProbe.Outcome o) {
        if (o.result == null) return "refused";
        return o.result.phase() == V3FeedPhase.TWO_PHASE ? String.format(Locale.ROOT, "two(%.6f)", o.result.vaporFraction())
                : o.result.phase().name().toLowerCase(Locale.ROOT);
    }
}
