from pathlib import Path
p=Path('src/main/java/com/wormzjl/icheme/runtime/control/ControlService.java');s=p.read_text(encoding='utf-8')
s=s.replace('import com.wormzjl.icheme.runtime.instrument.*;','import com.wormzjl.icheme.runtime.instrument.*;\nimport com.wormzjl.icheme.science.equipment.*;\nimport com.wormzjl.icheme.world.level.block.EquipmentStructure;')
s=s.replace('    private DataBus bus=', '    private final Map<Long,BuiltInInputs.Binding> bindings=new HashMap<>();\n    private final Map<Long,Long> aliases=new HashMap<>();\n    private DataBus bus=')
at=s.index('        var r=world.registrations().get(host.identity());',s.index('public void bind('))
s=s[:at]+'''        if(host.entity() instanceof EquipmentShellBlockEntity shell&&shell.shape()!=null){
            var nodes=ShapeRasterizer.fluidCells(shell.shape());var ids=new ArrayList<Long>();
            for(var node:nodes){var at=shell.origin().offset(node.x(),node.y(),node.z());var pos=new PhysicalFluidTopology.Position(shell.getLevel().dimension().location().toString(),at.getX(),at.getY(),at.getZ());ids.add(world.at(pos).map(r->r.device().id()).orElse(0L));}
            if(!ids.isEmpty()&&ids.getFirst()>0){long primary=ids.getFirst();bindings.put(primary,new BuiltInInputs.Binding(primary,shell.shape(),new PhysicalFluidTopology.Position(shell.getLevel().dimension().location().toString(),shell.origin().getX(),shell.origin().getY(),shell.origin().getZ()),ids));for(long id:ids)if(id>0)aliases.put(id,primary);
                if(host.identity()!=primary){if(shell.instruments().dataPort()!=null&&!facts.wired(shell.instruments().dataPort().id())){removeHost(host.identity());shell.instruments().dataPort(null);syncPort(host);}var cell=nodes.getFirst();var pos=shell.origin().offset(cell.x(),cell.y(),cell.z());if(shell.getLevel().getBlockEntity(pos) instanceof EquipmentShellBlockEntity first)bind(new InstrumentHosts.Host(primary,first,InstrumentHosts.reservoirMounts(first),false));return;}
            }
        }
''' +s[at:]
# avoid reservoirMounts access private and method signature (host.mounts unused for service). use List.of
s=s.replace('InstrumentHosts.reservoirMounts(first)','List.of()')
s=s.replace('if(!actuator&&host.metadata().sensors().isEmpty()&&host.metadata().dataPort()==null)return;','if(!actuator&&host.metadata().sensors().isEmpty()&&host.metadata().dataPort()==null&&!(host.entity() instanceof EquipmentShellBlockEntity shell&&shell.shape()!=null&&ShapeRasterizer.services(shell.shape()).data()!=null))return;')
s=s.replace('    public void bindMotor(EquipmentShellBlockEntity cell,ShaftUnit unit) {','''    public void bindColumn(EquipmentShellBlockEntity cell){
        if(cell.shape()==null||cell.shape().kind()!=EquipmentShape.Kind.COLUMN)return;
        var existing=cell.instruments().dataPort();long id=existing==null?world.reserveControlIdentity():existing.host();
        var origin=cell.origin();bindings.put(id,new BuiltInInputs.Binding(id,cell.shape(),new PhysicalFluidTopology.Position(cell.getLevel().dimension().location().toString(),origin.getX(),origin.getY(),origin.getZ()),List.of()));
        bind(new InstrumentHosts.Host(id,cell,List.of(),false));
    }
    public void bindMotor(EquipmentShellBlockEntity cell,ShaftUnit unit) {
        var origin=cell.origin();bindings.put(unit.id(),new BuiltInInputs.Binding(unit.id(),cell.shape(),new PhysicalFluidTopology.Position(cell.getLevel().dimension().location().toString(),origin.getX(),origin.getY(),origin.getZ()),List.of()));''')
s=s.replace('var port=equipmentPorts.get(host);if(port==null)return Set.of();','var port=equipmentPorts.get(aliases.getOrDefault(host,host));if(port==null)return Set.of();')
s=s.replace('return Set.copyOf(result);','for(var a:aliases.entrySet())if(result.contains(a.getValue()))result.add(a.getKey());return Set.copyOf(result);',1)
s=s.replace('public void removeHost(long host){var port=', 'public void removeHost(long host){bindings.remove(host);aliases.entrySet().removeIf(a->a.getKey()==host||a.getValue()==host);var port=')
s=s.replace('var sensors=new HashMap<UUID,Sensor.Installed>();world.instruments().allSensors().forEach(s->{facts.identify(s);sensors.put(s.instance(),s);});','''var sensors=new HashMap<UUID,ControlInput>();world.instruments().allSensors().forEach(s->{facts.identify(s);sensors.put(s.instance(),ControlInput.installed(s));});
        for(var binding:bindings.values())for(var input:BuiltInInputs.channels(binding))sensors.put(input.instance(),input);
        for(var entry:equipmentPorts.entrySet())if(!bindings.containsKey(entry.getKey())){var r=world.registrations().get(entry.getKey());if(r!=null&&r.device().control() instanceof FlowControl.Mover)for(var input:BuiltInInputs.inline(entry.getKey()))sensors.put(input.instance(),input);}''')
