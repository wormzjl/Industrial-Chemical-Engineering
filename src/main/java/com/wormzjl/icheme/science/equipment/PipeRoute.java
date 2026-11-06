package com.wormzjl.icheme.science.equipment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.Function;

/**
 * The cells a laid pipe takes between its two ends (batch 2026-10-26-pipe-laying-system, plan section 4.3). The client
 * searches the route with an A* and sends it; the server checks the cells it is sent ({@link #check}, {@link #blocked})
 * and never searches, so the two cannot disagree on the route.
 *
 * <p>Faces follow {@link LineGeometry#STEPS} (Minecraft's direction order): face {@code f}'s opposite is {@code f ^ 1}. A
 * route's heading in a cell is the face it travels towards there, so it entered through {@code heading ^ 1}. Each cell of a
 * route has a joint towards the cells before and after it; the first and last cells also have one towards what the line
 * starts or ends at, unless that end is open, where the line runs on straight (or, at the start, from the face behind
 * its heading).</p>
 *
 * <p>In a rack cell the line is a lane ({@link RackGeometry}): its option says where it lies, and its arms are the faces
 * it enters and leaves through. Steps along a rack are cheap, so routes follow racks as plant piping does. The search
 * carries every option still possible in a rack cell (a bit each); a step from one rack cell to the next keeps the
 * options that meet one of them on the shared face, so a line keeps its place along a run whatever the racks' facings.
 * The options are chosen once the route is found ({@link #choose}, RACK_LAYERS_PLAN I7): the positions nearest the side
 * the line leaves towards, then the fewest level changes.</p>
 */
public final class PipeRoute {
    /** A step through a free cell; a change of heading (an elbow); the extra for a vertical step. */
    public static final double STEP = 1, ELBOW = 1.5, VERTICAL = .3;
    /** A step into a rack cell along its run; the extra for passing between a lane and a plain cell. */
    public static final double RACK_STEP = .4, TRANSITION = .5;

    public record Cell(int x, int y, int z) {
        public Cell step(int face) {
            var s = LineGeometry.STEPS[face];
            return new Cell(x + s[0], y + s[1], z + s[2]);
        }

        public int distance(Cell other) {
            return Math.abs(x - other.x) + Math.abs(y - other.y) + Math.abs(z - other.z);
        }

        /** The face of this cell beyond which {@code next} lies, or -1 when it is not a neighbour. */
        public int faceTowards(Cell next) {
            for (int face = 0; face < 6; face++) if (step(face).equals(next)) return face;
            return -1;
        }
    }

    /** What a cell offers a route. */
    public enum Admit {FREE, RACK, BLOCKED}

    /** A cell as the route sees it: free, blocked, or a rack cell with its frame, its lanes, and the faces they have joints on (a bit per face). */
    public record Room(Admit admit, RackGeometry.Rack rack, List<RackGeometry.Lane> lanes, int faces) {
        public static final Room FREE = new Room(Admit.FREE, null, List.of(), 0), BLOCKED = new Room(Admit.BLOCKED, null, List.of(), 0);

        public Room {
            lanes = List.copyOf(lanes);
            if (admit == Admit.RACK && rack == null) throw new IllegalArgumentException("A rack room without its frame");
        }

        public static Room rack(RackGeometry.Rack rack, List<RackGeometry.Lane> lanes, int faces) {
            return new Room(Admit.RACK, rack, lanes, faces);
        }
    }

    /** The world as the route sees it. */
    public interface Space {
        Room room(Cell cell);

        /**
         * Every rack cell from {@code low} to {@code high}, inclusive. The search's estimate counts on knowing them all: a
         * rack it does not know of, it may pass by.
         */
        Collection<Cell> racks(Cell low, Cell high);

        static Space of(Function<Cell, Room> rooms, Collection<Cell> racks) {
            return new Space() {
                @Override
                public Room room(Cell cell) {
                    return rooms.apply(cell);
                }

                @Override
                public Collection<Cell> racks(Cell low, Cell high) {
                    return racks;
                }
            };
        }
    }

    /**
     * An end of a line: its cell, the face of that cell joined to what the line meets there (-1: an open end), and in a
     * rack cell the point its lane must meet on that face ({@link RackGeometry#point}; -1: any), as a lane continued from
     * the next rack does. A slot aimed on a rack ({@link RackGeometry#aim}) fixes an open end's lane: its {@code option},
     * the face it passes {@code through} to the rest of the route, and the face it ends {@code open} at; -1 each where
     * the route chooses.
     */
    public record End(Cell cell, int joint, int point, int option, int through, int open) {
        public End(Cell cell, int joint, int point) {
            this(cell, joint, point, -1, -1, -1);
        }

