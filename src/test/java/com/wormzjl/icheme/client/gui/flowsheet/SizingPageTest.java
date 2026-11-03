package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Rect;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.sizing.AirCoolerSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.ColumnSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.CompressorSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.DrumSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.ExchangerSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.HeaterSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.Limit;
import com.wormzjl.icheme.science.flowsheet.sizing.LineSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.PressurePath;
import com.wormzjl.icheme.science.flowsheet.sizing.PumpSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.Rating;
import com.wormzjl.icheme.science.flowsheet.sizing.Service;
import com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.SizingFacts;
import com.wormzjl.icheme.science.flowsheet.sizing.TankSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitSize;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitType;
import com.wormzjl.icheme.science.flowsheet.sizing.ValveSizing;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Sizing tab (S4b of batch 2026-11-02-process-simulator): its door column in every view and for every kind of item, in English
 * and in Chinese; the facts its keys and entries write, each one step; Size all and Stop on the runner's thread; one Undo step for
 * Size all; the lamps a run leaves; the symbol that follows a type, and a sized exchanger's drops as meters.
 */
class SizingPageTest {
    private static final long GUARD = 60;
    private static final Flowsheet SHEET = sheet();
    private static final FlowsheetRun STEADY = new FlowsheetRun(FlowsheetRun.Status.STEADY, Map.of(), Map.of(), Map.of(), List.of(), List.of(), 0, 0, 0, 0, 0, List.of(), List.of());

    /** The design sheets' crude unit, with a drum, a tank and a compressor beside it, so every kind of size has a row. */
    private static Flowsheet sheet() {
        var sample = SheetFixture.sample();
        var units = new ArrayList<>(sample.units());
        for (var type : List.of("drum", "storage_tank", "compressor", "kettle"))
            units.add(new Flowsheet.Unit("u-" + type, type, type.toUpperCase(java.util.Locale.ROOT), 0, 300, SheetEditor.defaults(type, SheetFixture::column)));
        return new Flowsheet(sample.basis(), units, sample.streams(), FlowsheetSolver.Settings.DEFAULT);
    }

    private static Rating rating(Limit limit, double value, double bound) {
        return new Rating(List.of(limit.against(value, bound), Limit.UNIT_SHAPE.read(1)));
    }

