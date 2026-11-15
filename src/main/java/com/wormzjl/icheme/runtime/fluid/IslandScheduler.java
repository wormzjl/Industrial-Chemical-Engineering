package com.wormzjl.icheme.runtime.fluid;

import java.util.*;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

/**
 * Server-thread deadline heap of the fluid runtime, in ticks of the shared online epoch, reached (with its
 * {@link FairQueue}) only through its {@link IslandCoordinator}, which checks the thread. Every recurring piece
 * of scheduling work is an entry here, so the per-tick hook only asks {@link #nextDue()} whether anything is
 * due, which is O(1), and pops exactly the due entries.
 *
 * <p>Invalidation is lazy: an entry carries the generation its owner had when it was scheduled, and the owner
 * drops an entry whose generation no longer matches when it is popped (a revision bump, repartition, removal
 * or stop gives the owner a new generation or removes it). A replaced deadline therefore costs one pop, never
 * a search. Entries at the same tick come out in scheduling order.
 */
public final class IslandScheduler {
    public enum Kind {
        /** An island's next slice becomes due: committed tick plus cadence, the retry span, or a fence. */
        SLICE_DUE,
        /** A held island's retry time. */
        RETRY,
        /** A dispatch round's hard wall budget may have expired. */
        ROUND_TIMEOUT,
        /** The shared worker allocator's lower-demand shrink becomes due. */
        ALLOCATOR_SHRINK,
        /** An undelivered solid recovery (offline player, full inventory, unloaded chunk) is retried. */
        RECOVERY_RETRY,
        /** The running columns' next cycle (batch 2026-10-28-column-operation, plan section 5). */
        COLUMN_CYCLE,
        /** A certified island's horizon (rest and steady-flow certificates). */
        CERTIFICATE_HORIZON
        // Presentation buckets are not entries here: FluidPresentation keeps its one next-bucket tick, checked in
        // O(1) at the end of the world tick so that a bucket sees the events applied in its own tick.
    }
    public record Deadline(long tick,long sequence,Kind kind,long id,long generation) {
        public Deadline {Objects.requireNonNull(kind);}
    }
    private final PriorityQueue<Deadline> heap=new PriorityQueue<>(Comparator.comparingLong(Deadline::tick).thenComparingLong(Deadline::sequence));
    private long sequence;
    /** Adds a deadline due once the epoch reaches {@code tick}. */
    public void schedule(long tick,Kind kind,long id,long generation) {
        if(tick==Long.MAX_VALUE)throw new IllegalArgumentException("A deadline needs a finite tick");
        heap.add(new Deadline(tick,sequence=Math.addExact(sequence,1),kind,id,generation));
    }
    /** O(1). The earliest scheduled tick, possibly of a stale entry; {@link Long#MAX_VALUE} when empty. */
    public long nextDue(){var head=heap.peek();return head==null?Long.MAX_VALUE:head.tick();}
    /** Removes and returns the earliest entry if it is due at {@code now}, otherwise null. */
    public Deadline poll(long now){var head=heap.peek();return head==null||head.tick()>now?null:heap.poll();}
    public int size(){return heap.size();}
    public void clear(){heap.clear();}
    /** Drops the entries the owner no longer recognises. Only needed to bound memory, never for correctness. */
    public void compact(Predicate<Deadline> live){heap.removeIf(entry->!live.test(entry));}
    /**
     * Coordinator-owned round robin over registered owners, without queuing duplicate heavy snapshots.
     *
     * <p>The service order is the one the queue always had: owners never served yet are offered first, in
     * registration order; a newcomer once served joins one fixed cycle at its back (just after the service
     * position, so the owners not served since come first) and becomes the position. A request that serves no
     * newcomer serves the first ready owner after the service position, round the cycle to the position itself
     * last, and makes it the position. Skipping an owner that is
     * not ready moves the position exactly as rotating it to the back of a deque did, so the order among ready
     * owners is independent of how many other owners exist.
     *
     * <p>A readiness change keeps an owner's place (a newcomer stays a newcomer, a served owner keeps its place in
     * the cycle); a removal drops it, and the position stays where it was. The order is a result: it decides which
     * islands share a round ({@code FairQueueEquivalenceTest} holds it to the queue's earlier form).
     *
     * <p>Cost: only ready owners are indexed ({@link #setReady}), so a request never visits an owner that is waiting.
     * Every registered owner has one ordinal key: a newcomer's is below zero, in registration order; a served owner's
     * is above zero, in cycle order, and the service position is the key of the owner most recently served (0 before
     * the first). {@code ready} indexes the ready owners of both ranges, {@code cycle} every served owner, ready or not,
     * for its place. {@link #register(long)} keeps the old contract that a new owner is offered to the predicate;
     * {@link #register(long,boolean)} lets the owner state readiness itself.
     */
    public static final class FairQueue {
        private static final long GAP=1L<<20;
        /** Every registered owner's key. */
        private final Map<Long,Long> keyOf=new HashMap<>();
        /** The ready owners by key: newcomers (below zero) in registration order, then served owners (above zero) in cycle order. */
        private final TreeMap<Long,Long> ready=new TreeMap<>();
        /** Every served owner by key, in cycle order. */
        private final TreeMap<Long,Long> cycle=new TreeMap<>();
        private long arrivals,last;
        public void register(long island){register(island,true);}
        public void register(long island,boolean isReady) {
            if(island<=0)throw new IllegalArgumentException("Invalid island identity");
            if(keyOf.containsKey(island))return;
            long key=Long.MIN_VALUE+(++arrivals);keyOf.put(island,key);
            if(isReady)ready.put(key,island);
        }
        public void remove(long island) {
            var key=keyOf.remove(island);if(key==null)return;
            ready.remove(key);if(key>0)cycle.remove(key);
        }
        public int size(){return keyOf.size();}
        public int readyCount(){return ready.size();}
        public boolean isReady(long island){var key=keyOf.get(island);return key!=null&&ready.containsKey(key);}
        /** Marks whether a registered owner may be served; unregistered owners are ignored. */
        public void setReady(long island,boolean isReady) {
            var key=keyOf.get(island);if(key==null||ready.containsKey(key)==isReady)return;
            if(isReady)ready.put(key,island);else ready.remove(key);
        }
        /** Serves the next ready owner that also passes {@code accept}; an owner failing it is passed over, not unmarked. */
        public OptionalLong nextReady(LongPredicate accept) {
            Objects.requireNonNull(accept);
            // A newly ready owner gets its first opportunity before already-served owners catch up again.
            for(var entry:ready.headMap(0L,false).entrySet()) {
                long arrival=entry.getKey(),island=entry.getValue();if(!accept.test(island))continue;
                ready.remove(arrival);
                long key=positionAtBack();cycle.put(key,island);keyOf.put(island,key);ready.put(key,island);last=key;
                return OptionalLong.of(island);
            }
            for(var entry:ready.tailMap(last,false).entrySet())if(accept.test(entry.getValue())){last=entry.getKey();return OptionalLong.of(entry.getValue());}
            for(var entry:ready.subMap(0L,false,last,true).entrySet())if(accept.test(entry.getValue())){last=entry.getKey();return OptionalLong.of(entry.getValue());}
            return OptionalLong.empty();
        }
        /** A key after the service position and before the next served owner: the back of the cycle. */
        private long positionAtBack() {
            var after=cycle.higherKey(last);
            if(after==null)return Math.addExact(last,GAP);
            if(after-last<2){renumber();after=cycle.higherKey(last);if(after==null)return Math.addExact(last,GAP);}
            return last+(after-last)/2;
        }
        /** Restores key gaps without changing the service order: the cycle is re-keyed from the service position. */
        private void renumber() {
            var order=new ArrayList<Long>(cycle.tailMap(last,false).values());order.addAll(cycle.headMap(last,true).values());
            var served=ready.tailMap(0L,false);var wasReady=new HashSet<Long>(served.values());
            cycle.clear();served.clear();long key=0;
            for(long island:order){key+=GAP;cycle.put(key,island);keyOf.put(island,key);if(wasReady.contains(island))ready.put(key,island);}
            last=key;
        }
    }
}
