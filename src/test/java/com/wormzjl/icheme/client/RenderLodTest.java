package com.wormzjl.icheme.client;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** The level-of-detail rule of batch 2026-11-09-lod-rendering (LOD_RENDERING_PLAN.md, sections 1 and 3). */
class RenderLodTest {
    private static final double M11_70 = 1 / Math.tan(Math.toRadians(35));

    @Test
    void theReferenceViewIsTheOneThePlanDistancesUse() {
        var view = RenderLod.View.of(M11_70, 1080, 1);
        assertEquals(RenderLod.REFERENCE, view.pixelsPerMetre(), 1);
        // A dial face of 0.34 m: 48 px at 5.5 m, 12 px at 22 m, 3 px at 87 m.
        assertEquals(48, view.pixels(.34, 5.46 * 5.46), .2);
        assertEquals(12, view.pixels(.34, 21.8 * 21.8), .1);
        assertEquals(3, view.pixels(.34, 87.4 * 87.4), .05);
        // Twice the lines, twice the pixels; a narrower field (zoom) gives more.
        assertEquals(2 * view.pixelsPerMetre(), RenderLod.View.of(M11_70, 2160, 1).pixelsPerMetre(), 1e-9);
        assertTrue(RenderLod.View.of(1 / Math.tan(Math.toRadians(15)), 1080, 1).pixelsPerMetre() > view.pixelsPerMetre());
    }

    @Test
    void theScaleDropsDetailNearerAndStaysInItsBounds() {
        double k = RenderLod.View.of(M11_70, 1080, 1).pixelsPerMetre();
        assertEquals(k / 2, RenderLod.View.of(M11_70, 1080, 2).pixelsPerMetre(), 1e-9);
        assertEquals(k / RenderLod.MOST_SCALE, RenderLod.View.of(M11_70, 1080, 100).pixelsPerMetre(), 1e-9);
        assertEquals(k / RenderLod.LEAST_SCALE, RenderLod.View.of(M11_70, 1080, 0).pixelsPerMetre(), 1e-9);
        assertEquals(k, RenderLod.View.of(M11_70, 1080, Double.NaN).pixelsPerMetre(), 1e-9);
        assertEquals(RenderLod.REFERENCE, RenderLod.View.of(M11_70, 0, 1).pixelsPerMetre(), "an empty window keeps the reference");
    }

    @Test
    void aTierLosesDetailAtItsThresholdAndGainsItOnlyPastTheHysteresis() {
        double[] dial = {48, 12, 3};
        assertEquals(0, RenderLod.tier(50, -1, dial));
        assertEquals(1, RenderLod.tier(20, -1, dial));
        assertEquals(2, RenderLod.tier(5, -1, dial));
        assertEquals(3, RenderLod.tier(1, -1, dial));
        assertEquals(1, RenderLod.tier(47.9, 0, dial), "loses detail at the threshold");
        assertEquals(1, RenderLod.tier(50, 1, dial), "inside the band: keeps its tier");
        assertEquals(0, RenderLod.tier(48 * RenderLod.HYSTERESIS, 1, dial), "past the band: gains detail");
        assertEquals(3, RenderLod.tier(3.2, 3, dial));
        assertEquals(2, RenderLod.tier(3.5, 3, dial));
        assertEquals(0, RenderLod.tier(1000, 3, dial), "a jump gains every tier at once");
        assertEquals(3, RenderLod.tier(.5, 0, dial), "and loses every tier at once");
    }

    @Test
    void tiersRememberEachObjectApart() {
        var tiers = new RenderLod.Tiers<String>(48, 12, 3);
        assertEquals(3, tiers.hidden());
        assertEquals(1, tiers.of("a", 47));
        assertEquals(0, tiers.of("b", 50));
        assertEquals(1, tiers.of("a", 50), "a stays in the band");
        assertEquals(0, tiers.of("b", 50));
        tiers.clear();
        assertEquals(0, tiers.of("a", 50), "a cleared memory starts fresh");
        assertThrows(IllegalArgumentException.class, () -> new RenderLod.Tiers<String>(3, 12));
    }

    @Test
    void aBoxIsMeasuredAtItsNearestPoint() {
        var view = new RenderLod.View(100);
        assertEquals(view.pixels(.025, 10 * 10), view.pixels(.025, 0, 5, 0, 10, 0, -3, 14, 9, 3), 1e-12, "beside a face");
        assertEquals(view.pixels(.025, 3 * 3 + 4 * 4), view.pixels(.025, -3, -4, 0, 0, 0, -1, 1, 1, 1), 1e-12, "past an edge");
        assertEquals(view.pixels(.025, 1e-4), view.pixels(.025, .5, .5, .5, 0, 0, 0, 1, 1, 1), 1e-12, "inside");
    }
}
