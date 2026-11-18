package com.wormzjl.icheme.runtime.pollution;

import com.wormzjl.icheme.science.pollution.CubeKey;
import com.wormzjl.icheme.science.pollution.Environment;
import com.wormzjl.icheme.science.pollution.Environment.Cell;
import java.util.Arrays;

/**
 * A dimension's last cell reads, kept as sorted parallel arrays (key, cell, tick), and the snapshot a pass reads. No
 * Minecraft type: the world is reached through {@link World}. Server thread only.
 *
 * <p>A reading stays fresh while its source (live or stored) is unchanged. At most {@code budget} cubes are read per pass,
 * the core first. A cube over budget uses its last reading when available. The cache keeps only readings of wanted
 * cubes, including misses, which are retried after the freshness interval or as soon as their chunk loads.</p>
 */
public final class CellCache {
    /** The world as a snapshot reads it. */
    public interface World {
        /** Whether the cube's chunk is loaded now. */
        boolean chunkLoaded(long cube);

        /** A fresh read of the cube's live or saved section. */
        Reading read(long cube);
    }

    record Reading(Cell cell, int centreY) {
        static final Reading UNKNOWN = new Reading(Cell.UNLOADED, 8);
    }

    private record Sample(Reading reading, int tick, boolean live) {}

    private static final Cell[] SHARED = new Cell[2 * (CubeKey.SECTION_BLOCKS + 1)];

    private long[] keys = new long[0];
    private Sample[] samples = new Sample[0];

    /**
     * The one loaded cell of {@code air} air blocks out of {@link CubeKey#SECTION_BLOCKS}, open or not: cells are values,
     * so every read with the same counts shares one instance.
     */
    public static Cell cell(int air, boolean open) {
        int slot = 2 * air + (open ? 1 : 0);
        Cell cell = SHARED[slot];
        if (cell == null) {
            cell = new Cell(air / (double) CubeKey.SECTION_BLOCKS, open, true);
            SHARED[slot] = cell;
        }
        return cell;
    }

    /** The kept read of a cube, or {@code null}. */
    Reading get(long cube) {
        int i = Arrays.binarySearch(keys, cube);
        return i < 0 ? null : samples[i].reading();
    }

    public int size() {
        return keys.length;
    }

    public void clear() {
        keys = new long[0];
        samples = new Sample[0];
    }

    /**
     * The cells of the {@code wanted} cubes for the next pass ({@code core} ⊆ {@code wanted}, both sorted sets), reading
     * at most {@code budget}; keeps the reads of the wanted cubes and forgets the rest.
     */
    public Environment snapshot(long[] core, long[] wanted, int now, int freshTicks, int budget, World world,
            boolean raining, int minimumY, int maximumY) {
        int n = wanted.length;
        // The kept reads of the wanted cubes, aligned with them.
        Sample[] kept = new Sample[n];
        for (int w = 0, o = 0; w < n && o < keys.length; ) {
            if (keys[o] < wanted[w]) {
                o++;
            } else if (keys[o] > wanted[w]) {
                w++;
            } else {
                kept[w] = samples[o];
                w++;
                o++;
            }
        }
        Cell[] out = new Cell[n];
        boolean[] done = new boolean[n];
        int[] left = {budget};
        for (int c = 0, w = 0; c < core.length; c++) {
            while (wanted[w] != core[c]) w++;
            out[w] = visit(wanted[w], w, kept, now, freshTicks, left, world, minimumY, maximumY);
            done[w] = true;
        }
        for (int w = 0; w < n; w++) {
            if (!done[w]) out[w] = visit(wanted[w], w, kept, now, freshTicks, left, world, minimumY, maximumY);
        }
        int size = 0;
        for (Sample sample : kept) if (sample != null) size++;
        long[] nextKeys = new long[size];
        Sample[] nextSamples = new Sample[size];
        for (int w = 0, t = 0; w < n; w++) {
            if (kept[w] != null) {
                nextKeys[t] = wanted[w];
                nextSamples[t] = kept[w];
                t++;
            }
        }
        keys = nextKeys;
        samples = nextSamples;
        return new Snapshot(wanted, out, raining, minimumY, maximumY);
    }

    private static Cell visit(long cube, int w, Sample[] kept, int now, int freshTicks, int[] left,
            World world, int minimumY, int maximumY) {
        int y = CubeKey.y(cube);
        if (y < minimumY || y > maximumY) {
            return Cell.UNLOADED;
        }
        boolean live = world.chunkLoaded(cube);
        Sample cached = kept[w];
        if (cached != null && cached.live() == live && now - cached.tick() < freshTicks) {
            return cached.reading().cell();
        }
        if (left[0] <= 0) {
            return cached != null ? cached.reading().cell() : Cell.UNLOADED;
        }
        left[0]--;
        Reading read = world.read(cube);
        kept[w] = new Sample(read, now, live);
        return read.cell();
    }

    /** A pass's environment: the wanted cubes' cells, aligned with their sorted keys; any other cube is unloaded. */
    private record Snapshot(long[] keys, Cell[] cells, boolean raining, int minimumY, int maximumY) implements Environment {
        @Override
        public Cell cell(long key) {
            int i = Arrays.binarySearch(keys, key);
            return i < 0 ? Cell.UNLOADED : cells[i];
        }
    }
}
