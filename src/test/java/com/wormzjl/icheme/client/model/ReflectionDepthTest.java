package com.wormzjl.icheme.client.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The reflection pass's depth pull ({@link ReflectionDepth}) measured on a test scene: one plane drawn by the chunk mesh's
 * route (cut at every block, float positions from the section origin plus a float chunk offset; or Sodium 0.6's 20-bit
 * positions plus a float region offset) and by the pass's route (the whole facet, float positions from a 32-block region
 * corner plus a float offset, pulled along the view ray), projected in float as the shaders do, each vertex snapped to
 * 1/256 px, depth interpolated per pixel and rounded to 24 bits. The pass must pass LEQUAL on every pixel. Prints the
 * raw deficit and the pull per case (REFLECTION_PASS_REVIEW.md section 4).
 */
class ReflectionDepthTest {
    private static final int W = 1920, H = 1080;
    private static final double FOV = Math.toRadians(70), FAR = 512;
    /** A camera far from the origin, so the offsets' float rounding counts. */
    private static final double[] CAMERA = {12345.678, 71.234, -9876.543};

    private record Vertex(double x, double y, double depth) {}

    private record Triangle(Vertex a, Vertex b, Vertex c) {
        /** The interpolated depth at pixel centre (px, py), or NaN outside. */
        double depth(double px, double py) {
            double d = (b.x - a.x) * (c.y - a.y) - (c.x - a.x) * (b.y - a.y);
            if (Math.abs(d) < 1e-12) return Double.NaN;
            double u = ((b.x - px) * (c.y - py) - (c.x - px) * (b.y - py)) / d;
            double v = ((c.x - px) * (a.y - py) - (a.x - px) * (c.y - py)) / d;
            double w = 1 - u - v;
            if (u < 0 || v < 0 || w < 0) return Double.NaN;
            return u * a.depth + v * b.depth + w * c.depth;
        }
    }

    private static final class View {
        final float[] mv = new float[9];
        final float f = (float) (1 / Math.tan(FOV / 2)), aspect = (float) W / H;
        final double[] forward;

        View(double yaw, double pitch) {
            forward = new double[]{Math.cos(pitch) * Math.sin(yaw), Math.sin(pitch), -Math.cos(pitch) * Math.cos(yaw)};
            double[] right = unit(cross(forward, new double[]{0, 1, 0})), up = cross(right, forward);
            for (int i = 0; i < 3; i++) {
                mv[i] = (float) right[i];
                mv[3 + i] = (float) up[i];
                mv[6 + i] = (float) -forward[i];
            }
        }

        /** A camera-relative float position through the model-view and projection in float, pulled by {@code pull} m; snapped window x, y and depth. */
        Vertex project(float qx, float qy, float qz, double pull) {
            float ex = mv[0] * qx + mv[1] * qy + mv[2] * qz, ey = mv[3] * qx + mv[4] * qy + mv[5] * qz, ez = mv[6] * qx + mv[7] * qy + mv[8] * qz;
            if (pull > 0) {
                float s = (float) (1 - pull / -ez);
                ex *= s;
                ey *= s;
                ez *= s;
            }
            float n = (float) ReflectionDepth.NEAR, far = (float) FAR;
            float cx = f / aspect * ex, cy = f * ey, cz = (far + n) / (n - far) * ez + 2 * far * n / (n - far), cw = -ez;
            double x = Math.round(((cx / cw) * .5 + .5) * W * 256) / 256.0, y = Math.round(((cy / cw) * .5 + .5) * H * 256) / 256.0;
            return new Vertex(x, y, (cz / cw) * .5 + .5);
        }
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double[] unit(double[] a) {
        double l = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return new double[]{a[0] / l, a[1] / l, a[2] / l};
    }

