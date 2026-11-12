package com.wormzjl.icheme.science.equipment;

import java.util.List;

/** Shared proportions and outward-wound faces for the tank's shell-mounted level indicator. */
public final class TankGaugeGeometry {
    public static final double CHAMBER_RADIUS = .10, BAND_HALF = .06, SCALE_HALF = .13;
    /** Clear sleeve between the liquid and the external collar: 3 mm liquid gap, 2 mm collar gap. */
    public static final double GLASS_HALF = .063, END_HEIGHT = .12;
    public static final double MARKER_HALF = .075, MARKER_HEIGHT = .07;
    /** The collar slips around the liquid/glass column with 5 mm clearance. */
    public static final double MARKER_INNER_HALF = BAND_HALF + .005;
    private static final List<Mesh.Polygon> BAND_FACES;
    private static final List<Mesh.Polygon> MARKER_FACES;

    static {
        var mesh = new Mesh();
        mesh.box(new double[]{0, 0, 0}, new double[]{1, 1, 1}, Mesh.ALL_FACES, Mesh.Material.OPENING);
        BAND_FACES = List.copyOf(mesh.cell(0, 0, 0));
        // A collar has a hole: its end faces must never cover the liquid cap, even at a clipped gauge limit.
        var collar = new Mesh();
        double inset = (1 - MARKER_INNER_HALF / MARKER_HALF) / 2;
        collar.box(new double[]{0, 0, 0}, new double[]{1, 1, inset}, Mesh.ALL_FACES, Mesh.Material.OPENING);
        collar.box(new double[]{0, 0, 1 - inset}, new double[]{1, 1, 1}, Mesh.ALL_FACES, Mesh.Material.OPENING);
        collar.box(new double[]{0, 0, inset}, new double[]{inset, 1, 1 - inset}, Mesh.ALL_FACES, Mesh.Material.OPENING);
        collar.box(new double[]{1 - inset, 0, inset}, new double[]{1, 1, 1 - inset}, Mesh.ALL_FACES, Mesh.Material.OPENING);
        MARKER_FACES = List.copyOf(collar.cell(0, 0, 0));
    }

    private TankGaugeGeometry() {}

