package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.equipment.ProcessUnit.MotorDetail;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Motion;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Solid;

import java.util.List;

import static com.wormzjl.icheme.science.equipment.Mesh.ALL_FACES;
import static com.wormzjl.icheme.science.equipment.Mesh.scale;

/**
 * Reverse-flow cyclones (owner request 2026-10-18, {@code NEW_UNITS_PLAN.md}; {@code NEW_UNITS_SURVEY_FCC_COKER.md}
 * section 2): one to four Stairmand high-efficiency cyclones of body diameter D, 0.3 to 2 m (the gas outlet, half of it,
 * stays within the 1 m pipe), in a row D + 1.5 m apart (to the next metre) on common headers. Stairmand's proportions over
 * D [Leith &amp; Mehta; Koch &amp; Licht 1977]: a tangential inlet 0.5 high and 0.2 wide, its top flush with the roof and
 * its outer wall tangent to the shell, fed by a duct of 1 D and a round-to-rectangle transition of 0.7 D (one cyclone's
 * eccentric, its round end on the feed nozzle's cell centre, its walls within 30° of the axis); a gas outlet of
 * 0.5 rising at least 0.4 D over the roof into a 90° elbow (one cyclone) or the outlet header over the row; a cylinder of
 * 1.5 on a flanged joint over a cone of 2.5 (longer than the cylinder: the signature) down to a dust outlet of 0.375 on a
 * short spool whose flange stands {@code support} up (the spool, 0.05 to 1 m, sets the inlet's centre on a cell centre).
 * Under it a dipleg of 1 D, or from 1 m a conical hopper, and a rotary airlock (flange 0.375 D and 0.15 m at least,
 * housing 1.6 of it square and 1.2 high) with its right-angle gearmotor, whose coupling turns at
 * {@link #AIRLOCK_SPEED} (Rotolok: dust-collector airlocks at 28-29 rpm); several cyclones' airlocks drop into a screw
 * conveyor on the structure's beams with its spout at the right (-x) end. Each cyclone hangs by four lugs at its
 * cylinder-cone joint on the beams of the row's structure (posts at its ends and between cyclones, X-braced at the back
 * and the ends, the front left open to reach the airlocks); from {@link #PLATFORM} a walkway runs along the front at roof
 * level over the inlet ducts, reached by caged ladders at its right end, a rest platform between them wherever one climb
 * would pass {@link Access#MAX_CLIMB} ({@link #climb}). A bolted hatch on the cylinder (a second from
 * 1 m) and an explosion vent on the roof from 0.8 m. Nozzles: gas in at the transition's end or the inlet manifold's
 * front, 0.36 D √N (the inlet's area); gas out after the elbow or from the outlet header's back, 0.5 D √N, both at most
 * 1 m; solids down under the airlock's chute or the conveyor's spout.
 */
final class CycloneDesign extends UnitDesign {
    /**
     * Stairmand high-efficiency proportions over the body diameter [Leith &amp; Mehta; Koch &amp; Licht 1977]: the inlet's
     * height and width, the gas outlet, the cylinder, the cone and the dust outlet.
     */
    static final double INLET_HEIGHT = .5, INLET_WIDTH = .2, OUTLET = .5, CYLINDER = 1.5, CONE = 2.5, DUST = .375;
    /** The inlet's duct beyond the shell and its round-to-rectangle transition, body diameters. */
    static final double DUCT = 1, TRANSITION = .7;
    /**
     * Gas speed in the inlets, m/s (15 to 25), the pressure drop in inlet velocity heads, air's density, kg/m³, and
     * viscosity, Pa s, and a mineral dust's density, kg/m³.
     */
    static final double INLET_SPEED = 20, HEADS = 8, GAS_DENSITY = 1.2, GAS_VISCOSITY = 1.8e-5, DUST_DENSITY = 2500;
    /** The gas's effective turns in a Stairmand cyclone, (1.5 + 2.5 / 2) / 0.5 (Lapple). */
    static final double TURNS = 5.5;
    /** A rotary airlock's shown speed, rev/s: 30 rpm, whole turns in {@link ProcessUnit#MOTION_PERIOD}. */
    static final double AIRLOCK_SPEED = .5;
    /** The smallest body carrying the roof walkway and its caged ladder, m (survey: from 1.2 m); the walkway's width, m. */
    static final double PLATFORM = 1.2, WALK = 1;
    /** The highest dust outlet, m. */
    static final int MAX_DUST_OUTLET = 8;

    @Override
    public double minSize() {
        return .3;
    }

    @Override
    public double maxSize() {
        return 2;
    }

    @Override
    public double sizeStep() {
        return .1;
    }

    @Override
    public int minLength(double size) {
        return 1;
    }

    @Override
    public int maxLength(double size) {
        return 4;
    }

    /** The lowest dust outlet whose solids outlet keeps its pipe block over the ground. */
    @Override
    public int minSupport(double size, int length) {
        return Math.max(1, (int) Math.ceil(1.04 + ProcessUnit.NECK + below(size, length)));
    }

