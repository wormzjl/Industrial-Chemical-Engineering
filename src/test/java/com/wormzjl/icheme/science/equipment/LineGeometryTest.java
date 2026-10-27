package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.LineGeometry.Key;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LineGeometryTest {
    private static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;
    private static final double[] SIZES = {1 / 16.0, .15, .4, 1};

    /** A key whose connected faces meet lines of {@code met} outside (0: own size), flanged where {@code flanged} says. */
    private static Key key(Kind kind, int facing, double own, int[] faces, double met, int flanged, boolean spool) {
        int connections = 0;
        long codes = 0;
        for (int face : faces) {
            connections |= 1 << face;
            codes |= (long) PipeSizes.code(met > 0 ? met : own) << 7 * face;
        }
        return new Key(kind, facing, connections, PipeSizes.code(own), codes, flanged, spool);
    }

    private static List<Key> keys() {
        var keys = new ArrayList<Key>();
        int[][] arms = {{}, {EAST}, {WEST, EAST}, {DOWN, UP}, {NORTH, EAST}, {UP, SOUTH}, {WEST, EAST, UP}, {WEST, EAST, NORTH, SOUTH}, {UP, EAST, SOUTH}};
        for (double od : SIZES)
            for (int[] faces : arms)
                for (int flanged : new int[]{0, 1 << EAST | 1 << UP}) {
                    keys.add(key(Kind.PIPE, NORTH, od, faces, 0, flanged, false));
                    keys.add(key(Kind.PIPE, NORTH, od, faces, od / 2, flanged, false));
                }
        for (double od : SIZES) keys.add(key(Kind.PIPE, EAST, od, new int[]{WEST, EAST}, 0, 0, true));
        for (var kind : Kind.values()) {
            // A heat side is a unit's passage (batch 2026-10-27-heat-exchanger-design), never a line block.
            if (kind == Kind.PIPE || kind == Kind.HEAT_SIDE) continue;
            for (int facing : new int[]{NORTH, EAST, UP})
                for (double od : SIZES) {
                    int a = facing & ~1;
                    boolean inline = kind == Kind.VALVE || kind == Kind.PUMP || kind == Kind.COMPRESSOR || kind == Kind.FILTER;
                    int[] faces = inline ? new int[]{a, a + 1} : new int[]{UP, a, a + 1};
                    int flanged = 0;
                    for (int face : faces) flanged |= 1 << face;
                    keys.add(key(kind, facing, od, faces, 0, flanged, false));
                    keys.add(key(kind, facing, od, new int[0], 0, 0, false));
                }
            keys.add(Key.item(kind));
        }
        keys.add(Key.item(Kind.PIPE));
        return keys;
    }

    @Test
    void everyPieceFitsItsDrawingEnvelopeAndFacesOutwards() {
        for (var key : keys()) {
            var polygons = LineGeometry.polygons(key);
            assertFalse(polygons.isEmpty(), key.toString());
            for (var polygon : polygons) {
                assertTrue(polygon.size() >= 3);
                for(int i=0;i<polygon.positions().length;i++) {
                    double v=polygon.positions()[i],lo=0,hi=1;
                    // Only the pressure actuator's stem direction may overhang its host cell.
                    if(key.kind()==Kind.VALVE) {
                        if(key.facing()<2&&i%3==2)lo=-1.1;
                        else if(key.facing()>=2&&i%3==1)hi=2.1;
                    }
                    // The scalable filter body and diagonal service cover retain their decorative overhang.
                    if(key.kind()==Kind.FILTER){lo=-1;hi=2;}
                    assertTrue(v>=lo&&v<=hi,key+" position "+v);
                }
                for (double v : polygon.uvs()) assertTrue(v >= 0 && v <= 1, key + " uv " + v);
                double[] face = newell(polygon), smooth = new double[3];
                for (int i = 0; i < polygon.size(); i++)
                    for (int c = 0; c < 3; c++) smooth[c] += polygon.normals()[3 * i + c];
                assertTrue(face[0] * smooth[0] + face[1] * smooth[1] + face[2] * smooth[2] > 0, key + " winding " + polygon.material());
            }
        }
    }

    @Test
    void runsElbowsReducersAndFlangesAreWatertightBetweenTheirFaces() {
        // Every edge is shared by two facets, except the rims left open on a face plane where the next block continues.
        for (double od : SIZES)
            for (int[] faces : new int[][]{{WEST, EAST}, {DOWN, UP}, {NORTH, EAST}, {UP, SOUTH}, {DOWN, WEST}})
                for (double met : new double[]{0, od / 2})
                    for (int flanged : new int[]{0, 1 << faces[0]})
                        for (boolean spool : new boolean[]{false, true}) {
                            if (spool && (faces[0] ^ 1) != faces[1]) continue;
                            var key = key(Kind.PIPE, NORTH, od, faces, met, flanged, spool);
                            double drawn = PipeSizes.outsideOf(key.outside()), room = .5 - 1.05 * drawn / 2;
                            // A ball corner (no room for a bend) is open inside by design: its arms end in the ball.
                            if ((faces[0] ^ 1) != faces[1] && LineGeometry.bend(LineGeometry.fit(LineGeometry.end(key, faces[0], drawn), room),
                                    LineGeometry.fit(LineGeometry.end(key, faces[1], drawn), room), drawn) == 0) continue;
                            assertWatertight(key);
                        }
    }

    /** Every edge is shared by two facets, except the rims left open on a face plane where the next block continues. */
    private static void assertWatertight(Key key) {
        var edges = new HashMap<String, Integer>();
        var ends = new HashMap<String, double[][]>();
        for (var facet : LineGeometry.mesh(key).facets()) {
            var p = facet.positions();
            for (int i = 0; i < p.size(); i++) {
                double[] a = p.get(i), b = p.get((i + 1) % p.size());
                String ka = round(a), kb = round(b), edge = ka.compareTo(kb) < 0 ? ka + "|" + kb : kb + "|" + ka;
                edges.merge(edge, 1, Integer::sum);
                ends.put(edge, new double[][]{a, b});
            }
        }
        for (var entry : edges.entrySet()) {
            if (entry.getValue() == 2) continue;
            assertEquals(1, entry.getValue(), key + " edge used " + entry.getValue() + " times");
            double[][] e = ends.get(entry.getKey());
            assertTrue(onFacePlane(e[0]) && onFacePlane(e[1]), key + " open edge off the face planes at " + round(e[0]));
        }
    }

    /** The line sizes the transition keys use: each rack width's largest line, the largest resting four-position line, and the largest lowered one. */
    private static final int[] LANE_CODES = {11, 23, 35, 36, 47};

    /**
     * Every point off its face's centre where a lane of size {@code code} meets rack face {@code face}, as packed offsets:
     * over every facing and shape a rack cell takes, every option, and every pair of arms the cell takes alone.
     */
    private static java.util.TreeSet<Integer> lanePoints(int face, int code) {
        var out = new java.util.TreeSet<Integer>();
        double outside = PipeSizes.outsideOf(code);
        for (int facing = 2; facing < 6; facing++)
            for (int corner = RackGeometry.STRAIGHT; corner <= RackGeometry.TEE; corner++) {
                var rack = new RackGeometry.Rack(facing, corner);
                for (int option = 0; option < RackGeometry.OPTIONS; option++)
                    for (int a = 0; a < 6; a++) {
                        int arms = 1 << face | 1 << a;
                        if (a == face) continue;
                        int width = RackGeometry.width(outside);
                        if (!RackGeometry.takes(option, width)) continue;
                        var lane = new RackGeometry.Lane(option, width, arms, arms, code);
                        if (!RackGeometry.fits(rack, List.of(), lane)) continue;
                        int point = RackGeometry.point(rack, option, width, outside, face);
                        if (point != 0) out.add(point);
                    }
            }
        return out;
    }

    /**
     * Transition pipes (batch 2026-10-26-pipe-laying-system, plan 6; RACK_LAYERS_PLAN I2): beside every point off its
     * face's centre where a lane meets a rack face, at each lane size and the smallest line, on to the opposite face,
     * round to each adjacent one, or a dead end; between that point and every point a lane of its size meets in a second
     * rack, opposite and above; and from a flanged off-centre point, as an off-centre nozzle's would be.
     */
    private static List<Key> transitions() {
        var keys = new ArrayList<Key>();
        int small = PipeSizes.code(PipeSizes.MIN_OUTSIDE);
        for (int lane : LANE_CODES) {
            // The pipe's west face meets a rack to its west, through that rack's east face.
            for (int at : lanePoints(EAST, lane)) {
                var step = RackGeometry.steps(at);
                for (int own : new int[]{lane, small})
                    for (int b : new int[]{-1, EAST, UP, DOWN, NORTH, SOUTH}) {
                        long faces = (long) lane << 7 * WEST | (b < 0 ? 0 : (long) own << 7 * b);
                        keys.add(new Key(Kind.PIPE, EAST, 1 << WEST | (b < 0 ? 0 : 1 << b), own, faces, 0, false, Key.offsets(WEST, step[0], step[1])));
                    }
                for (int b : new int[]{EAST, UP})
                    for (int there : lanePoints(b ^ 1, lane)) {
                        var other = RackGeometry.steps(there);
                        keys.add(new Key(Kind.PIPE, EAST, 1 << WEST | 1 << b, lane, (long) lane << 7 * WEST | (long) lane << 7 * b, 0, false,
                                Key.offsets(WEST, step[0], step[1]) | Key.offsets(b, other[0], other[1])));
                    }
            }
        }
        for (double od : new double[]{.1, .2})
            for (int b : new int[]{EAST, UP, NORTH}) {
                int code = PipeSizes.code(od);
                keys.add(new Key(Kind.PIPE, EAST, 1 << WEST | 1 << b, code, (long) code << 7 * WEST | (long) code << 7 * b, 1 << WEST, false, Key.offsets(WEST, 6, -3)));
            }
        return keys;
    }

    @Test
    void transitionPipesStayInTheirBlockAndAreWatertight() {
        var keys = transitions();
        assertEquals(289, keys.size());
        for (var key : keys) {
            assertTrue(LineGeometry.transition(key), key.toString());
            var bounds = LineGeometry.mesh(key).bounds();
            for (int c = 0; c < 3; c++) assertTrue(bounds[c] > -1e-9 && bounds[c + 3] < 1 + 1e-9, key + " leaves its block: " + java.util.Arrays.toString(bounds));
            assertWatertight(key);
        }
        assertFalse(LineGeometry.transition(key(Kind.PIPE, EAST, .2, new int[]{WEST, EAST}, 0, 0, false)), "a centred pipe");
    }

    /**
     * Where a transition pipe meets a lane, or a centred pipe, its open rim is the other line's, vertex for vertex: the
     * facets carried round its bends arrive where the lane's and the pipe's own lie.
     */
    @Test
    void transitionRimsAreTheRimsOfTheLinesTheyMeet() {
        for (var key : transitions()) {
            double own = PipeSizes.outsideOf(key.outside());
            int sides = PipeSizes.sides(own);
            for (int face = 0; face < 6; face++) {
                if (!key.connected(face) || key.flanged(face)) continue;
                double met = PipeSizes.outsideOf(key.face(face));
                if (key.centred(face)) met = Math.min(own, met);
                var frame = new Mesh.Frame(LineGeometry.point(key, face), LineGeometry.step(face), LineGeometry.ref(face));
                var expected = new java.util.TreeSet<String>();
                for (int i = 0; i < sides; i++) expected.add(round(frame.at(2 * Math.PI * i / sides, met / 2, 0)));
                int axis = LineGeometry.axis(face);
                double plane = face & 1;
                var rim = new java.util.TreeSet<String>();
                for (var facet : LineGeometry.mesh(key).facets())
                    for (var p : facet.positions()) if (Math.abs(p[axis] - plane) < 1e-9) rim.add(round(p));
                assertEquals(expected, rim, key + " face " + face);
            }
        }
    }

    @Test
    void aViewerOutsideTheBlockSeesOnlyOuterFaces() {
        // The first polygon a sight line from outside meets must face the viewer; a back face there is a hole in game,
        // where back faces are culled (an open flange, an open body end, a missing base). Sight lines entering through a
        // connected face are skipped: the next block covers that face.
        var random = new java.util.Random(7);
        var holes = new ArrayList<String>();
        for (var key : keys()) {
            var polygons = LineGeometry.polygons(key);
            for (int ray = 0; ray < 200; ray++) {
                double[] target = {.02 + .96 * random.nextDouble(), .02 + .96 * random.nextDouble(), .02 + .96 * random.nextDouble()};
                double[] dir = {random.nextGaussian(), random.nextGaussian(), random.nextGaussian()};
                double length = Math.sqrt(dir[0] * dir[0] + dir[1] * dir[1] + dir[2] * dir[2]);
                for (int c = 0; c < 3; c++) dir[c] /= length;
                double[] origin = {target[0] - 3 * dir[0], target[1] - 3 * dir[1], target[2] - 3 * dir[2]};
                if (key.connected(entryFace(origin, dir))) continue;
                double front = Double.POSITIVE_INFINITY, back = Double.POSITIVE_INFINITY;
                for (var polygon : polygons) {
                    double t = hit(polygon, origin, dir);
                    if (Double.isNaN(t)) continue;
                    var n = newell(polygon);
                    if (n[0] * dir[0] + n[1] * dir[1] + n[2] * dir[2] < 0) front = Math.min(front, t);
                    else back = Math.min(back, t);
                }
                if (back < front - 1e-7) {
                    holes.add(key.kind() + " facing " + key.facing() + " outside " + key.outside() + " connections " + key.connections() + " towards " + round(target));
                    break;
                }
            }
        }
        assertTrue(holes.isEmpty(), holes.size() + " keys show a back face from outside: " + String.join("; ", holes));
    }

    @Test
    void aReducerNarrowsTheLargerPipeToTheSmallerAtTheFace() {
        // A 0.4 m pipe meeting a 0.15 m one: the large block ends at 0.15 m on the shared face, the small one is straight.
        var large = key(Kind.PIPE, EAST, .4, new int[]{WEST, EAST}, .15, 0, false);
        var small = key(Kind.PIPE, EAST, .15, new int[]{WEST, EAST}, .4, 0, false);
        double narrow = PipeSizes.outsideOf(PipeSizes.code(.15)) / 2, wide = PipeSizes.outsideOf(PipeSizes.code(.4)) / 2;
        assertEquals(narrow, rimRadius(large, 0, 1, Material.PIPE), 1e-9);
        assertEquals(narrow, rimRadius(small, 0, 0, Material.PIPE), 1e-9);
        // The large pipe meets the 0.15 m line at both ends here (both faces carry it), so it narrows at both.
        assertEquals(narrow, rimRadius(large, 0, 0, Material.PIPE), 1e-9);
        var meetsLarge = key(Kind.PIPE, EAST, .4, new int[]{WEST, EAST}, 0, 0, false);
        assertEquals(wide, rimRadius(meetsLarge, 0, 0, Material.PIPE), 1e-9);
    }

    @Test void sightGlassNecksAreEnclosedWithoutClosingTheViewingBore(){
        var random=new java.util.Random(61);var holes=new ArrayList<String>();
        for(double bore:new double[]{.05,.21,.5,1})for(int face:new int[]{UP,NORTH,EAST})for(var axis:SightGlassGeometry.Axis.values()){
            if(axis.ordinal()==LineGeometry.axis(face))continue;
            var key=key(Kind.PIPE,face,PipeSizes.outside(bore),new int[]{face,face^1},0,0,false);
            var metal=SightGlassGeometry.build(new SightGlassGeometry.Key(key,axis),false).metal();
            double[] eye={.5,.5,.5},through=new double[3];eye[axis.ordinal()]+=2;through[axis.ordinal()]=-1;
            for(var p:metal)assertTrue(Double.isNaN(hit(p,eye,through)),"the clear centreline must not intersect metal");
            for(int ray=0;ray<80;ray++){
                double[] target={random.nextDouble(),random.nextDouble(),random.nextDouble()},dir={random.nextGaussian(),random.nextGaussian(),random.nextGaussian()};
                double length=Math.sqrt(dir[0]*dir[0]+dir[1]*dir[1]+dir[2]*dir[2]);for(int c=0;c<3;c++)dir[c]/=length;
                double[] origin={target[0]-3*dir[0],target[1]-3*dir[1],target[2]-3*dir[2]};if(key.connected(entryFace(origin,dir)))continue;
                double front=Double.POSITIVE_INFINITY,back=Double.POSITIVE_INFINITY;
                for(var p:metal){double t=hit(p,origin,dir);if(Double.isNaN(t))continue;var n=newell(p);if(n[0]*dir[0]+n[1]*dir[1]+n[2]*dir[2]<0)front=Math.min(front,t);else back=Math.min(back,t);}
                if(back<front-1e-7){holes.add("bore="+bore+" facing="+face+" window="+axis);break;}
            }
        }
        assertEquals(List.of(),holes,"the outside must not expose an uncapped neck or clipped housing face");
    }

    @Test
    void flangePairsMatchAcrossTheJoint() {
        // A pipe flanged to a pump on its east face and the pump's west nozzle draw flanges of one size on the shared plane.
        for (double od : new double[]{1 / 16.0, .15, .4}) {
            var pipe = key(Kind.PIPE, EAST, od, new int[]{WEST, EAST}, 0, 1 << EAST, false);
            var pump = key(Kind.PUMP, EAST, .05, new int[]{WEST, EAST}, od, 1 << WEST | 1 << EAST, false);
            double expected = PipeSizes.flange(PipeSizes.outsideOf(PipeSizes.code(od))) / 2;
            assertEquals(expected, rimRadius(pipe, 0, 1, Material.FLANGE), 1e-9, "pipe " + od);
            assertEquals(expected, rimRadius(pump, 0, 0, Material.FLANGE), 1e-9, "pump " + od);
        }
    }

    @Test
    void devicesStandAsPhotographed() {
        // A control valve's actuator above a horizontal line and beside a vertical one; a pump's motor on top; a
        // strainer's leg below the line and downstream.
        assertTrue(centroid(LineGeometry.polygons(key(Kind.VALVE, EAST, .1, new int[0], 0, 0, false)), Material.ACTUATOR)[1] > .7);
        var vertical = centroid(LineGeometry.polygons(key(Kind.VALVE, UP, .1, new int[0], 0, 0, false)), Material.ACTUATOR);
        assertTrue(vertical[2] < .3 && Math.abs(vertical[1] - .5) < .1, List.of(vertical[0], vertical[1], vertical[2]).toString());
        assertTrue(centroid(LineGeometry.polygons(key(Kind.PUMP, NORTH, .1, new int[0], 0, 0, false)), Material.MOTOR)[1] > .7);
        // The two connection flanges balance about the line; the service cover shifts their centre down/downstream.
        var cover = centroid(LineGeometry.polygons(key(Kind.FILTER, EAST, .15, new int[0], 0, 0, false)), Material.FLANGE);
        assertTrue(cover[0] > .5 && cover[1] < .5, "filter service cover lies below and downstream of the line");
    }

    /** Largest distance from the line's centre of {@code material} vertices on the plane {@code axis} = {@code plane}. */
    private static double rimRadius(Key key, int axis, double plane, Material material) {
        double best = 0;
        for (var polygon : LineGeometry.polygons(key)) {
            if (polygon.material() != material) continue;
            for (int i = 0; i < polygon.size(); i++) {
                double[] p = {polygon.positions()[3 * i], polygon.positions()[3 * i + 1], polygon.positions()[3 * i + 2]};
                if (Math.abs(p[axis] - plane) > 1e-9) continue;
                double d = 0;
                for (int c = 0; c < 3; c++) if (c != axis) d += (p[c] - .5) * (p[c] - .5);
                best = Math.max(best, Math.sqrt(d));
            }
        }
        return best;
    }

    private static double[] centroid(List<Mesh.Polygon> polygons, Material material) {
        var c = new double[3];
        int n = 0;
        for (var polygon : polygons) {
            if (polygon.material() != material) continue;
            for (int i = 0; i < polygon.size(); i++, n++)
                for (int a = 0; a < 3; a++) c[a] += polygon.positions()[3 * i + a];
        }
        assertTrue(n > 0, material + " drawn");
        for (int a = 0; a < 3; a++) c[a] /= n;
        return c;
    }

    /** The face (in {@link LineGeometry#STEPS} order) through which a line from outside enters the unit cell. */
    private static int entryFace(double[] origin, double[] dir) {
        int face = -1;
        double enter = Double.NEGATIVE_INFINITY;
        int[][] faces = {{4, 5}, {0, 1}, {2, 3}};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(dir[a]) < 1e-12) continue;
            double t = ((dir[a] > 0 ? 0 : 1) - origin[a]) / dir[a];
            if (t > enter) {
                enter = t;
                face = faces[a][dir[a] > 0 ? 0 : 1];
            }
        }
        return face;
    }

    /** Distance along the line to the convex polygon, or NaN where it misses. */
    private static double hit(Mesh.Polygon polygon, double[] o, double[] d) {
        double[] p = polygon.positions(), n = newell(polygon);
        double along = n[0] * d[0] + n[1] * d[1] + n[2] * d[2];
        if (Math.abs(along) < 1e-12) return Double.NaN;
        double t = (n[0] * (p[0] - o[0]) + n[1] * (p[1] - o[1]) + n[2] * (p[2] - o[2])) / along;
        double[] q = {o[0] + t * d[0], o[1] + t * d[1], o[2] + t * d[2]};
        int m = polygon.size();
        for (int i = 0; i < m; i++) {
            int j = (i + 1) % m;
            double[] e = {p[3 * j] - p[3 * i], p[3 * j + 1] - p[3 * i + 1], p[3 * j + 2] - p[3 * i + 2]};
            double[] w = {q[0] - p[3 * i], q[1] - p[3 * i + 1], q[2] - p[3 * i + 2]};
            double[] c = {e[1] * w[2] - e[2] * w[1], e[2] * w[0] - e[0] * w[2], e[0] * w[1] - e[1] * w[0]};
            if (c[0] * n[0] + c[1] * n[1] + c[2] * n[2] < -1e-12) return Double.NaN;
        }
        return t;
    }

    private static boolean onFacePlane(double[] p) {
        for (double v : p) if (Math.abs(v) < 1e-9 || Math.abs(v - 1) < 1e-9) return true;
        return false;
    }

    /** A vertex to 1e-7, the grid shifted by a third so that no dyadic coordinate (a texel multiple) falls on a rounding edge. */
    private static String round(double[] p) {
        return Math.round(p[0] * 1e7 + 1 / 3.0) + "," + Math.round(p[1] * 1e7 + 1 / 3.0) + "," + Math.round(p[2] * 1e7 + 1 / 3.0);
    }

    @Test
    void unconnectedEndsCloseInABlindFlange() {
        // Owner request 2026-10-16: "Non-connected pipe tip should look like a flange". A dead end runs straight through
        // its block and a bare pipe through both faces; every unconnected end shows a flange face, as large as the
        // pipe's flange, on the block face.
        for (double od : SIZES)
            for (int[] faces : new int[][]{{WEST}, {}}) {
                var key = key(Kind.PIPE, EAST, od, faces, 0, 0, false);
                double drawn = PipeSizes.outsideOf(key.outside()), reach = 0;
                for (var polygon : LineGeometry.polygons(key)) {
                    if (polygon.material() != Material.FLANGE) continue;
                    double[] p = polygon.positions();
                    boolean onFace = true;
                    for (int i = 0; i < polygon.size(); i++) onFace &= Math.abs(p[3 * i] - 1) < 1e-9;
                    if (!onFace || newell(polygon)[0] <= 0) continue;
                    for (int i = 0; i < polygon.size(); i++) reach = Math.max(reach, Math.hypot(p[3 * i + 1] - .5, p[3 * i + 2] - .5));
                }
                assertEquals(PipeSizes.flange(drawn) / 2, reach, .02 * PipeSizes.flange(drawn) + 1e-6, key.toString());
            }
    }

    private static double[] newell(Mesh.Polygon polygon) {
        double[] p = polygon.positions(), n = new double[3];
        int m = polygon.size();
        for (int i = 0; i < m; i++) {
            int j = (i + 1) % m;
            n[0] += (p[3 * i + 1] - p[3 * j + 1]) * (p[3 * i + 2] + p[3 * j + 2]);
            n[1] += (p[3 * i + 2] - p[3 * j + 2]) * (p[3 * i] + p[3 * j]);
            n[2] += (p[3 * i] - p[3 * j]) * (p[3 * i + 1] + p[3 * j + 1]);
        }
        return n;
    }
}
