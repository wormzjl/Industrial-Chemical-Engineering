package com.wormzjl.icheme.science.equipment;

import java.util.TreeMap;

/** A unit's drawn polygons: chunk (shell cells) plus moving parts. Args: "KIND size length support"... */
public class UnitCount {
    public static void main(String[] args) {
        for (var arg : args) {
            var p = arg.split(" ");
            var shape = EquipmentShape.unit(EquipmentShape.Kind.valueOf(p[0]), Double.parseDouble(p[1]), Integer.parseInt(p[2]), EquipmentShape.Heading.NORTH, Integer.parseInt(p[3]));
            var fixed = new TreeMap<String, Integer>();
            int moving = 0;
            for (var cell : ShapeRasterizer.layout(shape).shell())
                for (var polygon : ShapeRasterizer.chunkSurface(ShapeRasterizer.key(shape, cell.x(), cell.y(), cell.z()))) fixed.merge(polygon.material().name(), 1, Integer::sum);
            for (var part : ShapeRasterizer.moving(shape)) moving += part.polygons().size();
            int a = fixed.values().stream().mapToInt(Integer::intValue).sum();
            var terminal = new StringBuilder();
            for (var t : ShapeRasterizer.shafts(shape)) terminal.append(" shaft=").append(t.block());
            System.out.println(arg + ": box " + shape.sizeX() + "x" + shape.sizeY() + "x" + shape.sizeZ() + terminal + " fixed " + a + " moving " + moving + " total " + (a + moving));
        }
    }
}
