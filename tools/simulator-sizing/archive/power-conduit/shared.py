from pathlib import Path
root=Path('src')
renames={'DataCableGeometry':'ConduitGeometry','DataCableLayingClient':'CableLayingClient','DataCableLaying':'CableLaying','DataCableBlockEntity':'ConduitBlockEntity','DataCableBlock':'ConduitBlock','DataCableModel':'ConduitModel'}
for base in [root/'main/java',root/'test/java']:
 for p in list(base.rglob('*.java')):
  old=p.read_text(encoding='utf-8');s=old
  for a,b in renames.items():s=s.replace(a,b)
  if s!=old:p.write_text(s,encoding='utf-8')
  if p.stem in renames:p.rename(p.with_name(renames[p.stem]+'.java'))

base=root/'main/java/com/wormzjl/icheme'
def edit(path,a,b):
 p=base/path;s=p.read_text(encoding='utf-8');assert a in s,(path,a[:100]);p.write_text(s.replace(a,b),encoding='utf-8')
edit('science/equipment/ConduitGeometry.java','public record Key(int joints,long offsets) {','''public record Key(int joints,long offsets,CableMedium medium) {
        public Key(int joints,long offsets){this(joints,offsets,CableMedium.DATA);}''')
edit('science/equipment/ConduitGeometry.java','        public Key {','        public Key {\n            Objects.requireNonNull(medium);')
edit('science/equipment/ConduitGeometry.java','return new Key(joints|1<<face,(offsets&~(1023L<<(10*face)))|at);','return new Key(joints|1<<face,(offsets&~(1023L<<(10*face)))|at,medium);')
edit('science/equipment/ConduitGeometry.java','LineGeometry.conduit(key.joints(),key.offsets(),OUTSIDE)','LineGeometry.conduit(key.joints(),key.offsets(),key.medium().outside())')
edit('science/equipment/ConduitGeometry.java','public static List<Polygon> path(List<double[]> points){return LineGeometry.conduit(points,OUTSIDE);}','public static List<Polygon> path(List<double[]> points){return LineGeometry.conduit(points,OUTSIDE);}')
edit('science/equipment/ConduitGeometry.java','public static List<Polygon> lead(double[] outward,double[] end,double[] entering){','''public static List<Polygon> lead(double[] outward,double[] end,double[] entering){return lead(outward,end,entering,CableMedium.DATA);}
    public static List<Polygon> lead(double[] outward,double[] end,double[] entering,CableMedium medium){''')
edit('science/equipment/ConduitGeometry.java','points.add(end);return path(points);','points.add(end);return LineGeometry.conduit(points,medium.outside());')
edit('runtime/control/DataBus.java','import com.wormzjl.icheme.science.equipment.ConduitGeometry;','import com.wormzjl.icheme.science.equipment.ConduitGeometry;\nimport com.wormzjl.icheme.science.equipment.CableMedium;')
edit('runtime/control/DataBus.java','t.putInt("joints",geometry.joints());','if(geometry.medium()!=CableMedium.DATA)t.putString("medium",geometry.medium().name());t.putInt("joints",geometry.joints());')
edit('runtime/control/DataBus.java','if(!t.getAllKeys().equals(Set.of("dimension","cell","lane","joints","offsets","arms","endpoints"))','if(!t.getAllKeys().equals(t.contains("medium")?Set.of("dimension","cell","lane","joints","offsets","arms","endpoints","medium"):Set.of("dimension","cell","lane","joints","offsets","arms","endpoints"))')
edit('runtime/control/DataBus.java','new ConduitGeometry.Key(t.getInt("joints"),t.getLong("offsets"))','new ConduitGeometry.Key(t.getInt("joints"),t.getLong("offsets"),t.contains("medium")?CableMedium.valueOf(t.getString("medium")):CableMedium.DATA)')
edit('runtime/control/DataBus.java','    public DataBus(Collection<Cable> cables,Map<UUID,DataPort> ports){','''    public DataBus(Collection<Cable> cables,Map<UUID,DataPort> ports){this(cables,ports,CableMedium.DATA);}
    public DataBus(Collection<Cable> cables,Map<UUID,DataPort> ports,CableMedium medium){''')
