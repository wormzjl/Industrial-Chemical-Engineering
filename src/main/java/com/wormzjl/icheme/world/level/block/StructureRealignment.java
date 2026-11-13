package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.science.equipment.EquipmentRefresh;
import com.wormzjl.icheme.science.equipment.HeatSides;
import com.wormzjl.icheme.science.equipment.Paint;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import com.wormzjl.icheme.world.level.block.EquipmentStructure.Vessel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * The registry first for placed structures ({@link RegistryRealignment}): the queue of structures the registry records
 * whose chunks loaded, its serve on the level tick ({@link #serveRealignment}) and the rebuild of a structure to its record
 * ({@link #realign}). Server thread only.
 */
final class StructureRealignment {
    private StructureRealignment() {
    }

    /**
     * Structures the registry records whose chunks loaded, waiting for their re-alignment (registry first, batch
     * 2026-11-03-save-corruption), by level and origin, each with the far corner of the region it reads beyond its box.
     */
    private static final Map<ServerLevel, Map<BlockPos, BlockPos>> REALIGN = new WeakHashMap<>();

    /**
     * Server, from {@link FluidWorldAuthority#loadedChunk}: a chunk of the structure the registry records at {@code origin}
     * loaded, and the structure is re-aligned to its record on a later tick ({@link #serveRealignment}).
     */
    static void realignLater(ServerLevel level, BlockPos origin) {
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
        var stale = StructureRefresh.STALE.get(level);
        int checked = 0;
        for (var it = queue.entrySet().iterator(); it.hasNext() && checked < limit && System.nanoTime() - deadline < 0; ) {
            var entry = it.next();
            var origin = entry.getKey();
            var record = world.structure(EquipmentStructure.position(level, origin));
            if (record == null) {
                it.remove();
                continue;
            }
            var box = StructureRefresh.corner(origin, record.shape());
            if (!StructureRefresh.loaded(level, origin, box)) {
                it.remove();
                continue;
            }
            var high = StructureRefresh.max(box, entry.getValue());
            if (stale != null && stale.containsKey(origin) || !StructureRefresh.ready(level, origin.offset(-1, 0, -1), high.offset(1, 0, 1))) continue;
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
     * takes the record's shape and paint, and its registrations bind as at a load ({@link StructureRegistration#restoreFluidBinding}). A cell
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
        var survey = StructureRefresh.survey(level, origin, origin, high, shell -> shell.shape() != null && origin.equals(shell.origin()), true);
        var far = StructureRefresh.max(high, survey.far());
        if (!far.equals(high)) return new Realignment(null, far);
        com.wormzjl.icheme.runtime.assembly.AssemblyService.removed(level, vessel);
        var layout = ShapeRasterizer.layout(shape);
        var plan = EquipmentRefresh.plan(layout, survey.world(), new ShapeRasterizer.Cell(0, 0, 0));
        var touched = StructureRefresh.write(level, vessel, paint, name, plan, true);
        StructureRefresh.waitForLight(level, origin, high);
        // A shell cell that is no node of the record's shape stands for no registration.
        var nodes = new HashSet<>(EquipmentStructure.nodes(vessel));
        for (var cell : layout.shell()) {
            var at = origin.offset(cell.x(), cell.y(), cell.z());
            if (!nodes.contains(at) && level.getBlockEntity(at) instanceof EquipmentShellBlockEntity shell && shell.fluidIdentity() != 0) shell.releaseIdentity();
        }
        StructureRegistration.restoreFluidBinding(level, vessel);
        boolean blocked = !plan.blocked().isEmpty(), changed = false;
        for (long id : members(world, level, vessel)) changed |= world.hold(id, blocked);
        changed |= holdShaft(world, level, vessel, blocked);
        StructureRefresh.settle(level, vessel, touched);
        IChemE.LOGGER.warn("equipment_realign kind={} origin={} cleared={} placed={} blocked={} detail=rebuilt from the registry's record", shape.kind().name().toLowerCase(Locale.ROOT),
                origin.toShortString(), plan.clear().size(), plan.shell().size() + plan.interior().size(), plan.blocked().size());
        return new Realignment(new RegistryRealignment.Check(blocked ? RegistryRealignment.Outcome.HELD : RegistryRealignment.Outcome.PLACED, !blocked && changed), null);
    }

    /** Whether every cell of {@code vessel}'s layout stands as its own: a shell cell of its origin and shape, an interior cell. Stamps are the layout refresh's. */
    private static boolean stands(ServerLevel level, Vessel vessel) {
        var layout = ShapeRasterizer.layout(vessel.shape());
        for (var cell : layout.shell())
            if (!(level.getBlockEntity(vessel.origin().offset(cell.x(), cell.y(), cell.z())) instanceof EquipmentShellBlockEntity shell) || !EquipmentStructure.ours(shell, vessel.origin(), vessel.shape()))
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
        var ids = EquipmentStructure.heldAtNodes(world, level, vessel);
        if (HeatSides.unit(vessel.shape().kind()) == null) return ids;
        return EquipmentStructure.unitRecords(world, ids).values().stream().map(r -> r.device().id()).toList();
    }
}
