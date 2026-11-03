package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.PipeRoute.Cell;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The layered rack cell (batch 2026-10-26-pipe-laying-system, RACK_LAYERS_PLAN option A, support P2 with the lowered
 * support): every pair of lanes that fit one cell together, at every option, arm pair and test size, in every shape,
 * keeps 1/64 m between their surfaces; every lane stays in its block and clear of the steel; lanes of racks side by
 * side meet where their face points coincide, whatever their facings, and keep their order round a corner.
 *
 * <p>Aiming on a rack's tier (owner 2026-10-26, "Aim on the tier"): the run face nearest the aimed point is where the
 * line passes through to its route, the free slot nearest the point is its lane, and a cell with no free slot gives the
 * nearest one, not fitting.</p>
 *
 * <p>Rack cells placed together (RACK_JUNCTION_PLAN J1 and J2): the shape each takes once, as it is placed, from the
 * racks whose runs point into it and the player's look, one test per row of the J2 table; groups and turns built block
 * by block; and the steel they draw.</p>
 */
class PipeRackGeometryTest {
    /** Test sizes as outside codes (1/64 m): 2", the largest of each width, and both sides of the lowered support. */
    private static final int[] CODES = {4, 11, 23, 35, 36, 37, 47};
    private static final int NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;
    private static final double SMALL = PipeSizes.outsideOf(PipeSizes.code(.0625)), SIX = PipeSizes.outsideOf(PipeSizes.code(.17));
    private static final Cell O = new Cell(0, 0, 0);

    @Test
    void aLineTakesThePositionsItsSizeNeedsAndRestsOnTheBeam() {
        assertEquals(1, RackGeometry.width(.168), "6 inch");
        assertEquals(2, RackGeometry.width(.356), "14 inch");
        assertEquals(3, RackGeometry.width(.508), "20 inch");
        assertEquals(4, RackGeometry.width(.711), "28 inch");
        assertTrue(RackGeometry.width(PipeSizes.MAX_OUTSIDE) > RackGeometry.MAX_WIDTH, "32 inch is not laid on racks (RD10)");
        for (int width = 1; width <= RackGeometry.MAX_WIDTH; width++) assertEquals(width, RackGeometry.width(RackGeometry.largest(width)));
        assertEquals(14, RackGeometry.height(.0625), "2 inch: 14/32");
        assertEquals(16, RackGeometry.height(.168), "6 inch: 16/32");
        assertEquals(19, RackGeometry.height(.356), "14 inch: 19/32");
        assertEquals(22, RackGeometry.height(.508), "20 inch: 22/32");
        for (int code = 1; code <= 47; code++) {
            double od = PipeSizes.outsideOf(code), bottom = RackGeometry.height(od) / 32.0 - od / 2;
            if (RackGeometry.height(od) == 16 && code > 36) {
                // Too large to rest on the beam within the block: at mid-height on a lowered beam, as support P1.
                assertTrue(RackGeometry.top(od) <= 1, code + " fits its block");
                assertTrue(RackGeometry.beam(List.of(lane(RackGeometry.option(0, RackGeometry.TIER), 4, 1 << NORTH | 1 << SOUTH, code))) * 2 / 64.0 <= bottom + 1e-9, code + " on its beam");
            } else {
                assertTrue(bottom >= 13 / 32.0 - 1e-9 && bottom < 14 / 32.0, code + " rests on the beam: bottom " + bottom);
                assertTrue(RackGeometry.top(od) + RackGeometry.CLEARANCE <= 1 + 1e-9, code + " clears the block's top");
            }
        }
    }

    @Test
    void crossingLevelsClearTheLinesTheyPass() {
        assertTrue(RackGeometry.clears(.273, .168), "a 6 inch crossing passes over a 10 inch line");
        assertFalse(RackGeometry.clears(.3238, .168), "but not over a 12 inch one");
        for (int code = 1; code <= 36; code++) assertTrue(RackGeometry.clearsUnder(PipeSizes.outsideOf(code), RackGeometry.largest(1)), code + " rests over the lower level");
        assertFalse(RackGeometry.clearsUnder(PipeSizes.outsideOf(47), .0625), "a line on a lowered beam closes the lower level");
        var rack = new RackGeometry.Rack(NORTH, RackGeometry.STRAIGHT);
        var wide = List.of(lane(RackGeometry.option(0, RackGeometry.TIER), 4, 1 << NORTH | 1 << SOUTH, 47));
        assertFalse(RackGeometry.open(rack, wide, RackGeometry.crossing(RackGeometry.ABOVE), 1, .0625));
        assertFalse(RackGeometry.open(rack, wide, RackGeometry.crossing(RackGeometry.BELOW), 1, .0625));
        var small = List.of(lane(RackGeometry.option(1, RackGeometry.TIER), 1, 1 << NORTH | 1 << SOUTH, 11));
        assertTrue(RackGeometry.open(rack, small, RackGeometry.crossing(RackGeometry.ABOVE), 1, .168));
        assertTrue(RackGeometry.open(rack, small, RackGeometry.crossing(RackGeometry.BELOW), 1, .168));
    }

    @Test
    void aFlatExitClosesThePositionsBetweenItAndItsSide() {
        var rack = new RackGeometry.Rack(NORTH, RackGeometry.STRAIGHT);
        // Facing north, the right is east: a lane at position 1 entering from the back (south) leaving flat to the east.
        var flat = lane(RackGeometry.option(1, RackGeometry.TIER), 1, 1 << SOUTH | 1 << EAST, 11);
        assertEquals(0b1110, RackGeometry.taken(rack, List.of(flat)));
        assertTrue(RackGeometry.fits(rack, List.of(flat), lane(RackGeometry.option(0, RackGeometry.TIER), 1, 1 << NORTH | 1 << SOUTH, 11)));
        assertFalse(RackGeometry.fits(rack, List.of(flat), lane(RackGeometry.option(3, RackGeometry.TIER), 1, 1 << NORTH | 1 << SOUTH, 11)));
        assertFalse(RackGeometry.fits(rack, List.of(lane(RackGeometry.option(3, RackGeometry.TIER), 1, 1 << NORTH | 1 << SOUTH, 11)), flat), "nor passes over one");
        assertTrue(RackGeometry.fits(rack, List.of(lane(RackGeometry.option(3, RackGeometry.TIER), 1, 1 << NORTH | 1 << SOUTH, 11)),
                lane(RackGeometry.option(1, RackGeometry.ABOVE), 1, 1 << SOUTH | 1 << EAST, 11)), "over it instead");
        assertFalse(RackGeometry.fits(rack, List.of(), lane(RackGeometry.option(0, RackGeometry.TIER), 4, 1 << SOUTH | 1 << EAST, 47)), "a line on a lowered beam cannot leave flat over the stringer");
    }

