package com.wormzjl.icheme.science.machine;

import java.util.*;

/**
 * Shaft trains from their parts (batch 2026-11-03-rotating-machinery, W3, plan section 5.1; build plan section 1):
 * Minecraft-free, deterministic. A part is a shaft block (a cell and an axis), or a machine with shaft ends (a cell, the
 * way the shaft leaves it, and whether power leaves or enters there). A shaft block joins the shaft blocks beside it on
 * its axis; a shaft end joins the shaft block in the cell beyond its face, on the same axis (reading A16: machines never
 * couple face to face). The parts so joined make one <em>line</em>; a gearbox's two ends lie on two lines, which it joins
 * at its ratio and {@link #GEAR_EFFICIENCY}. The lines a gearbox joins make one <em>train</em>, with one driver (reading
 * A17: two drivers or a loop of gears stand the train).
 */
public final class ShaftNetwork {
    /** A gearbox's efficiency (plan 1.3). */
    public static final double GEAR_EFFICIENCY = .98;
    /** Initial output/input speed ratio of a newly built gearbox; an explicitly selected zero still means Auto. */
    public static final double DEFAULT_GEARBOX_RATIO = 4;
    /** The largest ratio a gearbox takes either way (plan 5.3). */
    public static final double MAXIMUM_RATIO = 20;
    /** The largest ratio of a gearbox whose slow line a Create coupling drives (plan 5.3, W6b): 256 rpm reaches 16,384 rpm. */
    public static final double CREATE_RATIO = 64;

    private ShaftNetwork() {
    }

    /** What a part is in its train. */
    public enum Kind {
        SHAFT, MOTOR, GENERATOR, GEARBOX, STEAM_TURBINE, CASING, CREATIVE_MOTOR, CREATE_COUPLING;

        /** Whether it drives its train. */
        public boolean driver() {
            return this == MOTOR || this == STEAM_TURBINE || this == CREATIVE_MOTOR || this == CREATE_COUPLING;
        }

        /** Whether its train drives it. */
        public boolean driven() {
            return this == CASING || this == GENERATOR;
        }
    }

    /** A world cell. */
    public record Cell(String dimension, int x, int y, int z) {
        public Cell {
            Objects.requireNonNull(dimension);
        }

        Cell offset(int dx, int dy, int dz) {
            return new Cell(dimension, x + dx, y + dy, z + dz);
        }
    }

