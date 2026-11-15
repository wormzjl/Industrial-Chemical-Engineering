package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Solid;
import com.wormzjl.icheme.science.equipment.design.UnitRatings;

import java.util.List;

/** A TEMA AES shell-and-tube exchanger ({@link #build}): shell 0.5 to 2.5 m, tubes three shells to 12 m long. */
final class ExchangerDesign extends UnitDesign {
    ExchangerDesign() {
        // minSupport: The shell's bottom 2 m up leaves a block under its bottom nozzles for their pipes.
        // Computed in its overrides: minLength.
        super(new Sizes(.5, 2.5, ProcessUnit.SHELL_STEP,
                Sizes.COMPUTED, 12,
                2, EquipmentShape.MAX_SUPPORT,
                Orientation.HORIZONTAL, "shell", "tubes", "shell_bottom", false,
                1));
    }

    @Override
    public int minLength(double size) {
        return Math.max(2, (int) Math.ceil(3 * size - 1e-9));
    }

    @Override
    public Line held(EquipmentShape shape) {
        return new Line("surface", number(UnitRatings.tubeSurface(shape.diameter(), shape.tangentLength())));
    }

    @Override
    public String variant(EquipmentShape shape) {
        return shape.exchangerType()==ExchangerType.STANDARD?"":"."+shape.exchangerType().key();
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double d = shape.diameter(), tubes = shape.tangentLength();
        return List.of(new Line("shell", size(d)), new Line("tubes", number(tubes), number(UnitRatings.tubeSurface(d, tubes))));
    }

    /**
     * A TEMA AES exchanger lying along z on two saddles, its bottom {@code support} up: from the front, the channel's
     * cover, the channel (tube side in at its bottom, out at its top), the tubesheet's flange, the shell (shell side in
     * on top near the back, out at the bottom near the front), the floating head's flange and its cover.
     */
    @Override
    void build(ProcessUnit u) {
        double d = u.shape.diameter(), r = d / 2, y = u.shape.support() + r, a = ProcessUnit.centre(1.1 * r);
        int n = ProcessUnit.sides(d);
        // A readable cover pattern at game scale: roughly 0.3 m pitch, in fours, with at least eight bolts.
        int bolts = Math.max(8, 4 * (int) Math.ceil(Math.PI * d / 1.2));
        double channel = .5 * d + .2, zn = Math.ceil(.08 + channel / 2 - .5) + .5;
        double c0 = zn - channel / 2 - .08, ch1 = zn + channel / 2, s0 = ch1 + .1, s1 = s0 + .95 * u.shape.tangentLength(), end = s1 + .1;
        var f = new Frame(new double[]{a, y, 0}, new double[]{0, 0, 1}, new double[]{0, 1, 0});
        u.mesh.ring(f, 0, 1.1 * r, c0, c0 + .08, n, 3, Material.FLANGE);
        u.mesh.bolts(f, d, r, 1.1 * r, c0, -1, bolts, Material.BOLT);
        u.mesh.prism(f, r, c0 + .08, ch1, n, Material.SHELL);
        u.mesh.ring(f, r, 1.1 * r, ch1, s0, n, 3, Material.FLANGE);
        u.mesh.bolts(f, d, r, 1.1 * r, ch1, -1, bolts, Material.BOLT);
        u.mesh.bolts(f, d, r, 1.1 * r, s0, 1, bolts, Material.BOLT);
        u.mesh.prism(f, r, s0, s1, n, Material.SHELL);
        u.mesh.ring(f, r, 1.08 * r, s1, end, n, 3, Material.FLANGE);
        u.mesh.bolts(f, d, r, 1.08 * r, s1, -1, bolts, Material.BOLT);
        u.mesh.bolts(f, d, r, 1.08 * r, end, 1, bolts, Material.BOLT);
        u.mesh.head(f, r, end, d / 4, n, Material.HEAD);
        u.solids.add(Solid.rod(new double[]{a, y, 0}, 2, c0, end, 1.05 * r, d / 4));
        u.volumes.add(Solid.rod(new double[]{a, y, 0}, 2, c0 + .1, end, .94 * r, .9 * d / 4));
        u.round(a, y, c0, end, r);

        double tube = Math.min(PipeSizes.MAX_OUTSIDE, .4 * d), shellOd = Math.min(PipeSizes.MAX_OUTSIDE, d / 2), flange = PipeSizes.flange(shellOd);
        u.nozzles.add(ProcessUnit.radial(Role.TUBE_INLET, a, y, r, n, zn, -1, ColumnStructure.size(.25 * d, tube)));
        u.nozzles.add(ProcessUnit.radial(Role.TUBE_OUTLET, a, y, r, n, zn, 1, ColumnStructure.size(.25 * d, tube)));
        // The shell nozzles near the tubesheets, two rows at least behind the channel's (one free block between pipes).
        double zIn = Math.max(Math.floor(s1 - flange / 2 - .1 - .5) + .5, zn + 2), zOut = Math.max(Math.ceil(s0 + flange / 2 + .1 - .5) + .5, zn + 2);
        var layout=u.shape.exchangerType().layout();
        if(layout.transverseShell()) {
            // TEMA X-shell: distribution and collection are internal; the outer pressure shell stays cylindrical.
            zIn=zOut=Math.max(zn+2,Math.floor((s0+s1)/2)+.5);
        }
        u.nozzles.add(ProcessUnit.radial(Role.SHELL_INLET, a, y, r, n, zIn, 1, ColumnStructure.size(.3 * d, shellOd)));
        u.nozzles.add(ProcessUnit.radial(Role.SHELL_OUTLET, a, y, r, n, zOut, -1, ColumnStructure.size(.3 * d, shellOd)));
        // The saddles a fifth of the shell in from its ends, off the pipe blocks under the tube inlet and the shell outlet.
        double w = ProcessUnit.saddleWidth(r), length = s1 - s0;
        u.saddle(ProcessUnit.saddleAt(s0 + .2 * length, w, s0, s1, zn, zOut), a, y, r, w, 0);
        u.saddle(ProcessUnit.saddleAt(s1 - .2 * length, w, s0, s1, zn, zOut), a, y, r, w, 0);
    }
}
