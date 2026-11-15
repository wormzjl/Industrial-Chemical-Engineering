package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.science.equipment.EquipmentRefresh;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.equipment.UnitDesign;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import com.wormzjl.icheme.world.level.block.EquipmentStructure.Vessel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Predicate;

/**
 * A placed structure after its load: the queues of vessels to bring to the current layout, to restate and of retired kinds
 * to remove, their poll on the level tick ({@link #onLevelTick}), the waits for chunks and light, the walk over what stands
 * in a region ({@link #survey}) and the rebuild ({@link #write}, {@link #settle}). Server thread only; {@link
 * EquipmentStructure} keeps the entry points.
 */
final class StructureRefresh {
    private StructureRefresh() {
    }

    /**
     * Vessels an older layout placed, by level and origin, each with the region its old and new cells span (server
     * thread only; weak, so a level that goes drops its own).
     */
    static final Map<ServerLevel, Map<BlockPos, Stale>> STALE = new WeakHashMap<>();

    record Stale(EquipmentShape shape, BlockPos low, BlockPos high) {
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
     * The registry first for a current vessel's paint (REGISTRY_FIRST_PLAN.md 3.5): a vessel the registry holds no record
     * of teaches it its shape and its controller's paint (a world saved before the registry held structures); a vessel
     * whose record has its shape and another paint takes the record's paint on every shell cell. A vessel's cells share one
     * paint ({@link EquipmentStructure#place} and {@link #refresh} bind each with it), so the cells are walked only when the controller's
     * differs. A record of another shape waits for the re-alignment (plan 4.4).
     */
    private static void restatePaint(ServerLevel level, BlockPos origin, EquipmentShape shape, Paint paint, String name) {
        var world = FluidWorldAuthority.find(level.getServer()).orElse(null);
        if (world == null) return;
        var at = Positions.of(level, origin);
        var held = world.structure(at);
        if (held == null) {
            EquipmentStructure.record(level, origin, shape, paint, name);
            return;
        }
        if (!held.shape().equals(shape)) return;
        // A named vessel whose record holds no name teaches it (a world saved before the registry held names, W5c C4).
        if (held.name().isEmpty() && !name.isEmpty()) world.structure(at, held = held.withName(name));
        if (held.paint().equals(paint) && held.name().equals(name)) return;
        for (var cell : ShapeRasterizer.layout(shape).shell())
            if (level.getBlockEntity(origin.offset(cell.x(), cell.y(), cell.z())) instanceof EquipmentShellBlockEntity shell && EquipmentStructure.ours(shell, origin, shape)) {
                shell.repaint(held.paint());
                shell.rename(held.name());
            }
    }

    /**
     * Server, from the load of a vessel's controller cell when its layout is current: its interior cells are brought to
     * the state the code gives them ({@link #restate}) on a later tick, once its chunks are loaded and block-ticking. An
     * interior cell's state follows the code as a shell cell's drawing does; no cell is added or removed.
     */
    static void restateLater(ServerLevel level, BlockPos origin, EquipmentShape shape) {
        RESTATE.computeIfAbsent(level, k -> new LinkedHashMap<>()).putIfAbsent(origin.immutable(), shape);
    }

    /**
     * Sets every interior cell of the vessel at {@code origin} that holds another state than {@link EquipmentStructure#interior} gives
     * (a save from before {@link EquipmentInteriorBlock#SKYLIGHT}) to it, and lets its chunks wait for their light; its
     * paint follows the registry ({@link #restatePaint}). Only a vessel whose controller cell is its own and current: a
     * stale one is refreshed instead, which restates it too.
     */
    static void restate(ServerLevel level, BlockPos origin, EquipmentShape shape) {
        var controller = ShapeRasterizer.layout(shape).controller();
        if (!(level.getBlockEntity(origin.offset(controller.x(), controller.y(), controller.z())) instanceof EquipmentShellBlockEntity shell)
                || !EquipmentStructure.ours(shell, origin, shape) || shell.stale()) return;
        restatePaint(level, origin, shape, shell.paint(), shell.name());
        int changed = restateCells(level, origin, shape, Set.of(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        if (changed == 0) return;
        waitForLight(level, origin, corner(origin, shape));
        IChemE.LOGGER.info("equipment_restate kind={} origin={} interior={}", shape.kind().name().toLowerCase(Locale.ROOT), origin.toShortString(), changed);
    }

    /**
     * Sets each of the layout's interior cells at {@code origin}, but those in {@code skip}, that holds an interior block of
     * another state to {@link EquipmentStructure#interior}; how many.
     */
    private static int restateCells(ServerLevel level, BlockPos origin, EquipmentShape shape, Set<ShapeRasterizer.Cell> skip, int flags) {
        var state = EquipmentStructure.interior(shape);
        int changed = 0;
        for (var cell : ShapeRasterizer.layout(shape).interior()) {
            if (skip.contains(cell)) continue;
            var at = origin.offset(cell.x(), cell.y(), cell.z());
            var held = level.getBlockState(at);
            if (held.is(ModBlocks.EQUIPMENT_INTERIOR.get()) && held != state) {
                EquipmentStructure.setCell(level, at, state, flags);
                changed++;
            }
        }
        return changed;
    }

    /**
     * Server, from a shell cell's load: an older layout of its shape placed it (owner request 2026-10-18). Its vessel is
     * refreshed on a later tick, once every chunk its old and new cells span is loaded and block-ticking; blocks are not
     * changed while a chunk loads. {@code box} is the box the cell was placed by (zeros when saved before the layout was recorded),
     * {@code cell} its position, so a cell an old, larger box left outside the new one widens the region; the new box may
     * stand {@link EquipmentRefresh#REACH} from the old one along x and z, and its nozzles' pipe blocks one cell beyond it.
     */
    static void refreshLater(ServerLevel level, BlockPos origin, EquipmentShape shape, int[] box, BlockPos cell) {
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
     * box (zeros when saved before the layout was recorded), {@code cell} the cell's position, which widens the region.
     */
    static void removeRetiredLater(ServerLevel level, BlockPos origin, String kind, int[] box, BlockPos cell) {
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
        EquipmentStructure.record(level, origin, null, null, com.wormzjl.icheme.science.equipment.DeviceName.NONE);
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        EquipmentStructure.dismantling = true;
        try {
            for (var at : interior) EquipmentStructure.setCell(level, at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), flags);
            for (var at : shells) EquipmentStructure.setCell(level, at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), flags);
        } finally {
            EquipmentStructure.dismantling = false;
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
            for (int step = 1; step <= EquipmentStructure.FIND_STEPS; step++) {
                var at = position.relative(direction, step);
                if (retiredShell(level, at, origin, kind)) return true;
                if (!level.getBlockState(at).is(ModBlocks.EQUIPMENT_INTERIOR.get())) break;
            }
        return false;
    }

    /** At most one retired, stale or restated vessel a tick, the first whose region is ready; on most ticks there is none. */
    static void onLevelTick(LevelTickEvent.Post event) {
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
    static boolean ready(ServerLevel level, BlockPos low, BlockPos high) {
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
     * drum keeps its registration ({@link StructureRegistration#reregister}). Nothing else changes. A chunk the change reaches before it is
     * sent to a player waits for its light ({@link ChunkMap#waitForLightBeforeSending}): the server sends a player light
     * changes only on the edge of its view, a player relighting its own block changes, so a chunk sent with the light
     * of before would keep it.
     */
    static void refresh(ServerLevel level, BlockPos origin, EquipmentShape shape, BlockPos low, BlockPos high) {
        var survey = survey(level, origin, low, high, shell -> EquipmentStructure.ours(shell, origin, shape), false);
        if (survey.paint() == null) return;
        // A rebuilt structure is a structural change to every assembly report that relied on it.
        com.wormzjl.icheme.runtime.assembly.AssemblyService.removed(level, new Vessel(origin, shape));
        var plan = EquipmentRefresh.plan(ShapeRasterizer.layout(shape), survey.world());
        var vessel = new Vessel(origin.offset(plan.shift().x(), 0, plan.shift().z()), shape);
        var touched = write(level, vessel, survey.paint(), survey.name(), plan, false);
        waitForLight(level, low, high);
        // The registry's record follows the structure to where its layout now stands, cells left out or not: the registry
        // holds the structure the player built.
        if (!vessel.origin().equals(origin)) EquipmentStructure.record(level, origin, null, null, com.wormzjl.icheme.science.equipment.DeviceName.NONE);
        EquipmentStructure.record(level, vessel.origin(), shape, survey.paint(), survey.name());
        // A shaft unit stands at its structure's origin (W3): a moved origin's old unit leaves, the new one is queued here.
        if (UnitDesign.shafted(shape.kind()) && !vessel.origin().equals(origin)) ShaftParts.remove(level, origin);
        if (shape.fluidDevice()) StructureRegistration.reregister(level, vessel, survey.node(), survey.identities());
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
    record Survey(Map<ShapeRasterizer.Cell, EquipmentRefresh.Holding> world, @Nullable Paint paint, String name, @Nullable BlockPos node, List<Long> identities,
                          BlockPos far) {
    }

    /**
     * Reads what stands between {@code low} and {@code high}, from {@code origin}. A shell cell is the vessel's own when
     * {@code own} accepts it; an interior cell when a walk over interior cells reaches one of its own shell cells, or, with
     * {@code orphans}, reaches none (an interior cell holds no data: one whose shell cells are gone is the vessel's whose
     * region it lies in); a fluid device block is a pipe; any other block that is not replaceable is another owner's.
     */
    static Survey survey(ServerLevel level, BlockPos origin, BlockPos low, BlockPos high, Predicate<EquipmentShellBlockEntity> own, boolean orphans) {
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
                var shell = EquipmentStructure.shellReached(level, at);
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
     * written keep the stale layout revision, so the next load tries again. Returns the cells whose block or drawing changed.
     */
    static List<BlockPos> write(ServerLevel level, Vessel vessel, Paint paint, String name, EquipmentRefresh.Plan plan, boolean repaint) {
        var placed = vessel.origin();
        var shape = vessel.shape();
        var layout = ShapeRasterizer.layout(shape);
        var placeShell = new HashSet<>(plan.shell());
        var blocked = new HashSet<>(plan.blocked());
        var touched = new ArrayList<BlockPos>();
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        EquipmentStructure.dismantling = true;
        try {
            for (var cell : plan.clear()) {
                var at = placed.offset(cell.x(), cell.y(), cell.z());
                EquipmentStructure.setCell(level, at, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), flags);
                touched.add(at);
            }
            for (var cell : layout.shell()) {
                if (blocked.contains(cell)) continue;
                var at = placed.offset(cell.x(), cell.y(), cell.z());
                if (placeShell.contains(cell)) {
                    EquipmentStructure.setCell(level, at, ModBlocks.EQUIPMENT_SHELL.get().defaultBlockState(), flags);
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
            for (var cell : plan.interior()) EquipmentStructure.setCell(level, placed.offset(cell.x(), cell.y(), cell.z()), EquipmentStructure.interior(shape), flags);
            // The interior cells it kept take the current state too (restate); a blocked cell is another owner's.
            restateCells(level, placed, shape, blocked, flags);
            // Cells left out where other blocks stand: the cells written keep the stale layout revision, so the next load tries again.
            if (!blocked.isEmpty())
                for (var cell : layout.shell())
                    if (!blocked.contains(cell) && level.getBlockEntity(placed.offset(cell.x(), cell.y(), cell.z())) instanceof EquipmentShellBlockEntity entity) entity.layoutIncomplete();
        } finally {
            EquipmentStructure.dismantling = false;
        }
        return touched;
    }

    /**
     * After a rebuild: the pipes beside a dropped, added or redrawn cell take up or drop their connection (old nozzles
     * included), the vessel's nozzles redraw at the sizes of their pipes, and the assemblies that rely on it learn of it.
     */
    static void settle(ServerLevel level, Vessel vessel, List<BlockPos> touched) {
        for (var at : touched)
            for (var direction : Direction.values()) {
                var next = at.relative(direction);
                var state = level.getBlockState(next);
                if (state.getBlock() instanceof FluidDeviceBlock) Block.updateOrDestroy(state, Block.updateFromNeighbourShapes(state, level, next), level, next, Block.UPDATE_ALL);
            }
        EquipmentStructure.reconnect(level, vessel);
        EquipmentStructure.refreshNozzles(level, vessel);
        com.wormzjl.icheme.runtime.assembly.AssemblyService.placed(level, vessel);
    }

    /**
     * Each chunk from {@code low} to {@code high} waits for its light before it is sent to a player: the server sends a
     * player light changes only on the edge of its view, so a chunk sent with the light of before would keep it.
     */
    static void waitForLight(ServerLevel level, BlockPos low, BlockPos high) {
        for (int x = low.getX() >> 4; x <= high.getX() >> 4; x++)
            for (int z = low.getZ() >> 4; z <= high.getZ() >> 4; z++) level.getChunkSource().chunkMap.waitForLightBeforeSending(new ChunkPos(x, z), 0);
    }

    /** The far corner of {@code shape}'s box at {@code origin}. */
    static BlockPos corner(BlockPos origin, EquipmentShape shape) {
        return origin.offset(shape.sizeX() - 1, shape.sizeY() - 1, shape.sizeZ() - 1);
    }

    /** Every chunk of the region is loaded. */
    static boolean loaded(ServerLevel level, BlockPos low, BlockPos high) {
        for (int x = low.getX() >> 4; x <= high.getX() >> 4; x++)
            for (int z = low.getZ() >> 4; z <= high.getZ() >> 4; z++) if (!level.hasChunk(x, z)) return false;
        return true;
    }

    private static BlockPos min(BlockPos a, BlockPos b) {
        return new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
    }

    static BlockPos max(BlockPos a, BlockPos b) {
        return new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
    }
}
