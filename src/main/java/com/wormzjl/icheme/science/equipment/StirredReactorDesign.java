package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Nozzle;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.ArrayList;
import java.util.List;

import static com.wormzjl.icheme.science.equipment.Mesh.*;

/**
 * A jacketed stirred tank reactor (owner decisions 2026-10-18, NEW_UNITS_PLAN.md; {@code NEW_UNITS_SURVEY_STIRRED_ADSORPTION.md}
 * section 1): a closed-welded, top-driven DIN 28136 reactor (BE type, glass-lined or stainless) of shell diameter D, 1 to
 * 4 m in 0.2 m steps, and straight side S in whole metres (0.7 to 2 D), jacketed over its straight side and bottom head,
 * hung from four lugs in the opening of its own operating deck; {@code support} is the clearance under its bottom valve.
 *
 * <p>Proportions [3V-CE datasheet]: the jacket D + 0.1 m across (D + 0.15 m from 3.2 m), up to just under the lugs; 2:1
 * heads, 0.25 D deep; the lugs 0.2 D + 0.1 m under the top tangent; the drive (lantern, gearbox, in-line motor) 0.7 +
 * 0.385 D high over the agitator's flange. The deck's grating ({@link ProcessUnit#squareDeck}) stands on its beams at the
 * lugs' feet with 0.95 to 1.4 m of walkway round the opening, on four columns braced on its sides, a caged ladder walked
 * through on its left: from the ground up to 9 m, above that from a rest platform (owner decision C1 2026-10-18). The top
 * head carries the process nozzles' flanges over the four corner cells round the drive's (one free block
 * between pipes, owner rule 2026-10-17): straight up from the head from 3.8 m, where the corners lie on it; on a smaller
 * head as the survey's crown of nozzles at 0.6 R with spools rising to the corners, over head height where they stand over
 * the walkway. Between them the manway (inclined, swing-bolt clamps, where it fits: from about 1.4 m), the sight glasses
 * with their lamp and the thermowell; the drive's lantern shows the agitator's shaft and coupling turning through its
 * windows (60 rpm drawn; 30 to 110 real) and the motor's fan turns under its cowl. The jacket's coolant enters low at the
 * front and leaves high at the back, through spools to the nearest free cell rows where the jacket is short. The bottom
 * valve's outlet faces down on the axis.
 */
final class StirredReactorDesign extends UnitDesign {
    /** The agitator's shaft and coupling, rev/s (a whole turn every second: 60 rpm, a large reactor's 30 to 80). */
    static final double AGITATOR_SPEED = 1;
    /** An impeller's tip speed, m/s, for the datasheet's speed: glass-lined impellers run at about 3 to 5. */
    static final double TIP_SPEED = 3.5;

    @Override
    public double minSize() {
        return 1;
    }

    @Override
    public double maxSize() {
        return 4;
    }

    @Override
    public double sizeStep() {
        return .2;
    }

    /** The straight side, whole metres: 0.7 D at least (DIN 28136 small reactors are squat, 0.65 to 1.2 D), 1 m at least. */
    @Override
    public int minLength(double size) {
        return Math.max(1, (int) Math.ceil(.7 * size - 1e-9));
    }

    @Override
    public int maxLength(double size) {
        return (int) Math.floor(2 * size + 1e-9);
    }

    /** A new reactor's straight side: 1.1 D (survey default). */
    @Override
    public int defaultLength(double size) {
        return Math.max(minLength(size), Math.min(maxLength(size), (int) Math.round(1.1 * size)));
    }

    /** The bottom valve's clearance: 1 m keeps 2.1 m under the deck's beams at every size; the survey's 0.9 to 3 m. */
    @Override
    public int minSupport(double size, int length) {
        return 1;
    }

    @Override
    public int maxSupport() {
        return 3;
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
        return "stirred_reactor.diameter";
    }

    @Override
    public String lengthRow() {
        return "stirred_reactor.straight_side";
    }

    @Override
    public String supportRow() {
        return "stirred_reactor.clearance";
    }

    @Override
    public Line held(EquipmentShape shape) {
        double d = shape.diameter(), s = shape.tangentLength();
        return new Line("stirred_reactor.volume", number(volume(d, s)), number(power(d, s)));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double d = shape.diameter();
        int s = (int) shape.tangentLength();
        var p = Plan.of(d, s, shape.support());
        return List.of(new Line("stirred_reactor", size(d), s, size(2 * p.rj)),
                new Line("stirred_reactor.drive", number(volume(d, s)), number(power(d, s)), whole(speed(d))),
                new Line("stirred_reactor.deck", number(p.level + Access.DECK)));
    }

