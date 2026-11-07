package com.wormzjl.icheme.runtime.control;

import com.wormzjl.icheme.runtime.instrument.InstrumentHosts;
import com.wormzjl.icheme.runtime.instrument.Sensor;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import com.wormzjl.icheme.world.level.block.ControlPanelBlock;
import com.wormzjl.icheme.world.level.block.entity.ControlPanelBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Physical connectors use the same signed 1/32 m face coordinates as off-centre pipe entries. */
public final class DataPorts {
    private DataPorts(){}
    private record Mount(Vec3 centre,Direction outward){}
    private static Mount equipmentMount(InstrumentHosts.Host host){
        if(host.entity() instanceof EquipmentShellBlockEntity shell&&shell.shape()!=null){
            var socket=DataSocketGeometry.equipment(shell.shape());
            if(socket!=null)return new Mount(new Vec3(socket.x()+shell.origin().getX(),socket.y()+shell.origin().getY(),socket.z()+shell.origin().getZ()),Direction.values()[socket.face()]);
        }
        if(host.pipe()){
            for(var sensor:host.metadata().sensors())for(var mount:Sensor.mounts(host,sensor.kind()))if(mount.id().equals(sensor.key().mount()))
                return new Mount(mount.point(.14,0,0),Direction.getNearest(mount.right().x,mount.right().y,mount.right().z));
            if(host.entity() instanceof FluidDeviceBlockEntity pipe){
                var state=pipe.getBlockState();var key=new LineGeometry.Key(TopologyCompiler.Kind.PIPE,state.getValue(FluidDeviceBlock.FACING).ordinal(),FluidDeviceBlock.connectionMask(state),PipeSizes.code(PipeSizes.outside(pipe.bore())),0,0,false);
                List<Mesh.Polygon> polygons=List.of();
                if(FluidDeviceBlock.hasPressureValve(state))polygons=ValveGeometry.pressure(key,false);
                else if(FluidDeviceBlock.hasPump(state)||FluidDeviceBlock.hasCompressor(state))polygons=InlineMoverGeometry.polygons(key,FluidDeviceBlock.hasCompressor(state),false);
                var socket=DataSocketGeometry.surface(polygons);
                if(socket!=null)return new Mount(new Vec3(socket.x()+pipe.getBlockPos().getX(),socket.y()+pipe.getBlockPos().getY(),socket.z()+pipe.getBlockPos().getZ()),Direction.values()[socket.face()]);
            }
        }
        var mount=host.mounts().stream().filter(m->!m.top()).findFirst().orElse(host.mounts().stream().findFirst().orElse(null));
        var outward=mount==null?Direction.NORTH:mount.face();
        var centre=mount==null?host.entity().getBlockPos().getCenter().add(0,.2,-.51):mount.centre().add(outward.getAxis()==Direction.Axis.X?0:.28,0,outward.getAxis()==Direction.Axis.X?.28:0);
        return new Mount(centre,outward);
    }
    public static DataPort equipment(InstrumentHosts.Host host,boolean actuator){var m=equipmentMount(host);return create(UUID.randomUUID(),actuator?DataPort.Kind.ACTUATOR:DataPort.Kind.SENSOR,host.identity(),host.entity().getLevel(),host.entity().getBlockPos(),m.centre(),m.outward());}
    public static DataPort refresh(DataPort old,InstrumentHosts.Host host,java.util.function.Predicate<UUID> wired){
        var m=equipmentMount(host);
        boolean connected=wired.test(old.id());
        if(connected&&host.entity() instanceof EquipmentShellBlockEntity shell&&shell.shape()!=null){
            var socket=DataSocketGeometry.drum(shell.shape(),old.face().getOpposite().ordinal());
            if(socket!=null)m=new Mount(new Vec3(socket.x()+shell.origin().getX(),socket.y()+shell.origin().getY(),socket.z()+shell.origin().getZ()),old.face().getOpposite());
        }
        return remount(old,create(old.id(),old.kind(),old.host(),host.entity().getLevel(),old.owner(),m.centre(),m.outward()),connected);
    }
    /** Existing cable face points are built facts. Do not swap a wired connector to another side of its equipment. */
    public static DataPort remount(DataPort old,DataPort current,boolean wired){
        if(wired&&(old.face()!=current.face()||current.centre().distanceToSqr(old.cell().getCenter())>25))return old;
        return new DataPort(old.id(),current.kind(),current.host(),current.dimension(),current.owner(),current.centre(),
                wired?old.cell():current.cell(),wired?old.face():current.face(),wired?old.u():current.u(),wired?old.v():current.v());
    }
    public static Direction outward(Level level,DataPort port){
        // A live mount can move within its face, but remount never flips a wired connector to another side.
        return port.face().getOpposite();
    }
    public static DataPort motor(EquipmentShellBlockEntity cell,com.wormzjl.icheme.runtime.fluid.ShaftUnit unit){
        return equipment(new InstrumentHosts.Host(unit.id(),cell,List.of(),false),true);
    }
    public static DataPort panel(ControlPanelBlockEntity panel){
        return panel(panel,panel.identity());
    }
    public static DataPort panel(ControlPanelBlockEntity panel,UUID identity){
        var outward=panel.getBlockState().getValue(ControlPanelBlock.FACING).getOpposite();
        var normal=Vec3.atLowerCornerOf(outward.getNormal());
        return create(identity,panel.dcs()?DataPort.Kind.DCS:DataPort.Kind.CONTROLLER,0,panel.getLevel(),
                panel.getBlockPos(),panel.getBlockPos().getCenter().add(normal.scale((panel.dcs()?.375:.3125)+.005)),outward);
    }
    public static DataPort power(UUID id,DataPort.Kind kind,Level level,BlockPos owner,Vec3 centre,Direction outward){if(!kind.power())throw new IllegalArgumentException("Not a power port");return create(id,kind,0,level,owner,centre,outward);}
    private static DataPort create(UUID id,DataPort.Kind kind,long host,Level level,BlockPos owner,Vec3 centre,Direction outward){
        var normal=Vec3.atLowerCornerOf(outward.getNormal());
        // A gland may overlap its neighbouring pipe face, but an empty overhang cell must not put the entry behind it.
        var ahead=centre.add(normal.scale(-.075));int axis=outward.getAxis().ordinal();
        int boundary=outward.getAxisDirection()==Direction.AxisDirection.POSITIVE?(int)Math.ceil(ahead.get(outward.getAxis())):(int)Math.floor(ahead.get(outward.getAxis()))-1;
        var cell=new BlockPos(axis==0?boundary:(int)Math.floor(centre.x),axis==1?boundary:(int)Math.floor(centre.y),axis==2?boundary:(int)Math.floor(centre.z));
        var first=cell;
        // Clear the casting, without loading chunks, moving blocks or hiding the socket behind a solid cell.
        for(int step=0;step<8&&level.hasChunkAt(cell)&&!level.getBlockState(cell).canBeReplaced();step++)cell=cell.relative(outward);
        if(centre.distanceToSqr(cell.getCenter())>16)cell=first;
        double[] local={centre.x-cell.getX()-.5,centre.y-cell.getY()-.5,centre.z-cell.getZ()-.5};
        int u=(int)Math.clamp(Math.round(local[(axis+1)%3]*32),-14,14),v=(int)Math.clamp(Math.round(local[(axis+2)%3]*32),-14,14);
        return new DataPort(id,kind,host,level.dimension().location().toString(),owner,centre,cell,outward.getOpposite(),u,v);
    }
    public static int packed(DataPort port){return (port.u()&31)|((port.v()&31)<<5);}
}
