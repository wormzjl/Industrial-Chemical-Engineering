package com.wormzjl.icheme.science.equipment;

import java.util.Optional;

/**
 * Where a named structure's nameplate sits (batch 2026-11-02-process-simulator, N1; owner 2026-11-03: "Give them a
 * nameplate on the model as well"). Plants put a tag plate on the shell at about eye height, on the side that faces the
 * access way: here the side the structure's front heading faces, 1.6 m above its base (its skirt, saddles or support),
 * flat against the shell, the space before it clear of nozzles, decks and ladders. Failing that, other heights, then
 * spots to either side, then a smaller plate, then the other three sides. Drawn, not placed: it adds no cell.
 */
public final class Nameplate {
    /** Eye height above the structure's base, m. */
    public static final double EYE = 1.6;
    /** The plate's sizes, m, largest first: width and height. */
    static final double[][] SIZES = {{0.9, 0.25}, {0.7, 0.2}, {0.5, 0.15}};
    /** How far a plate's corner may stand off a curved shell, m, and the clear space the plate needs before it. */
    static final double STAND_OFF = 0.08, CLEAR = 0.5;
    /** The plate stands this far off the shell, m, so it draws over it. */
    public static final double LIFT = 0.012;
    private static final double STEP = 0.05;

    /**
     * A plate: its centre in the structure's box (m, the box's least corner at 0), the heading its face looks along, its
     * width and height, m. The text runs along {@link #right()}: to the right of a player who faces the plate.
     */
    public record Plate(double x, double y, double z, EquipmentShape.Heading facing, double width, double height) {
        /** The box-local direction the text runs, {x, z}. */
        public int[] right() {
            return new int[]{facing.dz, -facing.dx};
        }
    }

    private Nameplate() {
    }

    /** The plate of {@code shape}; empty where no side has a clear spot. */
    public static Optional<Plate> of(EquipmentShape shape) {
        var front = shape.front();
        EquipmentShape.Heading[] sides = {front, front.counterClockwise(), front.counterClockwise().counterClockwise().counterClockwise(), front.counterClockwise().counterClockwise()};
        double base = shape.support() + EYE;
        var heights = new java.util.ArrayList<>(java.util.List.of(base, base - 0.4, base + 0.4, base - 0.8, base + 0.8, EYE, base + 1.6, base - 1.2));
        // Then up the body, for a vessel whose shell stands high above its base (a sphere).
        for (double h = base + 2.4; h < shape.sizeY() - 0.3; h += 0.8) heights.add(h);
        double[] across = {0, 0.6, -0.6, 1.2, -1.2};
        // The vessel's own shell first, then any solid (a designed unit's casing, a deck's edge).
        for (boolean vessel : new boolean[]{true, false})
        for (var side : sides)
            for (var size : SIZES)
                for (double h : heights)
                    for (double a : across) {
                        var plate = at(shape, vessel, side, a, h, size[0], size[1]);
                        if (plate != null) return Optional.of(plate);
                    }
        return Optional.empty();
    }

    /** The plate on side {@code side}, {@code across} m right of the box's middle, its centre {@code height} m above the box's floor; null where it does not fit. */
    static Plate at(EquipmentShape shape, boolean vessel, EquipmentShape.Heading side, double across, double height, double width, double tall) {
        double sx = shape.sizeX(), sy = shape.sizeY(), sz = shape.sizeZ();
        if (height - tall / 2 < 0.1 || height + tall / 2 > sy - 0.1) return null;
        int rx = side.dz, rz = -side.dx;
        double cx = sx / 2 + rx * across, cz = sz / 2 + rz * across;
        // The outermost shell point under the plate's corners and middle: the plate's plane stands there.
        double outer = Double.NEGATIVE_INFINITY, inner = Double.POSITIVE_INFINITY;
        for (int i = -1; i <= 1; i++)
            for (int j = -1; j <= 1; j++) {
                double px = cx + rx * i * width / 2, pz = cz + rz * i * width / 2, py = height + j * tall / 2;
                double out = surface(shape, vessel, side, px, py, pz);
                if (Double.isNaN(out)) return null;
                outer = Math.max(outer, out);
                inner = Math.min(inner, out);
            }
        if (outer - inner > STAND_OFF) return null;
        double plane = outer + LIFT;
        double x = cx + side.dx * (plane - outward(side, sx, sz, cx, cz)), z = cz + side.dz * (plane - outward(side, sx, sz, cx, cz));
        // Nothing before the plate: no nozzle, deck, rail or ladder in the space a reader looks through.
        for (int i = -2; i <= 2; i++)
            for (int j = -1; j <= 1; j++)
                for (double d = 0.02; d <= CLEAR; d += 0.04) {
                    double px = x + rx * i * width / 4 + side.dx * d, pz = z + rz * i * width / 4 + side.dz * d, py = height + j * tall / 2;
                    if (ShapeRasterizer.inside(shape, px, py, pz) || ShapeRasterizer.climbable(shape, px, py, pz)) return null;
                }
        return new Plate(x, height, z, side, width, tall);
    }

