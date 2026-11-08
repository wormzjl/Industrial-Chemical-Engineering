from pathlib import Path
p=Path('src/main/java/com/wormzjl/icheme/runtime/control/DataPorts.java');s=p.read_text(encoding='utf-8');a=s.index('    public static DataPort equipment(');b=s.index('    public static DataPort panel(',a)
s=s[:a]+'''    private record Mount(Vec3 centre,Direction outward){}
    private static Mount equipmentMount(InstrumentHosts.Host host){
        if(host.entity() instanceof EquipmentShellBlockEntity shell&&shell.shape()!=null){
            var socket=com.wormzjl.icheme.science.equipment.DataSocketGeometry.equipment(shell.shape());
            if(socket!=null)return new Mount(new Vec3(socket.x()+shell.origin().getX(),socket.y()+shell.origin().getY(),socket.z()+shell.origin().getZ()),Direction.values()[socket.face()]);
        }
        if(host.pipe()){
            for(var sensor:host.metadata().sensors())for(var mount:com.wormzjl.icheme.runtime.instrument.Sensor.mounts(host,sensor.kind()))if(mount.id().equals(sensor.key().mount()))
                return new Mount(mount.point(.14,0,0),Direction.getNearest(mount.right().x,mount.right().y,mount.right().z));
            if(host.entity() instanceof com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity pipe){
                var state=pipe.getBlockState();var key=new com.wormzjl.icheme.science.equipment.LineGeometry.Key(com.wormzjl.icheme.science.fluid.topology.TopologyCompiler.Kind.PIPE,state.getValue(com.wormzjl.icheme.world.level.block.FluidDeviceBlock.FACING).ordinal(),com.wormzjl.icheme.world.level.block.FluidDeviceBlock.connectionMask(state),com.wormzjl.icheme.science.equipment.PipeSizes.code(com.wormzjl.icheme.science.equipment.PipeSizes.outside(pipe.bore())),0,0,false);
                java.util.List<com.wormzjl.icheme.science.equipment.Mesh.Polygon> polygons=java.util.List.of();
                if(com.wormzjl.icheme.world.level.block.FluidDeviceBlock.hasPressureValve(state))polygons=com.wormzjl.icheme.science.equipment.ValveGeometry.pressure(key,false);
                else if(com.wormzjl.icheme.world.level.block.FluidDeviceBlock.hasPump(state)||com.wormzjl.icheme.world.level.block.FluidDeviceBlock.hasCompressor(state))polygons=com.wormzjl.icheme.science.equipment.InlineMoverGeometry.polygons(key,com.wormzjl.icheme.world.level.block.FluidDeviceBlock.hasCompressor(state),false);
                var socket=com.wormzjl.icheme.science.equipment.DataSocketGeometry.surface(polygons);
                if(socket!=null)return new Mount(new Vec3(socket.x()+pipe.getBlockPos().getX(),socket.y()+pipe.getBlockPos().getY(),socket.z()+pipe.getBlockPos().getZ()),Direction.EAST);
            }
        }
        var mount=host.mounts().stream().filter(m->!m.top()).findFirst().orElse(host.mounts().stream().findFirst().orElse(null));
        var outward=mount==null?Direction.NORTH:mount.face();
        var centre=mount==null?host.entity().getBlockPos().getCenter().add(0,.2,-.51):mount.centre().add(outward.getAxis()==Direction.Axis.X?0:.28,0,outward.getAxis()==Direction.Axis.X?.28:0);
        return new Mount(centre,outward);
    }
    public static DataPort equipment(InstrumentHosts.Host host,boolean actuator){var m=equipmentMount(host);return create(UUID.randomUUID(),actuator?DataPort.Kind.ACTUATOR:DataPort.Kind.SENSOR,host.identity(),host.entity().getLevel(),host.entity().getBlockPos(),m.centre(),m.outward());}
    public static DataPort refresh(DataPort old,InstrumentHosts.Host host,java.util.function.Predicate<UUID> wired){
        var m=equipmentMount(host);if(old.centre().distanceToSqr(m.centre())<1e-12)return old;
        if(wired.test(old.id())){
            // Existing cable face points are built facts. Move only the enclosure, retaining its lead and endpoint identity.
            if(m.centre().distanceToSqr(old.cell().getCenter())>25)return old;
            return new DataPort(old.id(),old.kind(),old.host(),old.dimension(),old.owner(),m.centre(),old.cell(),old.face(),old.u(),old.v());
        }
        return create(old.id(),old.kind(),old.host(),host.entity().getLevel(),old.owner(),m.centre(),m.outward());
    }
    public static Direction outward(Level level,DataPort port){
        var host=InstrumentHosts.at(level,port.owner());if(host!=null)return equipmentMount(host).outward();
        if(level.getBlockEntity(port.owner()) instanceof EquipmentShellBlockEntity cell&&cell.shape()!=null){var socket=com.wormzjl.icheme.science.equipment.DataSocketGeometry.equipment(cell.shape());if(socket!=null)return Direction.values()[socket.face()];}
        return port.face().getOpposite();
    }
    public static DataPort motor(EquipmentShellBlockEntity cell,com.wormzjl.icheme.runtime.fluid.ShaftUnit unit){
        return equipment(new InstrumentHosts.Host(unit.id(),cell,java.util.List.of(),false),true);
    }
''' +s[b:];p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/wormzjl/icheme/runtime/control/ControlService.java');s=p.read_text(encoding='utf-8');needle='if(port==null){port=DataPorts.equipment(host,actuator);facts.port(port);equipmentPorts.put(host.identity(),port);}'
assert needle in s;s=s.replace(needle,needle+'''
        else{var refreshed=DataPorts.refresh(port,host,id->facts.cables().stream().anyMatch(c->c.endpoints().containsValue(id)));if(!refreshed.equals(port)){port=refreshed;facts.port(port);equipmentPorts.put(host.identity(),port);}}''')
