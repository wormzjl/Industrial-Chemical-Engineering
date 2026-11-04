package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.PortColours;
import com.wormzjl.icheme.client.gui.common.HeatControlColumn;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter;
import com.wormzjl.icheme.client.gui.common.kit.FlowsheetPainter.Look;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.assembly.ColumnBlueprint;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import java.util.List;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * Draws a sheet on the canvas (batch 2026-11-02-process-simulator, W6_SCREEN_PLAN.md section 2): the grid, lines, symbols, ports, names, pills,
 * tears, rings and a gesture's overlay. It only reads, so the screen and the render tool draw the same picture. Numbers follow the approved
 * prototypes: lines muted, a torn one in the accent, the selected one white; a port grey once a stream holds it.
 */
public final class SheetPainter {
    public static final Look NONE = new Look(FlowsheetPainter.BODY, Palette.LINE, Palette.TEXT), ACTIVE = new Look(FlowsheetPainter.BODY, Palette.TEXT, Palette.TEXT);
    /** A tile with a process model, and one with none; the carried ghost. */
    public static final Look LIT = new Look(FlowsheetPainter.BODY, Palette.ACCENT, Palette.TEXT), UNLIT = new Look(0xFF20292F, 0xFF46545C, 0xFF72828C), GHOST = new Look(0x6033434D, Palette.TEXT, Palette.TEXT);
    static final int PLATE = 0xFF10181D;
    private static final int GRID = 0x24FFFFFF, WHITE = 0xFFFFFFFF, HATCH = 0xFF7A2A2A, DESIGN_GRID = 16;
    /** A column's frame, its fill, and the colour of what runs hot inside it. */
    static final int FRAME = 0xFF7F97A5;
    private static final int FRAME_FILL = 0xFF1A252C, HOT = 0xFFE8A23C;

    private SheetPainter() {
    }

    public static Look look(SheetState.Tone tone) {
        return switch (tone) {
            case SOLVED -> Look.TEAL;
            case WARNING -> Look.YELLOW;
            case FAILED -> Look.RED;
            case NONE -> NONE;
            case ACTIVE -> ACTIVE;
        };
    }

    /** Draws it all, clipped to the view's box, with no stream's conditions. */
    public static void draw(Canvas c, SheetLayout layout, SheetState state, SheetView view, SheetGestures.@Nullable Selection selected, SheetGestures.@Nullable Overlay overlay, HeatControlColumn.Texts t) {
        draw(c, layout, state, view, selected, overlay, t, null);
    }

    /**
     * Draws it all, clipped to the view's box; with {@code conditions}, the lines it gives a stream (its T and P; the owner,
     * 2026-11-04: "Add a button to show/hide boundary conditions on the GUI (P, T)", picked every stream) under its name, or under
     * its pill where the pill says the name. A stream whose lines would cover a label, a pill or another stream's lines shows none of them.
     */
    public static void draw(Canvas c, SheetLayout layout, SheetState state, SheetView view, SheetGestures.@Nullable Selection selected, SheetGestures.@Nullable Overlay overlay, HeatControlColumn.Texts t,
                            @Nullable Function<Flowsheet.Stream, @Nullable List<String>> conditions) {
        var box = view.box();
        c.clip(box);
        grid(c, view);
        var p = new FlowsheetPainter(c, (int) Math.round(view.canvasX(0)), (int) Math.round(view.canvasY(0)), view.zoom(), 1);
        String hidden = overlay == null ? null : overlay.hidden();
        for (var line : layout.lines()) {
            if (line.stream().id().equals(hidden) || line.points().length < 4) continue;
            p.wire(line.points(), false, false, colour(line, state, selected));
        }
        for (var box1 : layout.boxes()) unit(p, layout, box1, state, selected, t);
        for (var box1 : layout.boxes())
            for (var place : box1.places()) {
                boolean free = overlay != null && overlay.freed() != null && overlay.freed().equals(place);
                // Too small for a name, a held port's square would hide the unit; its line already shows it.
                if (place.held() && !free && !p.labels()) continue;
                p.square(place.x(), place.y(), SheetLayout.PORT, place.held() && !free ? PortColours.CONNECTED : place.rgb());
            }
        // One head a line, its tip on the edge of the inlet's square (the owner, 2026-11-04: "Add directional arrow to all lines",
        // picked C), or of the open end's pill, whose edge is the line's last point.
        c.batch(() -> {
            for (var line : layout.lines()) {
                if (line.stream().id().equals(hidden) || line.points().length < 4) continue;
                int back = line.end() == null && p.labels() ? Math.max(2, p.px(SheetLayout.PORT / 2)) + 2 : 1;
                p.head(line.points(), back, colour(line, state, selected));
            }
        });
        if (selected != null && !selected.unit()) ends(p, layout.line(selected.id()));
        for (var line : layout.lines()) {
            if (line.stream().id().equals(hidden) || !line.hasName() || !p.labels()) continue;
            boolean tear = state.tear(line.stream().id());
            p.tag(line.stream().name(), line.nameX(), line.nameY(), .5f, tear ? Palette.ACCENT : Palette.MUTED, PLATE);
        }
        for (var line : layout.lines()) {
            if (state.tear(line.stream().id())) tear(p, line.midX(), line.midY());
            pill(p, line.start(), selected);
            pill(p, line.end(), selected);
        }
        if (conditions != null && p.labels()) conditions(p, layout, hidden, conditions);
        if (overlay != null) overlay(p, overlay, t);
        c.unclip();
    }

