package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.science.column.operation.ColumnCycle;
import com.wormzjl.icheme.science.column.operation.DriftBands;
import com.wormzjl.icheme.science.column.operation.OperationView;
import com.wormzjl.icheme.science.column.operation.OperationView.Duty;
import com.wormzjl.icheme.science.column.operation.OperationView.DutyKind;
import com.wormzjl.icheme.science.column.operation.OperationView.Phase;
import com.wormzjl.icheme.science.column.operation.OperationView.Stream;
import com.wormzjl.icheme.science.column.operation.Port;
import com.wormzjl.icheme.science.column.operation.Setpoints;
import com.wormzjl.icheme.science.display.DisplayUnit.FlowRateUnit;
import com.wormzjl.icheme.science.display.DisplayUnit.PressureUnit;
import com.wormzjl.icheme.science.display.DisplayUnit.TemperatureUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The column's Operation tab (batch 2026-10-28-column-operation, C7; owner's choice B): every text it names has its English
 * line; the door column and the glass's tables fit at every width the page takes, with no text cut, plates in one line,
 * widgets at least 40 px and boxes in line; at its design size the usual column needs no bar; the switch and Apply follow
 * the column and a command awaiting its answer; Apply sends the set points shown, in SI, whatever the client's units.
 */
public class OperationColumnTest {
    private static final Measure M = Measure.fixed(6);
    /** The Operation page's image heights: the 1366 by 768 window's at scale 2, its design size's, the 1920 by 1080 window's at the minimum scale. */
    private static final int[] HEIGHTS = {344, 368, 524};
    private static final List<OperationColumn.Units> UNITS = List.of(new OperationColumn.Units(TemperatureUnit.CELSIUS, PressureUnit.BAR, FlowRateUnit.PER_HOUR),
            new OperationColumn.Units(TemperatureUnit.KELVIN, PressureUnit.PASCAL, FlowRateUnit.PER_MINUTE),
            new OperationColumn.Units(TemperatureUnit.CELSIUS, PressureUnit.KILOPASCAL, FlowRateUnit.PER_SECOND));

    /** A design's set points: {@code steam} inlets, {@code draws}, {@code pumparounds}, a reboiler where {@code reboiler}. */
    public static Setpoints setpoints(int steam, int draws, int pumparounds, boolean reboiler) {
        double[] s = new double[steam], d = new double[draws], p = new double[pumparounds];
        java.util.Arrays.fill(s, .05);
        java.util.Arrays.fill(d, .155);
        java.util.Arrays.fill(p, -63000);
        return new Setpoints(33.3, 28.7, 318.15, 2.5, reboiler ? 41000 : 0, s, d, p);
    }

    /** A running column on {@code s}: every port, every duty, limited by its condenser, a solve in flight. */
    public static OperationView running(Setpoints s, Phase phase) {
        var streams = new ArrayList<Stream>();
        streams.add(new Stream(Port.FEED, 0, 28.7, 613.15, 240000, .35, .62));
        for (int j = 0; j < s.steamPerFeed().length; j++) streams.add(new Stream(Port.STEAM, j, 1.4, 673.15, 350000, .3, .41));
        streams.add(new Stream(Port.OFF_GAS, 0, .5, 318.15, 130000, .48, .22));
        streams.add(new Stream(Port.SOUR_WATER, 0, 1.5, 318.15, 130000, .4, Double.NaN));
        streams.add(new Stream(Port.DISTILLATE, 0, 7.5, 318.15, 130000, .51, .57));
        for (int j = 0; j < s.drawsPerFeed().length; j++) streams.add(new Stream(Port.DRAW, j, 4.4, 463.15, 162000, .55, .46));
        streams.add(new Stream(Port.BOTTOMS, 0, 10.8, 604.15, 195000, 1.2, 1));
        var duties = new ArrayList<Duty>();
        duties.add(new Duty(DutyKind.CONDENSER, 0, -7.32e6, -8.51e6));
        if (s.reboilerPerFeed() != 0) duties.add(new Duty(DutyKind.REBOILER, 0, 1.2e6, 1.4e6));
        for (int j = 0; j < s.pumparoundsPerFeed().length; j++) duties.add(new Duty(DutyKind.PUMPAROUND, j, -1.81e6, -2.1e6));
        return new OperationView(phase, ColumnCycle.Limit.CONDENSER, .86, true, DriftBands.Trigger.COMPOSITION, true, "LIMITED: condenser, 86 %", true, true, s, streams, duties,
                new OperationView.Solve(DriftBands.Trigger.ENTHALPY, true, 4321));
    }

