package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.screens.inventory.FluidDeviceScreen.FluidGuiLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A fluid converter's groups on its process vessel's door (batch 2026-11-12-fluid-conversion, W5): every mode and both
 * variants, for an editor and a reader, with no rule, a reverse rule with a range outside and a forward rule; every text it
 * names has its English line; it fits the door at every supported width with its boxes in line; the deadband shows only in
 * Balance; and its widgets keep their slots while the plant's values change.
 */
class ConverterDoorTest {
    private static final Measure M = Measure.fixed(6);

    private static List<ConverterDoor.Range> reverse() {
        return List.of(new ConverterDoor.Range("Water", "0.998", "at least 0.99", "mol frac", false), new ConverterDoor.Range("Others", "0.002", "at most 0.01", "mol frac", false),
                new ConverterDoor.Range("Temperature", "71.2", "5 to 60", "°C", true), new ConverterDoor.Range("Pressure", "101.3", "90 to 150", "kPa", false));
    }

    private static List<ConverterDoor.Range> forward() {
        return List.of(new ConverterDoor.Range("Water", "1", "gives", "mol frac", false), new ConverterDoor.Range("Temperature", "25", "gives", "°C", false),
                new ConverterDoor.Range("Pressure", "101.3", "gives", "kPa", false));
    }

    static ConverterDoor.Input input(boolean edit, ConverterDoor.Mode mode, boolean closed, int rule, double fill, String booked) {
        var forge = new ConverterDoor.Tank(.75, "75", "1,500 of 2,000 mB");
        var process = new ConverterDoor.Tank(fill, Double.isFinite(fill) ? "40" : "—", closed ? "1.24 kmol, 35.6 kg" : "628 L");
        String id = rule == 0 ? null : rule == 1 ? "icheme:water_out" : "icheme:water_in";
        return new ConverterDoor.Input(edit, mode, "L/s", "1,570", "800", "5", List.of("90", "—", "—", "10"), forge, rule == 0 ? null : "minecraft:water", process,
                id, rule == 1, rule == 0 ? List.of() : rule == 1 ? reverse() : forward(), booked, rule == 1 ? "to_forge" : rule == 2 ? "to_icheme" : "none");
    }

    record Case(String name, ConverterDoor.Input in) {
    }

    private static List<Case> cases() {
        var out = new ArrayList<Case>();
        for (boolean edit : new boolean[] {true, false})
            for (var mode : ConverterDoor.Mode.values())
                for (boolean closed : new boolean[] {false, true})
                    for (int rule = 0; rule < 3; rule++)
                        out.add(new Case(mode + (closed ? " gas" : " liquid") + " rule " + rule + (edit ? "" : " reader"), input(edit, mode, closed, rule, .4, "-0.8")));
        return out;
    }

    private static Column place(ConverterDoor.Input in, HeatControlColumn.Texts texts, int width) {
        return Column.place(ConverterDoor.rows(in, texts), 10, 74, FluidGuiLayout.side(width) - 10, M, Column.Metrics.COMPACT);
    }

    @Test
    void everyTextThePageNamesHasItsEnglishLine() {
        var texts = new HeatControlColumnTest.Recording();
        for (var c : cases()) ConverterDoor.rows(c.in(), texts);
        // The texts the screen fills before the builder reads them (FluidDeviceScreen.converterInput).
        for (var key : List.of("converter_tank.forge_amount", "converter_tank.liquid_amount", "converter_tank.gas_amount", "converter_bounds.between",
                "converter_bounds.at_least", "converter_bounds.at_most", "converter_bounds.any", "converter_bounds.gives", "converter_basis.mole", "converter_basis.mass",
                "converter_others", "converter_temperature", "converter_pressure", "converter_direction.none"))
            texts.get(key, "1", "2");
        assertEquals(Set.of(), texts.missing);
        assertTrue(texts.asked.size() > 40, "the cases ask for the page's texts: " + texts.asked.size());
    }

    /** Every plate is one line at every width; nothing is cut; every widget lies inside its row, one per key; boxes in line. */
    @Test
    void thePageFitsTheDoorAtEverySupportedWidth() {
        var texts = new HeatControlColumnTest.Recording();
        for (var c : cases())
            for (int width = 300; width <= 790; width += 7) {
                var column = place(c.in(), texts, width);
                var bounds = column.bounds();
                assertTrue(bounds.right() <= FluidGuiLayout.side(width), c.name() + " ends left of its bar at " + width);
                for (var p : column.rows()) {
                    assertTrue(p.rect().x() >= bounds.x() && p.rect().right() <= bounds.right(), c.name() + " at " + width);
                    for (var t : p.texts()) assertTrue(M.width(t) <= p.rect().w(), c.name() + " a text cut at " + width + ": " + t);
                    for (var s : p.slots().values()) assertTrue(s.inside(p.rect()) && s.w() >= 40, c.name() + " a box at " + width + ": " + s);
                    if (p.row() instanceof Column.Group g) assertEquals(1, p.texts().size(), c.name() + " a plate in one line at " + width + ": " + g.title());
                    if (p.row() instanceof Column.Reading r) assertTrue(p.texts().contains(r.unit()), c.name() + " a unit whole at " + width + ": " + r.unit());
                    if (p.row() instanceof Column.Field f) assertTrue(p.texts().contains(f.unit()), c.name() + " a unit whole at " + width + ": " + f.unit());
                }
                column.slots();
                DeviceColumnTest.assertInLine(column, c.name() + " at " + width);
            }
    }

