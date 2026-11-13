package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Nozzle;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Compressor;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Motion;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Solid;
import com.wormzjl.icheme.science.equipment.design.UnitRatings;
import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.ArrayList;
import java.util.List;

/**
 * An API 618 balanced-opposed compressor ({@link #build}): bore 0.15 to 1.2 m, 2 to
 * {@link ProcessUnit#MAX_THROWS} throws in opposed pairs, on a foundation high enough that its discharge bottles clear
 * grade by 0.3 m and its motor by 0.1 m.
 */
final class CompressorDesign extends UnitDesign {
    CompressorDesign() {
        // Computed in its overrides: minSupport.
        super(new Sizes(.15, 1.2, ProcessUnit.SUCTION_STEP,
                2, ProcessUnit.MAX_THROWS,
                Sizes.COMPUTED, EquipmentShape.MAX_SUPPORT,
                Orientation.HORIZONTAL, "bore", "throws", "foundation", true));
    }

    @Override
    public int lengthStep() {
        return 2;
    }

    @Override
    public int minSupport(double size, int length) {
        var c = ProcessUnit.Compressor.of(size, length);
        int h = 1;
        while (h < EquipmentShape.MAX_SUPPORT && !c.clears(h)) h++;
        return h;
    }

    @Override
    void check(double size, int length, int support, ProcessUnit.Bank bank) {
        if (length % 2 != 0) throw new IllegalArgumentException("A balanced-opposed compressor has an even number of throws, not " + length);
    }

    @Override
    public boolean lengthInMetres() {
        return false;
    }

    @Override
    public Line held(EquipmentShape shape) {
        double d = shape.diameter();
        return new Line("displacement", whole(UnitRatings.displacement(d, (int) shape.tangentLength())), whole(UnitRatings.compressorSpeed(d)));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double d = shape.diameter();
        int throwCount = (int) shape.tangentLength();
        return List.of(new Line("compressor", throwCount, size(d), size(UnitRatings.stroke(d))),
                new Line("displacement", whole(UnitRatings.displacement(d, throwCount)), whole(UnitRatings.compressorSpeed(d))));
    }

