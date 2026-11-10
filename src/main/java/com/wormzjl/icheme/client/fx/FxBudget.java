package com.wormzjl.icheme.client.fx;

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
        int[] out = new int[n];
        allot(pixels, wanted, n, density, budget, out, new int[n], new int[n]);
        return out;
    }

    /**
     * {@link #allot(double[], int[], double, int)} over the first {@code n} entries, into {@code out}, with {@code order} and
     * {@code scratch} (each at least {@code n} long) as working space: no allocation, for the per-frame draw.
     */
    public static void allot(double[] pixels, int[] wanted, int n, double density, int budget, int[] out, int[] order, int[] scratch) {
        for (int i = 0; i < n; i++) order[i] = i;
        sort(order, n, pixels, scratch);
        double d = Double.isNaN(density) ? 0 : Math.max(0, Math.min(1, density));
        int left = Math.max(0, budget);
        for (int k = 0; k < n; k++) {
            int i = order[k];
            int wish = wanted[i] <= 0 ? 0 : Math.max(1, (int) Math.ceil(wanted[i] * d));
            int given = Math.min(wish, left);
            out[i] = given;
            left -= given;
        }
    }

    /** The rank key of {@code pixels}: larger first, NaN as none ({@code Double.compare} order, as before). */
    private static double key(double pixels) {
        return Double.isNaN(pixels) ? 0 : -pixels;
    }

    /** Whether index {@code i} ranks strictly before {@code j}: by {@link #key}, then by index. */
    private static boolean before(int i, int j, double[] pixels) {
        int c = Double.compare(key(pixels[i]), key(pixels[j]));
        return c < 0 || c == 0 && i < j;
    }

    /** Sorts {@code index[0, n)} by {@link #before}: insertion runs of 16, then merges through {@code scratch}. */
    private static void sort(int[] index, int n, double[] pixels, int[] scratch) {
        final int run = 16;
        for (int from = 0; from < n; from += run) {
            int to = Math.min(n, from + run);
            for (int i = from + 1; i < to; i++) {
                int v = index[i], j = i - 1;
                while (j >= from && before(v, index[j], pixels)) {
                    index[j + 1] = index[j];
                    j--;
                }
                index[j + 1] = v;
            }
        }
        int[] a = index, b = scratch;
        for (int width = run; width < n; width *= 2) {
            for (int lo = 0; lo < n; lo += 2 * width) {
                int mid = Math.min(n, lo + width), hi = Math.min(n, lo + 2 * width), i = lo, j = mid, k = lo;
                while (i < mid && j < hi) b[k++] = before(a[j], a[i], pixels) ? a[j++] : a[i++];
                while (i < mid) b[k++] = a[i++];
                while (j < hi) b[k++] = a[j++];
            }
            int[] t = a;
            a = b;
            b = t;
        }
        if (a != index) System.arraycopy(a, 0, index, 0, n);
    }

    /** An allotment rounded down to {@link #STEPS}ths of the wish (the wish itself when fully allotted). */
    public static int quantize(int allotted, int wanted) {
        if (wanted <= 0 || allotted <= 0) return 0;
        if (allotted >= wanted) return wanted;
        int step = (int) ((long) allotted * STEPS / wanted);
        return (int) ((long) wanted * step / STEPS);
    }
}
