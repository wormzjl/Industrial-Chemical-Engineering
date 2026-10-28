package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.ArrayList;
import java.util.List;

import static com.wormzjl.icheme.science.equipment.Mesh.*;

/**
 * The drawn shape of one fluid device block, inside its cell (batch 2026-10-15-custom-equipment-rendering, owner request
 * 2026-10-16: pipes drawn at their diameter up to 1 m, accessories with connections sized by the connected pipe).
 *
 * <p>A pipe runs from its block's centre to each connected face at its own outside diameter. Two arms in line make a
 * straight run, two at right angles a long-radius elbow (bend radius 1.5 OD, shortened to leave room for an end
 * fitting), more a tee or cross along a straight run, or a ball core where no two arms are in line; one arm, or none,
 * runs straight through. Where the next pipe is smaller, a concentric reducer in the larger pipe's block narrows to it
 * at the face. Flanges stand only where a line meets equipment or a vessel nozzle, as a pair in the middle of every
 * twelfth straight block (spool ends; photo survey section 3), and as a blind flange where a line ends unconnected
 * (owner request 2026-10-16); pipes are welded elsewhere.
 *
 * <p>Pipeline devices draw their bodies from photographs (survey section 4): a globe control valve with its diaphragm
 * actuator above a horizontal line or beside a vertical one; a vertical in-line pump with its motor up on a pedestal; a
 * compressor barrel with its motor on a skid; a Y-strainer with its screen leg 45° down and downstream; a small
 * vertical tank on legs; and the feed and drain boxes of the source and sink. Each draws a flanged nozzle at the size of
 * the line met at each connected face; an in-line device draws its two line nozzles even unconnected.
 */
public final class LineGeometry {
    /** Faces in Minecraft's {@code Direction} order (down, up, north, south, west, east), and their unit steps. */
    public static final int[][] STEPS = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
    /** A line nozzle with no pipe met and no size of its own: 0.1 m, a 3" line. */
    public static final double NOZZLE = .1;
    /** The step of a face point's offset from the face's centre: 1/32 m, so a rack slot's centre is a whole number of steps. */
    public static final double OFFSET = 1 / 32.0;
    /** Pipes are welded in spools of this many blocks; the block at each spool end carries a flange pair (a rack lane's, staggered by its position). */
    public static final int SPOOL = 12;
    private static final double[] CENTRE = {.5, .5, .5};
    private static final int UP = 1, NORTH = 2, SOUTH = 3;

    /**
     * What a device block draws. {@code facing} and the six faces follow {@link #STEPS}; {@code connections} has a bit per
     * connected face; {@code outside} is the device's own outside diameter as a {@link PipeSizes#code}; {@code faces}
     * holds, seven bits per face, the outside diameter of the line met there (0: none); {@code flanged} has a bit per face
     * whose joint is flanged; {@code spool} marks a straight pipe at a spool end. {@code offsets} holds, ten bits per face,
     * where the line met there leaves the face's centre (batch 2026-10-26-pipe-laying-system, plan 6): two signed five-bit
     * steps of {@link #OFFSET} along the face's in-plane axes, (axis + 1) % 3 then (axis + 2) % 3; 0 is centred.
     */
    public record Key(Kind kind, int facing, int connections, int outside, long faces, int flanged, boolean spool, long offsets) {
        /** A key whose every face point is centred. */
        public Key(Kind kind, int facing, int connections, int outside, long faces, int flanged, boolean spool) {
            this(kind, facing, connections, outside, faces, flanged, spool, 0);
        }

        /** Face {@code face}'s point off its centre along its in-plane axis {@code k} (0 or 1), in {@link #OFFSET} steps. */
        public int offset(int face, int k) {
            int v = (int) (offsets >>> (10 * face + 5 * k) & 31);
            return v >= 16 ? v - 32 : v;
        }

        public boolean centred(int face) {
            return (offsets >>> 10 * face & 1023) == 0;
        }

        /** The {@link #offsets} bits of face {@code face} at steps {@code u} and {@code v} (each -16 to 15). */
        public static long offsets(int face, int u, int v) {
            if (u < -16 || u > 15 || v < -16 || v > 15) throw new IllegalArgumentException("Face offset " + u + ", " + v + " out of range");
            return ((long) (u & 31) | (long) (v & 31) << 5) << 10 * face;
        }

        public boolean connected(int face) {
            return (connections >> face & 1) != 0;
        }

        public int face(int face) {
            return (int) (faces >>> (7 * face) & 127);
        }

        public boolean flanged(int face) {
            return (flanged >> face & 1) != 0;
        }

