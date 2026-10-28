package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.PortColours;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram.Align;
import com.wormzjl.icheme.client.gui.common.HeatDiagram.Glyph;
import com.wormzjl.icheme.client.gui.common.HeatDiagram.Part;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter.Look;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.science.assembly.AssemblyReport;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * A heat unit's flowsheet on its Control tab (HEAT_GUI_PLAN.md section 2), drawn from its {@link HeatDiagram} scaled to the
 * area it is given, through the kit's {@link FlowsheetPainter} as a column's drawing is (batch 2026-10-28-gui-toolkit): the
 * body neutral, an exchanger's line parts teal, yellow or red by their state, a square in its class colour at each nozzle,
 * this side's pills in the accent colour, and every reading the screen resolves. No Minecraft classes: the screen and the
 * render tool draw the same drawing.
 */
public final class HeatFlowsheet {
    /** The design's labels against the 9-pixel font. */
    private static final double FONT = 1.0;

    /** What the drawing reads: the side's view, and the language. */
    public interface Data {
        /** A reading as text, or empty for none. */
        String value(HeatDiagram.Value value);

        /** The state of line part {@code slot} by its {@code AssemblyReport.State} name, or {@code NOT_VALIDATED}. */
        String state(String slot);

        /** A drawing text in the player's language. */
        String text(AssemblyDiagram.Text text);
    }

    public final HeatDiagram diagram;
    private final List<String> own;
    private int x, y, w = HeatDiagram.WIDTH, h = HeatDiagram.HEIGHT;
    private @Nullable FlowsheetPainter painter;

    /** The drawing of {@code diagram}; {@code own} the roles of the side whose screen it is on (its pills in the accent). */
    public HeatFlowsheet(HeatDiagram diagram, List<String> own) {
        this.diagram = diagram;
        this.own = List.copyOf(own);
    }

    /** Fits the drawing into the box from ({@code x}, {@code y}), {@code w} by {@code h}, centred. */
    public void fit(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    /** A line part's colour group from its state's name; a part before its first check is red, as a column's is. */
    public static AssemblyReport.Colour colour(String state) {
        try {
            return AssemblyReport.State.valueOf(state).colour;
        } catch (IllegalArgumentException notChecked) {
            return AssemblyReport.Colour.RED;
        }
    }

    private static Look look(Part part, Data data) {
        return part.line() ? Look.of(colour(data.state(part.slot()))) : new Look(FlowsheetPainter.BODY, Palette.TEAL, Palette.TEXT);
    }

    private static float anchor(Align align) {
        return switch (align) {
            case LEFT -> 0;
            case CENTRE -> 0.5f;
            case RIGHT -> 1;
        };
    }

    public void draw(Canvas c, Data data) {
        var p = painter = FlowsheetPainter.fit(c, HeatDiagram.WIDTH, HeatDiagram.HEIGHT, x, y, w, h, FONT);
        var d = diagram;
        // Process lines under the parts; utility lines (air, flue, the bundle, the weir) over them.
        for (var wire : d.wires()) if (!wire.utility()) p.wire(wire.points(), wire.arrow(), false, Palette.MUTED);
        for (var pill : d.pills()) {
            p.pill(pill.x(), pill.y(), pill.w(), 9);
            String value = data.value(pill.value()), name = data.text(pill.name());
            int colour = own.contains(pill.role()) ? Palette.ACCENT : Palette.MUTED;
            // The name and the value, or the value alone where both do not fit.
            if (!p.fitted(name + (value.isEmpty() ? "" : " " + value), pill.x() + pill.w() / 2, pill.y(), pill.w() - 6, colour))
                p.fitted(value.isEmpty() ? name : value, pill.x() + pill.w() / 2, pill.y(), pill.w() - 6, colour);
        }
        for (var part : d.parts()) part(p, part, data);
        for (var wire : d.wires()) if (wire.utility()) p.wire(wire.points(), wire.arrow(), true, Palette.LINE);
        if (p.labels()) for (var l : d.labels()) p.label(data.text(l.text()), l.x(), l.y(), anchor(l.align()), Palette.MUTED);
        for (var t : d.tags()) {
            String value = data.value(t.value());
            if (!value.isEmpty()) p.label(value, t.x(), t.y(), anchor(t.align()), Palette.TEXT);
        }
        for (var n : d.nozzles()) p.square(n.x(), n.y(), HeatDiagram.Nozzle.SIZE, PortColours.of(EquipmentShape.Role.valueOf(n.role()).port));
    }

    private static void part(FlowsheetPainter p, Part part, Data data) {
        var look = look(part, data);
        var c = p.canvas();
        int x0 = p.x(part.x()), y0 = p.y(part.y()), x1 = p.x(part.x() + part.w()), y1 = p.y(part.y() + part.h());
        int cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
        switch (part.glyph()) {
            case SHELL, DRUM -> p.rounded(x0, y0, x1, y1, Math.max(1, Math.min((y1 - y0) / 2, (x1 - x0) / 2) * (part.glyph() == Glyph.SHELL ? 2 : 1) / 2), look);
            case HEAD, BOX -> p.box(x0, y0, x1, y1, look);
            case TEE -> p.tee(x0, y0, x1, y1, look);
            case VALVE -> p.valve(cx, p.y(part.y() + part.h() - 6), look);
            case VALVE_DOWN -> p.valveDown(p.x(part.x() + part.w() - 6), cy, look);
            case METER, FAN -> {
                p.circle(cx, cy, Math.max(2, (x1 - x0) / 2), look);
                if (part.glyph() == Glyph.METER) p.fitted("FT", part.x() + part.w() / 2, part.y() + part.h() / 2, part.w() + 4, look.text());
            }
            case BURNER -> {
                for (int yy = y0; yy < y1; yy++) {
                    int half = (int) Math.round((x1 - x0) / 2.0 * (yy - y0 + 1) / Math.max(1, y1 - y0));
                    c.hLine(cx - half, cx + half, yy, Palette.YELLOW);
                }
            }
        }
        if (part.label() != null) p.fitted(data.text(part.label()), part.x() + part.w() / 2, part.y() + part.h() / 2 - 6, part.w(), look.text());
    }

    /** The line part under canvas point ({@code mx}, {@code my}) as last drawn: the smallest box there. */
    public @Nullable Part part(double mx, double my) {
        if (painter == null) return null;
        double dx = painter.designX(mx), dy = painter.designY(my);
        Part found = null;
        for (var p : diagram.parts()) if (p.contains(dx, dy) && (found == null || p.w() * p.h() < found.w() * found.h())) found = p;
        return found;
    }

    /** The pill under canvas point ({@code mx}, {@code my}) as last drawn, whose full text a hover shows where it was cut. */
    public @Nullable HeatDiagram.Pill pill(double mx, double my) {
        if (painter == null) return null;
        double dx = painter.designX(mx), dy = painter.designY(my);
        for (var p : diagram.pills()) if (dx >= p.x() && dx < p.x() + p.w() && dy >= p.y() - 9 && dy < p.y() + 9) return p;
        return null;
    }
}
