package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Format;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.client.gui.screens.inventory.FluidDeviceScreen.FluidGuiLayout;
import com.wormzjl.icheme.science.equipment.ColumnComponents.Condenser;
import com.wormzjl.icheme.science.equipment.ColumnComponents.Pump;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.IntFunction;
import org.jetbrains.annotations.Nullable;

/**
 * The column calculator as a panel door (batch 2026-10-28-gui-toolkit, GUI_REVIEW.md order 5): the title and the theme
 * key; the Column, Feed, Results and Equipment tabs, the page shown latched; the status said once, on the message
 * display, the solver's own line on its hover; the HMI glass at the left (the column drawing, the tables, the plots and
 * the solver's readouts); the door columns at the right, two on the Column page where the door is wide enough, with the
 * page's entries and selectors in function groups; Templates, Solve and Save on the footer, the basis and temperature
 * display keys on their tray at its right. The door rows hold only widgets, so nothing the engine answers moves one.
 * Texts are keys under {@code gui.icheme.}. No Minecraft classes: the screen, the gates and the render tool place the
 * same door.
 */
public final class CalculatorColumn {
    private CalculatorColumn() {
    }

    /** The screen's keys under {@code gui.icheme.}. */
    public static final String KEY = "column.";
    /** The door columns' and the glass's top (under the message display); the room under them (the footer); the footer's divider above the door's foot. */
    public static final int TOP = 74, FOOT = 38, DIVIDER = 35;
    /** The door's narrowest and widest, and its lowest. */
    public static final int MIN_WIDTH = 320, MAX_WIDTH = 960, MIN_HEIGHT = 220;
    /** A door column's narrowest and widest; the glass's least beside two columns; a display key's width (as the fluid screens'). */
    public static final int NARROW = 132, WIDE = 180, GLASS = 155, KEY_WIDTH = 40;
    /** A line's pitch on the glass. */
    public static final int PITCH = DialogColumns.PITCH;

    /** The pages, as the tabs show them. */
    public enum Page {
        COLUMN, FEED, RESULTS, EQUIPMENT;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What the Results page's glass shows. */
    public enum View {
        PROFILES, PRODUCTS, HEAT, TRAY, SOLVER;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What a profile plots down the column. */
    public enum Metric {
        TEMPERATURE, PRESSURE, DROP, TRAFFIC;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The connection selected on the drawing: none, a side draw, a pumparound, a steam feed. */
    public enum Connection {
        NONE, DRAW, PA, STEAM
    }

    // ---- The frame ----

    /** The door's width in a window {@code width} wide: 16 px less, from {@link #MIN_WIDTH} to {@link #MAX_WIDTH}. */
    public static int width(int window) {
        return Math.clamp(window - 16, MIN_WIDTH, MAX_WIDTH);
    }

    /** The door's height in a window {@code height} high: 16 px less, at least {@link #MIN_HEIGHT}. */
    public static int height(int window) {
        return Math.max(MIN_HEIGHT, window - 16);
    }

    /** A door column's width: what the glass leaves of the door's width, {@link #NARROW} to {@link #WIDE}. */
    public static int columnWidth(int width) {
        return Math.clamp((width - 200) / 2, NARROW, WIDE);
    }

    /** Whether two door columns stand beside the glass, leaving it {@link #GLASS} px or more. */
    public static boolean split(int width) {
        return width >= 2 * NARROW + 200;
    }

    /**
     * The door columns' areas: the right one 20 px in from the door's edge (its bar in between), the left one 12 px
     * before it where the page has two and the door splits; from {@link #TOP} to the footer.
     */
    public static List<Rect> door(int width, int height, boolean two) {
        int c = columnWidth(width), right = width - 20, h = height - FOOT - TOP;
        var column = new Rect(right - c, TOP, c, h);
        return two && split(width) ? List.of(new Rect(right - 2 * c - 12, TOP, c, h), column) : List.of(column);
    }

    /** The door's bar, right of its last column. */
    public static int bar(int width) {
        return width - 16;
    }

    /** The HMI glass: from x 5 to 8 px before the first door column, from 3 px above {@link #TOP} to the footer. */
    public static Rect glass(int width, int height, boolean two) {
        int left = door(width, height, two).getFirst().x();
        return new Rect(5, TOP - 3, left - 8 - 5, height - FOOT - TOP + 3);
    }

    /** The dividers' x: before the first column, and between two. */
    public static List<Integer> dividers(int width, int height, boolean two) {
        var areas = door(width, height, two);
        var out = new ArrayList<Integer>();
        out.add(areas.getFirst().x() - 6);
        if (areas.size() == 2) out.add(areas.getLast().x() - 7);
        return out;
    }

    /** The message display's text line under the tabs; its window lies round it ({@code Skin.status}). */
    public static Rect display(int width) {
        return new Rect(10, 59, width - 20, 9);
    }

    /** The theme key on the title row. */
    public static Rect theme(int width) {
        return new Rect(width - 28, 5, 18, 18);
    }

    /** The title's room: the title row less the theme key. */
    public static int title(int width) {
        return width - 54;
    }

    /** The tabs' slots at y 29, as every fluid screen places its tabs ({@code FluidGuiLayout.tabs}). */
    public static List<Rect> tabSlots(int width) {
        return FluidGuiLayout.tabs(width, Page.values().length).stream().map(s -> new Rect(s.x(), 29, s.width(), 18)).toList();
    }

    /** The tabs (keys {@code tab:<page>}), the page shown latched. */
    public static List<Column.Button> tabs(Page shown, HeatControlColumn.Texts t) {
        var out = new ArrayList<Column.Button>();
        for (var p : Page.values())
            out.add(new Column.Button("tab:" + p.key(), t.get(KEY + "tab." + p.key()), p != shown, t.get(KEY + "tab." + p.key() + ".hint"), p == shown));
        return out;
    }

    /**
     * The footer's slots at {@code height} - 27: Templates, Solve and Save sharing the door's width up to 4 px before the
     * display keys' tray; then the basis and the temperature display keys, {@link #KEY_WIDTH} px each, 6 px apart, 10 px
     * in from the door's edge.
     */
    public static List<Rect> footer(int width, int height) {
        int keys = width - 10 - (2 * KEY_WIDTH + 6), y = height - 27, w = (keys - 4 - 4 - 10 - 2 * 4) / 3;
        return List.of(new Rect(10, y, w, 18), new Rect(10 + w + 4, y, w, 18), new Rect(10 + 2 * (w + 4), y, w, 18),
                new Rect(keys, y, KEY_WIDTH, 18), new Rect(keys + KEY_WIDTH + 6, y, KEY_WIDTH, 18));
    }

