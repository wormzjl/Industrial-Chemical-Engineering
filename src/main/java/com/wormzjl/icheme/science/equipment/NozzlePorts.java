package com.wormzjl.icheme.science.equipment;

import org.jetbrains.annotations.Nullable;

/**
 * The one rule for "a pipe meets this nozzle" (batch 2026-11-16-review-plant-sizing, NOZZLE_JOIN_REVIEW.md): a structure's
 * nozzle is met from the cell in front of its flange face, by a pipe whose joint faces back at it. Every reader of a
 * structure's nozzles as fluid ports asks here: a shell cell's port, the assembly walk, a column's start and the fluid
 * world's nozzle index that draws a pipe joined to a structure that is no fluid device.
 */
public final class NozzlePorts {
    private NozzlePorts() {
    }

    /**
     * The port of {@code shape} whose flange face is the cell {@code (x, y, z)} (relative to the structure's origin) and
     * whose outward step is {@code (dx, dy, dz)}, or null: the nozzle a pipe at {@code (x + dx, y + dy, z + dz)} meets
     * through its face towards {@code (-dx, -dy, -dz)}.
     */
    public static ShapeRasterizer.@Nullable Port at(EquipmentShape shape, int x, int y, int z, int dx, int dy, int dz) {
        for (var p : ShapeRasterizer.ports(shape))
            if (p.cell().x() == x && p.cell().y() == y && p.cell().z() == z && p.dx() == dx && p.dy() == dy && p.dz() == dz) return p;
        return null;
    }

    /** The cell in front of {@code port}'s flange face, relative to the structure's origin: where the pipe that meets it stands. */
    public static ShapeRasterizer.Cell front(ShapeRasterizer.Port port) {
        return new ShapeRasterizer.Cell(port.cell().x() + port.dx(), port.cell().y() + port.dy(), port.cell().z() + port.dz());
    }
}
