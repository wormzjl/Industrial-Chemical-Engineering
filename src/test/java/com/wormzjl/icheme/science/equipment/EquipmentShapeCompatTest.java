package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Heading;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import com.wormzjl.icheme.world.level.block.entity.EquipmentConstructorBlockEntity;
import com.wormzjl.icheme.world.level.block.entity.EquipmentShellBlockEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Backward compatibility of saved equipment shapes (batch 2026-10-26-backward-compat, plan section 5.1): a shell's or
 * a constructor's saved shape is read by name with defaults, a value the current rules refuse is repaired and named,
 * a kind this build no longer has is retired, and no saved value makes the reader throw.
 */
class EquipmentShapeCompatTest {
    /** A component design with an exchanger condenser, an In-line reflux pump, one pumparound pump and an ejector. */
    static final ColumnComponents COMPONENTS = new ColumnComponents(ColumnComponents.Condenser.EXCHANGER, ColumnComponents.Pump.IN_LINE,
            List.of(ColumnComponents.Pump.STANDARD), true, false);

    /** The sump steam turned to the front, the pumparound draw to the left. */
    static final List<ColumnTrays.Turn> TURNS = List.of(new ColumnTrays.Turn(EquipmentShape.Role.STEAM, 1, 0), new ColumnTrays.Turn(EquipmentShape.Role.PUMPAROUND_DRAW, 1, 1));

    /** A two-component feed with one sump steam, one draw and one uniform pumparound cooler. */
    static final ColumnOperatingPoint OPERATING = new ColumnOperatingPoint("icheme:tjl20_methane_nitrogen", "icheme:tia_juana_light_methane",
            List.of("Methane", "Nitrogen"), List.of(10.0, 90.5), 638.15, 250_000, 0, 332.15, 4.17, 0,
            List.of(new ColumnOperatingPoint.Steam(33.3, 533.15)), List.of(13.6), List.of(new ColumnOperatingPoint.Pumparound(-1.284e6,
            com.wormzjl.icheme.science.column.v3.V3PumparoundSpec.Split.UNIFORM)));

