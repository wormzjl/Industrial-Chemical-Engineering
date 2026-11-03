package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetEngine;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.Note;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.Report;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The run half on the crude unit (W7 of batch 2026-11-02-process-simulator), a slow gate: Stop deep inside a column on the game's own path,
 * bound by a count of checkpoints and not by a clock; the same digits on the run thread, beside another run, and with nobody listening;
 * progress and the Log against what the loop did; Solve alone on a real column.
 */
class SimulatorRunTest {
    private static final Flowsheet SAMPLE = SheetFixture.sample();
    /** The run on its own, on the caller, with no progress: what every other run here must equal. */
    private static final FlowsheetRun ONE = FlowsheetEngine.run(SAMPLE, SheetFixture.CATALOG, () -> {
    });

    private static void ended() throws InterruptedException {
        for (var thread : Thread.getAllStackTraces().keySet())
            if (thread.getName().equals(SimulatorRunner.THREAD)) {
                thread.join(TimeUnit.MINUTES.toMillis(3));
                assertFalse(thread.isAlive(), "the run thread ended");
            }
    }

    /** Every number of a run's streams, to the bit. */
    private static void same(FlowsheetRun expected, FlowsheetRun actual) {
        assertEquals(expected.status(), actual.status());
        assertEquals(expected.streams().keySet(), actual.streams().keySet());
        for (var id : expected.streams().keySet()) {
            var a = expected.streams().get(id);
            var b = actual.streams().get(id);
            assertArrayEquals(a.amountsView(), b.amountsView(), id);
            assertEquals(Double.doubleToLongBits(a.temperature()), Double.doubleToLongBits(b.temperature()), id);
            assertEquals(Double.doubleToLongBits(a.pressure()), Double.doubleToLongBits(b.pressure()), id);
            assertEquals(Double.doubleToLongBits(a.enthalpy()), Double.doubleToLongBits(b.enthalpy()), id);
        }
        assertEquals(expected.loops(), actual.loops());
    }

    /** A wrapper that hears every checkpoint and every word of progress. */
    private static class Ear implements SimulatorRunner.Engine {
        final List<Thread> threads = Collections.synchronizedList(new ArrayList<>());
        final List<String> heard = Collections.synchronizedList(new ArrayList<>());

        void at() {
            threads.add(Thread.currentThread());
        }

        @Override public FlowsheetRun run(Flowsheet sheet, MaterialCatalog catalog, Runnable checkpoint, FlowsheetRun last, FlowsheetSolver.Progress progress) {
            return FlowsheetEngine.run(sheet, catalog, () -> {
                at();
                checkpoint.run();
            }, last, new FlowsheetSolver.Progress() {
                @Override public void calculation(int pass, int passes, String unit) {
                    heard.add(pass + "/" + passes + " " + unit);
                    progress.calculation(pass, passes, unit);
                }

                @Override public void start(Report.Start start) {
                    heard.add(start.name());
                    progress.start(start);
                }
            });
        }

        @Override public FlowsheetRun alone(Flowsheet sheet, String unit, MaterialCatalog catalog, Runnable checkpoint, Map<String, FlowStream> streams, FlowsheetSolver.Progress progress) {
            return SimulatorRunner.Engine.REAL.alone(sheet, unit, catalog, checkpoint, streams, progress);
        }
    }

    /** The whole sample, a column's display with it, fits a block's body and comes back; the printed times are what a run's end and an opening cost the client. */
    @Test
    void theSamplesResultsFitTheBodyAndComeBack() throws Exception {
        byte[] sheet = com.wormzjl.icheme.science.flowsheet.FlowsheetFile.write(SAMPLE);
        var finished = new SimulatorHost.Finished(SAMPLE, ONE, 1);
        long start = System.nanoTime();
        byte[] results = com.wormzjl.icheme.network.SimulatorResults.fitted(finished, SAMPLE, sheet);
        long fitted = System.nanoTime();
        com.wormzjl.icheme.network.SimulatorResults.fitted(finished, SAMPLE, sheet);
        long warm = System.nanoTime();
        byte[] body = com.wormzjl.icheme.science.flowsheet.SheetBody.pack(sheet, results);
        long packed = System.nanoTime();
        var unpacked = com.wormzjl.icheme.science.flowsheet.SheetBody.unpack(body);
        var back = com.wormzjl.icheme.network.SimulatorResults.read(unpacked.results(), SAMPLE, SheetFixture.FLUIDS.model());
        long read = System.nanoTime();
        System.out.printf("SAMPLE_BODY sheet=%d results=%d packed=%d fit_ms=%.1f again_ms=%.1f pack_ms=%.1f read_ms=%.1f%n", sheet.length, results.length, body.length, (fitted - start) / 1e6, (warm - fitted) / 1e6, (packed - warm) / 1e6, (read - packed) / 1e6);
        assertTrue(body.length <= com.wormzjl.icheme.science.flowsheet.SheetBody.MAX_PACKED);
        assertTrue(ONE.reports().values().stream().anyMatch(report -> report.display() != null), "the sample has a column to show");
        assertEquals(ONE.reports().keySet(), back.run().reports().keySet());
        for (var id : ONE.reports().keySet()) assertEquals(ONE.reports().get(id).display(), back.run().reports().get(id).display(), id);
        assertEquals(ONE.status(), back.run().status());
        assertEquals(ONE.streams().keySet(), back.run().streams().keySet());
    }