    /** The volume of the shell and its two 2:1 heads, m³: π D² S / 4 + π D³ / 12. */
    static double volume(double d, double s) {
        return Math.PI * d * d * s / 4 + Math.PI * d * d * d / 12;
    }

    /** The agitator's motor, kW: about 4 V^⅔ (DIN glass-lined reactors: 4 kW at 1 m³ to 55 kW at 40 m³, 3V-CE). */
    static double power(double d, double s) {
        double c = DeterministicMath.cbrt(volume(d, s));
        return 4 * c * c;
    }

    /** The agitator's speed, rpm: an impeller of 0.6 D (small) to 0.5 D (large) at {@link #TIP_SPEED}. */
    static double speed(double d) {
        return 60 * TIP_SPEED / (Math.PI * (.62 - .03 * d) * d);
    }

    /**
     * A reactor's heights and sizes, m, frame y up from the ground: the shell's radius, the jacket's gap and radius, the
     * bottom valve's line (largest and usual) and its body's height, the jacket's bottom apex, the bottom and top tangents,
     * the deck's level (the lugs' feet and the beams' tops; its grating {@link Access#DECK} over it), the jacket's top,
     * the deck beams' depth, the deck opening's half side and the deck's.
     */
    record Plan(double d, int s, int support, double r, double gap, double rj, double valve, double usualValve, double body, double yj, double bottom, double top,
                double level, double jacketTop, double beam, double hole, double half) {
        static Plan of(double d, int s, int support) {
            double r = d / 2, gap = d >= 3.2 - 1e-9 ? .075 : .05, rj = r + gap;
            double valve = PipeSizes.od(.06 * d, .1, .25), usual = PipeSizes.od(.05 * d, .1, valve), body = .15 + 1.6 * valve;
            // From the valve's outlet face up: its neck (a pipe's largest hub and flange, ProcessUnit.NECK: owner decision
            // 2026-10-21, a 1 m pipe has a 1 m root), body, flange pair and the vessel's bottom nozzle through the jacket.
            double yj = support + ProcessUnit.NECK + body + .06 + .1, bottom = yj + d / 4 + gap, top = bottom + s, level = top - (.2 * d + .1);
            double hole = rj + .12;
            // About 1.1 m of grating round the opening, enough to cover its corners, and an odd number of post bays along
            // the front and back edges: no deck bracket hangs over the jacket nozzles' lines on the axis.
            double half = hole + Math.max(1.1, .42 * hole + .25);
            int bays = (int) Math.ceil(2 * half / Access.POSTS - 1e-9);
            if (bays % 2 == 0) {
                double lower = Access.POSTS * (bays - 1) / 2 - .01;
                half = lower - hole >= Math.max(.95, .42 * hole + .2) ? lower : Access.POSTS * bays / 2 + .01;
            }
            return new Plan(d, s, support, r, gap, rj, valve, usual, body, yj, bottom, top, level, top - (.08 + .04 * s), .2 + .05 * d, hole, half);
        }

        /** The top head's outer surface over radius {@code rho}. */
        double head(double rho) {
            return top + d / 4 * Math.sqrt(Math.max(0, 1 - rho * rho / (r * r)));
        }
    }

    private static final double[] UP = {0, 1, 0}, X = {1, 0, 0}, Z = {0, 0, 1}, NEG_Z = {0, 0, -1};

    @Override
    void build(ProcessUnit u) {
        var p = Plan.of(u.shape.diameter(), (int) u.shape.tangentLength(), u.shape.support());
        double d = p.d, r = p.r, rj = p.rj, half = p.half;
        // The axis on a cell centre, the deck's right and front edges in the box's first cells.
        double a = Math.ceil(half - .5) + .5;
        int n = ProcessUnit.sides(2 * rj);
        var axis = new double[]{a, 0, a};
        var up = Frame.of(axis, 1, 1);
        // The jacket: its shell from its bottom tangent to the closure ring under the lugs, its bottom head round the
        // vessel's; over it the shell's last course and the top head.
        u.mesh.prism(up, rj, p.bottom, p.jacketTop, n, Material.SHELL);
        u.mesh.head(Frame.of(axis, 1, -1), rj, -p.bottom, p.bottom - p.yj, n, Material.HEAD);
        u.mesh.annulus(up, r, rj, p.jacketTop, n, 1, Material.SHELL);
        u.mesh.prism(up, r, p.jacketTop, p.top, n, Material.SHELL);
        u.mesh.head(up, r, p.top, d / 4, n, Material.HEAD);
        // The jacket collides with its bottom head, the vessel encloses with both of its own.
        u.solids.add(ProcessUnit.Solid.rod(axis, 1, p.bottom, p.jacketTop, rj, p.bottom - p.yj, 0));
        u.solids.add(ProcessUnit.Solid.rod(axis, 1, p.jacketTop, p.top, r, d / 4));
        u.volumes.add(ProcessUnit.Solid.rod(axis, 1, p.bottom, p.top, .94 * r, .9 * d / 4, .9 * d / 4));
        u.round(a, a, p.bottom, p.jacketTop, rj, 1);
        u.round(a, a, p.jacketTop, p.top, r, 1);
        lugs(u, p, a);
        deck(u, p, a);
        bottomValve(u, p, a);
        jacketNozzles(u, p, a, n);
        top(u, p, a);
    }

