package com.wormzjl.icheme.science.tutorial;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.tutorial.films.StillRunData;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The still run of chapter 2 (batch 2026-11-12-tutorial-system, P2b): its mass balance closes, the distilled share only
 * rises, the pot only warms while it distils, the derived lines are in order, and a rerun of the law gives the file to
 * 1e-9 (the rerun costs the same law steps as the recording took). Prints the derived lines.
 */
class StillRunRecordingTest {
    @Test
    void theMassBalanceCloses() {
        var run = StillRunData.load();
        var s = run.setup();
        double charge = s.get("charge_kg"), left = s.get("left_kg"), distilled = s.get("distilled_kg"), vented = s.get("vented_kg");
        assertEquals(charge, left + distilled + vented, 1e-6 * charge, "charge = left in the pot + distilled + vented");
        var time = run.time();
        double end = time.end();
        assertEquals(distilled / charge, run.series("distilled").at(end), 1e-12);
        assertEquals(vented / charge, run.series("vented").at(end), 1e-12);
        assertTrue(time.size() <= 240, "at most 240 points: " + time.size());
        assertEquals(.6, run.series("level").at(0), 1e-12, "charged 60 % full");
    }

    @Test
    void theDistilledShareRisesAndThePotWarmsWhileItDistils() {
        var run = StillRunData.load();
        var distilled = run.series("distilled");
        var pot = run.series("pot");
        var status = run.words().get("status");
        for (int k = 1; k < distilled.size(); k++) {
            assertTrue(distilled.value(k) >= distilled.value(k - 1), "distilled falls at " + k);
            if (status.get(k).equals("DISTILLING")) assertTrue(pot.value(k) >= pot.value(k - 1), "the pot cools while distilling at " + k);
        }
        for (var word : status) assertDoesNotThrow(() -> com.wormzjl.icheme.science.fluid.network.BatchStill.Status.valueOf(word));
    }

    @Test
    void theDerivedLinesAreInOrder() {
        var run = StillRunData.load();
        double first = run.line("first", "time"), naphtha = run.line("naphtha", "time"), kerosene = run.line("kerosene", "time"), end = run.line("end", "time");
        assertTrue(first > 0 && first < naphtha && naphtha <= end, first + " " + naphtha + " " + end);
        if (!Double.isNaN(kerosene)) assertTrue(naphtha < kerosene && kerosene <= end);
        else assertEquals("REFUSED", run.setupWords().get("finish"), "a run that never reaches the end of kerosene ended early");
        if ("REFUSED".equals(run.setupWords().get("finish"))) assertEquals("law_refusal", run.setupWords().get("ended_at"), "the set-up says the run ended at the law's refusal");
        assertTrue(run.line("first", "pot_kelvin") < run.line("naphtha", "pot_kelvin"));
        var parts = run.setup();
        assertEquals(end, run.time().end(), 1e-9);
        for (var name : new String[]{"first", "naphtha", "kerosene", "end"})
            if (!Double.isNaN(run.line(name, "time"))) {
                assertEquals(run.line(name, "time") / 4 / 60, run.line(name, "play_minutes"), 1e-9, name);
                System.out.printf(Locale.ROOT, "%-9s %8.1f s  pot %.2f K  %.1f min of play%n", name, run.line(name, "time"), run.line(name, "pot_kelvin"), run.line(name, "play_minutes"));
            }
        assertNotNull(parts.get("charge_kg"));
        // The end of naphtha is where the share taken overhead reaches the charge's gas and naphtha.
        double share = StillRunData.overhead(run, naphtha);
        assertTrue(share > .1 && share < .3, "gas and naphtha: " + share);
    }

    @Test
    void aRerunOfTheLawGivesTheFile() throws Exception {
        var recorded = parse();
        int steps = recorded.getAsJsonObject().getAsJsonObject("setup").get("steps").getAsInt();
        var run = StillRunRecorder.run();
        assertEquals(steps, run.steps(), "the rerun takes the recording's law steps");
        same("", recorded, JsonParser.parseString(StillRunRecorder.toJson(run)));
    }

    private static JsonElement parse() throws Exception {
        try (var in = StillRunRecordingTest.class.getResourceAsStream(StillRunData.PATH)) {
            return JsonParser.parseReader(new InputStreamReader(Objects.requireNonNull(in), StandardCharsets.UTF_8));
        }
    }

    private static void same(String at, JsonElement a, JsonElement b) {
        if (a.isJsonObject()) {
            assertTrue(b.isJsonObject(), at);
            assertEquals(a.getAsJsonObject().keySet(), b.getAsJsonObject().keySet(), at);
            for (var key : a.getAsJsonObject().keySet()) same(at + "." + key, a.getAsJsonObject().get(key), b.getAsJsonObject().get(key));
        } else if (a.isJsonArray()) {
            assertEquals(a.getAsJsonArray().size(), b.getAsJsonArray().size(), at);
            for (int i = 0; i < a.getAsJsonArray().size(); i++) same(at + "[" + i + "]", a.getAsJsonArray().get(i), b.getAsJsonArray().get(i));
        } else if (a.isJsonNull()) assertTrue(b.isJsonNull(), at);
        else if (a.getAsJsonPrimitive().isNumber()) {
            double x = a.getAsDouble(), y = b.getAsDouble();
            assertEquals(x, y, 1e-9 * Math.max(1, Math.abs(x)), at);
        } else assertEquals(a, b, at);
    }
}
