package com.wormzjl.icheme.client.fx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;

/**
 * The COMPATIBLE path's writer: eases each quad on the CPU this frame, faces it to the camera and writes vanilla
 * {@code NEW_ENTITY} vertices with real colours and texture coordinates, for {@code entityTranslucentEmissive} and
 * {@code entityTranslucentCull} (CUSTOM_SHADER_SYSTEM_PLAN.md section 4.6). Positions are camera-relative, as the other
 * level-stage renderers write them. Texture layouts ({@link FxRenderTypes}): the flame flipbook stacks
 * {@link FxRenderTypes#FLAME_FRAMES} frames from top to bottom, tip at each frame's top; smoke and plume textures hold four
 * puff variants in a 2 × 2 grid, chosen by the seed.
 */
final class FxImmediateSink implements FxSink {
    /** Flipbook frames per second. */
    private static final double FRAME_RATE = 12;

    // Set again for each emitter ({@link #reset}): the batch keeps one sink, so the per-frame draw allocates none.
    private VertexConsumer out;
    private PoseStack.Pose pose;
    private double ox, oy, oz;
    private float leftX, leftY, leftZ, upX, upY, upZ;
    private double ease, seconds, daylight, driftPeriod, growth;
    private FxKind kind;
    private int light;
    private int left;

    /**
     * @param origin   the emitter origin minus the camera position, m
     * @param leftAxis the camera's left vector and {@code upAxis} its up vector, world axes
     * @param ease     the glide's ease this frame, 0 old to 1 new
     * @param seconds  game time, s, for the flipbook
     * @param daylight the sky brightness, 0.2 to 1
     * @param driftPeriod the emitter's travelling-puff period, s ({@link FxEmitter#driftPeriod()})
     * @param growth   what a period adds to a travelling puff's half-size, m ({@link FxEmitter#puffGrowth()})
     */
    FxImmediateSink(VertexConsumer out, PoseStack.Pose pose, double[] origin, org.joml.Vector3f leftAxis, org.joml.Vector3f upAxis,
                    double ease, double seconds, double daylight, double driftPeriod, double growth, FxKind kind, int light, int quads) {
        reset(out, pose, origin[0], origin[1], origin[2], leftAxis, upAxis, ease, seconds, daylight, driftPeriod, growth, kind, light, quads);
    }

    /** As the constructor, for the next emitter, the origin as {@code ox, oy, oz}; returns this sink. */
    FxImmediateSink reset(VertexConsumer out, PoseStack.Pose pose, double ox, double oy, double oz, org.joml.Vector3f leftAxis, org.joml.Vector3f upAxis,
                          double ease, double seconds, double daylight, double driftPeriod, double growth, FxKind kind, int light, int quads) {
        this.driftPeriod = driftPeriod;
        this.growth = growth;
        this.out = out;
        this.pose = pose;
        this.ox = ox;
        this.oy = oy;
        this.oz = oz;
        this.leftX = leftAxis.x();
        this.leftY = leftAxis.y();
        this.leftZ = leftAxis.z();
        this.upX = upAxis.x();
        this.upY = upAxis.y();
        this.upZ = upAxis.z();
        this.ease = ease;
        this.seconds = seconds;
        this.daylight = daylight;
        this.kind = kind;
        this.light = light;
        this.left = Math.max(0, quads);
        return this;
    }

    @Override
    public int remaining() {
        return left;
    }

    @Override
    public void sprite(Point c, int params, int seed) {
        if (left <= 0) return;
        square(ox + c.x(ease), oy + c.y(ease), oz + c.z(ease), c.half(ease), params, seed);
    }

