package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.client.gui.screens.inventory.FluidDeviceScreen.FluidGuiLayout;
import com.wormzjl.icheme.runtime.fluid.FluidView;
import com.wormzjl.icheme.runtime.fluid.HeatUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The heat Control tab's column on the GUI kit (batch 2026-10-28-gui-toolkit, GUI_TOOLKIT_PLAN.md section 4): for every
 * unit, side and mode, the rows and widget keys the screen builds; every text it names has its English line; and the
 * column, its fields and its buttons fit the panel at every supported screen width.
 */
class HeatControlColumnTest {
    private static final Measure M = Measure.fixed(6);
    private static Map<String, String> english;

    private static Map<String, String> english() throws java.io.IOException {
        if (english != null) return english;
        try (var in = HeatControlColumnTest.class.getResourceAsStream("/assets/icheme/lang/en_us.json")) {
            var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(java.util.Objects.requireNonNull(in), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            var map = new java.util.HashMap<String, String>();
            for (var e : json.entrySet()) map.put(e.getKey(), e.getValue().getAsString());
            return english = map;
        }
    }

    /** The English lines, filled in as the game fills them; every key asked for is recorded, and a missing one too. */
    private static final class Recording implements HeatControlColumn.Texts {
        final Set<String> asked = new TreeSet<>(), missing = new TreeSet<>();

        @Override
        public String get(String key, Object... args) {
            String full = "gui.icheme.fluid." + key;
            asked.add(full);
            String line;
            try {
                line = english().get(full);
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            if (line == null) {
                missing.add(full);
                return key;
            }
            return String.format(java.util.Locale.ROOT, line, args);
        }
    }

    private static FluidView.Heat heat(String unit, String side, boolean paired, String actuator, @Nullable Double effort, @Nullable FluidView.Lines lines, List<FluidView.Port> ports) {
        boolean fans = unit.equals("AIR_COOLER"), burners = unit.equals("FIRED_HEATER");
        return new FluidView.Heat(unit, side, paired, 320, -1e6, fans ? 0.5 : null, fans ? 1e4 : null, burners ? 1.0 : null, burners ? 473.15 : null, burners ? 2e6 : null,
                unit.equals("EXCHANGER") ? 0.25 : null).with(actuator, effort, "NONE", ports, lines);
    }

    private static FluidView.Port port(String role, double flow) {
        return new FluidView.Port(role, 330.0, 2e5, flow, new double[3][2], new double[3]);
    }

    private record Case(String name, HeatControlColumn.Input in) {
    }

    /** Every unit and side, every mode its list holds, as an editor and as a reader. */
    private static List<Case> cases() {
        var out = new ArrayList<Case>();
        for (boolean edit : new boolean[]{true, false}) {
            for (var mode : HeatUnit.Mode.values()) {
                if (mode == HeatUnit.Mode.FIRING) continue;
                var applied = new HeatUnit.Control(mode, 330, 0, 0.25);
                for (var side : List.of("TUBE", "SHELL")) {
                    String actuator = mode == HeatUnit.Mode.RATIO ? "feed_valve" : "bypass";
                    out.add(new Case("exchanger " + side + " " + mode + (edit ? "" : " reader"), new HeatControlColumn.Input(heat("EXCHANGER", side, true, actuator, mode == HeatUnit.Mode.RATIO ? null : 0.25, null, List.of()), applied, edit, true, mode, true, "C")));
                }
                if (mode != HeatUnit.Mode.RATIO) {
                    out.add(new Case("air cooler " + mode, new HeatControlColumn.Input(heat("AIR_COOLER", "PROCESS", false, "fans", 0.5, null, List.of()), applied, edit, true, mode, true, "C")));
                    out.add(new Case("kettle tubes " + mode, new HeatControlColumn.Input(heat("KETTLE", "TUBE", true, "bypass", 0.1, null, List.of()), applied, edit, true, mode, true, "K")));
                }
            }
            for (var side : List.of("CONVECTION", "RADIANT"))
                for (boolean on : new boolean[]{true, false})
                    out.add(new Case("heater " + side + (on ? " firing" : " out"), new HeatControlColumn.Input(heat("FIRED_HEATER", side, true, "burners", 0.7, null, List.of()), null, edit, false, HeatUnit.Mode.OFF, on, "C")));
            out.add(new Case("kettle pool", new HeatControlColumn.Input(heat("KETTLE", "SHELL", true, "none", null, null, List.of()), null, edit, false, HeatUnit.Mode.OFF, true, "C")));
        }
        return out;
    }

    @Test
    void everyTextTheColumnNamesHasItsEnglishLine() {
        var texts = new Recording();
        for (var c : cases()) HeatControlColumn.rows(c.in(), texts);
        // The lines' notes, every status of both kinds.
        for (var status : List.of("NOT_VALIDATED", "CHECKED", "UNMETERED", "COMPLETE"))
            for (var mode : List.of(HeatUnit.Mode.OUTLET_TEMPERATURE, HeatUnit.Mode.RATIO)) {
                var lines = new FluidView.Lines(status, 1, List.of());
                HeatControlColumn.rows(new HeatControlColumn.Input(heat("EXCHANGER", "TUBE", true, "bypass", 0.2, lines, List.of()), new HeatUnit.Control(mode, 330, 0, 0), true, true, mode, true, "C"), texts);
            }
        assertEquals(Set.of(), texts.missing);
        assertTrue(texts.asked.size() > 30, "the cases ask for the column's texts: " + texts.asked.size());
    }

    @Test
    void theColumnAndItsWidgetsFitThePanelAtEverySupportedWidth() {
        var texts = new Recording();
        for (var c : cases()) {
            var rows = HeatControlColumn.rows(c.in(), texts);
            for (int width = 300; width <= 790; width += 7) {
                var slots = FluidGuiLayout.control(width);
                var column = Column.place(rows, slots.panelX(), 74, slots.panelWidth(), M);
                for (var p : column.rows()) {
                    assertTrue(p.rect().x() >= slots.panelX() && p.rect().right() <= slots.panelX() + slots.panelWidth(), c.name() + " at " + width);
                    for (var s : p.slots().values()) assertTrue(s.inside(p.rect()) && s.w() >= 40, c.name() + " a widget at " + width + ": " + s);
                }
                column.slots();
            }
        }
    }

    @Test
    void eachSideListsItsOwnModesFieldsAndWidgets() {
        var texts = new Recording();
        // An exchanger's tube side held at its outlet: four modes, the ratio's naming the feed valve, the others the bypass.
        var tube = new HeatControlColumn.Input(heat("EXCHANGER", "TUBE", true, "bypass", 0.25, null, List.of()), new HeatUnit.Control(HeatUnit.Mode.OUTLET_TEMPERATURE, 330, 0, 0), true, true, HeatUnit.Mode.OUTLET_TEMPERATURE, true, "C");
        var rows = HeatControlColumn.rows(tube, texts);
        assertEquals(List.of("mode:off", "mode:outlet_temperature", "mode:duty", "mode:ratio"), choices(rows).stream().map(Column.Choice::action).toList());
        assertTrue(choices(rows).get(1).selected());
        assertEquals(texts.get("heat_mode.ratio.line", texts.get("heat_actuator.feed_valve")), choices(rows).get(3).line());
        assertTrue(choices(rows).get(1).line().contains(texts.get("heat_actuator.bypass")));
        assertEquals(List.of("side:0", "side:1", "heatTemperature", "lowLimit"), keys(rows));
        var selector = (Column.Buttons) rows.getFirst();
        assertFalse(selector.buttons().get(0).active(), "this side's own button");
        assertTrue(selector.buttons().get(1).active());
        assertTrue(rows.stream().anyMatch(r -> r instanceof Column.Bar b && b.share() == 0.25), "the internal bypass's effort");
        // The shell side: its own button is the second.
        var shell = new HeatControlColumn.Input(heat("EXCHANGER", "SHELL", true, "bypass", 0.0, null, List.of()), null, true, true, HeatUnit.Mode.DUTY, true, "C");
        var shellRows = HeatControlColumn.rows(shell, texts);
        assertFalse(((Column.Buttons) shellRows.getFirst()).buttons().get(1).active());
        assertEquals(List.of("side:0", "side:1", "heatDuty", "lowLimit"), keys(shellRows));
        // An air cooler in manual: no selector, its fans by hand, no bypass field; its modes name its fans.
        var cooler = new HeatControlColumn.Input(heat("AIR_COOLER", "PROCESS", false, "fans", 1.0, null, List.of()), null, true, true, HeatUnit.Mode.OFF, true, "C");
        var coolerRows = HeatControlColumn.rows(cooler, texts);
        assertEquals(List.of("power", "lowLimit"), keys(coolerRows));
        assertTrue(choices(coolerRows).get(1).line().contains(texts.get("heat_actuator.fans")));
        // Louvers, when the server names them, name the modes too.
        var louvered = new HeatControlColumn.Input(heat("AIR_COOLER", "PROCESS", false, "louvers", 0.3, null, List.of()), null, true, true, HeatUnit.Mode.OUTLET_TEMPERATURE, true, "C");
        assertTrue(choices(HeatControlColumn.rows(louvered, texts)).get(1).line().contains(texts.get("heat_actuator.louvers")));
        // A heater's coil: the burners as two modes, its set point, no low limit.
        var coil = new HeatControlColumn.Input(heat("FIRED_HEATER", "RADIANT", true, "burners", 0.7, null, List.of()), null, true, false, HeatUnit.Mode.OFF, false, "C");
        var coilRows = HeatControlColumn.rows(coil, texts);
        assertEquals(List.of("mode:burners_out", "mode:firing"), choices(coilRows).stream().map(Column.Choice::action).toList());
        assertTrue(choices(coilRows).get(0).selected());
        assertEquals(List.of("side:0", "side:1", "temperature"), keys(coilRows));
        // The kettle's pool: the note, no modes or fields; nothing acts.
        var pool = new HeatControlColumn.Input(heat("KETTLE", "SHELL", true, "none", null, null, List.of()), null, true, false, HeatUnit.Mode.OFF, true, "C");
        var poolRows = HeatControlColumn.rows(pool, texts);
        assertTrue(choices(poolRows).isEmpty());
        assertEquals(List.of("side:0", "side:1"), keys(poolRows));
        assertTrue(poolRows.stream().anyMatch(r -> r instanceof Column.Paragraph p && p.text().equals(texts.get("pool_note"))));
        assertTrue(poolRows.stream().noneMatch(r -> r instanceof Column.Bar));
        // A reader: choices that act on nothing, the reader's note, no fans button.
        var reader = new HeatControlColumn.Input(cooler.heat(), null, false, true, HeatUnit.Mode.OFF, true, "C");
        var readerRows = HeatControlColumn.rows(reader, texts);
        assertTrue(choices(readerRows).stream().allMatch(c -> c.action() == null));
        assertEquals(List.of("lowLimit"), keys(readerRows));
        assertTrue(readerRows.stream().anyMatch(r -> r instanceof Column.Paragraph p && p.text().equals(texts.get("reader_note"))));
    }

    @Test
    void aRatioReadsItsMeasuredRatioAndCompleteLinesSayItInTheAccent() {
        var texts = new Recording();
        var ports = List.of(port("TUBE_INLET", 3), port("TUBE_OUTLET", 3), port("SHELL_INLET", 6), port("SHELL_OUTLET", 6));
        var lines = new FluidView.Lines("COMPLETE", 0, List.of());
        var in = new HeatControlColumn.Input(heat("EXCHANGER", "TUBE", true, "feed_valve", null, lines, ports), new HeatUnit.Control(HeatUnit.Mode.RATIO, 0.5, 0, 0), true, true, HeatUnit.Mode.RATIO, true, "C");
        var rows = HeatControlColumn.rows(in, texts);
        assertTrue(rows.stream().anyMatch(r -> r instanceof Column.Line l && l.text().equals(texts.get("heat_effort.feed_valve", "0.5"))), "tube feed 3 over shell feed 6");
        assertTrue(rows.stream().noneMatch(r -> r instanceof Column.Bar), "a ratio has no bar");
        assertTrue(rows.stream().anyMatch(r -> r instanceof Column.Paragraph p && p.text().equals(texts.get("lines_note.ratio.complete", 0)) && p.colour() == Palette.ACCENT));
        assertEquals(List.of("side:0", "side:1", "heatRatio", "lowLimit"), keys(rows));
        var checked = new HeatControlColumn.Input(in.heat().with("none", null, "NONE", ports, new FluidView.Lines("CHECKED", 2, List.of())), in.applied(), true, true, HeatUnit.Mode.RATIO, true, "C");
        assertTrue(HeatControlColumn.rows(checked, texts).stream().anyMatch(r -> r instanceof Column.Paragraph p && p.text().equals(texts.get("lines_note.ratio.checked", 2)) && p.colour() == Palette.WARN));
    }

    private static List<Column.Choice> choices(List<Column.Row> rows) {
        return rows.stream().filter(r -> r instanceof Column.Choice).map(r -> (Column.Choice) r).toList();
    }

    private static List<String> keys(List<Column.Row> rows) {
        var keys = new ArrayList<String>();
        for (var r : rows) {
            if (r instanceof Column.Buttons b) for (var button : b.buttons()) keys.add(button.key());
            else if (r instanceof Column.Field f) keys.add(f.key());
        }
        assertEquals(keys.size(), new HashSet<>(keys).size(), "widget keys are distinct");
        return keys;
    }
}
