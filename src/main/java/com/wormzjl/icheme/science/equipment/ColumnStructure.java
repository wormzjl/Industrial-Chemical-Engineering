package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

import static com.wormzjl.icheme.science.equipment.Access.*;
import static com.wormzjl.icheme.science.equipment.Mesh.*;

/**
 * What a distillation column carries besides its shell (owner request 2026-10-16, batch
 * 2026-10-15-custom-equipment-rendering; photo survey section 2): the insulation's aluminium cladding with its rain
 * flashing rings, part-ring platforms at the manways (grating decks, handrails with mid-rails and toe boards, knee
 * braces), caged ladders between them, the manways with their hinge davits, a full ring at the top with the lifting davit,
 * and the process nozzles. Decks, handrails and ladders are drawn by {@link Access}, shared with the fired heaters.
 *
 * <p>Plant layout keeps a column's piping on one side and its access on the other: the nozzles face the front (towards the
 * constructor and the pipe rack) and the two sides, the platforms, ladders and manways the back. Ladders stand at the two
 * back diagonals and alternate at each platform, so every climb ends beside the next deck and starts on the deck below.
 * The process sets each nozzle's height (finding F3, batch 2026-10-26-multi-machine-framework): a crowded nozzle turns to
 * another side, a ladder moves aside from a nozzle, and a deck is cut round one.
 *
 * <p>Built in the vessel frame ({@link EquipmentShape}): axis along +y through (a, ·, a), front -z, back +z; an angle θ
 * runs from +x towards +z, so the back is θ = 90°. Heights are frame y, which is box y for a standing vessel; deck levels
 * are whole metres, so a deck's walking surface is {@link #DECK} above a block boundary.
 */
final class ColumnStructure {
    /** Least deck width, m: the kit's ({@link Access#MIN_DECK}), which the designs reach through the column's name too. */
    static final double MIN_DECK = Access.MIN_DECK;
    /** Manway: neck and cover diameters (24" class 150), cover face beyond the cladding, centre over the deck, m. */
    static final double MANWAY = Manway.LARGE, COVER = Manway.COVER_RATIO * MANWAY, MANWAY_REACH = Manway.REACH, MANWAY_HEIGHT = .9;
    /** Rain flashing rings: pitch, band height and projection, m. */
    static final double FLASHING_PITCH = 3, FLASHING = 1 / 16.0;
    /** Platforms: at least this far apart, and no climb longer than {@link #MAX_CLIMB} (the kit's {@link Access#MAX_CLIMB}, old OSHA 1910.27), m. */
    static final int MIN_PITCH = 3, MAX_CLIMB = Access.MAX_CLIMB;
    /** The two ladder positions (back-right and back-left diagonals) and the manways' (the back). */
    static final double LADDER_A = 3 * Math.PI / 4, LADDER_B = Math.PI / 4, BACK = Math.PI / 2;

    /** A platform: its deck from frame y {@code level} to {@code level + DECK}, over angles [from, to]; which ends carry a rail. */
    record Platform(int level, double from, double to, boolean ring, boolean closedFrom, boolean closedTo, boolean manway) {
        double manwayHeight() {
            return level + DECK + MANWAY_HEIGHT;
        }
    }

    /** A ladder at angle {@code angle}, from its foot at {@code base} to the top of its rails at {@code top}. */
    record Ladder(double angle, double base, double top) {
        boolean caged() {
            return Access.caged(base, top);
        }
    }

    /** The layout: platforms bottom to top (the last is the top ring), ladders (the first from the ground), the lifting davit. */
    record Plan(List<Platform> platforms, List<Ladder> ladders, double deckRadius, double davitAngle, double davitTop) {
        Platform top() {
            return platforms.getLast();
        }
    }

    /** The placed nozzles of a shape, and every side nozzle in placing order, placed or not (findings F1 and F3, batch 2026-10-26-multi-machine-framework). */
    private record Layout(List<EquipmentShape.Nozzle> nozzles, List<ColumnDesign.SideNozzle> sides) {
    }

    private static final int CACHE = 64;
    /** The nozzle layout of each shape: every box and transform reads it, and placing the nozzles tests the structure row by row. */
    private static final Map<EquipmentShape, Layout> LAYOUTS = new ConcurrentHashMap<>();

    private final EquipmentShape shape;
    private final Plan plan;
    private final double axis, outer, apothem, rung;
    private final int n;
    /** The plan's ladders as a player meets them, the first from the ground. */
    private final List<Access.Climb> climbs = new ArrayList<>();

    ColumnStructure(EquipmentShape shape) {
        this.shape = shape;
        axis = shape.axis();
        outer = shape.outerRadius();
        n = shape.facets();
        apothem = outer * DeterministicMath.cos(Math.PI / n);
        rung = outer + STANDOFF;
        plan = plan(shape);
        for (var l : plan.ladders()) {
            var foot = at(rung, l.angle(), 0);
            var out = radial(l.angle());
            climbs.add(new Access.Climb(foot[0], foot[2], out[0], out[2], l.base(), l.top(), l.top()));
        }
    }

    List<Access.Climb> climbs() {
        return climbs;
    }

    /** A side nozzle's heading in the frame, from +x towards +z. */
    private static double heading(int axis, int sign) {
        return axis == 0 ? (sign > 0 ? 0 : Math.PI) : (sign > 0 ? Math.PI / 2 : 3 * Math.PI / 2);
    }

    /**
     * The least outer edge of the decks, m from the axis: at least {@link #MIN_DECK} wide, and beyond the flange face of
     * every side nozzle (on the first block plane {@link EquipmentShape#STAND_OUT} past the cladding; the axis runs through
     * a cell centre), so a nozzle ends inside the handrail. The column's box is as wide as this edge and its rail.
     */
    static double minimumDeckRadius(EquipmentShape shape) {
        double outer = shape.outerRadius();
        return Math.max(outer + GAP + MIN_DECK, Math.ceil(outer + EquipmentShape.STAND_OUT + .5) - .5 + .15);
    }

