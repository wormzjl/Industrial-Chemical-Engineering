package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;

import java.util.*;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The alarm beacon's door (batch 2026-11-15-fluid-interfaces, W9, plan section 5.3): every text it names has its English
 * line; each mode fits the door at full width and narrowed for its bar, boxes and units in line; the condition and its
 * threshold show only for an input that is not an alarm channel; the drafts' positions are latched; a widget keeps its
 * slot while the status and the reading change.
 */
public class AlarmColumnsTest {
    private static final Measure M = Measure.fixed(6);
    /** The door as AlarmBeaconScreen places it (246 wide less 20), and narrowed for a bar. */
    private static final int DOOR = 226, BAR = 12;

    /** The door's variants: a level above 80 %, an alarm channel, any alarm, a beacon off with no input. */
    public static Map<String, AlarmColumns.View> views() {
        var out = new LinkedHashMap<String, AlarmColumns.View>();
        out.put("level above", new AlarmColumns.View(true, false, "LT-0012 / LEVEL", false, true, "%", "ACTIVE", "84.2", "%", true, ""));
        out.put("pressure below", new AlarmColumns.View(true, false, "EQ-0007 / outlet pressure", false, false, "kPa(a)", "CLEAR", "1180", "kPa(a)", true, "NO DATA LINK"));
        out.put("alarm channel", new AlarmColumns.View(true, false, "EQ-0031 / alarm flame out", true, true, "", "ACKNOWLEDGED", "", "", true, ""));
        out.put("any alarm", new AlarmColumns.View(true, true, "", false, true, "m", "NO_SIGNAL", "", "", false, ""));
        out.put("off", new AlarmColumns.View(false, false, "", false, true, "m", "OFF", "", "", true, ""));
        out.put("waiting", new AlarmColumns.View(false, false, "", false, true, "m", "WAITING", "", "", true, "…"));
        return out;
    }

    private static Map<String, Column.Button> buttons(List<? extends Column.Row> rows) {
        var out = new LinkedHashMap<String, Column.Button>();
        for (var row : rows) {
            if (row instanceof Column.Group g) out.putAll(buttons(g.rows()));
            if (row instanceof Column.Buttons b) for (var button : b.buttons()) out.put(button.key(), button);
        }
        return out;
    }

    private static List<String> fields(List<? extends Column.Row> rows) {
        var out = new ArrayList<String>();
        for (var row : rows) {
            if (row instanceof Column.Group g) out.addAll(fields(g.rows()));
            if (row instanceof Column.Field f) out.add(f.key());
            if (row instanceof Column.Wide w) out.add(w.key());
        }
        return out;
    }

    private static List<ControlColumns.Choice> choices() {
        return List.of(new ControlColumns.Choice("sensor:" + new UUID(0, 1), "LT-0012 / LEVEL", true),
                new ControlColumns.Choice("sensor:" + new UUID(0, 2), "EQ-0031 / alarm flame out", false));
    }

    @Test
    void everyTextTheDoorNamesHasItsEnglishLine() {
        var texts = new HeatControlColumnTest.Recording("gui.icheme.");
        for (var v : views().values()) AlarmColumns.rows(v, texts::get);
        AlarmColumns.choices(choices(), 0, 2, texts::get);
        AlarmColumns.choices(List.of(), 0, 1, texts::get);
        for (var key : List.of("alarm_beacon.apply", "alarm_beacon.status.waiting", "control.invalid")) texts.get(key);
        assertEquals(Set.of(), texts.missing);
    }

