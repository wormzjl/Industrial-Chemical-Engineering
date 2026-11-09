from pathlib import Path
import json
r=Path('src/main/java/com/wormzjl/icheme')
def edit(file,old,new):
 p=r/file;s=p.read_text(encoding='utf-8');assert old in s,(file,old);p.write_text(s.replace(old,new),encoding='utf-8')
edit('registry/ModBlocks.java','sound(SoundType.WOOL)','sound(SoundType.METAL)')
edit('registry/ModBlocks.java','    public static final DeferredBlock<com.wormzjl.icheme.world.level.block.ControlPanelBlock> LOCAL_CONTROLLER', '''    public static final DeferredBlock<com.wormzjl.icheme.world.level.block.ConduitBlock> POWER_CABLE=BLOCKS.registerBlock("power_cable",
            p->new com.wormzjl.icheme.world.level.block.ConduitBlock(p,com.wormzjl.icheme.science.equipment.CableMedium.POWER),BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(.5F).sound(SoundType.METAL).noOcclusion().noCollission().dynamicShape());
    public static final DeferredBlock<com.wormzjl.icheme.world.level.block.ControlPanelBlock> LOCAL_CONTROLLER''')
edit('registry/ModBlockEntities.java','ConduitBlockEntity::new,ModBlocks.DATA_CABLE.get())','ConduitBlockEntity::new,ModBlocks.DATA_CABLE.get(),ModBlocks.POWER_CABLE.get())')
edit('registry/ModItems.java','    private static DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> linePaint', '''    public static final DeferredHolder<DataComponentType<?>,DataComponentType<Boolean>> PIPE_LAYING_POWER = COMPONENTS.register("pipe_laying_power",
            ()->DataComponentType.<Boolean>builder().persistent(com.mojang.serialization.Codec.BOOL).networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.BOOL).build());
    private static DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> linePaint''')
edit('registry/ModItems.java','DATA_CABLE=ITEMS.registerSimpleItem("data_cable");','DATA_CABLE=ITEMS.registerSimpleItem("data_cable");\n    public static final DeferredItem<net.minecraft.world.item.Item> POWER_CABLE=ITEMS.registerSimpleItem("power_cable");')
edit('registry/ModCreativeTabs.java','output.accept(ModItems.DATA_CABLE.get());','output.accept(ModItems.DATA_CABLE.get());\n                    output.accept(ModItems.POWER_CABLE.get());')
edit('world/item/PipeLayingToolItem.java','return stack.getOrDefault(ModItems.PIPE_LAYING_DATA.get(),false);','return !power(stack)&&stack.getOrDefault(ModItems.PIPE_LAYING_DATA.get(),false);')
edit('world/item/PipeLayingToolItem.java','    public PipeLayingToolItem(Properties properties)', '''    public static boolean power(ItemStack stack){return stack.getOrDefault(ModItems.PIPE_LAYING_POWER.get(),false);}
    public static boolean conduit(ItemStack stack){return data(stack)||power(stack);}
    public static com.wormzjl.icheme.science.equipment.CableMedium medium(ItemStack stack){return power(stack)?com.wormzjl.icheme.science.equipment.CableMedium.POWER:com.wormzjl.icheme.science.equipment.CableMedium.DATA;}
    public PipeLayingToolItem(Properties properties)''')
edit('world/item/PipeLayingToolItem.java','data(stack)?"gui.icheme.pipeline_tool.mode.data":"gui.icheme.pipeline_tool.mode.pipe"','power(stack)?"gui.icheme.pipeline_tool.mode.power":data(stack)?"gui.icheme.pipeline_tool.mode.data":"gui.icheme.pipeline_tool.mode.pipe"')
edit('world/item/PipeLayingToolItem.java','if(data(stack)){','if(conduit(stack)){')
edit('world/item/PipeLayingToolItem.java','"item.icheme.pipe_laying_tool.data."+line','"item.icheme.pipe_laying_tool."+(power(stack)?"power.":"data.")+line')
for f in ['client/PipeLayingClient.java','world/level/block/PipeLaying.java']:
 edit(f,'PipeLayingToolItem.data(','PipeLayingToolItem.conduit(')
edit('world/level/block/entity/ConduitBlockEntity.java','private ConduitGeometry.Key geometry=new ConduitGeometry.Key(0,0,((ConduitBlock)getBlockState().getBlock()).medium());','private ConduitGeometry.Key geometry;')
edit('runtime/instrument/Instrument.java','        private com.wormzjl.icheme.runtime.control.DataPort dataPort;', '''        private com.wormzjl.icheme.runtime.control.DataPort powerPort;
        public com.wormzjl.icheme.runtime.control.DataPort powerPort(){return powerPort;}
        public void powerPort(com.wormzjl.icheme.runtime.control.DataPort value){powerPort=value;}
        private com.wormzjl.icheme.runtime.control.DataPort dataPort;''')
edit('runtime/instrument/Instrument.java','public void write(CompoundTag tag){','public void write(CompoundTag tag){if(powerPort!=null)tag.put("PowerPort",powerPort.save());else tag.remove("PowerPort");')
edit('runtime/instrument/Instrument.java','public void read(CompoundTag tag) {','''public void read(CompoundTag tag) {
            try{powerPort=tag.contains("PowerPort",10)?com.wormzjl.icheme.runtime.control.DataPort.load(tag.getCompound("PowerPort")):null;if(powerPort!=null&&!powerPort.kind().power())powerPort=null;}catch(IllegalArgumentException invalid){powerPort=null;}''')
