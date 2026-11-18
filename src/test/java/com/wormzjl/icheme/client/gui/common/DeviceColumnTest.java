package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
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

    /** Every reading the screen shows (DeviceMeters.meters), each once. */
    private static final List<String> METERS = List.of("fans_state", "burners_state", "temperature", "pressure", "top_pressure", "bottom_pressure", "mass",
            "inlet_temperature", "suction_pressure", "inlet_pressure", "net_flow", "throughput", "velocity", "velocity_lowest", "velocity_highest", "gradient",
            "gradient_lowest", "gradient_highest", "filter_load", "captured", "pressure_change", "outlet_temperature", "duty", "ua", "bypass", "fans_speed",
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

    /** A vessel's carry-over meters: the gas's alone (a standing drum), or all three (a lying drum with a boot). */
    private static List<DeviceColumn.Meter> carried(boolean liquids) {
        var gas = new DeviceColumn.Meter("carried_liquid", "26.0", "%", "a hint");
        return liquids ? List.of(new DeviceColumn.Meter("carried_water", "1.8", "%", "a hint"), new DeviceColumn.Meter("carried_oil", "100.0", "%", "a hint"), gas) : List.of(gas);
    }

    /** A vessel's levels of separation (batch 2026-11-17-dynamic-settling): gas, oil and water; gas and oil; the gas's alone. */
    private static List<DeviceColumn.Phase> separation(int phases) {
        var all = List.of(new DeviceColumn.Phase("separation_gas", 1, "100.0", "a hint"), new DeviceColumn.Phase("separation_oil", .55, "55.0", "a hint"),
                new DeviceColumn.Phase("separation_water", .4, "40.0", null));
        return all.subList(0, phases);
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
        // A vessel the separation law acts on (batch 2026-11-17-three-phase-separator): its Carry-over group.
        for (boolean edit : new boolean[]{true, false})
            for (boolean liquids : new boolean[]{true, false})
                out.add(new Case("RESERVOIR carry-over " + (liquids ? "boot" : "gas") + (edit ? "" : " reader"), input(Kind.RESERVOIR, edit, false, DeviceColumn.Valve.NONE, 6, "", "volume").withCarried(carried(liquids))));
        // Its levels of separation (batch 2026-11-17-dynamic-settling): three, two and one phase, with and without Carry-over.
        for (boolean edit : new boolean[]{true, false})
            for (int phases = 1; phases <= 3; phases++)
                for (boolean carry : new boolean[]{true, false}) {
                    var in = input(Kind.RESERVOIR, edit, false, DeviceColumn.Valve.NONE, 6, "", "volume").withSeparation(separation(phases));
                    out.add(new Case("RESERVOIR separation " + phases + (carry ? " carry-over" : "") + (edit ? "" : " reader"), carry ? in.withCarried(carried(phases == 3)) : in));
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

    /**
     * W7 (owner decision D31, option A): a casing's Drive group comes after its set points, with Open driver where it has a
     * driver and NO DRIVER without; a fixed-speed train's set flow says the flow follows the line; a fan unit's Power group
     * comes last. Each fits the door at every width, its words whole and its boxes in line.
     */
    @Test
    void aCasingsDriveAndAFanUnitsPowerFitTheDoor() {
        var texts = new HeatControlColumnTest.Recording();
        var driven = new DeviceColumn.Drive(texts.get("drive_line_poles", texts.get("driver.motor"), "30.0", 2), texts.get("drive_state", "CURRENT LIMITED", "2950"), true, true);
        var none = new DeviceColumn.Drive(texts.get("no_driver"), null, false, false);
        var power = new DeviceColumn.Power("26.7", "534", "72");
        for (var drive : List.of(driven, none))
            for (boolean edit : new boolean[]{true, false}) {
                var base = input(Kind.PUMP, edit, true, DeviceColumn.Valve.NONE, 8, "", "volume");
                var in = new DeviceColumn.Input(base.kind(), edit, true, true, base.pressureUnit(), base.temperatureUnit(), base.flowTime(), base.valve(), null, false, null, null,
                        List.of(), base.phases(), base.readings(), drive, power);
                var rows = DeviceColumn.rows(in, texts);
                int at = -1, phases = -1, readings = -1;
                for (int i = 0; i < rows.size(); i++)
                    if (rows.get(i) instanceof Column.Group g) {
                        if (g.title().equals(texts.get("device_group.drive"))) at = i;
                        if (g.title().equals(texts.get("device_group.phases"))) phases = i;
                        if (g.title().equals(texts.get("device_group.readings"))) readings = i;
                    }
                assertTrue(at >= 0 && at < phases, "the Drive group stands before the plant's texts");
                assertEquals(texts.get("device_group.power"), ((Column.Group) rows.getLast()).title());
                assertTrue(readings < rows.size() - 1);
                for (int width = 300; width <= 790; width += 7) {
                    var column = Column.place(rows, 10, 74, FluidGuiLayout.side(width) - 10, M, Column.Metrics.COMPACT);
                    for (var p : column.rows()) {
                        for (var t : p.texts()) assertTrue(M.width(t) <= p.rect().w(), "a text cut at " + width + ": " + t);
                        for (var s : p.slots().values()) assertTrue(s.inside(p.rect()) && s.w() >= 40, "a box at " + width + ": " + s);
                    }
                    assertEquals(drive.open(), widgets(column).containsKey("open_driver"), "Open driver only with a driver, at " + width);
                    column.slots();
                    assertInLine(column, "casing at " + width);
                }
                if (edit) {
                    var flow = rows.stream().flatMap(r -> r instanceof Column.Group g ? g.rows().stream() : java.util.stream.Stream.of(r))
                            .filter(r -> r instanceof Column.Field f && f.key().equals("volumeFlow")).map(r -> (Column.Field) r).findFirst().orElseThrow();
                    assertEquals(texts.get("device_field.suction_flow" + (drive.fixed() ? ".fixed" : "") + ".hint"), flow.hint());
                }
            }
        for (var key : List.of("head", "head.hint", "speed_share", "shaft_power", "efficiency", "efficiency.hint", "driver.steam_turbine", "driver.creative_motor", "drive_line"))
            texts.get(key, "1", "2");
        assertEquals(Set.of(), texts.missing);
    }

    /**
     * W10d (owner decision D37): a pump's Suction group (NPSH available, NPSH required, gas) and Health group come after its
     * set points and drive and before its Phases, for a casing and an in-line pump, an editor and a reader; meters only, so
     * no widget moves while they read; each fits the door at every width, words whole and boxes in line. A compressor has none.
     */
    @Test
    void aPumpsSuctionAndHealthFitTheDoor() {
        var texts = new HeatControlColumnTest.Recording();
        var driven = new DeviceColumn.Drive(texts.get("drive_line_poles", texts.get("driver.motor"), "30.0", 2), texts.get("drive_state", "RUNNING", "2950"), true, false);
        var readings = new DeviceColumn.Suction("6.8", "3.3", "0.4", "73.0", "87.6");
        var none = new DeviceColumn.Suction("—", "—", "—", "100.0");
        for (var drive : java.util.Arrays.asList(driven, null))
            for (boolean edit : new boolean[]{true, false})
                for (var suction : List.of(readings, none)) {
                    var base = input(Kind.PUMP, edit, drive != null, DeviceColumn.Valve.NONE, drive != null ? 8 : 4, "", "volume");
                    var in = new DeviceColumn.Input(base.kind(), edit, drive != null, true, base.pressureUnit(), base.temperatureUnit(), base.flowTime(), base.valve(), null, false, null, null,
                            List.of(), base.phases(), base.readings(), drive, null, suction);
                    var rows = DeviceColumn.rows(in, texts);
                    int drives = -1, intake = -1, health = -1, phases = -1;
                    for (int i = 0; i < rows.size(); i++)
                        if (rows.get(i) instanceof Column.Group g) {
                            if (g.title().equals(texts.get("device_group.drive"))) drives = i;
                            if (g.title().equals(texts.get("device_group.suction"))) intake = i;
                            if (g.title().equals(texts.get("device_group.health"))) health = i;
                            if (g.title().equals(texts.get("device_group.phases"))) phases = i;
                        }
                    String what = (drive != null ? "casing" : "in-line") + (edit ? "" : " reader");
                    assertTrue(intake > drives && health == intake + 1 && phases == health + 1, what + ": Suction and Health after the drive, before the Phases");
                    var suctionRows = ((Column.Group) rows.get(intake)).rows();
                    assertEquals(List.of("npsh:available", "npsh:required", "npsh:gas"), suctionRows.stream().map(r -> ((Column.Reading) r).key()).toList(), what);
                    assertEquals(List.of("health", "life"), ((Column.Group) rows.get(health)).rows().stream().map(r -> ((Column.Reading) r).key()).toList(), what + ": the health left, then its life in minutes of play");
                    for (int width = 300; width <= 790; width += 7) {
                        var column = Column.place(rows, 10, 74, FluidGuiLayout.side(width) - 10, M, Column.Metrics.COMPACT);
                        for (var p : column.rows()) {
                            assertTrue(p.rect().right() <= column.bounds().right(), what + " a row at " + width);
                            for (var t : p.texts()) assertTrue(M.width(t) <= p.rect().w(), what + " a text cut at " + width + ": " + t);
                            for (var s : p.slots().values()) assertTrue(s.inside(p.rect()) && s.w() >= 40, what + " a box at " + width + ": " + s);
                            if (p.row() instanceof Column.Group g) assertEquals(1, p.texts().size(), what + " a plate in one line at " + width + ": " + g.title());
                            if (p.row() instanceof Column.Reading r) assertTrue(p.texts().contains(r.unit()), what + " a unit whole at " + width + ": " + r.unit());
                        }
                        column.slots();
                        assertInLine(column, what + " at " + width);
                        // The readings move no widget: the same column with other values keeps every slot.
                        var other = Column.place(DeviceColumn.rows(in.withSuction(suction == readings ? none : readings), texts), 10, 74, FluidGuiLayout.side(width) - 10, M, Column.Metrics.COMPACT);
                        assertEquals(widgets(column), widgets(other), what + " at " + width);
                    }
                }
        var compressor = DeviceColumn.rows(input(Kind.COMPRESSOR, true, true, DeviceColumn.Valve.NONE, 4, "", "volume"), texts);
        assertTrue(compressor.stream().noneMatch(r -> r instanceof Column.Group g && (g.title().equals(texts.get("device_group.suction")) || g.title().equals(texts.get("device_group.health")))),
                "a compressor shows no pump readings (D34)");
        // The curve's texts on the glass (PumpCurve).
        for (var key : List.of("pump_curve.title", "pump_curve.head", "pump_curve.npsh3", "pump_curve.inception", "pump_curve.available", "pump_curve.head_axis",
                "pump_curve.npsh_axis", "pump_curve.flow_axis"))
            texts.get(key, "1");
        assertEquals(Set.of(), texts.missing);
    }

    /**
     * WP4 of batch 2026-11-16-fouling-corrosion: a vessel's Integrity group, a pump's beside its Health and a control valve's
     * Trim group fit the door at every width, words whole and boxes in line, and other values move no widget.
     */
    @Test
    void theIntegrityAndTrimGroupsFitTheDoor() {
        var texts = new HeatControlColumnTest.Recording();
        var thin = new DeviceColumn.Wall("28.0", "0.4", "1.5", "Ammonium bisulfide", "Stainless steel");
        var sound = new DeviceColumn.Wall("100.0", "0.0", "—", "—", "Carbon steel");
        var trim = new DeviceColumn.Trim("30.0", "10.0");
        var suction = new DeviceColumn.Suction("6.8", "3.3", "0.4", "73.0", "87.6");
        for (var kind : List.of(Kind.RESERVOIR, Kind.PUMP, Kind.VALVE))
            for (boolean edit : new boolean[]{true, false}) {
                var base = input(kind, edit, false, kind == Kind.VALVE ? DeviceColumn.Valve.PRESSURE : DeviceColumn.Valve.NONE, 4, "", "volume");
                var withPump = kind == Kind.PUMP ? base.withSuction(suction) : base;
                var in = withPump.withIntegrity(kind == Kind.VALVE ? null : thin, kind == Kind.VALVE ? trim : null);
                var rows = DeviceColumn.rows(in, texts);
                String what = kind + (edit ? "" : " reader");
                String title = texts.get(kind == Kind.VALVE ? "device_group.trim" : "device_group.integrity");
                int at = -1, phases = -1;
                for (int i = 0; i < rows.size(); i++)
                    if (rows.get(i) instanceof Column.Group g) {
                        if (g.title().equals(title)) at = i;
                        if (g.title().equals(texts.get("device_group.phases"))) phases = i;
                    }
                assertTrue(at >= 0, what + ": its group");
                assertTrue(phases < 0 || at == phases + 1, what + ": after the Phases' basis key, so its wrapped names move no widget");
                for (int width = 300; width <= 790; width += 7) {
                    var column = Column.place(rows, 10, 74, FluidGuiLayout.side(width) - 10, M, Column.Metrics.COMPACT);
                    for (var p : column.rows()) {
                        assertTrue(p.rect().right() <= column.bounds().right(), what + " a row at " + width);
                        for (var t : p.texts()) assertTrue(M.width(t) <= p.rect().w(), what + " a text cut at " + width + ": " + t);
                        for (var s : p.slots().values()) assertTrue(s.inside(p.rect()) && s.w() >= 40, what + " a box at " + width + ": " + s);
                        if (p.row() instanceof Column.Group g) assertEquals(1, p.texts().size(), what + " a plate in one line at " + width + ": " + g.title());
                        if (p.row() instanceof Column.Reading r) assertTrue(p.texts().contains(r.unit()), what + " a unit whole at " + width + ": " + r.unit());
                    }
                    assertInLine(column, what + " at " + width);
                    var other = Column.place(DeviceColumn.rows(withPump.withIntegrity(kind == Kind.VALVE ? null : sound, kind == Kind.VALVE ? new DeviceColumn.Trim("100.0", "—") : null), texts),
                            10, 74, FluidGuiLayout.side(width) - 10, M, Column.Metrics.COMPACT);
                    assertEquals(widgets(column), widgets(other), what + " at " + width);
                }
            }
        assertEquals(Set.of(), texts.missing);
    }

    /** A fan tower's control keeps its words whole and in line at every width, for an editor and a reader, and a natural tower shows none. */
    @Test
    void aTowersControlFitsTheDoorAtEverySupportedWidth() {
        var texts = new HeatControlColumnTest.Recording();
        assertTrue(DeviceColumn.towerControl(false, false, true, "K", texts).isEmpty());
        for (boolean target : new boolean[]{false, true})
            for (boolean edit : new boolean[]{false, true})
                for (int width = 300; width <= 790; width += 7) {
                    var column = Column.place(DeviceColumn.towerControl(true, target, edit, "K", texts), 10, 74, FluidGuiLayout.side(width) - 10, M, Column.Metrics.COMPACT);
                    for (var p : column.rows()) {
                        assertTrue(p.rect().right() <= column.bounds().right(), "a row at " + width);
                        for (var t : p.texts()) assertTrue(M.width(t) <= p.rect().w(), "a text cut at " + width + ": " + t);
                        for (var slot : p.slots().values()) assertTrue(slot.inside(p.rect()) && slot.w() >= 40, "a box at " + width + ": " + slot);
                    }
                    column.slots();
                    assertInLine(column, "tower at " + width);
                }
        for (var key : List.of("inlet_temperature", "tower_basin_temperature", "bottom_pressure.hint", "tower_cooling_duty", "tower_return", "tower_evaporation", "tower_other_losses", "fan_power", "tower_speed")) texts.get(key);
        assertEquals(Set.of(), texts.missing);
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
                    for (var s : p.slots().values()) assertTrue(s.inside(p.rect()) && s.w() >= 40, c.name() + " a box at " + width + ": " + s);
                    if (p.row() instanceof Column.Group g) assertEquals(1, p.texts().size(), c.name() + " a plate in one line at " + width + ": " + g.title());
                    if (p.row() instanceof Column.Reading r) assertTrue(p.texts().contains(r.unit()), c.name() + " a unit whole at " + width + ": " + r.unit());
                    if (p.row() instanceof Column.Field f) assertTrue(p.texts().contains(f.unit()), c.name() + " a unit whole at " + width + ": " + f.unit());
                    if (p.row() instanceof Column.Gauge g) assertTrue(p.texts().contains(g.unit()), c.name() + " a unit whole at " + width + ": " + g.unit());
                }
                column.slots();
                assertInLine(column, c.name() + " at " + width);
            }
    }

    /**
     * Owner: "Try to align them somehow", "make alignment a rule for GUI creating": in a column, every entry's and meter's
     * box has one place and one width, and every unit after a box fits its one place, so they stand in line.
     */
    static void assertInLine(Column column, String what) {
        var boxes = new java.util.HashSet<List<Integer>>();
        for (var p : column.rows())
            if (p.row() instanceof Column.Field || p.row() instanceof Column.Reading || p.row() instanceof Column.Setting || p.row() instanceof Column.Gauge) {
                var box = p.slots().values().iterator().next();
                boxes.add(List.of(box.x(), box.w()));
                assertTrue(M.width(p.texts().getLast()) <= Column.UNIT, what + ": a unit wider than its place after the box: " + p.texts().getLast());
            }
        assertTrue(boxes.size() <= 1, what + ": boxes out of line " + boxes);
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

    /**
     * Batch 2026-11-17-three-phase-separator, package 4 (owner: "B: own group"): a vessel the separation law acts on has
     * a Carry-over group of its own, right under Readings, one meter an outlet; any other device has none. The group
     * moves no widget, and the other inputs carry it along.
     */
    @Test
    void aVesselTheLawActsOnShowsItsCarryOverUnderReadings() {
        var texts = new HeatControlColumnTest.Recording();
        var plain = input(Kind.RESERVOIR, true, false, DeviceColumn.Valve.NONE, 6, "", "volume");
        for (var row : DeviceColumn.rows(plain, texts)) assertFalse(row instanceof Column.Group g && g.title().equals(texts.get("device_group.carry_over")), "no group without meters");
        for (boolean liquids : new boolean[]{true, false}) {
            var rows = DeviceColumn.rows(plain.withCarried(carried(liquids)), texts);
            int readings = -1, group = -1;
            for (int i = 0; i < rows.size(); i++)
                if (rows.get(i) instanceof Column.Group g) {
                    if (g.title().equals(texts.get("device_group.readings"))) readings = i;
                    if (g.title().equals(texts.get("device_group.carry_over"))) group = i;
                }
            assertTrue(readings >= 0 && group == readings + 1, "the group stands right under Readings: " + readings + ", " + group);
            var carry = (Column.Group) rows.get(group);
            assertEquals(texts.get("carry_over.hint"), carry.hint());
            var ids = carry.rows().stream().map(r -> ((Column.Reading) r).key()).toList();
            assertEquals(liquids ? List.of("carried:carried_water", "carried:carried_oil", "carried:carried_liquid") : List.of("carried:carried_liquid"), ids);
            for (var r : carry.rows()) assertEquals("%", ((Column.Reading) r).unit());
            for (int width : new int[]{300, 464, 790})
                assertEquals(widgets(place(plain, texts, width)), widgets(place(plain.withCarried(carried(liquids)), texts, width)), "no widget moves at " + width);
        }
        var carrying = plain.withCarried(carried(true));
        assertEquals(carrying.carried(), carrying.withFixedDuty("x").carried());
        assertEquals(carrying.carried(), carrying.withIntegrity(null, null).carried());
        assertEquals(carrying.carried(), carrying.withSuction(null).carried());
    }

    /**
     * Batch 2026-11-17-dynamic-settling (owner: "C: bars and section"): a vessel's levels of separation are a Separation
     * group between Readings and Carry-over, one gauge a phase it holds in the phase's colour; none without them. The
     * gauges' bars start and end at one x, and their values and units stand in line with the meters'. The group moves no
     * widget, and the other inputs carry it along.
     */
    @Test
    void aVesselsSeparationStandsBetweenReadingsAndCarryOver() {
        var texts = new HeatControlColumnTest.Recording();
        var plain = input(Kind.RESERVOIR, true, false, DeviceColumn.Valve.NONE, 6, "", "volume");
        for (var row : DeviceColumn.rows(plain.withCarried(carried(true)), texts))
            assertFalse(row instanceof Column.Group g && g.title().equals(texts.get("device_group.separation")), "no group without levels");
        for (int phases = 1; phases <= 3; phases++)
            for (boolean carry : new boolean[]{true, false}) {
                var in = plain.withSeparation(separation(phases));
                if (carry) in = in.withCarried(carried(true));
                var rows = DeviceColumn.rows(in, texts);
                int readings = -1, group = -1, carryOver = -1;
                for (int i = 0; i < rows.size(); i++)
                    if (rows.get(i) instanceof Column.Group g) {
                        if (g.title().equals(texts.get("device_group.readings"))) readings = i;
                        if (g.title().equals(texts.get("device_group.separation"))) group = i;
                        if (g.title().equals(texts.get("device_group.carry_over"))) carryOver = i;
                    }
                assertTrue(readings >= 0 && group == readings + 1, "the group stands right under Readings: " + readings + ", " + group);
                assertEquals(carry ? group + 1 : -1, carryOver, "Carry-over right under it");
                var separation = (Column.Group) rows.get(group);
                assertEquals(texts.get("separation.hint"), separation.hint());
                var gauges = separation.rows().stream().map(r -> (Column.Gauge) r).toList();
                assertEquals(List.of("separation:separation_gas", "separation:separation_oil", "separation:separation_water").subList(0, phases), gauges.stream().map(Column.Gauge::key).toList());
                assertEquals(List.of(DeviceColumn.PHASE_COLOURS[2], DeviceColumn.PHASE_COLOURS[0], DeviceColumn.PHASE_COLOURS[1]).subList(0, phases), gauges.stream().map(Column.Gauge::colour).toList());
                assertEquals(List.of(1.0, .55, .4).subList(0, phases), gauges.stream().map(Column.Gauge::share).toList());
                assertEquals(List.of("100.0", "55.0", "40.0").subList(0, phases), gauges.stream().map(Column.Gauge::value).toList());
                for (var g : gauges) assertEquals("%", g.unit());
                for (int width = 300; width <= 790; width += 7) {
                    var column = place(in, texts, width);
                    var bars = new java.util.HashSet<List<Integer>>();
                    var boxes = new java.util.HashSet<List<Integer>>();
                    for (var p : column.rows()) {
                        if (p.row() instanceof Column.Gauge g) {
                            bars.add(List.of(p.mark().x(), p.mark().right()));
                            assertTrue(p.mark().inside(p.rect()) && p.mark().w() >= Column.GAUGE_MIN, "a bar inside its row at " + width + ": " + p.mark());
                            assertEquals(g.hint(), column.hover(p.rect().x() + 1, p.rect().y() + 1), "its hover at " + width);
                            assertEquals("%", p.texts().getLast(), "the unit in its place at " + width);
                        }
                        // Explicitly: a gauge's box place is a meter's, so its value and unit stand in line with them.
                        if (p.row() instanceof Column.Gauge || p.row() instanceof Column.Reading) {
                            var box = p.slots().values().iterator().next();
                            boxes.add(List.of(box.x(), box.w()));
                        }
                    }
                    assertEquals(1, bars.size(), "the bars start and end at one x at " + width + ": " + bars);
                    assertEquals(1, boxes.size(), "the gauges' values and units in line with the meters' at " + width + ": " + boxes);
                    assertInLine(column, "separation " + phases + " at " + width);
                    var without = carry ? plain.withCarried(carried(true)) : plain;
                    assertEquals(widgets(place(without, texts, width)), widgets(column), "no widget moves at " + width);
                }
            }
        var settling = plain.withSeparation(separation(3)).withCarried(carried(true));
        assertEquals(separation(3), settling.separation());
        assertEquals(settling.separation(), settling.withFixedDuty("x").separation());
        assertEquals(settling.separation(), settling.withIntegrity(null, null).separation());
        assertEquals(settling.separation(), settling.withSuction(null).separation());
        assertEquals(settling.separation(), settling.withCarried(List.of()).separation());
        assertEquals(settling.carried(), settling.withSeparation(List.of()).carried());
        assertThrows(IllegalArgumentException.class, () -> new DeviceColumn.Phase("separation_oil", 1.2, "120.0", null));
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

    /**
     * One scale for every device screen (owner 2026-10-28, option b): a flow valve's page, a pump's and a compressor's, with the four
     * readings each shows, fit the door view of the device screen's design height, so 2560 by 1440 shows them at scale 3.
     */
    @Test
    void aValvesAPumpsAndACompressorsPageFitTheDesignView() {
        var texts = new HeatControlColumnTest.Recording();
        int view = FluidGuiLayout.doorView(FluidGuiLayout.HEIGHT);
        assertEquals(338, view);
        for (var c : List.of(new Case("valve", input(Kind.VALVE, true, false, DeviceColumn.Valve.MASS_FLOW, 4, "Fully open / target limited", "volume")),
                new Case("pump", input(Kind.PUMP, true, true, DeviceColumn.Valve.NONE, 4, "", "volume")),
                new Case("compressor (its ratio, its pressure change on hover)", input(Kind.COMPRESSOR, true, true, DeviceColumn.Valve.NONE, 4, "", "volume"))))
            for (int width : new int[]{464, 790}) {
                int tall = place(c.in(), texts, width).bounds().h();
                assertTrue(tall <= view, c.name() + " at " + width + ": " + tall + " px in a " + view + " px view");
            }
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
        var setPoint = (Column.Group) flow.getFirst();
        assertEquals("Set point", setPoint.title(), "the target is the set point's own, in its group (option b)");
        var targets = setPoint.rows().stream().filter(Column.Choice.class::isInstance).map(Column.Choice.class::cast).toList();
        assertEquals(List.of("valve:mass", "valve:volume"), targets.stream().map(Column.Choice::action).toList());
        assertTrue(targets.getFirst().selected());
        assertInstanceOf(Column.Field.class, setPoint.rows().getLast(), "the value after what it holds");

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

    /** Plan N7: the aqueous liquid reads "Water" while water is at least 99 % of it by moles, else "Aqueous". */
    @Test void theAqueousRowIsNamedWaterWhileItIsAlmostPureWater() {
        assertTrue(DeviceColumn.aqueousIsWater(new double[]{0.01, 0.99}, 1));
        assertFalse(DeviceColumn.aqueousIsWater(new double[]{0.0101, 0.9899}, 1), "an ethanol-water liquid stays aqueous");
        assertFalse(DeviceColumn.aqueousIsWater(new double[]{0, 0}, 1), "an empty liquid is not named");
        assertFalse(DeviceColumn.aqueousIsWater(new double[]{1}, -1), "a basis without water never names it");
        var texts = new HeatControlColumnTest.Recording();
        for (boolean water : new boolean[]{true, false}) {
            var group = (Column.Group) DeviceColumn.phases(new DeviceColumn.Phases("mole", new double[]{1, 2, 3, 0}, false, "Phase fractions.", water), texts);
            assertEquals(water ? "Water" : "Aqueous", ((Column.Split) group.rows().get(1)).shares().get(1).label());
        }
        assertTrue(texts.missing.isEmpty(), () -> "missing keys " + texts.missing);
    }

    /**
     * A pressure safety valve's group (batch 2026-11-11-gas-blanketing): an editor's set pressure entry, a reader's meter,
     * the host's pressure only where no blanketing valve's group shows it, what it relieves and where to; every line in
     * en_us; a drum's page with both valves fits the design view.
     */
    @Test
    void aSafetyValvesGroupHoldsItsSetPressureItsReliefAndItsDischargeAndFitsBesideTheBlanketingValve() {
        var texts = new HeatControlColumnTest.Recording();
        var editor = (Column.Group) DeviceColumn.reliefValve(true, "bar", "4.013", null, "1.111", "kg/s", true, texts).getFirst();
        assertEquals("Safety valve", editor.title());
        assertEquals("relief", ((Column.Field) editor.rows().getFirst()).key());
        assertEquals(List.of("meter:relief_flow", "meter:relief_discharge"), editor.rows().stream().skip(1).map(r -> ((Column.Reading) r).key()).toList(), "no pressure beside a blanketing valve's");
        assertEquals("Air", ((Column.Reading) editor.rows().getLast()).value());
        var reader = (Column.Group) DeviceColumn.reliefValve(false, "bar", "3.000", "2.998", "0.000", "kg/s", false, texts).getFirst();
        assertEquals(List.of("meter:relief_set", "meter:relief_pressure", "meter:relief_flow", "meter:relief_discharge"), reader.rows().stream().map(r -> ((Column.Reading) r).key()).toList());
        assertEquals("Piped", ((Column.Reading) reader.rows().getLast()).value());
        var blanket = DeviceColumn.blanketValve(true, "bar", "1.213", "4.012", texts);
        assertEquals(List.of("Blanketing valve"), blanket.stream().map(r -> ((Column.Group) r).title()).toList(), "one group under the valve's own plate");
        var both = DeviceColumn.accessories(blanket, DeviceColumn.reliefValve(true, "bar", "4.013", null, "1.111", "kg/s", true, texts));
        assertEquals(List.of("Blanketing valve", "", "Safety valve"), both.stream().map(r -> r instanceof Column.Group g ? g.title() : "").toList(), "two groups, a gap between");
        int view = FluidGuiLayout.doorView(FluidGuiLayout.HEIGHT);
        for (int width : new int[]{464, 790})
            assertTrue(Column.place(both, 10, 74, FluidGuiLayout.side(width) - 10, M, Column.Metrics.COMPACT).bounds().h() <= view, "both valves fit the design view at " + width);
        assertEquals(Set.of(), texts.missing);
    }
    /** A valve's sensors as its screen lists them: none, or three linked, the {@code selected}th marked (-1: none). */
    private static List<DeviceColumn.SensorChoice> sensors(int count, int selected) {
        var all = List.of(new DeviceColumn.SensorChoice("FT-0012 / FLOW", "Sensor block: 104, 64, -31", false, "sensor:12"),
                new DeviceColumn.SensorChoice("PT-0007 / PRESSURE", "Sensor block: 98, 66, -31\nEquipment: #0042", false, "sensor:7"),
                new DeviceColumn.SensorChoice("FT-0015 / FLOW", "Sensor block: 112, 64, -28", false, "sensor:15"));
        var out = new ArrayList<DeviceColumn.SensorChoice>();
        for (int i = 0; i < count; i++) {
            var c = all.get(i);
            out.add(new DeviceColumn.SensorChoice(c.label(), c.hint(), i == selected, c.key()));
        }
        return out;
    }

    /** A control valve's page led by its Sensor group, as FluidDeviceScreen builds it: {@code valve} the set point the selection shows. */
    private static DeviceColumn.Input sensorValve(boolean edit, List<DeviceColumn.SensorChoice> choices, DeviceColumn.Valve valve, int readings, HeatControlColumn.Texts t) {
        return new DeviceColumn.Input(Kind.VALVE, edit, false, true, "kPa", "°C", "/min", valve, new DeviceColumn.Opening(choices.stream().anyMatch(DeviceColumn.SensorChoice::selected) ? .42 : 1, ""),
                false, null, null, DeviceColumn.sensors(choices, edit, t), phases("volume", false), meters(readings));
    }

    /**
     * The control valve's Sensor group (batch 2026-11-16-review-plant-sizing, owner's pick A): "No sensor linked" alone where
     * none is; else None and each linked sensor as a mark, the selected one marked, None while none is. An editor's choices
     * act, a reader's do not. It leads the valve's column, before its set point; it fits the door at every width, its words
     * whole and its boxes in line; the readings move no widget under it; and a flow valve's page with three sensors still fits
     * the design view.
     */
    @Test
    void aControlValveLeadsWithItsSensorGroup() {
        var texts = new HeatControlColumnTest.Recording();
        record State(String name, int count, int selected, DeviceColumn.Valve valve) {}
        var states = List.of(new State("no sensor linked", 0, -1, DeviceColumn.Valve.UNCONFIGURED), new State("none selected", 3, -1, DeviceColumn.Valve.UNCONFIGURED),
                new State("flowmeter selected", 3, 0, DeviceColumn.Valve.MASS_FLOW), new State("probe selected", 3, 1, DeviceColumn.Valve.PRESSURE));
        for (var st : states)
            for (boolean edit : new boolean[]{true, false}) {
                String what = st.name() + (edit ? "" : " reader");
                var in = sensorValve(edit, sensors(st.count(), st.selected()), st.valve(), 6, texts);
                var group = (Column.Group) DeviceColumn.rows(in, texts).getFirst();
                assertEquals("Sensor", group.title(), what + ": the Sensor group leads");
                if (st.count() == 0) {
                    assertEquals(1, group.rows().size(), what);
                    assertEquals("No sensor linked", ((Column.Paragraph) group.rows().getFirst()).text(), what);
                } else {
                    var choices = group.rows().stream().map(r -> (Column.Choice) r).toList();
                    assertEquals(List.of("None", "FT-0012 / FLOW", "PT-0007 / PRESSURE", "FT-0015 / FLOW"), choices.stream().map(Column.Choice::title).toList(), what);
                    assertEquals(st.selected() + 1, java.util.stream.IntStream.range(0, choices.size()).filter(k -> choices.get(k).selected()).boxed().toList().getFirst(), what + ": one mark, on the selection");
                    assertEquals(1, choices.stream().filter(Column.Choice::selected).count(), what);
                    assertEquals(edit ? List.of("sensor:none", "sensor:12", "sensor:7", "sensor:15") : java.util.Arrays.asList(null, null, null, null), choices.stream().map(Column.Choice::action).toList(), what + ": an editor's choices act, a reader's do not");
                    assertEquals("Sensor block: 104, 64, -31", choices.get(1).hint(), what + ": where it is, on hover");
                }
                for (int width = 300; width <= 790; width += 7) {
                    var column = place(in, texts, width);
                    for (var p : column.rows()) {
                        for (var t : p.texts()) assertTrue(M.width(t) <= p.rect().w(), what + " a text cut at " + width + ": " + t);
                        for (var slot : p.slots().values()) assertTrue(slot.inside(p.rect()) && slot.w() >= 40, what + " a box at " + width + ": " + slot);
                        if (p.row() instanceof Column.Group g) assertEquals(1, p.texts().size(), what + " a plate in one line at " + width + ": " + g.title());
                    }
                    assertInLine(column, what + " at " + width);
                }
                for (int width : new int[]{300, 464, 790})
                    assertEquals(widgets(place(sensorValve(edit, sensors(st.count(), st.selected()), st.valve(), 2, texts), texts, width)),
                            widgets(place(sensorValve(edit, sensors(st.count(), st.selected()), st.valve(), 14, texts), texts, width)), what + ": the readings move no widget at " + width);
            }
        int view = FluidGuiLayout.doorView(FluidGuiLayout.HEIGHT);
        // The whole page scrolls at the design height with three sensors (377 px at 464); its widgets, the Sensor group and the
        // Set point, stand inside the view without scrolling.
        for (int width : new int[]{464, 790}) {
            var column = place(sensorValve(true, sensors(3, 0), DeviceColumn.Valve.MASS_FLOW, 4, texts), texts, width);
            var setPoint = column.rows().stream().filter(p -> p.row() instanceof Column.Group g && g.title().equals("Set point")).findFirst().orElseThrow();
            assertTrue(setPoint.rect().bottom() - 74 <= view, "a flow valve's Sensor group and Set point with three sensors at " + width + ": " + (setPoint.rect().bottom() - 74) + " px in a " + view + " px view");
        }
        assertEquals(Set.of(), texts.missing);
    }
}
