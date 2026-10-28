package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.column.operation.ColumnCycle.Account;
import com.wormzjl.icheme.science.column.operation.Port;
import com.wormzjl.icheme.science.column.operation.Setpoints;
import com.wormzjl.icheme.science.fluid.network.FlowControl;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.util.*;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A running column's place in the network (batch 2026-10-28-column-operation, C3, plan 4.1 and 4.2): the devices it holds
 * keep what they hold and join nothing, its port inside a boundary valve meets that valve alone, so the port splits the
 * line into islands, and a stop joins the line again with every vessel as it was.
 */
class ColumnSuspensionTest {
    /** A 3 bar nitrogen generator, a pipe, a valve, a pipe and a tank in a row along x. */
    private static long[] line(EventPathWorld world) {
        long generator=world.place(0,64,0,Kind.GENERATOR,Direction.EAST,new FluidDeviceSpec(1,340,300000,EventPathWorld.nitrogen().composition()),.05);
        long first=world.place(1,64,0,Kind.PIPE,Direction.EAST,.05);
        long valve=world.place(2,64,0,Kind.VALVE,Direction.EAST);
        long inside=world.place(3,64,0,Kind.PIPE,Direction.EAST,.05);
        long tank=world.place(4,64,0,Kind.RESERVOIR,Direction.EAST);
        return new long[]{generator,first,valve,inside,tank};
    }
    private static Set<Long> islandOf(EventPathWorld world,long id) {
        for(var s:world.coordinator.snapshots()) {
            var ids=new HashSet<Long>();for(var n:s.graph().reservoirs())ids.add(n.id());
            if(ids.contains(id))return ids;
        }
        return Set.of();
    }
    private static PassiveNetwork.Inventory inventory(EventPathWorld world,long id) {
        for(var s:world.coordinator.snapshots())for(var n:s.graph().reservoirs())if(n.id()==id)return n.inventory();
        throw new AssertionError("vessel "+id+" has no island");
    }
    private static WorldTopologyLedger.Edit held(EventPathWorld world,long id,long unit) {
        var r=world.registry.registrations().get(id);
        return new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(r.device().withSuspendedBy(unit),r.spec(),r.revision()+1));
    }

    @Test void aRunningColumnsPortSplitsTheLineAndAStopJoinsItAgainWithEveryVesselAsItWas() {
        var world=new EventPathWorld();var ids=line(world);long generator=ids[0],valve=ids[2],inside=ids[3],tank=ids[4];
        world.runTo(400);
        assertTrue(islandOf(world,generator).contains(tank),"one line before the start");
        var before=inventory(world,tank);
        assertTrue(Arrays.stream(before.moles()).sum()>0,"the generator charged the tank");
        // Start: the column holds the inside pipe and the tank; its feed port, a void at 1.5 bar, stands inside the valve.
        long unit=world.ledger.reserve(1);long port=world.ledger.nextIdentity();int m=world.model.componentCount();
        var at=new PhysicalFluidTopology.Position(EventPathWorld.DIM,3,64,0,PhysicalFluidTopology.PORT_LANE);
        var feed=new PhysicalFluidTopology.Device(port,at,Kind.VOID,Direction.WEST,world.registry.registrations().get(valve).device().geometry(),new FlowControl.Passive(),
                List.of(new PhysicalFluidTopology.Port(at,Direction.WEST,PassiveNetwork.PhasePort.BULK)),true,PhysicalFluidTopology.Cutoff.NONE,false,PhysicalFluidTopology.ALL_FACES,0,0,null,0);
        var setpoints=new Setpoints(1,1,320,1,0,new double[0],new double[0],new double[0]);
        world.ledger.column(new ColumnUnit(unit,new PhysicalFluidTopology.Position(EventPathWorld.DIM,4,70,0),true,setpoints,
                List.of(new ColumnUnit.Station(Port.FEED,0,port,valve,Account.empty(m))),ColumnUnit.Totals.empty(m)));
        world.registry.submit(List.of(held(world,inside,unit),held(world,tank,unit),
                new WorldTopologyLedger.Edit(port,new WorldTopologyLedger.Registration(feed,new FluidDeviceSpec(1,330,150000,EventPathWorld.nitrogen().composition()),0))),port+1,null);
        world.runTo(800);
        var running=islandOf(world,generator);
        assertTrue(running.contains(port)&&!running.contains(tank),"the port ends the generator's line: "+running);
        assertEquals(Set.of(tank),islandOf(world,tank),"the held tank stands alone");
        assertTrue(inventory(world,tank).sameContents(before),"the held tank keeps what it held, bit for bit");
        // Stop: the held devices are released and the port leaves.
        var release=new ArrayList<WorldTopologyLedger.Edit>();
        for(long id:new long[]{inside,tank}){var r=world.registry.registrations().get(id);release.add(new WorldTopologyLedger.Edit(id,new WorldTopologyLedger.Registration(r.device().withSuspendedBy(0),r.spec(),r.revision()+1)));}
        release.add(new WorldTopologyLedger.Edit(port,null));
        world.registry.submit(release,world.ledger.nextIdentity(),null);
        world.runTo(820);
        assertTrue(islandOf(world,generator).contains(tank),"one line again after the stop");
        assertTrue(inventory(world,tank).sameContents(before),"the tank comes back with what it held");
        world.assertKeptUp("after the stop");
    }
}