    /** The designs drawn: the smallest, the usual crude column, the largest the calculator builds. */
    static List<Setpoints> designs() {
        return List.of(setpoints(0, 0, 0, true), setpoints(1, 2, 2, false), setpoints(2, 4, 4, true));
    }

    /** Every case: each design running, stopped, never run (a reader), each phase, a command pending. */
    static List<OperationColumn.Input> inputs() {
        var out = new ArrayList<OperationColumn.Input>();
        for (var s : designs())
            for (var u : UNITS) {
                for (var phase : Phase.values()) out.add(new OperationColumn.Input(running(s, phase), false, u));
                out.add(new OperationColumn.Input(running(s, Phase.RUNNING), true, u));
                out.add(new OperationColumn.Input(OperationView.idle(Phase.STOPPED, "STOPPED", s), false, u));
                out.add(new OperationColumn.Input(OperationView.idle(Phase.NOT_READY, "a vacuum column cannot run yet", s), false, u));
            }
        return out;
    }

    @Test
    void everyTextTheTabNamesHasItsEnglishLine() {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        for (var in : inputs()) {
            OperationColumn.rows(in, t);
            OperationColumn.status(in.view(), t);
            OperationColumn.ports(in.view(), in.units(), t);
            OperationColumn.duties(in.view(), t);
            OperationColumn.solve(in.view(), t);
        }
        for (var limit : ColumnCycle.Limit.values())
            OperationColumn.status(new OperationView(Phase.LIMITED, limit, .5, false, null, false, "", true, true, setpoints(1, 1, 1, false), List.of(), List.of(), null), t);
        for (var trigger : DriftBands.Trigger.values()) OperationColumn.trigger(trigger, t);
        OperationColumn.solve(new OperationView(Phase.RUNNING, ColumnCycle.Limit.NONE, 1, false, null, false, "", true, true, setpoints(1, 1, 1, false), List.of(), List.of(),
                new OperationView.Solve(null, false, 30)), t);
        OperationColumn.footerButtons(true, t);
        t.get("assembly.operation");
        assertEquals(Set.of(), t.missing);
        assertTrue(t.asked.size() > 60, "the cases ask for the tab's texts: " + t.asked.size());
    }

