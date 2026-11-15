package com.wormzjl.icheme.world.level.block;

import com.mojang.serialization.MapCodec;
import com.wormzjl.icheme.registry.ModBlockEntities;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.pollution.MonitorSignals;
import com.wormzjl.icheme.runtime.pollution.PollutionService;
import com.wormzjl.icheme.science.pollution.Channel;
import com.wormzjl.icheme.science.pollution.Exposure;
import com.wormzjl.icheme.world.level.block.entity.PollutionMonitorBlockEntity;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A fixed pollution monitor (batch 2026-11-15-pollution-system, WP10; plan section 11): a post-mounted analyser box with
 * a lamp. It reads the four indices of the 16 m cube it stands in; a data cable to its socket (the box's back, as the
 * controller's) lists its channels on the controllers and DCS consoles of that bus. Its front faces the player who
 * placed it. Use prints its tag and reading in chat; it has no menu.
 */
public final class PollutionMonitorBlock extends BaseEntityBlock {
    public static final MapCodec<PollutionMonitorBlock> CODEC = simpleCodec(PollutionMonitorBlock::new);
    public static final DirectionProperty FACING = HorizontalDirectionalBlock.FACING;
    /** The lamp's colour: state, set by the monitor's pass. */
    public static final EnumProperty<MonitorSignals.Lamp> LAMP = EnumProperty.create("lamp", MonitorSignals.Lamp.class);
    /** The socket's distance behind the block's centre: the analyser box's back face, at z = 12 of 16 facing north. */
    public static final double SOCKET_DEPTH = .25;
    /** The block's parts facing north, in sixteenths: foot plate, post, analyser box, lamp and cap (the JSON model's). */
    private static final Map<Direction, VoxelShape> SHAPES = HorizontalShapes.byFacing(List.of(
            new double[] {4, 0, 4, 12, 1, 12},
            new double[] {7, 1, 7, 9, 4, 9},
            new double[] {3, 4, 5, 13, 13, 12},
            new double[] {6, 13, 6.75, 10, 16, 10.75}));
    private static final Channel[] CHANNELS = Channel.values();

    public PollutionMonitorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(LAMP, MonitorSignals.Lamp.DARK));
    }

    /** The light a lit lamp gives (block light 3), none while dark. */
    public static int light(BlockState state) {
        return state.getValue(LAMP) == MonitorSignals.Lamp.DARK ? 0 : 3;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, LAMP);
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
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PollutionMonitorBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide() ? null : createTickerHelper(type, ModBlockEntities.POLLUTION_MONITOR.get(), PollutionMonitorBlockEntity::tick);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return LinePieces.shape(level, pos, SHAPES.get(state.getValue(FACING)));
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer server && level instanceof ServerLevel world && level.getBlockEntity(pos) instanceof PollutionMonitorBlockEntity monitor)
            server.sendSystemMessage(reading(monitor.tag(), PollutionService.find(world.getServer()).orElse(null), world, pos));
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /** The chat line: the tag and the four indices, each in its level's colour, or that pollution is off. */
    private static Component reading(String tag, PollutionService service, ServerLevel level, BlockPos pos) {
        if (!MonitorSignals.enabled(service)) return Component.translatable("pollution.icheme.monitor.off", tag);
        var indices = MonitorSignals.indices(service, level.dimension(), pos);
        var segments = new Object[CHANNELS.length + 1];
        segments[0] = tag;
        for (var channel : CHANNELS) {
            double index = indices[channel.ordinal()];
            var exposure = Exposure.level(index);
            MutableComponent segment = Component.translatable("pollution.icheme.monitor.segment",
                    Component.translatable("pollution.icheme.monitor.channel." + channel.name().toLowerCase(Locale.ROOT)),
                    String.format(Locale.ROOT, "%.2f", index),
                    Component.translatable("pollution.icheme.monitor.level." + exposure.name().toLowerCase(Locale.ROOT)));
            segments[channel.ordinal() + 1] = segment.withStyle(colour(exposure));
        }
        return Component.translatable("pollution.icheme.monitor.line", segments);
    }

    private static ChatFormatting colour(Exposure.Level level) {
        return switch (level) {
            case NONE -> ChatFormatting.GREEN;
            case LOW -> ChatFormatting.YELLOW;
            case MEDIUM -> ChatFormatting.GOLD;
            case HIGH -> ChatFormatting.RED;
            case EXTREME -> ChatFormatting.DARK_RED;
        };
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        // A lamp change keeps the block; a removal takes the socket and its channels off the bus (the cable stays, unwired).
        if (!FluidDeviceBlock.quiet()
                && !state.is(next.getBlock())
                && level instanceof ServerLevel server
                && level.getBlockEntity(pos) instanceof PollutionMonitorBlockEntity monitor
                && monitor.host() > 0)
            FluidWorldAuthority.find(server.getServer()).ifPresent(w -> w.controls().removeMonitor(monitor.host()));
        super.onRemove(state, level, pos, next, moved);
    }
}
