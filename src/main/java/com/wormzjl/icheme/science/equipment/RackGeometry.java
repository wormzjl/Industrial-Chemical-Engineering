package com.wormzjl.icheme.science.equipment;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static com.wormzjl.icheme.science.equipment.Mesh.*;

/**
 * A layered pipe rack cell (batch 2026-10-26-pipe-laying-system, RACK_LAYERS_PLAN option A with support P2): one tier of
 * four positions across the run, the lanes laid on it, a crossing level above it and one below, and the steel that
 * carries them.
 *
 * <p>Canonical cell coordinates: u across the run from the left side (as seen along the facing), y up, w along the run
 * from the back face; a {@link Rack} maps them to block coordinates. Every tier line rests on the beam, whose top lies at
 * {@link #BEAM_TOP}: a line's centre stands its radius above it, rounded up to the 1/32 m grid ({@link #height}); a line
 * too large to rest there within the block (a four-position line) sits at mid-height on a beam lowered beneath it, as
 * support P1 has it (owner, 2026-10-26). So every point where a lane meets a face lies on the transition pipes' grid
 * ({@link LineGeometry#OFFSET}). Lines turn and leave at mid-block, between the bents, which stand in the block's face
 * planes.</p>
 *
 * <p>A lane's option says where it lies: a tier lane by its first position p and the level l its side arm leaves at
 * (option p + 4 l), a crossing lane by its level (option 12 above the tier, 13 below). In a tee the level is the lane's
 * leg instead ({@link #RUN}, {@link #TURN_LEFT}, {@link #TURN_RIGHT}). Lanes meet across a face where their points on it
 * coincide, so the numbering is only a label.</p>
 *
 * <p>A cell's shape is decided once, when it is placed, from the racks whose runs point into it and the player's look
 * ({@link #placed}, RACK_JUNCTION_PLAN J1 and J2): straight, a corner, or a tee where a branch meets a run.</p>
 */
public final class RackGeometry {
    public static final int POSITIONS = 4, MAX_WIDTH = 4;
    /** The pitch of the positions, the first position's centre, and the clearance kept between lines. */
    public static final double PITCH = 3 / 16.0, FIRST = 7 / 32.0, CLEARANCE = 1 / 64.0;
    /** Heights in 1/32 m steps: the beam's top, the crossing levels above and below. */
    public static final int BEAM_TOP = 13, UPPER = 26, LOWER = 6;
    /** How a side arm leaves: at its tier (flat), over the tier at the upper level, or under it at the lower. */
    public static final int TIER = 0, ABOVE = 1, BELOW = 2;
    /** A tee's legs, as its options' levels: along the run through it, or from the branch turning left or right into the run. */
    public static final int RUN = TIER, TURN_LEFT = ABOVE, TURN_RIGHT = BELOW;
    public static final int OPTIONS = 14;
    /**
     * Shapes: straight; a corner, by the end face (back, front) and the side (left, right) its lanes turn between; a tee,
     * whose run passes from its left face to its right, and whose branch leaves through its front.
     */
    public static final int STRAIGHT = 0, BACK_LEFT = 1, BACK_RIGHT = 2, FRONT_LEFT = 3, FRONT_RIGHT = 4, TEE = 5;
    /**
     * The drawn steel bits ({@link #steel}): a bent in the back face's plane and in the front's; a rack stands on the cell
     * (its columns run on up); a rack beside it joins it on the left and on the right; it stands on the ground, not on a
     * rack (pedestals); the bent at the back and at the front is a run's end, or faces a junction (a whole bent, not half
     * of one shared with the next cell); and its side braces rise from the back (they alternate block by block).
     */
    public static final int BENT_BACK = 1, BENT_FRONT = 2, STACKED = 4, LEFT_JOINED = 8, RIGHT_JOINED = 16, GROUNDED = 32, END_BACK = 64, END_FRONT = 128, RISING = 256,
            STEEL = 511;
    /** The canonical faces a block face is to a rack cell. */
    static final int FRONT = 0, BACK = 1, LEFT = 2, RIGHT = 3, TOP = 4, BOTTOM = 5;
    private static final double S = LineGeometry.OFFSET, EPS = 1e-9;
    /** A lane's bend radius at most: its bends start clear of the bents' planes, 2/32 m deep at each run face, so a riser never sweeps a beam. */
    static final double BEND = .5 - 2 * S;

    /**
     * A rack cell's frame: its facing (a horizontal face in {@link LineGeometry#STEPS} order), its shape ({@code corner}:
     * {@link #STRAIGHT} to {@link #TEE}), its drawn steel bits ({@link #STEEL}), and its beams' top in 1/32 m steps:
     * {@link #BEAM_TOP}, or lower beneath a line too large to rest there ({@link #beam}).
     */
    public record Rack(int facing, int corner, int steel, int beam) {
        public Rack {
            if (facing < 2 || facing > 5 || corner < STRAIGHT || corner > TEE || (steel & ~STEEL) != 0 || beam < 2 || beam > BEAM_TOP)
                throw new IllegalArgumentException("Invalid rack frame");
        }

        /** A cell's lane geometry alone: its facing and shape. */
        public Rack(int facing, int corner) {
            this(facing, corner, 0, BEAM_TOP);
        }

        /** The bents, a bit per face plane: 1 the back's, 2 the front's. */
        public int bents() {
            return steel & 3;
        }

        public boolean stacked() {
            return (steel & STACKED) != 0;
        }

        /** The sides a rack beside it joins, a bit each: 1 left, 2 right (no column or side member there, the beams run through). */
        public int sides() {
            return steel >> 3 & 3;
        }

        public boolean grounded() {
            return (steel & GROUNDED) != 0;
        }

        /** The bents that are whole, a bit per face plane as {@link #bents}: at a run's end, or against a junction. */
        public int ends() {
            return steel >> 6 & 3;
        }

        public boolean rising() {
            return (steel & RISING) != 0;
        }

        public int front() {
            return facing;
        }

        public int back() {
            return facing ^ 1;
        }

        public int left() {
            return ccw(facing);
        }

        public int right() {
            return cw(facing);
        }

        public boolean cornered() {
            return corner >= BACK_LEFT && corner <= FRONT_RIGHT;
        }

        public boolean tee() {
            return corner == TEE;
        }

        /** A corner or a tee: lanes turn in it, and the straight racks next to it stand a whole bent against it. */
        public boolean junction() {
            return corner != STRAIGHT;
        }

        /** A corner's end face, where its lanes run in from the run. */
        public int end() {
            return corner <= BACK_RIGHT ? back() : front();
        }

        /** A corner's side face, where its lanes turn out to the next run. */
        public int side() {
            return corner == BACK_LEFT || corner == FRONT_LEFT ? left() : right();
        }

        /** Canonical face {@link #FRONT} to {@link #BOTTOM} of block face {@code face}. */
        int canonical(int face) {
            if (face == 1) return TOP;
            if (face == 0) return BOTTOM;
            return face == front() ? FRONT : face == back() ? BACK : face == left() ? LEFT : RIGHT;
        }

        /** The block coordinates of canonical point (u, y, w). */
        public double[] world(double u, double y, double w) {
            return switch (facing) {
                case 2 -> new double[]{u, y, 1 - w};
                case 3 -> new double[]{1 - u, y, w};
                case 4 -> new double[]{1 - w, y, 1 - u};
                default -> new double[]{w, y, u};
            };
        }
    }

    /**
     * A lane as drawn: its option, width in positions (1 for a crossing), its arms (the two faces its pipe runs to, a bit
     * per face), those a line next door meets (welded there, capped elsewhere), and its outside size code.
     */
    public record Lane(int option, int width, int arms, int joined, int outside) {
        public Lane {
            if (!takes(option, width) || Integer.bitCount(arms) != 2 || (arms & ~63) != 0 || (joined & ~arms) != 0)
                throw new IllegalArgumentException("Invalid rack lane " + option + "+" + width);
        }

