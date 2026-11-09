package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Procedural surfaces of block-scale equipment (batch 2026-10-15-custom-equipment-rendering): a list of planar convex
 * facets, each with a material, a texture frame and the smooth surface it approximates, and the cutting of those facets
 * into the polygons of one block cell. Vessels, columns, pipes and pipeline devices are all built from its primitives
 * (prisms, frustums, rings, discs, boxes, elbows, spheres) in a local frame and turned into the box by a proper quarter-turn
 * {@link Transform}, so every polygon keeps its counter-clockwise winding seen from outside.
 *
 * <p>Coordinates are metres (1 block = 1 m). A facet lying in a cell face belongs to the cell on its inner side, so each
 * piece of surface is drawn by exactly one cell.
 */
public final class Mesh {
    static final double EPS = 1e-9, MIN_AREA = 1e-7, OWNER_NUDGE = 1e-6;

    /**
     * Which sprite a polygon samples, how many metres one sprite tile covers when a part has no frame of its own, and
     * which {@link Paint} colour a vessel's sprite of it takes.
     */
    public enum Material {
        SHELL(4, Paint.BODY), HEAD(4, Paint.BODY), SUPPORT(2, Paint.STRUCTURE), PIPE(1, -1), FLANGE(1, -1), OPENING(1, -1), CLADDING(2, Paint.BODY),
        GRATING(1, -1), HANDRAIL(1, -1), STEELWORK(1, Paint.STRUCTURE), MACHINE(1, Paint.BODY), MOTOR(1, -1), ACTUATOR(1, -1), FEED(1, -1), DRAIN(1, -1),
        /** A flange's nuts and stud ends ({@link #bolts}). */
        BOLT(1, -1), BOLT_FACE(1, -1),
        /**
         * An air cooler's Blender parts (their own texture coordinates) and sprites: fan rotor, fan ring and guard, motor;
         * fins (the bundle, seen through the fan rings from below), headers, the plenum casing's sheet.
         */
        AC_FAN(1, -1), AC_RING(1, Paint.BODY), AC_MOTOR(1, -1), AC_FINS(1, -1), AC_HEADER(.5, Paint.BODY), AC_PLENUM(1, Paint.BODY),
        /**
         * A fired heater's casing (panels between stiffeners 2 m apart), a machine's concrete plinth or block, a pump's
         * coupling guard in safety yellow (owner request 2026-10-17).
         */
        HEATER(2, Paint.BODY), CONCRETE(2, -1), GUARD(1, -1), GLASS(1, -1),
        /** Neutral, unpainted level-instrument scale. */
        GAUGE_SCALE(1, -1),
        /** A tower's baked sprites, each a whole panel: casing, louvers, fan stack, deck, the spray seen through a fan, a natural shell's concrete. */
        TOWER_PANEL(4, Paint.BODY), TOWER_LOUVER(4, Paint.BODY), TOWER_STACK(2, Paint.BODY), TOWER_DECK(4, -1), TOWER_DRIFT(4, -1), TOWER_CONCRETE(16, -1), TOWER_FILL(64, -1), TOWER_FLOOR(80, -1),
        /**
         * A pump's baked sprites (batch 2026-11-03-rotating-machinery, {@code tools/pump-sprites}): its coated cast surface
         * (tiled by two metres, so its tiles meet at block faces), its volute's crown, its finned bearing housing, a bolted
         * joint's rim and face, its baseplate's drain-rim deck and channel sides. All but the casting are stretched once
         * over their part ({@link Mesh#band}, {@link Mesh#disc}).
         */
        PUMP_CASTING(2, Paint.BODY), PUMP_VOLUTE(.5, Paint.BODY), PUMP_FINS(1, Paint.BODY), PUMP_JOINT(1, Paint.BODY), PUMP_DISC(1, Paint.BODY), PUMP_COVER(1, Paint.BODY),
        PUMP_BASE(1, Paint.STRUCTURE), PUMP_CHANNEL(1, Paint.STRUCTURE),
        /**
         * A coupling guard's framed expanded-metal panel in safety yellow, one sprite a panel ({@code ProcessUnit.guard}); its
         * own sprite, so {@link #GUARD}'s plain yellow (valve handles, labels, derricks) stays as it is.
         */
        COUPLING_GUARD(1, -1),
        /** Bright machined steel: every turning shaft and coupling hub, as the shaft block draws its shaft ({@code shaft_steel}). */
        SHAFT(1, -1),
        /**
         * A TEFC motor's baked sprites, neutral greys its paint tints (owner request 2026-11-04): its finned frame and nameplate
         * stretched over its body, its end shield's face, a large motor's bolted panels (tiled by the metre), the plain
         * cast parts (cowl, terminal box, feet, caps).
         */
        MOTOR_FRAME(1, Paint.BODY), MOTOR_SHIELD(1, Paint.BODY), MOTOR_PANEL(1, Paint.BODY), GENERATOR_PANEL(1, Paint.BODY), MOTOR_PLAIN(1, Paint.BODY),
        /** Fixed shaft role markings, independent of the equipment's paint. */
        SHAFT_INPUT(1, -1), SHAFT_OUTPUT(1, -1),
        /** Equipment service cabinets and the protected, equipment-owned instrument raceway finish. */
        SERVICE_CASE(1,Paint.BODY), SERVICE_COVER(1,Paint.BODY), INSTRUMENT_STEEL(1,-1), INSTRUMENT_CONDUIT(1,-1),
        /**
         * A batch still's own finishes (batch 2026-11-09-batch-distiller), unpainted: its copper pot, helmet, column and
         * lines, its brick setting and flue, its cast-iron doors, hoops and valve, its wooden worm tub; a block's texture
         * a metre.
         */
        COPPER(1, -1), BRICK(1, -1), IRON(1, -1), WOOD(1, -1);

        private final double period;
        private final int channel;

        Material(double period, int channel) {
            this.period = period;
            this.channel = channel;
        }

        public double period() {
            return period;
        }

        /**
         * The paint channel a vessel's part of this material takes ({@link Paint#BODY} or {@link Paint#STRUCTURE}), or -1
         * for parts that keep their own colour. Pipeline devices are never painted.
         */
        public int channel() {
            return channel;
        }

        /** The texture variable a model JSON names this material's sprite with. */
        public String texture() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * Convex planar polygon in cell-local coordinates [0, 1], counter-clockwise seen from outside the part; texture
     * coordinates in [0, 1] of its material's sprite; one unit normal per vertex from the smooth surface, for lighting.
     */
    public record Polygon(double[] positions, double[] uvs, double[] normals, Material material) {
        public int size() {
            return positions.length / 3;
        }
    }

    /** A proper rotation by quarter turns (entries -1, 0, 1, determinant 1) followed by a translation. */
    public record Transform(int[] matrix, double[] offset) {
        public static final Transform IDENTITY = new Transform(new int[]{1, 0, 0, 0, 1, 0, 0, 0, 1}, new double[3]);

        public double[] point(double[] p) {
            var d = direction(p);
            return new double[]{d[0] + offset[0], d[1] + offset[1], d[2] + offset[2]};
        }

        public double[] direction(double[] d) {
            int[] m = matrix;
            return new double[]{m[0] * d[0] + m[1] * d[1] + m[2] * d[2], m[3] * d[0] + m[4] * d[1] + m[5] * d[2], m[6] * d[0] + m[7] * d[1] + m[8] * d[2]};
        }

        /** The inverse: the box back to the local frame. */
        public double[] local(double[] p) {
            int[] m = matrix;
            double x = p[0] - offset[0], y = p[1] - offset[1], z = p[2] - offset[2];
            return new double[]{m[0] * x + m[3] * y + m[6] * z, m[1] * x + m[4] * y + m[7] * z, m[2] * x + m[5] * y + m[8] * z};
        }

