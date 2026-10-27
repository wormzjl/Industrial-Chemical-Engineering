package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.math.DeterministicMath;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.wormzjl.icheme.science.equipment.Mesh.*;

/**
 * Turns an {@link EquipmentShape} into what the world needs, one block cell at a time: which cells hold the shell and
 * which the interior ({@link #layout}), and for a shell cell its surface polygons ({@link #surface}) and its collision
 * solid ({@link #solid}). Every polygon is clipped to its own cell, so a chunk renderer can bake each cell like any
 * block and a vessel needs no lookup across cells or chunks (batch 2026-10-15-custom-equipment-rendering, route A1).
 *
 * <p>The vessel is built in its own frame ({@link EquipmentShape}), axis along +y: a profile of revolution (skirt or
 * bottom head, cylinder, top head), a skirt's base ring, anchor chairs and access opening (a drum's skirt hollow, with
 * slots, its inside drawn), and, lying down, two saddles below it. {@link EquipmentShape#transform()} then turns it into
 * the box; a rotation keeps every polygon counter-clockwise seen from outside. A turned bottom outlet (owner request
 * 2026-10-25) turns its port, and the slots keep to the other three sides.
 *
 * <p>A unit ({@link EquipmentShape.Kind#unit()}) has no body of revolution: its parts ({@link ProcessUnit}) give its
 * facets, collision and interior, and every cell it reaches is its own.
 *
 * <p>Nozzles are built apart: their drawn size follows the pipe connected to them (owner decision 2026-10-16), which the
 * cell's {@link CellKey#nozzles()} carries as 1/64 m codes, seven bits for each nozzle reaching that cell, in nozzle order
 * (0: unconnected, drawn at its default size under a blind flange; {@link #cellCodes}), so a column takes any number of
 * nozzles. The cells a nozzle may touch are those of its largest size, so the layout never depends on what is connected.
 *
 * <p>Texture follows a fabricated vessel. The shell is rolled plate in {@link #COURSE} courses from the bottom tangent
 * line, each course closed by plates about {@link #PLATE_LENGTH} long fitted to the circumference; one shell sprite
 * holds two courses with staggered seams. Heads are pressed in one piece: their sprite runs once from the tangent line
 * weld to the apex. Supports carry a plain painted sprite repeating every {@link #SUPPORT_TILE}.
 *
 * <p>A cell wholly inside the cylindrical shell or skirt, clear of the skirt base, the opening, the slots, a hollow
 * skirt's bottom head, the saddles and every nozzle, has the same geometry at every position along the axis and a texture that repeats every two courses; its
 * {@link CellKey} carries {@link CellKey#BODY} minus its texture class instead of that coordinate, so callers that cache
 * by key build it once per class (at most eight).
 */
public final class ShapeRasterizer {
    /** Collision solid resolution, in parts of a block: 1/8 gives 4 to 11 boxes per shell cell on average (report section 4). */
    public static final int SOLID_RESOLUTION = 8;
    /** Texture layout, m: a shell course, the nominal shell plate length, a support sprite's repeat. */
    public static final double COURSE = 2, PLATE_LENGTH = 4, SUPPORT_TILE = 2;
    /** A drum skirt's vent sleeve, outside diameter, m (photo survey B: 2 to 4 in sleeves under the head). */
    static final double VENT = .1;
    /** Bits per nozzle in a cell key, and the most nozzles that may reach one cell. */
    private static final int CODE_BITS = 7, KEYED_NOZZLES = 9;
    private static final int GEOMETRY_CACHE = 64, GEOMETRY_FACETS = 300_000;
    private static final Map<EquipmentShape, Geometry> GEOMETRIES = new ConcurrentHashMap<>();
    /**
     * Whether geometries keep their facets to draw cells ({@link #surface}, {@link #facetArea}). A dedicated server never draws:
     * once a vessel's layout is known it keeps only the cell tables, the ports and the solid test ({@link #serverOnly}).
     */
    private static volatile boolean drawing = true;

    /**
     * For a dedicated server, at startup: geometries drop their facets once laid out (about 750 bytes each, tens of
     * thousands on a large column), so the cache holds only what layout, keys, ports and collision read.
     */
    public static void serverOnly() {
        drawing(false);
    }

    /** Whether geometries keep their facets from now on; the cache starts afresh. */
    static void drawing(boolean on) {
        drawing = on;
        GEOMETRIES.clear();
    }

    public record Cell(int x, int y, int z) {
    }

    /** Shell and interior cells of a vessel in x-major, then y, then z order; every other cell of its box is outside. */
    public record Layout(EquipmentShape shape, List<Cell> shell, List<Cell> interior) {
        public Layout {
            shell = List.copyOf(shell);
            interior = List.copyOf(interior);
        }

        /**
         * The vessel's controller: its shell cell in the lowest row, then westmost, then northmost (owner decision
         * 2026-10-15: the controller is a role of one shell cell, not a block of its own).
         */
        public Cell controller() {
            Cell best = shell.getFirst();
            for (var cell : shell)
                if (cell.y() < best.y() || cell.y() == best.y() && (cell.x() < best.x() || cell.x() == best.x() && cell.z() < best.z())) best = cell;
            return best;
        }
    }

    /**
     * Geometry key of one shell cell. Its coordinate along the vessel's axis is {@link #BODY} minus a texture class (0 to
     * 7) where the cell lies wholly in the cylindrical shell or skirt; all such cells of one class are identical.
     * {@code nozzles} holds the drawn size codes of the nozzles that reach this cell, seven bits each in nozzle order.
     */
    public record CellKey(EquipmentShape shape, int x, int y, int z, long nozzles) {
        public static final int BODY = -1;
    }

    /** Solid box in units of 1/{@link #SOLID_RESOLUTION} block, lower corner inclusive, upper exclusive. */
    public record Box(int x0, int y0, int z0, int x1, int y1, int z1) {
    }

    /**
     * A nozzle as a fluid port: the cell holding its flange face, the box direction a pipe meets it from (the neighbour
     * at {@code cell + (dx, dy, dz)}), its port class and its index among the shape's nozzles.
     */
    public record Port(Cell cell, int dx, int dy, int dz, EquipmentShape.PortClass port, int nozzle) {
    }

    private ShapeRasterizer() {
    }

    public static Layout layout(EquipmentShape shape) {
        return geometry(shape).layout;
    }

    /** The key of a cell with every nozzle unconnected. */
    public static CellKey key(EquipmentShape shape, int x, int y, int z) {
        return key(shape, x, y, z, 0);
    }

    /** The key of a cell whose reaching nozzles are drawn at {@code codes}, as {@link #cellCodes} packs them. */
    public static CellKey key(EquipmentShape shape, int x, int y, int z, long codes) {
        var g = geometry(shape);
        int[] cell = {x, y, z};
        int reaching = g.reaching(new Cell(x, y, z)).length;
        long kept = reaching == 0 ? 0 : codes & (-1L >>> (64 - CODE_BITS * reaching));
        int bodyClass = g.bodyClass(cell[g.axial]);
        if (bodyClass >= 0 && reaching == 0 && !g.irregularColumns.contains(column(x, z))) cell[g.axial] = CellKey.BODY - bodyClass;
        return new CellKey(shape, cell[0], cell[1], cell[2], kept);
    }

    /**
     * The codes a cell's key carries for nozzles drawn at {@code outside} (m, by nozzle index; 0: unconnected): seven
     * bits for each nozzle reaching the cell, in nozzle order.
     */
    public static long cellCodes(EquipmentShape shape, Cell cell, double[] outside) {
        long codes = 0;
        var reaching = geometry(shape).reaching(cell);
        for (int j = 0; j < reaching.length; j++) {
            double od = outside[reaching[j]];
            codes |= (long) (od <= 0 ? 0 : PipeSizes.code(od)) << (CODE_BITS * j);
        }
        return codes;
    }

    public static List<Polygon> surface(CellKey key) {
        int[] c = cell(key);
        return surface(key.shape(), c[0], c[1], c[2], key.nozzles(), true);
    }

    /**
     * A cell's surface without its moving parts' facets ({@link ProcessUnit.Motion}): the chunk mesh; the client draws
     * those parts, moving or at rest.
     */
    public static List<Polygon> fixedSurface(CellKey key) {
        int[] c = cell(key);
        return surface(key.shape(), c[0], c[1], c[2], key.nozzles(), false);
    }

    /**
     * A unit's moving part in the box: its motion (pivot and axis in box coordinates), its polygons relative to the pivot,
     * cut at the cell grid as the chunk mesh cuts them (the moving part shows the still one's texels), and its farthest
     * point from the pivot.
     */
    public record Moving(ProcessUnit.Motion motion, List<Polygon> polygons, double reach) {
    }

    /** A unit's moving parts in the box, by index ({@link ProcessUnit.Motion}); none for a vessel, a column or a unit without any. */
    public static List<Moving> moving(EquipmentShape shape) {
        if (!drawing) throw new IllegalStateException("A dedicated server keeps no surfaces");
        return geometry(shape).moving();
    }

    /**
     * A floating-roof tank's client-drawn parts in the box (plan section 3.4), cut at the cell grid as the chunk mesh cuts
     * them: the roof's polygons where it lies landed (the client lifts them by {@link #underside} less {@code landed}), the
     * rolling ladder's relative to its {@code pivot}, drawn level along {@code along} (the client turns them down by
     * {@link #pitch} about {@code axis}); the sight glass's column at {@code glass} (x, z) from {@code glassLow} to
     * {@code glassHigh}, its front facing {@code facing}. Heights are box y, a liquid {@code level} m deep over the floor.
     */
    public record Floating(List<Polygon> roof, List<Polygon> ladder, double landed, double top, double deck, double[] pivot, double[] along, double[] axis,
                           double length, double runway, double[] glass, double glassLow, double glassHigh, double[] facing) {
        /** The roof's underside for a liquid {@code level} m deep: on the liquid, between its legs' height and the fill height. */
        public double underside(double level) {
            return frame().underside(level);
        }

        /** The ladder's turn down from level, radians, its foot on the roof's deck. */
        public double pitch(double level) {
            return frame().pitch(level);
        }

        private ProcessUnit.Floating frame() {
            return new ProcessUnit.Floating(landed, top, deck, pivot, along, axis, length, runway, glass, glassLow, glassHigh, facing);
        }
    }

    /** A floating-roof tank's client-drawn parts in the box, or null for any other shape. */
    public static @Nullable Floating floating(EquipmentShape shape) {
        if (!drawing) throw new IllegalStateException("A dedicated server keeps no surfaces");
        return geometry(shape).floating();
    }

    /**
     * A floating-roof tank's roof as the world collides with it (owner request 2026-10-21: a player on the roof rides it),
     * in box coordinates, kept on a dedicated server too: its axis ({@code x}, {@code z}), its outer {@code radius} and its
     * deck's {@code inner} one, the heights of the deck and of the pontoon ring's top over the roof's underside, where the
     * underside stands ({@link #underside}, as {@link Floating#underside}), and the rolling ladder: its hinge {@code
     * pivot}, its level direction {@code along} and its {@code length}, turned down by {@link #pitch}.
     */
    public record Roof(double x, double z, double radius, double inner, double deck, double pontoon, double landed, double top, double[] pivot,
                       double[] along, double length) {
        /** The roof's underside over the floor for a liquid {@code level} m deep: on the liquid, between its legs' and the fill height. */
        public double underside(double level) {
            return Math.max(landed, Math.min(top, level));
        }

        /** The walking surface over the floor at {@code distance} m from the axis for a liquid {@code level} m deep: the deck
         * inside the inner radius, the pontoon ring's top beyond it. */
        public double surface(double distance, double level) {
            return underside(level) + (distance < inner ? deck : pontoon);
        }

        /** The ladder's turn down from level, radians, its foot on the deck ({@link Floating#pitch}). */
        public double pitch(double level) {
            double drop = pivot[1] - (underside(level) + deck);
            return drop >= length ? Math.PI / 2 : drop <= 0 ? 0 : DeterministicMath.asin(drop / length);
        }
    }