        public End(Cell cell, int joint) {
            this(cell, joint, -1);
        }

        public End {
            if (cell == null || joint < -1 || joint > 5 || point < -1 || point > 1023 || point >= 0 && joint < 0) throw new IllegalArgumentException("Invalid route end");
            boolean aimed = option >= 0;
            if (option < -1 || option >= RackGeometry.OPTIONS || through < -1 || through > 5 || open < -1 || open > 5 || aimed != through >= 0 || aimed != open >= 0
                    || aimed && (joint >= 0 || through == open)) throw new IllegalArgumentException("Invalid aimed route end");
        }

        /** The face the end cell's line runs on to past the route: its joint, else its aimed open face, else straight on from {@code entry}. */
        int exit(int entry) {
            return joint >= 0 ? joint : open >= 0 ? open : entry ^ 1;
        }
    }

    /** The search bounds: the cells of a route, the nodes expanded, and the blocks the search box extends past its ends. */
    public record Limits(int cells, int expansions, int margin) {
        public static final Limits DEFAULT = new Limits(128, 32768, 8);
    }

    /** Why a route cannot be laid; each has a player message. */
    public enum Refusal {START_OCCUPIED, END_OCCUPIED, SAME_FACE, BLOCKED, TOO_LONG, BROKEN, JOINTS, ONE_SLOT}

    /** A route's cells and each cell's lane option (-1 in a plain cell), or why there is none. */
    public record Result(List<Cell> cells, List<Integer> options, Refusal refusal) {
        public Result {
            cells = List.copyOf(cells);
            options = List.copyOf(options);
        }

        public boolean found() {
            return refusal == null;
        }

        static Result refused(Refusal refusal) {
            return new Result(List.of(), List.of(), refusal);
        }
    }

    /** The line being laid: its outside diameter (a standard size), its width in rack positions, and its size code. */
    private record Line(double outside, int width, int code) {
        static Line of(double outside) {
            int code = PipeSizes.code(outside);
            double od = PipeSizes.outsideOf(code);
            return new Line(od, RackGeometry.width(od), code);
        }

        boolean racked() {
            return width <= RackGeometry.MAX_WIDTH;
        }
    }

    private PipeRoute() {
    }

