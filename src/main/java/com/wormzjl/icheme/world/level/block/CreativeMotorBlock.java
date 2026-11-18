package com.wormzjl.icheme.world.level.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Map;

/**
 * The creative motor (batch 2026-11-03-rotating-machinery, plan section 6): one block that turns a shaft at a set speed
 * up to a power limit, with no electricity. Its shaft end leaves its {@link #FACING} side through the block's centre, where
 * a shaft block or a machine's shaft end joins it. Its set points and its run are the engine's (W3); this block only
 * stands and points its shaft.
 */
public final class CreativeMotorBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<CreativeMotorBlock> CODEC = simpleCodec(CreativeMotorBlock::new);
    /** Facing north, as the model of tools/pump-sprites/CreativeMotorAssets: the base, the frame and its cowl, the terminal box, the shaft's stub. */
    private static final Map<Direction, VoxelShape> SHAPES = HorizontalShapes.byFacing(java.util.List.of(
            new double[]{2, 0, 3, 14, 1, 15}, new double[]{2.5, 1, 4, 13.5, 13.5, 15}, new double[]{5, 12.5, 6, 11, 16, 11}, new double[]{6.5, 6.5, 0, 9.5, 9.5, 4}));

    public CreativeMotorBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    @Override
    public MapCodec<CreativeMotorBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    /** Its shaft points away from the player, into the block they look at beyond it. */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    /** Using it opens its page (W7, reading A89): Off and On, its speed or what it regulates, its power limit. */
    @Override
    protected net.minecraft.world.InteractionResult useWithoutItem(BlockState state, net.minecraft.world.level.Level level, BlockPos pos, net.minecraft.world.entity.player.Player player,
                                                                   net.minecraft.world.phys.BlockHitResult hit) {
        if (player instanceof net.minecraft.server.level.ServerPlayer server)
            com.wormzjl.icheme.network.MachineNetwork.open(server, pos, com.wormzjl.icheme.world.inventory.MachineMenu.REACH, false);
        return net.minecraft.world.InteractionResult.sidedSuccess(level.isClientSide());
    }

    /** A placed creative motor queues its part (W3). */
    @Override
    protected void onPlace(BlockState state, net.minecraft.world.level.Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (!level.isClientSide() && !FluidDeviceBlock.quiet() && (!oldState.is(this) || oldState.getValue(FACING) != state.getValue(FACING))) ShaftParts.creative(level, pos, state.getValue(FACING));
    }

    /** A broken creative motor queues its part's removal (W3). */
    @Override
    protected void onRemove(BlockState state, net.minecraft.world.level.Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!level.isClientSide() && !FluidDeviceBlock.quiet() && !newState.is(this)) ShaftParts.remove(level, pos);
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