    /** A floating-roof tank's {@link Roof}, or null for any other shape. */
    public static @Nullable Roof roof(EquipmentShape shape) {
        return geometry(shape).roof();
    }

    /** Greedy merge of the cell's solid sampled at {@link #SOLID_RESOLUTION}: along z, then x, then y. */
    public static List<Box> solid(CellKey key) {
        var g = geometry(key.shape());
        int s = SOLID_RESOLUTION;
        int[] c = cell(key);
        var filled = new boolean[s][s][s];
        for (int a = 0; a < s; a++)
            for (int b = 0; b < s; b++)
                for (int d = 0; d < s; d++)
                    filled[a][b][d] = g.inside(c[0] + (a + .5) / s, c[1] + (b + .5) / s, c[2] + (d + .5) / s, key.nozzles());
        var used = new boolean[s][s][s];
        var boxes = new ArrayList<Box>();
        for (int x = 0; x < s; x++)
            for (int y = 0; y < s; y++)
                for (int z = 0; z < s; z++) {
                    if (!filled[x][y][z] || used[x][y][z]) continue;
                    int z1 = z + 1;
                    while (z1 < s && filled[x][y][z1] && !used[x][y][z1]) z1++;
                    int x1 = x + 1;
                    while (x1 < s && run(filled, used, x1, x1 + 1, y, y + 1, z, z1)) x1++;
                    int y1 = y + 1;
                    while (y1 < s && run(filled, used, x, x1, y1, y1 + 1, z, z1)) y1++;
                    for (int a = x; a < x1; a++) for (int b = y; b < y1; b++) for (int d = z; d < z1; d++) used[a][b][d] = true;
                    boxes.add(new Box(x, y, z, x1, y1, z1));
                }
        return List.copyOf(boxes);
    }

    /** Whether a box-local point lies inside the faceted solid (vessel, supports, unconnected nozzles, a column's decks and rails). */
    public static boolean inside(EquipmentShape shape, double x, double y, double z) {
        return geometry(shape).inside(x, y, z, 0);
    }

    /** Whether a box-local point lies in the vessel, its supports or an unconnected nozzle: the solid without a column's access structure. */
    static boolean insideVessel(EquipmentShape shape, double x, double y, double z) {
        var g = geometry(shape);
        var p = g.transform.local(new double[]{x, y, z});
        if (g.inVessel(p)) return true;
        for (int k = 0; k < g.nozzles.size(); k++) if (g.inNozzle(p, k, 0)) return true;
        return false;
    }

    /**
     * Whether a frame point lies where it hides nozzle {@code k}'s neck: in the solid without its nozzles, in that nozzle's
     * own root at size {@code code} (0: its design size), or in another nozzle at its design size.
     */
    static boolean hides(EquipmentShape shape, int k, int code, double[] p) {
        var g = geometry(shape);
        if (g.solid(p)) return true;
        for (int j = 0; j < g.nozzles.size(); j++) if (g.inNozzle(p, j, j == k ? code : 0)) return true;
        return false;
    }

    /** Whether a box-local point lies where one of a column's or a heater's ladders is climbed. */
    public static boolean climbable(EquipmentShape shape, double x, double y, double z) {
        var p = geometry(shape).transform.local(new double[]{x, y, z});
        for (var climb : geometry(shape).climbs()) if (climb.climbable(p)) return true;
        return false;
    }

    /** The shape's ladders in its frame: a column's or a unit's (a storage vessel's too); none for a drum. */
    static List<Access.Climb> climbs(EquipmentShape shape) {
        return geometry(shape).climbs();
    }

    /** A box column (x, z) of a standing vessel as one number. */
    private static long column(int x, int z) {
        return (long) x << 32 | z & 0xffffffffL;
    }

    /**
     * The selection outline seen from the box-local point {@code (x, y, z)}, as box-local segments {x0, y0, z0, x1, y1,
     * z1}. The vessel's silhouette against that eye: the two meridians, end to end, where the shell turns away from it
     * (seen along the axis, the shell's rim instead). Fixed meridians drew a line down the middle of a drum (owner report
     * 2026-10-16). Then the fixed edges: a skirt's base ring, a column's decks, each saddle's main edges and each nozzle's
     * flange face.
     */
    public static List<double[]> outline(EquipmentShape shape, double x, double y, double z) {
        var g = geometry(shape);
        var out = new ArrayList<>(g.edges());
        g.silhouette(new double[]{x, y, z}, out);
        return out;
    }

    /**
     * The stamp of the shape's layout (owner request 2026-10-18: a placed vessel follows its kind's current drawing):
     * a 64-bit FNV-1a hash of its box, its shell and interior cells and its ports, never 0. A shell cell keeps the stamp
     * it was placed with; another one on load means the vessel's cells in the world no longer match the layout the code
     * draws ({@link EquipmentRefresh}). The drawing of a cell needs no stamp: it follows the code at every load.
     */
    public static long stamp(EquipmentShape shape) {
        var g = geometry(shape);
        long stamp = g.stamp;
        if (stamp != 0) return stamp;
        long h = 0xcbf29ce484222325L;
        h = mix(mix(mix(h, shape.sizeX()), shape.sizeY()), shape.sizeZ());
        for (var cells : List.of(g.layout.shell(), g.layout.interior())) {
            for (var cell : cells) h = mix(mix(mix(h, cell.x()), cell.y()), cell.z());
            h = mix(h, -1);
        }
        for (var port : g.ports)
            h = mix(mix(mix(mix(mix(mix(mix(mix(h, port.cell().x()), port.cell().y()), port.cell().z()), port.dx()), port.dy()), port.dz()), port.port().ordinal()), port.nozzle());
        g.stamp = stamp = h == 0 ? 1 : h;
        return stamp;
    }

    /** One FNV-1a step over the four bytes of {@code v}. */
    private static long mix(long h, int v) {
        for (int i = 0; i < 4; i++) h = (h ^ (v >>> 8 * i & 0xff)) * 0x100000001b3L;
        return h;
    }

    /** The nozzles as fluid ports, in the order of {@link EquipmentShape#nozzles()}. */
    public static List<Port> ports(EquipmentShape shape) {
        return geometry(shape).ports;
    }

    /** The cells nozzle {@code k} may reach at its largest size: those whose keys carry its size. */
    public static Set<Cell> nozzleCells(EquipmentShape shape, int k) {
        return geometry(shape).nozzleCells.get(k);
    }

    /**
     * A drum's shell cell at its lowest inside point, where its fluid node stands: the bottom apex, or a lying drum's
     * bottom line; a mover's suction nozzle cell; null for other columns and units.
     */
    public static @Nullable Cell fluidCell(EquipmentShape shape) {
        return geometry(shape).fluidCell;
    }

    /** Every node cell of {@code shape}, one per registration: a heat unit's in its sides' order ({@link HeatSides#of}), then a kettle's pool's. */
    public static List<Cell> fluidCells(EquipmentShape shape) {
        return geometry(shape).fluidCells;
    }

    /** Surface polygons of the cell at its actual position, without key normalization; {@code codes} as {@link #cellCodes}. */
    static List<Polygon> surface(EquipmentShape shape, int x, int y, int z, long codes) {
        return surface(shape, x, y, z, codes, true);
    }

    /** Surface polygons of the cell, with or without the {@code moving} parts' facets. */
    private static List<Polygon> surface(EquipmentShape shape, int x, int y, int z, long codes, boolean moving) {
        if (!drawing) throw new IllegalStateException("A dedicated server keeps no surfaces");
        var g = geometry(shape);
        var out = new ArrayList<Polygon>();
        var cell = new Cell(x, y, z);
        for (var facet : g.facetsIn(cell)) if (moving || facet.part() < 0) g.mesh.cell(facet, x, y, z, out);
        var reaching = g.reaching(cell);
        for (int j = 0; j < reaching.length; j++) {
            var mesh = g.nozzle(reaching[j], code(codes, j));
            for (var facet : mesh.facets()) mesh.cell(facet, x, y, z, out);
        }
        return List.copyOf(out);
    }

    /** Total area of the vessel's facets with every nozzle unconnected, m²: what the cells' surface pieces must add up to. */
    static double facetArea(EquipmentShape shape) {
        return geometry(shape).mesh.area() + nozzleArea(shape);
    }

    /** Area of the nozzles drawn unconnected, m². */
    static double nozzleArea(EquipmentShape shape) {
        var g = geometry(shape);
        double total = 0;
        for (int k = 0; k < g.nozzleCells.size(); k++) total += g.nozzle(k, 0).area();
        return total;
    }

    /** The code of the {@code j}-th nozzle reaching a cell. */
    private static int code(long codes, int j) {
        return (int) (codes >>> (CODE_BITS * j) & 127);
    }

    /** The cell a key stands for: a body key stands for the first body cell of its class along the axis. */
    private static int[] cell(CellKey key) {
        var g = geometry(key.shape());
        int[] c = {key.x(), key.y(), key.z()};
        if (c[g.axial] <= CellKey.BODY) c[g.axial] = g.representatives[CellKey.BODY - c[g.axial]];
        return c;
    }

    private static boolean run(boolean[][][] filled, boolean[][][] used, int x0, int x1, int y0, int y1, int z0, int z1) {
        for (int a = x0; a < x1; a++)
            for (int b = y0; b < y1; b++)
                for (int c = z0; c < z1; c++)
                    if (!filled[a][b][c] || used[a][b][c]) return false;
        return true;
    }

    private static Geometry geometry(EquipmentShape shape) {
        var g = GEOMETRIES.get(shape);
        if (g != null) return g;
        // A large column holds tens of thousands of facets (about 750 bytes each): the cache is bounded by facets too.
        long facets = 0;
        for (var cached : GEOMETRIES.values()) facets += cached.mesh.facets().size();
        if (GEOMETRIES.size() >= GEOMETRY_CACHE || facets >= GEOMETRY_FACETS) GEOMETRIES.clear();
        return GEOMETRIES.computeIfAbsent(shape, Geometry::new);
    }