    /** One shape of every kind at its default size, and a lying drum. */
    static List<EquipmentShape> everyKind() {
        var shapes = new ArrayList<EquipmentShape>();
        shapes.add(EquipmentShape.unit(Kind.INDUCED_DRAFT_COOLING_TOWER,8,3,Heading.NORTH,0).withTowerBank(new TowerBank(6,2)));
        shapes.add(EquipmentShape.drum(2, 4, Orientation.VERTICAL, Heading.NORTH, EquipmentShape.defaultSupport(2, Orientation.VERTICAL)));
        shapes.add(EquipmentShape.drum(2, 6, Orientation.HORIZONTAL, Heading.EAST, EquipmentShape.defaultSupport(2, Orientation.HORIZONTAL)));
        shapes.add(EquipmentShape.column(2, ColumnTrays.simple(20, 10), Heading.SOUTH, EquipmentShape.minimumSkirt(2)));
        // Shape fields 2: a column built from a calculator's design carries its components.
        shapes.add(EquipmentShape.column(3, new ColumnTrays(20, 15, List.of(8), List.of(21), List.of(new ColumnTrays.Pumparound(8, 6)),
                COMPONENTS), Heading.NORTH, EquipmentShape.minimumSkirt(3)));
        // Shape fields 3 (finding F3): side nozzles the player turned.
        shapes.add(EquipmentShape.column(3, new ColumnTrays(20, 15, List.of(8), List.of(21), List.of(new ColumnTrays.Pumparound(8, 6)), COMPONENTS, TURNS),
                Heading.EAST, EquipmentShape.minimumSkirt(3)));
        // Shape fields 4 (batch 2026-10-28-column-operation): the operating point the calculator saved with the design.
        shapes.add(EquipmentShape.column(3, new ColumnTrays(20, 15, List.of(8), List.of(21), List.of(new ColumnTrays.Pumparound(8, 6)), COMPONENTS, TURNS,
                OPERATING), Heading.WEST, EquipmentShape.minimumSkirt(3)));
        // Shape fields 6 (batch 2026-11-02-process-simulator, S3b): two condenser shells, three kettles and two units on the pumparound's cooler.
        var parallel = new ColumnComponents(ColumnComponents.Condenser.EXCHANGER, ColumnComponents.Pump.STANDARD, List.of(ColumnComponents.Pump.STANDARD), false, true,
                new ColumnComponents.Units(2, 3, List.of(2)));
        shapes.add(EquipmentShape.column(3, new ColumnTrays(20, 15, List.of(8), List.of(21), List.of(new ColumnTrays.Pumparound(8, 6)), parallel), Heading.SOUTH,
                EquipmentShape.minimumSkirt(3)));
        // Shape fields 7 (COOLER_MODEL_PLAN.md section 6): an air-then-water condenser, two air cooler banks and three trim shells.
        var trimmed = new ColumnComponents(ColumnComponents.Condenser.AIR_THEN_WATER, ColumnComponents.Pump.STANDARD, List.of(ColumnComponents.Pump.STANDARD), false, true,
                new ColumnComponents.Units(2, 1, List.of(1), 3));
        shapes.add(EquipmentShape.column(3, new ColumnTrays(20, 15, List.of(8), List.of(21), List.of(new ColumnTrays.Pumparound(8, 6)), trimmed), Heading.EAST,
                EquipmentShape.minimumSkirt(3)));
        for (var kind : Kind.values()) {
            if (!kind.designed()) continue;
            double size = ProcessUnit.minDiameter(kind);int length = ProcessUnit.defaultLength(kind, size);
            shapes.add(EquipmentShape.designed(kind, size, length, Heading.WEST, ProcessUnit.defaultSupport(kind, size, length), ProcessUnit.Bank.ONE, 0));
        }
        // Shape fields 8 (batch 2026-11-03-rotating-machinery): machines' design points other than their size's.
        shapes.add(EquipmentShape.unit(Kind.PUMP, .05, 1, Heading.EAST, 0).withDuty(MachineDuty.pump(120, 60, 4)));
        shapes.add(EquipmentShape.unit(Kind.CENTRIFUGAL_COMPRESSOR, .3, 2, Heading.SOUTH, 6).withDuty(MachineDuty.compressor(20000, 150)));
        shapes.add(EquipmentShape.unit(Kind.STEAM_TURBINE, .3, 1, Heading.NORTH, 0).withDuty(MachineDuty.turbine(2500, MachineDuty.Steam.MP, 10)));
        shapes.add(EquipmentShape.unit(Kind.MOTOR, .75, 1, Heading.NORTH, 2).withDuty(MachineDuty.motor(4500, 6, MachineDuty.Drive.VARIABLE)));
        shapes.add(EquipmentShape.unit(Kind.GENERATOR, .75, 1, Heading.WEST, 0).withDuty(MachineDuty.generator(2000, 2)));
        shapes.add(EquipmentShape.unit(Kind.GEARBOX, .75, 1, Heading.EAST, 0).withDuty(MachineDuty.gearbox(5000)));
        shapes.add(EquipmentShape.unit(Kind.EXCHANGER, 1.5, 6, Heading.EAST, 3).withExchangerType(ExchangerType.CROSSFLOW));
        shapes.add(EquipmentShape.column(3, new ColumnTrays(20, 15, List.of(8), List.of(21), List.of(new ColumnTrays.Pumparound(8, 6)),
                trimmed.withExchangers(new ColumnComponents.Exchangers(ExchangerType.CROSSFLOW, ExchangerType.CROSSFLOW, List.of(ExchangerType.CROSSFLOW)))),
                Heading.NORTH, EquipmentShape.minimumSkirt(3)));
        // Shape fields 11 (batch 2026-11-09-batch-distiller, W2): a batch still's options other than the default.
        shapes.add(EquipmentShape.unit(Kind.BATCH_STILL, 750, 1, Heading.SOUTH, 0)
                .withStill(new BatchStillOptions(BatchStillOptions.Heater.ELECTRIC, BatchStillOptions.Condenser.BUILT_IN, 6)));
        shapes.add(EquipmentShape.unit(Kind.BATCH_STILL, 2000, 1, Heading.EAST, 0)
                .withStill(new BatchStillOptions(BatchStillOptions.Heater.LIQUID, BatchStillOptions.Condenser.NONE, 0)));
        // Shape fields 12 (batch 2026-11-10-vacuum-pump, owner V5): a vacuum top held by a liquid ring pump on the receiver.
        shapes.add(EquipmentShape.column(3, new ColumnTrays(20, 15, List.of(8), List.of(21), List.of(new ColumnTrays.Pumparound(8, 6)),
                COMPONENTS.withVacuum(ColumnComponents.Vacuum.LIQUID_RING)), Heading.SOUTH, EquipmentShape.minimumSkirt(3)));
        return shapes;
    }

    /**
     * A batch still's options (shape fields 11): saved by name; a still saved before them is the default still; an unknown
     * heater or an out-of-range column reads its default and is named, the other options kept.
     */
    @Test
    void aStillsOptionsReadByNameAndAreRepairedOneAtATime() {
        var still = everyKind().stream().filter(s -> s.kind() == Kind.BATCH_STILL && s.still().column()).findFirst().orElseThrow();
        var tag = saved(still);
        assertEquals("electric", tag.getCompound("Still").getString("Heater"));
        assertEquals("built_in", tag.getCompound("Still").getString("Condenser"));
        var old = tag.copy();
        old.remove("Still");
        var read = EquipmentShellBlockEntity.readShape(old);
        assertEquals(still.withStill(BatchStillOptions.DEFAULT), read.shape());
        assertTrue(read.repairs().isEmpty(), read.repairs().toString());
        var heater = tag.copy();
        heater.getCompound("Still").putString("Heater", "steam");
        read = EquipmentShellBlockEntity.readShape(heater);
        assertEquals(still.withStill(still.still().withHeater(BatchStillOptions.Heater.SOLID)), read.shape());
        assertEquals(List.of("still heater steam -> solid"), read.repairs());
        var stages = tag.copy();
        stages.getCompound("Still").putInt("Stages", 12);
        read = EquipmentShellBlockEntity.readShape(stages);
        assertEquals(still.withStill(still.still().withStages(0)), read.shape());
        assertEquals(List.of("still stages 12 -> 0"), read.repairs());
        // Every other kind writes no still options.
        assertFalse(saved(EquipmentShape.unit(Kind.PUMP, .05, 1, Heading.EAST, 0)).contains("Still"));
    }

    private static CompoundTag saved(EquipmentShape shape) {
        var tag = new CompoundTag();EquipmentShellBlockEntity.writeShape(tag, shape);return tag;
    }