    /**
     * The cheapest route from {@code start} to {@code end} for a line of outside diameter {@code outside}, by the costs
     * above, entering the start cell along {@code heading} (the face the player clicked: it steers the first leg). An A*
     * search with {@link Estimate}, which never overestimates, so the route found is a cheapest one. Deterministic: ties
     * go to the lower f, then the lower h, then the earlier push; pushes follow the face order. A state in a rack cell
     * holds the lane options still possible there, so equal choices are one state, not one each. An aimed end
     * ({@link End#option}) keeps its option, and the route passes through its {@code through} face only. A route passes
     * through each cell once ({@link #holds}); where an aimed start leaves away from its end, it comes round outside the
     * start's cell, or there is no route.
     */
    public static Result search(End start, int heading, End end, double outside, Space space, Limits limits) {
        if (heading < 0 || heading > 5 || start.joint >= 0 && start.joint != (heading ^ 1) || start.open >= 0 && start.open != (heading ^ 1))
            throw new IllegalArgumentException("Heading does not leave the start's joint or open face");
        var line = Line.of(outside);
        var rooms = new HashMap<Cell, Room>();
        Function<Cell, Room> room = cell -> rooms.computeIfAbsent(cell, c -> admitted(space.room(c), line));
        var startRoom = room.apply(start.cell);
        int startOptions = entering(startRoom, heading ^ 1, start.point < 0 ? null : new int[]{start.point}, line) & aimed(start);
        if (startRoom.admit == Admit.BLOCKED || startRoom.admit == Admit.RACK && startOptions == 0) return Result.refused(Refusal.START_OCCUPIED);
        var endRoom = room.apply(end.cell);
        if (endRoom.admit == Admit.BLOCKED || endRoom.admit == Admit.RACK && (entering(endRoom, end.joint, end.point < 0 ? null : new int[]{end.point}, line) & aimed(end)) == 0)
            return Result.refused(Refusal.END_OCCUPIED);
        if (start.cell.equals(end.cell)) {
            if (start.joint >= 0 && start.joint == end.joint) return Result.refused(Refusal.SAME_FACE);
            // One cell is one lane, or one pipe block (ends): an aimed end's lane, which both aimed ends must name, with
            // each end's joint on one of its faces.
            if (start.option >= 0 && end.option >= 0 && start.option != end.option) return Result.refused(Refusal.ONE_SLOT);
            var cells = List.of(start.cell);
            int[] arms = ends(cells, start, heading, end)[0];
            if (arms[0] == arms[1]) return Result.refused(Refusal.SAME_FACE);
            if (!on(start.joint, arms) || !on(end.joint, arms)) return Result.refused(Refusal.BLOCKED);
            int options = finishing(startRoom, startOptions, arms[0], arms[1], end, line);
            if (startRoom.admit == Admit.RACK && options == 0) return Result.refused(Refusal.END_OCCUPIED);
            return new Result(cells, choose(cells, List.of(options), start, heading, end, line, room), null);
        }
        int lowX = Math.min(start.cell.x, end.cell.x) - limits.margin, highX = Math.max(start.cell.x, end.cell.x) + limits.margin;
        int lowY = Math.min(start.cell.y, end.cell.y) - limits.margin, highY = Math.max(start.cell.y, end.cell.y) + limits.margin;
        int lowZ = Math.min(start.cell.z, end.cell.z) - limits.margin, highZ = Math.max(start.cell.z, end.cell.z) + limits.margin;
        var estimate = Estimate.of(end.cell, space.racks(new Cell(lowX, lowY, lowZ), new Cell(highX, highY, highZ)), room, line);
        var open = new PriorityQueue<Node>();
        var best = new HashMap<State, Double>();
        long pushes = 0;
        open.add(new Node(start.cell, heading, startOptions, 0, estimate.at(start.cell, heading), 1, null, pushes++));
        best.put(new State(start.cell, heading, startOptions), 0.0);
        // An aimed end is entered from the cell beyond its through face only, and a route holds that cell once: from it the
        // route steps into the end, or it could never come back to.
        var approach = end.through >= 0 ? end.cell.step(end.through) : null;
        int expansions = 0;
        boolean capped = false;
        while (!open.isEmpty()) {
            var node = open.poll();
            if (node.g > best.getOrDefault(new State(node.cell, node.heading, node.options), Double.MAX_VALUE)) continue;
            if (node.cell.equals(end.cell)) {
                var cells = new ArrayList<Cell>();
                var options = new ArrayList<Integer>();
                for (var at = node; at != null; at = at.parent) {
                    cells.add(at.cell);
                    options.add(at.options);
                }
                Collections.reverse(cells);
                Collections.reverse(options);
                return new Result(cells, choose(cells, options, start, heading, end, line, room), null);
            }
            if (++expansions > limits.expansions) {
                capped = true;
                break;
            }
            var here = room.apply(node.cell);
            int entry = node.heading ^ 1;
            for (int face = 0; face < 6; face++) {
                if (face == entry || node.parent == null && start.through >= 0 && face != start.through) continue;
                var next = node.cell.step(face);
                if (next.x < lowX || next.x > highX || next.y < lowY || next.y > highY || next.z < lowZ || next.z > highZ) continue;
                if (holds(node, next) || node.cell.equals(approach) && !next.equals(end.cell)) continue;
                if (node.length + 1 > limits.cells) {
                    capped = true;
                    continue;
                }
                var there = room.apply(next);
                if (there.admit == Admit.BLOCKED) continue;
                boolean lane = here.admit == Admit.RACK, rack = there.admit == Admit.RACK;
                // A rack face towards a plain cell carries at most one lane (plan 5.2): no other lane of the rack may point there already.
                if (lane && !rack && (here.faces >> face & 1) != 0) continue;
                if (!lane && rack && (there.faces >> (face ^ 1) & 1) != 0) continue;
                // The lane here leaves through this face; the next rack takes the options that meet one left on the face.
                int leaving = lane ? leaving(here, node.options, entry, face, line) : 0;
                if (lane && leaving == 0) continue;
                boolean last = next.equals(end.cell);
                if (last && end.through >= 0 && (face ^ 1) != end.through) continue;
                int options = 0;
                if (rack) {
                    options = entering(there, face ^ 1, lane ? points(here, leaving, face, line) : null, line);
                    if (last) options = finishing(there, options, face ^ 1, end.exit(face ^ 1), end, line);
                    if (options == 0) continue;
                }
                double g = node.g + (rack && RackGeometry.along(there.rack, face ^ 1) ? RACK_STEP : STEP) + (face != node.heading ? ELBOW : 0) + (face < 2 ? VERTICAL : 0)
                        + (lane != rack ? TRANSITION : 0);
                if (last && end.joint >= 0) {
                    // The end cell joins what it meets through end.joint: entering through that face is impossible, any other but the far one is an elbow.
                    if (end.joint == (face ^ 1)) continue;
                    if (end.joint != face) g += ELBOW;
                }
                var state = new State(next, face, options);
                if (g >= best.getOrDefault(state, Double.MAX_VALUE)) continue;
                best.put(state, g);
                open.add(new Node(next, face, options, g, estimate.at(next, face), node.length + 1, node, pushes++));
            }
        }
        return Result.refused(capped ? Refusal.TOO_LONG : Refusal.BLOCKED);
    }