    /** Per-shape tables (facet directions, head rings, plate and saddle layout), the static mesh, nozzles and layout. */
    private static final class Geometry {
        private final EquipmentShape shape;
        private final Transform transform;
        private final int n, contact;
        /** Index (0 x, 1 y, 2 z) of the box coordinate that runs along the vessel's axis. */
        private final int axial;
        /** Body texture class of each row of cells along the axis (-1: not a body row), and the first row of each class. */
        private int[] bodyClasses, representatives;
        /** The shell's radius, and its outside's (a column's cladding; the shell's for a drum). */
        private final double axis, radius, outer, apothemFactor, ground, plateLength;
        /** A column's access structure, or null; a unit's parts, or null. */
        private final ColumnStructure column;
        private final ProcessUnit unit;
        /**
         * Box columns (x, z) a column's ladders pass through: their cells differ row by row, so they never share a body key.
         * Filled from the facets from {@link #irregularFrom} on, with the facets by cell.
         */
        private final Set<Long> irregularColumns = new HashSet<>();
        private int irregularFrom = Integer.MAX_VALUE;
        /** The facets reaching each cell, in mesh order. */
        private final Map<Cell, List<Facet>> facetsByCell = new HashMap<>();
        /** Outer radii (vertex) of a saddle's top plate and a skirt's base ring; a saddle's half width across the axis. */
        private final double saddleRadius, ringRadius, saddleLateral;
        /**
         * A skirt's port: centre height and radius at its largest (0: none), and the band of rows its skirt facets' pieces
         * round it take, drawn in its bottom outlet's mesh with the hole at the pipe's size ({@link #skirtPort}).
         */
        private final double openingHeight, openingRadius, portLow, portHigh;
        /**
         * The way the port faces, frame x and z ({@link EquipmentShape#SIDES}[{@link EquipmentShape#outlet}]: the front,
         * -z, unless turned, owner request 2026-10-25), and the shell vertex it is centred on.
         */
        private final int portX, portZ, portVertex;
        /**
         * A skirt is hollow: drawn on both faces, with its bottom head (a column's bare, under its cladding), its outlet
         * line and a floor plate inside (owner report 2026-10-16; a column's too, owner request 2026-10-16). Its slots, from
         * {@code slotLow} to {@code slotHigh} (0: none), are one facet wide and centred on the three of the shell vertices
         * at frame +x, +z, -x and -z its port leaves free: the left, the back and the right for a port at the front.
         */
        private final boolean hollow;
        private final double slotLow, slotHigh, innerRadius;
        /** A drum's manway and a lying drum's boot, or null. */
        private final @Nullable Manway manway;
        private final EquipmentShape.@Nullable Boot boot;
        private final double[] normalCos, normalSin, vertexCos, vertexSin, saddles;
        /** Each head's rings, {height over the tangent line, radius}, and its meridian lengths to them: the top head's (clad on a column) and the bottom head's (bare). */
        private final double[][] rings, bottomRings;
        private final double[] meridian, bottomMeridian;
        private final Mesh mesh = new Mesh();
        private final List<EquipmentShape.Nozzle> nozzles;
        private final List<Set<Cell>> nozzleCells = new ArrayList<>();
        /** The nozzles reaching each cell, in nozzle order. */
        private final Map<Cell, int[]> reaching = new HashMap<>();
        private final Map<Integer, Mesh> nozzleMeshes = new ConcurrentHashMap<>();
        private final List<Port> ports = new ArrayList<>();
        private final Layout layout;
        private final Cell fluidCell;
        /** One node cell per registration: the fluid cell, or each heat side's first inlet cell (batch 2026-10-27-heat-exchanger-design). */
        private final List<Cell> fluidCells;
        /** The layout's {@link #stamp}, 0 until first asked. */
        private volatile long stamp;

        Geometry(EquipmentShape shape) {
            this.shape = shape;
            transform = shape.transform();
            mesh.transform(transform);
            n = shape.facets();
            axis = shape.axis();
            radius = shape.radius();
            outer = shape.outerRadius();
            column = shape.column() ? new ColumnStructure(shape) : null;
            unit = shape.designed() ? ProcessUnit.of(shape) : null;
            var along = transform.direction(new double[]{0, 1, 0});
            axial = along[0] != 0 ? 0 : along[1] != 0 ? 1 : 2;
            // A lying vessel's frame has the ground below it at x = -support (world y = 0).
            ground = -shape.support();
            apothemFactor = DeterministicMath.cos(Math.PI / n);
            normalCos = new double[n];
            normalSin = new double[n];
            vertexCos = new double[n + 1];
            vertexSin = new double[n + 1];
            for (int i = 0; i < n; i++) {
                double mid = (i + .5) * 2 * Math.PI / n;
                normalCos[i] = DeterministicMath.cos(mid);
                normalSin[i] = DeterministicMath.sin(mid);
            }
            for (int i = 0; i <= n; i++) {
                double angle = 2 * Math.PI * (i % n) / n;
                vertexCos[i] = DeterministicMath.cos(angle);
                vertexSin[i] = DeterministicMath.sin(angle);
            }
            rings = rings(outer, shape.outerHeadDepth());
            meridian = meridian(rings);
            // Inside a skirt the bottom head is bare: a column's cladding ends at the bottom tangent line.
            bottomRings = rings(radius, shape.headDepth());
            bottomMeridian = meridian(bottomRings);
            double chord = 2 * radius * DeterministicMath.sin(Math.PI / n), circumference = n * chord;
            plateLength = circumference / Math.max(1, Math.round(circumference / PLATE_LENGTH));
            // Saddles cradle the shell vertices within half the contact angle of the bottom vertex (n/2, angle π, towards the ground).
            contact = (int) Math.round(n * EquipmentShape.SADDLE_CONTACT_DEGREES / 720);
            saddleRadius = radius + EquipmentShape.PLATE / apothemFactor;
            saddleLateral = saddleRadius * vertexSin[contact];
            ringRadius = radius + EquipmentShape.BASE_RING / apothemFactor;
            saddles = shape.saddleCentres();
            openingHeight = shape.openingHeight();
            openingRadius = shape.openingRadius();
            portLow = Math.max(EquipmentShape.PLATE, openingHeight - openingRadius - EquipmentShape.PORT_COLLAR - .05);
            portHigh = openingHeight + openingRadius + EquipmentShape.PORT_COLLAR + .05;
            int[] side = EquipmentShape.SIDES[shape.outlet()];
            portX = side[0] == 0 ? side[1] : 0;
            portZ = side[0] == 2 ? side[1] : 0;
            // Vertex n/4 lies at frame +z; the front's -z at 3n/4, each turn a quarter on.
            portVertex = Math.floorMod(3 * n / 4 + shape.outlet() * n / 4, n);
            hollow = shape.skirt();
            innerRadius = radius - EquipmentShape.SKIRT_WALL / apothemFactor;
            double slotTop = Math.min(shape.bottomTangent() - .2, .4 + 1.6);
            boolean slots = hollow && shape.diameter() >= 1 && slotTop - .4 >= .4;
            slotLow = slots ? .4 : 0;
            slotHigh = slots ? slotTop : 0;
            nozzles = shape.nozzles();
            manway = shape.manway();
            boot = shape.bootPlace();

            if (unit != null) {
                // The design's facets one for one: those from its irregular index on cross its body rows (a tank's stair).
                mesh.include(unit.mesh());
                irregularFrom = unit.irregularFrom;
            } else {
                build();
                if (shape.skirt()) skirtBase();
                if (shape.skirt() && shape.drum()) junction();
                if (boot != null) boot();
                if (manway != null) manway.draw(mesh);
                for (double c : saddles) saddle(c);
            }
            if (column != null) {
                var ladders = new Mesh();
                ladders.transform(transform);
                column.build(mesh, ladders, nozzles);
                irregularFrom = mesh.facets().size();
                mesh.facets().addAll(ladders.facets());
            }
            // Every nozzle takes the pipe's size. Reservoir ports and a mover's suction/discharge also join the engine.
            var byCell = new HashMap<Cell, List<Integer>>();
            for (int k = 0; k < nozzles.size(); k++) {
                var nozzle = nozzles.get(k);
                var cells = new HashSet<Cell>();
                // Every cell the largest pipe's nozzle reaches: a nozzle takes any pipe.
                var largest = nozzle(k, PipeSizes.code(PipeSizes.MAX_OUTSIDE));
                for (var facet : largest.facets()) cells(facet, cells);
                nozzleCells.add(Set.copyOf(cells));
                for (var cell : cells) byCell.computeIfAbsent(cell, c -> new ArrayList<>()).add(k);
                var face = nozzle.face();
                var inner = transform.point(new double[]{face[0] - nozzle.direction()[0] * .01, face[1] - nozzle.direction()[1] * .01, face[2] - nozzle.direction()[2] * .01});
                var out = transform.direction(nozzle.direction());
                ports.add(new Port(new Cell(floor(inner[0]), floor(inner[1]), floor(inner[2])), (int) out[0], (int) out[1], (int) out[2], nozzle.role().port, k));
            }
            for (var entry : byCell.entrySet()) {
                if (entry.getValue().size() > KEYED_NOZZLES)
                    throw new IllegalStateException(entry.getValue().size() + " nozzles reach cell " + entry.getKey() + " of " + shape + ": a key carries " + KEYED_NOZZLES);
                reaching.put(entry.getKey(), entry.getValue().stream().mapToInt(Integer::intValue).toArray());
            }
            classify();
            Cell node;
            List<Cell> sides = null;
            if (HeatSides.unit(shape.kind()) != null) {
                // A heat unit's sides each register at their first inlet's cell; the first side's is the fluid cell.
                sides = HeatSides.of(shape).stream().map(definition -> ports.stream().filter(port -> nozzles.get(port.nozzle()).role() == definition.inlet())
                        .findFirst().orElseThrow(() -> new IllegalStateException("A heat side needs an inlet nozzle: " + definition.side())).cell()).toList();
                // A kettle's shell registers after its sides, at its pool's node (W7).
                if (HeatSides.pool(shape.kind())) {
                    if (unit == null || unit.fluidPoint == null) throw new IllegalStateException("A kettle needs its pool's node");
                    var low = transform.point(unit.fluidPoint);
                    var all = new ArrayList<>(sides);all.add(new Cell(floor(low[0]), floor(low[1]), floor(low[2])));sides = List.copyOf(all);
                }
                node = sides.getFirst();
            } else if (shape.drum()) {
                var low = shape.orientation().horizontal()
                        ? transform.point(new double[]{axis - radius + .01, (shape.bottomTangent() + shape.topTangent()) / 2, axis})
                        : transform.point(new double[]{axis, shape.bottomTangent() - shape.headDepth() + .01, axis});
                node = new Cell(floor(low[0]), floor(low[1]), floor(low[2]));
            } else if (shape.kind().mover()) {
                node = ports.stream().filter(port -> nozzles.get(port.nozzle()).role() == EquipmentShape.Role.SUCTION)
                        .findFirst().orElseThrow(() -> new IllegalStateException("A mover needs a suction nozzle")).cell();
            } else if (unit != null && unit.fluidPoint != null) {
                var low = transform.point(unit.fluidPoint);
                node = new Cell(floor(low[0]), floor(low[1]), floor(low[2]));
            } else node = null;
            fluidCell = node;
            fluidCells = sides != null ? sides : node == null ? List.of() : List.of(node);
            layout = buildLayout();
            if (!drawing) {
                mesh.facets().clear();
                facetsByCell.clear();
                nozzleMeshes.clear();
            }
        }

        /** A head's rings from its tangent line (k = 0) to its apex: {height over the line, radius} at equal latitude steps. */
        private double[][] rings(double r, double depth) {
            int bands = shape.bands();
            var out = new double[bands + 1][];
            for (int k = 0; k <= bands; k++) {
                double phi = Math.PI / 2 * k / bands;
                out[k] = new double[]{depth * DeterministicMath.sin(phi), k == bands ? 0 : r * DeterministicMath.cos(phi)};
            }
            return out;
        }

        /** Meridian distance from the tangent line to each head ring, measured between facet centre lines. */
        private double[] meridian(double[][] rings) {
            var out = new double[rings.length];
            for (int k = 0; k + 1 < rings.length; k++) {
                double dh = rings[k + 1][0] - rings[k][0], dr = (rings[k][1] - rings[k + 1][1]) * apothemFactor;
                out[k + 1] = out[k] + Math.sqrt(dh * dh + dr * dr);
            }
            return out;
        }

