package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.runtime.ProcessSolveJobs;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.IslandScheduler.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

/** The deadline heap and the scheduling properties of the coordinator built on it. */
class IslandSchedulerTest {
    /** Merges or splits aligned islands without changing any owned inventory: a topology change with no additions or
     * removals, at the first affected island's committed and online ticks. */
    private static void repartition(IslandCoordinator coordinator,UUID event,Set<Long> affected,List<IslandCoordinator.Replacement> replacements) {
        var clock=coordinator.observe(affected.iterator().next()).clock();
        coordinator.topology(Set.of(event),affected,replacements,MODEL,clock.committedTick(),clock.onlineTick(),Map.of(),Set.of(),Map.of(),()->{});
    }
    private static final FluidThermodynamics MODEL=FluidTestSupport.networkModel();
    private static final PassiveNetwork.Reservoir TANK=new PassiveNetwork.Reservoir(1,0,MODEL.initialNitrogenCharge(1,298.15,101325,()->{}));

    @AfterEach void disableCounters(){FluidRuntimeDiagnostics.ENABLED=false;FluidRuntimeDiagnostics.reset();}
    private static void count(){FluidRuntimeDiagnostics.reset();FluidRuntimeDiagnostics.ENABLED=true;}
    private static long counted(String name){return FluidRuntimeDiagnostics.sample().get(name);}

    /** Hands out slices and, on request, completes them with a real interval solve. */
    private static final class Dispatch implements IslandCoordinator.Dispatcher {
        long sequence;int capacity=64;IslandCoordinator coordinator;
        final Map<Long,IslandCoordinator.Attempt> attempts=new LinkedHashMap<>();
        final Map<Long,ProcessSolveJobs.FluidIslandCommand> commands=new HashMap<>();
        final List<IslandCoordinator.Attempt> submitted=new ArrayList<>();
        public int availableWorkers(){return capacity-attempts.size();}
        public long nextRequestId(){return ++sequence;}
        public boolean submit(IslandCoordinator.Attempt attempt,ProcessSolveJobs.FluidIslandCommand command){attempts.put(attempt.slice().requestId(),attempt);commands.put(attempt.slice().requestId(),command);submitted.add(attempt);return true;}
        public void cancel(long request){}
        void finishAll() {
            for(long request:List.copyOf(attempts.keySet())) {
                var attempt=attempts.remove(request);var command=commands.remove(request);
                var result=new PassiveIntervalSolver(MODEL).solve(command.snapshot(),command.durationSeconds(),command.settings(),()->{});
                coordinator.completed(attempt,Optional.of(new ProcessSolveJobs.FluidIslandSolveResult(Optional.of(result),"FULL",10)));
            }
            coordinator.pump();
        }
    }
    private static IslandCoordinator coordinator(Dispatch dispatch) {
        var coordinator=new IslandCoordinator(dispatch,changed->{},()->0,new IslandCoordinator.Settings(2_000_000_000L,1_500_000_000L,64,false));
        dispatch.coordinator=coordinator;return coordinator;
    }
    private static IslandCoordinator.Snapshot island(long id,long online,long committed,Map<UUID,Long> fences) {
        var node=new PassiveNetwork.Reservoir(id,0,TANK.state());
        return new IslandCoordinator.Snapshot(id,0,new PassiveNetwork(List.of(node),List.of()),new IslandClock.Snapshot(online,committed,0,100),Optional.empty(),"READY",fences);
    }

