package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.network.PipeResistance;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.*;

/**
 * A layout refresh rebuilds a placed vessel or machine where it now stands and to its new size (batch
 * 2026-10-26-backward-compat, decision D7): one queued edit of its registration moves its node and its ports and
 * resizes a vessel, and it keeps its identity, its controls and what it holds. A vessel's moles, energy and solids stay
 * as they were, in its new geometry; the island's next interval start closes its state to the new capacity. No stream
 * carries potential energy (D37, S1), so the move books nothing.
 */
class VesselRebuildTest {
    private static PhysicalFluidTopology.Position at(int x,int y,int z){return new PhysicalFluidTopology.Position(EventPathWorld.DIM,x,y,z);}
    private static PassiveNetwork.Reservoir vessel(EventPathWorld w,long id) {
        var island=w.registry.owner(id);assertNotNull(island,"vessel "+id+" has an island");
        for(var node:w.coordinator.observe(island).graph().reservoirs())if(node.id()==id)return node;
        throw new AssertionError("vessel "+id+" is in no node of its island");
    }
    private static double sum(double[] values){return Arrays.stream(values).sum();}

    /**
     * A 2 m3 drum filled to 3 bar from a 340 K nitrogen generator, the generator then removed, is rebuilt with its node one
     * cell lower and across a chunk edge, at twice the volume, its nozzle where it was. Expected: the same identity, the
     * same moles and internal energy at the edit, the gas at about half its pressure once the state closes (a Joule
     * expansion: nitrogen near ideal, T about kept), and the nozzle still joined to its line.
     */
    @Test void aRebuiltVesselKeepsItsIdentityAndWhatItHoldsInItsNewPlaceAndSize() {
        var w=new EventPathWorld();
        var nozzle=List.of(new PhysicalFluidTopology.Port(at(0,64,0),EAST,PassiveNetwork.PhasePort.BULK));
        long drum=w.placeVessel(at(0,62,0),nozzle,2,3);
        for(int x=1;x<=3;x++)w.place(x,64,0,Kind.PIPE,EAST,.05);
        long generator=w.place(4,64,0,Kind.GENERATOR,EAST,new FluidDeviceSpec(1,340,300000,EventPathWorld.nitrogen().composition()),.05);
        w.runTo(400);w.registry.submit(List.of(new WorldTopologyLedger.Edit(generator,null)),w.ledger.nextIdentity(),null);w.runTo(600);
        w.assertKeptUp("the charged drum");
        var before=vessel(w,drum);
        assertTrue(sum(before.inventory().moles())>150,"the generator charged the drum: "+before.inventory());
        var record=w.registry.registrations().get(drum);var spec=record.spec();
        var moved=record.device().withPlacement(at(-1,61,0),nozzle);
        var bigger=new FluidDeviceSpec(4,spec.temperature(),spec.pressure(),spec.composition(),spec.solids(),3.5,spec.open());
        w.registry.submit(List.of(new WorldTopologyLedger.Edit(drum,new WorldTopologyLedger.Registration(moved,bigger,record.revision()+1))),w.ledger.nextIdentity(),null);
        // At the edit, before any solve: the stock as it was, in the new geometry.
        var atEdit=new PassiveNetwork.Reservoir[1];
        w.beforeSolves=()->{if(atEdit[0]==null&&w.ledger.active().get(drum).revision()==record.revision()+1)atEdit[0]=vessel(w,drum);};
        w.runTo(640);w.beforeSolves=()->{};
        assertNotNull(atEdit[0],"the edit applied");
        assertArrayEquals(before.inventory().moles(),atEdit[0].inventory().moles(),0,"the moles are kept");
        assertEquals(before.inventory().internalEnergy(),atEdit[0].inventory().internalEnergy(),0,"the energy is kept");
        assertEquals(4,atEdit[0].inventory().volume());assertEquals(3.5,atEdit[0].inventory().levelHeight());assertEquals(61,atEdit[0].elevation());
        // The registry finds the drum at its new node, its nozzle where it was, and no longer at the old node.
        assertEquals(drum,w.registry.at(at(-1,61,0)).orElseThrow().device().id());assertEquals(drum,w.registry.at(at(0,64,0)).orElseThrow().device().id());
        assertTrue(w.registry.at(at(0,62,0)).isEmpty(),"the old node is free");
        assertTrue(w.registry.chunk(EventPathWorld.DIM,PhysicalRegistry.chunkOf(at(-1,61,0))).contains(drum),"the drum is in its new chunk");
        assertFalse(w.registry.chunk(EventPathWorld.DIM,PhysicalRegistry.chunkOf(at(0,62,0))).contains(drum),"the drum left its old chunk");
        w.runTo(800);w.assertKeptUp("the rebuilt drum");
        var after=vessel(w,drum);
        assertEquals(1,sum(after.inventory().moles())/sum(before.inventory().moles()),1e-9,"no flow in a closed line");
        assertEquals(4,after.state().volume(),4e-6,"the gas fills the new capacity");
        double ratio=after.state().pressure()/before.state().pressure();
        assertTrue(ratio>.48&&ratio<.52,"about half the pressure at twice the volume: "+ratio);
        // The line still runs through the nozzle: a void at its end empties the drum.
        w.place(4,64,0,Kind.VOID,EAST,.05);w.runTo(1400);w.assertKeptUp("the drum emptying into a void");
        assertTrue(sum(vessel(w,drum).inventory().moles())<.9*sum(after.inventory().moles()),"the rebuilt drum drains through its nozzle");
    }

