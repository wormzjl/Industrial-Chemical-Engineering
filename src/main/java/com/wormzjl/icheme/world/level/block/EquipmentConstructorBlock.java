package com.wormzjl.icheme.world.level.block;

import com.mojang.serialization.MapCodec;
import com.wormzjl.icheme.client.EquipmentClient;
import com.wormzjl.icheme.world.level.block.entity.EquipmentConstructorBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.Map;

/**
 * Builds a parametric vessel in front of itself (owner decision 2026-10-15: a placed block, the Schematicannon
 * pattern). It faces the way the player looked when placing it; its screen sets the vessel's diameter, tangent length
 * and offsets, shows the vessel's ghost, and asks the server to build it. The constructor stays after a build.
 */
public final class EquipmentConstructorBlock extends BaseEntityBlock {
    public static final MapCodec<EquipmentConstructorBlock> CODEC = simpleCodec(EquipmentConstructorBlock::new);
    /** The way the vessel is built: away from the player who placed the constructor. */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    private static final Map<Direction, VoxelShape> SHAPES = benchShapes();

    /** The solid cabinet/worktop and the gantry, matching the model's parts without filling its open working space. */
    private static Map<Direction, VoxelShape> benchShapes() {
        double[][] parts = {
                {0, 0, 0, 16, 12, 16},
                {1, 12, 11, 3, 16, 13}, {13, 12, 11, 15, 16, 13},
                {3, 14.5, 10.75, 13, 16, 13},
                {6, 13, 10.3, 10, 15, 13.5}, {7.5, 12.5, 11, 8.5, 13, 12},
                {.7, 12, 10.6, 3.3, 12.5, 13.4}, {12.7, 12, 10.6, 15.3, 12.5, 13.4},
                {4.5, 12, 3.5, 11.5, 12.35, 8.5}
        };
        return HorizontalShapes.byFacing(java.util.List.of(parts));
    }

    public EquipmentConstructorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<EquipmentConstructorBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
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
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos position, CollisionContext context) {
        return SHAPES.get(state.getValue(FACING));
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos position, BlockState state) {
        return new EquipmentConstructorBlockEntity(position, state);
    }

    /** The screen is client-only and edits a local draft; the server hears of it only when the player saves or builds. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
        if (level.isClientSide() && level.getBlockEntity(position) instanceof EquipmentConstructorBlockEntity) EquipmentClient.openConstructor(position);
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}
