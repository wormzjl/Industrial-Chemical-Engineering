package com.wormzjl.icheme.science.equipment;

import java.nio.file.*;
import java.util.*;

public class ShaftSnapshot {
    public static void main(String[] args) throws Exception {
        var lines = new ArrayList<String>();
        for (double bore : new double[]{.15, .5, 1.2}) {
            var kind = EquipmentShape.Kind.COMPRESSOR;
            int length = ProcessUnit.defaultLength(kind, bore), support = ProcessUnit.defaultSupport(kind, bore, length);
            var shape = EquipmentShape.unit(kind, bore, length, EquipmentShape.Heading.NORTH, support);
            var end = shape.shaftEnds().getFirst();
            lines.add(bore + " shaft=" + Arrays.toString(end.face()) + " block=" + Arrays.toString(end.shaftBlock())
                    + " box=" + shape.sizeX() + "," + shape.sizeY() + "," + shape.sizeZ()
                    + " pipes=" + ShapeRasterizer.ports(shape));
            long guards = ProcessUnit.of(shape).mesh().facets().stream().filter(f -> f.material() == Mesh.Material.COUPLING_GUARD).count();
            System.out.println(bore + " guard facets=" + guards);
        }
        Files.write(Path.of(args[0]), lines);
    }
}
