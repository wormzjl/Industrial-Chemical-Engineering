package com.wormzjl.icheme.science.equipment;

import static org.junit.jupiter.api.Assertions.*;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Heading;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.design.UnitRatings;
import org.junit.jupiter.api.Test;

/**
 * The larger air cooler bays (batch 2026-11-15-equipment-visual-design, AIR_COOLER_BAY_PLAN.md, owner: "make max size
 * single AC model larger (as well as their duty) and correspond to realistic scale"): the table of section 4.1 as built,
 * every bay of the range before (to 3 by 6 m) as it was, and every listed bay buildable.
 */
class AirCoolerBayTest {
    private static final UnitDesign DESIGN = UnitDesign.of(Kind.AIR_COOLER);

    @Test
    void theLargerBaysRateAsThePlanSays() {
        // width, length, face m², fans, fan diameter m, least and default support m
        double[][] table = {{3, 6, 12.232, 2, 1.9, 3, 4}, {3, 12, 28.912, 3, 2.35, 3, 4}, {6, 6, 24.904, 1, 3.8, 5, 5}, {6, 12, 58.864, 2, 4.55, 6, 6}};
        for (var row : table) {
            double w = row[0];
            int l = (int) row[1];
            String bay = w + " by " + l;
            assertEquals(row[2], UnitRatings.bundleFace(w, l), 1e-9, bay);
            var fans = UnitRatings.fans(w, l);
            assertEquals((int) row[3], fans.count(), bay);
            assertEquals(row[4], fans.diameter(), 1e-9, bay);
            assertEquals((int) row[5], DESIGN.minSupport(w, l), bay);
            assertEquals((int) row[6], DESIGN.defaultSupport(w, l), bay);
            assertEquals(HeatSides.BARE_PER_FACE * row[2], HeatSides.airCoolerSurface(w, l, ProcessUnit.Bank.ONE), 1e-9, bay);
            assertTrue(UnitRatings.coverage(w, l) >= ProcessUnit.FAN_COVERAGE, bay);
        }
    }

    @Test
    void everyBayOfTheRangeBeforeKeepsItsRatingsSupportAndFanSpeed() {
        int before = 0;
        for (var bay : ProcessUnit.bays()) {
            double w = bay[0];
            int l = (int) bay[1];
            if (w > 3 + 1e-9 || l > 6) continue;
            before++;
            String name = w + " by " + l;
            assertEquals(1, UnitRatings.bundles(w), name);
            assertEquals((w - .22) * (l - 1 - ProcessUnit.header(l)), UnitRatings.bundleFace(w, l), 0, name);
            assertEquals(3, DESIGN.minSupport(w, l), name);
            assertEquals(4, DESIGN.defaultSupport(w, l), name);
            assertTrue(UnitRatings.fans(w, l).diameter() <= AirCoolerDesign.SPEED_FAN + 1e-9, name);
            assertEquals(ProcessUnit.FAN_SPEED, AirCoolerDesign.fanSpeed(UnitRatings.fans(w, l).diameter()), 0, name);
        }
        assertTrue(before > 10, "the bays before 2026-11-15 are still listed");
    }

    @Test
    void everyListedBayIsBuildableAndItsFansMakeWholeTurns() {
        for (double w = DESIGN.minSize(); w <= DESIGN.maxSize() + 1e-9; w += DESIGN.sizeStep()) {
            int least = DESIGN.minLength(w);
            assertTrue(UnitRatings.coverage(w, least) >= ProcessUnit.FAN_COVERAGE, "no least length at " + w + " m");
        }
        for (var bay : ProcessUnit.bays()) {
            double w = bay[0];
            int l = (int) bay[1];
            var shape = EquipmentShape.designed(Kind.AIR_COOLER, w, l, Heading.NORTH, DESIGN.minSupport(w, l), ProcessUnit.Bank.ONE, 0);
            assertNotNull(ProcessUnit.of(shape), w + " by " + l);
            double turns = AirCoolerDesign.fanSpeed(UnitRatings.fans(w, l).diameter()) * ProcessUnit.MOTION_PERIOD / 20;
            assertEquals(Math.rint(turns), turns, 1e-9, w + " by " + l + ": whole turns in the motion period");
        }
        assertTrue(ProcessUnit.bays().stream().anyMatch(b -> b[0] == 6 && b[1] == 12), "6 by 12 m is listed");
    }

    @Test
    void aTwoBundleBayHasTwoBundlesUnderOnePlenum() {
        var unit = ProcessUnit.of(EquipmentShape.designed(Kind.AIR_COOLER, 6, 12, Heading.NORTH, 6, ProcessUnit.Bank.ONE, 0));
        long fins = unit.mesh().facets().stream().filter(f -> f.material() == Mesh.Material.AC_FINS && f.normal()[1] > .9).count();
        assertEquals(2, fins, "two bundles' tops");
        long rotors = unit.motions.size();
        assertEquals(2, rotors, "two fans");
        assertEquals(UnitRatings.finWidth(6), 6 - .34, 1e-12);
    }
}
