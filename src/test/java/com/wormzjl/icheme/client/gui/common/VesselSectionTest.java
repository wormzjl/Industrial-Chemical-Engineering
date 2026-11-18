package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A vessel's section on the glass (batch 2026-11-17-dynamic-settling, decision D6 C: "bars and section"): everything it
 * draws lies inside its box, at every box from 60 by 60 to 260 by 200, standing and lying, with each layer empty in turn;
 * its readings stand whole and apart, beside a standing vessel and under a lying drum, which takes the box's width; its
 * loops of fills draw in one batch.
 */
class VesselSectionTest {
    private static final Measure M = Measure.fixed(6);
    private static final List<VesselSection.Label> LABELS = List.of(new VesselSection.Label("Gas 100.0 %", VesselSection.GAS_LAYER),
            new VesselSection.Label("Oil 55.0 %", VesselSection.OIL_LAYER), new VesselSection.Label("Water 40.0 %", VesselSection.WATER_LAYER));

    /** A canvas that records what it draws, and whether each fill came inside a batch. */
    private static final class Recording implements Canvas {
        final Rect box;
        final List<Rect> texts = new ArrayList<>();
        int depth, outside, batches, fills;
        Rect strayFill;

        Recording(Rect box) {
            this.box = box;
        }

        @Override
        public Measure measure() {
            return M;
        }

        @Override
        public void fill(int x0, int y0, int x1, int y1, int argb) {
            if (x1 > x0 && y1 > y0 && strayFill == null && !(x0 >= box.x() && y0 >= box.y() && x1 <= box.right() && y1 <= box.bottom())) strayFill = new Rect(x0, y0, x1 - x0, y1 - y0);
            fills++;
            if (depth == 0) outside++;
        }

        @Override
        public void text(String text, int x, int y, int argb) {
            texts.add(new Rect(x, y, M.width(text), M.height()));
        }

        @Override
        public void text(String text, float x, float y, float scale, int argb) {
        }

        @Override
        public void batch(Runnable body) {
            if (depth == 0) batches++;
            depth++;
            body.run();
            depth--;
        }

        @Override
        public void clip(Rect area) {
        }

        @Override
        public void unclip() {
        }
    }

    /** Every case: standing and lying, the liquid at 0, half and full, each of the liquid's layers empty in turn, mist or none. */
    private static List<VesselSection.Layers> cases(List<VesselSection.Label> labels) {
        var out = new ArrayList<VesselSection.Layers>();
        double[][] liquids = {{.45, .35, .20}, {0, .6, .4}, {.6, 0, .4}, {.6, .4, 0}, {1, 0, 0}, {0, 0, 1}, {0, 1, 0}};
        for (boolean lying : new boolean[]{false, true})
            for (double level : new double[]{0, .5, 1})
                for (var l : liquids)
                    for (boolean mist : new boolean[]{false, true}) out.add(new VesselSection.Layers(lying, level, l[0], l[1], l[2], mist, labels));
        return out;
    }

