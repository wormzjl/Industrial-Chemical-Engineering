package com.wormzjl.icheme.science.tutorial;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.tutorial.guide.Guide;
import com.wormzjl.icheme.science.tutorial.guide.GuideFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The guide's file (batch 2026-11-12-tutorial-system, P1): chapter 0 as written, and what the reader refuses. */
class GuideFileTest {
    private static JsonObject json(String text) {
        return JsonParser.parseString(text.replace('\'', '"')).getAsJsonObject();
    }

    private static Guide read(String text) {
        return GuideFile.read(json(text), "film_a"::equals, "card_a"::equals);
    }

    @Test
    void chapterZeroReadsAsWritten() {
        var guide = GuideFile.load();
        var welcome = guide.chapters().getFirst();
        assertEquals("welcome", welcome.id());
        assertEquals(List.of("guide", "units", "help"), welcome.steps().stream().map(Guide.Step::id).toList());
        assertEquals(List.of(Guide.Media.NONE, Guide.Media.UNITS, Guide.Media.NONE), welcome.steps().stream().map(Guide.Step::media).toList());
        var crude = guide.chapters().get(1);
        assertEquals("crude", crude.id());
        assertEquals(List.of("what", "barrel", "why", "road", "parts", "first"), crude.steps().stream().map(Guide.Step::id).toList());
        assertEquals(List.of(new Guide.Card("chain"), new Guide.Card("barrel"), new Guide.Card("products"), new Guide.Card("road"), new Guide.Card("three_parts"),
                new Guide.Film("still_inside")), crude.steps().stream().map(Guide.Step::media).toList());
        for (var chapter : guide.chapters()) for (var step : chapter.steps()) assertEquals(Guide.Check.CLIENT, step.check(), step.id());
        assertEquals("tutorial.icheme.guide.welcome", welcome.titleKey());
        assertEquals("tutorial.icheme.guide.crude.first", crude.key(crude.steps().get(5)));
        assertEquals("crude/first", guide.terms().get("still"));
        assertEquals("crude/what", guide.terms().get("hydrocarbon"));
        for (var term : List.of("gas", "naphtha", "kerosene", "diesel", "gas_oil", "residue")) assertEquals("crude/barrel", guide.terms().get(term), term);
        assertEquals("tutorial/welcome/help", Guide.advancement("welcome/help"));
        assertNotNull(guide.step("crude/parts"));
        assertNull(guide.step("welcome/dots"), "moved to crude/parts");
        assertNull(guide.step("welcome/none"));
        assertFalse(guide.stepIds().contains("still/build"), "the still's build step comes in P2");
    }

    @Test
    void theGuideLoadsWithNoMinecraftClass() throws Exception {
        try (var loader = new MinecraftFree()) {
            var file = loader.loadClass(GuideFile.class.getName());
            assertNotSame(GuideFile.class, file);
            assertNotNull(file.getMethod("load").invoke(null));
            for (var name : List.of("com.wormzjl.icheme.science.tutorial.guide.Progress", "com.wormzjl.icheme.science.tutorial.guide.Glossary",
                    "com.wormzjl.icheme.science.tutorial.guide.Marked", "com.wormzjl.icheme.science.tutorial.cards.ThreeParts", "com.wormzjl.icheme.science.tutorial.cards.Chain",
                    "com.wormzjl.icheme.science.tutorial.cards.BarrelData", "com.wormzjl.icheme.science.tutorial.cards.Barrel", "com.wormzjl.icheme.science.tutorial.cards.Products",
                    "com.wormzjl.icheme.science.tutorial.cards.Road"))
                Class.forName(name, true, loader);
            // Every card builds, its recording read, with no Minecraft class.
            var cards = loader.loadClass("com.wormzjl.icheme.science.tutorial.cards.Cards");
            for (var name : (List<?>) cards.getMethod("names").invoke(null)) assertNotNull(cards.getMethod("build", String.class).invoke(null, name), String.valueOf(name));
        }
    }

    @Test
    void aGoodFileReads() {
        var guide = read("{'terms':{'pot':'a/one'},'chapters':[{'id':'a','steps':[{'id':'one','media':'film:film_a'},{'id':'two','media':'card:card_a'},"
                + "{'id':'three','check':'server','criterion':'equipment_built'}]}]}");
        assertEquals(Guide.Check.SERVER, guide.step("a/three").check());
        assertEquals("equipment_built", guide.step("a/three").criterion());
    }

    @Test
    void itRefusesAnUnknownField() {
        assertThrows(IllegalArgumentException.class, () -> read("{'chapters':[],'extra':1}"));
        assertThrows(IllegalArgumentException.class, () -> read("{'chapters':[{'id':'a','steps':[{'id':'one'}],'title':'x'}]}"));
        assertThrows(IllegalArgumentException.class, () -> read("{'chapters':[{'id':'a','steps':[{'id':'one','text':'x'}]}]}"));
    }

    @Test
    void itRefusesADuplicateId() {
        assertThrows(IllegalArgumentException.class, () -> read("{'chapters':[{'id':'a','steps':[{'id':'one'}]},{'id':'a','steps':[{'id':'two'}]}]}"));
        assertThrows(IllegalArgumentException.class, () -> read("{'chapters':[{'id':'a','steps':[{'id':'one'},{'id':'one'}]}]}"));
    }

    @Test
    void itRefusesAnUnknownMediaName() {
        assertThrows(IllegalArgumentException.class, () -> read("{'chapters':[{'id':'a','steps':[{'id':'one','media':'film:none'}]}]}"));
        assertThrows(IllegalArgumentException.class, () -> read("{'chapters':[{'id':'a','steps':[{'id':'one','media':'card:none'}]}]}"));
        assertThrows(IllegalArgumentException.class, () -> read("{'chapters':[{'id':'a','steps':[{'id':'one','media':'picture:x'}]}]}"));
    }

    @Test
    void itRefusesABadCheckOrTerm() {
        assertThrows(IllegalArgumentException.class, () -> read("{'chapters':[{'id':'a','steps':[{'id':'one','check':'maybe'}]}]}"));
        assertThrows(IllegalArgumentException.class, () -> read("{'chapters':[{'id':'a','steps':[{'id':'one','check':'server'}]}]}"), "a server step names its criterion");
        assertThrows(IllegalArgumentException.class, () -> read("{'chapters':[{'id':'a','steps':[{'id':'one','criterion':'x'}]}]}"), "a client step names none");
        assertThrows(IllegalArgumentException.class, () -> read("{'terms':{'pot':'a/two'},'chapters':[{'id':'a','steps':[{'id':'one'}]}]}"));
        assertThrows(IllegalArgumentException.class, () -> read("{'chapters':[{'id':'A b','steps':[{'id':'one'}]}]}"));
    }
}
