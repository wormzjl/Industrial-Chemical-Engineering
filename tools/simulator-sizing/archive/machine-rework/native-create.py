from pathlib import Path
b=Path('src/main/java/com/wormzjl/icheme')
def edit(path,old,new):
 p=b/path;s=p.read_text(encoding='utf-8');assert old in s,(path,old[:70]);p.write_text(s.replace(old,new),encoding='utf-8')
edit('science/equipment/ProcessUnit.java','mesh.polygons()','mesh.facets()')
edit('science/equipment/ProcessUnit.java','new Mesh.Polygon(p.positions(),p.normals(),p.uvs(),Material.GENERATOR_PANEL)','new Mesh.Facet(p.positions(),p.normal(),p.min(),p.max(),p.shading(),Material.GENERATOR_PANEL,p.u(),p.v(),p.part())')
# Roles are disposable compiled topology; no saved part kind changes meaning.
p=b/'science/machine/ShaftNetwork.java';s=p.read_text(encoding='utf-8');old='List<End> ends, double ratio, double designSpeed) {';assert old in s;s=s.replace(old,'List<End> ends, double ratio, double designSpeed,Role role) {',1)
at=s.index('        public Part {')
s=s[:at]+'''        public Part(long id,Kind kind,Cell cell,int axis,List<End> ends,double ratio,double designSpeed){this(id,kind,cell,axis,ends,ratio,designSpeed,Role.PASSIVE);}
        public boolean driver(){return role==Role.SOURCE||role!=Role.LOAD&&kind.driver();}
        public boolean driven(){return role==Role.LOAD||role!=Role.SOURCE&&kind.driven();}
        public Part withRole(Role value,double rpm){return new Part(id,kind,cell,axis,ends,ratio,rpm,value);}
'''+s[at:]
s=s.replace('    public record Part(', '    public enum Role { PASSIVE, SOURCE, LOAD }\n    public record Part(',1)
s=s.replace('.filter(p -> p.kind().driver())','.filter(Part::driver)').replace('if (nodes.get(node)[1] >= 0) hasEnd = true;','if (nodes.get(node)[1] >= 0 || byId.get(nodes.get(node)[0]).role()!=Role.PASSIVE) hasEnd = true;')
s=s.replace('int start = lineOf[endNode.get(driverPart.id())[0]];','int start = lineOf[driverPart.kind()==Kind.SHAFT?shaftAt.get(driverPart.cell()):endNode.get(driverPart.id())[0]];')
s=s.replace('driverPart.kind() == Kind.CREATE_COUPLING && upward','(driverPart.kind() == Kind.CREATE_COUPLING||driverPart.role()==Role.SOURCE) && upward')
s=s.replace('else if (part.kind().driven() && seen.add(part.id()))','if (part.driven() && seen.add(part.id()))')
p.write_text(s,encoding='utf-8')
# Extended input is a queued event, not a fact. Old explicit couplings still use the original overload.
edit('runtime/fluid/ShaftTrains.java','public record CreateInput(PhysicalFluidTopology.Position cell, double rpm, double stress) implements Event {','''public record CreateInput(PhysicalFluidTopology.Position cell, double rpm, double stress,double demand,boolean connected) implements Event {
        public CreateInput(PhysicalFluidTopology.Position cell,double rpm,double stress){this(cell,rpm,stress,0,true);}''')
edit('runtime/fluid/ShaftTrains.java','!Double.isFinite(stress))','!Double.isFinite(stress)||!(demand>=0)||!Double.isFinite(demand))')
edit('runtime/fluid/ShaftTrains.java','    private final Map<PhysicalFluidTopology.Position, CreateInput> create = new HashMap<>();','''    private final Map<PhysicalFluidTopology.Position, CreateInput> create = new HashMap<>();
    private final Set<Long> createDrivers=new HashSet<>(),createLoads=new HashSet<>();''')
edit('runtime/fluid/ShaftTrains.java','        default Set<Long> linked(long motor) { return Set.of(); }','''        default Set<Long> linked(long motor) { return Set.of(); }
        default void output(PhysicalFluidTopology.Position port,double rpm,double watts) {}''')
