package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.HeatControlColumn;
import com.wormzjl.icheme.client.gui.common.kit.Palette;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.Note;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** A run in hand, as its host reports it. */
    public record Running(double seconds, int pass, int passes, @Nullable String unit) {
    }

    private final Flowsheet sheet;
    private final @Nullable SheetResults results;
    private final @Nullable Running running;
    private final double seconds;
    private final Set<String> stale = new HashSet<>();

    /**
     * @param ran     the sheet the run was made from, or null with no run
     * @param seconds how long the run took
     * @param running a run in hand, or null
     */
    public SheetState(Flowsheet sheet, @Nullable Flowsheet ran, @Nullable FlowsheetRun run, double seconds, @Nullable Running running) {
        this.sheet = sheet;
        this.results = run == null || ran == null ? null : new SheetResults(ran, run);
        this.seconds = seconds;
        this.running = running;
        if (results != null) stale(ran);
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

    /** Marks what changed since {@code ran}, and all that lies downstream of it in this sheet. */
    private void stale(Flowsheet ran) {
        var units = new HashMap<String, String>();
        var streams = new HashMap<String, String>();
        var was = new HashMap<String, Flowsheet.Stream>();
        for (var unit : ran.units()) units.put(unit.id(), unit(unit));
        for (var stream : ran.streams()) {
            streams.put(stream.id(), stream(stream));
            was.put(stream.id(), stream);
        }
        var work = new ArrayDeque<String>();
        for (var unit : sheet.units()) if (!unit(unit).equals(units.get(unit.id()))) work.add(unit.id());
        var downstream = new HashMap<String, Set<Flowsheet.Stream>>();
        for (var stream : sheet.streams()) {
            if (stream.from() != null) downstream.computeIfAbsent(stream.from().unit(), k -> new HashSet<>()).add(stream);
            if (stream(stream).equals(streams.get(stream.id()))) continue;
            stale.add(stream.id());
            if (stream.to() != null) work.add(stream.to().unit());
            // A line that left a port greys what it fed, though it feeds it no more.
            var before = was.get(stream.id());
            if (before != null && before.to() != null) work.add(before.to().unit());
        }
        for (var stream : ran.streams())
            if (sheet.streams().stream().noneMatch(s -> s.id().equals(stream.id())) && stream.to() != null) work.add(stream.to().unit());
        while (!work.isEmpty()) {
            String id = work.remove();
            if (!stale.add(id)) continue;
            for (var stream : downstream.getOrDefault(id, Set.of())) {
                stale.add(stream.id());
                if (stream.to() != null) work.add(stream.to().unit());
            }
        }
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
            if (running.unit() == null) return new Status(t.get(key + "starting", seconds), Palette.ACCENT);
            return new Status(t.get(key + "running", seconds, running.pass(), running.passes(), NoteText.subject(running.unit(), sheet)), Palette.ACCENT);
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