        /** An item's look: a pipe 0.25 m across lying north-south, a device on a 0.15 m line. */
        public static Key item(Kind kind) {
            if (kind == Kind.PIPE) {
                int code = PipeSizes.code(.25);
                return new Key(kind, NORTH, 1 << NORTH | 1 << SOUTH, code, (long) code << 7 * NORTH | (long) code << 7 * SOUTH, 0, false);
            }
            return new Key(kind, NORTH, 0, PipeSizes.code(.15), 0, 0, false);
        }
    }

    private LineGeometry() {
    }

    /** The block's polygons, cell-local. */
    public static List<Polygon> polygons(Key key) {
        if(key.kind()==Kind.VALVE)return ValveGeometry.pressure(key,false);
        if(key.kind()==Kind.FILTER)return overhanging(mesh(key));
        return mesh(key).cell(0, 0, 0);
    }

    /** Native Y-strainer, sized from its host pipe and retaining overhanging screen geometry. */
    public static List<Polygon> filterPolygons(Key pipe){return overhanging(mesh(new Key(Kind.FILTER,pipe.facing,pipe.connections,pipe.outside,pipe.faces,pipe.flanged,pipe.spool,pipe.offsets)));}

    /** Keep decorative overhangs in their owning block model, with UV/cell clipping intact. */
    static List<Polygon> overhanging(Mesh mesh) {
        var bounds=mesh.bounds();if(bounds==null)return List.of();var out=new ArrayList<Polygon>();
        for(int x=(int)Math.floor(bounds[0]);x<=Math.floor(bounds[3]);x++)
            for(int y=(int)Math.floor(bounds[1]);y<=Math.floor(bounds[4]);y++)
                for(int z=(int)Math.floor(bounds[2]);z<=Math.floor(bounds[5]);z++)for(var polygon:mesh.cell(x,y,z)) {
                    var positions=polygon.positions().clone();
                    for(int v=0;v<positions.length;v+=3){positions[v]+=x;positions[v+1]+=y;positions[v+2]+=z;}
                    out.add(new Polygon(positions,polygon.uvs(),polygon.normals(),polygon.material()));
                }
        return List.copyOf(out);
    }

    static Mesh mesh(Key key) {
        var m = new Mesh();
        switch (key.kind()) {
            case PIPE -> pipe(m, key);
            case VALVE -> valve(m, key);
            case FILTER -> strainer(m, key);
            case RESERVOIR -> tank(m, key);
            case GENERATOR -> box(m, key, Material.FEED);
            case VOID -> box(m, key, Material.DRAIN);
        }
        return m;
    }

    // ---- Pipes -----------------------------------------------------------------------------------------------------

    /** How a line ends at a face: its outside, flange/fitting lengths, and whether it mates with another block. */
    record End(double outside, boolean flange, double reducer, double tail, boolean joined) {
        static End flanged(double outside, boolean joined) {
            return new End(outside, true, 0, PipeSizes.flangeThickness(outside) + PipeSizes.hub(outside), joined);
        }
    }

    /**
     * A pipe of outside {@code od} at face {@code i}: it narrows to a smaller line met there, and is flanged where the joint
     * is; where it meets nothing it ends in a blind flange of its own size (owner request 2026-10-16).
     */
    static End end(Key key, int i, double od) {
        if (!key.connected(i)) return End.flanged(od, false);
        int code = key.face(i);
        double met = code == 0 ? od : PipeSizes.outsideOf(code);
        // Only an off-centre point widens to a larger line met there: a rack lane draws no reducer of its own.
        if (key.centred(i)) met = Math.min(od, met);
        double reducer = Math.abs(met - od) > EPS ? reducerLength(Math.max(od, met)) : 0;
        double tail = reducer + (key.flanged(i) ? PipeSizes.flangeThickness(met) + PipeSizes.hub(met) : 0);
        return new End(met, key.flanged(i), reducer, tail, true);
    }

    /** The end of a fitting's own arm at face {@code i}: always flanged, its flange and hub after any reducer. */
    static End fittingEnd(Key key, int i, double od) {
        var end = end(key, i, od);
        return new End(end.outside(), true, end.reducer(), end.reducer() + PipeSizes.flangeThickness(end.outside()) + PipeSizes.hub(end.outside()), end.joined());
    }

    /** A concentric reducer's length: ASME B16.9 gives 0.6 to 1.3 times the large end; 0.8 OD, a texel to 0.3 m. */
    static double reducerLength(double large) {
        return Math.max(1 / 16.0, Math.min(.3, .8 * large));
    }

