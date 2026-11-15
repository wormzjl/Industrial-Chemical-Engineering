package com.wormzjl.icheme.runtime.fluid;

import java.util.*;
import java.util.function.LongPredicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The ready queue's order is a result (it decides which islands share a round): the queue of today serves exactly what its
 * earlier form served, on every sequence of operations (batch 2026-11-13-codebase-review, D-R1, review row R14).
 */
class FairQueueEquivalenceTest {
    /** A predicate call that fails, to show that a failing predicate leaves both queues as they were. */
    private static final class Refused extends RuntimeException {}

    /** The outcome of one operation: its value, or the class of what it threw. */
    private static Object outcome(Supplier<?> action) {
        try{return action.get();}catch(RuntimeException failure){return failure.getClass();}
    }

    /** The predicate both queues are offered: it records every owner it is asked about, in order, and may refuse a call. */
    private static final class Probe implements LongPredicate {
        final List<Long> asked=new ArrayList<>();final Set<Long> veto;final int failAt;
        Probe(Set<Long> veto,int failAt){this.veto=veto;this.failAt=failAt;}
        @Override public boolean test(long owner){asked.add(owner);if(asked.size()==failAt)throw new Refused();return !veto.contains(owner);}
    }

    private static final class Counts {long served,fromCycle,wrapped,renumbered,refusedCalls;}

    /** One request on both queues, compared: the owner served, every owner the predicate was asked about, and a failure. */
    private static void request(EarlierFairQueue earlier,IslandScheduler.FairQueue queue,Set<Long> veto,int failAt,Counts counts,String where) {
        // A renumbering happens when a newcomer is served while the key gap after the position is exhausted.
        var after=earlier.cycle.higherKey(earlier.last);boolean tight=after!=null&&after-earlier.last<2;
        int newcomers=earlier.readyNewcomers.size();long position=earlier.last;
        var expectedProbe=new Probe(veto,failAt);var actualProbe=new Probe(veto,failAt);
        var expected=outcome(()->earlier.nextReady(expectedProbe));var actual=outcome(()->queue.nextReady(actualProbe));
        assertEquals(expected,actual,where+": the owner served");
        assertEquals(expectedProbe.asked,actualProbe.asked,where+": the owners the predicate was asked about, in order");
        if(expected==Refused.class)counts.refusedCalls++;
        if(expected instanceof OptionalLong served&&served.isPresent()) {
            counts.served++;
            if(earlier.readyNewcomers.size()==newcomers){counts.fromCycle++;if(earlier.last<=position)counts.wrapped++;}
            else if(tight)counts.renumbered++;
        }
    }
    private static void compareObservables(EarlierFairQueue earlier,IslandScheduler.FairQueue queue,int owners,String where) {
        assertEquals(earlier.size(),queue.size(),where+": size");
        assertEquals(earlier.readyCount(),queue.readyCount(),where+": ready count");
        for(long id=-1;id<=owners+1;id++)assertEquals(earlier.isReady(id),queue.isReady(id),where+": readiness of "+id);
    }

    /**
     * Seeded sequences of every operation on small id ranges, so that owners collide, leave, come back and are renumbered:
     * every pick, every predicate call and every observable equal after each operation.
     */
    @Test void theQueueServesWhatItsEarlierFormServedOnEverySequenceOfOperations() {
        var counts=new Counts();
        for(int seed=0;seed<24;seed++) {
            var random=new SplittableRandom(0x5EED_0000L+seed);
            var earlier=new EarlierFairQueue();var queue=new IslandScheduler.FairQueue();
            int owners=seed%3==0?2+random.nextInt(6):seed%3==1?8+random.nextInt(24):32+random.nextInt(33);
            Set<Long> veto=new HashSet<>();
            for(int step=0;step<5_000;step++) {
                String where="seed "+seed+" step "+step;
                long id=1+random.nextInt(owners);int op=random.nextInt(24);
                if(op==0)assertEquals(outcome(()->{earlier.register(id);return "done";}),outcome(()->{queue.register(id);return "done";}),where);
                else if(op<=2){boolean ready=random.nextBoolean();assertEquals(outcome(()->{earlier.register(id,ready);return "done";}),outcome(()->{queue.register(id,ready);return "done";}),where);}
                else if(op==3){earlier.remove(id);queue.remove(id);}
                else if(op<=7){boolean ready=random.nextInt(3)!=0;earlier.setReady(id,ready);queue.setReady(id,ready);}
                else if(op==8){veto=new HashSet<>();for(long owner=1;owner<=owners;owner++)if(random.nextInt(4)==0)veto.add(owner);}
                else if(op==9) {
                    // An invalid identity is refused by both, and changes nothing.
                    long invalid=-random.nextInt(3);boolean ready=random.nextBoolean();
                    assertEquals(outcome(()->{earlier.register(invalid,ready);return "done";}),outcome(()->{queue.register(invalid,ready);return "done";}),where);
                }
                else if(op==10) {
                    // A burst of owners that leave and come back ready, then are served in a row: newcomers served at the
                    // position exhaust the key gap after it and force a renumbering.
                    int burst=1+random.nextInt(owners);var back=new ArrayList<Long>();
                    for(int i=0;i<burst;i++){long owner=1+random.nextInt(owners);earlier.remove(owner);queue.remove(owner);back.add(owner);}
                    for(long owner:back){earlier.register(owner,true);queue.register(owner,true);}
                    for(int i=0;i<burst;i++)request(earlier,queue,Set.of(),0,counts,where+" burst "+i);
                }
                else if(op==11)assertEquals(outcome(()->earlier.nextReady(null)),outcome(()->queue.nextReady(null)),where+": a missing predicate");
                else if(op==12)request(earlier,queue,veto,1+random.nextInt(4),counts,where+" (a failing predicate)");
                else if(op==13)request(earlier,queue,Set.of(),0,counts,where+" (every owner accepted)");
                else request(earlier,queue,veto,0,counts,where);
                compareObservables(earlier,queue,owners,where);
            }
            // Drain: every ready owner, round after round, in the same order.
            for(int i=0;i<3*owners;i++)request(earlier,queue,Set.of(),0,counts,"seed "+seed+" drain "+i);
            compareObservables(earlier,queue,owners,"seed "+seed+" drained");
        }
        // The sequences must exercise every path the order depends on.
        assertTrue(counts.served>20_000,"owners served: "+counts.served);
        assertTrue(counts.fromCycle>5_000,"owners served from the cycle: "+counts.fromCycle);
        assertTrue(counts.served-counts.fromCycle>2_000,"newcomers served: "+(counts.served-counts.fromCycle));
        assertTrue(counts.wrapped>500,"requests that went round the cycle past its last key: "+counts.wrapped);
        assertTrue(counts.renumbered>0,"renumberings: "+counts.renumbered);
        assertTrue(counts.refusedCalls>100,"requests whose predicate failed: "+counts.refusedCalls);
    }

