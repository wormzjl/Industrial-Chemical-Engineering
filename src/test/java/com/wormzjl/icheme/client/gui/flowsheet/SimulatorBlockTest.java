package com.wormzjl.icheme.client.gui.flowsheet;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.wormzjl.icheme.client.gui.screens.inventory.SimulatorBlockHost;
import com.wormzjl.icheme.network.SimulatorResults;
import com.wormzjl.icheme.science.column.v3.V3ColumnDisplayResult;
import com.wormzjl.icheme.science.column.v3.V3ConvergenceEvidence;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetFile;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.Note;
import com.wormzjl.icheme.science.flowsheet.Report;
import com.wormzjl.icheme.science.flowsheet.RevisionNotice;
import com.wormzjl.icheme.science.flowsheet.SheetBody;
import com.wormzjl.icheme.world.inventory.ProcessSimulatorMenu;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The client's half of the sheet on the block (W8 of batch 2026-11-02-process-simulator, plan sections 3.4, 3.8 and 3.9): results as state, what gives way, a replay, a reader who sends
 * nothing, the clipboard. A run here is water, which takes no time; the sample's size is in {@link CrudeUnitSampleTest.SimulatorRunTest}.
 */
class SimulatorBlockTest {
    private static SimulatorHost.Finished run(Flowsheet sheet) throws InterruptedException {
        var runner = new SimulatorRunner(() -> SheetFixture.CATALOG);
        runner.run(sheet);
        for (int i = 0; i < 6000 && runner.running() != null; i++) Thread.sleep(10);
        return runner.last();
    }

    private static SimulatorHost.Finished replay(SimulatorHost.Finished finished, Flowsheet sheet) {
        byte[] bytes = FlowsheetFile.write(sheet);
        return SimulatorResults.read(SimulatorResults.fitted(finished, sheet, bytes), sheet, SheetFixture.FLUIDS.model());
    }

    private static V3ColumnDisplayResult display() {
        return new V3ColumnDisplayResult("0".repeat(64), "mesh", "assumptions", "data", 3, 1e-12, 1, List.of(), Optional.empty(), V3ConvergenceEvidence.MAXIMUM_LOG_FLOW_CHANGE, Optional.empty(), Optional.empty());
    }

    private static SimulatorBlockHost host(boolean reader, boolean waiting, List<byte[]> sent) {
        return new SimulatorBlockHost(() -> SheetFixture.CATALOG, reader, reader ? "Alex" : null, waiting, RevisionNotice.tokens(SheetFixture.CATALOG), sent::add);
    }

    @Test
    void resultsComeBackAsTheyWent() throws Exception {
        var sheet = SheetFixture.water();
        var finished = run(sheet);
        assertEquals(FlowsheetRun.Status.STEADY, finished.run().status());
        var back = replay(finished, sheet);
        assertSame(sheet, back.sheet(), "the sheet it ran on is the body's, not a second copy");
        assertEquals(finished.ranOn(), back.ranOn());
        assertFalse(back.ranOn().isEmpty());
        assertEquals(finished.seconds(), back.seconds());
        var run = finished.run();
        var again = back.run();
        assertEquals(run.status(), again.status());
        assertEquals(run.outcomes(), again.outcomes());
        assertEquals(run.massIn(), again.massIn());
        assertEquals(run.heatOut(), again.heatOut());
        assertEquals(run.order(), again.order());
        assertEquals(run.passes(), again.passes());
        assertEquals(run.log(), again.log());
        assertEquals(run.streams().keySet(), again.streams().keySet());
        for (var id : run.streams().keySet()) {
            FlowStream was = run.streams().get(id), now = again.streams().get(id);
            assertArrayEquals(was.amounts(), now.amounts());
            assertEquals(was.enthalpy(), now.enthalpy());
            assertEquals(was.mass(), now.mass());
            assertEquals(was.state() == null, now.state() == null);
            if (was.state() == null) continue;
            assertEquals(was.state().enthalpy(), now.state().enthalpy(), Math.abs(was.state().enthalpy()) * 1e-12, id);
            var a = StreamProps.of(was, SheetFixture.FLUIDS);
            var b = StreamProps.of(now, SheetFixture.FLUIDS);
            assertEquals(a.vapour(), b.vapour());
            assertEquals(a.density(), b.density(), a.density() * 1e-12);
        }
        assertEquals(run.reports().keySet(), again.reports().keySet());
        for (var id : run.reports().keySet()) {
            Report was = run.reports().get(id), now = again.reports().get(id);
            assertEquals(was.readings(), now.readings());
            assertEquals(was.notes(), now.notes());
            assertEquals(was.duty(), now.duty());
            assertEquals(was.massIn(), now.massIn());
            assertEquals(was.enthalpyOut(), now.enthalpyOut());
        }
    }