    @Test
    void theDigitsAreTheSameOnTheRunThreadAndBesideAnotherRun() throws InterruptedException {
        var one = new SimulatorRunner(() -> SheetFixture.CATALOG);
        var two = new SimulatorRunner(() -> SheetFixture.CATALOG);
        one.run(SAMPLE);
        two.run(SAMPLE);
        ended();
        same(ONE, one.last().run());
        same(ONE, two.last().run());
    }

    @Test
    void progressRunsInOrderAndTheLogFollowsTheLoop() throws InterruptedException {
        var ear = new Ear();
        var runner = new SimulatorRunner(() -> SheetFixture.CATALOG, ear);
        runner.run(SAMPLE);
        ended();
        var finished = runner.last();
        same(ONE, finished.run());
        for (var thread : ear.threads) assertEquals(SimulatorRunner.THREAD, thread.getName());

        int pass = 0, tower = 0;
        for (var said : ear.heard) {
            if (said.equals("LEARNED") || said.equals("LAST")) {
                assertEquals(tower++ == 0 ? "LEARNED" : "LAST", said, "a cold start, then its last state");
                continue;
            }
            int at = Integer.parseInt(said.substring(0, said.indexOf('/')));
            assertEquals(FlowsheetSolver.Settings.DEFAULT.maxPasses(), Integer.parseInt(said.substring(said.indexOf('/') + 1, said.indexOf(' '))));
            assertTrue(at == 0 || at >= pass, () -> "passes only go up: " + ear.heard);
            if (at > 0) pass = at;
        }
        var loop = finished.run().loops().getFirst();
        assertEquals(loop.passes(), pass, "the last pass heard is the loop's last");
        assertEquals(loop.passes(), tower, "one solve of the column a pass");

        var editor = SheetFixture.editor(SAMPLE);
        var texts = new SheetFixture.Texts();
        var x = new SheetContext(editor, SheetState.of(editor.sheet(), finished), SheetUnits.DEFAULT, texts, SheetFixture.M, SheetFixture.FLUIDS, SimulatorHost.NONE, java.util.List.of());
        var lines = ResultsPage.logLines(x).stream().map(ResultsPage.Line::text).toList();
        assertTrue(texts.missing.isEmpty(), texts.missing.toString());
        assertTrue(lines.getFirst().startsWith("Order: "), lines.getFirst());
        assertTrue(lines.getFirst().contains("with tear " + loop.tears().getFirst()), lines.getFirst());
        assertTrue(lines.getFirst().contains("T-101/pa 1"), lines.getFirst());
        assertFalse(lines.getFirst().contains("CRUDE"), "a source is no step");
        var passes = lines.stream().filter(line -> line.startsWith("Pass ")).toList();
        assertEquals(loop.passes(), passes.size(), lines.toString());
        for (int i = 0; i < passes.size(); i++) assertTrue(passes.get(i).startsWith("Pass " + (i + 1) + ": "), passes.get(i));
        assertTrue(passes.getFirst().contains("T-101 from its learned start"), passes.getFirst());
        assertTrue(passes.get(1).contains("T-101 from its last state"), passes.get(1));
        assertTrue(passes.get(1).contains("moved"), passes.get(1));
        assertTrue(passes.getFirst().contains("came out at"), passes.getFirst());
        assertTrue(lines.stream().anyMatch(line -> line.contains("closed in " + loop.passes() + " passes")), lines.toString());
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("Hot sides checked") && line.contains("T-101/pa 1") && line.contains("T-101/condenser")), lines.toString());
    }

    /** Stops at the {@code at}th checkpoint; says whether a column was on the stack, and how many checkpoints came after. */
    private record Stopped(FlowsheetRun run, boolean inColumn, int after) {
    }

    private static Stopped stopAt(int at) throws InterruptedException {
        var seen = new AtomicInteger();
        var inColumn = new AtomicBoolean();
        var after = new AtomicInteger();
        var runner = new SimulatorRunner[1];
        runner[0] = new SimulatorRunner(() -> SheetFixture.CATALOG, new SimulatorRunner.Engine() {
            @Override public FlowsheetRun run(Flowsheet sheet, MaterialCatalog catalog, Runnable checkpoint, FlowsheetRun last, FlowsheetSolver.Progress progress) {
                return FlowsheetEngine.run(sheet, catalog, () -> {
                    int n = seen.incrementAndGet();
                    if (n == at) {
                        inColumn.set(Arrays.stream(Thread.currentThread().getStackTrace()).anyMatch(frame -> frame.getClassName().contains("V3ColumnCalculator")));
                        runner[0].stop();
                    }
                    if (n > at) after.incrementAndGet();
                    checkpoint.run();
                }, last, progress);
            }

            @Override public FlowsheetRun alone(Flowsheet sheet, String unit, MaterialCatalog catalog, Runnable checkpoint, Map<String, FlowStream> streams, FlowsheetSolver.Progress progress) {
                return SimulatorRunner.Engine.REAL.alone(sheet, unit, catalog, checkpoint, streams, progress);
            }
        });
        runner[0].run(SAMPLE);
        ended();
        return new Stopped(runner[0].last().run(), inColumn.get(), after.get());
    }

    @Test
    void stopReachesInsideAColdColumnOnTheGamesPath() throws InterruptedException {
        var count = new AtomicInteger();
        FlowsheetEngine.run(SAMPLE, SheetFixture.CATALOG, count::incrementAndGet);
        int deep = 0;
        // Spread through the first pass, whose cold column takes most of a run's checkpoints.
        for (double share : new double[]{.05, .15, .3}) {
            var stopped = stopAt((int) (count.get() * share));
            assertEquals(FlowsheetRun.Status.STOPPED, stopped.run().status(), "never FAILED");
            assertTrue(stopped.run().log().stream().noneMatch(note -> note.severity() == Note.Severity.ERROR), () -> "a stop blames nothing: " + stopped.run().log());
            assertFalse(stopped.run().outcomes().containsValue(FlowsheetRun.Outcome.FAILED));
            assertTrue(stopped.after() <= 8, () -> stopped.after() + " checkpoints after Stop");
            if (stopped.inColumn()) {
                deep++;
                assertNull(stopped.run().reports().get("T-101"), "a stopped column left no report");
            }
            assertEquals(FlowsheetRun.Outcome.SOLVED, stopped.run().outcomes().get("P-101"), "what finished stays");
        }
        assertTrue(deep >= 2, "Stop came inside the column " + deep + " times");
    }

    @Test
    void solveAloneHoldsAColumnOnTheStreamItHas() throws InterruptedException {
        var runner = new SimulatorRunner(() -> SheetFixture.CATALOG);
        var editor = SheetFixture.editor(SAMPLE);
        runner.run(editor.sheet());
        ended();
        var ran = runner.last();
        var json = editor.unit("T-101").params().json().deepCopy();
        json.addProperty("reflux", json.get("reflux").getAsDouble() * 1.05);
        assertTrue(editor.setParams("T-101", new Params(json), null));
        var door = new SheetContext(editor, SheetState.of(editor.sheet(), ran), SheetUnits.DEFAULT, new SheetFixture.Texts(), SheetFixture.M, SheetFixture.FLUIDS, new SimulatorHost() {
            @Override public SimulatorRunner runner() {
                return runner;
            }
        }, List.of());
        var solve = ColumnDoors.door(door, "T-101", new Door.View()).footer().stream().filter(b -> b.key().equals("solve")).findFirst().orElseThrow();
        assertTrue(door.state().stale("S-7"), "in a loop the edit greys the column's own feed");
        assertTrue(solve.active(), () -> "and it still has that stream: " + solve.hint());
        runner.alone(editor.sheet(), "T-101");
        ended();
        var alone = runner.last();
        assertEquals(FlowsheetRun.Status.STEADY, alone.alone().status(), () -> String.valueOf(alone.alone().why()));
        var state = SheetState.of(editor.sheet(), alone);
        assertFalse(state.stale("T-101"));
        assertFalse(state.stale("KERO"), "its products are known");
        assertTrue(state.stale("P-102"), "what follows has not run on them");
        assertTrue(state.stale("E-101"), "nor has the loop back to it");
        assertTrue(state.stale("S-7"), "its feed is on that loop, and held as it was");
        assertFalse(state.steady());
        assertEquals(ran.run().streams().get("S-7").temperature(), alone.run().streams().get("S-7").temperature(), "held");
        assertNotEquals(ran.run().streams().get("NAPHTHA").total(), alone.run().streams().get("NAPHTHA").total(), "the door's new reflux took");
        assertNotNull(alone.run().reports().get("T-101").display(), "the door's Results pages read it");
    }
}