    /** A one-block device's tag, m: width and height. */
    public static final double TAG_WIDTH = 0.36, TAG_HEIGHT = 0.1;

    /**
     * A one-block device's tag (N1): a small plate on the side of its casing, {@code half} m from the block's centre, at
     * the centre's height, on the horizontal side across its run nearest the eye at ({@code eyeX}, {@code eyeZ}) (block
     * coordinates, the centre at 0), so a reader sees it from either side. {@code axis}: the run, 0 x, 1 y, 2 z. The
     * centre is relative to the block's centre.
     */
    public static Plate tag(int axis, double half, double eyeX, double eyeZ) {
        EquipmentShape.Heading side;
        if (axis == 0) side = eyeZ >= 0 ? EquipmentShape.Heading.SOUTH : EquipmentShape.Heading.NORTH;
        else if (axis == 2) side = eyeX >= 0 ? EquipmentShape.Heading.EAST : EquipmentShape.Heading.WEST;
        else side = Math.abs(eyeX) > Math.abs(eyeZ) ? (eyeX >= 0 ? EquipmentShape.Heading.EAST : EquipmentShape.Heading.WEST)
                : eyeZ >= 0 ? EquipmentShape.Heading.SOUTH : EquipmentShape.Heading.NORTH;
        double out = Math.min(0.5, half) + LIFT;
        return new Plate(side.dx * out, 0, side.dz * out, side, TAG_WIDTH, TAG_HEIGHT);
    }

    /**
     * How far out along {@code side} the vessel's surface stands at the box-local point (x, y, z) taken along that heading:
     * the coordinate along the heading, NaN where the line meets no vessel inside the box.
     */
    private static double surface(EquipmentShape shape, boolean vessel, EquipmentShape.Heading side, double x, double y, double z) {
        double sx = shape.sizeX(), sz = shape.sizeZ();
        if (x < 0 || x > sx || z < 0 || z > sz) return Double.NaN;
        // From just outside the box's face on that side, inward.
        double from = outward(side, sx, sz, sx / 2 + side.dx * sx, sz / 2 + side.dz * sz), to = outward(side, sx, sz, sx / 2 - side.dx * sx, sz / 2 - side.dz * sz);
        double reach = from - to;
        for (double s = 0; s <= reach; s += STEP) {
            double t = from - s;
            if (solid(shape, vessel, side, x, y, z, t)) {
                double lo = t, hi = t + STEP;
                for (int k = 0; k < 8; k++) {
                    double mid = (lo + hi) / 2;
                    if (solid(shape, vessel, side, x, y, z, mid)) lo = mid;
                    else hi = mid;
                }
                return lo;
            }
        }
        return Double.NaN;
    }

    /** The coordinate along {@code side} of the box-local point (x, z): x or z, signed so that out is larger. */
    private static double outward(EquipmentShape.Heading side, double sx, double sz, double x, double z) {
        return side.dx != 0 ? side.dx * x : side.dz * z;
    }

    /** Whether the vessel (with {@code vessel}) or any solid holds the point whose coordinate along {@code side} is {@code t}, its others those of (x, y, z). */
    private static boolean solid(EquipmentShape shape, boolean vessel, EquipmentShape.Heading side, double x, double y, double z, double t) {
        double px = side.dx != 0 ? side.dx * t : x, pz = side.dz != 0 ? side.dz * t : z;
        return vessel ? ShapeRasterizer.insideVessel(shape, px, y, pz) : ShapeRasterizer.inside(shape, px, y, pz);
    }
}
