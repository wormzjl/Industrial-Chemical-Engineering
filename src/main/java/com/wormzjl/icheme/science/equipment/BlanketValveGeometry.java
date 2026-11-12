package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;

/**
 * A drum's gas blanketing valve (batch 2026-11-11-gas-blanketing; owner: an optional accessory "for vertical & horizontal
 * drums", "connected to a gas source through piping, vent to atmosphere", "placed on top of drums"; of the first drawing,
 * "this location is not good and it's too big"): a self-acting pressure regulator on the drum's first reserved top flange
 * ({@link EquipmentInstruments}, reservation {@code F1}).
 *
 * <p>Its place, in the equipment's box frame: a slim nozzle at the flange's place, moved to the nearest cell centre, up to
 * the first cell boundary that leaves room for the valve: on a drum that is where its own top nozzles end, so the valve
 * stands no taller than they do. The angle body sits half way up that nozzle, its diaphragm actuator lying to one side, a
 * vent stub with a rain cap on the other. The gas inlet is the nozzle's flange at that boundary, facing up: the supply pipe
 * stands in the cell above, as on every top nozzle. The accessory has no cells: it is drawn, and the drum's fluid
 * registration takes the inlet as a port ({@link #cell}, {@link #face}). Where less than the valve's height is left under
 * that boundary, the valve is smaller; a drum whose roof holds only its own nozzle's cell (a standing drum 1.5 m across)
 * takes no valve.</p>
 *
 * <p>On a short lying drum that pipe cell stands beside the rear nozzle's (nozzle pipes may touch, owner 2026-11-10): a
 * laid line keeps its own joints there, a pipe placed by hand joins the pipe beside it. The other inlets are worse: one out
 * to the side needs the body at a cell row's centre above the drum's box, half a metre taller; one out over the head needs
 * the cell behind the head, which is the drum's own on a short drum.</p>
 *
 * @param foot   the nozzle's foot on the drum: its axis ({@code x}, {@code z}) and the flange's height
 * @param centre the body's height
 * @param seat   the inlet flange's face: the top of {@link #cell}
 * @param side   the direction along x the actuator lies to, 1 or -1 (away from the second flange where it stands beside)
 * @param cell   the cell whose top face is the inlet
 * @param scale  the valve's size against its largest
 */
public record BlanketValveGeometry(double[] foot, double centre, double seat, int side, int[] cell, double scale) {
    /** The valve's height at full size, from the drum's flange to the inlet's face (the body between two flanges), and its least size. */
    private static final double HEIGHT = .31, LEAST = .6;

    public BlanketValveGeometry {
        foot = foot.clone();
        cell = cell.clone();
    }

    /** The valve's place on {@code shape}, or null where the shape reserves no flange for it (every kind but a drum). */
    public static BlanketValveGeometry of(EquipmentShape shape) {
        if (shape.kind() == EquipmentShape.Kind.GAS_CONVERTER) {
            // A gas converter's valve stands on its process vessel's head beside the apex (batch 2026-11-12-fluid-conversion):
            // the drum's valve, its foot on the head, none where the head is too small for it.
            var frame = ConverterDesign.blanketFoot(shape.diameter(), shape.tangentLength());
            if (frame == null) return null;
            var foot = shape.transform().point(frame);
            double seat = Math.ceil(foot[1] + LEAST * HEIGHT);
            int[] cell = {(int) Math.floor(foot[0]), (int) seat - 1, (int) Math.floor(foot[2])};
            for (var port : ShapeRasterizer.ports(shape)) if (port.dy() == 1 && port.cell().x() == cell[0] && port.cell().z() == cell[2]) return null;
            return new BlanketValveGeometry(foot, .5 * (foot[1] + seat), seat, 1, cell, Math.min(1, (seat - foot[1]) / HEIGHT));
        }
        if (shape.kind() != EquipmentShape.Kind.DRUM) return null;
        EquipmentInstruments.Reservation own = null, other = null;
        for (var r : EquipmentInstruments.of(shape).reserved()) {
            if (r.id().equals("F1")) own = r;
            else if (r.id().equals("F2")) other = r;
        }
        if (own == null) return null;
        double[] lo = own.low(), hi = own.high();
        double[] foot = {Math.floor((lo[0] + hi[0]) / 2) + .5, lo[1], Math.floor((lo[2] + hi[2]) / 2) + .5};
        double seat = Math.ceil(foot[1] + LEAST * HEIGHT);
        // Beside the second flange (a standing drum's roof), the actuator lies away from it.
        int side = other != null && (other.low()[0] + other.high()[0]) / 2 > foot[0] + .5 ? -1 : 1;
        int[] cell = {(int) Math.floor(foot[0]), (int) seat - 1, (int) Math.floor(foot[2])};
        // The cell of a top nozzle is that nozzle's.
        for (var port : ShapeRasterizer.ports(shape)) if (port.dy() == 1 && port.cell().x() == cell[0] && port.cell().z() == cell[2]) return null;
        double scale = Math.min(Math.clamp(hi[0] - lo[0], LEAST, 1), (seat - foot[1]) / HEIGHT);
        return new BlanketValveGeometry(foot, .5 * (foot[1] + seat), seat, side, cell, scale);
    }