    /**
     * Four lugs (DIN 28145 brackets) on the jacket at the deck's level, on the axes: a base plate out over the opening's
     * beam, two gussets and a top plate.
     */
    private static void lugs(ProcessUnit u, Plan p, double a) {
        double d = p.d, w = .1 + .05 * d, height = .25 + .1 * d, t = .03, r0 = p.rj - .02, r1 = p.hole + .1, y = p.level;
        for (int k = 0; k < 4; k++) {
            int dx = k == 0 ? 1 : k == 2 ? -1 : 0, dz = k == 1 ? 1 : k == 3 ? -1 : 0;
            radialBox(u, a, dx, dz, r0, r1, -w, w, y, y + t, true);
            for (double side : new double[]{-w, w - t}) radialBox(u, a, dx, dz, r0, r1 - .04, side, side + t, y + t, y + height, false);
            radialBox(u, a, dx, dz, r0, r0 + .55 * (r1 - r0), -w, w, y + height - t, y + height, false);
        }
    }

    /** A box out along the axis direction (dx, dz) from (a, a): {@code r0} to {@code r1} out, {@code t0} to {@code t1} across, {@code y0} to {@code y1} up. */
    private static void radialBox(ProcessUnit u, double a, int dx, int dz, double r0, double r1, double t0, double t1, double y0, double y1, boolean solid) {
        double[] p = {a + dx * r0 - dz * t0, y0, a + dz * r0 + dx * t0}, q = {a + dx * r1 - dz * t1, y1, a + dz * r1 + dx * t1};
        u.box(new double[]{Math.min(p[0], q[0]), y0, Math.min(p[2], q[2])}, new double[]{Math.max(p[0], q[0]), y1, Math.max(p[2], q[2])}, Material.STEELWORK, solid);
    }

    /**
     * The operating deck ({@link ProcessUnit#squareDeck}): grating on the lugs' beams round a square opening filled to the
     * jacket, railed round its edges but where the ladder lands on the left; the beams framing the opening and the deck's
     * edges both ways, four columns X-braced on the left and right sides and knee-braced front and back (left open for the
     * jacket's lines), and the caged ladder on the left ({@link #climb}).
     */
    private static void deck(ProcessUnit u, Plan p, double a) {
        double h = p.hole, half = p.half, level = p.level, beam = p.beam, fb = ProcessUnit.FLANGE_BEAM / 2;
        u.squareDeck(a - half, a + half, a - half, a + half, level, 0, a, h, new double[]{a}, p.rj + .04, 32, Access.RAILED,
                new double[][]{null, {a - Access.WALK_THROUGH, a + Access.WALK_THROUGH}, null, null}, null);
        // The beams: round the opening under the lugs, and along the deck's edges.
        for (double off : new double[]{h + .05, half - fb})
            for (int s = -1; s <= 1; s += 2) {
                u.box(new double[]{a - half, level - beam, a + s * off - fb}, new double[]{a + half, level, a + s * off + fb}, Material.STEELWORK, true);
                u.box(new double[]{a + s * off - fb, level - beam, a - half}, new double[]{a + s * off + fb, level, a + half}, Material.STEELWORK, true);
            }
        // The columns on base plates; X-bracing on the left and right sides, knee braces under the front and back beams.
        double post = ProcessUnit.POST + .02 * p.d, at = half - post / 2 - .02, foot = level - beam;
        for (int sx = -1; sx <= 1; sx += 2)
            for (int sz = -1; sz <= 1; sz += 2) {
                u.post(a + sx * at, a + sz * at, 0, foot, post, true);
            }
        // The side bracing in tiers of at most 3.5 m.
        for (int sx = -1; sx <= 1; sx += 2) u.brace(new double[]{a + sx * at, a - at}, new double[]{a + sx * at, a + at}, .3, foot, .15, 3.5);
        for (int sz = -1; sz <= 1; sz += 2) u.knees(new double[]{a - at, a + sz * at}, new double[]{a + at, a + sz * at}, foot, post);
        climb(u, p, a, at, post);
    }

