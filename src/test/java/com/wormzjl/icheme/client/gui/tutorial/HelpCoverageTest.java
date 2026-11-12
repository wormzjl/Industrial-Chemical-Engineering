package com.wormzjl.icheme.client.gui.tutorial;

import com.wormzjl.icheme.client.gui.common.DeviceColumn;
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
 * every anchor of the guide's tour stands on GuidePage; each screen either has a tour or waits in {@link #LATER}.
 */
class HelpCoverageTest {
    /** The screens with a tour, by class, and the tours they host. */
    static final Map<String, Set<String>> TOURED = Map.of("GuideScreen", Set.of("guide"), "FluidDeviceScreen", Set.of("fluid_device.still"));
    /** The screens with no tour yet; phase P6 empties this list (and FluidDeviceScreen's other pages). */
    static final Set<String> LATER = Set.of("AssemblyScreen", "ControlPanelScreen", "EquipmentConstructorScreen", "FilmScreen", "GaugeScreen", "MachineScreen",
            "PipelineToolScreen", "ProcessSimulatorScreen", "VesselScreen");
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
        var tour = TourFile.load().get("fluid_device.still");
        var m = GuidePageTest.M;
        for (int width = 300; width <= 790; width += 7) {
            int door = FluidGuiLayout.side(width) - 10;
            var placed = new ArrayList<Map<String, ?>>();
            var inputs = stills();
            for (var in : inputs) placed.add(Column.place(DeviceColumn.rows(in, TEXTS), 10, FluidGuiLayout.DOOR_TOP, door, m, Column.Metrics.COMPACT).slots());
            for (var stop : tour.stops()) {
                if (FRAME.contains(stop.anchor())) continue;
                boolean found = false;
                for (int i = 0; i < inputs.size() && !found; i++) {
                    var slot = slot(stop.anchor(), inputs.get(i));
                    found = slot != null && placed.get(i).containsKey(slot);
                }
                assertTrue(found, width + " px: " + stop.anchor() + " on the still's page");
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
    void everyScreenHasATourOrWaitsForOne() throws Exception {
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
            assertTrue(TOURED.containsKey(screen) ^ LATER.contains(screen), screen + ": in exactly one of TOURED and LATER");
        for (var name : LATER) assertTrue(screens.contains(name), name + " is a screen");
    }
}