        /** This transform applied after {@code inner}. */
        public Transform after(Transform inner) {
            int[] a = matrix, b = inner.matrix, m = new int[9];
            for (int r = 0; r < 3; r++)
                for (int c = 0; c < 3; c++) m[3 * r + c] = a[3 * r] * b[c] + a[3 * r + 1] * b[3 + c] + a[3 * r + 2] * b[6 + c];
            return new Transform(m, point(inner.offset));
        }

        /** A quarter turn {@code turns} times counter-clockwise seen from above (about +y), then a shift. */
        public static Transform yaw(int turns, double x, double y, double z) {
            int[][] m = {{1, 0, 0, 0, 1, 0, 0, 0, 1}, {0, 0, 1, 0, 1, 0, -1, 0, 0}, {-1, 0, 0, 0, 1, 0, 0, 0, -1}, {0, 0, -1, 0, 1, 0, 1, 0, 0}};
            return new Transform(m[Math.floorMod(turns, 4)], new double[]{x, y, z});
        }
    }

    /** How a facet is lit at a point: its own plane, or the normal of the smooth surface it approximates. */
    sealed interface Shading permits Flat, Radial, Spheroid, Torus, Cone, Sphere, Inward, Interpolated {
        /** Normal at a box point, not necessarily of unit length; null for the facet's own normal. */
        double[] at(double[] p);

        Shading map(Transform t);
    }

    record Flat() implements Shading {
        public double[] at(double[] p) {
            return null;
        }

        public Shading map(Transform t) {
            return this;
        }
    }

    /** A surface of revolution with a straight meridian: radial from the axis line through {@code centre}. */
    record Radial(double[] centre, double[] axis) implements Shading {
        public double[] at(double[] p) {
            var r = sub(p, centre);
            double along = dot(r, axis);
            return new double[]{r[0] - along * axis[0], r[1] - along * axis[1], r[2] - along * axis[2]};
        }

        public Shading map(Transform t) {
            return new Radial(t.point(centre), t.direction(axis));
        }
    }

    /** The inside of a tube around the axis line through {@code centre}: towards the axis. */
    record Inward(double[] centre, double[] axis) implements Shading {
        public double[] at(double[] p) {
            var n = new Radial(centre, axis).at(p);
            return new double[]{-n[0], -n[1], -n[2]};
        }

        public Shading map(Transform t) {
            return new Inward(t.point(centre), t.direction(axis));
        }
    }

    /** A spheroid of equatorial radius {@code radius} and polar semi-axis {@code depth} along {@code axis} (a vessel head). */
    record Spheroid(double[] centre, double[] axis, double radius, double depth) implements Shading {
        public double[] at(double[] p) {
            var r = sub(p, centre);
            double along = dot(r, axis), r2 = radius * radius, h2 = depth * depth;
            return new double[]{(r[0] - along * axis[0]) / r2 + along * axis[0] / h2, (r[1] - along * axis[1]) / r2 + along * axis[1] / h2,
                    (r[2] - along * axis[2]) / r2 + along * axis[2] / h2};
        }

        public Shading map(Transform t) {
            return new Spheroid(t.point(centre), t.direction(axis), radius, depth);
        }
    }

    /** A torus whose centre line is the circle of radius {@code bend} around {@code axis} through {@code centre} (an elbow). */
    record Torus(double[] centre, double[] axis, double bend) implements Shading {
        public double[] at(double[] p) {
            var radial = new Radial(centre, axis).at(p);
            double length = Math.sqrt(dot(radial, radial));
            if (length < EPS) return null;
            // The nearest point of the centre line, a circle in the plane through the centre across the axis.
            double[] onCircle = {centre[0] + radial[0] / length * bend, centre[1] + radial[1] / length * bend, centre[2] + radial[2] / length * bend};
            return sub(p, onCircle);
        }

        public Shading map(Transform t) {
            return new Torus(t.point(centre), t.direction(axis), bend);
        }
    }

    /** A cone around the axis line through {@code centre}: radial plus {@code slope} times the axis, with slope = -dr/dz. */
    record Cone(double[] centre, double[] axis, double slope) implements Shading {
        public double[] at(double[] p) {
            var radial = new Radial(centre, axis).at(p);
            double length = Math.sqrt(dot(radial, radial));
            if (length < EPS) return null;
            return new double[]{radial[0] / length + slope * axis[0], radial[1] / length + slope * axis[1], radial[2] / length + slope * axis[2]};
        }

        public Shading map(Transform t) {
            return new Cone(t.point(centre), t.direction(axis), slope);
        }
    }

    record Sphere(double[] centre) implements Shading {
        public double[] at(double[] p) {
            return sub(p, centre);
        }

        public Shading map(Transform t) {
            return new Sphere(t.point(centre));
        }
    }

    /** An imported triangle's surface: its three corners' normals, blended by where a point lies in it. */
    record Interpolated(double[] p0, double[] p1, double[] p2, double[] n0, double[] n1, double[] n2) implements Shading {
        public double[] at(double[] p) {
            double[] e1 = sub(p1, p0), e2 = sub(p2, p0), w = sub(p, p0);
            double a = dot(e1, e1), b = dot(e1, e2), c = dot(e2, e2), det = a * c - b * b;
            if (det <= 0) return null;
            double s = (c * dot(w, e1) - b * dot(w, e2)) / det, t = (a * dot(w, e2) - b * dot(w, e1)) / det, r = 1 - s - t;
            return new double[]{r * n0[0] + s * n1[0] + t * n2[0], r * n0[1] + s * n1[1] + t * n2[1], r * n0[2] + s * n1[2] + t * n2[2]};
        }

        public Shading map(Transform t) {
            return new Interpolated(t.point(p0), t.point(p1), t.point(p2), t.direction(n0), t.direction(n1), t.direction(n2));
        }
    }

    static final Shading FLAT = new Flat();

    /** A texture coordinate, in sprite tiles: (offset + dot(p - origin, axis)) / period. */
    record Uv(double[] origin, double[] axis, double offset, double period) {
        double at(double[] p) {
            return (offset + (p[0] - origin[0]) * axis[0] + (p[1] - origin[1]) * axis[1] + (p[2] - origin[2]) * axis[2]) / period;
        }

        Uv map(Transform t) {
            return new Uv(t.point(origin), t.direction(axis), offset, period);
        }
    }

    /**
     * Box-axis projections by material and facing ({@link #projection}): the sprite upright on side faces and unmirrored
     * seen from outside. Shared by every facet without a texture frame of its own (two records and four arrays a facet
     * saved; never changed).
     */
    private static final Uv[][][] BOX_UVS = new Uv[Material.values().length][6][];

    static {
        var origin = new double[3];
        for (var material : Material.values())
            for (int k = 0; k < 6; k++) {
                double[] uAxis, vAxis;
                if (k < 2) {
                    uAxis = new double[]{1, 0, 0};
                    vAxis = new double[]{0, 0, k == 0 ? -1 : 1};
                } else if (k < 4) {
                    uAxis = new double[]{0, 0, k == 2 ? -1 : 1};
                    vAxis = new double[]{0, 1, 0};
                } else {
                    uAxis = new double[]{k == 4 ? 1 : -1, 0, 0};
                    vAxis = new double[]{0, 1, 0};
                }
                BOX_UVS[material.ordinal()][k] = new Uv[]{new Uv(origin, uAxis, 0, material.period()), new Uv(origin, vAxis, 0, material.period())};
            }
    }

    /** A facing's box projection: 0 up, 1 down, 2 +x, 3 -x, 4 +z, 5 -z, by its largest component (y, then x, first on ties). */
    private static int projection(double[] facing) {
        double ax = Math.abs(facing[0]), ay = Math.abs(facing[1]), az = Math.abs(facing[2]);
        if (ay >= ax && ay >= az) return facing[1] > 0 ? 0 : 1;
        if (ax >= az) return facing[0] > 0 ? 2 : 3;
        return facing[2] > 0 ? 4 : 5;
    }

