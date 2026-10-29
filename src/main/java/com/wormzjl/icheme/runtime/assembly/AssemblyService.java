package com.wormzjl.icheme.runtime.assembly;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.runtime.assembly.AssemblyStore.Entry;
import com.wormzjl.icheme.runtime.assembly.AssemblyStore.Status;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.science.assembly.AssemblyMatcher;
import com.wormzjl.icheme.science.assembly.AssemblyView;
import com.wormzjl.icheme.science.assembly.Blueprint;
import com.wormzjl.icheme.science.assembly.ColumnBlueprint;
import com.wormzjl.icheme.science.assembly.ExchangerBlueprint;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.EquipmentStructure.Vessel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * Validates assemblies and reverts their reports without polling (batch 2026-10-26-multi-machine-framework, plan
 * sections 6 and 7). A validation walks the pipes once and stores its report; nothing runs periodically. Three paths
 * change what a report relies on, each answered with one index lookup: a fluid edit (a pipe placed or removed, a fitting
 * installed or removed; a setting edit changes no code and reverts nothing), a structure placed, removed or rebuilt, and a
 * chunk load, whose blocks are compared with the stored codes (reads only). A complete report reverts on any structural
 * change it relied on, a new branch included; a checked one on a change at a block or part it found, but not on an
 * addition beside it: the player is building. Requests and chunk checks queue and run at the next server tick.
 */
public final class AssemblyService {
    /** A master to validate, found from one of its cells; {@code rebuild}: only if the store was dropped and holds no entry. */
    private record Request(ResourceKey<Level> dimension, BlockPos cell, boolean rebuild) {
    }

    private record LoadedChunk(ResourceKey<Level> dimension, long chunk) {
    }

    private static final class Queue {
        final LinkedHashMap<BlockPos, Request> requests = new LinkedHashMap<>();
        final LinkedHashSet<LoadedChunk> chunks = new LinkedHashSet<>();
        long walks;
    }

    private static final Map<MinecraftServer, Queue> QUEUES = new IdentityHashMap<>();

    private AssemblyService() {
    }

    private static Queue queue(MinecraftServer server) {
        return QUEUES.computeIfAbsent(server, s -> new Queue());
    }

    /** Forgets a stopped server's queue. */
    public static void forget(MinecraftServer server) {
        QUEUES.remove(server);
    }

    /** How many validations walked on {@code server}: a counter the GameTests read. */
    public static long walks(MinecraftServer server) {
        var q = QUEUES.get(server);
        return q == null ? 0 : q.walks;
    }

    /** A column with a component design: one that has an assembly. */
    public static boolean designed(EquipmentShape shape) {
        return shape.kind() == EquipmentShape.Kind.COLUMN && shape.trays() != null && shape.trays().components() != null;
    }

    /** The entry of the column at {@code origin}, or null. */
    public static @Nullable Entry entry(ServerLevel level, BlockPos origin) {
        return AssemblyStore.of(level).entry(origin.asLong());
    }

    /**
     * What the GUI of the column holding {@code cell} shows: a read of its stored entry, which walks nothing. Null when no
     * column stands there.
     */
    public static @Nullable AssemblyView view(ServerLevel level, BlockPos cell) {
        var vessel = EquipmentStructure.find(level, cell);
        if (vessel == null || vessel.shape().kind() != EquipmentShape.Kind.COLUMN) return null;
        if (!designed(vessel.shape())) return AssemblyView.NO_DESIGN;
        var entry = entry(level, vessel.origin());
        return entry == null ? AssemblyView.NOT_VALIDATED : view(entry);
    }

    private static AssemblyView view(Entry entry) {
        var status = switch (entry.status()) {
            case NOT_VALIDATED -> AssemblyView.Status.NOT_VALIDATED;
            case CHECKED -> AssemblyView.Status.CHECKED;
            case COMPLETE -> AssemblyView.Status.COMPLETE;
        };
        return new AssemblyView(status, entry.report(), entry.changedAt());
    }