    @Override
    public int maxSupport() {
        return MAX_DUST_OUTLET;
    }

    @Override
    public Orientation orientation() {
        return Orientation.VERTICAL;
    }

    @Override
    public boolean moves() {
        return true;
    }

    @Override
    public String sizeRow() {
        return "cyclone.body";
    }

    @Override
    public String lengthRow() {
        return "cyclone.count";
    }

    @Override
    public boolean lengthInMetres() {
        return false;
    }

    @Override
    public String supportRow() {
        return "cyclone.dust_outlet";
    }

    @Override
    public Line held(EquipmentShape shape) {
        double d = shape.diameter();
        return new Line("cyclone.flow", whole(flow(d, (int) shape.tangentLength())), number(cut(d)));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double d = shape.diameter();
        int count = (int) shape.tangentLength();
        return List.of(new Line("cyclone", count, size(d), number((CYLINDER + CONE) * d)), new Line("cyclone.duty", whole(flow(d, count)), number(drop()), number(cut(d))));
    }

    /** Gas through {@code count} cyclones of body {@code d}, m³/h: {@link #INLET_SPEED} through each inlet of 0.5 D × 0.2 D. */
    static double flow(double d, int count) {
        return count * INLET_SPEED * INLET_HEIGHT * d * INLET_WIDTH * d * 3600;
    }

    /**
     * The cut size, µm, the particle caught half the time: Lapple's d50 = √(9 μ b / (2π N v (ρp - ρg))), about 2.4 µm at
     * 0.3 m and 6 µm at 2 m; it grows as √D, so several small cyclones catch finer dust than one large one.
     */
    static double cut(double d) {
        return 1e6 * Math.sqrt(9 * GAS_VISCOSITY * INLET_WIDTH * d / (2 * Math.PI * TURNS * INLET_SPEED * (DUST_DENSITY - GAS_DENSITY)));
    }

    /** The pressure drop, kPa: {@link #HEADS} inlet velocity heads of air. */
    static double drop() {
        return HEADS * GAS_DENSITY * INLET_SPEED * INLET_SPEED / 2 / 1000;
    }

    /** A rotary airlock's flange, m: the dust outlet, 0.15 m at least. */
    static double lock(double d) {
        return Math.max(.15, DUST * d);
    }

    /** The dipleg's length (1 D, 0.3 m at least) or, from 1 m, the hopper's (1 D and its roof's flange), m. */
    static double leg(double d) {
        return d < 1 - 1e-9 ? Math.max(.3, d) : d + .05;
    }

    /** A screw conveyor's trough across and deep under airlocks of {@code lock}, m, and its spout's length. */
    static double trough(double lock) {
        return Math.max(.25, 1.1 * lock);
    }

    static double spout(double lock) {
        return .1 + .2 * trough(lock);
    }

    /** How far under the dust outlet's flange the lowest outlet flange hangs, m: the dipleg or hopper, the airlock and, with several cyclones, the conveyor and its spout. */
    static double below(double d, int count) {
        double lock = lock(d), drop = leg(d) + 1.2 * lock;
        return count > 1 ? drop + trough(lock) + spout(lock) : drop;
    }

    /**
     * The chute that brings an outlet flange at {@code y} down to just over a block face (its nozzle's neck then
     * {@link ProcessUnit#NECK} and 0.06 m long), m; none within 0.02 m.
     */
    static double chute(double y) {
        double over = y - .03 - ProcessUnit.NECK - Math.floor(y - .03 - ProcessUnit.NECK + 1e-6);
        return over < .02 ? 0 : over;
    }

    /** The dust outlet's spool, m: 0.05 to 1.05, so the inlet's centre, 3.75 D over the cone's bottom, lies on a cell centre. */
    static double spool(double d) {
        double h = (CONE + CYLINDER - INLET_HEIGHT / 2) * d;
        return Math.ceil(h - .45 - 1e-9) + .5 - h;
    }

    /** Draws the row where it lies, its first cyclone's axis on a cell centre: the box starts at the parts' and nozzle faces' least whole metre. */
    @Override
    void build(ProcessUnit unit) {
        draw(unit, .5, .5);
    }