    /** The decks' outer edge: the least one, widened (by 0.15 m at most) to reach into the box's outermost cells. */
    static double deckRadius(EquipmentShape shape) {
        return Math.max(minimumDeckRadius(shape), shape.width() / 2.0 - .95);
    }

    /** Half the angle a ladder and its cage take at the deck's inner edge, with room for a rail: where a deck ends beside a ladder ({@link Access#clearance}). */
    static double clearance(EquipmentShape shape) {
        return Access.clearance(shape.outerRadius());
    }

    /**
     * The platform levels (photo survey: at the top, the bottom, the feed and every 15 trays or fewer), each one deck
     * {@link #MANWAY_HEIGHT} under its manway, kept {@link #MIN_PITCH} apart from the top down; rest platforms split any
     * climb longer than {@link #MAX_CLIMB}. Every level after the ground, rising, and the lowest that serves a manway. The
     * trays set them; the nozzles are placed round them, the decks and ladders round the nozzles (finding F3).
     */
    private record Levels(List<Integer> levels, int lowestManway) {
        int top() {
            return levels.getLast();
        }
    }

    private static Levels levels(EquipmentShape s) {
        var t = s.trays();
        double d = s.diameter(), yB = s.bottomTangent(), yT = s.topTangent();
        double bottom = Math.max(yB + COVER / 2 + .1, yB + ColumnTrays.bottomSpace(d) - .7);
        double feed = yB + (t.feed() > t.trays() ? ColumnTrays.bottomSpace(d) - .7 : t.trayHeight(d, t.feed()) + .3);
        double top = Math.max(bottom, Math.min(yT - COVER / 2 - .05, yB + t.trayHeight(d, 1) + .6));
        var wanted = new TreeSet<Integer>();
        for (double y : new double[]{bottom, feed, top}) wanted.add(Math.max(1, (int) Math.round(y - DECK - MANWAY_HEIGHT)));
        var kept = new ArrayList<Integer>();
        for (int level : wanted.descendingSet()) if (kept.isEmpty() || kept.getLast() - level >= MIN_PITCH) kept.add(level);
        Collections.reverse(kept);
        var levels = new ArrayList<Integer>();
        int previous = 0;
        for (int level : kept) {
            int gap = level - previous, parts = (gap + MAX_CLIMB - 1) / MAX_CLIMB;
            for (int i = 1; i < parts; i++) levels.add(previous + (int) Math.round(gap * i / (double) parts));
            levels.add(level);
            previous = level;
        }
        return new Levels(List.copyOf(levels), kept.getFirst());
    }

    /** Whether the platform at {@code level} carries a manway: from the lowest manway's level up, where its manway lies on the shell. */
    private static boolean manway(EquipmentShape s, Levels levels, int level) {
        double y = level + DECK + MANWAY_HEIGHT;
        return level >= levels.lowestManway() && y >= s.bottomTangent() + COVER / 2 + .05 && y <= s.topTangent() - COVER / 2 - .05;
    }

    /** Whether cell row [row, row + 1) meets the platform at {@code level}: its deck, rails and the knee braces down to 0.65 m under it. */
    private static boolean atPlatform(double row, int level) {
        return row < level + DECK + RAIL + BAR && row + 1 > level - .65;
    }

    /** The top of the lifting davit: at least 4.5 m over the top deck and 1.2 m over the head, off a block boundary inside the box's top row. */
    static double davitTop(EquipmentShape s) {
        return Math.ceil(Math.max(levels(s).top() + DECK + 3.9, s.top() + .6)) + .6;
    }

    /**
     * The access structure, fitted round the nozzles (finding F3, owner decision 2026-10-27: the process sets a nozzle's
     * height, the access makes room for it). The top platform is a ring, the others part rings over the back between the
     * two ladders that meet them. Each ladder stands at a back diagonal ({@link #LADDER_A}, {@link #LADDER_B}, alternating)
     * or as near it as clears the nozzles: its cage clear of every nozzle's room over its climb, its decks' ends clear of
     * every cut, the manway still between the decks' ends. Each deck is cut round the nozzles at its level, its cut ends
     * railed; of the pieces, those a player reaches stay: the ones beside a ladder, under the next ladder's foot or under
     * the manway. The lifting davit stands on the top deck as near its place as clears the nozzles.
     */
    static Plan plan(EquipmentShape s) {
        var levels = levels(s);
        var list = levels.levels();
        int count = list.size();
        double clear = clearance(s);
        var rooms = Rooms.of(s);
        var cuts = new ArrayList<List<double[]>>();
        for (int level : list) cuts.add(rooms.cutsAt(level));
        double[] angles = new double[count];
        for (int k = 0; k < count; k++) {
            var near = new ArrayList<>(cuts.get(k));
            if (k > 0) near.addAll(cuts.get(k - 1));
            angles[k] = rooms.ladder(k % 2 == 0 ? LADDER_A : LADDER_B, k == 0 ? 0 : list.get(k - 1) + DECK, list.get(k) + DECK + LANDING, near, clear);
        }
        var platforms = new ArrayList<Platform>();
        var ladders = new ArrayList<Ladder>();
        int firstTop = 0;
        for (int k = 0; k < count; k++) {
            int level = list.get(k);
            double in = angles[k];
            boolean manway = manway(s, levels, level);
            ladders.add(new Ladder(in, k == 0 ? 0 : list.get(k - 1) + DECK, level + DECK + LANDING));
            if (k == count - 1) {
                firstTop = platforms.size();
                platforms.addAll(rooms.cut(new Platform(level, in + clear, in + 2 * Math.PI - clear, true, false, false, manway), cuts.get(k), Double.NaN));
            } else {
                double out = angles[k + 1];
                var whole = in > out ? new Platform(level, out - clear, in - clear, false, true, false, manway) : new Platform(level, in + clear, out + clear, false, false, true, manway);
                platforms.addAll(rooms.cut(whole, cuts.get(k), out));
            }
        }
        double top = davitTop(s);
        double davit = rooms.davit(count % 2 == 1 ? Math.PI / 6 : 5 * Math.PI / 6, platforms.subList(firstTop, platforms.size()), list.getLast(), top);
        return new Plan(List.copyOf(platforms), List.copyOf(ladders), deckRadius(s), davit, top);
    }

