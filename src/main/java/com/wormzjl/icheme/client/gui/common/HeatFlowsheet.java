package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.PortColours;
import com.wormzjl.icheme.client.gui.common.AssemblyDiagram.Align;
import com.wormzjl.icheme.client.gui.common.HeatDiagram.Part;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter.Look;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
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

    /** The insides' colours: tubes and fins, plates (baffles, the weir, blades), the pool, its level and its bubbles, refractory, the firebox, flames. */
    private static final int TUBE = 0xFF86A2B0, FIN = 0xFF55707E, PLATE = 0xFFA6BAC5, POOL = 0xFF1A3A52, LEVEL = 0xFF5AAEE6, BUBBLE = 0xFFB4DDF5,
            DARK = 0xFF1B242A, REFRACTORY = 0xFF7A5442, FIREBOX = 0xFF2A1A14, FLAME = 0xFFF08A24, FLAME_CORE = 0xFFFFDA7A, FLAME_BASE = 0xFF5B9BE8;
    /** The plate under a reading, so a line or a part's insides do not run through its text. */
    private static final int TAG_PLATE = 0xD00E1418;

    public void draw(Canvas c, Data data) {
        var p = painter = FlowsheetPainter.fit(c, HeatDiagram.WIDTH, HeatDiagram.HEIGHT, x, y, w, h, FONT);
        var d = diagram;
        // Process lines under the parts; utility lines (the air) over them.
        for (var wire : d.wires()) if (!wire.utility()) p.wire(wire.points(), wire.arrow(), false, Palette.MUTED);
        for (var pill : d.pills()) {
            p.pill(pill.x(), pill.y(), pill.w(), 9);
            p.take(new Rect(p.x(pill.x()), p.y(pill.y() - 9), p.x(pill.x() + pill.w()) - p.x(pill.x()), p.y(pill.y() + 9) - p.y(pill.y() - 9)));
            String value = data.value(pill.value()), name = data.text(pill.name());
            int colour = own.contains(pill.role()) ? Palette.ACCENT : Palette.MUTED;
            // The name and the value, or the value alone where both do not fit.
            if (!p.fitted(name + (value.isEmpty() ? "" : " " + value), pill.x() + pill.w() / 2, pill.y(), pill.w() - 6, colour))
                p.fitted(value.isEmpty() ? name : value, pill.x() + pill.w() / 2, pill.y(), pill.w() - 6, colour);
        }
        for (var part : d.parts()) part(p, part, data);
        for (var wire : d.wires()) if (wire.utility()) p.wire(wire.points(), wire.arrow(), true, Palette.LINE);
        // Free labels and readings where they cover no text placed before them.
        if (p.labels()) for (var l : d.labels()) p.tag(data.text(l.text()), l.x(), l.y(), anchor(l.align()), Palette.MUTED, 0);
        for (var t : d.tags()) {
            String value = data.value(t.value());
            if (!value.isEmpty()) p.tag(value, t.x(), t.y(), anchor(t.align()), Palette.TEXT, TAG_PLATE);
        }
        for (var n : d.nozzles()) p.square(n.x(), n.y(), HeatDiagram.Nozzle.SIZE, PortColours.of(EquipmentShape.Role.valueOf(n.role()).port));
    }

    private static void part(FlowsheetPainter p, Part part, Data data) {
        var look = look(part, data);
        var c = p.canvas();
        int x0 = p.x(part.x()), y0 = p.y(part.y()), x1 = p.x(part.x() + part.w()), y1 = p.y(part.y() + part.h());
        int cx = (x0 + x1) / 2, cy = (y0 + y1) / 2;
        switch (part.glyph()) {
            case SHELL -> shell(p, x0, y0, x1, y1, look);
            case HEAD -> bonnet(p, x0, y0, x1, y1, look);
            case SADDLE -> saddle(c, x0, y0, x1, y1, look);
            case KETTLE -> kettle(p, x0, y0, x1, y1, look);
            case BUNDLE -> bundle(p, x0, y0, x1, y1, look);
            case PLENUM -> p.trapezoid(x0, y0, x1, y1, 0, (x1 - x0) / 12, look);
            case FAN -> fan(p, x0, y0, x1, y1, look);
            case STACK -> stack(p, x0, y0, x1, y1, look);
            case CONVECTION -> convection(p, x0, y0, x1, y1, look);
            case TRANSITION -> p.trapezoid(x0, y0, x1, y1, (x1 - x0) / 7, 0, look);
            case RADIANT -> radiant(p, x0, y0, x1, y1, look);
            case BURNER -> burner(c, x0, y0, x1, y1, look);
            case TEE -> p.tee(x0, y0, x1, y1, look);
            case VALVE -> p.valve(cx, p.y(part.y() + part.h() - 6), look);
            case VALVE_DOWN -> p.valveDown(p.x(part.x() + part.w() - 6), cy, look);
            case METER -> {
                p.circle(cx, cy, Math.max(2, (x1 - x0) / 2), look);
                p.fitted("FT", part.x() + part.w() / 2, part.y() + part.h() / 2, part.w() + 4, look.text());
            }
        }
        // The part's name on a plate of its own colour, over its insides: a kettle's in its vapour space, a firebox's over the flames.
        int ly = part.glyph() == HeatDiagram.Glyph.KETTLE || part.glyph() == HeatDiagram.Glyph.RADIANT ? part.y() + part.h() / 4 : part.y() + part.h() / 2 - 6;
        if (part.label() != null) p.fitted(data.text(part.label()), part.x() + part.w() / 2, ly, part.w(), look.text(), look.fill());
    }

    /** A dashed line across, two pixels on, one off. */
    private static void dashed(Canvas c, int xa, int xb, int y, int colour) {
        for (int x = xa; x <= xb; x += 3) c.hLine(x, Math.min(x + 1, xb), y, colour);
    }

    /** Two nested U-tubes from {@code from} to {@code end}, their legs {@code outer} and {@code inner} of the height in from each edge. */
    private static void uTubes(FlowsheetPainter p, int from, int end, int y0, int y1, double outer, double inner, int colour) {
        var c = p.canvas();
        int h = y1 - y0, step = Math.max(2, p.px(5));
        double[] legs = {outer, inner};
        for (int i = 0; i < 2; i++) {
            int top = y0 + (int) Math.round(h * legs[i]), bottom = y1 - 1 - (int) Math.round(h * legs[i]), right = end - i * step;
            if (bottom <= top) continue;
            c.hLine(from, right, top, colour);
            c.hLine(from, right, bottom, colour);
            c.fill(right, top, right + 1, bottom + 1, colour);
        }
    }

    /** A shell-and-tube shell (TEMA U-tube): the tubesheet at the channel, the U-tube bundle, segmental baffles, the dished rear head. */
    private static void shell(FlowsheetPainter p, int x0, int y0, int x1, int y1, Look look) {
        var c = p.canvas();
        int h = y1 - y0, head = Math.max(1, h / 4), sheet = Math.max(1, p.px(3)), end = x1 - head - Math.max(1, p.px(4));
        p.vessel(x0, y0, x1, y1, 0, head, look);
        // Baffles cut a quarter, alternately from the top and the bottom; the tubes pass through them.
        int span = end - x0 - sheet, baffles = Math.clamp(span / Math.max(4, p.px(14)), 2, 7);
        for (int k = 1; k <= baffles; k++) {
            int bx = x0 + sheet + span * k / (baffles + 1);
            boolean top = k % 2 == 1;
            c.fill(bx, top ? y0 + 1 : y0 + h / 4, bx + 1, top ? y1 - h / 4 : y1 - 1, PLATE);
        }
        uTubes(p, x0 + sheet, end, y0, y1, 0.2, 0.36, TUBE);
        c.fill(x0, y0, x0 + sheet, y1, look.stroke());
    }

    /** A channel bonnet: its dished end, the flange to the shell, the pass partition between the tubes' inlet and outlet. */
    private static void bonnet(FlowsheetPainter p, int x0, int y0, int x1, int y1, Look look) {
        var c = p.canvas();
        int depth = Math.max(1, (x1 - x0) * 2 / 5), flange = Math.max(1, p.px(2));
        p.vessel(x0, y0, x1, y1, depth, 0, look);
        c.hLine(x0 + depth / 2, x1 - 1, (y0 + y1) / 2, PLATE);
        c.fill(x1 - flange, y0 - flange, x1, y1 + flange, look.stroke());
    }

    /** A saddle under the shell: its cradle, web and base plate. */
    private static void saddle(Canvas c, int x0, int y0, int x1, int y1, Look look) {
        int w = x1 - x0, t = Math.max(1, (y1 - y0) / 5);
        c.fill(x0, y0, x1, y0 + t, look.stroke());
        c.fill(x0 + w / 3, y0, x1 - w / 3, y1, look.stroke());
        c.fill(x0 - 1, y1 - t, x1 + 1, y1, look.stroke());
    }

    /**
     * A kettle reboiler's shell: a cone up from the channel's diameter to the drum's, the dished rear head; the U-tube
     * bundle low in the pool, the weir that holds the pool over it, the liquid beyond the weir over the liquid outlet, and
     * bubbles rising from the bundle.
     */
    private static void kettle(FlowsheetPainter p, int x0, int y0, int x1, int y1, Look look) {
        var c = p.canvas();
        int w = x1 - x0, h = y1 - y0, cone = Math.max(2, w * 12 / 100), head = Math.max(1, h / 4);
        // The channel (HeatDiagram's kettle: 110 to 160 of the drum's 90 to 180) meets the cone's small end.
        int c0 = y0 + h * 2 / 9, c1 = y1 - h * 2 / 9;
        int weir = x0 + w * 79 / 100, level = y0 + h * 47 / 100, beyond = y0 + h * 72 / 100, bundleEnd = x0 + w * 72 / 100;
        for (int yy = y0; yy < y1; yy++) {
            int left = yy < c0 ? x0 + (int) Math.round(cone * (c0 - yy) / (double) (c0 - y0)) : yy >= c1 ? x0 + (int) Math.round(cone * (yy - c1 + 1) / (double) (y1 - c1)) : x0;
            double t = Math.min(1, Math.abs(yy + 0.5 - (y0 + h / 2.0)) / (h / 2.0));
            int right = x1 - 1 - (int) Math.round(head * (1 - Math.sqrt(Math.max(0, 1 - t * t))));
            c.hLine(left, right, yy, look.fill());
            if (yy > level) c.hLine(left + 1, Math.min(right - 1, weir - 1), yy, POOL);
            if (yy > beyond) c.hLine(weir + 1, right - 1, yy, POOL);
            c.fill(right, yy, right + 1, yy + 1, look.stroke());
            if (yy >= c0 && yy < c1) c.fill(x0, yy, x0 + 1, yy + 1, look.stroke());
        }
        p.line(x0, c0, x0 + cone, y0, look.stroke());
        p.line(x0, c1 - 1, x0 + cone, y1 - 1, look.stroke());
        c.hLine(x0 + cone, x1 - 1 - head / 2, y0, look.stroke());
        c.hLine(x0 + cone, x1 - 1 - head / 2, y1 - 1, look.stroke());
        dashed(c, x0 + cone / 2 + 2, weir - 1, level, LEVEL);
        dashed(c, weir + 2, x1 - head - 1, beyond, LEVEL);
        c.fill(weir, level + 1, weir + Math.max(1, p.px(2)), y1 - 1, PLATE);
        uTubes(p, x0 + 1, bundleEnd, c0, y1, 0.42, 0.56, TUBE);
        // Bubbles over the bundle, rising to the level.
        int bundleTop = c0 + (int) Math.round((y1 - c0) * 0.42);
        for (int k = 0; k < 7; k++) {
            int bx = x0 + cone + (bundleEnd - x0 - cone) * (2 * k + 1) / 14, by = level + 2 + (k * 5 % 3) * Math.max(1, (bundleTop - level - 3) / 3);
            if (by < bundleTop) c.fill(bx, by, bx + 1, by + 1, BUBBLE);
        }
        c.fill(x0, c0, x0 + Math.max(1, p.px(3)), c1, look.stroke());
    }

    /** An air cooler's bundle: plug headers standing proud at both ends, finned tubes across between them. */
    private static void bundle(FlowsheetPainter p, int x0, int y0, int x1, int y1, Look look) {
        var c = p.canvas();
        int hw = Math.max(2, (x1 - x0) * 7 / 100), proud = Math.max(1, p.px(4));
        p.box(x0 + hw - 1, y0 + proud, x1 - hw + 1, y1 - proud, look);
        var header = new Look(DARK, look.stroke(), look.text());
        p.box(x0, y0, x0 + hw, y1, header);
        p.box(x1 - hw, y0, x1, y1, header);
        for (int yy = y0 + 2; yy < y1 - 2; yy += 3) {
            c.fill(x0 + hw / 2, yy, x0 + hw / 2 + 1, yy + 1, PLATE);
            c.fill(x1 - hw / 2 - 1, yy, x1 - hw / 2, yy + 1, PLATE);
        }
        int inner = y1 - y0 - 2 * proud, rows = Math.clamp(inner / 5, 2, 5);
        for (int i = 1; i <= rows; i++) {
            int ty = y0 + proud + inner * i / (rows + 1);
            for (int fx = x0 + hw + 1; fx < x1 - hw - 1; fx += 2) c.fill(fx, ty - 1, fx + 1, ty + 2, FIN);
            c.hLine(x0 + hw, x1 - hw - 1, ty, TUBE);
        }
    }

    /** A forced-draft fan: its ring under the plenum, the blades on their hub, the shaft down to the motor. */
    private static void fan(FlowsheetPainter p, int x0, int y0, int x1, int y1, Look look) {
        var c = p.canvas();
        int w = x1 - x0, h = y1 - y0, cx = (x0 + x1) / 2, ring = Math.max(3, h * 22 / 100), motor = Math.max(3, h * 28 / 100);
        p.box(x0, y0, x1, y0 + ring, new Look(DARK, look.stroke(), look.text()));
        int by = y0 + ring / 2;
        p.line(x0 + 2, by + 1, cx, by, PLATE);
        p.line(cx, by, x1 - 3, by - 1, PLATE);
        c.fill(cx - 1, by - 1, cx + 2, by + 2, look.stroke());
        c.fill(cx, y0 + ring, cx + 1, y1 - motor, TUBE);
        int mw = Math.max(3, w * 2 / 5);
        p.box(cx - mw / 2, y1 - motor, cx - mw / 2 + mw, y1 - 1, look);
        for (int yy = y1 - motor + 2; yy < y1 - 2; yy += 2) c.hLine(cx - mw / 2 + 1, cx - mw / 2 + mw - 2, yy, FIN);
        c.hLine(cx - mw / 2 - 1, cx - mw / 2 + mw, y1 - 1, look.stroke());
    }

    /** The stack: the breeching off the convection section, the stack with its lip, the damper across it on its spindle. */
    private static void stack(FlowsheetPainter p, int x0, int y0, int x1, int y1, Look look) {
        var c = p.canvas();
        int w = x1 - x0, h = y1 - y0, inset = w * 3 / 10, neck = y0 + h * 7 / 10, cx = (x0 + x1) / 2;
        p.box(x0 + inset, y0 + Math.max(1, p.px(2)), x1 - inset, neck + 1, look);
        c.hLine(x0 + inset - 1, x1 - inset, y0 + Math.max(1, p.px(2)), look.stroke());
        p.trapezoid(x0, neck, x1, y1, inset, 0, look);
        int dy = y0 + h * 2 / 5;
        p.line(x0 + inset + 1, dy + 1, x1 - inset - 2, dy - 1, PLATE);
        c.fill(cx, dy, cx + 1, dy + 1, look.stroke());
    }

    /** The convection section: rows of tubes seen end on, staggered, the bottom (shock) row bare, the others finned; the return-bend headers at both ends. */
    private static void convection(FlowsheetPainter p, int x0, int y0, int x1, int y1, Look look) {
        var c = p.canvas();
        p.box(x0, y0, x1, y1, look);
        int hw = Math.max(2, p.px(5));
        c.fill(x0 + 1, y0 + 1, x0 + hw, y1 - 1, DARK);
        c.fill(x1 - hw, y0 + 1, x1 - 1, y1 - 1, DARK);
        int rows = Math.clamp((y1 - y0) / 5, 2, 6), pitch = Math.max(3, p.px(10)), left = x0 + hw + 2, right = x1 - hw - 3;
        for (int r = 0; r < rows; r++) {
            int ty = y1 - 4 - r * (y1 - y0 - 6) / Math.max(1, rows - 1);
            boolean shock = r == 0;
            for (int tx = left + (r % 2) * pitch / 2; tx <= right; tx += pitch) {
                if (!shock) c.fill(tx - 1, ty - 1, tx + 2, ty + 2, FIN);
                c.fill(tx, ty, tx + 1, ty + 1, shock ? PLATE : TUBE);
            }
        }
    }

    /**
     * The radiant firebox of a vertical cylindrical heater: its refractory lining, the coil up and down its walls with the
     * return bends, and the burners' flames rising off the floor.
     */
    private static void radiant(FlowsheetPainter p, int x0, int y0, int x1, int y1, Look look) {
        var c = p.canvas();
        p.box(x0, y0, x1, y1, look);
        int lining = Math.max(1, p.px(4));
        c.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, REFRACTORY);
        c.fill(x0 + 1 + lining, y0 + 1 + lining, x1 - 1 - lining, y1 - 1 - lining, FIREBOX);
        // The wall coil: three tubes up and down each wall, joined by return bends at alternate ends.
        int step = Math.max(2, p.px(5)), top = y0 + lining + Math.max(1, p.px(4)), bottom = y1 - lining - Math.max(1, p.px(3));
        for (int side = 0; side < 2; side++)
            for (int i = 0; i < 3; i++) {
                int tx = side == 0 ? x0 + 2 + lining + i * step : x1 - 3 - lining - i * step;
                c.fill(tx, top, tx + 1, bottom, TUBE);
                if (i < 2) {
                    int nx = side == 0 ? tx + step : tx - step, yy = i % 2 == 0 ? bottom - 1 : top;
                    c.hLine(Math.min(tx, nx), Math.max(tx, nx), yy, TUBE);
                }
            }
        // The flames over the burners: a teardrop 42 % of the firebox high, a hot core and a blue root.
        int floor = y1 - 1 - lining, tall = Math.max(4, (y1 - y0) * 42 / 100), half = Math.max(1, p.px(7));
        for (int bx : HeatDiagram.HEATER_BURNERS) {
            int fx = p.x(bx);
            for (int k = 0; k < tall; k++) {
                double t = k / (double) tall, shape = t < 0.25 ? 0.55 + 1.8 * t : (1 - t) / 0.75;
                int wHalf = (int) Math.round(half * Math.max(0, shape)), yy = floor - 1 - k;
                if (wHalf == 0 && t > 0.9) continue;
                c.hLine(fx - wHalf, fx + wHalf, yy, FLAME);
                if (t < 0.7) c.hLine(fx - wHalf / 2, fx + wHalf / 2, yy, k < 2 ? FLAME_BASE : FLAME_CORE);
            }
        }
    }

    /** A burner under the floor: its tile in the floor, its air register, the gas line into it. */
    private static void burner(Canvas c, int x0, int y0, int x1, int y1, Look look) {
        int w = x1 - x0, h = y1 - y0, tile = Math.max(1, h * 35 / 100), register = Math.max(2, h * 35 / 100), cx = (x0 + x1) / 2;
        c.fill(x0 + w / 5, y0, x1 - w / 5, y0 + tile, look.stroke());
        c.fill(x0, y0 + tile, x1, y0 + tile + register, DARK);
        c.outline(x0, y0 + tile, w, register, look.stroke());
        c.fill(cx, y0 + tile + register, cx + 1, y1, TUBE);
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
