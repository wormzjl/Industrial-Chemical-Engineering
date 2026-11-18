package com.wormzjl.icheme.testworld;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.assembly.AssemblyService;
import com.wormzjl.icheme.runtime.assembly.AssemblyStore;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.science.assembly.AssemblyReport.State;
import com.wormzjl.icheme.science.assembly.ColumnBlueprint;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.ColumnTrays;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.runtime.fluid.HeatUnit;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.ProcessUnit;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.EquipmentStructure.Vessel;
import com.wormzjl.icheme.world.level.block.FluidDeviceBlock;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import org.jetbrains.annotations.Nullable;
import java.util.*;
import com.mojang.authlib.GameProfile;
import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModItems;
import com.wormzjl.icheme.runtime.control.ColumnCommands;
import com.wormzjl.icheme.runtime.control.ControlSettings;
import com.wormzjl.icheme.runtime.control.DataBus;
import com.wormzjl.icheme.runtime.control.DataPort;
import com.wormzjl.icheme.runtime.fluid.EquipmentAlarm;
import com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec;
import com.wormzjl.icheme.runtime.instrument.Instrument;
import com.wormzjl.icheme.runtime.instrument.InstrumentHosts;
import com.wormzjl.icheme.runtime.instrument.Sensor;
import com.wormzjl.icheme.science.plant.column.ColumnSolve;
import com.wormzjl.icheme.science.plant.column.Condition;
import com.wormzjl.icheme.science.plant.column.DriftBands;
import com.wormzjl.icheme.science.plant.column.OperationView;
import com.wormzjl.icheme.science.plant.column.Port;
import com.wormzjl.icheme.science.plant.column.Setpoints;
import com.wormzjl.icheme.science.plant.column.SplitTable;
import com.wormzjl.icheme.science.column.v3.V3ColumnCalculator;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.column.v3.V3ColumnSpecification;
import com.wormzjl.icheme.science.equipment.CableMedium;
import com.wormzjl.icheme.science.equipment.ColumnOperatingPoint;
import com.wormzjl.icheme.science.equipment.ConduitGeometry;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.PipeSizes;
import com.wormzjl.icheme.science.fluid.thermo.FluidThermodynamics;
import com.wormzjl.icheme.science.material.MaterialCatalog;
import com.wormzjl.icheme.science.material.MaterialRuntime;
import com.wormzjl.icheme.world.inventory.ControlPanelMenu;
import com.wormzjl.icheme.world.level.block.ControlPanelBlock;
import com.wormzjl.icheme.world.level.block.entity.ConduitBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.ControlPanelBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.CreativePowerBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import com.wormzjl.icheme.science.equipment.design.UnitRatings;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * A plant builder in a world (used by the column test world and the review GameTests): places structures, lays lines,
 * installs valves and drives. Moved from the GameTests' {@code AssemblyGameTests.Plant} (owner, 2026-11-17: the column
 * test world is a world type of the mod).
 *
 * <p>Its first use: a small column plant: a 2 m column of 8 trays with sump steam and no kettle (an exchanger condenser, a standard
 * reflux pump), its condenser, its receiver and its reflux pump, the lines between them routed so that no pipe touches
 * another line, and a valve and a tank on every boundary line. The review world builds a larger plant with the same
 * tools ({@link ColumnReviewWorld}).
 */
public final class PlantBuilder {
    public final ServerLevel level;
    public final FluidWorldAuthority world;
    public final BlockPos base, low, high;
    public final List<Vessel> structures = new ArrayList<>();
    public final LinkedHashSet<BlockPos> devices = new LinkedHashSet<>();
    public final Set<BlockPos> reserved = new HashSet<>();
    public final Map<BlockPos, Direction> valves = new LinkedHashMap<>();
    /**
     * Each bend a route makes costs as many pipes as this: 0, the shortest line of any shape (the plants' own); above 0, the
     * line with the fewest bends among the short ones, so a line runs straight where it can.
     */
    public int bendCost;
    public Vessel column, drum;
    public BlockPos offgasEnd;
    /** An exchanger plant's bypass valve and the bypass pipe before it (W8). */
    public BlockPos bypassValve, bypassPipe;



    /**
     * A plant in {@code level} outside a GameTest (the column test world, {@link ColumnTestWorld}): the same plant from
     * {@code base} in the same box; nothing of the plant reads a GameTest's helper.
     */
    public PlantBuilder(ServerLevel level, BlockPos base, BlockPos below, BlockPos above) {
        this.level = level;
        world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
        this.base = base;
        low = base.offset(below);
        high = base.offset(above);
    }

    /** Holds the plant's chunks loaded while it is built and checked. */
    public void hold() {
        for (var chunk : chunks()) {
            level.setChunkForced(chunk.x, chunk.z, true);
            level.getChunk(chunk.x, chunk.z);
        }
    }

    public static EquipmentShape unit(EquipmentShape.Kind kind) {
        var d = UnitDesign.of(kind);
        double size = d.minSize();
        int length = d.defaultLength(size);
        return EquipmentShape.designed(kind, size, length, EquipmentShape.Heading.NORTH, d.defaultSupport(size, length), ProcessUnit.Bank.ONE);
    }

