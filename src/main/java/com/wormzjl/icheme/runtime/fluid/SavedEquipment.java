package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.runtime.fluid.PhysicalFluidTopology.Position;
import com.wormzjl.icheme.science.column.v3.V3ColumnInput;
import com.wormzjl.icheme.science.equipment.ColumnComponents;
import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.ShapeRasterizer;
import com.wormzjl.icheme.world.level.block.EquipmentStructure;
import com.wormzjl.icheme.world.level.block.EquipmentStructure.Vessel;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Built equipment shapes, available independently of which chunks currently stand. */
public final class SavedEquipment {
    private record Legacy(@Nullable EquipmentShape shape, Set<Long> searched) {}
    private final Map<Position, Legacy> legacy = new HashMap<>();

    SavedEquipment() {}

    /** The structure at its declared origin, reading shell tags only where old saves lack its registry fact. */
    public static @Nullable EquipmentShape at(FluidWorldAuthority world, Position origin) {
        return world.savedEquipment().at(origin, world::structure, pos -> world.savedBlockEntity(origin.dimension(), pos));
    }

    @Nullable EquipmentShape at(Position origin, Function<Position, WorldTopologyLedger.Structure> structures,
            Function<BlockPos, CompoundTag> shellTags) {
        var saved = structures.apply(origin);
        if (saved != null) return saved.shape();
        return legacy.computeIfAbsent(origin, key -> {
            var searched = new HashSet<Long>();
            var vessel = EquipmentStructure.at(new BlockPos(key.x(), key.y(), key.z()), pos -> {
                searched.add(ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4));
                return shell(pos, shellTags.apply(pos));
            });
            return new Legacy(vessel == null ? null : vessel.shape(), Set.copyOf(searched));
        }).shape();
    }

    /** A build, refresh or removal supersedes any reading of the old shell tags at this origin. */
    void invalidate(Position origin) {
        legacy.remove(origin);
    }

    /** Recheck legacy shapes and misses whose fallback search read this newly loaded chunk. */
    void invalidate(String dimension, long chunk) {
        legacy.entrySet().removeIf(e -> e.getKey().dimension().equals(dimension) && e.getValue().searched().contains(chunk));
    }

    /** The structure that owns a socket's shell cell, or null for a socket on a separate block. */
    public static @Nullable Vessel ownedBy(FluidWorldAuthority world, String dimension, BlockPos owner) {
        int chunkX = owner.getX() >> 4;
        int chunkZ = owner.getZ() >> 4;
        for (var origin : world.structuresInChunks(dimension, chunkX, chunkX, chunkZ, chunkZ)) {
            var saved = world.structure(origin);
            if (saved == null) continue;
            var shape = saved.shape();
            int x = owner.getX() - origin.x(), y = owner.getY() - origin.y(), z = owner.getZ() - origin.z();
            if (x >= 0 && x < shape.sizeX() && y >= 0 && y < shape.sizeY() && z >= 0 && z < shape.sizeZ()
                    && ShapeRasterizer.layout(shape).shell().contains(new ShapeRasterizer.Cell(x, y, z)))
                return new Vessel(new BlockPos(origin.x(), origin.y(), origin.z()), shape);
        }
        return shell(owner, world.savedBlockEntity(dimension, owner));
    }

    /** The declared origin and repaired shape of a saved shell cell, absent for a retired or unreadable kind. */
    public static @Nullable Vessel shell(BlockPos at, @Nullable CompoundTag tag) {
        if (tag == null || !"icheme:equipment_shell".equals(tag.getString("id")) || !tag.contains("Diameter")) return null;
        var cell = tag.getIntArray("Cell");
        if (cell.length != 3 || !EquipmentShellBlockEntity.validSavedCell(cell[0], cell[1], cell[2])) return null;
        var shape = EquipmentShellBlockEntity.readShape(tag).shape();
        return shape == null ? null : new Vessel(at.offset(-cell[0], -cell[1], -cell[2]), shape);
    }

    /** The design input using the built diameter and trays, absent for a shell without an operating point. */
    static @Nullable V3ColumnInput columnInput(@Nullable EquipmentShape shape) {
        var trays = shape == null ? null : shape.trays();
        var point = trays == null ? null : trays.operating();
        return point == null || !point.fits(trays) ? null : point.input(trays, shape.diameter());
    }

    /** The condenser of the built design, with the existing water-cooled default. */
    static ColumnComponents.Condenser columnCondenser(@Nullable EquipmentShape shape) {
        var trays = shape == null ? null : shape.trays();
        return trays == null || trays.components() == null
                ? ColumnComponents.Condenser.EXCHANGER : trays.components().condenser();
    }
}