        /** The nozzles reaching a cell, in nozzle order. */
        int[] reaching(Cell cell) {
            return reaching.getOrDefault(cell, NONE);
        }

        private static final int[] NONE = new int[0];

        /**
         * The shell, the skirt (less its port's band, which its bottom outlet draws at the pipe's size) and the heads. A
         * column's shell and top head are clad: the cladding stands {@link EquipmentShape#INSULATION} out from the shell, in
         * aluminium panels, its lower edge closed over the skirt at the bottom tangent line.
         */
        private void build() {
            double yB = shape.bottomTangent(), yT = shape.topTangent(), plate = EquipmentShape.PLATE;
            boolean clad = column != null;
            var up = new double[]{0, 1, 0};
            var courses = new Uv(new double[]{axis, yB, axis}, up, 0, clad ? Material.CLADDING.period() : 2 * COURSE);
            var skirtTiles = new Uv(new double[]{axis, yB, axis}, up, 0, SUPPORT_TILE);
            var centre = new double[]{axis, (yB + yT) / 2, axis};
            var round = new Radial(new double[]{axis, 0, axis}, up);
            double chord = 2 * outer * DeterministicMath.sin(Math.PI / n);
            for (int i = 0; i < n; i++) {
                double[] a = shell(i, 0), b = shell(i + 1, 0);
                double[] tangent = unit(b[0] - a[0], 0, b[2] - a[2]);
                // Arc length runs counter-clockwise seen from the top head, leftwards seen from outside: u runs against it.
                var plates = new Uv(a, neg(tangent), -i * chord, clad ? Material.CLADDING.period() : plateLength);
                mesh.add(List.of(shell(i, yB), shell(i + 1, yB), shell(i + 1, yT), shell(i, yT)), centre, round, clad ? Material.CLADDING : Material.SHELL, plates, courses);
                if (clad) mesh.flat(List.of(ring(i, yB), ring(i + 1, yB), shell(i + 1, yB), shell(i, yB)), new double[]{0, -1, 0}, Material.CLADDING);
                if (shape.skirt()) {
                    if (portFacet(i)) {
                        skirtFace(mesh, i, plate, portLow, 0);
                        skirtFace(mesh, i, portHigh, yB, 0);
                    } else skirtFace(mesh, i, plate, yB, 0);
                    if (hollow) slotEdges(i);
                }
                for (int side = 0; side < 2; side++) {
                    double base = side == 0 ? yB : yT, sign = side == 0 ? -1 : 1;
                    double[][] h = side == 0 ? bottomRings : rings;
                    double[] m = side == 0 ? bottomMeridian : meridian;
                    var apex = new double[]{axis, base, axis};
                    var spheroid = new Spheroid(apex, up, h[0][1], h[h.length - 1][0]);
                    for (int k = 0; k < h.length - 1; k++) {
                        var polygon = new ArrayList<double[]>(4);
                        polygon.add(head(h, i, k, base, sign));
                        polygon.add(head(h, i + 1, k, base, sign));
                        polygon.add(head(h, i + 1, k + 1, base, sign));
                        if (h[k + 1][1] > 0) polygon.add(head(h, i, k + 1, base, sign));
                        // v runs from the tangent line weld (sprite top) to the apex (bottom), once over the head.
                        double[] outer = lerp(head(h, i, k, base, sign), head(h, i + 1, k, base, sign), .5);
                        double[] inner = lerp(head(h, i, k + 1, base, sign), head(h, i + 1, k + 1, base, sign), .5);
                        var towardsTangent = unit(outer[0] - inner[0], outer[1] - inner[1], outer[2] - inner[2]);
                        mesh.add(polygon, apex, spheroid, Material.HEAD, plates, new Uv(outer, towardsTangent, -m[k], m[h.length - 1]));
                    }
                }
            }
        }

        /**
         * Skirt facet {@code i} from {@code y0} to {@code y1}, its outer face and a hollow skirt's inner face (seen through
         * the port and the slots), less a port hole of radius {@code port} (0: none) and a slot where they reach it, into
         * {@code m}: the vessel's mesh, or the bottom outlet's at a pipe size for the port's band.
         */
        private void skirtFace(Mesh m, int i, double y0, double y1, double port) {
            if (y1 <= y0) return;
            double yB = shape.bottomTangent(), yT = shape.topTangent(), apothem = radius * apothemFactor;
            var up = new double[]{0, 1, 0};
            double[] a = shell(i, 0), b = shell(i + 1, 0), tangent = unit(b[0] - a[0], 0, b[2] - a[2]);
            double chord = 2 * outer * DeterministicMath.sin(Math.PI / n);
            var skirtU = new Uv(a, neg(tangent), -i * chord, plateLength / 2);
            var skirtTiles = new Uv(new double[]{axis, yB, axis}, up, 0, SUPPORT_TILE);
            var centre = new double[]{axis, (yB + yT) / 2, axis};
            for (var piece : skirtFacet(i, radius, apothem, y0, y1, port)) m.add(piece, centre, new Radial(new double[]{axis, 0, axis}, up), Material.SUPPORT, skirtU, skirtTiles);
            if (!hollow) return;
            var beyond = new double[]{axis + 2 * radius * normalCos[i], yB / 2, axis + 2 * radius * normalSin[i]};
            for (var piece : skirtFacet(i, innerRadius, apothem - EquipmentShape.SKIRT_WALL, y0, y1, port))
                m.add(piece, beyond, new Inward(new double[]{axis, 0, axis}, up), Material.SUPPORT, skirtU, skirtTiles);
        }

        /** Whether the port at its largest reaches skirt facet {@code i}: its band is drawn with the bottom outlet. */
        private boolean portFacet(int i) {
            return openingRadius > 0 && hole(i, radius * apothemFactor, openingRadius + EquipmentShape.PORT_COLLAR) != null;
        }

        /**
         * Skirt facet {@code i} from {@code y0} to {@code y1}, its vertices at radius {@code r} (its plane at {@code apothem}
         * from the axis), less a port hole of radius {@code port} (0: none) and a slot where they reach it.
         */
        private List<List<double[]>> skirtFacet(int i, double r, double apothem, double y0, double y1, double port) {
            var normal = new double[]{normalCos[i], 0, normalSin[i]};
            List<List<double[]>> pieces = List.of(List.of(vertex(i, r, y0), vertex(i + 1, r, y0), vertex(i + 1, r, y1), vertex(i, r, y1)));
            var hole = port > 0 ? hole(i, apothem, port) : null;
            if (hole != null) pieces = cut(pieces, hole, normal);
            var slot = slot(i, r);
            if (slot != null) pieces = cut(pieces, slot, normal);
            return pieces;
        }

        private static List<List<double[]>> cut(List<List<double[]>> pieces, List<double[]> hole, double[] normal) {
            var out = new ArrayList<List<double[]>>();
            for (var piece : pieces) out.addAll(subtract(piece, hole, normal));
            return out;
        }

        /**
         * The port's hole of radius {@code port} on the plane of skirt facet {@code i} at {@code apothem} from the axis, or
         * null when it does not reach that facet: the circle around the port's horizontal axis (towards {@link #portX},
         * {@link #portZ}), projected along that axis onto the facet. A 32-gon whose edges stay outside the circle, so no
         * plate lies inside it. Across the port runs the direction a quarter turn back from it (frame +x for the front's -z).
         */
        private List<double[]> hole(int i, double apothem, double port) {
            double facing = normalCos[i] * portX + normalSin[i] * portZ;
            if (facing < .3) return null;
            int ux = -portZ, uz = portX;
            double across = normalCos[i] * ux + normalSin[i] * uz, v0 = vertexCos[i] * ux + vertexSin[i] * uz, v1 = vertexCos[i + 1] * ux + vertexSin[i + 1] * uz;
            double low = Math.min(v0, v1) * radius, high = Math.max(v0, v1) * radius;
            if (high < -port || low > port) return null;
            int sides = 32;
            double vertexRadius = port / DeterministicMath.cos(Math.PI / sides);
            var hole = new ArrayList<double[]>(sides);
            for (int j = 0; j < sides; j++) {
                double angle = 2 * Math.PI * j / sides, x = vertexRadius * DeterministicMath.cos(angle), y = openingHeight + vertexRadius * DeterministicMath.sin(angle);
                double out = (apothem - x * across) / facing;
                hole.add(new double[]{axis + x * ux + out * portX, y, axis + x * uz + out * portZ});
            }
            return hole;
        }

        /** The part {s0, s1} of skirt facet {@code i} (0 at vertex i, 1 at vertex i + 1) a slot takes, or null. */
        private double[] slotSpan(int i) {
            if (slotHigh <= 0) return null;
            if (slotVertex(i)) return new double[]{0, .5};
            if (slotVertex(i + 1)) return new double[]{.5, 1};
            return null;
        }

        /** Whether a slot is centred on shell vertex {@code v}: the quarter vertices but the port's. */
        private boolean slotVertex(int v) {
            v = Math.floorMod(v, n);
            return v % (n / 4) == 0 && v != portVertex;
        }

        /** The slot's rectangle on skirt facet {@code i} at vertex radius {@code r}, or null. */
        private List<double[]> slot(int i, double r) {
            var span = slotSpan(i);
            if (span == null) return null;
            double[] a0 = vertex(i, r, slotLow), b0 = vertex(i + 1, r, slotLow), a1 = vertex(i, r, slotHigh), b1 = vertex(i + 1, r, slotHigh);
            return List.of(lerp(a0, b0, span[0]), lerp(a0, b0, span[1]), lerp(a1, b1, span[1]), lerp(a1, b1, span[0]));
        }

        /** A slot's cut edges through the skirt plate on facet {@code i}: its sill, its head, and its jamb where the slot ends on this facet. */
        private void slotEdges(int i) {
            var span = slotSpan(i);
            if (span == null) return;
            for (double y : new double[]{slotLow, slotHigh}) {
                var edge = List.of(lerp(vertex(i, radius, y), vertex(i + 1, radius, y), span[0]), lerp(vertex(i, radius, y), vertex(i + 1, radius, y), span[1]),
                        lerp(vertex(i, innerRadius, y), vertex(i + 1, innerRadius, y), span[1]), lerp(vertex(i, innerRadius, y), vertex(i + 1, innerRadius, y), span[0]));
                mesh.flat(edge, new double[]{0, y == slotLow ? 1 : -1, 0}, Material.SUPPORT);
            }
            // The jamb stands where the slot ends inside this facet (s = 0.5); the facet's other end is the slot's middle.
            double s = span[0] == 0 ? span[1] : span[0];
            var jamb = List.of(lerp(vertex(i, radius, slotLow), vertex(i + 1, radius, slotLow), s), lerp(vertex(i, radius, slotHigh), vertex(i + 1, radius, slotHigh), s),
                    lerp(vertex(i, innerRadius, slotHigh), vertex(i + 1, innerRadius, slotHigh), s), lerp(vertex(i, innerRadius, slotLow), vertex(i + 1, innerRadius, slotLow), s));
            var along = sub(vertex(i + 1, radius, 0), vertex(i, radius, 0));
            // It faces into the slot: towards vertex i when the slot holds vertex i, the plate lying towards vertex i + 1.
            mesh.flat(jamb, span[0] == 0 ? neg(along) : along, Material.SUPPORT);
        }

        /** Vertex {@code i} of a skirt face at radius {@code r} and axial position {@code y}, in the vessel frame. */
        private double[] vertex(int i, double r, double y) {
            return new double[]{axis + r * vertexCos[i], y, axis + r * vertexSin[i]};
        }

