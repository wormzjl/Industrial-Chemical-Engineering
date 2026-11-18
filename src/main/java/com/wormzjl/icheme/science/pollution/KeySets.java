package com.wormzjl.icheme.science.pollution;

import java.util.Arrays;

/**
 * Sorted sets of cube and column keys as primitive arrays, ascending in {@code long} order with no repeats: the field's
 * own order. The face neighbours of a set are found without boxing and without a general sort: a key is mapped to an
 * order in which a coordinate step is a constant add ({@link CubeKey}'s y and z, and {@link MapColumns}'s z, are stored
 * in two's complement, so their negative values sort after the positive ones in key order), the shifted copies are
 * merged, and the result is mapped back by moving the negative blocks behind the positive ones.
 */
public final class KeySets {
    public static final long[] EMPTY = new long[0];

    // CubeKey: x at bit 42 (22 bits), z at bit 20 (22 bits), y at bit 0 (20 bits).
    private static final long CUBE_FLIP = 1L << 41 | 1L << 19;
    private static final long[] CUBE_STEPS = {0, -(1L << 42), 1L << 42, -1, 1, -(1L << 20), 1L << 20};
    // MapColumns: x in the high 32 bits, z in the low 32 bits.
    private static final long COLUMN_FLIP = 1L << 31;
    private static final long[] COLUMN_STEPS = {0, -(1L << 32), 1L << 32, -1, 1};

    private KeySets() {
    }

    /** The union of two sorted sets. */
    public static long[] union(long[] a, long[] b) {
        long[] out = new long[a.length + b.length];
        int i = 0, j = 0, n = 0;
        while (i < a.length || j < b.length) {
            long v;
            if (j >= b.length || i < a.length && a[i] < b[j]) {
                v = a[i++];
            } else if (i >= a.length || b[j] < a[i]) {
                v = b[j++];
            } else {
                v = a[i++];
                j++;
            }
            out[n++] = v;
        }
        return n == out.length ? out : Arrays.copyOf(out, n);
    }

    /** Sorts {@code keys} in place and drops repeats; returns the set (a prefix copy when repeats were dropped). */
    public static long[] of(long[] keys) {
        Arrays.sort(keys);
        int n = 0;
        for (int i = 0; i < keys.length; i++) {
            if (n == 0 || keys[i] != keys[n - 1]) keys[n++] = keys[i];
        }
        return n == keys.length ? keys : Arrays.copyOf(keys, n);
    }

    /** Whether the sorted set holds {@code key}. */
    public static boolean contains(long[] set, long key) {
        return Arrays.binarySearch(set, key) >= 0;
    }

    /** The cubes of a sorted set and their six face neighbours ({@link CubeKey}), a sorted set. */
    public static long[] cubeFaces(long[] cubes) {
        long[] flipped = flipped(cubes, CUBE_FLIP);
        long[] merged = shiftedUnion(flipped, CUBE_STEPS);
        for (int i = 0; i < merged.length; i++) merged[i] ^= CUBE_FLIP;
        return cubeOrder(merged);
    }

    /** The columns of a sorted set and their four face neighbours ({@link MapColumns}), a sorted set. */
    public static long[] columnFaces(long[] columns) {
        long[] flipped = flipped(columns, COLUMN_FLIP);
        long[] merged = shiftedUnion(flipped, COLUMN_STEPS);
        for (int i = 0; i < merged.length; i++) merged[i] ^= COLUMN_FLIP;
        return columnOrder(merged);
    }

    private static long[] flipped(long[] keys, long flip) {
        long[] out = new long[keys.length];
        for (int i = 0; i < keys.length; i++) out[i] = keys[i] ^ flip;
        Arrays.sort(out);
        return out;
    }

    /** The union of {@code sorted + step} over the steps, each copy sorted because a step is a constant add. */
    private static long[] shiftedUnion(long[] sorted, long[] steps) {
        int k = steps.length;
        int n = sorted.length;
        int[] at = new int[k];
        long[] out = new long[n * k];
        int size = 0;
        while (true) {
            long next = Long.MAX_VALUE;
            boolean any = false;
            for (int s = 0; s < k; s++) {
                if (at[s] < n) {
                    long v = sorted[at[s]] + steps[s];
                    if (!any || v < next) {
                        next = v;
                        any = true;
                    }
                }
            }
            if (!any) break;
            out[size++] = next;
            for (int s = 0; s < k; s++) {
                if (at[s] < n && sorted[at[s]] + steps[s] == next) at[s]++;
            }
        }
        return Arrays.copyOf(out, size);
    }

    /**
     * Cubes from the flipped order (x, z, y each signed) back to key order (x signed, then z and y each with their
     * negative values last): within each x the blocks of z ≥ 0 come before those of z &lt; 0, within each (x, z) the
     * y ≥ 0 before the y &lt; 0.
     */
    private static long[] cubeOrder(long[] keys) {
        long[] out = new long[keys.length];
        int n = 0;
        int i = 0;
        while (i < keys.length) {
            int x = CubeKey.x(keys[i]);
            int end = i;
            while (end < keys.length && CubeKey.x(keys[end]) == x) end++;
            int split = i;
            while (split < end && CubeKey.z(keys[split]) < 0) split++;
            n = columns(keys, split, end, out, n);
            n = columns(keys, i, split, out, n);
            i = end;
        }
        return out;
    }

    /** The (x, z) groups of {@code [from, to)} in order, each with its y ≥ 0 before its y &lt; 0. */
    private static int columns(long[] keys, int from, int to, long[] out, int n) {
        int i = from;
        while (i < to) {
            int z = CubeKey.z(keys[i]);
            int end = i;
            while (end < to && CubeKey.z(keys[end]) == z) end++;
            int split = i;
            while (split < end && CubeKey.y(keys[split]) < 0) split++;
            System.arraycopy(keys, split, out, n, end - split);
            n += end - split;
            System.arraycopy(keys, i, out, n, split - i);
            n += split - i;
            i = end;
        }
        return n;
    }

    /** Columns from the flipped order (x, z signed) back to key order: within each x the z ≥ 0 before the z &lt; 0. */
    private static long[] columnOrder(long[] keys) {
        long[] out = new long[keys.length];
        int n = 0;
        int i = 0;
        while (i < keys.length) {
            int x = MapColumns.x(keys[i]);
            int end = i;
            while (end < keys.length && MapColumns.x(keys[end]) == x) end++;
            int split = i;
            while (split < end && MapColumns.z(keys[split]) < 0) split++;
            System.arraycopy(keys, split, out, n, end - split);
            n += end - split;
            System.arraycopy(keys, i, out, n, split - i);
            n += split - i;
            i = end;
        }
        return out;
    }
}