edit('runtime/control/DataBus.java','        for(var cable:cables){','        for(var cable:cables){\n            if(cable.geometry().medium()!=medium)continue;')
edit('runtime/control/DataBus.java','if(port==null||!port.dimension()','if(port==null||port.kind().medium()!=medium||!port.dimension()')
edit('runtime/control/DataPort.java','public enum Kind { SENSOR, ACTUATOR, CONTROLLER, DCS }','''public enum Kind { SENSOR, ACTUATOR, CONTROLLER, DCS, POWER_INPUT, POWER_OUTPUT, POWER_BOTH;
        public boolean power(){return ordinal()>=POWER_INPUT.ordinal();}
        public com.wormzjl.icheme.science.equipment.CableMedium medium(){return power()?com.wormzjl.icheme.science.equipment.CableMedium.POWER:com.wormzjl.icheme.science.equipment.CableMedium.DATA;}
    }''')
edit('runtime/control/DataPort.java','if((kind==Kind.SENSOR||kind==Kind.ACTUATOR)!=(host>0))','if(!kind.power()&&(kind==Kind.SENSOR||kind==Kind.ACTUATOR)!=(host>0))')
edit('runtime/control/ControlFacts.java','public static final int FORMAT=2;','public static final int FORMAT=3;')
edit('runtime/control/ControlFacts.java','!legacy&&tag.getInt("format")!=FORMAT','!legacy&&tag.getInt("format")!=2&&tag.getInt("format")!=FORMAT')
edit('runtime/control/ControlService.java','if(p.host()>0)equipmentPorts.put','if(p.host()>0&&!p.kind().power())equipmentPorts.put')

# A medium belongs to a physical block as well as its registered geometry.
edit('world/level/block/ConduitBlock.java','    public ConduitBlock(Properties properties){super(properties);','''    private final com.wormzjl.icheme.science.equipment.CableMedium medium;
    public com.wormzjl.icheme.science.equipment.CableMedium medium(){return medium;}
    public ConduitBlock(Properties properties){this(properties,com.wormzjl.icheme.science.equipment.CableMedium.DATA);}
    public ConduitBlock(Properties properties,com.wormzjl.icheme.science.equipment.CableMedium medium){super(properties);this.medium=medium;''')
edit('world/level/block/entity/ConduitBlockEntity.java','public ConduitBlockEntity(BlockPos pos,BlockState state){super(ModBlockEntities.DATA_CABLE.get(),pos,state);}','''public ConduitBlockEntity(BlockPos pos,BlockState state){super(ModBlockEntities.DATA_CABLE.get(),pos,state);geometry=new ConduitGeometry.Key(0,0,((ConduitBlock)state.getBlock()).medium());}
    public net.neoforged.neoforge.energy.IEnergyStorage energy(){return geometry.medium()==com.wormzjl.icheme.science.equipment.CableMedium.POWER&&level instanceof ServerLevel server?com.wormzjl.icheme.runtime.power.PowerNetwork.of(server.getServer()).capability(cable().site()):null;}''')
edit('world/level/block/entity/ConduitBlockEntity.java','geometry=cable.geometry();endpoints=cable.endpoints();','if(cable.geometry().medium()!=((ConduitBlock)getBlockState().getBlock()).medium())throw new IllegalArgumentException("Cable medium differs from its block");\n        geometry=cable.geometry();endpoints=cable.endpoints();')
edit('world/level/block/entity/ConduitBlockEntity.java','tag.putInt("Joints",geometry.joints());','tag.putString("Medium",geometry.medium().name());tag.putInt("Joints",geometry.joints());')
edit('world/level/block/entity/ConduitBlockEntity.java','new ConduitGeometry.Key(tag.getInt("Joints"),tag.getLong("Offsets"))','new ConduitGeometry.Key(tag.getInt("Joints"),tag.getLong("Offsets"),((ConduitBlock)getBlockState().getBlock()).medium())')
edit('world/level/block/entity/ConduitBlockEntity.java','geometry=new ConduitGeometry.Key(0,0);','geometry=new ConduitGeometry.Key(0,0,((ConduitBlock)getBlockState().getBlock()).medium());')
# Rack copies retain medium and use the same width calculation as pipe lanes.
edit('world/level/block/entity/PipeRackBlockEntity.java','new RackGeometry.Lane(cable.site().lane()-1,1,cable.arms(),cable.geometry().joints(),PipeSizes.code(com.wormzjl.icheme.science.equipment.ConduitGeometry.OUTSIDE))','new RackGeometry.Lane(cable.site().lane()-1,RackGeometry.width(cable.geometry().medium().outside()),cable.arms(),cable.geometry().joints(),PipeSizes.code(cable.geometry().medium().outside()))')
