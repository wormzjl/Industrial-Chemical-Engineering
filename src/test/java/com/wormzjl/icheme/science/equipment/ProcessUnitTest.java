package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Heading;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Role;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Standalone process units (owner request and decisions 2026-10-16, batch 2026-10-15-custom-equipment-rendering,
 * {@code STANDALONE_UNITS_PLAN.md}; machines and heaters 2026-10-17, {@code MACHINES_HEATERS_PLAN.md}): their sizes and
 * ranges, where each nozzle faces, and that at every size each nozzle leaves a free block for its pipe, clear of the
 * saddles, frames and other nozzles.
 */
class ProcessUnitTest {
    /**
     * Every size a player can choose of every unit kind, at its least second size and the lowest support, and at its most
     * too where the kind has at most nine sizes, else at its smallest and largest (2026-10-18: every kind's design is
     * gated, the new ones as soon as they exist); odd and turned ones.
     */
    static List<EquipmentShape> everySize() {
        var shapes = new ArrayList<EquipmentShape>();
        for (var kind : Kind.values()) {
            if (!kind.unit()) continue;
            var sizes = ProcessUnit.sizes(kind);
            boolean few = sizes.size() <= 9;
            for (double size : sizes) {
                boolean end = size == ProcessUnit.minDiameter(kind) || size == ProcessUnit.maxDiameter(kind);
                int least = ProcessUnit.minLength(kind, size), most = ProcessUnit.maxLength(kind, size);
                for (int length : (few || end) && most > least ? new int[]{least, most} : new int[]{least})
                    shapes.add(EquipmentShape.unit(kind, size, length, Heading.NORTH, ProcessUnit.minSupport(kind, size, length)));
            }
        }
        shapes.add(EquipmentShape.unit(Kind.PUMP, .25, 1, Heading.EAST, 0));
        shapes.add(EquipmentShape.unit(Kind.PUMP, .45, 1, Heading.SOUTH, 0));
        shapes.add(EquipmentShape.unit(Kind.COMPRESSOR, .15, 6, Heading.SOUTH, 3));
        shapes.add(EquipmentShape.unit(Kind.COMPRESSOR, .45, 4, Heading.WEST, ProcessUnit.minSupport(Kind.COMPRESSOR, .45, 4)));
        shapes.add(EquipmentShape.unit(Kind.VERTICAL_HEATER, 4.5, 9, Heading.EAST, 5));
        shapes.add(EquipmentShape.unit(Kind.CABIN_HEATER, 7, 20, Heading.WEST, 4));
        shapes.add(EquipmentShape.unit(Kind.CABIN_HEATER, 4, 25, Heading.SOUTH, 3));
        shapes.add(EquipmentShape.unit(Kind.EXCHANGER, 1.25, 7, Heading.EAST, 5));
        shapes.add(EquipmentShape.unit(Kind.EJECTOR, .7, 1, Heading.SOUTH, 9));
        // Air cooler banks (owner request 2026-10-16): every count in parallel and in series of the smallest bay, the
        // largest bank of the largest bay, and an odd one turned.
        for (int p = 1; p <= ProcessUnit.Bank.MAX_PARALLEL; p++)
            for (int s = 1; s <= ProcessUnit.Bank.MAX_SERIES; s++)
                shapes.add(EquipmentShape.unit(Kind.AIR_COOLER, 1.5, 4, Heading.NORTH, 5, new ProcessUnit.Bank(p, s)));
        shapes.add(EquipmentShape.unit(Kind.AIR_COOLER, 3, 6, Heading.NORTH, 5, new ProcessUnit.Bank(ProcessUnit.Bank.MAX_PARALLEL, ProcessUnit.Bank.MAX_SERIES)));
        shapes.add(EquipmentShape.unit(Kind.AIR_COOLER, 2.5, 4, Heading.WEST, 7, new ProcessUnit.Bank(3, 2)));
        shapes.addAll(stillOptions());
        return shapes;
    }

    /**
     * The batch still's options (batch 2026-11-09-batch-distiller, W2) beyond the default one {@link #everySize} has at every
     * pot: every heater with the worm tub, no condenser and the column's fewest and most stages, at the least, middle and
     * largest pots; and two turned.
     */
    static List<EquipmentShape> stillOptions() {
        var shapes = new ArrayList<EquipmentShape>();
        for (double litres : new double[]{100, 500, 2000})
            for (var heater : BatchStillOptions.Heater.values())
                for (var options : List.of(new BatchStillOptions(heater, BatchStillOptions.Condenser.BUILT_IN, 0), new BatchStillOptions(heater, BatchStillOptions.Condenser.NONE, 0),
                        new BatchStillOptions(heater, BatchStillOptions.Condenser.BUILT_IN, BatchStillOptions.MIN_STAGES),
                        new BatchStillOptions(heater, BatchStillOptions.Condenser.BUILT_IN, BatchStillOptions.MAX_STAGES)))
                    if (!options.equals(BatchStillOptions.DEFAULT)) shapes.add(EquipmentShape.unit(Kind.BATCH_STILL, litres, 1, Heading.NORTH, 0).withStill(options));
        shapes.add(EquipmentShape.unit(Kind.BATCH_STILL, 750, 1, Heading.EAST, 0).withStill(new BatchStillOptions(BatchStillOptions.Heater.LIQUID, BatchStillOptions.Condenser.BUILT_IN, 5)));
        shapes.add(EquipmentShape.unit(Kind.BATCH_STILL, 1500, 1, Heading.WEST, 0).withStill(new BatchStillOptions(BatchStillOptions.Heater.ELECTRIC, BatchStillOptions.Condenser.NONE, 0)));
        return shapes;
    }

    /**
     * A batch still's nozzle faces by its options: the charge to the left (-x), the drain to the back; the top condenser's
     * distillate and vent to the front, its water in to the left and out at the back, with or without a column (owner,
     * 2026-11-09); without a condenser the vapour to the right; a liquid-fuel burner's fuel to the back.
     */
    private static Map<Role, String> stillSides(BatchStillOptions options) {
        var sides = new HashMap<Role, String>(Map.of(Role.CHARGE, "0-", Role.DRAIN, "2+"));
        if (!options.condenses()) sides.put(Role.VAPOUR_OUTLET, "0+");
        else {
            sides.putAll(Map.of(Role.DISTILLATE, "2-", Role.VENT, "2-", Role.WATER_OUTLET, "2+"));
            sides.put(Role.WATER_INLET, "0-");
        }
        if (options.heater() == BatchStillOptions.Heater.LIQUID) sides.put(Role.FUEL, "2+");
        return sides;
    }

    @Test
    void aStillsPotFollowsItsVolume() {
        // Owner B1 (BATCH_DISTILLER_PLAN.md): 100 to 2000 L, 0.50 m across at 100 L, 0.86 m at 500 L, 1.37 m at 2000 L.
        assertEquals(List.of(100., 150., 200., 300., 500., 750., 1000., 1500., 2000.), ProcessUnit.sizes(Kind.BATCH_STILL));
        assertEquals(.50, BatchStillDesign.potDiameter(100), 1e-12);
        assertEquals(.86, BatchStillDesign.potDiameter(500), 1e-12);
        assertEquals(1.37, BatchStillDesign.potDiameter(2000), 1e-12);
        for (double litres : ProcessUnit.sizes(Kind.BATCH_STILL))
            assertEquals(litres / 1000, EquipmentShape.unit(Kind.BATCH_STILL, litres, 1, Heading.NORTH, 0).volume(), 1e-12);
        // The options: a column of 2 to 8 stages or none, and only over the built-in condenser; the default on every other kind.
        assertEquals(BatchStillOptions.DEFAULT, EquipmentShape.unit(Kind.BATCH_STILL, 500, 1, Heading.NORTH, 0).still());
        for (Runnable bad : List.<Runnable>of(
                () -> new BatchStillOptions(BatchStillOptions.Heater.SOLID, BatchStillOptions.Condenser.BUILT_IN, 1),
                () -> new BatchStillOptions(BatchStillOptions.Heater.SOLID, BatchStillOptions.Condenser.BUILT_IN, 9),
                () -> new BatchStillOptions(BatchStillOptions.Heater.SOLID, BatchStillOptions.Condenser.NONE, 4),
                () -> EquipmentShape.unit(Kind.PUMP, .25, 1, Heading.NORTH, 0).withStill(BatchStillOptions.DEFAULT.withStages(4))))
            assertThrows(IllegalArgumentException.class, bad::run);
        assertEquals(0, BatchStillOptions.DEFAULT.withStages(4).withCondenser(BatchStillOptions.Condenser.NONE).stages(), "no condenser, no column");
        // Saved options read by name, a missing field at its default, a refused one repaired with a line.
        var repairs = new ArrayList<String>();
        assertEquals(new BatchStillOptions(BatchStillOptions.Heater.ELECTRIC, BatchStillOptions.Condenser.BUILT_IN, 6), BatchStillOptions.read("electric", "built_in", 6, repairs));
        assertEquals(BatchStillOptions.DEFAULT, BatchStillOptions.read(null, null, null, repairs));
        assertEquals(List.of(), repairs);
        assertEquals(new BatchStillOptions(BatchStillOptions.Heater.SOLID, BatchStillOptions.Condenser.NONE, 0), BatchStillOptions.read("peat", "none", 3, repairs));
        assertEquals(2, repairs.size(), repairs.toString());
    }

    /**
     * A still names the parts its animation moves (phase 3): a fired still its firing door, fire bed and flue top, a
     * liquid-fuel one its burner too, an electric one its lamp; every still its spout, a column its reflux valve; each
     * inside the box.
     */
    @Test
    void aStillNamesItsAnimatedParts() {
        var shapes = new ArrayList<EquipmentShape>();
        for (var shape : everySize()) if (shape.kind() == Kind.BATCH_STILL) shapes.add(shape);
        assertEquals(ProcessUnit.sizes(Kind.BATCH_STILL).size() + stillOptions().size(), shapes.size());
        for (var shape : shapes) {
            var options = shape.still();
            var want = new HashSet<String>(Set.of("spout"));
            if (!options.condenses()) want.clear();
            if (options.heater().fired()) want.addAll(Set.of("door", "fire_bed", "flue_top"));
            else want.add("lamp");
            if (options.heater() == BatchStillOptions.Heater.LIQUID) want.add("burner");
            if (options.column()) want.add("reflux_valve");
            var marks = ShapeRasterizer.marks(shape);
            assertEquals(want, marks.keySet(), shape.toString());
            for (var box : marks.values()) {
                assertTrue(box[0] >= 0 && box[1] >= 0 && box[2] >= 0, shape + " " + java.util.Arrays.toString(box));
                assertTrue(box[3] <= shape.sizeX() && box[4] <= shape.sizeY() && box[5] <= shape.sizeZ(), shape + " " + java.util.Arrays.toString(box));
                assertTrue(box[0] < box[3] && box[1] < box[4] && box[2] < box[5], shape + " " + java.util.Arrays.toString(box));
            }
        }
        assertEquals(Map.of(), ShapeRasterizer.marks(EquipmentShape.unit(Kind.PUMP, .25, 1, Heading.NORTH, 0)));
    }

