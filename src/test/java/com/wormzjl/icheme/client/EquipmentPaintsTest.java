package com.wormzjl.icheme.client;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.equipment.Paint;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

class EquipmentPaintsTest {
    @Test
    void preferencesRoundTripByAccountWithoutReplacingOtherPlayers() {
        var first = UUID.fromString("10000000-0000-0000-0000-000000000001");
        var second = UUID.fromString("10000000-0000-0000-0000-000000000002");
        var blue = new Paint(0x2271B3, 0x383E42);
        var green = new Paint(0x587246, 0xF7B500);
        var entries = EquipmentPaints.remember(List.of(), first, blue);
        entries = EquipmentPaints.remember(entries, second, green);
        // The list is the persisted representation, not an in-memory cache.
        entries = List.copyOf(entries);
        assertEquals(blue, EquipmentPaints.find(entries, first, Paint.DEFAULT));
        assertEquals(green, EquipmentPaints.find(entries, second, Paint.DEFAULT));
        entries = EquipmentPaints.remember(entries, first, Paint.DEFAULT);
        assertEquals(2, entries.size());
        assertEquals(Paint.DEFAULT, EquipmentPaints.find(entries, first, blue));
        assertEquals(green, EquipmentPaints.find(entries, second, Paint.DEFAULT));
    }

    @Test
    void missingOrInvalidPreferencesKeepTheDraftPaint() {
        var player = new UUID(1, 2);
        var draft = new Paint(0xFFFFFF, 0x123456);
        assertEquals(
                draft,
                EquipmentPaints.find(List.of("bad", player + ":#BAD:#FFFFFF"), player, draft));
        assertFalse(EquipmentPaints.valid(player + ":#GGGGGG:#FFFFFF"));
        assertFalse(EquipmentPaints.valid("1-2-3-4-5:#FFFFFF:#FFFFFF"));
        assertTrue(EquipmentPaints.valid(player + ":#ffffff:#123456"));
    }
}