    /** A rating of every kind of size, as a sizing leaves one; its numbers are sheet 24's. */
    private static SheetSizing.Sized sized() {
        var units = new LinkedHashMap<String, SheetSizing.SizedUnit>();
        units.put("P-101", new SheetSizing.SizedUnit(new PumpSizing.Size(.3), rating(Limit.PUMP_RATED, 96, 120)));
        units.put("T-101", new SheetSizing.SizedUnit(new ColumnSizing.Size(8, new ColumnTrays(40, 30, List.of(), List.of(), List.of())), rating(Limit.COLUMN_FLOOD, 71.3, 80)));
        units.put("T-101/condenser", new SheetSizing.SizedUnit(new ExchangerSizing.Size(false, 3, 1.25, 4), rating(Limit.EXCHANGER_DROP_GAS, .18, .2)));
        units.put("T-101/reboiler", new SheetSizing.SizedUnit(new ExchangerSizing.Size(true, 1, 1.5, 6), rating(Limit.KETTLE_FLUX, 20, 40)));
        units.put("T-101/receiver", new SheetSizing.SizedUnit(new DrumSizing.Size(UnitType.LYING_DRUM, 4, 10, true), rating(Limit.DRUM_HOLD, 7, 5)));
        units.put("T-101/reflux pump", new SheetSizing.SizedUnit(new PumpSizing.Size(.2), rating(Limit.PUMP_RATED, 119, 120)));
        units.put("T-101/pa 1 cooler", new SheetSizing.SizedUnit(new ExchangerSizing.Size(false, 1, 1, 3), rating(Limit.EXCHANGER_SURFACE, 225, 224)));
        units.put("T-101/pa 1 pump", new SheetSizing.SizedUnit(new PumpSizing.Size(.25), rating(Limit.PUMP_RATED, 110, 120)));
        units.put("E-101", new SheetSizing.SizedUnit(new ExchangerSizing.Size(false, 1, .75, 6), rating(Limit.EXCHANGER_SURFACE, 253, 444)));
        units.put("H-101", new SheetSizing.SizedUnit(new HeaterSizing.Size(UnitType.CABIN_HEATER, 1, 11, 41), rating(Limit.HEATER_DUTY, 87.6e6, 86.2e6)));
        units.put("V-101", new SheetSizing.SizedUnit(new ValveSizing.Size(.7), rating(Limit.VALVE_DROP, 2.28, 1.19)));
        units.put("E-102", new SheetSizing.SizedUnit(new AirCoolerSizing.Size(2.5, 6, new ProcessUnit.Bank(5, 3), 2), rating(Limit.AIR_COOLER_SURFACE, 752, 741)));
        units.put("P-102", new SheetSizing.SizedUnit(new PumpSizing.Size(.15), rating(Limit.PUMP_RATED, 71, 120)));
        units.put("u-drum", new SheetSizing.SizedUnit(new DrumSizing.Size(UnitType.STANDING_DRUM, 1.6, 5, false), rating(Limit.DRUM_HOLD, 6, 5)));
        units.put("u-storage_tank", new SheetSizing.SizedUnit(new TankSizing.Size(UnitType.SPHERE, 2, 12, 12), rating(Limit.TANK_HOLD, 30, 24)));
        units.put("u-compressor", new SheetSizing.SizedUnit(new CompressorSizing.Size(UnitType.TURBO, 1, 3), rating(Limit.TYPE_TURBO, 9000, 7000)));
        units.put("u-kettle", new SheetSizing.SizedUnit(new ExchangerSizing.Size(true, 1, 1, 6), rating(Limit.KETTLE_FLUX, 20, 40)));
        var lines = new LinkedHashMap<String, SheetSizing.SizedLine>();
        for (var stream : SHEET.streams()) {
            var line = new LineSizing.Line(Service.LIQUID, 50, .06, 800, 1e-3, 5e5, 50, .8, 20, null);
            lines.put(stream.id(), new SheetSizing.SizedLine(line, new LineSizing.Size(.3, 1), new Rating(List.of(Limit.LIQUID_SPEED.against(1.1, 3), Limit.LIQUID_DROP.against(.2, .5)))));
        }
        lines.put("T-101/overhead", new SheetSizing.SizedLine(new LineSizing.Line(Service.GAS, 30, 3, 10, 1e-5, 2e5, 50, .8, 60, null), new LineSizing.Size(.8, 2), new Rating(List.of(Limit.GAS_MOMENTUM.against(4000, 3700)))));
        var steps = List.of(new PressurePath.Step("S-5", PressurePath.Step.LINE, .02, 0), new PressurePath.Step("H-101", "coils", 2.11, 7.6), new PressurePath.Step("S-6", PressurePath.Step.LINE, .03, 0),
                new PressurePath.Step("T-101", "pa/1/drop", .3, .3));
        var paths = List.of(new PressurePath("P-101", 14, 0, "V-101", 2.72, true, "V-101", steps), new PressurePath("CRUDE", 8, 0, "P-101", 7.9, false, null, steps.subList(0, 1)));
        return new SheetSizing.Sized(lines, units, paths);
    }

    /** A host whose last run and rating stand as given: what the tab reads. */
    private static SimulatorHost host(SimulatorHost.Finished last, SimulatorRunner.Rated rated, boolean reader) {
        return new SimulatorHost() {
            @Override public Finished last() {
                return last;
            }

            @Override public SimulatorRunner.Rated rated() {
                return rated;
            }

            @Override public boolean reader() {
                return reader;
            }

            @Override public boolean canRun() {
                return true;
            }
        };
    }

    private static SheetContext context(SheetFixture.Texts texts, SheetEditor editor, FlowsheetRun run, boolean reader) {
        var last = new SimulatorHost.Finished(editor.sheet(), run, 1);
        var host = host(last, new SimulatorRunner.Rated(editor.sheet(), last, sized(), false), reader);
        if (reader) editor.lock(true, SheetEditor.LOCKED);
        return new SheetContext(editor, SheetState.of(editor.sheet(), last), SheetUnits.DEFAULT, texts, texts.measure, SheetFixture.FLUIDS, host, List.of());
    }

    @Test
    void theDoorFitsInEveryViewForEveryItem() {
        assertDoorsFit(new SheetFixture.Texts());
    }

    @Test
    void theDoorFitsInEveryViewForEveryItemInChinese() {
        assertDoorsFit(SheetFixture.Texts.chinese());
    }

