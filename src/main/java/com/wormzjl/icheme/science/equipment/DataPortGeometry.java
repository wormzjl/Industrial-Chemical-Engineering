package com.wormzjl.icheme.science.equipment;

import static com.wormzjl.icheme.science.equipment.Mesh.*;

import java.util.*;

/** Weatherproof equipment bus socket. Its cable entry can sit off-centre on a block face. */
public final class DataPortGeometry {
    private DataPortGeometry() {}

    public static List<Polygon> polygons(double[] centre, double[] outward) {
        return polygons(centre, outward, false);
    }

    public static List<Polygon> polygons(double[] centre, double[] outward, boolean junctionBox) {
        return polygons(centre, outward, junctionBox, CableMedium.DATA.outside());
    }

    public static List<Polygon> polygons(
            double[] centre, double[] outward, boolean junctionBox, RenderDetail detail) {
        return polygons(centre, outward, junctionBox, CableMedium.DATA.outside(), detail);
    }

    static List<Polygon> polygons(
            double[] centre, double[] outward, boolean junctionBox, double cableOutside) {
        return polygons(centre, outward, junctionBox, cableOutside, RenderDetail.NORMAL);
    }

    static List<Polygon> polygons(
            double[] centre,
            double[] outward,
            boolean junctionBox,
            double cableOutside,
            RenderDetail detail) {
        var mesh = new Mesh(detail);
        double opening = Math.max(.035, cableOutside / 2 + .00375);
        double gland = Math.max(.075, opening / .8660254037844386 + .012);
        var ref = Math.abs(outward[1]) < .8 ? new double[] {0, 1, 0} : new double[] {1, 0, 0};
        var f = new Frame(centre, outward, ref);
        if (junctionBox) {
            mesh.block(
                    f.along(-.10),
                    scale(f.ref(), .13),
                    scale(f.side(), .13),
                    scale(outward, .08),
                    Material.MACHINE);
            mesh.block(
                    f.along(-.012),
                    scale(f.ref(), .115),
                    scale(f.side(), .115),
                    scale(outward, .008),
                    Material.FLANGE);
            for (int a : new int[] {-1, 1})
                for (int b : new int[] {-1, 1})
                    mesh.nut(
                            new Frame(
                                    f.at(
                                            com.wormzjl.icheme.science.math.DeterministicMath.atan2(
                                                    b, a),
                                            .13,
                                            -.003),
                                    outward,
                                    ref),
                            .008,
                            0,
                            .01,
                            6,
                            Material.BOLT);
        }
        mesh.cylinder(f, Math.max(.03, cableOutside / 2), -.22, -.04, 8, Material.PIPE);
        mesh.cylinder(f, .11, -.04, .015, 8, Material.FLANGE);
        mesh.prism(f, gland, .015, .05, 6, Material.PIPE);
        mesh.annulus(f, opening, gland, .05, 6, 1, Material.GUARD);
        mesh.cylinder(f, opening, .025, .055, 8, Material.OPENING);
        return LineGeometry.overhanging(mesh);
    }
}