    /** Builds the plant; {@code offgasTank}: the off-gas line ends at a tank, else open two blocks beyond its valve. */
    public void build(boolean offgasTank) {
        // The plant reaches beyond the test's structure: its chunks are held loaded until it is removed.
        hold();
        var components = new ColumnComponents(ColumnComponents.Condenser.EXCHANGER, ColumnComponents.Pump.STANDARD, List.of(), false, false);
        var trays = new ColumnTrays(8, 4, List.of(), List.of(9), List.of(), components);
        column = place(EquipmentShape.column(2, trays, EquipmentShape.Heading.NORTH, EquipmentShape.minimumSkirt(2)), 0, 0, 0);
        var exchanger = place(unit(EquipmentShape.Kind.EXCHANGER), 10, 9, 2);
        drum = place(EquipmentShape.drum(1.5, 4, EquipmentShape.Orientation.HORIZONTAL, EquipmentShape.Heading.NORTH, 2,
                EquipmentShape.defaultConnections(1.5, 4, EquipmentShape.Orientation.HORIZONTAL, 2, true), true), 14, 0, -2);
        var pump = place(unit(EquipmentShape.Kind.PUMP), 20, 0, -8);
        // The boundary lines first, straight out of their nozzles: the feed and steam valves point at the column.
        outside(column, Role.FEED, false, true);
        outside(column, Role.STEAM, false, true);
        outside(column, Role.BOTTOMS, true, true);
        offgasEnd = outside(drum, Role.VAPOUR_OUTLET, true, offgasTank);
        outside(drum, Role.BOOT_DRAIN, true, true);
        route(front(column, Role.VAPOUR_OUTLET), front(exchanger, Role.SHELL_INLET));
        route(front(exchanger, Role.SHELL_OUTLET), front(drum, Role.SIDE));
        route(front(drum, Role.LIQUID_OUTLET), front(pump, Role.SUCTION));
        tee(route(front(pump, Role.DISCHARGE), front(column, Role.REFLUX)));
        for (var valve : valves.entrySet()) install(valve.getKey(), valve.getValue());
        // A casing turns only with its shaft train (W3): a creative motor drives the reflux pump.
        drive(pump);
    }

    /**
     * A creative motor on a pump's coupling (batch 2026-11-03-rotating-machinery W3): a shaft block beyond the casing's
     * driven end, the motor beyond it facing back along the shaft. Both are the plant's, removed with it. Returns the
     * shaft block's position.
     */
    public BlockPos drive(Vessel vessel) {
        for (var s : ShapeRasterizer.shafts(vessel.shape())) {
            if (s.role() != EquipmentShape.ShaftEnd.Role.DRIVEN_INPUT) continue;
            var d = Direction.fromDelta(s.dx(), s.dy(), s.dz());
            var shaft = vessel.origin().offset(s.block().x(), s.block().y(), s.block().z());
            var motor = shaft.relative(d);
            if (!level.getBlockState(shaft).canBeReplaced() || !level.getBlockState(motor).canBeReplaced() || d.getAxis() == Direction.Axis.Y)
                throw new IllegalStateException("No room for the " + vessel.shape().kind() + "'s creative motor at " + shaft.toShortString());
            level.setBlock(shaft, ModBlocks.SHAFT.get().defaultBlockState().setValue(com.wormzjl.icheme.world.level.block.ShaftBlock.AXIS, d.getAxis()), Block.UPDATE_ALL);
            level.setBlock(motor, ModBlocks.CREATIVE_MOTOR.get().defaultBlockState().setValue(com.wormzjl.icheme.world.level.block.CreativeMotorBlock.FACING, d.getOpposite()), Block.UPDATE_ALL);
            devices.add(shaft.immutable());
            devices.add(motor.immutable());
            return shaft.immutable();
        }
        throw new IllegalStateException("No driven coupling on the " + vessel.shape().kind());
    }

    /**
     * A motor standing beyond a shaft block on {@code pump}'s driven coupling, turned so its driving end faces the shaft,
     * rated {@code kilowatts} at {@code poles}: the plant's, removed with it.
     */
    public Vessel motor(Vessel pump, double kilowatts, int poles) {
        return motor(pump, kilowatts, poles, 0);
    }

