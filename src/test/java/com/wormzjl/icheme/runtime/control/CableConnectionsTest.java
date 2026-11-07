package com.wormzjl.icheme.runtime.control;

import com.wormzjl.icheme.science.equipment.CableMedium;
import com.wormzjl.icheme.science.equipment.ConduitGeometry;
import net.minecraft.core.*;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CableConnectionsTest {
    @Test void terminalMarksAndBusAgreeAfterASensorOrNeighbourIsRemoved(){
        for(var medium:CableMedium.values()){
            var id=new UUID(0,1);var port=new DataPort(id,medium==CableMedium.DATA?DataPort.Kind.SENSOR:DataPort.Kind.POWER_INPUT,medium==CableMedium.DATA?12:0,
                    "minecraft:overworld",new BlockPos(-1,0,0),new Vec3(-.1,.5,.5),BlockPos.ZERO,Direction.WEST,0,0);
            var cable=cable(BlockPos.ZERO,0,48,0,medium,Map.of(Direction.WEST,id));
            var neighbour=cable(new BlockPos(1,0,0),0,48,0,medium,Map.of());
            var nearby=Map.of(neighbour.site().cell(),List.of(neighbour));
            assertEquals(0,CableConnections.openFaces(cable,p->nearby.getOrDefault(p,List.of()),Map.of(id,port)::get));
            assertEquals(16,CableConnections.openFaces(cable,p->nearby.getOrDefault(p,List.of()),Map.<UUID,DataPort>of()::get));
            assertEquals(32,CableConnections.openFaces(cable,p->List.of(),Map.of(id,port)::get));
            assertEquals(48,CableConnections.openFaces(cable,p->List.of(),Map.<UUID,DataPort>of()::get));
            assertTrue(CableConnections.binds(cable,port));
            var moved=new DataPort(id,port.kind(),port.host(),port.dimension(),port.owner(),port.centre(),port.cell(),port.face(),3,0);
            assertFalse(CableConnections.binds(cable,moved));
        }
    }
    @Test void coincidentOffsetsJoinButDifferentOffsetsAndMediaStayOpen(){
        var first=cable(BlockPos.ZERO,0,48,0,CableMedium.DATA,Map.of());
        var second=cable(new BlockPos(1,0,0),0,48,0,CableMedium.DATA,Map.of());
        assertTrue(CableConnections.meets(first,Direction.EAST,second));
        var shifted=new DataBus.Cable(second.site(),second.geometry().connect(Direction.WEST.ordinal(),7,-4),0,Map.of());
        assertFalse(CableConnections.meets(first,Direction.EAST,shifted));
        assertFalse(CableConnections.meets(first,Direction.EAST,cable(second.site().cell(),0,48,0,CableMedium.POWER,Map.of())));
        assertEquals(48,CableConnections.openFaces(first,p->p.equals(second.site().cell())?List.of(shifted):List.of(),id->null));
    }
    @Test void unjointedRackArmsAndImplicitBlockEndsRemainVisible(){
        var rack=cable(BlockPos.ZERO,1,16,48,CableMedium.DATA,Map.of());
        var neighbour=cable(new BlockPos(1,0,0),0,48,0,CableMedium.DATA,Map.of());
        assertEquals(48,CableConnections.openFaces(rack,p->List.of(neighbour),id->null),"An unregistered arm must not look connected");
        assertEquals(48,CableConnections.openFaces(cable(BlockPos.ZERO,0,0,0,CableMedium.DATA,Map.of()),p->List.of(),id->null));
        assertEquals(12,CableConnections.openFaces(cable(BlockPos.ZERO,0,4,0,CableMedium.DATA,Map.of()),p->List.of(),id->null));
    }
    private static DataBus.Cable cable(BlockPos cell,int lane,int joints,int arms,CableMedium medium,Map<Direction,UUID> endpoints){
        return new DataBus.Cable(new DataBus.Site("minecraft:overworld",cell,lane),new ConduitGeometry.Key(joints,0,medium),arms,endpoints);
    }
}
