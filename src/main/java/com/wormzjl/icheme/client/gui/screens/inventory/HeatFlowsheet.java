package com.wormzjl.icheme.client.gui.screens.inventory;

import com.wormzjl.icheme.client.PortColours;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram.Align;
import com.wormzjl.icheme.client.gui.common.HeatDiagram;
import com.wormzjl.icheme.client.gui.common.HeatDiagram.Glyph;
import com.wormzjl.icheme.client.gui.common.HeatDiagram.Part;
import com.wormzjl.icheme.client.gui.common.ProcessUi;
import com.wormzjl.icheme.science.assembly.AssemblyReport;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * A heat unit's flowsheet on its Control tab (HEAT_GUI_PLAN.md section 2), drawn from its {@link HeatDiagram} scaled to the
 * area it is given, as a column's drawing is ({@link AssemblyScreen}): the body neutral, an exchanger's line parts teal,
 * yellow or red by their state, a square in its class colour at each nozzle, this side's pills in the accent colour, and
 * every reading the screen resolves. Drawn in the screen's own coordinates (its labels' pose).
 */
final class HeatFlowsheet {
    private static final int TEAL_FILL = 0xFF12392F, TEAL = 0xFF1D9E75, TEAL_TEXT = 0xFF9FE1CB, YELLOW_FILL = 0xFF3D2E0F, YELLOW = 0xFFEF9F27,
            YELLOW_TEXT = 0xFFFAC775, RED_FILL = 0xFF3F1A1A, RED = 0xFFE24B4A, RED_TEXT = 0xFFF7C1C1, BODY = 0xFF33434D, EDGE = 0xFF444441;
    /** The design's labels against the 9-pixel font, and the smallest font drawn. */
    private static final double FONT = 1.0, MIN_FONT = 0.5;

    /** What the drawing reads from the side's view. */
    interface Data {
        /** A reading as text, or empty for none. */
        String value(HeatDiagram.Value value);

        /** The state of line part {@code slot} by its {@code AssemblyReport.State} name, or {@code NOT_VALIDATED}. */
        String state(String slot);
    }

    final HeatDiagram diagram;
    private final List<String> own;
    private int ox, oy;
    private double scale = 1;

    HeatFlowsheet(HeatDiagram diagram, List<String> own) {
        this.diagram = diagram;
        this.own = List.copyOf(own);
    }

    /** Fits the drawing into the box from ({@code x}, {@code y}), {@code w} by {@code h}, centred. */
    void fit(int x, int y, int w, int h) {
        scale = Math.max(0.05, Math.min(w / (double) HeatDiagram.WIDTH, h / (double) HeatDiagram.HEIGHT));
        ox = x + (int) ((w - HeatDiagram.WIDTH * scale) / 2);
        oy = y + (int) ((h - HeatDiagram.HEIGHT * scale) / 2);
    }

    private int x(int design) {
        return ox + (int) Math.round(design * scale);
    }

    private int y(int design) {
        return oy + (int) Math.round(design * scale);
    }

    private int px(int design) {
        return Math.max(1, (int) Math.round(design * scale));
    }

    /** A line part's colour group from its state's name; a part before its first check is red, as a column's is. */
    static AssemblyReport.Colour colour(String state) {
        try {
            return AssemblyReport.State.valueOf(state).colour;
        } catch (IllegalArgumentException notChecked) {
            return AssemblyReport.Colour.RED;
        }
    }

    private int[] look(Part part, Data data) {
        if (!part.line()) return new int[]{BODY, TEAL, ProcessUi.TEXT};
        return switch (colour(data.state(part.slot()))) {
            case TEAL -> new int[]{TEAL_FILL, TEAL, TEAL_TEXT};
            case YELLOW -> new int[]{YELLOW_FILL, YELLOW, YELLOW_TEXT};
            case RED -> new int[]{RED_FILL, RED, RED_TEXT};
        };
    }

