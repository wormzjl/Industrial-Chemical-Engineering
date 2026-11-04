package com.wormzjl.icheme.client.gui.common;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.client.gui.screens.inventory.FluidDeviceScreen.FluidGuiLayout;
import com.wormzjl.icheme.runtime.fluid.MachineView;
import com.wormzjl.icheme.runtime.fluid.ShaftUnit;
import com.wormzjl.icheme.science.machine.ShaftNetwork;
import com.wormzjl.icheme.science.machine.ShaftTrain;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A machine's own page (batch 2026-11-03-rotating-machinery, W7, owner decision D31: option A), copied from
 * DeviceColumnTest: every text the builder names has its English line; the column fits the door at every width it takes,
 * its boxes and units in line; its widgets keep their slots while the readings change; one widget a key; an editor sets
 * what its kind sets and a reader nothing; the published view maps to the page as the plan words it.
 */
class MachineColumnTest {
    private static final Measure M = Measure.fixed(6);

    private static Column place(MachineColumn.Input in, HeatControlColumn.Texts t, int width) {
        return Column.place(MachineColumn.rows(in, t), 10, FluidGuiLayout.DOOR_TOP, FluidGuiLayout.side(width) - 10, M, Column.Metrics.COMPACT);
    }

    private static MachineView view(ShaftNetwork.Kind kind, double setPoint, ShaftTrain.Trip trip) {
        return new MachineView(7, kind, trip == ShaftTrain.Trip.NONE ? "RUNNING" : ShaftTrain.tripped(trip), 2950, 24900, kind == ShaftNetwork.Kind.MOTOR ? 26700 : 0,
                kind == ShaftNetwork.Kind.MOTOR ? 534 : 0, new ShaftUnit.Settings(true, ShaftTrain.Mode.SPEED, setPoint, 0, 0), trip, 30000, 2950, 2, true,
                kind == ShaftNetwork.Kind.MOTOR || kind == ShaftNetwork.Kind.GENERATOR ? .72 : Double.NaN, kind == ShaftNetwork.Kind.MOTOR ? 370 : Double.NaN,
                kind == ShaftNetwork.Kind.STEAM_TURBINE ? "FV-101" : "", kind == ShaftNetwork.Kind.GEARBOX ? 4.27 : 0,
                kind == ShaftNetwork.Kind.STEAM_TURBINE ? 2.9 : Double.NaN, kind == ShaftNetwork.Kind.STEAM_TURBINE ? 40e5 : Double.NaN, kind == ShaftNetwork.Kind.STEAM_TURBINE ? 4e5 : Double.NaN);
    }

    @Test
    void everyTextThePageNamesHasItsEnglishLine() {
        var texts = new HeatControlColumnTest.Recording("gui.icheme.");
        for (var c : MachineColumn.cases(texts)) MachineColumn.rows(c.in(), texts);
        for (var kind : ShaftNetwork.Kind.values())
            if (MachineColumn.Kind.of(kind) != null) {
                MachineColumn.rows(MachineColumn.input(view(kind, 0, ShaftTrain.Trip.NONE), true, true, MachineColumn.Mode.SPEED, texts), texts);
                texts.get(MachineColumn.KEY + "kind." + kind.name().toLowerCase(java.util.Locale.ROOT));
            }
        MachineColumn.duration(7300, texts);
        MachineColumn.duration(40, texts);
        // The keys MachineScreen reads itself.
        for (var key : List.of("waiting_first", "waiting", "invalid", "nameplate", "rating", "poles", "rated_speed", "steam_flow", "inlet", "exhaust")) texts.get(MachineColumn.KEY + key);
        texts.get(MachineColumn.KEY + "refused", "x");
        assertEquals(Set.of(), texts.missing);
        assertTrue(texts.asked.size() > 60, "the cases ask for the page's texts: " + texts.asked.size());
    }