    /** The row with its first cyclone's axis at (x0, az): see the class. */
    private static void draw(ProcessUnit u, double x0, double az) {
        double d = u.shape.diameter(), r = d / 2;
        int count = (int) u.shape.tangentLength(), s = u.shape.support();
        double pitch = Math.ceil(d + 1.5 - 1e-9), lock = lock(d), dust = DUST * d, leg = leg(d);
        // The frame: posts ps square, e out from the axes in z and at the row's ends (one cyclone's posts and base plates
        // clear of the solids outlet's pipe block under its axis), half a pitch between cyclones; beams bd deep and bw wide
        // just under the cylinder-cone joint, the lugs on beams b out from each axis (a grillage of their own round small
        // bodies).
        double ps = .1 + .08 * d, e = count == 1 ? Math.max(r + .25 + .05 * d, .57 + ps / 2) : r + .25 + .05 * d, bd = .12 + .1 * d, bw = .08 + .05 * d;
        double b = r + .1 + .05 * d + bw / 2;
        if (e - b < bw + ps / 2) b = e;
        double yCone = s + spool(d), yJoint = yCone + CONE * d, yRoof = yJoint + CYLINDER * d, yFeed = yRoof - INLET_HEIGHT * d / 2, yBeam = yJoint + .05;
        boolean deck = d >= PLATFORM - 1e-9;
        int n = ProcessUnit.sides(d), nd = ProcessUnit.sides(dust), no = ProcessUnit.sides(OUTLET * d);
        double rf = .18 * d, feedOd = Math.min(PipeSizes.MAX_OUTSIDE, 2 * rf * Math.sqrt(count)), outOd = Math.min(PipeSizes.MAX_OUTSIDE, OUTLET * d * Math.sqrt(count));
        double zDuct = az - .3 * d - DUCT * d, zT = zDuct - TRANSITION * d;
        // One cyclone's transition is eccentric: its round end on the nearest cell centre (the feed nozzle's), at least √3
        // times that offset long so its walls stay within 30° of the axis, as a fabricated offset transition's do.
        double xt0 = x0 + r - INLET_WIDTH * d / 2, xf = Math.floor(xt0) + .5;
        double zEnd = count == 1 ? zDuct - Math.max(TRANSITION * d, Math.sqrt(3) * Math.abs(xf - xt0)) : zT;
        double xLast = x0 + (count - 1) * pitch;
        for (int k = 0; k < count; k++) {
            double x = x0 + k * pitch;
            var up = Frame.of(new double[]{x, 0, az}, 1, 1);
            double[] axis = {x, 0, az};
            // The body: the cylinder and its roof, the cone, the dust outlet's spool and its flanges; the flanged joint
            // between the cylinder and the cone, its nuts from 0.6 m. Flanges whose bore lies inside a closed run are hoops.
            u.mesh.prism(up, r, yJoint, yRoof, n, Material.SHELL);
            u.mesh.annulus(up, OUTLET * d / 2, r, yRoof, n, 1, Material.SHELL);
            u.mesh.frustum(up, dust / 2, r, yCone, yJoint, n, Material.SHELL);
            double fd = PipeSizes.flange(dust) / 2;
            u.mesh.prism(up, dust / 2, s + .03, yCone - .03, nd, Material.SHELL);
            u.mesh.hoop(up, dust / 2, fd, yCone - .03, yCone, nd, Material.FLANGE);
            u.mesh.hoop(up, dust / 2, fd, s, s + .03, nd, Material.FLANGE);
            double jr = r + .04 + .02 * d;
            u.mesh.hoop(up, r, jr, yJoint - .03, yJoint + .03, n, Material.FLANGE);
            if (d >= .6 - 1e-9) {
                int nuts = 4 * (int) Math.round(Math.PI * d / 1.2);
                double nut = .02 + .01 * d;
                for (int j = 0; j < nuts; j++) {
                    var at = up.at(2 * Math.PI * (j + .5) / nuts, (r + jr) / 2, yJoint + .03);
                    if (u.mesh.detail() == RenderDetail.LOW) u.mesh.nut(Frame.of(at, 1, 1), nut, 0, 1.4 * nut, 6, Material.BOLT);
                    else u.mesh.box(new double[]{at[0] - nut, at[1], at[2] - nut}, new double[]{at[0] + nut, at[1] + 1.4 * nut, at[2] + nut}, ALL_FACES & ~4, Material.BOLT);
                }
            }
            u.solids.add(Solid.rod(axis, 1, yJoint, yRoof, r, 0));
            u.solids.add(Solid.cone(axis, 1, yCone, yJoint, dust / 2, r, 0));
            u.solids.add(Solid.rod(axis, 1, s, yCone, fd, 0));
            u.volumes.add(Solid.rod(axis, 1, yJoint, yRoof - .05, r - .05, 0));
            u.volumes.add(Solid.cone(axis, 1, yCone + .05, yJoint, Math.max(.01, dust / 2 - .05), r - .05, 0));
            u.round(x, az, yJoint, yRoof, r, 1);
            // The tangential inlet: its duct (outer wall tangent at +x, top just under the roof, its back hidden in the
            // shell, its front in the transition) and the round-to-rectangle transition in front of it, along -z.
            u.box(new double[]{x + r - INLET_WIDTH * d, yRoof - INLET_HEIGHT * d, zDuct}, new double[]{x + r, yRoof - .01, az}, ALL_FACES & ~(16 | 32), Material.SHELL, true);
            double xt = x + r - INLET_WIDTH * d / 2, xc = count == 1 ? xf : xt;
            u.transition(2, x + r - INLET_WIDTH * d, x + r, yRoof - INLET_HEIGHT * d, yRoof - .01, zDuct, xc, yFeed, rf, zEnd, ProcessUnit.sides(2 * rf), Material.SHELL, false);
            u.solids.add(Solid.box(new double[]{Math.min(xt, xc) - rf, yFeed - INLET_HEIGHT * d / 2, zEnd}, new double[]{Math.max(xt, xc) + rf, yFeed + INLET_HEIGHT * d / 2, zDuct}));
            // A bolted hatch on the cylinder's back right, a second at its front right from 1 m (between the lugs); the
            // roof's explosion vent from 0.8 m.
            u.door(x, az, r, .75 * Math.PI, yJoint + .55 * CYLINDER * d, .08 + .06 * d, .1 + .08 * d);
            if (d >= 1 - 1e-9) u.door(x, az, r, -.75 * Math.PI, yJoint + .4 * CYLINDER * d, .08 + .06 * d, .1 + .08 * d);
            if (d >= .8 - 1e-9) {
                double vx = x - .55 * r, vz = az + .5 * r, vh = .15 * r;
                u.box(new double[]{vx - vh, yRoof, vz - vh}, new double[]{vx + vh, yRoof + .05, vz + vh}, ALL_FACES & ~4, Material.MOTOR, false);
            }
            lugs(u, x, az, r, b, bw, yBeam, d);
            // Under the dust outlet: a dipleg widening into the airlock's flange, or from 1 m a hopper; the airlock.
            double yLock = s - leg;
            if (d < 1 - 1e-9) {
                double adapter = lock > dust + .01 ? .08 + .1 * lock : 0, fl = PipeSizes.flange(lock) / 2;
                u.mesh.prism(up, dust / 2, yLock + .03 + adapter, s - .03, nd, Material.SHELL);
                u.mesh.hoop(up, dust / 2, fd, s - .03, s, nd, Material.FLANGE);
                if (adapter > 0) u.mesh.frustum(up, lock / 2, dust / 2, yLock + .03, yLock + .03 + adapter, ProcessUnit.sides(lock), Material.SHELL);
                u.mesh.hoop(up, lock / 2 - .01, fl, yLock, yLock + .03, ProcessUnit.sides(lock), Material.FLANGE);
                u.solids.add(Solid.rod(axis, 1, yLock, s, Math.max(lock, dust) / 2, 0));
            } else {
                double rh = .4 * d, top = s - .03, belly = top - .4 * d, fl = PipeSizes.flange(lock) / 2;
                u.mesh.hoop(up, dust / 2, fd, top, s, nd, Material.FLANGE);
                u.mesh.annulus(up, dust / 2, rh, top, n, 1, Material.SHELL);
                u.mesh.prism(up, rh, belly, top, n, Material.SHELL);
                u.mesh.frustum(up, lock / 2, rh, yLock + .03, belly, n, Material.SHELL);
                u.mesh.hoop(up, lock / 2 - .01, fl, yLock, yLock + .03, ProcessUnit.sides(lock), Material.FLANGE);
                u.solids.add(Solid.rod(axis, 1, belly, top, rh, 0));
                u.solids.add(Solid.cone(axis, 1, yLock, belly, lock / 2, rh, 0));
                u.volumes.add(Solid.rod(axis, 1, belly, top - .05, rh - .05, 0));
                u.round(x, az, belly, top, rh, 1);
            }
            airlock(u, x, az, yLock, lock, .25 * k);
            if (count == 1) {
                // A flanged chute under the airlock brings the solids outlet's flange just over a block face (the flange
                // over the outlet keeps its bore, seen up the open outlet).
                double yOut = yLock - 1.2 * lock, chute = chute(yOut);
                if (chute > 0) {
                    var down = Frame.of(new double[]{x, 0, az}, 1, 1);
                    int nl = ProcessUnit.sides(lock);
                    if (chute > .07) {
                        u.mesh.prism(down, lock / 2, yOut - chute + .03, yOut - .03, nl, Material.SHELL);
                        u.mesh.hoop(down, lock / 2 - .01, PipeSizes.flange(lock) / 2, yOut - .03, yOut, nl, Material.FLANGE);
                        u.mesh.ring(down, lock / 2 - .01, PipeSizes.flange(lock) / 2, yOut - chute, yOut - chute + .03, nl, 3, Material.FLANGE);
                    } else u.mesh.ring(down, lock / 2 - .01, PipeSizes.flange(lock) / 2, yOut - chute, yOut, nl, 3, Material.FLANGE);
                    u.solids.add(Solid.rod(new double[]{x, 0, az}, 1, yOut - chute, yOut, PipeSizes.flange(lock) / 2, 0));
                    yOut -= chute;
                }
                u.nozzles.add(ProcessUnit.end(Role.SOLIDS_OUTLET, new double[]{x, yOut + .03, az}, 1, -1, lock));
            }
        }
        // The structure: column lines at the row's ends and half a pitch between cyclones.
        var columns = new double[count + 1];
        columns[0] = x0 - e;
        columns[count] = xLast + e;
        for (int j = 1; j < count; j++) columns[j] = x0 + (j - .5) * pitch;
        double endLow = count > 1 ? s - leg - 1.2 * lock + .15 : .3;
        structure(u, columns, x0, pitch, count, az, e, b, ps, bd, bw, yBeam, deck ? yRoof : yBeam, endLow, .06 + .04 * d);
        // The gas in: one cyclone's nozzle at its transition's round end; several cyclones' run straight into the inlet
        // manifold in front, the nozzle on its front near the middle.
        if (count == 1) u.nozzles.add(ProcessUnit.front(Role.FEED, xf, yFeed, zEnd + .02, feedOd));
        else {
            double rm = rf * Math.sqrt(count), zm = zT - .3 - rm, xa = xt0, xb = xt0 + (count - 1) * pitch;
            int nm = ProcessUnit.sides(2 * rm);
            for (int k = 0; k < count; k++) u.mesh.prism(Frame.of(new double[]{xt0 + k * pitch, yFeed, 0}, 2, 1), rf, zm, zT + .01, ProcessUnit.sides(2 * rf), Material.PIPE);
            u.solids.add(ProcessUnit.span(new double[]{xa - rf, yFeed - rf, zm}, new double[]{xb + rf, yFeed + rf, zT}));
            header(u, xa, xb, yFeed, zm, rm, nm);
            // Two posts under the manifold's ends, each with its saddle (its top inside the manifold).
            for (double xp : new double[]{xa, xb}) {
                u.post(xp, zm, 0, yFeed - rm, ps, true);
                u.box(new double[]{xp - .6 * rm, yFeed - rm - .04, zm - ps / 2}, new double[]{xp + .6 * rm, yFeed - .7 * rm, zm + ps / 2}, ALL_FACES & ~8, Material.STEELWORK, false);
            }
            u.nozzles.add(ProcessUnit.radialSide(Role.FEED, Math.floor((xa + xb) / 2) + .5, yFeed, zm, rm, nm, -1, feedOd));
        }
        // The gas out: one cyclone's outlet rises into a 90° elbow to the back; several rise into the outlet header over
        // the row, the nozzle on its back at the middle cyclone.
        double ro = OUTLET * d / 2;
        if (count == 1) {
            double bend = 2 * ro, yE = Math.ceil(yRoof + .4 * d + bend - .5 - 1e-9) + .5;
            var up = Frame.of(new double[]{x0, 0, az}, 1, 1);
            u.mesh.hoop(up, ro, ro + .04 + .03 * d, yRoof, yRoof + .04, no, Material.FLANGE);
            u.mesh.prism(up, ro, yRoof, yE - bend, no, Material.PIPE);
            u.elbow(new double[]{x0, yE - bend, az}, new double[]{0, 1, 0}, new double[]{0, 0, 1}, bend, ro, no, 6, Material.PIPE);
            u.solids.add(Solid.rod(new double[]{x0, 0, az}, 1, yRoof, yE - bend, ro, 0));
            u.solids.add(ProcessUnit.span(new double[]{x0 - ro, yE - bend, az - ro}, new double[]{x0 + ro, yE + ro, az + bend}));
            u.nozzles.add(ProcessUnit.end(Role.VAPOUR_OUTLET, new double[]{x0, yE, az + bend - .01}, 2, 1, outOd));
        } else {
            double rh = ro * Math.sqrt(count), yE = Math.ceil(yRoof + .4 * d + rh - .5 - 1e-9) + .5;
            int nh = ProcessUnit.sides(2 * rh);
            for (int k = 0; k < count; k++) {
                var up = Frame.of(new double[]{x0 + k * pitch, 0, az}, 1, 1);
                u.mesh.hoop(up, ro, ro + .04 + .03 * d, yRoof, yRoof + .04, no, Material.FLANGE);
                u.mesh.prism(up, ro, yRoof, yE - .5 * rh, no, Material.PIPE);
                u.solids.add(Solid.rod(new double[]{x0 + k * pitch, 0, az}, 1, yRoof, yE, ro, 0));
            }
            // A header lying along x: its axis on the frame's z, the outlet on its back (a radial nozzle along +z).
            header(u, x0, xLast, yE, az, rh, nh);
            u.nozzles.add(ProcessUnit.radialSide(Role.VAPOUR_OUTLET, x0 + (count - 1) / 2 * pitch, yE, az, rh, nh, 1, outOd));
        }
        // Several cyclones' airlocks drop into a screw conveyor: its trough with lipped ends on beams across the column
        // lines, the drive at its left (+x) end, the spout (lengthened to bring the solids outlet's flange just over a block
        // face) at its right end beyond the structure.
        if (count > 1) {
            double tw = trough(lock), yT1 = s - leg - 1.2 * lock, yT2 = yT1 - tw, half = tw / 2, sp = spout(lock) + chute(yT2 - spout(lock));
            double xs = x0 - Math.ceil(e + ps / 2 + .55 - 1e-9), xa = xs - half - .1, xb = xLast + e + ps / 2 + .2;
            u.box(new double[]{xa, yT2, az - half}, new double[]{xb, yT1, az + half}, Material.MACHINE, true);
            for (double xe : new double[]{xa - .03, xb})
                u.box(new double[]{xe, yT2 - .02, az - half - .03}, new double[]{xe + .03, yT1 + .02, az + half + .03}, Material.MACHINE, false);
            for (double xr = xa + .5; xr < xb - .3; xr += 1)
                u.box(new double[]{xr - .02, yT1, az - half - .02}, new double[]{xr + .02, yT1 + .025, az + half + .02}, ALL_FACES & ~4, Material.BOLT, false);
            // The drive: its bearing housing, the gearbox and a plain motor lying along +z off it (no feet: flange-mounted).
            double gb = .5 * tw + .1, yd = yT1 - half;
            u.mesh.cylinder(Frame.of(new double[]{0, yd, az}, 0, 1), .15 * tw + .02, xb + .03, xb + .1, 12, Material.MACHINE);
            u.box(new double[]{xb + .1, yd - gb / 2, az - gb / 2}, new double[]{xb + .1 + gb, yd + gb / 2, az + gb / 2}, Material.MACHINE, true);
            u.motor(Frame.of(new double[]{xb + .1 + gb / 2, yd, 0}, 2, 1), .3 * gb, az + gb / 2, az + gb / 2 + 1.4 * gb, 0, yd, MotorDetail.PLAIN);
            var spoutAxis = Frame.of(new double[]{xs, 0, az}, 1, 1);
            double so = Math.min(PipeSizes.MAX_OUTSIDE, lock), yOut = yT2 - sp;
            u.mesh.frustum(spoutAxis, so / 2, .45 * tw, yOut + .03, yT2, ProcessUnit.sides(so), Material.MACHINE);
            u.mesh.ring(spoutAxis, so / 2 - .01, PipeSizes.flange(so) / 2, yOut, yOut + .03, ProcessUnit.sides(so), 3, Material.FLANGE);
            u.solids.add(Solid.rod(new double[]{xs, 0, az}, 1, yOut, yT2, PipeSizes.flange(so) / 2, 0));
            double cd = .1 + .05 * d, cw = .08 + .03 * d;
            for (double xc : columns) u.box(new double[]{xc - cw / 2, yT2 - cd, az - e + ps / 2}, new double[]{xc + cw / 2, yT2, az + e - ps / 2}, Material.STEELWORK, true);
            u.nozzles.add(ProcessUnit.end(Role.SOLIDS_OUTLET, new double[]{xs, yOut + .03, az}, 1, -1, so));
        }
        // From 1.2 m: the walkway along the front at roof level over the inlet ducts, from the right-hand frame's posts to
        // the left-hand one's, open at its right end for the caged ladders (rests).
        if (deck) {
            double xs0 = x0 - e - ps / 2, xs1 = xLast + e + ps / 2, wall = az - e + .1;
            u.platform(new Access.Line(new double[]{xs0, 0, wall}, new double[]{1, 0, 0}, new double[]{0, 0, -1}, xs1 - xs0), yRoof, WALK, false, true);
            // The climb lands on the middle of the walkway's clear width, between its wall and its handrail's band (a player
            // centred on the deck's whole width met the band, 2026-10-25).
            climb(u, xs0, wall - (Access.GAP + WALK - Access.RAIL_BAND) / 2, az - e + ps / 2, yRoof, ps);
        }
    }

