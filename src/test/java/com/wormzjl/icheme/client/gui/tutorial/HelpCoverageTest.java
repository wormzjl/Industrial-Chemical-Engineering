package com.wormzjl.icheme.client.gui.tutorial;

import com.wormzjl.icheme.client.gui.common.DeviceColumn;
import com.wormzjl.icheme.client.gui.common.DeviceTour;
import com.wormzjl.icheme.client.gui.common.ScreenTours;
import com.wormzjl.icheme.client.gui.common.HeatControlColumn;
import com.wormzjl.icheme.client.gui.common.StillDoor;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.screens.inventory.FluidDeviceScreen.FluidGuiLayout;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.tutorial.guide.Tour;
import com.wormzjl.icheme.science.tutorial.guide.TourFile;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The help tours' coverage (batch 2026-11-12-tutorial-system, P1): every tour has at most 7 stops; every anchor of the
 * still's tour stands in the rows StillDoor and DeviceColumn build, for at least one still, at every width the door takes;
 * every anchor of the guide's tour stands on GuidePage; each screen either has a tour or is {@link #EXEMPT} (P6). P4b: the
 * still with a column has its own tour (its reflux valve), whose anchors stand on such a still's page; the simulator's four
 * tabs have theirs (their anchors: SimulatorTourTest). P5 stage B: the column's assembly screen has one for each tab (their
 * anchors: AssemblyTourTest).
 */
class HelpCoverageTest {
    /**
     * The screens with a tour, by class, and the tours they host (P6: every page of every screen). The fluid device screen
     * hosts one for each tab and one for each Overview door layout (DeviceTour; their anchors: DeviceTourTest); the dialogs,
     * the machine page, the control panel and the constructor theirs (ScreenTours; their anchors: ScreenToursTest).
     */
    static final Map<String, Set<String>> TOURED = Map.ofEntries(Map.entry("GuideScreen", Set.of("guide")), Map.entry("FluidDeviceScreen", devicePages()),
            Map.entry("ProcessSimulatorScreen", Set.of("simulator.sheet", "simulator.results", "simulator.sizing", "simulator.basis")),
            Map.entry("AssemblyScreen", Set.of("assembly.structure", "assembly.operation")), Map.entry("GaugeScreen", Set.of(ScreenTours.GAUGE)),
            Map.entry("PipelineToolScreen", Set.of(ScreenTours.PIPELINE_TOOL)), Map.entry("VesselScreen", Set.of(ScreenTours.VESSEL)),
            Map.entry("MachineScreen", Set.of(ScreenTours.MACHINE)),
            Map.entry("ControlPanelScreen", ScreenTours.CONTROL_PAGES.stream().map(ScreenTours::control).collect(java.util.stream.Collectors.toSet())),
            Map.entry("EquipmentConstructorScreen", java.util.Arrays.stream(ScreenTours.Constructor.values()).map(ScreenTours.Constructor::page).collect(java.util.stream.Collectors.toSet())));
    /** The screens that need no tour: a film is itself the help, and its three keys (Back, Next, Close) are the film player's standard ones. */
    static final Set<String> EXEMPT = Set.of("FilmScreen");

    /** The fluid device screen's tours: each tab's, and the Overview's for each door layout. */
    private static Set<String> devicePages() {
        var out = new TreeSet<String>();
        for (var tab : DeviceTour.TABS) for (var layout : DeviceTour.Layout.values()) out.add(DeviceTour.page(tab, layout));
        return out;
    }
    /** Anchors a page resolves itself, not from its builder's rows. */
    static final Set<String> FRAME = Set.of("status", "title");

    static final HeatControlColumn.Texts TEXTS = (key, args) -> {
        String line = GuidePageTest.english().apply("gui.icheme.fluid." + key);
        return args.length == 0 ? line : String.format(Locale.ROOT, line, args);
    };

    @Test
    void everyTourHasAtMostSevenStopsAndItsLines() {
        var tours = TourFile.load();
        assertEquals(TOURED.values().stream().flatMap(Set::stream).collect(java.util.stream.Collectors.toSet()), tours.keySet(), "every tour has its screen");
        for (var tour : tours.values()) {
            assertTrue(tour.stops().size() <= Tour.MAX_STOPS, tour.page());
            for (int i = 0; i < tour.stops().size(); i++) assertNotNull(GuidePageTest.english().apply(tour.key(i)));
        }
    }

