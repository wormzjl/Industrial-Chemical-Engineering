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

    /** One shape of every kind at its default size, and a lying drum. */
    static List<EquipmentShape> everyKind() {
        var shapes = new ArrayList<EquipmentShape>();
        shapes.add(EquipmentShape.drum(2, 4, Orientation.VERTICAL, Heading.NORTH, EquipmentShape.defaultSupport(2, Orientation.VERTICAL)));
        shapes.add(EquipmentShape.drum(2, 6, Orientation.HORIZONTAL, Heading.EAST, EquipmentShape.defaultSupport(2, Orientation.HORIZONTAL)));
        shapes.add(EquipmentShape.column(2, ColumnTrays.simple(20, 10), Heading.SOUTH, EquipmentShape.minimumSkirt(2)));
        // Shape fields 2: a column built from a calculator's design carries its components.
        shapes.add(EquipmentShape.column(3, new ColumnTrays(20, 15, List.of(8), List.of(21), List.of(new ColumnTrays.Pumparound(8, 6)),
                COMPONENTS), Heading.NORTH, EquipmentShape.minimumSkirt(3)));
        // Shape fields 3 (finding F3): side nozzles the player turned.
        shapes.add(EquipmentShape.column(3, new ColumnTrays(20, 15, List.of(8), List.of(21), List.of(new ColumnTrays.Pumparound(8, 6)), COMPONENTS, TURNS),
                Heading.EAST, EquipmentShape.minimumSkirt(3)));
        for (var kind : Kind.values()) {
            if (!kind.designed()) continue;
            double size = ProcessUnit.minDiameter(kind);int length = ProcessUnit.defaultLength(kind, size);
            shapes.add(EquipmentShape.designed(kind, size, length, Heading.WEST, ProcessUnit.defaultSupport(kind, size, length), ProcessUnit.Bank.ONE, 0));
        }
        return shapes;
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
     * A constructor builds a column from an imported design only (plan section 4.5): the import sets the form, the
     * diameter, the trays and components and a skirt at least the column's least; a design the player sends keeps the
     * import; the imported column round-trips through the constructor's tags.
     */
    @Test
    void aConstructorBuildsAColumnFromItsImportedDesignOnly() {
        var fresh = EquipmentConstructorBlockEntity.Design.DEFAULT;
        assertFalse(new EquipmentConstructorBlockEntity.Design(fresh.diameter(), fresh.tangentLength(), VesselPlacement.Form.COLUMN, EquipmentShape.minimumSkirt(2),
                fresh.placement(), fresh.trays(), fresh.connections(), fresh.paint(), fresh.bank(), false, 0).buildable(), "no import, no column");
        var column = new ColumnDesign(new ColumnTrays(20, 15, List.of(8), List.of(21), List.of(new ColumnTrays.Pumparound(8, 6)), COMPONENTS), 6);
        var imported = fresh.imported(column);
        assertTrue(imported.buildable());
        assertEquals(VesselPlacement.Form.COLUMN, imported.form());
        var shape = imported.shape(net.minecraft.core.Direction.SOUTH);
        assertEquals(6, shape.diameter());
        assertEquals(column.trays(), shape.trays(), "the trays carry the components into the shape");
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

    // ---------------- the corpus: saved shell tags per shape-field change ----------------

    private static final Path CORPUS = Path.of("src/test/resources/compat/equipment");
    /**
     * The shape fields' revision: the corpus file this build's tags are kept in. Raise it with a shape-field change (2: a
     * column's components; 3: a column's turned nozzles).
     */
    private static final int SHAPE_FIELDS = 3;

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
                    if (read.shape().column()) expected = expected.replaceFirst("tangentLength=[0-9.]+", "tangentLength=" + read.shape().tangentLength());
                    assertEquals(expected, read.shape().toString(), file.toString());
                }
        }
    }
}
