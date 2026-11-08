package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Nozzle;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.List;

/**
 * An external floating-roof tank (owner request 2026-10-21, batch 2026-10-21-floating-roof-tanks,
 * {@code FLOATING_ROOF_TANKS_PLAN.md} section 3.3; concept sheet and photo survey approved 2026-10-21): an open vertical
 * cylinder of diameter D, 4 to 40 m in whole metres, its shell 4 to 24 m high, standing at grade on a concrete ringwall.
 * The roof floats on the liquid, so the tank keeps only liquid at ambient pressure up to its fill height H, the shell
 * less the roof's own depth ({@link #FREEBOARD}): {@code volume() = π D²/4 · H}, {@code levelHeight() = H}.
 *
 * <p>In the chunk mesh: the shell in 2 m courses (rows clear of everything else are body rows), the top angle,
 * a rim-level ring walkway merged with the gauger's landing, straight external ladders and
 * the front service region for the optional level-gauge accessory. A tank under {@link #RING_SIZE} across has no ring
 * walkway but retains the landing. Below {@link #COMPACT_LIMIT} in both diameter
 * and shell height, it instead has a plain external rim ladder, with no platform or rolling ladder. Tanks taller than
 * their diameter also omit the rolling ladder, retaining the platform with a closed inner railing. Tanks without a
 * rolling ladder carry a fixed see-through safety grating across the rim.
 * Drawn by the client, apart ({@link ProcessUnit#floating}):
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
    /** The walkway's width, m: room for a player round the shell between its rail and the shell ({@link Access#PLAYER}), to the next 5 cm. */
    static final double WALKWAY = Math.ceil((Access.GAP + Access.PLAYER + Access.RAIL_BAND) * 20) / 20;
    /** The top angle's outstand and depth, m. */
    static final double TOP_ANGLE = .08;
    /** The gauger's landing: its overhang over the rim, length round the shell, and knee-brace drop, m. */
    static final double OVERHANG = .6, PLATFORM = 2, BRACE_DROP = 1.5;
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
    /** Least diameter with the rim-level ring walkway, m. Every noncompact tank uses straight external ladders. */
    static final double RING_SIZE = 6;
    /** Compact rim access below this size in both diameter and shell height, m. */
    static final double COMPACT_LIMIT = 6;
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
                Plan.of(d, (int) shape.tangentLength()).compact() ? new Line("tank.access.compact", number(shape.tangentLength())) :
                        d >= RING_SIZE ? new Line("tank.access", number(shape.tangentLength() + Access.DECK)) : new Line("tank.access.ladder", number(shape.tangentLength())));
    }

    /**
     * A tank's sizes, m, in its frame (y up from grade, front -z): its diameter, shell height and radius, the axis (x and
     * z), the fill height, the shell's facets, the access deck's outer radius, and the nozzles' default size.
     */
    record Plan(double d, int shell, double r, double a, double fill, int n, double outer, double defaultOd) {
        static Plan of(double d, int shell) {
            double r = d / 2, outer = r + (d >= RING_SIZE ? WALKWAY : Access.MIN_DECK);
            int n = Math.max(12, 4 * (int) Math.ceil(.75 * Math.PI * d));
            return new Plan(d, shell, r, TankPlacement.of(d, shell).axis(), shell - FREEBOARD, n, outer, PipeSizes.od(.1 + .007 * d, .15, .35));
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

        /** Whether the rim landing continues round the shell as a ring walkway. */
        boolean ringWalkway() {
            return d >= RING_SIZE;
        }

        boolean compact() {
            return d < COMPACT_LIMIT && shell < COMPACT_LIMIT;
        }

        /** The fixed ladder must fit across the roof at its highest level. Shell and diameter use whole metres. */
        boolean rollingLadder() {
            return !compact() && shell <= d;
        }
    }

    private static final double[] UP = {0, 1, 0};

    /** The level gauge's column axis (x, z) and the box axis it faces out along. */
    record Gauge(double x, double z, double[] out) {
    }

    @Override
    void build(ProcessUnit u) {
        var p = Plan.of(u.shape.diameter(), (int) u.shape.tangentLength());
        TankPlacement.of(p.d, p.shell).retain(u);
        shell(u, p);
        if (!p.rollingLadder()) safetyGrating(u, p);
        double hinge;
        if (p.compact()) {
            u.bodyRows = new double[]{1, Math.floor(p.shell - TOP_ANGLE - .6 - 1e-9), 0};
            u.irregularFrom = u.mesh.facets().size();
            var climb = new Access.Climb(p.a - p.r - Access.STANDOFF, p.a, -1, 0, 0, p.shell + Access.LANDING, p.shell);
            Access.ladder(u.mesh, climb, Access.roundLadderBack(p.r, p.n), false);
            u.ladders.add(climb);
            hinge = LADDER;
        } else {
            // One landing beside the straight ladder, extended into the ring walkway on wider tanks.
            double from = LADDER + Access.clearance(p.r), span = span(p, p.r + Access.MIN_DECK / 2);
            if (p.ringWalkway()) walkway(u, p);
            platform(u, p, from, from + span);
            // Rows below the landing's knee braces can share the cylindrical body geometry.
            u.bodyRows = new double[]{1, Math.floor(p.shell - BRACE_DROP - 1e-9), 0};
            u.irregularFrom = u.mesh.facets().size();
            externalLadder(u, p);
            hinge = from + span / 2;
        }
        u.nozzles.add(nozzle(Role.BOTTOM, p, -1, .5));
        u.nozzles.add(nozzle(Role.BOTTOM, p, 1, .5));
        u.nozzles.add(nozzle(Role.OVERFLOW, p, -1, p.shell - .5));
        u.fluidPoint = new double[]{p.a, .01, p.a - p.r + .02};
        floating(u, p, hinge);
    }

    /** Fixed rim grating where there is no access down to the roof; its small openings collide as a continuous deck. */
    private static void safetyGrating(ProcessUnit u, Plan p) {
        double top = p.shell + Access.DECK, inner = p.r - .06;
        var axis = new double[]{p.a, 0, p.a};
        var up = Frame.of(axis, 1, 1);
        u.mesh.hoop(up, inner, p.r, p.shell, top, p.n, Material.STEELWORK);
        u.mesh.bore(up, inner, p.shell, top, p.n, Material.STEELWORK);
        // A bare-metal welded grid, 20 mm bars on 400 mm centres: about 90% open so the roof remains visible. The cross-bars
        // sit just below the longitudinal bars, avoiding coplanar visible faces at their intersections.
        // Recess the grid 5 mm below the rim landing so its bars cannot flicker through the landing's floor.
        double pitch = .4, bar = .02, y = top - bar / 2 - .005;
        int rows = (int) Math.floor(inner / pitch);
        for (int row = -rows; row <= rows; row++) {
            double offset = row * pitch, half = Math.sqrt(inner * inner - offset * offset) + .02;
            u.mesh.bar(new double[]{p.a - half, y, p.a + offset}, new double[]{p.a + half, y, p.a + offset}, bar, UP, Mesh.NO_ENDS, Material.PIPE);
            u.mesh.bar(new double[]{p.a + offset, y - bar, p.a - half}, new double[]{p.a + offset, y - bar, p.a + half}, bar, UP, Mesh.NO_ENDS, Material.PIPE);
        }
        u.solids.add(ProcessUnit.Solid.rod(axis, 1, p.shell, top, p.r, 0));
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

    /** One continuous straight climb from grade to the rim landing, clear of both pipe lanes and the level gauge. */
    private static void externalLadder(ProcessUnit u, Plan p) {
        u.verticalLadder(p.a, p.a, p.r, LADDER, 0, p.shell + Access.DECK + Access.LANDING, false);
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

    /** One rim-level ring, open beside the straight ladder and across the front nozzle/gauge service lane. */
    private static void walkway(ProcessUnit u, Plan p) {
        double edge = p.outer;
        double gap = DeterministicMath.asin(Math.min(1, FRONT_GAP / p.r)), clear = Access.clearance(p.r);
        double start = -Math.PI / 2 + gap, stop = 3 * Math.PI / 2 - gap;
        u.platform(new Access.Arc(p.a, p.a, p.r, start, LADDER - clear, edge, Access.Arc.step(edge)), p.shell, WALKWAY,
                Access.ALL_PARTS, true, false);
        u.platform(new Access.Arc(p.a, p.a, p.r, LADDER + clear, stop, edge, Access.Arc.step(edge)), p.shell, WALKWAY,
                Access.ALL_PARTS, false, true);
    }

    /**
     * The landing beside the straight ladder. On a wide tank its inward extension fills the ring walkway out over the
     * rim, sharing the same floor and outer railing; a narrow tank has a standalone landing. The inner railing opens
     * at the rolling ladder's head where fitted, with a short guard across each exposed inner end.
     */
    private static void platform(ProcessUnit u, Plan p, double from, double to) {
        double wall = p.r - OVERHANG - Access.GAP, s = p.shell, step = Access.Arc.step(p.outer);
        double edgeRadius = p.ringWalkway() ? p.r + Access.GAP : p.outer, width = edgeRadius - wall;
        var run = new Access.Arc(p.a, p.a, wall, from, to, edgeRadius, step);
        if (p.ringWalkway()) {
            // Infill of the ring's existing floor, without a second outer railing or overlapping deck surfaces.
            Access.deck(u.mesh, run, s, width, Access.WALL_EDGE);
            u.solids.add(ProcessUnit.Solid.ring(p.a, p.a, s, s + Access.DECK, wall + Access.GAP, edgeRadius, from, to));
        } else u.platform(run, s, width, Access.RAILED | Access.WALL_EDGE, false, true);
        // The inner edge over the tank: a rail whose band runs along the deck's inner edge, open at the rolling ladder's head
        // in its middle (owner report 2026-10-21: the rail kept the player from the ladder).
        var edge = new Access.Arc(p.a, p.a, wall - 1, from, to, wall + Access.GAP, step);
        if (p.rollingLadder()) {
            double head = LADDER_OPENING / 2 / (wall + Access.GAP) * edge.length() / (to - from);
            u.rails(edge, s, 1 + Access.GAP + Access.RAIL_BAND, false, false, edge.length() / 2 - head, edge.length() / 2 + head);
        } else u.rails(edge, s, 1 + Access.GAP + Access.RAIL_BAND, false, false);
        // Guard the overhang's ends without fencing across the ring's walking lane or the ladder's step-off.
        for (int end = 0; end < (p.ringWalkway() ? 2 : 1); end++) {
            double open = end == 0 ? from : to, limit = p.r + Access.GAP, sign = end == 0 ? -1 : 1;
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
        }
        // Knee braces from the shell under the deck's ends, and the rolling ladder's hinge.
        double span = to - from;
        for (double t : new double[]{from + .15 * span, to - .15 * span})
            u.mesh.bar(p.at(p.r + .02, t, s - BRACE_DROP), p.at(p.outer - .1, t, s - .03), .08, new double[]{-DeterministicMath.sin(t), 0, DeterministicMath.cos(t)}, Material.STEELWORK);
        if (p.rollingLadder()) {
            double mid = (from + to) / 2;
            // Under the deck's slab, clear of its faces (owner report 2026-10-21: the hinge's top flickered in the grating's plane).
            var hinge = p.at(p.r - OVERHANG + .1, mid, s - .07);
            u.mesh.block(hinge, Mesh.scale(new double[]{DeterministicMath.cos(mid), 0, DeterministicMath.sin(mid)}, .1), new double[]{0, .06, 0},
                    Mesh.scale(new double[]{-DeterministicMath.sin(mid), 0, DeterministicMath.cos(mid)}, .4), Material.STEELWORK);
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
            mesh.bar(new double[]{x + sx * rail, low, z + sz * rail}, new double[]{x + sx * rail, high, z + sz * rail}, .011, out, Mesh.NO_ENDS, Material.FLANGE);
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
        // Rim-mounted secondary seal: overlapping bare-metal compression plates bridge the clearance, with only a
        // narrow flexible wiper at the shell. The upper face must look into the tank opening; an outward-facing
        // frustum widens upwards here and points down instead. Both sides of each thin sheet are visible.
        double seal = p.r - EquipmentShape.PLATE + .01, metal = seal - .02;
        double metalTop = top + .15 * (metal - rr) / (seal - rr);
        for (int i = 0; i < p.n; i++) {
            double a0 = 2 * Math.PI * i / p.n, a1 = 2 * Math.PI * (i + 1) / p.n;
            var plate = List.of(up.at(a0, rr, top), up.at(a1, rr, top), up.at(a1, metal, metalTop), up.at(a0, metal, metalTop));
            roof.flat(plate, UP, Material.PIPE);
            roof.flat(plate, Mesh.neg(UP), Material.PIPE);
            var wiper = List.of(up.at(a0, metal, metalTop), up.at(a1, metal, metalTop), up.at(a1, seal, top + .15), up.at(a0, seal, top + .15));
            roof.flat(wiper, UP, Material.OPENING);
            roof.flat(wiper, Mesh.neg(UP), Material.OPENING);
            if (i % 2 == 0) roof.bar(up.at(a0, rr, top + .008), up.at(a0, metal, metalTop + .008), .008, UP, Material.PIPE);
        }
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
        double length = p.rollingLadder() ? p.fill : 0, runway = Math.min(length, 2 * rr - .6);
        double[] along = {-DeterministicMath.cos(hinge), 0, -DeterministicMath.sin(hinge)}, axis = {along[2], 0, -along[0]};
        // The ladder's head at the platform's inner edge, level with its deck (a climber steps off it onto the deck with
        // nothing overhead; owner report 2026-10-21), its hinge under the deck's slab.
        var pivot = p.at(p.r - OVERHANG, hinge, p.shell + Access.DECK);
        if (length > 0) {
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
        }
        var gauge = p.gauge();
        u.floating = new ProcessUnit.Floating(under, p.fill, DECK, pivot, along, axis, length, runway, new double[]{gauge.x(), gauge.z()}, gaugeLow(), p.fill,
                gauge.out());
        u.roofPlan = new ProcessUnit.RoofPlan(new double[]{p.a, 0, p.a}, rr, rr - pw, PONTOON_DEPTH);
    }
}
