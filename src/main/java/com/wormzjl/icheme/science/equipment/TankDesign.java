package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Nozzle;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.ArrayList;
import java.util.List;

/**
 * An external floating-roof tank (owner request 2026-10-21, batch 2026-10-21-floating-roof-tanks,
 * {@code FLOATING_ROOF_TANKS_PLAN.md} section 3.3; concept sheet and photo survey approved 2026-10-21): an open vertical
 * cylinder of diameter D, 4 to 40 m in whole metres, its shell 4 to 24 m high, standing at grade on a concrete ringwall.
 * The roof floats on the liquid, so the tank keeps only liquid at ambient pressure up to its fill height H, the shell
 * less the roof's own depth ({@link #FREEBOARD}): {@code volume() = π D²/4 · H}, {@code levelHeight() = H}.
 *
 * <p>In the chunk mesh: the shell in 2 m courses (rows clear of everything else are body rows), the top angle, the wind
 * girder 1.2 m below the top with its walkway (API 650 5.9.6), one stair wound round the shell at about 40° from grade to
 * the gauger's platform at the rim (owner approval 2026-10-21), which it passes beside the walkway and opens onto it, the
 * platform over the rim, and the front service region for the optional level-gauge accessory. A tank under {@link #STAIR_SIZE} across has neither
 * walkway nor stair but caged ladders on its right side to the platform. Drawn by the client, apart ({@link ProcessUnit#floating}):
 * the roof (pontoon ring 0.1 D wide, centre deck, rim seal, legs 1.8 m, drain sump), floating on the liquid and landing on
 * its legs, and the rolling ladder, as long as the fill height, hinged at the platform with its foot on a runway on the
 * deck. Nozzles, fixed: two identical bottom nozzles centered opposite each other at front and back ({@link Role#BOTTOM},
 * liquid ports), and the overflow on the front at the fill height ({@link Role#OVERFLOW}).
 *
 * <p>The interior holds the liquid: its cells collide as a drum's but pass light, so the roof shows from above (the
 * world side). The tank's {@code tangentLength} is its shell height; it has no support.
 */
final class TankDesign extends UnitDesign {
    /** The shell's height over the fill height, m: the roof's own depth (pontoon and rim seal) at its highest. */
    static final double FREEBOARD = 1;
    /** The concrete ringwall's height and its projection beyond the shell, m (photo survey: 0.3 to 0.5 m ringwalls, mostly buried). */
    static final double RINGWALL = .15, RINGWALL_OUT = .25;
    /** The wind girder's walkway below the shell's top, m (API 650 5.9.6: at least 1.1 m; the photo survey's 1.2), and the girder's outstand and depth under it. */
    static final double GIRDER_DROP = 1.2, GIRDER = .35, GIRDER_DEPTH = .3;
    /** The walkway's width, m: room for a player round the shell between its rail and the shell ({@link Access#PLAYER}), to the next 5 cm. */
    static final double WALKWAY = Math.ceil((Access.GAP + Access.PLAYER + Access.RAIL_BAND) * 20) / 20;
    /** The top angle's outstand and depth, m. */
    static final double TOP_ANGLE = .08;
    /** The gauger's platform: its overhang over the rim and its length along the stair, m (the photo survey's 2 m platform). */
    static final double OVERHANG = .6, PLATFORM = 2;
    /** The opening in the platform's inner rail at the rolling ladder's head, m: the ladder's 0.6 m and a player's room either side. */
    static final double LADDER_OPENING = 1.1;
    /** The roof: its legs, the pontoon ring's width over D, the rim gap to the shell, the pontoon's depth, the deck's top over the roof's underside, m. */
    static final double LEGS = 1.8, PONTOON = .1, RIM_GAP = .2, PONTOON_DEPTH = .6, DECK = .15;
    /**
     * The level gauge on the shell (owner requests 2026-10-21: the gauge was hard to pick out; it reaches the bottom, on the
     * tank as before): its column {@code GLASS_OUT} off the shell, from {@code GAUGE_LOW} over grade, piped from the shell at
     * its end caps, clipped to it every {@code CLIP} m between; on the front {@code GLASS_OFFSET} to
     * the left of its middle beside the centered nozzles on every tank (in the walkway's front gap where fitted).
     * The ladders of a small tank stand on its right side. A steel-framed
     * clear chamber on an unpainted pale scale board ({@code SCALE_HALF} either side), ticked dark every metre and longer
     * every 5 m; its cap radius is {@code GLASS_RADIUS} plus the flange allowance.
     */
    static final double GLASS_OFFSET = .85, GLASS_OUT = .5, GAUGE_LOW = .3, CLIP = 3;
    static final double GLASS_RADIUS = TankGaugeGeometry.CHAMBER_RADIUS, SCALE_HALF = TankGaugeGeometry.SCALE_HALF;

