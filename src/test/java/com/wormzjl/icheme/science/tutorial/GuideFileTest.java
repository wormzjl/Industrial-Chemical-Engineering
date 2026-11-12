package com.wormzjl.icheme.science.tutorial;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.tutorial.guide.Guide;
import com.wormzjl.icheme.science.tutorial.guide.GuideFile;
import com.wormzjl.icheme.science.tutorial.guide.PageFile;
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
        for (var chapter : List.of(welcome, crude)) for (var step : chapter.steps()) assertEquals(Guide.Check.CLIENT, step.check(), step.id());
        assertEquals("tutorial.icheme.guide.welcome", welcome.titleKey());
        assertEquals("tutorial.icheme.guide.crude.first", crude.key(crude.steps().get(5)));
        assertEquals("crude/first", guide.terms().get("still"));
        assertEquals("crude/what", guide.terms().get("hydrocarbon"));
        for (var term : List.of("gas", "naphtha", "kerosene", "diesel", "gas_oil", "residue")) assertEquals("crude/barrel", guide.terms().get(term), term);
        assertEquals("tutorial/welcome/help", Guide.advancement("welcome/help"));
        assertNotNull(guide.step("crude/parts"));
        assertNull(guide.step("welcome/dots"), "moved to crude/parts");
        assertNull(guide.step("welcome/none"));
    }

    /** P2b: chapter 2, the still: its steps, media, checks and criteria, its terms, and its source line filling the charge step. */
    @Test
    void chapterTwoReadsAsWritten() {
        var guide = GuideFile.load();
        var still = guide.chapters().get(2);
        assertEquals("still", still.id());
        assertEquals(List.of("build", "charge", "water", "watch", "fire", "first", "cut", "switch", "stop"), still.steps().stream().map(Guide.Step::id).toList());
        assertEquals(List.of(new Guide.Film("still_build"), Guide.Media.NONE, Guide.Media.READ, new Guide.Film("still_run"), Guide.Media.NONE, Guide.Media.NONE, new Guide.Card("pot_temperature"),
                Guide.Media.NONE, Guide.Media.NONE), still.steps().stream().map(Guide.Step::media).toList());
        var criteria = new java.util.ArrayList<String>();
        for (var step : still.steps()) criteria.add(step.check() == Guide.Check.CLIENT ? "client" : step.criterion());
        assertEquals(List.of("equipment_built", "still_state", "client", "client", "still_state", "still_state", "still_state", "diverter_switched", "still_stopped"), criteria);
        for (var term : List.of("vapour", "condenser")) assertEquals("still/build", guide.terms().get(term), term);
        assertEquals("still/watch", guide.terms().get("distillate"));
        assertEquals("still/cut", guide.terms().get("cut"));
        assertNull(guide.terms().get("boil_dry"), "the run ends at the still's overheat cut, not dry");
        assertEquals("tutorial.icheme.guide.still.source", still.sourceKey());
        var lang = TutorialText.english();
        var charge = still.text(still.steps().get(1), lang::get);
        assertFalse(charge.contains("%s"), charge);
        assertTrue(charge.contains(lang.get(still.sourceKey())), "the charge step names the source: " + charge);
        assertEquals(lang.get(still.key(still.steps().get(0))), still.text(still.steps().get(0), lang::get), "a step with no %s reads as written");
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
                    "com.wormzjl.icheme.science.tutorial.cards.Road", "com.wormzjl.icheme.science.tutorial.cards.PotTemperature",
                    "com.wormzjl.icheme.science.tutorial.guide.StillCondition",
                    "com.wormzjl.icheme.science.tutorial.guide.SheetCondition", "com.wormzjl.icheme.science.tutorial.films.StillTray", "com.wormzjl.icheme.science.tutorial.films.SmallTower",
                    "com.wormzjl.icheme.science.tutorial.guide.PageFile", "com.wormzjl.icheme.science.tutorial.films.EquipmentFilms", "com.wormzjl.icheme.science.tutorial.films.CrudeUnit",
                    "com.wormzjl.icheme.science.tutorial.equipment.ReferenceShapes"))
                Class.forName(name, true, loader);
            // Every card builds, its recording read, with no Minecraft class.
            var cards = loader.loadClass("com.wormzjl.icheme.science.tutorial.cards.Cards");
            for (var name : (List<?>) cards.getMethod("names").invoke(null)) assertNotNull(cards.getMethod("build", String.class).invoke(null, name), String.valueOf(name));
            // P5: the pages read, and every film builds, with no Minecraft class.
            assertNotNull(loader.loadClass("com.wormzjl.icheme.science.tutorial.guide.PageFile").getMethod("load").invoke(null));
            var films = loader.loadClass("com.wormzjl.icheme.science.tutorial.films.Films");
            for (var name : (Iterable<?>) films.getMethod("names").invoke(null)) assertNotNull(films.getMethod("build", String.class).invoke(null, name), String.valueOf(name));
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

    /** P5: a page step names its page by its item id: a kind's reference item or one of the other items' pages; anything else is refused. */
    @Test
    void aPageStepReadsAndAnUnknownPageIsRefused() {
        var guide = read("{'chapters':[{'id':'a','steps':[{'id':'one','media':'page:equipment_column'},{'id':'two','media':'page:pressure_control_valve'}]}]}");
        assertEquals(new Guide.Page("equipment_column"), guide.step("a/one").media());
        assertThrows(IllegalArgumentException.class, () -> read("{'chapters':[{'id':'a','steps':[{'id':'one','media':'page:equipment_nothing'}]}]}"));
        assertThrows(IllegalArgumentException.class, () -> read("{'chapters':[{'id':'a','steps':[{'id':'one','media':'page:fluid_pipe'}]}]}"));
    }

    /** P5: the pages' file refuses an unknown field, a page that is no kind or listed item, a missing kind, an unknown film or tour, and a unit naming a page twice or none. */
    @Test
    void thePagesFileRefusesWhatItCannotShow() {
        var good = PageFile.load();
        java.util.function.Function<String, com.google.gson.JsonObject> with = extra -> {
            var json = new com.google.gson.JsonObject();
            var pages = new com.google.gson.JsonObject();
            for (var id : good.pages().keySet()) pages.add(id, new com.google.gson.JsonObject());
            json.add("pages", pages);
            json.add("unit", JsonParser.parseString("['equipment_pump','equipment_column']"));
            var change = JsonParser.parseString(extra).getAsJsonObject();
            for (var e : change.entrySet()) if (e.getKey().equals("unit")) json.add("unit", e.getValue());
                else if (e.getKey().equals("extra")) json.add("extra", e.getValue());
                else if (e.getValue().isJsonNull()) pages.remove(e.getKey());
                else pages.add(e.getKey(), e.getValue());
            return json;
        };
        assertEquals(List.of("equipment_pump", "equipment_column"), PageFile.read(with.apply("{}")).unit());
        assertThrows(IllegalArgumentException.class, () -> PageFile.read(with.apply("{'extra':1}")));
        assertThrows(IllegalArgumentException.class, () -> PageFile.read(with.apply("{'equipment_pump':{'picture':'x'}}")));
        assertThrows(IllegalArgumentException.class, () -> PageFile.read(with.apply("{'fluid_pipe':{}}")), "an item with no page");
        assertThrows(IllegalArgumentException.class, () -> PageFile.read(with.apply("{'equipment_pump':null}")), "a kind without its page");
        assertThrows(IllegalArgumentException.class, () -> PageFile.read(with.apply("{'equipment_pump':{'film':'none'}}")));
        assertThrows(IllegalArgumentException.class, () -> PageFile.read(with.apply("{'equipment_pump':{'tour':'none'}}")));
        assertThrows(IllegalArgumentException.class, () -> PageFile.read(with.apply("{'unit':['equipment_pump','equipment_pump']}")));
        assertThrows(IllegalArgumentException.class, () -> PageFile.read(with.apply("{'unit':['fluid_pipe']}")));
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