    /**
     * Whether the route to {@code node} holds {@code cell} already. A line passes through a cell once, as {@link #check}
     * requires: a rack cell's rooms do not hold the line's own lanes, so without this a line aimed out of its start away
     * from its end would come round through its start cell, even on the start's own slot.
     */
    private static boolean holds(Node node, Cell cell) {
        for (var at = node; at != null; at = at.parent) if (at.cell.equals(cell)) return true;
        return false;
    }

    /** A rack the line is too wide for is no way through (RD10). */
    private static Room admitted(Room room, Line line) {
        return room.admit == Admit.RACK && !line.racked() ? Room.BLOCKED : room;
    }

    /** The options an end allows, a bit each: its aimed option, or any. */
    private static int aimed(End end) {
        return end.option >= 0 ? 1 << end.option : ~0;
    }

    /**
     * The options a line may take in a rack cell it enters through {@code face}: those with an arm there, open beside
     * the cell's lanes, and meeting one of {@code points} on that face (null: any); 0 in a plain cell, or with no face (-1).
     */
    private static int entering(Room room, int face, int[] points, Line line) {
        if (room.admit != Admit.RACK) return 0;
        if (face < 0) return opened(room, line);
        int out = 0;
        for (int option = 0; option < RackGeometry.OPTIONS; option++) {
            int point = RackGeometry.point(room.rack, option, line.width, line.outside, face);
            if (point < 0 || points != null && !contains(points, point)) continue;
            if (RackGeometry.open(room.rack, room.lanes, option, line.width, line.outside)) out |= 1 << option;
        }
        return out;
    }

    /** The options open in a rack cell whatever face they use. */
    private static int opened(Room room, Line line) {
        int out = 0;
        for (int option = 0; option < RackGeometry.OPTIONS; option++)
            if (RackGeometry.open(room.rack, room.lanes, option, line.width, line.outside)) out |= 1 << option;
        return out;
    }

    private static boolean contains(int[] points, int point) {
        for (int p : points) if (p == point) return true;
        return false;
    }

    /** The options among {@code options} whose lane, entering through {@code entry} and leaving through {@code exit}, suits and fits the cell. */
    private static int leaving(Room room, int options, int entry, int exit, Line line) {
        int out = 0;
        for (int option = 0; option < RackGeometry.OPTIONS; option++)
            if ((options >> option & 1) != 0 && fits(room, option, 1 << entry | 1 << exit, line)) out |= 1 << option;
        return out;
    }

    /** Whether {@code joint} (-1: none) is one of the two faces {@code arms}. */
    private static boolean on(int joint, int[] arms) {
        return joint < 0 || joint == arms[0] || joint == arms[1];
    }

    private static boolean fits(Room room, int option, int arms, Line line) {
        return RackGeometry.valid(room.rack, option, line.width, arms) && RackGeometry.fits(room.rack, room.lanes, new RackGeometry.Lane(option, line.width, arms, 0, line.code));
    }

    /** The end cell's options: those of the end's aimed slot, or any, running from {@code entry} to {@code exit} and meeting the end's point on its joint. */
    private static int finishing(Room room, int options, int entry, int exit, End end, Line line) {
        if (room.admit != Admit.RACK || exit == entry) return 0;
        int out = leaving(room, options & aimed(end), entry, exit, line);
        if (end.point >= 0)
            for (int option = 0; option < RackGeometry.OPTIONS; option++)
                if ((out >> option & 1) != 0 && RackGeometry.point(room.rack, option, line.width, line.outside, end.joint) != end.point) out &= ~(1 << option);
        return out;
    }