    private static void pipe(Mesh m, Key key) {
        double od = PipeSizes.outsideOf(key.outside());
        int sides = PipeSizes.sides(od);
        var arms = new ArrayList<Integer>();
        for (int i = 0; i < 6; i++) if (key.connected(i)) arms.add(i);
        if (transition(key)) {
            transition(m, key, arms, od, sides);
            return;
        }
        if (arms.size() <= 1) {
            // Unconnected, a length along its facing; a dead end, straight on from its connection: blind flanges where
            // the line ends.
            run(m, key, arms.isEmpty() ? key.facing() & ~1 : arms.getFirst(), od, sides, false);
            return;
        }
        if (arms.size() == 2) {
            int a = arms.get(0), b = arms.get(1);
            if ((a ^ 1) == b) run(m, key, a, od, sides, key.spool());
            else elbow(m, key, a, b, od, sides);
            return;
        }
        // Tee or cross: a straight run through the block (the facing's axis first), branches welded into it.
        int through = -1;
        for (int i : new int[]{key.facing() & ~1, 4, 2, 0})
            if (through < 0 && key.connected(i) && key.connected(i ^ 1)) through = i;
        if (through < 0) ball(m, od, sides);
        else run(m, key, through, od, sides, false);
        for (int i : arms)
            if (through < 0 || (i & ~1) != through) arm(m, i, 0, od, end(key, i, od), ref(i), sides, true);
    }

    /**
     * The ball a dead end, a corner or a core ends its arms in: around the pipe's facets, but inside the block, so a line
     * of one block across keeps its ball whole; the arms ending in it close their start (see {@link #arm}).
     */
    private static void ball(Mesh m, double od, int sides) {
        m.sphere(CENTRE, Math.min(.5, od / 2 / DeterministicMath.cos(Math.PI / sides)), sides, Material.PIPE);
    }

    /** A straight length through faces {@code i} and its opposite, with a flange pair in the middle at a spool end. */
    private static void run(Mesh m, Key key, int i, double od, int sides, boolean spool) {
        double from = spool ? spoolPair(m, frame(i, ref(i)), od, sides) : 0;
        arm(m, i, from, od, end(key, i, od), ref(i), sides, false);
        arm(m, i ^ 1, from, od, end(key, i ^ 1, od), ref(i ^ 1), sides, false);
    }

    /**
     * The flange pair between two spools, its seam at {@code f}'s origin square to its axis, and the necks either side;
     * returns how far along the axis each side reaches, where the pipe goes on.
     */
    static double spoolPair(Mesh m, Frame f, double od, int sides) {
        double t = PipeSizes.flangeThickness(od), hub = PipeSizes.hub(od);
        double flange = PipeSizes.flange(od) / 2, hubR = PipeSizes.hubOutside(od) / 2;
        double halfSeam = PipeSizes.FLANGE_SEAM / 2;
        m.prism(f, flange, -t, -halfSeam, sides, Material.FLANGE);
        m.prism(f, flange, -halfSeam, halfSeam, sides, Material.OPENING);
        m.prism(f, flange, halfSeam, t, sides, Material.FLANGE);
        for (int side = -1; side <= 1; side += 2) {
            m.annulus(f, hubR, flange, side * t, sides, side, Material.FLANGE);
            m.bolts(f, od, hubR, flange, side * t, side, Material.BOLT);
            m.frustum(f, hubR, od / 2, side * t, side * (t + hub), sides, Material.PIPE);
        }
        return t + hub;
    }

    /**
     * The ends of an elbow from face {@code a} to face {@code b} and its bend radius: 1.5 OD, less where end fittings take
     * room (reducers shorten to a texel first); 0 where not even a bend of about one radius fits.
     */
    static double bend(End ea, End eb, double od) {
        double bend = Math.min(1.5 * od, Math.min(.5 - ea.tail(), .5 - eb.tail()));
        return bend < 1.05 * od / 2 ? 0 : bend;
    }

    /** {@code end} with its reducer shortened (to a texel at least) so its fittings take at most {@code room}. */
    static End fit(End end, double room) {
        if (end.tail() <= room || end.reducer() == 0) return end;
        double reducer = Math.max(1 / 16.0, end.reducer() - (end.tail() - room));
        return new End(end.outside(), end.flange(), reducer, end.tail() - end.reducer() + reducer, end.joined());
    }

