package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.List;

import static com.wormzjl.icheme.science.equipment.Mesh.ALL_FACES;

/**
 * A mechanical-drive steam turbine (owner decision D5, batch 2026-11-03-rotating-machinery; its proportions from
 * {@code NEW_UNITS_SURVEY_ROTATING.md} section 2), drawn by {@link SteamTurbineDesign}. Its shaft runs along frame z; from
 * the front: the front standard over the front bearing (the governor actuator and the overspeed trip on its face), the
 * turbine casing, the rear bearing pedestal, and the shaft end, a half coupling under its guard, where its driven
 * machine joins it through a shaft block. Across the top of the casing's HP end lies the steam chest with its governor
 * valves; beside it stands the trip-and-throttle valve, which with the steam chest is the turbine's signature from 10 to
 * 30 m. The generator, gear, exciter, TEWAC cooler, auxiliary oil pump and sentinel valve of the generator sets it
 * replaces are gone: a generator is a machine of its own.
 *
 * <p>Proportions in Dc, the exhaust-end casing's outside diameter (survey, typ.): the HP end 0.8 Dc (back pressure) or
 * 0.65 Dc (vacuum), joined to the exhaust end by a cone; (0.5 + 0.15 N) Dc between the end walls for N stages, a third of
 * it the HP barrel under its aluminium cladding and half the bare, ribbed exhaust barrel; the horizontal split flange
 * 0.08 Dc proud with its studs from Dc 1 m (smaller ones are left out first); the shaft about 0.6 Dc (back pressure) or
 * 0.45 Dc (vacuum) over the base frame, on a cell centre; the front standard 0.6 Dc long. The steam chest, 0.25 Dc across
 * and 0.8 Dc long, carries one governor valve (N 3 at most) to five under a bar-lift bridge 0.2 Dc high worked by a
 * hydraulic servo 0.13 Dc across; a single-stage turbine (API 611) has one governor valve on a lever and one to three
 * hand valves with handwheels of 0.15 to 0.25 m. The trip-and-throttle valve's body is 0.25 Dc, its handwheel 0.4 to
 * 0.6 m, its hydraulic trip cylinder beside its yoke, its steam line 0.15 Dc. Gland condenser, drains, lube console and
 * instrument tubing are left out (survey: leave out first). The coupling's hub turns.
 */
final class SteamTurbineTrain {
    /** A set's power, MW: about 5 Dc² (survey, within a factor of 2; 13 MW at about 1.6 m). */
    static double power(double d) {
        return 5 * d * d;
    }

    /**
     * The turbine's speed, rev/min, to the next 100: the last stage's tip (about 0.8 Dc across) at 300 m/s, 3,000 to
     * 13,500 rpm (API 612 sets; the 13 MW set turns 4,500 rpm); a gearbox matches it to the machine it drives.
     */
    static double speed(double d) {
        return Math.round(Math.max(3000, Math.min(13500, 7200 / d)) / 100) * 100.0;
    }

    final double d, hp, casing, rise, flange;
    final int stages, valves;
    final boolean vacuum;
    /** Stations along z from the front standard's front face: its back, the casing's HP end wall, the HP barrel's end, the cone's end, the exhaust end wall, the rear pedestal. */
    final double standard, hpEnd, hpBarrel, coneEnd, exhaustEnd, pedestal0, pedestal1;
    /** The exhaust barrel's middle, the steam chest's axis. */
    final double exhaust, chest;

    /** A turbine of exhaust-end casing {@code d} and {@code stages}, exhausting to a condenser where {@code vacuum}, its shaft {@code rise} over its base frame. */
    SteamTurbineTrain(double d, int stages, boolean vacuum, double rise) {
        this.d = d;
        this.stages = stages;
        this.vacuum = vacuum;
        this.rise = rise;
        hp = (vacuum ? .65 : .8) * d;
        casing = (.5 + .15 * stages) * d;
        flange = .08 * d;
        valves = Math.max(1, Math.min(5, (stages - 1) / 2));
        standard = .6 * d;
        hpEnd = standard + .08 * d + .04;
        hpBarrel = hpEnd + .3 * casing;
        coneEnd = hpEnd + .5 * casing;
        exhaustEnd = hpEnd + casing;
        pedestal0 = exhaustEnd + .08 * d + .03;
        pedestal1 = pedestal0 + .25 * d + .05;
        exhaust = (coneEnd + exhaustEnd) / 2;
        chest = hpEnd + Math.max(.14 * d, .15 * casing);
    }

