package com.wormzjl.icheme.fluid.gametest;

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
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Assembly validation in a world (batch 2026-10-26-multi-machine-framework, plan sections 6 and 7, gate W5): a column
 * plant built as its design asks validates complete; a removed pipe reverts the report; a pipe added at an open end keeps a
 * partial one; a reload keeps a complete report without a walk; a dropped store rebuilds with one walk per column. Each
 * test removes what it placed, its result held until the cleanup is done, so a failure leaves nothing registered.
 */
@GameTestHolder("icheme_fluid_test")
@PrefixGameTestTemplate(false)
public final class AssemblyGameTests {
    private AssemblyGameTests() {}

    /**
     * A small column plant: a 2 m column of 8 trays with sump steam and no kettle (an exchanger condenser, a standard
     * reflux pump), its condenser, its receiver and its reflux pump, the lines between them routed so that no pipe touches
     * another line, and a valve and a tank on every boundary line. The review world builds a larger plant with the same
     * tools ({@link ColumnReviewWorld}).
     */
    static final class Plant {
        final GameTestHelper helper;
        final ServerLevel level;
        final FluidWorldAuthority world;
        final BlockPos base, low, high;
        final List<Vessel> structures = new ArrayList<>();
        final LinkedHashSet<BlockPos> devices = new LinkedHashSet<>();
        final Set<BlockPos> reserved = new HashSet<>();
        final Map<BlockPos, Direction> valves = new LinkedHashMap<>();
        Vessel column, drum;
        BlockPos offgasEnd;
        /** An exchanger plant's bypass valve and the bypass pipe before it (W8). */
        BlockPos bypassValve, bypassPipe;

        Plant(GameTestHelper helper) {
            this(helper, helper.absolutePos(new BlockPos(8, 4, 18)), new BlockPos(-8, -3, -18), new BlockPos(32, 22, 14));
        }

        /** A plant built from {@code base}, its pipes kept inside the box from {@code base + below} to {@code base + above}. */
        Plant(GameTestHelper helper, BlockPos base, BlockPos below, BlockPos above) {
            this.helper = helper;
            level = helper.getLevel();
            world = FluidWorldAuthority.find(level.getServer()).orElseThrow();
            this.base = base;
            low = base.offset(below);
            high = base.offset(above);
        }

        /** Holds the plant's chunks loaded while it is built and checked. */
        void hold() {
            for (var chunk : chunks()) {
                level.setChunkForced(chunk.x, chunk.z, true);
                level.getChunk(chunk.x, chunk.z);
            }
        }

        static EquipmentShape unit(EquipmentShape.Kind kind) {
            var d = UnitDesign.of(kind);
            double size = d.minSize();
            int length = d.defaultLength(size);
            return EquipmentShape.designed(kind, size, length, EquipmentShape.Heading.NORTH, d.defaultSupport(size, length), ProcessUnit.Bank.ONE);
        }

        /** Builds the plant; {@code offgasTank}: the off-gas line ends at a tank, else open two blocks beyond its valve. */
        void build(boolean offgasTank) {
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
        }

        /**
         * An exchanger with a bypass around its shell side (batch 2026-10-27-heat-exchanger-design, W8): a line routed from
         * the shell inlet's pipe block to the outlet's, with a control valve on it along its stream, then the feed and the
         * product branching off those two blocks (each a tee) to tanks. The exchanger is the master.
         */
        void buildBypass() {
            hold();
            var exchanger = place(unit(EquipmentShape.Kind.EXCHANGER), 0, 4, 0);
            column = exchanger;
            buildBypass(exchanger, Role.SHELL_INLET, Role.SHELL_OUTLET);
            for (var valve : valves.entrySet()) install(valve.getKey(), valve.getValue());
        }

        /** A bypass around {@code vessel}'s side from {@code in} to {@code out}, its feed and product to tanks; valves not yet installed. */
        void buildBypass(Vessel vessel, Role in, Role out) {
            var tank = ModBlocks.FLUID_RESERVOIR.get().defaultBlockState();
            buildBypass(vessel, in, out, tank, tank);
        }

