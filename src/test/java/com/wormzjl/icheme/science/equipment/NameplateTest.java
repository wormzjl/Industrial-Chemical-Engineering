package com.wormzjl.icheme.science.equipment;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * N1 (batch 2026-11-02-process-simulator; owner 2026-11-03: "Give them a nameplate on the model as well"): where a named
 * structure's plate sits, on every shape of {@link EquipmentLayoutGoldenTest#shapes()}, and a one-block device's tag.
 */
class NameplateTest {
    /**
     * Every plate lies flat on the model's own faces: its middle and corners within the plate's stand-off of the solid behind
     * them, inside the box, its face clear of every nozzle, deck, rail and ladder for half a metre; most face the front.
     * The shapes too small for the smallest plate are listed, so a change that loses one shows.
     */
    @Test
    void everyPlateLiesOnTheModelClearOfNozzlesAndLadders() {
        int front = 0;
        var none = new ArrayList<String>();
        for (var shape : EquipmentLayoutGoldenTest.shapes()) {
            var found = Nameplate.of(shape);
            if (found.isEmpty()) {
                none.add(shape.kind() + " " + shape.diameter());
                continue;
            }
            var p = found.get();
            String what = shape.kind() + " " + shape.diameter() + " " + p;
            if (p.facing() == shape.front()) front++;
            assertTrue(java.util.Arrays.stream(Nameplate.SIZES).anyMatch(s -> s[0] == p.width() && s[1] == p.height()), what);
            assertTrue(p.y() - p.height() / 2 > 0 && p.y() + p.height() / 2 < shape.sizeY(), what);
            int[] r = p.right();
            int ox = p.facing().dx, oz = p.facing().dz;
            for (int i = -1; i <= 1; i++)
                for (int j = -1; j <= 1; j++) {
                    double x = p.x() + r[0] * i * p.width() / 2, y = p.y() + j * p.height() / 2, z = p.z() + r[1] * i * p.width() / 2;
                    assertTrue(x >= -0.05 && x <= shape.sizeX() + 0.05 && z >= -0.05 && z <= shape.sizeZ() + 0.05, what + " corner outside the box");
                    assertFalse(ShapeRasterizer.inside(shape, x + ox * 0.004, y, z + oz * 0.004), what + " corner inside the solid");
                    boolean behind = false;
                    for (double d = 0; d <= Nameplate.STAND_OFF + Nameplate.LIFT + 0.02 && !behind; d += 0.005)
                        behind = ShapeRasterizer.inside(shape, x - ox * d, y, z - oz * d);
                    assertTrue(behind, what + " corner (" + i + ", " + j + ") stands off the model");
                    for (double d = 0.02; d <= Nameplate.CLEAR; d += 0.04)
                        assertFalse(ShapeRasterizer.inside(shape, x + ox * d, y, z + oz * d) || ShapeRasterizer.climbable(shape, x + ox * d, y, z + oz * d),
                                what + " something stands before the plate at " + d + " m");
                }
        }
        // The smallest motor and generator (0.75 m, batch 2026-11-03-rotating-machinery's W4) have no face clear of their
        // shaft ends and feet; their larger sizes carry a plate.
        assertEquals(java.util.List.of("DRUM 0.5", "EXCHANGER 0.5", "EJECTOR 0.05", "EJECTOR 0.45", "CYCLONE 0.3", "MOTOR 0.75", "GENERATOR 0.75"), none, "shapes with no plate");
        assertTrue(front >= 75, "plates facing the front: " + front);
    }

    /** A plate faces the structure's front where it can: a turned drum's plate turns with it. */
    @Test
    void aTurnedStructuresPlateTurnsWithIt() {
        for (var heading : EquipmentShape.Heading.values()) {
            var shape = EquipmentShape.drum(2, 4, EquipmentShape.Orientation.VERTICAL, heading, EquipmentShape.defaultSupport(2, EquipmentShape.Orientation.VERTICAL));
            var plate = Nameplate.of(shape).orElseThrow();
            assertEquals(heading, plate.facing());
            assertEquals(shape.support() + Nameplate.EYE, plate.y(), 1e-9, "at eye height above the skirt");
        }
    }

    /** A one-block device's tag stands across the run, on the side nearest the eye, off the casing, at the centre's height. */
    @Test
    void aTagStandsAcrossTheRunTowardsTheEye() {
        var tag = Nameplate.tag(0, 0.42, 3, -2);
        assertEquals(EquipmentShape.Heading.NORTH, tag.facing(), "a run along x shows north or south");
        assertEquals(-(0.42 + Nameplate.LIFT), tag.z(), 1e-9);
        assertEquals(0, tag.y(), 1e-9);
        assertEquals(EquipmentShape.Heading.EAST, Nameplate.tag(2, 0.3, 1, 5).facing(), "a run along z shows east or west");
        assertEquals(EquipmentShape.Heading.WEST, Nameplate.tag(1, 0.3, -4, 1).facing(), "a vertical run shows the side nearest the eye");
        assertEquals(0.5 + Nameplate.LIFT, Nameplate.tag(1, 0.9, 0, 3).z(), 1e-9, "never beyond the block's face");
        assertEquals(Nameplate.TAG_WIDTH, tag.width());
        assertEquals(Nameplate.TAG_HEIGHT, tag.height());
    }
}