    private static void assertDoorsFit(SheetFixture.Texts texts) {
        var m = texts.measure;
        int width = SimulatorFrame.SIZING_DOOR - 10;
        for (boolean reader : new boolean[]{false, true}) {
            var x = context(texts, SheetFixture.editor(SHEET), STEADY, reader);
            var state = new SizingPage.State();
            var sized = sized();
            for (var view : SizingPage.View.values()) {
                state.view = view;
                for (var id : sized.units().keySet()) {
                    state.unit = id;
                    for (var line : List.of("S-1", "T-101/overhead"))
                        for (int path = 0; path < 2; path++) {
                            state.line = line;
                            state.path = path;
                            var rows = SizingPage.rows(x, state);
                            SimulatorColumnsTest.assertFixedFits(m, rows, width, "sizing " + view + " " + id + " " + line + (reader ? ", reader" : ""));
                            // A view or a path is what the screen shows: a reader picks them too. Every fact's key is off.
                            if (reader) for (var key : SimulatorPage.keys(rows)) if (!key.startsWith("sizing/view/")) assertFalse(active(rows, key), "a reader sees no key active: " + key);
                        }
                    SizingPage.values(x, state);
                }
                SizingPage.status(x);
            }
            for (int w : new int[]{SimulatorFrame.LEAST_W, 500, SimulatorFrame.DOOR_W}) {
                var frame = new SimulatorFrame(w, SimulatorFrame.DOOR_H);
                var footer = SizingPage.footer(x, frame, x.host(), x.editor());
                for (var slot : footer) assertTrue(m.width(slot.button().text()) <= slot.rect().w() - 4, "a footer key fits at " + w + ": " + slot.button().text());
                assertTrue(footer.getLast().rect().right() < frame.softTray().x(), "the footer clears the display keys at " + w);
            }
        }
        // The status line's words, a run that is not steady, an item that misses.
        context(texts, SheetFixture.editor(SHEET), FlowsheetRun.none(), false);
        SizingPage.status(context(texts, SheetFixture.editor(SHEET), FlowsheetRun.none(), false));
        assertEquals(java.util.Set.of(), texts.missing);
        assertEquals(List.of(), texts.inEnglish.stream().filter(key -> key.startsWith("gui.icheme.flowsheet.")).toList());
    }

    private static boolean active(List<Column.Row> rows, String key) {
        for (var row : rows)
            if (row instanceof Column.Group group) for (var inner : group.rows())
                if (inner instanceof Column.Buttons buttons) for (var button : buttons.buttons()) if (button.key().equals(key)) return button.active();
        return false;
    }

    @Test
    void theGlassDrawsEveryViewAndAClickPicksARow() {
        var texts = new SheetFixture.Texts();
        var x = context(texts, SheetFixture.editor(SHEET), STEADY, false);
        var state = new SizingPage.State();
        var box = new Rect(189, 77, 1400, 400);
        for (var view : SizingPage.View.values()) {
            state.view = view;
            SizingPage.draw(new Recording(texts.measure), x, state, box);
        }
        state.view = SizingPage.View.UNITS;
        // The head is one line where the columns fit: the third row down is the third unit.
        String key = SizingPage.click(x, state, texts.measure, box, box.x() + 30, box.y() + 4 + 12 + 2 * 12 + 4);
        assertEquals("T-101/condenser", key);
        assertTrue(SizingPage.pick(state, key));
        assertEquals("T-101/condenser", state.unit);
        state.view = SizingPage.View.PRESSURE;
        assertTrue(SizingPage.pick(state, SizingPage.click(x, state, texts.measure, box, box.x() + 30, box.y() + 4 + 12 + 12 + 4)));
        assertEquals(1, state.path);
        assertEquals(List.of(), texts.missing.stream().toList());
    }

    @Test
    void aNarrowGlassStacksItsTableRatherThanCutACell() {
        var texts = new SheetFixture.Texts();
        var x = context(texts, SheetFixture.editor(SHEET), STEADY, false);
        var canvas = new Recording(texts.measure);
        SizingPage.draw(canvas, x, new SizingPage.State(), new Rect(189, 77, 270, 300));
        for (var text : canvas.texts) assertTrue(text.x() + texts.measure.width(text.text()) <= 189 + 270 + 4 || text.x() >= 189 + 270, "a cell inside the glass: " + text.text());
    }

