package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.science.material.TiaJuanaTestCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

/**
 * E30 landing probe (batch 2026-11-04-open-thermo-data): O10GridProbe's grids (medium and Tia Juana, 622 to 644 K, classical
 * start) and O3's light, heavy and dilbit presets, printing each result to 4 decimals (distillate, bottoms, condenser duty) so
 * a root that moves is seen. Needs O3FeedTemperatureProbe beside it. Prints "fix grid" and "fix preset" lines; the label is
 * read from tmp/o10-fix/label.txt.
 */
class O10FixGridProbe {
    static final String ROOT = "D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/cleanup-stale-branches-worktrees-9bc4aa/tmp/";
    static final String LABEL = label();
    static String label() { try { return Files.readString(Path.of(ROOT + "o10-fix/label.txt")).trim(); } catch (Exception e) { return "?"; } }

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
        }
        return r.append(')').toString();
    }

    @Test void grids() {
        double[] temps = {622, 626, 630, 631.28, 633, 634, 636, 638.15, 640, 644};
        for (String grade : new String[] {"med", "tjl"}) {
            boolean tjl = grade.equals("tjl");
            var c = tjl ? TiaJuanaTestCatalog.catalog() : MaterialCatalog.bundled();
            var base = c.presets().column(tjl ? "tia_juana" : "medium_crude").input(c);
            var sb = new StringBuilder("fix grid " + LABEL + " " + grade + ":");
            long[] total = {0};
            int ok = 0;
            for (double t : temps) {
                String r = run(c, O3FeedTemperatureProbe.at(base, t, 1.0, 1.0, base.feedStageNumber()), total);
                if (r.startsWith("ok")) ok++;
                sb.append("\n   ").append(t).append('=').append(r);
            }
            System.out.println(sb + "\n   | pass " + ok + "/10 polls " + total[0] / 1000 + "k");
        }
    }

    @Test void presets() throws Exception {
        var resources = new LinkedHashMap<>(MaterialCatalog.bundled().resources());
        for (String id : new String[] {"light_crude", "heavy_crude", "oil_sand_dilbit"})
            resources.put("data/icheme/materials/presets/column_" + id + ".json", Files.readString(Path.of(ROOT + "open-thermo-o3/presets/column_" + id + ".json")));
        var c = MaterialCatalog.parse(resources);
        for (String id : new String[] {"light_crude", "heavy_crude", "oil_sand_dilbit"}) {
            long[] total = {0};
            System.out.println("fix preset " + LABEL + " " + id + " " + run(c, c.presets().column(id).input(c), total));
        }
    }
}