    @Test void deadlinesComeOutByTickThenInSchedulingOrderAndNextDueIsAPeek() {
        var scheduler=new IslandScheduler();assertEquals(Long.MAX_VALUE,scheduler.nextDue());assertNull(scheduler.poll(Long.MAX_VALUE-1));
        scheduler.schedule(50,SLICE_DUE,1,1);scheduler.schedule(10,RETRY,2,1);scheduler.schedule(50,ROUND_TIMEOUT,3,0);scheduler.schedule(10,RECOVERY_RETRY,4,7);
        for(int i=0;i<1000;i++)assertEquals(10,scheduler.nextDue());
        assertEquals(4,scheduler.size(),"nextDue must not pop");assertNull(scheduler.poll(9));
        assertEquals(2,scheduler.poll(10).id());assertEquals(4,scheduler.poll(10).id());assertNull(scheduler.poll(10));
        assertEquals(50,scheduler.nextDue());var tied=scheduler.poll(60);assertEquals(1,tied.id());assertEquals(SLICE_DUE,tied.kind());assertEquals(3,scheduler.poll(60).id());
        assertEquals(Long.MAX_VALUE,scheduler.nextDue());
        // The answer is the heap's head whatever its size: nextDue is O(1) and leaves the heap as it was.
        for(int i=0;i<100_000;i++)scheduler.schedule(1_000_000-i,SLICE_DUE,i,0);
        assertEquals(900_001,scheduler.nextDue());assertEquals(100_000,scheduler.size());
        scheduler.compact(entry->entry.id()%2==0);assertEquals(50_000,scheduler.size());assertEquals(900_002,scheduler.nextDue(),"id 99,998 is now the head");
        assertThrows(IllegalArgumentException.class,()->scheduler.schedule(Long.MAX_VALUE,SLICE_DUE,1,0));
        scheduler.clear();assertEquals(0,scheduler.size());
    }

    @Test void aRevisionBumpLeavesOnlyStaleEntriesThatFireNothingUntilTheResumeReschedules() {
        var dispatch=new Dispatch();var coordinator=coordinator(dispatch);
        coordinator.register(island(1,0,0,Map.of()),MODEL);coordinator.register(island(2,0,0,Map.of()),MODEL);
        assertEquals(100,coordinator.nextDue());
        coordinator.suspendForPropertyChange("HELD: property data changed");count();
        for(int tick=0;tick<150;tick++)coordinator.tick();
        assertTrue(dispatch.submitted.isEmpty());assertEquals(0,counted("deadlinesFired"),"a revision bump must invalidate the queued deadlines");
        assertEquals(0,counted("islandVisits"));assertEquals(0,counted("readinessPumps"));assertEquals(Long.MAX_VALUE,coordinator.nextDue(),"stale entries are dropped when popped");
        coordinator.resumeQualifiedProperties();assertEquals(2,coordinator.readyCount());
        coordinator.tick();assertEquals(2,dispatch.submitted.size());
    }

    @Test void repartitionAndRemovalInvalidateTheReplacedIslandsDeadlines() {
        var dispatch=new Dispatch();var coordinator=coordinator(dispatch);
        coordinator.register(island(1,0,0,Map.of()),MODEL);coordinator.register(island(2,0,0,Map.of()),MODEL);coordinator.register(island(3,0,0,Map.of()),MODEL);
        assertEquals(3,coordinator.scheduledDeadlines(),"one live deadline per island");
        var merge=UUID.randomUUID();coordinator.fence(merge,0,List.of(1L,2L));assertTrue(coordinator.aligned(merge,List.of(1L,2L)));
        var first=coordinator.snapshot(1).graph().reservoirs().getFirst();var second=coordinator.snapshot(2).graph().reservoirs().getFirst();
        repartition(coordinator,merge,Set.of(1L,2L),List.of(new IslandCoordinator.Replacement(4,new PassiveNetwork(List.of(first,second),List.of()))));
        var removal=UUID.randomUUID();coordinator.fence(removal,0,List.of(3L));
        coordinator.topology(Set.of(removal),Set.of(3L),List.of(),MODEL,0,0,Map.of(),Set.of(3L),Map.of(),()->{});
        assertEquals(Set.of(4L),coordinator.snapshots().stream().map(IslandCoordinator.Snapshot::id).collect(java.util.stream.Collectors.toSet()));
        // The replacement starts cold (IslandCoordinator.topology): one-tick first slice, doubling as each is accepted,
        // so it fires one slice deadline per dispatch; the replaced islands' entries, due at tick 100, fire nothing.
        count();for(int tick=0;tick<=100;tick++){coordinator.tick();dispatch.finishAll();}
        assertEquals(dispatch.submitted.size(),counted("deadlinesFired"),"only the replacement's deadlines are current");
        assertEquals(List.of(1L,2L,4L,8L,16L,32L),dispatch.submitted.stream().map(a->a.slice().endTick()-a.slice().startTick()).toList());
        assertTrue(dispatch.submitted.stream().allMatch(a->a.islandId()==4L));
        assertTrue(coordinator.fencedIslands(merge).isEmpty()&&coordinator.fencedIslands(removal).isEmpty(),"released events leave no index entries");
    }

