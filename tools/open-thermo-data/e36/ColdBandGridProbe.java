package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.science.material.TiaJuanaTestCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

/**
 * Scratch (tmp/v3-cold-band, not for the tree): E30's grids (medium and Tia Juana, 622 to 644 K, classical start) and O3's
 * light, heavy and dilbit presets, each with the forms named in the system property icheme.forms (HEAD or a form letter; the scratch solver reads icheme.form<letter>).
 * Prints each result to 4 decimals (distillate, bottoms, condenser duty) and the polls, so a root that moves is seen.
 */
class ColdBandGridProbe {
    static final String TMP = "D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/cleanup-stale-branches-worktrees-9bc4aa/tmp/";

    static V3ColumnInput at(V3ColumnInput i, double t) {
        return new V3ColumnInput(i.schemaVersion(), i.packageId(), i.assayId(), i.componentBasis(), i.feedComponentMolarFlowsMolPerSecond(), t,
                i.stageCount(), i.feedStageNumber(), i.topPressurePascal(), i.stagePressureDropPascal(), i.specifications(),
                i.sideDraws(), i.steamFeeds(), i.pumparounds(), i.columnDiameterMetres());
    }

    static String run(MaterialCatalog c, V3ColumnInput input, long[] total) {
        var polls = new java.util.concurrent.atomic.AtomicLong();
        var o = MaterialRuntime.with(c, input.packageId(), () -> V3ColumnCalculator.calculate(input, polls::incrementAndGet, 0, 0,
                V3InitializationOptions.CURRENT, V3NeuralInitializer.UNAVAILABLE, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO));
        total[0] += polls.get();
        StringBuilder r = new StringBuilder(o instanceof V3ColumnOutcome.Success ? "ok" : "FAIL").append('(').append(polls.get() / 1000).append('k');
        if (o instanceof V3ColumnOutcome.Success s) {
            for (var st : s.result().streams()) if (st.streamId().startsWith("distillate") || st.streamId().startsWith("bottoms") || st.streamId().startsWith("free_water"))
                r.append(String.format("/%s=%.4f", st.streamId().substring(0, 3), st.massFlowKgPerSecond()));
            r.append(String.format("/Qc=%.4f", s.result().dutyLedger().orElseThrow().condenserWatts() / 1e6));
        } else r.append('/').append(o.diagnostics().solvePath());
        return r.append(')').toString();
    }

    @Test void gridsAndPresets() throws Exception {
        double[] temps = {622, 626, 630, 631.28, 633, 634, 636, 638.15, 640, 644};
        var resources = new LinkedHashMap<>(MaterialCatalog.bundled().resources());
        for (String id : new String[] {"light_crude", "heavy_crude", "oil_sand_dilbit"})
            resources.put("data/icheme/materials/presets/column_" + id + ".json", Files.readString(Path.of(TMP + "open-thermo-o3/presets/column_" + id + ".json")));
        var withPresets = MaterialCatalog.parse(resources);
        for (String label : System.getProperty("icheme.forms", "FINAL").split(",")) {
            for (String letter : new String[] {"a", "g", "g2", "k"}) System.clearProperty("icheme.form" + letter);
            if (!label.equals("HEAD")) System.setProperty("icheme.form" + label.toLowerCase(), "true");
            for (String grade : new String[] {"med", "tjl"}) {
                boolean tjl = grade.equals("tjl");
                var c = tjl ? TiaJuanaTestCatalog.catalog() : MaterialCatalog.bundled();
                var base = c.presets().column(tjl ? "tia_juana" : "medium_crude").input(c);
                var sb = new StringBuilder("grid " + label + " " + grade + ":");
                long[] total = {0};
                int ok = 0;
                for (double t : temps) {
                    String r = run(c, at(base, t), total);
                    if (r.startsWith("ok")) ok++;
                    sb.append("\n   ").append(t).append('=').append(r);
                }
                System.out.println(sb + "\n   | pass " + ok + "/10 polls " + total[0] / 1000 + "k");
            }
            for (String id : new String[] {"light_crude", "heavy_crude", "oil_sand_dilbit"}) {
                long[] total = {0};
                System.out.println("preset " + label + " " + id + " " + run(withPresets, withPresets.presets().column(id).input(withPresets), total));
            }
        }
        for (String letter : new String[] {"a", "g", "g2", "k"}) System.clearProperty("icheme.form" + letter);
    }
}
