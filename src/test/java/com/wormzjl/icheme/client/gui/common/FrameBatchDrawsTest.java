package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.HeldDraws;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import com.wormzjl.icheme.client.gui.common.kit.Sprite;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The all-kit screens draw their frame in one batch (finding C4, batch 2026-11-13-codebase-review), as the simulator does:
 * a door's page, title, column and widgets, counted as 1.21.1 {@code GuiGraphics} draws them ({@link HeldDraws}), without
 * and with the batch. Outside a batch each fill and each text is one draw call.
 */
class FrameBatchDrawsTest {
    private static final Measure M = Measure.fixed(6);

    /** Counts draw calls as {@code GuiCanvas} makes them. */
    private static final class Counting implements Canvas {
        final HeldDraws held = new HeldDraws();

        @Override
        public Measure measure() {
            return M;
        }

        @Override
        public void batch(Runnable body) {
            held.begin(() -> {
            });
            try {
                body.run();
            } finally {
                held.end();
            }
        }

        @Override
        public void fill(int x0, int y0, int x1, int y1, int argb) {
            held.fill(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1));
        }

        @Override
        public void text(String text, int x, int y, int argb) {
            text(text, x, y, 1, argb);
        }

        @Override
        public void text(String text, float x, float y, float scale, int argb) {
            held.text(x, y, x + M.width(text) * scale, y + M.height() * scale, () -> {
            });
        }

        @Override
        public void sprite(Sprite sprite, float x0, float y0, float x1, float y1, int argb) {
            held.sprite(x0, y0, x1, y1, () -> {
            });
        }

        @Override
        public void clip(Rect area) {
            held.clip();
        }

        @Override
        public void unclip() {
            held.clip();
        }
    }

    private record Screen(String name, int width, int height, List<Column.Row> rows, int footer) {
    }

    /** One frame of a door: the page, the title, the column clipped to its view, each of its buttons and the footer's push buttons. */
    private static void frame(Canvas c, Skin skin, Screen s) {
        skin.page(c, new Rect(0, 0, s.width(), s.height()));
        skin.title(c, 10, 10, s.name());
        var column = Column.place(s.rows(), 10, DialogColumns.TOP, s.width() - 20, M, Column.Metrics.COMPACT);
        c.clip(new Rect(8, DialogColumns.TOP, s.width() - 16, s.height() - DialogColumns.TOP - DialogColumns.FOOTER));
        column.draw(c, 0, skin);
        c.unclip();
        for (var p : column.rows())
            if (p.row() instanceof Column.Buttons b)
                for (var spec : b.buttons()) skin.button(c, p.slots().get(spec.key()), spec.text(), Skin.Press.REST, false);
        if (s.footer() > 0) for (var r : DialogColumns.footer(s.width(), s.height(), s.footer())) skin.button(c, r, "Save", Skin.Press.REST, false);
    }

    @Test
    void eachScreensFrameIsAFewDrawsInItsBatch() {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        var sizes = List.of(new DialogColumns.Size("diameter", "2.00", "m", true), new DialogColumns.Size("length", "6.00", "m", true),
                new DialogColumns.Size("volume", "18.8", "m³", false));
        var screens = List.of(
                new Screen("gauge", 246, 254, DialogColumns.gauge(new DialogColumns.Gauge(true, List.of("Pa", "kPa", "bar", "MPa"), 2, true, true), t), 2),
                new Screen("pipeline tool", 246, 254, DialogColumns.pipelineTool(new DialogColumns.PipelineTool(true, 0, false, true, false, false), t), 2),
                new Screen("vessel", 464, 254, DialogColumns.vessel(sizes, false, false, t), 1),
                new Screen("machine", 464, 254, MachineColumn.rows(MachineColumnSamples.sample(MachineColumn.Kind.MOTOR, true, true, MachineColumn.Mode.SPEED, false, t), t), 0));
        var report = new ArrayList<String>();
        for (var skin : List.of(PanelSkin.LIGHT, PanelSkin.DARK))
            for (var s : screens) {
                var plain = new Counting();
                frame(plain, skin, s);
                var batched = new Counting();
                batched.batch(() -> frame(batched, skin, s));
                report.add(s.name() + ": " + plain.held.draws() + " -> " + batched.held.draws());
                assertTrue(batched.held.draws() * 3 <= plain.held.draws(), s.name() + ": " + plain.held.draws() + " draws plain, " + batched.held.draws() + " batched");
            }
        System.out.println("frame draw calls, plain -> batched: " + report);
    }
}
