package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.runtime.control.DataPort;
import com.wormzjl.icheme.science.equipment.*;
import net.minecraft.core.*;
import net.minecraft.world.phys.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LineInteractionTest {
    private static DataPort port(Vec3 centre,Direction face){return new DataPort(new UUID(0,1),DataPort.Kind.POWER_INPUT,0,"minecraft:overworld",BlockPos.ZERO,centre,BlockPos.containing(centre),face.getOpposite(),0,0);}
    @Test void socketPickingUsesTheVisibleBoxAndAllowsItsOwnCoarseCasting(){
        var p=port(new Vec3(0,.5,0),Direction.EAST);var eye=new Vec3(3,.5,.15);var look=new Vec3(-1,0,0);
        assertEquals(p,LinePortTarget.select(List.of(p),eye,look,5,2.5,q->true,q->Direction.EAST));
        assertNull(LinePortTarget.select(List.of(p),eye,look,5,2.5,q->false,q->Direction.EAST),"an unrelated obstruction still blocks the socket");
        assertNull(LinePortTarget.select(List.of(p),new Vec3(-3,.5,0),new Vec3(1,0,0),5,0,q->true,q->Direction.EAST),"a far-side socket cannot be selected through the machine");
        assertNull(LinePortTarget.select(List.of(p),new Vec3(6,.5,0),look,5,Double.POSITIVE_INFINITY,q->true,q->Direction.EAST));
    }
    @Test void socketCornersCanBePickedWithoutComparingTheCentreToTheWallHit(){
        var p=port(new Vec3(0,.5,0),Direction.EAST);var eye=new Vec3(3,.65,.15);
        assertEquals(p,LinePortTarget.select(List.of(p),eye,new Vec3(-1,0,0),5,2.9,q->false,q->Direction.EAST));
    }
    @Test void pipeAndConduitShapesShareTheSameCachedEnvelope(){
        for(var medium:CableMedium.values()){
            int outside=PipeSizes.code(medium.outside());
            assertSame(LineShapes.shape(48,2,outside,0),LineShapes.shape(48,5,outside,0));
            assertSame(LineShapes.shape(16,4,outside,0),LineShapes.shape(48,5,outside,0));
        }
    }
    @Test void pickingAndBreakParticlesStayBoundedForEveryJunctionAndOffsets(){
        for(var medium:CableMedium.values())for(int mask=0;mask<64;mask++)for(long offsets:new long[]{0,LineGeometry.Key.offsets(4,12,-10)|LineGeometry.Key.offsets(5,-9,11)}){
            var boxes=LineSelection.boxes(mask,5,PipeSizes.code(medium.outside()),offsets);assertTrue(boxes.size()<=7);
            var shape=LineShapes.shape(mask,5,PipeSizes.code(medium.outside()),offsets);
            assertTrue(shape.toAabbs().size()<=96,"bounded particle source boxes: "+mask+" / "+shape.toAabbs().size());
            for(var b:shape.toAabbs())for(double v:new double[]{b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ})assertTrue(Double.isFinite(v)&&v>=0&&v<=1);
        }
    }
    @Test void ordinaryPipeSelectionKeepsItsPreviousHubAndArms(){
        for(int mask=1;mask<64;mask++){
            var shape=LineShapes.shape(mask,2,PipeSizes.code(.4),0);
            int normalized=LineSelection.joints(mask,2);
            double h=PipeSizes.outsideOf(PipeSizes.code(.4))/2,lo=.5-h,hi=.5+h;
            var expected=net.minecraft.world.phys.shapes.Shapes.box(lo,lo,lo,hi,hi,hi);
            for(var face:Direction.values())if((normalized&(1<<face.ordinal()))!=0){
                var p=face.getNormal();expected=net.minecraft.world.phys.shapes.Shapes.or(expected,net.minecraft.world.phys.shapes.Shapes.box(
                    p.getX()<0?0:lo,p.getY()<0?0:lo,p.getZ()<0?0:lo,p.getX()>0?1:hi,p.getY()>0?1:hi,p.getZ()>0?1:hi));
            }
            assertFalse(net.minecraft.world.phys.shapes.Shapes.joinIsNotEmpty(shape,expected,net.minecraft.world.phys.shapes.BooleanOp.NOT_SAME));
        }
    }
}