    /** The points the options in {@code options} meet face {@code face} at. */
    private static int[] points(Room room, int options, int face, Line line) {
        var out = new int[Integer.bitCount(options)];
        int k = 0;
        for (int option = 0; option < RackGeometry.OPTIONS; option++)
            if ((options >> option & 1) != 0) out[k++] = RackGeometry.point(room.rack, option, line.width, line.outside, face);
        return out;
    }

    /**
     * The faces each cell of a route runs to: the faces towards the cells before and after it; at the start the start's
     * joint, or for an open start the face behind the heading; at the end the end's joint, its aimed open face, or for an
     * open end straight on. A line of one cell with an aimed end is that end's lane, the start's where both are aimed: its
     * two faces, whichever half each click aimed at, as a one-block line has no route to pass on to.
     */
    private static int[][] ends(List<Cell> cells, End start, int heading, End end) {
        int n = cells.size();
        var out = new int[n][2];
        for (int i = 0; i < n; i++) {
            out[i][0] = i == 0 ? (start.joint >= 0 ? start.joint : heading ^ 1) : cells.get(i).faceTowards(cells.get(i - 1));
            out[i][1] = i + 1 < n ? cells.get(i).faceTowards(cells.get(i + 1)) : end.exit(out[i][0]);
        }
        var aimed = start.option >= 0 ? start : end;
        if (n == 1 && aimed.option >= 0) out[0] = new int[]{aimed.through, aimed.open};
        return out;
    }

    /** The two faces each cell's pipe runs to, a bit each: a lane's arms. */
    public static int[] arms(List<Cell> cells, End start, int heading, End end) {
        var ends = ends(cells, start, heading, end);
        var out = new int[ends.length];
        for (int i = 0; i < ends.length; i++) out[i] = 1 << ends[i][0] | 1 << ends[i][1];
        return out;
    }

    /**
     * Each cell's option along a found route: -1 in a plain cell; in each rack portion (consecutive rack cells) the
     * options are chosen from the last cell back, each meeting the next on their shared face. At the last cell the
     * positions nearest the side the line leaves towards win (I7): the side its arm leaves through, else the first
     * sideways step after the portion, else the last one before it; then the fewest level changes, above before below.
     * Earlier cells keep the fewest level changes.
     */
    private static List<Integer> choose(List<Cell> cells, List<Integer> states, End start, int heading, End end, Line line, Function<Cell, Room> room) {
        int n = cells.size();
        var ends = ends(cells, start, heading, end);
        var out = new ArrayList<Integer>(Collections.nCopies(n, -1));
        for (int last = n - 1; last >= 0; last--) {
            var lastRoom = room.apply(cells.get(last));
            if (lastRoom.admit != Admit.RACK) continue;
            int first = last;
            while (first > 0 && room.apply(cells.get(first - 1)).admit == Admit.RACK) first--;
            int towards = towards(cells, ends, first, last, lastRoom.rack, start, heading, end);
            int chosen = -1;
            int options = leaving(lastRoom, states.get(last), ends[last][0], ends[last][1], line);
            for (int option = 0; option < RackGeometry.OPTIONS; option++)
                if ((options >> option & 1) != 0 && (chosen < 0 || rank(lastRoom.rack, option, line, towards) < rank(lastRoom.rack, chosen, line, towards))) chosen = option;
            out.set(last, chosen);
            for (int i = last - 1; i >= first; i--) {
                var here = room.apply(cells.get(i));
                var after = room.apply(cells.get(i + 1));
                int meet = RackGeometry.point(after.rack, out.get(i + 1), line.width, line.outside, ends[i + 1][0]);
                int candidates = leaving(here, states.get(i), ends[i][0], ends[i][1], line), pick = -1;
                for (int option = 0; option < RackGeometry.OPTIONS; option++)
                    if ((candidates >> option & 1) != 0 && RackGeometry.point(here.rack, option, line.width, line.outside, ends[i][1]) == meet
                            && (pick < 0 || RackGeometry.level(option) < RackGeometry.level(pick))) pick = option;
                out.set(i, pick);
            }
            last = first;
        }
        return out;
    }