    /** Half the walkway's gap across the front, m: the overflow's pipe stands there. */
    static final double FRONT_GAP = 1.2;
    /**
     * The least diameter that carries the wound stair, m (owner change 2026-10-21, "only put round ladders on them when they
     * are big enough"): spiral tank stairs start at about 20 ft (6 m) of diameter in practice, the size above API 650 Annex
     * J's shop-assembled tanks (6 m at most), which carry caged ladders. A smaller tank has caged ladders on its right side
     * instead ({@link #ladders}) and no walkway: its top angle is its wind girder.
     */
    static final double STAIR_SIZE = 6;
    /** Small-tank access stands on the right side (frame -x), clear of the centered front/back pipe lanes. */
    static final double LADDER = Math.PI;

    @Override
    public double minSize() {
        return 4;
    }

    @Override
    public double maxSize() {
        return 40;
    }

    @Override
    public double sizeStep() {
        return 1;
    }

    /** The shell's height: 4 to 24 m (photo survey: large crude tanks stand 15 to 22 m). */
    @Override
    public int minLength(double size) {
        return 4;
    }

    @Override
    public int maxLength(double size) {
        return 24;
    }

    /** A new tank's shell: 0.6 D (the concept sheet's 20 m tank on a 12 m shell), within its range. */
    @Override
    public int defaultLength(double size) {
        return Math.max(minLength(size), Math.min(maxLength(size), (int) Math.round(.6 * size)));
    }

    /** At grade on its ringwall. */
    @Override
    public int minSupport(double size, int length) {
        return 0;
    }

    @Override
    public int maxSupport() {
        return 0;
    }

    @Override
    public Orientation orientation() {
        return Orientation.VERTICAL;
    }

    @Override
    public String sizeRow() {
        return "tank.diameter";
    }

    @Override
    public String lengthRow() {
        return "tank.shell";
    }

    @Override
    public String supportRow() {
        return null;
    }

    @Override
    public Line held(EquipmentShape shape) {
        return new Line("tank.held", whole(shape.volume()), number(shape.fillHeight()));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double d = shape.diameter();
        return List.of(new Line("tank", size(d), (int) shape.tangentLength(), number(shape.fillHeight())),
                new Line("tank.roof", number(PONTOON * d), number(LEGS)),
                d >= STAIR_SIZE ? new Line("tank.access", number(shape.tangentLength() - GIRDER_DROP)) : new Line("tank.access.ladder", number(shape.tangentLength())));
    }

    /**
     * A tank's sizes, m, in its frame (y up from grade, front -z): its diameter, shell height and radius, the axis (x and
     * z), the fill height, the shell's facets, the walkway's walking level, the stair's inner radius (beyond the walkway)
     * and the platform's outer radius (a small tank's: its ladders' decks'), the stair approach's setback, and the nozzles' default size.
     */
    record Plan(double d, int shell, double r, double a, double fill, int n, double walk, double band, double outer, int accessSetback, double defaultOd) {
        static Plan of(double d, int shell) {
            double r = d / 2, band = r + WALKWAY + Access.GAP, outer = d >= STAIR_SIZE ? band + Access.WOUND_WIDTH + Access.GAP : r + Access.MIN_DECK;
            int n = Math.max(12, 4 * (int) Math.ceil(.75 * Math.PI * d));
            return new Plan(d, shell, r, ProcessUnit.centre(outer + .05), shell - FREEBOARD, n, shell - GIRDER_DROP, band, outer,
                    Math.max(d >= STAIR_SIZE ? 2 : 1, (int) Math.round(r / 6)), PipeSizes.od(.1 + .007 * d, .15, .35));
        }

        /** The point {@code rho} from the axis at angle {@code t} (from +x towards +z), at height {@code y}. */
        double[] at(double rho, double t, double y) {
            return Access.around(a, a, rho, t, y);
        }

        /** The level gauge's place ({@link #GLASS_OFFSET}): its column's axis (x, z) and the box axis it faces out along. */
        Gauge gauge() {
            double rho = r + GLASS_OUT;
            return new Gauge(a + GLASS_OFFSET, a - Math.sqrt(rho * rho - GLASS_OFFSET * GLASS_OFFSET), new double[]{0, 0, -1});
        }

        /** Whether the tank carries the wound stair and the walkway ({@link #STAIR_SIZE}). */
        boolean stairway() {
            return d >= STAIR_SIZE;
        }
    }

    private static final double[] UP = {0, 1, 0};

    /** The level gauge's column axis (x, z) and the box axis it faces out along. */
    record Gauge(double x, double z, double[] out) {
    }