    /** A long-radius elbow from face {@code a} to face {@code b}, or a ball corner where the fittings leave no room for it. */
    private static void elbow(Mesh m, Key key, int a, int b, double od, int sides) {
        double r = od / 2, room = .5 - 1.05 * r;
        End ea = fit(end(key, a, od), room), eb = fit(end(key, b, od), room);
        double bend = bend(ea, eb, od);
        if (bend == 0) {
            ball(m, od, sides);
            arm(m, a, 0, od, ea, ref(a), sides, true);
            arm(m, b, 0, od, eb, ref(b), sides, true);
            return;
        }
        double[] da = step(a), db = step(b);
        var origin = new double[]{.5 + bend * (da[0] + db[0]), .5 + bend * (da[1] + db[1]), .5 + bend * (da[2] + db[2])};
        // Angle 0 at the tangent point on arm a (towards -b), sweeping a quarter turn to the tangent point on arm b.
        var f = new Frame(origin, cross(db, da), neg(db));
        int segments = Math.max(3, (int) Math.ceil(Math.PI / 2 * bend / .08));
        m.torus(f, bend, r, 0, Math.PI / 2, segments, sides, Material.PIPE);
        // Each arm's prism starts its facets where the torus's cross-section does, so the rims meet vertex to vertex.
        arm(m, a, bend, od, ea, neg(db), sides, false);
        arm(m, b, bend, od, eb, neg(da), sides, false);
    }

    /**
     * A line from {@code from} (metres from the block's centre along face {@code i}) to the face, at outside {@code od},
     * ending as {@code end} says: a reducer, then a square-shouldered flange, or a square cut where nothing connects. A
     * {@code closed} arm also closes its start: it starts inside a ball or a device body whose facets need not cover its
     * rim, and as a closed solid it shows no opening wherever it pokes out. An arm starting within its flange is the
     * flange alone, closed by the flange's own back.
     */
    static void arm(Mesh m, int i, double from, double od, End end, double[] ref, int sides, boolean closed) {
        arm(m, CENTRE, i, from, od, end, ref, sides, closed);
    }

    /** {@link #arm} along the line through {@code origin}, a point half a block in from face {@code i}. */
    static void arm(Mesh m, double[] origin, int i, double from, double od, End end, double[] ref, int sides, boolean closed) {
        var f = new Frame(origin, step(i), ref);
        double r = od / 2, z = Math.max(from, .5 - end.tail());
        double body = end.flange() ? .5 - PipeSizes.flangeThickness(end.outside()) : .5;
        if (closed && from < body - EPS) m.annulus(f, 0, r, from, sides, -1, Material.PIPE);
        if (z > from + EPS) m.prism(f, r, from, z, sides, Material.PIPE);
        if (end.reducer() > 0) {
            double z1 = Math.max(z, .5 - end.tail() + end.reducer());
            if (z1 > z + EPS) m.frustum(f, r, end.outside() / 2, z, z1, sides, Material.PIPE);
            z = z1;
        }
        if (end.flange()) flange(m, f, end.outside(), z, sides, end.joined());
        // A bare end meeting nothing (a rack lane's) is capped.
        else if (!end.joined()) m.annulus(f, 0, r, z, sides, 1, Material.PIPE);
    }

    /**
     * A square-shouldered flange whose face lies on the block face: its straight neck from {@code z}, the back of the flange, its rim and its
     * face, a blind flange where nothing meets it. Where a flange of one size meets it, the two faces lie in one plane
     * and look opposite ways, so only one is ever towards the viewer, and it lies inside the other flange. Without a hub
     * (the line starts within the flange) its back is a whole disc.
     */
    private static void flange(Mesh m, Frame f, double od, double z, int sides, boolean joined) {
        double t = PipeSizes.flangeThickness(od), flange = PipeSizes.flange(od) / 2, hubR = PipeSizes.hubOutside(od) / 2;
        boolean hub = .5 - t > z + EPS;
        if (hub) m.frustum(f, od / 2, hubR, z, .5 - t, sides, Material.PIPE);
        m.annulus(f, hub ? hubR : 0, flange, .5 - t, sides, -1, Material.FLANGE);
        m.bolts(f, od, hub ? hubR : od / 2, flange, .5 - t, -1, Material.BOLT);
        double seam = joined ? PipeSizes.FLANGE_SEAM / 2 : 0;
        m.prism(f, flange, .5 - t, .5 - seam, sides, Material.FLANGE);
        if (joined) m.prism(f, flange, .5 - seam, .5, sides, Material.OPENING);
        m.annulus(f, 0, flange, .5, sides, 1, Material.FLANGE);
    }

    // ---- Transition pipes ------------------------------------------------------------------------------------------

    /** Face {@code face}'s point: its centre, moved by the key's offset along the face's in-plane axes. */
    static double[] point(Key key, int face) {
        var p = new double[]{.5, .5, .5};
        int axis = axis(face);
        p[axis] = face & 1;
        p[(axis + 1) % 3] += key.offset(face, 0) * OFFSET;
        p[(axis + 2) % 3] += key.offset(face, 1) * OFFSET;
        return p;
    }

