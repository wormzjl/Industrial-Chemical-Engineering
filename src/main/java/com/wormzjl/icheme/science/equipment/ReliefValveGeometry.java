package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;

/**
 * A pressure safety valve (batch 2026-11-11-gas-blanketing, PRESSURE_SAFETY_VALVE_PLAN.md sections 4 and 5; owner: "Can be
 * installed on drums, and pipes"; "Outlet can be connected to pipes, release to atmosphere otherwise"; "these 2 should be
 * placed on top of drums, there are space intentionally left for them"; of the first blanketing valve, "it's too big"): a
 * spring-loaded relief valve on a drum's second reserved top flange ({@link EquipmentInstruments}, reservation {@code F2}),
 * or on a pipe.
 *
 * <p>In the valve's own frame {@code up} leads from its inlet to its outlet: from the drum's skin (or the pipe's wall) to
 * the face of the cell it stands in. The inlet nozzle rises beside the cell's centre line to the body; the bonnet with its
 * spring stands on the body, under a cap and a lifting lever; the outlet leaves the body sideways to the cell's centre
 * line and turns up into a short tail pipe whose flange is the outlet, at that cell face. The discharge pipe stands in the
 * next cell, as on every top nozzle, or the valve relieves to the atmosphere through the open flange. The accessory has
 * no cells: it is drawn, and the host's fluid registration takes the outlet as a port ({@link #cell}, {@link #face}).</p>
 *
 * <p>On a drum the outlet face is the first cell boundary above the flange that leaves room for the valve, which is where
 * the drum's own top nozzles end, so the valve stands no taller than they do; with less room it is smaller. A standing
 * drum 3 m across puts both reserved flanges in one cell: the safety valve takes the next free roof cell ({@link
 * EquipmentInstruments}); a drum with none, and one whose flange stands in a top nozzle's cell or the blanketing valve's,
 * takes no safety valve.</p>
 *
 * @param foot  the inlet's foot on the host, in the box frame (a pipe's: the block frame): the cell centre line's point at the flange
 * @param up    the outlet's direction: the axis (0 x, 1 y, 2 z) and its sign
 * @param side  the axis the body stands beside the centre line along, and its sign
 * @param seat  the outlet face's coordinate along {@code up}'s axis
 * @param cell  the cell whose face (toward {@code up}) is the outlet
 * @param scale the valve's size against its largest
 */
public record ReliefValveGeometry(double[] foot, int up, int upSign, int side, int sideSign, double seat, int[] cell, double scale) {
    /** The valve's height at full size, from its flange to its outlet face, and its least size. */
    static final double HEIGHT = .46, LEAST = .6;

    public ReliefValveGeometry {
        foot = foot.clone();
        cell = cell.clone();
    }

    /** The valve's place on {@code shape}, or null where the shape takes none: every kind but a drum, and a drum without a free flange for it. */
    public static ReliefValveGeometry of(EquipmentShape shape) {
        if (shape.kind() != EquipmentShape.Kind.DRUM) return null;
        EquipmentInstruments.Reservation own = null;
        for (var r : EquipmentInstruments.of(shape).reserved()) if (r.id().equals("F2")) own = r;
        if (own == null) return null;
        double[] lo = own.low(), hi = own.high();
        double[] foot = {Math.floor((lo[0] + hi[0]) / 2) + .5, lo[1], Math.floor((lo[2] + hi[2]) / 2) + .5};
        double seat = Math.ceil(foot[1] + LEAST * HEIGHT);
        int[] cell = {(int) Math.floor(foot[0]), (int) seat - 1, (int) Math.floor(foot[2])};
        // The cell of a top nozzle is that nozzle's; the blanketing valve's is its own.
        for (var port : ShapeRasterizer.ports(shape)) if (port.dy() == 1 && port.cell().x() == cell[0] && port.cell().z() == cell[2]) return null;
        var blanket = BlanketValveGeometry.of(shape);
        if (blanket != null && blanket.cell()[0] == cell[0] && blanket.cell()[2] == cell[2]) return null;
        double scale = Math.min(Math.clamp(hi[0] - lo[0], LEAST, 1), (seat - foot[1]) / HEIGHT);
        // The body stands toward the drum's middle along x, away from the blanketing valve beside it on a standing drum's roof.
        int toward = blanket != null && blanket.foot()[0] > foot[0] ? -1 : 1;
        return new ReliefValveGeometry(foot, 1, 1, 0, toward, seat, cell, scale);
    }

