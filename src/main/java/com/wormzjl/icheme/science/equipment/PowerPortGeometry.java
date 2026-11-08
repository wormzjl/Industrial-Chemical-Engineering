package com.wormzjl.icheme.science.equipment;

import static com.wormzjl.icheme.science.equipment.Mesh.*;

import java.util.List;

/**
 * Orange-identified gland for the metallic power enclosure; the equal-sized data socket remains
 * yellow.
 */
public final class PowerPortGeometry {
    private PowerPortGeometry() {}

    public static List<Polygon> polygons(double[] centre, double[] outward, boolean box) {
        return polygons(centre, outward, box, RenderDetail.NORMAL);
    }

    public static List<Polygon> polygons(
            double[] centre, double[] outward, boolean box, RenderDetail detail) {
        return DataPortGeometry.polygons(centre, outward, box, CableMedium.POWER.outside(), detail)
                .stream()
                .map(
                        p ->
                                new Polygon(
                                        p.positions(),
                                        p.uvs(),
                                        p.normals(),
                                        p.material() == Material.GUARD
                                                ? Material.ACTUATOR
                                                : p.material()))
                .toList();
    }
}