    /** A package's token lists its records' revisions, so it is long: an opening carries every one as it stands. */
    @Test
    void anOpeningCarriesEveryPackagesToken() {
        var tokens = RevisionNotice.tokens(SheetFixture.CATALOG);
        assertTrue(tokens.values().stream().anyMatch(token -> token.length() > 512), "a short token proves nothing here");
        var buffer = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        new ProcessSimulatorMenu.Opening(net.minecraft.core.BlockPos.ZERO, java.util.UUID.nameUUIDFromBytes(new byte[3]), true, "Alex", true, false, tokens).write(buffer);
        var back = ProcessSimulatorMenu.Opening.read(buffer);
        assertEquals(tokens, back.tokens());
        assertEquals("Alex", back.holder());
        assertFalse(buffer.isReadable());
    }

    /** A batch still's curve (batch 2026-11-09-batch-distiller, W7): each series comes back in its order, its gaps (NaN) kept. */
    @Test
    void aBatchCurveComesBack() {
        var sheet = SheetFixture.water();
        var report = new Report();
        report.reading("batch", 5400);
        var channels = new LinkedHashMap<String, double[]>();
        channels.put("pot", new double[]{300, 320, 341.5});
        channels.put("head", new double[]{Double.NaN, 310.25, 333});
        channels.put("x:Isopentane", new double[]{Double.NaN, .6, .2});
        report.series("batch", new Report.Series(new double[]{0, 1800, 5400}, channels));
        report.series("second", new Report.Series(new double[0], Map.of()));
        var run = new FlowsheetRun(FlowsheetRun.Status.STEADY, Map.of(), Map.of(), Map.of("B", report), List.of(), List.of(), 0, 0, 0, 0, 0, List.of(), List.of());
        var back = replay(new SimulatorHost.Finished(sheet, run, 1), sheet).run().reports().get("B");
        assertEquals(List.of("batch", "second"), List.copyOf(back.series().keySet()));
        var series = back.series().get("batch");
        assertArrayEquals(new double[]{0, 1800, 5400}, series.time());
        assertEquals(List.copyOf(channels.keySet()), List.copyOf(series.channels().keySet()));
        for (var key : channels.keySet()) assertArrayEquals(channels.get(key), series.channel(key), key);
        assertEquals(0, back.series().get("second").size());
        assertEquals(5400, back.readings().get("batch"));
    }

    @Test
    void aColumnsDisplayAndASolveAloneComeBack() {
        var sheet = SheetFixture.water();
        var report = new Report();
        report.display(display());
        report.start(Report.Start.LAST);
        var run = new FlowsheetRun(FlowsheetRun.Status.NOT_STEADY, Map.of(), Map.of("T", FlowsheetRun.Outcome.SOLVED), Map.of("T", report), List.of(new FlowsheetRun.Loop(List.of("A"), 3, true)), List.of(Note.warning("T", "column.x", "1")), 1, 2, 3, 4, 5, List.of(), List.of());
        var alone = new SimulatorHost.Alone("T", sheet, FlowsheetRun.Status.FAILED, Note.error("T", "column.y"), 2);
        var back = replay(new SimulatorHost.Finished(SheetFixture.line(), run, 7, alone, Map.of("p", "1")), sheet);
        assertEquals(display(), back.run().reports().get("T").display());
        assertEquals(Report.Start.LAST, back.run().reports().get("T").start());
        assertEquals(alone, back.alone());
        assertEquals(FlowsheetFile.write(SheetFixture.line()).length, FlowsheetFile.write(back.sheet()).length, "a run's own sheet travels when it differs");
    }

    /** A sheet is a fact and never gives way; a column's display goes first, for every reading stands without it; then the results whole. */
    @Test
    void whatGivesWayAtTheBound() {
        var sheet = SheetFixture.water();
        var report = new Report();
        report.reading("duty", 1);
        report.display(display());
        var run = new FlowsheetRun(FlowsheetRun.Status.STEADY, Map.of(), Map.of(), Map.of("T", report), List.of(), List.of(), 0, 0, 0, 0, 0, List.of(), List.of());
        var finished = new SimulatorHost.Finished(sheet, run, 1);
        int whole = SimulatorResults.fitted(finished, sheet, new byte[0]).length;
        var fits = new byte[SheetBody.MAX_UNPACKED - 5 - whole];
        assertEquals(whole, SimulatorResults.fitted(finished, sheet, fits).length);
        var tight = new byte[fits.length + 1];
        var without = SimulatorResults.fitted(finished, sheet, tight);
        assertTrue(without.length < whole && without.length > 0);
        var back = SimulatorResults.read(without, sheet, SheetFixture.FLUIDS.model());
        assertNull(back.run().reports().get("T").display());
        assertEquals(1, back.run().reports().get("T").readings().get("duty"));
        assertEquals(0, SimulatorResults.fitted(finished, sheet, new byte[SheetBody.MAX_UNPACKED - 5]).length);
        assertEquals(0, SimulatorResults.fitted(null, sheet, new byte[0]).length, "no run, no results");
    }