    /**
     * The valve on a pipe of outside diameter {@code outside} in its block (the block frame, 0 to 1), its outlet out of the
     * block's face {@code face} (an axis and its sign), its body standing along the pipe's {@code axis}. Its foot is the
     * pipe's wall; it is smaller where the pipe leaves less than its height to the face.
     */
    public static ReliefValveGeometry onPipe(int face, int faceSign, int axis, double outside) {
        if (face == axis) throw new IllegalArgumentException("A pipe's safety valve stands across the pipe");
        double[] foot = {.5, .5, .5};
        foot[face] += faceSign * outside / 2;
        double seat = faceSign > 0 ? 1 : 0;
        double room = Math.abs(seat - foot[face]);
        return new ReliefValveGeometry(foot, face, faceSign, axis, 1, seat, new int[3], Math.min(1, room / HEIGHT));
    }

    /** The direction out of {@link #cell} the outlet faces, as a unit step. */
    public int[] face() {
        var f = new int[3];
        f[up] = upSign;
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

    /** The outlet face's distance along up from the foot. */
    private double rise() {
        return Math.abs(seat - foot[up]);
    }

    /** The accessory's bounds, {@code minX, minY, minZ, maxX, maxY, maxZ}: selection, clearance and hover. */
    public double[] bounds() {
        double k = scale, a = .14 * k;
        double[] p = at(0, -a - .15 * k, -.1 * k), q = at(rise(), .07 * k, .1 * k);
        return new double[] {Math.min(p[0], q[0]), Math.min(p[1], q[1]), Math.min(p[2], q[2]), Math.max(p[0], q[0]), Math.max(p[1], q[1]), Math.max(p[2], q[2])};
    }

    /** Draws the valve into {@code mesh}, in the frame of {@link #foot}; {@code skin} is how far below the foot its inlet nozzle starts (into the host's wall). */
    void build(Mesh mesh, double skin) {
        double k = scale, a = .14 * k, pipe = .02 + .04 * k, h = rise();
        double body = .1 * k + .08 * k;
        // The inlet nozzle beside the centre line, its flange on the host, up to the body.
        var inlet = Frame.of(at(0, -a, 0), up, upSign);
        mesh.cylinder(inlet, pipe, -skin, body, 12, Material.PIPE);
        mesh.cylinder(inlet, pipe + .05 * k, 0, .04 * k, 16, Material.FLANGE);
        mesh.cylinder(inlet, pipe + .04 * k, .08 * k, .1 * k, 16, Material.FLANGE);
        // The body, the bonnet with its spring, the cap and the lifting lever.
        mesh.sphere(at(body, -a, 0), .09 * k, 12, Material.MACHINE);
        mesh.frustum(inlet, .07 * k, .05 * k, body + .05 * k, body + .24 * k, 14, Material.ACTUATOR);
        mesh.cylinder(inlet, .03 * k, body + .24 * k, Math.min(h - .01 * k, body + .27 * k), 10, Material.FLANGE);
        var lever = Frame.of(at(body + .2 * k, -a, 0), side, -sideSign);
        mesh.cylinder(lever, .008 + .006 * k, .04 * k, .13 * k, 6, Material.FLANGE);
        // The outlet sideways to the centre line, the elbow, and the tail pipe up to the outlet flange at the cell face.
        var out = Frame.of(at(body, -a, 0), side, sideSign);
        mesh.cylinder(out, pipe + .01 * k, 0, a, 12, Material.PIPE);
        mesh.sphere(at(body, 0, 0), pipe + .01 * k, 12, Material.PIPE);
        var tail = Frame.of(at(0, 0, 0), up, upSign);
        mesh.cylinder(tail, pipe + .01 * k, body, h, 12, Material.PIPE);
        mesh.cylinder(tail, pipe + .06 * k, h - .05 * k, h, 16, Material.FLANGE);
    }
}
