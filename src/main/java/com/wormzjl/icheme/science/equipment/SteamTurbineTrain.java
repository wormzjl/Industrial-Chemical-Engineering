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

    /**
     * The casing's profile from its HP end wall: each barrel's start and end (from the front standard's front) and radius,
     * one barrel per section, short cones between them. A casing of one section has today's two barrels (the HP end and
     * the exhaust end) and its cone.
     */
    final double[] barrelStart, barrelEnd, barrelRadius;

    /** A turbine of one section ({@link #SteamTurbineTrain(double, int, boolean, double, double[])}). */
    SteamTurbineTrain(double d, int stages, boolean vacuum, double rise) {
        this(d, stages, vacuum, rise, new double[]{1});
    }

    /**
     * A turbine of exhaust-end casing {@code d} and {@code stages}, exhausting to a condenser where {@code vacuum}, its
     * shaft {@code rise} over its base frame; its casing stepped into one barrel per section, each as long as its
     * {@code drops} share (batch 2026-11-15-equipment-visual-design, owner: the HP turbine includes the MP and LP
     * sections). The barrels: the front one the HP end's ({@link #hp}), the last one Dc, a middle one 0.85 Dc (0.9 Dc
     * behind a back-pressure HP end). Every station that places a nozzle, the steam chest or a solid stays where one
     * section put it: the end walls, {@link #coneEnd} and {@link #exhaust}.
     */
    SteamTurbineTrain(double d, int stages, boolean vacuum, double rise, double[] drops) {
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
        int k = drops.length;
        barrelStart = new double[Math.max(2, k)];
        barrelEnd = new double[barrelStart.length];
        barrelRadius = new double[barrelStart.length];
        if (k <= 1) {
            barrelStart[0] = hpEnd;
            barrelEnd[0] = hpBarrel;
            barrelRadius[0] = hp / 2;
            barrelStart[1] = coneEnd;
            barrelEnd[1] = exhaustEnd;
            barrelRadius[1] = d / 2;
            return;
        }
        // Short cones between the barrels; the barrels share what is left by their drops. The front barrel reaches past the
        // steam chest on its top; the last starts before the exhaust branch it carries (its radius as one section gave it).
        double cone = Math.min(.15 * d, .1 * casing), room = casing - (k - 1) * cone, total = 0;
        for (double x : drops) total += Math.max(0, x);
        double[] length = new double[k];
        for (int i = 0; i < k; i++) length[i] = room * (total > 0 ? Math.max(0, drops[i]) / total : 1.0 / k);
        double branch = Math.min(.2 * d, .25 * casing - .02 * d);
        double first = chest - hpEnd + chestRadius() + .03, last = casing - (.75 * casing - branch - .02 * d), middle = .12 * casing;
        length[0] = Math.max(length[0], first);
        length[k - 1] = Math.max(length[k - 1], last);
        if (k > 2) {
            double rest = room - length[0] - length[k - 1];
            // A middle barrel keeps a share of the casing; the last, then the front, give way to it down to their least.
            if (rest < middle * (k - 2)) {
                double need = middle * (k - 2) - rest, give = Math.min(need, Math.max(0, length[k - 1] - last));
                length[k - 1] -= give;
                need -= give;
                give = Math.min(need, Math.max(0, length[0] - first));
                length[0] -= give;
                rest = room - length[0] - length[k - 1];
            }
            double share = 0;
            for (int i = 1; i < k - 1; i++) share += length[i];
            for (int i = 1; i < k - 1; i++) length[i] = share > 0 ? rest * length[i] / share : rest / (k - 2);
        } else {
            length[1] = Math.max(room - length[0], last);
            length[0] = room - length[1];
        }
        double z = hpEnd;
        for (int i = 0; i < k; i++) {
            barrelStart[i] = z;
            barrelEnd[i] = i == k - 1 ? exhaustEnd : z + length[i];
            barrelRadius[i] = i == 0 ? hp / 2 : i == k - 1 ? d / 2 : Math.max(.85 * d, (hp + d) / 2) / 2;
            z = barrelEnd[i] + cone;
        }
    }

    /** The last barrel's start: the exhaust end's, where the cladding ends and the casing's feet stand. */
    double lastBarrel() {
        return barrelStart[barrelStart.length - 1];
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

        // The casing: the clad HP end wall, the clad barrels of the sections before the last and the cones between them,
        // the bare exhaust barrel and its end wall; the cladding's edge over the bare casing, the exhaust barrel's girth
        // ribs; the glands at both ends.
        int last = barrelStart.length - 1;
        z2 = z0 + lastBarrel();
        m.annulus(f, 0, hp / 2, zh, n, -1, Material.CLADDING);
        for (int k = 0; k <= last; k++) {
            double s = z0 + barrelStart[k], t = z0 + barrelEnd[k], rk = barrelRadius[k];
            m.prism(f, rk, s, t, n, k == last ? Material.MACHINE : Material.CLADDING);
            if (k < last && barrelRadius[k + 1] > rk) m.frustum(f, rk, barrelRadius[k + 1], t, z0 + barrelStart[k + 1], n, Material.CLADDING);
            else if (k < last) m.prism(f, rk, t, z0 + barrelStart[k + 1], n, Material.CLADDING);
            u.round(a, shaft, s, t, rk);
        }
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

        // The horizontal split flange along both sides of every barrel and cone, its studs on top from 1 m; the casing's
        // feet under it before the exhaust barrel.
        double th = Math.max(.05, .1 * d);
        for (int side = -1; side <= 1; side += 2) {
            for (int k = 0; k <= last; k++) {
                double s = z0 + barrelStart[k], t = z0 + barrelEnd[k], rk = barrelRadius[k];
                ProcessUnit.ledge(m, a, side, s, rk, t, rk, shaft - th / 2, shaft + th / 2, e, k == 0, k == last, k == last ? Material.MACHINE : Material.CLADDING);
                if (k < last) ProcessUnit.ledge(m, a, side, t, rk, z0 + barrelStart[k + 1], barrelRadius[k + 1], shaft - th / 2, shaft + th / 2, e, false, false, Material.CLADDING);
            }
            if (d >= 1) {
                int studs = (int) Math.floor((ze - zh) / .3);
                for (int k = 0; k < studs; k++) {
                    double z = zh + (ze - zh) * (k + .5) / studs, x = a + side * (radius(z - z0) + .55 * e);
                    m.box(new double[]{x - .025, shaft + th / 2, z - .025}, new double[]{x + .025, shaft + th / 2 + .06, z + .025}, ALL_FACES & ~4, Material.BOLT);
                }
            }
            // The feet, solids, stand where one section placed them (at its cone's end, on its profile), so the stepped
            // casing keeps the cells a placed turbine has.
            double zf1 = z0 + coneEnd - .06, zf0 = Math.max(zh + .02, zf1 - .2 * d), x = a + side * (oneSection((zf0 + zf1) / 2 - z0) + .55 * e), w = .02 + .02 * d;
            u.box(new double[]{x - w, base, zf0}, new double[]{x + w, shaft - th / 2, zf1}, Material.MACHINE, true);
        }

        // The rear bearing pedestal and its cap.
        double px = Math.max(.15, .25 * d), p0 = z0 + pedestal0, p1 = z0 + pedestal1, pt = shaft + .15 * d;
        u.box(new double[]{a - px, base, p0}, new double[]{a + px, pt, p1}, Material.MACHINE, true);
        u.box(new double[]{a - .6 * px, pt, p0 + .2 * (p1 - p0)}, new double[]{a + .6 * px, pt + .02 + .03 * d, p1 - .2 * (p1 - p0)}, Material.MACHINE, false);

        steamChest(u, a, base + chestAxis(), z0 + chest);
        tripThrottle(u, a, xv, yv, zv, base + chestAxis(), z0 + chest);
    }

    /** A casing of one section's radius at station {@code z} from the front standard's front: the HP barrel's, the cone's, the exhaust barrel's. */
    private double oneSection(double z) {
        if (z <= hpBarrel) return hp / 2;
        if (z >= coneEnd) return d / 2;
        return hp / 2 + (d - hp) / 2 * (z - hpBarrel) / (coneEnd - hpBarrel);
    }

    /** The casing's radius at station {@code z} from the front standard's front: a barrel's, or a cone's between two. */
    double radius(double z) {
        int last = barrelStart.length - 1;
        if (z <= barrelEnd[0]) return barrelRadius[0];
        for (int k = 0; k < last; k++) {
            if (z <= barrelEnd[k]) return barrelRadius[k];
            if (z < barrelStart[k + 1])
                return barrelRadius[k] + (barrelRadius[k + 1] - barrelRadius[k]) * (z - barrelEnd[k]) / (barrelStart[k + 1] - barrelEnd[k]);
        }
        return barrelRadius[last];
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
