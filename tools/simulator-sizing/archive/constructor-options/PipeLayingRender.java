package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.LineGeometry.Key;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Offline look at pipe racks and transition pipes (batch 2026-10-26-pipe-laying-system, W4 and the layered rack R4;
 * rasterizer from tools/storage-vessel-renders/StorageRender): rack cells from {@link RackGeometry#polygons} and
 * {@link RackGeometry#lane}, plain pipe cells from {@link LineGeometry#polygons}, as the chunk models draw them, textured
 * with the mod's sprites, back faces culled.
 *
 * <p>Args: {@code scene outPrefix [views]}; scenes {@code tier}, {@code exits}, {@code lowered}, {@code corner},
 * {@code widths} and {@code stacked} (see the README). Views as StorageRender: front, back, left, right, top, iso, iso2,
 * iso3, low. Run from the worktree root.
 */
public class PipeLayingRender {
    private static final String ASSETS = "src/main/resources/assets/icheme/";
    private static final int DOWN = 0, UP = 1, NORTH = 2, SOUTH = 3, WEST = 4, EAST = 5;

    private record Triangle(double[][] p, double[][] uv, double[] normal, Mesh.Material material) {
    }

    private record Sprite(int width, int height, int[] argb) {
        int sample(double u, double v) {
            int x = Math.floorMod((int) Math.floor(u * width), width), y = Math.floorMod((int) Math.floor(v * height), height);
            return argb[y * width + x];
        }
    }

    private static final List<Triangle> TRIANGLES = new ArrayList<>();
    private static int lowX = 0, lowZ = 0, highX = 1, highZ = 1;

    /** Placed steel (world block units: boxes {lo, hi}, bars {a, b, {half width}}) and pipe surface samples, for the -Dclash check. */
    private static final List<double[][]> STEEL = new ArrayList<>();
    private static final List<double[]> PIPE_POINTS = new ArrayList<>();
    private static final boolean CLASH = Boolean.getBoolean("clash");

    private static void place(int x, int y, int z, List<Mesh.Polygon> polygons) {
        for (var polygon : polygons) add(TRIANGLES, polygon, x, y, z);
        if (CLASH)
            for (var polygon : polygons)
                if (polygon.material() == Mesh.Material.PIPE || polygon.material() == Mesh.Material.FLANGE) sample(polygon, new double[]{x, y, z});
        lowX = Math.min(lowX, x);
        lowZ = Math.min(lowZ, z);
        highX = Math.max(highX, x + 1);
        highZ = Math.max(highZ, z + 1);
    }

    /** A rack lane on {@code position} at {@code level}, of size {@code code}, with these arms, joined where {@code joined}. */
    private static RackGeometry.Lane lane(int position, int level, int code, int arms, int joined) {
        return new RackGeometry.Lane(RackGeometry.option(position, level), RackGeometry.width(PipeSizes.outsideOf(code)), arms, joined, code);
    }

    /** A crossing at {@code level} of size {@code code}. */
    private static RackGeometry.Lane crossing(int level, int code, int arms, int joined) {
        return new RackGeometry.Lane(RackGeometry.crossing(level), 1, arms, joined, code);
    }

    /** A rack cell's lanes as the chunk model draws them, each with its spool pair where {@link RackGeometry#spool} puts one, {@code along} blocks along the run. */
    private static void lanes(RackGeometry.Rack frame, List<RackGeometry.Lane> lanes, int x, int y, int z, int along) {
        for (var lane : lanes) place(x, y, z, RackGeometry.lane(frame, lane, RackGeometry.spool(frame, lane, along)));
    }

    /** A rack cell's steel as the chunk model draws it ({@link RackGeometry#polygons}), its members kept for -Dclash. */
    private static void frame(RackGeometry.Rack frame, List<RackGeometry.Lane> lanes, int x, int y, int z) {
        place(x, y, z, RackGeometry.polygons(frame, lanes));
        if (!CLASH) return;
        for (var member : RackGeometry.steelwork(frame, lanes).drawn()) {
            double[] a = {member.a()[0] + x, member.a()[1] + y, member.a()[2] + z}, b = {member.b()[0] + x, member.b()[1] + y, member.b()[2] + z};
            STEEL.add(member.bar() ? new double[][]{a, b, {member.width() / 2}} : new double[][]{a, b});
        }
    }

    /**
     * A rack cell facing {@code facing} with shape {@code corner}, a whole bent in the planes of {@code bents} (1 back
     * plane, 2 front: the run's ends), a rack stacked on it or not, and its lanes: drawn as the chunk model draws it, the
     * beams lowered under a lowered line.
     */
    private static RackGeometry.Rack rack(int x, int y, int z, int facing, int corner, int bents, boolean stacked, RackGeometry.Lane... lanes) {
        var frame = new RackGeometry.Rack(facing, corner, bents | bents << 6 | (stacked ? RackGeometry.STACKED : 0), RackGeometry.beam(List.of(lanes)));
        for (var lane : lanes) if (!RackGeometry.fits(frame, List.of(lanes).subList(0, List.of(lanes).indexOf(lane)), lane)) throw new IllegalStateException("Lane does not fit: " + lane);
        frame(frame, List.of(lanes), x, y, z);
        lanes(frame, List.of(lanes), x, y, z, facing >= 4 ? x : z);
        return frame;
    }

    /** A plain pipe of size {@code code} with joints {@code joints}, meeting lanes of the racks next door. */
    private static void pipe(int x, int y, int z, int code, int joints, Meet... meets) {
        long faces = 0, offsets = 0;
        for (int face = 0; face < 6; face++) if ((joints >> face & 1) != 0) faces |= (long) code << 7 * face;
        for (var meet : meets) {
            var l = meet.lane;
            var at = RackGeometry.steps(RackGeometry.point(meet.rack, l.option(), l.width(), l.diameter(), meet.face ^ 1));
            faces = faces & ~(127L << 7 * meet.face) | (long) l.outside() << 7 * meet.face;
            offsets |= Key.offsets(meet.face, at[0], at[1]);
        }
        int facing = joints == 0 ? EAST : Integer.numberOfTrailingZeros(joints);
        place(x, y, z, LineGeometry.polygons(new Key(Kind.PIPE, facing, joints, code, faces, 0, false, offsets)));
    }

    /**
     * A group of bare racks from a layout: levels split by '/', rows (z) by ';', one character per cell along x: N, S, E,
     * W a rack placed looking that way (lower case: sneaking), '.' none. Placed in reading order, levels first, each shaped
     * once by {@link RackGeometry#placed} and framed by {@link RackGeometry#steel}, as the block and its entity do in a
     * world. Returns the frames by cell.
     */
    private static Map<String, RackGeometry.Rack> grid(String layout) {
        int[][] steps = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
        var shaped = new java.util.LinkedHashMap<String, RackGeometry.Rack>();
        var levels = layout.split("/");
        for (int y = 0; y < levels.length; y++) {
            var rows = levels[y].split(";");
            for (int z = 0; z < rows.length; z++)
                for (int x = 0; x < rows[z].length(); x++) {
                    char c = rows[z].charAt(x);
                    int look = "..NSWE".indexOf(Character.toUpperCase(c));
                    if (look < 2) continue;
                    int[] at = {x, y, z};
                    RackGeometry.Neighbours around = face -> shaped.get((at[0] + steps[face][0]) + "," + (at[1] + steps[face][1]) + "," + (at[2] + steps[face][2]));
                    shaped.put(x + "," + y + "," + z, RackGeometry.placed(around, look, Character.isLowerCase(c)));
                }
        }
        var out = new java.util.TreeMap<String, RackGeometry.Rack>();
        for (var e : shaped.entrySet()) {
            var at = Arrays.stream(e.getKey().split(",")).mapToInt(Integer::parseInt).toArray();
            RackGeometry.Neighbours around = face -> shaped.get((at[0] + steps[face][0]) + "," + (at[1] + steps[face][1]) + "," + (at[2] + steps[face][2]));
            var rack = e.getValue();
            int along = rack.facing() >= 4 ? at[0] : at[2];
            var frame = RackGeometry.framed(rack, RackGeometry.steel(around, rack, along, 0), RackGeometry.BEAM_TOP);
            out.put(e.getKey(), frame);
            frame(frame, List.of(), at[0], at[1], at[2]);
        }
        return out;
    }

    /**
     * The proposal scene (RACK_FRAME_PLAN, the owner's photos 2026-10-26): racks facing east, x 0 to 5, two wide (z 0 and
     * 1), on -Dtiers levels (1 or 2) from the ground, with lines of several sizes, a riser from the lower tier into the
     * upper and a flat exit to the south; each cell framed by {@link RackGeometry#steel} as in a world.
     */
    private static void proposal(int tiers) {
        int c2 = 4, c4 = 7, c6 = 11, c14 = 23, c20 = 35, T = RackGeometry.TIER;
        var lanes = new java.util.HashMap<String, List<RackGeometry.Lane>>();
        for (int y = 0; y < tiers; y++) for (int z = 0; z < 2; z++) for (int x = 0; x < 6; x++) lanes.put(x + "," + y + "," + z, new ArrayList<>());
        java.util.function.BiConsumer<String, int[]> add = (cell, l) -> {
            int x = Integer.parseInt(cell.split(",")[0]), arms = l[2], open = (x == 0 ? j(WEST) : 0) | (x == 5 ? j(EAST) : 0);
            lanes.get(cell).add(lane(l[0], T, l[1], arms, arms & ~open));
        };
        int along = j(WEST, EAST);
        for (int x = 0; x < 6; x++) {
            add.accept(x + ",0,0", new int[]{0, c6, along});
            add.accept(x + ",0,0", new int[]{1, c14, along});
            add.accept(x + ",0,1", new int[]{0, c4, along});
            if (tiers == 1 || x < 4) add.accept(x + ",0,1", new int[]{2, c6, along});
            if (tiers == 1 && x == 2) {
                lanes.get(x + ",0,1").add(lane(3, T, c6, j(WEST, SOUTH), j(WEST, SOUTH)));
            } else if (tiers == 1 && x < 2) add.accept(x + ",0,1", new int[]{3, c6, along});
            if (tiers < 2) continue;
            add.accept(x + ",1,0", new int[]{0, c20, along});
            add.accept(x + ",1,0", new int[]{3, c2, along});
            add.accept(x + ",1,1", new int[]{0, c2, along});
            if (x == 4) lanes.get(x + ",1,1").add(lane(2, T, c6, j(DOWN, EAST), j(DOWN, EAST)));
            if (x == 5) add.accept(x + ",1,1", new int[]{2, c6, along});
            if (x == 2) lanes.get(x + ",1,1").add(lane(3, T, c6, j(WEST, SOUTH), j(WEST, SOUTH)));
            else if (x < 2) add.accept(x + ",1,1", new int[]{3, c6, along});
        }
        if (tiers >= 2) lanes.get("4,0,1").add(lane(2, T, c6, j(WEST, UP), j(WEST, UP)));
        if (Boolean.getBoolean("exits") && tiers == 1) {
            // -Dexits, for the clash check: side exits under the tier at a bent (x 3) and at a run's end (x 0), over the
            // tier beside a light frame (x 4), and crossings under the tier at the other run end (x 5).
            int B = RackGeometry.BELOW, A = RackGeometry.ABOVE;
            lanes.get("3,0,0").removeIf(l -> l.option() == RackGeometry.option(0, T));
            lanes.get("3,0,0").add(lane(0, B, c6, j(WEST, NORTH), j(WEST, NORTH)));
            lanes.get("0,0,1").removeIf(l -> l.option() == RackGeometry.option(3, T));
            lanes.get("0,0,1").add(lane(3, B, c6, j(EAST, SOUTH), j(EAST, SOUTH)));
            lanes.get("4,0,0").removeIf(l -> l.option() == RackGeometry.option(0, T));
            lanes.get("4,0,0").add(lane(0, A, c4, j(WEST, NORTH), j(WEST, NORTH)));
            for (int z = 0; z < 2; z++) lanes.get("5,0," + z).add(crossing(B, c4, j(NORTH, SOUTH), j(NORTH, SOUTH)));
            // And a 28" line on lowered beams at x 3 and 4, south.
            for (int x = 3; x <= 4; x++) {
                lanes.get(x + ",0,1").clear();
                lanes.get(x + ",0,1").add(lane(0, T, 45, along, along));
            }
        }
        int[][] steps = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
        for (var e : lanes.entrySet()) {
            var at = Arrays.stream(e.getKey().split(",")).mapToInt(Integer::parseInt).toArray();
            RackGeometry.Neighbours around = face -> lanes.containsKey((at[0] + steps[face][0]) + "," + (at[1] + steps[face][1]) + "," + (at[2] + steps[face][2])) ? new RackGeometry.Rack(EAST, RackGeometry.STRAIGHT) : null;
            var rack = new RackGeometry.Rack(EAST, RackGeometry.STRAIGHT);
            var frame = RackGeometry.framed(rack, RackGeometry.steel(around, rack, at[0], 0), RackGeometry.beam(e.getValue()));
            frame(frame, e.getValue(), at[0], at[1], at[2]);
            lanes(frame, e.getValue(), at[0], at[1], at[2], at[0]);
        }
        // The flat exit's pipe to the south, from the top tier.
        int top = tiers - 1;
        var exit = lanes.get("2," + top + ",1").stream().filter(l -> (l.arms() & j(SOUTH)) != 0).findFirst().orElseThrow();
        var exitFrame = new RackGeometry.Rack(EAST, RackGeometry.STRAIGHT, 0, RackGeometry.beam(lanes.get("2," + top + ",1")));
        pipe(2, top, 2, c6, j(NORTH, SOUTH), meet(NORTH, exitFrame, exit));
        pipe(2, top, 3, c6, j(NORTH));
    }

    /**
     * The flange scene (owner 2026-10-26, "Staggered, off steel"): a run facing east, x -1 to 12, on two tiers framed by
     * {@link RackGeometry#steel} as in a world, lines of several sizes on every position, blind-flanged at both ends of
     * the run, with a spool pair every 12 blocks staggered three a position (x 0, 3, 6 and 9). {@code -Dbig=true} puts a
     * 20" line on the upper tier (an end capped where its flange meets the stacked rack's side brace).
     */
    private static void flanges() {
        int c2 = 4, c4 = 7, c6 = 11, c10 = 17, c14 = 23, c20 = 35, T = RackGeometry.TIER, along = j(WEST, EAST);
        var lanes = new java.util.HashMap<String, List<RackGeometry.Lane>>();
        for (int x = -1; x <= 12; x++) {
            int open = (x == -1 ? j(WEST) : 0) | (x == 12 ? j(EAST) : 0);
            lanes.put(x + ",0,0", new ArrayList<>(List.of(lane(0, T, c6, along, along & ~open), lane(1, T, c4, along, along & ~open), lane(2, T, c6, along, along & ~open), lane(3, T, c2, along, along & ~open))));
            lanes.put(x + ",1,0", Boolean.getBoolean("big") ? new ArrayList<>(List.of(lane(0, T, c20, along, along & ~open)))
                    : new ArrayList<>(List.of(lane(0, T, c14, along, along & ~open), lane(2, T, c10, along, along & ~open))));
        }
        int[][] steps = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
        for (var e : lanes.entrySet()) {
            var at = Arrays.stream(e.getKey().split(",")).mapToInt(Integer::parseInt).toArray();
            RackGeometry.Neighbours around = face -> lanes.containsKey((at[0] + steps[face][0]) + "," + (at[1] + steps[face][1]) + "," + (at[2] + steps[face][2])) ? new RackGeometry.Rack(EAST, RackGeometry.STRAIGHT) : null;
            var rack = new RackGeometry.Rack(EAST, RackGeometry.STRAIGHT);
            var frame = RackGeometry.framed(rack, RackGeometry.steel(around, rack, at[0], 0), RackGeometry.beam(e.getValue()));
            for (int i = 0; i < e.getValue().size(); i++)
                if (!RackGeometry.fits(frame, e.getValue().subList(0, i), e.getValue().get(i))) throw new IllegalStateException("Lane does not fit at " + e.getKey());
            frame(frame, e.getValue(), at[0], at[1], at[2]);
            lanes(frame, e.getValue(), at[0], at[1], at[2], at[0]);
        }
    }

    /** One cell of a junction scene (RACK_JUNCTION_PLAN): its rack (facing and shape) and its lanes. */
    private static final class Spot {
        final RackGeometry.Rack rack;
        final List<RackGeometry.Lane> lanes = new ArrayList<>();

        Spot(int facing, int corner) {
            rack = new RackGeometry.Rack(facing, corner);
        }

        Spot add(int position, int level, int code, int arms, int joined) {
            lanes.add(lane(position, level, code, arms, joined));
            return this;
        }
    }

    /**
     * The junction scenes, -Djunction: {@code corner} (a run north turning east), {@code tee} (a run north-south, a branch
     * east: two lines pass behind, two turn north and two south), {@code cross} (a run north-south under a run east-west
     * carried on empty racks, two heights), {@code wide} (two runs side by side turning east, a corner each on the
     * diagonal). Every cell framed by {@link RackGeometry#steel} as in a world, its lanes checked by {@link RackGeometry#fits}.
     */
    private static void junctions(String junction) {
        int c2 = 4, c4 = 7, c6 = 11, S = RackGeometry.STRAIGHT, T = RackGeometry.TIER;
        var spots = new java.util.LinkedHashMap<String, Spot>();
        java.util.function.BiFunction<int[], Spot, Spot> put = (at, spot) -> {
            spots.put(at[0] + "," + at[1] + "," + at[2], spot);
            return spot;
        };
        int ns = j(NORTH, SOUTH), we = j(WEST, EAST);
        switch (junction) {
            case "corner" -> {
                int[] codes = {c6, c4, c6, c2};
                for (int z = 1; z <= 3; z++) {
                    var s = put.apply(new int[]{0, 0, z}, new Spot(NORTH, S));
                    for (int p = 0; p < 4; p++) s.add(p, T, codes[p], ns, z == 3 ? j(NORTH) : ns);
                }
                var c = put.apply(new int[]{0, 0, 0}, new Spot(NORTH, RackGeometry.BACK_RIGHT));
                for (int p = 0; p < 4; p++) c.add(p, T, codes[p], j(SOUTH, EAST), j(SOUTH, EAST));
                for (int x = 1; x <= 3; x++) {
                    var s = put.apply(new int[]{x, 0, 0}, new Spot(EAST, S));
                    for (int p = 0; p < 4; p++) s.add(p, T, codes[p], we, x == 3 ? j(WEST) : we);
                }
            }
            case "tee" -> {
                // The tee faces its branch (east); its run passes from its left (north) to its right (south). Through lines
                // on run positions 0 and 1; branch positions 0 and 1 turn north onto run positions 3 and 2, branch
                // positions 2 and 3 turn south onto 2 and 3.
                int[] branch = {c2, c6, c4, c6};
                for (int z = -3; z <= 3; z++) {
                    if (z == 0) continue;
                    var s = put.apply(new int[]{0, 0, z}, new Spot(NORTH, S));
                    int end = z == -3 ? j(SOUTH) : z == 3 ? j(NORTH) : ns;
                    s.add(0, T, c6, ns, end).add(1, T, c4, ns, end);
                    if (z < 0) s.add(3, T, branch[0], ns, end).add(2, T, branch[1], ns, end);
                    else s.add(2, T, branch[2], ns, end).add(3, T, branch[3], ns, end);
                }
                var t = put.apply(new int[]{0, 0, 0}, new Spot(EAST, RackGeometry.TEE));
                t.add(0, RackGeometry.RUN, c6, ns, ns).add(1, RackGeometry.RUN, c4, ns, ns);
                t.add(0, RackGeometry.TURN_LEFT, branch[0], j(EAST, NORTH), j(EAST, NORTH)).add(1, RackGeometry.TURN_LEFT, branch[1], j(EAST, NORTH), j(EAST, NORTH));
                t.add(2, RackGeometry.TURN_RIGHT, branch[2], j(EAST, SOUTH), j(EAST, SOUTH)).add(3, RackGeometry.TURN_RIGHT, branch[3], j(EAST, SOUTH), j(EAST, SOUTH));
                for (int x = 1; x <= 3; x++) {
                    var s = put.apply(new int[]{x, 0, 0}, new Spot(EAST, S));
                    for (int p = 0; p < 4; p++) s.add(p, T, branch[p], we, x == 3 ? j(WEST) : we);
                }
            }
            case "cross" -> {
                int[] lower = {c6, c4, c6, c2}, upper = {c4, c6, c6, c4};
                for (int z = -2; z <= 2; z++) {
                    var s = put.apply(new int[]{0, 0, z}, new Spot(NORTH, S));
                    for (int p = 0; p < 4; p++) s.add(p, T, lower[p], ns, z == -2 ? j(SOUTH) : z == 2 ? j(NORTH) : ns);
                }
                for (int x = -2; x <= 2; x++) {
                    if (x != 0) put.apply(new int[]{x, 0, 0}, new Spot(EAST, S));
                    var s = put.apply(new int[]{x, 1, 0}, new Spot(EAST, S));
                    for (int p = 0; p < 4; p++) s.add(p, T, upper[p], we, x == -2 ? j(EAST) : x == 2 ? j(WEST) : we);
                }
            }
            case "wide" -> {
                // The outer run: x 0 north from z 3, turning at (0, 0) into z 0 east; the inner: x 1, turning at (1, 1).
                int[] outer = {c6, c4, c6, c2}, inner = {c4, c6, c2, c6};
                for (int run = 0; run < 2; run++) {
                    int[] codes = run == 0 ? outer : inner;
                    for (int z = run + 1; z <= 3; z++) {
                        var s = put.apply(new int[]{run, 0, z}, new Spot(NORTH, S));
                        for (int p = 0; p < 4; p++) s.add(p, T, codes[p], ns, z == 3 ? j(NORTH) : ns);
                    }
                    var c = put.apply(new int[]{run, 0, run}, new Spot(NORTH, RackGeometry.BACK_RIGHT));
                    for (int p = 0; p < 4; p++) c.add(p, T, codes[p], j(SOUTH, EAST), j(SOUTH, EAST));
                    for (int x = run + 1; x <= 3; x++) {
                        var s = put.apply(new int[]{x, 0, run}, new Spot(EAST, S));
                        for (int p = 0; p < 4; p++) s.add(p, T, codes[p], we, x == 3 ? j(WEST) : we);
                    }
                }
            }
            default -> throw new IllegalArgumentException("No junction " + junction);
        }
        int[][] steps = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
        for (var e : spots.entrySet()) {
            var at = Arrays.stream(e.getKey().split(",")).mapToInt(Integer::parseInt).toArray();
            var spot = e.getValue();
            RackGeometry.Neighbours around = face -> {
                var n = spots.get((at[0] + steps[face][0]) + "," + (at[1] + steps[face][1]) + "," + (at[2] + steps[face][2]));
                return n == null ? null : n.rack;
            };
            int along = spot.rack.facing() >= 4 ? at[0] : at[2];
            var frame = RackGeometry.framed(spot.rack, RackGeometry.steel(around, spot.rack, along, 0), RackGeometry.beam(spot.lanes));
            for (int i = 0; i < spot.lanes.size(); i++)
                if (!RackGeometry.fits(frame, spot.lanes.subList(0, i), spot.lanes.get(i))) throw new IllegalStateException("Lane does not fit at " + e.getKey() + ": " + spot.lanes.get(i));
            frame(frame, spot.lanes, at[0], at[1], at[2]);
            lanes(frame, spot.lanes, at[0], at[1], at[2], along);
        }
    }

    /** Points on a pipe polygon at most 0.4/32 m apart, in world block units. */
    private static void sample(Mesh.Polygon polygon, double[] o) {
        double[] p = polygon.positions();
        double step = .4 / 32;
        for (int i = 1; i + 1 < polygon.size(); i++) {
            double[] a = {p[0], p[1], p[2]}, b = {p[3 * i], p[3 * i + 1], p[3 * i + 2]}, c = {p[3 * i + 3], p[3 * i + 4], p[3 * i + 5]};
            double l = Math.max(Math.sqrt(dot(sub(b, a), sub(b, a))), Math.max(Math.sqrt(dot(sub(c, a), sub(c, a))), Math.sqrt(dot(sub(c, b), sub(c, b)))));
            int n = Math.max(1, (int) Math.ceil(l / step));
            for (int s = 0; s <= n; s++)
                for (int t = 0; s + t <= n; t++) {
                    double ws = s / (double) n, wt = t / (double) n, wa = 1 - ws - wt;
                    PIPE_POINTS.add(new double[]{wa * a[0] + ws * b[0] + wt * c[0] + o[0], wa * a[1] + ws * b[1] + wt * c[1] + o[1], wa * a[2] + ws * b[2] + wt * c[2] + o[2]});
                }
        }
    }

    /**
     * -Dclash: pipe surface samples inside steel (a box less 0.05/32 m, -Dclash.eps, a bar's inscribed round) and the
     * closest approach of a pipe to steel (negative: the deepest sample inside).
     */
    private static void clashes() {
        double eps = Double.parseDouble(System.getProperty("clash.eps", ".05")) / 32;
        int count = 0;
        double worst = -Double.MAX_VALUE;
        double[] where = null;
        for (var q : PIPE_POINTS)
            for (var part : STEEL) {
                double depth;
                if (part.length == 2) {
                    double[] lo = part[0], hi = part[1];
                    depth = Double.MAX_VALUE;
                    for (int k = 0; k < 3; k++) depth = Math.min(depth, Math.min(q[k] - lo[k], hi[k] - q[k]));
                } else {
                    double[] a = part[0], b = part[1], ab = sub(b, a);
                    double t = Math.max(0, Math.min(1, dot(sub(q, a), ab) / dot(ab, ab)));
                    double[] near = {a[0] + t * ab[0], a[1] + t * ab[1], a[2] + t * ab[2]}, d = sub(q, near);
                    depth = part[2][0] - Math.sqrt(dot(d, d));
                }
                if (depth > eps) count++;
                if (depth > worst) {
                    worst = depth;
                    where = q;
                }
            }
        System.out.printf("clash: %d pipe samples inside steel of %d samples, %d members; closest approach %.2f/32 m%s%n", count, PIPE_POINTS.size(), STEEL.size(), -worst * 32,
                where == null ? "" : String.format(" at (%.3f, %.3f, %.3f)", where[0], where[1], where[2]));
    }

    /** A plain pipe's face {@code face} meets {@code lane} of {@code rack} in the next cell. */
    private record Meet(int face, RackGeometry.Rack rack, RackGeometry.Lane lane) {
    }

    private static Meet meet(int face, RackGeometry.Rack rack, RackGeometry.Lane lane) {
        return new Meet(face, rack, lane);
    }

    private static int j(int... faces) {
        int out = 0;
        for (int f : faces) out |= 1 << f;
        return out;
    }

    public static void main(String[] args) throws Exception {
        String scene = args[0], out = args[1];
        var views = args.length > 2 ? args[2].split(",") : new String[]{"iso", "iso2", "top", "front"};
        // Sizes in 1/64 m: 2", 4", 6", 14" (two positions), 20" (three), 22" (four, the largest resting), 28" (lowered).
        int c2 = 4, c4 = 7, c6 = 11, c10 = 18, c14 = 23, c20 = 35, c22 = 36, c28 = 45;
        int T = RackGeometry.TIER, A = RackGeometry.ABOVE, B = RackGeometry.BELOW, S = RackGeometry.STRAIGHT, along = j(WEST, EAST);
        switch (scene) {
            case "preview" -> {
                int own = PipeSizes.code(.4), branch = PipeSizes.code(.171875);
                long faces = (long)own << (7*WEST) | (long)own << (7*EAST);
                var straight = new Key(Kind.PIPE, EAST, j(WEST,EAST), own, faces, 0, false);
                place(0,0,0,LineGeometry.polygons(straight));
                var tee = straight.withConnection(UP,branch,0,0);
                place(2,0,0,LineGeometry.polygons(tee));
                var cross = tee.withConnection(NORTH,branch,0,0);
                place(4,0,0,LineGeometry.polygons(cross));
            }
            case "junctions" -> junctions(System.getProperty("junction", "tee"));
            case "proposal" -> proposal(Integer.getInteger("tiers", 2));
            case "flanges" -> flanges();
            case "grid" -> {
                // The layout in -Dlayout (see grid()); each cell's frame printed.
                grid(System.getProperty("layout")).forEach((cell, frame) -> System.out.println(cell + " " + frame));
            }
            case "tier" -> {
                // A run facing east, x 0 to 3: 2" on position 0, 6" on 1, 14" on 2 and 3, each resting on the beams (P2).
                for (int x = 0; x < 4; x++) {
                    int open = (x == 0 ? j(WEST) : 0) | (x == 3 ? j(EAST) : 0);
                    rack(x, 1, 0, EAST, S, x == 0 ? 1 : x == 3 ? 2 : 0, false, lane(0, T, c2, along, along & ~open), lane(1, T, c6, along, along & ~open), lane(2, T, c14, along, along & ~open));
                }
            }
            case "exits" -> {
                // A run facing east, x 0 to 3: position 0 (north) on out of the east end; position 1 up at x 1; position 2 under
                // the tier and out south at x 2; position 3 (south) out flat south at x 3; and a crossing over the tier at x 0.
                var k0 = rack(0, 1, 0, EAST, S, 1, false, lane(0, T, c6, along, j(EAST)), lane(1, T, c4, along, j(EAST)), lane(2, T, c2, along, j(EAST)), lane(3, T, c6, along, j(EAST)),
                        crossing(A, c4, j(NORTH, SOUTH), j(NORTH, SOUTH)));
                var up = lane(1, T, c4, j(WEST, UP), j(WEST, UP));
                var k1 = rack(1, 1, 0, EAST, S, 0, false, lane(0, T, c6, along, along), up, lane(2, T, c2, along, along), lane(3, T, c6, along, along));
                var under = lane(2, B, c2, j(WEST, SOUTH), j(WEST, SOUTH));
                var k2 = rack(2, 1, 0, EAST, S, 0, false, lane(0, T, c6, along, along), under, lane(3, T, c6, along, along));
                var on = lane(0, T, c6, along, along);
                var flat = lane(3, T, c6, j(WEST, SOUTH), j(WEST, SOUTH));
                var k3 = rack(3, 1, 0, EAST, S, 2, false, on, flat);
                pipe(4, 1, 0, c6, j(WEST, EAST), meet(WEST, k3, on));
                pipe(5, 1, 0, c6, j(WEST));
                pipe(1, 2, 0, c4, j(DOWN, UP), meet(DOWN, k1, up));
                pipe(1, 3, 0, c4, j(DOWN));
                pipe(2, 1, 1, c2, j(NORTH, DOWN), meet(NORTH, k2, under));
                pipe(2, 0, 1, c2, j(UP));
                pipe(3, 1, 1, c6, j(NORTH, SOUTH), meet(NORTH, k3, flat));
                pipe(3, 1, 2, c6, j(NORTH));
                var cross = crossing(A, c4, j(NORTH, SOUTH), j(NORTH, SOUTH));
                pipe(0, 1, -1, c4, j(SOUTH, NORTH), meet(SOUTH, k0, cross));
                pipe(0, 1, -2, c4, j(SOUTH));
                pipe(0, 1, 1, c4, j(NORTH, SOUTH), meet(NORTH, k0, cross));
                pipe(0, 1, 2, c4, j(NORTH));
            }
            case "corner" -> {
                // A run facing east, x 0 and 1; a corner at x 2 turning right (south); a run facing south, z 1 and 2. The four
                // lanes bend about the inner corner and keep their order.
                int[] codes = {c2, c4, c6, c6};
                for (int x = 0; x < 2; x++) {
                    var lanes = new RackGeometry.Lane[4];
                    for (int p = 0; p < 4; p++) lanes[p] = lane(p, T, codes[p], along, x == 0 ? j(EAST) : along);
                    rack(x, 1, 0, EAST, S, x == 0 ? 1 : 0, false, lanes);
                }
                var turning = new RackGeometry.Lane[4];
                for (int p = 0; p < 4; p++) turning[p] = lane(p, T, codes[p], j(WEST, SOUTH), j(WEST, SOUTH));
                rack(2, 1, 0, EAST, RackGeometry.BACK_RIGHT, 0, false, turning);
                for (int z = 1; z < 3; z++) {
                    var lanes = new RackGeometry.Lane[4];
                    for (int p = 0; p < 4; p++) lanes[p] = lane(p, T, codes[p], j(NORTH, SOUTH), z == 2 ? j(NORTH) : j(NORTH, SOUTH));
                    rack(2, 1, z, SOUTH, S, z == 2 ? 2 : 0, false, lanes);
                }
            }
            case "lowered" -> {
                // North, z 0: a 28" line on lowered beams, out of the east end into a transition pipe. South, z 2: a 22" line
                // resting on the beams, down at x 2 past the front bent's beam.
                for (int x = 0; x < 3; x++) rack(x, 1, 0, EAST, S, x == 0 ? 1 : x == 2 ? 2 : 0, false, lane(0, T, c28, along, x == 0 ? j(EAST) : along));
                var big = lane(0, T, c28, along, along);
                pipe(3, 1, 0, c28, j(WEST, EAST), meet(WEST, new RackGeometry.Rack(EAST, S, 0, RackGeometry.beam(List.of(big))), big));
                pipe(4, 1, 0, c28, j(WEST));
                for (int x = 0; x < 2; x++) rack(x, 1, 2, EAST, S, x == 0 ? 1 : 0, false, lane(0, T, c22, along, x == 0 ? j(EAST) : along));
                var down = lane(0, T, c22, j(WEST, DOWN), j(WEST, DOWN));
                var k = rack(2, 1, 2, EAST, S, 2, false, down);
                pipe(2, 0, 2, c22, j(UP, DOWN), meet(UP, k, down));
                pipe(2, -1, 2, c22, j(UP));
            }
            case "stacked" -> {
                // Two tiers facing north, z 0 to 2: the lower stacked (its posts run up to the upper's), 6" and 10" lines; the
                // upper a 4" and a 14" line.
                for (int z = 0; z < 3; z++) {
                    int bents = z == 2 ? 1 : z == 0 ? 2 : 0, ns = j(NORTH, SOUTH), open = (z == 0 ? j(NORTH) : 0) | (z == 2 ? j(SOUTH) : 0);
                    rack(0, 1, z, NORTH, S, bents, true, lane(0, T, c6, ns, ns & ~open), lane(2, T, c10, ns, ns & ~open));
                    rack(0, 2, z, NORTH, S, bents, false, lane(0, T, c4, ns, ns & ~open), lane(2, T, c14, ns, ns & ~open));
                }
            }
            case "widths" -> {
                // Racks facing south, one per width from x 6(w - 1), the largest line of each width (and a 28" on its lowered
                // beam) on position 0, out of the front into a transition pipe that turns east.
                int[] codes = {c6, c14, c20, c22, c28};
                for (int i = 0; i < codes.length; i++) {
                    int x = 6 * i, code = codes[i], ns = j(NORTH, SOUTH);
                    rack(x, 1, 0, SOUTH, S, 1, false, lane(0, T, code, ns, j(SOUTH)));
                    var out1 = lane(0, T, code, ns, ns);
                    var k = rack(x, 1, 1, SOUTH, S, 2, false, out1);
                    pipe(x, 1, 2, code, j(NORTH, EAST), meet(NORTH, k, out1));
                    pipe(x + 1, 1, 2, code, j(WEST));
                }
            }
            default -> throw new IllegalArgumentException("No scene " + scene);
        }
        if (CLASH) clashes();
        var sprites = sprites();
        new File(out).getAbsoluteFile().getParentFile().mkdirs();
        for (var view : views) {
            double[] depth = switch (view) {
                case "front" -> new double[]{0, 0, 1};
                case "back" -> new double[]{0, 0, -1};
                case "left" -> new double[]{-1, 0, 0};
                case "right" -> new double[]{1, 0, 0};
                case "top" -> new double[]{0, -1, 0};
                case "iso" -> new double[]{-1, -.75, 1.15};
                case "iso2" -> new double[]{1, -.75, 1.15};
                case "iso3" -> new double[]{-1, -.75, -1.15};
                case "low" -> new double[]{-1, -.12, 1.3};
                default -> throw new IllegalArgumentException("No view " + view);
            };
            render(TRIANGLES, unit(depth), sprites, out + "-" + view + ".png");
            System.out.println("wrote " + out + "-" + view + ".png");
        }
    }

    /** The sprites by material, from the shell model's texture names. */
    private static Map<Mesh.Material, Sprite> sprites() throws Exception {
        String json = Files.readString(Path.of(ASSETS + "models/block/equipment_shell.json"));
        var names = Pattern.compile("\"(\\w+)\"\\s*:\\s*\"icheme:block/(\\w+)\"").matcher(json);
        var files = new java.util.HashMap<String, String>();
        while (names.find()) files.put(names.group(1), names.group(2));
        var out = new EnumMap<Mesh.Material, Sprite>(Mesh.Material.class);
        for (var material : Mesh.Material.values()) {
            String file = files.get(material.texture());
            if (file == null) continue;
            var image = ImageIO.read(new File(ASSETS + "textures/block/" + file + ".png"));
            int w = image.getWidth(), h = image.getHeight() > w && image.getHeight() % w == 0 ? w : image.getHeight();
            int[] argb = new int[w * h];
            image.getRGB(0, 0, w, h, argb, 0, w);
            out.put(material, new Sprite(w, h, argb));
        }
        // -Dpaint=RRGGBB: a painted line, as the block tint draws it (Paint.tint on the neutral painted sprites); its flanges
        // in -Dsecondary=RRGGBB, else in the paint, as the block reads a pipe without a secondary paint.
        var paint = System.getProperty("paint");
        if (paint != null) {
            var paints = java.util.Map.of(Mesh.Material.PIPE, paint, Mesh.Material.FLANGE, System.getProperty("secondary", paint));
            for (var entry : java.util.Map.of(Mesh.Material.PIPE, "painted_pipe", Mesh.Material.FLANGE, "painted_flange").entrySet()) {
                int tint = com.wormzjl.icheme.science.equipment.Paint.tint(Integer.parseInt(paints.get(entry.getKey()), 16));
                var image = ImageIO.read(new File(ASSETS + "textures/block/" + entry.getValue() + ".png"));
                int w = image.getWidth(), h = image.getHeight();
                int[] argb = new int[w * h];
                image.getRGB(0, 0, w, h, argb, 0, w);
                for (int i = 0; i < argb.length; i++) {
                    int p = argb[i], r = (p >> 16 & 255) * (tint >> 16 & 255) / 255, g = (p >> 8 & 255) * (tint >> 8 & 255) / 255, b = (p & 255) * (tint & 255) / 255;
                    argb[i] = p & 0xFF000000 | r << 16 | g << 8 | b;
                }
                out.put(entry.getKey(), new Sprite(w, h, argb));
            }
        }
        return out;
    }

    private static void add(List<Triangle> out, Mesh.Polygon polygon, double x, double y, double z) {
        double[] p = polygon.positions(), uv = polygon.uvs();
        int n = polygon.size();
        for (int i = 1; i + 1 < n; i++) {
            int[] c = {0, i, i + 1};
            double[][] pts = new double[3][], uvs = new double[3][];
            for (int k = 0; k < 3; k++) {
                pts[k] = new double[]{p[3 * c[k]] + x, p[3 * c[k] + 1] + y, p[3 * c[k] + 2] + z};
                uvs[k] = new double[]{uv[2 * c[k]], uv[2 * c[k] + 1]};
            }
            var normal = cross(sub(pts[1], pts[0]), sub(pts[2], pts[0]));
            double l = Math.sqrt(dot(normal, normal));
            if (l < 1e-12) continue;
            out.add(new Triangle(pts, uvs, new double[]{normal[0] / l, normal[1] / l, normal[2] / l}, polygon.material()));
        }
    }

    /** The game's directional shade of a face normal (NeoForge {@code QuadLighter.calculateShade}, no constant ambient). */
    private static double shade(double[] n) {
        return Math.min(n[0] * n[0] * .6 + n[1] * n[1] * ((3 + n[1]) / 4) + n[2] * n[2] * .8, 1);
    }

    private static void render(List<Triangle> triangles, double[] depth, Map<Mesh.Material, Sprite> sprites, String file) throws Exception {
        double[] right = unit(Math.abs(depth[1]) > .99 ? new double[]{1, 0, 0} : cross(depth, new double[]{0, 1, 0}));
        double[] up = Math.abs(depth[1]) > .99 ? new double[]{0, 0, -1} : cross(right, depth);
        int gx0 = lowX - 1, gz0 = lowZ - 1, gx1 = highX + 1, gz1 = highZ + 1;
        double minX = 1e9, maxX = -1e9, minY = 1e9, maxY = -1e9;
        var corners = new ArrayList<double[]>();
        for (var t : triangles) corners.addAll(Arrays.asList(t.p));
        for (int x : new int[]{gx0, gx1})
            for (int z : new int[]{gz0, gz1}) corners.add(new double[]{x, 0, z});
        for (var v : corners) {
            minX = Math.min(minX, dot(v, right));
            maxX = Math.max(maxX, dot(v, right));
            minY = Math.min(minY, dot(v, up));
            maxY = Math.max(maxY, dot(v, up));
        }
        double scale = Math.max(10, Math.min(160, 1400 / Math.max(maxX - minX, maxY - minY)));
        int w = (int) Math.ceil((maxX - minX) * scale) + 20, h = (int) Math.ceil((maxY - minY) * scale) + 20;
        var image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var zbuf = new double[w * h];
        Arrays.fill(zbuf, Double.MAX_VALUE);
        for (int i = 0; i < w * h; i++) image.setRGB(i % w, i / w, 0x9CC3F5);
        double fx = minX, fy = minY;
        Rasterizer raster = (a, b, c, colour) -> {
            double[][] s = new double[3][];
            int k = 0;
            for (var v : new double[][]{a, b, c}) s[k++] = new double[]{(dot(v, right) - fx) * scale + 10, h - ((dot(v, up) - fy) * scale + 10), dot(v, depth)};
            int x0 = (int) Math.max(0, Math.floor(Math.min(s[0][0], Math.min(s[1][0], s[2][0])))), x1 = (int) Math.min(w - 1, Math.ceil(Math.max(s[0][0], Math.max(s[1][0], s[2][0]))));
            int y0 = (int) Math.max(0, Math.floor(Math.min(s[0][1], Math.min(s[1][1], s[2][1])))), y1 = (int) Math.min(h - 1, Math.ceil(Math.max(s[0][1], Math.max(s[1][1], s[2][1]))));
            double area = edge(s[0], s[1], s[2][0], s[2][1]);
            if (Math.abs(area) < 1e-12) return;
            for (int y = y0; y <= y1; y++)
                for (int x = x0; x <= x1; x++) {
                    double px = x + .5, py = y + .5;
                    double w0 = edge(s[1], s[2], px, py) / area, w1 = edge(s[2], s[0], px, py) / area, w2 = edge(s[0], s[1], px, py) / area;
                    if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                    double z = w0 * s[0][2] + w1 * s[1][2] + w2 * s[2][2];
                    if (z >= zbuf[y * w + x]) continue;
                    int rgb = colour.at(w0, w1, w2);
                    if (rgb < 0) continue;
                    zbuf[y * w + x] = z;
                    image.setRGB(x, y, rgb);
                }
        };
        for (int x = gx0; x < gx1; x++)
            for (int z = gz0; z < gz1; z++) {
                double[] a = {x, -.001, z}, b = {x + 1, -.001, z}, c = {x + 1, -.001, z + 1}, d = {x, -.001, z + 1};
                int fx0 = x, fz0 = z;
                Colour grass = (w0, w1, w2) -> 0x6A9A3C;
                raster.fill(a, b, c, grass);
                raster.fill(a, c, d, grass);
                double t = .03;
                for (double[][] line : new double[][][]{{{fx0, -.0005, fz0}, {fx0 + 1, -.0005, fz0}, {fx0 + 1, -.0005, fz0 + t}, {fx0, -.0005, fz0 + t}},
                        {{fx0, -.0005, fz0}, {fx0 + t, -.0005, fz0}, {fx0 + t, -.0005, fz0 + 1}, {fx0, -.0005, fz0 + 1}}}) {
                    Colour dark = (w0, w1, w2) -> 0x557D30;
                    raster.fill(line[0], line[1], line[2], dark);
                    raster.fill(line[0], line[2], line[3], dark);
                }
            }
        for (var t : triangles) {
            if (dot(t.normal, depth) >= 0) continue; // back face: culled as the chunk renderer culls it
            var sprite = sprites.get(t.material);
            double light = shade(t.normal);
            int channel = t.material.channel();
            int tint = channel < 0 ? 0xFFFFFF : Paint.DEFAULT.colour(channel);
            double tr = Math.min(255, (tint >> 16 & 255) * 255.0 / Paint.SPRITE_VALUE) / 255, tg = Math.min(255, (tint >> 8 & 255) * 255.0 / Paint.SPRITE_VALUE) / 255,
                    tb = Math.min(255, (tint & 255) * 255.0 / Paint.SPRITE_VALUE) / 255;
            if (channel < 0) tr = tg = tb = 1;
            double ftr = tr, ftg = tg, ftb = tb;
            raster.fill(t.p[0], t.p[1], t.p[2], (w0, w1, w2) -> {
                int texel = 0xFFFF00FF;
                if (sprite != null) {
                    double u = w0 * t.uv[0][0] + w1 * t.uv[1][0] + w2 * t.uv[2][0], v = w0 * t.uv[0][1] + w1 * t.uv[1][1] + w2 * t.uv[2][1];
                    texel = sprite.sample(u, v);
                    if ((texel >>> 24) < 128) return -1;
                }
                int r = (int) Math.min(255, (texel >> 16 & 255) * ftr * light), g = (int) Math.min(255, (texel >> 8 & 255) * ftg * light),
                        b = (int) Math.min(255, (texel & 255) * ftb * light);
                return r << 16 | g << 8 | b;
            });
        }
        ImageIO.write(image, "png", new File(file));
    }

    private interface Colour {
        int at(double w0, double w1, double w2);
    }

    private interface Rasterizer {
        void fill(double[] a, double[] b, double[] c, Colour colour);
    }

    private static double edge(double[] a, double[] b, double x, double y) {
        return (b[0] - a[0]) * (y - a[1]) - (b[1] - a[1]) * (x - a[0]);
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double[] sub(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] unit(double[] a) {
        double l = Math.sqrt(dot(a, a));
        return new double[]{a[0] / l, a[1] / l, a[2] / l};
    }
}

