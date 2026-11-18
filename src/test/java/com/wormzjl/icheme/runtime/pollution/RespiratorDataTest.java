package com.wormzjl.icheme.runtime.pollution;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The respirator's data (batch 2026-11-18-respirator): English lines, item models and icons, the worn texture, the
 * vanilla-only recipes and the optional Curios slot files. A missing file only shows in game, so it is held here.
 */
class RespiratorDataTest {
    private static final Path ASSETS = Path.of("src/main/resources/assets/icheme");
    private static final Path DATA = Path.of("src/main/resources/data");

    private static JsonObject read(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), "missing " + file);
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    void linesAreInEnglish() throws IOException {
        var english = read(ASSETS.resolve("lang/en_us.json"));
        for (var key : List.of("item.icheme.respirator", "item.icheme.respirator_filter", "tooltip.icheme.respirator",
                "tooltip.icheme.respirator.fit", "tooltip.icheme.respirator.filter", "tooltip.icheme.respirator.spent",
                "tooltip.icheme.respirator.no_filter", "tooltip.icheme.respirator_filter", "tooltip.icheme.respirator_filter.replace",
                "pollution.icheme.meter.respirator", "pollution.icheme.meter.respirator.protected",
                "pollution.icheme.meter.respirator.spent", "pollution.icheme.meter.respirator.none")) {
            assertTrue(english.has(key), key);
        }
        assertTrue(english.get("tooltip.icheme.respirator.filter").getAsString().contains("%%"), "the percent sign is escaped");
        assertTrue(english.get("pollution.icheme.meter.respirator").getAsString().contains("%2$s"), "the segment holds the word");
        assertTrue(english.get("tooltip.icheme.respirator_filter.replace").getAsString().contains("lost"), "a replaced part-used filter is lost");
    }

    @Test
    void modelsIconsAndWornTexture() throws IOException {
        for (var id : List.of("respirator", "respirator_filter")) {
            var model = read(ASSETS.resolve("models/item/" + id + ".json"));
            assertEquals("minecraft:item/generated", model.get("parent").getAsString());
            assertEquals("icheme:item/" + id, model.getAsJsonObject("textures").get("layer0").getAsString());
            var image = javax.imageio.ImageIO.read(ASSETS.resolve("textures/item/" + id + ".png").toFile());
            assertEquals(16, image.getWidth(), id);
            assertEquals(16, image.getHeight(), id);
        }
        // The armour material icheme:respirator's outer layer (ModItems.RESPIRATOR_MATERIAL, RespiratorClient.TEXTURE).
        var worn = javax.imageio.ImageIO.read(ASSETS.resolve("textures/models/armor/respirator_layer_1.png").toFile());
        assertEquals(64, worn.getWidth());
        assertEquals(32, worn.getHeight());
    }

    private static List<String> ingredients(JsonObject recipe) {
        var out = new java.util.ArrayList<String>();
        recipe.getAsJsonObject("key").entrySet().forEach(e -> {
            var o = e.getValue().getAsJsonObject();
            out.add(o.has("item") ? o.get("item").getAsString() : "#" + o.get("tag").getAsString());
        });
        return out;
    }

    @Test
    void recipesAreVanillaOnly() throws IOException {
        var respirator = read(DATA.resolve("icheme/recipe/respirator.json"));
        assertEquals("icheme:respirator", respirator.getAsJsonObject("result").get("id").getAsString());
        assertEquals(new HashSet<>(List.of("minecraft:leather", "minecraft:glass_pane", "minecraft:iron_ingot", "minecraft:string")),
                new HashSet<>(ingredients(respirator)));
        var filter = read(DATA.resolve("icheme/recipe/respirator_filter.json"));
        assertEquals("icheme:respirator_filter", filter.getAsJsonObject("result").get("id").getAsString());
        assertEquals(new HashSet<>(List.of("minecraft:charcoal", "minecraft:paper", "#minecraft:wool")), new HashSet<>(ingredients(filter)));
    }

    @Test
    void curiosSlotFiles() throws IOException {
        var entities = read(DATA.resolve("icheme/curios/entities/respirator.json"));
        assertEquals("player", entities.getAsJsonArray("entities").get(0).getAsString());
        assertEquals("head", entities.getAsJsonArray("slots").get(0).getAsString());
        var tag = read(DATA.resolve("curios/tags/item/head.json"));
        assertFalse(tag.get("replace").getAsBoolean(), "Curios' own tag is extended, not replaced");
        assertEquals("icheme:respirator", tag.getAsJsonArray("values").get(0).getAsString());
    }

    @Test
    void curiosTypesStayInTheirPackage() throws IOException {
        // The mod starts without Curios: only compat/curios names its types.
        try (var files = Files.walk(Path.of("src/main/java"))) {
            var faults = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().replace('\\', '/').contains("/compat/curios/"))
                    .filter(p -> {
                        try {
                            return Files.readString(p, StandardCharsets.UTF_8).contains("top.theillusivec4");
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }).toList();
            assertEquals(List.of(), faults);
        }
    }
}