    /**
     * The stored views of the columns with a block, a nozzle or a unit within {@code range} of ({@code x}, {@code y},
     * {@code z}), by master (the laying tool's watch, {@link AssemblyWatch}): reads of the stored entries, which walk nothing.
     */
    public static Map<Long, AssemblyView> watched(ServerLevel level, double x, double y, double z, double range) {
        var out = new TreeMap<Long, AssemblyView>();
        for (var stored : AssemblyStore.of(level).entries().entrySet()) {
            long master = stored.getKey();
            boolean near = near(master, x, y, z, range);
            for (var it = stored.getValue().positions().iterator(); !near && it.hasNext(); ) near = near(it.next(), x, y, z, range);
            if (near) out.put(master, view(stored.getValue()));
        }
        return out;
    }

    private static boolean near(long packed, double x, double y, double z, double range) {
        double dx = BlockPos.getX(packed) + .5 - x, dy = BlockPos.getY(packed) + .5 - y, dz = BlockPos.getZ(packed) + .5 - z;
        return dx * dx + dy * dy + dz * dz <= range * range;
    }

    /** The column whose report holds the unit at {@code origin}, or null: a click on that unit opens the column's GUI. */
    public static @Nullable Long holder(ServerLevel level, BlockPos origin) {
        var store = AssemblyStore.of(level);
        long o = origin.asLong();
        // The least, should two reports hold it.
        return store.mastersAt(o).stream().filter(m -> m != o && store.entry(m) != null && store.entry(m).holds(o)).min(Long::compare).orElse(null);
    }

    /**
     * A loaded shell cell of the unit at {@code origin} that {@code master}'s report reached: one of its coded nozzles
     * (a unit's origin can be an air cell). Null when the report reached none.
     */
    public static @Nullable BlockPos cellOf(ServerLevel level, long master, long origin) {
        var entry = AssemblyStore.of(level).entry(master);
        if (entry == null) return null;
        for (var coded : entry.codes().entrySet()) {
            if (coded.getValue() >= 0) continue;
            var at = BlockPos.of(coded.getKey());
            if (!level.isLoaded(at)) continue;
            var vessel = EquipmentStructure.find(level, at);
            if (vessel != null && vessel.origin().asLong() == origin) return at;
        }
        return null;
    }

    /** Queues a validation of the structure holding {@code cell}. */
    public static void request(ServerLevel level, BlockPos cell) {
        queue(level.getServer()).requests.putIfAbsent(cell.immutable(), new Request(level.dimension(), cell.immutable(), false));
    }

    /**
     * Validates the column, or the exchanger with control lines (batch 2026-10-27-heat-exchanger-design, W8), holding
     * {@code cell} now: walks its pipes, matches its blueprint and stores the report; an exchanger's sides then take its
     * lines. Null for a structure with no assembly (an exchanger whose controls ask for no lines drops a stored report).
     */
    public static @Nullable Entry validate(ServerLevel level, BlockPos cell) {
        var vessel = EquipmentStructure.find(level, cell);
        if (vessel == null) return null;
        var world = FluidWorldAuthority.find(level.getServer()).orElse(null);
        if (world == null) return null;
        var store = AssemblyStore.of(level);
        long master = vessel.origin().asLong();
        Blueprint blueprint;
        if (designed(vessel.shape())) blueprint = ColumnBlueprint.of(vessel.shape().trays());
        else {
            var design = ExchangerLines.design(level, world, vessel);
            if (!design.any()) {
                if (store.entry(master) != null) store.forget(master);
                ExchangerLines.publish(level, world, vessel, null);
                return null;
            }
            blueprint = ExchangerBlueprint.of(design);
        }
        var walker = new AssemblyWalker(new LevelGround(level, world));
        var report = AssemblyMatcher.match(blueprint, walker, walker.unit(vessel.origin(), vessel.shape()), store.taken(master));
        var entry = Entry.of(report, walker.codes());
        store.put(master, entry);
        ExchangerLines.publish(level, world, vessel, entry);
        queue(level.getServer()).walks++;
        if (walker.exhausted())
            IChemE.LOGGER.warn("assembly status=WALK_LIMIT master={} visited={} detail=the walk stopped at {} blocks; lines beyond read as not checked",
                    vessel.origin().toShortString(), walker.visited(), AssemblyWalker.MAX_WALK);
        return entry;
    }

