package com.wormzjl.icheme.client.gui.common.kit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * A column of rows placed top to bottom in one pass from its width (GUI_TOOLKIT_PLAN.md section 1): lines, paragraphs,
 * choices, field rows, button rows, bars and gaps. A placed row keeps its rect, the texts it draws (cut or wrapped to the
 * width), the slots its fields and buttons take, and its hover text. The same placement draws the rows, places the
 * widgets and answers clicks and hovers, so they cannot drift apart.
 */
public final class Column {
    public sealed interface Row permits Line, Paragraph, Choice, Field, Buttons, Bar, Gap {
    }

    /** One line of text, cut to the width; its full text on hover where cut. */
    public record Line(String text, int colour) implements Row {
    }

    /** Text wrapped to the width. */
    public record Paragraph(String text, int colour) implements Row {
    }

    /** One choice of a list (a mode): its title, a line under it, whether it is chosen, and the action a click on it runs (null: none). */
    public record Choice(String title, String line, boolean selected, @Nullable String action) implements Row {
    }

    /** A labelled field: its key, label, unit and hint; its box ({@link #BOX} px) at the row's right, the unit after the box. */
    public record Field(String key, String label, String unit, @Nullable String hint) implements Row {
    }

    /** Buttons side by side, sharing the width. */
    public record Buttons(List<Button> buttons) implements Row {
        public Buttons {
            buttons = List.copyOf(buttons);
            if (buttons.isEmpty()) throw new IllegalArgumentException("A button row without buttons");
        }
    }

    public record Button(String key, String text, boolean active, @Nullable String hint) {
    }

    /** A bar filled to {@code share} of its width. */
    public record Bar(double share) implements Row {
        public Bar {
            if (!(share >= 0 && share <= 1)) throw new IllegalArgumentException("A bar's share outside 0 to 1");
        }
    }

    public record Gap(int height) implements Row {
        public Gap {
            if (height < 0) throw new IllegalArgumentException("A negative gap");
        }
    }

    public static final int BOX = 58, WIDGET = 18, BUTTON_GAP = 4;

    /**
     * Row heights: a line, a choice, a field row, a button row, a bar. A choice lower than 20 px draws its title only (its
     * line on hover). {@link #DEFAULT} is the process screens' spacing; {@link #COMPACT} fits more rows in a short screen.
     */
    public record Metrics(int line, int choice, int field, int buttons, int bar) {
        public static final Metrics DEFAULT = new Metrics(12, 24, 24, 24, 12), COMPACT = new Metrics(11, 20, 20, 20, 10);

        public Metrics {
            if (line < Measure.LINE || choice < Measure.LINE || field < WIDGET || buttons < WIDGET || bar < 8) throw new IllegalArgumentException("Rows lower than what they draw");
        }
    }
    /** A field row's label width: the row less the box, the unit and their gaps. */
    public static final int FIELD_TAIL = 80;