    /** The tray the display keys sit on: 4 px round them. */
    public static Rect keys(int width, int height) {
        var first = footer(width, height).get(3);
        return new Rect(first.x() - 4, height - 31, 2 * KEY_WIDTH + 6 + 8, 26);
    }

    /** What the footer reads: whether the templates show; whether the page is the Feed's (its templates and saves are mixtures); whether Solve acts; the basis and the temperature unit shown. */
    public record Footer(boolean browsing, boolean feed, boolean solvable, boolean mass, String unit) {
    }

    /**
     * The footer: Templates (latched while the templates show), Solve, Save (a column's settings, on the Feed page its
     * mixture); then the display keys: the basis (Mole, Mass) and the temperature unit. Keys {@code templates},
     * {@code solve}, {@code save}, {@code basis}, {@code temperature}.
     */
    public static List<Column.Button> footerButtons(Footer in, HeatControlColumn.Texts t) {
        String kind = in.feed() ? "mixture" : "column";
        return List.of(new Column.Button("templates", t.get(KEY + "templates"), !in.browsing(), t.get(KEY + kind + "_templates.hint"), in.browsing()),
                new Column.Button("solve", t.get(KEY + "solve"), in.solvable(), t.get(KEY + "solve.hint")),
                new Column.Button("save", t.get(KEY + "save"), true, t.get(KEY + "save_" + kind + ".hint")),
                new Column.Button("basis", t.get(in.mass() ? "fluid.mass_basis_short" : "fluid.mole_basis_short"), true, t.get(KEY + "flow_basis.hint"), false, true),
                new Column.Button("temperature", in.unit(), true, t.get(KEY + "temperature_unit.hint"), false, true));
    }

    // ---- The status, said once ----

    /** The status on the message display: its words, their tone, and the solver's own line its hover gives (null: none). */
    public record Status(String text, int palette, @Nullable String hint) {
    }

    /**
     * What the status reads: no draft yet; the server's inputs changed under the draft; a refused request's words; a
     * notice (saved, copied); a solve or a design save under way; the last solve of these inputs failed; why the draft
     * does not assemble (null: it does); an accepted result shown; the draft edited since; the engine's own line.
     */
    public record StatusInput(boolean waiting, boolean conflict, String rejection, String notice, boolean busy, boolean savingDesign, boolean failed,
                              @Nullable String invalid, boolean accepted, boolean invalidated, @Nullable String engine) {
    }

    /** The one status, in order of what the player must act on first. */
    public static Status status(StatusInput in, HeatControlColumn.Texts t) {
        String text;
        int palette;
        if (in.waiting()) {
            text = t.get(KEY + "waiting");
            palette = Palette.MUTED;
        } else if (in.conflict()) {
            text = t.get(KEY + "conflict");
            palette = Palette.WARN;
        } else if (!in.rejection().isBlank()) {
            text = in.rejection();
            palette = Palette.WARN;
        } else if (!in.notice().isBlank()) {
            text = in.notice();
            palette = Palette.ACCENT;
        } else if (in.busy()) {
            text = t.get(KEY + (in.savingDesign() ? "design_saving" : "solving"));
            palette = Palette.MUTED;
        } else if (in.failed()) {
            text = t.get(KEY + "failed");
            palette = Palette.WARN;
        } else if (in.invalid() != null) {
            text = in.invalid();
            palette = Palette.WARN;
        } else if (in.accepted()) {
            text = t.get(KEY + "accepted");
            palette = Palette.ACCENT;
        } else {
            text = t.get(KEY + (in.invalidated() ? "draft_changed" : "ready"));
            palette = Palette.MUTED;
        }
        return new Status(text, palette, in.engine());
    }

    // ---- The door columns ----

    /** A page's door rows: the left column's (none on a page of one column) and the right one's. */
    public record Rows(List<Column.Row> left, List<Column.Row> right) {
        public Rows {
            left = List.copyOf(left);
            right = List.copyOf(right);
        }

        /** Whether the page has two columns. */
        public boolean two() {
            return !left.isEmpty();
        }

        /** Every row, the left column's first: the one column of a door too narrow to split. */
        public List<Column.Row> all() {
            var out = new ArrayList<>(left);
            out.addAll(right);
            return out;
        }

        /** The rows a column, as the door at {@code width} shows them ({@link #door}). */
        public List<List<Column.Row>> at(int width) {
            return two() && split(width) ? List.of(left, right) : List.of(all());
        }
    }

    /** A selector's position: latched where it stands, else a key that moves it there while {@code acts}. */
    private static Column.Button position(String key, String text, boolean current, boolean acts, @Nullable String hint) {
        return new Column.Button(key, text, !current && acts, hint, current);
    }

    private static Column.Button button(String key, String text, boolean active, HeatControlColumn.Texts t, String hint) {
        return new Column.Button(key, t.get(KEY + text), active, t.get(KEY + hint + ".hint"));
    }

    /** An entry: its draft key, its label under {@code field.}, its unit, its hover from {@code <hint>.hint}. */
    private static Column.Field field(String key, String label, String unit, String hint, HeatControlColumn.Texts t) {
        return new Column.Field(key, t.get(KEY + "field." + label), unit, t.get(KEY + hint + ".hint"));
    }

    private static Column.Group group(String key, HeatControlColumn.Texts t, Column.Row... rows) {
        return new Column.Group(t.get(KEY + "group." + key), t.get(KEY + "group." + key + ".hint"), List.of(rows));
    }

    /** The draft's choice while the server's inputs changed under it: the server's, or the player's own. Keys {@code use_server}, {@code keep_draft}. */
    public static Column.Group draft(HeatControlColumn.Texts t) {
        return group("draft", t, new Column.Buttons(List.of(button("use_server", "use_server", true, t, "use_server"),
                button("keep_draft", "keep_draft", true, t, "keep_draft"))));
    }

    /** The Feed group: the flow, the temperature in {@code unit}, the tray. Keys {@code s0}, {@code s1}, {@code s3}. */
    private static Column.Group feedGroup(String unit, HeatControlColumn.Texts t) {
        return group("feed", t, field("s0", "flow", "kmol/h", "feed_flow", t), field("s1", "temperature", unit, "feed_temperature", t),
                field("s3", "tray", "", "feed_tray", t));
    }

    /**
     * What the Column page reads: the draft conflict; whether the draft takes edits; the temperature unit; the selected
     * connection and its index; a pumparound's cooling spread over its trays (else at its return tray); whether a draw,
     * a pumparound and a steam feed can be added.
     */
    public record ColumnInput(boolean conflict, boolean editable, String unit, Connection selected, int index, boolean spread, boolean addDraw,
                              boolean addPa, boolean addSteam) {
    }