    @Test
    void keysAndEntriesWriteFactsEachOneStep() {
        var texts = new SheetFixture.Texts();
        var editor = SheetFixture.editor(SHEET);
        var x = context(texts, editor, STEADY, false);
        var state = new SizingPage.State();
        state.unit = "E-101";
        SizingPage.entry(x, state, "sizing/size/shell", "1.25");
        var held = editor.sheet().sizing().unit("E-101");
        assertEquals(SizingFacts.Mode.FIXED, held.mode(), "a typed size keeps the item Fixed");
        assertEquals(new ExchangerSizing.Size(false, 1, 1.25, 6), held.size());
        SizingPage.press(x, state, "sizing/auto");
        assertEquals(SizingFacts.Mode.AUTO, editor.sheet().sizing().unit("E-101").mode());
        assertTrue(editor.undo());
        assertTrue(editor.undo());
        assertFalse(editor.sheet().sizing().any(), "two steps, two undos");
        // A member's count is its column door's (S3b): one step writes both.
        state.unit = "T-101/condenser";
        SizingPage.press(x, state, "sizing/size/count+");
        assertEquals(4, editor.sheet().sizing().unit("T-101/condenser").size().count());
        assertEquals(4, ColumnDoor.of(editor.unit("T-101").params()).units().condenser());
        assertTrue(editor.undo());
        assertEquals(1, ColumnDoor.of(editor.unit("T-101").params()).units().condenser());
        assertFalse(editor.sheet().sizing().any());
        // A column's diameter is its door's.
        state.unit = "T-101";
        SizingPage.entry(x, state, "sizing/size/diameter", "7.5");
        assertEquals(7.5, ColumnDoor.of(editor.unit("T-101").params()).diameter());
        assertEquals(SizingFacts.Mode.FIXED, editor.sheet().sizing().unit("T-101").mode());
        // A type key keeps the type; pressed again it lets Auto choose.
        state.unit = "H-101";
        SizingPage.press(x, state, "sizing/type/VERTICAL_HEATER");
        assertEquals(UnitType.VERTICAL_HEATER, editor.sheet().sizing().unit("H-101").type());
        SizingPage.press(x, state, "sizing/type/VERTICAL_HEATER");
        assertNull(editor.sheet().sizing().unit("H-101").type());
        // The design basis, a line's service and runs.
        SizingPage.entry(x, state, "sizing/design_flow", "120");
        assertEquals(1.2, editor.sheet().sizing().basis().designFlow(), 1e-12);
        SizingPage.entry(x, state, "sizing/design_flow", "-5");
        assertEquals(1.2, editor.sheet().sizing().basis().designFlow(), 1e-12, "a basis the record refuses changes nothing");
        state.view = SizingPage.View.LINES;
        state.line = "S-1";
        SizingPage.press(x, state, "sizing/service");
        assertEquals(Service.values()[0], editor.sheet().sizing().line("S-1").service());
        SizingPage.press(x, state, "sizing/line/runs+");
        assertEquals(new LineSizing.Size(.3, 2), editor.sheet().sizing().line("S-1").size());
    }

    @Test
    void aReadingStandsInItsOwnUnitAndAYesOrNoLimitSaysSo() {
        var texts = new SheetFixture.Texts();
        var x = context(texts, SheetFixture.editor(SHEET), STEADY, false);
        assertEquals("80.4 m", SizingPage.value(x, Limit.PUMP_HEAD.against(80.4, 204)), "a pump's head is metres of its liquid");
        assertEquals("max 204 m", SizingPage.limit(x, Limit.PUMP_HEAD.against(80.4, 204)));
        assertEquals("Yes", SizingPage.value(x, Limit.UNIT_SHAPE.read(1)));
        assertEquals("No", SizingPage.value(x, Limit.RUN_STEADY.read(0)));
        assertEquals("Yes", SizingPage.limit(x, Limit.RUN_STEADY.read(0)));
        assertEquals("0.13 bar", SizingPage.value(x, Limit.EXCHANGER_DROP_GAS.against(.13, .2)));
        assertTrue(texts.missing.isEmpty(), texts.missing.toString());
    }

    @Test
    void theTabOpensOnASteadyRunAndSizeAllOnlyOnAnUneditedOne() {
        var texts = new SheetFixture.Texts();
        var editor = SheetFixture.editor(SHEET);
        var none = new SheetContext(editor, SheetState.of(editor.sheet(), null), SheetUnits.DEFAULT, texts, texts.measure, SheetFixture.FLUIDS, SimulatorHost.NONE, List.of());
        assertFalse(SizingPage.open(none), "no run, no tab");
        assertFalse(SizingPage.open(context(texts, editor, FlowsheetRun.none(), false)), "a run that is not steady");
        assertTrue(SizingPage.open(context(texts, editor, STEADY, false)));
        assertTrue(SizingPage.sizes(context(texts, editor, STEADY, false)));
        var frame = new SimulatorFrame(SimulatorFrame.DOOR_W, SimulatorFrame.DOOR_H);
        var tab = SimulatorPage.frameKeys(none, SimulatorFrame.Tab.FLOWSHEET, frame, SimulatorHost.NONE, editor).get(SimulatorFrame.Tab.SIZING.ordinal()).button();
        assertFalse(tab.active());
        assertEquals(texts.get("flowsheet.hint.sizing"), tab.hint());
        // A size the run reads greys its unit: Size all waits for the next run, and the tab stays open on the sizes.
        var x = context(texts, editor, STEADY, false);
        var state = new SizingPage.State();
        state.unit = "E-101";
        SizingPage.entry(x, state, "sizing/size/shell", "1.25");
        var edited = new SheetContext(editor, SheetState.of(editor.sheet(), x.host().last()), SheetUnits.DEFAULT, texts, texts.measure, SheetFixture.FLUIDS, x.host(), List.of());
        assertTrue(edited.state().stale("E-101"), "the exchanger's drops follow its size");
        assertFalse(SizingPage.sizes(edited));
        assertTrue(SizingPage.open(edited));
        var footer = SizingPage.footer(edited, frame, edited.host(), editor);
        assertEquals(List.of("close", "size_all", "send", "undo", "redo"), footer.stream().map(slot -> slot.button().key()).toList());
        assertFalse(footer.get(1).button().active());
        assertEquals(texts.get("flowsheet.sizing.size_all_hint"), footer.get(1).button().hint());
        assertFalse(footer.get(2).button().active(), "a sheet on no block sends nowhere");
        assertEquals(texts.get("flowsheet.sizing.send_nowhere"), footer.get(2).button().hint());
        assertTrue(texts.missing.isEmpty(), texts.missing.toString());
    }