    @Test
    void everyPairOfLanesInACellKeepsItsClearance() {
        double worst = Double.MAX_VALUE;
        for (int corner = RackGeometry.STRAIGHT; corner <= RackGeometry.TEE; corner++) {
            var rack = new RackGeometry.Rack(NORTH, corner);
            var lanes = lanes(rack);
            var shapes = new ArrayList<List<double[][]>>();
            for (var lane : lanes) shapes.add(segments(rack, lane));
            for (int i = 0; i < lanes.size(); i++)
                for (int j = i + 1; j < lanes.size(); j++) {
                    var a = lanes.get(i);
                    var b = lanes.get(j);
                    if (!RackGeometry.fits(rack, List.of(a), b)) continue;
                    double gap = distance(shapes.get(i), shapes.get(j)) - a.diameter() / 2 - b.diameter() / 2;
                    worst = Math.min(worst, gap);
                    // Each chord spans 1/32 of a quarter turn: it lies at most 1 mm inside the bend it stands for.
                    assertTrue(gap >= RackGeometry.CLEARANCE - 1e-3, "corner " + corner + ": " + a + " and " + b + " gap " + gap);
                }
        }
        assertEquals(RackGeometry.CLEARANCE, worst, 1e-3, "the narrowest gap is the clearance the positions are sized for");
    }

    /**
     * The steel each lane is checked against: every bent whole and stacked, on the ground, its braces both ways; and
     * every bent half of a shared one, a rack beside it on the right (the middle column), standing on a rack.
     */
    private static final int[] FRAMES = {
            RackGeometry.BENT_BACK | RackGeometry.BENT_FRONT | RackGeometry.END_BACK | RackGeometry.END_FRONT | RackGeometry.STACKED | RackGeometry.GROUNDED | RackGeometry.RISING,
            RackGeometry.BENT_BACK | RackGeometry.BENT_FRONT | RackGeometry.END_BACK | RackGeometry.END_FRONT | RackGeometry.STACKED | RackGeometry.GROUNDED,
            RackGeometry.BENT_BACK | RackGeometry.BENT_FRONT | RackGeometry.RIGHT_JOINED | RackGeometry.RISING};

    @Test
    void everyLaneStaysInItsBlockAndClearOfTheSteel() {
        for (int facing : new int[]{NORTH, SOUTH, WEST, EAST})
            for (int corner = RackGeometry.STRAIGHT; corner <= RackGeometry.TEE; corner++) {
                var geometry = new RackGeometry.Rack(facing, corner);
                for (var lane : lanes(geometry))
                    for (int steel : FRAMES) {
                        var rack = new RackGeometry.Rack(facing, corner, steel, RackGeometry.beam(List.of(lane)));
                        double r = lane.diameter() / 2;
                        // A resting line touches its beam; a chord lies at most 1 mm inside the bend it stands for. A brace
                        // is a square bar: clear of the line by its half diagonal.
                        for (var segment : segments(rack, lane))
                            for (var member : RackGeometry.steelwork(rack, List.of(lane)).drawn()) {
                                double gap = member.bar() ? segmentDistance(segment[0], segment[1], member.a(), member.b()) - member.width() / Math.sqrt(2)
                                        : boxDistance(segment, new double[]{member.a()[0], member.a()[1], member.a()[2], member.b()[0], member.b()[1], member.b()[2]});
                                assertTrue(gap >= r - 1e-3, "facing " + facing + " shape " + corner + " steel " + steel + ": " + lane + " runs into the steel at "
                                        + java.util.Arrays.toString(member.a()) + " " + java.util.Arrays.toString(member.b()));
                            }
                        for (var polygon : RackGeometry.lane(rack, lane)) {
                            var p = polygon.positions();
                            for (int v = 0; v < p.length; v++) assertTrue(p[v] >= -1e-9 && p[v] <= 1 + 1e-9, lane + " leaves its block");
                        }
                    }
            }
    }

    @Test
    void everyFlangeStandsClearOfTheSteel() {
        // Owner 2026-10-26, "Staggered, off steel": an open end's blind flange stands in off the steel at its face, a
        // spool's flange pair where no brace or bent comes near it. No flange or bolt reaches into a member, between
        // bents (no bent, a light frame) as well as at them.
        int[] frames = {FRAMES[0], FRAMES[1], FRAMES[2], 0, RackGeometry.LEFT_JOINED | RackGeometry.RIGHT_JOINED | RackGeometry.STACKED};
        int spools = 0;
        for (int facing : new int[]{NORTH, EAST})
            for (int corner = RackGeometry.STRAIGHT; corner <= RackGeometry.TEE; corner++)
                for (var alone : lanes(new RackGeometry.Rack(facing, corner)))
                    for (int joined : new int[]{0, alone.arms()})
                        for (int steel : frames) {
                            var lane = new RackGeometry.Lane(alone.option(), alone.width(), alone.arms(), joined, alone.outside());
                            var rack = new RackGeometry.Rack(facing, corner, steel, RackGeometry.beam(List.of(lane)));
                            boolean spool = RackGeometry.spool(rack, lane, 3 * RackGeometry.position(lane.option()));
                            if (spool) spools++;
                            var members = RackGeometry.steelwork(rack, List.of(lane)).drawn();
                            for (var polygon : RackGeometry.lane(rack, lane, spool)) {
                                if (polygon.material() != Mesh.Material.FLANGE && polygon.material() != Mesh.Material.BOLT) continue;
                                var box = bounds(polygon.positions());
                                for (var member : members) {
                                    double grow = member.bar() ? member.width() : 0;
                                    double[] a = member.a(), b = member.b();
                                    if (Math.min(a[0], b[0]) - grow > box[3] || Math.max(a[0], b[0]) + grow < box[0] || Math.min(a[1], b[1]) - grow > box[4]
                                            || Math.max(a[1], b[1]) + grow < box[1] || Math.min(a[2], b[2]) - grow > box[5] || Math.max(a[2], b[2]) + grow < box[2]) continue;
                                    for (var p : samples(polygon)) {
                                        boolean inside = member.bar() ? segmentDistance(p, p, a, b) < member.width() / 2 - 1e-3
                                                : p[0] > a[0] + 1e-3 && p[0] < b[0] - 1e-3 && p[1] > a[1] + 1e-3 && p[1] < b[1] - 1e-3 && p[2] > a[2] + 1e-3 && p[2] < b[2] - 1e-3;
                                        assertFalse(inside, "facing " + facing + " shape " + corner + " steel " + steel + (spool ? " spool" : "") + ": " + lane + "'s "
                                                + polygon.material() + " at " + java.util.Arrays.toString(p) + " in the steel at " + java.util.Arrays.toString(a) + " " + java.util.Arrays.toString(b));
                                    }
                                }
                            }
                        }
        assertTrue(spools > 0, "spools drawn");
    }

