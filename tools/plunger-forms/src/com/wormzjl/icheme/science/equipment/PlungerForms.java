package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Motion;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Solid;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Scratch only (tmp/plunger-forms): candidate forms of the plunger pump (stage S8), drawn with the kit's own parts on a
 * host shape, written as polygons for render.py. Not a design: no kind, no layout, no solids worth keeping.
 */
public final class PlungerForms {
    static final double GROUT = .04, BASEPLATE = .16, XC = .5, YC = 1.5, ZM = 2.5;

    record Dim(double k, double cap) {
        double p() { return .40 * k; }
        double kw() { return cap / 3600 * 25e6 / .88 / 1e3; }
        double suction() { return PipeSizes.od(Math.sqrt(4 * cap / 3600 / (Math.PI * 1.5)), .05, .4); }
        double discharge() { return PipeSizes.od(Math.sqrt(4 * cap / 3600 / (Math.PI * 4)), .05, .4); }
        double[] zs() { return new double[]{ZM - p(), ZM, ZM + p()}; }
        double zA() { return ZM - 1.5 * p() - .04 * k; }
        double zB() { return ZM + 1.5 * p() + .04 * k; }
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);
        Files.createDirectories(out);
        double[] ks = {.55, .75, 1, 1.35}, caps = {3, 10, 30, 100};
        for (String form : new String[]{"A", "B", "C"})
            for (int i = 0; i < ks.length; i++) {
                var d = new Dim(ks[i], caps[i]);
                var shape = EquipmentShape.unit(EquipmentShape.Kind.DRY_SCREW_PUMP, 1, 1, EquipmentShape.Heading.NORTH, 0);
                var u = ProcessUnit.of(shape, unit -> {
                    switch (form) {
                        case "A" -> monoblock(unit, d);
                        case "B" -> pots(unit, d);
                        default -> vertical(unit, d);
                    }
                });
                write(out.resolve(form + "_" + (int) caps[i] + ".json"), u);
            }
    }

    static void write(Path file, ProcessUnit u) throws Exception {
        var sb = new StringBuilder("{\"polygons\":[");
        boolean first = true;
        var extra = new Mesh();
        for (var nz : u.nozzles()) {
            var ref = new double[3];
            ref[(nz.axis() + 1) % 3] = 1;
            var f = new Frame(nz.base(), nz.direction(), ref);
            double od = nz.defaultOd(), r = od / 2, t = PipeSizes.flangeThickness(od);
            int n = PipeSizes.sides(od);
            extra.prism(f, r, 0, nz.length(), n, Material.PIPE);
            extra.ring(f, r, PipeSizes.flange(od) / 2, nz.length() - t, nz.length(), n, 3, Material.FLANGE);
        }
        for (var mesh : new Mesh[]{u.mesh(), extra})
            for (var facet : mesh.facets()) {
                var p = Mesh.whole(facet);
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"m\":\"").append(p.material().name()).append("\",\"v\":[");
                var v = p.positions();
                for (int i = 0; i < v.length; i++) sb.append(i > 0 ? "," : "").append(String.format(Locale.ROOT, "%.4f", v[i]));
                sb.append("]}");
            }
        sb.append("]}");
        Files.writeString(file, sb);
        System.out.println(file.getFileName() + " " + u.nozzles().size() + " nozzles");
    }

    static double[] at(double x, double y, double z) { return new double[]{x, y, z}; }

    /** A bolted round cover on frame {@code f} from station {@code s} out by {@code t}. */
    static void cover(ProcessUnit u, Frame f, double r, double s, double t, int bolts) {
        u.mesh.band(f, r, r, s, s + t, 12, Material.PUMP_JOINT);
        u.mesh.disc(f, r, s + t, 12, 1, Material.PUMP_DISC);
        if (bolts > 0) u.mesh.bolts(f, Math.min(2 * r, .4), .45 * r, r, s + t, 1, bolts, Material.BOLT);
        else u.mesh.nut(f, .45 * r, s + t, s + 1.9 * t, 6, Material.BOLT);
    }

    /** The crank's bearing covers at both ends of a case from z0 to z1, the shaft leaving the back one. */
    static void bearings(ProcessUnit u, double k, double z0, double z1) {
        var along = Frame.of(at(XC, YC, 0), 2, 1);
        double rb = .3 * k, t = .06 * k;
        int n = ProcessUnit.sides(2 * rb);
        u.mesh.band(along, rb, rb, z0 - t, z0, n, Material.PUMP_JOINT);
        u.mesh.disc(along, rb, z0 - t, n, -1, Material.PUMP_DISC);
        u.mesh.band(along, rb, rb, z1, z1 + t, n, Material.PUMP_JOINT);
        u.mesh.annulus(along, 0, rb, z1 + t, n, 1, Material.PUMP_DISC);
    }

    /** The shaft end at the back, the baseplate from x0 to x1 on its grout and plinth. */
    static void drive(ProcessUnit u, Dim d, double z0, double z1, double x0, double x1, double least) {
        double k = d.k, deck = YC - .5 * k, base = deck - GROUT - BASEPLATE, zb = z1 + .06 * k;
        double rs = ProcessUnit.shaftRadius(d.kw(), 300), face = Math.max(least, Math.ceil(zb + .2 + rs - 1e-9));
        u.coupling(XC, YC, zb, face, 1, rs, deck, ProcessUnit.shown(300), EquipmentShape.ShaftEnd.Role.DRIVEN_INPUT);
        double za = z0 - .14 * k, zz = face - .01;
        u.baseplate(x0, x1, base + GROUT, deck, za, zz);
        u.box(at(x0 + .02, base, za + .02), at(x1 - .02, base + GROUT, zz - .02), Material.CONCRETE, false);
        u.driverBase(x0 - .08, x1 + .08, za - .08, zz, base, 0);
    }

    /** A horizontal power end: the crank along z, the crosshead guides out of its nose towards +x as far as xg. */
    static void powerEnd(ProcessUnit u, Dim d, double z0, double z1, double nose, double xg) {
        double k = d.k, b = YC - .5 * k;
        u.extrude(List.of(at(XC - .55 * k, b, 0), at(nose, b, 0), at(nose, YC + .3 * k, 0), at(XC + .25 * k, YC + .5 * k, 0), at(XC - .3 * k, YC + .5 * k, 0),
                at(XC - .55 * k, YC + .25 * k, 0)), z0, z1, Material.PUMP_CASTING);
        u.solids.add(Solid.box(at(XC - .55 * k, b, z0), at(nose, YC + .5 * k, z1)));
        u.solids.add(Solid.box(at(nose, YC - .2 * k, d.zA()), at(xg, YC + .2 * k, d.zB())));
        u.box(at(XC - .26 * k, YC + .5 * k, z0 + .12 * k), at(XC + .2 * k, YC + .53 * k, z1 - .12 * k), Material.PUMP_JOINT, false);
        for (double z : new double[]{z0 + (z1 - z0) / 3, z1 - (z1 - z0) / 3})
            u.box(at(XC - .58 * k, b + .08 * k, z - .03 * k), at(XC - .55 * k, YC + .22 * k, z + .03 * k), Material.PUMP_CASTING, false);
        u.mesh.cylinder(Frame.of(at(XC - .38 * k, YC - .2 * k, 0), 2, 1), .05 * k, z0 - .03 * k, z0, 8, Material.GLASS);
        u.mesh.cylinder(Frame.of(at(XC - .1 * k, 0, z0 + .3 * k), 1, 1), .045 * k, YC + .53 * k, YC + .68 * k, 8, Material.PUMP_CASTING);
        bearings(u, k, z0, z1);
        for (double z : new double[]{z0 + .08 * k, z1 - .08 * k})
            u.box(at(XC - .62 * k, b, z - .08 * k), at(nose + .06 * k, b + .08 * k, z + .08 * k), Material.PUMP_CASTING, false);
        for (double z : d.zs()) {
            var f = Frame.of(at(0, YC, z), 0, 1);
            u.mesh.cylinder(f, .17 * k, nose, xg, 12, Material.PUMP_CASTING);
            u.mesh.ring(f, .17 * k, .2 * k, xg - .04 * k, xg, 12, 3, Material.PUMP_JOINT);
        }
        // Ribs on the nose between the guides.
        double[] zs = d.zs();
        for (double z : new double[]{(zs[0] + zs[1]) / 2, (zs[1] + zs[2]) / 2})
            u.box(at(nose, YC - .3 * k, z - .025 * k), at(nose + .3 * k, YC + .28 * k, z + .025 * k), Material.PUMP_CASTING, false);
    }

    /** The open cradle from the guides at xg to the liquid end at xf: its tray, the plungers in their stuffing boxes, the stay rods. */
    static void cradle(ProcessUnit u, Dim d, double xg, double xf) {
        double k = d.k, zA = d.zA(), zB = d.zB();
        u.solids.add(Solid.box(at(xg, YC - .32 * k, zA), at(xf, YC + .27 * k, zB)));
        u.box(at(xg - .02 * k, YC - .32 * k, zA), at(xf, YC - .27 * k, zB), Material.PUMP_CASTING, false);
        for (double z : new double[]{zA, zB - .035 * k})
            u.box(at(xg - .02 * k, YC - .32 * k, z), at(xf, YC + .02 * k, z + .035 * k), Material.PUMP_CASTING, false);
        double[] zs = d.zs();
        for (int i = 0; i < 3; i++) {
            var f = Frame.of(at(0, YC, zs[i]), 0, 1);
            u.mesh.cylinder(f, .1 * k, xf - .16 * k, xf, 12, Material.FLANGE);
            u.mesh.cylinder(f, .135 * k, xf - .2 * k, xf - .16 * k, 12, Material.FLANGE);
            u.mesh.part(u.motions.size());
            u.mesh.cylinder(f, .05 * k, xg - .05 * k, xf - .1 * k, 10, Material.SHAFT);
            u.mesh.part(-1);
            u.motions.add(new Motion(at((xg + xf) / 2, YC, zs[i]), at(1, 0, 0), ProcessUnit.CRANK_SPEED, i / 3.0, .06 * k, .3 * k));
        }
        for (double z : new double[]{zA + .06 * k, zB - .06 * k, (zs[0] + zs[1]) / 2, (zs[1] + zs[2]) / 2})
            u.mesh.cylinder(Frame.of(at(0, YC + .25 * k, z), 0, 1), .022 * k, xg - .03 * k, xf, 8, Material.BOLT);
    }

    /** A pulsation dampener: a neck up from (x, y, z) and a bladder sphere on it. */
    static void dampener(ProcessUnit u, double x, double y, double z, double neck, double r) {
        var up = Frame.of(at(x, 0, z), 1, 1);
        u.mesh.cylinder(up, .35 * r, y, y + neck, 10, Material.PUMP_CASTING);
        u.mesh.ring(up, .35 * r, .55 * r, y + neck - .04, y + neck, 12, 3, Material.FLANGE);
        u.mesh.sphere(at(x, y + neck + .92 * r, z), r, 16, Material.HEAD);
        u.mesh.cylinder(up, .12 * r, y + neck + 1.9 * r, y + neck + 2.08 * r, 6, Material.BOLT);
        u.solids.add(Solid.rod(at(x, 0, z), 1, y, y + neck + 1.92 * r, r, 0));
    }

    /** A line of radius r from the liquid end at (x, y, z) along z (sign s) to a riser at a cell centre and up to a flange. Returns the riser's z. */
    static double riser(ProcessUnit u, Role role, double x, double y, double z, int s, double clear, double od) {
        double r = od / 2 + .015, bend = Math.max(.15, 2 * r);
        double want = z + s * (clear + bend), zn = s > 0 ? Math.ceil(want - .5) + .5 : Math.floor(want - .5) + .5;
        double out = .05 + PipeSizes.flangeThickness(od) + EquipmentShape.STAND_OUT, face = Math.ceil(y + bend + r + .05 + out - 1e-9), from = face - out;
        u.pipe(r, Material.PUMP_CASTING, true, at(x, y, z), at(x, y, zn), at(x, from, zn));
        u.pumpNozzle(role, at(x, 0, zn), 1, 1, from, face, od, od / 2);
        return zn;
    }

    /** Form A: a horizontal triplex with a monoblock liquid end overhanging the skid. */
    static void monoblock(ProcessUnit u, Dim d) {
        double k = d.k, p = d.p(), z0 = ZM - 1.5 * p - .25 * k, z1 = ZM + 1.5 * p + .25 * k, nose = XC + .45 * k, xg = XC + .85 * k;
        double xd = Math.ceil(XC + 1.3 * k - .5) + .5, xf0 = xd - .275 * k, xf1 = xd + .275 * k, top = YC + .4 * k, bot = YC - .4 * k, zA = d.zA(), zB = d.zB();
        powerEnd(u, d, z0, z1, nose, xg);
        cradle(u, d, xg, xf0);
        u.box(at(xf0, bot, zA), at(xf1, top, zB), Material.PUMP_CASTING, true);
        double rm = d.suction() / 2 + .015, ym = bot - rm - .05 * k;
        for (double z : d.zs()) {
            cover(u, Frame.of(at(0, YC, z), 0, 1), .15 * k, xf1, .05 * k, 6);
            cover(u, Frame.of(at(xd, 0, z), 1, 1), .14 * k, top, .06 * k, 0);
            u.mesh.cylinder(Frame.of(at(xd, 0, z), 1, 1), .8 * rm, ym, bot, 10, Material.PUMP_CASTING);
        }
        // The suction manifold under the block, its riser at the front; the discharge out of the back end, its dampener on a tee.
        u.mesh.disc(Frame.of(at(xd, ym, 0), 2, 1), rm, zB, 12, 1, Material.PUMP_CASTING);
        riser(u, Role.SUCTION, xd, ym, zB, -1, zB - zA + .12, d.suction());
        double rd = d.discharge() / 2 + .015, yd = YC, rb = .17 * k + .04;
        double zr = riser(u, Role.DISCHARGE, xd, yd, zB - .02, 1, 2 * rb + .05, d.discharge());
        dampener(u, xd, yd, zB + rb + .04, .12 * k + rd, rb);
        // A shaft block stands two cells clear of a pipe block: where the risers are in the cell row next to the shaft line, the shaft end reaches a block further back.
        drive(u, d, z0, z1, XC - .65 * k, xf0 - .06 * k, Math.floor(xd) - Math.floor(XC) < 2 ? zr + 1.5 : 0);
    }

    /** A standing pulsation bottle at (x, z) from y0 to y1 on a pedestal, a flange on its top. */
    static void bottle(ProcessUnit u, Role role, double x, double z, double r, double y0, double least, double od) {
        // Its top where its flange face lands on a block face with the least neck.
        double out = .02 + PipeSizes.flangeThickness(od) + EquipmentShape.STAND_OUT, y1 = Math.ceil(least + r / 2 + .06 + out) - out - .06 - r / 2;
        var up = Frame.of(at(x, 0, z), 1, 1);
        int n = ProcessUnit.sides(2 * r);
        double depth = r / 2;
        u.mesh.prism(up, r, y0, y1, n, Material.SHELL);
        u.mesh.head(up, r, y1, depth, n, Material.HEAD);
        u.mesh.head(Frame.of(at(x, 0, z), 1, -1), r, -y0, depth, n, Material.HEAD);
        u.mesh.cylinder(up, .55 * r, 0, y0 - .5 * depth, 12, Material.STEELWORK);
        u.mesh.cylinder(up, .8 * r, 0, .03, 12, Material.STEELWORK);
        double rn = od / 2 + .03, from = y1 + depth + .06;
        u.mesh.cylinder(up, rn, y1 + .5 * depth, from, 12, Material.SHELL);
        u.pumpNozzle(role, at(x, 0, z), 1, 1, from, Math.rint(from + out), od, od / 2);
    }

    /** Form B: a horizontal triplex, each plunger in its own valve pot between a suction and a discharge manifold, a standing bottle on each. */
    static void pots(ProcessUnit u, Dim d) {
        double k = d.k, p = d.p(), z0 = ZM - 1.5 * p - .25 * k, z1 = ZM + 1.5 * p + .25 * k, nose = XC + .45 * k, xg = XC + .85 * k;
        double xp = Math.ceil(XC + 1.35 * k - .5) + .5, xb = xp - .32 * k, rpot = .17 * k, lo = YC - .55 * k, hi = YC + .55 * k;
        powerEnd(u, d, z0, z1, nose, xg);
        cradle(u, d, xg, xb);
        double rm = d.suction() / 2 + .015, rd = d.discharge() / 2 + .015, ym = lo - .08 * k - rm, yd = hi + .08 * k + rd;
        double[] zs = d.zs();
        for (double z : zs) {
            var fx = Frame.of(at(0, YC, z), 0, 1);
            var up = Frame.of(at(xp, 0, z), 1, 1);
            u.mesh.cylinder(fx, .135 * k, xb, xp + .27 * k, 12, Material.PUMP_CASTING);
            cover(u, fx, .16 * k, xp + .27 * k, .05 * k, 6);
            u.mesh.cylinder(up, rpot, lo, hi, 12, Material.PUMP_CASTING);
            u.mesh.ring(up, rpot, rpot + .035 * k, hi - .05 * k, hi, 12, 3, Material.PUMP_JOINT);
            u.mesh.ring(up, rpot, rpot + .035 * k, lo, lo + .05 * k, 12, 3, Material.PUMP_JOINT);
            u.mesh.cylinder(up, .8 * rd, hi, yd, 10, Material.PUMP_CASTING);
            u.mesh.cylinder(up, .8 * rm, ym, lo, 10, Material.PUMP_CASTING);
            for (double y : new double[]{YC - .37 * k, YC + .37 * k}) cover(u, Frame.of(at(0, y, z), 0, 1), .095 * k, xp + rpot - .02 * k, .07 * k, 0);
        }
        // The manifolds: the suction under the pots to the front bottle, the discharge over them to the back bottle.
        double rbs = .13 * k + rm + .05, rbd = .1 * k + rd + .05;
        double zbs = Math.floor(zs[0] - rpot - rbs - .1 - .5) + .5, zbd = Math.ceil(zs[2] + rpot + rbd + .1 - .5) + .5;
        u.pipe(rm, Material.PUMP_CASTING, false, at(xp, ym, zs[2] + .22 * k), at(xp, ym, zbs));
        u.mesh.disc(Frame.of(at(xp, ym, 0), 2, 1), rm, zs[2] + .22 * k, 12, 1, Material.PUMP_CASTING);
        u.pipe(rd, Material.PUMP_CASTING, false, at(xp, yd, zs[0] - .22 * k), at(xp, yd, zbd));
        u.mesh.disc(Frame.of(at(xp, yd, 0), 2, 1), rd, zs[0] - .22 * k, 12, -1, Material.PUMP_CASTING);
        bottle(u, Role.SUCTION, xp, zbs, rbs, Math.min(ym - rm - .1, .5), YC + .45 * k, d.suction());
        bottle(u, Role.DISCHARGE, xp, zbd, rbd, Math.min(YC - .4 * k, .6), yd + rd + .3 * k, d.discharge());
        // Saddles under the suction manifold.
        for (double z : new double[]{zs[0] - .5 * p, zs[2] + .5 * p})
            u.box(at(xp - .14 * k, 0, z - .04 * k), at(xp + .14 * k, ym - .6 * rm, z + .04 * k), Material.STEELWORK, false);
        drive(u, d, z0, z1, XC - .65 * k, xb - .08 * k, 0);
    }

    /** Form C: a vertical triplex, the liquid end on a lantern over the crankcase. */
    static void vertical(ProcessUnit u, Dim d) {
        double k = d.k, p = d.p(), z0 = ZM - 1.5 * p - .2 * k, z1 = ZM + 1.5 * p + .2 * k, b = YC - .5 * k, zA = d.zA(), zB = d.zB();
        u.extrude(List.of(at(XC - .5 * k, b, 0), at(XC + .5 * k, b, 0), at(XC + .5 * k, YC + .25 * k, 0), at(XC + .3 * k, YC + .45 * k, 0), at(XC - .3 * k, YC + .45 * k, 0),
                at(XC - .5 * k, YC + .25 * k, 0)), z0, z1, Material.PUMP_CASTING);
        bearings(u, k, z0, z1);
        double[] zs = d.zs();
        double yg = YC + .85 * k, f0 = yg + .45 * k, f1 = f0 + .75 * k;
        for (int i = 0; i < 3; i++) {
            double z = zs[i];
            var up = Frame.of(at(XC, 0, z), 1, 1);
            for (int side = -1; side <= 1; side += 2)
                u.box(at(XC + side * .5 * k - .015 * k, YC - .32 * k, z - .14 * k), at(XC + side * .5 * k + .015 * k, YC + .14 * k, z + .14 * k), Material.PUMP_JOINT, false);
            u.mesh.cylinder(up, .17 * k, YC + .45 * k, yg, 12, Material.PUMP_CASTING);
            u.mesh.ring(up, .17 * k, .2 * k, yg - .04 * k, yg, 12, 3, Material.PUMP_JOINT);
            u.mesh.cylinder(up, .1 * k, f0 - .16 * k, f0, 12, Material.FLANGE);
            u.mesh.cylinder(up, .135 * k, f0 - .2 * k, f0 - .16 * k, 12, Material.FLANGE);
            u.mesh.part(u.motions.size());
            u.mesh.cylinder(up, .05 * k, yg - .05 * k, f0 - .1 * k, 10, Material.SHAFT);
            u.mesh.part(-1);
            u.motions.add(new Motion(at(XC, (yg + f0) / 2, z), at(0, 1, 0), ProcessUnit.CRANK_SPEED, i / 3.0, .06 * k, .3 * k));
            cover(u, up, .13 * k, f1, .06 * k, 0);
            for (double y : new double[]{f0 + .2 * k, f0 + .54 * k}) cover(u, Frame.of(at(0, y, z), 0, 1), .1 * k, XC + .3 * k, .05 * k, 6);
        }
        // The lantern's four columns, the liquid end on them.
        for (double x : new double[]{XC - .27 * k, XC + .27 * k})
            for (double z : new double[]{zA + .03 * k, zB - .03 * k}) u.mesh.cylinder(Frame.of(at(x, 0, z), 1, 1), .05 * k, YC + .43 * k, f0, 10, Material.PUMP_CASTING);
        u.box(at(XC - .32 * k, YC + .43 * k, zA - .02 * k), at(XC + .32 * k, YC + .47 * k, zB + .02 * k), Material.PUMP_JOINT, false);
        u.box(at(XC - .3 * k, f0, zA), at(XC + .3 * k, f1, zB), Material.PUMP_CASTING, true);
        // The suction into the back end (over the coupling), the discharge out of the front end with its dampener.
        double rd = d.discharge() / 2 + .015, rb = .17 * k + .04;
        riser(u, Role.SUCTION, XC, f0 + .2 * k, zB - .02, 1, .1, d.suction());
        riser(u, Role.DISCHARGE, XC, f0 + .52 * k, zA + .02, -1, 2 * rb + .1, d.discharge());
        dampener(u, XC, f0 + .52 * k, zA - rb - .04, .12 * k + rd, rb);
        drive(u, d, z0, z1, XC - .65 * k, XC + .65 * k, 0);
    }
}
