package com.wormzjl.icheme.world.level.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.Map;

/**
 * A shaft block (batch 2026-11-03-rotating-machinery, plan section 5.3): a steel shaft on one axis through the block's
 * centre, joining machines' shaft ends and other shaft blocks; it carries no fluid. Its drawn diameter, {@link #SIZE}
 * pixels, shows its train's torque at three times the real shaft (2 to 8 px: {@code ProcessUnit.shaftPixels}); the
 * train sets it (W3), a placed shaft starts at {@link #DEFAULT_SIZE}. No block entity: the train lives in the engine.
 */
public final class ShaftBlock extends RotatedPillarBlock {
    public static final MapCodec<ShaftBlock> CODEC = simpleCodec(ShaftBlock::new);
    /** The drawn diameter, px. */
    public static final IntegerProperty SIZE = IntegerProperty.create("size", 2, 8);
    public static final int DEFAULT_SIZE = 4;
    private static final Map<Direction.Axis, VoxelShape[]> SHAPES = new EnumMap<>(Direction.Axis.class);

    static {
        for (var axis : Direction.Axis.values()) {
            var shapes = new VoxelShape[9];
            for (int size = 2; size <= 8; size++) {
                // The shape is whole pixels round the centre: an odd size takes the next even one.
                double low = 8 - Math.ceil(size / 2.0), high = 8 + Math.ceil(size / 2.0);
                shapes[size] = switch (axis) {
                    case X -> Block.box(0, low, low, 16, high, high);
                    case Y -> Block.box(low, 0, low, high, 16, high);
                    case Z -> Block.box(low, low, 0, high, high, 16);
                };
            }
            SHAPES.put(axis, shapes);
        }
    }

    public ShaftBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(AXIS, Direction.Axis.Z).setValue(SIZE, DEFAULT_SIZE));
    }

    @Override
    public MapCodec<ShaftBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(SIZE);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPES.get(state.getValue(AXIS))[state.getValue(SIZE)];
    }
}