        public double diameter() {
            return PipeSizes.outsideOf(outside);
        }
    }

    private RackGeometry() {
    }

    // ---- options and sizes -----------------------------------------------------------------------------------------

    public static int option(int position, int level) {
        return position + 4 * level;
    }

    public static int crossing(int level) {
        return 11 + level;
    }

    public static boolean tier(int option) {
        return option < 12;
    }

    /** A tier option's first position. */
    public static int position(int option) {
        return option % 4;
    }

    /** An option's level: a tier lane's side arm's, or a crossing's. */
    public static int level(int option) {
        return option < 12 ? option / 4 : option - 11;
    }

    /** The positions a line of outside diameter {@code outside} takes: (OD + 1/64) / (3/16), rounded up; over {@link #MAX_WIDTH} it is not laid on racks. */
    public static int width(double outside) {
        return (int) Math.ceil((outside + CLEARANCE) / PITCH - EPS);
    }

    /** The largest outside diameter a lane of {@code width} positions carries. */
    public static double largest(int width) {
        return width * PITCH - CLEARANCE;
    }

    /** A lane's centre across the run: the mean of its positions' centres. */
    public static double centre(int position, int width) {
        return FIRST + PITCH * (position + (width - 1) / 2.0);
    }

    /**
     * A tier line's centre height in 1/32 m steps: resting on the beam, its radius over the beam's top rounded up to the
     * grid (support P2); a line whose top would then come closer than the clearance to the block's top sits at mid-height
     * instead, on a beam lowered just beneath it (as support P1; owner, 2026-10-26).
     */
    public static int height(double outside) {
        int resting = (int) Math.ceil(BEAM_TOP + outside / 2 / S - EPS);
        return resting * S + outside / 2 + CLEARANCE <= 1 + EPS ? resting : 16;
    }

    /** The beams' top in a cell with {@code lanes}: {@link #BEAM_TOP}, or just beneath the lowest tier line where one sits lower. */
    public static int beam(List<Lane> lanes) {
        int top = BEAM_TOP;
        for (var lane : lanes) if (tier(lane.option)) top = Math.min(top, (int) Math.floor(bottom(lane.diameter()) / S + EPS));
        return Math.max(2, top);
    }

    /** The top of a tier line. */
    static double top(double outside) {
        return height(outside) * S + outside / 2;
    }

    /** The bottom of a tier line. */
    static double bottom(double outside) {
        return height(outside) * S - outside / 2;
    }

    /** Whether a tier line of outside {@code below} passes under a line of outside {@code above} at the upper level, with the clearance. */
    public static boolean clears(double below, double above) {
        return top(below) + CLEARANCE <= UPPER * S - above / 2 + EPS;
    }

    /** Whether a line of outside {@code below} at the lower level passes under a tier line of outside {@code above}, with the clearance. */
    public static boolean clearsUnder(double above, double below) {
        return bottom(above) + EPS >= LOWER * S + below / 2 + CLEARANCE;
    }

    static int cw(int face) {
        return switch (face) {
            case 2 -> 5;
            case 5 -> 3;
            case 3 -> 4;
            case 4 -> 2;
            default -> throw new IllegalArgumentException("Not a horizontal face " + face);
        };
    }

    static int ccw(int face) {
        return cw(cw(cw(face)));
    }

    // ---- groups ----------------------------------------------------------------------------------------------------

    /** A rack cell's neighbours, as its shape and its drawn steel see them. */
    public interface Neighbours {
        /** The rack beyond face {@code face} (its facing and corner), or null where none stands. */
        Rack rack(int face);

        /** Whether what stands beyond {@code face} is known: false in an unloaded chunk, where the steel keeps what it drew. */
        default boolean known(int face) {
            return true;
        }
    }

    /**
     * The shape a rack placed with the player looking {@code look} takes (RACK_JUNCTION_PLAN J2), decided once: it never
     * changes after (J1). It faces the look (owner 2026-10-26: "Placed rack direction should be strictly based on player
     * facing"); its shape comes from the racks whose runs point into the cell, its feeds (a rack whose lanes run through
     * its face towards the cell), and the look:
     * <ul>
     *   <li>no feed: straight, whatever stands beside it;</li>
     *   <li>one: straight where it lies in line with the look, else a corner from it towards the look;</li>
     *   <li>two in line: straight between them where the look runs along them, else a tee whose branch opens towards
     *   the look;</li>
     *   <li>two at a corner: the corner joining them;</li>
     *   <li>three: the tee joining them, which faces its branch, the feed with none across from it;</li>
     *   <li>four: straight, as runs cross at two heights, one stacked on the other (X1).</li>
     * </ul>
     * Sneaking places a plain straight rack. A rack beside a run, its side towards the cell, never feeds it, so racks side
     * by side make no junctions.
     */
    public static Rack placed(Neighbours around, int look, boolean sneaking) {
        if (sneaking) return new Rack(look, STRAIGHT);
        int feeds = 0;
        for (int face = 2; face < 6; face++) {
            var neighbour = around.rack(face);
            if (neighbour != null && along(neighbour, face ^ 1)) feeds |= 1 << face;
        }
        int first = Integer.numberOfTrailingZeros(feeds), last = 31 - Integer.numberOfLeadingZeros(feeds);
        return switch (Integer.bitCount(feeds)) {
            case 1 -> LineGeometry.axis(first) == LineGeometry.axis(look) ? new Rack(look, STRAIGHT) : joining(look, look, first);
            case 2 -> last != (first ^ 1) ? joining(look, first, last) : LineGeometry.axis(look) != LineGeometry.axis(first) ? new Rack(look, TEE) : new Rack(look, STRAIGHT);
            case 3 -> {
                int branch = -1;
                for (int face = 2; face < 6; face++) if ((feeds >> face & 1) != 0 && (feeds >> (face ^ 1) & 1) == 0) branch = face;
                yield new Rack(branch, TEE);
            }
            default -> new Rack(look, STRAIGHT);
        };
    }

    /** The corner facing {@code facing} that joins faces {@code a} and {@code b}: one on its run's axis (its end), one across (its side). */
    private static Rack joining(int facing, int a, int b) {
        int end = LineGeometry.axis(a) == LineGeometry.axis(facing) ? a : b, side = end == a ? b : a;
        boolean right = side == cw(facing);
        return new Rack(facing, end == facing ? (right ? FRONT_RIGHT : FRONT_LEFT) : (right ? BACK_RIGHT : BACK_LEFT));
    }

    /**
     * The drawn steel bits ({@link #STEEL}) of {@code rack} at {@code along} (its coordinate along its run); a bit whose
     * neighbour is not known keeps its value in {@code previous}. A straight cell stands a bent every third block face
     * along its run, half in each cell's face plane there; a whole bent at each end where no straight rack continues its
     * run (nothing, a junction, or a rack across); its columns run on up under a rack; a side with a straight rack of its
     * run's axis beside it joins it. Every cell: pedestals where no rack stands under it; side braces alternating by block.
     */
    public static int steel(Neighbours around, Rack rack, int along, int previous) {
        int out = bit(around, 1, STACKED, previous, r -> r != null) | bit(around, 0, GROUNDED, previous, r -> r == null);
        if (Math.floorMod(along, 2) == 0) out |= RISING;
        if (rack.junction()) return out;
        // The bent face is the low face of the block at along % 3 == 0, the high face of the block before it.
        boolean up = rack.facing == 3 || rack.facing == 5;
        if (Math.floorMod(along, 3) == 0) out |= up ? BENT_BACK : BENT_FRONT;
        if (Math.floorMod(along, 3) == 2) out |= up ? BENT_FRONT : BENT_BACK;
        Predicate<Rack> end = r -> r == null || r.junction() || LineGeometry.axis(r.facing) != LineGeometry.axis(rack.facing);
        out |= bit(around, rack.back(), BENT_BACK | END_BACK, previous, end);
        out |= bit(around, rack.front(), BENT_FRONT | END_FRONT, previous, end);
        Predicate<Rack> beside = r -> r != null && !r.junction() && LineGeometry.axis(r.facing) == LineGeometry.axis(rack.facing);
        out |= bit(around, rack.left(), LEFT_JOINED, previous, beside);
        out |= bit(around, rack.right(), RIGHT_JOINED, previous, beside);
        return out;
    }

