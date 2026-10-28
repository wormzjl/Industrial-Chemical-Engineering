package com.wormzjl.icheme.world.level.block;

import com.mojang.serialization.MapCodec;
import com.wormzjl.icheme.world.level.block.entity.ColumnCalculatorV3BlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/** Calculator block with server-authoritative V3 solve admission and result state. */
public final class ColumnCalculatorV3Block extends BaseEntityBlock {
    public static final MapCodec<ColumnCalculatorV3Block> CODEC = simpleCodec(ColumnCalculatorV3Block::new);

    public ColumnCalculatorV3Block(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<ColumnCalculatorV3Block> codec() {
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
        return new ColumnCalculatorV3BlockEntity(pos, state);
    }

    @Override
    public @Nullable MenuProvider getMenuProvider(BlockState state, Level level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof ColumnCalculatorV3BlockEntity calculator ? calculator : null;
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            MenuProvider menuProvider = state.getMenuProvider(level, pos);
            if (menuProvider != null) serverPlayer.openMenu(menuProvider, buffer -> buffer.writeBlockPos(pos));
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}