        /**
         * A skirt's port round its outlet line of radius {@code line}, at the line's size (owner requests 2026-10-16: the
         * port follows the line; 2026-10-21: "Skirt hole needs to adapt to the size of the pipe to max 1m"): the skirt
         * facets' pieces in the port's band with the hole cut to the line's radius and {@link EquipmentShape#PORT_CLEARANCE},
         * and the sleeve through the plate round it, its bore that wide, its collar {@link EquipmentShape#PORT_COLLAR}
         * thick, closed at its inner end (inside the skirt the sleeve's tube is hidden).
         */
        private void skirtPort(Mesh m, double line) {
            var f = openingFrame();
            double apothem = radius * apothemFactor, port = EquipmentShape.portRadius(2 * line), rim = port + EquipmentShape.PORT_COLLAR;
            double outer = radius + 1 / 16.0, back = apothem - .12;
            for (int i = 0; i < n; i++) if (portFacet(i)) skirtFace(m, i, portLow, portHigh, port);
            int sides = Math.max(16, PipeSizes.sides(2 * rim));
            m.prism(f, rim, back, outer, sides, Material.SUPPORT);
            m.bore(f, port, back, outer, sides, Material.SUPPORT);
            m.annulus(f, port, rim, outer, sides, 1, Material.SUPPORT);
            m.annulus(f, port, rim, back, sides, -1, Material.SUPPORT);
        }

        /** The port's frame: along its line out of the skirt, its reference across it as {@link #hole} runs. */
        private Frame openingFrame() {
            return new Frame(new double[]{axis, openingHeight, axis}, new double[]{portX, 0, portZ}, new double[]{-portZ, 0, portX});
        }

        /** A skirt's foot: the plate under it and its base ring, the ring's top and rim, and the anchor chairs. */
        private void skirtBase() {
            double plate = EquipmentShape.PLATE;
            var centre = new double[]{axis, plate / 2, axis};
            var round = new Radial(new double[]{axis, 0, axis}, new double[]{0, 1, 0});
            var bottom = new ArrayList<double[]>(n);
            for (int i = 0; i < n; i++) bottom.add(rim(i, 0));
            mesh.add(bottom, centre, FLAT, Material.SUPPORT, null, null);
            if (hollow) {
                // A hollow skirt's floor: the base plate's top inside the skirt plate.
                var floor = new ArrayList<double[]>(n);
                for (int i = 0; i < n; i++) floor.add(vertex(i, innerRadius, plate));
                mesh.add(floor, new double[]{axis, 0, axis}, FLAT, Material.SUPPORT, null, null);
            }
            for (int i = 0; i < n; i++) {
                mesh.add(List.of(rim(i, plate), rim(i + 1, plate), ring(i + 1, plate), ring(i, plate)), centre, FLAT, Material.SUPPORT, null, null);
                mesh.add(List.of(rim(i, 0), rim(i + 1, 0), rim(i + 1, plate), rim(i, plate)), centre, round, Material.SUPPORT, null, null);
            }
            int chairs = shape.anchorChairs();
            double apothem = radius * apothemFactor, depth = EquipmentShape.BASE_RING, half = EquipmentShape.CHAIR / 2;
            double low = plate, high = plate + EquipmentShape.CHAIR;
            for (int k = 0; k < chairs; k++) {
                int i = chairFacet(k, chairs);
                // A box on the ring against shell facet i: corners at radial r and tangential t from the axis.
                double[] in0 = chair(i, apothem, -half), in1 = chair(i, apothem, half);
                double[] out0 = chair(i, apothem + depth, -half), out1 = chair(i, apothem + depth, half);
                var middle = chair(i, apothem + depth / 2, 0);
                middle[1] = (low + high) / 2;
                mesh.add(List.of(at(out0, low), at(out1, low), at(out1, high), at(out0, high)), middle, FLAT, Material.SUPPORT, null, null);
                mesh.add(List.of(at(in0, high), at(out0, high), at(out1, high), at(in1, high)), middle, FLAT, Material.SUPPORT, null, null);
                mesh.add(List.of(at(in0, low), at(out0, low), at(out0, high), at(in0, high)), middle, FLAT, Material.SUPPORT, null, null);
                mesh.add(List.of(at(in1, low), at(out1, low), at(out1, high), at(in1, high)), middle, FLAT, Material.SUPPORT, null, null);
            }
        }

        /**
         * Where a drum's skirt meets its bottom head (owner request 2026-10-16; photo survey EQUIPMENT_DETAILS_PHOTO_SURVEY.md,
         * section B): the skirt-to-head weld under a bare steel band standing 0.02 m proud round the bottom tangent line, and
         * the skirt's vent sleeves 0.25 m under it, two (four from 1.25 m) on the diagonals clear of the opening and the
         * slots, which let out what gathers under the head.
         */
        private void junction() {
            double yB = shape.bottomTangent(), band = .1, rOut = radius + .02 / apothemFactor;
            var f = new Frame(new double[]{axis, yB - band / 2, axis}, new double[]{0, 1, 0}, new double[]{1, 0, 0});
            mesh.prism(f, rOut, 0, band, n, Material.FLANGE);
            mesh.annulus(f, radius, rOut, 0, n, -1, Material.FLANGE);
            mesh.annulus(f, radius, rOut, band, n, 1, Material.FLANGE);
            int vents = shape.diameter() >= 1.25 ? 4 : 2;
            double apothem = radius * apothemFactor, r = VENT / 2, bore = r - .015;
            for (int k = 0; k < vents; k++) {
                double angle = Math.PI / 4 + k * 2 * Math.PI / vents;
                int i = Math.floorMod((int) Math.round(angle * n / (2 * Math.PI) - .5), n);
                var normal = new double[]{normalCos[i], 0, normalSin[i]};
                var v = new Frame(new double[]{axis + apothem * normalCos[i], yB - .25, axis + apothem * normalSin[i]}, normal, new double[]{0, 1, 0});
                mesh.prism(v, r, -.03, .05, 8, Material.SUPPORT);
                mesh.annulus(v, bore, r, .05, 8, 1, Material.SUPPORT);
                mesh.bore(v, bore, -.03, .05, 8, Material.SUPPORT);
                mesh.annulus(v, 0, bore, -.03, 8, 1, Material.OPENING);
            }
        }

        /**
         * A lying drum's boot (photo survey A): its shell from inside the drum's down to its tangent line and its 2:1
         * bottom head, under the drum's axis at its station.
         */
        private void boot() {
            double rb = boot.diameter() / 2, top = Math.sqrt(Math.max(0, radius * radius - rb * rb)) * apothemFactor - .03, tangent = radius + boot.length();
            int sides = Math.max(12, 4 * (int) Math.ceil(.75 * Math.PI * boot.diameter()));
            var f = new Frame(new double[]{axis, boot.station(), axis}, new double[]{-1, 0, 0}, new double[]{0, 0, 1});
            mesh.prism(f, rb, top, tangent, sides, Material.SHELL);
            mesh.head(f, rb, tangent, boot.diameter() / 4, sides, Material.HEAD);
        }

        /** Inside a lying drum's boot, at a frame point: its shell under the drum and its bottom head. */
        private boolean inBoot(double[] p) {
            if (boot == null) return false;
            double rb = boot.diameter() / 2, dy = p[1] - boot.station(), dz = p[2] - axis, across = dy * dy + dz * dz, down = axis - p[0], tangent = radius + boot.length();
            if (across > rb * rb || down < radius - .1) return false;
            if (down <= tangent) return true;
            double off = (down - tangent) / (boot.diameter() / 4);
            return off <= 1 && across <= rb * rb * (1 - off * off);
        }

        /**
         * The shell facet anchor chair {@code k} of {@code chairs} stands against. A drum's skirt is pierced on its axes (its
         * outlet's port on one, slots on the other three), so its chairs stand between them: chairs come in fours, so a
         * quarter turn of the port (owner request 2026-10-25) maps their set onto itself and they stay between the
         * piercings. A column's ladders come down on the diagonals, so its chairs keep to the axes.
         */
        private int chairFacet(int k, int chairs) {
            return shape.column() ? k * n / chairs : (2 * k + 1) * n / (2 * chairs);
        }

        /** Cross-section point at radial distance {@code r} along facet {@code i}'s normal and {@code t} along the facet. */
        private double[] chair(int i, double r, double t) {
            return new double[]{axis + r * normalCos[i] - t * normalSin[i], 0, axis + r * normalSin[i] + t * normalCos[i]};
        }

        /**
         * One saddle at an axial centre, in the vessel frame. A top plate wraps the shell over the contact arc; a web plate
         * under it and a rib at each horn stand on a base plate. Faces that touch another part are not drawn.
         */
        private void saddle(double c) {
            double plate = EquipmentShape.PLATE, rib = EquipmentShape.SADDLE_RIB, deck = ground + plate;
            double y0 = c - EquipmentShape.SADDLE_WIDTH / 2, y1 = c + EquipmentShape.SADDLE_WIDTH / 2;
            double w0 = c - EquipmentShape.SADDLE_WEB / 2, w1 = c + EquipmentShape.SADDLE_WEB / 2;
            int first = n / 2 - contact, last = n / 2 + contact;
            var axisPoint = new double[]{axis, c, axis};
            var lowest = new double[]{axis - radius, c, axis};
            var round = new Radial(new double[]{axis, 0, axis}, new double[]{0, 1, 0});
            // Top plate: outside faces (beside the web), axial ends and horn ends.
            for (int j = first; j < last; j++) {
                for (double[] span : new double[][]{{y0, w0}, {w1, y1}})
                    mesh.add(List.of(outer(j, span[0]), outer(j + 1, span[0]), outer(j + 1, span[1]), outer(j, span[1])), axisPoint, round, Material.SUPPORT, null, null);
                for (double y : new double[]{y0, y1})
                    mesh.add(List.of(ring(j, y), ring(j + 1, y), outer(j + 1, y), outer(j, y)), axisPoint, FLAT, Material.SUPPORT, null, null);
            }
            for (int j : new int[]{first, last})
                mesh.add(List.of(ring(j, y0), ring(j, y1), outer(j, y1), outer(j, y0)), lowest, FLAT, Material.SUPPORT, null, null);
            // Web: strips under the top plate between the ribs, on both faces.
            double webHalf = saddleLateral - rib;
            var webCentre = new double[]{deck, c, axis};
            for (double y : new double[]{w0, w1}) strips(chain(-webHalf, webHalf), y, deck, webCentre);
            // Ribs: from the deck up to the top plate, outside the web.
            for (int side = 0; side < 2; side++) {
                var edge = side == 0 ? chain(webHalf, saddleLateral) : chain(-saddleLateral, -webHalf);
                double[] horn = side == 0 ? edge.getFirst() : edge.getLast(), inner = side == 0 ? edge.getLast() : edge.getFirst();
                double sign = side == 0 ? 1 : -1;
                var ribCentre = new double[]{(deck + horn[0]) / 2, c, axis + sign * (saddleLateral - rib / 2)};
                mesh.add(List.of(new double[]{deck, y0, horn[2]}, new double[]{deck, y1, horn[2]}, at(horn, y1), at(horn, y0)), ribCentre, FLAT, Material.SUPPORT, null, null);
                for (double y : new double[]{y0, y1}) strips(edge, y, deck, ribCentre);
                for (double[] span : new double[][]{{y0, w0}, {w1, y1}})
                    mesh.add(List.of(new double[]{deck, span[0], inner[2]}, new double[]{deck, span[1], inner[2]}, at(inner, span[1]), at(inner, span[0])),
                            ribCentre, FLAT, Material.SUPPORT, null, null);
            }
            // Base plate: underside, four sides, and its top beside the web (the web and ribs cover the rest).
            double lateral = saddleLateral;
            var baseCentre = new double[]{ground + plate / 2, c, axis};
            double[][] corners = {{-lateral, y0}, {lateral, y0}, {lateral, y1}, {-lateral, y1}};
            var under = new ArrayList<double[]>(4);
            for (var corner : corners) under.add(new double[]{ground, corner[1], axis + corner[0]});
            mesh.add(under, baseCentre, FLAT, Material.SUPPORT, null, null);
            for (int e = 0; e < 4; e++) {
                double[] a = corners[e], b = corners[(e + 1) % 4];
                mesh.add(List.of(new double[]{ground, a[1], axis + a[0]}, new double[]{ground, b[1], axis + b[0]}, new double[]{deck, b[1], axis + b[0]},
                        new double[]{deck, a[1], axis + a[0]}), baseCentre, FLAT, Material.SUPPORT, null, null);
            }
            for (double[] span : new double[][]{{y0, w0}, {w1, y1}})
                mesh.add(List.of(new double[]{deck, span[0], axis - webHalf}, new double[]{deck, span[0], axis + webHalf}, new double[]{deck, span[1], axis + webHalf},
                        new double[]{deck, span[1], axis - webHalf}), baseCentre, FLAT, Material.SUPPORT, null, null);
        }

