package com.wormzjl.icheme.client;

import java.util.HashMap;
import java.util.Map;

/**
 * Drawing only: how many pixels an object covers on the screen, and the detail tier that follows (batch
 * 2026-11-09-lod-rendering, {@code LOD_RENDERING_PLAN.md}). An object of size s at distance d covers k·s/d pixels, with
 * k = (framebuffer height / 2) · m11 of the projection, which is 1 / tan(FOV / 2): 771 at 1080 lines and a 70° field,
 * and it follows zoom and dynamic FOV. A detail under a pixel buys nothing. Each per-frame renderer keeps its own range
 * as the upper bound and asks this for the tier inside it, so a tier never draws more than the renderer drew before.
 * Names no Minecraft class.
 */
public final class RenderLod {
    /** A tier gains detail only once its threshold is passed by this factor; it loses detail at the threshold itself. */
    public static final double HYSTERESIS = 1.15;
    /** k at 1080 lines and a 70° vertical field: the reference of the plan's distances. */
    public static final double REFERENCE = 771;
    /** The client setting's bounds: above 1 detail drops nearer, below 1 farther. */
    public static final double LEAST_SCALE = .25, MOST_SCALE = 4;

    private RenderLod() {
    }

    /** One frame's view: k divided by the client's {@code lod_scale}. */
    public record View(double pixelsPerMetre) {
        public View {
            if (!(pixelsPerMetre > 0) || !Double.isFinite(pixelsPerMetre)) pixelsPerMetre = REFERENCE;
        }

        /** The view of a projection whose m11 is {@code m11}, on a framebuffer {@code height} pixels high. */
        public static View of(double m11, int height, double scale) {
            double s = Double.isFinite(scale) ? Math.clamp(scale, LEAST_SCALE, MOST_SCALE) : 1;
            return new View(height / 2.0 * Math.abs(m11) / s);
        }

        /** Pixels covered by a size of {@code size} metres at the squared distance {@code distanceSqr}. */
        public double pixels(double size, double distanceSqr) {
            return size * pixelsPerMetre / Math.sqrt(Math.max(distanceSqr, 1e-6));
        }

        /** Pixels covered at the nearest point of a box (an eye inside it counts as one centimetre away). */
        public double pixels(double size, double eyeX, double eyeY, double eyeZ, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            double dx = Math.max(Math.max(minX - eyeX, 0), eyeX - maxX);
            double dy = Math.max(Math.max(minY - eyeY, 0), eyeY - maxY);
            double dz = Math.max(Math.max(minZ - eyeZ, 0), eyeZ - maxZ);
            return pixels(size, Math.max(dx * dx + dy * dy + dz * dz, 1e-4));
        }
    }

    /**
     * The tier of {@code pixels} over descending {@code thresholds}: 0 at or above the first, i at or above the i-th,
     * {@code thresholds.length} (not drawn) under the last. From {@code previous} (negative: none) the tier loses detail
     * at a threshold and gains it only past threshold × {@link #HYSTERESIS}, so a viewer standing at a threshold sees no
     * flicker.
     */
    public static int tier(double pixels, int previous, double... thresholds) {
        int coarse = raw(pixels, thresholds);
        if (previous < 0 || previous > thresholds.length || coarse > previous) return coarse;
        int fine = raw(pixels / HYSTERESIS, thresholds);
        return Math.min(previous, fine);
    }

    private static int raw(double pixels, double[] thresholds) {
        for (int i = 0; i < thresholds.length; i++) if (pixels >= thresholds[i]) return i;
        return thresholds.length;
    }

    /** Each object's last tier, for the hysteresis. Render thread only; forgets everything past its bound. */
    public static final class Tiers<K> {
        private static final int BOUND = 8192;
        private final Map<K, Integer> last = new HashMap<>();
        private final double[] thresholds;

        public Tiers(double... thresholds) {
            for (int i = 1; i < thresholds.length; i++)
                if (!(thresholds[i] < thresholds[i - 1])) throw new IllegalArgumentException("Thresholds descend");
            this.thresholds = thresholds.clone();
        }

        /** The tier of {@code key} at {@code pixels}, remembered for the next frame. */
        public int of(K key, double pixels) {
            if (last.size() >= BOUND) last.clear();
            int previous = last.getOrDefault(key, -1);
            int next = tier(pixels, previous, thresholds);
            if (next != previous) last.put(key, next);
            return next;
        }

        /** The tier past the last threshold: not drawn. */
        public int hidden() {
            return thresholds.length;
        }

        public void clear() {
            last.clear();
        }
    }
}
