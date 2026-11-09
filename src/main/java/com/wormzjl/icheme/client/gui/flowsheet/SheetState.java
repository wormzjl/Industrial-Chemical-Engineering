package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.HeatControlColumn;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.Note;
import com.wormzjl.icheme.science.flowsheet.Report;
import com.wormzjl.icheme.science.flowsheet.sizing.SizedPassages;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.jetbrains.annotations.Nullable;

/**
 * From a run and the edits since: each unit's look, the tears, the status (batch 2026-11-02-process-simulator, W6_SCREEN_PLAN.md section 2).
 * What a run knows is judged against the facts it ran on. A signature holds what a solve reads, so a name or a place changes nothing;
 * a unit or stream whose signature moved greys, with all that lies downstream of it, and an Undo back to the same facts ungreys them.
 */
public final class SheetState {
    public enum Tone {
        SOLVED, WARNING, FAILED, NONE, ACTIVE
    }

    /** What a job in hand does: run, Size all (with its run again), or rate the sizes at a run's end. */
    public enum Task {
        RUN, SIZE, RATE
    }

    /**
     * A run in hand, as its host reports it.
     *
     * @param pass  which pass of a loop, 0 outside one
     * @param unit  what it is solving, or null before the first calculation
     * @param start where that block's solve set out from, or null
     */
    public record Running(double seconds, int pass, int passes, @Nullable String unit, Report.@Nullable Start start, Task task) {
    }

    private final Flowsheet sheet;
    private final @Nullable SheetResults results;
    private final @Nullable Running running;
    private final double seconds;
    private final SimulatorHost.@Nullable Alone alone;
    private final Set<String> stale;
    private final Map<String, StreamProps> props;

    /**
     * @param ran     the sheet the run was made from, or null with no run
     * @param seconds how long the run took
     * @param running a run in hand, or null
     */
    public SheetState(Flowsheet sheet, @Nullable Flowsheet ran, @Nullable FlowsheetRun run, double seconds, @Nullable Running running) {
        this(sheet, ran, run, seconds, running, null);
    }

    /** @param alone what a unit solved alone has done since the run, or null */
    public SheetState(Flowsheet sheet, @Nullable Flowsheet ran, @Nullable FlowsheetRun run, double seconds, @Nullable Running running, SimulatorHost.@Nullable Alone alone) {
        this.sheet = sheet;
        this.results = run == null || ran == null ? null : new SheetResults(ran, run);
        this.seconds = seconds;
        this.running = running;
        this.alone = alone;
        stale = results == null ? new HashSet<>() : greyed(sheet, ran);
        props = new HashMap<>();
        if (alone != null && results != null && alone.status() != FlowsheetRun.Status.FAILED && alone.status() != FlowsheetRun.Status.STOPPED) settle(alone);
    }

    private SheetState(SheetState base, @Nullable Running running) {
        sheet = base.sheet;
        results = base.results;
        seconds = base.seconds;
        alone = base.alone;
        stale = base.stale;
        props = base.props;
        this.running = running;
    }

    /** {@code sheet} against {@code last}, its host's last run. */
    public static SheetState of(Flowsheet sheet, SimulatorHost.@Nullable Finished last) {
        return last == null ? new SheetState(sheet, null, null, 0, null) : new SheetState(sheet, last.sheet(), last.run(), last.seconds(), null, last.alone());
    }

    /** This state with {@code running} in hand. It shares all the rest, so a state the screen keeps need not be worked out again for each frame's clock. */
    public SheetState running(@Nullable Running running) {
        return new SheetState(this, running);
    }

    public boolean running() {
        return running != null;
    }

    /** What a shown stream reads as, worked out once for this state: a frame asks for it again and again. */
    StreamProps props(String stream, Supplier<StreamProps> worked) {
        return props.computeIfAbsent(stream, id -> worked.get());
    }

    private static String unit(Flowsheet.Unit unit) {
        return unit.type() + " " + unit.params().json();
    }

    private static String end(Flowsheet.@Nullable End end) {
        return end == null ? "-" : end.unit() + " " + end.port();
    }

    private static String stream(Flowsheet.Stream stream) {
        var text = new StringBuilder(end(stream.from())).append(">").append(end(stream.to()));
        var feed = stream.feed();
        if (feed != null) {
            text.append(' ').append(feed.temperature()).append(' ').append(feed.pressure()).append(' ').append(feed.amounts());
            for (var solid : feed.solids()) text.append(' ').append(solid.material()).append(' ').append(solid.micrometres()).append(' ').append(solid.massFlow());
        }
        return text.toString();
    }