        /**
         * The top plate's outer edge over lateral offsets [low, high] from the axis, as cross-section points (x, 0, z) in
         * order of decreasing z: its vertices within the range and the points where the range cuts it.
         */
        private List<double[]> chain(double low, double high) {
            var points = new ArrayList<double[]>();
            for (int j = n / 2 - contact; j < n / 2 + contact; j++) {
                double[] a = outer(j, 0), b = outer(j + 1, 0);
                double za = a[2] - axis, zb = b[2] - axis, top = Math.min(za, high), bottom = Math.max(zb, low);
                if (top <= bottom + EPS) continue;
                if (points.isEmpty()) points.add(lerp(a, b, (za - top) / (za - zb)));
                points.add(lerp(a, b, (za - bottom) / (za - zb)));
            }
            return points;
        }

        /** The faces at axial position {@code y} of the part between the deck and an edge: one strip under each edge segment. */
        private void strips(List<double[]> edge, double y, double deck, double[] centre) {
            for (int p = 0; p + 1 < edge.size(); p++) {
                double[] a = at(edge.get(p), y), b = at(edge.get(p + 1), y);
                mesh.add(List.of(new double[]{deck, y, a[2]}, new double[]{deck, y, b[2]}, b, a), centre, FLAT, Material.SUPPORT, null, null);
            }
        }

        /**
         * Nozzle {@code k} drawn at size {@code code} (0: unconnected, at its default size): a neck from under the vessel's
         * surface and a flange at the pipe's size whose face lies on the block face. The neck is the pipe's, whatever its
         * size, and meets the flange in a weld-neck hub (owner decision 2026-10-21: a 1 m pipe has a 1 m root). A bottom
         * outlet's line also has its riser from the head and its elbow into the neck, and its skirt port round it, all at
         * the neck's size (owner request 2026-10-16). Built once per size.
         */
        Mesh nozzle(int k, int code) {
            return nozzleMeshes.computeIfAbsent(k * 128 + code, key -> {
                var nozzle = nozzles.get(k);
                double od = code == 0 ? nozzle.defaultOd() : PipeSizes.outsideOf(code);
                var m = new Mesh();
                m.transform(transform);
                var dir = nozzle.direction();
                var ref = new double[3];
                ref[(nozzle.axis() + 1) % 3] = 1;
                var f = new Frame(nozzle.base(), dir, ref);
                double r = od / 2, flange = PipeSizes.flange(od) / 2, t = PipeSizes.flangeThickness(od), hub = PipeSizes.hub(od), length = nozzle.length();
                double hubR = PipeSizes.hubOutside(od) / 2;
                int sides = PipeSizes.sides(od);
                // A unit's fixed casting ends 0.01 m ahead of the nozzle base. Blend its root into the connected neck and
                // close the casting around a smaller neck; the outer neck and flange keep the pipe's size.
                double taper = length - t - hub, neckStart = 0;
                var root = unit != null ? unit.nozzleRoots.get(k) : null;
                if (root != null) {
                    double end = .01 + Math.min(.12, taper - .01);
                    m.frustum(f, root, r, .01, end, sides, Material.MACHINE);
                    if (root > r) m.annulus(f, r, root, .01, sides, 1, Material.MACHINE);
                    neckStart = end;
                }
                // The neck's taper into the flange: a weld-neck hub.
                m.prism(f, r, neckStart, taper, sides, Material.PIPE);
                m.frustum(f, r, hubR, taper, length - t, sides, Material.PIPE);
                m.annulus(f, hubR, flange, length - t, sides, -1, Material.FLANGE);
                m.bolts(f, od, hubR, flange, length - t, -1, Material.BOLT);
                m.prism(f, flange, length - t, length, sides, Material.FLANGE);
                m.annulus(f, 0, flange, length, sides, 1, Material.FLANGE);
                if (nozzle.riser() > 0) {
                    // Back from the neck's start: a straight run, the elbow (bend radius 1 D of the line drawn) and the riser
                    // up into the head at its pole, a pipe's largest behind the neck's start (owner request 2026-10-16: the
                    // line from the drum's bottom to its port follows the pipe; owner decision 2026-10-21: a 1 m pipe has a 1 m root).
                    double bend = od, run = PipeSizes.MAX_OUTSIDE - bend, lift = nozzle.base()[1] + bend;
                    var down = new double[]{0, -1, 0};
                    m.prism(f, r, -run, 0, sides, Material.PIPE);
                    var corner = new double[]{nozzle.base()[0] - dir[0] * run, lift, nozzle.base()[2] - dir[2] * run};
                    m.torus(new Frame(corner, cross(down, neg(dir)), down), bend, r, 0, Math.PI / 2, 6, sides, Material.PIPE);
                    var foot = new double[]{corner[0] - dir[0] * bend, lift, corner[2] - dir[2] * bend};
                    m.prism(new Frame(foot, new double[]{0, 1, 0}, dir), r, 0, nozzle.riser() - lift, sides, Material.PIPE);
                }
                if (nozzle.riser() > 0 && hollow && openingRadius > 0) skirtPort(m, r);
                var sleeve = unit != null ? unit.sleeves.get(k) : null;
                if (sleeve != null) sleeve(m, f, sleeve, r);
                return m;
            });
        }

        /**
         * A unit nozzle's sleeve through a closed skirt round its line of radius {@code line}, at the line's size (owner
         * decision 2026-10-21: "Skirt hole needs to adapt to the size of the pipe to max 1m"): the hole, the line's
         * clearance wider, dark just in front of the skirt; the sleeve's tube {@link EquipmentShape#PORT_COLLAR} thick from
         * behind the skirt's face round it to 0.05 m proud, its bore and its front face.
         */
        private static void sleeve(Mesh m, Frame f, ProcessUnit.Sleeve sleeve, double line) {
            double port = line + EquipmentShape.PORT_CLEARANCE, rim = port + EquipmentShape.PORT_COLLAR;
            double face = sleeve.radius() - sleeve.fromAxis() + .01, front = face + .05;
            double deep = Math.max(0, Math.sqrt(Math.max(0, sleeve.apothem() * sleeve.apothem() - rim * rim)) - .02 - sleeve.fromAxis());
            int sides = Math.max(16, PipeSizes.sides(2 * rim));
            m.prism(f, rim, Math.min(deep, face), front, sides, Material.SUPPORT);
            m.bore(f, port, face, front, sides, Material.SUPPORT);
            m.annulus(f, port, rim, front, sides, 1, Material.SUPPORT);
            m.annulus(f, line, port, face, sides, 1, Material.OPENING);
        }

        /** Whether a frame point lies in nozzle {@code k} at size {@code code}: its neck, or its flange. */
        private boolean inNozzle(double[] p, int k, int code) {
            var nozzle = nozzles.get(k);
            double od = code == 0 ? nozzle.defaultOd() : PipeSizes.outsideOf(code);
            var base = nozzle.base();
            double s = (p[nozzle.axis()] - base[nozzle.axis()]) * nozzle.sign(), rho2 = 0;
            for (int a = 0; a < 3; a++) if (a != nozzle.axis()) rho2 += (p[a] - base[a]) * (p[a] - base[a]);
            double t = PipeSizes.flangeThickness(od), flange = PipeSizes.flange(od) / 2;
            double length = nozzle.length(), radius = od / 2;
            var root = unit != null ? unit.nozzleRoots.get(k) : null;
            if (root != null) {
                double end = .01 + Math.min(.12, length - t - PipeSizes.hub(od) - .01);
                if (s >= .01 && s <= end) {
                    double r = root + (radius - root) * (s - .01) / (end - .01);
                    return rho2 <= r * r;
                }
            }
            return s >= 0 && s <= length - t && rho2 <= radius * radius || s > length - t && s <= length && rho2 <= flange * flange;
        }

        private void cells(Facet facet, Set<Cell> out) {
            for (int x = floor(facet.min()[0] - EPS); x < ceil(facet.max()[0] + EPS); x++)
                for (int y = floor(facet.min()[1] - EPS); y < ceil(facet.max()[1] + EPS); y++)
                    for (int z = floor(facet.min()[2] - EPS); z < ceil(facet.max()[2] + EPS); z++)
                        if (piece(facet, x, y, z) != null) out.add(new Cell(x, y, z));
        }

        /**
         * Shell cells: every cell a facet or a nozzle reaches, a drum's fluid node, which carries its identity even where
         * nothing is drawn, and every cell a ladder's climb zone reaches ({@link #climbCells}). The other cells inside the vessel are interior, a hollow skirt's too: its collision stays a
         * full cylinder, so nothing spawns or is placed inside it, and the cells showing its inside are shell cells.
         */
        private Layout buildLayout() {
            int sx = shape.sizeX(), sy = shape.sizeY(), sz = shape.sizeZ();
            var facets = mesh.facets();
            var reached = new HashSet<Cell>();
            for (int i = 0; i < facets.size(); i++) {
                reached.clear();
                cells(facets.get(i), reached);
                for (var cell : reached) {
                    facetsByCell.computeIfAbsent(cell, c -> new ArrayList<>()).add(facets.get(i));
                    if (i >= irregularFrom) irregularColumns.add(column(cell.x(), cell.z()));
                }
            }
            var cells = new HashSet<>(facetsByCell.keySet());
            for (var nozzle : nozzleCells) cells.addAll(nozzle);
            if (fluidCell != null) cells.add(fluidCell);
            climbCells(cells);
            var shellCells = new ArrayList<Cell>();
            var interiorCells = new ArrayList<Cell>();
            for (int x = 0; x < sx; x++)
                for (int y = 0; y < sy; y++)
                    for (int z = 0; z < sz; z++) {
                        var cell = new Cell(x, y, z);
                        if (cells.contains(cell)) shellCells.add(cell);
                        else {
                            var p = transform.local(new double[]{x + .5, y + .5, z + .5});
                            if (unit != null ? unit.filled(p) : inVessel(p)) interiorCells.add(cell);
                        }
                    }
            return new Layout(shape, shellCells, interiorCells);
        }

