package com.wormzjl.icheme.world.level.block;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.WorldTopologyLedger;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.EquipmentRefresh;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Whole parametric vessels in the world: placement, removal of every cell when one goes (owner decision D5, batch
 * 2026-10-15-custom-equipment-rendering), lookup of the vessel a cell belongs to, and the collision shape of each cell
 * key. The equipment constructor places vessels; {@code /icheme equipment place} does too, for debugging. A vessel
 * whose cells an older layout of its shape placed is brought to the current layout after it loads ({@link #refreshLater},
 * {@link EquipmentRefresh}); one whose cells differ from the registry's record of it is rebuilt to the record
 * ({@link StructureRealignment#serveRealignment}).
 */
@EventBusSubscriber(modid = IChemE.MOD_ID)
public final class EquipmentStructure {
    /** A vessel in the world: its shape and its origin cell (lower north-west corner of its box). */
    public record Vessel(BlockPos origin, EquipmentShape shape) {
        /** The controller cell: the role of one shell cell ({@link ShapeRasterizer.Layout#controller()}), not a block of its own. */
        public BlockPos controller() {
            var cell = ShapeRasterizer.layout(shape).controller();
            return origin.offset(cell.x(), cell.y(), cell.z());
        }
    }

    private static final int SHAPE_CACHE = 4096;
    private static final com.wormzjl.icheme.science.equipment.BoundedCache<ShapeRasterizer.CellKey, VoxelShape> SHAPES = new com.wormzjl.icheme.science.equipment.BoundedCache<>(SHAPE_CACHE);
    /** Set while a vessel is being removed, so the cells it removes do not start removals of their own (server thread only). */
    static boolean dismantling;

    private EquipmentStructure() {
    }

    /** All verified layout/removal writes share the same narrowly scoped authority. */
    static boolean setCell(Level level, BlockPos position, BlockState state, int flags) {
        return EquipmentProtection.edit(level, position, () -> level.setBlock(position, state, flags));
    }

    /** Collision and outline shape of a shell cell: its solid at 1/8 block. Both sides compute the same shape. */
    public static VoxelShape collision(ShapeRasterizer.CellKey key) {
        return SHAPES.computeIfAbsent(key, k -> {
            double s = ShapeRasterizer.SOLID_RESOLUTION;
            VoxelShape union = Shapes.empty();
            for (var box : ShapeRasterizer.solid(k))
                union = Shapes.joinUnoptimized(union, Shapes.box(box.x0() / s, box.y0() / s, box.z0() / s, box.x1() / s, box.y1() / s, box.z1() / s), BooleanOp.OR);
            return union.optimize();
        });
    }

    /**
     * Places a vessel whose box starts at {@code origin}, painted {@code paint}. Only cells below {@code rows} are
     * placed, which builds a vessel part way. Every cell of the whole vessel must be loaded, inside the build height and
     * replaceable.
     *
     * @return the number of cells placed
     * @throws IllegalStateException naming the first cell that blocks the vessel
     */
    public static int place(ServerLevel level, BlockPos origin, EquipmentShape shape, Paint paint, int rows) {
        return place(level, origin, shape, paint, com.wormzjl.icheme.science.equipment.DeviceName.NONE, rows);
    }

    /**
     * Names the structure {@code vessel} (N1): every shell cell takes {@code name} (cleaned), as every cell holds its
     * paint, and sends itself to the clients. Nothing changes, and false, while a shell cell is not loaded: the name stays
     * the same on every cell.
     */
    public static boolean rename(ServerLevel level, Vessel vessel, String name) {
        var cells = ShapeRasterizer.layout(vessel.shape()).shell();
        for (var cell : cells) if (!level.isLoaded(vessel.origin().offset(cell.x(), cell.y(), cell.z()))) return false;
        for (var cell : cells)
            if (level.getBlockEntity(vessel.origin().offset(cell.x(), cell.y(), cell.z())) instanceof EquipmentShellBlockEntity entity) entity.rename(name);
        // The registry's record holds the name too (registry first, W5c C4): a structure placed again takes it.
        var world = FluidWorldAuthority.find(level.getServer()).orElse(null);
        var at = Positions.of(level, vessel.origin());
        var held = world == null ? null : world.structure(at);
        if (held != null) world.structure(at, held.withName(com.wormzjl.icheme.science.equipment.DeviceName.clean(name)));
        return true;
    }

    /**
     * {@link #place(ServerLevel, BlockPos, EquipmentShape, Paint, int)}, every cell named {@code name} (N1: an imported
     * design's tag). A whole structure built with no name takes its default plant tag ({@link #defaultName}): the next
     * free number of its kind's prefix in its dimension.
     */
    public static int place(ServerLevel level, BlockPos origin, EquipmentShape shape, Paint paint, String name, int rows) {
        var layout = ShapeRasterizer.layout(shape);
        for (var list : java.util.List.of(layout.shell(), layout.interior()))
            for (var cell : list) {
                var position = origin.offset(cell.x(), cell.y(), cell.z());
                if (level.isOutsideBuildHeight(position) || !level.isLoaded(position))
                    throw new IllegalStateException("Cell " + position.toShortString() + " is outside the loaded world");
                if (!level.getBlockState(position).canBeReplaced())
                    throw new IllegalStateException("Cell " + position.toShortString() + " is occupied by " + level.getBlockState(position).getBlock().getName().getString());
            }
        if (name.isEmpty() && rows >= shape.sizeY()) name = defaultName(level, origin, shape);
        int placed = 0, flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        for (var cell : layout.shell()) {
            if (cell.y() >= rows) continue;
            var position = origin.offset(cell.x(), cell.y(), cell.z());
            setCell(level, position, ModBlocks.EQUIPMENT_SHELL.get().defaultBlockState(), flags);
            if (level.getBlockEntity(position) instanceof EquipmentShellBlockEntity entity) entity.bind(shape, cell, paint, name);
            placed++;
        }
        for (var cell : layout.interior()) {
            if (cell.y() >= rows) continue;
            setCell(level, origin.offset(cell.x(), cell.y(), cell.z()), interior(shape), flags);
            placed++;
        }
        var vessel = new Vessel(origin, shape);
        if (rows >= shape.sizeY()) record(level, origin, shape, paint, name);
        if (shape.fluidDevice() && rows >= shape.sizeY()) StructureRegistration.register(level, vessel);
        // A motor, generator or gearbox joins its shaft train whole (W3); a casing or turbine does once registered.
        else if (!shape.fluidDevice() && rows >= shape.sizeY()) ShaftParts.structure(level, origin, shape, 0);
        if(shape.kind()==EquipmentShape.Kind.COLUMN&&rows>=shape.sizeY()){var c=layout.controller();if(level.getBlockEntity(origin.offset(c.x(),c.y(),c.z())) instanceof EquipmentShellBlockEntity cell)FluidWorldAuthority.find(level.getServer()).ifPresent(w->w.controls().bindColumn(cell));}
        reconnect(level, vessel);
        refreshNozzles(level, vessel);
        com.wormzjl.icheme.runtime.assembly.AssemblyService.placed(level, vessel);
        return placed;
    }

    /**
     * Rebinds a saved registration, or queues defaults for a complete placed structure with none ({@link
     * StructureRegistration#restoreFluidBinding}). Returns false only while chunks are missing, so a later chunk-load event
     * retries it.
     */
    public static boolean restoreFluidBinding(ServerLevel level,Vessel vessel) {
        return StructureRegistration.restoreFluidBinding(level, vessel);
    }

    /** The registrations of a unit's sides, by side: those held at {@code ids} and their partners. */
    static Map<HeatSides.Side, WorldTopologyLedger.Registration> unitRecords(FluidWorldAuthority world, java.util.Collection<Long> ids) {
        var found = new EnumMap<HeatSides.Side, WorldTopologyLedger.Registration>(HeatSides.Side.class);
        for (long id : ids) {
            var record = world.registrations().get(id);
            if (record == null || record.device().heat() == null) continue;
            found.put(record.device().heat().side(), record);
            var partner = world.registrations().get(record.device().heat().partner());
            if (partner != null && partner.device().heat() != null) found.putIfAbsent(partner.device().heat().side(), partner);
        }
        return found;
    }

    /** The heat sides registered at {@code vessel}'s nodes, by registry identity: a heat unit's sides (a column's condenser, C6b). */
    public static List<Long> heatSides(FluidWorldAuthority world, ServerLevel level, Vessel vessel) {
        var sides = new ArrayList<Long>();
        for (long id : heldAtNodes(world, level, vessel)) {
            var r = world.registrations().get(id);
            if (r != null && r.device().heat() != null) sides.add(id);
        }
        return sides;
    }

    /** The fluid movers registered at {@code vessel}'s nodes: a pump casing's (W5 of batch 2026-11-10-column-failure-modes). */
    public static List<Long> movers(FluidWorldAuthority world, ServerLevel level, Vessel vessel) {
        var out = new ArrayList<Long>();
        for (long id : heldAtNodes(world, level, vessel)) {
            var r = world.registrations().get(id);
            if (r != null && r.device().control() instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.Mover) out.add(id);
        }
        return out;
    }

    /** The identities registered at {@code vessel}'s nodes. */
    static List<Long> heldAtNodes(FluidWorldAuthority world, ServerLevel level, Vessel vessel) {
        var ids = new ArrayList<Long>();
        for (var node : nodes(vessel)) {
            var held = Positions.of(level, node);
            heldAt(world, held).ifPresent(r -> ids.add(r.device().id()));
        }
        return ids;
    }

    /**
     * The registration at {@code at} of the device that stands there with ports: a structure's device at its node, not a
     * pipe or a one-block device registered at the same cell.
     */
    public static java.util.Optional<WorldTopologyLedger.Registration> heldAt(FluidWorldAuthority world, PhysicalFluidTopology.Position at) {
        return world.at(at).filter(r -> r.device().position().equals(at) && !r.device().ports().isEmpty());
    }

    /**
     * The registration bound at each of {@code vessel}'s nodes, in {@link ShapeRasterizer#fluidCells}' order (a unit's sides,
     * then a kettle's shell), 0 where a node is not loaded or not bound (batch 2026-10-27-heat-exchanger-design, W8).
     */
    public static long[] sideIdentities(Level level, Vessel vessel) {
        var nodes = nodes(vessel);
        var ids = new long[nodes.size()];
        for (int i = 0; i < ids.length; i++)
            if (level.isLoaded(nodes.get(i)) && level.getBlockEntity(nodes.get(i)) instanceof EquipmentShellBlockEntity shell) ids[i] = shell.fluidIdentity();
        return ids;
    }

    /** Each node of {@code vessel}, one per registration ({@link ShapeRasterizer#fluidCells}). */
    static List<BlockPos> nodes(Vessel vessel) {
        var nodes = new ArrayList<BlockPos>();
        for (var cell : ShapeRasterizer.fluidCells(vessel.shape())) nodes.add(vessel.origin().offset(cell.x(), cell.y(), cell.z()));
        return nodes;
    }

    /**
     * The registry's record of the structure at {@code origin} (batch 2026-11-03-save-corruption, REGISTRY_FIRST_PLAN.md
     * 3.3): its shape and paint, or none when {@code shape} is null. The registry places the structure again from it. The
     * record's shape text is made only when the record changes.
     */
    /**
     * The plant tag a new structure of {@code shape}'s kind at {@code origin} takes ({@link
     * com.wormzjl.icheme.science.equipment.PlantTag}): beside the names the registry's structures of its dimension hold.
     * No name without a fluid world.
     */
    static String defaultName(ServerLevel level, BlockPos origin, EquipmentShape shape) {
        var world = FluidWorldAuthority.find(level.getServer()).orElse(null);
        if (world == null) return com.wormzjl.icheme.science.equipment.DeviceName.NONE;
        return com.wormzjl.icheme.science.equipment.PlantTag.tag(shape.kind(), world.structureNames(Positions.of(level, origin).dimension()));
    }

    static void record(ServerLevel level, BlockPos origin, @Nullable EquipmentShape shape, @Nullable Paint paint, String name) {
        var world = FluidWorldAuthority.find(level.getServer()).orElse(null);
        if (world == null) return;
        var at = Positions.of(level, origin);
        var held = world.structure(at);
        if (shape == null) {
            if (held != null) world.structure(at, null);
        } else if (held == null || !held.shape().equals(shape) || !held.paint().equals(paint) || !held.name().equals(name))
            world.structure(at, WorldTopologyLedger.Structure.of(shape, paint, name));
    }

    /** The pipes in front of the vessel's nozzles take up (or drop) their connection: the cells were set without shape updates. */
    static void reconnect(Level level, Vessel vessel) {
        for (var port : ShapeRasterizer.ports(vessel.shape())) {
            var at = vessel.origin().offset(port.cell().x() + port.dx(), port.cell().y() + port.dy(), port.cell().z() + port.dz());
            if (!level.isLoaded(at)) continue;
            var state = level.getBlockState(at);
            if (state.getBlock() instanceof FluidDeviceBlock) Block.updateOrDestroy(state, Block.updateFromNeighbourShapes(state, level, at), level, at, Block.UPDATE_ALL);
        }
    }

    /**
     * Server: each nozzle drawn at the size of the pipe connected to it (owner decisions 2026-10-16: its flange always the
     * pipe's; 2026-10-21: its neck too, a 1 m pipe has a 1 m root); an unconnected nozzle shows its default size under a
     * blind flange. Only the cells the nozzles reach change, each keeping the sizes of the nozzles reaching it.
     */
    public static void refreshNozzles(Level level, Vessel vessel) {
        if (level.isClientSide()) return;
        var shape = vessel.shape();
        var nozzles = shape.nozzles();
        var outside = new double[nozzles.size()];
        for (var port : ShapeRasterizer.ports(shape)) {
            var at = vessel.origin().offset(port.cell().x() + port.dx(), port.cell().y() + port.dy(), port.cell().z() + port.dz());
            var towards = Direction.fromDelta(-port.dx(), -port.dy(), -port.dz());
            if (towards == null || !level.isLoaded(at)) continue;
            var state = level.getBlockState(at);
            if (state.getBlock() instanceof FluidDeviceBlock block && block.kind() == TopologyCompiler.Kind.PIPE && state.getValue(FluidDeviceBlock.connection(towards))
                    && level.getBlockEntity(at) instanceof FluidDeviceBlockEntity pipe)
                outside[port.nozzle()] = PipeSizes.outside(pipe.bore());
        }
        var cells = new java.util.HashSet<ShapeRasterizer.Cell>();
        for (int k = 0; k < nozzles.size(); k++) cells.addAll(ShapeRasterizer.nozzleCells(shape, k));
        for (var cell : cells) {
            var position = vessel.origin().offset(cell.x(), cell.y(), cell.z());
            if (level.isLoaded(position) && level.getBlockEntity(position) instanceof EquipmentShellBlockEntity entity && vessel.origin().equals(entity.origin()))
                entity.nozzles(ShapeRasterizer.cellCodes(shape, cell, outside));
        }
    }

    /** Server: when a pipe beside {@code position} changed, redraw the nozzles of the vessel whose port cell it is. */
    public static void refreshNozzlesAt(Level level, BlockPos position) {
        if (level.isClientSide() || !(level.getBlockEntity(position) instanceof EquipmentShellBlockEntity shell) || shell.shape() == null) return;
        var cell = shell.cell();
        for (var port : ShapeRasterizer.ports(shell.shape()))
            if (port.cell().equals(cell)) {
                refreshNozzles(level, new Vessel(shell.origin(), shell.shape()));
                return;
            }
    }

    /**
     * The vessel a shell or interior cell belongs to. An interior cell has no data of its own: walking east, or south
     * when east runs along a lying vessel's axis, crosses the convex cross-section and reaches a shell cell of the same
     * vessel within a diameter.
     */
    public static @Nullable Vessel find(BlockGetter level, BlockPos position) {
        var shell = shellReached(level, position);
        if (shell == null) return null;
        var vessel = new Vessel(shell.origin(), shell.shape());
        return contains(vessel, position) ? vessel : null;
    }

    /**
     * The vessel whose origin is {@code origin}, or null. A round shell's origin corner is no cell of it: its bottom layer,
     * which every vessel's cells reach, is searched for one of its shell cells within the widest vessel's reach.
     */
    public static @Nullable Vessel at(BlockGetter level, BlockPos origin) {
        var vessel = find(level, origin);
        if (vessel != null && vessel.origin().equals(origin)) return vessel;
        for (int dx = 0; dx <= FIND_STEPS; dx++)
            for (int dz = 0; dz <= FIND_STEPS; dz++)
                if (level.getBlockEntity(origin.offset(dx, 0, dz)) instanceof EquipmentShellBlockEntity shell && shell.shape() != null && origin.equals(shell.origin()))
                    return new Vessel(origin, shell.shape());
        return null;
    }

    /**
     * The name of the device at {@code position} (N1): a one-block device's, or the structure's whose cell or interior that
     * is, read from its loaded cells; empty when it has none or none is loaded.
     */
    public static String name(BlockGetter level, BlockPos position) {
        if (level.getBlockEntity(position) instanceof com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity device) return device.name();
        var vessel = find(level, position);
        return vessel == null ? com.wormzjl.icheme.science.equipment.DeviceName.NONE : name(level, vessel);
    }

    /** The name of {@code vessel}, from its first loaded shell cell (every cell holds it); empty when it has none or none is loaded. */
    public static String name(BlockGetter level, Vessel vessel) {
        var layout = ShapeRasterizer.layout(vessel.shape());
        var controller = layout.controller();
        if (level.getBlockEntity(vessel.origin().offset(controller.x(), controller.y(), controller.z())) instanceof EquipmentShellBlockEntity shell && shell.shape() != null)
            return shell.name();
        for (var cell : layout.shell())
            if (level.getBlockEntity(vessel.origin().offset(cell.x(), cell.y(), cell.z())) instanceof EquipmentShellBlockEntity shell && shell.shape() != null) return shell.name();
        return com.wormzjl.icheme.science.equipment.DeviceName.NONE;
    }

    /** The longest walk over interior cells: across the widest body, a column's or a floating-roof tank's. */
    static final int FIND_STEPS = (int) Math.ceil(Math.max(EquipmentShape.MAX_COLUMN_DIAMETER, UnitDesign.of(EquipmentShape.Kind.TANK).maxSize())) + 2;

    /**
     * The interior cell of {@code shape}: a floating-roof tank's is open (its roof shows from above); a vessel's whose
     * skirt shows its inside lets skylight through ({@link ShapeRasterizer#skylight}).
     */
    static net.minecraft.world.level.block.state.BlockState interior(EquipmentShape shape) {
        return ModBlocks.EQUIPMENT_INTERIOR.get().defaultBlockState().setValue(EquipmentInteriorBlock.OPEN, shape.kind() == EquipmentShape.Kind.TANK)
                .setValue(EquipmentInteriorBlock.SKYLIGHT, ShapeRasterizer.skylight(shape));
    }

    /** Server, from the load of a vessel's controller cell when its layout is current: {@link StructureRefresh#restateLater}. */
    public static void restateLater(ServerLevel level, BlockPos origin, EquipmentShape shape) {
        StructureRefresh.restateLater(level, origin, shape);
    }

    /** The first bound shell cell a walk east, or else south, over interior cells reaches from {@code position}, or null. */
    static @Nullable EquipmentShellBlockEntity shellReached(BlockGetter level, BlockPos position) {
        for (var direction : new Direction[]{Direction.EAST, Direction.SOUTH})
            for (int step = 0; step <= FIND_STEPS; step++) {
                var at = position.relative(direction, step);
                if (level.getBlockEntity(at) instanceof EquipmentShellBlockEntity shell && shell.shape() != null) return shell;
                if (step > 0 && !level.getBlockState(at).is(ModBlocks.EQUIPMENT_INTERIOR.get())) break;
            }
        return null;
    }

    static void breaking(Level level,BlockPos position,net.minecraft.world.entity.player.Player player){var vessel=find(level,position);if(vessel==null||!vessel.shape().fluidDevice())return;var cell=ShapeRasterizer.fluidCell(vessel.shape());if(cell!=null&&level.getBlockEntity(vessel.origin().offset(cell.x(),cell.y(),cell.z())) instanceof EquipmentShellBlockEntity shell)shell.instruments().recoverOnBreak(!player.isCreative());}

    /**
     * Removes every remaining cell of the vessel; nothing drops. Not while the registry restores the world ({@link
     * FluidDeviceBlock#quietly}): a cell removed then keeps the structure's record and registrations.
     */
    static void dismantle(Level level, Vessel vessel) {
        if (dismantling || FluidDeviceBlock.registered || level.isClientSide()) return;
        dismantling = true;
        try {
            if (level instanceof ServerLevel server) com.wormzjl.icheme.runtime.assembly.AssemblyService.removed(server, vessel);
            if (level instanceof ServerLevel server) com.wormzjl.icheme.runtime.assembly.AssemblyService.dismantled(server, vessel);
            if(level instanceof ServerLevel server&&vessel.shape().kind()==EquipmentShape.Kind.COLUMN){var c=ShapeRasterizer.layout(vessel.shape()).controller();if(level.getBlockEntity(vessel.origin().offset(c.x(),c.y(),c.z())) instanceof EquipmentShellBlockEntity cell&&cell.instruments().dataPort()!=null)FluidWorldAuthority.find(server.getServer()).ifPresent(w->w.controls().removeHost(cell.instruments().dataPort().host()));}
            if (level instanceof ServerLevel server && vessel.shape().fluidDevice()) StructureRegistration.unregister(server, vessel);
            if (level instanceof ServerLevel server) record(server, vessel.origin(), null, null, com.wormzjl.icheme.science.equipment.DeviceName.NONE);
            if (UnitDesign.shafted(vessel.shape().kind())) ShaftParts.remove(level, vessel.origin());
            var layout = ShapeRasterizer.layout(vessel.shape());
            for (var list : java.util.List.of(layout.shell(), layout.interior()))
                for (var cell : list) {
                    var position = vessel.origin().offset(cell.x(), cell.y(), cell.z());
                    var state = level.getBlockState(position);
                    boolean ours = state.is(ModBlocks.EQUIPMENT_INTERIOR.get())
                            || level.getBlockEntity(position) instanceof EquipmentShellBlockEntity shell && vessel.origin().equals(shell.origin());
                    if (ours) setCell(level, position, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            reconnect(level, vessel);
        } finally {
            dismantling = false;
        }
    }

    /** Server, from a shell cell's load: an older layout of its shape placed it ({@link StructureRefresh#refreshLater}). */
    public static void refreshLater(ServerLevel level, BlockPos origin, EquipmentShape shape, int[] box, BlockPos cell) {
        StructureRefresh.refreshLater(level, origin, shape, box, cell);
    }

    /** Server, from the load of a shell cell whose kind this build no longer has ({@link StructureRefresh#removeRetiredLater}). */
    public static void removeRetiredLater(ServerLevel level, BlockPos origin, String kind, int[] box, BlockPos cell) {
        StructureRefresh.removeRetiredLater(level, origin, kind, box, cell);
    }

    /** At most one retired, stale or restated vessel a tick ({@link StructureRefresh#onLevelTick}). */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        StructureRefresh.onLevelTick(event);
    }

    /** Server, from {@link FluidWorldAuthority#loadedChunk}: {@link StructureRealignment#realignLater}. */
    public static void realignLater(ServerLevel level, BlockPos origin) {
        StructureRealignment.realignLater(level, origin);
    }

    static boolean ours(EquipmentShellBlockEntity shell, BlockPos origin, EquipmentShape shape) {
        return origin.equals(shell.origin()) && shape.equals(shell.shape());
    }

    private static boolean contains(Vessel vessel, BlockPos position) {
        int x = position.getX() - vessel.origin().getX(), y = position.getY() - vessel.origin().getY(), z = position.getZ() - vessel.origin().getZ();
        return x >= 0 && x < vessel.shape().sizeX() && y >= 0 && y < vessel.shape().sizeY() && z >= 0 && z < vessel.shape().sizeZ();
    }

    /**
     * {@code /icheme equipment place <bottom centre> <diameter m> <tangent length m> [rows | vertical <skirt m>
     * [front [outlet <turns>]] | horizontal <saddle m> [front [boot]]]} (operators only). Without a support the drum is
     * bare: vertical, on its lower head. The front (north, east, south, west; north by default) is where a standing drum's
     * liquid outlet and a lying drum's front head face; {@code outlet} turns a skirted drum's bottom outlet that many
     * quarter turns from the front (0 to 3: front, left, back, right, owner request 2026-10-25); {@code boot} hangs a water
     * boot under a lying drum's front end. {@code /icheme equipment column <bottom centre> <diameter m> <trays> <skirt m>
     * [front]} places a column fed at its middle tray, its piping side to the front. {@code /icheme equipment unit
     * <bottom centre> <kind> <size m> <length> <support m> [front [parallel series | outlet <turns>]]} places a unit of any
     * unit kind, by its lower-case name. The kind's design gives the meaning and ranges of the size, the second size (a
     * length, a height or a count: an air cooler's size and length are a bay's, a compressor's its throws, 1 where the kind
     * has none) and the support (0 where the unit stands at grade); its channel, suction, feed or process nozzles face the
     * front, its bottom outlet as turned where its design turns it (a trickle-bed reactor's effluent).
     */
    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        var rows = Commands.argument("rows", IntegerArgumentType.integer(1))
                .executes(context -> place(context, IntegerArgumentType.getInteger(context, "rows"), EquipmentShape.Orientation.VERTICAL, 0, EquipmentShape.Heading.NORTH, false, 0));
        var tangentLength = Commands.argument("tangentLength", IntegerArgumentType.integer(1, (int) EquipmentShape.MAX_TANGENT_LENGTH))
                .executes(context -> place(context, Integer.MAX_VALUE, EquipmentShape.Orientation.VERTICAL, 0, EquipmentShape.Heading.NORTH, false, 0)).then(rows);
        for (var orientation : EquipmentShape.Orientation.values()) {
            var support = Commands.argument("support", IntegerArgumentType.integer(0, EquipmentShape.MAX_SUPPORT))
                    .executes(context -> place(context, Integer.MAX_VALUE, orientation, IntegerArgumentType.getInteger(context, "support"), EquipmentShape.Heading.NORTH, false, 0));
            for (var front : EquipmentShape.Heading.values())
                support.then(Commands.literal(front.name().toLowerCase(Locale.ROOT))
                        .executes(context -> place(context, Integer.MAX_VALUE, orientation, IntegerArgumentType.getInteger(context, "support"), front, false, 0))
                        .then(Commands.literal("boot").executes(context -> place(context, Integer.MAX_VALUE, orientation, IntegerArgumentType.getInteger(context, "support"), front, true, 0)))
                        .then(Commands.literal("outlet").then(Commands.argument("outlet", IntegerArgumentType.integer(0, EquipmentShape.CONNECTION_SIDES - 1))
                                .executes(context -> place(context, Integer.MAX_VALUE, orientation, IntegerArgumentType.getInteger(context, "support"), front, false,
                                        IntegerArgumentType.getInteger(context, "outlet"))))));
            tangentLength.then(Commands.literal(orientation.name().toLowerCase(Locale.ROOT)).then(support));
        }
        var diameter = Commands.argument("diameter", DoubleArgumentType.doubleArg(EquipmentShape.MIN_DIAMETER, EquipmentShape.MAX_DIAMETER)).then(tangentLength);
        var place = Commands.literal("place").then(Commands.argument("position", BlockPosArgument.blockPos()).then(diameter));
        // A column: diameter, tray count (feed at the middle tray) and skirt height; its feed side faces the front.
        var skirt = Commands.argument("skirt", IntegerArgumentType.integer(1, EquipmentShape.MAX_SUPPORT))
                .executes(context -> placeColumn(context, EquipmentShape.Heading.NORTH));
        for (var front : EquipmentShape.Heading.values())
            skirt.then(Commands.literal(front.name().toLowerCase(Locale.ROOT)).executes(context -> placeColumn(context, front)));
        var column = Commands.literal("column").then(Commands.argument("position", BlockPosArgument.blockPos())
                .then(Commands.argument("diameter", DoubleArgumentType.doubleArg(EquipmentShape.MIN_DIAMETER, EquipmentShape.MAX_DIAMETER))
                        .then(Commands.argument("trays", IntegerArgumentType.integer(1, ColumnTrays.MAX_TRAYS)).then(skirt))));
        // A unit: its kind, size (shell, bundle, suction, discharge, bore or radiant section; an air cooler's bay width),
        // length (tubes, throws, radiant height or length; an air cooler's bay length, an ejector's and a pump's 1) and
        // support (shell bottom, bundle, discharge, foundation or floor height; a pump's 0); its channel, suction or process
        // nozzles face the front; an air cooler's bays in parallel and in series after the front. The shape checks each
        // kind's ranges.
        var at = Commands.argument("position", BlockPosArgument.blockPos());
        for (var kind : EquipmentShape.Kind.values()) {
            if (!kind.designed()) continue;
            if(kind==EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER){
                var size=Commands.argument("size",DoubleArgumentType.doubleArg(40,80)).executes(context->placeNaturalTower(context,EquipmentShape.Heading.NORTH));
                for(var front:EquipmentShape.Heading.values())size.then(Commands.literal(front.name().toLowerCase(Locale.ROOT)).executes(context->placeNaturalTower(context,front)));
                at.then(Commands.literal(kind.name().toLowerCase(Locale.ROOT)).then(size));
                continue;
            }
            var support = Commands.argument("support", IntegerArgumentType.integer(0, EquipmentShape.MAX_SUPPORT))
                    .executes(context -> placeUnit(context, kind, EquipmentShape.Heading.NORTH, false, 0));
            for (var front : EquipmentShape.Heading.values())
                support.then(Commands.literal(front.name().toLowerCase(Locale.ROOT)).executes(context -> placeUnit(context, kind, front, false, 0))
                        .then(Commands.argument("parallel", IntegerArgumentType.integer(1, ProcessUnit.Bank.MAX_PARALLEL))
                                .then(Commands.argument("series", IntegerArgumentType.integer(1, ProcessUnit.Bank.MAX_SERIES)).executes(context -> placeUnit(context, kind, front, true, 0))))
                        .then(Commands.literal("outlet").then(Commands.argument("outlet", IntegerArgumentType.integer(0, EquipmentShape.CONNECTION_SIDES - 1))
                                .executes(context -> placeUnit(context, kind, front, false, IntegerArgumentType.getInteger(context, "outlet"))))));
            at.then(Commands.literal(kind.name().toLowerCase(Locale.ROOT)).then(Commands.argument("size", DoubleArgumentType.doubleArg(ProcessUnit.SUCTION_STEP, UnitDesign.of(kind).maxSize()))
                    .then(Commands.argument("length", IntegerArgumentType.integer(1, (int) EquipmentShape.MAX_TANGENT_LENGTH)).then(support))));
        }
        var unit = Commands.literal("unit").then(at);
        event.getDispatcher().register(Commands.literal("icheme").requires(source -> source.hasPermission(2))
                .then(Commands.literal("equipment").then(place).then(column).then(unit))
                .then(com.wormzjl.icheme.runtime.fluid.HeldDevicesCommand.node()));
    }

    private static int place(CommandContext<CommandSourceStack> context, int rows, EquipmentShape.Orientation orientation, int support, EquipmentShape.Heading front, boolean boot,
                             int outlet) throws CommandSyntaxException {
        EquipmentShape shape;
        try {
            double diameter = DoubleArgumentType.getDouble(context, "diameter");
            int length = IntegerArgumentType.getInteger(context, "tangentLength");
            shape = EquipmentShape.drum(diameter, length, orientation, front, support, EquipmentShape.defaultConnections(diameter, length, orientation, support, boot, outlet), boot, outlet);
        } catch (IllegalArgumentException invalid) {
            context.getSource().sendFailure(Component.literal(invalid.getMessage()));
            return 0;
        }
        return place(context, shape, rows, shape.diameter() + " m x " + (int) shape.tangentLength() + " m drum");
    }

    private static int placeColumn(CommandContext<CommandSourceStack> context, EquipmentShape.Heading front) throws CommandSyntaxException {
        int trays = IntegerArgumentType.getInteger(context, "trays");
        EquipmentShape shape;
        try {
            shape = EquipmentShape.column(DoubleArgumentType.getDouble(context, "diameter"), ColumnTrays.simple(trays, (trays + 1) / 2), front,
                    IntegerArgumentType.getInteger(context, "skirt"));
        } catch (IllegalArgumentException invalid) {
            context.getSource().sendFailure(Component.literal(invalid.getMessage()));
            return 0;
        }
        return place(context, shape, Integer.MAX_VALUE, shape.diameter() + " m column of " + trays + " trays");
    }

    private static int placeUnit(CommandContext<CommandSourceStack> context, EquipmentShape.Kind kind, EquipmentShape.Heading front, boolean banked, int outlet)
            throws CommandSyntaxException {
        EquipmentShape shape;
        try {
            shape = EquipmentShape.designed(kind, DoubleArgumentType.getDouble(context, "size"), IntegerArgumentType.getInteger(context, "length"), front,
                    IntegerArgumentType.getInteger(context, "support"), banked&&!kind.coolingTower() ? new ProcessUnit.Bank(IntegerArgumentType.getInteger(context, "parallel"),
                            IntegerArgumentType.getInteger(context, "series")) : ProcessUnit.Bank.ONE, outlet);
            if(banked&&kind==EquipmentShape.Kind.INDUCED_DRAFT_COOLING_TOWER)
                shape=shape.withTowerBank(new com.wormzjl.icheme.science.equipment.TowerBank(IntegerArgumentType.getInteger(context,"parallel"),IntegerArgumentType.getInteger(context,"series")));
        } catch (IllegalArgumentException invalid) {
            context.getSource().sendFailure(Component.literal(invalid.getMessage()));
            return 0;
        }
        return place(context, shape, Integer.MAX_VALUE, kind.name().toLowerCase(Locale.ROOT) + " of " + shape.diameter() + " m");
    }

    private static int placeNaturalTower(CommandContext<CommandSourceStack> context,EquipmentShape.Heading front)throws CommandSyntaxException{
        double diameter=DoubleArgumentType.getDouble(context,"size");
        try{return place(context,EquipmentShape.unit(EquipmentShape.Kind.NATURAL_DRAFT_COOLING_TOWER,diameter,com.wormzjl.icheme.science.fluid.network.CoolingTower.naturalHeight(diameter),front,0),Integer.MAX_VALUE,"natural-draft tower");}
        catch(IllegalArgumentException invalid){context.getSource().sendFailure(Component.literal(invalid.getMessage()));return 0;}
    }

    private static int place(CommandContext<CommandSourceStack> context, EquipmentShape shape, int rows, String name) throws CommandSyntaxException {
        var source = context.getSource();
        var origin = BlockPosArgument.getLoadedBlockPos(context, "position").offset(-shape.sizeX() / 2, 0, -shape.sizeZ() / 2);
        try {
            int placed = place(source.getLevel(), origin, shape, IChemE.defaultPaint(), rows);
            source.sendSuccess(() -> Component.literal("Placed " + placed + " cells of a " + name + " at " + origin.toShortString()), true);
            return placed;
        } catch (IllegalStateException blocked) {
            source.sendFailure(Component.literal(blocked.getMessage()));
            return 0;
        }
    }
}
