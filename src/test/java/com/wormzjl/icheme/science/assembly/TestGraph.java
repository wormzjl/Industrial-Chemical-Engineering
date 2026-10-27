package com.wormzjl.icheme.science.assembly;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;

import java.util.*;
import java.util.function.LongFunction;

/**
 * A port graph built by hand for the matcher's tests: units, pipes between their ports (recorded both ways, fittings
 * reversed on the way back), lines to outside or to an open end, and tees.
 */
final class TestGraph implements PortGraph {
    private record Key(long origin, Role role, int ordinal) {
        static Key of(Port port) {
            return new Key(port.unit().origin(), port.role(), port.ordinal());
        }
    }

    /** A fitting on a pipe, its arrow along the pipe from its first end (+1) or against it (-1). */
    record F(Fitting kind, int direction) {
    }

    /** A tee's branch: its fittings from the tee, and where it ends (null: outside). */
    record Out(List<F> fittings, Port to) {
    }

    private final Map<Key, Run> runs = new HashMap<>();
    private final Map<Long, List<Run>> branches = new HashMap<>();
    private long nextOrigin = 1, nextPosition = 1_000_000;

    static F valve() {
        return new F(Fitting.CONTROL_VALVE, 1);
    }

    static F valveBack() {
        return new F(Fitting.CONTROL_VALVE, -1);
    }

    static F inlinePump() {
        return new F(Fitting.INLINE_PUMP, 1);
    }

    static F other() {
        return new F(Fitting.OTHER, 1);
    }

    static F meter() {
        return new F(Fitting.FLOWMETER, 1);
    }

    static Port port(Unit unit, Role role) {
        return new Port(unit, role, 0);
    }

    static Port port(Unit unit, Role role, int ordinal) {
        return new Port(unit, role, ordinal);
    }

    Unit unit(Kind kind, Role... nozzles) {
        return new Unit(nextOrigin++, kind, false, false, List.of(nozzles));
    }

    Unit drum(boolean lying, boolean boot, Role... nozzles) {
        return new Unit(nextOrigin++, Kind.DRUM, lying, boot, List.of(nozzles));
    }

    @Override
    public Run run(Port port) {
        return runs.getOrDefault(Key.of(port), Run.unconnected());
    }

    @Override
    public List<Run> branches(Junction junction, Run arrived) {
        return branches.getOrDefault(junction.position(), List.of());
    }

    /** A pipe from {@code a} to {@code b}, fittings in order from {@code a}, recorded both ways. */
    void pipe(Port a, Port b, F... fittings) {
        var run = line(fittings, new AtPort(b), 3 + 3 * fittings.length);
        runs.put(Key.of(a), run);
        runs.put(Key.of(b), reverse(run, new AtPort(a)));
    }

    /**
     * A line from {@code a} through {@code fittings}, then pipe blocks to its end, which {@code end} makes from the line's
     * last block; recorded from {@code a} only. Each fitting has a block before it and two after it, so the last fitting
     * has {@code pipesAfter + 2} blocks from it to the end. Returns the last block.
     */
    long end(Port a, LongFunction<End> end, int pipesAfter, F... fittings) {
        int pipes = 3 * fittings.length + pipesAfter;
        long last = nextPosition + pipes - 1;
        runs.put(Key.of(a), line(fittings, end.apply(last), pipes));
        return last;
    }

    /** A line from {@code a} through {@code fittings} to a tank outside. */
    void outside(Port a, F... fittings) {
        end(a, last -> new Terminal(last + 1_000_000_000L), 3, fittings);
    }

    /** A line from {@code a} through {@code fittings} to a tee, its branches beyond it. */
    long tee(Port a, List<F> fittings, Out... outs) {
        long at = nextPosition++;
        var arrived = line(fittings.toArray(F[]::new), new Junction(at, outs.length), 3 + 3 * fittings.size());
        runs.put(Key.of(a), arrived);
        var list = new ArrayList<Run>();
        for (var out : outs) {
            var f = out.fittings().toArray(F[]::new);
            if (out.to() == null) list.add(line(f, new Terminal(nextPosition++), 3 + 3 * f.length));
            else {
                var run = line(f, new AtPort(out.to()), 3 + 3 * f.length);
                list.add(run);
                runs.put(Key.of(out.to()), reverse(run, new Junction(at, outs.length)));
            }
        }
        branches.put(at, list);
        return at;
    }

    /**
     * A bypass around a unit's side, as walked from its inlet {@code in}: a line through {@code inlet} to an inlet tee,
     * whose branches are the feed (through {@code feed}, to a tank outside) and the bypass (through {@code bypass}) to an
     * outlet tee, whose branches are the line to {@code out} and the product (to a tank outside). {@code feedFirst}: the
     * inlet tee lists the feed before the bypass. Returns the inlet and the outlet tee's positions.
     */
    long[] bypass(Port in, Port out, List<F> inlet, List<F> feed, List<F> bypass, boolean feedFirst) {
        long inletTee = nextPosition++, outletTee = nextPosition++;
        runs.put(Key.of(in), line(inlet.toArray(F[]::new), new Junction(inletTee, 3), 3 + 3 * inlet.size()));
        var feedRun = line(feed.toArray(F[]::new), new Terminal(nextPosition++), 3 + 3 * feed.size());
        var bypassRun = line(bypass.toArray(F[]::new), new Junction(outletTee, 3), 3 + 3 * bypass.size());
        branches.put(inletTee, feedFirst ? List.of(feedRun, bypassRun) : List.of(bypassRun, feedRun));
        var toOutlet = line(new F[0], new AtPort(out), 3);
        runs.put(Key.of(out), reverse(toOutlet, new Junction(outletTee, 3)));
        branches.put(outletTee, List.of(line(new F[0], new Terminal(nextPosition++), 3), toOutlet));
        return new long[]{inletTee, outletTee};
    }

    /** No pipe at {@code port}. */
    void cut(Port port) {
        runs.remove(Key.of(port));
    }

    private Run line(F[] fittings, End end, int pipes) {
        var positions = new ArrayList<Long>();
        for (int i = 0; i < pipes; i++) positions.add(nextPosition++);
        var elements = new ArrayList<Element>();
        for (int i = 0; i < fittings.length; i++) elements.add(new Element(fittings[i].kind(), fittings[i].direction(), positions.get(1 + 3 * i), 1 + 3 * i));
        return new Run(elements, end, pipes, positions);
    }

    private static Run reverse(Run run, End end) {
        var elements = new ArrayList<Element>();
        for (var e : run.elements().reversed()) elements.add(new Element(e.kind(), -e.direction(), e.position(), run.pipes() - 1 - e.pipesBefore()));
        return new Run(elements, end, run.pipes(), run.positions().reversed());
    }
}
