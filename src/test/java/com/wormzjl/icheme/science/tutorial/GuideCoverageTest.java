package com.wormzjl.icheme.science.tutorial;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.tutorial.cards.Cards;
import com.wormzjl.icheme.science.tutorial.films.Films;
import com.wormzjl.icheme.science.tutorial.guide.Guide;
import com.wormzjl.icheme.science.tutorial.guide.GuideFile;
import com.wormzjl.icheme.science.tutorial.guide.TourFile;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The guide's server side (batch 2026-11-12-tutorial-system, P1; owner decision D2): every step has its advancement under
 * {@code data/icheme/advancement/tutorial/} with the criterion its check asks for, in a parent chain up to the root; the
 * root gives the Field Guide once by its loot table; every kind an {@code equipment_built} names is a structure kind;
 * every film and card the guide and the tours name resolves.
 */
class GuideCoverageTest {
    private static final Path DATA = Path.of("src/main/resources/data/icheme"), ADVANCEMENTS = DATA.resolve("advancement/tutorial");
    private static final Set<String> TRIGGERS = Set.of("minecraft:tick", "icheme:guide_step", "icheme:equipment_built", "icheme:still_state", "icheme:still_stopped",
            "icheme:diverter_switched");

    private static JsonObject read(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    private static Path advancement(String id) {
        assertTrue(id.startsWith("icheme:tutorial/"), id);
        return ADVANCEMENTS.resolve(id.substring("icheme:tutorial/".length()) + ".json");
    }

    /** The criteria of one advancement: each one's trigger and conditions. */
    private static List<JsonObject> criteria(JsonObject advancement) {
        var out = new ArrayList<JsonObject>();
        for (var e : advancement.getAsJsonObject("criteria").entrySet()) out.add(e.getValue().getAsJsonObject());
        return out;
    }

    @Test
    void everyStepHasItsAdvancementWithTheCriterionItsCheckAsks() throws IOException {
        var guide = GuideFile.load();
        for (var chapter : guide.chapters())
            for (var step : chapter.steps()) {
                String id = chapter.id(step);
                var file = ADVANCEMENTS.resolve(id + ".json");
                assertTrue(Files.isRegularFile(file), id + ": " + file);
                var json = read(file);
                var criteria = criteria(json);
                assertEquals(1, criteria.size(), id + ": one criterion");
                var criterion = criteria.getFirst();
                if (step.check() == Guide.Check.CLIENT) {
                    assertEquals("icheme:guide_step", criterion.get("trigger").getAsString(), id);
                    assertEquals(id, criterion.getAsJsonObject("conditions").get("step").getAsString(), id + ": the step its own");
                } else assertEquals("icheme:" + step.criterion(), criterion.get("trigger").getAsString(), id + ": a SERVER step listens to its engine criterion");
                var display = json.getAsJsonObject("display");
                assertFalse(display.get("show_toast").getAsBoolean(), id);
                assertFalse(display.get("announce_to_chat").getAsBoolean(), id);
                // The chain up to the root.
                var seen = new HashSet<String>();
                var at = json;
                while (at.has("parent")) {
                    String parent = at.get("parent").getAsString();
                    assertTrue(seen.add(parent), id + ": no loop at " + parent);
                    assertTrue(Files.isRegularFile(advancement(parent)), id + ": its parent " + parent);
                    at = read(advancement(parent));
                }
                assertTrue(seen.contains("icheme:tutorial/root"), id + ": its chain ends at the root");
            }
    }

    @Test
    void theStepsChainInGuideOrderAndTheStillFollowsTheLastCrudeStep() throws IOException {
        var guide = GuideFile.load();
        String parent = "icheme:tutorial/root";
        var files = new HashSet<Path>();
        for (var id : guide.stepIds()) {
            assertEquals(parent, read(ADVANCEMENTS.resolve(id + ".json")).get("parent").getAsString(), id + ": after the step before it");
            parent = "icheme:tutorial/" + id;
            files.add(ADVANCEMENTS.resolve(id + ".json"));
        }
        assertEquals("icheme:tutorial/still/stop", parent);
        assertEquals("icheme:tutorial/crude/first", read(ADVANCEMENTS.resolve("still/build.json")).get("parent").getAsString(), "the still's build after chapter 1");
        files.add(ADVANCEMENTS.resolve("root.json"));
        try (Stream<Path> all = Files.walk(ADVANCEMENTS)) {
            for (var file : all.filter(p -> p.toString().endsWith(".json")).toList()) assertTrue(files.contains(file), file + ": no advancement without its step");
        }
    }

    @Test
    void theRootGivesTheFieldGuideOnce() throws IOException {
        var root = read(ADVANCEMENTS.resolve("root.json"));
        assertFalse(root.has("parent"));
        assertEquals("minecraft:tick", criteria(root).getFirst().get("trigger").getAsString(), "earned at the first tick in the world");
        assertEquals("icheme:field_guide", root.getAsJsonObject("display").getAsJsonObject("icon").get("id").getAsString());
        assertTrue(root.getAsJsonObject("display").has("background"));
        var loot = root.getAsJsonObject("rewards").getAsJsonArray("loot");
        assertEquals(1, loot.size());
        assertEquals("icheme:tutorial/field_guide", loot.get(0).getAsString());
        var table = read(DATA.resolve("loot_table/tutorial/field_guide.json"));
        assertEquals("minecraft:advancement_reward", table.get("type").getAsString());
        var entries = table.getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("entries");
        assertEquals(1, entries.size());
        assertEquals("icheme:field_guide", entries.get(0).getAsJsonObject().get("name").getAsString());
        assertTrue(Files.isRegularFile(Path.of("src/main/resources/assets/icheme/models/item/field_guide.json")));
        assertTrue(Files.isRegularFile(Path.of("src/main/resources/assets/icheme/textures/item/field_guide.png")));
        assertNotNull(TutorialText.english().get("item.icheme.field_guide"));
    }

    @Test
    void everyCriterionIsRegisteredAndEveryKindIsAStructureKind() throws IOException {
        var source = Files.readString(Path.of("src/main/java/com/wormzjl/icheme/runtime/tutorial/TutorialTriggers.java"));
        for (var name : List.of("guide_step", "equipment_built", "still_state", "still_stopped", "diverter_switched"))
            assertTrue(source.contains("register(\"" + name + "\""), name + " registered");
        var kinds = new HashSet<String>();
        for (var kind : EquipmentShape.Kind.values()) kinds.add(kind.name());
        var lang = TutorialText.english();
        int built = 0;
        try (Stream<Path> files = Files.walk(ADVANCEMENTS)) {
            for (var file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                var json = read(file);
                for (var criterion : criteria(json)) {
                    String trigger = criterion.get("trigger").getAsString();
                    assertTrue(TRIGGERS.contains(trigger), file + " names " + trigger);
                    if (trigger.equals("icheme:equipment_built") && criterion.has("conditions") && criterion.getAsJsonObject("conditions").has("kind")) {
                        assertTrue(kinds.contains(criterion.getAsJsonObject("conditions").get("kind").getAsString()), file + ": a structure kind");
                        built++;
                    }
                }
                var display = json.getAsJsonObject("display");
                for (var part : List.of("title", "description"))
                    assertNotNull(lang.get(display.getAsJsonObject(part).get("translate").getAsString()), file + ": its " + part + "'s English line");
            }
        }
        assertTrue(built >= 1, "the still's build proves the server path");
        assertTrue(Files.isRegularFile(ADVANCEMENTS.resolve("still/build.json")));
    }

    /**
     * P2b: the still's steps ask what the recording and the brief hold: half a pot to charge, a firing status to light, a
     * distilling one for the first drops (the text's minutes the recording's), and the cut at the recording's end of the
     * naphtha in whole K, distilling.
     */
    @Test
    void theStillStepsHoldTheRecordingsNumbers() throws IOException {
        var run = com.wormzjl.icheme.science.tutorial.films.StillRunData.load();
        var firing = List.of("HEATING", "BOILING", "DISTILLING", "COOLING_LIMITED");
        assertEquals(.5, conditions("charge").get("min_filled").getAsDouble());
        assertEquals(firing, strings(conditions("fire")));
        // The first drop and the cut count liquid at the spout, never a head that only vents its gas (the partial condenser).
        for (var step : List.of("first", "cut")) {
            assertTrue(conditions(step).get("liquid").getAsBoolean(), step + " asks for liquid at the spout");
            assertFalse(conditions(step).has("status"), step + " reads the spout, not a status");
        }
        assertEquals(Math.round(run.line("naphtha", "pot_kelvin")), conditions("cut").get("min_pot_temperature").getAsDouble(), 0, "the cut: the end of the naphtha, whole K");
        assertEquals(com.wormzjl.icheme.science.tutorial.guide.StillCondition.FIRING, Set.copyOf(firing), "the stop step's firing statuses");
        var lang = TutorialText.english();
        String first = lang.get("tutorial.icheme.guide.still.first");
        var minutes = java.util.regex.Pattern.compile("(\\d+) minutes? of play").matcher(first);
        assertTrue(minutes.find(), first);
        // The first drop is a trace; the step names when the spout has given a trickle the player sees in the drum.
        assertEquals(Math.max(1, Math.round(run.line("trickle", "play_minutes"))), Long.parseLong(minutes.group(1)), "the trickle's minutes of play, the recording's");
        // The fire out: at the end of the kerosene, its minutes of play the recording's.
        String stop = lang.get("tutorial.icheme.guide.still.stop");
        var out = java.util.regex.Pattern.compile("(\\d+) minutes? of play").matcher(stop);
        assertTrue(out.find(), stop);
        assertEquals(Math.round(run.line("kerosene", "play_minutes")), Long.parseLong(out.group(1)), "the fire out's minutes of play: the end of the kerosene");
        assertNotNull(lang.get("tutorial.icheme.guide.still.source"));
        assertTrue(lang.get("tutorial.icheme.guide.still.charge").contains("%s"), "the charge step names its source through the source line");
    }

    private static JsonObject conditions(String step) throws IOException {
        return criteria(read(ADVANCEMENTS.resolve("still/" + step + ".json"))).getFirst().getAsJsonObject("conditions");
    }

    private static List<String> strings(JsonObject conditions) {
        var out = new ArrayList<String>();
        for (var e : conditions.getAsJsonArray("status")) out.add(e.getAsString());
        return out;
    }

    @Test
    void everyFilmAndCardResolves() {
        var guide = GuideFile.load();
        for (var chapter : guide.chapters())
            for (var step : chapter.steps())
                switch (step.media()) {
                    case Guide.Film f -> assertNotNull(Films.build(f.name()), f.name());
                    case Guide.Card c -> assertNotNull(Cards.build(c.name()), c.name());
                    default -> {
                    }
                }
        for (var tour : TourFile.load().values()) if (tour.film() != null) assertNotNull(Films.build(tour.film()), tour.page());
    }
}
