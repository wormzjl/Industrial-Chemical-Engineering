package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.fluid.support.FluidTestSupport;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The inline pipe fittings (check, shutoff and pressure valves, filter, pump and compressor) on a fresh current-format
 * line: real registry and coordinator edits, the native constraints and models, no Minecraft client or compatibility
 * fixtures. Each fitting keeps the host pipe, its pending edits and the owned contents.
 */
class PipelineFittingTest {
    private final com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics model=FluidTestSupport.networkModel();
    private static final String DIM="minecraft:overworld";
    private static final Runnable NOOP=()->{};

    /** Two 1 m3 nitrogen reservoirs at {@code first} and {@code last} Pa joined by three pipes along {@code axis}; the fitting goes on device 3. */
    private EventPathWorld line(Direction axis,double first,double last) {
        var records=new LinkedHashMap<Long,WorldTopologyLedger.Registration>();var stock=new HashMap<Long,PassiveNetwork.Reservoir>();
        for(int i=0;i<5;i++) {
            long id=i+1;var kind=i==0||i==4?Kind.RESERVOIR:Kind.PIPE;var p=new Position(DIM,axis.x*i,64+axis.y*i,axis.z*i);
            var d=new Device(id,p,kind,axis,new PipeResistance.Geometry(1,.05,.000045,0),new FlowControl.Passive());
            var spec=FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG);records.put(id,new WorldTopologyLedger.Registration(d,spec,0));
            if(kind==Kind.RESERVOIR)stock.put(id,new PassiveNetwork.Reservoir(id,p.y(),model.initialNitrogenCharge(spec.volume(),298.15,i==0?first:last,NOOP)));
        }
        var compiled=PhysicalFluidTopology.compile(records.values().stream().map(WorldTopologyLedger.Registration::device).toList(),stock);
        assertEquals(1,compiled.islands().size());
        var island=new IslandCoordinator.Snapshot(10,0,compiled.islands().getFirst().graph(),new IslandClock.Snapshot(0,0,100,100),Optional.empty(),"READY");
        var empty=WorldTopologyLedger.Snapshot.empty(EventPathWorld.CATALOG);
        var total=WorldTopologyLedger.MaterialTotal.empty(model.components().size()).plus(stock.values(),model.components(),model.molecularWeights());
        return new EventPathWorld(0,new WorldTopologyLedger.Snapshot(0,11,records,List.of(),total,empty.destroyed(),empty.basis()),List.of(island));
    }
    private EventPathWorld line(Direction axis){return line(axis,350000,100000);}
    private static Device valve(EventPathWorld world){return world.registry.registrations().get(3L).device();}
    /** The island holding reservoir 1. */
    private static PassiveNetwork graph(EventPathWorld world){return world.coordinator.snapshots().stream().map(IslandCoordinator.Snapshot::graph).filter(g->g.reservoirs().stream().anyMatch(n->n.id()==1)).findFirst().orElseThrow();}
    private static Map<Long,PassiveNetwork.Inventory> stock(EventPathWorld world) {
        var out=new TreeMap<Long,PassiveNetwork.Inventory>();
        for(var island:world.coordinator.snapshots())for(var n:island.graph().reservoirs())if(n.kind()==PassiveNetwork.NodeKind.RESERVOIR)out.put(n.id(),n.inventory());
        return out;
    }
    private byte[] image(Device device) {
        var empty=WorldTopologyLedger.Snapshot.empty(EventPathWorld.CATALOG);
        var saved=new WorldTopologyLedger.Snapshot(0,device.id()+1,Map.of(device.id(),new WorldTopologyLedger.Registration(device,FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG),0)),List.of(),empty.constructed(),empty.destroyed(),empty.basis());
        return FluidCheckpointCodec.encodeTopology(saved);
    }

    // ---- Check valve ------------------------------------------------------------------------------------------------

    @Test void allSixDirectionsPassForwardAndBlockReverseWithoutChangingPipeOrOwnedContents() {
        for(var axis:Direction.values())for(boolean reverse:List.of(false,true)) {
            var world=line(axis,reverse?100000:200000,reverse?200000:100000);var before=stock(world);var original=valve(world);
            var unrestricted=new PassiveStepSolver(model).solve(graph(world),.1,NOOP);
            assertTrue(reverse?unrestricted.massFlows()[0]<0:unrestricted.massFlows()[0]>0,"baseline must actually permit both directions");
            assertTrue(world.registry.installPipeValve(3,axis,Cutoff.CHECK));
            assertFalse(world.registry.installPipeValve(3,axis,Cutoff.CHECK));
            world.registry.applyPending();var network=graph(world);
            assertEquals(before,stock(world));assertEquals(original.id(),valve(world).id());assertEquals(original.geometry(),valve(world).geometry());
            assertEquals(2,valve(world).contacts().size());
            for(var d:Direction.values())assertEquals(d==axis||d==opposite(axis),valve(world).mechanicallyConnects(d));
            assertTrue(network.pipes().stream().allMatch(p->p.forbiddenDirections()!=0));
            var result=new PassiveStepSolver(model).solve(network,.1,NOOP);
            for(double flow:result.massFlows())if(reverse)assertEquals(0,flow,1e-10);else assertTrue(flow>0);
            assertTrue(world.commands.isEmpty(),"install does not force a solve");
        }
    }

    @Test void reversingComposesWithPendingDiameterEditsAndRoundTripsTheCompleteCheckpoint() {
        var world=line(Direction.EAST,200000,100000);world.registry.installPipeValve(3,Direction.EAST,Cutoff.CHECK);world.registry.applyPending();
        var before=stock(world);world.registry.togglePipeCutoff(3);
        var record=world.registry.registrations().get(3L);var g=record.device().geometry();
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(3,new WorldTopologyLedger.Registration(record.device().withGeometry(new PipeResistance.Geometry(g.length(),.25,g.roughness(),g.minorLoss())),record.spec(),record.revision()+1))),world.ledger.nextIdentity(),null);
        world.registry.togglePipeCutoff(3);world.registry.togglePipeCutoff(3);
        assertEquals(Direction.WEST,valve(world).facing());assertEquals(.25,valve(world).geometry().diameter());
        assertEquals(Direction.EAST,world.ledger.active().get(3L).device().facing(),"committed direction changes only on the schedule");
        var checkpoint=new FluidCheckpointCodec.Checkpoint(world.coordinator.snapshots().stream().map(s->new FluidCheckpointCodec.IslandEntry(DIM,"icheme:tjl20_methane_nitrogen",s)).toList());
        var store=FluidCheckpointStore.memory();store.commit(store.prepare(checkpoint,Optional.of(world.ledger.snapshot()),world.ledger.onlineTick(),key->model));
        var image=store.image();var loaded=FluidCheckpointStore.memory(image).load(image.core(),key->model);var saved=loaded.world().orElseThrow();
        assertArrayEquals(FluidCheckpointCodec.encodeTopology(world.ledger.snapshot()),FluidCheckpointCodec.encodeTopology(saved));
        assertEquals(graph(world).pipes(),loaded.checkpoint().islands().getFirst().snapshot().graph().pipes(),"permanent scientific restrictions persist too");
        var reload=new EventPathWorld(0,saved,loaded.checkpoint().islands().stream().map(FluidCheckpointCodec.IslandEntry::snapshot).toList());
        reload.registry.applyPending();assertEquals(Direction.WEST,valve(reload).facing());assertEquals(before,stock(reload));
        for(double flow:new PassiveStepSolver(model).solve(graph(reload),.1,NOOP).massFlows())assertEquals(0,flow,1e-10);
        assertTrue(reload.commands.isEmpty());
    }

    @Test void reopeningSolidClosuresCannotOpenTheCheckValveAndInvalidCodesAreRejected() {
        var world=line(Direction.NORTH,200000,100000);world.registry.installPipeValve(3,Direction.NORTH,Cutoff.CHECK);world.registry.applyPending();var graph=graph(world);
        var closed=new PassiveNetwork(graph.reservoirs(),graph.pipes().stream().map(p->p.withBlockedDirections(3)).toList());
        var reopened=NetworkReduction.reopened(closed);
        for(int i=0;i<graph.pipes().size();i++) {
            var p=reopened.pipes().get(i);assertEquals(0,p.blockedDirections());assertEquals(graph.pipes().get(i).forbiddenDirections(),p.forbiddenDirections());
            assertTrue(p.blocked(-1));assertFalse(p.blocked(1));
        }
        assertThrows(IllegalArgumentException.class,()->graph.pipes().getFirst().withForbiddenDirections(4));
        assertThrows(IllegalArgumentException.class,()->Cutoff.fromCode(4));
        var d=new Device(20,new Position(DIM,10,64,0),Kind.VOID,Direction.EAST,new PipeResistance.Geometry(1,.05,.000045,0),new FlowControl.Passive());
        assertThrows(IllegalArgumentException.class,()->d.withCutoff(Direction.EAST,Cutoff.CHECK));
        var bad=CheckpointImages.renamed(image(valve(world)),"CHECK","CHECX");
        assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decodeTopology(bad,0,key->model)).getMessage().contains("unknown Cutoff CHECX"));
    }

    @Test void checkNodesRetainPressureAndRestrictionsSurviveDrivenEndSwapping() {
        for(var pumpFacing:List.of(Direction.EAST,Direction.WEST)) {
            var records=new ArrayList<Device>();var stocks=new HashMap<Long,PassiveNetwork.Reservoir>();
            for(int x=0;x<7;x++) {
                long id=x+1;var kind=x==0||x==6?Kind.RESERVOIR:x==4?Kind.PUMP:Kind.PIPE;
                var device=new Device(id,new Position(DIM,x,64,0),com.wormzjl.icheme.fluid.support.FluidTestSupport.mechanical(kind),kind==Kind.PUMP?pumpFacing:Direction.EAST,new PipeResistance.Geometry(1,.05,.000045,0),kind==Kind.PUMP?new FlowControl.Pump(.01,500000):new FlowControl.Passive());
                if(x==2)device=device.withCutoff(Direction.EAST,Cutoff.CHECK);
                records.add(device);if(kind==Kind.RESERVOIR)stocks.put(id,new PassiveNetwork.Reservoir(id,64,model.initialNitrogenCharge(1,298.15,200000,NOOP)));
            }
            var network=PhysicalFluidTopology.compile(records,stocks).islands().getFirst().graph();
            int node=-1;for(int i=0;i<network.reservoirs().size();i++)if(network.reservoirs().get(i).id()==3)node=i;
            assertTrue(node>=0,"check valve keeps a pressure node between independently driven sections");
            if(pumpFacing==Direction.WEST) {
                var driven=network.pipes().stream().filter(p->p.control() instanceof FlowControl.Pump).findFirst().orElseThrow();
                assertEquals(1,driven.forbiddenDirections(),"a discharge facing back through the check is blocked, even after swapping the run");
            }
        }
        for(boolean reverse:List.of(false,true)) {
            var world=line(Direction.EAST,reverse?100000:200000,reverse?200000:100000);
            world.registry.installPipeValve(3,Direction.EAST,Cutoff.CHECK);
            world.registry.installPipeValve(4,Direction.WEST,Cutoff.CHECK);world.registry.applyPending();
            assertTrue(graph(world).pipes().stream().anyMatch(p->p.forbiddenDirections()==3),"opposed check valves block both directions in their connecting run");
            for(double flow:new PassiveStepSolver(model).solve(graph(world),.1,NOOP).massFlows())assertEquals(0,flow,1e-10);
        }
    }

    // ---- Shutoff valve and accessory removal ------------------------------------------------------------------

    @Test void everyAxisCutsBothDirectionsAndReopensWithoutChangingOwnedContents() {
        for(var axis:List.of(Direction.UP,Direction.SOUTH,Direction.EAST)) {
            var world=line(axis,150000,190000);var before=stock(world);var original=valve(world);
            assertFalse(world.registry.installPipeCutoff(3,axis==Direction.UP?Direction.EAST:Direction.UP),"physical neighbours must agree with the installed axis");
            assertTrue(world.registry.installPipeCutoff(3,axis));
            assertFalse(world.registry.installPipeCutoff(3,axis),"duplicate installation queues nothing");
            world.registry.applyPending();
            assertEquals(world.registry.owner(1),world.registry.owner(5));
            assertEquals(2,valve(world).contacts().size());
            for(var d:Direction.values())assertEquals(d==axis||d==opposite(axis),valve(world).mechanicallyConnects(d));
            world.registry.togglePipeCutoff(3);
            assertEquals(Cutoff.OPEN,world.ledger.active().get(3L).device().cutoff(),"interaction never applies the topology early");
            world.registry.applyPending();
            assertNotEquals(world.registry.owner(1),world.registry.owner(5),"both charged tanks advance as independent islands");
            assertTrue(valve(world).contacts().isEmpty(),"closed in both directions");
            assertEquals(before,stock(world));
            world.registry.togglePipeCutoff(3);world.registry.applyPending();
            assertEquals(world.registry.owner(1),world.registry.owner(5));
            assertEquals(before,stock(world));assertEquals(original.id(),valve(world).id());assertEquals(original.geometry(),valve(world).geometry());
            assertTrue(world.commands.isEmpty(),"topology edits do not force a solve");
        }
    }

    @Test void rapidTogglesAndDiameterEditsSurviveAFullCheckpointWithAPendingToggle() {
        var world=line(Direction.EAST,150000,190000);world.registry.installPipeCutoff(3,Direction.EAST);world.registry.applyPending();
        var before=stock(world);world.registry.togglePipeCutoff(3);
        var record=world.registry.registrations().get(3L);var g=record.device().geometry();
        var resized=record.device().withGeometry(new PipeResistance.Geometry(g.length(),.25,g.roughness(),g.minorLoss()));
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(3,new WorldTopologyLedger.Registration(resized,record.spec(),record.revision()+1))),world.ledger.nextIdentity(),null);
        world.registry.togglePipeCutoff(3);world.registry.togglePipeCutoff(3);
        assertEquals(Cutoff.CLOSED,valve(world).cutoff());assertEquals(.25,valve(world).geometry().diameter());
        var snapshots=world.coordinator.snapshots();
        var checkpoint=new FluidCheckpointCodec.Checkpoint(snapshots.stream().map(s->new FluidCheckpointCodec.IslandEntry(DIM,"icheme:tjl20_methane_nitrogen",s)).toList());
        var store=FluidCheckpointStore.memory();store.commit(store.prepare(checkpoint,Optional.of(world.ledger.snapshot()),world.ledger.onlineTick(),key->model));
        var image=store.image();var loaded=FluidCheckpointStore.memory(image).load(image.core(),key->model);
        var saved=loaded.world().orElseThrow();assertEquals(world.ledger.onlineTick(),saved.onlineTick());
        assertArrayEquals(FluidCheckpointCodec.encodeTopology(world.ledger.snapshot()),FluidCheckpointCodec.encodeTopology(saved),"material, active state and pending edits round-trip byte-for-byte");
        var restored=loaded.checkpoint().islands().stream().map(FluidCheckpointCodec.IslandEntry::snapshot).toList();
        assertEquals(snapshots.stream().map(IslandCoordinator.Snapshot::clock).toList(),restored.stream().map(IslandCoordinator.Snapshot::clock).toList());
        var reload=new EventPathWorld(0,saved,restored);
        assertEquals(Cutoff.OPEN,reload.ledger.active().get(3L).device().cutoff());assertEquals(Cutoff.CLOSED,valve(reload).cutoff());
        reload.registry.applyPending();assertEquals(Cutoff.CLOSED,reload.ledger.active().get(3L).device().cutoff());
        assertNotEquals(reload.registry.owner(1),reload.registry.owner(5));assertEquals(before,stock(reload));
        assertEquals(.25,valve(reload).geometry().diameter());assertTrue(reload.commands.isEmpty());
    }

    @Test void aClosedValveDoesNotRemoveARealBypass() {
        var world=line(Direction.EAST,150000,190000);world.registry.installPipeCutoff(3,Direction.EAST);world.registry.togglePipeCutoff(3);
        long next=world.ledger.nextIdentity();var edits=new ArrayList<WorldTopologyLedger.Edit>();
        for(int x=1;x<=3;x++) {
            var d=new Device(next,new Position(DIM,x,64,1),Kind.PIPE,Direction.EAST,new PipeResistance.Geometry(1,.05,.000045,0),new FlowControl.Passive());
            edits.add(new WorldTopologyLedger.Edit(next,new WorldTopologyLedger.Registration(d,FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG),0)));next++;
        }
        world.registry.submit(edits,next,null);world.registry.applyPending();
        assertEquals(Cutoff.CLOSED,valve(world).cutoff());assertEquals(world.registry.owner(1),world.registry.owner(5));
        assertFalse(valve(world).mechanicallyConnects(Direction.SOUTH));
    }

    @Test void malformedCodesAndCutoffsOnOtherDeviceKindsAreRejectedByTheReader() {
        var device=new Device(1,new Position(DIM,0,64,0),Kind.PIPE,Direction.EAST,new PipeResistance.Geometry(1,.05,.000045,0),new FlowControl.Passive());
        var open=image(device.withCutoff(Direction.EAST,Cutoff.OPEN));var bad=CheckpointImages.renamed(open,"OPEN","OPEX");
        assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decodeTopology(bad,0,key->model)).getMessage().contains("unknown Cutoff OPEX"));
        var other=new Device(1,device.position(),Kind.VOID,device.facing(),device.geometry(),device.control());
        var illegalKind=CheckpointImages.renamed(open,"PIPE","VOID");
        assertTrue(assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decodeTopology(illegalKind,0,key->model)).getMessage().contains("Only a pipe"));
        assertThrows(IllegalArgumentException.class,()->other.withCutoff(Direction.EAST,Cutoff.OPEN));
    }
    @Test void wrenchRemovalRestoresOrdinaryPipeAndPreservesPendingDiameterAndContents(){
        for(var axis:List.of(Direction.EAST,Direction.UP,Direction.SOUTH))for(int type=0;type<4;type++){
            var world=line(axis,150000,190000);var before=stock(world);
            if(type==0){world.registry.installPipeCutoff(3,axis);world.registry.togglePipeCutoff(3);}else if(type==1)world.registry.installPipeValve(3,axis,Cutoff.CHECK);else if(type==2)world.registry.installPipePressureValve(3,axis);else world.registry.installPipeFilter(3,axis);
            world.registry.applyPending();var record=world.registry.registrations().get(3L);var g=record.device().geometry();
            world.registry.submit(List.of(new WorldTopologyLedger.Edit(3,new WorldTopologyLedger.Registration(record.device().withGeometry(new PipeResistance.Geometry(g.length(),.25,g.roughness(),g.minorLoss())),record.spec(),record.revision()+1))),world.ledger.nextIdentity(),null);
            assertTrue(world.registry.removePipeAccessory(3));int pending=world.ledger.snapshot().events().size();assertTrue(world.registry.removePipeAccessory(3));assertEquals(pending,world.ledger.snapshot().events().size());
            world.registry.applyPending();assertEquals(3,valve(world).id());assertEquals(.25,valve(world).geometry().diameter());assertEquals(Cutoff.NONE,valve(world).cutoff());assertInstanceOf(FlowControl.Passive.class,valve(world).control());assertEquals(world.registry.owner(1),world.registry.owner(5));assertEquals(before,stock(world));assertTrue(world.commands.isEmpty());
        }
    }

    // ---- Pressure-control valve -----------------------------------------------------------------------------------

    @Test void allSixDirectionsInstallAnUnconfiguredValveAndPreservePipeAndReservoirs() {
        for(var axis:Direction.values()) {
            var world=line(axis);var before=stock(world);var original=world.registry.registrations().get(3L);
            assertFalse(world.registry.installPipePressureValve(3,axis.y!=0?Direction.EAST:Direction.UP));
            assertTrue(world.registry.installPipePressureValve(3,axis));assertFalse(world.registry.installPipePressureValve(3,axis));
            var installed=world.registry.registrations().get(3L);var d=installed.device();
            assertEquals(original.device().id(),d.id());assertEquals(original.device().position(),d.position());
            assertEquals(original.device().geometry(),d.geometry());assertEquals(original.spec(),installed.spec());
            assertEquals(Kind.PIPE,d.kind());assertEquals(Kind.VALVE,d.hydraulicKind());
            assertFalse(d.mechanicallyConnects(axis.y!=0?Direction.EAST:Direction.UP));
            world.registry.applyPending();assertEquals(before,stock(world));
            assertTrue(graph(world).pipes().stream().anyMatch(p->p.control().equals(FlowControl.ControlValve.unconfigured())));
            assertFalse(world.registry.installPipeCutoff(3,axis));assertTrue(world.commands.isEmpty());
        }
    }

    @Test void directBoundaryConnectionsAreOrientedAndPressureSetpointClosesFlow() {
        for(var axis:List.of(Direction.UP,Direction.SOUTH,Direction.WEST))for(double upstream:new double[]{150000,350000}) {
            var devices=new ArrayList<Device>();var stock=new HashMap<Long,PassiveNetwork.Reservoir>();
            for(int i=0;i<3;i++) {
                long id=i+1;var position=new Position(DIM,i*axis.x,64+i*axis.y,i*axis.z);
                devices.add(new Device(id,position,i==1?Kind.PIPE:Kind.RESERVOIR,axis,new PipeResistance.Geometry(1,.05,.000045,0),i==1?new FlowControl.PressureValve(200000):new FlowControl.Passive()));
                if(i!=1)stock.put(id,new PassiveNetwork.Reservoir(id,position.y(),model.initialNitrogenCharge(1,298.15,i==0?upstream:100000,NOOP)));
            }
            var compiled=PhysicalFluidTopology.compile(devices,stock);assertEquals(1,compiled.islands().size());
            var graph=compiled.islands().getFirst().graph();assertTrue(graph.pipes().stream().anyMatch(p->p.control() instanceof FlowControl.PressureValve));
            var result=new PassiveStepSolver(model).solve(graph,.01,NOOP);
            for(double q:result.massFlows())if(upstream<200000)assertEquals(0,q,1e-8);else assertTrue(q>0,"forward flow follows the installed arrow");
        }
    }

    @Test void pendingInstallResizeAndPressureEditsRoundTripWithoutAnotherFormatField() {
        var world=line(Direction.EAST);var before=stock(world);assertTrue(world.registry.installPipePressureValve(3,Direction.EAST));
        var record=world.registry.registrations().get(3L);var d=record.device();
        var edited=new Device(d.id(),d.position(),d.kind(),d.facing(),new PipeResistance.Geometry(1,.25,d.geometry().roughness(),0),new FlowControl.PressureValve(275000));
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(3,new WorldTopologyLedger.Registration(edited,record.spec(),record.revision()+1))),world.ledger.nextIdentity(),null);
        var snapshots=world.coordinator.snapshots();var checkpoint=new FluidCheckpointCodec.Checkpoint(snapshots.stream().map(s->new FluidCheckpointCodec.IslandEntry(DIM,"icheme:tjl20_methane_nitrogen",s)).toList());
        var store=FluidCheckpointStore.memory();store.commit(store.prepare(checkpoint,Optional.of(world.ledger.snapshot()),world.ledger.onlineTick(),key->model));
        var image=store.image();var loaded=FluidCheckpointStore.memory(image).load(image.core(),key->model);
        assertArrayEquals(FluidCheckpointCodec.encodeTopology(world.ledger.snapshot()),FluidCheckpointCodec.encodeTopology(loaded.world().orElseThrow()));
        var restored=new EventPathWorld(0,loaded.world().orElseThrow(),loaded.checkpoint().islands().stream().map(FluidCheckpointCodec.IslandEntry::snapshot).toList());
        restored.registry.applyPending();assertEquals(before,stock(restored));assertEquals(edited,restored.ledger.active().get(3L).device());
        var active=FluidCheckpointCodec.decodeTopology(FluidCheckpointCodec.encodeTopology(restored.ledger.snapshot()),restored.ledger.onlineTick(),key->model);
        assertEquals(edited,active.active().get(3L).device());
        assertThrows(IllegalArgumentException.class,()->edited.withCutoff(Direction.EAST,Cutoff.OPEN));
    }


    // ---- Inline filter -------------------------------------------------------------------------------------------

    @Test void everyAxisUsesNativeFilterAndRemovalComposesWithPendingDiameterEdits(){
        for(var axis:Direction.values()){
            var world=line(axis);var before=stock(world);var original=world.registry.registrations().get(3L);
            assertFalse(world.registry.installPipeFilter(3,axis.y==0?Direction.UP:Direction.EAST));
            assertTrue(world.registry.installPipeFilter(3,axis));assertFalse(world.registry.installPipeFilter(3,axis));
            assertFalse(world.registry.installPipeCutoff(3,axis));assertFalse(world.registry.installPipePressureValve(3,axis));
            var installed=world.registry.registrations().get(3L);assertEquals(Kind.PIPE,installed.device().kind());assertEquals(Kind.FILTER,installed.device().hydraulicKind());
            assertEquals(original.device().id(),installed.device().id());assertEquals(original.device().geometry(),installed.device().geometry());assertEquals(original.spec(),installed.spec());
            world.registry.applyPending();assertEquals(before,stock(world));assertEquals(1,graph(world).pipes().stream().filter(p->p.filter()!=null).count());
            assertNotNull(world.registry.cake(3));var d=installed.device();var g=d.geometry();
            world.registry.submit(List.of(new WorldTopologyLedger.Edit(3,new WorldTopologyLedger.Registration(d.withGeometry(new PipeResistance.Geometry(g.length(),.25,g.roughness(),g.minorLoss())),installed.spec(),installed.revision()+1))),world.ledger.nextIdentity(),null);
            assertTrue(world.registry.removePipeAccessory(3));int events=world.ledger.snapshot().events().size();assertTrue(world.registry.removePipeAccessory(3));assertEquals(events,world.ledger.snapshot().events().size());
            world.registry.applyPending();assertEquals(before,stock(world));assertEquals(.25,world.registry.registrations().get(3L).device().geometry().diameter());assertFalse(world.registry.registrations().get(3L).device().inlineFilter());assertTrue(graph(world).pipes().stream().noneMatch(p->p.filter()!=null));assertTrue(world.commands.isEmpty());
        }
    }
    @Test void activeAndPendingFilterFlagsRoundTripAndMalformedFlagsAreRejected(){
        var world=line(Direction.EAST);world.registry.installPipeFilter(3,Direction.EAST);
        var pending=FluidCheckpointCodec.decodeTopology(FluidCheckpointCodec.encodeTopology(world.ledger.snapshot()),0,k->model);assertArrayEquals(FluidCheckpointCodec.encodeTopology(world.ledger.snapshot()),FluidCheckpointCodec.encodeTopology(pending));
        world.registry.applyPending();var active=world.ledger.snapshot();assertArrayEquals(FluidCheckpointCodec.encodeTopology(active),FluidCheckpointCodec.encodeTopology(FluidCheckpointCodec.decodeTopology(FluidCheckpointCodec.encodeTopology(active),0,k->model)));
        var r=active.active().get(3L);var plain=new LinkedHashMap<>(active.active());plain.put(3L,new WorldTopologyLedger.Registration(r.device().withInlineFilter(Direction.EAST,false),r.spec(),r.revision()));
        var clean=new WorldTopologyLedger.Snapshot(active.onlineTick(),active.nextIdentity(),plain,active.events(),active.constructed(),active.destroyed(),active.basis(),active.recoveries());
        var encoded=FluidCheckpointCodec.encodeTopology(active);var other=FluidCheckpointCodec.encodeTopology(clean);int changed=-1;for(int i=0;i<encoded.length;i++)if(encoded[i]!=other[i]){assertEquals(-1,changed);changed=i;}assertTrue(changed>=0);encoded[changed]=2;assertThrows(IllegalArgumentException.class,()->FluidCheckpointCodec.decodeTopology(encoded,0,k->model));
        var d=r.device();assertThrows(IllegalArgumentException.class,()->new Device(d.id(),d.position(),Kind.VOID,d.facing(),d.geometry(),d.control(),List.of(),true,Cutoff.NONE,true));assertThrows(IllegalArgumentException.class,()->d.withCutoff(Direction.EAST,Cutoff.OPEN));
    }
    @Test void removingLoadedCakeQueuesOneConservedWorldRecovery(){
        var world=line(Direction.EAST);world.registry.installPipeFilter(3,Direction.EAST);world.registry.applyPending();
        var solids=new com.wormzjl.icheme.science.fluid.state.SolidInventory(List.of(new com.wormzjl.icheme.science.fluid.state.SolidInventory.Population(EventPathWorld.CATALOG.solids().require("icheme:demo_particle"),com.wormzjl.icheme.science.fluid.state.ParticleSize.micrometres("100"),.25)));
        var cake=InlineFilter.empty().add(solids,125);var snapshot=world.coordinator.snapshots().getFirst();
        var pipes=snapshot.graph().pipes().stream().map(p->p.filter()==null?p:p.withFilter(cake)).toList();
        var loaded=new EventPathWorld(0,world.ledger.snapshot(),List.of(new IslandCoordinator.Snapshot(snapshot.id(),snapshot.revision(),new PassiveNetwork(snapshot.graph().reservoirs(),pipes),snapshot.clock(),Optional.empty(),snapshot.status())));
        var before=stock(loaded);assertTrue(loaded.registry.removePipeAccessory(3));assertTrue(loaded.registry.removePipeAccessory(3));loaded.registry.applyPending();
        assertEquals(before,stock(loaded));var recoveries=loaded.ledger.snapshot().recoveries();assertEquals(1,recoveries.size());var recovered=recoveries.values().iterator().next();assertEquals(solids,recovered.solids());assertEquals(125,recovered.energyJoule());assertNull(recovered.player());
        assertTrue(loaded.registry.removePipeAccessory(3));loaded.registry.applyPending();assertEquals(recoveries,loaded.ledger.snapshot().recoveries());
    }

    // ---- Flowmeter ---------------------------------------------------------------------------------------------------

    /** Every tap on the island's pipes. */
    private static List<PassiveNetwork.Pipe.ObservationPoint> taps(EventPathWorld world){return graph(world).pipes().stream().flatMap(p->p.observationPoints().stream()).toList();}
    /** {@code world} saved through the complete checkpoint and loaded with {@code flowmeters} installed, as the authority starts. */
    private EventPathWorld reloaded(EventPathWorld world,int workers,Collection<Long> flowmeters) {
        var checkpoint=new FluidCheckpointCodec.Checkpoint(world.coordinator.snapshots().stream().map(s->new FluidCheckpointCodec.IslandEntry(DIM,"icheme:tjl20_methane_nitrogen",s)).toList());
        var store=FluidCheckpointStore.memory();store.commit(store.prepare(checkpoint,Optional.of(world.ledger.snapshot()),world.ledger.onlineTick(),key->model));
        var image=store.image();var loaded=FluidCheckpointStore.memory(image).load(image.core(),key->model);
        var islands=loaded.checkpoint().islands().stream().map(FluidCheckpointCodec.IslandEntry::snapshot).toList();
        assertTrue(islands.stream().allMatch(s->s.graph().pipes().stream().allMatch(p->p.observationPoints().isEmpty())),"a checkpoint carries no tap");
        return new EventPathWorld(workers,loaded.world().orElseThrow(),islands,flowmeters);
    }
    @Test void aFlowmeterTapsItsPipeOnTheEngineScheduleAndAgainAfterAReload() {
        var world=line(Direction.EAST);var before=stock(world);assertTrue(taps(world).isEmpty());
        world.registry.flowmeter(3,true);assertTrue(taps(world).isEmpty(),"an installation is a queued engine event");
        world.registry.applyPending();var tapped=taps(world);
        assertEquals(1,tapped.size());assertEquals(3,tapped.getFirst().host());assertEquals(before,stock(world),"a tap moves nothing");
        var reload=reloaded(world,0,List.of(3L));
        assertEquals(tapped,taps(reload),"the load compiles the tap again");assertFalse(reload.ledger.hasPendingEvents(),"a load queues nothing");assertEquals(before,stock(reload));
        reload.registry.flowmeter(3,false);reload.registry.applyPending();assertTrue(taps(reload).isEmpty());assertEquals(before,stock(reload));
    }
    @Test void aRestingIslandKeepsItsTapThroughItsRestoredCertificate() {
        var line=line(Direction.EAST,100000,100000);line.registry.flowmeter(3,true);line.registry.applyPending();var tapped=taps(line);
        var resting=reloaded(line,1,List.of(3L));resting.runTo(resting.ledger.onlineTick()+600);
        var island=resting.coordinator.snapshots().getFirst();
        assertTrue(island.certificate().flatMap(IslandCoordinator.Certified::saved).isPresent(),"the equal-pressure line rests: "+island.status());
        // The restored certificate's graph is the saved interval's, which has no tap; its replay keeps the island's.
        var reload=reloaded(resting,0,List.of(3L));reload.runTo(reload.ledger.onlineTick()+100);
        var replayed=reload.coordinator.snapshots().getFirst();
        assertTrue(replayed.certificate().isPresent()&&replayed.clock().committedTick()>island.clock().committedTick(),"the reloaded island replays its certificate");
        assertEquals(tapped,taps(reload));
    }

    // ---- Inline pump and compressor --------------------------------------------------------------------------------

    @Test void eachHorizontalHeadingInstallsOneMoverWithoutChangingHostOrOwnedContents(){
        for(var axis:List.of(Direction.NORTH,Direction.SOUTH,Direction.EAST,Direction.WEST))for(var kind:List.of(Kind.PUMP,Kind.COMPRESSOR)){
            var world=line(axis);var before=stock(world);var old=world.registry.registrations().get(3L);
            assertFalse(world.registry.installPipeMover(3,Direction.UP,kind));assertFalse(world.registry.installPipeMover(3,axis.x==0?Direction.EAST:Direction.NORTH,kind));
            assertTrue(world.registry.installPipeMover(3,axis,kind));assertFalse(world.registry.installPipeMover(3,axis,kind));
            assertFalse(world.registry.installPipeFilter(3,axis));assertFalse(world.registry.installPipePressureValve(3,axis));assertFalse(world.registry.installPipeCutoff(3,axis));
            var installed=world.registry.registrations().get(3L);var d=installed.device();
            assertEquals(Kind.PIPE,d.kind());assertEquals(kind,d.hydraulicKind());assertEquals(PhysicalFluidTopology.defaultMover(kind),d.control());assertEquals(old.spec(),installed.spec());assertEquals(old.device().geometry(),d.geometry());assertEquals(old.device().id(),d.id());
            world.registry.applyPending();assertEquals(before,stock(world));
            // The one-block mover this was once compared with is gone (batch 2026-10-28-old-mover-removal): the in-line
            // mover is the only form, one driven pipe in the host's bore.
            assertEquals(1,graph(world).pipes().stream().filter(p->p.control() instanceof FlowControl.Mover).count());
            assertFalse(PipePresentation.actuatorMappings(d,graph(world)).isEmpty());assertTrue(world.commands.isEmpty());
        }
    }
    @Test void pendingControlResizeRemovalAndReinstallationComposeAndRoundTrip(){
        for(var kind:List.of(Kind.PUMP,Kind.COMPRESSOR)){
            var world=line(Direction.EAST);var before=stock(world);world.registry.installPipeMover(3,Direction.EAST,kind);var r=world.registry.registrations().get(3L);var d=r.device();
            FlowControl control=kind==Kind.PUMP?new FlowControl.Pump(.02,600000):new FlowControl.Compressor(.08,2);
            var edited=new Device(d.id(),d.position(),d.kind(),d.facing(),new PipeResistance.Geometry(1,.25,.000045,0),control,d.ports(),true,d.cutoff(),false);
            world.registry.submit(List.of(new WorldTopologyLedger.Edit(3,new WorldTopologyLedger.Registration(edited,r.spec(),r.revision()+1))),world.ledger.nextIdentity(),null);
            var image=FluidCheckpointCodec.encodeTopology(world.ledger.snapshot());var loaded=FluidCheckpointCodec.decodeTopology(image,0,k->model);assertArrayEquals(image,FluidCheckpointCodec.encodeTopology(loaded));
            var restored=new EventPathWorld(0,loaded,world.coordinator.snapshots());restored.registry.applyPending();assertEquals(edited,restored.ledger.active().get(3L).device());
            var active=FluidCheckpointCodec.encodeTopology(restored.ledger.snapshot());assertArrayEquals(active,FluidCheckpointCodec.encodeTopology(FluidCheckpointCodec.decodeTopology(active,0,k->model)));
            assertTrue(restored.registry.removePipeAccessory(3));int events=restored.ledger.snapshot().events().size();assertTrue(restored.registry.removePipeAccessory(3));assertEquals(events,restored.ledger.snapshot().events().size());
            assertTrue(restored.registry.installPipeFilter(3,Direction.EAST));assertTrue(restored.registry.removePipeAccessory(3));restored.registry.applyPending();var ordinary=restored.registry.registrations().get(3L).device();assertEquals(3,ordinary.id());assertEquals(.25,ordinary.geometry().diameter());assertInstanceOf(FlowControl.Passive.class,ordinary.control());assertEquals(before,stock(restored));assertTrue(restored.commands.isEmpty());
        }
    }
    @Test void verticalHostsAndConflictingOrDisabledMoverControlsAreRejected(){
        var world=line(Direction.UP);for(var kind:List.of(Kind.PUMP,Kind.COMPRESSOR)){
            assertFalse(world.registry.installPipeMover(3,Direction.UP,kind));assertFalse(world.registry.installPipeMover(3,Direction.EAST,kind));
            var d=world.registry.registrations().get(3L).device();assertThrows(IllegalArgumentException.class,()->new Device(d.id(),d.position(),Kind.PIPE,Direction.UP,d.geometry(),PhysicalFluidTopology.defaultMover(kind)));
            assertThrows(IllegalArgumentException.class,()->new Device(d.id(),d.position(),Kind.PIPE,Direction.EAST,d.geometry(),PhysicalFluidTopology.defaultMover(kind),List.of(),true,Cutoff.OPEN));
            assertThrows(IllegalArgumentException.class,()->new Device(d.id(),d.position(),Kind.PIPE,Direction.EAST,d.geometry(),PhysicalFluidTopology.defaultMover(kind),List.of(),true,Cutoff.NONE,true));
            FlowControl disabled=kind==Kind.PUMP?new FlowControl.Pump(.01,500000,false):new FlowControl.Compressor(.05,3,false);assertThrows(IllegalArgumentException.class,()->new Device(d.id(),d.position(),Kind.PIPE,Direction.EAST,d.geometry(),disabled));
        }
    }
    @Test void eligibilityAndMeshesCoverBothAxesHeadingsAndDiameterExtremes(){
        for(int face=0;face<6;face++)for(int mask=0;mask<64;mask++){
            boolean expected=face>=2&&SightGlassGeometry.supports(mask)&&(mask==0||(mask&3)==0);
            // A connected run determines its axis, independently of the old isolated pipe facing.
            if(mask!=0)expected=SightGlassGeometry.supports(mask)&&(mask&3)==0;
            assertEquals(expected,InlineMoverGeometry.supports(mask,face));
        }
        for(int face:new int[]{2,3,4,5})for(double bore:new double[]{.001,.05,.2,.21,.4,1})for(boolean compressor:List.of(false,true)){
            var key=new LineGeometry.Key(Kind.PIPE,face,3<<(face&~1),PipeSizes.code(PipeSizes.outside(bore)),0,0,false);var polygons=InlineMoverGeometry.polygons(key,compressor,false);
            assertFalse(polygons.isEmpty());double top=0;for(var p:polygons)for(int i=0;i<p.positions().length;i++){assertTrue(Double.isFinite(p.positions()[i]));if(i%3==1)top=Math.max(top,p.positions()[i]);}assertTrue(top>1,"upright motor can exceed one block");assertTrue(polygons.stream().anyMatch(p->p.material()==Mesh.Material.GUARD),"body-owned flow arrow");
            boolean foot=false;for(var p:polygons){boolean base=p.material()==Mesh.Material.MACHINE&&p.normals()[1]<-.9;for(int i=1;i<p.positions().length;i+=3){assertTrue(p.positions()[i]>=-1e-9,"supporting block stays clear");base&=Math.abs(p.positions()[i])<1e-9;}foot|=base;}assertTrue(foot,"a support foot rests on the block below");
            int axis=face<4?2:0;for(int end=0;end<=1;end++){
                boolean flange=false;for(var p:polygons){boolean at=p.material()==Mesh.Material.FLANGE&&p.normals()[axis]*(end==0?-1:1)>.9;for(int i=axis;i<p.positions().length;i+=3)at&=Math.abs(p.positions()[i]-end)<1e-9;flange|=at;}
                assertEquals(bore>.2,flange,"large-bore connection flange is on the block face");
            }
            for(var p:polygons){var v=p.positions();var normals=p.normals();for(int i=1;i<p.size()-1;i++){
                double ax=v[3*i]-v[0],ay=v[3*i+1]-v[1],az=v[3*i+2]-v[2],bx=v[3*i+3]-v[0],by=v[3*i+4]-v[1],bz=v[3*i+5]-v[2];
                assertTrue((ay*bz-az*by)*normals[0]+(az*bx-ax*bz)*normals[1]+(ax*by-ay*bx)*normals[2]>=-1e-9,"winding follows the native outward shading normal");
            }}
        }
    }
}
