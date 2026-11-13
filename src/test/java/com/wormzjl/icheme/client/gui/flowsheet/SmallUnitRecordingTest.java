package com.wormzjl.icheme.client.gui.flowsheet;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.tutorial.cards.SmallUnitData;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Chapter 4's recording (batch 2026-11-12-tutorial-system, P4b), a slow gate (holdGates: two cold runs of the small unit):
 * both runs are steady, what goes in comes out (the three products make the crude), the bundled run makes the shares
 * {@code SmallCrudeUnitTest} names, the lowered heater gives less heat and less of the light product, and a rerun of the
 * simulator gives the file to 1e-9.
 */
class SmallUnitRecordingTest {
    @Test
    void whatGoesInComesOutAndLessHeatGivesLessOfTheLightProduct() {
        var bundled = SmallUnitData.bundled();
        var lowered = SmallUnitData.lowered();
        for (var run : SmallUnitData.load()) {
            assertEquals(FlowsheetRun.Status.STEADY.name(), run.status());
            double sum = 0;
            for (var p : SmallUnitData.PRODUCTS) sum += run.productKgH().get(p);
            assertEquals(run.crudeKgH(), sum, 1e-3 * run.crudeKgH(), "in equals out");
        }
        assertEquals(663.15, bundled.heaterKelvin(), 1e-9, "the example's own heater outlet, 390 C");
        assertEquals(.18, bundled.share("overhead"), .01);
        assertEquals(.35, bundled.share("middle"), .01);
        assertEquals(.47, bundled.share("bottoms"), .01);
        assertTrue(lowered.heaterKelvin() < bundled.heaterKelvin());
        assertTrue(lowered.duty(SmallUnitRecorder.HEATER) < bundled.duty(SmallUnitRecorder.HEATER), "less heat");
        assertTrue(bundled.share("overhead") - lowered.share("overhead") >= .02, "a clear change in the light product: " + lowered.share("overhead"));
        // P5 stage B: H-102 raises the tower's steam from water, 0.21 MW whatever H-101 does, about a seventh of H-101's.
        assertEquals(.21e6, bundled.duty("H-102"), .1 * .21e6, "the steam heater's duty");
        assertEquals(bundled.duty("H-102"), lowered.duty("H-102"), 1e-6 * bundled.duty("H-102"));
    }

    @Test
    void aRerunOfTheSimulatorGivesTheFile() throws Exception {
        JsonElement recorded;
        try (var in = SmallUnitRecordingTest.class.getResourceAsStream(SmallUnitData.PATH)) {
            recorded = JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in), StandardCharsets.UTF_8));
        }
        same("", recorded, JsonParser.parseString(SmallUnitRecorder.toJson(SmallUnitRecorder.record())));
    }

    private static void same(String at, JsonElement a, JsonElement b) {
        if (a.isJsonObject()) {
            assertTrue(b.isJsonObject(), at);
            assertEquals(a.getAsJsonObject().keySet(), b.getAsJsonObject().keySet(), at);
            for (var key : a.getAsJsonObject().keySet()) same(at + "." + key, a.getAsJsonObject().get(key), b.getAsJsonObject().get(key));
        } else if (a.isJsonArray()) {
            assertEquals(a.getAsJsonArray().size(), b.getAsJsonArray().size(), at);
            for (int i = 0; i < a.getAsJsonArray().size(); i++) same(at + "[" + i + "]", a.getAsJsonArray().get(i), b.getAsJsonArray().get(i));
        } else if (a.getAsJsonPrimitive().isNumber()) {
            double x = a.getAsDouble(), y = b.getAsDouble();
            assertEquals(x, y, 1e-9 * Math.max(1, Math.abs(x)), at);
        } else assertEquals(a, b, at);
    }
}
