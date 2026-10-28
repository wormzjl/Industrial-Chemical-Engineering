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
 * The heat Control tab's column on the GUI kit (batch 2026-10-28-gui-toolkit, GUI_TOOLKIT_PLAN.md sections 4 and 11): for
 * every unit, side, switch position and target, the groups, rows and widget keys the screen builds; every text it names
 * has its English line; and the column, its plates, fields and buttons fit the panel at every supported screen width.
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
                    if (mode == HeatUnit.Mode.OFF)
                        out.add(new Case("air cooler still", new HeatControlColumn.Input(heat("AIR_COOLER", "PROCESS", false, "fans", 0.0, null, List.of()), applied, edit, true, mode, false, "C")));
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

    /** Layout C's two columns, or one on a narrow screen, as FluidDeviceScreen.buildControl places them. */
    private static List<Column> place(HeatControlColumn.Input in, HeatControlColumn.Texts texts, FluidGuiLayout.ControlSlots slots) {
        if (!slots.split()) return List.of(Column.place(HeatControlColumn.rows(in, texts), slots.rightX(), 74, slots.rightWidth(), M, Column.Metrics.COMPACT));
        var two = HeatControlColumn.columns(in, texts);
        return List.of(Column.place(two.left(), slots.leftX(), 74, slots.leftWidth(), M, Column.Metrics.COMPACT),
                Column.place(two.right(), slots.rightX(), 74, slots.rightWidth(), M, Column.Metrics.COMPACT));
    }

    /** Owner, 2026-10-28: "so new users are not overwhelmed". Every plate is one line at every width; nothing is cut. */
    @Test
    void theColumnsAndTheirWidgetsFitThePanelAtEverySupportedWidth() {
        var texts = new Recording();
        for (var c : cases()) {
            for (int width = 300; width <= 790; width += 7) {
                var slots = FluidGuiLayout.control(width);
                var keys = new HashSet<String>();
                for (var column : place(c.in(), texts, slots)) {
                    var bounds = column.bounds();
                    for (var p : column.rows()) {
                        assertTrue(p.rect().x() >= bounds.x() && p.rect().right() <= bounds.right(), c.name() + " at " + width);
                        for (var t : p.texts()) assertTrue(M.width(t) <= p.rect().w(), c.name() + " a text cut at " + width + ": " + t);
                        for (var s : p.slots().values()) assertTrue(s.inside(p.rect()) && s.w() >= 40, c.name() + " a widget at " + width + ": " + s);
                        if (p.row() instanceof Column.Group g) assertEquals(1, p.texts().size(), c.name() + " a plate in one line at " + width + ": " + g.title());
                    }
                    for (var key : column.slots().keySet()) assertTrue(keys.add(key), c.name() + " one widget per key: " + key);
                }
            }
        }
    }

    @Test
    void layoutCPutsTheSelectorControlAndTargetLeftAndTheRestRight() {
        var texts = new Recording();
        var lines = new FluidView.Lines("CHECKED", 1, List.of());
        var in = new HeatControlColumn.Input(heat("EXCHANGER", "TUBE", true, "bypass", 0.25, lines, List.of()), new HeatUnit.Control(HeatUnit.Mode.OUTLET_TEMPERATURE, 330, 0, 0), true, true, HeatUnit.Mode.OUTLET_TEMPERATURE, true, "C");
        var two = HeatControlColumn.columns(in, texts);
        assertInstanceOf(Column.Buttons.class, two.left().getFirst(), "the side selector heads the left");
        assertEquals(List.of(texts.get("heat_group.control"), texts.get("heat_group.target")), groups(two.left()));
        assertEquals(List.of(texts.get("heat_group.set_point"), texts.get("heat_acting.bypass")), groups(two.right()));
        assertEquals(List.of("side:0", "side:1", "power:off", "power:on"), keys(two.left()));
        assertEquals(List.of("heatTemperature", "lowLimit", "check_lines"), keys(two.right()));
        assertEquals(HeatControlColumn.rows(in, texts), java.util.stream.Stream.concat(two.left().stream(), two.right().stream()).toList(), "one column: the left, then the right");
        var pool = new HeatControlColumn.Input(heat("KETTLE", "SHELL", true, "none", null, null, List.of()), null, true, false, HeatUnit.Mode.OFF, true, "C");
        var poolColumns = HeatControlColumn.columns(pool, texts);
        assertInstanceOf(Column.Paragraph.class, poolColumns.left().getLast(), "the pool's note closes the left");
        assertTrue(poolColumns.right().isEmpty());
    }

    /** Owner, 2026-10-28: "The logic should be something like a On/off switch plus a target duty mode and target temperature mode". */
    @Test
    void eachSideHasItsSwitchItsTargetsAndItsSettings() {
        var texts = new Recording();
        // An exchanger's tube side held at its outlet: the switch at On, three targets, the outlet's set point.
        var tube = new HeatControlColumn.Input(heat("EXCHANGER", "TUBE", true, "bypass", 0.25, null, List.of()), new HeatUnit.Control(HeatUnit.Mode.OUTLET_TEMPERATURE, 330, 0, 0), true, true, HeatUnit.Mode.OUTLET_TEMPERATURE, true, "C");
        var rows = HeatControlColumn.rows(tube, texts);
        assertEquals(List.of("target:outlet_temperature", "target:duty", "target:ratio"), choices(rows).stream().map(Column.Choice::action).toList());
        assertTrue(choices(rows).getFirst().selected());
        assertTrue(choices(rows).getFirst().hint().contains(texts.get("heat_actuator.bypass")), "a target's hover names what it works");
        assertEquals(List.of("side:0", "side:1", "power:off", "power:on", "heatTemperature", "lowLimit"), keys(rows));
        assertEquals(List.of(true, false, false, true), latched(rows), "this side's button and On stand latched");
        assertEquals(List.of(false, true, true, false), active(rows), "a click acts on the other side and on Off");
        assertTrue(flat(rows).stream().anyMatch(r -> r instanceof Column.Bar b && b.share() == 0.25), "the internal bypass's effort");
        // Switched Off: no targets; the bypass by hand.
        var off = new HeatControlColumn.Input(tube.heat(), tube.applied(), true, true, HeatUnit.Mode.OFF, true, "C");
        var offRows = HeatControlColumn.rows(off, texts);
        assertTrue(choices(offRows).isEmpty());
        assertEquals(List.of("side:0", "side:1", "power:off", "power:on", "bypass", "lowLimit"), keys(offRows));
        assertTrue(groups(offRows).contains(texts.get("heat_group.manual")));
        // The shell side held at a duty: its own button is the second.
        var shell = new HeatControlColumn.Input(heat("EXCHANGER", "SHELL", true, "bypass", 0.0, null, List.of()), null, true, true, HeatUnit.Mode.DUTY, true, "C");
        var shellRows = HeatControlColumn.rows(shell, texts);
        assertEquals(List.of(false, true, false, true), latched(shellRows));
        assertEquals(List.of("side:0", "side:1", "power:off", "power:on", "heatDuty", "lowLimit"), keys(shellRows));
        // An air cooler at a fixed fan speed (owner: "AC should have duty ratio mode and target temperature mode as well"):
        // the switch at On, three targets, the fans' speed in %; no selector, no bypass, no low limit.
        var cooler = new HeatControlColumn.Input(heat("AIR_COOLER", "PROCESS", false, "fans", 1.0, null, List.of()), null, true, true, HeatUnit.Mode.OFF, true, "C");
        var coolerRows = HeatControlColumn.rows(cooler, texts);
        assertEquals(List.of("power:off", "power:on", "fans"), keys(coolerRows), "no low limit: the target is the floor");
        assertEquals(List.of(false, true), latched(coolerRows));
        assertEquals(List.of("target:outlet_temperature", "target:duty", "target:fan_speed"), choices(coolerRows).stream().map(Column.Choice::action).toList());
        assertTrue(choices(coolerRows).get(2).selected(), "no controller, the fans running: a fixed fan speed");
        assertEquals(texts.get("heat_switch.fans_off.hint"), buttons(coolerRows).getFirst().hint());
        // Switched Off: the fans still, nothing to set.
        var still = HeatControlColumn.rows(new HeatControlColumn.Input(cooler.heat(), null, true, true, HeatUnit.Mode.OFF, false, "C"), texts);
        assertEquals(List.of("power:off", "power:on"), keys(still));
        assertEquals(List.of(true, false), latched(still));
        assertTrue(choices(still).isEmpty());
        // Held at its outlet; louvers, when the server names them, name the targets' hovers.
        var louvered = new HeatControlColumn.Input(heat("AIR_COOLER", "PROCESS", false, "louvers", 0.3, null, List.of()), null, true, true, HeatUnit.Mode.OUTLET_TEMPERATURE, true, "C");
        var louveredRows = HeatControlColumn.rows(louvered, texts);
        assertTrue(choices(louveredRows).getFirst().selected());
        assertEquals(List.of("power:off", "power:on", "heatTemperature"), keys(louveredRows));
        assertTrue(choices(louveredRows).getFirst().hint().contains(texts.get("heat_actuator.louvers")));
        // A heater's coil: the switch is its burners; lit, the outlet's set point; no targets, no low limit.
        var coil = new HeatControlColumn.Input(heat("FIRED_HEATER", "RADIANT", true, "burners", 0.7, null, List.of()), null, true, false, HeatUnit.Mode.OFF, false, "C");
        assertEquals(List.of("side:0", "side:1", "power:off", "power:on"), keys(HeatControlColumn.rows(coil, texts)));
        var lit = new HeatControlColumn.Input(coil.heat(), null, true, false, HeatUnit.Mode.OFF, true, "C");
        var litRows = HeatControlColumn.rows(lit, texts);
        assertEquals(List.of("side:0", "side:1", "power:off", "power:on", "temperature"), keys(litRows));
        assertTrue(choices(litRows).isEmpty());
        assertEquals(texts.get("heat_switch.burners_on.hint"), buttons(litRows).get(3).hint());
        // The kettle's pool: the note, no switch or fields; nothing acts.
        var pool = new HeatControlColumn.Input(heat("KETTLE", "SHELL", true, "none", null, null, List.of()), null, true, false, HeatUnit.Mode.OFF, true, "C");
        var poolRows = HeatControlColumn.rows(pool, texts);
        assertEquals(List.of("side:0", "side:1"), keys(poolRows));
        assertTrue(poolRows.stream().anyMatch(r -> r instanceof Column.Paragraph p && p.text().equals(texts.get("pool_note"))));
        assertTrue(flat(poolRows).stream().noneMatch(r -> r instanceof Column.Bar));
        // A reader: the switch stands where it is and acts on nothing; the reader's note.
        var reader = new HeatControlColumn.Input(cooler.heat(), null, false, true, HeatUnit.Mode.OFF, true, "C");
        var readerRows = HeatControlColumn.rows(reader, texts);
        assertEquals(List.of(false, false), active(readerRows));
        assertEquals(List.of(false, true), latched(readerRows));
        assertTrue(readerRows.stream().anyMatch(r -> r instanceof Column.Paragraph p && p.text().equals(texts.get("reader_note"))));
        var readerHolding = new HeatControlColumn.Input(louvered.heat(), null, false, true, HeatUnit.Mode.OUTLET_TEMPERATURE, true, "C");
        assertTrue(choices(HeatControlColumn.rows(readerHolding, texts)).stream().allMatch(c -> c.action() == null));
    }

    @Test
    void aRatioReadsItsMeasuredRatioAndOnlyIncompleteLinesTakeANote() {
        var texts = new Recording();
        var ports = List.of(port("TUBE_INLET", 3), port("TUBE_OUTLET", 3), port("SHELL_INLET", 6), port("SHELL_OUTLET", 6));
        var lines = new FluidView.Lines("COMPLETE", 0, List.of());
        var in = new HeatControlColumn.Input(heat("EXCHANGER", "TUBE", true, "feed_valve", null, lines, ports), new HeatUnit.Control(HeatUnit.Mode.RATIO, 0.5, 0, 0), true, true, HeatUnit.Mode.RATIO, true, "C");
        var column = HeatControlColumn.rows(in, texts);
        var rows = flat(column);
        assertTrue(rows.stream().anyMatch(r -> r instanceof Column.Line l && l.text().equals(texts.get("heat_effort.feed_valve", "0.5"))), "tube feed 3 over shell feed 6");
        assertTrue(rows.stream().noneMatch(r -> r instanceof Column.Bar), "a ratio has no bar");
        assertTrue(rows.stream().noneMatch(r -> r instanceof Column.Paragraph), "complete lines: no note");
        assertTrue(groups(column).contains(texts.get("heat_acting.feed_valve")), "the plate names the real valve");
        assertEquals(List.of("side:0", "side:1", "power:off", "power:on", "heatRatio", "lowLimit", "check_lines"), keys(column), "Check lines under the lines' note");
        var checked = new HeatControlColumn.Input(in.heat().with("none", null, "NONE", ports, new FluidView.Lines("CHECKED", 2, List.of())), in.applied(), true, true, HeatUnit.Mode.RATIO, true, "C");
        var checkedRows = HeatControlColumn.rows(checked, texts);
        assertTrue(flat(checkedRows).stream().anyMatch(r -> r instanceof Column.Paragraph p && p.text().equals(texts.get("lines_note.ratio.checked", 2)) && p.colour() == Palette.WARN));
        assertTrue(groups(checkedRows).contains(texts.get("heat_group.lines")), "nothing acts: the lines' own group");
    }

    /** The rows, a group followed by its own rows. */
    private static List<Column.Row> flat(List<Column.Row> rows) {
        var out = new ArrayList<Column.Row>();
        for (var r : rows) {
            out.add(r);
            if (r instanceof Column.Group g) out.addAll(flat(g.rows()));
        }
        return out;
    }

    private static List<String> groups(List<Column.Row> rows) {
        return flat(rows).stream().filter(r -> r instanceof Column.Group).map(r -> ((Column.Group) r).title()).toList();
    }

    private static List<Column.Choice> choices(List<Column.Row> rows) {
        return flat(rows).stream().filter(r -> r instanceof Column.Choice).map(r -> (Column.Choice) r).toList();
    }

    private static List<Column.Button> buttons(List<Column.Row> rows) {
        return flat(rows).stream().filter(r -> r instanceof Column.Buttons).flatMap(r -> ((Column.Buttons) r).buttons().stream()).toList();
    }

    private static List<Boolean> latched(List<Column.Row> rows) {
        return buttons(rows).stream().map(Column.Button::latched).toList();
    }

    private static List<Boolean> active(List<Column.Row> rows) {
        return buttons(rows).stream().map(Column.Button::active).toList();
    }

    private static List<String> keys(List<Column.Row> rows) {
        var keys = new ArrayList<String>();
        for (var r : flat(rows)) {
            if (r instanceof Column.Buttons b) for (var button : b.buttons()) keys.add(button.key());
            else if (r instanceof Column.Field f) keys.add(f.key());
        }
        assertEquals(keys.size(), new HashSet<>(keys).size(), "widget keys are distinct");
        return keys;
    }
}