    /**
     * The caged climb from the ground onto the roof walkway at {@code level} (its deck level), whose open end lies on the
     * structure's right-hand face at x {@code face} (the end posts' outer face): the kit's ({@link ProcessUnit#climb}),
     * walked through onto that end at z; where it is longer than {@link Access#MAX_CLIMB}, its rest platform from 0.75 m
     * in front of the ladder back to the corner post's back face {@code back}, on posts {@code ps} square, its sections a
     * deck's width apart. A post behind each section carries it.
     */
    private static void climb(ProcessUnit u, double face, double z, double back, double level, double ps) {
        var run = new Access.Line(new double[]{face, 0, z - .75}, new double[]{0, 0, 1}, new double[]{-1, 0, 0}, back - z + .75);
        for (double rest : u.climb(run, 0, level + Access.DECK, .75, .75, ps)) u.post(face - Access.MIN_DECK + .05, z, 0, rest, .1, false);
        u.post(face + .05, z, 0, level, .1, false);
    }

    /** A header lying along x from {@code xa} to {@code xb} at (y, z), radius {@code rh}, with 2:1 heads; it collides and encloses as drawn. */
    private static void header(ProcessUnit u, double xa, double xb, double y, double z, double rh, int n) {
        var f = Frame.of(new double[]{0, y, z}, 0, 1);
        u.mesh.prism(f, rh, xa, xb, n, Material.PIPE);
        u.mesh.head(f, rh, xb, .5 * rh, n, Math.max(3, n / 4), Material.PIPE);
        u.mesh.head(Frame.of(new double[]{0, y, z}, 0, -1), rh, -xa, .5 * rh, n, Math.max(3, n / 4), Material.PIPE);
        u.solids.add(Solid.rod(new double[]{0, y, z}, 0, xa - .5 * rh, xb + .5 * rh, rh, 0));
        u.volumes.add(Solid.rod(new double[]{0, y, z}, 0, xa, xb, rh - .05, 0));
        u.round(new double[]{0, y, z}, 0, xa, xb, rh);
    }