    @Test
    void theSteelStaysInItsBlockAndClearOverTheTierOfTheLinesAtTheSides() {
        // Over the beam's top only the columns and braces of the sides stand, within 3.75/32 m of them: tier lines keep
        // from about 4.26/32 m (a 6" line on an outer position).
        for (int corner = RackGeometry.STRAIGHT; corner <= RackGeometry.TEE; corner++)
            for (int steel : FRAMES) {
                var rack = new RackGeometry.Rack(NORTH, corner, steel, RackGeometry.BEAM_TOP);
                for (var member : RackGeometry.steelwork(rack, List.of()).drawn()) {
                    double[] a = member.a(), b = member.b();
                    for (var p : new double[][]{a, b})
                        for (double v : p) assertTrue(v >= -1e-9 && v <= 1 + 1e-9, "shape " + corner + ": steel leaves its block");
                    double grow = member.bar() ? member.width() : 0;
                    double lowX = Math.min(a[0], b[0]) - grow, highX = Math.max(a[0], b[0]) + grow, lowZ = Math.min(a[2], b[2]) - grow, highZ = Math.max(a[2], b[2]) + grow;
                    if (Math.max(a[1], b[1]) <= 13 / 32.0 + 1e-9) continue;
                    assertTrue(highX <= 3.75 / 32 + 1e-9 || lowX >= 28.25 / 32 - 1e-9, "shape " + corner + ": steel over the tier between the sides, x " + lowX + " to " + highX);
                    // A junction's lines leave through its sides too: its columns keep to its corners.
                    if (rack.junction()) assertTrue(highZ <= 4 / 32.0 + 1e-9 || lowZ >= 28 / 32.0 - 1e-9, "shape " + corner + ": a column over the tier off the corners, z " + lowZ + " to " + highZ);
                }
            }
    }

    @Test
    void lanesMeetWhereTheirPointsCoincideWhateverTheFacing() {
        // Two racks in line north-south, facing the same way and opposite ways: each tier lane meets exactly one lane of
        // the next rack, the same positions seen from the world.
        for (int other : new int[]{NORTH, SOUTH}) {
            var near = new RackGeometry.Rack(NORTH, RackGeometry.STRAIGHT);
            var far = new RackGeometry.Rack(other, RackGeometry.STRAIGHT);
            for (int code : CODES) {
                int width = RackGeometry.width(PipeSizes.outsideOf(code));
                for (int p = 0; p + width <= RackGeometry.POSITIONS; p++) {
                    int point = RackGeometry.point(near, RackGeometry.option(p, RackGeometry.TIER), width, PipeSizes.outsideOf(code), NORTH);
                    int meets = 0, at = -1;
                    for (int q = 0; q + width <= RackGeometry.POSITIONS; q++)
                        if (RackGeometry.point(far, RackGeometry.option(q, RackGeometry.TIER), width, PipeSizes.outsideOf(code), SOUTH) == point) {
                            meets++;
                            at = q;
                        }
                    assertEquals(1, meets, "position " + p + " of " + width);
                    assertEquals(other == NORTH ? p : RackGeometry.POSITIONS - width - p, at, "the numbering is a label only");
                }
            }
        }
    }

    @Test
    void cornerLanesKeepTheirOrderAndMeetTheNextRun() {
        // A run facing north turning east: the corner faces north and turns from its back to its right, into a run facing east.
        var corner = new RackGeometry.Rack(NORTH, RackGeometry.BACK_RIGHT);
        var next = new RackGeometry.Rack(EAST, RackGeometry.STRAIGHT);
        double od = PipeSizes.outsideOf(11);
        for (int p = 0; p < RackGeometry.POSITIONS; p++) {
            int option = RackGeometry.option(p, RackGeometry.TIER);
            assertTrue(RackGeometry.valid(corner, option, 1, 1 << SOUTH | 1 << EAST));
            assertFalse(RackGeometry.valid(corner, option, 1, 1 << SOUTH | 1 << NORTH), "a corner carries corner lanes only");
            int out = RackGeometry.point(corner, option, 1, od, EAST);
            assertEquals(out, RackGeometry.point(next, option, 1, od, WEST), "position " + p + " stays position " + p + " round the corner");
        }
        assertFalse(RackGeometry.valid(corner, RackGeometry.crossing(RackGeometry.ABOVE), 1, 1 << WEST | 1 << EAST), "no crossing in a corner");
        var members = RackGeometry.members(new RackGeometry.Rack(NORTH, RackGeometry.BACK_RIGHT));
        for (var box : members) assertFalse(box[0] >= 28 / 32.0 - 1e-9 && box[2] >= 28 / 32.0 - 1e-9 && box[1] == 0, "no column at the inner (south-east) corner");
        assertEquals(7, members.size(), "three columns, and a beam under the lines on each face");
    }