    @Test
    void anythingAmissDropsResultsAndNeverThrows() throws Exception {
        var sheet = SheetFixture.water();
        var finished = run(sheet);
        var good = SimulatorResults.fitted(finished, sheet, FlowsheetFile.write(sheet));
        var model = SheetFixture.FLUIDS.model();
        assertNull(SimulatorResults.read(new byte[0], sheet, model));
        var other = good.clone();
        other[0] = (byte) (SimulatorResults.FORMAT + 1);
        assertNull(SimulatorResults.read(other, sheet, model), "a format this version does not write");
        assertNull(SimulatorResults.read(Arrays.copyOf(good, good.length - 1), sheet, model));
        assertNull(SimulatorResults.read(Arrays.copyOf(good, good.length + 1), sheet, model));
        var noise = new java.util.Random(3);
        for (int i = 0; i < 400; i++) {
            var damaged = good.clone();
            for (int hit = 0; hit < 4; hit++) damaged[noise.nextInt(damaged.length)] = (byte) noise.nextInt();
            SimulatorResults.read(damaged, sheet, model);
        }
    }

    @Test
    void aReplayPutsTheSheetAndResultsOnShow() throws Exception {
        var sheet = SheetFixture.water();
        var finished = run(sheet);
        byte[] bytes = FlowsheetFile.write(sheet);
        var body = SheetBody.pack(bytes, SimulatorResults.fitted(finished, sheet, bytes));
        var host = host(false, true, new ArrayList<>());
        assertTrue(host.reader(), "a body on its way takes no edit");
        assertFalse(host.canRun());
        var shown = host.replayed(body, SheetFixture.FLUIDS.model());
        assertArrayEquals(bytes, FlowsheetFile.write(shown));
        assertFalse(host.reader());
        assertNotNull(host.last(), "the replay's results stand on show");
        assertEquals(finished.run().status(), host.last().run().status());
        assertFalse(host.unsaved(shown));
    }

    /**
     * A stored run that stood steady starts the next run's loops, as a run on this client does (owner 2026-11-04, P1); one that did
     * not starts nothing. A column's warm start is client memory only, so the stored run carries none (see {@code FlowsheetColumnTest}).
     */
    @Test
    void aStoredSteadyRunStartsTheNextRunsLoops() throws Exception {
        var sheet = SheetFixture.water();
        var stored = replay(run(sheet), sheet);
        assertEquals(FlowsheetRun.Status.STEADY, stored.run().status());
        var lasts = new ArrayList<FlowsheetRun>();
        var runner = new SimulatorRunner(() -> SheetFixture.CATALOG, new SimulatorRunner.Engine() {
            @Override public FlowsheetRun run(Flowsheet s, com.wormzjl.icheme.science.material.MaterialCatalog c, Runnable p, FlowsheetRun last, com.wormzjl.icheme.science.flowsheet.FlowsheetSolver.Progress g) {
                lasts.add(last);
                return FlowsheetRun.none();
            }

            @Override public FlowsheetRun alone(Flowsheet s, String u, com.wormzjl.icheme.science.material.MaterialCatalog c, Runnable p, Map<String, FlowStream> t, com.wormzjl.icheme.science.flowsheet.FlowsheetSolver.Progress g) {
                return FlowsheetRun.none();
            }
        });
        runner.restore(new SimulatorHost.Finished(sheet, FlowsheetRun.refused(Note.error("sheet", "run.broken", "x")), 0));
        runner.run(sheet);
        for (int i = 0; i < 6000 && runner.running() != null; i++) Thread.sleep(10);
        runner.restore(stored);
        assertSame(stored, runner.last());
        runner.run(sheet);
        for (int i = 0; i < 6000 && runner.running() != null; i++) Thread.sleep(10);
        assertEquals(2, lasts.size());
        assertNull(lasts.getFirst(), "a stored run that failed starts nothing");
        assertSame(stored.run(), lasts.get(1));
    }

