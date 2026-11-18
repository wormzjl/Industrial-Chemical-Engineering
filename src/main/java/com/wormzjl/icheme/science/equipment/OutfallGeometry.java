package com.wormzjl.icheme.science.equipment;

import java.util.List;

import static com.wormzjl.icheme.science.equipment.Mesh.Material;
import static com.wormzjl.icheme.science.equipment.Mesh.Polygon;

/**
 * A waste outfall's block (batch 2026-11-18-water-intake-outfall): a discharge pipe's open end, where the liquid falls out
 * to the air. The look is not final; the owner picks one of three options on rendered sheets:
 * <ul>
 * <li>{@link #GOOSENECK} (the placeholder drawn in game): the joined line turns down to an open, lipped mouth near the
 * block's floor;</li>
 * <li>{@link #APRON}: the same mouth higher up, over a concrete splash apron;</li>
 * <li>{@link #HEADWALL}: a concrete headwall the pipe passes through, open on the wall's far side, with its apron.</li>
 * </ul>
 * The joined faces get the line's own pipe (no flange at the mouth: the open end is the point). Only the mouth's bore is
 * dark: it is an open pipe end, as a stack top is.
 */
public final class OutfallGeometry {
    /** The options: a downturned open end, the same over a splash apron, a headwall. */
    public static final int GOOSENECK = 0, APRON = 1, HEADWALL = 2;
    /** The mouth's height for each option, the apron's thickness, the headwall's thickness and height (block units). */
    static final double MOUTH_Y = .10, APRON_MOUTH_Y = .34, APRON_T = .07, WALL_T = .22, WALL_TOP = .92;
    static final int SIDES = 12;

    private OutfallGeometry() {}

    /** The placeholder's drawn polygons in the block's cell, for the device's key ({@link LineGeometry.Key}, kind {@code VOID}). */
    public static List<Polygon> polygons(LineGeometry.Key key, RenderDetail detail) {
        return polygons(key, detail, GOOSENECK);
    }

    /** Option {@code option}'s drawn polygons (for the option sheets). */
    public static List<Polygon> polygons(LineGeometry.Key key, RenderDetail detail, int option) {
        return mesh(key, detail, option).cell(0, 0, 0);
    }

    /** Where the open mouth stands in the block (block units, the cell's corner at 0) and which way it faces: for the falling stream's effect. */
    public static double mouthHeight(int option) {
        return option == APRON || option == HEADWALL ? APRON_MOUTH_Y : MOUTH_Y;
    }

    static Mesh mesh(LineGeometry.Key key, RenderDetail detail, int option) {
        var m = new Mesh(detail);
        double od = PipeSizes.outsideOf(key.outside()), r = od / 2;
        // The first joined side face: the line comes in there (below where none is: the gooseneck then stands on its own).
        int in = -1;
        for (int face = 2; face < 6 && in < 0; face++) if (key.connected(face)) in = face;
        if (option == HEADWALL) {
            int across = in < 0 ? 2 : in;
            int axis = LineGeometry.axis(across);
            // The wall across the line at the block's centre, on its apron, the pipe through it to an open, lipped end beyond.
            double[] lo = {.06, APRON_T, .06}, hi = {.94, WALL_TOP, .94};
            lo[axis] = .5 - WALL_T / 2;
            hi[axis] = .5 + WALL_T / 2;
            m.box(lo, hi, 0b111111, Material.CONCRETE);
            m.box(new double[]{0, 0, 0}, new double[]{1, APRON_T, 1}, 0b111110, Material.CONCRETE);
            double sign = across % 2 == 0 ? 1 : -1;
            var out = new Mesh.Frame(new double[]{.5, APRON_MOUTH_Y + r, .5}, axisVector(axis, sign), LineGeometry.ref(across));
            m.prism(out, r, -.5, .5 * WALL_T + .12, SIDES, Material.PIPE);
            m.hoop(out, r, r + .025, .5 * WALL_T + .09, .5 * WALL_T + .12, SIDES, Material.FLANGE);
            m.bore(out, r * .9, .5 * WALL_T + .02, .5 * WALL_T + .12, SIDES, Material.PIPE);
            m.disc(out, r * .9, .5 * WALL_T + .02, SIDES, 1, Material.OPENING);
            for (int face = 0; face < 6; face++) if (face != across && key.connected(face)) arm(m, key, face, 0);
            return m;
        }
        double mouth = mouthHeight(option);
        var down = Mesh.Frame.of(new double[]{.5, .5, .5}, 1, -1);
        // The leg down from the block's centre to the mouth, its lip, its open bore.
        m.prism(down, r, -r, .5 - mouth, SIDES, Material.PIPE);
        m.annulus(down, 0, r, -r, SIDES, -1, Material.PIPE);
        m.hoop(down, r, r + .025, .5 - mouth - .03, .5 - mouth, SIDES, Material.FLANGE);
        m.bore(down, r * .9, .5 - mouth - .08, .5 - mouth, SIDES, Material.PIPE);
        m.disc(down, r * .9, .5 - mouth - .08, SIDES, 1, Material.OPENING);
        if (option == APRON) m.box(new double[]{.04, 0, .04}, new double[]{.96, APRON_T, .96}, 0b111110, Material.CONCRETE);
        for (int face = 1; face < 6; face++) if (key.connected(face)) arm(m, key, face, 0);
        return m;
    }

    private static void arm(Mesh m, LineGeometry.Key key, int face, double from) {
        double size = key.face(face) == 0 ? PipeSizes.outsideOf(key.outside()) : PipeSizes.outsideOf(key.face(face));
        LineGeometry.arm(m, face, from, size, LineGeometry.End.flanged(size, true), LineGeometry.ref(face), PipeSizes.sides(size), true);
    }

    /** The unit vector along box axis {@code axis} (0 x, 1 y, 2 z) with sign {@code sign}. */
    private static double[] axisVector(int axis, double sign) {
        var v = new double[3];
        v[axis] = sign;
        return v;
    }
}