needle='if(port==null){port=DataPorts.motor(cell,unit);facts.port(port);equipmentPorts.put(unit.id(),port);}'
assert needle in s;s=s.replace(needle,needle+'''
        else{var host=new InstrumentHosts.Host(unit.id(),cell,List.of(),false);var refreshed=DataPorts.refresh(port,host,id->facts.cables().stream().anyMatch(c->c.endpoints().containsValue(id)));if(!refreshed.equals(port)){port=refreshed;facts.port(port);equipmentPorts.put(unit.id(),port);}}''');p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/wormzjl/icheme/runtime/instrument/InstrumentHosts.java');s=p.read_text(encoding='utf-8');s=s.replace('shell.shape()!=null&&shell.fluidIdentity()>0))return;','shell.shape()!=null&&(shell.fluidIdentity()>0||shell.instruments().dataPort()!=null)))return;');p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/wormzjl/icheme/client/DataControlRenderer.java');s=p.read_text(encoding='utf-8');s=s.replace('private static final Map<DataPort,List<Mesh.Polygon>> PORTS=new HashMap<>();','private record PortMesh(List<Mesh.Polygon> polygons,net.minecraft.core.Direction outward){}\n    private static final Map<DataPort,PortMesh> PORTS=new HashMap<>();')
old='var polygons=PORTS.computeIfAbsent(port,p->DataPortGeometry.polygons(new double[]{0,0,0},new double[]{-p.face().getStepX(),-p.face().getStepY(),-p.face().getStepZ()}));\n            draw(pose,buffers,camera,port.centre(),polygons,0,255);'
new='''var mesh=PORTS.computeIfAbsent(port,p->{var out=DataPorts.outward(mc.level,p);boolean box=mc.level.getBlockEntity(p.owner()) instanceof EquipmentShellBlockEntity;return new PortMesh(DataPortGeometry.polygons(new double[]{0,0,0},new double[]{out.getStepX(),out.getStepY(),out.getStepZ()},box),out);});
            draw(pose,buffers,camera,port.centre(),mesh.polygons(),0,255);'''
assert old in s;s=s.replace(old,new)
s=s.replace('DataCableGeometry.lead(new double[]{-port.face().getStepX(),-port.face().getStepY(),-port.face().getStepZ()},','DataCableGeometry.lead(new double[]{mesh.outward().getStepX(),mesh.outward().getStepY(),mesh.outward().getStepZ()},')
s=s.replace('Rack lanes, flexible socket leads and chunk-baked cable blocks all sample the same insulating jacket.','Rack lanes, connector leads and chunk-baked conduit blocks use the same painted-metal finish.');p.write_text(s,encoding='utf-8')
