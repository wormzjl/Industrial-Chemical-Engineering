package com.wormzjl.icheme.client.gui.common.kit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * When an entry's edit ends (the owner, 2026-11-04: "clicking anywhere else doesn't work"): a press anywhere outside the
 * focused entry ends it; a press on it keeps it. The entry's box counts as the game's {@code isMouseOver} does: its left
 * and top edges in, its right and bottom edges out. The screens apply it through {@code SkinField.clickOutside}, which only
 * the game covers.
 */
class EditFocusTest {
    private static final Rect ENTRY = new Rect(100, 50, 46, 14);

    @Test
    void aPressOnTheEntryKeepsTheEdit() {
        assertFalse(EditFocus.ends(ENTRY, 100, 50), "its top left corner");
        assertFalse(EditFocus.ends(ENTRY, 123.5, 57), "its middle");
        assertFalse(EditFocus.ends(ENTRY, 145.9, 63.9), "just inside its bottom right");
    }

    @Test
    void aPressAnywhereElseEndsTheEdit() {
        assertTrue(EditFocus.ends(ENTRY, 146, 57), "its right edge is outside");
        assertTrue(EditFocus.ends(ENTRY, 123, 64), "its bottom edge is outside");
        assertTrue(EditFocus.ends(ENTRY, 99.9, 57), "left of it");
        assertTrue(EditFocus.ends(ENTRY, 123, 49), "above it");
        assertTrue(EditFocus.ends(ENTRY, 400, 300), "the door, the glass or the sheet far away");
    }

    @Test
    void withNoEntryFocusedNothingEnds() {
        assertFalse(EditFocus.ends(null, 123, 57));
    }
}