    /** The stills the page can show: each heater, with and without a column, a condenser or not. */
    static List<DeviceColumn.Input> stills() {
        var out = new ArrayList<DeviceColumn.Input>();
        for (var heater : StillDoor.Heater.values())
            for (boolean column : new boolean[]{false, true})
                for (boolean condenser : new boolean[]{false, true}) {
                    var fuel = heater == StillDoor.Heater.SOLID ? new StillDoor.Fuel("Coal", "12", "1630", "27.7") : null;
                    var door = StillDoor.rows(new StillDoor.Input(heater, true, true, column, true, null, null, fuel), TEXTS);
                    // The readings as FluidDeviceScreen.stillMeters lists them.
                    var meters = new ArrayList<DeviceColumn.Meter>(List.of(new DeviceColumn.Meter("still_pot_temperature", "118.4", "°C", null),
                            new DeviceColumn.Meter("still_head_temperature", "86.2", "°C", null), new DeviceColumn.Meter("still_level", "0.52", "m", null),
                            new DeviceColumn.Meter("still_heat", "20.2", "kW", null)));
                    if (condenser) for (var key : List.of("still_distillate", "still_vent", "still_condensed")) meters.add(new DeviceColumn.Meter(key, "1.0", "kg/h", null));
                    if (column && condenser) meters.add(new DeviceColumn.Meter("still_reflux_run", "2.0", "", null));
                    out.add(new DeviceColumn.Input(Kind.RESERVOIR, true, false, true, "bar", "°C", "/h", DeviceColumn.Valve.NONE, null, false,
                            new DeviceColumn.Level(0.61, "305.0", "500.0"), null, door, null, meters, null, null));
                }
        return out;
    }

    /** The slot an anchor names in the device column ({@code reading:<key>} by the meter's index), or null. */
    static String slot(String anchor, DeviceColumn.Input in) {
        if (!anchor.startsWith("reading:")) return anchor;
        for (int i = 0; i < in.readings().size(); i++) if (in.readings().get(i).key().equals(anchor.substring(8))) return "meter:" + i;
        return null;
    }

    @Test
    void everyAnchorOfTheStillsTourStandsOnItsPageAtEveryWidth() {
        stillTourStands("fluid_device.still", stills());
    }

    /** The column's tour, which FluidDeviceScreen shows on a still with a column and its condenser: on such a still's page. */
    @Test
    void everyAnchorOfTheColumnStillsTourStandsOnSuchAStillsPage() {
        var columns = stills().stream().filter(in -> in.readings().stream().anyMatch(r -> r.key().equals("still_reflux_run"))).toList();
        assertFalse(columns.isEmpty());
        stillTourStands("fluid_device.still_column", columns);
        assertTrue(TourFile.load().get("fluid_device.still_column").stops().stream().anyMatch(s -> s.anchor().startsWith("still_reflux:")), "a stop on the reflux valve");
    }

    private static void stillTourStands(String name, List<DeviceColumn.Input> inputs) {
        var tour = TourFile.load().get(name);
        var m = GuidePageTest.M;
        for (int width = 300; width <= 790; width += 7) {
            int door = FluidGuiLayout.side(width) - 10;
            var placed = new ArrayList<Map<String, ?>>();
            for (var in : inputs) placed.add(Column.place(DeviceColumn.rows(in, TEXTS), 10, FluidGuiLayout.DOOR_TOP, door, m, Column.Metrics.COMPACT).slots());
            for (var stop : tour.stops()) {
                if (FRAME.contains(stop.anchor())) continue;
                boolean found = false;
                for (int i = 0; i < inputs.size() && !found; i++) {
                    var slot = slot(stop.anchor(), inputs.get(i));
                    found = slot != null && placed.get(i).containsKey(slot);
                }
                assertTrue(found, width + " px: " + stop.anchor() + " on the still's page (" + name + ")");
            }
        }
    }

    @Test
    void theFuelStopIsSkippedOnAStillWithNoFuelSlot() {
        var liquid = stills().stream().filter(in -> in.lead().stream().noneMatch(r -> r instanceof Column.Group g && g.rows().stream()
                .anyMatch(x -> x instanceof Column.Buttons b && b.buttons().stream().anyMatch(k -> k.key().equals("still_fuel:load"))))).findFirst();
        assertTrue(liquid.isPresent(), "a still with no fuel slot has no Load key: its stop is skipped");
    }

    @Test
    void everyAnchorOfTheGuidesTourStandsOnTheGuidePage() {
        var tour = TourFile.load().get("guide");
        for (var size : GuidePageTest.SIZES)
            for (var progress : GuidePageTest.states()) {
                var anchors = GuidePageTest.page(progress, size[0], size[1], null).anchors();
                for (var stop : tour.stops()) assertTrue(anchors.containsKey(stop.anchor()), size[0] + ": " + stop.anchor());
            }
    }

    @Test
    void everyScreenHasATourOrIsExempt() throws Exception {
        var screens = new TreeSet<String>();
        var pattern = Pattern.compile("class ([A-Za-z0-9]+) extends [A-Za-z0-9.]*Screen\\b");
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/com/wormzjl/icheme"))) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                var matcher = pattern.matcher(Files.readString(file));
                while (matcher.find()) screens.add(matcher.group(1));
            }
        }
        assertTrue(screens.size() >= 10, "the screens read: " + screens);
        for (var screen : screens)
            assertTrue(TOURED.containsKey(screen) ^ EXEMPT.contains(screen), screen + ": in exactly one of TOURED and EXEMPT");
        for (var name : EXEMPT) assertTrue(screens.contains(name), name + " is a screen");
        for (var name : TOURED.keySet()) assertTrue(screens.contains(name), name + " is a screen");
    }
}
