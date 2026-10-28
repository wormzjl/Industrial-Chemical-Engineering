package com.wormzjl.icheme.world.level.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A cell wholly inside a parametric vessel: an invisible full cube with no data of its own. It keeps mobs, fluids and
 * placed blocks out of the vessel. A closed vessel's cell is opaque: it casts its shadow and gives Distant Horizons a
 * solid voxel. An {@link #OPEN} cell (a floating-roof tank's, owner request 2026-10-21: its roof shows from above, plan
 * section 3.3) passes light, hides no face beside it and collides up to the tank's floating roof where it stands now
 * ({@link FloatingRoofs}); the rolling ladder down to the roof is climbable. A {@link #SKYLIGHT} cell (a vessel whose
 * skirt shows its inside, {@link com.wormzjl.icheme.science.equipment.ShapeRasterizer#skylight}) is solid and hides the
 * faces beside it as a closed one, but lets light through as a shell cell does, so the floor inside the skirt is lit as
 * the ground beside the vessel. A save from before the property reads it false: the vessel's next load restates it
 * ({@link EquipmentStructure#restateLater}).
 */
public final class EquipmentInteriorBlock extends Block {
    public static final MapCodec<EquipmentInteriorBlock> CODEC = simpleCodec(EquipmentInteriorBlock::new);
    public static final BooleanProperty OPEN = BooleanProperty.create("open");
    public static final BooleanProperty SKYLIGHT = BooleanProperty.create("skylight");

    public EquipmentInteriorBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(OPEN, false).setValue(SKYLIGHT, false));
    }

    /** Light passes: an open tank's cell or a skylight one. */
    private static boolean clear(BlockState state) {
        return state.getValue(OPEN) || state.getValue(SKYLIGHT);
    }

    @Override
    protected MapCodec<EquipmentInteriorBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(OPEN, SKYLIGHT);
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    protected VoxelShape getOcclusionShape(BlockState state, BlockGetter level, BlockPos position) {
        return state.getValue(OPEN) ? Shapes.empty() : super.getOcclusionShape(state, level, position);
    }

    @Override
    protected int getLightBlock(BlockState state, BlockGetter level, BlockPos position) {
        return clear(state) ? 0 : super.getLightBlock(state, level, position);
    }

    @Override
    protected boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos position) {
        return clear(state) || super.propagatesSkylightDown(state, level, position);
    }

    @Override
    protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos position) {
        return state.getValue(OPEN) ? 1 : super.getShadeBrightness(state, level, position);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos position, CollisionContext context) {
        if (state.getValue(OPEN)) {
            var world = FloatingRoofs.level(level, context);
            if (world != null) {
                var shape = FloatingRoofs.of(world).collision(position, world.getGameTime());
                if (shape != null) return shape;
            }
        }
        return super.getCollisionShape(state, level, position, context);
    }

    @Override
    public boolean isLadder(BlockState state, LevelReader level, BlockPos position, LivingEntity entity) {
        return state.getValue(OPEN) && FloatingRoofs.of(entity.level()).ladder(position, entity, entity.level().getGameTime());
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    /** Using any cell opens the vessel's screen ({@link EquipmentShellBlock#use}); an interior cell finds its vessel through the shell. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
        return EquipmentShellBlock.use(level, position, player, false);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos position, Player player, InteractionHand hand, BlockHitResult hit) {
        return EquipmentShellBlock.useDebugger(stack, level, position, player);
    }

    @Override public BlockState playerWillDestroy(Level level,BlockPos pos,BlockState state,Player player){EquipmentStructure.breaking(level,pos,player);return super.playerWillDestroy(level,pos,state,player);}

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos position, BlockState replacement, boolean moved) {
        if (!level.isClientSide() && !state.is(replacement.getBlock())) {
            var vessel = EquipmentStructure.find(level, position);
            if (vessel != null) EquipmentStructure.dismantle(level, vessel);
        }
        super.onRemove(state, level, position, replacement, moved);
    }
}