    /** Each stream's lines under its name or its pill, after every pill is held against them; one batch. */
    private static void conditions(FlowsheetPainter p, SheetLayout layout, @Nullable String hidden, Function<Flowsheet.Stream, @Nullable List<String>> conditions) {
        for (var line : layout.lines())
            for (var pill : new SheetLayout.Pill[]{line.start(), line.end()}) {
                if (pill == null) continue;
                var r = pill.rect();
                p.take(new Rect(p.x(r.x()), p.y(r.y()), p.x(r.right()) - p.x(r.x()), p.y(r.bottom()) - p.y(r.y())));
            }
        float h = p.lineHeight();
        var placed = new java.util.ArrayList<FlowsheetPainter.Placed>();
        for (var line : layout.lines()) {
            if (line.stream().id().equals(hidden)) continue;
            var pill = line.start() != null ? line.start() : line.end();
            if (!line.hasName() && pill == null) continue;
            var lines = conditions.apply(line.stream());
            if (lines == null) continue;
            // Under the name, a line down for each; under the pill, from its bottom edge, half a line down to the first's middle.
            int x = line.hasName() ? line.nameX() : pill.x(), y = line.hasName() ? line.nameY() : pill.y();
            float first = line.hasName() ? h : p.px(SheetLayout.PILL_HALF) + h / 2 + 1;
            placed.addAll(p.place(lines, x, y, .5f, first));
        }
        // The plates, then the texts: a fill run and a text run.
        p.canvas().batch(() -> {
            for (var at : placed) at.plate(p.canvas(), PLATE);
            for (var at : placed) at.write(p.canvas(), Palette.MUTED);
        });
    }

    /** A line's colour: white where selected, the accent where torn. */
    private static int colour(SheetLayout.Line line, SheetState state, SheetGestures.@Nullable Selection selected) {
        boolean mine = selected != null && !selected.unit() && selected.id().equals(line.stream().id());
        return mine ? WHITE : state.tear(line.stream().id()) ? Palette.ACCENT : Palette.MUTED;
    }

    /** A selected line's ends on their ports, ringed. */
    private static void ends(FlowsheetPainter p, SheetLayout.@Nullable Line line) {
        if (line == null || line.points().length < 4) return;
        int[] q = line.points();
        if (line.start() == null) p.canvas().outline(p.x(q[0]) - 4, p.y(q[1]) - 4, 9, 9, WHITE);
        if (line.end() == null) p.canvas().outline(p.x(q[q.length - 2]) - 4, p.y(q[q.length - 1]) - 4, 9, 9, WHITE);
    }