    /**
     * An option's rank at a portion's last cell: its distance from the side {@code towards} (-1: none, the left), then its
     * level changes. In a tee, the run's lanes keep to the positions furthest from the branch and the turning lanes to
     * their own side, leaving room for the others.
     */
    private static int rank(RackGeometry.Rack rack, int option, Line line, int towards) {
        if (!RackGeometry.tier(option)) return RackGeometry.level(option);
        int p = RackGeometry.position(option);
        if (rack.tee()) return RackGeometry.level(option) == RackGeometry.TURN_RIGHT ? RackGeometry.POSITIONS - line.width - p : p;
        int distance = towards == rack.right() ? RackGeometry.POSITIONS - line.width - p : p;
        return 4 * distance + RackGeometry.level(option);
    }

    /**
     * The side a rack portion's line leaves towards, as a block face of its last cell ({@code rack}), or -1: the side its
     * last arm leaves through; else the first sideways step after the portion (the end's joint included); else the last
     * sideways step into it from before.
     */
    private static int towards(List<Cell> cells, int[][] ends, int first, int last, RackGeometry.Rack rack, End start, int heading, End end) {
        int left = rack.left(), right = rack.right();
        if (!rack.junction() && (ends[last][1] == left || ends[last][1] == right)) return ends[last][1];
        for (int i = last + 1; i < cells.size(); i++) if (ends[i][1] == left || ends[i][1] == right) return ends[i][1];
        for (int i = first; i >= 0; i--) {
            // Coming in from that side: the face back towards where it came from.
            int back = ends[i][0];
            if (back == left || back == right) return back;
        }
        return -1;
    }

    /**
     * The search's estimate of the cost left from a cell to the goal; it never overestimates. Each block of a route costs
     * at least {@link #STEP}, except a step into a rack cell along its run ({@link #RACK_STEP}). A route that uses no rack
     * therefore costs at least the Manhattan distance d; one that uses racks takes at least (r - 1) plain steps to reach
     * the first rack cell, r its distance from the nearest rack run, and at least r_goal plain steps from the last one,
     * so it costs at least 0.4 d + 0.6 (max(0, r - 1) + r_goal). The estimate is the smaller of the two: the full distance
     * far from racks, so open-ground routes go straight to the goal, and less only near a rack run. Every route also pays
     * {@link #VERTICAL} per block it still has to climb or drop, and an {@link #ELBOW} per turn it still needs
     * ({@link #turns}); without these, every path with as few turns would tie with the cheapest, and the search would
     * expand them all.
     *
     * <p>Rack runs are boxes of rack cells in a row along their facing's axis; a cell's distance to a box is at most its
     * distance to any rack cell in it. Racks with no room for the line are left out: the line cannot enter them.</p>
     */
    private record Estimate(Cell goal, List<int[]> runs, int goalToRack) {
        static Estimate of(Cell goal, Collection<Cell> racks, Function<Cell, Room> room, Line line) {
            var rows = new HashMap<List<Integer>, List<Integer>>();
            for (var cell : racks) {
                var r = room.apply(cell);
                if (r.admit != Admit.RACK || opened(r, line) == 0) continue;
                int[] c = {cell.x, cell.y, cell.z};
                int a = LineGeometry.axis(r.rack.facing());
                rows.computeIfAbsent(List.of(a, c[(a + 1) % 3], c[(a + 2) % 3]), k -> new ArrayList<>()).add(c[a]);
            }
            var runs = new ArrayList<int[]>();
            for (var row : rows.entrySet()) {
                int a = row.getKey().get(0);
                var along = row.getValue();
                Collections.sort(along);
                for (int i = 0; i < along.size(); ) {
                    int j = i;
                    while (j + 1 < along.size() && along.get(j + 1) <= along.get(j) + 1) j++;
                    var box = new int[6];
                    box[a] = along.get(i);
                    box[a + 3] = along.get(j);
                    box[(a + 1) % 3] = box[(a + 1) % 3 + 3] = row.getKey().get(1);
                    box[(a + 2) % 3] = box[(a + 2) % 3 + 3] = row.getKey().get(2);
                    runs.add(box);
                    i = j + 1;
                }
            }
            var estimate = new Estimate(goal, runs, 0);
            return new Estimate(goal, runs, estimate.toRack(goal));
        }

        /** The estimate from {@code cell}, entered along {@code heading}. */
        double at(Cell cell, int heading) {
            int d = cell.distance(goal);
            double blocks = runs.isEmpty() ? STEP * d : Math.min(STEP * d, RACK_STEP * d + (STEP - RACK_STEP) * (Math.max(0, toRack(cell) - 1) + goalToRack));
            return blocks + VERTICAL * Math.abs(goal.y - cell.y) + ELBOW * turns(cell, heading);
        }

