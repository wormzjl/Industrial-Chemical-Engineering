package com.wormzjl.icheme.science.equipment;

import com.wormzjl.icheme.science.equipment.EquipmentShape.Heading;
import com.wormzjl.icheme.science.equipment.EquipmentShape.Kind;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Probe (batch 2026-11-03-rotating-machinery, W4; not a gate): every pump, reciprocating and centrifugal compressor
 * and steam turbine size, second size and support, with its pipe blocks in the box, written to
 * {@code tmp/rotating-w4/probe-new.txt}, to compare the casings' nozzles before and after the drivers left them
 * ({@code tools/machine-nozzle-probe/README.md}).
 */
class NozzleProbe {
    @Test
    void dump() throws IOException {
        var lines = new ArrayList<String>();
        for (var kind : List.of(Kind.PUMP, Kind.COMPRESSOR, Kind.CENTRIFUGAL_COMPRESSOR, Kind.STEAM_TURBINE))
            for (double d : ProcessUnit.sizes(kind))
                for (int length = ProcessUnit.minLength(kind, d); length <= ProcessUnit.maxLength(kind, d); length += UnitDesign.of(kind).lengthStep())
                    for (int h = 0; h <= ProcessUnit.maxSupport(kind); h++) {
                        EquipmentShape shape;
                        try {
                            shape = EquipmentShape.unit(kind, d, length, Heading.NORTH, h);
                        } catch (IllegalArgumentException refused) {
                            continue;
                        }
                        var b = new StringBuilder(kind + " " + d + " " + length + " " + h + " |");
                        for (var port : ShapeRasterizer.ports(shape))
                            b.append(' ').append(shape.nozzles().get(port.nozzle()).role()).append('=').append(port.cell().x() + port.dx()).append(',').append(port.cell().y() + port.dy()).append(',').append(port.cell().z() + port.dz());
                        lines.add(b.toString());
                    }
        var out = Path.of("tmp/rotating-w4/probe-new.txt");
        Files.write(out, lines);
    }
}