    /** The rear pedestal's back face from the front standard's front. */
    double length() {
        return pedestal1;
    }

    /** The steam chest's axis over the base. */
    double chestAxis() {
        return rise + hp / 2 + .08 * d;
    }

    double chestRadius() {
        return .125 * d;
    }

    /** The trip-and-throttle valve's body radius, its handwheel across, the steam line's outside diameter (0.15 Dc, 0.05 m at least). */
    double valveBody() {
        return Math.max(.06, .125 * d);
    }

    double handwheel() {
        return Math.max(.4, Math.min(.6, .15 * d + .3));
    }

    double steamLine() {
        return Math.max(.05, .15 * d);
    }

    /** The least offset of the trip-and-throttle valve from the shaft along +x: clear of the steam chest's end and the HP casing's split flange. */
    double valveOffset() {
        return Math.max(.4 * d + .2 + valveBody(), hp / 2 + flange + valveBody() + .08);
    }

    /** The governor actuator's reach in front of the front standard. */
    double frontReach() {
        return .8 * Math.max(.06, .1 * d) + .02;
    }

    /** How far the turbine reaches to -x from its shaft: the governor's servo, the split flange. */
    double leftReach() {
        return Math.max(.63 * d + .02, .5 * d + flange + .02);
    }

    // ---- Drawing ----------------------------------------------------------------------------------------------------

