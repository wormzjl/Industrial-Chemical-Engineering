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
        assertEquals(List.of("guide", "units", "dots", "film", "help"), welcome.steps().stream().map(Guide.Step::id).toList());
        assertEquals(List.of(Guide.Media.NONE, Guide.Media.UNITS, new Guide.Card("three_parts"), new Guide.Film("still_inside"), Guide.Media.NONE),
                welcome.steps().stream().map(Guide.Step::media).toList());
        for (var step : welcome.steps()) assertEquals(Guide.Check.CLIENT, step.check(), step.id());
        assertEquals("tutorial.icheme.guide.welcome", welcome.titleKey());
        assertEquals("tutorial.icheme.guide.welcome.film", welcome.key(welcome.steps().get(3)));
        assertEquals("welcome/film", guide.terms().get("still"));
        assertEquals("tutorial/welcome/help", Guide.advancement("welcome/help"));
        assertNotNull(guide.step("welcome/dots"));
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
                    "com.wormzjl.icheme.science.tutorial.guide.Marked", "com.wormzjl.icheme.science.tutorial.cards.ThreeParts"))
                Class.forName(name, true, loader);
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
