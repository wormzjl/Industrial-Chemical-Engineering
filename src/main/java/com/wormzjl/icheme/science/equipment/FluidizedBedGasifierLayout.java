package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Motion;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Ring;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Solid;
import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.ArrayList;
import java.util.List;

import static com.wormzjl.icheme.science.equipment.FluidizedBedParts.closedBox;

/**
 * An HTW gasifier's parts ({@link FluidizedBedGasifierDesign}; {@code NEW_UNITS_SURVEY_SOLID_FUEL.md} section 1, gasifier
 * mode), in the unit's frame: the front (-z) towards the constructor, +x its left.
 *
 * <p>The vessel: a painted pressure shell with 2:1 heads (no cladding), the O2/steam ring headers at the distributor and
 * at the post-gasification level 0.4 of the way up (signature), its support ring a third of the way up on the
 * structure's floor. In front of it the lock-hopper stack (feed bin, lock hopper, charge hopper, each 1.1 ID across and
 * 1.5 ID tall, Class 300 lock valves between them stroked by hydraulic cylinders) over one or two screw feeders into the
 * bed 1.5 m over the distributor, their drives at the front on the feed floor. On its left the cyclone (two over 4.5 m
 * ID) from the vessel's top, its dipleg back down into the bed; the hot gas duct from the cyclones' outlets back to the
 * vertical syngas cooler (0.6 ID across, 3 ID long) hanging behind the vessel, its steam drum on the structure's top.
 * Under the vessel the water-cooled ash screw, its drive turning, into the ash lock hopper behind it. The open steel
 * structure round it all: columns fireproofed in concrete up to the support floor, girders at that floor, at the lock
 * valves' floors and at the top, X-bracing in the vessel bay's sides; grating decks round the vessel and the lock-hopper
 * stack (the kit's ring access), caged ladders on the right, rest platforms where a climb would be longer than 9 m
 * (owner decision C1 2026-10-18).
 */
final class FluidizedBedGasifierLayout {
    /** The gap between the lock-hopper stack and the vessel, m: room for both decks and the screws' run. */
    static final double GAP = 2.6;
    /** A deck's width round the vessel and the stack, m. */
    static final double WIDTH = 1;
    /** The ladders' angles either side of the right (-x): a quarter turn of the ring decks apart at most. */
    static final double LADDER = .45;
    /** A lock valve actuator's stroke, m, and its rate, rev/s: out and back every 20 s (a lock cycles every few minutes). */
    static final double STROKE = .2, VALVE_SPEED = .05;

    final ProcessUnit u;
    final double id, od, r, ht, hd, yb, yt, a, za;
    final int nc, screws;
    final double dcy, tcy, rcy, xcy, roof, stubTop, dd, hdu, wd;
    final double[] zcy;
    final double dv, hv, hval, zl, ys, rsc, rm, cone, cyl;
    final double dsc, lsc, zs, ysc0, ysc1, ddr, ydr, lr;
    final double dsa, ysa, dl, hl, zal;
    final double hc, xm, xp, zf, zm, zr, frameTop, ring, feedFloor, xw, zFront;

    FluidizedBedGasifierLayout(ProcessUnit unit) {
        u = unit;
        id = u.shape.diameter();
        od = FluidizedBedGasifierDesign.shell(id);
        r = od / 2;
        ht = FluidizedBedGasifierDesign.tangent(id);
        hd = od / 4;
        yb = u.shape.support();
        yt = yb + ht;
        // The cyclones (survey: about 0.7 ID; Basu's proportions as on the boiler), their roofs a little over the vessel's
        // top tangent, their inlet ducts 0.55 dc high from its top shell.
        nc = FluidizedBedGasifierDesign.cyclones(id);
        dcy = FluidizedBedGasifierDesign.cyclone(id);
        tcy = .3 + .015 * dcy;
        rcy = dcy / 2 + tcy;
        roof = yt + .4;
        hdu = .55 * dcy;
        wd = .3 * dcy + .2;
        dd = .5 * dcy + .3;
        // The gas duct runs back beside the vessel and across behind it, so it need not clear the top head.
        stubTop = roof + .8;
        // The lock-hopper stack: three vessels 1.1 ID across and 1.5 ID tall (survey), each a 2:1 top head, a cylinder and a
        // 60° cone; a lock valve between each two; the charge hopper's outlet over the screws, 1.5 m over the distributor.
        dv = 1.1 * id;
        hv = 1.5 * id;
        hval = .45 + .06 * id;
        cone = .87 * (.45 * dv - .1);
        cyl = hv - dv / 4 - cone;
        screws = id > 3.5 ? 2 : 1;
        rsc = Math.max(.25, Math.min(.5, .06 * id + .15));
        rm = Math.max(.2, Math.min(.35, .15 + .03 * id));
        ys = yb + 1.5;
        // The syngas cooler (survey: 0.6 ID across, 3 ID long) and its drum.
        dsc = .6 * id + .2;
        lsc = 3 * id;
        ddr = Math.max(.8, Math.min(1.6, .2 * id + .5));
        lr = Math.max(1.6 * dsc, 2.5 * (dsc / 2 + .45 + 2 * (.09 + .02 * id)));
        // The ash system.
        dsa = FluidizedBedGasifierDesign.ashScrew(id);
        dl = FluidizedBedGasifierDesign.ashLock(id);
        hl = 1.6 * dl;
        ysa = yb - hd - .4 - dsa / 2;
        hc = Math.max(.4, Math.min(.6, .35 + .04 * id));
        // Across: the frame's right line clear of the vessel's decks and ladders, its left beyond the cyclones.
        xm = r + 1.6;
        xp = r + 1.4 + 2 * rcy + .8;
        a = Math.ceil(xm + hc / 2 + .3 - .5) + .5;
        xcy = a + r + 1.4 + rcy;
        // Along: the feed floor's front past the screws' drives; the stack on a cell centre (the bin's inlet up its axis).
        double drive = rsc + .45 + .6 + 4.5 * rm;
        double front = Math.max(dv / 2 + 2.6, drive + .6) + hc + .5;
        zl = Math.ceil(front - .5) + .5;
        zFront = zl - drive - .5;
        zf = Math.min(Math.floor(zl - dv / 2 - 1.3 - .5) + .2, zFront) - hc / 2 - .1;
        za = zl + dv / 2 + GAP + r;
        zm = zl + dv / 2 + GAP / 2;
        zs = Math.floor(za + r + 1.5 + dsc / 2 - .5) + .5;
        zr = zs + dsc / 2 + 2.6;
        zal = Math.floor(za + r - .2) + .5;
        zcy = nc == 1 ? new double[]{za} : new double[]{za - rcy - .35, za + rcy + .35};
        // Up: the cooler under the gas duct, the drum over it on the structure's top.
        ysc1 = stubTop - .5 - dsc / 4;
        ysc0 = ysc1 - lsc;
        ydr = Math.ceil(stubTop + dd + .7 + ddr / 2 - .5) + .5;
        frameTop = ydr - ddr / 2 - .7;
        ring = Math.round(yb + ht / 3);
        feedFloor = ys - 1.15;
        xw = Math.max(dv / 2 + .6, .25 * dv + rm + .8);
    }