    @Test
    void everythingLiesInsideTheBoxAndTheReadingsStandWholeAndApart() {
        int checked = 0;
        for (var layers : cases(LABELS))
            for (int w = 60; w <= 260; w += 20)
                for (int h = 60; h <= 200; h += 20) {
                    var box = new Rect(17, 23, w, h);
                    String what = (layers.lying() ? "lying" : "standing") + " " + layers + " in " + w + " x " + h;
                    var c = new Recording(box);
                    VesselSection.draw(c, box, layers);
                    assertNull(c.strayFill, what + ": a fill outside");
                    assertTrue(c.fills > 0 && c.batches == 1, what + ": its fills in one batch");
                    for (var t : c.texts) assertTrue(t.inside(box), what + ": a text outside " + t);
                    assertEquals(0, c.outside, what + ": fills outside a batch");
                    var layout = VesselSection.layout(box, layers, M);
                    var vessel = layout.vessel();
                    var outline = new Rect(vessel.x() - 1, vessel.y() - 1, vessel.w() + 2, vessel.h() + 2);
                    assertTrue(outline.inside(box), what + ": the vessel " + vessel);
                    assertEquals(5, layout.bounds().length);
                    for (int i = 0; i < 4; i++) assertTrue(layout.bounds()[i] <= layout.bounds()[i + 1], what + ": layers in order");
                    assertEquals(vessel.h(), layout.bounds()[4]);
                    var texts = layout.texts();
                    assertTrue(texts.isEmpty() || texts.size() == LABELS.size(), what + ": all readings or none");
                    for (int i = 0; i < texts.size(); i++) {
                        var t = texts.get(i);
                        assertEquals(t.label().text(), String.join(" ", t.lines()), what + ": a reading whole");
                        for (var line : t.lines()) assertTrue(M.width(line) <= t.w(), what + ": a line wider than its reading");
                        assertTrue(t.rect().inside(box), what + ": a reading outside " + t.rect());
                        assertFalse(t.rect().overlaps(outline), what + ": a reading over the vessel");
                        if (t.swatch() != null) {
                            assertTrue(t.swatch().inside(box) && !t.swatch().overlaps(outline), what + ": a swatch " + t.swatch());
                            assertTrue(t.swatch().right() <= t.x(), what + ": the swatch before its text");
                        }
                        for (int j = 0; j < i; j++) {
                            var o = texts.get(j);
                            assertFalse(t.rect().overlaps(o.rect()), what + ": readings over each other");
                            if (t.swatch() != null && o.swatch() != null) assertFalse(t.swatch().overlaps(o.rect()) || o.swatch().overlaps(t.rect()), what + ": a swatch over a reading");
                        }
                    }
                    if (layers.lying()) {
                        assertEquals(w - 2, vessel.w(), what + ": the drum takes the box's width");
                        for (var t : texts) assertTrue(t.y() > vessel.bottom() && t.swatch() != null, what + ": the readings under the drum");
                    }
                    if (w >= 110 && h >= 80) assertEquals(LABELS.size(), texts.size(), what + ": room for every reading");
                    checked++;
                }
        assertTrue(checked > 5000);
    }

    @Test
    void aStandingVesselHasItsReadingsBesideItsLayers() {
        var layers = new VesselSection.Layers(false, .5, .45, .35, .20, false, LABELS);
        var box = new Rect(0, 0, 160, 160);
        var layout = VesselSection.layout(box, layers, M);
        var vessel = layout.vessel();
        assertEquals(158, vessel.h(), "upright, the box's height");
        assertEquals(Math.round(158 * .42), vessel.w());
        for (var t : layout.texts()) {
            assertNull(t.swatch(), "a leader line, no swatch");
            assertTrue(t.x() > vessel.right(), "beside the vessel");
        }
        // Each at its layer's middle: gas over the top half, the oil and the water in the lower half.
        int[] b = layout.bounds();
        assertEquals(79, b[1]);
        var gas = layout.texts().get(0);
        assertEquals(vessel.y() + b[1] / 2 - 4, gas.y());
        assertTrue(layout.texts().get(2).y() > layout.texts().get(1).y(), "water under oil");
        // With no labels the vessel alone, upright.
        assertTrue(VesselSection.layout(box, new VesselSection.Layers(false, .5, 1, 0, 0, false, List.of()), M).texts().isEmpty());
    }

    @Test
    void theLayersAreChecked() {
        assertThrows(IllegalArgumentException.class, () -> new VesselSection.Layers(false, 1.1, 1, 0, 0, false, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new VesselSection.Layers(false, .5, .5, .2, 0, false, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new VesselSection.Label("x", 4));
        // An empty vessel needs no liquid layers.
        new VesselSection.Layers(true, 0, 0, 0, 0, false, List.of());
    }
}
