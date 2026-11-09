package com.wormzjl.icheme.science.column.v3;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.science.material.TiaJuanaTestCatalog;
import org.junit.jupiter.api.Test;

/**
 * O3 probe (batch 2026-11-04-open-thermo-data): the classical start over a feed-temperature grid, 622 to 644 K, for the Tia Juana
 * column (control) and the medium-grade default with its draws, steam and feed tray varied. Copy to
 * src/test/java/com/wormzjl/icheme/science/column/v3/, run ./gradlew columnGates --offline --tests "*O3FeedTemperatureProbe" -i,
 * read the "o3 grid" lines, move it back.
 */
class O3FeedTemperatureProbe {
    static V3ColumnInput at(V3ColumnInput i, double t, double draws, double steam, int feedStage) {
        return new V3ColumnInput(i.schemaVersion(), i.packageId(), i.assayId(), i.componentBasis(), i.feedComponentMolarFlowsMolPerSecond(), t,
                i.stageCount(), feedStage, i.topPressurePascal(), i.stagePressureDropPascal(), i.specifications(),
                i.sideDraws().stream().map(d -> new V3SideDrawSpec(d.trayNumber(), d.molarFlowMolPerSecond() * draws)).toList(),
                i.steamFeeds().stream().map(s -> new V3SteamFeedSpec(s.stageNumber(), s.molarFlowMolPerSecond() * steam, s.temperatureKelvin())).toList(),
                i.pumparounds(), i.columnDiameterMetres());
    }
    static String run(MaterialCatalog c, String preset, double t, double draws, double steam, int dFeed) {
        var base = c.presets().column(preset).input(c);
        var input = at(base, t, draws, steam, base.feedStageNumber() + dFeed);
        var polls = new java.util.concurrent.atomic.AtomicLong();
        var o = MaterialRuntime.with(c, input.packageId(), () -> V3ColumnCalculator.calculate(input, polls::incrementAndGet, 0, 0,
                V3InitializationOptions.CURRENT, V3NeuralInitializer.UNAVAILABLE, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO));
        return (o instanceof V3ColumnOutcome.Success ? "ok" : "FAIL") + "(" + polls.get() / 1000 + "k)";
    }
    @Test void feedTemperatures() {
        double[] temps = {622, 626, 630, 631.28, 633, 634, 636, 638.15, 640, 644};
        Object[][] variants = {{"tjl", 1.0, 1.0, 0}, {"med", 1.0, 1.0, 0}, {"med", 0.8, 1.0, 0}, {"med", 1.0, 1.0, -1}, {"med", 1.0, 1.0, 1}, {"med", 1.0, 1.5, 0}};
        for (var v : variants) {
            var sb = new StringBuilder("o3 grid " + v[0] + " draws=" + v[1] + " steam=" + v[2] + " dFeed=" + v[3] + ":");
            for (double t : temps) {
                boolean tjl = v[0].equals("tjl");
                var c = tjl ? TiaJuanaTestCatalog.catalog() : MaterialCatalog.bundled();
                sb.append(' ').append(t).append('=').append(run(c, tjl ? "tia_juana" : "medium_crude", t, (double) v[1], (double) v[2], (int) v[3]));
            }
            System.out.println(sb);
        }
    }
}