    @Override
    void build(ProcessUnit u) {
        var p = Plan.of(u.shape.diameter(), (int) u.shape.tangentLength());
        shell(u, p);
        double hinge;
        if (p.stairway()) {
            var stair = stair(p);
            double end = stair.angle(stair.steps()), span = span(p, p.band + Access.WOUND_WIDTH / 2);
            walkway(u, p, stair, end - span, end);
            platform(u, p, end - span, end, false);
            // Everything after this crosses the body rows: its box columns keep their own keys.
            u.bodyRows = new double[]{1, Math.floor(p.walk - Access.DECK - .6 - 1e-9), 0};
            u.irregularFrom = u.mesh.facets().size();
            u.woundStair(stair);
            brackets(u, p, stair);
            hinge = end - span / 2;
        } else {
            // The platform beside the top ladder, round from it towards the right.
            double from = LADDER + Access.clearance(p.r), span = span(p, p.r + Access.MIN_DECK / 2);
            platform(u, p, from, from + span, true);
            u.bodyRows = new double[]{1, Math.floor(p.shell - TOP_ANGLE - .6 - 1e-9), 0};
            u.irregularFrom = u.mesh.facets().size();
            ladders(u, p);
            hinge = from + span / 2;
        }
        u.nozzles.add(nozzle(Role.BOTTOM, p, -1, .5));
        u.nozzles.add(nozzle(Role.BOTTOM, p, 1, .5));
        u.nozzles.add(nozzle(Role.OVERFLOW, p, -1, p.shell - .5));
        u.fluidPoint = new double[]{p.a, .01, p.a - p.r + .02};
        floating(u, p, hinge);
    }

    /**
     * The gauger's platform's angle round the axis: {@link #PLATFORM} long at radius {@code mid}, and its inner edge the
     * ladder's opening and a rail band either side, where each of the inner rail's pieces ends at its posts. The platform
     * narrows to its inner edge: on a tank under about 17 m the opening took the whole edge, the inner rail and its posts
     * were left out and the rails across the open end stopped in the air (owner report 2026-10-25, a 7 m tank).
     */
    private static double span(Plan p, double mid) {
        return Math.max(PLATFORM / mid, (LADDER_OPENING + 2 * Access.RAIL_BAND) / (p.r - OVERHANG));
    }

    /**
     * A small tank's access ({@link #STAIR_SIZE}): caged ladders from grade to the gauger's platform, the top one at the
     * right side ({@link #LADDER}), split by part-ring rest platforms where the climb is over {@link Access#MAX_CLIMB},
     * with successive sections beside one another in that side area ({@link ProcessUnit#ringAccess});
     * both centered pipe lanes and the level gauge on the left remain clear.
     */
    private static void ladders(ProcessUnit u, Plan p) {
        var levels = Access.climbs(0, List.of(), p.shell);
        levels.removeLast();
        int m = levels.size();
        double clear = Access.clearance(p.r), adjacent = LADDER + 2 * clear;
        var rings = new ArrayList<ProcessUnit.Ring>();
        for (int j = 0; j < m; j++) {
            double in = (m - j) % 2 == 0 ? LADDER : adjacent;
            rings.add(in == LADDER ? new ProcessUnit.Ring(levels.get(j), in, in, adjacent + clear) : new ProcessUnit.Ring(levels.get(j), in, LADDER - clear, in));
        }
        u.ringAccess(p.a, p.a, p.r, Access.MIN_DECK, 0, rings);
        u.verticalLadder(p.a, p.a, p.r, LADDER, m == 0 ? 0 : levels.getLast() + Access.DECK, p.shell + Access.DECK + Access.LANDING, false);
    }

    /**
     * The stair from grade to the gauger's platform, wound clockwise seen from above round the shell beyond the walkway,
     * at the plant stair's slope. Its foot stands beside the front access gap, and it
     * climbs away from them; where that would meet the front's nozzles or bring the platform or the walkway's landing into
     * the walkway's front gap, it starts further round, in steps of 0.1 rad.
     */
    static Access.WoundStair stair(Plan p) {
        double hw = Access.WOUND_WIDTH / 2, rw = p.band + hw, top = p.shell + Access.DECK;
        // Retain the established access approach clearance, independent of the relocated nozzle centers.
        double zone = Math.max(p.accessSetback + .5, FRONT_GAP) + .5 + hw;
        double first = -Math.PI / 2 - DeterministicMath.asin(Math.min(1, (p.accessSetback + 1 + hw) / rw));
        for (int m = 0; m < 63; m++) {
            double start = first - .1 * m;
            var plain = Access.WoundStair.wind(p.a, p.a, y -> p.r, WALKWAY + Access.GAP, start, -1, 0, top, Access.WOUND_WIDTH, Access.WOUND_SLOPE, List.of(), false);
            double landing = plain.station(p.walk);
            var opening = new double[]{landing - .6, landing + .6};
            if (clear(p, plain, opening, zone)) return Access.WoundStair.wind(p.a, p.a, y -> p.r, WALKWAY + Access.GAP, start, -1, 0, top, Access.WOUND_WIDTH,
                    Access.WOUND_SLOPE, List.<double[]>of(opening), false);
        }
        throw new IllegalStateException("No place for a " + p.d + " m tank's stair on a " + p.shell + " m shell");
    }

