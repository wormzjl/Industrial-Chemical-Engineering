package com.wormzjl.icheme.runtime.assembly;

import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;
import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology;
import com.wormzjl.icheme.science.assembly.PortGraph.Fitting;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

/**
 * The walker's world (batch 2026-10-26-multi-machine-framework, plan section 6): the fluid registry's latest projected
 * registrations for the one-block devices, the shell block entities for the structures, in loaded chunks only.
 */
final class LevelGround implements AssemblyWalker.Ground {
    private final ServerLevel level;
    private final FluidWorldAuthority world;
    private final String dimension;

    LevelGround(ServerLevel level, FluidWorldAuthority world) {
        this.level = level;
        this.world = world;
        this.dimension = level.dimension().location().toString();
    }

    @Override
    public boolean loaded(BlockPos position) {
        return level.getChunkSource().getChunkNow(position.getX() >> 4, position.getZ() >> 4) != null;
    }

    @Override
    public AssemblyWalker.@Nullable Piece piece(BlockPos position) {
        return world.at(new PhysicalFluidTopology.Position(dimension, position.getX(), position.getY(), position.getZ()))
                .map(r -> piece(r.device())).orElse(null);
    }

    @Override
    public AssemblyWalker.@Nullable Shell shell(BlockPos position) {
        if (!(level.getBlockEntity(position) instanceof EquipmentShellBlockEntity shell) || shell.shape() == null) return null;
        return new AssemblyWalker.Shell(shell.origin(), shell.shape());
    }

    /**
     * A registered device as the walk reads it, or null for a vessel or constructed mover, which the walk meets at its
     * shell's nozzles. A control valve or an In-line Pump on a pipe is a fitting with its outlet, read from the law
     * the pipe compiles through ({@link PhysicalFluidTopology.Device#hydraulicKind}), whatever module or setting the
     * valve carries: the structure stage counts the valve body. Another accessory (a shutoff or check valve, a strainer)
     * or a one-block filter passes the line on. A one-block pump or compressor is an in-line mover. A one-block tank, a
     * generator and a void are outside.
     */
    static AssemblyWalker.@Nullable Piece piece(PhysicalFluidTopology.Device device) {
        if (!device.ports().isEmpty()) return null;
        var facing = Direction.valueOf(device.facing().name());
        int faces = 0;
        for (var d : PhysicalFluidTopology.Direction.values()) if (device.mechanicallyConnects(d)) faces |= 1 << Direction.valueOf(d.name()).ordinal();
        return switch (device.kind()) {
            case PIPE -> {
                Fitting fitting = switch (device.hydraulicKind()) {
                    case VALVE -> Fitting.CONTROL_VALVE;
                    case PUMP, COMPRESSOR -> Fitting.INLINE_PUMP;
                    default -> device.cutoff() != PhysicalFluidTopology.Cutoff.NONE || device.inlineFilter() ? Fitting.OTHER : null;
                };
                yield new AssemblyWalker.Piece(true, false, fitting, facing, faces);
            }
            case FILTER -> new AssemblyWalker.Piece(false, false, Fitting.OTHER, facing, faces);
            case PUMP, COMPRESSOR -> new AssemblyWalker.Piece(false, false, Fitting.INLINE_PUMP, facing, faces);
            case VALVE -> new AssemblyWalker.Piece(false, false, Fitting.CONTROL_VALVE, facing, faces);
            case RESERVOIR, GENERATOR, VOID -> new AssemblyWalker.Piece(false, true, null, facing, faces);
            // A unit's heat side always has ports: the check above returned.
            case HEAT_SIDE -> null;
        };
    }
}
