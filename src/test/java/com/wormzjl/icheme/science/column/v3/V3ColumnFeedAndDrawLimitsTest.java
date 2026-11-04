package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The column's admission limits that the bundled model does not cover, solved on the default route (the model declines them,
 * so the classical seed solves them): nitrogen in the feed, and a fourth side draw.
 */
@org.junit.jupiter.api.extension.ExtendWith(com.wormzjl.icheme.science.material.TiaJuanaExtension.class)
class V3ColumnFeedAndDrawLimitsTest {
    /**
     * The {@code tia_juana_nitrogen} preset is the Tia Juana column on the fluid network's package ({@code tjl20_methane_nitrogen}).
     * Nitrogen (Tc 126 K) cannot condense at the 59 C condenser: it leaves overhead, apart from what dissolves in the liquid
     * distillate (Henry's law, about 1e-3 mole fraction per bar of its partial pressure: about 1 % of it).
     */
    @Test void aTwoPercentNitrogenFeedSolvesAndLeavesOverhead() {
        var catalog = com.wormzjl.icheme.science.material.MaterialRuntime.current();
        var preset = catalog.presets().column("tia_juana_nitrogen").input(catalog);
        assertEquals("icheme:tjl20_methane_nitrogen", preset.packageId());
        int nitrogen = preset.componentBasis().componentIds().indexOf("Nitrogen");
        assertTrue(nitrogen >= 0, "the nitrogen package's axis carries Nitrogen");
        double[] feed = preset.feedComponentMolarFlowsMolPerSecond().clone();
        double total = Arrays.stream(feed).sum();
        for (int i = 0; i < feed.length; i++) feed[i] *= 0.98;
        feed[nitrogen] = 0.02 * total;
        var success = solve(with(preset, feed, preset.sideDraws()));
        assertEquals(feed[nitrogen], nitrogenIn(success, s -> true), feed[nitrogen] * 1e-7, "nitrogen is conserved");
        double overhead = nitrogenIn(success, s -> s.streamId().equals("overhead_vapor"));
        assertTrue(overhead > 0.95 * feed[nitrogen], "nitrogen leaves with the overhead vapor: " + overhead + " of " + feed[nitrogen]);
    }

    /** The default column (draws on trays 10, 18, 28) with a fourth 10 kmol/h draw on tray 29: eight product streams. */
    @Test void aFourthSideDrawSolvesAndIsAProductStream() {
        var preset = V3TestInputs.methaneCduInput();
        assertEquals(3, preset.sideDraws().size());
        var draws = new ArrayList<>(preset.sideDraws());
        draws.add(new V3SideDrawSpec(29, 10 / 3.6));
        assertEquals(V3ColumnInput.MAX_SIDE_DRAWS, draws.size());
        var success = solve(with(preset, preset.feedComponentMolarFlowsMolPerSecond(), draws));
        var side = success.result().streams().stream().filter(s -> s.streamId().equals("side_liquid_tray_29")).findFirst().orElseThrow();
        assertEquals(10 / 3.6, side.molarFlowMolPerSecond(), 1e-6);
        assertTrue(success.result().streams().size() <= V3ColumnStreamProperties.MAX_STREAMS);
    }

    private static V3ColumnInput with(V3ColumnInput preset, double[] feed, List<V3SideDrawSpec> draws) {
        return new V3ColumnInput(preset.schemaVersion(), preset.packageId(), preset.assayId(), preset.componentBasis(), feed,
                preset.feedTemperatureKelvin(), preset.stageCount(), preset.feedStageNumber(), preset.topPressurePascal(),
                preset.stagePressureDropPascal(), preset.specifications(), draws, preset.steamFeeds(), preset.pumparounds(),
                preset.columnDiameterMetres());
    }

    private static V3ColumnOutcome.Success solve(V3ColumnInput input) {
        assertTrue(V3NeuralRegistry.bundled().predict(input, V3SolveControl.UNBOUNDED).isEmpty(), "the model declines this input");
        long start = System.nanoTime();
        var outcome = V3ColumnCalculator.calculate(input, () -> {
            if (System.nanoTime() - start > 120_000_000_000L) throw new java.util.concurrent.CancellationException("solve deadline");
        }, 0, 0, V3TestInputs.LEARNED, V3NeuralRegistry.bundled(), V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO);
        var success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        assertTrue(success.result().convergenceEvidence().satisfiesGates());
        return success;
    }

    private static double nitrogenIn(V3ColumnOutcome.Success success, java.util.function.Predicate<V3ColumnStreamProperties> streams) {
        return success.result().streams().stream().filter(streams)
                .mapToDouble(s -> s.molarFlowMolPerSecond() * s.moleFractions().stream().filter(f -> f.componentId().equals("Nitrogen"))
                        .mapToDouble(V3ColumnStreamProperties.ComponentFraction::moleFraction).sum()).sum();
    }
}