    /**
     * The caged ladder on the left, walked through onto the deck at the opening in its rail ({@link ProcessUnit#climb}):
     * where it is longer than {@link Access#MAX_CLIMB} (the deck stands under 13 m: one rest at most), its rest platform
     * from 0.6 m behind the upper section to 0.6 m in front of the lower one, along the frame towards the front, on
     * brackets off a girt between the left columns; the lower section 1 m to the front.
     */
    private static void climb(ProcessUnit u, Plan p, double a, double at, double post) {
        double x = a + p.half, offset = 1;
        for (double rest : u.climb(new Access.Line(new double[]{x, 0, a + .6}, NEG_Z, X, offset + 1.2), 0, p.level + Access.DECK, offset + .6, .6, 0))
            // The girt carrying the brackets, its face flush with the columns'.
            u.beam(new double[]{x - .02 - ProcessUnit.FLANGE_BEAM, rest - p.beam, a - at + post / 2}, new double[]{x - .02, rest, a + at - post / 2});
    }

    /**
     * The bottom valve under the jacket's apex (DN 100 to 250): the vessel's bottom nozzle through the jacket, a flange
     * pair, the cast body and its outlet neck down to the nozzle's flange {@code support} over the ground; a rack-and-pinion
     * actuator on a stem out to the left.
     */
    private static void bottomValve(ProcessUnit u, Plan p, double a) {
        double v = p.valve, y0 = p.support, b0 = y0 + ProcessUnit.NECK, b1 = b0 + p.body, fv = PipeSizes.flange(v) / 2, rb = .7 * v + .04;
        var f = Frame.of(new double[]{a, 0, a}, 1, 1);
        int n = ProcessUnit.sides(2 * fv);
        u.mesh.cylinder(f, fv, b0, b0 + .04, n, Material.FLANGE);
        u.mesh.cylinder(f, rb, b0 + .04, b1, n, Material.MACHINE);
        u.mesh.cylinder(f, fv, b1, b1 + .06, n, Material.FLANGE);
        u.mesh.bolts(f, v, v / 2 + .01, fv, b1 + .06, 1, Material.BOLT);
        u.mesh.prism(f, v / 2 + .01, b1 + .06, p.yj + .05, n, Material.PIPE);
        double mid = (b0 + b1) / 2, box = .1 + .2 * v;
        u.mesh.cylinder(Frame.of(new double[]{0, mid, a}, 0, 1), .025, a + rb - .01, a + rb + .12, 8, Material.PIPE);
        u.box(new double[]{a + rb + .12, mid - box, a - .7 * box}, new double[]{a + rb + .12 + 1.6 * box, mid + box, a + .7 * box}, Material.ACTUATOR, false);
        u.mesh.cylinder(Frame.of(new double[]{a + rb + .12 + .8 * box, mid + box, a}, 2, 1), .45 * box, -1.1 * box, 1.1 * box, 12, Material.ACTUATOR);
        u.solids.add(ProcessUnit.Solid.rod(new double[]{a, 0, a}, 1, b0, p.yj, rb, 0));
        u.nozzles.add(new Nozzle(Role.LIQUID_OUTLET, new double[]{a, b0 + .03, a}, 1, -1, b0 + .03 - y0, p.usualValve, 0));
    }

    /**
     * The jacket's coolant: in low at the front, out high at the back (survey: steam would reverse them), each on the axis'
     * line. A nozzle leaves on its own cell row where the jacket has one free under the deck's beams; on a short jacket its
     * branch leaves the jacket at its own height and a spool runs down (or up) to the nearest free row.
     */
    private static void jacketNozzles(ProcessUnit u, Plan p, double a, int n) {
        double most = PipeSizes.od(.05 * p.d, .1, .2), usual = PipeSizes.od(.03 * p.d, .05, most), rr = most / 2;
        // The outlet's row: the highest whose cell clears the deck's beams; the inlet's: the lowest on the jacket's straight
        // side, not over the outlet's.
        double outRow = Math.floor(p.level - p.beam - .02 - 1) + .5, highest = Math.min(p.jacketTop - .1 - rr, p.level - p.beam - .1 - rr);
        double inRow = Math.ceil(p.bottom + .1 + rr - .5) + .5, lowest = p.bottom + .1 + rr;
        if (inRow > outRow) inRow = Math.min(Math.floor(lowest - .5) + .5, outRow);
        jacketNozzle(u, Role.COOLANT_INLET, a, p.rj, n, -1, inRow >= lowest - 1e-9 && inRow <= highest + 1e-9 ? inRow : lowest, inRow, most, usual);
        jacketNozzle(u, Role.COOLANT_OUTLET, a, p.rj, n, 1, outRow >= lowest - 1e-9 && outRow <= highest + 1e-9 ? outRow : highest, outRow, most, usual);
    }

