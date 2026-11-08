package com.wormzjl.icheme.compat.create;

import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.foundation.block.IBE;
import com.wormzjl.icheme.world.level.block.ShaftBlock;

import net.minecraft.core.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/** The ordinary industrial shaft, with a native Create endpoint when Create is installed. */
public final class CreateShaftBlock extends ShaftBlock
        implements IRotate, IBE<CreateShaftBlockEntity> {
    public CreateShaftBlock(Properties properties) {
        super(properties);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(AXIS);
    }

    @Override
    public boolean hasShaftTowards(
            LevelReader level, BlockPos pos, BlockState state, Direction face) {
        return face.getAxis() == getRotationAxis(state);
    }

    @Override
    public Class<CreateShaftBlockEntity> getBlockEntityClass() {
        return CreateShaftBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends CreateShaftBlockEntity> getBlockEntityType() {
        return CreateCompat.SHAFT_ENTITY.get();
    }

    @Override
    protected void neighborChanged(
            BlockState state,
            Level level,
            BlockPos pos,
            Block neighbour,
            BlockPos from,
            boolean moved) {
        super.neighborChanged(state, level, pos, neighbour, from, moved);
        if (level.getBlockEntity(pos) instanceof CreateShaftBlockEntity shaft)
            shaft.connectionsChanged();
    }
}
