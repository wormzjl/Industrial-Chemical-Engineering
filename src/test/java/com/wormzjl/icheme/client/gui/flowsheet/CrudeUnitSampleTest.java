package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetEngine;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.Note;
import com.wormzjl.icheme.science.flowsheet.Params;
import com.wormzjl.icheme.science.flowsheet.Report;
import com.wormzjl.icheme.science.flowsheet.sizing.AirCoolerSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.ColumnSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.DesignBasis;
import com.wormzjl.icheme.science.flowsheet.sizing.DrumSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.ExchangerSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.HeaterSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.Lamp;
import com.wormzjl.icheme.science.flowsheet.sizing.Limit;
import com.wormzjl.icheme.science.flowsheet.sizing.LineSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.PressurePath;
import com.wormzjl.icheme.science.flowsheet.sizing.Proposal;
import com.wormzjl.icheme.science.flowsheet.sizing.PumpSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.Rating;
import com.wormzjl.icheme.science.flowsheet.sizing.Service;
import com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.SizingFacts;
import com.wormzjl.icheme.science.flowsheet.sizing.TankSizing;
import com.wormzjl.icheme.science.flowsheet.sizing.UnitType;
import com.wormzjl.icheme.science.flowsheet.sizing.ValveSizing;
import com.wormzjl.icheme.science.fluid.thermo.TestPhases;
import com.wormzjl.icheme.science.fluid.transport.MixtureViscosity;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The {@code crude_unit} sample on a real steady run, the slow gates of batch 2026-11-02-process-simulator that read it: the
 * runner ({@link SimulatorRunTest}), S1's lines ({@link LineSizingSampleTest}), S2's units ({@link UnitSizingSampleTest}), S3's
 * column ({@link ColumnSizingSampleTest}) and S4a's Size all ({@link SizeAllSampleTest}). One class since batch
 * 2026-11-11-aqueous-solvents ({@code TEST_DUPLICATES_REVIEW.md}, item 2): the five each solved the sample cold in a JVM of
 * their own; here one cold run, one sizing pass and one Size all serve them all.
 */
class CrudeUnitSampleTest {
    /** The sample, solved once, cold, on the caller with no progress, counting its checkpoints: what every other run here must equal. */
    private static final class Sample {
        static final Flowsheet SHEET = SheetFixture.sample();
        /** How many checkpoints {@link #RUN} met: a cold run's. */
        static final AtomicInteger COLD = new AtomicInteger();
        static final FlowsheetRun RUN = com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.run(SHEET, SheetFixture.CATALOG, COLD::incrementAndGet);
        static final FlowThermo THERMO = new FlowThermo(SheetFixture.FLUIDS.model(), SheetFixture.CATALOG, SheetFixture.NETWORK, () -> {
        });
    }

