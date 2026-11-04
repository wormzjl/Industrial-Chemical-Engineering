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
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * The creative motor (batch 2026-11-03-rotating-machinery, plan section 6): one block that turns a shaft at a set speed
 * up to a power limit, with no electricity. Its shaft end leaves its {@link #FACING} side through the block's centre, where
 * a shaft block or a machine's shaft end joins it. Its set points and its run are the engine's (W3); this block only
 * stands and points its shaft.
 */
public final class CreativeMotorBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<CreativeMotorBlock> CODEC = simpleCodec(CreativeMotorBlock::new);
    private static final Map<Direction, VoxelShape> SHAPES = new EnumMap<>(Direction.class);

    static {
        // Facing north: the base, the body, the shaft's stub out of the north face.
        var base = Block.box(1, 0, 2, 15, 2, 15);
        for (var facing : Direction.Plane.HORIZONTAL) {
            VoxelShape body = switch (facing) {
                case NORTH -> Shapes.or(Block.box(3, 2, 4, 13, 14, 15), Block.box(6.5, 6.5, 0, 9.5, 9.5, 4));
                case SOUTH -> Shapes.or(Block.box(3, 2, 1, 13, 14, 12), Block.box(6.5, 6.5, 12, 9.5, 9.5, 16));
                case EAST -> Shapes.or(Block.box(1, 2, 3, 12, 14, 13), Block.box(12, 6.5, 6.5, 16, 9.5, 9.5));
                default -> Shapes.or(Block.box(4, 2, 3, 15, 14, 13), Block.box(0, 6.5, 6.5, 4, 9.5, 9.5));
            };
            var plate = switch (facing) {
                case NORTH -> base;
                case SOUTH -> Block.box(1, 0, 1, 15, 2, 14);
                case EAST -> Block.box(1, 0, 1, 14, 2, 15);
                default -> Block.box(2, 0, 1, 15, 2, 15);
            };
            SHAPES.put(facing, Shapes.or(plate, body));
        }
    }

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
}
