package com.wormzjl.icheme.runtime.pollution;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The data the world pass of pollution (WP5) reads: its three damage types, the armour-bypass tag that lists them, the
 * immune entity tag and the death messages. A missing or misspelt file only shows in game (no damage, a raw key), so it is
 * held here.
 */
class PollutionDataTest {
    private static final Path DATA = Path.of("src/main/resources/data");

    private static JsonObject read(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), "missing " + file);
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static Set<String> values(JsonObject tag) {
        var out = new HashSet<String>();
        JsonArray values = tag.getAsJsonArray("values");
        values.forEach(v -> out.add(v.getAsString()));
        return out;
    }

    @Test
    void damageTypesExistAndBypassArmour() throws IOException {
        var keys = List.of(PollutionEffects.TOXIC_GAS, PollutionEffects.CORROSIVE_GAS, PollutionEffects.PARTICULATE);
        var bypass = read(DATA.resolve("minecraft/tags/damage_type/bypasses_armor.json"));
        assertFalse(bypass.has("replace") && bypass.get("replace").getAsBoolean(), "the vanilla tag is extended, not replaced");
        var listed = values(bypass);
        for (var key : keys) {
            var id = key.location();
            assertEquals("icheme", id.getNamespace());
            var type = read(DATA.resolve(id.getNamespace() + "/damage_type/" + id.getPath() + ".json"));
            assertEquals("icheme." + id.getPath(), type.get("message_id").getAsString(), id + " message id");
            assertEquals("never", type.get("scaling").getAsString(), id + " scaling");
            assertEquals(0.0, type.get("exhaustion").getAsDouble(), id + " exhaustion");
            assertTrue(listed.contains(id.toString()), id + " bypasses armour");
        }
    }

    @Test
    void immuneTagHoldsTheUndeadAndTheConstructs() throws IOException {
        var id = PollutionEffects.IMMUNE.location();
        var tag = read(DATA.resolve(id.getNamespace() + "/tags/entity_type/" + id.getPath() + ".json"));
        assertTrue(values(tag).containsAll(Set.of("#minecraft:undead", "minecraft:iron_golem", "minecraft:snow_golem",
                "minecraft:wither", "minecraft:ender_dragon", "minecraft:armor_stand", "minecraft:shulker")));
    }

    @Test
    void deathMessagesAreInEnglish() throws IOException {
        var english = JsonParser.parseString(Files.readString(Path.of("src/main/resources/assets/icheme/lang/en_us.json"),
                StandardCharsets.UTF_8)).getAsJsonObject();
        for (var key : List.of(PollutionEffects.TOXIC_GAS, PollutionEffects.CORROSIVE_GAS, PollutionEffects.PARTICULATE)) {
            String base = "death.attack.icheme." + key.location().getPath();
            assertTrue(english.has(base), base);
            assertTrue(english.get(base).getAsString().contains("%1$s"), base + " names the victim");
            assertTrue(english.has(base + ".player"), base + ".player");
            assertTrue(english.get(base + ".player").getAsString().contains("%2$s"), base + ".player names the attacker");
        }
    }
}