    @Test
    void everyKindRoundTripsThroughItsSavedFieldsWithNoRepair() {
        for (var shape : everyKind()) {
            var read = EquipmentShellBlockEntity.readShape(saved(shape));
            assertEquals(shape, read.shape(), shape.kind().toString());
            assertTrue(read.repairs().isEmpty(), shape.kind() + ": " + read.repairs());
        }
    }

    /**
     * Member counts (S3b, shape fields 6): a design saved before them reads one unit on every step; a count out of range
     * reads 1 and is named, the kinds and the other counts kept.
     */
    @Test
    void memberCountsReadOneWhenAbsentAndAreRepairedOneAtATime() {
        var built = everyKind().stream().filter(s -> s.trays() != null && s.trays().components() != null && s.trays().components().units().condenser() > 1).findFirst().orElseThrow();
        var tag = saved(built);
        var components = tag.getCompound("Components");
        assertEquals(2, components.getInt("CondenserUnits"));
        var older = tag.copy();
        for (var key : List.of("CondenserUnits", "KettleUnits", "CoolerUnits")) older.getCompound("Components").remove(key);
        var read = EquipmentShellBlockEntity.readShape(older);
        assertEquals(List.of(), read.repairs());
        assertEquals(new ColumnComponents.Units(1, 1, List.of(1)), read.shape().trays().components().units(), "an older design: one unit on every step");
        var wrong = tag.copy();
        wrong.getCompound("Components").putInt("CondenserUnits", ColumnComponents.MAX_UNITS + 1);
        wrong.getCompound("Components").putIntArray("CoolerUnits", new int[]{0});
        read = EquipmentShellBlockEntity.readShape(wrong);
        assertEquals(List.of("condenser units 7 -> 1", "pumparound cooler 1 units 0 -> 1"), read.repairs());
        assertEquals(new ColumnComponents.Units(1, 3, List.of(1)), read.shape().trays().components().units(), "the kettle's count kept");
        assertEquals(built.trays().components().condenser(), read.shape().trays().components().condenser());
    }

    /** The water trim's count (shape fields 7): a design saved before it reads one shell; a count out of range reads 1 and is named. */
    @Test
    void theTrimCountReadsOneWhenAbsentAndIsRepaired() {
        var built = everyKind().stream().filter(s -> s.trays() != null && s.trays().components() != null
                && s.trays().components().condenser() == ColumnComponents.Condenser.AIR_THEN_WATER).findFirst().orElseThrow();
        var tag = saved(built);
        assertEquals(3, tag.getCompound("Components").getInt("TrimUnits"));
        assertEquals("AIR_THEN_WATER", tag.getCompound("Components").getString("Condenser"), "saved by name");
        var older = tag.copy();
        older.getCompound("Components").remove("TrimUnits");
        var read = EquipmentShellBlockEntity.readShape(older);
        assertEquals(List.of(), read.repairs());
        assertEquals(1, read.shape().trays().components().units().trim(), "a design saved before the trim: one shell");
        var wrong = tag.copy();
        wrong.getCompound("Components").putInt("TrimUnits", 0);
        read = EquipmentShellBlockEntity.readShape(wrong);
        assertEquals(List.of("trim units 0 -> 1"), read.repairs());
        assertEquals(new ColumnComponents.Units(2, 1, List.of(1), 1), read.shape().trays().components().units(), "the other counts kept");
    }

    /**
     * What holds a vacuum top (shape fields 12): saved by name only where it is not the ejector, so a design saved before it reads
     * the ejector; a name this build does not have never makes the reader throw.
     */
    @Test
    void theVacuumReadsTheEjectorWhenAbsent() {
        var built = everyKind().stream().filter(s -> s.trays() != null && s.trays().components() != null
                && s.trays().components().vacuum() == ColumnComponents.Vacuum.LIQUID_RING).findFirst().orElseThrow();
        var tag = saved(built);
        assertEquals("LIQUID_RING", tag.getCompound("Components").getString("Vacuum"), "saved by name");
        assertTrue(built.trays().components().vacuumPump());
        var older = tag.copy();
        older.getCompound("Components").remove("Vacuum");
        var read = EquipmentShellBlockEntity.readShape(older);
        assertEquals(List.of(), read.repairs());
        assertEquals(ColumnComponents.Vacuum.EJECTOR, read.shape().trays().components().vacuum(), "a design saved before it: the ejector");
        assertFalse(saved(read.shape()).getCompound("Components").contains("Vacuum"), "the ejector writes no field");
        var unknown = tag.copy();
        unknown.getCompound("Components").putString("Vacuum", "ION_PUMP");
        assertNotNull(EquipmentShellBlockEntity.readShape(unknown).shape());
    }

    @Test
    void aKindThisBuildNoLongerHasIsRetired() {
        var tag = saved(everyKind().getFirst());tag.putString("Kind", "pressure_cooker");
        var read = EquipmentShellBlockEntity.readShape(tag);
        assertNull(read.shape());assertEquals("pressure_cooker", read.retired());
    }

