package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Heading;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.fluid.network.Flare;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The effects' part marks (batch 2026-11-10-custom-shader-system, W3 to W5): where a flame, a plume or a flue gas leaves,
 * design data read with {@link ShapeRasterizer#marks}, each a thin disc box under its mouth: a flare's {@code flare_tip}, a
 * fired heater's {@code stack_top_<i>}, an induced-draft tower's {@code fan_exhaust_<i>} per cell, a natural-draft tower's
 * {@code throat}. Marks add no cell: the layout table stands ({@code EquipmentLayoutGoldenTest}).
 */
class EffectMarksTest {
    /** {@code shape}'s marks named {@code prefix}: each inside the shape's box, a disc no more than 5 cm deep. */
    private static List<double[]> marks(EquipmentShape shape, String prefix) {
        var out = new ArrayList<double[]>();
        for (var e : ShapeRasterizer.marks(shape).entrySet()) {
            if (!e.getKey().startsWith(prefix)) continue;
            var box = e.getValue();
            String at = shape + " " + e.getKey() + " " + Arrays.toString(box);
            assertTrue(box[0] >= 0 && box[1] >= 0 && box[2] >= 0, at);
            assertTrue(box[3] <= shape.sizeX() && box[4] <= shape.sizeY() && box[5] <= shape.sizeZ(), at);
            assertTrue(box[0] < box[3] && box[2] < box[5], at);
            assertEquals(.05, box[4] - box[1], 1e-9, at);
            assertEquals(box[3] - box[0], box[5] - box[2], 1e-9, at + ": a round mouth's square box");
            out.add(box);
        }
        return out;
    }

    /** The names {@code prefix_0} to {@code prefix_<n-1>}, for marks of {@code shape} that count up. */
    private static int counted(EquipmentShape shape, String prefix) {
        var names = ShapeRasterizer.marks(shape).keySet().stream().filter(k -> k.startsWith(prefix)).toList();
        for (int i = 0; i < names.size(); i++) assertTrue(names.contains(prefix + i), shape + ": " + names);
        return names.size();
    }

    @Test void aFlaresTipMarkStandsOnTheWindshieldsMouth() {
        var kind = Kind.FLARE_STACK;
        for (double tip = ProcessUnit.minDiameter(kind); tip <= ProcessUnit.maxDiameter(kind) + 1e-9; tip += Flare.TIP_STEP)
            for (var heading : Heading.values()) {
                var shape = EquipmentShape.designed(kind, tip, 1, heading, 0, ProcessUnit.Bank.ONE);
                var marks = marks(shape, "flare_tip");
                assertEquals(1, marks.size(), shape.toString());
                var box = marks.getFirst();
                assertEquals(Flare.stackHeight(tip) + 1, box[4], 1e-9, shape + ": the top of the tip");
                assertEquals(.5, (box[0] + box[3]) / 2 % 1, 1e-9, shape + ": on the riser's centre line");
                assertEquals(.5, (box[2] + box[5]) / 2 % 1, 1e-9, shape + ": on the riser's centre line");
                assertTrue(box[3] - box[0] > tip, shape + ": the mouth is wider than the tip");
            }
    }

    /** A chimney's {@code stack_top} (batch 2026-11-10-chimney): one mouth at the top of the stack (a brick chimney's liner 1 m proud), on its centre line. */
    @Test void aChimneysTopMarkStandsOnItsMouth() {
        for (var kind : List.of(Kind.STEEL_STACK, Kind.BRICK_CHIMNEY))
            for (double d : new double[] {ProcessUnit.minDiameter(kind), ProcessUnit.maxDiameter(kind)})
                for (var heading : Heading.values()) {
                    int height = ProcessUnit.defaultLength(kind, d);
                    var shape = EquipmentShape.designed(kind, d, height, heading, 0, ProcessUnit.Bank.ONE);
                    var marks = marks(shape, "stack_top");
                    assertEquals(1, marks.size(), shape.toString());
                    var box = marks.getFirst();
                    assertEquals(kind == Kind.BRICK_CHIMNEY ? height + 1 : height, box[4], 1e-9, shape + ": the stack's mouth (a brick chimney's liner 1 m proud)");
                    assertTrue(box[4] <= shape.sizeY(), shape + ": inside the box");
                    assertEquals(shape.sizeX() / 2.0, (box[0] + box[3]) / 2, .5 + 1e-9, shape + ": on the centre line");
                    assertTrue(box[3] - box[0] < d + 1e-9, shape + ": inside the mouth");
                }
    }

    @Test void eachFiredHeaterStackHasItsTopMark() {
        for (var shape : List.of(EquipmentShape.unit(Kind.VERTICAL_HEATER, 4.5, 9, Heading.EAST, 5), EquipmentShape.unit(Kind.VERTICAL_HEATER, 4, 8, Heading.NORTH, 3),
                EquipmentShape.unit(Kind.CABIN_HEATER, 7, 20, Heading.WEST, 4), EquipmentShape.unit(Kind.CABIN_HEATER, 4, 25, Heading.SOUTH, 3))) {
            int n = counted(shape, "stack_top_");
            assertTrue(n >= 1, shape.toString());
            if (shape.kind() == Kind.VERTICAL_HEATER) assertEquals(1, n, shape + ": one stack");
            for (var box : marks(shape, "stack_top_")) assertTrue(box[4] > shape.sizeY() / 2.0, shape + ": high on the heater " + Arrays.toString(box));
        }
    }

    @Test void coolingTowersMarkEachFanStackOrTheirTopRing() {
        var bank = new TowerBank(3, 2);
        var induced = EquipmentShape.unit(Kind.INDUCED_DRAFT_COOLING_TOWER, 8, 2, Heading.NORTH, 0).withTowerBank(bank);
        assertEquals(bank.cells(), counted(induced, "fan_exhaust_"), "one per cell");
        var tower = induced.coolingTower();
        var centres = new HashSet<String>();
        for (var box : marks(induced, "fan_exhaust_")) {
            assertEquals(tower.height(), box[4], 1e-9, "the top of the fan stack");
            assertEquals(2 * 1.06 * tower.fanRadius(), box[3] - box[0], 1e-9, "the stack's mouth");
            centres.add((box[0] + box[3]) / 2 + "," + (box[2] + box[5]) / 2);
        }
        assertEquals(bank.cells(), centres.size(), "a mark over each cell");
        var natural = EquipmentShape.unit(Kind.NATURAL_DRAFT_COOLING_TOWER, 40, 52, Heading.NORTH, 0);
        var throat = marks(natural, "throat");
        assertEquals(1, throat.size());
        var box = throat.getFirst();
        assertEquals(natural.coolingTower().height(), box[4], 1e-9, "the top ring");
        assertEquals(natural.sizeX() / 2.0, (box[0] + box[3]) / 2, .5, "on the axis");
        assertTrue(box[3] - box[0] > .4 * 40 && box[3] - box[0] < 40, "the ring's opening: " + Arrays.toString(box));
        assertEquals(Map.of(), ShapeRasterizer.marks(EquipmentShape.unit(Kind.PUMP, .25, 1, Heading.NORTH, 0)), "a pump has none");
    }
}
