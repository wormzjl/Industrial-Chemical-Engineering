package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Canvas;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.PanelSkin;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.science.assembly.AssemblyReport;
import com.wormzjl.icheme.science.assembly.AssemblyReport.State;
import com.wormzjl.icheme.science.assembly.AssemblyView;
import com.wormzjl.icheme.science.assembly.Blueprint.Slot;
import com.wormzjl.icheme.science.assembly.ColumnBlueprint;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnComponents.Condenser;
import com.wormzjl.icheme.science.equipment.ColumnComponents.Pump;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.ColumnTrays.Pumparound;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The assembly screen's panel door (batch 2026-10-28-gui-toolkit, GUI_REVIEW.md order 4): every text it names has its
 * English line; the door column, the glass, the tabs and the footer fit at every width the screen takes (320 to 760 px)
 * and every height, with no text cut, plates in one line, widgets at least 40 px and boxes in line; each state shows its
 * keys; no widget moves while the engine's answers come; the parts list cuts no name and bands every second part; the
 * drawing answers its own parts and ports.
 */
class AssemblyColumnTest {
    private static final Measure M = Measure.fixed(6);
    private static final int[] HEIGHTS = {220, 254, 400, 600};

    /** The smallest column design and the largest (ejector, kettle, four draws, two steams, four pumparounds). */
    private static List<ColumnTrays> designs() {
        return List.of(new ColumnTrays(20, 10, List.of(), List.of(21), List.of(), new ColumnComponents(Condenser.AIR_COOLER, Pump.IN_LINE, List.of(), false, false)),
                new ColumnTrays(20, 10, List.of(3, 6, 9, 12), List.of(18, 21), List.of(new Pumparound(4, 2), new Pumparound(8, 6), new Pumparound(12, 10), new Pumparound(16, 14)),
                        new ColumnComponents(Condenser.EXCHANGER, Pump.STANDARD, List.of(Pump.STANDARD, Pump.IN_LINE, Pump.STANDARD, Pump.IN_LINE), true, true)));
    }

    /** A report on {@code trays}' slots: one slot in every state by turns, the units members at made-up places. */
    private static AssemblyReport report(ColumnTrays trays, boolean complete) {
        var states = State.values();
        var slots = new ArrayList<AssemblyReport.SlotReport>();
        var members = new ArrayList<Long>();
        var all = ColumnBlueprint.of(trays).slots();
        for (int i = 0; i < all.size(); i++) {
            long member = 1000L + i;
            members.add(member);
            slots.add(new AssemblyReport.SlotReport(all.get(i), complete ? State.OK : states[i % states.length], member, AssemblyReport.NONE, ""));
        }
        return new AssemblyReport(slots, List.of(), Set.of(), members);
    }

    /** The door's states: nothing selected before a report; selected with parts to fix; a check pending; all in place; the widest numbers. */
    private static List<AssemblyColumn.Input> inputs(@Nullable String selected) {
        return List.of(new AssemblyColumn.Input(12, -1, false, selected, false), new AssemblyColumn.Input(29, 13, false, selected, selected != null),
                new AssemblyColumn.Input(29, 13, true, selected, selected != null), new AssemblyColumn.Input(29, 0, false, selected, selected != null),
                new AssemblyColumn.Input(29, 29, false, selected, false), new AssemblyColumn.Input(9, 1, true, selected, false));
    }

    private static final List<String> NAMES = Arrays.asList(null, "Reflux pump", "Sour water valve", "PA 4 exchanger", "Flowmeter on the shell bypass valve");

    @Test
    void everyTextTheScreenNamesHasItsEnglishLine() {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        for (var name : NAMES) for (var in : inputs(name)) AssemblyColumn.rows(in, t);
        for (boolean operation : new boolean[]{false, true}) for (boolean complete : new boolean[]{false, true}) AssemblyColumn.tabs(operation, complete, t);
        AssemblyColumn.footerButtons(t);
        AssemblyColumn.words(t);
        for (var trays : designs()) {
            var diagram = AssemblyDiagram.of(trays);
            var slots = ColumnBlueprint.of(trays).slots();
            var report = report(trays, false);
            AssemblyColumn.parts(slots, diagram, null, t);
            AssemblyColumn.parts(slots, diagram, report, t);
            AssemblyColumn.status(new AssemblyView(AssemblyView.Status.CHECKED, report, AssemblyReport.NONE), at -> "1, 2, 3", t);
            AssemblyColumn.status(new AssemblyView(AssemblyView.Status.COMPLETE, report(trays, true), AssemblyReport.NONE), at -> "1, 2, 3", t);
        }
        AssemblyColumn.status(AssemblyView.NO_DESIGN, at -> "1, 2, 3", t);
        AssemblyColumn.status(AssemblyView.NOT_VALIDATED, at -> "1, 2, 3", t);
        AssemblyColumn.status(new AssemblyView(AssemblyView.Status.NOT_VALIDATED, null, 42L), at -> "1, 2, 3", t);
        for (var key : List.of("assembly.title", "assembly.no_design", "assembly.operation", "fluid.theme.light", "fluid.theme.dark", "fluid.theme.hint")) t.get(key);
        assertEquals(Set.of(), t.missing);
        assertTrue(t.asked.size() > 40, "the cases ask for the screen's texts: " + t.asked.size());
    }

