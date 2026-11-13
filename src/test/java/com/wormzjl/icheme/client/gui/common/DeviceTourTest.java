package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.screens.inventory.FluidDeviceScreen.FluidGuiLayout;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.tutorial.guide.TourFile;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The fluid device screen's help tours (batch 2026-11-12-tutorial-system, P6, {@link DeviceTour}): every tab the screen
 * shows has a tour, and the Overview one for each door layout; every stop of every tour stands on its page, at every width
 * the screen takes (300 to 790 in steps of 7) and at the smallest and a tall window, on at least one device that shows the
 * page (as an editor or a reader, with each optional row present and absent), and its first stop, which says what the page
 * is for, on every such device: a frame place ({@link DeviceTour#frame}), the title plate, or a door row's widget or group
 * ({@link TourAnchors#find}) as the screen places the column.
 */
public class DeviceTourTest {
    private static final Measure M = Measure.fixed(6);
    private static final HeatControlColumnTest.Recording T = new HeatControlColumnTest.Recording();
    /** Image heights: the smallest window's (240 GUI px) and a tall one's. */
    private static final int[] HEIGHTS = {FluidGuiLayout.imageHeight(240), FluidGuiLayout.imageHeight(480)};

    /** A device as one page shows it: the tab, the tabs shown, whether Apply and Copy stand in the footer, the Control tab's legend, and its door columns at an image width. */
    public record Sample(String name, String tab, DeviceTour.Layout layout, List<String> tabs, boolean apply, boolean copy, boolean legend, IntFunction<List<Column>> door) {
        public String page() {
            return DeviceTour.page(tab, layout);
        }
    }

    private static List<Column> device(List<Column.Row> rows, int width) {
        return List.of(Column.place(rows, 10, FluidGuiLayout.DOOR_TOP, FluidGuiLayout.side(width) - 10, M, Column.Metrics.COMPACT));
    }

    private static List<DeviceColumn.Meter> meters(String... keys) {
        var out = new ArrayList<DeviceColumn.Meter>();
        for (var key : keys) out.add(new DeviceColumn.Meter(key, "1.0", "bar", null));
        return out;
    }

    private static DeviceColumn.Phases phases() {
        return new DeviceColumn.Phases("volume", new double[]{0.4, 0.3, 0.3, 0}, false, "phases");
    }

    /** A device's input: its kind, editor or reader, switch, valve, opening, solids, level, junction, lead, phases, meters, drive, suction. */
    private static DeviceColumn.Input input(Kind kind, boolean edit, boolean switchable, DeviceColumn.Valve valve, DeviceColumn.Opening opening, boolean recoverable,
                                            DeviceColumn.Level level, String junction, List<Column.Row> lead, DeviceColumn.Phases phases, List<DeviceColumn.Meter> meters,
                                            DeviceColumn.Drive drive, DeviceColumn.Suction suction) {
        return new DeviceColumn.Input(kind, edit, switchable, true, "bar", "°C", "/h", valve, opening, recoverable, level, junction, lead, phases, meters, drive, null, suction);
    }

    private static DeviceColumn.Input plain(Kind kind, boolean edit, boolean switchable) {
        return input(kind, edit, switchable, DeviceColumn.Valve.NONE, null, false, null, null, List.of(), phases(), meters("temperature", "pressure", "throughput"), null, null);
    }

    private static Sample overview(String name, DeviceTour.Layout layout, List<String> tabs, boolean edit, boolean copy, DeviceColumn.Input in) {
        var rows = DeviceColumn.rows(in, T);
        return new Sample(name, "overview", layout, tabs, edit, copy, false, w -> device(rows, w));
    }

    private static ConverterDoor.Input converter(boolean edit, ConverterDoor.Mode mode, boolean rule) {
        var tank = new ConverterDoor.Tank(0.5, "50", "500 L");
        return new ConverterDoor.Input(edit, mode, "L/h", "1000", "500", "10", List.of("—", "—", "—", "—"), tank, rule ? "Water" : null, tank, rule ? "icheme:water" : null, false,
                List.of(), "0", "none");
    }

    /** Every page the screen shows, on the devices that show it. */
    public static List<Sample> samples() {
        var out = new ArrayList<Sample>();
        var overview = List.of("overview");
        for (boolean edit : new boolean[]{true, false}) {
            String who = edit ? "" : " reader";
            for (var mode : ConverterDoor.Mode.values())
                out.add(overview("converter " + mode + who, DeviceTour.Layout.CONVERTER, overview, edit, false, input(Kind.RESERVOIR, edit, false, DeviceColumn.Valve.NONE, null, false, null, null,
                        ConverterDoor.rows(converter(edit, mode, mode != ConverterDoor.Mode.OFF), T), phases(), meters("temperature"), null, null)));
            for (boolean fans : new boolean[]{true, false})
                for (boolean target : new boolean[]{true, false})
                    out.add(overview("tower" + (fans ? " fans" : " natural") + (target ? " target" : "") + who, DeviceTour.Layout.TOWER, overview, edit, false,
                            input(Kind.RESERVOIR, edit, false, DeviceColumn.Valve.NONE, null, false, null, null, DeviceColumn.towerControl(fans, target, edit, "°C", T), null,
                                    meters("inlet_temperature", "tower_basin_temperature", "tower_cooling_duty", "tower_return", "tower_evaporation"), null, null)));
            for (var c : HeatControlColumnTest.cases()) {
                if (c.in().canEdit() != edit) continue;
                out.add(overview("heat " + c.name(), DeviceTour.Layout.HEAT, List.of("overview", "control"), edit, false, input(Kind.HEAT_SIDE, edit, false, DeviceColumn.Valve.NONE, null, false,
                        null, null, HeatControlColumn.sideSelector(c.in(), T), phases(), meters("inlet_temperature", "inlet_pressure", "throughput"), null, null)));
                out.add(new Sample("control " + c.name(), "control", DeviceTour.Layout.HEAT, List.of("overview", "control"), edit, false, c.name().contains("air"),
                        w -> HeatControlColumnTest.place(c.in(), T, FluidGuiLayout.control(w))));
            }
            // An exchanger side with its lines checked: Check lines stands under what acts.
            var lined = new HeatControlColumn.Input(HeatControlColumnTest.heat("EXCHANGER", "TUBE", true, "bypass", 0.2, new com.wormzjl.icheme.runtime.fluid.FluidView.Lines("CHECKED", 1, List.of()), List.of()),
                    new com.wormzjl.icheme.runtime.fluid.HeatUnit.Control(com.wormzjl.icheme.runtime.fluid.HeatUnit.Mode.OUTLET_TEMPERATURE, 330, 0, 0), edit, true,
                    com.wormzjl.icheme.runtime.fluid.HeatUnit.Mode.OUTLET_TEMPERATURE, true, "C");
            out.add(new Sample("control lines" + who, "control", DeviceTour.Layout.HEAT, List.of("overview", "control"), edit, false, true, w -> HeatControlColumnTest.place(lined, T, FluidGuiLayout.control(w))));
            var drive = new DeviceColumn.Drive("MOTOR 30 kW", "RUNNING · 2950 rpm", true, false);
            var suction = new DeviceColumn.Suction("6.0", "2.0", "0", "100", "—");
            out.add(overview("pump curve" + who, DeviceTour.Layout.PUMP_CURVE, List.of("overview", "datasheet"), edit, false, input(Kind.PUMP, edit, true, DeviceColumn.Valve.NONE, null, false,
                    null, null, List.of(), phases(), meters("suction_pressure", "throughput", "head"), drive, suction)));
            out.add(overview("pump no driver" + who, DeviceTour.Layout.PUMP_CURVE, List.of("overview", "datasheet"), edit, false, input(Kind.PUMP, edit, true, DeviceColumn.Valve.NONE, null, false,
                    null, null, List.of(), phases(), meters("suction_pressure", "throughput"), new DeviceColumn.Drive("NO DRIVER", null, false, false), suction)));
            for (var kind : List.of(Kind.PUMP, Kind.COMPRESSOR))
                for (boolean switchable : new boolean[]{true, false}) {
                    out.add(overview(kind + (switchable ? " built" : " in-line") + who, DeviceTour.Layout.MOVER, overview, edit, false, plain(kind, edit, switchable)));
                    out.add(overview(kind + " fixed" + who, DeviceTour.Layout.MOVER, overview, edit, false, plain(kind, edit, false).withFixedDuty("10 m³/h")));
                }
            for (var valve : List.of(DeviceColumn.Valve.MASS_FLOW, DeviceColumn.Valve.VOLUME_FLOW, DeviceColumn.Valve.PRESSURE, DeviceColumn.Valve.UNCONFIGURED))
                out.add(overview("valve " + valve + who, DeviceTour.Layout.VALVE, overview, edit, false, input(Kind.VALVE, edit, false, valve, new DeviceColumn.Opening(0.4, "Holding flow"),
                        false, null, null, List.of(), phases(), meters("temperature", "inlet_pressure", "throughput", "pressure_change"), null, null)));
            for (boolean junction : new boolean[]{true, false}) {
                var tabs = junction ? List.of("overview", "connections") : overview;
                var pipe = input(Kind.PIPE, edit, false, DeviceColumn.Valve.NONE, null, false, null, junction ? "Junction · 3 connections" : null, List.of(), phases(),
                        meters("temperature", "pressure", "throughput", "velocity", "gradient"), null, null);
                out.add(overview("pipe" + (junction ? " junction" : "") + who, DeviceTour.Layout.PIPE, tabs, edit, false, pipe));
                if (junction) out.add(new Sample("pipe connections" + who, "connections", DeviceTour.Layout.PIPE, tabs, edit, false, false, w -> device(DeviceColumn.rows(pipe, T), w)));
            }
            var level = new DeviceColumn.Level(0.6, "300", "500");
            var tank = input(Kind.RESERVOIR, edit, false, DeviceColumn.Valve.NONE, null, false, level, null, List.of(), phases(), meters("temperature", "top_pressure", "mass"), null, null);
            var tankTabs = List.of("overview", "outlets", "datasheet", "accessory");
            out.add(overview("tank" + who, DeviceTour.Layout.VESSEL, tankTabs, edit, true, tank));
            for (var tab : List.of("outlets", "datasheet"))
                out.add(new Sample("tank " + tab + who, tab, DeviceTour.Layout.VESSEL, tankTabs, edit, true, false, w -> device(DeviceColumn.rows(tank, T), w)));
            var generator = plain(Kind.GENERATOR, edit, true);
            var feedTabs = edit ? List.of("overview", "composition", "solids") : overview;
            out.add(overview("feed" + who, DeviceTour.Layout.VESSEL, feedTabs, edit, false, generator));
            if (edit) for (var tab : List.of("composition", "solids"))
                out.add(new Sample("feed " + tab, tab, DeviceTour.Layout.VESSEL, feedTabs, true, false, false, w -> device(DeviceColumn.rows(generator, T), w)));
            out.add(overview("filter" + who, DeviceTour.Layout.VESSEL, overview, edit, false, input(Kind.FILTER, edit, false, DeviceColumn.Valve.NONE, null, true, null, null, List.of(), phases(),
                    meters("throughput", "filter_load", "captured"), null, null)));
            out.add(overview("void" + who, DeviceTour.Layout.VESSEL, overview, edit, false, plain(Kind.VOID, edit, false)));
            // The drum's valves (DeviceColumn.accessories): either or both; a pipe's safety valve, the screen's one tab.
            var blanket = DeviceColumn.blanketValve(edit, "bar", "1.1", "1.0", T);
            for (var both : List.of(List.of(true, false), List.of(false, true), List.of(true, true))) {
                var relief = both.get(1) ? DeviceColumn.reliefValve(edit, "bar", "5.0", both.get(0) ? null : "1.0", "0", "kg/h", true, T) : List.<Column.Row>of();
                var rows = DeviceColumn.accessories(both.get(0) ? blanket : List.of(), relief);
                out.add(new Sample("accessory " + both + who, "accessory", DeviceTour.Layout.VESSEL, tankTabs, edit, true, false, w -> device(rows, w)));
            }
            var psv = DeviceColumn.accessories(List.of(), DeviceColumn.reliefValve(edit, "bar", "5.0", "1.0", "0", "kg/h", false, T));
            out.add(new Sample("pipe safety valve" + who, "safety_valve", DeviceTour.Layout.PIPE, List.of("safety_valve"), edit, false, false, w -> device(psv, w)));
        }
        // The still's two layouts have their own gate (HelpCoverageTest): here only their names.
        return out;
    }

    /** Where {@code anchor} stands on {@code s}'s page at image width {@code w} and height {@code h}, or null. */
    public static Rect at(Sample s, String anchor, int w, int h) {
        var frame = DeviceTour.frame(w, h, s.tabs(), s.tab(), s.legend(), s.apply(), s.copy(), false);
        if (frame.containsKey(anchor)) return frame.get(anchor);
        if (anchor.equals("title")) return new Rect(6, 6, 40, 16);
        return TourAnchors.find(anchor, s.door().apply(w), k -> T.get(k));
    }

    @Test
    void everyTabAndEveryOverviewLayoutHasItsTour() throws Exception {
        var tours = TourFile.load();
        for (var tab : DeviceTour.TABS)
            for (var layout : DeviceTour.Layout.values()) assertNotNull(tours.get(DeviceTour.page(tab, layout)), tab + " on " + layout);
        // Every tab the screen builds is one DeviceTour knows.
        var source = Files.readString(Path.of("src/main/java/com/wormzjl/icheme/client/gui/screens/inventory/FluidDeviceScreen.java"));
        var matcher = Pattern.compile("tabs\\.add\\(\"([a-z_]+)\"\\)").matcher(source);
        int found = 0;
        while (matcher.find()) {
            found++;
            assertTrue(DeviceTour.TABS.contains(matcher.group(1)), matcher.group(1) + " is in DeviceTour.TABS");
        }
        assertTrue(found >= DeviceTour.TABS.size(), "the screen's tabs are read: " + found);
    }

    @Test
    void everyStopStandsOnItsPageAndTheFirstOnEveryDevice() {
        var tours = TourFile.load();
        var byPage = new HashMap<String, List<Sample>>();
        for (var s : samples()) byPage.computeIfAbsent(s.page(), k -> new ArrayList<>()).add(s);
        for (var tab : DeviceTour.TABS)
            for (var layout : DeviceTour.Layout.values()) {
                String page = DeviceTour.page(tab, layout);
                if (layout == DeviceTour.Layout.STILL || layout == DeviceTour.Layout.STILL_COLUMN) continue;
                assertTrue(byPage.containsKey(page), page + ": a device shows it");
            }
        for (var e : byPage.entrySet()) {
            var tour = tours.get(e.getKey());
            assertNotNull(tour, e.getKey());
            for (int w = 300; w <= 790; w += 7)
                for (int h : HEIGHTS) {
                    var found = new boolean[tour.stops().size()];
                    for (var s : e.getValue())
                        for (int i = 0; i < found.length; i++) {
                            var r = at(s, tour.stops().get(i).anchor(), w, h);
                            if (r != null) {
                                found[i] = true;
                                assertTrue(r.x() >= 0 && r.y() >= 0 && r.right() <= w && r.w() > 0 && r.h() > 0, w + " by " + h + ", " + s.name() + ": " + tour.stops().get(i).anchor() + " inside the screen: " + r);
                            } else if (i == 0) fail(w + " by " + h + ", " + s.name() + ": the first stop " + tour.stops().get(0).anchor() + " stands on every device");
                        }
                    for (int i = 0; i < found.length; i++) assertTrue(found[i], w + " by " + h + ", " + e.getKey() + ": " + tour.stops().get(i).anchor() + " on some device");
                }
        }
    }

    /** A stop whose row the device lacks is skipped: a natural-draft tower has no fan keys, a reader no entries. */
    @Test
    void aStopWhoseRowIsAbsentIsSkipped() {
        var natural = samples().stream().filter(s -> s.name().equals("tower natural reader")).findFirst().orElseThrow();
        assertNull(at(natural, "tower:manual", 464, 238));
        assertNull(at(natural, "towerSpeed", 464, 238));
        assertNotNull(at(natural, "status", 464, 238));
        Map<String, Rect> frame = DeviceTour.frame(464, 238, List.of("overview"), "overview", false, false, false, false);
        assertFalse(frame.containsKey("apply"), "a reader's footer has no Apply");
    }
}
