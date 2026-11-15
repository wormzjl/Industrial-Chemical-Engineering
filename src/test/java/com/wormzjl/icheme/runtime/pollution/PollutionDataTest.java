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
 * immune entity tag and the death messages; and the pollution meter's (WP8) lines, model, texture and sounds. A missing or
 * misspelt file only shows in game (no damage, a raw key, no sound), so it is held here. The meter has no recipe: the
 * gauges it sits beside have none.
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

    private static final Path ASSETS = Path.of("src/main/resources/assets/icheme");

    /** The pollution meter (WP8): its English lines, every word and channel key the meter builds, and the subtitle. */
    @Test
    void meterLinesAreInEnglish() throws IOException {
        var english = read(ASSETS.resolve("lang/en_us.json"));
        var keys = new java.util.ArrayList<String>(List.of("item.icheme.pollution_meter", "tooltip.icheme.pollution_meter",
                "tutorial.icheme.item.pollution_meter.serves", "pollution.icheme.meter.segment", "pollution.icheme.meter.off",
                "pollution.icheme.meter.header", "pollution.icheme.meter.line", "pollution.icheme.meter.air",
                "pollution.icheme.air.open", "pollution.icheme.air.enclosed", "pollution.icheme.air.unread", "subtitles.icheme.meter_tick"));
        for (var word : com.wormzjl.icheme.science.pollution.Reading.Word.values()) {
            keys.add("pollution.icheme.word." + word.name().toLowerCase(java.util.Locale.ROOT));
        }
        for (var channel : com.wormzjl.icheme.science.pollution.Channel.values()) {
            String name = channel.name().toLowerCase(java.util.Locale.ROOT);
            keys.add("pollution.icheme.short." + name);
            keys.add("pollution.icheme.name." + name);
        }
        for (var key : keys) {
            assertTrue(english.has(key), key);
        }
        assertTrue(english.get("pollution.icheme.meter.segment").getAsString().contains("%3$s"), "the segment holds the word");
        assertTrue(english.get("pollution.icheme.meter.line").getAsString().contains("%3$s"), "the line holds the word");
        assertTrue(english.get("pollution.icheme.meter.air").getAsString().contains("%%"), "the air share's percent sign is escaped");
    }

    /** The meter's item model, its texture (16 x 16) and its three click sounds with their ogg files. */
    @Test
    void meterModelTextureAndSoundsExist() throws IOException {
        var model = read(ASSETS.resolve("models/item/pollution_meter.json"));
        assertEquals("minecraft:item/generated", model.get("parent").getAsString());
        assertEquals("icheme:item/pollution_meter", model.getAsJsonObject("textures").get("layer0").getAsString());
        var texture = ASSETS.resolve("textures/item/pollution_meter.png");
        assertTrue(Files.isRegularFile(texture), "missing " + texture);
        var image = javax.imageio.ImageIO.read(texture.toFile());
        assertEquals(16, image.getWidth());
        assertEquals(16, image.getHeight());
        var sounds = read(ASSETS.resolve("sounds.json"));
        for (var name : List.of("meter_tick_slow", "meter_tick_medium", "meter_tick_fast")) {
            assertTrue(sounds.has(name), "sounds.json has " + name);
            var entry = sounds.getAsJsonObject(name);
            assertEquals("subtitles.icheme.meter_tick", entry.get("subtitle").getAsString(), name + " subtitle");
            var sound = entry.getAsJsonArray("sounds").get(0).getAsJsonObject();
            assertEquals("icheme:" + name, sound.get("name").getAsString(), name + " file");
            assertFalse(sound.get("stream").getAsBoolean(), name + " is not streamed");
            var ogg = ASSETS.resolve("sounds/" + name + ".ogg");
            assertTrue(Files.isRegularFile(ogg) && Files.size(ogg) > 1000, "missing or empty " + ogg);
        }
    }

    /**
     * The fixed pollution monitor (WP10): its blockstate (every facing by every lamp), its models and their textures, the
     * item model, the loot table and recipe, its English lines (the chat line, the DCS alarm words) and its horn.
     */
    @Test
    void monitorBlockModelsSoundAndLinesExist() throws IOException {
        var variants = read(ASSETS.resolve("blockstates/pollution_monitor.json")).getAsJsonObject("variants");
        var models = new HashSet<String>();
        for (var facing : List.of("north", "east", "south", "west")) {
            for (var lamp : MonitorSignals.Lamp.values()) {
                String key = "facing=" + facing + ",lamp=" + lamp.getSerializedName();
                assertTrue(variants.has(key), "blockstate " + key);
                models.add(variants.getAsJsonObject(key).get("model").getAsString());
            }
        }
        assertEquals(16, variants.size(), "no other variant");
        assertEquals(4, models.size(), "one model a lamp colour");
        for (var model : models) {
            var json = read(ASSETS.resolve("models/" + model.substring("icheme:".length()) + ".json"));
            var textures = new java.util.ArrayList<String>();
            if (json.has("textures")) {
                json.getAsJsonObject("textures").entrySet().forEach(e -> textures.add(e.getValue().getAsString()));
            }
            for (var texture : textures) {
                var png = ASSETS.resolve("textures/" + texture.substring("icheme:".length()) + ".png");
                assertTrue(Files.isRegularFile(png), model + ": missing " + png);
            }
        }
        assertEquals("icheme:block/pollution_monitor_green", read(ASSETS.resolve("models/item/pollution_monitor.json")).get("parent").getAsString());
        var loot = read(DATA.resolve("icheme/loot_table/blocks/pollution_monitor.json"));
        assertEquals("icheme:pollution_monitor", loot.getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("entries").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("icheme:pollution_monitor", read(DATA.resolve("icheme/recipe/pollution_monitor.json")).getAsJsonObject("result").get("id").getAsString());

        var english = read(ASSETS.resolve("lang/en_us.json"));
        var keys = new java.util.ArrayList<String>(List.of("block.icheme.pollution_monitor", "tutorial.icheme.item.pollution_monitor.serves",
                "pollution.icheme.monitor.line", "pollution.icheme.monitor.segment", "pollution.icheme.monitor.off", "subtitles.icheme.gas_alarm"));
        for (var channel : com.wormzjl.icheme.science.pollution.Channel.values()) {
            keys.add("pollution.icheme.monitor.channel." + channel.name().toLowerCase(java.util.Locale.ROOT));
        }
        for (var level : com.wormzjl.icheme.science.pollution.Exposure.Level.values()) {
            keys.add("pollution.icheme.monitor.level." + level.name().toLowerCase(java.util.Locale.ROOT));
        }
        for (var alarm : MonitorSignals.Alarm.values()) {
            if (alarm != MonitorSignals.Alarm.CLEAR) {
                keys.add("gui.icheme.control.alarm." + alarm.name().toLowerCase(java.util.Locale.ROOT));
            }
        }
        for (var key : keys) {
            assertTrue(english.has(key), key);
        }
        assertTrue(english.get("pollution.icheme.monitor.line").getAsString().contains("%5$s"), "the line holds the four kinds");

        var entry = read(ASSETS.resolve("sounds.json")).getAsJsonObject("gas_alarm");
        assertNotNull(entry, "sounds.json has gas_alarm");
        assertEquals("subtitles.icheme.gas_alarm", entry.get("subtitle").getAsString());
        var sound = entry.getAsJsonArray("sounds").get(0).getAsJsonObject();
        assertEquals("icheme:gas_alarm", sound.get("name").getAsString());
        assertFalse(sound.get("stream").getAsBoolean());
        var ogg = ASSETS.resolve("sounds/gas_alarm.ogg");
        assertTrue(Files.isRegularFile(ogg) && Files.size(ogg) > 1000, "missing or empty " + ogg);
    }

    /** Ignition (WP6a): the two source tags exist and name fire and the torch; no lantern, redstone torch or magma (owner D2). */
    @Test
    void ignitionTagsNameFireAndTheTorch() throws IOException {
        var blocks = Ignition.SOURCE_BLOCKS.location();
        var blockTag = read(DATA.resolve(blocks.getNamespace() + "/tags/block/" + blocks.getPath() + ".json"));
        assertFalse(blockTag.has("replace") && blockTag.get("replace").getAsBoolean());
        var named = new HashSet<String>();
        blockTag.getAsJsonArray("values").forEach(v -> named.add(v.isJsonObject() ? v.getAsJsonObject().get("id").getAsString() : v.getAsString()));
        assertTrue(named.containsAll(Set.of("minecraft:fire", "minecraft:soul_fire", "minecraft:lava", "minecraft:torch", "minecraft:wall_torch",
                "minecraft:campfire", "minecraft:furnace", "#minecraft:candles", "create:blaze_burner")), named.toString());
        for (var not : List.of("minecraft:lantern", "minecraft:redstone_torch", "minecraft:magma_block")) {
            assertFalse(named.contains(not), not + " is no ignition source");
        }
        blockTag.getAsJsonArray("values").forEach(v -> {
            if (v.isJsonObject()) {
                assertFalse(v.getAsJsonObject().get("required").getAsBoolean(), "an optional mod's block is not required: " + v);
            }
        });
        var entities = Ignition.SOURCE_ENTITIES.location();
        var entityTag = read(DATA.resolve(entities.getNamespace() + "/tags/entity_type/" + entities.getPath() + ".json"));
        assertTrue(values(entityTag).containsAll(Set.of("minecraft:blaze", "minecraft:small_fireball", "minecraft:fireball",
                "minecraft:lightning_bolt", "minecraft:tnt", "minecraft:firework_rocket")));
    }

    /** The burst pipe's three sounds (WP6a) with their files and one subtitle. */
    @Test
    void burstSoundsExist() throws IOException {
        var english = read(ASSETS.resolve("lang/en_us.json"));
        assertTrue(english.has("subtitles.icheme.pipe_burst"), "subtitles.icheme.pipe_burst");
        var sounds = read(ASSETS.resolve("sounds.json"));
        for (var name : List.of("pipe_burst_gas", "pipe_burst_liquid", "pipe_burst_empty")) {
            assertTrue(sounds.has(name), "sounds.json has " + name);
            var entry = sounds.getAsJsonObject(name);
            assertEquals("subtitles.icheme.pipe_burst", entry.get("subtitle").getAsString(), name + " subtitle");
            var sound = entry.getAsJsonArray("sounds").get(0).getAsJsonObject();
            assertEquals("icheme:" + name, sound.get("name").getAsString(), name + " file");
            assertFalse(sound.get("stream").getAsBoolean(), name + " is not streamed");
            var ogg = ASSETS.resolve("sounds/" + name + ".ogg");
            assertTrue(Files.isRegularFile(ogg) && Files.size(ogg) > 1000, "missing or empty " + ogg);
        }
    }

    /** A breach (WP6b): the chat lines at the breach, named or by position, and the repair kit's answer. */
    @Test
    void breachLinesAreInEnglish() throws IOException {
        var english = read(ASSETS.resolve("lang/en_us.json"));
        for (var key : List.of("chat.icheme.breach", "chat.icheme.breach.unnamed", "chat.icheme.repair_kit.breach_repaired")) {
            assertTrue(english.has(key), key);
        }
        assertTrue(english.get("chat.icheme.breach").getAsString().contains("%s"), "the line names the equipment");
        assertTrue(english.get("chat.icheme.breach.unnamed").getAsString().contains("%3$s"), "the line gives the position");
        // Every repair outcome has its line (RepairKitItem.reply).
        for (var outcome : com.wormzjl.icheme.runtime.fluid.MachineHealth.Outcome.values()) {
            String key = "chat.icheme.repair_kit." + outcome.name().toLowerCase(java.util.Locale.ROOT);
            assertTrue(english.has(key), key);
        }
    }
}