    @Test
    void outOfRangeSizesAreClampedAndSnappedAndEachRepairIsNamed() {
        var tag = saved(EquipmentShape.drum(2, 4, Orientation.VERTICAL, Heading.NORTH, 0));
        tag.putDouble("Diameter", 2.3);tag.putDouble("TangentLength", 70);tag.putString("Front", "up");
        var read = EquipmentShellBlockEntity.readShape(tag);
        assertEquals(2.5, read.shape().diameter());assertEquals(EquipmentShape.MAX_TANGENT_LENGTH, read.shape().tangentLength());assertEquals(Heading.NORTH, read.shape().front());
        assertTrue(read.repairs().containsAll(List.of("front up -> north", "diameter 2.3 -> 2.5", "length 70.0 -> 64.0")), read.repairs().toString());
        // A unit's size snaps to its own grid and range.
        var cyclone = EquipmentShape.unit(Kind.CYCLONE, 2.0, 1, Heading.NORTH, 8);var unit = saved(cyclone);
        unit.putDouble("Diameter", ProcessUnit.maxDiameter(Kind.CYCLONE) * 3);
        var big = EquipmentShellBlockEntity.readShape(unit).shape();
        assertEquals(ProcessUnit.maxDiameter(Kind.CYCLONE), big.diameter(), 1e-9);
    }

    @Test
    void aDrumSavedBeforeItListedItsConnectionsGetsTheStandardInlet() {
        var shape = EquipmentShape.drum(3, 6, Orientation.VERTICAL, Heading.NORTH, 4);var tag = saved(shape);tag.remove("Connections");
        var read = EquipmentShellBlockEntity.readShape(tag);
        assertEquals(EquipmentShape.defaultConnections(3, 6, Orientation.VERTICAL, 4, false), read.shape().connections(), "the behaviour before the field existed");
        assertTrue(read.repairs().isEmpty(), read.repairs().toString());
    }

    /**
     * A machine's design point (shape fields 8) reads by name; one this build cannot read (an unknown drive or steam
     * class, a value out of its ranges) is dropped and named, and the machine takes its size's. A machine saved before
     * the field takes its size's with no repair (decision A3); a condensing turbine is the steam turbine exhausting to a
     * condenser at 10 kPa, its size kept (owner decision D5).
     */
    @Test
    void aMachinesDesignPointReadsByNameAndAnUnreadableOneTakesItsSizes() {
        var motor = EquipmentShape.unit(Kind.MOTOR, .75, 1, Heading.NORTH, 0).withDuty(MachineDuty.motor(250, 2, MachineDuty.Drive.VARIABLE));
        var tag = saved(motor);
        assertEquals("VARIABLE", tag.getCompound("Duty").getString("Drive"), "drives saved by name");
        tag.getCompound("Duty").putString("Drive", "hydraulic");
        var read = EquipmentShellBlockEntity.readShape(tag);
        assertEquals(motor.withDuty(UnitDesign.of(Kind.MOTOR).duty(250, 1)), read.shape());
        assertEquals(1, read.repairs().size(), read.repairs().toString());
        assertTrue(read.repairs().getFirst().startsWith("duty (no drive hydraulic) -> its size's"), read.repairs().toString());
        var pump = saved(EquipmentShape.unit(Kind.PUMP, .25, 1, Heading.NORTH, 0).withDuty(MachineDuty.pump(400, 80, 4)));
        pump.getCompound("Duty").putDouble("Head", 5000);
        var repaired = EquipmentShellBlockEntity.readShape(pump);
        assertFalse(repaired.repairs().isEmpty());
        assertEquals(UnitDesign.of(Kind.PUMP).duty(repaired.shape().diameter(), 1), repaired.shape().duty());
        // Before the field: the size's design point, no repair.
        var custom = EquipmentShape.unit(Kind.PUMP, .25, 1, Heading.NORTH, 0).withDuty(MachineDuty.pump(400, 80, 4));
        var old = saved(custom);
        old.remove("Duty");
        var before = EquipmentShellBlockEntity.readShape(old);
        assertEquals(EquipmentShape.unit(Kind.PUMP, custom.diameter(), 1, Heading.NORTH, 0), before.shape());
        assertEquals(List.of(), before.repairs());
        // A condensing turbine.
        var turbine = saved(EquipmentShape.unit(Kind.STEAM_TURBINE, 1, 1, Heading.SOUTH, 0));
        turbine.remove("Duty");
        turbine.putString("Kind", "condensing_turbine");
        var condensing = EquipmentShellBlockEntity.readShape(turbine);
        assertEquals(Kind.STEAM_TURBINE, condensing.shape().kind());
        assertEquals(1, condensing.shape().diameter(), 1e-9);
        assertEquals(SteamTurbineDesign.CONDENSING_EXHAUST, condensing.shape().duty().exhaust());
        assertTrue(condensing.shape().duty().vacuum());
        assertEquals(List.of(), condensing.repairs());
    }

    /** Any saved value: the reader gives a valid shape or a retired kind, and never throws. */
    @Test
    void anySavedValueReadsToAValidShapeOrARetiredKind() {
        var random = new Random(20261026);
        var kinds = new ArrayList<String>();for (var kind : Kind.values()) kinds.add(kind.name().toLowerCase(Locale.ROOT));kinds.add("retired_unit");
        var headings = List.of("north", "east", "south", "west", "up", "");
        int retired = 0, repaired = 0;
        for (int i = 0; i < 3000; i++) {
            double diameter = random.nextInt(20) == 0 ? Double.NaN : -5 + 35 * random.nextDouble(), length = -5 + 105 * random.nextDouble();
            int[] garbage = random.ints(random.nextInt(8), -3, 40).toArray();
            var saved = new ShapeRepair.Saved(kinds.get(random.nextInt(kinds.size())), headings.get(random.nextInt(headings.size())), random.nextBoolean() ? "horizontal" : "sideways",
                    diameter, length, random.nextInt(45) - 5, random.nextBoolean() ? ColumnTrays.simple(5 + random.nextInt(40), 2).encode() : garbage,
                    random.nextBoolean() ? null : garbage, random.nextBoolean(), random.nextInt(8) - 2, random.nextBoolean() ? null : garbage);
            var read = assertDoesNotThrow(() -> ShapeRepair.read(saved), saved.toString());
            if (read.retired() != null) {
                retired++;assertEquals("retired_unit", read.retired());continue;
            }
            assertNotNull(read.shape());if (!read.repairs().isEmpty()) repaired++;
            if (i % 100 == 0) assertNotEquals(0, ShapeRasterizer.stamp(read.shape()), "the repaired shape is drawn: " + read.shape());
        }
        assertTrue(retired > 0 && repaired > 1000, "retired " + retired + ", repaired " + repaired);
    }