    /**
     * An API 618 balanced-opposed compressor (owner decision M4 2026-10-17; {@code MACHINES_HEATERS_SURVEY.md}), its
     * crankshaft along z on a steel table {@code support} high (owner request 2026-10-17: steel beams like the other
     * units). The frame, ribbed, a bolted cover over each throw, a breather, the oil pump's cover at its free end and the
     * lubricator on its side; on alternate sides of it, each throw's crosshead guide (a bolted door on top), its distance
     * piece (flanged at both ends, a window cover each side of each compartment, two compartments when long) and its
     * double-acting cylinder: a heavy flange at its crank end, valve caps at both ends on top (suction) and underneath
     * (discharge), one at each end of a small cylinder and two of a large (0.45 m bore up) beside its nozzles, a bolted head
     * and from 0.3 m bore a clearance pocket with its handwheel, an outboard support under the head. On each side a
     * suction bottle lies over the cylinders and a discharge bottle under them on saddled posts, both as long as the longer
     * side's, each joined to every cylinder by a flanged neck. One suction header crosses the machine over its outboard
     * (front) end and drops into both suction bottles, the inlet on top of its middle; one discharge header joins both discharge bottles'
     * front heads in front of the frame, the outlet at the front of its middle (owner request 2026-10-17: one inlet, one
     * outlet). The crankshaft's back end is its shaft end, a half coupling under its guard: its driver (a motor or a turbine)
     * joins it through a shaft block (owner decision D4: the synchronous motor and its exciter went). Sizes from
     * {@link Compressor}.
     */
    @Override
    void build(ProcessUnit u) {
        var c = Compressor.of(u.shape.diameter(), (int) u.shape.tangentLength());
        int throwCount = c.throwCount(), h = u.shape.support();
        double b = c.bore(), yc = h + c.crank(), ds = c.suction(), dd = c.discharge(), cyl = c.cylinder(), lc = c.cylinderLength(), gh = c.guide(), piece = c.piece();
        double ysb = c.suctionAxis(h), ydb = c.dischargeAxis(h), pocket = b >= .3 ? .3 + .3 * b : 0;
        double hs = c.suctionHeader(), hd = c.dischargeHeader();
        double a = ProcessUnit.centre(c.out() + Math.max(lc / 2 + .12 + pocket + (pocket > 0 ? .12 : 0), Math.max(ds, dd) / 2) + .05);
        // Along z from the frame's front: an auxiliary end of 0.6 m, the throws at the crank pitch, 0.6 m to the drive end.
        // The bottles span the cylinders of either side and two diameters at least.
        var zs = new double[throwCount];
        for (int k = 0; k < throwCount; k++) zs[k] = .6 + (k + .5) * c.pitch();
        double end = throwCount * c.pitch() + 1.2;
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (int side = 0; side < 2; side++) {
            lo = Math.min(lo, zs[side] - cyl / 2 - .1);
            hi = Math.max(hi, zs[throwCount - 2 + side] + cyl / 2 + .1);
        }
        double grow = Math.max(0, 2 * ds - (hi - lo)) / 2;
        lo -= grow;
        hi += grow;
        // The discharge header on a cell centre in front of the bottles' heads and the table; everything shifted by whole
        // metres (cell centres stay cell centres) so the outlet's flange starts at z 0.
        double depth = dd / 4, zd = Math.floor(Math.min(lo - depth - .1, -.25) - hd / 2 - .5) + .5;
        double shift = -Math.floor(zd - hd / 2 - ProcessUnit.NECK);
        for (int k = 0; k < throwCount; k++) zs[k] += shift;
        lo += shift;
        hi += shift;
        zd += shift;
        double front = shift;
        end += shift;
        // The steel table: posts at the frame's corners and between the throws, beams along its sides and across at each
        // post line; the frame on it, its ribs between the throws, a cover over each throw, the breather, the oil pump's
        // cover and the pump at the free end, the lubricator on its side.
        double half = c.half(), top = h + c.frameTop();
        var lines = new ArrayList<Double>();
        lines.add(front + .1);
        for (int k = 1; k < throwCount; k += 2) lines.add((zs[k] + (k + 1 < throwCount ? zs[k + 1] : end - .1)) / 2);
        if (lines.getLast() < end - .4) lines.add(end - .1);
        steelTable(u, a, half, front - .1, end + .1, h, lines, c.rail());
        u.box(new double[]{a - half, h, front}, new double[]{a + half, top, end}, Material.MACHINE, true);
        u.volumes.add(Solid.box(new double[]{a - half + .05, h + .05, front + .05}, new double[]{a + half - .05, top - .05, end - .05}));
        for (int k = 0; k < throwCount; k++) {
            u.box(new double[]{a - half + .08, top, zs[k] - .38 * c.pitch()}, new double[]{a + half - .08, top + .03, zs[k] + .38 * c.pitch()}, Material.MACHINE, false);
            u.mesh.bar(new double[]{a - .15, top + .05, zs[k]}, new double[]{a + .15, top + .05, zs[k]}, .03, new double[]{0, 1, 0}, Material.PIPE);
            // A rib on the side where this throw has no guide.
            double rx = a + (k % 2 == 0 ? 1 : -1) * half;
            u.box(new double[]{rx - .04, h + .05, zs[k] - .05}, new double[]{rx + .04, top - .05, zs[k] + .05}, Material.MACHINE, false);
        }
        u.mesh.cylinder(Frame.of(new double[]{a + .5 * half, 0, front + .3}, 1, 1), .06 + .06 * b, top, top + .15 + .1 * b, 10, Material.MACHINE);
        u.mesh.cylinder(Frame.of(new double[]{a, yc, 0}, 2, 1), .25 + .15 * b, front - .06, front, ProcessUnit.sides(.5 + .3 * b), Material.MACHINE);
        u.box(new double[]{a - .2, h + .15, front - .25}, new double[]{a + .2, h + .45, front}, Material.MACHINE, false);
        u.box(new double[]{a + half, yc - .1, front + .05}, new double[]{a + half + .3, yc + .3, front + .45}, Material.MACHINE, false);
        int n = ProcessUnit.sides(cyl), nb = ProcessUnit.sides(ds), nd = ProcessUnit.sides(dd), caps = b >= .45 ? 2 : 1;
        double neck = .25 * b + .04, cap = Math.min(.225, .1 + .2 * b), capHeight = .08 + .08 * b;
        for (int k = 0; k < throwCount; k++) {
            int sx = k % 2 == 0 ? -1 : 1;
            double z = zs[k], xb = a + sx * c.out(), g0 = a + sx * half, g1 = a + sx * (half + c.guideLength()), p1 = xb - sx * (lc / 2 + .06);
            // The crosshead guide and its door; the open distance piece between its flanges with the piston rod sliding in it.
            u.box(new double[]{Math.min(g0, g1), yc - gh, z - gh}, new double[]{Math.max(g0, g1), yc + gh, z + gh}, Material.MACHINE, true);
            double d0 = g0 + sx * .15, d1 = g1 - sx * .15;
            u.box(new double[]{Math.min(d0, d1), yc + gh, z - .7 * gh}, new double[]{Math.max(d0, d1), yc + gh + .025, z + .7 * gh}, Material.MACHINE, false);
            u.mesh.bar(new double[]{(d0 + d1) / 2 - .1, yc + gh + .06, z}, new double[]{(d0 + d1) / 2 + .1, yc + gh + .06, z}, .03, new double[]{0, 1, 0}, Material.PIPE);
            u.plate(g1 - sx * .05, g1, yc, z, piece + .06);
            // Balanced opposed: a pair's pistons move out together, the pairs' cranks spread over half a turn.
            distancePiece(u, g1, p1, sx, yc, z, piece, c.pieceLength() > 1.1 ? 2 : 1, b, c.stroke(), (k / 2) / (double) throwCount);
            // The cylinder: crank-end flange, body, head flange with its studs, head cover; the clearance pocket and its wheel.
            var fx = Frame.of(new double[]{xb, yc, z}, 0, sx);
            u.mesh.cylinder(fx, cyl / 2 + .05, -lc / 2 - .06, -lc / 2 + .02, n, Material.FLANGE);
            u.mesh.prism(fx, cyl / 2, -lc / 2 + .02, lc / 2, n, Material.MACHINE);
            u.mesh.cylinder(fx, cyl / 2 + .03, lc / 2, lc / 2 + .07, n, Material.FLANGE);
            u.mesh.bolts(fx, Math.min(cyl, PipeSizes.MAX_OUTSIDE), .35 * cyl, cyl / 2 + .03, lc / 2 + .07, 1, Material.BOLT);
            u.mesh.cylinder(fx, .42 * cyl, lc / 2 + .07, lc / 2 + .12, n, Material.MACHINE);
            if (pocket > 0) {
                double wheel = .12 + .1 * b, at = lc / 2 + .12 + pocket;
                u.mesh.cylinder(fx, .25 * cyl, lc / 2 + .12, at, n, Material.MACHINE);
                u.mesh.prism(fx, .02, at, at + .1, 6, Material.PIPE);
                u.mesh.ring(fx, wheel - .025, wheel, at + .07, at + .1, 12, 3, Material.ACTUATOR);
                u.mesh.bar(fx.at(0, wheel - .02, at + .085), fx.at(Math.PI, wheel - .02, at + .085), .02, new double[]{0, 0, 1}, Material.ACTUATOR);
            }
            u.solids.add(Solid.rod(new double[]{0, yc, z}, 0, Math.min(xb - sx * (lc / 2 + .06), xb + sx * (lc / 2 + .12 + pocket)),
                    Math.max(xb - sx * (lc / 2 + .06), xb + sx * (lc / 2 + .12 + pocket)), cyl / 2 + .05, 0));
            u.round(new double[]{0, yc, z}, 0, xb - lc / 2, xb + lc / 2, cyl / 2);
            u.volumes.add(Solid.rod(new double[]{0, yc, z}, 0, xb - lc / 2 + .05, xb + lc / 2 - .05, cyl / 2 - .05, 0));
            // Valve caps at both ends, on top (suction) and underneath (discharge); their nuts are too small to show.
            for (double t : new double[]{-(lc / 2 - cap - .08), lc / 2 - cap - .08})
                for (int below = 0; below < 2; below++)
                    for (int j = 0; j < caps; j++) {
                        double phi = (below == 0 ? 0 : Math.PI) + (caps == 1 ? 0 : (j == 0 ? -1 : 1) * Math.toRadians(35));
                        double[] radial = {0, DeterministicMath.cos(phi), DeterministicMath.sin(phi)};
                        var cf = new Frame(new double[]{xb + sx * t, yc, z}, radial, new double[]{1, 0, 0});
                        u.mesh.cylinder(cf, cap, cyl / 2 - .04, cyl / 2 + capHeight, 12, Material.FLANGE);
                    }
            // The necks to the bottles; the outboard support under the head.
            var up = Frame.of(new double[]{xb, 0, z}, 1, 1);
            u.mesh.prism(up, neck, yc + cyl / 2 - .05, ysb - .45 * ds, 12, Material.PIPE);
            u.mesh.ring(up, neck, 1.6 * neck, yc + cyl / 2 + .05, yc + cyl / 2 + .09, 12, 3, Material.FLANGE);
            u.mesh.prism(up, neck, ydb + .45 * dd, yc - cyl / 2 + .05, 12, Material.PIPE);
            u.mesh.ring(up, neck, 1.6 * neck, yc - cyl / 2 - .09, yc - cyl / 2 - .05, 12, 3, Material.FLANGE);
            double xs = xb + sx * (lc / 2 + .04);
            u.box(new double[]{xs - .04, .03, z - .2 * cyl}, new double[]{xs + .04, yc - .4 * cyl, z + .2 * cyl}, Material.STEELWORK, true);
            u.box(new double[]{xs - .12, 0, z - .2 * cyl - .06}, new double[]{xs + .12, .03, z + .2 * cyl + .06}, Material.STEELWORK, false);
            // A beam under the guide from the table's rail out to a post at the guide's end, a saddle up to the guide.
            double rail = Math.min(c.rail(), h), gx = g1 - sx * .2;
            u.box(new double[]{Math.min(g0, gx) - .05, h - rail, z - ProcessUnit.FLANGE_BEAM}, new double[]{Math.max(g0, gx) + .05, h, z + ProcessUnit.FLANGE_BEAM}, Material.STEELWORK, true);
            if (h - rail > .05) u.box(new double[]{gx - ProcessUnit.POST / 2, .03, z - ProcessUnit.POST / 2}, new double[]{gx + ProcessUnit.POST / 2, h - rail, z + ProcessUnit.POST / 2}, Material.STEELWORK, true);
            u.box(new double[]{Math.min(g0, gx) + .1, h, z - .6 * gh}, new double[]{Math.max(g0, gx) - .1, yc - gh, z + .6 * gh}, Material.STEELWORK, true);
        }
        for (int side = 0; side < 2; side++) {
            int sx = side == 0 ? -1 : 1;
            double xb = a + sx * c.out();
            bottle(u, xb, ysb, lo, hi, ds, nb);
            bottle(u, xb, ydb, lo, hi, dd, nd);
            // Posts with saddles under the discharge bottle, a quarter in from each end.
            for (double zp : new double[]{lo + .25 * (hi - lo), hi - .25 * (hi - lo)}) {
                u.box(new double[]{xb - .1, 0, zp - .1}, new double[]{xb + .1, ydb - .45 * dd, zp + .1}, Material.STEELWORK, true);
                u.box(new double[]{xb - .4 * dd, ydb - .45 * dd - .04, zp - .13}, new double[]{xb + .4 * dd, ydb - .3 * dd, zp + .13}, Material.STEELWORK, false);
            }
        }
        // The suction header across the outboard (front) end on a cell centre, over the frame, its branches down into both
        // suction bottles, the inlet on top of its middle; the discharge header from both discharge bottles' front heads,
        // the outlet at the front of its middle.
        double xl = a - c.out(), xr = a + c.out(), zh = Math.ceil(lo + .1 + hs / 2 - .5) + .5, yh = Math.ceil(ysb + ds / 2 + .2 + hs / 2 - .5) + .5, into = ysb + .3 * ds;
        u.header(hs, new double[]{xl, into, zh}, new double[]{xl, yh, zh}, new double[]{xr, yh, zh}, new double[]{xr, into, zh});
        u.nozzles.add(ProcessUnit.radial(Role.SUCTION, a, yh, hs / 2, ProcessUnit.sides(hs), zh, 1, ColumnStructure.size(hs, hs)));
        double head = lo - .7 * depth;
        u.header(hd, new double[]{xl, ydb, head}, new double[]{xl, ydb, zd}, new double[]{xr, ydb, zd}, new double[]{xr, ydb, head});
        double start = zd + .03, face = Math.floor(zd - hd / 2 - ProcessUnit.NECK);
        u.nozzles.add(new Nozzle(Role.DISCHARGE, new double[]{a, ydb, start}, 2, -1, start - face, ColumnStructure.size(hd, hd), 0));
        // The shaft end uses the pumps' coupling and guard. A large torque-sized hub needs a shelf below the table top
        // to enclose its underside without raising the crankshaft or moving the existing shaft and process connections.
        double rpm = UnitRatings.compressorSpeed(b), rs = ProcessUnit.shaftRadius(1000 * UnitRatings.compressorPower(b, throwCount), rpm);
        double driveFace = Math.ceil(end + .2 + rs - 1e-9), floor = Math.min(h, yc - ProcessUnit.couplingHubRadius(rs) - .1);
        double mount = ProcessUnit.couplingHubRadius(rs) + .13;
        u.baseplate(a - mount, a + mount, floor - .06, floor, end - .15, driveFace - .01);
        for (int side = -1; side <= 1; side += 2) {
            double x = a + side * (mount - .08);
            u.box(new double[]{x - .05, floor - .18, end - .15}, new double[]{x + .05, floor - .06, driveFace - .01}, Material.STEELWORK, true);
        }
        u.coupling(a, yc, end, driveFace, 1, rs, floor, ProcessUnit.shown(rpm), EquipmentShape.ShaftEnd.Role.DRIVEN_INPUT);
    }

