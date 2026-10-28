package com.wormzjl.icheme.world.level.block;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.HeatUnit;
import com.wormzjl.icheme.runtime.fluid.WorldTopologyLedger;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.science.fluid.network.PassiveNetwork;
import com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec;
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
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
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
import java.util.TreeMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whole parametric vessels in the world: placement, removal of every cell when one goes (owner decision D5, batch
 * 2026-10-15-custom-equipment-rendering), lookup of the vessel a cell belongs to, and the collision shape of each cell
 * key. The equipment constructor places vessels; {@code /icheme equipment place} does too, for debugging. A vessel
 * whose cells an older layout of its shape placed is brought to the current layout after it loads ({@link #refreshLater},
 * {@link EquipmentRefresh}).
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
    private static final Map<ShapeRasterizer.CellKey, VoxelShape> SHAPES = new ConcurrentHashMap<>();
    /** Set while a vessel is being removed, so the cells it removes do not start removals of their own (server thread only). */
    private static boolean dismantling;
    /**
     * Vessels an older layout placed, by level and origin, each with the region its old and new cells span (server
     * thread only; weak, so a level that goes drops its own).
     */
    private static final Map<ServerLevel, Map<BlockPos, Stale>> STALE = new WeakHashMap<>();

    private record Stale(EquipmentShape shape, BlockPos low, BlockPos high) {
        Stale with(Stale other) {
            return new Stale(shape, min(low, other.low), max(high, other.high));
        }
    }

    /** Structures of a retired kind waiting for their removal, by level and origin, each with the region its cells span. */
    private static final Map<ServerLevel, Map<BlockPos, Retired>> RETIRED = new WeakHashMap<>();

    record Retired(String kind, BlockPos low, BlockPos high) {
        Retired with(Retired other) {
            return new Retired(kind, min(low, other.low), max(high, other.high));
        }
    }

    /** Loaded vessels whose interior cells are brought to the current state ({@link #restate}), by level and origin. */
    private static final Map<ServerLevel, Map<BlockPos, EquipmentShape>> RESTATE = new WeakHashMap<>();

    private EquipmentStructure() {
    }

    /** Collision and outline shape of a shell cell: its solid at 1/8 block. Both sides compute the same shape. */
    public static VoxelShape collision(ShapeRasterizer.CellKey key) {
        var shape = SHAPES.get(key);
        if (shape != null) return shape;
        if (SHAPES.size() >= SHAPE_CACHE) SHAPES.clear();
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
        var layout = ShapeRasterizer.layout(shape);
        for (var list : java.util.List.of(layout.shell(), layout.interior()))
            for (var cell : list) {
                var position = origin.offset(cell.x(), cell.y(), cell.z());
                if (level.isOutsideBuildHeight(position) || !level.isLoaded(position))
                    throw new IllegalStateException("Cell " + position.toShortString() + " is outside the loaded world");
                if (!level.getBlockState(position).canBeReplaced())
                    throw new IllegalStateException("Cell " + position.toShortString() + " is occupied by " + level.getBlockState(position).getBlock().getName().getString());
            }
        int placed = 0, flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        for (var cell : layout.shell()) {
            if (cell.y() >= rows) continue;
            var position = origin.offset(cell.x(), cell.y(), cell.z());
            level.setBlock(position, ModBlocks.EQUIPMENT_SHELL.get().defaultBlockState(), flags);
            if (level.getBlockEntity(position) instanceof EquipmentShellBlockEntity entity) entity.bind(shape, cell, paint);
            placed++;
        }
        for (var cell : layout.interior()) {
            if (cell.y() >= rows) continue;
            level.setBlock(origin.offset(cell.x(), cell.y(), cell.z()), interior(shape), flags);
            placed++;
        }
        var vessel = new Vessel(origin, shape);
        if (shape.fluidDevice() && rows >= shape.sizeY()) register(level, vessel);
        reconnect(level, vessel);
        refreshNozzles(level, vessel);
        com.wormzjl.icheme.runtime.assembly.AssemblyService.placed(level, vessel);
        return placed;
    }

    /**
     * A whole drum joins the fluid network as a reservoir (owner request 2026-10-16): its node at its lowest inside cell,
     * each nozzle a port of the class its position fixes (top: vapour; lowest side: liquid; other sides: bulk), its
     * design volume, and its liquid height for the static head {@code g·m·H/V}. It is placed empty, as a tank is.
     */
    private static void register(ServerLevel level, Vessel vessel) {
        if (HeatSides.unit(vessel.shape().kind()) != null) {
            registerUnit(level, vessel);
            return;
        }
        var node = node(vessel);
        if (vessel.shape().kind().mover()) {
            var kind = vessel.shape().kind() == EquipmentShape.Kind.PUMP ? TopologyCompiler.Kind.PUMP : TopologyCompiler.Kind.COMPRESSOR;
            FluidWorldAuthority.find(level.getServer()).ifPresent(world -> {
                var record = world.placeMover(position(level, node), kind, ports(level, vessel));
                if (level.getBlockEntity(node) instanceof EquipmentShellBlockEntity entity) entity.bindIdentity(record.device().id());
            });
            return;
        }
        if (vessel.shape().volume() > FluidDeviceSpec.MAX_VOLUME) {
            IChemE.LOGGER.warn("A {} of {} m³ is over the fluid model's {} m³: not registered", vessel.shape().kind().name().toLowerCase(Locale.ROOT),
                    Math.round(vessel.shape().volume()), Math.round(FluidDeviceSpec.MAX_VOLUME));
            return;
        }
        FluidWorldAuthority.find(level.getServer()).ifPresent(world -> {
            var record = world.placeVessel(position(level, node), ports(level, vessel), vessel.shape().volume(), vessel.shape().levelHeight(), open(vessel), vessel.shape().fluidGeometry());
            if (level.getBlockEntity(node) instanceof EquipmentShellBlockEntity entity) entity.bindIdentity(record.device().id());
        });
    }

    /**
     * Rebind a saved registration, or queue defaults for a complete placed structure with none. Returns false only
     * while chunks are missing, so a later chunk-load event retries it. Partial or stale layouts stay inactive;
     * normal construction or the existing layout refresh handles them. No blocks are changed and nothing solves.
     */
    public static boolean restoreFluidBinding(ServerLevel level,Vessel vessel) {
        if(!vessel.shape().fluidDevice())return true;
        var world=FluidWorldAuthority.find(level.getServer()).orElse(null);if(world==null)return false;
        boolean unit=HeatSides.unit(vessel.shape().kind())!=null;
        var heldIds=unit?heldAtNodes(world,level,vessel):List.<Long>of();
        if(unit&&unitRecords(world,heldIds).size()==registrations(vessel.shape())) {
            // A whole pair binds where its cells are loaded; none or half of a pair registers afresh below, once complete.
            boolean loaded=true;
            for(var at:nodes(vessel))loaded&=level.hasChunkAt(at)&&level.getBlockEntity(at) instanceof EquipmentShellBlockEntity shell&&ours(shell,vessel.origin(),vessel.shape());
            if(loaded)restateUnit(level,vessel,world,heldIds);
            return true;
        }
        var node=node(vessel);var held=position(level,node);
        var record=unit?null:world.at(held).orElse(null);
        if(record!=null) {
            if(level.hasChunkAt(node)&&record.device().position().equals(held)&&!record.device().ports().isEmpty()
                    &&level.getBlockEntity(node) instanceof EquipmentShellBlockEntity shell&&ours(shell,vessel.origin(),vessel.shape())) {
                if(vessel.shape().reservoir()&&!record.spec().geometry().equals(vessel.shape().fluidGeometry()))reregister(level,vessel,node,List.of());
                if(shell.fluidIdentity()!=record.device().id())shell.bindIdentity(record.device().id());else world.deviceLoaded(record.device().id());
            }
            return true;
        }
        var origin=vessel.origin();var shape=vessel.shape();
        for(int x=origin.getX()>>4;x<=(origin.getX()+shape.sizeX()-1)>>4;x++)
            for(int z=origin.getZ()>>4;z<=(origin.getZ()+shape.sizeZ()-1)>>4;z++)if(!level.hasChunk(x,z))return false;
        var layout=ShapeRasterizer.layout(shape);
        for(var cell:layout.shell()) {
            var at=origin.offset(cell.x(),cell.y(),cell.z());
            if(!(level.getBlockEntity(at) instanceof EquipmentShellBlockEntity shell)||!ours(shell,origin,shape)||shell.stale())return true;
        }
        for(var cell:layout.interior())if(!level.getBlockState(origin.offset(cell.x(),cell.y(),cell.z())).is(ModBlocks.EQUIPMENT_INTERIOR.get()))return true;
        if(unit)restateUnit(level,vessel,world,heldIds);else register(level,vessel);
        return true;
    }

    /**
     * A whole heat-exchanging unit joins the fluid network as one heat side per passage (batch
     * 2026-10-27-heat-exchanger-design, {@link HeatSides}): each at its first inlet's cell, its inlet and outlet nozzles
     * its ports, its passage and surface the datasheet's. Its sides are placed in one event, as partners.
     */
    private static void registerUnit(ServerLevel level, Vessel vessel) {
        var shape = vessel.shape();
        var unit = HeatSides.unit(shape.kind());
        var sides = HeatSides.of(shape);
        var nodes = nodes(vessel);
        var placing = new ArrayList<FluidWorldAuthority.UnitSide>();
        for (int i = 0; i < sides.size(); i++) {
            var side = sides.get(i);
            placing.add(new FluidWorldAuthority.UnitSide(position(level, nodes.get(i)), sidePorts(level, vessel, side), side.passage(), new HeatUnit(unit, side.side(), side.area(), 0)));
        }
        // A kettle's shell (W7): a closed vessel at its pool's node, its phase ports the drum's, its surface its tubes'.
        var pool = HeatSides.pool(shape.kind()) ? new FluidWorldAuthority.UnitVessel(position(level, nodes.get(sides.size())), ports(level, vessel), shape.volume(), shape.levelHeight(),
                shape.fluidGeometry(), new HeatUnit(unit, HeatSides.Side.SHELL, sides.getFirst().area(), 0)) : null;
        FluidWorldAuthority.find(level.getServer()).ifPresent(world -> {
            var records = world.placeUnit(placing, pool);
            for (int i = 0; i < records.size(); i++)
                if (level.getBlockEntity(nodes.get(i)) instanceof EquipmentShellBlockEntity entity) entity.bindIdentity(records.get(i).device().id());
        });
    }

    /** A heat side's nozzles: its inlets and outlets in nozzle order, so its first inlet is its node ({@link ShapeRasterizer#fluidCells}). */
    private static List<PhysicalFluidTopology.Port> sidePorts(Level level, Vessel vessel, HeatSides.Definition side) {
        var ports = new ArrayList<PhysicalFluidTopology.Port>();
        var nozzles = vessel.shape().nozzles();
        for (var port : ShapeRasterizer.ports(vessel.shape())) {
            var role = nozzles.get(port.nozzle()).role();
            var connection = role == side.inlet() ? PhysicalFluidTopology.ConnectionRole.INLET : role == side.outlet() ? PhysicalFluidTopology.ConnectionRole.OUTLET : null;
            if (connection == null) continue;
            var cell = vessel.origin().offset(port.cell().x(), port.cell().y(), port.cell().z());
            var face = PhysicalFluidTopology.Direction.valueOf(Direction.fromDelta(port.dx(), port.dy(), port.dz()).name());
            ports.add(new PhysicalFluidTopology.Port(position(level, cell), face, PassiveNetwork.PhasePort.BULK, connection));
        }
        return ports;
    }

    /** A heat unit's registrations: one per side, and a kettle's shell (W7). */
    private static int registrations(EquipmentShape shape) {
        return HeatSides.of(shape).size() + (HeatSides.pool(shape.kind()) ? 1 : 0);
    }

    /** The registrations of a unit's sides, by side: those held at {@code ids} and their partners. */
    private static Map<HeatSides.Side, WorldTopologyLedger.Registration> unitRecords(FluidWorldAuthority world, java.util.Collection<Long> ids) {
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

    /** The identities registered at {@code vessel}'s nodes. */
    private static List<Long> heldAtNodes(FluidWorldAuthority world, ServerLevel level, Vessel vessel) {
        var ids = new ArrayList<Long>();
        for (var node : nodes(vessel)) {
            var held = position(level, node);
            world.at(held).filter(r -> r.device().position().equals(held) && !r.device().ports().isEmpty()).ifPresent(r -> ids.add(r.device().id()));
        }
        return ids;
    }

    /**
     * Moves or restates a unit's sides onto {@code vessel}'s layout (decision D7: each keeps its identity, partner, control
     * and heat in transit), or registers the unit afresh when its sides are not all there. {@code held} are the identities
     * its cells held. A side's passage and surface follow the datasheet.
     */
    private static void restateUnit(ServerLevel level, Vessel vessel, FluidWorldAuthority world, java.util.Collection<Long> held) {
        var sides = HeatSides.of(vessel.shape());
        var records = unitRecords(world, held);
        if (records.size() != registrations(vessel.shape())) {
            if (!records.isEmpty()) world.removeUnit(records.values().stream().map(r -> r.device().id()).toList());
            registerUnit(level, vessel);
            return;
        }
        var nodes = nodes(vessel);
        for (int i = 0; i < sides.size(); i++) {
            var side = sides.get(i);
            var record = records.get(side.side());
            var node = nodes.get(i);
            var device = record.device();
            var moved = device.withPlacement(position(level, node), sidePorts(level, vessel, side)).withGeometry(side.passage()).withHeat(device.heat().withArea(side.area()));
            if (!moved.equals(device)) world.edit(device.id(), record.revision(), moved, record.spec());
            for (var other : nodes)
                if (!other.equals(node) && level.getBlockEntity(other) instanceof EquipmentShellBlockEntity stale && stale.fluidIdentity() == device.id()) stale.releaseIdentity();
            if (level.getBlockEntity(node) instanceof EquipmentShellBlockEntity entity) {
                if (entity.fluidIdentity() != device.id()) entity.bindIdentity(device.id());
                else world.deviceLoaded(device.id());
            }
        }
        if (!HeatSides.pool(vessel.shape().kind())) return;
        // A kettle's shell (W7) moves to its pool's node with its ports, its vessel follows the datasheet, it keeps what it holds.
        var record = records.get(HeatSides.Side.SHELL);
        var node = nodes.get(sides.size());
        var device = record.device();
        var shape = vessel.shape();
        var moved = device.withPlacement(position(level, node), ports(level, vessel)).withHeat(device.heat().withArea(sides.getFirst().area()));
        var spec = record.spec();
        var respec = new FluidDeviceSpec(shape.volume(), spec.temperature(), spec.pressure(), spec.composition(), spec.solids(), shape.levelHeight(), false, shape.fluidGeometry());
        if (!moved.equals(device) || !respec.equals(spec)) world.edit(device.id(), record.revision(), moved, respec);
        for (var other : nodes)
            if (!other.equals(node) && level.getBlockEntity(other) instanceof EquipmentShellBlockEntity stale && stale.fluidIdentity() == device.id()) stale.releaseIdentity();
        if (level.getBlockEntity(node) instanceof EquipmentShellBlockEntity entity) {
            if (entity.fluidIdentity() != device.id()) entity.bindIdentity(device.id());
            else world.deviceLoaded(device.id());
        }
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
    private static List<BlockPos> nodes(Vessel vessel) {
        var nodes = new ArrayList<BlockPos>();
        for (var cell : ShapeRasterizer.fluidCells(vessel.shape())) nodes.add(vessel.origin().offset(cell.x(), cell.y(), cell.z()));
        return nodes;
    }

    /** A floating-roof tank registers open to the atmosphere (batch 2026-10-21-floating-roof-tanks); a drum and a sphere closed. */
    private static boolean open(Vessel vessel) {
        return vessel.shape().kind() == EquipmentShape.Kind.TANK;
    }

    /** A reservoir's lowest inside cell, or a mover's suction nozzle cell ({@link ShapeRasterizer#fluidCell}). */
    private static BlockPos node(Vessel vessel) {
        var cell = ShapeRasterizer.fluidCell(vessel.shape());
        return vessel.origin().offset(cell.x(), cell.y(), cell.z());
    }

    private static PhysicalFluidTopology.Position position(Level level, BlockPos at) {
        return new PhysicalFluidTopology.Position(level.dimension().location().toString(), at.getX(), at.getY(), at.getZ());
    }

    /** Reservoir nozzles keep their phase classes; movers register only their explicitly directed process pair; a kettle's
     * shell (W7) its own nozzles, its tubes' left to their side. */
    private static List<PhysicalFluidTopology.Port> ports(Level level, Vessel vessel) {
        var ports = new ArrayList<PhysicalFluidTopology.Port>();
        var nozzles = vessel.shape().nozzles();
        var passages = new java.util.HashSet<EquipmentShape.Role>();
        for (var side : HeatSides.of(vessel.shape())) { passages.add(side.inlet()); passages.add(side.outlet()); }
        for (var port : ShapeRasterizer.ports(vessel.shape())) {
            if (passages.contains(nozzles.get(port.nozzle()).role())) continue;
            var role = PhysicalFluidTopology.ConnectionRole.VESSEL;
            if (vessel.shape().kind().mover()) {
                role = switch (nozzles.get(port.nozzle()).role()) {
                    case SUCTION -> PhysicalFluidTopology.ConnectionRole.SUCTION;
                    case DISCHARGE -> PhysicalFluidTopology.ConnectionRole.DISCHARGE;
                    default -> null;
                };
                if (role == null) continue; // Oil-cooler and other auxiliary nozzles remain decorative.
            }
            var cell = vessel.origin().offset(port.cell().x(), port.cell().y(), port.cell().z());
            var face = PhysicalFluidTopology.Direction.valueOf(Direction.fromDelta(port.dx(), port.dy(), port.dz()).name());
            var phase = switch (port.port()) {
                case VAPOUR -> PassiveNetwork.PhasePort.VAPOR;
                case LIQUID -> PassiveNetwork.PhasePort.LIQUID;
                case BULK -> PassiveNetwork.PhasePort.BULK;
                case OVERFLOW -> PassiveNetwork.PhasePort.OVERFLOW;
            };
            if(vessel.shape().boot())phase=switch(nozzles.get(port.nozzle()).role()){case BOOT_DRAIN->PassiveNetwork.PhasePort.BOOT_WATER;case LIQUID_OUTLET->PassiveNetwork.PhasePort.BOOT_HC;default->phase;};
            ports.add(new PhysicalFluidTopology.Port(position(level, cell), face, phase, role));
        }
        return ports;
    }

    /** The equipment's registration leaves with its structure (a reservoir's contents go with it). */
    private static void unregister(ServerLevel level, Vessel vessel) {
        if (HeatSides.unit(vessel.shape().kind()) != null) {
            // A unit's sides leave together: no event leaves half a pair.
            FluidWorldAuthority.find(level.getServer()).ifPresent(world -> world.removeUnit(
                    unitRecords(world, heldAtNodes(world, level, vessel)).values().stream().map(r -> r.device().id()).toList()));
            return;
        }
        var position = position(level, node(vessel));
        FluidWorldAuthority.find(level.getServer()).ifPresent(world -> world.at(position)
                .filter(record -> record.device().position().equals(position) && !record.device().ports().isEmpty())
                .ifPresent(record -> world.remove(record.device().id())));
    }

    /** The pipes in front of the vessel's nozzles take up (or drop) their connection: the cells were set without shape updates. */
    private static void reconnect(Level level, Vessel vessel) {
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

    /** The longest walk over interior cells: across the widest body, a column's or a floating-roof tank's. */
    private static final int FIND_STEPS = (int) Math.ceil(Math.max(EquipmentShape.MAX_COLUMN_DIAMETER, UnitDesign.of(EquipmentShape.Kind.TANK).maxSize())) + 2;

    /**
     * The interior cell of {@code shape}: a floating-roof tank's is open (its roof shows from above); a vessel's whose
     * skirt shows its inside lets skylight through ({@link ShapeRasterizer#skylight}).
     */
    private static net.minecraft.world.level.block.state.BlockState interior(EquipmentShape shape) {
        return ModBlocks.EQUIPMENT_INTERIOR.get().defaultBlockState().setValue(EquipmentInteriorBlock.OPEN, shape.kind() == EquipmentShape.Kind.TANK)
                .setValue(EquipmentInteriorBlock.SKYLIGHT, ShapeRasterizer.skylight(shape));
    }

    /**
     * Server, from the load of a vessel's controller cell when its layout is current: its interior cells are brought to
     * the state the code gives them ({@link #restate}) on a later tick, once its chunks are loaded and block-ticking. An
     * interior cell's state follows the code as a shell cell's drawing does; no cell is added or removed.
     */
    public static void restateLater(ServerLevel level, BlockPos origin, EquipmentShape shape) {
        RESTATE.computeIfAbsent(level, k -> new LinkedHashMap<>()).putIfAbsent(origin.immutable(), shape);
    }

    /**
     * Sets every interior cell of the vessel at {@code origin} that holds another state than {@link #interior} gives
     * (a save from before {@link EquipmentInteriorBlock#SKYLIGHT}) to it, and lets its chunks wait for their light. Only a
     * vessel whose controller cell is its own and current: a stale one is refreshed instead, which restates it too.
     */
    static void restate(ServerLevel level, BlockPos origin, EquipmentShape shape) {
        var controller = ShapeRasterizer.layout(shape).controller();
        if (!(level.getBlockEntity(origin.offset(controller.x(), controller.y(), controller.z())) instanceof EquipmentShellBlockEntity shell)
                || !ours(shell, origin, shape) || shell.stale()) return;
        int changed = restateCells(level, origin, shape, Set.of(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        if (changed == 0) return;
        var high = origin.offset(shape.sizeX() - 1, shape.sizeY() - 1, shape.sizeZ() - 1);
        for (int x = origin.getX() >> 4; x <= high.getX() >> 4; x++)
            for (int z = origin.getZ() >> 4; z <= high.getZ() >> 4; z++) level.getChunkSource().chunkMap.waitForLightBeforeSending(new ChunkPos(x, z), 0);
        IChemE.LOGGER.info("equipment_restate kind={} origin={} interior={}", shape.kind().name().toLowerCase(Locale.ROOT), origin.toShortString(), changed);
    }

    /**
     * Sets each of the layout's interior cells at {@code origin}, but those in {@code skip}, that holds an interior block of
     * another state to {@link #interior}; how many.
     */
    private static int restateCells(ServerLevel level, BlockPos origin, EquipmentShape shape, Set<ShapeRasterizer.Cell> skip, int flags) {
        var state = interior(shape);
        int changed = 0;
        for (var cell : ShapeRasterizer.layout(shape).interior()) {
            if (skip.contains(cell)) continue;
            var at = origin.offset(cell.x(), cell.y(), cell.z());
            var held = level.getBlockState(at);
            if (held.is(ModBlocks.EQUIPMENT_INTERIOR.get()) && held != state) {
                level.setBlock(at, state, flags);
                changed++;
            }
        }
        return changed;
    }

    /** The first bound shell cell a walk east, or else south, over interior cells reaches from {@code position}, or null. */
    private static @Nullable EquipmentShellBlockEntity shellReached(BlockGetter level, BlockPos position) {
        for (var direction : new Direction[]{Direction.EAST, Direction.SOUTH})
            for (int step = 0; step <= FIND_STEPS; step++) {
                var at = position.relative(direction, step);
                if (level.getBlockEntity(at) instanceof EquipmentShellBlockEntity shell && shell.shape() != null) return shell;
                if (step > 0 && !level.getBlockState(at).is(ModBlocks.EQUIPMENT_INTERIOR.get())) break;
            }
        return null;
    }

    static void breaking(Level level,BlockPos position,net.minecraft.world.entity.player.Player player){var vessel=find(level,position);if(vessel==null||!vessel.shape().fluidDevice())return;var cell=ShapeRasterizer.fluidCell(vessel.shape());if(cell!=null&&level.getBlockEntity(vessel.origin().offset(cell.x(),cell.y(),cell.z())) instanceof EquipmentShellBlockEntity shell)shell.instruments().recoverOnBreak(!player.isCreative());}

    /** Removes every remaining cell of the vessel; nothing drops. */
    static void dismantle(Level level, Vessel vessel) {
        if (dismantling || level.isClientSide()) return;
        dismantling = true;
        try {
            if (level instanceof ServerLevel server) com.wormzjl.icheme.runtime.assembly.AssemblyService.removed(server, vessel);
            if (level instanceof ServerLevel server && vessel.shape().fluidDevice()) unregister(server, vessel);
            var layout = ShapeRasterizer.layout(vessel.shape());
            for (var list : java.util.List.of(layout.shell(), layout.interior()))
                for (var cell : list) {
                    var position = vessel.origin().offset(cell.x(), cell.y(), cell.z());
                    var state = level.getBlockState(position);
                    boolean ours = state.is(ModBlocks.EQUIPMENT_INTERIOR.get())
                            || level.getBlockEntity(position) instanceof EquipmentShellBlockEntity shell && vessel.origin().equals(shell.origin());
                    if (ours) level.setBlock(position, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            reconnect(level, vessel);
        } finally {
            dismantling = false;
        }
    }

    /**
     * Server, from a shell cell's load: an older layout of its shape placed it (owner request 2026-10-18). Its vessel is
     * refreshed on a later tick, once every chunk its old and new cells span is loaded and block-ticking; blocks are not
     * changed while a chunk loads. {@code box} is the box the cell was placed by (zeros when saved before stamps),
     * {@code cell} its position, so a cell an old, larger box left outside the new one widens the region; the new box may
     * stand {@link EquipmentRefresh#REACH} from the old one along x and z, and its nozzles' pipe blocks one cell beyond it.
     */
    public static void refreshLater(ServerLevel level, BlockPos origin, EquipmentShape shape, int[] box, BlockPos cell) {
        int reach = EquipmentRefresh.REACH + 1;
        var low = origin.offset(-reach, -1, -reach);
        var high = origin.offset(Math.max(shape.sizeX(), box[0]) - 1 + reach, Math.max(shape.sizeY(), box[1]), Math.max(shape.sizeZ(), box[2]) - 1 + reach);
        var stale = new Stale(shape, min(low, cell), max(high, cell));
        STALE.computeIfAbsent(level, k -> new LinkedHashMap<>()).merge(origin.immutable(), stale, Stale::with);
    }

    /**
     * Server, from the load of a shell cell whose kind this build no longer has (owner decision D6, batch
     * 2026-10-26-backward-compat: "simply remove it, and don't leave ghost blocks behind"): its structure is removed whole on
     * a later tick, once every chunk of the box it was placed by is loaded ({@link #removeRetired}). {@code box} is that
     * box (zeros when saved before stamps), {@code cell} the cell's position, which widens the region.
     */
    public static void removeRetiredLater(ServerLevel level, BlockPos origin, String kind, int[] box, BlockPos cell) {
        var high = origin.offset(Math.max(1, box[0]) - 1, Math.max(1, box[1]) - 1, Math.max(1, box[2]) - 1);
        var retired = new Retired(kind, min(origin, cell), max(high, cell));
        RETIRED.computeIfAbsent(level, k -> new LinkedHashMap<>()).merge(origin.immutable(), retired, Retired::with);
    }

    /**
     * Removes a structure of a retired kind as a dismantle removes one: its fluid registration leaves (a reservoir's
     * contents with it, its gauges refunded, as a broken vessel's), then every cell it placed is cleared, its interior
     * cells (found from a shell cell of the same origin and saved kind, its shape being unreadable) and its shell cells.
     * No other block is touched.
     */
    static void removeRetired(ServerLevel level, BlockPos origin, Retired retired) {
        var shells = new ArrayList<BlockPos>();var interior = new ArrayList<BlockPos>();
        for (var at : BlockPos.betweenClosed(retired.low(), retired.high()))
            if (retiredShell(level, at, origin, retired.kind())) shells.add(at.immutable());
            else if (level.getBlockState(at).is(ModBlocks.EQUIPMENT_INTERIOR.get()) && retiredReached(level, at, origin, retired.kind())) interior.add(at.immutable());
        if (shells.isEmpty()) return;
        var world = FluidWorldAuthority.find(level.getServer()).orElse(null);
        for (var at : shells)
            if (world != null && level.getBlockEntity(at) instanceof EquipmentShellBlockEntity shell && shell.fluidIdentity() != 0) world.remove(shell.fluidIdentity());
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        dismantling = true;
        try {
            for (var at : interior) level.setBlock(at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), flags);
            for (var at : shells) level.setBlock(at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), flags);
        } finally {
            dismantling = false;
        }
        for (int x = retired.low().getX() >> 4; x <= retired.high().getX() >> 4; x++)
            for (int z = retired.low().getZ() >> 4; z <= retired.high().getZ() >> 4; z++) level.getChunkSource().chunkMap.waitForLightBeforeSending(new ChunkPos(x, z), 0);
        // Pipes that ran into the removed nozzles drop their connection.
        for (var list : List.of(shells, interior))
            for (var at : list)
                for (var direction : Direction.values()) {
                    var next = at.relative(direction);
                    var state = level.getBlockState(next);
                    if (state.getBlock() instanceof FluidDeviceBlock) Block.updateOrDestroy(state, Block.updateFromNeighbourShapes(state, level, next), level, next, Block.UPDATE_ALL);
                }
        IChemE.LOGGER.warn("equipment_compat status=REMOVED kind={} origin={} shell={} interior={} detail=this build no longer has the kind (owner decision D6)",
                retired.kind(), origin.toShortString(), shells.size(), interior.size());
    }

    private static boolean retiredShell(BlockGetter level, BlockPos at, BlockPos origin, String kind) {
        return level.getBlockEntity(at) instanceof EquipmentShellBlockEntity shell && origin.equals(shell.retiredOrigin()) && kind.equals(shell.retired());
    }

    /** Whether a walk east, or else south, over interior cells from {@code position} reaches a retired shell cell of {@code origin}. */
    private static boolean retiredReached(BlockGetter level, BlockPos position, BlockPos origin, String kind) {
        for (var direction : new Direction[]{Direction.EAST, Direction.SOUTH})
            for (int step = 1; step <= FIND_STEPS; step++) {
                var at = position.relative(direction, step);
                if (retiredShell(level, at, origin, kind)) return true;
                if (!level.getBlockState(at).is(ModBlocks.EQUIPMENT_INTERIOR.get())) break;
            }
        return false;
    }

    /** At most one retired, stale or restated vessel a tick, the first whose region is ready; on most ticks there is none. */
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        var retired = RETIRED.get(level);
        if (retired != null)
            for (var it = retired.entrySet().iterator(); it.hasNext(); ) {
                var entry = it.next();
                if (!ready(level, entry.getValue().low(), entry.getValue().high())) continue;
                it.remove();
                try {
                    removeRetired(level, entry.getKey(), entry.getValue());
                } catch (RuntimeException failed) {
                    IChemE.LOGGER.error("equipment_compat removal failed at {}", entry.getKey().toShortString(), failed);
                }
                return;
            }
        var pending = STALE.get(level);
        if (pending != null)
            for (var it = pending.entrySet().iterator(); it.hasNext(); ) {
                var entry = it.next();
                var stale = entry.getValue();
                if (!ready(level, stale.low(), stale.high())) continue;
                it.remove();
                try {
                    refresh(level, entry.getKey(), stale.shape(), stale.low(), stale.high());
                } catch (RuntimeException failed) {
                    // A vessel that cannot be refreshed keeps its cells as they stand; the world goes on.
                    IChemE.LOGGER.error("equipment_refresh failed at {}", entry.getKey().toShortString(), failed);
                }
                return;
            }
        var restating = RESTATE.get(level);
        if (restating != null)
            for (var it = restating.entrySet().iterator(); it.hasNext(); ) {
                var entry = it.next();
                var shape = entry.getValue();
                if (!ready(level, entry.getKey(), entry.getKey().offset(shape.sizeX() - 1, shape.sizeY() - 1, shape.sizeZ() - 1))) continue;
                it.remove();
                try {
                    restate(level, entry.getKey(), shape);
                } catch (RuntimeException failed) {
                    IChemE.LOGGER.error("equipment_restate failed at {}", entry.getKey().toShortString(), failed);
                }
                return;
            }
    }

    /**
     * Every chunk of the region is loaded and block-ticking, where a block change reaches the players watching it as any
     * other does (outside that range the server sends a player no block changes).
     */
    private static boolean ready(ServerLevel level, BlockPos low, BlockPos high) {
        for (int x = low.getX() >> 4; x <= high.getX() >> 4; x++)
            for (int z = low.getZ() >> 4; z <= high.getZ() >> 4; z++)
                if (!level.hasChunk(x, z) || !level.shouldTickBlocksAt(ChunkPos.asLong(x, z))) return false;
        return true;
    }

    /**
     * Brings the vessel at {@code origin} to its shape's current layout ({@link EquipmentRefresh}). What of it stands
     * between {@code low} and {@code high} is read first: a shell cell is its own by origin and shape, an interior cell
     * where a walk over interior cells reaches one of its shell cells. The new layout stands where it covers the most of
     * that (its body stays on its foundation); the cells it dropped are cleared, those it added are placed where the world
     * is free, and every shell cell is rebound to it with its paint; the nozzles redraw at the sizes of their pipes, and a
     * drum keeps its registration ({@link #reregister}). Nothing else changes. A chunk the change reaches before it is
     * sent to a player waits for its light ({@link ChunkMap#waitForLightBeforeSending}): the server sends a player light
     * changes only on the edge of its view, a player relighting its own block changes, so a chunk sent with the light
     * of before would keep it.
     */
    static void refresh(ServerLevel level, BlockPos origin, EquipmentShape shape, BlockPos low, BlockPos high) {
        var world = new HashMap<ShapeRasterizer.Cell, EquipmentRefresh.Holding>();
        Paint paint = null;
        BlockPos oldNode = null;
        var held = new ArrayList<Long>();
        for (var at : BlockPos.betweenClosed(low, high)) {
            var cell = new ShapeRasterizer.Cell(at.getX() - origin.getX(), at.getY() - origin.getY(), at.getZ() - origin.getZ());
            var state = level.getBlockState(at);
            if (level.getBlockEntity(at) instanceof EquipmentShellBlockEntity shell && ours(shell, origin, shape)) {
                world.put(cell, EquipmentRefresh.Holding.SHELL);
                if (paint == null) paint = shell.paint();
                if (shell.fluidIdentity() != 0) {
                    oldNode = at.immutable();
                    held.add(shell.fluidIdentity());
                }
            } else if (state.is(ModBlocks.EQUIPMENT_INTERIOR.get())) {
                var shell = shellReached(level, at);
                world.put(cell, shell != null && ours(shell, origin, shape) ? EquipmentRefresh.Holding.INTERIOR : EquipmentRefresh.Holding.OTHER);
            } else if (state.getBlock() instanceof FluidDeviceBlock) world.put(cell, EquipmentRefresh.Holding.PIPE);
            else if (!state.canBeReplaced()) world.put(cell, EquipmentRefresh.Holding.OTHER);
        }
        if (paint == null) return;
        // A rebuilt structure is a structural change to every assembly report that relied on it.
        com.wormzjl.icheme.runtime.assembly.AssemblyService.removed(level, new Vessel(origin, shape));
        var layout = ShapeRasterizer.layout(shape);
        var plan = EquipmentRefresh.plan(layout, world);
        var placed = origin.offset(plan.shift().x(), 0, plan.shift().z());
        var placeShell = new HashSet<>(plan.shell());
        var blocked = new HashSet<>(plan.blocked());
        var touched = new ArrayList<BlockPos>();
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        dismantling = true;
        try {
            for (var cell : plan.clear()) {
                var at = placed.offset(cell.x(), cell.y(), cell.z());
                level.setBlock(at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), flags);
                touched.add(at);
            }
            for (var cell : layout.shell()) {
                if (blocked.contains(cell)) continue;
                var at = placed.offset(cell.x(), cell.y(), cell.z());
                var cellPaint = paint;
                if (placeShell.contains(cell)) {
                    level.setBlock(at, ModBlocks.EQUIPMENT_SHELL.get().defaultBlockState(), flags);
                    touched.add(at);
                }
                if (level.getBlockEntity(at) instanceof EquipmentShellBlockEntity entity) {
                    var before = entity.key();
                    if (!placeShell.contains(cell)) cellPaint = entity.paint();
                    entity.bind(shape, cell, cellPaint);
                    if (!entity.key().equals(before)) {
                        level.sendBlockUpdated(at, entity.getBlockState(), entity.getBlockState(), Block.UPDATE_CLIENTS);
                        touched.add(at);
                    }
                }
            }
            for (var cell : plan.interior()) level.setBlock(placed.offset(cell.x(), cell.y(), cell.z()), interior(shape), flags);
            // The interior cells it kept take the current state too (restate); a blocked cell is another owner's.
            restateCells(level, placed, shape, blocked, flags);
            // Cells left out where other blocks stand: the cells written keep no stamp, so the next load tries again.
            if (!blocked.isEmpty())
                for (var cell : layout.shell())
                    if (!blocked.contains(cell) && level.getBlockEntity(placed.offset(cell.x(), cell.y(), cell.z())) instanceof EquipmentShellBlockEntity entity) entity.layoutIncomplete();
        } finally {
            dismantling = false;
        }
        for (int x = low.getX() >> 4; x <= high.getX() >> 4; x++)
            for (int z = low.getZ() >> 4; z <= high.getZ() >> 4; z++) level.getChunkSource().chunkMap.waitForLightBeforeSending(new ChunkPos(x, z), 0);
        var vessel = new Vessel(placed, shape);
        if (shape.fluidDevice()) reregister(level, vessel, oldNode, held);
        // Pipes beside a dropped, added or redrawn cell take up or drop their connection (old nozzles included).
        for (var at : touched)
            for (var direction : Direction.values()) {
                var next = at.relative(direction);
                var state = level.getBlockState(next);
                if (state.getBlock() instanceof FluidDeviceBlock) Block.updateOrDestroy(state, Block.updateFromNeighbourShapes(state, level, next), level, next, Block.UPDATE_ALL);
            }
        reconnect(level, vessel);
        refreshNozzles(level, vessel);
        com.wormzjl.icheme.runtime.assembly.AssemblyService.placed(level, vessel);
        IChemE.LOGGER.info("equipment_refresh kind={} origin={} shift={},{} cleared={} placed={} blocked={}", shape.kind().name().toLowerCase(Locale.ROOT),
                origin.toShortString(), plan.shift().x(), plan.shift().z(), plan.clear().size(), plan.shell().size() + plan.interior().size(), plan.blocked().size());
        if (!plan.blocked().isEmpty()) {
            var first = plan.blocked().getFirst();
            IChemE.LOGGER.warn("equipment_refresh left {} cells out: other blocks stand there, first at {}", plan.blocked().size(),
                    placed.offset(first.x(), first.y(), first.z()).toShortString());
        }
    }

    private static boolean ours(EquipmentShellBlockEntity shell, BlockPos origin, EquipmentShape shape) {
        return origin.equals(shell.origin()) && shape.equals(shell.shape());
    }

    /**
     * A refreshed vessel or machine keeps its registration (decision D7): one queued edit moves it to its new node with
     * its new ports, and a vessel takes its new volume, level height and opening. It keeps its identity, its controls and
     * what it holds ({@code PhysicalRegistry}: a vessel's moles, energy and solids in its new geometry), so nothing the
     * network or a gauge knows of it changes but where it stands. {@code oldNode} held the identity.
     */
    private static void reregister(ServerLevel level, Vessel vessel, @Nullable BlockPos oldNode, List<Long> heldIds) {
        if (HeatSides.unit(vessel.shape().kind()) != null) {
            FluidWorldAuthority.find(level.getServer()).ifPresent(world -> restateUnit(level, vessel, world, heldIds));
            return;
        }
        var node = node(vessel);
        var world = FluidWorldAuthority.find(level.getServer()).orElse(null);
        if (world == null) return;
        var held = position(level, oldNode == null ? node : oldNode);
        var record = world.at(held).filter(r -> r.device().position().equals(held) && !r.device().ports().isEmpty()).orElse(null);
        if (record == null) {
            register(level, vessel);
            return;
        }
        var device = record.device();
        var spec = record.spec();
        world.edit(device.id(), record.revision(), device.withPlacement(position(level, node), ports(level, vessel)),
                vessel.shape().kind().mover() ? spec : new FluidDeviceSpec(vessel.shape().volume(), spec.temperature(), spec.pressure(), spec.composition(), spec.solids(), vessel.shape().levelHeight(), open(vessel), vessel.shape().fluidGeometry()));
        if (oldNode != null && !oldNode.equals(node) && level.getBlockEntity(oldNode) instanceof EquipmentShellBlockEntity old && old.fluidIdentity() == device.id()) old.releaseIdentity();
        if (level.getBlockEntity(node) instanceof EquipmentShellBlockEntity entity && entity.fluidIdentity() != device.id()) entity.bindIdentity(device.id());
    }

    private static BlockPos min(BlockPos a, BlockPos b) {
        return new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
    }

    private static BlockPos max(BlockPos a, BlockPos b) {
        return new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
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
            var support = Commands.argument("support", IntegerArgumentType.integer(0, EquipmentShape.MAX_SUPPORT))
                    .executes(context -> placeUnit(context, kind, EquipmentShape.Heading.NORTH, false, 0));
            for (var front : EquipmentShape.Heading.values())
                support.then(Commands.literal(front.name().toLowerCase(Locale.ROOT)).executes(context -> placeUnit(context, kind, front, false, 0))
                        .then(Commands.argument("parallel", IntegerArgumentType.integer(1, ProcessUnit.Bank.MAX_PARALLEL))
                                .then(Commands.argument("series", IntegerArgumentType.integer(1, ProcessUnit.Bank.MAX_SERIES)).executes(context -> placeUnit(context, kind, front, true, 0))))
                        .then(Commands.literal("outlet").then(Commands.argument("outlet", IntegerArgumentType.integer(0, EquipmentShape.CONNECTION_SIDES - 1))
                                .executes(context -> placeUnit(context, kind, front, false, IntegerArgumentType.getInteger(context, "outlet"))))));
            at.then(Commands.literal(kind.name().toLowerCase(Locale.ROOT)).then(Commands.argument("size", DoubleArgumentType.doubleArg(ProcessUnit.SUCTION_STEP, ProcessUnit.maxDiameter(kind)))
                    .then(Commands.argument("length", IntegerArgumentType.integer(1, (int) EquipmentShape.MAX_TANGENT_LENGTH)).then(support))));
        }
        var unit = Commands.literal("unit").then(at);
        event.getDispatcher().register(Commands.literal("icheme").requires(source -> source.hasPermission(2))
                .then(Commands.literal("equipment").then(place).then(column).then(unit)));
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
                    IntegerArgumentType.getInteger(context, "support"), banked ? new ProcessUnit.Bank(IntegerArgumentType.getInteger(context, "parallel"),
                            IntegerArgumentType.getInteger(context, "series")) : ProcessUnit.Bank.ONE, outlet);
        } catch (IllegalArgumentException invalid) {
            context.getSource().sendFailure(Component.literal(invalid.getMessage()));
            return 0;
        }
        return place(context, shape, Integer.MAX_VALUE, kind.name().toLowerCase(Locale.ROOT) + " of " + shape.diameter() + " m");
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
