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
 * smallest, middle and largest size, a checked-in table holds the box, the cell counts, the layout stamp and each
 * nozzle's pipe block. A layout change fails this test until the table is updated with it, so the review sees what a
 * placed vessel's refresh does: cells added and dropped, and nozzles moved (a pipe that can part; a moved nozzle needs
 * the owner's approval).
 */
class EquipmentLayoutGoldenTest {
    private static final Path TABLE = Path.of("src/test/resources/compat/layouts/layouts.txt");

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
            double low = ProcessUnit.minDiameter(kind), high = ProcessUnit.maxDiameter(kind);
            for (double diameter : new double[]{low, ProcessUnit.snap(kind, (low + high) / 2), high}) {
                int length = ProcessUnit.defaultLength(kind, diameter);
                shapes.add(EquipmentShape.designed(kind, diameter, length, Heading.NORTH, ProcessUnit.defaultSupport(kind, diameter, length), ProcessUnit.Bank.ONE, 0));
            }
        }
        for (double diameter : new double[]{.5, 1.5, 2.5}) {
            int length = ProcessUnit.defaultLength(Kind.EXCHANGER, diameter);
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
                .append(" | stamp ").append(Long.toHexString(ShapeRasterizer.stamp(shape))).append(" | pipes");
        for (var nozzle : ShapeRasterizer.ports(shape))
            b.append(' ').append(nozzle.cell().x() + nozzle.dx()).append(',').append(nozzle.cell().y() + nozzle.dy()).append(',').append(nozzle.cell().z() + nozzle.dz());
        // An electric machine's or fan unit's terminal box, its FE port (W5): an existing cell, recorded where there is one.
        var terminal = ShapeRasterizer.terminal(shape);
        if (terminal != null) b.append(" | terminal ").append(terminal.x()).append(',').append(terminal.y()).append(',').append(terminal.z());
        return b.toString();
    }

    @Test
    void everyLayoutIsTheRecordedOne() throws IOException {
        var lines = shapes().stream().map(EquipmentLayoutGoldenTest::line).toList();
        assertEquals(lines.size(), new HashSet<>(lines).size(), "two recorded shapes are one");
        var written = Path.of("build/compat/layouts/layouts.txt");
        if (!Files.exists(TABLE)) {
            Files.createDirectories(written.getParent());Files.write(written, lines, StandardCharsets.UTF_8);
            fail("No layout table: copy " + written.toAbsolutePath() + " to " + TABLE);
        }
        var recorded = Files.readAllLines(TABLE, StandardCharsets.UTF_8).stream().filter(l -> !l.isBlank()).toList();
        if (recorded.equals(lines)) return;
        Files.createDirectories(written.getParent());Files.write(written, lines, StandardCharsets.UTF_8);
        var changes = new ArrayList<String>();
        var before = new LinkedHashSet<>(recorded);var after = new LinkedHashSet<>(lines);
        for (var line : recorded) if (!after.contains(line)) changes.add("- " + line);
        for (var line : lines) if (!before.contains(line)) changes.add("+ " + line);
        fail("Layouts changed (a placed vessel of each refreshes; review the cells and pipes, then copy " + written.toAbsolutePath() + " to " + TABLE + "):\n"
                + String.join("\n", changes.subList(0, Math.min(40, changes.size()))));
    }
}