    /**
     * A jacket nozzle at x {@code a} facing {@code sz} along z (-1 the front): out of the jacket (radius {@code rj}, {@code n}
     * sides) at height {@code y}, its neck rooted where a pipe's largest neck's edge meets the jacket (owner decision
     * 2026-10-21: a 1 m pipe has a 1 m root); where {@code row} is not {@code y}, flanged 0.1 m out and turned down (or up)
     * in a spool to {@code row}, the nozzle on from its last bend. Its flange face on the next block face 0.25 m beyond.
     */
    private static void jacketNozzle(ProcessUnit u, Role role, double a, double rj, int n, int sz, double y, double row, double most, double usual) {
        double rr = most / 2;
        if (Math.abs(row - y) < 1e-6) {
            double start = ProcessUnit.rootUnder(rj, n);
            double face = sz < 0 ? Math.floor(a - rj - ProcessUnit.NECK) : Math.ceil(a + rj + ProcessUnit.NECK), base = a + sz * start;
            u.nozzles.add(new Nozzle(role, new double[]{a, row, base}, 2, sz, Math.abs(face - base), usual, 0));
            return;
        }
        double out = rj + .25, fl = PipeSizes.flange(most) / 2;
        int sides = ProcessUnit.sides(most);
        double[] bend = {a, row, a + sz * out};
        u.line(rr, Material.PIPE, new double[]{a, y, a + sz * (rj - .03)}, new double[]{a, y, a + sz * out}, bend);
        u.mesh.sphere(bend, 1.05 * rr, sides, Material.PIPE);
        u.mesh.cylinder(Frame.of(new double[]{a, y, a}, 2, sz), fl, rj + .06, rj + .12, sides, Material.FLANGE);
        double face = sz < 0 ? Math.floor(a - out - .25) : Math.ceil(a + out + .25);
        u.nozzles.add(new Nozzle(role, bend, 2, sz, Math.abs(face - bend[2]), usual, 0));
        u.solids.add(ProcessUnit.Solid.rod(new double[]{a, 0, a + sz * out}, 1, Math.min(y, row), Math.max(y, row), rr, 0));
    }