    /** {@link #motor(Vessel, double, int)} at base speed {@code baseSpeed} rpm (0: its poles' speed). */
    public Vessel motor(Vessel pump, double kilowatts, int poles, double baseSpeed) {
        for (var s : ShapeRasterizer.shafts(pump.shape())) {
            if (s.role() != EquipmentShape.ShaftEnd.Role.DRIVEN_INPUT) continue;
            var d = Direction.fromDelta(s.dx(), s.dy(), s.dz());
            var shaft = pump.origin().offset(s.block().x(), s.block().y(), s.block().z());
            var end = shaft.relative(d);
            var design = UnitDesign.of(EquipmentShape.Kind.MOTOR);
            double size = design.snap(kilowatts);
            int length = design.defaultLength(size);
            for (var heading : EquipmentShape.Heading.values()) {
                var shape = EquipmentShape.designed(EquipmentShape.Kind.MOTOR, size, length, heading, 0, ProcessUnit.Bank.ONE)
                        .withDuty(com.wormzjl.icheme.science.equipment.MachineDuty.motor(size, poles, com.wormzjl.icheme.science.equipment.MachineDuty.Drive.FIXED).withBaseSpeed(baseSpeed));
                for (var m : ShapeRasterizer.shafts(shape)) {
                    if (m.role() != EquipmentShape.ShaftEnd.Role.DRIVER_OUTPUT || m.dx() != -s.dx() || m.dy() != -s.dy() || m.dz() != -s.dz()) continue;
                    var origin = end.offset(-m.cell().x(), -m.cell().y(), -m.cell().z());
                    boolean clear = level.getBlockState(shaft).canBeReplaced();
                    for (var cell : ShapeRasterizer.layout(shape).shell()) clear &= level.getBlockState(origin.offset(cell.x(), cell.y(), cell.z())).canBeReplaced();
                    if (!clear) continue;
                    level.setBlock(shaft, ModBlocks.SHAFT.get().defaultBlockState().setValue(com.wormzjl.icheme.world.level.block.ShaftBlock.AXIS, d.getAxis()), Block.UPDATE_ALL);
                    devices.add(shaft.immutable());
                    return place(shape, origin.getX() - base.getX(), origin.getY() - base.getY(), origin.getZ() - base.getZ());
                }
            }
            throw new IllegalStateException("No room for a motor on the pump's coupling at " + shaft.toShortString());
        }
        throw new IllegalStateException("No driven coupling on the " + pump.shape().kind());
    }

    /**
     * A motor for {@code pump} as {@link #motor(Vessel, double, int)}: twice the pump's design shaft power with the design
     * spare, so its run-out never overloads it, at the pump's poles and at its design speed (a pump built slower than its poles'
     * speed, {@code PumpSizing.builtSpeed}, gets a motor of that base speed: the train turns both at one speed).
     */
    public Vessel motor(Vessel pump) {
        var duty = pump.shape().duty();
        double shaft = 1000 * 9.81 * duty.flow() / 3600 * duty.head() / .6;
        return motor(pump, com.wormzjl.icheme.science.machine.Motor.ratingFor(2 * shaft) / 1000, duty.poles() == 0 ? 2 : duty.poles(), duty.baseSpeed());
    }

    /**
     * An exchanger with a bypass around its shell side (batch 2026-10-27-heat-exchanger-design, W8): a line routed from
     * the shell inlet's pipe block to the outlet's, with a control valve on it along its stream, then the feed and the
     * product branching off those two blocks (each a tee) to tanks. The exchanger is the master.
     */
    public void buildBypass() {
        hold();
        var exchanger = place(unit(EquipmentShape.Kind.EXCHANGER), 0, 4, 0);
        column = exchanger;
        buildBypass(exchanger, Role.SHELL_INLET, Role.SHELL_OUTLET);
        for (var valve : valves.entrySet()) install(valve.getKey(), valve.getValue());
    }

    /** A bypass around {@code vessel}'s side from {@code in} to {@code out}, its feed and product to tanks; valves not yet installed. */
    public void buildBypass(Vessel vessel, Role in, Role out) {
        var tank = ModBlocks.FLUID_RESERVOIR.get().defaultBlockState();
        buildBypass(vessel, in, out, tank, tank);
    }

    /**
     * {@link #buildBypass(Vessel, Role, Role)} with the feed's end block {@code feedEnd} and the product's
     * {@code productEnd}; returns the two ends' positions, feed first.
     */
    public BlockPos[] buildBypass(Vessel vessel, Role in, Role out, net.minecraft.world.level.block.state.BlockState feedEnd,
                           net.minecraft.world.level.block.state.BlockState productEnd) {
        var path = route(front(vessel, in), front(vessel, out));
        for (int i = 2; i < path.size() - 2 && bypassValve == null; i++) {
            if (!straight(path, i)) continue;
            bypassValve = path.get(i);
            bypassPipe = path.get(i - 1);
            var next = path.get(i + 1);
            valves.put(bypassValve, Direction.fromDelta(next.getX() - bypassValve.getX(), next.getY() - bypassValve.getY(), next.getZ() - bypassValve.getZ()));
        }
        if (bypassValve == null) throw new IllegalStateException("No straight run for the bypass valve");
        return new BlockPos[]{branch(front(vessel, in), out(vessel, in), false, feedEnd), branch(front(vessel, out), out(vessel, out), true, productEnd)};
    }

