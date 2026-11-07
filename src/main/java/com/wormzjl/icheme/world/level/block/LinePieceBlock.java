package com.wormzjl.icheme.world.level.block;

import com.mojang.serialization.MapCodec;
import com.wormzjl.icheme.world.level.block.entity.LinePieceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.*;

/** Occupancy of a socket lead in an otherwise empty cell. It owns no inventory or network identity. */
public final class LinePieceBlock extends BaseEntityBlock {
    public static final MapCodec<LinePieceBlock> CODEC=simpleCodec(LinePieceBlock::new);
    public LinePieceBlock(Properties properties){super(properties);}
    @Override protected MapCodec<? extends BaseEntityBlock> codec(){return CODEC;}
    @Override public BlockEntity newBlockEntity(BlockPos pos,BlockState state){return new LinePieceBlockEntity(pos,state);}
    @Override protected RenderShape getRenderShape(BlockState state){return RenderShape.INVISIBLE;}
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,BlockPos cell,CollisionContext context){
        if(level.getBlockEntity(cell) instanceof LinePieceBlockEntity copy)return copy.shape();return Shapes.empty();
    }
    @Override protected void tick(BlockState state,net.minecraft.server.level.ServerLevel level,BlockPos pos,net.minecraft.util.RandomSource random){
        if(level.getBlockEntity(pos) instanceof LinePieceBlockEntity copy&&copy.linePieces().isEmpty())level.removeBlock(pos,false);
    }
    @Override public BlockState playerWillDestroy(Level level,BlockPos pos,BlockState state,net.minecraft.world.entity.player.Player player){
        if(player instanceof net.minecraft.server.level.ServerPlayer server&&level.getBlockEntity(pos) instanceof LinePieceBlockEntity copy)for(var piece:copy.linePieces())LineLaying.breakPiece(server,piece,pos);
        return super.playerWillDestroy(level,pos,state,player);
    }
    @Override protected void onRemove(BlockState state,Level level,BlockPos pos,BlockState next,boolean moved){
        if(!state.is(next.getBlock())&&level.getBlockEntity(pos) instanceof LinePieceBlockEntity copy)copy.broken();
        super.onRemove(state,level,pos,next,moved);
    }
}