    void draw() {
        vessel();
        cyclones();
        cooler();
        stack();
        screws();
        ash();
        frame();
        access();
        nozzles();
    }

    // ---- The vessel ---------------------------------------------------------------------------------------------------

    /**
     * The vessel: its shell and heads, girth seams' bands every 6 m, the support ring (a ring plate and gussets) on the
     * structure's floor a third of the way up, the O2/steam ring headers at the distributor and at 0.4 of the way up with
     * their eight injection stubs each (survey: a signature), the top manway, the bottom outlet's neck.
     */
    void vessel() {
        var up = Frame.of(new double[]{a, 0, za}, 1, 1);
        int n = ProcessUnit.sides(od);
        double[] axis = {a, 0, za};
        u.mesh.prism(up, r, yb, yt, n, Material.SHELL);
        u.mesh.head(up, r, yt, hd, n, Material.HEAD);
        u.mesh.head(Frame.of(new double[]{a, 0, za}, 1, -1), r, -yb, hd, n, Material.HEAD);
        for (double y = yb + 6; y < yt - 3; y += 6) u.mesh.hoop(up, r, r + .04, y, y + .1, n, Material.SHELL);
        u.solids.add(Solid.rod(axis, 1, yb, yt, r, hd, hd));
        // Its inside, the heads' too.
        u.volumes.add(Solid.rod(axis, 1, yb, yt, r - .2, hd - .2, hd - .2));
        u.round(a, za, yb, yt, r, 1);
        // The support ring on the floor's beams, with its gussets.
        u.mesh.hoop(up, r, r + .35, ring - .6, ring - .45, n, Material.SHELL);
        for (int k = 0; k < 8; k++) {
            double t = Math.PI / 8 + Math.PI / 4 * k, c = DeterministicMath.cos(t), s = DeterministicMath.sin(t);
            u.mesh.block(new double[]{a + (r + .17) * c, ring - .9, za + (r + .17) * s}, new double[]{.17 * c, 0, .17 * s}, new double[]{0, .3, 0},
                    new double[]{-.02 * s, 0, .02 * c}, Material.SHELL);
        }
        // The ring headers and their stubs into the shell.
        double rt = header() / 2;
        for (double y : new double[]{distributor(), yb + .4 * ht}) {
            u.mesh.torus(Frame.of(new double[]{a, y, za}, 1, 1), r + .3, rt, 0, 2 * Math.PI, 24, 8, Material.PIPE);
            for (int k = 0; k < 8; k++) {
                double t = Math.PI / 8 * (2 * k + 1), c = DeterministicMath.cos(t), s = DeterministicMath.sin(t);
                u.mesh.bar(new double[]{a + (r - .02) * c, y, za + (r - .02) * s}, new double[]{a + (r + .3) * c, y, za + (r + .3) * s}, .7 * rt + .02, new double[]{0, 1, 0},
                        Material.PIPE);
            }
            u.solids.add(Solid.ring(a, za, y - rt, y + rt, r, r + .3 + rt, 0, 0));
        }
        // The top manway on the head's right shoulder (over the top deck); the bottom outlet's neck.
        double mx = a - .5 * r, my = yt + hd * Math.sqrt(1 - .25) - .1;
        double manway = Math.max(.7, Manway.preferredSize(id)), cover = manway * .45 / .7;
        u.mesh.cylinder(Frame.of(new double[]{mx, 0, za}, 1, 1), manway / 2, my, my + .45, 12, Material.SHELL);
        u.mesh.cylinder(Frame.of(new double[]{mx, 0, za}, 1, 1), cover, my + .45, my + .58, 12, Material.FLANGE);
        u.mesh.annulus(Frame.of(new double[]{mx, 0, za}, 1, 1), 0, cover, my + .58, 12, 1, Material.FLANGE);
        double ra = outlet();
        u.mesh.cylinder(Frame.of(axis, 1, 1), ra, yb - hd - .4, yb - hd + .1, 12, Material.SHELL);
        u.mesh.cylinder(Frame.of(axis, 1, 1), ra + .1, yb - hd - .4, yb - hd - .28, 12, Material.FLANGE);
        u.solids.add(Solid.rod(axis, 1, yb - hd - .4, yb - hd, ra + .1, 0));
    }

