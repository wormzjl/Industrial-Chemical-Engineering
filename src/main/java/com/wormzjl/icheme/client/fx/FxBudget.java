package com.wormzjl.icheme.client.fx;

import java.util.Arrays;
import java.util.Comparator;

/**
 * The per-frame quad budget (CUSTOM_SHADER_SYSTEM_PLAN.md section 4.6): at most {@link #FRAME_QUADS} effect quads in
 * a frame, given to the emitters that cover the most pixels first; half the density under a shader pack. Pure; names no
 * Minecraft class.
 */
public final class FxBudget {
    /** Effect quads per frame, all emitters together. */
    public static final int FRAME_QUADS = 2000;
    /** Density under a shader pack: its frame is already heavy. */
    public static final double PACK_DENSITY = .5;
    /** The SHADER path's allotment steps: a cached buffer is rebuilt only when the share moves by one step. */
    public static final int STEPS = 8;

    private FxBudget() {
    }

    /**
     * Quads for each emitter: ranked by {@code pixels} (largest first, ties by index), each gets its wish × {@code density}
     * (rounded up, at least 1 for a positive wish) while the budget lasts; the one that crosses the budget gets what is left,
     * the rest none.
     */
    public static int[] allot(double[] pixels, int[] wanted, double density, int budget) {
        if (pixels.length != wanted.length) throw new IllegalArgumentException("pixels and wanted differ in length");
        int n = wanted.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, Comparator.<Integer>comparingDouble(i -> Double.isNaN(pixels[i]) ? 0 : -pixels[i]).thenComparingInt(i -> i));
        double d = Double.isNaN(density) ? 0 : Math.max(0, Math.min(1, density));
        int left = Math.max(0, budget);
        int[] out = new int[n];
        for (int i : order) {
            int wish = wanted[i] <= 0 ? 0 : Math.max(1, (int) Math.ceil(wanted[i] * d));
            int given = Math.min(wish, left);
            out[i] = given;
            left -= given;
        }
        return out;
    }

    /** An allotment rounded down to {@link #STEPS}ths of the wish (the wish itself when fully allotted). */
    public static int quantize(int allotted, int wanted) {
        if (wanted <= 0 || allotted <= 0) return 0;
        if (allotted >= wanted) return wanted;
        int step = (int) ((long) allotted * STEPS / wanted);
        return (int) ((long) wanted * step / STEPS);
    }
}
