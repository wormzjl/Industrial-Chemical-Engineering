package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Nozzle;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Ring;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Solid;
import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.ArrayList;
import java.util.List;

import static com.wormzjl.icheme.science.equipment.Access.around;
import static com.wormzjl.icheme.science.equipment.HydroprocessingParts.*;
import static com.wormzjl.icheme.science.equipment.Mesh.*;
import static com.wormzjl.icheme.science.equipment.ProcessUnit.outward;
import static com.wormzjl.icheme.science.equipment.ProcessUnit.tangential;

/**
 * An ebullated-bed residue hydrocracker (H-Oil, LC-Fining; owner request 2026-10-18, "with its special external
 * recirculation pump", NEW_UNITS_PLAN.md, form logged from {@code NEW_UNITS_SURVEY_HYDROPROCESSING.md} section 2): residue
 * and hydrogen flow up through a grid; the ebullating pump draws gas-free liquid from the recycle cup near the top down
 * the central downcomer and returns it under the grid, keeping the catalyst bed expanded (US 7,815,870). Sizes: inside
 * diameter D 2.5 to 6 m in 0.5 m steps (commercial reactors 3 to 6 m); tangent to tangent 4 D to 7 D in whole metres (the
 * survey's default 5 D is the constructor's to offer); the skirt bay from grade to the bottom tangent,
 * {@link #minSupport} at least.
 *
 * <p>Proportions (survey): wall t = 0.055 D, insulation 0.1 + 0.02 D under aluminium cladding, hemispherical heads. The
 * pump (Sulzer, Flowserve Byron Jackson: a vertical sealless machine, pump above its canned or oil-filled motor, barrier
 * oil cooled outside, no shaft in sight) stands coaxially under the bottom head in the open skirt bay: its forged casing
 * 0.25 D across (0.9 m at least) and 0.2 D tall; the bolted joint ring 1.15 times the casing across with 24 or 32 studs
 * (the signature); the motor's pressure housing 0.18 D across (0.7 m at least), 0.35 D + 0.8 m long, hung under the
 * casing from a steel frame bearing on the joint ring, {@link #DROP} clear over the base plate to lower the motor out.
 * The bay is D/2 + t + 1.2 + 0.5 D + 2 m high at least (the survey's Hs), more where the stack and its suction joint need
 * it. The recycle loop: the suction (0.15 D) from the bottom head's pole straight down into the casing, its heavy joint
 * taking the head's growth; the discharge (0.12 D) out of the casing's side to the left, a short-radius elbow up into
 * the plenum nozzle 0.3 D off the axis (further where the casing and elbow need it); every joint a heavy flange pair.
 * Opposite, the combined feed (0.12 D) comes down from its plenum nozzle to the tee 1.5 m over grade where FEED (0.08 D)
 * and HYDROGEN (0.06 D) join it, each leaving through its own skirt opening, front and back; the openings are 0.6 D wide
 * (survey) or wider, so those lines pass them. Spent catalyst leaves the bottom head through two ball valves and a sleeve
 * in the skirt to the withdrawal vessel standing at grade at the left (0.3 D across, 1 m at least, 0.6 D tangent to
 * tangent, 2 m at least, its 60° cone bottom over the low outlet); fresh catalyst comes from the addition vessel (as
 * large, 2:1 heads) standing on legs on the top head behind the effluent nozzle, through a ball valve into the head.
 *
 * <p>Drawn besides: the clad shell with flashing bands every 3.5 m (a band a fitting would cross left out); the effluent's
 * forged top nozzle with its Class 2500 flange pair and studs ({@link HydroprocessingParts#topNozzle}) and elbow to the front; the square top
 * deck with its handrail, the caged ladder coming up through it, and a pipe davit ({@link HydroprocessingParts#topDeck}); a part ring a metre
 * over the bottom tangent (in a bay over 8 m a rest on the skirt under it, 9 m up at most), one full platform ring at mid
 * height, part rings resting longer climbs ({@link Access#climbs}; none on the tangent, those under it laid round the
 * skirt on their brackets), all part rings over the front; caged ladders left and right, the ground one on the right
 * clear of the withdrawal vessel
 * ({@link ProcessUnit#ringAccess}); five nuclear density-gauge detectors in a row up the front left tracking the bed level
 * (0.4 to 0.8 of the tangent length); three thermowells at the back right; two lifting trunnions under the top deck; the
 * barrier-oil cooler (0.3 by 1.5 m) on brackets outside the skirt at the front left with its two lines to the motor; the
 * skirt as a column's on grade (owner report 2026-10-25: in line with the drums' and columns' skirts), its base ring and
 * anchor chairs, the bay's floor plate. No moving parts: the canned motor hides its
 * rotation (survey Q5).
 */
