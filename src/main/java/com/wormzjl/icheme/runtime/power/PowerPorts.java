package com.wormzjl.icheme.runtime.power;

import com.wormzjl.icheme.runtime.control.*;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.world.level.block.*;
import com.wormzjl.icheme.world.level.block.entity.*;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Physical FE terminals, independent of process readings and sensor identities. */
public final class PowerPorts {
    private PowerPorts(){}
    public static boolean eligible(BlockEntity be){
        if(be instanceof CreativePowerBlockEntity)return true;
        if(be instanceof FluidDeviceBlockEntity pipe)return FluidDeviceBlock.hasPump(pipe.getBlockState())||FluidDeviceBlock.hasCompressor(pipe.getBlockState());
        if(be instanceof EquipmentShellBlockEntity shell&&shell.shape()!=null){
            var terminal=ShapeRasterizer.terminal(shell.shape());
            return terminal!=null&&terminal.equals(shell.cell())&&switch(shell.shape().kind()){
                case MOTOR,GENERATOR,AIR_COOLER,INDUCED_DRAFT_COOLING_TOWER->true;default->false;};
        }return false;
    }
    public static DataPort create(BlockEntity be){
        var level=be.getLevel();if(level==null||!eligible(be))return null;
        Vec3 centre;Direction outward;DataPort.Kind kind=DataPort.Kind.POWER_INPUT;
        if(be instanceof EquipmentShellBlockEntity shell){
            var socket=DataSocketGeometry.power(shell.shape());if(socket==null)return null;
            centre=new Vec3(socket.x()+shell.origin().getX(),socket.y()+shell.origin().getY(),socket.z()+shell.origin().getZ());outward=Direction.values()[socket.face()];
            if(shell.shape().kind()==EquipmentShape.Kind.GENERATOR)kind=DataPort.Kind.POWER_OUTPUT;
        }else if(be instanceof FluidDeviceBlockEntity pipe){
            var s=pipe.getBlockState();var key=new LineGeometry.Key(pipe.kind(),s.getValue(FluidDeviceBlock.FACING).ordinal(),FluidDeviceBlock.connectionMask(s),PipeSizes.code(PipeSizes.outside(pipe.bore())),0,0,false);
            var socket=DataSocketGeometry.surface(InlineMoverGeometry.polygons(key,FluidDeviceBlock.hasCompressor(s),false).stream().filter(p->p.material()==Mesh.Material.MOTOR).toList(),Direction.WEST.ordinal());if(socket==null)return null;
            centre=new Vec3(pipe.getBlockPos().getX()+socket.x(),pipe.getBlockPos().getY()+socket.y(),pipe.getBlockPos().getZ()+socket.z());outward=Direction.values()[socket.face()];
        }else{centre=be.getBlockPos().getCenter().add(.52,0,0);outward=Direction.EAST;kind=DataPort.Kind.POWER_OUTPUT;}
        return DataPorts.power(UUID.randomUUID(),kind,level,be.getBlockPos(),centre,outward);
    }
    public static DataPort refresh(DataPort old,BlockEntity be,boolean wired){
        var current=create(be);if(current==null)return old;
        return DataPorts.remount(old,current,wired);
    }
    /** An ordinary FE-capable block face is also a legal endpoint; previews never create saved ports. */
    public static DataPort external(Level level,BlockPos owner,Direction face){
        if(!level.hasChunkAt(owner))return null;
        var be=level.getBlockEntity(owner);
        if(be instanceof ConduitBlockEntity||be instanceof PipeRackBlockEntity||be instanceof EquipmentShellBlockEntity||be instanceof FluidDeviceBlockEntity||be instanceof ControlPanelBlockEntity||be instanceof CreativePowerBlockEntity)return null;
        if((!level.isClientSide()||be==null)&&level.getCapability(Capabilities.EnergyStorage.BLOCK,owner,face)==null)return null;
        var id=UUID.nameUUIDFromBytes(("icheme:fe:"+level.dimension().location()+":"+owner.asLong()+":"+face.getName()).getBytes(StandardCharsets.UTF_8));
        return new DataPort(id,DataPort.Kind.POWER_BOTH,0,level.dimension().location().toString(),owner,
                owner.getCenter().add(Vec3.atLowerCornerOf(face.getNormal()).scale(.52)),owner.relative(face),face.getOpposite(),0,0);
    }
}
