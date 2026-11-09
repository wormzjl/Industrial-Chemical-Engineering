package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.IChemE;
import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.control.ControlFacts;
import com.wormzjl.icheme.runtime.control.DataBus;
import com.wormzjl.icheme.runtime.fluid.FluidRuntimeDiagnostics;
import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.runtime.fluid.ShaftTrains;
import com.wormzjl.icheme.runtime.fluid.ShaftUnit;
import com.wormzjl.icheme.runtime.fluid.WorldTopologyLedger;
import com.wormzjl.icheme.runtime.instrument.Instrument;
import com.wormzjl.icheme.science.equipment.RackGeometry;
import com.wormzjl.icheme.science.equipment.SightGlassGeometry;
import com.wormzjl.icheme.science.equipment.CableMedium;
import com.wormzjl.icheme.world.level.block.entity.ConduitBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.FluidDeviceBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.PipeRackBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

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
 * A rack lane is checked at its rack cell: the cell takes the frame the registry holds for its lanes, or a free cell gets
 * a rack of that frame, and its copy rebuilds the lanes from their registrations; a lane the cell cannot carry is held.
 * A held registration whose block or lane stands again is released. A registration is served once its own chunk is
 * loaded. Its block draws the joints the network makes from the registry ({@link FluidDeviceBlock#connectedState}, batch
 * 2026-11-09-drawn-connections), so a neighbour in a chunk that is not loaded is known by its registration.
 *
 * <p>A shaft part (batch 2026-11-03-save-corruption, W5C_NEW_MODULES_PLAN.md C1) is checked at its cell after the devices,
 * in the same budget: a shaft block on its axis, a creative motor facing its end, a Create coupling with its shaft side
 * at its end (with Create loaded). Its block standing turned another way is turned back; a free cell gets its block, a shaft
 * at the size its train drew it. Any other block there holds the part, which joins no train until its block stands again
 * (option Q1). A structure's part is its structure's to re-align.</p>
 *
 * <p>A data or FE cable (W5c C2) is checked at its cell after the shaft parts: a cable block of its medium, or a rack
 * lane. A lost block comes back and its copy takes the fact at its load ({@code ControlService.loaded}), with its line
 * pieces; a cable block of the other medium is replaced. A lost rack comes back with the frame its lanes hold, or one
 * derived from them for lanes saved before frames. Any other block there holds the cable, which its bus leaves out.</p>
 *
 * <p>A structure the registry records (plan 4.4) is checked once every chunk of its box and of the blocks beside it is
 * block-ticking, after the layout refresh when that has it queued: one whose cells stand as its record says is left as it
 * is; any other is rebuilt to its record where the record puts it, its cells placed where the world is free; with a cell
 * another block holds, its registrations are held ({@link EquipmentStructure#serveRealignment}).</p>
 */
public final class RegistryRealignment {
    private RegistryRealignment() {}

    /** At most this many registrations checked a level tick (plan section 6). */
    static final int DEVICES = 32;
    /** And no more checks after this long within a level tick. */
    static final long BUDGET_NANOS = 1_000_000L;
    /** At most this many structures checked a level tick, in the time the devices left, and none after one rebuilt (plan 4.4). */
    static final int STRUCTURES = 8;

    /** What one check did to the world. */
    public enum Outcome { NOTHING, PLACED, RESTATED, HELD }

    /** One check: what it did, and whether it released a hold. */
    public record Check(Outcome outcome, boolean released) {
        static final Check NONE = new Check(Outcome.NOTHING, false);
    }

    /** Serves {@code level}'s queue for this tick, from its level tick: its devices, then its structures in the time left. */
    public static void serve(ServerLevel level) {
        var world = FluidWorldAuthority.find(level.getServer()).orElse(null);
        if (world == null) return;
        String dimension = level.dimension().location().toString();
        long start = System.nanoTime();
        var tally = new Tally();
        for (long id; tally.checked < DEVICES && System.nanoTime() - start < BUDGET_NANOS && (id = world.nextRealignment(dimension)) != 0; ) {
            try {
                tally.add(realign(level, world, id));
            } catch (RuntimeException failed) {
                tally.checked++;
                IChemE.LOGGER.error("fluid_realign failed device={}", id, failed);
            }
        }
        for (long id; tally.checked < DEVICES && System.nanoTime() - start < BUDGET_NANOS && (id = world.nextShaftRealignment(dimension)) != 0; ) {
            try {
                tally.add(shaft(level, world, id));
            } catch (RuntimeException failed) {
                tally.checked++;
                IChemE.LOGGER.error("fluid_realign failed shaft={}", id, failed);
            }
        }
        var controls = world.controls();
        for (DataBus.Site site; tally.checked < DEVICES && System.nanoTime() - start < BUDGET_NANOS && (site = controls.nextCable(dimension)) != null; ) {
            try {
                tally.add(cable(level, world, site));
            } catch (RuntimeException failed) {
                tally.checked++;
                IChemE.LOGGER.error("fluid_realign failed cable={}", site, failed);
            }
        }
        EquipmentStructure.serveRealignment(level, world, start + BUDGET_NANOS, STRUCTURES, tally::add);
        if (tally.checked == 0) return;
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.realignChecked, tally.checked);
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.realignPlaced, tally.placed);
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.realignRestated, tally.restated);
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.realignHeld, tally.held);
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.realignReleased, tally.released);
        FluidRuntimeDiagnostics.count(FluidRuntimeDiagnostics.realignNanos, System.nanoTime() - start);
        if (tally.placed + tally.restated + tally.held + tally.released > 0)
            IChemE.LOGGER.info("fluid_realign dimension={} checked={} placed={} restated={} held={} released={}", dimension, tally.checked, tally.placed, tally.restated, tally.held,
                    tally.released);
    }

    /** One serving tick's checks, by outcome: a structure counts as one, whatever number of cells it placed. */
    private static final class Tally {
        int checked, placed, restated, held, released;

        void add(Check check) {
            checked++;
            switch (check.outcome()) {
                case PLACED -> placed++;
                case RESTATED -> restated++;
                case HELD -> held++;
                case NOTHING -> {}
            }
            if (check.released()) released++;
        }
    }

    /** Re-aligns the cell of registration {@code id} of {@code level}'s dimension to it. */
    public static Check realign(ServerLevel level, FluidWorldAuthority world, long id) {
        var record = world.registrations().get(id);
        if (record == null) return Check.NONE; // removed since its chunk loaded
        var d = record.device();
        if (d.port() || !d.position().dimension().equals(level.dimension().location().toString())) return Check.NONE;
        var at = new BlockPos(d.position().x(), d.position().y(), d.position().z());
        if (!level.hasChunkAt(at)) return Check.NONE; // unloaded since: its next load queues it again
        if (d.position().lane() > 0) return lane(level, world, at, d);
        var wanted = FluidDeviceBlock.stateFor(d);
        if (wanted == null) return Check.NONE;
        var present = level.getBlockState(at);
        Outcome outcome;
        if (present.getBlock() == wanted.getBlock()) outcome = restate(level, world, at, present, d) ? Outcome.RESTATED : Outcome.NOTHING;
        else if (present.isAir() || present.canBeReplaced() || present.getBlock() instanceof FluidDeviceBlock) {
            place(level, world, at, wanted, d);
            outcome = Outcome.PLACED;
        } else return world.hold(id, true) ? new Check(Outcome.HELD, false) : Check.NONE;
        return new Check(outcome, d.held() && world.hold(id, false));
    }

    /**
     * Rack lane {@code d} (plan 4.2 item 3): its rack cell at {@code at} takes the frame the registry holds for the cell's
     * lanes, and its copy rebuilds the lanes from their registrations ({@link PipeRackBlockEntity#bind}). A free cell gets
     * a rack of that frame. A lane its cell cannot carry, or whose cell another block holds, is held.
     */
    private static Check lane(ServerLevel level, FluidWorldAuthority world, BlockPos at, PhysicalFluidTopology.Device d) {
        var present = level.getBlockState(at);
        var recorded = recordedFrame(world, d.position());
        Outcome outcome;
        if (present.getBlock() instanceof PipeRackBlock racks) {
            boolean changed = recorded != null && !recorded.equals(PipeRackBlock.frame(present));
            if (changed) level.setBlock(at, racks.stateFor(recorded), Block.UPDATE_ALL);
            if (!(level.getBlockEntity(at) instanceof PipeRackBlockEntity rack)) return Check.NONE;
            if (changed || !rack.carries(d.id())) {
                rack.bind(world);
                changed = true;
            }
            if (!rack.carries(d.id())) return hold(world, d);
            outcome = changed ? Outcome.RESTATED : Outcome.NOTHING;
        } else if (present.isAir() || present.canBeReplaced()) {
            var frame = recorded != null ? recorded : derivedFrame(world, d.position());
            if (frame == null) return hold(world, d);
            level.setBlock(at, ModBlocks.PIPE_RACK.get().stateFor(frame), Block.UPDATE_ALL);
            if (!(level.getBlockEntity(at) instanceof PipeRackBlockEntity rack)) return Check.NONE;
            rack.bind(world);
            if (!rack.carries(d.id())) return hold(world, d);
            outcome = Outcome.PLACED;
        } else return hold(world, d);
        return new Check(outcome, d.held() && world.hold(d.id(), false));
    }

    /** Re-aligns the cell of shaft part {@code id} of {@code level}'s dimension to it (W5c C1). */
    public static Check shaft(ServerLevel level, FluidWorldAuthority world, long id) {
        var trains = world.shafts();
        var u = trains.unit(id);
        if (u == null || !u.position().dimension().equals(level.dimension().location().toString())) return Check.NONE;
        var at = new BlockPos(u.position().x(), u.position().y(), u.position().z());
        if (!level.hasChunkAt(at)) return Check.NONE;
        var wanted = shaftState(u, trains.drawnSize(id));
        if (wanted == null) return Check.NONE;
        var present = level.getBlockState(at);
        Outcome outcome;
        if (present.getBlock() == wanted.getBlock()) {
            boolean turned = u.kind() == com.wormzjl.icheme.science.machine.ShaftNetwork.Kind.SHAFT ? present.getValue(ShaftBlock.AXIS) != wanted.getValue(ShaftBlock.AXIS)
                    : facing(present) != facing(wanted);
            if (turned) FluidDeviceBlock.quietly(() -> level.setBlock(at, wanted, Block.UPDATE_ALL));
            outcome = turned ? Outcome.RESTATED : Outcome.NOTHING;
        } else if (present.isAir() || present.canBeReplaced()) {
            FluidDeviceBlock.quietly(() -> level.setBlock(at, wanted, Block.UPDATE_ALL));
            outcome = Outcome.PLACED;
        } else {
            if (u.held()) return Check.NONE;
            trains.queue(new ShaftTrains.Hold(id, true));
            return new Check(Outcome.HELD, false);
        }
        if (u.held()) trains.queue(new ShaftTrains.Hold(id, false));
        return new Check(outcome, u.held());
    }

    /**
     * The block that stands for shaft part {@code u}, a shaft drawn at {@code size}: null for a structure's part, or for a
     * coupling without Create (the load forgets those parts, ShaftTrains.forget).
     */
    private static @Nullable BlockState shaftState(ShaftUnit u, int size) {
        return switch (u.kind()) {
            case SHAFT -> ModBlocks.SHAFT.get().defaultBlockState().setValue(ShaftBlock.AXIS, Direction.Axis.values()[u.axis()]).setValue(ShaftBlock.SIZE, size);
            case CREATIVE_MOTOR -> ModBlocks.CREATIVE_MOTOR.get().defaultBlockState().setValue(CreativeMotorBlock.FACING, end(u));
            case CREATE_COUPLING -> ShaftParts.couplingState(end(u));
            default -> null;
        };
    }

    /** The direction part {@code u}'s one shaft end leaves along. */
    private static Direction end(ShaftUnit u) {
        var e = u.ends().getFirst();
        return java.util.Objects.requireNonNull(Direction.fromDelta(e.dx(), e.dy(), e.dz()));
    }

    /** A creative motor's or a coupling's facing. */
    private static @Nullable Direction facing(BlockState state) {
        for (var p : state.getProperties()) if (p.getName().equals("facing") && p.getValueClass() == Direction.class) return (Direction) state.getValue(p);
        return null;
    }

    /** Re-aligns the cell of cable {@code site} of {@code level}'s dimension to its fact (W5c C2). */
    public static Check cable(ServerLevel level, FluidWorldAuthority world, DataBus.Site site) {
        var controls = world.controls();
        var facts = controls.facts();
        var cable = facts.cable(site);
        if (cable == null || !site.dimension().equals(level.dimension().location().toString())) return Check.NONE;
        var at = site.cell();
        if (!level.hasChunkAt(at)) return Check.NONE;
        var present = level.getBlockState(at);
        boolean held = facts.held(site);
        Outcome outcome;
        if (site.lane() == 0) {
            var wanted = (cable.geometry().medium() == CableMedium.POWER ? ModBlocks.POWER_CABLE : ModBlocks.DATA_CABLE).get();
            if (present.is(wanted)) {
                if (!(level.getBlockEntity(at) instanceof ConduitBlockEntity wire)) return Check.NONE;
                boolean differs = !wire.geometry().equals(cable.geometry()) || !wire.cable().endpoints().equals(cable.endpoints());
                if (differs) wire.configure(cable);
                outcome = differs ? Outcome.RESTATED : Outcome.NOTHING;
            } else if (present.isAir() || present.canBeReplaced() || present.getBlock() instanceof ConduitBlock) {
                FluidDeviceBlock.quietly(() -> level.setBlock(at, wanted.defaultBlockState(), Block.UPDATE_ALL));
                // Its copy took the fact at its load (ControlService.loaded), and queued its line pieces.
                if (level.getBlockEntity(at) instanceof ConduitBlockEntity wire && !wire.geometry().equals(cable.geometry())) wire.configure(cable);
                outcome = Outcome.PLACED;
            } else return holdCable(facts, site, held);
        } else if (present.getBlock() instanceof PipeRackBlock) {
            if (!(level.getBlockEntity(at) instanceof PipeRackBlockEntity rack)) return Check.NONE;
            if (!RackGeometry.valid(rack.rack(), site.lane() - 1, RackGeometry.width(cable.geometry().medium().outside()), cable.arms())) return holdCable(facts, site, held);
            boolean carried = rack.cables().contains(cable);
            if (!carried) controls.loaded(rack);
            outcome = carried ? Outcome.NOTHING : Outcome.RESTATED;
        } else if (present.isAir() || present.canBeReplaced()) {
            var frame = cableFrame(facts, site);
            if (frame == null) return holdCable(facts, site, held);
            // Its copy takes the cell's lanes at its load (ControlService.loaded).
            FluidDeviceBlock.quietly(() -> level.setBlock(at, ModBlocks.PIPE_RACK.get().stateFor(frame), Block.UPDATE_ALL));
            outcome = Outcome.PLACED;
        } else return holdCable(facts, site, held);
        if (held) facts.hold(site, false);
        return new Check(outcome, held);
    }

    private static Check holdCable(ControlFacts facts, DataBus.Site site, boolean held) {
        if (held) return Check.NONE;
        facts.hold(site, true);
        return new Check(Outcome.HELD, false);
    }

    /**
     * The frame of the rack cell of cable lane {@code site}: the first of its cell's lanes that holds one, or, for lanes
     * saved before frames, the first frame that takes every lane of the cell, straight cells first. Null where none does.
     */
    private static WorldTopologyLedger.@Nullable RackFrame cableFrame(ControlFacts facts, DataBus.Site site) {
        var lanes = new ArrayList<DataBus.Cable>();
        for (int lane = 1; lane <= RackGeometry.OPTIONS; lane++) {
            var c = facts.cable(new DataBus.Site(site.dimension(), site.cell(), lane));
            if (c != null) lanes.add(c);
        }
        for (var c : lanes) if (c.frame() != null) return c.frame();
        for (int corner = RackGeometry.STRAIGHT; corner <= RackGeometry.TEE; corner++)
            for (int facing = 2; facing < 6; facing++) {
                var rack = new RackGeometry.Rack(facing, corner);
                boolean takes = !lanes.isEmpty();
                for (var c : lanes) takes &= RackGeometry.valid(rack, c.site().lane() - 1, RackGeometry.width(c.geometry().medium().outside()), c.arms());
                if (takes) return PipeRackBlock.frame(rack);
            }
        return null;
    }

    private static Check hold(FluidWorldAuthority world, PhysicalFluidTopology.Device d) {
        return world.hold(d.id(), true) ? new Check(Outcome.HELD, false) : Check.NONE;
    }

    /** The registered lanes of the rack cell at {@code cell}, in option order. */
    private static List<PhysicalFluidTopology.Device> lanes(FluidWorldAuthority world, PhysicalFluidTopology.Position cell) {
        var out = new ArrayList<PhysicalFluidTopology.Device>();
        for (int option = 0; option < RackGeometry.OPTIONS; option++)
            world.at(cell.withLane(option + 1)).ifPresent(r -> out.add(r.device()));
        return out;
    }

    /** The frame the registry holds for the rack cell at {@code cell}: its lowest lane identity's, or null where none holds one. */
    private static WorldTopologyLedger.@Nullable RackFrame recordedFrame(FluidWorldAuthority world, PhysicalFluidTopology.Position cell) {
        WorldTopologyLedger.RackFrame frame = null;
        long lowest = Long.MAX_VALUE;
        for (var d : lanes(world, cell)) {
            var held = world.rackFrame(d.id());
            if (held != null && d.id() < lowest) {
                frame = held;
                lowest = d.id();
            }
        }
        return frame;
    }

    /**
     * A frame for a rack cell whose lanes were saved before the registry kept frames (schema 8 and older): the first that
     * takes every lane at its registered points, straight cells first. The lanes stand where they stood; where the points
     * leave the shape open, the cell may come back straight. Null where no frame takes them all.
     */
    private static WorldTopologyLedger.@Nullable RackFrame derivedFrame(FluidWorldAuthority world, PhysicalFluidTopology.Position cell) {
        var frames = PipeRackBlockEntity.frames(lanes(world, cell));
        return frames.isEmpty() ? null : PipeRackBlock.frame(frames.getFirst());
    }

    /** The block of {@code d} stands at {@code at}: it takes {@code d}'s facing, fittings and sight glass, and its identity. Whether anything changed. */
    private static boolean restate(ServerLevel level, FluidWorldAuthority world, BlockPos at, BlockState present, PhysicalFluidTopology.Device d) {
        var fitted = sightGlass(FluidDeviceBlock.fittings(present, d), world, d.id());
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
     * from the registration before any neighbour reads them, as the laying tool places a line (LinePlacement.apply); then it
     * connects, takes the sight glass its accessory record holds ({@link #sightGlass}), and its neighbours redraw.
     */
    private static void place(ServerLevel level, FluidWorldAuthority world, BlockPos at, BlockState wanted, PhysicalFluidTopology.Device d) {
        FluidDeviceBlock.quietly(() -> level.setBlock(at, wanted, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
        if (!(level.getBlockEntity(at) instanceof FluidDeviceBlockEntity entity)) return;
        entity.bindIdentity(d.id());
        var connected = sightGlass(FluidDeviceBlock.connectedState(level.getBlockState(at), level, at), world, d.id());
        FluidDeviceBlock.quietly(() -> level.setBlock(at, connected, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE));
        // The neighbours meet the new block, or part from the one it replaced, as at a placement.
        connected.updateNeighbourShapes(level, at, Block.UPDATE_CLIENTS);
        level.blockUpdated(at, connected.getBlock());
        entity.refreshGeometry(d.geometry().diameter(), true);
    }

    /**
     * {@code state} with the sight glass the accessory store holds for pipe {@code id}: the store is the fact, the block
     * state draws it (plan 4.3). The window takes its run's default axis, as {@link SightGlassGeometry.Key} draws a window
     * that lies along the run. A glass the block already shows is kept. A run that cannot carry one stays bare, and the
     * record stays for the break to refund.
     */
    private static BlockState sightGlass(BlockState state, FluidWorldAuthority world, long id) {
        if (state.getValue(FluidDeviceBlock.SIGHT_GLASS) || !FluidDeviceBlock.supportsSightGlass(state)) return state;
        if (world.instruments().fitting(id) != Instrument.Type.SIGHT_GLASS) return state;
        var window = SightGlassGeometry.towards(FluidDeviceBlock.connectionMask(state), state.getValue(FluidDeviceBlock.FACING).ordinal(), 0, 0, 0);
        return state.setValue(FluidDeviceBlock.SIGHT_GLASS, true).setValue(FluidDeviceBlock.SIGHT_GLASS_AXIS, Direction.Axis.valueOf(window.name()));
    }
}