    /**
     * A boundary line off the pipe block {@code at}, the nozzle's own direction {@code d} first, else square to it: three
     * pipes, a valve on the second (along the line, or back at {@code at}), and {@code end} (a tank, a generator, a void).
     * Returns the end block's position.
     */
    public BlockPos branch(BlockPos at, Direction d, boolean outward, net.minecraft.world.level.block.state.BlockState end) {
        var tries = new ArrayList<Direction>(List.of(d));
        for (var q : Direction.values()) if (q.getAxis() != d.getAxis()) tries.add(q);
        for (var q : tries) {
            boolean clear = true;
            for (int k = 1; k <= 4 && clear; k++) clear = free(at.relative(q, k), around(at.relative(q, k - 1), at.relative(q, k + 1)));
            if (!clear) continue;
            for (int k = 1; k <= 3; k++) pipe(at.relative(q, k));
            level.setBlock(at.relative(q, 4), end, Block.UPDATE_ALL);
            devices.add(at.relative(q, 4));
            valves.put(at.relative(q, 2), outward ? q : q.getOpposite());
            return at.relative(q, 4);
        }
        throw new IllegalStateException("No room for a line off " + at.toShortString());
    }

    /** The registration of the master exchanger's {@code side}. */
    public long side(HeatSides.Side side) {
        var ids = EquipmentStructure.sideIdentities(level, column);
        var sides = HeatSides.of(column.shape());
        for (int i = 0; i < sides.size(); i++) if (sides.get(i).side() == side) return ids[i];
        throw new IllegalStateException("No " + side + " side");
    }

    public Vessel place(EquipmentShape shape, int dx, int dy, int dz) {
        var origin = base.offset(dx, dy, dz);
        EquipmentStructure.place(level, origin, shape, Paint.DEFAULT, Integer.MAX_VALUE);
        var vessel = new Vessel(origin, shape);
        structures.add(vessel);
        for (var p : ShapeRasterizer.ports(shape)) reserved.add(origin.offset(p.cell().x() + p.dx(), p.cell().y() + p.dy(), p.cell().z() + p.dz()));
        return vessel;
    }

    public static ShapeRasterizer.Port port(Vessel vessel, Role role) {
        for (var p : ShapeRasterizer.ports(vessel.shape())) if (vessel.shape().nozzles().get(p.nozzle()).role() == role) return p;
        throw new IllegalStateException("No " + role + " on the " + vessel.shape().kind());
    }

    public static BlockPos front(Vessel vessel, Role role) {
        return front(vessel, port(vessel, role));
    }

    /** The pipe block in front of {@code p} of {@code vessel} ({@link com.wormzjl.icheme.science.equipment.NozzlePorts#front}). */
    public static BlockPos front(Vessel vessel, ShapeRasterizer.Port p) {
        var c = com.wormzjl.icheme.science.equipment.NozzlePorts.front(p);
        return vessel.origin().offset(c.x(), c.y(), c.z());
    }

    public static Direction out(Vessel vessel, Role role) {
        var p = port(vessel, role);
        return Direction.fromDelta(p.dx(), p.dy(), p.dz());
    }

    /** {@link #joined(Vessel, ShapeRasterizer.Port)} for {@code vessel}'s first {@code role} nozzle. */
    public @Nullable String joined(Vessel vessel, Role role) {
        return joined(vessel, port(vessel, role));
    }

    /**
     * Null when the pipe in front of {@code port} of {@code vessel} meets the nozzle as the player sees it (batch
     * 2026-11-16-review-plant-sizing, NOZZLE_JOIN_REVIEW.md): its block state is joined towards the nozzle, its face
     * there is flanged, and the nozzle is drawn at the pipe's size, not at its default. Otherwise what is wrong, naming
     * the vessel, the nozzle's role and the block.
     */
    public @Nullable String joined(Vessel vessel, ShapeRasterizer.Port port) {
        return joined(level, vessel, port);
    }

    /** {@link #joined(Vessel, ShapeRasterizer.Port)} in {@code level}. */
    public static @Nullable String joined(ServerLevel level, Vessel vessel, ShapeRasterizer.Port port) {
        var role = vessel.shape().nozzles().get(port.nozzle()).role();
        var flange = vessel.origin().offset(port.cell().x(), port.cell().y(), port.cell().z());
        var front = front(vessel, port);
        var towards = Direction.fromDelta(-port.dx(), -port.dy(), -port.dz());
        String name = "the " + vessel.shape().kind() + " at " + vessel.origin().toShortString() + ", nozzle " + role + " (" + port.nozzle() + "), pipe at " + front.toShortString();
        var state = level.getBlockState(front);
        if (!(state.getBlock() instanceof FluidDeviceBlock) || !(level.getBlockEntity(front) instanceof com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity pipe))
            return name + ": no pipe there (" + state + ")";
        if (!state.getValue(FluidDeviceBlock.connection(towards))) return name + ": its block state is not joined " + towards;
        var drawn = pipe.getModelData().get(com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity.DRAWN);
        if (drawn == null || !drawn.flanged(towards)) return name + ": its face " + towards + " is not flanged";
        if (!(level.getBlockEntity(flange) instanceof EquipmentShellBlockEntity piece) || !vessel.origin().equals(piece.origin()))
            return name + ": no shell cell of it at " + flange.toShortString();
        int code = ShapeRasterizer.nozzleCode(vessel.shape(), piece.cell(), port.nozzle(), piece.nozzles());
        int pipeCode = com.wormzjl.icheme.science.equipment.PipeSizes.code(com.wormzjl.icheme.science.equipment.PipeSizes.outside(pipe.bore()));
        if (code != pipeCode) return name + ": the nozzle is drawn at size code " + code + (code == 0 ? " (its default)" : "") + ", the pipe's is " + pipeCode;
        return null;
    }