    /**
     * The Column page, after the prototype: at the left Column (trays, diameter, top pressure) and Feed (flow,
     * temperature, tray); at the right Condenser (outlet, reflux ratio), Reboiler (duty), Add (draw, pumparound, steam at
     * the selected tray) and the connection selected on the drawing (a draw's flow and tray; a pumparound's cooling, draw
     * and return trays and where its cooling goes; a steam feed's flow, tray and temperature), with Remove. Fields are
     * keyed by the draft's keys; buttons {@code add:draw}, {@code add:pa}, {@code add:steam}, {@code split:uniform},
     * {@code split:return}, {@code remove}.
     */
    public static Rows column(ColumnInput in, HeatControlColumn.Texts t) {
        var left = new ArrayList<Column.Row>();
        if (in.conflict()) left.add(draft(t));
        left.add(group("column", t, field("s2", "trays", "", "tray_count", t), field("s9", "diameter", "m", "diameter", t),
                field("s7", "top_pressure", "bar", "top_pressure", t)));
        left.add(feedGroup(in.unit(), t));
        var right = new ArrayList<Column.Row>();
        right.add(group("condenser", t, field("s4", "outlet", in.unit(), "top_temperature", t), field("s6", "reflux", "", "reflux", t)));
        right.add(group("reboiler", t, field("s5", "duty", "MW", "duty", t)));
        right.add(group("add", t, new Column.Buttons(List.of(button("add:draw", "add_draw", in.addDraw(), t, "add_draw"), button("add:pa", "add_pa", in.addPa(), t, "add_pa"))),
                new Column.Buttons(List.of(button("add:steam", "add_steam", in.addSteam(), t, "add_steam")))));
        int i = in.index();
        switch (in.selected()) {
            case DRAW -> right.add(new Column.Group(t.get(KEY + "group.draw", i + 1), t.get(KEY + "group.draw.hint"), List.of(
                    field("d" + i + "rate", "flow", "kmol/h", "draw_flow", t), field("d" + i + "stage", "tray", "", "draw_tray", t),
                    new Column.Buttons(List.of(button("remove", "remove", in.editable(), t, "remove_draw"))))));
            case PA -> right.add(new Column.Group(t.get(KEY + "group.pa", i + 1), t.get(KEY + "group.pa.hint"), List.of(
                    field("c" + i + "duty", "cooling", "MW", "cooling", t), field("c" + i + "draw", "draw_tray", "", "draw_tray", t),
                    field("c" + i + "return", "return_tray", "", "return_tray", t),
                    new Column.Buttons(List.of(position("split:uniform", t.get(KEY + "uniform_heat"), in.spread(), in.editable(), t.get(KEY + "uniform_heat.hint")),
                            position("split:return", t.get(KEY + "return_heat"), !in.spread(), in.editable(), t.get(KEY + "return_heat.hint")))),
                    new Column.Buttons(List.of(button("remove", "remove", in.editable(), t, "remove_pa"))))));
            case STEAM -> right.add(new Column.Group(t.get(KEY + "group.steam", i + 1), t.get(KEY + "group.steam.hint"), List.of(
                    field("t" + i + "rate", "flow", "kmol/h", "steam_flow", t), field("t" + i + "stage", "tray", "", "injection_tray", t),
                    field("t" + i + "T", "temperature", in.unit(), "steam_temperature", t),
                    new Column.Buttons(List.of(button("remove", "remove", in.editable(), t, "remove_steam"))))));
            case NONE -> {
            }
        }
        return new Rows(left, right);
    }

    /** The Feed page: Feed (flow, temperature, tray) and Mixture (Clear); the mixture's table on the glass. Button {@code clear}. */
    public static Rows feed(boolean conflict, boolean editable, String unit, HeatControlColumn.Texts t) {
        var rows = new ArrayList<Column.Row>();
        if (conflict) rows.add(draft(t));
        rows.add(feedGroup(unit, t));
        rows.add(group("mixture", t, new Column.Buttons(List.of(button("clear", "clear_mixture", editable, t, "clear_mixture")))));
        return new Rows(List.of(), rows);
    }

    /**
     * What the Results page reads: the draft conflict; the view and the profile shown; the product streams' names and
     * the one shown; the tray shown (a node, from 0) of {@code trays} interior trays, -1 before a draft.
     */
    public record ResultsInput(boolean conflict, View view, Metric metric, List<String> streams, int stream, int tray, int trays) {
        public ResultsInput {
            streams = List.copyOf(streams);
        }
    }

    /**
     * The Results page: View (Profiles, Products, Heat, Tray, Solver; latched selectors in place of the cycle buttons),
     * then the view's own group: Profile (temperature, pressure, tray dP, traffic), Product (one position a stream), Tray
     * (the tray shown, stepped), Report (Copy). Buttons {@code view:<view>}, {@code metric:<metric>}, {@code stream:<i>},
     * {@code tray-} and {@code tray+} (a setting keyed {@code tray}), {@code copy_report}.
     */
    public static Rows results(ResultsInput in, HeatControlColumn.Texts t) {
        var rows = new ArrayList<Column.Row>();
        if (in.conflict()) rows.add(draft(t));
        var views = new ArrayList<Column.Button>();
        for (var v : View.values()) views.add(position("view:" + v.key(), t.get(KEY + "view." + v.key()), v == in.view(), true, t.get(KEY + "view." + v.key() + ".hint")));
        rows.add(group("view", t, new Column.Buttons(views.subList(0, 2)), new Column.Buttons(views.subList(2, 4)), new Column.Buttons(views.subList(4, 5))));
        switch (in.view()) {
            case PROFILES -> {
                var metrics = new ArrayList<Column.Button>();
                for (var m : Metric.values()) metrics.add(position("metric:" + m.key(), t.get(KEY + "metric." + m.key()), m == in.metric(), true, null));
                rows.add(group("profile", t, new Column.Buttons(metrics.subList(0, 2)), new Column.Buttons(metrics.subList(2, 4))));
            }
            case PRODUCTS -> {
                if (!in.streams().isEmpty()) {
                    var streams = new ArrayList<Column.Row>();
                    for (int i = 0; i < in.streams().size(); i++)
                        streams.add(new Column.Buttons(List.of(position("stream:" + i, in.streams().get(i), i == in.stream(), true, null))));
                    rows.add(new Column.Group(t.get(KEY + "group.product"), t.get(KEY + "group.product.hint"), streams));
                }
            }
            case TRAY -> {
                if (in.tray() >= 0)
                    rows.add(group("tray", t, new Column.Setting("tray", t.get(KEY + "field.tray"), Integer.toString(in.tray() + 1), "", t.get(KEY + "group.tray.hint"), true)));
            }
            case SOLVER -> rows.add(group("report", t, new Column.Buttons(List.of(button("copy_report", "copy_report", true, t, "copy_report")))));
            case HEAT -> {
            }
        }
        return new Rows(List.of(), rows);
    }

