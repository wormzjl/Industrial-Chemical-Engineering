package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Nozzle;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.Mesh.Frame;
import com.wormzjl.icheme.science.equipment.Mesh.Material;
import com.wormzjl.icheme.science.equipment.ProcessUnit.FanStack;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Motion;
import com.wormzjl.icheme.science.equipment.ProcessUnit.Solid;
import com.wormzjl.icheme.science.equipment.design.UnitRatings;

import java.util.ArrayList;
import java.util.List;

/**
 * An air cooler bank of bays ({@link #build}, {@link ProcessUnit.Bank}): bays 1.5 to 6 m wide and 2 to 12
 * m long where their fans cover {@link ProcessUnit#FAN_COVERAGE} of the bundle face (API 661); a bay wider than 3 m holds
 * two bundles side by side (batch 2026-11-15-equipment-visual-design, AIR_COOLER_BAY_PLAN.md: tubes 9 to 12 m long, bays
 * 3 to 6 m wide). The constructor sets the bay and the bank on rows of their own.
 */
final class AirCoolerDesign extends UnitDesign {
    AirCoolerDesign() {
        // minSupport: The bundle 3 m up (owner request 2026-10-16) keeps the fans' ring inlets at least 0.6 fan
        // diameters above grade, more than the half diameter of free air a forced-draft fan usually takes.
        // Computed in its overrides: minLength.
        super(new Sizes(1.5, 6, .5,
                Sizes.COMPUTED, ProcessUnit.MAX_BAY_LENGTH,
                3, EquipmentShape.MAX_SUPPORT,
                Orientation.HORIZONTAL, "bay", null, "bundle_height", true,
                1));
    }

    /** The shortest bay of width {@code size} whose fans cover {@link ProcessUnit#FAN_COVERAGE} of its bundle face. */
    @Override
    public int minLength(double size) {
        int length = ProcessUnit.MIN_BAY_LENGTH;
        while (length < ProcessUnit.MAX_BAY_LENGTH && UnitRatings.coverage(size, length) < ProcessUnit.FAN_COVERAGE) length++;
        return length;
    }

    /**
     * The bundle's least height, m: the fans' ring inlets 0.6 fan diameters above grade, under the plenum (0.2 + 0.4 fan
     * deep) and the ring (0.35 of its depth, at most {@link ProcessUnit#RING_DEPTH}, drawn): 3 m for every fan to 1.9 m,
     * 6 m for a 4.55 m fan (AIR_COOLER_BAY_PLAN.md 4.3).
     */
    @Override
    public int minSupport(double size, int length) {
        double fan = UnitRatings.fans(size, length).diameter();
        return Math.max(3, (int) Math.ceil(.6 * fan + .2 + .4 * fan + .35 * Math.min(fan, ProcessUnit.RING_DEPTH) - 1e-9));
    }

    /**
     * The longest bay of width {@code size}, to {@link ProcessUnit#MAX_BAY_LENGTH}: every length from {@link #minLength} to
     * it keeps fans over {@link ProcessUnit#FAN_COVERAGE} of the face (three fans at most cover a narrow bay's long bundle
     * no further: 1.5 m wide to 6 m long, 2 m to 9, 2.5 m to 10, 3 m and wider to 12).
     */
    @Override
    public int maxLength(double size) {
        int length = minLength(size);
        while (length < ProcessUnit.MAX_BAY_LENGTH && UnitRatings.coverage(size, length + 1) >= ProcessUnit.FAN_COVERAGE) length++;
        return length;
    }

    /** A new bank's bundle 4 m up (owner request 2026-10-16), 1.68 m or more under its motor beams, or its least height where that is higher. */
    @Override
    public int defaultSupport(double size, int length) {
        return Math.max(4, minSupport(size, length));
    }

    /**
     * A fan's shown speed, rev/s: {@link ProcessUnit#FAN_SPEED} to a {@link #SPEED_FAN} fan, then at the same tip speed
     * ({@code SPEED_FAN} / fan of it; a fan's tip runs at about 60 m/s whatever its size), on whole turns in
     * {@link ProcessUnit#MOTION_PERIOD}.
     */
    static double fanSpeed(double fan) {
        return fan <= SPEED_FAN + 1e-9 ? ProcessUnit.FAN_SPEED : ProcessUnit.shown(60 * ProcessUnit.FAN_SPEED * SPEED_FAN / fan);
    }