    // ---- Nozzles ------------------------------------------------------------------------------------------------

    /** The sides a column's side nozzle faces, as indices into {@link EquipmentShape#side}: the front, the left, the back, the right. */
    static final int FRONT = 0, LEFT = 1, BACK_SIDE = 2, RIGHT = 3;

    /**
     * The process nozzles (owner decisions 2026-10-16: the player places the units and runs the pipes; 2026-10-27, finding
     * F3: each at its stage, the access round it), each centred on a cell row so its flange face lies on a block face: the
     * vapour outlet on the top head; the bottoms through the skirt's port to the front; and the side nozzles, each in its
     * stage's band: the reflux over tray 1, the feed over its tray, each pumparound's draw under its draw tray and its
     * return over its return tray, the side draws under their trays, the stripping steam under its stage and the
     * reboiler's vapour return ({@link #reboiled}) between the sump's level and the bottom tray. Each has a home side: the
     * feed (and the bottoms where the skirt leaves it no room) the front, towards the constructor and the pipe rack; the
     * reflux and the pumparounds the right; the vapour return, the draws and the steam the left. A crowded nozzle turns
     * before it changes row ({@link Rows#side}); one the player turned in the constructor keeps that side. Sizes follow
     * the column diameter; each takes any pipe. A nozzle with no free row and side is left out and listed by
     * {@link #unplaced}. Cached per shape.
     */
    static void nozzles(EquipmentShape s, List<EquipmentShape.Nozzle> out) {
        out.addAll(layout(s).nozzles());
    }

    /** The side nozzles the shell of {@code s} has no row for, in placing order: a column calculator refuses to save such a design. */
    static List<ColumnDesign.SideNozzle> unplaced(EquipmentShape s) {
        return layout(s).sides().stream().filter(n -> !n.placed()).toList();
    }

    /** Every side nozzle of {@code s} in placing order, placed or not, with the side it faces. */
    static List<ColumnDesign.SideNozzle> sides(EquipmentShape s) {
        return layout(s).sides();
    }

    private static Layout layout(EquipmentShape s) {
        var cached = LAYOUTS.get(s);
        if (cached == null) {
            if (LAYOUTS.size() >= CACHE) LAYOUTS.clear();
            cached = LAYOUTS.computeIfAbsent(s, ColumnStructure::place);
        }
        return cached;
    }

    private static Layout place(EquipmentShape s) {
        var t = s.trays();
        double d = s.diameter(), a = s.axis(), yB = s.bottomTangent(), yT = s.topTangent();
        // The top neck rooted under the head where a pipe's largest neck's edge meets it (owner decision 2026-10-21: a 1 m
        // pipe has a 1 m root).
        double topOd = size(.4 * d, PipeSizes.MAX_OUTSIDE), start = s.headSurface(PipeSizes.MAX_OUTSIDE / 2) - .03;
        var out = new ArrayList<EquipmentShape.Nozzle>();
        out.add(new EquipmentShape.Nozzle(EquipmentShape.Role.VAPOUR_OUTLET, new double[]{a, start, a}, 1, 1, Math.ceil(s.top() + EquipmentShape.STAND_OUT) - start, topOd, 0));
        double sump = ColumnTrays.bottomSpace(d);
        // The bands (finding F3): an inlet between its tray and the tray over it (the top tangent over tray 1), an outlet
        // between its tray and the tray under it (the sump's level under the bottom tray), so the sump's nozzles stand
        // between its level and the bottom tray.
        IntFunction<double[]> over = k -> new double[]{yB + t.trayHeight(d, k), k == 1 ? yT : yB + t.trayHeight(d, k - 1)};
        IntFunction<double[]> under = k -> new double[]{yB + t.trayHeight(d, Math.min(k, t.trays()) + 1), yB + t.trayHeight(d, Math.min(k, t.trays()))};
        var rows = new Rows(s);
        rows.side(EquipmentShape.Role.REFLUX, 1, over.apply(1), yB + t.trayHeight(d, 1) + .45, RIGHT, size(d / 8, .5));
        boolean sumpFeed = t.feed() > t.trays();
        rows.side(EquipmentShape.Role.FEED, 1, sumpFeed ? under.apply(t.feed()) : over.apply(t.feed()), yB + (sumpFeed ? sump - .3 : t.trayHeight(d, t.feed()) + .3),
                FRONT, size(d / 5, .8));
        for (int i = 0; i < t.pumparounds().size(); i++) {
            var p = t.pumparounds().get(i);
            rows.side(EquipmentShape.Role.PUMPAROUND_DRAW, i + 1, under.apply(p.draw()), yB + t.trayHeight(d, p.draw()) - .2, RIGHT, size(d / 6, .6));
            rows.side(EquipmentShape.Role.RETURN, i + 1, over.apply(p.ret()), yB + t.trayHeight(d, p.ret()) + .3, RIGHT, size(d / 6, .6));
        }
        if (reboiled(t))
            rows.side(EquipmentShape.Role.VAPOUR_RETURN, 1, under.apply(t.trays() + 1), yB + .75 * sump, LEFT, returnOd(d));
        for (int i = 0; i < t.draws().size(); i++)
            rows.side(EquipmentShape.Role.DRAW, i + 1, under.apply(t.draws().get(i)), yB + t.trayHeight(d, t.draws().get(i)) - .2, LEFT, size(d / 8, .5));
        for (int i = 0; i < t.steam().size(); i++) {
            int stage = t.steam().get(i);
            rows.side(EquipmentShape.Role.STEAM, i + 1, under.apply(stage), yB + (stage > t.trays() ? sump : t.trayHeight(d, stage)) - .3, LEFT, size(d / 8, .5));
        }
        var bottoms = s.skirtOutlet(EquipmentShape.Role.BOTTOMS, size(d / 6, .6));
        if (bottoms == null) rows.side(EquipmentShape.Role.BOTTOMS, 1, new double[]{yB, yB + sump / 2}, yB + .3, FRONT, size(d / 6, .6));
        out.addAll(rows.placed);
        if (bottoms != null) out.add(bottoms);
        return new Layout(List.copyOf(out), List.copyOf(rows.sides));
    }

