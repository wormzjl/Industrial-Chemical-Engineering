package com.wormzjl.icheme.runtime.control;

import com.wormzjl.icheme.runtime.instrument.InstrumentHosts;
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
    public static DataPort equipment(InstrumentHosts.Host host,boolean actuator){
        var mount=host.mounts().stream().findFirst().orElse(null);
        var outward=mount==null?Direction.NORTH:mount.face();
        var centre=mount==null?host.entity().getBlockPos().getCenter().add(0,.2,-.51):
                mount.centre().add(outward.getAxis()==Direction.Axis.X?0:.28,0,outward.getAxis()==Direction.Axis.X?.28:0);
        return create(UUID.randomUUID(),actuator?DataPort.Kind.ACTUATOR:DataPort.Kind.SENSOR,host.identity(),
                host.entity().getLevel(),host.entity().getBlockPos(),centre,outward);
    }
    public static DataPort motor(EquipmentShellBlockEntity cell,com.wormzjl.icheme.runtime.fluid.ShaftUnit unit) {
        var shaft=unit.ends().getFirst();var face=Direction.fromDelta(shaft.dx(),shaft.dy(),shaft.dz());
        var centre=new Vec3(shaft.cell().x()+.5,shaft.cell().y()+.8,shaft.cell().z()+.5);
        return create(UUID.randomUUID(),DataPort.Kind.ACTUATOR,unit.id(),cell.getLevel(),cell.getBlockPos(),centre,face);
    }
    public static DataPort panel(ControlPanelBlockEntity panel){
        return panel(panel,panel.identity());
    }
    public static DataPort panel(ControlPanelBlockEntity panel,UUID identity){
        var outward=panel.getBlockState().getValue(ControlPanelBlock.FACING).getOpposite();
        var normal=Vec3.atLowerCornerOf(outward.getNormal());
        return create(identity,panel.dcs()?DataPort.Kind.DCS:DataPort.Kind.CONTROLLER,0,panel.getLevel(),
                panel.getBlockPos(),panel.getBlockPos().getCenter().add(normal.scale((panel.dcs()?.375:.3125)+.005)).add(0,-.22,0),outward);
    }
    private static DataPort create(UUID id,DataPort.Kind kind,long host,Level level,BlockPos owner,Vec3 centre,Direction outward){
        var normal=Vec3.atLowerCornerOf(outward.getNormal());
        var cell=BlockPos.containing(centre.add(normal.scale(.2)));
        // Clear the casting, without loading chunks, moving blocks or hiding the socket behind a solid cell.
        for(int step=0;step<8&&level.hasChunkAt(cell)&&!level.getBlockState(cell).canBeReplaced();step++)cell=cell.relative(outward);
        if(centre.distanceToSqr(cell.getCenter())>16)cell=BlockPos.containing(centre.add(normal.scale(.2)));
        int axis=outward.getAxis().ordinal(); // Direction.Axis order X, Y, Z.
        double[] local={centre.x-cell.getX()-.5,centre.y-cell.getY()-.5,centre.z-cell.getZ()-.5};
        int u=(int)Math.clamp(Math.round(local[(axis+1)%3]*32),-14,14),v=(int)Math.clamp(Math.round(local[(axis+2)%3]*32),-14,14);
        return new DataPort(id,kind,host,level.dimension().location().toString(),owner,centre,cell,outward.getOpposite(),u,v);
    }
    public static int packed(DataPort port){return (port.u()&31)|((port.v()&31)<<5);}
}
