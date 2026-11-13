package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.science.equipment.LineSelection;

import net.minecraft.world.phys.shapes.*;


/**
 * Shared, bounded pipe/data/power selection shapes; render tessellation never controls
 * break-particle counts.
 */
public final class LineShapes {
    private record Key(int joints, int facing, int outside, long offsets) {}

    private static final com.wormzjl.icheme.science.equipment.BoundedCache<Key, VoxelShape> CACHE = new com.wormzjl.icheme.science.equipment.BoundedCache<>(2048);

    private LineShapes() {}

    public static VoxelShape shape(int joints, int facing, int outside, long offsets) {
        return CACHE.computeIfAbsent(
                new Key(LineSelection.joints(joints, facing), 0, outside, offsets),
                key -> {
                    var shape = Shapes.empty();
                    for (var b :
                            LineSelection.boxes(
                                    key.joints(), key.facing(), key.outside(), key.offsets()))
                        shape = Shapes.or(shape, Shapes.box(b[0], b[1], b[2], b[3], b[4], b[5]));
                    return shape.optimize();
                });
    }
}
