package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.network.*;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A blast's breach of a vessel and its repair (batch 2026-11-15-pollution-system, WP6b): each is the queued topology edit
 * that replaces the vessel's registration by its spec {@link FluidDeviceSpec#withBreached breached} or whole again
 * ({@code PhysicalRegistry}, a respecified vessel). At the batch that applies it the vessel keeps its moles and energy
 * exactly; breached it stands open ({@link FluidDeviceSpec#vented}), not rigid and with no gas blanket, and vents what it
 * holds; repaired it is closed again, a bag until its wall carries its contents once more.
 */
class VesselBreachTest {
    private static final double SET=135000;

    private static PassiveNetwork.Reservoir node(EventPathWorld world,long id) {
        for(var s:world.coordinator.snapshots())for(var n:s.graph().reservoirs())if(n.id()==id)return n;
        return null;
    }
    private static double held(PassiveNetwork.Reservoir node){return Arrays.stream(node.inventory().moles()).sum();}
    /** Vessel {@code id}'s registration replaced by its spec breached or whole, as FluidWorldAuthority.breach and repair queue it. */
    private static void respecified(EventPathWorld world,long id,boolean breached) {
        var r=world.registry.registrations().get(id);
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(r.device(),r.spec().withBreached(breached),r.revision()+1))),world.ledger.nextIdentity(),null);
    }
    /**
     * Runs until vessel {@code id}'s graph inventory first stands {@code open}: the inventory the batch that applied the edit
     * installed (read before that tick's solves), and the inventory it replaced (read after the tick before).
     */
    private static PassiveNetwork.Inventory[] applied(EventPathWorld world,long id,boolean open) {
        PassiveNetwork.Inventory[] seen={null};
        world.beforeSolves=()->{var n=node(world,id);if(seen[0]==null&&n!=null&&n.inventory().open()==open)seen[0]=n.inventory();};
        PassiveNetwork.Inventory before=node(world,id).inventory();long limit=world.ledger.onlineTick()+200;
        while(seen[0]==null&&world.ledger.onlineTick()<limit){var n=node(world,id);if(n!=null)before=n.inventory();world.tick();}
        world.beforeSolves=()->{};
        assertNotNull(seen[0],"the edit applied within 200 ticks");
        return new PassiveNetwork.Inventory[]{before,seen[0]};
    }

    /** A 1 m3 vessel charged from a 3 bar, 300 K nitrogen generator through two pipes, the generator then removed. */
    private static long charged(EventPathWorld world) {
        long generator=world.place(0,64,0,Kind.GENERATOR,Direction.EAST,new FluidDeviceSpec(1,300,3e5,FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG).composition()),.05);
        for(int x=1;x<=2;x++)world.place(x,64,0,Kind.PIPE,Direction.EAST,.05);
        long vessel=world.place(3,64,0,Kind.RESERVOIR,Direction.EAST);
        world.runTo(400);
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(generator,null)),world.ledger.nextIdentity(),null);world.runTo(600);
        return vessel;
    }

    @Test void aBreachStandsAClosedVesselOpenWithWhatItHeldAndARepairClosesIt() {
        var world=new EventPathWorld();long id=charged(world);
        var start=node(world,id);
        assertTrue(held(start)>10&&!start.inventory().open(),"a charged closed vessel: "+held(start)+" mol");
        assertTrue(start.inventory().rigid(),"its wall carried 3 bar: rigid");
        var spec=world.registry.registrations().get(id).spec();
        var once=spec.withBreached(true);
        assertSame(once,once.withBreached(true),"breached twice is breached once: the same spec, so no second edit (Placement.edit)");
        assertSame(spec,spec.withBreached(false),"a whole vessel repaired is unchanged");
        assertTrue(once.vented()&&!once.open()&&!spec.vented()&&!once.equals(spec),"vented, still built closed, another spec");

        respecified(world,id,true);
        var breach=applied(world,id,true);
        assertTrue(breach[1].sameContents(breach[0]),"the breach keeps every mole and the energy exactly");
        assertTrue(breach[1].open()&&!breach[1].rigid()&&breach[1].blanket()==0,"breached: open, not rigid, no blanket");
        assertTrue(world.registry.registrations().get(id).spec().breached());
        // The open-vessel law vents its gas at the share dt / 1 s a step (OpenVessel.RELEASE_TIME) and keeps none of it: 20 s here.
        world.runTo(world.ledger.onlineTick()+400);
        var vented=node(world,id);
        assertTrue(held(vented)<.05*held(start),"the breached vessel vented its gas: "+held(start)+" -> "+held(vented)+" mol");

        respecified(world,id,false);
        var repair=applied(world,id,false);
        assertTrue(repair[1].sameContents(repair[0]),"the repair keeps every mole and the energy exactly");
        assertTrue(!repair[1].open()&&!repair[1].rigid(),"repaired: closed, a bag until its wall carries its contents again");
        assertFalse(world.registry.registrations().get(id).spec().breached());
        assertTrue(world.held.isEmpty(),"no hold: "+world.held);
    }

    /** A drum with a gas blanketing valve: breached, its graph inventory carries no blanket while its spec keeps the valve's set pressure; repaired, the valve works again. */
    @Test void aBreachedBlanketedDrumKeepsItsValveInItsSpecOnly() {
        var world=new EventPathWorld();
        long id=world.ledger.nextIdentity();var node=new PhysicalFluidTopology.Position(EventPathWorld.DIM,10,64,0);
        var g=VesselGeometry.round(VesselGeometry.Form.HORIZONTAL_DRUM,2,6,2.0/3,.7);
        var ports=List.of(new PhysicalFluidTopology.Port(node,Direction.WEST,PassiveNetwork.PhasePort.LIQUID),
                new PhysicalFluidTopology.Port(new PhysicalFluidTopology.Position(EventPathWorld.DIM,10,66,0),Direction.EAST,PassiveNetwork.PhasePort.VAPOR,PhysicalFluidTopology.ConnectionRole.BLANKET));
        var device=PhysicalFluidTopology.Device.builder(id,node,Kind.RESERVOIR,Direction.NORTH,new PipeResistance.Geometry(1,.05,PipeResistance.DEFAULT_ROUGHNESS_METRES,0),new FlowControl.Passive()).ports(ports).build();
        var spec=new FluidDeviceSpec(g.capacity(),298.15,101325,FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG).composition(),SlurryFeed.NONE,g.height(),false,g,null,SET);
        world.registry.submit(List.of(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(device,spec,0))),id+1,null);
        world.runTo(40);
        assertEquals(SET,node(world,id).inventory().blanket());
        respecified(world,id,true);
        var breach=applied(world,id,true);
        assertEquals(0,breach[1].blanket(),"no blanket on an open vessel");
        assertEquals(SET,world.registry.registrations().get(id).spec().blanket(),"the valve's set pressure is kept");
        respecified(world,id,false);
        var repair=applied(world,id,false);
        assertEquals(SET,repair[1].blanket(),"repaired, the valve works again");
        assertTrue(world.held.isEmpty(),"no hold: "+world.held);
    }

    /** A breached spec placed new (a registration restored from facts that reached a fresh island) starts as an empty open vessel. */
    @Test void aBreachedVesselPlacedEmptyStandsOpen() {
        var world=new EventPathWorld();
        long id=world.place(0,64,0,Kind.RESERVOIR,Direction.EAST,new FluidDeviceSpec(4,298.15,101325,FluidDeviceSpec.nitrogen(EventPathWorld.CATALOG).composition()).withBreached(true),.05);
        world.runTo(40);
        var n=node(world,id);
        assertNotNull(n);assertTrue(n.inventory().open()&&!n.inventory().rigid()&&held(n)==0,"an empty open vessel");
    }
}