    /** A column's components are read by name; one this build cannot read is dropped and named, the column kept (batch 2026-10-26-multi-machine-framework). */
    @Test
    void aColumnsComponentsReadByNameAndAnUnknownKindIsRepairedAway() {
        var built = everyKind().stream().filter(s -> s.trays() != null && s.trays().components() != null).findFirst().orElseThrow();
        var tag = saved(built);
        assertEquals("EXCHANGER", tag.getCompound("Components").getString("Condenser"), "kinds saved by name");
        tag.getCompound("Components").putString("RefluxPump", "jet_pump");
        var read = EquipmentShellBlockEntity.readShape(tag);
        assertEquals(built.trays().withComponents(null), read.shape().trays());
        assertEquals(1, read.repairs().size(), read.repairs().toString());
        assertTrue(read.repairs().getFirst().startsWith("components (reflux pump: unknown kind jet_pump)"), read.repairs().toString());
        // A field that is not a compound is named too.
        var broken = saved(built);broken.putInt("Components", 7);
        assertFalse(EquipmentShellBlockEntity.readShape(broken).repairs().isEmpty());
    }

    /**
     * A column's turned nozzles are read by role name (finding F3); a turn this build cannot read is dropped and named,
     * and that nozzle takes its side by itself; the other turns and the column are kept.
     */
    @Test
    void aColumnsTurnsReadByNameAndAnUnreadableTurnIsDropped() {
        var built = everyKind().stream().filter(s -> s.trays() != null && !s.trays().turns().isEmpty()).findFirst().orElseThrow();
        var tag = saved(built);
        assertEquals("STEAM", tag.getList("Turns", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).getString("Role"), "roles saved by name");
        tag.getList("Turns", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).putString("Role", "sidecar");
        var read = EquipmentShellBlockEntity.readShape(tag);
        assertEquals(built.trays().withTurns(TURNS.subList(1, 2)), read.shape().trays());
        assertEquals(1, read.repairs().size(), read.repairs().toString());
        assertTrue(read.repairs().getFirst().startsWith("turn sidecar 1 (no role sidecar) -> automatic"), read.repairs().toString());
        // A side out of range, a nozzle turned twice and a field that is not a list are named too.
        var twice = saved(built);
        var list = twice.getList("Turns", net.minecraft.nbt.Tag.TAG_COMPOUND);
        list.getCompound(0).putInt("Side", 4);list.add(list.getCompound(1).copy());
        var repairs = EquipmentShellBlockEntity.readShape(twice).repairs();
        assertEquals(2, repairs.size(), repairs.toString());
        var broken = saved(built);broken.putInt("Turns", 7);
        assertEquals(built.trays().withTurns(List.of()), EquipmentShellBlockEntity.readShape(broken).shape().trays());
        assertEquals(1, EquipmentShellBlockEntity.readShape(broken).repairs().size());
    }

    /**
     * A column's operating point (batch 2026-10-28-column-operation) reads by name; one this build cannot read (an unknown
     * pumparound split, a missing value, a field that is not a compound) is dropped and named, the column and its
     * components kept: such a column cannot run, as a column built before the field.
     */
    @Test
    void aColumnsOperatingPointReadsByNameAndAnUnreadableOneIsRepairedAway() {
        var built = everyKind().stream().filter(s -> s.trays() != null && s.trays().operating() != null).findFirst().orElseThrow();
        var tag = saved(built);
        assertEquals("UNIFORM", tag.getCompound("Operating").getList("PumparoundSplits", net.minecraft.nbt.Tag.TAG_STRING).getString(0), "splits saved by name");
        var split = tag.copy();
        split.getCompound("Operating").getList("PumparoundSplits", net.minecraft.nbt.Tag.TAG_STRING).set(0, net.minecraft.nbt.StringTag.valueOf("SPRAY"));
        var read = EquipmentShellBlockEntity.readShape(split);
        assertEquals(built.trays().withOperating(null), read.shape().trays());
        assertEquals(List.of("operating point (no pumparound split SPRAY) -> none"), read.repairs());
        var missing = tag.copy();
        missing.getCompound("Operating").remove("RefluxRatio");
        assertEquals(1, EquipmentShellBlockEntity.readShape(missing).repairs().size());
        var broken = tag.copy();
        broken.putInt("Operating", 7);
        assertNull(EquipmentShellBlockEntity.readShape(broken).shape().trays().operating());
        // Without a component design an operating point is refused with it.
        var noComponents = tag.copy();
        noComponents.putInt("Components", 7);
        assertNull(EquipmentShellBlockEntity.readShape(noComponents).shape().trays().operating());
    }