    /** The O2/steam ring headers' pipe, m (survey: OXIDANT up to 0.05 ID, the headers a size up). */
    double header() {
        return Math.max(.12, Math.min(.3, .06 * id + .03));
    }

    /** The distributor's ring header's height: 0.5 m over the bottom tangent line. */
    double distributor() {
        return yb + .5;
    }

    /** The bottom outlet's neck radius, m. */
    double outlet() {
        return Math.max(.2, Math.min(.45, .08 * id + .12));
    }

    // ---- Cyclones, cooler -----------------------------------------------------------------------------------------------

    /**
     * Each cyclone ({@link FluidizedBedParts#cyclone}) on the vessel's left with its inlet duct from the vessel's top shell
     * (tangential, on the side towards the vessel's middle), its dipleg (0.2 dc) straight down and at 45° into the bed
     * 2.5 m over the distributor; two beams carrying it from the structure's left. The hot gas duct over its outlet runs
     * back over the vessel's left, then across into the syngas cooler's top.
     */
    void cyclones() {
        double rs = .1 * dcy + .15, entry = yb + 2.5;
        for (double zc : zcy) {
            double foot = FluidizedBedParts.cyclone(u, xcy, zc, roof, dcy, tcy, stubTop - roof, roof - 2.75 * dcy - .5, 24);
            // On the barrel's side towards the vessel's middle (the front with one cyclone).
            int side = zc < za - .01 ? -1 : 1;
            double z0 = side > 0 ? zc - rcy + .05 : zc + rcy - .05 - wd, z1 = z0 + wd, mid = (z0 + z1) / 2;
            double shell = a + Math.sqrt(Math.max(0, r * r - (mid - za) * (mid - za))) - .15;
            closedBox(u, new double[]{shell, roof - hdu - .15, z0}, new double[]{xcy, roof - .15, z1}, Material.CLADDING);
            // The dipleg: down from the cone's standpipe, then at 45° into the bed.
            double xin = a + Math.sqrt(Math.max(0, r * r - (zc - za) * (zc - za))) - .1, bend = entry + (xcy - xin);
            u.line(rs, Material.CLADDING, new double[]{xcy, foot - .5, zc}, new double[]{xcy, bend, zc}, new double[]{xin, entry, zc});
            u.solids.add(Solid.rod(new double[]{xcy, 0, zc}, 1, bend, foot, rs, 0));
            u.mesh.hoop(Frame.of(new double[]{xcy, 0, zc}, 1, 1), rs, rs + .1, bend + 1, bend + 1.15, 12, Material.FLANGE);
            // The beams it hangs from.
            double yb0 = roof - 1.25 * dcy - .45;
            for (int s = -1; s <= 1; s += 2)
                u.box(new double[]{xcy - rcy - .2, yb0, zc + s * .4 * rcy - .12}, new double[]{a + xp, yb0 + .35, zc + s * .4 * rcy + .12}, Material.STEELWORK, true);
        }
        // The hot gas duct: back over the cyclones' outlets to the cooler's line, across to its top, down into it.
        double y0 = stubTop, y1 = stubTop + dd, zfirst = zcy[0] - dd / 2;
        closedBox(u, new double[]{xcy - dd / 2, y0, zfirst}, new double[]{xcy + dd / 2, y1, zs + dd / 2}, Material.CLADDING);
        closedBox(u, new double[]{a - dd / 2, y0, zs - dd / 2}, new double[]{xcy - dd / 2, y1, zs + dd / 2}, Material.CLADDING);
        double rdrop = Math.min(dd, dsc) / 2 - .05;
        u.mesh.cylinder(Frame.of(new double[]{a, 0, zs}, 1, 1), rdrop, ysc1 + dsc / 4 - .1, y0, ProcessUnit.sides(2 * rdrop), Material.CLADDING);
        u.solids.add(Solid.rod(new double[]{a, 0, zs}, 1, ysc1, y0, rdrop, 0));
    }

