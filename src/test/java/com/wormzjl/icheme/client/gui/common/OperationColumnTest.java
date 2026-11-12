package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.common.kit.ScrollView;
import com.wormzjl.icheme.science.column.operation.Condition;
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
        // The condenser binds at 86 %, rule A holds the steam, and the last solve was refused.
        var conditions = List.of(new Condition.Active(Condition.COOLING_SHORT, .86), new Condition.Active(Condition.STEAM_SHORT, .9, 0, Condition.RULE_A),
                new Condition.Active(Condition.SOLVE_REFUSED, "the composition solve failed (test), the table of 30 s ago kept"));
        return new OperationView(phase, conditions, .86, DriftBands.Trigger.COMPOSITION, "LIMITED: condenser, 86 %", true, true, s, null, streams, duties,
                new OperationView.Solve(DriftBands.Trigger.ENTHALPY, true, 4321));
    }

    /**
     * A column on {@code s} past flood with every condition but the trip listed (W7): flooding binds at 80 %, a high load on tray 28, the
     * applied set points refused (the accepted ones: twice the steam and draws, half the pumparound heat), the trays known.
     */
    public static OperationView loaded(Setpoints s) {
        var base = running(s, Phase.LIMITED);
        var conditions = new ArrayList<Condition.Active>();
        // Every condition but the trip, which would make the phase UPSET.
        for (var c : Condition.values())
            if (c != Condition.CLOSURE) conditions.add(switch (c) {
                case FLOODING -> new Condition.Active(c, .8, 27, "");
                case HIGH_LOAD -> new Condition.Active(c, 1, 27, "tray 28 at 100 % of flood");
                case SETPOINT_REFUSED -> new Condition.Active(c, "the setpoint solve failed (no convergence), the table of 5 s ago kept");
                case STEAM_SHORT, PUMPAROUND_SHORT, PRODUCT_FULL, NO_POWER, UNMETERED -> new Condition.Active(c, .95, 1, "");
                default -> new Condition.Active(c, c.rank == Condition.Rank.LIMIT ? .9 : 0);
            });
        double[] steam = s.steamPerFeed().clone(), draws = s.drawsPerFeed().clone(), pa = s.pumparoundsPerFeed().clone();
        for (int j = 0; j < steam.length; j++) steam[j] *= 2;
        for (int j = 0; j < draws.length; j++) draws[j] *= 2;
        for (int j = 0; j < pa.length; j++) pa[j] /= 2;
        var accepted = new Setpoints(s.designFeedRate(), s.feedRate(), s.condenserTemperature(), s.refluxRatio(), s.reboilerPerFeed() * 2, steam, draws, pa);
        return new OperationView(Phase.LIMITED, conditions, .8, null, "LIMITED: flooding, 80 %", true, true, s, accepted, base.streams(), base.duties(), base.solve(),
                new OperationView.Trays(18_400, .87, 21_300, 1.25, 28));
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
                out.add(new OperationColumn.Input(OperationView.idle(Phase.STOPPED, "STOPPED", s, true), false, u));
                out.add(new OperationColumn.Input(loaded(s), false, u));
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
            OperationColumn.tables(in.view(), in.units(), t);
        }
        for (var limit : Condition.values())
            if (limit.rank == Condition.Rank.LIMIT)
                OperationColumn.status(new OperationView(Phase.LIMITED, List.of(new Condition.Active(limit, .5)), .5, null, "", true, true, setpoints(1, 1, 1, false), null, List.of(), List.of(), null), t);
        for (var trigger : DriftBands.Trigger.values()) OperationColumn.trigger(trigger, t);
        OperationColumn.conditions(new OperationView(Phase.UPSET, java.util.Arrays.stream(Condition.values()).map(c -> new Condition.Active(c, 0, 0, "")).toList(), 0, null, "", true, true,
                setpoints(1, 1, 1, false), null, List.of(), List.of(), null), t);
        OperationColumn.solve(new OperationView(Phase.RUNNING, List.of(), 1, null, "", true, true, setpoints(1, 1, 1, false), null, List.of(), List.of(),
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
                    for (var table : OperationColumn.tables(in.view(), in.units(), t)) {
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
        // The tables of before (the ports, the duties, the last solve) stay in one view; the conditions and the set points'
        // absolute values (W7) follow under them, where the glass scrolls.
        var before = OperationColumn.tables(usual.view(), UNITS.getFirst(), t).subList(0, 3);
        int first = OperationColumn.PITCH * before.stream().mapToInt(List::size).sum() + 2 * OperationColumn.TABLE_GAP + 3 * 2;
        assertTrue(first <= AssemblyColumn.glass(624, 344, true).h() - 4, "the usual tables on the glass: " + first);
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
        // An air condenser's fans (U2) draw power, not heat: their row reads kW, which its name says.
        var fans = new OperationView(Phase.RUNNING, List.of(), 1, null, "", true, true, setpoints(1, 1, 1, false), null, List.of(),
                List.of(new Duty(DutyKind.FANS, 0, 144700, 361200)), null);
        assertArrayEquals(new String[]{"Air fans, kW", "144.7", "361.2"}, OperationColumn.duties(fans, t).get(1));
        var solve = OperationColumn.solve(view, t);
        assertArrayEquals(new String[]{"Last solve", "Warm route"}, solve.get(0));
        assertArrayEquals(new String[]{"Age (play)", "72 min"}, solve.get(2));
        var none = OperationColumn.solve(OperationView.idle(Phase.STOPPED, "STOPPED", designs().get(1)), t);
        assertEquals(1, none.size());
        // The glass's tables lie under one another, inside its box.
        var box = new Rect(0, 0, 400, OperationColumn.height(view, UNITS.getFirst(), t));
        assertTrue(box.h() > OperationColumn.PITCH * ports.size());
    }

    /**
     * The glass (W7): the duties with the trays beside them, live and at the set feed; every condition in its rank's colour
     * with its share and its member; each ratio entry's absolute value at the set feed, with the accepted one beside it where
     * the applied set points were refused, and the accepted value and the reason on the entry.
     */
    @Test
    void theGlassShowsTheConditionsTheTraysAndTheSetPointsAtTheSetFeed() {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        var u = UNITS.getFirst();
        var s = designs().get(1);
        var view = loaded(s);
        var duties = OperationColumn.duties(view, u, t);
        assertArrayEquals(new String[]{"Tray drop, bar", "0.18", "0.21"}, duties.get(duties.size() - 2));
        assertArrayEquals(new String[]{"Flood %, tray 28", "87", "125"}, duties.getLast());
        assertEquals(OperationColumn.duties(running(s, Phase.RUNNING), t).size(), OperationColumn.duties(running(s, Phase.RUNNING), u, t).size(), "no tray rows without hydraulics");
        var conditions = OperationColumn.conditions(view, t);
        assertEquals(Condition.values().length, conditions.size(), "a header and every condition but the trip");
        var byName = new HashMap<String, String[]>();
        for (var row : conditions) byName.put(row[0], row);
        assertArrayEquals(new String[]{"Condition", "Share", "At"}, conditions.getFirst());
        assertArrayEquals(new String[]{"Flooding", "80 %", "Tray 28"}, byName.get("Flooding"));
        assertArrayEquals(new String[]{"High load", "100 %", "Tray 28"}, byName.get("High load"));
        assertArrayEquals(new String[]{"Set points refused", "—", "—"}, byName.get("Set points refused"));
        assertArrayEquals(new String[]{"Steam short", "95 %", "Steam 2"}, byName.get("Steam short"));
        assertEquals(List.of(), OperationColumn.conditions(OperationView.idle(Phase.STOPPED, "STOPPED", s), t), "no table where nothing holds");
        assertEquals(Palette.RED, OperationColumn.colour(Condition.Rank.TRIP));
        assertEquals(Palette.WARN, OperationColumn.colour(Condition.Rank.LIMIT));
        assertEquals(Palette.MUTED, OperationColumn.colour(Condition.Rank.NOTICE));
        // At the set feed of 28.7 mol/s: steam 0.05 mol/mol is 93 kg/h, a draw 0.155 mol/mol is 16.0 kmol/h, a pumparound -63 kJ/mol is -1.81 MW.
        var absolutes = OperationColumn.absolutes(view, u, t);
        assertArrayEquals(new String[]{"Set point", "Requested", "Accepted"}, absolutes.getFirst());
        assertArrayEquals(new String[]{"Steam, kg/h", "93", "186"}, absolutes.get(1));
        assertArrayEquals(new String[]{"Draw 1, kmol/h", "16.0", "32.0"}, absolutes.get(2));
        assertArrayEquals(new String[]{"Pumparound 1, MW", "-1.81", "-0.90"}, absolutes.get(4));
        assertArrayEquals(new String[]{"Set point", "At set feed"}, OperationColumn.absolutes(running(s, Phase.RUNNING), u, t).getFirst(), "one value where nothing was refused");
        assertEquals(List.of(), OperationColumn.absolutes(running(setpoints(0, 0, 0, false), Phase.RUNNING), u, t), "no table without a ratio entry");
        // The entry whose requested value the table in force does not hold shows the accepted one and the refusal's reason.
        var rows = OperationColumn.rows(new OperationColumn.Input(view, false, u), t);
        var steam = ((Column.Group) rows.get(1)).rows().stream().map(r -> (Column.Field) r).filter(f -> f.key().equals("steam:0")).findFirst().orElseThrow();
        assertTrue(steam.hint().contains("Accepted: 0.1000 mol/mol") && steam.hint().contains("Refused: the setpoint solve failed (no convergence)"), steam.hint());
        var reflux = ((Column.Group) rows.get(1)).rows().stream().map(r -> (Column.Field) r).filter(f -> f.key().equals("reflux")).findFirst().orElseThrow();
        assertFalse(reflux.hint().contains("Accepted"), "an entry the table holds says nothing more");
    }

    /** The DCS's column page (W6.2) places the same door in the console's page column: it fits there at every console size, narrowed for a bar too. */
    @Test
    void theDoorFitsTheDcsColumnPage() {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        for (int width = 300; width <= 760; width += 23)
            for (int height = 240; height <= 520; height += 70) {
                var layout = ControlColumns.layout(width, height, true);
                var area = layout.areas().getLast();
                for (var in : inputs())
                    for (boolean bar : new boolean[]{false, true}) {
                        int w = bar ? area.w() - ScrollView.BAR - 4 : area.w();
                        assertFits(Column.place(OperationColumn.rows(in, t), area.x(), area.y(), w, M, Column.Metrics.COMPACT), "DCS " + width + " by " + height + (bar ? " narrowed" : ""));
                    }
            }
    }

    /** Before the first run a column that can run takes set points (W7): entries and Apply; a vacuum column keeps the design's meters. */
    @Test
    void aColumnThatNeverRanTakesSetPoints() {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        var u = UNITS.getFirst();
        var s = designs().get(1);
        var idle = new OperationColumn.Input(OperationView.idle(Phase.STOPPED, "STOPPED", s, true), false, u);
        assertTrue(((Column.Group) OperationColumn.rows(idle, t).get(1)).rows().stream().allMatch(r -> r instanceof Column.Field));
        assertTrue(OperationColumn.applies(idle, Map.of()));
        var vacuum = new OperationColumn.Input(OperationView.idle(Phase.NOT_READY, "a vacuum column cannot run yet", s, false), false, u);
        assertTrue(((Column.Group) OperationColumn.rows(vacuum, t).get(1)).rows().stream().allMatch(r -> r instanceof Column.Reading));
        assertFalse(OperationColumn.applies(vacuum, Map.of()));
    }

    /** The status reads the binding condition (W1): the lowest LIMIT names the limit, a refused set point reads as a refused solve, a rule A steam as every input cut. */
    @Test
    void theStatusReadsTheBindingCondition() {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        var s = setpoints(1, 1, 1, false);
        var feed = new OperationView(Phase.LIMITED, List.of(new Condition.Active(Condition.FEED_SHORT, .5), new Condition.Active(Condition.PRODUCT_FULL, .8, 2, "")), .5, null,
                "LIMITED: feed, 50 %", true, true, s, null, List.of(), List.of(), null);
        assertEquals("Limited: feed, 50 % of the feed", OperationColumn.status(feed, t).text());
        var refused = new OperationView(Phase.RUNNING, List.of(new Condition.Active(Condition.SETPOINT_REFUSED, "the setpoint solve failed (x), the table of 5 s ago kept")), 1, null,
                "RUNNING", true, true, s, s, List.of(), List.of(), null);
        assertEquals("Running; last solve failed", OperationColumn.status(refused, t).text());
        var ruleA = new OperationView(Phase.LIMITED, List.of(new Condition.Active(Condition.STEAM_SHORT, .3, 0, Condition.RULE_A)), .3, null,
                "LIMITED: steam, 30 %", true, true, s, null, List.of(), List.of(), null);
        assertEquals("Limited: steam, 30 % of the feed, every input cut", OperationColumn.status(ruleA, t).text());
        var solving = new OperationView(Phase.RUNNING, List.of(new Condition.Active(Condition.OFF_TABLE, "composition")), 1, DriftBands.Trigger.COMPOSITION,
                "RE-SOLVING: composition; RUNNING", true, true, s, null, List.of(), List.of(), null);
        assertTrue(OperationColumn.status(solving, t).text().startsWith("Running; solving: "), OperationColumn.status(solving, t).text());
        assertEquals(Set.of(), t.missing);
    }
}
