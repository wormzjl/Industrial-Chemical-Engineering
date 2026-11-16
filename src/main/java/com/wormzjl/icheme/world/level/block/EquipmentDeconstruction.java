package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.science.equipment.VesselPlacement;
import com.wormzjl.icheme.world.level.block.entity.EquipmentConstructorBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;

/**
 * Physical target selection is independent of the constructor's selected equipment kind and
 * dimensions.
 */
public final class EquipmentDeconstruction {
    private EquipmentDeconstruction() {}

    /**
     * Nearest loaded structure crossing the constructor's placement line, at most 16 blocks ahead.
     * Never loads chunks.
     */
    public static @Nullable EquipmentStructure.Vessel target(
            Level level, BlockPos constructor, Direction facing, VesselPlacement placement) {
        var line =
                constructor.offset(
                        -facing.getStepZ() * placement.right(),
                        placement.up(),
                        facing.getStepX() * placement.right());
        var candidates = new TreeMap<BlockPos, EquipmentStructure.Vessel>();
        var visited = new HashSet<Long>();
        for (int step = 1; step <= VesselPlacement.MAX_FORWARD; step++) {
            var at = line.relative(facing, step);
            long key = net.minecraft.world.level.ChunkPos.asLong(at.getX() >> 4, at.getZ() >> 4);
            if (!visited.add(key)) continue;
            var chunk =
                    level.getChunkSource()
                            .getChunk(at.getX() >> 4, at.getZ() >> 4, ChunkStatus.FULL, false);
            if (!(chunk instanceof LevelChunk loaded)) continue;
            for (var entity : loaded.getBlockEntities().values())
                if (entity instanceof EquipmentShellBlockEntity shell && shell.shape() != null) {
                    var origin = shell.origin();
                    if (!candidates.containsKey(origin))
                        candidates.put(
                                origin, new EquipmentStructure.Vessel(origin, shell.shape()));
                }
        }
        EquipmentStructure.Vessel best = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (var vessel : candidates.values()) {
            var origin = vessel.origin();
            var shape = vessel.shape();
            double x = line.getX() + .5, y = line.getY() + .5, z = line.getZ() + .5;
            if (y < origin.getY() || y >= origin.getY() + shape.sizeY()) continue;
            if (facing.getAxis() == Direction.Axis.Z
                    && (x < origin.getX() || x >= origin.getX() + shape.sizeX())) continue;
            if (facing.getAxis() == Direction.Axis.X
                    && (z < origin.getZ() || z >= origin.getZ() + shape.sizeZ())) continue;
            double near =
                    switch (facing) {
                        case EAST -> origin.getX() - x;
                        case WEST -> x - origin.getX() - shape.sizeX();
                        case SOUTH -> origin.getZ() - z;
                        case NORTH -> z - origin.getZ() - shape.sizeZ();
                        default ->
                                throw new IllegalArgumentException(
                                        "Constructor facing is horizontal");
                    };
            if (near < 0 || near > VesselPlacement.MAX_FORWARD || near >= nearest) continue;
            nearest = near;
            best = vessel;
        }
        return best;
    }

    /**
     * Transient wire check; not a saved identity. A changed target must be reviewed again on the
     * constructor.
     */
    public static int stamp(EquipmentStructure.Vessel vessel) {
        return vessel.shape().toString().hashCode();
    }

    /**
     * Validates the entire removal before touching a cell. Ordinary pipes/cables and unrelated
     * blocks remain in place.
     */
    public static int remove(
            ServerPlayer player,
            EquipmentConstructorBlockEntity constructor,
            BlockPos expected,
            int stamp) {
        var checked = checked(player, constructor, expected, stamp, true);
        var level = player.serverLevel();
        var vessel = checked.vessel();
        for (var cell : ShapeRasterizer.fluidCells(vessel.shape()))
            if (level.getBlockEntity(vessel.origin().offset(cell.x(), cell.y(), cell.z()))
                    instanceof EquipmentShellBlockEntity shell)
                shell.instruments().recoverOnBreak(!player.isCreative());
        EquipmentStructure.dismantle(level, vessel);
        return checked.cells();
    }

    /** A structure the constructor's removal group may act on, and its protected cell count. */
    public record Checked(EquipmentStructure.Vessel vessel, int cells) {}

    /**
     * The shared check of the removal group's actions (Deconstruct, Repair): the player may use the constructor (same level,
     * within 8 blocks, may build, not a spectator, may interact), the structure on its line is the one the screen showed
     * ({@code expected} origin and {@code stamp}), and every cell is loaded and the player may interact with it. A removal
     * ({@code breaking}) also fires each cell's break event, so a protection mod may refuse it. Throws
     * {@link IllegalStateException} with {@code not_editable}, {@code changed}, {@code unloaded} or {@code protected}.
     */
    public static Checked checked(
            ServerPlayer player,
            EquipmentConstructorBlockEntity constructor,
            BlockPos expected,
            int stamp,
            boolean breaking) {
        var level = player.serverLevel();
        var at = constructor.getBlockPos();
        if (constructor.getLevel() != level
                || level.getBlockEntity(at) != constructor
                || player.isSpectator()
                || !player.mayBuild()
                || player.distanceToSqr(at.getCenter()) > 64
                || !level.mayInteract(player, at)) throw new IllegalStateException("not_editable");
        var vessel = target(level, at, constructor.facing(), constructor.design().placement());
        if (vessel == null || !vessel.origin().equals(expected) || stamp(vessel) != stamp)
            throw new IllegalStateException("changed");
        var layout = ShapeRasterizer.layout(vessel.shape());
        int count = 0;
        for (var cells : List.of(layout.shell(), layout.interior()))
            for (var cell : cells) {
                var position = vessel.origin().offset(cell.x(), cell.y(), cell.z());
                if (!level.hasChunkAt(position)) throw new IllegalStateException("unloaded");
                var state = level.getBlockState(position);
                if (!EquipmentProtection.cell(state)) continue;
                if (state.getBlock() instanceof EquipmentShellBlock
                        && (!(level.getBlockEntity(position)
                                        instanceof EquipmentShellBlockEntity shell)
                                || !vessel.origin().equals(shell.origin())))
                    throw new IllegalStateException("changed");
                if (!level.mayInteract(player, position)
                        || breaking
                                && net.neoforged.neoforge.common.CommonHooks.fireBlockBreak(
                                                level,
                                                player.gameMode.getGameModeForPlayer(),
                                                player,
                                                position,
                                                state)
                                        .isCanceled()) throw new IllegalStateException("protected");
                count++;
            }
        return new Checked(vessel, count);
    }
}