    /** What the Equipment page reads: the draft conflict; the condenser, the reflux pump and each pumparound's pump chosen; whether the design saves. */
    public record EquipmentInput(boolean conflict, Condenser condenser, Pump reflux, List<Pump> pumparounds, boolean saves) {
        public EquipmentInput {
            pumparounds = List.copyOf(pumparounds);
        }
    }

    private static Column.Buttons pumps(String key, Pump chosen, HeatControlColumn.Texts t) {
        return new Column.Buttons(List.of(position(key + ":standard", t.get(KEY + "pump.standard"), chosen == Pump.STANDARD, true, null),
                position(key + ":in_line", t.get(KEY + "pump.in_line"), chosen == Pump.IN_LINE, true, null)));
    }

    /**
     * The Equipment page: Condenser (air cooler, exchanger), Reflux pump and each pumparound's pump (standard, in-line),
     * latched selectors in place of the cycle buttons; Design (Save design). The derived components and the design's
     * state stand on the glass. Buttons {@code condenser:air_cooler}, {@code condenser:exchanger}, {@code reflux:<pump>},
     * {@code pa<i>:<pump>}, {@code save_design}.
     */
    public static Rows equipment(EquipmentInput in, HeatControlColumn.Texts t) {
        var rows = new ArrayList<Column.Row>();
        if (in.conflict()) rows.add(draft(t));
        rows.add(new Column.Group(t.get(KEY + "component_condenser"), t.get(KEY + "condenser.hint"), List.of(new Column.Buttons(List.of(
                position("condenser:air_cooler", t.get(KEY + "condenser.air_cooler"), in.condenser() == Condenser.AIR_COOLER, true, null),
                position("condenser:exchanger", t.get(KEY + "condenser.exchanger"), in.condenser() == Condenser.EXCHANGER, true, null))))));
        rows.add(new Column.Group(t.get(KEY + "component_reflux_pump"), t.get(KEY + "pump.hint"), List.of(pumps("reflux", in.reflux(), t))));
        for (int i = 0; i < in.pumparounds().size(); i++)
            rows.add(new Column.Group(t.get(KEY + "component_pa_pump", i + 1), t.get(KEY + "pump.hint"), List.of(pumps("pa" + i, in.pumparounds().get(i), t))));
        rows.add(group("design", t, new Column.Buttons(List.of(button("save_design", "save_design", in.saves(), t, "save_design")))));
        return new Rows(List.of(), rows);
    }

    /**
     * The templates on the door while they show: Templates (Back, Refresh, Open folder), the list on the glass; or, while
     * the player names a new one, Save (Save, Back), the name on the glass. Buttons {@code back}, {@code refresh},
     * {@code open_folder}, {@code save_json}.
     */
    public static Rows presets(boolean conflict, boolean saving, HeatControlColumn.Texts t) {
        var rows = new ArrayList<Column.Row>();
        if (conflict) rows.add(draft(t));
        if (saving) rows.add(group("save", t, new Column.Buttons(List.of(button("save_json", "save_json", true, t, "save_json"), button("back", "back", true, t, "back")))));
        else rows.add(group("templates", t, new Column.Buttons(List.of(button("back", "back", true, t, "back"), button("refresh", "refresh", true, t, "refresh"))),
                new Column.Buttons(List.of(button("open_folder", "open_folder", true, t, "open_folder")))));
        return new Rows(List.of(), rows);
    }

    // ---- The column drawing on the glass ----

    /** A connection on the drawing: its index (D1 is 0), its node (its tray, from 0), and a pumparound's return node. */
    public record Mark(int index, int node, int other) {
    }

    /**
     * What the drawing shows: the interior trays; the feed's node; the side draws, pumparounds and steam feeds; the
     * node and the connection selected; each node's temperature as a share of the column's span (null before a result).
     */
    public record DrawingInput(int trays, int feed, List<Mark> draws, List<Mark> pumparounds, List<Mark> steams, int node, Connection selected, int index,
                               double @Nullable [] shares) {
        public DrawingInput {
            draws = List.copyOf(draws);
            pumparounds = List.copyOf(pumparounds);
            steams = List.copyOf(steams);
        }
    }

    /** A place on the drawing a click acts on: its rect, its action ({@code tray:<node>}, {@code feed}, {@code draw:<i>}, {@code pa:<i>}, {@code steam:<i>}), its hover. */
    public record Hit(Rect r, String action, String hint) {
    }

    /** The drawing as placed in its box: the shell's axis, the tray pitch, the content's height, the top's offset, the click places. */
    public record Drawing(DrawingInput in, Rect box, int axis, int pitch, int height, int top, List<Hit> hits) {
        public Drawing {
            hits = List.copyOf(hits);
        }

        /** A node's y on the glass. */
        public int y(int node) {
            return top + 14 + node * pitch;
        }

        /** The action at glass point ({@code x}, {@code y}), or null; a connection's before a tray's. */
        public @Nullable Hit at(double x, double y) {
            if (!box.contains(x, y)) return null;
            for (var h : hits) if (h.r().contains(x, y)) return h;
            return null;
        }
    }

    /** The tray pitch for {@code trays} interior trays in a box {@code height} high: 3 px at least, so a tray stays a click target. */
    public static int pitch(int trays, int height) {
        if (trays < 0 || trays > 64) throw new IllegalArgumentException("Tray count outside diagram bounds");
        return Math.max(3, (height - 28) / (trays + 1));
    }

    /** The drawing's height: the heads and labels, 28 px, and a pitch a tray; taller than the box, the glass scrolls. */
    public static int drawingHeight(int trays, int height) {
        return 28 + (trays + 1) * pitch(trays, height);
    }