    /**
     * The vertical syngas cooler (a fire-tube boiler: the gas down its tubes, the water round them) hanging from two beams
     * at its middle, its gas outlet at the bottom head's side; the riser and the downcomer up to its steam drum, which sits
     * on two beams over the structure's top with its safety valve; the moderator steam line from the drum down to the
     * post-gasification header (the surplus leaves as STEAM).
     */
    void cooler() {
        var up = Frame.of(new double[]{a, 0, zs}, 1, 1);
        double rc = dsc / 2;
        int n = ProcessUnit.sides(dsc);
        u.mesh.prism(up, rc, ysc0, ysc1, n, Material.SHELL);
        u.mesh.head(up, rc, ysc1, rc / 2, n, Material.HEAD);
        u.mesh.head(Frame.of(new double[]{a, 0, zs}, 1, -1), rc, -ysc0, rc / 2, n, Material.HEAD);
        u.mesh.hoop(up, rc, rc + .08, ysc1 - .35, ysc1 - .2, n, Material.FLANGE);
        u.solids.add(Solid.rod(new double[]{a, 0, zs}, 1, ysc0, ysc1, rc, rc / 2, rc / 2));
        u.volumes.add(Solid.rod(new double[]{a, 0, zs}, 1, ysc0, ysc1, rc - .15, rc / 2 - .15, rc / 2 - .15));
        u.round(a, zs, ysc0, ysc1, rc, 1);
        // Its beams.
        double mid = (ysc0 + ysc1) / 2;
        u.mesh.hoop(up, rc, rc + .3, mid, mid + .15, n, Material.SHELL);
        for (int s = -1; s <= 1; s += 2) u.box(new double[]{a - xm, mid - .45, zs + s * (rc + .35) - .12}, new double[]{a + xp, mid, zs + s * (rc + .35) + .12}, Material.STEELWORK, true);
        // The drum on its beams and saddles, its safety valve.
        double x0 = a - lr / 2, x1 = a + lr / 2, rd = ddr / 2;
        var f = Frame.of(new double[]{0, ydr, zs}, 0, 1);
        int nd = ProcessUnit.sides(ddr);
        u.mesh.prism(f, rd, x0, x1, nd, Material.SHELL);
        u.mesh.head(f, rd, x1, rd / 2, nd, Material.HEAD);
        u.mesh.head(Frame.of(new double[]{0, ydr, zs}, 0, -1), rd, -x0, rd / 2, nd, Material.HEAD);
        u.solids.add(Solid.rod(new double[]{0, ydr, zs}, 0, x0 - rd / 2, x1 + rd / 2, rd, 0));
        u.volumes.add(Solid.rod(new double[]{0, ydr, zs}, 0, x0, x1, .85 * rd, 0));
        u.round(new double[]{0, ydr, zs}, 0, x0, x1, rd);
        for (int s = -1; s <= 1; s += 2) {
            double x = a + s * .4 * lr;
            u.box(new double[]{x - .2, frameTop, zm}, new double[]{x + .2, frameTop + .4, zr}, Material.STEELWORK, true);
            u.box(new double[]{x - .4 * rd - .1, frameTop + .4, zs - .3}, new double[]{x + .4 * rd + .1, ydr - .6 * rd, zs + .3}, Material.STEELWORK, true);
        }
        double xv = a - .15 * lr, yv = ydr + rd - .05;
        u.box(new double[]{xv - .15, yv, zs - .15}, new double[]{xv + .15, yv + .4, zs + .15}, Material.MACHINE, false);
        u.mesh.prism(Frame.of(new double[]{xv, 0, zs}, 1, 1), .05, yv + .4, yv + 1.6, 8, Material.PIPE);
        u.mesh.cylinder(Frame.of(new double[]{xv, 0, zs}, 1, 1), .18, yv + 1.1, yv + 1.9, 10, Material.MACHINE);
        // The riser and the downcomer as single bars, on the cooler's right clear of the gas duct.
        double xr = a - rc - .15, b = .09 + .02 * id;
        for (double z : new double[]{zs, zs + .45}) {
            u.box(new double[]{xr - b, ysc1 - 1, z - b}, new double[]{xr + b, ydr - .5 * rd, z + b}, Material.PIPE, true);
            u.box(new double[]{xr + b, ysc1 - 1 - b, z - b}, new double[]{a - rc * .8, ysc1 - 1 + b, z + b}, Material.PIPE, true);
        }
        // The moderator steam down from the drum's right end to the post-gasification header's back.
        double xs = x0 + .3, yp = yb + .4 * ht, ps = .05 + .012 * id;
        u.line(ps, Material.PIPE, new double[]{xs, ydr - rd + .05, zs}, new double[]{xs, yp + .6, zs}, new double[]{xs, yp + .6, za + r + .5}, new double[]{xs, yp,
                za + Math.sqrt(Math.max(0, (r + .3) * (r + .3) - (xs - a) * (xs - a)))});
    }

    // ---- The feed ---------------------------------------------------------------------------------------------------

    /** The bottom of stack vessel {@code k} (0 the charge hopper, 1 the lock hopper, 2 the feed bin): its cone's outlet. */
    double vesselBottom(int k) {
        return ys + rsc + .35 + k * (hv + hval);
    }

    /** Vessel {@code k}'s top tangent line. */
    double tangentTop(int k) {
        return vesselBottom(k) + cone + cyl;
    }

    /** The feed bin's top, m over grade. */
    double binTop() {
        return vesselBottom(2) + hv;
    }

    /**
     * The lock-hopper stack: the feed bin, the lock hopper and the charge hopper, each a 60° cone, a cylinder and a 2:1
     * top head, hung from the floors round them on four lugs; a lock valve between each two (a Class 300 ball valve's
     * body with its bonnet, a hydraulic cylinder whose rod strokes); the charge hopper's outlet split to the screws.
     */
    void stack() {
        double rv = dv / 2, rn = .1 + .05 * dv;
        var up = Frame.of(new double[]{a, 0, zl}, 1, 1);
        int n = ProcessUnit.sides(dv);
        double[] axis = {a, 0, zl};
        for (int k = 0; k < 3; k++) {
            double y0 = vesselBottom(k), y1 = y0 + cone, y2 = y1 + cyl;
            u.mesh.frustum(up, rn, rv, y0, y1, n, Material.SHELL);
            u.mesh.prism(up, rv, y1, y2, n, Material.SHELL);
            u.mesh.head(up, rv, y2, dv / 4, n, Material.HEAD);
            u.mesh.hoop(up, rv, rv + .05, y1 - .05, y1 + .1, n, Material.SHELL);
            u.mesh.annulus(up, 0, rn, y0, 12, -1, Material.SHELL);
            u.solids.add(Solid.cone(axis, 1, y0, y1, rn, rv, 0));
            u.solids.add(Solid.rod(axis, 1, y1, y2, rv, dv / 4));
            u.volumes.add(Solid.cone(axis, 1, y0 + .15, y1, .6 * rn, rv - .15, 0));
            u.volumes.add(Solid.rod(axis, 1, y1 - .05, y2, rv - .15, dv / 4 - .15));
            u.round(a, zl, y1, y2, rv, 1);
            // Four lugs at the top tangent.
            for (int j = 0; j < 4; j++) {
                double t = Math.PI / 4 + Math.PI / 2 * j, c = DeterministicMath.cos(t), s = DeterministicMath.sin(t);
                u.mesh.block(new double[]{a + (rv + .12) * c, y2 - .25, zl + (rv + .12) * s}, new double[]{.12 * c, 0, .12 * s}, new double[]{0, .2, 0},
                        new double[]{-.08 * s, 0, .08 * c}, Material.SHELL);
            }
            if (k < 2) valve(a, zl, y2 + dv / 4, hval, rn, 1, .25 * k);
        }
        // The charge hopper's outlet: a neck into one screw, or a breeches piece into two.
        double y0 = vesselBottom(0), top = ys + rsc - .05;
        if (screws == 1) u.mesh.cylinder(up, rn, top, y0, 12, Material.SHELL);
        else {
            double s = .25 * dv;
            u.box(new double[]{a - s - rn, y0 - .35, zl - rn}, new double[]{a + s + rn, y0, zl + rn}, Material.SHELL, true);
            for (int k = -1; k <= 1; k += 2) u.mesh.cylinder(Frame.of(new double[]{a + k * s, 0, zl}, 1, 1), .7 * rn, top, y0 - .35, 12, Material.SHELL);
        }
    }