    @Test
    void aTeeTurnsItsBranchIntoTheRunAndPassesTheRunBehind() {
        // A run north-south, its branch east: the tee faces east. Its run lanes count their positions from the tee's back
        // (west); its turning lanes those of the branch, from its left (north).
        var tee = new RackGeometry.Rack(EAST, RackGeometry.TEE);
        var branch = new RackGeometry.Rack(EAST, RackGeometry.STRAIGHT);
        var run = new RackGeometry.Rack(NORTH, RackGeometry.STRAIGHT);
        double od = PipeSizes.outsideOf(11);
        int ns = 1 << NORTH | 1 << SOUTH, left = 1 << EAST | 1 << NORTH, right = 1 << EAST | 1 << SOUTH;
        int through = RackGeometry.option(0, RackGeometry.RUN), turnLeft = RackGeometry.option(0, RackGeometry.TURN_LEFT), turnRight = RackGeometry.option(3, RackGeometry.TURN_RIGHT);
        assertTrue(RackGeometry.valid(tee, through, 1, ns) && RackGeometry.valid(tee, turnLeft, 1, left) && RackGeometry.valid(tee, turnRight, 1, right));
        assertFalse(RackGeometry.valid(tee, through, 1, left), "each leg its own faces");
        assertFalse(RackGeometry.valid(tee, RackGeometry.option(0, RackGeometry.TIER), 1, 1 << EAST | 1 << WEST), "nothing through the tee's back");
        assertTrue(RackGeometry.valid(tee, RackGeometry.option(1, RackGeometry.TURN_LEFT), 2, left), "a wide line turns too");
        // Points: the branch meets the next rack east as a straight cell does; the run lanes meet the run racks north and south.
        for (int p = 0; p < RackGeometry.POSITIONS; p++) {
            assertEquals(RackGeometry.point(branch, RackGeometry.option(p, RackGeometry.TIER), 1, od, EAST), RackGeometry.point(tee, RackGeometry.option(p, RackGeometry.TURN_LEFT), 1, od, EAST));
            assertEquals(RackGeometry.point(run, RackGeometry.option(p, RackGeometry.TIER), 1, od, NORTH), RackGeometry.point(tee, RackGeometry.option(p, RackGeometry.RUN), 1, od, NORTH), "run position " + p);
            // A left-turning lane lands mirrored across the branch on the north run, a right-turning one keeps its number on the south.
            assertEquals(RackGeometry.point(run, RackGeometry.option(RackGeometry.POSITIONS - 1 - p, RackGeometry.TIER), 1, od, NORTH), RackGeometry.point(tee, RackGeometry.option(p, RackGeometry.TURN_LEFT), 1, od, NORTH));
            assertEquals(RackGeometry.point(run, RackGeometry.option(p, RackGeometry.TIER), 1, od, SOUTH), RackGeometry.point(tee, RackGeometry.option(p, RackGeometry.TURN_RIGHT), 1, od, SOUTH));
        }
        // Room (J3): two run lanes on positions 0 and 1, two lanes turning left from branch 0 and 1, two right from 2 and 3.
        var lanes = new ArrayList<RackGeometry.Lane>();
        for (var lane : List.of(lane(RackGeometry.option(0, RackGeometry.RUN), 1, ns, 11), lane(RackGeometry.option(1, RackGeometry.RUN), 1, ns, 11),
                lane(RackGeometry.option(0, RackGeometry.TURN_LEFT), 1, left, 11), lane(RackGeometry.option(1, RackGeometry.TURN_LEFT), 1, left, 11),
                lane(RackGeometry.option(2, RackGeometry.TURN_RIGHT), 1, right, 11), lane(RackGeometry.option(3, RackGeometry.TURN_RIGHT), 1, right, 11))) {
            assertTrue(RackGeometry.fits(tee, lanes, lane), lane + " beside " + lanes);
            lanes.add(lane);
        }
        assertFalse(RackGeometry.fits(tee, List.of(lane(RackGeometry.option(2, RackGeometry.TURN_LEFT), 1, left, 11)), lane(RackGeometry.option(1, RackGeometry.TURN_RIGHT), 1, right, 11)),
                "a right-turning lane left of a left-turning one would cross its bend");
        assertFalse(RackGeometry.fits(tee, List.of(lane(RackGeometry.option(0, RackGeometry.TURN_RIGHT), 1, right, 11)), lane(RackGeometry.option(0, RackGeometry.RUN), 1, ns, 11)),
                "the run passes behind every turning lane's landing");
        assertFalse(RackGeometry.fits(tee, List.of(lane(RackGeometry.option(1, RackGeometry.TURN_LEFT), 2, left, 23)), lane(RackGeometry.option(2, RackGeometry.TURN_LEFT), 1, left, 11)), "no shared branch position");
        // Steel: columns stand from the ground (the beams from under the tier), at the tee's back only, so none stands in a bend.
        var columns = RackGeometry.members(tee).stream().filter(box -> box[1] == 0).toList();
        assertEquals(2, columns.size(), "two columns");
        for (var box : columns) assertTrue(box[3] <= 4 / 32.0 + 1e-9, "at the back (west): " + java.util.Arrays.toString(box));
    }

    @Test
    void anOpenLaneEndIsBlindFlangedAndAJoinedOneIsNot() {
        var rack = new RackGeometry.Rack(NORTH, RackGeometry.STRAIGHT);
        int arms = 1 << NORTH | 1 << SOUTH;
        var open = RackGeometry.lane(rack, new RackGeometry.Lane(RackGeometry.option(1, RackGeometry.TIER), 1, arms, 0, PipeSizes.code(.1)));
        var joined = RackGeometry.lane(rack, new RackGeometry.Lane(RackGeometry.option(1, RackGeometry.TIER), 1, arms, arms, PipeSizes.code(.1)));
        assertTrue(open.size() > joined.size(), "the open lane has a blind flange at each end");
        assertTrue(open.stream().anyMatch(polygon -> polygon.material() == Mesh.Material.FLANGE), "flanged as a laid pipe's open end");
        var exit = new RackGeometry.Lane(RackGeometry.option(1, RackGeometry.ABOVE), 1, 1 << SOUTH | 1 << EAST, 0, PipeSizes.code(.1));
        assertEquals(3, RackGeometry.boxes(rack, exit).size(), "a lane over the tier: along, up and across");
    }