    /**
     * A transition pipe (plan 6): at most two joints, one at an off-centre point (a rack lane's). To the opposite face it
     * offsets in two equal bends a quarter block in from each face. To an adjacent face it turns once, in the plane of the
     * two faces: where their lines meet, with an offset along the third axis on the way in or on the way out, or at the
     * block's centre with an offset on each leg, whichever leaves the largest bends. A dead end runs on to its opposite
     * face's centre, closed by a blind flange there.
     */
    private static void transition(Mesh m, Key key, List<Integer> arms, double od, int sides) {
        int a = arms.getFirst(), b = arms.size() == 2 ? arms.get(1) : a ^ 1;
        double[] pa = point(key, a), pb = point(key, b);
        End ea = end(key, a, od), eb = end(key, b, od);
        double r = od / 2;
        if ((a ^ 1) == b) {
            var across = step(b);
            var lateral = sub(sub(pb, pa), across);
            if (dot(lateral, lateral) < EPS) {
                // In line: straight through, off the block's centre.
                var origin = lerp(pa, pb, .5);
                arm(m, origin, a, 0, od, ea, ref(a), sides, false);
                arm(m, origin, b, 0, od, eb, ref(b), sides, false);
                return;
            }
            // A quarter block in from each face gives the largest bends (the straights and the slant are then equally tight).
            double in = Math.min(.4, Math.max(.25, Math.max(ea.tail(), eb.tail()) + .15));
            line(m, fitted(List.of(pa, plus(pa, scale(across, in)), sub(pb, scale(across, in)), pb), ea, eb, od), a, b, od, sides);
            return;
        }
        int axisA = axis(a), axisB = axis(b), third = 3 - axisA - axisB;
        double[] inA = neg(step(a)), outB = step(b);
        // Turning where the faces' lines meet in the plane of the turn, the offset along the third axis after the turn or before it.
        var meet = new double[3];
        meet[axisA] = pb[axisA];
        meet[axisB] = pa[axisB];
        meet[third] = pa[third];
        double legA = Math.abs(meet[axisA] - pa[axisA]), legB = Math.abs(pb[axisB] - meet[axisB]);
        var after = new ArrayList<double[]>(List.of(pa, meet));
        offset(after, meet, outB, legB, sub(pb, plus(meet, scale(outB, legB))), eb.tail(), false, r);
        after.add(pb);
        var turn = meet.clone();
        turn[third] = pb[third];
        var before = new ArrayList<double[]>(List.of(pa));
        offset(before, pa, inA, legA, sub(turn, plus(pa, scale(inA, legA))), ea.tail(), true, r);
        before.add(turn);
        before.add(pb);
        // Turning at the block's centre, an offset on each leg.
        var centre = new double[3];
        centre[axisA] = .5;
        centre[axisB] = .5;
        centre[third] = (pa[third] + pb[third]) / 2;
        var middle = new ArrayList<double[]>(List.of(pa));
        offset(middle, pa, inA, .5, sub(centre, plus(pa, scale(inA, .5))), ea.tail(), true, r);
        middle.add(centre);
        offset(middle, centre, outB, .5, sub(pb, plus(centre, scale(outB, .5))), eb.tail(), false, r);
        middle.add(pb);
        Fitted best = null;
        for (var path : List.of(after, before, middle)) {
            var fitted = fitted(path, ea, eb, od);
            if (best == null || fitted.bend() > best.bend() + EPS) best = fitted;
        }
        if (best.bend() >= 1.05 * r) {
            line(m, best, a, b, od, sides);
            return;
        }
        // No room for bends (a wide lane): ball corners, the fewest the turn needs, where each face's line meets the other's
        // plane of the turn, kept a ball clear of the faces. A ball joins straights of any direction, so the offset needs none.
        var first = pa.clone();
        first[axisA] = inside(pb[axisA], a, ea.tail(), r);
        var second = pb.clone();
        second[axisB] = inside(pa[axisB], b, eb.tail(), r);
        var balls = fitted(List.of(pa, first, second, pb), ea, eb, od);
        line(m, new Fitted(balls.points(), balls.dirs(), balls.lengths(), balls.tans(), balls.ea(), balls.eb(), 0), a, b, od, sides);
    }

    /** {@code at} along face {@code face}'s axis, kept a ball's radius (a tenth over {@code r}) inside the block, and {@code tail} more from that face. */
    private static double inside(double at, int face, double tail, double r) {
        double ball = 1.1 * r, near = face & 1;
        double low = near == 0 ? tail + ball : ball, high = near == 0 ? 1 - ball : 1 - tail - ball;
        return low > high ? .5 : Math.max(low, Math.min(high, at));
    }

