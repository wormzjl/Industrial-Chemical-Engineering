package com.wormzjl.icheme.client.gui.common.kit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * A column of rows placed top to bottom in one pass from its width (GUI_TOOLKIT_PLAN.md section 1): lines, paragraphs,
 * choices, field rows, button rows, bars and gaps. A placed row keeps its rect, the texts it draws, the slots its fields
 * and buttons take, and its hover text. The same placement draws the rows, places the widgets and answers clicks and
 * hovers, so they cannot drift apart. No text is cut (owner, 2026-10-28: "make sure characters are rendered correctly"):
 * a text wider than its row wraps, and a button row whose texts do not fit side by side stacks its buttons.
 */
public final class Column {
    public sealed interface Row permits Heading, Line, Paragraph, Choice, Field, Buttons, Bar, Gap {
    }

    /**
     * A section's heading, wrapped to the width less a plate's margins; the skin draws it (a muted line, or a legend
     * plate) in a row {@link #HEADING_PAD} px taller than its lines.
     */
    public record Heading(String text) implements Row {
    }

    /** A line of text, wrapped to the width where it is wider. */
    public record Line(String text, int colour) implements Row {
    }

    /** Text wrapped to the width. */
    public record Paragraph(String text, int colour) implements Row {
    }

    /**
     * One choice of a list (a mode): its title, its line, whether it is chosen, and the action a click on it runs (null:
     * none). The title wraps beside the mark; the chosen choice's line wraps under it, another's shows on hover.
     */
    public record Choice(String title, String line, boolean selected, @Nullable String action) implements Row {
    }

    /**
     * A labelled field: its key, label, unit and hint; its box ({@link #BOX} px) at the row's right, the unit after the
     * box. A label wider than its share of the row goes above the box, wrapped to the row (the box where it always is).
     */
    public record Field(String key, String label, String unit, @Nullable String hint) implements Row {
    }

    /** Buttons side by side, sharing the width; one a row, each the whole width, where a text does not fit its share. */
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

    public static final int BOX = 58, WIDGET = 18, BUTTON_GAP = 4, HEADING_PAD = 5;

    /**
     * Row heights: a line, a choice (its title and one line), a field row, a button row, a bar; each further line of a
     * wrapped text adds 10 px. {@link #DEFAULT} is the process screens' spacing; {@link #COMPACT} fits more rows in a short
     * screen.
     */
    public record Metrics(int line, int choice, int field, int buttons, int bar) {
        public static final Metrics DEFAULT = new Metrics(12, 24, 24, 24, 12), COMPACT = new Metrics(11, 20, 20, 20, 10);

        public Metrics {
            if (line < Measure.LINE || choice < 2 * Measure.LINE || field < WIDGET || buttons < WIDGET || bar < 8) throw new IllegalArgumentException("Rows lower than what they draw");
        }
    }
    /** A field row's label width: the row less the box, the unit and their gaps. */
    public static final int FIELD_TAIL = 80;

