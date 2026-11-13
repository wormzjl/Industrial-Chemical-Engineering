package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Nozzle;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Solid;

import java.util.ArrayList;
import java.util.List;

/** A steam ejector standing on its frame ({@link #build}): suction 0.05 to 1 m, 8.3 suctions long. */
final class EjectorDesign extends UnitDesign {
    @Override
    public double minSize() {
        return ProcessUnit.SUCTION_STEP;
    }

    @Override
    public double maxSize() {
        return PipeSizes.MAX_OUTSIDE;
    }

    @Override
    public double sizeStep() {
        return ProcessUnit.SUCTION_STEP;
    }

    /** The discharge flange 1 m up at least; a new ejector's 2 m. */
    @Override
    public int minSupport(double size, int length) {
        return 1;
    }

    @Override
    public int defaultSupport(double size, int length) {
        return 2;
    }

    @Override
    public Orientation orientation() {
        return Orientation.VERTICAL;
    }

    @Override
    public String sizeRow() {
        return "suction";
    }

    @Override
    public String supportRow() {
        return "discharge";
    }

    @Override
    public Line held(EquipmentShape shape) {
        return new Line("ejector_length", number(ProcessUnit.ejectorLength(shape.diameter())));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        return List.of(new Line("suction", size(shape.diameter()), number(ProcessUnit.ejectorLength(shape.diameter()))));
    }