    @Test
    void theBentsStandWhereTheFrameSays() {
        // Facing north, the low face is the front (north): a cell without a bent stands a light frame there.
        var bare = RackGeometry.members(new RackGeometry.Rack(NORTH, RackGeometry.STRAIGHT));
        assertEquals(5, bare.size(), "between bents: the side channels and a light frame (two posts and an angle)");
        int back = RackGeometry.BENT_BACK;
        var bent = RackGeometry.members(new RackGeometry.Rack(NORTH, RackGeometry.STRAIGHT, back, RackGeometry.BEAM_TOP));
        assertEquals(8, bent.size(), "a bent at the back: two columns and a beam; the light frame; the channels");
        for (var box : bent) assertTrue(box[4] <= 13 / 32.0 + 1e-9, "nothing stands over the tier");
        double half = bent.stream().filter(box -> box[1] == 0 && box[4] == 13 / 32.0 && box[2] > .5).mapToDouble(box -> box[5] - box[2]).max().orElseThrow();
        assertEquals(2 / 32.0, half, 1e-9, "half of a shared bent: 2/32 m deep");
        var whole = RackGeometry.members(new RackGeometry.Rack(NORTH, RackGeometry.STRAIGHT, back | RackGeometry.END_BACK, RackGeometry.BEAM_TOP));
        assertEquals(4 / 32.0, whole.stream().filter(box -> box[1] == 0 && box[2] > .5).mapToDouble(box -> box[5] - box[2]).max().orElseThrow(), 1e-9, "a run's end: a whole bent, 4/32 m deep");
        var grounded = RackGeometry.members(new RackGeometry.Rack(NORTH, RackGeometry.STRAIGHT, back | RackGeometry.GROUNDED, RackGeometry.BEAM_TOP));
        assertEquals(bent.size() + 4, grounded.size(), "on the ground: a pedestal and a base plate under each column");
        var stacked = RackGeometry.members(new RackGeometry.Rack(NORTH, RackGeometry.STRAIGHT, back | RackGeometry.STACKED, RackGeometry.BEAM_TOP));
        assertEquals(4, stacked.stream().filter(box -> box[4] == 1).count(), "under a rack the columns and the light frame's posts run on up");
        var joined = RackGeometry.members(new RackGeometry.Rack(NORTH, RackGeometry.STRAIGHT, back | RackGeometry.LEFT_JOINED | RackGeometry.RIGHT_JOINED, RackGeometry.BEAM_TOP));
        assertEquals(3, joined.size(), "joined on both sides: the middle column, the beam running through and the light frame's angle");
    }

    // ---- aiming on the tier ----------------------------------------------------------------------------------------

    @Test
    void theFaceAndSlotNearestThePointAreAimed() {
        // Facing north, x is across from the left and the front (north) is at z 0.
        var rack = new RackGeometry.Rack(NORTH, RackGeometry.STRAIGHT);
        assertEquals(new RackGeometry.Aim(RackGeometry.option(3, RackGeometry.TIER), NORTH, SOUTH, true), RackGeometry.aim(rack, List.of(), SMALL, new double[]{.8, .4, .1}));
        assertEquals(new RackGeometry.Aim(RackGeometry.option(0, RackGeometry.TIER), SOUTH, NORTH, true), RackGeometry.aim(rack, List.of(), SMALL, new double[]{.2, .4, .9}),
                "near the back: the line passes through the back and the lane ends open at the front");
        // A 14" line, two positions wide: the pair whose centre is nearest.
        var wide = RackGeometry.aim(rack, List.of(), PipeSizes.outsideOf(PipeSizes.code(.356)), new double[]{.5, .4, .1});
        assertEquals(2, RackGeometry.width(PipeSizes.outsideOf(PipeSizes.code(.356))));
        assertEquals(RackGeometry.option(1, RackGeometry.TIER), wide.option(), "positions 1 and 2, centred");
    }

    @Test
    void aTakenSlotGivesTheNearestFreeOneAndAFullCellTheNearestRefused() {
        var rack = new RackGeometry.Rack(NORTH, RackGeometry.STRAIGHT);
        int along = 1 << NORTH | 1 << SOUTH;
        var lanes = new ArrayList<RackGeometry.Lane>(List.of(laid(rack, RackGeometry.option(3, RackGeometry.TIER), SIX, along)));
        assertEquals(new RackGeometry.Aim(RackGeometry.option(2, RackGeometry.TIER), NORTH, SOUTH, true), RackGeometry.aim(rack, lanes, SMALL, new double[]{.8, .4, .1}));
        for (int p = 0; p < 3; p++) lanes.add(laid(rack, RackGeometry.option(p, RackGeometry.TIER), SIX, along));
        assertEquals(new RackGeometry.Aim(RackGeometry.option(3, RackGeometry.TIER), NORTH, SOUTH, false), RackGeometry.aim(rack, lanes, SMALL, new double[]{.8, .4, .1}));
        assertNull(RackGeometry.aim(rack, List.of(), PipeSizes.MAX_OUTSIDE, new double[]{.5, .4, .1}), "a line too wide for racks");
    }

    @Test
    void aCornersArmFaceNearestThePointIsAimed() {
        // Facing east with its front-right corner: its lanes turn between the front (east) and the right (south).
        var corner = new RackGeometry.Rack(EAST, RackGeometry.FRONT_RIGHT);
        var nearSide = RackGeometry.aim(corner, List.of(), SMALL, new double[]{.4, .4, .95});
        assertTrue(nearSide.fits() && nearSide.through() == SOUTH && nearSide.open() == EAST && RackGeometry.level(nearSide.option()) == RackGeometry.TIER, "" + nearSide);
        var nearEnd = RackGeometry.aim(corner, List.of(), SMALL, new double[]{.95, .4, .4});
        assertTrue(nearEnd.fits() && nearEnd.through() == EAST && nearEnd.open() == SOUTH, "" + nearEnd);
    }

    @Test
    void aTeesBranchOrRunFaceIsAimedAndItsLaneSuitsTheLeg() {
        // Facing north: the run from the west to the east, the branch out of the north.
        var tee = new RackGeometry.Rack(NORTH, RackGeometry.TEE);
        var branch = RackGeometry.aim(tee, List.of(), SMALL, new double[]{.6, .4, .05});
        assertTrue(branch.fits() && branch.through() == NORTH && RackGeometry.level(branch.option()) != RackGeometry.RUN, "the branch: a turning lane " + branch);
        var run = RackGeometry.aim(tee, List.of(), SMALL, new double[]{.95, .4, .85});
        assertTrue(run.fits() && run.through() == EAST && run.open() == WEST && RackGeometry.level(run.option()) == RackGeometry.RUN, "the run, far from the branch: " + run);
        for (var aim : List.of(branch, run))
            assertTrue(RackGeometry.valid(tee, aim.option(), 1, 1 << aim.through() | 1 << aim.open()), "suits its leg: " + aim);
    }

    // ---- the J2 table ----------------------------------------------------------------------------------------------