    @Test
    void resultsDropOnARevisionChange() throws Exception {
        var sheet = SheetFixture.water();
        var finished = run(sheet);
        var moved = new HashMap<>(finished.ranOn());
        moved.replaceAll((pack, token) -> token + "-old");
        var stale = new SimulatorHost.Finished(finished.sheet(), finished.run(), finished.seconds(), null, moved);
        byte[] bytes = FlowsheetFile.write(sheet);
        var host = host(false, true, new ArrayList<>());
        assertNotNull(host.replayed(SheetBody.pack(bytes, SimulatorResults.fitted(stale, sheet, bytes)), SheetFixture.FLUIDS.model()), "the sheet is a fact and stands");
        assertNull(host.last(), "the results go");
        assertEquals("store.results_dropped", host.said().key());
    }

    @Test
    void aSheetThisClientCannotReadSavesNothing() {
        var sent = new ArrayList<byte[]>();
        var host = host(false, true, sent);
        assertNull(host.replayed(new byte[]{1, 2, 3}, SheetFixture.FLUIDS.model()));
        assertTrue(host.reader());
        assertTrue(host.said().warn());
        host.store(SheetFixture.water());
        host.settle(SheetFixture.water());
        assertTrue(sent.isEmpty());
    }

    @Test
    void aReaderSendsNothingAndTheHolderNameShows() throws Exception {
        var sent = new ArrayList<byte[]>();
        var host = host(true, false, sent);
        assertEquals("Alex", host.holder());
        assertTrue(host.reader());
        assertFalse(host.canRun());
        var sheet = SheetFixture.water();
        host.opened(SheetFixture.line());
        assertFalse(host.unsaved(sheet));
        host.store(sheet);
        host.settle(sheet);
        host.ranOut(sheet);
        assertTrue(sent.isEmpty());
    }

    @Test
    void theEditorSendsOnSaveOnARunsEndAndOnCloseButNotOnAnEdit() throws Exception {
        var sent = new ArrayList<byte[]>();
        var host = host(false, false, sent);
        var sheet = SheetFixture.water();
        host.opened(sheet);
        assertFalse(host.unsaved(sheet));
        host.settle(sheet);
        host.ranOut(sheet);
        assertTrue(sent.isEmpty(), "nothing new, nothing sent");
        var edited = SheetFixture.line();
        assertTrue(host.unsaved(edited));
        host.ranOut(edited);
        assertTrue(sent.isEmpty(), "an edit alone sends nothing");
        host.run(edited);
        for (int i = 0; i < 6000 && host.running() != null; i++) Thread.sleep(10);
        host.ranOut(edited);
        assertEquals(1, sent.size(), "the run's end does");
        assertFalse(host.unsaved(edited));
        var contents = SheetBody.unpack(sent.getFirst());
        assertTrue(contents.results().length > 0, "with its results");
        host.ranOut(edited);
        host.settle(edited);
        assertEquals(1, sent.size());
        var other = SheetFixture.water();
        host.settle(other);
        assertEquals(2, sent.size(), "a close sends what changed");
        host.store(other);
        assertEquals(3, sent.size(), "as Save does, whatever changed");
    }

    @Test
    void aSheetOverTheBoundIsNotSentAndThePlayerHears() {
        var sent = new ArrayList<byte[]>();
        var host = host(false, false, sent);
        var units = new ArrayList<Flowsheet.Unit>();
        String name = "x".repeat(SheetBody.MAX_UNPACKED);
        units.add(new Flowsheet.Unit("a", "pump", name, 0, 0, SheetEditor.defaults("pump", SheetFixture::column)));
        host.store(new Flowsheet(SheetFixture.basis(), units, List.of(), com.wormzjl.icheme.science.flowsheet.FlowsheetSolver.Settings.DEFAULT));
        assertTrue(sent.isEmpty());
        assertEquals("store.too_large", host.said().key());
    }

    // ---- The clipboard

    private static SheetContext context(SheetEditor editor) {
        return new SheetContext(editor, new SheetState(editor.sheet(), null, null, 0, null), SheetUnits.DEFAULT, new SheetFixture.Texts(), SheetFixture.M, SheetFixture.FLUIDS, SimulatorHost.NONE, java.util.List.of());
    }

    @Test
    void aVesselsPhasesSumAndZerosStayOut() {
        var held = SheetClipboard.vessel("Tank", List.of("A", "B", "C"), new double[][]{{1, 0, 0}, {2, 0, 0}, {0, 5, 0}}, 313.15, 101325);
        assertEquals(Map.of("A", 3.0, "B", 5.0), held.amounts());
        assertEquals(313.15, held.temperature());
        assertEquals(101325, held.pressure());
    }

