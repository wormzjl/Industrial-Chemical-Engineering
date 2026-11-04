package com.wormzjl.icheme.science.equipment;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** N1 (batch 2026-11-02-process-simulator): what a device's name may be, and the titles that put it first. */
class DeviceNameTest {
    /** Trimmed, no formatting codes, no control or format characters, at most 32 characters; empty and null are no name. */
    @Test
    void aNameIsCleanedToTheRule() {
        assertEquals("E-101", DeviceName.clean("  E-101 \t"));
        assertEquals("E-101", DeviceName.clean("§cE-1§l01"));
        assertEquals("E-101", DeviceName.clean("E-1\u0007\n01"));
        assertEquals("E-101", DeviceName.clean("E-1​01"));
        assertEquals("", DeviceName.clean("   "));
        assertEquals("", DeviceName.clean(null));
        assertEquals("", DeviceName.clean("§"));
        assertEquals("回流罐 D-102", DeviceName.clean("回流罐 D-102"));
        String forty = "A".repeat(40);
        assertEquals(DeviceName.MAX, DeviceName.clean(forty).length());
        // Cut at characters, not at UTF-16 units: a name of emoji keeps whole ones.
        String wide = "🔥".repeat(40);
        var cut = DeviceName.clean(wide);
        assertEquals(DeviceName.MAX, cut.codePointCount(0, cut.length()));
        // Cut, then trimmed again: no trailing space.
        assertEquals("B".repeat(31), DeviceName.clean("B".repeat(31) + " " + "C".repeat(10)));
        assertTrue(DeviceName.valid("T-101"));
        assertFalse(DeviceName.valid(" T-101"));
        assertFalse(DeviceName.valid(null));
    }

    /** The name first through the line; the kind alone for no name. */
    @Test
    void aTitlePutsTheNameFirst() {
        assertEquals("E-101 · Heat exchanger: tube side", DeviceName.titled("E-101", "Heat exchanger: tube side", (n, k) -> n + " · " + k));
        assertEquals("Pump", DeviceName.titled("", "Pump", (n, k) -> n + " · " + k));
        assertEquals("Pump", DeviceName.titled(null, "Pump", (n, k) -> n + " · " + k));
    }
}
