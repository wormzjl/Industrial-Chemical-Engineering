package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A drum's gas blanketing valve in a world (batch 2026-11-11-gas-blanketing): a drum placed empty, its valve fitted
 * ({@link FluidDeviceSpec#blanket}, its inlet the drum's {@code BLANKET} port), on a 5 bar nitrogen line of two pipes. The
 * run into the port compiles with the valve ({@link FlowControl.ValveSource#BLANKET}), the coordinator plans it each slice
 * ({@link GasBlanket#plan}), and the drum fills to its set pressure and rests there. Removing the valve's port and set
 * pressure leaves a plain closed drum.
 */
class BlanketValveWorldTest {
    private static final double SET=135000;

    private static long drum(EventPathWorld world,boolean valve) {
        long id=world.ledger.nextIdentity();var node=new PhysicalFluidTopology.Position(EventPathWorld.DIM,10,64,0);
        var g=VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,2,6,2.0/3,.7);
        var ports=new ArrayList<PhysicalFluidTopology.Port>();
        ports.add(new PhysicalFluidTopology.Port(node,Direction.WEST,PassiveNetwork.PhasePort.LIQUID));
        if(valve)ports.add(new PhysicalFluidTopology.Port(new PhysicalFluidTopology.Position(EventPathWorld.DIM,10,66,0),Direction.EAST,PassiveNetwork.PhasePort.VAPOR,PhysicalFluidTopology.ConnectionRole.BLANKET));
        var device=new PhysicalFluidTopology.Device(id,node,Kind.RESERVOIR,Direction.NORTH,new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),new FlowControl.Passive(),ports);
        var spec=new FluidDeviceSpec(g.capacity(),298.15,101325,FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG).composition(),SlurryFeed.NONE,g.height(),false,g,null,valve?SET:0);
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(device,spec,0))),id+1,null);
        return id;
    }
    private static PassiveNetwork.Reservoir node(EventPathWorld world,long id) {
        for(var s:world.coordinator.snapshots())for(var n:s.graph().reservoirs())if(n.id()==id)return n;
        throw new AssertionError("no node "+id);
    }
    private static FlowControl valveInto(EventPathWorld world,long id) {
        for(var s:world.coordinator.snapshots())for(var p:s.graph().pipes())if(s.graph().reservoirs().get(p.second()).id()==id||s.graph().reservoirs().get(p.first()).id()==id)return p.control();
        return null;
    }

    @Test void aFittedDrumOnANitrogenLineFillsToItsSetPressureAndRests() {
        var world=new EventPathWorld();
        long drum=drum(world,true);
        var supply=new FluidDeviceSpec(1,298.15,5e5,FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG).composition());
        world.place(11,66,0,Kind.PIPE,Direction.EAST);world.place(12,66,0,Kind.PIPE,Direction.EAST);
        world.place(13,66,0,Kind.GENERATOR,Direction.EAST,supply,.05);
        world.runTo(1600);
        world.assertKeptUp("blanketed drum");
        var n=node(world,drum);
        System.out.println("BLANKET_WORLD P="+n.state().pressure()+" rigid="+n.inventory().rigid()+" blanket="+n.inventory().blanket()+" valve="+valveInto(world,drum));
        assertTrue(valveInto(world,drum) instanceof FlowControl.ControlValve v&&v.source()==FlowControl.ValveSource.BLANKET,"the run into the port carries the valve");
        assertEquals(SET,n.inventory().blanket(),0);
        // The drum's view lists the supply connection by its face (TankOutlets refuses a connection without one).
        assertEquals(Direction.EAST,world.registry.tankFaces(drum).values().stream().findFirst().orElse(null),"the supply connection's face");
        assertTrue(n.inventory().rigid(),"its gas fills it");
        assertEquals(SET,n.state().pressure(),.03*SET,"at its set pressure");
        var island=world.coordinator.snapshots().stream().filter(s->s.graph().reservoirs().stream().anyMatch(r->r.id()==drum)).findFirst().orElseThrow();
        System.out.println("BLANKET_WORLD status="+island.status());
        assertTrue(island.status().startsWith("STEADY: no flow"),"the valve asks for nothing more, and the island rests: "+island.status());
        // The valve removed: one registry edit, the drum keeps what it holds and is a plain closed drum again.
        var record=world.registry.registrations().get(drum);var ports=new ArrayList<>(record.device().ports());ports.removeIf(p->p.role()==PhysicalFluidTopology.ConnectionRole.BLANKET);
        double held=Arrays.stream(n.inventory().moles()).sum();
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(drum,new WorldTopologyLedger.Registration(record.device().withPlacement(record.device().position(),ports),record.spec().withBlanket(0),record.revision()+1))),world.ledger.nextIdentity(),null);
        world.runTo(1700);
        world.assertKeptUp("valve removed");
        var after=node(world,drum);
        assertEquals(0,after.inventory().blanket(),0);
        assertEquals(held,Arrays.stream(after.inventory().moles()).sum(),1e-9*held,"it keeps what it holds");
    }
}
