import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.world.level.block.LineShapes;
import net.minecraft.world.phys.shapes.*;
import java.lang.management.*;
import java.util.*;

public class ShapeProbe {
    static VoxelShape previous(ConduitGeometry.Key key){
        var shape=Shapes.empty();
        for(var p:ConduitGeometry.polygons(key)){
            var b=new double[]{Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.NEGATIVE_INFINITY};
            var v=p.positions();for(int i=0;i<v.length;i++){int k=i%3;b[k]=Math.min(b[k],v[i]);b[k+3]=Math.max(b[k+3],v[i]);}
            for(int k=0;k<3;k++){b[k]-=.012;b[k+3]+=.012;}
            shape=Shapes.or(shape,Shapes.box(b[0],b[1],b[2],b[3],b[4],b[5]));
        }return shape.optimize();
    }
    public static void main(String[] args){
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();bean.setThreadAllocatedMemoryEnabled(true);
        for(int mask:new int[]{48,17,52}){
            var key=new ConduitGeometry.Key(mask,0,CableMedium.POWER);long cpu=bean.getCurrentThreadCpuTime(),bytes=bean.getThreadAllocatedBytes(Thread.currentThread().threadId()),time=System.nanoTime();
            var old=previous(key);System.out.printf(Locale.ROOT,"OLD mask=%d wall_ms=%.3f cpu_ms=%.3f alloc_MB=%.3f boxes=%d%n",mask,(System.nanoTime()-time)/1e6,(bean.getCurrentThreadCpuTime()-cpu)/1e6,(bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-bytes)/1e6,old.toAabbs().size());
            cpu=bean.getCurrentThreadCpuTime();bytes=bean.getThreadAllocatedBytes(Thread.currentThread().threadId());time=System.nanoTime();
            var current=LineShapes.shape(mask,5,PipeSizes.code(CableMedium.POWER.outside()),0);System.out.printf(Locale.ROOT,"NEW mask=%d wall_ms=%.3f cpu_ms=%.3f alloc_MB=%.3f boxes=%d%n",mask,(System.nanoTime()-time)/1e6,(bean.getCurrentThreadCpuTime()-cpu)/1e6,(bean.getThreadAllocatedBytes(Thread.currentThread().threadId())-bytes)/1e6,current.toAabbs().size());
        }
    }
}
