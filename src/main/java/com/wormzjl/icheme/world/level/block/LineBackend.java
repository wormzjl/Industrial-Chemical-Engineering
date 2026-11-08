package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.runtime.fluid.FluidWorldAuthority;

import net.minecraft.core.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Domain snapshots and registration only. Backends do not admit commands, search routes, merge
 * edits or refund items.
 */
public interface LineBackend {
    record Host(
            BlockPos cell,
            int lane,
            long identity,
            double bore,
            int joints,
            int arms,
            long points,
            int facing,
            int allowedFaces,
            boolean branchable,
            boolean automatic,
            boolean transitionAllowed,
            boolean fluidTransition) {
        public Host {
            cell = cell.immutable();
            if (lane < 0
                    || lane > com.wormzjl.icheme.science.equipment.RackGeometry.OPTIONS
                    || !Double.isFinite(bore)
                    || bore <= 0
                    || joints < 0
                    || joints > 63
                    || arms < 0
                    || arms > 63
                    || facing < 0
                    || facing > 5) throw new IllegalArgumentException("Invalid retained line");
        }

        public LineLayout.Site site() {
            return new LineLayout.Site(cell, lane);
        }

        public double outside() {
            return fluidTransition
                    ? com.wormzjl.icheme.science.equipment.PipeSizes.outside(bore)
                    : bore;
        }
    }

    record Socket(
            UUID id,
            BlockPos owner,
            Vec3 centre,
            BlockPos cell,
            Direction face,
            int point,
            LinePlacement.Lead lead,
            PipeAnchor.Kind kind) {
        public Socket(
                UUID id,
                BlockPos owner,
                Vec3 centre,
                BlockPos cell,
                Direction face,
                int point,
                LinePlacement.Lead lead) {
            this(id, owner, centre, cell, face, point, lead, PipeAnchor.Kind.NOZZLE);
        }
    }

    record PortResult(boolean recognized, Socket socket, PipeAnchor.Refusal refusal) {}

    List<Host> hosts(Level level, BlockPos cell, LineProfile profile);

    int openFaces(Level level, Host host, LineProfile profile);

    PortResult socket(Level level, LineTarget target, LineProfile.Settings settings);

    LinePlacement.Work register(
            Level level,
            FluidWorldAuthority world,
            LineLayout layout,
            LineProfile.Settings settings);

    void forgetLane(Level level, FluidWorldAuthority world, Host host);

    void refresh(Level level, Collection<BlockPos> cells);

    default List<Socket> socketsAt(
            Level level, FluidWorldAuthority world, BlockPos cell, LineProfile.Settings settings) {
        return List.of();
    }

    default UUID binding(Level level, Host host, int face) {
        return null;
    }

    default void registerSocket(Level level, FluidWorldAuthority world, Socket socket) {}

    default net.minecraft.network.chat.Component validateFacts(
            Level level, FluidWorldAuthority world, LineLayout layout) {
        return null;
    }

    static LineBackend of(LineProfile profile) {
        return profile == LineProfile.FLUID
                ? FluidLineBackend.INSTANCE
                : ConduitLineBackend.INSTANCE;
    }
}