    /**
     * Adds an offset by {@code shift} (square to {@code along}) on a leg of {@code length} from {@code start}: two bends, at
     * 45° where the leg is twice the shift, steeper where it is shorter, the rest of the leg shared between its ends as
     * their bends need it. The face end (the leg's start where {@code faceFirst}, else its end) keeps its fittings'
     * {@code tail} and a ball corner's radius (a tenth over {@code r}) clear.
     */
    private static void offset(List<double[]> path, double[] start, double[] along, double length, double[] shift, double tail, boolean faceFirst, double r) {
        double size = Math.sqrt(dot(shift, shift));
        if (size < EPS) return;
        double clear = tail + 1.1 * r, slant = Math.min(size, Math.max(EPS, (length - clear) / 2));
        double half = size / (Math.sqrt(size * size + slant * slant) + slant); // tan of half each bend
        double free = Math.max(0, length - clear - slant), atFace = half, atTurn = half + 1; // the turn's own bend: tan 45° = 1
        double first = faceFirst ? clear + free * atFace / (atFace + atTurn) : free * atTurn / (atFace + atTurn);
        path.add(plus(start, scale(along, first)));
        path.add(plus(plus(start, scale(along, first + slant)), shift));
    }

    /**
     * A transition's centre line from face to face: its corners, its legs' directions and lengths, the tangent of half
     * each corner's turn (a bend of radius R takes R tan from each leg beside it), its end fittings shortened to fit, and
     * the one bend radius all its corners take: 1.5 OD, less where the legs between corners are short.
     */
    record Fitted(List<double[]> points, double[][] dirs, double[] lengths, double[] tans, End ea, End eb, double bend) {
    }

    static Fitted fitted(List<double[]> path, End ea, End eb, double od) {
        return fitted(path, ea, eb, od, Double.MAX_VALUE);
    }

    /** As {@link #fitted(List, End, End, double)}, with bends of radius {@code most} at most. */
    static Fitted fitted(List<double[]> path, End ea, End eb, double od, double most) {
        var points = new ArrayList<>(path);
        // A stop on a straight, or on the stop before it, is no corner.
        for (int k = points.size() - 2; k >= 1; k--) {
            var before = sub(points.get(k), points.get(k - 1));
            var after = sub(points.get(k + 1), points.get(k));
            var c = cross(before, after);
            if (dot(after, after) < EPS || dot(before, before) < EPS || dot(c, c) < EPS * dot(before, before) * dot(after, after)) points.remove(k);
        }
        int legs = points.size() - 1;
        double r = od / 2;
        var dirs = new double[legs][];
        var lengths = new double[legs];
        for (int k = 0; k < legs; k++) {
            var d = sub(points.get(k + 1), points.get(k));
            lengths[k] = Math.sqrt(dot(d, d));
            dirs[k] = scale(d, 1 / lengths[k]);
        }
        var tans = new double[legs + 1];
        for (int k = 1; k < legs; k++) {
            var c = cross(dirs[k - 1], dirs[k]);
            tans[k] = Math.sqrt(dot(c, c)) / (1 + dot(dirs[k - 1], dirs[k]));
        }
        ea = fit(ea, Math.max(0, lengths[0] - 1.05 * r * tans[1]));
        eb = fit(eb, Math.max(0, lengths[legs - 1] - 1.05 * r * tans[legs - 1]));
        double bend = Math.min(1.5 * od, most);
        for (int k = 0; k < legs; k++) {
            double need = tans[k] + tans[k + 1], room = lengths[k] - (k == 0 ? ea.tail() : 0) - (k == legs - 1 ? eb.tail() : 0);
            if (need > 0) bend = Math.min(bend, room / need);
        }
        return new Fitted(points, dirs, lengths, tans, ea, eb, Math.max(0, bend));
    }

