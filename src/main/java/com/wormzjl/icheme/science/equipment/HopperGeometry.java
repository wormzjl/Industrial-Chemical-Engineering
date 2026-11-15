package com.wormzjl.icheme.science.equipment;

import java.util.ArrayList;
import java.util.List;

import static com.wormzjl.icheme.science.equipment.Mesh.Material;
import static com.wormzjl.icheme.science.equipment.Mesh.Polygon;

/**
 * A liquid hopper's block (batch 2026-11-15-fluid-interfaces, W4): a square funnel on a short square body, as a tote's
 * filling hopper. The funnel's four outer slopes, a thin rim, four inner slopes down to its dark throat; the body's four
 * sides and floor; its outlet a flanged stub to each joined face from the body's wall, down to a blind flange where
 * nothing is joined. It stands upright whatever its facing. Few facets: the body's five, the funnel's thirteen and the stubs.
 */
public final class HopperGeometry {
    /** The body's half width, bottom and top; the funnel's half width at its rim, the rim's inner half width, the throat's, and their heights (block units). */
    static final double BODY = .24, BOTTOM = .14, SHOULDER = .62, RIM = .44, LIP = .41, THROAT = .20, RIM_Y = .96, THROAT_Y = .66;

    private HopperGeometry() {}

    /** The drawn polygons in the block's cell, for the device's key ({@link LineGeometry.Key}, kind {@code GENERATOR}). */
    public static List<Polygon> polygons(LineGeometry.Key key, RenderDetail detail) {
        return mesh(key, detail).cell(0, 0, 0);
    }

    static Mesh mesh(LineGeometry.Key key, RenderDetail detail) {
        var m = new Mesh(detail);
        // The body: four sides and its floor; the funnel stands on its top.
        m.box(new double[]{.5 - BODY, BOTTOM, .5 - BODY}, new double[]{.5 + BODY, SHOULDER, .5 + BODY}, 0b110111, Material.MACHINE);
        // The funnel: per side an outer slope from the shoulder to the rim, a strip of rim, an inner slope down to the throat.
        double outRise = RIM_Y - SHOULDER, outRun = RIM - BODY, inRise = RIM_Y - THROAT_Y, inRun = LIP - THROAT;
        for (int side = 0; side < 4; side++) {
            m.flat(side(side, BODY, SHOULDER, RIM, RIM_Y), outward(side, outRise, -outRun), Material.MACHINE);
            m.flat(side(side, LIP, RIM_Y, RIM, RIM_Y), new double[]{0, 1, 0}, Material.MACHINE);
            m.flat(side(side, THROAT, THROAT_Y, LIP, RIM_Y), outward(side, -inRise, inRun), Material.MACHINE);
        }
        m.flat(List.of(new double[]{.5 - THROAT, THROAT_Y, .5 - THROAT}, new double[]{.5 + THROAT, THROAT_Y, .5 - THROAT},
                new double[]{.5 + THROAT, THROAT_Y, .5 + THROAT}, new double[]{.5 - THROAT, THROAT_Y, .5 + THROAT}), new double[]{0, 1, 0}, Material.OPENING);
        // The outlet: a flanged stub to each joined face, from the body's wall; a blind-flanged stub down where none is joined.
        double[] from = {.5 - BOTTOM, THROAT_Y - .5, BODY, BODY, BODY, BODY};
        boolean any = false;
        for (int face = 0; face < 6; face++) {
            if (!key.connected(face)) continue;
            any = true;
            double size = key.face(face) == 0 ? PipeSizes.outsideOf(key.outside()) : PipeSizes.outsideOf(key.face(face));
            LineGeometry.arm(m, face, from[face] - .02, size, LineGeometry.End.flanged(size, true), LineGeometry.ref(face), PipeSizes.sides(size), true);
        }
        if (!any) {
            double size = PipeSizes.outsideOf(key.outside());
            LineGeometry.arm(m, 0, from[0] - .02, size, LineGeometry.End.flanged(size, false), LineGeometry.ref(0), PipeSizes.sides(size), true);
        }
        return m;
    }

    /**
     * Side {@code side} (0 -x, 1 +x, 2 -z, 3 +z) of a band between half width {@code lo} at height {@code yLo} and half
     * width {@code hi} at {@code yHi}: a quadrilateral, its lower edge first.
     */
    private static List<double[]> side(int side, double lo, double yLo, double hi, double yHi) {
        var out = new ArrayList<double[]>(4);
        double s = side % 2 == 0 ? -1 : 1;
        boolean x = side < 2;
        for (double[] p : new double[][]{{lo, yLo, -1}, {lo, yLo, 1}, {hi, yHi, 1}, {hi, yHi, -1}}) {
            double across = .5 + s * p[0], along = .5 + p[2] * p[0];
            out.add(x ? new double[]{across, p[1], along} : new double[]{along, p[1], across});
        }
        return out;
    }

    /** A slope's normal on side {@code side}: {@code horizontal} outward from the axis and {@code vertical} up. */
    private static double[] outward(int side, double horizontal, double vertical) {
        double s = side % 2 == 0 ? -1 : 1;
        return side < 2 ? Mesh.unit(s * horizontal, vertical, 0) : Mesh.unit(0, vertical, s * horizontal);
    }
}