    @Test
    void sizesFollowTheirRangesAndGrid() {
        // Off the grid, outside the range, tubes under three bundle diameters, a second air cooler size, supports too low.
        for (Runnable bad : List.<Runnable>of(
                () -> EquipmentShape.unit(Kind.EXCHANGER, .4, 2, Heading.NORTH, 2),
                () -> EquipmentShape.unit(Kind.EXCHANGER, 2.75, 12, Heading.NORTH, 2),
                () -> EquipmentShape.unit(Kind.EXCHANGER, .6, 2, Heading.NORTH, 2),
                () -> EquipmentShape.unit(Kind.EXCHANGER, 2, 5, Heading.NORTH, 2),
                () -> EquipmentShape.unit(Kind.EXCHANGER, 1, 13, Heading.NORTH, 2),
                () -> EquipmentShape.unit(Kind.EXCHANGER, 1, 4, Heading.NORTH, 1),
                () -> EquipmentShape.unit(Kind.KETTLE, 2.25, 10, Heading.NORTH, 2),
                () -> EquipmentShape.unit(Kind.KETTLE, 1, 11, Heading.NORTH, 2),
                () -> EquipmentShape.unit(Kind.AIR_COOLER, 4, 6, Heading.NORTH, 6),
                () -> EquipmentShape.unit(Kind.AIR_COOLER, 3, 7, Heading.NORTH, 6),
                () -> EquipmentShape.unit(Kind.AIR_COOLER, 3, 6, Heading.NORTH, 2),
                () -> EquipmentShape.unit(Kind.AIR_COOLER, 1, 4, Heading.NORTH, 5),
                () -> EquipmentShape.unit(Kind.AIR_COOLER, 3, 3, Heading.NORTH, 5),
                () -> EquipmentShape.unit(Kind.AIR_COOLER, 2.5, 3, Heading.NORTH, 5),
                () -> EquipmentShape.unit(Kind.EXCHANGER, 1, 4, Heading.NORTH, 2, new ProcessUnit.Bank(2, 1)),
                () -> new ProcessUnit.Bank(ProcessUnit.Bank.MAX_PARALLEL + 1, 1),
                () -> new ProcessUnit.Bank(1, 0),
                () -> EquipmentShape.unit(Kind.EJECTOR, 1.1, 1, Heading.NORTH, 2),
                () -> EquipmentShape.unit(Kind.EJECTOR, .33, 1, Heading.NORTH, 2),
                () -> EquipmentShape.unit(Kind.EJECTOR, 0, 1, Heading.NORTH, 2),
                () -> EquipmentShape.unit(Kind.EJECTOR, .3, 2, Heading.NORTH, 2),
                () -> EquipmentShape.unit(Kind.EJECTOR, .3, 1, Heading.NORTH, 17),
                () -> EquipmentShape.unit(Kind.DRUM, 2, 4, Heading.NORTH, 2),
                // Machines and heaters (2026-10-17): a pump off its grid, too large or raised; odd throws, a bore too large, a
                // foundation too low for the discharge bottles; a vertical heater too slender or its floor too low; a cabin off
                // its 1 m grid or too long.
                () -> EquipmentShape.unit(Kind.PUMP, .12, 1, Heading.NORTH, 0),
                () -> EquipmentShape.unit(Kind.PUMP, .8, 1, Heading.NORTH, 0),
                () -> EquipmentShape.unit(Kind.PUMP, .1, 1, Heading.NORTH, 1),
                () -> EquipmentShape.unit(Kind.COMPRESSOR, .3, 3, Heading.NORTH, 3),
                () -> EquipmentShape.unit(Kind.COMPRESSOR, 1.25, 2, Heading.NORTH, 3),
                () -> EquipmentShape.unit(Kind.COMPRESSOR, .45, 12, Heading.NORTH, 3),
                () -> EquipmentShape.unit(Kind.VERTICAL_HEATER, 10, 21, Heading.NORTH, 3),
                () -> EquipmentShape.unit(Kind.COMPRESSOR, .8, 6, Heading.NORTH, ProcessUnit.minSupport(Kind.COMPRESSOR, .8, 6) - 1),
                () -> EquipmentShape.unit(Kind.VERTICAL_HEATER, 4, 12, Heading.NORTH, 3),
                () -> EquipmentShape.unit(Kind.VERTICAL_HEATER, 4, 5, Heading.NORTH, 3),
                () -> EquipmentShape.unit(Kind.VERTICAL_HEATER, 4, 8, Heading.NORTH, 2),
                () -> EquipmentShape.unit(Kind.CABIN_HEATER, 4.5, 8, Heading.NORTH, 3),
                () -> EquipmentShape.unit(Kind.CABIN_HEATER, 6, 49, Heading.NORTH, 3)))
            assertThrows(IllegalArgumentException.class, bad::run);
        assertEquals(.3, ProcessUnit.snap(Kind.EJECTOR, .1 + .1 + .1), 0);
        // A new unit (2026-10-18, NEW_UNITS_PLAN.md) starts at its design's second size and support, inside its ranges at every size.
        for (var kind : Kind.values()) {
            if (!kind.unit()) continue;
            for (double size : ProcessUnit.sizes(kind)) {
                int length = ProcessUnit.defaultLength(kind, size), support = ProcessUnit.defaultSupport(kind, size, length);
                assertTrue(length >= ProcessUnit.minLength(kind, size) && length <= ProcessUnit.maxLength(kind, size), kind + " " + size + " default length " + length);
                assertTrue(support >= ProcessUnit.minSupport(kind, size, length) && support <= ProcessUnit.maxSupport(kind), kind + " " + size + " default support " + support);
            }
        }
        assertEquals(2, ProcessUnit.defaultLength(Kind.ADSORBER, 2));
        assertEquals(32, ProcessUnit.defaultLength(Kind.FCC_CONVERTER, 9.5));
        assertEquals(2.5, ProcessUnit.snap(Kind.EXCHANGER, 3), 0);
        assertEquals(.75, ProcessUnit.snap(Kind.KETTLE, .8), 0);
        assertEquals(3, ProcessUnit.minLength(Kind.EXCHANGER, .75));
        assertEquals(2, ProcessUnit.minLength(Kind.EXCHANGER, .5));
        // Physics of the plan: 1 m shell, 6 m tubes ≈ 450 m²; a kettle's shell 1.6 bundles; fans cover at least 40 % of the
        // bundle face (API 661); an ejector of 0.3 m suction is 2.5 m long.
        assertEquals(450, ProcessUnit.tubeSurface(1, 6), 1e-9);
        assertEquals(1.5, ProcessUnit.kettleShell(1), 0);
        assertEquals(3.25, ProcessUnit.kettleShell(2), 0);
        // Owner request 2026-10-16 ("allow smaller ACs"): every bay from 1.5 m wide, each with fans over 40 % of its bundle
        // face (API 661); 1.5 and 2 m wide from 3 m long, 2.5 and 3 m wide from 4 m (a 3 m bay of 3 m has 28 %). The 3 × 6 m bay keeps two
        // fans of 1.9 m, their rings 2.13 m across (owner report 2026-10-16: the rings of 2.05 m fans overlapped).
        var bays = ProcessUnit.bays();
        assertEquals(14, bays.size());
        for (var bay : bays) {
            assertTrue(ProcessUnit.coverage(bay[0], bay[1]) >= ProcessUnit.FAN_COVERAGE, bay[0] + " x " + bay[1]);
            EquipmentShape.unit(Kind.AIR_COOLER, bay[0], bay[1], Heading.NORTH, 5);
        }
        assertEquals(3, ProcessUnit.minLength(Kind.AIR_COOLER, 1.5));
        assertEquals(4, ProcessUnit.minLength(Kind.AIR_COOLER, 2.5));
        assertEquals(3, ProcessUnit.minLength(Kind.AIR_COOLER, 2));
        assertEquals(4, ProcessUnit.minLength(Kind.AIR_COOLER, 3));
        assertEquals(new ProcessUnit.Fans(2, 1.9), new ProcessUnit.Fans(ProcessUnit.fans(3, 6).count(), Math.round(ProcessUnit.fans(3, 6).diameter() * 100) / 100.0));
        assertEquals(2.49, ProcessUnit.ejectorLength(.3), 1e-9);
        // Owner request 2026-10-16 ("allow smaller ejectors"): suction from 0.05 m; one of 0.2 m or less stands on a single
        // post in the cell behind it, so it takes one cell by two; a larger one keeps its four-post frame.
        assertEquals(.05, ProcessUnit.minDiameter(Kind.EJECTOR), 0);
        for (double s : new double[]{.05, .1, .2}) {
            var small = EquipmentShape.unit(Kind.EJECTOR, s, 1, Heading.NORTH, 1);
            assertEquals(1, small.sizeX(), small.toString());
            assertEquals(2, small.sizeZ(), small.toString());
        }
        assertEquals(3, EquipmentShape.unit(Kind.EJECTOR, .25, 1, Heading.NORTH, 1).sizeX());
        // Machines and heaters (MACHINES_HEATERS_PLAN.md): a pump's suction one size up; liquid at 3 m/s in its discharge
        // (85 m³/h at 0.1 m), 33 kW at a 100 m head and 70 %, a TEFC motor of about 0.46 m; four 0.4 m cylinders at a 4 m/s
        // mean piston speed (600 rpm on a 0.2 m stroke) sweep about 7,200 m³/h, a 1.2 m bore runs about 300 rpm
        // (MACHINES_SCALE_RESEARCH.md); a vertical heater of D 4 × 8 m about 6.3 MW, a cabin of 6 × 12 m about 13.5 MW, of
        // 12 × 24 m 56.4 MW and a twin cell twice that at 48 m (12 × 40 m: two cells of 20 m, 93.1 MW); radiant heights from 1.5 to 2.75 diameters and 20 m at most; a heater's floor 3 m up; a pump at
        // grade.
        assertEquals(.15, ProcessUnit.pumpSuction(.1), 0);
        assertEquals(.55, ProcessUnit.pumpSuction(.4), 0);
        assertEquals(.95, ProcessUnit.pumpSuction(.75), 0);
        assertEquals(4771, ProcessUnit.pumpFlow(.75), 1);
        assertEquals(84.8, ProcessUnit.pumpFlow(.1), .1);
        assertEquals(33.0, ProcessUnit.pumpPower(.1), .1);
        assertEquals(.46, ProcessUnit.motorDiameter(ProcessUnit.pumpPower(.1)), .01);
        assertEquals(7238, ProcessUnit.displacement(.4, 4), 1);
        assertEquals(300, ProcessUnit.compressorSpeed(1.2), 1e-9);
        assertEquals(6.29, ProcessUnit.heaterDuty(Kind.VERTICAL_HEATER, 4, 8), .01);
        assertEquals(13.5, ProcessUnit.heaterDuty(Kind.CABIN_HEATER, 6, 12), .05);
        assertEquals(93.1, ProcessUnit.heaterDuty(Kind.CABIN_HEATER, 12, 40), .05);
        assertEquals(2 * ProcessUnit.heaterDuty(Kind.CABIN_HEATER, 12, 24), ProcessUnit.heaterDuty(Kind.CABIN_HEATER, 12, 48), 1e-9);
        assertEquals(List.of(1, 2), List.of(ProcessUnit.cabinCells(24), ProcessUnit.cabinCells(25)));
        assertEquals(48, ProcessUnit.maxLength(Kind.CABIN_HEATER, 12));
        assertEquals(6, ProcessUnit.minLength(Kind.VERTICAL_HEATER, 4));
        assertEquals(11, ProcessUnit.maxLength(Kind.VERTICAL_HEATER, 4));
        assertEquals(20, ProcessUnit.maxLength(Kind.VERTICAL_HEATER, 12));
        assertEquals(3, ProcessUnit.minSupport(Kind.VERTICAL_HEATER, 4, 8));
        assertEquals(0, ProcessUnit.maxSupport(Kind.PUMP));
        // A compressor's lowest foundation keeps its discharge bottles 0.3 m over grade, and is the lowest that does; its
        // discharge bottles clear the cylinders on the crankshaft's cell centre (batch 2026-11-03-rotating-machinery).
        for (double b = .15; b <= ProcessUnit.maxDiameter(Kind.COMPRESSOR) + 1e-9; b += .05)
            for (int throwsCount = 2; throwsCount <= ProcessUnit.MAX_THROWS; throwsCount += 2) {
                double bore = ProcessUnit.snap(Kind.COMPRESSOR, b);
                var c = ProcessUnit.Compressor.of(bore, throwsCount);
                int h = ProcessUnit.minSupport(Kind.COMPRESSOR, bore, throwsCount);
                assertTrue(c.clears(h) && (h == 1 || !c.clears(h - 1)), bore + " x " + throwsCount);
                assertTrue(c.dischargeAxis(h) - c.discharge() / 2 >= .3, bore + " x " + throwsCount);
                assertTrue(h + c.crank() - c.cylinder() / 2 - c.dischargeAxis(h) - c.discharge() / 2 >= .05, bore + " x " + throwsCount);
            }
    }

    @Test
    void theLowestBundleLeavesFreeAirUnderTheFans() {
        // A forced-draft fan takes its air from under its ring; half a fan diameter of free air there is a usual least.
        // Every bay on its lowest bundle (3 m, owner request 2026-10-16) keeps its rings' inlets that high (the 3 x 5 m
        // bay's 2.48 m fan least, at 0.6 D), and nothing of the unit lies under them between its motor and the ring's rim
        // but the beams carrying the motors.
        for (var bay : ProcessUnit.bays()) {
            int lowest = ProcessUnit.minSupport(Kind.AIR_COOLER, bay[0], bay[1]);
            var fans = ProcessUnit.fans(bay[0], bay[1]);
            var stack = ProcessUnit.FanStack.of(lowest, fans.diameter());
            assertTrue(stack.inlet() >= .5 * fans.diameter(), bay[0] + " x " + bay[1] + ": inlet " + stack.inlet());
            var unit = ProcessUnit.of(EquipmentShape.unit(Kind.AIR_COOLER, bay[0], bay[1], Heading.NORTH, lowest));
            double[] box = unit.box();
            // Across the bay at the ring's mid-radius, beside the motor and its beam, under the ring.
            double x = (box[0] + box[3]) / 2 + .35 * fans.diameter();
            for (double z = .75; z < bay[1] - .7; z += .1)
                for (double y = .05; y < stack.inlet() - .02; y += .1)
                    if (y < stack.motorBottom() - ProcessUnit.BEAM - .01 || y > stack.motorBottom() + .01)
                        assertFalse(unit.inside(new double[]{x, y, z}), bay[0] + " x " + bay[1] + " at " + y + ", " + z);
        }
    }

