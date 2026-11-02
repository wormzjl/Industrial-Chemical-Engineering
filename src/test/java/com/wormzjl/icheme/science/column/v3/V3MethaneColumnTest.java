package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.GsonBuilder;
import com.wormzjl.icheme.network.ColumnCodecs;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class V3MethaneColumnTest {
    @Test void freshPresetAndSavedInputRetainTheMethaneAxis() throws Exception {
        var input = V3TestInputs.methaneCduInput();
        assertEquals(input, ColumnCodecs.wireRoundTrip(input));
        var original = V3TestInputs.literatureCduInput();
        assertEquals(original, ColumnCodecs.wireRoundTrip(original), "a 19-component feed keeps its axis");
        assertEquals(original.specifications(), input.specifications());
        assertEquals(V3TestInputs.packagePreset(com.wormzjl.icheme.science.material.MaterialCatalog.bundled(),input.packageId()).draws().stream().map(V3SideDrawSpec::molarFlowMolPerSecond).toList(),input.sideDraws().stream().map(V3SideDrawSpec::molarFlowMolPerSecond).toList());
        assertEquals(original.steamFeeds(), input.steamFeeds());
        assertEquals(original.pumparounds(), input.pumparounds());
    }

    @Test void methaneFeedConvergesWithCurrentInitializer() throws Exception {
        var input = V3TestInputs.methaneCduInput();
        assertEquals("Methane", input.componentBasis().componentId(0));
        assertEquals(com.wormzjl.icheme.science.material.MaterialTestBasis.CRUDE, input.componentBasis().componentCount());
        assertEquals(java.util.Arrays.stream(input.feedComponentMolarFlowsMolPerSecond()).sum() * 0.005, input.feedComponentMolarFlowsMolPerSecond()[0], 1e-12);
        long start = System.nanoTime();
        var outcome = V3ColumnCalculator.calculate(input, () -> {
            if (System.nanoTime() - start > 60_000_000_000L) throw new java.util.concurrent.CancellationException("methane qualification deadline");
        }, 0, 0, V3InitializationOptions.CURRENT, new V3NeuralInitializer() {
            @Override public String modelId() { throw new AssertionError("CURRENT_ONLY must not access the model"); }
            @Override public java.util.Optional<V3NeuralSeed> predict(V3ColumnInput ignored, V3SolveControl control) {
                throw new AssertionError("CURRENT_ONLY must not infer");
            }
        }, V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO);
        var directory = Path.of("build", "methane-qualification");
        Files.createDirectories(directory);
        var json = new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create();
        Files.writeString(directory.resolve("input.json"), json.toJson(input));
        Files.writeString(directory.resolve("outcome.json"), json.toJson(outcome));
        double elapsedMillis = (System.nanoTime() - start) / 1e6;
        Files.writeString(directory.resolve("timing.json"), json.toJson(java.util.Map.of("elapsedMilliseconds", elapsedMillis)));
        System.out.println("Methane CURRENT_ONLY elapsed ms=" + elapsedMillis);
        var success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        assertTrue(success.result().acceptanceAudit().accepted());
        assertTrue(success.result().convergenceEvidence().satisfiesGates());
        double methaneOut = success.result().streams().stream()
                .mapToDouble(stream -> stream.molarFlowMolPerSecond() * stream.moleFractions().stream()
                        .filter(fraction -> fraction.componentId().equals("Methane"))
                        .mapToDouble(V3ColumnStreamProperties.ComponentFraction::moleFraction).sum()).sum();
        assertEquals(input.feedComponentMolarFlowsMolPerSecond()[0], methaneOut, 1e-7);
        var overhead = success.result().streams().stream().filter(stream -> stream.streamId().equals("overhead_vapor"))
                .findFirst().orElseThrow();
        assertTrue(overhead.moleFractions().stream().anyMatch(fraction -> fraction.componentId().equals("Methane")
                && fraction.moleFraction() > 0.01));
    }

    @Test void defaultNeuralFirstModeSolvesTheMethaneColumnThroughTheBundledModel() {
        var input = V3TestInputs.methaneCduInput();
        long start = System.nanoTime();
        var outcome = V3ColumnCalculator.calculate(input, () -> {
            if (System.nanoTime() - start > 60_000_000_000L) throw new java.util.concurrent.CancellationException("methane fallback deadline");
        }, 0, 0, V3TestInputs.LEARNED, V3NeuralRegistry.bundled(), V3ColumnCalculator.DEFAULT_LIQUID_SUPPLY_SCREEN_RATIO);
        var success = assertInstanceOf(V3ColumnOutcome.Success.class, outcome, outcome::toString);
        assertTrue(success.result().convergenceEvidence().satisfiesGates());
        // One model ships, so the initialization event names it whichever route won. Which route wins on
        // this preset is a coverage question the 405-input campaign answers, not an invariant of this test.
        String event = success.diagnostics().events().getFirst();
        assertTrue(event.contains("model=" + V3NeuralRegistry.bundled().bind(com.wormzjl.icheme.science.material.MaterialRuntime.current(), input).modelId()), event);
    }
}