    /**
     * Whether a column carries the reboiler's vapour return (owner answer Q9 (a), batch 2026-10-26-multi-machine-framework):
     * where its component design has a kettle. A column stripped by sump steam has no reboiler, so no return; a column
     * without a component design keeps the nozzle it was built with.
     */
    static boolean reboiled(ColumnTrays trays) {
        return trays.components() == null || trays.components().kettle();
    }

    /** The reboiler's vapour return's design size on a column {@code diameter} across: the largest side nozzle; the sump's stack makes room for it. */
    static double returnOd(double diameter) {
        return size(.27 * diameter, PipeSizes.MAX_OUTSIDE);
    }

    /**
     * A nozzle or line size from a fraction of the diameter: to the next 5 cm, at least 0.1 m, at most {@code max} (which
     * wins: a neck narrower than 0.1 m, a small ejector's, defaults to its largest size).
     */
    static double size(double od, double max) {
        return Math.min(max, Math.max(.1, Math.round(od * 20) / 20.0));
    }

    /** The sides a nozzle tries from its home side: that side, the two at 90° (the front first, else the left), then the opposite one. */
    private static int[] order(int home) {
        return switch (home) {
            case FRONT -> new int[]{FRONT, LEFT, RIGHT, BACK_SIDE};
            case LEFT -> new int[]{LEFT, FRONT, BACK_SIDE, RIGHT};
            case BACK_SIDE -> new int[]{BACK_SIDE, LEFT, RIGHT, FRONT};
            default -> new int[]{RIGHT, FRONT, BACK_SIDE, LEFT};
        };
    }

    /** The side nozzles placed so far on a shape, and every side nozzle with its side, placed or not. */
    private static final class Rows {
        private final EquipmentShape s;
        private final Levels levels;
        private final double apothem, outer, face, clear, deck;
        final List<EquipmentShape.Nozzle> placed = new ArrayList<>();
        final List<ColumnDesign.SideNozzle> sides = new ArrayList<>();

        Rows(EquipmentShape s) {
            this.s = s;
            levels = levels(s);
            apothem = s.outerRadius() * DeterministicMath.cos(Math.PI / s.facets());
            outer = s.outerRadius();
            face = Rooms.face(s);
            clear = clearance(s);
            deck = deckRadius(s);
        }

        /**
         * Adds a radial nozzle in its stage's band (heights {@code band}: a cell row reaching into it, its flange on the
         * shell; nearest {@code y} first, then the lower), facing the first side where it meets no other nozzle: the side
         * the player turned it to, else its home side, the sides at 90° and the opposite one ({@link #order}). It turns
         * before it changes row (finding F3). No two flanges on a side overlap, every pipe block keeps one free block from
         * every other ({@link EquipmentShape.Nozzle#crowds}), and a nozzle at the back keeps off the platforms' rows and
         * the manways, which stand there. A row and side whose room a ladder's cage at its back diagonal meets is taken
         * only where no other is free (a slim column's ladders cannot move far enough aside, {@link Rooms#ladder}), and
         * never where the cage reaches into the block its pipe stands in, nor where its deck cut would part a walk between
         * two ladders ({@link #severs}). Lists it as unplaced when no row and side is free.
         */
        void side(EquipmentShape.Role role, int index, double[] band, double y, int home, double od) {
            double half = PipeSizes.flange(od) / 2, neck = Rooms.neck(half), yB = s.bottomTangent(), yT = s.topTangent();
            int turned = s.trays().turn(role, index);
            int[] tried = turned >= 0 ? new int[]{turned} : order(home);
            var rows = new ArrayList<Double>();
            for (double at = Math.floor(band[0]) + .5; at - .5 < band[1]; at++) if (at - half >= yB + .05 && at + half <= yT - .05) rows.add(at);
            rows.sort(Comparator.comparingDouble((Double at) -> Math.abs(at - y)).thenComparingDouble(at -> at));
            for (boolean pass : new boolean[]{true, false})
                for (double at : rows)
                    for (int side : tried) {
                        int[] facing = EquipmentShape.side(side);
                        var nozzle = s.side(role, s.axis(), at, facing[0], facing[1], apothem, od);
                        if (free(nozzle, half, side) && !ladderMeets(at, side, neck, pass) && !severs(at, side, neck)) {
                            placed.add(nozzle);
                            sides.add(new ColumnDesign.SideNozzle(role, index, home, side, turned >= 0, nozzle));
                            return;
                        }
                    }
            sides.add(new ColumnDesign.SideNozzle(role, index, home, turned >= 0 ? turned : home, turned >= 0, null));
        }

