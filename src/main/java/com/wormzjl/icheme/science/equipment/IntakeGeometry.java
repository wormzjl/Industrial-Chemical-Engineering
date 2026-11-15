package com.wormzjl.icheme.science.equipment;

import java.util.List;

import static com.wormzjl.icheme.science.equipment.Mesh.Material;
import static com.wormzjl.icheme.science.equipment.Mesh.Polygon;

/**
 * A gas intake's block (batch 2026-11-15-fluid-interfaces, W2): an air inlet filter house, as on a compressor's suction. A
 * square box whose four sides are louvres ({@link Material#MOTOR_LOUVRE}: the blades are relief in its normal texture, one
 * facet a side), under a rain hood that overhangs it; its outlet a flanged stub to each joined face, down to a blind flange
 * where nothing is joined. It stands upright whatever its facing: air comes in at the sides under the hood. Few facets:
 * the box's five, the hood's six and the stubs.
 */
public final class IntakeGeometry {
    /** The house's half width, its bottom and top, and the hood's half width at its eaves and at its crown, and their heights (block units). */
    static final double HALF = .34, BOTTOM = .22, TOP = .78, EAVES = .40, CROWN = .28, EAVES_Y = .80, CROWN_Y = .90;

    private IntakeGeometry() {}

    /** The drawn polygons in the block's cell, for the device's key ({@link LineGeometry.Key}, kind {@code GENERATOR}). */
    public static List<Polygon> polygons(LineGeometry.Key key, RenderDetail detail) {
        return mesh(key, detail).cell(0, 0, 0);
    }

    static Mesh mesh(LineGeometry.Key key, RenderDetail detail) {
        var m = new Mesh(detail);
        // The house: four louvred sides and its floor; the hood covers its roof.
        m.box(new double[]{.5 - HALF, BOTTOM, .5 - HALF}, new double[]{.5 + HALF, TOP, .5 + HALF}, 0b110011, Material.MOTOR_LOUVRE);
        m.box(new double[]{.5 - HALF, BOTTOM, .5 - HALF}, new double[]{.5 + HALF, TOP, .5 + HALF}, 0b000100, Material.MACHINE);
        // The rain hood: a flat crown, four slopes down to the eaves, and its underside.
        double lo = .5 - EAVES, hi = .5 + EAVES, cl = .5 - CROWN, ch = .5 + CROWN;
        m.flat(List.of(new double[]{cl, CROWN_Y, cl}, new double[]{ch, CROWN_Y, cl}, new double[]{ch, CROWN_Y, ch}, new double[]{cl, CROWN_Y, ch}), new double[]{0, 1, 0}, Material.MACHINE);
        m.flat(List.of(new double[]{lo, EAVES_Y, lo}, new double[]{hi, EAVES_Y, lo}, new double[]{hi, EAVES_Y, hi}, new double[]{lo, EAVES_Y, hi}), new double[]{0, -1, 0}, Material.MACHINE);
        double rise = CROWN_Y - EAVES_Y, run = EAVES - CROWN;
        m.flat(List.of(new double[]{hi, EAVES_Y, lo}, new double[]{hi, EAVES_Y, hi}, new double[]{ch, CROWN_Y, ch}, new double[]{ch, CROWN_Y, cl}), Mesh.unit(rise, run, 0), Material.MACHINE);
        m.flat(List.of(new double[]{lo, EAVES_Y, lo}, new double[]{cl, CROWN_Y, cl}, new double[]{cl, CROWN_Y, ch}, new double[]{lo, EAVES_Y, hi}), Mesh.unit(-rise, run, 0), Material.MACHINE);
        m.flat(List.of(new double[]{lo, EAVES_Y, hi}, new double[]{cl, CROWN_Y, ch}, new double[]{ch, CROWN_Y, ch}, new double[]{hi, EAVES_Y, hi}), Mesh.unit(0, run, rise), Material.MACHINE);
        m.flat(List.of(new double[]{lo, EAVES_Y, lo}, new double[]{hi, EAVES_Y, lo}, new double[]{ch, CROWN_Y, cl}, new double[]{cl, CROWN_Y, cl}), Mesh.unit(0, run, -rise), Material.MACHINE);
        // The outlet: a flanged stub to each joined face, from the house's wall; a blind-flanged stub down where none is joined.
        double[] from = {.5 - BOTTOM, CROWN_Y - .5, HALF, HALF, HALF, HALF};
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
}
