package com.wormzjl.icheme.compat.create;

import com.simibubi.create.content.kinetics.base.DirectionalKineticBlock;
import com.simibubi.create.foundation.block.IBE;
import com.wormzjl.icheme.world.level.block.ShaftParts;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Create coupling (batch 2026-11-03-rotating-machinery W6b, form A, owner decision D40; plan section 8): one block,
 * Create's rotation in on its {@link #FACING} side, an industrial shaft end out of the opposite side, where a shaft block
 * or a machine's shaft end joins it. Its train is the engine's: placing it queues its part, breaking it the part's removal;
 * Create's speed and spare stress reach the engine as queued events from its block entity. It has no settings and no page
 * yet (the page waits for the owner's look at its prototype).
 */
public final class CreateCouplingBlock extends DirectionalKineticBlock implements IBE<CreateCouplingBlockEntity> {
    public CreateCouplingBlock(Properties properties) {
        super(properties);
    }

    /** Create's shaft meets it on its facing side only. */
    @Override
    public boolean hasShaftTowards(LevelReader level, BlockPos pos, BlockState state, Direction face) {
        return face == state.getValue(FACING);
    }

    @Override
    public Direction.Axis getRotationAxis(BlockState state) {
        return state.getValue(FACING).getAxis();
    }

    @Override
    public Class<CreateCouplingBlockEntity> getBlockEntityClass() {
        return CreateCouplingBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends CreateCouplingBlockEntity> getBlockEntityType() {
        return CreateCompat.COUPLING_ENTITY.get();
    }

    /** A placed or turned coupling queues its part, its industrial shaft leaving the side opposite its facing. */
    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide() && (!oldState.is(this) || oldState.getValue(FACING) != state.getValue(FACING)))
            ShaftParts.coupling(level, pos, state.getValue(FACING).getOpposite());
    }

    /** A broken coupling queues its part's removal. */
    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide() && !newState.is(this)) ShaftParts.remove(level, pos);
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
