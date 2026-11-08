package com.wormzjl.icheme.client;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;

import java.util.*;
import java.util.function.ToDoubleFunction;

/** Common pipe/data/power marker geometry, bounds and outline ownership. Client thread only. */
final class LineToolMarkers {
    static final int RANGE = 48,
            MAX_MARKS = 256,
            OPEN = 0xFFDD33,
            START = 0x55FF55,
            REFUSED = 0xFF5555;
    private final Set<Object> slots = new HashSet<>();
    private Set<Object> shown = new HashSet<>();

    void begin() {
        shown = new HashSet<>();
    }

    void box(Object slot, AABB bounds, int colour) {
        shown.add(slot);
        Outlines.box(slot, bounds, colour, 1 / 32F);
    }

    void line(
            Object slot,
            net.minecraft.world.phys.Vec3 from,
            net.minecraft.world.phys.Vec3 to,
            int colour) {
        shown.add(slot);
        Outlines.line(slot, from, to, colour, 1 / 32F);
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

    void adopt(Set<Object> current) {
        shown = new HashSet<>(current);
        end();
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