    void draw(GuiGraphics g, Font font, Data data) {
        var d = diagram;
        // Process lines under the parts; utility lines (air, flue, the bundle, the weir) over them.
        for (var w : d.wires()) if (!w.utility()) wire(g, w);
        for (var p : d.pills()) {
            g.fill(x(p.x()), y(p.y() - 9), x(p.x() + p.w()), y(p.y() + 9), ProcessUi.PANEL);
            g.renderOutline(x(p.x()), y(p.y() - 9), x(p.x() + p.w()) - x(p.x()), y(p.y() + 9) - y(p.y() - 9), ProcessUi.LINE);
            String value = data.value(p.value());
            String name = AssemblyScreen.text(p.name()).getString();
            int colour = own.contains(p.role()) ? ProcessUi.ACCENT : ProcessUi.MUTED;
            // The name and the value, or the value alone where both do not fit.
            if (!fitted(g, font, name + (value.isEmpty() ? "" : " " + value), p.x() + p.w() / 2, p.y(), p.w() - 6, colour)) fitted(g, font, value.isEmpty() ? name : value, p.x() + p.w() / 2, p.y(), p.w() - 6, colour);
        }
        for (var part : d.parts()) part(g, font, part, data);
        for (var w : d.wires()) if (w.utility()) wire(g, w);
        if (scale * FONT >= MIN_FONT) for (var l : d.labels()) label(g, font, AssemblyScreen.text(l.text()).getString(), l.x(), l.y(), l.align(), ProcessUi.MUTED);
        for (var t : d.tags()) {
            String value = data.value(t.value());
            if (!value.isEmpty()) label(g, font, value, t.x(), t.y(), t.align(), ProcessUi.TEXT);
        }
        for (var n : d.nozzles()) {
            int half = Math.max(2, px(HeatDiagram.Nozzle.SIZE / 2)), cx = x(n.x()), cy = y(n.y());
            g.fill(cx - half - 1, cy - half - 1, cx + half + 1, cy + half + 1, EDGE);
            g.fill(cx - half, cy - half, cx + half, cy + half, 0xFF000000 | PortColours.of(EquipmentShape.Role.valueOf(n.role()).port));
        }
    }

    private void wire(GuiGraphics g, HeatDiagram.Wire w) {
        int[] p = w.points();
        int colour = w.utility() ? ProcessUi.LINE : ProcessUi.MUTED;
        for (int i = 0; i + 3 < p.length; i += 2) {
            int x0 = x(p[i]), y0 = y(p[i + 1]), x1 = x(p[i + 2]), y1 = y(p[i + 3]);
            boolean across = y0 == y1;
            int from = across ? Math.min(x0, x1) : Math.min(y0, y1), to = across ? Math.max(x0, x1) : Math.max(y0, y1);
            // A utility line is dashed: two pixels on, one off.
            for (int a = from; a <= to; a += w.utility() ? 3 : to - from + 1) {
                int b = w.utility() ? Math.min(a + 1, to) : to;
                if (across) g.hLine(a, b, y0, colour);
                else g.fill(x0, a, x0 + 1, b + 1, colour);
            }
        }
        if (!w.arrow() || p.length < 4) return;
        int n = p.length;
        int ex = x(p[n - 2]), ey = y(p[n - 1]), dx = Integer.signum(x(p[n - 2]) - x(p[n - 4])), dy = Integer.signum(y(p[n - 1]) - y(p[n - 3]));
        for (int k = 0; k < 3; k++) {
            int bx = ex - dx * k, by = ey - dy * k;
            if (dx != 0) g.vLine(bx, by - k - 1, by + k + 1, colour);
            else g.hLine(bx - k, bx + k, by, colour);
        }
    }