    /** {@code polygons} each split by the world plane {@code axis} = k for every integer k they cross. */
    private static List<List<double[]>> cut(List<List<double[]>> polygons, int axis) {
        var out = new ArrayList<List<double[]>>();
        for (var polygon : polygons) {
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (var p : polygon) {
                lo = Math.min(lo, p[axis]);
                hi = Math.max(hi, p[axis]);
            }
            var rest = polygon;
            for (double k = Math.floor(lo) + 1; k < hi; k++) {
                var below = new ArrayList<double[]>();
                var above = new ArrayList<double[]>();
                for (int i = 0; i < rest.size(); i++) {
                    double[] a = rest.get(i), b = rest.get((i + 1) % rest.size());
                    if (a[axis] <= k) below.add(a);
                    if (a[axis] >= k) above.add(a);
                    if ((a[axis] - k) * (b[axis] - k) < 0) {
                        double t = (k - a[axis]) / (b[axis] - a[axis]);
                        double[] m = {a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1]), a[2] + t * (b[2] - a[2])};
                        below.add(m);
                        above.add(m);
                    }
                }
                if (below.size() >= 3) out.add(below);
                rest = above;
            }
            if (rest.size() >= 3) out.add(rest);
        }
        return out;
    }

    private static List<Triangle> fan(List<Vertex> v, List<Triangle> out) {
        for (int i = 1; i + 1 < v.size(); i++) out.add(new Triangle(v.get(0), v.get(i), v.get(i + 1)));
        return out;
    }

    /** The chunk route's triangles: cut at every block, positions from the section origin (vanilla float, or Sodium's 20 bits). */
    private static List<Triangle> chunk(List<double[]> facet, View view, boolean sodium) {
        var pieces = List.of(facet);
        for (int axis = 0; axis < 3; axis++) pieces = cut(pieces, axis);
        var out = new ArrayList<Triangle>();
        for (var piece : pieces) {
            double[] c = new double[3];
            for (var p : piece) for (int i = 0; i < 3; i++) c[i] += p[i] / piece.size();
            double[] section = new double[3], region = new double[3];
            for (int i = 0; i < 3; i++) {
                section[i] = Math.floor(Math.floor(c[i]) / 16) * 16;
                region[i] = Math.floor(section[i] / (i == 1 ? 64 : 128)) * (i == 1 ? 64 : 128);
            }
            var v = new ArrayList<Vertex>();
            for (var p : piece) {
                float[] q = new float[3];
                for (int i = 0; i < 3; i++) {
                    float local = (float) (p[i] - section[i]);
                    if (sodium) {
                        int u = (int) ((local + 8f) / 32f * 1048576f) & 0xfffff;
                        float back = u * (32f / 1048576f) - 8f;
                        float translation = (float) (region[i] - CAMERA[i]) + (float) (section[i] - region[i]);
                        q[i] = back + translation;
                    } else q[i] = local + (float) (section[i] - CAMERA[i]);
                }
                v.add(view.project(q[0], q[1], q[2], 0));
            }
            fan(v, out);
        }
        return out;
    }

    /** The pass's triangles: the whole facet from its region corner, pulled by {@link ReflectionDepth#pull} (or not, {@code pulled} false). */
    private static List<Triangle> pass(List<double[]> facet, double[] normal, View view, boolean pulled) {
        double[] corner = new double[3];
        for (int i = 0; i < 3; i++) corner[i] = i == 1 ? 0 : Math.floor(facet.get(0)[i] / 32) * 32;
        var v = new ArrayList<Vertex>();
        for (var p : facet) {
            float[] q = new float[3];
            for (int i = 0; i < 3; i++) q[i] = (float) (p[i] - corner[i]) + (float) (corner[i] - CAMERA[i]);
            double z = 0, d = 0;
            for (int i = 0; i < 3; i++) {
                z += q[i] * view.forward[i];
                d += q[i] * q[i];
            }
            double cos = 0;
            for (int i = 0; i < 3; i++) cos -= normal[i] * q[i] / Math.sqrt(d);
            cos = Math.max(1e-6, Math.min(1, cos));
            double tan = Math.sqrt(1 - cos * cos) / cos;
            v.add(view.project(q[0], q[1], q[2], pulled ? ReflectionDepth.pull(z, tan) : 0));
        }
        return fan(v, new ArrayList<>());
    }

    private static double depthAt(List<Triangle> triangles, double px, double py) {
        for (var t : triangles) {
            double d = t.depth(px, py);
            if (!Double.isNaN(d)) return d;
        }
        return Double.NaN;
    }

    /** {raw worst deficit in depth steps, pixels failing with the pull, pixels compared}. */
    private static long[] measure(double distance, double incidence, boolean sodium, View view) {
        double[] f = view.forward;
        double[] right = unit(cross(f, new double[]{0, 1, 0})), up = cross(right, f);
        double[] centre = new double[3], normal = new double[3];
        for (int i = 0; i < 3; i++) {
            centre[i] = CAMERA[i] + f[i] * distance;
            normal[i] = -f[i] * Math.cos(incidence) + up[i] * Math.sin(incidence);
        }
        double[] t1 = right, t2 = unit(cross(normal, t1));
        double pixel = distance / (H / 2.0 * view.f);
        double h1 = 30 * pixel, h2 = 30 * pixel / Math.max(Math.cos(incidence), .1);
        var facet = new ArrayList<double[]>();
        for (int[] s : new int[][]{{-1, -1}, {1, -1}, {1, 1}, {-1, 1}}) {
            double[] p = new double[3];
            for (int i = 0; i < 3; i++) p[i] = centre[i] + s[0] * h1 * t1[i] + s[1] * h2 * t2[i];
            facet.add(p);
        }
        var chunk = chunk(facet, view, sodium);
        var raw = pass(facet, normal, view, false);
        var pulled = pass(facet, normal, view, true);
        long worst = Long.MIN_VALUE, failed = 0, compared = 0;
        for (int py = H / 2 - 24; py < H / 2 + 24; py++)
            for (int px = W / 2 - 24; px < W / 2 + 24; px++) {
                double x = px + .5, y = py + .5;
                double c = depthAt(chunk, x, y), r = depthAt(raw, x, y), p = depthAt(pulled, x, y);
                if (Double.isNaN(c) || Double.isNaN(r) || Double.isNaN(p)) continue;
                long qc = Math.round(c * (1 << 24)), qr = Math.round(r * (1 << 24)), qp = Math.round(p * (1 << 24));
                worst = Math.max(worst, qr - qc);
                if (qp > qc) failed++;
                compared++;
            }
        return new long[]{worst, failed, compared};
    }

    @Test
    void thePullPassesTheChunkMeshOnEveryPixel() {
        var report = new StringBuilder("view, distance m, incidence deg, route: raw worst deficit (depth steps), pull mm, failing pixels / compared\n");
        long failing = 0;
        for (double[] look : new double[][]{{37, -20}, {128, 35}})
        for (boolean sodium : new boolean[]{false, true})
            for (double distance : new double[]{1, 2, 5, 10, 20, 40, 64, 96, 128})
                for (double degrees : new double[]{0, 30, 60, 75, 85}) {
                    double incidence = Math.toRadians(degrees);
                    var view = new View(Math.toRadians(look[0]), Math.toRadians(look[1]));
                    long[] m = measure(distance, incidence, sodium, view);
                    assertTrue(m[2] > 100, "the patch covers the sampled pixels");
                    failing += m[1];
                    report.append(String.format(Locale.ROOT, "%4.0f/%3.0f %5.0f %3.0f %-7s raw %+6d  pull %8.3f  fail %d/%d%n", look[0], look[1], distance, degrees, sodium ? "sodium" : "vanilla",
                            m[0], 1000 * ReflectionDepth.pull(distance, Math.tan(incidence)), m[1], m[2]));
                }
        System.out.print(report);
        assertEquals(0, failing, "pixels where the pass would fail its depth test on its own surface\n" + report);
    }

    @Test
    void thePullStaysSmallAgainstWhatLiesBehind() {
        assertEquals(2.5e-4, ReflectionDepth.pull(5, 0), 1e-4, "about a quarter of a millimetre at 5 m head-on");
        assertTrue(ReflectionDepth.pull(96, 0) < .03, "under 3 cm at the default range");
        assertEquals(ReflectionDepth.pull(10, ReflectionDepth.TAN_CAP), ReflectionDepth.pull(10, 1e6), 0, "the slope is capped at 85 degrees");
    }
}