    /**
     * Whether a planned stair keeps clear: its treads and fences pass front/back (within {@code zone} of the centerline) only
     * over the bottom nozzles' row, and pass the front under the overflow; the gauger's platform at its top and its landing onto the
     * walkway ({@code opening}) stand outside the walkway's front gap, and the landing outside the platform.
     */
    private static boolean clear(Plan p, Access.WoundStair stair, double[] opening, double zone) {
        for (int k = 1; k <= stair.steps(); k++) {
            var q = stair.at((k - .5) * Access.WOUND_GOING, 0, 0);
            // The front includes the gauge/access gap; the back has only its centered 1 m maximum nozzle.
            double width = q[2] < p.a ? zone : PipeSizes.MAX_OUTSIDE / 2 + .5 + Access.WOUND_WIDTH / 2;
            if (Math.abs(q[0] - p.a) >= width) continue;
            double low = stair.tread(k) - stair.rise(k) - .3, high = stair.tread(k) + Access.FENCE;
            if (low < 1.1 || q[2] < p.a && high > p.shell - 1.2 && low < p.shell + .2) return false;
        }
        double end = stair.angle(stair.steps()), span = PLATFORM / (p.band + Access.WOUND_WIDTH / 2), gap = frontGap(p), margin = .3 / p.r;
        if (meets(end - span - margin, end + margin, -Math.PI / 2 - gap, -Math.PI / 2 + gap)) return false;
        if (!(opening[0] > 0)) return false;
        // The landing lies on a walkway piece, clear of its closed ends.
        double t0 = angle(p, stair.at(opening[1], 0, 0)), t1 = angle(p, stair.at(opening[0], 0, 0)), ends = (Access.RAIL_BAND + .1) / (p.r + WALKWAY);
        for (var piece : pieces(p, end - span, end)) {
            var on = landing(piece, t0, t1);
            if (on != null && on[0] - piece[0] >= ends && piece[1] - on[1] >= ends) return true;
        }
        return false;
    }

    /**
     * The walkway's pieces, {from, to} angles each (rising): the circle from the front gap's left end round to its right
     * end, less the gauger's platform's stretch from {@code from} to {@code to} and a margin; a piece under 2 m along its
     * edge, between the front's gap and the platform's, leads nowhere and is left out.
     */
    static List<double[]> pieces(Plan p, double from, double to) {
        double gap = frontGap(p), margin = .3 / p.r, start = -Math.PI / 2 + gap, stop = 3 * Math.PI / 2 - gap, edge = p.r + WALKWAY;
        double p0 = from - margin;
        p0 += 2 * Math.PI * Math.ceil((start - p0) / (2 * Math.PI));
        double p1 = p0 + (to - from) + 2 * margin;
        var out = new ArrayList<double[]>();
        for (var piece : p0 >= stop ? List.of(new double[]{start, stop}) : List.of(new double[]{start, p0}, new double[]{p1, stop}))
            if ((piece[1] - piece[0]) * edge >= 2) out.add(piece);
        return out;
    }

    /** The landing's angles [t0, t1] (rising) on {@code piece}, turned to lie there, or null where it does not. */
    private static double[] landing(double[] piece, double t0, double t1) {
        double l0 = t0 + 2 * Math.PI * Math.ceil((piece[0] - t0) / (2 * Math.PI)), l1 = l0 + (t1 - t0 + 2 * Math.PI) % (2 * Math.PI);
        return l1 <= piece[1] ? new double[]{l0, l1} : null;
    }

    /** Half the angle of the walkway's front gap, at the shell. */
    private static double frontGap(Plan p) {
        return DeterministicMath.asin(Math.min(1, FRONT_GAP / p.r));
    }

    private static double angle(Plan p, double[] q) {
        return DeterministicMath.atan2(q[2] - p.a, q[0] - p.a);
    }

    /** Whether the arcs [a0, a1] and [b0, b1] (rising, under a turn each) meet, round the circle. */
    static boolean meets(double a0, double a1, double b0, double b1) {
        double start = b0 - a0;
        start -= 2 * Math.PI * Math.floor(start / (2 * Math.PI));
        // b0 lies start after a0; the arcs meet where b starts inside a, or a starts inside b.
        return start <= a1 - a0 + 1e-9 || 2 * Math.PI - start <= b1 - b0 + 1e-9;
    }