    @Test void stopClearsEveryDeadlineAndLaterTicksDoNothing() {
        var dispatch=new Dispatch();var coordinator=coordinator(dispatch);
        for(long id=1;id<=5;id++)coordinator.register(island(id,0,0,Map.of()),MODEL);
        coordinator.stop();assertEquals(Long.MAX_VALUE,coordinator.nextDue());assertEquals(0,coordinator.scheduledDeadlines());
        count();for(int tick=0;tick<500;tick++)coordinator.tick();
        assertTrue(dispatch.submitted.isEmpty());assertEquals(0,counted("islandVisits"));assertEquals(0,counted("readinessPumps"));
    }

    /**
     * Idle islands (waiting on an event fence at their committed tick, the one state without due work before
     * certificates exist) cost nothing per tick at any count, and a transient island's work is the same with
     * or without a thousand idle neighbours.
     */
    @Test void idleIslandsCostNothingPerTickAtOneHundredOrAThousand() {
        long active=-1;
        for(int count:new int[]{1,100,1000}) {
            var dispatch=new Dispatch();var coordinator=coordinator(dispatch);var event=UUID.randomUUID();
            for(long id=1;id<=count;id++)coordinator.register(island(id,0,0,Map.of(event,0L)),MODEL);
            assertEquals(Long.MAX_VALUE,coordinator.nextDue());assertEquals(0,coordinator.scheduledDeadlines());assertEquals(0,coordinator.readyCount());
            count();for(int tick=0;tick<10_000;tick++)coordinator.tick();
            for(var name:List.of("islandVisits","readinessPumps","solvesDispatched","deadlinesFired","islandSnapshots","topologySnapshots"))
                assertEquals(0,counted(name),count+" idle islands: "+name+" over 10,000 ticks");
            assertTrue(dispatch.submitted.isEmpty());assertEquals(Long.MAX_VALUE,coordinator.nextDue());
            for(var snapshot:coordinator.snapshots())assertEquals(new IslandClock.Snapshot(10_000,0,0,100),snapshot.clock(),"online time still accrues");
            // One transient island beside the idle ones: its visits do not depend on how many idle ones exist.
            coordinator.register(island(count+1L,0,0,Map.of()),MODEL);count();
            for(int tick=0;tick<2_000;tick++){coordinator.tick();dispatch.finishAll();}
            assertEquals(20,counted("solvesDispatched"));assertEquals(2_000,coordinator.snapshot(count+1L).clock().committedTick());
            long visits=counted("islandVisits");if(active<0)active=visits;
            assertEquals(active,visits,"the transient island's scheduling work must not scale with "+count+" idle islands");
        }
    }