        /**
         * The fewest elbows left: a leg along each axis the goal still lies off, each a change of heading, but for a first
         * leg along the heading the cell was entered on.
         */
        private int turns(Cell cell, int heading) {
            int[] apart = {goal.x - cell.x, goal.y - cell.y, goal.z - cell.z};
            var step = LineGeometry.STEPS[heading];
            int axes = 0;
            boolean along = false;
            for (int a = 0; a < 3; a++)
                if (apart[a] != 0) {
                    axes++;
                    if (Integer.signum(apart[a]) == step[a]) along = true;
                }
            return along ? axes - 1 : axes;
        }

        private int toRack(Cell cell) {
            int nearest = Integer.MAX_VALUE;
            for (var b : runs) {
                int d = Math.max(0, Math.max(b[0] - cell.x, cell.x - b[3])) + Math.max(0, Math.max(b[1] - cell.y, cell.y - b[4])) + Math.max(0, Math.max(b[2] - cell.z, cell.z - b[5]));
                if (d < nearest) nearest = d;
            }
            return nearest;
        }
    }

    private static int[] masks(List<Cell> cells) {
        int n = cells.size();
        var joints = new int[n];
        for (int i = 0; i < n; i++) {
            if (i > 0) joints[i] |= 1 << cells.get(i).faceTowards(cells.get(i - 1));
            if (i + 1 < n) joints[i] |= 1 << cells.get(i).faceTowards(cells.get(i + 1));
        }
        return joints;
    }

    /**
     * The facing each cell is placed with: the face towards the next cell, the last cell keeping the heading it was
     * entered along ({@code heading} for a one-cell line). A straight cell faces along its run, as an axial fitting needs.
     */
    public static int[] facings(List<Cell> cells, int heading) {
        int n = cells.size();
        var facings = new int[n];
        for (int i = 0; i < n; i++)
            facings[i] = i + 1 < n ? cells.get(i).faceTowards(cells.get(i + 1)) : n > 1 ? cells.get(i - 1).faceTowards(cells.get(i)) : heading;
        return facings;
    }

    /** The changes of direction along a checked line, the ends' joints included. */
    public static int elbows(List<Cell> cells, End start, End end) {
        var joints = laidJoints(cells, start, end);
        int elbows = 0;
        for (int mask : joints) if (Integer.bitCount(mask) == 2 && !opposite(mask)) elbows++;
        return elbows;
    }

    private static boolean opposite(int mask) {
        int low = Integer.numberOfTrailingZeros(mask);
        return mask == (1 << low | 1 << (low ^ 1));
    }

    /**
     * Server: why {@code cells} with {@code options} cannot be laid from {@code start} to {@code end}, or null: at most
     * {@code maxCells} cells, each a neighbour of the one before and none twice, from the start's cell to the end's; an
     * option per cell, -1 or one of {@link RackGeometry#OPTIONS}; no end joint on a face the line itself uses; and an
     * aimed end's option, with the line passing on through its {@code through} face. Whether each cell is free, or a rack
     * cell that takes the lane, is the world's to say ({@link #blocked}).
     */
    public static Refusal check(List<Cell> cells, List<Integer> options, End start, End end, int maxCells) {
        if (cells.isEmpty() || cells.size() > maxCells) return Refusal.TOO_LONG;
        if (options.size() != cells.size() || !cells.getFirst().equals(start.cell) || !cells.getLast().equals(end.cell)) return Refusal.BROKEN;
        var seen = new HashSet<Cell>();
        for (int i = 0; i < cells.size(); i++) {
            int option = options.get(i);
            if (!seen.add(cells.get(i)) || option < -1 || option >= RackGeometry.OPTIONS) return Refusal.BROKEN;
            if (i > 0 && cells.get(i - 1).faceTowards(cells.get(i)) < 0) return Refusal.BROKEN;
        }
        var masks = masks(cells);
        int last = cells.size() - 1;
        if (start.joint >= 0 && (masks[0] >> start.joint & 1) != 0) return Refusal.JOINTS;
        if (end.joint >= 0 && (masks[last] >> end.joint & 1) != 0) return Refusal.JOINTS;
        if (last == 0 && start.joint >= 0 && start.joint == end.joint) return Refusal.SAME_FACE;
        if (start.option >= 0 && options.getFirst() != start.option || end.option >= 0 && options.getLast() != end.option) return Refusal.BROKEN;
        if (last > 0 && (start.through >= 0 && cells.get(0).faceTowards(cells.get(1)) != start.through || end.through >= 0 && cells.get(last).faceTowards(cells.get(last - 1)) != end.through))
            return Refusal.BROKEN;
        return null;
    }