    /**
     * A pipe along a fitted centre line from face {@code a}'s point to face {@code b}'s. The facets are carried round each
     * bend, so every straight meets its bends vertex to vertex, and bends in one plane, or in planes square to the faces,
     * leave the facets at each face where a centred pipe or a lane has them. Where not even a bend of about one radius
     * fits, the corners are balls.
     */
    static void line(Mesh m, Fitted line, int a, int b, double od, int sides) {
        var points = line.points();
        double[][] dirs = line.dirs();
        double[] lengths = line.lengths(), tans = line.tans();
        int legs = lengths.length;
        double r = od / 2, bend = line.bend();
        var originA = sub(points.getFirst(), scale(step(a), .5));
        var originB = sub(points.getLast(), scale(step(b), .5));
        if (bend < 1.05 * r) {
            // Ball corners, closed straights between them, as an elbow without room for its bend.
            for (int k = 1; k < legs; k++) {
                var p = points.get(k);
                double wall = Math.min(Math.min(Math.min(p[0], 1 - p[0]), Math.min(p[1], 1 - p[1])), Math.min(p[2], 1 - p[2]));
                m.sphere(p, Math.min(wall, r / DeterministicMath.cos(Math.PI / sides)), sides, Material.PIPE);
            }
            arm(m, originA, a, .5 - lengths[0], od, line.ea(), ref(a), sides, true);
            for (int k = 1; k < legs - 1; k++) {
                var f = new Frame(points.get(k), dirs[k], perpendicular(dirs[k]));
                m.annulus(f, 0, r, 0, sides, -1, Material.PIPE);
                m.prism(f, r, 0, lengths[k], sides, Material.PIPE);
                m.annulus(f, 0, r, lengths[k], sides, 1, Material.PIPE);
            }
            arm(m, originB, b, .5 - lengths[legs - 1], od, line.eb(), ref(b), sides, true);
            return;
        }
        double[] facets = ref(a);
        arm(m, originA, a, .5 - lengths[0] + bend * tans[1], od, line.ea(), facets, sides, false);
        for (int k = 1; k < legs; k++) {
            double[] u = dirs[k - 1], w = dirs[k];
            var inward = unit(sub(w, scale(u, dot(w, u))));
            var radial = neg(inward);
            var axis = cross(u, inward);
            var centre = plus(sub(points.get(k), scale(u, bend * tans[k])), scale(inward, bend));
            var c = cross(u, w);
            double turn = DeterministicMath.atan2(Math.sqrt(dot(c, c)), dot(u, w));
            double phase = DeterministicMath.atan2(dot(facets, axis), dot(facets, radial));
            m.torus(new Frame(centre, axis, radial), bend, r, 0, turn, Math.max(3, (int) Math.ceil(turn * bend / .08)), sides, phase, Material.PIPE);
            // The facet reference carried round the bend: at the same angle from the outward radial at its end.
            var out = plus(scale(radial, DeterministicMath.cos(turn)), scale(u, DeterministicMath.sin(turn)));
            facets = plus(scale(out, DeterministicMath.cos(phase)), scale(axis, DeterministicMath.sin(phase)));
            double from = bend * tans[k], to = lengths[k] - bend * tans[k + 1];
            if (k < legs - 1 && to > from + EPS) m.prism(new Frame(points.get(k), w, facets), r, from, to, sides, Material.PIPE);
        }
        arm(m, originB, b, .5 - lengths[legs - 1] + bend * tans[legs - 1], od, line.eb(), facets, sides, false);
    }

    /** A unit vector square to {@code d}. */
    private static double[] perpendicular(double[] d) {
        return unit(cross(d, Math.abs(d[0]) < .9 ? new double[]{1, 0, 0} : new double[]{0, 1, 0}));
    }

    /** True where the key draws a transition pipe: a pipe of at most two joints, one of them off its face's centre. */
    public static boolean transition(Key key) {
        if (key.kind() != Kind.PIPE || Integer.bitCount(key.connections()) > 2) return false;
        for (int i = 0; i < 6; i++) if (key.connected(i) && !key.centred(i)) return true;
        return false;
    }

    // ---- Pipeline devices ------------------------------------------------------------------------------------------

    /** The size of the line through an in-line device: the larger pipe met on its axis, else its own size (at least a 3" stub). */
    private static double line(Key key) {
        int a = key.facing() & ~1;
        double od = Math.max(key.face(a), key.face(a ^ 1)) > 0 ? PipeSizes.outsideOf(Math.max(key.face(a), key.face(a ^ 1)))
                : Math.max(NOZZLE, PipeSizes.outsideOf(key.outside()));
        return od;
    }

    /**
     * An in-line device's two nozzles, flanged at the face, at the size of the line met at each (or the device's line
     * size); each closes its start inside the body.
     */
    private static void lineNozzles(Mesh m, Key key, double from, double od) {
        int a = key.facing() & ~1;
        for (int i : new int[]{a, a ^ 1}) {
            double size = key.face(i) > 0 ? PipeSizes.outsideOf(key.face(i)) : od;
            arm(m, i, from, size, End.flanged(size, key.connected(i)), ref(i), PipeSizes.sides(size), true);
        }
    }

    /** A boundary device's nozzles: one per connected face with a line met, from inside the body at {@code from[face]}. */
    private static void faceNozzles(Mesh m, Key key, double[] from) {
        for (int i = 0; i < 6; i++) {
            if (!key.connected(i) || key.face(i) == 0) continue;
            double size = PipeSizes.outsideOf(key.face(i));
            arm(m, i, from[i], size, End.flanged(size, true), ref(i), PipeSizes.sides(size), true);
        }
    }

    /**
     * A globe control valve: a round body, flanged to the line; a bonnet, a yoke with the stem and a diaphragm casing
     * along the actuator's direction (up over a horizontal line, horizontal beside a vertical one).
     */
    private static void valve(Mesh m, Key key) {
        ValveGeometry.pressure(m,key);
    }

