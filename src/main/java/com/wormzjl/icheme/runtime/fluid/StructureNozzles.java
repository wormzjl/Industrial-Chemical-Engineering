package com.wormzjl.icheme.runtime.fluid;

import com.wormzjl.icheme.science.equipment.EquipmentShape;
import com.wormzjl.icheme.science.equipment.NozzlePorts;
import java.util.*;

/**
 * The nozzles of the recorded structures that are no fluid device (a column, a reactor, a cyclone; batch
 * 2026-11-16-review-plant-sizing, NOZZLE_JOIN_REVIEW.md): what a pipe's drawn faces ({@link PhysicalFluidTopology#builtFaces})
 * meet where the registry has no device. A fluid device's ports are its registration's, so its structures are left out.
 * Kept from the ledger's structure records ({@link WorldTopologyLedger#structure}) as {@link ChunkBinding#indexStructure}
 * keeps their chunks: by dimension and chunk of each structure's box, its shape with it. A nozzle's flange cell is
 * found from its shape's ports ({@link NozzlePorts#at}) only when a pipe beside the structure is drawn, so the load builds
 * no shape's geometry. Reads no block and loads no chunk.
 */
final class StructureNozzles {
    private record Recorded(PhysicalFluidTopology.Position origin, EquipmentShape shape) {
        boolean holds(PhysicalFluidTopology.Position cell) {
            return cell.x() >= origin.x() && cell.x() < origin.x() + shape.sizeX() && cell.y() >= origin.y() && cell.y() < origin.y() + shape.sizeY()
                    && cell.z() >= origin.z() && cell.z() < origin.z() + shape.sizeZ();
        }
    }

    /** The recorded structures that are no fluid device, by dimension and by chunk of their box. */
    private final Map<String, Map<Long, List<Recorded>>> chunks = new HashMap<>();

    /** Adds or removes the structure at {@code origin}; a fluid device's structure is not kept. */
    void index(PhysicalFluidTopology.Position origin, WorldTopologyLedger.Structure structure, boolean add) {
        var shape = structure.shape();
        if (shape.fluidDevice()) return;
        var byChunk = chunks.computeIfAbsent(origin.dimension(), k -> new HashMap<>());
        var recorded = new Recorded(origin.withLane(0), shape);
        for (int x = origin.x() >> 4; x <= (origin.x() + shape.sizeX() - 1) >> 4; x++)
            for (int z = origin.z() >> 4; z <= (origin.z() + shape.sizeZ() - 1) >> 4; z++) {
                long chunk = net.minecraft.world.level.ChunkPos.asLong(x, z);
                if (add) byChunk.computeIfAbsent(chunk, k -> new ArrayList<>()).add(recorded);
                else {
                    var at = byChunk.get(chunk);
                    if (at != null && at.remove(recorded) && at.isEmpty()) byChunk.remove(chunk);
                }
            }
    }

    /** The origin of the recorded structure (no fluid device) whose box holds {@code cell}, or null. */
    PhysicalFluidTopology.Position origin(PhysicalFluidTopology.Position cell) {
        var found = recorded(cell);
        return found == null ? null : found.origin();
    }

    /**
     * Whether {@code cell} (lane 0) is the flange cell of a nozzle of a recorded structure that is no fluid device, the
     * nozzle facing {@code outward}: a pipe at {@code cell + outward} meets it through its face towards {@code cell}.
     */
    boolean faces(PhysicalFluidTopology.Position cell, PhysicalFluidTopology.Direction outward) {
        if (cell.lane() != 0) return false;
        var found = recorded(cell);
        if (found == null) return false;
        var o = found.origin();
        return NozzlePorts.at(found.shape(), cell.x() - o.x(), cell.y() - o.y(), cell.z() - o.z(), outward.x, outward.y, outward.z) != null;
    }

    private Recorded recorded(PhysicalFluidTopology.Position cell) {
        var byChunk = chunks.get(cell.dimension());
        if (byChunk == null) return null;
        var at = byChunk.get(net.minecraft.world.level.ChunkPos.asLong(cell.x() >> 4, cell.z() >> 4));
        if (at == null) return null;
        for (var r : at) if (r.holds(cell)) return r;
        return null;
    }
}