    private static int bit(Neighbours around, int face, int mask, int previous, Predicate<Rack> test) {
        if (!around.known(face)) return previous & mask;
        return test.test(around.rack(face)) ? mask : 0;
    }

    /** A frame from its lane geometry and the steel bits of {@link #steel}, its beams at {@code beam}. */
    public static Rack framed(Rack rack, int steel, int beam) {
        return new Rack(rack.facing, rack.corner, steel & STEEL, beam);
    }

    // ---- lanes -----------------------------------------------------------------------------------------------------

    /**
     * Whether a lane of {@code option}, {@code width} and {@code arms} suits a cell ({@link #takes(Rack, int, int)}): a
     * crossing runs side to side across a straight cell; a corner's lane runs from its end face to its side; a tee's
     * along its leg ({@link #legFaces}); any other tier lane has an end arm and runs on to the other end, up, down, or out
     * of a side.
     */
    public static boolean valid(Rack rack, int option, int width, int arms) {
        if (!takes(rack, option, width) || Integer.bitCount(arms) != 2 || (arms & ~63) != 0) return false;
        if (rack.tee()) return arms == legFaces(rack, option);
        if (rack.cornered()) return arms == (1 << rack.end() | 1 << rack.side());
        int a = rack.canonical(Integer.numberOfTrailingZeros(arms)), b = rack.canonical(31 - Integer.numberOfLeadingZeros(arms));
        if (!tier(option)) return side(a) && side(b);
        boolean endA = a == FRONT || a == BACK, endB = b == FRONT || b == BACK;
        if (!endA && !endB) return false;
        int other = endA ? b : a;
        return side(other) || level(option) == TIER;
    }

    /** Whether {@code option} takes a line {@code width} positions wide in some cell: a tier lane within the tier, a crossing one position wide. */
    public static boolean takes(int option, int width) {
        if (option < 0 || option >= OPTIONS || width < 1 || width > MAX_WIDTH) return false;
        return tier(option) ? position(option) + width <= POSITIONS : width == 1;
    }

    /**
     * Whether a cell of {@code rack}'s shape takes {@code option} for a line {@code width} positions wide: in a straight
     * cell a lane leaves above or below the tier only one position wide; a corner's lanes stay on the tier; a tee's lie on
     * its three legs, any width.
     */
    static boolean takes(Rack rack, int option, int width) {
        if (!takes(option, width)) return false;
        if (rack.tee()) return tier(option);
        if (rack.cornered()) return tier(option) && level(option) == TIER;
        return !tier(option) || level(option) == TIER || width == 1;
    }

    private static boolean side(int canonical) {
        return canonical == LEFT || canonical == RIGHT;
    }

    /** A lane's first arm: its end arm for a tier lane (the back of a straight one), the left one for a crossing; a tee's lane's on its leg. */
    static int first(Rack rack, Lane lane) {
        if (rack.tee()) {
            var leg = leg(rack, lane);
            return first(leg.rack, leg.lane);
        }
        int a = Integer.numberOfTrailingZeros(lane.arms), b = 31 - Integer.numberOfLeadingZeros(lane.arms);
        int ca = rack.canonical(a), cb = rack.canonical(b);
        if (!tier(lane.option)) return ca == LEFT ? a : b;
        if (ca == BACK || cb == BACK) return ca == BACK ? a : b;
        return ca == FRONT ? a : b;
    }

    static int second(Rack rack, Lane lane) {
        return Integer.numberOfTrailingZeros(lane.arms & ~(1 << first(rack, lane)));
    }

    /** The tier positions a lane takes, a bit each: its own, none for a crossing. */
    static int own(Lane lane) {
        return tier(lane.option) ? ((1 << lane.width) - 1) << position(lane.option) : 0;
    }

    /** Whether a lane leaves through a side face at its tier's height. */
    private static boolean flat(Rack rack, Lane lane) {
        if (!tier(lane.option) || rack.junction() || level(lane.option) != TIER) return false;
        for (int face = 2; face < 6; face++) if ((lane.arms >> face & 1) != 0 && side(rack.canonical(face))) return true;
        return false;
    }

    /** The tier positions a lane's flat side arm passes over: those between the lane and that side. */
    static int shadow(Rack rack, Lane lane) {
        if (!tier(lane.option) || rack.junction() || level(lane.option) != TIER) return 0;
        int out = 0, p = position(lane.option);
        for (int face = 0; face < 6; face++) {
            if ((lane.arms >> face & 1) == 0) continue;
            int c = rack.canonical(face);
            if (c == LEFT) out |= (1 << p) - 1;
            else if (c == RIGHT) out |= ((1 << POSITIONS) - 1) & ~((1 << (p + lane.width)) - 1);
        }
        return out;
    }

    /** The tier positions a cell's lanes take or pass over flat, a bit each: no new lane may take them; in a tee, the branch positions its turning lanes take. */
    public static int taken(Rack rack, List<Lane> lanes) {
        int out = 0;
        for (var lane : lanes) out |= rack.tee() ? level(lane.option) == RUN ? 0 : own(lane) : own(lane) | shadow(rack, lane);
        return out;
    }

    /** The levels a lane uses, a bit each (1 upper, 2 lower): a riser through the top or bottom face, a side arm over or under the tier, a crossing. */
    static int levels(Rack rack, Lane lane) {
        int level = level(lane.option);
        if (!tier(lane.option) || level != TIER) return level == ABOVE ? 1 : 2;
        int out = 0;
        if ((lane.arms >> 1 & 1) != 0) out |= 1;
        if ((lane.arms & 1) != 0) out |= 2;
        return out;
    }

    /** Whether a lane takes the upper level over the tier, where every tier line of its cell must pass under it. */
    private static boolean over(Lane lane) {
        return level(lane.option) == ABOVE;
    }

    /** Whether a lane takes the lower level under the tier, where every tier line of its cell must pass over it. */
    private static boolean under(Lane lane) {
        return level(lane.option) == BELOW;
    }

    /** Whether tier lane {@code tier} clears a lane at a crossing level beside it in one cell (true for any other pair). */
    private static boolean apart(Lane tier, Lane crossing) {
        if (!tier(tier.option) || tier == crossing) return true;
        return (!over(crossing) || clears(tier.diameter(), crossing.diameter())) && (!under(crossing) || clearsUnder(tier.diameter(), crossing.diameter()));
    }

    /**
     * Whether {@code lane} fits a cell beside {@code lanes}: it suits the cell; it takes no position another lane takes or
     * passes over flat, nor passes over one flat; it shares no level; and every tier line of the cell clears any line at
     * a crossing level by the clearance: at the upper level lines up to about 10" pass under, at the lower every line
     * resting on the beam passes over (a line on a lowered beam closes it). A tee's lanes keep to their legs ({@link #room}).
     */
    public static boolean fits(Rack rack, List<Lane> lanes, Lane lane) {
        if (!valid(rack, lane.option, lane.width, lane.arms)) return false;
        if (rack.tee()) return room(lanes, lane.option, lane.width);
        int own = own(lane), shadow = shadow(rack, lane), levels = levels(rack, lane);
        // A flat side arm passes over the stringer at the side: only a line resting on the beam clears it.
        if (flat(rack, lane) && bottom(lane.diameter()) < BEAM_TOP * S - EPS) return false;
        for (var other : lanes) {
            if ((own & (own(other) | shadow(rack, other))) != 0 || (shadow & own(other)) != 0 || (levels & levels(rack, other)) != 0) return false;
            if (!apart(other, lane) || !apart(lane, other)) return false;
        }
        return true;
    }