    /** Runs the queued chunk checks and validations; nothing when none is queued. */
    public static void tick(MinecraftServer server) {
        var q = QUEUES.get(server);
        if (q == null || q.requests.isEmpty() && q.chunks.isEmpty()) return;
        var chunks = new ArrayList<>(q.chunks);
        q.chunks.clear();
        for (var c : chunks) {
            var level = server.getLevel(c.dimension());
            if (level != null) verify(level, c.chunk());
        }
        var requests = new ArrayList<>(q.requests.values());
        q.requests.clear();
        var done = new HashSet<Long>();
        for (var r : requests) {
            var level = server.getLevel(r.dimension());
            if (level == null || !level.isLoaded(r.cell())) continue;
            var vessel = EquipmentStructure.find(level, r.cell());
            // One validation per master per round.
            if (vessel == null || !done.add(vessel.origin().asLong())) continue;
            if (r.rebuild()) {
                var store = AssemblyStore.of(level);
                if (!store.rebuilding() || store.entry(vessel.origin().asLong()) != null) continue;
            }
            validate(level, r.cell());
        }
    }

    /**
     * A designed column's controller loaded: after a dropped store, a column with no entry is validated once, which
     * rebuilds its report and footprint.
     */
    public static void controllerLoaded(ServerLevel level, BlockPos cell, EquipmentShape shape) {
        if (!designed(shape) || !AssemblyStore.of(level).rebuilding()) return;
        queue(level.getServer()).requests.putIfAbsent(cell.immutable(), new Request(level.dimension(), cell.immutable(), true));
    }

    /**
     * A flowmeter installed on or removed from the pipe at {@code at} (W8): a report whose walk read that block reverts, as
     * for a fitting; the walk reads a metered pipe as a flowmeter.
     */
    public static void metered(ServerLevel level, BlockPos at) {
        var store = AssemblyStore.of(level);
        for (long master : coded(store, at.asLong())) revert(level, store, master, at, "flowmeter");
    }

    /** A chunk loaded: its blocks are compared with the codes of the reports that rely on them, at the next tick. */
    public static void chunkLoaded(ServerLevel level, ChunkPos chunk) {
        if (AssemblyStore.of(level).mastersInChunk(chunk.toLong()).isEmpty()) return;
        queue(level.getServer()).chunks.add(new LoadedChunk(level.dimension(), chunk.toLong()));
    }

