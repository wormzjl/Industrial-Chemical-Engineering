package com.wormzjl.icheme.science.equipment;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Default plant tags (batch 2026-11-18-default-equipment-names): a prefix by kind, the lowest free number from 101. */
class PlantTagTest {
    /** Every kind the constructor builds has a prefix of capital letters. */
    @Test
    void everyKindHasAPrefix() {
        for (var kind : EquipmentShape.Kind.values()) {
            var prefix = PlantTag.prefix(kind);
            assertTrue(prefix.matches("[A-Z]{1,2}"), kind + ": " + prefix);
        }
        assertEquals("P", PlantTag.prefix(EquipmentShape.Kind.PUMP));
        assertEquals("C", PlantTag.prefix(EquipmentShape.Kind.COLUMN));
        assertEquals("V", PlantTag.prefix(EquipmentShape.Kind.DRUM));
        assertEquals("E", PlantTag.prefix(EquipmentShape.Kind.EXCHANGER));
        assertEquals("TK", PlantTag.prefix(EquipmentShape.Kind.TANK));
    }

    /** From nothing taken the first is 101; taken tags are skipped and a gap is filled. */
    @Test
    void theNextNumberIsTheLowestFreeFrom101() {
        assertEquals(101, PlantTag.next("P", Set.of()));
        assertEquals(102, PlantTag.next("P", Set.of("P-101")));
        assertEquals(102, PlantTag.next("P", Set.of("P-101", "P-103")));
        assertEquals(104, PlantTag.next("P", Set.of("P-101", "P-102", "P-103")));
        assertEquals(101, PlantTag.next("P", Set.of("P-100", "P-99", "P-1")));
        // Three pumps built in a row.
        var taken = new java.util.HashSet<String>();
        for (var wanted : List.of("P-101", "P-102", "P-103")) {
            var tag = PlantTag.tag(EquipmentShape.Kind.PUMP, taken);
            assertEquals(wanted, tag);
            taken.add(tag);
        }
    }

    /** Only an exact name of the same prefix counts: other prefixes, longer numbers, case and words after do not. */
    @Test
    void nearMissesDoNotCount() {
        assertEquals(101, PlantTag.next("P", Set.of("P-1010", "PX-101", "p-101", "P-101 feed", "P-0101", "P-101a", "TP-101", "P-", "P101")));
        assertEquals(101, PlantTag.next("TK", Set.of("K-101", "T-101")));
        assertEquals(101, PlantTag.next("K", Set.of("TK-101")));
        // A huge number neither fills the gap nor costs memory.
        assertEquals(101, PlantTag.next("P", Set.of("P-2147483647", "P-99999999999")));
    }

    /** A tag is a name as the player's are: unchanged by the cleaning (the registry's record: WorldTopologyLedgerTest). */
    @Test
    void aTagIsAValidName() {
        for (var kind : EquipmentShape.Kind.values()) {
            var tag = PlantTag.tag(kind, Set.of());
            assertEquals(tag, DeviceName.clean(tag));
            assertTrue(DeviceName.valid(tag));
        }
        // The longest tag a plausible world reaches is far inside the name's length.
        assertTrue(("TK-" + Integer.MAX_VALUE).length() <= DeviceName.MAX);
    }
}
