package com.wormzjl.icheme.science.tutorial;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.client.gui.tutorial.cards.Cards;
import com.wormzjl.icheme.science.tutorial.films.Films;
import com.wormzjl.icheme.science.tutorial.guide.Guide;
import com.wormzjl.icheme.science.tutorial.guide.GuideFile;
import com.wormzjl.icheme.science.tutorial.guide.TourFile;
import com.wormzjl.icheme.science.tutorial.guide.PageFile;
import com.wormzjl.icheme.client.gui.tutorial.cards.UnitMap;
import com.wormzjl.icheme.science.tutorial.equipment.EquipmentInfo;
import com.wormzjl.icheme.science.tutorial.films.SmallTower;
import com.wormzjl.icheme.science.assembly.Blueprint;
import com.wormzjl.icheme.science.assembly.ColumnBlueprint;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import java.util.Objects;
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
            "icheme:diverter_switched", "icheme:sheet_run", "icheme:design_sent", "icheme:assembly_state", "icheme:column_state");

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

    /**
     * No two steps listen to the same trigger with the same conditions (code review finding 3: the simulator's Send also
     * completed the next chapter's site step): one event completes one step.
     */
    @Test
    void noTwoStepsShareATriggerAndItsConditions() throws IOException {
        var seen = new java.util.HashMap<String, String>();
        for (var chapter : GuideFile.load().chapters())
            for (var step : chapter.steps()) {
                String id = chapter.id(step);
                for (var criterion : criteria(read(ADVANCEMENTS.resolve(id + ".json")))) {
                    String key = criterion.get("trigger").getAsString() + " " + (criterion.has("conditions") ? criterion.get("conditions").toString() : "{}");
                    String other = seen.putIfAbsent(key, id);
                    assertNull(other, id + " and " + other + " both listen to " + key);
                }
            }
    }

    /**
     * A client step with no media has only a screen to report it: each is one a screen names (P5 stage B: the start-up
     * chapter's feed, fire and water steps had none, and would never have completed). The constants are compile-time, so
     * no screen class loads.
     */
    @Test
    void everyClientStepWithNoMediaIsOneAScreenReports() {
        var reported = Set.of(com.wormzjl.icheme.client.gui.tutorial.GuideScreen.OPENED, com.wormzjl.icheme.client.gui.tutorial.GuideScreen.TOURED,
                com.wormzjl.icheme.client.gui.flowsheet.SimulatorTour.OPEN_STEP, com.wormzjl.icheme.client.gui.flowsheet.SimulatorTour.SIZE_STEP);
        for (var chapter : GuideFile.load().chapters())
            for (var step : chapter.steps())
                if (step.check() == Guide.Check.CLIENT && step.media() instanceof Guide.None)
                    assertTrue(reported.contains(chapter.id(step)), chapter.id(step) + ": a client step no screen reports never completes");
    }

    /**
     * P5 stage B corrections: the start-up chapter asks for every supply before On, one thing a step, each step opening the
     * page of the kind it builds; the tanks come before On, since a tower with no room for a product stands at 0 %.
     */
    @Test
    void theStartUpChapterAsksForEverySupplyBeforeOn() {
        var start = GuideFile.load().chapters().stream().filter(c -> c.id().equals("start")).findFirst().orElseThrow();
        var ids = start.steps().stream().map(Guide.Step::id).toList();
        assertEquals(List.of("crude", "fuel", "power", "cooling", "steam", "tanks"), ids.subList(0, ids.indexOf("go")), "the supplies, then On");
        for (var step : start.steps().subList(0, ids.indexOf("go")))
            assertInstanceOf(Guide.Page.class, step.media(), "start/" + step.id() + " opens the page of the kind it builds");
        assertEquals("products", ids.get(ids.size() - 2), "the first product leaving is the last check");
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
        assertEquals("icheme:tutorial/start/end", parent);
        assertEquals("icheme:tutorial/unit/next", read(ADVANCEMENTS.resolve("building/site.json")).get("parent").getAsString(), "chapter 6 after chapter 5");
        assertEquals("icheme:tutorial/simulator/send", read(ADVANCEMENTS.resolve("unit/whole.json")).get("parent").getAsString(), "chapter 5 after chapter 4");
        assertEquals("icheme:tutorial/crude/first", read(ADVANCEMENTS.resolve("still/build.json")).get("parent").getAsString(), "the still's build after chapter 1");
        assertEquals("icheme:tutorial/still/stop", read(ADVANCEMENTS.resolve("tower/tray.json")).get("parent").getAsString(), "chapter 3 after the still");
        assertEquals("icheme:tutorial/tower/many", read(ADVANCEMENTS.resolve("simulator/open.json")).get("parent").getAsString(), "chapter 4 after chapter 3");
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
        for (var name : List.of("guide_step", "equipment_built", "still_state", "still_stopped", "diverter_switched", "sheet_run", "design_sent", "assembly_state", "column_state"))
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
        return conditions("still", step);
    }

    private static JsonObject conditions(String chapter, String step) throws IOException {
        return criteria(read(ADVANCEMENTS.resolve(chapter + "/" + step + ".json"))).getFirst().getAsJsonObject("conditions");
    }

    /** The first number before {@code after} in {@code text} ("22 minutes of play" gives 22), or fails. */
    private static double number(String text, String after) {
        var m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?) ?" + java.util.regex.Pattern.quote(after)).matcher(text);
        assertTrue(m.find(), "\"" + after + "\" in: " + text);
        return Double.parseDouble(m.group(1));
    }

    /**
     * P4b: chapter 3 asks what the still with a column records (still_trays.json): a still built with a column of the
     * recording's trays, the valve open at the shown form's ratio, and its first cut at that form's pot temperature in whole
     * K, liquid at the spout; the texts' trays, ratio and minutes of play are the recording's.
     */
    @Test
    void theTowerStepsHoldTheRecordingsNumbers() throws IOException {
        var shown = com.wormzjl.icheme.science.tutorial.cards.StillTraysData.columns().getLast();
        var build = conditions("tower", "build");
        assertEquals("BATCH_STILL", build.get("kind").getAsString());
        assertTrue(build.get("column").getAsBoolean(), "a still with a column");
        var reflux = conditions("tower", "reflux");
        assertTrue(reflux.get("column").getAsBoolean());
        assertEquals(shown.reflux(), reflux.get("min_reflux").getAsDouble(), 0, "the shown form's ratio");
        var compare = conditions("tower", "compare");
        assertTrue(compare.get("column").getAsBoolean());
        assertTrue(compare.get("liquid").getAsBoolean(), "the cut is liquid at the spout");
        assertEquals(Math.round(shown.first().pot()), compare.get("min_pot_temperature").getAsDouble(), 0, "the first cut's pot temperature, whole K");
        var lang = TutorialText.english();
        String text = lang.get("tutorial.icheme.guide.tower.build");
        assertEquals(shown.stages(), (int) number(text, "trays"), "the build's trays: " + text);
        assertEquals(shown.stages(), (int) number(lang.get("tutorial.icheme.guide.tower.build"), ":"), "Stages set to the recording's trays");
        var ratio = java.util.regex.Pattern.compile("ratio to (\\d+)").matcher(lang.get("tutorial.icheme.guide.tower.reflux"));
        assertTrue(ratio.find());
        assertEquals(shown.reflux(), Double.parseDouble(ratio.group(1)), 0, "the ratio the text names");
        assertEquals(Math.round(shown.first().playMinutes()), (long) number(lang.get("tutorial.icheme.guide.tower.compare"), "minutes of play"), "the first cut's minutes of play");
    }

    /**
     * P4b: chapter 4 asks what the bundled small unit records (small_unit.json): the balance text's shares by mass, the
     * lowered heater's outlet as the heat step's condition and its sentence's temperature and top share.
     */
    @Test
    void theSimulatorStepsHoldTheRecordingsNumbers() throws IOException {
        var bundled = com.wormzjl.icheme.science.tutorial.cards.SmallUnitData.bundled();
        var lowered = com.wormzjl.icheme.science.tutorial.cards.SmallUnitData.lowered();
        assertEquals("STEADY", bundled.status());
        assertEquals("STEADY", lowered.status());
        var lang = TutorialText.english();
        String balance = lang.get("tutorial.icheme.guide.simulator.balance");
        var shares = java.util.regex.Pattern.compile("(\\d+), (\\d+) and (\\d+) % by mass").matcher(balance);
        assertTrue(shares.find(), balance);
        for (int i = 0; i < 3; i++)
            assertEquals(Math.round(100 * bundled.share(com.wormzjl.icheme.science.tutorial.cards.SmallUnitData.PRODUCTS.get(i))), Long.parseLong(shares.group(i + 1)), balance);
        assertEquals(lowered.heaterKelvin(), conditions("simulator", "heat").get("max_heater_outlet").getAsDouble(), 1e-9, "the heat step: the lowered outlet");
        assertEquals("H-101", conditions("simulator", "heat").get("heater").getAsString(), "the heat step: H-101's outlet, not the steam heater's");
        String heat = lang.get("tutorial.icheme.guide.simulator.heat");
        var outlet = com.wormzjl.icheme.science.tutorial.guide.Guide.KELVIN.matcher(heat);
        assertTrue(outlet.find(), "the outlet in the player's unit: " + heat);
        assertEquals(lowered.heaterKelvin(), Double.parseDouble(outlet.group(1)), 1e-9, "the lowered outlet, the recording's");
        var falls = java.util.regex.Pattern.compile("from (\\d+) to (\\d+) %").matcher(heat);
        assertTrue(falls.find(), heat);
        assertEquals(Math.round(100 * bundled.share("overhead")), Long.parseLong(falls.group(1)), heat);
        assertEquals(Math.round(100 * lowered.share("overhead")), Long.parseLong(falls.group(2)), heat);
        assertTrue(lowered.duty("H-101") < bundled.duty("H-101"), "less heat duty goes in");
        assertFalse(conditions("simulator", "run") != null && conditions("simulator", "run").has("max_heater_outlet"), "any run counts");
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
        for (var tour : TourFile.load().values()) {
            if (tour.film() != null) assertNotNull(Films.build(tour.film()), tour.page());
            if (tour.show() != null) assertNotNull(guide.step(tour.show()), tour.page() + " shows " + tour.show());
        }
        for (var page : PageFile.load().pages().values()) if (page.film() != null) assertNotNull(Films.build(page.film()), page.id());
    }

    /**
     * P5: every kind has its page, with the reference item's own name and lines (JEI's keys, no copy); the crude unit's chain
     * holds exactly the kinds its sheet and its column's assembly ({@link ColumnBlueprint#of}) name, with the converter and the
     * tanks, each once; every unit page with a body has its film, every one but the last its next link, and the chain from the
     * first visits each once and ends. The chain is the file's {@code unit} list: the pages' key order does not move it.
     */
    @Test
    void everyKindHasAPageAndTheUnitsChainVisitsEachOfItsKindsOnce() throws IOException {
        var pages = PageFile.load();
        for (var kind : EquipmentShape.Kind.values()) {
            var page = pages.page(kind);
            assertNotNull(page, kind + ": its page");
            assertEquals(EquipmentInfo.nameKey(kind), page.nameKey(), kind + ": the item's name");
            assertEquals(EquipmentInfo.pageKeys(kind), page.lines(), kind + ": JEI's lines");
        }
        // The unit's kinds: the sheet's units by the page each opens, the column's members as its design builds them, the crude's converter and the tanks.
        var expected = new java.util.LinkedHashSet<String>();
        expected.add(EquipmentInfo.itemId(EquipmentShape.Kind.FLUID_CONVERTER));
        expected.add(EquipmentInfo.itemId(EquipmentShape.Kind.TANK));
        for (var unit : UnitMap.sheet().units()) expected.add(Objects.requireNonNull(UnitMap.PAGES.get(unit.type()), unit.type()));
        var column = UnitMap.sheet().units().stream().filter(u -> u.type().equals("column")).findFirst().orElseThrow();
        var components = ColumnComponents.named(column.params().text("condenser", "AIR_COOLER"), "STANDARD", List.of("STANDARD"), false, false);
        var blueprint = ColumnBlueprint.of(SmallTower.preset().trays().withComponents(components));
        for (var link : blueprint.links())
            for (var step : link.steps())
                if (step.form() instanceof Blueprint.UnitForm form) expected.add(EquipmentInfo.itemId(form.kind()));
        assertEquals(expected, Set.copyOf(pages.unit()), "the unit's pages are its sheet's and its column's kinds");
        assertTrue(expected.contains(EquipmentInfo.itemId(EquipmentShape.Kind.DRUM)) && expected.contains("pressure_control_valve"), "the reflux drum and the valve");
        var visited = new ArrayList<String>();
        for (var page = pages.page(pages.unit().getFirst()); page != null; page = pages.next(page.id())) {
            assertFalse(visited.contains(page.id()), page.id() + ": once");
            visited.add(page.id());
            if (page.kind() != null) assertNotNull(page.film(), page.id() + ": its film");
        }
        assertEquals(pages.unit(), visited, "the chain visits the unit in its order and ends");
        assertNull(pages.page("pressure_control_valve").film(), "the control valve has no equipment body: its page is the header only");
        // The pages' keys reversed: the same chain.
        var json = JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/icheme/tutorial/pages.json"))).getAsJsonObject();
        var reversed = new JsonObject();
        var keys = new ArrayList<>(json.getAsJsonObject("pages").keySet());
        java.util.Collections.reverse(keys);
        var body = new JsonObject();
        for (var k : keys) body.add(k, json.getAsJsonObject("pages").get(k));
        reversed.add("pages", body);
        reversed.add("unit", json.get("unit"));
        var again = PageFile.read(reversed);
        for (var id : pages.unit()) assertEquals(pages.next(id) == null ? null : pages.next(id).id(), again.next(id) == null ? null : again.next(id).id(), id);
    }
}