        /**
         * Whether the cage of a ladder at its back diagonal, climbing past cell row {@code at}, meets the room of a nozzle
         * there facing {@code side} with a neck {@code neck} to each side ({@code room}), or else the block its pipe stands in.
         */
        private boolean ladderMeets(double at, int side, double neck, boolean room) {
            double row = Math.floor(at);
            int[] facing = EquipmentShape.side(side);
            double heading = heading(facing[0], facing[1]);
            var space = room ? Rooms.room(outer, face, heading, neck) : new double[][][]{Rooms.block(face, heading)};
            var list = levels.levels();
            for (int k = 0; k < list.size(); k++) {
                double base = k == 0 ? 0 : list.get(k - 1) + DECK, top = list.get(k) + DECK + LANDING;
                if (row < top && row + 1 > base && Rooms.meets(Rooms.cage(outer, k % 2 == 0 ? LADDER_A : LADDER_B), space)) return true;
            }
            return false;
        }

        /**
         * Whether a nozzle at cell row {@code at} facing {@code side} would part the walk of a part ring (every platform but
         * the top) between its two ladders: its deck cut reaches the walk's end beside a ladder however near the back that
         * ladder stands ({@link Rooms#ladder} moves it at most to {@code clear} and a step from the back, and a walk spans the
         * back quarter at least). A slim column's left and right nozzles at a platform's rows do; the top ring's walk, from
         * its ladder to the manway at the back, no left, right or front cut reaches.
         */
        private boolean severs(double at, int side, double neck) {
            int[] facing = EquipmentShape.side(side);
            double cut = Rooms.cutHalf(outer, face, deck, neck);
            if (apart(heading(facing[0], facing[1]), BACK) >= cut + clear + Math.min(Math.PI / 4, clear + Rooms.STEP)) return false;
            var list = levels.levels();
            for (int k = 0; k + 1 < list.size(); k++) if (atPlatform(Math.floor(at), list.get(k))) return true;
            return false;
        }

        private boolean free(EquipmentShape.Nozzle nozzle, double half, int side) {
            double at = nozzle.base()[1];
            for (var other : placed)
                if (other.axis() == nozzle.axis() && other.sign() == nozzle.sign() && Math.abs(other.base()[1] - at) < half + PipeSizes.flange(other.defaultOd()) / 2 + .05
                        || nozzle.crowds(other)) return false;
            if (side != BACK_SIDE) return true;
            for (int level : levels.levels()) {
                if (atPlatform(Math.floor(at), level)) return false;
                if (manway(s, levels, level) && Math.abs(level + DECK + MANWAY_HEIGHT - at) < COVER / 2 + half + .05) return false;
            }
            return true;
        }
    }

    /**
     * The room each side nozzle keeps, seen from above (finding F3: the access gives it), over its cell row: its neck and
     * flange, from the cladding to the flange face, half its flange and {@link #MARGIN} to each side of its centre line;
     * and the block beyond the face where its pipe stands, {@link #REACH} to each side. A ladder's cage, a deck and the
     * davit's mast stay out of it. Each part is a convex outline from the axis, tested by separating axes.
     */
    private static final class Rooms {
        /** Half the width kept clear beside a pipe's block (half a block and a margin), and a ladder's or the davit's step when it moves aside. */
        static final double REACH = .55, STEP = Math.PI / 36;
        /** The margin beside a flange. */
        static final double MARGIN = .05;
        /** The narrowest deck piece kept, m along its middle: a player's width; a narrower one stays only under a ladder's foot or the manway. */
        static final double PIECE = .6;
        private final double outer, face, deck, mast, middle;
        /** Each side nozzle's cell row, heading and neck ({@link #neck}). */
        private final List<double[]> rooms = new ArrayList<>();

        private Rooms(EquipmentShape s) {
            outer = s.outerRadius();
            face = face(s);
            deck = deckRadius(s);
            mast = outer + .35;
            middle = (outer + GAP + deck) / 2;
        }

        /** The half width a nozzle's neck keeps beside its centre line: half its flange ({@code flangeHalf}) and the margin, at most a block's. */
        static double neck(double flangeHalf) {
            return Math.min(REACH, flangeHalf + MARGIN);
        }

        /**
         * Half a deck's cut round a nozzle whose neck keeps {@code neck} to each side: the widest angle, seen from the axis,
         * of its room's points on a deck from {@code outer} plus the gap out to {@code deck}: the neck's where it crosses
         * the deck's inner edge, the pipe's block's at its near corner on the flange face where the deck reaches past it.
         * Each part is grown by a rail's bar, so the rail across the cut end stands clear of it. (Each part is a rectangle
         * starting at the shell or at the face, so the angle stays under 90° on a slim column.)
         */
        static double cutHalf(double outer, double face, double deck, double neck) {
            double in = outer + GAP, n = neck + BAR, b = REACH + BAR;
            double cut = DeterministicMath.atan2(n, Math.max(outer, Math.sqrt(Math.max(0, in * in - n * n))));
            return deck > face ? Math.max(cut, DeterministicMath.atan2(b, Math.max(face, Math.sqrt(Math.max(0, in * in - b * b))))) : cut;
        }

        /** The flange faces' plane, from the axis (the axis runs through a cell centre). */
        static double face(EquipmentShape s) {
            return Math.ceil(s.axis() + s.outerRadius() + EquipmentShape.STAND_OUT) - s.axis();
        }

        static Rooms of(EquipmentShape s) {
            var rooms = new Rooms(s);
            for (var nozzle : layout(s).nozzles())
                if (nozzle.axis() != 1)
                    rooms.rooms.add(new double[]{Math.floor(nozzle.base()[1]), heading(nozzle.axis(), nozzle.sign()), neck(PipeSizes.flange(nozzle.defaultOd()) / 2)});
            return rooms;
        }

        /** The cuts of the platform at {@code level}, one per nozzle whose room meets it: its heading and half angle. */
        List<double[]> cutsAt(int level) {
            var out = new ArrayList<double[]>();
            for (var room : rooms) if (atPlatform(room[0], level)) out.add(new double[]{room[1], cutHalf(outer, face, deck, room[2])});
            return out;
        }