    /**
     * Draws the turbine on {@code base} (the base frame's top), its shaft along z at x {@code a}, its front standard's
     * front face at {@code z0}; the trip-and-throttle valve's body centred at (xv, yv, zv), its inlet towards +x, its
     * outlet to the steam chest's +x end. The shaft end follows the rear pedestal ({@link #couple}).
     */
    void draw(ProcessUnit u, double a, double base, double z0, double xv, double yv, double zv) {
        var m = u.mesh;
        double shaft = base + rise, r = d / 2, e = flange;
        int n = ProcessUnit.sides(d);
        var f = Frame.of(new double[]{a, shaft, 0}, 2, 1);
        double zs = z0 + standard, zh = z0 + hpEnd, z1 = z0 + hpBarrel, z2 = z0 + coneEnd, ze = z0 + exhaustEnd;

        // The front standard over the front bearing and its cap; the governor actuator and the overspeed trip on its face.
        double sx = Math.max(.2, .32 * d), top = shaft + Math.max(.12, .2 * d), ga = Math.max(.06, .1 * d);
        u.box(new double[]{a - sx, base, z0}, new double[]{a + sx, top, zs}, Material.MACHINE, true);
        u.box(new double[]{a - .6 * sx, top, z0 + .25 * standard}, new double[]{a + .6 * sx, top + .02 + .04 * d, zs - .25 * standard}, Material.MACHINE, false);
        u.box(new double[]{a - ga, shaft + .02, z0 - .8 * ga}, new double[]{a + ga, shaft + .02 + 1.8 * ga, z0}, Material.ACTUATOR, true);
        u.serviceHousing=new double[]{a-ga,shaft+.02,z0-.8*ga,a+ga,shaft+.02+1.8*ga,z0};
        m.cylinder(Frame.of(new double[]{a + .55 * sx, shaft - .05 * d - .05, 0}, 2, 1), .04 * d + .02, z0 - .05 * d - .03, z0, 10, Material.MACHINE);

        // The casing: the clad HP end wall, barrel and cone, the bare exhaust barrel and its end wall; the cladding's edge
        // over the bare casing, the exhaust barrel's girth ribs; the glands at both ends.
        m.annulus(f, 0, hp / 2, zh, n, -1, Material.CLADDING);
        m.prism(f, hp / 2, zh, z1, n, Material.CLADDING);
        if (hp < d) m.frustum(f, hp / 2, r, z1, z2, n, Material.CLADDING);
        m.prism(f, r, z2, ze, n, Material.MACHINE);
        m.annulus(f, 0, r, ze, n, 1, Material.MACHINE);
        m.hoop(f, r, r + .02 + .01 * d, z2, z2 + .04, n, Material.CLADDING);
        if (d >= 1) {
            int ribs = Math.max(1, (int) Math.round((ze - z2) / (.35 * d)));
            for (int k = 1; k <= ribs; k++) {
                double z = z2 + (ze - z2) * k / (ribs + 1), w = .02 + .005 * d;
                m.hoop(f, r, r + .025 * d, z - w, z + w, n, Material.MACHINE);
            }
        }
        m.prism(f, .1 * d + .02, zs, zh, n, Material.MACHINE);
        m.prism(f, .1 * d + .02, ze, z0 + pedestal0, n, Material.MACHINE);
        u.solids.add(ProcessUnit.Solid.rod(new double[]{a, shaft, 0}, 2, zh, ze, r, 0));
        u.volumes.add(ProcessUnit.Solid.rod(new double[]{a, shaft, 0}, 2, zh + .05, ze - .05, .9 * hp / 2, 0));
        u.round(a, shaft, z2, ze, r);
        u.round(a, shaft, zh, z1, hp / 2);

        // The horizontal split flange along both sides, its studs on top from 1 m; the casing's feet under it at the cone's end.
        double th = Math.max(.05, .1 * d);
        for (int side = -1; side <= 1; side += 2) {
            ledge(m, a, side, zh, hp / 2, z1, hp / 2, shaft - th / 2, shaft + th / 2, e, true, false, Material.CLADDING);
            if (hp < d) ledge(m, a, side, z1, hp / 2, z2, r, shaft - th / 2, shaft + th / 2, e, false, false, Material.CLADDING);
            ledge(m, a, side, z2, r, ze, r, shaft - th / 2, shaft + th / 2, e, false, true, Material.MACHINE);
            if (d >= 1) {
                int studs = (int) Math.floor((ze - zh) / .3);
                for (int k = 0; k < studs; k++) {
                    double z = zh + (ze - zh) * (k + .5) / studs, x = a + side * (radius(z, zh, z1, z2) + .55 * e);
                    m.box(new double[]{x - .025, shaft + th / 2, z - .025}, new double[]{x + .025, shaft + th / 2 + .06, z + .025}, ALL_FACES & ~4, Material.BOLT);
                }
            }
            double zf1 = z2 - .06, zf0 = Math.max(zh + .02, zf1 - .2 * d), x = a + side * (radius((zf0 + zf1) / 2, zh, z1, z2) + .55 * e), w = .02 + .02 * d;
            u.box(new double[]{x - w, base, zf0}, new double[]{x + w, shaft - th / 2, zf1}, Material.MACHINE, true);
        }

        // The rear bearing pedestal and its cap.
        double px = Math.max(.15, .25 * d), p0 = z0 + pedestal0, p1 = z0 + pedestal1, pt = shaft + .15 * d;
        u.box(new double[]{a - px, base, p0}, new double[]{a + px, pt, p1}, Material.MACHINE, true);
        u.box(new double[]{a - .6 * px, pt, p0 + .2 * (p1 - p0)}, new double[]{a + .6 * px, pt + .02 + .03 * d, p1 - .2 * (p1 - p0)}, Material.MACHINE, false);

        steamChest(u, a, base + chestAxis(), z0 + chest);
        tripThrottle(u, a, xv, yv, zv, base + chestAxis(), z0 + chest);
    }

    /** The casing's radius at station {@code z}: the HP barrel's, the cone's between, the exhaust barrel's. */
    private double radius(double z, double zh, double z1, double z2) {
        if (z <= z1) return hp / 2;
        if (z >= z2) return d / 2;
        return hp / 2 + (d - hp) / 2 * (z - z1) / (z2 - z1);
    }

