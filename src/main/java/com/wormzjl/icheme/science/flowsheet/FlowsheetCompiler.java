package com.wormzjl.icheme.science.flowsheet;

import com.wormzjl.icheme.science.flowsheet.sizing.SizedPassages;
import com.wormzjl.icheme.science.flowsheet.sizing.SizingFacts;
import com.wormzjl.icheme.science.fluid.state.ParticleSize;
import com.wormzjl.icheme.science.fluid.state.SolidInventory;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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

    /** @param drawn the sheet as drawn: its passages' ends read as the roles the models name ({@link Wiring#roles}) */
    public static List<Calculation> compile(Flowsheet drawn, FlowThermo thermo) {
        var sheet = Wiring.roles(drawn);
        var calculations = new ArrayList<Calculation>();
        var wires = new HashMap<String, Map<String, String>>();
        var outlets = new HashMap<String, List<String>>();
        var utilities = new HashMap<String, Map<String, Flowsheet.Feed>>();
        var utilityPorts = new HashSet<Live>();
        var live = livePorts(sheet, utilityPorts);
        for (var stream : sheet.streams()) {
            boolean out = claims(stream, stream.from(), Port.Direction.OUT, live, wires);
            boolean in = claims(stream, stream.to(), Port.Direction.IN, live, wires);
            if (out) outlets.computeIfAbsent(stream.from().unit(), k -> new ArrayList<>()).add(stream.id());
            // A feed of water alone on a utility port is the unit's to set (COOLER_MODEL_PLAN.md section 8): its side writes it.
            else if (in && stream.from() == null && stream.feed() != null && utilityPorts.contains(new Live(stream.to(), Port.Direction.IN)) && waterAlone(stream.feed(), thermo))
                utilities.computeIfAbsent(stream.to().unit(), k -> new HashMap<>()).put(stream.to().port(), stream.feed());
            else calculations.add(source(stream, thermo));
        }
        double fall = sheet.sizing().basis().paFall();
        for (var unit : sheet.units())
            calculations.addAll(unit(unit, sheet.sizing(), wires.getOrDefault(unit.id(), Map.of()), outlets.getOrDefault(unit.id(), List.of()), new WiredUnit.Utilities(utilities.getOrDefault(unit.id(), Map.of()), fall), thermo));
        return calculations;
    }

    /** Whether {@code feed} is water and nothing else: cooling water as a liquid, steam as a vapour. */
    private static boolean waterAlone(Flowsheet.Feed feed, FlowThermo thermo) {
        int water = thermo.axis().indexOf(com.wormzjl.icheme.science.column.operation.SplitTable.WATER);
        if (water < 0) return false;
        for (var solid : feed.solids()) if (solid.massFlow() > 0) return false;
        double[] amounts = amounts(feed, thermo);
        for (int i = 0; i < amounts.length; i++) if (i != water && amounts[i] != 0) return false;
        return amounts[water] > 0 && parked(feed, thermo).isEmpty();
    }

    /** A feed as it was typed: the first guess of a side that sets its flow. */
    public static FlowStream typed(Flowsheet.Feed feed, FlowThermo thermo) {
        return thermo.atTemperature(feed.temperature(), feed.pressure(), amounts(feed, thermo), solids(feed, thermo));
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

    /** Each component and solid the sheet holds that this package no longer has, once: they stay in the file, out of the solve. */
    public static List<String> parked(Flowsheet sheet, FlowThermo thermo) {
        var parked = new LinkedHashSet<String>();
        for (var stream : sheet.streams()) if (stream.feed() != null) parked.addAll(parked(stream.feed(), thermo));
        return List.copyOf(parked);
    }

    private static List<String> parked(Flowsheet.Feed feed, FlowThermo thermo) {
        var parked = new ArrayList<String>();
        feed.amounts().forEach((component, amount) -> {
            if (amount > 0 && thermo.place(component) < 0) parked.add(component);
        });
        for (var solid : feed.solids()) if (solid.massFlow() > 0 && !thermo.model().solids.materials().containsKey(solid.material())) parked.add(solid.material());
        return parked;
    }

    /** A port holds one stream: whether {@code end} is a live port that {@code stream} now holds. */
    private static boolean claims(Flowsheet.Stream stream, @Nullable Flowsheet.End end, Port.Direction direction, Set<Live> live, Map<String, Map<String, String>> wires) {
        return end != null && live.contains(new Live(end, direction)) && wires.computeIfAbsent(end.unit(), k -> new HashMap<>()).putIfAbsent(end.port(), stream.id()) == null;
    }

    /** A port by its stable strings: what else a stream's end holds does not make it another port. */
    private record Live(String unit, String port, Port.Direction direction) {
        Live(Flowsheet.End end, Port.Direction direction) {
            this(end.unit(), end.port(), direction);
        }
    }

    /**
     * The ports a stream may stand on: those of its unit's settings, and for a unit that cannot run, a removed type or a setting
     * that gives no ports, whatever its streams name, as its placeholder keeps them. A stream on any other end stays, and that
     * end is open.
     */
    private static Set<Live> livePorts(Flowsheet sheet, Set<Live> utilities) {
        var live = new HashSet<Live>();
        var placeholders = new HashSet<String>();
        for (var unit : sheet.units()) {
            try {
                var model = UnitTypes.model(unit.type());
                if (model == null) placeholders.add(unit.id());
                else for (var port : model.ports(unit.params())) {
                    live.add(new Live(unit.id(), port.id(), port.direction()));
                    if (port.utility()) utilities.add(new Live(unit.id(), port.id(), port.direction()));
                }
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
            for (var id : parked(feed, thermo)) report.note(Note.warning(stream.id(), "basis.parked", id));
        });
    }

    /** The feed on the axis. An id and its alias name one component, so they add; a component with no place is parked. */
    private static double[] amounts(Flowsheet.Feed feed, FlowThermo thermo) {
        double[] amounts = new double[thermo.axis().size()];
        for (var entry : feed.amounts().entrySet()) {
            int at = thermo.place(entry.getKey());
            if (at >= 0) amounts[at] += entry.getValue();
        }
        return amounts;
    }

    private static SolidInventory solids(Flowsheet.Feed feed, FlowThermo thermo) {
        var populations = new ArrayList<SolidInventory.Population>();
        for (var solid : feed.solids()) {
            var material = thermo.model().solids.materials().get(solid.material());
            if (material != null) populations.add(new SolidInventory.Population(material, ParticleSize.micrometres(solid.micrometres()), solid.massFlow()));
        }
        return populations.isEmpty() ? SolidInventory.EMPTY : new SolidInventory(populations);
    }

    /** A unit with a size runs its passages' laws at the run's flow; one with none, its door's drops (sizing plan section 3.11, D19). */
    private static List<Calculation> unit(Flowsheet.Unit unit, SizingFacts sizing, Map<String, String> wires, List<String> outlets, WiredUnit.Utilities utilities, FlowThermo thermo) {
        var model = UnitTypes.model(unit.type());
        if (model == null) return List.of(refused(unit, outlets, "unit.removed", unit.type()));
        try {
            var wired = new WiredUnit(unit.id(), unit.params(), Map.copyOf(wires), thermo, SizedPassages.of(sizing, unit, thermo), utilities);
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