final class EbullatedBedReactorDesign extends UnitDesign {
    /** The motor housing's foot over the base plate: room to lower the motor out, m. */
    static final double DROP = 1.2;
    /** The liquid upflow that keeps the bed expanded (m/s), the pump's head (Pa) and efficiency (survey: 4 to 5 cm/s, 3.5 bar). */
    static final double UPFLOW = .045, HEAD = 3.5e5, EFFICIENCY = .7;
    private static final double[] UP = {0, 1, 0};

    static double wall(double d) {
        return .055 * d;
    }

    /** The pump casing's OD and height, m. */
    static double casing(double d) {
        return Math.max(.9, .25 * d);
    }

    static double casingHeight(double d) {
        return .2 * d;
    }

    /** The motor housing's OD and length, m. */
    static double motor(double d) {
        return Math.max(.7, .18 * d);
    }

    static double motorLength(double d) {
        return .35 * d + .8;
    }

    /** The suction line from the bottom head's pole, m. */
    static double suction(double d) {
        return .15 * d;
    }

    /** The joint ring's flanges' thickness, m. */
    static double jointFlange(double d) {
        return Math.max(.06, .08 * casing(d));
    }

    /**
     * The pump stack's height from grade to the bottom head's pole, m: the base plate, the motor's drop, the motor, the joint
     * ring, the casing, and the suction (forged neck 0.2, its heavy flange pair, the casing's hub 0.15).
     */
    static double stack(double d) {
        return EquipmentShape.PLATE + DROP + motorLength(d) + jointFlange(d) + .01 + casingHeight(d) + .36 + .6 * suction(d);
    }

    /** The recycle, m³/s, and the pump's shaft power, kW. */
    static double recycle(double d) {
        return UPFLOW * Math.PI * d * d / 4;
    }

    static double power(double d) {
        return recycle(d) * HEAD / EFFICIENCY / 1000;
    }

    /** The catalyst vessels' OD and tangent length, m. */
    static double catalystVessel(double d) {
        return Math.max(1, .3 * d);
    }

    static double catalystVesselLength(double d) {
        return Math.max(2, .6 * d);
    }

    EbullatedBedReactorDesign() {
        // Computed in its overrides: minLength, maxLength, minSupport.
        super(new Sizes(2.5, 6, .5,
                Sizes.COMPUTED, Sizes.COMPUTED,
                Sizes.COMPUTED, EquipmentShape.MAX_SUPPORT,
                Orientation.VERTICAL, "ebullated_bed_reactor.diameter", "ebullated_bed_reactor.tangent_length", "ebullated_bed_reactor.bay", false,
                1));
    }

    @Override
    public int minLength(double size) {
        return (int) Math.ceil(4 * size - 1e-9);
    }

    @Override
    public int maxLength(double size) {
        return (int) Math.floor(7 * size + 1e-9);
    }

    /** A new reactor's tangent length: 5 D (survey default). */
    @Override
    public int defaultLength(double size) {
        return Math.max(minLength(size), Math.min(maxLength(size), (int) Math.round(5 * size)));
    }

    /** The bay: the survey's D/2 + t + Hp + 2 m (Hp = 1.2 + 0.5 D), or the pump stack under the head where it is taller. */
    @Override
    public int minSupport(double size, int length) {
        double head = size / 2 + wall(size);
        return (int) Math.ceil(Math.max(head + 1.2 + .5 * size + 2, head + stack(size)) - 1e-9);
    }

    @Override
    public boolean lengthInMetres() {
        return true;
    }

    @Override
    public Line held(EquipmentShape shape) {
        double d = shape.diameter();
        return new Line("ebullated_bed_reactor.recycle", whole(3600 * recycle(d)), whole(power(d)));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double d = shape.diameter();
        return List.of(new Line("ebullated_bed_reactor.vessel", size(d), (int) shape.tangentLength(), size(wall(d))),
                new Line("ebullated_bed_reactor.pump", size(casing(d)), whole(3600 * recycle(d)), whole(power(d))));
    }