    /**
     * A planar convex facet in box coordinates, counter-clockwise seen from outside, with its texture frame; {@code part}
     * is the moving part it belongs to ({@link ProcessUnit.Motion}, by index), -1 for the still parts.
     */
    record Facet(List<double[]> positions, double[] normal, double[] min, double[] max, Shading shading, Material material, Uv u, Uv v, int part) {
    }

    /** Whole facet for a large static mesh, with one sprite tile per facet and the same smooth normals. */
    static Polygon whole(Facet facet){
        int count=facet.positions().size();var positions=new double[3*count];var normals=new double[3*count];var uv=new double[2*count];
        double minU=Double.POSITIVE_INFINITY,minV=minU,maxU=Double.NEGATIVE_INFINITY,maxV=maxU;
        for(var point:facet.positions()){
            double u=facet.u.at(point),v=facet.v.at(point);
            minU=Math.min(minU,u);maxU=Math.max(maxU,u);minV=Math.min(minV,v);maxV=Math.max(maxV,v);
        }
        for(int index=0;index<count;index++){
            var point=facet.positions().get(index);System.arraycopy(point,0,positions,3*index,3);
            var normal=facet.shading.at(point);
            if(normal==null||dot(normal,normal)<EPS*EPS||dot(normal,facet.normal)<=0)normal=facet.normal;
            System.arraycopy(unit(normal),0,normals,3*index,3);
            uv[2*index]=(facet.u.at(point)-minU)/Math.max(EPS,maxU-minU);
            uv[2*index+1]=1-(facet.v.at(point)-minV)/Math.max(EPS,maxV-minV);
        }
        return new Polygon(positions,uv,normals,facet.material);
    }

    /**
     * An orthonormal frame for parts of revolution: {@code origin}, the {@code axis}, and {@code ref}, the direction of
     * angle 0 around it; angles run from {@code ref} towards {@code axis × ref}.
     */
    public record Frame(double[] origin, double[] axis, double[] ref) {
        double[] side() {
            return cross(axis, ref);
        }

        /** The point at angle {@code angle}, distance {@code r} from the axis and {@code z} along it. */
        double[] at(double angle, double r, double z) {
            double c = DeterministicMath.cos(angle), s = DeterministicMath.sin(angle);
            var side = side();
            return new double[]{origin[0] + r * (c * ref[0] + s * side[0]) + z * axis[0], origin[1] + r * (c * ref[1] + s * side[1]) + z * axis[1],
                    origin[2] + r * (c * ref[2] + s * side[2]) + z * axis[2]};
        }

        double[] along(double z) {
            return new double[]{origin[0] + z * axis[0], origin[1] + z * axis[1], origin[2] + z * axis[2]};
        }

        /** A frame with its axis along a box direction (0 x, 1 y, 2 z, sign) through {@code origin}. */
        public static Frame of(double[] origin, int axisIndex, double sign) {
            double[] axis = new double[3], ref = new double[3];
            axis[axisIndex] = sign;
            ref[(axisIndex + 1) % 3] = 1;
            return new Frame(origin, axis, ref);
        }
    }

    private final List<Facet> facets = new ArrayList<>();
    private Transform transform = Transform.IDENTITY;
    private int part = -1;

    private final RenderDetail detail;

    public Mesh() { this(RenderDetail.NORMAL); }
    public Mesh(RenderDetail detail) { this.detail = java.util.Objects.requireNonNull(detail); }
    RenderDetail detail() { return detail; }

    /** The transform applied to everything added from now on. */
    public void transform(Transform transform) {
        this.transform = transform;
    }

    public Transform transform() {
        return transform;
    }

    /** The moving part everything added from now on belongs to ({@link ProcessUnit.Motion}, by index); -1: the still parts. */
    void part(int part) {
        this.part = part;
    }

    List<Facet> facets() {
        return facets;
    }

    // ---- Primitives, in the local frame -------------------------------------------------------------------------

    /**
     * Adds a convex polygon, oriented counter-clockwise seen from away from {@code inside}, with a texture frame (or null
     * for a projection along the facet's main box axis at the material's period).
     */
    void add(List<double[]> polygon, double[] inside, Shading shading, Material material, Uv u, Uv v) {
        var normal = newell(polygon);
        double[] centroid = new double[3];
        for (var p : polygon)
            for (int a = 0; a < 3; a++) centroid[a] += p[a] / polygon.size();
        var oriented = new ArrayList<>(polygon);
        if (dot(normal, sub(centroid, inside)) < 0) {
            Collections.reverse(oriented);
            normal = neg(normal);
        }
        if (dot(normal, normal) < 4 * MIN_AREA * MIN_AREA) return;
        var positions = new ArrayList<double[]>(oriented.size());
        double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (var p : oriented) {
            var w = transform.point(p);
            positions.add(w);
            for (int a = 0; a < 3; a++) {
                min[a] = Math.min(min[a], w[a]);
                max[a] = Math.max(max[a], w[a]);
            }
        }
        var facing = transform.direction(unit(normal));
        Uv worldU, worldV;
        if (u != null) {
            worldU = u.map(transform);
            worldV = v.map(transform);
        } else {
            var projection = BOX_UVS[material.ordinal()][projection(facing)];
            worldU = projection[0];
            worldV = projection[1];
        }
        facets.add(new Facet(List.copyOf(positions), facing, min, max, shading.map(transform), material, worldU, worldV, part));
    }

    /**
     * Adds the facets of {@code source}, built in this mesh's local frame without a transform of their own, turned by
     * this mesh's transform: a part built once in its frame (a unit) placed in any box. Texture frames and shadings the
     * source's facets share stay shared, each mapped once.
     */
    void include(Mesh source) {
        var uvs = new IdentityHashMap<Uv, Uv>();
        var shadings = new IdentityHashMap<Shading, Shading>();
        for (var f : source.facets) {
            var positions = new ArrayList<double[]>(f.positions().size());
            double[] min = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, max = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
            for (var p : f.positions()) {
                var w = transform.point(p);
                positions.add(w);
                for (int a = 0; a < 3; a++) {
                    min[a] = Math.min(min[a], w[a]);
                    max[a] = Math.max(max[a], w[a]);
                }
            }
            facets.add(new Facet(List.copyOf(positions), transform.direction(f.normal()), min, max, shadings.computeIfAbsent(f.shading(), s -> s.map(transform)), f.material(),
                    uvs.computeIfAbsent(f.u(), u -> u.map(transform)), uvs.computeIfAbsent(f.v(), v -> v.map(transform)), f.part()));
        }
    }

    /** Imports the existing polygon's UV frame and normals without inventing another mesh convention. */
    void include(Polygon polygon) {
        var points=new double[polygon.size()][3];var normals=new double[polygon.size()][3];var uv=new double[polygon.size()][2];
        for(int i=0;i<points.length;i++){System.arraycopy(polygon.positions(),3*i,points[i],0,3);System.arraycopy(polygon.normals(),3*i,normals[i],0,3);System.arraycopy(polygon.uvs(),2*i,uv[i],0,2);}
        imported(points,Arrays.copyOf(points,3),Arrays.copyOf(uv,3),Arrays.copyOf(normals,3),polygon.material());
    }

    /** {min, max} corners of every facet, {x0, y0, z0, x1, y1, z1}; null when there is none. */
    double[] bounds() {
        if (facets.isEmpty()) return null;
        double[] out = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (var f : facets)
            for (int a = 0; a < 3; a++) {
                out[a] = Math.min(out[a], f.min()[a]);
                out[a + 3] = Math.max(out[a + 3], f.max()[a]);
            }
        return out;
    }