    /** The size code {@code port} of {@code vessel} is drawn at: 0 for its default, under a blind flange. */
    public static int nozzleCode(ServerLevel level, Vessel vessel, ShapeRasterizer.Port port) {
        var flange = vessel.origin().offset(port.cell().x(), port.cell().y(), port.cell().z());
        if (!(level.getBlockEntity(flange) instanceof EquipmentShellBlockEntity piece)) return -1;
        return ShapeRasterizer.nozzleCode(vessel.shape(), piece.cell(), port.nozzle(), piece.nozzles());
    }

    /**
     * Every nozzle of the plant's structures with a pipe of the plant in front of it that does not meet it ({@link
     * #joined(Vessel, ShapeRasterizer.Port)}), joined by "; ", or null when every one does: the plant's lines start and
     * end at nozzles only there, as {@link #free} keeps every other line off a nozzle's pipe block.
     */
    public @Nullable String unjoined() {
        var out = new StringBuilder();
        for (var vessel : structures)
            for (var p : ShapeRasterizer.ports(vessel.shape())) {
                if (!devices.contains(front(vessel, p))) continue;
                var wrong = joined(vessel, p);
                if (wrong != null) out.append(out.isEmpty() ? "" : "; ").append(wrong);
            }
        return out.isEmpty() ? null : out.toString();
    }

    public boolean inside(BlockPos at) {
        return at.getX() >= low.getX() && at.getX() <= high.getX() && at.getY() >= low.getY() && at.getY() <= high.getY() && at.getZ() >= low.getZ()
                && at.getZ() <= high.getZ();
    }

    /** Whether a pipe may stand at {@code at}: air, no other nozzle's pipe block, nothing it would join but {@code allowed}. */
    public boolean free(BlockPos at, Set<BlockPos> allowed) {
        if (!inside(at) || !level.getBlockState(at).isAir() || reserved.contains(at) && !allowed.contains(at) || devices.contains(at)) return false;
        for (var d : Direction.values()) {
            var n = at.relative(d);
            if (!allowed.contains(n) && (devices.contains(n) || reserved.contains(n))) return false;
        }
        return true;
    }

    /** The positions a new block may stand beside. */
    public static Set<BlockPos> around(BlockPos... positions) {
        return new HashSet<>(List.of(positions));
    }

    public void pipe(BlockPos at) {
        // As a player's placement draws it (FluidDeviceBlock.getStateForPlacement): joined where the network will join it
        // once registered, nozzles included. A bare default state would take its joints later, through its neighbours'
        // shape updates, which neither redraw its faces nor tell the nozzle beside it (NOZZLE_JOIN_REVIEW.md, the trace).
        var state = ModBlocks.FLUID_PIPE.get().defaultBlockState();
        var facing = PhysicalFluidTopology.Direction.valueOf(state.getValue(FluidDeviceBlock.FACING).name());
        level.setBlock(at, FluidDeviceBlock.withFaces(state, world.placedFaces(position(at), TopologyCompiler.Kind.PIPE, facing)), Block.UPDATE_ALL);
        devices.add(at.immutable());
    }

    /**
     * A boundary line straight out of a nozzle: three pipes, a valve on the second (its outlet along the line, or back
     * at the nozzle), then a tank, or nothing (an open end). Returns the line's last pipe.
     */
    public BlockPos outside(Vessel vessel, Role role, boolean outward, boolean tank) {
        var from = front(vessel, role);
        var d = out(vessel, role);
        for (int k = 0; k <= 3; k++) {
            var at = from.relative(d, k);
            if (!free(at, around(from, from.relative(d, k - 1), from.relative(d, k + 1)))) throw new IllegalStateException(role + " line blocked at " + at.toShortString());
        }
        for (int k = 0; k < 3; k++) pipe(from.relative(d, k));
        valves.put(from.relative(d), outward ? d : d.getOpposite());
        if (!tank) return from.relative(d, 2);
        level.setBlock(from.relative(d, 3), ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(), Block.UPDATE_ALL);
        devices.add(from.relative(d, 3));
        return from.relative(d, 2);
    }

    /** The shortest line of pipes from one nozzle's pipe block to another's that touches no other line. */
    public List<BlockPos> route(BlockPos from, BlockPos to) {
        return route(from, to, Set.of());
    }