    /**
     * Whether a line of {@code outside} may take {@code option} beside {@code lanes} before its arms are known: its
     * positions free (a tier lane), or its level free and clear over the tier (a crossing).
     */
    public static boolean open(Rack rack, List<Lane> lanes, int option, int width, double outside) {
        if (!takes(rack, option, width)) return false;
        if (rack.tee()) return room(lanes, option, width);
        if (tier(option)) {
            int own = ((1 << width) - 1) << position(option);
            for (var other : lanes) {
                if ((own & (own(other) | shadow(rack, other))) != 0) return false;
                if (over(other) && !clears(outside, other.diameter()) || under(other) && !clearsUnder(outside, other.diameter())) return false;
            }
            return true;
        }
        int level = level(option) == ABOVE ? 1 : 2;
        for (var other : lanes) {
            if ((levels(rack, other) & level) != 0) return false;
            if (tier(other.option) && (level == 1 ? !clears(other.diameter(), outside) : !clearsUnder(other.diameter(), outside))) return false;
        }
        return true;
    }

    /** The faces a cell's lanes run through along its runs, a bit each: a straight cell's ends, a corner's end and side, a tee's run faces and its branch. */
    public static int runs(Rack rack) {
        if (rack.tee()) return 1 << rack.front() | 1 << rack.left() | 1 << rack.right();
        if (rack.cornered()) return 1 << rack.end() | 1 << rack.side();
        return 1 << rack.front() | 1 << rack.back();
    }

    /** Whether a line in this cell may step through {@code face} along a run ({@link #runs}). */
    public static boolean along(Rack rack, int face) {
        return (runs(rack) >> face & 1) != 0;
    }

    // ---- aiming ----------------------------------------------------------------------------------------------------

    /**
     * A slot aimed on a rack's tier: the lane's option, the run face the line passes through to the rest of its route
     * (where it leaves the rack at a first click, or arrives at a second), the run face its lane ends open at, and whether
     * a line fits there beside the cell's lanes.
     */
    public record Aim(int option, int through, int open, boolean fits) {
    }

    /**
     * The slot a player aims at on a rack cell (owner 2026-10-26, "Aim on the tier"), {@code point} being where the
     * crosshair crosses the tier, cell-local. The run face nearest the point in plan is where the line passes through to
     * its route; of the tier lanes from that face to another run face (on along the run; a corner's bend; a tee's run or
     * turn), the free one whose centre line passes nearest the point in plan, for a line of {@code outside}; with none
     * free, the nearest that suits the cell, not fitting. Exits over, under or out of the side stay the route's. Null
     * where the cell takes no lane that wide.
     */
    public static @Nullable Aim aim(Rack rack, List<Lane> lanes, double outside, double[] point) {
        int code = PipeSizes.code(outside), width = width(PipeSizes.outsideOf(code)), runs = runs(rack), through = -1;
        double most = -Double.MAX_VALUE;
        for (int face = 2; face < 6; face++) {
            var step = LineGeometry.STEPS[face];
            double toward = (point[0] - .5) * step[0] + (point[2] - .5) * step[2];
            if ((runs >> face & 1) != 0 && toward > most + EPS) {
                most = toward;
                through = face;
            }
        }
        Aim best = null;
        double nearest = Double.MAX_VALUE;
        for (int option = 0; option < OPTIONS; option++) {
            if (!tier(option) || !rack.tee() && level(option) != TIER) continue;
            for (int open = 2; open < 6; open++) {
                int arms = 1 << through | 1 << open;
                if (open == through || (runs >> open & 1) == 0 || !valid(rack, option, width, arms)) continue;
                var lane = new Lane(option, width, arms, 0, code);
                boolean fits = open(rack, lanes, option, width, PipeSizes.outsideOf(code)) && fits(rack, lanes, lane);
                double distance = planDistance(path(rack, lane), point);
                if (best == null || fits && !best.fits || fits == best.fits && distance < nearest - EPS) {
                    best = new Aim(option, through, open, fits);
                    nearest = distance;
                }
            }
        }
        return best;
    }

    /** The distance in plan (x and z) from {@code point} to the centre line {@code path}. */
    private static double planDistance(List<double[]> path, double[] point) {
        double best = Double.MAX_VALUE;
        for (int k = 0; k + 1 < path.size(); k++) {
            double[] a = path.get(k), b = path.get(k + 1);
            double dx = b[0] - a[0], dz = b[2] - a[2], length = dx * dx + dz * dz;
            double t = length < EPS ? 0 : Math.max(0, Math.min(1, ((point[0] - a[0]) * dx + (point[2] - a[2]) * dz) / length));
            double ex = a[0] + t * dx - point[0], ez = a[2] + t * dz - point[2];
            best = Math.min(best, Math.sqrt(ex * ex + ez * ez));
        }
        return best;
    }

    // ---- tees ------------------------------------------------------------------------------------------------------

    /** The faces a tee's lane of {@code option} runs between, a bit each: its run's two faces, or the branch and the side it turns to. */
    static int legFaces(Rack tee, int option) {
        return switch (level(option)) {
            case RUN -> 1 << tee.left() | 1 << tee.right();
            case TURN_LEFT -> 1 << tee.front() | 1 << tee.left();
            default -> 1 << tee.front() | 1 << tee.right();
        };
    }

    /**
     * The frame a tee's lane of {@code option} lies in, as a cell of its own (RACK_JUNCTION_PLAN J3: two corners back to
     * back, with the run behind them): the run, a straight cell facing the tee's left, its positions counted from the
     * tee's back; or the corner from the branch turning left or right, its positions those of the branch.
     */
    static Rack legRack(Rack tee, int option) {
        return switch (level(option)) {
            case RUN -> new Rack(tee.left(), STRAIGHT);
            case TURN_LEFT -> new Rack(tee.facing, FRONT_LEFT);
            default -> new Rack(tee.facing, FRONT_RIGHT);
        };
    }

    /** A lane and the frame it lies in: a tee's lane on its leg, at its position there; any other cell's as it is. */
    record Leg(Rack rack, Lane lane) {
    }

    static Leg leg(Rack rack, Lane lane) {
        if (!rack.tee()) return new Leg(rack, lane);
        return new Leg(legRack(rack, lane.option), new Lane(position(lane.option), lane.width, lane.arms, lane.joined, lane.outside));
    }

    /**
     * Whether a tee has room for a lane of {@code option}, {@code width} positions wide, beside {@code lanes}. A lane on
     * the run takes run positions; a turning lane takes branch positions and lands on the run positions nearest the
     * branch ({@link #landing}), where a left-turning and a right-turning lane may share a run position, one on each half.
     * No two lanes share a position; the left-turning lanes stay left of the right-turning ones, so their bends cannot
     * cross; and the run's lanes pass behind every turning lane's landing.
     */
    private static boolean room(List<Lane> lanes, int option, int width) {
        for (var other : lanes) if (!teeApart(option, width, other.option, other.width)) return false;
        return true;
    }

    private static boolean teeApart(int a, int wa, int b, int wb) {
        int la = level(a), lb = level(b), pa = position(a), pb = position(b);
        if ((la == RUN) != (lb == RUN)) return la == RUN ? pa + wa <= landing(b, wb) : pb + wb <= landing(a, wa);
        if (pa < pb + wb && pb < pa + wa) return false;
        if (la == lb) return true;
        return la == TURN_LEFT ? pa + wa <= pb : pb + wb <= pa;
    }

