package com.wormzjl.icheme.science.tutorial;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.tutorial.cards.StillTraysData;
import com.wormzjl.icheme.science.tutorial.films.StillRunData;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The still with a column of chapter 3 (batch 2026-11-12-tutorial-system, P4b): the pot still's form is chapter 2's run,
 * the first cut grows purer and slower with the reflux, the column the chapter shows gains at least 10 points of naphtha
 * on the pot still, solves itself on at least 80 % of its boiling steps after the light gas and cuts within 45 minutes of
 * play, and a rerun of the law gives the file to 1e-9.
 */
class StillTraysRecordingTest {
    /** The form chapter 3 compares with the pot still. */
    static final String SHOWN = "trays_r2";

    @Test
    void thePotStillIsChapterTwosRun() {
        var pot = StillTraysData.pot();
        var run = StillRunData.load();
        assertEquals(0, pot.stages());
        assertEquals(run.line("naphtha", "time"), pot.first().time(), 1e-6, "the first cut ends at chapter 2's end of naphtha");
        assertEquals(run.line("kerosene", "time"), pot.second().time(), 1e-6, "the second at its end of kerosene");
        assertEquals(run.setupWords().get("finish"), pot.finish());
    }

    @Test
    void theCutGrowsPurerAndSlowerWithTheReflux() {
        var forms = StillTraysData.load();
        assertEquals("pot", forms.getFirst().name());
        for (int k = 1; k < forms.size(); k++) {
            var a = forms.get(k - 1).first();
            var b = forms.get(k).first();
            assertTrue(b.part(1) > a.part(1), "purer at " + forms.get(k).name());
            assertTrue(b.playMinutes() > a.playMinutes(), "slower at " + forms.get(k).name());
            if (k > 1) assertTrue(forms.get(k).reflux() > forms.get(k - 1).reflux());
        }
        for (var f : forms) {
            assertEquals(1, java.util.Arrays.stream(f.first().parts()).sum(), 1e-9, f.name());
            assertTrue(f.first().time() < f.second().time(), f.name());
        }
        int trays = StillTraysData.trays();
        assertTrue(trays >= com.wormzjl.icheme.science.equipment.BatchStillOptions.MIN_STAGES && trays <= com.wormzjl.icheme.science.equipment.BatchStillOptions.MAX_STAGES,
                "a tray count the constructor offers: " + trays);
    }

    @Test
    void theShownColumnClearlyRectifies() throws Exception {
        var shown = StillTraysData.form(SHOWN);
        double gain = shown.first().part(1) - StillTraysData.pot().first().part(1);
        assertTrue(gain >= .10, "at least 10 points of naphtha on the pot still: " + gain);
        assertTrue(shown.first().playMinutes() < 45, "a tutorial step's length: " + shown.first().playMinutes());
        var column = form(parse(), SHOWN).getAsJsonObject("column");
        int after = column.get("boiling_after_gas").getAsInt(), solved = column.get("solved_after_gas").getAsInt();
        assertTrue(solved >= .8 * after, "the column solves itself after the light gas: " + solved + " of " + after);
    }

    @Test
    void aRerunOfTheLawGivesTheFile() throws Exception {
        var recorded = parse();
        same("", recorded, JsonParser.parseString(StillTraysRecorder.toJson(StillTraysRecorder.record())));
    }

    private static com.google.gson.JsonObject form(JsonElement root, String name) {
        for (var f : root.getAsJsonObject().getAsJsonArray("forms"))
            if (f.getAsJsonObject().get("name").getAsString().equals(name)) return f.getAsJsonObject();
        throw new AssertionError("No form " + name);
    }

    private static JsonElement parse() throws Exception {
        try (var in = StillTraysRecordingTest.class.getResourceAsStream(StillTraysData.PATH)) {
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