    /** The largest fan shown at {@link ProcessUnit#FAN_SPEED}, m: the largest of the bays before 2026-11-15 (the 3 by 5 m bay's one fan). */
    static final double SPEED_FAN = 2.35;

    @Override
    public boolean banks() {
        return true;
    }

    @Override
    void check(double size, int length, int support, ProcessUnit.Bank bank) {
        if (UnitRatings.coverage(size, length) < ProcessUnit.FAN_COVERAGE)
            throw new IllegalArgumentException("An air cooler bay of " + size + " × " + length + " m has no room for fans covering " + ProcessUnit.FAN_COVERAGE + " of its bundle");
    }

    @Override
    public Line held(EquipmentShape shape) {
        double d = shape.diameter(), length = shape.tangentLength();
        var fans = UnitRatings.fans(d, length);
        int bays = shape.bank().bays();
        return new Line("bay_face", number(bays * UnitRatings.bundleFace(d, length)), bays * fans.count(), size(fans.diameter()));
    }

    @Override
    public List<Line> datasheet(EquipmentShape shape) {
        double d = shape.diameter(), length = shape.tangentLength();
        var fans = UnitRatings.fans(d, length);
        // The process side's bare tube surface and the fans' power at full speed (batch 2026-10-27-heat-exchanger-design).
        double surface = HeatSides.of(shape).getFirst().area();
        return List.of(new Line("bank", shape.bank().parallel(), shape.bank().series()), new Line("bay", size(d), number(length), number(UnitRatings.bundleFace(d, length))),
                new Line("fans", fans.count(), size(fans.diameter()), Math.round(100 * UnitRatings.coverage(d, length))),
                new Line("air_side", number(surface), number(HeatSides.fanPower(surface, 1) / 1000)));
    }

