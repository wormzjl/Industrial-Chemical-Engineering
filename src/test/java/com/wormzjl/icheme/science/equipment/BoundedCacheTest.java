package com.wormzjl.icheme.science.equipment;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The bounded cache (finding E13, batch 2026-11-13-codebase-review): the least recently used entry goes when full, not
 * every entry, so a 65th shape in view costs one build where clearing the cache cost 65.
 */
class BoundedCacheTest {
    @Test
    void aSixtyFifthKeyDropsOnlyTheLeastRecentlyUsed() {
        var builds = new AtomicInteger();
        var cache = new BoundedCache<Integer, String>(64);
        for (int key = 0; key < 64; key++) cache.computeIfAbsent(key, k -> "v" + k + builds.incrementAndGet());
        assertEquals(64, builds.get());
        cache.get(0); // key 0 is now the most recently used, key 1 the least
        cache.computeIfAbsent(64, k -> "v" + k + builds.incrementAndGet());
        assertEquals(65, builds.get());
        assertEquals(64, cache.size());
        assertNull(cache.get(1), "the least recently used entry is dropped");
        // Every other shape still in view is kept: walking them all again builds nothing (the old cache rebuilt all 64).
        for (int key = 0; key < 65; key++) if (key != 1) cache.computeIfAbsent(key, k -> "v" + k + builds.incrementAndGet());
        assertEquals(65, builds.get(), "rebuilds when a 65th key enters");
    }

    @Test
    void theWeightBoundDropsTheEldestAndKeepsTheNewest() {
        var cache = new BoundedCache<String, Integer>(10, 100, v -> v);
        cache.computeIfAbsent("a", k -> 40);
        cache.computeIfAbsent("b", k -> 40);
        assertEquals(80, cache.weight());
        cache.computeIfAbsent("c", k -> 40);
        assertNull(cache.get("a"));
        assertEquals(80, cache.weight());
        cache.computeIfAbsent("heavy", k -> 500);
        assertEquals(1, cache.size(), "the newest stays alone even past the bound");
        assertEquals(Integer.valueOf(500), cache.get("heavy"));
    }

    @Test
    void aNullIsNotKeptAndRemoveIfAndClearDropEntries() {
        var cache = new BoundedCache<Integer, Integer>(8);
        assertNull(cache.computeIfAbsent(1, k -> null));
        assertEquals(0, cache.size());
        for (int key = 0; key < 6; key++) cache.put(key, key);
        cache.removeIf((k, v) -> v % 2 == 0);
        assertEquals(3, cache.size());
        assertNull(cache.get(2));
        assertEquals(Integer.valueOf(3), cache.get(3));
        cache.clear();
        assertEquals(0, cache.size());
        assertEquals(0, cache.weight());
        assertEquals(Integer.valueOf(7), cache.computeIfAbsent(7, k -> 7));
    }
}