    @Test
    void pasteMapsByIdHoldsTheFlowAndNamesWhatItCouldNotPlace() {
        var feed = new Flowsheet.Feed(300, 2e5, new LinkedHashMap<>(Map.of("Water", 10.0)), List.of());
        var held = new SheetClipboard.Held("Tank", Map.of("Water", 1.0, "Methane", 3.0, "Unobtainium", 4.0, "Adamantium", 4.0), 400, 5e5);
        var axis = List.of("Methane", "Water");
        var plain = SheetClipboard.paste(held, feed, axis, false);
        assertEquals(2, plain.placed());
        assertEquals(List.of("Adamantium", "Unobtainium"), plain.left());
        assertEquals(7.5, plain.feed().amounts().get("Methane"), 1e-12);
        assertEquals(2.5, plain.feed().amounts().get("Water"), 1e-12);
        assertEquals(300, plain.feed().temperature());
        var state = SheetClipboard.paste(held, feed, axis, true);
        assertEquals(400, state.feed().temperature());
        assertEquals(5e5, state.feed().pressure());
        var empty = new Flowsheet.Feed(300, 2e5, Map.of(), List.of());
        assertEquals(100 / 3.6, SheetClipboard.paste(held, empty, axis, false).feed().amounts().values().stream().mapToDouble(Double::doubleValue).sum(), 1e-9, "a feed with no flow takes 100 kmol/h");
        var none = SheetClipboard.paste(new SheetClipboard.Held("Tank", Map.of("Unobtainium", 1.0), 400, 5e5), feed, axis, true);
        assertEquals(0, none.placed());
        assertEquals(300, none.feed().temperature(), "nothing placed, nothing changed");
        assertSame(feed, none.feed());
    }

    @Test
    void aFeedsDoorCopiesPastesAndSaysWhatItLeftOut() {
        var editor = SheetFixture.editor(SheetFixture.water());
        var x = context(editor);
        var view = new Door.View();
        view.tab = 1;
        SheetClipboard.copy(null);
        var empty = StreamDoors.door(x, "FEED", view);
        assertEquals("FEED", editor.stream("FEED").id());
        assertTrue(SimulatorPage.keys(empty.columns().getFirst()).containsAll(List.of("copy", "paste", "paste_state")));
        var parts = new LinkedHashMap<String, Double>();
        parts.put("Methane", 3.0);
        parts.put("Water", 1.0);
        parts.put("Unobtainium", 2.0);
        SheetClipboard.copy(new SheetClipboard.Held("Tank at 1, 2, 3", parts, 400, 5e5));
        var door = StreamDoors.door(x, "FEED", view);
        door.actions().button("paste_state");
        assertNotNull(view.said);
        assertTrue(view.said.contains("Unobtainium"), view.said);
        assertTrue(view.said.contains("Tank at 1, 2, 3"), view.said);
        assertEquals(400, editor.stream("FEED").feed().temperature());
        assertEquals(3, editor.stream("FEED").feed().amounts().get("Methane") / editor.stream("FEED").feed().amounts().get("Water"), 1e-9);
        assertEquals(view.said, StreamDoors.door(x, "FEED", view).status(), "the door says it");
        var other = new Door.View();
        other.tab = 1;
        StreamDoors.door(x, "FEED", other).actions().button("copy");
        assertEquals(Map.of("Methane", editor.stream("FEED").feed().amounts().get("Methane"), "Water", editor.stream("FEED").feed().amounts().get("Water")), SheetClipboard.held().amounts());
        assertEquals(400, SheetClipboard.held().temperature());
        SheetClipboard.copy(null);
    }

    /** No packet on Copy: what it calls reads the menu's data and writes the clipboard. */
    @Test
    void copyOnATankSendsNoPacketAndSolvesNothing() throws Exception {
        var screen = Files.readString(Path.of("src/main/java/com/wormzjl/icheme/client/gui/screens/inventory/FluidDeviceScreen.java"));
        int at = screen.indexOf("private void copyContents(){");
        assertTrue(at > 0);
        var body = screen.substring(at, screen.indexOf("\n    }", at));
        assertTrue(body.contains("menu.clientData()"), "it reads the last published snapshot");
        assertTrue(body.contains("SheetClipboard.copy(held)"));
        for (var word : List.of("FluidNetwork", "PacketDistributor", "send", "flash", "solve", "Thermodynamics")) assertFalse(body.contains(word), word);
        var clipboard = Files.readString(Path.of("src/main/java/com/wormzjl/icheme/client/gui/flowsheet/SheetClipboard.java"));
        for (var word : List.of("net.minecraft", "PacketDistributor", ".network.", "Thermodynamics")) assertFalse(clipboard.contains(word), word);
    }