    /**
     * Four lugs round a cyclone (axis at (x, z), radius r) at its cylinder's foot, each on a beam {@code bw} wide whose
     * centre lies {@code b} out: a base plate on the beam's top at {@code yBeam} and two gussets up against the shell.
     */
    private static void lugs(ProcessUnit u, double x, double z, double r, double b, double bw, double yBeam, double d) {
        double plate = .03 + .01 * d, gusset = .15 + .15 * d, width = .12 + .08 * d, t = .012 + .006 * d;
        for (double[] dir : new double[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            double[] radial = {dir[0], 0, dir[1]}, across = {-dir[1], 0, dir[0]};
            double out0 = r - .02, out1 = b + bw / 2 - .01, mid = (out0 + out1) / 2, g1 = r + .6 * (out1 - r), gm = (out0 + g1) / 2;
            u.mesh.block(new double[]{x + mid * dir[0], yBeam + plate / 2, z + mid * dir[1]}, scale(radial, (out1 - out0) / 2), new double[]{0, plate / 2, 0},
                    scale(across, width / 2), Material.SUPPORT);
            for (int side = -1; side <= 1; side += 2) {
                double off = side * (width / 2 - t);
                u.mesh.block(new double[]{x + gm * dir[0] + off * across[0], yBeam + plate + gusset / 2, z + gm * dir[1] + off * across[2]}, scale(radial, (g1 - out0) / 2),
                        new double[]{0, gusset / 2, 0}, scale(across, t), Material.SUPPORT);
            }
            u.solids.add(ProcessUnit.span(new double[]{x + out0 * dir[0] - width / 2 * across[0], yBeam, z + out0 * dir[1] - width / 2 * across[2]},
                    new double[]{x + out1 * dir[0] + width / 2 * across[0], yBeam + plate, z + out1 * dir[1] + width / 2 * across[2]}));
        }
    }

    /**
     * The row's structure: posts {@code ps} square on base plates at each column line ({@code columns}, along x) {@code e}
     * in front of and behind the axes, up to {@code top}; beams {@code bd} deep under {@code yBeam} along the front and back
     * and across at each column line; the lug beams along z {@code b} out from each axis where no column line runs and,
     * where {@code b} falls short of {@code e}, a grillage of their own along x. X-bracing ({@code brace} angles) in tiers of
     * at most 3 m in every back bay (the front left open to reach the airlocks, struts only) and at the row's ends (from {@code endLow}, over a screw conveyor running out
     * through them). A structure carrying the roof walkway rises to the roof with beams there along its back and at its
     * ends, braced on those faces (the inlet ducts pass its front, the walkway rests on its front posts).
     */
    private static void structure(ProcessUnit u, double[] columns, double x0, double pitch, int count, double az, double e, double b, double ps, double bd, double bw,
                                  double yBeam, double top, double endLow, double brace) {
        int last = columns.length - 1;
        for (double xc : columns)
            for (double zc : new double[]{az - e, az + e}) u.post(xc, zc, 0, top, ps, true);
        boolean high = top > yBeam + 1e-9;
        for (int j = 0; j < last; j++)
            for (int side = -1; side <= 1; side += 2) {
                double[] p = {columns[j], az + side * e}, q = {columns[j + 1], az + side * e};
                beam(u, p, q, yBeam - bd, yBeam, ps, bw);
                tiers(u, p, q, .3, yBeam - bd, ps, bw, side > 0 ? brace : 0);
                if (high && side > 0) {
                    beam(u, p, q, top - bd, top, ps, bw);
                    tiers(u, p, q, yBeam, top - bd, ps, bw, brace);
                }
            }
        for (int j = 0; j <= last; j++) {
            double[] p = {columns[j], az - e}, q = {columns[j], az + e};
            beam(u, p, q, yBeam - bd, yBeam, ps, bw);
            if (j > 0 && j < last) continue;
            tiers(u, p, q, endLow, yBeam - bd, ps, bw, brace);
            if (high) {
                beam(u, p, q, top - bd, top, ps, bw);
                tiers(u, p, q, yBeam, top - bd, ps, bw, brace);
            }
        }
        for (int k = 0; k < count; k++) {
            double x = x0 + k * pitch;
            for (int side = -1; side <= 1; side += 2) {
                double xb = x + side * b;
                boolean column = false;
                for (double xc : columns) column |= Math.abs(xc - xb) < 1e-6;
                if (!column) u.box(new double[]{xb - bw / 2, yBeam - bd, az - e + bw / 2}, new double[]{xb + bw / 2, yBeam, az + e - bw / 2}, Material.STEELWORK, true);
                if (b < e - 1e-9)
                    u.box(new double[]{x - b + bw / 2, yBeam - bd, az + side * b - bw / 2}, new double[]{x + b - bw / 2, yBeam, az + side * b + bw / 2}, Material.STEELWORK, true);
            }
        }
    }

    /** A beam between posts p and q ({x, z}, {@code ps} square) from y0 to y1, {@code bw} wide. */
    private static void beam(ProcessUnit u, double[] p, double[] q, double y0, double y1, double ps, double bw) {
        boolean alongX = Math.abs(q[0] - p[0]) > Math.abs(q[1] - p[1]);
        double a0 = Math.min(alongX ? p[0] : p[1], alongX ? q[0] : q[1]) + ps / 2, a1 = Math.max(alongX ? p[0] : p[1], alongX ? q[0] : q[1]) - ps / 2, c = alongX ? p[1] : p[0];
        if (alongX) u.box(new double[]{a0, y0, c - bw / 2}, new double[]{a1, y1, c + bw / 2}, Material.STEELWORK, true);
        else u.box(new double[]{c - bw / 2, y0, a0}, new double[]{c + bw / 2, y1, a1}, Material.STEELWORK, true);
    }

    /**
     * The kit's X-bracing ({@link ProcessUnit#brace}) of {@code brace} angles between posts p and q from {@code low} to
     * {@code high} in equal tiers of at most {@link ProcessUnit#BRACE_TIER}, a strut where two tiers meet; struts only where
     * {@code brace} is 0 (an open face).
     */
    private static void tiers(ProcessUnit u, double[] p, double[] q, double low, double high, double ps, double bw, double brace) {
        if (high - low < .5) return;
        int tiers = Math.max(1, (int) Math.ceil((high - low) / ProcessUnit.BRACE_TIER - 1e-9));
        for (int t = 1; t < tiers; t++) {
            double y = low + (high - low) * t / tiers;
            beam(u, p, q, y - .05, y + .05, ps, .6 * bw);
        }
        if (brace > 0) u.brace(p, q, low, high, brace, ProcessUnit.BRACE_TIER);
    }

    /**
     * A rotary airlock under a dust outlet at (x, z), its inlet flange's top at {@code top}, flange {@code lock}: square
     * flanges 1.6 of it across top and bottom, the throat between them, the rotor's housing lying along x with its bearing
     * caps, and on the left (+x) its right-angle gearmotor: the exposed shaft and jaw coupling (a moving part turning at
     * {@link #AIRLOCK_SPEED} from {@code phase}, its bolt heads showing it), the worm gearbox and the kit's plain motor
     * lying along +z, flange-mounted on it.
     */
    private static void airlock(ProcessUnit u, double x, double z, double top, double lock, double phase) {
        double half = .8 * lock, t = .03 + .03 * lock, bottom = top - 1.2 * lock, y = (top + bottom) / 2;
        u.box(new double[]{x - half, top - t, z - half}, new double[]{x + half, top, z + half}, Material.MACHINE, false);
        u.box(new double[]{x - half, bottom, z - half}, new double[]{x + half, bottom + t, z + half}, Material.MACHINE, false);
        u.box(new double[]{x - .45 * lock, bottom + t, z - .4 * lock}, new double[]{x + .45 * lock, top - t, z + .4 * lock}, ALL_FACES & ~(4 | 8), Material.MACHINE, false);
        var along = Frame.of(new double[]{0, y, z}, 0, 1);
        u.mesh.cylinder(along, .55 * lock, x - .6 * lock, x + .6 * lock, 16, Material.MACHINE);
        u.mesh.cylinder(along, .3 * lock, x - .75 * lock, x - .6 * lock, 12, Material.MACHINE);
        u.mesh.cylinder(along, .3 * lock, x + .6 * lock, x + .75 * lock, 12, Material.MACHINE);
        double gap = .2 * lock + .04, c0 = x + .75 * lock, g0 = c0 + gap, gs = .55 * lock, shaft = .015 + .04 * lock, hub = .04 + .1 * lock, bolt = .01 + .01 * lock;
        u.mesh.part(u.motions.size());
        u.mesh.prism(along, shaft, c0 - .01, g0 + .01, 8, Material.PIPE);
        u.mesh.cylinder(along, hub, c0 + .15 * gap, c0 + .45 * gap, 12, Material.FLANGE);
        u.mesh.cylinder(along, hub, c0 + .55 * gap, c0 + .85 * gap, 12, Material.FLANGE);
        for (int j = 0; j < 4; j++) {
            var at = along.at(Math.PI / 4 + j * Math.PI / 2, .65 * hub, c0 + .15 * gap);
            u.mesh.box(new double[]{at[0] - bolt, at[1] - bolt, at[2] - bolt}, new double[]{at[0], at[1] + bolt, at[2] + bolt}, ALL_FACES & ~2, Material.BOLT);
        }
        u.mesh.part(-1);
        u.motions.add(Motion.turning(new double[]{c0 + gap / 2, y, z}, new double[]{1, 0, 0}, AIRLOCK_SPEED, phase));
        u.box(new double[]{g0, y - gs / 2, z - gs / 2}, new double[]{g0 + gs, y + gs / 2, z + gs / 2}, Material.MACHINE, true);
        u.motor(Frame.of(new double[]{g0 + gs / 2, y, 0}, 2, 1), .26 * lock, z + gs / 2, z + gs / 2 + 1.1 * lock, 0, y, MotorDetail.PLAIN);
        u.solids.add(ProcessUnit.span(new double[]{x - half, bottom, z - half}, new double[]{x + half, top, z + half}));
    }

}