edit('runtime/fluid/ShaftTrains.java','if (u == null || u.kind() != ShaftNetwork.Kind.CREATE_COUPLING) continue;','if (u == null || u.kind() != ShaftNetwork.Kind.CREATE_COUPLING&&u.kind()!=ShaftNetwork.Kind.SHAFT) continue;\n                    recompile=true;')
edit('runtime/fluid/ShaftTrains.java','        var compiled = ShaftNetwork.compile(units.values().stream().map(ShaftUnit::part).toList());','''        var parts=new HashMap<Long,ShaftNetwork.Part>();units.values().forEach(u->parts.put(u.id(),u.part()));
        // First find physical trains. A real industrial driver owns its train; connected Create endpoints are loads.
        var physical=ShaftNetwork.compile(parts.values());createDrivers.clear();createLoads.clear();
        for(var train:physical) {
            boolean industrial=train.parts().stream().map(units::get).anyMatch(u->u.kind().driver()&&u.kind()!=ShaftNetwork.Kind.CREATE_COUPLING);
            var endpoints=train.parts().stream().map(units::get).filter(u->u.kind()==ShaftNetwork.Kind.CREATE_COUPLING||u.kind()==ShaftNetwork.Kind.SHAFT&&create.containsKey(u.position())&&create.get(u.position()).connected()).sorted(Comparator.comparingLong(ShaftUnit::id)).toList();
            if(industrial)for(var u:endpoints){parts.put(u.id(),parts.get(u.id()).withRole(ShaftNetwork.Role.LOAD,u.designSpeed()));createLoads.add(u.id());}
            else if(endpoints.stream().noneMatch(u->u.kind()==ShaftNetwork.Kind.CREATE_COUPLING)&&!endpoints.isEmpty()) {
                var source=endpoints.getFirst();parts.put(source.id(),parts.get(source.id()).withRole(ShaftNetwork.Role.SOURCE,com.wormzjl.icheme.science.machine.CreateCoupling.DESIGN_SPEED));createDrivers.add(source.id());
            }
        }
        var compiled = ShaftNetwork.compile(parts.values());''')
edit('runtime/fluid/ShaftTrains.java','        double demand = 0;\n        for (var m : train.driven())', '''        for(var m:train.driven())if(createLoads.contains(m.id())){var u=topology.shaft(m.id());var input=u==null?null:create.get(u.position());run.power.put(m.id(),input==null?0:input.demand());}
        double demand = 0;
        for (var m : train.driven())''')
edit('runtime/fluid/ShaftTrains.java','        var drive = d.drive(d.drive() == com.wormzjl.icheme.science.equipment.MachineDuty.Drive.VARIABLE);','        var drive = createDrivers.contains(d.id())?ShaftTrain.Drive.CREATE:d.drive(d.drive() == com.wormzjl.icheme.science.equipment.MachineDuty.Drive.VARIABLE);')
edit('runtime/fluid/ShaftTrains.java','        run.trip = step.trip();','''        // Publish only finite mechanical capacity left after the internal machines. External demand is booked once above.
        double internal=0;for(var m:train.driven())if(!createLoads.contains(m.id()))internal+=run.power.getOrDefault(m.id(),0.0)/m.efficiency();
        double capacity=driver.drive()==ShaftTrain.Drive.TURBINE?Math.min(driver.limit(),Math.max(run.available,step.governorPower())):driver.limit();
        if(driver.drive()==ShaftTrain.Drive.VARIABLE_MOTOR)capacity=com.wormzjl.icheme.science.machine.Motor.availablePower(capacity,driver.designSpeed(),step.speed());
        for(var m:train.driven())if(createLoads.contains(m.id())){var u=topology.shaft(m.id());if(u!=null)host.output(u.position(),step.speed()*m.ratio(),step.speed()>0?Math.max(0,capacity-internal)*m.efficiency():0);}
        run.trip = step.trip();''')
# Zero stale exported capacity before a topology rebuild; only loaded adapters receive it.
edit('runtime/fluid/ShaftTrains.java','        var before = new HashMap<>(runs);','''        for(var run:runs.values())for(long id:run.train.parts()){var u=units.get(id);if(u!=null&&(u.kind()==ShaftNetwork.Kind.CREATE_COUPLING||u.kind()==ShaftNetwork.Kind.SHAFT&&create.containsKey(u.position())))host.output(u.position(),0,0);}
        var before = new HashMap<>(runs);''')
edit('world/level/block/ShaftParts.java','        void stressImpact(double suPerRpm);','        void stressImpact(double suPerRpm);\n        default void output(double rpm,double watts) {}')
edit('runtime/fluid/FluidWorldAuthority.java','        @Override public Set<Long> linked(long motor){return controls().connectedHosts(motor);}','''        @Override public Set<Long> linked(long motor){return controls().connectedHosts(motor);}
        @Override public void output(PhysicalFluidTopology.Position port,double rpm,double watts) {
            var level=server.getLevel(dimension(port.dimension()));if(level==null)return;
            var pos=new BlockPos(port.x(),port.y(),port.z());if(!level.hasChunkAt(pos))return;
            if(level.getBlockEntity(pos) instanceof com.wormzjl.icheme.world.level.block.ShaftParts.Coupling bridge)bridge.output(rpm,watts);
        }''')
edit('world/level/block/ShaftBlock.java','public final class ShaftBlock','public class ShaftBlock')
edit('registry/ModBlocks.java','com.wormzjl.icheme.world.level.block.ShaftBlock::new,','properties->net.neoforged.fml.ModList.get().isLoaded("create")?new com.wormzjl.icheme.compat.create.CreateShaftBlock(properties):new com.wormzjl.icheme.world.level.block.ShaftBlock(properties),')