    @Test
    void noFeedAndNoRackBesideGoesAlongTheLook() {
        var world = new HashMap<Cell, RackGeometry.Rack>();
        assertEquals(new RackGeometry.Rack(EAST, RackGeometry.STRAIGHT), place(world, O, EAST));
        // A rack under it is no feed: a run across it stands on it (crossing at two heights, X1).
        assertEquals(new RackGeometry.Rack(NORTH, RackGeometry.STRAIGHT), place(world, new Cell(0, 1, 0), NORTH));
    }

    @Test
    void noFeedBesideARunFacesTheLook() {
        var world = new HashMap<Cell, RackGeometry.Rack>();
        run(world, new Cell(1, 0, 0), NORTH, 1);
        assertEquals(new RackGeometry.Rack(EAST, RackGeometry.STRAIGHT), place(world, O, EAST), "the look, whatever stands beside");
    }

    @Test
    void oneFeedInLineWithTheLookRunsOn() {
        var world = new HashMap<Cell, RackGeometry.Rack>();
        run(world, new Cell(0, 0, 1), SOUTH, 1);
        assertEquals(new RackGeometry.Rack(NORTH, RackGeometry.STRAIGHT), place(world, O, NORTH), "on along the feed's run, facing the look");
    }

    @Test
    void oneFeedAcrossTheLookTurnsTowardsTheLook() {
        var world = new HashMap<Cell, RackGeometry.Rack>();
        run(world, new Cell(0, 0, 1), NORTH, 1);
        var corner = place(world, O, EAST);
        assertTrue(corner.cornered(), "a corner");
        assertEquals(EAST, corner.facing(), "facing the look");
        assertEquals(faces(SOUTH, EAST), RackGeometry.runs(corner), "from the run (south) towards the look (east)");
        // Out of a corner's side the next block runs on away from it.
        assertEquals(new RackGeometry.Rack(EAST, RackGeometry.STRAIGHT), place(world, new Cell(1, 0, 0), EAST));
    }

    @Test
    void twoFeedsInLineWithTheLookAlongThemFillTheGap() {
        var world = new HashMap<Cell, RackGeometry.Rack>();
        run(world, new Cell(-1, 0, 0), WEST, 1);
        run(world, new Cell(1, 0, 0), WEST, 1);
        assertEquals(new RackGeometry.Rack(EAST, RackGeometry.STRAIGHT), place(world, O, EAST), "facing the look");
    }

    @Test
    void twoFeedsInLineWithTheLookAcrossMakeATeeTowardsTheLook() {
        var world = new HashMap<Cell, RackGeometry.Rack>();
        run(world, new Cell(0, 0, -1), NORTH, 1);
        run(world, new Cell(0, 0, 1), NORTH, 1);
        var tee = place(world, O, EAST);
        assertEquals(new RackGeometry.Rack(EAST, RackGeometry.TEE), tee, "the branch opens towards the look");
        assertEquals(faces(NORTH, SOUTH, EAST), RackGeometry.runs(tee));
    }

    @Test
    void twoFeedsAtACornerMakeTheCornerJoiningThem() {
        for (int look : new int[]{NORTH, SOUTH, WEST, EAST}) {
            var world = new HashMap<Cell, RackGeometry.Rack>();
            run(world, new Cell(0, 0, 1), NORTH, 1);
            run(world, new Cell(1, 0, 0), EAST, 1);
            var corner = place(world, O, look);
            assertTrue(corner.cornered() && corner.facing() == look, "look " + look + ": " + corner);
            assertEquals(faces(SOUTH, EAST), RackGeometry.runs(corner), "look " + look);
        }
    }

    @Test
    void threeFeedsMakeTheTeeJoiningThem() {
        var world = new HashMap<Cell, RackGeometry.Rack>();
        run(world, new Cell(0, 0, -1), NORTH, 1);
        run(world, new Cell(0, 0, 1), SOUTH, 1);
        run(world, new Cell(-1, 0, 0), WEST, 1);
        assertEquals(new RackGeometry.Rack(WEST, RackGeometry.TEE), place(world, O, NORTH), "the branch is the feed with none across from it; the tee faces it");
    }

    @Test
    void fourFeedsPlaceAStraightRackAlongTheLook() {
        var world = new HashMap<Cell, RackGeometry.Rack>();
        for (int face : new int[]{NORTH, SOUTH}) run(world, O.step(face), NORTH, 1);
        for (int face : new int[]{WEST, EAST}) run(world, O.step(face), EAST, 1);
        assertEquals(new RackGeometry.Rack(EAST, RackGeometry.STRAIGHT), place(world, O, EAST), "no cross block (X1)");
    }

    @Test
    void sneakingPlacesAPlainStraightRackAlongTheLook() {
        var world = new HashMap<Cell, RackGeometry.Rack>();
        run(world, new Cell(0, 0, 1), NORTH, 1);
        assertEquals(new RackGeometry.Rack(EAST, RackGeometry.STRAIGHT), place(world, O, EAST, true), "no corner while sneaking");
    }

    // ---- groups ----------------------------------------------------------------------------------------------------

    @Test
    void aGroupPlacedLookingAlongItIsOneStraightRack() {
        // Two racks wide and three long, every block placed looking north, row by row or column by column.
        for (boolean rows : new boolean[]{true, false}) {
            var world = new HashMap<Cell, RackGeometry.Rack>();
            for (int i = 0; i < 6; i++) place(world, rows ? new Cell(i % 2, 0, i / 2) : new Cell(i / 3, 0, i % 3), NORTH);
            for (var cell : world.keySet()) {
                assertEquals(RackGeometry.STRAIGHT, world.get(cell).corner(), cell + " straight");
                int steel = steel(world, cell);
                assertEquals(cell.x() == 0 ? RackGeometry.RIGHT_JOINED : RackGeometry.LEFT_JOINED, steel & (RackGeometry.LEFT_JOINED | RackGeometry.RIGHT_JOINED), cell + " joins the rack beside it");
                assertEquals(cell.z() != 1, (steel & 3) != 0, cell + " has a bent at the run's ends only");
            }
        }
    }