    /**
     * The shell, outside and in (its inside shows over the roof), in courses of {@link ShapeRasterizer#COURSE} from grade
     * on plates about {@link ShapeRasterizer#PLATE_LENGTH} long, as a drum's; its top angle and its ringwall. It encloses
     * its inside up to its top, as a drum does, but collides only as a wall {@link Access#NARROW_BAND} thick: inside it the
     * floating roof collides ({@code FloatingRoofs}), in the cells the wall crosses too (owner report 2026-10-25: the whole
     * inside collided in those cells, and a climber on the rolling ladder met it under the platform).
     */
    private static void shell(ProcessUnit u, Plan p) {
        double r = p.r, in = r - EquipmentShape.PLATE, s = p.shell, chord = 2 * r * DeterministicMath.sin(Math.PI / p.n), circumference = p.n * chord;
        double plates = circumference / Math.max(1, Math.round(circumference / ShapeRasterizer.PLATE_LENGTH));
        var axis = new double[]{p.a, 0, p.a};
        var courses = new Mesh.Uv(axis, UP, 0, 2 * ShapeRasterizer.COURSE);
        var round = new Mesh.Radial(axis, UP);
        var inward = new Mesh.Inward(axis, UP);
        for (int i = 0; i < p.n; i++) {
            double t0 = 2 * Math.PI * i / p.n, t1 = 2 * Math.PI * (i + 1) / p.n;
            double[] a = p.at(r, t0, 0), b = p.at(r, t1, 0);
            var tangent = Mesh.unit(b[0] - a[0], 0, b[2] - a[2]);
            var around = new Mesh.Uv(a, Mesh.neg(tangent), -i * chord, plates);
            u.mesh.add(List.of(a, b, p.at(r, t1, s), p.at(r, t0, s)), new double[]{p.a, s / 2, p.a}, round, Material.SHELL, around, courses);
            double mid = (t0 + t1) / 2;
            u.mesh.add(List.of(p.at(in, t0, 0), p.at(in, t1, 0), p.at(in, t1, s), p.at(in, t0, s)), p.at(2 * r, mid, s / 2), inward, Material.SHELL, around, courses);
        }
        var up = Frame.of(axis, 1, 1);
        u.mesh.annulus(up, in, r, s, p.n, 1, Material.HEAD);
        u.mesh.hoop(up, r, r + TOP_ANGLE, s - TOP_ANGLE, s, p.n, Material.HEAD);
        u.mesh.prism(up, r + RINGWALL_OUT, 0, RINGWALL, p.n, Material.CONCRETE);
        u.mesh.annulus(up, r, r + RINGWALL_OUT, RINGWALL, p.n, 1, Material.CONCRETE);
        u.solids.add(ProcessUnit.Solid.ring(p.a, p.a, 0, s, r - Access.NARROW_BAND, r, 0, 0));
        u.solids.add(ProcessUnit.Solid.ring(p.a, p.a, 0, RINGWALL, r, r + RINGWALL_OUT, 0, 0));
        u.solids.add(ProcessUnit.Solid.ring(p.a, p.a, s - TOP_ANGLE, s, r, r + TOP_ANGLE, 0, 0));
        u.volumes.add(ProcessUnit.Solid.rod(axis, 1, 0, s, r, 0));
        u.round(p.a, p.a, 0, s, r, 1);
    }

    /**
     * The wind girder, a ring under the walkway, and its walkway round the shell but across the front (the overflow and the level gauge stand
     * there) and under the gauger's platform from {@code from} to {@code to}: one deck or two, railed
     * across their ends, open towards the stair over its landing.
     */
    private static void walkway(ProcessUnit u, Plan p, Access.WoundStair stair, double from, double to) {
        double level = p.walk - Access.DECK, low = level - GIRDER_DEPTH, edge = p.r + WALKWAY;
        var up = Frame.of(new double[]{p.a, 0, p.a}, 1, 1);
        u.mesh.prism(up, p.r + GIRDER, low, level, p.n, Material.SUPPORT);
        u.mesh.annulus(up, p.r, p.r + GIRDER, low, p.n, -1, Material.SUPPORT);
        u.solids.add(ProcessUnit.Solid.ring(p.a, p.a, low, level, p.r, p.r + GIRDER, 0, 0));
        var opening = stair.openings().getFirst();
        double t0 = angle(p, stair.at(opening[1], 0, 0)), t1 = angle(p, stair.at(opening[0], 0, 0));
        for (var piece : pieces(p, from, to)) {
            var arc = new Access.Arc(p.a, p.a, p.r, piece[0], piece[1], edge, Access.Arc.step(edge));
            // Open over the landing's stations, on the piece it lies on.
            var on = landing(piece, t0, t1);
            double[] gaps = on != null ? new double[]{(on[0] - piece[0]) * edge, (on[1] - piece[0]) * edge} : new double[0];
            u.platform(arc, level, WALKWAY, Access.ALL_PARTS, true, true, gaps);
        }
    }