    private static void grid(Canvas c, SheetView view) {
        grid(c, view.box(), view.zoom(), (int) Math.round(view.canvasX(0)), (int) Math.round(view.canvasY(0)));
    }

    /** The sheet's dotted grid on {@code box} at {@code zoom}, its design origin at canvas point ({@code ox}, {@code oy}): one draw call. */
    static void grid(Canvas c, Rect box, double zoom, int ox, int oy) {
        int step = Math.max(8, (int) Math.round(DESIGN_GRID * zoom));
        int dx = Math.floorMod(ox - box.x() + step / 2, step), dy = Math.floorMod(oy - box.y() + step / 2, step);
        c.batch(() -> {
            for (int y = box.y() + dy; y < box.bottom(); y += step)
                for (int x = box.x() + dx; x < box.right(); x += step) c.fill(x, y, x + 1, y + 1, GRID);
        });
    }

    private static void unit(FlowsheetPainter p, SheetLayout layout, SheetLayout.Box box, SheetState state, SheetGestures.@Nullable Selection selected, HeatControlColumn.Texts t) {
        var r = box.rect();
        var tone = state.tone(box.unit().id());
        var look = look(tone);
        if (box.placeholder()) placeholder(p, box, t);
        else if (box.column()) block(p, box, tone, look, t);
        else sprite(p.canvas(), box.symbol(), p.ox() + r.x() * p.scale(), p.oy() + r.y() * p.scale(), r.w() * p.scale(), r.h() * p.scale(), look);
        boolean ringed = tone == SheetState.Tone.ACTIVE || selected != null && selected.unit() && selected.id().equals(box.unit().id());
        if (ringed) p.canvas().outline(p.x(r.x()) - 3, p.y(r.y()) - 3, p.px(r.w()) + 6, p.px(r.h()) + 6, Palette.TEXT);
        if (!p.labels()) return;
        for (var mark : box.marks()) p.tag(t.get("flowsheet." + mark.key(), mark.argument()), mark.x(), mark.y(), .5f, Palette.TEXT, PLATE);
        if (box.column()) {
            int room = Math.max(2, p.px(5)), x0 = p.x(box.tagX()) - room, y0 = p.y(box.tagY() - 6), w = (int) Math.ceil(p.canvas().measure().width(box.unit().name()) * p.fontScale()) + 2 * room, h = Math.max(4, p.px(12));
            p.canvas().fill(x0, y0, x0 + w, y0 + h, PLATE);
            p.canvas().outline(x0, y0, w, h, frame(tone, look));
            p.tag(box.unit().name(), box.tagX(), box.tagY(), 0f, tone == SheetState.Tone.WARNING ? Palette.WARN : Palette.TEXT, 0);
        } else p.tag(box.unit().name(), box.tagX(), box.tagY(), box.tagAnchor(), tone == SheetState.Tone.WARNING ? Palette.WARN : Palette.TEXT, PLATE);
    }

    private static int frame(SheetState.Tone tone, Look look) {
        return tone == SheetState.Tone.WARNING || tone == SheetState.Tone.FAILED ? look.stroke() : FRAME;
    }

    /**
     * A column's frame and what it holds (decision D23). Amber and teal are a solved block's; any other tone takes them all, so a greyed or failed block reads as one. Too small for labels, the frame, the
     * tower, its coolers, drum, pumps and pipes stay; valves, a pump's motor, a cooler's coil, the tee and arrows go, for they would only blot.
     */
    private static void block(FlowsheetPainter p, SheetLayout.Box box, SheetState.Tone tone, Look look, HeatControlColumn.Texts t) {
        block(p, box.block(), frame(tone, look), tone == SheetState.Tone.SOLVED, look, slot -> look, t);
    }