    /** A flat polygon whose front faces along {@code outward}. */
    void flat(List<double[]> polygon, double[] outward, Material material) {
        var centroid = new double[3];
        for (var p : polygon)
            for (int a = 0; a < 3; a++) centroid[a] += p[a] / polygon.size();
        add(polygon, sub(centroid, outward), FLAT, material, null, null);
    }

    /**
     * An imported part's convex face {@code corners} ({@link Part}: a triangle, or a quad of two triangles merged because
     * their texture coordinates and normals are one affine field), counter-clockwise seen from outside. Its texture
     * coordinates (in its material's sprite, 0 to 1, v down) and normals are those of the reference triangle {@code p} at
     * its corners, extended affinely over the face's plane, so a cell's piece of it samples the same texels; drawn as one
     * facet, a quad costs one chunk quad, not two.
     */
    void imported(double[][] corners, double[][] p, double[][] uv, double[][] n, Material material) {
        double[] e1 = sub(p[1], p[0]), e2 = sub(p[2], p[0]);
        double a = dot(e1, e1), b = dot(e1, e2), c = dot(e2, e2), det = a * c - b * b;
        if (det < 4 * MIN_AREA * MIN_AREA) return;
        var u = affine(p[0], e1, e2, a, b, c, det, uv[0][0], uv[1][0] - uv[0][0], uv[2][0] - uv[0][0]);
        var v = affine(p[0], e1, e2, a, b, c, det, uv[0][1], uv[1][1] - uv[0][1], uv[2][1] - uv[0][1]);
        var outward = cross(e1, e2);
        double[] inside = new double[3];
        for (var corner : corners)
            for (int k = 0; k < 3; k++) inside[k] += corner[k] / corners.length;
        for (int k = 0; k < 3; k++) inside[k] -= outward[k];
        add(List.of(corners), inside, new Interpolated(p[0], p[1], p[2], n[0], n[1], n[2]), material, u, v);
    }

    /** The coordinate {@code at0} + d at the triangle's corners (d1, d2 at its second and third), affine over its plane. */
    private static Uv affine(double[] origin, double[] e1, double[] e2, double a, double b, double c, double det, double at0, double d1, double d2) {
        double alpha = (c * d1 - b * d2) / det, beta = (a * d2 - b * d1) / det;
        return new Uv(origin, new double[]{alpha * e1[0] + beta * e2[0], alpha * e1[1] + beta * e2[1], alpha * e1[2] + beta * e2[2]}, at0, 1);
    }

    /** The side of a prism of {@code n} facets, radius {@code r} (to its vertices), from {@code z0} to {@code z1} along the frame. */
    void prism(Frame f, double r, double z0, double z1, int n, Material material) {
        frustum(f, r, r, z0, z1, n, material);
    }

    /** The side of a frustum from radius {@code r0} at {@code z0} to {@code r1} at {@code z1}, lit as a cone. */
    void frustum(Frame f, double r0, double r1, double z0, double z1, int n, Material material) {
        n = detail.sides(n);
        if (z1 < z0) {
            frustum(f, r1, r0, z1, z0, n, material);
            return;
        }
        double chord = 2 * Math.max(r0, r1) * DeterministicMath.sin(Math.PI / n), slope = (r0 - r1) / Math.max(EPS, z1 - z0);
        Shading shading = r0 == r1 ? new Radial(f.origin, f.axis) : new Cone(f.origin, f.axis, slope);
        var along = new Uv(f.along(z0), f.axis, 0, material.period());
        double rWide = Math.max(r0, r1), zWide = r0 >= r1 ? z0 : z1;
        for (int i = 0; i < n; i++) {
            double a0 = 2 * Math.PI * i / n, a1 = 2 * Math.PI * (i + 1) / n;
            var p0 = f.at(a0, r0, z0);
            var p1 = f.at(a1, r0, z0);
            var w0 = f.at(a0, rWide, zWide);
            var around = new Uv(w0, neg(unit(sub(f.at(a1, rWide, zWide), w0))), -i * chord, material.period());
            var polygon = new ArrayList<double[]>(4);
            polygon.add(p0);
            polygon.add(p1);
            if (r1 > EPS) {
                polygon.add(f.at(a1, r1, z1));
                polygon.add(f.at(a0, r1, z1));
            } else polygon.add(f.along(z1));
            if (r0 <= EPS) polygon.remove(1);
            if (polygon.size() < 3) continue;
            add(polygon, f.along((z0 + z1) / 2), shading, material, around, along);
        }
    }

    /**
     * The side of a frustum as {@link #frustum} draws it from {@code z0} to {@code z1} (z0 &lt; z1), its sprite stretched
     * once round it and once along it (a pump's finned bearing housing, a bolted joint's rim): a baked sprite of the whole
     * part, never cut where a tiled one would cross a tile.
     */
    void band(Frame f, double r0, double r1, double z0, double z1, int n, Material material) {
        n = detail.sides(n);
        double chord = 2 * Math.max(r0, r1) * DeterministicMath.sin(Math.PI / n), slope = (r0 - r1) / Math.max(EPS, z1 - z0);
        Shading shading = r0 == r1 ? new Radial(f.origin, f.axis) : new Cone(f.origin, f.axis, slope);
        var along = new Uv(f.along(z0), f.axis, 0, z1 - z0);
        double rWide = Math.max(r0, r1), zWide = r0 >= r1 ? z0 : z1;
        for (int i = 0; i < n; i++) {
            double a0 = 2 * Math.PI * i / n, a1 = 2 * Math.PI * (i + 1) / n;
            var w0 = f.at(a0, rWide, zWide);
            var around = new Uv(w0, unit(sub(f.at(a1, rWide, zWide), w0)), i * chord, n * chord);
            add(List.of(f.at(a0, r0, z0), f.at(a1, r0, z0), f.at(a1, r1, z1), f.at(a0, r1, z1)), f.along((z0 + z1) / 2), shading, material, around, along);
        }
    }

    /**
     * A flat disc of {@code n} corners and radius {@code r} at frame station {@code z}, facing along the axis
     * ({@code sign} +1) or against it, its sprite stretched once across it (a bolted cover, a flange's back): one polygon.
     */
    void disc(Frame f, double r, double z, int n, int sign, Material material) {
        n = detail.sides(n);
        if (material == Material.PUMP_DISC && detail == RenderDetail.NORMAL) {
            // Keep the previous cover envelope: relief sits in a shallow recess, its bolt ends on the old face.
            double recess = Math.min(.025, .05 * r);
            disc(f, r, z - sign * recess, n, sign, Material.PUMP_COVER);
            bore(f, .96 * r, Math.min(z, z - sign * recess), Math.max(z, z - sign * recess), n, Material.PUMP_CASTING);
            annulus(f, .96 * r, r, z, n, sign, Material.PUMP_CASTING);
            var outward = new double[]{sign * f.axis[0], sign * f.axis[1], sign * f.axis[2]};
            for (int k = 0; k < 12; k++) cylinder(new Frame(f.at(2 * Math.PI * (k + .5) / 12, .82 * r, z - sign * recess), outward, f.ref), .075 * r, 0, recess, 6, Material.BOLT);
            return;
        }
        var polygon = new ArrayList<double[]>(n);
        for (int i = 0; i < n; i++) polygon.add(f.at(2 * Math.PI * i / n, r, z));
        var centre = f.along(z);
        add(polygon, new double[]{centre[0] - sign * f.axis[0], centre[1] - sign * f.axis[1], centre[2] - sign * f.axis[2]}, FLAT, material,
                new Uv(centre, f.ref, r, 2 * r), new Uv(centre, f.side(), r, 2 * r));
    }