    /**
     * A constructor builds a column from an imported design only (plan section 4.5): the import sets the form, the
     * diameter, the trays and components and a skirt at least the column's least; a design the player sends keeps the
     * import; the imported column round-trips through the constructor's tags.
     */
    @Test
    void aConstructorBuildsAColumnFromItsImportedDesignOnly() {
        var fresh = EquipmentConstructorBlockEntity.Design.DEFAULT;
        assertFalse(new EquipmentConstructorBlockEntity.Design(fresh.diameter(), fresh.tangentLength(), VesselPlacement.Form.COLUMN, EquipmentShape.minimumSkirt(2),
                fresh.placement(), fresh.trays(), fresh.connections(), fresh.paint(), fresh.bank(), false, 0).buildable(), "no import, no column");
        var column = new ColumnDesign(new ColumnTrays(20, 15, List.of(8), List.of(21), List.of(new ColumnTrays.Pumparound(8, 6)), COMPONENTS).withOperating(OPERATING), 6);
        var imported = fresh.imported(column);
        assertTrue(imported.buildable());
        assertEquals(VesselPlacement.Form.COLUMN, imported.form());
        var shape = imported.shape(net.minecraft.core.Direction.SOUTH);
        assertEquals(6, shape.diameter());
        assertEquals(column.trays(), shape.trays(), "the trays carry the components and the operating point into the shape");
        assertTrue(imported.support() >= EquipmentShape.minimumSkirt(6));
        assertEquals(fresh.placement(), imported.placement());
        // The player's edit (a drum diameter, other trays) keeps the imported column.
        var edited = new EquipmentConstructorBlockEntity.Design(2, imported.tangentLength(), VesselPlacement.Form.COLUMN, imported.support(), imported.placement(),
                ColumnTrays.simple(5, 2), imported.connections(), imported.paint(), imported.bank(), false, 0);
        var kept = imported.keepingColumn(edited);
        assertEquals(column, kept.column());
        assertEquals(shape, kept.shape(net.minecraft.core.Direction.SOUTH));
        // The constructor's tags: written and read with no repair.
        var tag = new CompoundTag();
        EquipmentConstructorBlockEntity.writeDesign(tag, imported);
        var repairs = new ArrayList<String>();
        var read = EquipmentConstructorBlockEntity.readDesign(tag, fresh, repairs::add);
        assertEquals(column, read.column());
        assertEquals(shape, read.shape(net.minecraft.core.Direction.SOUTH));
        assertEquals(List.of(), repairs);
    }

    @Test
    void aConstructorDesignIsRepairedKeepingItsPlacementAndPaint() {
        var fresh = EquipmentConstructorBlockEntity.Design.DEFAULT;
        var tag = new CompoundTag();
        tag.putDouble("Diameter", 0.2);tag.putInt("TangentLength", 4);tag.putString("Form", "vertical");tag.putInt("Support", 0);
        tag.putIntArray("Offsets", new int[]{3, -1, 0});tag.putIntArray("Paint", new Paint(0x123456, 0x654321).encode());
        var repairs = new ArrayList<String>();
        var design = EquipmentConstructorBlockEntity.readDesign(tag, fresh, repairs::add);
        assertEquals(EquipmentShape.MIN_DIAMETER, design.diameter());assertEquals(new VesselPlacement(3, -1, 0), design.placement());
        assertEquals(new Paint(0x123456, 0x654321), design.paint());assertTrue(repairs.contains("diameter 0.2 -> 0.5"), repairs.toString());
        // A form this build no longer has: the default shape, with the saved placement and paint.
        tag.putString("Form", "pressure_cooker");repairs.clear();
        var retired = EquipmentConstructorBlockEntity.readDesign(tag, fresh, repairs::add);
        assertEquals(fresh.form(), retired.form());assertEquals(new VesselPlacement(3, -1, 0), retired.placement());assertEquals(new Paint(0x123456, 0x654321), retired.paint());
        assertEquals(1, repairs.size(), repairs.toString());
    }

    /**
     * Finding F2 (batch 2026-10-26-multi-machine-framework): a constructor keeps every form's fields across a reload in
     * any form, as it keeps them across a form switch (a drum's side connections and boot, the trays, the air cooler's
     * bank, the drum length while a column is shown). Each design reads back as it was written, with no repair.
     */
    @Test
    void aConstructorKeepsEveryFormsFieldsAcrossAReload() {
        var fresh = EquipmentConstructorBlockEntity.Design.DEFAULT;
        var trays = ColumnTrays.simple(12, 6);
        var bank = new ProcessUnit.Bank(3, 2);
        int saddles = EquipmentShape.minimumBootSupport(2, 6);
        // A lying drum with a boot and its side connection; the trays and the bank belong to the other forms.
        var lying = new EquipmentConstructorBlockEntity.Design(2, 6, VesselPlacement.Form.LENGTHWISE, saddles, fresh.placement(), trays,
                EquipmentShape.defaultConnections(2, 6, Orientation.HORIZONTAL, saddles, true), fresh.paint(), bank, true, 0);
        var designs = new ArrayList<>(List.of(lying,
                // Standing, its bottom outlet turned: the boot waits for the drum to lie again.
                new EquipmentConstructorBlockEntity.Design(2, 6, VesselPlacement.Form.VERTICAL, 3, fresh.placement(), trays,
                        EquipmentShape.defaultConnections(2, 6, Orientation.VERTICAL, 3, false, 2), fresh.paint(), bank, true, 2),
                lying.imported(new ColumnDesign(new ColumnTrays(20, 15, List.of(8), List.of(21), List.of(new ColumnTrays.Pumparound(8, 6)), COMPONENTS), 6))));
        for (var kind : List.of(Kind.AIR_COOLER, Kind.PUMP)) {
            double d = ProcessUnit.minDiameter(kind);
            int length = ProcessUnit.defaultLength(kind, d);
            var form = VesselPlacement.Form.valueOf(kind.name());
            designs.add(new EquipmentConstructorBlockEntity.Design(d, length, form, ProcessUnit.defaultSupport(kind, d, length), fresh.placement(), trays,
                    lying.connections(), fresh.paint(), bank, true, 0));
        }
        for (var design : designs) {
            var tag = new CompoundTag();
            EquipmentConstructorBlockEntity.writeDesign(tag, design);
            var repairs = new ArrayList<String>();
            assertEquals(design, EquipmentConstructorBlockEntity.readDesign(tag, fresh, repairs::add), design.form()::name);
            assertEquals(List.of(), repairs, design.form()::name);
        }
    }

