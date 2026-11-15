package com.wormzjl.icheme.world.level.block;

import com.mojang.serialization.MapCodec;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.science.equipment.AlarmGeometry;
import com.wormzjl.icheme.world.inventory.AlarmBeaconMenu;
import com.wormzjl.icheme.world.level.block.entity.AlarmBeaconBlockEntity;

import net.minecraft.core.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;

import java.util.*;

/**
 * The alarm beacon (batch 2026-11-15-fluid-interfaces, W9, decisions A1 and A5): a horn and a beacon lamp on a base, its
 * top facing away from the face it was placed on ({@link #FACING}, six ways). {@link #STATE} is what it shows, set by the
 * control service at its cadence and only when it changes: a lit beacon flashes its lamp and lights its surroundings
 * (block light 12); an active one also sounds its horn (the client's {@code AlarmBeaconSounds}). An empty hand
 * acknowledges an active beacon (a queued engine event) and otherwise opens its screen; Shift and use always opens it.
 */
public final class AlarmBeaconBlock extends BaseEntityBlock {
    public static final MapCodec<AlarmBeaconBlock> CODEC = simpleCodec(AlarmBeaconBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    /** What a beacon shows. Engine state drawn as a block state; NO SIGNAL and OFF show as {@link #CLEAR}. */
    public enum Shown implements StringRepresentable {
        CLEAR,
        ACTIVE,
        ACKNOWLEDGED;

        /** Whether its lamp flashes and lights the cell. */
        public boolean lit() {
            return this != CLEAR;
        }

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public static final EnumProperty<Shown> STATE = EnumProperty.create("state", Shown.class);
    /** Block light of a lit beacon (decision A5). */
    public static final int LIGHT = 12;

    private static final Map<Direction, VoxelShape> SHAPES = shapes();

    public AlarmBeaconBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.UP).setValue(STATE, Shown.CLEAR));
    }

    private static Map<Direction, VoxelShape> shapes() {
        var out = new EnumMap<Direction, VoxelShape>(Direction.class);
        for (var facing : Direction.values()) {
            VoxelShape shape = Shapes.empty();
            for (var part : AlarmGeometry.parts()) {
                var b = AlarmGeometry.turned(part.box(), facing.ordinal());
                shape = Shapes.or(shape, Block.box(b[0], b[1], b[2], b[3], b[4], b[5]));
            }
            out.put(facing, shape.optimize());
        }
        return Map.copyOf(out);
    }

    /**
     * The mounting a beacon's saved socket says (the re-alignment's, registry first): the facing whose socket faces as
     * {@code port} does and lies nearest its centre. Four wall mountings share the socket's direction (down); its centre
     * tells them apart.
     */
    public static Direction facing(com.wormzjl.icheme.runtime.control.DataPort port, BlockPos at) {
        Direction best = Direction.UP;
        double nearest = Double.POSITIVE_INFINITY;
        for (var facing : Direction.values()) {
            if (AlarmGeometry.socketOutward(facing.ordinal()) != port.outward().ordinal()) continue;
            var s = AlarmGeometry.socket(facing.ordinal());
            double d = port.centre().distanceToSqr(at.getX() + s[0] / 16, at.getY() + s[1] / 16, at.getZ() + s[2] / 16);
            if (d < nearest) {
                nearest = d;
                best = facing;
            }
        }
        return best;
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, STATE);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getClickedFace());
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
        return new AlarmBeaconBlockEntity(pos, state);
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
        if (player instanceof ServerPlayer server && level.getBlockEntity(pos) instanceof AlarmBeaconBlockEntity beacon) {
            if (!player.isShiftKeyDown() && state.getValue(STATE) == Shown.ACTIVE) {
                FluidWorldAuthority.find(server.server).ifPresent(w -> w.controls().acknowledge(beacon.identity()));
                server.displayClientMessage(Component.translatable("message.icheme.alarm_beacon.acknowledging"), true);
            } else AlarmBeaconMenu.open(server, pos);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState next, boolean moved) {
        if (!FluidDeviceBlock.quiet()
                && !state.is(next.getBlock())
                && level instanceof ServerLevel server
                && level.getBlockEntity(pos) instanceof AlarmBeaconBlockEntity beacon)
            FluidWorldAuthority.find(server.getServer()).ifPresent(w -> w.controls().removeAlarm(beacon.identity()));
        super.onRemove(state, level, pos, next, moved);
    }
}