    /** A host on a block, as the holder or a reader, with a run in hand or not. */
    private static SimulatorHost block(SimulatorHost.Finished last, SimulatorRunner.Rated rated, boolean reader, SheetState.Running running) {
        return new SimulatorHost() {
            @Override public Finished last() {
                return last;
            }

            @Override public SimulatorRunner.Rated rated() {
                return rated;
            }

            @Override public boolean reader() {
                return reader;
            }

            @Override public boolean canRun() {
                return true;
            }

            @Override public boolean stores() {
                return true;
            }

            @Override public SheetState.Running running() {
                return running;
            }
        };
    }

    private static SheetContext sending(SheetFixture.Texts texts, SheetEditor editor, FlowsheetRun run, SheetSizing.Sized sized, boolean reader, SheetState.Running running) {
        var last = new SimulatorHost.Finished(editor.sheet(), run, 1);
        var host = block(last, new SimulatorRunner.Rated(editor.sheet(), last, sized, false), reader, running);
        return new SheetContext(editor, SheetState.of(editor.sheet(), last), SheetUnits.DEFAULT, texts, texts.measure, SheetFixture.FLUIDS, host, List.of());
    }

    /** {@code sized} with its red rows left out. */
    private static SheetSizing.Sized green(SheetSizing.Sized sized) {
        var units = new LinkedHashMap<String, SheetSizing.SizedUnit>();
        sized.units().forEach((id, unit) -> {
            if (unit.rating().lamp() != com.wormzjl.icheme.science.flowsheet.sizing.Lamp.RED) units.put(id, unit);
        });
        var lines = new LinkedHashMap<String, SheetSizing.SizedLine>();
        sized.lines().forEach((id, line) -> {
            if (line.rating().lamp() != com.wormzjl.icheme.science.flowsheet.sizing.Lamp.RED) lines.put(id, line);
        });
        return new SheetSizing.Sized(lines, units, sized.paths().stream().filter(p -> p.rating().lamp() != com.wormzjl.icheme.science.flowsheet.sizing.Lamp.RED).toList());
    }