    /**
     * A column block on its own, each part in the look {@code looks} gives its slot and the tower in the column's (N1: the assembly
     * screen draws the built column as the sheet does, each part by its check). The frame in {@code frame}; amber and teal lines
     * where {@code solved}, else in {@code look}'s stroke.
     */
    public static void block(FlowsheetPainter p, ColumnBlock block, int frame, boolean solved, Look look, java.util.function.Function<com.wormzjl.icheme.science.assembly.Blueprint.Slot, Look> looks,
                             HeatControlColumn.Texts t) {
        var c = p.canvas();
        var f = block.frame();
        int x0 = p.x(f.x()), y0 = p.y(f.y()), x1 = p.x(f.right()), y1 = p.y(f.bottom());
        c.fill(x0, y0, x1, y1, FRAME_FILL);
        c.outline(x0, y0, x1 - x0, y1 - y0, frame);
        boolean fine = p.labels();
        for (var pipe : block.pipes()) {
            int colour = switch (pipe.service()) {
                case HOT -> solved ? HOT : look.stroke();
                case COLD -> solved ? Palette.ACCENT : look.stroke();
                case PRODUCT -> Palette.MUTED;
            };
            p.wire(pipe.points(), pipe.arrow() && fine, false, colour);
        }
        var half = new FlowsheetPainter(c, p.ox(), p.oy(), p.scale() / 2, 1);
        var tower = block.tower();
        column(c, p.ox() + tower.x() * p.scale(), p.oy() + tower.y() * p.scale(), p.scale(), block.slices(), looks.apply(ColumnBlueprint.COLUMN));
        int hot = solved ? HOT : look.stroke();
        for (var part : block.parts()) part(p, half, part, looks.apply(part.slot()), fine, part.slot().equals(ColumnBlueprint.REFLUX_VALVE) ? hot : Palette.MUTED);
        if (!fine) return;
        for (var note : block.notes()) {
            String text = t.get("flowsheet." + note.key(), note.argument());
            if (note.width() > 0) p.fitted(text, note.x(), note.y(), note.width(), Palette.TEXT);
            else p.tag(text, note.x(), note.y(), .5f, Palette.MUTED, 0);
        }
    }

    /** A part; a valve too small to draw leaves its line whole, in {@code line}'s colour. */
    private static void part(FlowsheetPainter p, FlowsheetPainter half, ColumnBlock.Part part, Look look, boolean fine, int line) {
        var c = p.canvas();
        var r = part.rect();
        int x = p.x(part.x()), y = p.y(part.y());
        switch (part.shape()) {
            case TOWER -> {
            }
            case COOLER, CONDENSER -> {
                int cy = p.y(r.y() + r.h() / 2), radius = p.px(8);
                p.circle(x, cy, radius, look);
                if (!fine) return;
                int a = p.px(4), b = p.px(3), d = p.px(7);
                if (part.shape() == ColumnBlock.Shape.COOLER) {
                    p.line(x, cy - radius, x - a, cy - b, Palette.TEXT);
                    p.line(x - a, cy - b, x + a, cy + b, Palette.TEXT);
                    p.line(x + a, cy + b, x, cy + radius, Palette.TEXT);
                } else {
                    p.line(x - a, cy - d, x - a, cy + b, Palette.TEXT);
                    p.line(x - a, cy + b, x + a, cy + b, Palette.TEXT);
                    p.line(x + a, cy + b, x + a, cy - d, Palette.TEXT);
                }
            }
            case AIR_CONDENSER -> sprite(c, Symbol.AIR_COOLER, p.ox() + r.x() * p.scale(), p.oy() + r.y() * p.scale(), r.w() * p.scale(), r.h() * p.scale(), look);
            case EJECTOR -> sprite(c, Symbol.EJECTOR, p.ox() + r.x() * p.scale(), p.oy() + r.y() * p.scale(), r.w() * p.scale(), r.h() * p.scale(), look);
            case DRUM -> {
                p.box(p.x(part.x() - 4), p.y(r.bottom() - 2), p.x(part.x() + 4), p.y(part.y() + 4), look);
                p.vessel(p.x(r.x()), p.y(r.y()), p.x(r.right()), p.y(r.bottom()), p.px(5), p.px(5), look);
            }
            case KETTLE -> p.vessel(p.x(r.x()), p.y(r.y()), p.x(r.right()), p.y(r.bottom()), p.px(5), p.px(5), look);
            case PUMP_DOWN, PUMP_ACROSS -> {
                if (fine && part.shape() == ColumnBlock.Shape.PUMP_DOWN) c.fill(x + p.px(5), y - p.px(2), x + p.px(9), y + p.px(2), look.stroke());
                else if (fine) c.fill(x - p.px(2), y + p.px(5), x + p.px(2), y + p.px(9), look.stroke());
                p.circle(x, y, p.px(5), look);
            }
            case INLINE_PUMP -> p.circle(x, y, p.px(4), look);
            case TEE -> {
                if (fine) p.tee(x - p.px(2), y - p.px(2), x + p.px(2), y + p.px(2), look);
            }
            case VALVE -> {
                if (fine) half.valve(x, y, look);
                else p.wire(new int[]{part.x() - 4, part.y(), part.x() + 4, part.y()}, false, false, line);
            }
            case VALVE_DOWN -> {
                if (fine) half.valveDown(x, y, look);
                else p.wire(new int[]{part.x(), part.y() - 4, part.x(), part.y() + 4}, false, false, line);
            }
        }
    }