        /**
         * A ladder's angle: {@code preferred} or the nearest of its steps that clears the nozzles, the back's side first:
         * its cage clear of every room over its climb from {@code base} to {@code top}, its decks' ends ({@code clear} beside
         * it) clear of the cuts {@code cuts} (each a heading and half angle), and the manway still between the decks' ends
         * ({@code preferred} where none clears).
         */
        double ladder(double preferred, double base, double top, List<double[]> cuts, double clear) {
            boolean right = preferred > Math.PI / 2;
            double low = right ? Math.min(preferred, Math.PI / 2 + clear + STEP) : 0, high = right ? Math.PI : Math.max(preferred, Math.PI / 2 - clear - STEP);
            double toBack = right ? -1 : 1;
            for (int j = 0; j <= 16; j++) {
                double angle = preferred + toBack * STEP * (j % 2 == 1 ? (j + 1) / 2 : -(j / 2));
                if (angle < low - 1e-9 || angle > high + 1e-9) continue;
                if (cageClear(angle, base, top) && endsClear(angle, cuts, clear)) return angle;
            }
            return preferred;
        }

        private boolean cageClear(double angle, double base, double top) {
            var cage = cage(outer, angle);
            for (var room : rooms) if (room[0] < top && room[0] + 1 > base && meets(cage, room(outer, face, room[1], room[2]))) return false;
            return true;
        }

        /** The outline of a ladder's cage at {@code angle} on a shell of outer radius {@code outer}: from the shell out past its hoops. */
        static double[][] cage(double outer, double angle) {
            double r = outer + STANDOFF;
            return new double[][]{point(outer, angle, -CAGE_HALF), point(r + 2 * CAGE_HALF, angle, -CAGE_HALF), point(r + 2 * CAGE_HALF, angle, CAGE_HALF),
                    point(outer, angle, CAGE_HALF)};
        }

        private boolean endsClear(double angle, List<double[]> cuts, double clear) {
            for (var cut : cuts) if (apart(angle, cut[0]) < clear + cut[1]) return false;
            return true;
        }

        /**
         * The pieces of {@code whole} left between the cuts round the nozzles ({@code cuts}: each a heading and half angle), each cut end railed; only
         * those a player reaches: beside the ladder (an end of {@code whole} without a rail), over {@code foot} (the next
         * ladder's foot; NaN for the top ring) or under the manway, which stays on the piece nearest the back (a slim
         * column's ring may end short of it). A piece beside a ladder narrower than {@link #PIECE} goes.
         */
        List<Platform> cut(Platform whole, List<double[]> cuts, double foot) {
            var gaps = new ArrayList<double[]>();
            for (var cut : cuts)
                for (int turn = -1; turn <= 2; turn++) {
                    double centre = cut[0] + turn * 2 * Math.PI;
                    if (centre + cut[1] > whole.from() && centre - cut[1] < whole.to()) gaps.add(new double[]{centre - cut[1], centre + cut[1]});
                }
            gaps.sort(Comparator.comparingDouble(g -> g[0]));
            var pieces = new ArrayList<Platform>();
            double from = whole.from();
            boolean closedFrom = whole.closedFrom();
            for (int g = 0; g <= gaps.size(); g++) {
                boolean last = g == gaps.size();
                double to = last ? whole.to() : gaps.get(g)[0];
                boolean closedTo = !last || whole.closedTo();
                if (to - from > 1e-3) pieces.add(new Platform(whole.level(), from, to, whole.ring(), closedFrom, closedTo, false));
                if (!last) {
                    from = Math.max(from, gaps.get(g)[1]);
                    closedFrom = true;
                }
            }
            int manway = -1;
            if (whole.manway())
                for (int i = 0; i < pieces.size(); i++)
                    if (manway < 0 || away(BACK, pieces.get(i)) < away(BACK, pieces.get(manway))) manway = i;
            var out = new ArrayList<Platform>();
            for (int i = 0; i < pieces.size(); i++) {
                var p = pieces.get(i);
                boolean ladderEnd = p.from() == whole.from() && !whole.closedFrom() || p.to() == whole.to() && !whole.closedTo();
                if (i == manway || !Double.isNaN(foot) && onArc(foot, p.from(), p.to()) || ladderEnd && (p.to() - p.from()) * middle >= PIECE)
                    out.add(i == manway ? new Platform(p.level(), p.from(), p.to(), p.ring(), p.closedFrom(), p.closedTo(), true) : p);
            }
            return out;
        }

        /** How far angle {@code a} lies from the arc of {@code piece}, radians: 0 on it. */
        private static double away(double a, Platform piece) {
            if (onArc(a, piece.from(), piece.to())) return 0;
            return Math.min(apart(a, piece.from()), apart(a, piece.to()));
        }

        /**
         * The lifting davit's angle: {@code preferred} or the nearest of its steps where its mast stands on a top deck
         * piece, a rail's width from its ends, clear of every room from the top deck ({@code level}) to {@code top}.
         */
        double davit(double preferred, List<Platform> deck, int level, double top) {
            double margin = DeterministicMath.asin(Math.min(1, .3 / mast));
            for (int j = 0; j <= 72; j++) {
                double angle = preferred + STEP * (j % 2 == 1 ? (j + 1) / 2 : -(j / 2));
                boolean on = false;
                for (var piece : deck) on |= onArc(angle, piece.from() + margin, piece.to() - margin);
                if (!on) continue;
                var c = point(mast, angle, 0);
                var square = new double[][]{{c[0] - .1, c[1] - .1}, {c[0] + .1, c[1] - .1}, {c[0] + .1, c[1] + .1}, {c[0] - .1, c[1] + .1}};
                boolean clear = true;
                for (var room : rooms) clear &= !(room[0] < top && room[0] + 1 > level && meets(square, room(outer, face, room[1], room[2])));
                if (clear) return angle;
            }
            return preferred;
        }