    /**
     * The drawing in {@code box}, moved up by {@code offset}: the shell centred with its marks, the right kept where the
     * box is narrower (the draw marks, the bar where it scrolls); the trays, the feed's name, each connection's mark
     * answering clicks and hovers. {@code tray} gives a tray's hover.
     */
    public static Drawing drawing(DrawingInput in, Rect box, int offset, Measure m, HeatControlColumn.Texts t, IntFunction<String> tray) {
        int n = in.trays(), pitch = pitch(n, box.h()), height = 28 + (n + 1) * pitch;
        int pa = in.pumparounds().stream().mapToInt(Mark::index).max().orElse(-1), steam = in.steams().stream().mapToInt(Mark::index).max().orElse(-1);
        int left = 4 + Math.max(60, Math.max(pa < 0 ? 0 : 59 + 7 * pa, steam < 0 ? 0 : 83 + 8 * steam));
        int right = 4 + (in.draws().isEmpty() ? 26 : 42) + (height > box.h() ? ScrollView.BAR + 2 : 0);
        int axis = box.w() >= left + right ? box.x() + left + (box.w() - left - right) / 2 : box.right() - right;
        int top = box.y() - offset;
        var hits = new ArrayList<Hit>();
        var d = new Drawing(in, box, axis, pitch, height, top, List.of());
        String feed = t.get(KEY + "feed_icon");
        hits.add(new Hit(new Rect(box.x() + 3, d.y(in.feed()) - 13, Math.min(125, m.width(feed) + 8), 13), "feed", t.get(KEY + "feed_icon.hint")));
        for (var w : in.draws())
            hits.add(new Hit(new Rect(axis + 25, d.y(w.node()) - 11, Math.max(16, m.width(t.get(KEY + "draw_icon", w.index() + 1)) + 4), 13), "draw:" + w.index(),
                    t.get(KEY + "draw_icon.hint", w.index() + 1, w.node() + 1)));
        for (var p : in.pumparounds()) {
            int lane = axis - 34 - p.index() * 7, mid = (d.y(p.node()) + d.y(p.other())) / 2;
            hits.add(new Hit(new Rect(lane - 25, mid - 6, 29, 13), "pa:" + p.index(), t.get(KEY + "pa_icon.hint", p.index() + 1)));
        }
        for (var s : in.steams()) {
            int start = steamLabel(box, axis, s.index());
            hits.add(new Hit(new Rect(start - 2, d.y(s.node()) - 12, Math.max(24, m.width(t.get(KEY + "steam_icon", s.index() + 1)) + 4), 12), "steam:" + s.index(),
                    t.get(KEY + "steam_icon.hint", s.index() + 1, s.node() + 1)));
        }
        int shellTop = d.y(0) - 12, shellBottom = d.y(n + 1) + 12, half = Math.max(1, pitch / 2);
        for (int node = 0; node < n + 2; node++) {
            int y = d.y(node), hy = node == 0 ? shellTop : y - half;
            int hh = node == 0 ? y + pitch - half - shellTop : node == n + 1 ? shellBottom - hy : Math.max(3, pitch);
            hits.add(new Hit(new Rect(axis - 24, hy, 49, hh), "tray:" + node, tray.apply(node)));
        }
        return new Drawing(in, box, axis, pitch, height, top, hits);
    }

    /** Where steam feed {@code index}'s label starts: at its line's start, inside a glass too narrow for its whole line. */
    private static int steamLabel(Rect box, int axis, int index) {
        return Math.max(axis - 82 - index * 8, box.x() + 3);
    }

    /** A temperature's share of the column's span as a colour: blue for the coldest, amber for the hottest. */
    public static int temperatureColour(double share) {
        double s = Math.clamp(share, 0, 1);
        return 0xFF000000 | ((int) (77 + 173 * s) << 16) | ((int) (161 + 30 * s) << 8) | (int) (211 - 123 * s);
    }

    /** Draws {@code d} on its glass in the screens' palette; the caller clips to the box. */
    public static void draw(Canvas c, Drawing d, HeatControlColumn.Texts t) {
        var in = d.in();
        int x = d.axis(), n = in.trays();
        ColumnDiagram.shell(c, x, d.y(0) - 12, d.y(n + 1) + 12, Palette.PANEL, Palette.LINE);
        for (int node = 0; node < n + 2; node++) {
            int y = d.y(node);
            ColumnDiagram.tray(c, x, y, in.shares() == null ? Palette.MUTED : temperatureColour(in.shares()[node]));
            if (node == in.node()) c.outline(x - 23, y - 2, 47, 5, Palette.TEXT);
            if (node == 0 || node == n + 1 || (node + 1) % 5 == 0 && node <= n - 2) {
                int ly = node == 0 ? y - 9 : node == n + 1 ? y + 2 : y - 4;
                c.fill(x - 8, ly - 1, x + 14, ly + 9, Palette.PANEL);
                c.text(Integer.toString(node + 1), x - 7, ly, Palette.MUTED);
            }
        }
        int fy = d.y(in.feed());
        ColumnDiagram.feed(c, x, d.box().x() + 3, fy, Palette.ACCENT);
        c.text(t.get(KEY + "feed_icon"), d.box().x() + 3, fy - 11, Palette.ACCENT);
        var m = c.measure();
        for (var w : in.draws()) {
            int y = d.y(w.node());
            ColumnDiagram.draw(c, x, y, Palette.ACCENT);
            String label = t.get(KEY + "draw_icon", w.index() + 1);
            c.text(label, x + 27, y - 9, Palette.ACCENT);
            if (in.selected() == Connection.DRAW && in.index() == w.index()) c.outline(x + 25, y - 11, m.width(label) + 4, 12, Palette.TEXT);
        }
        for (var p : in.pumparounds()) {
            int a = d.y(p.node()), b = d.y(p.other());
            int lane = ColumnDiagram.pumparound(c, x, p.index(), a, b, Palette.WARN), mid = (a + b) / 2;
            String label = t.get(KEY + "pa_icon", p.index() + 1);
            c.text(label, lane - 24, mid - 4, Palette.WARN);
            if (in.selected() == Connection.PA && in.index() == p.index()) c.outline(lane - 26, mid - 6, m.width(label) + 4, 12, Palette.TEXT);
        }
        for (var s : in.steams()) {
            int y = d.y(s.node());
            ColumnDiagram.steam(c, x, s.index(), y, Palette.WARN);
            int start = steamLabel(d.box(), x, s.index());
            String label = t.get(KEY + "steam_icon", s.index() + 1);
            c.text(label, start, y - 10, Palette.WARN);
            if (in.selected() == Connection.STEAM && in.index() == s.index()) c.outline(start - 2, y - 12, m.width(label) + 4, 12, Palette.TEXT);
        }
    }

    // ---- The plots on the glass ----

    /** A legend's entry: its word and its series' colour. */
    public record Legend(String word, int colour) {
    }

    /** The traffic profile's four series' colours: the organic liquid, the hydrocarbon vapour, the water vapour, the aqueous liquid. */
    public static final int[] TRAFFIC = {Palette.ACCENT, Palette.WARN, 0xFF91B5FF, 0xFFD5AFF1};