    /** {@link #route(BlockPos, BlockPos)}, its ends also allowed beside the pipes {@code joins}: a line that ends on another line. */
    public List<BlockPos> route(BlockPos from, BlockPos to, Set<BlockPos> joins) {
        var allowed = new HashSet<BlockPos>(joins);
        allowed.add(from);
        allowed.add(to);
        if (bendCost > 0) return straightRoute(from, to, allowed);
        var previous = new HashMap<BlockPos, BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        previous.put(from, from);
        queue.add(from);
        while (!queue.isEmpty() && !previous.containsKey(to)) {
            var at = queue.poll();
            for (var d : Direction.values()) {
                var n = at.relative(d);
                if (previous.containsKey(n) || !free(n, allowed)) continue;
                previous.put(n, at);
                queue.add(n);
            }
        }
        if (!previous.containsKey(to)) throw new IllegalStateException("No route from " + from.toShortString() + " to " + to.toShortString());
        var path = new ArrayList<BlockPos>();
        for (var at = to; ; at = previous.get(at)) {
            path.add(at);
            if (at.equals(from)) break;
        }
        Collections.reverse(path);
        for (var at : path) pipe(at);
        return path;
    }

    /**
     * {@link #route(BlockPos, BlockPos, Set)} by least cost, a pipe 1 and a bend {@link #bendCost}: a search over (block,
     * heading), the first pipe off {@code from} free to leave any way.
     */
    private List<BlockPos> straightRoute(BlockPos from, BlockPos to, Set<BlockPos> allowed) {
        var dirs = Direction.values();
        record State(BlockPos at, int heading) {}
        record Node(State state, int cost) {}
        var best = new HashMap<State, Integer>();
        var previous = new HashMap<State, State>();
        var queue = new PriorityQueue<Node>(Comparator.comparingInt(Node::cost));
        var first = new State(from, -1);
        best.put(first, 0);
        queue.add(new Node(first, 0));
        State end = null;
        while (!queue.isEmpty()) {
            var node = queue.poll();
            var s = node.state();
            if (best.getOrDefault(s, Integer.MAX_VALUE) < node.cost()) continue;
            if (s.at().equals(to)) {
                end = s;
                break;
            }
            for (int d = 0; d < 6; d++) {
                var n = s.at().relative(dirs[d]);
                if (!free(n, allowed)) continue;
                int cost = node.cost() + 1 + (s.heading() < 0 || s.heading() == d ? 0 : bendCost);
                var next = new State(n, d);
                if (cost < best.getOrDefault(next, Integer.MAX_VALUE)) {
                    best.put(next, cost);
                    previous.put(next, s);
                    queue.add(new Node(next, cost));
                }
            }
        }
        if (end == null) throw new IllegalStateException("No route from " + from.toShortString() + " to " + to.toShortString());
        var path = new ArrayList<BlockPos>();
        for (var s = end; ; s = previous.get(s)) {
            path.add(s.at());
            if (s.equals(first)) break;
        }
        Collections.reverse(path);
        for (var at : path) pipe(at);
        return path;
    }

    /**
     * A branch from the nozzle's pipe block {@code from} onto a straight pipe of {@code path}, from beside it (a tee):
     * the first straight pipe from the path's start, two pipes clear of each end, whose route is free. Returns the branch.
     */
    public List<BlockPos> join(List<BlockPos> path, BlockPos from) {
        for (int i = 2; i < path.size() - 2; i++) {
            if (!straight(path, i)) continue;
            var at = path.get(i);
            var along = path.get(i + 1).subtract(at);
            var axis = Direction.fromDelta(along.getX(), along.getY(), along.getZ()).getAxis();
            for (var q : Direction.values()) {
                if (q.getAxis() == axis) continue;
                var t = at.relative(q);
                if (!free(t, around(at))) continue;
                try {
                    return route(from, t, Set.of(at));
                } catch (IllegalStateException ignored) {
                }
            }
        }
        throw new IllegalStateException("No room to join " + from.toShortString() + " to the line from " + path.getFirst().toShortString());
    }

    public static boolean straight(List<BlockPos> path, int i) {
        var a = path.get(i).subtract(path.get(i - 1));
        var b = path.get(i + 1).subtract(path.get(i));
        return a.equals(b);
    }

    /** The reflux line's tee: a branch square to the line, a valve on it and a tank; the reflux valve after the tee. */
    public void tee(List<BlockPos> path) {
        for (int t = 2; t < path.size() - 3; t++) {
            var tee = path.get(t);
            var in = Direction.fromDelta(tee.getX() - path.get(t - 1).getX(), tee.getY() - path.get(t - 1).getY(), tee.getZ() - path.get(t - 1).getZ());
            var outDir = Direction.fromDelta(path.get(t + 1).getX() - tee.getX(), path.get(t + 1).getY() - tee.getY(), path.get(t + 1).getZ() - tee.getZ());
            int reflux = -1;
            for (int j = t + 2; j < path.size() - 1; j++) if (straight(path, j)) { reflux = j; break; }
            if (reflux < 0) continue;
            for (var q : Direction.values()) {
                if (q.getAxis() == in.getAxis() || q.getAxis() == outDir.getAxis()) continue;
                boolean clear = true;
                for (int k = 1; k <= 4 && clear; k++) clear = free(tee.relative(q, k), around(tee.relative(q, k - 1), tee.relative(q, k + 1)));
                if (!clear) continue;
                for (int k = 1; k <= 3; k++) pipe(tee.relative(q, k));
                level.setBlock(tee.relative(q, 4), ModBlocks.FLUID_RESERVOIR.get().defaultBlockState(), Block.UPDATE_ALL);
                devices.add(tee.relative(q, 4));
                valves.put(tee.relative(q, 2), q);
                var at = path.get(reflux);
                valves.put(at, Direction.fromDelta(path.get(reflux + 1).getX() - at.getX(), path.get(reflux + 1).getY() - at.getY(), path.get(reflux + 1).getZ() - at.getZ()));
                return;
            }
        }
        throw new IllegalStateException("No place for the reflux tee");
    }

