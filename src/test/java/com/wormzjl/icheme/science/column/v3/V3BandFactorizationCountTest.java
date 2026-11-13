package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.science.material.TiaJuanaTestCatalog;
import org.junit.jupiter.api.Test;

/**
 * The band factorizations of a solve by call site (P7 of batch 2026-11-13-codebase-review), on the inputs of
 * {@code tools/codebase-review-bench/ColumnBench.java}: medium_crude under its tower template and the literature preset, each
 * cold (the block's learned start) and warm (from the cold seed, every extensive input times 1.02). Printed for the report;
 * the counts decide how much reusing a factorization is worth.
 */
class V3BandFactorizationCountTest {
    private static final V3InitializationOptions START = new V3InitializationOptions(V3InitializationOptions.Mode.LNN_FIRST,
            V3InitializationOptions.WetStart.AUTO, 16, V3InitializationOptions.DEFAULT_BUDGET_POLLS, V3InitializationOptions.Correction.PROGRESS);

    @Test void eachSolvePublishesItsBandFactorizationsBySite() {
        MaterialCatalog bundled = MaterialCatalog.bundled();
        report("medium", V3TestInputs.crude(bundled, "medium_crude"), bundled);
        // As the bench: the literature preset on the Tia Juana fixture.
        report("literature", V3TestInputs.literatureCduInput(), TiaJuanaTestCatalog.catalog());
    }

    private static void report(String name, V3ColumnInput input, MaterialCatalog catalog) {
        V3ColumnOutcome.Success cold = assertInstanceOf(V3ColumnOutcome.Success.class, MaterialRuntime.with(catalog, input.packageId(),
                () -> V3ColumnCalculator.calculate(input, () -> {}, 0, 0, START, V3NeuralRegistry.bundled().bind(catalog, input),
                        V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO)));
        print(name + " cold", cold);
        V3NeuralSeed seed = cold.result().warmSeed().orElseThrow();
        V3ColumnInput target = scaled(input, 1.02);
        V3ColumnOutcome warm = MaterialRuntime.with(catalog, input.packageId(),
                () -> V3ColumnCalculator.calculateWithStrategy(target, () -> {}, V3SolveStrategy.AGGRESSIVE, seed,
                        V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO));
        print(name + " warm", assertInstanceOf(V3ColumnOutcome.Success.class, warm, warm::toString));
    }

    private static void print(String label, V3ColumnOutcome.Success success) {
        var events = success.diagnostics().events();
        String counts = events.stream().filter(e -> e.startsWith("band factorizations:")).findFirst()
                .orElseThrow(() -> new AssertionError(String.join(" | ", events)));
        String hydraulics = events.stream().filter(e -> e.startsWith("tray hydraulics: ")).findFirst().orElse("no tray hydraulics");
        int at = hydraulics.indexOf("correction band factorizations ");
        System.out.println("V3 " + label + " (" + success.diagnostics().solvePath() + "): " + counts
                + (at < 0 ? "; no hydraulic correction" : "; hydraulic correction " + hydraulics.substring(at + 31)));
        int total = Integer.parseInt(counts.substring(counts.lastIndexOf(' ') + 1));
        assertTrue(total > 0, counts);
    }

    /** Every extensive input times {@code alpha}, as the bench's warm case: feed, steam, draws, pumparound duties. */
    private static V3ColumnInput scaled(V3ColumnInput input, double alpha) {
        var steam = input.steamFeeds().stream().map(s -> new V3SteamFeedSpec(s.stageNumber(), s.molarFlowMolPerSecond() * alpha, s.temperatureKelvin())).toList();
        var draws = input.sideDraws().stream().map(d -> new V3SideDrawSpec(d.trayNumber(), d.molarFlowMolPerSecond() * alpha)).toList();
        var pumparounds = input.pumparounds().stream().map(p -> new V3PumparoundSpec(p.returnTray(), p.drawTray(), p.dutyWatts() * alpha, p.split())).toList();
        double[] feed = input.feedComponentMolarFlowsMolPerSecond();
        for (int i = 0; i < feed.length; i++) feed[i] *= alpha;
        return new V3ColumnInput(input.schemaVersion(), input.packageId(), input.assayId(), input.componentBasis(), feed,
                input.feedTemperatureKelvin(), input.stageCount(), input.feedStageNumber(), input.topPressurePascal(),
                input.stagePressureDropPascal(), input.specifications(), draws, steam, pumparounds, input.columnDiameterMetres());
    }
}