    @Override
    void build(ProcessUnit u) {
        double d = u.shape.diameter(), r = d / 2, shell = r + wall(d), wall = shell + insulation(d), skirt = shell;
        int length = (int) u.shape.tangentLength(), s = u.shape.support();
        double yB = s, yT = s + length;
        int n = ProcessUnit.sides(2 * wall), level = (int) Math.floor(yT + 1e-9);
        double outer = wall + Access.MIN_DECK, a = ProcessUnit.centre(outer + .1);
        double[] axis = {a, 0, a};
        var up = Frame.of(axis, 1, 1);

        // ---- The pump's stations, the loop's offsets and the skirt's openings.
        double dc = casing(d), hc = casingHeight(d), dm = motor(d), lm = motorLength(d), ods = suction(d), odd = .12 * d, odc = .12 * d;
        double odF = .08 * d, odH = .06 * d, tj = jointFlange(d), ring = .575 * dc, q1 = .5 * dc + .05;
        double yM = EquipmentShape.PLATE + DROP, yLow = yM + lm, yJ = yLow + tj + .01, yC = yJ + hc, yP = yB - shell;
        double off = Math.max(.3 * d, dc / 2 + .1 + odd);
        int offF = (int) Math.max(1, Math.ceil(ring + odc / 2 + .05 - 1e-9));
        // The front and back openings take the feed's and hydrogen's lines at a pipe's largest with the port's clearance,
        // every skirt facet they cross cut (owner decision 2026-10-21: the skirt hole takes the pipe's size, up to 1 m).
        double thick = EquipmentShape.SKIRT_WALL, crossed = DeterministicMath.asin(Math.min(1, (offF + PipeSizes.MAX_OUTSIDE / 2 + EquipmentShape.PORT_CLEARANCE) / (skirt - thick))) + Math.PI / n;
        double hw = Math.max(.3 * d, crossed >= Math.PI / 2 ? skirt : skirt * DeterministicMath.sin(crossed)), top = yB - Math.max(1, .25 * d);
        double[] front = {-Math.PI / 2, hw, 0, top}, back = {Math.PI / 2, hw, 0, top};

        // ---- The clad shell over its top head, the cladding's end and the bottom head over the bay (a solid's head under
        // its low end), the open skirt on grade.
        cladShell(u, a, wall, skirt, yB, yT, n);
        u.mesh.head(Frame.of(axis, 1, -1), shell, -yB, shell, n, Material.CLADDING);
        u.solids.add(Solid.rod(axis, 1, yB, yB, shell, shell, 0));
        u.volumes.add(Solid.rod(axis, 1, yB, yB, .94 * shell, .94 * shell, 0));
        u.skirt(a, a, skirt, yB, n, front, back);
        u.baseRing(a, a, skirt, d, n, front, back);
        u.round(a, a, 0, yB, skirt, 1);

        // ---- The motor's pressure housing: banded, its dished bottom cover, the terminal box towards the front opening.
        int nm = ProcessUnit.sides(dm), nc = ProcessUnit.sides(2 * ring);
        u.mesh.prism(up, dm / 2, yM, yLow, nm, Material.MACHINE);
        u.mesh.ring(up, 0, dm / 2 + .06, yM - .08, yM, nm, 3, Material.FLANGE);
        u.mesh.head(Frame.of(axis, 1, -1), dm / 2, -(yM - .08), dm / 4, nm, Material.MACHINE);
        for (double y = yM + .4; y < yLow - .35; y += .5) u.mesh.ring(up, dm / 2, dm / 2 + .03, y, y + .05, nm, 3, Material.MACHINE);
        double yBox = yM + .55 * lm, zBox = a - dm / 2 - .1;
        u.mesh.block(new double[]{a, yBox, zBox}, new double[]{.18, 0, 0}, new double[]{0, .2, 0}, new double[]{0, 0, .12}, Material.MOTOR);
        u.line(.03, Material.PIPE, new double[]{a, yBox - .2, zBox}, new double[]{a, EquipmentShape.PLATE, zBox});
        u.solids.add(Solid.rod(axis, 1, yM - .08 - dm / 4, yLow, dm / 2 + .06, 0));
        u.solids.add(ProcessUnit.span(new double[]{a - .18, EquipmentShape.PLATE, zBox - .12}, new double[]{a + .18, yBox + .2, zBox + .12}));

        // ---- The joint ring (the signature): the motor's flange and the casing's, the gasket between, 24 or 32 studs.
        u.mesh.cylinder(up, ring, yLow, yLow + tj, nc, Material.FLANGE);
        u.mesh.prism(up, .96 * ring, yLow + tj, yJ, nc, Material.BOLT);
        u.mesh.cylinder(up, ring, yJ, yJ + tj, nc, Material.FLANGE);
        u.studs(a, a, (dc / 2 + ring) / 2, yLow, yJ + tj, dc < 1.2 ? 24 : 32, Math.max(.03, .04 * dc));
        // The forged casing, its bolted cover and the suction's hub.
        u.mesh.prism(up, dc / 2, yJ + tj, yC - .08, ProcessUnit.sides(dc), Material.MACHINE);
        u.mesh.cylinder(up, dc / 2 + .04, yC - .08, yC, ProcessUnit.sides(dc), Material.MACHINE);
        u.mesh.bolts(up, dc, .62 * ods + .02, dc / 2 + .04, yC, 1, Material.BOLT);
        u.mesh.frustum(up, .62 * ods, ods / 2, yC, yC + .15, ProcessUnit.sides(ods), Material.MACHINE);
        u.solids.add(Solid.rod(axis, 1, yLow, yC + .15, ring, 0));

        // ---- The pump's frame: two beams under the motor's flange on four posts, ties between them, base plates.
        // Its posts on the feed's side stand beyond the feed's line at a pipe's largest where the skirt has room for them,
        // else inside it, a pipe's largest neck and 0.05 m clear of it (owner decision 2026-10-21: a 1 m pipe has a 1 m root,
        // other components give way; at q1 the posts took 0.02 m of the neck, D 3.5 m and under, 2026-10-25).
        double post = .2, beam = .25, yTie = (EquipmentShape.PLATE + yLow - beam) / 2, wide = offF + PipeSizes.MAX_OUTSIDE / 2 + post / 2 + .05,
                inside = offF - PipeSizes.MAX_OUTSIDE / 2 - post / 2 - .05;
        double qL = q1 <= inside ? q1 : Math.sqrt(wide * wide + q1 * q1) + post <= skirt - thick - .05 ? wide : inside;
        for (int sz = -1; sz <= 1; sz += 2) {
            double z = a + sz * q1;
            u.box(new double[]{a - qL - post / 2, yLow - beam, z - .1}, new double[]{a + q1 + post / 2, yLow, z + .1}, Material.STEELWORK, true);
            for (int sx = -1; sx <= 1; sx += 2) u.post(sx < 0 ? a - qL : a + q1, z, EquipmentShape.PLATE, yLow - beam, post, true);
        }
        for (int sx = -1; sx <= 1; sx += 2) {
            double x = sx < 0 ? a - qL : a + q1;
            u.box(new double[]{x - .07, yTie - .07, a - q1 + post / 2}, new double[]{x + .07, yTie + .07, a + q1 - post / 2}, Material.STEELWORK, true);
        }

        // ---- The suction: the forged neck from the pole, its heavy flange pair (the joint taking the head's growth), the spool.
        double tjs = Math.max(.03, .3 * ods), yNs = yP - .2, js = yNs - tjs - .005;
        u.mesh.prism(up, .62 * ods, yNs, yP + .15, ProcessUnit.sides(1.24 * ods), Material.SHELL);
        u.heavyJoint(up, ods, js);
        u.mesh.prism(up, ods / 2, yC + .15, js - .005 - tjs, ProcessUnit.sides(ods), Material.PIPE);
        u.solids.add(Solid.rod(axis, 1, yC + .15, yNs, ods / 2, 0));
        u.solids.add(Solid.rod(axis, 1, js - .005 - tjs, js + .005 + tjs, .95 * ods, 0));

        // ---- The discharge: out of the casing's side to the left, a short-radius elbow up, its heavy joint, the plenum nozzle.
        int nd = ProcessUnit.sides(odd);
        double yD = (yJ + tj + yC) / 2, xBend = a + off - odd;
        var along = Frame.of(new double[]{0, yD, a}, 0, 1);
        u.mesh.frustum(along, .62 * odd, odd / 2, a + dc / 2 - .05, a + dc / 2 + .12, nd, Material.MACHINE);
        u.mesh.prism(along, odd / 2, a + dc / 2 + .12, xBend, nd, Material.PIPE);
        u.elbow(new double[]{xBend, yD, a}, new double[]{1, 0, 0}, UP, odd, odd / 2, nd, 6, Material.PIPE);
        plenumLine(u, a, a + off, a, yB, shell, odd, yD + odd);
        u.solids.add(Solid.rod(new double[]{0, yD, a}, 0, a + dc / 2 - .05, xBend, .62 * odd, 0));
        u.solids.add(ProcessUnit.span(new double[]{xBend - odd / 2, yD - odd / 2, a - odd / 2}, new double[]{a + off + odd / 2, yD + 1.5 * odd, a + odd / 2}));

        // ---- The combined feed: down from its plenum nozzle opposite the recycle to the tee, FEED and HYDROGEN out of it
        // through the front and back openings.
        double xF = a - offF;
        plenumLine(u, a, xF, a, yB, shell, odc, PORT_ROW);
        u.mesh.sphere(new double[]{xF, PORT_ROW, a}, .55 * odc, ProcessUnit.sides(odc), Material.PIPE);
        double corner = skirt + EquipmentShape.BASE_RING + .15, zFeed = Math.floor(a - corner), zH2 = Math.ceil(a + corner);
        u.nozzles.add(new Nozzle(Role.FEED, new double[]{xF, PORT_ROW, a}, 2, -1, a - zFeed, PipeSizes.od(odF, .1, odF), 0));
        u.nozzles.add(new Nozzle(Role.HYDROGEN, new double[]{xF, PORT_ROW, a}, 2, 1, zH2 - a, PipeSizes.od(odH, .1, odH), 0));

        // ---- The withdrawal vessel at grade to the left, as near the skirt as its line's sleeve keeps off the openings.
        double dv = catalystVessel(d), rv = dv / 2, tv = catalystVesselLength(d);
        int nv = ProcessUnit.sides(dv);
        double most = Math.max(.2, Math.PI / 2 - DeterministicMath.asin(Math.min(1, hw / skirt)) - .17), dist = skirt + .35 + Math.max(rv, legCircle(rv));
        int kz = (int) Math.max(1, Math.round(dist * DeterministicMath.sin(Math.min(most, Math.PI / 7))));
        double xo = Math.max(Math.sqrt(Math.max(0, dist * dist - kz * kz)), kz * DeterministicMath.cos(most) / DeterministicMath.sin(most));
        double xv = a + xo, zv = a + kz, tw = DeterministicMath.atan2(kz, xo);
        double yA = .8, yV0 = yA + 1.7 * rv, yV1 = yV0 + tv;
        var vf = Frame.of(new double[]{xv, 0, zv}, 1, 1);
        u.mesh.prism(vf, rv, yV0, yV1, nv, Material.SHELL);
        u.mesh.head(vf, rv, yV1, rv / 2, nv, Material.HEAD);
        u.mesh.frustum(vf, .07, rv, yA, yV0, nv, Material.SHELL);
        u.mesh.cylinder(vf, .1, yA - .05, yA + .02, 12, Material.FLANGE);
        legs(u, xv, zv, rv, 0, yV0 + .3);
        u.solids.add(Solid.rod(new double[]{xv, 0, zv}, 1, yV0, yV1, rv, rv / 2));
        u.solids.add(Solid.cone(new double[]{xv, 0, zv}, 1, yA, yV0, .07, rv, 0));
        u.round(xv, zv, yA, yV1, rv, 1);
        double xOut = Math.ceil(xv + rv + .3);
        u.nozzles.add(new Nozzle(Role.CATALYST_OUTLET, new double[]{xv + .1, .5, zv}, 0, 1, xOut - xv - .1, .1, yA + .03));
        // The spent catalyst line: from the bottom head 0.8 of its radius out, a ball valve, through the skirt's sleeve,
        // over the vessel and down into it (a second valve where the drop has room); its leg out runs radially, so it
        // collides as a ring sector.
        double rhoC = .8 * shell, yHc = yB - rise(shell, rhoC), yL = yHc - .75, yIn = yV1 + rv / 2 - .05;
        var cf = Frame.of(around(a, a, rhoC, tw, 0), 1, 1);
        u.mesh.prism(cf, .07, yHc - .2, yHc + .1, 12, Material.SHELL);
        u.mesh.cylinder(cf, .11, yHc - .24, yHc - .2, 12, Material.FLANGE);
        double[] c0 = around(a, a, rhoC, tw, yHc - .24), c1 = around(a, a, rhoC, tw, yL), c2 = {xv, yL, zv}, c3 = {xv, yIn, zv};
        u.line(.05, Material.PIPE, c0, c1, c2, c3);
        u.solids.add(Solid.rod(new double[]{c0[0], 0, c0[2]}, 1, yL - .05, yHc - .24, .05, 0));
        u.solids.add(Solid.ring(a, a, yL - .05, yL + .05, rhoC - .05, Math.sqrt(xo * xo + kz * kz) + .05, tw - .05 / rhoC, tw + .05 / rhoC));
        u.solids.add(Solid.rod(new double[]{xv, 0, zv}, 1, Math.min(yIn, yL), Math.max(yIn, yL) + .05, .05, 0));
        u.valve(c0[0], yHc - .51, c0[2], .05, tangential(tw));
        if (yL - yIn >= .75) u.valve(xv, (yL + yIn) / 2, zv, .05, outward(tw));
        u.mesh.ring(new Frame(around(a, a, skirt - .08, tw, yL), outward(tw), UP), .05, .11, 0, .12, 12, 3, Material.SUPPORT);

        // ---- The barrier-oil cooler on brackets off the skirt at the front left, its supply and return to the motor.
        double xc = a + skirt + .3, yc = EquipmentShape.PLATE + 2, zc = a - .35;
        var cool = Frame.of(new double[]{xc, yc, 0}, 2, 1);
        u.mesh.prism(cool, .15, zc - .6, zc + .6, 12, Material.SHELL);
        u.mesh.cylinder(cool, .19, zc - .75, zc - .6, 12, Material.FLANGE);
        u.mesh.cylinder(cool, .19, zc + .6, zc + .75, 12, Material.FLANGE);
        for (double dz : new double[]{-.4, .4})
            u.box(new double[]{a + skirt - .03, yc - .27, zc + dz - .05}, new double[]{xc + .12, yc - .15, zc + dz + .05}, Material.STEELWORK, true);
        u.solids.add(Solid.rod(new double[]{xc, yc, 0}, 2, zc - .75, zc + .75, .19, 0));
        for (int k = 0; k < 2; k++) {
            double z = a + (k == 0 ? -.1 : .1), y = yc + .35 + .15 * k;
            u.pipe(.03, Material.PIPE, true, new double[]{xc, yc + .1, z}, new double[]{xc, y, z}, new double[]{a + dm / 2 - .02, y, z});
        }

        // ---- Platforms: a part ring a metre over the bottom tangent, on the shell, or in a bay over 8 m a rest on the skirt
        // under the tangent, 9 m up at most (it crosses the front opening); a full ring at mid height, part rings resting
        // climbs over 9 m (Access.climbs). None on the tangent itself, where its brackets would hang off the skirt's top: a
        // metre lower onto the skirt, or higher where the climb over it would then be too long.
        int mid = (int) Math.floor(yB + length / 2.0), bay = s + 1 <= Access.MAX_CLIMB ? s + 1 : Math.min(s - 1, Access.MAX_CLIMB);
        var levels = Access.climbs(0, List.of(bay, mid), level);
        levels.removeLast();
        offTangent(levels, s, level);
        // Caged ladders left and right, the one from the ground on the right; a part ring runs the half turn over the front
        // from where its ladder comes up to the next one's foot, off the withdrawal line; decks under the bottom tangent
        // round the skirt (HydroprocessingParts.access).
        int m = levels.size(), low = underTangent(levels, yB);
        double upAngle = m % 2 == 0 ? Math.PI : 0;
        var rings = new ArrayList<Ring>();
        for (int j = 0; j < m; j++) {
            int lv = levels.get(j);
            double in = (m - j) % 2 == 0 ? upAngle : Math.PI - upAngle, clear = Access.clearance(j < low ? skirt : wall);
            rings.add(lv == mid ? new Ring(lv, in, in, in + 2 * Math.PI) : in == 0 ? new Ring(lv, 0, -Math.PI - clear, 0) : new Ring(lv, in, Math.PI, 2 * Math.PI + clear));
        }
        double girder = girder(d), deck = level + Access.DECK;
        access(u, a, wall, skirt, levels, low, rings, upAngle, level, girder);

        // ---- The top: the effluent nozzle, its elbow and run to the front; the davit at the front right.
        double p = Math.min(PipeSizes.MAX_OUTSIDE, .12 * d);
        var run = topNozzle(u, a, a, yT + wall, p, deck);
        double zEff = Math.floor(a - wall - TOP_DECK - .05);
        u.nozzles.add(new Nozzle(Role.EFFLUENT, new double[]{a, run[0], run[1]}, 2, -1, run[1] - zEff, PipeSizes.od(.12 * d, .1, p), 0));
        u.davit(a, a, -3 * Math.PI / 4, wall + .6, deck, Math.max(deck + 3, run[0] + p / 2 + 1.4), -.2);

        // ---- The catalyst addition vessel on legs on the top head behind the nozzle (clear of its flanges, on a cell
        // centre where it still stands on the head), a ball valve on its line into the head; CATALYST_INLET on its top head
        // at the nearest cell centre.
        double rhoA = 1.075 * p + rv + .1;
        if (Math.ceil(rhoA - 1e-9) + rv + .2 <= wall) rhoA = Math.ceil(rhoA - 1e-9);
        double yHa = yT + rise(wall, rhoA), yA0 = yHa + .75 + rv / 2, yA1 = yA0 + tv;
        var af = Frame.of(new double[]{a, 0, a + rhoA}, 1, 1);
        u.mesh.prism(af, rv, yA0, yA1, nv, Material.SHELL);
        u.mesh.head(af, rv, yA1, rv / 2, nv, Material.HEAD);
        u.mesh.head(Frame.of(new double[]{a, 0, a + rhoA}, 1, -1), rv, -yA0, rv / 2, nv, Material.HEAD);
        u.mesh.prism(af, .07, yHa - .1, yHa + .2, 12, Material.SHELL);
        u.mesh.cylinder(af, .11, yHa + .2, yHa + .24, 12, Material.FLANGE);
        u.line(.05, Material.PIPE, new double[]{a, yHa + .24, a + rhoA}, new double[]{a, yA0 - rv / 2 + .05, a + rhoA});
        u.valve(a, yHa + .5, a + rhoA, .05, new double[]{1, 0, 0});
        for (int k = 0; k < 4; k++) {
            double t = Math.PI / 4 + k * Math.PI / 2, lx = a + (rv + .05) * DeterministicMath.cos(t), lz = a + rhoA + (rv + .05) * DeterministicMath.sin(t);
            double foot = Math.max(yT + rise(wall, Math.sqrt((lx - a) * (lx - a) + (lz - a) * (lz - a))), deck) - .05;
            u.post(lx, lz, foot, yA0 + .3, .1, false);
        }
        u.solids.add(Solid.rod(new double[]{a, 0, a + rhoA}, 1, yA0 - rv / 2, yA1, rv, rv / 2));
        u.solids.add(Solid.rod(new double[]{a, 0, a + rhoA}, 1, yHa - .1, yA0, .15, 0));
        u.round(a, a + rhoA, yA0, yA1, rv, 1);
        // The inlet's neck rooted under the head where a pipe's largest neck's outer edge meets it (owner decision
        // 2026-10-21: a 1 m pipe has a 1 m root).
        int kI = (int) Math.round(rhoA);
        double outerA = (Math.abs(kI - rhoA) + PipeSizes.MAX_OUTSIDE / 2) / rv, yInlet = yA1 + rv / 2 * Math.sqrt(Math.max(0, 1 - outerA * outerA)) - .05;
        u.nozzles.add(new Nozzle(Role.CATALYST_INLET, new double[]{a, yInlet, a + kI}, 1, 1, Math.ceil(yA1 + rv / 2 + .25) - yInlet, .1, 0));

        // ---- Trunnions under the top deck's corners; density gauges up the front left; thermowells at the back right.
        var fittings = new ArrayList<double[]>();
        trunnions(u, a, wall, d, level, girder, fittings, Math.PI / 4, -3 * Math.PI / 4);
        for (int k = 0; k < 5; k++) {
            double y = yB + length * (.4 + .1 * k);
            if (!clear(y, levels, .9, .2)) continue;
            gauge(u, a, wall, -Math.PI / 4, y);
            fittings.add(new double[]{y - .4, y + .4});
        }
        for (double f : new double[]{.25, .45, .65}) {
            double y = yB + f * length;
            if (!clear(y, levels, .75, .15)) continue;
            u.thermowell(a, a, wall, 3 * Math.PI / 4, y);
            fittings.add(new double[]{y - .1, y + .1});
        }
        bands(u, up, wall, n, yB, yT, level, girder, levels, fittings);
    }