    /** A control valve on the pipe at {@code at}, its outlet {@code outlet}, as the fitting item installs one. */
    public void install(BlockPos at, Direction outlet) {
        var state = level.getBlockState(at).setValue(FluidDeviceBlock.FACING, outlet).setValue(FluidDeviceBlock.PRESSURE_CONTROL, true);
        level.setBlock(at, FluidDeviceBlock.connectedState(state, level, at), Block.UPDATE_ALL);
        long id = world.at(position(at)).orElseThrow(() -> new IllegalStateException("No pipe at " + at.toShortString())).device().id();
        if (!world.installPipePressureValve(id, PhysicalFluidTopology.Direction.valueOf(outlet.name())))
            throw new IllegalStateException("The valve at " + at.toShortString() + " did not install");
    }

    public PhysicalFluidTopology.Position position(BlockPos at) {
        return new PhysicalFluidTopology.Position(level.dimension().location().toString(), at.getX(), at.getY(), at.getZ());
    }

    public boolean valvesInstalled() {
        for (var at : valves.keySet())
            if (!world.at(position(at)).map(r -> r.device().hydraulicKind() == TopologyCompiler.Kind.VALVE).orElse(false)) return false;
        return true;
    }

    public BlockPos controller() {
        return column.controller();
    }

    public @Nullable AssemblyStore.Entry entry() {
        return column == null ? null : AssemblyService.entry(level, column.origin());
    }

    /** The slots not in place, for a failure's message. */
    public String faults() {
        var entry = entry();
        if (entry == null || entry.report() == null) return "no report: " + entry;
        var out = new StringBuilder();
        for (var s : entry.report().slots())
            if (s.state() != State.OK) out.append(s.slot().part()).append(s.slot().index()).append('=').append(s.state()).append(' ').append(s.detail()).append("; ");
        return out.toString();
    }

    /** Removes every pipe, tank and structure the plant placed. */
    public void remove() {
        for (var at : devices) level.removeBlock(at, false);
        for (var vessel : structures) removeStructure(level, vessel.controller());
    }

    /**
     * Removes the block at {@code position}; a constructed equipment cell through the real creative-player path (a fake
     * creative player breaks it), never by bypassing its protection.
     */
    public static void removeStructure(ServerLevel level, BlockPos position) {
        var state = level.getBlockState(position);
        if (!com.wormzjl.icheme.world.level.block.EquipmentProtection.cell(state)) {
            level.removeBlock(position, false);
            return;
        }
        var player = net.neoforged.neoforge.common.util.FakePlayerFactory.get(level, new com.mojang.authlib.GameProfile(new java.util.UUID(0, 7801), "EquipmentCleanup"));
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        state.getBlock().playerWillDestroy(level, position, state, player);
        if (!state.onDestroyedByPlayer(level, position, player, false, level.getFluidState(position)))
            throw new IllegalStateException("Creative equipment cleanup was refused");
    }
    /** Releases the chunks the plant held loaded. */
    public void release() {
        for (var chunk : chunks()) level.setChunkForced(chunk.x, chunk.z, false);
    }

    /** Whether nothing the plant placed is still registered or standing. */
    public boolean removed() {
        for (var at : devices) if (!level.getBlockState(at).isAir() || world.at(position(at)).isPresent()) return false;
        for (var vessel : structures) if (level.getBlockEntity(vessel.controller()) instanceof EquipmentShellBlockEntity) return false;
        for (var record : world.registrations().values()) {
            var p = record.device().position();
            if (inside(new BlockPos(p.x(), p.y(), p.z()))) return false;
        }
        return entry() == null;
    }

    /** The chunks the plant spans. */
    public List<ChunkPos> chunks() {
        var out = new ArrayList<ChunkPos>();
        for (int x = low.getX() >> 4; x <= high.getX() >> 4; x++) for (int z = low.getZ() >> 4; z <= high.getZ() >> 4; z++) out.add(new ChunkPos(x, z));
        return out;
    }

    /**
     * Generator {@code id} set to {@code spec} and switched on in one queued edit, as a player sets its feed and its power
     * switch on its page: a generator is placed switched off (batch 2026-10-23-fluid-device-controls-display, 0f208c52).
     */
    public static void charge(FluidWorldAuthority world, long id, com.wormzjl.icheme.runtime.fluid.FluidDeviceSpec spec) {
        var old = world.registrations().get(id);
        var d = old.device();
        world.edit(id, old.revision(), PhysicalFluidTopology.Device.builder(d.id(), d.position(), d.kind(), d.facing(), d.geometry(), d.control()).ports(d.ports())
                .cutoff(d.cutoff()).inlineFilter(d.inlineFilter()).build(), spec);
    }

