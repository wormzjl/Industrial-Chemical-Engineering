package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.FlowsheetRun;
import com.wormzjl.icheme.science.flowsheet.Report;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * A run as the screen reads it (batch 2026-11-02-process-simulator, W6_SCREEN_PLAN.md section 6): the run keys its outcomes and reports
 * by calculation, {@code unit} or {@code unit/part}, a source by {@code feed <stream id>}, so a unit's look and readings gather its parts here.
 * {@link #streams} may hold ids the sheet does not: a free outlet writes a stream {@code unit:port}.
 */
public record SheetResults(Flowsheet sheet, FlowsheetRun run) {
    /** The calculation ids of a unit. */
    private List<String> ids(String unit) {
        var ids = new ArrayList<String>();
        for (var id : run.outcomes().keySet()) if (id.equals(unit) || id.startsWith(unit + "/")) ids.add(id);
        return ids;
    }

    /** A unit's worst outcome, or null where the run never reached it. */
    public FlowsheetRun.@Nullable Outcome outcome(String unit) {
        FlowsheetRun.Outcome worst = null;
        for (var id : ids(unit)) {
            var outcome = run.outcomes().get(id);
            if (worst == null || outcome == FlowsheetRun.Outcome.FAILED || outcome == FlowsheetRun.Outcome.NOT_SOLVED && worst == FlowsheetRun.Outcome.SOLVED) worst = outcome;
        }
        return worst;
    }

    /** The report of a unit or {@code unit/part}, by its calculation id. */
    public @Nullable Report report(String calculation) {
        return run.reports().get(calculation);
    }

    /** A reading of a unit, the first of its parts that has it, or NaN. */
    public double reading(String unit, String key) {
        var whole = run.reports().get(unit);
        if (whole != null && whole.readings().containsKey(key)) return whole.readings().get(key);
        for (var id : ids(unit)) {
            var report = run.reports().get(id);
            if (report != null && report.readings().containsKey(key)) return report.readings().get(key);
        }
        return Double.NaN;
    }

    /** A reading of one part, {@code unit/part}, or NaN. */
    public double part(String unit, String part, String key) {
        var report = run.reports().get(unit + "/" + part);
        return report == null ? Double.NaN : report.readings().getOrDefault(key, Double.NaN);
    }

    public @Nullable FlowStream stream(String id) {
        return run.streams().get(id);
    }

    /** Whether a warning or an error names {@code id}, or one of its parts. */
    public boolean warned(String id) {
        for (var note : run.log()) if (note.severity() != com.wormzjl.icheme.science.flowsheet.Note.Severity.INFO && (note.subject().equals(id) || note.subject().startsWith(id + "/"))) return true;
        for (var calculation : ids(id)) {
            var report = run.reports().get(calculation);
            if (report != null) for (var note : report.notes()) if (note.severity() != com.wormzjl.icheme.science.flowsheet.Note.Severity.INFO) return true;
        }
        return false;
    }

    /** The stream ids a loop tore. */
    public List<String> tears() {
        var tears = new ArrayList<String>();
        for (var loop : run.loops()) tears.addAll(loop.tears());
        return tears;
    }

    /** The passes the longest loop took; a sheet with no loop, one. */
    public int passes() {
        int passes = 1;
        for (var loop : run.loops()) passes = Math.max(passes, loop.passes());
        return passes;
    }

    /** The mass gap in percent of what came in. */
    public double massGap() {
        return run.massIn() == 0 ? 0 : 100 * Math.abs(run.massGap()) / run.massIn();
    }

    /** The heat gap in percent of the duties. */
    public double heatGap() {
        return run.duties() == 0 ? 0 : 100 * Math.abs(run.heatGap()) / run.duties();
    }
}
