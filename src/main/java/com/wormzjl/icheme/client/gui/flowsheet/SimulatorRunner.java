package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetEngine;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.Note;
import com.wormzjl.icheme.science.flowsheet.Report;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * The run half of {@link SimulatorHost} (batch 2026-11-02-process-simulator, plan section 3.4): one low-priority daemon thread and
 * one run at a time, on a private copy of the sheet, so nothing an edit does can reach it. The caller takes no part in a run: {@link #run}
 * returns before the engine starts, and all it reads back is two volatile fields. Stop sets a flag that stays set, so a catch deep in
 * a solver that eats one {@link CancellationException} meets the next checkpoint; {@link #close} stops the run and drops its result,
 * so the thread ends with the screen. Minecraft-free.
 */
public final class SimulatorRunner {
    /** The run thread's name. */
    public static final String THREAD = "IChemE simulator run";

    /** What a run calls. A gate stands in front of it to watch the thread and hold a run still; the game has no other. */
    interface Engine {
        Engine REAL = new Engine() {
            @Override public FlowsheetRun run(Flowsheet sheet, MaterialCatalog catalog, Runnable checkpoint, @Nullable FlowsheetRun last, FlowsheetSolver.Progress progress) {
                return FlowsheetEngine.run(sheet, catalog, checkpoint, last, progress);
            }

            @Override public FlowsheetRun alone(Flowsheet sheet, String unit, MaterialCatalog catalog, Runnable checkpoint, Map<String, FlowStream> streams, FlowsheetSolver.Progress progress) {
                return FlowsheetEngine.alone(sheet, unit, catalog, checkpoint, streams, progress);
            }
        };

        FlowsheetRun run(Flowsheet sheet, MaterialCatalog catalog, Runnable checkpoint, @Nullable FlowsheetRun last, FlowsheetSolver.Progress progress);

        FlowsheetRun alone(Flowsheet sheet, String unit, MaterialCatalog catalog, Runnable checkpoint, Map<String, FlowStream> streams, FlowsheetSolver.Progress progress);
    }

    private final Supplier<MaterialCatalog> catalogs;
    private final Engine engine;
    private volatile @Nullable Job job;
    private volatile SimulatorHost.@Nullable Finished last;
    /** The last run that stood steady, which starts the next one's loops: a stopped or failed run leaves it where it was. */
    private volatile @Nullable FlowsheetRun steady;
    private volatile boolean closed;

    /** @param catalogs the catalog of the moment, which each run takes at its start and keeps */
    public SimulatorRunner(Supplier<MaterialCatalog> catalogs) {
        this(catalogs, Engine.REAL);
    }

    SimulatorRunner(Supplier<MaterialCatalog> catalogs, Engine engine) {
        this.catalogs = catalogs;
        this.engine = engine;
    }

    /** Starts a run of {@code sheet}, unless one is in hand. */
    public void run(Flowsheet sheet) {
        start(sheet, null);
    }

    /** Starts a solve of {@code unit} alone on the streams it has now, unless a run is in hand. */
    public void alone(Flowsheet sheet, String unit) {
        start(sheet, unit);
    }

    private void start(Flowsheet sheet, @Nullable String unit) {
        if (closed || job != null) return;
        var next = new Job(sheet.copy(), unit, catalogs.get());
        job = next;
        var thread = new Thread(next, THREAD);
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    /** Stops the run in hand, which ends at its next checkpoint and keeps what it has. */
    public void stop() {
        var running = job;
        if (running != null) running.stopped = true;
    }

    /** Stops the run in hand and drops its result, and starts no other: the screen is gone. */
    public void close() {
        closed = true;
        stop();
    }

    /** The run in hand, or null. */
    public SheetState.@Nullable Running running() {
        var running = job;
        return running == null ? null : running.state();
    }

    /** The last run, or null. */
    public SimulatorHost.@Nullable Finished last() {
        return last;
    }

    /** What a run is at. */
    private record Now(int pass, int passes, String unit, Report.@Nullable Start start) {
    }

    private final class Job implements Runnable, FlowsheetSolver.Progress {
        private final Flowsheet sheet;
        private final @Nullable String unit;
        private final MaterialCatalog catalog;
        private final long began = System.nanoTime();
        private volatile boolean stopped;
        private volatile @Nullable Now now;

        Job(Flowsheet sheet, @Nullable String unit, MaterialCatalog catalog) {
            this.sheet = sheet;
            this.unit = unit;
            this.catalog = catalog;
        }

        private void checkpoint() {
            if (stopped) throw new CancellationException();
        }

        private double seconds() {
            return (System.nanoTime() - began) / 1e9;
        }

        SheetState.Running state() {
            var at = now;
            return at == null ? new SheetState.Running(seconds(), 0, 0, null, null) : new SheetState.Running(seconds(), at.pass, at.passes, at.unit, at.start);
        }

        @Override public void calculation(int pass, int passes, String unit) {
            now = new Now(pass, passes, unit, null);
        }

        @Override public void start(Report.Start start) {
            var at = now;
            if (at != null) now = new Now(at.pass, at.passes, at.unit, start);
        }

        @Override public void run() {
            SimulatorHost.Finished finished;
            try {
                finished = unit == null ? whole() : alone(unit);
            } catch (Throwable broken) {
                // Whatever broke, the lock lifts and the screen says so: a tool's run does not take the game with it.
                finished = new SimulatorHost.Finished(sheet, FlowsheetRun.refused(Note.error("sheet", "run.broken", String.valueOf(broken))), seconds());
            }
            if (finished != null && !closed) last = finished;
            job = null;
        }

        /** The whole sheet. Stop before its first calculation leaves nothing, so what is on show stands. */
        private SimulatorHost.@Nullable Finished whole() {
            var run = engine.run(sheet, catalog, this::checkpoint, steady, this);
            if (run.status() == FlowsheetRun.Status.STEADY) steady = run;
            if (run.status() == FlowsheetRun.Status.STOPPED && run.reports().isEmpty()) return null;
            return new SimulatorHost.Finished(sheet, run, seconds());
        }

        /**
         * One unit on what it has now. Its solve lies over the shown run, and {@link SheetState} greys what lies after it, which has not
         * run on what it now leaves. A failed or stopped solve changes nothing on the sheet, and says why on the status line.
         */
        private SimulatorHost.Finished alone(String id) {
            var shown = last;
            var base = shown == null ? FlowsheetRun.none() : shown.run();
            var ran = shown == null ? sheet : shown.sheet();
            double took = shown == null ? 0 : shown.seconds();
            var run = engine.alone(sheet, id, catalog, this::checkpoint, base.streams(), this);
            boolean solved = run.status() != FlowsheetRun.Status.FAILED && run.status() != FlowsheetRun.Status.STOPPED;
            return new SimulatorHost.Finished(ran, solved ? base.with(run) : base, took, new SimulatorHost.Alone(id, sheet, run.status(), solved ? null : error(run), seconds()));
        }
    }

    private static @Nullable Note error(FlowsheetRun run) {
        for (var note : run.log()) if (note.severity() == Note.Severity.ERROR) return note;
        return null;
    }
}