    /**
     * A row as placed: its rect, its widget slots by key, the lines it draws, how many of them lead (a choice's title, a
     * field's label; after them a choice's line, a field's unit), and its hover text (or null).
     */
    public record Placed(Row row, Rect rect, Map<String, Rect> slots, List<String> texts, int lead, @Nullable String hover) {
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
            case Heading hd -> {
                // Room for a legend plate: 2 px over and under its lines, and 1 px under the plate.
                var lines = m.wrap(hd.text(), w - 8);
                yield new Placed(row, new Rect(x, y, w, lines.size() * Measure.LINE + HEADING_PAD), slots, lines, lines.size(), null);
            }
            case Line l -> {
                var lines = m.wrap(l.text(), w);
                yield new Placed(row, new Rect(x, y, w, h.line() + (lines.size() - 1) * Measure.LINE), slots, lines, lines.size(), null);
            }
            case Paragraph p -> {
                var lines = m.wrap(p.text(), w);
                yield new Placed(row, new Rect(x, y, w, lines.size() * Measure.LINE + 4), slots, lines, lines.size(), null);
            }
            case Choice c -> {
                // The title wrapped beside the mark; the chosen one's line under it, another's on hover.
                var texts = new ArrayList<>(m.wrap(c.title(), w - 12));
                int lead = texts.size();
                if (!c.selected() && !c.line().isEmpty())
                    yield new Placed(row, new Rect(x, y, w, Math.max(h.line(), lead * Measure.LINE + h.choice() - 2 * Measure.LINE)), slots, texts, lead, c.title() + "\n" + c.line());
                if (!c.line().isEmpty()) texts.addAll(m.wrap(c.line(), w - 12));
                yield new Placed(row, new Rect(x, y, w, h.choice() + Math.max(0, texts.size() - 2) * Measure.LINE), slots, texts, lead, null);
            }
            case Field f -> {
                // A label wider than its share of the row goes above the box, wrapped to the row.
                var label = m.width(f.label()) > w - FIELD_TAIL ? m.wrap(f.label(), w) : List.of(f.label());
                int above = m.width(f.label()) > w - FIELD_TAIL ? h.line() + (label.size() - 1) * Measure.LINE : 0;
                slots.put(f.key(), new Rect(x + w - FIELD_TAIL + 4, y + above, BOX, WIDGET));
                var texts = new ArrayList<>(label);
                texts.add(m.cut(f.unit(), 15));
                yield new Placed(row, new Rect(x, y, w, above + h.field()), slots, texts, label.size(), f.hint() != null ? f.label() + "\n" + f.hint() : null);
            }
            case Buttons b -> {
                // Side by side while every text fits its button; else one button a row, each the whole width.
                int n = b.buttons().size(), bw = (w - BUTTON_GAP * (n - 1)) / n;
                boolean stacked = b.buttons().stream().anyMatch(button -> m.width(button.text()) > bw - 6);
                for (int i = 0; i < n; i++)
                    slots.put(b.buttons().get(i).key(), stacked ? new Rect(x, y + i * h.buttons(), w, WIDGET) : new Rect(x + i * (bw + BUTTON_GAP), y, bw, WIDGET));
                yield new Placed(row, new Rect(x, y, w, stacked ? n * h.buttons() : h.buttons()), slots, List.of(), 0, null);
            }
            case Bar ignored -> new Placed(row, new Rect(x, y, w, h.bar()), slots, List.of(), 0, null);
            case Gap g -> new Placed(row, new Rect(x, y, w, g.height()), slots, List.of(), 0, null);
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

    /** Draws the rows' texts and marks, moved down by {@code dy}, in today's look; the widgets draw themselves. */
    public void draw(Canvas c, int dy) {
        draw(c, dy, Skin.PLAIN);
    }

    /** Draws the rows' texts and marks, moved down by {@code dy}, in {@code skin}; the widgets draw themselves. */
    public void draw(Canvas c, int dy, Skin skin) {
        for (var p : rows) {
            var r = p.rect().moved(0, dy);
            var texts = p.texts();
            switch (p.row()) {
                case Heading ignored -> skin.heading(c, r, texts);
                case Line l -> {
                    for (int i = 0; i < texts.size(); i++) skin.text(c, texts.get(i), r.x(), r.y() + i * Measure.LINE, l.colour());
                }
                case Paragraph para -> {
                    for (int i = 0; i < texts.size(); i++) skin.text(c, texts.get(i), r.x(), r.y() + i * Measure.LINE, para.colour());
                }
                case Choice ch -> {
                    skin.mark(c, r.x(), r.y(), ch.selected());
                    for (int i = 0; i < texts.size(); i++)
                        skin.text(c, texts.get(i), r.x() + 12, r.y() + i * Measure.LINE, i >= p.lead() ? Palette.MUTED : ch.selected() ? Palette.ACCENT : Palette.TEXT);
                }
                case Field ignored -> {
                    // A stacked label on the lines above its box, else beside it; the unit after the box.
                    var box = p.slots().values().iterator().next().moved(0, dy);
                    boolean stacked = box.y() > r.y();
                    for (int i = 0; i < p.lead(); i++) skin.text(c, texts.get(i), r.x(), stacked ? r.y() + i * Measure.LINE : r.y() + 5, Palette.MUTED);
                    skin.text(c, texts.getLast(), r.right() - 15, box.y() + 5, Palette.MUTED);
                }
                case Bar b -> skin.bar(c, new Rect(r.x(), r.y(), r.w(), 8), b.share());
                case Buttons ignored -> {
                }
                case Gap ignored -> {
                }
            }
        }
    }
}
