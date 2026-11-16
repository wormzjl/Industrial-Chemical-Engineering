package com.wormzjl.icheme.client;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Heading;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** The casings' running loops ({@link MoverSounds#casingLoop}): the plunger pump's own (batch 2026-11-16-supercritical-followups, P4). */
class MoverSoundsTest {
    private static final Path ASSETS = Path.of("src/main/resources/assets/icheme");

    @Test
    void eachCasingPicksItsLoop() {
        assertEquals("plunger_pump_running", MoverSounds.casingLoop(Kind.PLUNGER_PUMP, false));
        assertEquals("plunger_pump_running", MoverSounds.casingLoop(Kind.PLUNGER_PUMP, true), "on a gas it is still a plunger pump");
        assertEquals("pump_running", MoverSounds.casingLoop(Kind.PUMP, false));
        assertEquals("compressor_running", MoverSounds.casingLoop(Kind.COMPRESSOR, true));
        assertEquals("pump_running", MoverSounds.casingLoop(Kind.LIQUID_RING_PUMP, true), "a liquid-ring pump sounds as a pump");
    }

    @Test
    void thePlungerLoopIsRegisteredWithAnExistingSubtitle() throws Exception {
        var sounds = JsonParser.parseString(Files.readString(ASSETS.resolve("sounds.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        var entry = sounds.getAsJsonObject("plunger_pump_running");
        assertNotNull(entry, "sounds.json entry");
        var sound = entry.getAsJsonArray("sounds").get(0).getAsJsonObject();
        assertEquals("icheme:plunger_pump_running", sound.get("name").getAsString());
        assertFalse(sound.get("stream").getAsBoolean());
        assertTrue(Files.isRegularFile(ASSETS.resolve("sounds/plunger_pump_running.ogg")), "the loop's ogg file");
        var english = JsonParser.parseString(Files.readString(ASSETS.resolve("lang/en_us.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        assertTrue(english.has(entry.get("subtitle").getAsString()), "the subtitle key exists");
    }

    @Test
    void theLoopPlaysTrueAtTheRatedSpeed() {
        // The loop is authored at 300 rpm, every model's rated speed; the plunger's pitch is its speed share, so the rated
        // point plays the loop as authored and the clamp (0.5 to 2) bounds only the share.
        var shape = EquipmentShape.unit(Kind.PLUNGER_PUMP, 2, 1, Heading.NORTH, 0);
        assertEquals(300, UnitDesign.designSpeed(shape), 1e-9);
        assertEquals(1f, UnitDesign.soundPitch(shape, 1), 1e-6f);
        assertEquals(.75f, UnitDesign.soundPitch(shape, .75), 1e-6f);
        assertEquals(.5f, UnitDesign.soundPitch(shape, .2), 1e-6f);
    }
}
