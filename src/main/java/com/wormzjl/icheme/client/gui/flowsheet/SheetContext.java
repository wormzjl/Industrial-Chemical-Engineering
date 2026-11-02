package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.HeatControlColumn;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jetbrains.annotations.Nullable;

/** What every door and page reads (batch 2026-11-02-process-simulator): the sheet through its editor, the run's state, the display units, the texts. */
public record SheetContext(SheetEditor editor, SheetState state, SheetUnits units, HeatControlColumn.Texts t, Measure m, StreamProps.Fluids fluids, SimulatorHost host, List<String> differing) {
    public static final String KEY = "flowsheet.";

    public SheetContext(SheetEditor editor, SheetState state, SheetUnits units, HeatControlColumn.Texts t, Measure m, StreamProps.Fluids fluids) {
        this(editor, state, units, t, m, fluids, SimulatorHost.NONE, List.of());
    }

    public String text(String key, Object... arguments) {
        return t.get(KEY + key, arguments);
    }

    /** The stream that reaches a unit's port, or null. */
    public Flowsheet.@Nullable Stream into(String unit, String port) {
        for (var stream : editor.sheet().streams()) if (stream.to() != null && stream.to().unit().equals(unit) && stream.to().port().equals(port)) return stream;
        return null;
    }

    /** The stream that leaves a unit's port, or null. */
    public Flowsheet.@Nullable Stream outOf(String unit, String port) {
        for (var stream : editor.sheet().streams()) if (stream.from() != null && stream.from().unit().equals(unit) && stream.from().port().equals(port)) return stream;
        return null;
    }

    /** Every stream on a unit: {@code in} true those that reach it. */
    public List<Flowsheet.Stream> streams(String unit, boolean in) {
        var streams = new ArrayList<Flowsheet.Stream>();
        for (var stream : editor.sheet().streams()) {
            var end = in ? stream.to() : stream.from();
            if (end != null && end.unit().equals(unit)) streams.add(stream);
        }
        return streams;
    }

    /** A stream's last solved state, or null if greyed or unsolved. */
    public @Nullable FlowStream solved(Flowsheet.@Nullable Stream stream) {
        if (stream == null) return null;
        var results = state.shown(stream.id());
        return results == null ? null : results.stream(stream.id());
    }

    /** A stream's last solved state, greyed or not: what a unit that solves alone has now, or null if no run has solved it. */
    public @Nullable FlowStream last(Flowsheet.@Nullable Stream stream) {
        var results = state.results();
        return stream == null || results == null ? null : results.stream(stream.id());
    }

    public @Nullable StreamProps props(Flowsheet.@Nullable Stream stream) {
        var solved = solved(stream);
        return solved == null ? null : state.props(stream.id(), () -> StreamProps.of(solved, fluids));
    }

    /** A number as typed: a comma reads as a point; null if it is no number. */
    public static @Nullable Double number(String text) {
        try {
            double value = Double.parseDouble(text.strip().replace(',', '.'));
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException noNumber) {
            return null;
        }
    }

    /** The sentence-case text of an enum-like id. */
    public static String title(String id) {
        return id.isEmpty() ? id : id.substring(0, 1).toUpperCase(Locale.ROOT) + id.substring(1);
    }
}