    /**
     * A throw's open distance piece from the crosshead guide's flange at {@code g} to the cylinder's at {@code p} (outward
     * {@code sx} along x; its axis at (y, z), its half section {@code piece}) in {@code compartments} (owner request
     * 2026-10-18, "add Piston model to compressor", answered: the rods in open windows, since the pistons stay in the
     * cylinders): top and bottom plates, sides open in a window per compartment, a partition between compartments, the
     * rod's packing case on the cylinder's flange and its wiper case on the guide's. The piston rod, 0.05 + 0.12 bores
     * across, slides through it as a moving part ({@link Motion}) on a crank of half the {@code stroke} and a connecting rod
     * of five cranks from {@code phase}, its oil deflector collar in the compartment by the guide; it reaches half a stroke
     * and 0.1 m into the guide and the cylinder, so its ends never show.
     */
    private static void distancePiece(ProcessUnit u, double g, double p, int sx, double y, double z, double piece, int compartments, double bore, double stroke, double phase) {
        double x0 = Math.min(g, p), x1 = Math.max(g, p), t = .03 + .02 * bore, length = (x1 - x0) / compartments, win = .55 * piece;
        u.solids.add(Solid.box(new double[]{x0, y - piece, z - piece}, new double[]{x1, y + piece, z + piece}));
        u.box(new double[]{x0, y + piece - t, z - piece}, new double[]{x1, y + piece, z + piece}, Material.MACHINE, false);
        u.box(new double[]{x0, y - piece, z - piece}, new double[]{x1, y - piece + t, z + piece}, Material.MACHINE, false);
        for (int face = -1; face <= 1; face += 2) {
            double za = face < 0 ? z - piece : z + piece - t, zb = za + t, from = x0;
            u.box(new double[]{x0, y - piece + t, za}, new double[]{x1, y - win, zb}, Material.MACHINE, false);
            u.box(new double[]{x0, y + win, za}, new double[]{x1, y + piece - t, zb}, Material.MACHINE, false);
            for (int j = 0; j < compartments; j++) {
                double mid = x0 + length * (j + .5);
                u.box(new double[]{from, y - win, za}, new double[]{mid - .3 * length, y + win, zb}, Material.MACHINE, false);
                from = mid + .3 * length;
            }
            u.box(new double[]{from, y - win, za}, new double[]{x1, y + win, zb}, Material.MACHINE, false);
        }
        for (int j = 1; j < compartments; j++) u.plate(x0 + length * j - .025, x0 + length * j + .025, y, z, piece - t);
        var f = Frame.of(new double[]{0, y, z}, 0, 1);
        double rod = .025 + .06 * bore, cased = p - sx * (.1 + .05 * bore), wiper = g + sx * .06;
        u.mesh.cylinder(f, 2.2 * rod, Math.min(p, cased), Math.max(p, cased), 12, Material.FLANGE);
        u.mesh.cylinder(f, 1.8 * rod, Math.min(g, wiper), Math.max(g, wiper), 12, Material.FLANGE);
        double reach = stroke / 2 + .1, collar = g + sx * length / 2, half = .02 + .02 * bore;
        u.mesh.part(u.motions.size());
        u.mesh.cylinder(f, rod, Math.min(g - sx * reach, p + sx * reach), Math.max(g - sx * reach, p + sx * reach), 10, Material.PIPE);
        u.mesh.cylinder(f, 1.9 * rod, collar - half, collar + half, 12, Material.FLANGE);
        u.mesh.part(-1);
        double crank = stroke / 2;
        u.motions.add(new Motion(new double[]{(g + p) / 2, y, z}, new double[]{sx, 0, 0}, ProcessUnit.CRANK_SPEED, phase, crank, 5 * crank));
    }