    /**
     * S5: Send to block acts for the holder on a steady run nothing has greyed, with no red row (plan section 3.1: a yellow row does not stop
     * it), and nothing in hand; its hover says why not, or how many designs it stores. Those designs are each unit and member at its shown size,
     * under its block's tag, with its count; the valve, which the pipe tool lays, is not among them.
     */
    @Test
    void sendToBlockActsForTheHolderOnASteadyRunWithNoRedRow() {
        var texts = new SheetFixture.Texts();
        var editor = SheetFixture.editor(SHEET);
        var frame = new SimulatorFrame(SimulatorFrame.DOOR_W, SimulatorFrame.DOOR_H);
        var all = sized();
        int red = SizingPage.reds(all);
        assertTrue(red > 0, "the fixture has red rows");
        var x = sending(texts, editor, STEADY, all, false, null);
        assertEquals(texts.get("flowsheet.sizing.send_red", red), SizingPage.unsent(x));
        var ok = green(all);
        // A Should missed: the column floods past good practice and still runs.
        ok.units().put("T-101", new SheetSizing.SizedUnit(ok.units().get("T-101").size(), rating(Limit.COLUMN_FLOOD, 85, 80)));
        assertEquals(0, SizingPage.reds(ok));
        assertTrue(ok.units().values().stream().anyMatch(u -> u.rating().lamp() == com.wormzjl.icheme.science.flowsheet.sizing.Lamp.YELLOW), "a yellow row stays");
        var holder = sending(texts, editor, STEADY, ok, false, null);
        assertNull(SizingPage.unsent(holder));
        var designs = SizingPage.designs(holder);
        var send = SizingPage.footer(holder, frame, holder.host(), editor).get(2).button();
        assertTrue(send.active());
        assertEquals(texts.get("flowsheet.sizing.send_hint", designs.size()), send.hint());
        assertEquals(texts.get("flowsheet.sizing.send_reader"), SizingPage.unsent(sending(texts, editor, STEADY, ok, true, null)));
        assertEquals(texts.get("flowsheet.sizing.send_run"), SizingPage.unsent(sending(texts, editor, FlowsheetRun.none(), ok, false, null)));
        assertEquals(texts.get("flowsheet.sizing.send_running"), SizingPage.unsent(sending(texts, editor, STEADY, ok, false, new SheetState.Running(1, 0, 0, null, null, SheetState.Task.SIZE))));
        // What it stores: every green unit the constructor builds (a valve it does not; a size off its grid it refuses), a member under its
        // block's tag with its own key and its count.
        var expected = ok.units().entrySet().stream().filter(e -> com.wormzjl.icheme.science.flowsheet.sizing.UnitSize.built(e.getValue().size()) != null).map(java.util.Map.Entry::getKey).toList();
        assertTrue(expected.contains("T-101/condenser") && !expected.contains("V-101"));
        assertEquals(expected.size(), designs.size());
        for (int i = 0; i < designs.size(); i++) {
            var design = designs.get(i);
            String id = expected.get(i);
            int slash = id.indexOf('/');
            assertEquals(slash < 0 ? editor.unit(id).name() : editor.unit(id.substring(0, slash)).name(), design.tag(), id);
            assertEquals(slash < 0 ? "" : id.substring(slash + 1), design.member(), id);
            assertEquals(ok.units().get(id).size().count(), design.count(), id);
            assertEquals(ok.units().get(id).size() instanceof ColumnSizing.Size, design.column() != null, id);
        }
        assertTrue(texts.missing.isEmpty(), texts.missing.toString());
    }

    @Test
    void theSymbolFollowsTheTypeAndASizedSideShowsItsDropsAsMeters() {
        var editor = SheetFixture.editor(SHEET);
        var heater = editor.unit("H-101");
        assertEquals(Symbol.HEATER, Tiles.symbol(heater, editor.sheet().sizing()), "the tile's, until a type is held");
        var facts = editor.sheet().sizing().with("H-101", SizingFacts.UnitFacts.AUTO.with(new HeaterSizing.Size(UnitType.CABIN_HEATER, 1, 11, 41)))
                .with("u-drum", SizingFacts.UnitFacts.AUTO.typed(UnitType.LYING_DRUM)).with("u-compressor", SizingFacts.UnitFacts.AUTO.typed(UnitType.TURBO))
                .with("u-storage_tank", SizingFacts.UnitFacts.AUTO.typed(UnitType.SPHERE)).with("E-101", SizingFacts.UnitFacts.AUTO.with(new ExchangerSizing.Size(false, 1, 1, 6)));
        assertTrue(editor.sizing(facts, null));
        var sizing = editor.sheet().sizing();
        assertEquals(Symbol.CABIN, Tiles.symbol(heater, sizing));
        assertEquals(Symbol.LYING, Tiles.symbol(editor.unit("u-drum"), sizing));
        assertEquals(Symbol.TURBO, Tiles.symbol(editor.unit("u-compressor"), sizing));
        assertEquals(Symbol.SPHERE, Tiles.symbol(editor.unit("u-storage_tank"), sizing));
        assertEquals(Symbol.CABIN, editor.layout().box("H-101").symbol(), "the sheet draws it");
        assertEquals(74, editor.layout().box("H-101").rect().w());
        var texts = new SheetFixture.Texts();
        var x = new SheetContext(editor, SheetState.of(editor.sheet(), null), SheetUnits.DEFAULT, texts, texts.measure, SheetFixture.FLUIDS);
        assertNotNull(UnitSize.built(new ExchangerSizing.Size(false, 1, 1, 6)), "a size the constructor builds");
        var door = UnitDoors.door(x, "E-101", new Door.View());
        var keys = new ArrayList<String>();
        for (var rows : door.columns()) keys.addAll(SimulatorPage.keys(rows));
        assertFalse(keys.contains("tube_drop"), "no entry once sized");
        assertTrue(door.columns().stream().flatMap(List::stream).anyMatch(row -> row instanceof Column.Group g && g.title().equals(texts.get("flowsheet.door.group.drops_flow"))));
        SimulatorColumnsTest.assertDoorFits(texts.measure, door, "E-101 sized");
        assertTrue(texts.missing.isEmpty(), texts.missing.toString());
    }

    // ---- The runner ----

