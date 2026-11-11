package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import java.util.HashSet;
import java.util.List;

/**
 * A pressure safety valve (batch 2026-11-11-gas-blanketing, PRESSURE_SAFETY_VALVE_PLAN.md sections 4 and 5; owner: "Can be
 * installed on drums, and pipes"; "Outlet can be connected to pipes, release to atmosphere otherwise"; "these 2 should be
 * placed on top of drums, there are space intentionally left for them"; of the first blanketing valve, "it's too big"; of
 * this one, "connection should point to side"): a spring-loaded relief valve on a drum's second reserved top flange
 * ({@link EquipmentInstruments}, reservation {@code F2}), or on a pipe.
 *
 * <p>In the valve's own frame {@code up} leads from its inlet up the spring: from the drum's skin (or the pipe's wall)
 * toward the top of the cell it stands in. The inlet nozzle rises on the cell's centre line to the body; the bonnet with its
 * spring stands on the body, under a cap and a lifting lever; the outlet leaves the body sideways, along {@code side}, to
 * the cell's face, where its flange is the outlet. The discharge pipe stands in the next cell that way, or the valve
 * relieves to the atmosphere through the open flange. The accessory has no cells: it is drawn, and the host's fluid
 * registration takes the outlet as a port ({@link #cell}, {@link #face}).</p>
 *
 * <p>On a drum the valve stands no taller than the first cell boundary above its flange that leaves room for it, which is
 * where the drum's own top nozzles end; with less room it is smaller. Its outlet points away from the drum's middle, into
 * the first neighbouring cell that is no cell of the drum, of a nozzle or its pipe, or of the blanketing valve. A standing
 * drum 3 m across puts both reserved flanges in one cell: the safety valve takes the next free roof cell ({@link
 * EquipmentInstruments}); a drum with none, one whose flange stands in a top nozzle's cell or the blanketing valve's, and
 * one with no free cell beside the valve take no safety valve.</p>
 *
 * @param foot     the inlet's foot on the host, in the box frame (a pipe's: the block frame): the cell centre line's point at the flange
 * @param up       the spring's direction: the axis (0 x, 1 y, 2 z) and its sign
 * @param side     the outlet's direction: the axis and its sign
 * @param top      the coordinate along {@code up} the valve stays below
 * @param seat     the outlet face's coordinate along {@code side}
 * @param cell     the cell whose face (toward {@code side}) is the outlet
 * @param scale    the valve's size against its largest
 */
public record ReliefValveGeometry(double[] foot, int up, int upSign, int side, int sideSign, double top, double seat, int[] cell, double scale) {
    /** The valve's height at full size, from its flange to its cap, and its least size. */
    static final double HEIGHT = .46, LEAST = .6;

    public ReliefValveGeometry {
        foot = foot.clone();
        cell = cell.clone();
    }

    /** The valve's place on {@code shape}, or null where the shape takes none: every kind but a drum, and a drum without a free flange for it. */
    public static ReliefValveGeometry of(EquipmentShape shape) {
        return of(shape, -1, 0);
    }

    /**
     * The valve's place on {@code shape} with its outlet toward horizontal axis {@code axis} and {@code sign} (owner: "outlet
     * should face the user when placed"), or null where that cell is taken; {@code axis} -1: the default, away from the
     * drum's middle into the first free cell.
     */
    public static ReliefValveGeometry of(EquipmentShape shape, int axis, int sign) {
        if (axis == 1) throw new IllegalArgumentException("A safety valve's outlet points to the side");
        if (shape.kind() != EquipmentShape.Kind.DRUM) return null;
        EquipmentInstruments.Reservation own = null;
        for (var r : EquipmentInstruments.of(shape).reserved()) if (r.id().equals("F2")) own = r;
        if (own == null) return null;
        double[] lo = own.low(), hi = own.high();
        double[] foot = {Math.floor((lo[0] + hi[0]) / 2) + .5, lo[1], Math.floor((lo[2] + hi[2]) / 2) + .5};
        double top = Math.ceil(foot[1] + LEAST * HEIGHT);
        int[] cell = {(int) Math.floor(foot[0]), (int) top - 1, (int) Math.floor(foot[2])};
        // The cell of a top nozzle is that nozzle's; the blanketing valve's is its own.
        var taken = new HashSet<List<Integer>>();
        for (var port : ShapeRasterizer.ports(shape)) {
            taken.add(List.of(port.cell().x(), port.cell().y(), port.cell().z()));
            taken.add(List.of(port.cell().x() + port.dx(), port.cell().y() + port.dy(), port.cell().z() + port.dz()));
            if (port.dy() == 1 && port.cell().x() == cell[0] && port.cell().z() == cell[2]) return null;
        }
        var blanket = BlanketValveGeometry.of(shape);
        if (blanket != null && blanket.cell()[0] == cell[0] && blanket.cell()[2] == cell[2]) return null;
        if (blanket != null) for (int y = cell[1] - 1; y <= cell[1] + 1; y++) taken.add(List.of(blanket.cell()[0], y, blanket.cell()[2]));
        var layout = ShapeRasterizer.layout(shape);
        for (var cells : List.of(layout.shell(), layout.interior())) for (var c : cells) taken.add(List.of(c.x(), c.y(), c.z()));
        // The outlet points away from the drum's middle: the horizontal direction most outward first, into a free cell.
        double ox = foot[0] - shape.sizeX() / 2.0, oz = foot[2] - shape.sizeZ() / 2.0;
        int[][] ways = {{0, 1}, {0, -1}, {2, 1}, {2, -1}};
        java.util.Arrays.sort(ways, (a, b) -> Double.compare(outward(b, ox, oz), outward(a, ox, oz)));
        if (axis >= 0) ways = new int[][] {{axis, sign}};
        // Where the drum's crown fills every cell beside the valve's, the valve stands a cell higher on a longer inlet nozzle.
        for (int lift = 0; lift <= 1; lift++) {
            int[] at = {cell[0], cell[1] + lift, cell[2]};
            double scale = Math.min(Math.clamp(hi[0] - lo[0], LEAST, 1), (top + lift - foot[1]) / HEIGHT);
            for (var way : ways) {
                int[] next = at.clone();
                next[way[0]] += way[1];
                if (taken.contains(List.of(next[0], next[1], next[2]))) continue;
                return new ReliefValveGeometry(foot, 1, 1, way[0], way[1], top + lift, foot[way[0]] + way[1] * .5, at, scale);
            }
        }
        return null;
    }

