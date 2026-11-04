package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.math.DeterministicMath;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.jetbrains.annotations.Nullable;

/**
 * The largest pipe a nozzle takes (owner decision 2026-10-27, option (C) of batch 2026-10-26-multi-machine-framework's
 * NOZZLE_ROOM_GATE_REVIEW). A column's nozzle takes a pipe up to the largest bore, in {@link #STEP}s, whose neck stands
 * clear of the rest of the column (its shell and head, decks, rails, ladders, davit and the other nozzles) beyond
 * {@link #SURFACE} from where it leaves the vessel, and never less than the pipe it was designed for: a column's access
 * stands round its nozzles, so the nozzle takes what its place allows. A boot drum's oil outlet beside its front saddle
 * takes the bore that keeps its flange clear of the saddle ({@link EquipmentShape.Boot#largest}, owner decision
 * 2026-10-28). Every other nozzle takes a pipe's largest, as its parts give way to it (owner decision 2026-10-21).
 * Computed from the shape, never saved.
 */
public final class NozzleBore {
    /** The bores tried, m. */
    public static final double STEP = .05;
    /** How far beyond where it leaves the vessel a neck may still meet the vessel's own rings (girth flanges, pads), m. */
    public static final double SURFACE = .1;
    /** The step along the neck at which {@link #leaves} looks for where a ring has left the solid, m. */
    private static final double ALONG = .02;
    private static final int CACHE = 64;
    private static final Map<EquipmentShape, double[]> BORES = new ConcurrentHashMap<>();
    /** The last proposed shape searched and its solid: a shape's nozzles are asked one after another, and only one geometry stays. */
    private static volatile @Nullable Probe probe;

    private record Probe(EquipmentShape shape, ShapeRasterizer.Solid solid) {
    }

    private NozzleBore() {
    }

    /** The largest bore of a pipe on nozzle {@code k} of {@code shape}, m. */
    public static double largest(EquipmentShape shape, int k) {
        return largest(shape, k, NozzleBore::world);
    }

    /**
     * {@link #largest} for a size the process simulator proposes, a shape no world places: its search reads the world's geometry
     * where one is held, else one built for it and not kept, so the proposals leave {@link ShapeRasterizer}'s cache as the world
     * left it (each new proposed column would otherwise stay there, 12 MB, until the facet bound clears the world's shapes with it).
     * The bores are the same.
     */
    public static double proposed(EquipmentShape shape, int k) {
        return largest(shape, k, NozzleBore::probe);
    }

    private static double largest(EquipmentShape shape, int k, Function<EquipmentShape, ShapeRasterizer.Solid> solids) {
        if (shape.kind() != EquipmentShape.Kind.COLUMN) {
            // A boot drum's oil outlet beside its front saddle keeps its flange clear of it (EquipmentShape.bootFor).
            var boot = shape.bootPlace();
            return boot != null && !Double.isNaN(boot.outlet()) && shape.nozzles().get(k).role() == EquipmentShape.Role.LIQUID_OUTLET ? boot.largest() : PipeSizes.MAX_BORE;
        }
        var bores = BORES.get(shape);
        if (bores == null) {
            if (BORES.size() >= CACHE) BORES.clear();
            var fresh = new double[shape.nozzles().size()];
            Arrays.fill(fresh, Double.NaN);
            bores = BORES.computeIfAbsent(shape, s -> fresh);
        }
        double bore = bores[k];
        if (Double.isNaN(bore)) bores[k] = bore = search(shape, solids.apply(shape), k);
        return bore;
    }

    /** Whether a pipe of bore {@code bore} fits nozzle {@code k} of {@code shape}. */
    public static boolean fits(EquipmentShape shape, int k, double bore) {
        return bore <= largest(shape, k) + 1e-9;
    }