    private static void ended() throws InterruptedException {
        for (var thread : Thread.getAllStackTraces().keySet())
            if (thread.getName().equals(SimulatorRunner.THREAD)) {
                thread.join(TimeUnit.SECONDS.toMillis(GUARD));
                assertFalse(thread.isAlive(), "the run thread ended");
            }
    }

    @AfterEach
    void noThreadStays() throws InterruptedException {
        ended();
    }

    /** Holds Size all at its door until opened, then asks the checkpoint, as the real sizing does between items. */
    private static final class Held implements SimulatorRunner.Engine {
        final CountDownLatch open = new CountDownLatch(1), entered = new CountDownLatch(1);
        volatile Thread thread;

        @Override public FlowsheetRun run(Flowsheet sheet, MaterialCatalog catalog, Runnable checkpoint, FlowsheetRun last, FlowsheetSolver.Progress progress) {
            return REAL.run(sheet, catalog, checkpoint, last, progress);
        }

        @Override public FlowsheetRun alone(Flowsheet sheet, String unit, MaterialCatalog catalog, Runnable checkpoint, Map<String, com.wormzjl.icheme.science.flowsheet.FlowStream> streams, FlowsheetSolver.Progress progress) {
            return REAL.alone(sheet, unit, catalog, checkpoint, streams, progress);
        }

