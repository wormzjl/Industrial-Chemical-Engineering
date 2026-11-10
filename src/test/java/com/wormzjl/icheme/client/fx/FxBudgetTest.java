package com.wormzjl.icheme.client.fx;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CUSTOM_SHADER_SYSTEM_PLAN.md section 4.6: at most 2,000 quads a frame, the largest on screen first. */
class FxBudgetTest {
    @Test
    void everyoneFitsUnderTheBudget() {
        assertArrayEquals(new int[]{48, 32, 16}, FxBudget.allot(new double[]{10, 300, 50}, new int[]{48, 32, 16}, 1, FxBudget.FRAME_QUADS));
    }

    @Test
    void theLargestOnScreenAreServedFirst() {
        double[] pixels = new double[60];
        int[] wanted = new int[60];
        for (int i = 0; i < 60; i++) {
            pixels[i] = i;
            wanted[i] = 48;
        }
        int[] out = FxBudget.allot(pixels, wanted, 1, FxBudget.FRAME_QUADS);
        assertEquals(FxBudget.FRAME_QUADS, Arrays.stream(out).sum());
        // 41 full flares (1,968 quads), then the 32 quads left, then nothing.
        for (int i = 59; i >= 19; i--) assertEquals(48, out[i]);
        assertEquals(32, out[18]);
        for (int i = 17; i >= 0; i--) assertEquals(0, out[i]);
    }

    @Test
    void halfDensityUnderAPack() {
        assertArrayEquals(new int[]{24, 16, 1}, FxBudget.allot(new double[]{3, 2, 1}, new int[]{48, 31, 1}, FxBudget.PACK_DENSITY, 2000));
    }

    @Test
    void tiesKeepTheirOrderAndNothingGoesNegative() {
        assertArrayEquals(new int[]{5, 3, 0}, FxBudget.allot(new double[]{1, 1, 1}, new int[]{5, 5, 5}, 1, 8));
        assertArrayEquals(new int[]{0, 0}, FxBudget.allot(new double[]{1, Double.NaN}, new int[]{-3, 0}, 1, 8));
        assertArrayEquals(new int[]{0}, FxBudget.allot(new double[]{1}, new int[]{4}, 1, -5));
    }

    @Test
    void quantizedSharesMoveInEighths() {
        assertEquals(48, FxBudget.quantize(48, 48));
        assertEquals(48, FxBudget.quantize(60, 48));
        assertEquals(42, FxBudget.quantize(47, 48));
        assertEquals(24, FxBudget.quantize(25, 48));
        assertEquals(0, FxBudget.quantize(5, 48));
        assertEquals(0, FxBudget.quantize(0, 48));
        for (int a = 0; a <= 48; a++) assertTrue(FxBudget.quantize(a, 48) <= a);
    }

    /** Bench 2026-11-10: the per-frame form, over a prefix with reused arrays, allots as the allocating one (NaN and ties too). */
    @Test
    void thePerFrameFormAllotsTheSame() {
        var random = new java.util.Random(3);
        int[] out = new int[512], order = new int[512], scratch = new int[512];
        for (int n : new int[]{0, 1, 7, 16, 33, 200}) {
            double[] pixels = new double[n + 5];
            int[] wanted = new int[n + 5];
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = random.nextInt(9) == 0 ? Double.NaN : random.nextInt(12);
                wanted[i] = random.nextInt(60) - 5;
            }
            var expected = reference(Arrays.copyOf(pixels, n), Arrays.copyOf(wanted, n), .5, 900);
            FxBudget.allot(pixels, wanted, n, .5, 900, out, order, scratch);
            assertArrayEquals(expected, Arrays.copyOf(out, n), "n=" + n);
            assertArrayEquals(expected, FxBudget.allot(Arrays.copyOf(pixels, n), Arrays.copyOf(wanted, n), .5, 900), "n=" + n);
        }
    }

    /** The allotment as it was written before the bench (boxed indices, a comparator), the reference of the per-frame form. */
    private static int[] reference(double[] pixels, int[] wanted, double density, int budget) {
        int n = wanted.length;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        Arrays.sort(order, java.util.Comparator.<Integer>comparingDouble(i -> Double.isNaN(pixels[i]) ? 0 : -pixels[i]).thenComparingInt(i -> i));
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
}