    /**
     * A length of the split flange on the casing's {@code side} (±x of {@code a}) from station {@code za} (the casing's
     * radius there {@code ra}) to {@code zb} ({@code rb}), {@code out} proud of the casing between heights y0 and y1: its
     * top, underside and outer face, and the end faces asked for (a cone's flange is a slanted hexahedron).
     */
    private static void ledge(Mesh m, double a, int side, double za, double ra, double zb, double rb, double y0, double y1, double out, boolean startCap, boolean endCap,
                              Material material) {
        double[][] in = {{a + side * (ra - .03), za}, {a + side * (rb - .03), zb}}, ex = {{a + side * (ra + out), za}, {a + side * (rb + out), zb}};
        for (double y : new double[]{y0, y1})
            m.flat(List.of(new double[]{in[0][0], y, za}, new double[]{ex[0][0], y, za}, new double[]{ex[1][0], y, zb}, new double[]{in[1][0], y, zb}),
                    new double[]{0, y == y1 ? 1 : -1, 0}, material);
        m.flat(List.of(new double[]{ex[0][0], y0, za}, new double[]{ex[1][0], y0, zb}, new double[]{ex[1][0], y1, zb}, new double[]{ex[0][0], y1, za}), new double[]{side, 0, 0},
                material);
        if (startCap)
            m.flat(List.of(new double[]{in[0][0], y0, za}, new double[]{ex[0][0], y0, za}, new double[]{ex[0][0], y1, za}, new double[]{in[0][0], y1, za}), new double[]{0, 0, -1},
                    material);
        if (endCap)
            m.flat(List.of(new double[]{in[1][0], y0, zb}, new double[]{ex[1][0], y0, zb}, new double[]{ex[1][0], y1, zb}, new double[]{in[1][0], y1, zb}), new double[]{0, 0, 1},
                    material);
    }

    /**
     * The shaft end behind the rear pedestal: the shaft and the half coupling at {@code face} (a block face), under its
     * guard on {@code base}, turning at the turbine's shown speed; a {@link EquipmentShape.ShaftEnd.Role#DRIVER_OUTPUT}.
     */
    void couple(ProcessUnit u, double a, double base, double z0, double face, double rs, double rpm) {
        u.coupling(a, base + rise, z0 + pedestal1, face, 1, rs, base, ProcessUnit.shown(rpm), EquipmentShape.ShaftEnd.Role.DRIVER_OUTPUT);
    }