    /** Bounds in the equipment's box frame, available on a dedicated server without building any surfaces. */
    public static double[] bounds(EquipmentShape shape){
        var place=GaugePlace.of(shape);
        if(place==null)throw new IllegalArgumentException("A level gauge requires a floating-roof tank or a liquid converter");
        double offset=place.x()-place.cx(),shell=place.cz()-Math.sqrt(place.r()*place.r()-offset*offset);
        double[] min={place.x()-SCALE_HALF,place.grade(),place.z()-CHAMBER_RADIUS-.03},max={place.x()+SCALE_HALF,place.floor()+place.scale()+.6,shell+.04};
        double[] bounds={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        var transform=shape.transform();
        for(int bits=0;bits<8;bits++){
            var point=transform.point(new double[]{(bits&1)==0?min[0]:max[0],(bits&2)==0?min[1]:max[1],(bits&4)==0?min[2]:max[2]});
            for(int axis=0;axis<3;axis++){bounds[axis]=Math.min(bounds[axis],point[axis]);bounds[axis+3]=Math.max(bounds[axis+3],point[axis]);}
        }
        return bounds;
    }

    /** Four continuous panes, with one full sprite per face rather than metre-tiled glass borders. */
    /** The gauge's support clips' spacing up the column, m. */
    static final double CLIP = 3;
    private static final double[] UP = {0, 1, 0};

    /**
     * The level gauge at {@code p} (moved here from the floating-roof tank's design, batch 2026-11-12-fluid-conversion, so a
     * liquid converter takes the same accessory): a clear sleeve inside its guard rails, its drain down to grade and its vent
     * cap above; round equalizing pipes connect both end manifolds to the shell, with union fittings and support clips
     * between; the pale scale board behind it, ticked dark at each metre over the floor, the fifth ones across the whole board.
     */
    static void draw(Mesh mesh, GaugePlace p) {
        double x = p.x(), z = p.z(), low = p.low(), high = p.high(), flange = CHAMBER_RADIUS + .03;
        double[] out = p.out(), side = {-out[2], 0, out[0]};
        var column = Mesh.Frame.of(new double[]{x, 0, z}, 1, 1);
        double end = END_HEIGHT;
        // Only vertical glass faces: opaque end manifolds close the sleeve without coplanar transparent caps.
        sleeve(mesh, x, z, low, high);
        // Corner rails leave the front and sides clear; no opaque barrel hides the empty window or its liquid.
        double rail = BAND_HALF + .025;
        for (int sx : new int[]{-1, 1}) for (int sz : new int[]{-1, 1})
            mesh.bar(new double[]{x + sx * rail, low, z + sz * rail}, new double[]{x + sx * rail, high, z + sz * rail}, .011, out, Mesh.NO_ENDS, Mesh.Material.FLANGE);
        for (double[] cap : new double[][]{{low - end, low}, {high, high + end}})
            mesh.cylinder(column, flange, cap[0], cap[1], 12, Mesh.Material.FLANGE);
        mesh.cylinder(column, .025, p.grade(), low - end, 12, Mesh.Material.PIPE);
        mesh.cylinder(column, .025, high + end, high + .3, 12, Mesh.Material.PIPE);
        // Real round connections enter the end manifolds, outside the liquid/scale area, rather than stopping behind it.
        double offset = (x - p.cx()) * side[0] + (z - p.cz()) * side[2], radial = Math.sqrt(p.r() * p.r() - offset * offset);
        double[] shell = {p.cx() + offset * side[0] + radial * out[0], 0, p.cz() + offset * side[2] + radial * out[2]};
        for (double y : new double[]{low - end / 2, high + end / 2}) {
            var pipe = new Mesh.Frame(new double[]{shell[0], y, shell[2]}, out, UP);
            double length = (x - shell[0]) * out[0] + (z - shell[2]) * out[2], union = length - CHAMBER_RADIUS - .10;
            mesh.cylinder(pipe, .045, -.04, length, 12, Mesh.Material.PIPE);
            mesh.cylinder(pipe, .067, -.01, .055, 12, Mesh.Material.FLANGE);
            mesh.cylinder(pipe, .065, union - .05, union + .05, 12, Mesh.Material.FLANGE);
            mesh.cylinder(pipe, .075, union - .025, union + .025, 6, Mesh.Material.FLANGE);
        }
        for (double y = low + .6; y < high - .3; y += CLIP) {
            mesh.bar(new double[]{shell[0] + .02 * out[0], y, shell[2] + .02 * out[2]}, new double[]{x - CHAMBER_RADIUS * out[0], y, z - CHAMBER_RADIUS * out[2]},
                    .04, UP, Mesh.END, Mesh.Material.STEELWORK);
        }
        // The scale board just behind the chamber, and its ticks on its face either side of it.
        double[] face = {x - (CHAMBER_RADIUS - .02) * out[0], 0, z - (CHAMBER_RADIUS - .02) * out[2]};
        board(mesh, face, out, side, -SCALE_HALF, SCALE_HALF, low, p.floor() + p.scale() + .1, .04, Mesh.Material.GAUGE_SCALE);
        for (int m = 1; m <= p.scale() + 1e-9; m++) {
            boolean fifth = m % 5 == 0;
            double half = fifth ? .03 : .015, at = p.floor() + m;
            double[] proud = {face[0] + .01 * out[0], 0, face[2] + .01 * out[2]};
            double window = BAND_HALF + .045;
            board(mesh, proud, out, side, -SCALE_HALF, -window, at - half, at + half, .01, Mesh.Material.BOLT);
            if (fifth) board(mesh, proud, out, side, window, SCALE_HALF, at - half, at + half, .01, Mesh.Material.BOLT);
        }
    }

    /** An axis-aligned plate whose face is the plane through {@code face} facing {@code out}, {@code depth} thick behind it, from {@code s0} to {@code s1} along {@code side}, from {@code y0} to {@code y1}. */
    private static void board(Mesh mesh, double[] face, double[] out, double[] side, double s0, double s1, double y0, double y1, double depth, Mesh.Material material) {
        double[] a = {face[0] + s0 * side[0], y0, face[2] + s0 * side[2]}, b = {face[0] + s1 * side[0] - depth * out[0], y1, face[2] + s1 * side[2] - depth * out[2]};
        mesh.box(new double[]{Math.min(a[0], b[0]), y0, Math.min(a[2], b[2])}, new double[]{Math.max(a[0], b[0]), y1, Math.max(a[2], b[2])}, 63, material);
    }

    static void sleeve(Mesh mesh, double x, double z, double low, double high) {
        double[] lo = {x - GLASS_HALF, low, z - GLASS_HALF}, hi = {x + GLASS_HALF, high, z + GLASS_HALF};
        var vertical = new Mesh.Uv(lo, new double[]{0, 1, 0}, 0, high - low);
        for (int axis : new int[]{0, 2}) for (int sign : new int[]{0, 1}) {
            int across = 2 - axis;
            var corners = new java.util.ArrayList<double[]>();
            for (int[] corner : new int[][]{{0, 0}, {1, 0}, {1, 1}, {0, 1}}) {
                double[] p = lo.clone();p[axis] = sign == 0 ? lo[axis] : hi[axis];
                p[across] = corner[0] == 0 ? lo[across] : hi[across];p[1] = corner[1] == 0 ? low : high;
                corners.add(p);
            }
            double[] horizontal = new double[3];horizontal[across] = 1;
            mesh.add(corners, new double[]{x, (low + high) / 2, z}, new Mesh.Flat(), Mesh.Material.GLASS,
                    new Mesh.Uv(lo, horizontal, 0, 2 * GLASS_HALF), vertical);
        }
    }

    /** Read-only closed unit cube, 0..1 on every axis. Scale/translate copies for each band, including its underside. */
    public static List<Mesh.Polygon> bandFaces() { return BAND_FACES; }

    /** Read-only hollow collar in the same unit frame; its top/bottom leave the liquid surface clear. */
    public static List<Mesh.Polygon> markerFaces() { return MARKER_FACES; }
}
