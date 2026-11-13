package com.wormzjl.icheme.science.tutorial.scene;

import com.wormzjl.icheme.science.equipment.Mesh;

import java.util.ArrayList;
import java.util.List;

/** A plane's clip of convex polygons (Sutherland-Hodgman against one plane), for a cutaway ({@link Track.Cut}). */
public final class Clip {
    /** A vertex within this distance of the plane counts as on it, m. */
    static final double EPS = 1e-9;

    private Clip() {
    }

    /** Each polygon's part on the kept side of the plane through {@code point} with {@code normal} (the normal points at the part removed). */
    public static List<Mesh.Polygon> keep(List<Mesh.Polygon> polygons, double[] point, double[] normal) {
        var out = new ArrayList<Mesh.Polygon>(polygons.size());
        for (var polygon : polygons) {
            var kept = keep(polygon, point, normal);
            if (kept != null) out.add(kept);
        }
        return List.copyOf(out);
    }

    /**
     * {@code polygon}'s part where {@code (p - point) . normal <= 0}: the polygon itself where it lies wholly there, null
     * where nothing is left. New vertices on the plane interpolate the texture coordinates and the normals (normalised).
     */
    public static Mesh.Polygon keep(Mesh.Polygon polygon, double[] point, double[] normal) {
        int n = polygon.size();
        var p = polygon.positions();
        double[] d = new double[n];
        boolean out = false, in = false;
        for (int i = 0; i < n; i++) {
            d[i] = (p[3 * i] - point[0]) * normal[0] + (p[3 * i + 1] - point[1]) * normal[1] + (p[3 * i + 2] - point[2]) * normal[2];
            if (d[i] > EPS) out = true;
            else in = true;
        }
        if (!out) return polygon;
        if (!in) return null;
        var uv = polygon.uvs();
        var nm = polygon.normals();
        var positions = new ArrayList<double[]>();
        var uvs = new ArrayList<double[]>();
        var normals = new ArrayList<double[]>();
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            boolean keepI = d[i] <= EPS, keepJ = d[j] <= EPS;
            if (keepI) {
                positions.add(new double[]{p[3 * i], p[3 * i + 1], p[3 * i + 2]});
                uvs.add(new double[]{uv[2 * i], uv[2 * i + 1]});
                normals.add(new double[]{nm[3 * i], nm[3 * i + 1], nm[3 * i + 2]});
            }
            if (keepI != keepJ) {
                double s = Math.max(0, Math.min(1, d[i] / (d[i] - d[j])));
                positions.add(new double[]{p[3 * i] + (p[3 * j] - p[3 * i]) * s, p[3 * i + 1] + (p[3 * j + 1] - p[3 * i + 1]) * s, p[3 * i + 2] + (p[3 * j + 2] - p[3 * i + 2]) * s});
                uvs.add(new double[]{uv[2 * i] + (uv[2 * j] - uv[2 * i]) * s, uv[2 * i + 1] + (uv[2 * j + 1] - uv[2 * i + 1]) * s});
                double nx = nm[3 * i] + (nm[3 * j] - nm[3 * i]) * s, ny = nm[3 * i + 1] + (nm[3 * j + 1] - nm[3 * i + 1]) * s, nz = nm[3 * i + 2] + (nm[3 * j + 2] - nm[3 * i + 2]) * s;
                double l = Math.sqrt(nx * nx + ny * ny + nz * nz);
                normals.add(l > 0 ? new double[]{nx / l, ny / l, nz / l} : new double[]{nm[3 * i], nm[3 * i + 1], nm[3 * i + 2]});
            }
        }
        int m = positions.size();
        if (m < 3) return null;
        double[] ps = new double[3 * m], us = new double[2 * m], ns = new double[3 * m];
        for (int k = 0; k < m; k++) {
            System.arraycopy(positions.get(k), 0, ps, 3 * k, 3);
            System.arraycopy(uvs.get(k), 0, us, 2 * k, 2);
            System.arraycopy(normals.get(k), 0, ns, 3 * k, 3);
        }
        return new Mesh.Polygon(ps, us, ns, polygon.material());
    }

    /** A planar polygon's area, m². */
    public static double area(Mesh.Polygon polygon) {
        var p = polygon.positions();
        int n = polygon.size();
        double x = 0, y = 0, z = 0;
        for (int i = 1; i + 1 < n; i++) {
            double ax = p[3 * i] - p[0], ay = p[3 * i + 1] - p[1], az = p[3 * i + 2] - p[2];
            double bx = p[3 * i + 3] - p[0], by = p[3 * i + 4] - p[1], bz = p[3 * i + 5] - p[2];
            x += ay * bz - az * by;
            y += az * bx - ax * bz;
            z += ax * by - ay * bx;
        }
        return Math.sqrt(x * x + y * y + z * z) / 2;
    }
}
