package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.PortColours;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram.Align;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram.Glyph;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram.Part;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram.Pill;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram.PortMark;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter.Look;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.assembly.AssemblyReport;
import com.wormzjl.icheme.science.assembly.AssemblyReport.PortState;
import com.wormzjl.icheme.science.assembly.AssemblyReport.SlotReport;
import com.wormzjl.icheme.science.assembly.Blueprint.Slot;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * A column's assembly drawing on the HMI glass (batch 2026-10-28-gui-toolkit, GUI_REVIEW.md order 4), from its
 * {@link AssemblyDiagram} fitted to the box it is given, through the kit's {@link FlowsheetPainter}: each part teal, yellow
 * or red by its report's state (the column neutral while it is in place; every part red before the first report), the
 * selected part outlined, each port square in its own colour, edged grey once connected. Its hit tests answer the
 * screen's clicks and hovers from the same fit. No Minecraft classes: the screen and the render tool draw the same drawing.
 */
public final class AssemblyFlowsheet {
    /** The design's labels against the 9-pixel font. */
    private static final double FONT = 1.2;

    public final AssemblyDiagram diagram;
    private int ox, oy;
    private double scale = 1;

    public AssemblyFlowsheet(AssemblyDiagram diagram) {
        this.diagram = diagram;
    }

    /** Fits the drawing into {@code box}, centred, as large as it fits. */
    public void fit(Rect box) {
        scale = Math.max(0.02, Math.min(box.w() / (double) AssemblyDiagram.WIDTH, box.h() / (double) AssemblyDiagram.HEIGHT));
        ox = box.x() + (int) ((box.w() - AssemblyDiagram.WIDTH * scale) / 2);
        oy = box.y() + (int) ((box.h() - AssemblyDiagram.HEIGHT * scale) / 2);
    }

    /** The report of {@code slot} in {@code report}, or null (no report yet). */
    public static @Nullable SlotReport slot(@Nullable AssemblyReport report, Slot slot) {
        if (report == null) return null;
        for (var s : report.slots()) if (s.slot().equals(slot)) return s;
        return null;
    }

    /** A port's state, or null while there is no report or no unit in its slot: it still needs its pipe. */
    public static @Nullable PortState port(@Nullable AssemblyReport report, PortMark m) {
        var r = slot(report, m.slot());
        if (r == null || r.member() == AssemblyReport.NONE) return null;
        PortState found = null;
        for (var p : report.ports()) {
            if (p.unit() != r.member() || p.role() != m.role() || m.ordinal() >= 0 && p.ordinal() != m.ordinal()) continue;
            if (m.ordinal() >= 0 || p.state() == PortState.CONNECTED) return p.state();
            found = p.state();
        }
        return found;
    }

    /** A slot's colour group: the report's, red before it. */
    public static AssemblyReport.Colour colour(@Nullable SlotReport r) {
        return r == null ? AssemblyReport.Colour.RED : r.state().colour;
    }

    /** A part's look: the column neutral while it is in place (or before a report), every other part by its colour group. */
    private static Look look(Part part, @Nullable AssemblyReport report) {
        var r = slot(report, part.slot());
        if (part.glyph() == Glyph.COLUMN && (r == null || r.state() == AssemblyReport.State.OK)) return new Look(FlowsheetPainter.BODY, Palette.LINE, Palette.TEXT);
        return Look.of(colour(r));
    }

    private static float anchor(Align align) {
        return switch (align) {
            case LEFT -> 0;
            case CENTRE -> 0.5f;
            case RIGHT -> 1;
        };
    }

    /**
     * Draws the drawing as {@link #fit} placed it: the lines, the trays, the pills, the parts (the {@code selected} one
     * outlined), the free labels where the font keeps the design's proportion, the port squares. {@code text} gives a
     * drawing text in the player's language.
     */
    public void draw(Canvas c, @Nullable AssemblyReport report, @Nullable Slot selected, Function<AssemblyDiagram.Text, String> text) {
        var d = diagram;
        var p = new FlowsheetPainter(c, ox, oy, scale, FONT);
        for (var w : d.wires()) p.wire(w.points(), w.arrow(), w.utility(), w.utility() ? Palette.LINE : Palette.MUTED);
        for (var t : d.trays()) c.hLine(p.x(t[0]), p.x(t[2]), p.y(t[1]), Palette.LINE);
        for (var pill : d.pills()) {
            p.pill(pill.x(), pill.y(), pill.w(), 11);
            p.fitted(text.apply(pill.text()), pill.x() + pill.w() / 2, pill.y(), pill.w(), Palette.MUTED);
        }
        for (var part : d.parts()) part(p, part, report, selected, text);
        // Free labels only where the font keeps the design's proportion; below it they would cover the parts.
        if (p.labels()) for (var l : d.labels()) p.label(text.apply(l.text()), l.x(), l.y(), anchor(l.align()), Palette.MUTED);
        for (var m : d.ports()) {
            var state = port(report, m);
            if (state != PortState.UNUSED) p.square(m.x(), m.y(), PortMark.SIZE, PortColours.assembly(d, m.slot(), m.role(), m.ordinal()),
                    state == PortState.CONNECTED ? 0xFF000000 | PortColours.CONNECTED : FlowsheetPainter.EDGE);
        }
    }