    /**
     * A plot's heading at the top of {@code box}: its title wrapped, then the legend's entries (a swatch and a word in
     * its colour) side by side, wrapped. Draws it where {@code c} is not null; returns its height.
     */
    public static int heading(@Nullable Canvas c, Rect box, String title, List<Legend> legend, Measure m) {
        var lines = m.wrap(title, box.w() - 6);
        int y = box.y() + 2;
        for (var line : lines) {
            if (c != null) c.text(line, box.x() + 3, y, Palette.TEXT);
            y += PITCH;
        }
        int x = box.x() + 3;
        if (!legend.isEmpty()) {
            for (var l : legend) {
                int w = 11 + m.width(l.word());
                if (x > box.x() + 3 && x + w > box.right() - 3) {
                    x = box.x() + 3;
                    y += PITCH;
                }
                if (c != null) {
                    c.fill(x, y + 1, x + 7, y + 8, l.colour());
                    c.text(l.word(), x + 10, y, l.colour());
                }
                x += w + 8;
            }
            y += PITCH;
        }
        return y - box.y();
    }

    /** The plot's frame under a heading {@code heading} px high: the tray numbers at its left, the axis values under it. */
    public static Rect plot(Rect box, int heading) {
        return new Rect(box.x() + 24, box.y() + heading + 4, Math.max(0, box.w() - 24 - 8), Math.max(0, box.h() - heading - 4 - 14));
    }

    /** The node a click at glass y {@code y} on a plot of {@code nodes} nodes points at. */
    public static int node(Rect plot, int nodes, double y) {
        return Math.clamp((int) ((y - plot.y()) * (nodes - 1) / Math.max(1, plot.h())), 0, nodes - 1);
    }