        /** A column's or a heater's ladders; none on other shapes. */
        List<Access.Climb> climbs() {
            return column != null ? column.climbs() : unit != null ? unit.ladders() : List.of();
        }

        /**
         * The cells a ladder's climb zone reaches: Minecraft asks the block at a player's feet whether it is a ladder, so
         * every cell where a climbing player can stand is a shell cell, drawn or not (below a cage nothing is drawn in
         * front of the rungs). The footprint is the zone's rectangle; a cell counts when the rectangle meets its square.
         */
        private void climbCells(Set<Cell> out) {
            int sx = shape.sizeX(), sy = shape.sizeY(), sz = shape.sizeZ();
            for (var climb : climbs()) {
                var corners = climb.footprint();
                double[][] box = new double[4][];
                double x0 = Double.MAX_VALUE, x1 = -Double.MAX_VALUE, z0 = Double.MAX_VALUE, z1 = -Double.MAX_VALUE;
                for (int k = 0; k < 4; k++) {
                    box[k] = transform.point(new double[]{corners[k][0], 0, corners[k][1]});
                    x0 = Math.min(x0, box[k][0]);
                    x1 = Math.max(x1, box[k][0]);
                    z0 = Math.min(z0, box[k][2]);
                    z1 = Math.max(z1, box[k][2]);
                }
                double y0 = transform.point(new double[]{climb.x(), climb.base() - .1, climb.z()})[1], y1 = transform.point(new double[]{climb.x(), climb.top(), climb.z()})[1];
                for (int x = Math.max(0, floor(x0)); x < Math.min(sx, ceil(x1)); x++)
                    for (int z = Math.max(0, floor(z0)); z < Math.min(sz, ceil(z1)); z++) {
                        if (!meets(box, x, z)) continue;
                        for (int y = Math.max(0, floor(Math.min(y0, y1))); y < Math.min(sy, ceil(Math.max(y0, y1))); y++) out.add(new Cell(x, y, z));
                    }
            }
        }

        /** Whether the convex quadrilateral {@code q} (box x, z in [0] and [2], in order round it) meets the unit square at (x, z): separating axes. */
        private static boolean meets(double[][] q, int x, int z) {
            double[][] square = {{x, z}, {x + 1, z}, {x + 1, z + 1}, {x, z + 1}};
            double[][] quad = new double[4][];
            int[] order = {0, 1, 3, 2};
            for (int k = 0; k < 4; k++) quad[k] = new double[]{q[order[k]][0], q[order[k]][2]};
            for (var polygon : new double[][][]{square, quad})
                for (int k = 0; k < 4; k++) {
                    double nx = polygon[(k + 1) % 4][1] - polygon[k][1], nz = polygon[k][0] - polygon[(k + 1) % 4][0];
                    double a0 = Double.MAX_VALUE, a1 = -Double.MAX_VALUE, b0 = Double.MAX_VALUE, b1 = -Double.MAX_VALUE;
                    for (var p : square) {
                        a0 = Math.min(a0, p[0] * nx + p[1] * nz);
                        a1 = Math.max(a1, p[0] * nx + p[1] * nz);
                    }
                    for (var p : quad) {
                        b0 = Math.min(b0, p[0] * nx + p[1] * nz);
                        b1 = Math.max(b1, p[0] * nx + p[1] * nz);
                    }
                    if (a1 <= b0 + 1e-9 || b1 <= a0 + 1e-9) return false;
                }
            return true;
        }

        List<Facet> facetsIn(Cell cell) {
            return facetsByCell.getOrDefault(cell, List.of());
        }

        private volatile List<Moving> moving;

        /** The unit's moving parts, cut and gathered once (the client asks for them as it meets the unit). */
        List<Moving> moving() {
            var out = moving;
            if (out != null) return out;
            var parts = new ArrayList<Moving>();
            var motions = unit != null ? unit.motions() : List.<ProcessUnit.Motion>of();
            var byPart = new ArrayList<List<Facet>>();
            for (int k = 0; k < motions.size(); k++) byPart.add(new ArrayList<>());
            for (var facet : mesh.facets()) if (facet.part() >= 0) byPart.get(facet.part()).add(facet);
            for (int k = 0; k < motions.size(); k++) {
                var motion = motions.get(k).in(transform);
                var pivot = motion.pivot();
                var polygons = new ArrayList<Polygon>();
                double reach = cut(mesh, byPart.get(k), pivot, polygons);
                parts.add(new Moving(motion, List.copyOf(polygons), reach));
            }
            return moving = List.copyOf(parts);
        }

        /**
         * The polygons of {@code facets} (of {@code source}) cut at the cell grid as the chunk mesh cuts them, relative to
         * {@code origin}, into {@code out}; their farthest point from it.
         */
        private double cut(Mesh source, List<Facet> facets, double[] origin, List<Polygon> out) {
            double reach = 0;
            var pieces = new ArrayList<Polygon>();
            var reached = new HashSet<Cell>();
            for (var facet : facets) {
                // The cells the chunk mesh cuts the facet into (a facet in a cell face belongs to the cell on its inner
                // side): a piston rod's end lying on a block face was lost from the moving rod (2026-10-18).
                reached.clear();
                cells(facet, reached);
                for (var cell : reached) {
                    pieces.clear();
                    source.cell(facet, cell.x(), cell.y(), cell.z(), pieces);
                    for (var piece : pieces) {
                        var p = piece.positions().clone();
                        for (int i = 0; i < p.length; i += 3) {
                            p[i] += cell.x() - origin[0];
                            p[i + 1] += cell.y() - origin[1];
                            p[i + 2] += cell.z() - origin[2];
                            reach = Math.max(reach, Math.sqrt(p[i] * p[i] + p[i + 1] * p[i + 1] + p[i + 2] * p[i + 2]));
                        }
                        out.add(new Polygon(p, piece.uvs(), piece.normals(), piece.material()));
                    }
                }
            }
            return reach;
        }

        private volatile Floating floating;
        private volatile Roof roof;

        /** A floating-roof tank's roof in the box, from its layout (no surfaces); null for any other shape. */
        @Nullable Roof roof() {
            var out = roof;
            if (out != null || unit == null || unit.floating == null || unit.roofPlan == null) return out;
            var f = unit.floating;
            var plan = unit.roofPlan;
            var centre = transform.point(plan.centre());
            return roof = new Roof(centre[0], centre[2], plan.radius(), plan.inner(), f.deck(), plan.pontoon(), f.landed(), f.top(),
                    transform.point(f.pivot()), transform.direction(f.along()), f.length());
        }

        /** A floating-roof tank's client-drawn parts in the box, drawn and cut once; null for any other shape. */
        @Nullable Floating floating() {
            var out = floating;
            if (out != null || unit == null || unit.floating == null) return out;
            var drawn = unit.drawing();
            var f = drawn.floating;
            var pivot = transform.point(f.pivot());
            var roof = new ArrayList<Polygon>();
            var ladder = new ArrayList<Polygon>();
            for (var part : List.of(drawn.roof, drawn.ladder)) {
                var m = new Mesh();
                m.transform(transform);
                m.include(part);
                cut(m, m.facets(), part == drawn.roof ? new double[3] : pivot, part == drawn.roof ? roof : ladder);
            }
            var glass = transform.point(new double[]{f.glass()[0], 0, f.glass()[1]});
            return floating = new Floating(List.copyOf(roof), List.copyOf(ladder), f.landed(), f.top(), f.deck(), pivot, transform.direction(f.along()),
                    transform.direction(f.axis()), f.length(), f.runway(), new double[]{glass[0], glass[2]}, f.glassLow(), f.glassHigh(), transform.direction(f.facing()));
        }

        /** Body rows along the axis and their texture classes: rows wholly in the cylinder, clear of supports, opening and nozzles. */
        private void classify() {
            int cells = axial == 1 ? shape.sizeY() : axial == 0 ? shape.sizeX() : shape.sizeZ();
            double yB = shape.bottomTangent();
            bodyClasses = new int[cells];
            var classes = new HashMap<Integer, Integer>();
            var firstRows = new ArrayList<Integer>();
            for (int a = 0; a < cells; a++) {
                bodyClasses[a] = -1;
                var probe = new double[3];
                probe[axial] = a + .5;
                double low = transform.local(probe)[1] - .5;
                if (!body(low)) continue;
                // Rows of one class share geometry and the texture phase: region (skirt or shell) and course pair; a
                // design's class is its own.
                int signature = unit != null ? unit.bodyClass(low) : (low < yB ? 0 : 4) + Math.floorMod((int) Math.round(low) - floor(yB), 4);
                Integer known = classes.get(signature);
                if (known == null) {
                    known = firstRows.size();
                    classes.put(signature, known);
                    firstRows.add(a);
                }
                bodyClasses[a] = known;
            }
            representatives = firstRows.stream().mapToInt(Integer::intValue).toArray();
        }

        /**
         * Whether the frame rows [low, low + 1) lie wholly in the cylindrical shell or skirt, clear of every special part
         * (a column's: the cladding's lower edge, its flashing rings, and its platforms with their manways).
         */
        private boolean body(double low) {
            double high = low + 1, start = shape.skirt() ? 1 : shape.bottomTangent(), half = EquipmentShape.SADDLE_WIDTH / 2;
            // A design's body rows are its own ({@link ProcessUnit#bodyRows}): a tank's plain shell courses, clear of its nozzles.
            if (unit != null) return unit.bodyClass(low) >= 0 && clearOfNozzles(low, high);
            if (low < start - EPS || high > shape.topTangent() + EPS) return false;
            if (column != null) {
                double yB = shape.bottomTangent();
                if (low < yB + 1 - EPS && high > yB - 1 + EPS) return false;
                for (double y : column.flashingHeights()) if (low < y + ColumnStructure.FLASHING + EPS && high > y - EPS) return false;
                for (var span : column.platformSpans()) if (low < span[1] + EPS && high > span[0] - EPS) return false;
            }
            for (double centre : saddles) if (low < centre + half + EPS && high > centre - half - EPS) return false;
            if (openingRadius > 0 && low < openingHeight + openingRadius + .1 && high > openingHeight - openingRadius - .1) return false;
            if (slotHigh > 0 && low < slotHigh + EPS && high > slotLow - EPS) return false;
            // A drum's skirt meets its head under a band, with vents below it; a drum's manway and a boot take rows of their own.
            if (shape.skirt() && shape.drum() && low < shape.bottomTangent() + .1 + EPS && high > shape.bottomTangent() - .35 - EPS) return false;
            if (manway != null) {
                var m = manway.box();
                if (low < m[4] + EPS && high > m[1] - EPS) return false;
            }
            if (boot != null && low < boot.station() + boot.diameter() / 2 + .05 + EPS && high > boot.station() - boot.diameter() / 2 - .05 - EPS) return false;
            // A hollow skirt shows its bottom head in the rows it reaches.
            if (hollow && high > shape.bottomTangent() - shape.headDepth() - EPS && low < shape.bottomTangent() + EPS) return false;
            return clearOfNozzles(low, high);
        }

        /** Whether the frame rows [low, high) are clear of every nozzle's neck, flange and riser. */
        private boolean clearOfNozzles(double low, double high) {
            for (var nozzle : nozzles) {
                double reach = PipeSizes.flange(PipeSizes.MAX_OUTSIDE) / 2, y0, y1;
                if (nozzle.axis() == 1) {
                    y0 = Math.min(nozzle.base()[1], nozzle.face()[1]);
                    y1 = Math.max(nozzle.base()[1], nozzle.face()[1]);
                } else {
                    y0 = nozzle.base()[1] - reach;
                    y1 = Math.max(nozzle.base()[1] + reach, nozzle.riser());
                }
                if (low < y1 + EPS && high > y0 - EPS) return false;
            }
            return true;
        }