    /** The height of a hemispherical head's outside of radius {@code r} from the tangent at {@code rho} off its axis (0 beyond it). */
    private static double rise(double r, double rho) {
        return rho >= r ? 0 : Math.sqrt(r * r - rho * rho);
    }

    /**
     * A line {@code od} across from a plenum nozzle on the bottom head (outer radius {@code shell}, tangent {@code yB}) at
     * (x, z), the axis through (a, a), straight down to {@code foot}: the forged nozzle 1.24 od across from inside the head
     * to 0.2 m under it, its heavy flange pair ({@link ProcessUnit#heavyJoint}), the pipe; colliding.
     */
    private static void plenumLine(ProcessUnit u, double a, double x, double z, double yB, double shell, double od, double foot) {
        double rho = Math.sqrt((x - a) * (x - a) + (z - a) * (z - a)), rn = .62 * od, tf = Math.max(.03, .3 * od);
        double yN = yB - rise(shell, Math.max(0, rho - rn)) - .2, station = yN - tf - .005, low = station - .005 - tf, inside = yB - rise(shell, rho + rn) + .1;
        var f = Frame.of(new double[]{x, 0, z}, 1, 1);
        u.mesh.prism(f, rn, yN, inside, ProcessUnit.sides(2 * rn), Material.SHELL);
        u.heavyJoint(f, od, station);
        if (low > foot + .01) u.mesh.prism(f, od / 2, foot, low, ProcessUnit.sides(od), Material.PIPE);
        u.solids.add(Solid.rod(new double[]{x, 0, z}, 1, foot, yN, od / 2, 0));
        u.solids.add(Solid.rod(new double[]{x, 0, z}, 1, low, yN, .95 * od, 0));
        u.solids.add(Solid.rod(new double[]{x, 0, z}, 1, yN, inside - .1, rn, 0));
    }

