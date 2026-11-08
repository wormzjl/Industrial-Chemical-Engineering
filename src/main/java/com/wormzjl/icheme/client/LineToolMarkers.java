package com.wormzjl.icheme.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;
import java.util.function.ToDoubleFunction;

/**
 * Common pipe/data/power marker geometry, bounds and outline ownership. A frame of marks is drawn
 * between {@link #begin} and {@link #end}; a slot not drawn in the frame is removed. Client thread
 * only.
 */
final class LineToolMarkers {
    static final int RANGE = 48,
            MAX_MARKS = 256,
            OPEN = 0xFFDD33,
            START = 0x55FF55,
            REFUSED = 0xFF5555;

    /** Outline widths: marks and slots are thin, the start, route and removal outlines bold. */
    static final float THIN = 1 / 32F, BOLD = 1 / 16F;

    private final Set<Object> slots = new HashSet<>();
    private Set<Object> shown = new HashSet<>();

    void begin() {
        shown = new HashSet<>();
    }

    void box(Object slot, AABB bounds, int colour) {
        box(slot, bounds, colour, THIN);
    }

    void box(Object slot, AABB bounds, int colour, float width) {
        shown.add(slot);
        Outlines.box(slot, bounds, colour, width);
    }

    void line(Object slot, Vec3 from, Vec3 to, int colour) {
        line(slot, from, to, colour, THIN);
    }

    void line(Object slot, Vec3 from, Vec3 to, int colour, float width) {
        shown.add(slot);
        Outlines.line(slot, from, to, colour, width);
    }

    void end() {
        for (var slot : slots) if (!shown.contains(slot)) Outlines.remove(slot);
        slots.clear();
        slots.addAll(shown);
    }

    void clear() {
        for (var slot : slots) Outlines.remove(slot);
        slots.clear();
        shown.clear();
    }

    static <T> List<T> nearest(List<T> marks, ToDoubleFunction<T> distance) {
        return marks.stream()
                .filter(m -> distance.applyAsDouble(m) <= RANGE * RANGE)
                .sorted(Comparator.comparingDouble(distance))
                .limit(MAX_MARKS)
                .toList();
    }

    static AABB square(BlockPos cell, Direction face, double[] point, double half) {
        double[] low = new double[3], high = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            double extent = axis == face.getAxis().ordinal() ? .01 : half;
            low[axis] = point[axis] - extent;
            high[axis] = point[axis] + extent;
        }
        return new AABB(low[0], low[1], low[2], high[0], high[1], high[2]).move(cell);
    }
}
