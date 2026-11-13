package com.wormzjl.icheme.science.equipment;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import org.jetbrains.annotations.Nullable;

/**
 * A cache bounded by its entry count and by a weight, which drops entries when full, least recently used approximately
 * (finding E13, batch 2026-11-13-codebase-review): the second-chance rule. An entry used since the last eviction pass
 * survives it; an unused one goes, eldest first. It replaces caches that cleared everything when full, so one shape too many
 * no longer rebuilds every other one. A cache changes only when a value is built, never the value.
 *
 * <p>Thread safe. A hit takes no monitor: a {@link ConcurrentHashMap} read, and a write of the entry's used flag only when
 * it is clear. A value is built once per key at a time, as {@link ConcurrentHashMap#computeIfAbsent} builds it: a second
 * caller for the same key waits for the first, callers for other keys build in parallel. The builder must not use this
 * cache. A builder's null is not kept. The monitor guards only admission and eviction, and values are removed after it is
 * released. The newest entry stays even when it alone is heavier than the bound.
 */
public final class BoundedCache<K, V> {
    /** A kept value, its weight and whether it was used since the last eviction pass reached it. */
    private static final class Entry<K, V> {
        final K key;
        final V value;
        final long weight;
        volatile boolean used;

        Entry(K key, V value, long weight) {
            this.key = key;
            this.value = value;
            this.weight = weight;
        }
    }

    private final ConcurrentHashMap<K, Entry<K, V>> values = new ConcurrentHashMap<>();
    /** The admitted entries, eldest first (insertion order, an entry given a second chance moved to the tail); guarded by {@code this}. */
    private final ArrayDeque<Entry<K, V>> order = new ArrayDeque<>();
    private final int maxEntries;
    private final long maxWeight;
    private final ToLongFunction<? super V> weigher;
    /** The summed weight of the entries in {@link #order}; guarded by {@code this}. */
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
        var entry = values.get(key);
        return entry == null ? null : use(entry);
    }

    /** The value of {@code key}, built by {@code builder} and kept when absent. */
    public @Nullable V computeIfAbsent(K key, Function<? super K, ? extends V> builder) {
        var entry = values.get(key);
        if (entry != null) return use(entry);
        boolean[] built = {false};
        entry = values.computeIfAbsent(key, k -> {
            V value = builder.apply(k);
            if (value == null) return null;
            built[0] = true;
            return new Entry<>(k, value, weigher.applyAsLong(value));
        });
        if (entry == null) return null;
        if (!built[0]) return use(entry);
        admit(entry, null);
        return entry.value;
    }

    /** Keeps {@code value} for {@code key}, in place of any value it had. */
    public void put(K key, V value) {
        var entry = new Entry<>(key, value, weigher.applyAsLong(value));
        admit(entry, values.put(key, entry));
    }

    /** Drops every entry for which {@code test} holds. */
    public void removeIf(BiPredicate<? super K, ? super V> test) {
        var dropped = new ArrayList<Entry<K, V>>();
        synchronized (this) {
            for (var it = order.iterator(); it.hasNext(); ) {
                var entry = it.next();
                boolean kept = values.get(entry.key) == entry;
                if (kept && !test.test(entry.key, entry.value)) continue;
                weight -= entry.weight;
                it.remove();
                if (kept) dropped.add(entry);
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

    /** A hit: the flag is written only when it is clear, so a repeated hit writes nothing. */
    private static <K, V> V use(Entry<K, V> entry) {
        if (!entry.used) entry.used = true;
        return entry.value;
    }

    /**
     * Admits {@code entry} (in place of {@code replaced}, when a put replaced one) and drops entries past the bounds: from
     * the eldest, an entry used since the last pass is given a second chance (its flag cleared, moved to the tail), an unused
     * one is dropped. One pass clears every flag, so a second pass drops without a second chance.
     */
    private void admit(Entry<K, V> entry, @Nullable Entry<K, V> replaced) {
        var dropped = new ArrayList<Entry<K, V>>();
        synchronized (this) {
            if (replaced != null && order.remove(replaced)) weight -= replaced.weight;
            // A value cleared or dropped between its build and here is not admitted.
            if (values.get(entry.key) != entry) return;
            order.addLast(entry);
            weight += entry.weight;
            int chances = order.size();
            while ((order.size() > maxEntries || weight > maxWeight) && order.size() > 1) {
                var eldest = order.pollFirst();
                if (eldest == entry) {
                    order.addLast(eldest);
                    continue;
                }
                boolean kept = values.get(eldest.key) == eldest;
                if (kept && eldest.used && chances-- > 0) {
                    eldest.used = false;
                    order.addLast(eldest);
                    continue;
                }
                weight -= eldest.weight;
                if (kept) dropped.add(eldest);
            }
        }
        drop(dropped);
    }

    private void drop(List<Entry<K, V>> dropped) {
        for (var entry : dropped) values.remove(entry.key, entry);
    }
}
