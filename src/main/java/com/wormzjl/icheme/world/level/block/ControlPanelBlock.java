package com.wormzjl.icheme.world.level.block;

import com.mojang.serialization.MapCodec;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.world.inventory.ControlPanelMenu;
import com.wormzjl.icheme.world.level.block.entity.ControlPanelBlockEntity;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

/** A local controller; the DCS subclass adds its second physical half without another control identity. */
public class ControlPanelBlock extends BaseEntityBlock {
    public static final MapCodec<ControlPanelBlock> CODEC=simpleCodec(ControlPanelBlock::new);
    public static final DirectionProperty FACING=HorizontalDirectionalBlock.FACING;
    private static final net.minecraft.world.phys.shapes.VoxelShape[] SHAPES=localShapes();
    public ControlPanelBlock(Properties properties){super(properties);registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH));}
    @Override protected MapCodec<? extends BaseEntityBlock> codec(){return CODEC;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> builder){builder.add(FACING);}
    @Override public BlockState getStateForPlacement(BlockPlaceContext context){return defaultBlockState().setValue(FACING,context.getHorizontalDirection().getOpposite());}
    @Override protected BlockState rotate(BlockState state,Rotation rotation){return state.setValue(FACING,rotation.rotate(state.getValue(FACING)));}
    @Override protected BlockState mirror(BlockState state,Mirror mirror){return state.rotate(mirror.getRotation(state.getValue(FACING)));}
    @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState state){return new ControlPanelBlockEntity(pos,state);}
    @Override protected RenderShape getRenderShape(BlockState state){return RenderShape.MODEL;}
    @Override protected net.minecraft.world.phys.shapes.VoxelShape getShape(BlockState state,BlockGetter level,BlockPos pos,net.minecraft.world.phys.shapes.CollisionContext context){
        return LinePieces.shape(level,pos,SHAPES[state.getValue(FACING).get2DDataValue()]);
    }
    private static net.minecraft.world.phys.shapes.VoxelShape[] localShapes(){
        var shapes=new net.minecraft.world.phys.shapes.VoxelShape[4];
        for(var facing:Direction.Plane.HORIZONTAL){
            int turns=switch(facing){case NORTH->0;case EAST->1;case SOUTH->2;case WEST->3;default->throw new IllegalStateException("Vertical panel");};
            var shape=net.minecraft.world.phys.shapes.Shapes.empty();
            for(var part:com.wormzjl.icheme.science.equipment.DcsGeometry.localParts()){
                var b=com.wormzjl.icheme.science.equipment.DcsGeometry.bounds(part,turns);
                shape=net.minecraft.world.phys.shapes.Shapes.or(shape,Block.box(b[0],b[1],b[2],b[3],b[4],b[5]));
            }
            shapes[facing.get2DDataValue()]=shape.optimize();
        }return shapes;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,Player player,BlockHitResult hit){
        if(player instanceof ServerPlayer server)ControlPanelMenu.open(server,pos);
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
    @Override protected void onRemove(BlockState state,Level level,BlockPos pos,BlockState next,boolean moved){
        if(!state.is(next.getBlock())&&level instanceof ServerLevel server&&level.getBlockEntity(pos) instanceof ControlPanelBlockEntity panel)
            FluidWorldAuthority.find(server.getServer()).ifPresent(w->w.controls().removePanel(panel.identity()));
        super.onRemove(state,level,pos,next,moved);
    }
}