    @Test
    void aTwoWideTurnBuiltRunByRunNestsItsCornersOnTheDiagonal() {
        // Two runs side by side going north, x 0 and 1, turning east into two runs at z 0 and 1: each turns at its own
        // corner, (0, 0) for the outer and (1, 1) for the inner (rack-7). The runs are laid first, each looking along
        // itself; each corner then looking towards the turn; then the turned runs looking east.
        var north = List.of(new Cell(0, 0, 3), new Cell(1, 0, 3), new Cell(0, 0, 2), new Cell(1, 0, 2), new Cell(0, 0, 1));
        var east = List.of(new Cell(1, 0, 0), new Cell(2, 0, 0), new Cell(3, 0, 0), new Cell(2, 0, 1), new Cell(3, 0, 1));
        var corners = List.of(O, new Cell(1, 0, 1));
        for (boolean cornersFirst : new boolean[]{true, false}) {
            var world = new HashMap<Cell, RackGeometry.Rack>();
            for (var cell : north) place(world, cell, NORTH);
            var order = new ArrayList<Cell>();
            if (cornersFirst) order.addAll(corners);
            order.addAll(east);
            if (!cornersFirst) order.addAll(corners);
            for (var cell : order) place(world, cell, EAST);
            for (var cell : world.keySet()) {
                boolean corner = corners.contains(cell);
                assertEquals(corner, world.get(cell).cornered(), cell + (cornersFirst ? ", corners first" : ", corners last"));
                if (corner) assertEquals(faces(SOUTH, EAST), RackGeometry.runs(world.get(cell)), cell + " turns from the south to the east");
            }
        }
    }

    @Test
    void aJunctionsNeighboursStandAWholeBentAgainstIt() {
        var world = new HashMap<Cell, RackGeometry.Rack>();
        run(world, new Cell(0, 0, 4), NORTH, 4);
        place(world, O, EAST);
        // The run's last block before the corner: along z 1, a bent only by its neighbours.
        int steel = steel(world, new Cell(0, 0, 1));
        assertEquals(RackGeometry.BENT_FRONT | RackGeometry.END_FRONT, steel & (RackGeometry.BENT_FRONT | RackGeometry.END_FRONT), "a whole bent towards the corner");
        assertEquals(0, steel & RackGeometry.END_BACK, "a shared bent, or none, along the run");
    }

