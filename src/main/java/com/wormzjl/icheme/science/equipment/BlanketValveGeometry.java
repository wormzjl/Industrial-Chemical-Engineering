package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;

/**
 * A drum's gas blanketing valve (batch 2026-11-11-gas-blanketing; owner: an optional accessory "for vertical & horizontal
 * drums", "connected to a gas source through piping, vent to atmosphere", "placed on top of drums"): a self-acting pressure
 * regulator on the drum's first reserved top flange ({@link EquipmentInstruments}, reservation {@code F1}).
 *
 * <p>Its place, in the equipment's box frame: a nozzle stub from the drum to the cell boundary under the first cell row
 * clear of the flange, flanged there as the drum's own top nozzles are; the globe body at that row's centre under its
 * diaphragm actuator; the gas inlet level with the body, out to a block face on the
 * side away from the second flange (the safety valve's), so the two valves' pipes never share a cell face; a short vent
 * stub with a rain cap on the other side. The riser stands at the flange's place, moved along the drum to the nearest
 * cell centre, so the inlet's centre line runs through cell centres as every nozzle's does. The accessory has no cells: it
 * is drawn, and the drum's fluid registration takes the inlet as a port ({@link #cell}, {@link #face}).</p>
 */
public record BlanketValveGeometry(double[] foot, double centre, int axis, int sign, int[] cell, double scale) {
    public BlanketValveGeometry {
        foot = foot.clone();
        cell = cell.clone();
    }

    /** The valve's place on {@code shape}, or null where the shape reserves no flange for it (every kind but a drum). */
    public static BlanketValveGeometry of(EquipmentShape shape) {
        if (shape.kind() != EquipmentShape.Kind.DRUM) return null;
        EquipmentInstruments.Reservation own = null, other = null;
        for (var r : EquipmentInstruments.of(shape).reserved()) {
            if (r.id().equals("F1")) own = r;
            else if (r.id().equals("F2")) other = r;
        }
        if (own == null || other == null) return null;
        double[] lo = own.low(), hi = own.high(), otherLo = other.low(), otherHi = other.high();
        double[] c = {(lo[0] + hi[0]) / 2, lo[1], (lo[2] + hi[2]) / 2}, o = {(otherLo[0] + otherHi[0]) / 2, 0, (otherLo[2] + otherHi[2]) / 2};
        // Away from the second flange, along the horizontal axis that separates the two.
        int axis = Math.abs(c[0] - o[0]) >= Math.abs(c[2] - o[2]) ? 0 : 2, across = 2 - axis, sign = c[axis] >= o[axis] ? 1 : -1;
        double[] foot = c.clone();
        foot[across] = Math.floor(c[across]) + .5;
        // The lowest cell row whose centre stands a neck (0.45 m) above the flange.
        int row = (int) Math.floor(c[1]);
        if (row + .5 - c[1] < .45) row++;
        int[] cell = new int[3];
        cell[axis] = (int) Math.floor(c[axis]);
        cell[1] = row;
        cell[across] = (int) Math.floor(c[across]);
        return new BlanketValveGeometry(foot, row + .5, axis, sign, cell, Math.clamp((hi[0] - lo[0]) / 1.0, .75, 1));
    }

    /** The direction out of {@link #cell} the gas inlet faces, as a unit step. */
    public int[] face() {
        var d = new int[3];
        d[axis] = sign;
        return d;
    }

    /** Where the inlet's flange face lies along {@link #axis}: the block face of {@link #cell} on the inlet's side. */
    public double faceAt() {
        return sign > 0 ? cell[axis] + 1 : cell[axis];
    }

    /** The accessory's bounds in the box frame, {@code minX, minY, minZ, maxX, maxY, maxZ}: selection, clearance and hover. */
    public double[] bounds() {
        double r = .28 * scale;
        double[] b = {foot[0] - r, foot[1], foot[2] - r, foot[0] + r, centre + .5 * scale, foot[2] + r};
        if (sign > 0) {
            b[axis + 3] = Math.max(b[axis + 3], faceAt());
            b[axis] = Math.min(b[axis], foot[axis] - .36 * scale);
        } else {
            b[axis] = Math.min(b[axis], faceAt());
            b[axis + 3] = Math.max(b[axis + 3], foot[axis] + .36 * scale);
        }
        return b;
    }

    /** Draws the valve into {@code mesh}, in the box frame. */
    void build(Mesh mesh) {
        double k = scale, pipe = .06 * k, flange = .125 * k;
        int across = 2 - axis;
        var up = Frame.of(new double[] {foot[0], 0, foot[2]}, 1, 1);
        // The drum's nozzle for it: a stub from under the drum's skin to the cell boundary under the valve, as the drum's own
        // top nozzles stand, and its flange pair there; then the valve's short neck.
        double stub = .07 + .07 * k, seat = centre - .5;
        mesh.cylinder(up, stub, foot[1] - .45, seat, 14, Material.PIPE);
        mesh.cylinder(up, stub + .07 * k, seat - .04 * k, seat + .05 * k, 16, Material.FLANGE);
        mesh.cylinder(up, pipe, seat, centre, 12, Material.PIPE);
        // The globe body, the bonnet, the diaphragm actuator (two dished cases on a bolted rim) and its spring case.
        mesh.sphere(new double[] {foot[0], centre, foot[2]}, .13 * k, 12, Material.MACHINE);
        mesh.cylinder(up, .055 * k, centre + .1 * k, centre + .22 * k, 12, Material.MACHINE);
        mesh.frustum(up, .09 * k, .25 * k, centre + .22 * k, centre + .27 * k, 16, Material.ACTUATOR);
        mesh.cylinder(up, .27 * k, centre + .27 * k, centre + .3 * k, 16, Material.FLANGE);
        mesh.frustum(up, .25 * k, .09 * k, centre + .3 * k, centre + .35 * k, 16, Material.ACTUATOR);
        mesh.cylinder(up, .07 * k, centre + .35 * k, centre + .47 * k, 12, Material.ACTUATOR);
        mesh.cylinder(up, .04 * k, centre + .47 * k, centre + .5 * k, 12, Material.FLANGE);
        // The sensing line: from the lower case down beside the riser and into the neck above its flange.
        double[] tap = {foot[0], centre + .24 * k, foot[2]};
        tap[across] += .19 * k;
        double low = Math.min(seat + .14 * k, centre - .16 * k);
        mesh.cylinder(Frame.of(new double[] {tap[0], 0, tap[2]}, 1, 1), .014 * k, low, tap[1], 8, Material.PIPE);
        mesh.cylinder(Frame.of(new double[] {tap[0], low, tap[2]}, across, -1), .014 * k, 0, .19 * k, 8, Material.PIPE);
        // The gas inlet, out to the block face.
        double[] body = {foot[0], centre, foot[2]};
        var out = Frame.of(body, axis, sign);
        double reach = Math.abs(faceAt() - foot[axis]);
        mesh.cylinder(out, pipe, 0, reach, 12, Material.PIPE);
        mesh.cylinder(out, flange, reach - .05 * k, reach, 16, Material.FLANGE);
        // The vent: a stub on the far side, turned down under a rain cap.
        var back = Frame.of(body, axis, -sign);
        mesh.cylinder(back, .045 * k, 0, .26 * k, 10, Material.PIPE);
        double[] vent = body.clone();
        vent[axis] -= sign * .26 * k;
        var down = Frame.of(vent, 1, -1);
        mesh.cylinder(down, .045 * k, -.045 * k, .12 * k, 10, Material.PIPE);
        mesh.frustum(down, .045 * k, .1 * k, .12 * k, .16 * k, 12, Material.FLANGE);
    }
}
