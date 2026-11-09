package com.wormzjl.icheme.world.level.block;

import com.wormzjl.icheme.science.equipment.DcsGeometry;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Collision and selection shapes of a horizontally facing block, built from boxes drawn for the
 * block facing north (block sixteenths, {@link DcsGeometry#turned}).
 */
public final class HorizontalShapes {
    private HorizontalShapes() {}

    /** Clockwise quarter turns from north: north 0, east 1, south 2, west 3. */
    public static int turns(Direction facing) {
        return switch (facing) {
            case NORTH -> 0;
            case EAST -> 1;
            case SOUTH -> 2;
            case WEST -> 3;
            default -> throw new IllegalArgumentException("Not a horizontal facing: " + facing);
        };
    }

    /** The union of {@code boxes} after {@code turns} quarter turns. */
    public static VoxelShape shape(List<double[]> boxes, int turns) {
        VoxelShape shape = Shapes.empty();
        for (var box : boxes) {
            var b = DcsGeometry.turned(box, turns);
            shape = Shapes.or(shape, Block.box(b[0], b[1], b[2], b[3], b[4], b[5]));
        }
        return shape.optimize();
    }

    /** One shape per horizontal facing. */
    public static Map<Direction, VoxelShape> byFacing(List<double[]> northBoxes) {
        var shapes = new EnumMap<Direction, VoxelShape>(Direction.class);
        for (var facing : Direction.Plane.HORIZONTAL)
            shapes.put(facing, shape(northBoxes, turns(facing)));
        return Map.copyOf(shapes);
    }
}
