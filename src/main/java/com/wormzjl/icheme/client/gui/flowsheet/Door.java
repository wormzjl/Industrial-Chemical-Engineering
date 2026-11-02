package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.CalculatorColumn;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.Skin;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * A door as a builder says it (batch 2026-11-02-process-simulator, W6_SCREEN_PLAN.md section 2): its frame's texts, its rows and what its
 * entries show, its glass, its footer keys and what they do. The screen makes widgets from it and the render tool draws it, so both show one
 * door. Entries take effect as typed, so there is no Apply (D6).
 *
 * @param calculator the column calculator's frame, two door columns and its footer, and not the device frame
 * @param columns    the door's rows: one list for a device frame, one or two for the calculator's
 * @param values     what each entry shows, by its key
 * @param footer     keys beside Close; Close is the frame's
 */
public record Door(String title, List<Column.Button> tabs, int tab, String status, int tone, boolean calculator, List<List<Column.Row>> columns, Map<String, String> values, Glass glass, List<Column.Button> footer, Actions actions) {
    public Door {
        tabs = List.copyOf(tabs);
        columns = List.copyOf(columns);
        values = Map.copyOf(values);
        footer = List.copyOf(footer);
    }

    /** What a door does with its entries and keys. */
    public interface Actions {
        void entry(String key, String text);

        void button(String key);
    }

    /** What a door draws on its glass, and the widgets it keeps there. */
    public interface Glass {
        void draw(Canvas c, Skin skin, Rect box);

        default List<Slot> slots(Rect box) {
            return List.of();
        }

        /** The key a click at a point of the glass presses, or null. */
        default @Nullable String click(Rect box, double x, double y) {
            return null;
        }
    }

    /** Tabs that each open, keyed {@code tab/<i>}. */
    public static List<Column.Button> tabs(int tab, String... names) {
        var tabs = new java.util.ArrayList<Column.Button>();
        for (int i = 0; i < names.length; i++) tabs.add(position("tab/" + i, names[i], i == tab));
        return tabs;
    }

    /**
     * A widget on the glass: an entry showing {@code text} with {@code hint} when blank, or a key labelled {@code text}.
     *
     * @param latched a key's position
     */
    public record Slot(String key, Rect rect, String text, @Nullable String hint, boolean button, boolean latched) {
        public static Slot entry(String key, Rect rect, String text, @Nullable String hint) {
            return new Slot(key, rect, text, hint, false, false);
        }

        public static Slot key(String key, Rect rect, String text, boolean latched) {
            return new Slot(key, rect, text, null, true, latched);
        }
    }

    /** A door's view: the tab shown, the phase on the glass, the flow's basis on a feed's page; the screen keeps it while the door stands open. */
    public static final class View {
        public int tab, phase;
        public String basis = "mole";
        /** The column door's selected connection, the results it shows, the tray and product in hand. */
        public CalculatorColumn.Connection connection = CalculatorColumn.Connection.NONE;
        public CalculatorColumn.View results = CalculatorColumn.View.PROFILES;
        public CalculatorColumn.Metric metric = CalculatorColumn.Metric.TEMPERATURE;
        public int index, stream, tray;

        /** Whether the selected pumparound spreads its heat; a row's own setting. */
        public boolean spread(com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor door) {
            return connection == CalculatorColumn.Connection.PA && index < door.pumparounds().size() && door.pumparounds().get(index).split() == com.wormzjl.icheme.science.column.v3.V3PumparoundSpec.Split.UNIFORM;
        }
    }

    /** A meter; a unit wider than its place goes into the label (the alignment rule). */
    public static Column.Reading meter(String label, String value, String unit) {
        return meter(label.toLowerCase(Locale.ROOT), label, value, unit);
    }

    /** A meter whose label another in its column shares; {@code key} tells them apart. */
    public static Column.Reading meter(String key, String label, String value, String unit) {
        boolean wide = unit.length() > 4;
        return new Column.Reading("meter:" + key, wide ? label + " " + unit : label, value, wide ? "" : unit, null);
    }

    public static Column.Field entry(String key, String label, String unit) {
        boolean wide = unit.length() > 4;
        return new Column.Field(key, wide ? label + " " + unit : label, wide ? "" : unit, null);
    }

    /** A selector's position: a key that acts unless it stands here. */
    /** A name inside a sentence: its first letter small, unless it is an abbreviation. */
    public static String lower(String text) {
        return text.length() < 2 || !Character.isLowerCase(text.charAt(1)) ? text : Character.toLowerCase(text.charAt(0)) + text.substring(1);
    }

    /** A word that opens a sentence. */
    public static String upper(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    public static Column.Button position(String id, String text, boolean latched) {
        return new Column.Button(id, text, !latched, null, latched);
    }

    /** A key that acts where {@code active}, with its hover. */
    public static Column.Button key(String id, String text, boolean active, @Nullable String hint) {
        return new Column.Button(id, text, active, hint);
    }

    public static Column.Group group(String title, Column.Row... rows) {
        return new Column.Group(title, null, List.of(rows));
    }

    /** The door's name row. */
    public static Column.Group name(String title) {
        return group(title, new Column.Wide("name", null));
    }

    /** The phases of a stream as the shipped pages draw them, by mole. */
    public static Column.Group phases(String title, StreamProps.Phases p, com.wormzjl.icheme.client.gui.common.HeatControlColumn.Texts t) {
        return group(title, new Column.Split(List.of(share(t.get("flowsheet.phase.organic"), p.organic(), 0xFFD0A85A), share(t.get("flowsheet.phase.aqueous"), p.aqueous(), 0xFF3377FF),
                share(t.get("flowsheet.phase.gas"), p.gas(), 0xFFF0F0F0), share(t.get("flowsheet.phase.solid"), p.solid(), 0xFF72828C)), null));
    }

    private static Column.Share share(String label, double value, int colour) {
        return new Column.Share(label, SheetUnits.number(1, value) + " %", value, colour);
    }

    /** A table on the glass: a header, then rows, numbers right-aligned in the last columns; {@code widths} from the left. */
    public static void table(Canvas c, Rect box, int[] widths, List<String[]> rows) {
        var m = c.measure();
        int y = box.y() + 2;
        for (int r = 0; r < rows.size() && y + 10 <= box.bottom(); r++, y += 12) {
            int x = box.x() + 3;
            for (int i = 0; i < widths.length; i++) {
                String cell = m.cut(rows.get(r)[i], widths[i] - 4);
                int colour = r == 0 ? Palette.MUTED : i == 0 ? Palette.TEXT : Palette.ACCENT;
                c.text(cell, i == 0 ? x : x + widths[i] - 4 - m.width(cell), y, colour);
                x += widths[i];
            }
            if (r == 0) c.hLine(box.x() + 2, box.right() - 2, y + 10, Palette.LINE);
        }
    }
}