    /** The direction out of {@link #cell} the gas inlet faces, as a unit step: up. */
    public int[] face() {
        return new int[] {0, 1, 0};
    }

    /** The accessory's bounds in the box frame, {@code minX, minY, minZ, maxX, maxY, maxZ}: selection, clearance and hover. */
    public double[] bounds() {
        double k = scale, far = .43 * k, near = .3 * k, r = .23 * k;
        return new double[] {
            foot[0] - (side > 0 ? near : far), foot[1], foot[2] - r, foot[0] + (side > 0 ? far : near), seat, foot[2] + r
        };
    }

    /** Draws the valve into {@code mesh}, in the box frame. */
    void build(Mesh mesh) {
        double k = scale, pipe = .02 + .05 * k;
        var up = Frame.of(new double[] {foot[0], 0, foot[2]}, 1, 1);
        // The nozzle from under the drum's skin to the inlet's face, its flange on the drum and the inlet flange.
        mesh.cylinder(up, pipe, foot[1] - .45, seat, 12, Material.PIPE);
        mesh.cylinder(up, pipe + .05 * k, foot[1], foot[1] + .04 * k, 16, Material.FLANGE);
        mesh.cylinder(up, pipe + .06 * k, seat - .05 * k, seat, 16, Material.FLANGE);
        // The body, and the diaphragm actuator lying to its side: bonnet, two dished cases on a bolted rim, spring case.
        double[] body = {foot[0], centre, foot[2]};
        mesh.sphere(body, .11 * k, 12, Material.MACHINE);
        var out = Frame.of(body, 0, side);
        mesh.cylinder(out, .045 * k, .09 * k, .2 * k, 12, Material.MACHINE);
        mesh.frustum(out, .08 * k, .2 * k, .2 * k, .24 * k, 16, Material.ACTUATOR);
        mesh.cylinder(out, .215 * k, .24 * k, .27 * k, 16, Material.FLANGE);
        mesh.frustum(out, .2 * k, .07 * k, .27 * k, .31 * k, 16, Material.ACTUATOR);
        mesh.cylinder(out, .06 * k, .31 * k, .41 * k, 12, Material.ACTUATOR);
        mesh.cylinder(out, .035 * k, .41 * k, .43 * k, 12, Material.FLANGE);
        // The vent: a stub on the other side, turned down under a rain cap.
        var back = Frame.of(body, 0, -side);
        mesh.cylinder(back, .035 * k, 0, .22 * k, 10, Material.PIPE);
        double[] vent = body.clone();
        vent[0] -= side * .22 * k;
        var down = Frame.of(vent, 1, -1);
        mesh.cylinder(down, .035 * k, -.035 * k, .08 * k, 10, Material.PIPE);
        mesh.frustum(down, .035 * k, .08 * k, .08 * k, .11 * k, 12, Material.FLANGE);
    }
}
