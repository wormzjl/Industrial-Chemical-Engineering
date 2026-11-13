package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Nozzle;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Solid;
import com.wormzjl.icheme.science.equipment.design.UnitRatings;

import java.util.List;

/** A kettle reboiler ({@link #build}): bundle 0.5 to 2 m in a shell of 1.6 bundles, tubes three bundles to 10 m long. */
final class KettleDesign extends UnitDesign {
    @Override
    public double minSize() {
        return .5;
    }

    @Override
    public double maxSize() {
        return 2;
    }

    @Override
    public double sizeStep() {
        return ProcessUnit.SHELL_STEP;
    }

    @Override
    public int minLength(double size) {
        return Math.max(2, (int) Math.ceil(3 * size - 1e-9));
    }

    @Override
    public int maxLength(double size) {
        return 10;
    }

    /** The kettle's bottom 2 m up leaves a block under its bottom nozzles for their pipes. */
    @Override
    public int minSupport(double size, int length) {
        return 2;
    }

    @Override
    public Orientation orientation() {
        return Orientation.HORIZONTAL;
    }

    @Override
    public String sizeRow() {
        return "bundle";
    }

    @Override
    public String lengthRow() {
        return "tubes";
    }

    @Override
    public String supportRow() {
        return "kettle_bottom";
    }

    @Override
    public Line held(EquipmentShape shape) {
        double d = shape.diameter();
        return new Line("kettle_surface", size(UnitRatings.kettleShell(d)), number(UnitRatings.tubeSurface(d, shape.tangentLength())));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double d = shape.diameter(), tubes = shape.tangentLength();
        return List.of(new Line("bundle", size(d), size(UnitRatings.kettleShell(d))), new Line("tubes", number(tubes), number(UnitRatings.tubeSurface(d, tubes))));
    }

    /**
     * A kettle reboiler (TEMA AKT) lying along z, its kettle's bottom {@code support} up: the channel at the front (steam
     * in at its top, condensate out at its bottom), a cone up to the kettle's shell (1.6 times the bundle), the shell
     * (liquid fed low at its front, vapour out on top) and its back head, where the product leaves low behind the weir.
     */
    @Override
    void build(ProcessUnit u) {
        double db = u.shape.diameter(), rb = db / 2, dk = UnitRatings.kettleShell(db), rk = dk / 2, y = u.shape.support() + rk, a = ProcessUnit.centre(rk);
        int n = ProcessUnit.sides(dk), nb = ProcessUnit.sides(db);
        double channel = .5 * db + .2, zn = Math.ceil(.08 + channel / 2 - .5) + .5;
        double c0 = zn - channel / 2 - .08, ch1 = zn + channel / 2, cone = Math.max(.3, .6 * (dk - db)), s0 = ch1 + .1 + cone, s1 = s0 + .9 * u.shape.tangentLength() + .3;
        var f = new Frame(new double[]{a, y, 0}, new double[]{0, 0, 1}, new double[]{0, 1, 0});
        u.mesh.ring(f, 0, 1.1 * rb, c0, c0 + .08, nb, 3, Material.FLANGE);
        u.mesh.bolts(f, db, rb, 1.1 * rb, c0, -1, Material.BOLT);
        u.mesh.prism(f, rb, c0 + .08, ch1, nb, Material.SHELL);
        u.mesh.ring(f, rb, 1.12 * rb, ch1, ch1 + .1, nb, 3, Material.FLANGE);
        u.mesh.bolts(f, db, rb, 1.12 * rb, ch1, -1, Material.BOLT);
        u.mesh.frustum(f, 1.05 * rb, rk, ch1 + .1, s0, n, Material.SHELL);
        u.mesh.prism(f, rk, s0, s1, n, Material.SHELL);
        u.mesh.head(f, rk, s1, dk / 4, n, Material.HEAD);
        // Channel, cone and kettle collide as drawn and enclose their insides.
        var axis = new double[]{a, y, 0};
        u.solids.add(Solid.rod(axis, 2, c0, ch1 + .1, 1.12 * rb, 0));
        u.solids.add(Solid.cone(axis, 2, ch1 + .1, s0, 1.05 * rb, rk, 0));
        u.solids.add(Solid.rod(axis, 2, s0, s1, rk, dk / 4));
        u.volumes.add(Solid.rod(axis, 2, c0 + .1, ch1 + .1, .94 * rb, 0));
        u.volumes.add(Solid.cone(axis, 2, ch1 + .1, s0 + .02, .94 * 1.05 * rb, .94 * rk, 0));
        u.volumes.add(Solid.rod(axis, 2, s0 + .02, s1, .94 * rk, .9 * dk / 4));
        u.round(a, y, c0, ch1 + .1, rb);
        u.round(a, y, s0, s1, rk);
        // The shell's pool node (W7): its lowest inside cell, under the back of its cylinder, clear of the feed.
        u.fluidPoint = new double[]{a, y - rk + .01, s1 - .25};

        double tube = Math.min(PipeSizes.MAX_OUTSIDE, .4 * db), liquid = Math.min(PipeSizes.MAX_OUTSIDE, db / 2), vapour = Math.min(PipeSizes.MAX_OUTSIDE, dk / 2), flange = PipeSizes.flange(liquid);
        u.nozzles.add(ProcessUnit.radial(Role.TUBE_INLET, a, y, rb, nb, zn, 1, ColumnStructure.size(.25 * db, tube)));
        u.nozzles.add(ProcessUnit.radial(Role.TUBE_OUTLET, a, y, rb, nb, zn, -1, ColumnStructure.size(.15 * db, tube)));
        // The feed near the cone, two rows at least behind the channel's outlet (one free block between pipes).
        double zFeed = Math.max(Math.ceil(s0 + flange / 2 + .1 - .5) + .5, zn + 2);
        u.nozzles.add(ProcessUnit.radial(Role.FEED, a, y, rk, n, zFeed, -1, ColumnStructure.size(.15 * db, liquid)));
        u.nozzles.add(ProcessUnit.radial(Role.VAPOUR_OUTLET, a, y, rk, n, Math.floor((s0 + s1) / 2) + .5, 1, ColumnStructure.size(.3 * dk, vapour)));
        // The product: low on the back head, at the lowest cell centre 0.3 m over the shell's bottom, its default size
        // within 0.85 of the head's radius from the axis; its neck rooted under the head where a pipe's largest neck's
        // edge meets it (owner decision 2026-10-21: a 1 m pipe has a 1 m root).
        double yP = Math.ceil(u.shape.support() + .3 - .5) + .5, e = y - yP, depth = dk / 4;
        double od = Math.min(liquid, 2 * (.85 * rk - Math.abs(e)));
        double edge = (Math.abs(e) + PipeSizes.MAX_OUTSIDE / 2) / rk, under = depth * Math.sqrt(Math.max(0, 1 - edge * edge)), start = s1 + under - .03;
        u.nozzles.add(new Nozzle(Role.LIQUID_OUTLET, new double[]{a, yP, start}, 2, 1, Math.ceil(s1 + depth + ProcessUnit.NECK) - start,
                ColumnStructure.size(.15 * db, od), 0));
        double w = ProcessUnit.saddleWidth(rk), length = s1 - s0;
        u.saddle(ProcessUnit.saddleAt(s0 + .2 * length, w, s0, s1, zFeed), a, y, rk, w, 0);
        u.saddle(ProcessUnit.saddleAt(s1 - .2 * length, w, s0, s1, zFeed), a, y, rk, w, 0);
    }
}