    /** A plate in one line, no text cut, widgets at least 40 px inside their rows, one per key, boxes in line; the summary in one line. */
    private static void assertFits(Column column, String at) {
        var bounds = column.bounds();
        for (var p : column.rows()) {
            assertTrue(p.rect().x() >= bounds.x() && p.rect().right() <= bounds.right(), at);
            for (var text : p.texts()) assertTrue(M.width(text) <= p.rect().w(), at + ": a text cut: " + text);
            for (var s : p.slots().values()) assertTrue(s.inside(p.rect()) && s.w() >= 40, at + ": a widget " + s);
            if (p.row() instanceof Column.Group g) assertEquals(1, p.texts().size(), at + ": a plate in one line: " + g.title());
        }
        column.slots();
        DeviceColumnTest.assertInLine(column, at);
        // The summary (the Check group's line) stands in one line, so the Selected group's widgets never move with it.
        var summary = column.rows().stream().filter(p -> p.row() instanceof Column.Line).findFirst().orElseThrow();
        assertEquals(1, summary.texts().size(), at + ": the summary in one line: " + summary.texts());
    }

    @Test
    void theDoorFitsAtEveryWidthTheScreenTakes() {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        var statuses = List.of(t.get("assembly.status.checked", 29, 29, 29), t.get("assembly.status.changed"), t.get("assembly.status.not_validated"),
                t.get("assembly.status.no_design"), t.get("assembly.status.complete"));
        for (int width = 320; width <= 760; width += 7)
            for (int height : HEIGHTS) {
                String at = width + " by " + height;
                var area = AssemblyColumn.area(width, height);
                var glass = AssemblyColumn.glass(width, height, true);
                assertEquals(width - 10, area.right(), at + ": the column 10 px in from the door's edge");
                assertTrue(glass.x() == 5 && glass.right() + 6 == area.x() && glass.y() == area.y() - 3 && glass.bottom() == area.bottom(), at + ": the glass beside the column");
                assertEquals(width - 5, AssemblyColumn.glass(width, height, false).right(), at + ": the glass across the door without a column");
                assertTrue(area.bottom() <= height - AssemblyColumn.DIVIDER - 2, at + ": the column above the footer's divider");
                for (var name : NAMES)
                    for (var in : inputs(name))
                        for (boolean bar : new boolean[]{false, true}) {
                            int w = bar ? area.w() - ScrollView.BAR - 4 : area.w();
                            var column = Column.place(AssemblyColumn.rows(in, t), area.x(), area.y(), w, M, Column.Metrics.COMPACT);
                            assertFits(column, at + (bar ? " narrowed" : "") + " " + in);
                            // A one-line name keeps the column in its area at the lowest door: no bar.
                            if (!bar && (name == null || M.width(name) <= w - 8)) assertTrue(column.bounds().h() <= area.h(), at + ": the column fits its area " + in);
                        }
                var display = AssemblyColumn.display(width);
                for (var s : statuses) assertTrue(M.width(s) <= display.w(), at + ": the status cut: " + s);
                assertTrue(M.width(t.get("assembly.title")) <= AssemblyColumn.title(width) && AssemblyColumn.theme(width).right() == width - 10, at + ": the title row");
                var tabs = AssemblyColumn.tabs(false, false, t);
                var tabSlots = AssemblyColumn.tabSlots(width);
                for (int i = 0; i < tabs.size(); i++) {
                    var r = tabSlots.get(i);
                    assertTrue(r.x() >= 10 && r.right() <= width - 10 && M.width(tabs.get(i).text()) <= r.w() - 6, at + ": a tab " + r);
                }
                assertFalse(tabSlots.get(0).overlaps(tabSlots.get(1)), at + ": the tabs apart");
                var footer = AssemblyColumn.footer(width, height);
                var buttons = AssemblyColumn.footerButtons(t);
                for (int i = 0; i < buttons.size(); i++) {
                    var r = footer.get(i);
                    assertTrue(r.x() >= 10 && r.right() <= width - 10 && r.y() > height - AssemblyColumn.DIVIDER && M.width(buttons.get(i).text()) <= r.w() - 6, at + ": a footer key " + r);
                }
            }
    }

