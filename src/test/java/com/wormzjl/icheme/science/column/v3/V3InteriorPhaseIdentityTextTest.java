package com.wormzjl.icheme.science.column.v3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** A tray whose liquid and vapour are one fluid names its cause (batch 2026-11-15-supercritical-fluids, D2). */
class V3InteriorPhaseIdentityTextTest {
    @Test
    void theTextNamesTheCriticalPointAndWhatToChange() {
        String text = V3AcceptanceAuditor.interiorPhaseIdentityText(4,
                "one phase, no two-phase split above the mixture's pseudo-critical temperature: liquid by reduced density 1.197", 1.0e-9, 2.0e-9);
        assertEquals("tray 5 has no liquid and vapour to separate: it is at or above its mixture's critical point"
                + " (one phase, no two-phase split above the mixture's pseudo-critical temperature: liquid by reduced density 1.197);"
                + " lower the pressure or change the cut; x-y=1.0E-9, Z separation=2.0E-9", text);
        // The audit's detail is bounded at 512 characters.
        assertTrue(V3AcceptanceAuditor.interiorPhaseIdentityText(Integer.MAX_VALUE - 1, "x".repeat(256),
                -Double.MAX_VALUE, -Double.MAX_VALUE).length() <= 512);
    }
}