    /** The selector's four positions, latched where the mode stands; the deadband in Balance alone; an editor's entries, a reader's meters. */
    @Test
    void theSelectorLatchesItsModeAndTheDeadbandShowsOnlyInBalance() {
        var texts = new HeatControlColumnTest.Recording();
        for (var mode : ConverterDoor.Mode.values()) {
            var column = place(input(true, mode, false, 1, .4, "0"), texts, 464);
            var buttons = new ArrayList<Column.Button>();
            var fields = new ArrayList<String>();
            for (var p : column.rows()) {
                if (p.row() instanceof Column.Buttons b) buttons.addAll(b.buttons());
                if (p.row() instanceof Column.Field f) fields.add(f.key());
            }
            assertEquals(List.of("converter_mode:off", "converter_mode:balance", "converter_mode:to_icheme", "converter_mode:to_forge"), buttons.stream().map(Column.Button::key).toList());
            for (var b : buttons) {
                boolean current = b.key().equals("converter_mode:" + mode.key());
                assertEquals(current, b.latched(), b.key());
                assertEquals(!current, b.active(), b.key() + ": the other positions move the selector");
            }
            var expected = new ArrayList<>(List.of("converterRate"));
            if (mode == ConverterDoor.Mode.BALANCE) expected.add("converterDeadband");
            expected.addAll(ConverterDoor.THRESHOLDS);
            assertEquals(expected, fields, mode.name());
        }
        var reader = place(input(false, ConverterDoor.Mode.BALANCE, false, 1, .4, "0"), texts, 464);
        for (var p : reader.rows()) {
            assertFalse(p.row() instanceof Column.Field, "a reader has no entry");
            if (p.row() instanceof Column.Buttons b) for (var k : b.buttons()) assertFalse(k.active(), "a reader moves no selector");
        }
    }

    /** The widgets' slots, by key. */
    private static Map<String, Rect> widgets(Column column) {
        var out = new LinkedHashMap<String, Rect>();
        for (var p : column.rows())
            if (p.row() instanceof Column.Field || p.row() instanceof Column.Buttons) out.putAll(p.slots());
        return out;
    }

    /** What the plant reads (the fills, the booked rate, a range's value and its mark, an unknown fill) moves no widget. */
    @Test
    void aWidgetKeepsItsSlotWhileTheReadingsChange() {
        var texts = new HeatControlColumnTest.Recording();
        for (int width = 300; width <= 790; width += 49) {
            var a = widgets(place(input(true, ConverterDoor.Mode.BALANCE, false, 1, .4, "-0.8"), texts, width));
            var b = widgets(place(input(true, ConverterDoor.Mode.BALANCE, false, 1, Double.NaN, "12,345.6"), texts, width));
            assertEquals(a, b, "at " + width);
            assertFalse(a.isEmpty());
        }
    }

    /** The DCS's converter page (ControlPanelScreen) places the same groups in a console's column, 210 to 226 px: they fit, boxes in line; its own texts have their lines. */
    @Test
    void theGroupsFitADcsColumnAndItsTextsHaveTheirLines() {
        var texts = new HeatControlColumnTest.Recording();
        for (var c : cases())
            for (int width : new int[] {210, 214, 222, 226}) {
                var column = Column.place(ConverterDoor.rows(c.in(), texts), 10, 30, width, M, Column.Metrics.COMPACT);
                for (var p : column.rows()) {
                    assertTrue(p.rect().x() >= column.bounds().x() && p.rect().right() <= column.bounds().right(), c.name() + " at " + width);
                    for (var t : p.texts()) assertTrue(M.width(t) <= p.rect().w(), c.name() + " a text cut at " + width + ": " + t);
                    for (var s : p.slots().values()) assertTrue(s.inside(p.rect()) && s.w() >= 40, c.name() + " a box at " + width + ": " + s);
                }
                column.slots();
                DeviceColumnTest.assertInLine(column, c.name() + " at " + width);
            }
        var control = new HeatControlColumnTest.Recording("gui.icheme.");
        for (var key : List.of("control.converters", "control.converters.hint", "control.converter.hint", "control.no_converters", "control.converter.open", "control.converter.closed",
                "control.alarm.no_rule", "control.alarm.off_spec", "control.alarm.source_empty", "control.alarm.destination_full", "control.alarm.level_high", "control.alarm.level_low"))
            control.get(key);
        assertEquals(Set.of(), control.missing);
    }
}
