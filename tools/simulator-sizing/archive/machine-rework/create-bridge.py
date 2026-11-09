from pathlib import Path
b=Path('src/main/java/com/wormzjl/icheme')
p=b/'compat/create/CreateCouplingBlockEntity.java';p.write_text('''package com.wormzjl.icheme.compat.create;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
/** Saved explicit couplings remain available, and now exchange power in either direction. */
public final class CreateCouplingBlockEntity extends CreateBridgeBlockEntity {
    public CreateCouplingBlockEntity(BlockEntityType<?> type,BlockPos pos,BlockState state){super(type,pos,state);}
    @Override protected boolean connected(){return true;}
    public float impact(){return calculateStressApplied();}
}
''',encoding='utf-8')
def edit(path,old,new):
 p=b/path;s=p.read_text(encoding='utf-8');assert old in s,(path,old[:60]);p.write_text(s.replace(old,new),encoding='utf-8')
edit('compat/create/CreateCompat.java','    private CreateCompat() {','''    public static final DeferredHolder<BlockEntityType<?>,BlockEntityType<CreateShaftBlockEntity>> SHAFT_ENTITY=BLOCK_ENTITIES.register("create_shaft",
            ()->BlockEntityType.Builder.of(CreateShaftBlockEntity::new,com.wormzjl.icheme.registry.ModBlocks.SHAFT.get()).build(null));
    private CreateCompat() {''')
edit('runtime/fluid/ShaftTrains.java','                    create.put(input.cell(), input);','                    var prior=create.put(input.cell(), input);')
edit('runtime/fluid/ShaftTrains.java','                    recompile=true;','                    recompile|=prior==null||prior.connected()!=input.connected();')
edit('runtime/fluid/ShaftTrains.java','        var parts=new HashMap<Long,ShaftNetwork.Part>();','        var oldLoads=Set.copyOf(createLoads);\n        var parts=new HashMap<Long,ShaftNetwork.Part>();')
old='        for(var run:runs.values())for(long id:run.train.parts()){var u=units.get(id);if(u!=null&&(u.kind()==ShaftNetwork.Kind.CREATE_COUPLING||u.kind()==ShaftNetwork.Kind.SHAFT&&create.containsKey(u.position())))host.output(u.position(),0,0);}'
edit('runtime/fluid/ShaftTrains.java',old,'        for(long id:oldLoads)if(!createLoads.contains(id)){var u=units.get(id);if(u!=null)host.output(u.position(),0,0);}')
edit('runtime/fluid/ShaftTrains.java','Math.max(0,capacity-internal)*m.efficiency():0','Math.max(0,capacity-internal)*m.efficiency()/Math.max(1,train.driven().stream().filter(part->createLoads.contains(part.id())).count()):0')
