package com.wormzjl.icheme.client.gui.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The title strip's name entry (N1; finding C7, batch 2026-11-13-codebase-review) without its box, which needs the game's
 * font: the draft is taken once, follows the device's name while untouched, and Apply has a name to send only where the
 * typed name differs and the screen's renamer holds, asked only once a draft exists.
 */
class NameEntryTest {
    @Test
    void theDraftIsTakenOnceAndFollowsTheName() {
        var name = new String[]{"Drum 1"};
        var entry = new NameEntry(() -> name[0]);
        assertNull(entry.draft());
        assertNull(entry.box(), "no box before a rebuild makes one");
        entry.follow();
        assertNull(entry.draft(), "nothing followed before the draft is taken");
        entry.seed();
        assertEquals("Drum 1", entry.draft());
        assertFalse(entry.renames(), "the draft is the name");
        name[0] = "Drum 2";
        assertTrue(entry.renames(), "the name moved on, the draft not yet: a difference until it follows");
        entry.follow();
        assertEquals("Drum 2", entry.draft(), "an untouched draft follows another player's rename");
        assertFalse(entry.renames());
        entry.seed();
        assertEquals("Drum 2", entry.draft(), "a second seed keeps the draft");
        entry.forget();
        assertNull(entry.box());
    }

    @Test
    void theRenamerIsAskedOnlyOnceADraftExists() {
        var name = new String[]{"Column"};
        var asked = new int[1];
        var allowed = new boolean[]{false};
        var entry = new NameEntry(() -> name[0], () -> {
            asked[0]++;
            return allowed[0];
        });
        assertFalse(entry.renames());
        assertEquals(0, asked[0], "no draft: the renamer is not asked");
        entry.seed();
        name[0] = "Column B";
        assertFalse(entry.renames(), "a difference, but no renamer");
        assertEquals(1, asked[0]);
        allowed[0] = true;
        assertTrue(entry.renames());
    }
}
