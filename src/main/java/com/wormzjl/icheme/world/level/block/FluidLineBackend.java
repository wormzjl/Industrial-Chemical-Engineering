package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.registry.ModBlocks;
import com.wormzjl.icheme.runtime.fluid.*;
import com.wormzjl.icheme.science.equipment.*;
import com.wormzjl.icheme.science.fluid.topology.TopologyCompiler;
import com.wormzjl.icheme.world.level.block.entity.*;

import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.*;

/**
 * Fluid registration, paint and fitting facts. All spatial planning/admission is owned by the line
 * framework.
 */
final class FluidLineBackend implements LineBackend {
    static final FluidLineBackend INSTANCE = new FluidLineBackend();

    private FluidLineBackend() {}

    public List<Host> hosts(Level level, BlockPos cell, LineProfile profile) {
        if (!level.hasChunkAt(cell)) return List.of();
        var be = level.getBlockEntity(cell);
        if (be instanceof PipeRackBlockEntity rack)
            return rack.lanes().stream()
                    .map(
                            l ->
                                    new Host(
                                            cell,
                                            l.option() + 1,
                                            l.identity(),
                                            l.bore(),
                                            l.joints(),
                                            l.arms(),
                                            rack.points(l),
                                            l.facing(),
                                            63,
                                            true,
                                            false,
                                            true,
                                            true))
                    .toList();
        if (!(be instanceof FluidDeviceBlockEntity pipe)
                || pipe.kind() != TopologyCompiler.Kind.PIPE) {
            var state = level.getBlockState(cell);
            if (state.getBlock() instanceof FluidDeviceBlock block
                    && block.kind() == TopologyCompiler.Kind.PIPE)
                return List.of(
                        new Host(
                                cell,
                                0,
                                0,
                                .05,
                                63,
                                0,
                                0,
                                state.getValue(FluidDeviceBlock.FACING).ordinal(),
                                63,
                                !FluidDeviceBlock.hasFitting(state),
                                true,
                                false,
                                true));
            return List.of();
        }
        var state = pipe.getBlockState();
        int faces = 0;
        for (var face : Direction.values())
            if (FluidDeviceBlock.face(state, face)) faces |= 1 << face.ordinal();
        return List.of(
                new Host(
                        cell,
                        0,
                        pipe.fluidIdentity(),
                        pipe.bore(),
                        pipe.joints(),
                        0,
                        pipe.lineOffsets(),
                        state.getValue(FluidDeviceBlock.FACING).ordinal(),
                        faces,
                        !FluidDeviceBlock.hasFitting(state),
                        pipe.joints() == 63,
                        !FluidDeviceBlock.hasFitting(state)
                                && pipe.instruments().installed().isEmpty(),
                        true));
    }

    public int openFaces(Level level, Host host, LineProfile profile) {
        var rack = (PipeRackBlockEntity) level.getBlockEntity(host.cell());
        for (int i = 0; i < rack.lanes().size(); i++)
            if (rack.lanes().get(i).option() + 1 == host.lane()) return rack.openFaces(i);
        return 0;
    }

    public PortResult socket(Level level, LineTarget target, LineProfile.Settings settings) {
        var state = level.getBlockState(target.clicked());
        var next = target.clicked().relative(target.face());
        if (state.getBlock() instanceof FluidDeviceBlock block
                && block.kind() != TopologyCompiler.Kind.PIPE) {
            if (!FluidDeviceBlock.face(state, target.face()))
                return new PortResult(true, null, PipeAnchor.Refusal.DEVICE_FACE);
            return new PortResult(
                    true,
                    new Socket(
                            null,
                            target.clicked(),
                            target.clicked().getCenter(),
                            next,
                            target.face().getOpposite(),
                            -1,
                            null,
                            PipeAnchor.Kind.DEVICE),
                    null);
        }
        if (level.getBlockEntity(target.clicked()) instanceof EquipmentShellBlockEntity shell
                && shell.nozzleFacing(target.face()) != null) {
            if (settings.outside() > PipeSizes.outside(shell.largestBore(target.face())) + 1e-9)
                return new PortResult(true, null, PipeAnchor.Refusal.NOZZLE_SIZE);
            return new PortResult(
                    true,
                    new Socket(
                            null,
                            target.clicked(),
                            target.clicked().getCenter(),
                            next,
                            target.face().getOpposite(),
                            -1,
                            null),
                    null);
        }
        return new PortResult(false, null, null);
    }

