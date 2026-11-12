package com.wormzjl.icheme.science.tutorial.scene;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Mesh;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;

import java.util.ArrayList;
import java.util.function.Predicate;

/** Actors made from the mod's geometry. */
public final class Actors {
    private Actors() {
    }

    /** Every shell cell's surface polygons of {@code shape}, moved by the cell's place: the equipment from its origin. */
    public static Actor equipment(String id, EquipmentShape shape) {
        return equipment(id, shape, centroid -> true);
    }

    /**
     * The polygons of {@link #equipment(String, EquipmentShape)} whose centroid {@code keep} accepts: a part of the
     * equipment as its own actor (a film cuts each part with its own plane).
     */
    public static Actor equipment(String id, EquipmentShape shape, Predicate<double[]> keep) {
        var out = new ArrayList<Mesh.Polygon>();
        for (var cell : ShapeRasterizer.layout(shape).shell())
            for (var polygon : ShapeRasterizer.surface(ShapeRasterizer.key(shape, cell.x(), cell.y(), cell.z()))) {
                var moved = moved(polygon, cell.x(), cell.y(), cell.z());
                if (keep.test(centroid(moved))) out.add(moved);
            }
        return new Actor(id, out);
    }

    /** {@code polygon} moved by (x, y, z). */
    public static Mesh.Polygon moved(Mesh.Polygon polygon, double x, double y, double z) {
        var p = polygon.positions().clone();
        for (int i = 0; i < p.length; i += 3) {
            p[i] += x;
            p[i + 1] += y;
            p[i + 2] += z;
        }
        return new Mesh.Polygon(p, polygon.uvs(), polygon.normals(), polygon.material());
    }

    /** The mean of a polygon's vertices. */
    public static double[] centroid(Mesh.Polygon polygon) {
        var p = polygon.positions();
        int n = polygon.size();
        double[] c = new double[3];
        for (int i = 0; i < n; i++)
            for (int a = 0; a < 3; a++) c[a] += p[3 * i + a] / n;
        return c;
    }
}