    /**
     * A machine's design point as the constructor steps it (option A, batch 2026-11-03-rotating-machinery): every setting
     * to both ends, every pole count, the variable drive and every steam class make a valid design whose sizes follow it,
     * and it reads back as written. A design saved before it (no {@code Duty}) takes the design point its sizes give.
     */
    @Test
    void aConstructorKeepsAMachinesDesignPointAcrossAReload() {
        var fresh = EquipmentConstructorBlockEntity.Design.DEFAULT;
        int checked = 0;
        for (var kind : Kind.values()) {
            if (!kind.designed() || !UnitDesign.of(kind).hasDuty()) continue;
            var design = UnitDesign.of(kind);
            var form = VesselPlacement.Form.valueOf(kind.name());
            double d = ProcessUnit.sizes(kind).getFirst();
            var start = design.duty(d, ProcessUnit.defaultLength(kind, d));
            var points = new ArrayList<MachineDuty>();
            for (int steps : new int[]{-1000, 1, 1000})
                for (var setting : List.of("flow", "head", "power", "exhaust")) points.add(design.step(start, setting, steps));
            if (start.poles() != 0) for (int poles : kind == Kind.PUMP ? List.of(2, 4) : MachineDuty.POLES) points.add(start.withPoles(poles));
            if (kind == Kind.MOTOR) points.add(start.withDrive(MachineDuty.Drive.VARIABLE));
            if (start.steam() != null)
                for (var steam : MachineDuty.Steam.values()) points.add(design.step(design.step(start, "exhaust", 1000).withSteam(steam), "power", 1000));
            for (var point : points) {
                var made = new EquipmentConstructorBlockEntity.Design(0, 0, form, ProcessUnit.minSupport(kind, design.size(point), design.length(point)), fresh.placement(),
                        fresh.trays(), List.of(), fresh.paint(), ProcessUnit.Bank.ONE, false, 0, null, List.of(), TowerBank.ONE, point);
                assertEquals(point, made.duty(), kind::name);
                assertEquals(design.size(point), made.diameter(), kind::name);
                var tag = new CompoundTag();
                EquipmentConstructorBlockEntity.writeDesign(tag, made);
                var repairs = new ArrayList<String>();
                assertEquals(made, EquipmentConstructorBlockEntity.readDesign(tag, fresh, repairs::add), kind::name);
                assertEquals(List.of(), repairs, kind::name);
                checked++;
            }
            // Saved before the constructor kept a design point: the one its sizes give.
            var old = new CompoundTag();
            EquipmentConstructorBlockEntity.writeDesign(old, new EquipmentConstructorBlockEntity.Design(0, 0, form, ProcessUnit.minSupport(kind, design.size(points.getLast()),
                    design.length(points.getLast())), fresh.placement(), fresh.trays(), List.of(), fresh.paint(), ProcessUnit.Bank.ONE, false, 0, null, List.of(), TowerBank.ONE,
                    points.getLast()));
            old.remove("Duty");
            var read = EquipmentConstructorBlockEntity.readDesign(old, fresh, repair -> {
            });
            // Absence of Duty means the historical fixed-speed motor, not a new VFD default.
            var legacy = kind == Kind.MOTOR ? MachineDuty.motor(read.diameter(), 4, MachineDuty.Drive.FIXED)
                    : design.duty(read.diameter(), read.tangentLength());
            assertEquals(legacy, read.duty(), kind::name);
        }
        assertTrue(checked > 60, "design points checked: " + checked);
    }

    // ---------------- the corpus: saved shell tags per shape-field change ----------------

    private static final Path CORPUS = Path.of("src/test/resources/compat/equipment");
    /**
     * The shape fields' revision: the corpus file this build's tags are kept in. Raise it with a shape-field change (2: a
     * column's components; 3: a column's turned nozzles; 4: a column's operating point; 5: a tower's cell bank; 6: a
     * column's member counts; 7: a column's water trim count and its air-then-water condenser; 8: a machine's design
     * point; 11: a batch still's options; 12: what holds a column's vacuum top).
     */
    private static final int SHAPE_FIELDS = 12;

    /** A column's components as an image written before the member counts shows them. */
    private static final java.util.regex.Pattern COMPONENTS_BEFORE_UNITS = java.util.regex.Pattern.compile(
            "(ColumnComponents\\[condenser=\\w+, refluxPump=\\w+, pumparoundPumps=\\[([A-Z_, ]*)\\], ejector=\\w+, kettle=\\w+)\\]");