    /** The server stores what it is sent, and a replay is a read: none of its code can reach a solver or the engine. */
    @Test
    void theServersHalfNamesNoSolverAndNoProcessState() throws Exception {
        var root = "src/main/java/com/wormzjl/icheme/";
        var files = List.of("network/ProcessSimulatorNetwork.java", "world/level/block/ProcessSimulatorBlock.java", "world/level/block/entity/ProcessSimulatorBlockEntity.java", "world/inventory/ProcessSimulatorMenu.java", "science/flowsheet/SheetStore.java", "science/flowsheet/SheetBody.java");
        for (var file : files) {
            var source = Files.readString(Path.of(root + file));
            for (var word : List.of("FlowsheetEngine", "FlowsheetSolver", "ProcessSolveServices", "FluidWorldAuthority", "SimulatorRunner", "FluidThermodynamics", "ColumnSolve", "V3ColumnCalculator", "client.gui", "getUpdateTag", "getUpdatePacket"))
                assertFalse(source.contains(word), file + " names " + word);
        }
    }

    @Test
    void autosaveSitsBesideItsFileAndListsByName() throws Exception {
        var folder = Path.of("build/tmp/simulator-autosave");
        Files.createDirectories(folder);
        try (var old = Files.list(folder)) {
            for (var file : old.toList()) Files.delete(file);
        }
        var t = new SheetFixture.Texts();
        assertEquals(folder.resolve("autosave-a.json"), FileColumns.autosave(folder, null, "a"));
        assertNotEquals(FileColumns.autosave(folder, null, "a"), FileColumns.autosave(folder, null, "b"), "a second block keeps the first's");
        assertEquals(folder.resolve("CDU train.autosave.json"), FileColumns.autosave(folder, "CDU train", "a"));
        FlowsheetFile.save(FileColumns.autosave(folder, "CDU train", "a"), SheetFixture.water());
        FlowsheetFile.save(FileColumns.autosave(folder, null, "3f2a1c9e-7b10"), SheetFixture.water());
        assertEquals(List.of("Autosave of CDU train", "Autosave of block 3f2a1c9e"), FileColumns.entries(folder, t).stream().map(FileColumns.Entry::name).sorted().toList());
        assertFalse(FileColumns.nameAllowed("x.autosave"), "no name a player gives can be overwritten by an autosave");
        assertFalse(FileColumns.nameAllowed("Autosave"));
        assertFalse(FileColumns.nameAllowed("autosave-a"));
        assertTrue(t.missing.isEmpty(), () -> "missing " + t.missing);
    }

    private static FileColumns.ColumnTemplate template(boolean bundled) {
        var column = new com.wormzjl.icheme.science.column.v3.V3ColumnTemplate(0, 42, 38, 300, 0, 1, 1.4e5, 0, List.of(), List.of(), List.of());
        return new FileColumns.ColumnTemplate("One", column, bundled, "one.json");
    }

    @Test
    void deleteIsOffOnABundledTemplateAndSaysWhy() {
        var t = new SheetFixture.Texts();
        var list = List.of(template(true), template(false));
        assertEquals("A bundled template stays: only your own can go.", FileColumns.columnTemplates(list, 0, SheetUnits.DEFAULT, t).off()[1]);
        assertNull(FileColumns.columnTemplates(list, 1, SheetUnits.DEFAULT, t).off()[1], "your own can go");
        assertNotNull(FileColumns.columnTemplates(list, -1, SheetUnits.DEFAULT, t).off()[1], "nothing chosen, nothing to delete");
        assertNull(FileColumns.columnTemplates(list, 0, SheetUnits.DEFAULT, t).off()[3], "Load stays on");
        assertEquals(5, FileColumns.open(List.of(), -1, t).off().length, "a place for each key");
        assertTrue(t.missing.isEmpty(), () -> "missing " + t.missing);
    }

    private static Flowsheet current(List<Flowsheet.Unit> units, List<Flowsheet.Stream> streams) {
        return com.wormzjl.icheme.science.flowsheet.RevisionNotice.stamped(new Flowsheet(new Flowsheet.Basis(SheetFixture.NETWORK, SheetFixture.FLUIDS.pack().revision(), SheetFixture.FLUIDS.axis()), units, streams, FlowsheetSolver.Settings.DEFAULT), SheetFixture.CATALOG);
    }

    private static List<FileColumns.Change> changes(Flowsheet sheet, SheetFixture.Texts t) {
        return changes(sheet, SheetFixture.CATALOG, t);
    }

    private static List<FileColumns.Change> changes(Flowsheet sheet, com.wormzjl.icheme.science.material.MaterialCatalog catalog, SheetFixture.Texts t) {
        return FileColumns.changes(sheet, SheetFixture.FLUIDS, catalog, SheetFixture::column, t);
    }