    private static void placeholder(FlowsheetPainter p, SheetLayout.Box box, HeatControlColumn.Texts t) {
        var c = p.canvas();
        var r = box.rect();
        int x0 = p.x(r.x()), y0 = p.y(r.y()), x1 = p.x(r.right()), y1 = p.y(r.bottom()), pw = x1 - x0, ph = y1 - y0;
        p.box(x0, y0, x1, y1, Look.RED);
        c.clip(new Rect(x0 + 1, y0 + 1, pw - 2, ph - 2));
        c.batch(() -> {
            for (int k = -ph; k < pw; k += 8) p.line(x0 + k, y1, x0 + k + ph, y0, HATCH);
        });
        c.unclip();
        if (!p.labels()) return;
        p.fitted(Tiles.typeName(box.unit().type()), r.x() + r.w() / 2, r.y() + r.h() / 2 - 6, r.w(), Look.RED.text());
        p.fitted(t.get("flowsheet.removed_mark"), r.x() + r.w() / 2, r.y() + r.h() / 2 + 6, r.w(), Look.RED.text());
    }

    /** A symbol's masks over its box from canvas point ({@code x}, {@code y}): the body in the look's fill, the marks in its stroke, a fire in its own colour. */
    public static void sprite(Canvas c, Symbol s, double x, double y, double w, double h, Look look) {
        double sx = w / s.w, sy = h / s.h;
        float x0 = (float) (x - Symbol.MARGIN * sx), x1 = (float) (x + w + Symbol.MARGIN * sx), y0 = (float) (y - s.top() * sy), y1 = (float) (y + h + s.foot() * sy);
        for (var layer : s.layers()) {
            c.sprite(s.sprite(layer), x0, y0, x1, y1, tint(layer, look));
            // Below its own size a stroke thins under a pixel and fades: a second pass holds its colour.
            if (layer == Symbol.Layer.MARKS && sx < .9) c.sprite(s.sprite(layer), x0, y0, x1, y1, tint(layer, look));
        }
    }

    private static int tint(Symbol.Layer layer, Look look) {
        return switch (layer) {
            case BODY -> look.fill();
            case MARKS -> look.stroke();
            case HOT -> Symbol.FLAME;
        };
    }

    /** A column of {@code slices} tray slices, from canvas point ({@code x}, {@code y}): its head, its trays, its foot. */
    public static void column(Canvas c, double x, double y, double scale, int slices, Look look) {
        column(c, x, y, scale, scale, slices, look);
    }

    /** A column in a tile's box: its slices chosen so its shape fits the box, stretched the rest of the way. */
    public static void columnTile(Canvas c, int x, int y, int w, int h, Look look) {
        double scale = (double) w / Symbol.COLUMN_TOP.w;
        int slices = Math.max(1, (int) Math.round((h / scale - 30) / 16));
        column(c, x, y, scale, h / (30.0 + 16 * slices), slices, look);
    }

