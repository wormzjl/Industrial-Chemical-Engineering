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
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Whole parametric vessels in the world: placement, removal of every cell when one goes (owner decision D5, batch
 * 2026-10-15-custom-equipment-rendering), lookup of the vessel a cell belongs to, and the collision shape of each cell
 * key. The equipment constructor places vessels; {@code /icheme equipment place} does too, for debugging. A vessel
 * whose cells an older layout of its shape placed is brought to the current layout after it loads ({@link #refreshLater},
 * {@link EquipmentRefresh}); one whose cells differ from the registry's record of it is rebuilt to the record
 * ({@link #serveRealignment}).
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

    /**
     * Structures the registry records whose chunks loaded, waiting for their re-alignment (registry first, batch
     * 2026-11-03-save-corruption), by level and origin, each with the far corner of the region it reads beyond its box.
     */
    private static final Map<ServerLevel, Map<BlockPos, BlockPos>> REALIGN = new WeakHashMap<>();

    private EquipmentStructure() {
    }

    /** All verified layout/removal writes share the same narrowly scoped authority. */
    private static boolean setCell(Level level, BlockPos position, BlockState state, int flags) {
        return EquipmentProtection.edit(level, position, () -> level.setBlock(position, state, flags));
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
        var at = position(level, vessel.origin());
        var held = world == null ? null : world.structure(at);
        if (held != null) world.structure(at, held.withName(com.wormzjl.icheme.science.equipment.DeviceName.clean(name)));
        return true;
    }

    /** {@link #place(ServerLevel, BlockPos, EquipmentShape, Paint, int)}, every cell named {@code name} (N1: an imported design's tag). */
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
        if (shape.fluidDevice() && rows >= shape.sizeY()) register(level, vessel);
        // A motor, generator or gearbox joins its shaft train whole (W3); a casing or turbine does once registered.
        else if (!shape.fluidDevice() && rows >= shape.sizeY()) ShaftParts.structure(level, origin, shape, 0);
        if(shape.kind()==EquipmentShape.Kind.COLUMN&&rows>=shape.sizeY()){var c=layout.controller();if(level.getBlockEntity(origin.offset(c.x(),c.y(),c.z())) instanceof EquipmentShellBlockEntity cell)FluidWorldAuthority.find(level.getServer()).ifPresent(w->w.controls().bindColumn(cell));}
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
        if (vessel.shape().kind() == EquipmentShape.Kind.BATCH_STILL) {
            registerStill(level, vessel, 0);
            return;
        }
        if (HeatSides.unit(vessel.shape().kind()) != null) {
            registerUnit(level, vessel);
            return;
        }
        var node = node(vessel);
        if (vessel.shape().kind() == EquipmentShape.Kind.FLARE_STACK) {
            // A flare's tip at its gas inlet's cell (batch 2026-11-09-flare-stack), its tip diameter the structure's size.
            FluidWorldAuthority.find(level.getServer()).ifPresent(world -> {
                var record = world.placeFlare(position(level, node), ports(level, vessel), vessel.shape().diameter());
                if (level.getBlockEntity(node) instanceof EquipmentShellBlockEntity entity) entity.bindIdentity(record.device().id());
            });
            return;
        }
        if (vessel.shape().kind().chimney()) {
            // A chimney's tip in its top cell (batch 2026-11-10-chimney): the stack from its inlet up is the run into it.
            FluidWorldAuthority.find(level.getServer()).ifPresent(world -> {
                var record = world.placeChimney(position(level, node), ports(level, vessel), vessel.shape());
                if (level.getBlockEntity(node) instanceof EquipmentShellBlockEntity entity) entity.bindIdentity(record.device().id());
            });
            return;
        }
        if (vessel.shape().kind() == EquipmentShape.Kind.EJECTOR) {
            // A steam ejector set on its curve at its suction's cell (batch 2026-11-10-steam-ejector-flow): no shaft.
            FluidWorldAuthority.find(level.getServer()).ifPresent(world -> {
                var record = world.placeMachine(position(level, node), TopologyCompiler.Kind.EJECTOR, ports(level, vessel), ejectorControl(vessel.shape()));
                if (level.getBlockEntity(node) instanceof EquipmentShellBlockEntity entity) entity.bindIdentity(record.device().id());
            });
            return;
        }
        if (vessel.shape().kind().primeMover()) {
            // A prime mover (batch 2026-11-10-prime-movers): its fuel void at its combustor's pressure behind its FUEL nozzle, a gas
            // turbine's steam coil at its FEEDWATER nozzle; its shaft unit names the fuel void.
            FluidWorldAuthority.find(level.getServer()).ifPresent(world -> {
                var shape = vessel.shape();
                var model = com.wormzjl.icheme.science.equipment.UnitDesign.primeMoverModel(shape);
                var cells = nodes(vessel);
                var fuelPorts = rolePorts(level, vessel, EquipmentShape.Role.FUEL);
                FluidWorldAuthority.UnitSide coil = null;
                if (cells.size() > 1) {
                    var definition = new HeatSides.Definition(HeatSides.Side.EXHAUST, EquipmentShape.Role.FEEDWATER, EquipmentShape.Role.STEAM,
                            com.wormzjl.icheme.science.equipment.UnitDesign.coilSurface(shape), com.wormzjl.icheme.science.equipment.UnitDesign.coilPassage(shape));
                    coil = new FluidWorldAuthority.UnitSide(position(level, cells.get(1)), sidePorts(level, vessel, definition), definition.passage(),
                            new HeatUnit(HeatSides.Unit.PRIME_MOVER, HeatSides.Side.EXHAUST, com.wormzjl.icheme.science.equipment.UnitDesign.coilSurface(shape), 0));
                }
                var placed = world.placePrimeMover(position(level, cells.getFirst()), fuelPorts, model.family() == com.wormzjl.icheme.science.machine.PrimeMover.Family.GAS_TURBINE
                        ? model.combustor() : com.wormzjl.icheme.science.machine.PrimeMover.ATMOSPHERE, coil);
                for (int i = 0; i < placed.size(); i++)
                    if (level.getBlockEntity(cells.get(i)) instanceof EquipmentShellBlockEntity entity) entity.bindIdentity(placed.get(i).device().id());
                ShaftParts.structure(level, vessel.origin(), shape, placed.getFirst().device().id());
            });
            return;
        }
        if (vessel.shape().kind().mover() || turbine(vessel.shape())) {
            // A casing on its curve, a frame at its displacement, a turbine on its steam (W3): each turns with its shaft train.
            var kind = turbine(vessel.shape()) ? TopologyCompiler.Kind.TURBINE : vessel.shape().kind() == EquipmentShape.Kind.PUMP ? TopologyCompiler.Kind.PUMP : TopologyCompiler.Kind.COMPRESSOR;
            FluidWorldAuthority.find(level.getServer()).ifPresent(world -> {
                var record = world.placeMachine(position(level, node), kind, ports(level, vessel), machineControl(vessel.shape()));
                if (level.getBlockEntity(node) instanceof EquipmentShellBlockEntity entity) entity.bindIdentity(record.device().id());
                ShaftParts.structure(level, vessel.origin(), vessel.shape(), record.device().id());
            });
            return;
        }
        if (vessel.shape().volume() > FluidDeviceSpec.MAX_VOLUME) {
            IChemE.LOGGER.warn("A {} of {} m³ is over the fluid model's {} m³: not registered", vessel.shape().kind().name().toLowerCase(Locale.ROOT),
                    Math.round(vessel.shape().volume()), Math.round(FluidDeviceSpec.MAX_VOLUME));
            return;
        }
        FluidWorldAuthority.find(level.getServer()).ifPresent(world -> {
            var record = world.placeVessel(position(level, node), ports(level, vessel), vessel.shape().volume(), vessel.shape().levelHeight(), open(vessel), vessel.shape().fluidGeometry(),vessel.shape().kind().coolingTower()?vessel.shape().coolingTower():null);
            if (level.getBlockEntity(node) instanceof EquipmentShellBlockEntity entity) entity.bindIdentity(record.device().id());
        });
    }

    /**
     * Rebind a saved registration, or queue defaults for a complete placed structure with none. Returns false only
     * while chunks are missing, so a later chunk-load event retries it. Partial or stale layouts stay inactive;
     * normal construction or the existing layout refresh handles them. No blocks are changed and nothing solves.
     */
    public static boolean restoreFluidBinding(ServerLevel level,Vessel vessel) {
        // A motor, generator or gearbox's shaft unit (W3); queued again on every load, a no-op when nothing changed.
        if(!vessel.shape().fluidDevice()){ShaftParts.structure(level,vessel.origin(),vessel.shape(),0);return true;}
        var world=FluidWorldAuthority.find(level.getServer()).orElse(null);if(world==null)return false;
        if(vessel.shape().kind()==EquipmentShape.Kind.BATCH_STILL)return restoreStill(level,vessel,world);
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
                // A casing saved before W3 (reading A29): its set-flow control becomes its machine, keeping its set flow.
                var machine=restoredControl(vessel.shape(),record.device().control());
                if(machine!=null)world.edit(record.device().id(),record.revision(),record.device().withControl(machine),record.spec());
                ShaftParts.structure(level,vessel.origin(),vessel.shape(),record.device().id());
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
            placing.add(new FluidWorldAuthority.UnitSide(position(level, nodes.get(i)), sidePorts(level, vessel, side), side.passage(), new HeatUnit(unit, side.side(), side.area(), 0).withFans(HeatSides.fans(shape)).withExchangerType(shape.exchangerType())));
        }
        // A kettle's shell (W7): a closed vessel at its pool's node, its phase ports the drum's, its surface its tubes'.
        var pool = HeatSides.pool(shape.kind()) ? new FluidWorldAuthority.UnitVessel(position(level, nodes.get(sides.size())), ports(level, vessel), shape.volume(), shape.levelHeight(),
                shape.fluidGeometry(), new HeatUnit(unit, HeatSides.Side.SHELL, sides.getFirst().area(), 0)) : null;
        // A fired heater's burners (batch 2026-11-09-furnace-fuel-power): the firebox behind its FUEL nozzle, after its coils.
        var burners = HeatSides.burners(shape.kind()) ? burners(level, vessel, nodes.get(sides.size())) : null;
        FluidWorldAuthority.find(level.getServer()).ifPresent(world -> {
            var records = world.placeUnit(placing, pool, burners);
            for (int i = 0; i < records.size(); i++)
                if (level.getBlockEntity(nodes.get(i)) instanceof EquipmentShellBlockEntity entity) entity.bindIdentity(records.get(i).device().id());
        });
    }

    /** A heat side's nozzles: its inlets and outlets in nozzle order, so its first inlet is its node ({@link ShapeRasterizer#fluidCells}). */
    /** The vessel port of {@code vessel}'s first nozzle of {@code role} (a prime mover's fuel), or none. */
    private static List<PhysicalFluidTopology.Port> rolePorts(Level level, Vessel vessel, EquipmentShape.Role role) {
        var nozzles = vessel.shape().nozzles();
        for (var port : ShapeRasterizer.ports(vessel.shape())) {
            if (nozzles.get(port.nozzle()).role() != role) continue;
            var cell = vessel.origin().offset(port.cell().x(), port.cell().y(), port.cell().z());
            var face = PhysicalFluidTopology.Direction.valueOf(Direction.fromDelta(port.dx(), port.dy(), port.dz()).name());
            return List.of(new PhysicalFluidTopology.Port(position(level, cell), face, PassiveNetwork.PhasePort.BULK, PhysicalFluidTopology.ConnectionRole.VESSEL));
        }
        return List.of();
    }

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

    /** A heat unit's registrations: one per side, a kettle's shell (W7) and a fired heater's burners. */
    private static int registrations(EquipmentShape shape) {
        return HeatSides.of(shape).size() + (HeatSides.pool(shape.kind()) ? 1 : 0) + (HeatSides.burners(shape.kind()) ? 1 : 0);
    }

    /** A fired heater's burners to place at {@code node}: the FUEL nozzle its one port (batch 2026-11-09-furnace-fuel-power). */
    private static FluidWorldAuthority.UnitBurners burners(Level level, Vessel vessel, BlockPos node) {
        var ports = new ArrayList<PhysicalFluidTopology.Port>();
        var nozzles = vessel.shape().nozzles();
        for (var port : ShapeRasterizer.ports(vessel.shape())) {
            if (nozzles.get(port.nozzle()).role() != HeatSides.BURNER_ROLE) continue;
            var cell = vessel.origin().offset(port.cell().x(), port.cell().y(), port.cell().z());
            var face = PhysicalFluidTopology.Direction.valueOf(Direction.fromDelta(port.dx(), port.dy(), port.dz()).name());
            ports.add(new PhysicalFluidTopology.Port(position(level, cell), face, PassiveNetwork.PhasePort.BULK, PhysicalFluidTopology.ConnectionRole.VESSEL));
            break;
        }
        var radiant = HeatSides.of(vessel.shape()).stream().filter(s -> s.side() == HeatSides.Side.RADIANT).findFirst().orElseThrow();
        return new FluidWorldAuthority.UnitBurners(position(level, node), ports, new HeatUnit(HeatSides.Unit.FIRED_HEATER, HeatSides.Side.BURNER, radiant.area(), 0));
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
        // A fired heater placed before its burners registered (batch 2026-11-09-furnace-fuel-power): its coils keep their
        // identities, set point and burners' switch; the burners join them at their node, partnered with the radiant coil.
        if (HeatSides.burners(vessel.shape().kind()) && !records.containsKey(HeatSides.Side.BURNER) && records.containsKey(HeatSides.Side.RADIANT)
                && records.containsKey(HeatSides.Side.CONVECTION) && records.size() + 1 == registrations(vessel.shape()))
            records.put(HeatSides.Side.BURNER, world.placeBurners(records.get(HeatSides.Side.RADIANT).device().id(), burners(level, vessel, nodes(vessel).get(sides.size()))));
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
            // An air cooler's fans follow the drawing (W5), as its surface does.
            var moved = device.withPlacement(position(level, node), sidePorts(level, vessel, side)).withGeometry(side.passage()).withHeat(device.heat().withArea(side.area()).withFans(HeatSides.fans(vessel.shape())));
            if (!moved.equals(device)) world.edit(device.id(), record.revision(), moved, record.spec());
            for (var other : nodes)
                if (!other.equals(node) && level.getBlockEntity(other) instanceof EquipmentShellBlockEntity stale && stale.fluidIdentity() == device.id()) stale.releaseIdentity();
            if (level.getBlockEntity(node) instanceof EquipmentShellBlockEntity entity) {
                if (entity.fluidIdentity() != device.id()) entity.bindIdentity(device.id());
                else world.deviceLoaded(device.id());
            }
        }
        if (HeatSides.burners(vessel.shape().kind())) {
            // The burners move to their FUEL nozzle's node with it.
            var record = records.get(HeatSides.Side.BURNER);
            var node = nodes.get(sides.size());
            var device = record.device();
            var moved = device.withPlacement(position(level, node), burners(level, vessel, node).ports()).withHeat(device.heat().withArea(records.get(HeatSides.Side.RADIANT).device().heat().area()));
            if (!moved.equals(device)) world.edit(device.id(), record.revision(), moved, record.spec());
            for (var other : nodes)
                if (!other.equals(node) && level.getBlockEntity(other) instanceof EquipmentShellBlockEntity stale && stale.fluidIdentity() == device.id()) stale.releaseIdentity();
            if (level.getBlockEntity(node) instanceof EquipmentShellBlockEntity entity) {
                if (entity.fluidIdentity() != device.id()) entity.bindIdentity(device.id());
                else world.deviceLoaded(device.id());
            }
            return;
        }
        if (!HeatSides.pool(vessel.shape().kind())) return;
        // A kettle's shell (W7) moves to its pool's node with its ports, its vessel follows the datasheet, it keeps what it holds.
        var record = records.get(HeatSides.Side.SHELL);
        var node = nodes.get(sides.size());
        var device = record.device();
        var shape = vessel.shape();
        var moved = device.withPlacement(position(level, node), ports(level, vessel)).withHeat(device.heat().withArea(sides.getFirst().area()));
        var spec = record.spec();
        var respec = new FluidDeviceSpec(shape.volume(), spec.temperature(), spec.pressure(), spec.composition(), spec.solids(), shape.levelHeight(), false, shape.fluidGeometry(), null);
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
        return vessel.shape().kind() == EquipmentShape.Kind.TANK || vessel.shape().kind().coolingTower();
    }

    /** A reservoir's lowest inside cell, or a mover's suction nozzle cell ({@link ShapeRasterizer#fluidCell}). */
    private static BlockPos node(Vessel vessel) {
        var cell = ShapeRasterizer.fluidCell(vessel.shape());
        return vessel.origin().offset(cell.x(), cell.y(), cell.z());
    }

    private static PhysicalFluidTopology.Position position(Level level, BlockPos at) {
        return new PhysicalFluidTopology.Position(level.dimension().location().toString(), at.getX(), at.getY(), at.getZ());
    }

    /**
     * The registry's record of the structure at {@code origin} (batch 2026-11-03-save-corruption, REGISTRY_FIRST_PLAN.md
     * 3.3): its shape and paint, or none when {@code shape} is null. The registry places the structure again from it. The
     * record's shape text is made only when the record changes.
     */
    private static void record(ServerLevel level, BlockPos origin, @Nullable EquipmentShape shape, @Nullable Paint paint, String name) {
        var world = FluidWorldAuthority.find(level.getServer()).orElse(null);
        if (world == null) return;
        var at = position(level, origin);
        var held = world.structure(at);
        if (shape == null) {
            if (held != null) world.structure(at, null);
        } else if (held == null || !held.shape().equals(shape) || !held.paint().equals(paint) || !held.name().equals(name))
            world.structure(at, WorldTopologyLedger.Structure.of(shape, paint, name));
    }

    /**
     * The registry first for a current vessel's paint (REGISTRY_FIRST_PLAN.md 3.5): a vessel the registry holds no record
     * of teaches it its shape and its controller's paint (a world saved before the registry held structures); a vessel
     * whose record has its shape and another paint takes the record's paint on every shell cell. A vessel's cells share one
     * paint ({@link #place} and {@link #refresh} bind each with it), so the cells are walked only when the controller's
     * differs. A record of another shape waits for the re-alignment (plan 4.4).
     */
    private static void restatePaint(ServerLevel level, BlockPos origin, EquipmentShape shape, Paint paint, String name) {
        var world = FluidWorldAuthority.find(level.getServer()).orElse(null);
        if (world == null) return;
        var at = position(level, origin);
        var held = world.structure(at);
        if (held == null) {
            record(level, origin, shape, paint, name);
            return;
        }
        if (!held.shape().equals(shape)) return;
        // A named vessel whose record holds no name teaches it (a world saved before the registry held names, W5c C4).
        if (held.name().isEmpty() && !name.isEmpty()) world.structure(at, held = held.withName(name));
        if (held.paint().equals(paint) && held.name().equals(name)) return;
        for (var cell : ShapeRasterizer.layout(shape).shell())
            if (level.getBlockEntity(origin.offset(cell.x(), cell.y(), cell.z())) instanceof EquipmentShellBlockEntity shell && ours(shell, origin, shape)) {
                shell.repaint(held.paint());
                shell.rename(held.name());
            }
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
            if (vessel.shape().kind() == EquipmentShape.Kind.EJECTOR) {
                // A steam ejector's suction, its motive steam as an inlet, its discharge (batch 2026-11-10-steam-ejector-flow).
                role = switch (nozzles.get(port.nozzle()).role()) {
                    case SUCTION -> PhysicalFluidTopology.ConnectionRole.SUCTION;
                    case MOTIVE -> PhysicalFluidTopology.ConnectionRole.INLET;
                    case DISCHARGE -> PhysicalFluidTopology.ConnectionRole.DISCHARGE;
                    default -> null;
                };
                if (role == null) continue;
            } else if (vessel.shape().kind().mover() || turbine(vessel.shape())) {
                // A turbine's steam inlet is its suction, its exhaust its discharge (W3, reading A37).
                role = switch (nozzles.get(port.nozzle()).role()) {
                    case SUCTION, STEAM -> PhysicalFluidTopology.ConnectionRole.SUCTION;
                    case DISCHARGE, EXHAUST -> PhysicalFluidTopology.ConnectionRole.DISCHARGE;
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
            if(turbine(vessel.shape())||vessel.shape().kind()==EquipmentShape.Kind.EJECTOR)phase=PassiveNetwork.PhasePort.BULK;
            if(vessel.shape().kind().coolingTower())phase=switch(nozzles.get(port.nozzle()).role()){case HOT_WATER_INLET->PassiveNetwork.PhasePort.SPRAY;default->phase;};
            if(vessel.shape().boot())phase=switch(nozzles.get(port.nozzle()).role()){case BOOT_DRAIN->PassiveNetwork.PhasePort.BOOT_WATER;case LIQUID_OUTLET->PassiveNetwork.PhasePort.BOOT_HC;default->phase;};
            ports.add(new PhysicalFluidTopology.Port(position(level, cell), face, phase, role));
        }
        return ports;
    }

    /** The equipment's registration leaves with its structure (a reservoir's contents go with it). */
    private static void unregister(ServerLevel level, Vessel vessel) {
        if (vessel.shape().kind() == EquipmentShape.Kind.BATCH_STILL) {
            dropStillFuel(level, vessel);
            FluidWorldAuthority.find(level.getServer()).ifPresent(world -> world.removeUnit(heldAtNodes(world, level, vessel)));
            return;
        }
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


    // ---------------- the batch still (batch 2026-11-09-batch-distiller, W3 and W4) ----------------

    /**
     * A whole batch still joins the fluid network as one event ({@link FluidWorldAuthority#placeStill}): its pot, a fired
     * reservoir at its lowest inside cell with its CHARGE, DRAIN and (without the condenser) VAPOUR nozzles; its condenser's
     * water side through WATER IN and OUT; its DISTILLATE and VENT outlets and its burners at FUEL, each at its nozzle's cell.
     * {@code former}, the pot of the still it refreshes (0 for none): that still's devices move onto this layout, keeping
     * their identities, facts and contents (decision D7, owner decision O9); a cell holding one of its identities that its
     * device left lets it go.
     */
    private static void registerStill(ServerLevel level, Vessel vessel, long former) {
        var shape = vessel.shape();var options = shape.still();
        var cells = nodes(vessel);if (cells.isEmpty()) return;
        var heater = switch (options.heater()) {
            case SOLID -> com.wormzjl.icheme.science.fluid.network.BatchStill.Heater.SOLID;
            case LIQUID -> com.wormzjl.icheme.science.fluid.network.BatchStill.Heater.LIQUID;
            case ELECTRIC -> com.wormzjl.icheme.science.fluid.network.BatchStill.Heater.ELECTRIC;
        };
        var design = new com.wormzjl.icheme.science.fluid.network.BatchStill.Design(shape.volume(), heater, options.condenses(), options.stages());
        // The pot's liquid stands over its straight side and its head: 1.1 diameters (ProcessUnit.StillPlan).
        double height = 1.1 * design.diameter();
        var potPorts = stillPorts(level, vessel, java.util.Set.of(EquipmentShape.Role.CHARGE, EquipmentShape.Role.DRAIN, EquipmentShape.Role.VAPOUR_OUTLET), PhysicalFluidTopology.ConnectionRole.VESSEL);
        var pot = new FluidWorldAuthority.UnitVessel(position(level, cells.getFirst()), potPorts, shape.volume(), height,
                com.wormzjl.icheme.science.fluid.network.VesselGeometry.prism(shape.volume(), height),
                new HeatUnit(HeatSides.Unit.STILL, HeatSides.Side.POT, design.heatedArea(), 0));
        FluidWorldAuthority.UnitSide water = null;
        if (design.condenser()) {
            var ports = new ArrayList<PhysicalFluidTopology.Port>();
            ports.addAll(stillPorts(level, vessel, java.util.Set.of(EquipmentShape.Role.WATER_INLET), PhysicalFluidTopology.ConnectionRole.INLET));
            ports.addAll(stillPorts(level, vessel, java.util.Set.of(EquipmentShape.Role.WATER_OUTLET), PhysicalFluidTopology.ConnectionRole.OUTLET));
            // The worm's water passage: the tub's (or the shell's) water space, a short wide passage.
            water = new FluidWorldAuthority.UnitSide(ports.getFirst().cell(), ports, new com.wormzjl.icheme.science.fluid.network.PipeResistance.Geometry(1, .1, com.wormzjl.icheme.science.fluid.network.PipeResistance.DEFAULT_ROUGHNESS_METRES, 0),
                    new HeatUnit(HeatSides.Unit.STILL, HeatSides.Side.WATER, design.condenserArea(), 0));
        }
        var distillate = stillPort(level, vessel, EquipmentShape.Role.DISTILLATE);
        var vent = stillPort(level, vessel, EquipmentShape.Role.VENT);
        var burners = heater == com.wormzjl.icheme.science.fluid.network.BatchStill.Heater.LIQUID ? stillPort(level, vessel, EquipmentShape.Role.FUEL) : null;
        var devices = new FluidWorldAuthority.StillDevices(pot, water, design.condenser() ? distillate : null, design.condenser() ? vent : null, burners, design);
        FluidWorldAuthority.find(level.getServer()).ifPresent(world -> {
            var records = world.placeStill(devices, former);
            var bound = new java.util.HashMap<BlockPos, Long>();
            for (var record : records) {
                var d = record.device();bound.put(new BlockPos(d.position().x(), d.position().y(), d.position().z()), d.id());
            }
            for (var entry : bound.entrySet())
                if (level.getBlockEntity(entry.getKey()) instanceof EquipmentShellBlockEntity entity) {
                    if (entity.fluidIdentity() != entry.getValue()) entity.bindIdentity(entry.getValue());
                    else world.deviceLoaded(entry.getValue());
                }
            var ids = new java.util.HashSet<>(bound.values());
            for (var at : cells)
                if (!bound.containsKey(at) && level.getBlockEntity(at) instanceof EquipmentShellBlockEntity stale && ids.contains(stale.fluidIdentity())) stale.releaseIdentity();
        });
    }

    /** The still's nozzles of {@code roles} as ports of {@code connection}, each of its nozzle's phase class. */
    private static List<PhysicalFluidTopology.Port> stillPorts(Level level, Vessel vessel, java.util.Set<EquipmentShape.Role> roles, PhysicalFluidTopology.ConnectionRole connection) {
        var ports = new ArrayList<PhysicalFluidTopology.Port>();
        var nozzles = vessel.shape().nozzles();
        for (var port : ShapeRasterizer.ports(vessel.shape())) {
            if (!roles.contains(nozzles.get(port.nozzle()).role())) continue;
            var cell = vessel.origin().offset(port.cell().x(), port.cell().y(), port.cell().z());
            var face = PhysicalFluidTopology.Direction.valueOf(Direction.fromDelta(port.dx(), port.dy(), port.dz()).name());
            var phase = connection != PhysicalFluidTopology.ConnectionRole.VESSEL ? PassiveNetwork.PhasePort.BULK : switch (port.port()) {
                case VAPOUR -> PassiveNetwork.PhasePort.VAPOR;
                case LIQUID -> PassiveNetwork.PhasePort.LIQUID;
                case BULK, OVERFLOW -> PassiveNetwork.PhasePort.BULK;
            };
            ports.add(new PhysicalFluidTopology.Port(position(level, cell), face, phase, connection));
        }
        return ports;
    }

    /** A still device's one port at its nozzle of {@code role} (bulk), or null where the still has none. */
    private static PhysicalFluidTopology.@Nullable Port stillPort(Level level, Vessel vessel, EquipmentShape.Role role) {
        var ports = stillPorts(level, vessel, java.util.Set.of(role), PhysicalFluidTopology.ConnectionRole.VESSEL);
        if (ports.isEmpty()) return null;
        var p = ports.getFirst();
        return new PhysicalFluidTopology.Port(p.cell(), p.face(), PassiveNetwork.PhasePort.BULK, PhysicalFluidTopology.ConnectionRole.VESSEL);
    }

    /** {@link #restoreStill(ServerLevel, Vessel, FluidWorldAuthority, java.util.Collection)} from the identities its cells hold now. */
    private static boolean restoreStill(ServerLevel level, Vessel vessel, FluidWorldAuthority world) {
        for (var at : nodes(vessel)) if (!level.hasChunkAt(at)) return false;
        var layout = ShapeRasterizer.layout(vessel.shape());
        for (var cell : layout.shell()) {
            var at = vessel.origin().offset(cell.x(), cell.y(), cell.z());
            if (!(level.getBlockEntity(at) instanceof EquipmentShellBlockEntity shell) || !ours(shell, vessel.origin(), vessel.shape()) || shell.stale()) return true;
        }
        restoreStill(level, vessel, world, heldAtNodes(world, level, vessel));
        return true;
    }

    /**
     * A still's devices onto {@code vessel}'s layout: where each of its cells holds the device it registers (the pot a
     * fired reservoir, the others theirs), the cells bind them; otherwise the still whose pot {@code held} names moves onto
     * the layout whole (its identities, facts and contents kept), and {@code held}'s other units leave. With no still
     * among them the still registers afresh.
     */
    private static void restoreStill(ServerLevel level, Vessel vessel, FluidWorldAuthority world, java.util.Collection<Long> held) {
        var cells = nodes(vessel);
        boolean whole = !cells.isEmpty();long pot = 0;
        for (int i = 0; i < cells.size() && whole; i++) {
            var at = position(level, cells.get(i));
            var record = world.at(at).filter(r -> r.device().position().equals(at) && !r.device().ports().isEmpty()).orElse(null);
            if (record == null || (i == 0) != record.device().stillPot()) whole = false;
            else if (i == 0) pot = record.device().id();
        }
        if (whole && world.stills().view(pot) != null) {
            for (var at : cells) {
                var p = position(level, at);
                world.at(p).ifPresent(r -> {
                    if (level.getBlockEntity(at) instanceof EquipmentShellBlockEntity shell) {
                        if (shell.fluidIdentity() != r.device().id()) shell.bindIdentity(r.device().id());
                        else world.deviceLoaded(r.device().id());
                    }
                });
            }
            return;
        }
        long former = 0;
        for (long id : held) {var r = world.registrations().get(id);if (r != null && r.device().stillPot() && world.stills().view(id) != null) former = id;}
        var still = former > 0 ? world.stillDevices(former) : java.util.Set.<Long>of();
        var others = held.stream().filter(id -> !still.contains(id)).toList();
        if (!others.isEmpty()) world.removeUnit(others);
        registerStill(level, vessel, former);
    }

    /** A broken still's fuel slot drops its items at its pot's cell; the heat left in the burning item is lost with it. */
    private static void dropStillFuel(ServerLevel level, Vessel vessel) {
        var cell = ShapeRasterizer.fluidCell(vessel.shape());if (cell == null) return;
        var at = vessel.origin().offset(cell.x(), cell.y(), cell.z());
        if (level.getBlockEntity(at) instanceof EquipmentShellBlockEntity shell) {
            var stack = shell.dropFuel();
            if (!stack.isEmpty()) net.minecraft.world.Containers.dropItemStack(level, at.getX() + .5, at.getY() + .5, at.getZ() + .5, stack);
        }
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
     * (a save from before {@link EquipmentInteriorBlock#SKYLIGHT}) to it, and lets its chunks wait for their light; its
     * paint follows the registry ({@link #restatePaint}). Only a vessel whose controller cell is its own and current: a
     * stale one is refreshed instead, which restates it too.
     */
    static void restate(ServerLevel level, BlockPos origin, EquipmentShape shape) {
        var controller = ShapeRasterizer.layout(shape).controller();
        if (!(level.getBlockEntity(origin.offset(controller.x(), controller.y(), controller.z())) instanceof EquipmentShellBlockEntity shell)
                || !ours(shell, origin, shape) || shell.stale()) return;
        restatePaint(level, origin, shape, shell.paint(), shell.name());
        int changed = restateCells(level, origin, shape, Set.of(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        if (changed == 0) return;
        waitForLight(level, origin, corner(origin, shape));
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
                setCell(level, at, state, flags);
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
            if (level instanceof ServerLevel server && vessel.shape().fluidDevice()) unregister(server, vessel);
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
        record(level, origin, null, null, com.wormzjl.icheme.science.equipment.DeviceName.NONE);
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        dismantling = true;
        try {
            for (var at : interior) setCell(level, at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), flags);
            for (var at : shells) setCell(level, at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), flags);
        } finally {
            dismantling = false;
        }
        waitForLight(level, retired.low(), retired.high());
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
        var survey = survey(level, origin, low, high, shell -> ours(shell, origin, shape), false);
        if (survey.paint() == null) return;
        // A rebuilt structure is a structural change to every assembly report that relied on it.
        com.wormzjl.icheme.runtime.assembly.AssemblyService.removed(level, new Vessel(origin, shape));
        var plan = EquipmentRefresh.plan(ShapeRasterizer.layout(shape), survey.world());
        var vessel = new Vessel(origin.offset(plan.shift().x(), 0, plan.shift().z()), shape);
        var touched = write(level, vessel, survey.paint(), survey.name(), plan, false);
        waitForLight(level, low, high);
        // The registry's record follows the structure to where its layout now stands, cells left out or not: the registry
        // holds the structure the player built.
        if (!vessel.origin().equals(origin)) record(level, origin, null, null, com.wormzjl.icheme.science.equipment.DeviceName.NONE);
        record(level, vessel.origin(), shape, survey.paint(), survey.name());
        // A shaft unit stands at its structure's origin (W3): a moved origin's old unit leaves, the new one is queued here.
        if (UnitDesign.shafted(shape.kind()) && !vessel.origin().equals(origin)) ShaftParts.remove(level, origin);
        if (shape.fluidDevice()) reregister(level, vessel, survey.node(), survey.identities());
        else ShaftParts.structure(level, vessel.origin(), shape, 0);
        settle(level, vessel, touched);
        IChemE.LOGGER.info("equipment_refresh kind={} origin={} shift={},{} cleared={} placed={} blocked={}", shape.kind().name().toLowerCase(Locale.ROOT),
                origin.toShortString(), plan.shift().x(), plan.shift().z(), plan.clear().size(), plan.shell().size() + plan.interior().size(), plan.blocked().size());
        if (!plan.blocked().isEmpty()) {
            var first = plan.blocked().getFirst();
            IChemE.LOGGER.warn("equipment_refresh left {} cells out: other blocks stand there, first at {}", plan.blocked().size(),
                    vessel.origin().offset(first.x(), first.y(), first.z()).toShortString());
        }
    }

    /**
     * What stands in a vessel's region ({@link #survey}): each cell's holding from the origin, free cells absent; the paint
     * and name of the first of its own shell cells (paint null when none stands); the last of its own shell cells that holds a fluid
     * identity, and the identities they hold; the far corner of the boxes its own cells were placed by.
     */
    private record Survey(Map<ShapeRasterizer.Cell, EquipmentRefresh.Holding> world, @Nullable Paint paint, String name, @Nullable BlockPos node, List<Long> identities,
                          BlockPos far) {
    }

    /**
     * Reads what stands between {@code low} and {@code high}, from {@code origin}. A shell cell is the vessel's own when
     * {@code own} accepts it; an interior cell when a walk over interior cells reaches one of its own shell cells, or, with
     * {@code orphans}, reaches none (an interior cell holds no data: one whose shell cells are gone is the vessel's whose
     * region it lies in); a fluid device block is a pipe; any other block that is not replaceable is another owner's.
     */
    private static Survey survey(ServerLevel level, BlockPos origin, BlockPos low, BlockPos high, Predicate<EquipmentShellBlockEntity> own, boolean orphans) {
        var world = new HashMap<ShapeRasterizer.Cell, EquipmentRefresh.Holding>();
        Paint paint = null;
        String name = com.wormzjl.icheme.science.equipment.DeviceName.NONE;
        BlockPos node = null;
        var identities = new ArrayList<Long>();
        var far = origin;
        for (var at : BlockPos.betweenClosed(low, high)) {
            var cell = new ShapeRasterizer.Cell(at.getX() - origin.getX(), at.getY() - origin.getY(), at.getZ() - origin.getZ());
            var state = level.getBlockState(at);
            if (level.getBlockEntity(at) instanceof EquipmentShellBlockEntity shell && own.test(shell)) {
                world.put(cell, EquipmentRefresh.Holding.SHELL);
                if (paint == null) {
                    paint = shell.paint();
                    name = shell.name();
                }
                if (shell.fluidIdentity() != 0) {
                    node = at.immutable();
                    identities.add(shell.fluidIdentity());
                }
                far = max(far, corner(origin, shell.shape()));
            } else if (state.is(ModBlocks.EQUIPMENT_INTERIOR.get())) {
                var shell = shellReached(level, at);
                boolean owned = shell == null ? orphans : own.test(shell);
                world.put(cell, owned ? EquipmentRefresh.Holding.INTERIOR : EquipmentRefresh.Holding.OTHER);
                if (owned && shell != null) far = max(far, corner(origin, shell.shape()));
            } else if (state.getBlock() instanceof FluidDeviceBlock) world.put(cell, EquipmentRefresh.Holding.PIPE);
            else if (!state.canBeReplaced()) world.put(cell, EquipmentRefresh.Holding.OTHER);
        }
        return new Survey(world, paint, name, node, identities, far);
    }

    /**
     * Writes {@code plan} for {@code vessel}, whose origin is the old one moved by the plan's shift: clears the cells the
     * plan drops, places the shell and interior cells it adds, binds every shell cell to the layout, and brings the interior
     * cells it kept to their current state ({@link #restateCells}). A shell cell it kept keeps its own paint unless
     * {@code repaint}; every shell cell takes {@code name} (N1). With cells left out where other blocks stand, the cells
     * written keep no stamp, so the next load tries again. Returns the cells whose block or drawing changed.
     */
    private static List<BlockPos> write(ServerLevel level, Vessel vessel, Paint paint, String name, EquipmentRefresh.Plan plan, boolean repaint) {
        var placed = vessel.origin();
        var shape = vessel.shape();
        var layout = ShapeRasterizer.layout(shape);
        var placeShell = new HashSet<>(plan.shell());
        var blocked = new HashSet<>(plan.blocked());
        var touched = new ArrayList<BlockPos>();
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        dismantling = true;
        try {
            for (var cell : plan.clear()) {
                var at = placed.offset(cell.x(), cell.y(), cell.z());
                setCell(level, at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), flags);
                touched.add(at);
            }
            for (var cell : layout.shell()) {
                if (blocked.contains(cell)) continue;
                var at = placed.offset(cell.x(), cell.y(), cell.z());
                if (placeShell.contains(cell)) {
                    setCell(level, at, ModBlocks.EQUIPMENT_SHELL.get().defaultBlockState(), flags);
                    touched.add(at);
                }
                if (level.getBlockEntity(at) instanceof EquipmentShellBlockEntity entity) {
                    var before = entity.key();
                    var paintBefore = entity.paint();
                    var cellPaint = repaint || placeShell.contains(cell) ? paint : paintBefore;
                    entity.bind(shape, cell, cellPaint, name);
                    if (!entity.key().equals(before) || !cellPaint.equals(paintBefore)) {
                        level.sendBlockUpdated(at, entity.getBlockState(), entity.getBlockState(), Block.UPDATE_CLIENTS);
                        touched.add(at);
                    }
                }
            }
            for (var cell : plan.interior()) setCell(level, placed.offset(cell.x(), cell.y(), cell.z()), interior(shape), flags);
            // The interior cells it kept take the current state too (restate); a blocked cell is another owner's.
            restateCells(level, placed, shape, blocked, flags);
            // Cells left out where other blocks stand: the cells written keep no stamp, so the next load tries again.
            if (!blocked.isEmpty())
                for (var cell : layout.shell())
                    if (!blocked.contains(cell) && level.getBlockEntity(placed.offset(cell.x(), cell.y(), cell.z())) instanceof EquipmentShellBlockEntity entity) entity.layoutIncomplete();
        } finally {
            dismantling = false;
        }
        return touched;
    }

    /**
     * After a rebuild: the pipes beside a dropped, added or redrawn cell take up or drop their connection (old nozzles
     * included), the vessel's nozzles redraw at the sizes of their pipes, and the assemblies that rely on it learn of it.
     */
    private static void settle(ServerLevel level, Vessel vessel, List<BlockPos> touched) {
        for (var at : touched)
            for (var direction : Direction.values()) {
                var next = at.relative(direction);
                var state = level.getBlockState(next);
                if (state.getBlock() instanceof FluidDeviceBlock) Block.updateOrDestroy(state, Block.updateFromNeighbourShapes(state, level, next), level, next, Block.UPDATE_ALL);
            }
        reconnect(level, vessel);
        refreshNozzles(level, vessel);
        com.wormzjl.icheme.runtime.assembly.AssemblyService.placed(level, vessel);
    }

    /**
     * Each chunk from {@code low} to {@code high} waits for its light before it is sent to a player: the server sends a
     * player light changes only on the edge of its view, so a chunk sent with the light of before would keep it.
     */
    private static void waitForLight(ServerLevel level, BlockPos low, BlockPos high) {
        for (int x = low.getX() >> 4; x <= high.getX() >> 4; x++)
            for (int z = low.getZ() >> 4; z <= high.getZ() >> 4; z++) level.getChunkSource().chunkMap.waitForLightBeforeSending(new ChunkPos(x, z), 0);
    }

    /** The far corner of {@code shape}'s box at {@code origin}. */
    private static BlockPos corner(BlockPos origin, EquipmentShape shape) {
        return origin.offset(shape.sizeX() - 1, shape.sizeY() - 1, shape.sizeZ() - 1);
    }

    /**
     * Server, from {@link FluidWorldAuthority#loadedChunk}: a chunk of the structure the registry records at {@code origin}
     * loaded, and the structure is re-aligned to its record on a later tick ({@link #serveRealignment}).
     */
    public static void realignLater(ServerLevel level, BlockPos origin) {
        REALIGN.computeIfAbsent(level, k -> new LinkedHashMap<>()).putIfAbsent(origin.immutable(), origin.immutable());
    }

    /**
     * Serves {@code level}'s structures for this level tick ({@link RegistryRealignment#serve}): each is checked against its
     * record ({@link #realign}) until {@code deadline} ({@link System#nanoTime}) or {@code limit} checks, and none after a
     * rebuild. A structure waits while the layout refresh has it queued (the refresh comes first, plan section 8) and while
     * a chunk of its region, or of the blocks beside it, is not block-ticking. One whose record is gone, or a chunk of
     * whose box is not loaded, leaves the queue: its next chunk load queues it again. {@code done} takes each check.
     */
    static void serveRealignment(ServerLevel level, FluidWorldAuthority world, long deadline, int limit, Consumer<RegistryRealignment.Check> done) {
        var queue = REALIGN.get(level);
        if (queue == null || queue.isEmpty()) return;
        var stale = STALE.get(level);
        int checked = 0;
        for (var it = queue.entrySet().iterator(); it.hasNext() && checked < limit && System.nanoTime() - deadline < 0; ) {
            var entry = it.next();
            var origin = entry.getKey();
            var record = world.structure(position(level, origin));
            if (record == null) {
                it.remove();
                continue;
            }
            var box = corner(origin, record.shape());
            if (!loaded(level, origin, box)) {
                it.remove();
                continue;
            }
            var high = max(box, entry.getValue());
            if (stale != null && stale.containsKey(origin) || !ready(level, origin.offset(-1, 0, -1), high.offset(1, 0, 1))) continue;
            checked++;
            Realignment result;
            try {
                result = realign(level, world, new Vessel(origin, record.shape()), record.paint(), record.name(), high);
            } catch (RuntimeException failed) {
                it.remove();
                IChemE.LOGGER.error("fluid_realign failed structure={}", origin.toShortString(), failed);
                continue;
            }
            if (result.wider() != null) {
                entry.setValue(result.wider());
                continue;
            }
            it.remove();
            done.accept(result.check());
            if (result.check().outcome() != RegistryRealignment.Outcome.NOTHING) return;
        }
    }

    /** Every chunk of the region is loaded. */
    private static boolean loaded(ServerLevel level, BlockPos low, BlockPos high) {
        for (int x = low.getX() >> 4; x <= high.getX() >> 4; x++)
            for (int z = low.getZ() >> 4; z <= high.getZ() >> 4; z++) if (!level.hasChunk(x, z)) return false;
        return true;
    }

    /** One structure's re-alignment: what it did, or the far corner of the wider region it reads first. */
    private record Realignment(@Nullable RegistryRealignment.Check check, @Nullable BlockPos wider) {
    }

    /**
     * Registry first for {@code vessel}, the structure the registry records at its origin, painted {@code paint} and named
     * {@code name} (batch
     * 2026-11-03-save-corruption, REGISTRY_FIRST_PLAN.md 4.4). A structure whose layout cells all stand as its own is left
     * as it is, and its registrations held before are released. Any other is rebuilt to its record where it stands (shift
     * 0: the registry holds the origin). Cells of its origin and another shape (a structure built there after the
     * registry's last save) are its own to clear or rebind, as are interior cells no shell cell reaches; every shell cell
     * takes the record's shape and paint, and its registrations bind as at a load ({@link #restoreFluidBinding}). A cell
     * another block stands in is left out, never overwritten, and the structure's registrations are held (decision S11)
     * until a later load finds it whole. {@code high} is the far corner of the region read: the record's box, or wider
     * where cells of its origin reach further.
     */
    private static Realignment realign(ServerLevel level, FluidWorldAuthority world, Vessel vessel, Paint paint, String name, BlockPos high) {
        var origin = vessel.origin();
        var shape = vessel.shape();
        if (stands(level, vessel)) {
            boolean released = false;
            for (long id : members(world, level, vessel)) released |= world.hold(id, false);
            released |= holdShaft(world, level, vessel, false);
            return new Realignment(new RegistryRealignment.Check(RegistryRealignment.Outcome.NOTHING, released), null);
        }
        var survey = survey(level, origin, origin, high, shell -> shell.shape() != null && origin.equals(shell.origin()), true);
        var far = max(high, survey.far());
        if (!far.equals(high)) return new Realignment(null, far);
        com.wormzjl.icheme.runtime.assembly.AssemblyService.removed(level, vessel);
        var layout = ShapeRasterizer.layout(shape);
        var plan = EquipmentRefresh.plan(layout, survey.world(), new ShapeRasterizer.Cell(0, 0, 0));
        var touched = write(level, vessel, paint, name, plan, true);
        waitForLight(level, origin, high);
        // A shell cell that is no node of the record's shape stands for no registration.
        var nodes = new HashSet<>(nodes(vessel));
        for (var cell : layout.shell()) {
            var at = origin.offset(cell.x(), cell.y(), cell.z());
            if (!nodes.contains(at) && level.getBlockEntity(at) instanceof EquipmentShellBlockEntity shell && shell.fluidIdentity() != 0) shell.releaseIdentity();
        }
        restoreFluidBinding(level, vessel);
        boolean blocked = !plan.blocked().isEmpty(), changed = false;
        for (long id : members(world, level, vessel)) changed |= world.hold(id, blocked);
        changed |= holdShaft(world, level, vessel, blocked);
        settle(level, vessel, touched);
        IChemE.LOGGER.warn("equipment_realign kind={} origin={} cleared={} placed={} blocked={} detail=rebuilt from the registry's record", shape.kind().name().toLowerCase(Locale.ROOT),
                origin.toShortString(), plan.clear().size(), plan.shell().size() + plan.interior().size(), plan.blocked().size());
        return new Realignment(new RegistryRealignment.Check(blocked ? RegistryRealignment.Outcome.HELD : RegistryRealignment.Outcome.PLACED, !blocked && changed), null);
    }

    /** Whether every cell of {@code vessel}'s layout stands as its own: a shell cell of its origin and shape, an interior cell. Stamps are the layout refresh's. */
    private static boolean stands(ServerLevel level, Vessel vessel) {
        var layout = ShapeRasterizer.layout(vessel.shape());
        for (var cell : layout.shell())
            if (!(level.getBlockEntity(vessel.origin().offset(cell.x(), cell.y(), cell.z())) instanceof EquipmentShellBlockEntity shell) || !ours(shell, vessel.origin(), vessel.shape()))
                return false;
        var interior = ModBlocks.EQUIPMENT_INTERIOR.get();
        for (var cell : layout.interior())
            if (!level.getBlockState(vessel.origin().offset(cell.x(), cell.y(), cell.z())).is(interior)) return false;
        return true;
    }

    /** The registrations of {@code vessel}: those at its nodes, and a heat unit's partner sides. */
    /**
     * Holds or releases the shaft part of {@code vessel}'s structure with its registrations (W5c C1, option Q1): a held
     * motor, generator, gearbox, turbine or casing joins no train. Whether that changes it.
     */
    private static boolean holdShaft(FluidWorldAuthority world, ServerLevel level, Vessel vessel, boolean on) {
        if (ShaftParts.kind(vessel.shape().kind()) == null) return false;
        var o = vessel.origin();
        var u = world.shafts().unitAt(new com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Position(level.dimension().location().toString(), o.getX(), o.getY(), o.getZ()));
        if (u == null || u.held() == on) return false;
        world.shafts().queue(new com.wormzjl.icheme.runtime.fluid.ShaftTrains.Hold(u.id(), on));
        return true;
    }

    private static List<Long> members(FluidWorldAuthority world, ServerLevel level, Vessel vessel) {
        if (!vessel.shape().fluidDevice()) return List.of();
        var ids = heldAtNodes(world, level, vessel);
        if (HeatSides.unit(vessel.shape().kind()) == null) return ids;
        return unitRecords(world, ids).values().stream().map(r -> r.device().id()).toList();
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
        if (vessel.shape().kind() == EquipmentShape.Kind.BATCH_STILL) {
            FluidWorldAuthority.find(level.getServer()).ifPresent(world -> restoreStill(level, vessel, world, heldIds));
            return;
        }
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
                vessel.shape().kind().mover() || vessel.shape().kind() == EquipmentShape.Kind.FLARE_STACK || vessel.shape().kind().chimney() ? spec : new FluidDeviceSpec(vessel.shape().volume(), spec.temperature(), spec.pressure(), spec.composition(), spec.solids(), vessel.shape().levelHeight(), open(vessel), vessel.shape().fluidGeometry(),spec.tower()));
        if (oldNode != null && !oldNode.equals(node) && level.getBlockEntity(oldNode) instanceof EquipmentShellBlockEntity old && old.fluidIdentity() == device.id()) old.releaseIdentity();
        if (level.getBlockEntity(node) instanceof EquipmentShellBlockEntity entity && entity.fluidIdentity() != device.id()) entity.bindIdentity(device.id());
        ShaftParts.structure(level, vessel.origin(), vessel.shape(), device.id());
    }

    /** A steam ejector's control (batch 2026-11-10-steam-ejector-flow): its curve from its suction bore, no motive until its first slice. */
    static com.wormzjl.icheme.science.fluid.network.FlowControl.Ejector ejectorControl(EquipmentShape shape) {
        return new com.wormzjl.icheme.science.fluid.network.FlowControl.Ejector(new com.wormzjl.icheme.science.machine.SteamEjector(shape.diameter()));
    }

    /** Whether a shape registers as a steam turbine (W3, reading A37). */
    private static boolean turbine(EquipmentShape shape) {
        return shape.kind() == EquipmentShape.Kind.STEAM_TURBINE;
    }

    /**
     * A shaft-driven machine's control from its shape's design point (W3, readings A3 and A32): a pump on its curve at its
     * duty, with Corripio's efficiency; a centrifugal compressor on its curve at its rating, efficiency 0.78; a reciprocating
     * frame at its displacement; a steam turbine at its rated power and its size's efficiency. A machine's speed is its train's.
     */
    static com.wormzjl.icheme.science.fluid.network.FlowControl machineControl(EquipmentShape shape) {
        return switch (shape.kind()) {
            case PUMP -> {
                var r = UnitDesign.pumpRating(shape);
                yield new com.wormzjl.icheme.science.fluid.network.FlowControl.Machine(new com.wormzjl.icheme.science.machine.CentrifugalPump(r[0], r[1],
                        com.wormzjl.icheme.science.machine.CentrifugalPump.bestEfficiency(r[0]), UnitDesign.designSpeed(shape)), 0, true);
            }
            case CENTRIFUGAL_COMPRESSOR -> {
                var r = UnitDesign.compressorRating(shape);
                yield new com.wormzjl.icheme.science.fluid.network.FlowControl.Machine(new com.wormzjl.icheme.science.machine.CentrifugalCompressor(r[0], r[1], .78), 0, true);
            }
            case COMPRESSOR -> new com.wormzjl.icheme.science.fluid.network.FlowControl.Compressor(frameDisplacement(shape), 3, true);
            // A vacuum pump casing (batch 2026-11-10-vacuum-pump): its model's curve, on the machine row at its train's speed.
            case LIQUID_RING_PUMP, DRY_SCREW_PUMP -> new com.wormzjl.icheme.science.fluid.network.FlowControl.Machine(UnitDesign.vacuumPump(shape), 0, true);
            case STEAM_TURBINE -> new com.wormzjl.icheme.science.fluid.network.FlowControl.Turbine(com.wormzjl.icheme.science.machine.SteamTurbine.rated(Math.max(1e3, UnitDesign.ratedPower(shape))));
            default -> throw new IllegalArgumentException("Not a shaft-driven machine: " + shape.kind());
        };
    }

    private static double frameDisplacement(EquipmentShape shape) {
        return Math.max(1e-4, Math.min(com.wormzjl.icheme.science.fluid.network.FlowControl.Mover.MAXIMUM_VOLUME_FLOW, UnitDesign.displacement(shape)));
    }

    /**
     * The control a casing saved before W3 takes at its restore (reading A29), or null when it already has its own: a pump's
     * or centrifugal compressor's set-flow control becomes its machine, its set flow kept as the machine's; a frame takes
     * its displacement, keeping its pressure ratio and switch.
     */
    static @Nullable com.wormzjl.icheme.science.fluid.network.FlowControl restoredControl(EquipmentShape shape, com.wormzjl.icheme.science.fluid.network.FlowControl control) {
        return switch (shape.kind()) {
            case PUMP, CENTRIFUGAL_COMPRESSOR -> {
                if (!(control instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.Pump) && !(control instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.Compressor)) yield null;
                var mover = (com.wormzjl.icheme.science.fluid.network.FlowControl.Mover) control;
                var machine = (com.wormzjl.icheme.science.fluid.network.FlowControl.Machine) machineControl(shape);
                double set = Math.max(0, Math.min(com.wormzjl.icheme.science.fluid.network.FlowControl.Mover.MAXIMUM_VOLUME_FLOW, mover.requestedVolumeFlow()));
                yield new com.wormzjl.icheme.science.fluid.network.FlowControl.Machine(machine.law(), 0, mover.enabled(), set, 0);
            }
            case COMPRESSOR -> control instanceof com.wormzjl.icheme.science.fluid.network.FlowControl.Compressor c && c.requestedVolumeFlow() != frameDisplacement(shape)
                    ? new com.wormzjl.icheme.science.fluid.network.FlowControl.Compressor(frameDisplacement(shape), c.maximumPressureRatio(), c.enabled()) : null;
            default -> null;
        };
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
            at.then(Commands.literal(kind.name().toLowerCase(Locale.ROOT)).then(Commands.argument("size", DoubleArgumentType.doubleArg(ProcessUnit.SUCTION_STEP, ProcessUnit.maxDiameter(kind)))
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