    /**
     * A shaft end: the machine's cell inside its coupling face, the face's outward direction (one unit step on one axis) and
     * whether power leaves the machine there ({@code output}: a driver's, or a gearbox's fast end).
     */
    public record End(Cell cell, int dx, int dy, int dz, boolean output) {
        public End {
            Objects.requireNonNull(cell);
            if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) != 1) throw new IllegalArgumentException("A shaft end leaves along one axis");
        }

        /** The cell beyond the face, where its shaft block stands. */
        public Cell block() {
            return cell.offset(dx, dy, dz);
        }

        /** Its axis: 0 x, 1 y, 2 z. */
        public int axis() {
            return dx != 0 ? 0 : dy != 0 ? 1 : 2;
        }
    }

    /**
     * One part: its identity, kind, and for a shaft block its cell and axis; for a machine its ends. {@code ratio} is a
     * gearbox's fast-end speed over its slow end's (0: auto, reading A20); {@code designSpeed} a member's rated speed, rpm.
     */
    public record Part(long id, Kind kind, Cell cell, int axis, List<End> ends, double ratio, double designSpeed) {
        public Part {
            Objects.requireNonNull(kind);
            ends = List.copyOf(ends);
            if (kind == Kind.SHAFT ? cell == null || axis < 0 || axis > 2 || !ends.isEmpty() : ends.isEmpty())
                throw new IllegalArgumentException("A shaft block has a cell and an axis, a machine its shaft ends");
            if (kind == Kind.GEARBOX && (ends.size() != 2 || ends.get(0).output() == ends.get(1).output()))
                throw new IllegalArgumentException("A gearbox has a slow and a fast end");
            if (!(ratio >= 0) || !Double.isFinite(ratio) || !(designSpeed >= 0) || !Double.isFinite(designSpeed))
                throw new IllegalArgumentException("Invalid ratio or speed");
        }

        public static Part shaft(long id, Cell cell, int axis) {
            return new Part(id, Kind.SHAFT, cell, axis, List.of(), 0, 0);
        }
    }

    /** Why a train stands without its driver's logic. */
    public enum Fault {
        NONE, NO_DRIVER, TWO_DRIVERS, GEAR_LOOP
    }

    /** A driven member: its speed is the driver's times {@code ratio}, and it takes power through {@code efficiency}. */
    public record Driven(long id, double ratio, double efficiency) {
    }

    /**
     * One train: its key (its lowest part identity), every part in it, its driver (0 for none), the driven members, its
     * fault, each shaft block's speed share of the driver's ({@code shafts}, for the drawn size) and each gearbox's ratio as
     * it applies (auto resolved).
     */
    public record Train(long key, Set<Long> parts, long driver, List<Driven> driven, Fault fault, Map<Long, Double> shafts, Map<Long, Double> ratios) {
        public Train {
            parts = Collections.unmodifiableSet(new TreeSet<>(parts));
            driven = List.copyOf(driven);
            shafts = Map.copyOf(shafts);
            ratios = Map.copyOf(ratios);
        }
    }

    /** Compiles {@code parts} into trains, in key order. Parts that touch no train end (a lone shaft run) make none. */
    public static List<Train> compile(Collection<Part> parts) {
        var byId = new TreeMap<Long, Part>();
        for (var p : parts) if (byId.put(p.id(), p) != null) throw new IllegalArgumentException("Duplicate shaft part " + p.id());
        // Union-find nodes: one per shaft block, one per machine end.
        var nodes = new ArrayList<long[]>(); // {part, end index or -1}
        var shaftAt = new HashMap<Cell, Integer>();
        var endNode = new HashMap<Long, int[]>();
        for (var p : byId.values()) {
            if (p.kind() == Kind.SHAFT) {
                if (shaftAt.put(p.cell(), nodes.size()) != null) throw new IllegalArgumentException("Two shaft blocks at " + p.cell());
                nodes.add(new long[]{p.id(), -1});
            } else {
                var at = new int[p.ends().size()];
                for (int e = 0; e < at.length; e++) {
                    at[e] = nodes.size();
                    nodes.add(new long[]{p.id(), e});
                }
                endNode.put(p.id(), at);
            }
        }
        int[] parent = new int[nodes.size()];
        for (int i = 0; i < parent.length; i++) parent[i] = i;
        for (var p : byId.values()) {
            if (p.kind() == Kind.SHAFT) {
                int self = shaftAt.get(p.cell());
                for (int sign = -1; sign <= 1; sign += 2) {
                    var next = p.cell().offset(p.axis() == 0 ? sign : 0, p.axis() == 1 ? sign : 0, p.axis() == 2 ? sign : 0);
                    var other = shaftAt.get(next);
                    if (other != null && byId.get(nodes.get(other)[0]).axis() == p.axis()) union(parent, self, other);
                }
            } else {
                for (int e = 0; e < p.ends().size(); e++) {
                    var end = p.ends().get(e);
                    var shaft = shaftAt.get(end.block());
                    if (shaft != null && byId.get(nodes.get(shaft)[0]).axis() == end.axis()) union(parent, endNode.get(p.id())[e], shaft);
                }
            }
        }
        // Lines: the components, by their lowest node.
        var lineOf = new int[nodes.size()];
        var lines = new TreeMap<Integer, List<Integer>>();
        for (int i = 0; i < nodes.size(); i++) {
            lineOf[i] = find(parent, i);
            lines.computeIfAbsent(lineOf[i], k -> new ArrayList<>()).add(i);
        }
        // Trains: lines joined through gearboxes.
        var trainParent = new HashMap<Integer, Integer>();
        for (int line : lines.keySet()) trainParent.put(line, line);
        var loops = new HashSet<Integer>();
        var gears = new ArrayList<Part>();
        for (var p : byId.values()) {
            if (p.kind() != Kind.GEARBOX) continue;
            gears.add(p);
            int a = lineOf[endNode.get(p.id())[0]], b = lineOf[endNode.get(p.id())[1]];
            int ra = findLine(trainParent, a), rb = findLine(trainParent, b);
            if (ra == rb) loops.add(ra);
            else {
                int low = Math.min(ra, rb), high = Math.max(ra, rb);
                trainParent.put(high, low);
                if (loops.remove(high)) loops.add(low);
            }
        }
        var trainLines = new TreeMap<Integer, List<Integer>>();
        for (int line : lines.keySet()) trainLines.computeIfAbsent(findLine(trainParent, line), k -> new ArrayList<>()).add(line);
        var trains = new ArrayList<Train>();
        for (var entry : trainLines.entrySet()) {
            var partIds = new TreeSet<Long>();
            boolean hasEnd = false;
            for (int line : entry.getValue())
                for (int node : lines.get(line)) {
                    partIds.add(nodes.get(node)[0]);
                    if (nodes.get(node)[1] >= 0) hasEnd = true;
                }
            if (!hasEnd) continue;
            var drivers = partIds.stream().map(byId::get).filter(p -> p.kind().driver()).toList();
            var fault = loops.contains(entry.getKey()) ? Fault.GEAR_LOOP : drivers.isEmpty() ? Fault.NO_DRIVER : drivers.size() > 1 ? Fault.TWO_DRIVERS : Fault.NONE;
            var driven = new ArrayList<Driven>();
            var shafts = new HashMap<Long, Double>();
            var ratios = new HashMap<Long, Double>();
            long driver = drivers.size() == 1 ? drivers.getFirst().id() : 0;
            if (fault == Fault.NONE) {
                var driverPart = drivers.getFirst();
                int start = lineOf[endNode.get(driverPart.id())[0]];
                var factor = new HashMap<Integer, Double>();
                var efficiency = new HashMap<Integer, Double>();
                factor.put(start, 1.0);
                efficiency.put(start, 1.0);
                var queue = new ArrayDeque<Integer>();
                queue.add(start);
                while (!queue.isEmpty()) {
                    int line = queue.poll();
                    for (var gear : gears) {
                        int[] ends = endNode.get(gear.id());
                        int slow = gear.ends().get(0).output() ? 1 : 0, fast = 1 - slow;
                        int slowLine = lineOf[ends[slow]], fastLine = lineOf[ends[fast]];
                        int other;
                        boolean upward;
                        if (slowLine == line && !factor.containsKey(fastLine)) {
                            other = fastLine;
                            upward = true;
                        } else if (fastLine == line && !factor.containsKey(slowLine)) {
                            other = slowLine;
                            upward = false;
                        } else continue;
                        double ratio = gear.ratio();
                        if (ratio == 0) {
                            // Auto (reading A20): the ratio that puts the first driven member beyond it at its design speed
                            // with the driver at its own.
                            double target = firstDrivenSpeed(lines.get(other), nodes, byId);
                            double here = factor.get(line) * driverPart.designSpeed();
                            ratio = target > 0 && here > 0 ? (upward ? target / here : here / target) : 1;
                        }
                        // Behind a Create coupling, on the line it drives, a gearbox steps up to 64 (plan 5.3, W6b; reading A145).
                        double most = driverPart.kind() == Kind.CREATE_COUPLING && upward && line == start ? CREATE_RATIO : MAXIMUM_RATIO;
                        ratio = Math.max(1 / most, Math.min(most, ratio));
                        ratios.put(gear.id(), ratio);
                        factor.put(other, factor.get(line) * (upward ? ratio : 1 / ratio));
                        efficiency.put(other, efficiency.get(line) * GEAR_EFFICIENCY);
                        queue.add(other);
                    }
                }
                for (int line : entry.getValue()) {
                    double f = factor.getOrDefault(line, 0.0), eta = efficiency.getOrDefault(line, 1.0);
                    var seen = new HashSet<Long>();
                    for (int node : lines.get(line)) {
                        var part = byId.get(nodes.get(node)[0]);
                        if (part.kind() == Kind.SHAFT) shafts.put(part.id(), f);
                        else if (part.kind().driven() && seen.add(part.id())) driven.add(new Driven(part.id(), f, eta));
                    }
                }
                driven.sort(Comparator.comparingLong(Driven::id));
            } else for (long id : partIds) if (byId.get(id).kind() == Kind.SHAFT) shafts.put(id, 0.0);
            trains.add(new Train(partIds.first(), partIds, driver, driven, fault, shafts, ratios));
        }
        trains.sort(Comparator.comparingLong(Train::key));
        return trains;
    }

    /** The design speed of the lowest-numbered driven member on a line, 0 for none. */
    private static double firstDrivenSpeed(List<Integer> line, List<long[]> nodes, Map<Long, Part> byId) {
        Part first = null;
        for (int node : line) {
            var p = byId.get(nodes.get(node)[0]);
            if (p.kind().driven() && (first == null || p.id() < first.id())) first = p;
        }
        return first == null ? 0 : first.designSpeed();
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) parent[Math.max(ra, rb)] = Math.min(ra, rb);
    }

    private static int findLine(Map<Integer, Integer> parent, int line) {
        int root = line;
        while (parent.get(root) != root) root = parent.get(root);
        return root;
    }
}
