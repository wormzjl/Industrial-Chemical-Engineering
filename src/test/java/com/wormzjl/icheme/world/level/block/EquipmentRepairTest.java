package com.wormzjl.icheme.world.level.block;

import com.google.gson.JsonParser;
import com.wormzjl.icheme.runtime.fluid.Integrity;
import com.wormzjl.icheme.science.integrity.WallState;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The constructor's Repair answers and the removal group's structure words (batch 2026-11-16-fouling-corrosion, WP4): every
 * outcome a repair can answer has its English line, the refusals of the shared check too, and the words read most severe
 * first.
 */
class EquipmentRepairTest {
    private static com.google.gson.JsonObject english() throws Exception {
        try (var in = EquipmentRepairTest.class.getResourceAsStream("/assets/icheme/lang/en_us.json")) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    @Test
    void everyAnswerHasItsLine() throws Exception {
        var lines = english();
        assertEquals("message.icheme.equipment.repair.not_idle", EquipmentRepair.key(Integrity.Outcome.NOT_IDLE));
        assertEquals("message.icheme.equipment.repair.repaired", EquipmentRepair.key(Integrity.Outcome.CLEANED), "a repair never cleans; it reads as repaired");
        for (var outcome : Integrity.Outcome.values()) assertTrue(lines.has(EquipmentRepair.key(outcome)), outcome.name());
        for (var refusal : List.of("not_editable", "changed", "unloaded", "protected"))
            assertTrue(lines.has("message.icheme.equipment.repair." + refusal), refusal);
        assertEquals("Stop the flow through it first.", lines.get(EquipmentRepair.key(Integrity.Outcome.NOT_IDLE)).getAsString());
        assertEquals("Drain and depressure its vessels first.", lines.get(EquipmentRepair.key(Integrity.Outcome.NOT_EMPTY)).getAsString());
        assertEquals("Wrecked by an explosion: deconstruct and rebuild.", lines.get(EquipmentRepair.key(Integrity.Outcome.WRECKED)).getAsString());
    }

    @Test
    void theWordsReadMostSevereFirst() {
        assertEquals("", EquipmentRepair.words(false, false, false, WallState.Stage.SOUND));
        assertEquals("THIN WALL", EquipmentRepair.words(false, false, false, WallState.Stage.THIN));
        assertEquals("WEEPING", EquipmentRepair.words(false, false, false, WallState.Stage.WEEPING));
        assertEquals("FAILED · WEEPING", EquipmentRepair.words(false, false, true, WallState.Stage.WEEPING));
        assertEquals("LEAKING · FAILED", EquipmentRepair.words(false, true, true, WallState.Stage.WEEPING), "a leak says more than a weep");
        assertEquals("WRECKED", EquipmentRepair.words(true, true, true, WallState.Stage.THIN), "wrecked says it all");
    }
}