    /**
     * An air cooler bank of forced-draft single-pass bays ({@link ProcessUnit.Bank}), their tubes along z. Per bay: the inlet header at
     * its front and the outlet header at its back (each centred half a metre in from its end), the finned bundle between
     * them {@code support} up, a plenum under it tapering to its fans' floor, each fan (a Blender rotor in its ring and
     * guard) with its motor under it on a beam, and legs under the headers' ends (one shared where trains meet). Bays in
     * series join back header to front header by crossover spools. Trains in parallel take the process from an inlet
     * manifold over their first front headers, the bank's inlet on its top, and give it to an outlet manifold behind their
     * last back headers, the outlet under it where the condensate drains; a single train's inlet is on its first header and
     * its outlet under a stub behind its last. A bay wider than {@link UnitRatings#ONE_BUNDLE} holds two bundles side by side
     * under its one plenum and set of fans, each with its own header boxes and side frames, its headers joined by the
     * manifolds as trains are; it stands on posts and beams 0.3 m deep, and a bay longer than {@link #POST_SPAN} has a post
     * line at its middle (AIR_COOLER_BAY_PLAN.md 4.4).
     */
    @Override
    void build(ProcessUnit u) {
        var bank = u.shape.bank();
        int trains = bank.parallel(), rows = bank.series();
        double w = u.shape.diameter(), length = u.shape.tangentLength(), hb = u.shape.support(), h = ProcessUnit.header(length);
        double low = hb - .05, high = low + ProcessUnit.HEADER_HEIGHT, a = ProcessUnit.centre(trains * w / 2), x0 = a - trains * w / 2, x1 = x0 + trains * w, end = rows * length;
        var fans = UnitRatings.fans(w, length);
        var stack = FanStack.of(hb, fans.diameter());
        double d = fans.diameter(), up = stack.up(), bottom = stack.floor(), rotor = stack.rotor();
        double motor = stack.motor(), motorTop = stack.motorTop(), motorBottom = stack.motorBottom();
        // A bay's bundles side by side (two above 3 m wide), each a header column; the nozzles and crossovers by a bundle's
        // width; a manifold carries the columns' flow at the same speed.
        int nb = UnitRatings.bundles(w), columns = trains * nb;
        double bw = w / nb, stub = .1 + .04 * bw, manifold = Math.min(ProcessUnit.HEADER_HEIGHT - .1, stub * Math.sqrt(columns));
        boolean wide = nb > 1;
        double post = wide ? WIDE_POST : ProcessUnit.POST, beam = wide ? WIDE_POST : ProcessUnit.BEAM;
        var fanZ = new ArrayList<Double>();
        for (int k = 0; k < rows; k++) {
            double front = k * length + .5, back = (k + 1) * length - .5, front1 = front + h / 2, back0 = back - h / 2;
            for (int i = 0; i < trains; i++) {
                double bx0 = x0 + i * w, bx1 = bx0 + w, mid = bx0 + w / 2, ix0 = bx0 + .11, ix1 = bx1 - .11;
                // Each bundle: its header boxes, its fins and its side frames; two bundles meet at the bay's centre line.
                for (int b = 0; b < nb; b++) {
                    double c0 = bx0 + b * bw, c1 = c0 + bw, hx0 = b == 0 ? c0 + .05 : c0 + .01, hx1 = b + 1 == nb ? c1 - .05 : c1 - .01;
                    double fx0 = b == 0 ? ix0 : c0 + .06, fx1 = b + 1 == nb ? ix1 : c1 - .06;
                    for (double zh : new double[]{front, back})
                        u.box(new double[]{hx0, low, zh - h / 2}, new double[]{hx1, high, zh + h / 2}, Material.AC_HEADER, true);
                    u.box(new double[]{fx0, hb, front1}, new double[]{fx1, hb + ProcessUnit.BUNDLE, back0}, Material.AC_FINS, true);
                    for (double x : new double[]{b == 0 ? bx0 + .05 : c0, fx1})
                        u.box(new double[]{x, hb - .02, front1}, new double[]{x + .06, hb + ProcessUnit.BUNDLE + .02, back0}, Material.STEELWORK, false);
                }
                double share = (back0 - front1 - .1) / fans.count();
                var centres = new double[fans.count()];
                for (int f = 0; f < centres.length; f++) centres[f] = front1 + .05 + (f + .5) * share;
                plenum(u, ix0, ix1, front1, back0, hb, bottom, centres, ProcessUnit.RING_OD / 2 * d);
                for (int f = 0; f < fans.count(); f++) {
                    double zc = front1 + .05 + (f + .5) * share;
                    if (i == 0 && k == 0) fanZ.add(zc);
                    Part.of("ac_fan_ring").place(u.mesh, new double[]{mid, bottom, zc}, d, up, 0, Material.AC_RING);
                    // The rotor turns clockwise seen from below, each from its own angle so a bank's blades are not in step.
                    u.mesh.part(u.motions.size());
                    Part.of("ac_fan").place(u.mesh, new double[]{mid, rotor, zc}, d, d, 0, Material.AC_FAN);
                    u.mesh.part(-1);
                    u.motions.add(Motion.turning(new double[]{mid, rotor, zc}, new double[]{0, -1, 0}, fanSpeed(d), .618 * u.motions.size()));
                    // Terminal boxes face out of the bank.
                    Part.of("ac_motor").place(u.mesh, new double[]{mid, motorTop, zc}, motor, motor, 2 * i < trains - 1 ? 2 : 0, Material.AC_MOTOR);
                    u.mesh.cylinder(Frame.of(new double[]{mid, 0, zc}, 1, 1), .025 * motor, motorTop, rotor - .06 * d, 8, Material.FLANGE);
                    u.solids.add(Solid.rod(new double[]{mid, 0, zc}, 1, bottom - .35 * up, bottom, .535 * d, 0));
                    u.solids.add(Solid.rod(new double[]{mid, 0, zc}, 1, motorBottom, motorTop, .2 * motor, 0));
                }
                if (k + 1 < rows) for (int b = 0; b < nb; b++) spool(u, bx0 + (b + .5) * bw, (low + high) / 2, back + h / 2, back + 1 - h / 2, stub);
            }
        }
        // Legs under the headers' ends; beams under the headers across the bank and along each leg line; at the motors'
        // level beams along the leg lines and across the bank under each row of motors.
        var legX = new ArrayList<Double>();
        legX.add(x0 + .15);
        for (int i = 1; i < trains; i++) legX.add(x0 + i * w);
        legX.add(x1 - .15);
        var legZ = new ArrayList<Double>();
        // Post lines under the headers; one more at the middle of a bay longer than POST_SPAN (no beam across it: the bundle
        // rests on the side beams, and one would run inside the plenum).
        var middles = new ArrayList<Double>();
        for (int k = 0; k < rows; k++) {
            legZ.add(k * length + .5);
            if (length > POST_SPAN) middles.add(k * length + length / 2.0);
            legZ.add((k + 1) * length - .5);
        }
        var posts = new ArrayList<>(legZ);
        posts.addAll(middles);
        for (double x : legX)
            for (double z : posts) u.box(new double[]{x - post / 2, 0, z - post / 2}, new double[]{x + post / 2, low, z + post / 2}, Material.STEELWORK, true);
        for (double z : legZ) u.box(new double[]{x0 + .05, low - beam, z - ProcessUnit.FLANGE_BEAM / 2}, new double[]{x1 - .05, low, z + ProcessUnit.FLANGE_BEAM / 2}, Material.STEELWORK, true);
        for (double x : legX)
            for (double y : new double[]{low, motorBottom})
                u.box(new double[]{x - ProcessUnit.FLANGE_BEAM / 2, y - (y == low ? beam : ProcessUnit.BEAM), legZ.getFirst()}, new double[]{x + ProcessUnit.FLANGE_BEAM / 2, y, legZ.getLast()}, Material.STEELWORK, true);
        // The motor beams keep their depth on every bay: the fans' free air under them stands as it was.
        for (int k = 0; k < rows; k++)
            for (double zc : fanZ)
                u.box(new double[]{x0 + .15, motorBottom - ProcessUnit.BEAM, k * length + zc - ProcessUnit.FLANGE_BEAM / 2}, new double[]{x1 - .15, motorBottom, k * length + zc + ProcessUnit.FLANGE_BEAM / 2},
                        Material.STEELWORK, true);
        // The process in at the front, on top; out at the back, underneath.
        double zIn = .5, zOut = end + .5;
        if (columns == 1) u.nozzles.add(new Nozzle(Role.PROCESS_INLET, new double[]{a, high - .03, zIn}, 1, 1, Math.ceil(high + ProcessUnit.NECK) - high + .03, stub, 0));
        else {
            double ym = high + .1 + manifold / 2;
            u.manifold(x0 + bw / 2, x1 - bw / 2, ym, zIn, manifold);
            for (int i = 0; i < columns; i++) {
                var f = Frame.of(new double[]{x0 + (i + .5) * bw, 0, zIn}, 1, 1);
                u.mesh.prism(f, stub / 2, high, ym, ProcessUnit.sides(stub), Material.PIPE);
                u.mesh.ring(f, stub / 2, PipeSizes.flange(stub) / 2, high, high + .03, ProcessUnit.sides(stub), 3, Material.FLANGE);
                u.mesh.flangeBolts(f, stub, stub / 2, PipeSizes.flange(stub) / 2, high + .03, 1, Material.BOLT);
            }
            u.nozzles.add(new Nozzle(Role.PROCESS_INLET, new double[]{a, ym + manifold / 2 - .03, zIn}, 1, 1,
                    Math.ceil(ym + manifold / 2 + ProcessUnit.NECK) - ym - manifold / 2 + .03, manifold, 0));
        }
        double out = columns == 1 ? stub : manifold, yo = low + .05 + stub / 2;
        for (int i = 0; i < columns; i++) {
            double mid = x0 + (i + .5) * bw, z0 = end - .5 + h / 2;
            var f = Frame.of(new double[]{mid, yo, 0}, 2, 1);
            u.mesh.prism(f, stub / 2, z0, columns == 1 ? zOut + stub / 2 : zOut, ProcessUnit.sides(stub), Material.PIPE);
            u.mesh.ring(f, stub / 2, PipeSizes.flange(stub) / 2, z0, z0 + .03, ProcessUnit.sides(stub), 3, Material.FLANGE);
            u.mesh.flangeBolts(f, stub, stub / 2, PipeSizes.flange(stub) / 2, z0 + .03, 1, Material.BOLT);
            if (columns == 1) u.mesh.annulus(f, 0, stub / 2, zOut + stub / 2, ProcessUnit.sides(stub), 1, Material.PIPE);
            u.solids.add(Solid.rod(new double[]{mid, yo, 0}, 2, z0, zOut, stub / 2, 0));
        }
        if (columns > 1) u.manifold(x0 + bw / 2, x1 - bw / 2, yo, zOut, manifold);
        double outBase = yo - out / 2 + .03;
        u.nozzles.add(new Nozzle(Role.PROCESS_OUTLET, new double[]{a, outBase, zOut}, 1, -1, outBase - Math.floor(yo - out / 2 - ProcessUnit.NECK), out, 0));
        var outline = new double[][]{{x0 + .05, high, .5 - h / 2}, {x1 - .05, high, .5 - h / 2}, {x1 - .05, high, end - .5 + h / 2}, {x0 + .05, high, end - .5 + h / 2}};
        for (int k = 0; k < 4; k++) u.segment(outline[k], outline[(k + 1) % 4]);
        // The bank's FE port (W5, reading A58): the foot of its front corner leg, at grade, on the bank's outside faces, the
        // inlet above and the outlet at the back far from it; the fans' motors share it (one feeder per bay, reading A71).
        u.terminal = new double[]{legX.getFirst(), .25, legZ.getFirst()};
    }