    public LinePlacement.Work register(
            Level level,
            FluidWorldAuthority world,
            LineLayout layout,
            LineProfile.Settings settings) {
        String dim = level.dimension().location().toString();
        var old = new HashMap<Long, Integer>();
        for (var edit : layout.hosts())
            if (!edit.before().automatic() && edit.joints() != edit.before().joints())
                old.put(edit.before().identity(), edit.joints());
        var lanes = new ArrayList<PipeRackBlockEntity.Lane>();
        var laid = new ArrayList<FluidWorldAuthority.Laid>();
        for (var cell : layout.cells()) {
            var site = cell.site();
            var position =
                    new PhysicalFluidTopology.Position(
                            dim,
                            site.cell().getX(),
                            site.cell().getY(),
                            site.cell().getZ(),
                            site.lane());
            var lane =
                    site.lane() == 0
                            ? null
                            : new PipeRackBlockEntity.Lane(
                                    0,
                                    site.lane() - 1,
                                    RackGeometry.width(PipeSizes.drawn(settings.bore())),
                                    cell.arms(),
                                    cell.joints(),
                                    settings.bore());
            lanes.add(lane);
            laid.add(
                    new FluidWorldAuthority.Laid(
                            position,
                            PhysicalFluidTopology.Direction.values()[
                                    lane == null ? cell.facing() : lane.facing()],
                            cell.joints(),
                            lane == null ? 0 : lane.width(),
                            lane == null ? 0 : cell.points()));
        }
        var records = world.lay(laid, settings.bore(), old);
        var edits = new ArrayList<LinePlacement.Edit>();
        var touched = new LinkedHashSet<BlockPos>();
        for (int i = 0; i < layout.cells().size(); i++) {
            int k = i;
            var cell = layout.cells().get(i);
            var lane = lanes.get(i);
            touched.add(cell.site().cell());
            if (lane != null)
                edits.add(
                        new LinePlacement.Edit(
                                cell.site().cell(),
                                null,
                                0,
                                entity ->
                                        ((PipeRackBlockEntity) entity)
                                                .addLane(
                                                        new PipeRackBlockEntity.Lane(
                                                                records.get(k).device().id(),
                                                                lane.option(),
                                                                lane.width(),
                                                                lane.arms(),
                                                                lane.joints(),
                                                                lane.bore(),
                                                                settings.paint(),
                                                                settings.secondary()))));
            else
                edits.add(
                        new LinePlacement.Edit(
                                cell.site().cell(),
                                ModBlocks.FLUID_PIPE
                                        .get()
                                        .defaultBlockState()
                                        .setValue(
                                                FluidDeviceBlock.FACING,
                                                Direction.values()[cell.facing()]),
                                Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE,
                                entity -> {
                                    var pipe = (FluidDeviceBlockEntity) entity;
                                    pipe.paint(settings.paint(), settings.secondary());
                                    pipe.bindIdentity(records.get(k).device().id());
                                }));
        }
        layout.hosts().forEach(e -> touched.add(e.before().cell()));
        return new LinePlacement.Work(
                edits,
                () -> {
                    for (var at : touched)
                        if (level.getBlockEntity(at) instanceof PipeRackBlockEntity rack)
                            rack.refresh(false);
                    for (var at : touched)
                        if (level.getBlockEntity(at) instanceof FluidDeviceBlockEntity pipe)
                            pipe.refreshGeometry(
                                    layout.cells().stream()
                                                    .anyMatch(c -> c.site().cell().equals(at))
                                            ? settings.bore()
                                            : pipe.bore(),
                                    true);
                    for (var at : touched)
                        if (level.getBlockEntity(at) instanceof PipeRackBlockEntity rack)
                            rack.refresh(true);
                });
    }

    public void forgetLane(Level level, FluidWorldAuthority world, Host host) {
        world.remove(host.identity());
        ((PipeRackBlockEntity) level.getBlockEntity(host.cell())).removeLane(host.identity());
    }

    public void refresh(Level level, Collection<BlockPos> cells) {
        for (var cell : cells)
            if (level.getBlockEntity(cell) instanceof PipeRackBlockEntity rack) rack.refresh(false);
        for (var cell : cells)
            if (level.getBlockEntity(cell) instanceof FluidDeviceBlockEntity pipe)
                pipe.refreshGeometry(pipe.bore(), true);
        for (var cell : cells)
            if (level.getBlockEntity(cell) instanceof PipeRackBlockEntity rack) rack.refresh(true);
    }

    public net.minecraft.network.chat.Component validateFacts(
            Level level, FluidWorldAuthority world, LineLayout layout) {
        String dim = level.dimension().location().toString();
        for (var edit : layout.hosts()) {
            var h = edit.before();
            var r =
                    world.at(
                                    new PhysicalFluidTopology.Position(
                                            dim,
                                            h.cell().getX(),
                                            h.cell().getY(),
                                            h.cell().getZ(),
                                            h.lane()))
                            .orElse(null);
            if (r == null
                    || r.device().kind() != TopologyCompiler.Kind.PIPE
                    || r.device().id() != h.identity()) return PipeLaying.message("anchor.fitting");
        }
        return null;
    }
}