    /** Many newcomers served while the position is mid-cycle: the renumbering keeps the order (the scenario of IslandSchedulerTest). */
    @Test void aRenumberingMidCycleKeepsTheOrder() {
        var earlier=new EarlierFairQueue();var queue=new IslandScheduler.FairQueue();var counts=new Counts();
        for(long id=1;id<=10;id++){earlier.register(id);queue.register(id);}
        for(int i=0;i<15;i++)request(earlier,queue,Set.of(),0,counts,"first round "+i);
        for(long id=11;id<=110;id++){earlier.register(id);queue.register(id);request(earlier,queue,Set.of(),0,counts,"newcomer "+id);}
        var veto=new HashSet<Long>();for(long id=3;id<=110;id+=3)veto.add(id);
        for(int i=0;i<500;i++)request(earlier,queue,veto,0,counts,"rounds "+i);
        compareObservables(earlier,queue,110,"end");
        assertTrue(counts.renumbered>0,"the newcomers must exhaust the gap: renumberings "+counts.renumbered);
    }

    /**
     * The queue as it was before batch 2026-11-13-codebase-review (D-R1, R14): {@code IslandScheduler.FairQueue} copied
     * verbatim from commit d33c0a18a, renamed and made private. It is the reference the queue of today is held to; do
     * not change it.
     */
    private static final class EarlierFairQueue {
        private static final long GAP=1L<<20;
        private final Set<Long> registered=new HashSet<>(),ready=new HashSet<>();
        private final Map<Long,Long> arrival=new HashMap<>();
        private final TreeMap<Long,Long> newcomers=new TreeMap<>(),readyNewcomers=new TreeMap<>();
        private final Map<Long,Long> keyOf=new HashMap<>();
        private final TreeMap<Long,Long> cycle=new TreeMap<>(),readyCycle=new TreeMap<>();
        private long arrivals,last;
        public void register(long island){register(island,true);}
        public void register(long island,boolean isReady) {
            if(island<=0)throw new IllegalArgumentException("Invalid island identity");
            if(!registered.add(island))return;
            long order=++arrivals;arrival.put(island,order);newcomers.put(order,island);
            if(isReady){ready.add(island);readyNewcomers.put(order,island);}
        }
        public void remove(long island) {
            if(!registered.remove(island))return;ready.remove(island);
            var order=arrival.remove(island);if(order!=null){newcomers.remove(order);readyNewcomers.remove(order);}
            var key=keyOf.remove(island);if(key!=null){cycle.remove(key);readyCycle.remove(key);}
        }
        public int size(){return registered.size();}
        public int readyCount(){return ready.size();}
        public boolean isReady(long island){return ready.contains(island);}
        /** Marks whether a registered owner may be served; unregistered owners are ignored. */
        public void setReady(long island,boolean isReady) {
            if(!registered.contains(island)||ready.contains(island)==isReady)return;
            if(isReady)ready.add(island);else ready.remove(island);
            var order=arrival.get(island);
            if(order!=null){if(isReady)readyNewcomers.put(order,island);else readyNewcomers.remove(order);return;}
            long key=keyOf.get(island);if(isReady)readyCycle.put(key,island);else readyCycle.remove(key);
        }
        /** Serves the next ready owner that also passes {@code accept}; an owner failing it is passed over, not unmarked. */
        public OptionalLong nextReady(LongPredicate accept) {
            Objects.requireNonNull(accept);
            // A newly ready owner gets its first opportunity before already-served owners catch up again.
            for(var entry:readyNewcomers.entrySet()) {
                long island=entry.getValue();if(!accept.test(island))continue;
                newcomers.remove(entry.getKey());readyNewcomers.remove(entry.getKey());arrival.remove(island);
                long key=positionAtBack();cycle.put(key,island);keyOf.put(island,key);readyCycle.put(key,island);last=key;
                return OptionalLong.of(island);
            }
            for(var entry:readyCycle.tailMap(last,false).entrySet())if(accept.test(entry.getValue())){last=entry.getKey();return OptionalLong.of(entry.getValue());}
            for(var entry:readyCycle.headMap(last,true).entrySet())if(accept.test(entry.getValue())){last=entry.getKey();return OptionalLong.of(entry.getValue());}
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
            cycle.clear();readyCycle.clear();keyOf.clear();long key=0;
            for(long island:order){key+=GAP;cycle.put(key,island);keyOf.put(island,key);if(ready.contains(island))readyCycle.put(key,island);}
            last=key;
        }
    }
}
