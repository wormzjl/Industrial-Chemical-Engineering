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
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/** What every door and page reads (batch 2026-11-02-process-simulator): the sheet through its editor, the run's state, the display units, the texts. */
public record SheetContext(SheetEditor editor, SheetState state, SheetUnits units, HeatControlColumn.Texts t, Measure m, StreamProps.Fluids fluids, SimulatorHost host, List<String> differing) {
    public static final String KEY = "flowsheet.";

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

    /**
     * A stream's T and P on the canvas (the P, T key), in the display units: its run's, or a feed's set values while no run
     * holds it; null for a stream with neither.
     */
    public @Nullable List<String> conditions(Flowsheet.Stream stream) {
        var solved = solved(stream);
        var feed = stream.feed();
        if (solved == null && feed == null) return null;
        double kelvin = solved != null ? solved.temperature() : feed.temperature(), pascals = solved != null ? solved.pressure() : feed.pressure();
        return List.of(units.t(kelvin) + " " + units.temperature().symbol(), units.p(pascals) + " " + units.pressure().symbol());
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

    /** A feed's mol/s, all its parts together. */
    public static double total(Flowsheet.Feed feed) {
        double total = 0;
        for (double amount : feed.amounts().values()) total += amount;
        return total;
    }

    /** A feed's kg/s: each part by its molar mass, one the package lacks as water. */
    public double mass(Flowsheet.Feed feed) {
        double mass = 0;
        var axis = fluids.axis();
        for (var e : feed.amounts().entrySet()) mass += e.getValue() * fluids.weight(axis.indexOf(e.getKey()));
        return mass;
    }

    /**
     * A stream's density at its own temperature and pressure, kg/m³, for its flow in Volume; NaN where it is not known.
     * Density is intensive: a feed greyed only because its flow changed keeps the run's density, as long as its temperature,
     * pressure and mole fractions are the run's (a feed whose flow a unit set, U1, still does). Once one of those changed,
     * or with no run, nobody knows it until the sheet runs.
     */
    public double density(Flowsheet.Stream stream) {
        var shown = props(stream);
        if (shown != null) return shown.density();
        var feed = stream.feed();
        var run = last(stream);
        if (feed == null || run == null || !sameState(feed, run)) return Double.NaN;
        return state.props("density " + stream.id(), () -> StreamProps.of(run, fluids)).density();
    }

    /** Whether {@code feed} stands at the run's temperature, pressure and mole fractions, whatever its flow. */
    private boolean sameState(Flowsheet.Feed feed, FlowStream run) {
        if (!close(feed.temperature(), run.temperature()) || !close(feed.pressure(), run.pressure())) return false;
        double[] ran = run.amounts(), typed = new double[ran.length];
        var axis = fluids.axis();
        for (var e : feed.amounts().entrySet()) {
            int i = axis.indexOf(e.getKey());
            if (i >= 0 && i < typed.length) typed[i] += e.getValue();
        }
        double a = 0, b = 0;
        for (int i = 0; i < ran.length; i++) {
            a += typed[i];
            b += ran[i];
        }
        if (!(a > 0) || !(b > 0)) return false;
        for (int i = 0; i < ran.length; i++) if (Math.abs(typed[i] / a - ran[i] / b) > 1e-9) return false;
        return true;
    }

    private static boolean close(double a, double b) {
        return Math.abs(a - b) <= 1e-9 * Math.max(Math.abs(a), Math.abs(b));
    }

    /** A feed's flow on the display key's basis ({@link SheetUnits#flow}); empty in Volume while its density is not known. */
    public String feedFlow(Flowsheet.Stream stream) {
        var feed = stream.feed();
        return units.flow(total(feed), mass(feed), density(stream));
    }

    /** Whether the feed's flow reads and takes a typed flow on the display key's basis: all but Volume with no density. */
    public boolean feedFlowKnown(Flowsheet.Stream stream) {
        return units.basis() != SheetUnits.Basis.VOLUME || density(stream) > 0;
    }

    /** The mol/s a flow typed on the display key's basis means for this feed ({@link SheetUnits#molesOf}); NaN where none: no flow to scale, or Volume with no density. */
    public double feedMoles(Flowsheet.Stream stream, double typed) {
        var feed = stream.feed();
        double total = total(feed);
        return total > 0 ? units.molesOf(typed, mass(feed) / total, density(stream)) : Double.NaN;
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
}
