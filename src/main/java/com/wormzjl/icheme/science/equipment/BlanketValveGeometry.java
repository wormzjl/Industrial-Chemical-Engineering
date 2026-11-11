package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;

/**
 * A drum's gas blanketing valve (batch 2026-11-11-gas-blanketing; owner: an optional accessory "for vertical & horizontal
 * drums", "connected to a gas source through piping, vent to atmosphere", "placed on top of drums"): a self-acting pressure
 * regulator on the drum's first reserved top flange ({@link EquipmentInstruments}, reservation {@code F1}).
 *
 * <p>Its place, in the equipment's box frame: a riser from the flange to the centre of the first cell row clear of the
 * flange; the globe body there under its diaphragm actuator; the gas inlet level with the body, out to a block face on the
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
        int row = (int) Math.ceil(c[1]);
        if (row + .5 - c[1] < .45) row++;
        int[] cell = new int[3];
        cell[axis] = (int) Math.floor(c[axis]);
        cell[1] = row;
        cell[across] = (int) Math.floor(c[across]);
        return new BlanketValveGeometry(foot, row + .5, axis, sign, cell, Math.clamp((hi[0] - lo[0]) / 1.0, .5, 1));
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
        double r = .2 * scale;
        double[] b = {foot[0] - r, foot[1], foot[2] - r, foot[0] + r, centre + .36 * scale, foot[2] + r};
        if (sign > 0) {
            b[axis + 3] = Math.max(b[axis + 3], faceAt());
            b[axis] = Math.min(b[axis], foot[axis] - .25 * scale);
        } else {
            b[axis] = Math.min(b[axis], faceAt());
            b[axis + 3] = Math.max(b[axis + 3], foot[axis] + .25 * scale);
        }
        return b;
    }

    /** Draws the valve into {@code mesh}, in the box frame. */
    void build(Mesh mesh) {
        double k = scale, pipe = .045 * k, flange = .1 * k;
        var up = Frame.of(new double[] {foot[0], 0, foot[2]}, 1, 1);
        // The neck from under the drum's skin, its flange pair, the riser.
        mesh.cylinder(up, pipe, foot[1] - .45, centre, 12, Material.PIPE);
        mesh.cylinder(up, flange, foot[1], foot[1] + .05 * k, 16, Material.FLANGE);
        // The globe body, the bonnet and the diaphragm actuator with its spring case.
        mesh.sphere(new double[] {foot[0], centre, foot[2]}, .1 * k, 12, Material.MACHINE);
        mesh.cylinder(up, .04 * k, centre + .08 * k, centre + .2 * k, 12, Material.MACHINE);
        mesh.frustum(up, .08 * k, .18 * k, centre + .2 * k, centre + .24 * k, 16, Material.ACTUATOR);
        mesh.cylinder(up, .18 * k, centre + .24 * k, centre + .27 * k, 16, Material.ACTUATOR);
        mesh.frustum(up, .18 * k, .06 * k, centre + .27 * k, centre + .31 * k, 16, Material.ACTUATOR);
        mesh.cylinder(up, .045 * k, centre + .31 * k, centre + .36 * k, 12, Material.ACTUATOR);
        // The gas inlet, out to the block face.
        double[] body = {foot[0], centre, foot[2]};
        var out = Frame.of(body, axis, sign);
        double reach = Math.abs(faceAt() - foot[axis]);
        mesh.cylinder(out, pipe, 0, reach, 12, Material.PIPE);
        mesh.cylinder(out, flange, reach - .04 * k, reach, 16, Material.FLANGE);
        // The vent: a stub on the far side, turned down under a rain cap.
        var back = Frame.of(body, axis, -sign);
        mesh.cylinder(back, .03 * k, 0, .17 * k, 10, Material.PIPE);
        double[] vent = body.clone();
        vent[axis] -= sign * .17 * k;
        var down = Frame.of(vent, 1, -1);
        mesh.cylinder(down, .03 * k, -.03 * k, .1 * k, 10, Material.PIPE);
        mesh.frustum(down, .03 * k, .065 * k, .1 * k, .13 * k, 12, Material.FLANGE);
    }
}