    /** The catalog with one record of {@code path} edited by {@code edit}. */
    private static com.wormzjl.icheme.science.material.MaterialCatalog edited(String path, java.util.function.Consumer<JsonObject> edit) {
        var resources = new java.util.HashMap<>(SheetFixture.CATALOG.resources());
        var record = com.google.gson.JsonParser.parseString(resources.get(path)).getAsJsonObject();
        edit.accept(record);
        resources.put(path, record.toString());
        return com.wormzjl.icheme.science.material.MaterialCatalog.parse(resources);
    }

    /**
     * The file-open notice compares the identity of the data the sheet's compounds run on, which the file holds since it was written
     * (batch 2026-11-04-refinery-compounds, W4): a package record edit that changes no physics, or a dataset of a compound the sheet
     * does not feed, says nothing; a dataset of a compound it feeds says its data moved.
     */
    @Test
    void theDataNoticeComparesTheDataOfTheSheetsOwnCompounds() {
        var t = new SheetFixture.Texts();
        var amounts = new LinkedHashMap<String, Double>();
        amounts.put("Methane", 1.0);
        var feed = new Flowsheet.Stream("f", "F", null, null, new Flowsheet.Feed(300, 2e5, amounts, List.of()), List.of(new Flowsheet.Point(10, 70)), new JsonObject());
        var sheet = current(List.of(), List.of(feed));
        assertEquals(List.of(), changes(sheet, t));
        java.util.function.Consumer<JsonObject> bump = r -> r.addProperty("revision", r.get("revision").getAsString() + "-edited");
        String root = "data/icheme/materials/";
        assertEquals(List.of(), changes(sheet, edited(root + "packages/tjl20_nitrogen.json", bump), t), "a package record edit changes no physics");
        assertEquals(List.of(), changes(sheet, edited(root + "properties/crude_pc12.json", bump), t), "a compound the sheet does not feed");
        var moved = changes(sheet, edited(root + "properties/tjl20_methane.json", bump), t);
        assertEquals(List.of("Data"), moved.stream().map(FileColumns.Change::kind).toList());
        assertEquals(t.get("flowsheet.file.change_revision"), moved.getFirst().text());
        assertTrue(t.missing.isEmpty(), () -> "missing " + t.missing);
    }

    @Test
    void aSheetOfThisVersionOpensWithNothingToSay() {
        var t = new SheetFixture.Texts();
        var units = new ArrayList<Flowsheet.Unit>();
        int x = 40;
        for (var type : SimulatorPage.litTypes()) {
            units.add(new Flowsheet.Unit("u" + x, type, "U-" + x, x, 60, SheetEditor.defaults(type, SheetFixture::column)));
            x += 80;
        }
        var amounts = new LinkedHashMap<String, Double>();
        amounts.put(SheetFixture.FLUIDS.axis().getFirst(), 1.0);
        var feed = new Flowsheet.Stream("f", "F", null, null, new Flowsheet.Feed(300, 2e5, amounts, List.of()), List.of(new Flowsheet.Point(10, 70)), new JsonObject());
        assertEquals(List.of(), changes(current(units, List.of(feed)), t));
    }

    @Test
    void aFileOlderThanThisVersionSaysEachKindOfChange() {
        var t = new SheetFixture.Texts();
        var pump = SheetEditor.defaults("pump", SheetFixture::column).json().deepCopy();
        pump.remove("rise");
        var units = List.of(new Flowsheet.Unit("p", "pump", "P-201", 40, 60, new com.wormzjl.icheme.science.flowsheet.Params(pump)),
                new Flowsheet.Unit("h", "old_heater", "H-201", 140, 60, new com.wormzjl.icheme.science.flowsheet.Params(new JsonObject())));
        var amounts = new java.util.LinkedHashMap<String, Double>();
        amounts.put("Water", 99.0);
        amounts.put("Bitumen", 1.0);
        var feed = new Flowsheet.Stream("f", "FEED-2", null, new Flowsheet.End("p", "in"), new Flowsheet.Feed(300, 2e5, amounts, List.of()), List.of(new Flowsheet.Point(10, 70)), new JsonObject());
        var sheet = new Flowsheet(new Flowsheet.Basis(SheetFixture.NETWORK, "r1", SheetFixture.FLUIDS.axis()), units, List.of(feed), FlowsheetSolver.Settings.DEFAULT);
        var lines = changes(sheet, t);
        assertEquals(List.of("Unit", "Component", "Field", "Data"), lines.stream().map(FileColumns.Change::kind).toList());
        assertTrue(lines.get(0).text().startsWith("H-201 Old heater"), lines.get(0).text());
        assertEquals("Bitumen left the package: 1.00 mole % of FEED-2 parked, not solved.", lines.get(1).text());
        assertEquals("P-201 has a new setting, Rise: it starts at its default.", lines.get(2).text());
        assertEquals("The data of the sheet's compounds changed since it was saved. Results dropped: run again.", lines.get(3).text());
        assertEquals(List.of(true, true, false, false), lines.stream().map(FileColumns.Change::warn).toList(), "what left the solve warns; the rest only says");
        assertTrue(t.missing.isEmpty(), () -> "missing " + t.missing);
    }