    private static Map<String, Column.Button> buttons(List<Column.Row> rows) {
        var out = new LinkedHashMap<String, Column.Button>();
        for (var b : ConstructorColumn.buttons(rows)) out.put(b.key(), b);
        return out;
    }

    /** The Check group's summary line and the Selected group's name line. */
    private static List<String> lines(List<Column.Row> rows) {
        var out = new ArrayList<String>();
        for (var row : rows) if (row instanceof Column.Group g) for (var r : g.rows()) if (r instanceof Column.Line l) out.add(l.text());
        return out;
    }

    @Test
    void eachStateShowsItsKeys() {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        // Nothing selected, no report yet: Check parts acts, the parts to check; no part, Open unit off.
        var fresh = AssemblyColumn.rows(new AssemblyColumn.Input(12, -1, false, null, false), t);
        assertEquals(List.of("Check", "Selected"), fresh.stream().map(r -> ((Column.Group) r).title()).toList());
        assertEquals(List.of("check", "open"), List.copyOf(buttons(fresh).keySet()));
        assertTrue(buttons(fresh).get("check").active() && !buttons(fresh).get("open").active());
        assertEquals(List.of("12 parts to check", "None"), lines(fresh));
        // A unit selected after an answer: Open unit acts; the parts to fix.
        var answered = AssemblyColumn.rows(new AssemblyColumn.Input(12, 3, false, "Reflux pump", true), t);
        assertTrue(buttons(answered).get("check").active() && buttons(answered).get("open").active());
        assertEquals(List.of("12 parts, 3 to fix", "Reflux pump"), lines(answered));
        // A valve selected: it has no screen of its own.
        assertFalse(buttons(AssemblyColumn.rows(new AssemblyColumn.Input(12, 3, false, "Feed valve", false), t)).get("open").active());
        // A check pending: Check parts waits; the summary says so.
        var pending = AssemblyColumn.rows(new AssemblyColumn.Input(12, 3, true, "Reflux pump", true), t);
        assertFalse(buttons(pending).get("check").active());
        assertEquals("Check queued", lines(pending).getFirst());
        // Every part in place: nothing to check again.
        var done = AssemblyColumn.rows(new AssemblyColumn.Input(12, 0, false, null, false), t);
        assertFalse(buttons(done).get("check").active());
        assertEquals("12 parts in place", lines(done).getFirst());
        // The parts to fix: the report's misplaced and missing ones; none known before a report.
        var some = report(designs().getFirst(), false);
        assertEquals(some.misplaced() + some.missing(), AssemblyColumn.input(12, some, false, null, false).fix());
        assertEquals(-1, AssemblyColumn.input(12, null, false, null, false).fix());
        assertEquals(0, AssemblyColumn.input(12, report(designs().getFirst(), true), false, null, false).fix());

        // The tabs: the page shown latched; Operation off until the structure is complete.
        var building = AssemblyColumn.tabs(false, false, t);
        assertTrue(building.get(0).latched() && !building.get(0).active() && !building.get(1).latched() && !building.get(1).active());
        var complete = AssemblyColumn.tabs(false, true, t);
        assertTrue(complete.get(0).latched() && complete.get(1).active());
        var operation = AssemblyColumn.tabs(true, true, t);
        assertTrue(operation.get(0).active() && !operation.get(0).latched() && operation.get(1).latched() && !operation.get(1).active());
        assertEquals(List.of("close", "datasheet"), AssemblyColumn.footerButtons(t).stream().map(Column.Button::key).toList());

        // The status, once: no coordinates on the door; where the structure changed on its hover.
        var changed = AssemblyColumn.status(new AssemblyView(AssemblyView.Status.NOT_VALIDATED, null, 42L), at -> "10, 64, -3", t);
        assertFalse(changed.text().contains("10, 64"), changed.text());
        assertTrue(changed.hint() != null && changed.hint().contains("10, 64, -3"), String.valueOf(changed.hint()));
        assertNull(AssemblyColumn.status(AssemblyView.NOT_VALIDATED, at -> "?", t).hint());
        var report = report(designs().getLast(), false);
        var checked = AssemblyColumn.status(new AssemblyView(AssemblyView.Status.CHECKED, report, AssemblyReport.NONE), at -> "?", t);
        assertEquals(Palette.WARN, checked.palette());
        assertTrue(checked.text().contains(report.connected() + " connected"), checked.text());
        assertEquals(Palette.ACCENT, AssemblyColumn.status(new AssemblyView(AssemblyView.Status.COMPLETE, report(designs().getLast(), true), AssemblyReport.NONE), at -> "?", t).palette());
    }

