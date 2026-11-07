package com.wormzjl.icheme.world.level.block;

import com.mojang.serialization.MapCodec;
import com.wormzjl.icheme.runtime.control.DataBus;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.world.level.block.entity.DataCableBlockEntity;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.*;

/** Structural signal cable. It shares grid routing with pipes but never joins the fluid topology. */
public final class DataCableBlock extends BaseEntityBlock {
    private static final java.util.Map<com.wormzjl.icheme.science.equipment.DataCableGeometry.Key,VoxelShape> SHAPES=new java.util.concurrent.ConcurrentHashMap<>();
    public static final MapCodec<DataCableBlock> CODEC=simpleCodec(DataCableBlock::new);
    public DataCableBlock(Properties properties){super(properties);var state=stateDefinition.any();for(var face:Direction.values())state=state.setValue(connection(face),false);registerDefaultState(state);}
    @Override protected MapCodec<? extends BaseEntityBlock> codec(){return CODEC;}
    public static BooleanProperty connection(Direction face){return FluidDeviceBlock.connection(face);}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){for(var face:Direction.values())builder.add(connection(face));}
    @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState state){return new DataCableBlockEntity(pos,state);}
    @Override protected RenderShape getRenderShape(BlockState state){return RenderShape.MODEL;}
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,CollisionContext context){
        if(!(level.getBlockEntity(pos) instanceof DataCableBlockEntity cable))return Block.box(6,6,6,10,10,10);
        if(SHAPES.size()>1024)SHAPES.clear();
        return SHAPES.computeIfAbsent(cable.geometry(),key->{VoxelShape shape=Shapes.empty();
            for(var b:com.wormzjl.icheme.science.equipment.DataCableGeometry.boxes(key))shape=Shapes.or(shape,Shapes.box(b[0],b[1],b[2],b[3],b[4],b[5]));
            return shape.optimize();});
    }
    @Override protected void onRemove(BlockState state,Level level,BlockPos pos,BlockState next,boolean moved){
        if(!state.is(next.getBlock())&&level instanceof ServerLevel server)FluidWorldAuthority.find(server.getServer())
                .ifPresent(w->w.controls().removeCable(new DataBus.Site(level.dimension().location().toString(),pos,0)));
        super.onRemove(state,level,pos,next,moved);
    }
}