    /**
     * The gauger's platform at the access's top, over the rim from the shell's inside out over the walkway to beyond the
     * stair (a small tank's: beyond its top ladder's cage), from angle {@code from} to {@code to}: railed round but where
     * the access arrives (the stair's top at {@code to}, or a small tank's ladder beside {@code from}: {@code ladder}), its
     * inner edge railed over the tank, on two knee braces from the shell; the ladder's hinge at its middle.
     */
    private static void platform(ProcessUnit u, Plan p, double from, double to, boolean ladder) {
        double wall = p.r - OVERHANG - Access.GAP, width = p.outer - wall, s = p.shell, step = Access.Arc.step(p.outer);
        u.platform(new Access.Arc(p.a, p.a, wall, from, to, p.outer, step), s, width, Access.RAILED | Access.WALL_EDGE, !ladder, ladder);
        // The inner edge over the tank: a rail whose band runs along the deck's inner edge, open at the rolling ladder's head
        // in its middle (owner report 2026-10-21: the rail kept the player from the ladder).
        var edge = new Access.Arc(p.a, p.a, wall - 1, from, to, wall + Access.GAP, step);
        double head = LADDER_OPENING / 2 / (wall + Access.GAP) * edge.length() / (to - from);
        u.rails(edge, s, 1 + Access.GAP + Access.RAIL_BAND, false, false, edge.length() / 2 - head, edge.length() / 2 + head);
        // Across the open end, from the inner edge out to the stair's inner side (or just past the shell, short of the
        // ladder's rung plane): the climber arrives beyond it.
        double open = ladder ? from : to, limit = ladder ? p.r + .1 : p.band, sign = ladder ? -1 : 1;
        double deck = s + Access.DECK, inner = wall + Access.GAP, length = limit - inner, inward = Access.RAIL_BAND / 2;
        double[] radial = {DeterministicMath.cos(open), 0, DeterministicMath.sin(open)}, back = {sign * DeterministicMath.sin(open), 0, -sign * DeterministicMath.cos(open)};
        var centre = p.at(inner + length / 2, open, deck + Access.FENCE / 2);
        centre[0] += inward * back[0];
        centre[2] += inward * back[2];
        u.solids.add(ProcessUnit.Solid.block(centre, Mesh.scale(radial, length / 2), new double[]{0, Access.FENCE / 2, 0}, Mesh.scale(back, inward)));
        for (double over : new double[]{Access.RAIL, Access.MID_RAIL}) {
            var a = p.at(inner + .12, open, deck + over);
            var b = p.at(limit - .03, open, deck + over);
            for (var q : new double[][]{a, b}) {
                q[0] += .03 * back[0];
                q[2] += .03 * back[2];
            }
            u.mesh.bar(a, b, Access.BAR, UP, Mesh.NO_ENDS, Material.HANDRAIL);
        }
        var post = p.at(limit - .03, open, deck - .04);
        post[0] += .03 * back[0];
        post[2] += .03 * back[2];
        u.mesh.bar(post, new double[]{post[0], deck + Access.RAIL + Access.BAR / 2, post[2]}, Access.BAR, radial, Mesh.NO_ENDS, Material.HANDRAIL);
        // Knee braces from the shell under the deck's ends, and the rolling ladder's hinge.
        double span = to - from;
        for (double t : new double[]{from + .15 * span, to - .15 * span})
            u.mesh.bar(p.at(p.r + .02, t, s - 1.5), p.at(p.outer - .1, t, s - .03), .08, new double[]{-DeterministicMath.sin(t), 0, DeterministicMath.cos(t)}, Material.STEELWORK);
        double mid = (from + to) / 2;
        // Under the deck's slab, clear of its faces (owner report 2026-10-21: the hinge's top flickered in the grating's plane).
        var hinge = p.at(p.r - OVERHANG + .1, mid, s - .07);
        u.mesh.block(hinge, Mesh.scale(new double[]{DeterministicMath.cos(mid), 0, DeterministicMath.sin(mid)}, .1), new double[]{0, .06, 0},
                Mesh.scale(new double[]{-DeterministicMath.sin(mid), 0, DeterministicMath.cos(mid)}, .4), Material.STEELWORK);
    }

    /** The stair's brackets to the shell from its inner stringer, every eight treads, below the walkway (the girder carries it above). */
    private static void brackets(ProcessUnit u, Plan p, Access.WoundStair stair) {
        for (int j = 4; j < stair.steps(); j += 8) {
            double y = stair.tread(j) - .25;
            if (y > p.walk - 2.5 || y < .3) continue;
            var q = stair.at((j - .5) * Access.WOUND_GOING, -Access.WOUND_WIDTH / 2 + .03, y);
            double t = DeterministicMath.atan2(q[2] - p.a, q[0] - p.a);
            u.mesh.bar(q, p.at(p.r + .01, t, y), .08, UP, Material.STEELWORK);
        }
    }

    /** The level gauge's chamber bottom over grade, m: where its bands start. */
    static double gaugeLow() {
        return GAUGE_LOW;
    }

