package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.screens.inventory.FluidDeviceScreen.FluidGuiLayout;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A fluid device's door column (batch 2026-10-28-gui-toolkit, GUI_TOOLKIT_PLAN.md section 14, GUI_REVIEW.md order 1):
 * every kind's groups and widget keys; every text it names has its English line; it fits the door at every supported
 * width; and its widgets keep their slots while the plant's readings change, so the screen may place it again each frame
 * without a rebuild.
 */
class DeviceColumnTest {
    private static final Measure M = Measure.fixed(6);

    /** Every reading the screen shows (FluidDeviceScreen.meters), each once. */
    private static final List<String> METERS = List.of("fans_state", "burners_state", "temperature", "pressure", "top_pressure", "bottom_pressure", "mass",
            "inlet_temperature", "suction_pressure", "inlet_pressure", "net_flow", "throughput", "velocity", "velocity_lowest", "velocity_highest", "gradient",
            "gradient_lowest", "gradient_highest", "filter_load", "captured", "pressure_change", "outlet_temperature", "duty", "bypass", "fans_speed",
            "fan_power", "set_temperature", "duty_available", "pressure_ratio", "pressure_probe", "temperature_probe", "flowmeter", "boot_level", "body_level");

    private static List<DeviceColumn.Meter> meters(int count) {
        var list = new ArrayList<DeviceColumn.Meter>();
        for (int i = 0; i < count; i++) list.add(new DeviceColumn.Meter(METERS.get(i % METERS.size()), "101.3", i % 3 == 0 ? "kmol/min" : "kPa", i % 2 == 0 ? "a hint" : null));
        return list;
    }

    private static DeviceColumn.Phases phases(String basis, boolean excluded) {
        return new DeviceColumn.Phases(basis, new double[]{1, 2, 3, 0.5}, excluded, "Phase fractions.");
    }

    private static DeviceColumn.Input input(Kind kind, boolean edit, boolean switchable, DeviceColumn.Valve valve, int readings, String controller, String phaseBasis) {
        return new DeviceColumn.Input(kind, edit, switchable, true, "kPa", "°C", "/min", valve,
                kind == Kind.VALVE ? new DeviceColumn.Opening(0.42, controller) : null, kind == Kind.FILTER, kind == Kind.RESERVOIR ? new DeviceColumn.Level(0.43, "1200.0", "2800.0") : null,
                kind == Kind.PIPE ? "Junction · 3 connections" : null, List.of(), phases(phaseBasis, phaseBasis.equals("mole")), meters(readings));
    }

    private record Case(String name, DeviceColumn.Input in) {
    }

    /** Every kind as an editor and a reader, a valve in every setting, a mover with and without its switch, every phase basis. */
    private static List<Case> cases() {
        var out = new ArrayList<Case>();
        for (boolean edit : new boolean[]{true, false})
            for (var kind : Kind.values())
                for (var basis : List.of("volume", "mole", "mass")) {
                    var valves = kind == Kind.VALVE ? List.of(DeviceColumn.Valve.values()) : List.of(DeviceColumn.Valve.NONE);
                    for (var valve : valves)
                        for (boolean switchable : kind == Kind.PUMP || kind == Kind.COMPRESSOR || kind == Kind.GENERATOR ? new boolean[]{true, false} : new boolean[]{false})
                            out.add(new Case(kind + " " + valve + (switchable ? " switch" : "") + (edit ? "" : " reader") + " " + basis, input(kind, edit, switchable, valve, 6, "Holding 2.0 kg/s", basis)));
                }
        return out;
    }

    private static Column place(DeviceColumn.Input in, HeatControlColumn.Texts texts, int width) {
        return Column.place(DeviceColumn.rows(in, texts), 10, 74, FluidGuiLayout.side(width) - 10, M, Column.Metrics.COMPACT);
    }