    @Override
    public void puff(Point start, double dx, double dy, double dz, int params, int seed) {
        if (left <= 0) return;
        // As fx_plume.vsh: the drift as the packed normal would carry it, the phase, the growth and the fade.
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz), cut = length > DRIFT_SCALE ? DRIFT_SCALE / length : 1;
        double phase = FxSink.phase(seconds, driftPeriod, seed), along = phase * cut;
        int alpha = (int) Math.round((params >>> 24) * FxSink.envelope(phase));
        square(ox + start.x(ease) + dx * along, oy + start.y(ease) + dy * along, oz + start.z(ease) + dz * along,
                start.half(ease) + growth * phase, params & 0xFFFFFF | alpha << 24, seed);
    }

    private void square(double x, double y, double z, double h, int params, int seed) {
        if (!Double.isFinite(x + y + z + h) || h <= 0 || (params >>> 24) == 0) return;
        left--;
        int colour = kind == FxKind.FLAME ? FxColour.flame(.5, (params >> 8 & 0xFF) / 255.0, (params & 0xFF) / 255.0, (params >>> 24) / 255.0)
                : FxColour.puff(params, daylight);
        int variant = seed & 3;
        float u0 = (variant & 1) * .5F, v0 = (variant >> 1) * .5F;
        float nx = (float) -x, ny = (float) -y, nz = (float) -z;
        float n = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (n > 1e-6F) {
            nx /= n;
            ny /= n;
            nz /= n;
        } else {
            ny = 1;
        }
        // Right is minus left; corners (0,0) (1,0) (1,1) (0,1) as in FxPackedSink.
        for (int k = 0; k < 4; k++) {
            int cu = k == 1 || k == 2 ? 1 : 0, cv = k >= 2 ? 1 : 0;
            double sx = (2 * cu - 1) * h, sy = (2 * cv - 1) * h;
            vertex(x - leftX * sx + upX * sy, y - leftY * sx + upY * sy, z - leftZ * sx + upZ * sy, colour,
                    u0 + cu * .5F, v0 + (1 - cv) * .5F, nx, ny, nz);
        }
    }

    @Override
    public void ribbon(Point a, double va, Point b, double vb, int params, int seed) {
        double[] axis = {b.x(ease) - a.x(ease), b.y(ease) - a.y(ease), b.z(ease) - a.z(ease)};
        ribbon(a, va, axis, b, vb, axis, params, seed);
    }

    @Override
    public void ribbon(Point a, double va, double[] ta, Point b, double vb, double[] tb, int params, int seed) {
        if (left <= 0) return;
        double ax = ox + a.x(ease), ay = oy + a.y(ease), az = oz + a.z(ease), ha = a.half(ease);
        double bx = ox + b.x(ease), by = oy + b.y(ease), bz = oz + b.z(ease), hb = b.half(ease);
        if (!Double.isFinite(ax + ay + az + ha + bx + by + bz + hb)) return;
        left--;
        double heat = (params >> 8 & 0xFF) / 255.0, soot = (params & 0xFF) / 255.0, opacity = (params >>> 24) / 255.0;
        int frame = (int) Math.floorMod((long) Math.floor(seconds * FRAME_RATE) + seed, (long) FxRenderTypes.FLAME_FRAMES);
        float frameTop = (float) frame / FxRenderTypes.FLAME_FRAMES, frameHeight = 1F / FxRenderTypes.FLAME_FRAMES;
        double[][] ends = {{ax, ay, az, ha, va}, {bx, by, bz, hb, vb}};
        double[][] tangents = {ta, tb};
        for (int k = 0; k < 4; k++) {
            int cu = k == 1 || k == 2 ? 1 : 0, cv = k >= 2 ? 1 : 0;
            double[] e = ends[cv];
            double tx = tangents[cv][0], ty = tangents[cv][1], tz = tangents[cv][2];
            // The side is across this end's tangent and the line of sight, so the ribbon turns about its centre line.
            double sx = ty * -e[2] - tz * -e[1], sy = tz * -e[0] - tx * -e[2], sz = tx * -e[1] - ty * -e[0];
            double s = Math.sqrt(sx * sx + sy * sy + sz * sz);
            if (s < 1e-9) {
                sx = -leftX;
                sy = -leftY;
                sz = -leftZ;
                s = 1;
            }
            double w = (2 * cu - 1) * e[3] / s;
            int colour = kind == FxKind.FLAME ? FxColour.flame(e[4], heat, soot, opacity) : FxColour.puff(params, daylight);
            float v = frameTop + (float) (1 - Math.max(0, Math.min(1, e[4]))) * frameHeight;
            vertex(e[0] + sx * w, e[1] + sy * w, e[2] + sz * w, colour, cu, v, 0, 1, 0);
        }
    }

    private void vertex(double x, double y, double z, int argb, float u, float v, float nx, float ny, float nz) {
        out.addVertex(pose, (float) x, (float) y, (float) z)
                .setColor(argb >> 16 & 0xFF, argb >> 8 & 0xFF, argb & 0xFF, argb >>> 24)
                .setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, nx, ny, nz);
    }
}