    /** A plate in one line, no text cut, widgets at least 40 px inside their rows, one per key, boxes and units in line. */
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
    }

    @Test
    void theDoorAndTheGlassFitAtEveryWidthThePageTakes() {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        // The page's image is at least the design width less 16 px: its scale always holds 464 GUI px across.
        for (int width = 448; width <= 760; width += 7)
            for (int height : HEIGHTS) {
                String at = width + " by " + height;
                var area = AssemblyColumn.area(width, height);
                var glass = AssemblyColumn.glass(width, height, true);
                int inside = glass.w() - 4;
                for (var in : inputs())
                    for (boolean bar : new boolean[]{false, true}) {
                        int w = bar ? area.w() - ScrollView.BAR - 4 : area.w();
                        assertFits(Column.place(OperationColumn.rows(in, t), area.x(), area.y(), w, M, Column.Metrics.COMPACT), at + (bar ? " narrowed " : " ") + in.view().phase());
                    }
                for (var in : inputs())
                    for (var table : List.of(OperationColumn.ports(in.view(), in.units(), t), OperationColumn.duties(in.view(), t), OperationColumn.solve(in.view(), t))) {
                        for (int room : new int[]{inside, inside - ScrollView.BAR - 2}) {
                            int[] widths = OperationColumn.widths(table, room, M);
                            assertEquals(room, java.util.Arrays.stream(widths).sum(), at + ": the table across the glass");
                            for (var row : table) for (int i = 0; i < row.length; i++) assertTrue(M.width(row[i]) + OperationColumn.PAD <= widths[i], at + ": a cell cut: " + row[i]);
                        }
                    }
                var status = AssemblyColumn.display(width);
                for (var in : inputs()) assertTrue(M.width(OperationColumn.status(in.view(), t).text()) <= status.w(), at + ": the status cut: " + OperationColumn.status(in.view(), t).text());
                var footer = AssemblyColumn.footer(width, height);
                var keys = OperationColumn.footerButtons(true, t);
                for (int i = 0; i < keys.size(); i++) assertTrue(M.width(keys.get(i).text()) <= footer.get(i).w() - 6, at + ": a footer key");
            }
        // At 1920 by 1080 and 2560 by 1440 (scale 3 and 4: 640 by 360 GUI px, the image 624 by 344) the usual column's door and tables fit: no bar.
        var usual = new OperationColumn.Input(running(designs().get(1), Phase.LIMITED), false, UNITS.getFirst());
        var area = AssemblyColumn.area(624, 344);
        int tall = Column.place(OperationColumn.rows(usual, t), area.x(), area.y(), area.w(), M, Column.Metrics.COMPACT).bounds().h();
        assertTrue(tall <= area.h(), "the usual door in one view: " + tall + " in " + area.h());
        assertTrue(OperationColumn.height(usual.view()) <= AssemblyColumn.glass(624, 344, true).h() - 4, "the usual tables on the glass: " + OperationColumn.height(usual.view()));
    }

    private static Map<String, Column.Button> buttons(List<Column.Row> rows) {
        var out = new LinkedHashMap<String, Column.Button>();
        for (var b : ConstructorColumn.buttons(rows)) out.put(b.key(), b);
        return out;
    }

    @Test
    void theSwitchAndApplyFollowTheColumn() {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        var u = UNITS.getFirst();
        var s = designs().get(1);
        // Running: On latched, Off acts; the set points are entries.
        var run = OperationColumn.rows(new OperationColumn.Input(running(s, Phase.RUNNING), false, u), t);
        assertTrue(buttons(run).get("start").latched() && !buttons(run).get("start").active() && buttons(run).get("stop").active());
        var entries = ((Column.Group) run.get(1)).rows();
        assertTrue(entries.stream().allMatch(r -> r instanceof Column.Field), "entries where the column takes set points");
        assertEquals(List.of("feed", "condenser", "reflux", "steam:0", "draw:0", "draw:1", "pumparound:0", "pumparound:1"),
                entries.stream().map(r -> ((Column.Field) r).key()).toList(), "no reboiler entry where the design has none");
        // A command awaiting its answer: nothing acts.
        var pending = OperationColumn.rows(new OperationColumn.Input(running(s, Phase.RUNNING), true, u), t);
        assertFalse(buttons(pending).get("start").active() || buttons(pending).get("stop").active());
        assertFalse(OperationColumn.applies(new OperationColumn.Input(running(s, Phase.RUNNING), true, u), Map.of()));
        // Never run: Off latched, On acts; the set points are the design's meters, and Apply is off.
        var idle = new OperationColumn.Input(OperationView.idle(Phase.STOPPED, "STOPPED", s), false, u);
        var rows = OperationColumn.rows(idle, t);
        assertTrue(buttons(rows).get("stop").latched() && buttons(rows).get("start").active());
        assertTrue(((Column.Group) rows.get(1)).rows().stream().allMatch(r -> r instanceof Column.Reading));
        assertFalse(OperationColumn.applies(idle, Map.of()));
        // Apply acts while every entry reads as a number.
        var editor = new OperationColumn.Input(running(s, Phase.RUNNING), false, u);
        assertTrue(OperationColumn.applies(editor, Map.of()));
        assertFalse(OperationColumn.applies(editor, Map.of("reflux", "-")));
        // The status once: the limit and its share, and the failure before rule A and the solve in flight.
        assertEquals("Limited: condenser, 86 % of the feed; last solve failed",
                OperationColumn.status(running(s, Phase.LIMITED), t).text());
        assertEquals("LIMITED: condenser, 86 %", OperationColumn.status(running(s, Phase.LIMITED), t).hint(), "the engine's line in the hover");
    }

    @Test
    void applySendsTheSetPointsShownInSi() {
        for (var s : designs())
            for (var u : UNITS) {
                var values = OperationColumn.parse(s, Map.of(), u);
                assertNotNull(values);
                double[] expected = new double[4 + s.steamPerFeed().length + s.drawsPerFeed().length + s.pumparoundsPerFeed().length];
                expected[0] = s.feedRate();
                expected[1] = s.condenserTemperature();
                expected[2] = s.refluxRatio();
                expected[3] = s.reboilerPerFeed();
                int k = 4;
                for (double v : s.steamPerFeed()) expected[k++] = v;
                for (double v : s.drawsPerFeed()) expected[k++] = v;
                for (double v : s.pumparoundsPerFeed()) expected[k++] = v;
                assertEquals(expected.length, values.size());
                for (int i = 0; i < expected.length; i++)
                    assertEquals(expected[i], values.get(i), 0, u + ": value " + i + " of " + values);
                // A typed feed in the client's units arrives in mol/s; an untouched text sends the set point exactly.
                var typed = new HashMap<String, String>();
                typed.put("feed", "1");
                assertEquals(u.time().perSecond(1000), OperationColumn.parse(s, typed, u).getFirst(), 1e-12);
            }
        assertNull(OperationColumn.parse(designs().get(1), Map.of("draw:1", ""), UNITS.getFirst()), "an empty entry sends nothing");
    }

    @Test
    void theTablesShowEveryPortAndDuty() {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        var view = running(designs().get(2), Phase.RUNNING);
        var u = UNITS.getFirst();
        var ports = OperationColumn.ports(view, u, t);
        assertEquals(1 + view.streams().size(), ports.size());
        assertArrayEquals(new String[]{"Port", "kmol/h", "°C", "bar", "Fill %", "Open %"}, ports.getFirst());
        assertArrayEquals(new String[]{"Feed", "103.3", "340.0", "2.40", "35", "62"}, ports.get(1));
        assertEquals("Steam 2", ports.get(3)[0], "steam inlets numbered where there are two");
        assertEquals("—", ports.get(5)[5], "an opening the valve does not read");
        assertEquals("Draw 1", ports.get(7)[0]);
        var duties = OperationColumn.duties(view, t);
        assertArrayEquals(new String[]{"Condenser", "-7.32", "-8.51"}, duties.get(1));
        assertEquals("Pumparound 4", duties.getLast()[0]);
        var solve = OperationColumn.solve(view, t);
        assertArrayEquals(new String[]{"Last solve", "Warm route"}, solve.get(0));
        assertArrayEquals(new String[]{"Age", "72 min"}, solve.get(2));
        var none = OperationColumn.solve(OperationView.idle(Phase.STOPPED, "STOPPED", designs().get(1)), t);
        assertEquals(1, none.size());
        // The glass's tables lie under one another, inside its box.
        var box = new Rect(0, 0, 400, OperationColumn.height(view));
        assertTrue(box.h() > OperationColumn.PITCH * ports.size());
    }
}
