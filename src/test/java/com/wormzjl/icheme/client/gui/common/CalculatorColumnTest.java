package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.CalculatorColumn.ColumnInput;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn.Connection;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn.DrawingInput;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn.EquipmentInput;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn.FeedRow;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn.Footer;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn.Kind;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn.Legend;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn.Mark;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn.Metric;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn.Page;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn.ResultsInput;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn.Rows;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn.StatusInput;
import com.wormzjl.icheme.client.gui.common.CalculatorColumn.View;
import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.KitDoor;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.science.equipment.ColumnComponents.Condenser;
import com.wormzjl.icheme.science.equipment.ColumnComponents.Pump;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The column calculator's panel door (batch 2026-10-28-gui-toolkit, GUI_REVIEW.md order 5): every text the door and the
 * screen name has its English line; every page fits at every width the screen takes (320 to 960 px) and every height,
 * with no text cut, plates in one line, widgets at least 40 px and boxes in line; the reflux entry shows at every width
 * (the review's 710 to 823 px defect); each page shows its keys; the status is said once; no widget moves while results
 * and status texts change; the glass's sheets, feed table, plots and drawing cut no text and answer their clicks.
 */
class CalculatorColumnTest {
    private static final Measure M = Measure.fixed(6);
    private static final int[] HEIGHTS = {220, 254, 400, 600};
    private static final List<String> UNITS = List.of("°C", "°F", "K");
    private static final Path SCREEN = Path.of("src/main/java/com/wormzjl/icheme/client/gui/screens/inventory/ColumnCalculatorV3Screen.java");

    /** The screen's title, the block's English name. */
    private static String blockName() {
        try (var in = CalculatorColumnTest.class.getResourceAsStream("/assets/icheme/lang/en_us.json")) {
            var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(java.util.Objects.requireNonNull(in), java.nio.charset.StandardCharsets.UTF_8));
            return json.getAsJsonObject().get("block.icheme.column_calculator_v3").getAsString();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static HeatControlColumnTest.Recording texts() {
        return new HeatControlColumnTest.Recording("gui.icheme.");
    }

    /** Every width the gates try: the range by 7 px, its ends and the split's edge. */
    private static List<Integer> widths() {
        var out = new TreeSet<Integer>();
        for (int w = CalculatorColumn.MIN_WIDTH; w <= CalculatorColumn.MAX_WIDTH; w += 7) out.add(w);
        out.addAll(List.of(CalculatorColumn.MAX_WIDTH, 463, 464, 710, 767, 823));
        return List.copyOf(out);
    }

    /** The Column page's states: each connection selected (its widest index), with and without the draft's conflict, taking edits or not, in each unit. */
    private static List<ColumnInput> columnInputs() {
        var out = new ArrayList<ColumnInput>();
        for (boolean conflict : new boolean[]{false, true})
            for (boolean editable : new boolean[]{false, true})
                for (var c : Connection.values())
                    for (var unit : UNITS)
                        out.add(new ColumnInput(conflict, editable, unit, c, c == Connection.STEAM ? 1 : 3, editable, editable, !editable, editable));
        return out;
    }

    private static List<String> streams(HeatControlColumn.Texts t) {
        return List.of(t.get("column.stream.overhead_vapor"), t.get("column.stream.distillate_liquid"), t.get("column.stream.bottoms_liquid"),
                t.get("column.stream.free_water"), t.get("column.stream.side_draw", 64));
    }

    /** The Results page's states: each view, each profile, the widest stream names and tray number, before a draft, with no products. */
    private static List<ResultsInput> resultsInputs(HeatControlColumn.Texts t) {
        var out = new ArrayList<ResultsInput>();
        for (boolean conflict : new boolean[]{false, true})
            for (var v : View.values())
                for (var m : v == View.PROFILES ? Metric.values() : new Metric[]{Metric.TEMPERATURE}) {
                    out.add(new ResultsInput(conflict, v, m, streams(t), 4, 63, 62));
                    out.add(new ResultsInput(conflict, v, m, List.of(), 0, -1, 0));
                }
        return out;
    }

    private static List<EquipmentInput> equipmentInputs() {
        var out = new ArrayList<EquipmentInput>();
        for (boolean conflict : new boolean[]{false, true})
            for (var condenser : Condenser.values())
                for (var pumps : List.of(List.<Pump>of(), List.of(Pump.STANDARD, Pump.IN_LINE, Pump.STANDARD, Pump.IN_LINE)))
                    for (boolean saves : new boolean[]{false, true}) out.add(new EquipmentInput(conflict, condenser, saves ? Pump.IN_LINE : Pump.STANDARD, pumps, saves));
        return out;
    }

    /** Every door the screen shows, named. */
    private static Map<String, Rows> pages(HeatControlColumn.Texts t) {
        var out = new LinkedHashMap<String, Rows>();
        for (var in : columnInputs()) out.put("column " + in, CalculatorColumn.column(in, t));
        for (boolean conflict : new boolean[]{false, true})
            for (boolean editable : new boolean[]{false, true})
                for (var unit : UNITS) out.put("feed " + conflict + " " + editable + " " + unit, CalculatorColumn.feed(conflict, editable, unit, t));
        for (var in : resultsInputs(t)) out.put("results " + in, CalculatorColumn.results(in, t));
        for (var in : equipmentInputs()) out.put("equipment " + in, CalculatorColumn.equipment(in, t));
        for (boolean conflict : new boolean[]{false, true})
            for (boolean saving : new boolean[]{false, true}) out.put("presets " + conflict + " " + saving, CalculatorColumn.presets(conflict, saving, t));
        return out;
    }

    /** The status's states, one for each line it can say. */
    private static List<StatusInput> statusInputs() {
        return List.of(new StatusInput(true, true, "Refused", "Saved", true, false, true, "Invalid", true, true, null),
                new StatusInput(false, true, "Refused", "Saved", true, false, true, "Invalid", true, true, "Engine"),
                new StatusInput(false, false, "Refused", "Saved", true, false, true, "Invalid", true, true, null),
                new StatusInput(false, false, "", "Saved", true, false, true, "Invalid", true, true, null),
                new StatusInput(false, false, "", "", true, false, true, "Invalid", true, true, null),
                new StatusInput(false, false, "", "", true, true, true, "Invalid", true, true, null),
                new StatusInput(false, false, "", "", false, false, true, "Invalid", true, true, null),
                new StatusInput(false, false, "", "", false, false, false, "Invalid", true, true, null),
                new StatusInput(false, false, "", "", false, false, false, null, true, true, "Engine"),
                new StatusInput(false, false, "", "", false, false, false, null, false, true, null),
                new StatusInput(false, false, "", "", false, false, false, null, false, false, null));
    }

    /** The drawing's largest state: three draws, four pumparounds, two steam feeds, a result's colours. */
    private static DrawingInput drawingInput(int trays, Connection selected, int index) {
        int last = trays + 1;
        var draws = new ArrayList<Mark>();
        var pas = new ArrayList<Mark>();
        var steams = new ArrayList<Mark>();
        if (trays >= 26) {
            draws.addAll(List.of(new Mark(0, 5, 0), new Mark(1, 12, 0), new Mark(2, 18, 0)));
            pas.addAll(List.of(new Mark(0, 8, 6), new Mark(1, 14, 11), new Mark(2, 20, 17), new Mark(3, 24, 22)));
            steams.addAll(List.of(new Mark(0, last, 0), new Mark(1, last - 2, 0)));
        }
        double[] shares = new double[trays + 2];
        for (int i = 0; i < shares.length; i++) shares[i] = (double) i / Math.max(1, last);
        return new DrawingInput(trays, Math.max(0, trays / 2), draws, pas, steams, Math.min(3, last), selected, index, shares);
    }

    /**
     * The keys the screen asks for itself, as written in its source: each literal that opens the first argument of a
     * {@code tr(} (under {@code column.}) or a {@code lang(} call, or follows a {@code ?} or a {@code :} in it; not a
     * key's prefix (it ends in a dot or a {@code +} follows it).
     */
    private static Set<String> screenKeys() throws IOException {
        var source = Files.readString(SCREEN);
        var keys = new TreeSet<String>();
        var call = Pattern.compile("\\b(tr|lang)\\(").matcher(source);
        var literal = Pattern.compile("(^|[?:])\\s*\"([a-z_][a-z0-9_.]*)\"(?!\\s*\\+)");
        while (call.find()) {
            var arg = new StringBuilder();
            int depth = 0;
            for (int i = call.end(); i < source.length(); i++) {
                char ch = source.charAt(i);
                if (ch == '"' || ch == '\'') {
                    int end = i + 1;
                    while (source.charAt(end) != ch) end += source.charAt(end) == '\\' ? 2 : 1;
                    arg.append(source, i, end + 1);
                    i = end;
                    continue;
                }
                if (ch == '(') depth++;
                else if (ch == ')' && depth-- == 0) break;
                else if (ch == ',' && depth == 0) break;
                arg.append(ch);
            }
            var m = literal.matcher(arg);
            while (m.find()) if (!m.group(2).endsWith(".")) keys.add((call.group(1).equals("tr") ? "column." : "") + m.group(2));
        }
        var full = Pattern.compile("\"gui\\.icheme\\.([a-z_][a-z0-9_.]*)\"").matcher(source);
        while (full.find()) if (!full.group(1).endsWith(".")) keys.add(full.group(1));
        return keys;
    }

    @Test
    void everyTextTheScreenNamesHasItsEnglishLine() throws IOException {
        var t = texts();
        pages(t);
        for (var p : Page.values()) CalculatorColumn.tabs(p, t);
        for (boolean browsing : new boolean[]{false, true})
            for (boolean feed : new boolean[]{false, true})
                for (boolean mass : new boolean[]{false, true}) CalculatorColumn.footerButtons(new Footer(browsing, feed, !browsing, mass, "°C"), t);
        for (var in : statusInputs()) CalculatorColumn.status(in, t);
        for (var c : Connection.values()) {
            var in = drawingInput(30, c, 1);
            var d = CalculatorColumn.drawing(in, new Rect(11, 77, 200, 300), 0, M, t, node -> "tray " + node);
            CalculatorColumn.draw(new Recording(), d, t);
        }
        var keys = screenKeys();
        assertTrue(keys.size() > 60 && keys.contains("column.reading.pressure") && keys.contains("fluid.theme.dark"), "the screen's own keys found: " + keys);
        for (var key : keys) t.get(key);
        assertEquals(Set.of(), t.missing);
        assertTrue(t.asked.size() > 180, "the cases ask for the screen's texts: " + t.asked.size());
    }

    /** A plate in one line, no text cut (a key's text in its key too), widgets at least 40 px (a stepper its own width) inside their rows, one per key, boxes in line. */
    private static void assertFits(Column column, Rect area, String at) {
        for (var p : column.rows()) {
            assertTrue(p.rect().x() >= area.x() && p.rect().right() <= area.right(), at + ": a row outside its column " + p.rect());
            for (var text : p.texts()) assertTrue(M.width(text) <= p.rect().w(), at + ": a text cut: " + text);
            for (var e : p.slots().entrySet()) {
                var s = e.getValue();
                assertTrue(s.inside(p.rect()), at + ": a widget outside its row " + e.getKey());
                boolean stepper = p.row() instanceof Column.Setting setting && !e.getKey().equals(setting.key());
                assertTrue(stepper ? s.w() == Column.STEP : s.w() >= 40, at + ": a widget too narrow " + e.getKey() + " " + s);
            }
            if (p.row() instanceof Column.Buttons b)
                for (var spec : b.buttons()) assertTrue(M.width(spec.text()) <= p.slots().get(spec.key()).w() - 6, at + ": a key's text cut: " + spec.text());
            if (p.row() instanceof Column.Group g) assertEquals(1, p.texts().size(), at + ": a plate in one line: " + g.title());
        }
        column.slots();
        DeviceColumnTest.assertInLine(column, at);
    }

    /** Each page's columns as the door at {@code width} places them. */
    private static List<Column> place(Rows rows, int width, int height) {
        var areas = CalculatorColumn.door(width, height, rows.two());
        var lists = rows.at(width);
        assertEquals(areas.size(), lists.size(), width + ": one row list a column");
        var out = new ArrayList<Column>();
        for (int i = 0; i < areas.size(); i++) {
            var a = areas.get(i);
            out.add(Column.place(lists.get(i), a.x(), a.y(), a.w(), M, Column.Metrics.COMPACT));
        }
        return out;
    }

    @Test
    void everyPageFitsAtEveryWidthTheScreenTakes() {
        var t = texts();
        var pages = pages(t);
        for (int width : widths()) {
            for (var e : pages.entrySet()) {
                var areas = CalculatorColumn.door(width, 254, e.getValue().two());
                var columns = place(e.getValue(), width, 254);
                for (int i = 0; i < columns.size(); i++) assertFits(columns.get(i), areas.get(i), width + " " + e.getKey());
            }
        }
    }

    @Test
    void theFrameFitsAtEveryWidthAndHeight() {
        var t = texts();
        var statuses = new ArrayList<String>();
        for (var key : List.of("waiting", "conflict", "solving", "design_saving", "failed", "accepted", "draft_changed", "ready", "request_rejected",
                "invalid_input", "invalid_mixture", "invalid_draw", "invalid_steam", "invalid_pa", "save_failed", "preset_incompatible", "report_copied"))
            statuses.add(t.get("column." + key));
        String title = blockName();
        for (int width : widths())
            for (int height : HEIGHTS) {
                String at = width + " by " + height;
                for (boolean two : new boolean[]{false, true}) {
                    var areas = CalculatorColumn.door(width, height, two);
                    var glass = CalculatorColumn.glass(width, height, two);
                    var last = areas.getLast();
                    assertEquals(two && CalculatorColumn.split(width) ? 2 : 1, areas.size(), at);
                    assertEquals(width - 20, last.right(), at + ": the last column 20 px in, its bar between");
                    assertTrue(last.right() + 4 <= CalculatorColumn.bar(width) && CalculatorColumn.bar(width) + ScrollView.BAR <= width - 8, at + ": the bar beside the columns");
                    if (areas.size() == 2) assertEquals(areas.getFirst().right() + 12, last.x(), at + ": the columns 12 px apart");
                    for (var a : areas) {
                        assertEquals(CalculatorColumn.columnWidth(width), a.w(), at);
                        assertTrue(a.y() == CalculatorColumn.TOP && a.bottom() <= height - CalculatorColumn.DIVIDER - 2, at + ": a column above the footer's divider");
                    }
                    assertTrue(glass.x() == 5 && glass.right() + 8 == areas.getFirst().x() && glass.y() == CalculatorColumn.TOP - 3 && glass.bottom() == last.bottom(),
                            at + ": the glass beside the columns " + glass);
                    assertTrue(glass.w() >= CalculatorColumn.GLASS, at + ": the glass " + glass.w() + " px");
                    var dividers = CalculatorColumn.dividers(width, height, two);
                    assertTrue(dividers.getFirst() > glass.right() && dividers.getFirst() < areas.getFirst().x(), at + ": the divider between the glass and the door");
                    if (areas.size() == 2) assertTrue(dividers.getLast() > areas.getFirst().right() && dividers.getLast() < last.x(), at + ": the divider between the columns");
                }
                var display = CalculatorColumn.display(width);
                assertEquals(width - 10, display.right(), at);
                for (var s : statuses) assertTrue(M.width(s) <= display.w(), at + ": the status cut: " + s);
                assertTrue(M.width(title) <= CalculatorColumn.title(width) && CalculatorColumn.theme(width).right() == width - 10, at + ": the title row");
                var tabs = CalculatorColumn.tabs(Page.COLUMN, t);
                var tabSlots = CalculatorColumn.tabSlots(width);
                assertEquals(tabs.size(), tabSlots.size());
                for (int i = 0; i < tabs.size(); i++) {
                    var r = tabSlots.get(i);
                    assertTrue(r.x() >= 10 && r.right() <= width - 10 && M.width(tabs.get(i).text()) <= r.w() - 6, at + ": a tab " + r);
                    if (i > 0) assertFalse(tabSlots.get(i - 1).overlaps(r), at + ": the tabs apart");
                }
                var footer = CalculatorColumn.footer(width, height);
                var keys = CalculatorColumn.keys(width, height);
                for (boolean feed : new boolean[]{false, true}) {
                    var buttons = CalculatorColumn.footerButtons(new Footer(false, feed, true, true, "°F"), t);
                    assertEquals(footer.size(), buttons.size());
                    for (int i = 0; i < buttons.size(); i++) {
                        var r = footer.get(i);
                        assertTrue(r.x() >= 10 && r.right() <= width - 10 && r.y() > height - CalculatorColumn.DIVIDER && r.w() >= 40
                                && M.width(buttons.get(i).text()) <= r.w() - 6, at + ": a footer key " + r);
                        if (i > 0) assertFalse(footer.get(i - 1).overlaps(r), at + ": the footer's keys apart");
                        assertEquals(i >= 3, r.inside(keys), at + ": the display keys on their tray, the push buttons before it");
                    }
                }
                assertTrue(keys.right() <= width - 6 && keys.bottom() <= height - 4, at + ": the tray inside the door");
            }
    }

    /** The review's defect: the reflux entry hidden from 710 to 823 px. It stands in view at every width where the door splits, and a scroll reaches it where it does not. */
    @Test
    void theRefluxEntryShowsAtEveryWidth() {
        var t = texts();
        for (int width : widths())
            for (int height : HEIGHTS)
                for (var in : columnInputs()) {
                    String at = width + " by " + height + " " + in;
                    var rows = CalculatorColumn.column(in, t);
                    var areas = CalculatorColumn.door(width, height, rows.two());
                    var door = new KitDoor();
                    door.layout(areas, CalculatorColumn.bar(width), rows.at(width), M);
                    Rect reflux = null, area = null;
                    for (int i = 0; i < door.columns().size(); i++)
                        if (door.columns().get(i).slots().containsKey("s6")) {
                            reflux = door.columns().get(i).slots().get("s6");
                            area = areas.get(i);
                        }
                    assertNotNull(reflux, at + ": the reflux entry on the Column page");
                    assertTrue(reflux.x() >= area.x() && reflux.right() <= area.right(), at + ": the reflux entry inside its column " + reflux);
                    var view = door.view().viewport();
                    if (CalculatorColumn.split(width)) assertTrue(reflux.bottom() <= view.bottom(), at + ": the reflux entry in view " + reflux);
                    else assertTrue(reflux.bottom() - door.view().max() <= view.bottom() && reflux.y() >= view.y(), at + ": a scroll reaches the reflux entry " + reflux);
                }
    }

    private static List<String> keys(List<Column.Row> rows) {
        return List.copyOf(Column.place(rows, 0, 0, CalculatorColumn.WIDE, M, Column.Metrics.COMPACT).slots().keySet());
    }

    private static Map<String, Column.Button> buttons(List<Column.Row> rows) {
        var out = new LinkedHashMap<String, Column.Button>();
        for (var b : ConstructorColumn.buttons(rows)) out.put(b.key(), b);
        return out;
    }

    @Test
    void eachPageShowsItsKeys() {
        var t = texts();
        var none = CalculatorColumn.column(new ColumnInput(false, true, "°C", Connection.NONE, 0, true, true, true, true), t);
        assertTrue(none.two());
        assertEquals(List.of("s2", "s9", "s7", "s0", "s1", "s3"), keys(none.left()));
        assertEquals(List.of("s4", "s6", "s5", "add:draw", "add:pa", "add:steam"), keys(none.right()));
        assertEquals(List.of(none.left(), none.right()), none.at(464));
        assertEquals(List.of(none.all()), none.at(463));
        var conflict = CalculatorColumn.column(new ColumnInput(true, true, "°C", Connection.NONE, 0, true, true, true, true), t);
        assertEquals(List.of("use_server", "keep_draft"), keys(conflict.left()).subList(0, 2));
        assertEquals(List.of("s4", "s6", "s5", "add:draw", "add:pa", "add:steam", "d2rate", "d2stage", "remove"),
                keys(CalculatorColumn.column(new ColumnInput(false, true, "°C", Connection.DRAW, 2, true, true, true, true), t).right()));
        var pa = CalculatorColumn.column(new ColumnInput(false, true, "°C", Connection.PA, 1, true, false, true, false), t);
        assertEquals(List.of("c1duty", "c1draw", "c1return", "split:uniform", "split:return", "remove"), keys(pa.right()).subList(6, 12));
        var split = buttons(pa.right());
        assertTrue(split.get("split:uniform").latched() && !split.get("split:uniform").active() && split.get("split:return").active() && !split.get("split:return").latched());
        assertFalse(split.get("add:draw").active() || split.get("add:steam").active());
        assertTrue(split.get("add:pa").active());
        assertEquals(t.get("column.group.pa", 2), ((Column.Group) pa.right().getLast()).title());
        var readOnly = buttons(CalculatorColumn.column(new ColumnInput(false, false, "°C", Connection.STEAM, 1, false, false, false, false), t).right());
        assertFalse(readOnly.get("remove").active() || readOnly.get("add:draw").active());
        assertEquals(List.of("t1rate", "t1stage", "t1T", "remove"),
                keys(CalculatorColumn.column(new ColumnInput(false, true, "°C", Connection.STEAM, 1, true, true, true, true), t).right()).subList(6, 10));

        var feed = CalculatorColumn.feed(false, true, "K", t);
        assertFalse(feed.two());
        assertEquals(List.of("s0", "s1", "s3", "clear"), keys(feed.right()));
        assertFalse(buttons(CalculatorColumn.feed(false, false, "K", t).right()).get("clear").active());

        var views = List.of("view:profiles", "view:products", "view:heat", "view:tray", "view:solver");
        var streams = streams(t);
        assertEquals(concat(views, List.of("metric:temperature", "metric:pressure", "metric:drop", "metric:traffic")),
                keys(CalculatorColumn.results(new ResultsInput(false, View.PROFILES, Metric.DROP, streams, 0, 3, 30), t).right()));
        assertEquals(concat(views, List.of("stream:0", "stream:1", "stream:2", "stream:3", "stream:4")),
                keys(CalculatorColumn.results(new ResultsInput(false, View.PRODUCTS, Metric.DROP, streams, 0, 3, 30), t).right()));
        assertEquals(views, keys(CalculatorColumn.results(new ResultsInput(false, View.PRODUCTS, Metric.DROP, List.of(), 0, 3, 30), t).right()));
        assertEquals(views, keys(CalculatorColumn.results(new ResultsInput(false, View.HEAT, Metric.DROP, streams, 0, 3, 30), t).right()));
        assertEquals(concat(views, List.of("tray", "tray-", "tray+")), keys(CalculatorColumn.results(new ResultsInput(false, View.TRAY, Metric.DROP, streams, 0, 3, 30), t).right()));
        assertEquals(views, keys(CalculatorColumn.results(new ResultsInput(false, View.TRAY, Metric.DROP, streams, 0, -1, 0), t).right()));
        assertEquals(concat(views, List.of("copy_report")), keys(CalculatorColumn.results(new ResultsInput(false, View.SOLVER, Metric.DROP, streams, 0, 3, 30), t).right()));
        var profile = buttons(CalculatorColumn.results(new ResultsInput(false, View.PROFILES, Metric.DROP, streams, 0, 3, 30), t).right());
        assertTrue(profile.get("view:profiles").latched() && !profile.get("view:profiles").active() && profile.get("view:heat").active());
        assertTrue(profile.get("metric:drop").latched() && profile.get("metric:temperature").active() && !profile.get("metric:temperature").latched());

        var equipment = CalculatorColumn.equipment(new EquipmentInput(false, Condenser.EXCHANGER, Pump.IN_LINE, List.of(Pump.STANDARD, Pump.IN_LINE), false), t);
        assertEquals(List.of("condenser:air_cooler", "condenser:exchanger", "reflux:standard", "reflux:in_line", "pa0:standard", "pa0:in_line", "pa1:standard",
                "pa1:in_line", "save_design"), keys(equipment.right()));
        var chosen = buttons(equipment.right());
        assertTrue(chosen.get("condenser:exchanger").latched() && chosen.get("reflux:in_line").latched() && chosen.get("pa0:standard").latched() && chosen.get("pa1:in_line").latched());
        assertFalse(chosen.get("save_design").active());

        assertEquals(List.of("save_json", "back"), keys(CalculatorColumn.presets(false, true, t).right()));
        assertEquals(List.of("back", "refresh", "open_folder"), keys(CalculatorColumn.presets(false, false, t).right()));

        // The tabs: the page shown latched; the footer: Templates latched while they show, Solve off while it cannot act, the basis and the unit as words.
        var tabs = CalculatorColumn.tabs(Page.RESULTS, t);
        assertEquals(List.of("tab:column", "tab:feed", "tab:results", "tab:equipment"), tabs.stream().map(Column.Button::key).toList());
        assertTrue(tabs.get(2).latched() && !tabs.get(2).active() && tabs.get(0).active() && !tabs.get(0).latched());
        var footer = CalculatorColumn.footerButtons(new Footer(true, false, false, true, "°F"), t);
        assertEquals(List.of("templates", "solve", "save", "basis", "temperature"), footer.stream().map(Column.Button::key).toList());
        assertTrue(footer.get(0).latched() && !footer.get(0).active() && !footer.get(1).active() && footer.get(3).soft() && footer.get(4).soft());
        assertEquals(List.of(t.get("fluid.mass_basis_short"), "°F"), List.of(footer.get(3).text(), footer.get(4).text()));
        assertEquals(t.get("column.mixture_templates.hint"), CalculatorColumn.footerButtons(new Footer(false, true, true, false, "K"), t).get(0).hint());
    }

    private static List<String> concat(List<String> a, List<String> b) {
        var out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }

    /** The status, said once: in order of what the player must act on first; the engine's own line on its hover only. */
    @Test
    void theStatusIsSaidOnceInOrder() {
        var t = texts();
        var said = statusInputs().stream().map(in -> CalculatorColumn.status(in, t)).toList();
        assertEquals(List.of(t.get("column.waiting"), t.get("column.conflict"), "Refused", "Saved", t.get("column.solving"), t.get("column.design_saving"),
                t.get("column.failed"), "Invalid", t.get("column.accepted"), t.get("column.draft_changed"), t.get("column.ready")), said.stream().map(CalculatorColumn.Status::text).toList());
        assertEquals(List.of(Palette.MUTED, Palette.WARN, Palette.WARN, Palette.ACCENT, Palette.MUTED, Palette.MUTED, Palette.WARN, Palette.WARN, Palette.ACCENT,
                Palette.MUTED, Palette.MUTED), said.stream().map(CalculatorColumn.Status::palette).toList());
        assertEquals("Engine", said.get(1).hint());
        assertEquals("Engine", said.get(8).hint());
        assertNull(said.get(0).hint());
    }

    /** The screen places its widgets at a rebuild and its texts each frame: what the engine answers and the player chooses on a page moves no widget. */
    @Test
    void aWidgetKeepsItsSlotWhileResultsAndStatusChange() {
        var t = texts();
        for (int width : new int[]{320, 463, 464, 600, 767, 960}) {
            var groups = new LinkedHashMap<String, List<Rows>>();
            for (var in : columnInputs()) groups.computeIfAbsent("column " + in.conflict() + " " + in.selected(), k -> new ArrayList<>()).add(CalculatorColumn.column(in, t));
            for (boolean conflict : new boolean[]{false, true})
                for (var v : View.values()) {
                    var list = new ArrayList<Rows>();
                    for (var m : Metric.values())
                        for (int tray : new int[]{0, 8, 63})
                            for (int stream = 0; stream < 5; stream++) {
                                var names = streams(t).stream().map(n -> n + (tray > 8 ? " and more" : "")).toList();
                                list.add(CalculatorColumn.results(new ResultsInput(conflict, v, m, names, stream, tray, 62), t));
                            }
                    groups.put("results " + conflict + " " + v, list);
                }
            for (boolean conflict : new boolean[]{false, true}) {
                var list = new ArrayList<Rows>();
                for (var in : equipmentInputs()) if (in.conflict() == conflict && in.pumparounds().size() == 4) list.add(CalculatorColumn.equipment(in, t));
                groups.put("equipment " + conflict, list);
                var feeds = new ArrayList<Rows>();
                for (boolean editable : new boolean[]{false, true}) for (var unit : UNITS) feeds.add(CalculatorColumn.feed(conflict, editable, unit, t));
                groups.put("feed " + conflict, feeds);
            }
            for (var e : groups.entrySet()) {
                List<Map<String, Rect>> first = null;
                for (var rows : e.getValue()) {
                    var slots = place(rows, width, 254).stream().map(Column::slots).toList();
                    if (first == null) first = slots;
                    else assertEquals(first, slots, width + " " + e.getKey());
                }
            }
        }
    }

    // ---- The glass ----

    /** A canvas that records its fills and its texts with their places. */
    private static final class Recording implements Canvas {
        final List<int[]> fills = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        final List<Rect> places = new ArrayList<>();

        @Override
        public Measure measure() {
            return M;
        }

        @Override
        public void fill(int x0, int y0, int x1, int y1, int argb) {
            fills.add(new int[]{x0, y0, x1, y1, argb});
        }

        @Override
        public void text(String text, int x, int y, int argb) {
            texts.add(text);
            places.add(new Rect(x, y, M.width(text), 9));
        }

        @Override
        public void text(String text, float x, float y, float scale, int argb) {
            text(text, (int) x, (int) y, argb);
        }

        @Override
        public void clip(Rect area) {
        }

        @Override
        public void unclip() {
        }
    }

    private static final List<String> NAMES = List.of("Water", "n-Hexane", "Ethylbenzene and its isomers", "1,2,4-Trimethylbenzene", "Hydrogen sulfide");

    /** No text cut on a sheet: a note wraps, a readout stacks its value under its label, a table wraps its names or stacks; every second entry on a band. */
    @Test
    void theSheetsCutNoTextAndBandEverySecondEntry() {
        var header = List.of("Component", "Mol %", "kmol/h");
        var rows = NAMES.stream().map(n -> List.of(n, "100.0", "12345.6")).toList();
        for (int width = 143; width <= 560; width += 9) {
            var sheet = new CalculatorColumn.SheetBuilder(width, M).note("The accepted result: the solver's checks and its evidence, in full words.", Palette.TEXT)
                    .pair("Pressure drop", "12.3 kPa").pair("The column's worst flooding fraction", "0.87 at tray 12").blank().table(header, rows).build();
            int entries = 0;
            for (var line : sheet.lines()) {
                var cells = line.cells();
                switch (line.kind()) {
                    case NOTE -> assertTrue(M.width(cells.getFirst()) <= width - 6, width + ": a note cut: " + cells);
                    case PAIR -> assertTrue(M.width(cells.get(0)) + 8 + M.width(cells.get(1)) <= width - 6, width + ": a readout cut: " + cells);
                    case HEADER, ROW -> {
                        assertTrue(M.width(cells.getFirst()) <= sheet.widths()[0], width + ": a name cut: " + cells);
                        int numbers = 0;
                        for (int i = 1; i < cells.size(); i++) {
                            assertTrue(M.width(cells.get(i)) <= sheet.widths()[i] - 8, width + ": a number wider than its column");
                            numbers += sheet.widths()[i];
                        }
                        boolean stacked = sheet.widths()[0] == width - 6;
                        assertTrue(stacked || sheet.widths()[0] + numbers <= width - 6, width + ": the table wider than the glass");
                    }
                    case BLANK -> {
                    }
                }
                entries = Math.max(entries, line.entry() + 1);
            }
            assertEquals(3 + NAMES.size(), entries, width + ": every entry");
            assertEquals(rows.size(), sheet.lines().stream().filter(l -> l.kind() == Kind.ROW && l.cells().contains("12345.6")).count(), width + ": each row's numbers once");
            var canvas = new Recording();
            var box = new Rect(0, 0, width, 10_000);
            CalculatorColumn.draw(canvas, sheet, box, 0);
            long bands = canvas.fills.stream().filter(f -> f[4] == DialogColumns.BAND).count();
            assertEquals(sheet.lines().stream().filter(l -> l.entry() % 2 == 1).count(), bands, width + ": the bands");
            for (var place : canvas.places) assertTrue(place.x() >= 0 && place.right() <= width, width + ": a text outside the glass " + place);
            assertEquals(sheet.lines().size() * CalculatorColumn.PITCH + 4, sheet.height());
        }
        assertFalse(new CalculatorColumn.SheetBuilder(400, M).table(header, rows).build().widths()[0] == 394, "the table stacks only where a word is wider than the names' column");
        assertEquals(137, new CalculatorColumn.SheetBuilder(143, M).table(header, rows).build().widths()[0], "the table stacks on the narrowest glass");
    }

    /** The feed's table: no name cut; the boxes keep their slots while the player types and the result changes; the numbers beside the box, the remove key inside. */
    @Test
    void theFeedTableCutsNoNameAndKeepsItsBoxes() {
        var t = texts();
        for (boolean mass : new boolean[]{false, true}) {
            var header = List.of(t.get("column.component"), t.get("column.relative_amount"), t.get(mass ? "column.mass_percent" : "column.mole_percent"),
                    t.get(mass ? "column.mass_rate" : "column.molar_rate"), t.get(mass ? "column.molar_rate" : "column.mass_rate"));
            for (int width = 143; width <= 560; width += 9) {
                var area = new Rect(11, 101, width - ScrollView.BAR - 2, 0);
                var blank = new ArrayList<FeedRow>();
                var shown = new ArrayList<FeedRow>();
                for (int i = 0; i < NAMES.size(); i++) {
                    blank.add(new FeedRow(i, NAMES.get(i), "", "", ""));
                    shown.add(new FeedRow(i, NAMES.get(i), i == 0 ? "100.0" : "0.01", "123456.7", "—"));
                }
                var before = CalculatorColumn.feedTable(header, blank, area, M);
                var after = CalculatorColumn.feedTable(header, shown, area, M);
                assertEquals(before.entries().stream().map(e -> List.of(e.amount(), e.remove())).toList(), after.entries().stream().map(e -> List.of(e.amount(), e.remove())).toList(),
                        width + ": the boxes keep their slots");
                int name = before.edges()[1] - 6 - before.edges()[0];
                for (var e : after.entries()) {
                    for (var line : e.name()) assertTrue(M.width(line) <= (after.stacked() ? area.w() - 6 : name), width + ": a name cut: " + line);
                    assertTrue(e.amount().x() >= area.x() && e.remove().right() <= area.right() - 3 && e.amount().w() == CalculatorColumn.AMOUNT, width + ": the boxes inside");
                    assertTrue(e.amount().right() <= after.edges()[2] - M.width("100.0"), width + ": the share beside the box");
                    assertTrue(after.edges()[after.header().size() - 1] <= e.remove().x() - 4, width + ": the numbers before the remove key");
                    assertNotNull(after.at(e.y() + 1));
                    assertEquals(e, after.at(e.y() + e.h() - 1));
                }
                var canvas = new Recording();
                CalculatorColumn.draw(canvas, after, new Rect(11, 77, width, 10_000), 0);
                for (var place : canvas.places) assertTrue(place.x() >= 11 && place.right() <= 11 + width, width + ": a text outside the glass " + place);
                var heads = canvas.places.subList(0, after.header().size());
                for (int i = 1; i < heads.size(); i++) assertFalse(heads.get(i - 1).overlaps(heads.get(i)), width + ": the header's cells apart " + heads);
                assertTrue(after.entries().getFirst().y() >= heads.getLast().bottom() + 2, width + ": the entries under the header");
                assertEquals(after.entries().stream().filter(e -> after.entries().indexOf(e) % 2 == 1).count(),
                        canvas.fills.stream().filter(f -> f[4] == DialogColumns.BAND).count(), width + ": the bands");
            }
        }
    }

    /** The plots: their texts inside the glass; a click finds its node; no line where no series has a value. */
    @Test
    void thePlotsStayOnTheGlassAndAnswerTheirClicks() {
        var t = texts();
        var legend = List.of(new Legend(t.get("column.traffic.organic"), CalculatorColumn.TRAFFIC[0]), new Legend(t.get("column.traffic.vapor"), CalculatorColumn.TRAFFIC[1]),
                new Legend(t.get("column.traffic.water"), CalculatorColumn.TRAFFIC[2]), new Legend(t.get("column.traffic.aqueous"), CalculatorColumn.TRAFFIC[3]));
        for (int width = 143; width <= 555; width += 37)
            for (int height : new int[]{101, 135, 281, 481}) {
                var box = new Rect(11, 77, width, height);
                var canvas = new Recording();
                int head = CalculatorColumn.heading(canvas, box, t.get("column.traffic"), legend, M);
                assertEquals(head, CalculatorColumn.heading(null, box, t.get("column.traffic"), legend, M));
                var plot = CalculatorColumn.plot(box, head);
                int nodes = 64;
                double[][] series = new double[4][nodes];
                for (int s = 0; s < 4; s++) for (int n = 0; n < nodes; n++) series[s][n] = s == 3 && n < 40 ? Double.NaN : 100 + s * 10 + n;
                var profile = new Recording();
                assertTrue(CalculatorColumn.profile(profile, plot, series, CalculatorColumn.TRAFFIC));
                double[] drops = new double[nodes];
                for (int n = 2; n < nodes - 1; n++) drops[n] = 0.4 + n * 0.01;
                var bars = new Recording();
                CalculatorColumn.drops(bars, plot, drops);
                for (var plotted : List.of(profile, bars)) {
                    canvas.places.addAll(plotted.places);
                    // The tray numbers left of the plot, a line apart.
                    var numbers = plotted.places.stream().filter(p -> p.right() <= plot.x()).map(Rect::y).sorted().toList();
                    assertTrue(numbers.size() >= 2, width + " by " + height + ": the first and the last tray numbered");
                    for (int i = 1; i < numbers.size(); i++) assertTrue(numbers.get(i) - numbers.get(i - 1) >= 9, width + " by " + height + ": tray numbers overlap " + numbers);
                }
                for (var place : canvas.places) assertTrue(place.x() >= box.x() && place.right() <= box.right(), width + " by " + height + ": a text off the glass " + place);
                assertEquals(0, CalculatorColumn.node(plot, nodes, plot.y()));
                assertEquals(nodes - 1, CalculatorColumn.node(plot, nodes, plot.bottom()));
                if (plot.h() >= 16) {
                    for (int i = 2; i < nodes - 1; i++) {
                        int py = plot.y() + (i - 2) * plot.h() / (nodes - 3);
                        int found = CalculatorColumn.dropNode(plot, drops, py);
                        assertTrue(found >= 2 && found <= i, width + " by " + height + ": the bar at tray " + (i + 1));
                    }
                }
                assertEquals(-1, CalculatorColumn.dropNode(plot, drops, plot.y() - 1));
                assertFalse(CalculatorColumn.profile(new Recording(), plot, new double[][]{{Double.NaN, Double.NaN}}, new int[]{Palette.ACCENT}));
            }
    }

    /**
     * Moved from the screen's DiagramLayout gates (V3ColumnInputDraftTest): a glass tall enough fits every supported tray
     * count without a scroll; a small one scrolls rather than make the trays too thin to click; every tray answers a
     * click on the shell, every connection one on its label; the marks stay on the glass.
     */
    @Test
    void theDrawingFitsLargeGlassesAndScrollsSmallOnesWithTraysClickable() {
        for (int trays = 0; trays <= 64; trays++)
            for (int h : new int[]{28 + 65 * 3, 400, 900}) {
                assertTrue(CalculatorColumn.drawingHeight(trays, h) <= h, "trays=" + trays + " in " + h);
                assertTrue(CalculatorColumn.pitch(trays, h) >= 3);
            }
        assertTrue(CalculatorColumn.drawingHeight(64, 101) > 101);
        assertEquals(3, CalculatorColumn.pitch(64, 101));
        assertThrows(IllegalArgumentException.class, () -> CalculatorColumn.pitch(65, 400));

        var t = texts();
        var canvas = new Recording();
        for (int width : widths())
            for (int height : HEIGHTS) {
                var box = PanelSkin.LIGHT.well(canvas, CalculatorColumn.glass(width, height, true));
                for (int trays : new int[]{0, 8, 28, 62})
                    for (var selected : Connection.values())
                        for (int offset : new int[]{0, 40}) {
                            String at = width + " by " + height + ", " + trays + " trays, " + selected + " at " + offset;
                            var in = drawingInput(trays, selected, 1);
                            var d = CalculatorColumn.drawing(in, box, offset, M, t, node -> "tray " + node);
                            assertEquals(CalculatorColumn.drawingHeight(trays, box.h()), d.height(), at);
                            assertEquals(box.y() - offset, d.top(), at);
                            var content = new Rect(box.x(), d.top(), box.w(), d.height());
                            for (var hit : d.hits()) assertTrue(hit.r().inside(content), at + ": a mark off the glass " + hit);
                            for (int node = 0; node < trays + 2; node++) {
                                var hit = d.at(d.axis(), d.y(node));
                                if (!box.contains(d.axis(), d.y(node))) continue;
                                assertNotNull(hit, at + ": tray " + node);
                                assertEquals("tray:" + node, hit.action(), at);
                                assertEquals(hit, d.at(d.axis() - 20, d.y(node)), at + ": the tray's width answers");
                            }
                            var draw = new Recording();
                            CalculatorColumn.draw(draw, d, t);
                            for (var place : draw.places) assertTrue(place.x() >= box.x() && place.right() <= box.right(), at + ": a label off the glass " + place);
                            if (trays >= 26 && offset == 0 && d.pitch() >= 9) {
                                for (var action : List.of("feed", "draw:0", "draw:1", "draw:2", "pa:0", "pa:1", "pa:2", "pa:3", "steam:0", "steam:1")) {
                                    var mark = d.hits().stream().filter(h -> h.action().equals(action)).findFirst().orElseThrow();
                                    var r = mark.r();
                                    if (!r.inside(box)) continue;
                                    assertEquals(action, d.at(r.x() + r.w() / 2.0, r.y() + r.h() / 2.0).action(), at + ": a click on " + action);
                                }
                            }
                        }
            }
    }

    /** The template's name form: the name's box under the library and the label, the hint under it, inside the glass. */
    @Test
    void theSaveFormStandsInsideTheGlass() {
        var t = texts();
        for (int width = 143; width <= 555; width += 23) {
            var box = new Rect(11, 77, width, 200);
            var form = CalculatorColumn.saveForm(t.get("column.column_library"), t.get("column.preset_filename"), t.get("column.preset_filename_hint"), box, M);
            assertTrue(form.name().inside(box) && form.name().w() <= 230 && form.name().w() >= 40, width + ": the name's box " + form.name());
            assertTrue(form.name().y() >= box.y() + form.head().lines().size() * CalculatorColumn.PITCH, width + ": the box under its label");
            assertTrue(form.hintY() > form.name().bottom(), width + ": the hint under the box");
            for (var line : form.hint().lines()) assertTrue(M.width(line.cells().getFirst()) <= width - 6, width + ": the hint cut");
        }
        assertEquals(Set.of(), t.missing);
    }

}