    @Test
    void nozzlesFaceWhereTheirFluidGoes() {
        // Tube side in at the channel's bottom and out at its top; a condensing shell side in on top and out underneath;
        // a kettle fed underneath, its vapour up, its product over the weir at the back; an air cooler's process in on top
        // of its inlet header and out underneath; an ejector's suction at the front, motive steam on top, discharge down; a
        // pump's suction at the front along its shaft and discharge up, an axially split one's suction to your right (-x)
        // and discharge to your left (owner decision 2026-10-17); a compressor's gas in on top of its suction header and
        // out at the front of its discharge header (one each, owner request 2026-10-17); a heater's convection and radiant
        // coils' inlets and outlets and its fuel gas towards the front.
        // The new units' faces (2026-10-18, NEW_UNITS_PLAN.md) as their designs place them; every unit kind is listed.
        var expected = Map.ofEntries(
                Map.entry(Kind.INDUCED_DRAFT_COOLING_TOWER,Map.of(Role.HOT_WATER_INLET,"2-",Role.COLD_WATER_OUTLET,"2+")),
                Map.entry(Kind.NATURAL_DRAFT_COOLING_TOWER,Map.of(Role.HOT_WATER_INLET,"2-",Role.COLD_WATER_OUTLET,"2+")),
                Map.entry(Kind.EXCHANGER, Map.of(Role.TUBE_INLET, "1-", Role.TUBE_OUTLET, "1+", Role.SHELL_INLET, "1+", Role.SHELL_OUTLET, "1-")),
                Map.entry(Kind.KETTLE, Map.of(Role.TUBE_INLET, "1+", Role.TUBE_OUTLET, "1-", Role.FEED, "1-", Role.VAPOUR_OUTLET, "1+", Role.LIQUID_OUTLET, "2+")),
                Map.entry(Kind.AIR_COOLER, Map.of(Role.PROCESS_INLET, "1+", Role.PROCESS_OUTLET, "1-")),
                Map.entry(Kind.EJECTOR, Map.of(Role.SUCTION, "2-", Role.MOTIVE, "1+", Role.DISCHARGE, "1-")),
                Map.entry(Kind.PUMP, Map.of(Role.SUCTION, "2-", Role.DISCHARGE, "1+")),
                Map.entry(Kind.COMPRESSOR, Map.of(Role.SUCTION, "1+", Role.DISCHARGE, "2-")),
                Map.entry(Kind.VERTICAL_HEATER, Map.of(Role.CONVECTION_INLET, "2-", Role.CONVECTION_OUTLET, "2-", Role.RADIANT_INLET, "2-", Role.RADIANT_OUTLET, "2-", Role.FUEL, "2-")),
                Map.entry(Kind.CABIN_HEATER, Map.of(Role.CONVECTION_INLET, "2-", Role.CONVECTION_OUTLET, "2-", Role.RADIANT_INLET, "2-", Role.RADIANT_OUTLET, "2-", Role.FUEL, "2-")),
                Map.entry(Kind.STIRRED_REACTOR, Map.of(Role.VAPOUR_OUTLET, "1+", Role.FEED, "1+", Role.NITROGEN, "1+", Role.VENT, "1+", Role.LIQUID_OUTLET, "1-",
                        Role.COOLANT_INLET, "2-", Role.COOLANT_OUTLET, "2+")),
                Map.entry(Kind.ADSORBER, Map.of(Role.FEED, "2-", Role.VENT, "2-", Role.PRODUCT, "2-", Role.NITROGEN, "2-", Role.REGENERATION_OUTLET, "2+", Role.DRAIN, "2+",
                        Role.REGENERATION_INLET, "2+")),
                Map.entry(Kind.TRICKLE_BED_REACTOR, Map.of(Role.FEED, "2-", Role.HYDROGEN, "0+", Role.EFFLUENT, "2-", Role.QUENCH, "0+")),
                Map.entry(Kind.EBULLATED_BED_REACTOR, Map.of(Role.EFFLUENT, "2-", Role.FEED, "2-", Role.HYDROGEN, "2+", Role.CATALYST_INLET, "1+", Role.CATALYST_OUTLET, "0+")),
                Map.entry(Kind.CENTRIFUGAL_COMPRESSOR, Map.of(Role.SUCTION, "1+", Role.DISCHARGE, "1+")),
                Map.entry(Kind.STEAM_TURBINE, Map.of(Role.STEAM, "0+", Role.EXHAUST, "1+")),
                // A flare stack's vent gas towards the front (batch 2026-11-09-flare-stack).
                Map.entry(Kind.FLARE_STACK, Map.of(Role.FLARE_GAS, "2-")),
                // A batch still's by its options (batch 2026-11-09-batch-distiller, stillSides); here the default still's.
                Map.entry(Kind.BATCH_STILL, stillSides(BatchStillOptions.DEFAULT)),
                Map.entry(Kind.MOTOR, Map.of()), Map.entry(Kind.GENERATOR, Map.of()), Map.entry(Kind.GEARBOX, Map.of()),
                Map.entry(Kind.CYCLONE, Map.of(Role.FEED, "2-", Role.VAPOUR_OUTLET, "2+", Role.SOLIDS_OUTLET, "1-")),
                Map.entry(Kind.FCC_CONVERTER, Map.of(Role.FEED, "2-", Role.STEAM, "2+", Role.AIR, "2-", Role.FUEL, "2-", Role.FLUE_GAS, "0-", Role.VAPOUR_OUTLET, "0+",
                        Role.CATALYST_INLET, "2-", Role.CATALYST_OUTLET, "2+")),
                Map.entry(Kind.DELAYED_COKER, Map.of(Role.FEED, "2-", Role.WATER, "2+", Role.STEAM, "0-", Role.VAPOUR_OUTLET, "0-", Role.QUENCH, "1+", Role.BLOWDOWN, "0+",
                        Role.RELIEF, "2+", Role.CUTTING_WATER, "0+", Role.SOLIDS_OUTLET, "1-")),
                Map.entry(Kind.FLUIDIZED_BED_BOILER, Map.of(Role.FEEDWATER, "0+", Role.STEAM, "0+", Role.BLOWDOWN, "0+", Role.DRAIN, "0-", Role.FUEL, "2-", Role.SOLIDS_INLET, "1+",
                        Role.SOLIDS_OUTLET, "1-")),
                Map.entry(Kind.FLUIDIZED_BED_GASIFIER, Map.of(Role.OXIDANT, "2-", Role.SYNGAS, "2+", Role.FEEDWATER, "0+", Role.BLOWDOWN, "0-", Role.STEAM, "1+", Role.NITROGEN, "1+",
                        Role.VENT, "1+", Role.SOLIDS_INLET, "1+", Role.SOLIDS_OUTLET, "1-")),
                Map.entry(Kind.GRATE_BOILER, Map.of(Role.STEAM, "1+", Role.FEEDWATER, "0+", Role.BLOWDOWN, "0+", Role.DRAIN, "1-", Role.FUEL, "2-", Role.SOLIDS_INLET, "1+",
                        Role.SOLIDS_OUTLET, "1-")),
                Map.entry(Kind.FIXED_BED_GASIFIER, Map.of(Role.OXIDANT, "2-", Role.STEAM, "2+", Role.FEEDWATER, "2-", Role.NITROGEN, "2-", Role.VENT, "1+", Role.SOLIDS_INLET, "1+",
                        Role.SOLIDS_OUTLET, "1-", Role.QUENCH, "1+", Role.LIQUID_OUTLET, "1-", Role.SYNGAS, "0+")));
        for (var kind : Kind.values()) assertEquals(kind.unit(), expected.containsKey(kind), kind + " has its nozzles' faces listed");
        for (var shape : everySize()) {
            var sides = shape.kind() == Kind.PUMP && shape.diameter() > ProcessUnit.OH2_MAX ? Map.of(Role.SUCTION, "1+", Role.DISCHARGE, "1+")
                    : shape.kind() == Kind.BATCH_STILL ? stillSides(shape.still()) : expected.get(shape.kind());
            var nozzles = shape.nozzles();
            assertEquals(sides.keySet(), new HashSet<>(nozzles.stream().map(EquipmentShape.Nozzle::role).toList()), shape.toString());
            assertEquals(sides.size(), nozzles.size(), shape.toString());
            for (var nozzle : nozzles) {
                assertEquals(sides.get(nozzle.role()), nozzle.axis() + (nozzle.sign() > 0 ? "+" : "-"), shape + " " + nozzle.role());
                assertTrue(nozzle.defaultOd() > 0 && nozzle.defaultOd() <= PipeSizes.MAX_OUTSIDE, shape + " " + nozzle.role());
            }
        }
    }

    /**
     * Owner rule 2026-10-17 ("piping connection should have 1 block spacing between them to prevent misconnection"):
     * pipes in blocks that share a face join, so no two of a shape's pipe blocks touch, by a face, an edge or a corner.
     */
    static void pipesApart(EquipmentShape shape) {
        var ports = ShapeRasterizer.ports(shape);
        for (int i = 0; i < ports.size(); i++)
            for (int j = i + 1; j < ports.size(); j++) {
                var p = ports.get(i);
                var q = ports.get(j);
                int dx = Math.abs(p.cell().x() + p.dx() - q.cell().x() - q.dx()), dy = Math.abs(p.cell().y() + p.dy() - q.cell().y() - q.dy()),
                        dz = Math.abs(p.cell().z() + p.dz() - q.cell().z() - q.dz());
                assertTrue(Math.max(dx, Math.max(dy, dz)) >= 2, shape + ": the pipes of " + shape.nozzles().get(p.nozzle()).role() + " and "
                        + shape.nozzles().get(q.nozzle()).role() + " touch");
            }
    }

    /**
     * A neck at a pipe's largest that touches its own unit, accepted (owner decisions 2026-10-21, "sometimes some overlap is
     * fine", and 2026-10-25: pipes at most 0.8 m; columns are left alone): its kind and role, on these diameters.
     */
    private record Touch(Kind kind, Role role, List<Double> diameters) {
        /** The listed diameter that is this shape's, for a nozzle of {@code role}; NaN if this touch is not its. */
        double at(EquipmentShape shape, Role role) {
            if (shape.kind() == kind && role == this.role)
                for (double d : diameters) if (Math.abs(d - shape.diameter()) < 1e-9) return d;
            return Double.NaN;
        }
    }

    /**
     * The accepted touches (batch 2026-10-21-floating-roof-tanks, 2026-10-25; they replace the room gate's baseline at
     * 4b842f30), each with what the neck touches and how deep; every other neck stands clear.
     */
    private static final List<Touch> TOUCHES = List.of(
            // Shell 1.75 m: 0.01 m into the channel rod; the 0.75 m neck clears with its shortened drawn hub.
            new Touch(Kind.EXCHANGER, Role.TUBE_OUTLET, List.of(1.75)),
            // Shell 1.75 m: 0.01 m into the rod; the 0.75 m neck clears with its shortened drawn hub.
            new Touch(Kind.EXCHANGER, Role.SHELL_INLET, List.of(1.75)),
            // Channel 0.75 m: the hub's base on its girth flanges (1.12 rb rod); 1 m: 0.02 m into them.
            new Touch(Kind.KETTLE, Role.TUBE_OUTLET, List.of(.75, 1.)),
            // The smallest heater's front legs stand 0.68 m apart: the neck 0.08 m into each one's casing and footing (owner item).
            new Touch(Kind.VERTICAL_HEATER, Role.FUEL, List.of(2.5)),
            // A 3 m column's top outlet is designed at a pipe's largest, which it therefore takes: it stands 0.21 m, its hub's base 0.03 m into the head.
            new Touch(Kind.COLUMN, Role.VAPOUR_OUTLET, List.of(3.)));

    /**
     * Owner decisions 2026-10-21 (a 1 m pipe has a 1 m root; other components give way) and 2026-10-27 (a column's nozzle
     * takes the largest pipe its place clears, {@link NozzleBore}): every nozzle's neck at its own largest pipe (a pipe's
     * largest but on a column) stands clear of the rest of its shape beyond {@link NozzleRoom#SURFACE} from where it leaves
     * the vessel, on every gated size, drums, columns and storage too, but the {@link #TOUCHES}; each of those still
     * touches (none stale).
     */
    @Test
    void everyNozzleNeckAtItsLargestStandsClear() {
        var shapes = new ArrayList<>(everySize());
        shapes.addAll(ShapeRasterizerTest.shapes());
        var failures = new ArrayList<String>();
        var met = new HashSet<String>();
        for (var shape : shapes)
            for (int k = 0; k < shape.nozzles().size(); k++) {
                var nozzle = shape.nozzles().get(k);
                if (NozzleRoom.clear(shape, k, PipeSizes.outside(NozzleBore.largest(shape, k)))) continue;
                var at = TOUCHES.stream().mapToDouble(t -> t.at(shape, nozzle.role())).filter(d -> !Double.isNaN(d)).findFirst();
                if (at.isPresent()) met.add(shape.kind() + " " + nozzle.role() + " " + at.getAsDouble());
                else failures.add(shape + " " + nozzle.role());
            }
        assertEquals(List.of(), failures);
        var accepted = new HashSet<String>();
        for (var t : TOUCHES) for (double d : t.diameters()) accepted.add(t.kind() + " " + t.role() + " " + d);
        assertEquals(accepted, met, "accepted touches that no longer touch");
    }

