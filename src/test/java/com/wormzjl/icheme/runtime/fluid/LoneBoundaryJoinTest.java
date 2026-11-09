package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind;
import java.util.List;
import org.junit.jupiter.api.Test;
import static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Direction.NORTH;
import static org.junit.jupiter.api.Assertions.*;

/**
 * D32 (main 0.8.0 runtime code): a lone generator or void is an island of one (owner decision D17), and the pipe that joins
 * it to an island ends that island of one. The batch that put the boundary into the joined island used to leave its island
 * of one in the coordinator, certified, and it was saved beside the joined island; after a reload it compiled to no device,
 * and every later save threw in {@code FluidWorldAuthority.capture} ({@code members(id).getFirst()}). Here a lone
 * generator and a lone void are joined to a tank, the world is saved, reloaded and saved again, as the crash needed.
 */
class LoneBoundaryJoinTest {
    @Test void joiningALoneGeneratorAndALoneVoidToATankEndsTheirIslandsOfOneThroughASaveAndAReload() {
        var w=new EventPathWorld();
        long generator=w.place(0,0,0,Kind.GENERATOR,NORTH,EventPathWorld.nitrogen(),.05);
        long tank=w.place(3,0,0,Kind.RESERVOIR,NORTH);
        long vent=w.place(6,0,0,Kind.VOID,NORTH);
        applied(w);
        for(long device:List.of(generator,tank,vent))assertEquals(List.of(device),w.registry.members(w.registry.owner(device)),"placed alone: an island of one");
        for(int x:new int[]{1,2,4,5})w.place(x,0,0,Kind.PIPE,NORTH);
        applied(w);
        long joined=w.registry.owner(tank);
        assertEquals(java.util.Set.of(joined),w.registry.islands(generator),"the generator is in the joined island only");
        assertEquals(java.util.Set.of(joined),w.registry.islands(vent),"the void is in the joined island only");
        assertEquals(1,w.coordinator.snapshots().size(),"one island: the joined one");
        everyIslandHoldsADevice(w,"the first save");
        var reloaded=new EventPathWorld(1,w.ledger.snapshot(),w.entries());
        everyIslandHoldsADevice(reloaded,"the save after the reload");
        assertEquals(java.util.Set.of(reloaded.registry.owner(tank)),reloaded.registry.islands(generator));
    }
    /** Ticks the world until every queued placement is applied. */
    private static void applied(EventPathWorld w) {
        for(int i=0;i<400&&!w.ledger.events().isEmpty();i++)w.tick();
        assertTrue(w.ledger.events().isEmpty(),"every placement applied");
    }
    /** What {@code FluidWorldAuthority.capture} reads of every island: the dimension of its first device. */
    private static void everyIslandHoldsADevice(EventPathWorld w,String when) {
        for(var island:w.coordinator.snapshots())assertFalse(w.registry.members(island.id()).isEmpty(),when+": island "+island.id()+" holds a device");
    }
}