edit('runtime/instrument/InstrumentHosts.java','public static void loaded(BlockEntity be){','public static void loaded(BlockEntity be){\n        com.wormzjl.icheme.runtime.power.PowerNetwork.loaded(be);')
edit('runtime/instrument/InstrumentHosts.java','shell.instruments().dataPort()!=null)))','shell.instruments().dataPort()!=null||shell.instruments().powerPort()!=null)||be instanceof CreativePowerBlockEntity))')
edit('runtime/instrument/InstrumentHosts.java','h.instruments().dataPort()!=null||h.instruments().fitting()','h.instruments().dataPort()!=null||h.instruments().powerPort()!=null||h.instruments().fitting()')
edit('runtime/instrument/InstrumentHosts.java','    public static List<Host> nearby(Level level,Vec3 centre,double radius){','''    public static List<com.wormzjl.icheme.runtime.control.DataPort> powerPorts(Level level,Vec3 centre,double radius){
        var result=new ArrayList<com.wormzjl.icheme.runtime.control.DataPort>();
        for(var entry:loaded(level,true).entrySet()){
            var pos=entry.getKey();double reach=radius+entry.getValue();if(pos.getCenter().distanceToSqr(centre)>reach*reach||!level.hasChunkAt(pos))continue;
            if(level.getBlockEntity(pos) instanceof Instrument.Holder holder){var port=holder.instruments().powerPort();if(port!=null&&port.centre().distanceToSqr(centre)<=radius*radius)result.add(port);}
        }return List.copyOf(result);
    }
    public static List<Host> nearby(Level level,Vec3 centre,double radius){''')
edit('world/level/block/entity/EquipmentShellBlockEntity.java','if (level != null && !level.isClientSide()) level.invalidateCapabilities(worldPosition);','if (level != null && !level.isClientSide()) level.invalidateCapabilities(worldPosition);\n        com.wormzjl.icheme.runtime.instrument.InstrumentHosts.loaded(this);')
edit('world/level/block/entity/FluidDeviceBlockEntity.java','super.setBlockState(state);','super.setBlockState(state);\n        com.wormzjl.icheme.runtime.power.PowerNetwork.loaded(this);\n        if(level!=null&&!level.isClientSide())level.invalidateCapabilities(worldPosition);')
edit('IChemE.java','        event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,ModBlockEntities.CREATIVE_POWER','        event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,ModBlockEntities.DATA_CABLE.get(),(cable,side)->cable.energy());\n        event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,ModBlockEntities.CREATIVE_POWER')
edit('IChemE.java','            com.wormzjl.icheme.runtime.assembly.AssemblyService.tick','            com.wormzjl.icheme.runtime.power.PowerNetwork.of(event.getServer()).tick();\n            com.wormzjl.icheme.runtime.assembly.AssemblyService.tick')
edit('IChemE.java','            com.wormzjl.icheme.runtime.assembly.AssemblyService.forget','            com.wormzjl.icheme.runtime.power.PowerNetwork.forget(event.getServer());\n            com.wormzjl.icheme.runtime.assembly.AssemblyService.forget')
edit('client/model/ConduitModel.java','new Geometry();','new Geometry(json.has("medium")?CableMedium.valueOf(json.get("medium").getAsString()):CableMedium.DATA);')
edit('client/model/ConduitModel.java','    private final TextureAtlasSprite sprite;','    private final CableMedium medium;\n    private final TextureAtlasSprite sprite;')
edit('client/model/ConduitModel.java','private ConduitModel(TextureAtlasSprite sprite,','private ConduitModel(CableMedium medium,TextureAtlasSprite sprite,')
edit('client/model/ConduitModel.java','{this.sprite=sprite;','{this.medium=medium;this.sprite=sprite;')
edit('client/model/ConduitModel.java','new ConduitGeometry.Key(0,0);','new ConduitGeometry.Key(0,0,medium);')
edit('client/model/ConduitModel.java','record Geometry()','record Geometry(CableMedium medium)')
edit('client/model/ConduitModel.java','new ConduitModel(sprites.apply','new ConduitModel(medium,sprites.apply')
assets=Path('src/main/resources/assets/icheme');data=Path('src/main/resources/data/icheme')
for folder in ['blockstates','models/block','models/item']:
 p=assets/folder/'data_cable.json';s=p.read_text();s=s.replace('data_cable','power_cable');s=s.replace('"loader": "icheme:power_cable"','"medium": "POWER",\n  "loader": "icheme:data_cable"');(p.parent/'power_cable.json').write_text(s)
for p in data.rglob('data_cable.json'):(p.parent/'power_cable.json').write_text(p.read_text().replace('data_cable','power_cable'))
lang=assets/'lang/en_us.json';s=lang.read_text();i=s.rfind('}');extra={
 'block.icheme.power_cable':'Power conduit','item.icheme.power_cable':'Power conduit',
 'gui.icheme.pipeline_tool.mode.power':'Power','gui.icheme.pipeline_tool.mode.power.hint':'Lay 1/8 m electrical conduit. Connects FE power ports and uses pipe routing and rack slots.',
 'item.icheme.pipe_laying_tool.power.lay':'Use twice to lay power conduit between FE ports.',
 'item.icheme.pipe_laying_tool.power.remove':'Shift + use removes one conduit.',
 'item.icheme.pipe_laying_tool.power.configure':'Shift + use in the air opens settings.',
 'item.icheme.pipe_laying_tool.power.items':'Each laid cell consumes one power conduit item.',
 'gui.icheme.power.input':'FE input','gui.icheme.power.output':'FE output','gui.icheme.power.both':'FE connection'}
lang.write_text(s[:i].rstrip()+',\n'+',\n'.join('  '+json.dumps(k)+': '+json.dumps(v) for k,v in extra.items())+'\n}\n',encoding='utf-8')