    /**
     * A steam ejector standing on its own frame, sized by its suction s: from the top, the steam chest (motive steam in on
     * top), the suction chamber (suction from the front), the inlet cone, the throat and the diffuser down to its discharge
     * flange, {@code support} up. Four posts round it from the ground to over the chest, tied by square frames every
     * 2.5 m, open at the front in the suction's row; a small one ({@link ProcessUnit#SMALL_EJECTOR}), which a plant hangs on its
     * piping, on one post in the cell behind it. Lengths in suction diameters: chest 0.8, chamber 1.5 (1.2 wide), inlet
     * 2.2, throat 0.8 (0.35 wide), diffuser 3.0 (to 0.75 wide).
     */
    @Override
    void build(ProcessUnit u) {
        // Posts at ±e round the axis, their base plates outside the pipe blocks of the suction and the discharge (half a
        // block each side of the axis), and clear of the chamber; a small ejector's axis on its cell's centre.
        double s = u.shape.diameter(), e = Math.max(.5 + ProcessUnit.POST, .6 * s + .3), h = u.shape.support();
        boolean small = s <= ProcessUnit.SMALL_EJECTOR + 1e-9;
        double a = small ? .5 : ProcessUnit.centre(e + ProcessUnit.POST);
        // The suction's centre on a cell centre: the diffuser's exit at least 0.2 m over the discharge flange.
        int row = (int) Math.ceil(h + .2 + 6.75 * s - .5);
        double exit = row + .5 - 6.75 * s, chamber = exit + 6 * s, chest = exit + 7.5 * s, top = exit + 8.3 * s;
        var f = Frame.of(new double[]{a, exit, a}, 1, 1);
        int n = ProcessUnit.sides(1.2 * s);
        double rc = .6 * s, throat = .175 * s, outlet = .375 * s;
        u.mesh.ring(f, 0, outlet + .05 + .1 * s, -.03, 0, n, 1, Material.FLANGE);
        u.mesh.bolts(f, 2 * outlet, outlet, outlet + .05 + .1 * s, 0, 1, Material.BOLT);
        u.mesh.frustum(f, outlet, throat, 0, 3 * s, n, Material.MACHINE);
        u.mesh.prism(f, throat, 3 * s, 3.8 * s, n, Material.MACHINE);
        u.mesh.frustum(f, throat, rc, 3.8 * s, 6 * s, n, Material.MACHINE);
        u.mesh.prism(f, rc, 6 * s, 7.5 * s, n, Material.MACHINE);
        u.mesh.annulus(f, .225 * s, rc, 7.5 * s, n, 1, Material.MACHINE);
        u.mesh.ring(f, .225 * s, .3 * s, 7.5 * s, 7.58 * s, n, 3, Material.FLANGE);
        u.mesh.bolts(f, .45 * s, .225 * s, .3 * s, 7.58 * s, 1, Material.BOLT);
        u.mesh.cylinder(f, .225 * s, 7.5 * s, 8.3 * s, n, Material.MACHINE);
        u.solids.add(Solid.rod(new double[]{a, 0, a}, 1, exit, chest, rc, 0));
        u.solids.add(Solid.rod(new double[]{a, 0, a}, 1, chest, top, .225 * s, 0));
        u.round(a, a, chamber, chest, rc, 1);
        u.nozzles.add(ProcessUnit.radialSide(Role.SUCTION, a, row + .5, a, rc, n, -1, s));
        // The motive inlet's flange on the block face over the steam chest, and at least two rows over the suction's (one
        // free block between their pipes): a small ejector's stub runs up to it.
        double motive = Math.max(PipeSizes.MIN_OUTSIDE, .45 * s), motiveFace = Math.max(Math.ceil(top + ProcessUnit.NECK), row + 2);
        u.nozzles.add(new Nozzle(Role.MOTIVE, new double[]{a, top - .03, a}, 1, 1, motiveFace - top + .03,
                ColumnStructure.size(.3 * s, motive), 0));
        double discharge = Math.max(PipeSizes.MIN_OUTSIDE, 2 * outlet);
        u.nozzles.add(new Nozzle(Role.DISCHARGE, new double[]{a, exit + .03, a}, 1, -1, exit + .03 - h, discharge, 0));
        double postTop = top + .3, bar = .14, clamp = exit + 3.4 * s;
        if (small) {
            // One post in the cell behind (the suction's pipe leaves in front, the discharge's below, the motive's above),
            // braced to the throat and the chamber.
            double z = a + 1;
            u.box(new double[]{a - ProcessUnit.POST / 2, 0, z - ProcessUnit.POST / 2}, new double[]{a + ProcessUnit.POST / 2, postTop, z + ProcessUnit.POST / 2}, Material.STEELWORK, true);
            u.box(new double[]{a - ProcessUnit.POST, 0, z - ProcessUnit.POST}, new double[]{a + ProcessUnit.POST, .04, z + ProcessUnit.POST}, Material.SUPPORT, false);
            u.mesh.bar(new double[]{a, clamp, z}, new double[]{a, clamp, a + throat}, bar * .6, new double[]{0, 1, 0}, Material.STEELWORK);
            u.mesh.bar(new double[]{a, (chamber + chest) / 2, z}, new double[]{a, (chamber + chest) / 2, a + rc}, bar * .6, new double[]{0, 1, 0}, Material.STEELWORK);
            return;
        }
        // The frame: posts beside the suction's pipe block, square frames open at the front in its row, a clamp at the
        // throat.
        double[][] corners = {{a - e, a - e}, {a + e, a - e}, {a + e, a + e}, {a - e, a + e}};
        for (var c : corners) {
            u.box(new double[]{c[0] - ProcessUnit.POST / 2, 0, c[1] - ProcessUnit.POST / 2}, new double[]{c[0] + ProcessUnit.POST / 2, postTop, c[1] + ProcessUnit.POST / 2}, Material.STEELWORK, true);
            u.box(new double[]{c[0] - ProcessUnit.POST, 0, c[1] - ProcessUnit.POST}, new double[]{c[0] + ProcessUnit.POST, .04, c[1] + ProcessUnit.POST}, Material.SUPPORT, false);
        }
        var levels = new ArrayList<Double>();
        for (double y = 2.5; y < postTop - .3; y += 2.5) levels.add(y);
        levels.add(clamp);
        levels.add(postTop - .1);
        for (double y : levels) {
            // In the suction's row its pipe leaves between the front posts: the front bar and brackets give way there.
            boolean suction = y + bar / 2 > row - .05 && y - bar / 2 < row + 1.05;
            for (int k = suction ? 1 : 0; k < 4; k++) {
                var p = corners[k];
                var q = corners[(k + 1) % 4];
                u.mesh.bar(new double[]{p[0], y, p[1]}, new double[]{q[0], y, q[1]}, bar * .8, new double[]{0, 1, 0}, Material.STEELWORK);
            }
            // At the clamp, brackets from the corners to the throat.
            if (y == clamp)
                for (var c : corners)
                    if (!suction || c[1] > a)
                        u.mesh.bar(new double[]{c[0], y, c[1]}, new double[]{a + (c[0] - a) * throat / e, y, a + (c[1] - a) * throat / e}, bar * .6,
                                new double[]{0, 1, 0}, Material.STEELWORK);
        }
    }
}
