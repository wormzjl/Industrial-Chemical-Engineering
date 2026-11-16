package com.wormzjl.icheme.runtime.pollution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Who sees the pollution map overlay (WP9, {@code pollution.mapOverlay}). The answers themselves are {@code MapColumnsTest}'s. */
class PollutionMapsTest {
    @Test
    void theConfigTextReadsAsAnAccess() {
        assertEquals(PollutionMaps.Access.ALL, PollutionMaps.Access.parse("ALL"));
        assertEquals(PollutionMaps.Access.ALL, PollutionMaps.Access.parse("all"));
        assertEquals(PollutionMaps.Access.OFF, PollutionMaps.Access.parse(" off "));
        assertEquals(PollutionMaps.Access.METER, PollutionMaps.Access.parse("METER"));
        assertEquals(PollutionMaps.Access.METER, PollutionMaps.Access.parse("everyone"), "anything else is the default");
        assertEquals(PollutionMaps.Access.METER, PollutionMaps.Access.parse(null));
    }

    @Test
    void allLetsEveryoneSeeItAndOffNoOne() {
        assertTrue(PollutionMaps.allowed(PollutionMaps.Access.ALL, null));
        assertFalse(PollutionMaps.allowed(PollutionMaps.Access.OFF, null));
        assertFalse(PollutionMaps.allowed(PollutionMaps.Access.METER, null), "no player carries no meter");
    }
}