    /** A unit solved alone stands on what it had, however grey that was, until it or what feeds it changes. What lies after it has not run on what it now leaves, and a loop back to it does not grey it. */
    private void settle(SimulatorHost.Alone alone) {
        if (greyed(sheet, alone.sheet()).contains(alone.unit())) return;
        var work = new ArrayDeque<String>();
        var out = streamsFrom(sheet).getOrDefault(alone.unit(), Set.of());
        for (var stream : out) if (stream.to() != null) work.add(stream.to().unit());
        walk(stale, work, streamsFrom(sheet), alone.unit());
        stale.remove(alone.unit());
        for (var stream : out) stale.remove(stream.id());
    }

    private static Map<String, Set<Flowsheet.Stream>> streamsFrom(Flowsheet sheet) {
        var from = new HashMap<String, Set<Flowsheet.Stream>>();
        for (var stream : sheet.streams()) if (stream.from() != null) from.computeIfAbsent(stream.from().unit(), k -> new HashSet<>()).add(stream);
        return from;
    }

    /** Whether {@code a} and {@code b} hold the same units and lines by id: what a rating needs to read one's items on the other's run. */
    public static boolean sameShape(Flowsheet a, Flowsheet b) {
        return ids(a.units().stream().map(Flowsheet.Unit::id).toList()).equals(ids(b.units().stream().map(Flowsheet.Unit::id).toList()))
                && ids(a.streams().stream().map(Flowsheet.Stream::id).toList()).equals(ids(b.streams().stream().map(Flowsheet.Stream::id).toList()));
    }

    private static Set<String> ids(List<String> ids) {
        return new HashSet<>(ids);
    }

    /** Whether {@code a} and {@code b} hold the same facts for a solve and for sizing: a name or a place changes nothing. */
    public static boolean same(Flowsheet a, Flowsheet b) {
        if (!a.sizing().equals(b.sizing()) || a.units().size() != b.units().size() || a.streams().size() != b.streams().size()) return false;
        var units = new HashMap<String, String>();
        var streams = new HashMap<String, String>();
        for (var unit : b.units()) units.put(unit.id(), unit(unit));
        for (var stream : b.streams()) streams.put(stream.id(), stream(stream));
        for (var unit : a.units()) if (!unit(unit).equals(units.get(unit.id()))) return false;
        for (var stream : a.streams()) if (!stream(stream).equals(streams.get(stream.id()))) return false;
        return true;
    }

    /**
     * What changed since {@code ran}, and all that lies downstream of it in {@code sheet}. A size whose drops a unit's run reads
     * ({@link SizedPassages#reads}) is one of its facts.
     */
    private static Set<String> greyed(Flowsheet sheet, Flowsheet ran) {
        var stale = new HashSet<String>();
        var units = new HashMap<String, String>();
        var reads = new HashMap<String, List<Object>>();
        var streams = new HashMap<String, String>();
        var was = new HashMap<String, Flowsheet.Stream>();
        for (var unit : ran.units()) {
            units.put(unit.id(), unit(unit));
            reads.put(unit.id(), SizedPassages.reads(ran.sizing(), unit));
        }
        for (var stream : ran.streams()) {
            streams.put(stream.id(), stream(stream));
            was.put(stream.id(), stream);
        }
        var work = new ArrayDeque<String>();
        for (var unit : sheet.units())
            if (!unit(unit).equals(units.get(unit.id())) || !SizedPassages.reads(sheet.sizing(), unit).equals(reads.get(unit.id()))) work.add(unit.id());
        for (var stream : sheet.streams()) {
            if (stream(stream).equals(streams.get(stream.id()))) continue;
            stale.add(stream.id());
            if (stream.to() != null) work.add(stream.to().unit());
            // A line that left a port greys what it fed, though it feeds it no more.
            var before = was.get(stream.id());
            if (before != null && before.to() != null) work.add(before.to().unit());
        }
        for (var stream : ran.streams())
            if (sheet.streams().stream().noneMatch(s -> s.id().equals(stream.id())) && stream.to() != null) work.add(stream.to().unit());
        walk(stale, work, streamsFrom(sheet), null);
        return stale;
    }

    private static void walk(Set<String> stale, ArrayDeque<String> work, Map<String, Set<Flowsheet.Stream>> downstream, @Nullable String stays) {
        while (!work.isEmpty()) {
            String id = work.remove();
            if (id.equals(stays) || !stale.add(id)) continue;
            for (var stream : downstream.getOrDefault(id, Set.of())) {
                stale.add(stream.id());
                if (stream.to() != null) work.add(stream.to().unit());
            }
        }
    }

    /** Whether edits have greyed anything since the run. */
    public boolean edited() {
        return !stale.isEmpty();
    }

    /**
     * Whether the results are out of date: no run yet on a sheet with something on it, or edits greyed something since the
     * run, which the status line says as "Changed since the last run" ({@link #edited}). Run is lit while it is.
     */
    public boolean outOfDate() {
        return results == null ? !sheet.units().isEmpty() || !sheet.streams().isEmpty() : edited();
    }

    /** Whether edits have greyed a unit or stream. */
    public boolean stale(String id) {
        return stale.contains(id);
    }

