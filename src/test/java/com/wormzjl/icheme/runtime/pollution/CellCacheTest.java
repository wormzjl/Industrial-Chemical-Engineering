package com.wormzjl.icheme.runtime.pollution;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.pollution.CubeKey;
import com.wormzjl.icheme.science.pollution.Environment;
import com.wormzjl.icheme.science.pollution.Environment.Cell;
import com.wormzjl.icheme.science.pollution.KeySets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The cell and water caches keep the service's rules: budget order, fresh reads, unloaded chunks, forgetting. */
class CellCacheTest {
    private static final long A = CubeKey.of(0, 2, 0);
    private static final long B = CubeKey.of(0, 3, 0);
    private static final long C = CubeKey.of(5, 2, 5);
    private static final long HIGH = CubeKey.of(0, 40, 0);

    /** A world whose reads are logged; a cube's air count is its x + 100, so each read is told apart. */
    private static final class Log implements CellCache.World {
        final List<Long> reads = new ArrayList<>();
        final Set<Long> unloadedColumns = new HashSet<>();
        int air = 100;

        @Override
        public boolean chunkLoaded(long cube) {
            return !unloadedColumns.contains((long) CubeKey.x(cube));
        }

        @Override
        public Cell read(long cube) {
            reads.add(cube);
            return CellCache.cell(air++, true);
        }
    }

    private static long[] set(long... keys) {
        return KeySets.of(keys);
    }

    @Test
    void theCoreIsReadFirstAndTheBudgetHolds() {
        var cache = new CellCache();
        var world = new Log();
        // Budget 1: the core cube C is read, A (smaller key, not core) is over the budget and unloaded.
        Environment e = cache.snapshot(set(C), set(A, C), 0, 1200, 1, world, false, -4, 19);
        assertEquals(List.of(C), world.reads);
        assertTrue(e.cell(C).loaded());
        assertSame(Cell.UNLOADED, e.cell(A));
        assertSame(Cell.UNLOADED, e.cell(B), "a cube not wanted is unloaded");
        // The next pass: C is fresh, A gets the budget.
        cache.snapshot(set(C), set(A, C), 100, 1200, 1, world, false, -4, 19);
        assertEquals(List.of(C, A), world.reads);
        assertEquals(2, cache.size());
    }

    @Test
    void anOldReadIsUsedOverTheBudgetAndReadAgainWhenStale() {
        var cache = new CellCache();
        var world = new Log();
        cache.snapshot(set(A, B), set(A, B), 0, 1200, 2, world, false, -4, 19);
        Cell first = cache.get(B);
        // Stale at 1200, budget 1: A is read again, B keeps its old read.
        Environment e = cache.snapshot(set(A, B), set(A, B), 1200, 1200, 1, world, false, -4, 19);
        assertEquals(List.of(A, B, A), world.reads);
        assertSame(first, e.cell(B));
        assertNotSame(first, e.cell(A));
    }

    @Test
    void anUnloadedChunkOrAHeightOutsideIsUnloadedAndTheCacheForgetsWhatIsNotWanted() {
        var cache = new CellCache();
        var world = new Log();
        cache.snapshot(set(A, C), set(A, C, HIGH), 0, 1200, 9, world, false, -4, 19);
        assertEquals(List.of(A, C), world.reads, "the cube above the height is not read");
        world.unloadedColumns.add(5L);
        Environment e = cache.snapshot(set(A), set(A, C), 2000, 1200, 9, world, false, -4, 19);
        assertSame(Cell.UNLOADED, e.cell(C));
        assertNotNull(cache.get(C), "a wanted cube of an unloaded chunk keeps its read");
        cache.snapshot(set(A), set(A), 2001, 1200, 9, world, false, -4, 19);
        assertNull(cache.get(C), "a cube no longer wanted is forgotten");
        assertEquals(1, cache.size());
    }

    @Test
    void cellsOfTheSameCountsAreOneInstance() {
        assertSame(CellCache.cell(4096, true), CellCache.cell(4096, true));
        assertEquals(0.5, CellCache.cell(2048, false).airShare());
        assertFalse(CellCache.cell(2048, false).open());
    }

    @Test
    void theWaterCacheReadsWithinTheBudgetAndKeepsOldReads() {
        var cache = new WaterCache();
        List<Long> reads = new ArrayList<>();
        long[] wanted = set(1, 2, 3);
        WaterCache.World world = new WaterCache.World() {
            @Override
            public boolean chunkLoaded(long column) {
                return column != 3;
            }

            @Override
            public boolean water(long column) {
                reads.add(column);
                return column == 2;
            }
        };
        var e = cache.snapshot(wanted, 0, 1200, 1, world, false);
        assertEquals(List.of(1L), reads);
        assertTrue(e.loaded(1) && e.loaded(2) && !e.loaded(3));
        assertFalse(e.water(2), "over the budget with no read: land");
        e = cache.snapshot(wanted, 10, 1200, 1, world, true);
        assertEquals(List.of(1L, 2L), reads);
        assertTrue(e.water(2) && e.raining());
        assertEquals(2, cache.size());
        cache.snapshot(set(2), 20, 1200, 1, world, false);
        assertEquals(1, cache.size(), "the cache keeps only wanted columns");
    }
}