    @Test
    void eachModeFitsTheDoorWithItsBoxesInLine() {
        var texts = new HeatControlColumnTest.Recording("gui.icheme.");
        var cases = new LinkedHashMap<String, List<Column.Row>>();
        views().forEach((name, v) -> cases.put(name, AlarmColumns.rows(v, texts::get)));
        cases.put("choices", AlarmColumns.choices(choices(), 0, 2, texts::get));
        cases.forEach((name, rows) -> {
            for (int width : new int[] {DOOR, DOOR - BAR}) {
                var column = Column.place(rows, 10, DialogColumns.TOP, width, M, Column.Metrics.COMPACT);
                var bounds = column.bounds();
                String at = name + " at " + width;
                for (var p : column.rows()) {
                    assertTrue(p.rect().x() >= bounds.x() && p.rect().right() <= bounds.right(), at);
                    for (var t : p.texts()) assertTrue(M.width(t) <= p.rect().w(), at + ": a text cut: " + t);
                    for (var s : p.slots().values()) assertTrue(s.inside(p.rect()) && s.w() >= 40, at + ": a box " + s);
                    if (p.row() instanceof Column.Group g) assertEquals(1, p.texts().size(), at + ": a plate in one line: " + g.title());
                    if (p.row() instanceof Column.Field f) assertTrue(p.texts().contains(f.unit()), at + ": a unit whole: " + f.unit());
                    if (p.row() instanceof Column.Reading r) assertTrue(p.texts().contains(r.unit()), at + ": a unit whole: " + r.unit());
                }
                column.slots();
                DeviceColumnTest.assertInLine(column, at);
            }
        });
    }

    /** The threshold shows only for an input that is not an alarm channel; the drafts' positions are latched. */
    @Test
    void eachModeShowsItsKeysWithTheDraftsLatched() {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        var v = views();
        var level = AlarmColumns.rows(v.get("level above"), t::get);
        assertEquals(List.of("name", "threshold"), fields(level));
        var keys = buttons(level);
        assertEquals(List.of("off", "on", "source:input", "source:any", "above", "below", "input"), List.copyOf(keys.keySet()));
        assertTrue(keys.get("on").latched() && !keys.get("off").latched() && keys.get("source:input").latched() && keys.get("above").latched());
        assertTrue(buttons(AlarmColumns.rows(v.get("pressure below"), t::get)).get("below").latched());
        var channel = AlarmColumns.rows(v.get("alarm channel"), t::get);
        assertEquals(List.of("name"), fields(channel), "an alarm channel needs no threshold");
        assertTrue(buttons(channel).containsKey("input") && !buttons(channel).containsKey("above"));
        var any = AlarmColumns.rows(v.get("any alarm"), t::get);
        assertEquals(List.of("name"), fields(any));
        assertEquals(List.of("off", "on", "source:input", "source:any"), List.copyOf(buttons(any).keySet()));
        assertTrue(buttons(any).get("source:any").latched());
        assertTrue(buttons(AlarmColumns.rows(v.get("off"), t::get)).get("off").latched());
        var page = buttons(AlarmColumns.choices(choices(), 0, 2, t::get));
        assertEquals(List.of("back", "inputs:prev", "inputs:next"), List.copyOf(page.keySet()));
        assertFalse(page.get("inputs:prev").active());
        assertTrue(page.get("inputs:next").active());
    }

    /** The status and the reading come last: the widgets keep their slots while they change. */
    @Test
    void aWidgetKeepsItsSlotWhileTheStatusChanges() {
        var t = new HeatControlColumnTest.Recording("gui.icheme.");
        var clear = new AlarmColumns.View(true, false, "LT-0012 / LEVEL", false, true, "%", "CLEAR", "", "", true, "");
        var active = new AlarmColumns.View(true, false, "LT-0012 / LEVEL", false, true, "%", "ACTIVE", "84.2", "%", true, "SETTINGS CHANGED: APPLY AGAIN");
        var a = Column.place(AlarmColumns.rows(clear, t::get), 10, DialogColumns.TOP, DOOR, M, Column.Metrics.COMPACT).slots();
        var b = Column.place(AlarmColumns.rows(active, t::get), 10, DialogColumns.TOP, DOOR, M, Column.Metrics.COMPACT).slots();
        for (var key : a.keySet()) if (!key.equals("reading")) assertEquals(a.get(key), b.get(key), key);
    }
}