    @Test
    void everyTextTheColumnNamesHasItsEnglishLine() {
        var texts = new HeatControlColumnTest.Recording();
        for (var c : cases()) DeviceColumn.rows(c.in(), texts);
        DeviceColumn.rows(input(Kind.PIPE, true, false, DeviceColumn.Valve.NONE, METERS.size(), "", "volume"), texts);
        for (var key : List.of("reading.unavailable", "reading.still", "reading.volume_flow", "reading.height", "reading.height_overrange", "line_temperature.hint",
                "top_pressure.hint", "bottom_pressure.hint", "net_flow.hint", "throughput.hint", "velocity.hint", "gradient.hint", "state_on", "state_off", "junction",
                "reversed_interval", "phase_ratio.hint", "phase_ratio.tank_hint", "phase_ratio.generator_hint", "phase_ratio.mole_hint"))
            texts.get(key, "1");
        assertEquals(Set.of(), texts.missing);
        assertTrue(texts.asked.size() > 60, "the cases ask for the column's texts: " + texts.asked.size());
    }

    /** Every plate is one line at every width; nothing is cut; every widget lies inside its row, one per key. */
    @Test
    void theColumnFitsTheDoorAtEverySupportedWidth() {
        var texts = new HeatControlColumnTest.Recording();
        for (var c : cases())
            for (int width = 300; width <= 790; width += 7) {
                var column = place(c.in(), texts, width);
                var bounds = column.bounds();
                assertTrue(bounds.right() <= FluidGuiLayout.side(width), c.name() + " ends left of its bar at " + width);
                for (var p : column.rows()) {
                    assertTrue(p.rect().x() >= bounds.x() && p.rect().right() <= bounds.right(), c.name() + " at " + width);
                    for (var t : p.texts()) assertTrue(M.width(t) <= p.rect().w(), c.name() + " a text cut at " + width + ": " + t);
                    for (var s : p.slots().values()) assertTrue(s.inside(p.rect()) && s.w() >= (p.row() instanceof Column.Buttons ? 40 : 30), c.name() + " a box at " + width + ": " + s);
                    if (p.row() instanceof Column.Group g) assertEquals(1, p.texts().size(), c.name() + " a plate in one line at " + width + ": " + g.title());
                    if (p.row() instanceof Column.Reading r) assertTrue(p.texts().contains(r.unit()), c.name() + " a unit whole at " + width + ": " + r.unit());
                    if (p.row() instanceof Column.Field f) assertTrue(p.texts().contains(f.unit()), c.name() + " a unit whole at " + width + ": " + f.unit());
                }
                column.slots();
            }
    }

    /** The widgets' slots, by key: a field's and a button's, not a meter's. */
    private static Map<String, Rect> widgets(Column column) {
        var out = new LinkedHashMap<String, Rect>();
        for (var p : column.rows())
            if (p.row() instanceof Column.Field || p.row() instanceof Column.Buttons) out.putAll(p.slots());
        return out;
    }

    /**
     * The screen places its widgets at a rebuild and the texts again each frame: what the plant reads (the readings, the
     * valve's controller words, the custom solids' warning) moves no widget. The basis key keeps its slot as its word changes.
     */
    @Test
    void aWidgetKeepsItsSlotWhileTheReadingsChange() {
        var texts = new HeatControlColumnTest.Recording();
        for (var kind : Kind.values())
            for (var valve : kind == Kind.VALVE ? List.of(DeviceColumn.Valve.values()) : List.of(DeviceColumn.Valve.NONE))
                for (int width : new int[]{300, 464, 790}) {
                    var before = widgets(place(input(kind, true, true, valve, 2, "", "volume"), texts, width));
                    var after = place(input(kind, true, true, valve, 14, "Held at its limit while the downstream pressure stays above the set point", "mole"), texts, width);
                    assertEquals(before, widgets(after), kind + " " + valve + " at " + width);
                    assertFalse(before.isEmpty(), kind + " has its phase basis key at least");
                }
    }

