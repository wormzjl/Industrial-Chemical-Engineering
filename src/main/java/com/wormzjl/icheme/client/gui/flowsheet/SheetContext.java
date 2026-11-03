package com.wormzjl.icheme.client.gui.flowsheet;

import com.wormzjl.icheme.client.gui.common.HeatControlColumn;
import com.wormzjl.icheme.client.gui.common.kit.Measure;
import com.wormzjl.icheme.science.flowsheet.FlowStream;
import com.wormzjl.icheme.science.flowsheet.Flowsheet;
import com.wormzjl.icheme.science.flowsheet.Port;
import com.wormzjl.icheme.science.flowsheet.UnitTypes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    /**
     * The stream that reaches a unit's port, or null. Here and in {@link #outOf} and {@link #streams}, a port is a role the model names
     * ({@code in}, {@code tube_out}), and a stream's ends name the roles they play ({@link SheetEditor#roles}): a passage's inlet is
     * whichever end its stream enters by.
     */
    public Flowsheet.@Nullable Stream into(String unit, String port) {
        for (var stream : editor.roles().streams()) if (stream.to() != null && stream.to().unit().equals(unit) && stream.to().port().equals(port)) return stream;
        return null;
    }

    /** The stream that leaves a unit's port, or null. */
    public Flowsheet.@Nullable Stream outOf(String unit, String port) {
        for (var stream : editor.roles().streams()) if (stream.from() != null && stream.from().unit().equals(unit) && stream.from().port().equals(port)) return stream;
        return null;
    }

    /** The role a stream's end plays as its {@code to} ({@code inlet}) or its {@code from}: its own port where it plays none. */
    public String role(Flowsheet.End end, boolean inlet) {
        var role = editor.wiring(null).role(end, inlet);
        return role != null ? role : end.port();
    }

    /** Every stream on a unit: {@code in} true those that reach it. */
    public List<Flowsheet.Stream> streams(String unit, boolean in) {
        var streams = new ArrayList<Flowsheet.Stream>();
        for (var stream : editor.roles().streams()) {
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

    /** What {@code unit}'s open outlets carry, by port: a free outlet has no stream on the sheet, and the run keeps it under the unit's own name for the port. */
    public Map<String, StreamProps> openOutlets(Flowsheet.Unit unit) {
        var open = new LinkedHashMap<String, StreamProps>();
        var model = UnitTypes.model(unit.type());
        var results = state.shown(unit.id());
        if (model == null || results == null) return open;
        var taken = new HashSet<String>();
        for (var stream : streams(unit.id(), false)) taken.add(stream.from().port());
        for (var port : model.ports(unit.params())) {
            if (port.direction() != Port.Direction.OUT || taken.contains(port.id())) continue;
            var solved = results.stream(unit.id() + ":" + port.id());
            if (solved != null && !solved.empty()) open.put(port.id(), StreamProps.of(solved, fluids));
        }
        return open;
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