    /**
     * A compressor's steel table (owner request 2026-10-17; {@code MACHINES_SCALE_RESEARCH.md}): two rails {@code rail} deep
     * under the frame's sides (half width {@code half} about x {@code a}) from {@code z0} to {@code z1}, cross beams as deep
     * at each of the {@code lines} along z, posts under them on base plates, their tops at {@code top}. A slow-speed machine
     * of the largest frames stands on a concrete block in real plants; steel here keeps to the other units' look.
     */
    private static void steelTable(ProcessUnit u, double a, double half, double z0, double z1, double top, List<Double> lines, double rail) {
        double beam = Math.min(rail, top), post = ProcessUnit.POST + .1, x0 = a - half + post / 2, x1 = a + half - post / 2;
        for (double x : new double[]{x0, x1}) {
            u.box(new double[]{x - ProcessUnit.FLANGE_BEAM, top - beam, z0}, new double[]{x + ProcessUnit.FLANGE_BEAM, top, z1}, Material.STEELWORK, true);
            for (double z : lines) u.post(x, z, 0, top - beam, post, true);
        }
        for (double z : lines)
            u.box(new double[]{x0 + ProcessUnit.FLANGE_BEAM, top - beam, z - ProcessUnit.FLANGE_BEAM / 2 - .025}, new double[]{x1 - ProcessUnit.FLANGE_BEAM, top, z + ProcessUnit.FLANGE_BEAM / 2 + .025}, Material.STEELWORK, true);
    }

    /** A pulsation bottle lying along z at (x, y), its tangent lines at {@code lo} and {@code hi}, with 2:1 heads; it collides and encloses as drawn. */
    private static void bottle(ProcessUnit u, double x, double y, double lo, double hi, double od, int n) {
        double r = od / 2, depth = od / 4;
        u.mesh.prism(Frame.of(new double[]{x, y, 0}, 2, 1), r, lo, hi, n, Material.SHELL);
        u.mesh.head(Frame.of(new double[]{x, y, 0}, 2, 1), r, hi, depth, n, Material.HEAD);
        u.mesh.head(Frame.of(new double[]{x, y, 0}, 2, -1), r, -lo, depth, n, Material.HEAD);
        var axis = new double[]{x, y, 0};
        u.solids.add(Solid.rod(axis, 2, lo - depth, hi, r, depth));
        u.volumes.add(Solid.rod(axis, 2, lo, hi, .94 * r, .9 * depth));
        u.volumes.add(Solid.rod(axis, 2, lo - .6 * depth, lo, .65 * r, 0));
        u.round(x, y, lo, hi, r);
    }
}
