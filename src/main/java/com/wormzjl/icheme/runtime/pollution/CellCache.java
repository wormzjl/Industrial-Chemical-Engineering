package com.wormzjl.icheme.runtime.pollution;

import com.wormzjl.icheme.science.pollution.CubeKey;
import com.wormzjl.icheme.science.pollution.Environment;
import com.wormzjl.icheme.science.pollution.Environment.Cell;
import java.util.Arrays;

/**
 * A dimension's last cell reads, kept as sorted parallel arrays (key, cell, tick), and the snapshot a pass reads. No
 * Minecraft type: the world is reached through {@link World}. Server thread only.
 *
 * <p>The rules are {@code PollutionService}'s: a cube outside the dimension's height or of a chunk not loaded now is
 * unloaded for the pass; a read younger than {@code freshTicks} is used again; at most {@code budget} cubes are read a
 * pass, the core first, then the rest of the wanted cubes, each in key order; a cube over the budget uses its older read,
 * or is unloaded for the pass; the cache keeps only reads of wanted cubes.</p>
 */
public final class CellCache {
    /** The world as a snapshot reads it. */
    public interface World {
        /** Whether the cube's chunk is loaded now. */
        boolean chunkLoaded(long cube);

        /** A fresh read of the cube's section; {@link Cell#loaded()} false when it cannot be read. */
        Cell read(long cube);
    }

    private static final Cell[] SHARED = new Cell[2 * (CubeKey.SECTION_BLOCKS + 1)];

    private long[] keys = new long[0];
    private Cell[] cells = new Cell[0];
    private int[] ticks = new int[0];

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
    public Cell get(long cube) {
        int i = Arrays.binarySearch(keys, cube);
        return i < 0 ? null : cells[i];
    }

    public int size() {
        return keys.length;
    }

    public void clear() {
        keys = new long[0];
        cells = new Cell[0];
        ticks = new int[0];
    }

    /**
     * The cells of the {@code wanted} cubes for the next pass ({@code core} ⊆ {@code wanted}, both sorted sets), reading
     * at most {@code budget}; keeps the reads of the wanted cubes and forgets the rest.
     */
    public Environment snapshot(long[] core, long[] wanted, int now, int freshTicks, int budget, World world,
            boolean raining, int minimumY, int maximumY) {
        int n = wanted.length;
        // The kept reads of the wanted cubes, aligned with them.
        Cell[] kept = new Cell[n];
        int[] keptTicks = new int[n];
        for (int w = 0, o = 0; w < n && o < keys.length; ) {
            if (keys[o] < wanted[w]) {
                o++;
            } else if (keys[o] > wanted[w]) {
                w++;
            } else {
                kept[w] = cells[o];
                keptTicks[w] = ticks[o];
                w++;
                o++;
            }
        }
        Cell[] out = new Cell[n];
        boolean[] done = new boolean[n];
        int[] left = {budget};
        for (int c = 0, w = 0; c < core.length; c++) {
            while (wanted[w] != core[c]) w++;
            out[w] = visit(wanted[w], w, kept, keptTicks, now, freshTicks, left, world, minimumY, maximumY);
            done[w] = true;
        }
        for (int w = 0; w < n; w++) {
            if (!done[w]) out[w] = visit(wanted[w], w, kept, keptTicks, now, freshTicks, left, world, minimumY, maximumY);
        }
        int size = 0;
        for (Cell cell : kept) if (cell != null) size++;
        long[] nextKeys = new long[size];
        Cell[] nextCells = new Cell[size];
        int[] nextTicks = new int[size];
        for (int w = 0, t = 0; w < n; w++) {
            if (kept[w] != null) {
                nextKeys[t] = wanted[w];
                nextCells[t] = kept[w];
                nextTicks[t] = keptTicks[w];
                t++;
            }
        }
        keys = nextKeys;
        cells = nextCells;
        ticks = nextTicks;
        return new Snapshot(wanted, out, raining, minimumY, maximumY);
    }

    private static Cell visit(long cube, int w, Cell[] kept, int[] keptTicks, int now, int freshTicks, int[] left,
            World world, int minimumY, int maximumY) {
        int y = CubeKey.y(cube);
        if (y < minimumY || y > maximumY || !world.chunkLoaded(cube)) {
            return Cell.UNLOADED;
        }
        Cell cached = kept[w];
        if (cached != null && now - keptTicks[w] < freshTicks) {
            return cached;
        }
        if (left[0] <= 0) {
            return cached != null ? cached : Cell.UNLOADED;
        }
        left[0]--;
        Cell read = world.read(cube);
        if (read.loaded()) {
            kept[w] = read;
            keptTicks[w] = now;
        }
        return read;
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