    /**
     * A fluid edit: each device before and after it. A changed code at a position a report relies on reverts it; a block
     * placed beside a complete report's block is a new branch and reverts it too.
     */
    public static void edited(MinecraftServer server, List<FluidWorldAuthority.DeviceChange> changes) {
        for (var change : changes) {
            var p = change.position();
            var level = server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.ResourceLocation.parse(p.dimension())));
            if (level == null) continue;
            var store = AssemblyStore.of(level);
            var at = new BlockPos(p.x(), p.y(), p.z());
            // An exchanger side whose control now asks for other lines (W8): its exchanger's report was for another design.
            var was = change.before() == null ? null : change.before().heat();
            var now = change.after() == null ? null : change.after().heat();
            if (was != null && now != null && was.lines() != now.lines()) redesigned(level, at);
            Integer before = code(change.before()), after = code(change.after());
            if (Objects.equals(before, after)) continue;
            for (long master : coded(store, at.asLong())) revert(level, store, master, at, "changed");
            if (before == null)
                for (var d : Direction.values())
                    for (long master : coded(store, at.relative(d).asLong()))
                        if (status(store, master) == Status.COMPLETE) revert(level, store, master, at, "branch");
        }
    }

    /**
     * A structure placed or rebuilt: a complete report whose blocks it stands beside reverts (its nozzles may join a walked
     * line). A placed designed column starts not validated.
     */
    public static void placed(ServerLevel level, Vessel vessel) {
        var store = AssemblyStore.of(level);
        for (var position : around(vessel))
            for (long master : coded(store, position))
                if (status(store, master) == Status.COMPLETE) revert(level, store, master, BlockPos.of(position), "structure");
        if (designed(vessel.shape()) && store.entry(vessel.origin().asLong()) == null) store.put(vessel.origin().asLong(), Entry.NOT_VALIDATED);
    }

    /**
     * A structure removed, or about to be rebuilt elsewhere: a column's own entry leaves; a report that held it, or reached
     * one of its nozzles, reverts.
     */
    public static void removed(ServerLevel level, Vessel vessel) {
        var store = AssemblyStore.of(level);
        long origin = vessel.origin().asLong();
        stopColumn(level, origin);
        store.forget(origin);
        FluidWorldAuthority.find(level.getServer()).ifPresent(world -> ExchangerLines.publish(level, world, vessel, null));
        for (long master : store.mastersAt(origin))
            if (store.entry(master) != null && store.entry(master).holds(origin)) revert(level, store, master, vessel.origin(), "removed");
        for (var position : around(vessel)) for (long master : coded(store, position)) revert(level, store, master, BlockPos.of(position), "removed");
    }

    /**
     * A structure dismantled (batch 2026-10-28-column-operation, plan section 12): a column's unit leaves the world with it,
     * its accounts booked as destroyed. A rebuild elsewhere ({@link #removed} alone) keeps the unit: the column stops.
     */
    public static void dismantled(ServerLevel level, Vessel vessel) {
        FluidWorldAuthority.find(level.getServer()).ifPresent(world -> world.columnRemoved(position(level, vessel.origin().asLong())));
    }

    /** A running column at {@code master} stops (plan 4.1): its structure or its assembly changed. */
    private static void stopColumn(ServerLevel level, long master) {
        FluidWorldAuthority.find(level.getServer()).ifPresent(world -> {
            var unit = world.column(position(level, master));
            if (unit != null && unit.running()) world.stopColumn(unit.id());
        });
    }

    /** The Start of the column at {@code origin} (C7): its request from its complete report ({@link ColumnStarts}); null, or why it did not start. */
    public static @Nullable String start(ServerLevel level, BlockPos origin) {
        var world = FluidWorldAuthority.find(level.getServer()).orElse(null);
        if (world == null) return "the fluid world is not running";
        var built = ColumnStarts.build(level, world, origin);
        return built.request() == null ? built.refusal() : world.startColumn(built.request());
    }

    /** The Stop of the column at {@code origin} (C7): nothing where it does not run. */
    public static void stop(ServerLevel level, BlockPos origin) {
        stopColumn(level, origin.asLong());
    }

    /**
     * The set points {@code values} for the column at {@code origin} (C7; {@code AssemblyNetwork.OperationPayload}'s order):
     * the column's own set points with these values, the feed rate held to the design rate (owner Q8). Null, or why not.
     */
    public static @Nullable String setpoints(ServerLevel level, BlockPos origin, List<Double> values) {
        var world = FluidWorldAuthority.find(level.getServer()).orElse(null);
        var master = position(level, origin.asLong());
        var unit = world == null ? null : world.column(master);
        if (unit == null) return "the column has not run yet";
        var s = unit.setpoints();
        int steam = s.steamPerFeed().length, draws = s.drawsPerFeed().length, pa = s.pumparoundsPerFeed().length;
        if (values.size() != 4 + steam + draws + pa) return "the set points do not fit the column's design";
        double[] v = values.stream().mapToDouble(Double::doubleValue).toArray();
        try {
            var value = new com.wormzjl.icheme.science.column.operation.Setpoints(s.designFeedRate(), Math.max(0, Math.min(s.designFeedRate(), v[0])), v[1], v[2], v[3],
                    Arrays.copyOfRange(v, 4, 4 + steam), Arrays.copyOfRange(v, 4 + steam, 4 + steam + draws), Arrays.copyOfRange(v, 4 + steam + draws, v.length));
            return world.editColumn(master, value);
        } catch (IllegalArgumentException invalid) {
            return invalid.getMessage();
        }
    }

    /**
     * The Operation tab's snapshot of the column at {@code origin} (C7): its unit's, a read of the engine's last cycle; where
     * it never ran, stopped on its design's set points, or not ready where it cannot run (a vacuum column, owner Q11) or its
     * last Start was refused ({@code refusal}). Null where its design has no operating point.
     */
    public static @Nullable com.wormzjl.icheme.science.column.operation.OperationView operation(ServerLevel level, BlockPos origin, @Nullable String refusal,
                                                                                               java.util.function.LongToDoubleFunction opening) {
        var world = FluidWorldAuthority.find(level.getServer()).orElse(null);
        if (world == null) return null;
        var view = world.columnView(position(level, origin.asLong()), opening);
        if (view != null && (view.running() || refusal == null)) return view;
        var vessel = EquipmentStructure.find(level, origin);
        var trays = vessel == null ? null : vessel.shape().trays();
        var point = trays == null ? null : trays.operating();
        var setpoints = view != null ? view.setpoints() : point == null ? null : com.wormzjl.icheme.science.column.operation.Setpoints.of(point);
        if (setpoints == null) return null;
        var phase = com.wormzjl.icheme.science.column.operation.OperationView.Phase.STOPPED;
        String engine = "STOPPED";
        if (trays.components() != null && trays.components().ejector()) {
            phase = com.wormzjl.icheme.science.column.operation.OperationView.Phase.NOT_READY;
            engine = "a vacuum column cannot run yet";
        } else if (refusal != null) {
            phase = com.wormzjl.icheme.science.column.operation.OperationView.Phase.NOT_READY;
            engine = refusal;
        }
        var idle = com.wormzjl.icheme.science.column.operation.OperationView.idle(phase, engine, setpoints);
        return view == null ? idle : new com.wormzjl.icheme.science.column.operation.OperationView(phase, idle.limit(), 0, false, null, false, engine, false, true, setpoints,
                List.of(), List.of(), null);
    }

    private static com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Position position(ServerLevel level, long at) {
        var p = BlockPos.of(at);
        return new com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Position(level.dimension().location().toString(), p.getX(), p.getY(), p.getZ());
    }

    /** The masters whose reports coded the block at {@code position}. */
    private static List<Long> coded(AssemblyStore store, long position) {
        var out = new ArrayList<Long>();
        for (long master : store.mastersAt(position)) {
            var entry = store.entry(master);
            if (entry != null && entry.codes().containsKey(position)) out.add(master);
        }
        return out;
    }

    private static @Nullable Status status(AssemblyStore store, long master) {
        var entry = store.entry(master);
        return entry == null ? null : entry.status();
    }

    /**
     * The exchanger whose side registers at {@code at} changed the lines its controls ask for (W8): its stored report, for
     * the old design, leaves, and its sides' lines with it. A check validates the new design.
     */
    private static void redesigned(ServerLevel level, BlockPos at) {
        var vessel = EquipmentStructure.find(level, at);
        var world = FluidWorldAuthority.find(level.getServer()).orElse(null);
        if (vessel == null || world == null || vessel.shape().kind() != EquipmentShape.Kind.EXCHANGER) return;
        var store = AssemblyStore.of(level);
        if (store.entry(vessel.origin().asLong()) != null) store.forget(vessel.origin().asLong());
        ExchangerLines.publish(level, world, vessel, null);
        IChemE.LOGGER.info("assembly status=REDESIGNED master={} detail=an exchanger side's control asks for other lines", vessel.origin().toShortString());
    }

    /** {@code master}'s stored entry as its exchanger's sides' lines (W8), where its exchanger is loaded; nothing for a column. */
    private static void published(ServerLevel level, long master) {
        published(level, master, cellOf(level, master, master));
    }

    /** {@link #published(ServerLevel, long)} with the exchanger found from {@code cell}, one of its loaded cells (null: none). */
    private static void published(ServerLevel level, long master, @Nullable BlockPos cell) {
        var world = FluidWorldAuthority.find(level.getServer()).orElse(null);
        var vessel = cell == null ? null : EquipmentStructure.find(level, cell);
        if (world == null || vessel == null || vessel.shape().kind() != EquipmentShape.Kind.EXCHANGER) return;
        ExchangerLines.publish(level, world, vessel, AssemblyStore.of(level).entry(master));
    }

    private static void revert(ServerLevel level, AssemblyStore store, long master, BlockPos at, String why) {
        var status = status(store, master);
        if (status == null || status == Status.NOT_VALIDATED) return;
        // Its cell first: a reverted entry codes nothing to find it by.
        var cell = cellOf(level, master, master);
        store.revert(master, at.asLong());
        stopColumn(level, master);
        published(level, master, cell);
        IChemE.LOGGER.info("assembly status=REVERTED master={} was={} at={} reason={}", BlockPos.of(master).toShortString(), status, at.toShortString(), why);
    }

    /** The structural code of a registered device, or null for none or a vessel (which its shell's nozzles stand for). */
    private static @Nullable Integer code(@Nullable PhysicalFluidTopology.Device device) {
        if (device == null) return null;
        var piece = LevelGround.piece(device);
        return piece == null ? null : piece.code();
    }

    /** A structure's shell cells and the pipe block in front of each nozzle. */
    private static Set<Long> around(Vessel vessel) {
        var out = new HashSet<Long>();
        var o = vessel.origin();
        out.add(o.asLong());
        for (var cell : ShapeRasterizer.layout(vessel.shape()).shell()) out.add(o.offset(cell.x(), cell.y(), cell.z()).asLong());
        for (var port : ShapeRasterizer.ports(vessel.shape()))
            out.add(o.offset(port.cell().x() + port.dx(), port.cell().y() + port.dy(), port.cell().z() + port.dz()).asLong());
        return out;
    }

    /** Compares the coded blocks of a loaded chunk with what stands there; a difference reverts the report. */
    private static void verify(ServerLevel level, long chunk) {
        var world = FluidWorldAuthority.find(level.getServer()).orElse(null);
        if (world == null) return;
        var ground = new LevelGround(level, world);
        var store = AssemblyStore.of(level);
        var pos = new ChunkPos(chunk);
        for (long master : store.mastersInChunk(chunk)) {
            var entry = store.entry(master);
            if (entry == null || entry.status() == Status.NOT_VALIDATED) continue;
            for (var coded : entry.codes().entrySet()) {
                var at = BlockPos.of(coded.getKey());
                if (at.getX() >> 4 != pos.x || at.getZ() >> 4 != pos.z) continue;
                if (actual(ground, at, coded.getValue()) != coded.getValue()) {
                    revert(level, store, master, at, "chunk_load");
                    break;
                }
            }
            // An exchanger's sides take its lines again (W8): the fluid world keeps them only while the server runs.
            published(level, master);
        }
    }

    /** What stands at {@code at} as a code: the piece's, or the nozzle code {@code expected} if a nozzle of that code is there. */
    private static int actual(AssemblyWalker.Ground ground, BlockPos at, int expected) {
        if (expected >= 0) {
            var piece = ground.piece(at);
            return piece == null ? Integer.MIN_VALUE : piece.code();
        }
        var shell = ground.shell(at);
        if (shell == null) return Integer.MIN_VALUE;
        var o = shell.origin();
        for (var port : ShapeRasterizer.ports(shell.shape()))
            if (o.getX() + port.cell().x() == at.getX() && o.getY() + port.cell().y() == at.getY() && o.getZ() + port.cell().z() == at.getZ()
                    && AssemblyWalker.nozzleCode(shell.shape().kind(), port.nozzle()) == expected) return expected;
        return Integer.MIN_VALUE;
    }
}
