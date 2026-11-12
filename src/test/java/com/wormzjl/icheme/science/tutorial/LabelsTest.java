package com.wormzjl.icheme.science.tutorial;

import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.tutorial.scene.Labels;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The labels' places (batch 2026-11-12-tutorial-system, P2b): beside their points, inside the glass, clear of each other and of what is kept. */
class LabelsTest {
    private static final Rect GLASS = new Rect(10, 30, 440, 160);

    @Test
    void labelsStandInsideTheGlassClearOfEachOtherAndOfWhatIsKept() {
        // Five labels crowded round the middle, a legend and a readout kept, the equipment avoided.
        var anchors = new ArrayList<Labels.Anchor>();
        for (int i = 0; i < 5; i++) anchors.add(new Labels.Anchor(200 + 12 * i, 100 + 6 * i, 50, 12));
        var keep = List.of(new Rect(13, 33, 120, 12), new Rect(330, 33, 117, 12));
        var avoid = List.of(new Rect(170, 60, 120, 100));
        var placed = Labels.place(anchors, GLASS, keep, avoid);
        assertEquals(anchors.size(), placed.size());
        for (int i = 0; i < placed.size(); i++) {
            var box = placed.get(i);
            assertNotNull(box, "label " + i + " placed");
            assertTrue(grown(box).inside(GLASS), "inside the glass with its gap: " + box);
            for (var k : keep) assertFalse(grown(box).overlaps(k), "clear of what is kept: " + box);
            for (int j = 0; j < placed.size(); j++) {
                if (j == i) continue;
                assertFalse(grown(box).overlaps(placed.get(j)), "label " + i + " over label " + j);
                assertFalse(grown(box).contains(anchors.get(j).x(), anchors.get(j).y()), "label " + i + " over point " + j);
            }
            assertEquals(50, box.w());
            assertEquals(12, box.h());
        }
    }

    @Test
    void aLabelStandsNearItsPointAndOffWhatItAvoids() {
        var a = new Labels.Anchor(230, 110, 40, 12);
        var free = Labels.place(List.of(a), GLASS, List.of(), List.of()).getFirst();
        double[] near = Labels.nearest(free, a.x(), a.y());
        assertTrue(Math.hypot(near[0] - a.x(), near[1] - a.y()) <= 10, "a free label stands within reach of its point: " + free);
        var avoid = new Rect(150, 70, 160, 80);
        var off = Labels.place(List.of(a), GLASS, List.of(), List.of(avoid)).getFirst();
        assertEquals(0, Labels.overlap(off, avoid), "off the equipment where the glass has room: " + off);
        assertEquals(Labels.place(List.of(a), GLASS, List.of(), List.of(avoid)), Labels.place(List.of(a), GLASS, List.of(), List.of(avoid)), "a pure function");
    }

    @Test
    void aLabelWithNoRoomIsNotPlaced() {
        var tiny = new Rect(0, 0, 30, 14);
        var placed = Labels.place(List.of(new Labels.Anchor(15, 7, 40, 12)), tiny, List.of(), List.of());
        assertNull(placed.getFirst(), "wider than the glass");
        assertArrayEquals(new double[]{20, 30}, Labels.nearest(new Rect(20, 30, 10, 10), 0, 0));
    }

    private static Rect grown(Rect r) {
        return Labels.grown(r, Labels.GAP);
    }
}