        /**
         * {@link #buildBypass(Vessel, Role, Role)} with the feed's end block {@code feedEnd} and the product's
         * {@code productEnd}; returns the two ends' positions, feed first.
         */
        BlockPos[] buildBypass(Vessel vessel, Role in, Role out, net.minecraft.world.level.block.state.BlockState feedEnd,
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
        BlockPos branch(BlockPos at, Direction d, boolean outward, net.minecraft.world.level.block.state.BlockState end) {
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
        long side(HeatSides.Side side) {
            var ids = EquipmentStructure.sideIdentities(level, column);
            var sides = HeatSides.of(column.shape());
            for (int i = 0; i < sides.size(); i++) if (sides.get(i).side() == side) return ids[i];
            throw new IllegalStateException("No " + side + " side");
        }

        Vessel place(EquipmentShape shape, int dx, int dy, int dz) {
            var origin = base.offset(dx, dy, dz);
            EquipmentStructure.place(level, origin, shape, Paint.DEFAULT, Integer.MAX_VALUE);
            var vessel = new Vessel(origin, shape);
            structures.add(vessel);
            for (var p : ShapeRasterizer.ports(shape)) reserved.add(origin.offset(p.cell().x() + p.dx(), p.cell().y() + p.dy(), p.cell().z() + p.dz()));
            return vessel;
        }

        static ShapeRasterizer.Port port(Vessel vessel, Role role) {
            for (var p : ShapeRasterizer.ports(vessel.shape())) if (vessel.shape().nozzles().get(p.nozzle()).role() == role) return p;
            throw new IllegalStateException("No " + role + " on the " + vessel.shape().kind());
        }

        static BlockPos front(Vessel vessel, Role role) {
            var p = port(vessel, role);
            return vessel.origin().offset(p.cell().x() + p.dx(), p.cell().y() + p.dy(), p.cell().z() + p.dz());
        }

        static Direction out(Vessel vessel, Role role) {
            var p = port(vessel, role);
            return Direction.fromDelta(p.dx(), p.dy(), p.dz());
        }

        boolean inside(BlockPos at) {
            return at.getX() >= low.getX() && at.getX() <= high.getX() && at.getY() >= low.getY() && at.getY() <= high.getY() && at.getZ() >= low.getZ()
                    && at.getZ() <= high.getZ();
        }

        /** Whether a pipe may stand at {@code at}: air, no other nozzle's pipe block, nothing it would join but {@code allowed}. */
        boolean free(BlockPos at, Set<BlockPos> allowed) {
            if (!inside(at) || !level.getBlockState(at).isAir() || reserved.contains(at) && !allowed.contains(at) || devices.contains(at)) return false;
            for (var d : Direction.values()) {
                var n = at.relative(d);
                if (!allowed.contains(n) && (devices.contains(n) || reserved.contains(n))) return false;
            }
            return true;
        }

        /** The positions a new block may stand beside. */
        static Set<BlockPos> around(BlockPos... positions) {
            return new HashSet<>(List.of(positions));
        }

        void pipe(BlockPos at) {
            level.setBlock(at, ModBlocks.FLUID_PIPE.get().defaultBlockState(), Block.UPDATE_ALL);
            devices.add(at.immutable());
        }

        /**
         * A boundary line straight out of a nozzle: three pipes, a valve on the second (its outlet along the line, or back
         * at the nozzle), then a tank, or nothing (an open end). Returns the line's last pipe.
         */
        BlockPos outside(Vessel vessel, Role role, boolean outward, boolean tank) {
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
        List<BlockPos> route(BlockPos from, BlockPos to) {
            var allowed = Set.of(from, to);
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

        static boolean straight(List<BlockPos> path, int i) {
            var a = path.get(i).subtract(path.get(i - 1));
            var b = path.get(i + 1).subtract(path.get(i));
            return a.equals(b);
        }

        /** The reflux line's tee: a branch square to the line, a valve on it and a tank; the reflux valve after the tee. */
        void tee(List<BlockPos> path) {
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
        void install(BlockPos at, Direction outlet) {
            var state = level.getBlockState(at).setValue(FluidDeviceBlock.FACING, outlet).setValue(FluidDeviceBlock.PRESSURE_CONTROL, true);
            level.setBlock(at, FluidDeviceBlock.connectedState(state, level, at), Block.UPDATE_ALL);
            long id = world.at(position(at)).orElseThrow(() -> new IllegalStateException("No pipe at " + at.toShortString())).device().id();
            if (!world.installPipePressureValve(id, PhysicalFluidTopology.Direction.valueOf(outlet.name())))
                throw new IllegalStateException("The valve at " + at.toShortString() + " did not install");
        }

        PhysicalFluidTopology.Position position(BlockPos at) {
            return new PhysicalFluidTopology.Position(level.dimension().location().toString(), at.getX(), at.getY(), at.getZ());
        }

        boolean valvesInstalled() {
            for (var at : valves.keySet())
                if (!world.at(position(at)).map(r -> r.device().hydraulicKind() == TopologyCompiler.Kind.VALVE).orElse(false)) return false;
            return true;
        }

        BlockPos controller() {
            return column.controller();
        }

        @Nullable AssemblyStore.Entry entry() {
            return column == null ? null : AssemblyService.entry(level, column.origin());
        }

        /** The slots not in place, for a failure's message. */
        String faults() {
            var entry = entry();
            if (entry == null || entry.report() == null) return "no report: " + entry;
            var out = new StringBuilder();
            for (var s : entry.report().slots())
                if (s.state() != State.OK) out.append(s.slot().part()).append(s.slot().index()).append('=').append(s.state()).append(' ').append(s.detail()).append("; ");
            return out.toString();
        }

        /** Removes every pipe, tank and structure the plant placed. */
        void remove() {
            for (var at : devices) level.removeBlock(at, false);
            for (var vessel : structures) level.removeBlock(vessel.controller(), false);
        }

        /** Releases the chunks the plant held loaded. */
        void release() {
            for (var chunk : chunks()) level.setChunkForced(chunk.x, chunk.z, false);
        }

        /** Whether nothing the plant placed is still registered or standing. */
        boolean removed() {
            for (var at : devices) if (!level.getBlockState(at).isAir() || world.at(position(at)).isPresent()) return false;
            for (var vessel : structures) if (level.getBlockEntity(vessel.controller()) instanceof EquipmentShellBlockEntity) return false;
            for (var record : world.registrations().values()) {
                var p = record.device().position();
                if (inside(new BlockPos(p.x(), p.y(), p.z()))) return false;
            }
            return entry() == null;
        }

        /** The chunks the plant spans. */
        List<ChunkPos> chunks() {
            var out = new ArrayList<ChunkPos>();
            for (int x = low.getX() >> 4; x <= high.getX() >> 4; x++) for (int z = low.getZ() >> 4; z <= high.getZ() >> 4; z++) out.add(new ChunkPos(x, z));
            return out;
        }

    }

    /** A step of a test on its plant: it records a failure in {@code failure[0]}. */
    @FunctionalInterface
    private interface Step {
        void run(Plant plant, String[] failure);
    }

    /**
     * Builds a plant, waits for its valves, runs {@code steps} 20 ticks apart (each only while none failed), then removes
     * the plant and waits for it to leave; only then does the test pass or fail.
     */
    private static void withPlant(GameTestHelper helper, boolean offgasTank, Step... steps) {
        withPlant(helper, plant -> plant.build(offgasTank), steps);
    }

    /** {@link #withPlant} with the plant built by {@code build}. */
    private static void withPlant(GameTestHelper helper, java.util.function.Consumer<Plant> build, Step... steps) {
        var plant = new Plant(helper);
        String[] failure = {null};
        var sequence = helper.startSequence();
        try {
            build.accept(plant);
            sequence = sequence.thenWaitUntil(() -> helper.assertTrue(plant.valvesInstalled(), "Waiting for the valves"));
            for (int i = 0; i < steps.length; i++) {
                var step = steps[i];
                if (i > 0) sequence = sequence.thenIdle(20);
                sequence = sequence.thenExecute(() -> {
                    if (failure[0] != null) return;
                    try {
                        step.run(plant, failure);
                    } catch (RuntimeException e) {
                        failure[0] = e.toString();
                    }
                });
            }
        } catch (RuntimeException e) {
            failure[0] = "build: " + e.getMessage();
            com.wormzjl.icheme.IChemE.LOGGER.error("assembly_gametest build failed", e);
        }
        sequence.thenExecute(plant::remove).thenWaitUntil(() -> helper.assertTrue(plant.removed(), "Waiting for the plant to leave"))
                .thenExecute(() -> {
                    plant.release();
                    if (failure[0] != null) helper.fail(failure[0]);
                }).thenSucceed();
    }

    /** Validates the plant's column; a report that is not complete is a failure. */
    private static void complete(Plant plant, String[] failure) {
        var entry = AssemblyService.validate(plant.level, plant.controller());
        if (entry == null || entry.status() != AssemblyStore.Status.COMPLETE) failure[0] = "not complete: " + plant.faults();
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "assembly-complete")
    public static void aPlantBuiltAsDesignedValidatesComplete(GameTestHelper helper) {
        withPlant(helper, true, (plant, failure) -> {
            complete(plant, failure);
            if (failure[0] == null && plant.entry().report().slots().size() != ColumnBlueprint.of(plant.column.shape().trays()).slots().size())
                failure[0] = "slots: " + plant.entry().report().slots();
        });
    }

    /**
     * A click on a unit of the plant opens nothing of the column's before a validation holds it; after it, the column's GUI
     * with the unit selected, found through a shell cell of the column (plan section 10). A click on the column opens its own.
     */
    @GameTest(template = "empty", timeoutTicks = 600, batch = "assembly-click")
    public static void aClickOnAMemberOpensItsColumnsGuiWithTheMemberSelected(GameTestHelper helper) {
        withPlant(helper, true, (plant, failure) -> {
            if (com.wormzjl.icheme.network.AssemblyNetwork.target(plant.level, plant.drum) != null) {
                failure[0] = "a drum no report holds opens a column";
                return;
            }
            complete(plant, failure);
            if (failure[0] != null) return;
            var origin = plant.column.origin();
            var drum = com.wormzjl.icheme.network.AssemblyNetwork.target(plant.level, plant.drum);
            var own = com.wormzjl.icheme.network.AssemblyNetwork.target(plant.level, plant.column);
            if (drum == null || !drum.origin().equals(origin) || drum.selected() != plant.drum.origin().asLong()) failure[0] = "drum: " + drum;
            else if (own == null || !own.origin().equals(origin) || own.selected() != com.wormzjl.icheme.science.assembly.AssemblyReport.NONE) failure[0] = "column: " + own;
            else for (var target : List.of(drum, own)) {
                var found = EquipmentStructure.find(plant.level, target.column());
                if (found == null || !found.origin().equals(origin)) failure[0] = "the column cell " + target.column() + " finds " + found;
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "assembly-removed-pipe")
    public static void aRemovedPipeRevertsACompleteReport(GameTestHelper helper) {
        withPlant(helper, true, (plant, failure) -> {
            complete(plant, failure);
            if (failure[0] != null) return;
            // The off-gas line's pipe beyond its valve leaves: the report relied on it.
            plant.level.removeBlock(plant.offgasEnd, false);
            plant.devices.remove(plant.offgasEnd);
        }, (plant, failure) -> {
            var after = plant.entry();
            if (after == null || after.status() != AssemblyStore.Status.NOT_VALIDATED) failure[0] = "not reverted: " + (after == null ? null : after.status());
        });
    }

    /**
     * W8 (batch 2026-10-27-heat-exchanger-design, plan section 7): an exchanger whose shell side holds an outlet temperature
     * asks for a bypass. Its built bypass validates complete; its side's lines name the bypass valve, which, bare (no
     * flowmeter), cannot regulate yet; the tube side has none. A removed bypass pipe reverts the report and the lines.
     */
    @GameTest(template = "empty", timeoutTicks = 800, batch = "assembly-exchanger")
    public static void anExchangersBypassValidatesAndARemovedPipeReverts(GameTestHelper helper) {
        withPlant(helper, Plant::buildBypass, (plant, failure) -> {
            long shell = plant.side(HeatSides.Side.SHELL);
            var r = plant.world.registrations().get(shell);
            plant.world.edit(shell, r.revision(), r.device().withHeat(r.device().heat().withControl(new HeatUnit.Control(HeatUnit.Mode.OUTLET_TEMPERATURE, 318.15, 0, 0))), r.spec());
        }, (plant, failure) -> {
            complete(plant, failure);
            if (failure[0] != null) return;
            var lines = plant.world.lines(plant.side(HeatSides.Side.SHELL));
            long valve = plant.world.at(plant.position(plant.bypassValve)).orElseThrow().device().id();
            if (lines == null || lines.status() != FluidWorldAuthority.Lines.Status.UNMETERED || lines.valve() != valve) failure[0] = "shell lines: " + lines + ", the valve " + valve;
            else if (plant.world.lines(plant.side(HeatSides.Side.TUBE)) != null) failure[0] = "the tube side has lines: " + plant.world.lines(plant.side(HeatSides.Side.TUBE));
        }, (plant, failure) -> {
            plant.level.removeBlock(plant.bypassPipe, false);
            plant.devices.remove(plant.bypassPipe);
        }, (plant, failure) -> {
            var after = plant.entry();
            var lines = plant.world.lines(plant.side(HeatSides.Side.SHELL));
            if (after == null || after.status() != AssemblyStore.Status.NOT_VALIDATED) failure[0] = "not reverted: " + (after == null ? null : after.status());
            else if (lines == null || lines.status() != FluidWorldAuthority.Lines.Status.NOT_VALIDATED) failure[0] = "lines not reverted: " + lines;
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "assembly-open-end")
    public static void aPipeAddedAtAnOpenEndKeepsAPartialReport(GameTestHelper helper) {
        var before = new AssemblyStore.Entry[1];
        withPlant(helper, false, (plant, failure) -> {
            var entry = AssemblyService.validate(plant.level, plant.controller());
            if (entry == null || entry.status() != AssemblyStore.Status.CHECKED || entry.report().slot(ColumnBlueprint.OFFGAS_VALVE).state() != State.OPEN_END) {
                failure[0] = "expected a checked report with the off-gas line open: " + plant.faults();
                return;
            }
            before[0] = entry;
            plant.pipe(plant.offgasEnd.relative(Plant.out(plant.drum, Role.VAPOUR_OUTLET)));
        }, (plant, failure) -> {
            if (!before[0].equals(plant.entry())) failure[0] = "the partial report changed: " + plant.faults();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "assembly-reload")
    public static void aReloadKeepsACompleteReportWithoutAWalk(GameTestHelper helper) {
        var walks = new long[1];
        withPlant(helper, true, (plant, failure) -> {
            complete(plant, failure);
            if (failure[0] != null) return;
            // The store saved and read back, as a reload does; the column's controller and the plant's chunks load again.
            var registries = plant.level.registryAccess();
            var store = AssemblyStore.of(plant.level);
            plant.level.getDataStorage().set(AssemblyStore.DATA_NAME, AssemblyStore.load(store.save(new CompoundTag(), registries), registries));
            walks[0] = AssemblyService.walks(plant.level.getServer());
            ((EquipmentShellBlockEntity) plant.level.getBlockEntity(plant.controller())).onLoad();
            for (var chunk : plant.chunks()) AssemblyService.chunkLoaded(plant.level, chunk);
        }, (plant, failure) -> {
            var entry = plant.entry();
            if (entry == null || entry.status() != AssemblyStore.Status.COMPLETE) failure[0] = "not kept: " + plant.faults();
            else if (AssemblyService.walks(plant.level.getServer()) != walks[0]) failure[0] = "a reload walked";
        });
    }

    @GameTest(template = "empty", timeoutTicks = 600, batch = "assembly-rebuild")
    public static void aDroppedStoreRebuildsWithOneWalkPerColumn(GameTestHelper helper) {
        var walks = new long[1];
        withPlant(helper, true, (plant, failure) -> {
            complete(plant, failure);
            if (failure[0] != null) return;
            // A store of another state format reads as dropped; the column's controller loads.
            var registries = plant.level.registryAccess();
            var tag = AssemblyStore.of(plant.level).save(new CompoundTag(), registries);
            tag.putInt("format", AssemblyStore.FORMAT + 1);
            plant.level.getDataStorage().set(AssemblyStore.DATA_NAME, AssemblyStore.load(tag, registries));
            walks[0] = AssemblyService.walks(plant.level.getServer());
            ((EquipmentShellBlockEntity) plant.level.getBlockEntity(plant.controller())).onLoad();
        }, (plant, failure) -> {
            var entry = plant.entry();
            if (entry == null || entry.status() != AssemblyStore.Status.COMPLETE) failure[0] = "not rebuilt: " + plant.faults();
            else if (AssemblyService.walks(plant.level.getServer()) != walks[0] + 1) failure[0] = "walks: " + (AssemblyService.walks(plant.level.getServer()) - walks[0]);
            // A later load finds the entry: no second walk.
            ((EquipmentShellBlockEntity) plant.level.getBlockEntity(plant.controller())).onLoad();
        }, (plant, failure) -> {
            if (AssemblyService.walks(plant.level.getServer()) != walks[0] + 1) failure[0] = "a second load walked";
            // The level keeps a store that is not rebuilding: later runs reuse this world.
            plant.level.getDataStorage().set(AssemblyStore.DATA_NAME, new AssemblyStore());
        });
    }
}