    /** A constructed mover moved by a refresh keeps its identity and controls (D7); a vessel's charge and a kind or a
     * dimension never change by an edit. */
    @Test void aMovedMachineKeepsItsIdentityAndAVesselsChargeNeverChanges() {
        var w=new EventPathWorld();
        long pump=w.ledger.nextIdentity();
        var ports=List.of(new PhysicalFluidTopology.Port(at(10,64,0),WEST,PassiveNetwork.PhasePort.BULK,PhysicalFluidTopology.ConnectionRole.SUCTION),
                new PhysicalFluidTopology.Port(at(11,64,0),EAST,PassiveNetwork.PhasePort.BULK,PhysicalFluidTopology.ConnectionRole.DISCHARGE));
        var device=PhysicalFluidTopology.Device.builder(pump,at(10,64,0),Kind.PUMP,EAST,new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),new FlowControl.Pump(.02,400000)).ports(ports).build();
        w.registry.submit(List.of(new WorldTopologyLedger.Edit(pump,new WorldTopologyLedger.Registration(device,EventPathWorld.nitrogen(),0))),pump+1,null);w.runTo(20);
        var movedPorts=List.of(new PhysicalFluidTopology.Port(at(20,64,0),WEST,PassiveNetwork.PhasePort.BULK,PhysicalFluidTopology.ConnectionRole.SUCTION),
                new PhysicalFluidTopology.Port(at(21,64,0),EAST,PassiveNetwork.PhasePort.BULK,PhysicalFluidTopology.ConnectionRole.DISCHARGE));
        w.registry.submit(List.of(new WorldTopologyLedger.Edit(pump,new WorldTopologyLedger.Registration(device.withPlacement(at(20,64,0),movedPorts),EventPathWorld.nitrogen(),1))),w.ledger.nextIdentity(),null);w.runTo(40);
        var moved=w.registry.at(at(20,64,0)).orElseThrow();
        assertEquals(pump,moved.device().id());assertEquals(device.control(),moved.device().control(),"the controls are kept");
        assertTrue(w.registry.at(at(10,64,0)).isEmpty()&&w.registry.at(at(11,64,0)).isEmpty(),"the old cells are free");
        assertTrue(w.registry.chunk(EventPathWorld.DIM,PhysicalRegistry.chunkOf(at(20,64,0))).contains(pump));
        assertFalse(w.registry.chunk(EventPathWorld.DIM,PhysicalRegistry.chunkOf(at(10,64,0))).contains(pump));

        long drum=w.placeVessel(at(0,62,0),List.of(new PhysicalFluidTopology.Port(at(0,64,0),EAST,PassiveNetwork.PhasePort.BULK)),2,3);w.runTo(60);
        var record=w.registry.registrations().get(drum);var spec=record.spec();
        var warmer=new FluidDeviceSpec(spec.volume(),spec.temperature()+10,spec.pressure(),spec.composition(),spec.solids(),spec.levelHeight(),spec.open());
        assertThrows(IllegalStateException.class,()->w.registry.submit(List.of(new WorldTopologyLedger.Edit(drum,new WorldTopologyLedger.Registration(record.device(),warmer,record.revision()+1))),w.ledger.nextIdentity(),null),
                "a vessel is never charged again");
        var nether=new PhysicalFluidTopology.Position("minecraft:the_nether",0,62,0);
        assertThrows(IllegalStateException.class,()->w.registry.submit(List.of(new WorldTopologyLedger.Edit(drum,new WorldTopologyLedger.Registration(
                record.device().withPlacement(nether,List.of(new PhysicalFluidTopology.Port(nether,EAST,PassiveNetwork.PhasePort.BULK))),spec,record.revision()+1))),w.ledger.nextIdentity(),null),
                "a device never changes its dimension");
    }
}