    @Test
    void theChangesDialogShowsWhatFitsAboveItsFoot() {
        var line = new FileColumns.Change("Unit", "H-201: kept.", true);
        assertEquals(3, FileColumns.fits(List.of(line, line, line), SheetFixture.M, 400, 51));
        assertEquals(2, FileColumns.fits(List.of(line, line, line), SheetFixture.M, 400, 50));
    }

    @Test
    void aDrumsStreamsTableHasItsOpenOutlet() {
        var units = List.of(new Flowsheet.Unit("d", "drum", "D-101", 100, 60, SheetEditor.defaults("drum", SheetFixture::column)));
        var streams = List.of(new Flowsheet.Stream("f", "F", null, new Flowsheet.End("d", "in"), SheetFixture.water(100), List.of(new Flowsheet.Point(10, 70)), new JsonObject()),
                new Flowsheet.Stream("g", "G", new Flowsheet.End("d", "gas"), null, null, List.of(new Flowsheet.Point(400, 40)), new JsonObject()));
        var sheet = new Flowsheet(SheetFixture.basis(), units, streams, FlowsheetSolver.Settings.DEFAULT);
        var run = com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.run(sheet, SheetFixture.CATALOG, () -> {
        });
        var editor = SheetFixture.editor(sheet);
        var x = new SheetContext(editor, new SheetState(editor.sheet(), sheet, run, 1, null), SheetUnits.DEFAULT, new SheetFixture.Texts(), SheetFixture.M, SheetFixture.FLUIDS, SimulatorHost.NONE, java.util.List.of());
        var open = x.openOutlets(units.getFirst());
        assertEquals(java.util.Set.of("water"), open.keySet(), "the water leaves by the port with no stream on it; the empty oil outlet says nothing");
        assertEquals(100, open.get("water").moles(), 1e-6);
        assertEquals("Free water (open)", x.text("door.open_outlet", StreamDoors.portName(x, "water")));
    }

    @Test
    void theDialogsKeysAreThereAndEveryTextHasItsLine() {
        var t = new SheetFixture.Texts();
        assertEquals(List.of("Cancel", "Folder", "From clipboard", "Delete", "Open"), List.of(FileColumns.open(List.of(), -1, t).keys()));
        assertEquals(FileColumns.OPEN_OPEN, FileColumns.open(List.of(), -1, t).primary(), "Open stays the primary key, at the right");
        assertEquals(List.of("Cancel", "Delete", "Save current", "Load"), List.of(FileColumns.columnTemplates(List.of(), -1, SheetUnits.DEFAULT, t).keys()));
        var stream = new FileColumns.StreamTemplate("Air", Map.of("Water", 1.0), 300, 2e5, 2);
        var rows = FileColumns.streamTemplates(List.of(stream, new FileColumns.StreamTemplate("Oil", Map.of("Water", 1.0), Double.NaN, Double.NaN, Double.NaN)), -1, SheetUnits.DEFAULT, t).rows();
        assertTrue(rows.get(0).cells()[2].contains(","), "a template's conditions show");
        assertEquals("", rows.get(1).cells()[2], "and none where it has none");
        FileColumns.unsaved(t);
        for (var key : List.of("store.saved", "store.design_saved", "store.design_refused", "store.too_large", "store.damaged", "store.parts", "store.read_only", "store.kept", "store.unreadable", "store.results_dropped", "store.autosave_failed", "file.on_clipboard", "file.clipboard_refused", "file.save_failed", "file.template_title", "file.column_title", "file.template_saved", "file.column_no_feed", "file.unsaved_title", "file.unsaved_foot", "file.discard", "file.to_clipboard", "door.copied", "door.pasted", "door.pasted_state", "door.paste_left", "door.clipboard_holds", "door.clipboard_stream", "clipboard.tank", "panel.copied"))
            t.get("flowsheet." + key);
        assertTrue(t.missing.isEmpty(), () -> "missing " + t.missing);
    }
}
