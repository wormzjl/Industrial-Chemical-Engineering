package com.wormzjl.icheme.runtime.pollution;

import com.wormzjl.icheme.science.pollution.GroundEnvironment;
import java.util.Arrays;

/**
 * A dimension's last surface reads by chunk column (G2), kept as sorted parallel arrays (column, water, tick), and the
 * ground's environment for a pass. No Minecraft type: the world is reached through {@link World}. Server thread only.
 *
 * <p>A reading stays fresh while its source (live or stored) is unchanged. At most {@code budget} columns are read per
 * pass. A column over budget uses its last reading when available. The cache keeps only readings of wanted columns.</p>
 */
public final class WaterCache {
    /** The world as the ground's snapshot reads it. */
    public interface World {
        boolean chunkLoaded(long column);

        /** A fresh read of the column's live or saved surface. */
        Surface water(long column);
    }

    enum Surface { UNKNOWN, LAND, WATER }

    private record Sample(Surface surface, int tick, boolean live) {}

    private long[] columns = new long[0];
    private Sample[] samples = new Sample[0];

    public int size() {
        return columns.length;
    }

    /** The ground's environment for the {@code wanted} columns (a sorted set); keeps the reads of the wanted columns. */
    public GroundEnvironment snapshot(long[] wanted, int now, int freshTicks, int budget, World world, boolean raining) {
        int n = wanted.length;
        Sample[] kept = new Sample[n];
        for (int w = 0, o = 0; w < n && o < columns.length; ) {
            if (columns[o] < wanted[w]) {
                o++;
            } else if (columns[o] > wanted[w]) {
                w++;
            } else {
                kept[w] = samples[o];
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
            boolean live = world.chunkLoaded(column);
            Sample sample = kept[w];
            boolean fresh = sample != null && sample.live() == live && now - sample.tick() < freshTicks;
            if (!fresh && left > 0) {
                left--;
                sample = new Sample(world.water(column), now, live);
                kept[w] = sample;
            }
            if (sample == null || sample.surface() == Surface.UNKNOWN) continue;
            loaded[loadedCount++] = column;
            if (sample.surface() == Surface.WATER) wet[wetCount++] = column;
        }
        int size = 0;
        for (Sample sample : kept) if (sample != null) size++;
        long[] nextColumns = new long[size];
        Sample[] nextSamples = new Sample[size];
        for (int w = 0, t = 0; w < n; w++) {
            if (kept[w] != null) {
                nextColumns[t] = wanted[w];
                nextSamples[t] = kept[w];
                t++;
            }
        }
        columns = nextColumns;
        samples = nextSamples;
        return GroundEnvironment.sorted(Arrays.copyOf(loaded, loadedCount), Arrays.copyOf(wet, wetCount), raining);
    }
}
