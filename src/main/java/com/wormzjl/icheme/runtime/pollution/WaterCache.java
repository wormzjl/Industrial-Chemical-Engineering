package com.wormzjl.icheme.runtime.pollution;

import com.wormzjl.icheme.science.pollution.GroundEnvironment;
import java.util.Arrays;

/**
 * A dimension's last surface reads by chunk column (G2), kept as sorted parallel arrays (column, water, tick), and the
 * ground's environment for a pass. No Minecraft type: the world is reached through {@link World}. Server thread only.
 *
 * <p>The rules are {@code PollutionService}'s: a column of a chunk not loaded now is unloaded for the pass; a read
 * younger than {@code freshTicks} is used again; at most {@code budget} columns are read a pass, in key order; a column
 * over the budget uses its older read, or counts as land; the cache keeps only reads of wanted columns.</p>
 */
public final class WaterCache {
    /** The world as the ground's snapshot reads it. */
    public interface World {
        boolean chunkLoaded(long column);

        /** Whether the column's surface is mostly water, read now. */
        boolean water(long column);
    }

    private long[] columns = new long[0];
    private boolean[] water = new boolean[0];
    private int[] ticks = new int[0];

    public int size() {
        return columns.length;
    }

    /** The ground's environment for the {@code wanted} columns (a sorted set); keeps the reads of the wanted columns. */
    public GroundEnvironment snapshot(long[] wanted, int now, int freshTicks, int budget, World world, boolean raining) {
        int n = wanted.length;
        byte[] kept = new byte[n]; // 0 none, 1 land, 2 water
        int[] keptTicks = new int[n];
        for (int w = 0, o = 0; w < n && o < columns.length; ) {
            if (columns[o] < wanted[w]) {
                o++;
            } else if (columns[o] > wanted[w]) {
                w++;
            } else {
                kept[w] = (byte) (water[o] ? 2 : 1);
                keptTicks[w] = ticks[o];
                w++;
                o++;
            }
        }
        long[] loaded = new long[n];
        long[] wet = new long[n];
        int loadedCount = 0;
        int wetCount = 0;
        int left = budget;
        for (int w = 0; w < n; w++) {
            long column = wanted[w];
            if (!world.chunkLoaded(column)) {
                continue;
            }
            loaded[loadedCount++] = column;
            boolean isWater;
            if (kept[w] != 0 && now - keptTicks[w] < freshTicks) {
                isWater = kept[w] == 2;
            } else if (left > 0) {
                left--;
                isWater = world.water(column);
                kept[w] = (byte) (isWater ? 2 : 1);
                keptTicks[w] = now;
            } else {
                isWater = kept[w] == 2;
            }
            if (isWater) {
                wet[wetCount++] = column;
            }
        }
        int size = 0;
        for (byte k : kept) if (k != 0) size++;
        long[] nextColumns = new long[size];
        boolean[] nextWater = new boolean[size];
        int[] nextTicks = new int[size];
        for (int w = 0, t = 0; w < n; w++) {
            if (kept[w] != 0) {
                nextColumns[t] = wanted[w];
                nextWater[t] = kept[w] == 2;
                nextTicks[t] = keptTicks[w];
                t++;
            }
        }
        columns = nextColumns;
        water = nextWater;
        ticks = nextTicks;
        return GroundEnvironment.sorted(Arrays.copyOf(loaded, loadedCount), Arrays.copyOf(wet, wetCount), raining);
    }
}