        @Override public SheetSizing.SizeAll sizeAll(Flowsheet sheet, FlowsheetRun run, MaterialCatalog catalog, Runnable checkpoint, FlowsheetSolver.Progress progress) {
            thread = Thread.currentThread();
            entered.countDown();
            try {
                assertTrue(open.await(GUARD, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            checkpoint.run();
            return SimulatorRunner.Engine.super.sizeAll(sheet, run, catalog, checkpoint, progress);
        }
    }

    @Test
    void aRunEndsWithItsLampsAndSizeAllLandsAsOneUndoStep() throws InterruptedException {
        var held = new Held();
        var runner = new SimulatorRunner(() -> SheetFixture.CATALOG, held);
        var editor = SheetFixture.editor(SheetFixture.water());
        runner.run(editor.sheet());
        ended();
        var last = runner.last();
        var rated = runner.rated();
        assertEquals(FlowsheetRun.Status.STEADY, last.run().status());
        assertNotNull(rated, "a steady run ends rated");
        assertSame(last, rated.run(), "its lamps hold for that run");
        assertFalse(rated.edit());
        assertEquals(List.of("P-1", "V-1", "P-2"), List.copyOf(rated.sized().units().keySet()));
        assertTrue(rated.sized().lines().containsKey("A"));
        // Size all: on the run's thread, with the runner busy and the editor locked.
        var from = editor.sheet();
        runner.size(from);
        assertTrue(held.entered.await(GUARD, TimeUnit.SECONDS));
        assertNotSame(Thread.currentThread(), held.thread);
        assertEquals(SimulatorRunner.THREAD, held.thread.getName());
        assertEquals(SheetState.Task.SIZE, runner.running().task());
        var host = new SimulatorHost() {
            @Override public SimulatorRunner runner() {
                return runner;
            }
        };
        host.lock(editor);
        assertTrue(editor.locked(), "no edit while it sizes");
        assertTrue(new SheetState(from, null, null, 0, runner.running()).status(new SheetFixture.Texts(), SheetUnits.DEFAULT, false).text().startsWith("Sizing all, "));
        held.open.countDown();
        ended();
        host.lock(editor);
        var all = runner.rated();
        assertTrue(all.edit(), "Size all's sheet is an edit");
        assertTrue(all.sheet().sizing().any(), "every Auto item holds its size");
        assertSame(runner.last(), all.run());
        assertSame(last.run(), all.run().run(), "pumps and lines move no run, so the run stands");
        assertTrue(editor.sized(all.sheet().copy()));
        assertTrue(SheetState.same(editor.sheet(), all.sheet()));
        assertFalse(editor.sized(all.sheet().copy()), "the same sizes add no step");
        assertTrue(SheetState.of(editor.sheet(), runner.last()).steady(), "the sized sheet is the one the run stands on");
        assertTrue(editor.undo());
        assertSame(from, editor.sheet(), "one Undo takes Size all back whole");
        assertFalse(editor.canUndo());
    }

    @Test
    void stopInSizeAllChangesNothing() throws InterruptedException {
        var held = new Held();
        var runner = new SimulatorRunner(() -> SheetFixture.CATALOG, held);
        var sheet = SheetFixture.water();
        runner.run(sheet);
        ended();
        var last = runner.last();
        var rated = runner.rated();
        runner.size(sheet);
        assertTrue(held.entered.await(GUARD, TimeUnit.SECONDS));
        runner.stop();
        held.open.countDown();
        ended();
        assertSame(last, runner.last(), "the run on show stands");
        assertSame(rated, runner.rated(), "its lamps stand");
        assertNull(runner.running());
        assertNull(runner.broken());
    }

    @Test
    void aQuietRatingLocksNothingAndGivesWayToARun() throws InterruptedException {
        var gate = new CountDownLatch(1);
        var rating = new CountDownLatch(1);
        var engine = new SimulatorRunner.Engine() {
            @Override public FlowsheetRun run(Flowsheet sheet, MaterialCatalog catalog, Runnable checkpoint, FlowsheetRun last, FlowsheetSolver.Progress progress) {
                return REAL.run(sheet, catalog, checkpoint, last, progress);
            }

            @Override public FlowsheetRun alone(Flowsheet sheet, String unit, MaterialCatalog catalog, Runnable checkpoint, Map<String, com.wormzjl.icheme.science.flowsheet.FlowStream> streams, FlowsheetSolver.Progress progress) {
                return REAL.alone(sheet, unit, catalog, checkpoint, streams, progress);
            }

            @Override public SheetSizing.Sized rate(Flowsheet sheet, FlowsheetRun run, MaterialCatalog catalog, Runnable checkpoint) {
                if (run.status() == FlowsheetRun.Status.STEADY && Thread.currentThread().getName().equals(SimulatorRunner.THREAD) && rating.getCount() == 0) {
                    try {
                        assertTrue(gate.await(GUARD, TimeUnit.SECONDS));
                    } catch (InterruptedException e) {
                        throw new IllegalStateException(e);
                    }
                    checkpoint.run();
                }
                return SimulatorRunner.Engine.super.rate(sheet, run, catalog, checkpoint);
            }
        };
        var runner = new SimulatorRunner(() -> SheetFixture.CATALOG, engine);
        var editor = SheetFixture.editor(SheetFixture.water());
        runner.run(editor.sheet());
        ended();
        var first = runner.rated();
        rating.countDown();
        runner.rate(editor.sheet());
        assertTrue(runner.rating());
        assertNull(runner.running(), "a quiet rating shows no run in hand");
        var host = new SimulatorHost() {
            @Override public SimulatorRunner runner() {
                return runner;
            }
        };
        host.lock(editor);
        assertFalse(editor.locked(), "nor locks the sheet");
        runner.run(editor.sheet());
        assertFalse(runner.rating(), "the run takes its place");
        gate.countDown();
        ended();
        assertNotSame(first, runner.rated(), "the run rates what it leaves");
        assertSame(runner.last(), runner.rated().run());
    }

    @Test
    void aStoredRunWithoutLampsIsRatedOnItsOwnSheetAndAnotherShapeIsAllRed() throws InterruptedException {
        var runner = new SimulatorRunner(() -> SheetFixture.CATALOG);
        var sheet = SheetFixture.water();
        runner.run(sheet);
        ended();
        var stored = new SimulatorHost.Finished(runner.last().sheet(), runner.last().run(), 1);
        var fresh = new SimulatorRunner(() -> SheetFixture.CATALOG);
        fresh.restore(stored);
        assertNull(fresh.rated(), "a block's stored run comes without its lamps");
        fresh.rate(sheet);
        ended();
        assertSame(stored, fresh.rated().run());
        assertEquals(runner.rated().sized().units().keySet(), fresh.rated().sized().units().keySet());
        var grown = new Flowsheet(sheet.basis(), sheet.units(), List.of(sheet.streams().get(0), sheet.streams().get(1)), sheet.settings());
        fresh.rate(grown);
        ended();
        for (var unit : fresh.rated().sized().units().values()) assertNotNull(unit.rating().reading(Limit.RUN_STEADY), "a sheet the run does not fit reads red");
    }

    /** A canvas that keeps its texts. */
    private static final class Recording implements com.wormzjl.icheme.client.gui.common.kit.Canvas {
        record Text(String text, int x, int y) {
        }

        final List<Text> texts = new ArrayList<>();
        private final com.wormzjl.icheme.client.gui.common.kit.Measure measure;

        Recording(com.wormzjl.icheme.client.gui.common.kit.Measure measure) {
            this.measure = measure;
        }

        @Override public com.wormzjl.icheme.client.gui.common.kit.Measure measure() {
            return measure;
        }

        @Override public void fill(int x0, int y0, int x1, int y1, int argb) {
        }

        @Override public void text(String text, int x, int y, int argb) {
            texts.add(new Text(text, x, y));
        }

        @Override public void text(String text, float x, float y, float scale, int argb) {
            texts.add(new Text(text, (int) x, (int) y));
        }

        @Override public void clip(Rect area) {
        }

        @Override public void unclip() {
        }
    }

}