    public @Nullable SheetResults results() {
        return results;
    }

    /** The results a unit or stream may show: none once it is greyed. */
    public @Nullable SheetResults shown(String id) {
        return stale(id) ? null : results;
    }

    /** A unit's look. */
    public Tone tone(String unit) {
        if (running != null && unit.equals(running.unit())) return Tone.ACTIVE;
        if (results == null || stale(unit)) return Tone.NONE;
        var outcome = results.outcome(unit);
        if (outcome == null || outcome == FlowsheetRun.Outcome.NOT_SOLVED) return Tone.NONE;
        if (outcome == FlowsheetRun.Outcome.FAILED) return Tone.FAILED;
        return results.warned(unit) ? Tone.WARNING : Tone.SOLVED;
    }

    /** Whether a stream was torn in the run, and has not been greyed since. */
    public boolean tear(String stream) {
        return results != null && !stale(stream) && results.tears().contains(stream);
    }

    /** Whether the sheet stands steady and unchanged, so Sizing may open. */
    public boolean steady() {
        return results != null && results.run().status() == FlowsheetRun.Status.STEADY && stale.isEmpty();
    }

    /** The status line, as a text and a tone. */
    public record Status(String text, int tone) {
    }

    /** The status display: a run in hand, then the last run's result; {@code compact} leaves out the counts a small window has no room for. */
    public Status status(HeatControlColumn.Texts t, SheetUnits units, boolean compact) {
        String key = "flowsheet.status.";
        if (running != null) {
            String seconds = SheetUnits.number(1, running.seconds());
            if (running.task() == Task.RATE) return new Status(t.get(key + "rating", seconds), Palette.ACCENT);
            if (running.unit() == null) return new Status(t.get(key + (running.task() == Task.SIZE ? "sizing" : "starting"), seconds), Palette.ACCENT);
            String unit = NoteText.subject(running.unit(), sheet);
            if (running.start() != null) unit = t.get(key + (running.start() == Report.Start.LEARNED ? "from_learned" : "from_last"), unit);
            String text = running.pass() == 0 ? t.get(key + "running_step", seconds, unit) : t.get(key + "running", seconds, running.pass(), running.passes(), unit);
            return new Status(running.task() == Task.SIZE ? t.get(key + "sizing_run", text) : text, Palette.ACCENT);
        }
        if (results == null) return new Status(t.get(key + "ready"), Palette.MUTED);
        var run = results.run();
        var notes = NoteText.all(run);
        Map<Note.Severity, List<Note>> by = new HashMap<>();
        for (var note : notes) by.computeIfAbsent(note.severity(), k -> new java.util.ArrayList<>()).add(note);
        var errors = by.getOrDefault(Note.Severity.ERROR, List.of());
        var warnings = by.getOrDefault(Note.Severity.WARNING, List.of());
        String time = SheetUnits.number(1, seconds);
        String head = switch (run.status()) {
            case FAILED -> errors.isEmpty() ? t.get(key + "failed") : NoteText.text(errors.getFirst(), t, sheet, units);
            case STOPPED -> t.get(key + "stopped");
            case NOT_STEADY -> errors.isEmpty() ? t.get(key + "not_steady", results.passes()) : t.get(key + "not_steady_because", NoteText.text(errors.getFirst(), t, sheet, units));
            case STEADY -> compact ? t.get(key + "steady_short", results.passes(), time)
                    : t.get(key + "steady", results.passes(), time, sheet.streams().size(), sheet.units().size(), SheetUnits.number(2, results.massGap()), SheetUnits.number(2, results.heatGap()));
        };
        int tone = run.status() == FlowsheetRun.Status.FAILED || run.status() == FlowsheetRun.Status.NOT_STEADY || !warnings.isEmpty() ? Palette.WARN : Palette.ACCENT;
        if (alone != null) {
            String unit = NoteText.subject(alone.unit(), sheet);
            head = switch (alone.status()) {
                case FAILED -> t.get(key + "alone_failed", unit, alone.why() == null ? t.get(key + "failed") : NoteText.text(alone.why(), t, sheet, units));
                case STOPPED -> t.get(key + "alone_stopped", unit);
                default -> t.get(key + "alone", unit, SheetUnits.number(1, alone.seconds()));
            };
            tone = alone.status() == FlowsheetRun.Status.FAILED ? Palette.WARN : Palette.ACCENT;
        }
        if (!warnings.isEmpty()) {
            String first = NoteText.text(warnings.getFirst(), t, sheet, units);
            head += " " + t.get(warnings.size() == 1 ? key + "warning" : key + "warnings", warnings.size(), first);
        }
        if (!stale.isEmpty()) {
            head = t.get(key + "edited") + " " + head;
            if (tone == Palette.ACCENT) tone = Palette.MUTED;
        }
        return new Status(head, tone);
    }
}
