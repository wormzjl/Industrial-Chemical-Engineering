package com.wormzjl.icheme.science.equipment;

import java.util.List;

import static com.wormzjl.icheme.science.equipment.Mesh.Material;
import static com.wormzjl.icheme.science.equipment.Mesh.Polygon;

/**
 * A water intake's block (batch 2026-11-18-water-intake-outfall): the strainer on the end of a pump's suction line under
 * a river's or lake's surface. The look is not final; the owner picks one of three options on rendered sheets:
 * <ul>
 * <li>{@link #BASKET} (the placeholder drawn in game): an upright strainer basket, a perforated drum (its holes are the
 * grating's relief) between a solid top and bottom;</li>
 * <li>{@link #BELL}: a bell-mouth flaring down inside a cage of vertical screen bars;</li>
 * <li>{@link #TEE}: a lying T-screen, a perforated drum either side of the joined riser.</li>
 * </ul>
 * Its outlet is a flanged stub to each joined face, down to a blind flange where nothing is joined (as {@link IntakeGeometry}).
 * It stands upright whatever its facing.
 */
public final class WaterIntakeGeometry {
    /** The options: an upright strainer basket, a bell-mouth with a bar screen, a lying T-screen. */
    public static final int BASKET = 0, BELL = 1, TEE = 2;
    /** The basket's radius, bottom and top; the bell's throat and mouth radii and heights; the cage's radius and bars; the T-screen's drum radius and centre height (block units). */
    static final double BASKET_R = .30, BASKET_BOTTOM = .12, BASKET_TOP = .78, THROAT_R = .12, MOUTH_R = .32, THROAT_Y = .66, MOUTH_Y = .30,
            CAGE_R = .40, CAGE_BOTTOM = .04, CAGE_TOP = .70, BAR = .035, TEE_R = .19, TEE_Y = .30;
    static final int SIDES = 12, BARS = 14;

    private WaterIntakeGeometry() {}

    /** The placeholder's drawn polygons in the block's cell, for the device's key ({@link LineGeometry.Key}, kind {@code GENERATOR}). */
    public static List<Polygon> polygons(LineGeometry.Key key, RenderDetail detail) {
        return polygons(key, detail, BASKET);
    }

    /** Option {@code option}'s drawn polygons (for the option sheets). */
    public static List<Polygon> polygons(LineGeometry.Key key, RenderDetail detail, int option) {
        return mesh(key, detail, option).cell(0, 0, 0);
    }

    static Mesh mesh(LineGeometry.Key key, RenderDetail detail, int option) {
        var m = new Mesh(detail);
        var up = Mesh.Frame.of(new double[]{.5, 0, .5}, 1, 1);
        double[] from;
        switch (option) {
            case BELL -> {
                // The bell-mouth flares down from its throat; the cage of screen bars stands round it on a ring, under a top plate.
                m.frustum(up, MOUTH_R, THROAT_R, MOUTH_Y, THROAT_Y, SIDES, Material.MACHINE);
                m.bore(up, THROAT_R * .9, THROAT_Y, THROAT_Y + .02, SIDES, Material.MACHINE);
                m.annulus(up, 0, CAGE_R, CAGE_TOP, SIDES, 1, Material.MACHINE);
                m.annulus(up, 0, CAGE_R, CAGE_TOP - .04, SIDES, -1, Material.MACHINE);
                m.prism(up, CAGE_R, CAGE_TOP - .04, CAGE_TOP, SIDES, Material.MACHINE);
                m.hoop(up, CAGE_R - BAR, CAGE_R + .01, CAGE_BOTTOM, CAGE_BOTTOM + .04, SIDES, Material.MACHINE);
                m.disc(up, CAGE_R - BAR, CAGE_BOTTOM + .01, SIDES, 1, Material.GRATING);
                m.prism(up, THROAT_R, THROAT_Y, CAGE_TOP - .04, SIDES, Material.PIPE);
                for (int k = 0; k < BARS; k++) {
                    double a = 2 * Math.PI * (k + .5) / BARS;
                    var lo = up.at(a, CAGE_R - BAR / 2, CAGE_BOTTOM + .04);
                    var hi = up.at(a, CAGE_R - BAR / 2, CAGE_TOP - .04);
                    m.bar(lo, hi, BAR, new double[]{0, 0, 1}, Mesh.NO_ENDS, Material.MACHINE);
                }
                from = new double[]{.5 - CAGE_BOTTOM, CAGE_TOP - .5, CAGE_R, CAGE_R, CAGE_R, CAGE_R};
            }
            case TEE -> {
                // Two perforated drums along x either side of the riser, their ends closed by plates.
                var along = Mesh.Frame.of(new double[]{0, TEE_Y, .5}, 0, 1);
                m.prism(along, TEE_R, .04, .40, SIDES, Material.GRATING);
                m.prism(along, TEE_R, .60, .96, SIDES, Material.GRATING);
                m.cylinder(along, TEE_R + .02, .40, .60, SIDES, Material.MACHINE);
                m.ring(along, 0, TEE_R + .015, .02, .04, SIDES, 3, Material.MACHINE);
                m.ring(along, 0, TEE_R + .015, .96, .98, SIDES, 3, Material.MACHINE);
                // The riser to the block's centre.
                m.prism(up, .09, TEE_Y + TEE_R, .5, SIDES, Material.PIPE);
                from = new double[]{.5 - TEE_Y + TEE_R, 0, .1, .1, .1, .1};
            }
            default -> {
                // The basket: a perforated drum, a solid floor, a lid with its rim, bands at top and bottom.
                m.prism(up, BASKET_R, BASKET_BOTTOM + .04, BASKET_TOP - .04, SIDES, Material.GRATING);
                m.hoop(up, BASKET_R - .01, BASKET_R + .02, BASKET_BOTTOM, BASKET_BOTTOM + .05, SIDES, Material.MACHINE);
                m.hoop(up, BASKET_R - .01, BASKET_R + .02, BASKET_TOP - .05, BASKET_TOP, SIDES, Material.MACHINE);
                m.disc(up, BASKET_R, BASKET_BOTTOM, SIDES, -1, Material.MACHINE);
                m.disc(up, BASKET_R, BASKET_TOP, SIDES, 1, Material.MACHINE);
                from = new double[]{.5 - BASKET_BOTTOM, BASKET_TOP - .5, BASKET_R, BASKET_R, BASKET_R, BASKET_R};
            }
        }
        stubs(m, key, from, option == TEE ? 1 : 0);
        return m;
    }

    /** A flanged stub to each joined face from the body's wall {@code from}; a blind-flanged stub at face {@code blind} where none is joined. */
    static void stubs(Mesh m, LineGeometry.Key key, double[] from, int blind) {
        boolean any = false;
        for (int face = 0; face < 6; face++) {
            if (!key.connected(face)) continue;
            any = true;
            double size = key.face(face) == 0 ? PipeSizes.outsideOf(key.outside()) : PipeSizes.outsideOf(key.face(face));
            LineGeometry.arm(m, face, from[face] - .02, size, LineGeometry.End.flanged(size, true), LineGeometry.ref(face), PipeSizes.sides(size), true);
        }
        if (!any) {
            double size = PipeSizes.outsideOf(key.outside());
            LineGeometry.arm(m, blind, from[blind] - .02, size, LineGeometry.End.flanged(size, false), LineGeometry.ref(blind), PipeSizes.sides(size), true);
        }
    }
}