    /** The inside of a tube (seen from its axis), radius {@code r}, from {@code z0} to {@code z1}. */
    void bore(Frame f, double r, double z0, double z1, int n, Material material) {
        n = detail.sides(n);
        double chord = 2 * r * DeterministicMath.sin(Math.PI / n);
        var along = new Uv(f.along(z0), f.axis, 0, material.period());
        for (int i = 0; i < n; i++) {
            double a0 = 2 * Math.PI * i / n, a1 = 2 * Math.PI * (i + 1) / n;
            var p0 = f.at(a0, r, z0);
            var p1 = f.at(a1, r, z0);
            var around = new Uv(p0, unit(sub(p1, p0)), i * chord, material.period());
            var middle = f.at((a0 + a1) / 2, 2 * r, (z0 + z1) / 2);
            add(List.of(p0, p1, f.at(a1, r, z1), f.at(a0, r, z1)), middle, new Inward(f.origin, f.axis), material, around, along);
        }
    }

    /** A flat ring from {@code rIn} to {@code rOut} at {@code z}, facing along the axis ({@code sign} +1) or against it (-1). */
    void annulus(Frame f, double rIn, double rOut, double z, int n, double sign, Material material) {
        n = detail.sides(n);
        var outward = new double[]{sign * f.axis[0], sign * f.axis[1], sign * f.axis[2]};
        if (rIn <= EPS) {
            var polygon = new ArrayList<double[]>(n);
            for (int i = 0; i < n; i++) polygon.add(f.at(2 * Math.PI * i / n, rOut, z));
            flat(polygon, outward, material);
            return;
        }
        for (int i = 0; i < n; i++) {
            double a0 = 2 * Math.PI * i / n, a1 = 2 * Math.PI * (i + 1) / n;
            flat(List.of(f.at(a0, rIn, z), f.at(a1, rIn, z), f.at(a1, rOut, z), f.at(a0, rOut, z)), outward, material);
        }
    }

    /** The smallest nut drawn, across its flats: half a texel, so small flanges still show their bolts. */
    static final double MIN_NUT = 1 / 32.0;

    /**
     * The nuts on one side of a bolted flange (owner request 2026-10-16: "add bolts to all flanges") on frame {@code f}:
     * the shared visible count of a line of outside diameter {@code od} ({@link PipeSizes#drawnBolts}), each nut 1.6 bolt diameters
     * across its flats and, with its stud's end, 1.2 d high, a closed hexagonal prism standing on the flange's surface at
     * {@code z} towards {@code sign} along the axis. They sit on a bolt circle half way from {@code inner} (the hub, the
     * neck or a cover's edge) to {@code outer} (the rim), straddling the frame's reference direction as flange bolt holes
     * do; a flange whose land is narrower than a nut shows none. The seat stays drawn: a large device's flange does not
     * cover it everywhere (LineGeometryTest found its inside seen from outside without it).
     */
    void bolts(Frame f, double od, double inner, double outer, double z, int sign, Material material) {
        bolts(f, od, inner, outer, z, sign, PipeSizes.drawnBolts(od), material);
    }

    /** The same bolt geometry with an explicit visual count for large equipment covers. */
    void bolts(Frame f, double od, double inner, double outer, double z, int sign, int count, Material material) {
        double d = PipeSizes.boltDiameter(od), across = Math.max(1.6 * d, MIN_NUT);
        if (outer - inner < across) return;
        double r = across / Math.sqrt(3), height = Math.max(1.2 * d, MIN_NUT), circle = (inner + outer) / 2;
        var axis = new double[]{sign * f.axis[0], sign * f.axis[1], sign * f.axis[2]};
        for (int k = 0; k < count; k++) {
            var nut = new Frame(f.at(2 * Math.PI * (k + .5) / count, circle, z), axis, f.ref);
            if (detail == RenderDetail.LOW) disc(nut, r, .0004, 6, 1, Material.BOLT_FACE);
            else cylinder(nut, r, 0, height, 6, material);
        }
    }

    /**
     * A 2:1 head from its tangent line at frame station {@code at} to its apex {@code depth} beyond, along the frame's
     * axis, drawn as bands of the ellipsoid (a unit's heads, a drum's boot).
     */
    void head(Frame f, double r, double at, double depth, int n, Material material) {
        head(f, r, at, depth, n, Math.max(3, n / 4), material);
    }

    /**
     * A 2:1 head as {@link #head(Frame, double, double, double, int, Material)} draws it, in {@code bands} bands of the
     * ellipsoid from its tangent line to its apex: a large vessel's heads keep their outline in fewer bands (the FCC's
     * a sixth of its sides, four at least).
     */
    void head(Frame f, double r, double at, double depth, int n, int bands, Material material) {
        n = detail.sides(n);
        bands = detail.bands(bands);
        var base = f.along(at);
        var spheroid = new Spheroid(base, f.axis(), r, depth);
        for (int k = 0; k < bands; k++) {
            double p0 = Math.PI / 2 * k / bands, p1 = Math.PI / 2 * (k + 1) / bands;
            double r0 = r * DeterministicMath.cos(p0), r1 = k + 1 == bands ? 0 : r * DeterministicMath.cos(p1);
            double z0 = at + depth * DeterministicMath.sin(p0), z1 = at + depth * DeterministicMath.sin(p1);
            for (int i = 0; i < n; i++) {
                double a0 = 2 * Math.PI * i / n, a1 = 2 * Math.PI * (i + 1) / n;
                var polygon = new ArrayList<double[]>(4);
                polygon.add(f.at(a0, r0, z0));
                polygon.add(f.at(a1, r0, z0));
                if (r1 > 0) {
                    polygon.add(f.at(a1, r1, z1));
                    polygon.add(f.at(a0, r1, z1));
                } else polygon.add(f.along(z1));
                add(polygon, base, spheroid, material, null, null);
            }
        }
    }

    /** A fastener rather than a dark cylindrical machine part; low detail uses its flush textured face. */
    void nut(Frame f, double radius, double from, double to, int sides, Material material) {
        if (detail == RenderDetail.LOW) disc(f, radius, Math.min(from, to) + .0004, 6, 1, Material.BOLT_FACE);
        else cylinder(f, radius, from, to, sides, material);
    }

    /** A closed cylinder (prism with both ends). */
    void cylinder(Frame f, double r, double z0, double z1, int n, Material material) {
        prism(f, r, z0, z1, n, material);
        annulus(f, 0, r, Math.min(z0, z1), n, -1, material);
        annulus(f, 0, r, Math.max(z0, z1), n, 1, material);
    }

    /**
     * A ring-shaped plate (flange, ring, collar): outer side, inner side when it has a bore, and both faces;
     * {@code faces} masks the faces drawn (1 the low face, 2 the high face).
     */
    void ring(Frame f, double rIn, double rOut, double z0, double z1, int n, int faces, Material material) {
        prism(f, rOut, z0, z1, n, material);
        if (rIn > EPS) bore(f, rIn, z0, z1, n, material);
        if ((faces & 1) != 0) annulus(f, rIn, rOut, Math.min(z0, z1), n, -1, material);
        if ((faces & 2) != 0) annulus(f, rIn, rOut, Math.max(z0, z1), n, 1, material);
    }

    /**
     * A hoop round a round body of radius {@code rIn} (a stiffening band, a riding ring, a stack's joint) on frame
     * {@code f} from {@code z0} to {@code z1}: its rim and both faces; its bore lies on the body and is not drawn.
     */
    void hoop(Frame f, double rIn, double rOut, double z0, double z1, int n, Material material) {
        prism(f, rOut, z0, z1, n, material);
        annulus(f, rIn, rOut, Math.min(z0, z1), n, -1, material);
        annulus(f, rIn, rOut, Math.max(z0, z1), n, 1, material);
    }