    /** A two-bundle bay's posts and beams, m (AIR_COOLER_BAY_PLAN.md 4.4). */
    static final double WIDE_POST = .3;
    /** The longest bay standing on its header lines' posts alone, m: a longer one has a post line at its middle (columns about 6 m apart). */
    static final int POST_SPAN = 6;

    /** Sides of the Blender fan ring's polygon ({@code ac_fan_ring}), a multiple of 4 for the hopper's quadrants. */
    static final int RING_SIDES = 24;

    /**
     * A bay's plenum (owner report 2026-10-16 with a photo: "the metal sheets lack a bottom casing"): a sheet-metal hopper
     * per fan, from the bundle's frame (x0 to x1, the fan's share of z0 to z1, at {@code top}) down to its ring's top
     * flange (radius {@code ring} round each of {@code centres}) at {@code bottom}. Each hopper is a square-to-round
     * transition: a triangle from each side of the frame to the circle's point facing it, a fan of triangles from each
     * corner to the quarter of the circle between, at the ring's {@link #RING_SIDES} corners. Sheets are drawn on both
     * faces, so the casing also shows from inside a ring; looking up through a ring one sees the bundle's fins.
     */
    private static void plenum(ProcessUnit u, double x0, double x1, double z0, double z1, double top, double bottom, double[] centres, double ring) {
        for (int f = 0; f < centres.length; f++) {
            double za = f == 0 ? z0 : (centres[f - 1] + centres[f]) / 2, zb = f + 1 == centres.length ? z1 : (centres[f] + centres[f + 1]) / 2;
            // The ring part's top flange is a 24-gon at these angles (ac_parts.py, lathe n = 24): the hopper meets its corners.
            u.transition(1, x0, x1, za, zb, top, (x0 + x1) / 2, centres[f], ring, bottom, RING_SIDES, Material.AC_PLENUM, true);
        }
        // Collides as its frame's box; encloses its inside.
        u.solids.add(Solid.box(new double[]{x0, bottom, z0}, new double[]{x1, top, z1}));
        u.volumes.add(Solid.box(new double[]{x0 + .02, bottom + .02, z0 + .02}, new double[]{x1 - .02, top - .02, z1 - .02}));
    }

    /** A crossover spool along z at (x, y) from a back header's face {@code z0} to the next front header's {@code z1}, flanged at both. */
    private static void spool(ProcessUnit u, double x, double y, double z0, double z1, double od) {
        var f = Frame.of(new double[]{x, y, 0}, 2, 1);
        int n = ProcessUnit.sides(od);
        double r = PipeSizes.flange(od) / 2;
        u.mesh.prism(f, od / 2, z0, z1, n, Material.PIPE);
        u.mesh.ring(f, od / 2, r, z0, z0 + .03, n, 3, Material.FLANGE);
        u.mesh.flangeBolts(f, od, od / 2, r, z0 + .03, 1, Material.BOLT);
        u.mesh.ring(f, od / 2, r, z1 - .03, z1, n, 3, Material.FLANGE);
        u.mesh.flangeBolts(f, od, od / 2, r, z1 - .03, -1, Material.BOLT);
        u.solids.add(Solid.rod(new double[]{x, y, 0}, 2, z0, z1, od / 2, 0));
    }
}
