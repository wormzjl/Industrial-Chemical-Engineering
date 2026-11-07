package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.runtime.control.*;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.world.level.block.entity.*;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.*;
import java.util.*;
import java.util.function.*;

/** Socket picking is independent of the owning block cell, which may be across a large machine. */
public final class LinePortTarget {
    private LinePortTarget(){}
    public static DataPort select(Collection<DataPort> ports,Vec3 eye,Vec3 look,double range,double solid,
                                  Predicate<DataPort> ownOccluder,Function<DataPort,Direction> outward){
        DataPort picked=null;double nearest=Double.POSITIVE_INFINITY;var end=eye.add(look.scale(range));
        for(var port:ports){
            var face=outward.apply(port);var front=Vec3.atLowerCornerOf(face.getNormal());
            if(eye.subtract(port.centre()).dot(front)<-.02)continue;
            var hit=new AABB(port.centre(),port.centre()).inflate(.18).clip(eye,end).orElse(null);if(hit==null)continue;
            double distance=hit.distanceTo(eye);
            if(distance>=nearest||distance>solid+.025&&!ownOccluder.test(port))continue;
            picked=port;nearest=distance;
        }return picked;
    }
    public static DataPort aim(Level level,Collection<DataPort> ports,Vec3 eye,Vec3 look,double range,BlockHitResult hit){
        double solid=hit==null||hit.getType()!=HitResult.Type.BLOCK?Double.POSITIVE_INFINITY:hit.getLocation().distanceTo(eye);
        return select(ports,eye,look,range,solid,p->hit!=null&&ownOccluder(level,hit.getBlockPos(),p),p->DataPorts.outward(level,p));
    }
    private static boolean ownOccluder(Level level,BlockPos obstruction,DataPort port){
        if(obstruction.equals(port.owner()))return true;
        if(obstruction.equals(port.cell())&&level.getBlockEntity(obstruction) instanceof ConduitBlockEntity cable
                &&cable.geometry().medium()==port.kind().medium())return true;
        if(!(level.getBlockEntity(port.owner()) instanceof EquipmentShellBlockEntity owner)||owner.shape()==null)return false;
        if(level.getBlockEntity(obstruction) instanceof EquipmentShellBlockEntity cell)return Objects.equals(owner.origin(),cell.origin());
        if(!(level.getBlockState(obstruction).getBlock() instanceof EquipmentInteriorBlock))return false;
        var p=obstruction.subtract(owner.origin());
        return ShapeRasterizer.layout(owner.shape()).interior().contains(new ShapeRasterizer.Cell(p.getX(),p.getY(),p.getZ()));
    }
}
