package com.wormzjl.icheme.world.level.block;

import com.mojang.serialization.MapCodec;
import com.wormzjl.icheme.science.equipment.DcsGeometry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A two-cell console with one saved controller and rear data socket, both at its original lower
 * position.
 */
public final class DcsBlock extends ControlPanelBlock {
    public static final MapCodec<DcsBlock> CODEC = simpleCodec(DcsBlock::new);
    public static final EnumProperty<DoubleBlockHalf> HALF = BlockStateProperties.DOUBLE_BLOCK_HALF;
    private static final VoxelShape[][] SHAPES = shapes();

    public DcsBlock(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(HALF, DoubleBlockHalf.LOWER));
    }

    @Override
    protected MapCodec<? extends DcsBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(HALF);
    }

    public static BlockPos base(BlockPos pos, BlockState state) {
        return state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        var level = context.getLevel();
        var above = context.getClickedPos().above();
        var player = context.getPlayer();
        if (above.getY() >= level.getMaxBuildHeight()
                || !level.getWorldBorder().isWithinBounds(above)
                || !level.hasChunkAt(above)
                || !level.getBlockState(above).canBeReplaced(context)
                || player != null
                        && (!level.mayInteract(player, above)
                                || !player.mayUseItemAt(
                                        above, context.getClickedFace(), context.getItemInHand())))
            return null;
        return super.getStateForPlacement(context);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(HALF) == DoubleBlockHalf.LOWER
                ? super.newBlockEntity(pos, state)
                : null;
    }

    @Override
    public void setPlacedBy(
            Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        complete(level, pos);
    }

    /**
     * Old single-block consoles gain their top only in free space; their identity and wired entry
     * never move.
     */
    public void complete(Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (!state.is(this) || state.getValue(HALF) != DoubleBlockHalf.LOWER) return;
        var above = pos.above();
        if (!level.hasChunkAt(above)
                || level.isOutsideBuildHeight(above)
                || !level.getWorldBorder().isWithinBounds(above)) return;
        var top = level.getBlockState(above);
        if (top.is(this) && top.getValue(HALF) == DoubleBlockHalf.UPPER) {
            var aligned = top.setValue(FACING, state.getValue(FACING));
            if (!aligned.equals(top)) level.setBlock(above, aligned, Block.UPDATE_ALL);
        } else if (top.canBeReplaced())
            level.setBlock(above, state.setValue(HALF, DoubleBlockHalf.UPPER), Block.UPDATE_ALL);
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        complete(level, pos);
    }

    @Override
    protected BlockState updateShape(
            BlockState state,
            Direction direction,
            BlockState neighbour,
            LevelAccessor level,
            BlockPos pos,
            BlockPos next) {
        if (state.getValue(HALF) == DoubleBlockHalf.UPPER && direction == Direction.DOWN)
            return neighbour.is(this) && neighbour.getValue(HALF) == DoubleBlockHalf.LOWER
                    ? state.setValue(FACING, neighbour.getValue(FACING))
                    : Blocks.AIR.defaultBlockState();
        if (state.getValue(HALF) == DoubleBlockHalf.LOWER
                && direction == Direction.UP
                && !level.isClientSide()) level.scheduleTick(pos, this, 1);
        return super.updateShape(state, direction, neighbour, level, pos, next);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        var base = base(pos, state);
        return super.useWithoutItem(level.getBlockState(base), level, base, player, hit);
    }

    @Override
    protected void onRemove(
            BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (!state.is(next.getBlock())) {
            var partner = state.getValue(HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos.above();
            var other = level.getBlockState(partner);
            if (other.is(this) && other.getValue(HALF) != state.getValue(HALF))
                level.setBlock(partner, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        super.onRemove(state, level, pos, next, moved);
    }

    @Override
    protected VoxelShape getShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        int turn =
                switch (state.getValue(FACING)) {
                    case NORTH -> 0;
                    case EAST -> 1;
                    case SOUTH -> 2;
                    case WEST -> 3;
                    default -> throw new IllegalStateException("Vertical console");
                };
        return LinePieces.shape(
                level, pos, SHAPES[state.getValue(HALF) == DoubleBlockHalf.UPPER ? 1 : 0][turn]);
    }

    private static VoxelShape[][] shapes() {
        var shapes = new VoxelShape[2][4];
        for (int half = 0; half < 2; half++)
            for (int turn = 0; turn < 4; turn++) {
                var shape = Shapes.empty();
                for (var part : DcsGeometry.parts(half == 1)) {
                    var b = DcsGeometry.bounds(part, turn);
                    shape = Shapes.or(shape, Block.box(b[0], b[1], b[2], b[3], b[4], b[5]));
                }
                shapes[half][turn] = shape.optimize();
            }
        return shapes;
    }
}