    /** From the design pipe up, in {@link #STEP}s to {@link PipeSizes#MAX_BORE}, the last bore whose neck stands clear; the design pipe where none does. */
    private static double search(EquipmentShape shape, ShapeRasterizer.Solid solid, int k) {
        double design = shape.nozzles().get(k).defaultOd() / 1.1, best = design;
        for (double bore = (Math.floor(design / STEP + 1e-9) + 1) * STEP; bore <= PipeSizes.MAX_BORE + 1e-9; bore += STEP) {
            if (!clear(shape, solid, k, PipeSizes.outside(bore))) break;
            best = bore;
        }
        // Every bore from the one that draws at a pipe's largest outside up is the same neck.
        return PipeSizes.outside(best) >= PipeSizes.MAX_OUTSIDE - 1e-9 ? PipeSizes.MAX_BORE : best;
    }

    /**
     * Whether nozzle {@code k}'s neck at outside diameter {@code od} stands clear of the rest of the shape's solid, beyond
     * {@link #SURFACE} from where it has left the vessel. Where it leaves: the first point along the neck where a ring of
     * its size is wholly out of the solid, looked for from where a ring just round its unconnected neck has left (its own
     * vessel's surface there) up to half a pipe's largest beyond, the most a convex body's curve holds a neck's edge
     * behind its centre line, or up to its flange's hub; a neck that has not left the solid by then meets it.
     */
    public static boolean clear(EquipmentShape shape, int k, double od) {
        return clear(shape, world(shape), k, od);
    }

    private static boolean clear(EquipmentShape shape, ShapeRasterizer.Solid solid, int k, double od) {
        var nozzle = shape.nozzles().get(k);
        double to = nozzle.length() - PipeSizes.flangeThickness(od) - PipeSizes.hub(od) - .01, radius = od / 2 + .01;
        double near = Math.max(0, leaves(shape, solid, nozzle, nozzle.defaultOd() / 2 + .01, 0, to));
        double out = leaves(shape, solid, nozzle, radius, near, Math.min(to, near + PipeSizes.MAX_OUTSIDE / 2)), from = out + SURFACE;
        if (out < 0) return false;
        int rings = Math.max(1, (int) Math.ceil((to - from) / .05));
        for (int i = 0; i <= rings && to > from; i++)
            if (!ringClear(shape, solid, nozzle, radius, from + (to - from) * i / rings)) return false;
        return true;
    }

    /** A world shape's solid: {@link ShapeRasterizer#inside} at each point, on the geometry the world's cache holds. */
    private static ShapeRasterizer.Solid world(EquipmentShape shape) {
        return (x, y, z) -> ShapeRasterizer.inside(shape, x, y, z);
    }

    /** A proposed shape's solid, the probe's where it was the last shape searched. */
    private static ShapeRasterizer.Solid probe(EquipmentShape shape) {
        var last = probe;
        if (last != null && last.shape().equals(shape)) return last.solid();
        var solid = ShapeRasterizer.solid(shape);
        probe = new Probe(shape, solid);
        return solid;
    }

    /** The first distance from {@code from} to {@code to} along the neck, in {@link #ALONG}s, where a ring of {@code radius} is wholly out of the solid; -1 where there is none. */
    private static double leaves(EquipmentShape shape, ShapeRasterizer.Solid solid, EquipmentShape.Nozzle nozzle, double radius, double from, double to) {
        for (double s = from; s <= to + 1e-9; s += ALONG) if (ringClear(shape, solid, nozzle, radius, s)) return s;
        return -1;
    }

    /** Whether the ring of {@code radius} round the nozzle's axis, {@code s} from its base, lies wholly out of the shape's solid. */
    private static boolean ringClear(EquipmentShape shape, ShapeRasterizer.Solid solid, EquipmentShape.Nozzle nozzle, double radius, double s) {
        var transform = shape.transform();
        int axis = nozzle.axis(), u = (axis + 1) % 3, v = (axis + 2) % 3, around = 24;
        for (int j = 0; j < around; j++) {
            double angle = 2 * Math.PI * j / around;
            var p = nozzle.base().clone();
            p[axis] += nozzle.sign() * s;
            p[u] += radius * DeterministicMath.cos(angle);
            p[v] += radius * DeterministicMath.sin(angle);
            var box = transform.point(p);
            if (solid.inside(box[0], box[1], box[2])) return false;
        }
        return true;
    }
}