    /** A row as placed: its rect, its widget slots by key, the texts it draws, and its hover text (or null). */
    public record Placed(Row row, Rect rect, Map<String, Rect> slots, List<String> texts, @Nullable String hover) {
        public Placed {
            slots = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(slots));
            texts = List.copyOf(texts);
        }
    }

    private final List<Placed> rows;
    private final Rect bounds;

    private Column(List<Placed> rows, Rect bounds) {
        this.rows = List.copyOf(rows);
        this.bounds = bounds;
    }

    /** {@code rows} placed from ({@code x}, {@code y}) down, {@code width} wide. */
    public static Column place(List<? extends Row> rows, int x, int y, int width, Measure m) {
        return place(rows, x, y, width, m, Metrics.DEFAULT);
    }

    /** {@code rows} placed from ({@code x}, {@code y}) down, {@code width} wide, at row heights {@code metrics}. */
    public static Column place(List<? extends Row> rows, int x, int y, int width, Measure m, Metrics metrics) {
        if (width < FIELD_TAIL + 20) throw new IllegalArgumentException("A column narrower than a field row");
        var placed = new ArrayList<Placed>();
        int at = y;
        for (var row : rows) {
            var p = place(row, x, at, width, m, metrics);
            placed.add(p);
            at = p.rect().bottom();
        }
        return new Column(placed, new Rect(x, y, width, at - y));
    }

    private static Placed place(Row row, int x, int y, int w, Measure m, Metrics h) {
        var slots = new LinkedHashMap<String, Rect>();
        return switch (row) {
            case Line l -> {
                String shown = m.cut(l.text(), w);
                yield new Placed(row, new Rect(x, y, w, h.line()), slots, List.of(shown), shown.equals(l.text()) ? null : l.text());
            }
            case Paragraph p -> {
                var lines = m.wrap(p.text(), w);
                yield new Placed(row, new Rect(x, y, w, lines.size() * Measure.LINE + 4), slots, lines, null);
            }
            case Choice c -> {
                String title = m.cut(c.title(), w - 12), line = m.cut(c.line(), w - 12);
                boolean titleOnly = h.choice() < 20, cut = !title.equals(c.title()) || !titleOnly && !line.equals(c.line());
                boolean hidden = titleOnly && !c.line().isEmpty();
                yield new Placed(row, new Rect(x, y, w, h.choice()), slots, List.of(title, titleOnly ? "" : line), cut || hidden ? c.title() + "\n" + c.line() : null);
            }
            case Field f -> {
                slots.put(f.key(), new Rect(x + w - FIELD_TAIL + 4, y, BOX, WIDGET));
                String label = m.cut(f.label(), w - FIELD_TAIL), unit = m.cut(f.unit(), 15);
                String hover = f.hint() != null ? f.label() + "\n" + f.hint() : label.equals(f.label()) ? null : f.label();
                yield new Placed(row, new Rect(x, y, w, h.field()), slots, List.of(label, unit), hover);
            }
            case Buttons b -> {
                int n = b.buttons().size(), bw = (w - BUTTON_GAP * (n - 1)) / n;
                for (int i = 0; i < n; i++) slots.put(b.buttons().get(i).key(), new Rect(x + i * (bw + BUTTON_GAP), y, bw, WIDGET));
                yield new Placed(row, new Rect(x, y, w, h.buttons()), slots, List.of(), null);
            }
            case Bar ignored -> new Placed(row, new Rect(x, y, w, h.bar()), slots, List.of(), null);
            case Gap g -> new Placed(row, new Rect(x, y, w, g.height()), slots, List.of(), null);
        };
    }

    public List<Placed> rows() {
        return rows;
    }

    public Rect bounds() {
        return bounds;
    }

    /** Every widget slot, top to bottom, by key. */
    public Map<String, Rect> slots() {
        var all = new LinkedHashMap<String, Rect>();
        for (var p : rows)
            for (var e : p.slots().entrySet())
                if (all.put(e.getKey(), e.getValue()) != null) throw new IllegalStateException("Two widgets with key " + e.getKey());
        return all;
    }

    /** The row at content point ({@code px}, {@code py}), or null. */
    public @Nullable Placed at(double px, double py) {
        for (var p : rows) if (p.rect().contains(px, py)) return p;
        return null;
    }

    /** The action of the choice at content point ({@code px}, {@code py}), or null. */
    public @Nullable String action(double px, double py) {
        var p = at(px, py);
        return p != null && p.row() instanceof Choice c ? c.action() : null;
    }

    /** The hover text at content point ({@code px}, {@code py}): a row's, outside its widgets (they have their own). */
    public @Nullable String hover(double px, double py) {
        var p = at(px, py);
        if (p == null) return null;
        for (var slot : p.slots().values()) if (slot.contains(px, py)) return null;
        return p.hover();
    }

    /** Draws the rows' texts and marks, moved down by {@code dy}; the widgets draw themselves. */
    public void draw(Canvas c, int dy) {
        for (var p : rows) {
            var r = p.rect().moved(0, dy);
            switch (p.row()) {
                case Line l -> c.text(p.texts().getFirst(), r.x(), r.y(), l.colour());
                case Paragraph para -> {
                    for (int i = 0; i < p.texts().size(); i++) c.text(p.texts().get(i), r.x(), r.y() + i * Measure.LINE, para.colour());
                }
                case Choice ch -> {
                    radio(c, r.x(), r.y(), ch.selected());
                    c.text(p.texts().get(0), r.x() + 12, r.y(), ch.selected() ? Palette.ACCENT : Palette.TEXT);
                    if (!p.texts().get(1).isEmpty()) c.text(p.texts().get(1), r.x() + 12, r.y() + Measure.LINE, Palette.MUTED);
                }
                case Field ignored -> {
                    c.text(p.texts().get(0), r.x(), r.y() + 5, Palette.MUTED);
                    c.text(p.texts().get(1), r.right() - 15, r.y() + 5, Palette.MUTED);
                }
                case Bar b -> {
                    c.fill(r.x(), r.y(), r.right(), r.y() + 8, Palette.BG);
                    c.outline(r.x(), r.y(), r.w(), 8, Palette.LINE);
                    c.fill(r.x() + 1, r.y() + 1, r.x() + 1 + (int) Math.round((r.w() - 2) * b.share()), r.y() + 7, Palette.ACCENT);
                }
                case Buttons ignored -> {
                }
                case Gap ignored -> {
                }
            }
        }
    }

    private static void radio(Canvas c, int x, int y, boolean selected) {
        c.fill(x, y, x + 8, y + 8, selected ? Palette.ACCENT : Palette.LINE);
        c.fill(x + 1, y + 1, x + 7, y + 7, Palette.BG);
        if (selected) c.fill(x + 2, y + 2, x + 6, y + 6, Palette.ACCENT);
    }
}
