package com.wormzjl.icheme.science.equipment;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import org.jetbrains.annotations.Nullable;

/**
 * A cache bounded by its entry count and by a weight, which drops the least recently used entries when full (finding E13,
 * batch 2026-11-13-codebase-review). It replaces caches that cleared everything when full, so one shape too many no longer
 * rebuilds every other one. A cache changes only when a value is built, never the value.
 *
 * <p>Thread safe. A value is built once per key at a time, as {@link ConcurrentHashMap#computeIfAbsent} builds it: a second
 * caller for the same key waits for the first, callers for other keys build in parallel. The builder must not use this
 * cache. A builder's null is not kept. The newest entry stays even when it alone is heavier than the bound.
 */
public final class BoundedCache<K, V> {
    private final ConcurrentHashMap<K, V> values = new ConcurrentHashMap<>();
    /** The admitted keys, least recently used first, and the weight of each; guarded by {@code this}. */
    private final LinkedHashMap<K, Long> order = new LinkedHashMap<>(16, .75f, true);
    private final int maxEntries;
    private final long maxWeight;
    private final ToLongFunction<? super V> weigher;
    private long weight;

    /** A cache of at most {@code maxEntries} entries. */
    public BoundedCache(int maxEntries) {
        this(maxEntries, Long.MAX_VALUE, v -> 0);
    }

    /** A cache of at most {@code maxEntries} entries whose {@code weigher} weights sum to at most {@code maxWeight}. */
    public BoundedCache(int maxEntries, long maxWeight, ToLongFunction<? super V> weigher) {
        if (maxEntries < 1 || maxWeight < 0) throw new IllegalArgumentException("A cache holds at least one entry");
        this.maxEntries = maxEntries;
        this.maxWeight = maxWeight;
        this.weigher = weigher;
    }

    /** The value of {@code key}, or null; a hit counts as a use. */
    public @Nullable V get(K key) {
        var value = values.get(key);
        if (value != null) touch(key);
        return value;
    }

    /** The value of {@code key}, built by {@code builder} and kept when absent. */
    public @Nullable V computeIfAbsent(K key, Function<? super K, ? extends V> builder) {
        var value = values.get(key);
        if (value != null) {
            touch(key);
            return value;
        }
        boolean[] built = {false};
        value = values.computeIfAbsent(key, k -> {
            built[0] = true;
            return builder.apply(k);
        });
        if (value == null) return null;
        if (built[0]) admit(key, value);
        else touch(key);
        return value;
    }

    /** Keeps {@code value} for {@code key}, in place of any value it had. */
    public void put(K key, V value) {
        values.put(key, value);
        admit(key, value);
    }

    /** Drops every entry for which {@code test} holds. */
    public void removeIf(BiPredicate<? super K, ? super V> test) {
        var dropped = new ArrayList<Map.Entry<K, V>>();
        synchronized (this) {
            for (Iterator<Map.Entry<K, Long>> it = order.entrySet().iterator(); it.hasNext(); ) {
                var entry = it.next();
                var value = values.get(entry.getKey());
                if (value != null && !test.test(entry.getKey(), value)) continue;
                weight -= entry.getValue();
                it.remove();
                if (value != null) dropped.add(Map.entry(entry.getKey(), value));
            }
        }
        drop(dropped);
    }

    public void clear() {
        synchronized (this) {
            order.clear();
            weight = 0;
        }
        values.clear();
    }

    public int size() {
        return values.size();
    }

    /** The summed weight of the kept entries. */
    public synchronized long weight() {
        return weight;
    }

    private synchronized void touch(K key) {
        order.get(key);
    }

    /**
     * Admits {@code value} and drops the least recently used entries past the bounds. The monitor guards only the order:
     * the values are removed after it is released, so a builder holding a bin of {@link #values} can still read the cache.
     */
    private void admit(K key, V value) {
        var dropped = new ArrayList<Map.Entry<K, V>>();
        synchronized (this) {
            // A value cleared or dropped between its build and here is not admitted.
            if (values.get(key) != value) return;
            long w = weigher.applyAsLong(value);
            var previous = order.put(key, w);
            weight += w - (previous == null ? 0 : previous);
            for (var it = order.entrySet().iterator(); (order.size() > maxEntries || weight > maxWeight) && order.size() > 1 && it.hasNext(); ) {
                var eldest = it.next();
                if (eldest.getKey().equals(key)) continue;
                weight -= eldest.getValue();
                it.remove();
                var old = values.get(eldest.getKey());
                if (old != null) dropped.add(Map.entry(eldest.getKey(), old));
            }
        }
        drop(dropped);
    }

    private void drop(List<Map.Entry<K, V>> dropped) {
        for (var entry : dropped) values.remove(entry.getKey(), entry.getValue());
    }
}