    /**
     * Owner decision 2026-10-27 (batch 2026-10-26-multi-machine-framework, NOZZLE_ROOM_GATE_REVIEW option (C)): a column's
     * nozzle takes the largest pipe, in {@link NozzleBore#STEP}s, whose neck stands clear by this test's own {@link
     * NozzleRoom} (the next step does not), never less than its design pipe; every other kind takes a pipe's largest. On
     * the gated columns: the 3 m column's reflux 0.55 m (a ladder beside it), the 1.5 m column's 0.7 m, the 0.5 m column's
     * side nozzles 0.25 m (the neck wider than the column's cladding), its top outlet a pipe's largest.
     */
    @Test
    void aColumnNozzleTakesTheLargestPipeItsPlaceClears() {
        var shapes = new ArrayList<>(everySize());
        shapes.addAll(ShapeRasterizerTest.shapes());
        var pinned = new java.util.TreeMap<String, Double>();
        for (var shape : shapes)
            for (int k = 0; k < shape.nozzles().size(); k++) {
                var nozzle = shape.nozzles().get(k);
                double largest = NozzleBore.largest(shape, k);
                String at = shape + " " + nozzle.role();
                if (shape.kind() != Kind.COLUMN) {
                    assertEquals(PipeSizes.MAX_BORE, largest, at);
                    continue;
                }
                assertTrue(largest >= nozzle.defaultOd() / 1.1 - 1e-9, at + ": under its design pipe");
                double next = Math.floor(largest / NozzleBore.STEP + 1e-9) * NozzleBore.STEP + NozzleBore.STEP;
                if (largest < PipeSizes.MAX_BORE - 1e-9) assertFalse(NozzleRoom.clear(shape, k, PipeSizes.outside(next)), at + ": a larger pipe clears");
                if (largest < PipeSizes.MAX_BORE - 1e-9) pinned.merge(shape.diameter() + " " + nozzle.role(), largest, Math::min);
                if (shape.diameter() == 3. && nozzle.role() == Role.VAPOUR_OUTLET) assertEquals(PipeSizes.MAX_BORE, largest, at + ": designed at a pipe's largest");
            }
        assertEquals(java.util.Map.of("0.5 FEED", .25, "0.5 PUMPAROUND_DRAW", .25, "0.5 REFLUX", .25, "0.5 RETURN", .25, "0.5 VAPOUR_RETURN", .25,
                "1.5 REFLUX", .7, "3.0 REFLUX", .55), rounded(pinned));
    }

    private static java.util.Map<String, Double> rounded(java.util.Map<String, Double> bores) {
        var out = new java.util.TreeMap<String, Double>();
        bores.forEach((k, v) -> out.put(k, Math.round(v * 1000) / 1000.0));
        return out;
    }

    /**
     * Owner request 2026-10-25 ("make drum/reactor bottom outlet rotatable"): a trickle-bed reactor's effluent leaves its
     * skirt to the front; to the left, its quench port then mirrored to the front; and to the back or the right, whichever
     * the ladder from grade leaves free, the other refused. Each turn keeps its pipes apart, its effluent's and quench's
     * necks clear at a pipe's largest, a free block for every pipe, its effluent line off the ladders, and its decks and
     * ladders as they were. No other unit's bottom outlet leaves a skirt sideways: theirs stay where they are.
     */
    @Test
    void aTrickleBedsEffluentTurnsWhereNoLadderComesDown() {
        var kind = Kind.TRICKLE_BED_REACTOR;
        int built = 0;
        for (double d = ProcessUnit.minDiameter(kind); d <= ProcessUnit.maxDiameter(kind) + 1e-9; d += ProcessUnit.diameterStep(kind))
            for (int beds = ProcessUnit.minLength(kind, d); beds <= ProcessUnit.maxLength(kind, d); beds++) {
                int least = ProcessUnit.minSupport(kind, d, beds);
                for (int s : new int[]{least, least + 1}) {
                    var front = EquipmentShape.unit(kind, d, beds, Heading.NORTH, s);
                    int sides = EquipmentShape.outlets(kind, d, beds, EquipmentShape.Orientation.VERTICAL, s);
                    assertEquals(0b0011, sides & 0b0011, front.toString());
                    assertEquals(1, Integer.bitCount(sides & 0b1100), front.toString());
                    var grade = ProcessUnit.of(front).ladders().stream().filter(l -> l.base() == 0).toList();
                    assertEquals(1, grade.size(), front.toString());
                    for (int q = 0; q < EquipmentShape.CONNECTION_SIDES; q++) {
                        int[] side = EquipmentShape.side(q);
                        boolean ladder = Math.abs(grade.getFirst().outX() - (side[0] == 0 ? side[1] : 0)) < 1e-9 && Math.abs(grade.getFirst().outZ() - (side[0] == 2 ? side[1] : 0)) < 1e-9;
                        String at = front + " turned " + q;
                        assertEquals(ladder, (sides >> q & 1) == 0, at);
                        double size = d;
                        int b = beds, skirt = s, turn = q;
                        if (ladder) {
                            assertThrows(IllegalArgumentException.class, () -> EquipmentShape.unit(kind, size, b, Heading.NORTH, skirt, ProcessUnit.Bank.ONE, turn), at);
                            continue;
                        }
                        var shape = EquipmentShape.unit(kind, d, beds, Heading.NORTH, s, ProcessUnit.Bank.ONE, q);
                        assertTrue(shape.outletTurns(), at);
                        int effluent = role(shape, Role.EFFLUENT), quench = role(shape, Role.QUENCH);
                        var e = shape.nozzles().get(effluent);
                        var qn = shape.nozzles().get(quench);
                        assertArrayEquals(side, new int[]{e.axis(), e.sign()}, at);
                        assertArrayEquals(q == 1 ? new int[]{2, -1} : new int[]{0, 1}, new int[]{qn.axis(), qn.sign()}, at);
                        pipesApart(shape);
                        assertTrue(NozzleRoom.clear(shape, effluent, PipeSizes.MAX_OUTSIDE), at + " effluent");
                        assertTrue(NozzleRoom.clear(shape, quench, PipeSizes.MAX_OUTSIDE), at + " quench");
                        var layout = ShapeRasterizer.layout(shape);
                        var occupied = new HashSet<ShapeRasterizer.Cell>(layout.shell());
                        occupied.addAll(layout.interior());
                        for (var port : ShapeRasterizer.ports(shape))
                            assertFalse(occupied.contains(new ShapeRasterizer.Cell(port.cell().x() + port.dx(), port.cell().y() + port.dy(), port.cell().z() + port.dz())),
                                    at + " " + shape.nozzles().get(port.nozzle()).role());
                        // The effluent's line, a pipe's largest round it, out to its face: no rung or cage.
                        int across = 2 - side[0];
                        for (double t = 0; t <= e.length() + 1e-9; t += .1)
                            for (double[] off : new double[][]{{0, 0}, {.4, 0}, {-.4, 0}, {0, .4}, {0, -.4}}) {
                                var p = e.base().clone();
                                p[side[0]] += side[1] * t;
                                p[across] += off[0];
                                p[1] += off[1];
                                assertFalse(climbable(shape, p), at + " effluent line on a ladder at " + t);
                            }
                        if (q != 0) walkedAndClimbed(shape);
                        built++;
                    }
                }
            }
        assertEquals(9 * 4 * 2 * 3, built);
        for (var shape : everySize())
            if (shape.kind() != kind)
                assertEquals(1, EquipmentShape.outlets(shape.kind(), shape.diameter(), shape.tangentLength(), shape.orientation(), shape.support()), shape.toString());
    }

    private static int role(EquipmentShape shape, Role role) {
        for (int k = 0; k < shape.nozzles().size(); k++) if (shape.nozzles().get(k).role() == role) return k;
        throw new AssertionError(shape + " has no " + role);
    }

    /**
     * Batch 2026-11-03-rotating-machinery (W4): every machine's shaft end has its coupling's half in the machine and a
     * free block beyond its coupling face for the shaft block, level, off the ground and a block clear of every nozzle's
     * pipe; a gearbox has its input and its output, every other machine one end. A motor's and a generator's terminal box
     * lies in the machine; an air cooler and an induced-draft tower have one too (W5), and a fired heater for its burners' air fans
     * (batch 2026-11-09-furnace-fuel-power); no other kind has one.
     */
    @Test
    void everyShaftEndMeetsAFreeBlockForItsShaft() {
        int machines = 0;
        for (var shape : everySize()) {
            var shafts = ShapeRasterizer.shafts(shape);
            var terminal = ShapeRasterizer.terminal(shape);
            boolean electric = shape.kind() == Kind.MOTOR || shape.kind() == Kind.GENERATOR || shape.kind() == Kind.AIR_COOLER || shape.kind() == Kind.INDUCED_DRAFT_COOLING_TOWER
                    || shape.kind() == Kind.VERTICAL_HEATER || shape.kind() == Kind.CABIN_HEATER || EquipmentServices.electricStill(shape);
            assertEquals(electric, terminal != null, shape.toString());
            if (!UnitDesign.of(shape.kind()).hasDuty() && shape.kind() != Kind.COMPRESSOR) {
                assertEquals(List.of(), shafts, shape.toString());
                continue;
            }
            machines++;
            assertEquals(shape.kind() == Kind.GEARBOX ? 2 : 1, shafts.size(), shape.toString());
            var layout = ShapeRasterizer.layout(shape);
            var occupied = new HashSet<ShapeRasterizer.Cell>(layout.shell());
            occupied.addAll(layout.interior());
            if (terminal != null) assertTrue(occupied.contains(terminal), shape + ": its terminal box at " + terminal);
            for (var shaft : shafts) {
                var block = shaft.block();
                assertEquals(0, shaft.dy(), shape + ": a level shaft");
                assertTrue(occupied.contains(shaft.cell()), shape + ": its coupling at " + shaft.cell());
                assertFalse(occupied.contains(block), shape + ": no room for its shaft at " + block);
                assertTrue(block.y() >= 0, shape + ": its shaft under the ground");
                for (var port : ShapeRasterizer.ports(shape)) {
                    int dx = Math.abs(block.x() - port.cell().x() - port.dx()), dy = Math.abs(block.y() - port.cell().y() - port.dy()),
                            dz = Math.abs(block.z() - port.cell().z() - port.dz());
                    assertTrue(Math.max(dx, Math.max(dy, dz)) >= 2, shape + ": its shaft touches the pipe of " + shape.nozzles().get(port.nozzle()).role());
                }
            }
        }
        assertTrue(machines > 200, machines + " machines");
    }

