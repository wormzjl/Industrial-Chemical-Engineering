package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.runtime.fluid.FluidRuntimeDiagnostics;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Registry first (batch 2026-11-03-save-corruption, REGISTRY_FIRST_PLAN.md 4.2 and 4.3): the fluid registry decides which
 * IChemE devices exist, and the world is re-aligned to it after their chunks load. A chunk load queues the chunk's
 * registrations ({@link FluidWorldAuthority#loadedChunk}); each level tick checks at most {@link #DEVICES} of them and stops
 * after {@link #BUDGET_NANOS}. It never loads a chunk and never solves.
 *
 * <p>A one-block device (a pipe, a tank, a generator, a void, a filter) is checked at its cell:</p>
 * <ul>
 *   <li>its block there: the block takes the registration's facing and fittings where they differ;</li>
 *   <li>the cell free, or another fluid device block in it: its block is placed, bound to the registration, and connected
 *       as the laying tool connects a line. The block it replaces drops nothing (decision S9) and leaves no registration;</li>
 *   <li>any other block there: the registration is held (decision S11), never the block overwritten.</li>
 * </ul>
 * A held registration whose block stands again is released. Rack lanes and structures follow in steps 5 and 6. A
 * registration is served once its own chunk is loaded: neighbours in a chunk that is not loaded keep their saved joints,
 * and their own load settles both sides (FluidDeviceBlock.connectedState).
 */
public final class RegistryRealignment {
    private RegistryRealignment() {}

    /** At most this many registrations checked a level tick (plan section 6). */
    static final int DEVICES = 32;
    /** And no more checks after this long within a level tick. */
    static final long BUDGET_NANOS = 1_000_000L;

    /** What one check did to the world. */
    public enum Outcome { NOTHING, PLACED, RESTATED, HELD }

    /** One check: what it did, and whether it released a hold. */
    public record Check(Outcome outcome, boolean released) {
        static final Check NONE = new Check(Outcome.NOTHING, false);
    }

    /** Serves {@code level}'s queue for this tick, from its level tick. */
    public static void serve(ServerLevel level) {
        var world = FluidWorldAuthority.find(level.getServer()).orElse(null);
        if (world == null) return;
        String dimension = level.dimension().location().toString();
        long start = System.nanoTime();
        int checked = 0, placed = 0, restated = 0, held = 0, released = 0;
        for (long id; checked < DEVICES && System.nanoTime() - start < BUDGET_NANOS && (id = world.nextRealignment(dimension)) != 0; checked++) {
            Check check;
            try {
                check = realign(level, world, id);
            } catch (RuntimeException failed) {
                IChemE.LOGGER.error("fluid_realign failed device={}", id, failed);
                continue;
            }
            switch (check.outcome()) {
                case PLACED -> placed++;
                case RESTATED -> restated++;
                case HELD -> held++;
                case NOTHING -> {}
            }
            if (check.released()) released++;
        }
        if (checked == 0) return;
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.realignChecked, checked);
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.realignPlaced, placed);
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.realignRestated, restated);
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.realignHeld, held);
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.realignReleased, released);
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.realignNanos, System.nanoTime() - start);
        if (placed + restated + held + released > 0)
            IChemE.LOGGER.info("fluid_realign dimension={} checked={} placed={} restated={} held={} released={}", dimension, checked, placed, restated, held, released);
    }

    /** Re-aligns the cell of registration {@code id} of {@code level}'s dimension to it. */
    public static Check realign(ServerLevel level, FluidWorldAuthority world, long id) {
        var record = world.registrations().get(id);
        if (record == null) return Check.NONE; // removed since its chunk loaded
        var d = record.device();
        var wanted = FluidDeviceBlock.stateFor(d);
        if (wanted == null || !d.position().dimension().equals(level.dimension().location().toString())) return Check.NONE;
        var at = new BlockPos(d.position().x(), d.position().y(), d.position().z());
        if (!level.hasChunkAt(at)) return Check.NONE; // unloaded since: its next load queues it again
        var present = level.getBlockState(at);
        Outcome outcome;
        if (present.getBlock() == wanted.getBlock()) outcome = restate(level, world, at, present, d) ? Outcome.RESTATED : Outcome.NOTHING;
        else if (present.isAir() || present.canBeReplaced() || present.getBlock() instanceof FluidDeviceBlock) {
            place(level, at, wanted, d);
            outcome = Outcome.PLACED;
        } else return world.hold(id, true) ? new Check(Outcome.HELD, false) : Check.NONE;
        return new Check(outcome, d.held() && world.hold(id, false));
    }

    /** The block of {@code d} stands at {@code at}: it takes {@code d}'s facing and fittings, and its identity. Whether anything changed. */
    private static boolean restate(ServerLevel level, FluidWorldAuthority world, BlockPos at, BlockState present, PhysicalFluidTopology.Device d) {
        var fitted = FluidDeviceBlock.fittings(present, d);
        // A lever's drawn position follows the commit, not a queued toggle (FluidDeviceBlockEntity.refreshGeometry).
        if (FluidDeviceBlock.hasShutoff(present) && FluidDeviceBlock.hasShutoff(fitted)) fitted = fitted.setValue(FluidDeviceBlock.SHUTOFF, present.getValue(FluidDeviceBlock.SHUTOFF));
        boolean changed = fitted != present;
        if (changed) {
            var connected = FluidDeviceBlock.connectedState(fitted, level, at);
            FluidDeviceBlock.quietly(() -> level.setBlock(at, connected, Block.UPDATE_ALL));
        }
        if (!(level.getBlockEntity(at) instanceof FluidDeviceBlockEntity entity)) return changed;
        if (entity.fluidIdentity() != d.id()) {
            entity.bind(world, d);
            changed = true;
        }
        if (changed) entity.refreshGeometry(d.geometry().diameter(), true);
        return changed;
    }

    /**
     * Places {@code d}'s block at {@code at}, which is free or holds another fluid device block. The block takes its joints
     * from the registration before any neighbour reads them, as the laying tool places a line (PipeLaying.lay); then it
     * connects, and its neighbours redraw.
     */
    private static void place(ServerLevel level, BlockPos at, BlockState wanted, PhysicalFluidTopology.Device d) {
        FluidDeviceBlock.quietly(() -> level.setBlock(at, wanted, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
        if (!(level.getBlockEntity(at) instanceof FluidDeviceBlockEntity entity)) return;
        entity.bindIdentity(d.id());
        var connected = FluidDeviceBlock.connectedState(level.getBlockState(at), level, at);
        FluidDeviceBlock.quietly(() -> level.setBlock(at, connected, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
        // The neighbours meet the new block, or part from the one it replaced, as at a placement.
        connected.updateNeighbourShapes(level, at, Block.UPDATE_CLIENTS);
        level.blockUpdated(at, connected.getBlock());
        entity.refreshGeometry(d.geometry().diameter(), true);
    }
}
