from pathlib import Path
p=Path('src/main/java/com/wormzjl/icheme/runtime/control/ControlService.java');s=p.read_text(encoding='utf-8').replace('    private final Map<Long,Long> aliases=', '    private final Map<Long,java.lang.ref.WeakReference<EquipmentShellBlockEntity>> columnCells=new HashMap<>();\n    private final Map<Long,List<ControlInput>> memberInputs=new HashMap<>();\n    private final Map<Long,Set<Long>> columnMembers=new HashMap<>();\n    private final Map<Long,Long> aliases=')
s=s.replace('        var existing=cell.instruments().dataPort();long id=', '        var existing=cell.instruments().dataPort();long id=',1).replace('        bind(new InstrumentHosts.Host(id,cell,List.of(),false));','        columnCells.put(id,new java.lang.ref.WeakReference<>(cell));bind(new InstrumentHosts.Host(id,cell,List.of(),false));',1)
s=s.replace('        var readings=new HashMap<UUID,Sensor.Reading>();','''        refreshColumnMembers();for(var channels:memberInputs.values())for(var input:channels)sensors.put(input.instance(),input);
        var readings=new HashMap<UUID,Sensor.Reading>();''',1)
s=s.replace('private boolean wired(UUID controller,long host){var port=','private boolean wired(UUID controller,long host){for(var entry:columnMembers.entrySet())if(entry.getValue().contains(host)){var primary=equipmentPorts.get(entry.getKey());if(primary!=null&&bus().connected(controller,primary.id()))return true;}var port=')
s=s.replace('for(var a:aliases.entrySet())if(hostIds.contains(a.getValue()))hostIds.add(a.getKey());','for(var a:aliases.entrySet())if(hostIds.contains(a.getValue()))hostIds.add(a.getKey());for(var entry:columnMembers.entrySet())if(hostIds.contains(entry.getKey()))hostIds.addAll(entry.getValue());')
s=s.replace('bindings.remove(host);aliases.entrySet()','bindings.remove(host);columnCells.remove(host);memberInputs.remove(host);columnMembers.remove(host);aliases.entrySet()')
at=s.index('    /** Connected physical host IDs')
s=s[:at]+'''    /** Uses the cached assembly report and registration positions; never traverses pipes or loads a chunk. */
    private void refreshColumnMembers(){
        for(var entry:columnCells.entrySet()){
            var cell=entry.getValue().get();if(cell==null||cell.isRemoved()||!(cell.getLevel() instanceof net.minecraft.server.level.ServerLevel level))continue;
            var stored=com.wormzjl.icheme.runtime.assembly.AssemblyService.entry(level,cell.origin());if(stored==null||stored.report()==null){memberInputs.remove(entry.getKey());columnMembers.remove(entry.getKey());continue;}
            var inputs=new ArrayList<ControlInput>();var members=new HashSet<Long>();
            for(var slot:stored.report().slots())for(long placed:slot.units()){
                var pos=BlockPos.of(placed);if(!level.hasChunkAt(pos))continue;var vessel=EquipmentStructure.find(level,pos);if(vessel==null)continue;var ids=new ArrayList<Long>();
                for(var node:ShapeRasterizer.fluidCells(vessel.shape())){var at=vessel.origin().offset(node.x(),node.y(),node.z());var where=new PhysicalFluidTopology.Position(level.dimension().location().toString(),at.getX(),at.getY(),at.getZ());ids.add(world.at(where).map(r->r.device().id()).orElse(0L));}
                long primary=ids.stream().filter(i->i>0).findFirst().orElse(0L);if(primary==0)continue;members.addAll(ids.stream().filter(i->i>0).toList());
                var binding=new BuiltInInputs.Binding(primary,vessel.shape(),new PhysicalFluidTopology.Position(level.dimension().location().toString(),vessel.origin().getX(),vessel.origin().getY(),vessel.origin().getZ()),ids);
                String prefix=slot.slot().part()+"_"+(slot.slot().index()+1)+"_EQ"+primary+"_";
                for(var input:BuiltInInputs.channels(binding))inputs.add(ControlInput.builtIn(entry.getKey(),input.source(),prefix+input.channel(),input.kind(),input.role()));
            }
            memberInputs.put(entry.getKey(),List.copyOf(inputs));columnMembers.put(entry.getKey(),Set.copyOf(members));
        }
    }
''' +s[at:];p.write_text(s,encoding='utf-8')
# Instrument mounts have a radial upright normal, so bound all eight head corners, not merely two opposed corners.
p=Path('src/main/java/com/wormzjl/icheme/runtime/instrument/Instrument.java');s=p.read_text(encoding='utf-8');s=s.replace('public AABB bounds(){return new AABB(point(-.175,-.175,-.07),point(.175,.175,.07)).minmax(new AABB(tap.add(-.03,-.03,-.03),tap.add(.03,.03,.03))).inflate(.008);}','public AABB bounds(){var box=new AABB(centre,centre);for(double x:new double[]{-.175,.175})for(double y:new double[]{-.175,.175})for(double z:new double[]{-.07,.07}){var p=point(x,y,z);box=box.minmax(new AABB(p,p));}return box.minmax(new AABB(tap.add(-.11,-.11,-.11),tap.add(.11,.11,.11))).inflate(.008);}')
p.write_text(s,encoding='utf-8')
# Reinforce sphere mounting with the two approved leg clamps.
p=Path('src/main/java/com/wormzjl/icheme/science/equipment/EquipmentServices.java');s=p.read_text(encoding='utf-8');s=s.replace('anchor=surface.ray(2,new double[]{foot[0],1.5,foot[2]},-1,Set.of(Material.SUPPORT));','anchor=surface.ray(2,new double[]{foot[0],1.5,foot[2]},-1,Set.of(Material.SUPPORT));for(double y:new double[]{1.36,1.64})mesh.ring(Frame.of(foot,1,1),plan.wl()/2-.003,plan.wl()/2+.025,y-.025,y+.025,12,3,Material.SERVICE_CASE);');p.write_text(s,encoding='utf-8')