    /**
     * The radius of the circle a vessel's legs stand on: at its shell, or wide enough that its outlet line at a pipe's
     * largest leaves between two of them (owner decision 2026-10-21: a 1 m pipe has a 1 m root, other components give way).
     */
    static double legCircle(double rv) {
        return Math.max(rv + .06, (PipeSizes.MAX_OUTSIDE / 2 + .05 + .06) / DeterministicMath.sin(Math.PI / 4));
    }

    /**
     * Four legs of a vertical vessel of radius {@code rv} on the axis through (x, z), at 45°, from {@code y0} to {@code y1},
     * on base plates, on their {@link #legCircle}; a bracket from the shell to each leg standing off it.
     */
    private static void legs(ProcessUnit u, double x, double z, double rv, double y0, double y1) {
        double out = legCircle(rv), gap = out - .06 - rv;
        for (int k = 0; k < 4; k++) {
            double t = Math.PI / 4 + k * Math.PI / 2, lx = x + out * DeterministicMath.cos(t), lz = z + out * DeterministicMath.sin(t);
            u.post(lx, lz, y0, y1, .12, true);
            if (gap > .01) {
                double[] centre = around(x, z, rv + gap / 2, t, y1 - .1), ex = scale(outward(t), gap / 2 + .02), ey = {0, .1, 0}, ez = scale(tangential(t), .05);
                u.mesh.block(centre, ex, ey, ez, Material.STEELWORK);
                u.solids.add(Solid.block(centre, ex, ey, ez));
            }
        }
    }

    /** A nuclear density gauge's detector on the cladding (radius {@code wall}) at angle {@code t} and height {@code y}: its bracket and housing. */
    private static void gauge(ProcessUnit u, double a, double wall, double t, double y) {
        var c = around(a, a, wall + .26, t, 0);
        var f = Frame.of(c, 1, 1);
        u.mesh.bar(around(a, a, wall - .02, t, y - .25), around(a, a, wall + .16, t, y - .25), .08, UP, Material.STEELWORK);
        u.mesh.bar(around(a, a, wall - .02, t, y + .25), around(a, a, wall + .16, t, y + .25), .08, UP, Material.STEELWORK);
        u.mesh.cylinder(f, .1, y - .35, y + .3, 12, Material.GUARD);
        u.mesh.cylinder(f, .07, y + .3, y + .38, 12, Material.MOTOR);
        u.solids.add(Solid.rod(c, 1, y - .35, y + .38, .1, 0));
    }
}