    /** A Y-strainer: a body on the line, its screen leg 45° down and downstream (the facing), closed by a blind flange. */
    private static void strainer(Mesh m, Key key) {
        double od=PipeSizes.outsideOf(key.outside()),rb=Math.max(.025,.62*od),half=Math.max(.06,Math.min(.32,.4*od));
        int a=key.facing()&~1,n=Math.max(12,PipeSizes.sides(2*rb));var f=frame(a,ref(a));
        m.prism(f,rb,-half,half,n,Material.MACHINE);
        // Close the opaque casting behind its nozzles; their lower-sided rims need not match its facets.
        m.annulus(f,0,rb,-half,n,-1,Material.MACHINE);
        m.annulus(f,0,rb,half,n,1,Material.MACHINE);
        lineNozzles(m,key,half,od);
        double[] downstream=step(key.facing()),drop=axis(key.facing())==1?step(NORTH):step(0);
        var direction=unit(downstream[0]+drop[0],downstream[1]+drop[1],downstream[2]+drop[2]);var leg=new Frame(CENTRE,direction,unit(cross(downstream,drop)));
        double rl=.72*rb,cover=1.3*rl,length=1.65*rb+.05,thickness=Math.max(.014,.07*od);
        m.prism(leg,rl,0,length-thickness,n,Material.MACHINE);
        m.annulus(leg,rl,cover,length-thickness,n,-1,Material.FLANGE);
        m.prism(leg,cover,length-thickness,length,n,Material.FLANGE);
        m.annulus(leg,0,cover,length,n,1,Material.FLANGE);
        m.bolts(leg,2*rl,rl,cover,length,1,Material.BOLT);
        m.cylinder(leg,Math.max(.006,.14*rl),length,length+thickness,8,Material.FLANGE);
    }

    /** A small vertical tank on four legs, with dished heads, and a flanged nozzle to each line met. */
    private static void tank(Mesh m, Key key) {
        double radius = .38, bottom = .22, top = .78, depth = .12;
        int n = 24;
        var up = Frame.of(new double[]{.5, 0, .5}, 1, 1);
        m.prism(up, radius, bottom, top, n, Material.SHELL);
        // Dished heads as three bands of a 2:1 ellipse squashed to the head depth.
        double[][] head = {{1, 0}, {.85, .55}, {.5, .87}, {0, 1}};
        for (int k = 0; k + 1 < head.length; k++)
            for (int s = -1; s <= 1; s += 2) {
                double base = s > 0 ? top : bottom;
                m.frustum(up, radius * head[k][0], radius * head[k + 1][0], base + s * depth * head[k][1], base + s * depth * head[k + 1][1], n, Material.HEAD);
            }
        for (int corner = 0; corner < 4; corner++) {
            double angle = Math.PI / 4 + corner * Math.PI / 2, x = .5 + .3 * DeterministicMath.cos(angle), z = .5 + .3 * DeterministicMath.sin(angle);
            m.box(new double[]{x - .03, 0, z - .03}, new double[]{x + .03, bottom + .04, z + .03}, ALL_FACES & ~8, Material.STEELWORK);
        }
        double side = radius * DeterministicMath.cos(Math.PI / n) - .02, end = top - .5 + depth - .02;
        faceNozzles(m, key, new double[]{end, end, side, side, side, side});
    }

    /** The source or sink: a painted box on the floor with a flanged nozzle to each line met. */
    private static void box(Mesh m, Key key, Material material) {
        m.box(new double[]{.18, 0, .18}, new double[]{.82, .7, .82}, ALL_FACES, material);
        m.box(new double[]{.15, .7, .15}, new double[]{.85, .76, .85}, ALL_FACES, Material.STEELWORK);
        faceNozzles(m, key, new double[]{.5, .25, .3, .3, .3, .3});
    }

    // ---- Frames ----------------------------------------------------------------------------------------------------

    static double[] step(int face) {
        var s = STEPS[face];
        return new double[]{s[0], s[1], s[2]};
    }

    /** The default facet reference for an arm along face {@code i}: the next box axis, as vessel nozzles use it. */
    static double[] ref(int face) {
        var r = new double[3];
        r[(axis(face) + 1) % 3] = 1;
        return r;
    }

    static int axis(int face) {
        return face < 2 ? 1 : face < 4 ? 2 : 0;
    }

    /** The frame of an arm from the block centre towards face {@code i}, facets starting at {@code ref}. */
    static Frame frame(int face, double[] ref) {
        return new Frame(CENTRE, step(face), ref);
    }

    private static double[] side(Frame f) {
        return cross(f.axis(), f.ref());
    }
}