        /**
         * The room of a nozzle at {@code heading} on a shell of outer radius {@code outer}, its flange faces' plane
         * {@code face} from the axis, its neck {@code neck} to each side: the neck's outline and the pipe's block's.
         */
        static double[][][] room(double outer, double face, double heading, double neck) {
            return new double[][][]{{point(outer, heading, -neck), point(face, heading, -neck), point(face, heading, neck), point(outer, heading, neck)},
                    {point(face, heading, -REACH), point(face + 1, heading, -REACH), point(face + 1, heading, REACH), point(face, heading, REACH)}};
        }

        /** Whether the convex outline {@code outline} overlaps any of {@code parts}. */
        static boolean meets(double[][] outline, double[][][] parts) {
            for (var part : parts) if (overlap(outline, part)) return true;
            return false;
        }

        /** The block a nozzle's pipe stands in, beyond its flange faces' plane {@code face}, at {@code heading}. */
        static double[][] block(double face, double heading) {
            return new double[][]{point(face, heading, -.5), point(face + 1, heading, -.5), point(face + 1, heading, .5), point(face, heading, .5)};
        }

        /** The point {@code r} out from the axis at angle {@code a}, {@code t} across (towards increasing angle), seen from above. */
        private static double[] point(double r, double a, double t) {
            double c = DeterministicMath.cos(a), s = DeterministicMath.sin(a);
            return new double[]{r * c - t * s, r * s + t * c};
        }

        /** Whether angle {@code a} lies on the arc [from, to] (counter-clockwise from +x towards +z). */
        private static boolean onArc(double a, double from, double to) {
            if (to < from) return false;
            double x = from + ((a - from) % (2 * Math.PI) + 2 * Math.PI) % (2 * Math.PI);
            return x <= to + 1e-9;
        }

        /** Whether two convex outlines overlap: no edge normal of either separates them. */
        static boolean overlap(double[][] a, double[][] b) {
            return !separates(a, b) && !separates(b, a);
        }

        private static boolean separates(double[][] edges, double[][] other) {
            for (int i = 0; i < edges.length; i++) {
                double[] p = edges[i], q = edges[(i + 1) % edges.length];
                double nx = q[1] - p[1], nz = p[0] - q[0];
                double minA = Double.MAX_VALUE, maxA = -Double.MAX_VALUE, minB = Double.MAX_VALUE, maxB = -Double.MAX_VALUE;
                for (var v : edges) {
                    double d = v[0] * nx + v[1] * nz;
                    minA = Math.min(minA, d);
                    maxA = Math.max(maxA, d);
                }
                for (var v : other) {
                    double d = v[0] * nx + v[1] * nz;
                    minB = Math.min(minB, d);
                    maxB = Math.max(maxB, d);
                }
                if (maxA <= minB + 1e-9 || maxB <= minA + 1e-9) return true;
            }
            return false;
        }
    }

    // ---- Mesh ---------------------------------------------------------------------------------------------------

    /** Draws the cladding's flashing rings, the platforms, the manways and the lifting davit into {@code mesh}, the ladders into {@code ladders}. */
    void build(Mesh mesh, Mesh ladders, List<EquipmentShape.Nozzle> nozzles) {
        flashing(mesh, nozzles);
        for (var p : plan.platforms()) {
            var run = new Access.Arc(axis, axis, outer, p.from(), p.to(), plan.deckRadius(), Access.Arc.STEP);
            Access.deck(mesh, run, p.level(), plan.deckRadius() - outer, Access.ALL_PARTS);
            Access.handrail(mesh, run, p.level(), plan.deckRadius() - outer, p.closedFrom(), p.closedTo());
            if (p.manway()) manway(p.manwayHeight()).draw(mesh);
        }
        for (var climb : climbs) Access.ladder(ladders, climb, STANDOFF);
        davit(mesh);
    }

    /** Rows of the cladding crossed by a flashing ring: their lower heights. */
    List<Double> flashingHeights() {
        var out = new ArrayList<Double>();
        for (double y = shape.bottomTangent() + FLASHING_PITCH + .5; y + FLASHING < shape.topTangent() - .25; y += FLASHING_PITCH) out.add(y);
        return out;
    }

    /** Heights a platform's deck, rails, braces and manway span. */
    List<double[]> platformSpans() {
        var out = new ArrayList<double[]>();
        for (var p : plan.platforms()) out.add(new double[]{p.level() - .7, p.level() + DECK + 1.45});
        return out;
    }

    Plan plan() {
        return plan;
    }

    private void flashing(Mesh m, List<EquipmentShape.Nozzle> nozzles) {
        double r = outer + FLASHING;
        for (double y : flashingHeights())
            for (int i = 0; i < n; i++) {
                double a0 = 2 * Math.PI * i / n, a1 = 2 * Math.PI * (i + 1) / n;
                if (crossed((a0 + a1) / 2, y + FLASHING / 2, nozzles)) continue;
                var outward = radial((a0 + a1) / 2);
                m.flat(List.of(at(r, a0, y), at(r, a1, y), at(r, a1, y + FLASHING), at(r, a0, y + FLASHING)), outward, Material.CLADDING);
                m.flat(List.of(at(outer, a0, y + FLASHING), at(outer, a1, y + FLASHING), at(r, a1, y + FLASHING), at(r, a0, y + FLASHING)), UP, Material.CLADDING);
                m.flat(List.of(at(outer, a0, y), at(outer, a1, y), at(r, a1, y), at(r, a0, y)), DOWN, Material.CLADDING);
            }
    }