    /**
     * Plan section 5 item 1: 1, 100 and 1,000 certified islands with no viewers and no events cost nothing over
     * 10,000 ticks - no island visit, readiness pump, solve, deadline, snapshot or materialisation, and no heap
     * entry - while online time accrues; a read materialises only the island read; and a transient island
     * beside them does the same scheduling work at every count.
     */
    @Test void certifiedIslandsCostNothingPerTickAtOneHundredOrAThousand() {
        long active=-1;
        for(int count:new int[]{1,100,1000}) {
            var dispatch=new Dispatch();
            var coordinator=new IslandCoordinator(dispatch,changed->{},()->0,new IslandCoordinator.Settings(2_000_000_000L,1_500_000_000L,64,false,100,CertificatePolicy.defaults()));
            dispatch.coordinator=coordinator;
            for(long id=1;id<=count;id++)coordinator.register(island(id,0,0,Map.of()),MODEL);
            int tick=0;
            for(;tick<1_000&&coordinator.observe().stream().anyMatch(s->s.certificate().isEmpty());tick++){coordinator.tick();if(!dispatch.attempts.isEmpty())dispatch.finishAll();}
            for(var snapshot:coordinator.observe())assertEquals(0.0,snapshot.certificate().orElseThrow().drift(),"island "+snapshot.id());
            assertEquals(0,coordinator.readyCount());assertEquals(0,coordinator.pendingCount());assertEquals(2L*count,dispatch.submitted.size(),"two exact-zero intervals each");
            count();
            for(int idle=0;idle<10_000;idle++){coordinator.tick();tick++;if(!dispatch.attempts.isEmpty())dispatch.finishAll();}
            for(var name:List.of("islandVisits","readinessPumps","solvesDispatched","deadlinesFired","islandSnapshots","topologySnapshots","materialisations","certificateWakes"))
                assertEquals(0,counted(name),count+" certified islands: "+name+" over 10,000 ticks");
            assertEquals(Long.MAX_VALUE,coordinator.nextDue());assertEquals(2L*count,dispatch.submitted.size());
            final long now=tick;
            var read=coordinator.snapshot(1);assertEquals(now,read.clock().onlineTick());assertEquals(now,read.clock().committedTick(),"a read materialises the island to now");
            assertEquals(1,counted("materialisations"),"and only the island read");
            assertTrue(coordinator.observe().stream().filter(s->s.id()!=1).allMatch(s->s.clock().committedTick()<now-9_000),"the others were not advanced");
            // One transient island beside them: a generator filling a thousand-cubic-metre tank never certifies here.
            var generator=new PassiveNetwork.Reservoir(10_000_001,0,MODEL.initialNitrogenCharge(1,298.15,150000,()->{}),PassiveNetwork.NodeKind.GENERATOR);
            var tank=new PassiveNetwork.Reservoir(10_000_002,0,MODEL.initialNitrogenCharge(1000,298.15,101325,()->{}));
            var filling=new PassiveNetwork(List.of(generator,tank),List.of(new PassiveNetwork.Pipe(10_000_003,0,1,new PipeResistance.Geometry(4,.05,.000045,0))));
            long id=count+1L;coordinator.register(new IslandCoordinator.Snapshot(id,0,filling,new IslandClock.Snapshot(0,0,0,100),Optional.empty(),"READY"),MODEL);
            count();
            for(int step=0;step<2_000;step++){coordinator.tick();if(!dispatch.attempts.isEmpty())dispatch.finishAll();}
            assertTrue(coordinator.observe(id).certificate().isEmpty(),"the filling tank must not certify");
            assertEquals(20,counted("solvesDispatched"));assertEquals(2_000,coordinator.observe(id).clock().committedTick());
            assertEquals(0,counted("materialisations"));
            long visits=counted("islandVisits");if(active<0)active=visits;
            assertEquals(active,visits,"the transient island's scheduling work must not scale with "+count+" certified islands");
        }
    }
    // The fair ready queue (merged from FairIslandQueueTest).
    @Test void catchupYieldsToAnotherReadyOwnerAndUnavailableOwnersDoNotWasteWorkers() {
        var queue=new IslandScheduler.FairQueue();queue.register(1);queue.register(2);queue.register(3);queue.register(1);
        assertEquals(3,queue.size());assertEquals(1,queue.nextReady(id->id!=2).orElseThrow());
        assertEquals(3,queue.nextReady(id->id!=2).orElseThrow());assertEquals(2,queue.nextReady(id->true).orElseThrow());
        assertEquals(1,queue.nextReady(id->true).orElseThrow());queue.remove(2);assertEquals(2,queue.size());
        assertTrue(queue.nextReady(id->false).isEmpty());assertEquals(3,queue.nextReady(id->true).orElseThrow());
    }

