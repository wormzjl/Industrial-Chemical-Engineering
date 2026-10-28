package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.runtime.BoundedCpuSolveService;
import com.wormzjl.icheme.runtime.ProcessSolveServices;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import java.util.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The topology event path of the world ({@link PhysicalRegistry}) with a real {@link IslandCoordinator}: placing,
 * removing or editing one device compiles and replaces only the islands it touches, a tick's events apply once, in
 * order, as one batch, and the cost of one placement does not grow with the world. The world is WP5's rest lines -
 * tank, three pipes, tank - placed with the world's placement defaults (FluidWorldAuthority.place).
 * See {@code documentation/fluid-followups/FLUID_PLACEMENT_REVIEW.md}.
 */
class PhysicalRegistryTest {
    private static final String DIM="minecraft:overworld";
    private static final int Y=64;
    private final FluidThermodynamics model=FluidTestSupport.networkModel();
    private final MaterialCatalog catalog=MaterialCatalog.bundled();

    @AfterEach void counters(){FluidRuntimeDiagnostics.ENABLED=false;FluidRuntimeDiagnostics.reset();}

    /** The event-path world with this test's placement records and batch helpers. {@code workers} is 0 (no
     * solve) or 1 (a synchronous worker, as FluidPumpedFillLineTest, on the wall clock: rest lines solve in milliseconds). */
    private final class World extends EventPathWorld {
        World(int workers){super(workers);}
        WorldTopologyLedger.Registration record(long id,int x,int z,Kind kind,PhysicalFluidTopology.Direction facing) {
            var control=kind==Kind.PUMP?new FlowControl.Pump(.01,500000):new FlowControl.Passive();
            var device=new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position(DIM,x,Y,z),com.wormzjl.icheme.fluid.support.FluidTestSupport.mechanical(kind),facing,new PipeResistance.Geometry(1,.05,.000045,0),control);
            var spec=kind==Kind.GENERATOR?FluidDeviceSpec.water(catalog):FluidDeviceSpec.nitrogen(catalog);
            if(kind==Kind.RESERVOIR)spec=new FluidDeviceSpec(1,298.15,101325,spec.composition());
            return new WorldTopologyLedger.Registration(device,spec,0);
        }
        /** Queues a placement, as FluidWorldAuthority.place does; nothing applies until {@link #apply()}. */
        long queue(int x,int z,Kind kind) {
            assertTrue(registry.at(new PhysicalFluidTopology.Position(DIM,x,Y,z)).isEmpty(),"occupied");
            long id=ledger.nextIdentity();registry.submit(List.of(new WorldTopologyLedger.Edit(id,record(id,x,z,kind,PhysicalFluidTopology.Direction.EAST))),id+1,null);return id;
        }
        WorldTopologyLedger.Event queueRemove(long id){return registry.submit(List.of(new WorldTopologyLedger.Edit(id,null)),ledger.nextIdentity(),null);}
        WorldTopologyLedger.Event queueFacing(long id,PhysicalFluidTopology.Direction facing) {
            var old=registry.registrations().get(id);var d=old.device();
            return registry.submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(new PhysicalFluidTopology.Device(d.id(),d.position(),d.kind(),facing,d.geometry(),d.control()),old.spec(),old.revision()+1))),ledger.nextIdentity(),null);
        }
        void apply(){registry.applyPending();}
        long place(int x,int z,Kind kind){long id=queue(x,z,kind);apply();return id;}
        void remove(long id){queueRemove(id);apply();}
        /** Rest line n of a grid of 16 columns 6 blocks apart and rows 2 blocks apart (WP5's rest1000 layout); returns its ids. */
        long[] queueLine(int n){int x=16+6*(n%16),z=16+2*(n/16);long[] ids=new long[5];String layout="RPPPR";for(int i=0;i<5;i++)ids[i]=queue(x+i,z,layout.charAt(i)=='R'?Kind.RESERVOIR:Kind.PIPE);return ids;}
        void lines(int count){for(int n=0;n<count;n++){queueLine(n);if(registry.unapplied()>=FluidWorldAuthority.APPLY_BATCH)apply();}apply();}
        long owner(long device){return Objects.requireNonNull(registry.owner(device),"device "+device+" has no island");}
        IslandCoordinator.Snapshot island(long device){return coordinator.observe(owner(device));}
        /** Online ticks as the world runs them: the epoch advances, due deadlines fire, admitted jobs run, and the pump runs. */
        void run(long ticks) {
            for(long tick=0;tick<ticks;tick++) {
                ledger.tick();coordinator.tick();apply();coordinator.pumpIfUseful();
                for(long request:List.copyOf(attempts.keySet())) {
                    var attempt=attempts.remove(request);var command=commands.remove(request);
                    var result=(ProcessSolveServices.FluidIslandSolveResult)command.solve(FluidTestSupport.NEVER);
                    coordinator.completed(attempt,Optional.of(result));
                }
                coordinator.pump();
            }
        }
        long counter(String name){return FluidRuntimeDiagnostics.sample().get(name);}
    }
    /** Identities differ between two worlds that allocate island identities at different times; positions do not. */
    private static PhysicalFluidTopology.Position at(World w,long id){return w.ledger.active().get(id).device().position();}
    private static Map<PhysicalFluidTopology.Position,List<Object>> registry(World w) {
        var result=new HashMap<PhysicalFluidTopology.Position,List<Object>>();
        for(var r:w.ledger.active().values()){var d=r.device();result.put(d.position(),List.of(d.kind(),d.facing(),d.geometry(),d.control(),r.spec(),r.revision(),String.valueOf(w.registry.diagnostic(d.id())),w.registry.pipeViews(d.id()).size()));}
        return result;
    }
    private static Map<Set<PhysicalFluidTopology.Position>,IslandCoordinator.Snapshot> islands(World w) {
        var result=new HashMap<Set<PhysicalFluidTopology.Position>,IslandCoordinator.Snapshot>();
        for(var island:w.coordinator.observe()){var positions=new HashSet<PhysicalFluidTopology.Position>();for(long id:w.registry.members(island.id()))positions.add(at(w,id));result.put(Set.copyOf(positions),island);}
        return result;
    }

    @Test void anEventTouchingOneIslandCompilesAndReplacesOnlyThatIsland() {
        var w=new World(0);w.lines(50);FluidRuntimeDiagnostics.reset();FluidRuntimeDiagnostics.ENABLED=true;
        long tank=w.registry.at(new PhysicalFluidTopology.Position(DIM,16,Y,16)).orElseThrow().device().id();
        var before=new HashMap<Long,IslandCoordinator.Snapshot>();for(var island:w.coordinator.observe())before.put(island.id(),island);
        long old=w.owner(tank);
        // A pipe beside the first line's first pipe, on the side no other line is: a dead-end branch of that line.
        long pipe=w.queue(17,15,Kind.PIPE);var event=w.ledger.events().getLast();
        assertEquals(2,event.touched().size(),"the touched set is the neighbourhood, not the island: "+event.touched());
        w.apply();
        assertEquals(1,w.counter("topologyBatches"));assertEquals(1,w.counter("topologyEvents"));
        assertEquals(6,w.counter("compiledDevices"),"only the touched line and its new pipe are compiled");
        long replacement=w.owner(tank);assertNotEquals(old,replacement,"a replaced island gets a new identity");
        assertNull(w.registry.owner(pipe),"a dead-end branch belongs to no island");
        assertEquals("NO FLOW: dead-end pipe",w.registry.diagnostic(pipe));
        assertEquals(before.get(old).revision()+1,w.coordinator.observe(replacement).revision());
        for(var island:w.coordinator.observe())if(island.id()!=replacement) {
            var was=before.get(island.id());assertNotNull(was,"no other island was replaced");
            assertEquals(was.payloadGeneration(),island.payloadGeneration(),"an untouched island keeps its payload");
        }
        assertFalse(w.coordinator.observe().stream().anyMatch(s->s.id()==old),"the replaced island is gone");
    }

    /** Owner decision 2026-10-15: an event fences only the islands it shares a flow path with. Two lines whose end tanks
     * touch (two tanks never join): removing one tank fences its own line, not the line beside it. */
    @Test void anEventDoesNotFenceAnAdjacentIslandItIsNotJoinedTo() {
        var w=new World(0);
        long tankA=w.queue(0,0,Kind.RESERVOIR);w.queue(1,0,Kind.PIPE);long pipeA=w.queue(2,0,Kind.PIPE);long endA=w.queue(3,0,Kind.RESERVOIR);
        long endB=w.queue(4,0,Kind.RESERVOIR);w.queue(5,0,Kind.PIPE);w.queue(6,0,Kind.PIPE);w.queue(7,0,Kind.RESERVOIR);w.apply();
        long lineA=w.owner(pipeA),lineB=w.owner(endB);assertNotEquals(lineA,lineB);assertEquals(lineA,w.owner(tankA));
        var event=w.queueRemove(endA);
        assertEquals(Set.of(lineA),w.coordinator.fencedIslands(event.id()),"the neighbouring line is not fenced");
        w.apply();assertNull(w.registry.owner(endA));assertEquals(lineB,w.owner(endB),"the untouched line keeps its island");
    }
    /** The player's report "breaking a block does not remove it from the network": a network whose every solve holds
     * (here a worker that refuses its island) applies a pipe's removal within two ticks, with its tanks' inventory intact: the
     * fence gives the island one fresh attempt (the edit may free it), that attempt holds, and the island accepts the event. */
    @Test void aPipeBrokenInAHeldNetworkLeavesItAtOnce() {
        var w=new EventPathWorld();
        w.place(0,Y,0,Kind.RESERVOIR,PhysicalFluidTopology.Direction.EAST);w.place(1,Y,0,Kind.PIPE,PhysicalFluidTopology.Direction.EAST);
        long pipe=w.place(2,Y,0,Kind.PIPE,PhysicalFluidTopology.Direction.EAST);w.place(3,Y,0,Kind.RESERVOIR,PhysicalFluidTopology.Direction.EAST);
        w.runTo(200);long island=Objects.requireNonNull(w.registry.owner(pipe));
        Runnable heldTick=()->{
            w.ledger.tick();w.coordinator.tick();if(w.ledger.hasPendingEvents()){w.registry.applyPending();w.coordinator.pumpIfUseful();}
            for(long request:List.copyOf(w.attempts.keySet())){var attempt=w.attempts.remove(request);var command=w.commands.remove(request);
                var result=attempt.islandId()==island?new ProcessSolveServices.FluidIslandSolveResult(Optional.empty(),"HELD: test refusal",10)
                        :(ProcessSolveServices.FluidIslandSolveResult)command.solve(FluidTestSupport.NEVER);
                w.coordinator.completed(attempt,Optional.of(result));}
            w.coordinator.pump();
        };
        for(int tick=0;tick<400;tick++)heldTick.run();
        assertTrue(w.coordinator.observe(island).status().startsWith("HELD"),w.coordinator.observe(island).status());
        double mass=w.coordinator.observe(island).graph().reservoirs().stream().filter(n->n.kind()==PassiveNetwork.NodeKind.RESERVOIR).mapToDouble(n->n.state().mass()).sum();
        w.registry.submit(List.of(new WorldTopologyLedger.Edit(pipe,null)),w.ledger.nextIdentity(),null);
        heldTick.run();heldTick.run();
        assertTrue(w.ledger.events().isEmpty(),"the removal applied");assertNull(w.registry.owner(pipe));
        assertTrue(w.coordinator.snapshots().stream().noneMatch(s->s.id()==island),"the held island was replaced");
        double after=w.coordinator.snapshots().stream().flatMap(s->s.graph().reservoirs().stream()).filter(n->n.kind()==PassiveNetwork.NodeKind.RESERVOIR).mapToDouble(n->n.state().mass()).sum();
        assertEquals(mass,after,0,"the tanks keep their inventory");
    }
    @Test void mergesAndSplitsProduceTheRightIslandsAndRevisions() {
        var w=new World(0);w.lines(2);
        long leftTank=w.registry.at(new PhysicalFluidTopology.Position(DIM,20,Y,16)).orElseThrow().device().id();
        long rightTank=w.registry.at(new PhysicalFluidTopology.Position(DIM,22,Y,16)).orElseThrow().device().id();
        long left=w.owner(leftTank),right=w.owner(rightTank);assertNotEquals(left,right);
        long leftRevision=w.coordinator.observe(left).revision(),rightRevision=w.coordinator.observe(right).revision();
        var inventories=new HashMap<Long,double[]>();for(var island:w.coordinator.observe())for(var n:island.graph().reservoirs())inventories.put(n.id(),n.inventory().moles());
        // A pipe between the two lines joins them into one island.
        long bridge=w.place(21,16,Kind.PIPE);
        long merged=w.owner(bridge);assertEquals(merged,w.owner(leftTank));assertEquals(merged,w.owner(rightTank));
        assertEquals(1,w.coordinator.observe().size());assertEquals(11,w.registry.members(merged).size());
        assertEquals(Math.max(leftRevision,rightRevision)+1,w.coordinator.observe(merged).revision());
        assertTrue(merged>left&&merged>right,"new identities come from the identity sequence");
        for(var n:w.coordinator.observe(merged).graph().reservoirs())if(n.kind()==PassiveNetwork.NodeKind.RESERVOIR)assertArrayEquals(inventories.get(n.id()),n.inventory().moles(),"a merge moves no stock");
        // Breaking it splits them again, into two new islands.
        w.remove(bridge);
        long a=w.owner(leftTank),b=w.owner(rightTank);assertNotEquals(a,b);assertTrue(a>merged&&b>merged);
        assertEquals(Math.max(leftRevision,rightRevision)+2,w.coordinator.observe(a).revision(),"a split island's revision follows the merged one");
        assertEquals(Math.max(leftRevision,rightRevision)+2,w.coordinator.observe(b).revision());
        assertEquals(2,w.coordinator.observe().size());assertEquals(5,w.registry.members(a).size());assertEquals(5,w.registry.members(b).size());
        assertTrue(w.committedRemovals.getLast().contains(bridge),"the host learns which device left the registry");
    }

    /**
     * One hundred and four events queued in one tick - twenty lines, a tank placed and broken again, a pipe placed and
     * turned - apply once, in queue order, as one batch, and leave the world exactly as applying them one by one does:
     * the same registry, islands, compiled diagnostics and construction accounting, bit for bit. Only island identities
     * and revisions differ: applied one by one, the first line's island was replaced three times within the tick.
     */
    @Test void aTicksEventsApplyOnceInOrderAsOneBatch() {
        var batched=new World(0);var sequential=new World(0);
        FluidRuntimeDiagnostics.reset();FluidRuntimeDiagnostics.ENABLED=true;
        for(var w:List.of(batched,sequential)) {
            boolean each=w==sequential;
            for(int n=0;n<20;n++){w.queueLine(n);if(each)w.apply();}
            long tank=w.queue(10,10,Kind.RESERVOIR);if(each)w.apply();w.queueRemove(tank);if(each)w.apply();
            long pipe=w.queue(17,15,Kind.PIPE);if(each)w.apply();w.queueFacing(pipe,PhysicalFluidTopology.Direction.NORTH);if(each)w.apply();
            if(!each) {
                assertEquals(104,w.ledger.events().size());assertTrue(w.coordinator.observe().isEmpty(),"nothing applies before the batch");
                long ticks=w.ledger.events().stream().mapToLong(WorldTopologyLedger.Event::tick).distinct().count();assertEquals(1,ticks);
                FluidRuntimeDiagnostics.reset();w.apply();
                assertEquals(1,w.counter("topologyBatches"));assertEquals(104,w.counter("topologyEvents"));
                assertEquals(101,w.counter("compiledDevices"),"every component once: 20 lines of 5 and the branch pipe");
                FluidRuntimeDiagnostics.reset();w.apply();assertEquals(0,w.counter("topologyBatches"),"exactly once");
            }
            assertTrue(w.ledger.events().isEmpty());
        }
        assertEquals(registry(sequential),registry(batched),"the same registrations, diagnostics and views at every position");
        var sequentialIslands=islands(sequential);var batchedIslands=islands(batched);assertEquals(sequentialIslands.keySet(),batchedIslands.keySet(),"the same islands");
        var s=sequential.ledger.snapshot();var b=batched.ledger.snapshot();
        assertArrayEquals(s.constructed().moles(),b.constructed().moles());assertEquals(s.constructed().totalEnergy(),b.constructed().totalEnergy());
        assertArrayEquals(s.destroyed().moles(),b.destroyed().moles());assertEquals(s.destroyed().totalEnergy(),b.destroyed().totalEnergy());
        // Decision D54: a tank is placed empty, so the one placed and broken in the tick is booked built and destroyed holding
        // nothing.
        assertEquals(0,s.destroyed().totalEnergy(),"the tank placed and broken in the tick held nothing");assertEquals(0,Arrays.stream(s.destroyed().moles()).sum());
        for(var entry:sequentialIslands.entrySet()) {
            var graph=entry.getValue().graph();var twin=batchedIslands.get(entry.getKey()).graph();
            assertEquals(graph.reservoirs().size(),twin.reservoirs().size());assertEquals(graph.pipes().size(),twin.pipes().size());
            for(int i=0;i<graph.reservoirs().size();i++){var x=graph.reservoirs().get(i);var y=twin.reservoirs().get(i);
                assertEquals(at(sequential,x.id()),at(batched,y.id()));assertEquals(x.kind(),y.kind());assertEquals(x.elevation(),y.elevation());assertArrayEquals(x.inventory().moles(),y.inventory().moles());assertEquals(x.inventory().internalEnergy(),y.inventory().internalEnergy());}
            for(int i=0;i<graph.pipes().size();i++){var x=graph.pipes().get(i);var y=twin.pipes().get(i);assertEquals(List.of(x.first(),x.second(),x.sections(),x.control()),List.of(y.first(),y.second(),y.sections(),y.control()));}
        }
    }

    /** Removing a line's middle pipe and turning another follow the same path: the line's island alone is compiled and replaced. */
    @Test void removalAndEditFollowTheSamePath() {
        var w=new World(0);w.lines(30);FluidRuntimeDiagnostics.reset();FluidRuntimeDiagnostics.ENABLED=true;
        long tank=w.registry.at(new PhysicalFluidTopology.Position(DIM,16,Y,16)).orElseThrow().device().id();
        long middle=w.registry.at(new PhysicalFluidTopology.Position(DIM,18,Y,16)).orElseThrow().device().id();
        long other=w.registry.at(new PhysicalFluidTopology.Position(DIM,22,Y,16)).orElseThrow().device().id();long untouched=w.owner(other);
        long before=w.owner(tank);w.remove(middle);
        assertEquals(4,w.counter("compiledDevices"),"the four devices left of the broken line");
        assertNotEquals(before,w.owner(tank));assertEquals(untouched,w.owner(other));
        assertNull(w.registry.owner(middle));assertEquals(1,w.registry.members(w.owner(tank)).size(),"a tank with a dead-end pipe is an island of one");
        long pipe=w.registry.at(new PhysicalFluidTopology.Position(DIM,23,Y,16)).orElseThrow().device().id();
        FluidRuntimeDiagnostics.reset();long revision=w.island(pipe).revision();
        w.queueFacing(pipe,PhysicalFluidTopology.Direction.NORTH);w.apply();
        assertEquals(5,w.counter("compiledDevices"));assertNotEquals(untouched,w.owner(other),"an edit replaces its island");
        assertEquals(revision+1,w.island(pipe).revision());assertEquals(1,w.registry.registrations().get(pipe).revision());
    }

    /**
     * A device placed at the end of a dead-end branch reaches the island through devices no island owns: its touched set
     * follows the branch to the island and fences it, so the tank joins that island at the event's tick.
     */
    @Test void anEventReachesAnIslandThroughAnOwnerlessBranch() {
        var w=new World(0);w.lines(1);
        long tank=w.registry.at(new PhysicalFluidTopology.Position(DIM,16,Y,16)).orElseThrow().device().id();long island=w.owner(tank);
        for(int z=15;z>=12;z--)w.place(17,z,Kind.PIPE);
        assertNotEquals(island,w.owner(tank),"a branch touches the line's island");for(int z=15;z>=12;z--)assertEquals("NO FLOW: dead-end pipe",w.registry.diagnostic(w.registry.at(new PhysicalFluidTopology.Position(DIM,17,Y,z)).orElseThrow().device().id()));
        long end=w.queue(17,11,Kind.RESERVOIR);
        var event=w.ledger.events().getLast();assertTrue(event.touched().contains(tank)||w.coordinator.fencedIslands(event.id()).contains(w.owner(tank)),"the island is fenced");
        w.apply();
        assertEquals(w.owner(tank),w.owner(end),"the tank joins the line through the branch");assertEquals(10,w.registry.members(w.owner(end)).size());
    }

    /**
     * An event whose island is held stays queued at its tick; a later, independent event applies without it, and a
     * later event that touches the held event's device waits for it. Once the hold ends, both apply at their own ticks.
     */
    @Test void aHeldEventKeepsItsTickAndOrdersTheEventsThatDependOnIt() {
        var w=new World(1);w.lines(1);
        long tank=w.registry.at(new PhysicalFluidTopology.Position(DIM,16,Y,16)).orElseThrow().device().id();long island=w.owner(tank);
        var hold=UUID.randomUUID();w.coordinator.fence(hold,w.coordinator.observe(island).clock().committedTick(),Set.of(island));
        w.run(5);
        long pipe=w.queue(17,15,Kind.PIPE);long queuedAt=w.ledger.onlineTick();w.apply();
        assertEquals(1,w.ledger.events().size(),"the edit waits at the held island's horizon");assertNull(w.registry.owner(pipe));
        long next=w.queue(17,14,Kind.PIPE);w.run(3);
        long independent=w.place(100,100,Kind.RESERVOIR);
        assertNotNull(w.registry.owner(independent),"an unrelated placement is not blocked");
        assertEquals(2,w.ledger.events().size(),"the dependent placement waits behind the held one");assertNull(w.registry.owner(next));
        assertEquals(queuedAt,w.ledger.events().getFirst().tick(),"the held event keeps its tick");
        w.coordinator.releaseFence(hold,Set.of(island));w.run(40);
        assertTrue(w.ledger.events().isEmpty(),"both applied once the hold ended");
        assertEquals("NO FLOW: dead-end pipe",w.registry.diagnostic(next));
    }

    /** Certified islands no event touches stay certified, unread and with their saved payloads; the touched one is replaced. */
    @Test void certifiedIslandsUntouchedByAnEventStayCertified() {
        var w=new World(1);w.lines(8);w.run(2_000);
        var certified=w.coordinator.observe();
        assertTrue(certified.stream().allMatch(s->s.certificate().isPresent()),"every rest line certifies: "+certified.stream().map(IslandCoordinator.Snapshot::status).toList());
        FluidRuntimeDiagnostics.reset();FluidRuntimeDiagnostics.ENABLED=true;
        long tank=w.registry.at(new PhysicalFluidTopology.Position(DIM,16,Y,16)).orElseThrow().device().id();long touched=w.owner(tank);
        w.place(17,15,Kind.PIPE);
        assertEquals(1,w.counter("materialisations"),"only the touched island is brought to the event's tick");
        for(var was:certified)if(was.id()!=touched) {
            var now=w.coordinator.observe(was.id());
            assertEquals(was.certificate(),now.certificate());assertEquals(was.payloadGeneration(),now.payloadGeneration());
            assertEquals(was.clock(),now.clock(),"an untouched island is not even read");
        }
        var replacement=w.island(tank);assertTrue(replacement.certificate().isEmpty(),"a topology change ends the touched island's certificate");
    }

    /**
     * BE_INTEGRATOR_PLAN.md WP3 (b): every junction's buffer holds m_J = tau x the largest velocity-cap mass flow of its
     * connections at its seed state (tau = 0.05 s, PassiveStepSolver.HOLDUP_TAU). The edit resets a junction's composition
     * to its seed state (the accepted behaviour, review 9.3). Owner decision D46: the buffer is the state of the
     * junction's relaxation row, not inventory, so the world ledger books no junction, neither the minted ones when the
     * island is compiled nor, at a topology edit, the replaced island's solved buffers or the recompile's fresh ones.
     * Restated by D46: before, every minted junction was booked as constructed and every replaced one as destroyed.
     */
    @Test void junctionBuffersAreSizedAndRemintedAtATopologyEditAndNothingIsBooked() {
        var w=new World(1);int count=model.components().size();var weights=model.molecularWeights();
        int methane=catalog.requirePackage("icheme:tjl20_methane_nitrogen").components().indexOf("Methane");
        int nitrogen=model.components().indexOf("Nitrogen");
        double[] methaneFeed=new double[count],nitrogenFeed=new double[count];methaneFeed[methane]=1;nitrogenFeed[nitrogen]=1;
        // A methane generator at 150 kPa filling two tanks charged with nitrogen at 1 atm: a line of three pipes with a
        // branch off the middle one to the second tank (a series of pipes compiles to one connection; a branch to a third
        // port compiles a junction). The island flows and its junction changes composition.
        long tank=queueSpec(w,16,16,Kind.GENERATOR,new FluidDeviceSpec(1,298.15,150000,methaneFeed));
        for(int x=17;x<=19;x++)queueSpec(w,x,16,Kind.PIPE,new FluidDeviceSpec(1,298.15,101325,nitrogenFeed));
        queueSpec(w,18,17,Kind.PIPE,new FluidDeviceSpec(1,298.15,101325,nitrogenFeed));
        queueSpec(w,18,18,Kind.RESERVOIR,new FluidDeviceSpec(1,298.15,101325,nitrogenFeed));
        long other=queueSpec(w,20,16,Kind.RESERVOIR,new FluidDeviceSpec(1,298.15,101325,nitrogenFeed));
        var empty=WorldTopologyLedger.MaterialTotal.empty(count);
        var start=w.ledger.snapshot();w.apply();var placed=w.ledger.snapshot();
        var graph=w.island(tank).graph();var junctions=graph.reservoirs().stream().filter(PassiveNetwork.Reservoir::junction).toList();
        assertFalse(junctions.isEmpty(),"the branch compiles a junction");
        for(var junction:junctions) {
            var state=junction.state();double rho=state.mass()/state.volume(),capacity=0;int index=graph.reservoirs().indexOf(junction);
            for(var pipe:graph.pipes())if(pipe.first()==index||pipe.second()==index)capacity=Math.max(capacity,rho*pipe.minimumArea()*model.velocityLimit(state));
            double minted=0;var n=junction.inventory().moles();for(int c=0;c<count;c++)minted+=n[c]*weights[c];
            System.out.println("junction "+junction.id()+": m_J "+minted+" kg, 0.05 s x cap flow "+capacity+" kg/s");
            assertEquals(.05*capacity,minted,1e-12*minted,"junction "+junction.id()+"'s buffer holds tau x its largest cap flow");
        }
        var tanks=graph.reservoirs().stream().filter(n->n.kind()==PassiveNetwork.NodeKind.RESERVOIR).toList();
        assertEquals(2,tanks.size());
        assertBooked(empty.plus(tanks,model.components(),weights),placed.constructed(),start.constructed(),"the placement books both tanks as constructed, and no junction (D46)");
        assertBooked(empty,placed.destroyed(),start.destroyed(),"a placement into an empty world destroys nothing");
        // Ten intervals of flow move the junctions' composition away from the mint.
        w.run(1_000);
        long owner=w.owner(tank);var solved=w.coordinator.snapshot(owner);assertEquals(1_000,solved.clock().committedTick());
        var old=solved.graph().reservoirs().stream().filter(PassiveNetwork.Reservoir::junction).toList();
        var before=w.ledger.snapshot();
        // The edit: a stub off the first pipe recompiles the island.
        w.place(17,15,Kind.PIPE);
        var after=w.ledger.snapshot();assertNotEquals(owner,w.owner(tank),"the edit replaced the island");
        var fresh=w.island(tank).graph().reservoirs().stream().filter(PassiveNetwork.Reservoir::junction).toList();
        assertFalse(fresh.isEmpty(),"the recompiled island mints its junctions again");
        assertBooked(empty,after.destroyed(),before.destroyed(),"the replaced island's solved junction buffers are not booked (D46)");
        assertBooked(empty,after.constructed(),before.constructed(),"the recompile's minted junctions are not booked (D46), and no tank was placed");
        // Composition resets at an edit: every fresh junction holds its seed state's composition, whatever the old one held.
        double moved=0;
        for(var junction:fresh) {
            var seed=com.wormzjl.icheme.science.fluid.solver.PhaseLayout.totalAmounts(junction.state());var n=junction.inventory().moles();
            double seedTotal=0,total=0;for(int c=0;c<count;c++){seedTotal+=seed[c];total+=n[c];}
            assertEquals(seed[methane]/seedTotal,n[methane]/total,1e-12,"junction "+junction.id()+" is minted at its seed composition");
        }
        for(var junction:old){var n=junction.inventory().moles();double total=0;for(double v:n)total+=v;moved=Math.max(moved,n[methane]/total);}
        System.out.println("remint: old junction methane fractions up to "+moved+"; fresh junctions at their seed composition");
        assertTrue(moved>1e-3,"the solved junctions had taken up methane, which the remint discards with the buffer (D46: not booked)");
    }
    private long queueSpec(World w,int x,int z,Kind kind,FluidDeviceSpec spec) {
        long id=w.ledger.nextIdentity();
        var device=new PhysicalFluidTopology.Device(id,new PhysicalFluidTopology.Position(DIM,x,Y,z),com.wormzjl.icheme.fluid.support.FluidTestSupport.mechanical(kind),PhysicalFluidTopology.Direction.EAST,new PipeResistance.Geometry(1,.05,.000045,0),new FlowControl.Passive());
        w.registry.submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(device,spec,0))),id+1,null);return id;
    }
    /** What the ledger booked between two snapshots equals {@code expected}, to roundoff of the cumulative totals. */
    private static void assertBooked(WorldTopologyLedger.MaterialTotal expected,WorldTopologyLedger.MaterialTotal later,WorldTopologyLedger.MaterialTotal earlier,String what) {
        var n=expected.moles();var a=later.moles();var b=earlier.moles();
        for(int c=0;c<n.length;c++)assertEquals(n[c],a[c]-b[c],1e-12*Math.max(1,Math.abs(a[c])),what+": component "+c);
        assertEquals(expected.totalEnergy(),later.totalEnergy()-earlier.totalEnergy(),1e-12*Math.max(1,Math.abs(later.totalEnergy())),what+": energy");
    }

    /**
     * The scaling test on the synchronous rig: one placement - a pipe extending a line, which recompiles and replaces that
     * line's island - in worlds of 500, 2,000 and 5,000 devices costs the same. The old event path compiled the whole
     * registry: 1.6, 5.2 and 19.3 ms (FLUID_PLACEMENT_REVIEW.md section 1). Counted, it compiles six devices at every size.
     */
    @Test void placementCostIsFlatInWorldSize() {
        var w=new World(0);int lines=0;
        FluidRuntimeDiagnostics.ENABLED=true;
        for(int size:new int[]{500,2_000,5_000}) {
            for(;lines*5<size;lines++){w.queueLine(lines);if(w.registry.unapplied()>=FluidWorldAuthority.APPLY_BATCH)w.apply();}w.apply();
            assertEquals(size,w.ledger.active().size());
            for(int i=0;i<80;i++) {
                FluidRuntimeDiagnostics.reset();long pipe=w.place(17,15,Kind.PIPE);
                assertEquals(6,w.counter("compiledDevices"),"one placement compiles its own island at "+size+" devices");
                w.remove(pipe);
            }
        }
    }
}
