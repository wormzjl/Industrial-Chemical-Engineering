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

    /** A world whose reads are logged, and whose successive readings have different occupancies. */
    private static final class Log implements CellCache.World {
        final List<Long> reads = new ArrayList<>();
        final Set<Long> unloadedColumns = new HashSet<>();
        final Set<Long> savedColumns = new HashSet<>();
        int air = 100;

        @Override
        public boolean chunkLoaded(long cube) {
            return !unloadedColumns.contains((long) CubeKey.x(cube));
        }

        @Override
        public CellCache.Reading read(long cube) {
            reads.add(cube);
            if (!chunkLoaded(cube) && !savedColumns.contains((long) CubeKey.x(cube))) return CellCache.Reading.UNKNOWN;
            return new CellCache.Reading(CellCache.cell(air++, true), 9);
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
        Cell first = cache.get(B).cell();
        // Stale at 1200, budget 1: A is read again, B keeps its old read.
        Environment e = cache.snapshot(set(A, B), set(A, B), 1200, 1200, 1, world, false, -4, 19);
        assertEquals(List.of(A, B, A), world.reads);
        assertSame(first, e.cell(B));
        assertNotSame(first, e.cell(A));
    }

    @Test
    void aSavedChunkStillFeedsTheSnapshotAndTheCacheForgetsWhatIsNotWanted() {
        var cache = new CellCache();
        var world = new Log();
        cache.snapshot(set(A, C), set(A, C, HIGH), 0, 1200, 9, world, false, -4, 19);
        assertEquals(List.of(A, C), world.reads, "the cube above the height is not read");
        world.unloadedColumns.add(5L);
        world.savedColumns.add(5L);
        Environment e = cache.snapshot(set(A), set(A, C), 20, 1200, 9, world, false, -4, 19);
        assertEquals(List.of(A, C, C), world.reads, "switching source refreshes the cache");
        assertTrue(e.cell(C).loaded(), "saved terrain advances the field");
        assertEquals(9, cache.get(C).centreY());
        cache.snapshot(set(A), set(A), 21, 1200, 9, world, false, -4, 19);
        assertNull(cache.get(C), "a cube no longer wanted is forgotten");
        assertEquals(1, cache.size());
    }

    @Test
    void anUnsavedChunkWaitsAndIsRetriedAsSoonAsItLoads() {
        var cache = new CellCache();
        var world = new Log();
        world.unloadedColumns.add(5L);
        var keys = set(C);
        assertSame(Cell.UNLOADED, cache.snapshot(keys, keys, 0, 1200, 1, world, false, -4, 19).cell(C));
        assertSame(Cell.UNLOADED, cache.snapshot(keys, keys, 100, 1200, 1, world, false, -4, 19).cell(C));
        assertEquals(List.of(C), world.reads, "a read miss is cached");
        world.unloadedColumns.clear();
        assertTrue(cache.snapshot(keys, keys, 200, 1200, 1, world, false, -4, 19).cell(C).loaded());
        assertEquals(List.of(C, C), world.reads);
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
                return column == 1;
            }

            @Override
            public WaterCache.Surface water(long column) {
                reads.add(column);
                return switch ((int) column) {
                    case 1 -> WaterCache.Surface.LAND;
                    case 2 -> WaterCache.Surface.WATER;
                    default -> WaterCache.Surface.UNKNOWN;
                };
            }
        };
        var e = cache.snapshot(wanted, 0, 1200, 1, world, false);
        assertEquals(List.of(1L), reads);
        assertTrue(e.loaded(1) && !e.loaded(2) && !e.loaded(3));
        e = cache.snapshot(wanted, 10, 1200, 1, world, true);
        assertEquals(List.of(1L, 2L), reads);
        assertTrue(e.loaded(2) && e.water(2) && e.raining(), "saved terrain supplies the type of surface");
        assertEquals(2, cache.size());
        cache.snapshot(set(2), 20, 1200, 1, world, false);
        assertEquals(1, cache.size(), "the cache keeps only wanted columns");
    }
}