    /** Every plate is one line at every width; nothing is cut; every widget lies inside its row, one a key; boxes and units in line. */
    @Test
    void thePageFitsTheDoorAtEverySupportedWidth() {
        var texts = new HeatControlColumnTest.Recording("gui.icheme.");
        for (var c : MachineColumn.cases(texts))
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

    private static Map<String, Rect> widgets(Column column) {
        var out = new LinkedHashMap<String, Rect>();
        for (var p : column.rows())
            if (p.row() instanceof Column.Field || p.row() instanceof Column.Buttons) out.putAll(p.slots());
        return out;
    }

    /** What the plant reads (speeds, powers, the store and its time, the governor's words) moves no widget. */
    @Test
    void aWidgetKeepsItsSlotWhileTheReadingsChange() {
        var texts = new HeatControlColumnTest.Recording("gui.icheme.");
        for (var c : MachineColumn.cases(texts))
            for (int width : new int[]{300, 464, 790}) {
                var in = c.in();
                var before = widgets(place(in, texts, width));
                var meters = new ArrayList<MachineColumn.Meter>();
                for (var m : in.supply()) meters.add(new MachineColumn.Meter(m.key(), "88888", m.unit(), m.hint()));
                var changed = new MachineColumn.Input(in.kind(), in.canEdit(), in.running(), in.variable(), in.mode(), in.tripped(),
                        in.governor() == null ? null : "GOVERNED by a valve with a much longer name than usual", in.shaft(), meters, in.lasts() == null ? null : "12 h 59 min");
                assertEquals(before, widgets(place(changed, texts, width)), c.name() + " at " + width);
            }
    }

    /** What an editor sets on each kind, by its fields' keys; a reader sets nothing and its switch acts nowhere. */
    @Test
    void eachKindHasItsSetPointsAndAReaderNone() {
        var texts = new HeatControlColumnTest.Recording("gui.icheme.");
        var fields = Map.of(MachineColumn.Kind.MOTOR, List.of("speed"), MachineColumn.Kind.STEAM_TURBINE, List.of("speed"), MachineColumn.Kind.GENERATOR, List.of("powerLimit"),
                MachineColumn.Kind.GEARBOX, List.of("ratio"), MachineColumn.Kind.CREATIVE_MOTOR, List.of("speed", "powerLimit"), MachineColumn.Kind.CREATE_COUPLING, List.of());
        for (var kind : MachineColumn.Kind.values()) {
            var editor = place(MachineColumn.sample(kind, true, true, MachineColumn.Mode.SPEED, false, texts), texts, 464);
            assertEquals(fields.get(kind), keys(editor), kind.name());
            var reader = place(MachineColumn.sample(kind, false, true, MachineColumn.Mode.SPEED, false, texts), texts, 464);
            assertEquals(List.of(), keys(reader), kind + " reader");
            for (var p : reader.rows())
                if (p.row() instanceof Column.Buttons b) for (var spec : b.buttons()) assertFalse(spec.active(), kind + " reader's key " + spec.key());
        }
        // A fixed motor neither regulates nor sets a speed; a regulating one names the casing's target; Reset shows only while tripped (A94).
        assertEquals(List.of(), keys(place(MachineColumn.sample(MachineColumn.Kind.MOTOR, true, false, MachineColumn.Mode.SPEED, false, texts), texts, 464)));
        assertEquals(List.of(), keys(place(MachineColumn.sample(MachineColumn.Kind.MOTOR, true, true, MachineColumn.Mode.FLOW, false, texts), texts, 464)));
        assertTrue(widgets(place(MachineColumn.sample(MachineColumn.Kind.MOTOR, true, true, MachineColumn.Mode.SPEED, true, texts), texts, 464)).containsKey("reset"));
        assertFalse(widgets(place(MachineColumn.sample(MachineColumn.Kind.MOTOR, true, true, MachineColumn.Mode.SPEED, false, texts), texts, 464)).containsKey("reset"));
        assertFalse(widgets(place(MachineColumn.sample(MachineColumn.Kind.GEARBOX, true, true, MachineColumn.Mode.SPEED, false, texts), texts, 464)).containsKey("power:on"),
                "a gearbox has no switch (A99)");
    }

    /**
     * A97: the motor's tallest page fits the device page's design view (338 px). At the design height (480) a 4:3 window
     * is 640 wide and a 16:9 one 853; a narrower door scrolls.
     */
    @Test
    void theTallestPageFitsTheDesignView() {
        var texts = new HeatControlColumnTest.Recording("gui.icheme.");
        int view = FluidGuiLayout.doorView(FluidGuiLayout.HEIGHT);
        for (var c : MachineColumn.cases(texts))
            for (int width : new int[]{FluidGuiLayout.HEIGHT * 4 / 3, FluidGuiLayout.HEIGHT * 16 / 9}) {
                int tall = place(c.in(), texts, width).bounds().h();
                assertTrue(tall <= view, c.name() + " at " + width + ": " + tall + " px in a " + view + " px view");
            }
    }

    /** The view as the page shows it: speeds in rpm, powers in kW, the store's share and time (A92), the set speed both ways (A90). */
    @Test
    void thePublishedViewMapsToThePage() {
        var texts = new HeatControlColumnTest.Recording("gui.icheme.");
        var motor = view(ShaftNetwork.Kind.MOTOR, .8, ShaftTrain.Trip.NONE);
        var in = MachineColumn.input(motor, true, true, MachineColumn.Mode.SPEED, texts);
        assertEquals("2950", in.shaft().getFirst().value());
        assertEquals("24.9", in.shaft().get(1).value());
        assertEquals(List.of("26.7", "534", "72"), in.supply().stream().map(MachineColumn.Meter::value).toList());
        assertEquals("6 min 10 s", in.lasts());
        assertEquals(80, MachineColumn.speedEntry(motor), 1e-9);
        assertEquals(.8, MachineColumn.setPoint(MachineColumn.Kind.MOTOR, 80, 2950), 1e-12);
        var turbine = view(ShaftNetwork.Kind.STEAM_TURBINE, .9, ShaftTrain.Trip.NONE);
        assertEquals(2655, MachineColumn.speedEntry(turbine), 1e-9);
        assertEquals(.9, MachineColumn.setPoint(MachineColumn.Kind.STEAM_TURBINE, 2655, 2950), 1e-12);
        var steam = MachineColumn.input(turbine, true, true, MachineColumn.Mode.SPEED, texts);
        assertEquals("GOVERNED by FV-101", steam.governor());
        assertEquals(List.of("steam_flow", "inlet", "exhaust"), steam.supply().stream().map(MachineColumn.Meter::key).toList());
        assertEquals(List.of("2.9", "40.0", "4.0"), steam.supply().stream().map(MachineColumn.Meter::value).toList());
        assertEquals(ShaftTrain.CREATIVE_SPEED, MachineColumn.speedEntry(view(ShaftNetwork.Kind.CREATIVE_MOTOR, 0, ShaftTrain.Trip.NONE)), 1e-9);
        assertTrue(MachineColumn.input(view(ShaftNetwork.Kind.MOTOR, 1, ShaftTrain.Trip.OVERLOAD), true, false, MachineColumn.Mode.SPEED, texts).tripped());
        assertFalse(MachineColumn.input(view(ShaftNetwork.Kind.MOTOR, 1, ShaftTrain.Trip.NO_POWER), true, false, MachineColumn.Mode.SPEED, texts).tripped(),
                "NO POWER clears itself: no Reset");
        assertEquals("2 h 1 min", MachineColumn.duration(7300, texts));
    }

    private static List<String> keys(Column column) {
        var out = new ArrayList<String>();
        for (var p : column.rows()) if (p.row() instanceof Column.Field f) out.add(f.key());
        return out;
    }
}