    /**
     * The steam chest across the HP end's top at (yc, zc), its covers' flanges at both ends (steam in at +x). Its
     * governor valves' bonnets and spindles rise to a bar-lift bridge 0.2 Dc over it, which a hydraulic servo on a bracket
     * beyond its -x end lifts by a lever; a single-stage turbine's one governor valve hangs on the servo's lever, and one to
     * three hand valves (1, 2 from 1.2 m, 3 from 2.2 m) stand beside it with their handwheels.
     */
    private void steamChest(ProcessUnit u, double a, double yc, double zc) {
        var m = u.mesh;
        double rc = chestRadius(), xl = a - .4 * d, xr = a + .4 * d, cap = .03 + .01 * d, rf = rc + .02 + .01 * d;
        var fc = Frame.of(new double[]{0, yc, zc}, 0, 1);
        int nc = ProcessUnit.sides(2 * rc);
        m.prism(fc, rc, xl, xr, nc, Material.CLADDING);
        m.cylinder(fc, rf, xl - cap, xl, nc, Material.FLANGE);
        m.cylinder(fc, rf, xr, xr + cap, nc, Material.FLANGE);
        u.solids.add(ProcessUnit.Solid.rod(new double[]{0, yc, zc}, 0, xl - cap, xr + cap, rf, 0));
        double ct = yc + rc, bar = ct + .2 * d, w = Math.max(.04, .05 * d), xs = xl - cap - .03 - .065 * d, rs = Math.max(.04, .065 * d), st = bar + .08 * d;
        // The servo on its bracket beyond the -x end, its piston rod.
        m.cylinder(Frame.of(new double[]{xs, 0, zc}, 1, 1), rs, yc - .06 * d, st, 12, Material.ACTUATOR);
        m.bar(new double[]{xl - cap, yc, zc}, new double[]{xs, yc, zc}, Math.max(.04, .04 * d), new double[]{0, 1, 0}, Material.MACHINE);
        m.cylinder(Frame.of(new double[]{xs, 0, zc}, 1, 1), .015 + .006 * d, st, st + .06 * d + .02, 6, Material.PIPE);
        u.solids.add(ProcessUnit.Solid.rod(new double[]{xs, 0, zc}, 1, yc - .06 * d, st, rs, 0));
        double lever = st + .06 * d + .02;
        if (stages >= 2) {
            for (int k = 0; k < valves; k++) spindle(m, a - .28 * d + .56 * d * (k + .5) / valves, ct, bar, zc);
            for (int s = -1; s <= 1; s += 2)
                m.bar(new double[]{a + s * .34 * d, ct - .02, zc}, new double[]{a + s * .34 * d, bar + w / 2, zc}, w, new double[]{0, 0, 1}, Material.MACHINE);
            m.bar(new double[]{a - .37 * d, bar, zc}, new double[]{a + .37 * d, bar, zc}, 1.2 * w, new double[]{0, 1, 0}, Material.MACHINE);
            m.bar(new double[]{xs, lever, zc}, new double[]{a - .37 * d, bar + .6 * w, zc}, .8 * w, new double[]{0, 0, 1}, Material.MACHINE);
            u.solids.add(ProcessUnit.Solid.box(new double[]{a - .37 * d, ct, zc - .6 * w}, new double[]{a + .37 * d, bar + .6 * w, zc + .6 * w}));
            return;
        }
        double xg = a - .15 * d, gTop = ct + .12 * d;
        spindle(m, xg, ct, gTop, zc);
        m.bar(new double[]{xs, lever, zc}, new double[]{xg, gTop, zc}, .8 * w, new double[]{0, 0, 1}, Material.MACHINE);
        int hand = d < 1.2 ? 1 : d < 2.2 ? 2 : 3;
        double hw = Math.max(.15, Math.min(.25, .08 * d + .1)) / 2;
        for (int k = 0; k < hand; k++) {
            double x = a + .05 * d + k * (2 * hw + .04), yh = ct + .1 * d + .06;
            spindle(m, x, ct, yh, zc);
            var fw = Frame.of(new double[]{x, 0, zc}, 1, 1);
            m.ring(fw, hw - .025, hw, yh - .015, yh + .015, 12, 3, Material.ACTUATOR);
            m.bar(new double[]{x - hw + .02, yh, zc}, new double[]{x + hw - .02, yh, zc}, .02, new double[]{0, 1, 0}, Material.ACTUATOR);
            m.bar(new double[]{x, yh, zc - hw + .02}, new double[]{x, yh, zc + hw - .02}, .02, new double[]{0, 1, 0}, Material.ACTUATOR);
        }
    }

    /** A governor or hand valve on the chest's top {@code from} at (x, zc): its bonnet and its spindle up to {@code to}. */
    private void spindle(Mesh m, double x, double from, double to, double zc) {
        var up = Frame.of(new double[]{x, 0, zc}, 1, 1);
        double bonnet = from + .06 * d + .02;
        m.cylinder(up, .04 * d + .015, from - .02, bonnet, 10, Material.MACHINE);
        m.cylinder(up, Math.max(.012, .01 * d + .006), bonnet, to, 6, Material.PIPE);
    }