    @Test
    void aStackedRackCarriesTheColumnsUpToTheRackOnIt() {
        var world = new HashMap<Cell, RackGeometry.Rack>();
        place(world, O, EAST);
        place(world, new Cell(0, 1, 0), EAST);
        int lower = steel(world, O), upper = steel(world, new Cell(0, 1, 0));
        assertEquals(RackGeometry.STACKED | RackGeometry.GROUNDED, lower & (RackGeometry.STACKED | RackGeometry.GROUNDED), "stacked, on the ground");
        assertEquals(0, upper & (RackGeometry.STACKED | RackGeometry.GROUNDED), "nothing on the upper rack, and it stands on the lower");
        double top = RackGeometry.members(RackGeometry.framed(world.get(O), lower, RackGeometry.BEAM_TOP)).stream().mapToDouble(box -> box[4]).max().orElseThrow();
        assertEquals(1, top, 1e-9, "the lower rack's columns reach the upper rack");
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static RackGeometry.Lane lane(int option, int width, int arms, int code) {
        return new RackGeometry.Lane(option, width, arms, 0, code);
    }

    /** Every lane the cell takes alone, at every option and arm pair, for each test size. */
    private static List<RackGeometry.Lane> lanes(RackGeometry.Rack rack) {
        var out = new ArrayList<RackGeometry.Lane>();
        for (int code : CODES) {
            int width = RackGeometry.width(PipeSizes.outsideOf(code));
            for (int option = 0; option < RackGeometry.OPTIONS; option++) {
                int w = RackGeometry.tier(option) ? width : 1;
                if (!RackGeometry.tier(option) && width != 1) continue;
                for (int a = 0; a < 6; a++)
                    for (int b = a + 1; b < 6; b++) {
                        int arms = 1 << a | 1 << b;
                        if (!RackGeometry.valid(rack, option, w, arms)) continue;
                        var lane = new RackGeometry.Lane(option, w, arms, 0, code);
                        if (RackGeometry.fits(rack, List.of(), lane)) out.add(lane);
                    }
            }
        }
        return out;
    }

    /** A lane's centre line as segments: its straights, and its bends in chords of 1/32 of a quarter turn each; a tee's lane on its leg. */
    private static List<double[][]> segments(RackGeometry.Rack cell, RackGeometry.Lane laid) {
        var leg = RackGeometry.leg(cell, laid);
        var rack = leg.rack();
        var lane = leg.lane();
        var path = RackGeometry.path(rack, lane);
        var out = new ArrayList<double[][]>();
        double od = lane.diameter();
        if (path.size() == 2) {
            out.add(new double[][]{path.get(0), path.get(1)});
            return out;
        }
        if (RackGeometry.tier(lane.option()) && rack.cornered()) {
            double[] da = LineGeometry.step(RackGeometry.first(rack, lane)), db = LineGeometry.step(RackGeometry.second(rack, lane)), corner = path.get(1);
            double bend = Math.abs(Mesh.dot(Mesh.sub(path.get(0), corner), da));
            var origin = Mesh.plus(corner, Mesh.scale(Mesh.plus(da, db), bend));
            arc(out, origin, Mesh.sub(path.get(0), origin), Mesh.sub(path.get(2), origin), Math.PI / 2);
            return out;
        }
        var end = new LineGeometry.End(od, false, 0, 0, true);
        var fitted = LineGeometry.fitted(path, end, end, od, RackGeometry.BEND);
        var points = fitted.points();
        int legs = fitted.lengths().length;
        double bend = fitted.bend() < 1.05 * od / 2 ? 0 : fitted.bend();
        double[] from = points.getFirst();
        for (int k = 0; k < legs; k++) {
            var dir = fitted.dirs()[k];
            var to = k == legs - 1 ? points.getLast() : Mesh.sub(points.get(k + 1), Mesh.scale(dir, bend * fitted.tans()[k + 1]));
            out.add(new double[][]{from, to});
            if (k == legs - 1) break;
            var next = fitted.dirs()[k + 1];
            if (bend == 0) {
                from = to;
                continue;
            }
            var inward = Mesh.unit(Mesh.sub(next, Mesh.scale(dir, Mesh.dot(next, dir))));
            var centre = Mesh.plus(to, Mesh.scale(inward, bend));
            var c = Mesh.cross(dir, next);
            double turn = Math.atan2(Math.sqrt(Mesh.dot(c, c)), Mesh.dot(dir, next));
            arc(out, centre, Mesh.sub(to, centre), Mesh.scale(dir, bend), turn);
            from = Mesh.plus(points.get(k + 1), Mesh.scale(next, bend * fitted.tans()[k + 1]));
        }
        return out;
    }

    /** Chords of the arc about {@code centre} from {@code u} (a radius vector) towards {@code v} (square to it, of the same length), through {@code turn}. */
    private static void arc(List<double[][]> out, double[] centre, double[] u, double[] v, double turn) {
        int chords = Math.max(4, (int) Math.ceil(turn / (Math.PI / 64)));
        double[] last = Mesh.plus(centre, u);
        for (int k = 1; k <= chords; k++) {
            double t = turn * k / chords;
            var p = Mesh.plus(centre, Mesh.plus(Mesh.scale(u, Math.cos(t)), Mesh.scale(v, Math.sin(t))));
            out.add(new double[][]{last, p});
            last = p;
        }
    }

    private static double distance(List<double[][]> a, List<double[][]> b) {
        double best = Double.MAX_VALUE;
        for (var s : a) for (var t : b) best = Math.min(best, segmentDistance(s[0], s[1], t[0], t[1]));
        return best;
    }

    /** The distance between segments pq and rs (closest points, clamped). */
    static double segmentDistance(double[] p, double[] q, double[] r, double[] s) {
        double[] d1 = Mesh.sub(q, p), d2 = Mesh.sub(s, r), w = Mesh.sub(p, r);
        double a = Mesh.dot(d1, d1), e = Mesh.dot(d2, d2), f = Mesh.dot(d2, w);
        double sc, tc;
        if (a < 1e-15 && e < 1e-15) return Math.sqrt(Mesh.dot(w, w));
        if (a < 1e-15) {
            sc = 0;
            tc = clamp(f / e);
        } else {
            double c = Mesh.dot(d1, w);
            if (e < 1e-15) {
                tc = 0;
                sc = clamp(-c / a);
            } else {
                double b = Mesh.dot(d1, d2), denom = a * e - b * b;
                sc = denom > 1e-15 ? clamp((b * f - c * e) / denom) : 0;
                tc = (b * sc + f) / e;
                if (tc < 0) {
                    tc = 0;
                    sc = clamp(-c / a);
                } else if (tc > 1) {
                    tc = 1;
                    sc = clamp((b - c) / a);
                }
            }
        }
        var between = Mesh.sub(Mesh.plus(p, Mesh.scale(d1, sc)), Mesh.plus(r, Mesh.scale(d2, tc)));
        return Math.sqrt(Mesh.dot(between, between));
    }

    private static double clamp(double x) {
        return Math.max(0, Math.min(1, x));
    }

    /** The box {@code {x0, y0, z0, x1, y1, z1}} round the points {@code positions}. */
    private static double[] bounds(double[] positions) {
        double[] out = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        for (int v = 0; v < positions.length; v++) {
            out[v % 3] = Math.min(out[v % 3], positions[v]);
            out[3 + v % 3] = Math.max(out[3 + v % 3], positions[v]);
        }
        return out;
    }

    /** Points over a convex polygon: a grid eight to an edge on each triangle of its fan. */
    private static List<double[]> samples(Mesh.Polygon polygon) {
        var out = new ArrayList<double[]>();
        double[] p = polygon.positions();
        int n = 8;
        for (int k = 1; k + 1 < polygon.size(); k++)
            for (int i = 0; i <= n; i++)
                for (int j = 0; i + j <= n; j++) {
                    double s = i / (double) n, t = j / (double) n, r = 1 - s - t;
                    out.add(new double[]{r * p[0] + s * p[3 * k] + t * p[3 * k + 3], r * p[1] + s * p[3 * k + 1] + t * p[3 * k + 4], r * p[2] + s * p[3 * k + 2] + t * p[3 * k + 5]});
                }
        return out;
    }

    /** A segment's least distance to a box, sampled finely (the boxes are thin, the segments short). */
    private static double boxDistance(double[][] segment, double[] box) {
        double best = Double.MAX_VALUE;
        for (int k = 0; k <= 256; k++) {
            var p = Mesh.plus(segment[0], Mesh.scale(Mesh.sub(segment[1], segment[0]), k / 256.0));
            double dx = Math.max(0, Math.max(box[0] - p[0], p[0] - box[3])), dy = Math.max(0, Math.max(box[1] - p[1], p[1] - box[4])), dz = Math.max(0, Math.max(box[2] - p[2], p[2] - box[5]));
            best = Math.min(best, Math.sqrt(dx * dx + dy * dy + dz * dz));
        }
        return best;
    }

    private static RackGeometry.Lane laid(RackGeometry.Rack rack, int option, double outside, int arms) {
        return new RackGeometry.Lane(option, RackGeometry.width(outside), arms, arms, PipeSizes.code(outside));
    }

    private static RackGeometry.Neighbours around(Map<Cell, RackGeometry.Rack> world, Cell cell) {
        return face -> world.get(cell.step(face));
    }

    /** The world after placing a rack at {@code cell} looking {@code look}: its shape decided from what stands there, nothing reshaped (J1). */
    private static RackGeometry.Rack place(Map<Cell, RackGeometry.Rack> world, Cell cell, int look, boolean sneaking) {
        var rack = RackGeometry.placed(around(world, cell), look, sneaking);
        world.put(cell, rack);
        return rack;
    }

    private static RackGeometry.Rack place(Map<Cell, RackGeometry.Rack> world, Cell cell, int look) {
        return place(world, cell, look, false);
    }

    /** A straight run of racks facing {@code facing} from {@code from}, {@code length} blocks along it. */
    private static void run(Map<Cell, RackGeometry.Rack> world, Cell from, int facing, int length) {
        var at = from;
        for (int i = 0; i < length; i++, at = at.step(facing)) world.put(at, new RackGeometry.Rack(facing, RackGeometry.STRAIGHT));
    }

    private static int faces(int... faces) {
        int out = 0;
        for (int face : faces) out |= 1 << face;
        return out;
    }

    private static int steel(Map<Cell, RackGeometry.Rack> world, Cell cell) {
        var rack = world.get(cell);
        return RackGeometry.steel(around(world, cell), rack, LineGeometry.axis(rack.facing()) == 2 ? cell.z() : cell.x(), 0);
    }
}