    /** An axis-aligned box in the local frame; {@code faces} masks its faces (bits -x, +x, -y, +y, -z, +z). */
    void box(double[] lo, double[] hi, int faces, Material material) {
        for (int axis = 0; axis < 3; axis++)
            for (int side = 0; side < 2; side++) {
                if ((faces & (1 << (2 * axis + side))) == 0) continue;
                int a = (axis + 1) % 3, b = (axis + 2) % 3;
                double c = side == 0 ? lo[axis] : hi[axis];
                var polygon = new ArrayList<double[]>(4);
                double[][] corners = {{lo[a], lo[b]}, {hi[a], lo[b]}, {hi[a], hi[b]}, {lo[a], hi[b]}};
                for (var corner : corners) {
                    var p = new double[3];
                    p[axis] = c;
                    p[a] = corner[0];
                    p[b] = corner[1];
                    polygon.add(p);
                }
                var outward = new double[3];
                outward[axis] = side == 0 ? -1 : 1;
                flat(polygon, outward, material);
            }
    }

    static final int ALL_FACES = 63;

    /** A box spanned by a centre and three half-extent vectors (orthogonal), every face drawn. */
    void block(double[] centre, double[] ex, double[] ey, double[] ez, Material material) {
        block(centre, ex, ey, ez, ALL_FACES, material);
    }

    /** A box spanned by a centre and three half-extent vectors; {@code faces} masks its faces (bits -ex, +ex, -ey, +ey, -ez, +ez). */
    void block(double[] centre, double[] ex, double[] ey, double[] ez, int faces, Material material) {
        double[][] e = {ex, ey, ez};
        for (int axis = 0; axis < 3; axis++)
            for (int sign = -1; sign <= 1; sign += 2) {
                if ((faces & (1 << (2 * axis + (sign + 1) / 2))) == 0) continue;
                double[] a = e[(axis + 1) % 3], b = e[(axis + 2) % 3], n = e[axis];
                double[] c = {centre[0] + sign * n[0], centre[1] + sign * n[1], centre[2] + sign * n[2]};
                var polygon = List.of(add3(c, -1, a, -1, b), add3(c, 1, a, -1, b), add3(c, 1, a, 1, b), add3(c, -1, a, 1, b));
                flat(polygon, new double[]{sign * n[0], sign * n[1], sign * n[2]}, material);
            }
    }

    /** A bar of square section {@code width} from {@code a} to {@code b} (handrails, rungs, cage bars), its ends drawn. */
    void bar(double[] a, double[] b, double width, double[] up, Material material) {
        bar(a, b, width, up, BOTH_ENDS, material);
    }

    /** Which ends of a {@link #bar} are drawn: none where both are buried in other parts, its start, its end, or both. */
    static final int NO_ENDS = 0, START = 1, END = 2, BOTH_ENDS = 3;

    /**
     * A bar whose {@code ends} ({@link #START}, {@link #END}) are drawn: an end buried in a rail, a deck or a hoop is never
     * seen, and leaving it out saves a third of a bar's quads (rungs, posts and cage bars are most of a column's).
     */
    void bar(double[] a, double[] b, double width, double[] up, int ends, Material material) {
        var axis = sub(b, a);
        double length = Math.sqrt(dot(axis, axis));
        if (length < EPS) return;
        var dir = new double[]{axis[0] / length, axis[1] / length, axis[2] / length};
        var side = cross(dir, up);
        if (dot(side, side) < EPS) side = cross(dir, Math.abs(dir[0]) < .9 ? new double[]{1, 0, 0} : new double[]{0, 1, 0});
        side = unit(side);
        var normal = cross(side, dir);
        double h = width / 2;
        block(new double[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2}, scale(dir, length / 2), scale(side, h), scale(normal, h), ALL_FACES & ~3 | ends, material);
    }

    /**
     * A bar of square section {@code width} along the polyline {@code points} (handrails along a deck's edge, a cage's
     * hoops), its section turned about {@code up} as {@link #bar}'s. Where it bends its faces meet on the bisecting plane
     * (a mitre), so no end face is drawn there: two quads a bend fewer than a bar per segment, the same outline. A bend
     * sharper than {@link #MITRE_LIMIT}, or one that tilts the section, ends one bar and starts the next. {@code ends}
     * masks the polyline's first and last end faces ({@link #START}, {@link #END}).
     */
    void rail(List<double[]> points, double width, double[] up, int ends, Material material) {
        int m = points.size();
        if (m < 2) return;
        var dirs = new double[m - 1][];
        var sides = new double[m - 1][];
        var normals = new double[m - 1][];
        for (int k = 0; k + 1 < m; k++) {
            var axis = sub(points.get(k + 1), points.get(k));
            double length = Math.sqrt(dot(axis, axis));
            if (length < EPS) throw new IllegalArgumentException("Rail points " + k + " and " + (k + 1) + " coincide");
            dirs[k] = scale(axis, 1 / length);
            var side = cross(dirs[k], up);
            if (dot(side, side) < EPS) side = cross(dirs[k], Math.abs(dirs[k][0]) < .9 ? new double[]{1, 0, 0} : new double[]{0, 1, 0});
            sides[k] = unit(side);
            normals[k] = cross(sides[k], dirs[k]);
        }
        double h = width / 2;
        // The section's corner offsets (side sign a, normal sign b) at each point: a mitre where the bar bends smoothly.
        var offsets = new double[m][][];
        var joined = new boolean[m];
        for (int j = 0; j < m; j++) {
            int before = Math.max(0, j - 1), after = Math.min(m - 2, j);
            double[] side = sides[after], normal = normals[after];
            if (j > 0 && j < m - 1) {
                double bend = dot(sides[before], sides[after]);
                var tilt = sub(normals[before], normals[after]);
                joined[j] = bend > MITRE_LIMIT && dot(tilt, tilt) < EPS;
                if (joined[j]) side = scale(plus(sides[before], sides[after]), 1 / (1 + bend));
            }
            offsets[j] = new double[4][];
            for (int c = 0; c < 4; c++) {
                double a = c == 0 || c == 3 ? -h : h, b = c < 2 ? -h : h;
                offsets[j][c] = new double[]{a * side[0] + b * normal[0], a * side[1] + b * normal[1], a * side[2] + b * normal[2]};
            }
        }
        for (int k = 0; k + 1 < m; k++) {
            // A segment's corners: its own section where a bend is not joined.
            double[][] from = joined[k] || k == 0 ? offsets[k] : section(sides[k], normals[k], h);
            double[][] to = joined[k + 1] || k + 2 == m ? offsets[k + 1] : section(sides[k], normals[k], h);
            double[] p = points.get(k), q = points.get(k + 1);
            for (int c = 0; c < 4; c++) {
                int d = (c + 1) % 4;
                var outward = plus(from[c], from[d]);
                flat(List.of(plus(p, from[c]), plus(p, from[d]), plus(q, to[d]), plus(q, to[c])), outward, material);
            }
            boolean first = k == 0 ? (ends & START) != 0 : !joined[k], last = k + 2 == m ? (ends & END) != 0 : !joined[k + 1];
            if (first) flat(List.of(plus(p, from[0]), plus(p, from[1]), plus(p, from[2]), plus(p, from[3])), neg(dirs[k]), material);
            if (last) flat(List.of(plus(q, to[0]), plus(q, to[1]), plus(q, to[2]), plus(q, to[3])), dirs[k], material);
        }
    }

    /** The sharpest bend a {@link #rail} mitres, as the cosine between its sides: about 140 degrees. */
    static final double MITRE_LIMIT = -.75;

    /** A square section's corner offsets, as {@link #rail} orders them. */
    private static double[][] section(double[] side, double[] normal, double h) {
        var out = new double[4][];
        for (int c = 0; c < 4; c++) {
            double a = c == 0 || c == 3 ? -h : h, b = c < 2 ? -h : h;
            out[c] = new double[]{a * side[0] + b * normal[0], a * side[1] + b * normal[1], a * side[2] + b * normal[2]};
        }
        return out;
    }

