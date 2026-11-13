package com.wormzjl.icheme.science.tutorial;

import com.google.gson.JsonParser;
import com.wormzjl.icheme.client.gui.flowsheet.FlowsheetCatalog;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.tutorial.equipment.EquipmentInfo;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The equipment reference items and the JEI pages (batch 2026-11-12-tutorial-system, P3), without a Minecraft bootstrap:
 * every structure kind has its item id in the registration loop's list, a model, a 64 px texture, a name and its three
 * lines; every item model has its {@code serves} line; no equipment or item line is left without its kind or item; the
 * shape-only lists are the process simulator catalog's DRAWING_ONLY kinds.
 */
class EquipmentInfoCoverageTest {
    private static final Path ASSETS = Path.of("src/main/resources/assets/icheme");
    private static final Path MODELS = ASSETS.resolve("models/item");
    /** The icons' side (tools/tutorial-renders/IconRender, owner 2026-11-12: 64 px). */
    private static final int ICON = 64;

    /**
     * The item models' ids that are items: every model file but a parent model no registration names (the sensors'
     * shared {@code sensor_probe}).
     */
    private static Set<String> itemModels() throws IOException {
        var ids = new TreeSet<String>();
        var parents = new HashSet<String>();
        try (Stream<Path> files = Files.list(MODELS)) {
            for (var file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                String name = file.getFileName().toString();
                ids.add(name.substring(0, name.length() - ".json".length()));
                var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                if (json.has("parent") && json.get("parent").getAsString().startsWith("icheme:item/"))
                    parents.add(json.get("parent").getAsString().substring("icheme:item/".length()));
            }
        }
        var sources = Files.readString(Path.of("src/main/java/com/wormzjl/icheme/registry/ModItems.java"))
                + Files.readString(Path.of("src/main/java/com/wormzjl/icheme/compat/create/CreateCompat.java"));
        for (var parent : parents) if (!sources.contains("\"" + parent + "\"")) ids.remove(parent);
        return ids;
    }

    @Test
    void everyKindHasItsItemModelTextureNameAndLines() throws IOException {
        var lang = TutorialText.english();
        var ids = EquipmentInfo.itemIds();
        assertEquals(EquipmentShape.Kind.values().length, ids.size());
        assertEquals(ids.size(), Set.copyOf(ids).size(), "the ids differ");
        var faults = new ArrayList<String>();
        for (var kind : EquipmentShape.Kind.values()) {
            String id = EquipmentInfo.itemId(kind);
            assertEquals(kind, EquipmentInfo.kindOf(id));
            var model = MODELS.resolve(id + ".json");
            if (!Files.isRegularFile(model)) faults.add(id + ": no model");
            else {
                var json = JsonParser.parseString(Files.readString(model)).getAsJsonObject();
                if (!json.get("parent").getAsString().equals("minecraft:item/generated")) faults.add(id + ": not item/generated");
                if (!json.getAsJsonObject("textures").get("layer0").getAsString().equals("icheme:item/" + id)) faults.add(id + ": its texture");
            }
            var texture = ASSETS.resolve("textures/item/" + id + ".png");
            if (!Files.isRegularFile(texture)) faults.add(id + ": no texture");
            else {
                var image = ImageIO.read(texture.toFile());
                if (image.getWidth() != ICON || image.getHeight() != ICON) faults.add(id + ": texture " + image.getWidth() + " by " + image.getHeight());
            }
            if (lang.get(EquipmentInfo.nameKey(kind)) == null) faults.add(id + ": no name " + EquipmentInfo.nameKey(kind));
            for (var key : EquipmentInfo.pageKeys(kind)) if (lang.get(key) == null) faults.add(id + ": no line " + key);
        }
        assertEquals(List.of(), faults);
        assertNotNull(lang.get(EquipmentInfo.REFERENCE_KEY));
        assertNotNull(lang.get("itemGroup.icheme.equipment_guide"));
    }

    @Test
    void theItemsAreRegisteredByTheLoopAndShownInTheirTab() throws IOException {
        var items = Files.readString(Path.of("src/main/java/com/wormzjl/icheme/registry/ModItems.java"));
        assertTrue(items.contains("EquipmentShape.Kind.values()") && items.contains("EquipmentInfo.itemId(kind)"), "one registration a kind, by its id");
        var tabs = Files.readString(Path.of("src/main/java/com/wormzjl/icheme/registry/ModCreativeTabs.java"));
        assertTrue(tabs.contains("register(\"equipment_guide\"") && tabs.contains("ModItems.EQUIPMENT_INFO.values()"), "the Equipment guide tab holds them");
    }

