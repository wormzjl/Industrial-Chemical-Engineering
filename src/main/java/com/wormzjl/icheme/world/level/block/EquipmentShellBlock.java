package com.wormzjl.icheme.world.level.block;

import com.mojang.serialization.MapCodec;
import com.wormzjl.icheme.network.AssemblyNetwork;
import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.item.ItemStack;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/**
 * A cell crossed by the surface of a parametric vessel. Its model is generated from its block entity's cell key and
 * baked into the chunk mesh; its collision shape is the vessel's solid inside the cell. It is transparent to light,
 * because a curved quad inside the cell is lit by the cell's own light value; the interior cells cast the shadow.
 */
public final class EquipmentShellBlock extends BaseEntityBlock {
    public static final MapCodec<EquipmentShellBlock> CODEC = simpleCodec(EquipmentShellBlock::new);

    public EquipmentShellBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<EquipmentShellBlock> codec() {
        return CODEC;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos position, BlockState state) {
        return new EquipmentShellBlockEntity(position, state);
    }

    /** Unbound cells (before their data arrives) are full blocks, so nothing falls through while a vessel loads. */
    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos position, CollisionContext context) {
        return LinePieces.shape(level,position,level.getBlockEntity(position) instanceof EquipmentShellBlockEntity shell && shell.key() != null
                ? EquipmentStructure.collision(shell.key()) : Shapes.block());
    }

    /**
     * The cell's solid; in a floating-roof tank, the roof's too where it lies inside the shell (FloatingRoofs#rim; owner
     * report 2026-10-25: the shell's cells collided as the tank's whole inside, and a climber on the rolling ladder met them).
     */
    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos position, CollisionContext context) {
        var solid = getShape(state, level, position, context);
        if (!(level.getBlockEntity(position) instanceof EquipmentShellBlockEntity shell) || shell.shape() == null || shell.shape().kind() != EquipmentShape.Kind.TANK)
            return solid;
        var world = FloatingRoofs.level(level, context);
        return world == null ? solid : FloatingRoofs.join(solid, FloatingRoofs.of(world).rim(position, world.getGameTime()));
    }

    /** A column's or a heater's ladder is climbed where the entity stands in front of its rungs, inside its cage (not anywhere in its cells); other shapes have none. */
    @Override
    public boolean isLadder(BlockState state, net.minecraft.world.level.LevelReader level, BlockPos position, net.minecraft.world.entity.LivingEntity entity) {
        if (!(level.getBlockEntity(position) instanceof EquipmentShellBlockEntity shell) || shell.shape() == null) return false;
        var origin = shell.origin();
        // A floating-roof tank's rolling ladder passes its rim's cells too (owner report 2026-10-21: climbable from the platform).
        return ShapeRasterizer.climbable(shell.shape(), entity.getX() - origin.getX(), entity.getY() - origin.getY(), entity.getZ() - origin.getZ())
                || FloatingRoofs.of(entity.level()).ladder(position, entity, entity.level().getGameTime());
    }

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
    protected float getShadeBrightness(BlockState state, BlockGetter level, BlockPos position) {
        return 1;
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos position, Player player, BlockHitResult hit) {
        return use(level, position, player, false);
    }

    /** The fluid debugger opens functional equipment with diagnostics. */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos position, Player player, InteractionHand hand, BlockHitResult hit) {
        return useDebugger(stack, level, position, player);
    }

    static ItemInteractionResult useDebugger(ItemStack stack, Level level, BlockPos position, Player player) {
        if (!stack.is(ModItems.FLUID_DEBUGGER.get())) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        var vessel = EquipmentStructure.find(level, position);
        if (vessel == null || !vessel.shape().fluidDevice()) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        use(level, position, player, true);
        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }

    /**
     * Using any cell of a vessel, shell or interior; the server decides what opens (batch 2026-10-26-multi-machine-framework,
     * plan section 10): a column opens its assembly GUI; a unit that a column's report holds opens that column's GUI with
     * the unit selected; any other structure opens its own screen ({@link #openOwn}). The fluid debugger opens a fluid
     * device's own screen with diagnostics.
     */
    static InteractionResult use(Level level, BlockPos position, Player player, boolean debug) {
        var vessel = EquipmentStructure.find(level, position);
        if (vessel == null) return InteractionResult.PASS;
        if (player instanceof ServerPlayer server && (debug || !AssemblyNetwork.open(server, vessel, position))) openOwn(server, vessel, position, debug);
        return InteractionResult.sidedSuccess(level.isClientSide());
    }

    /**
     * A structure's own screen: a reservoir (a drum, a floating-roof tank, a sphere) opens its tank screen, as a one-block
     * tank's does, with its datasheet on a tab (owner request 2026-10-16); a mover opens its operating controls with the
     * same datasheet tab; other columns and units open only their datasheet.
     */
    public static void openOwn(ServerPlayer player, EquipmentStructure.Vessel vessel, BlockPos cell, boolean debug) {
        if (!vessel.shape().fluidDevice()) {
            // A motor, generator or gearbox opens its own page (W7, reading A89), its datasheet a push button there.
            var shape = vessel.shape();
            double reach = com.wormzjl.icheme.world.inventory.MachineMenu.REACH + Math.max(shape.sizeX(), Math.max(shape.sizeY(), shape.sizeZ()));
            if (!debug && ShaftParts.kind(shape.kind()) != null && com.wormzjl.icheme.network.MachineNetwork.open(player, vessel.origin(), reach, true)) return;
            AssemblyNetwork.datasheet(player, cell);
            return;
        }
        var level = player.serverLevel();
        var fluid = ShapeRasterizer.fluidCell(vessel.shape());
        var node = vessel.origin().offset(fluid.x(), fluid.y(), fluid.z());
        if (level.isLoaded(node) && level.getBlockEntity(node) instanceof EquipmentShellBlockEntity entity) entity.open(player, debug);
    }

    /** A pipe placed, removed or changed in front of a nozzle: the vessel redraws its nozzles at the pipe's size. */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos position, net.minecraft.world.level.block.Block neighbor, BlockPos neighborPosition, boolean moved) {
        super.neighborChanged(state, level, position, neighbor, neighborPosition, moved);
        if (!level.isClientSide() && level instanceof net.minecraft.server.level.ServerLevel server)
            // After the pipe's own shape update has taken its connection.
            server.getServer().execute(() -> EquipmentStructure.refreshNozzlesAt(level, position));
    }

    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos position, Player player, boolean harvest,
                                       net.minecraft.world.level.material.FluidState fluid) {
        return EquipmentProtection.playerRemoval(level, position, player,
                () -> super.onDestroyedByPlayer(state, level, position, player, harvest, fluid));
    }

    @Override
    public boolean canEntityDestroy(BlockState state, BlockGetter level, BlockPos position, net.minecraft.world.entity.Entity entity) {
        return false;
    }

    @Override
    public void onBlockExploded(BlockState state, Level level, BlockPos position, net.minecraft.world.level.Explosion explosion) {
        // Explosions never authorise deconstruction, even when a custom blast ignores resistance.
    }

    @Override public BlockState playerWillDestroy(Level level,BlockPos pos,BlockState state,Player player){EquipmentStructure.breaking(level,pos,player);return super.playerWillDestroy(level,pos,state,player);}

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos position, BlockState replacement, boolean moved) {
        if (!state.is(replacement.getBlock()) && EquipmentProtection.editing(level, position) && level.getBlockEntity(position) instanceof EquipmentShellBlockEntity shell && shell.shape() != null)
            EquipmentStructure.dismantle(level, new EquipmentStructure.Vessel(shell.origin(), shell.shape()));
        super.onRemove(state, level, position, replacement, moved);
    }
}