    /**
     * The level gauge ({@link Plan#gauge}): a clear sleeve inside its guard rails, from just over grade to above the
     * fill height, its drain down to grade and its vent cap above; round equalizing pipes connect both end manifolds
     * to the shell, with union fittings and support clips between; the
     * pale scale board behind it, ticked dark at each metre over the floor, the fifth ones across the whole board.
     */
    static void gauge(Mesh mesh, Plan p) {
        var g = p.gauge();
        double x = g.x(), z = g.z(), low = GAUGE_LOW, high = p.fill + .3, flange = GLASS_RADIUS + .03;
        double[] out = g.out(), side = {-out[2], 0, out[0]};
        var column = Frame.of(new double[]{x, 0, z}, 1, 1);
        double end = TankGaugeGeometry.END_HEIGHT;
        // Only vertical glass faces: opaque end manifolds close the sleeve without coplanar transparent caps.
        TankGaugeGeometry.sleeve(mesh, x, z, low, high);
        // Corner rails leave the front and sides clear; no opaque barrel hides the empty window or its liquid.
        double rail = TankGaugeGeometry.BAND_HALF + .025;
        for (int sx : new int[]{-1, 1}) for (int sz : new int[]{-1, 1})
            mesh.bar(new double[]{x + sx * rail, low, z + sz * rail}, new double[]{x + sx * rail, high, z + sz * rail}, .022, out, Mesh.NO_ENDS, Material.FLANGE);
        for (double[] cap : new double[][]{{low - end, low}, {high, high + end}})
            mesh.cylinder(column, flange, cap[0], cap[1], 12, Material.FLANGE);
        mesh.cylinder(column, .025, 0, low - end, 12, Material.PIPE);
        mesh.cylinder(column, .025, high + end, high + .3, 12, Material.PIPE);
        // Real round connections enter the end manifolds, outside the liquid/scale area, rather than stopping behind it.
        double offset = (x - p.a) * side[0] + (z - p.a) * side[2], radial = Math.sqrt(p.r * p.r - offset * offset);
        double[] shell = {p.a + offset * side[0] + radial * out[0], 0, p.a + offset * side[2] + radial * out[2]};
        for (double y : new double[]{low - end / 2, high + end / 2}) {
            var pipe = new Frame(new double[]{shell[0], y, shell[2]}, out, UP);
            double length = (x - shell[0]) * out[0] + (z - shell[2]) * out[2], union = length - GLASS_RADIUS - .10;
            mesh.cylinder(pipe, .045, -.04, length, 12, Material.PIPE);
            mesh.cylinder(pipe, .067, -.01, .055, 12, Material.FLANGE);
            mesh.cylinder(pipe, .065, union - .05, union + .05, 12, Material.FLANGE);
            mesh.cylinder(pipe, .075, union - .025, union + .025, 6, Material.FLANGE);
        }
        for (double y = low + .6; y < high - .3; y += CLIP) {
            mesh.bar(new double[]{shell[0] + .02 * out[0], y, shell[2] + .02 * out[2]}, new double[]{x - GLASS_RADIUS * out[0], y, z - GLASS_RADIUS * out[2]},
                    .04, UP, Mesh.END, Material.STEELWORK);
        }
        // The scale board just behind the chamber, and its ticks on its face either side of it.
        double[] face = {x - (GLASS_RADIUS - .02) * out[0], 0, z - (GLASS_RADIUS - .02) * out[2]};
        board(mesh, face, out, side, -SCALE_HALF, SCALE_HALF, low, p.fill + .1, .04, Material.GAUGE_SCALE);
        for (int m = 1; m <= p.fill + 1e-9; m++) {
            boolean fifth = m % 5 == 0;
            double half = fifth ? .03 : .015;
            double[] proud = {face[0] + .01 * out[0], 0, face[2] + .01 * out[2]};
            double window = TankGaugeGeometry.BAND_HALF + .045;
            board(mesh, proud, out, side, -SCALE_HALF, -window, m - half, m + half, .01, Material.BOLT);
            if (fifth) board(mesh, proud, out, side, window, SCALE_HALF, m - half, m + half, .01, Material.BOLT);
        }
    }

    /** An axis-aligned plate whose face is the plane through {@code face} facing {@code out}, {@code depth} thick behind it, from {@code s0} to {@code s1} along {@code side}, from {@code y0} to {@code y1}. */
    private static void board(Mesh mesh, double[] face, double[] out, double[] side, double s0, double s1, double y0, double y1, double depth, Material material) {
        double[] a = {face[0] + s0 * side[0], y0, face[2] + s0 * side[2]}, b = {face[0] + s1 * side[0] - depth * out[0], y1, face[2] + s1 * side[2] - depth * out[2]};
        mesh.box(new double[]{Math.min(a[0], b[0]), y0, Math.min(a[2], b[2])}, new double[]{Math.max(a[0], b[0]), y1, Math.max(a[2], b[2])}, 63, material);
    }

