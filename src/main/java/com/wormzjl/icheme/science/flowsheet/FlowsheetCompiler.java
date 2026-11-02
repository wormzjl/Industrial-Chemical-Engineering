package com.wormzjl.icheme.science.flowsheet;

import com.wormzjl.icheme.science.fluid.state.ParticleSize;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;

/**
 * A sheet's facts to a run's calculations (batch 2026-11-02-process-simulator, plan sections 3.1 and 3.9). What cannot run stays
 * in the graph as a calculation that says why, so everything upstream solves, what lies after it stays grey, and the status
 * names the first thing to fix.
 */
public final class FlowsheetCompiler {
    private FlowsheetCompiler() {
    }

    public static List<Calculation> compile(Flowsheet sheet, FlowThermo thermo) {
        var calculations = new ArrayList<Calculation>();
        var wires = new HashMap<String, Map<String, String>>();
        var outlets = new HashMap<String, List<String>>();
        var live = livePorts(sheet);
        for (var stream : sheet.streams()) {
            if (claims(stream, stream.from(), Port.Direction.OUT, live, wires)) outlets.computeIfAbsent(stream.from().unit(), k -> new ArrayList<>()).add(stream.id());
            else calculations.add(source(stream, thermo));
            claims(stream, stream.to(), Port.Direction.IN, live, wires);
        }
        for (var unit : sheet.units()) calculations.addAll(unit(unit, wires.getOrDefault(unit.id(), Map.of()), outlets.getOrDefault(unit.id(), List.of()), thermo));
        return calculations;
    }

    /**
     * What a loop's tears start from. A run that stood steady left the best start there is. Else a line's own spec is its first
     * estimate, which a loop that cannot start from no flow needs: a column under a furnace on a fixed duty, with the heat of its
     * own bottoms still to come.
     */
    public static Map<String, FlowStream> estimates(Flowsheet sheet, FlowThermo thermo, @Nullable FlowsheetRun last) {
        var estimates = new HashMap<String, FlowStream>();
        for (var stream : sheet.streams()) {
            if (stream.feed() != null && stream.from() != null) {
                try {
                    estimates.put(stream.id(), thermo.atTemperature(stream.feed().temperature(), stream.feed().pressure(), amounts(stream.feed(), thermo), solids(stream.feed(), thermo)));
                } catch (CancellationException stop) {
                    throw stop;
                } catch (RuntimeException noState) {
                    // An estimate with no state is no estimate: the tear starts from no flow.
                }
            }
            if (last == null || last.status() != FlowsheetRun.Status.STEADY) continue;
            var solved = last.streams().get(stream.id());
            if (solved != null && solved.amountsView().length == thermo.axis().size()) estimates.put(stream.id(), solved);
        }
        return estimates;
    }

    /** A port holds one stream: whether {@code end} is a live port that {@code stream} now holds. */
    private static boolean claims(Flowsheet.Stream stream, @Nullable Flowsheet.End end, Port.Direction direction, Set<Live> live, Map<String, Map<String, String>> wires) {
        return end != null && live.contains(new Live(end, direction)) && wires.computeIfAbsent(end.unit(), k -> new HashMap<>()).putIfAbsent(end.port(), stream.id()) == null;
    }

    private record Live(Flowsheet.End end, Port.Direction direction) {
    }

    /**
     * The ports a stream may stand on: those of its unit's settings, and for a unit that cannot run, a removed type or a setting
     * that gives no ports, whatever its streams name, as its placeholder keeps them. A stream on any other end stays, and that
     * end is open.
     */
    private static Set<Live> livePorts(Flowsheet sheet) {
        var live = new HashSet<Live>();
        var placeholders = new HashSet<String>();
        for (var unit : sheet.units()) {
            try {
                var model = UnitTypes.model(unit.type());
                if (model == null) placeholders.add(unit.id());
                else for (var port : model.ports(unit.params())) live.add(new Live(new Flowsheet.End(unit.id(), port.id()), port.direction()));
            } catch (FlowsheetFailure noPorts) {
                placeholders.add(unit.id());
            }
        }
        for (var stream : sheet.streams()) {
            if (stream.from() != null && placeholders.contains(stream.from().unit())) live.add(new Live(stream.from(), Port.Direction.OUT));
            if (stream.to() != null && placeholders.contains(stream.to().unit())) live.add(new Live(stream.to(), Port.Direction.IN));
        }
        return live;
    }

    private static Calculation source(Flowsheet.Stream stream, FlowThermo thermo) {
        var feed = stream.feed();
        return Calculation.of("feed " + stream.id(), List.of(), List.of(stream.id()), (board, report) -> {
            if (feed == null) throw new FlowsheetFailure("stream.open", stream.name());
            var flow = thermo.atTemperature(feed.temperature(), feed.pressure(), amounts(feed, thermo), solids(feed, thermo));
            board.stream(stream.id(), flow);
            report.enters(flow.mass(), flow.enthalpy());
        });
    }

    private static double[] amounts(Flowsheet.Feed feed, FlowThermo thermo) {
        var axis = thermo.axis();
        double[] amounts = new double[axis.size()];
        for (var entry : feed.amounts().entrySet()) {
            int at = axis.indexOf(entry.getKey());
            if (at >= 0) amounts[at] = entry.getValue();
        }
        return amounts;
    }

    private static SolidInventory solids(Flowsheet.Feed feed, FlowThermo thermo) {
        if (feed.solids().isEmpty()) return SolidInventory.EMPTY;
        var populations = new ArrayList<SolidInventory.Population>();
        for (var solid : feed.solids()) populations.add(new SolidInventory.Population(thermo.model().solids.require(solid.material()), ParticleSize.micrometres(solid.micrometres()), solid.massFlow()));
        return new SolidInventory(populations);
    }

    private static List<Calculation> unit(Flowsheet.Unit unit, Map<String, String> wires, List<String> outlets, FlowThermo thermo) {
        var model = UnitTypes.model(unit.type());
        if (model == null) return List.of(refused(unit, outlets, "unit.removed", unit.type()));
        var wired = new WiredUnit(unit.id(), unit.params(), Map.copyOf(wires), thermo);
        try {
            for (var port : model.ports(unit.params())) if (port.required() && wired.wire(port.id()) == null) throw new FlowsheetFailure("unit.free_inlet", port.id());
            return model.calculations(wired);
        } catch (FlowsheetFailure refusal) {
            return List.of(refused(unit, outlets, refusal.key(), refusal.arguments().toArray(String[]::new)));
        }
    }

    /** A unit that cannot run: it fails when the run reaches it, so what it would have written stays grey. */
    private static Calculation refused(Flowsheet.Unit unit, List<String> outlets, String key, String... arguments) {
        return new Calculation(unit.id(), unit.id(), List.of(), outlets, List.of(), List.of(), Calculation.Kind.STEP, (board, report) -> {
            throw new FlowsheetFailure(key, arguments);
        });
    }
}