    /** Member counts as an image written before the water trim's count shows them. */
    private static final java.util.regex.Pattern UNITS_BEFORE_TRIM = java.util.regex.Pattern.compile("(Units\\[condenser=\\d+, kettle=\\d+, coolers=\\[[0-9, ]*\\])\\]");

    /**
     * {@code expected} as this build prints it: components written before the member counts read one unit on every step,
     * and counts written before the trim's read one trim shell.
     */
    static String withUnits(String expected) {
        var m = COMPONENTS_BEFORE_UNITS.matcher(expected);
        var out = new StringBuilder();
        while (m.find()) {
            int pumps = m.group(2).isBlank() ? 0 : m.group(2).split(", ").length;
            var units = new ColumnComponents.Units(1, 1, java.util.Collections.nCopies(pumps, 1));
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(m.group(1) + ", units=" + units + "]"));
        }
        return UNITS_BEFORE_TRIM.matcher(m.appendTail(out).toString()).replaceAll("$1, trim=1]");
    }

    /**
     * Every corpus file's saved shell tags read with no repair, to the shapes recorded beside them. The file of this
     * build's shape fields must exist (AGENTS.md: a shape-field change adds one); when it is missing the test writes it
     * under {@code build/compat/equipment/} to be copied into the corpus.
     */
    @Test
    void everyCorpusShapeReadsAsItWasSaved() throws IOException, com.mojang.brigadier.exceptions.CommandSyntaxException {
        var current = CORPUS.resolve("shapes-" + SHAPE_FIELDS + ".snbt");
        if (!Files.exists(current)) {
            var written = Path.of("build/compat/equipment/shapes-" + SHAPE_FIELDS + ".snbt");Files.createDirectories(written.getParent());
            var lines = new ArrayList<String>();
            for (var shape : everyKind()) {
                var entry = new CompoundTag();entry.put("Saved", saved(shape));entry.putString("Expected", shape.toString());lines.add(entry.toString());
            }
            Files.write(written, lines, StandardCharsets.UTF_8);
            fail("No corpus file of shape fields " + SHAPE_FIELDS + ": copy " + written.toAbsolutePath() + " to " + current);
        }
        try (var files = Files.list(CORPUS)) {
            for (var file : files.sorted().toList())
                for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) continue;
                    var entry = TagParser.parseTag(line);var read = EquipmentShellBlockEntity.readShape(entry.getCompound("Saved"));
                    assertNotNull(read.shape(), file + ": " + line);assertTrue(read.repairs().isEmpty(), file + ": " + read.repairs());
                    // A column's length is no fact: it follows its trays and diameter by the current layout rule
                    // (ColumnTrays.tangentLength; batch 2026-10-27-column-sump), and a placed column refreshes to it.
                    var expected = entry.getString("Expected");
                    if(!expected.contains("towerBank="))expected=expected.substring(0,expected.length()-1)+", towerBank=TowerBank[columns=1, rows=1]]";
                    expected = withUnits(expected);
                    if (!expected.contains("exchangers=Exchangers[") && read.shape().trays() != null && read.shape().trays().components() != null) {
                        var pattern = java.util.regex.Pattern.compile("(units=Units\\[condenser=\\d+, kettle=\\d+, coolers=\\[[0-9, ]*\\], trim=\\d+\\])\\]");
                        var match = pattern.matcher(expected);
                        var upgraded = new StringBuilder();
                        while (match.find()) match.appendReplacement(upgraded, java.util.regex.Matcher.quoteReplacement(match.group(1) + ", exchangers=" + read.shape().trays().components().exchangers() + "]"));
                        expected = match.appendTail(upgraded).toString();
                    }
                    if (!expected.contains("duty=MachineDuty[")) {
                        // Shape fields 8: a machine saved before its design point takes its size's (decision A3); a
                        // condensing turbine is the steam turbine exhausting to a condenser (owner decision D5).
                        boolean condensing = expected.contains("kind=CONDENSING_TURBINE");
                        expected = expected.replace("kind=CONDENSING_TURBINE", "kind=STEAM_TURBINE");
                        var shape = read.shape();
                        var duty = shape.designed() ? UnitDesign.of(shape.kind()).duty(shape.diameter(), (int) shape.tangentLength()) : MachineDuty.NONE;
                        if(shape.kind()==Kind.MOTOR)duty=MachineDuty.motor(shape.diameter(),4,MachineDuty.Drive.FIXED);
                        if (condensing) duty = MachineDuty.turbine(duty.power(), duty.steam(), SteamTurbineDesign.CONDENSING_EXHAUST);
                        expected = expected.substring(0, expected.length() - 1) + ", duty=" + duty + "]";
                    }
                    if (read.shape().column()) expected = expected.replaceFirst("tangentLength=[0-9.]+", "tangentLength=" + read.shape().tangentLength());
                    if (!expected.contains("exchangerType=")) expected = expected.substring(0, expected.length() - 1) + ", exchangerType=STANDARD]";
                    // Shape fields 11: a shape saved before the still options has the default ones.
                    if (!expected.contains("still=")) expected = expected.substring(0, expected.length() - 1) + ", still=" + BatchStillOptions.DEFAULT + "]";
                    if(!expected.contains("baseSpeed="))expected=expected.replaceAll("(exhaust=[0-9.Ee+-]+)\\]","$1, baseSpeed=0.0]");
                    assertEquals(expected, read.shape().toString(), file.toString());
                }
        }
    }
}