    /**
     * A bottom nozzle or the overflow: centered on front (sign -1) or back (sign +1) at height {@code y},
     * its neck from under the shell where a pipe's largest neck's
     * edge meets it (owner decision 2026-10-21: a 1 m pipe has a 1 m root), its flange face on the next block face
     * {@link EquipmentShape#STAND_OUT} beyond the shell.
     */
    private static Nozzle nozzle(Role role, Plan p, int sign, double y) {
        double apothem = p.r * DeterministicMath.cos(Math.PI / p.n), edge = PipeSizes.MAX_OUTSIDE / 2;
        double start = Math.sqrt(apothem * apothem - edge * edge) - .03;
        double plane = sign < 0 ? Math.floor(p.a - p.r - EquipmentShape.STAND_OUT) : Math.ceil(p.a + p.r + EquipmentShape.STAND_OUT);
        double base = p.a + sign * start;
        return new Nozzle(role, new double[]{p.a, y, base}, 2, sign, sign * (plane - base), p.defaultOd, 0);
    }

    /**
     * The client-drawn parts (plan section 3.4): the roof landed on its legs, a pontoon ring 0.1 D wide round its centre
     * deck, its rim seal to the shell, its legs and drain sump and the ladder's runway on the deck, in the structure
     * colour; the rolling ladder, as long as the fill height, drawn level from its hinge at angle {@code hinge} towards the
     * axis.
     */
    private static void floating(ProcessUnit u, Plan p, double hinge) {
        double rr = p.r - RIM_GAP, pw = PONTOON * p.d, under = LEGS, top = under + PONTOON_DEPTH, deck = under + DECK;
        var up = Frame.of(new double[]{p.a, 0, p.a}, 1, 1);
        var roof = u.roof;
        roof.prism(up, rr, under, top, p.n, Material.SUPPORT);
        roof.bore(up, rr - pw, deck, top, p.n, Material.SUPPORT);
        roof.annulus(up, rr - pw, rr, top, p.n, 1, Material.SUPPORT);
        roof.annulus(up, 0, rr, under, p.n, -1, Material.SUPPORT);
        roof.annulus(up, 0, rr - pw, deck, p.n, 1, Material.STEELWORK);
        roof.frustum(up, rr, p.r - EquipmentShape.PLATE - .01, top, top + .15, p.n, Material.OPENING);
        roof.cylinder(up, .3, under - .3, under, 12, Material.SUPPORT);
        // Legs on the pontoon and on the deck, about 4 m apart round each ring.
        for (double ring : new double[]{rr - pw / 2, (rr - pw) / 2}) {
            int count = Math.max(3, (int) Math.round(2 * Math.PI * ring / 4));
            for (int i = 0; i < count; i++) {
                double t = 2 * Math.PI * (i + .5) / count;
                roof.bar(p.at(ring, t, under - LEGS), p.at(ring, t, under), .08, new double[]{1, 0, 0}, Material.STEELWORK);
            }
        }
        // The ladder's hinge over the rim, the runway from the rim towards the axis along the deck.
        double length = p.fill, runway = Math.min(length, 2 * rr - .6);
        double[] along = {-DeterministicMath.cos(hinge), 0, -DeterministicMath.sin(hinge)}, axis = {along[2], 0, -along[0]};
        // The ladder's head at the platform's inner edge, level with its deck (a climber steps off it onto the deck with
        // nothing overhead; owner report 2026-10-21), its hinge under the deck's slab.
        var pivot = p.at(p.r - OVERHANG, hinge, p.shell + Access.DECK);
        for (double side : new double[]{-.3, .3}) {
            var from = p.at(rr - .1, hinge, deck + .03);
            from[0] += side * axis[0];
            from[2] += side * axis[2];
            roof.bar(from, new double[]{from[0] + runway * along[0], deck + .03, from[2] + runway * along[2]}, .06, UP, Material.STEELWORK);
            // The stringers and treads under the walking line through the pivot, their tops on it (they stood half over it,
            // through the deck's edge at the head); the first tread half a pitch from the head, at the deck's edge.
            var foot = pivot.clone();
            foot[0] += side * axis[0];
            foot[1] -= .04;
            foot[2] += side * axis[2];
            u.ladder.bar(foot, new double[]{foot[0] + length * along[0], foot[1], foot[2] + length * along[2]}, .08, UP, Material.STEELWORK);
        }
        for (double s = .15; s < length - .1; s += .3) {
            double[] c = {pivot[0] + s * along[0], pivot[1] - .025, pivot[2] + s * along[2]};
            u.ladder.bar(new double[]{c[0] - .3 * axis[0], c[1], c[2] - .3 * axis[2]}, new double[]{c[0] + .3 * axis[0], c[1], c[2] + .3 * axis[2]}, .05, UP,
                    Mesh.NO_ENDS, Material.HANDRAIL);
        }
        var gauge = p.gauge();
        u.floating = new ProcessUnit.Floating(under, p.fill, DECK, pivot, along, axis, length, runway, new double[]{gauge.x(), gauge.z()}, gaugeLow(), p.fill,
                gauge.out());
        u.roofPlan = new ProcessUnit.RoofPlan(new double[]{p.a, 0, p.a}, rr, rr - pw, PONTOON_DEPTH);
    }
}