    private static void part(FlowsheetPainter p, Part part, @Nullable AssemblyReport report, @Nullable Slot selected, Function<AssemblyDiagram.Text, String> text) {
        var look = look(part, report);
        int x0 = p.x(part.x()), y0 = p.y(part.y()), x1 = p.x(part.x() + part.w()), y1 = p.y(part.y() + part.h());
        switch (part.glyph()) {
            case COLUMN, UNIT, DRUM -> {
                if (part.glyph() == Glyph.DRUM) p.box(p.x(part.x() + 60), p.y(part.y() + 26), p.x(part.x() + 82), p.y(part.y() + 64), look);
                p.box(x0, y0, x1, y1, look);
            }
            case TEE -> p.tee(x0, y0, x1, y1, look);
            case VALVE -> p.valve((x0 + x1) / 2, p.y(part.y() + part.h() - 6), look);
            default -> {
                boolean down = part.glyph() == Glyph.PUMP_DOWN || part.glyph() == Glyph.INLINE_PUMP_DOWN;
                boolean standard = part.glyph() == Glyph.PUMP_DOWN || part.glyph() == Glyph.PUMP_ACROSS;
                int cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
                if (standard) {
                    int m = p.px(4), o = p.px(10), s = p.px(8);
                    if (down) p.canvas().fill(cx + o, cy - m, cx + o + s, cy + m, look.stroke());
                    else p.canvas().fill(cx - m, cy + o, cx + m, cy + o + s, look.stroke());
                }
                p.circle(cx, cy, Math.max(2, (x1 - x0) / 2), look);
            }
        }
        if (part.label() != null) p.fitted(text.apply(part.label()), part.x() + part.w() / 2, part.y() + part.h() / 2, part.w(), look.text());
        if (part.slot().equals(selected)) p.canvas().outline(x0 - 2, y0 - 2, x1 - x0 + 4, y1 - y0 + 4, Palette.TEXT);
    }

    /** The part at canvas point ({@code x}, {@code y}), or null: the smallest box under it, so a valve on a unit's line before the unit. */
    public @Nullable Part part(double x, double y) {
        double dx = (x - ox) / scale, dy = (y - oy) / scale;
        Part found = null;
        for (var p : diagram.parts()) if (p.contains(dx, dy) && (found == null || p.w() * p.h() < found.w() * found.h())) found = p;
        return found;
    }

    /** The port square at canvas point ({@code x}, {@code y}), or null; a square drawn smaller than 4 px takes 2 px round it. */
    public @Nullable PortMark port(double x, double y) {
        double dx = (x - ox) / scale, dy = (y - oy) / scale, slack = Math.max(0, 2 / scale - PortMark.SIZE / 2.0);
        for (var m : diagram.ports())
            if (Math.abs(dx - m.x()) <= PortMark.SIZE / 2.0 + slack && Math.abs(dy - m.y()) <= PortMark.SIZE / 2.0 + slack) return m;
        return null;
    }

    /** The pill (a stream's end outside the assembly) at canvas point ({@code x}, {@code y}), or null. */
    public @Nullable Pill pill(double x, double y) {
        double dx = (x - ox) / scale, dy = (y - oy) / scale;
        for (var p : diagram.pills()) if (dx >= p.x() && dx < p.x() + p.w() && dy >= p.y() - 11 && dy < p.y() + 11) return p;
        return null;
    }

    /** Where design point ({@code x}, {@code y}) lies on the canvas, as drawn. */
    public int[] onCanvas(int x, int y) {
        return new int[]{ox + (int) Math.round(x * scale), oy + (int) Math.round(y * scale)};
    }
}
