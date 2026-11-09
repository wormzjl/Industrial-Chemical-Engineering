package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.science.material.TiaJuanaTestCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;

/**
 * O10 form measurement (batch 2026-11-04-open-thermo-data): the medium and Tia Juana feed-temperature grids (622 to 644 K, 10 points)
 * and the light, heavy and dilbit draft presets of O3 (tmp/open-thermo-o3/presets), classical start. The form under test is read by
 * the scratch instrumentation from tmp/open-thermo-o10/form.txt. Prints "o10 grid" and "o10 preset" lines.
 */
class O10GridProbe {
    static final String ROOT = "D:/Minecraft/Modding/1.21/CreateChemE/.claude/worktrees/cleanup-stale-branches-worktrees-9bc4aa/tmp/";

    static String run(MaterialCatalog c, V3ColumnInput input, long[] total) {
        var polls = new java.util.concurrent.atomic.AtomicLong();
        var o = MaterialRuntime.with(c, input.packageId(), () -> V3ColumnCalculator.calculate(input, polls::incrementAndGet, 0, 0,
                V3InitializationOptions.CURRENT, V3NeuralInitializer.UNAVAILABLE, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO));
        total[0] += polls.get();
        String products = "";
        if (o instanceof V3ColumnOutcome.Success s) {
            for (var st : s.result().streams()) if (st.streamId().startsWith("distillate") || st.streamId().startsWith("bottoms"))
                products += String.format("/%s=%.2f", st.streamId().substring(0, 3), st.massFlowKgPerSecond());
        }
        return (o instanceof V3ColumnOutcome.Success ? "ok" : "FAIL") + "(" + polls.get() / 1000 + "k" + products + ")";
    }

    @Test void grids() {
        double[] temps = {622, 626, 630, 631.28, 633, 634, 636, 638.15, 640, 644};
        String form = "?";
        try { form = Files.readString(Path.of(ROOT + "open-thermo-o10/form.txt")).trim(); } catch (Exception ignored) {}
        for (String grade : new String[] {"med", "tjl"}) {
            boolean tjl = grade.equals("tjl");
            var c = tjl ? TiaJuanaTestCatalog.catalog() : MaterialCatalog.bundled();
            var base = c.presets().column(tjl ? "tia_juana" : "medium_crude").input(c);
            var sb = new StringBuilder("o10 grid form=" + form + " " + grade + ":");
            long[] total = {0};
            int ok = 0;
            for (double t : temps) {
                String r = run(c, O3FeedTemperatureProbe.at(base, t, 1.0, 1.0, base.feedStageNumber()), total);
                if (r.startsWith("ok")) ok++;
                sb.append(' ').append(t).append('=').append(r);
            }
            System.out.println(sb + " | pass " + ok + "/10 polls " + total[0] / 1000 + "k");
        }
    }

    @Test void presets() throws Exception {
        String form = "?";
        try { form = Files.readString(Path.of(ROOT + "open-thermo-o10/form.txt")).trim(); } catch (Exception ignored) {}
        var resources = new LinkedHashMap<>(MaterialCatalog.bundled().resources());
        for (String id : new String[] {"light_crude", "heavy_crude", "oil_sand_dilbit"})
            resources.put("data/icheme/materials/presets/column_" + id + ".json", Files.readString(Path.of(ROOT + "open-thermo-o3/presets/column_" + id + ".json")));
        var c = MaterialCatalog.parse(resources);
        for (String id : new String[] {"light_crude", "heavy_crude", "oil_sand_dilbit"}) {
            long[] total = {0};
            System.out.println("o10 preset form=" + form + " " + id + " " + run(c, c.presets().column(id).input(c), total));
        }
    }
}