    /** The first run position a tee's turning lane lands on: a left-turning lane's positions mirror across the branch, a right-turning lane's keep their number. */
    static int landing(int option, int width) {
        return level(option) == TURN_LEFT ? POSITIONS - position(option) - width : position(option);
    }

    // ---- points ----------------------------------------------------------------------------------------------------

    /**
     * The canonical point (u, y, w) where a lane of {@code option} meets face {@code face} of a straight cell or a
     * corner, or null where the option has no arm there or the cell does not take it ({@link #takes(Rack, int, int)}).
     * {@code outside} sets a tier line's height.
     */
    private static double[] canonicalPoint(Rack rack, int option, int width, double outside, int face) {
        if (!takes(rack, option, width)) return null;
        int c = rack.canonical(face);
        double mid = .5;
        if (!tier(option)) return !side(c) ? null : new double[]{c == LEFT ? 0 : 1, (option == crossing(ABOVE) ? UPPER : LOWER) * S, mid};
        int level = level(option);
        double u = centre(position(option), width), h = height(outside) * S;
        if (rack.cornered()) {
            if (face == rack.end()) return new double[]{u, h, c == BACK ? 0 : 1};
            if (face != rack.side()) return null;
            double d = c == RIGHT ? 1 - u : u;
            return new double[]{c == RIGHT ? 1 : 0, h, rack.canonical(rack.end()) == BACK ? d : 1 - d};
        }
        return switch (c) {
            case FRONT -> new double[]{u, h, 1};
            case BACK -> new double[]{u, h, 0};
            case TOP -> level == TIER ? new double[]{u, 1, mid} : null;
            case BOTTOM -> level == TIER ? new double[]{u, 0, mid} : null;
            default -> new double[]{c == LEFT ? 0 : 1, (level == TIER ? h : level == ABOVE ? UPPER * S : LOWER * S), mid};
        };
    }

    /**
     * Where a lane of {@code option} meets face {@code face}: its offsets from the face's centre in 1/32 m steps along
     * the face's in-plane axes, packed as ten bits as {@link LineGeometry.Key#offsets} packs them; -1 where it has no arm
     * there. Two lanes in cells side by side meet where their points on the shared face are equal.
     */
    public static int point(Rack rack, int option, int width, double outside, int face) {
        var world = worldPoint(rack, option, width, outside, face);
        if (world == null) return -1;
        int axis = LineGeometry.axis(face);
        int u = (int) Math.round((world[(axis + 1) % 3] - .5) / S), v = (int) Math.round((world[(axis + 2) % 3] - .5) / S);
        return (int) (LineGeometry.Key.offsets(0, u, v));
    }

    /** A point's two offsets, unpacked: along the face's first and second in-plane axis. */
    public static int[] steps(int point) {
        int u = point & 31, v = point >> 5 & 31;
        return new int[]{u >= 16 ? u - 32 : u, v >= 16 ? v - 32 : v};
    }

    /** A lane's points on its arm faces, ten bits per face (as {@link LineGeometry.Key#offsets}): what its registration carries. */
    public static long points(Rack rack, Lane lane) {
        long out = 0;
        for (int face = 0; face < 6; face++)
            if ((lane.arms >> face & 1) != 0) out |= (long) point(rack, lane.option, lane.width, lane.diameter(), face) << 10 * face;
        return out;
    }

    /** A lane's point on face {@code face} in block coordinates. */
    public static double[] facePoint(Rack rack, Lane lane, int face) {
        return worldPoint(rack, lane.option, lane.width, lane.diameter(), face);
    }

    /** Where a lane of {@code option} meets face {@code face}, in block coordinates, or null where it has no arm there; a tee's lane meets it as its leg does. */
    private static double[] worldPoint(Rack rack, int option, int width, double outside, int face) {
        if (rack.tee())
            return takes(rack, option, width) && (legFaces(rack, option) >> face & 1) != 0 ? worldPoint(legRack(rack, option), position(option), width, outside, face) : null;
        var p = canonicalPoint(rack, option, width, outside, face);
        return p == null ? null : rack.world(p[0], p[1], p[2]);
    }

    // ---- centre lines ----------------------------------------------------------------------------------------------

    /**
     * A lane's centre line in block coordinates, from its first arm's face point to its second's through its corners: a
     * straight line, a corner's two legs meeting at its corner point (it is drawn as a bend about the inner corner), a
     * turn at mid-block up, down or out flat, or a rise or drop to a crossing level at mid-block and out sideways; a
     * tee's lane as its leg lays it.
     */
    public static List<double[]> path(Rack rack, Lane lane) {
        if (rack.tee()) {
            var leg = leg(rack, lane);
            return path(leg.rack, leg.lane);
        }
        int a = first(rack, lane), b = second(rack, lane);
        var pa = canonicalPoint(rack, lane.option, lane.width, lane.diameter(), a);
        var pb = canonicalPoint(rack, lane.option, lane.width, lane.diameter(), b);
        var points = new ArrayList<double[]>();
        points.add(pa);
        int cb = rack.canonical(b);
        if (tier(lane.option) && rack.cornered()) points.add(new double[]{pa[0], pa[1], pb[2]});
        else if (tier(lane.option) && cb != FRONT && cb != BACK) {
            points.add(new double[]{pa[0], pa[1], .5});
            // A side arm over or under the tier rises or drops at mid-block first.
            if (side(cb) && level(lane.option) != TIER) points.add(new double[]{pa[0], pb[1], .5});
        }
        points.add(pb);
        var out = new ArrayList<double[]>(points.size());
        for (var p : points) out.add(rack.world(p[0], p[1], p[2]));
        return out;
    }

    /** One lane's polygons, cell-local, with no spool flanges: the laying tool's ghost draws a lane it would lay. */
    public static List<Polygon> lane(Rack rack, Lane lane) {
        return lane(rack, lane, false);
    }

    /** One lane's polygons, cell-local, its spool flanges where {@code spool} says ({@link #spool}): the rack draws each lane apart for its paints. */
    public static List<Polygon> lane(Rack rack, Lane lane, boolean spool) {
        var m = new Mesh();
        lane(m, rack, lane, spool);
        return m.cell(0, 0, 0);
    }

    /**
     * Whether {@code lane} carries a spool's flange pair in this cell, {@code along} blocks along its run in the world:
     * a lane joined straight through a straight cell, every {@link LineGeometry#SPOOL} blocks as a pipe is, staggered
     * three blocks a position (owner 2026-10-26: flanges "staggered, off steel"), so that lanes side by side never
     * flange in one block; where the pair finds no room clear of the cell's steel ({@link #spoolAt}), the line runs on
     * welded to the next.
     */
    public static boolean spool(Rack rack, Lane lane, int along) {
        int arms = runs(rack);
        return !rack.junction() && tier(lane.option) && level(lane.option) == TIER && lane.arms == arms && lane.joined == arms
                && Math.floorMod(along - 3 * position(lane.option), LineGeometry.SPOOL) == 0 && spoolAt(rack, lane) >= 0;
    }