s=s.replace('        for(long host:needed)for(var r:world.instruments().sensorReadings(host,world.view(host)))readings.put(r.instance(),r);','''        var views=new HashMap<Long,FluidView>();
        for(long host:needed)for(var r:world.instruments().sensorReadings(host,views.computeIfAbsent(host,world::view)))readings.put(r.instance(),r);
        for(var input:sensors.values())if(input.physical()==null&&needed.contains(input.host()))readings.put(input.instance(),BuiltInInputs.read(input,world,bindings.get(input.host()),views));''')
s=s.replace('private boolean wired(UUID controller,long host){var port=equipmentPorts.get(host);','private boolean wired(UUID controller,long host){var port=equipmentPorts.get(aliases.getOrDefault(host,host));')
s=s.replace('ControlFacts.Panel panel,Sensor.Installed sensor','ControlFacts.Panel panel,ControlInput sensor')
s=s.replace('world.controlCoupled(sensor.key().host(),s.output())','world.controlCoupled(sensor.source(),s.output())')
s=s.replace('||sensor.key().host()!=s.output())','||sensor.source()!=s.output()||sensor.physical()==null&&!sensor.role().endsWith("OUTLET"))')
s=s.replace('private void sensorLocation(CompoundTag tag,Sensor.Installed sensor){','private void sensorLocation(CompoundTag tag,ControlInput sensor){')
s=s.replace('Map<UUID,Sensor.Installed> sensors','Map<UUID,ControlInput> sensors')
s=s.replace('sorted(Comparator.comparingLong(s->facts.sensorId(s.instance()).number()))','sorted(Comparator.comparing(s->s.label(facts)))')
s=s.replace('facts.sensorId(s.instance()).label()+" / "+s.kind().name()','s.label(facts)')
s=s.replace('var hostIds=bus().hosts(p.id(),facts::port);','var hostIds=new HashSet<>(bus().hosts(p.id(),facts::port));for(var a:aliases.entrySet())if(hostIds.contains(a.getValue()))hostIds.add(a.getKey());')
p.write_text(s,encoding='utf-8')
# hooks for column structural socket
p=Path('src/main/java/com/wormzjl/icheme/world/level/block/entity/EquipmentShellBlockEntity.java');s=p.read_text(encoding='utf-8').replace('com.wormzjl.icheme.runtime.assembly.AssemblyService.controllerLoaded(serverLevel, worldPosition, shape);','com.wormzjl.icheme.runtime.assembly.AssemblyService.controllerLoaded(serverLevel, worldPosition, shape);\n            if(shape.kind()==EquipmentShape.Kind.COLUMN)FluidWorldAuthority.find(serverLevel.getServer()).ifPresent(w->w.controls().bindColumn(this));');p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/wormzjl/icheme/world/level/block/EquipmentStructure.java');s=p.read_text(encoding='utf-8').replace('        reconnect(level, vessel);\n        refreshNozzles(level, vessel);','        if(shape.kind()==EquipmentShape.Kind.COLUMN&&rows>=shape.sizeY()){var c=layout.controller();if(level.getBlockEntity(origin.offset(c.x(),c.y(),c.z())) instanceof EquipmentShellBlockEntity cell)FluidWorldAuthority.find(level.getServer()).ifPresent(w->w.controls().bindColumn(cell));}\n        reconnect(level, vessel);\n        refreshNozzles(level, vessel);',1)
s=s.replace('            if (level instanceof ServerLevel server && vessel.shape().fluidDevice()) unregister(server, vessel);','''            if(level instanceof ServerLevel server&&vessel.shape().kind()==EquipmentShape.Kind.COLUMN){var c=ShapeRasterizer.layout(vessel.shape()).controller();if(level.getBlockEntity(vessel.origin().offset(c.x(),c.y(),c.z())) instanceof EquipmentShellBlockEntity cell&&cell.instruments().dataPort()!=null)FluidWorldAuthority.find(server.getServer()).ifPresent(w->w.controls().removeHost(cell.instruments().dataPort().host()));}
            if (level instanceof ServerLevel server && vessel.shape().fluidDevice()) unregister(server, vessel);''');p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/wormzjl/icheme/runtime/control/BuiltInInputs.java');s=p.read_text(encoding='utf-8').replace('for(String end:List.of("top","bottom"))stream(out,host,host,end,"COLUMN:"+end,false);','for(String end:List.of("top","bottom"))stream(out,host,host,end,"COLUMN:"+end,false);stream(out,host,host,"reflux","REFLUX",true);').replace('input.role().startsWith("COLUMN")&&binding!=null','(input.role().startsWith("COLUMN")||input.role().equals("REFLUX"))&&binding!=null');p.write_text(s,encoding='utf-8')