    @Test
    void everyNozzleLeavesAFreeBlockForItsPipe() {
        for (var shape : everySize()) {
            var layout = ShapeRasterizer.layout(shape);
            var occupied = new HashSet<ShapeRasterizer.Cell>(layout.shell());
            occupied.addAll(layout.interior());
            var ports = ShapeRasterizer.ports(shape);
            assertEquals(shape.nozzles().size(), ports.size(), shape.toString());
            for (var port : ports) {
                var role = shape.nozzles().get(port.nozzle()).role();
                var pipe = new ShapeRasterizer.Cell(port.cell().x() + port.dx(), port.cell().y() + port.dy(), port.cell().z() + port.dz());
                assertTrue(occupied.contains(port.cell()), shape + " " + role);
                assertFalse(occupied.contains(pipe), shape + " " + role + ": no room for a pipe at " + pipe);
                assertTrue(pipe.y() >= 0, shape + " " + role + " pipe under the ground");
                // The nozzle meets its pipe: its face on the block face, its centre line through the pipe block's (batch
                // 2026-11-09-flare-stack). The delayed coker's feed, steam and water stand 0.05 to 0.35 m off: an open item.
                if (shape.kind() == Kind.DELAYED_COKER) continue;
                var face = shape.transform().point(shape.nozzles().get(port.nozzle()).face());
                int axis = port.dx() != 0 ? 0 : port.dy() != 0 ? 1 : 2;
                int[] at = {pipe.x(), pipe.y(), pipe.z()};
                for (int a = 0; a < 3; a++)
                    if (a == axis) assertEquals(Math.rint(face[a]), face[a], 1e-9, shape + " " + role + ": its face off the block face");
                    else assertEquals(at[a] + .5, face[a], 1e-9, shape + " " + role + ": its centre line off its pipe's, axis " + a);
            }
            pipesApart(shape);
            // Flanges at their largest stand clear of each other.
            var nozzles = shape.nozzles();
            for (int i = 0; i < nozzles.size(); i++)
                for (int j = i + 1; j < nozzles.size(); j++)
                    assertFalse(overlap(flange(nozzles.get(i)), flange(nozzles.get(j))), shape + " " + nozzles.get(i).role() + " meets " + nozzles.get(j).role());
            // The enclosed insides (shells, channels, the kettle's cone, the plenum) are sealed by drawn cells.
            for (var cell : layout.interior())
                for (int[] d : new int[][]{{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}})
                    assertTrue(occupied.contains(new ShapeRasterizer.Cell(cell.x() + d[0], cell.y() + d[1], cell.z() + d[2])), shape + " interior " + cell + " touches the outside");
            // On the ground and filling its box.
            int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}, max = {-1, -1, -1};
            for (var cell : layout.shell()) {
                int[] c = {cell.x(), cell.y(), cell.z()};
                for (int a = 0; a < 3; a++) {
                    min[a] = Math.min(min[a], c[a]);
                    max[a] = Math.max(max[a], c[a]);
                }
            }
            occupiesPlacementFrame(shape, min, max);
        }
    }

    /** Empty edge cells retain established nozzle coordinates when tank access or compressor coolant is removed. */
    static void occupiesPlacementFrame(EquipmentShape shape, int[] min, int[] max) {
        int[] size = {shape.sizeX(), shape.sizeY(), shape.sizeZ()};
        boolean retained = shape.kind() == Kind.TANK || shape.kind() == Kind.CENTRIFUGAL_COMPRESSOR;
        for (int axis = 0; axis < 3; axis++) {
            int padding = retained && axis != 1 ? shape.kind() == Kind.TANK ? 2 : 1 : 0;
            assertTrue(min[axis] >= 0 && min[axis] <= padding, shape + " min axis " + axis + ": " + min[axis]);
            assertTrue(max[axis] < size[axis] && max[axis] >= size[axis] - 1 - padding, shape + " max axis " + axis + ": " + max[axis]);
        }
    }

    /** The box of a nozzle's largest flange in the frame: its thickness behind the face, its radius across. */
    private static double[][] flange(EquipmentShape.Nozzle nozzle) {
        double r = PipeSizes.flange(PipeSizes.MAX_OUTSIDE) / 2, t = PipeSizes.flangeThickness(PipeSizes.MAX_OUTSIDE);
        double[] face = nozzle.face(), lo = new double[3], hi = new double[3];
        for (int a = 0; a < 3; a++) {
            lo[a] = face[a] - r;
            hi[a] = face[a] + r;
        }
        int x = nozzle.axis();
        lo[x] = Math.min(face[x], face[x] - nozzle.sign() * t);
        hi[x] = Math.max(face[x], face[x] - nozzle.sign() * t);
        return new double[][]{lo, hi};
    }

    @Test
    void fanRingsKeepApartInsideThePlenum() {
        // Owner report 2026-10-16: two fans' rings overlapped. In every bay of a bank, rings side by side along the tubes
        // keep 0.02 m apart at their widest (flange and bellmouth, RING_OD rotor diameters), and each ring stays 0.05 m
        // inside the plenum's frame across the bay.
        for (var bay : ProcessUnit.bays()) {
            var shape = EquipmentShape.unit(Kind.AIR_COOLER, bay[0], bay[1], Heading.NORTH, 4, new ProcessUnit.Bank(2, 2));
            var hubs = ShapeRasterizer.moving(shape).stream().map(part -> part.motion().pivot()).toList();
            double ring = ProcessUnit.RING_OD * ProcessUnit.fans(bay[0], bay[1]).diameter();
            assertTrue(ring <= bay[0] - .32 + 1e-9, bay[0] + " x " + bay[1]);
            for (var a : hubs)
                for (var b : hubs)
                    if (a != b && Math.abs(a[0] - b[0]) < 1e-9)
                        assertTrue(Math.abs(a[2] - b[2]) >= ring + .02 - 1e-9, bay[0] + " x " + bay[1] + ": " + Math.abs(a[2] - b[2]) + " < " + ring);
        }
    }

    @Test
    void movingPartsAreTheStillOnesSetGoing() {
        // Owner requests 2026-10-16 and 2026-10-18 ("give compressor and pump working animations"): the client moves each
        // part about its pivot. An air cooler bank has a fan per fan position per bay, a pump its shaft and its motor's
        // fan, a compressor a piston rod per throw and its motor's cooler fan, the other units none. Each pivot lies in the
        // unit's box and each part within its reach of it; the parts' polygons have the area of the facets the chunk mesh
        // draws at rest, which it leaves out while they move; they are unpainted, drawn without the cell's tint.
        var bank = new ProcessUnit.Bank(2, 2);
        var cases = Map.of(EquipmentShape.unit(Kind.AIR_COOLER, 3, 6, Heading.EAST, 4, bank), bank.bays() * ProcessUnit.fans(3, 6).count(),
                EquipmentShape.unit(Kind.PUMP, .05, 1, Heading.NORTH, 0), 2, EquipmentShape.unit(Kind.PUMP, .25, 1, Heading.EAST, 0), 2,
                EquipmentShape.unit(Kind.PUMP, .6, 1, Heading.SOUTH, 0), 2, EquipmentShape.unit(Kind.COMPRESSOR, .15, 2, Heading.NORTH, 2), 3,
                EquipmentShape.unit(Kind.COMPRESSOR, .45, 4, Heading.WEST, ProcessUnit.minSupport(Kind.COMPRESSOR, .45, 4)), 5,
                EquipmentShape.unit(Kind.EXCHANGER, 1, 4, Heading.NORTH, 2), 0, EquipmentShape.unit(Kind.VERTICAL_HEATER, 2.5, 4, Heading.NORTH, 3), 0);
        for (var entry : cases.entrySet()) {
            var shape = entry.getKey();
            assertEquals(entry.getValue(), ShapeRasterizer.moving(shape).size(), shape.toString());
            assertEquals(entry.getValue() > 0, ProcessUnit.moves(shape.kind()), shape.toString());
            if (entry.getValue() > 0) movesAsStill(shape);
        }
        // Every size of every kind (2026-10-18, NEW_UNITS_PLAN.md): a kind that moves has moving parts at every size, each
        // its still parts set going; one that does not has none.
        for (var shape : everySize()) {
            if (ProcessUnit.moves(shape.kind())) movesAsStill(shape);
            else assertTrue(ShapeRasterizer.moving(shape).isEmpty(), shape.toString());
        }
    }

    /**
     * The shape's moving parts are its still parts set going: each turns or slides round a pivot inside the box, in
     * unpainted materials within its reach, and the shell cells' surfaces less their fixed ones are their area.
     */
    static void movesAsStill(EquipmentShape shape) {
        var parts = ShapeRasterizer.moving(shape);
        assertFalse(parts.isEmpty(), shape + " moves");
        double moving = 0;
        for (var part : parts) {
            double[] p = part.motion().pivot();
            assertTrue(p[0] > 0 && p[0] < shape.sizeX() && p[1] > 0 && p[1] < shape.sizeY() && p[2] > 0 && p[2] < shape.sizeZ(), shape + " " + part.motion());
            assertFalse(part.polygons().isEmpty(), shape.toString());
            for (var polygon : part.polygons()) {
                assertTrue(polygon.material().channel() < 0, shape + ": " + polygon.material());
                double[] q = polygon.positions();
                for (int i = 0; i < q.length; i += 3) assertTrue(Math.sqrt(q[i] * q[i] + q[i + 1] * q[i + 1] + q[i + 2] * q[i + 2]) <= part.reach() + 1e-9);
                moving += area(polygon);
            }
        }
        double still = 0;
        for (var cell : ShapeRasterizer.layout(shape).shell()) {
            var key = ShapeRasterizer.key(shape, cell.x(), cell.y(), cell.z());
            for (var polygon : ShapeRasterizer.surface(key)) still += area(polygon);
            for (var polygon : ShapeRasterizer.fixedSurface(key)) still -= area(polygon);
        }
        assertEquals(moving, still, 1e-6 * Math.max(1, moving), shape.toString());
    }

    @Test
    void pistonRodsHideTheirEnds() {
        // Owner request 2026-10-18 ("add Piston model to compressor"; answered: the rods in open distance-piece windows).
        // A rod rides its slider-crank: drawn with its crank a quarter turn round, out and in by about half the stroke;
        // over the whole stroke both of its ends stay inside the crosshead guide and the cylinder, so it never shows a cut
        // end in the windows.
        for (double b : new double[]{.15, .45, .8, 1.2})
            for (int throwCount : new int[]{ProcessUnit.minLength(Kind.COMPRESSOR, b), ProcessUnit.maxLength(Kind.COMPRESSOR, b)}) {
                var shape = EquipmentShape.unit(Kind.COMPRESSOR, b, throwCount, Heading.NORTH, ProcessUnit.minSupport(Kind.COMPRESSOR, b, throwCount));
                int rods = 0;
                for (var part : ShapeRasterizer.moving(shape)) {
                    var motion = part.motion();
                    if (!motion.slides()) continue;
                    rods++;
                    double crank = ProcessUnit.stroke(b) / 2;
                    assertEquals(crank, motion.crank(), 1e-12);
                    assertEquals(0, motion.offset(.25), 1e-12);
                    // Dead centres a stroke apart, out and in from the still rod (a quarter turn is not quite mid stroke).
                    assertEquals(2 * crank, motion.offset(0) - motion.offset(.5), 1e-12);
                    assertTrue(motion.offset(0) > 0 && motion.offset(.5) < 0);
                    double[] pivot = motion.pivot(), axis = motion.axis();
                    double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
                    for (var polygon : part.polygons())
                        for (int i = 0; i < polygon.positions().length; i += 3) {
                            double t = polygon.positions()[i] * axis[0] + polygon.positions()[i + 1] * axis[1] + polygon.positions()[i + 2] * axis[2];
                            lo = Math.min(lo, t);
                            hi = Math.max(hi, t);
                        }
                    for (double turns = 0; turns < 1; turns += .05) {
                        double d = motion.offset(turns);
                        for (double t : new double[]{lo + d + .01, hi + d - .01})
                            assertTrue(ShapeRasterizer.inside(shape, pivot[0] + t * axis[0], pivot[1] + t * axis[1], pivot[2] + t * axis[2]), shape + " turn " + turns);
                    }
                }
                assertEquals(throwCount, rods, shape.toString());
            }
    }

    @Test
    void heatersCarryWalkableDecksAndClimbableLadders() {
        // Owner feedback 2026-10-17 (MACHINES_HEATERS_PLAN.md, API 560 12.4.1): every deck is walked on with head room over
        // it; every ladder starts on a deck or the ground, is climbed in front of its rungs and pressed against at them; one
        // walked through is passed between its rails over its landing. Owner report 2026-10-17 ("ladder on both furnace are
        // not functional"): a player pressing the rungs stands in a shell cell all the way up, so Minecraft asks the shell.
        var shapes = List.of(EquipmentShape.unit(Kind.VERTICAL_HEATER, 2.5, 4, Heading.NORTH, 3), EquipmentShape.unit(Kind.VERTICAL_HEATER, 4, 9, Heading.EAST, 3),
                EquipmentShape.unit(Kind.VERTICAL_HEATER, 12, 20, Heading.SOUTH, 3), EquipmentShape.unit(Kind.CABIN_HEATER, 4, 6, Heading.NORTH, 3),
                EquipmentShape.unit(Kind.CABIN_HEATER, 6, 12, Heading.WEST, 3), EquipmentShape.unit(Kind.CABIN_HEATER, 12, 40, Heading.NORTH, 4),
                EquipmentShape.unit(Kind.CABIN_HEATER, 4, 25, Heading.EAST, 3));
        for (var shape : shapes) {
            assertFalse(ProcessUnit.of(shape).decks().isEmpty(), shape.toString());
            walkedAndClimbed(shape);
        }
        assertTrue(ProcessUnit.of(EquipmentShape.unit(Kind.CABIN_HEATER, 6, 12, Heading.WEST, 3)).decks().size() >= 6);
    }

    /**
     * No unit design reaches into another design's files (kit consolidation 2026-10-18, {@code KIT_CONSOLIDATION_PLAN.md}):
     * what two designs share is the kit's. A design's files are its {@code <Name>Design} and the helpers named after it;
     * the two turbine sets share their train, the two fluidized beds and the two hydroprocessing reactors their parts, by
     * design. Comments are left out of the scan.
     */
    @Test
    void designsShareOnlyTheKit() throws IOException {
        var dir = Path.of("src/main/java/com/wormzjl/icheme/science/equipment");
        var names = new ArrayList<String>();
        try (var files = Files.list(dir)) {
            for (var file : files.toList()) names.add(file.getFileName().toString().replace(".java", ""));
        }
        var owners = new HashMap<String, Set<Kind>>();
        for (var kind : Kind.values()) {
            if (!kind.designed()) continue;
            String prefix = UnitDesign.of(kind).getClass().getSimpleName().replace("Design", "");
            for (var name : names) if (name.startsWith(prefix)) owners.computeIfAbsent(name, n -> EnumSet.noneOf(Kind.class)).add(kind);
        }
        owners.put("FluidizedBedParts", EnumSet.of(Kind.FLUIDIZED_BED_BOILER, Kind.FLUIDIZED_BED_GASIFIER));
        owners.put("HydroprocessingParts", EnumSet.of(Kind.TRICKLE_BED_REACTOR, Kind.EBULLATED_BED_REACTOR));
        var crossings = new ArrayList<String>();
        for (var file : owners.entrySet()) {
            // The code only: a comment naming another design (a cross-reference in prose) uses none of it.
            String source = Files.readString(dir.resolve(file.getKey() + ".java")).replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\n]*", "");
            for (var other : owners.entrySet())
                if (Collections.disjoint(file.getValue(), other.getValue()) && Pattern.compile("\\b" + other.getKey() + "\\b").matcher(source).find())
                    crossings.add(file.getKey() + " -> " + other.getKey());
        }
        assertEquals(List.of(), crossings);
    }

    /** Every size of every kind (2026-10-18, NEW_UNITS_PLAN.md): its decks are walked on and its ladders climbed. */
    @Test
    void everyUnitsDecksAreWalkedAndLaddersClimbed() {
        for (var shape : everySize()) walkedAndClimbed(shape);
    }

    /**
     * A player climbs every ladder of every unit and storage vessel from its foot and gets off at its landing, moving as the
     * game moves one ({@link PlayerWalk#leave}; owner report 2026-10-25: "I cannot move to side platforms when climbing the
     * ladder on the furnace as well, review other ladders"). The fluidized-bed gasifier is left to its rework (owner
     * 2026-10-25: "These big structures will need a major rework later, leave them for now"): one of its stack's ladders
     * lands beside too narrow a ring deck.
     */
    @Test
    void aPlayerClimbsEveryLadderAndGetsOffAtItsLanding() {
        var shapes = new ArrayList<EquipmentShape>();
        for (var shape : everySize()) if (shape.kind() != Kind.FLUIDIZED_BED_GASIFIER) shapes.add(shape);
        for (int d : new int[]{4, 5, 8, 12, 20, 30, 40}) for (int shell : new int[]{4, 8, 12, 24}) shapes.add(EquipmentShape.storage(Kind.TANK, d, shell, Heading.NORTH, 0));
        for (int d : new int[]{4, 9, 14, 24}) for (int support : new int[]{2, 5, 8}) shapes.add(EquipmentShape.storage(Kind.SPHERE, d, 1, Heading.NORTH, support));
        var stuck = new ArrayList<String>();
        for (var shape : shapes)
            for (var l : ShapeRasterizer.climbs(shape)) {
                String why = PlayerWalk.leave(shape, l);
                if (why != null) stuck.add(shape + " " + l + ": " + why);
            }
        assertEquals(List.of(), stuck);
    }

    /** The shape's decks are walked on with head room over them, its ladders climbed from a deck or the ground. */
    static void walkedAndClimbed(EquipmentShape shape) {
        var unit = ProcessUnit.of(shape);
        var starts = new HashSet<Double>(List.of(0.0));
        for (var deck : unit.decks()) {
            double mid = deck.run().length() / 2, off = (Access.GAP + deck.width() - Access.RAIL_BAND) / 2;
            assertTrue(in(shape, deck.run().at(mid, off, deck.level() + Access.DECK / 2)), shape + " deck at " + deck.level());
            assertFalse(in(shape, deck.run().at(mid, off, deck.level() + Access.DECK + .3)), shape + " room over the deck at " + deck.level());
            starts.add(deck.level() + Access.DECK);
        }
        for (var l : unit.ladders()) {
            assertTrue(starts.contains(l.base()), shape + " a climb from " + l.base() + " starts on a deck or the ground");
            // Owner decision C1 2026-10-18: no climb longer than Access.MAX_CLIMB from its foot to the deck it lands on
            // (the columns' rule; a deck's grating may add its own thickness: 9.125 m, under the old rule's 30 ft).
            // The owner explicitly removed tank intermediate platforms; other equipment keeps the rest rule.
            assertTrue(shape.kind() == Kind.TANK || l.top() - Access.LANDING - l.base() <= Access.MAX_CLIMB + Access.DECK + 1e-9, shape + " climbs from " + l.base() + " to " + (l.top() - Access.LANDING));
            double y = (l.base() + l.rungTop()) / 2;
            assertTrue(climbable(shape, new double[]{l.x() + .3 * l.outX(), y, l.z() + .3 * l.outZ()}), shape + " climbable in front of the rungs at " + y);
            assertFalse(climbable(shape, new double[]{l.x() + 1.5 * l.outX(), y, l.z() + 1.5 * l.outZ()}), shape + " not climbable outside the cage");
            assertTrue(in(shape, new double[]{l.x() - .04 * l.outX(), y, l.z() - .04 * l.outZ()}), shape + " the rung plane is pressed against to climb");
            if (l.rungTop() < l.top()) assertFalse(in(shape, new double[]{l.x(), l.rungTop() + .6, l.z()}), shape + " walked through over its landing");
        }
        // A stair flight (2026-10-18, NEW_UNITS_PLAN.md) runs from a deck or the ground up to a deck; each tread is stepped on
        // with room over it, between fences either side.
        for (var st : unit.stairs()) {
            assertTrue(starts.contains(st.base()) && starts.contains(st.top()), shape + " a stair from " + st.base() + " to " + st.top() + " joins decks or the ground");
            for (int k = 1; k <= st.steps(); k++) {
                double s = (k - .5) * ProcessUnit.STAIR_GOING, y = st.base() + k * st.rise();
                assertTrue(in(shape, st.at(s, 0, y - .06)), shape + " stair tread " + k + " at " + y);
                assertFalse(in(shape, st.at(s, 0, y + .3)), shape + " room over stair tread " + k + " at " + y);
                for (int side = -1; side <= 1; side += 2)
                    assertTrue(in(shape, st.at(s, side * (st.width() / 2 - .1), y + .8)), shape + " stair fence " + side + " at tread " + k);
            }
        }
        woundStairsWalked(unit.woundStairs(), p -> in(shape, p), starts, shape.toString());
        climbedFromShellCells(shape, unit.ladders());
        stoppedAtClosedEnds(shape, unit.decks());
        railedAtClosedEnds(shape, unit);
    }

    /**
     * A player sees the rail that stops them: each closed end's handrail is drawn across the deck's middle (in-game check
     * 2026-10-18: a stair landing's opening starting at a walkway's end left the end's fence without its rail).
     */
    static void railedAtClosedEnds(EquipmentShape shape, ProcessUnit unit) {
        var rails = unit.mesh().facets().stream().filter(f -> f.material() == Mesh.Material.HANDRAIL).toList();
        for (var d : unit.decks())
            for (int e = 0; e < 2; e++) {
                if (!(e == 0 ? d.closedStart() : d.closedEnd())) continue;
                var p = d.run().at(e == 0 ? 0 : d.run().length(), (.12 + d.width() - .03) / 2, d.level() + Access.DECK + Access.RAIL);
                boolean drawn = rails.stream().anyMatch(f -> {
                    for (int i = 0; i < 3; i++) if (p[i] < f.min()[i] - .05 || p[i] > f.max()[i] + .05) return false;
                    return true;
                });
                assertTrue(drawn, shape + " the deck at " + d.level() + " draws its rail across its closed end " + e);
            }
    }

    /**
     * A player walking off a deck's closed end meets the game's collision there (owner report 2026-10-17, "box furnace
     * walkway end hitbox is not working"): some 1/8-block voxel, solid at its centre, lies in the end's rail band across the
     * player's width from knee to chest height.
     */
    static void stoppedAtClosedEnds(EquipmentShape shape, List<ProcessUnit.Deck> decks) {
        for (var d : decks)
            for (int e = 0; e < 2; e++) {
                if (!(e == 0 ? d.closedStart() : d.closedEnd())) continue;
                double len = d.run().length(), from = e == 0 ? 0 : len - Access.RAIL_BAND, off = (Access.GAP + d.width() - Access.RAIL_BAND) / 2, y = d.level() + Access.DECK;
                // The box round the band's eight corners (a round deck's band is curved), in the shape's box coordinates.
                double[] p = {Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE}, q = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
                for (double s : new double[]{from, from + Access.RAIL_BAND})
                    for (double o : new double[]{off - .3, off + .3})
                        for (double h : new double[]{y + .3, y + 1.2}) {
                            var c = shape.transform().point(d.run().at(s, o, h));
                            for (int i = 0; i < 3; i++) {
                                p[i] = Math.min(p[i], c[i]);
                                q[i] = Math.max(q[i], c[i]);
                            }
                        }
                int met = 0;
                for (int i = (int) Math.ceil(Math.min(p[0], q[0]) * 8 - .5); (i + .5) / 8 <= Math.max(p[0], q[0]); i++)
                    for (int j = (int) Math.ceil(Math.min(p[1], q[1]) * 8 - .5); (j + .5) / 8 <= Math.max(p[1], q[1]); j++)
                        for (int k = (int) Math.ceil(Math.min(p[2], q[2]) * 8 - .5); (k + .5) / 8 <= Math.max(p[2], q[2]); k++)
                            if (ShapeRasterizer.inside(shape, (i + .5) / 8, (j + .5) / 8, (k + .5) / 8)) met++;
                assertTrue(met > 0, shape + " the deck at " + d.level() + " stops a player at its closed end " + e);
            }
    }

    /** A player pressing a ladder's rungs (centre {@link Access#RUNG_FACE} + 0.3 m out) stands in a shell cell from its foot to its landing. */
    static void climbedFromShellCells(EquipmentShape shape, List<Access.Climb> climbs) {
        var shell = new HashSet<>(ShapeRasterizer.layout(shape).shell());
        for (var l : climbs)
            for (double y = l.base() + .05; y < l.rungTop(); y += .25) {
                double out = Access.RUNG_FACE + .3;
                var b = shape.transform().point(new double[]{l.x() + out * l.outX(), y, l.z() + out * l.outZ()});
                var cell = new ShapeRasterizer.Cell((int) Math.floor(b[0]), (int) Math.floor(b[1]), (int) Math.floor(b[2]));
                assertTrue(shell.contains(cell), shape + " a climber at " + y + " over the foot " + l.base() + " stands in shell cell " + cell);
                assertTrue(ShapeRasterizer.climbable(shape, b[0], b[1], b[2]), shape + " climbs at " + y);
            }
    }

    // ---- The kit's pieces (2026-10-18, KIT_CONSOLIDATION_PLAN.md) ---------------------------------------------------

    /** A unit drawn by {@code design} alone (the shape only names it): the kit's pieces under test, in the unit's frame. */
    private static ProcessUnit kit(Consumer<ProcessUnit> design) {
        return ProcessUnit.of(EquipmentShape.unit(Kind.PUMP, .25, 1, Heading.NORTH, 0), design);
    }

    /**
     * A kit unit's decks are walked with room over them and their closed ends stop a player; its ladders and flights start
     * on a deck or the ground and its flights reach one; each tread is stepped on between its fences with room over it
     * and, between them, room for a player (0.6 m) at the game's 1/8-block collision; so is each landing piece under 1.2 m
     * across between its ends.
     */
    static void walkedInFrame(ProcessUnit unit) {
        var starts = new HashSet<Double>(List.of(0.0));
        for (var deck : unit.decks()) {
            var run = deck.run();
            double mid = run.length() / 2, off = (Access.GAP + deck.width() - Access.RAIL_BAND) / 2, y = deck.level() + Access.DECK;
            assertTrue(unit.inside(run.at(mid, off, deck.level() + Access.DECK / 2)), unit.shape + " deck at " + deck.level() + " " + run);
            assertFalse(unit.inside(run.at(mid, off, y + .3)), "room over the deck at " + deck.level());
            starts.add(y);
            for (int e = 0; e < 2; e++)
                if (e == 0 ? deck.closedStart() : deck.closedEnd())
                    assertTrue(unit.inside(run.at(e == 0 ? .06 : run.length() - .06, off, y + .8)), "the deck at " + deck.level() + " stops a player at its end " + e);
            if (run.length() < 1.2) assertTrue(clear(unit, run.at(0, off, y + .6), run.at(run.length(), off, y + .6)) >= .6, "room across the deck at " + deck.level());
        }
        for (var l : unit.ladders()) assertTrue(starts.contains(l.base()), "a climb from " + l.base() + " starts on a deck or the ground");
        for (var st : unit.stairs()) {
            assertTrue(starts.contains(st.base()) && starts.contains(st.top()), "a stair from " + st.base() + " to " + st.top() + " joins decks or the ground");
            for (int k = 1; k <= st.steps(); k++) {
                double s = (k - .5) * ProcessUnit.STAIR_GOING, y = st.base() + k * st.rise();
                assertTrue(unit.inside(st.at(s, 0, y - .06)), "stair tread " + k + " at " + y);
                assertFalse(unit.inside(st.at(s, 0, y + .3)), "room over stair tread " + k + " at " + y);
                for (int side = -1; side <= 1; side += 2) assertTrue(unit.inside(st.at(s, side * (st.width() / 2 - .1), y + .8)), "stair fence " + side + " at tread " + k);
                assertTrue(clear(unit, st.at(s, -st.width() / 2, y + .6), st.at(s, st.width() / 2, y + .6)) >= .6, "room for a player on tread " + k + " at " + y);
            }
        }
        woundStairsWalked(unit.woundStairs(), unit::inside, starts, unit.shape.toString());
    }

    /**
     * A wound stair (owner approval 2026-10-21) runs from the ground or a deck up to a deck; each tread is stepped on, a
     * fence stands either side (but where the inner side opens onto a deck), and a player's box, 0.6 m square and aligned
     * with the world, stands on the tread's middle at the walk line without meeting a solid 1/8-block voxel from over the
     * second tread up to head height. {@code solid} says whether a frame point is solid.
     */
    static void woundStairsWalked(List<Access.WoundStair> stairs, java.util.function.Predicate<double[]> solid, Set<Double> starts, String name) {
        for (var st : stairs) {
            assertTrue(starts.contains(st.base()) && starts.contains(st.top()), name + " a wound stair from " + st.base() + " to " + st.top() + " joins decks or the ground");
            for (int k = 1; k <= st.steps(); k++) {
                double s = (k - .5) * Access.WOUND_GOING, y = st.tread(k);
                assertTrue(solid.test(st.at(s, 0, y - Math.min(.04, st.rise(k) / 2))), name + " wound tread " + k + " at " + y);
                assertFalse(solid.test(st.at(s, 0, y + .3)), name + " room over wound tread " + k + " at " + y);
                for (int side = -1; side <= 1; side += 2) {
                    if (side < 0 && st.open(s)) continue;
                    assertTrue(solid.test(st.at(s, side * (st.width() / 2 - Access.NARROW_BAND / 2), y + .8)), name + " wound stair fence " + side + " at tread " + k);
                }
                var c = st.at(s, 0, y);
                for (int i = (int) Math.floor((c[0] - .3) * 8); i / 8.0 < c[0] + .3; i++)
                    for (int j = (int) Math.ceil((y + .45) * 8 - .5); (j + .5) / 8 <= y + 1.75; j++)
                        for (int l = (int) Math.floor((c[2] - .3) * 8); l / 8.0 < c[2] + .3; l++)
                            assertFalse(solid.test(new double[]{(i + .5) / 8, (j + .5) / 8, (l + .5) / 8}),
                                    name + " a player on wound tread " + k + " at " + y + " meets the voxel at " + (i + .5) / 8 + ", " + (j + .5) / 8 + ", " + (l + .5) / 8);
            }
        }
    }

    /** The longest stretch free of solid 1/8-block voxels (sampled at their centres) on the line from p to q along a horizontal frame axis, m. */
    private static double clear(ProcessUnit unit, double[] p, double[] q) {
        int axis = Math.abs(q[0] - p[0]) > Math.abs(q[2] - p[2]) ? 0 : 2;
        double lo = Math.min(p[axis], q[axis]) - .125, hi = Math.max(p[axis], q[axis]) + .125, best = 0, run = 0;
        var c = p.clone();
        for (int a = 0; a < 3; a++) if (a != axis) c[a] = (Math.floor(c[a] * 8) + .5) / 8;
        for (int i = (int) Math.floor(lo * 8); (i + .5) / 8 <= hi; i++) {
            c[axis] = (i + .5) / 8;
            run = unit.inside(c) ? 0 : run + .125;
            best = Math.max(best, run);
        }
        return best;
    }

    @Test
    void climbsRestAtMostNineMetresApart() {
        // Owner decision C1 2026-10-18: a caged ladder climbs at most Access.MAX_CLIMB between rest platforms.
        assertEquals(List.of(5, 13, 20, 28), Access.climbs(0, List.of(5, 20), 28));
        assertEquals(List.of(8, 15, 23, 30), Access.climbs(0, List.of(), 30));
        assertEquals(List.of(10), Access.climbs(4, List.of(2, 4, 40), 10));
        for (int top = 1; top <= 40; top += 3) {
            var levels = Access.climbs(0, List.of(top / 3, top / 2), top);
            assertEquals(top, levels.getLast());
            int previous = 0;
            for (int level : levels) {
                assertTrue(level > previous && level - previous <= Access.MAX_CLIMB, top + ": " + levels);
                previous = level;
            }
        }
        // The cage clearance, the least deck and the longest climb are the columns'.
        var column = EquipmentShape.column(3, ColumnTrays.simple(12, 6), Heading.SOUTH, 4);
        assertEquals(ColumnStructure.clearance(column), Access.clearance(column.outerRadius()));
        assertEquals(ColumnStructure.MIN_DECK, Access.MIN_DECK);
        assertEquals(ColumnStructure.MAX_CLIMB, Access.MAX_CLIMB);
        assertEquals(.35, PipeSizes.od(.33, .1, .6), 1e-12);
        assertEquals(.1, PipeSizes.od(.02, .1, .6), 1e-12);
        assertEquals(.6, PipeSizes.od(.9, .1, .6), 1e-12);
    }

    @Test
    void aTallHeaterRestsItsClimbs() {
        // A vertical heater on a 14 m support: a rest platform under its floor (owner decision C1 2026-10-18).
        var shape = EquipmentShape.unit(Kind.VERTICAL_HEATER, 4, 9, Heading.NORTH, 14);
        walkedAndClimbed(shape);
        var unit = ProcessUnit.of(shape);
        for (var l : unit.ladders()) assertTrue(l.top() - Access.LANDING - l.base() <= Access.MAX_CLIMB + 1e-9, shape + " climbs from " + l.base() + " to " + l.top());
        assertTrue(unit.decks().stream().anyMatch(d -> d.level() < shape.support()), shape + " rests under its floor");
    }

    @Test
    void solidsCloseWithAHeadAtEitherEnd() {
        var rod = ProcessUnit.Solid.rod(new double[]{0, 0, 0}, 1, 2, 5, 1, .5, .25);
        assertEquals(1.5, rod.min()[1], 1e-12);
        assertEquals(5.25, rod.max()[1], 1e-12);
        assertTrue(rod.contains(new double[]{0, 1.6, 0}));
        assertTrue(rod.contains(new double[]{.5, 1.8, 0}));
        assertFalse(rod.contains(new double[]{.9, 1.6, 0}));
        assertTrue(rod.contains(new double[]{0, 5.2, 0}));
        assertTrue(rod.contains(new double[]{.99, 3, 0}));
        var flat = ProcessUnit.Solid.rod(new double[]{0, 0, 0}, 1, 2, 5, 1, 0);
        assertFalse(flat.contains(new double[]{0, 1.99, 0}));
        assertTrue(flat.contains(new double[]{0, 2, 0}));
    }

    @Test
    void meshPiecesDrawTheirFacets() {
        // A hoop: its rim and both faces, no bore; a mitred bend: a prism per piece, a half piece along each end's
        // tangent (its end square to the pipe there, so a straight run meets it flush), none where the runs carry on to
        // the mitres; a 2:1 head in the bands asked.
        var hoop = new Mesh();
        hoop.hoop(Mesh.Frame.of(new double[]{0, 0, 0}, 1, 1), 1, 1.05, 0, .12, 12, Mesh.Material.HEATER);
        assertEquals(36, hoop.facets().size());
        var f = new Mesh.Frame(new double[]{0, 0, 0}, new double[]{0, 1, 0}, new double[]{1, 0, 0});
        var bend = new Mesh();
        bend.bend(f, 1, .1, 0, Math.PI / 2, 3, 8, true, Mesh.Material.PIPE);
        assertEquals(32, bend.facets().size());
        var b = bend.bounds();
        assertTrue(Math.abs(b[0]) < 1e-9 && b[3] < 1.15 && b[2] > -1.15 && Math.abs(b[5]) < 1e-9, java.util.Arrays.toString(b));
        var inner = new Mesh();
        inner.bend(f, 1, .1, 0, Math.PI / 2, 3, 8, false, Mesh.Material.PIPE);
        assertEquals(16, inner.facets().size());
        var head = new Mesh();
        head.head(Mesh.Frame.of(new double[]{0, 0, 0}, 1, 1), 1, 0, .5, 16, 4, Mesh.Material.HEAD);
        assertEquals(64, head.facets().size());
        assertEquals(16, ProcessUnit.stackTop(10, 8), 1e-12);
        assertEquals(24, ProcessUnit.stackTop(10, 12), 1e-12);
        assertEquals(16, ProcessUnit.stackTop(10, 3), 1e-12);
    }

    @Test
    void toeBoardsRunOnlyUnderTheRail() {
        // Owner decision 2026-10-18: no toe board across a rail's gap (a ladder's or a stair's opening), the bare grating
        // edge there; the rail's pieces carry it.
        var run = new Access.Line(new double[]{0, 0, 0}, new double[]{1, 0, 0}, new double[]{0, 0, -1}, 4);
        var m = new Mesh();
        Access.deck(m, run, 2, 1.2, Access.ALL_PARTS, 1.5, 2.5);
        boolean bare = false, toeLeft = false, toeRight = false;
        for (var f : m.facets()) {
            if (f.max()[0] - f.min()[0] < 1e-6) continue;
            boolean overGap = f.min()[0] > 1.5 - 1e-9 && f.max()[0] < 2.5 + 1e-9;
            if (f.material() == Mesh.Material.HANDRAIL) {
                assertFalse(overGap, "a toe board over the gap");
                toeLeft |= f.max()[0] <= 1.5 + 1e-9;
                toeRight |= f.min()[0] >= 2.5 - 1e-9;
            }
            bare |= overGap && f.material() == Mesh.Material.GRATING && Math.abs(f.min()[2] + 1.2) < 1e-9 && Math.abs(f.max()[2] + 1.2) < 1e-9;
        }
        assertTrue(bare && toeLeft && toeRight);
    }

    @Test
    void stairTowersClimbToEveryStop() {
        // Owner decision 2026-10-18: flights 0.9 m wide on lanes 1 m apart on cell centres, of at most 3.6 m between
        // landings 1.2 m deep, each stop's landing open towards the structure.
        // Its plan, worked out before it is drawn, places it: its ends and each stop's opening as stations from lane A's foot.
        double[] stops = {4, 11, 14};
        var plan = ProcessUnit.towerPlan(stops);
        var tower = new ProcessUnit.Tower[1];
        var unit = kit(u -> tower[0] = u.stairTower(plan, 10.3, 5.7, 0, 1, 1, true));
        walkedInFrame(unit);
        assertEquals(5, unit.stairs().size());
        assertEquals(stops.length, tower[0].openings().size());
        assertEquals(5.05, tower[0].edge(), 1e-12);
        assertEquals(10.3 + plan.from(), tower[0].from(), 1e-12);
        assertEquals(10.3 + plan.to(), tower[0].to(), 1e-12);
        for (var st : unit.stairs()) {
            assertTrue(st.top() - st.base() <= ProcessUnit.TOWER_RISE + 1e-9);
            assertEquals(.5, st.z() - Math.floor(st.z()), 1e-12, "a lane on a cell centre");
        }
        for (int j = 0; j < stops.length; j++) {
            var o = tower[0].openings().get(j);
            assertEquals(10.3 + plan.opening(j)[0], o[0], 1e-12);
            assertEquals(10.3 + plan.opening(j)[1], o[1], 1e-12);
            assertTrue(o[0] >= tower[0].from() && o[1] <= tower[0].to() && o[1] - o[0] >= ProcessUnit.TOWER_LANDING - 1e-9);
            // The structure's side of the landing is open there and fenced elsewhere along the tower's lane A.
            double y = stops[j] + Access.DECK + .8, mid = (o[0] + o[1]) / 2;
            assertFalse(unit.inside(new double[]{mid, y, 5.05 + .06}), "the landing at " + stops[j] + " opens onto the structure");
            // A deck run along the structure's edge opens its rail over the landing.
            var run = new Access.Line(new double[]{20, 0, 5.05}, new double[]{-1, 0, 0}, new double[]{0, 0, -1}, 20);
            assertArrayEquals(new double[]{20 - o[1], 20 - o[0]}, run.gap(o), 1e-12);
        }
        // The corner posts stand flush with the tower's side, clear of the structure's deck beside it.
        assertTrue(unit.inside(new double[]{tower[0].from() + .1, 1, 5.06}));
        assertFalse(unit.inside(new double[]{tower[0].from() + .1, 1, 5.04}));
        // A single flight lands on lane A alone; a tower with no stop over 3.6 m has no turn.
        var single = kit(u -> u.stairTower(ProcessUnit.towerPlan(3), 2, 9, 2, -1, -1, false));
        walkedInFrame(single);
        assertEquals(1, single.stairs().size());
    }

    @Test
    void concreteTablesAreCutRoundTheirHoles() {
        var unit = kit(u -> u.concreteTable(0, 8, 0, 6, 4, .8, List.of(new double[]{2, 2, 4, 4}), .6, List.of(new double[]{.5, .5}, new double[]{7.5, 5.5})));
        assertTrue(unit.inside(new double[]{1, 3.6, 1}));
        assertTrue(unit.filled(new double[]{1, 3.6, 1}));
        assertFalse(unit.inside(new double[]{3, 3.6, 3}), "the hole");
        assertTrue(unit.inside(new double[]{.5, 2, .5}), "a column");
        assertTrue(unit.inside(new double[]{.85, .1, .5}), "its pad");
        assertFalse(unit.inside(new double[]{.85, 2, .5}));
        // Drawn faces: each piece's top and underside, the four outer faces, the hole's four walls, and the columns and pads.
        long walls = unit.mesh().facets().stream().filter(f -> f.material() == Mesh.Material.CONCRETE && f.min()[1] > 3.2 - 1e-9 && f.max()[1] < 4 + 1e-9
                && f.max()[1] - f.min()[1] > .7).count();
        assertEquals(8, walls);
    }

    @Test
    void squareDecksAndRingAccessAreWalked() {
        var deck = kit(u -> u.squareDeck(0, 6, 0, 9, 3, 2, 3, 1, new double[]{2.5, 6.5}, .7, 32, Access.RAILED, new double[][]{null, {4, 5}, null, null}, null));
        walkedInFrame(deck);
        double y = 3 + Access.DECK;
        assertTrue(deck.inside(new double[]{1, 3 + Access.DECK / 2, 4.5}), "a strip");
        assertTrue(deck.inside(new double[]{3.85, 3 + Access.DECK / 2, 2.5}), "the fill round a body");
        assertFalse(deck.inside(new double[]{3, 3 + Access.DECK / 2, 2.5}), "the body's place");
        assertTrue(deck.inside(new double[]{.1, y + .6, 4.5}), "the rail on the -x edge");
        assertFalse(deck.inside(new double[]{5.9, y + .6, 4.5}), "the gap in the +x edge's rail");
        assertTrue(deck.inside(new double[]{5.9, y + .6, 1}), "the +x edge's rail");
        var arcs = new ArrayList<List<Access.Arc>>();
        var rings = List.of(new ProcessUnit.Ring(4, 0, -Math.PI / 2 + .3, 1.5 * Math.PI - .3), new ProcessUnit.Ring(10, Math.PI, -.5, Math.PI + 1.2),
                new ProcessUnit.Ring(15, 0, 0, 2 * Math.PI));
        var ring = kit(u -> arcs.addAll(u.ringAccess(5.5, 5.5, 1.2, Access.MIN_DECK, 0, rings)));
        walkedInFrame(ring);
        assertEquals(List.of(2, 2, 1), arcs.stream().map(List::size).toList());
        assertEquals(3, ring.ladders().size());
        for (int k = 0; k < rings.size(); k++)
            for (var arc : arcs.get(k))
                for (int w = -1; w <= 1; w++) {
                    double t = rings.get(k).ladder() + 2 * Math.PI * w;
                    assertFalse(t > arc.from() && t < arc.to(), "ring " + k + " is open where its ladder comes up");
                }
        var decks = ring.decks();
        assertFalse(decks.getLast().closedStart() || decks.getLast().closedEnd(), "a whole ring is open only at its ladder");
    }

    @Test
    void motorsStacksAndPartsCollideAsDrawn() {
        // A TEFC motor lying along z, standing, and lying on a diagonal, in each detail.
        var lying = kit(u -> u.motor(Mesh.Frame.of(new double[]{2.5, 1, 0}, 2, 1), .2, 1, 2, .03, 0, ProcessUnit.MotorDetail.FULL));
        assertTrue(lying.inside(new double[]{2.5, 1, 1.5}));
        assertTrue(lying.inside(new double[]{2.5, .5, 1.5}), "its pedestal");
        assertEquals(1, lying.motions().size(), "its fan turns");
        var standing = kit(u -> u.motor(Mesh.Frame.of(new double[]{2.5, 0, 2.5}, 1, 1), .2, 1, 2, .03, 0, ProcessUnit.MotorDetail.FINNED));
        assertTrue(standing.inside(new double[]{2.5, 1.5, 2.5}));
        assertTrue(standing.motions().isEmpty());
        var diagonal = kit(u -> u.motor(new Mesh.Frame(new double[]{2.5, 1, 2.5}, Mesh.unit(1, 0, 1), new double[]{0, 1, 0}), .2, 1, 2, .03, 0, ProcessUnit.MotorDetail.PLAIN));
        assertTrue(diagonal.inside(new double[]{2.5 + 1.5 / Math.sqrt(2), 1, 2.5 + 1.5 / Math.sqrt(2)}));
        // A stack on its breeching; a pipe with a long-radius elbow; a skirt on grade with a bay, and a davit.
        var stack = kit(u -> u.stack(3.5, 3.5, 1, ProcessUnit.sides(1), 2, 20, Mesh.Material.HEATER, new ProcessUnit.Breeching(2, 5, 2.5, 4.5), true));
        assertTrue(stack.inside(new double[]{3.5, 10, 3.5}));
        assertTrue(stack.inside(new double[]{2.2, 2.3, 3}), "the breeching");
        var pipe = kit(u -> u.pipe(.1, Mesh.Material.PIPE, true, new double[]{1, 1, 1}, new double[]{1, 1, 4}, new double[]{4, 1, 4}));
        assertTrue(pipe.inside(new double[]{1, 1, 2}) && pipe.inside(new double[]{3, 1, 4}));
        double[] bay = {Math.PI / 2, .8, 0, 2};
        var vessel = kit(u -> {
            u.skirt(4.5, 4.5, 1.5, 3, 24, bay);
            u.baseRing(4.5, 4.5, 1.5, 3, 24, bay);
            u.davit(4.5, 4.5, Math.PI / 2, 1.8, 3, 7, 0);
        });
        assertTrue(vessel.inside(new double[]{4.5 + 1.47, 2, 4.5}), "the skirt");
        assertFalse(vessel.inside(new double[]{4.5, 1, 4.5 + 1.47}), "its bay");
        assertTrue(vessel.inside(new double[]{4.5, 2.5, 4.5 + 1.47}), "the skirt over its bay");
        assertTrue(vessel.inside(new double[]{4.5, 5, 4.5 + 1.8}), "the davit's mast");
    }

    private static boolean in(EquipmentShape shape, double[] p) {
        var b = shape.transform().point(p);
        return ShapeRasterizer.inside(shape, b[0], b[1], b[2]);
    }

    private static boolean climbable(EquipmentShape shape, double[] p) {
        var b = shape.transform().point(p);
        return ShapeRasterizer.climbable(shape, b[0], b[1], b[2]);
    }

    private static double area(Mesh.Polygon polygon) {
        double[] p = polygon.positions(), n = new double[3];
        int m = polygon.size();
        for (int i = 0; i < m; i++) {
            int j = (i + 1) % m;
            n[0] += (p[3 * i + 1] - p[3 * j + 1]) * (p[3 * i + 2] + p[3 * j + 2]);
            n[1] += (p[3 * i + 2] - p[3 * j + 2]) * (p[3 * i] + p[3 * j]);
            n[2] += (p[3 * i] - p[3 * j]) * (p[3 * i + 1] + p[3 * j + 1]);
        }
        return .5 * Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
    }

    private static boolean overlap(double[][] p, double[][] q) {
        for (int a = 0; a < 3; a++) if (p[1][a] <= q[0][a] || q[1][a] <= p[0][a]) return false;
        return true;
    }

    /**
     * A nozzle's neck as the world sees it (owner decision 2026-10-21: a 1 m pipe has a 1 m root, other components give way):
     * whether a neck of a given outside diameter, from where it has left the vessel to its flange's hub, stands clear of the
     * rest of the solid (the vessel, its supports, the unit's parts, the other nozzles).
     */
    static final class NozzleRoom {
        /** How far beyond where it leaves the vessel a neck may still meet the vessel's own rings (girth flanges, pads, a channel's cover), m. */
        static final double SURFACE = .1;
        /** The step along the neck at which {@link #leaves} looks for where a ring has left the solid, m. */
        private static final double STEP = .02;

        private NozzleRoom() {
        }

        /**
         * Whether nozzle {@code k}'s neck at outside diameter {@code od} stands clear of the rest of the shape's solid, beyond
         * {@link #SURFACE} from where it has left the vessel. Where it leaves: the first point along the neck where a ring of
         * its size is wholly out of the solid, looked for from where a ring just round its unconnected neck has left (its own
         * vessel's surface there) up to half a pipe's largest beyond, the most a convex body's curve holds a neck's edge
         * behind its centre line, or up to its flange's hub; a neck that has not left the solid by then meets it.
         */
        static boolean clear(EquipmentShape shape, int k, double od) {
            var nozzle = shape.nozzles().get(k);
            double to = nozzle.length() - PipeSizes.flangeThickness(od) - PipeSizes.hub(od) - .01, radius = od / 2 + .01;
            // The mesh shortens its flange hub where the cast reducer needs the available neck.
            var root = ShapeRasterizer.nozzleRoot(shape, k);
            if (root != null) to = Math.max(to, root.end(nozzle.length(), od));
            double near = Math.max(0, leaves(shape, nozzle, nozzle.defaultOd() / 2 + .01, 0, to));
            double out = leaves(shape, nozzle, radius, near, Math.min(to, near + PipeSizes.MAX_OUTSIDE / 2)), from = out + SURFACE;
            if (out < 0) return false;
            int rings = Math.max(1, (int) Math.ceil((to - from) / .05));
            for (int i = 0; i <= rings && to > from; i++)
                if (!ringClear(shape, nozzle, radius, from + (to - from) * i / rings)) return false;
            return true;
        }

        /** The first distance from {@code from} to {@code to} along the neck, in {@link #STEP}s, where a ring of {@code radius} is wholly out of the solid; -1 where there is none. */
        private static double leaves(EquipmentShape shape, EquipmentShape.Nozzle nozzle, double radius, double from, double to) {
            if (to < from) return -1;
            int steps = Math.max(1, (int) Math.ceil((to - from) / STEP));
            for (int i = 0; i <= steps; i++) {
                double s = from + (to - from) * i / steps;
                if (ringClear(shape, nozzle, radius, s)) return s;
            }
            return -1;
        }

        /** Whether the ring of {@code radius} round the nozzle's axis, {@code s} from its base, lies wholly out of the shape's solid. */
        private static boolean ringClear(EquipmentShape shape, EquipmentShape.Nozzle nozzle, double radius, double s) {
            var transform = shape.transform();
            int axis = nozzle.axis(), u = (axis + 1) % 3, v = (axis + 2) % 3, around = 24;
            for (int j = 0; j < around; j++) {
                double angle = 2 * Math.PI * j / around;
                var p = nozzle.base().clone();
                p[axis] += nozzle.sign() * s;
                p[u] += radius * Math.cos(angle);
                p[v] += radius * Math.sin(angle);
                var box = transform.point(p);
                if (ShapeRasterizer.inside(shape, box[0], box[1], box[2])) return false;
            }
            return true;
        }

        /**
         * The largest neck, to 0.05 m and at most a pipe's largest, that stands clear, holding the nozzle's base; searched from
         * just over its unconnected size (whose own neck the solid holds), -1 where even that is not clear.
         */
        static double room(EquipmentShape shape, int k) {
            double best = -1;
            for (double od = Math.ceil((shape.nozzles().get(k).defaultOd() + .02) / .05) * .05; od <= PipeSizes.MAX_OUTSIDE + 1e-9; od += .05) {
                if (!clear(shape, k, od)) break;
                best = od;
            }
            return best;
        }
    }
}