    /**
     * One lane: a straight pipe; a corner's bend about the inner corner, so the bends of a corner nest and keep their
     * order; or the bends of a turn at mid-block, as a transition pipe draws them. A tee's lane is drawn on its leg. A
     * lane is welded where a line next door continues it; where nothing does it ends in a blind flange, as a pipe does
     * (owner 2026-10-26: "Pipe on rack should have their flange rendered normally"), set in off the steel the cell draws
     * at that face ({@link #setback}); a corner's bend is capped there instead. A spool's flange pair stands where
     * {@link #spoolAt} finds it clear of the steel.
     */
    static void lane(Mesh m, Rack rack, Lane lane, boolean spool) {
        var leg = leg(rack, lane);
        var on = leg.rack;
        var drawn = leg.lane;
        double od = lane.diameter(), r = od / 2;
        int sides = PipeSizes.sides(od);
        int a = first(on, drawn), b = second(on, drawn);
        var path = new ArrayList<>(path(on, drawn));
        boolean openA = (drawn.joined >> a & 1) == 0, openB = (drawn.joined >> b & 1) == 0;
        double sa = openA ? setback(rack, od, a, path.getFirst()) : 0, sb = openB ? setback(rack, od, b, path.getLast()) : 0;
        var ea = end(openA, sa, od);
        var eb = end(openB, sb, od);
        sa = Math.max(0, sa);
        sb = Math.max(0, sb);
        double[] da = LineGeometry.step(a), db = LineGeometry.step(b);
        if (path.size() == 2) {
            // Each arm from where the pipe starts, measured from its face: the middle, or either side of the spool pair.
            double startA = .5, startB = .5;
            double at = spool ? spoolAt(rack, lane) : -1;
            if (at >= 0) {
                double fromA = a == (rack.rising() ? rack.back() : rack.front()) ? at : 1 - at;
                double reach = LineGeometry.spoolPair(m, new Frame(lerp(path.get(0), path.get(1), fromA), db, LineGeometry.ref(b)), od, sides);
                startA = fromA - reach;
                startB = 1 - fromA - reach;
            }
            LineGeometry.arm(m, plus(path.get(0), scale(da, -.5 - sa)), a, .5 + sa - startA, od, ea, LineGeometry.ref(a), sides, false);
            LineGeometry.arm(m, plus(path.get(1), scale(db, -.5 - sb)), b, .5 + sb - startB, od, eb, LineGeometry.ref(b), sides, false);
            return;
        }
        if (tier(drawn.option) && on.cornered()) {
            // Radius: the lane's distance from the inner corner, the same on both legs. Its bend meets both faces square,
            // so an open end cannot stand in off the junction's face beams: it is capped there. A flange set in would
            // tilt into the beam, and a tighter bend would close on the next lane out (6": 4.7/32 m apart, 6/32 m needed).
            var corner = path.get(1);
            double bend = Math.abs(dot(sub(path.get(0), corner), da));
            var origin = plus(corner, scale(plus(da, db), bend));
            var f = new Frame(origin, cross(db, da), neg(db));
            m.torus(f, bend, r, 0, Math.PI / 2, Math.max(3, (int) Math.ceil(Math.PI / 2 * bend / .08)), sides, Material.PIPE);
            if (openA) m.annulus(new Frame(corner, da, neg(db)), 0, r, bend, sides, 1, Material.PIPE);
            if (openB) m.annulus(new Frame(corner, db, neg(da)), 0, r, bend, sides, 1, Material.PIPE);
            return;
        }
        if (sa > 0) path.set(0, plus(path.getFirst(), scale(da, -sa)));
        if (sb > 0) path.set(path.size() - 1, plus(path.getLast(), scale(db, -sb)));
        LineGeometry.line(m, LineGeometry.fitted(path, ea, eb, od, BEND), a, b, od, sides);
    }

    /** A plate box at least this deep along a line runs beside it (a side channel), rather than across it. */
    private static final double ACROSS = 8 * S;
    /** Points a brace is checked at, along its length. */
    private static final int BAR_SAMPLES = 64;

    /** How far a flange's plate and the nuts behind it reach along its line from the plate's face, for a line of outside {@code od}. */
    private static double flangeReach(double od) {
        return PipeSizes.flangeThickness(od) + Math.max(1.2 * PipeSizes.boltDiameter(od), Mesh.MIN_NUT);
    }

    /**
     * How far in from face {@code face} an open end's blind flange stands, a line of outside {@code od} meeting the face
     * at {@code point}: its plate and the nuts behind it clear by {@link #CLEARANCE} of the steel the cell draws (a
     * bent's columns and beam, a light frame, a junction's beams) wherever its flange would meet it, stepping in past
     * each member it would reach (a column's outer flange, then its inner one); 0 where none is in the way. -1 where it
     * would stand past the middle of the cell, off its own half of the lane (a line of 21" or more in a stacked rack,
     * whose flange meets the side brace where it runs high): that end is capped at the face.
     */
    static double setback(Rack rack, double od, int face, double[] point) {
        double reach = flangeReach(od), x = clearOf(rack, face, point, PipeSizes.flange(od) / 2, 0, reach, 0);
        return x + reach <= .5 + EPS ? x : -1;
    }

    /** A lane's end: welded where a line next door goes on; else a blind flange where one stands clear ({@link #setback}), or a cap. */
    private static LineGeometry.End end(boolean open, double setback, double od) {
        return !open ? new LineGeometry.End(od, false, 0, 0, true) : setback >= 0 ? LineGeometry.End.flanged(od, false) : new LineGeometry.End(od, false, 0, 0, false);
    }

    /**
     * Where a spool's flange pair stands on a straight lane that carries one ({@link #spool}), as its distance in from
     * the face the cell's side braces leave low: the nearest place to that face where both plates and their nuts clear
     * the steel, as an open end's blind flange does; -1 where none does in the cell (a 26" or 28" line in a stacked
     * rack, whose flanges reach the side braces everywhere past a bent). Low there, the braces pass under the flanges.
     */
    static double spoolAt(Rack rack, Lane lane) {
        double od = lane.diameter(), reach = flangeReach(od);
        int low = rack.rising() ? rack.back() : rack.front();
        double[] point = low == first(rack, lane) ? path(rack, lane).getFirst() : path(rack, lane).getLast();
        double x = clearOf(rack, low, point, PipeSizes.flange(od) / 2, reach, reach, reach);
        return x + reach <= 1 + EPS ? x : -1;
    }

    /**
     * The least depth {@code x} in from face {@code face}, from {@code start} on, at which a disc of {@code radius} round
     * the line through {@code point} normal to the face, filling depths {@code [x - back, x + front]}, clears by
     * {@link #CLEARANCE} every member of the cell's steel that comes within {@code radius} of the line. Each member in
     * the way pushes the disc on past it.
     */
    private static double clearOf(Rack rack, int face, double[] point, double radius, double back, double front, double start) {
        int k = LineGeometry.axis(face);
        boolean high = (face & 1) != 0;
        var spans = new ArrayList<double[]>();
        for (var member : steelwork(rack, List.of()).drawn) {
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            if (member.bar()) {
                // A brace only over the stretch of it that comes that near; a square bar reaches its half diagonal.
                double grow = member.width / Math.sqrt(2), pad = Math.abs(member.b[k] - member.a[k]) / BAR_SAMPLES / 2;
                for (int n = 0; n <= BAR_SAMPLES; n++) {
                    double[] q = lerp(member.a, member.b, n / (double) BAR_SAMPLES);
                    if (inPlane(q, q, k, point) >= radius + grow) continue;
                    lo = Math.min(lo, q[k] - grow - pad);
                    hi = Math.max(hi, q[k] + grow + pad);
                }
            } else if (inPlane(member.a, member.b, k, point) < radius && Math.abs(member.b[k] - member.a[k]) <= ACROSS) {
                lo = Math.min(member.a[k], member.b[k]);
                hi = Math.max(member.a[k], member.b[k]);
            }
            if (lo <= hi) spans.add(high ? new double[]{1 - hi, 1 - lo} : new double[]{lo, hi});
        }
        double x = start;
        for (boolean moved = true; moved; ) {
            moved = false;
            for (var span : spans)
                if (span[0] < x + front + CLEARANCE - EPS && span[1] + CLEARANCE > x - back + EPS) {
                    x = span[1] + CLEARANCE + back;
                    moved = true;
                }
        }
        return x;
    }

