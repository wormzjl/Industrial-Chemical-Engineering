package com.wormzjl.icheme.runtime;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Owner-thread session commands and presentation at 100 online-tick deadlines.
 * At most one unacknowledged command per session. Closing a session expires its unsent command.
 * No wall clock, worker access, or per-tick scan. The host persists the online epoch.
 */
public final class PresentationMailbox<K> {
    public static final long PERIOD = 100;
    private final Thread owner = Thread.currentThread();
    private final Set<K> viewers = new LinkedHashSet<>();
    private final Map<K, Runnable> pending = new LinkedHashMap<>();
    private long next = Long.MAX_VALUE;

    public void subscribe(K key, long now) {
        owned();
        if (viewers.isEmpty()) next = Math.addExact(now, PERIOD - Math.floorMod(now, PERIOD));
        viewers.add(key);
    }

    public boolean submit(K key, long now, Runnable action) {
        subscribe(key, now);
        return pending.putIfAbsent(key, java.util.Objects.requireNonNull(action)) == null;
    }
    /** Queues a command for the next engine tick. The command remains owner-thread serialized and coalesced. */
    public boolean submitImmediate(K key, long now, Runnable action) {
        boolean accepted = submit(key, now, action);
        if (accepted) requestImmediate();
        return accepted;
    }

    public void remove(K key) {
        owned(); viewers.remove(key); pending.remove(key);
        if (viewers.isEmpty()) next = Long.MAX_VALUE;
    }

    public void tick(long now, Predicate<K> valid, Consumer<K> publish) {
        owned();
        if (now < next) return;
        next = Math.addExact(now, PERIOD - Math.floorMod(now, PERIOD));
        // Run all inputs before publishing, so concurrent viewers see one settled bucket.
        for (K key : List.copyOf(viewers)) {
            if (!valid.test(key)) { remove(key); continue; }
            Runnable action = pending.remove(key);
            if (action != null) action.run();
        }
        for (K key : List.copyOf(viewers)) {
            if (valid.test(key)) publish.accept(key); else remove(key);
        }
    }

    public long nextTick() { owned(); return next; }
    public int size() { owned(); return viewers.size(); }
    /** Requests one prompt publication on the next engine tick without changing the normal cadence afterwards. */
    public void requestImmediate() {
        owned();
        if (!viewers.isEmpty()) next = 0;
    }
    private void owned() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Presentation belongs to the server thread");
    }
}