    /** An elbow: a quarter (or any arc) of torus of bend radius {@code bend} and tube radius {@code r} from angle a0 to a1. */
    void torus(Frame f, double bend, double r, double a0, double a1, int segments, int n, Material material) {
        torus(f, bend, r, a0, a1, segments, n, 0, material);
    }

    /**
     * A torus whose section's vertices start {@code phase} round from the outward radial, towards the frame's axis: a
     * straight pipe whose facet reference lies at that angle meets it vertex to vertex.
     */
    void torus(Frame f, double bend, double r, double a0, double a1, int segments, int n, double phase, Material material) {
        n = detail.sides(n);
        var shading = new Torus(f.origin, f.axis, bend);
        double chord = 2 * r * DeterministicMath.sin(Math.PI / n);
        for (int s = 0; s < segments; s++) {
            double phi0 = a0 + (a1 - a0) * s / segments, phi1 = a0 + (a1 - a0) * (s + 1) / segments;
            for (int i = 0; i < n; i++) {
                double t0 = phase + 2 * Math.PI * i / n, t1 = phase + 2 * Math.PI * (i + 1) / n;
                var p00 = torusPoint(f, bend, r, phi0, t0);
                var p01 = torusPoint(f, bend, r, phi0, t1);
                var p11 = torusPoint(f, bend, r, phi1, t1);
                var p10 = torusPoint(f, bend, r, phi1, t0);
                var centre = torusPoint(f, bend, 0, (phi0 + phi1) / 2, 0);
                var around = new Uv(p00, unit(sub(p01, p00)), i * chord, material.period());
                var along = new Uv(p00, unit(sub(p10, p00)), bend * phi0, material.period());
                // Quads of a torus are planar only in the limit; split each into two triangles.
                add(List.of(p00, p01, p11), centre, shading, material, around, along);
                add(List.of(p00, p11, p10), centre, shading, material, around, along);
            }
        }
    }

    /**
     * A segmented bend (a mitred elbow, a ring header): a pipe of radius {@code r} along the arc of radius {@code bend}
     * round frame {@code f}'s origin, in the plane square to its axis, from angle {@code a0} to {@code a1} (from
     * {@code ref} towards {@code axis × ref}), turning {@code segments} times. An open bend's pieces lie along the arc's
     * tangents at a0, every step and a1 (fabricated mitre bends, ASME B31.3 304.2.3: the end pieces are half pieces along
     * the runs either side), so its ends are square to the pipe at the arc's ends and a straight run meets them flush;
     * where not {@code ends}, those runs carry on to the first and last mitres themselves (a pipe's legs) and the end
     * pieces are not drawn. A closed ring's pieces are its chords. Each piece runs on past its mitres by r tan(half the
     * turn), so the outside of the bend closes and the inside laps into the next piece. A piece's facets are quads, so it
     * costs half a {@link #torus}'s quads for the same outline.
     */
    void bend(Frame f, double bend, double r, double a0, double a1, int segments, int n, boolean ends, Material material) {
        double step = (a1 - a0) / segments, tan = Math.abs(DeterministicMath.sin(step / 2) / DeterministicMath.cos(step / 2)), lap = r * tan;
        if (Math.abs(a1 - a0) > 2 * Math.PI - 1e-9) {
            for (int k = 0; k < segments; k++) {
                var p0 = torusPoint(f, bend, 0, a0 + k * step, 0);
                var chord = sub(torusPoint(f, bend, 0, a0 + (k + 1) * step, 0), p0);
                double length = Math.sqrt(dot(chord, chord));
                if (length > EPS) prism(new Frame(p0, scale(chord, 1 / length), f.axis), r, -lap, length + lap, n, material);
            }
            return;
        }
        var side = f.side();
        double half = bend * tan, turn = Math.signum(step);
        for (int k = ends ? 0 : 1; k <= (ends ? segments : segments - 1); k++) {
            double t = a0 + k * step, c = DeterministicMath.cos(t), s = DeterministicMath.sin(t);
            double[] dir = {turn * (-s * f.ref[0] + c * side[0]), turn * (-s * f.ref[1] + c * side[1]), turn * (-s * f.ref[2] + c * side[2])};
            prism(new Frame(torusPoint(f, bend, 0, t, 0), dir, f.axis), r, k == 0 ? 0 : -half - lap, k == segments ? 0 : half + lap, n, material);
        }
    }

    private static double[] torusPoint(Frame f, double bend, double r, double phi, double theta) {
        double cp = DeterministicMath.cos(phi), sp = DeterministicMath.sin(phi), ct = DeterministicMath.cos(theta), st = DeterministicMath.sin(theta);
        var side = f.side();
        double radial = bend + r * ct;
        double[] dir = {cp * f.ref[0] + sp * side[0], cp * f.ref[1] + sp * side[1], cp * f.ref[2] + sp * side[2]};
        return new double[]{f.origin[0] + radial * dir[0] + r * st * f.axis[0], f.origin[1] + radial * dir[1] + r * st * f.axis[1],
                f.origin[2] + radial * dir[2] + r * st * f.axis[2]};
    }

    /** A sphere of {@code n} meridians and n/2 bands (a pipe junction's core, a cap). */
    void sphere(double[] centre, double r, int n, Material material) {
        n = detail.sides(n);
        var f = Frame.of(centre, 1, 1);
        int bands = detail == RenderDetail.LOW ? Math.min(12, Math.max(2, n / 2)) : Math.max(2, n / 2);
        var shading = new Sphere(centre);
        for (int k = 0; k < bands; k++) {
            double lat0 = -Math.PI / 2 + Math.PI * k / bands, lat1 = -Math.PI / 2 + Math.PI * (k + 1) / bands;
            double r0 = r * DeterministicMath.cos(lat0), r1 = r * DeterministicMath.cos(lat1), z0 = r * DeterministicMath.sin(lat0), z1 = r * DeterministicMath.sin(lat1);
            for (int i = 0; i < n; i++) {
                double a0 = 2 * Math.PI * i / n, a1 = 2 * Math.PI * (i + 1) / n;
                List<double[]> polygon = k == 0 ? List.of(f.along(z0), f.at(a0, r1, z1), f.at(a1, r1, z1))
                        : k == bands - 1 ? List.of(f.at(a0, r0, z0), f.at(a1, r0, z0), f.along(z1))
                        : List.of(f.at(a0, r0, z0), f.at(a1, r0, z0), f.at(a1, r1, z1), f.at(a0, r1, z1));
                add(polygon, centre, shading, material, null, null);
            }
        }
    }

    // ---- Cutting into cells -------------------------------------------------------------------------------------

    /** The polygons of cell (x, y, z), split where a texture wraps, with smooth normals; all facets considered. */
    public List<Polygon> cell(int x, int y, int z) {
        var out = new ArrayList<Polygon>();
        for (var facet : facets) cell(facet, x, y, z, out);
        return out;
    }

    void cell(Facet facet, int x, int y, int z, List<Polygon> out) {
        if (facet.max[0] < x - EPS || facet.min[0] > x + 1 + EPS || facet.max[1] < y - EPS || facet.min[1] > y + 1 + EPS
                || facet.max[2] < z - EPS || facet.min[2] > z + 1 + EPS) return;
        var clipped = piece(facet, x, y, z);
        if (clipped == null) return;
        for (var across : tiles(clipped, facet.u))
            for (var along : tiles(across.polygon, facet.v))
                out.add(polygon(along.polygon, facet, x, y, z, across.index, along.index));
    }

    /** Total area of the facets, m². */
    double area() {
        double total = 0;
        for (var facet : facets) total += area(facet.positions);
        return total;
    }