    /** The distance from {@code point} to the box spanned by {@code a} and {@code b}, in the plane square to axis {@code k}. */
    private static double inPlane(double[] a, double[] b, int k, double[] point) {
        double gap = 0;
        for (int i = 0; i < 3; i++) {
            if (i == k) continue;
            double d = Math.max(0, Math.max(Math.min(a[i], b[i]) - point[i], point[i] - Math.max(a[i], b[i])));
            gap += d * d;
        }
        return Math.sqrt(gap);
    }

    /**
     * The boxes a lane fills, cell-local {lo x, lo y, lo z, hi x, hi y, hi z}, one per leg of its centre line (a corner's
     * legs meet at its corner point): its shape for collision and for picking the lane a player aims at.
     */
    public static List<double[]> boxes(Rack rack, Lane lane) {
        double r = lane.diameter() / 2;
        var path = path(rack, lane);
        var out = new ArrayList<double[]>();
        for (int k = 0; k + 1 < path.size(); k++) {
            double[] p = path.get(k), q = path.get(k + 1), box = new double[6];
            for (int i = 0; i < 3; i++) {
                box[i] = Math.max(0, Math.min(p[i], q[i]) - r);
                box[i + 3] = Math.min(1, Math.max(p[i], q[i]) + r);
            }
            out.add(box);
        }
        return out;
    }

    /** The lane a click hits and the arm the leg it hits leads to, by index in a rack's lane list and face; {@link #NONE} off every lane. */
    public record Pick(int lane, int face) {
    }

    public static final Pick NONE = new Pick(-1, -1);
    /** How far outside a leg box a hit point may lie and still pick it: a hit lands on the box's surface, give or take rounding. */
    public static final double PICK_TOLERANCE = 1e-3;

    /**
     * The lane a click at cell-local point {@code (x, y, z)} hits: the one with a leg box nearest the point, within
     * {@link #PICK_TOLERANCE}. Its first leg leads to its first arm, every later leg to its second.
     */
    public static Pick pick(Rack rack, List<Lane> lanes, double x, double y, double z) {
        var best = NONE;
        double nearest = PICK_TOLERANCE;
        for (int i = 0; i < lanes.size(); i++) {
            var boxes = boxes(rack, lanes.get(i));
            for (int k = 0; k < boxes.size(); k++) {
                var b = boxes.get(k);
                double outside = Math.max(Math.max(Math.max(b[0] - x, x - b[3]), Math.max(b[1] - y, y - b[4])), Math.max(b[2] - z, z - b[5]));
                if (outside <= nearest) {
                    nearest = outside;
                    best = new Pick(i, k == 0 ? first(rack, lanes.get(i)) : second(rack, lanes.get(i)));
                }
            }
        }
        return best;
    }

    // ---- steel -----------------------------------------------------------------------------------------------------

    /** A drawn member, block-local: a box from its low corner {@code a} to its high corner {@code b}, or a square bar {@code width} wide between them. */
    record Member(double[] a, double[] b, double width, Material material) {
        boolean bar() {
            return width > 0;
        }
    }

    /** A cell's steel: the members drawn, and the boxes {lo x, lo y, lo z, hi x, hi y, hi z} a player collides with and aims at (braces are drawn only). */
    record Steelwork(List<Member> drawn, List<double[]> solid) {
    }

    private static final double[] UP_AXIS = {0, 1, 0};

    /** The frame's polygons, cell-local; {@code lanes} leave out the braces a lane passes through. */
    public static List<Polygon> polygons(Rack rack, List<Lane> lanes) {
        var m = new Mesh();
        for (var member : steelwork(rack, lanes).drawn)
            if (member.bar()) m.bar(member.a, member.b, member.width, UP_AXIS, member.material);
            else m.box(member.a, member.b, ALL_FACES, member.material);
        return m.cell(0, 0, 0);
    }

    /** The boxes of a cell's steel a player collides with and aims at, block-local {lo x, lo y, lo z, hi x, hi y, hi z}. */
    public static List<double[]> members(Rack rack) {
        return steelwork(rack, List.of()).solid;
    }

    /**
     * A cell's steel (RACK_FRAME_PLAN parts F-h to F-plan at the sturdy sizes of section 3a; owner 2026-10-26), in 1/32 m.
     * A straight cell: at a bent, H columns 3.75/32 across on its outer sides (and a middle one where a rack beside it
     * joins on the right), an I beam 4/32 deep under the lines, knee braces, and under each column on the ground a
     * concrete pedestal and a base plate; a bent shared by two cells is half in each, 2/32 deep, a whole one 4/32. At the
     * cell's low face, where it has no bent, a light frame: posts on the outer sides and an angle under the lines. Along
     * each outer side a channel at the beam; in each outer side panel a diagonal and under the tier a plan brace,
     * alternating by block, each left out where a lane passes through it. A junction: H columns at its outer corners only
     * (a corner's three, a tee's two at its back), so that none stands inside a bend, and the straight racks next to it
     * hold its inner corners with their bents; an I beam under the lines on every face; pedestals on the ground. Steel over
     * the beam's top keeps within 3.75/32 of the sides, clear of every tier line; columns run to the beam, or on up to the
     * top under a stacked rack.
     */
    static Steelwork steelwork(Rack rack, List<Lane> lanes) {
        var frame = new Framing(rack);
        if (rack.junction()) frame.junction();
        else frame.straight(lanes);
        return new Steelwork(List.copyOf(frame.drawn), List.copyOf(frame.solid));
    }

    /** Builds a cell's steel in canonical 1/32 m. */
    private static final class Framing {
        /** A column across the run, a flange's thickness; a pedestal's width and height, and its base plate's thickness. */
        static final double COLUMN = 3.75, FLANGE = .9, FOOT = 5.5, PEDESTAL = 4, PLATE = .6;
        private final Rack rack;
        private final List<Member> drawn = new ArrayList<>();
        private final List<double[]> solid = new ArrayList<>();
        private final double beam, top, bottom, base;
        private final boolean feet;

        Framing(Rack rack) {
            this.rack = rack;
            beam = rack.beam;
            top = rack.stacked() ? 32 : beam;
            bottom = Math.max(0, beam - 4);
            // A lowered beam (under a 24" to 28" line) lies at the ground: its columns stand on the ground, without pedestals.
            feet = rack.grounded() && bottom >= PEDESTAL + PLATE;
            base = feet ? PEDESTAL + PLATE : 0;
        }