    /**
     * The trip-and-throttle valve, its body centred at (xv, yv, zv): a globe body, its outlet's neck and flange towards
     * the steam chest and its inlet's towards +x; the bonnet, the
     * yoke's two arms and head, the stem and the handwheel on top; the hydraulic trip cylinder on a bracket beside the
     * yoke. Its outlet line runs to the chest's +x cover at (yc, zc), jogging where the valve stands off the chest's row.
     */
    private void tripThrottle(ProcessUnit u, double a, double xv, double yv, double zv, double yc, double zc) {
        var m = u.mesh;
        double rb = valveBody(), od = steamLine(), rf = PipeSizes.flange(od) / 2 + .02, neck = .55 * rb, hw = handwheel();
        int nv = ProcessUnit.sides(2 * rb);
        m.sphere(new double[]{xv, yv, zv}, rb, nv, Material.MACHINE);
        var fx = Frame.of(new double[]{0, yv, zv}, 0, 1);
        m.prism(fx, neck, xv - rb - .05, xv - .5 * rb, 12, Material.MACHINE);
        m.cylinder(fx, rf, xv - rb - .08, xv - rb - .05, 12, Material.FLANGE);
        m.prism(fx, neck, xv + .5 * rb, xv + rb + .05, 12, Material.MACHINE);
        m.cylinder(fx, rf, xv + rb + .05, xv + rb + .08, 12, Material.FLANGE);
        double yB = yv + rb + .08 * d + .03, yY = yB + .3 * d + .1, arm = .03 * d + .02, head = .04 + .02 * d, yH = yY + head + .08;
        var up = Frame.of(new double[]{xv, 0, zv}, 1, 1);
        m.cylinder(up, .6 * rb, yv + .5 * rb, yB, nv, Material.MACHINE);
        for (int s = -1; s <= 1; s += 2)
            m.bar(new double[]{xv, yB - .01, zv + s * .5 * rb}, new double[]{xv, yY, zv + s * .5 * rb}, arm, new double[]{1, 0, 0}, Material.MACHINE);
        u.box(new double[]{xv - .25 * rb - .02, yY, zv - .5 * rb - arm}, new double[]{xv + .25 * rb + .02, yY + head, zv + .5 * rb + arm}, Material.MACHINE, false);
        m.cylinder(up, .015 + .006 * d, yB, yH + .05, 6, Material.PIPE);
        m.ring(up, hw / 2 - .03, hw / 2, yH - .02, yH + .02, 16, 3, Material.ACTUATOR);
        m.bar(new double[]{xv - hw / 2 + .02, yH, zv}, new double[]{xv + hw / 2 - .02, yH, zv}, .025, new double[]{0, 1, 0}, Material.ACTUATOR);
        m.bar(new double[]{xv, yH, zv - hw / 2 + .02}, new double[]{xv, yH, zv + hw / 2 - .02}, .025, new double[]{0, 1, 0}, Material.ACTUATOR);
        double rt = .045 * d + .025, zt = zv + .5 * rb + arm + rt + .03;
        m.cylinder(Frame.of(new double[]{xv, 0, zt}, 1, 1), rt, yB - .02, yB + .2 * d + .12, 12, Material.ACTUATOR);
        m.bar(new double[]{xv, yB + .1 * d + .05, zv + .5 * rb}, new double[]{xv, yB + .1 * d + .05, zt - rt}, Math.max(.04, .03 * d), new double[]{0, 1, 0}, Material.MACHINE);
        u.solids.add(ProcessUnit.Solid.box(new double[]{xv - rb, yv - rb, zv - rb}, new double[]{xv + rb, yY + head, zt + rt}));
        u.solids.add(ProcessUnit.Solid.box(new double[]{xv - hw / 2, yH - .02, zv - hw / 2}, new double[]{xv + hw / 2, yH + .02, zv + hw / 2}));
        // The outlet line to the chest's +x cover ({@link ProcessUnit#pipe}, which leaves out a jog of no length): its legs
        // across, along z, up or down and across again.
        double xo = xv - rb - .08, xc = a + .4 * d + .03 + .01 * d, xm = (xo + xc) / 2;
        u.pipe(od / 2, Material.PIPE, true, new double[]{xo, yv, zv}, new double[]{xm, yv, zv}, new double[]{xm, yv, zc}, new double[]{xm, yc, zc}, new double[]{xc, yc, zc});
    }
}