    /** What an editor sets on each kind, by its fields' keys; a reader sets nothing. */
    @Test
    void eachKindHasItsSetPointsAndAReaderNone() {
        var texts = new HeatControlColumnTest.Recording();
        Map<Kind, List<String>> fields = Map.of(Kind.GENERATOR, List.of("pressure", "temperature"), Kind.PUMP, List.of("volumeFlow", "maximumAddedPressure"),
                Kind.COMPRESSOR, List.of("volumeFlow", "maximumPressureRatio"), Kind.VOID, List.of("pressure"), Kind.PIPE, List.of("diameter"));
        for (var kind : Kind.values()) {
            if (kind == Kind.VALVE) continue;
            assertEquals(fields.getOrDefault(kind, List.of()), keys(place(input(kind, true, false, DeviceColumn.Valve.NONE, 3, "", "volume"), texts, 464), Column.Field.class), kind.name());
            assertEquals(List.of(), keys(place(input(kind, false, false, DeviceColumn.Valve.NONE, 3, "", "volume"), texts, 464), Column.Field.class), kind + " reader");
        }
        var valve = Map.of(DeviceColumn.Valve.NONE, List.<String>of(), DeviceColumn.Valve.UNCONFIGURED, List.<String>of(), DeviceColumn.Valve.PRESSURE, List.of("pressure"),
                DeviceColumn.Valve.MASS_FLOW, List.of("massFlow"), DeviceColumn.Valve.VOLUME_FLOW, List.of("volumeFlow"));
        for (var e : valve.entrySet())
            assertEquals(e.getValue(), keys(place(input(Kind.VALVE, true, false, e.getKey(), 3, "", "volume"), texts, 464), Column.Field.class), "valve " + e.getKey());
    }

    private static List<String> keys(Column column, Class<? extends Column.Row> type) {
        var out = new ArrayList<String>();
        for (var p : column.rows()) if (type.isInstance(p.row())) out.addAll(p.slots().keySet());
        return out;
    }

    /**
     * The switch: both positions latched where it stands, a click only on the other and only for an editor; a flow valve's
     * target as two choices; a filter's recovery while solids are held; the Phases' basis a display key.
     */
    @Test
    void theSwitchTheTargetTheRecoveryAndTheBasisKey() {
        var texts = new HeatControlColumnTest.Recording();
        var rows = DeviceColumn.rows(input(Kind.PUMP, true, true, DeviceColumn.Valve.NONE, 2, "", "volume"), texts);
        var control = (Column.Group) rows.getFirst();
        assertEquals("Control", control.title());
        var positions = ((Column.Buttons) control.rows().getFirst()).buttons();
        assertEquals(List.of("power:off", "power:on"), positions.stream().map(Column.Button::key).toList());
        assertTrue(positions.get(1).latched() && !positions.get(1).active() && positions.get(0).active(), "On stands, Off acts");
        var reader = ((Column.Buttons) ((Column.Group) DeviceColumn.rows(input(Kind.PUMP, false, true, DeviceColumn.Valve.NONE, 2, "", "volume"), texts).getFirst()).rows().getFirst()).buttons();
        assertTrue(reader.stream().noneMatch(Column.Button::active), "a reader sees the switch and moves nothing");

        var flow = DeviceColumn.rows(input(Kind.VALVE, true, false, DeviceColumn.Valve.MASS_FLOW, 2, "", "volume"), texts);
        var target = (Column.Group) flow.getFirst();
        assertEquals(List.of("valve:mass", "valve:volume"), target.rows().stream().map(r -> ((Column.Choice) r).action()).toList());
        assertTrue(((Column.Choice) target.rows().getFirst()).selected());

        var filter = DeviceColumn.rows(input(Kind.FILTER, true, false, DeviceColumn.Valve.NONE, 2, "", "volume"), texts);
        var recover = ((Column.Buttons) ((Column.Group) filter.getFirst()).rows().getFirst()).buttons().getFirst();
        assertEquals("recover", recover.key());
        assertTrue(recover.active());

        var phases = (Column.Group) DeviceColumn.rows(input(Kind.RESERVOIR, false, false, DeviceColumn.Valve.NONE, 2, "", "mole"), texts).getFirst();
        var basis = ((Column.Buttons) phases.rows().getFirst()).buttons().getFirst();
        assertTrue(basis.soft() && basis.active(), "the basis is a display key, a reader's too");
        assertEquals("Crystals", ((Column.Split) phases.rows().get(1)).shares().get(3).label());
        assertEquals(3, phases.rows().size(), "a mole basis that leaves custom solids out says so");
    }
}
