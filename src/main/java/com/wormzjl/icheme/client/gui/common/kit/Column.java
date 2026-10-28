package com.wormzjl.icheme.client.gui.common.kit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * A column of rows placed top to bottom in one pass from its width (GUI_TOOLKIT_PLAN.md section 1): function groups,
 * lines, paragraphs, choices, field, meter and setting rows, button rows, bars and gaps. A placed row keeps its rect, the texts it draws, the
 * slots its fields and buttons take, and its hover text. The same placement draws the rows, places the widgets and answers
 * clicks and hovers, so they cannot drift apart. No text is cut (owner, 2026-10-28: "make sure characters are rendered
 * correctly"): a text wider than its row wraps, and a button row whose texts do not fit side by side stacks its buttons.
 */
public final class Column {
    public sealed interface Row permits Group, Line, Paragraph, Choice, Field, Reading, Setting, Buttons, Bar, Split, Gap {
    }

    /**
     * A function group (owner, 2026-10-28: "Text plate and function group should be better designed so new users are not
     * overwhelmed"; GUI_TOOLKIT_PLAN.md section 11): its rows in a frame, {@link #INSET} px in, under a plate that names the
     * function in a word or two. The plate's hover (or null) says more. The skin draws the frame and the plate.
     */
    public record Group(String title, @Nullable String hint, List<Row> rows) implements Row {
        public Group {
            rows = List.copyOf(rows);
        }
    }

    /** A line of text, wrapped to the width where it is wider. */
    public record Line(String text, int colour) implements Row {
    }

    /** Text wrapped to the width. */
    public record Paragraph(String text, int colour) implements Row {
    }

    /**
     * One choice of a list: its title (wrapped beside the mark), its hover (or null), whether it is chosen, and the action
     * a click on it runs (null: none).
     */
    public record Choice(String title, @Nullable String hint, boolean selected, @Nullable String action) implements Row {
    }

    /**
     * A labelled entry, a value the player types: its key, label, unit and hint; its box ({@link #BOX} px) at the row's
     * right, the unit after the box in its {@link #UNIT} px. A label wider than its share of the row wraps beside the box in
     * two lines, its words whole; where a label of its group needs more lines, or holds a word wider than its share, the
     * group's labels go above their boxes, wrapped to the row (the box where it always is; owner: "This Arrangement is not
     * good", GUI_TOOLKIT_PLAN.md sections 13 and 14).
     */
    public record Field(String key, String label, String unit, @Nullable String hint) implements Row {
    }

    /**
     * An indicating meter, a value the plant reads and the player cannot type (owner: "need to differentiate editable value
     * fields with non-editable"): laid out as a {@link Field}, its box drawn by the skin ({@link Skin#meter}). Its slot,
     * keyed {@code key}, takes no widget.
     */
    public record Reading(String key, String label, String value, String unit, @Nullable String hint) implements Row {
    }

    /**
     * A set point the player steps rather than types (the equipment constructor's sizes and places, GUI_REVIEW.md order
     * 3): laid out as a {@link Field}, its box an LCD showing {@code value} ({@link Skin#field}), its slot keyed
     * {@code key} taking no widget. With {@code steps}, a [−] and a [+] button ({@link #STEP} px each, {@link #minus} and
     * {@link #plus}) stand before the box, and the label wraps in the share they leave; without, the value is set
     * elsewhere (an imported design's) and shows on the same LCD, in line.
     */
    public record Setting(String key, String label, String value, String unit, @Nullable String hint, boolean steps) implements Row {
        /** The [−] button: key {@code key + "-"}, the setting's hint on hover. */
        public Button minus() {
            return new Button(key + "-", "-", true, hint);
        }

        /** The [+] button: key {@code key + "+"}. */
        public Button plus() {
            return new Button(key + "+", "+", true, hint);
        }
    }

    /** Buttons side by side, sharing the width; one a row, each the whole width, where a text does not fit its share. */
    public record Buttons(List<Button> buttons) implements Row {
        public Buttons {
            buttons = List.copyOf(buttons);
            if (buttons.isEmpty()) throw new IllegalArgumentException("A button row without buttons");
        }
    }

    /**
     * A button: its key, text, whether a click acts, its hover (or null), whether it is latched (the position a selector
     * or a switch stands in: the side shown, Off or On; lit from behind), and whether it is a display key ({@link
     * Skin#softKey}: a basis or a unit, which changes what the screen shows, not the plant).
     */
    public record Button(String key, String text, boolean active, @Nullable String hint, boolean latched, boolean soft) {
        public Button(String key, String text, boolean active, @Nullable String hint) {
            this(key, text, active, hint, false, false);
        }

        public Button(String key, String text, boolean active, @Nullable String hint, boolean latched) {
            this(key, text, active, hint, latched, false);
        }
    }

    /**
     * Shares of a whole in one bar, each in its colour, over a legend of one share a line: its label at the left, its
     * value at the right (a phase split). The legend's height is the shares' count, whatever their values, so rows
     * under it keep their places; its hover (or null) says more.
     */
    public record Split(List<Share> shares, @Nullable String hint) implements Row {
        public Split {
            shares = List.copyOf(shares);
        }
    }

    /** A share of a {@link Split}: its label, its value as shown, its amount, its colour. */
    public record Share(String label, String value, double amount, int colour) {
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

    /** A field's or a meter's box, the same in every row (five 7-segment digits); a widget's height; the gap between buttons side by side. */
    public static final int BOX = 46, WIDGET = 18, BUTTON_GAP = 4;
    /** A group: its rows' inset from the frame, its plate's height over the plate's lines, the gap under the frame. */
    public static final int INSET = 4, PLATE_PAD = 4, GROUP_GAP = 4;

    /** A group's plate height for {@code lines} lines of title. */
    public static int plate(int lines) {
        return lines * Measure.LINE + PLATE_PAD;
    }

    /**
     * Row heights: a line, a field row, a button row, a bar; each further line of a wrapped text adds 10 px.
     * {@link #DEFAULT} is the process screens' spacing; {@link #COMPACT} fits more rows in a short screen.
     */
    public record Metrics(int line, int field, int buttons, int bar) {
        public static final Metrics DEFAULT = new Metrics(12, 24, 24, 12), COMPACT = new Metrics(11, 20, 20, 10);

        public Metrics {
            if (line < Measure.LINE || field < WIDGET || buttons < WIDGET || bar < 8) throw new IllegalArgumentException("Rows lower than what they draw");
        }
    }
    /** A field row's label width: the row less the box, the unit and their gaps (4 + {@link #BOX} + 3 + {@link #UNIT}). */
    public static final int FIELD_TAIL = 76;
    /**
     * A unit's place after the box, the same in every row (°C, bar, kPa, m/s, kg/s, m³/h): boxes and units stand in line
     * down a column (owner: "Try to align them somehow", "make alignment a rule"). A wider unit is the label's last line.
     */
    public static final int UNIT = 23;
    /** A stepper's width, and the room a {@link Setting}'s two take before its box: [−], 2 px, [+], 2 px. */
    public static final int STEP = 14, STEPPERS = 2 * STEP + 4;

    /**
     * A row as placed: its rect, its widget slots by key, the lines it draws, how many of them lead (a field's label;
     * after them its unit), and its hover text (or null). A group is placed before its rows, its rect around them.
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

    /**
     * {@code rows} placed from ({@code x}, {@code y}) down, {@code width} wide, at row heights {@code metrics}. The column
     * ends at its last group's frame: the gap under it separates nothing.
     */
    public static Column place(List<? extends Row> rows, int x, int y, int width, Measure m, Metrics metrics) {
        if (width < FIELD_TAIL + 20) throw new IllegalArgumentException("A column narrower than a field row");
        var placed = new ArrayList<Placed>();
        int bottom = place(rows, x, y, width, m, metrics, placed);
        if (!rows.isEmpty() && rows.getLast() instanceof Group) bottom -= GROUP_GAP;
        return new Column(placed, new Rect(x, y, width, bottom - y));
    }

    /**
     * Places {@code rows} into {@code out}, a group before its own rows; returns the bottom. The entries and meters of one
     * group share one arrangement (owner: "This Arrangement is not good", "Try to align them somehow"): every label
     * beside its box, or every label above it. Every box has one width and one place in its row, so boxes and units stand
     * in line down the column whatever the rows hold.
     */
    private static int place(List<? extends Row> rows, int x, int y, int w, Measure m, Metrics h, List<Placed> out) {
        boolean beside = rows.stream().filter(Column::labelled).allMatch(row -> beside(label(row), unit(row), share(row, w), m));
        int at = y;
        for (var row : rows) {
            if (row instanceof Group g) {
                // The plate over the frame's top edge, its rows under it; the group's rect takes the gap under its frame.
                var title = m.wrap(g.title(), w - 2 * INSET - 8);
                int index = out.size();
                out.add(null);
                int bottom = place(g.rows(), x + INSET, at + plate(title.size()) + 2, w - 2 * INSET, m, h, out) + INSET;
                out.set(index, new Placed(g, new Rect(x, at, w, bottom + GROUP_GAP - at), Map.of(), title, title.size(), g.hint()));
                at = bottom + GROUP_GAP;
            } else {
                var p = place(row, x, at, w, m, h, beside);
                out.add(p);
                at = p.rect().bottom();
            }
        }
        return at;
    }

    /** Whether {@code row} is a labelled box: an entry, a meter or a setting. */
    private static boolean labelled(Row row) {
        return row instanceof Field || row instanceof Reading || row instanceof Setting;
    }

    private static String label(Row row) {
        return switch (row) {
            case Field f -> f.label();
            case Setting s -> s.label();
            default -> ((Reading) row).label();
        };
    }

    private static String unit(Row row) {
        return switch (row) {
            case Field f -> f.unit();
            case Setting s -> s.unit();
            default -> ((Reading) row).unit();
        };
    }

    /** A labelled row's share for its label beside the box, in a row {@code w} wide: less a setting's steppers. */
    private static int share(Row row, int w) {
        return w - FIELD_TAIL - (row instanceof Setting s && s.steps() ? STEPPERS : 0);
    }

    /** Whether {@code unit} is wider than its place after the box: then it is the label's last line. */
    private static boolean under(String unit, Measure m) {
        return m.width(unit) > UNIT;
    }

    /** Whether a label (and a unit under it) fits beside its box in {@code share}: two lines at most, every word whole. */
    private static boolean beside(String label, String unit, int share, Measure m) {
        var words = new ArrayList<>(List.of(label.split(" ")));
        int lines = m.wrap(label, share).size();
        if (under(unit, m)) {
            words.add(unit);
            lines++;
        }
        return lines <= 2 && words.stream().allMatch(word -> m.width(word) <= share);
    }

    private static Placed place(Row row, int x, int y, int w, Measure m, Metrics h, boolean beside) {
        var slots = new LinkedHashMap<String, Rect>();
        return switch (row) {
            case Line l -> {
                var lines = m.wrap(l.text(), w);
                yield new Placed(row, new Rect(x, y, w, h.line() + (lines.size() - 1) * Measure.LINE), slots, lines, lines.size(), null);
            }
            case Paragraph p -> {
                var lines = m.wrap(p.text(), w);
                yield new Placed(row, new Rect(x, y, w, lines.size() * Measure.LINE + 4), slots, lines, lines.size(), null);
            }
            case Choice c -> {
                // The title wrapped beside the mark; what it does on hover.
                var lines = m.wrap(c.title(), w - 12);
                yield new Placed(row, new Rect(x, y, w, h.line() + (lines.size() - 1) * Measure.LINE), slots, lines, lines.size(), c.hint());
            }
            case Field f -> labelled(row, f.key(), f.label(), f.unit(), f.hint(), x, y, w, m, h, beside);
            case Reading r -> labelled(row, r.key(), r.label(), r.unit(), r.hint(), x, y, w, m, h, beside);
            case Setting s -> labelled(row, s.key(), s.label(), s.unit(), s.hint(), x, y, w, m, h, beside);
            case Buttons b -> {
                // Side by side while every text fits its button; else one button a row, each the whole width.
                int n = b.buttons().size(), bw = (w - BUTTON_GAP * (n - 1)) / n;
                boolean stacked = b.buttons().stream().anyMatch(button -> m.width(button.text()) > bw - 6);
                for (int i = 0; i < n; i++)
                    slots.put(b.buttons().get(i).key(), stacked ? new Rect(x, y + i * h.buttons(), w, WIDGET) : new Rect(x + i * (bw + BUTTON_GAP), y, bw, WIDGET));
                yield new Placed(row, new Rect(x, y, w, stacked ? n * h.buttons() : h.buttons()), slots, List.of(), 0, null);
            }
            case Bar ignored -> new Placed(row, new Rect(x, y, w, h.bar()), slots, List.of(), 0, null);
            case Split s -> {
                // The bar, 8 px, then one legend line a share; a label cut only where a row is narrower than a share's
                // label and value side by side.
                var texts = new ArrayList<String>();
                for (var share : s.shares()) texts.add(m.cut(share.label(), Math.max(0, w - 12 - m.width(share.value()) - 6)));
                yield new Placed(row, new Rect(x, y, w, 12 + s.shares().size() * Measure.LINE + 2), slots, texts, texts.size(), s.hint());
            }
            case Gap g -> new Placed(row, new Rect(x, y, w, g.height()), slots, List.of(), 0, null);
            case Group g -> throw new IllegalStateException("A group is placed with its rows");
        };
    }

    /**
     * An entry's or a meter's row, as its group arranges them ({@code beside}). Its label beside the box, wrapped to
     * two lines; or above the box, wrapped to the row. Its box is {@link #BOX} px at the same place in every row, its unit
     * after it in {@link #UNIT} px, so boxes and units stand in line down the column; a unit wider than that place
     * (kmol/min, m³/min) is the label's last line, as a meter's legend plate reads "Flow, kmol/min". A setting's
     * steppers stand before its box, on the box's line: its slot first, then {@code key + "-"} and {@code key + "+"}.
     */
    private static Placed labelled(Row row, String key, String label, String unit, @Nullable String hint, int x, int y, int w, Measure m, Metrics h,
                                   boolean beside) {
        boolean under = under(unit, m);
        var lines = new ArrayList<>(m.wrap(label, beside ? share(row, w) : w));
        if (under) lines.add(unit);
        int above = beside ? 0 : h.line() + (lines.size() - 1) * Measure.LINE;
        var texts = new ArrayList<>(lines);
        texts.add(under ? "" : unit);
        var box = new Rect(x + w - FIELD_TAIL + 4, y + above, BOX, WIDGET);
        var slots = new LinkedHashMap<String, Rect>();
        slots.put(key, box);
        if (row instanceof Setting s && s.steps()) {
            slots.put(s.minus().key(), new Rect(box.x() - STEPPERS, box.y(), STEP, WIDGET));
            slots.put(s.plus().key(), new Rect(box.x() - STEP - 2, box.y(), STEP, WIDGET));
        }
        return new Placed(row, new Rect(x, y, w, above + h.field()), slots, texts, lines.size(), hint != null ? label + "\n" + hint : null);
    }

    public List<Placed> rows() {
        return rows;
    }

    public Rect bounds() {
        return bounds;
    }

    /** Every slot, top to bottom, by key: the widgets' and the boxes of the meters ({@link Reading}) and settings ({@link Setting}). */
    public Map<String, Rect> slots() {
        var all = new LinkedHashMap<String, Rect>();
        for (var p : rows)
            for (var e : p.slots().entrySet())
                if (all.put(e.getKey(), e.getValue()) != null) throw new IllegalStateException("Two widgets with key " + e.getKey());
        return all;
    }

    /** The row at content point ({@code px}, {@code py}), or null: a group's own row before the group. */
    public @Nullable Placed at(double px, double py) {
        Placed group = null;
        for (var p : rows)
            if (p.rect().contains(px, py)) {
                if (!(p.row() instanceof Group)) return p;
                group = p;
            }
        return group;
    }

    /** The action of the choice at content point ({@code px}, {@code py}), or null. */
    public @Nullable String action(double px, double py) {
        var p = at(px, py);
        return p != null && p.row() instanceof Choice c ? c.action() : null;
    }

    /**
     * The hover text at content point ({@code px}, {@code py}): a row's, outside its widgets (they have their own); a
     * meter's and a setting's over its box too.
     */
    public @Nullable String hover(double px, double py) {
        var p = at(px, py);
        if (p == null) return null;
        if (p.row() instanceof Field || p.row() instanceof Buttons) for (var slot : p.slots().values()) if (slot.contains(px, py)) return null;
        if (p.row() instanceof Setting s)
            for (var e : p.slots().entrySet()) if (!e.getKey().equals(s.key()) && e.getValue().contains(px, py)) return null;
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
                case Group ignored -> skin.group(c, new Rect(r.x(), r.y(), r.w(), r.h() - GROUP_GAP), texts);
                case Line l -> {
                    for (int i = 0; i < texts.size(); i++) skin.text(c, texts.get(i), r.x(), r.y() + i * Measure.LINE, l.colour());
                }
                case Paragraph para -> {
                    for (int i = 0; i < texts.size(); i++) skin.text(c, texts.get(i), r.x(), r.y() + i * Measure.LINE, para.colour());
                }
                case Choice ch -> {
                    skin.mark(c, r.x(), r.y(), ch.selected());
                    for (int i = 0; i < texts.size(); i++) skin.text(c, texts.get(i), r.x() + 12, r.y() + i * Measure.LINE, ch.selected() ? Palette.ACCENT : Palette.TEXT);
                }
                case Field ignored -> labels(c, skin, p, r, dy);
                case Reading reading -> skin.meter(c, labels(c, skin, p, r, dy), reading.value());
                case Setting setting -> skin.field(c, labels(c, skin, p, r, dy), setting.value(), "", false);
                case Bar b -> skin.bar(c, new Rect(r.x(), r.y(), r.w(), 8), b.share());
                case Split s -> {
                    var shares = s.shares();
                    double[] amounts = new double[shares.size()];
                    int[] colours = new int[shares.size()];
                    for (int i = 0; i < shares.size(); i++) {
                        amounts[i] = shares.get(i).amount();
                        colours[i] = shares.get(i).colour();
                    }
                    skin.split(c, new Rect(r.x(), r.y(), r.w(), 8), amounts, colours);
                    for (int i = 0; i < shares.size(); i++) {
                        int y = r.y() + 12 + i * Measure.LINE;
                        c.fill(r.x(), y + 1, r.x() + 7, y + 8, colours[i]);
                        skin.text(c, texts.get(i), r.x() + 11, y, Palette.MUTED);
                        String value = shares.get(i).value();
                        skin.text(c, value, r.right() - c.measure().width(value), y, Palette.TEXT);
                    }
                }
                case Buttons ignored -> {
                }
                case Gap ignored -> {
                }
            }
        }
    }

    /**
     * An entry's or a meter's label and unit; returns its box, moved by {@code dy}. A label above its box on the lines
     * above it; beside it, one line centred on the box, two lines along it; the unit after the box.
     */
    private static Rect labels(Canvas c, Skin skin, Placed p, Rect r, int dy) {
        var texts = p.texts();
        var box = p.slots().values().iterator().next().moved(0, dy);
        int top = box.y() > r.y() || p.lead() > 1 ? r.y() : r.y() + 5;
        for (int i = 0; i < p.lead(); i++) skin.text(c, texts.get(i), r.x(), top + i * Measure.LINE, Palette.MUTED);
        skin.text(c, texts.getLast(), box.right() + 3, box.y() + 5, Palette.MUTED);
        return box;
    }
}
