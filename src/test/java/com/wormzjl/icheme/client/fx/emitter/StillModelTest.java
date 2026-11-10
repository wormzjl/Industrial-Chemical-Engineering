package com.wormzjl.icheme.client.fx.emitter;

import com.wormzjl.icheme.client.fx.FxColour;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The fuelled still's fire at its door (CUSTOM_SHADER_SYSTEM_PLAN.md section 4.5, W6). */
class StillModelTest {
    /** A door 0.4 m wide and 0.3 m high facing -z, its burner on the -x side. */
    private static final double[] BOX = {.3, .2, -.035, .7, .5, -.015};
    private static final StillModel.Door DOOR = StillModel.door(new double[]{.5, .35, -.025}, new double[]{.5, .23, .5},
            new double[]{.07, .5, .5}, BOX);

    @Test
    void doorFacesAwayFromTheFireAndTheJetRunsFromTheBurner() {
        assertEquals(0, DOOR.outX(), 1e-12);
        assertEquals(-1, DOOR.outZ(), 1e-12);
        assertEquals(.4, DOOR.width(), 1e-12);
        assertEquals(.3, DOOR.height(), 1e-12);
        assertEquals(1, DOOR.jetX(), 1e-12);
        assertEquals(0, DOOR.jetZ(), 1e-12);
    }

    @Test
    void solidFireStaysInTheDoorAndGrowsWithTheFire() {
        assertEquals(10, StillModel.quads(true, .5));
        assertEquals(0, StillModel.quads(true, 0));
        var sink = new RecordingSink();
        StillModel.write(sink, DOOR, true, .2, 1);
        assertEquals(StillModel.quads(true, 1), sink.quads());
        for (var r : sink.ribbons) for (var p : new com.wormzjl.icheme.client.fx.FxSink.Point[]{r.a(), r.b()}) {
            assertTrue(Math.abs(p.newX()) + p.newHalf() <= DOOR.width() / 2 + 1e-9, "within the door's width");
            assertTrue(p.newY() >= -DOOR.height() / 2 - 1e-9 && p.newY() <= DOOR.height() / 2 + 1e-9, "within the door's height");
            assertEquals(-StillModel.PROUD, p.newZ(), 1e-12);
        }
        // The flames lick higher at a full fire than at a low one.
        double low = 0, high = 0;
        for (var r : sink.ribbons) {
            low = Math.max(low, r.b().oldY());
            high = Math.max(high, r.b().newY());
        }
        assertTrue(high > low);
        var none = new RecordingSink();
        StillModel.write(none, DOOR, true, 0, 0);
        assertEquals(0, none.quads());
    }

    @Test
    void burnerJetCrossesTheDoorHotAndBlueAtTheRoot() {
        assertEquals(StillModel.JET_SEGMENTS, StillModel.quads(false, .3));
        var sink = new RecordingSink();
        StillModel.write(sink, DOOR, false, 1, 1);
        assertEquals(StillModel.JET_SEGMENTS, sink.quads());
        var root = sink.ribbons.get(0).a();
        var tip = sink.ribbons.get(StillModel.JET_SEGMENTS - 1).b();
        assertEquals(-DOOR.width() / 2, root.newX(), 1e-12);
        assertEquals(-DOOR.width() / 2 + .9 * DOOR.width(), tip.newX(), 1e-12);
        int params = sink.ribbons.get(0).params();
        double heat = (params >> 8 & 0xFF) / 255.0, soot = (params & 0xFF) / 255.0;
        assertTrue(FxColour.blueRoot(0, heat, soot) > .6, "a clean pressure-jet flame is blue at its root");
        assertEquals(0, FxColour.blueRoot(.5, heat, soot), 1e-12);
    }
}