    /** The data or power port of the structure whose origin is {@code origin}, or null. */
    public static @Nullable DataPort port(PlantBuilder plant, BlockPos origin, CableMedium medium) {
        var level = plant.level;
        for (var p : plant.world.controls().facts().ports().values())
            if (p.kind().medium() == medium && level.getBlockEntity(p.owner()) instanceof EquipmentShellBlockEntity s && origin.equals(s.origin())) return p;
        return null;
    }

    /**
     * A cable of {@code medium} through {@code ports} in order: each leg the shortest run of free cells inside the plant's box,
     * a cable block in each cell with joints to its neighbours on the run and its socket's joint where a port's cell is, as
     * the laying tool leaves a line. Returns each cell's cable fact, in order.
     */
    public static List<DataBus.Cable> lay(PlantBuilder plant, List<DataPort> ports, CableMedium medium) {
        var level = plant.level;
        var path = new ArrayList<BlockPos>();
        // No leg leaves a socket's cell through the socket's own face: that joint is the socket's, at its offset.
        var beyond = new HashSet<BlockPos>();
        for (var p : ports) beyond.add(p.cell().relative(p.face()));
        for (int i = 0; i + 1 < ports.size(); i++) {
            var taken = new HashSet<>(path);
            taken.addAll(beyond);
            // Nor through another port's cell, which its own legs reach.
            for (var p : ports) taken.add(p.cell());
            taken.remove(ports.get(i + 1).cell());
            var leg = cableRoute(plant, ports.get(i).cell(), ports.get(i + 1).cell(), taken);
            if (!path.isEmpty()) leg.removeFirst();
            path.addAll(leg);
        }
        var joints = new LinkedHashMap<BlockPos, ConduitGeometry.Key>();
        for (int i = 0; i < path.size(); i++) {
            var cell = path.get(i);
            var key = joints.getOrDefault(cell, new ConduitGeometry.Key(0, 0, medium));
            for (int j : new int[]{i - 1, i + 1})
                if (j >= 0 && j < path.size() && !path.get(j).equals(cell)) {
                    var step = path.get(j).subtract(cell);
                    key = key.connect(Direction.fromDelta(step.getX(), step.getY(), step.getZ()).ordinal(), 0, 0);
                }
            joints.put(cell, key);
        }
        var ends = new HashMap<BlockPos, Map<Direction, UUID>>();
        for (var p : ports) {
            joints.put(p.cell(), joints.get(p.cell()).connect(p.face().ordinal(), p.u(), p.v()));
            ends.computeIfAbsent(p.cell(), c -> new EnumMap<>(Direction.class)).put(p.face(), p.id());
        }
        var block = medium == CableMedium.POWER ? ModBlocks.POWER_CABLE.get() : ModBlocks.DATA_CABLE.get();
        var dimension = level.dimension().location().toString();
        var out = new ArrayList<DataBus.Cable>();
        for (var entry : joints.entrySet()) {
            var cable = new DataBus.Cable(new DataBus.Site(dimension, entry.getKey(), 0), entry.getValue(), 0, ends.getOrDefault(entry.getKey(), Map.of()));
            place(plant, cable, block);
            out.add(cable);
        }
        return out;
    }

    /** A cable block holding {@code cable}. */
    public static void place(PlantBuilder plant, DataBus.Cable cable, Block block) {
        var level = plant.level;
        var at = cable.site().cell();
        level.setBlock(at, block.defaultBlockState(), Block.UPDATE_ALL);
        if (!(level.getBlockEntity(at) instanceof ConduitBlockEntity conduit)) throw new IllegalStateException("No cable at " + at.toShortString());
        conduit.configure(cable);
        plant.devices.add(at.immutable());
    }

    /** The shortest run of air cells from {@code from} to {@code to} inside the plant's box, past no pipe or structure and none of {@code taken}. */
    public static List<BlockPos> cableRoute(PlantBuilder plant, BlockPos from, BlockPos to, Set<BlockPos> taken) {
        var previous = new HashMap<BlockPos, BlockPos>();
        var queue = new ArrayDeque<BlockPos>();
        previous.put(from, from);
        queue.add(from);
        while (!queue.isEmpty()) {
            var cell = queue.removeFirst();
            if (cell.equals(to)) {
                var path = new ArrayList<BlockPos>();
                for (var c = to; !c.equals(from); c = previous.get(c)) path.addFirst(c);
                path.addFirst(from);
                return path;
            }
            for (var d : Direction.values()) {
                var next = cell.relative(d);
                if (previous.containsKey(next) || !plant.inside(next) || next.getY() < plant.base.getY()) continue;
                if (!next.equals(to) && (!plant.level.getBlockState(next).isAir() || taken.contains(next) || plant.reserved.contains(next))) continue;
                previous.put(next, cell);
                queue.addLast(next);
            }
        }
        throw new IllegalStateException("No free cable path from " + from.toShortString() + " to " + to.toShortString());
    }
}