    private void part(GuiGraphics g, Font font, Part part, Data data) {
        int[] look = look(part, data);
        int x0 = x(part.x()), y0 = y(part.y()), x1 = x(part.x() + part.w()), y1 = y(part.y() + part.h());
        int cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
        switch (part.glyph()) {
            case SHELL, DRUM -> {
                // Rounded ends: rows shortened by a circle's chord at each end.
                int r = Math.max(1, Math.min((y1 - y0) / 2, (x1 - x0) / 2) * (part.glyph() == Glyph.SHELL ? 2 : 1) / 2);
                for (int yy = y0; yy < y1; yy++) {
                    double dy = Math.max(0, Math.max(y0 + r - yy - 0.5, yy + 0.5 - (y1 - r)));
                    int inset = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
                    g.hLine(x0 + inset, x1 - 1 - inset, yy, look[0]);
                    g.fill(x0 + inset, yy, x0 + inset + 1, yy + 1, look[1]);
                    g.fill(x1 - 1 - inset, yy, x1 - inset, yy + 1, look[1]);
                }
                g.hLine(x0 + r, x1 - 1 - r, y0, look[1]);
                g.hLine(x0 + r, x1 - 1 - r, y1 - 1, look[1]);
            }
            case HEAD, BOX -> box(g, x0, y0, x1, y1, look);
            case TEE -> g.fill(x0, y0, Math.max(x1, x0 + 2), Math.max(y1, y0 + 2), look[1]);
            case VALVE -> {
                int vy = y(part.y() + part.h() - 6), hw = Math.max(2, px(8)), hh = Math.max(2, px(6));
                for (int dx = -hw; dx <= hw; dx++) {
                    int h = Math.round(hh * Math.abs(dx) / (float) hw);
                    g.vLine(cx + dx, vy - h - 1, vy + h + 1, look[1]);
                }
                g.vLine(cx, vy - px(11) - 1, vy, look[1]);
                g.fill(cx - px(5), vy - px(16), cx + px(5) + 1, vy - px(11), look[1]);
            }
            case VALVE_DOWN -> {
                int vx = x(part.x() + part.w() - 6), hw = Math.max(2, px(8)), hh = Math.max(2, px(6));
                for (int dy = -hw; dy <= hw; dy++) {
                    int w = Math.round(hh * Math.abs(dy) / (float) hw);
                    g.hLine(vx - w - 1, vx + w + 1, cy + dy, look[1]);
                }
                g.hLine(vx - px(11) - 1, vx, cy, look[1]);
                g.fill(vx - px(16), cy - px(5), vx - px(11), cy + px(5) + 1, look[1]);
            }
            case METER, FAN -> {
                int r = Math.max(2, (x1 - x0) / 2);
                for (int dy = -r; dy <= r; dy++) {
                    int half = (int) Math.round(Math.sqrt(r * r - dy * dy));
                    g.hLine(cx - half, cx + half, cy + dy, look[0]);
                    g.fill(cx - half, cy + dy, cx - half + 1, cy + dy + 1, look[1]);
                    g.fill(cx + half, cy + dy, cx + half + 1, cy + dy + 1, look[1]);
                }
                g.hLine(cx - 1, cx + 1, cy - r, look[1]);
                g.hLine(cx - 1, cx + 1, cy + r, look[1]);
                if (part.glyph() == Glyph.METER) fitted(g, font, "FT", part.x() + part.w() / 2, part.y() + part.h() / 2, part.w() + 4, look[2]);
            }
            case BURNER -> {
                for (int yy = y0; yy < y1; yy++) {
                    int half = (int) Math.round((x1 - x0) / 2.0 * (yy - y0 + 1) / Math.max(1, y1 - y0));
                    g.hLine(cx - half, cx + half, yy, YELLOW);
                }
            }
        }
        if (part.label() != null) fitted(g, font, AssemblyScreen.text(part.label()).getString(), part.x() + part.w() / 2, part.y() + part.h() / 2 - 6, part.w(), look[2]);
    }

    private static void box(GuiGraphics g, int x0, int y0, int x1, int y1, int[] look) {
        g.fill(x0, y0, x1, y1, look[0]);
        g.renderOutline(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0), look[1]);
    }

    private float fontScale() {
        return (float) Math.clamp(scale * FONT, MIN_FONT, 1.0);
    }

    /** A label centred on design point ({@code x}, {@code y}), drawn only if it fits {@code width} design units; whether it was drawn. */
    private boolean fitted(GuiGraphics g, Font font, String text, int x, int y, int width, int colour) {
        if (font.width(text) * fontScale() > width * scale - 2) return false;
        label(g, font, text, x, y, Align.CENTRE, colour);
        return true;
    }

    /** A label at design point ({@code x}, {@code y}), in a font scaled with the drawing, never below {@link #MIN_FONT}. */
    private void label(GuiGraphics g, Font font, String text, int x, int y, Align align, int colour) {
        float fs = fontScale();
        float w = font.width(text) * fs;
        float tx = switch (align) {
            case LEFT -> x(x);
            case CENTRE -> x(x) - w / 2;
            case RIGHT -> x(x) - w;
        };
        g.pose().pushPose();
        g.pose().translate(tx, y(y) - font.lineHeight * fs / 2 + fs, 0);
        g.pose().scale(fs, fs, 1);
        g.drawString(font, text, 0, 0, colour, false);
        g.pose().popPose();
    }

    /** The line part under screen point ({@code mx}, {@code my}): the smallest box there. */
    @Nullable Part part(double mx, double my) {
        double dx = (mx - ox) / scale, dy = (my - oy) / scale;
        Part found = null;
        for (var p : diagram.parts()) if (p.contains(dx, dy) && (found == null || p.w() * p.h() < found.w() * found.h())) found = p;
        return found;
    }

    /** The pill under screen point ({@code mx}, {@code my}), whose full text a hover shows where it was cut. */
    @Nullable HeatDiagram.Pill pill(double mx, double my) {
        double dx = (mx - ox) / scale, dy = (my - oy) / scale;
        for (var p : diagram.pills()) if (dx >= p.x() && dx < p.x() + p.w() && dy >= p.y() - 9 && dy < p.y() + 9) return p;
        return null;
    }

    /** A line part's hover: its name and its state in the column's words. */
    static Component hover(String slot, String state, String detail, long at) {
        var name = Component.translatable("gui.icheme.assembly.part." + slot);
        var where = Component.translatable("gui.icheme.assembly.where." + slot);
        try {
            return AssemblyScreen.stateText(name, where, null, AssemblyReport.State.valueOf(state), detail, at);
        } catch (IllegalArgumentException notChecked) {
            return Component.translatable("gui.icheme.assembly.state.not_validated", name, where);
        }
    }
}