    @Test
    void everyItemModelHasItsServesLine() throws IOException {
        var lang = TutorialText.english();
        var faults = new ArrayList<String>();
        var items = itemModels();
        for (var id : items) {
            if (EquipmentInfo.kindOf(id) != null) continue;
            for (var key : EquipmentInfo.itemPageKeys(id)) if (lang.get(key) == null) faults.add(id + ": no line " + key);
        }
        for (var id : EquipmentInfo.ITEMS_THAT_NEED) if (!items.contains(id)) faults.add(id + ": needs a line but is no item");
        assertEquals(List.of(), faults);
        assertTrue(items.size() > 70, "the mod's items and the 38 reference items: " + items.size());
    }

    @Test
    void noLineIsLeftWithoutItsKindOrItem() throws IOException {
        var items = itemModels();
        var faults = new ArrayList<String>();
        for (var key : TutorialText.english().keySet()) {
            if (key.startsWith("tutorial.icheme.equipment.")) {
                var rest = key.substring("tutorial.icheme.equipment.".length());
                int dot = rest.lastIndexOf('.');
                var kind = dot < 0 ? null : EquipmentInfo.kindOf(EquipmentInfo.PREFIX + rest.substring(0, dot));
                if (kind == null || !EquipmentInfo.pageKeys(kind).contains(key)) faults.add(key);
            } else if (key.startsWith("tutorial.icheme.item.")) {
                var rest = key.substring("tutorial.icheme.item.".length());
                int dot = rest.lastIndexOf('.');
                var id = dot < 0 ? "" : rest.substring(0, dot);
                if (!items.contains(id) || EquipmentInfo.kindOf(id) != null || !EquipmentInfo.itemPageKeys(id).contains(key)) faults.add(key);
            } else if (key.startsWith("tutorial.icheme.equipment_info.")
                    && !Set.of(EquipmentInfo.REFERENCE_KEY, EquipmentInfo.SHAPE_ONLY_KEY, EquipmentInfo.SIMULATOR_SHAPE_KEY).contains(key)) faults.add(key);
        }
        assertEquals(List.of(), faults);
    }

    @Test
    void theShapeOnlyListsAreTheCatalogsDrawingOnlyKinds() {
        var lang = TutorialText.english();
        for (var kind : EquipmentShape.Kind.values()) {
            boolean drawn = FlowsheetCatalog.of(kind).coverage() == FlowsheetCatalog.Coverage.DRAWING_ONLY;
            boolean marked = EquipmentInfo.SHAPE_ONLY.contains(kind) || EquipmentInfo.SIMULATOR_SHAPE.contains(kind);
            assertEquals(drawn, marked, kind + ": the catalog's flag");
            assertFalse(EquipmentInfo.SHAPE_ONLY.contains(kind) && EquipmentInfo.SIMULATOR_SHAPE.contains(kind), kind + ": one fourth line");
        }
        // The world models of the simulator-drawn kinds: the cooling towers are reservoirs, the drivers sit on shaft trains,
        // the converters (0.28.0) are a vessel with two hidden ports.
        for (var kind : EquipmentInfo.SIMULATOR_SHAPE)
            assertTrue(kind.coolingTower() || kind == EquipmentShape.Kind.MOTOR || kind == EquipmentShape.Kind.GENERATOR || kind == EquipmentShape.Kind.GEARBOX
                    || kind == EquipmentShape.Kind.FLUID_CONVERTER || kind == EquipmentShape.Kind.GAS_CONVERTER, kind.name());
        assertNotNull(lang.get(EquipmentInfo.SHAPE_ONLY_KEY));
        assertNotNull(lang.get(EquipmentInfo.SIMULATOR_SHAPE_KEY));
    }

    /**
     * P5: using a reference item opens its kind's page in the Field Guide, and every kind has a reference shape for its icon
     * and film (a kind with none would show its page's header only, never crash the tool).
     */
    @Test
    void everyItemOpensItsKindsPage() throws IOException {
        var pages = com.wormzjl.icheme.science.tutorial.guide.PageFile.load();
        for (var kind : EquipmentShape.Kind.values()) {
            assertNotNull(pages.page(EquipmentInfo.itemId(kind)), kind + ": the item's page");
            assertNotNull(com.wormzjl.icheme.science.tutorial.equipment.ReferenceShapes.of(kind), kind + ": its reference shape");
        }
        var source = Files.readString(Path.of("src/main/java/com/wormzjl/icheme/world/item/EquipmentInfoItem.java"));
        assertTrue(source.contains("GuideClient.openPage(EquipmentInfo.itemId(kind))"), "the item's use opens its page");
    }
}