    /** The sample's sizing pass at the default entries. */
    private static final class Sized {
        static final SheetSizing.Sized PASS = MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> SheetSizing.pass(Sample.SHEET, Sample.RUN, Sample.THERMO, SheetSizing.Entries.DEFAULT));
    }

    /** The sample's Size all. */
    private static final class All {
        static final SheetSizing.SizeAll ALL = MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.sizeAll(Sample.SHEET, Sample.RUN, Sample.THERMO));
    }

    /**
     * The run half on the crude unit (W7 of batch 2026-11-02-process-simulator), a slow gate: Stop deep inside a column on the game's own path,
     * bound by a count of checkpoints and not by a clock; the same digits on the run thread, beside another run, and with nobody listening;
     * progress and the Log against what the loop did; Solve alone on a real column.
     */
    @Nested
    class SimulatorRunTest {
        private static final Flowsheet SAMPLE = Sample.SHEET;
        /** How many checkpoints {@link #ONE} met: a cold run's. */
        private static final AtomicInteger COLD = Sample.COLD;
        /** The run on its own, on the caller, with no progress: what every other run here must equal. */
        private static final FlowsheetRun ONE = Sample.RUN;

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
            assertNotNull(ONE.reports().get("T-101").warm(), "the run leaves where its column ended");
            for (var report : back.run().reports().values()) assertNull(report.warm(), "which is client memory only: the stored run starts its columns from the learned start");
            assertEquals(ONE.status(), back.run().status());
            assertEquals(ONE.streams().keySet(), back.run().streams().keySet());
        }

        /**
         * Two claims on one pair of runs (until 2026-11-12 two tests, {@code theDigitsAreTheSameOnTheRunThreadAndBesideAnotherRun} and
         * {@code progressRunsInOrderAndTheLogFollowsTheLoop}): the same digits on the run thread and beside another run, the one
         * listened to and the plain one; and on the listened one, progress in order and the Log against what the loop did.
         */
        @Test
        void theDigitsAreTheSameBesideAnotherRunAndProgressRunsInOrderAndTheLogFollowsTheLoop() throws InterruptedException {
            var ear = new Ear();
            var runner = new SimulatorRunner(() -> SheetFixture.CATALOG, ear);
            var plain = new SimulatorRunner(() -> SheetFixture.CATALOG);
            runner.run(SAMPLE);
            plain.run(SAMPLE);
            ended();
            same(ONE, plain.last().run());
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
            int deep = 0;
            // Spread through the first pass, whose cold column takes most of a run's checkpoints.
            for (double share : new double[]{.05, .15, .3}) {
                var stopped = stopAt((int) (COLD.get() * share));
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

        /**
         * A run from a steady one starts the column where its solve there ended (owner 2026-11-04, P2): the same answer to the loop's
         * tolerance, in a fraction of a cold run's checkpoints. Stop's cold copy starts it from the learned start.
         */
        @Test
        void aRunFromASteadyOneStartsTheColumnWhereItEnded() {
            var starts = Collections.synchronizedList(new ArrayList<Report.Start>());
            FlowsheetSolver.Progress heard = new FlowsheetSolver.Progress() {
                @Override public void calculation(int pass, int passes, String unit) {
                }

                @Override public void start(Report.Start start) {
                    starts.add(start);
                }
            };
            var warm = new AtomicInteger();
            var next = FlowsheetEngine.run(SAMPLE, SheetFixture.CATALOG, warm::incrementAndGet, ONE, heard);
            assertEquals(FlowsheetRun.Status.STEADY, next.status(), () -> "log " + next.log());
            assertEquals(Report.Start.LAST, starts.getFirst(), "its first solve starts where the steady run's ended");
            double flow = 0, temperature = 0;
            for (var id : ONE.streams().keySet()) {
                var a = ONE.streams().get(id);
                var b = next.streams().get(id);
                if (a.empty() && b.empty()) continue;
                flow = Math.max(flow, Math.abs(b.mass() - a.mass()) / Math.max(a.mass(), 1e-9));
                temperature = Math.max(temperature, Math.abs(b.temperature() - a.temperature()));
            }
            System.out.printf("WARM_START checkpoints=%d cold=%d flow_rel=%.3g temperature_K=%.3g%n", warm.get(), COLD.get(), flow, temperature);
            assertTrue(warm.get() < COLD.get() / 2, () -> warm.get() + " checkpoints against a cold run's " + COLD.get());
            // The band: the warm solve's closure, 0.1 % of a row's own scale, and ten times the loop's 0.01 K (measured 4e-7 and 2e-5 K).
            assertTrue(flow < 1e-3, "flow moved by " + flow);
            assertTrue(temperature < .1, "temperature moved by " + temperature + " K");

            starts.clear();
            var probe = FlowsheetEngine.run(SAMPLE, SheetFixture.CATALOG, () -> {
                if (!starts.isEmpty()) throw new java.util.concurrent.CancellationException();
            }, ONE.cold(), heard);
            assertEquals(FlowsheetRun.Status.STOPPED, probe.status());
            assertEquals(List.of(Report.Start.LEARNED), starts, "Stop's cold copy starts the column from the learned start");
        }

        @Test
        void solveAloneHoldsAColumnOnTheStreamItHas() throws InterruptedException {
            var runner = new SimulatorRunner(() -> SheetFixture.CATALOG);
            var editor = SheetFixture.editor(SAMPLE);
            // The steady sample as a block hands it back: Solve alone reads its streams from the last run, which this is.
            assertSame(SAMPLE, editor.sheet());
            runner.restore(new SimulatorHost.Finished(SAMPLE, ONE, 1));
            var ran = runner.last();
            assertSame(ONE, ran.run());
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

    /**
     * S1's gate on a real steady run of the sample (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} section 5), a slow gate:
     * the 21 lines on the sheet take the probe's bore, count and deciding limit, on the service the plan's rule gives. The probe's
     * {@code sizing-sample.tsv} holds them. Its ten member lines (Overhead, Receiver out, Pump out, Reflux, the PA draws and returns)
     * stay for S3, which owns the circulation they need. The probe labelled WATER as Liquid by hand; the rule makes it Water, whose
     * row holds the same limits, so its size stands.
     *
     * <p>Since O4 of batch 2026-11-04-open-thermo-data (crude cuts on Twu 1985 viscosity, the one-term mixture term) the drops are
     * the run's, not the probe's table: eight crude lines moved at the third place, by -0.8 to +2.2 % (CRUDE 0.058 to 0.059, S-1
     * 0.233 to 0.236, S-2 0.196 to 0.199, S-3 0.085 to 0.086, S-4 0.084 to 0.085, DIESEL 0.165 to 0.166, S-10 0.179 to 0.183,
     * RESIDUE 0.345 to 0.342); every bore, count and deciding limit stands.
     *
     * <p>Since O3 of the same batch the sample runs the medium crude grade, and every value is the run's. Four bores moved (CWS and
     * CWR 0.7 to 0.6 m on the condenser's smaller duty; KERO 0.2 to 0.25 m and AGO 0.15 to 0.2 m on the larger draws) and two
     * deciding limits (OFFGAS speed to drop, NAPHTHA drop to speed); every service, count and lamp stands.
     *
     * <p>Since the column took the network's water (E30 (a) of the same batch) the column sends a little more to its distillate, and
     * NAPHTHA's drop is 0.175 bar/100 m, not 0.174.
     *
     * <p>With condenser carry-over (E31 of the same batch) the column sends more again: NAPHTHA's drop is 0.193 bar/100 m,
     * OFFGAS (0.070) is decided by its speed again, and WATER, DIESEL, S-10, RESIDUE and AGO move in the third place. Every
     * service, bore, count and lamp stands.
     */
    @Nested
    class LineSizingSampleTest {
        /**
         * A line of {@code sizing-sample.tsv}.
         *
         * @param drop bar/100 m
         */
        private record Probe(String line, Service service, double bore, Limit decides, double drop) {
        }

        private static final List<Probe> PROBE = List.of(new Probe("CRUDE", Service.SUCTION_COLD, .4, Limit.SUCTION_COLD_SPEED, .061), new Probe("S-1", Service.LIQUID, .3, Limit.LIQUID_SPEED, .243), new Probe("S-2", Service.LIQUID, .3, Limit.LIQUID_SPEED, .202),
                new Probe("S-3", Service.LIQUID, .35, Limit.LIQUID_SPEED, .086), new Probe("S-4", Service.LIQUID, .35, Limit.LIQUID_SPEED, .085), new Probe("S-5", Service.LIQUID, .35, Limit.LIQUID_SPEED, .087),
                // S-7: the probe's 0.056 at V-101's 2.72 bar; the sample sets 2.80 since S4c (0.054 on Tia Juana, 0.050 on the medium grade).
                new Probe("S-6", Service.TWO_PHASE, .7, Limit.TWO_PHASE_MOMENTUM, .050), new Probe("S-7", Service.TWO_PHASE, .8, Limit.TWO_PHASE_MOMENTUM, .050), new Probe("STEAM", Service.STEAM_SUPERHEATED, .35, Limit.STEAM_SUPERHEATED_SPEED, .063),
                new Probe("CWS", Service.WATER, .6, Limit.WATER_SPEED, .0852), new Probe("CWR", Service.WATER, .6, Limit.WATER_SPEED, .085), new Probe("OFFGAS", Service.GAS, .2, Limit.GAS_SPEED, .070),
                new Probe("NAPHTHA", Service.LIQUID, .1, Limit.LIQUID_SPEED, .193), new Probe("WATER", Service.WATER, .075, Limit.WATER_DROP, .280), new Probe("DIESEL", Service.LIQUID, .15, Limit.LIQUID_SPEED, .157),
                new Probe("S-10", Service.LIQUID, .25, Limit.LIQUID_SPEED, .186), new Probe("RESIDUE", Service.LIQUID, .25, Limit.LIQUID_SPEED, .324), new Probe("KERO", Service.DRAW, .25, Limit.DRAW_SPEED, .008),
                new Probe("AGO", Service.DRAW, .2, Limit.DRAW_SPEED, .009), new Probe("S-8", Service.DRAW, .25, Limit.DRAW_SPEED, .012), new Probe("S-9", Service.DRAW, .45, Limit.DRAW_SPEED, .010));

        @Test
        void theSheetsLinesEqualTheProbes() {
            var sheet = Sample.SHEET;
            var run = Sample.RUN;
            assertEquals(FlowsheetRun.Status.STEADY, run.status());
            assertEquals(sheet.streams().size(), PROBE.size(), "every line on the sheet");
            var thermo = Sample.THERMO;
            var scratch = new MixtureViscosity.Workspace();
            MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> {
                for (var probe : PROBE) {
                    var stream = stream(sheet, probe.line());
                    var flow = run.streams().get(stream.id());
                    var service = Service.of(flow, Service.Ends.of(sheet, stream), thermo, null);
                    var line = LineSizing.Line.of(flow, service, DesignBasis.DEFAULT, thermo, PipeSizes.MAX_BORE, null, scratch);
                    var choice = LineSizing.auto(line);
                    var size = choice.size();
                    var closest = choice.rating().closest();
                    assertEquals(probe.service(), service, probe.line());
                    assertEquals(probe.bore(), size.bore(), probe.line());
                    assertEquals(1, size.runs(), probe.line());
                    assertEquals(probe.decides(), closest.limit(), probe.line());
                    assertEquals(Lamp.GREEN, choice.rating().lamp(), probe.line());
                    assertEquals(probe.drop(), LineSizing.drop(line, size, 100), .0006, probe.line() + ": the probe's table holds three places");
                }
                return null;
            });
        }

        private static Flowsheet.Stream stream(Flowsheet sheet, String name) {
            for (var stream : sheet.streams()) if (stream.name().equals(name)) return stream;
            throw new AssertionError(name);
        }
    }

    /**
     * S2's gate on a real steady run of the sample (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} section 4), a slow gate: the
     * sheet's units take the probe's size and closest limit, from {@code sizing-sample.tsv}. T-101 and its ten members (the condenser,
     * the receiver, the reflux pump, three pumparound coolers and their pumps, and the member lines) are S3's, in
     * {@link ColumnSizingSampleTest}.
     *
     * <p>Two surfaces leave the probe's, because the probe holds one heat capacity along a duty and the curves hold the real one, which
     * rises with temperature, so more of the heat passes where the difference is wide. E-101 needs 440 m², not 443.5, and keeps its size. E-102
     * needs 724 m², not 741, and that moves it: 6 × 4 bays of 2.0 × 5 m hold it on 145 m² of face, where the probe's 5 × 3 of 2.5 × 6 m take 150.
     * The two gates below rebuild the probe's number from the same streams, so what moved is the form and nothing else.
     *
     * <p>P-102 draws from T-101's tray, whose height the column's own layout gives, and an entry with the probe's height reads the same.
     * V-101's share and H-101's spare read P-101's pressure path, which ends at V-101's set outlet (S4a).
     *
     * <p>With condenser carry-over (E31 of batch 2026-11-04-open-thermo-data) the column sends more naphtha overhead and a little
     * less residue: E-101 needs 568 m² (the probe's 574.3), E-102 732.5 (750.2), H-101 80.39 MW, and the naphtha takes a 15 m
     * sphere, not 14. P-102 reads 131.6 m³/h and an NPSH of 32.35 m (34.69 m since the NPSH counts over p_v). Every other size stands.
     */
    @Nested
    class UnitSizingSampleTest {
        /** Probe values stand in the table's printed places. */
        private static final double TENTH = .05, ONE = .5, HUNDREDTH = .005;

        private static SheetSizing.Sized alone;
        private static FlowsheetRun run;
        private static FlowThermo thermo;
        private static Flowsheet sheet;

        @BeforeAll
        static void sizeTheSample() {
            sheet = Sample.SHEET;
            run = Sample.RUN;
            assertEquals(FlowsheetRun.Status.STEADY, run.status());
            thermo = Sample.THERMO;
            alone = Sized.PASS;
        }

        /** Heat that a mean capacity would spread evenly, on a stream whose capacity rises: the upper half of its range holds more. */
        private static void itsCapacityRises(String stream, double high, double low) {
            var flow = run.streams().get(stream);
            MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> {
                double top = thermo.atTemperature(high, flow.pressure(), flow.amountsView(), flow.solids()).enthalpy(), middle = thermo.atTemperature((high + low) / 2, flow.pressure(), flow.amountsView(), flow.solids()).enthalpy();
                double bottom = thermo.atTemperature(low, flow.pressure(), flow.amountsView(), flow.solids()).enthalpy();
                assertTrue(top - middle > 1.05 * (middle - bottom), stream);
                return null;
            });
        }

        private static double read(Rating rating, Limit limit) {
            return rating.reading(limit).value();
        }

        private static void closest(Rating rating, Limit limit) {
            assertEquals(Lamp.GREEN, rating.lamp());
            assertSame(limit, rating.closest().limit());
        }

        @Test
        void p101() {
            var unit = alone.units().get("P-101");
            assertEquals(new PumpSizing.Size(.30), unit.size());
            closest(unit.rating(), Limit.PUMP_RATED);
            assertEquals(96, read(unit.rating(), Limit.PUMP_RATED), ONE);
            assertEquals(730, read(unit.rating(), Limit.PUMP_FLOW), ONE);
            assertEquals(71, read(unit.rating(), Limit.PUMP_HEAD), ONE);
            // 80.4 m before W3b: the derived pairs (light ends over the heavy cuts) put the dry crude's bubble pressure at
            // 298.15 K at 129.6 kPa instead of 115.0 kPa, 1.72 m of this crude's head (batch 2026-11-11-aqueous-solvents).
            // 78.65 m while the NPSH counted over the full bubble pressure: it counts over p_v now, the crude's vapour pressure
            // without its methane and ethane (above their critical temperature at 298.15 K), 8.74 m of this crude's head lower
            // (W3B_NETWORK_PLAN.md section 44, as the world's suction limit counts it).
            assertEquals(87.39, read(unit.rating(), Limit.PUMP_NPSH), TENTH, "a feed stands at grade, so no height is missing");
        }

        @Test
        void p102ReadsItsDrawTraysHeight() {
            var unit = alone.units().get("P-102");
            assertEquals(new PumpSizing.Size(.15), unit.size());
            // O3 of batch 2026-11-04-open-thermo-data: on the medium crude grade the diesel draw is smaller and the pump runs at 69 % of
            // its rated flow, under the lamp's 70 %, so Auto's pump reads yellow on that limit.
            assertEquals(Lamp.YELLOW, unit.rating().lamp());
            assertSame(Limit.PUMP_RATED, unit.rating().closest().limit());
            assertEquals(69, read(unit.rating(), Limit.PUMP_RATED), ONE);
            assertEquals(131.6, read(unit.rating(), Limit.PUMP_FLOW), ONE);
            assertEquals(52, read(unit.rating(), Limit.PUMP_HEAD), ONE);
            // 32.35 m over the full bubble pressure; over p_v the tray's liquid has 2.34 m more (section 44).
            assertEquals(34.69, read(unit.rating(), Limit.PUMP_NPSH), TENTH, "the column's own layout gives the probe's height");
        }

        /**
         * S4c: Auto takes the least surface that holds 1.2 × the need, 528 m²: a 1.25 m shell with 5 m tubes, 586 m², where the bare
         * need's least was the plan's 1.00 × 6 m, 450 m². Its wider shell and shorter tubes lose less on both sides. On the medium
         * crude grade (O3 of batch 2026-11-04-open-thermo-data) the need is 573 m², 688 with the spare: the same shell with 6 m tubes, 703 m².
         * Since the column took the network's water (E30 (a)) the residue brings E-101 a little less heat, and the need is 572 m² (the probe's 578.5).
         * Since the pumps' losses heat the crude (D42 of batch 2026-11-03-rotating-machinery) the crude reaches E-101 0.08 K warmer:
         * both ends close by 0.05 K and the need is 569 m² (the probe's 575.0).
         */
        @Test
        void e101() {
            var unit = alone.units().get("E-101");
            assertEquals(new ExchangerSizing.Size(false, 1, 1.25, 6), unit.size());
            closest(unit.rating(), Limit.EXCHANGER_SURFACE);
            var surface = unit.rating().reading(Limit.EXCHANGER_SURFACE);
            assertEquals(703, surface.value(), ONE);
            assertEquals(569, surface.low(), ONE, "the curves' surface");
            assertEquals(575.0, probesSurface(), ONE, "the probe's one LMTD at U 1000, from the same streams");
            assertEquals(67.91, read(unit.rating(), Limit.EXCHANGER_HOT_END), TENTH);
            assertEquals(32.64, read(unit.rating(), Limit.EXCHANGER_COLD_END), TENTH);
            assertEquals(32.63, read(unit.rating(), Limit.EXCHANGER_APPROACH), TENTH);
            assertEquals(23.6, read(unit.rating(), Limit.EXCHANGER_OVER_NEED), ONE);
            var drops = unit.rating().readings().stream().filter(reading -> reading.limit() == Limit.EXCHANGER_DROP_LIQUID).toList();
            assertEquals(.20, drops.get(0).value(), HUNDREDTH, "the tube side, 0.44 on 1.00 × 6 m; 0.17 before O4's crude viscosity");
            assertEquals(.07, drops.get(1).value(), HUNDREDTH, "the shell side, 0.15 on 1.00 × 6 m");
        }

        private static double probesSurface() {
            var s = run.streams();
            double hotIn = s.get("S-9").temperature(), hotOut = s.get("S-10").temperature(), coldIn = s.get("S-4").temperature(), coldOut = s.get("S-5").temperature();
            double a = hotIn - coldOut, b = hotOut - coldIn;
            return 1.1 * (s.get("S-9").enthalpy() - s.get("S-10").enthalpy()) / (1000 * (a - b) / Math.log(a / b));
        }

        @Test
        void e101KeptOneStepDownIsRatedByTheSameRule() {
            var kept = MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> SheetSizing.pass(sheet, run, thermo,
                    new SheetSizing.Entries(DesignBasis.DEFAULT, Map.of(), Map.of("E-101", new SheetSizing.UnitEntry(new ExchangerSizing.Size(false, 1, .75, 6), null, null, false))))).units().get("E-101");
            assertEquals(new ExchangerSizing.Size(false, 1, .75, 6), kept.size());
            assertEquals(Lamp.RED, kept.rating().lamp());
            assertSame(Limit.EXCHANGER_SURFACE, kept.rating().closest().limit());
            assertEquals(253, read(kept.rating(), Limit.EXCHANGER_SURFACE), ONE);
            var drops = kept.rating().readings().stream().filter(reading -> reading.limit() == Limit.EXCHANGER_DROP_LIQUID).toList();
            assertEquals(1.31, drops.get(0).value(), HUNDREDTH, "1.29 before O4's crude viscosity");
            assertEquals(Lamp.YELLOW, drops.get(0).lamp());
            assertEquals(.432, drops.get(1).value(), HUNDREDTH, "0.41 before O4's crude viscosity, 0.44 before condenser carry-over (E31)");
        }

        /**
         * S4c: the bare 86.2 MW sets the type and the one cabin; the spare sets the size: 12.0 × 45 m holds 105.4 MW, the least over
         * 1.2 × 86.2 = 103.4, where 11.0 × 41 m held 87.6. On the medium crude grade (O3 of batch 2026-11-04-open-thermo-data) the bare
         * duty is 80.3 MW: 11.0 × 45 m holds 96.6, over 1.2 × 80.3 = 96.4.
         */
        @Test
        void h101TakesACabin() {
            var unit = alone.units().get("H-101");
            assertEquals(new HeaterSizing.Size(UnitType.CABIN_HEATER, 1, 11, 45), unit.size());
            closest(unit.rating(), Limit.HEATER_DUTY);
            var duty = unit.rating().reading(Limit.HEATER_DUTY);
            assertEquals(96.6, duty.value(), TENTH);
            assertEquals(80.39, duty.low(), TENTH);
            assertEquals(path("P-101").spare(), read(unit.rating(), Limit.HEATER_SPARE), 0, "its path's spare, to V-101's set outlet");
        }

        /** V-101 ends P-101's path at its set outlet and takes what the path leaves at design flow: its drop and its share read that. */
        @Test
        void v101TakesItsLinesBoreAndItsPathsSpare() {
            var unit = alone.units().get("V-101");
            assertEquals(new ValveSizing.Size(.7), unit.size());
            var path = path("P-101");
            assertEquals("V-101", path.taker());
            assertEquals(path.spare(), read(unit.rating(), Limit.VALVE_DROP), 0);
            assertEquals(path.spare(), read(unit.rating(), Limit.PATH_SPARE), 0);
            assertEquals(100 * path.spare() / path.available(), read(unit.rating(), Limit.VALVE_SHARE), 1e-9);
            assertEquals(Lamp.GREEN, unit.rating().lamp());
        }

        private static com.wormzjl.icheme.science.flowsheet.sizing.PressurePath path(String start) {
            return alone.paths().stream().filter(path -> path.start().equals(start)).findFirst().orElseThrow();
        }

        @Test
        void e102() {
            var unit = alone.units().get("E-102");
            assertEquals(new AirCoolerSizing.Size(2.5, 6, new ProcessUnit.Bank(6, 3), 1), unit.size(), "S4c: the least face that holds 1.2 × the curve's need, 869 m²; 6 × 4 bays of 2.0 × 5 m held the bare need");
            closest(unit.rating(), Limit.AIR_COOLER_SURFACE);
            var surface = unit.rating().reading(Limit.AIR_COOLER_SURFACE);
            assertEquals(903, surface.value(), ONE);
            assertEquals(732.5, surface.low(), ONE, "the curve's surface");
            assertEquals(750.2, probesBareSurface(), ONE, "the probe's one capacity, from the same streams");
            itsCapacityRises("S-10", run.streams().get("S-10").temperature(), run.streams().get("RESIDUE").temperature());
            assertEquals(45, read(unit.rating(), Limit.AIR_COOLER_OUTLET), TENTH);
            assertEquals(.045, read(unit.rating(), Limit.AIR_COOLER_DROP), HUNDREDTH);
        }

        private static double probesBareSurface() {
            var in = run.streams().get("S-10");
            var out = run.streams().get("RESIDUE");
            double duty = 1.1 * (in.enthalpy() - out.enthalpy()), capacity = duty / (in.temperature() - out.temperature()), air = HeatSides.AIR_TEMPERATURE;
            return capacity * Math.log((in.temperature() - air) / (out.temperature() - air)) / HeatSides.airFilms(1).liquid();
        }

        @Test
        void theProbesBankStillHoldsItsDutyWhenThePlayerKeepsIt() {
            var kept = MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> SheetSizing.pass(sheet, run, thermo,
                    new SheetSizing.Entries(DesignBasis.DEFAULT, Map.of(), Map.of("E-102", new SheetSizing.UnitEntry(new AirCoolerSizing.Size(2.5, 6, new ProcessUnit.Bank(5, 3), 1), null, null, false))))).units().get("E-102");
            assertEquals(Lamp.GREEN, kept.rating().lamp());
            assertEquals(752, read(kept.rating(), Limit.AIR_COOLER_SURFACE), ONE);
            assertEquals(.05, read(kept.rating(), Limit.AIR_COOLER_DROP), HUNDREDTH, "0.05 before O4's crude viscosity: the residue cooled to 45 C");
        }

        /** The plan's what-if: the sample has no storage tile, so each product stands for the liquid one would hold. */
        private static TankSizing.Load tile(String stream) {
            return MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> TankSizing.Load.of(run.streams().get(stream), FlowStream.empty(thermo.axis().size()), null, DesignBasis.DEFAULT, thermo));
        }

        @Test
        void theUnstabilisedNaphthaTakesASphereAndTheResidueARoof() {
            var naphtha = tile("NAPHTHA");
            // W5 D1 of batch 2026-11-11-aqueous-solvents: the column flashes on the network's derived pairs, so the receiver's naphtha is again at the network's bubble point of its own drum (2.3186 while the column
            // flashed on the authored matrix and the network on the derived pairs, W3b).
            assertEquals(2.2989, naphtha.bubble(), 2.2989 * .0005, "both bisect to a ratio of 1.0005, from other bounds; 2.3186 with the column on the authored matrix (W3b to W5a)");
            assertEquals(new TankSizing.Size(UnitType.SPHERE, 1, 15, 1), TankSizing.auto(naphtha, null).size(), "17 m on Tia Juana; the medium grade makes less naphtha (O3); 14 m before condenser carry-over (E31)");
            var residue = tile("RESIDUE");
            assertTrue(residue.bubble() < .015);
            assertEquals(new TankSizing.Size(UnitType.FLOATING_ROOF, 1, 30, 18), TankSizing.auto(residue, null).size());
        }

        @Test
        void everyLineStandsSizedInSheetOrder() {
            assertEquals(sheet.streams().stream().map(Flowsheet.Stream::id).toList(), alone.lines().keySet().stream().limit(21).toList(), "the block's member lines come after");
            assertEquals(.7, alone.lines().get("S-6").size().bore());
            assertEquals(Lamp.GREEN, alone.lines().get("S-6").rating().lamp());
        }

        @Test
        void stopReachesItBetweenItems() {
            var asked = new AtomicInteger();
            var stopping = new FlowThermo(SheetFixture.FLUIDS.model(), SheetFixture.CATALOG, SheetFixture.NETWORK, () -> {
                if (asked.incrementAndGet() == 5) throw new CancellationException();
            });
            MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> assertThrows(CancellationException.class, () -> SheetSizing.pass(sheet, run, stopping, SheetSizing.Entries.DEFAULT)));
        }
    }

    /**
     * S3's gate on a real steady run of the sample (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} sections 3.4 to 3.6 and 4),
     * a slow gate: T-101 by real solves at its trial diameters, its ten members and ten member lines against the probe's
     * {@code sizing-sample.tsv}, P-102's NPSH from the draw tray's height, Stop inside a trial, and the re-run at a new diameter.
     *
     * <p>Floods carry a tolerance of 0.5 % of flood: the column's learned start and hydraulic correction run on wall-clock budgets, which can
     * move the last digits on a loaded machine, and no gate here measures time.
     */
    @Nested
    class ColumnSizingSampleTest {
        private static final double FLOOD = .5, ONE = .5, HUNDREDTH = .005, TENTH = .05;
        private static final String T = "T-101";

        private static Flowsheet sheet;
        private static FlowsheetRun run;
        private static FlowThermo thermo;
        private static SheetSizing.Sized sized;

        @BeforeAll
        static void sizeTheSample() {
            sheet = Sample.SHEET;
            run = Sample.RUN;
            assertEquals(FlowsheetRun.Status.STEADY, run.status());
            thermo = Sample.THERMO;
            sized = Sized.PASS;
            for (var e : sized.units().entrySet()) System.out.println("UNIT\t" + e.getKey() + "\t" + e.getValue().size() + "\t" + text(e.getValue().rating()));
            for (var e : sized.lines().entrySet()) System.out.println("LINE\t" + e.getKey() + "\t" + e.getValue().line().service() + "\t" + e.getValue().size() + "\t" + text(e.getValue().rating()));
        }

        private static SheetSizing.Sized size(Flowsheet sheet, FlowsheetRun run, FlowThermo thermo, SheetSizing.Entries entries) {
            return MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> SheetSizing.pass(sheet, run, thermo, entries));
        }

        private static String text(Rating rating) {
            var out = new StringBuilder(rating.lamp().toString());
            for (var r : rating.readings()) out.append(String.format(Locale.ROOT, " %s=%.4g[%.4g,%.4g]", r.limit(), r.value(), r.low(), r.high()));
            return out.toString();
        }

        private static SheetSizing.SizedUnit unit(String member) {
            return sized.units().get(SheetSizing.member(T, member));
        }

        private static SheetSizing.SizedLine line(String member) {
            return sized.lines().get(SheetSizing.member(T, member));
        }

        private static double read(Rating rating, Limit limit) {
            return rating.reading(limit).value();
        }

        private static void closest(Rating rating, Limit limit) {
            assertEquals(Lamp.GREEN, rating.lamp(), () -> text(rating));
            assertSame(limit, rating.closest().limit(), () -> text(rating));
        }

        @Test
        void theColumnTakesEightMetres() {
            var column = sized.units().get(T);
            assertEquals(8.0, ((ColumnSizing.Size) column.size()).diameter());
            closest(column.rating(), Limit.COLUMN_FLOOD);
            assertEquals(70.7, read(column.rating(), Limit.COLUMN_FLOOD), FLOOD);
            assertEquals(40, read(column.rating(), Limit.COLUMN_TRAYS));
        }

        @Test
        void sevenAndAHalfMetresFloodsPastTheShould() {
            var size = ((ColumnSizing.Size) sized.units().get(T).size());
            var kept = size(sheet, run, thermo, new SheetSizing.Entries(DesignBasis.DEFAULT, Map.of(), Map.of(T, new SheetSizing.UnitEntry(new ColumnSizing.Size(7.5, size.layout()), null, null, false))));
            var rating = kept.units().get(T).rating();
            assertEquals(Lamp.YELLOW, rating.lamp(), () -> text(rating));
            assertEquals(80.3, read(rating, Limit.COLUMN_FLOOD), FLOOD);
            assertEquals(1, read(rating, Limit.COLUMN_SOLVES));
            // At one traffic flood goes as one over the area: 70.7 × (8 / 7.5)² is 80.4 (71.3 and 81.1 on Tia Juana).
            assertEquals(read(sized.units().get(T).rating(), Limit.COLUMN_FLOOD) * 64 / 56.25, read(rating, Limit.COLUMN_FLOOD), 1);
        }

        /**
         * The probe's 3 × 1.25 × 4 m rested on the top tray's vapour flashed by the world at V3's temperature, 19 % of it liquid and so
         * denser; that inlet holds less heat than V3's condenser takes out. Heated back by that duty, the inlet is the gas the column
         * sends, faster through the shell. Auto enlarges shells before adding them, retaining excess surface as an advisory.
         * A kept size stands at its door's count (S3b): the probe's
         * shells on a door of three, and the same size on a door of one, which is short of surface. On the medium crude grade (O3 of
         * batch 2026-11-04-open-thermo-data) with condenser carry-over (E31 of that batch) the condenser takes less: one crossflow
         * shell of 2.00 × 6 m on the curves' 509 m² (2.25 × 7 m on 781 m² on Tia Juana), and the widest single standard shell,
         * 2.50 × 8 m, now meets the gas drop too; Auto's crossflow shell is the least surface that meets every limit.
         */
        @Test
        void theCondenserIsSetByItsShellDrop() {
            var condenser = unit("condenser");
            assertEquals(new ExchangerSizing.Size(false, 1, 2.0, 6, com.wormzjl.icheme.science.equipment.ExchangerType.CROSSFLOW), condenser.size(), "qualify scale-up before scaling out");
            assertEquals(Lamp.YELLOW, condenser.rating().lamp());
            assertTrue(condenser.rating().reading(Limit.EXCHANGER_DROP_GAS).met());
            assertTrue(condenser.rating().reading(Limit.EXCHANGER_DROP_LIQUID).met());
            assertTrue(condenser.rating().reading(Limit.EXCHANGER_SURFACE).met());
            assertFalse(condenser.rating().reading(Limit.EXCHANGER_OVER_NEED).met());
            assertEquals(509, condenser.rating().reading(Limit.EXCHANGER_SURFACE).low(), 8);
            assertTrue(condenser.rating().reading(Limit.EXCHANGER_SURFACE).value() >= 1.2*condenser.rating().reading(Limit.EXCHANGER_SURFACE).low());
            var single = new SheetSizing.Entries(DesignBasis.DEFAULT, Map.of(), Map.of(SheetSizing.member(T, "condenser"), new SheetSizing.UnitEntry(new ExchangerSizing.Size(false, 1, 2.5, 8), null, null, false)));
            var singleRating = size(sheet, run, thermo, single).units().get(SheetSizing.member(T, "condenser")).rating();
            assertTrue(singleRating.reading(Limit.EXCHANGER_DROP_GAS).met(), "the widest single standard shell meets the drop on the medium crude; it lost too much on Tia Juana");
            var probe = new SheetSizing.Entries(DesignBasis.DEFAULT, Map.of(), Map.of(SheetSizing.member(T, "condenser"), new SheetSizing.UnitEntry(new ExchangerSizing.Size(false, 3, 1.25, 4), null, null, false)));
            var three = size(door(sheet, json -> json.addProperty("condenser_units", 3)), run, thermo, probe).units().get(SheetSizing.member(T, "condenser"));
            assertEquals(new ExchangerSizing.Size(false, 3, 1.25, 4), three.size());
            var kept = three.rating();
            assertEquals(Lamp.YELLOW, kept.lamp(), () -> text(kept));
            assertEquals(.13, read(kept, Limit.EXCHANGER_DROP_GAS), HUNDREDTH, "over 0.20 on Tia Juana (O3)");
            var one = size(sheet, run, thermo, probe).units().get(SheetSizing.member(T, "condenser"));
            assertEquals(new ExchangerSizing.Size(false, 1, 1.25, 4), one.size(), "the door's one shell");
            assertEquals(Lamp.RED, one.rating().lamp(), () -> text(one.rating()));
        }

        /** The probe held the boot as a cylinder, 7.0 min; the world's boot has its head too, and holds 7.7. The drum is the same. */
        @Test
        void theReceiversBootSetsIt() {
            var receiver = unit("receiver");
            assertEquals(new DrumSizing.Size(UnitType.LYING_DRUM, 4, 10, true), receiver.size());
            closest(receiver.rating(), Limit.DRUM_BOOT_HOLD);
            assertEquals(7.8, read(receiver.rating(), Limit.DRUM_BOOT_HOLD), TENTH);
        }

        /** What the world's flash makes of the top tray's vapour at V3's own temperature: printed for the batch's report. */
        @Test
        void theTopTraysVapourAsTheWorldFlashesIt() {
            var inspection = run.reports().get(T).display().inspection().orElseThrow();
            var top = inspection.nodes().get(1);
            var ids = inspection.input().componentBasis().componentIds();
            var axis = thermo.axis();
            double[] amounts = new double[axis.size()];
            for (int i = 0; i < ids.size(); i++) if (axis.indexOf(ids.get(i)) >= 0) amounts[axis.indexOf(ids.get(i))] += top.vaporFractions().get(i) * top.vaporMolPerSecond();
            amounts[axis.size() - 1] += top.waterVaporMolPerSecond();
            double[] flash = MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> {
                var state = thermo.atTemperature(top.temperatureKelvin(), top.pressurePascal(), amounts, com.wormzjl.icheme.science.fluid.state.SolidInventory.EMPTY);
                return new double[]{state.mass(), thermo.model().vaporMass(state.state()), TestPhases.waterLiquid(state.state()), state.temperature()};
            });
            var overhead = line("overhead");
            System.out.printf(Locale.ROOT, "TOP\t%.2f K\tvapour %.4f of %.3f kg/s\twater liquid %.4f mol/s\toverhead line %s at %.4g kg/m3%n", flash[3], flash[1], flash[0], flash[2], overhead.line().service(), overhead.line().density());
            assertSame(Service.GAS, overhead.line().service(), "heated back by the condenser's duty, the overhead is a gas in the world too");
        }

        @Test
        void theRefluxPumpRunsNearItsTop() {
            var pump = unit("reflux pump");
            assertEquals(new PumpSizing.Size(.2), pump.size());
            closest(pump.rating(), Limit.PUMP_RATED);
            assertEquals(78, read(pump.rating(), Limit.PUMP_RATED), ONE, "119 on Tia Juana; the medium crude grade refluxes less (O3); 74 before condenser carry-over (E31)");
        }

        /**
         * S4c: Auto holds 1.2 × the need where a size can. PA 1 and PA 2 need 222 and 223 m², so 267 m² with the spare: 4 m tubes,
         * 300 m², where the least that held the bare need was 3 m, 225 m². PA 3's 225 m² already holds 1.2 × its 117. On the medium
         * crude grade (O3 of batch 2026-11-04-open-thermo-data) the needs are 202, 240 and 153 m², 242, 288 and 184 with the spare: the
         * same coolers and pumps. With condenser carry-over (E31 of that batch) PA 1 needs 198 m², 238 with the spare.
         */
        @Test
        void eachPumparoundTakesItsCoolerAndPump() {
            double[] needs = {198, 240, 153}, rated = {109, 107, 92}, pumps = {.25, .3, .25}, surfaces = {300, 300, 225};
            int[] tubes = {4, 4, 3};
            for (int i = 0; i < 3; i++) {
                var cooler = unit("pa " + (i + 1) + " cooler");
                assertEquals(new ExchangerSizing.Size(false, 1, 1, tubes[i]), cooler.size(), "PA " + (i + 1));
                assertEquals(surfaces[i], read(cooler.rating(), Limit.EXCHANGER_SURFACE), ONE);
                assertEquals(needs[i], cooler.rating().reading(Limit.EXCHANGER_SURFACE).low(), needs[i] * .02, "PA " + (i + 1) + ": the curves' need, within 2 % of the probe's one LMTD");
                var pump = unit("pa " + (i + 1) + " pump");
                assertEquals(new PumpSizing.Size(pumps[i]), pump.size(), "PA " + (i + 1));
                closest(pump.rating(), Limit.PUMP_RATED);
                assertEquals(rated[i], read(pump.rating(), Limit.PUMP_RATED), ONE);
            }
        }

        @Test
        void theMemberLinesTakeTheProbesBores() {
            // O3 of batch 2026-11-04-open-thermo-data: on the medium crude grade the overhead, receiver outlet and pump outlet take one
            // bore less than the probe's .8, .35 and .25.
            Object[][] probe = {{"overhead", Service.GAS, .7}, {"receiver out", Service.SUCTION_BOILING, .3}, {"pump out", Service.LIQUID, .2}, {"reflux", Service.LIQUID, .2},
                    {"pa 1 draw", Service.DRAW, .5}, {"pa 1 return", Service.LIQUID, .3}, {"pa 2 draw", Service.DRAW, .6}, {"pa 2 return", Service.LIQUID, .3},
                    {"pa 3 draw", Service.DRAW, .45}, {"pa 3 return", Service.LIQUID, .25}};
            var wrong = new ArrayList<String>();
            for (var row : probe) {
                var line = line((String) row[0]);
                if (line.line().service() != row[1] || line.size().bore() != (double) row[2] || line.size().runs() != 1 || line.rating().lamp() != Lamp.GREEN)
                    wrong.add(row[0] + ": " + line.line().service() + " " + line.size() + " " + text(line.rating()));
            }
            assertTrue(wrong.isEmpty(), String.join("\n", wrong));
            assertEquals(31, sized.lines().size(), "21 sheet lines and 10 members");
        }

        @Test
        void p102ReadsItsDrawTraysHeight() {
            var pump = sized.units().get("P-102").rating();
            // W5 D1 of batch 2026-11-11-aqueous-solvents: the column flashes on the network's derived pairs: the draw tray's liquid is no longer above its vapour pressure in the network's flash.
            assertEquals(34.69, read(pump, Limit.PUMP_NPSH), TENTH, "32.35 with the column on the authored matrix (to W5a); 32.3 before condenser carry-over (E31)");
            // O3 of batch 2026-11-04-open-thermo-data: on the medium crude grade P-102 runs at 69 % of its rated flow, under the lamp's 70 %.
            assertEquals(Lamp.YELLOW, pump.lamp(), () -> text(pump));
            assertSame(Limit.PUMP_RATED, pump.closest().limit(), () -> text(pump));
        }

        @Test
        void theBlocksMembersStandUnderIt() {
            var ids = List.copyOf(sized.units().keySet());
            int at = ids.indexOf(T);
            assertEquals(List.of("condenser", "receiver", "reflux pump", "pa 1 cooler", "pa 1 pump", "pa 2 cooler", "pa 2 pump", "pa 3 cooler", "pa 3 pump"),
                    ids.subList(at + 1, at + 10).stream().map(id -> id.substring(T.length() + 1)).toList());
        }

        /** Stop reaches a trial: the sticky stop the run thread sets throws at the first checkpoint inside the column solver. */
        @Test
        void stopReachesATrial() {
            var inside = new boolean[1];
            var stopping = new FlowThermo(SheetFixture.FLUIDS.model(), SheetFixture.CATALOG, SheetFixture.NETWORK, () -> {
                if (StackWalker.getInstance().walk(frames -> frames.anyMatch(f -> f.getClassName().contains("V3ColumnCalculator")))) {
                    inside[0] = true;
                    throw new CancellationException();
                }
            });
            var size = (ColumnSizing.Size) sized.units().get(T).size();
            var entries = new SheetSizing.Entries(DesignBasis.DEFAULT, Map.of(), Map.of(T, new SheetSizing.UnitEntry(new ColumnSizing.Size(8.5, size.layout()), null, null, false)));
            assertThrows(CancellationException.class, () -> size(sheet, run, stopping, entries));
            assertTrue(inside[0]);
        }

        /**
         * No process number moves but a column's: Size all from a door at 9.0 m takes the same sizes as from 8.0 m, and its one re-run,
         * on 8.0 m and those sizes' drops, is the 8.0 m sheet's Size all run.
         */
        @Test
        void aNewDiameterMovesTheProcessOnlyThroughTheColumn() {
            var wide = door(sheet, 9);
            var wideRun = com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.run(wide, SheetFixture.CATALOG, () -> {
            });
            assertEquals(FlowsheetRun.Status.STEADY, wideRun.status());
            var resized = MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.sizeAll(wide, wideRun, thermo));
            var plain = All.ALL;
            assertEquals(8.0, ((ColumnSizing.Size) resized.sized().units().get(T).size()).diameter());
            assertEquals(8.0, resized.sheet().units().stream().filter(u -> u.id().equals(T)).findFirst().orElseThrow().params().number("diameter"));
            assertEquals(plain.sheet().sizing(), resized.sheet().sizing(), "the same sizes from either door");
            assertEquals(FlowsheetRun.Status.STEADY, resized.run().status());
            for (var stream : plain.run().streams().entrySet()) {
                var again = resized.run().streams().get(stream.getKey());
                assertEquals(stream.getValue().temperature(), again.temperature(), .05, stream.getKey());
                assertEquals(stream.getValue().total(), again.total(), 1e-4 * Math.max(1, stream.getValue().total()), stream.getKey());
            }
            closest(resized.sized().units().get(T).rating(), Limit.COLUMN_FLOOD);
            assertEquals(read(plain.sized().units().get(T).rating(), Limit.COLUMN_FLOOD), read(resized.sized().units().get(T).rating(), Limit.COLUMN_FLOOD), FLOOD);
        }

        /**
         * An air-cooled condenser is S2's air cooler on the overhead; the cooling water's lines go with the exchanger. The sample's
         * overhead needs 1949 m² of bare surface at air 25 °C, more than the world's largest bank holds, 1468: one bank is red on its
         * surface, and Auto takes the fewest banks in parallel that condense it (S3b), two, each carrying half the overhead. On the medium
         * crude grade (O3 of batch 2026-11-04-open-thermo-data) it needs 1327 m², 1592 with the spare: still two banks. With condenser carry-over (E31 of that
         * batch) it needs 1265 m², 1518 with the spare: still two banks.
         */
        @Test
        void anAirCooledCondenserTakesAnAirCooler() {
            var air = door(sheet, json -> json.addProperty("condenser", "AIR_COOLER"), "CWS", "CWR");
            var airRun = com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.run(air, SheetFixture.CATALOG, () -> {
            });
            assertEquals(FlowsheetRun.Status.STEADY, airRun.status(), () -> airRun.log().toString());
            var airSized = size(air, airRun, thermo, SheetSizing.Entries.DEFAULT);
            var condenser = airSized.units().get(SheetSizing.member(T, "condenser"));
            System.out.println("AIR\t" + condenser.size() + "\t" + text(condenser.rating()));
            var banks = assertInstanceOf(AirCoolerSizing.Size.class, condenser.size());
            assertEquals(2, banks.banks());
            assertNotEquals(Lamp.RED, condenser.rating().lamp(), () -> text(condenser.rating()));
            assertEquals(1265, condenser.rating().reading(Limit.AIR_COOLER_SURFACE).low(), 10);
            var kept = size(air, airRun, thermo, new SheetSizing.Entries(DesignBasis.DEFAULT, Map.of(), Map.of(SheetSizing.member(T, "condenser"), new SheetSizing.UnitEntry(banks, null, null, false))))
                    .units().get(SheetSizing.member(T, "condenser"));
            assertEquals(banks.withBanks(1), kept.size(), "kept on a door of one bank");
            assertEquals(Lamp.RED, kept.rating().lamp(), () -> text(kept.rating()));
            assertSame(Limit.AIR_COOLER_SURFACE, kept.rating().closest().limit(), () -> text(kept.rating()));
            // Size all writes the count to the door, which the design then asks the assembly for.
            var applied = Proposal.applied(air, airSized);
            var door = com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor.of(applied.units().stream().filter(u -> u.id().equals(T)).findFirst().orElseThrow().params());
            assertEquals(2, door.units().condenser());
            assertNotNull(airSized.units().get(SheetSizing.member(T, "reflux pump")).size());
        }

        /**
         * The owner (C2 addendum): "it's pointless to use twin cooler config if you don't need to cool below 40C". The sample's T-101
         * held on air then water at its own 59 °C receiver gives its trim no duty in the run, so sizing proposes Air, gives the trim the
         * smallest exchanger and reads it yellow on the one Should row, never red.
         */
        @Test
        void aTwinCondenserAboveTheSplitLeavesItsTrimIdle() {
            var twin = door(sheet, json -> json.addProperty("condenser", "AIR_THEN_WATER"), "CWS", "CWR");
            var twinRun = com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.run(twin, SheetFixture.CATALOG, () -> {
            });
            assertEquals(FlowsheetRun.Status.STEADY, twinRun.status(), () -> twinRun.log().toString());
            var readings = twinRun.reports().get(T).readings();
            assertEquals(0, readings.get("condenser_water"), 1e-6 * readings.get("condenser"), "no trim duty above the split");
            var twinSized = size(twin, twinRun, thermo, SheetSizing.Entries.DEFAULT);
            assertEquals(com.wormzjl.icheme.science.equipment.ColumnComponents.Condenser.AIR_COOLER, twinSized.condensers().get(T));
            var trim = twinSized.units().get(SheetSizing.member(T, SheetSizing.TRIM));
            System.out.println("TWIN IDLE\t" + trim.size() + "\t" + text(trim.rating()));
            assertEquals(ExchangerSizing.smallest(), trim.size());
            assertEquals(Lamp.YELLOW, trim.rating().reading(Limit.TWIN_IDLE).lamp(), () -> text(trim.rating()));
            assertEquals(Lamp.YELLOW, trim.rating().lamp(), () -> text(trim.rating()));
            var air = twinSized.units().get(SheetSizing.member(T, "condenser"));
            assertInstanceOf(AirCoolerSizing.Size.class, air.size());
            assertNull(air.rating().reading(Limit.TWIN_IDLE));
            assertNotEquals(Lamp.RED, air.rating().lamp(), () -> text(air.rating()));
        }

        /**
         * A column with reboiler duty boils its bottom tray's liquid in S2's kettle, on what its reboiler pair brings: water at 100 bar
         * and 800 K, hot enough over a crude bottom near 350 °C, as steam at 20 bar is not.
         */
        @Test
        void aReboiledColumnTakesAKettle() {
            var reboiled = door(sheet, json -> json.addProperty("reboiler_duty", 5e6));
            var streams = new ArrayList<>(reboiled.streams());
            var amounts = new java.util.LinkedHashMap<String, Double>();
            amounts.put("Water", 1500.);
            streams.add(new Flowsheet.Stream("HPS", "HPS", null, new Flowsheet.End(T, "reboiler_a"), new Flowsheet.Feed(800, 100e5, amounts, List.of())));
            streams.add(new Flowsheet.Stream("HPC", "HPC", new Flowsheet.End(T, "reboiler_b"), null, null));
            var kettled = new Flowsheet(reboiled.basis(), reboiled.units(), streams, reboiled.settings(), reboiled.sizing(), reboiled.unknown());
            var kettleRun = com.wormzjl.icheme.science.flowsheet.FlowsheetRuns.run(kettled, SheetFixture.CATALOG, () -> {
            });
            assertEquals(FlowsheetRun.Status.STEADY, kettleRun.status(), () -> kettleRun.log().toString());
            var reboiler = size(kettled, kettleRun, thermo, SheetSizing.Entries.DEFAULT).units().get(SheetSizing.member(T, "reboiler"));
            assertInstanceOf(ExchangerSizing.Size.class, reboiler.size());
            assertTrue(((ExchangerSizing.Size) reboiler.size()).kettle());
            assertNotNull(reboiler.rating().reading(Limit.KETTLE_FLUX), () -> text(reboiler.rating()));
            System.out.println("KETTLE\t" + reboiler.size() + "\t" + text(reboiler.rating()));
            assertNotEquals(Lamp.RED, reboiler.rating().lamp(), () -> text(reboiler.rating()));
        }

        /** {@code sheet} with T-101's door at {@code diameter}. */
        private static Flowsheet door(Flowsheet sheet, double diameter) {
            return door(sheet, json -> json.addProperty("diameter", diameter));
        }

        /** {@code sheet} with T-101's door changed by {@code change}, and without the streams {@code dropped}. */
        private static Flowsheet door(Flowsheet sheet, java.util.function.Consumer<com.google.gson.JsonObject> change, String... dropped) {
            var units = new ArrayList<Flowsheet.Unit>();
            for (var unit : sheet.units()) {
                if (!unit.id().equals(T)) {
                    units.add(unit);
                    continue;
                }
                var json = unit.params().json().deepCopy();
                change.accept(json);
                units.add(new Flowsheet.Unit(unit.id(), unit.type(), unit.name(), unit.x(), unit.y(), new Params(json), unit.unknown()));
            }
            var streams = sheet.streams().stream().filter(stream -> !List.of(dropped).contains(stream.id())).toList();
            return new Flowsheet(sheet.basis(), units, streams, sheet.settings(), sheet.sizing(), sheet.unknown());
        }
    }

    /**
     * S4a's gate on a real steady run of the sample (batch 2026-11-02-process-simulator, {@code SIZING_PLAN.md} sections 3.1, 3.6 and
     * 3.11), a slow gate: the pressure paths against the probe's stair in {@code sizing-sample.tsv}, Size all's one re-run on the sized
     * drops as section 3.11 gives it, nothing sized twice, and a rating with no trial column.
     */
    @Nested
    class SizeAllSampleTest {
        /** The probe's stair stands in its printed hundredths. */
        private static final double HUNDREDTH = .005;

        private static Flowsheet sheet;
        private static FlowsheetRun run;
        private static FlowThermo thermo;
        private static SheetSizing.Sized proposal;
        private static SheetSizing.SizeAll all;

        @BeforeAll
        static void sizeTheSample() {
            sheet = Sample.SHEET;
            run = Sample.RUN;
            assertEquals(FlowsheetRun.Status.STEADY, run.status());
            thermo = Sample.THERMO;
            proposal = Sized.PASS;
            all = All.ALL;
            for (var path : proposal.paths()) {
                System.out.printf(Locale.ROOT, "PATH\t%s -> %s\tfrom %.2f head %.2f to %.2f %s\tavailable %.2f lost %.2f spare %.2f%s%n", path.start(), path.end(), path.from(), path.head(), path.to(),
                        path.set() ? "set" : "delivered", path.available(), path.lost(), path.spare(), path.taker() == null ? "" : "\ttaker " + path.taker());
                for (var step : path.steps()) System.out.printf(Locale.ROOT, "STEP\t%s\t%s\t%.3f\t%.3f%n", step.item(), step.passage(), step.lost(), step.typed());
            }
            var before = run.streams();
            var after = all.run().streams();
            System.out.printf(Locale.ROOT, "RUN\tS-6 %.3f -> %.3f bar\tS-7 %.2f -> %.2f C\tH-101 %.3f -> %.3f MW\tvapour %.4f -> %.4f\tstatus %s passes %d%n", before.get("S-6").pressure() / 1e5,
                    after.get("S-6").pressure() / 1e5, before.get("S-7").temperature() - 273.15, after.get("S-7").temperature() - 273.15, run.reports().get("H-101").readings().get("duty") / 1e6,
                    all.run().reports().get("H-101").readings().get("duty") / 1e6, run.reports().get("H-101").readings().get("vapour"), all.run().reports().get("H-101").readings().get("vapour"),
                    all.run().status(), all.run().loops().isEmpty() ? 0 : all.run().loops().get(0).passes());
            all.run().reports().forEach((id, report) -> report.readings().forEach((key, value) -> {
                if (key.endsWith("drop") || key.equals("coils")) System.out.printf(Locale.ROOT, "SIZED\t%s\t%s\t%.4f bar%n", id, key, value / 1e5);
            }));
        }

        private static PressurePath path(SheetSizing.Sized sized, String start) {
            return sized.paths().stream().filter(path -> path.start().equals(start)).findFirst().orElseThrow();
        }

        /**
         * P-101's path: the probe's stair, step by step, to V-101, which holds its outlet and takes the rest. The probe ended the path at
         * V-101's inlet on the doors' drops, 9.00 bar, so V-101's own 2.28 counted as spent; here V-101 is the path's end at its set
         * 2.80 bar (2.72 before S4c), the spare is what it takes, and a run on sized drops leaves it the same. Two steps differ from the probe's, both by
         * the physics: H-101's radiant coil starts where its convection coil ends, 0.46 bar lower, so more of the crude flashes in it
         * (0.11 bar more than the probe's march, which restarted the radiant coil at the inlet's pressure), and the built cabin's coils
         * hold 87.6 MW, not the design's 86.2, on the same 11 passes (0.05 bar more); and the transfer line S-6, which the probe left
         * out because it ended at H-101's outlet, is a step of the path. 3.66 + 0.16 + 0.03 = 3.85 bar on S4a's least sizes.
         *
         * <p>S4c's spare moves four steps, each by its unit's size. PA 1 and PA 2 coolers take 4 m tubes, not 3: longer, 0.36 and 0.40
         * bar, not 0.30 and 0.33. E-101 takes a 1.25 m shell with 5 m tubes, not 1.00 × 6 m: more tubes, shorter, 0.17, not 0.44. H-101's
         * cabin of 12.0 × 45 m holds 105.4 MW on 13 passes of 8 MW, not 87.6 on 11: each pass carries 11/13 of the crude on a pass 2 %
         * longer, 1.48 bar, not 2.11. The path loses 3.07 bar.
         *
         * <p>O4 of batch 2026-11-04-open-thermo-data (crude cuts on Twu 1985 viscosity, the one-term mixture term) makes the crude
         * up to 15 % more viscous at the coolers' temperatures: PA 2 0.40 to 0.41, PA 3 0.29 to 0.30, E-101 0.17 to 0.18, the
         * path 3.07 to 3.10 bar. No size moves.
         *
         * <p>O3 of the same batch runs the sample on the medium crude grade: PA 1 cooler 0.36 to 0.39, E-101 0.18 to 0.20, H-101 1.48 to
         * 1.39, S-6 0.03 to 0.02; the path 3.10 to 3.06 bar. With condenser carry-over (E31 of that batch) H-101 loses
         * 1.385; the path stands.
         */
        @Test
        void p101sPathIsTheProbesStair() {
            var path = path(proposal, "P-101");
            String[] items = {"S-1", "T-101/pa 1 cooler", "S-2", "T-101/pa 2 cooler", "S-3", "T-101/pa 3 cooler", "S-4", "E-101", "S-5", "H-101", "S-6"};
            double[] lost = {.12, .39, .10, .41, .04, .30, .04, .20, .04, 1.385, .02};
            assertEquals(List.of(items), path.steps().stream().map(PressurePath.Step::item).toList());
            for (int i = 0; i < lost.length; i++) assertEquals(lost[i], path.steps().get(i).lost(), HUNDREDTH, items[i]);
            assertEquals(3.06, path.lost(), HUNDREDTH);
            assertEquals(14.00, path.from(), HUNDREDTH);
            assertTrue(path.set());
            assertEquals("V-101", path.taker());
            assertEquals(2.80, path.to(), HUNDREDTH);
            assertEquals(path.from() - path.to() - path.lost(), path.spare(), 1e-12);
        }

        /**
         * The residue's path: the probe's 0.99 bar, the sump's head with the doors' drops, to a product nothing holds. It loses 0.48,
         * not the probe's 0.47: S2's heat curves give E-102 724 m² where the probe's one heat capacity gave 741, which picks another
         * bank, 0.07 bar where the probe's lost 0.05 (plan section 4). S4c's spare takes larger units on it: E-101's 1.25 m shell, whose
         * residue side loses 0.06, not 0.15, and E-102's 6 × 3 bays of 2.5 × 6 m, 0.04, not 0.07. The path loses 0.37; 0.38 since O4 of
         * batch 2026-11-04-open-thermo-data (crude cuts on Twu 1985 viscosity, the one-term mixture term), 0.375 against 0.37. On the
         * medium crude grade (O3 of that batch) E-101's residue side loses 0.07 and the product line 0.16; the head 0.48, the path 0.38; 0.374 with
         * condenser carry-over (E31 of that batch).
         */
        @Test
        void theResiduesPathIsTheProbes() {
            var residue = proposal.paths().stream().filter(p -> p.start().equals("T-101") && p.end().equals("RESIDUE")).findFirst().orElseThrow();
            assertEquals(List.of("S-9", "E-101", "S-10", "E-102", "RESIDUE"), residue.steps().stream().map(PressurePath.Step::item).toList());
            double[] lost = {.005, .07, .09, .045, .16};
            for (int i = 0; i < lost.length; i++) assertEquals(lost[i], residue.steps().get(i).lost(), HUNDREDTH, residue.steps().get(i).item());
            assertEquals(.48, residue.head(), HUNDREDTH);
            assertEquals(2.21, residue.to(), HUNDREDTH);
            assertEquals(.98, residue.available(), HUNDREDTH);
            assertEquals(.374, residue.lost(), HUNDREDTH);
            assertFalse(residue.set());
            assertSame(com.wormzjl.icheme.science.flowsheet.sizing.Rating.NONE, residue.rating());
        }

        /**
         * Section 3.11: the sized coils, coolers and exchanger lose about 2.8 bar at the run's flow where the doors typed 9.00, so S-6
         * rises from 5.00 to about 11.2 bar, less crude flashes at 365 °C, the furnace fires less, the feed reaches the column cooler,
         * and the sheet still closes. The run's sized drops lose 2.69 bar, not the plan's 2.8, which scaled the design stair by the
         * square of the flow: at 11.3 bar the coil's crude flashes less than at the design's 5, so its law loses less than the square
         * law says, and S-6 stands 0.11 bar above the plan's. The tolerances are the plan's "about": its last printed digit, and S-6's
         * 0.11 within 0.15.
         *
         * <p>S4c: with the 20 % spare the sized units are larger and lose less. The coils' 13 passes lose 1.07 bar at the run's flow,
         * and the sized steps 2.09 bar in all, so S-6 stands at 11.9 bar, 0.7 above S4a's least sizes. At 11.9 bar still less crude
         * flashes, a vapour share of 0.52, not 0.54; the furnace fires 73.9 MW, not 74.25, and the feed reaches the column at 349.8 °C, not
         * 350.5. The tolerances stay.
         *
         * <p>S4c also raises V-101's set outlet from 2.72 to 2.80 bar ("always have enough excess"): the feed flashes less across the
         * valve and reaches the column 0.24 K warmer, 359.70 °C before sizing and 350.02 after. Upstream of the valve nothing moves.
         *
         * <p>O3 of batch 2026-11-04-open-thermo-data runs the sample on the medium crude grade, lighter than Tia Juana: S-6 stands at
         * the same 5.00 and 11.9 bar, the furnace fires 73.0 MW before sizing and 67.7 after, the coil's vapour share is 0.67 and 0.41,
         * and the feed reaches the column at 358.5 °C and 347.1 °C.
         *
         * <p>Since the column took the network's water (E30 (a)) its residue leaves a little lighter and cooler, so E-101 gives the
         * crude less heat and the furnace fires 73.03 MW before sizing, not 73.00. With condenser
         * carry-over (E31) it fires 73.08 MW before sizing and 67.60 after. Since the pumps' losses heat the crude (D42 of batch
         * 2026-11-03-rotating-machinery) it fires 73.06 MW before sizing and 67.58 after, and the sized coil's vapour share is 0.415.
         * Since the derived pairs (W3b of batch 2026-11-11-aqueous-solvents) it fires 73.0525 MW before sizing, 3.9 kW under the
         * 73.0565 it fired: the crude's 2.5 mol/s of methane and 5.3 mol/s of ethane mix with its cuts under the derived rule, and
         * the liquid's residual enthalpy stands 3.9 kW higher at the feed's 298 K, 3.4 kW higher at the coil's inlet and 0.2 kW
         * higher at its two-phase outlet. Without those two compounds the crude's enthalpy is the same to the milliwatt at all
         * three states, and the ideal-gas part is the same. 0.24 kW of it is E-101, whose residue the column makes 0.026 mol/s more of.
         */
        @Test
        void theSampleSizedMovesAsThePlanSays() {
            var before = run.streams();
            var after = all.run().streams();
            assertEquals(FlowsheetRun.Status.STEADY, all.run().status());
            assertEquals(5.00, before.get("S-6").pressure() / 1e5, HUNDREDTH);
            assertEquals(11.9, after.get("S-6").pressure() / 1e5, .15);
            assertEquals(73.05, run.reports().get("H-101").readings().get("duty") / 1e6, HUNDREDTH,
                    "73.06 before W3b's derived pairs: methane and ethane in the crude's liquid carry 3.9 kW more residual enthalpy into the coil");
            assertEquals(67.58, all.run().reports().get("H-101").readings().get("duty") / 1e6, .1);
            assertEquals(358.5, before.get("S-7").temperature() - 273.15, .1);
            assertEquals(347.1, after.get("S-7").temperature() - 273.15, .15);
            assertEquals(.67, run.reports().get("H-101").readings().get("vapour"), HUNDREDTH);
            assertEquals(.415, all.run().reports().get("H-101").readings().get("vapour"), HUNDREDTH);
        }

        /** Nothing is sized twice: every Auto item holds the size Size all proposed, and that is the size its rating reads. */
        @Test
        void sizeAllAppliesTheProposalAndRatesIt() {
            var facts = all.sheet().sizing();
            proposal.units().forEach((id, unit) -> {
                if (unit.size() == null || unit.size() instanceof ColumnSizing.Size || unit.size() instanceof com.wormzjl.icheme.science.flowsheet.sizing.ValveSizing.Size) return;
                assertEquals(unit.size(), facts.unit(id).size(), id);
                assertEquals(SizingFacts.Mode.AUTO, facts.unit(id).mode(), id);
                assertEquals(unit.size(), all.sized().units().get(id).size(), id);
            });
            proposal.lines().forEach((id, line) -> {
                assertEquals(line.size(), facts.line(id).size(), id);
                assertEquals(line.size(), all.sized().lines().get(id).size(), id);
            });
            assertEquals(SizingFacts.NONE, sheet.sizing(), "the sheet it was given stays as it was");
        }

        /**
         * Auto's member counts and configurations reach the door. The low-dP condenser qualifies as one shell,
         * which the assembly asks for and builds (at most {@code ColumnComponents.MAX_UNITS}); each cooler keeps one shell.
         */
        @Test
        void theScaledUpCondensersCountReachesTheDoor() {
            var door = com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor.of(all.sheet().units().stream().filter(u -> u.id().equals("T-101")).findFirst().orElseThrow().params());
            assertEquals(new com.wormzjl.icheme.science.equipment.ColumnComponents.Units(1, 1, List.of(1, 1, 1)), door.units());
            var choices = door.choices();
            assertEquals(1, choices.units().condenser(), "a design the calculator and the assembly take");
            assertEquals(com.wormzjl.icheme.science.equipment.ExchangerType.CROSSFLOW,choices.exchangers().condenser());
            assertEquals(1, com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor.of(sheet.units().stream().filter(u -> u.id().equals("T-101")).findFirst().orElseThrow().params()).units().condenser(),
                    "the sheet it was given keeps its door");
        }

        /**
         * Send to block stores the column at its door's diameter with its member counts, the low-dP condenser, and every
         * unit with a shape but the valve, each reading back whole as the block keeps it.
         */
        @Test
        void sendToBlockStoresTheColumnAtItsDoorsDiameterWithItsMemberCounts() {
            var designs = SheetSizing.designs(all.sheet(), all.sized());
            var door = com.wormzjl.icheme.science.flowsheet.unit.ColumnDoor.of(all.sheet().units().stream().filter(u -> u.id().equals("T-101")).findFirst().orElseThrow().params());
            var column = designs.stream().filter(d -> d.column() != null).findFirst().orElseThrow();
            assertEquals("T-101", column.tag());
            assertEquals(door.diameter(), column.column().diameter(), 1e-12);
            assertEquals(door.units(), column.column().components().units());
            var condenser = designs.stream().filter(d -> d.member().equals("condenser")).findFirst().orElseThrow();
            assertEquals(1, condenser.count());
            assertEquals(com.wormzjl.icheme.science.equipment.ExchangerType.CROSSFLOW,condenser.shape().exchangerType());
            var imported=com.wormzjl.icheme.world.level.block.entity.EquipmentConstructorBlockEntity.Design.DEFAULT.landed(condenser);
            assertEquals(condenser.shape().exchangerType(),imported.exchangerType());
            assertEquals(condenser.shape().diameter(),imported.diameter());
            assertEquals((int)condenser.shape().tangentLength(),imported.tangentLength());
            assertEquals(door.exchangers(),column.column().components().exchangers());
            long shaped = all.sized().units().values().stream().filter(u -> u.size() != null && !(u.size() instanceof com.wormzjl.icheme.science.flowsheet.sizing.ValveSizing.Size)).count();
            assertEquals(shaped, designs.size());
            var read = com.wormzjl.icheme.world.level.block.entity.SentDesignTags.read(com.wormzjl.icheme.world.level.block.entity.SentDesignTags.write(designs, List.of()));
            assertTrue(read.whole(), read.repairs().toString());
            assertEquals(designs, read.designs());
        }

        /** A run that has just ended rates every size with no trial column: each column reads its own run's trays. */
        @Test
        void aRatingSolvesNoColumn() {
            var solved = new AtomicBoolean();
            var watching = new FlowThermo(SheetFixture.FLUIDS.model(), SheetFixture.CATALOG, SheetFixture.NETWORK, () -> {
                if (StackWalker.getInstance().walk(frames -> frames.anyMatch(f -> f.getClassName().contains("V3ColumnCalculator")))) solved.set(true);
            });
            var rated = MaterialRuntime.with(SheetFixture.CATALOG, SheetFixture.NETWORK, () -> SheetSizing.rate(all.sheet(), all.run(), watching));
            assertFalse(solved.get());
            assertEquals(all.sized().units().keySet(), rated.units().keySet());
            all.sized().units().forEach((id, unit) -> assertEquals(unit.size(), rated.units().get(id).size(), id));
        }
    }
}
