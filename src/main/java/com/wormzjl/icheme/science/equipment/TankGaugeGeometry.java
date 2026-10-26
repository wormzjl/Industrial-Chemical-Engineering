package com.wormzjl.icheme.science.equipment;

import java.util.List;

/** Shared proportions and outward-wound faces for the tank's shell-mounted level indicator. */
public final class TankGaugeGeometry {
    public static final double CHAMBER_RADIUS = .20, BAND_HALF = .12, SCALE_HALF = .26;
    /** Clear sleeve between the liquid and the external collar: 3 mm liquid gap, 2 mm collar gap. */
    public static final double GLASS_HALF = .123, END_HEIGHT = .12;
    public static final double MARKER_HALF = .16, MARKER_HEIGHT = .07;
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
        if(shape.kind()!=EquipmentShape.Kind.TANK)throw new IllegalArgumentException("A level gauge requires a floating-roof tank");
        var plan=TankDesign.Plan.of(shape.diameter(),(int)shape.tangentLength());var gauge=plan.gauge();
        double offset=gauge.x()-plan.a(),shell=plan.a()-Math.sqrt(plan.r()*plan.r()-offset*offset);
        double[] min={gauge.x()-SCALE_HALF,0,gauge.z()-CHAMBER_RADIUS-.03},max={gauge.x()+SCALE_HALF,plan.fill()+.6,shell+.04};
        double[] bounds={Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
        var transform=shape.transform();
        for(int bits=0;bits<8;bits++){
            var point=transform.point(new double[]{(bits&1)==0?min[0]:max[0],(bits&2)==0?min[1]:max[1],(bits&4)==0?min[2]:max[2]});
            for(int axis=0;axis<3;axis++){bounds[axis]=Math.min(bounds[axis],point[axis]);bounds[axis+3]=Math.max(bounds[axis+3],point[axis]);}
        }
        return bounds;
    }

    /** Four continuous panes, with one full sprite per face rather than metre-tiled glass borders. */
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
