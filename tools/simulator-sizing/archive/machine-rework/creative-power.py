from pathlib import Path
import json,sys
root=Path('.')
def replace(path,old,new):
 p=root/path;s=p.read_text(encoding='utf-8');assert old in s,path;p.write_text(s.replace(old,new),encoding='utf-8')
base='src/main/java/com/wormzjl/icheme/'
replace(base+'registry/ModBlocks.java','    private static DeferredBlock<com.wormzjl.icheme.world.level.block.ControlPanelBlock> controlPanel', '''    public static final DeferredBlock<com.wormzjl.icheme.world.level.block.CreativePowerBlock> CREATIVE_POWER=BLOCKS.registerBlock("creative_power",
            com.wormzjl.icheme.world.level.block.CreativePowerBlock::new,BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BLUE).strength(-1,3600000).sound(SoundType.METAL));
    private static DeferredBlock<com.wormzjl.icheme.world.level.block.ControlPanelBlock> controlPanel''')
replace(base+'registry/ModBlockEntities.java','    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<com.wormzjl.icheme.world.level.block.entity.DataCableBlockEntity>>', '''    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<com.wormzjl.icheme.world.level.block.entity.CreativePowerBlockEntity>> CREATIVE_POWER=BLOCK_ENTITY_TYPES.register("creative_power",
            ()->BlockEntityType.Builder.of(com.wormzjl.icheme.world.level.block.entity.CreativePowerBlockEntity::new,ModBlocks.CREATIVE_POWER.get()).build(null));
    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<com.wormzjl.icheme.world.level.block.entity.DataCableBlockEntity>>''')
replace(base+'registry/ModItems.java','    public static final DeferredItem<BlockItem> CREATIVE_MOTOR=', '    public static final DeferredItem<BlockItem> CREATIVE_POWER=ITEMS.registerSimpleBlockItem("creative_power",ModBlocks.CREATIVE_POWER);\n    public static final DeferredItem<BlockItem> CREATIVE_MOTOR=')
replace(base+'registry/ModCreativeTabs.java','                output.accept(ModItems.CREATIVE_MOTOR.get());','                output.accept(ModItems.CREATIVE_MOTOR.get());\n                output.accept(ModItems.CREATIVE_POWER.get());')
replace(base+'IChemE.java','    private static void registerCapabilities(net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) {','    private static void registerCapabilities(net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent event) {\n        event.registerBlockEntity(net.neoforged.neoforge.capabilities.Capabilities.EnergyStorage.BLOCK,ModBlockEntities.CREATIVE_POWER.get(),(source,side)->source);')
assets=root/'src/main/resources/assets/icheme'
for name,obj in [('blockstates/creative_power.json',{'variants':{'':{'model':'icheme:block/creative_power'}}}),('models/block/creative_power.json',{'parent':'minecraft:block/cube_all','textures':{'all':'icheme:block/creative_power'}}),('models/item/creative_power.json',{'parent':'icheme:block/creative_power'})]:
 (assets/name).write_text(json.dumps(obj,indent=2)+'\n',encoding='utf-8')
p=assets/'lang/en_us.json';s=p.read_text(encoding='utf-8');s=s.rstrip()[:-1].rstrip()+',\n  "block.icheme.creative_power": "Creative RF Power Source"\n}\n';p.write_text(s,encoding='utf-8')
sys.path.insert(0,str(root/'tmp/process-control'));from PIL import Image,ImageDraw
im=Image.new('RGB',(32,32),(46,56,68));d=ImageDraw.Draw(im)
d.rectangle((1,1,30,30),outline=(111,127,143),width=2);d.rectangle((5,5,26,26),fill=(32,38,47),outline=(63,139,167),width=2)
for y in range(9,23):
 left=18-(y-9)//2;right=left+4
 if y>15:left+=4;right+=4
 d.line((left,y,right,y),fill=(225,186,65),width=1)
for x in (3,28):
 for y in (3,28):d.rectangle((x,y,x+1,y+1),fill=(33,36,40))
im.save(assets/'textures/block/creative_power.png')