    private static void column(Canvas c, double x, double y, double sx, double sy, int slices, Look look) {
        double at = y;
        sprite(c, Symbol.COLUMN_TOP, x, at, Symbol.COLUMN_TOP.w * sx, Symbol.COLUMN_TOP.h * sy, look);
        at += Symbol.COLUMN_TOP.h * sy;
        for (int i = 0; i < slices; i++) {
            sprite(c, Symbol.COLUMN_TRAY, x, at, Symbol.COLUMN_TRAY.w * sx, Symbol.COLUMN_TRAY.h * sy, look);
            at += Symbol.COLUMN_TRAY.h * sy;
        }
        sprite(c, Symbol.COLUMN_FOOT, x, at, Symbol.COLUMN_FOOT.w * sx, Symbol.COLUMN_FOOT.h * sy, look);
    }

    /** A pill: a feed's in the accent, the selected one ringed. */
    private static void pill(FlowsheetPainter p, SheetLayout.@Nullable Pill pill, SheetGestures.@Nullable Selection selected) {
        if (pill == null) return;
        var c = p.canvas();
        var r = pill.rect();
        p.pill(r.x(), pill.y(), r.w(), SheetLayout.PILL_HALF);
        int x0 = p.x(r.x()), y0 = p.y(r.y()), w = p.x(r.right()) - x0, h = p.y(r.bottom()) - y0;
        if (pill.feed()) c.outline(x0, y0, w, h, Palette.ACCENT);
        p.fitted(pill.text(), pill.x(), pill.y(), r.w(), pill.feed() ? Palette.ACCENT : pill.lost() ? Palette.WARN : Palette.TEXT);
        if (selected != null && !selected.unit() && selected.id().equals(pill.stream())) c.outline(x0 - 3, y0 - 3, w + 6, h + 6, WHITE);
    }

    /** The loop's tear: a small diamond with a T on its line. */
    private static void tear(FlowsheetPainter p, int x, int y) {
        var c = p.canvas();
        int cx = p.x(x), cy = p.y(y), r = Math.max(4, p.px(7));
        c.batch(() -> {
            for (int dy = -r; dy <= r; dy++) c.hLine(cx - (r - Math.abs(dy)), cx + (r - Math.abs(dy)), cy + dy, Palette.ACCENT);
        });
        if (p.labels()) c.text("T", cx - 2, cy - 3, 0xFF0B1216);
    }

    private static void overlay(FlowsheetPainter p, SheetGestures.Overlay overlay, HeatControlColumn.Texts t) {
        var c = p.canvas();
        rings(p, overlay.rings());
        if (overlay.wire() != null) {
            int[] q = overlay.wire();
            p.wire(q, false, false, WHITE);
            c.outline(p.x(q[q.length - 2]) - 3, p.y(q[q.length - 1]) - 3, 7, 7, WHITE);
            if (overlay.name() != null) p.tag(overlay.name(), (q[0] + q[2]) / 2, q[1] - 8, .5f, WHITE, PLATE);
        }
        var tile = overlay.tile();
        if (tile == null) return;
        int w = tile.symbol().w, h = tile.type().equals("column") ? 44 : tile.symbol().h;
        double x = p.ox() + (overlay.x() - w / 2.0) * p.scale(), y = p.oy() + (overlay.y() - h / 2.0) * p.scale();
        if (tile.type().equals("column")) columnTile(c, (int) x, (int) y, p.px(16), p.px(44), GHOST);
        else sprite(c, tile.symbol(), x, y, w * p.scale(), h * p.scale(), GHOST);
        p.tag(overlay.name() == null ? t.get(tile.name()) : overlay.name(), overlay.x(), overlay.y() - h / 2 - 7, .5f, Palette.TEXT, PLATE);
    }

    private static void rings(FlowsheetPainter p, List<SheetLayout.Place> rings) {
        for (var place : rings) p.canvas().outline(p.x(place.x()) - 7, p.y(place.y()) - 7, 15, 15, WHITE);
    }
}
