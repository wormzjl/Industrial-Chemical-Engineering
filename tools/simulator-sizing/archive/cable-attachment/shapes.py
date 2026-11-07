from pathlib import Path
p=Path('src/main/java/com/wormzjl/icheme/world/level/block/FluidDeviceBlock.java');s=p.read_text(encoding='utf-8')
s=s.replace('    private static final java.util.Map<Integer,net.minecraft.world.phys.shapes.VoxelShape> PIPE_SHAPES=new java.util.concurrent.ConcurrentHashMap<>();\n','')
a=s.index('        return PIPE_SHAPES.computeIfAbsent(');b=s.index('    @Override public @Nullable BlockEntity newBlockEntity',a)
s=s[:a]+'''        long offsets=level.getBlockEntity(position) instanceof FluidDeviceBlockEntity entity?entity.lineOffsets():0;
        return LineShapes.shape(mask,state.getValue(FACING).ordinal(),code,offsets);
    }
'''+s[b:];p.write_text(s,encoding='utf-8')
p=Path('src/main/java/com/wormzjl/icheme/world/level/block/entity/FluidDeviceBlockEntity.java');s=p.read_text(encoding='utf-8').replace('    @Override public ModelData getModelData() {','    public long lineOffsets(){return offsets;}\n    @Override public ModelData getModelData() {');p.write_text(s,encoding='utf-8')