    /** The queue as it was before owners were indexed by readiness: every owner rotates through one deque. */
    private static final class RotatingDeque {
        private final ArrayDeque<Long> order=new ArrayDeque<>(),newcomers=new ArrayDeque<>();
        private final Set<Long> registered=new HashSet<>();
        void register(long island){if(registered.add(island))newcomers.addLast(island);}
        void remove(long island){if(registered.remove(island)){order.remove(island);newcomers.remove(island);}}
        OptionalLong nextReady(java.util.function.LongPredicate ready) {
            for(var iterator=newcomers.iterator();iterator.hasNext();) {
                long island=iterator.next();if(ready.test(island)){iterator.remove();order.addLast(island);return OptionalLong.of(island);}
            }
            int candidates=order.size();
            for(int i=0;i<candidates;i++){long island=order.removeFirst();order.addLast(island);if(ready.test(island))return OptionalLong.of(island);}
            return OptionalLong.empty();
        }
    }

    /** Serving only indexed ready owners gives exactly the service sequence of rotating every owner. */
    @Test void theReadyIndexServesTheRotatingDequeOrderUnderArbitraryChurn() {
        var random=new Random(20260923);long served=0;
        for(int trial=0;trial<300;trial++) {
            var queue=new IslandScheduler.FairQueue();var reference=new RotatingDeque();var ready=new HashSet<Long>();var veto=new HashSet<Long>();
            int owners=2+random.nextInt(40);
            for(int step=0;step<600;step++) {
                long id=1+random.nextInt(owners);int op=random.nextInt(12);
                if(op<2){boolean isReady=random.nextBoolean();if(!reference.registered.contains(id)){queue.register(id,isReady);reference.register(id);if(isReady)ready.add(id);else ready.remove(id);}}
                else if(op==2){queue.remove(id);reference.remove(id);ready.remove(id);}
                else if(op<6){boolean isReady=random.nextBoolean();queue.setReady(id,isReady);if(reference.registered.contains(id)){if(isReady)ready.add(id);else ready.remove(id);}}
                else if(op==6){veto.clear();for(long owner=1;owner<=owners;owner++)if(random.nextInt(4)==0)veto.add(owner);}
                else {
                    var expected=reference.nextReady(owner->ready.contains(owner)&&!veto.contains(owner));
                    var actual=queue.nextReady(owner->!veto.contains(owner));
                    assertEquals(expected,actual,"trial "+trial+" step "+step);if(actual.isPresent())served++;
                }
                assertEquals(reference.registered.size(),queue.size());assertEquals(ready.size(),queue.readyCount());
            }
        }
        assertTrue(served>50_000,"the comparison must exercise real service, served "+served);
    }

    /** Many newcomers served while the position is mid-cycle exhaust the key gap there and force a re-key. */
    @Test void reKeyingAnExhaustedGapKeepsTheServiceOrder() {
        var queue=new IslandScheduler.FairQueue();var reference=new RotatingDeque();
        for(long id=1;id<=10;id++){queue.register(id);reference.register(id);}
        for(int i=0;i<15;i++)assertEquals(reference.nextReady(id->true),queue.nextReady(id->true));
        for(long id=11;id<=110;id++){queue.register(id);reference.register(id);assertEquals(reference.nextReady(owner->true),queue.nextReady(owner->true));}
        for(int i=0;i<500;i++)assertEquals(reference.nextReady(id->id%3!=0),queue.nextReady(id->id%3!=0));
    }
}
