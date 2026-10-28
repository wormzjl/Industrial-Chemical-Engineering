package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * The small dialogs on the GUI kit (batch 2026-10-28-gui-toolkit, GUI_REVIEW.md order 2, GUI_TOOLKIT_PLAN.md section
 * 15): the gauge's, the pipeline tools' and the vessel datasheet's door columns, and the datasheet's glass. Each dialog is
 * a panel door: its title at (10, 10), its column from {@link #TOP}, its push buttons on the footer. The rows with widgets
 * come first and a warning line last, so the widgets keep their places while the player types. Texts are keys under
 * {@code gui.icheme.}. No Minecraft classes.
 */
public final class DialogColumns {
    private DialogColumns() {
    }

    /** The column's top, the room the footer takes under the column (the push buttons at height - 27), and the glass's line pitch. */
    public static final int TOP = 28, FOOTER = 35, PITCH = 11;

    /** The footer's push buttons: two share the width from 10 to width - 10, 4 px apart; one (Done) is 80 px at the right. */
    public static List<Rect> footer(int width, int height, int count) {
        if (count == 1) return List.of(new Rect(width - 90, height - 27, 80, 18));
        int w = (width - 20 - 4 * (count - 1)) / count;
        var out = new ArrayList<Rect>();
        for (int i = 0; i < count; i++) out.add(new Rect(10 + i * (w + 4), height - 27, w, 18));
        return List.copyOf(out);
    }

    /** A selector's position: latched where it stands, else a key that moves it there. */
    private static Column.Button position(String key, String text, boolean current, @Nullable String hint) {
        return new Column.Button(key, text, !current, hint, current);
    }

    // ---- The gauge ----

    /** A gauge: whether it reads pressure; its kind's unit symbols and the shown one's index; gauge or absolute; whether the drafts form a range. */
    public record Gauge(boolean pressure, List<String> units, int unit, boolean relative, boolean valid) {
        public Gauge {
            units = List.copyOf(units);
            if (unit < 0 || unit >= units.size()) throw new IllegalArgumentException("No unit " + unit);
        }
    }

    /**
     * The gauge's settings: the Range (its minimum and maximum in the dial's unit, the unit's selector), a pressure
     * gauge's Reading (gauge or absolute), then the warning while the drafts form no range. Keys: the fields
     * {@code minimum} and {@code maximum}; the buttons {@code unit:<index>}, {@code reading:gauge} and
     * {@code reading:absolute}.
     */
    public static List<Column.Row> gauge(Gauge in, HeatControlColumn.Texts t) {
        String unit = in.units().get(in.unit());
        var units = new ArrayList<Column.Button>();
        for (int i = 0; i < in.units().size(); i++) units.add(position("unit:" + i, in.units().get(i), i == in.unit(), null));
        var rows = new ArrayList<Column.Row>();
        rows.add(new Column.Group(t.get("gauge.range"), t.get("gauge.range.hint"), List.of(
                new Column.Field("minimum", t.get("gauge.minimum"), unit, null),
                new Column.Field("maximum", t.get("gauge.maximum"), unit, null),
                new Column.Buttons(units))));
        if (in.pressure())
            rows.add(new Column.Group(t.get("gauge.reading"), t.get("gauge.reading.hint"), List.of(new Column.Buttons(List.of(
                    position("reading:gauge", t.get("gauge.relative"), in.relative(), null),
                    position("reading:absolute", t.get("gauge.absolute"), !in.relative(), null))))));
        if (!in.valid()) rows.add(new Column.Line(t.get("gauge.invalid"), Palette.WARN));
        return rows;
    }

    // ---- The pipeline tools ----

    /** A pipeline tool: whether it paints (the laying tool); the channel edited (0 the primary, 1 the secondary) and whether it is bare steel; whether the bore draft is a size. */
    public record PipelineTool(boolean paints, int channel, boolean bare, boolean valid) {
    }

    /** The palette's rows under the channel selector, and a swatch's height and the gap between two (PaintSwatch). */
    public static final int SWATCH_ROWS = 2, SWATCH = 16, SWATCH_GAP = 4;

    /**
     * The pipeline tools' settings: the Size (the bore); the laying tool's Paint (the channel's selector, the palette,
     * a custom colour, bare steel); then the warning while the bore is not a size. Keys: the fields {@code bore} and
     * {@code custom}; the buttons {@code channel:0}, {@code channel:1} and {@code bare}. The palette's swatches sit in
     * the Paint group's gap ({@link #palette}).
     */
    public static List<Column.Row> pipelineTool(PipelineTool in, HeatControlColumn.Texts t) {
        var rows = new ArrayList<Column.Row>();
        rows.add(new Column.Group(t.get("pipeline_tool.size"), null,
                List.of(new Column.Field("bore", t.get("pipeline_tool.bore"), "m", t.get("pipeline_tool.bore.hint")))));
        if (in.paints())
            rows.add(new Column.Group(t.get("pipeline_tool.paint"), t.get("pipeline_tool.paint.hint"), List.of(
                    new Column.Buttons(List.of(
                            position("channel:0", t.get("pipeline_tool.paint.primary"), in.channel() == 0, t.get("pipeline_tool.paint.primary.hint")),
                            position("channel:1", t.get("pipeline_tool.paint.secondary"), in.channel() == 1, t.get("pipeline_tool.paint.secondary.hint")))),
                    new Column.Gap(SWATCH_ROWS * (SWATCH + SWATCH_GAP)),
                    new Column.Field("custom", t.get("pipeline_tool.paint.custom"), "RGB", t.get("pipeline_tool.paint.custom.hint")),
                    new Column.Buttons(List.of(position("bare", t.get("pipeline_tool.paint.bare"), in.bare(), t.get("pipeline_tool.paint.bare.hint")))))));
        if (!in.valid()) rows.add(new Column.Line(t.get("pipeline_tool.invalid"), Palette.WARN));
        return rows;
    }

    /** The {@code count} swatches' places in the palette's gap of {@code column}, in {@link #SWATCH_ROWS} rows across its width; none without one. */
    public static List<Rect> palette(Column column, int count) {
        var gap = column.rows().stream().filter(p -> p.row() instanceof Column.Gap).findFirst();
        if (gap.isEmpty() || count == 0) return List.of();
        var r = gap.get().rect();
        int across = (count + SWATCH_ROWS - 1) / SWATCH_ROWS, pitch = (r.w() + SWATCH_GAP) / across;
        var out = new ArrayList<Rect>();
        for (int i = 0; i < count; i++) out.add(new Rect(r.x() + i % across * pitch, r.y() + i / across * (SWATCH + SWATCH_GAP), pitch - SWATCH_GAP, SWATCH));
        return List.copyOf(out);
    }

    // ---- The vessel datasheet ----

    /** A size: its key (its label's under {@code vessel.size.}), its value and unit as shown, and whether the player chose it (an LCD entry) or the shape gives it (an LED meter). */
    public record Size(String key, String value, String unit, boolean chosen) {
    }

    /** A nozzle: what it is for, the phase it draws first, the side it faces as seen from the vessel's front. */
    public record Nozzle(String role, String port, String side) {
    }

    /** A vessel's datasheet: its sizes; its notes, its kind first; a warning (cells other blocks hold) or null; its nozzles. */
    public record Sheet(List<Size> sizes, List<String> notes, @Nullable String warning, List<Nozzle> nozzles) {
        public Sheet {
            sizes = List.copyOf(sizes);
            notes = List.copyOf(notes);
            nozzles = List.copyOf(nozzles);
        }
    }

    /**
     * The datasheet's door: the Size group (the sizes the player chose as entries, the rest as meters), and for a unit
     * with moving parts their switch, Still or Running, for every unit of the client. Keys: the sizes' keys as fields
     * (shown, not edited), {@code motion:still} and {@code motion:running}.
     */
    public static List<Column.Row> vessel(List<Size> sizes, boolean moving, boolean running, HeatControlColumn.Texts t) {
        var size = new ArrayList<Column.Row>();
        for (var s : sizes) {
            String label = t.get("vessel.size." + s.key()), hint = t.get("vessel.size." + s.key() + ".hint");
            size.add(s.chosen() ? new Column.Field(s.key(), label, s.unit(), hint) : new Column.Reading(s.key(), label, s.value(), s.unit(), hint));
        }
        var rows = new ArrayList<Column.Row>();
        if (!size.isEmpty()) rows.add(new Column.Group(t.get("vessel.size"), null, size));
        if (moving)
            rows.add(new Column.Group(t.get("vessel.motion"), t.get("vessel.motion.hint"), List.of(new Column.Buttons(List.of(
                    position("motion:still", t.get("vessel.motion.still"), !running, null),
                    position("motion:running", t.get("vessel.motion.running"), running, null))))));
        return rows;
    }

    /**
     * A line on the glass: one cell (a note), two (a size: its label and value), or three (a nozzle: its role, port and
     * side); the nozzles' header ruled under. {@code entry} numbers the note, size or nozzle the line belongs to, so an
     * entry's wrapped lines share its band (-1 for the blank line and the header).
     */
    public record GlassLine(List<String> cells, int palette, boolean header, int entry) {
        public GlassLine {
            cells = List.copyOf(cells);
        }
    }

    /** The glass's lines, the nozzle table's port and side columns' widths, and whether the table stacks (each role on lines of its own). */
    public record Glass(List<GlassLine> lines, int port, int side, boolean stacked) {
        public Glass {
            lines = List.copyOf(lines);
        }

        public int height() {
            return lines.size() * PITCH + 4;
        }
    }

    /**
     * The band behind every second entry on the glass (owner: "different lines needs some distinguishing"; of three ways
     * rendered, "B: bands"): white at 11 %, so a wrapped line reads as its entry's, not as an entry of its own.
     */
    public static final int BAND = 0x1CFFFFFF;

    /** Whether {@code line} lies on a band: every second entry's lines, all of them. */
    public static boolean banded(GlassLine line) {
        return line.entry() % 2 == 1;
    }

    /**
     * The datasheet on the HMI glass, {@code width} wide: the warning; the sizes where the door does not show them
     * ({@code sizes}, a drum's tab on its tank screen); the notes wrapped, the kind's muted; then, after a blank line, the
     * nozzles under their header, in three columns.
     */
    public static Glass glass(Sheet sheet, boolean sizes, int width, Measure m, HeatControlColumn.Texts t) {
        var lines = new ArrayList<GlassLine>();
        int entry = 0;
        // The warning first, where it shows unscrolled.
        if (sheet.warning() != null) note(lines, sheet.warning(), Palette.WARN, entry++, width - 6, m);
        if (sizes)
            for (var s : sheet.sizes())
                lines.add(new GlassLine(List.of(t.get("vessel.size." + s.key()), s.value() + " " + s.unit()), Palette.TEXT, false, entry++));
        for (int i = 0; i < sheet.notes().size(); i++) note(lines, sheet.notes().get(i), i == 0 ? Palette.MUTED : Palette.TEXT, entry++, width - 6, m);
        if (sheet.nozzles().isEmpty()) return new Glass(lines, 0, 0, false);
        // A blank line, then the table.
        if (!lines.isEmpty()) lines.add(new GlassLine(List.of(""), Palette.TEXT, false, -1));
        var header = List.of(t.get("vessel.nozzles.name"), t.get("vessel.nozzles.port"), t.get("vessel.nozzles.side"));
        int port = m.width(header.get(1)), side = m.width(header.get(2));
        for (var n : sheet.nozzles()) {
            port = Math.max(port, m.width(n.port()));
            side = Math.max(side, m.width(n.side()));
        }
        port += 8;
        side += 4;
        // A role wraps in its column, its port and side on its first line: no name is cut. Where a role's word is wider
        // than its column, the table stacks: each role across the glass, its port and side on the line under it.
        int role = width - 10 - port - side;
        boolean stacked = sheet.nozzles().stream().flatMap(n -> java.util.Arrays.stream(n.role().split(" "))).anyMatch(word -> m.width(word) > role);
        lines.add(new GlassLine(header, Palette.MUTED, true, -1));
        for (var n : sheet.nozzles()) {
            var wrapped = m.wrap(n.role(), stacked ? width - 6 : role);
            for (int i = 0; i < wrapped.size(); i++)
                lines.add(new GlassLine(i == 0 && !stacked ? List.of(wrapped.get(i), n.port(), n.side()) : List.of(wrapped.get(i), "", ""), Palette.TEXT, false, entry));
            if (stacked) lines.add(new GlassLine(List.of("", n.port(), n.side()), Palette.TEXT, false, entry));
            entry++;
        }
        return new Glass(lines, port, side, stacked);
    }

    private static void note(List<GlassLine> lines, String text, int palette, int entry, int width, Measure m) {
        for (var line : m.wrap(text, width)) lines.add(new GlassLine(List.of(line), palette, false, entry));
    }

    /**
     * Draws {@code glass} in {@code box} ({@link #glass}'s width wide), moved up by {@code offset}, in the screens'
     * palette: every second entry on its {@link #BAND}; a size's value and a nozzle's port and side right-aligned in the
     * accent tone. The caller clips to {@code box}.
     */
    public static void draw(Canvas c, Glass glass, Rect box, int offset) {
        var m = c.measure();
        int y = box.y() + 2 - offset, right = box.right() - 3;
        for (var line : glass.lines()) {
            if (y + PITCH > box.y() && y < box.bottom()) {
                if (banded(line)) c.fill(box.x(), y - 1, box.right(), y + PITCH - 1, BAND);
                var cells = line.cells();
                int colour = line.palette();
                switch (cells.size()) {
                    case 1 -> c.text(cells.getFirst(), box.x() + 3, y, colour);
                    case 2 -> {
                        c.text(m.cut(cells.get(0), box.w() - 9 - m.width(cells.get(1))), box.x() + 3, y, Palette.MUTED);
                        c.text(cells.get(1), right - m.width(cells.get(1)), y, Palette.ACCENT);
                    }
                    default -> {
                        int sideX = right - glass.side(), portX = sideX - glass.port();
                        c.text(cells.get(0), box.x() + 3, y, colour);
                        int tone = line.header() ? colour : Palette.ACCENT;
                        c.text(cells.get(1), portX + glass.port() - 4 - m.width(cells.get(1)), y, tone);
                        c.text(cells.get(2), right - m.width(cells.get(2)), y, tone);
                        if (line.header()) c.hLine(box.x() + 2, box.right() - 2, y + 10, Palette.LINE);
                    }
                }
            }
            y += PITCH;
        }
    }
}