    /** Whether a side nozzle or a manway passes through the cladding at angle {@code angle} and height {@code y}: the ring stops there. */
    private boolean crossed(double angle, double y, List<EquipmentShape.Nozzle> nozzles) {
        double half = Math.PI / n;
        for (var nozzle : nozzles) {
            if (nozzle.axis() == 1) continue;
            double reach = PipeSizes.flange(PipeSizes.MAX_OUTSIDE) / 2 + .05;
            double heading = nozzle.axis() == 0 ? (nozzle.sign() > 0 ? 0 : Math.PI) : (nozzle.sign() > 0 ? Math.PI / 2 : 3 * Math.PI / 2);
            if (Math.abs(nozzle.base()[1] - y) < reach && apart(angle, heading) < reach / outer + half) return true;
        }
        for (var p : plan.platforms())
            if (p.manway() && Math.abs(p.manwayHeight() - y) < COVER / 2 + .05 && apart(angle, BACK) < (COVER / 2 + .05) / outer + half) return true;
        return false;
    }

    private static double apart(double a, double b) {
        double d = Math.abs(a - b) % (2 * Math.PI);
        return Math.min(d, 2 * Math.PI - d);
    }

    /** The manway over a platform, at height {@code y}, facing the back ({@link Manway}): its 24" neck through the cladding. */
    private Manway manway(double y) {
        double r = MANWAY / 2;
        return new Manway(new double[]{axis, y, axis}, new double[]{0, 0, 1}, UP, MANWAY, Math.sqrt(apothem * apothem - r * r) - .03, outer, outer + MANWAY_REACH);
    }

    /** The lifting davit on the top ring ({@link Access#davit}): a mast on a base plate, a jib out past the deck with a knee brace, a cable and a hook block. */
    private void davit(Mesh m) {
        double a = plan.davitAngle(), r = outer + .35, base = plan.top().level() + DECK, top = plan.davitTop();
        double reach = Math.min(plan.deckRadius() + .5, (axis - .25) / Math.max(Math.abs(DeterministicMath.cos(a)), Math.abs(DeterministicMath.sin(a))));
        Access.davit(m, axis, axis, a, r, base, top, reach);
    }

    // ---- Solids -------------------------------------------------------------------------------------------------

    /**
     * Whether a frame point lies in a part a player collides with: a deck (walkable at {@link #DECK}), a handrail or a
     * closed end as a fence {@link #FENCE} high, a ladder's rung plane (a player climbs by pressing against it), a manway,
     * or the davit's mast. Walls are 0.2 m thick so the collision sampling cannot miss them; cages and braces do not collide.
     */
    boolean inside(double[] p) {
        double dx = p[0] - axis, dz = p[2] - axis, y = p[1], rho = Math.sqrt(dx * dx + dz * dz), edge = plan.deckRadius();
        for (var platform : plan.platforms()) {
            double level = platform.level();
            if (y < level || y > level + DECK + FENCE) continue;
            boolean within = within(dx, dz, platform.from(), platform.to());
            if (within && rho >= outer + GAP && rho <= edge && (y <= level + DECK || rho >= edge - RAIL_BAND)) return true;
            if (y > level + DECK)
                for (int e = 0; e < 2; e++) {
                    if (!(e == 0 ? platform.closedFrom() : platform.closedTo())) continue;
                    double a = e == 0 ? platform.from() : platform.to(), along = along(dx, dz, a), across = across(dx, dz, a);
                    if (along >= outer + GAP && along <= edge && Math.abs(across) <= RAIL_BAND / 2) return true;
                }
        }
        for (var climb : climbs) if (climb.rungs(p)) return true;
        for (var platform : plan.platforms()) {
            if (!platform.manway()) continue;
            if (manway(platform.manwayHeight()).inside(p)) return true;
        }
        var mast = at(outer + .35, plan.davitAngle(), 0);
        double mx = p[0] - mast[0], mz = p[2] - mast[2];
        return y >= plan.top().level() && y <= plan.davitTop() && mx * mx + mz * mz <= .01;
    }

    /** Whether a frame point lies where a ladder is climbed: in front of its rungs, inside its cage, from its foot to its top. */
    boolean climbable(double[] p) {
        for (var climb : climbs) if (climb.climbable(p)) return true;
        return false;
    }

    /** Whether the direction (dx, dz) from the axis lies in the arc [from, to] (counter-clockwise from +x towards +z). */
    private static boolean within(double dx, double dz, double from, double to) {
        double c0 = DeterministicMath.cos(from), s0 = DeterministicMath.sin(from), c1 = DeterministicMath.cos(to), s1 = DeterministicMath.sin(to);
        boolean afterFrom = c0 * dz - s0 * dx >= 0, beforeTo = dx * s1 - dz * c1 >= 0;
        return to - from <= Math.PI ? afterFrom && beforeTo : afterFrom || beforeTo;
    }

    private static double along(double dx, double dz, double a) {
        return dx * DeterministicMath.cos(a) + dz * DeterministicMath.sin(a);
    }

    private static double across(double dx, double dz, double a) {
        return -dx * DeterministicMath.sin(a) + dz * DeterministicMath.cos(a);
    }

    // ---- Frame helpers ------------------------------------------------------------------------------------------

    private static final double[] UP = {0, 1, 0}, DOWN = {0, -1, 0};

    private double[] at(double r, double a, double y) {
        return at(r, a, 0, y);
    }

    /** The point {@code r} out from the axis towards angle {@code a}, {@code t} across (towards increasing angle), at height {@code y}. */
    private double[] at(double r, double a, double t, double y) {
        double c = DeterministicMath.cos(a), s = DeterministicMath.sin(a);
        return new double[]{axis + r * c - t * s, y, axis + r * s + t * c};
    }

    private static double[] radial(double a) {
        return new double[]{DeterministicMath.cos(a), 0, DeterministicMath.sin(a)};
    }
}
