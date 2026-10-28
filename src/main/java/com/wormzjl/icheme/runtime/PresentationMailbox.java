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
 * At most one unacknowledged command per session. A command the host accepted runs on its bucket even when its session
 * closed first: closing ends the subscription, not the input (owner decision 2026-10-28; a column calculator's Save
 * design pressed just before its screen closed was lost, and the constructor then found no design to import).
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
        if (viewers.isEmpty() && pending.isEmpty()) next = Math.addExact(now, PERIOD - Math.floorMod(now, PERIOD));
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

    /** Ends a session's subscription; a command it sent still runs on its bucket. */
    public void remove(K key) {
        owned(); viewers.remove(key);
        idle();
    }

    public void tick(long now, Predicate<K> valid, Consumer<K> publish) {
        owned();
        if (now < next) return;
        next = Math.addExact(now, PERIOD - Math.floorMod(now, PERIOD));
        // Run every accepted input, of open and closed sessions alike, in the order they came, before publishing, so
        // concurrent viewers see one settled bucket. Only open sessions are published to.
        for (K key : List.copyOf(pending.keySet())) pending.remove(key).run();
        for (K key : List.copyOf(viewers)) {
            if (valid.test(key)) publish.accept(key); else viewers.remove(key);
        }
        idle();
    }

    /** With no session and no command left, no bucket is due. */
    private void idle() {
        if (viewers.isEmpty() && pending.isEmpty()) next = Long.MAX_VALUE;
    }

    public long nextTick() { owned(); return next; }
    public int size() { owned(); return viewers.size(); }
    /** Requests one prompt publication on the next engine tick without changing the normal cadence afterwards. */
    public void requestImmediate() {
        owned();
        if (!viewers.isEmpty() || !pending.isEmpty()) next = 0;
    }
    private void owned() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Presentation belongs to the server thread");
    }
}