        int bodyClass(int a) {
            return a >= 0 && a < bodyClasses.length ? bodyClasses[a] : -1;
        }

        /** Whether a box point lies in the solid, the nozzles reaching its cell drawn at {@code codes} ({@link #cellCodes}). */
        boolean inside(double x, double y, double z, long codes) {
            var p = transform.local(new double[]{x, y, z});
            if (solid(p)) return true;
            var reaching = reaching(new Cell(floor(x), floor(y), floor(z)));
            for (int k = 0, j = 0; k < nozzles.size(); k++) {
                boolean keyed = j < reaching.length && reaching[j] == k;
                if (inNozzle(p, k, keyed ? code(codes, j) : 0)) return true;
                if (keyed) j++;
            }
            return false;
        }

        /** Inside the solid without its nozzles, at a frame point: the vessel, a column's access structure, a unit's parts, a manway. */
        private boolean solid(double[] p) {
            return inVessel(p) || column != null && column.inside(p) || unit != null && unit.inside(p) || manway != null && manway.inside(p);
        }

        /** Inside the vessel and its supports, at a frame point: what the interior cells fill. A unit has no vessel. */
        private boolean inVessel(double[] p) {
            return unit == null && (inBody(p) || shape.skirt() && inSkirtBase(p) || saddles.length > 0 && inSaddle(p) || inBoot(p));
        }

        private boolean inBody(double[] p) {
            double rc = circumradius(p[1]);
            return rc > 0 && inPolygon(p, rc);
        }

        /** Inside the regular N-gon of circumradius {@code r} around the axis, in cross-section. */
        private boolean inPolygon(double[] p, double r) {
            double apothem = r * apothemFactor, px = p[0] - axis, pz = p[2] - axis;
            for (int i = 0; i < n; i++) if (px * normalCos[i] + pz * normalSin[i] > apothem) return false;
            return true;
        }

        private boolean inSkirtBase(double[] p) {
            double plate = EquipmentShape.PLATE;
            if (p[1] < 0 || p[1] > plate + EquipmentShape.CHAIR) return false;
            if (p[1] <= plate) return inPolygon(p, ringRadius);
            int chairs = shape.anchorChairs();
            double apothem = radius * apothemFactor, dx = p[0] - axis, dz = p[2] - axis;
            for (int k = 0; k < chairs; k++) {
                int i = chairFacet(k, chairs);
                double r = dx * normalCos[i] + dz * normalSin[i], t = -dx * normalSin[i] + dz * normalCos[i];
                if (r >= apothem && r <= apothem + EquipmentShape.BASE_RING && Math.abs(t) <= EquipmentShape.CHAIR / 2) return true;
            }
            return false;
        }

        /**
         * Inside a saddle: the base plate; the top plate (within the outer N-gon and the contact sector); below the top
         * plate's outer edge, the web and the ribs.
         */
        private boolean inSaddle(double[] p) {
            double dx = p[0] - axis, dz = Math.abs(p[2] - axis), deck = ground + EquipmentShape.PLATE;
            for (double c : saddles) {
                // Half-open along the axis: a saddle centred on the sampling lattice (every saddle is, 1/8 of a T/T of
                // whole metres in from a tangent line D/4 deep) samples its own width and its web's, not a layer more.
                double along = p[1] - c;
                if (along < -EquipmentShape.SADDLE_WIDTH / 2 || along >= EquipmentShape.SADDLE_WIDTH / 2 || p[0] < ground || dx > 0 || dz > saddleLateral) continue;
                if (p[0] <= deck) return true;
                if (inPolygon(p, saddleRadius)) {
                    // Within the contact sector: the angle from straight down is at most half the contact angle.
                    if (dz * vertexCos[contact] <= -dx * vertexSin[contact]) return true;
                    continue;
                }
                if (dz >= saddleLateral - EquipmentShape.SADDLE_RIB || along >= -EquipmentShape.SADDLE_WEB / 2 && along < EquipmentShape.SADDLE_WEB / 2) return true;
            }
            return false;
        }

        private double circumradius(double y) {
            if (y < 0 || y > shape.top()) return 0;
            if (y >= (shape.skirt() ? 0 : shape.bottomTangent()) && y <= shape.topTangent()) return y < shape.bottomTangent() ? radius : outer;
            double off = y < shape.bottomTangent() ? shape.bottomTangent() - y : y - shape.topTangent();
            for (int k = 0; k + 1 < rings.length; k++) {
                double[] a = rings[k], b = rings[k + 1];
                if (off <= b[0]) return a[1] + (b[1] - a[1]) * (off - a[0]) / (b[0] - a[0]);
            }
            return 0;
        }

        /** The outline's fixed edges, built once; the silhouette's meridians follow the eye ({@link #silhouette}). */
        private volatile List<double[]> edges;

        List<double[]> edges() {
            var built = edges;
            if (built == null) edges = built = buildEdges();
            return built;
        }

        /**
         * The meridians on the silhouette seen from a box point: where the line from the eye touches the shell's
         * cross-section, at the shell vertices nearest the two tangent points; from inside that cross-section (looking
         * along the axis) the shell's rim at the tangent line nearer the eye.
         */
        void silhouette(double[] eye, List<double[]> out) {
            var e = transform.local(eye);
            if (unit != null) {
                var lines = new ArrayList<double[]>();
                unit.silhouette(e, lines);
                for (var l : lines) segment(out, new double[]{l[0], l[1], l[2]}, new double[]{l[3], l[4], l[5]});
                return;
            }
            double dx = e[0] - axis, dz = e[2] - axis, d = Math.sqrt(dx * dx + dz * dz);
            if (d <= outer) {
                double y = e[1] >= (shape.bottomTangent() + shape.topTangent()) / 2 ? shape.topTangent() : shape.bottomTangent();
                for (int i = 0; i < n; i++) segment(out, shell(i, y), shell(i + 1, y));
                return;
            }
            double towards = DeterministicMath.atan2(dz, dx), spread = DeterministicMath.acos(outer / d);
            for (double angle : new double[]{towards - spread, towards + spread})
                meridian(Math.floorMod((int) Math.round(angle * n / (2 * Math.PI)), n), out);
        }

        /** Meridian {@code i} from the vessel's foot (a skirt's base, or the bottom apex) over the top head to its apex. */
        private void meridian(int i, List<double[]> out) {
            double yB = shape.bottomTangent(), yT = shape.topTangent();
            var points = new ArrayList<double[]>();
            if (shape.skirt()) points.add(ring(i, EquipmentShape.PLATE));
            else for (int k = bottomRings.length - 1; k >= 0; k--) points.add(head(bottomRings, i, k, yB, -1));
            for (int k = 0; k < rings.length; k++) points.add(head(rings, i, k, yT, 1));
            for (int p = 0; p + 1 < points.size(); p++) segment(out, points.get(p), points.get(p + 1));
        }

        private List<double[]> buildEdges() {
            var segments = new ArrayList<double[]>();
            double plate = EquipmentShape.PLATE;
            if (column != null)
                for (var p : column.plan().platforms()) {
                    double y = p.level() + Access.DECK, r = column.plan().deckRadius();
                    int count = Math.max(2, (int) Math.ceil((p.to() - p.from()) * r / .5));
                    for (int j = 0; j < count; j++) {
                        double a0 = p.from() + (p.to() - p.from()) * j / count, a1 = p.from() + (p.to() - p.from()) * (j + 1) / count;
                        segment(segments, new double[]{axis + r * DeterministicMath.cos(a0), y, axis + r * DeterministicMath.sin(a0)},
                                new double[]{axis + r * DeterministicMath.cos(a1), y, axis + r * DeterministicMath.sin(a1)});
                    }
                }
            if (unit != null) for (var edge : unit.edges()) segment(segments, new double[]{edge[0], edge[1], edge[2]}, new double[]{edge[3], edge[4], edge[5]});
            if (shape.skirt())
                for (int i = 0; i < n; i++) segment(segments, rim(i, plate), rim(i + 1, plate));
            double deck = ground + plate;
            for (double c : saddles) {
                double y0 = c - EquipmentShape.SADDLE_WIDTH / 2, y1 = c + EquipmentShape.SADDLE_WIDTH / 2;
                double[][] corners = {{-saddleLateral, y0}, {saddleLateral, y0}, {saddleLateral, y1}, {-saddleLateral, y1}};
                for (int e = 0; e < 4; e++)
                    segment(segments, new double[]{deck, corners[e][1], axis + corners[e][0]}, new double[]{deck, corners[(e + 1) % 4][1], axis + corners[(e + 1) % 4][0]});
                for (int j : new int[]{n / 2 - contact, n / 2 + contact}) {
                    segment(segments, outer(j, y0), outer(j, y1));
                    for (double y : new double[]{y0, y1}) segment(segments, new double[]{deck, y, outer(j, 0)[2]}, outer(j, y));
                }
            }
            // Each nozzle's flange face as a square around its centre.
            for (var nozzle : nozzles) {
                var face = nozzle.face();
                double h = PipeSizes.flange(nozzle.defaultOd()) / 2;
                int a = (nozzle.axis() + 1) % 3, b = (nozzle.axis() + 2) % 3;
                double[][] square = new double[4][];
                for (int corner = 0; corner < 4; corner++) {
                    square[corner] = face.clone();
                    square[corner][a] += corner == 0 || corner == 3 ? -h : h;
                    square[corner][b] += corner < 2 ? -h : h;
                }
                for (int e = 0; e < 4; e++) segment(segments, square[e], square[(e + 1) % 4]);
            }
            return List.copyOf(segments);
        }

        private void segment(List<double[]> segments, double[] a, double[] b) {
            double[] p = transform.point(a), q = transform.point(b);
            segments.add(new double[]{p[0], p[1], p[2], q[0], q[1], q[2]});
        }

        private static double[] at(double[] p, double y) {
            return new double[]{p[0], y, p[2]};
        }

        /** Shell vertex {@code i} at axial position {@code y}, in the vessel frame (a column's under its cladding: the skirt's). */
        private double[] ring(int i, double y) {
            return new double[]{axis + radius * vertexCos[i], y, axis + radius * vertexSin[i]};
        }

        /** Vertex {@code i} of the outside surface (a column's cladding) at axial position {@code y}. */
        private double[] shell(int i, double y) {
            return new double[]{axis + outer * vertexCos[i], y, axis + outer * vertexSin[i]};
        }

        /** Vertex {@code i} of a saddle's top plate, outside the shell. */
        private double[] outer(int i, double y) {
            return new double[]{axis + saddleRadius * vertexCos[i], y, axis + saddleRadius * vertexSin[i]};
        }

        /** Vertex {@code i} of a skirt's base ring rim. */
        private double[] rim(int i, double y) {
            return new double[]{axis + ringRadius * vertexCos[i], y, axis + ringRadius * vertexSin[i]};
        }

        /** Point of ring {@code k} of head {@code h} at shell vertex {@code i}, the head leaving its tangent line {@code base} in direction {@code sign}. */
        private double[] head(double[][] h, int i, int k, double base, double sign) {
            return new double[]{axis + h[k][1] * vertexCos[i], base + sign * h[k][0], axis + h[k][1] * vertexSin[i]};
        }
    }
}
