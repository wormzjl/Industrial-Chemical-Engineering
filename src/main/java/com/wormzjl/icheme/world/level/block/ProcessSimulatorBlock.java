package com.wormzjl.icheme.world.level.block;

import com.mojang.serialization.MapCodec;
import com.wormzjl.icheme.network.ProcessSimulatorNetwork;
import com.wormzjl.icheme.world.level.block.entity.ProcessSimulatorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/** The process simulator's block (batch 2026-11-02-process-simulator, plan section 3.6): it holds a sheet and opens the screen. It solves nothing, as a run is the client's. */
public final class ProcessSimulatorBlock extends BaseEntityBlock {
    public static final MapCodec<ProcessSimulatorBlock> CODEC = simpleCodec(ProcessSimulatorBlock::new);
    /** The screen's outward direction; a new console faces the player placing it. */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final Map<Direction, VoxelShape> SHAPES = consoleShapes();

    private static Map<Direction, VoxelShape> consoleShapes() {
        double[][] parts = {
                {1, 0, 1, 15, 2, 15}, {2, 2, 6, 14, 7, 15}, {1, 6, 0, 15, 7, 6},
                {1, 7, 9, 15, 16, 12}, {2, 8, 12, 14, 15, 14.5}, {2, 9, 8.7, 14, 15, 9},
                {4, 7.03, 1, 14, 7.35, 6}, {1.6, 7.03, 2, 3.4, 7.5, 5}, {3, 7.6, 8.55, 4.25, 8.65, 9}
        };
        var shapes = new EnumMap<Direction, VoxelShape>(Direction.class);
        for (var facing : Direction.Plane.HORIZONTAL) {
            VoxelShape shape = Shapes.empty();
            for (var p : parts) {
                var part = switch (facing) {
                    case EAST -> Block.box(16 - p[5], p[1], p[0], 16 - p[2], p[4], p[3]);
                    case SOUTH -> Block.box(16 - p[3], p[1], 16 - p[5], 16 - p[0], p[4], 16 - p[2]);
                    case WEST -> Block.box(p[2], p[1], 16 - p[3], p[5], p[4], 16 - p[0]);
                    default -> Block.box(p[0], p[1], p[2], p[3], p[4], p[5]);
                };
                shape = Shapes.or(shape, part);
            }
            shapes.put(facing, shape.optimize());
        }
        return Map.copyOf(shapes);
    }

    public ProcessSimulatorBlock(BlockBehaviour.Properties properties) {
        super(properties);
        // Old saves have no facing property and their computer screen faced south.
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.SOUTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos position, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    @Override
    public MapCodec<ProcessSimulatorBlock> codec() {
        return CODEC;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /**
     * The computer model leaves most of the cell empty. A full occlusion shape still shades the grass beneath it as if
     * a solid cube were sitting there.
     */
    @Override
    protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos position) {
        return Shapes.empty();
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos position) {
        return true;
    }

    @Override
    protected int getLightBlock(BlockState state, BlockGetter level, BlockPos position) {
        return 0;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ProcessSimulatorBlockEntity(pos, state);
    }

    @Override
    public @Nullable MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof ProcessSimulatorBlockEntity simulator ? simulator : null;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer && level.getBlockEntity(pos) instanceof ProcessSimulatorBlockEntity simulator) ProcessSimulatorNetwork.open(serverPlayer, simulator);
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}