        void straight(List<Lane> lanes) {
            boolean left = (rack.sides() & 1) == 0, right = (rack.sides() & 2) == 0;
            int low = rack.facing == 3 || rack.facing == 5 ? 0 : 1, used = 0;
            boolean plain = true;
            for (var lane : lanes) {
                used |= lane.arms;
                plain &= tier(lane.option) && level(lane.option) == TIER && (lane.arms & 3) == 0;
            }
            for (int plane = 0; plane < 2; plane++) {
                boolean end = (rack.ends() >> plane & 1) != 0;
                double w0 = plane == 0 ? 0 : 30;
                // A shared bent's half lies in this cell's face plane, the other half in the next cell's; an end's is whole.
                double c0 = plane == 0 ? 0 : end ? 28 : 30, c1 = plane == 0 ? (end ? 4 : 2) : 32;
                if ((rack.bents() >> plane & 1) != 0) {
                    for (int side = 0; side < 2; side++) {
                        boolean outer = side == 0 ? left : right, middle = side == 1 && !right;
                        if (!outer && !middle) continue;
                        double u = side == 0 ? 0 : 32 - COLUMN;
                        column(u, c0, c1, end ? -1 : plane);
                        if (feet) {
                            double p0 = plane == 0 ? 0 : 32 - (end ? FOOT : FOOT / 2), p1 = plane == 0 ? (end ? FOOT : FOOT / 2) : 32;
                            member(side == 0 ? 0 : 32 - FOOT, side == 0 ? FOOT : 32, 0, PEDESTAL, p0, p1, Material.CONCRETE);
                            member(side == 0 ? 0 : 27.5, side == 0 ? 4.5 : 32, PEDESTAL, PEDESTAL + PLATE, Math.max(0, c0 - .5), Math.min(32, c1 + .5), Material.STEELWORK);
                        }
                        if (outer && bottom - 7 > base + 1)
                            bar(new double[]{side == 0 ? COLUMN : 32 - COLUMN, bottom - 7, w0 + 1}, new double[]{side == 0 ? 11 : 21, bottom, w0 + 1}, 1.4);
                    }
                    girder(left ? COLUMN : 0, right ? 32 - COLUMN : 32, w0);
                } else if (plane == low) {
                    // The light frame: posts on the outer sides, an angle under the lines with its leg down on the face.
                    if (left) member(.5, 3.25, 0, top, w0, w0 + 2, Material.STEELWORK);
                    if (right) member(28.75, 31.5, 0, top, w0, w0 + 2, Material.STEELWORK);
                    double u0 = left ? .5 : 0, u1 = right ? 31.5 : 32;
                    box(u0, u1, beam - .8, beam, w0, w0 + 2);
                    box(u0, u1, beam - 2.5, beam - .8, plane == 0 ? 0 : 31.2, plane == 0 ? .8 : 32);
                    solid(u0, u1, beam - 2.5, beam, w0, w0 + 2);
                }
            }
            // Channels along the outer sides at the beam, the web outside and the flanges in. The flanges stop 2.75/32
            // in, so a 6" line's flange at the outer slot clears them (0.30 m across, its centre 7/32 in).
            for (int side = 0; side < 2; side++) {
                if (!(side == 0 ? left : right)) continue;
                double web = side == 0 ? .25 : 31, flange = side == 0 ? 1 : 29.25;
                box(web, web + .75, beam - 3, beam, 0, 32);
                box(flange, flange + 1.75, beam - .7, beam, 0, 32);
                box(flange, flange + 1.75, beam - 3, beam - 2.3, 0, 32);
                solid(side == 0 ? .25 : 29.25, side == 0 ? 2.75 : 31.75, beam - 3, beam, 0, 32);
            }
            double rise = rack.stacked() ? 32 : beam - 3;
            boolean rising = rack.rising();
            for (int side = 0; side < 2 && rise > 6; side++) {
                if (!(side == 0 ? left : right) || (used >> (side == 0 ? rack.left() : rack.right()) & 1) != 0) continue;
                double u = side == 0 ? 1.9 : 30.1;
                bar(new double[]{u, rising ? 0 : rise, 1}, new double[]{u, rising ? rise : 0, 31}, 1.4);
            }
            if (plain && bottom >= 2)
                bar(new double[]{rising ? 4.5 : 27.5, bottom - .7, 2.5}, new double[]{rising ? 27.5 : 4.5, bottom - .7, 29.5}, 1.1);
        }

        void junction() {
            // The inner corners, canonical (u, w), stand inside the bends: a corner's between its end and side, a tee's two at its branch.
            double innerU = rack.cornered() && rack.canonical(rack.side()) == RIGHT ? 32 : 0, innerW = rack.cornered() && rack.canonical(rack.end()) == BACK ? 0 : 32;
            for (double u : new double[]{0, 32 - COLUMN})
                for (double c0 : new double[]{0, 28}) {
                    double cu = u == 0 ? 0 : 32, cw = c0 == 0 ? 0 : 32;
                    if (rack.tee() ? cw == 32 : cu == innerU && cw == innerW) continue;
                    column(u, c0, c0 + 4, -1);
                    if (feet) {
                        member(u == 0 ? 0 : 32 - FOOT, u == 0 ? FOOT : 32, 0, PEDESTAL, c0 == 0 ? 0 : 32 - FOOT, c0 == 0 ? FOOT : 32, Material.CONCRETE);
                        member(u == 0 ? 0 : 27.5, u == 0 ? 4.5 : 32, PEDESTAL, PEDESTAL + PLATE, c0 == 0 ? 0 : 27.5, c0 == 0 ? 4.5 : 32, Material.STEELWORK);
                    }
                }
            for (double w0 : new double[]{0, 30}) girder(0, 32, w0);
            // On the two sides, I beams between the face beams, 2.5/32 wide so an outer lane's flange clears them.
            for (double u : new double[]{.25, 29.25}) {
                box(u, u + 2.5, beam - .8, beam, 2, 30);
                box(u + .875, u + 1.625, bottom + .8, beam - .8, 2, 30);
                box(u, u + 2.5, bottom, bottom + .8, 2, 30);
                solid(u, u + 2.5, bottom, beam, 2, 30);
            }
        }

        /**
         * An H column {@link #COLUMN} across from {@code u}, from w {@code c0} to {@code c1}, its flanges towards the
         * sides: its web on the block face where it is half of a shared bent ({@code plane} 0 or 1: the halves make one
         * web), at mid-depth where it is whole (-1). It stands on its base plate, or the ground, up to the frame's top.
         */
        private void column(double u, double c0, double c1, int plane) {
            box(u, u + FLANGE, base, top, c0, c1);
            box(u + COLUMN - FLANGE, u + COLUMN, base, top, c0, c1);
            double web0 = plane < 0 ? (c0 + c1) / 2 - .5 : plane == 0 ? c0 : c1 - .5;
            box(u + FLANGE, u + COLUMN - FLANGE, base, top, web0, web0 + (plane < 0 ? 1 : .5));
            solid(u, u + COLUMN, base, top, c0, c1);
        }

        /** An I beam 4/32 deep under the lines, from u {@code u0} to {@code u1}, in the face plane from w {@code w0}. */
        private void girder(double u0, double u1, double w0) {
            box(u0, u1, beam - .8, beam, w0, w0 + 2);
            box(u0, u1, bottom + .8, beam - .8, w0 + .6, w0 + 1.4);
            box(u0, u1, bottom, bottom + .8, w0, w0 + 2);
            solid(u0, u1, bottom, beam, w0, w0 + 2);
        }

        private void box(double u0, double u1, double y0, double y1, double w0, double w1) {
            box(u0, u1, y0, y1, w0, w1, Material.STEELWORK);
        }

        private void box(double u0, double u1, double y0, double y1, double w0, double w1, Material material) {
            var b = RackGeometry.box(rack, u0, u1, y0, y1, w0, w1);
            drawn.add(new Member(new double[]{b[0], b[1], b[2]}, new double[]{b[3], b[4], b[5]}, 0, material));
        }

        private void solid(double u0, double u1, double y0, double y1, double w0, double w1) {
            solid.add(RackGeometry.box(rack, u0, u1, y0, y1, w0, w1));
        }

        /** A member drawn and collided with. */
        private void member(double u0, double u1, double y0, double y1, double w0, double w1, Material material) {
            box(u0, u1, y0, y1, w0, w1, material);
            solid(u0, u1, y0, y1, w0, w1);
        }

        /** A square brace {@code width} wide between canonical points, drawn only. */
        private void bar(double[] a, double[] b, double width) {
            drawn.add(new Member(rack.world(a[0] * S, a[1] * S, a[2] * S), rack.world(b[0] * S, b[1] * S, b[2] * S), width * S, Material.STEELWORK));
        }
    }

    /** A canonical box, in 1/32 m steps, as block coordinates. */
    private static double[] box(Rack rack, double u0, double u1, double y0, double y1, double w0, double w1) {
        var a = rack.world(u0 * S, y0 * S, w0 * S);
        var b = rack.world(u1 * S, y1 * S, w1 * S);
        return new double[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]), Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])};
    }
}