    /**
     * The top head: the process nozzles over the corner cells (VAPOUR_OUTLET back left, FEED back right, NITROGEN front
     * left, VENT front right), the agitator's drive in the middle, the manway at the front, the sight glasses left and
     * right (the lamp on the left one), the thermowell at the back, where each fits.
     */
    private static void top(ProcessUnit u, Plan p, double a) {
        double d = p.d, r = p.r;
        Role[] roles = {Role.VAPOUR_OUTLET, Role.FEED, Role.NITROGEN, Role.VENT};
        int[][] corners = {{1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
        double[] most = {PipeSizes.od(.15 * d, .1, .6), PipeSizes.od(.1 * d, .1, .4), .1, PipeSizes.od(.06 * d, .1, .25)};
        double[] usual = {PipeSizes.od(.1 * d, .1, most[0]), PipeSizes.od(.06 * d, .05, most[1]), .05, PipeSizes.od(.04 * d, .05, most[3])};
        double agitator = .14 + .05 * d, fag = PipeSizes.flange(agitator) / 2, corner = Math.sqrt(2);
        // Circles taken on the head, {x, z, radius}: the agitator's flange and the process nozzles' (or their spools').
        var taken = new ArrayList<double[]>();
        taken.add(new double[]{a, a, fag});
        if (corner + most[0] / 2 <= .9 * r) {
            // Straight up from the head over the corner cells, every flange on one block face over the head under it.
            double face = 0;
            for (double m : most) face = Math.max(face, Math.ceil(p.head(corner - PipeSizes.flange(m) / 2) + ProcessUnit.NECK));
            for (int k = 0; k < 4; k++) {
                // A neck rooted under the head where a pipe's largest neck's edge meets it (owner decision 2026-10-21: a 1 m
                // pipe has a 1 m root).
                double x = a + corners[k][0], z = a + corners[k][1], base = p.head(corner + PipeSizes.MAX_OUTSIDE / 2) - .03;
                u.nozzles.add(new Nozzle(roles[k], new double[]{x, base, z}, 1, 1, face - base, usual[k], 0));
                taken.add(new double[]{x, z, PipeSizes.flange(most[k]) / 2});
            }
        } else {
            // The head's nozzles on its diagonals at 0.6 R, each flanged and spooled up, then out to its corner cell; over
            // head height where a corner stands over the walkway.
            double rho = Math.max(.6 * r, fag + PipeSizes.flange(most[0]) / 2 + .04);
            double rise = p.head(rho) + .5;
            if (corner + PipeSizes.flange(most[0]) / 2 > p.hole - .05) rise = Math.max(rise, p.level + Access.DECK + 2.1 + most[0] / 2 + .05);
            double face = Math.ceil(rise + .4);
            for (int k = 0; k < 4; k++) {
                double rr = most[k] / 2, hx = a + corners[k][0] * rho / corner, hz = a + corners[k][1] * rho / corner, surface = p.head(rho), fl = PipeSizes.flange(most[k]) / 2;
                int sides = ProcessUnit.sides(most[k]);
                var f = Frame.of(new double[]{hx, 0, hz}, 1, 1);
                u.mesh.prism(f, rr, surface - .08, surface + .12, sides, Material.PIPE);
                u.mesh.cylinder(f, fl, surface + .12, surface + .2, sides, Material.FLANGE);
                double[] bend = {a + corners[k][0], rise, a + corners[k][1]};
                u.line(rr, Material.PIPE, new double[]{hx, surface + .2, hz}, new double[]{hx, rise, hz}, bend);
                u.mesh.sphere(bend, 1.05 * rr, sides, Material.PIPE);
                u.solids.add(ProcessUnit.Solid.rod(new double[]{hx, 0, hz}, 1, surface, rise, fl, 0));
                u.nozzles.add(new Nozzle(roles[k], bend, 1, 1, face - rise, usual[k], 0));
                taken.add(new double[]{hx, hz, fl});
            }
        }
        drive(u, p, a, agitator, fag);
        manway(u, p, a, fag, taken);
        sightGlasses(u, p, a, fag, taken);
    }

    /** Whether a circle of {@code radius} at (x, z) keeps 0.03 m from every circle {@code taken} and stays 0.92 R from the axis. */
    private static boolean fits(Plan p, double a, double x, double z, double radius, List<double[]> taken) {
        if (Math.sqrt((x - a) * (x - a) + (z - a) * (z - a)) + radius > .92 * p.r) return false;
        for (var c : taken) if (Math.sqrt((x - c[0]) * (x - c[0]) + (z - c[1]) * (z - c[1])) < radius + c[2] + .03) return false;
        return true;
    }

    /**
     * The agitator's drive on its nozzle at the head's apex: the lantern (0.4 to 0.6 m across, a window each side front and
     * back) on the nozzle's flange, the mechanical seal in it, the shaft and its flanged coupling turning in the windows;
     * the gearbox and the in-line TEFC motor on top (the kit's standing {@link ProcessUnit#motor}, its fan turning); the seal's
     * thermosiphon pot on a bracket at the lantern's right.
     */
    private static void drive(ProcessUnit u, Plan p, double a, double agitator, double fag) {
        double d = p.d, apex = p.top + d / 4, flange = apex + .12;
        var f = Frame.of(new double[]{a, 0, a}, 1, 1);
        int na = ProcessUnit.sides(2 * fag);
        u.mesh.prism(f, agitator / 2, apex - .05, flange - .05, na, Material.PIPE);
        u.mesh.cylinder(f, fag, flange - .05, flange, na, Material.FLANGE);
        u.mesh.bolts(f, agitator, agitator / 2 + .01, fag, flange, 1, Material.BOLT);
        // The lantern between its flanges, its windows facing the front and back.
        double rl = .2 + .03 * d, l0 = flange, l1 = l0 + .3 + .1 * d;
        int nl = ProcessUnit.sides(2 * rl);
        u.mesh.cylinder(f, rl + .04, l0, l0 + .04, nl, Material.MACHINE);
        u.mesh.cylinder(f, rl + .04, l1 - .04, l1, nl, Material.MACHINE);
        u.bracket(f, rl, rl, l0 + .04, l1 - .04, nl);
        double seal = l0 + .04 + .3 * (l1 - l0);
        u.mesh.cylinder(f, .45 * rl, l0 + .04, seal, nl, Material.FLANGE);
        // The shaft and its coupling turning: the coupling's two halves and their bolts.
        double shaft = .03 + .012 * d, cz = l0 + .62 * (l1 - l0), half = .06 + .02 * d, rc = .5 * rl;
        u.mesh.part(u.motions.size());
        u.mesh.prism(f, shaft, seal, l1 - .04, 8, Material.PIPE);
        u.mesh.cylinder(f, rc, cz - half, cz - .005, 12, Material.FLANGE);
        u.mesh.cylinder(f, rc, cz + .005, cz + half, 12, Material.FLANGE);
        for (int k = 0; k < 6; k++) u.mesh.cylinder(new Frame(f.at(Math.PI * k / 3 + Math.PI / 6, .8 * rc, cz - half - .02), UP, Z), .012 + .004 * d, 0, 2 * half + .04, 6,
                Material.BOLT);
        u.mesh.part(-1);
        u.motions.add(ProcessUnit.Motion.turning(new double[]{a, cz, a}, UP, AGITATOR_SPEED, 0));
        // The gearbox, its bottom flange on the lantern's, and the motor on it.
        double g0 = l1, g1 = g0 + .15 + .085 * d, rg = rl + .03;
        u.mesh.cylinder(f, rg, g0, g1, nl, Material.MOTOR);
        u.mesh.cylinder(f, rg + .02, g0 + .4 * (g1 - g0), g0 + .4 * (g1 - g0) + .04, nl, Material.MOTOR);
        double rm = ProcessUnit.motorDiameter(power(d, p.s)) / 2;
        u.motor(f, rm, g1, g1 + .25 + .2 * d, 0, 0, ProcessUnit.MotorDetail.FULL);
        u.solids.add(ProcessUnit.Solid.rod(new double[]{a, 0, a}, 1, flange - .05, g1, Math.max(rl + .04, rg + .02), 0));
        u.round(a, a, l0, g1, rl + .04, 1);
        // The seal pot on its bracket at the lantern's right, two lines into the seal.
        double pot = .06 + .015 * d, height = .35 + .08 * d, px = a - (rl + .1 + pot), py = l0 + .06;
        var pf = Frame.of(new double[]{px, 0, a}, 1, 1);
        u.mesh.prism(pf, pot, py, py + height, 12, Material.MACHINE);
        u.mesh.head(pf, pot, py + height, pot / 2, 12, Material.MACHINE);
        u.mesh.annulus(pf, 0, pot, py, 12, -1, Material.MACHINE);
        u.mesh.bar(new double[]{px + pot - .01, py + .4 * height, a}, new double[]{a - rl - .02, py + .4 * height, a}, .04, UP, Material.STEELWORK);
        u.mesh.cylinder(Frame.of(new double[]{px, py + .75 * height, 0}, 2, 1), .035, a - pot - .02, a - pot + .01, 10, Material.OPENING);
        for (double y : new double[]{py + .15 * height, py + .85 * height})
            u.line(.012, Material.PIPE, new double[]{px + .6 * pot, y, a + .6 * pot}, new double[]{a - rl + .01, y, a + .6 * pot});
        u.solids.add(ProcessUnit.Solid.rod(new double[]{px, 0, a}, 1, py, py + height, pot, pot / 2));
    }

    /**
     * The manway at the head's front, its neck tilted 15° out: the flange, the domed cover with its ring of eight swing-bolt
     * clamps, the hinge towards the drive and a handle. Use the shared access sizes where they fit; a smaller fitted
     * opening is an inspection hatch, none under 0.2 m.
     */
    private static void manway(ProcessUnit u, Plan p, double a, double fag, List<double[]> taken) {
        double r = p.r, size = Math.floor(Math.min(Manway.preferredSize(p.d), (.9 * r - fag - .04) / 1.3) * 20 + 1e-9) / 20;
        double rho = 0;
        for (; size >= .2 - 1e-9; size -= .05) {
            rho = fag + .03 + .65 * size;
            if (fits(p, a, a, a - rho, .65 * size, taken)) break;
        }
        if (size < .2 - 1e-9) return;
        double tilt = Math.toRadians(15), c = DeterministicMath.cos(tilt), s = DeterministicMath.sin(tilt), surface = p.head(rho);
        var out = new double[]{0, c, -s};
        var f = new Frame(new double[]{a, surface, a - rho}, out, X);
        double rn = size / 2, cover = .62 * size, face = .25 + .05 * size;
        int n = ProcessUnit.sides(2 * cover);
        u.mesh.prism(f, rn, -.12, face - .05, n, Material.SHELL);
        u.mesh.cylinder(f, cover, face - .05, face, n, Material.FLANGE);
        u.mesh.cylinder(f, cover, face, face + .04, n, Material.ACTUATOR);
        u.mesh.head(f, .9 * cover, face + .04, .25 * cover, n, Material.ACTUATOR);
        for (int k = 0; k < 8; k++) {
            double angle = 2 * Math.PI * (k + .5) / 8;
            var radial = sub(f.at(angle, 1, 0), f.at(angle, 0, 0));
            u.mesh.block(f.at(angle, cover + .02, face), scale(radial, .025), scale(out, .07), scale(cross(out, radial), .018), Material.BOLT);
        }
        // The hinge on the side towards the drive, the handle opposite.
        u.mesh.block(plus(f.along(face + .02), scale(new double[]{0, s, c}, cover + .04)), scale(X, .08), scale(out, .04), scale(new double[]{0, s, c}, .04), Material.FLANGE);
        u.mesh.bar(plus(f.along(face + .06), scale(new double[]{0, -s, -c}, .6 * cover)), plus(f.along(face + .14), scale(new double[]{0, -s, -c}, .6 * cover)), .025, X,
                Material.PIPE);
        u.solids.add(ProcessUnit.Solid.rod(new double[]{a, 0, a - rho}, 1, surface - .1, surface + face + .1, cover, 0));
        taken.add(new double[]{a, a - rho, .65 * size});
    }

    /**
     * The sight glasses left and right of the drive (DN 100 to 175, a dark glass in a ring on a flanged neck), a lamp box on
     * the left one; the thermowell at the back with its transmitter's head; each where it fits.
     */
    private static void sightGlasses(ProcessUnit u, Plan p, double a, double fag, List<double[]> taken) {
        double d = p.d, od = .08 + .025 * d, fl = PipeSizes.flange(od) / 2;
        for (int side = 1; side >= -1; side -= 2) {
            double rho = Math.max(fag + fl + .04, .45 * p.r), x = a + side * rho;
            if (!fits(p, a, x, a, fl, taken)) continue;
            double surface = p.head(rho);
            var f = Frame.of(new double[]{x, 0, a}, 1, 1);
            int n = ProcessUnit.sides(2 * fl);
            u.mesh.prism(f, od / 2, surface - .06, surface + .15, n, Material.PIPE);
            u.mesh.cylinder(f, fl, surface + .15, surface + .2, n, Material.FLANGE);
            u.mesh.ring(f, .55 * fl, fl, surface + .2, surface + .25, n, 2, Material.FLANGE);
            u.mesh.annulus(f, 0, .55 * fl, surface + .23, n, 1, Material.OPENING);
            if (side > 0) {
                // The lamp: a small housing on a stalk over the glass's edge, looking in.
                u.mesh.bar(new double[]{x + .7 * fl, surface + .25, a}, new double[]{x + .7 * fl, surface + .4, a}, .025, X, Material.PIPE);
                u.mesh.cylinder(Frame.of(new double[]{x + .7 * fl, surface + .45, a}, 0, -1), .05, -.06, .06, 10, Material.MOTOR);
            }
            u.solids.add(ProcessUnit.Solid.rod(new double[]{x, 0, a}, 1, surface - .06, surface + .25, fl, 0));
            taken.add(new double[]{x, a, fl});
        }
        double well = .06 + .01 * d, fw = PipeSizes.flange(well) / 2, rho = Math.max(fag + fw + .06, .4 * p.r), z = a + rho;
        if (!fits(p, a, a, z, Math.max(fw, .07), taken)) return;
        double surface = p.head(rho);
        var f = Frame.of(new double[]{a, 0, z}, 1, 1);
        u.mesh.prism(f, well / 2, surface - .06, surface + .15, 12, Material.PIPE);
        u.mesh.cylinder(f, fw, surface + .15, surface + .2, 12, Material.FLANGE);
        u.mesh.prism(f, .015, surface + .2, surface + .32, 6, Material.PIPE);
        u.mesh.cylinder(f, .06, surface + .32, surface + .42, 10, Material.MOTOR);
        u.box(new double[]{a - .04, surface + .34, z - .1}, new double[]{a + .04, surface + .4, z - .06}, Material.MOTOR, false);
        taken.add(new double[]{a, z, fw});
    }
}
