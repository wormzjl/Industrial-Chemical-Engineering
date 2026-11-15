package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;

/**
 * A drum is a reservoir of the fluid network (batch 2026-10-15-custom-equipment-rendering, owner request 2026-10-16): one
 * node at its lowest inside cell, met by pipes only at its nozzles, each nozzle a port of the class its position fixes,
 * with the drum's own volume and level height. The drum here stands in a 3 x 5 x 3 box: node (1,0,1), top nozzle (1,4,1)
 * up, liquid nozzle (1,1,0) north, side nozzle (2,2,1) east.
 */
class DrumReservoirTest {
    private static PhysicalFluidTopology.Position at(int x,int y,int z){return new PhysicalFluidTopology.Position(EventPathWorld.DIM,x,y,z);}
    private static final List<PhysicalFluidTopology.Port> PORTS=List.of(
            new PhysicalFluidTopology.Port(at(1,4,1),UP,PassiveNetwork.PhasePort.VAPOR),
            new PhysicalFluidTopology.Port(at(1,1,0),NORTH,PassiveNetwork.PhasePort.LIQUID),
            new PhysicalFluidTopology.Port(at(2,2,1),EAST,PassiveNetwork.PhasePort.BULK));

    @Test void pipesJoinADrumOnlyAtItsNozzlesWithTheirDeclaredPorts() {
        var w=new EventPathWorld();w.runTo(100);
        long drum=w.placeVessel(at(1,0,1),PORTS,20,4);
        long top=w.place(1,5,1,Kind.PIPE,NORTH,.05);w.place(1,6,1,Kind.VOID,NORTH,.05);
        long liquid=w.place(1,1,-1,Kind.PIPE,NORTH,.05);w.place(1,1,-2,Kind.VOID,NORTH,.05);
        // Beside a nozzle cell on a face that is not the nozzle's, and under the node, where a one-block tank's bottom
        // outlet would be: neither meets the drum.
        long beside=w.place(2,1,0,Kind.PIPE,NORTH,.05);w.place(3,1,0,Kind.VOID,NORTH,.05);
        long under=w.place(1,-1,1,Kind.PIPE,NORTH,.05);w.place(1,-2,1,Kind.VOID,NORTH,.05);
        w.runTo(102);
        for(var cell:List.of(at(1,0,1),at(1,4,1),at(1,1,0),at(2,2,1)))assertEquals(drum,w.registry.at(cell).orElseThrow().device().id(),"the drum is found at "+cell);
        var island=w.registry.owner(drum);assertNotNull(island);var members=w.registry.members(island);
        assertTrue(members.containsAll(List.of(top,liquid)),"the nozzles' lines join the drum: "+members);
        assertFalse(members.contains(beside)||members.contains(under),"no line joins away from a nozzle: "+members);
        var faces=w.registry.tankFaces(drum);assertEquals(Set.of(UP,NORTH),Set.copyOf(faces.values()));
        var graph=w.coordinator.observe(island).graph();int node=-1;
        for(int i=0;i<graph.reservoirs().size();i++)if(graph.reservoirs().get(i).id()==drum)node=i;
        var tank=graph.reservoirs().get(node);assertEquals(20,tank.inventory().volume());assertEquals(4,tank.inventory().levelHeight());
        for(var pipe:graph.pipes()) {
            var face=faces.get(pipe.id());if(face==null)continue;
            assertEquals(face==UP?PassiveNetwork.PhasePort.VAPOR:PassiveNetwork.PhasePort.LIQUID,pipe.portAt(node),"the "+face+" nozzle");
        }
        assertThrows(IllegalStateException.class,()->w.place(1,4,1,Kind.PIPE,NORTH,.05),"a nozzle cell is the drum's");
        w.runTo(4000);w.assertKeptUp("a drum between two voids");
        w.registry.submit(List.of(new WorldTopologyLedger.Edit(drum,null)),w.ledger.nextIdentity(),null);w.runTo(4002);
        assertTrue(w.registry.at(at(1,4,1)).isEmpty(),"a removed drum leaves its nozzle cells");assertNull(w.registry.owner(drum));
    }

    @Test void aDrumsRegistrationRoundTripsItsPortsAndLevelHeight() {
        var empty=WorldTopologyLedger.Snapshot.empty(EventPathWorld.CATALOG);
        var device=PhysicalFluidTopology.Device.builder(1,at(1,0,1),Kind.RESERVOIR,NORTH,new com.wormzjl.icheme.science.fluid.network.PipeResistance.Geometry(1,.05,.000045,0),
                new com.wormzjl.icheme.science.fluid.network.FlowControl.Passive()).ports(PORTS).build();
        var spec=new FluidDeviceSpec(20,298.15,101325,EventPathWorld.nitrogen().composition(),SlurryFeed.NONE,4);
        var world=new WorldTopologyLedger.Snapshot(5,2,Map.of(1L,new WorldTopologyLedger.Registration(device,spec,0)),List.of(),empty.constructed(),empty.destroyed(),empty.basis(),Map.of());
        var bytes=FluidCheckpointCodec.encodeTopology(world);var decoded=FluidCheckpointCodec.decodeTopology(bytes,5,key->{throw new AssertionError("no recovery, no model");});
        assertEquals(world.active(),decoded.active(),"ports, their classes and the level height come back");
        assertArrayEquals(bytes,FluidCheckpointCodec.encodeTopology(decoded));
        assertThrows(IllegalArgumentException.class,()->PhysicalFluidTopology.Device.builder(2,at(0,0,0),Kind.PIPE,NORTH,device.geometry(),device.control()).ports(PORTS).build(),"only a vessel has ports");
    }
}