    /**
     * A lock valve on the axis at (x, z) from {@code y} up {@code h}: its body and flanges, the bonnet, a hydraulic cylinder
     * out to {@code side} (±x) along x whose rod strokes {@link #STROKE} (a slider: owner D4, survey: the lock valves'
     * actuators stroke).
     */
    void valve(double x, double z, double y, double h, double rn, int side, double phase) {
        double rb = rn + .12 + .03 * id, ym = y + h / 2;
        var up = Frame.of(new double[]{x, 0, z}, 1, 1);
        u.mesh.cylinder(up, rb + .08, y, y + .1, 12, Material.FLANGE);
        u.mesh.cylinder(up, rb + .08, y + h - .1, y + h, 12, Material.FLANGE);
        u.mesh.sphere(new double[]{x, ym, z}, rb, 12, Material.MACHINE);
        u.mesh.cylinder(up, rn, y + .1, y + h - .1, 12, Material.MACHINE);
        u.solids.add(Solid.rod(new double[]{x, 0, z}, 1, y, y + h, rb + .08, 0));
        // The stem's bonnet out to the side, the cylinder beyond it, the rod moving in its guide.
        double b0 = x + side * rb, b1 = b0 + side * .2, c0 = b1 + side * (.2 + STROKE), c1 = c0 + side * (.5 + STROKE);
        var f = Frame.of(new double[]{0, ym, z}, 0, side);
        u.mesh.cylinder(f, .1, side * b0, side * b1, 10, Material.MACHINE);
        u.box(new double[]{Math.min(b1, b1 + side * .2), ym - .12, z - .12}, new double[]{Math.max(b1, b1 + side * .2), ym + .12, z + .12}, Material.MACHINE, false);
        u.mesh.cylinder(f, .1 + .01 * id, side * c0, side * c1, 10, Material.ACTUATOR);
        u.mesh.annulus(f, 0, .1 + .01 * id, side * c1, 10, 1, Material.ACTUATOR);
        u.mesh.annulus(f, 0, .1 + .01 * id, side * c0, 10, -1, Material.ACTUATOR);
        u.solids.add(Solid.box(new double[]{Math.min(b0, c1), ym - .15, z - .15}, new double[]{Math.max(b0, c1), ym + .15, z + .15}));
        int part = u.motions.size();
        u.mesh.part(part);
        u.mesh.cylinder(f, .035, side * (b1 + side * .05), side * (c0 + side * (.15 + STROKE)), 8, Material.PIPE);
        u.mesh.part(-1);
        u.motions.add(new Motion(new double[]{(b1 + c0) / 2, ym, z}, new double[]{side, 0, 0}, VALVE_SPEED, phase, STROKE / 2, 1));
    }

    /**
     * The screw feeders: from under the charge hopper's outlet into the vessel's front 1.5 m over the distributor (survey:
     * 1 to 2 m), one, or two side by side over 3.5 m ID; each driven at its front end ({@link FluidizedBedParts#drive}, the
     * coupling turning) on the feed floor; its flange at the vessel.
     */
    void screws() {
        double z0 = zl - rsc - .45;
        for (int k = 0; k < screws; k++) {
            double x = screws == 1 ? a : a + (2 * k - 1) * .25 * dv, z1 = za - Math.sqrt(Math.max(0, r * r - (x - a) * (x - a))) + .1;
            var f = Frame.of(new double[]{x, ys, 0}, 2, 1);
            u.mesh.cylinder(f, rsc, z0, z1, 12, Material.MACHINE);
            u.mesh.annulus(f, 0, rsc, z0, 12, -1, Material.MACHINE);
            for (double z : new double[]{z0 + .05, z1 - .45}) u.mesh.hoop(f, rsc, rsc + .07, z, z + .1, 12, Material.FLANGE);
            u.solids.add(Solid.rod(new double[]{x, ys, 0}, 2, z0, z1, rsc + .07, 0));
            FluidizedBedParts.drive(u, x, ys, z0, rm, feedFloor + Access.DECK);
        }
    }

    // ---- The ash system -------------------------------------------------------------------------------------------------

