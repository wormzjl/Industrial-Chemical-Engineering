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

    /** {@code actor}'s polygons moved by (x, y, z), as actor {@code id}: equipment set beside another on the scene. */
    public static Actor moved(String id, Actor actor, double x, double y, double z) {
        var out = new ArrayList<Mesh.Polygon>(actor.polygons().size());
        for (var polygon : actor.polygons()) out.add(moved(polygon, x, y, z));
        return new Actor(id, out);
    }

    /** A box from {@code lo} to {@code hi} (m) of {@code material}, its six faces counter-clockwise from outside, its texture a tile a metre: a ground plate, a block. */
    public static Actor box(String id, double[] lo, double[] hi, Mesh.Material material) {
        return new Actor(id, boxFaces(lo, hi, material));
    }

    private static java.util.List<Mesh.Polygon> boxFaces(double[] lo, double[] hi, Mesh.Material material) {
        var faces = new ArrayList<Mesh.Polygon>();
        int[][] axes = {{0, 1, 2}, {1, 2, 0}, {2, 0, 1}};
        for (var a : axes)
            for (int sign : new int[]{-1, 1}) {
                double[][] corners = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
                double[] p = new double[12], n = new double[12], uv = new double[8];
                for (int k = 0; k < 4; k++) {
                    var c = corners[sign > 0 ? k : 3 - k];
                    p[3 * k + a[0]] = sign > 0 ? hi[a[0]] : lo[a[0]];
                    p[3 * k + a[1]] = c[0] > 0 ? hi[a[1]] : lo[a[1]];
                    p[3 * k + a[2]] = c[1] > 0 ? hi[a[2]] : lo[a[2]];
                    n[3 * k + a[0]] = sign;
                    uv[2 * k] = p[3 * k + a[1]];
                    uv[2 * k + 1] = p[3 * k + a[2]];
                }
                faces.add(new Mesh.Polygon(p, uv, n, material));
            }
        return faces;
    }

    /** A plain square run of {@code side} m through {@code points}, each leg its own box of {@code material}: a hose or a pipe joining two actors. */
    public static Actor pipe(String id, java.util.List<double[]> points, double side, Mesh.Material material) {
        var out = new ArrayList<Mesh.Polygon>();
        double h = side / 2;
        for (int i = 0; i + 1 < points.size(); i++) {
            var a = points.get(i);
            var b = points.get(i + 1);
            double[] lo = new double[3], hi = new double[3];
            for (int k = 0; k < 3; k++) {
                lo[k] = Math.min(a[k], b[k]) - h;
                hi[k] = Math.max(a[k], b[k]) + h;
            }
            out.addAll(boxFaces(lo, hi, material));
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