    /**
     * Server: the first cell of a checked line that {@code space} does not admit as the search would, or -1: a plain cell
     * free; a rack cell whose lane, with the option sent and the arms the route gives it, suits and fits it, meets the
     * lane before and after it on their shared faces and the end's point; the ends' joints on the faces their cells run
     * to (one cell: its lane's); and no step between a lane and a plain cell through a rack face that another lane already
     * has a joint on. Whether the ends' own hosts take the joints is the anchors' to check.
     */
    public static int blocked(List<Cell> cells, List<Integer> options, End start, int heading, End end, double outside, Space space) {
        var line = Line.of(outside);
        var ends = ends(cells, start, heading, end);
        Room before = null;
        for (int i = 0; i < cells.size(); i++) {
            var room = admitted(space.room(cells.get(i)), line);
            int option = options.get(i);
            boolean lane = option >= 0;
            if (room.admit == Admit.BLOCKED || lane != (room.admit == Admit.RACK)) return i;
            if (i == 0 && !on(start.joint, ends[0]) || i == cells.size() - 1 && !on(end.joint, ends[i])) return i;
            if (lane) {
                if (!fits(room, option, 1 << ends[i][0] | 1 << ends[i][1], line)) return i;
                if (i == 0 && start.point >= 0 && RackGeometry.point(room.rack, option, line.width, line.outside, start.joint) != start.point) return i;
                if (i == cells.size() - 1 && end.point >= 0 && RackGeometry.point(room.rack, option, line.width, line.outside, end.joint) != end.point) return i;
            }
            if (i > 0) {
                int face = ends[i - 1][1];
                int previous = options.get(i - 1);
                boolean wasLane = previous >= 0;
                if (wasLane && !lane && (before.faces >> face & 1) != 0 || !wasLane && lane && (room.faces >> (face ^ 1) & 1) != 0) return i;
                if (wasLane && lane && RackGeometry.point(before.rack, previous, line.width, line.outside, face)
                        != RackGeometry.point(room.rack, option, line.width, line.outside, face ^ 1)) return i;
            }
            before = room;
        }
        return -1;
    }

    /** All joints of each cell of a checked line, the ends' included: what each laid pipe or lane is registered with. */
    public static int[] laidJoints(List<Cell> cells, End start, End end) {
        var joints = masks(cells);
        if (start.joint >= 0) joints[0] |= 1 << start.joint;
        if (end.joint >= 0) joints[cells.size() - 1] |= 1 << end.joint;
        return joints;
    }

    /** How a checked line's cells are laid, one entry per cell: their {@link #laidJoints}, {@link #facings} and {@link #arms}. */
    public record Placement(int[] joints, int[] facings, int[] arms) {
    }

    /** Endpoint edits share the same limit in the laying authority and its preview; -1 means the host is full. */
    public static int hostJoints(int existing, int added, boolean lane) {
        if ((existing & ~63) != 0 || (added & ~63) != 0) throw new IllegalArgumentException("Invalid pipe joints");
        int merged = existing | added;
        return Integer.bitCount(merged) <= (lane ? 2 : 4) ? merged : -1;
    }

    /** The {@link Placement} of a checked line from {@code start}, leaving along {@code heading}, to {@code end}. */
    public static Placement placement(List<Cell> cells, End start, int heading, End end) {
        return new Placement(laidJoints(cells, start, end), facings(cells, heading), arms(cells, start, heading, end));
    }

    /** A search state: the cell, the heading it was entered along, and in a rack the lane options still possible (0 in a plain cell). */
    private record State(Cell cell, int heading, int options) {
    }

    private record Node(Cell cell, int heading, int options, double g, double h, int length, Node parent, long order) implements Comparable<Node> {
        double f() {
            return g + h;
        }

        @Override
        public int compareTo(Node other) {
            int byF = Double.compare(f(), other.f());
            if (byF != 0) return byF;
            int byH = Double.compare(h, other.h);
            return byH != 0 ? byH : Long.compare(order, other.order);
        }
    }
}