    private static Map<String, Rect> widgets(Column column) {
        var out = new LinkedHashMap<String, Rect>();
        for (var p : column.rows()) if (p.row() instanceof Column.Buttons) out.putAll(p.slots());
        return out;
    }

    /** The screen places its widgets at a rebuild and its texts each frame: the engine's answers (the counts, the check queued, all in place) move no widget. */
    @Test
    void aWidgetKeepsItsSlotWhileTheEngineAnswers() {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        for (int width : new int[]{320, 464, 600, 760})
            for (var name : NAMES) {
                var area = AssemblyColumn.area(width, 254);
                Map<String, Rect> first = null;
                for (var in : inputs(name)) {
                    var slots = widgets(Column.place(AssemblyColumn.rows(in, t), area.x(), area.y(), area.w(), M, Column.Metrics.COMPACT));
                    if (first == null) first = slots;
                    else assertEquals(first, slots, width + " " + name + " " + in);
                }
            }
    }

    private static List<AssemblyColumn.Part> parts(AssemblyReport report) {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        var trays = designs().getLast();
        var out = new ArrayList<>(AssemblyColumn.parts(ColumnBlueprint.of(trays).slots(), AssemblyDiagram.of(trays), report, t));
        // A heat exchanger's line part's long name, as a part list may hold one.
        out.add(new AssemblyColumn.Part("Flowmeter on the shell bypass valve", "Missing", Palette.RED));
        return out;
    }

    /** No name is cut: a name wraps in its column with its word on its first line, or the list stacks; every line inside the list; a click finds its part. */
    @Test
    void thePartsListCutsNoName() {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        var words = AssemblyColumn.words(t);
        var parts = parts(report(designs().getLast(), false));
        for (int width = 90; width <= AssemblyColumn.LIST_MAX; width += 5) {
            var list = AssemblyColumn.parts(parts, words, width, M);
            int column = list.stacked() ? width - 3 - AssemblyColumn.MARK - 3 : width - 3 - AssemblyColumn.MARK - 6 - list.words() - 3;
            int entry = -1;
            for (int i = 0; i < list.lines().size(); i++) {
                var line = list.lines().get(i);
                assertTrue(M.width(line.name()) <= column, width + ": a name cut: " + line.name());
                assertTrue(3 + AssemblyColumn.MARK + M.width(line.name()) + (line.word().isEmpty() ? 0 : 6 + M.width(line.word())) + 3 <= width, width + ": a line wider than the list");
                if (line.first()) {
                    assertEquals(entry + 1, line.entry(), width + ": the parts in order, their lines together");
                    entry = line.entry();
                    assertEquals(!list.stacked(), !line.word().isEmpty(), width + ": the word on the name's first line unless stacked");
                } else assertEquals(entry, line.entry(), width + ": a wrapped line follows its part");
                for (var word : line.name().split(" ")) assertTrue(word.isEmpty() || Arrays.asList(parts.get(line.entry()).name().split(" ")).contains(word), width + ": a split word " + word);
                var box = new Rect(0, 100, width, 10_000);
                assertEquals(line.entry(), AssemblyColumn.entry(list, box, 0, 100 + 2 + i * DialogColumns.PITCH + 4), width + ": a click on line " + i);
            }
            assertEquals(parts.size() - 1, entry, width + ": every part listed");
            assertEquals(list.lines().size() * DialogColumns.PITCH + 4, list.height());
        }
        // The list stacks only where a word of a name is wider than the name's column.
        assertFalse(AssemblyColumn.parts(parts.subList(0, 3), words, 200, M).stacked());
        assertTrue(AssemblyColumn.parts(parts, words, 100, M).stacked());
    }

    /** A canvas that records its fills. */
    private static final class Recording implements Canvas {
        final List<int[]> fills = new ArrayList<>();
        final List<String> texts = new ArrayList<>();

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
        }

        @Override
        public void text(String text, float x, float y, float scale, int argb) {
            texts.add(text);
        }

        @Override
        public void clip(Rect area) {
        }

