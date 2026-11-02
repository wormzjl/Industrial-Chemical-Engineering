package com.wormzjl.icheme.client.gui.flowsheet;

import com.google.gson.JsonParser;
import com.wormzjl.icheme.client.gui.common.kit.Column;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.science.column.v3.V3ColumnTemplate;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetEngine;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.Note;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.Report;
import com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The run half of the simulator (W7 of batch 2026-11-02-process-simulator) on water, which runs in no time. The column's part is in
 * {@link SimulatorRunTest}. A latch holds a run still, so nothing here reads a clock.
 */
class SimulatorRunnerTest {
    private static final long GUARD = 60;

    /** Stands in front of the engine: holds a run at its door, and notes what thread each checkpoint fires on. */
    private static class Gate implements SimulatorRunner.Engine {
        final CountDownLatch open = new CountDownLatch(1), entered = new CountDownLatch(1);
        final List<Thread> threads = Collections.synchronizedList(new ArrayList<>());
        final List<FlowsheetRun> lasts = Collections.synchronizedList(new ArrayList<>());

        Runnable watched(Runnable checkpoint) {
            return () -> {
                threads.add(Thread.currentThread());
                checkpoint.run();
            };
        }

        void hold() {
            entered.countDown();
            try {
                assertTrue(open.await(GUARD, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override public FlowsheetRun run(Flowsheet sheet, MaterialCatalog catalog, Runnable checkpoint, FlowsheetRun last, FlowsheetSolver.Progress progress) {
            hold();
            lasts.add(last);
            return FlowsheetEngine.run(sheet, catalog, watched(checkpoint), last, progress);
        }

        @Override public FlowsheetRun alone(Flowsheet sheet, String unit, MaterialCatalog catalog, Runnable checkpoint, Map<String, FlowStream> streams, FlowsheetSolver.Progress progress) {
            hold();
            return FlowsheetEngine.alone(sheet, unit, catalog, watched(checkpoint), streams, progress);
        }
    }

    private static void ended() throws InterruptedException {
        for (var thread : Thread.getAllStackTraces().keySet())
            if (thread.getName().equals(SimulatorRunner.THREAD)) {
                thread.join(TimeUnit.SECONDS.toMillis(GUARD));
                assertFalse(thread.isAlive(), "the run thread ended");
            }
    }

    private static SimulatorHost host(SimulatorRunner runner, boolean reader) {
        return new SimulatorHost() {
            @Override public SimulatorRunner runner() {
                return runner;
            }

            @Override public boolean reader() {
                return reader;
            }
        };
    }

    private static SimulatorRunner runner(SimulatorRunner.Engine engine) {
        return new SimulatorRunner(() -> SheetFixture.CATALOG, engine);
    }

    private static SimulatorRunner runner() {
        return runner(SimulatorRunner.Engine.REAL);
    }

    @AfterEach
    void noThreadStays() throws InterruptedException {
        ended();
    }

    @Test
    void runReturnsBeforeTheEngineStartsAndEveryCheckpointFiresOnTheRunThread() throws InterruptedException {
        var gate = new Gate();
        var runner = runner(gate);
        var sheet = SheetFixture.water();
        runner.run(sheet);
        assertTrue(gate.threads.isEmpty(), "run() has returned, and the engine stands at its door");
        assertTrue(gate.entered.await(GUARD, TimeUnit.SECONDS));
        var starting = runner.running();
        assertNotNull(starting);
        assertNull(starting.unit(), "starting");
        assertNull(runner.last());
        gate.open.countDown();
        ended();
        assertNull(runner.running());
        assertEquals(FlowsheetRun.Status.STEADY, runner.last().run().status());
        assertFalse(gate.threads.isEmpty());
        for (var thread : gate.threads) {
            assertEquals(SimulatorRunner.THREAD, thread.getName());
            assertNotEquals(Thread.currentThread(), thread);
            assertTrue(thread.isDaemon());
            assertEquals(Thread.MIN_PRIORITY, thread.getPriority());
        }
    }

    @Test
    void oneRunAtATime() throws InterruptedException {
        var gate = new Gate();
        var runner = runner(gate);
        runner.run(SheetFixture.water());
        assertTrue(gate.entered.await(GUARD, TimeUnit.SECONDS));
        runner.run(SheetFixture.water());
        runner.alone(SheetFixture.water(), "V-1");
        long threads = Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().equals(SimulatorRunner.THREAD)).count();
        assertEquals(1, threads);
        gate.open.countDown();
    }

    @Test
    void aRunIsOnItsOwnCopyOfTheSheet() throws InterruptedException {
        var gate = new Gate();
        var runner = runner(gate);
        var sheet = SheetFixture.water();
        var rise = sheet.units().getFirst().params().json();
        runner.run(sheet);
        assertTrue(gate.entered.await(GUARD, TimeUnit.SECONDS));
        rise.addProperty("rise", 9e5);
        gate.open.countDown();
        ended();
        var finished = runner.last();
        assertNotSame(sheet, finished.sheet());
        assertEquals(2e5, finished.sheet().units().getFirst().params().number("rise"), "what the run made it from");
        assertEquals(8e5 + 2e5, finished.run().streams().get("A").pressure(), 1, "and what it ran");
    }

    @Test
    void aSheetCopyReadsAsTheSheetAndSharesNoJson() {
        var sheet = SheetFixture.line();
        var copy = sheet.copy();
        var state = new SheetState(sheet, copy, FlowsheetRun.none(), 0, null);
        for (var unit : sheet.units()) assertFalse(state.stale(unit.id()), "nothing moved");
        assertEquals(sheet.units().getFirst().params().json(), copy.units().getFirst().params().json());
        assertNotSame(sheet.units().getFirst().params().json(), copy.units().getFirst().params().json());
        assertNotSame(sheet.streams().getFirst().unknown(), copy.streams().getFirst().unknown());
    }

    @Test
    void anEditIsRefusedDuringARunAndAcceptedAfter() throws InterruptedException {
        var gate = new Gate();
        var runner = runner(gate);
        var host = host(runner, false);
        var editor = SheetFixture.editor(SheetFixture.water());
        assertTrue(editor.moveUnit("P-1", 64, 32, null));
        runner.run(editor.sheet());
        host.lock(editor);
        assertTrue(editor.locked());
        assertFalse(editor.moveUnit("P-1", 96, 64, null));
        assertEquals(SheetEditor.RUNNING, editor.refusal().key());
        assertFalse(editor.canUndo(), "Undo waits too");
        assertFalse(editor.undo());
        gate.open.countDown();
        ended();
        host.lock(editor);
        assertFalse(editor.locked(), "the run's end lifts the lock");
        assertNull(editor.refusal(), "and what it refused");
        assertTrue(editor.moveUnit("P-1", 96, 64, null));
    }

    @Test
    void aRunsEndLeavesAReaderLocked() throws InterruptedException {
        var runner = runner();
        var host = host(runner, true);
        var editor = SheetFixture.editor(SheetFixture.water());
        runner.run(editor.sheet());
        ended();
        host.lock(editor);
        assertTrue(editor.locked());
        assertFalse(editor.moveUnit("P-1", 64, 32, null));
        assertEquals(SheetEditor.LOCKED, editor.refusal().key());
    }

    /** Spins on its checkpoint as a long solve does, eating the first few Stops as a catch inside one might. */
    private static class Spin extends Gate {
        final AtomicBoolean eaten = new AtomicBoolean();

        @Override public FlowsheetRun run(Flowsheet sheet, MaterialCatalog catalog, Runnable checkpoint, FlowsheetRun last, FlowsheetSolver.Progress progress) {
            entered.countDown();
            int eats = 3;
            while (eats > 0) {
                try {
                    checkpoint.run();
                } catch (CancellationException stop) {
                    eaten.set(true);
                    eats--;
                }
            }
            return FlowsheetEngine.run(sheet, catalog, checkpoint, last, progress);
        }
    }

    @Test
    void stopStaysSetSoACatchThatEatsOneMeetsTheNext() throws InterruptedException {
        var spin = new Spin();
        var runner = runner(spin);
        runner.run(SheetFixture.water());
        assertTrue(spin.entered.await(GUARD, TimeUnit.SECONDS));
        runner.stop();
        ended();
        assertTrue(spin.eaten.get());
        assertNull(runner.last(), "Stop before the first calculation leaves nothing: what was on show stands");
        assertNull(runner.running());
    }

    @Test
    void closingEndsTheRunLeavesNoThreadAndDropsItsResult() throws InterruptedException {
        var spin = new Spin();
        var runner = runner(spin);
        runner.run(SheetFixture.water());
        assertTrue(spin.entered.await(GUARD, TimeUnit.SECONDS));
        runner.close();
        ended();
        assertNull(runner.last());
        runner.run(SheetFixture.water());
        assertNull(runner.running(), "a closed runner starts nothing");
    }

    @Test
    void aStoppedRunKeepsWhatItHas() throws InterruptedException {
        var gate = new Gate() {
            @Override public FlowsheetRun run(Flowsheet sheet, MaterialCatalog catalog, Runnable checkpoint, FlowsheetRun last, FlowsheetSolver.Progress progress) {
                int[] seen = {0};
                return FlowsheetEngine.run(sheet, catalog, () -> {
                    if (++seen[0] == 7) throw new CancellationException();
                    checkpoint.run();
                }, last, progress);
            }
        };
        var runner = runner(gate);
        runner.run(SheetFixture.water());
        ended();
        var run = runner.last().run();
        assertEquals(FlowsheetRun.Status.STOPPED, run.status());
        assertEquals(FlowsheetRun.Outcome.SOLVED, run.outcomes().get("P-1"), "what finished stays");
        assertTrue(run.log().isEmpty(), "a stop blames nothing");
    }

    @Test
    void whatBreaksARunSaysSoAndLiftsTheLock() throws InterruptedException {
        var gate = new Gate() {
            @Override public FlowsheetRun run(Flowsheet sheet, MaterialCatalog catalog, Runnable checkpoint, FlowsheetRun last, FlowsheetSolver.Progress progress) {
                throw new StackOverflowError("deep");
            }
        };
        var runner = runner(gate);
        runner.run(SheetFixture.water());
        ended();
        assertNull(runner.running());
        var run = runner.last().run();
        assertEquals(FlowsheetRun.Status.FAILED, run.status());
        assertEquals("run.broken", run.log().getFirst().key());
        assertFalse(new SheetFixture.Texts().get("flowsheet.run.broken", "x").startsWith("flowsheet."));
    }

    @Test
    void theNextRunStartsFromTheLastSteadyOne() throws InterruptedException {
        var gate = new Gate() {
            int runs;

            @Override public FlowsheetRun run(Flowsheet sheet, MaterialCatalog catalog, Runnable checkpoint, FlowsheetRun last, FlowsheetSolver.Progress progress) {
                lasts.add(last);
                if (++runs != 2) return FlowsheetEngine.run(sheet, catalog, checkpoint, last, progress);
                // The second run stops partway, which leaves the steady one standing.
                int[] seen = {0};
                return FlowsheetEngine.run(sheet, catalog, () -> {
                    if (++seen[0] == 7) throw new CancellationException();
                }, last, progress);
            }
        };
        var runner = runner(gate);
        var sheet = SheetFixture.water();
        runner.run(sheet);
        ended();
        var steady = runner.last().run();
        assertEquals(FlowsheetRun.Status.STEADY, steady.status());
        runner.run(sheet);
        ended();
        assertEquals(FlowsheetRun.Status.STOPPED, runner.last().run().status());
        runner.run(sheet);
        ended();
        assertNull(gate.lasts.get(0));
        assertSame(steady, gate.lasts.get(1));
        assertSame(steady, gate.lasts.get(2), "a stopped run is no first estimate");
    }

    @Test
    void theCatalogIsTakenOnTheCallerAtTheRunsStart() throws InterruptedException {
        var threads = new ArrayList<Thread>();
        var runner = new SimulatorRunner(() -> {
            threads.add(Thread.currentThread());
            return SheetFixture.CATALOG;
        });
        runner.run(SheetFixture.water());
        ended();
        assertEquals(List.of(Thread.currentThread()), threads);
    }

    @Test
    void theStatusLineSaysWhereARunIs() {
        var texts = new SheetFixture.Texts();
        var sheet = SheetFixture.water();
        assertEquals("Starting, 0.2 s.", new SheetState(sheet, null, null, 0, new SheetState.Running(.2, 0, 0, null, null)).status(texts, SheetUnits.DEFAULT, false).text());
        assertEquals("Running, 1.0 s: P-1.", new SheetState(sheet, null, null, 0, new SheetState.Running(1, 0, 30, "P-1", null)).status(texts, SheetUnits.DEFAULT, false).text());
        assertEquals("Running, 3.1 s: pass 2 of at most 30, V-1 from its last state.", new SheetState(sheet, null, null, 0, new SheetState.Running(3.1, 2, 30, "V-1", Report.Start.LAST)).status(texts, SheetUnits.DEFAULT, false).text());
        assertEquals("Running, 2.0 s: pass 1 of at most 30, V-1 from its learned start.", new SheetState(sheet, null, null, 0, new SheetState.Running(2, 1, 30, "V-1", Report.Start.LEARNED)).status(texts, SheetUnits.DEFAULT, false).text());
        assertEquals(SheetState.Tone.ACTIVE, new SheetState(sheet, null, null, 0, new SheetState.Running(1, 1, 30, "V-1", null)).tone("V-1"));
        assertTrue(texts.missing.isEmpty(), texts.missing.toString());
    }

    @Test
    void aStateTheScreenKeepsTakesARunningClockWithoutWorkingItsSignaturesAgain() {
        var sheet = SheetFixture.water();
        var base = new SheetState(sheet, sheet, FlowsheetRun.none(), 0, null);
        var running = base.running(new SheetState.Running(1, 1, 30, "P-1", null));
        assertTrue(running.running());
        assertFalse(base.running());
        assertSame(base.results(), running.results(), "the run's results are the same object");
    }

    private static SheetContext context(SheetEditor editor, SimulatorHost host, SheetFixture.Texts texts) {
        return new SheetContext(editor, SheetState.of(editor.sheet(), host.last()), SheetUnits.DEFAULT, texts, SheetFixture.M, SheetFixture.FLUIDS, host, List.of());
    }

    private static Column.Button key(Door door, String key) {
        return door.footer().stream().filter(button -> button.key().equals(key)).findFirst().orElseThrow();
    }

    /** A column on a fed line of water, with its required outlets left free. */
    private static Flowsheet fedColumn(boolean fed) {
        var units = List.of(new Flowsheet.Unit("T", "column", "T-1", 200, 40, SheetFixture.column()));
        var feed = fed ? SheetFixture.water(100) : null;
        return new Flowsheet(SheetFixture.basis(), units, List.of(new Flowsheet.Stream("F", "FEED", null, new Flowsheet.End("T", "feed"), feed)), FlowsheetSolver.Settings.DEFAULT);
    }

    @Test
    void solveAloneIsOffWithAReasonWhereTheUnitHasNoStream() throws InterruptedException {
        var texts = new SheetFixture.Texts();
        var nowhere = SheetFixture.editor(fedColumn(true));
        var off = key(ColumnDoors.door(new SheetContext(nowhere, new SheetState(nowhere.sheet(), null, null, 0, null), SheetUnits.DEFAULT, texts, SheetFixture.M, SheetFixture.FLUIDS), "T", new Door.View()), "solve");
        assertFalse(off.active(), "nothing to run on");
        assertEquals(texts.get("flowsheet.hint.run"), off.hint());
        var gate = new Gate();
        var runner = runner(gate);
        var host = host(runner, false);
        var dry = SheetFixture.editor(fedColumn(false));
        var none = key(ColumnDoors.door(context(dry, host, texts), "T", new Door.View()), "solve");
        assertFalse(none.active(), "no stream, and no feed on its line");
        assertEquals(texts.get("flowsheet.door.solve_no_stream"), none.hint());
        var fed = SheetFixture.editor(fedColumn(true));
        assertTrue(key(ColumnDoors.door(context(fed, host, texts), "T", new Door.View()), "solve").active(), "a line that feeds it needs no run");
        runner.run(fed.sheet());
        assertTrue(gate.entered.await(GUARD, TimeUnit.SECONDS));
        host.lock(fed);
        var busy = ColumnDoors.door(context(fed, host, texts), "T", new Door.View());
        assertFalse(key(busy, "solve").active(), "not during a run");
        assertEquals(texts.get("flowsheet.door.locked"), key(busy, "solve").hint());
        assertFalse(key(busy, "templates").active());
        gate.open.countDown();
        assertTrue(texts.missing.isEmpty(), texts.missing.toString());
    }

    @Test
    void aUnitSolvedAloneChangesItsOwnLookAndGreysWhatLiesAfterIt() throws InterruptedException {
        var runner = runner();
        var host = host(runner, false);
        var editor = SheetFixture.editor(SheetFixture.water());
        runner.run(editor.sheet());
        ended();
        var ran = runner.last().run();
        assertTrue(SheetState.of(editor.sheet(), runner.last()).steady());
        var json = editor.unit("V-1").params().json().deepCopy();
        json.addProperty("drop", 3e5);
        assertTrue(editor.setParams("V-1", new Params(json), null));
        var edited = SheetState.of(editor.sheet(), runner.last());
        assertTrue(edited.stale("V-1"), "greyed by the edit");
        assertTrue(edited.stale("P-2"), "and what follows it");
        runner.alone(editor.sheet(), "V-1");
        ended();
        var alone = runner.last();
        assertEquals("V-1", alone.alone().unit());
        assertEquals(FlowsheetRun.Status.STEADY, alone.run().status(), "the sheet's own word stands");
        assertSame(ran.loops(), alone.run().loops());
        var state = SheetState.of(editor.sheet(), alone);
        assertFalse(state.stale("V-1"), "solved on what it has");
        assertFalse(state.stale("B"), "what it leaves is known");
        assertEquals(SheetState.Tone.SOLVED, state.tone("V-1"));
        assertTrue(state.stale("P-2"), "what follows has not run on it");
        assertTrue(state.stale("OUT"));
        assertFalse(state.steady(), "so Sizing stays off");
        var again = editor.unit("V-1").params().json().deepCopy();
        again.addProperty("drop", 2e5);
        assertTrue(editor.setParams("V-1", new Params(again), null));
        assertTrue(SheetState.of(editor.sheet(), alone).stale("V-1"), "an edit since greys it again");
        assertTrue(editor.undo());
        assertEquals(ran.streams().get("A").pressure() - 3e5, alone.run().streams().get("B").pressure(), 1);
        var texts = new SheetFixture.Texts();
        assertTrue(state.status(texts, SheetUnits.DEFAULT, false).text().contains("V-1 solved alone in"), () -> state.status(texts, SheetUnits.DEFAULT, false).text());
        runner.run(editor.sheet());
        ended();
        var whole = SheetState.of(editor.sheet(), runner.last());
        assertTrue(whole.steady(), "the next Run settles it");
        assertNull(runner.last().alone());
    }

    @Test
    void aSolveAloneThatFailsOrStopsChangesNothingOnTheSheet() throws InterruptedException {
        var runner = runner();
        var editor = SheetFixture.editor(SheetFixture.water());
        runner.run(editor.sheet());
        ended();
        var before = runner.last();
        var json = editor.unit("V-1").params().json().deepCopy();
        json.addProperty("drop", 3e9);
        assertTrue(editor.setParams("V-1", new Params(json), null));
        runner.alone(editor.sheet(), "V-1");
        ended();
        var failed = runner.last();
        assertEquals(FlowsheetRun.Status.FAILED, failed.alone().status());
        assertSame(before.run(), failed.run());
        assertSame(before.sheet(), failed.sheet());
        var texts = new SheetFixture.Texts();
        var line = SheetState.of(editor.sheet(), failed).status(texts, SheetUnits.DEFAULT, false).text();
        assertTrue(line.contains("V-1 failed alone"), line);
        var gate = new Gate() {
            @Override public FlowsheetRun alone(Flowsheet sheet, String unit, MaterialCatalog catalog, Runnable checkpoint, Map<String, FlowStream> streams, FlowsheetSolver.Progress progress) {
                return FlowsheetEngine.alone(sheet, unit, catalog, () -> {
                    throw new CancellationException();
                }, streams, progress);
            }
        };
        var stopped = runner(gate);
        stopped.alone(SheetFixture.water(), "P-1");
        ended();
        assertEquals(FlowsheetRun.Status.STOPPED, stopped.last().alone().status());
        assertTrue(stopped.last().run().streams().isEmpty());
    }

    @Test
    void aTemplateAppliesOnTheFeedAndUndoTakesItBack() {
        var texts = new SheetFixture.Texts();
        var editor = SheetFixture.editor(fedColumn(true));
        var before = editor.unit("T").params().json().deepCopy();
        var x = new SheetContext(editor, new SheetState(editor.sheet(), null, null, 0, null), SheetUnits.DEFAULT, texts, SheetFixture.M, SheetFixture.FLUIDS);
        var template = new V3ColumnTemplate(720, 12, 6, 310, 8, 2, 1.5, 3, List.of(new V3ColumnTemplate.Draw(4, 72)), List.of(new V3ColumnTemplate.Steam(11, 36, 500)),
                List.of(new V3ColumnTemplate.Pumparound(5, 3, 2, com.wormzjl.icheme.science.column.v3.V3PumparoundSpec.Split.UNIFORM)));
        assertTrue(ColumnDoors.template(x, "T", template));
        var door = ColumnDoor.of(editor.unit("T").params());
        assertEquals(10, door.stages());
        assertEquals(5, door.feedStage());
        assertEquals(1.5e5, door.topPressure(), 1e-6);
        assertEquals(310, door.condenserTemperature());
        assertEquals(2, door.reflux(), "a ratio stands");
        double k = 360.0 / 720;
        assertEquals(8e6 * k, door.reboilerDuty(), 1e-3, "the feed is 100 mol/s, 360 kmol/h: half the design feed");
        assertEquals(72 * k / 3.6, door.draws().getFirst().flow(), 1e-9);
        assertEquals(3, door.draws().getFirst().tray());
        assertEquals(36 * k / 3.6, door.steam().getFirst().flow(), 1e-9);
        assertEquals(500, door.steam().getFirst().temperature());
        assertEquals(-2e6 * k, door.pumparounds().getFirst().duty(), 1e-3);
        assertEquals(1, door.draws().size());
        assertTrue(editor.undo());
        assertEquals(before, editor.unit("T").params().json(), "Undo takes it back whole");
    }

    @Test
    void aTemplateKeepsTheDoorsOwnSettingsAndTheStreamsOnItsRows() {
        var door = SheetFixture.column();
        door.json().addProperty("condenser_drop", 7e4);
        var rows = door.rows("pumparounds");
        rows.getFirst().json().addProperty("drop", 3e4);
        var firstId = rows.getFirst().text("id", "");
        var template = new V3ColumnTemplate(0, 12, 6, 310, 8, 2, 1.5, 3, List.of(), List.of(), List.of(new V3ColumnTemplate.Pumparound(5, 3, 2, com.wormzjl.icheme.science.column.v3.V3PumparoundSpec.Split.UNIFORM)));
        var applied = ColumnDoor.of(ColumnDoor.template(door, template));
        assertEquals(7e4, applied.condenserDrop(), "not the template's to say");
        assertEquals(ColumnDoor.of(door).condenser(), applied.condenser());
        assertEquals(ColumnDoor.of(door).packageId(), applied.packageId());
        assertEquals(firstId, applied.pumparounds().getFirst().id(), "so a stream on that row stays on its port");
        assertEquals(3e4, applied.pumparounds().getFirst().drop());
        assertTrue(applied.draws().isEmpty());
        assertEquals(3, ColumnDoor.of(door).draws().size(), "the door it came from is not touched");
    }

    @Test
    void newRowsTakeIdsNoOldRowHas() {
        var door = SheetFixture.column();
        var json = door.json().deepCopy();
        var one = new com.google.gson.JsonArray();
        var row = json.getAsJsonArray("draws").get(0).getAsJsonObject().deepCopy();
        row.addProperty("id", "7");
        one.add(row);
        json.add("draws", one);
        var template = new V3ColumnTemplate(0, 12, 6, 310, 8, 2, 1.5, 3, List.of(new V3ColumnTemplate.Draw(4, 72), new V3ColumnTemplate.Draw(5, 72), new V3ColumnTemplate.Draw(6, 72)), List.of(), List.of());
        var draws = ColumnDoor.of(ColumnDoor.template(new Params(json), template)).draws();
        assertEquals(List.of("7", "8", "9"), draws.stream().map(ColumnDoor.Draw::id).toList());
    }

    @Test
    void everyBundledTemplateMakesADoor() throws Exception {
        var texts = new SheetFixture.Texts();
        var listed = new ArrayList<FileColumns.ColumnTemplate>();
        try (var index = getClass().getResourceAsStream("/assets/icheme/columnpreset/index.json")) {
            for (var file : JsonParser.parseReader(new InputStreamReader(index, StandardCharsets.UTF_8)).getAsJsonArray())
                try (var in = getClass().getResourceAsStream("/assets/icheme/columnpreset/" + file.getAsString())) {
                    var o = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                    listed.add(new FileColumns.ColumnTemplate(file.getAsString(), V3ColumnTemplate.read(o), true));
                }
        }
        assertFalse(listed.isEmpty());
        for (var template : listed) {
            var column = template.template();
            var door = ColumnDoor.of(ColumnDoor.template(SheetFixture.column(), column));
            assertEquals(column.trays() - 2, door.stages(), template.name());
            assertEquals(column.draws().size(), door.draws().size(), template.name());
            assertEquals(column.pumparounds().size(), door.pumparounds().size(), template.name());
        }
        var listing = FileColumns.columnTemplates(listed, 1, SheetUnits.DEFAULT, texts);
        assertEquals(listed.size(), listing.rows().size());
        assertEquals(5, listing.head().length);
        assertEquals(5, listing.at().length);
        assertEquals(2, listing.keys().length);
        assertEquals(1, listing.primary());
        assertTrue(listing.rows().stream().anyMatch(r -> r.cells()[2].equals("none")) || listing.rows().stream().allMatch(r -> r.cells()[2].contains("draws")), "the Connections column reads");
        assertTrue(texts.missing.isEmpty(), texts.missing.toString());
    }

    @Test
    void theRevisionNoticeNamesWhatDiffersAndBlocksNothing() {
        var client = Map.of("pack:a", "1", "pack:b", "2");
        assertTrue(RevisionNotice.differing(client, null).isEmpty(), "no word from a server: a single player");
        assertTrue(RevisionNotice.differing(client, Map.of("pack:a", "1", "pack:b", "2")).isEmpty(), "a match");
        assertEquals(List.of("pack:b"), RevisionNotice.differing(client, Map.of("pack:a", "1", "pack:b", "3")));
        assertEquals(List.of("pack:b", "pack:c"), RevisionNotice.differing(client, Map.of("pack:a", "1", "pack:c", "1")), "a package on one side only differs too");
        var mine = RevisionNotice.tokens(SheetFixture.CATALOG);
        assertTrue(mine.containsKey(SheetFixture.NETWORK));
        assertEquals(SheetFixture.CATALOG.fluidRevision(SheetFixture.NETWORK), mine.get(SheetFixture.NETWORK));
    }

    @Test
    void theNoticeStandsOnTheStatusLineAfterWhatItSays() {
        var texts = new SheetFixture.Texts();
        var editor = SheetFixture.editor(SheetFixture.water());
        var view = new SheetView();
        var gestures = new SheetGestures(editor, view, s -> {
        });
        var state = new SheetState(editor.sheet(), null, null, 0, null);
        var plain = new SheetContext(editor, state, SheetUnits.DEFAULT, texts, SheetFixture.M, SheetFixture.FLUIDS);
        var said = SimulatorPage.status(plain, SimulatorFrame.Tab.FLOWSHEET, new ResultsPage.State(), gestures, editor, false, null);
        assertEquals(texts.get("flowsheet.status.ready"), said.text());
        var differ = new SheetContext(editor, state, SheetUnits.DEFAULT, texts, SheetFixture.M, SheetFixture.FLUIDS, SimulatorHost.NONE, List.of("crude"));
        var noticed = SimulatorPage.status(differ, SimulatorFrame.Tab.FLOWSHEET, new ResultsPage.State(), gestures, editor, false, null);
        assertTrue(noticed.text().startsWith(said.text()), "nothing it says is lost");
        assertTrue(noticed.text().contains("server's on crude"), noticed.text());
        assertEquals(said.tone(), noticed.tone());
        assertTrue(texts.missing.isEmpty(), texts.missing.toString());
    }

    @Test
    void aMovedPackageRevisionDropsResults() {
        var ran = RevisionNotice.tokens(SheetFixture.water(), SheetFixture.CATALOG);
        assertEquals(Map.of(SheetFixture.NETWORK, SheetFixture.CATALOG.fluidRevision(SheetFixture.NETWORK)), ran);
        assertFalse(RevisionNotice.dropsResults(ran, ran));
        assertFalse(RevisionNotice.dropsResults(ran, RevisionNotice.tokens(SheetFixture.CATALOG)), "another package moving leaves them");
        assertTrue(RevisionNotice.dropsResults(ran, Map.of(SheetFixture.NETWORK, "moved")));
        assertTrue(RevisionNotice.dropsResults(ran, Map.of()), "a package gone");
    }

    @Test
    void aRunningStatusKeepsItsPlaceBesideARefusal() {
        var texts = new SheetFixture.Texts();
        var editor = SheetFixture.editor(SheetFixture.water());
        editor.lock(true, SheetEditor.RUNNING);
        assertFalse(editor.moveUnit("P-1", 64, 32, null));
        var gestures = new SheetGestures(editor, new SheetView(), s -> {
        });
        var state = new SheetState(editor.sheet(), null, null, 0, new SheetState.Running(1, 2, 30, "V-1", null));
        var x = new SheetContext(editor, state, SheetUnits.DEFAULT, texts, SheetFixture.M, SheetFixture.FLUIDS);
        var line = SimulatorPage.status(x, SimulatorFrame.Tab.RESULTS, new ResultsPage.State(), gestures, editor, false, null);
        assertTrue(line.text().startsWith("Running, 1.0 s: pass 2"), line.text());
        assertTrue(line.text().endsWith(texts.get("flowsheet.refused.running")), line.text());
        assertEquals(Palette.WARN, line.tone());
    }

    @Test
    void theFreeFilesKeysWaitForARun() {
        var texts = new SheetFixture.Texts();
        var editor = SheetFixture.editor(SheetFixture.water());
        var host = new SimulatorHost() {
            @Override public SheetState.Running running() {
                return new SheetState.Running(1, 0, 0, null, null);
            }
        };
        host.lock(editor);
        var x = new SheetContext(editor, new SheetState(editor.sheet(), null, null, 0, host.running()), SheetUnits.DEFAULT, texts, SheetFixture.M, SheetFixture.FLUIDS, host, List.of());
        var frame = new SimulatorFrame(960, 540);
        for (var slot : SimulatorPage.frameKeys(x, SimulatorFrame.Tab.FLOWSHEET, frame, host, editor)) {
            if (slot.button().key().equals("file/new") || slot.button().key().equals("file/open")) assertFalse(slot.button().active(), slot.button().key());
            if (slot.button().key().equals("stop")) assertTrue(slot.button().active());
            if (slot.button().key().equals("run")) assertFalse(slot.button().active());
        }
    }

    @Test
    void noteTextsReadForEveryRunNote() {
        var texts = new SheetFixture.Texts();
        var line = NoteText.text(Note.error("sheet", "run.broken", "x"), texts, SheetFixture.water(), SheetUnits.DEFAULT);
        assertTrue(line.contains("x"));
        assertTrue(texts.missing.isEmpty(), texts.missing.toString());
    }
}