    /**
     * A profile: each series (a value a node, NaN where the phase is absent) as a line down the plot, the nodes' rows
     * ruled at the first, the last and every fifth tray (as the drawing numbers them) with their tray numbers, the span's ends and middle under it. Returns
     * false where no series has a value.
     */
    public static boolean profile(Canvas c, Rect plot, double[][] series, int[] colours) {
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        for (var s : series) for (double v : s) if (Double.isFinite(v)) {
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        if (!Double.isFinite(min)) return false;
        if (max - min < Math.max(1, Math.abs(max)) * 1e-9) {
            double padding = Math.max(0.01, Math.abs(max) * 0.01);
            min -= padding;
            max += padding;
        }
        double span = max - min;
        int x = plot.x(), y = plot.y(), w = plot.w(), h = plot.h(), nodes = series[0].length;
        var m = c.measure();
        c.fill(x, y, x + w, y + h, Palette.PANEL);
        c.outline(x, y, w + 1, h + 1, Palette.LINE);
        // A fifth tray numbered only a line clear of the last number and of the bottom one.
        int numbered = Integer.MIN_VALUE;
        for (int n = 0; n < nodes; n++) {
            int py = y + n * h / Math.max(1, nodes - 1);
            if (n == 0 || n == nodes - 1 || (n + 1) % 5 == 0 && py - numbered >= PITCH && y + h - py >= PITCH) {
                c.text(Integer.toString(n + 1), x - 4 - m.width(Integer.toString(n + 1)), py - 4, Palette.MUTED);
                c.hLine(x, x + w, py, Palette.LINE);
                numbered = py;
            }
        }
        for (int s = 0; s < series.length; s++) {
            int px0 = 0, py0 = 0;
            boolean previous = false;
            for (int n = 0; n < nodes; n++) {
                double value = series[s][n];
                if (!Double.isFinite(value)) {
                    previous = false;
                    continue;
                }
                int px = x + (int) Math.round((value - min) / span * w), py = y + n * h / Math.max(1, nodes - 1);
                if (previous) line(c, px0, py0, px, py, colours[s]);
                px0 = px;
                py0 = py;
                previous = true;
            }
        }
        String low = Format.number(min), mid = Format.number((min + max) / 2), high = Format.number(max);
        c.text(low, x, y + h + 5, Palette.MUTED);
        c.text(mid, x + w / 2 - m.width(mid) / 2, y + h + 5, Palette.MUTED);
        c.text(high, x + w - m.width(high), y + h + 5, Palette.MUTED);
        return true;
    }

    /**
     * Each tray's pressure drop as a bar ({@code drops}: kPa a node, the interior trays' from node 1), the trays
     * numbered at the first, the last and every fifth, 0 and the largest drop under it.
     */
    public static void drops(Canvas c, Rect plot, double[] drops) {
        int n = drops.length - 2, x = plot.x(), y = plot.y(), w = plot.w(), h = plot.h();
        if (n < 2 || h < 16) return;
        double max = 0.001;
        for (int i = 2; i <= n; i++) max = Math.max(max, drops[i]);
        c.fill(x, y, x + w, y + h, Palette.PANEL);
        var m = c.measure();
        int numbered = Integer.MIN_VALUE, last = y + (n - 2) * h / (n - 1);
        for (int i = 2; i <= n; i++) {
            int py = y + (i - 2) * h / (n - 1), next = y + (i - 1) * h / (n - 1);
            c.fill(x, py, x + (int) Math.round(drops[i] / max * w), Math.max(py + 1, next - 1), Palette.ACCENT);
            if (i == 2 || i == n || (i + 1) % 5 == 0 && py - numbered >= PITCH && last - py >= PITCH) {
                c.text(Integer.toString(i + 1), x - 4 - m.width(Integer.toString(i + 1)), py, Palette.MUTED);
                numbered = py;
            }
        }
        String high = Format.number(max) + " kPa";
        c.text("0", x, y + h + 5, Palette.MUTED);
        c.text(high, x + w - m.width(high), y + h + 5, Palette.MUTED);
    }

    /** The node of the bar at glass y {@code y} on a plot of {@code drops}, or -1. */
    public static int dropNode(Rect plot, double[] drops, double y) {
        int n = drops.length - 2;
        if (n < 2 || y < plot.y() || y >= plot.bottom()) return -1;
        for (int i = 2; i <= n; i++) {
            int py = plot.y() + (i - 2) * plot.h() / (n - 1), next = plot.y() + (i - 1) * plot.h() / (n - 1);
            if (y >= py && y < Math.max(py + 1, next)) return i;
        }
        return -1;
    }

    private static void line(Canvas c, int x0, int y0, int x1, int y1, int colour) {
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        for (int i = 0; i <= steps; i++) {
            double f = steps == 0 ? 0 : (double) i / steps;
            int x = (int) Math.round(x0 + (x1 - x0) * f), y = (int) Math.round(y0 + (y1 - y0) * f);
            c.fill(x, y, x + 1, y + 1, colour);
        }
    }

    // ---- Readouts and tables on the glass ----

    /** A line's kind on the glass: a note (one cell at the left), a readout (its label at the left, its value at the right), a table's header or row, a blank line. */
    public enum Kind {
        NOTE, PAIR, HEADER, ROW, BLANK
    }

    /** A line on the glass: its kind, its cells, a note's tone, and the entry it belongs to (-1 for a header or a blank line), so a wrapped entry shares its band. */
    public record Line(Kind kind, List<String> cells, int palette, int entry) {
        public Line {
            cells = List.copyOf(cells);
        }
    }

    /** The glass's lines and its table's column widths (the name's first, then the numbers'). */
    public record Sheet(List<Line> lines, int[] widths) {
        public Sheet {
            lines = List.copyOf(lines);
        }

        public int height() {
            return lines.size() * PITCH + 4;
        }
    }

    /**
     * Builds the glass's lines {@code width} wide. No text is cut: a note wraps; a readout whose label and value do not
     * fit in one line puts its value on the lines under its label; a table's name wraps in its column, its numbers on its
     * first line, and where a word is wider than the name's column, the table stacks (each name across the glass, its
     * numbers on the line under it).
     */
    public static final class SheetBuilder {
        private final int width;
        private final Measure m;
        private final List<Line> lines = new ArrayList<>();
        private int entry;
        private int[] widths = new int[0];

        public SheetBuilder(int width, Measure m) {
            this.width = width;
            this.m = m;
        }

        public SheetBuilder note(String text, int palette) {
            for (var line : m.wrap(text, width - 6)) lines.add(new Line(Kind.NOTE, List.of(line), palette, entry));
            entry++;
            return this;
        }

        public SheetBuilder pair(String label, String value) {
            if (m.width(label) + 8 + m.width(value) <= width - 6) lines.add(new Line(Kind.PAIR, List.of(label, value), Palette.TEXT, entry));
            else {
                for (var line : m.wrap(label, width - 6)) lines.add(new Line(Kind.NOTE, List.of(line), Palette.MUTED, entry));
                for (var line : m.wrap(value, width - 6)) lines.add(new Line(Kind.NOTE, List.of(line), Palette.ACCENT, entry));
            }
            entry++;
            return this;
        }

        public SheetBuilder blank() {
            if (!lines.isEmpty()) lines.add(new Line(Kind.BLANK, List.of(""), Palette.TEXT, -1));
            return this;
        }

        /** The table: its header, then a row a name; the first cell a name, the others numbers. One table a sheet. */
        public SheetBuilder table(List<String> header, List<List<String>> rows) {
            int n = header.size();
            int[] w = new int[n];
            int numbers = 0;
            for (int i = 1; i < n; i++) {
                int most = m.width(header.get(i));
                for (var r : rows) most = Math.max(most, m.width(r.get(i)));
                w[i] = most + 8;
                numbers += w[i];
            }
            int name = width - 6 - numbers;
            boolean stacked = name < m.width(header.getFirst()) || rows.stream().flatMap(r -> Arrays.stream(r.getFirst().split(" "))).anyMatch(word -> m.width(word) > name);
            w[0] = stacked ? width - 6 : name;
            widths = w;
            lines.add(new Line(Kind.HEADER, header, Palette.MUTED, -1));
            var blank = new ArrayList<String>();
            for (int i = 1; i < n; i++) blank.add("");
            for (var r : rows) {
                var wrapped = m.wrap(r.getFirst(), w[0]);
                for (int j = 0; j < wrapped.size(); j++) {
                    var cells = new ArrayList<String>();
                    cells.add(wrapped.get(j));
                    cells.addAll(j == 0 && !stacked ? r.subList(1, n) : blank);
                    lines.add(new Line(Kind.ROW, cells, Palette.TEXT, entry));
                }
                if (stacked) {
                    var cells = new ArrayList<String>();
                    cells.add("");
                    cells.addAll(r.subList(1, n));
                    lines.add(new Line(Kind.ROW, cells, Palette.TEXT, entry));
                }
                entry++;
            }
            return this;
        }

        /** Whether a table of {@code header} and {@code rows} fits {@code width}: its numbers and its header's first cell side by side. */
        public static boolean fits(List<String> header, List<List<String>> rows, int width, Measure m) {
            int numbers = 0;
            for (int i = 1; i < header.size(); i++) {
                int most = m.width(header.get(i));
                for (var r : rows) most = Math.max(most, m.width(r.get(i)));
                numbers += most + 8;
            }
            return m.width(header.getFirst()) + numbers <= width - 6;
        }

        public Sheet build() {
            return new Sheet(lines, widths);
        }
    }

    /**
     * Draws {@code sheet} in {@code box} ({@link SheetBuilder}'s width wide), moved up by {@code offset}, in the screens'
     * palette: every second entry on its {@link DialogColumns#BAND}; a readout's value and a table's numbers
     * right-aligned in the accent tone; the header ruled under. The caller clips to {@code box}.
     */
    public static void draw(Canvas c, Sheet sheet, Rect box, int offset) {
        var m = c.measure();
        int y = box.y() + 2 - offset, right = box.right() - 3;
        for (var line : sheet.lines()) {
            if (y + PITCH > box.y() && y < box.bottom()) {
                if (line.entry() % 2 == 1) c.fill(box.x(), y - 1, box.right(), y + PITCH - 1, DialogColumns.BAND);
                var cells = line.cells();
                switch (line.kind()) {
                    case NOTE -> c.text(cells.getFirst(), box.x() + 3, y, line.palette());
                    case PAIR -> {
                        c.text(cells.get(0), box.x() + 3, y, Palette.MUTED);
                        c.text(cells.get(1), right - m.width(cells.get(1)), y, Palette.ACCENT);
                    }
                    case HEADER, ROW -> {
                        boolean header = line.kind() == Kind.HEADER;
                        c.text(cells.getFirst(), box.x() + 3, y, header ? Palette.MUTED : Palette.TEXT);
                        int end = right;
                        for (int i = cells.size() - 1; i >= 1; i--) {
                            c.text(cells.get(i), end - m.width(cells.get(i)), y, header ? Palette.MUTED : Palette.ACCENT);
                            end -= sheet.widths()[i];
                        }
                        if (header) c.hLine(box.x() + 2, box.right() - 2, y + 10, Palette.LINE);
                    }
                    case BLANK -> {
                    }
                }
            }
            y += PITCH;
        }
    }

    // ---- The feed's table, with its entries ----

    /** A relative amount's box and a component's remove key. */
    public static final int AMOUNT = Column.BOX, REMOVE = 16;

    /** A component of the feed: its index, its name, its normalized share and its flows as shown (the other basis's, or null where the glass has no room). */
    public record FeedRow(int id, String name, String percent, String flow, @Nullable String other) {
    }

    /** A component's entry: its row, its name's lines, its top and height, its amount's box and remove key, where its numbers stand. */
    public record FeedEntry(FeedRow row, List<String> name, int y, int h, Rect amount, Rect remove, int textY) {
    }

    /** The table: its header cells and their left (the name, the amount) or right (the numbers) edges; the entries; its top and height; whether it stacks. */
    public record FeedTable(List<String> header, int[] edges, List<FeedEntry> entries, int top, int height, boolean stacked) {
        public FeedTable {
            header = List.copyOf(header);
            entries = List.copyOf(entries);
        }

        /** The entry at glass point y {@code y}, or null. */
        public @Nullable FeedEntry at(double y) {
            for (var e : entries) if (y >= e.y() && y < e.y() + e.h()) return e;
            return null;
        }
    }

    /**
     * The feed's table in {@code area} (its top left and width): the header (component, relative amount, share, flow,
     * the other basis's flow where {@code header} has five cells and the glass room; the flow too), ruled; each component's entry: its name wrapped in
     * its column, its amount's box ({@link #AMOUNT} px), share and flows on the box's line, its remove key at the right.
     * The numbers' columns are as wide as their widest value can be, so the boxes keep their places while the player
     * types. Where a word of a name is wider than its column, the table stacks: each name across the table, its box and
     * numbers on the line under it, the header's first cell above the others.
     */
    public static FeedTable feedTable(List<String> header, List<FeedRow> rows, Rect area, Measure m) {
        int[] edges = edges(header, area, m);
        // The other basis's flow only where the names keep 80 px; the flow only where the amount's box stays on the glass.
        if (header.size() == 5 && edges[1] - 6 - edges[0] < 80) {
            header = header.subList(0, 4);
            edges = edges(header, area, m);
        }
        if (header.size() == 4 && edges[1] < edges[0]) {
            header = header.subList(0, 3);
            edges = edges(header, area, m);
        }
        int name = edges[1] - 6 - edges[0];
        boolean stacked = name < m.width(header.getFirst()) || rows.stream().flatMap(r -> Arrays.stream(r.name().split(" "))).anyMatch(w -> m.width(w) > name);
        var entries = new ArrayList<FeedEntry>();
        int y = area.y() + (stacked ? 2 : 1) * PITCH + 2;
        for (var r : rows) {
            var lines = m.wrap(r.name(), stacked ? area.w() - 6 : name);
            int widgets = stacked ? y + 2 + lines.size() * Measure.LINE + 1 : y + 2;
            int h = stacked ? widgets - y + 18 + 3 : Math.max(22, 6 + lines.size() * Measure.LINE + 2);
            entries.add(new FeedEntry(r, lines, y, h, new Rect(edges[1], widgets, AMOUNT, 18), new Rect(area.right() - 3 - REMOVE, widgets, REMOVE, 18), widgets + 5));
            y += h;
        }
        return new FeedTable(header, edges, entries, area.y(), y - area.y(), stacked);
    }

    /** The feed table's edges: the name's and the amount's left, the numbers' right, as wide as their header or widest value can be. */
    private static int[] edges(List<String> header, Rect area, Measure m) {
        int n = header.size();
        int[] edges = new int[n];
        int end = area.right() - 3 - REMOVE - 4;
        for (int i = n - 1; i >= 2; i--) {
            edges[i] = end;
            end -= Math.max(m.width(header.get(i)), m.width(i == 2 ? "100.0" : "000000.0")) + 8;
        }
        edges[1] = end - (Math.max(AMOUNT, m.width(header.get(1))) + 6) + 6;
        edges[0] = area.x() + 3;
        return edges;
    }

    /** Draws {@code table} moved up by {@code offset}: the header ruled; every second entry on its band; a name's lines; the numbers right-aligned. The widgets draw themselves. */
    public static void draw(Canvas c, FeedTable table, Rect box, int offset) {
        var m = c.measure();
        var h = table.header();
        int hy = table.top() - offset, second = hy + (table.stacked() ? PITCH : 0);
        c.text(h.get(0), table.edges()[0], hy, Palette.MUTED);
        c.text(h.get(1), table.edges()[1], second, Palette.MUTED);
        for (int i = 2; i < h.size(); i++) c.text(h.get(i), table.edges()[i] - m.width(h.get(i)), second, Palette.MUTED);
        c.hLine(box.x() + 2, box.right() - 2, second + 10, Palette.LINE);
        for (int k = 0; k < table.entries().size(); k++) {
            var e = table.entries().get(k);
            int y = e.y() - offset;
            if (y + e.h() < box.y() || y > box.bottom()) continue;
            if (k % 2 == 1) c.fill(box.x(), y, box.right(), y + e.h(), DialogColumns.BAND);
            int first = table.stacked() ? y + 2 : e.textY() - offset;
            for (int j = 0; j < e.name().size(); j++) c.text(e.name().get(j), table.edges()[0], first + j * Measure.LINE, Palette.TEXT);
            var cells = new ArrayList<>(List.of(e.row().percent(), e.row().flow()));
            if (e.row().other() != null) cells.add(e.row().other());
            for (int i = 0; i < cells.size() && i + 2 < h.size(); i++)
                c.text(cells.get(i), table.edges()[i + 2] - m.width(cells.get(i)), e.textY() - offset, Palette.ACCENT);
        }
    }

    // ---- The templates on the glass ----

    /** A new template's name on the glass: the lines above its box (the library, the label), the box, the hint's lines under it and their top. */
    public record SaveForm(Sheet head, Rect name, Sheet hint, int hintY) {
    }

    /** The name's form in {@code box}: the library and the label, the name's box (up to 230 px), the hint wrapped under it. */
    public static SaveForm saveForm(String library, String label, String hint, Rect box, Measure m) {
        var head = new SheetBuilder(box.w(), m).note(library, Palette.MUTED).note(label, Palette.TEXT).build();
        int y = box.y() + 2 + head.lines().size() * PITCH + 2;
        var name = new Rect(box.x() + 3, y, Math.min(230, box.w() - 6), 18);
        return new SaveForm(head, name, new SheetBuilder(box.w(), m).note(hint, Palette.MUTED).build(), name.bottom() + 4);
    }
}
