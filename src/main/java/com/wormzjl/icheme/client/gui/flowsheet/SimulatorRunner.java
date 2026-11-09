package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.FlowThermo;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetEngine;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.FlowsheetSolver;
import com.wormzjl.icheme.science.flowsheet.Note;
import com.wormzjl.icheme.science.flowsheet.Report;
import com.wormzjl.icheme.science.flowsheet.RevisionNotice;
import com.wormzjl.icheme.science.flowsheet.sizing.Proposal;
import com.wormzjl.icheme.science.flowsheet.sizing.SheetSizing;
import com.wormzjl.icheme.science.fluid.thermo.FluidMaterialCatalog;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
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

        /** Size all on {@code run}. Stop throws {@link CancellationException}. */
        default SheetSizing.SizeAll sizeAll(Flowsheet sheet, FlowsheetRun run, MaterialCatalog catalog, Runnable checkpoint, FlowsheetSolver.Progress progress) {
            return thermo(catalog, checkpoint, thermo -> Proposal.sizeAll(sheet, run, thermo, progress));
        }

        /** Every item rated at its size on {@code run}. */
        default SheetSizing.Sized rate(Flowsheet sheet, FlowsheetRun run, MaterialCatalog catalog, Runnable checkpoint) {
            return thermo(catalog, checkpoint, thermo -> SheetSizing.rate(sheet, run, thermo));
        }
    }

    /**
     * {@code on} with the world package's thermo, on whose axis a run's streams stand. The sizing takes the network's view of the
     * sheet's compounds from it, as the run does ({@code SheetSizing.pass}).
     */
    private static <T> T thermo(MaterialCatalog catalog, Runnable checkpoint, Function<FlowThermo, T> on) {
        String network = FluidMaterialCatalog.networkPackage(catalog);
        return MaterialRuntime.with(catalog, network, () -> on.apply(new FlowThermo(FluidThermodynamics.forNetwork(catalog, network), catalog, network, checkpoint)));
    }

    /**
     * Every item of a sheet rated at its size: state, never saved (plan section 3.1 item 5).
     *
     * @param sheet the sheet rated, the job's own copy
     * @param run   the run it was rated on
     * @param edit  whether {@code sheet} is Size all's edit, which the editor takes as one step
     */
    public record Rated(Flowsheet sheet, SimulatorHost.Finished run, SheetSizing.Sized sized, boolean edit) {
    }

    private final Supplier<MaterialCatalog> catalogs;
    private final Engine engine;
    private final AtomicReference<@Nullable Job> job = new AtomicReference<>();
    private volatile SimulatorHost.@Nullable Finished last;
    private volatile @Nullable Rated rated;
    private volatile @Nullable String broken;
    /**
     * The last run that stood steady, which starts the next one's loops and its columns where they ended there (owner 2026-11-04,
     * P1 and P2): a stopped or failed run leaves it where it was, and Stop leaves its columns to the learned start.
     */
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

    /** What a job does. */
    private enum Kind {
        RUN, ALONE, SIZE, RATE
    }

    /** Starts a run of {@code sheet}, unless one is in hand. */
    public void run(Flowsheet sheet) {
        start(sheet, null, Kind.RUN);
    }

    /** Starts a solve of {@code unit} alone on the streams it has now, unless a run is in hand. */
    public void alone(Flowsheet sheet, String unit) {
        start(sheet, unit, Kind.ALONE);
    }

    /**
     * Starts Size all of {@code sheet} on the last run (plan section 3.1), unless a run is in hand. It ends with {@link #rated()}
     * holding the sized sheet as an edit, and where the sizes moved the run, that run as the last. Stop changes nothing.
     */
    public void size(Flowsheet sheet) {
        if (last != null) start(sheet, null, Kind.SIZE);
    }

    /**
     * Rates {@code sheet}'s items at their sizes on the last run, quietly: no status, no lock, and a run or Size all takes its place.
     * The lamps a block's stored run lacks, and those an edit of a size has moved, are rebuilt so.
     */
    public void rate(Flowsheet sheet) {
        if (last != null) start(sheet, null, Kind.RATE);
    }

    private void start(Flowsheet sheet, @Nullable String unit, Kind kind) {
        if (closed) return;
        var held = job.get();
        if (held != null) {
            if (held.kind != Kind.RATE || kind == Kind.RATE) return;
            // A rating gives way: the run rates what it leaves.
            held.stopped = true;
        }
        var next = new Job(sheet.copy(), unit, catalogs.get(), kind, last);
        if (!job.compareAndSet(held, next)) return;
        broken = null;
        var thread = new Thread(next, THREAD);
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    /**
     * Stops the run in hand, which ends at its next checkpoint and keeps what it has. The next run starts its columns from the learned
     * start: a warm start that took long is not tried again.
     */
    public void stop() {
        var running = job.get();
        if (running != null) running.stopped = true;
        var held = steady;
        if (held != null) steady = held.cold();
    }

    /** Stops the run in hand and drops its result, and starts no other: the screen is gone. */
    public void close() {
        closed = true;
        stop();
    }

    /**
     * What a block held, which stands as the last run until the next. Where it stood steady it starts the next run's loops, as a run
     * on this client does (owner 2026-11-04, P1). A column's warm start lives only in this client's memory and a stored run has none,
     * so the first run after opening starts its columns from the learned start.
     */
    public void restore(SimulatorHost.Finished stored) {
        if (closed || job.get() != null) return;
        last = stored;
        if (stored.run().status() == FlowsheetRun.Status.STEADY) steady = stored.run();
    }

    /** The run or Size all in hand, or null: a quiet rating is none. */
    public SheetState.@Nullable Running running() {
        var running = job.get();
        return running == null || running.kind == Kind.RATE ? null : running.state();
    }

    /** Whether a quiet rating is in hand. */
    public boolean rating() {
        var running = job.get();
        return running != null && running.kind == Kind.RATE;
    }

    /** The last run, or null. */
    public SimulatorHost.@Nullable Finished last() {
        return last;
    }

    /** The last rating, or null. It holds for the run it names only. */
    public @Nullable Rated rated() {
        return rated;
    }

    /** Why the last Size all or rating broke, or null. */
    public @Nullable String broken() {
        return broken;
    }

    /** What a run is at. */
    private record Now(int pass, int passes, String unit, Report.@Nullable Start start) {
    }

    private final class Job implements Runnable, FlowsheetSolver.Progress {
        private final Flowsheet sheet;
        private final @Nullable String unit;
        private final MaterialCatalog catalog;
        private final Kind kind;
        /** The run on show when it began, which Solve alone, Size all and a rating read. */
        private final SimulatorHost.@Nullable Finished base;
        private final long began = System.nanoTime();
        private volatile boolean stopped;
        private volatile @Nullable Now now;
        private volatile SheetState.Task task;
        private SimulatorHost.@Nullable Finished finished;
        private @Nullable Rated made;

        Job(Flowsheet sheet, @Nullable String unit, MaterialCatalog catalog, Kind kind, SimulatorHost.@Nullable Finished base) {
            this.sheet = sheet;
            this.unit = unit;
            this.catalog = catalog;
            this.kind = kind;
            this.base = base;
            task = switch (kind) {
                case SIZE -> SheetState.Task.SIZE;
                case RATE -> SheetState.Task.RATE;
                default -> SheetState.Task.RUN;
            };
        }

        private void checkpoint() {
            if (stopped) throw new CancellationException();
        }

        private double seconds() {
            return (System.nanoTime() - began) / 1e9;
        }

        SheetState.Running state() {
            var at = now;
            return at == null ? new SheetState.Running(seconds(), 0, 0, null, null, task) : new SheetState.Running(seconds(), at.pass, at.passes, at.unit, at.start, task);
        }

        @Override public void calculation(int pass, int passes, String unit) {
            now = new Now(pass, passes, unit, null);
        }

        @Override public void start(Report.Start start) {
            var at = now;
            if (at != null) now = new Now(at.pass, at.passes, at.unit, start);
        }

        @Override public void run() {
            try {
                switch (kind) {
                    case RUN -> whole();
                    case ALONE -> alone(unit);
                    case SIZE -> sizeAll();
                    case RATE -> rate();
                }
            } catch (CancellationException stop) {
                // Stop in Size all or in a rating: nothing changes.
                finished = null;
                made = null;
            } catch (Throwable failure) {
                // Whatever broke, the lock lifts and the screen says so: a tool's run does not take the game with it.
                if (kind == Kind.RUN || kind == Kind.ALONE) finished = new SimulatorHost.Finished(sheet, FlowsheetRun.refused(Note.error("sheet", "run.broken", String.valueOf(failure))), seconds());
                else broken = String.valueOf(failure);
                made = null;
            }
            if (!closed && job.get() == this) {
                if (finished != null) last = finished;
                if (made != null) rated = made;
            }
            job.compareAndSet(this, null);
        }

        /** The whole sheet, then its items rated at their sizes. Stop before its first calculation leaves nothing, so what is on show stands. */
        private void whole() {
            var run = engine.run(sheet, catalog, this::checkpoint, steady, this);
            if (run.status() == FlowsheetRun.Status.STEADY) steady = run;
            if (run.status() == FlowsheetRun.Status.STOPPED && run.reports().isEmpty()) return;
            var ran = new SimulatorHost.Finished(sheet, run, seconds(), null, RevisionNotice.tokens(sheet, catalog));
            finished = ran;
            if (run.status() == FlowsheetRun.Status.STOPPED || run.status() != FlowsheetRun.Status.STEADY && !sheet.sizing().any()) return;
            task = SheetState.Task.RATE;
            now = null;
            try {
                made = new Rated(sheet, ran, engine.rate(sheet, run, catalog, this::checkpoint), false);
            } catch (CancellationException stop) {
                // The run stands; the tab rates it when it opens.
            }
        }

        /**
         * One unit on what it has now. Its solve lies over the shown run, and {@link SheetState} greys what lies after it, which has not
         * run on what it now leaves. A failed or stopped solve changes nothing on the sheet, and says why on the status line.
         */
        private void alone(String id) {
            var shown = base;
            var at = shown == null ? FlowsheetRun.none() : shown.run();
            var ran = shown == null ? sheet : shown.sheet();
            double took = shown == null ? 0 : shown.seconds();
            var run = engine.alone(sheet, id, catalog, this::checkpoint, at.streams(), this);
            boolean solved = run.status() != FlowsheetRun.Status.FAILED && run.status() != FlowsheetRun.Status.STOPPED;
            finished = new SimulatorHost.Finished(ran, solved ? at.with(run) : at, took, new SimulatorHost.Alone(id, sheet, run.status(), solved ? null : error(run), seconds()), RevisionNotice.tokens(sheet, catalog));
        }

        /**
         * Size all on the run on show: the sized sheet is an edit, and where the sizes moved the run, the run its ratings read
         * replaces the one on show. Stop throws, and nothing changes.
         */
        private void sizeAll() {
            var shown = base;
            if (shown == null) return;
            var all = engine.sizeAll(sheet, shown.run(), catalog, this::checkpoint, this);
            boolean ran = all.run() != shown.run();
            if (ran && all.run().status() == FlowsheetRun.Status.STEADY) steady = all.run();
            var after = ran ? new SimulatorHost.Finished(all.sheet(), all.run(), seconds(), null, RevisionNotice.tokens(all.sheet(), catalog))
                    : new SimulatorHost.Finished(all.sheet(), shown.run(), shown.seconds(), shown.alone(), shown.ranOn());
            finished = after;
            made = new Rated(all.sheet(), after, all.sized(), true);
        }

        /** The run on show, rated at the sheet's sizes. A sheet whose units or lines are not the run's has nothing it can read there, so every item is red. */
        private void rate() {
            var shown = base;
            if (shown == null) return;
            var run = SheetState.sameShape(sheet, shown.sheet()) ? shown.run() : FlowsheetRun.none();
            made = new Rated(sheet, shown, engine.rate(sheet, run, catalog, this::checkpoint), false);
        }
    }

    private static @Nullable Note error(FlowsheetRun run) {
        for (var note : run.log()) if (note.severity() == Note.Severity.ERROR) return note;
        return null;
    }
}
