package com.wormzjl.icheme.world.level.block;

import com.mojang.serialization.MapCodec;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.runtime.fluid.WorldTopologyLedger;
import com.wormzjl.icheme.science.equipment.RackGeometry;
import com.wormzjl.icheme.world.level.block.entity.PipeRackBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * A layered pipe rack cell (batch 2026-10-26-pipe-laying-system, RACK_LAYERS_PLAN option A): one tier of four positions
 * across its run, carrying up to four laid lanes ({@link PipeRackBlockEntity}). Its facing orders the positions and sets
 * the run; racks in line join into one run whatever their facings. Its shape, straight, a corner or a tee, is decided
 * once when it is placed, from the racks whose runs point into it and the player's look ({@link RackGeometry#placed},
 * RACK_JUNCTION_PLAN J1 and J2; sneaking places it straight along the look), and never changes after: neighbours placed
 * or broken later redraw only its steel and joins. A rack that carries lanes is not broken (owner decision D9): its
 * pipes are dismantled first, with the laying tool.
 */
public final class PipeRackBlock extends BaseEntityBlock {
    public static final MapCodec<PipeRackBlock> CODEC = simpleCodec(PipeRackBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final EnumProperty<Shape> SHAPE = EnumProperty.create("shape", Shape.class);

    /** A rack cell's shape, in {@link RackGeometry}'s shape order: straight, a corner from an end to a side, or a tee. Saved by name: new shapes go last. */
    public enum Shape implements StringRepresentable {
        STRAIGHT, BACK_LEFT, BACK_RIGHT, FRONT_LEFT, FRONT_RIGHT, TEE;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public PipeRackBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(SHAPE, Shape.STRAIGHT));
    }

    @Override
    protected MapCodec<PipeRackBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, SHAPE);
    }

    /** The lane geometry of a rack cell in this state: its facing and corner. */
    public static RackGeometry.Rack rack(BlockState state) {
        return new RackGeometry.Rack(state.getValue(FACING).ordinal(), state.getValue(SHAPE).ordinal());
    }

    /** A rack cell's frame as the registry keeps it for its lanes (batch 2026-11-03-save-corruption, schema 9). */
    public static WorldTopologyLedger.RackFrame frame(BlockState state) {
        return new WorldTopologyLedger.RackFrame(PhysicalFluidTopology.Direction.valueOf(state.getValue(FACING).name()), WorldTopologyLedger.RackShape.valueOf(state.getValue(SHAPE).name()));
    }

    /** The rack cell of {@code frame}. */
    public BlockState stateFor(WorldTopologyLedger.RackFrame frame) {
        return defaultBlockState().setValue(FACING, Direction.valueOf(frame.facing().name())).setValue(SHAPE, Shape.valueOf(frame.shape().name()));
    }

    /** The frame of {@code rack}'s geometry. */
    public static WorldTopologyLedger.RackFrame frame(RackGeometry.Rack rack) {
        return new WorldTopologyLedger.RackFrame(PhysicalFluidTopology.Direction.values()[rack.facing()], WorldTopologyLedger.RackShape.values()[rack.corner()]);
    }

    /** Shaped once by the racks next door and the player's horizontal look; sneaking, straight along the look. */
    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return placed(context.getLevel(), context.getClickedPos(), context.getHorizontalDirection(), context.isSecondaryUseActive());
    }

    /** The state a rack placed at {@code position} with the player looking {@code look} takes ({@link RackGeometry#placed}). */
    public BlockState placed(BlockGetter level, BlockPos position, Direction look, boolean sneaking) {
        var rack = RackGeometry.placed(neighbours(level, position), look.ordinal(), sneaking);
        return defaultBlockState().setValue(FACING, Direction.values()[rack.facing()]).setValue(SHAPE, Shape.values()[rack.corner()]);
    }

    /** The racks next to {@code position}, as {@link RackGeometry} sees them. */
    public static RackGeometry.Neighbours neighbours(BlockGetter level, BlockPos position) {
        return face -> {
            var neighbour = level.getBlockState(position.relative(Direction.values()[face]));
            return neighbour.getBlock() instanceof PipeRackBlock ? rack(neighbour) : null;
        };
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos position, BlockState state) {
        return new PipeRackBlockEntity(position, state);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos position, CollisionContext context) {
        return LinePieces.shape(level,position,level.getBlockEntity(position) instanceof PipeRackBlockEntity rack ? rack.shape() : PipeRackBlockEntity.frame(rack(state)));
    }

    /** A neighbour changed: the shape stays (J1); the drawn steel and the faces its lanes meet redraw once the change is in the world. */
    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour, LevelAccessor level, BlockPos position, BlockPos neighbourPosition) {
        if (level instanceof ServerLevel server) server.getServer().execute(() -> {
            if (server.getBlockEntity(position) instanceof PipeRackBlockEntity rack) rack.redraw();
        });
        return super.updateShape(state, direction, neighbour, level, position, neighbourPosition);
    }

    private static boolean carries(BlockGetter level, BlockPos position) {
        return level.getBlockEntity(position) instanceof PipeRackBlockEntity rack && (!rack.lanes().isEmpty()||!rack.cables().isEmpty());
    }

    /** A rack with lanes cannot be mined (D9): its pipes come out first. */
    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos position) {
        return carries(level, position) ? 0 : super.getDestroyProgress(state, player, level, position);
    }

    /** A path that removes the block for a player without the break event below keeps it too. */
    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos position, Player player, boolean willHarvest, FluidState fluid) {
        return !carries(level, position) && super.onDestroyedByPlayer(state, level, position, player, willHarvest, fluid);
    }

    /**
     * A player's break of a rack with lanes is cancelled (a creative player's instant break too). A cancelled break sends
     * the player the block and its lanes again, which the client had already removed.
     */
    @EventBusSubscriber(modid = IChemE.MOD_ID)
    public static final class Breaking {
        private Breaking() {
        }

        @SubscribeEvent
        public static void refuse(BlockEvent.BreakEvent event) {
            if (!(event.getState().getBlock() instanceof PipeRackBlock) || !carries(event.getLevel(), event.getPos())) return;
            event.setCanceled(true);
            event.getPlayer().displayClientMessage(LineLaying.message("rack_carries"), true);
        }
    }

    /** An explosion leaves a rack with lanes standing, as it cannot be mined. */
    @Override
    public void onBlockExploded(BlockState state, Level level, BlockPos position, Explosion explosion) {
        if (!carries(level, position)) super.onBlockExploded(state, level, position, explosion);
    }

    /** Removed any other way (a command), its lanes leave the ledger with it; not while {@link FluidDeviceBlock#quietly} edits the world. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos position, BlockState replacement, boolean moved) {
        if (!FluidDeviceBlock.registered && state.getBlock() != replacement.getBlock() && level instanceof ServerLevel server && level.getBlockEntity(position) instanceof PipeRackBlockEntity rack)
            FluidWorldAuthority.find(server.getServer()).ifPresent(world -> {
                for (var lane : rack.lanes()) world.remove(lane.identity());
                for(var cable:rack.cables())world.controls().removeCable(cable.site());
            });
        super.onRemove(state, level, position, replacement, moved);
    }
}
