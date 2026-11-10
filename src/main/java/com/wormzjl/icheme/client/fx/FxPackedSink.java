package com.wormzjl.icheme.client.fx;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;

/**
 * The SHADER path's writer: packs each quad, with its old and new values, into vanilla's {@code NEW_ENTITY} vertex
 * format, whose fields carry packed parameters here (no custom vertex element is registered). The vertex shaders
 * ({@code fx_flame.vsh}, {@code fx_plume.vsh}, {@code fx_haze.vsh}) unpack it; change both together.
 * <pre>
 * attribute  type        holds
 * Position   3 float     new centre, emitter-local m
 * Color      4 ubyte     the quad's parameter bytes (FxSink): R G B A
 * UV0        2 float     old − new centre, x and z, m
 * UV1        2 short     x: old − new centre y, mm (±32.767 m); y: flags = cornerU | cornerV &lt;&lt; 1 | ribbon &lt;&lt; 2 | seed &lt;&lt; 3
 * UV2        2 short     old half-size, new half-size, mm (0 to 32.767 m)
 * Normal     3 byte      a ribbon's axis (unit, new positions); a travelling puff's drift over one period / DRIFT_SCALE;
 *                        zero for a still sprite
 * </pre>
 * The glide's start is not in the vertex: an emitter's vertices share it, so it is the per-draw uniform
 * {@code GlideStart}, with the emitter's origin as {@code Origin}. A point that moved more than 32 m between snapshots
 * snaps to its new place. Corner order per quad: (0,0), (1,0), (1,1), (0,1).
 */
final class FxPackedSink implements FxSink {
    static final com.mojang.blaze3d.vertex.VertexFormat FORMAT = DefaultVertexFormat.NEW_ENTITY;
    private static final double MAX_DELTA = 32.767;
    private static final int[][] CORNERS = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};

    private final VertexConsumer out;
    private int left;

    FxPackedSink(VertexConsumer out, int quads) {
        this.out = out;
        this.left = Math.max(0, quads);
    }

    @Override
    public int remaining() {
        return left;
    }

    @Override
    public void sprite(Point c, int params, int seed) {
        if (left <= 0 || !finite(c)) return;
        left--;
        for (int[] k : CORNERS) vertex(c, params, flags(k[0], k[1], false, seed), 0, 0, 0);
    }

    @Override
    public void puff(Point start, double dx, double dy, double dz, int params, int seed) {
        if (left <= 0 || !finite(start) || !Double.isFinite(dx + dy + dz)) return;
        left--;
        float[] n = FxSink.drift(dx, dy, dz);
        for (int[] k : CORNERS) vertex(start, params, flags(k[0], k[1], false, seed), n[0], n[1], n[2]);
    }

    @Override
    public void ribbon(Point a, double va, Point b, double vb, int params, int seed) {
        double[] axis = unit(b.newX() - a.newX(), b.newY() - a.newY(), b.newZ() - a.newZ());
        ribbon(a, va, axis, b, vb, axis, params, seed);
    }

    @Override
    public void ribbon(Point a, double va, double[] ta, Point b, double vb, double[] tb, int params, int seed) {
        if (left <= 0 || !finite(a) || !finite(b)) return;
        left--;
        // Each end's normal is its own tangent: fx_flame.vsh turns that end about it, so joints of a curve meet flush.
        double[] na = unit(ta[0], ta[1], ta[2]), nb = unit(tb[0], tb[1], tb[2]);
        int pa = withAlong(params, va), pb = withAlong(params, vb);
        for (int[] k : CORNERS) {
            boolean first = k[1] == 0;
            double[] n = first ? na : nb;
            vertex(first ? a : b, first ? pa : pb, flags(k[0], k[1], true, seed), (float) n[0], (float) n[1], (float) n[2]);
        }
    }

    /** The unit vector along (x, y, z); straight up for a zero or broken one. */
    private static double[] unit(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        return length < 1e-6 || !Double.isFinite(length) ? new double[]{0, 1, 0} : new double[]{x / length, y / length, z / length};
    }

    private void vertex(Point p, int params, int flags, float nx, float ny, float nz) {
        double dx = p.oldX() - p.newX(), dy = p.oldY() - p.newY(), dz = p.oldZ() - p.newZ();
        double oldHalf = p.oldHalf();
        if (Math.abs(dx) > MAX_DELTA || Math.abs(dy) > MAX_DELTA || Math.abs(dz) > MAX_DELTA) {
            dx = dy = dz = 0;
            oldHalf = p.newHalf();
        }
        out.addVertex((float) p.newX(), (float) p.newY(), (float) p.newZ())
                .setColor(params >> 16 & 0xFF, params >> 8 & 0xFF, params & 0xFF, params >>> 24)
                .setUv((float) dx, (float) dz)
                .setUv1((int) Math.round(dy * 1000), flags)
                .setUv2(millimetres(oldHalf), millimetres(p.newHalf()))
                .setNormal(nx, ny, nz);
    }

    /** The flags short: corner u and v, ribbon or sprite, and the 12-bit seed; at most 32,767. */
    static int flags(int u, int v, boolean ribbon, int seed) {
        return (u & 1) | (v & 1) << 1 | (ribbon ? 1 : 0) << 2 | (seed & SEED_MASK) << 3;
    }

    private static int millimetres(double metres) {
        return (int) Math.round(Math.max(0, Math.min(MAX_DELTA, metres)) * 1000);
    }

    private static int withAlong(int params, double v) {
        int along = (int) Math.round(Math.max(0, Math.min(1, v)) * 255);
        return params & 0xFF00FFFF | along << 16;
    }

    private static boolean finite(Point p) {
        return Double.isFinite(p.oldX() + p.oldY() + p.oldZ() + p.newX() + p.newY() + p.newZ() + p.oldHalf() + p.newHalf());
    }
}