        @Override
        public void unclip() {
        }
    }

    /** Owner ("B: bands"): every second part's lines lie on a band, the selected part's on the line colour; one mark a part, in its colour. */
    @Test
    void everySecondPartLiesOnABand() {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        var parts = parts(report(designs().getLast(), false));
        for (int width : new int[]{100, 150, 250}) {
            var list = AssemblyColumn.parts(parts, AssemblyColumn.words(t), width, M);
            var canvas = new Recording();
            AssemblyColumn.draw(canvas, list, new Rect(0, 0, width, 10_000), 0, 2);
            long bands = canvas.fills.stream().filter(f -> f[4] == DialogColumns.BAND).count(), selected = canvas.fills.stream().filter(f -> f[4] == Palette.LINE).count();
            assertEquals(list.lines().stream().filter(l -> l.entry() % 2 == 1 && l.entry() != 2).count(), bands, width + ": the bands");
            assertEquals(list.lines().stream().filter(l -> l.entry() == 2).count(), selected, width + ": the selected part");
            assertEquals(parts.size(), canvas.fills.stream().filter(f -> f[2] - f[0] == 6 && f[3] - f[1] == 6).count(), width + ": one mark a part");
            assertTrue(canvas.texts.containsAll(AssemblyColumn.parts(parts, AssemblyColumn.words(t), width, M).lines().stream().map(AssemblyColumn.PartLine::name).filter(n -> !n.isEmpty()).toList()));
        }
        for (var p : parts(null)) assertEquals(Palette.RED, p.colour(), "every part red before the first report");
    }

    /** The glass: the drawing and the list inside it, apart; the list at least its narrowest where the glass allows, at most its widest; the drawing fitted inside its part. */
    @Test
    void theGlassSplitsBetweenTheDrawingAndTheList() {
        var canvas = new Recording();
        for (int width = 320; width <= 760; width += 7)
            for (int height : HEIGHTS) {
                var box = PanelSkin.LIGHT.well(canvas, AssemblyColumn.glass(width, height, true));
                var split = AssemblyColumn.split(box);
                String at = width + " by " + height;
                assertTrue(split.drawing().inside(box) && split.list().inside(box) && !split.drawing().overlaps(split.list()), at);
                assertTrue(split.list().w() >= AssemblyColumn.LIST_MIN && split.list().w() <= AssemblyColumn.LIST_MAX, at + ": the list " + split.list());
                assertEquals(box.right(), split.list().right(), at);
                var sheet = new AssemblyFlowsheet(AssemblyDiagram.of(designs().getLast()));
                sheet.fit(split.drawing());
                int[] origin = sheet.onCanvas(0, 0), end = sheet.onCanvas(AssemblyDiagram.WIDTH, AssemblyDiagram.HEIGHT);
                assertTrue(origin[0] >= split.drawing().x() && origin[1] >= split.drawing().y() && end[0] <= split.drawing().right() + 1 && end[1] <= split.drawing().bottom() + 1,
                        at + ": the drawing inside its part");
            }
    }

    /** A click or a hover on a part's middle finds that part, on a port's square that port, at the sizes the screen draws; the drawing draws every state. */
    @Test
    void theDrawingAnswersItsOwnPartsAndPorts() {
        for (var trays : designs())
            for (var box : List.of(new Rect(11, 77, 125, 132), new Rect(11, 77, 267, 278))) {
                var sheet = new AssemblyFlowsheet(AssemblyDiagram.of(trays));
                sheet.fit(box);
                for (var part : sheet.diagram.parts()) {
                    int[] c = sheet.onCanvas(part.x() + part.w() / 2, part.y() + part.h() / 2);
                    var found = sheet.part(c[0], c[1]);
                    assertNotNull(found, part::toString);
                    assertEquals(part.slot(), found.slot(), () -> part + " at " + box);
                }
                for (var port : sheet.diagram.ports()) {
                    int[] c = sheet.onCanvas(port.x(), port.y());
                    var found = sheet.port(c[0], c[1]);
                    assertNotNull(found, port::toString);
                    assertEquals(List.of(port.x(), port.y()), List.of(found.x(), found.y()), port::toString);
                }
                for (var pill : sheet.diagram.pills()) {
                    int[] c = sheet.onCanvas(pill.x() + pill.w() / 2, pill.y());
                    assertEquals(pill, sheet.pill(c[0], c[1]));
                }
                var t = new HeatControlColumnTest.Recording("gui.icheme.");
                for (var report : Arrays.asList(null, report(trays, false), report(trays, true))) {
                    var canvas = new Recording();
                    Slot selected = ColumnBlueprint.REFLUX_PUMP;
                    sheet.draw(canvas, report, selected, text -> AssemblyColumn.text(text, t));
                    assertFalse(canvas.fills.isEmpty());
                }
                assertEquals(Set.of(), t.missing);
            }
    }
}