    /**
     * Under the vessel: the water-cooled ash screw (its jacket's water lines on top) from the bottom outlet back to the ash
     * lock hopper behind the vessel, its drive at the front turning; the ash lock (half the ID across, 1.6 times as tall:
     * a 2:1 top head, a cylinder and a 60° cone) between its top and bottom lock valves, on four legs.
     */
    void ash() {
        double z0 = za - .4, z1 = zal + .3, rs = dsa / 2;
        var f = Frame.of(new double[]{a, ysa, 0}, 2, 1);
        u.mesh.cylinder(f, rs, z0, z1, 12, Material.MACHINE);
        u.mesh.annulus(f, 0, rs, z1, 12, 1, Material.MACHINE);
        for (double z : new double[]{z0 + .05, z1 - .2}) u.mesh.hoop(f, rs, rs + .07, z, z + .1, 12, Material.FLANGE);
        u.solids.add(Solid.rod(new double[]{a, ysa, 0}, 2, z0, z1, rs + .07, 0));
        u.mesh.cylinder(Frame.of(new double[]{a, 0, za}, 1, 1), outlet(), ysa + rs - .05, yb - hd - .4, 12, Material.SHELL);
        for (int s = -1; s <= 1; s += 2)
            u.mesh.cylinder(Frame.of(new double[]{a + s * .5 * rs, ysa + rs + .04, 0}, 2, 1), .04, z0 + .3, z1 - .3, 6, Material.PIPE);
        FluidizedBedParts.drive(u, a, ysa, z0, Math.max(.2, Math.min(.3, .15 + .025 * id)), ysa - rs - .2);
        // The chute, the top valve, the lock, the bottom valve and the outlet.
        double rn = .1 + .05 * dl, yv1 = ysa - rs - .3, rl = dl / 2, coneH = .87 * (rl - rn), headD = dl / 4;
        u.mesh.cylinder(Frame.of(new double[]{a, 0, zal}, 1, 1), rn, yv1, ysa - rs + .05, 12, Material.SHELL);
        valve(a, zal, yv1 - .5, .5, rn, -1, .5);
        double top = yv1 - .5, y2 = top - headD, y1 = top - hl, yc = y1 + coneH;
        var up = Frame.of(new double[]{a, 0, zal}, 1, 1);
        int n = ProcessUnit.sides(dl);
        u.mesh.frustum(up, rn, rl, y1, yc, n, Material.SHELL);
        u.mesh.prism(up, rl, yc, y2, n, Material.SHELL);
        u.mesh.head(up, rl, y2, headD, n, Material.HEAD);
        u.mesh.annulus(up, 0, rn, y1, 12, -1, Material.SHELL);
        double[] axis = {a, 0, zal};
        u.solids.add(Solid.cone(axis, 1, y1, yc, rn, rl, 0));
        u.solids.add(Solid.rod(axis, 1, yc, y2, rl, headD));
        u.volumes.add(Solid.cone(axis, 1, y1 + .15, yc, .6 * rn, rl - .12, 0));
        u.volumes.add(Solid.rod(axis, 1, yc - .05, y2, rl - .12, headD - .12));
        u.round(a, zal, yc, y2, rl, 1);
        valve(a, zal, y1 - .5, .5, rn, 1, 0);
        u.mesh.cylinder(Frame.of(new double[]{a, 0, zal}, 1, 1), rn, y1 - .8, y1 - .5, 12, Material.SHELL);
        // The legs, clear of the outlet's pipe block.
        double lx = Math.max(.62, .42 * dl), legTop = yc + .3;
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2) {
                double x = a + sx * lx, z = zal + sz * lx;
                u.post(x, z, 0, legTop, .12, false);
                u.mesh.bar(new double[]{x, legTop - .1, z}, new double[]{a + sx * .7 * rl, legTop - .1, zal + sz * .7 * rl}, .1, new double[]{0, 1, 0}, Material.STEELWORK);
            }
    }

    /** The ash lock's outlet's flange face height: the outlet's bottom. */
    double ashOutlet() {
        return ysa - dsa / 2 - .3 - .5 - hl - .5 - .3;
    }

    // ---- The structure and its access -----------------------------------------------------------------------------------

    /** The structure's floors: the stack's at its lock valves and the support floor, the vessel's every 8 m or less to the top. */
    List<Double> vesselLevels() {
        var out = new ArrayList<Double>();
        double top = Math.floor(yt - 1.6);
        int k = Math.max(1, (int) Math.ceil((top - ring) / 8 - 1e-9));
        for (int j = 0; j <= k; j++) out.add(ring + Math.round((top - ring) * j / k));
        return out;
    }

    /** The stack's floors: under each lock valve, at the lower vessel's top tangent line. */
    List<Double> stackLevels() {
        return List.of(tangentTop(0) - Access.DECK, tangentTop(1) - Access.DECK);
    }

    /**
     * The open structure: six columns (the stack's bay in front, the vessel's and the cooler's behind), fireproofed in
     * concrete up to the support floor (survey: a syngas fire area); girders round both bays at the support floor and the
     * top, round the stack's bay at its floors; beams from the support ring out to the corner columns; X-bracing in the
     * vessel bay's sides and back under the support floor.
     */
    void frame() {
        double[] xs = {a - xm, a + xp}, zs3 = {zf, zm, zr};
        double half = hc / 2, fire = half + .1;
        for (double x : xs)
            for (double z : zs3) {
                u.box(new double[]{x - fire - .15, 0, z - fire - .15}, new double[]{x + fire + .15, .4, z + fire + .15}, Mesh.ALL_FACES & ~4, Material.CONCRETE, true);
                double[] lo = {x - fire, .4, z - fire}, hi = {x + fire, ring - .5, z + fire};
                u.mesh.box(lo, hi, Mesh.ALL_FACES & ~4, Material.CONCRETE);
                u.solids.add(Solid.box(lo, hi));
                double[] slo = {x - half, ring - .5, z - half}, shi = {x + half, frameTop, z + half};
                u.mesh.box(slo, shi, Mesh.ALL_FACES & ~4, Material.STEELWORK);
                u.solids.add(Solid.box(slo, shi));
            }
        // Girders: round both bays at the support floor and the top, round the stack's bay at its floors.
        var levels = new ArrayList<double[]>();
        levels.add(new double[]{ring, zf, zr});
        levels.add(new double[]{frameTop, zf, zr});
        for (double level : stackLevels()) levels.add(new double[]{level, zf, zm});
        for (var lv : levels) {
            double y1 = lv[0], y0 = y1 - .5;
            for (double z : zs3)
                if (z >= lv[1] - .01 && z <= lv[2] + .01) u.box(new double[]{a - xm + half, y0, z - .12}, new double[]{a + xp - half, y1, z + .12}, Material.STEELWORK, true);
            for (double x : xs) u.box(new double[]{x - .12, y0, lv[1] + half}, new double[]{x + .12, y1, lv[2] - half}, Material.STEELWORK, true);
        }
        // The support ring's beams out to the vessel bay's corners.
        for (double x : xs)
            for (double z : new double[]{zm, zr}) {
                double dx = x - a, dz = z - za, l = Math.sqrt(dx * dx + dz * dz);
                u.mesh.bar(new double[]{a + dx / l * (r + .3), ring - .8, za + dz / l * (r + .3)}, new double[]{x - Math.signum(dx) * half, ring - .8, z - Math.signum(dz) * half},
                        .3, new double[]{0, 1, 0}, Material.STEELWORK);
            }
        // The cyclones' beams' girder on the left.
        double yc0 = roof - 1.25 * dcy - .45;
        u.box(new double[]{a + xp - .12, yc0, zm + half}, new double[]{a + xp + .12, yc0 + .35, zr - half}, Material.STEELWORK, true);
        // X-bracing in the vessel bay's sides and back, under the support floor.
        for (double x : xs) u.brace(new double[]{x, zm + fire}, new double[]{x, zr - fire}, .5, ring - .6);
        u.brace(new double[]{a - xm + fire, zr}, new double[]{a + xp - fire, zr}, .5, ring - .6);
    }

    /**
     * Decks and ladders (caged, as tall slender units keep them): round the vessel at the support floor (a whole ring)
     * and every 8 m or less to its top (between the ladders), round the stack under each lock valve; the feed floor
     * under the screws' drives. The ladders alternate either side of the right (-x), each from the deck the one before
     * reached: the vessel's from the ground, the stack's from the feed floor, the feed floor's walked through from grade.
     * Rest platforms ({@link #rests}) break every climb longer than {@link Access#MAX_CLIMB}: arcs between the ladders
     * round the vessel and the stack; under the feed floor (the highest support, 16 m, needs one at most) the kit's flat
     * face climb ({@link ProcessUnit#climb}): a deck in front of its edge on four posts, the climb from grade walked
     * through onto its front 1.2 m to the left, the next one up from its back onto the feed floor.
     */
    void access() {
        // The feed floor: against the vessel's front, under the screws' drives; its ladder lands at its front's right.
        double back = za - r - .5, depth = back - zFront, ladderX = a - xw + .6;
        u.platform(new Access.Line(new double[]{a + xw, 0, back}, new double[]{-1, 0, 0}, new double[]{0, 0, -1}, 2 * xw), feedFloor, depth, true, true,
                a + xw - ladderX - Access.WALK_THROUGH, a + xw - ladderX + Access.WALK_THROUGH);
        u.climb(new Access.Line(new double[]{ladderX - .6, 0, zFront}, new double[]{1, 0, 0}, new double[]{0, 0, -1}, 2.4), 0, feedFloor + Access.DECK, 1.8, .6, .15);
        // The vessel's decks and ladders, the stack's.
        round(a, za, r + .45, vesselLevels(), 0, true);
        round(a, zl, dv / 2, stackLevels(), feedFloor + Access.DECK, false);
    }

    /**
     * The deck levels of caged climbs from walking level {@code base} (the ground 0, or a deck's) up through
     * {@code levels} (deck levels, rising): each of them, and before each the rest platforms that split its climb where
     * it would be longer than {@link Access#MAX_CLIMB} (owner decision C1 2026-10-18), {@link Access#climbs} over its
     * whole metres: equal climbs of at most 9 m to the nearest metre, the last one's grating over it.
     */
    static List<Double> rests(double base, List<Double> levels) {
        var out = new ArrayList<Double>();
        double from = base;
        for (double level : levels) {
            var stops = Access.climbs(0, List.of(), (int) Math.ceil(level - from - 1e-9));
            for (int i = 0; i + 1 < stops.size(); i++) out.add(from + stops.get(i) - Access.DECK);
            out.add(level);
            from = level + Access.DECK;
        }
        return out;
    }

    /**
     * Decks round a standing body (axis at (x, z), {@code wall} its deck's inner edge) at {@code levels} and the rest
     * platforms between them ({@link #rests}), by the kit's ring access ({@link ProcessUnit#ringAccess}): its ladders at
     * the right ± {@link #LADDER} alternately from {@code base}, every deck an arc from its arriving ladder round past the
     * right to the next one; the first of {@code levels} a whole ring open at its ladder when {@code ring}; the last half
     * a ring round the right from its ladder, closed at its far end.
     */
    void round(double x, double z, double wall, List<Double> levels, double base, boolean ring) {
        var all = rests(base, levels);
        double clear = Access.clearance(wall), span = 2 * LADDER;
        var rings = new ArrayList<Ring>();
        for (int k = 0; k < all.size(); k++) {
            double level = all.get(k), in = Math.PI + (k % 2 == 0 ? -LADDER : LADDER);
            if (ring && level == levels.getFirst()) rings.add(new Ring(level, in, 0, 2 * Math.PI));
            else if (k == all.size() - 1) rings.add(in < Math.PI ? new Ring(level, in, in - Math.PI / 2, in) : new Ring(level, in, in, in + Math.PI / 2));
            else rings.add(in < Math.PI ? new Ring(level, in, in, in + clear + span) : new Ring(level, in, in - clear - span, in));
        }
        u.ringAccess(x, z, wall, WIDTH, base, rings);
    }

    // ---- Nozzles ------------------------------------------------------------------------------------------------------

    /** A round surface's sagitta under a flange {@code od} across, radius {@code r}. */
    static double sag(double r, double od) {
        return r - Math.sqrt(Math.max(0, r * r - od * od / 4));
    }

    /**
     * The nozzles, each at a line's end ({@link ProcessUnit#end}) or out of a round side ({@link ProcessUnit#radialSide}):
     * OXIDANT up the right front of the vessel from the distributor's header to the front 1.5 m over grade; SYNGAS out of
     * the cooler's bottom to the back; FEEDWATER and BLOWDOWN at the drum's ends, STEAM on its top (survey: 0.04 ID);
     * NITROGEN and VENT up past the bin's top from the lock hopper's front and left; SOLIDS_INLET up out of the bin's top;
     * SOLIDS_OUTLET down out of the ash lock.
     */
    void nozzles() {
        // OXIDANT: survey clamp(0.05 ID, 0.05, 0.25).
        double ox = Math.max(.05, Math.min(.25, .05 * id)), ro = r + .3, xo = Math.floor(a - .56 * ro) + .5, zo = Math.floor(za - Math.sqrt(ro * ro - (a - xo) * (a - xo))) + .5;
        double yo = distributor();
        u.line(header() / 2, Material.PIPE, new double[]{xo, yo, za - Math.sqrt(Math.max(0, ro * ro - (a - xo) * (a - xo)))}, new double[]{xo, yo, zo}, new double[]{xo, 1.5, zo});
        u.nozzles.add(ProcessUnit.front(Role.OXIDANT, xo, 1.5, zo - header() / 2, ox));
        // SYNGAS: survey clamp(0.17 ID, 0.25, 1.0), held at a pipe's largest.
        double sg = Math.max(.25, Math.min(PipeSizes.MAX_OUTSIDE, .17 * id)), ysg = Math.floor(ysc0 + .3 + sg / 2) + .5;
        u.nozzles.add(ProcessUnit.radialSide(Role.SYNGAS, a, ysg, zs, dsc / 2, ProcessUnit.sides(dsc), 1, sg));
        // The drum's water and steam: 0.04 ID. Each neck rooted under the drum where a pipe's largest neck's edge meets it
        // (owner decision 2026-10-21: a 1 m pipe has a 1 m root); the flange faces stay put.
        double ws = Math.max(.05, .04 * id), rd = ddr / 2, x0 = a - lr / 2, x1 = a + lr / 2;
        double edge = PipeSizes.MAX_OUTSIDE / 2 / rd, dip = rd / 2 * (1 - Math.sqrt(Math.max(0, 1 - edge * edge))) + .03;
        double east = Math.ceil(x1 + rd / 2 + ProcessUnit.NECK), west = Math.floor(x0 - rd / 2 - ProcessUnit.NECK);
        u.nozzles.add(new EquipmentShape.Nozzle(Role.FEEDWATER, new double[]{x1 + rd / 2 - dip, ydr, zs}, 0, 1, east - (x1 + rd / 2 - dip), ws, 0));
        u.nozzles.add(new EquipmentShape.Nozzle(Role.BLOWDOWN, new double[]{x0 - rd / 2 + dip, ydr, zs}, 0, -1, x0 - rd / 2 + dip - west, ws, 0));
        double xst = Math.floor(a + .25 * lr) + .5, steamBase = ydr + rd - sag(rd, PipeSizes.MAX_OUTSIDE) - .03, steamFace = Math.ceil(ydr + rd - sag(rd, 1.5 * ws) + ProcessUnit.NECK);
        u.nozzles.add(new EquipmentShape.Nozzle(Role.STEAM, new double[]{xst, steamBase, zs}, 1, 1, steamFace - steamBase, ws, 0));
        // NITROGEN and VENT: from the lock hopper's cylinder foot out past the stack's decks, up past the bin's top beside
        // its inlet (lock gas in, depressurising gas out to the flare: survey 0.05 to 0.1 and 0.1 to 0.15 m).
        double yl = vesselBottom(1) + cone + .3, top = Math.ceil(binTop() + .15) - .2, reach = dv / 2 + 1.3;
        double xv = Math.ceil(a + reach - .5) + .5, zn = Math.floor(zl - reach - .5) + .5;
        u.line(.05, Material.PIPE, new double[]{a + dv / 2 - .05, yl, zl}, new double[]{xv, yl, zl}, new double[]{xv, top, zl});
        u.line(.04, Material.PIPE, new double[]{a, yl, zl - dv / 2 + .05}, new double[]{a, yl, zn}, new double[]{a, top, zn});
        u.nozzles.add(ProcessUnit.end(Role.VENT, new double[]{xv, top - .03, zl}, 1, 1, .1));
        u.nozzles.add(ProcessUnit.end(Role.NITROGEN, new double[]{a, top - .03, zn}, 1, 1, .08));
        // The solids: up out of the bin's head (rooted under the head where a pipe's largest neck's edge meets it) and down
        // out of the ash lock's outlet.
        double rb = dv / 2, binEdge = PipeSizes.MAX_OUTSIDE / 2 / rb, binDip = rb / 2 * (1 - Math.sqrt(Math.max(0, 1 - binEdge * binEdge))) + .03;
        u.nozzles.add(new EquipmentShape.Nozzle(Role.SOLIDS_INLET, new double[]{a, binTop() - binDip, zl}, 1, 1, Math.ceil(binTop() + ProcessUnit.NECK) - binTop() + binDip,
                .3, 0));
        u.nozzles.add(ProcessUnit.end(Role.SOLIDS_OUTLET, new double[]{a, ashOutlet() + .03, zal}, 1, -1, .3));
    }
}