    private static double outward(int[] way, double ox, double oz) {
        return way[1] * (way[0] == 0 ? ox : oz);
    }

    /**
     * The valve on a pipe of outside diameter {@code outside} in its block (the block frame, 0 to 1), its outlet out of the
     * block's face {@code face} (a horizontal axis and its sign, across the pipe's {@code axis}). It stands up from a lying
     * pipe's wall, and from a standing pipe's wall along the horizontal axis across its outlet; it is smaller where the pipe
     * leaves less than its height to the block's face.
     */
    public static ReliefValveGeometry onPipe(int face, int faceSign, int axis, double outside) {
        if (face == axis || face == 1) throw new IllegalArgumentException("A pipe's safety valve's outlet points to the side, across the pipe");
        int up = axis != 1 ? 1 : 2 - face;
        double[] foot = {.5, .5, .5};
        foot[up] += outside / 2;
        return new ReliefValveGeometry(foot, up, 1, face, faceSign, 1, faceSign > 0 ? 1 : 0, new int[3], Math.min(1, (1 - foot[up]) / HEIGHT));
    }

    /** The direction out of {@link #cell} the outlet faces, as a unit step. */
    public int[] face() {
        var f = new int[3];
        f[side] = sideSign;
        return f;
    }

    /** The other axis, across both {@link #up} and {@link #side}. */
    private int across() {
        return 3 - up - side;
    }

    /** The point {@code u} along up from the foot, {@code v} along the side, {@code w} across. */
    private double[] at(double u, double v, double w) {
        var p = foot.clone();
        p[up] += upSign * u;
        p[side] += sideSign * v;
        p[across()] += w;
        return p;
    }

    /** The body's centre's height above the foot: low on the inlet, or in the valve's cell where that stands higher. */
    private double body() {
        return Math.max(.18 * scale, Math.abs(top - foot[up]) - 1 + .25);
    }

    /** The outlet face's distance along the side from the centre line. */
    private double reach() {
        return Math.abs(seat - foot[side]);
    }

    /** The accessory's bounds, {@code minX, minY, minZ, maxX, maxY, maxZ}: selection, clearance and hover. */
    public double[] bounds() {
        double k = scale, r = .02 + .1 * k;
        double[] p = at(0, -.17 * k, -r), q = at(Math.min(Math.abs(top - foot[up]), body() + .28 * k), reach(), r);
        return new double[] {Math.min(p[0], q[0]), Math.min(p[1], q[1]), Math.min(p[2], q[2]), Math.max(p[0], q[0]), Math.max(p[1], q[1]), Math.max(p[2], q[2])};
    }

    /** Draws the valve into {@code mesh}, in the frame of {@link #foot}; {@code skin} is how far below the foot its inlet nozzle starts (into the host's wall). */
    void build(Mesh mesh, double skin) {
        double k = scale, pipe = .02 + .04 * k, body = body(), cap = Math.min(Math.abs(top - foot[up]) - .01 * k, body + .27 * k);
        // The inlet nozzle on the centre line, its flange on the host, up to the body.
        var inlet = Frame.of(at(0, 0, 0), up, upSign);
        mesh.cylinder(inlet, pipe, -skin, body, 12, Material.PIPE);
        mesh.cylinder(inlet, pipe + .05 * k, 0, .04 * k, 16, Material.FLANGE);
        mesh.cylinder(inlet, pipe + .04 * k, .08 * k, .1 * k, 16, Material.FLANGE);
        // The body, the bonnet with its spring, the cap and the lifting lever, which points away from the outlet.
        mesh.sphere(at(body, 0, 0), .09 * k, 12, Material.MACHINE);
        mesh.frustum(inlet, .07 * k, .05 * k, body + .05 * k, body + .24 * k, 14, Material.ACTUATOR);
        mesh.cylinder(inlet, .03 * k, body + .24 * k, cap, 10, Material.FLANGE);
        var lever = Frame.of(at(body + .2 * k, 0, 0), side, -sideSign);
        mesh.cylinder(lever, .008 + .006 * k, .04 * k, .17 * k, 6, Material.FLANGE);
        // The outlet sideways out of the body to the cell's face, its flange the outlet.
        var out = Frame.of(at(body, 0, 0), side, sideSign);
        double h = reach();
        mesh.cylinder(out, pipe + .01 * k, 0, h, 12, Material.PIPE);
        mesh.cylinder(out, pipe + .06 * k, h - .05 * k, h, 16, Material.FLANGE);
    }
}