    /**
     * The facet clipped to the cell, or null when it covers no area there. A facet lying in a cell face belongs to the
     * cell on its inner side.
     */
    static List<double[]> piece(Facet facet, int x, int y, int z) {
        var p = facet.positions;
        p = clip(p, new double[]{1, 0, 0}, -x);
        p = clip(p, new double[]{-1, 0, 0}, x + 1);
        p = clip(p, new double[]{0, 1, 0}, -y);
        p = clip(p, new double[]{0, -1, 0}, y + 1);
        p = clip(p, new double[]{0, 0, 1}, -z);
        p = clip(p, new double[]{0, 0, -1}, z + 1);
        if (p.size() < 3 || area(p) < MIN_AREA) return null;
        double cx = 0, cy = 0, cz = 0;
        for (var v : p) {
            cx += v[0];
            cy += v[1];
            cz += v[2];
        }
        cx = cx / p.size() - facet.normal[0] * OWNER_NUDGE;
        cy = cy / p.size() - facet.normal[1] * OWNER_NUDGE;
        cz = cz / p.size() - facet.normal[2] * OWNER_NUDGE;
        return cx >= x && cx < x + 1 && cy >= y && cy < y + 1 && cz >= z && cz < z + 1 ? p : null;
    }

    private record Tile(List<double[]> polygon, int index) {
    }

    /** The polygon cut where a texture coordinate crosses whole tiles. */
    private static List<Tile> tiles(List<double[]> polygon, Uv uv) {
        double low = Double.POSITIVE_INFINITY, high = Double.NEGATIVE_INFINITY;
        for (var p : polygon) {
            double t = uv.at(p);
            low = Math.min(low, t);
            high = Math.max(high, t);
        }
        int first = floor(low + EPS), last = ceil(high - EPS);
        if (last <= first + 1) return List.of(new Tile(polygon, first));
        var out = new ArrayList<Tile>(last - first);
        double[] c = {uv.axis[0] / uv.period, uv.axis[1] / uv.period, uv.axis[2] / uv.period};
        double base = (uv.offset - dot(uv.origin, uv.axis)) / uv.period; // uv.at(p) = dot(c, p) + base
        for (int k = first; k < last; k++) {
            var tile = clip(clip(polygon, c, base - k), neg(c), k + 1 - base);
            if (tile.size() >= 3 && area(tile) > 1e-12) out.add(new Tile(tile, k));
        }
        return out;
    }

    /** Drawing polygons in world-local coordinates, split only at texture repeats. */
    static List<Polygon> tiled(Facet facet) {
        var out = new ArrayList<Polygon>();
        for (var across : tiles(facet.positions(), facet.u)) for (var along : tiles(across.polygon, facet.v))
            out.add(polygon(along.polygon, facet, 0, 0, 0, across.index, along.index, false));
        return out;
    }

    /** Cell-local polygon with texture coordinates in tile ({@code tileU}, {@code tileV}) of its sprite, and smooth normals. */
    private static Polygon polygon(List<double[]> clipped, Facet facet, int x, int y, int z, int tileU, int tileV) {
        return polygon(clipped, facet, x, y, z, tileU, tileV, true);
    }

    private static Polygon polygon(List<double[]> clipped, Facet facet, int x, int y, int z, int tileU, int tileV, boolean cellLocal) {
        int m = clipped.size();
        var positions = new double[m * 3];
        var uvs = new double[m * 2];
        var normals = new double[m * 3];
        int[] cell = {x, y, z};
        for (int i = 0; i < m; i++) {
            var p = clipped.get(i);
            for (int a = 0; a < 3; a++) positions[3 * i + a] = cellLocal ? clamp(p[a] - cell[a]) : p[a];
            // The sprite's top row is the far end of the tile along v.
            uvs[2 * i] = clamp(facet.u.at(p) - tileU);
            uvs[2 * i + 1] = 1 - clamp(facet.v.at(p) - tileV);
            var n = facet.shading.at(p);
            if (n == null || dot(n, n) < EPS * EPS || dot(n, facet.normal) <= 0) n = facet.normal;
            System.arraycopy(unit(n), 0, normals, 3 * i, 3);
        }
        return new Polygon(positions, uvs, normals, facet.material);
    }

    /**
     * A convex polygon minus a convex hole in its plane, as convex pieces: for each hole edge in turn, the part outside
     * that edge but inside the edges before it. What remains at the end is the overlap, dropped.
     */
    static List<List<double[]>> subtract(List<double[]> polygon, List<double[]> hole, double[] normal) {
        var pieces = new ArrayList<List<double[]>>();
        var centre = new double[3];
        for (var p : hole)
            for (int a = 0; a < 3; a++) centre[a] += p[a] / hole.size();
        var remaining = polygon;
        for (int i = 0; i < hole.size() && remaining.size() >= 3; i++) {
            double[] a = hole.get(i), b = hole.get((i + 1) % hole.size());
            var c = cross(sub(b, a), normal);
            if (dot(c, c) < EPS * EPS) continue;
            double d = -dot(c, a);
            if (dot(c, centre) + d > 0) {
                c = neg(c);
                d = -d;
            }
            var outside = clip(remaining, c, d);
            if (outside.size() >= 3 && area(outside) > MIN_AREA) pieces.add(outside);
            remaining = clip(remaining, neg(c), -d);
        }
        return pieces;
    }

    // ---- Vector arithmetic ---------------------------------------------------------------------------------------

    /** Sutherland-Hodgman: keeps the part of a convex polygon where dot(c, p) + d >= 0. */
    static List<double[]> clip(List<double[]> polygon, double[] c, double d) {
        var out = new ArrayList<double[]>(polygon.size() + 2);
        for (int i = 0; i < polygon.size(); i++) {
            double[] p = polygon.get(i), q = polygon.get((i + 1) % polygon.size());
            double fp = dot(c, p) + d, fq = dot(c, q) + d;
            boolean pIn = fp >= -EPS, qIn = fq >= -EPS;
            if (pIn) out.add(p);
            if (pIn != qIn) {
                double t = fp / (fp - fq);
                out.add(new double[]{p[0] + t * (q[0] - p[0]), p[1] + t * (q[1] - p[1]), p[2] + t * (q[2] - p[2])});
            }
        }
        return out;
    }

    static double[] newell(List<double[]> polygon) {
        double nx = 0, ny = 0, nz = 0;
        for (int i = 0; i < polygon.size(); i++) {
            double[] p = polygon.get(i), q = polygon.get((i + 1) % polygon.size());
            nx += (p[1] - q[1]) * (p[2] + q[2]);
            ny += (p[2] - q[2]) * (p[0] + q[0]);
            nz += (p[0] - q[0]) * (p[1] + q[1]);
        }
        return new double[]{nx, ny, nz};
    }

    static double area(List<double[]> polygon) {
        var n = newell(polygon);
        return .5 * Math.sqrt(dot(n, n));
    }

    static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    static double[] neg(double[] a) {
        return new double[]{-a[0], -a[1], -a[2]};
    }

    static double[] plus(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    static double[] scale(double[] a, double s) {
        return new double[]{a[0] * s, a[1] * s, a[2] * s};
    }

    static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    static double[] unit(double[] a) {
        double length = Math.sqrt(dot(a, a));
        return new double[]{a[0] / length, a[1] / length, a[2] / length};
    }

    static double[] unit(double x, double y, double z) {
        return unit(new double[]{x, y, z});
    }

    static double[] lerp(double[] a, double[] b, double t) {
        return new double[]{a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]), a[2] + t * (b[2] - a[2])};
    }

    private static double[] add3(double[] c, double sa, double[] a, double sb, double[] b) {
        return new double[]{c[0] + sa * a[0] + sb * b[0], c[1] + sa * a[1] + sb * b[1], c[2] + sa * a[2] + sb * b[2]};
    }

    static int floor(double v) {
        return (int) Math.floor(v);
    }

    static int ceil(double v) {
        return (int) Math.ceil(v);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
