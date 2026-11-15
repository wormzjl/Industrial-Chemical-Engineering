package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Heading;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Orientation;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Gate: every layout's world cost is recorded (batch 2026-10-26-backward-compat, plan section 5.3). Per kind at its
 * smallest, middle and largest size, a checked-in table holds the box, the cell counts, a digest of the cells, box and
 * nozzles (the {@code stamp} column, {@link ShapeRasterizer#legacyStamp}), each nozzle's pipe block and the layout
 * revision. A layout change fails this test until the table is updated with it, so the review sees what a placed
 * vessel's refresh does: cells added and dropped, and nozzles moved (a pipe that can part; a moved nozzle needs the
 * owner's approval). A change of the digest at a recorded shape whose design's layout revision did not move fails
 * first, with no table to copy (batch 2026-11-13-codebase-review, E20): the placed structures of that design would keep
 * their old cells.
 */
class EquipmentLayoutGoldenTest {
    private static final Path TABLE = Path.of("src/test/resources/compat/layouts/layouts.txt");
    /**
     * A line's last column, the layout revision ({@link ShapeRasterizer#layoutRevision}), written only where it is not 1:
     * a line without it reads 1, the revision of every layout when revisions were declared, so the table recorded before
     * them stands as it was.
     */
    private static final String REVISION = " | revision ";

    /** Each kind at its smallest, middle and largest size, facing north. */
    static List<EquipmentShape> shapes() {
        var shapes = new ArrayList<EquipmentShape>();
        for (var orientation : List.of(Orientation.VERTICAL, Orientation.HORIZONTAL))
            for (double diameter : sizes(EquipmentShape.MIN_DIAMETER, EquipmentShape.MAX_DIAMETER)) {
                double length = Math.min(EquipmentShape.MAX_TANGENT_LENGTH, Math.rint(2 * diameter));
                shapes.add(EquipmentShape.drum(diameter, length, orientation, Heading.NORTH, EquipmentShape.defaultSupport(diameter, orientation)));
            }
        for (double diameter : sizes(EquipmentShape.MIN_DIAMETER, EquipmentShape.MAX_COLUMN_DIAMETER))
            shapes.add(EquipmentShape.column(diameter, ColumnTrays.simple(20, 10), Heading.NORTH, EquipmentShape.minimumSkirt(diameter)));
        for (var kind : Kind.values()) {
            if (!kind.designed()) continue;
            double low = UnitDesign.of(kind).minSize(), high = UnitDesign.of(kind).maxSize();
            for (double diameter : new double[]{low, UnitDesign.of(kind).snap((low + high) / 2), high}) {
                int length = UnitDesign.of(kind).defaultLength(diameter);
                shapes.add(EquipmentShape.designed(kind, diameter, length, Heading.NORTH, ProcessUnit.defaultSupport(kind, diameter, length), ProcessUnit.Bank.ONE, 0));
            }
        }
        for (double diameter : new double[]{.5, 1.5, 2.5}) {
            int length = UnitDesign.of(Kind.EXCHANGER).defaultLength(diameter);
            shapes.add(EquipmentShape.unit(Kind.EXCHANGER, diameter, length, Heading.NORTH, ProcessUnit.defaultSupport(Kind.EXCHANGER, diameter, length))
                    .withExchangerType(ExchangerType.CROSSFLOW));
        }
        // A batch still's other layouts (batch 2026-11-09-batch-distiller, W2), at its middle pot: a burner with the
        // tallest column, an electric still without a condenser, and a fired one with the shortest column.
        for (var options : List.of(new BatchStillOptions(BatchStillOptions.Heater.LIQUID, BatchStillOptions.Condenser.BUILT_IN, BatchStillOptions.MAX_STAGES),
                new BatchStillOptions(BatchStillOptions.Heater.ELECTRIC, BatchStillOptions.Condenser.NONE, 0),
                new BatchStillOptions(BatchStillOptions.Heater.SOLID, BatchStillOptions.Condenser.BUILT_IN, BatchStillOptions.MIN_STAGES)))
            shapes.add(EquipmentShape.unit(Kind.BATCH_STILL, 500, 1, Heading.NORTH, 0).withStill(options));
        // A two-stage liquid ring (batch 2026-11-10-vacuum-pump): its smallest and largest two-stage models.
        for (double model : new double[]{1, 3}) shapes.add(EquipmentShape.unit(Kind.LIQUID_RING_PUMP, model, 2, Heading.NORTH, 0));
        // A gas turbine with its steam coil (batch 2026-11-10-prime-movers): its smallest and largest models.
        for (double model : new double[]{1, 9}) shapes.add(EquipmentShape.unit(Kind.GAS_TURBINE, model, 2, Heading.NORTH, 0));
        // Steam turbines above one casing (owner P12): HP to vacuum at 300 MW (HP, MP, double-flow LP), HP to the MP header at
        // 150 MW (HP, MP), MP to vacuum at 80 MW (MP, LP), VHP into the LP header at 60 MW (HP, MP).
        for (var duty : List.of(MachineDuty.turbine(300000, MachineDuty.Steam.HP, 10), MachineDuty.turbine(150000, MachineDuty.Steam.HP, 1000),
                MachineDuty.turbine(80000, MachineDuty.Steam.MP, 10), MachineDuty.turbine(63000, MachineDuty.Steam.VHP, 450),
                // The LP class above one casing (batch 2026-11-15-equipment-visual-design, W6): a single and a double flow.
                MachineDuty.turbine(63000, MachineDuty.Steam.LP, 10), MachineDuty.turbine(150000, MachineDuty.Steam.LP, 10)))
            shapes.add(EquipmentShape.unit(Kind.STEAM_TURBINE, 3, 1, Heading.NORTH, 0).withDuty(duty));
        // The generators the table held before their range grew to 400 MW: their layouts stand.
        for (double kilowatts : new double[]{10000, 20000}) {
            int length = UnitDesign.of(Kind.GENERATOR).defaultLength(kilowatts);
            shapes.add(EquipmentShape.designed(Kind.GENERATOR, kilowatts, length, Heading.NORTH, ProcessUnit.defaultSupport(Kind.GENERATOR, kilowatts, length), ProcessUnit.Bank.ONE, 0));
        }
        return shapes;
    }

    private static double[] sizes(double low, double high) {
        return new double[]{low, Math.rint((low + high)) / 2, high};
    }

    /** One table line: the shape, then what its layout places. */
    static String line(EquipmentShape shape) {
        var layout = ShapeRasterizer.layout(shape);
        var printed = shape.toString().replace(", baseSpeed=0.0]","]");
        // Shape fields 11: a shape with the default still options prints as it did before they existed.
        if (shape.still().equals(BatchStillOptions.DEFAULT)) printed = printed.replace(", still=" + BatchStillOptions.DEFAULT + "]", "]");
        if (shape.exchangerType() == ExchangerType.STANDARD) printed = printed.replace(", exchangerType=STANDARD]", "]").replaceAll(", exchangers=Exchangers\\[condenser=STANDARD, trim=STANDARD, coolers=\\[[A-Z, ]*\\]\\]", "");
        var b = new StringBuilder(printed).append(" | box ").append(shape.sizeX()).append(' ').append(shape.sizeY()).append(' ').append(shape.sizeZ())
                .append(" | shell ").append(layout.shell().size()).append(" interior ").append(layout.interior().size())
                .append(" | stamp ").append(Long.toHexString(ShapeRasterizer.legacyStamp(shape))).append(" | pipes");
        for (var nozzle : ShapeRasterizer.ports(shape))
            b.append(' ').append(nozzle.cell().x() + nozzle.dx()).append(',').append(nozzle.cell().y() + nozzle.dy()).append(',').append(nozzle.cell().z() + nozzle.dz());
        // An electric machine's or fan unit's terminal box, its FE port (W5): an existing cell, recorded where there is one.
        var terminal = ShapeRasterizer.terminal(shape);
        if (terminal != null) b.append(" | terminal ").append(terminal.x()).append(',').append(terminal.y()).append(',').append(terminal.z());
        int revision = ShapeRasterizer.layoutRevision(shape);
        if (revision != 1) b.append(REVISION).append(revision);
        return b.toString();
    }

    /** The shape a table line records: the line before its box. */
    static String shapeOf(String line) {
        int at = line.indexOf(" | box ");
        return at < 0 ? line : line.substring(0, at);
    }

    /** A table line's digest of the cells, box and nozzles (its {@code stamp} column); empty where it has none. */
    static String digestOf(String line) {
        int at = line.indexOf(" | stamp "), end = line.indexOf(" | pipes", Math.max(at, 0));
        return at < 0 || end < 0 ? "" : line.substring(at + " | stamp ".length(), end);
    }

    /** A table line's layout revision: 1 where the line names none. */
    static int revisionOf(String line) {
        int at = line.indexOf(REVISION);
        return at < 0 ? 1 : Integer.parseInt(line.substring(at + REVISION.length()).trim());
    }

    /**
     * The recorded shapes whose cells, box or nozzles changed while their layout revision stayed, or whose revision went
     * back: each named with its kind, the revisions and the old and new lines.
     */
    static List<String> unbumped(List<String> recorded, List<EquipmentShape> shapes, List<String> lines) {
        var before = new HashMap<String, String>();
        for (var line : recorded) before.put(shapeOf(line), line);
        var out = new ArrayList<String>();
        for (int k = 0; k < lines.size(); k++) {
            var line = lines.get(k);var old = before.get(shapeOf(line));
            if (old == null) continue;
            int was = revisionOf(old), now = revisionOf(line);
            if (now < was || now == was && !digestOf(old).equals(digestOf(line)))
                out.add(shapes.get(k).kind() + " at layout revision " + now + " (recorded " + was + "):\n- " + old + "\n+ " + line);
        }
        return out;
    }

    @Test
    void everyLayoutIsTheRecordedOne() throws IOException {
        var shapes = shapes();
        var lines = shapes.stream().map(EquipmentLayoutGoldenTest::line).toList();
        assertEquals(lines.size(), new HashSet<>(lines).size(), "two recorded shapes are one");
        var written = Path.of("build/compat/layouts/layouts.txt");
        if (!Files.exists(TABLE)) {
            Files.createDirectories(written.getParent());Files.write(written, lines, StandardCharsets.UTF_8);
            fail("No layout table: copy " + written.toAbsolutePath() + " to " + TABLE);
        }
        var recorded = Files.readAllLines(TABLE, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank()).toList();
        if (recorded.equals(lines)) return;
        var unbumped = unbumped(recorded, shapes, lines);
        if (!unbumped.isEmpty()) {
            // No table is offered to copy: with the old revision, every structure of the design placed by the old layout
            // would read as current and keep its old cells.
            Files.deleteIfExists(written);
            fail("Layouts changed while their layout revision did not rise (raise the design's layoutRevision in its Sizes, or "
                    + "ShapeRasterizer.DRUM_LAYOUT_REVISION or COLUMN_LAYOUT_REVISION, so its placed structures refresh; a revision "
                    + "never goes back; then run again for the new table):\n" + String.join("\n", unbumped.subList(0, Math.min(10, unbumped.size()))));
        }
        Files.createDirectories(written.getParent());Files.write(written, lines, StandardCharsets.UTF_8);
        var changes = new ArrayList<String>();
        var before = new LinkedHashSet<>(recorded);var after = new LinkedHashSet<>(lines);
        for (var line : recorded) if (!after.contains(line)) changes.add("- " + line);
        for (var line : lines) if (!before.contains(line)) changes.add("+ " + line);
        fail("Layouts changed (a placed vessel of each refreshes; review the cells and pipes, then copy " + written.toAbsolutePath() + " to " + TABLE + "):\n"
                + String.join("\n", changes.subList(0, Math.min(40, changes.size()))));
    }
}
